import assert from 'node:assert/strict';
import {readFileSync, writeFileSync, unlinkSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';

const root=process.env.STICKER_PACK_ROOT;
assert.match(root,/^\/tmp\/sticker-packs\.[A-Za-z0-9]+$/);
const config=JSON.parse(readFileSync(process.env.STICKER_PACK_HANDOFF,'utf8'));
assert.equal(config.ORIGIN,'http://127.0.0.1:55489');
const A=config.backendAcceptanceAccount,B=config.backendAcceptanceAccountB;
const png=readFileSync(process.env.STICKER_PACK_PNG);
const sha=b=>createHash('sha256').update(b).digest('hex');
const sql=query=>execFileSync('/opt/homebrew/opt/postgresql@16/bin/psql',[process.env.STICKER_PACK_DATABASE_URI,'-XAt','-v','ON_ERROR_STOP=1','-c',query],{encoding:'utf8'}).trim();
const ownedFile=key=>{assert.match(key,/^platform\/(sticker-archives\/[a-f0-9]+\.zip|stickers\/[a-f0-9]+)$/);const path=resolve(root,'storage',key);assert(path.startsWith(root+'/storage/'));return path;};
let checks=0;
async function call(path,{method='GET',body,auth=config.ADMIN,status=200,raw=false,error}={}){
 const headers={};if(auth)headers.Authorization=`Bearer ${auth}`;
 if(body && !(body instanceof FormData)){headers['Content-Type']='application/json';body=JSON.stringify(body);}
 const response=await fetch(config.ORIGIN+path,{method,headers,body});
 const bytes=Buffer.from(await response.arrayBuffer());
 assert.equal(response.status,status,`${method} ${path}: ${bytes.toString().slice(0,200)}`);checks++;
 if(raw)return{bytes,headers:response.headers};
 const data=JSON.parse(bytes.toString());assert.equal(data.code===0,status===200);if(error)assert(bytes.toString().includes(error));return data.data;
}
function crc(bytes){let c=-1;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^-1)>>>0;}
function storedZip(files){
 const le=(n,size=4)=>{const b=Buffer.alloc(size);b.writeUIntLE(n,0,size);return b;};
 let body=Buffer.alloc(0),central=Buffer.alloc(0);
 for(const[name,data]of Object.entries(files)){
  const file=Buffer.from(name),n=data.length,sum=crc(data);
  central=Buffer.concat([central,le(0x02014b50),le(20,2),le(20,2),le(0x800,2),le(0,2),le(0,2),le(0x21,2),le(sum),le(n),le(n),le(file.length,2),le(0,2),le(0,2),le(0,2),le(0,2),le(0),le(body.length),file]);
  body=Buffer.concat([body,le(0x04034b50),le(20,2),le(0x800,2),le(0,2),le(0,2),le(0x21,2),le(sum),le(n),le(n),le(file.length,2),le(0,2),file,data]);
 }
 const count=Object.keys(files).length;
 return Buffer.concat([body,central,le(0x06054b50),le(0,2),le(0,2),le(count,2),le(count,2),le(central.length),le(body.length),le(0,2)]);
}
function verifyZip(bytes,descriptor){
 assert.equal(bytes.length,descriptor.size);assert.equal(sha(bytes),descriptor.sha256);
 const expected=[descriptor.manifest,...descriptor.files], files=new Map(),offsets=[];let p=0;
 for(const file of expected){
  offsets.push(p);assert.equal(bytes.readUInt32LE(p),0x04034b50);
  assert.equal(bytes.readUInt16LE(p+4),20);assert.equal(bytes.readUInt16LE(p+6),0x800);
  assert.equal(bytes.readUInt16LE(p+8),0);assert.equal(bytes.readUInt16LE(p+10),0);assert.equal(bytes.readUInt16LE(p+12),0x21);
  const sum=bytes.readUInt32LE(p+14),size=bytes.readUInt32LE(p+18),n=bytes.readUInt16LE(p+26);
  assert.equal(size,file.size);assert.equal(bytes.readUInt32LE(p+22),size);assert.equal(bytes.readUInt16LE(p+28),0);
  assert.equal(bytes.subarray(p+30,p+30+n).toString(),file.file);p+=30+n;
  const data=bytes.subarray(p,p+size);assert.equal(crc(data),sum);assert.equal(sha(data),file.sha256);files.set(file.file,data);p+=size;
 }
 const central=p;
 for(const [index,file] of expected.entries()){
  assert.equal(bytes.readUInt32LE(p),0x02014b50);assert.equal(bytes.readUInt16LE(p+4),20);assert.equal(bytes.readUInt16LE(p+6),20);
  assert.equal(bytes.readUInt16LE(p+8),0x800);assert.equal(bytes.readUInt16LE(p+10),0);assert.equal(bytes.readUInt16LE(p+12),0);assert.equal(bytes.readUInt16LE(p+14),0x21);
  assert.equal(bytes.readUInt32LE(p+16),crc(files.get(file.file)));assert.equal(bytes.readUInt32LE(p+20),file.size);assert.equal(bytes.readUInt32LE(p+24),file.size);
  const n=bytes.readUInt16LE(p+28);assert.equal(bytes.subarray(p+46,p+46+n).toString(),file.file);
  for(const offset of [30,32,34,36])assert.equal(bytes.readUInt16LE(p+offset),0);
  assert.equal(bytes.readUInt32LE(p+38),0);assert.equal(bytes.readUInt32LE(p+42),offsets[index]);p+=46+n;
 }
 assert.equal(bytes.readUInt32LE(p),0x06054b50);assert.equal(bytes.readUInt32LE(p+4),0);
 assert.equal(bytes.readUInt16LE(p+8),expected.length);assert.equal(bytes.readUInt16LE(p+10),expected.length);
 assert.equal(bytes.readUInt32LE(p+12),p-central);assert.equal(bytes.readUInt32LE(p+16),central);assert.equal(bytes.readUInt16LE(p+20),0);assert.equal(p+22,bytes.length);
 const manifest=JSON.parse(files.get('manifest.json').toString());
 for(const file of descriptor.files)assert.match(file.file,new RegExp(`^r_${file.resourceId}\\.(png|webp|pag)$`));
 assert.deepEqual(descriptor.files.map(f=>BigInt(f.resourceId)),descriptor.files.map(f=>BigInt(f.resourceId)).sort((a,b)=>a<b?-1:a>b?1:0));
 return{manifest,files};
}
const label=`archive-http-${Date.now()}`;
const author=await call('/admin/sticker/author/create',{method:'POST',body:{name:label}});
async function make(key,version=1){
 const body=new FormData();for(const[k,v]of Object.entries({packId:key,name:JSON.stringify({en:key}),authorId:author.id,version:String(version),key:'first',defaultInstall:'0'}))body.set(k,v);
 body.set('file',new Blob([png]),'image.png');return call('/admin/sticker/pack/make',{method:'POST',body});
}
const draft=await make(label);
assert.match(draft.id,/^[1-9][0-9]*$/);assert.equal(draft.versionRowId,draft.id);
const manifestPath=id=>`/app/sticker/archive/${id}/manifest`,downloadPath=id=>`/app/sticker/archive/${id}/download`;
for(const path of [manifestPath(draft.id),downloadPath(draft.id)]){
 for(const auth of [null,config.ADMIN,config.REFRESH])await call(path,{auth,status:401});
 await call(path,{auth:A,status:404});
}
assert.equal(sql('SELECT COUNT(*) FROM sticker_archive_blobs'),'0');
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:draft.id}});
assert.equal(sql('SELECT COUNT(*) FROM sticker_archive_snapshots'),'1');
assert.equal(sql('SELECT COUNT(*) FROM sticker_archive_blobs'),'0');
const descriptorResponse=await call(manifestPath(draft.id),{auth:A,raw:true});
assert.equal(descriptorResponse.headers.get('cache-control'),'private, no-store');
const descriptor=JSON.parse(descriptorResponse.bytes.toString()).data;
assert.equal(descriptor.downloadPath,`/sticker/archive/${draft.id}/download`);assert.equal(descriptor.format,'zip-stored-v1');
const first=await call(downloadPath(draft.id),{auth:A,raw:true});
assert.equal(first.headers.get('cache-control'),'private, no-store');assert.equal(first.headers.get('etag'),`"${descriptor.sha256}"`);
assert.equal(first.headers.get('content-length'),String(descriptor.size));assert.match(first.headers.get('content-type'),/^application\/zip/);
assert.equal(first.headers.get('content-disposition'),`attachment; filename="sticker-${draft.id}.zip"`);
const parsed=verifyZip(first.bytes,descriptor);assert.equal(parsed.manifest.author,label);assert.equal(descriptor.files.length,1);
writeFileSync(root+'/archive-descriptor.json',JSON.stringify(descriptor,null,2));
writeFileSync(root+'/archive-delivery.zip',first.bytes);
assert.deepEqual(parsed.files.get(descriptor.files[0].file),png);assert.equal(descriptor.files[0].resourceId,draft.coverId);
await call('/admin/sticker/author/update',{method:'PUT',body:{id:author.id,name:label+'-renamed'}});
const cacheKey=()=>sql(`SELECT b.object_key FROM sticker_archive_snapshots s JOIN sticker_archive_blobs b ON b.id=s.cache_blob_id WHERE s.pack_version_id=${draft.id}`);
unlinkSync(ownedFile(cacheKey()));
assert.deepEqual((await call(downloadPath(draft.id),{auth:B,raw:true})).bytes,first.bytes);
writeFileSync(ownedFile(cacheKey()),Buffer.alloc(first.bytes.length,9));
assert.deepEqual((await call(downloadPath(draft.id),{auth:A,raw:true})).bytes,first.bytes);
assert.deepEqual(await call(manifestPath(draft.id),{auth:B}),descriptor);
const sourceKey=sql(`SELECT object_key FROM sticker_resources WHERE id=${draft.coverId}`);
unlinkSync(ownedFile(cacheKey()));writeFileSync(ownedFile(sourceKey),Buffer.alloc(png.length,7));
await call(downloadPath(draft.id),{auth:A,status:400});
writeFileSync(ownedFile(sourceKey),png);
assert.deepEqual((await call(downloadPath(draft.id),{auth:A,raw:true})).bytes,first.bytes);
await call('/app/sticker/install',{method:'POST',auth:A,body:{packId:label,versionRowId:draft.id}});
await call('/admin/sticker/pack/offline',{method:'POST',body:{id:draft.id}});
await call(manifestPath(draft.id),{auth:B,status:404});await call(downloadPath(draft.id),{auth:B,status:404});
assert.deepEqual((await call(downloadPath(draft.id),{auth:A,raw:true})).bytes,first.bytes);
await call('/app/sticker/uninstall',{method:'POST',auth:A,body:{packId:label}});
await call(downloadPath(draft.id),{auth:A,status:404});
const next=await make(label,2);await call('/admin/sticker/pack/publish',{method:'POST',body:{id:next.id}});
await call('/app/sticker/install',{method:'POST',auth:A,body:{packId:label,versionRowId:draft.id},status:400,error:'STICKER_VERSION_CHANGED'});
await call('/app/sticker/install',{method:'POST',auth:A,body:{packId:label,versionRowId:next.id}});
const installed=await call('/app/sticker/installed',{auth:A});assert(installed.some(p=>p.versionRowId===next.id));
// Delisted history is still readable for an active install of the stable logical pack.
assert.deepEqual((await call(downloadPath(draft.id),{auth:A,raw:true})).bytes,first.bytes);
// Official inspector is configured in the actual host; this is not a fabricated metadata port.
assert(process.env.STICKER_ARCHIVE_PAG,'Actual official-inspector-compatible sticker PAG fixture required');
const pag=readFileSync(process.env.STICKER_ARCHIVE_PAG);
const pagManifest={schema_version:2,pack_id:label+'-pag',name:{en:'PAG archive'},author:label+'-renamed',version:1,cover:'fallback.png',stickers:[{key:'motion',file:'fallback.png',emoji:'x',width:png.readUInt32BE(16),height:png.readUInt32BE(20),pag:{file:'motion.pag',profile:'pag-sticker-v1'}}]};
const pagForm=new FormData();pagForm.set('authorId',author.id);pagForm.set('file',new Blob([storedZip({'manifest.json':Buffer.from(JSON.stringify(pagManifest)),'fallback.png':png,'motion.pag':pag})]),'pag.zip');
const pagPack=await call('/admin/sticker/pack/import-v2',{method:'POST',body:pagForm});
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:pagPack.id}});
const pagDescriptor=await call(manifestPath(pagPack.id),{auth:B});
const pagDownload=await call(downloadPath(pagPack.id),{auth:B,raw:true});
const pagParsed=verifyZip(pagDownload.bytes,pagDescriptor);
assert.equal(pagDescriptor.manifestSchemaVersion,2);assert.equal(pagDescriptor.files.length,2);
assert.equal(pagParsed.manifest.stickers[0].key,'motion');assert.equal(pagParsed.manifest.stickers[0].emoji,'x');
assert.equal(pagParsed.manifest.stickers[0].pag.profile,'pag-sticker-v1');
const pagFile=pagDescriptor.files.find(f=>f.kind==='pag');assert(pagFile);
assert.equal(pagFile.resourceId,pagPack.stickers[0].pag.resourceId);assert.equal(pagFile.mimeType,'application/octet-stream');
assert.deepEqual(pagParsed.files.get(pagFile.file),pag);
assert.deepEqual(pagParsed.files.get(pagParsed.manifest.stickers[0].file),png);
writeFileSync(root+'/archive-pag-descriptor.json',JSON.stringify(pagDescriptor,null,2));
writeFileSync(root+'/archive-pag-delivery.zip',pagDownload.bytes);
// Upgrade shape: a row published by an old binary has no snapshot; listing is not backfill.
const legacy=await make(label+'-legacy');
sql(`UPDATE sticker_packs SET publication_state=2 WHERE id=${legacy.id}`);
sql(`UPDATE sticker_resources SET ever_published=1 WHERE id=${legacy.coverId}`);
const snapshotsBefore=Number(sql('SELECT COUNT(*) FROM sticker_archive_snapshots'));
assert((await call('/app/sticker/store',{auth:B})).some(p=>p.versionRowId===legacy.id));
assert.equal(Number(sql('SELECT COUNT(*) FROM sticker_archive_snapshots')),snapshotsBefore);
const legacyDescriptor=await call(manifestPath(legacy.id),{auth:B});
assert.equal(Number(sql('SELECT COUNT(*) FROM sticker_archive_snapshots')),snapshotsBefore+1);
const legacyBodies=await Promise.all(Array.from({length:4},()=>call(downloadPath(legacy.id),{auth:B,raw:true})));
for(const body of legacyBodies)assert.deepEqual(body.bytes,legacyBodies[0].bytes);
assert.equal(verifyZip(legacyBodies[0].bytes,legacyDescriptor).manifest.author,label+'-renamed');
const blobsBefore=sql('SELECT COUNT(*) FROM sticker_archive_blobs');
await call(manifestPath('9223372036854775807'),{auth:A,status:404});
await call(downloadPath('9223372036854775807'),{auth:A,status:404});
assert.equal(sql('SELECT COUNT(*) FROM sticker_archive_blobs'),blobsBefore);
// Oversized metadata is rejected before resource reads/output allocation; no giant fixture.
const oversized=await make(label+'-oversized');
sql(`UPDATE sticker_resources SET size=33554431 WHERE id=${oversized.coverId}`);
await call('/admin/sticker/pack/publish',{method:'POST',body:{id:oversized.id},status:400,error:'STICKER_ARCHIVE_SIZE'});
assert.equal(sql(`SELECT publication_state FROM sticker_packs WHERE id=${oversized.id}`),'1');
assert.equal(sql(`SELECT COUNT(*) FROM sticker_archive_snapshots WHERE pack_version_id=${oversized.id}`),'0');
console.log(`STICKER_ARCHIVE_HTTP_EXECUTED: ${checks} actual HTTP responses; realm/draft/access/CAS, strict Stored ZIP CRC+SHA+layout, rename/missing/corrupt cache repair and corrupt-source rejection passed`);
writeFileSync(root+'/archive-http-report.json',JSON.stringify({checks,versionRowId:draft.id,sha256:descriptor.sha256,size:descriptor.size,passed:true},null,2));
