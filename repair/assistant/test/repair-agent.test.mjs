import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve, join, dirname } from 'node:path';
import { mkdtemp, mkdir, readdir, readFile, writeFile, rename, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { runInNewContext } from 'node:vm';
import { createMailbox } from '../src/main/assets/repair-agent/tools/mailbox.js';
import { METHODS, MAX_MESSAGE_BYTES, MAX_CHUNK_BYTES, validateArgs } from '../src/main/assets/repair-agent/tools/contract.js';
const assets = fileURLToPath(new URL('../src/main/assets/repair-agent/', import.meta.url));
const anchor = process.env.DSH_ENGINE_PACKAGE;
assert.ok(anchor, 'DSH_ENGINE_PACKAGE must point to the real APK rc.5 engine package.json; no fake fallback');
const requireEngine = createRequire(resolve(anchor));
const fromEngine = spec => import(pathToFileURL(requireEngine.resolve(spec)).href);
process.env.DSH_REPAIR_ENGINE_PACKAGE = resolve(anchor);
process.env.DSH_REPAIR_TOOLS_MODULE = join(assets, 'tools/index.js');
process.env.DSH_REPAIR_ASSETS = assets;
test('repair entry creates a fresh workspace-attached session for current, recent and first-use flows', async () => {
  let client;
  runInNewContext(await readFile(join(assets,'rescue-entry/client.js'),'utf8'), {window:{__ModuleLoader__:{load:definition=>{client=definition.factory();}}}});
  for (const phase of ['disconnected','connected','disconnecting','error','expired',undefined]) assert.equal(client.showFeedback('requested','requested',phase),false,'cancel/settled state never leaves a stale authorization claim');
  for (const phase of ['checking','authorizing','connecting']) assert.equal(client.showFeedback('requested','requested',phase),true);
  assert.equal(client.showFeedback('native failed','requested','disconnected'),true,'actual errors remain visible');
  const opened=[]; const created=[];
  let items=[{workspaceId:'recent',sessionIds:['b'],createdAt:'2026-01-01'},{workspaceId:'current',sessionIds:['a'],createdAt:'2025-01-01'}];
  let byId={a:{updatedAt:1,retainedBy:{mainView:1}},b:{updatedAt:99}};
  let initialized=0;
  const scope={workspaces:{list:{getSnapshot:()=>({phase:'ready',items})},initializeDefault:async()=>{initialized++;return {workspaceId:'default'};}},
    sessions:{list:{getSnapshot:()=>({phase:'ready',ids:Object.keys(byId),byId})},create:async opts=>{created.push(JSON.parse(JSON.stringify(opts)));return 'new-'+created.length;}},
    uiWorkspace:{openSession:id=>opened.push(id)},locale:{bind:()=>()=> 'Choose a workspace first'}};
  await client.startRepairSession(scope);
  assert.deepEqual(created[0],{workspaceId:'current',agentPreset:'repair'});
  byId.a.retainedBy.mainView=0;
  await client.startRepairSession(scope);
  assert.deepEqual(created[1],{workspaceId:'recent',agentPreset:'repair'});
  items=[]; byId={};
  await client.startRepairSession(scope);
  assert.deepEqual(created[2],{workspaceId:'default',agentPreset:'repair'});
  assert.equal(initialized,1);
  assert.deepEqual(opened,['new-1','new-2','new-3']);
  scope.workspaces.initializeDefault=async()=>undefined;
  await assert.rejects(client.startRepairSession(scope),/Choose a workspace first/);
  assert.equal(created.length,3,'no orphan Session when workspace preparation is ineligible');
});
test('rc.5 landing and active-session slots share the compact connection panel without opening a session', async () => {
  const registrations = new Map(), mounts = [], writes = [], effects = [];
  let hook = 0, states = [], client;
  const React = {
    Fragment: Symbol('Fragment'),
    createElement: (type, props, ...children) => ({type, props: props ?? {}, children}),
    useState: initial => { const index = hook++; if (!(index in states)) states[index] = initial; return [states[index], value => { states[index] = value; }]; },
    useRef: current => ({current}), useEffect: effect => effects.push(effect),
  };
  const scope = {
    effect: effect => effect(),
    locale: {register: () => () => {}, bind: () => key => key},
    remote: {androidRescue: new Proxy({}, {get: (_, method) => () => { writes.push(method); }})},
    slots: {inject: (_name, register) => register(), register: (spec, component) => { registrations.set(spec.name, {spec, component}); }},
    sessions: {create: () => { writes.push('create'); }}, uiWorkspace: {openSession: () => { writes.push('openSession'); }},
  };
  runInNewContext(await readFile(join(assets,'rescue-entry/client.js'),'utf8'), {
    window: {__ModuleLoader__: {load: definition => { client = definition.factory(id => {
      assert.equal(id, 'react'); return React;
    }); }}},
  });
  await client.apply({remote: {$mount: async descriptors => mounts.push(descriptors)}, inject: (_deps, callback) => callback(scope)});
  assert.equal(mounts.length, 1);
  const landing = registrations.get('conversation.hero.options'), header = registrations.get('conversation.session.header.utilities');
  assert.ok(landing); assert.ok(header); assert.ok(registrations.has('settings.section'));
  assert.equal(landing.spec.order, 80); assert.equal(header.spec.order, 80);
  const landingElement = landing.component(), headerElement = header.component();
  assert.equal(landingElement.type, headerElement.type, 'both entries use the same Rescue panel component');
  assert.equal(landingElement.props.compact, true); assert.equal(headerElement.props.compact, true);
  hook = 0;
  const disconnected = landingElement.type(landingElement.props);
  const button = disconnected.children[0];
  assert.equal(button.type, 'button'); assert.equal(button.props['aria-label'], '维修状态');
  assert.equal(button.children[0], '连接 · phases.unknown');
  button.props.onClick();
  assert.equal(states[3], true, 'landing entry opens its shared details locally');
  assert.deepEqual(writes, [], 'render and entry click do not authorize, validate, or create/open a session');
  states = [{phase:'connected',target:'com.dsh.android'}, '', '', false]; hook = 0;
  assert.equal(headerElement.type(headerElement.props).children[0].children[0], '连接 · phases.connected');
  states = [{phase:'connected',target:'com.dsh.android',task:{phase:'validating',message:'正在准备运行环境：2/4',last_attempt:{level:'engine'}}}, '', '', false]; hook = 0;
  assert.equal(landingElement.type(landingElement.props).children[0].children[0], '维修 · 后台验证引擎中');
  hook = 0;
  const detail = landingElement.type({...landingElement.props,compact:false});
  const flatten = node => !node || typeof node !== 'object' ? [] : [node, ...(node.children ?? []).flatMap(flatten)];
  const progress = flatten(detail).find(node => node.children?.includes('正在准备运行环境：2/4'));
  assert.equal(progress?.props.role, 'status'); assert.equal(progress?.props['aria-live'], 'polite');
  assert.deepEqual(writes, []);
  // Assert the supported seat against the actual locked rc.5 source and its compiled artifact,
  // not a new slot invented by this helper or a DOM-position guess.
  const conversation = resolve(dirname(anchor), '../dsh-client-ui-conversation');
  const owner = await readFile(join(conversation,'src/client/skeleton/ConversationContent.tsx'),'utf8');
  const css = await readFile(join(conversation,'src/client/skeleton/ConversationRoot.module.css'),'utf8');
  const contract = await readFile(join(conversation,'src/client/contract/slots.ts'),'utf8');
  const compiled = await readFile(join(conversation,'lib/client.js'),'utf8');
  assert.match(contract, /'conversation\.hero\.options': \{ kind: 'list'; scope: 'session' \}/);
  assert.match(owner, /renderSlot\('conversation\.hero\.agentPreset', \{\}\)[\s\S]*?sessionId === undefined \? null :[\s\S]*?renderSlot\('conversation\.hero\.options', \{\}\)/);
  assert.match(owner, /\{hero && heroWorkspaceRow\}/);
  assert.match(css, /\.heroOptions\s*\{[^}]*margin-left:\s*auto;/);
  assert.match(compiled, /sessionId===void 0\?null:[\s\S]{0,180}renderSlot\(`conversation\.hero\.options`,\{\}\)/);
});
const binding = '12345678-1234-1234-1234-123456789abc';
async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'dsh-repair-mailbox-test-'));
  await mkdir(join(root, 'request')); await mkdir(join(root, 'response'));
  await writeFile(join(root, 'binding.json'), JSON.stringify({binding}));
  try { await run(root); }
  finally { assert.ok(resolve(root).startsWith(join(tmpdir(), 'dsh-repair-mailbox-test-'))); await rm(root, { recursive: true, force: true }); }
}
async function respond(root, makeReply) {
  for (let tries = 0; tries < 300; tries++) {
    const files = (await readdir(join(root, 'request'))).filter(x => x.endsWith('.json'));
    if (files.length) {
      const request = JSON.parse(await readFile(join(root, 'request', files[0]), 'utf8'));
      const tmp = join(root, 'response', `${request.id}.tmp`);
      await writeFile(tmp, JSON.stringify(makeReply(request)));
      await rename(tmp, join(root, 'response', `${request.id}.json`));
      return request;
    }
    await new Promise(resolve => setTimeout(resolve, 5));
  }
  throw Error('No mailbox request');
}

test('real rc.5 additive overlay preserves every original row and preset', async () => {
  const pkg = JSON.parse(await readFile(anchor, 'utf8'));
  assert.equal(pkg.name, '@deepseek-ai/dsh'); assert.equal(pkg.version, '0.2.2-rc.5');
  const boot = await fromEngine('@deepseek-ai/dsh-app-boot');
  const profile = boot.loadProfileDirectory('dsh', assets, resolve(anchor), { userLayer: true });
  assert.deepEqual(profile.skippedBundles, []);
  assert.equal(profile.layers.length, 2);
  assert.deepEqual(profile.patches, []);
  const warnings = [];
  const entries = boot.composeEntries([...profile.layers.map(x => x.patches), boot.loadOverlayPatches('dsh', join(assets, 'repair.patch.yml'))], x => warnings.push(x));
  assert.deepEqual(warnings, []);
  const baseline = boot.composeEntries(profile.layers.map(x => x.patches));
  assert.deepEqual(entries.filter(x => !['preset-repair','android-rescue-entry','agent-preset-registry'].includes(x.id)), baseline.filter(x => x.id !== 'agent-preset-registry'));
  assert.equal(entries.find(x => x.id === 'agent-preset-registry').config.default, 'repair');
  assert.deepEqual(entries.filter(x => x.name === '@deepseek-ai/dsh-agent-preset' && !x.disabled).map(x => x.config.id), ['standard','ptc','minimal','cordis','repair']);
  const preset = entries.find(x => x.id === 'preset-repair');
  const standard = baseline.find(x => x.id === 'preset-standard');
  assert.equal(preset.config.plugins.length, standard.config.plugins.length + 1);
  assert.match(preset.config.plugins[0].config.prefix, /原版 DSH 的界面、设置、插件、预设和工具保持可用/);
  assert.match(preset.config.plugins[0].config.prefix, /read_file 和 list_changes 均返回 io_error/);
  assert.match(preset.config.plugins[0].config.prefix, /不得重复未改变条件的调用/);
  assert.match(preset.config.plugins[0].config.prefix, /必须读到原件后才能断定 shebang/);
  for (const requirement of [/不是仅限 dsh\/lib/,/128 KiB 只限制单次/,/stage_patch/,/227100/,/密钥、凭据、会话和隐私/,/可能发送给所选模型/,/本次原生授权覆盖本任务内的文件修改与回退/,/不再逐文件弹窗/,/点「重启并验证」/,/正式签名的更新包/,/先 list_changes 核实/]) assert.match(preset.config.plugins[0].config.prefix,requirement);
  assert.doesNotMatch(preset.config.plugins[0].config.prefix,/如果救援范围不支持、文件过大/);
  assert.deepEqual(preset.config.plugins.slice(1,-1), standard.config.plugins.slice(1));
});

test('real Cordis + rc.5 adds 13 scoped tools while preserving inherited tools', async () => fixture(async root => {
  process.env.DSH_REPAIR_MAILBOX = root;
  const { Context } = await fromEngine('@deepseek-ai/cordis');
  const { createScope, scopeTarget } = await fromEngine('@deepseek-ai/dsh-scope');
  const { default: Tools, defineTool } = await fromEngine('@deepseek-ai/dsh-tools');
  const plugin = await import(pathToFileURL(join(assets, 'tools/index.js')).href);
  const ctx = new Context();
  ctx.provide('systemPrompt', { tools() {} });
  const toolsFiber = ctx.plugin(Tools);
  await toolsFiber;
  ctx.tools.register(defineTool({ name: 'bash', description: 'unsafe inherited tool', parameters: {}, output: { schema: { type: 'string' }, render: (_, value) => [{type:'text',text:value}] }, execute: () => 'unsafe' }));
  const presetKey = {};
  const presetScope = createScope(ctx, presetKey);
  const pluginFiber = presetScope.ctx.plugin(plugin);
  await pluginFiber;
  const agent = {};
  const scoped = createScope(ctx, agent, {parent:presetKey});
  agent.ctx = scoped.ctx;
  const carrier = scopeTarget(agent, agent);
  const before = await ctx.tools.resolve({agent,signal:new AbortController().signal});
  assert.deepEqual(ctx.tools.schemas(agent, before).map(x=>x.name), ['bash'], 'repair tools unavailable before initialization');
  before.dispose();
  await ctx.serial(carrier, 'agent/created', {agent,source:'fresh'});
  // Switching targets before the first tool call must not rebind the chat.
  await writeFile(join(root,'binding.json'), JSON.stringify({binding:'abcdef12-1234-1234-1234-123456789abc'}));
  const premature = await ctx.tools.resolve({agent,signal:new AbortController().signal});
  await assert.rejects(ctx.tools.executable('status',agent,premature).execute({}, {agent,signal:new AbortController().signal}), /REPAIR_BINDING_CHANGED_NEW_SESSION_REQUIRED/);
  premature.dispose();
  await writeFile(join(root,'binding.json'), JSON.stringify({binding}));
  assert.deepEqual(ctx.tools.definitions(agent).map(x => x.name).sort(), ['bash', ...Object.keys(METHODS)].sort());
  assert.ok(ctx.tools.get('bash', agent));
  assert.equal(ctx.tools.executable('status', agent), undefined, 'outside a prepared step conditional tools are not executable');
  const signal = new AbortController().signal;
  const selected = await ctx.tools.resolve({agent, signal});
  const schemas = ctx.tools.schemas(agent, selected);
  assert.equal(Object.keys(METHODS).length, 13);
  assert.deepEqual(schemas.map(x => x.name).sort(), ['bash', ...Object.keys(METHODS)].sort());
  const readSchema = schemas.find(x => x.name === 'read_file').parameters;
  assert.deepEqual(readSchema.required, ['path']);
  assert.equal(readSchema.properties.offset.type, 'integer');
  assert.equal(readSchema.properties.length.type, 'integer');
  assert.deepEqual(readSchema.properties.encoding.enum, ['utf8','base64']);
  assert.equal(schemas.find(x => x.name === 'list_files').parameters.required?.length ?? 0, 0);
  assert.equal(schemas.find(x => x.name === 'stage_patch').parameters.properties.delete_bytes.type, 'integer');
  const status = ctx.tools.executable('status', agent, selected);
  assert.ok(status);
  await assert.rejects(status.execute({target:'com.other'}, {agent,signal}), /unexpected|additional|INVALID_ARGUMENTS|not allowed/i);
  await assert.rejects(status.execute({}, {signal}), /REPAIR_AGENT_REQUIRED/);
  const response = respond(root, request => ({id:request.id,ok:true,result:{ok:true,authorized:true}}));
  assert.deepEqual(await status.execute({}, {agent,signal}), {ok:true,authorized:true});
  assert.equal((await response).method, 'status');
  // Exercise real typed tool execution, not only schema publication. Native
  // responses below are transport fixtures, not Android transaction evidence.
  const appPath = 'app/files/engine/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js';
  for (const [method,args,result] of [
    ['list_files', {}, {roots:[{name:'app',path:'app',type:'directory'}],entries:[],offset:0,next_offset:null,has_more:false}],
    ['read_file', {path:appPath,offset:200000,length:3,encoding:'base64'}, {path:appPath,sha256:'0'.repeat(64),size_bytes:227100,offset:200000,length:3,encoding:'base64',content:'AP+A',eof:false}],
    ['stage_patch', {path:appPath,expected_sha256:'0'.repeat(64),offset:200000,delete_bytes:1,content:'AA==',encoding:'base64'}, {ok:true,change_id:'patch-candidate'}],
    ['stage_edit', {path:'app/files/settings/custom.bin',expected_sha256:'0'.repeat(64),content:'AP+A',encoding:'base64',mode:384}, {ok:true,change_id:'binary-candidate'}],
    ['stage_file', {path:'app/files/new 状态.bin',operation:'create',content:'AA==',encoding:'base64'}, {ok:true,change_id:'create-candidate'}],
  ]) {
    const reply = respond(root,request=>({id:request.id,ok:true,result}));
    assert.deepEqual(await ctx.tools.executable(method,agent,selected).execute(args,{agent,signal}),result);
    const sent = await reply;
    assert.equal(sent.method,method);
    assert.deepEqual(sent.args,args);
  }
  ctx.emit(carrier, 'agent/status', {agent,status:'idle'});
  assert.deepEqual(await readdir(join(root,'request')), [], 'idle does not renew activity');
  const activeReply = respond(root, request => ({id:request.id,ok:true,result:{ok:true}}));
  ctx.emit(carrier, 'agent/assistant-stream', {agent,frame:{type:'chunk'}});
  assert.equal((await activeReply).method, 'activity');
  // The internal signal is never offered to the model and uses no target/grant.
  assert.equal(ctx.tools.get('activity', agent), undefined);
  await writeFile(join(root,'binding.json'), JSON.stringify({binding:'abcdef12-1234-1234-1234-123456789abc'}));
  await assert.rejects(status.execute({}, {agent,signal}), /REPAIR_BINDING_CHANGED_NEW_SESSION_REQUIRED/);
  for (const file of await readdir(join(root,'request'))) {
    const pending = JSON.parse(await readFile(join(root,'request',file),'utf8'));
    assert.equal(pending.binding, binding, 'even pending activity is bound to old epoch');
  }
  selected.dispose();
  await scoped.dispose();
  await presetScope.dispose();
  assert.deepEqual(ctx.tools.definitions(agent).map(x=>x.name), ['bash']);
  await ctx.fiber.dispose();
}));

test('mailbox carries exact native-bound envelope and deletes only owned files after success', async () => fixture(async root => {
  const mailbox = createMailbox({root,timeoutMs:500,pollMs:2});
  const server = respond(root, request => ({id:request.id,ok:true,result:{ok:true,changes:[]}}));
  assert.deepEqual(await mailbox.call('list_changes', {}), {ok:true,changes:[]});
  const request = await server;
  assert.deepEqual(Object.keys(request).sort(), ['args','binding','id','method']);
  assert.equal(request.binding, binding);
  assert.match(request.id, /^[0-9a-f-]{36}$/);
  assert.deepEqual(await readdir(join(root,'request')), []);
  assert.deepEqual(await readdir(join(root,'response')), []);
}));

test('mailbox rejects wrong response IDs, native failures, malformed outputs and timeouts', async () => fixture(async root => {
  for (const reply of [r=>({id:'wrong',ok:true,result:{}}),r=>({id:r.id,ok:false,error:'authorization_expired'}),r=>({id:r.id,ok:true,result:'unexpected'})]) {
    const server = respond(root, reply);
    await assert.rejects(createMailbox({root,timeoutMs:500,pollMs:2}).call('status',{}));
    await server;
  }
  await assert.rejects(createMailbox({root,timeoutMs:10,pollMs:2}).call('status',{}), /MAILBOX_TIMEOUT/);
  assert.deepEqual(await readdir(join(root,'request')), []);
}));

test('mailbox rejects oversized requests, symlink directories and obeys cancellation', async () => fixture(async root => {
  await assert.rejects(createMailbox({root,timeoutMs:50}).call('stage_edit',{path:'engine/node_modules/@deepseek-ai/dsh/lib/bin.js',expected_sha256:'0'.repeat(64),content:'\u0001'.repeat(MAX_CHUNK_BYTES)}),/REQUEST_TOO_LARGE/);
  const aborted = new AbortController(); aborted.abort();
  await assert.rejects(createMailbox({root}).call('status',{}, {signal:aborted.signal}));
  const alias=join(root,'alias'); await symlink(join(root,'request'),alias);
  assert.throws(()=>createMailbox({root:`${root}/../elsewhere`}), /INVALID_MAILBOX_CONFIGURATION/);
  await assert.rejects(createMailbox({root:alias}).call('status',{}), /INVALID_MAILBOX_DIRECTORY/);
}));

test('actual rc.5 registry activates additive authorization remote and publishes atomic fixed request', async () => fixture(async root => {
  process.env.DSH_REPAIR_CONTROL_FILE = join(root,'control-request.json');
  const { Context } = await fromEngine('@deepseek-ai/cordis');
  const { default: TypertRegistry } = await fromEngine('@deepseek-ai/dsh-typert-registry');
  const plugin = await import(pathToFileURL(join(assets,'rescue-entry/index.js')).href);
  const ctx = new Context();
  try {
    await ctx.plugin(TypertRegistry);
    await ctx.plugin(plugin);
    assert.deepEqual(await plugin.requestAuthorization(), {requested:true});
    assert.deepEqual(JSON.parse(await readFile(process.env.DSH_REPAIR_CONTROL_FILE,'utf8')), {action:'authorize'});
    await rm(process.env.DSH_REPAIR_CONTROL_FILE);
    assert.deepEqual(await plugin.requestDisconnect(), {requested:true});
    assert.deepEqual(JSON.parse(await readFile(process.env.DSH_REPAIR_CONTROL_FILE,'utf8')), {action:'disconnect'});
    assert.deepEqual(await plugin.readConnectionState(), {phase:'disconnected',target:null,label:null,message:'',revision:0});
    assert.equal((await readdir(root)).some(x=>x.endsWith('.tmp')),false);
    assert.equal(Object.keys(METHODS).includes('authorize'),false);
  } finally { await ctx.fiber.dispose(); }
}));

test('closed native arguments reject generic commands and traversal, not other packages or extensions', () => {
  assert.equal(MAX_MESSAGE_BYTES, 768*1024);
  assert.equal(MAX_CHUNK_BYTES,128*1024);
  for(const path of ['../bin.js','/data/data/x','engine/node_modules/@deepseek-ai/dsh/lib/../secret.js','engine/node_modules/@deepseek-ai/dsh/lib/a/../../b.js']) assert.throws(()=>validateArgs('read_file',{path}));
  for(const path of ['engine/node_modules/@deepseek-ai/dsh/lib/a.json','app/shared_prefs/设置 配置.xml','app/files/engine/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js','app/databases/cache.db']) assert.doesNotThrow(()=>validateArgs('read_file',{path}));
  assert.throws(()=>validateArgs('bash',{command:'true'}));
  assert.throws(()=>validateArgs('status',{target:'com.dsh.android'}));
  assert.throws(()=>validateArgs('stage_edit',{path:'engine/node_modules/@deepseek-ai/dsh/lib/bin.js',expected_sha256:'0'.repeat(64),content:'a'.repeat(MAX_CHUNK_BYTES+1)}));
  assert.doesNotThrow(()=>validateArgs('stage_edit',{path:'engine/node_modules/@deepseek-ai/dsh/lib/bin.js',expected_sha256:'0'.repeat(64),content:'console.log("candidate");\n'}));
});
