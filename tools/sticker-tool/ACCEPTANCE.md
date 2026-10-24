# Independent Sticker Tool Acceptance

Date: 2026-10-23. All changes are confined to `tools/sticker-tool`.
No root build/spec changes, commits, backend/admin/client edits, or release build.

## Result

Implemented independent Rust binary `privchat-sticker` with `convert`, `pack`,
`validate`, `import`, standalone Cargo workspace/lockfile, local `.gitignore`,
README, and real-codec integration tests.

```text
CARGO_BUILD_JOBS=2 cargo test --locked -- --test-threads=1
10 passed; 0 failed; 0 ignored (integration tests, 2.38 seconds)
cargo fmt --check
PASS
```

The test run actually executed cwebp/img2webp/ffmpeg/ffprobe. Positive images were
decoded, including every animated WebP frame, rather than accepted by headers.

Tests cover:

- 240x240, 320x180 and 512x256 preserve dimensions; 1024x512 becomes 512x256;
  1x513 becomes 1x512. Alpha retained; PNG compatibility and explicit lossy q75.
- GIF to animated WebP, animated WebP reconversion, real distinct frame pixels,
  finite loop count, explicit 25fps/40ms sampling report; animated-to-PNG refused.
- Real 3000ms animation accepted; 33ms frames, >3000ms total and >90 frames
  rejected; 90x34ms correctly rejected, not treated as valid 3-second content.
- PNG corruption, real geometry and 500KiB limit; random high-entropy source
  fails oversized lossless conversion without hidden lossy retries or output.
- Native stored ZIP packing, deflated ZIP reading, omitted schema v1,
  byte-preserved native import and external provenance sidecar.
- Extra/unknown ZIP files, traversal, raw duplicate filenames, symlink entries,
  ZIP64 sentinel and local/central header mismatch rejected.
- Strict manifest properties, UTF-16 emoji budget and decoded/declaration
  geometry mismatch rejected.
- Existing output unchanged; TGS reports missing renderer; failed collection
  import leaves no destination/sidecar; absent cwebp causes explicit failure.
- WhatsApp local contents import retains emoji/author/name/license/source hashes;
  reordered entries retain keys including Unicode source filename hash fallback.
- Real APNG and VP9 WebM inputs converted; audio WebM rejected by default and
  accepted only with explicit `--drop-audio`.

The first suite run caught two real gaps: source filename safety was incorrectly
using the backend's ASCII OUTPUT filename restriction, and zip 2.4 deduplicates
central names before callers see them. Source Unicode basenames are now handled
with safe local lookup/stable output hash keys; duplicate raw names are rejected
in preflight before invoking the ZIP library. The final full suite passed.

## Production Parser Cross-Validation

Parent task generated real inputs/outputs and contract ZIP at:

`/tmp/privchat-sticker-v1-contract.ZywbRF`

Observed evidence files include `rectangle-conversion.json`,
`resized-conversion.json`, `motion-conversion.json`, `pack-result.json`,
`validate-result.json`, `backend-result.log`, `tool-oversized.log`,
`backend-oversized.log`, and `overwrite.log`.

- 320x180 PNG to WebP stayed 320x180 (44 bytes).
- 640x320 PNG to WebP became 512x256 (46 bytes).
- 0.5-second GIF became animated WebP 128x64, 520ms, five coalesced frames,
  2994 bytes. JSON explicitly reports 25fps resampling/possible <40ms extension,
  null quality (=lossless), loops=0 and drop_audio=false.
- Tool packed/validated `contract.zip` with three images and manifest.
- Both tool and production parser rejected an unconverted 640-wide PNG ZIP;
  overwrite attempt refused (parent task evidence).

This task independently re-ran the current binary and existing backend runner:

```sh
target/debug/privchat-sticker validate /tmp/privchat-sticker-v1-contract.ZywbRF/contract.zip
/tmp/sticker-v1-focused.gtqTBj/unpack-sticker-zip.kexe /tmp/privchat-sticker-v1-contract.ZywbRF/contract.zip
```

Observed:

```text
{"files":4,"pack_id":"format-v1-contract","schema_version":1,"stickers":3,"valid":true}
STICKER_UNPACK_OK images=3
motion.webp image/webp 128x64 2994 bytes
rectangle.webp image/webp 320x180 44 bytes
resized.webp image/webp 512x256 46 bytes
```

This is production `StickerArchive.unpack` compatibility, NOT an authenticated
admin upload, database/storage transaction, publication, or client UI test.

## Operational Boundaries

- TGS renderer adapter is explicitly absent. No fake TGS/static substitution.
- Foreign adaptation is local WhatsApp contents directories or Telegram-style
  file collections, not Telegram API access or arbitrary `.wastickers` ZIPs.
- WebM must have usable timestamps/durations and VP8/VP9 decoder support. Missing
  frame timings fail rather than inventing them. No remote media fetching.
- Animation resampling is disclosed; it is not an original-timing preservation
  claim. Default encoding is lossless; lossy quality is opt-in and recorded.
- Native source manifest schema omissions remain accepted. Imported/packed
  manifests explicitly write schema_version=1. UTF-16 lengths intentionally match
  current Kotlin backend rather than broader code-point schema allowances.
- Local provenance includes hashes, source names/URL/author, exact conversion
  reports, codec/tool versions and optional license text. It stays OUTSIDE ZIP.
  The backend DTO does not persist/deliver this full record. Do not publish assets
  requiring attribution until that delivery chain exists. No redistribution
  rights, publication or complete attribution persistence is claimed.
- Outputs are staged and existing files are never overwritten. Import directory
  and external sidecar are not a two-object transactional commit; a filesystem
  failure publishing the sidecar may leave a complete validated directory.
- Current ignored local target footprint is approximately 250 MiB; retained for
  immediate parent verification. Debug=0, incremental=false, at most two build
  jobs; no release or unrelated workspace build was started.
