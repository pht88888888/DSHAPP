import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.join(directory, 'BuiltinPluginBootstrap.kt'), 'utf8');
const script = source.match(/val script = """\n([\s\S]*?)\n"""\.trimIndent\(\)/)[1];
const enginePackage = process.env.DSH_ENGINE_PACKAGE || createRequire(import.meta.url).resolve('@deepseek-ai/dsh/package.json', { paths: [process.env.DSH_CHECKOUT || '/root/.nvm/versions/node/v24.22.2/lib/node_modules'] });
const engineDir = path.resolve(path.dirname(enginePackage), '../../..');
const engineRequire = createRequire(enginePackage);
const { PROFILE_TEMPLATES } = engineRequire('@deepseek-ai/dsh-official-bundles');
const yaml = engineRequire('js-yaml');
const { resolveBundleDir } = engineRequire('@deepseek-ai/dsh-app-boot');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-builtin-test-'));
  t.after(() => { assert.ok(root.startsWith(path.join(os.tmpdir(), 'dsh-builtin-test-'))); fs.rmSync(root, { recursive: true, force: true }); });
  const home = path.join(root, 'home');
  const profileDir = path.join(home, 'profiles/web');
  const manifestPath = path.join(profileDir, 'package.json');
  const output = path.join(root, 'plan.json');
  const plugin = { id: 'shell-tool', packageName: 'dsh-android-shell', entryId: 'dsh-android-shell', legacyPatch: 'shell-tool.patch.yml', enabled: true };
  const plugins = [plugin];
  function run(phase, overrides = {}) {
    const request = path.join(root, 'request.json');
    fs.writeFileSync(request, JSON.stringify({ phase, engineDir, home, profile: 'web', output, plugins, ...overrides }));
    const result = spawnSync(process.execPath, ['-e', script, request], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    return phase === 'plan' ? JSON.parse(fs.readFileSync(output, 'utf8')) : undefined;
  }
  function manifest(change) {
    const value = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    if (change) { change(value); fs.writeFileSync(manifestPath, JSON.stringify(value)); }
    return value;
  }
  function install() {
    const pkgDir = path.join(home, 'node_modules', plugin.packageName);
    fs.mkdirSync(pkgDir, { recursive: true });
    fs.writeFileSync(path.join(pkgDir, 'package.json'), JSON.stringify({ name: plugin.packageName, version: '1.1.0', dsh: { bundle: { patch: './cordis.patch.yml' }, android: { builtinId: plugin.id } } }));
    fs.writeFileSync(path.join(pkgDir, 'cordis.patch.yml'), '- insert:\n    - id: dsh-android-shell\n      name: dsh-android-shell\n');
    return pkgDir;
  }
  run('init');
  return { home, profileDir, manifestPath, plugin, plugins, run, manifest, install };
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
