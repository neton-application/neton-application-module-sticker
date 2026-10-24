use crate::{
    archive, bounded_read,
    convert::{self, ConvertOptions},
    ensure,
    model::{self, Manifest, Sticker},
    output_parent, Result, MAX_MANIFEST,
};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs,
    io::Write,
    path::{Path, PathBuf},
    process::Command,
};

#[derive(Default)]
pub struct ImportOptions {
    pub pack_id: Option<String>,
    pub name: Option<String>,
    pub author: Option<String>,
    pub version: i32,
    pub select: Option<String>,
    pub source_url: Option<String>,
    pub license: Option<PathBuf>,
    pub conversion: ConvertOptions,
}

#[derive(Deserialize)]
struct Contents {
    sticker_packs: Vec<WaPack>,
}
#[derive(Deserialize)]
struct WaPack {
    identifier: String,
    name: String,
    publisher: String,
    tray_image_file: String,
    stickers: Vec<WaSticker>,
}
#[derive(Deserialize)]
struct WaSticker {
    image_file: String,
    #[serde(default)]
    emojis: Vec<String>,
}

fn digest(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

pub fn source_key(filename: &str) -> String {
    let stem = Path::new(filename)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("");
    if model::identifier(stem) {
        stem.to_owned()
    } else {
        format!("source_{}", &digest(filename.as_bytes())[..24])
    }
}

pub fn sidecar_path(output: &Path) -> Result<PathBuf> {
    let mut name = output
        .file_name()
        .ok_or("output needs a basename")?
        .to_os_string();
    name.push(".provenance.json");
    Ok(output.with_file_name(name))
}

fn version_record(work: &Path) -> BTreeMap<String, String> {
    [
        ("cwebp", "-version"),
        ("img2webp", "-version"),
        ("ffmpeg", "-version"),
        ("ffprobe", "-version"),
    ]
    .into_iter()
    .map(|(name, arg)| {
        let value = convert::run(Command::new(name).arg(arg), work)
            .map(|bytes| {
                String::from_utf8_lossy(&bytes)
                    .lines()
                    .next()
                    .unwrap_or("")
                    .to_owned()
            })
            .unwrap_or_else(|e| format!("unavailable: {e}"));
        (name.to_owned(), value)
    })
    .collect()
}

fn foreign_file(root: &Path, relative: &Path) -> Result<PathBuf> {
    let path = root.join(relative);
    ensure(
        fs::canonicalize(&path)?.starts_with(root),
        "source file escapes import directory",
    )?;
    Ok(path)
}

pub fn import(input: &Path, output: &Path, options: &ImportOptions) -> Result<Value> {
    let parent = output_parent(output)?;
    let sidecar = sidecar_path(output)?;
    output_parent(&sidecar)?;
    let input = fs::canonicalize(input)?;
    let work = tempfile::tempdir_in(parent)?;
    let assets = work.path().join("assets");
    fs::create_dir(&assets)?;
    let mut mappings = Vec::new();
    let manifest;
    let source_kind;
    let source_sha256;
    if input.is_file() || input.join("manifest.json").exists() {
        let bundle = archive::read(&input)?;
        source_kind = "native-v1";
        source_sha256 = if input.is_file() {
            Some(digest(&bounded_read(&input, crate::MAX_PACKAGE)?))
        } else {
            None
        };
        manifest = bundle.manifest;
        for (name, bytes) in bundle.files {
            if name != "manifest.json" {
                mappings.push(json!({"source":name,"source_sha256":digest(&bytes),"output":name,"output_sha256":digest(&bytes),"conversion":"byte-preserved"}));
            }
            fs::write(assets.join(name), bytes)?;
        }
    } else {
        ensure(
            input.is_dir(),
            "foreign import expects a local directory, not a remote URL or generic third-party ZIP",
        )?;
        let mut files: Vec<(String, PathBuf, String)> = Vec::new();
        let pack_id;
        let name;
        let author;
        let mut cover_source = None;
        if input.join("contents.json").exists() {
            source_kind = "whatsapp-local-contents";
            let bytes = bounded_read(&input.join("contents.json"), MAX_MANIFEST)?;
            source_sha256 = Some(digest(&bytes));
            let contents: Contents = serde_json::from_slice(&bytes)?;
            let selected: Vec<_> = contents
                .sticker_packs
                .into_iter()
                .filter(|p| options.select.as_ref().is_none_or(|s| s == &p.identifier))
                .collect();
            ensure(
                selected.len() == 1,
                "contents.json needs exactly one selected pack; use --select IDENTIFIER",
            )?;
            let pack = selected.into_iter().next().unwrap();
            ensure(
                model::identifier(&pack.identifier),
                "unsafe WhatsApp identifier",
            )?;
            pack_id = options.pack_id.clone().unwrap_or(pack.identifier.clone());
            name = options.name.clone().unwrap_or(pack.name);
            author = options.author.clone().unwrap_or(pack.publisher);
            let locate = |file: &str| -> Result<PathBuf> {
                ensure(
                    !file.is_empty()
                        && file.len() <= 255
                        && file != "."
                        && file != ".."
                        && !file.contains(['/', '\\'])
                        && !file.chars().any(char::is_control),
                    "unsafe WhatsApp source filename",
                )?;
                let nested = Path::new(&pack.identifier).join(file);
                foreign_file(
                    &input,
                    if input.join(&nested).is_file() {
                        &nested
                    } else {
                        Path::new(file)
                    },
                )
            };
            cover_source = Some(locate(&pack.tray_image_file)?);
            for sticker in pack.stickers {
                files.push((
                    source_key(&sticker.image_file),
                    locate(&sticker.image_file)?,
                    sticker.emojis.join(""),
                ));
            }
        } else {
            source_kind = "local-telegram-style-file-collection";
            source_sha256 = None;
            pack_id = options
                .pack_id
                .clone()
                .ok_or("file collection requires --pack-id")?;
            name = options
                .name
                .clone()
                .ok_or("file collection requires --name")?;
            author = options
                .author
                .clone()
                .ok_or("file collection requires --author")?;
            let mut paths = Vec::new();
            for entry in fs::read_dir(&input)? {
                let entry = entry?;
                let ext = entry
                    .path()
                    .extension()
                    .and_then(|s| s.to_str())
                    .unwrap_or("")
                    .to_ascii_lowercase();
                if ["png", "webp", "gif", "apng", "webm", "tgs"].contains(&ext.as_str()) {
                    ensure(
                        entry.file_type()?.is_file(),
                        "source assets must be regular non-symlink files",
                    )?;
                    paths.push(entry.path());
                }
            }
            paths.sort();
            for path in paths {
                let key = source_key(
                    path.file_name()
                        .and_then(|s| s.to_str())
                        .ok_or("non-UTF8 source filename")?,
                );
                files.push((key, path, String::new()));
            }
        }
        ensure(
            (1..=256).contains(&files.len()),
            "import expects 1..256 source stickers",
        )?;
        let mut stickers = Vec::new();
        let mut keys = std::collections::BTreeSet::new();
        for (key, path, emoji) in files {
            ensure(
                keys.insert(key.clone()),
                "duplicate imported key; rename colliding source stems",
            )?;
            let file = format!("{key}.webp");
            let source = bounded_read(&path, 64 * 1024 * 1024)?;
            let report = convert::convert(&path, &assets.join(&file), &options.conversion)?;
            mappings.push(json!({"source":path.strip_prefix(&input)?,"source_sha256":digest(&source),"output":file,"output_sha256":digest(&bounded_read(&assets.join(&file),crate::MAX_IMAGE)?),"conversion":report}));
            stickers.push(Sticker {
                key,
                file,
                emoji,
                width: report.media.width,
                height: report.media.height,
            });
        }
        let cover = if let Some(path) = cover_source {
            let cover = "pack-cover.webp".to_owned();
            ensure(
                !stickers.iter().any(|s| s.file == cover),
                "cover filename collides with sticker",
            )?;
            let report = convert::convert(&path, &assets.join(&cover), &options.conversion)?;
            mappings.push(json!({"source":path.strip_prefix(&input)?,"source_sha256":digest(&bounded_read(&path,64*1024*1024)?),"output":cover,"output_sha256":digest(&bounded_read(&assets.join(&cover),crate::MAX_IMAGE)?),"conversion":report}));
            cover
        } else {
            stickers[0].file.clone()
        };
        manifest = Manifest {
            schema_version: 1,
            pack_id,
            name: BTreeMap::from([("en".into(), name)]),
            author,
            version: options.version,
            cover,
            stickers,
        };
    }
    manifest.validate()?;
    fs::write(
        assets.join("manifest.json"),
        serde_json::to_vec_pretty(&manifest)?,
    )?;
    archive::read_directory(&assets)?;
    let license = options
        .license
        .as_ref()
        .map(|p| {
            bounded_read(p, MAX_MANIFEST).and_then(|b| {
                Ok(json!({"path":p,"sha256":digest(&b),"text":String::from_utf8(b)?}))
            })
        })
        .transpose()?;
    let record = json!({"tool":"privchat-sticker","tool_version":env!("CARGO_PKG_VERSION"),"source_kind":source_kind,"source_path":input,"source_sha256":source_sha256,"source_url":options.source_url,"author":manifest.author,"pack_id":manifest.pack_id,"version":manifest.version,"codec_versions":version_record(work.path()),"files":mappings,"license":license,"requires_release_review":true,"warning":"Local evidence only: backend DTO does not persist provenance/license/attribution; do not publish attribution-required assets before that chain is implemented."});
    let mut record_file = tempfile::NamedTempFile::new_in(parent)?;
    record_file.write_all(&serde_json::to_vec_pretty(&record)?)?;
    record_file.as_file().sync_all()?;
    // Reserve only after every conversion and validation succeeded. No existing
    // destination can be replaced; rename only our newly-created empty directory.
    fs::create_dir(output)?;
    if let Err(error) = fs::rename(&assets, output) {
        fs::remove_dir(output)?;
        return Err(error.into());
    }
    record_file.persist_noclobber(&sidecar)?;
    Ok(
        json!({"output":output,"provenance":sidecar,"stickers":manifest.stickers.len(),"source_kind":source_kind}),
    )
}
