# PrivChat sticker service

PLATFORM-owned sticker catalog and administration module. The paired management
UI is `privchat-application-front-sticker`. This module does not add customer
uploads or change the IM message protocol (type 6, `sticker_id` + `image_url`).

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
