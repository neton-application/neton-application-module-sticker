import fs from 'node:fs';
import assert from 'node:assert/strict';

// Explicit disposable host handoff; credentials are read from disk and never logged.
const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
assert.match(config.ORIGIN, /^http:\/\/127\.0\.0\.1:5548[0-9]$|^http:\/\/127\.0\.0\.1:55479$/);
assert.match(config.database, /^sticker_packs_/);
const account = config.backendAcceptanceAccount;
const other = config.backendAcceptanceAccountB;
let checks = 0;
async function call(action, token = account, body, expected = 200) {
  const response = await fetch(`${config.ORIGIN}/app/sticker/${action}`, {
    method: body ? 'POST' : 'GET',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, expected, action);
  checks++;
  if (expected !== 200) return;
  const envelope = await response.json();
  assert.equal(envelope.code, 0, action);
  if (/^(favorites|search|favorite\/)/.test(action)) assert.equal(response.headers.get('cache-control'), 'private, no-store');
  return envelope.data;
}

for (const token of [null, config.ADMIN, config.REFRESH]) {
  await call('favorites', token, undefined, 401);
  await call('search?query=smile', token, undefined, 401);
  await call('favorite/add', token, { stickerId: '1' }, 401);
  await call('favorite/remove', token, { stickerId: '1' }, 401);
}
const store = await call('store');
const pack = store.find(p => p.packId === 'apple-smileys-emotion');
assert.ok(pack?.stickers.length);
const stickerId = pack.stickers[0].stickerId;
await call('favorite/remove', account, { stickerId });
await call('favorite/remove', other, { stickerId });
await Promise.all(Array.from({ length: 4 }, () => call('favorite/add', account, { stickerId })));
assert.equal((await call('favorites')).filter(s => s.stickerId === stickerId).length, 1);
assert.ok(!(await call('favorites', other)).some(s => s.stickerId === stickerId));
await call('favorite/remove', other, { stickerId });
assert.ok((await call('favorites', config.backendAcceptanceAccountRelogin)).some(s => s.stickerId === stickerId));
const result = await call('search?query=apple');
assert.ok(result.packs.length > 0 && result.packs.length <= 20);
assert.equal(result.packs[0].packId, 'apple-smileys-emotion');
assert.ok(result.stickers.length > 0 && result.stickers.length <= 100);
assert.equal(new Set(result.stickers.map(s => s.stickerId)).size, result.stickers.length);
for (const query of ['%', "' OR 1=1 --"]) {
  const result = await call(`search?query=${encodeURIComponent(query)}`);
  assert.deepEqual(result, { packs: [], stickers: [] });
}
assert.deepEqual(await call('search?query=%20%20'), { packs: [], stickers: [] });
await call(`search?query=${'a'.repeat(129)}`, account, undefined, 400);
await call('search?query=bad%00query', account, undefined, 400);
await call('favorite/add', account, { stickerId: '9223372036854775807' }, 404);
await call('favorite/add', account, { stickerId: 'invalid' }, 400);
await call('favorite/remove', account, { stickerId });
await call('favorite/remove', account, { stickerId });
assert.ok(!(await call('favorites')).some(s => s.stickerId === stickerId));
console.log(`PASS ${checks} real HTTP favorites/search checks (auth matrix, ownership, concurrency, relogin, limits, literal SQL, idempotency)`);
