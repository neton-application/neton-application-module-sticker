use crate::{
    bounded_read, ensure, media, model::Manifest, publish_file, Result, MAX_IMAGE, MAX_MANIFEST,
    MAX_PACKAGE,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::{Cursor, Read, Write},
    path::Path,
};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipArchive, ZipWriter};

pub struct Bundle {
    pub manifest: Manifest,
    pub files: BTreeMap<String, Vec<u8>>,
}

pub fn validate_files(files: BTreeMap<String, Vec<u8>>) -> Result<Bundle> {
    let json = files
        .get("manifest.json")
        .ok_or("missing root manifest.json")?;
    ensure(
        json.len() as u64 <= MAX_MANIFEST,
        "manifest exceeds 128 KiB",
    )?;
    let manifest: Manifest = serde_json::from_slice(json)?;
    let expected = manifest.validate()?;
    ensure(
        expected == files.keys().cloned().collect(),
        "archive must contain exactly manifest-declared flat files (no sidecars)",
    )?;
    ensure(
        files.values().map(|v| v.len() as u64).sum::<u64>() <= MAX_PACKAGE,
        "expanded package exceeds 32 MiB budget",
    )?;
    let mut geometries = BTreeMap::new();
    for (name, bytes) in &files {
        if name == "manifest.json" {
            continue;
        }
        let info = media::validate(bytes).map_err(|e| format!("{name}: {e}"))?;
        geometries.insert(name, (info.width, info.height));
    }
    for sticker in &manifest.stickers {
        ensure(
            geometries.get(&sticker.file) == Some(&(sticker.width, sticker.height)),
            "manifest dimensions disagree with decoded pixels",
        )?;
    }
    Ok(Bundle { manifest, files })
}

pub fn read_directory(path: &Path) -> Result<Bundle> {
    ensure(
        fs::symlink_metadata(path)?.is_dir(),
        "expected native package directory",
    )?;
    let manifest: Manifest =
        serde_json::from_slice(&bounded_read(&path.join("manifest.json"), MAX_MANIFEST)?)?;
    let expected = manifest.validate()?;
    let mut actual = BTreeSet::new();
    for entry in fs::read_dir(path)? {
        let entry = entry?;
        ensure(
            entry.file_type()?.is_file(),
            "directories and symlinks are forbidden in packages",
        )?;
        let name = entry
            .file_name()
            .into_string()
            .map_err(|_| "non-UTF8 filename")?;
        ensure(crate::model::filename(&name), "unsafe filename")?;
        actual.insert(name);
    }
    ensure(
        actual == expected,
        "directory contains missing or extra files; keep licenses outside the package",
    )?;
    let mut total = 0;
    let mut files = BTreeMap::new();
    for name in expected {
        let bytes = bounded_read(
            &path.join(&name),
            if name == "manifest.json" {
                MAX_MANIFEST
            } else {
                MAX_IMAGE
            },
        )?;
        total += bytes.len() as u64;
        ensure(
            total <= MAX_PACKAGE,
            "expanded package exceeds 32 MiB budget",
        )?;
        files.insert(name, bytes);
    }
    validate_files(files)
}

pub fn read_zip(path: &Path) -> Result<Bundle> {
    let bytes = bounded_read(path, MAX_PACKAGE)?;
    compatible_zip_headers(&bytes)?;
    let mut zip = ZipArchive::new(Cursor::new(bytes))?;
    ensure(
        (2..=258).contains(&zip.len()),
        "ZIP must contain 2..258 entries",
    )?;
    let mut names = BTreeSet::new();
    let mut expanded = 0u64;
    let mut compressed = 0u64;
    let mut ranges = Vec::new();
    // Preflight every entry before inflating anything; never extract archive paths.
    for index in 0..zip.len() {
        let file = zip.by_index(index)?;
        let name = file.name();
        ensure(
            crate::model::filename(name) && names.insert(name.to_owned()),
            "unsafe or duplicate ZIP filename",
        )?;
        ensure(
            !file.encrypted()
                && matches!(
                    file.compression(),
                    CompressionMethod::Stored | CompressionMethod::Deflated
                ),
            "only unencrypted stored/deflate ZIP entries are supported",
        )?;
        ensure(
            !file.is_dir()
                && file
                    .unix_mode()
                    .map(|m| m & 0o170000 == 0 || m & 0o170000 == 0o100000)
                    .unwrap_or(true),
            "ZIP symlink or non-regular entry rejected",
        )?;
        ensure(
            file.size() > 0
                && file.size()
                    <= if name == "manifest.json" {
                        MAX_MANIFEST
                    } else {
                        MAX_IMAGE
                    },
            "ZIP entry size exceeds budget",
        )?;
        expanded = expanded
            .checked_add(file.size())
            .ok_or("ZIP size overflow")?;
        compressed = compressed
            .checked_add(file.compressed_size())
            .ok_or("ZIP size overflow")?;
        ensure(
            expanded <= MAX_PACKAGE && compressed <= MAX_PACKAGE && file.compressed_size() > 0,
            "ZIP expanded/compressed work budget exceeded",
        )?;
        let start = file.header_start();
        let end = file
            .data_start()
            .checked_add(file.compressed_size())
            .ok_or("ZIP range overflow")?;
        ensure(
            start < end && ranges.iter().all(|&(s, e)| end <= s || start >= e),
            "overlapping ZIP entries",
        )?;
        ranges.push((start, end));
    }
    let mut files = BTreeMap::new();
    for index in 0..zip.len() {
        let mut file = zip.by_index(index)?;
        let size = file.size();
        let mut data = Vec::new();
        file.by_ref().take(size + 1).read_to_end(&mut data)?;
        ensure(data.len() as u64 == size, "ZIP decoded size mismatch")?;
        files.insert(file.name().to_owned(), data);
    }
    validate_files(files)
}

fn le(bytes: &[u8], start: usize, count: usize) -> Result<u64> {
    let part = bytes
        .get(start..start.checked_add(count).ok_or("ZIP offset overflow")?)
        .ok_or("truncated ZIP header")?;
    Ok(part
        .iter()
        .enumerate()
        .fold(0, |a, (i, b)| a | (u64::from(*b) << (8 * i))))
}

fn no_zip64(extra: &[u8]) -> Result<()> {
    let mut p = 0;
    while p < extra.len() {
        ensure(le(extra, p, 2)? != 1, "ZIP64 extra field is unsupported")?;
        let size = le(extra, p + 2, 2)? as usize;
        p += 4 + size;
        ensure(p <= extra.len(), "invalid ZIP extra field")?;
    }
    Ok(())
}

// Match the backend's deliberately smaller ZIP dialect before the mature ZIP
// decoder handles inflation/CRC. In particular, do not silently accept ZIP64.
fn compatible_zip_headers(bytes: &[u8]) -> Result<()> {
    ensure(bytes.len() >= 22, "truncated ZIP")?;
    let end = (bytes.len().saturating_sub(65557)..=bytes.len() - 22)
        .rev()
        .find(|&p| bytes.get(p..p + 4) == Some(b"PK\x05\x06"))
        .ok_or("missing ZIP EOCD")?;
    ensure(
        le(bytes, end + 4, 2)? == 0
            && le(bytes, end + 6, 2)? == 0
            && end + 22 + le(bytes, end + 20, 2)? as usize == bytes.len(),
        "multidisk/trailing ZIP data rejected",
    )?;
    let count = le(bytes, end + 10, 2)? as usize;
    ensure(
        (2..=258).contains(&count) && le(bytes, end + 8, 2)? == count as u64,
        "invalid ZIP entry count / ZIP64 unsupported",
    )?;
    let size = le(bytes, end + 12, 4)? as usize;
    let central = le(bytes, end + 16, 4)? as usize;
    ensure(
        size != u32::MAX as usize
            && central != u32::MAX as usize
            && central.checked_add(size) == Some(end),
        "ZIP64 / invalid central directory",
    )?;
    let mut p = central;
    let mut ranges = Vec::new();
    let mut raw_names = BTreeSet::new();
    for _ in 0..count {
        ensure(
            p <= end.saturating_sub(46) && le(bytes, p, 4)? == 0x02014b50,
            "invalid central entry",
        )?;
        let flags = le(bytes, p + 8, 2)?;
        let method = le(bytes, p + 10, 2)?;
        ensure(
            flags & 0xf7f6 == 0 && (method == 0 || method == 8) && le(bytes, p + 34, 2)? == 0,
            "unsupported ZIP flags/method/disk",
        )?;
        let crc = le(bytes, p + 16, 4)?;
        let compressed = le(bytes, p + 20, 4)? as usize;
        let expanded = le(bytes, p + 24, 4)? as usize;
        ensure(
            compressed > 0
                && compressed as u64 <= MAX_PACKAGE
                && expanded > 0
                && expanded as u64 <= MAX_MANIFEST.max(MAX_IMAGE),
            "ZIP entry budget / ZIP64 rejected",
        )?;
        ensure(
            method != 0 || compressed == expanded,
            "stored ZIP size mismatch",
        )?;
        let n = le(bytes, p + 28, 2)? as usize;
        let x = le(bytes, p + 30, 2)? as usize;
        let c = le(bytes, p + 32, 2)? as usize;
        ensure(
            (1..=128).contains(&n) && p + 46 + n + x + c <= end,
            "invalid central filename/extra",
        )?;
        let name = std::str::from_utf8(&bytes[p + 46..p + 46 + n])?;
        ensure(
            crate::model::filename(name) && raw_names.insert(name),
            "unsafe or duplicate raw ZIP filename",
        )?;
        no_zip64(&bytes[p + 46 + n..p + 46 + n + x])?;
        let offset = le(bytes, p + 42, 4)? as usize;
        ensure(
            offset <= central.saturating_sub(30) && le(bytes, offset, 4)? == 0x04034b50,
            "invalid local header",
        )?;
        ensure(
            le(bytes, offset + 6, 2)? == flags && le(bytes, offset + 8, 2)? == method,
            "local/central flags or method mismatch",
        )?;
        let ln = le(bytes, offset + 26, 2)? as usize;
        let lx = le(bytes, offset + 28, 2)? as usize;
        let start = offset + 30 + ln + lx;
        ensure(
            ln == n
                && start <= central
                && bytes.get(offset + 30..offset + 30 + ln) == bytes.get(p + 46..p + 46 + n),
            "local/central name mismatch",
        )?;
        no_zip64(&bytes[offset + 30 + ln..start])?;
        let mut finish = start.checked_add(compressed).ok_or("ZIP size overflow")?;
        ensure(finish <= central, "ZIP data overlaps central directory")?;
        if flags & 8 == 0 {
            ensure(
                le(bytes, offset + 14, 4)? == crc
                    && le(bytes, offset + 18, 4)? == compressed as u64
                    && le(bytes, offset + 22, 4)? == expanded as u64,
                "local/central CRC or size mismatch",
            )?;
        } else {
            let descriptor = if le(bytes, finish, 4)? == 0x08074b50 {
                finish + 4
            } else {
                finish
            };
            ensure(
                descriptor + 12 <= central
                    && le(bytes, descriptor, 4)? == crc
                    && le(bytes, descriptor + 4, 4)? == compressed as u64
                    && le(bytes, descriptor + 8, 4)? == expanded as u64,
                "invalid ZIP data descriptor",
            )?;
            finish = descriptor + 12;
        }
        ensure(
            ranges.iter().all(|&(s, e)| finish <= s || offset >= e),
            "overlapping ZIP ranges",
        )?;
        ranges.push((offset, finish));
        p += 46 + n + x + c;
    }
    ensure(p == end, "central directory size mismatch")
}

pub fn read(path: &Path) -> Result<Bundle> {
    if path.is_dir() {
        read_directory(path)
    } else {
        read_zip(path)
    }
}

pub fn pack(input: &Path, output: &Path) -> Result<()> {
    crate::output_parent(output)?;
    let mut bundle = read_directory(input)?;
    bundle.files.insert(
        "manifest.json".into(),
        serde_json::to_vec_pretty(&bundle.manifest)?,
    );
    let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Stored)
        .unix_permissions(0o644);
    for (name, bytes) in bundle.files {
        writer.start_file(name, options)?;
        writer.write_all(&bytes)?;
    }
    let bytes = writer.finish()?.into_inner();
    ensure(
        bytes.len() as u64 <= MAX_PACKAGE,
        "ZIP exceeds 32 MiB budget",
    )?;
    publish_file(&bytes, output)
}
