# PrivChat Sticker Tool

Independent Rust CLI (`privchat-sticker`) for local conversion, native package
validation/packing, and import preparation. It is not a server module, client
dependency, uploader, or publisher. Its own Cargo workspace does not change the
repository's Gradle or any parent Cargo build.

## Build And Test

```sh
cd neton-application-module-sticker/tools/sticker-tool
CARGO_BUILD_JOBS=2 cargo build --locked
CARGO_BUILD_JOBS=2 cargo test --locked -- --test-threads=1
cargo fmt --check
target/debug/privchat-sticker --help
```

Debug information and incremental compilation are disabled for these local
profiles. `target/` is local and ignored. Cargo.lock is checked in. Dependencies
are restricted to serde/JSON, image (PNG/GIF/WebP only), zip (stored/deflate only),
flate2's Rust backend, tempfile and SHA-256. No client runtime, renderer, network
HTTP client or root workspace integration is introduced.

Conversion requires these executables on PATH:

- `cwebp` for static WebP; `img2webp` for animated WebP (libwebp).
- `ffprobe` and `ffmpeg` with VP8/VP9 decoders for WebM only. FFmpeg does **not**
  need a WebP encoder: decoded PNG frames go to `img2webp`.
- PNG/GIF/APNG/static and animated WebP decoding uses the mature Rust `image`
  codecs; PNG output does not need external tools.

Missing tools cause a nonzero exit with the missing executable identified. Tests
using real codecs do not silently skip when executables are absent. External
commands have a 90-second runtime and 1 MiB diagnostic-output budget. All media
inputs are bounded local regular files (64 MiB maximum); FFmpeg/ffprobe receive
only canonical local paths with the `file,pipe` protocol whitelist.

## Convert

```sh
target/debug/privchat-sticker convert input.png --output output.webp
target/debug/privchat-sticker convert input.webp --output compatible.png
target/debug/privchat-sticker convert input.gif --output motion.webp --loop 0
target/debug/privchat-sticker convert input.apng --output motion.webp
target/debug/privchat-sticker convert input.webm --output motion.webp
# Lossy encoding must be explicitly selected. No hidden quality fallback:
target/debug/privchat-sticker convert input.png --output smaller.webp --quality 75
# Audio is rejected by default, not silently discarded:
target/debug/privchat-sticker convert with-audio.webm --output silent.webp --drop-audio
```

- PNG/WebP static output: each axis at most 512 px, all images at most 500 KiB.
  Oversized dimensions are explicitly resized proportionally; small images are
  never upscaled, cropped or padded to a square. Alpha is retained. Lossless is
  the default. An oversized lossless result fails; the caller may explicitly
  choose `--quality 0..100`. PNG output rejects `--quality` rather than ignoring it.
- Animation input: GIF, APNG, animated WebP, or VP8/VP9 WebM. Fully composited
  source frames are **resampled at 25 fps / 40 ms**. Frames may be dropped or
  duplicated; duration can round up by less than 40 ms. This policy appears in
  help, conversion JSON and import provenance. No hidden trimming or speedup:
  sources longer than 3 seconds fail. Zero/unknown raster frame timings fail.
- Every output is decoded again before publication. Actual animation frames
  must each last at least 34 ms, total duration at most 3000 ms, and count at most
  90. These limits are independent: **90 frames at 34 ms is invalid** (3060 ms).
  The 25 fps converter normally generates at most 75 sampled frames; encoders
  may coalesce identical frames. Validators accept legal variable durations.
- `--loop 0` means infinite (default); finite values 1..65535 are allowed. Static
  results have no loop metadata. A single sampled animation frame may encode as
  static WebP; the returned decoded report states its actual frame count.
- No output is written until conversion and full validation succeed. Existing
  output files, including symlinks, are never overwritten. Temporary work is
  cleaned on failure. Output parent directories must already exist.

Successful conversion prints JSON containing decoded geometry, format, frames,
duration, requested/actual encoder quality policy and sampling policy. Keep this
JSON with your operational records for standalone conversions; `import` records
the same details automatically. The tool does not guess source animation loop
metadata: it uses the explicit/default `--loop` choice.

TGS fails explicitly: no Lottie/rlottie renderer adapter is implemented. A real
external renderer must first produce timed GIF/APNG frames. TGS is never treated
as ordinary JSON, a static first frame, or a successful unsupported conversion.

## Native Manifest And Flat ZIP

`manifest.json` must be at the root beside exactly its referenced assets:

```json
{
  "schema_version": 1,
  "pack_id": "cats",
  "name": {"en": "Cats", "zh-CN": "猫咪"},
  "author": "Original author",
  "version": 1,
  "cover": "smile.webp",
  "stickers": [
    {"key": "smile", "file": "smile.webp", "emoji": "🙂", "width": 320, "height": 180}
  ]
}
```

Contract: `privchat-docs/spec/features/sticker/sticker-manifest-v1.schema.json`.
Omitted `schema_version` is legacy v1; an omitted emoji defaults to empty.
Unknown schema versions/properties, wrong types, invalid IDs, duplicate logical
keys, unsafe filenames, and mismatched **decoded** dimensions are rejected.
String budgets use UTF-16 units to match the current Kotlin backend; this is
stricter than JSON Schema code-point lengths for supplementary characters.

```sh
target/debug/privchat-sticker validate ./cats
target/debug/privchat-sticker pack ./cats --output cats.zip
target/debug/privchat-sticker validate cats.zip
target/debug/privchat-sticker validate motion.webp
```

Limits apply equally to stickers and cover: normalized PNG/WebP only, 500 KiB per
image, 1..512 per axis, 1..256 stickers. Cover may reuse a sticker file. PNG/APNG
sources are distinguished: APNG is conversion input only, not normalized PNG.
Compressed archive, aggregate compressed work and expanded package are each
strictly below 32 MiB; manifest at most 128 KiB; at most 258 entries.

ZIP writing uses standard stored entries and flat ASCII filenames. ZIP reading
uses a mature bounded inflater/CRC checker, preceded by backend-compatible
header checks. It rejects ZIP64, multidisk/encrypted/unsupported compression,
duplicate raw names (before the ZIP library can deduplicate), traversal,
directories, symlinks, overlapping ranges, header-name/method/CRC/size mismatch,
and any missing or extra entry. It **never calls extract-all**. All budgets are
checked before decompression; all declared images are decoded, including every
animated WebP frame. Packaging does not silently resize invalid source assets.

## Import Preparation

```sh
# Validate a native ZIP/directory and preserve image bytes:
target/debug/privchat-sticker import cats.zip --output imported-cats
# A local WhatsApp-style contents.json folder; select if it lists several packs:
target/debug/privchat-sticker import whatsapp-folder --output cats \
  --select cats --version 1 --license original-LICENSE.txt \
  --source-url https://original-author.example/source
# Local Telegram-style FILE COLLECTION (not API/network import):
target/debug/privchat-sticker import source-images --output animals \
  --pack-id animals --name Animals --author 'Original author' --version 1
target/debug/privchat-sticker pack ./animals --output animals.zip
```

Native import copies validated resources byte-for-byte and writes explicit schema
v1. Foreign import converts every selected source into WebP and verifies the
whole native result. Destination must not exist. All conversions finish in
staging before destination creation; failed conversions leave no output folder.

The local WhatsApp adapter understands `sticker_packs[]` with `identifier`,
`name`, `publisher`, `tray_image_file`, and `stickers[].image_file/emojis`. It
accepts files at the source root or beneath the selected identifier. It retains
publisher, name and emoji data, original names/hashes and conversion mappings.
Logical keys come from valid original file stems, otherwise a stable filename
SHA-256 prefix; reordering/inserting entries does not renumber existing keys.
Colliding valid stems are rejected rather than silently renaming identities.

Telegram-style collection import is deliberately only local files. It requires
explicit pack name/author/ID; it does not fetch Telegram packs or recover emoji
metadata from an API. Non-ASCII filenames get stable filename-hash keys. Arbitrary
`.wastickers`/foreign ZIP formats, Telegram API export metadata and Matrix packs
are not implemented adapters; provide local files/native manifest instead.

Import writes `OUTPUT_DIRECTORY.provenance.json` **outside** the native folder.
The record includes source kind/path and archive/manifest SHA-256 where present,
per-file source/output SHA-256, original names, author, source URL (recorded, never
fetched), exact conversion options/results, codec/tool versions, and optional
license text/digest. Native imports are marked byte-preserved. Keep this record
beside any later ZIP; `pack` does not insert/copy sidecars or invent missing source
history. Reimporting a native ZIP cannot reconstruct provenance absent from it.

**Provenance is local evidence, not backend persistence.** Current admin DTOs do
not persist this full record or deliver complete attribution to users. Do not
publish third-party assets requiring attribution until those paths are ready.
Licenses/NOTICE files must NOT be placed inside the issuance ZIP. Import itself
does not grant redistribution rights or publish a pack. Sidecar and destination
are separate filesystem objects, not a multi-file transactional database commit;
an OS failure publishing the sidecar can leave a fully validated output folder.

## Verification Boundaries

The real-codec integration suite covers aspect ratio/no-upscale/alpha, explicit
lossy encoding, PNG corruption and budgets, GIF/APNG/WebM conversion, animated
WebP re-import, loop count, strict timing/size limits, audio refusal/explicit
drop, exact native ZIP roundtrip, legacy schema, ZIP attacks, missing tools,
failure/no-overwrite behavior and WhatsApp stable-key/provenance preservation.
It does not use header-only fake media as positive codec evidence.

For production backend parser cross-validation, use an existing compiled runner:

```sh
/path/to/unpack-sticker-zip.kexe /absolute/path/to/cats.zip
```

This checks only production `StickerArchive.unpack`, not admin authentication,
database/storage import, publication, client rendering or legal approval.
No success claim is made for TGS rendering, arbitrary foreign archives or
production provenance persistence. See `ACCEPTANCE.md` for this run's results.
