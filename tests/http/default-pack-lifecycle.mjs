import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const config=JSON.parse(readFileSync(process.env.STICKER_PACK_HANDOFF,'utf8'));
const origin=config.ORIGIN;
const A=config.backendAcceptanceAccount,B=config.backendAcceptanceAccountB;
const png=readFileSync(process.env.STICKER_PACK_PNG);
const width=png.readUInt32BE(16),height=png.readUInt32BE(20);
let checks=0;
async function call(path,{method='GET',body,auth=config.ADMIN,status=200}={}) {
 const headers={};if(auth)headers.Authorization=`Bearer ${auth}`;
 if(body && !(body instanceof FormData)){headers['Content-Type']='application/json';body=JSON.stringify(body);}
 const response=await fetch(origin+path,{method,body,headers});
 const bytes=Buffer.from(await response.arrayBuffer());
 assert.equal(response.status,status,`${method} ${path}: ${bytes.toString().slice(0,250)}`);checks++;
 if(response.headers.get('content-type')?.includes('json')){
  const value=JSON.parse(bytes.toString());assert.equal(value.code===0,status===200);return value.data;
 }
 return bytes;
}
function le(n,s=4){const b=Buffer.alloc(s);b.writeUIntLE(n,0,s);return b;}
function crc(bytes){let c=-1;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^-1)>>>0;}
function zip(files){let body=Buffer.alloc(0),dir=Buffer.alloc(0);for(const[name,data]of Object.entries(files)){
 const f=Buffer.from(name),sum=crc(data),n=data.length;
 dir=Buffer.concat([dir,le(0x02014b50),le(20,2),le(20,2),le(0,2),le(0,2),le(0),le(sum),le(n),le(n),le(f.length,2),le(0,2),le(0,2),le(0,2),le(0,2),le(0),le(body.length),f]);
 body=Buffer.concat([body,le(0x04034b50),le(20,2),le(0,2),le(0,2),le(0),le(sum),le(n),le(n),le(f.length,2),le(0,2),f,data]);
 }const n=Object.keys(files).length;return Buffer.concat([body,dir,le(0x06054b50),le(0,2),le(0,2),le(n,2),le(n,2),le(dir.length),le(body.length),le(0,2)]);}
for(const auth of [null,config.ADMIN,config.REFRESH])await call('/app/sticker/installed',{auth,status:401});
await call('/admin/sticker/pack/create',{method:'POST',body:{},auth:A,status:401});
await call('/admin/sticker/pack/create',{method:'POST',body:{},auth:config.READONLY,status:403});
const label=`default-http-${Date.now()}`;
const author=await call('/admin/sticker/author/create',{method:'POST',body:{name:label}});
function uploadManifest(key,flag,version=1){
 const m={pack_id:key,name:{en:key},author:label,version,cover:'image.png',stickers:[{key:'first',file:'image.png',width,height}]};
 if(flag!==undefined)m.default_install=flag;
 const form=new FormData();form.set('authorId',author.id);form.set('file',new Blob([zip({'manifest.json':Buffer.from(JSON.stringify(m)),'image.png':png})]),'pack.zip');return form;
}
await call('/admin/sticker/pack/import',{method:'POST',body:uploadManifest(`${label}-invalid`,2),status:400});
await call('/admin/sticker/pack/import',{method:'POST',body:uploadManifest(`${label}-invalid`,true),status:400});
const smileKey=`${label}-smileys`;
const smile=await call('/admin/sticker/pack/import',{method:'POST',body:uploadManifest(smileKey,1)});
assert.equal(smile.defaultInstall,1);assert.equal(smile.coverImageUrl,`/admin/sticker/resource/get/${smile.coverId}`);
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:smile.id}});
async function make(key,flag){
 const form=new FormData();for(const[k,v]of Object.entries({packId:key,name:JSON.stringify({en:key}),authorId:author.id,version:'1',key:'first',defaultInstall:String(flag)}))form.set(k,v);
 form.set('file',new Blob([png]),'image.png');return call('/admin/sticker/pack/make',{method:'POST',body:form,status:flag===9?400:200});
}
await make(`${label}-badmake`,9);
const animals=await make(`${label}-animals`,0),objects=await make(`${label}-objects`,0);
for(const p of [animals,objects])await call('/admin/sticker/pack/publish',{method:'POST',body:{id:p.id}});
const store=await call('/app/sticker/store',{auth:A});assert.equal(store.length,3);
assert.equal(store.filter(p=>p.defaultInstall===1).length,1);
assert(store.every(p=>p.coverImageUrl===`${origin}/app/sticker/resource/get/${p.coverId}`));
for(const p of store)assert.deepEqual(await call(new URL(p.coverImageUrl).pathname,{auth:null}),png);
const installed=auth=>call('/app/sticker/installed',{auth});
assert.deepEqual((await installed(A)).map(p=>p.packId),[smileKey]);
assert.deepEqual((await installed(B)).map(p=>p.packId),[smileKey]);
await call('/app/sticker/uninstall',{method:'POST',auth:A,body:{packId:smileKey}});
for(const auth of [A,config.backendAcceptanceAccountRelogin])assert.deepEqual(await installed(auth),[]);
assert.equal((await installed(B)).length,1);
for(const p of [animals,objects])await call('/app/sticker/install',{method:'POST',auth:A,body:{packId:p.packId}});
await call('/app/sticker/reorder',{method:'POST',auth:A,body:{packIds:[objects.packId,animals.packId]}});
await call('/app/sticker/install',{method:'POST',auth:A,body:{packId:objects.packId}});
assert.deepEqual((await installed(A)).map(p=>p.packId),[objects.packId,animals.packId]);
for(const packIds of [[objects.packId],[objects.packId,objects.packId],[objects.packId,animals.packId,smileKey]])await call('/app/sticker/reorder',{method:'POST',auth:A,body:{packIds},status:400});
const newer=await call('/admin/sticker/pack/import',{method:'POST',body:uploadManifest(smileKey,1,2)});
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:newer.id}});
assert.deepEqual((await installed(A)).map(p=>p.packId),[objects.packId,animals.packId]);
assert.equal((await installed(B))[0].version,2);
await call('/app/sticker/install',{method:'POST',auth:A,body:{packId:smileKey}});
assert.deepEqual((await installed(A)).map(p=>p.packId),[objects.packId,animals.packId,smileKey]);
await call('/admin/sticker/pack/offline',{method:'POST',body:{id:animals.id}});
assert((await installed(A)).some(p=>p.packId===animals.packId));
await call('/app/sticker/uninstall',{method:'POST',auth:A,body:{packId:objects.packId}});
for(const p of [smile,animals,objects]){const s=p.stickers[0];assert.equal((await call(`/app/sticker/resolve/${s.stickerId}`,{auth:A})).stickerId,s.stickerId);assert.deepEqual(await call(`/app/sticker/resource/get/${s.stickerId}`,{auth:null}),png);}
console.log(`STICKER_DEFAULT_HTTP_EXECUTED: ${checks} actual socket HTTP responses + default opt-out/relogin/version/reinstall/reorder/covers/realm/RBAC/history assertions passed`);
