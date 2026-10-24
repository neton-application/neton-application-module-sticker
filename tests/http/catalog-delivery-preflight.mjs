import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';

const [handoffPath, accountsPath, output] = process.argv.slice(2);
assert(handoffPath && accountsPath && output, 'Expected private handoff, real IM accounts, output directory');
const handoff = JSON.parse(await readFile(handoffPath, 'utf8'));
const identities = JSON.parse(await readFile(accountsPath, 'utf8'));
const base = handoff.baseUrl;
assert.match(base, /^http:\/\/127\.0\.0\.1:\d+\/app$/);
await mkdir(output, { recursive: true, mode: 0o700 });
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
async function get(path, token, limit = 8 * 1024 * 1024) {
  assert(path.startsWith('/sticker/'));
  const response = await fetch(base + path, {
    headers: { Authorization: `Bearer ${token}` }, redirect: 'error', signal: AbortSignal.timeout(30000),
  });
  assert.equal(response.status, 200, `Unexpected status for ${path}`);
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    assert(size <= limit, 'Response exceeds acceptance bound');
    chunks.push(chunk);
  }
  return { response, bytes: Buffer.concat(chunks) };
}
async function json(path, token) {
  const { bytes } = await get(path, token);
  const body = JSON.parse(bytes);
  assert.equal(body.code, 0);
  return body.data;
}
const report = { boundary: 'Actual catalog routes, PG/storage and same IM UIDs; not native rendering', roles: {} };
let exported = false;
for (const role of ['owner', 'admin', 'member']) {
  const uid = String(identities[role].user_id);
  const token = handoff.accounts.find(row => String(row.uid) === uid)?.token;
  assert(token, `Catalog identity does not match real IM ${role}`);
  const store = await json('/sticker/store', token);
  assert.equal(store.length, 21);
  assert.equal(store.reduce((n, pack) => n + pack.stickers.length, 0), 3953);
  const installed = await json('/sticker/installed', token);
  assert.equal(installed.length, 1, 'Only the smileys pack is installed by default');
  const pack = installed[0];
  assert.equal(pack.packId, 'apple-smileys-emotion');
  assert.equal(pack.stickers.length, 171);
  assert.equal(pack.versionRowId, pack.id);
  report.roles[role] = { packs: store.length, resources: 3953, installed: [pack.packId], stickers: 171 };
  if (exported) continue;
  for (const row of store) {
    const url = new URL(row.coverImageUrl);
    assert.equal(url.hostname, '127.0.0.1');
    const cover = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(10000) });
    assert.equal(cover.status, 200);
    const bytes = Buffer.from(await cover.arrayBuffer());
    assert(bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])));
  }
  const descriptor = await json(`/sticker/archive/${pack.versionRowId}/manifest`, token);
  assert.equal(descriptor.packId, pack.packId);
  assert.equal(descriptor.versionRowId, pack.versionRowId);
  assert.equal(descriptor.downloadPath, `/sticker/archive/${pack.versionRowId}/download`);
  assert(descriptor.size > 0 && descriptor.size < 32 * 1024 * 1024);
  const { bytes, response } = await get(descriptor.downloadPath, token, descriptor.size);
  assert.equal(bytes.length, descriptor.size);
  assert.equal(sha(bytes), descriptor.sha256);
  assert.equal(response.headers.get('etag'), `"${descriptor.sha256}"`);
  assert.equal(Number(response.headers.get('content-length')), bytes.length);
  await writeFile(`${output}/descriptor.json`, JSON.stringify(descriptor, null, 2), { mode: 0o600 });
  await writeFile(`${output}/delivery.zip`, bytes, { mode: 0o600 });
  report.defaultArchive = { versionRowId: pack.versionRowId, size: bytes.length, sha256: descriptor.sha256, files: descriptor.files.length };
  report.pngCovers = store.length;
  exported = true;
}
await writeFile(`${output}/report.json`, JSON.stringify(report, null, 2), { mode: 0o600 });
console.log(JSON.stringify(report));
