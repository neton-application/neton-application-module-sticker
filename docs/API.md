# Sticker API v1 (implementation contract)

PLATFORM only. Admin realm uses the existing system authenticator; no C-end resource upload APIs.
JSON IDs are decimal strings. Standard host response envelope applies. All paths below include route group.

## Independent Packs and Default Installation

`PackDto` appends `coverImageUrl: String? = null` and `defaultInstall: Int = 0`.
Both fields are emitted explicitly. `coverId`, pack database `id`, stable
`packId`, `version`, sticker IDs/keys and historical URLs are unchanged. Each
pack is independently listed/installed/uninstalled/reordered; these are not
emoji categories. The 256-sticker pack limit is unchanged. Generated Apple
resources are ordinary v1 PNG packs, not backend hardcoded/seeded keywords.

- Admin cover URL is `/admin/sticker/resource/get/{coverId}`; use the existing
  authenticated private-image fetch. Never add tokens to URLs.
- Store/installed covers use the same absolute public resource route as sticker
  images. Existing ready/ever-published access checks and immutable bytes apply.
- `PackWrite.defaultInstall` and `/pack/make` multipart `defaultInstall` are
  optional integers defaulting to 0. Only 0/1 is valid; booleans/invalid text fail.
- v1 and v2 manifest `default_install` is optional, integer 0/1, omitted means 0.
  Import reads policy only from the manifest, never a multipart override.
  Filling an empty manual draft requires policy, name and author to match.
- Admin policy edits are draft-only, then published through the existing action.
  Published/offline metadata is not rewritten; publish a new version instead.
- `/app/sticker/store` lists published packs with cover/default metadata but
  does not install on the caller's behalf.
- `/app/sticker/installed` initializes published default packs under the existing
  per-account advisory transaction lock. It only inserts when that account has
  no prior install decision for the stable packId. Defaults published later can
  initialize existing accounts on their next installed/install/order operation.
- Uninstall persists `sticker_installs.status=0`; reads, new tokens, account
  switches, and new versions do not resurrect that pack for that account.
  Explicit install sets status=1 and appends to the active order. Repeating an
  active install does not move it. No account identifier is accepted from callers.
- Reorder requires exactly all current active packIds with no duplicates,
  excluding tombstones. Installed offline packs retain the previous visibility
  policy; uninstall/offline never invalidates published message resources.

Apply new V004 through normal migrations before starting the updated module.
Existing pack rows default to opt-in-only (0), existing install rows stay active
(1). Pre-V004 physical deletions cannot retrospectively be reconstructed as
opt-outs. There is no startup DDL and no default-pack asset seed migration.

Disposable real PG/socket harness and commands: [default-pack acceptance](DEFAULT_PACK_ACCEPTANCE.md).

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
PackWrite {id?:string,packId:string,name:Record<string,string>,authorId:string,version:number,defaultInstall?:0|1}.
PackDto {id,packId,name,authorId,version,publicationState:1|2|3,stickers:StickerDto[],coverId:string|null,createdAt:number,coverImageUrl:string|null,defaultInstall:0|1}.
StickerDto {stickerId:string,key:string,emoji:string,width:number,height:number,mimeType:string,size:number,imageUrl:string}.
Permissions sticker:pack:query/create/update/delete/publish/offline/import/make. Asset preview uses query.
GET /admin/sticker/resource/get/{id} returns authenticated bytes, never a public draft URL.
POST /admin/sticker/resource/cleanup requires sticker:pack:delete; reclaims only unattached uploads older than 24h.

App /app/sticker: GET /store, /installed -> PackDto[]; POST /install,/uninstall {packId}; POST /reorder {packIds:string[]} (exact installed set).
GET /resolve/{id} -> StickerDto; GET /resource/get/{id} -> anonymous read-only bytes for ever-published resources. Only ever-published resources resolve, including offline/old versions; install does not gate historical access.
No legacy string ID migration is inferred.

### Sticker v1 ingress limits

All newly received images, including ZIP covers and sticker entries and `/pack/make` uploads, use the same bounded structural validator (not a full pixel decoder):
- Static PNG and static WebP (lossy/lossless) remain supported; APNG is rejected.
- Each image is at most 500 KiB (512000 bytes). Width and height must each be 1..512 pixels, inclusive. Rectangular images are accepted; square geometry is not required.
- Animated WebP additionally allows at most 90 ANMF frames and at most 3000 ms summed ANMF duration per animation cycle. Each frame duration must be at least 34 integer milliseconds, enforcing <=30 fps without rounding. These limits apply together: 89 frames already need at least 3026 ms, so at most 88 minimum-duration frames fit the duration budget. Loop metadata does not multiply the per-cycle duration.
- Malformed/truncated chunks, missing frame data, out-of-canvas frames and mismatched bitstream dimensions are rejected. Existing compressed-work and compositing-work safeguards remain.
- Max 256 stickers; ZIP and expanded package each <32 MiB. Existing ZIP protections remain: root files only, stored/deflate entries, no duplicate/extra entries, encryption or ZIP64; CRC, local/central record consistency, non-overlapping ranges and compressed/expanded work budgets are checked.

ZIP `manifest.json` fields are `{schema_version?:1,pack_id,name,author,version,cover,stickers:[{key,file,emoji?,width,height}]}`. Omitted `schema_version` defaults to 1 for legacy packages; any other integer version is rejected with `STICKER_UNSUPPORTED_SCHEMA_VERSION`. Invalid field types are rejected as an invalid manifest. `schema_version` is independent of the pack's positive release `version`. Manifest author is descriptive; authorId is authoritative and display name must match.

These are ingress-only limits: historical catalog, resolve and resource reads are not revalidated or rewritten. Publishing an already-stored legacy draft still checks resource readiness/existence, not the new ingress dimensions or animation budgets. Existing `key` values and immutable resource `stickerId` (`sticker_id` in messaging) identities are unchanged, including historical images larger than the new limits.
Same complete import is idempotent by packId/version and input digest; different same-version input conflicts. Versions strictly increase. Published metadata/resources cannot be edited; create a new version. Offline cannot be republished; create a new version.

Storage prerequisite: provision StorageManager source `sticker-private`, with NO public bucket access, static file mapping or baseUrl. Unpublished bytes are served through admin-authorized routes. DB rollback never undoes object writes. Upload reservations persist before writes and cleanup takes the same DB lock as attachment. Published objects are retained indefinitely by ordinary operations.

## PAG Import v2 (Backend)

`POST /admin/sticker/pack/import-v2` takes the same multipart `file` and `authorId` as v1 and requires the existing `sticker:pack:import` permission. This is an explicit v2 endpoint: `/import` continues to decode only the strict v1 DTO, rejecting PAG descriptors, unknown fields and extra ZIP files. No existing v1 JSON Schema was changed.

Machine-readable contract: [sticker-manifest-v2.schema.json](sticker-manifest-v2.schema.json). Example v2 `manifest.json` (all other pack fields retain v1 meanings):

```json
{"schema_version":2,"pack_id":"hearts","name":{"en":"Hearts"},"author":"Licensed author","version":1,"cover":"cover.png","stickers":[{"key":"heart","file":"fallback.webp","width":244,"height":244,"pag":{"file":"heart.pag","profile":"pag-sticker-v1"}}]}
```

Every PAG entry requires a structurally validated **static** PNG/WebP fallback. Cover, fallback and PAG each independently count against the 500 KiB per-file ceiling and existing <32 MiB compressed/expanded ZIP budgets. v2 permits at most 514 root files (256 fallback + 256 PAG + cover + manifest); v1 remains at 258. No paths, undeclared files, missing references or unknown profiles are accepted. PAG canvas may differ from fallback canvas, but both are <=512 per axis. An animated WebP is not the required static fallback. Optional animated-only entries remain governed by the existing WebP validator.

`StickerDto` adds `pag:null|{resourceId,url,sha256,size,width,height,durationMs,profile:"pag-sticker-v1"}`. Existing `stickerId`/`imageUrl` identify the fallback forever. PAG has a separate immutable resource ID and a frozen association, created in the attachment transaction. Both resources become public only when the pack publishes; `ready=1` and `ever_published=1` are required for anonymous bytes. Admin previews require `sticker:pack:query`. `/app/sticker/resolve/{id}` retains account-realm authentication and only accepts fallback IDs, never returns raw PAG as an old client's image. Update/offline preserve the old fallback and PAG bytes/association. Legacy draft publishing and historical reads are not revalidated against new limits.

For 1.0.0-beta, PAG metadata and `sticker_pag_links` are included in
`V001__schema.sql`; menus and initial data use `V002__data.sql`. Rebuild and use
normal embedded migrations on a fresh database. The superseded pre-beta patch
history is recorded in `sql/baseline.json`. No startup DDL or checksum bypass.

### Official Inspector Port

Optional module config key `pag_inspector_executable` is a fixed absolute path to the official libpag 4.5.98 CLI built by the native owner. Missing configuration/executable, nonzero exit, malformed/incomplete JSON, unsupported SDK/profile, failed parse or failed bounded render **rejects the upload before reservations/object writes**. No magic-header acceptance and no request-provided executable, command, local path, metadata or URL can replace inspection.

Invocation is `CONFIGURED_ABSOLUTE_BINARY TEMP_FILE sticker`; no shell or PATH resolution. Child environment is limited to `PATH=/usr/bin:/bin` and `LANG=C`. Temporary files are mode 0600 and removed on completion/failure; wall timeout is 10s, child CPU 5s, output 16 KiB, core dumps disabled. This is process containment, not an OS network/RSS sandbox: production deployment must additionally isolate the inspector with a memory cap and no network. Do not configure an on-demand build wrapper as the production executable.

The adapter consumes the native owner's actual `valid,sdkVersion,bytes,width,height,durationMs,frameRate,hasAudio,numVideos,numImages,numTexts,tagLevel,maxSupportedTagLevel,layerCount,maxDepth,truncated,sha256,profile,renderProbe,probeFrameMs,probeMaxFrameMs` JSON, plus the required codec inspection fields `codecParsed,codecHasAudio,codecLayerCount,codecTruncated,compositionBoundsValid,compositionCount,maxCompositionWidth,maxCompositionHeight,maxCompositionDurationMs,embeddedImageCount,imageLayerCount,textLayerCount,bitmapCompositions,videoCompositions`. Missing fields fail closed. Initial supported compositions are vector-only: no audio, video, embedded images or text/font dependencies; <=128 layers/compositions and depth <=8 (native zero-based depth). All nested compositions must also stay within the profile's axis/duration limits, not just the root canvas. This deliberately rejects data whose texture/font budgets the initial CLI cannot report. PAG timeline rate is 1..60 fps, separate from the WebP integer-ANMF rules. Sticker remains <=500 KiB, <=3000ms, <=512 per axis; effect is an independent profile/module.

Three actual offscreen sample probes must succeed, with finite nonnegative durations <=250ms each (including cold setup), and required tag level must be supported by the pinned official parser. These conservative upload screening limits are not mobile frame-time/RSS acceptance. CLI/library checksums and deployment provenance remain operator responsibilities; a test implementation of the port is never installed by bootstrap.

## Host configuration and tests

`config/sticker.conf`: `public_base_url` must be an absolute externally reachable API base URL, with any reverse-proxy prefix included, no trailing slash required. Published imageUrl is `${public_base_url}/app/sticker/resource/get/{id}` and requires NO token. The route checks `ready=1 AND ever_published=1`; resolving a draft returns not-found. Ordinary offline never revokes historical public images.
Default Neton HTTP body limit is 16 MiB. Host adapter must allow 34 MiB bodies (ZIP + multipart overhead), and at least 68 MiB buffer budget. Reverse-proxy limits must agree. Module still enforces <32 MiB ZIP and expanded data limits.
`pack/make` and `pack/import` can fill an existing EMPTY draft of the same packId/version only when authorId, names and defaultInstall match; subsequent upload retry must match digest.

Pure rules/auth boundary tests: from privchat-application, `./gradlew :module-sticker:macosArm64Test --max-workers=2`.
Focused v1 archive/rules tests: `./gradlew :module-sticker:macosArm64Test --tests 'sticker.logic.StickerArchiveTest' --tests 'StickerRulesTest' -Pneton.database.driver=postgres --max-workers=1`. No database or HTTP host is started by these filtered tests.
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

## Whole-pack offline delivery

See [immutable archive delivery v1](ARCHIVE_DELIVERY.md) for the authenticated
version-row descriptor/download contract, install version guard, limits and
immutable snapshot/storage lifecycle. Single-resource endpoints are unchanged.
## Account Favorites And Search

All routes below use the account access-token realm at `/app/sticker` (not admin
or refresh tokens). Member identity comes only from the authenticated principal.
Responses are `private, no-store` and use the existing `{code,data,message}` envelope.

- `GET /app/sticker/favorites`: `StickerDto[]`, newest favorite first.
- `POST /app/sticker/favorite/add`: body `{ "stickerId": "123" }`, returns `true`.
- `POST /app/sticker/favorite/remove`: same body, returns `true`, including absent rows.
- `GET /app/sticker/search?query=smile`: `{ "packs": PackDto[], "stickers": StickerDto[] }`.

Favorites persist independently of pack installation and default-install opt-outs.
Duplicate/concurrent adds are idempotent and do not change ordering. Each account
may keep at most 1000 favorites; an existing favorite can still be added at capacity.
Only ready PNG/WebP sticker entries from published immutable versions are eligible;
delisted history is eligible, but drafts, cover-only resources, raw PAG resources,
missing resources and C-end uploads are not. PAG links remain optional representations
of the immutable fallback sticker ID. Favorites never grant upload capability.

Search accepts at most 128 characters, rejects control characters below U+0020,
trims outer spaces, and returns empty lists for an empty query. Matching is
case-insensitive literal substring matching on pack ID, localized name values,
sticker key and emoji. `%`, `_` and `!` are literals, not wildcard operators.
There is no English semantic expansion for Unicode emoji labels. Results contain
at most 20 currently published packs and 100 distinct sticker IDs. Individual
stickers are currently published or favorited by this account in delisted history;
another account's favorites never broaden visibility. Matching published packs sort
default first, then this account's active installations, then newest first;
individual stickers sort by immutable resource ID. IDs stay strings.

Favorites are included in the 1.0.0-beta `V001__schema.sql` baseline. Apply both
baseline files through the versioned module migration system on a fresh database
before serving these endpoints; normal request/startup paths never perform DDL.
