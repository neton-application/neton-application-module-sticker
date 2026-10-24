use image::{Delay, Frame, ImageFormat, Rgba, RgbaImage};
use privchat_sticker::{
    archive,
    convert::{self, ConvertOptions},
    import::{self, ImportOptions},
    media,
    model::Manifest,
};
use serde_json::json;
use std::{
    fs,
    io::{Cursor, Write},
    path::Path,
    process::Command,
};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

fn png(path: &Path, w: u32, h: u32, color: [u8; 4]) {
    RgbaImage::from_pixel(w, h, Rgba(color))
        .save_with_format(path, ImageFormat::Png)
        .unwrap();
}
fn manifest(file: &str, w: u32, h: u32) -> serde_json::Value {
    json!({"schema_version":1,"pack_id":"test-pack","name":{"en":"Test"},"author":"Test author","version":1,"cover":file,"stickers":[{"key":"smile","file":file,"emoji":"🙂","width":w,"height":h}]})
}
fn write_manifest(dir: &Path, value: &serde_json::Value) {
    fs::write(
        dir.join("manifest.json"),
        serde_json::to_vec(value).unwrap(),
    )
    .unwrap();
}
fn zip(entries: &[(&str, &[u8])], method: CompressionMethod) -> Vec<u8> {
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    for (name, data) in entries {
        writer
            .start_file(
                *name,
                SimpleFileOptions::default().compression_method(method),
            )
            .unwrap();
        writer.write_all(data).unwrap();
    }
    writer.finish().unwrap().into_inner()
}

#[test]
fn static_conversion_geometry_alpha_and_lossy_opt_in() {
    let dir = tempfile::tempdir().unwrap();
    for (i, (w, h, ow, oh)) in [
        (240, 240, 240, 240),
        (320, 180, 320, 180),
        (512, 256, 512, 256),
        (1024, 512, 512, 256),
        (1, 513, 1, 512),
    ]
    .into_iter()
    .enumerate()
    {
        let src = dir.path().join(format!("input{i}.png"));
        let out = dir.path().join(format!("output{i}.webp"));
        png(&src, w, h, [80, 120, 200, 70]);
        let report = convert::convert(&src, &out, &ConvertOptions::default()).unwrap();
        assert_eq!((report.media.width, report.media.height), (ow, oh));
        assert_eq!(report.options.quality, None);
        assert_eq!(
            image::open(&out).unwrap().to_rgba8().get_pixel(0, 0).0[3],
            70
        );
    }
    let report = convert::convert(
        &dir.path().join("input0.png"),
        &dir.path().join("lossy.webp"),
        &ConvertOptions {
            quality: Some(75),
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(report.options.quality, Some(75));
    convert::convert(
        &dir.path().join("input0.png"),
        &dir.path().join("compatible.png"),
        &ConvertOptions::default(),
    )
    .unwrap();
}

#[test]
fn real_gif_animated_webp_roundtrip_and_explicit_sampling() {
    let dir = tempfile::tempdir().unwrap();
    let gif = dir.path().join("input.gif");
    let mut encoder = image::codecs::gif::GifEncoder::new(fs::File::create(&gif).unwrap());
    for color in [[255, 0, 0, 255], [0, 0, 255, 255]] {
        encoder
            .encode_frame(Frame::from_parts(
                RgbaImage::from_pixel(80, 40, Rgba(color)),
                0,
                0,
                Delay::from_numer_denom_ms(80, 1),
            ))
            .unwrap();
    }
    drop(encoder);
    let out = dir.path().join("animated.webp");
    let report = convert::convert(
        &gif,
        &out,
        &ConvertOptions {
            loops: 2,
            ..Default::default()
        },
    )
    .unwrap();
    assert!(report.media.frames >= 2);
    assert_eq!(report.media.duration_ms, 160);
    assert!(report.animation_sampling.unwrap().contains("25fps"));
    let decoder =
        image::codecs::webp::WebPDecoder::new(Cursor::new(fs::read(&out).unwrap())).unwrap();
    use image::AnimationDecoder;
    assert!(matches!(decoder.loop_count(),image::metadata::LoopCount::Finite(n) if n.get()==2));
    let decoded = decoder.into_frames().collect_frames().unwrap();
    assert_ne!(
        decoded[0].buffer().get_pixel(0, 0),
        decoded.last().unwrap().buffer().get_pixel(0, 0)
    );
    convert::convert(
        &out,
        &dir.path().join("again.webp"),
        &ConvertOptions::default(),
    )
    .unwrap();
    assert!(convert::convert(
        &gif,
        &dir.path().join("wrong.png"),
        &ConvertOptions::default()
    )
    .is_err());
}

fn animated_fixture(dir: &Path, name: &str, duration: u32, count: usize) -> Vec<u8> {
    let red = dir.join("red.png");
    let blue = dir.join("blue.png");
    png(&red, 16, 8, [255, 0, 0, 255]);
    png(&blue, 16, 8, [0, 0, 255, 255]);
    let output = dir.join(name);
    let mut cmd = Command::new("img2webp");
    cmd.args(["-loop", "0"]);
    for i in 0..count {
        cmd.args(["-d", &duration.to_string(), "-lossless"])
            .arg(if i % 2 == 0 { &red } else { &blue });
    }
    cmd.arg("-o").arg(&output);
    convert::run(&mut cmd, dir).unwrap();
    fs::read(output).unwrap()
}

#[test]
fn validate_real_timing_boundaries_not_metadata_alone() {
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(
        media::validate(&animated_fixture(dir.path(), "valid.webp", 1500, 2))
            .unwrap()
            .duration_ms,
        3000
    );
    assert!(media::validate(&animated_fixture(dir.path(), "short.webp", 33, 2)).is_err());
    assert!(media::validate(&animated_fixture(dir.path(), "long.webp", 1501, 2)).is_err());
    assert!(
        media::validate(&animated_fixture(dir.path(), "ninety.webp", 34, 90)).is_err(),
        "90*34=3060 is NOT valid"
    );
    assert!(media::validate(&animated_fixture(dir.path(), "many.webp", 34, 91)).is_err());
}

#[test]
fn png_real_decode_corruption_geometry_and_size() {
    let dir = tempfile::tempdir().unwrap();
    let p = dir.path().join("test.png");
    png(&p, 32, 16, [1, 2, 3, 200]);
    let bytes = fs::read(&p).unwrap();
    assert!(media::validate(&bytes).is_ok());
    let mut broken = bytes.clone();
    let position = broken.len() / 2;
    broken[position] ^= 0xff;
    assert!(media::validate(&broken).is_err());
    png(&p, 513, 1, [1, 2, 3, 255]);
    assert!(media::validate(&fs::read(&p).unwrap()).is_err());
    assert!(media::validate(&vec![0; 500 * 1024 + 1]).is_err());
    let mut random = RgbaImage::new(512, 512);
    let mut seed = 123u32;
    for pixel in random.pixels_mut() {
        for byte in &mut pixel.0 {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            *byte = (seed >> 24) as u8;
        }
    }
    random.save(&p).unwrap();
    let out = dir.path().join("oversized.webp");
    assert!(convert::convert(&p, &out, &ConvertOptions::default()).is_err());
    assert!(
        !out.exists(),
        "oversized lossless output must fail, not auto-retry lossy"
    );
}

#[test]
fn native_pack_legacy_schema_and_import_roundtrip() {
    let dir = tempfile::tempdir().unwrap();
    let assets = dir.path().join("assets");
    fs::create_dir(&assets).unwrap();
    png(&assets.join("a.png"), 32, 16, [1, 2, 3, 255]);
    let mut m = manifest("a.png", 32, 16);
    m.as_object_mut().unwrap().remove("schema_version");
    write_manifest(&assets, &m);
    let packed = dir.path().join("pack.zip");
    archive::pack(&assets, &packed).unwrap();
    assert_eq!(
        archive::read_zip(&packed).unwrap().manifest.schema_version,
        1
    );
    let out = dir.path().join("imported");
    import::import(&packed, &out, &ImportOptions::default()).unwrap();
    assert_eq!(
        fs::read(out.join("a.png")).unwrap(),
        fs::read(assets.join("a.png")).unwrap()
    );
    assert!(import::sidecar_path(&out).unwrap().is_file());
    assert_eq!(fs::read_dir(out).unwrap().count(), 2);
    let mut m = manifest("a.png", 32, 16);
    m["schema_version"] = json!(2);
    write_manifest(&assets, &m);
    assert!(archive::read_directory(&assets).is_err());
}

#[test]
fn zip_rejects_extras_traversal_duplicates_symlinks_zip64_and_header_mismatch() {
    let dir = tempfile::tempdir().unwrap();
    let p = dir.path().join("a.png");
    png(&p, 1, 1, [1, 2, 3, 255]);
    let image = fs::read(&p).unwrap();
    let m = serde_json::to_vec(&manifest("a.png", 1, 1)).unwrap();
    let out = dir.path().join("input.zip");
    let good = zip(
        &[("manifest.json", &m), ("a.png", &image)],
        CompressionMethod::Deflated,
    );
    fs::write(&out, &good).unwrap();
    archive::read_zip(&out).unwrap();
    for entries in [
        vec![
            ("manifest.json", m.as_slice()),
            ("a.png", image.as_slice()),
            ("LICENSE", b"license".as_slice()),
        ],
        vec![
            ("manifest.json", m.as_slice()),
            ("../a.png", image.as_slice()),
        ],
    ] {
        fs::write(&out, zip(&entries, CompressionMethod::Stored)).unwrap();
        assert!(archive::read_zip(&out).is_err());
    }
    let mut duplicate = zip(
        &[("manifest.json", &m), ("a.png", &image), ("b.png", &image)],
        CompressionMethod::Stored,
    );
    for i in 0..duplicate.len() - 5 {
        if &duplicate[i..i + 5] == b"b.png" {
            duplicate[i] = b'a';
        }
    }
    fs::write(&out, duplicate).unwrap();
    assert!(archive::read_zip(&out).is_err());
    let mut zip64 = good.clone();
    let end = zip64.len() - 22;
    zip64[end + 10..end + 12].copy_from_slice(&u16::MAX.to_le_bytes());
    fs::write(&out, zip64).unwrap();
    assert!(archive::read_zip(&out).is_err());
    let mut mismatched = good.clone();
    mismatched[30] = b'X';
    fs::write(&out, mismatched).unwrap();
    assert!(archive::read_zip(&out).is_err());
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    writer
        .start_file("manifest.json", SimpleFileOptions::default())
        .unwrap();
    writer.write_all(&m).unwrap();
    writer
        .add_symlink("a.png", "target", SimpleFileOptions::default())
        .unwrap();
    fs::write(&out, writer.finish().unwrap().into_inner()).unwrap();
    assert!(archive::read_zip(&out).is_err());
}

#[test]
fn manifest_exact_keys_utf16_and_real_geometry() {
    let mut m = manifest("a.png", 1, 1);
    m["stickers"][0]["emoji"] = json!("🙂".repeat(40));
    assert!(serde_json::from_value::<Manifest>(m)
        .unwrap()
        .validate()
        .is_err());
    let mut m = manifest("a.png", 1, 1);
    m["surprise"] = json!(true);
    assert!(serde_json::from_value::<Manifest>(m).is_err());
    let dir = tempfile::tempdir().unwrap();
    png(&dir.path().join("a.png"), 2, 1, [1, 2, 3, 255]);
    write_manifest(dir.path(), &manifest("a.png", 1, 1));
    assert!(archive::read_directory(dir.path()).is_err());
}

#[test]
fn failed_conversion_never_clobbers_output_or_leaves_import_directory() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("input.png");
    png(&src, 16, 8, [1, 2, 3, 255]);
    let output = dir.path().join("out.webp");
    fs::write(&output, b"keep me").unwrap();
    assert!(convert::convert(&src, &output, &ConvertOptions::default()).is_err());
    assert_eq!(fs::read(&output).unwrap(), b"keep me");
    let tgs = dir.path().join("fake.tgs");
    fs::write(&tgs, b"{}").unwrap();
    let error = convert::convert(
        &tgs,
        &dir.path().join("missing.webp"),
        &ConvertOptions::default(),
    )
    .unwrap_err()
    .to_string();
    assert!(error.contains("renderer"));
    let out = dir.path().join("imported");
    assert!(import::import(
        dir.path(),
        &out,
        &ImportOptions {
            pack_id: Some("test".into()),
            name: Some("Test".into()),
            author: Some("Author".into()),
            version: 1,
            ..Default::default()
        }
    )
    .is_err());
    assert!(!out.exists());
    assert!(!import::sidecar_path(&out).unwrap().exists());
    let status = Command::new(env!("CARGO_BIN_EXE_privchat-sticker"))
        .env("PATH", "")
        .args(["convert"])
        .arg(&src)
        .arg("--output")
        .arg(dir.path().join("dependency.webp"))
        .output()
        .unwrap();
    assert!(!status.status.success());
    assert!(String::from_utf8_lossy(&status.stderr).contains("cwebp"));
}

#[test]
fn whatsapp_reordering_preserves_keys_and_external_provenance() {
    let dir = tempfile::tempdir().unwrap();
    let src = dir.path().join("source");
    fs::create_dir(&src).unwrap();
    for file in ["smile.png", "你好.png", "cover.png"] {
        png(&src.join(file), 16, 8, [1, 2, 3, 255]);
    }
    let license = dir.path().join("LICENSE");
    fs::write(&license, "Test license; retain this text").unwrap();
    let mut keys = Vec::new();
    for (i, files) in [["smile.png", "你好.png"], ["你好.png", "smile.png"]]
        .into_iter()
        .enumerate()
    {
        fs::write(src.join("contents.json"),serde_json::to_vec(&json!({"sticker_packs":[{"identifier":"cats","name":"Cats","publisher":"Author","tray_image_file":"cover.png","stickers":files.map(|file|json!({"image_file":file,"emojis":["🙂"]}))}]})).unwrap()).unwrap();
        let out = dir.path().join(format!("import{i}"));
        import::import(
            &src,
            &out,
            &ImportOptions {
                version: 1,
                license: Some(license.clone()),
                ..Default::default()
            },
        )
        .unwrap();
        let mut current: Vec<_> = archive::read_directory(&out)
            .unwrap()
            .manifest
            .stickers
            .into_iter()
            .map(|s| s.key)
            .collect();
        current.sort();
        keys.push(current);
        let sidecar: serde_json::Value =
            serde_json::from_slice(&fs::read(import::sidecar_path(&out).unwrap()).unwrap())
                .unwrap();
        assert_eq!(sidecar["license"]["text"], "Test license; retain this text");
        assert!(sidecar["files"][0]["source_sha256"].as_str().unwrap().len() == 64);
    }
    assert_eq!(keys[0], keys[1]);
    assert_eq!(
        import::source_key("你好.png"),
        import::source_key("你好.png")
    );
}

#[test]
fn real_apng_webm_and_audio_policy() {
    let dir = tempfile::tempdir().unwrap();
    png(&dir.path().join("f01.png"), 80, 40, [255, 0, 0, 255]);
    png(&dir.path().join("f02.png"), 80, 40, [0, 0, 255, 255]);
    for (name, extra) in [
        ("input.apng", vec!["-plays", "0", "-f", "apng"]),
        (
            "input.webm",
            vec!["-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p"],
        ),
    ] {
        let input = dir.path().join(name);
        let mut cmd = Command::new("ffmpeg");
        cmd.args(["-v", "error", "-framerate", "10", "-i"])
            .arg(dir.path().join("f%02d.png"))
            .args(extra)
            .arg(&input);
        convert::run(&mut cmd, dir.path()).unwrap();
        let report = convert::convert(
            &input,
            &dir.path().join(format!("{name}.webp")),
            &ConvertOptions::default(),
        )
        .unwrap();
        assert!(report.media.frames >= 2);
        assert_eq!((report.media.width, report.media.height), (80, 40));
    }
    let audio = dir.path().join("audio.webm");
    convert::run(
        Command::new("ffmpeg")
            .args([
                "-v",
                "error",
                "-f",
                "lavfi",
                "-i",
                "color=c=red:s=80x40:r=10:d=0.2",
                "-f",
                "lavfi",
                "-i",
                "sine=duration=0.2",
                "-c:v",
                "libvpx-vp9",
                "-c:a",
                "libopus",
                "-shortest",
            ])
            .arg(&audio),
        dir.path(),
    )
    .unwrap();
    let out = dir.path().join("audio.webp");
    assert!(convert::convert(&audio, &out, &ConvertOptions::default())
        .unwrap_err()
        .to_string()
        .contains("audio"));
    assert!(!out.exists());
    convert::convert(
        &audio,
        &out,
        &ConvertOptions {
            drop_audio: true,
            ..Default::default()
        },
    )
    .unwrap();
}
