use crate::{ensure, Result, MAX_IMAGE};
use image::{AnimationDecoder, ImageDecoder, ImageFormat};
use serde::Serialize;
use std::io::Cursor;

#[derive(Debug, Clone, Serialize)]
pub struct MediaInfo {
    pub width: u32,
    pub height: u32,
    pub format: String,
    pub frames: usize,
    pub duration_ms: u64,
}

pub fn limits(axis: u32) -> image::Limits {
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(axis);
    limits.max_image_height = Some(axis);
    limits.max_alloc = Some(128 * 1024 * 1024);
    limits
}

pub fn validate(bytes: &[u8]) -> Result<MediaInfo> {
    ensure(
        !bytes.is_empty() && bytes.len() as u64 <= MAX_IMAGE,
        "all images, including covers, must be <=500 KiB",
    )?;
    let format = image::guess_format(bytes)?;
    ensure(
        matches!(format, ImageFormat::Png | ImageFormat::WebP),
        "normalized assets must be PNG or WebP",
    )?;
    let mut reader = image::ImageReader::with_format(Cursor::new(bytes), format);
    reader.limits(limits(512));
    let (width, height) = reader.into_dimensions()?;
    ensure(
        width > 0 && height > 0 && width <= 512 && height <= 512,
        "actual image dimensions exceed 512",
    )?;
    let mut info = MediaInfo {
        width,
        height,
        format: format!("{format:?}"),
        frames: 1,
        duration_ms: 0,
    };
    match format {
        ImageFormat::Png => {
            let mut decoder = image::codecs::png::PngDecoder::new(Cursor::new(bytes))?;
            decoder.set_limits(limits(512))?;
            ensure(
                !decoder.is_apng()?,
                "APNG is conversion input only, not a normalized asset",
            )?;
            image::DynamicImage::from_decoder(decoder)?;
        }
        ImageFormat::WebP => {
            let mut decoder = image::codecs::webp::WebPDecoder::new(Cursor::new(bytes))?;
            decoder.set_limits(limits(512))?;
            if decoder.has_animation() {
                // Decode every composited frame with the mature codec, not just RIFF headers.
                info.frames = 0;
                for frame in decoder.into_frames() {
                    let frame = frame?;
                    let (n, d) = frame.delay().numer_denom_ms();
                    ensure(
                        d != 0 && u64::from(n) >= 34 * u64::from(d),
                        "animation frame duration must be >=34ms",
                    )?;
                    ensure(
                        n % d == 0,
                        "WebP frame duration must be integral milliseconds",
                    )?;
                    info.frames += 1;
                    info.duration_ms += u64::from(n / d);
                    ensure(info.frames <= 90, "animation exceeds 90 frames")?;
                    ensure(info.duration_ms <= 3000, "animation exceeds 3000ms")?;
                    ensure(
                        frame.buffer().dimensions() == (width, height),
                        "decoded animation geometry mismatch",
                    )?;
                }
                ensure(info.frames > 0, "empty animation")?;
            } else {
                image::DynamicImage::from_decoder(decoder)?;
            }
        }
        _ => unreachable!(),
    }
    Ok(info)
}
