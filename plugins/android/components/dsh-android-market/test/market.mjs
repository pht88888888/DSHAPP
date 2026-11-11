import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import { z } from 'zod';
import { readFixtures, readRealInputs, assertPinned, toContract } from '../../../../../contracts/engine/market-fixtures.mjs';

// L0 executes unmodified official sources with pinned provenance, versions and hashes.
// L1 requires explicit real inputs, checks drift, then exercises both actual contracts.
const fixture = await readFixtures();
const realMode = process.env.DSH_MARKET_REAL_ENGINE === '1';
const inputs = realMode ? await readRealInputs() : fixture.inputs;
if (realMode) {
  for (const label of ['shipped', 'installed']) assertPinned(inputs[label], fixture.inputs[label], fixture.manifest, label);
}
const contractSets = Object.entries(inputs).map(([label, sources]) => ({ label, sources: toContract(sources) }));
const stripModule = (source) => source.replace(/^import .*;?\n/gm, '').replace(/^export (?:\{.*\};?|default .*);?$/gm, '');

function officialGateway(sources) {
  const metadata = vm.createContext({});
  vm.runInContext(stripModule(sources.remoteInfo).replace(/^export /gm, '') + '\nglobalThis.createInfo = createTypertRemoteInfo;', metadata);
  const protocol = vm.createContext({ Error, Service: class {} });
  vm.runInContext(stripModule(sources.protocol) + '\nglobalThis.exports = { RemoteError, isRemoteJsonValue, isRemoteUplinkItem, isTypertOwnedValue, remoteErrorOf };', protocol);
  const context = vm.createContext({ z, createTypertRemoteInfo: metadata.createInfo, ...protocol.exports, AbortController, AbortSignal, Error, Service: class {} });
  vm.runInContext(stripModule(sources.manager).replace('export const TYPERT_REMOTE', 'const TYPERT_REMOTE') + '\nglobalThis.info = TYPERT_REMOTE;', context);
  vm.runInContext(stripModule(sources.gateway) + '\nglobalThis.gateway = ClientRemoteService.prototype;', context);
  return {
    info: context.info,
    async call(method, values, respond) {
      const descriptor = context.info.methods[`pluginManager/${method}`];
      assert.ok(descriptor, `official descriptor missing: ${method}`);
      const connection = { rpc: { call: async (route, endpoint, payload) => {
        assert.equal(route, '/api');
        assert.equal(endpoint, `pluginManager/${method}`);
        descriptor.input({ strict: true }).create().parse(payload.args);
        return respond(payload.args);
      } } };
      const receiver = Object.assign(Object.create(context.gateway), { ownerCtx: { get: () => connection } });
      return receiver.invoke(descriptor, undefined, { active: true, abort: new AbortController() }, {}, values);
    },
  };
}
const official = officialGateway(contractSets[0].sources);

const clientPath = new URL('../lib/client.js', import.meta.url);
const clientSource = await readFile(clientPath, 'utf8');
const PACKAGE = '@example/market-plugin';
const REGISTRY = 'https://registry.example.invalid/';
const plain = (value) => JSON.parse(JSON.stringify(value));
const ok = (value) => ({ ok: true, value });
const plugin = (version = '1.2.3', name = PACKAGE) => ({ name, version, installable: true });
const bundle = (version = '1.0.0', extra = {}) => ({
  name: PACKAGE, version, installed: true, updatable: true, removable: true,
  enabled: false, ...extra,
});
const listed = (versions, extra = {}) => ok({
  status: 'listed', installed: '1.0.0', registry: REGISTRY,
  versions: versions.map((version) => typeof version === 'string' ? { version, bundle: true } : version),
  ...extra,
});
const updated = (result = {}, extra = {}) => ok({
  step: 'install', result: { application: 'applied', version: '1.2.3', ...result }, ...extra,
});

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function store(initial) {
  let snapshot = initial;
  const listeners = new Set();
  return {
    getSnapshot: () => snapshot,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    set(value) { snapshot = value; for (const listener of listeners) listener(); },
    get subscribers() { return listeners.size; },
  };
}

// One mounted component, with state/ref identity and effect cleanup preserved across renders.
// Event handlers only come from the element tree returned by the real registered section.
function hooks() {
  const cells = [];
  let cursor = 0;
  let dirty = false;
  let renderSection;
  let tree;
  let pendingEffects = [];
  const sameDeps = (a, b) => a && b && a.length === b.length && a.every((value, i) => Object.is(value, b[i]));
  const React = {
    createElement(type, props, ...children) { return { type, props: { ...props, children } }; },
    useState(initial) {
      const index = cursor++;
      if (!cells[index]) cells[index] = { value: typeof initial === 'function' ? initial() : initial };
      const cell = cells[index];
      return [cell.value, (next) => {
        const value = typeof next === 'function' ? next(cell.value) : next;
        if (!Object.is(value, cell.value)) { cell.value = value; dirty = true; }
      }];
    },
    useRef(initial) {
      const index = cursor++;
      if (!cells[index]) cells[index] = { current: initial };
      return cells[index];
    },
    useCallback(callback, deps) {
      const index = cursor++;
      if (!cells[index] || !sameDeps(cells[index].deps, deps)) cells[index] = { callback, deps };
      return cells[index].callback;
    },
    useEffect(effect, deps) {
      const index = cursor++;
      const previous = cells[index];
      if (!previous || !sameDeps(previous.deps, deps)) {
        cells[index] = { deps, cleanup: previous?.cleanup };
        pendingEffects.push(() => {
          cells[index].cleanup?.();
          cells[index].cleanup = effect();
        });
      }
    },
  };
  function render() {
    cursor = 0;
    dirty = false;
    tree = renderSection({});
    const effects = pendingEffects;
    pendingEffects = [];
    for (const effect of effects) effect();
  }
  return {
    React,
    mount(section) { renderSection = section; render(); },
    get tree() { return tree; },
    async flush() {
      let quietTurns = 0;
      for (let turn = 0; turn < 30; turn++) {
        if (dirty) { render(); quietTurns = 0; }
        // Drain promise chains without timers, network, or real React scheduling.
        await new Promise((resolve) => setImmediate(resolve));
        if (!dirty && ++quietTurns === 2) return;
      }
      assert.fail('market section did not settle within 30 render turns');
    },
    unmount() { for (const cell of cells) cell?.cleanup?.(); },
  };
}

function nodes(tree, predicate) {
  if (tree == null || typeof tree !== 'object') return [];
  if (Array.isArray(tree)) return tree.flatMap((child) => nodes(child, predicate));
  return [...(predicate(tree) ? [tree] : []), ...nodes(tree.props?.children, predicate)];
}
function text(tree) {
  if (tree == null || typeof tree === 'boolean') return '';
  if (typeof tree !== 'object') return String(tree);
  if (Array.isArray(tree)) return tree.map(text).join(' ');
  return text(tree.props?.children);
}
function button(tree, label) {
  const matches = nodes(tree, (node) => node.type === 'button' &&
    (label instanceof RegExp ? label.test(text(node)) : text(node) === label));
  assert.equal(matches.length, 1, `expected one button ${label}; page: ${text(tree)}`);
  return matches[0];
}

async function market(options = {}) {
  const renderer = hooks();
  let inventory = options.bundles ?? [];
  let catalog = options.plugins ?? [plugin()];
  const calls = { listBundles: [], listVersions: [], installBundle: [], updateBundle: [], removeBundle: [], fetch: [] };
  const state = store({ syncing: false, failures: [] });
  const releaseChange = store(false);
  const modules = {
    entries: { state, releaseChange },
    manifest: { modules: options.modules ?? [{ id: PACKAGE, rev: 'before' }] },
  };
  const loaderEntries = [{ options: { name: PACKAGE }, fiber: { state: 2 } }];
  const environment = {
    setBundles(value) { inventory = value; },
    setCatalog(value) { catalog = value; },
    modules, state, releaseChange, loaderEntries,
  };
  const manager = {};
  for (const method of ['listBundles', 'listVersions', 'installBundle', 'updateBundle', 'removeBundle']) {
    manager[method] = (...args) => {
      calls[method].push(plain(args));
      return official.call(method, args, () => {
        if (options[method]) return options[method](...args, environment);
        if (method === 'listBundles') return ok(inventory);
        if (method === 'listVersions') return listed(['1.2.3']);
        assert.fail(`unexpected ${method}(${JSON.stringify(args)})`);
      });
    };
  }
  let exports;
  let section;
  const locales = {};
  const warnings = [];
  const scope = {
    remote: { pluginManager: manager },
    locale: {
      bind(namespace) { return (key) => locales[namespace].zh[key]; },
      register(namespace, values) { locales[namespace] = values; return () => {}; },
    },
    effect(effect) { effect(); },
    slots: {
      inject(name, register) { assert.equal(name, 'settings.section'); register(); },
      register(config, component) {
        assert.equal(config.id, 'dsh-android-market');
        assert.equal(config.name, 'settings.section');
        section = component;
      },
    },
    get(name) {
      if (name === 'modules') return modules;
      if (name === 'loader') return { entries: () => loaderEntries };
      assert.fail(`unexpected scope.get(${name})`);
    },
  };
  const window = {
    __ModuleLoader__: {
      load(definition) {
        assert.equal(definition.id, 'dsh-android-market');
        exports = definition.factory((name) => { assert.equal(name, 'react'); return renderer.React; });
      },
    },
    fetch(url, init) {
      calls.fetch.push([url, plain(init)]);
      const plugins = plain(catalog);
      return Promise.resolve({ ok: true, json: async () => ({
        source: 'live', plugins,
        counts: { featured: plugins.filter((item) => item.featured).length, community: plugins.filter((item) => !item.featured).length },
      }) });
    },
    confirm: () => true,
  };
  vm.runInNewContext(clientSource, { window, console: { warn: (...args) => warnings.push(args) } }, { filename: clientPath.pathname });
  assert.equal(typeof exports?.apply, 'function');
  exports.apply({
    inject(dependencies, register) {
      assert.deepEqual(plain(dependencies), ['slots', 'locale', 'remote', 'remote.pluginManager']);
      register(scope);
    },
    get remote() { assert.fail('remote must be accessed through injected scope'); },
  });
  assert.deepEqual(warnings, []);
  assert.equal(typeof section, 'function', 'exports.apply must register a real settings section');
  renderer.mount(section);
  await renderer.flush();
  return {
    ...environment, calls, renderer,
    get tree() { return renderer.tree; },
    get page() { return text(renderer.tree); },
    button(label) { return button(renderer.tree, label); },
    async click(label) {
      const target = button(renderer.tree, label);
      assert.ok(!target.props.disabled, `button ${label} is disabled`);
      target.props.onClick();
      await renderer.flush();
    },
  };
}

for (const { label, sources } of contractSets) test(`${realMode ? 'real' : 'pinned'} ${label} official Gateway enforces business slots, optional options and AbortSignal`, async () => {
    const gateway = officialGateway(sources);
    const descriptor = gateway.info.methods['pluginManager/listVersions'];
    assert.deepEqual(Array.from(descriptor.parameters, (parameter) => parameter.name), ['name', 'options']);
    assert.equal(descriptor.parameters[1].acceptsUndefined, true);
    assert.equal(descriptor.cancellation.parameter, 'signal');
    await assert.rejects(gateway.call('listVersions', [PACKAGE], () => assert.fail('invalid arity reached RPC')), /expected 2 business argument\(s\) plus an optional AbortSignal, got 1/);
    for (const values of [[PACKAGE, {}], [PACKAGE, undefined], [PACKAGE, {}, new AbortController().signal]]) {
      const result = await gateway.call('listVersions', values, (args) => {
        assert.equal(args.name, PACKAGE);
        assert.equal(Object.hasOwn(args, 'signal'), false);
        assert.equal(Object.hasOwn(args, 'options'), values[1] !== undefined);
        return listed(['1.2.3']);
      });
      assert.equal(result.ok, true);
    }
    for (const [method, values] of [['listBundles', []], ['installBundle', [`${PACKAGE}@1.2.3`, { enabled: true, saveExact: true }]], ['updateBundle', [PACKAGE, '1.2.3', { registry: REGISTRY }]], ['removeBundle', [PACKAGE]]]) {
      assert.equal(gateway.info.methods[`pluginManager/${method}`].parameters.length, values.length);
      assert.equal((await gateway.call(method, values, () => ok(null))).ok, true);
    }
});

test('refresh and manual version checks retain the official options slot', async () => {
  const app = await market({ bundles: [bundle()], listVersions: () => listed([]) });
  await app.click('刷新');
  await app.click('检查更新');
  assert.deepEqual(app.calls.listVersions, [[PACKAGE, {}], [PACKAGE, {}], [PACKAGE, {}]]);
  assert.doesNotMatch(app.page, /检查更新失败/);
});

test('removal passes the official name slot and refreshes installed bundles', async () => {
  const app = await market({
    bundles: [bundle()],
    removeBundle(name, env) {
      assert.equal(name, PACKAGE);
      env.setBundles([]);
      return ok({ application: 'applied' });
    },
  });
  await app.click('卸载');
  assert.deepEqual(app.calls.removeBundle, [[PACKAGE]]);
  assert.doesNotMatch(app.page, /当前 v/);
  app.button('安装');
});

test('new package installs its exact displayed version with only saveExact/enabled options', async () => {
  const app = await market({
    installBundle(spec, options, env) {
      env.setBundles([bundle('1.2.3', { enabled: true })]);
      return ok({ application: 'applied', version: '1.2.3' });
    },
  });
  await app.click('安装');
  assert.deepEqual(app.calls.installBundle, [[`${PACKAGE}@1.2.3`, { enabled: true, saveExact: true }]]);
  assert.deepEqual(app.calls.updateBundle, []);
  assert.match(app.page, /当前 v1\.2\.3/);
  assert.match(app.page, /服务端已应用/);
});

test('approval retry keeps the originally selected version after the catalog changes', async () => {
  let attempt = 0;
  const app = await market({
    installBundle(spec, options, env) {
      if (++attempt === 1) return ok({ pendingBuilds: ['native-helper'] });
      env.setBundles([bundle('1.2.3', { enabled: true })]);
      return ok({ application: 'applied', version: '1.2.3' });
    },
  });
  await app.click('安装');
  app.setCatalog([plugin('2.0.0')]);
  await app.click('刷新');
  assert.match(app.page, /v2\.0\.0/);
  await app.click('允许脚本并安装选定版本');
  assert.deepEqual(app.calls.installBundle, [
    [`${PACKAGE}@1.2.3`, { enabled: true, saveExact: true }],
    [`${PACKAGE}@1.2.3`, { enabled: true, saveExact: true, approvedBuilds: ['native-helper'] }],
  ]);
  assert.match(app.page, /服务端已应用/);
});

test('installed updatable bundles select the first compatible stable bundle and delegate enabled preservation', async () => {
  const local = bundle('9.0.0', { name: 'local-plugin', updatable: false });
  let officialEnabled;
  const app = await market({
    plugins: [plugin('99.0.0')], bundles: [bundle(), local],
    listVersions: () => listed([
      { version: '5.0.0', bundle: false },
      { version: '4.0.0', bundle: true, incompatible: { runtimeVersion: '100' } },
      { version: '3.0.0-beta.1', bundle: true, prerelease: true },
      { version: '2.0.0', bundle: true, deprecated: 'obsolete' },
      { version: '1.2.3', bundle: true },
      { version: '1.0.0', bundle: true },
    ]),
    updateBundle(name, version, options, env) {
      assert.deepEqual(plain(options), { registry: REGISTRY });
      assert.equal(Object.hasOwn(options, 'enabled'), false);
      officialEnabled = false;
      env.setBundles([bundle(version, { enabled: officialEnabled }), local]);
      return updated();
    },
  });
  assert.deepEqual(app.calls.listVersions, [[PACKAGE, {}]]);
  assert.match(app.page, /本地或内置版本/);
  assert.match(app.page, /当前 v1\.0\.0/);
  await app.click('更新至 v1.2.3');
  assert.deepEqual(app.calls.updateBundle, [[PACKAGE, '1.2.3', { registry: REGISTRY }]]);
  assert.equal(officialEnabled, false);
  assert.deepEqual(app.calls.installBundle, []);
  assert.match(app.page, /当前 v1\.2\.3/);
  assert.match(app.page, /服务端已应用/);
});

test('installed bundles absent from the catalog remain visible and update through official versions', async () => {
  const app = await market({ plugins: [], bundles: [bundle()] });
  assert.deepEqual(app.calls.listVersions, [[PACKAGE, {}]]);
  assert.match(app.page, /当前 v1\.0\.0/);
  app.button('更新至 v1.2.3');
  assert.equal(nodes(app.tree, (node) => node.type === 'button' && text(node) === '安装').length, 0);
});

for (const application of ['restart-required', 'overridden']) {
  test(`nested update result displays ${application}, restart reason and warnings`, async () => {
    const app = await market({
      bundles: [bundle()],
      updateBundle(name, version, options, env) {
        env.setBundles([bundle(version)]);
        return updated({ application, restartReason: 'fixture restart reason', warnings: ['fixture warning'] });
      },
    });
    await app.click('更新至 v1.2.3');
    assert.match(app.page, application === 'restart-required' ? /需要重启引擎后生效/ : /被更高优先级的配置覆盖/);
    assert.match(app.page, /原因：fixture restart reason/);
    assert.match(app.page, /提示：fixture warning/);
  });
}

test('nested failed update and failed restore both show official diagnostic and pnpm failure kind', async () => {
  const app = await market({
    bundles: [bundle()],
    updateBundle: () => updated({
      application: 'failed', error: { code: 'operation-error', diagnostic: 'ENOSPC fixture' },
      packageResult: { kind: 'disk-full' },
    }, { restore: {
      application: 'failed', version: '1.0.0', error: { code: 'operation-error', diagnostic: 'EACCES fixture' },
      packageResult: { kind: 'permission' },
    } }),
  });
  await app.click('更新至 v1.2.3');
  assert.match(app.page, /操作失败：设备存储空间不足/);
  assert.match(app.page, /ENOSPC fixture/);
  assert.match(app.page, /旧版恢复结果：v1\.0\.0 · 文件权限被拒/);
  assert.match(app.page, /EACCES fixture/);
  assert.doesNotMatch(app.page, /服务端已应用/);
});

test('nested cancelled update exposes cancellation and restore result', async () => {
  const app = await market({
    bundles: [bundle()],
    updateBundle: () => updated({ application: 'cancelled' }, {
      restore: { application: 'failed', version: '1.0.0', error: { code: 'not-bundle' } },
    }),
  });
  await app.click('更新至 v1.2.3');
  assert.match(app.page, /操作已取消/);
  assert.match(app.page, /旧版恢复结果：v1\.0\.0 · 这个包不是 DSH 插件/);
  assert.doesNotMatch(app.page, /服务端已应用/);
});

test('update pending builds display error and restore without offering direct install retry', async () => {
  const app = await market({
    bundles: [bundle()],
    updateBundle: () => updated({
      application: 'failed', pendingBuilds: ['native-helper'],
      error: { code: 'operation-error' }, packageResult: { kind: 'build-blocked' },
    }, { restore: { application: 'applied', version: '1.0.0' } }),
  });
  await app.click('更新至 v1.2.3');
  assert.match(app.page, /依赖脚本被拦下，需要你批准/);
  assert.match(app.page, /native-helper/);
  assert.match(app.page, /旧版恢复结果：v1\.0\.0 · applied/);
  assert.equal(nodes(app.tree, (node) => node.type === 'button' && /允许脚本|^安装$/.test(text(node))).length, 0);
  assert.deepEqual(app.calls.installBundle, []);
  assert.deepEqual(app.calls.updateBundle, [[PACKAGE, '1.2.3', { registry: REGISTRY }]]);
  assert.doesNotMatch(app.page, /服务端已应用/);
});

test('failed official update envelope is not reported as applied', async () => {
  const app = await market({
    bundles: [bundle()],
    updateBundle: () => ({ ok: false, error: { code: 'gateway/internal', message: 'remote fixture failure', details: {} } }),
  });
  await app.click('更新至 v1.2.3');
  assert.match(app.page, /gateway\/internal/);
  assert.doesNotMatch(app.page, /服务端已应用/);
});

for (const operation of ['install', 'update']) {
  for (const mismatched of ['inventory', 'result']) {
    test(`${operation} rejects a mismatched ${mismatched} version after an apparently successful response`, async () => {
      const perform = (name, versionOrOptions, optionsOrEnv, maybeEnv) => {
        const env = maybeEnv ?? optionsOrEnv;
        env.setBundles([bundle(mismatched === 'inventory' ? '1.1.0' : '1.2.3')]);
        const result = { application: 'applied', version: mismatched === 'result' ? '1.1.0' : '1.2.3' };
        return operation === 'update' ? updated(result) : ok(result);
      };
      const app = await market({
        bundles: operation === 'update' ? [bundle()] : [],
        [operation === 'update' ? 'updateBundle' : 'installBundle']: perform,
      });
      await app.click(operation === 'update' ? '更新至 v1.2.3' : '安装');
      assert.match(app.page, /目标 v1\.2\.3，实际 v/);
      assert.match(app.page, /实际 v1\.1\.0/);
      assert.doesNotMatch(app.page, /服务端已应用/);
    });
  }
}

for (const failure of ['reply', 'rejection']) {
  test(`initial installed query ${failure} disables install and even a direct handler call cannot install`, async () => {
    const app = await market({
      listBundles: () => failure === 'reply'
        ? { ok: false, error: { code: 'operation-error' } }
        : Promise.reject(new Error('offline fixture')),
    });
    const install = app.button('安装');
    assert.equal(install.props.disabled, true);
    assert.match(app.page, /无法读取已安装状态，暂不能安装或更新/);
    install.props.onClick();
    await app.renderer.flush();
    assert.deepEqual(app.calls.installBundle, []);
    assert.deepEqual(app.calls.updateBundle, []);
  });
}

test('a failed refresh preserves known installed bundles and disables updates', async () => {
  let reads = 0;
  const app = await market({
    listBundles: () => ++reads === 1 ? ok([bundle()]) : Promise.reject(new Error('offline fixture')),
  });
  await app.click('刷新');
  assert.match(app.page, /当前 v1\.0\.0/);
  assert.match(app.page, /无法读取已安装状态/);
  assert.equal(app.button('更新至 v1.2.3').props.disabled, true);
  assert.equal(nodes(app.tree, (node) => node.type === 'button' && text(node) === '安装').length, 0);
  assert.deepEqual(app.calls.installBundle, []);
});

for (const operation of ['install', 'update']) {
  test(`same-package ${operation} double click invokes the manager only once before rerender`, async () => {
    const pending = deferred();
    let env;
    const app = await market({
      bundles: operation === 'update' ? [bundle()] : [],
      [operation === 'update' ? 'updateBundle' : 'installBundle']: (...args) => {
        env = args.at(-1);
        return pending.promise;
      },
    });
    const action = app.button(operation === 'update' ? '更新至 v1.2.3' : '安装');
    action.props.onClick();
    action.props.onClick();
    await app.renderer.flush();
    const calls = app.calls[operation === 'update' ? 'updateBundle' : 'installBundle'];
    assert.equal(calls.length, 1);
    assert.match(app.page, operation === 'update' ? /更新中…/ : /安装中…/);
    env.setBundles([bundle('1.2.3')]);
    pending.resolve(operation === 'update' ? updated() : ok({ application: 'applied', version: '1.2.3' }));
    await app.renderer.flush();
    assert.match(app.page, /服务端已应用/);
  });
}

test('client failure and release subscriptions update the rendered page and clean up on unmount', async () => {
  const app = await market({
    installBundle(spec, options, env) {
      env.setBundles([bundle('1.2.3')]);
      return ok({ application: 'applied', version: '1.2.3' });
    },
  });
  assert.equal(app.state.subscribers, 1);
  assert.equal(app.releaseChange.subscribers, 1);
  await app.click('安装');
  assert.match(app.page, /页面插件尚未确认同步/);
  app.state.set({ syncing: false, failures: [{ id: PACKAGE, message: 'fixture client module failure' }] });
  await app.renderer.flush();
  assert.match(app.page, /页面插件加载失败：fixture client module failure/);
  app.state.set({ syncing: false, failures: [] });
  app.modules.manifest.modules[0].rev = 'after';
  await app.renderer.flush();
  assert.match(app.page, /页面插件已热更新/);
  app.releaseChange.set(true);
  await app.renderer.flush();
  assert.match(app.page, /当前页面需要刷新后加载新版/);
  assert.doesNotMatch(app.page, /页面插件已热更新/);
  app.renderer.unmount();
  assert.equal(app.state.subscribers, 0);
  assert.equal(app.releaseChange.subscribers, 0);
});
