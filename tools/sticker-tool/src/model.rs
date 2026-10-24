use crate::{ensure, Result};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    #[serde(default = "schema_one")]
    pub schema_version: u32,
    pub pack_id: String,
    pub name: BTreeMap<String, String>,
    pub author: String,
    pub version: i32,
    pub cover: String,
    pub stickers: Vec<Sticker>,
}
fn schema_one() -> u32 {
    1
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Sticker {
    pub key: String,
    pub file: String,
    #[serde(default)]
    pub emoji: String,
    pub width: u32,
    pub height: u32,
}

pub fn identifier(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
pub fn filename(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && !s.contains("..")
        && (s.as_bytes()[0].is_ascii_alphanumeric() || b"_-".contains(&s.as_bytes()[0]))
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
}
pub fn text(s: &str, max: usize) -> bool {
    !s.trim().is_empty() && s.encode_utf16().count() <= max
}

impl Manifest {
    pub fn validate(&self) -> Result<BTreeSet<String>> {
        ensure(
            self.schema_version == 1,
            "unsupported schema_version (expected 1)",
        )?;
        ensure(identifier(&self.pack_id), "invalid pack_id")?;
        ensure(
            self.version > 0,
            "version must be a positive 32-bit integer",
        )?;
        ensure(
            text(&self.author, 128),
            "author must be nonblank and at most 128 characters",
        )?;
        ensure(
            !self.name.is_empty()
                && self.name.len() <= 32
                && self
                    .name
                    .iter()
                    .all(|(k, v)| !k.is_empty() && k.encode_utf16().count() <= 32 && text(v, 128)),
            "invalid localized name map",
        )?;
        ensure(
            (1..=256).contains(&self.stickers.len()),
            "expected 1..256 stickers",
        )?;
        ensure(
            filename(&self.cover) && self.cover != "manifest.json",
            "unsafe cover filename",
        )?;
        let mut files = BTreeSet::from(["manifest.json".to_owned(), self.cover.clone()]);
        let mut keys = BTreeSet::new();
        for s in &self.stickers {
            ensure(
                identifier(&s.key) && keys.insert(&s.key),
                "invalid or duplicate sticker key",
            )?;
            ensure(
                filename(&s.file) && s.file != "manifest.json",
                "unsafe sticker filename",
            )?;
            ensure(
                s.emoji.encode_utf16().count() <= 64,
                "emoji exceeds 64 UTF-16 units (backend compatibility)",
            )?;
            ensure(
                (1..=512).contains(&s.width) && (1..=512).contains(&s.height),
                "declared dimensions must be 1..512",
            )?;
            files.insert(s.file.clone());
        }
        Ok(files)
    }
}
