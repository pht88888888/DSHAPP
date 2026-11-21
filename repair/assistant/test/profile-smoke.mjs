/** Offline real-engine host smoke; disposable home only, no install/profile mutation. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { resolve, join } from 'node:path';
import { mkdtemp, mkdir, rm, readFile, writeFile, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
assert.ok(process.env.DSH_ENGINE_PACKAGE, 'DSH_ENGINE_PACKAGE is required');
const anchor=resolve(process.env.DSH_ENGINE_PACKAGE);
const requireEngine=createRequire(anchor);
const load=spec=>import(pathToFileURL(requireEngine.resolve(spec)).href);
const assets=fileURLToPath(new URL('../src/main/assets/repair-agent/',import.meta.url));
const home=await mkdtemp(join(tmpdir(),'dsh-repair-profile-smoke-'));
process.env.DSH_HOME=home;
process.env.DSH_REPAIR_ASSETS=assets;
process.env.DSH_REPAIR_TOOLS_MODULE=join(assets,'tools/index.js');
process.env.DSH_REPAIR_ENGINE_PACKAGE=anchor;
process.env.DSH_REPAIR_PROFILE=assets;
process.env.DSH_REPAIR_MAILBOX=join(home,'mailbox');
process.env.DSH_REPAIR_CONTROL_FILE=join(home,'control-request.json');
await mkdir(join(home,'mailbox/request'),{recursive:true}); await mkdir(join(home,'mailbox/response'));
const {createLaunchEnvironmentSnapshot}=await load('@deepseek-ai/dsh-launch-environment');
const {loadProfileDirectory}=await load('@deepseek-ai/dsh-app-boot');
const {runProfile}=await load('@deepseek-ai/dsh/profile-boot');
try {
  // Android runs the original profile separately from repair-addon. Keeping
  // the profile rooted at assets incorrectly hid nested relative-name imports.
  const profileDir=join(home,'profiles/web');
  await mkdir(profileDir,{recursive:true});
  await writeFile(join(profileDir,'package.json'),await readFile(join(assets,'package.json'),'utf8'));
  const profile=loadProfileDirectory('dsh',profileDir,anchor,{userLayer:true});
  const start=patch=>runProfile({environment:createLaunchEnvironmentSnapshot([{source:'process',values:{...process.env}}]),profile:'web',resolvedProfile:{profile,installAnchor:anchor},patchFiles:[patch],args:['--port','0','--no-open']});
  const raw=await start(join(assets,'repair.patch.yml'));
  try {
    const bad=await raw.ctx.agentPresets.list();
    assert.match(bad.find(x=>x.id==='repair').broken,/repair-tools \(\.\/tools\/index\.js\): never started/,'the historical APK overlay must reproduce the Android import failure');
  } finally {await raw.ctx.fiber.dispose();}
  const source=await readFile(join(assets,'repair.patch.yml'),'utf8');
  const marker='            name: ./tools/index.js';
  assert.equal(source.split(marker).length,2,'native renderer marker must be unique');
  // Matches native RepairAssistantAssets literal canonical-path rendering.
  // No !!js name; no change to the original profile or user settings.
  const addon=join(home,'repair-addon');
  await cp(assets,addon,{recursive:true});
  const rendered=source.replace(marker,`            name: ${JSON.stringify(join(addon,'tools/index.js'))}`);
  const emitted=join(addon,'repair.patch.yml');
  await writeFile(emitted,rendered);
  const {ctx}=await start(emitted);
  try {
    for (const method of ['status','authorize','disconnect','validateUi','themeSnapshot','bindSession']) assert.ok(ctx.typert.local.get(`androidRescue/${method}`), `Host must export ${method}`);
    assert.equal((await ctx.androidRescue.status()).phase, 'disconnected');
    const roster=await ctx.agentPresets.list();
    assert.deepEqual(roster.map(x=>x.id),['standard','ptc','minimal','cordis','repair']);
    assert.deepEqual(roster.filter(x=>x.broken),[], 'every shipped and repair preset must mount without broken rows');
    assert.equal(roster.find(x=>x.id==='repair').name,'修复模式');
    const {presetDisplayText}=await load('@deepseek-ai/dsh-agent-preset-registry/display');
    assert.equal(presetDisplayText(roster.find(x=>x.id==='repair'),()=>{throw new Error('repair display must use its literal published name')}).name,'修复模式');
    assert.equal(ctx.agentPresets.defaultId,'repair');
    const presets=ctx.get('agentPresets');
    const created=await ctx.sessionController.create({cwd:home});
    assert.ok(created);
    const agent=ctx.agents.get(created.sessionId);
    assert.ok(agent,'official session.create must publish the Agent');
    assert.equal(created.agentPreset,'repair','ordinary session creation must choose repair by default');
    assert.equal(presets.composedPreset(agent.ctx),'repair','a real fresh Agent must compose the requested repair preset');
    assert.ok(ctx.tools.get('status',agent),'repair capability must exist on the actual new Agent');
    // Mount the actual rc.5 Client Gateway namespace (no DOM/React doubles).
    const {Context}=await load('@deepseek-ai/cordis');
    const {default:TypertRegistry}=await load('@deepseek-ai/dsh-typert-registry');
    const clientGateway=await load('@deepseek-ai/dsh-api-gateway/client');
    const client=new Context();
    try {
      await client.plugin(TypertRegistry);
      const peer={id:'repair-smoke-peer',ctx,principal:{kind:'owner'},signal:new AbortController().signal,interactions:'none',dispose:async()=>{}};
      // Demonstrate the old plain provided object is rejected by the real Host
      // Gateway, so this test cannot accidentally bypass service validation.
      const rescueService=ctx.androidRescue;
      const binding=rescueService.typertRemote;
      assert.ok(binding);
      rescueService.typertRemote=undefined;
      await assert.rejects(ctx.typertGateway.invoke({namespace:'androidRescue',method:'status',args:{},peer}), /no visible typertRemote binding/);
      rescueService.typertRemote=binding;
      client.provide('connection', { interactions:'none', isLoopback:true, generation:{getSnapshot:()=>undefined},
        rpc:{call:async (_channel,endpoint,payload,signal) => ({ok:true,value:await ctx.typertGateway.invoke({namespace:endpoint.split('/')[0],method:endpoint.split('/')[1],args:payload.args,signal,peer})})} });
      await client.plugin(clientGateway);
      const {remoteParameters}=await import(pathToFileURL(join(addon,'rescue-entry/index.js')).href);
      await client.remote.$mount({package:'@dsh-android/repair-profile',descriptors:['status','authorize','disconnect','validateUi','themeSnapshot','bindSession'].map(method=>({id:`@dsh-android/repair-profile#${method}`,service:'androidRescue',namespace:'androidRescue',method,parameters:remoteParameters(method),result:{mode:'src-json'},invocation:{kind:'direct'}}))});
      assert.ok(client.get('remote.androidRescue'),'actual rc.5 Client must expose injected namespace');
      const {default:presetRemote}=await load('@deepseek-ai/dsh-agent-preset-registry/remote');
      await client.remote.$mount(presetRemote);
      const wireRoster=await client.remote.agentPresets.list();
      assert.equal(wireRoster.ok,true);
      assert.equal(wireRoster.value.presets.find(x=>x.id==='repair').name,'修复模式');
      assert.deepEqual(wireRoster.value.presets.filter(x=>x.broken),[],'actual Client/Host preset roster must remain selectable');
      assert.deepEqual(await client.remote.androidRescue.status(),{ok:true,value:{phase:'disconnected',target:null,label:null,message:'',revision:0}});
      assert.deepEqual(await client.remote.androidRescue.authorize(),{ok:true,value:{requested:true}});
      assert.deepEqual(await client.remote.androidRescue.disconnect(),{ok:true,value:{requested:true}});
      await rm(process.env.DSH_REPAIR_CONTROL_FILE);
      assert.deepEqual(await client.remote.androidRescue.validateUi(),{ok:true,value:{requested:true}});
      assert.deepEqual(JSON.parse(await readFile(process.env.DSH_REPAIR_CONTROL_FILE,'utf8')),{action:'validateUi'});
      const theme={dark:true,colors:{background:'#101010',surface:'#202020',text:'#ffffff',primary:'#4488ff'}};
      assert.deepEqual(await client.remote.androidRescue.themeSnapshot(theme),{ok:true,value:{stored:true}});
      assert.deepEqual(JSON.parse(await readFile(join(home,'theme-snapshot.json'),'utf8')),theme);
      await writeFile(join(home,'connection-state.json'),JSON.stringify({phase:'connected',target:'com.dsh.android',label:'System',message:'connected',revision:1,binding:'12345678-1234-1234-1234-123456789abc'}));
      assert.deepEqual(await client.remote.androidRescue.bindSession(created.sessionId),{ok:true,value:{bound:true}});
      assert.deepEqual(JSON.parse(await readFile(join(home,'evidence-route.json'),'utf8')),{binding:'12345678-1234-1234-1234-123456789abc',sessionId:created.sessionId});
    } finally {await client.fiber.dispose();}
    console.log('REPAIR_PROFILE_SMOKE_OK',JSON.stringify({roster,createdPreset:created.agentPreset,tools:ctx.tools.definitions(agent).map(x=>x.name).sort(),clientRemote:'androidRescue/status+authorize+disconnect+validateUi+themeSnapshot+bindSession envelopes verified'}));
  } finally { await ctx.fiber.dispose(); }
} finally {
  assert.ok(resolve(home).startsWith(join(tmpdir(),'dsh-repair-profile-smoke-')));
  await rm(home,{recursive:true,force:true});
}
