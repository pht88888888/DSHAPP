import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {resolve,join} from 'node:path';
import {mkdtemp,writeFile,rm,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {assertSettingsJson,assertModelNamespace} from '../src/main/assets/repair-agent/tools/settings-policy.js';
const anchor=process.env.DSH_ENGINE_PACKAGE;
assert.ok(anchor,'Real DSH_ENGINE_PACKAGE rc.5 input required');
process.env.DSH_REPAIR_ENGINE_PACKAGE=resolve(anchor);
const req=createRequire(resolve(anchor)); const load=spec=>import(pathToFileURL(req.resolve(spec)).href);

test('settings guard accepts normal model JSON and rejects expressions/prototypes/accessors/depth',()=>{
  assert.doesNotThrow(()=>assertSettingsJson({providers:{local:{baseURL:'https://api.example.test/v1',apiKeyEnv:'MY_KEY',models:[{id:'test',name:'Test',contextWindow:10000}]}}}));
  for(const key of ['__jsExpr','__proto__','prototype','constructor']) assert.throws(()=>assertSettingsJson(JSON.parse(`{"providers":{"x":{"${key}":"process.exit()"}}}`)),/FORBIDDEN/);
  assert.throws(()=>assertSettingsJson({x:Infinity}),/JSON_ONLY/);
  assert.throws(()=>assertSettingsJson(Object.create({x:1})),/JSON_ONLY/);
  assert.throws(()=>assertSettingsJson({get x(){throw Error('must not execute getter')}}),/JSON_ONLY/);
  let deep={}; for(let i=0;i<50;i++) deep={deep}; assert.throws(()=>assertSettingsJson(deep),/TOO_COMPLEX/);
  for(const id of ['preset-repair','bash-sandbox','agent-preset-registry','config-editor','../x']) assert.throws(()=>assertModelNamespace(id),/NAMESPACE_FORBIDDEN/);
  for(const id of ['llm-deepseek','llm-pi-ai','agent-default-model']) assert.doesNotThrow(()=>assertModelNamespace(id));
});

test('actual rc.5 ConfigEditor subclass activates actual Settings service without generic editor remotes',async()=>{
  const root=await mkdtemp(join(tmpdir(),'dsh-repair-settings-test-'));
  process.env.DSH_REPAIR_PROFILE=root;
  const patch=join(root,'cordis.patch.yml');
  await writeFile(patch,'[]\n'); await writeFile(join(root,'package.json'),'{"name":"@local/test","private":true,"dsh":{"profile":{"bundles":[]}}}\n');
  const {Context}=await load('@deepseek-ai/cordis');
  const {default:Settings}=await load('@deepseek-ai/dsh-settings');
  const {default:Guard}=await import(new URL('../src/main/assets/repair-agent/tools/settings.js',import.meta.url));
  const ctx=new Context();
  const loader={entries:()=>[],await:()=>Promise.resolve()};
  ctx.provide('loader',loader);
  ctx.provide('profileContext',{name:'fixhelp',dir:root,patchPath:patch,installAnchor:resolve(anchor),home:root});
  try {
    const editorFiber=ctx.plugin(Guard); await editorFiber;
    const settingsFiber=ctx.plugin(Settings); await settingsFiber;
    assert.ok(ctx.get('configEditor'));
    assert.ok(ctx.get('settings'),'settings now active with genuine official config editor dependency');
    assert.equal(ctx.get('configEditor').documentPath,patch);
    assert.deepEqual(ctx.get('settings').describe(),[]);
    assert.equal(await ctx.get('settings').prepareDocument(),patch);
    await assert.rejects(ctx.get('configEditor').edit({options:{id:'preset-repair'}},()=>({plugins:[]})),/NAMESPACE_FORBIDDEN/);
    // The actual official writer checks root-include entry ownership before any write.
    await assert.rejects(ctx.get('configEditor').edit({options:{id:'llm-deepseek'}},()=>({baseURL:'https://api.example.test'})),/entry is no longer available/);
    assert.equal(await readFile(patch,'utf8'),'[]\n');
    process.env.DSH_REPAIR_PROFILE=join(root,'elsewhere');
    assert.throws(()=>ctx.get('configEditor').documentPath,/PATH_FORBIDDEN/);
  } finally {
    await ctx.fiber.dispose();
    assert.ok(resolve(root).startsWith(join(tmpdir(),'dsh-repair-settings-test-')));
    await rm(root,{recursive:true,force:true});
  }
});
