# PAG Backend Acceptance (2026-10-23)

## Scope

- Sticker: explicit strict import-v2, optional PAG descriptor, verified static
  fallback, immutable association, new V003 PostgreSQL migration, existing
  author/draft/publication/resource authorization and historical v1 behavior.
- Independent effect sibling: real SQL-backed resources, rights metadata,
  admin effect/rule actions, official PAG import, immutable revision snapshots,
  account catalog, rule versions/expiry and offline snapshot revocation.
- Shared code checkout: prior v1 changes retained; tools/sticker-tool, frontend,
  mobile/native source and parent distribution source not edited by this task.
  Parent separately assembled the new module/config/private storage source.
  No commits.

## Executed Evidence

### Original MIT Asset Recheck

PG test sources now require original Weey assets instead of the earlier upstream
guide/LOGO positive fixtures: `weey-kiss-sticker.pag` with its real PNG fallback
(256x256, 2400ms), and `weey-kiss-effect.pag` (720x1280, 4000ms). Effect rights
explicitly record MIT / Weey / PrivChat contributors. The retained runner checks
the source MIT license and manifest SHA256 values before creating the ZIP.

Command (actual compiled production-linked test executable, not a mock adapter):

```sh
bash /Users/zoujiaqing/projects/privchat/neton-application-module-sticker/tests/pg/run-original-pag.sh \
  /tmp/sticker-pag-focused.SuxlZe/production-tests.kexe
```

It creates a new `pag_backend_test_original_<timestamp>` database on the same
guarded loopback cluster, now applies production sticker V001/V003/V004 and effect
V001/V002, selects only the two PG tests, sets all required opt-ins, and requires
both execution markers. Existing databases are never wiped. Setup, fixture
metadata, pre-execution binary/CLI hashes and raw results are retained under its
printed `/tmp/pag-postgres.original.*` directory.

Executed successfully at 23:28 on 2026-10-23, using the current official inspector:
`/tmp/pag-postgres.original.xD4tjW/acceptance.log`, database
`pag_backend_test_original_20261023232855`. **Exactly 2 PG tests ran and passed**:
effect 338ms and sticker 250ms; both post-assertion execution markers are present.
There were no missing-environment skips in this selected run. Raw results, setup,
source metadata and pre-execution binary hashes are copied into
`docs/evidence/pag-20261023/original-mit/`. This supersedes the old upstream-fixture
PG result below; the 54-check HTTP result below remains the earlier separate run,
not a newly repeated HTTP test with original assets.

An initial setup attempt resolved the workspace root incorrectly; a second
attempt reached the real tests and exposed a fixture manifest/author mismatch.
Both attempts remain in separate disposable databases/directories, not reset.
The runner fixes only the path and matching fixture author, without changing
production validation or weakening any profile limits.

### Opt-in Result Correction

The default `/tmp/sticker-pag-focused.SuxlZe/production-tests.log` is NOT
PostgreSQL/HTTP acceptance: without environment opt-ins, those test functions
return immediately and the runner still reports green. Do not use its 37-test
total to claim integration execution.

The separate opt-in `/tmp/pag-postgres.ZYhIXH/acceptance.log` contains:

```text
EFFECT_POSTGRES_EXECUTED: official libpag + real PG/storage + rights + rule versions + atomic snapshots + revocation
[       OK ] EffectPostgresTest.officialPagAndPublishedRuleSnapshots (325 ms)
STICKER_PAG_POSTGRES_EXECUTED: official libpag + real PG/storage + immutable fallback/PAG + legacy 513px publish/read
[       OK ] StickerPagPostgresTest.officialPagImportPublicationAndHistoricalFallback (244 ms)
```

These markers occur after the assertions, not before setup. The HTTP checks ran
separately and recorded 54 successful checks. Raw logs are now retained in this
module under `docs/evidence/pag-20261023/`, with SHA256SUMS for the logs and the
currently retained test executable/official inspector. These hashes were captured
at handoff, not during the earlier executions; the native owner's inspector may
have been rebuilt since those tests. They do not establish a historical binary
hash for that run. `retained-db-counts.log` is a later
read-only query of the same live PostgreSQL database, not a substitute for test
assertions. It includes the subsequently published mobile fixtures.

The temporary HTTP host later reached its intentional 900000ms lifetime limit;
its host test reports TimeoutCancellationException / FAILED. This is preserved
as `http-host-timeout.log`, not hidden as a passing host test. The 54-check
client completed earlier. No HTTP host is claimed to remain running, and there
is no claim of a normal assembled-product startup.

Both module Native main and full owned test-source compilations succeeded. The latter
ran through normal parent settings, without an init script:
`./gradlew :module-sticker:compileTestKotlinMacosArm64 :module-effect:compileTestKotlinMacosArm64 -Pneton.database.driver=postgres --offline --max-workers=1`.
Log: `/tmp/pag-backend-owned-tests-compile.log`.
The temporary assembly script
was used only before the parent included module-effect; subsequent checks
use normal project settings. Generated `ChateffectModuleManifest` calls
`init.ChateffectRuntimeBootstrap.initialize(ctx)` before registering 19 routes.
Both manifest and embedded migration module ID are `effect`; config is
`effect.conf`, explicitly selected by bootstrap. Registration is not proof
of a production host startup or production database migration.

Focused executable: `/tmp/sticker-pag-focused.SuxlZe/production-tests.kexe`.
Rebuild command: `bash /tmp/sticker-pag-focused.SuxlZe/run-production-tests.sh`.
It compiles tests against the actual compiled production module libraries,
including their real C interop, Neton database/storage/security and PostgreSQL
driver dependencies. It does not substitute a fake database or image inspector.

- 34 non-integration tests passed: StickerRulesTest (2), StickerArchiveTest (21),
  PagImportTest (5), ExternalPagInspectorTest (2), EffectRulesTest (4).
- `StickerPagPostgresTest.officialPagImportPublicationAndHistoricalFallback`
  actually passed with official libpag 4.5.98, the current codec-aware CLI,
  `guide_hand_drag.pag`, real PostgreSQL and local StorageOperator. It proves
  default missing-validator denial before reservations, separate draft/PAG IDs,
  private draft bytes, idempotent retry, publication, offline preservation and
  legacy 513px draft publication/read without new-ingress revalidation.
- `EffectPostgresTest.officialPagAndPublishedRuleSnapshots` actually passed
  with the same real dependencies and `PAG_LOGO.pag`: rights validation, magic-only
  input rejection, missing-validator denial, private draft bytes, immutable
  same-byte retry, publish/catalog separation, incrementing ruleVersion, frozen
  published JSON, new snapshot publication and offline revocation.
- Full runner reports 37 tests passed when integration variables are present.
  This means 34 normal tests + 2 executed DB tests + 1 HTTP-host test returning
  without starting. The host's optional return is NOT counted as HTTP evidence.
- `tests/http/pag-lifecycle.mjs`: **54 checks passed** against actual generated
  routes at loopback 127.0.0.1:55477, real PostgreSQL/storage, actual shared
  realm/RBAC/CSRF dispatch and official CLI. Includes anonymous/account/refresh/
  readonly denials; strict v1 rejects v2; true oversized/internal-composition PAG
  rejection; draft/public byte boundaries; fallback/PAG identity and digest;
  rights/resource/rule/catalog writes; version freezing; and offline revocation.

Real DB execution markers:
`STICKER_PAG_POSTGRES_EXECUTED`, `EFFECT_POSTGRES_EXECUTED`.
Real HTTP marker: `PAG_HTTP_EXECUTED`.

Logs and retained setup:
- `/tmp/pag-postgres.ZYhIXH/acceptance.log`
- `/tmp/pag-postgres.ZYhIXH/http-acceptance.log`
- `/tmp/pag-postgres.ZYhIXH/http-host.log`
- `/tmp/pag-postgres.ZYhIXH/migrations.log`
- `/tmp/pag-postgres.ZYhIXH/run-acceptance.sh`
- `/tmp/sticker-pag-focused.SuxlZe/run-production-tests.sh`
- `/tmp/sticker-pag-focused.SuxlZe/PagHttpServerTest.kt`
- `/tmp/sticker-pag-focused.SuxlZe/production-compile.log`

DB tests require the explicitly guarded loopback database
`127.0.0.1:55476/pag_backend_test`, pre-applied production sticker V001/V003 (new runs also require V004) and
effect V001/V002, and empty relevant tables. They refuse to wipe existing data.
The cluster is private disposable test infrastructure, initialized with trust
authentication and an explicit test-only user; no production credentials or
databases are involved. Failed protocol-attempt databases were preserved under
separate names, not reset over someone else's fixtures. Repeat DB tests require
a new empty disposable database setup; rerunning on retained successful fixtures
must fail the empty-table guard.

### Retained Commands

The exact PG environment and fixture paths are retained in
`/tmp/pag-postgres.ZYhIXH/run-acceptance.sh`. Run it only after providing a new
empty, production-migrated disposable database at the guarded endpoint; it is
not safe to expect success against the already populated retained database.
The focused rebuild script also runs the default non-opt-in suite; that output
alone is not database evidence.

For a fresh HTTP session against the retained disposable fixture DB:

```sh
export PAG_TEST_DATABASE_URI='postgresql://sticker_backend_test:testonly@127.0.0.1:55476/pag_backend_test'
export PAG_TEST_STORAGE_ROOT='/tmp/pag-postgres.ZYhIXH'
export PAG_TEST_INSPECTOR='/Users/zoujiaqing/projects/privchat/privchat-app/privchat/iosApp/build/pag-sdk/pag-inspect'
PAG_HTTP_HOST=1 /tmp/sticker-pag-focused.SuxlZe/production-tests.kexe \
  --ktest_filter=PagHttpServerTest.serveLoopback \
  > /tmp/pag-http-rerun-host.log 2>&1 &
# Wait for PAG_HTTP_READY in the host log before running the client.
PAG_HTTP_LOG=/tmp/pag-http-rerun-host.log \
PAG_FIXTURE_ROOT=/Users/zoujiaqing/projects/privchat/privchat-app/privchat/iosApp/build/pag-sdk/fixtures \
node /Users/zoujiaqing/projects/privchat/neton-application-module-sticker/tests/http/pag-lifecycle.mjs
# Stop the temporary host gracefully after the client completes:
touch /tmp/pag-postgres.ZYhIXH/http-stop
```

If reusing this host command later, remove only that harness's `http-stop`
sentinel first. HTTP tests add timestamped fixtures; they do not wipe tables.
The host uses explicit temporary fixture realms, not production system login.

## Mobile Wiring Fixture

After HTTP acceptance, six original Weey PAG assets and their real static PNG
fallbacks from the native owner's `resources/pag` were submitted through actual
admin APIs, officially inspected and published into the disposable database.
No keywords were added to service code or seeded production migrations.

Retained records:
`/tmp/pag-postgres.ZYhIXH/mobile-sticker-pack.json` and
`/tmp/pag-postgres.ZYhIXH/mobile-effect-catalog.json` (three rules; initial revision
7, subsequent authorized expiry refresh revision 8).
`publish-mobile-fixtures.mjs` in that directory records the actual API workflow
and verifies source file hashes. Expiry is finite and the HTTP host is bounded;
these fixture URLs are not permanent production endpoints.

## Limits and Gaps

- Parent/shared UI agent's separate iOS run is now confirmed as 4 tests,
  0 failures, 58.011 seconds in `/tmp/privchat-pag-ui-runtime/test.log`, with
  `/tmp/privchat-pag-ui-runtime/evidence/20261023-231210.xcresult`.
  This is attributed external evidence, not part of this backend's test count.
- Official vector-only parsing/codec checks and three offscreen sampled frames
  are real. This is not full-timeline/device performance, GPU/RSS acceptance,
  Android/iOS shared UI playback, accessibility or cross-device messaging proof.
- The current CLI rejects like.pag as a sticker because nested compositions
  exceed 512, even though its root is 244px. Tests use a genuinely accepted
  official file; they do not weaken the profile to force that asset through.
- External stdout is bounded with a pipe, not RLIMIT_FSIZE: a global file-size
  rlimit incorrectly terminates legitimate Metal cache writes. CPU/wall/output
  limits remain. Production still needs OS RSS/network isolation. Only the
  macOS official inspector was exercised; Linux needs an equivalent configured
  official inspector and acceptance, otherwise imports remain denied.
- The socket harness installs explicit temporary test identities/real signed
  fixture JWTs with the shared verifier. It does NOT prove system login, MFA,
  persistent admin roles, session revocation, proxy/CDN deployment or a normal
  assembled-product startup. Production bootstrap installs no test identities.
- Host credentials live only in a mode-0600 temporary file; logs contain its
  path, not tokens. Normal host exit removes it. Remove it if forcibly killing
  the test host. No default/fake signing identity was added to production.
- Frontend management, pagination UI, user preference persistence, push
  revocation, editable text/avatars and production deployment are out of scope.
