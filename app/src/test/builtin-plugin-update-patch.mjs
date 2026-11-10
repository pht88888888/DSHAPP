import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../..', import.meta.url));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'builtin-update-test-'));
const engine = path.join(tmp, 'engine');
const lib = path.join(engine, 'node_modules/@deepseek-ai/dsh-plugin-manager/lib');
const home = path.join(tmp, 'home');
const name = 'dsh-android-shell';
const pkg = path.join(home, 'node_modules', name);
const profile = path.join(home, 'profiles/web');
const link = `link:${pkg}`;
const run = (command, args, options = {}) => {
  const result = spawnSync(command, args, { encoding: 'utf8', ...options });
  assert.equal(result.status, 0, `${command}: ${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
};
let tests = 0;
const test = async (title, fn) => { await fn(); tests++; console.log(`ok ${tests} - ${title}`); };
try {
  fs.mkdirSync(lib, { recursive: true });
  fs.mkdirSync(pkg, { recursive: true });
  fs.mkdirSync(profile, { recursive: true });
  run('python3', ['-c', `import tarfile,sys,pathlib
out=pathlib.Path(sys.argv[2])
with tarfile.open(sys.argv[1]) as t:
 for m in t:
  if m.isfile() and (m.name.startswith('node_modules/semver/') or m.name in ['node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js','node_modules/@deepseek-ai/dsh-plugin-manager/lib/typert.host.js']):
   p=out/m.name
   p.parent.mkdir(parents=True,exist_ok=True)
   p.write_bytes(t.extractfile(m).read())`, path.join(root, 'app/src/main/assets/engine.bin'), engine]);
  const official = fs.readFileSync(path.join(lib, 'index.js'), 'utf8');
  const host = fs.readFileSync(path.join(lib, 'typert.host.js'), 'utf8');
  assert.match(host, /'updatable': z.boolean\(\)/);
  assert.match(host, /'restore': z\.object\(/);
  const gradleRoots = [process.env.KOTLIN_COMPILER_LIB, '/root/gradle-home/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/lib', '/root/.sdkman/candidates/gradle/9.3.0/lib'].filter(Boolean);
  const compilerLib = gradleRoots.find(dir => fs.existsSync(dir) && fs.readdirSync(dir).some(file => /^kotlin-compiler-embeddable-.*\.jar$/.test(file)));
  assert.ok(compilerLib, 'Set KOTLIN_COMPILER_LIB to the existing Gradle lib directory');
  const jars = fs.readdirSync(compilerLib).filter(file => file.endsWith('.jar')).map(file => path.join(compilerLib, file));
  const stdlib = jars.find(file => /\/kotlin-stdlib-[\d.]+\.jar$/.test(file));
  const harness = path.join(tmp, 'Harness.kt');
  fs.writeFileSync(harness, `package com.dsh.android.engine
import java.io.File
fun main(args: Array<String>) {
 val packages = mutableMapOf("shell-tool" to "${name}")
 if (args.size > 2) packages["balance"] = "dsh-android-balance"
 println(BuiltinPluginUpdatePatch.apply(File(args[0]), File(args[1]), packages) { println(it) })
}`);
  const classes = path.join(tmp, 'classes');
  run('java', ['-cp', jars.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', classes, path.join(root, 'app/src/main/java/com/dsh/android/engine/BuiltinPluginUpdatePatch.kt'), harness]);
  const apply = (targetHome = home, expanded = false) => run('java', ['-cp', [classes, stdlib].join(path.delimiter), 'com.dsh.android.engine.HarnessKt', engine, targetHome, ...(expanded ? ['expanded'] : [])]);
  await test('真实 Kotlin 补丁编译、官方锚点匹配及幂等', () => {
    assert.match(apply(), /true/);
    assert.match(apply(), /false/);
  });
  const patched = fs.readFileSync(path.join(lib, 'index.js'), 'utf8');
  await test('锚点漂移失败且不写入半成品', () => {
    const drift = official.replace('function updateRefusal(bundle, name) {', 'function changedUpdateRefusal(bundle, name) {');
    fs.writeFileSync(path.join(lib, 'index.js'), drift);
    const result = spawnSync('java', ['-cp', [classes, stdlib].join(path.delimiter), 'com.dsh.android.engine.HarnessKt', engine, home], { encoding: 'utf8' });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /expected one official anchor/);
    assert.equal(fs.readFileSync(path.join(lib, 'index.js'), 'utf8'), drift);
    fs.writeFileSync(path.join(lib, 'index.js'), patched);
  });
  await test('APK白名单扩充与DSH_HOME迁移严格校验骨架后原子刷新配置', () => {
    assert.match(apply(home, true), /true/);
    const expanded = fs.readFileSync(path.join(lib, 'index.js'), 'utf8');
    assert.equal(expanded.split('"balance":"dsh-android-balance"').length - 1, 2);
    assert.equal(expanded.replace(/^\/\/ DSH_ANDROID_BUILTIN_UPDATE_V1 .*$/m, '// CONFIG').replace(/^const dshAndroidBuiltinUpdate = .*;$/m, 'const CONFIG;'), patched.replace(/^\/\/ DSH_ANDROID_BUILTIN_UPDATE_V1 .*$/m, '// CONFIG').replace(/^const dshAndroidBuiltinUpdate = .*;$/m, 'const CONFIG;'));
    assert.match(apply(home, true), /false/);
    const relocated = path.join(tmp, 'migrated-home');
    assert.match(apply(relocated, true), /true/);
    const moved = fs.readFileSync(path.join(lib, 'index.js'), 'utf8');
    assert.equal(moved.split(JSON.stringify(relocated)).length - 1, 2);
    assert.match(apply(relocated, true), /false/);
    const damaged = moved.replace('function dshAndroidBuiltinLink(bundle, profile)', 'function changedBuiltinLink(bundle, profile)');
    fs.writeFileSync(path.join(lib, 'index.js'), damaged);
    const result = spawnSync('java', ['-cp', [classes, stdlib].join(path.delimiter), 'com.dsh.android.engine.HarnessKt', engine, home], { encoding: 'utf8' });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /expected one official anchor/);
    assert.equal(fs.readFileSync(path.join(lib, 'index.js'), 'utf8'), damaged);
    assert.equal(fs.readdirSync(lib).some(file => file.startsWith('.builtin-update-')), false);
    fs.writeFileSync(path.join(lib, 'index.js'), moved);
    assert.match(apply(), /true/);
    assert.equal(fs.readFileSync(path.join(lib, 'index.js'), 'utf8'), patched);
  });
  const require = createRequire(path.join(engine, 'test.cjs'));
  const semver = require('semver');
  const writeJson = (filename, value) => fs.writeFileSync(filename, JSON.stringify(value));
  const builtinManifest = () => ({ name, version: '1.0.0', dsh: { bundle: { patch: 'cordis.patch.yml' }, android: { builtinId: 'shell-tool' } } });
  const reset = (enabled = true, spec = link) => {
    writeJson(path.join(pkg, 'package.json'), builtinManifest());
    writeJson(path.join(profile, 'package.json'), { dependencies: { [name]: spec }, dsh: { profile: { bundles: enabled ? [name] : [] } } });
    writeJson(path.join(engine, 'package.json'), { dependencies: {} });
  };
  reset();
  const readManifest = dir => JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
  let resolved = pkg;
  const fluent = new Proxy(() => fluent, { get: () => fluent });
  const sandbox = {
    ...fs, ...path, semver, process, console, URL, Buffer, AbortController, AbortSignal,
    z: fluent, NPM_FILE_SOURCES: [], MAX_TIMER_DELAY_MS: 2147483647,
    TypertRemoteService: class {}, Remote: () => {}, UnsupportedBundlePlatformError: class extends Error {}, 
    OFFICIAL_ON_DEMAND_CATALOG: [], OPTIONAL_BUNDLES: [], PROTECTED_PLUGINS: new Set(),
    readProfileManifest: (_app, dir) => readManifest(dir),
    readProfileVersionExemptions: () => ({}), getDshRuntimeVersion: () => '1.0.0',
    evaluatePluginCompatibility: () => undefined, assertBundlePlatform: () => {},
    resolveBundleDir: () => resolved, readPluginMeta: () => undefined,
    pathToFileURL: url => ({ href: `file://${url}` }),
  };
  const context = vm.createContext(sandbox);
  vm.runInContext(patched.replace(/^import .*;\n/gm, '').replace(/^export .*;$/gm, '') + '\nglobalThis.Manager = PluginManager;', context, { filename: 'official-plugin-manager.js' });
  const proto = context.Manager.prototype;
  let hmr = true;
  const manager = Object.create(proto);
  Object.assign(manager, {
    profile: { dir: profile, installAnchor: path.join(engine, 'package.json'), startedBundles: [] },
    ownerContext: { get: () => hmr ? {} : undefined, emit() {} },
    installedFrom: new Map(), managementBundles: new Set(), versionListLimit: 20, versionLookupConcurrency: 4,
    protectsManager: () => false, declaredRows: () => ({ rows: [], overrides: [] }),
    registries: async () => ({ registry: 'https://registry.npmjs.org/', fallbackRegistries: [], resolved: null }),
    askRegistries: async spec => ({ status: 'answered', registry: 'https://registry.npmjs.org/', attempts: [], answer: spec === name ? { versions: ['0.9.0', '1.0.0', '1.1.0', '2.0.0'], 'dist-tags': { latest: '2.0.0' } } : { name, version: spec.split('@').at(-1), dsh: { bundle: { patch: 'cordis.patch.yml' } } } }),
  });
  await test('listBundles 与 listVersions 对可信出厂link放行并只列新版', async () => {
    assert.equal((await manager.listBundles())[0].updatable, true);
    const answer = await manager.listVersions(name);
    assert.equal(answer.status, 'listed');
    assert.deepEqual(Array.from(answer.versions, v => v.version), ['2.0.0', '1.1.0']);
    assert.equal(answer.installed, '1.0.0');
  });
  await test('拒绝任意link、相对link、file spec、伪造元数据及解析目录', async () => {
    for (const spec of [`link:${tmp}`, 'link:../../node_modules/dsh-android-shell', `file:${pkg}`]) {
      reset(true, spec);
      assert.equal((await manager.listBundles())[0].updatable, false);
      assert.equal((await manager.listVersions(name)).problem, 'not-updatable');
    }
    reset();
    for (const info of [{ ...builtinManifest(), name: 'other' }, { ...builtinManifest(), dsh: { bundle: { patch: 'cordis.patch.yml' }, android: { builtinId: 'unknown' } } }]) {
      writeJson(path.join(pkg, 'package.json'), info);
      assert.equal((await manager.listBundles())[0].updatable, false);
    }
    reset(); resolved = tmp;
    assert.equal((await manager.listBundles())[0].updatable, false);
    resolved = pkg;
  });
  await test('拒绝白名单路径逃逸符号链接', async () => {
    reset();
    const away = path.join(tmp, 'away');
    fs.renameSync(pkg, away); fs.symlinkSync(away, pkg);
    assert.equal((await manager.listBundles())[0].updatable, false);
    fs.unlinkSync(pkg); fs.renameSync(away, pkg);
  });
  const ok = (stage, target) => ({ changed: true, application: 'applied', stage, target });
  const fail = (stage, target) => ({ changed: true, application: 'failed', stage, target, error: { code: 'operation-error' } });
  const calls = [];
  let failInstall = false, failRemove = false, failRestore = false;
  manager.removeBundle = async target => {
    calls.push(['remove', target]);
    const manifest = readManifest(profile);
    manifest.dsh.profile.bundles = [];
    if (!failRemove) delete manifest.dependencies[name];
    writeJson(path.join(profile, 'package.json'), manifest);
    return (failRemove ? fail : ok)('remove', target);
  };
  manager.installBundle = async (spec, options) => {
    calls.push(['install', spec, options]);
    if (failInstall && spec === `${name}@2.0.0` || failRestore && spec === link) return fail('install', spec);
    const manifest = readManifest(profile);
    manifest.dependencies[name] = spec;
    manifest.dsh.profile.bundles = options.enabled ? [name] : [];
    writeJson(path.join(profile, 'package.json'), manifest);
    return ok('enable', name);
  };
  manager.change = async (operation, request) => {
    const result = ok(request.stage, request.target);
    await operation(result);
    return result;
  };
  manager.configure = fn => fn();
  manager.selectBundle = async (_name, enabled) => {
    const manifest = readManifest(profile);
    manifest.dsh.profile.bundles = enabled ? [name] : [];
    writeJson(path.join(profile, 'package.json'), manifest);
  };
  manager.publishAndReload = async () => ({});
  manager.bundleRows = () => [];
  await test('精确更新目标且保留启用/禁用状态，沿用官方remove/install次序', async () => {
    for (const enabled of [true, false]) {
      reset(enabled); calls.length = 0;
      const result = await manager.updateBundle(name, '2.0.0');
      assert.equal(result.result.application, 'applied');
      assert.deepEqual(calls.map(c => c.slice(0, 2)), [['remove', name], ['install', `${name}@2.0.0`]]);
      assert.equal(calls[1][2].enabled, enabled);
      assert.equal(calls[1][2].saveExact, true);
      assert.equal(readManifest(profile).dsh.profile.bundles.includes(name), enabled);
    }
  });
  await test('目标安装失败恢复本地原link而非registry旧版并保留enabled', async () => {
    failInstall = true;
    for (const enabled of [true, false]) {
      reset(enabled); calls.length = 0;
      const result = await manager.updateBundle(name, '2.0.0');
      assert.equal(result.result.application, 'failed');
      assert.equal(result.restore.application, 'applied');
      assert.deepEqual(calls.map(c => c.slice(0, 2)), [['remove', name], ['install', `${name}@2.0.0`], ['install', link]]);
      assert.equal(readManifest(profile).dependencies[name], link);
      assert.equal(readManifest(profile).dsh.profile.bundles.includes(name), enabled);
    }
    failInstall = false;
  });
  await test('移除失败恢复仍在的local link启用状态且不安装registry', async () => {
    reset(); calls.length = 0; failRemove = true;
    const result = await manager.updateBundle(name, '2.0.0');
    assert.equal(result.step, 'remove');
    assert.equal(result.restore.application, 'applied');
    assert.deepEqual(calls, [['remove', name]]);
    assert.equal(readManifest(profile).dependencies[name], link);
    assert.equal(readManifest(profile).dsh.profile.bundles.includes(name), true);
    failRemove = false;
  });
  await test('恢复失败保留官方嵌套restore错误', async () => {
    reset(); failInstall = true; failRestore = true;
    const result = await manager.updateBundle(name, '2.0.0');
    assert.equal(result.restore.application, 'failed');
    assert.equal(result.restore.target, link);
    failInstall = failRestore = false;
  });
  await test('registry依赖既有资格和registry旧版恢复不变', async () => {
    reset(true, '^1.0.0'); calls.length = 0; failInstall = true;
    assert.equal((await manager.listBundles())[0].updatable, true);
    const result = await manager.updateBundle(name, '2.0.0');
    assert.equal(result.restore.application, 'applied');
    assert.equal(calls.at(-1)[1], `${name}@1.0.0`);
    failInstall = false;
  });
  await test('无HMR沿用官方覆盖安装，拒绝非精确/降级更新', async () => {
    reset(false); calls.length = 0; hmr = false;
    assert.equal((await manager.updateBundle(name, '2.0.0')).step, 'install');
    assert.deepEqual(calls.map(c => c.slice(0, 2)), [['install', `${name}@2.0.0`]]);
    reset(); calls.length = 0;
    for (const target of ['latest', '^2.0.0', '1.0.0', '0.9.0']) assert.equal((await manager.updateBundle(name, target)).step, 'check');
    assert.equal(calls.length, 0);
  });
  await test('真实官方remove/install/change事务：包移动待重启、安装失败/取消恢复local link', async () => {
    // 仅替换文件锁、HMR与pnpm边界；remove/install/change及文件快照均运行官方方法。
    Object.assign(sandbox, {});
    Object.assign(context, {
      randomUUID: () => 'test-operation',
      withFileLock: async (_filename, fn) => fn(),
      writeFileAtomic: (filename, value) => fs.promises.writeFile(filename, value),
      readFile: fs.promises.readFile, rm: fs.promises.rm,
      PROFILE_COMPATIBILITY_FILENAME: 'compatibility.json',
      bundlePatchPaths: () => [], loadOverlayPatches: () => [],
    });
    const remotePkg = path.join(profile, 'node_modules', name);
    fs.mkdirSync(remotePkg, { recursive: true });
    const operations = [];
    const lifecycle = [];
    let failure = 'none';
    const live = Object.create(proto);
    Object.assign(live, {
      profile: manager.profile,
      ctx: { loader: { entries: () => [] } },
      ownerContext: {
        get: key => key === 'hmr' ? {
          runExclusive: fn => fn(),
          reconcileProfile: async () => {
            lifecycle.push('reload');
            return { warnings: [], pending: [], ...(resolved === remotePkg ? { restartReason: 'package-moved' } : {}) };
          },
        } : undefined,
        emit() {}, logger: { warn() {} },
      },
      abort: new AbortController(), installs: new Map(), packageOperations: new Set(),
      installedFrom: new Map(), managementBundles: new Set(),
      configuredRegistries: { registry: 'https://registry.npmjs.org/', fallbackRegistries: [] },
      reportOperation() {},
      protectsManager: () => false, declaredRows: () => ({ rows: [], overrides: [] }), bundleRows: () => [],
      registries: manager.registries, askRegistries: manager.askRegistries,
      runPnpm: async args => {
        operations.push(Array.from(args));
        const manifest = readManifest(profile);
        if (args[0] === 'remove') {
          lifecycle.push('remove');
          assert.equal(manifest.dsh.profile.bundles.includes(name), false, '官方remove必须先停用bundle');
          if (failure === 'remove') return { exitCode: 1, output: 'remove failed' };
          delete manifest.dependencies[name];
        } else {
          lifecycle.push('install');
          if (args[1] === `${name}@2.0.0` && ['install', 'cancel'].includes(failure)) {
            if (failure === 'cancel') live.installs.get('request').abort.abort();
            return { exitCode: 1, output: 'target installation failed' };
          }
          manifest.dependencies[name] = args[1];
          resolved = args[1] === link ? pkg : remotePkg;
          if (resolved === remotePkg) writeJson(path.join(remotePkg, 'package.json'), { ...builtinManifest(), version: '2.0.0' });
        }
        writeJson(path.join(profile, 'package.json'), manifest);
        return { exitCode: 0, output: '' };
      },
    });
    for (const enabled of [true, false]) {
      for (const mode of ['none', 'install', 'cancel', 'remove']) {
        reset(enabled); resolved = pkg; failure = mode; operations.length = lifecycle.length = 0;
        const answer = await live.updateBundle(name, '2.0.0', { requestId: 'request' });
        if (mode === 'none') {
          assert.equal(answer.result.application, 'restart-required');
          assert.equal(answer.result.restartReason, 'package-moved');
          assert.equal(readManifest(profile).dependencies[name], `${name}@2.0.0`);
          assert.ok(operations[1].includes('--save-exact'));
        } else {
          assert.equal(answer.result.application, mode === 'cancel' ? 'cancelled' : 'failed');
          assert.equal(answer.restore.application, 'applied');
          assert.equal(readManifest(profile).dependencies[name], link);
          assert.equal(resolved, pkg);
          if (mode !== 'remove') assert.equal(operations.at(-1)[1], link);
        }
        assert.equal(readManifest(profile).dsh.profile.bundles.includes(name), enabled);
        assert.equal(live.installs.size, 0);
      }
    }
  });
  console.log(`通过 ${tests} 个测试，跳过 0；运行 engine.bin 提取的官方 PluginManager 函数。`);
} finally {
  assert.ok(tmp.startsWith(path.join(os.tmpdir(), 'builtin-update-test-')));
  fs.rmSync(tmp, { recursive: true, force: true });
}
