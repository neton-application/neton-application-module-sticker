import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';

// Actual loopback Neton routes + PostgreSQL/storage + official CLI. No fetch/DB mocks.
const origin='http://127.0.0.1:55477';
const log=readFileSync(process.env.PAG_HTTP_LOG,'utf8');
const credentials=JSON.parse(readFileSync(log.match(/PAG_HTTP_CREDENTIALS=([^\s]+)/)?.[1],'utf8'));
const {ADMIN,ACCOUNT,READONLY,REFRESH}=credentials;
const fixtures=process.env.PAG_FIXTURE_ROOT;
assert(fixtures,'PAG_FIXTURE_ROOT required');
const stickerPag=readFileSync(`${fixtures}/guide_hand_drag.pag`), logo=readFileSync(`${fixtures}/PAG_LOGO.pag`);
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64');
let checks=0;
async function call(path,{method='GET',body,auth=ADMIN,status=200,cookie=false}={}) {
  const headers={};
  if(auth) headers[cookie?'Cookie':'Authorization']=cookie?`neton_session=${auth}`:`Bearer ${auth}`;
  if(body && !(body instanceof FormData)) { headers['Content-Type']='application/json'; body=JSON.stringify(body); }
  const response=await fetch(origin+path,{method,body,headers});
  const bytes=Buffer.from(await response.arrayBuffer());
  assert.equal(response.status,status,`${method} ${path}: ${bytes.toString().slice(0,300)}`); checks++;
  if(response.headers.get('content-type')?.includes('json')) {
    const envelope=JSON.parse(bytes.toString());
    assert.equal(typeof envelope.code,'number');
    if(status===200) assert.equal(envelope.code,0,JSON.stringify(envelope));
    else assert.notEqual(envelope.code,0);
    return envelope.data;
  }
  return bytes;
}
function le(n,size=4) { const b=Buffer.alloc(size); b.writeUIntLE(n,0,size); return b; }
function crc(bytes) { let c=-1; for(const b of bytes) { c^=b; for(let k=0;k<8;k++) c=(c>>>1)^((c&1)?0xedb88320:0); } return (c^-1)>>>0; }
function zip(files) {
  let body=Buffer.alloc(0),directory=Buffer.alloc(0);
  for(const [name,data] of Object.entries(files)) {
    const file=Buffer.from(name),sum=crc(data),size=data.length;
    directory=Buffer.concat([directory,le(0x02014b50),le(20,2),le(20,2),le(0,2),le(0,2),le(0),le(sum),le(size),le(size),le(file.length,2),le(0,2),le(0,2),le(0,2),le(0,2),le(0),le(body.length),file]);
    body=Buffer.concat([body,le(0x04034b50),le(20,2),le(0,2),le(0,2),le(0),le(sum),le(size),le(size),le(file.length,2),le(0,2),file,data]);
  }
  const n=Object.keys(files).length;
  return Buffer.concat([body,directory,le(0x06054b50),le(0,2),le(0,2),le(n,2),le(n,2),le(directory.length),le(body.length),le(0,2)]);
}
const label=`pag-http-${Date.now()}`;
for(const auth of [null,ADMIN,REFRESH]) await call('/app/chat-effect/catalog',{auth,status:401});
for(const auth of [null,ACCOUNT,REFRESH,READONLY]) {
  const status=auth===READONLY?403:401;
  for(const path of ['/admin/sticker/pack/import-v2','/admin/chat-effect/effect/import','/admin/chat-effect/catalog/publish','/admin/chat-effect/rule/create']) {
    await call(path,{method:'POST',body:{},auth,status});
  }
}
await call('/admin/chat-effect/effect/create',{method:'POST',body:{effectId:label,version:1,name:'Test',rights:'Test'},cookie:true,status:401});
const author=await call('/admin/sticker/author/create',{method:'POST',body:{name:label}});
function packForm(pag=stickerPag,version=1) {
  const manifest={schema_version:2,pack_id:label,name:{en:label},author:label,version,cover:'fallback.png',stickers:[{key:'original',file:'fallback.png',width:1,height:1,pag:{file:'motion.pag',profile:'pag-sticker-v1'}}]};
  const form=new FormData(); form.set('authorId',author.id);
  form.set('file',new Blob([zip({'manifest.json':Buffer.from(JSON.stringify(manifest)),'fallback.png':png,'motion.pag':pag})]),'pack.zip');
  return form;
}
await call('/admin/sticker/pack/import',{method:'POST',body:packForm(),status:400});
await call('/admin/sticker/pack/import-v2',{method:'POST',body:packForm(logo),status:400});
const draft=await call('/admin/sticker/pack/import-v2',{method:'POST',body:packForm()});
const fallback=draft.stickers[0],pag=fallback.pag;
assert(pag && pag.profile==='pag-sticker-v1'); checks++;
assert.notEqual(pag.resourceId,fallback.stickerId);
await call(`/app/sticker/resource/get/${pag.resourceId}`,{auth:null,status:404});
await call(`/app/sticker/resolve/${fallback.stickerId}`,{auth:ACCOUNT,status:404});
assert.deepEqual(await call(`/admin/sticker/resource/get/${pag.resourceId}`),stickerPag);
const retry=await call('/admin/sticker/pack/import-v2',{method:'POST',body:packForm()}); assert.equal(retry.id,draft.id);
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:draft.id}});
const resolved=await call(`/app/sticker/resolve/${fallback.stickerId}`,{auth:ACCOUNT});
assert.equal(resolved.pag.resourceId,pag.resourceId);
assert.equal(resolved.pag.sha256,createHash('sha256').update(stickerPag).digest('hex'));
assert.deepEqual(await call(`/app/sticker/resource/get/${pag.resourceId}`,{auth:null}),stickerPag);
assert.deepEqual(await call(`/app/sticker/resource/get/${fallback.stickerId}`,{auth:null}),png);
await call(`/app/sticker/resolve/${pag.resourceId}`,{auth:ACCOUNT,status:404});
await call('/admin/sticker/pack/offline',{method:'POST',body:{id:draft.id}});
assert.deepEqual(await call(`/app/sticker/resource/get/${pag.resourceId}`,{auth:null}),stickerPag);
assert.deepEqual(await call(`/app/sticker/resource/get/${fallback.stickerId}`,{auth:null}),png);
const effect=await call('/admin/chat-effect/effect/create',{method:'POST',body:{effectId:label,version:1,name:'Official logo',rights:'Official upstream test asset, fixture only'}});
function effectForm(bytes) { const form=new FormData(); form.set('id',effect.id); form.set('file',new Blob([bytes]),'effect.pag'); return form; }
await call('/admin/chat-effect/effect/import',{method:'POST',body:effectForm(Buffer.from('PAG\0')),status:400});
const imported=await call('/admin/chat-effect/effect/import',{method:'POST',body:effectForm(logo)});
const er=imported.resource; assert.equal(er.profile,'pag-effect-v1'); assert.equal(er.width,800);
await call(`/app/chat-effect/resource/get/${er.resourceId}`,{auth:null,status:404});
assert.deepEqual(await call(`/admin/chat-effect/resource/get/${er.resourceId}`),logo);
const rule=await call('/admin/chat-effect/rule/create',{method:'POST',body:{effectVersionId:effect.id,keywords:['hello','想你了']}});
await call('/admin/chat-effect/effect/publish',{method:'POST',body:{id:effect.id}});
const expiry=Date.now()+120000;
const first=await call('/admin/chat-effect/catalog/publish',{method:'POST',body:{expiresAtMs:expiry}});
const catalog=await call('/app/chat-effect/catalog',{auth:ACCOUNT});
assert.equal(catalog.schemaVersion,1); assert.equal(catalog.revision,first.revision);
const published=catalog.rules.find(r=>r.ruleId===rule.id);
assert(published); assert.equal(published.ruleVersion,1); assert.equal(published.expiresAtMs,expiry);
assert.equal(published.resource.sha256,createHash('sha256').update(logo).digest('hex'));
assert.equal(published.resource.size,logo.length);
assert.deepEqual(await call(`/app/chat-effect/resource/get/${er.resourceId}`,{auth:null}),logo);
const updated=await call('/admin/chat-effect/rule/update',{method:'PUT',body:{...rule.configuration,id:rule.id,priority:99}});
assert.equal(updated.ruleVersion,2);
assert.equal((await call('/app/chat-effect/catalog',{auth:ACCOUNT})).rules.find(r=>r.ruleId===rule.id).ruleVersion,1);
const second=await call('/admin/chat-effect/catalog/publish',{method:'POST',body:{expiresAtMs:expiry}});
assert(second.revision>first.revision); assert.equal(second.rules.find(r=>r.ruleId===rule.id).ruleVersion,2);
await call('/admin/chat-effect/effect/offline',{method:'POST',body:{id:effect.id}});
const revoked=await call('/app/chat-effect/catalog',{auth:ACCOUNT});
assert(revoked.revision>second.revision); assert(!revoked.rules.some(r=>r.ruleId===rule.id));
assert.deepEqual(await call(`/app/chat-effect/resource/get/${er.resourceId}`,{auth:null}),logo);
await call('/admin/chat-effect/effect/publish',{method:'POST',body:{id:effect.id},status:400});
console.log(`PAG_HTTP_EXECUTED: ${checks} HTTP checks passed; actual routes/realm/RBAC/CSRF/PG/storage + official libpag, not system login/MFA acceptance`);
