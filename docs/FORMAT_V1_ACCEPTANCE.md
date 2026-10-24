# Sticker Format v1 verification (2026-10-23)

## Scope

This change applies the accepted 512-pixel **per-axis bounding box**, not a
mandatory square canvas. New ingress validation does not rewrite published
objects, change type-6 messages or revalidate historical reads. Backend parsing,
admin preflight, native rendering and an independent operator tool have different
responsibilities; their evidence must not be conflated.

Canonical requirements and JSON Schema:
`privchat-docs/spec/features/sticker/STICKER_PACK_SPEC.md` and
`sticker-manifest-v1.schema.json` in that directory.

## Executed backend and admin checks

- 23 focused Kotlin/Native tests passed: 21 production archive/container tests
  and 2 business-rule tests. Includes static/animated rectangular geometry,
  513-pixel rejection on either axis, exact 500 KiB limits, independent covers,
  frame timing/count/duration limits, schema-version compatibility and the
  existing ZIP defenses. Test frame-header fixtures deliberately do not claim
  full pixel decoding.
- The focused harness compiles production DTO/rules/archive and native zlib
  sources with the real serialization plugin. This is not a fake archive parser.
  Harness and exact commands: `/tmp/sticker-v1-focused.gtqTBj/README.txt`.
  Parent rerun log: `/tmp/privchat-sticker-v1-backend-final.log`.
- Admin frontend: 9 tests and TypeScript typecheck passed. Static and animated
  images both have a 500 KiB hard ceiling, checked before reading/decoding the
  file. Static 100 KiB remains advisory. Browser scenarios exercised actual
  image decoding and Blob previews against stubbed HTTP; this is not backend
  E2E or production-login evidence.

## Independent cross-contract check

The Rust tool's 10 real-codec integration tests passed with no ignored tests;
`cargo fmt --check` passed. They cover PNG/GIF/APNG/VP9 WebM, alpha and loop
metadata, explicit lossy/audio policy, real malformed inputs, ZIP defenses,
stable imported keys, local provenance and no-overwrite failure behavior.
Commands and detailed boundaries: `tools/sticker-tool/ACCEPTANCE.md`.

Fixture directory: `/tmp/privchat-sticker-v1-contract.ZywbRF` (generated local
FFmpeg assets, not third-party or production content).

| Operation | Actual result |
|---|---|
| Convert 320x180 PNG to lossless WebP | 320x180, no enlargement/padding |
| Convert 640x320 PNG to lossless WebP | 512x256, original aspect ratio |
| Convert 128x64, 0.5-second GIF | Animated WebP, 5 encoded frames, 520 ms; explicit 25 FPS resampling report |
| Rust tool pack + validate | Native flat ZIP with schema version 1 and three images accepted |
| Production `StickerArchive.unpack` on that exact ZIP | `STICKER_UNPACK_OK images=3`, all dimensions and resources matched |
| Manually constructed ZIP containing a 640x320 PNG | Both Rust tool and production backend rejected it |
| Convert into an existing output | Refused; existing file retained |

The GIF timing is not falsely reported as byte-preserved: fixed-40-ms sampling
may extend the source by less than 40 ms. The conversion report records this.
Lossy encoding requires an explicit quality option; no silent quality retries.

Cross-contract runner:

```sh
/tmp/sticker-v1-focused.gtqTBj/unpack-sticker-zip.kexe \
  /tmp/privchat-sticker-v1-contract.ZywbRF/contract.zip
```

Logs and JSON reports live in that fixture directory. The runner calls the
production parser, but does not exercise HTTP, authorization, database writes,
StorageManager uploads or publication.

## Client checks and boundaries

Client changes preserve Fit rendering, bound legacy-image decode dimensions,
pause/unmount offscreen/background image views and limit animated candidates
through a shared fair admission queue. Waiting candidates use a first frame;
static images cannot monopolize playback forever.

Android compressed-byte caching has a separate 8 MiB budget, deduplicates
in-flight fetches and never shares animated Drawables. Focused real loopback
HTTP tests verify that 16 half-megabyte resources are fetched once each over
multiple animation/static rotations. Additional tests cover weighted LRU,
concurrent requests, retryable failures, excessive Content-Length and oversized
chunked bodies rejected while streaming. These are JVM HTTP/cache tests, not an
Android-device decode test. Reproduction:

```sh
cd privchat-app
scripts/verification/test-sticker-image-focused.sh
```

UI focused tests passed (14 catalog/panel/policy checks and 2 later slot/fairness
checks); 7 app catalog tests and 5 JVM HTTP/cache checks passed. See
`/tmp/privchat-sticker-client-ui-tests.log`,
`/tmp/privchat-sticker-client-app-tests.log`,
`/tmp/privchat-sticker-client-fairness-tests.log` and
`/tmp/privchat-sticker-client-cache-tests.log`. These JVM checks do not prove
iOS playback.

The dedicated iOS simulator native image host compiled the production
Objective-C adapter and used installed codec frameworks. Its fresh result is:

```text
STICKER_IMAGE_RUNTIME PASS boundedRectangle=1 staticFallback=1 lifecycle=1 released=1 decodedFrames=2 displayedFrames=2
```

Evidence: `privchat-app/privchat/build/sticker-client-verification/ios-native-image-result.txt`.
This verifies 2048x1024 legacy input decoded to 512x256, two actually presented
frames, static first-frame fallback, pause/resume via posted application
notifications, detach stop and eventual deallocation. It does not prove a
physical app-background transition, full shared-UI scrolling or a combined
four-player native handoff. Android instrumentation sources compile but were not
run on a phone in this change.

## Not Claimed By These Checks

- No new production deployment, real-database lifecycle, store release or
  iOS/Android cross-device messaging run is implied by this format change.
  Earlier 2026-10-21 evidence remains historical evidence, not a fresh rerun.
- TGS requires a genuine external vector renderer. Missing renderer support is
  an explicit failure, not a still-frame substitution.
- Full client-side ZIP offline installation is not delivered merely because
  the operator tool supports ZIP. Catalog installs and single-resource fetching
  are distinct from transactional offline package installation.
- Operator provenance/license sidecars do not implement persisted backend
  attribution or user-visible licensing details. Attribution-required packs
  must not be published until that chain is complete and rights are checked.
- C-end uploads remain unavailable. Operator imports create local assets or
  admin drafts; neither automatically publishes a pack.
