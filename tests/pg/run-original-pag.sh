#!/bin/bash
set -euo pipefail
base=$(cd "$(dirname "$0")/../../.." && pwd)
source="$base/../privchat/privchat-app/privchat/src/commonMain/resources/pag"
exe=${1:?Usage: run-original-pag.sh /absolute/path/production-tests.kexe}
psql=/opt/homebrew/opt/postgresql@16/bin/psql
root=$(mktemp -d /tmp/pag-postgres.original.XXXXXX)
database="pag_backend_test_original_$(date +%Y%m%d%H%M%S)"
export PAG_TEST_DATABASE_URI="postgresql://sticker_backend_test:testonly@127.0.0.1:55476/$database"
export PAG_TEST_STORAGE_ROOT="$root"
export PAG_TEST_INSPECTOR="$base/../privchat/privchat-app/privchat/iosApp/build/pag-sdk/pag-inspect"
export PAG_TEST_STICKER_ZIP="$root/original-sticker-v2.zip"
export PAG_TEST_EFFECT_FILE="$source/weey-kiss-effect.pag"
export PAG_TEST_LEGACY_PNG="$root/legacy513.png"
export PAG_ORIGINAL_SOURCE="$source"
printf '%s\n' "$root" > /tmp/pag-original-latest-root
# Only create a new private test database; never reset existing fixture data.
"$psql" 'postgresql://sticker_backend_test:testonly@127.0.0.1:55476/postgres' -X -v ON_ERROR_STOP=1 -c "CREATE DATABASE $database" > "$root/setup.log"
for sql in "$base/neton-application-module-sticker/sql/postgresql/V001__sticker.sql" "$base/neton-application-module-sticker/sql/postgresql/V003__pag_representations.sql" "$base/neton-application-module-sticker/sql/postgresql/V004__default_pack_installs.sql" "$base/neton-application-module-sticker/sql/postgresql/V005__immutable_archive_delivery.sql" "$base/neton-application-module-effect/sql/postgresql/V001__effect.sql" "$base/neton-application-module-effect/sql/postgresql/V002__rule_versions.sql"; do
  "$psql" "$PAG_TEST_DATABASE_URI" -X -v ON_ERROR_STOP=1 -f "$sql" >> "$root/setup.log"
done
node --input-type=module <<'JS'
import {readFileSync,writeFileSync,copyFileSync,mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const source=process.env.PAG_ORIGINAL_SOURCE,root=process.env.PAG_TEST_STORAGE_ROOT;
const manifest=JSON.parse(readFileSync(`${source}/manifest.json`));
assert.match(readFileSync(`${source}/LICENSE.txt`,'utf8'),/MIT License/);
const assets=manifest.assets.filter(a=>a.id==='weey-kiss-sticker'||a.id==='weey-kiss-effect');
assert.equal(assets.length,2);
for(const a of assets) {
 assert.equal(a.license,'MIT');
 for(const [file,hash] of [[a.file,a.sha256],[a.fallback,a.fallback_sha256]])
  assert.equal(createHash('sha256').update(readFileSync(`${source}/${file}`)).digest('hex'),hash);
}
const a=assets.find(a=>a.kind==='sticker');
const dir=`${root}/zip-input`;mkdirSync(dir);
for(const file of [a.file,a.fallback]) copyFileSync(`${source}/${file}`,`${dir}/${file}`);
writeFileSync(`${dir}/manifest.json`,JSON.stringify({schema_version:2,pack_id:'weey-original-pg',name:{en:'Weey Original MIT'},author:'PAG acceptance',version:1,cover:a.fallback,stickers:[{key:a.id,file:a.fallback,width:a.width,height:a.height,pag:{file:a.file,profile:'pag-sticker-v1'}}]}));
execFileSync('/usr/bin/zip',['-q','-j',process.env.PAG_TEST_STICKER_ZIP,`${dir}/manifest.json`,`${dir}/${a.file}`,`${dir}/${a.fallback}`]);
writeFileSync(`${root}/original-assets.json`,JSON.stringify(assets,null,2));
// Historical-only 513x1 PNG: new ingress must reject it, old reads must survive.
writeFileSync(process.env.PAG_TEST_LEGACY_PNG,Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAgEAAAABCAYAAABHeX1IAAAAF0lEQVR4nGNgGAWjYBSMglEwCkbBiAQACAUAAVbgEW4AAAAASUVORK5CYII=','base64'));
JS
# Record this exact inspector before executing the real tests.
shasum -a 256 "$exe" "$PAG_TEST_INSPECTOR" "$PAG_TEST_STICKER_ZIP" "$PAG_TEST_EFFECT_FILE" > "$root/executed-artifacts.sha256"
"$exe" --ktest_filter='StickerPagPostgresTest.*:EffectPostgresTest.*' > "$root/acceptance.log" 2>&1
# A green native runner alone cannot prove that an opt-in body ran.
grep -q STICKER_PAG_POSTGRES_EXECUTED "$root/acceptance.log"
grep -q EFFECT_POSTGRES_EXECUTED "$root/acceptance.log"
printf 'ORIGINAL_PAG_REAL_PG_ACCEPTED=%s\nDATABASE=%s\n' "$root" "$database"
cat "$root/acceptance.log"
