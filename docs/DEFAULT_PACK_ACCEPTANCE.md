# Default Pack Backend Acceptance

> Historical acceptance record. Since 1.0.0-beta, the migration filenames
> below are superseded by `sql/postgresql/V001__schema.sql` and
> `V002__data.sql`. Rebuild and use the fresh-database migration contract in
> `sql/README.md`; do not replay old patch instructions on current databases.
> The fixture setup scripts now install real dependency baseline pairs and copy
> their menu rows into the isolated admin fixture. Old evidence hashes remain unchanged.


## Executed Results (2026-10-24)

- Production `:module-sticker:compileKotlinMacosArm64` passed through normal
  application settings, including embedded V004. No duplicate include/init script.
- 33 selected rules/archive/PAG-port/default-wire tests passed in 729ms.
- One selected real PostgreSQL/storage lifecycle test passed in 649ms with
  `STICKER_DEFAULT_PG_EXECUTED` after its assertions. It exercised default init,
  opt-out persistence across reconstructed Logic/read calls, account isolation,
  repeated install/order, new versions, explicit reinstall, offline/history
  preservation and four concurrent initializations. This was not an opt-in skip.
- Separate socket client passed 48 actual HTTP response checks, plus DTO/bytes/
  order/persistence assertions. `STICKER_DEFAULT_HTTP_EXECUTED` is present.
  Different signed tokens for the same fixture account verify that opt-out is
  stored by account, not token. This is not production login/relogin acceptance.
- Authenticated test permission-info returned 200/code0 with one real seeded
  menu root; frontend execution is owned/reported by its separate task.
- After the publisher's real21-pack import/publish, an additional actual API
  check confirmed 21 published packs, 3953 unique sticker IDs, covers on every
  pack, and only `apple-smileys-emotion` (171 stickers) default-installed.
  Uninstall persisted for reserved backend account9191 using its second token,
  while account9192 independently received the default. iOS account indices
  0..31 were not used by this check. Sanitized proof is
  `published-contract-check.json` in the evidence directory.
- Separate Aquinas-owned real shared iOS acceptance now passed **8/8**, zero
  failures, at 04:24 Singapore time on 2026-10-24 (443.772 seconds). The backend
  task independently read the final test log and confirmed its matching xcresult
  exists: `/tmp/privchat-sticker-pack-ui-runtime/evidence/20261024-041704.xcresult`.
  It exercised actual PlatformStickerCatalog against this real PG/socket host:
  defaults/21-pack store, covers, actual DTO selection callback, ordering/process
  restart, uninstall/history, default opt-out, two-account isolation and reinstall.
  No IM submission was claimed. Native UI/AX fixes and mobile execution belong
  to that task, not this backend implementation/test count.

Raw logs and hashes: `docs/evidence/default-packs-20261024/`.
PG root `/tmp/sticker-packs.61p6y3`; HTTP validation root
`/tmp/sticker-packs.cT5rir` (55480). Publisher/iOS root
`/tmp/sticker-packs.51H0Tf` (55479) is separate and was empty at handoff.
Private handoff paths were sent directly to the authorized agents, not checked in.
Host READY is not normal assembled-product bootstrap or deployment evidence.
Both hosts remain running pending the parent's final cleanup instruction;
Aquinas has completed the eight UI tests. They are bounded to
four hours from approximately03:40 Singapore time. No production deployment.

The first lightweight wrapper's final path-print step returned a shell EOF
diagnostic after its script was edited while the compiler was running. The
compiler had produced the executable; the actual tests above ran that executable.
Current wrapper passes `bash -n`, and future invocations set
`KONAN_JVM_ARGS=-XX:ReservedCodeCacheSize=256m`. No successful wrapper exit is
claimed for that earlier invocation and no expensive rebuild was needed to run it.

## Contract and Ownership

Only this sticker module changed. Previous PAG/v1 work and disjoint
`tools/sticker-tool` changes are preserved. No production deployment, commit,
startup DDL, emoji-category service, identity replacement or asset seed.
See [API](API.md#independent-packs-and-default-installation).

Migration note: old pack rows become default_install=0 and existing install rows
become status=1. Explicit uninstalls AFTER V004 persist as status=0. Physical
deletions made BEFORE V004 cannot be recovered as historical opt-out decisions.

V004 adds per-version pack `default_install` and per-account install `status`.
The same per-account lock covers initialization, explicit install/uninstall and
exact-active-set reorder. Explicit opt-outs are persistent rows, not deletions.

Changed production paths: `src/commonMain/kotlin/sticker/dto/StickerDto.kt`,
`src/commonMain/kotlin/sticker/logic/StickerLogic.kt`,
`src/commonMain/kotlin/sticker/logic/StickerRules.kt`,
`src/commonMain/kotlin/sticker/logic/StickerArchive.kt`,
`src/commonMain/kotlin/controller/admin/sticker/StickerPackController.kt`,
`sql/postgresql/V004__default_pack_installs.sql`.
New tests: `src/commonTest/kotlin/sticker/logic/StickerDefaultContractTest.kt`,
`src/macosArm64Test/kotlin/StickerDefaultInstallPostgresTest.kt`,
`src/macosArm64Test/kotlin/StickerPackHttpHostTest.kt`,
`tests/http/default-pack-lifecycle.mjs`. `build.gradle.kts` forwards the opt-in
environment; `tests/native/` and `tests/pg/` retain build/setup runners, including
V004 in the prior original-PAG fixture setup. API/README/v2 schema and sanitized
evidence were updated. Existing tools/agent changes were not edited.

## Build and Run

Retained executable from the executed checks:
`/tmp/sticker-packs.native.qoKvus/default-pack-tests.kexe`.
The exact selected policy command was:

```sh
/tmp/sticker-packs.native.qoKvus/default-pack-tests.kexe \
  --ktest_filter='StickerRulesTest.*:sticker.logic.*'
```

To reproduce the PG check without touching retained fixtures:

```sh
set -euo pipefail
root=$(bash /Users/zoujiaqing/projects/privchat/neton-application-module-sticker/tests/pg/create-default-pack-database.sh)
source "$root/environment.sh"
/tmp/sticker-packs.native.qoKvus/default-pack-tests.kexe \
  --ktest_filter='StickerDefaultInstallPostgresTest.*' | tee "$root/acceptance.log"
grep STICKER_DEFAULT_PG_EXECUTED "$root/acceptance.log"
```

The earlier PG root is deliberately populated and must fail the empty-table
guard if reused. Likewise the 48-check HTTP client expects its own fresh
database: the retained55480 database now also contains the frontend agent's
successful browser fixtures. Do not wipe it or claim a rerun against it passes.

From `/Users/zoujiaqing/projects/privchat/privchat-application`:

```sh
./gradlew :module-sticker:compileKotlinMacosArm64 -Pneton.database.driver=postgres --offline --max-workers=1
```

The lightweight Native runner links real production klibs, PostgreSQL driver,
storage, generated route initializers and shared auth/RBAC. It needs a resolved
macosArm64 test classpath file; current local retained path is
`/tmp/module-sticker-pag-test-classpath.txt`.

```sh
bash /Users/zoujiaqing/projects/privchat/neton-application-module-sticker/tests/native/build-default-pack-tests.sh \
  /tmp/module-sticker-pag-test-classpath.txt /tmp/sticker-packs.native.RUN
bash /Users/zoujiaqing/projects/privchat/neton-application-module-sticker/tests/pg/create-default-pack-database.sh
```

The latter creates a NEW private `sticker_packs_*` database on loopback port
55476 and applies actual V001/V003/V004. It never drops/resets an old database.
Each invocation prints a private `/tmp/sticker-packs.*` root with environment.sh.
The existing test cluster uses explicit test-only trust credentials; not production.
For the frontend host, run `tests/pg/setup-pack-admin-fixture.sh ROOT` once:
this applies real V002 menu rows to an isolated `fixture_admin.system_menus`
fixture table, outside server startup. It creates no accounts or persistent roles.
The test-only permission-info route uses shared authentication and those SQL rows.

```sh
source /tmp/sticker-packs.NEW/environment.sh
/tmp/sticker-packs.native.RUN/default-pack-tests.kexe \
  --ktest_filter=StickerDefaultInstallPostgresTest.*
# Require STICKER_DEFAULT_PG_EXECUTED, not just a green runner.
```

Use a different newly migrated root for the socket host:

```sh
source /tmp/sticker-packs.HOST/environment.sh
STICKER_PACK_HTTP=1 STICKER_PACK_PORT=55479 \
  /tmp/sticker-packs.native.RUN/default-pack-tests.kexe \
  --ktest_filter=StickerPackHttpHostTest.* > "$STICKER_PACK_ROOT/host.log" 2>&1
```

Host prints only READY and the handoff FILE PATH, never tokens. Handoff is 0600,
root is 0700, credentials expire in four hours. It contains normalized
`{baseUrl,accounts:[{uid,token}]}` for indices0..31, eight accountPairs, plus
admin/readonly/refresh and separate backend validation accounts9191/9192.
Only loopback ports55479..55489 and guarded disposable DB names are accepted.
Stop gracefully via the host root's `stop` sentinel; normal exit removes handoff.

The shared realm verifier/RBAC/CSRF dispatch is real. Principals are explicit
fixture realms, NOT real account onboarding/login, MFA, persistent roles,
session revocation or assembled-product bootstrap acceptance. No test identity
or signing secret is added to production code.

Run the mutating HTTP client on its OWN fresh host/database, e.g. port55480,
not the clean generator/iOS handoff host:

```sh
STICKER_PACK_HANDOFF=/tmp/sticker-packs.HTTP/handoff-PRIVATE \
STICKER_PACK_PNG=/absolute/path/to/original/1f600.png \
node /Users/zoujiaqing/projects/privchat/neton-application-module-sticker/tests/http/default-pack-lifecycle.mjs
```

The client checks actual HTTP responses; it never mocks fetch/DB/storage. Its
post-assertion marker is STICKER_DEFAULT_HTTP_EXECUTED. Default optional test
returns without opt-in are NOT integration acceptance. Published21-pack asset
acceptance belongs to the publisher/mobile tasks, not these small lifecycle fixtures.
