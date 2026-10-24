# Archive Delivery Acceptance

## Scope and Contract

Backend module only; no frontend, app, SDK, framework, shared fixture host, account
source, or production deployment changes. Existing dirty work was preserved.
No commit/push. The optional admin export button is not included.

[Pinned contract](ARCHIVE_DELIVERY.md): authenticated version-row descriptor and
download, immutable publication/lazy-legacy snapshot, deterministic Stored ZIP,
original digest/size verification, exact cache repair, and optional install CAS.
**Final cap is 33554431 bytes (<32 MiB)**, superseding the initial 64 MiB proposal.
The archive budget includes every Stored ZIP header/name, not just file payloads;
ingress is unchanged. No chunking or StorageOperator bypass is used.
V005 is required before starting the rebuilt backend. Existing published packs
need no manual backfill; store/installed reads never generate whole-pack ZIPs.

## Executed Gates (2026-10-24)

- Production compile through the normal application composite and KSP registry:
  `JAVA_OPTS=-XX:ReservedCodeCacheSize=256m ./gradlew :module-sticker:compileKotlinMacosArm64 --offline --max-workers=1 --console=plain`
  from `privchat-application`. PASS; final log
  `/tmp/sticker-archive-release-main-compile.log` (40s), including V005 embedding.
- Actual Kotlin/Native production-library test executable, linked with strict
  partial-linkage diagnostics and the existing resolved test classpath:
  `bash tests/native/build-archive-tests.sh /tmp/module-sticker-pag-test-classpath.txt /tmp/sticker-archive-release-native`.
  PASS; compile log `/tmp/sticker-archive-release-native/compile.log`.
- Fresh PostgreSQL/local StorageOperator run: source the private environment made
  by `bash tests/pg/create-archive-database.sh`, then run
  `/tmp/sticker-archive-release-native/archive-tests.kexe --ktest_filter=sticker.logic.StoredStickerZipTest.*:StickerDeliveryPostgresTest.*`.
  PASS 4/4 (not environment-skipped). Final log
  `/tmp/sticker-packs.x6Yzf9/archive-release-pg-tests.log`.
- Actual KSP production routes over loopback HTTP, the real Logic, PostgreSQL and
  local private StorageOperator: PASS **47 responses**, plus ZIP/DB/byte assertions.
  Log `/tmp/sticker-packs.me3o0H/archive-release-http-tests.log`; sanitized report
  `/tmp/sticker-packs.me3o0H/archive-http-report.json`.
  The existing host test uses explicit signed test identity realms, not production
  login/session-revocation acceptance. Only its own fresh DB and port 55489 were
  used; the parent's existing host was not restarted or migrated.
- HTTP PAG v2 ingress uses the already-built official libpag **4.5.98** inspector
  executable and actual `weey-miss-you-sticker.pag` bytes; no metadata test double,
  new codec engine, native app build, Xcode build, or simulator was used here.
- `node --check tests/http/archive-lifecycle.mjs`, shell syntax checks for the
  touched setup/build scripts, and `git diff --check`: PASS.

The real DB gate verifies publication snapshot/rename immutability, exact missing
and corrupt ZIP repair, source digest rejection, immutable snapshot SQL guard,
legacy lazy generation/no listing backfill, concurrent materialization, account
access/delisted/uninstall rules, version CAS, and publication rollback. A failing
StorageOperator writes a real partial object then throws: the pointer rolls back,
the durable reservation remains, retry succeeds, and bounded orphan cleanup deletes
only unreferenced expired generations. Referenced caches and originals survive.
The final cohort also holds the maintenance lock while a reservation is removed,
then proves the waiting downloader fails without writing an untracked object;
a fresh request succeeds. The exact `<32 MiB` header-inclusive limit and limit+1
rejection are tested without allocating a giant fixture.

The HTTP gate independently checks all local/central/EOCD fields, contiguous
offsets, fixed flags/timestamps, CRC, whole/file SHA and size, immutable resource-ID
mapping, PNG cover deduplication, v2 PAG/fallback bytes and logical key/emoji mapping,
auth/realm/draft/unknown denial, no-store/ETag/length/disposition headers, author
rename + cache repair, source corruption rejection, installed-delisted access and
uninstall denial, install CAS, lazy legacy rows, and concurrent downloads.
It also injects oversized resource metadata, verifies `STICKER_ARCHIVE_SIZE`
before body reads, and checks that publication remains draft with no snapshot.

Before the final bound/retention-guard refinement, the existing rules/archive/PAG
port/default-wire/default-install regression runner passed 32/32, including its
real PG lifecycle marker. Log `/tmp/sticker-packs.uVlkI2/archive-baseline-regression.log`.
Those are baseline results, not a substitute for the final cohort's 4/4 + 47 gates.

## Frozen Handoff

Final tested kexe (business source frozen, no further module changes planned):
`/tmp/sticker-archive-release-native/archive-tests.kexe`

SHA-256: `80154dece6914fee85b6b5e92e36be2216d248d5a73c1fac3871e1050ca9af59`.
V005 SHA-256: `09f3c9204d92f62a0caf75a6aa8d381690c8722b431d2ec7f23e74c9862b92cf`.
Source hashes: `docs/evidence/archive-delivery-source-sha256.txt`.
The owned 55489 host was stopped normally after PASS; the parent's 55481 host,
shared DB, private UID source and token glue were not changed.

Faraday independently ran the production client Stored ZIP/CRC/SHA/manifest/native
signature validator and actual filesystem prepare/commit/resolve against captured
real HTTP v1/v2 artifacts from `/tmp/sticker-packs.raLUIh`. Both passed, including
after its bound was aligned to 33554431. v1: 16702 bytes/SHA
`bd8963bb6fc5ba7e8ffa6789d0275cf39e54fc4c7fd7f89d9b90138187d32a6a`;
v2: 20354 bytes/SHA
`bae9bf86a0ac95db57ec01657c05ef2daf6c2d163c726b3447e9bc45888ebd7b`.
This is archive interoperability, not client auth-HTTP or PAG playback acceptance.
Its separate pending/tmp quota crash-recovery defect remains assigned to its owner;
this backend report does not mark that client feature fully closed.

## Artifacts and Reproduction

Token-free real HTTP artifacts (retained for independent client validation):

- `/tmp/sticker-packs.me3o0H/archive-descriptor.json`
- `/tmp/sticker-packs.me3o0H/archive-delivery.zip`
- `/tmp/sticker-packs.me3o0H/archive-pag-descriptor.json`
- `/tmp/sticker-packs.me3o0H/archive-pag-delivery.zip`

Start only an owned fresh fixture with `STICKER_PACK_HTTP=1`,
`STICKER_PACK_PORT=55489`, its generated private environment and
`STICKER_PACK_INSPECTOR=<existing official inspector executable>`, filtered to
`StickerPackHttpHostTest.*`. Set `STICKER_PACK_HANDOFF` to the private path emitted
by that host and `STICKER_ARCHIVE_PAG` to the actual compatible sticker fixture;
run `node tests/http/archive-lifecycle.mjs`. The script refuses another origin or
storage root and never logs tokens. Stop only that owned host with its root's
`stop` file. Logs/artifacts/builds and isolated databases are retained.

These tests cover real PostgreSQL + local storage; S3 delivery was not executed.
The phase-1 framework buffers ByteArrays; delivery is bounded, not streaming.
Client atomic-index/journal acceptance belongs to the separately owned cache
engine/integration work, not this backend test count.

## Migration and Fixture Handoff

Normal deployment owner: rebuild the host with embedded V005, retain its existing
configuration/identity sources, then use that binary's normal `migrate status`,
`migrate verify`, `migrate up`, `migrate status` commands. One migrator only. Normal
startup performs no DDL. Never edit V001-V004, reset accounts, or manually bypass
the production migration history. V005 only creates the two archive tables, their
indexes, and the snapshot immutability trigger; it does not rewrite packs, source
resources, authors, installs, menus, or accounts. No all-pack generation/backfill.

For the parent's **manually migrated disposable fixture only**, its owner may
stop/restart only that fixture, apply V005 once using `psql -X -v ON_ERROR_STOP=1
--single-transaction -f sql/postgresql/V005__immutable_archive_delivery.sql`, and
rebuild its existing owned HTTP harness with the same private UID handoff/source.
Do not run that manual DDL against a migration-managed deployment. Preserve the
old fixture's credentials/UID ownership; this task did not modify or restart it.

For the owner's existing 21-pack fixture on **55481**, use its original private
environment/storage root, not any acceptance root listed above:

1. Stop only the old owned host, wait for its process and 55481 listener to exit.
2. Apply V005 once, transactionally, to that fixture DB; preserve V001-V004 and all
   21 packs/resources/installs. No source backfill or ZIP generation command.
3. Clear only that owned root's old `stop` sentinel after shutdown. Retain the
   original inspector setting and set `STICKER_PACK_HTTP=1`,
   `STICKER_PACK_PORT=55481`. Set `STICKER_PACK_ACCOUNT_IDS` from the owner's
   existing exact-UID private source (comma-separated positive decimal IDs), not
   newly registered/dummy identities. This host supports up to 32 additional UIDs.
4. Start the verified final archive kexe with
   `--ktest_filter='StickerPackHttpHostTest.*'`, redirecting to a new owned log.
   Wait for `STICKER_PACK_HTTP_READY=http://127.0.0.1:55481`.
5. Consume its freshly emitted **private** handoff for the same exact UIDs. This
   test host creates a fresh signing key on restart, so old fixture tokens are not
   reusable. Updating the owner's private token glue is not changing accounts.
   Check store count/IDs/default flag first; request only the selected pack's
   descriptor/download. Do not run this module's destructive-corruption HTTP test
   script against that shared 21-pack fixture.

For independent acceptance, from this module:

```bash
root=$(bash tests/pg/create-archive-database.sh)
source "$root/environment.sh"
# Setup creates a NEW DB and applies V001/V003/V004/V005; no drop/reset/migrate-existing.
export STICKER_PACK_HTTP=1 STICKER_PACK_PORT=55489
export STICKER_PACK_INSPECTOR=/absolute/path/to/existing/pag-inspect
/tmp/sticker-archive-release-native/archive-tests.kexe \
  --ktest_filter='StickerPackHttpHostTest.*' > "$root/host.log" 2>&1 &
# Wait for STICKER_PACK_HTTP_READY, then privately obtain the emitted handoff path.
export STICKER_PACK_HANDOFF=/private/emitted/handoff-path
export STICKER_ARCHIVE_PAG=/absolute/path/to/actual/sticker.pag
node tests/http/archive-lifecycle.mjs
touch "$root/stop"
```

The host's V002 menu fixture is needed only if separately exercising its
permission-info route: `bash tests/pg/setup-pack-admin-fixture.sh "$root"`.
Archive tests do not call that route. Its test credentials are not production
accounts and must not be printed/shared. Reuse the already-built focused backend
test binary when its business sources are unchanged; no APK/framework build is
needed. Preserve logs, ZIP/descriptor artifacts, and builds after stopping the
owned acceptance host.
