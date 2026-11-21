import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, readFile, writeFile, readdir, rename, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { METHODS, MAX_CHUNK_BYTES, MAX_MESSAGE_BYTES, TIMEOUT_MS, validPath, validateArgs } from '../src/main/assets/repair-agent/tools/contract.js';
import { createMailbox } from '../src/main/assets/repair-agent/tools/mailbox.js';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const digest = 'a'.repeat(64);
const path = 'app/files/engine/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js';
const anchor = process.env.DSH_ENGINE_PACKAGE;
assert.ok(anchor, 'DSH_ENGINE_PACKAGE must identify the actual APK rc.5 engine; no fake fallback');
const engineRequire = createRequire(resolve(anchor));

// A transport responder, not an Android/native broker transaction implementation.
async function transport(run) {
  const root = await mkdtemp(join(tmpdir(),'dsh-app-files-'));
  await mkdir(join(root,'request')); await mkdir(join(root,'response'));
  await writeFile(join(root,'binding.json'),JSON.stringify({binding:'12345678-1234-1234-1234-123456789abc'}));
  const mailbox = createMailbox({root,timeoutMs:1000,pollMs:2});
  async function roundtrip(method,args,result) {
    const pending = mailbox.call(method,args);
    let request;
    for (let i=0;i<500;i++) {
      const files = await readdir(join(root,'request'));
      const file = files.find(x=>x.endsWith('.json'));
      if (file) {
        const bytes = await readFile(join(root,'request',file));
        assert.ok(bytes.length <= MAX_MESSAGE_BYTES);
        request = JSON.parse(bytes);
        const tmp = join(root,'response',`${request.id}.tmp`);
        await writeFile(tmp,JSON.stringify({id:request.id,ok:true,result}));
        await rename(tmp,join(root,'response',`${request.id}.json`));
        break;
      }
      await new Promise(r=>setTimeout(r,2));
    }
    assert.ok(request,'expected native-bound request');
    assert.deepEqual(await pending,result);
    assert.deepEqual(request.args,args,'no hidden argument/path rewriting in transport');
    return request;
  }
  try { await run(roundtrip); }
  finally { assert.ok(resolve(root).startsWith(join(tmpdir(),'dsh-app-files-'))); await rm(root,{recursive:true,force:true}); }
}

test('13-tool closed contract has chunk/message limits, not a full-file ceiling', () => {
  assert.equal(Object.keys(METHODS).length,13);
  assert.equal(MAX_CHUNK_BYTES,131072);
  assert.equal(MAX_MESSAGE_BYTES,786432);
  assert.equal(TIMEOUT_MS,180000);
  for (const args of [{path,offset:0,length:131072},{path,offset:227099,length:1},{path,offset:2**40,length:8192,encoding:'base64'}]) assert.doesNotThrow(()=>validateArgs('read_file',args));
  assert.doesNotThrow(()=>validateArgs('stage_patch',{path,expected_sha256:digest,offset:227098,delete_bytes:2,content:'x'}));
  for(const args of [{path,offset:-1},{path,length:0},{path,length:131073},{path,offset:0.1},{path,offset:Number.MAX_SAFE_INTEGER+1},{path,encoding:'hex'},{path,target:'another.app'}]) assert.throws(()=>validateArgs('read_file',args));
  assert.throws(()=>validateArgs('stage_patch',{path,expected_sha256:digest,offset:Number.MAX_SAFE_INTEGER,delete_bytes:1,content:''}));
});

test('native inventory paths allow safe Unicode, all own-app kinds and legacy aliases only', () => {
  for(const p of ['app/shared_prefs/设置 与状态.xml','app/databases/session.db','device_app/cache/binary','external_files/folder/file.dat','external_cache_2/临时 文件','external_media_10/A😀.png','engine/node_modules/other-package/native.node']) assert.ok(validPath(p),p);
  for(const p of ['', 'app', 'device_app','external_files_2']) { assert.ok(validPath(p,true)); assert.equal(validPath(p),false); }
  for(const p of ['/app/file','../app/file','app//file','app/./file','app/a/../file','app/file/','app/..','app/a\0b','app/a\\b','unknown/file','external_files_0/file','app/\ud800']) { assert.equal(validPath(p),false,p); assert.throws(()=>validateArgs('read_file',{path:p})); }
  const deepPath = 'app/' + 'dependency/'.repeat(200) + 'module.js';
  assert.ok(deepPath.length > 1024 && deepPath.length < 4096);
  assert.doesNotThrow(()=>validateArgs('read_file',{path:deepPath}));
  assert.equal(validPath('app/'+'x'.repeat(4093)),false,'native 4096-character path bound');
  assert.doesNotThrow(()=>validateArgs('list_files',{}));
  assert.doesNotThrow(()=>validateArgs('list_files',{path:'',offset:0,limit:200}));
  assert.doesNotThrow(()=>validateArgs('list_files',{path:'app',offset:200,limit:1}));
  for(const args of [{path:'app',cursor:'0'},{path:'app',limit:201},{path:'app',limit:0},{offset:'0'}]) assert.throws(()=>validateArgs('list_files',args));
});

test('UTF8 and base64 staged payloads are bounded by decoded bytes and preserve binary NUL', () => {
  const base = {path:'app/databases/own data.db',expected_sha256:digest};
  const bytes = Buffer.alloc(MAX_CHUNK_BYTES,255); bytes[3]=0;
  assert.doesNotThrow(()=>validateArgs('stage_edit',{...base,content:bytes.toString('base64'),encoding:'base64'}));
  assert.doesNotThrow(()=>validateArgs('stage_edit',{...base,content:'\0text'}));
  assert.doesNotThrow(()=>validateArgs('stage_edit',{...base,content:'é'.repeat(MAX_CHUNK_BYTES/2)}));
  for(const content of ['Zg','Zh==','Zg=',' Zg==','-_==','Z===','%%%%']) assert.throws(()=>validateArgs('stage_edit',{...base,content,encoding:'base64'}));
  for(const content of ['é'.repeat(MAX_CHUNK_BYTES/2+1),'\ud800']) assert.throws(()=>validateArgs('stage_edit',{...base,content}));
  assert.throws(()=>validateArgs('stage_edit',{...base,content:Buffer.alloc(MAX_CHUNK_BYTES+1).toString('base64'),encoding:'base64'}));
});

test('operation-specific candidates require exact hashes, valid permission bits and closed fields', () => {
  const base = {path:'app/files/new private file'};
  for(const args of [{...base,operation:'create'},{...base,operation:'create',content:'AAEC',encoding:'base64',mode:384},{...base,operation:'delete',expected_sha256:digest},{...base,operation:'mkdir',mode:448},{...base,operation:'rmdir'}]) assert.doesNotThrow(()=>validateArgs('stage_file',args));
  for(const args of [{...base,operation:'delete'},{...base,operation:'delete',expected_sha256:digest,content:''},{...base,operation:'create',expected_sha256:digest},{...base,operation:'mkdir',content:''},{...base,operation:'mkdir',expected_sha256:digest},{...base,operation:'rmdir',mode:448},{...base,operation:'rename'},{...base,operation:'create',mode:512},{...base,operation:'create',mode:'0600'},{...base,operation:'create',approved:true}]) assert.throws(()=>validateArgs('stage_file',args));
  assert.doesNotThrow(()=>validateArgs('stage_file',{...base,operation:'mkdir',mode:0o500}));
  for(const mode of [0,0o400,0o100,0o077]) assert.throws(()=>validateArgs('stage_file',{...base,operation:'mkdir',mode}),/unsupported_mode/);
  for(const mode of [0,0o200,0o111,0o077]) assert.throws(()=>validateArgs('stage_file',{...base,operation:'create',mode}),/unsupported_mode/);
  for(const method of ['stage_edit','stage_patch']) {
    const args = {path,expected_sha256:digest,content:'',...(method==='stage_patch'?{offset:0,delete_bytes:0}:{})};
    assert.doesNotThrow(()=>validateArgs(method,{...args,mode:0o755}));
    assert.doesNotThrow(()=>validateArgs(method,{...args,mode:0o400}));
    for(const mode of [0,0o200,0o111,0o077]) assert.throws(()=>validateArgs(method,{...args,mode}),/unsupported_mode/);
    assert.throws(()=>validateArgs(method,{...args,expected_sha256:'A'.repeat(64)}));
    assert.throws(()=>validateArgs(method,{...args,consent:true}));
  }
});

test('real rc.5 app-boot larger than 128KiB uses full hash with bounded reads and tiny byte patch', async () => {
  const original = await readFile(engineRequire.resolve('@deepseek-ai/dsh-app-boot'));
  assert.ok(original.length >= 227100,`actual dependency must exceed the reported 227100B: ${original.length}`);
  const fullHash = sha(original);
  const insertAt = original.indexOf(Buffer.from('import { createRequire'));
  assert.ok(insertAt >= 0);
  const insertion = Buffer.from('/* localized rescue candidate */\n');
  const candidate = Buffer.concat([original.subarray(0,insertAt),insertion,original.subarray(insertAt)]);
  assert.equal(candidate.length,original.length+insertion.length);
  assert.notEqual(sha(candidate),fullHash);
  await transport(async roundtrip => {
    for(const offset of [0,131072,original.length-32]) {
      const length = Math.min(8192,original.length-offset);
      const content = original.subarray(offset,offset+length).toString('base64');
      await roundtrip('read_file',{path,offset,length,encoding:'base64'},{path,sha256:fullHash,size_bytes:original.length,offset,length,encoding:'base64',content,eof:offset+length===original.length});
    }
    const args = {path,expected_sha256:fullHash,offset:insertAt,delete_bytes:0,content:insertion.toString('base64'),encoding:'base64'};
    const request = await roundtrip('stage_patch',args,{ok:true,change_id:'native-candidate',operation:'edit',original_size_bytes:original.length,candidate_size_bytes:candidate.length});
    assert.ok(Buffer.byteLength(JSON.stringify(request))<2048,'only localized insertion crosses the mailbox');
    assert.equal(request.args.expected_sha256,sha(original),'hash binds whole original, not any returned chunk');
  });
});

test('binary private configuration transport and paginated inventory stay additive and bound', async () => transport(async roundtrip => {
  const path='app/files/config/custom 状态.bin';
  const bytes=Buffer.from([0,255,128,65,0,10]);
  await roundtrip('list_files',{}, {roots:[{name:'app',path:'app',type:'directory'}],entries:[{name:'app',path:'app',type:'directory'}],offset:0,next_offset:null,has_more:false});
  await roundtrip('list_files',{path:'app/files',offset:200,limit:100},{entries:[{name:'custom 状态.bin',path,type:'file',size_bytes:bytes.length,mode:384}],offset:200,next_offset:null,has_more:false});
  await roundtrip('read_file',{path,offset:1,length:3,encoding:'base64'},{path,sha256:sha(bytes),size_bytes:bytes.length,offset:1,length:3,encoding:'base64',content:bytes.subarray(1,4).toString('base64'),eof:false});
  await roundtrip('stage_edit',{path,expected_sha256:sha(bytes),content:bytes.toString('base64'),encoding:'base64',mode:384},{ok:true,change_id:'binary-candidate',operation:'edit'});
  await roundtrip('stage_file',{path:'app/files/config/new.bin',operation:'create',content:bytes.toString('base64'),encoding:'base64'},{ok:true,change_id:'create-candidate',operation:'create'});
  await roundtrip('stage_file',{path,operation:'delete',expected_sha256:sha(bytes)},{ok:true,change_id:'delete-candidate',operation:'delete'});
}));
