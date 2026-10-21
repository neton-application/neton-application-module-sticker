# Sticker API v1 (implementation contract)

PLATFORM only. Admin realm uses the existing system authenticator; no C-end write/upload APIs.
JSON IDs are decimal strings. Standard host response envelope applies. All paths below include route group.

Admin /admin/sticker/author:
- GET /page?pageNo=1&pageSize=20 -> PageResponse<AuthorDto> {list,total,page,size,totalPages}
- GET /list, /simple-list -> AuthorDto[]; GET /get/{id} -> AuthorDto
- POST /create AuthorWrite -> AuthorDto; PUT /update AuthorWrite (id required) -> AuthorDto
- DELETE /delete/{id} -> Boolean. Referenced authors cannot be deleted.
AuthorWrite {id?:string,name:string,bio?:string,status?:0|1}; AuthorDto {id,name,bio,status}.
Permissions sticker:author:query/create/update/delete correspond to actions.

Admin /admin/sticker/pack:
- GET /page?pageNo=1&pageSize=20, /list, /get/{id}
- POST /create PackWrite -> PackDto (empty draft); PUT /update PackWrite -> PackDto (draft metadata only)
- DELETE /delete/{id} -> Boolean (draft only)
- POST /publish, /offline {id:string} -> PackDto
- POST /import multipart file (ZIP), authorId -> PackDto
- POST /make multipart file (PNG/WebP), authorId, packId, name (JSON locale map), version, key -> PackDto
PackWrite {id?:string,packId:string,name:Record<string,string>,authorId:string,version:number}.
PackDto {id,packId,name,authorId,version,publicationState:1|2|3,stickers:StickerDto[],coverId:string|null,createdAt:number}.
StickerDto {stickerId:string,key:string,emoji:string,width:number,height:number,mimeType:string,size:number,imageUrl:string}.
Permissions sticker:pack:query/create/update/delete/publish/offline/import/make. Asset preview uses query.
GET /admin/sticker/resource/get/{id} returns authenticated bytes, never a public draft URL.
POST /admin/sticker/resource/cleanup requires sticker:pack:delete; reclaims only unattached uploads older than 24h.

App /app/sticker: GET /store, /installed -> PackDto[]; POST /install,/uninstall {packId}; POST /reorder {packIds:string[]} (exact installed set).
GET /resolve/{id} -> StickerDto; GET /resource/get/{id} -> anonymous read-only bytes for ever-published resources. Only ever-published resources resolve, including offline/old versions; install does not gate historical access.
No legacy string ID migration is inferred.

Implementation safety limits (not final product quotas): PNG/static and WebP, max image 500 KiB, max 256 stickers, ZIP and expanded package <32 MiB, max dimension 4096. APNG rejected. ZIP root manifest per product spec, stored/deflate entries, no directories/extra entries/encryption/ZIP64. Manifest author is descriptive; authorId is authoritative and display name must match.
Same complete import is idempotent by packId/version and input digest; different same-version input conflicts. Versions strictly increase. Published metadata/resources cannot be edited; create a new version. Offline cannot be republished; create a new version.

Storage prerequisite: provision StorageManager source `sticker-private`, with NO public bucket access, static file mapping or baseUrl. Unpublished bytes are served through admin-authorized routes. DB rollback never undoes object writes. Upload reservations persist before writes and cleanup takes the same DB lock as attachment. Published objects are retained indefinitely by ordinary operations.

## Host configuration and tests

`config/sticker.conf`: `public_base_url` must be an absolute externally reachable API base URL, with any reverse-proxy prefix included, no trailing slash required. Published imageUrl is `${public_base_url}/app/sticker/resource/get/{id}` and requires NO token. The route checks `ready=1 AND ever_published=1`; resolving a draft returns not-found. Ordinary offline never revokes historical public images.
Default Neton HTTP body limit is 16 MiB. Host adapter must allow 34 MiB bodies (ZIP + multipart overhead), and at least 68 MiB buffer budget. Reverse-proxy limits must agree. Module still enforces <32 MiB ZIP and expanded data limits.
`pack/make` and `pack/import` can fill an existing EMPTY draft of the same packId/version only when authorId and names match; subsequent upload retry must match digest.

Pure rules/auth boundary tests: from privchat-application, `./gradlew :module-sticker:macosArm64Test --max-workers=2`.
Real database test: explicitly set `STICKER_TEST_DATABASE_URI=postgresql://sticker_test:test@127.0.0.1:55473/sticker_test` and pass `-Pneton.database.driver=postgres`. Requires empty public sticker tables and uses production V001. It refuses to wipe preexisting tables, leaves fixtures for inspection, and uses `/tmp/sticker-integration-storage`. Absence of URI skips that test; only the `STICKER_POSTGRES_EXECUTED` marker proves execution. Shared realm tests are NOT full HTTP integration evidence.

## Acceptance evidence (2026-10-21)

- Final `:module-sticker:linkDebugTestMacosArm64 :application:compileKotlinMacosArm64 -Pneton.database.driver=postgres --max-workers=2`: successful. The host now uses the common expect/native actual HTTP adapter factory with 34 MiB body and 68 MiB buffer ceilings. Other modules' own validation limits are unchanged.
- Final Native binary run against the disposable `sticker_test_lifecycle` database: 18 rules/archive/realm-permission tests plus one executed PostgreSQL/local-StorageOperator lifecycle test passed. The optional HTTP-server test returned without starting in this invocation; the runner reports 20 passes, but this return is not counted as server evidence.
- Real database assertions include immutable version IDs, draft read/install rejection, concurrent installs, one-winner publishing, sequential and concurrent retry resource counts, author disable/delete rejection, cleanup retention, and offline completion while the unrelated authors advisory lock is held.
- `tests/http/lifecycle.mjs`: 63 real loopback HTTP checks passed against generated controller routes, shared JWT realm/RBAC pipeline, PostgreSQL and local StorageOperator. Includes cookie/CSRF, account/refresh/anonymous rejection, read-only permission denial, admin-to-app rejection, private draft previews, ZIP/single-image upload, update/delete, publish/offline, anonymous original-image bytes after updates/offline, installed latest-ever-published version, retry resource count, 17 MiB accepted by transport then rejected by module validation, and 35 MiB rejected with 413.
- HTTP fixture identities are deliberately fixed test users. These checks do NOT claim system login, MFA, persistent role assignment, administrator session revocation, production proxy/CDN, Linux targets, or device messaging acceptance. Images undergo bounded structural validation, not full pixel decoding.

### Reproduce the loopback HTTP test

After applying V001 in the disposable database, run the built test binary with `STICKER_HTTP_TEST_HOST=1`, `STICKER_TEST_DATABASE_URI=postgresql://sticker_test:test@127.0.0.1:55473/sticker_test` and filter `--ktest_filter=StickerHttpServerTest.*`. It listens only on 127.0.0.1:55474 for at most 30 minutes. The permission-info fixture reads the actual V002 menu seed from the parent's isolated `migration_check.system_menus` setup.

Redirect host output to `/tmp/sticker-http-host.log`. This log contains only `STICKER_HTTP_CREDENTIALS=<path>`, never tokens. The random temporary credential file is mode 0600 and contains ADMIN/READONLY/ACCOUNT/REFRESH fixture credentials. `node tests/http/lifecycle.mjs` reads it locally. Do not print, commit or publish its contents. Delete the credential file after forcibly terminating the test host; normal host shutdown removes it.

The database lifecycle test also permits the isolated `sticker_test_lifecycle` database on port 55473; it requires no existing sticker tables and leaves fixtures for inspection. Never point these tests at production. No deployment or commits were made.

### Different-image immutable regression (2026-10-21)

After rebuilding missing shared dependency klibs, the updated Native test linked successfully with one Gradle worker. The real PostgreSQL/local-StorageOperator lifecycle passed in the separate `sticker_test_immutable` database (also explicitly allowed by the test guard). This did not reset the shared HTTP/mobile database or restart its server.

The same logical `smile` key now uses a valid 1x1 PNG in v1 and a different valid 16x16 PNG in v2. Assertions verify distinct resource IDs and expected SHA-256 digests, and read both original byte arrays after v2 publication, offline, and cleanup. The successful run emitted both `STICKER_IMMUTABLE_REPLACEMENT_EXECUTED` and `STICKER_POSTGRES_EXECUTED`. The database and objects are retained for inspection and eventual parent-task cleanup.
