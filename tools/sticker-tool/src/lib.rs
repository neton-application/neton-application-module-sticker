pub mod archive;
pub mod convert;
pub mod import;
pub mod media;
pub mod model;

use std::{fs, path::Path};

pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
pub const MAX_IMAGE: u64 = 500 * 1024;
pub const MAX_PACKAGE: u64 = 32 * 1024 * 1024 - 1;
pub const MAX_MANIFEST: u64 = 128 * 1024;

pub fn ensure(ok: bool, message: &str) -> Result<()> {
    if ok {
        Ok(())
    } else {
        Err(message.into())
    }
}

pub fn bounded_read(path: &Path, limit: u64) -> Result<Vec<u8>> {
    use std::io::Read;
    let metadata = fs::symlink_metadata(path)?;
    ensure(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "expected a regular, non-symlink file",
    )?;
    ensure(metadata.len() <= limit, "file exceeds size budget")?;
    let mut bytes = Vec::new();
    fs::File::open(path)?
        .take(limit + 1)
        .read_to_end(&mut bytes)?;
    ensure(bytes.len() as u64 <= limit, "file exceeds size budget")?;
    Ok(bytes)
}

pub fn output_parent(path: &Path) -> Result<&Path> {
    ensure(
        !path.exists() && fs::symlink_metadata(path).is_err(),
        "output already exists; refusing to overwrite",
    )?;
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    ensure(parent.is_dir(), "output parent directory must exist")?;
    Ok(parent)
}

pub fn publish_file(bytes: &[u8], output: &Path) -> Result<()> {
    use std::io::Write;
    let mut temp = tempfile::NamedTempFile::new_in(output_parent(output)?)?;
    temp.write_all(bytes)?;
    temp.as_file().sync_all()?;
    temp.persist_noclobber(output)?;
    Ok(())
}
