import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolveRepositoryRoot, requireKotlinTestInput } from '../../../../../../../../../tools/kotlin-test-runtime.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.join(directory, 'BuiltinPluginBootstrap.kt'), 'utf8');
const script = source.match(/val script = """\n([\s\S]*?)\n"""\.trimIndent\(\)/)[1];
// L1: run against an explicitly supplied extracted engine package only.
const enginePackage = requireKotlinTestInput('DSH_ENGINE_PACKAGE');
const engineDir = path.resolve(path.dirname(enginePackage), '../../..');
const engineRequire = createRequire(enginePackage);
const { PROFILE_TEMPLATES } = engineRequire('@deepseek-ai/dsh-official-bundles');
const yaml = engineRequire('js-yaml');
const { resolveBundleDir, loadProfileDirectory, composeEntries } = engineRequire('@deepseek-ai/dsh-app-boot');
const { entryListSchema } = engineRequire('@deepseek-ai/cordis-plugin-include');
const repository = resolveRepositoryRoot(import.meta.url);
const legacySources = [
  ['mobile-ui', 'plugins/android/components/dsh-android-mobile'],
  ['mobile-settings', 'plugins/android/components/dsh-android-mobile-settings'],
  ['host-bridge', 'plugins/android/components/dsh-android-host-bridge'],
  ['balance', 'plugins/android/components/dsh-android-balance'],
  ['browser-pane', 'plugins/android/components/dsh-android-browser-pane'],
  ['market', 'plugins/android/components/dsh-android-market'],
  ['shell-tool', 'plugins/android/components/dsh-android-shell'],
  ['root-tool', 'plugins/android/components/dsh-android-root'],
  ['bubble-tool', 'plugins/android/components/dsh-android-bubble'],
  ['statusbar-tool', 'plugins/android/components/dsh-android-statusbar'],
  ['uiagent-tool', 'plugins/android/components/dsh-android-uiagent'],
  ['browser-tool', 'plugins/android/components/dsh-android-browser'],
  ['logs-tool', 'plugins/android/components/dsh-android-logs'],
  ['pet-bridge', 'plugins/android/components/dsh-android-pet'],
];
const legacyComponents = legacySources.map(([id, source]) => {
  const packageName = JSON.parse(fs.readFileSync(path.join(repository, source, 'package.json'), 'utf8')).name;
  return { id, packageName, entryId: packageName, legacyPatch: id + '.patch.yml', preferenceId: id === 'uiagent-tool' ? 'ui-tool' : id, enabled: true };
});
const androidPatch = legacySources.flatMap(([, source]) => yaml.load(fs.readFileSync(path.join(repository, source, 'cordis.patch.yml'), 'utf8'), { schema: entryListSchema }));

function fixture(t, android = false) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-builtin-test-'));
  t.after(() => { assert.ok(root.startsWith(path.join(os.tmpdir(), 'dsh-builtin-test-'))); fs.rmSync(root, { recursive: true, force: true }); });
  const home = path.join(root, 'home');
  const profileDir = path.join(home, 'profiles/web');
  const manifestPath = path.join(profileDir, 'package.json');
  const output = path.join(root, 'plan.json');
  const plugin = android
    ? { id: 'android-bundle', packageName: 'dsh-android-bundle', entryId: 'dsh-android-bundle', enabled: true }
    : { id: 'shell-tool', packageName: 'dsh-android-shell', entryId: 'dsh-android-shell', legacyPatch: 'shell-tool.patch.yml', enabled: true };
  const plugins = [plugin];
  const legacy = android ? structuredClone(legacyComponents) : [];
  function run(phase, overrides = {}) {
    const request = path.join(root, 'request.json');
    fs.writeFileSync(request, JSON.stringify({ phase, engineDir, home, profile: 'web', output, plugins, legacyComponents: legacy, ...overrides }));
    const result = spawnSync(process.execPath, ['-e', script, request], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    return phase === 'plan' ? JSON.parse(fs.readFileSync(output, 'utf8')) : undefined;
  }
  function manifest(change) {
    const value = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    if (change) { change(value); fs.writeFileSync(manifestPath, JSON.stringify(value)); }
    return value;
  }
  function install(version = '1.1.1') {
    const pkgDir = path.join(home, 'node_modules', plugin.packageName);
    fs.mkdirSync(pkgDir, { recursive: true });
    // APK installer 整目录替换，新资产不会继承旧包的源 patch 缓存。
    const cachedSource = path.resolve(pkgDir, '.builtin-source.patch.yml');
    assert.equal(cachedSource, path.join(home, 'node_modules', plugin.packageName, '.builtin-source.patch.yml'));
    fs.rmSync(cachedSource, { force: true });
    fs.writeFileSync(path.join(pkgDir, 'package.json'), JSON.stringify({ name: plugin.packageName, version, dsh: { bundle: { patch: './cordis.patch.yml' }, android: { builtinId: plugin.id } } }));
    fs.writeFileSync(path.join(pkgDir, 'cordis.patch.yml'), android ? yaml.dump(androidPatch, { schema: entryListSchema }) : '- insert:\n    - id: dsh-android-shell\n      name: dsh-android-shell\n');
    return pkgDir;
  }
  run('init');
  function entries() {
    const profile = loadProfileDirectory('test', profileDir, enginePackage);
    assert.deepEqual(profile.skippedBundles, []);
    return composeEntries([...profile.layers.map(layer => layer.patches), profile.patches]);
  }
  function seedLegacy() {
    manifest(value => {
      value.dsh.android = { builtinPlugins: Object.fromEntries(legacy.map(item => [item.id, item.packageName])) };
      for (const item of legacy) {
        value.dependencies[item.packageName] = 'link:' + path.join(home, 'node_modules', item.packageName);
        value.dsh.profile.bundles.push(item.packageName);
      }
    });
  }
  return { home, profileDir, manifestPath, plugin, plugins, legacy, run, manifest, install, entries, seedLegacy };
}

test('首次使用解包引擎的真实web模板并立即登记，单一bundle按稳定id解析', t => {
  const f = fixture(t);
  assert.deepEqual(f.manifest().dsh.profile.bundles, PROFILE_TEMPLATES.web.bundles);
  assert.ok(fs.existsSync(path.join(f.profileDir, 'pnpm-workspace.yaml')));
  assert.equal(fs.existsSync(path.join(f.profileDir, 'cordis.patch.yml')), false);
  assert.equal(f.run('plan').length, 1);
  const pkgDir = f.install();
  f.run('register');
  assert.equal(f.manifest().dependencies[f.plugin.packageName], 'link:' + pkgDir);
  assert.deepEqual(f.manifest().dsh.profile.bundles, [...PROFILE_TEMPLATES.web.bundles, f.plugin.packageName]);
  assert.equal(resolveBundleDir('dsh', f.plugin.packageName, enginePackage, f.profileDir), pkgDir);
  assert.equal(yaml.load(fs.readFileSync(path.join(pkgDir, 'cordis.patch.yml'), 'utf8'))[0].insert[0].id, f.plugin.entryId);
});

test('关闭后重启与APK登记不复活，卸载后不重新安装，文件marker丢失仍持久', t => {
  const f = fixture(t);
  f.install(); f.run('register');
  f.manifest(value => { value.dsh.profile.bundles = value.dsh.profile.bundles.filter(name => name !== f.plugin.packageName); });
  fs.unlinkSync(path.join(f.home, '.builtin-plugins/shell-tool.json'));
  f.run('register');
  assert.ok(!f.manifest().dsh.profile.bundles.includes(f.plugin.packageName));
  f.manifest(value => { delete value.dependencies[f.plugin.packageName]; });
  assert.deepEqual(f.run('plan'), []);
  f.run('register');
  assert.equal(f.manifest().dependencies[f.plugin.packageName], undefined);
});

test('旧core/preset一次性marker及disabled bundle不被补依赖或重新开启', t => {
  const f = fixture(t);
  f.plugin.legacyMarker = '.core_memory_bundle_registered';
  fs.writeFileSync(path.join(f.home, f.plugin.legacyMarker), '1');
  assert.deepEqual(f.run('plan'), []);
  f.run('register');
  assert.equal(f.manifest().dependencies[f.plugin.packageName], undefined);
  const pkgDir = f.install();
  f.manifest(value => { value.dependencies[f.plugin.packageName] = 'link:' + pkgDir; });
  f.run('register');
  assert.ok(!f.manifest().dsh.profile.bundles.includes(f.plugin.packageName));
});

test('历史原生开关false首次登记保留已安装身份但不启用', t => {
  const f = fixture(t); f.plugin.enabled = false;
  const pkgDir = f.install(); f.run('register');
  assert.equal(f.manifest().dependencies[f.plugin.packageName], 'link:' + pkgDir);
  assert.ok(!f.manifest().dsh.profile.bundles.includes(f.plugin.packageName));
});

test('市场registry/file/git spec优先且市场卸载后不复活，profile市场目录优先于出厂', t => {
  const f = fixture(t); f.install();
  for (const spec of ['2.3.4', '^2.0.0', 'file:/user/package', 'git+https://example.com/pkg']) {
    f.manifest(value => { value.dependencies[f.plugin.packageName] = spec; });
    assert.deepEqual(f.run('plan'), []);
    f.run('register');
    assert.equal(f.manifest().dependencies[f.plugin.packageName], spec);
  }
  const market = path.join(f.profileDir, 'node_modules', f.plugin.packageName);
  fs.mkdirSync(market, { recursive: true });
  fs.writeFileSync(path.join(market, 'package.json'), JSON.stringify({ name: f.plugin.packageName, version: '2.3.4' }));
  assert.equal(resolveBundleDir('dsh', f.plugin.packageName, enginePackage, f.profileDir), market);
  f.manifest(value => { delete value.dependencies[f.plugin.packageName]; });
  assert.deepEqual(f.run('plan'), []);
});

test('旧overlay迁移保留稳定id与shell/root runner配置，已有用户config优先', t => {
  const f = fixture(t); f.install();
  fs.writeFileSync(path.join(f.home, f.plugin.legacyPatch), '- insert:\n    - id: dsh-android-shell\n      name: file:///old/shell/lib/index.js\n      config:\n        runner: /custom/shell-runner\n        rootRunner: /custom/root-runner\n      disabled: false\n');
  fs.writeFileSync(path.join(f.profileDir, 'cordis.patch.yml'), '- id: unrelated\n  disabled: !!js process.env.DISABLED\n');
  f.run('register');
  const text = fs.readFileSync(path.join(f.profileDir, 'cordis.patch.yml'), 'utf8');
  assert.match(text, /runner: \/custom\/shell-runner/);
  assert.match(text, /rootRunner: \/custom\/root-runner/);
  assert.match(text, /id: dsh-android-shell/);
  assert.match(text, /!!js/);
  assert.ok(!fs.existsSync(path.join(f.home, f.plugin.legacyPatch)));
  const g = fixture(t); g.install();
  fs.writeFileSync(path.join(g.home, g.plugin.legacyPatch), '- insert:\n    - id: dsh-android-shell\n      config: {runner: /old}\n');
  fs.writeFileSync(path.join(g.profileDir, 'cordis.patch.yml'), '- id: dsh-android-shell\n  config: {runner: /user-selected}\n');
  g.run('register');
  assert.equal(yaml.load(fs.readFileSync(path.join(g.profileDir, 'cordis.patch.yml'), 'utf8'))[0].config.runner, '/user-selected');
});

test('安装半成品不登记不落marker，重试成功才提交身份', t => {
  const f = fixture(t);
  const pkgDir = f.install();
  fs.unlinkSync(path.join(pkgDir, 'cordis.patch.yml'));
  f.run('register');
  assert.equal(f.manifest().dependencies[f.plugin.packageName], undefined);
  assert.equal(fs.existsSync(path.join(f.home, '.builtin-plugins/shell-tool.json')), false);
  f.install(); f.run('register');
  assert.equal(f.manifest().dependencies[f.plugin.packageName], 'link:' + pkgDir);
});

test('已有profile与用户patch原字节保留，救援模板独立于损坏web', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.profileDir, 'cordis.patch.yml'), '# custom\n[]\n');
  const before = fs.readFileSync(f.manifestPath, 'utf8');
  f.run('init');
  assert.equal(fs.readFileSync(f.manifestPath, 'utf8'), before);
  assert.equal(fs.readFileSync(path.join(f.profileDir, 'cordis.patch.yml'), 'utf8'), '# custom\n[]\n');
  fs.writeFileSync(f.manifestPath, '{broken');
  f.run('init', { profile: 'rescue' });
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(f.home, 'profiles/rescue/package.json'), 'utf8')).dsh.profile.bundles, PROFILE_TEMPLATES.web.bundles);
  assert.equal(fs.readFileSync(f.manifestPath, 'utf8'), '{broken');
});

test('Android fresh仅登记一个顶层包，官方组合得到十四个稳定组件入口', t => {
  const f = fixture(t, true);
  assert.equal(f.run('plan').length, 1);
  f.install(); f.run('register');
  assert.deepEqual(Object.keys(f.manifest().dependencies), [f.plugin.packageName]);
  assert.deepEqual(f.manifest().dsh.profile.bundles, [...PROFILE_TEMPLATES.web.bundles, f.plugin.packageName]);
  const ids = new Set(f.entries().map(row => row.id));
  assert.equal(f.legacy.length, 14);
  for (const item of f.legacy) assert.ok(ids.has(item.entryId), item.entryId);
  assert.equal(f.entries().filter(row => f.legacy.some(item => item.entryId === row.id)).length, 14);
});

test('十四本地出厂link合并，逐组件保留关闭、config、旧卸载及原生preference', t => {
  const f = fixture(t, true); f.seedLegacy();
  const shell = f.legacy.find(item => item.id === 'shell-tool');
  const root = f.legacy.find(item => item.id === 'root-tool');
  const bubble = f.legacy.find(item => item.id === 'bubble-tool');
  const ui = f.legacy.find(item => item.id === 'uiagent-tool');
  ui.enabled = false;
  f.manifest(value => {
    value.dsh.profile.bundles = value.dsh.profile.bundles.filter(name => ![shell.packageName, bubble.packageName].includes(name));
    delete value.dependencies[bubble.packageName];
  });
  fs.writeFileSync(path.join(f.home, root.legacyPatch), yaml.dump([{ insert: [{ id: root.entryId, config: { runner: '/root/custom' }, disabled: true }] }]));
  fs.writeFileSync(path.join(f.profileDir, 'cordis.patch.yml'), yaml.dump([{ id: root.entryId, config: { runner: '/user/selected' } }]));
  f.install(); f.run('register');
  assert.deepEqual(Object.keys(f.manifest().dependencies), [f.plugin.packageName]);
  assert.deepEqual(f.manifest().dsh.profile.bundles, [...PROFILE_TEMPLATES.web.bundles, f.plugin.packageName]);
  const entries = f.entries();
  for (const item of [shell, root, bubble, ui]) assert.equal(entries.find(row => row.id === item.entryId).disabled, true, item.id);
  assert.equal(entries.find(row => row.id === root.entryId).config.runner, '/user/selected');
  assert.ok(!fs.existsSync(path.join(f.home, root.legacyPatch)));
  f.run('register');
  assert.equal(f.entries().find(row => row.id === bubble.entryId).disabled, true);
  assert.notEqual(f.entries().find(row => row.id === f.legacy[0].entryId).disabled, true);
});

test('迁移损坏root旧overlay只跳过该文件，其它组件配置及卸载继续迁移', t => {
  const f = fixture(t, true); f.seedLegacy();
  const root = f.legacy.find(item => item.id === 'root-tool');
  const shell = f.legacy.find(item => item.id === 'shell-tool');
  fs.writeFileSync(path.join(f.home, root.legacyPatch), '- insert:\n    - id: dsh-android-root\n      disabled: !!js !process.env.DSH_ROOT_AVAILABLE\n');
  fs.writeFileSync(path.join(f.home, shell.legacyPatch), yaml.dump([{ insert: [{ id: shell.entryId, config: { runner: '/custom/runner' } }] }]));
  f.install(); f.run('register');
  assert.deepEqual(Object.keys(f.manifest().dependencies), [f.plugin.packageName]);
  assert.equal(f.entries().find(row => row.id === shell.entryId).config.runner, '/custom/runner');
  assert.ok(fs.existsSync(path.join(f.home, root.legacyPatch)));
});

test('旧组件registry/file/git和市场启用选择保留，聚合不生成重复入口，市场卸载不复活', t => {
  const f = fixture(t, true); f.seedLegacy();
  const choices = f.legacy.slice(0, 3);
  const specs = ['2.3.4', 'file:/user/package', 'git+https://example.com/pkg'];
  for (const [index, item] of choices.entries()) {
    const target = path.join(f.profileDir, 'node_modules', item.packageName);
    fs.mkdirSync(target, { recursive: true });
    fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name: item.packageName, version: '2.3.4', dsh: { bundle: { patch: './cordis.patch.yml' } } }));
    fs.writeFileSync(path.join(target, 'cordis.patch.yml'), yaml.dump([{ insert: [{ id: item.entryId, name: item.packageName, config: { market: true } }] }]));
    f.manifest(value => {
      value.dependencies[item.packageName] = specs[index];
      if (index === 1) value.dsh.profile.bundles = value.dsh.profile.bundles.filter(name => name !== item.packageName);
    });
  }
  f.install(); f.run('register');
  for (const [index, item] of choices.entries()) {
    assert.equal(f.manifest().dependencies[item.packageName], specs[index]);
    assert.equal(f.manifest().dsh.profile.bundles.includes(item.packageName), index !== 1);
    const rows = f.entries().filter(row => row.id === item.entryId);
    assert.equal(rows.length, index === 1 ? 0 : 1);
    if (rows.length) assert.equal(rows[0].config.market, true);
  }
  f.manifest(value => {
    delete value.dependencies[choices[0].packageName];
    value.dsh.profile.bundles = value.dsh.profile.bundles.filter(name => name !== choices[0].packageName);
  });
  f.run('register');
  assert.equal(f.entries().find(row => row.id === choices[0].entryId).disabled, true);
});

test('聚合包关闭和卸载跨重启及APK内容升级保持，市场聚合spec也不替换', t => {
  const f = fixture(t, true); f.install(); f.run('register');
  f.manifest(value => { value.dsh.profile.bundles = value.dsh.profile.bundles.filter(name => name !== f.plugin.packageName); });
  const upgraded = f.install('1.2.0'); f.run('register');
  assert.equal(JSON.parse(fs.readFileSync(path.join(upgraded, 'package.json'), 'utf8')).version, '1.2.0');
  assert.equal(f.manifest().dsh.profile.bundles.includes(f.plugin.packageName), false);
  fs.unlinkSync(path.join(f.home, '.builtin-plugins/android-bundle.json'));
  f.manifest(value => { delete value.dependencies[f.plugin.packageName]; });
  f.run('register');
  assert.deepEqual(f.run('plan'), []);
  assert.equal(f.manifest().dependencies[f.plugin.packageName], undefined);
  const g = fixture(t, true); g.install(); g.seedLegacy();
  g.manifest(value => { value.dependencies[g.plugin.packageName] = '3.4.5'; });
  assert.deepEqual(g.run('plan'), []); g.run('register');
  assert.equal(g.manifest().dependencies[g.plugin.packageName], '3.4.5');
  assert.deepEqual(Object.keys(g.manifest().dependencies), [g.plugin.packageName]);
});
