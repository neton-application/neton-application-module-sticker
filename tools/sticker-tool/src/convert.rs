use crate::{bounded_read, ensure, media, output_parent, publish_file, Result, MAX_IMAGE};
use image::{AnimationDecoder, DynamicImage, ImageDecoder, ImageFormat};
use std::{
    fs,
    io::Cursor,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

const MAX_INPUT: u64 = 64 * 1024 * 1024;

#[derive(Debug, Default, Clone, serde::Serialize)]
pub struct ConvertOptions {
    pub loops: u16,
    pub quality: Option<u8>,
    pub drop_audio: bool,
}

#[derive(Debug, serde::Serialize)]
pub struct ConversionReport {
    pub media: media::MediaInfo,
    pub options: ConvertOptions,
    pub animation_sampling: Option<String>,
}

pub fn run(command: &mut Command, work: &Path) -> Result<Vec<u8>> {
    let out = tempfile::NamedTempFile::new_in(work)?;
    let err = tempfile::NamedTempFile::new_in(work)?;
    let tool = command.get_program().to_string_lossy().into_owned();
    let mut child = command
        .stdin(Stdio::null())
        .stdout(out.reopen()?)
        .stderr(err.reopen()?)
        .spawn()
        .map_err(|e| format!("cannot start {tool}; install the required codec tool: {e}"))?;
    let started = Instant::now();
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if started.elapsed() > Duration::from_secs(90)
            || fs::metadata(out.path())?.len() > 1024 * 1024
            || fs::metadata(err.path())?.len() > 1024 * 1024
        {
            child.kill()?;
            child.wait()?;
            return Err(format!("{tool} exceeded 90s/1MiB diagnostic budget").into());
        }
        thread::sleep(Duration::from_millis(15));
    };
    ensure(
        status.success(),
        &format!(
            "{tool} failed: {}",
            String::from_utf8_lossy(&bounded_read(err.path(), 1024 * 1024)?)
        ),
    )?;
    bounded_read(out.path(), 1024 * 1024)
}

fn resized(image: DynamicImage) -> DynamicImage {
    if image.width() <= 512 && image.height() <= 512 {
        image
    } else {
        image.resize(512, 512, image::imageops::FilterType::Lanczos3)
    }
}

fn encode_static(png: &Path, work: &Path, quality: Option<u8>) -> Result<PathBuf> {
    let encoded = work.join("encoded.webp");
    let mut cmd = Command::new("cwebp");
    cmd.args(["-quiet", "-exact", "-alpha_q", "100"]);
    if let Some(q) = quality {
        cmd.args(["-q", &q.to_string()]);
    } else {
        cmd.args(["-lossless", "-z", "6"]);
    }
    cmd.arg(png).arg("-o").arg(&encoded);
    run(&mut cmd, work)?;
    ensure(fs::metadata(&encoded)?.len() <= MAX_IMAGE, "encoding exceeds 500 KiB; explicitly choose --quality for lossy encoding or simplify the source")?;
    Ok(encoded)
}

fn encode_animation(
    frames: &[PathBuf],
    work: &Path,
    loops: u16,
    quality: Option<u8>,
) -> Result<PathBuf> {
    ensure(
        !frames.is_empty() && frames.len() * 40 <= 3000,
        "normalized animation exceeds 3 seconds",
    )?;
    let encoded = work.join("encoded.webp");
    let mut cmd = Command::new("img2webp");
    cmd.args(["-loop", &loops.to_string()]);
    for frame in frames {
        cmd.args(["-d", "40"]);
        if let Some(q) = quality {
            cmd.args(["-lossy", "-q", &q.to_string()]);
        } else {
            cmd.arg("-lossless");
        }
        cmd.arg(frame);
    }
    cmd.arg("-o").arg(&encoded);
    run(&mut cmd, work)?;
    ensure(
        fs::metadata(&encoded)?.len() <= MAX_IMAGE,
        "animation exceeds 500 KiB; explicitly choose --quality or simplify the source",
    )?;
    Ok(encoded)
}

fn sample_frames(frames: image::Frames<'_>, work: &Path) -> Result<Vec<PathBuf>> {
    let mut elapsed = 0f64;
    let mut next = 0u32;
    let mut pixels = 0u64;
    let mut paths = Vec::new();
    for (index, frame) in frames.enumerate() {
        ensure(index < 1000, "source exceeds 1000-frame decoding budget")?;
        let frame = frame?;
        pixels += u64::from(frame.buffer().width()) * u64::from(frame.buffer().height());
        ensure(
            pixels <= 128 * 1024 * 1024,
            "source exceeds decoded-pixel work budget",
        )?;
        let (n, d) = frame.delay().numer_denom_ms();
        ensure(
            n > 0 && d > 0,
            "source has zero/unknown frame duration; refusing to invent timing",
        )?;
        elapsed += f64::from(n) / f64::from(d);
        ensure(
            elapsed <= 3000.000001,
            "source animation exceeds 3 seconds; trim explicitly before conversion",
        )?;
        let image = resized(DynamicImage::ImageRgba8(frame.into_buffer()));
        while f64::from(next) < elapsed - 0.000001 {
            let path = work.join(format!("frame-{:03}.png", paths.len()));
            image.save_with_format(&path, ImageFormat::Png)?;
            paths.push(path);
            next += 40;
        }
    }
    ensure(!paths.is_empty(), "source has no animation frames")?;
    Ok(paths)
}

fn webm_frames(input: &Path, work: &Path, drop_audio: bool) -> Result<Vec<PathBuf>> {
    let metadata = run(
        Command::new("ffprobe")
            .args([
                "-v",
                "error",
                "-protocol_whitelist",
                "file,pipe",
                "-show_streams",
                "-show_entries",
                "stream=codec_type",
                "-of",
                "json",
            ])
            .arg(input),
        work,
    )?;
    let metadata: serde_json::Value = serde_json::from_slice(&metadata)?;
    ensure(
        drop_audio
            || !metadata["streams"]
                .as_array()
                .ok_or("missing stream metadata")?
                .iter()
                .any(|s| s["codec_type"] == "audio"),
        "WebM has audio; use --drop-audio to explicitly discard it",
    )?;
    let data = run(Command::new("ffprobe").args(["-v","error","-protocol_whitelist","file,pipe","-select_streams","v:0","-show_frames","-show_streams","-show_entries","frame=best_effort_timestamp_time,duration_time,pkt_duration_time:stream=codec_name,width,height,avg_frame_rate","-of","json"]).arg(input),work)?;
    let probe: serde_json::Value = serde_json::from_slice(&data)?;
    let stream = probe["streams"]
        .as_array()
        .and_then(|s| s.first())
        .ok_or("WebM contains no video stream")?;
    ensure(
        stream["width"].as_u64().is_some_and(|n| n > 0 && n <= 4096)
            && stream["height"]
                .as_u64()
                .is_some_and(|n| n > 0 && n <= 4096),
        "source WebM dimensions exceed decoding budget",
    )?;
    let decoder = match stream["codec_name"].as_str() {
        Some("vp9") => "libvpx-vp9",
        Some("vp8") => "libvpx",
        _ => return Err("WebM requires VP8/VP9 video".into()),
    };
    let frames = probe["frames"]
        .as_array()
        .ok_or("cannot determine video frame timing")?;
    ensure(
        !frames.is_empty() && frames.len() <= 1000,
        "WebM source frame budget exceeded",
    )?;
    let number = |value: &serde_json::Value| {
        value
            .as_str()
            .and_then(|s| s.parse::<f64>().ok())
            .filter(|n| n.is_finite())
    };
    let first =
        number(&frames[0]["best_effort_timestamp_time"]).ok_or("missing video timestamp")?;
    let mut end = 0f64;
    let mut previous = None;
    for (i, frame) in frames.iter().enumerate() {
        let pts = number(&frame["best_effort_timestamp_time"]).ok_or("missing video timestamp")?;
        ensure(
            previous.is_none_or(|p| pts > p),
            "non-increasing video timestamps",
        )?;
        let duration = number(&frame["duration_time"])
            .or_else(|| number(&frame["pkt_duration_time"]))
            .or_else(|| {
                frames
                    .get(i + 1)
                    .and_then(|f| number(&f["best_effort_timestamp_time"]))
                    .map(|next| next - pts)
            })
            .ok_or("missing video frame duration; refusing to invent timing")?;
        ensure(duration > 0.0, "non-positive video frame duration")?;
        end = end.max(pts - first + duration);
        previous = Some(pts);
    }
    ensure(
        end <= 3.000001,
        "source video exceeds 3 seconds; trim explicitly before conversion",
    )?;
    run(Command::new("ffmpeg").args(["-v","error","-nostdin","-protocol_whitelist","file,pipe","-c:v",decoder,"-i"]).arg(input)
        .args(["-an","-sn","-vf","scale=w='min(512,iw)':h='min(512,ih)':force_original_aspect_ratio=decrease,fps=25:round=up","-pix_fmt","rgba","-frames:v","76"])
        .arg(work.join("frame-%03d.png")),work)?;
    let mut paths: Vec<_> = fs::read_dir(work)?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            p.file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("frame-")
                && p.extension().is_some_and(|e| e == "png")
        })
        .collect();
    paths.sort();
    Ok(paths)
}

pub fn convert(input: &Path, output: &Path, options: &ConvertOptions) -> Result<ConversionReport> {
    ensure(
        options.quality.is_none_or(|q| q <= 100),
        "quality must be 0..100",
    )?;
    let parent = output_parent(output)?;
    let bytes = bounded_read(input, MAX_INPUT)?;
    let input = fs::canonicalize(input)?;
    let extension = input
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    ensure(extension != "tgs", "TGS unsupported: no Lottie/rlottie renderer adapter installed; render to APNG/GIF using a real renderer first")?;
    let output_format = output
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    ensure(
        output_format == "webp" || output_format == "png",
        "output extension must be .webp or .png",
    )?;
    ensure(
        output_format == "webp" || options.quality.is_none(),
        "--quality only applies to WebP output",
    )?;
    let work = tempfile::tempdir_in(parent)?;
    let mut frames = None;
    if extension == "webm" {
        ensure(
            bytes.starts_with(&[0x1a, 0x45, 0xdf, 0xa3]),
            "not an EBML/WebM file",
        )?;
        frames = Some(webm_frames(&input, work.path(), options.drop_audio)?);
    } else {
        let format = image::guess_format(&bytes)?;
        match format {
            ImageFormat::Gif => {
                let mut decoder = image::codecs::gif::GifDecoder::new(Cursor::new(&bytes))?;
                decoder.set_limits(media::limits(4096))?;
                frames = Some(sample_frames(decoder.into_frames(), work.path())?);
            }
            ImageFormat::Png => {
                let mut decoder = image::codecs::png::PngDecoder::new(Cursor::new(&bytes))?;
                decoder.set_limits(media::limits(4096))?;
                if decoder.is_apng()? {
                    frames = Some(sample_frames(decoder.apng()?.into_frames(), work.path())?);
                } else {
                    resized(DynamicImage::from_decoder(decoder)?)
                        .save_with_format(work.path().join("static.png"), ImageFormat::Png)?;
                }
            }
            ImageFormat::WebP => {
                let mut decoder = image::codecs::webp::WebPDecoder::new(Cursor::new(&bytes))?;
                decoder.set_limits(media::limits(4096))?;
                if decoder.has_animation() {
                    frames = Some(sample_frames(decoder.into_frames(), work.path())?);
                } else {
                    resized(DynamicImage::from_decoder(decoder)?)
                        .save_with_format(work.path().join("static.png"), ImageFormat::Png)?;
                }
            }
            _ => {
                return Err(
                    "input must be PNG/WebP/GIF/APNG/WebM; TGS needs an external renderer".into(),
                )
            }
        }
    }
    let animated = frames.is_some();
    let encoded = if let Some(frames) = frames {
        ensure(
            output_format == "webp",
            "animated input requires .webp output; refusing first-frame-only PNG",
        )?;
        encode_animation(&frames, work.path(), options.loops, options.quality)?
    } else if output_format == "png" {
        work.path().join("static.png")
    } else {
        encode_static(
            &work.path().join("static.png"),
            work.path(),
            options.quality,
        )?
    };
    let result = bounded_read(&encoded, MAX_IMAGE)?;
    let info = media::validate(&result)?;
    publish_file(&result, output)?;
    Ok(ConversionReport { media: info, options: options.clone(), animation_sampling: animated.then(|| "25fps/40ms resampling; may drop/duplicate frames and extend the source by <40ms (still <=3000ms)".into()) })
}
