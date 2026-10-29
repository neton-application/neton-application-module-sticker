# PrivChat sticker service

PLATFORM-owned sticker catalog and administration module. The paired management
UI is `neton-application-front-sticker`. This module does not add customer
uploads or change the IM message protocol (type 6, `sticker_id` + `image_url`).

Independent packs now expose cover URLs and a draft-editable default-install
flag. V004 preserves explicit per-account uninstalls as tombstones; it does not
seed Apple assets or turn packs into emoji categories. See
[API contract](docs/API.md#independent-packs-and-default-installation) and
[disposable PG/socket acceptance](docs/DEFAULT_PACK_ACCEPTANCE.md).

## Scope

- Admin realm: authors, localized pack metadata, ZIP import, single-image pack
  creation, private draft previews, explicit publish and offline actions.
- Account realm: published catalog, installation, uninstall, ordering and
  immutable resource resolution.
- Public read-only resources: only images that have been published. Ordinary
  offline stops new installation; existing installations and historical images
  remain available. Updating a logical sticker creates a new resource identity.

Exact DTOs, permissions, safety limits and retry behavior: [API contract](docs/API.md).
Product baseline: `privchat-docs/spec/features/sticker/STICKER_PACK_SPEC.md`.

## Sticker Format v1

New uploads have width and height independently bounded to 1..512 pixels, not
an exact 512x512 canvas. Preserve rectangles and original small dimensions; do
not stretch, upscale or pad to a square. Static PNG/WebP and Animated WebP are
accepted, each at most 500 KiB (512,000 bytes). Static 100 KiB is a target, not a
rejection threshold. Animation is bounded to a 3,000 ms cycle, 90 frames and
integer frame durations of at least 34 ms. Existing immutable historical
resources are still readable without being resubmitted to ingress validation.

ZIP uses the existing flat `manifest.json` layout. `schema_version: 1` identifies
the format; omission remains compatible with old v1 manifests. Content
`version` and immutable resource IDs are separate identities. All files,
including covers, pass the same ingress checks as single-image creation.

The independent Rust [sticker tool](tools/sticker-tool/README.md) prepares and
validates assets outside the mobile clients. It does not open consumer uploads,
publish drafts, grant asset redistribution rights or install additional codecs
in the app. Optional source adapters must report missing capabilities rather
than substituting a still frame for an animation. Production distribution still
requires rights/source records and any required user-visible attribution.

## Host integration

The module depends on `system`, `infra` and `account`. The distribution registers
it in Gradle and KSP; do not hand-edit generated routes. Apply the embedded
PostgreSQL migrations with the distribution's normal migration command before
starting the application. Normal startup must not create or alter tables.

Configure a StorageManager source named `sticker-private`. It must have no public
base URL, public bucket access or static directory mapping. Draft images are
served only by the permission-protected admin resource endpoint. Use durable
storage shared by application instances in a multi-instance deployment.

Set `PRIVCHAT_STICKER_PUBLIC_BASE_URL` for `config/sticker.conf` to the externally
reachable API base, including any proxy prefix. Use HTTPS in production. Do not
derive this address from an untrusted Host header. Ensure the reverse proxy and
HTTP adapter permit the documented 34 MiB multipart body; the module separately
enforces its stricter archive/image limits.

Object writes are not database transactions. Reserved, unattached objects can be
reclaimed by the authorized cleanup endpoint after 24 hours. Published historical
resources are not subject to that cleanup. Include both database and resource
objects in backups.

## Verification

Run module tests from `privchat-application`:

```sh
./gradlew :module-sticker:macosArm64Test --max-workers=2
```

The PostgreSQL lifecycle test is opt-in. Follow `docs/API.md` to create the
isolated loopback database and set `STICKER_TEST_DATABASE_URI`. A test runner
success without the `STICKER_POSTGRES_EXECUTED` marker does not prove database
acceptance.

`StickerHttpServerTest` is an opt-in loopback host using production routes,
database, storage and shared security dispatch with explicit test identities.
`tests/http/lifecycle.mjs` exercises its real HTTP endpoints. These tests do not
claim to validate production login, MFA, deployment or mobile UI behavior.

Never use production credentials or databases for these fixtures. Test-only
credentials must remain in private temporary files, not source or logs.
