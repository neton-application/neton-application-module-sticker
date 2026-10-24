use privchat_sticker::{
    archive, bounded_read,
    convert::{self, ConvertOptions},
    ensure,
    import::{self, ImportOptions},
    media, Result,
};
use std::{collections::BTreeMap, path::Path};

const HELP: &str = "privchat-sticker (independent local-only tool)
  convert INPUT --output FILE.webp|FILE.png [--quality 0..100] [--loop 0..65535] [--drop-audio]
  validate FILE.zip|NATIVE_DIRECTORY|FILE.png|FILE.webp
  pack NATIVE_DIRECTORY --output FILE.zip
  import FILE.zip|LOCAL_DIRECTORY --output NEW_DIRECTORY
         [--select WHATSAPP_ID] [--pack-id ID --name NAME --author AUTHOR] [--version N]
         [--source-url URL] [--license LOCAL_TEXT_FILE] [--quality 0..100] [--loop N] [--drop-audio]

Lossless by default; --quality explicitly opts into lossy WebP. No implicit quality retries.
Animation is explicitly resampled at 25fps/40ms (may extend <40ms, never beyond 3s).
Small images are not upscaled or padded. Outputs must not already exist.
TGS requires a real external renderer (adapter not installed); fails instead of faking support.
Import writes a provenance/license sidecar OUTSIDE the flat native package directory.
Import does not call admin APIs, publish assets, fetch URLs, or infer redistribution rights.";

fn execute() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.is_empty() || args.iter().any(|a| a == "--help" || a == "-h") {
        println!("{HELP}");
        return Ok(());
    }
    ensure(
        args.len() >= 2,
        "expected command and local input path; see --help",
    )?;
    let mut flags = BTreeMap::new();
    let mut i = 2;
    while i < args.len() {
        let key = args[i].as_str();
        ensure(key.starts_with("--"), "unexpected positional argument")?;
        let value = if key == "--drop-audio" {
            "true"
        } else {
            i += 1;
            args.get(i).ok_or("option requires a value")?.as_str()
        };
        ensure(flags.insert(key, value).is_none(), "duplicate option")?;
        i += 1;
    }
    let allowed: &[&str] = match args[0].as_str() {
        "convert" => &["--output", "--quality", "--loop", "--drop-audio"],
        "import" => &[
            "--output",
            "--quality",
            "--loop",
            "--drop-audio",
            "--pack-id",
            "--name",
            "--author",
            "--version",
            "--select",
            "--source-url",
            "--license",
        ],
        "pack" => &["--output"],
        "validate" => &[],
        _ => return Err("unknown command; see --help".into()),
    };
    ensure(
        flags.keys().all(|k| allowed.contains(k)),
        "unknown option for this command",
    )?;
    let input = Path::new(&args[1]);
    let output = || -> Result<&Path> {
        Ok(Path::new(
            flags.get("--output").ok_or("--output is required")?,
        ))
    };
    let conversion = ConvertOptions {
        quality: flags.get("--quality").map(|s| s.parse()).transpose()?,
        loops: flags.get("--loop").unwrap_or(&"0").parse()?,
        drop_audio: flags.contains_key("--drop-audio"),
    };
    let report = match args[0].as_str() {
        "convert" => serde_json::to_value(convert::convert(input, output()?, &conversion)?)?,
        "pack" => {
            archive::pack(input, output()?)?;
            serde_json::json!({"valid":true,"output":output()?})
        }
        "validate" => {
            if input
                .extension()
                .and_then(|s| s.to_str())
                .is_some_and(|s| s.eq_ignore_ascii_case("png") || s.eq_ignore_ascii_case("webp"))
            {
                serde_json::to_value(media::validate(&bounded_read(
                    input,
                    privchat_sticker::MAX_IMAGE,
                )?)?)?
            } else {
                let bundle = archive::read(input)?;
                serde_json::json!({"valid":true,"pack_id":bundle.manifest.pack_id,"schema_version":bundle.manifest.schema_version,"stickers":bundle.manifest.stickers.len(),"files":bundle.files.len()})
            }
        }
        "import" => import::import(
            input,
            output()?,
            &ImportOptions {
                pack_id: flags.get("--pack-id").map(|s| s.to_string()),
                name: flags.get("--name").map(|s| s.to_string()),
                author: flags.get("--author").map(|s| s.to_string()),
                version: flags.get("--version").unwrap_or(&"1").parse()?,
                select: flags.get("--select").map(|s| s.to_string()),
                source_url: flags.get("--source-url").map(|s| s.to_string()),
                license: flags.get("--license").map(std::path::PathBuf::from),
                conversion,
            },
        )?,
        _ => unreachable!(),
    };
    println!("{}", serde_json::to_string_pretty(&report)?);
    Ok(())
}
fn main() {
    if let Err(error) = execute() {
        eprintln!("privchat-sticker: {error}");
        std::process::exit(1);
    }
}
