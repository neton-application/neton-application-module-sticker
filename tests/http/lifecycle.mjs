import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { deflateRawSync } from 'node:zlib';
import { execFileSync } from 'node:child_process';

const origin='http://127.0.0.1:55474';
const log=readFileSync(process.env.STICKER_HTTP_LOG || '/tmp/sticker-http-host.log','utf8');
const credentialPath=process.env.STICKER_HTTP_CREDENTIALS || log.match(/STICKER_HTTP_CREDENTIALS=([^\s]+)/)?.[1];
assert(credentialPath,'Missing private credential path');
const credentials=JSON.parse(readFileSync(credentialPath,'utf8'));
const token=name=>credentials[name] || assert.fail(`Missing ${name} fixture token`);
const admin=token('ADMIN'), account=token('ACCOUNT'), readonly=token('READONLY'), refresh=token('REFRESH');
const resourceCount=()=>Number(execFileSync('/opt/homebrew/opt/postgresql@16/bin/psql',['postgresql://sticker_test:test@127.0.0.1:55473/sticker_test','-Atc','SELECT COUNT(*) FROM sticker_resources'],{encoding:'utf8'}).trim());
let checks=0;
async function call(path,{method='GET',body,auth=admin,status=200,cookie=false,csrf=true}={}) {
  const headers={};
  if(auth) headers[cookie?'Cookie':'Authorization']=cookie?`neton_session=${auth}`:`Bearer ${auth}`;
  if(cookie && csrf) headers['X-Neton-Csrf']='1';
  if(body && !(body instanceof FormData)) { headers['Content-Type']='application/json'; body=JSON.stringify(body); }
  const r=await fetch(origin+path,{method,body,headers});
  const bytes=Buffer.from(await r.arrayBuffer());
  assert.equal(r.status,status,`${method} ${path}: ${bytes.toString().slice(0,300)}`); checks++;
  if(r.headers.get('content-type')?.includes('json')) {
    const env=JSON.parse(bytes.toString());
    assert.equal(typeof env.code,'number');
    if(status===200) assert.equal(env.code,0,JSON.stringify(env));
    else assert.notEqual(env.code,0);
    return env.data;
  }
  return bytes;
}
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64');
const label=`http-${Date.now()}`;
const author=await call('/admin/sticker/author/create',{method:'POST',body:{name:label,bio:'HTTP fixture'}});
await call('/admin/sticker/author/update',{method:'PUT',body:{id:author.id,name:label,bio:'updated',status:1}});
await call('/admin/sticker/author/page?pageNo=1&pageSize=20');
await call('/admin/sticker/author/page?pageNo=0',{status:400});
for(const auth of [null,account,readonly,refresh]) {
  const status=auth===readonly?403:401;
  for(const path of ['pack/import','pack/make','pack/publish','author/create']) await call(`/admin/sticker/${path}`,{method:'POST',body:{},auth,status});
}
await call('/admin/sticker/author/create',{method:'POST',body:{name:label},cookie:true,csrf:false,status:401});
await call('/admin/system/auth/get-permission-info',{cookie:true});
const author2=await call('/admin/sticker/author/create',{method:'POST',body:{name:label+'-delete'},cookie:true});
await call(`/admin/sticker/author/delete/${author2.id}`,{method:'DELETE'});
function makeForm(version,key='smile',bytes=png) {
  const form=new FormData(); form.set('file',new Blob([bytes],{type:'image/png'}),'image.png');
  for(const [k,v] of Object.entries({authorId:author.id,packId:label,name:JSON.stringify({en:label}),version:String(version),key})) form.set(k,v);
  return form;
}
const p=await call('/admin/sticker/pack/make',{method:'POST',body:makeForm(1),cookie:true});
assert.equal(p.publicationState,1); assert.equal(typeof p.id,'string');
const id=p.stickers[0].stickerId;
await call(`/app/sticker/resource/get/${id}`,{auth:null,status:404});
await call(`/app/sticker/resolve/${id}`,{auth:account,status:404});
await call(`/admin/sticker/resource/get/${id}`,{auth:account,status:401});
await call('/app/sticker/store',{auth:admin,status:401});
await call('/app/sticker/installed',{auth:admin,status:401});
assert.deepEqual(await call(`/admin/sticker/resource/get/${id}`,{cookie:true}),png);
await call('/app/sticker/install',{auth:account,method:'POST',body:{packId:label},status:400});
const beforeRetry=resourceCount();
for(let i=0;i<3;i++) { const retry=await call('/admin/sticker/pack/make',{method:'POST',body:makeForm(1)}); assert.equal(retry.id,p.id); }
assert.equal(resourceCount(),beforeRetry,'Idempotent upload must not reserve orphan rows');
await call('/admin/sticker/pack/make',{method:'POST',body:makeForm(1,'other'),status:400});
await call('/admin/sticker/pack/make',{method:'POST',body:makeForm(2,'smile',Buffer.from('not an image')),status:400});
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:p.id}});
assert.deepEqual(await call(`/app/sticker/resource/get/${id}`,{auth:null}),png);
const resolved=await call(`/app/sticker/resolve/${id}`,{auth:account}); assert.equal(resolved.imageUrl,origin+`/app/sticker/resource/get/${id}`);
await call('/app/sticker/store',{auth:account});
await call('/app/sticker/install',{auth:account,method:'POST',body:{packId:label}});
await call('/app/sticker/install',{auth:account,method:'POST',body:{packId:label}});
await call('/app/sticker/reorder',{auth:account,method:'POST',body:{packIds:[label]}});
await call('/app/sticker/reorder',{auth:account,method:'POST',body:{packIds:[label,label]},status:400});
await call(`/admin/sticker/pack/delete/${p.id}`,{method:'DELETE',status:400});
await call('/admin/sticker/pack/offline',{method:'POST',body:{id:p.id}});
await call('/app/sticker/install',{auth:account,method:'POST',body:{packId:label},status:400});
assert((await call('/app/sticker/installed',{auth:account})).some(x=>x.id===p.id));
assert.deepEqual(await call(`/app/sticker/resource/get/${id}`,{auth:null}),png);
await call('/app/sticker/uninstall',{auth:account,method:'POST',body:{packId:label}});
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:p.id},status:400});
// ZIP uses real deflate and CRC, including cover dedup in the manifest.
function crc(buf){let c=0xffffffff;for(const b of buf){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0)}return (c^0xffffffff)>>>0}
function zip(files){let offset=0;const locals=[],centrals=[];for(const [name,bytes] of files){const n=Buffer.from(name),b=deflateRawSync(bytes),l=Buffer.alloc(30),c=Buffer.alloc(46);l.writeUInt32LE(0x04034b50);l.writeUInt16LE(20,4);l.writeUInt16LE(8,8);l.writeUInt32LE(crc(bytes),14);l.writeUInt32LE(b.length,18);l.writeUInt32LE(bytes.length,22);l.writeUInt16LE(n.length,26);c.writeUInt32LE(0x02014b50);c.writeUInt16LE(20,4);c.writeUInt16LE(20,6);c.writeUInt16LE(8,10);c.writeUInt32LE(crc(bytes),16);c.writeUInt32LE(b.length,20);c.writeUInt32LE(bytes.length,24);c.writeUInt16LE(n.length,28);c.writeUInt32LE(offset,42);locals.push(l,n,b);centrals.push(c,n);offset+=l.length+n.length+b.length}const central=Buffer.concat(centrals),end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(files.length,8);end.writeUInt16LE(files.length,10);end.writeUInt32LE(central.length,12);end.writeUInt32LE(offset,16);return Buffer.concat([...locals,central,end])}
const manifest={pack_id:label,name:{en:label},author:label,version:2,cover:'image.png',stickers:[{key:'smile',file:'image.png',width:1,height:1}]};
const zipped=zip([['manifest.json',Buffer.from(JSON.stringify(manifest))],['image.png',png]]);
function zipForm(){const f=new FormData();f.set('file',new Blob([zipped]),'pack.zip');f.set('authorId',author.id);return f}
const imported=await call('/admin/sticker/pack/import',{method:'POST',body:zipForm()});
assert.notEqual(imported.stickers[0].stickerId,id);
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:imported.id}});
await call('/app/sticker/install',{auth:account,method:'POST',body:{packId:label}});
assert.equal((await call('/app/sticker/installed',{auth:account})).find(x=>x.packId===label).version,2);
await call('/admin/sticker/pack/offline',{method:'POST',body:{id:imported.id}});
assert.equal((await call('/app/sticker/installed',{auth:account})).find(x=>x.packId===label).version,2);
await call('/app/sticker/uninstall',{auth:account,method:'POST',body:{packId:label}});
assert.deepEqual(await call(`/app/sticker/resource/get/${id}`,{auth:null}),png);
await call(`/admin/sticker/author/delete/${author.id}`,{method:'DELETE',status:400});
// 17 MiB must reach the module validator (400), rather than the old transport limit (413).
const large=new FormData(); large.set('file',new Blob([Buffer.alloc(17*1024*1024)]),'oversized.zip'); large.set('authorId',author.id);
await call('/admin/sticker/pack/import',{method:'POST',body:large,status:400});
const overTransport=new FormData(); overTransport.set('file',new Blob([Buffer.alloc(35*1024*1024)]),'too-large.zip'); overTransport.set('authorId',author.id);
await call('/admin/sticker/pack/import',{method:'POST',body:overTransport,status:413});
console.log(`STICKER_HTTP_EXECUTED: ${checks} real HTTP checks passed; PostgreSQL and local StorageOperator; fixture JWT identities (not system login/MFA)`);
