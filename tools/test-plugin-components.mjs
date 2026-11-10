#!/usr/bin/env node
/** node --test tools/test-plugin-components.mjs；只在 mkdtemp 目录生成产物并在 finally 清理。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, readdirSync, rmSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { ROOT, checkNpmPack, compareFiles, createZip, installAssets, loadCatalog, main, prepareComponent, readZip } from './build-plugin-components.mjs';

const script = join(ROOT, 'tools/build-plugin-components.mjs');
const digest = (data) => createHash('sha256').update(data).digest('hex');
function temporary(action) {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-plugin-components-test-'));
  try { return action(directory); }
  finally {
    const target = resolve(directory);
    assert.equal(dirname(target), resolve(tmpdir()));
    assert.ok(target.startsWith(join(resolve(tmpdir()), 'dsh-plugin-components-test-')));
    rmSync(target, { recursive: true, force: true });
  }
}
function put(root, path, content) {
  const target = join(root, path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, content);
}
function fixture(root, changes = {}) {
  const component = {
    id: 'example', source: 'plugin/example', asset: 'example.zip', assetConstant: 'EXAMPLE_ASSET',
    files: ['package.json', 'lib/index.js', 'lib/client.js'], clientFiles: ['lib/client.js'],
    ...changes,
  };
  put(root, 'app/src/main/java/com/dsh/android/engine/EngineConstants.kt', 'const val EXAMPLE_ASSET = "example.zip"\n');
  mkdirSync(join(root, 'app/src/main/assets'), { recursive: true });
  put(root, 'plugin/example/package.json', JSON.stringify({
    name: 'dsh-example', version: '1.2.3', type: 'module', private: true,
    main: './lib/index.js', exports: { '.': './lib/index.js', './client': './lib/client.js' },
    files: ['lib'],
    scripts: { prepack: 'node -e "require(\'fs\').writeFileSync(\'SHOULD_NOT_EXIST\', \'bad\')"' },
  }));
  put(root, 'plugin/example/lib/index.js', 'export const server = 1;\n');
  put(root, 'plugin/example/lib/client.js', 'export const client = globalThis.missing?.value ?? 42;\n');
  const catalogPath = join(root, 'components.json');
  put(root, 'components.json', JSON.stringify({ schemaVersion: 1, target: 'chrome73', components: [component] }));
  return { root, catalogPath, component, out: join(root, 'output') };
}
function command(args) {
  const result = spawnSync(process.execPath, [script, ...args], { cwd: ROOT, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

test('清单覆盖全部实际内置包且资产常量对应 EngineConstants', () => {
  const catalog = loadCatalog();
  assert.equal(catalog.components.length, 17);
  const mobile = readdirSync(join(ROOT, 'mobile-ui-plugin')).sort();
  assert.deepEqual(catalog.components.filter((component) => component.source.startsWith('mobile-ui-plugin/')).map((component) => component.source.split('/')[1]).sort(), mobile);
  for (const id of ['market', 'agnes', 'core-memory', 'preset-razor', 'mobile-settings', 'host-bridge', 'balance']) assert.ok(catalog.components.some((component) => component.id === id));
});

test('实际全部组件构建后逐文件 check，manifest 包含源码及产物摘要', () => temporary((work) => {
  const output = command(['build', '--out-dir', work]);
  assert.equal(output.split('\n').filter((line) => line.includes('已构建')).length, 17);
  const checked = command(['check', '--out-dir', work]);
  assert.equal(checked.split('\n').filter((line) => line.includes('逐文件一致')).length, 17);
  for (const component of loadCatalog().components) {
    const zip = readFileSync(join(work, component.asset));
    const files = readZip(zip);
    assert.deepEqual([...files.keys()].sort(), [...component.files].sort());
    const manifest = JSON.parse(readFileSync(join(work, `${component.id}.manifest.json`)));
    assert.equal(manifest.assetSha256, digest(zip));
    assert.equal(manifest.target, 'chrome73');
    assert.equal(manifest.version, JSON.parse(readFileSync(join(ROOT, component.source, 'package.json'))).version);
    assert.equal(manifest.revision, `${manifest.version}+sha256.${manifest.sourceDigest}`);
    for (const source of manifest.sourceFiles) assert.equal(source.sha256, digest(readFileSync(join(ROOT, component.source, source.path))));
    for (const file of manifest.files) assert.equal(file.sha256, digest(files.get(file.path)));
  }
}));

test('zip 与 manifest 不受源码 mtime/输出目录影响，客户端 chrome73 降级', () => temporary((root) => {
  const setup = fixture(root);
  main(['build', '--out-dir', setup.out], setup);
  const zip = readFileSync(join(setup.out, 'example.zip'));
  const manifest = readFileSync(join(setup.out, 'example.manifest.json'));
  const files = readZip(zip);
  assert.doesNotMatch(files.get('lib/client.js').toString(), /\?\.|\?\?/);
  assert.equal(files.get('lib/index.js').toString(), 'export const server = 1;\n');
  for (const file of setup.component.files) utimesSync(join(root, 'plugin/example', file), new Date('2030-04-01'), new Date('2030-04-01'));
  const other = join(root, 'another-output');
  main(['build', '--out-dir', other], setup);
  assert.deepEqual(readFileSync(join(other, 'example.zip')), zip);
  assert.deepEqual(readFileSync(join(other, 'example.manifest.json')), manifest);
}));

test('源码变化及 package 版本变化都会改变 manifest revision', () => temporary((root) => {
  const setup = fixture(root);
  const first = prepareComponent(setup.component, setup).manifest;
  put(root, 'plugin/example/lib/index.js', 'export const server = 2;\n');
  const second = prepareComponent(setup.component, setup).manifest;
  assert.notEqual(second.revision, first.revision);
  const path = join(root, 'plugin/example/package.json');
  const pkg = JSON.parse(readFileSync(path));
  pkg.version = '1.2.4';
  writeFileSync(path, JSON.stringify(pkg));
  const third = prepareComponent(setup.component, setup).manifest;
  assert.match(third.revision, /^1\.2\.4\+sha256\./);
  assert.notEqual(third.sourceDigest, second.sourceDigest);
}));

test('check 检出缺失、多余和内容过期文件', () => {
  const expected = new Map([['package.json', Buffer.from('{}')], ['lib/index.js', Buffer.from('new')]]);
  assert.throws(() => compareFiles(expected, new Map(), 'example'), /缺少 package.json/);
  assert.throws(() => compareFiles(expected, new Map([...expected, ['extra.js', Buffer.from('x')]]), 'example'), /多余 extra.js/);
  const changed = new Map(expected);
  changed.set('lib/index.js', Buffer.from('old'));
  assert.throws(() => compareFiles(expected, changed, 'example'), /内容不同 lib\/index.js/);
});

test('check 检出真实 zip 条目差异及 manifest 变化', () => temporary((root) => {
  const setup = fixture(root);
  main(['build', '--out-dir', setup.out], setup);
  const path = join(setup.out, 'example.zip');
  const original = readFileSync(path);
  const entries = readZip(original);
  entries.delete('lib/index.js');
  entries.set('extra.js', Buffer.from('old'));
  writeFileSync(path, createZip(entries));
  assert.throws(() => main(['check', '--out-dir', setup.out], setup), /缺少 lib\/index.js[\s\S]*多余 extra.js/);
  writeFileSync(path, original);
  put(setup.out, 'example.manifest.json', '{}');
  assert.throws(() => main(['check', '--out-dir', setup.out], setup), /manifest 与源码\/工具版本不一致/);
}));

test('单组件 CLI 构建和检查只生成选定组件', () => temporary((work) => {
  command(['build', '--component', 'shell-tool', '--out-dir', work]);
  assert.deepEqual(readdirSync(work).sort(), ['shell-tool.manifest.json', 'shell-tool.zip']);
  command(['check', '--component', 'shell-tool', '--out-dir', work]);
}));

test('拒绝未知组件、漏 out-dir、仓库资产目录及非法清单路径', () => temporary((root) => {
  const setup = fixture(root);
  assert.throws(() => main(['build'], setup), /必须显式指定/);
  assert.throws(() => main(['check', '--component', 'missing'], setup), /未知组件/);
  assert.throws(() => main(['build', '--out-dir', join(root, 'app/src/main/assets')], setup), /禁止直接覆盖/);
  fixture(root, { files: ['package.json', '../escape'] });
  assert.throws(() => loadCatalog(root, setup.catalogPath), /非法相对路径/);
}));

test('ZIP CRC、目录穿越和重复条目不能被当作通过', () => {
  assert.throws(() => createZip(new Map([['../escape', Buffer.from('x')]])), /非法相对路径/);
  const archive = createZip(new Map([['a', Buffer.from('x')]]));
  const corrupt = Buffer.from(archive);
  corrupt[31] ^= 1;
  assert.throws(() => readZip(corrupt), /内容校验失败/);
  const duplicate = createZip(new Map([['a', Buffer.from('x')], ['b', Buffer.from('y')]]));
  duplicate[62] = 'a'.charCodeAt(0); // 第二条本地记录的名称。
  let centralCount = 0;
  for (let index = 0; index < duplicate.length - 4; index++) {
    if (duplicate.readUInt32LE(index) === 0x02014b50 && ++centralCount === 2) duplicate[index + 46] = 'a'.charCodeAt(0);
  }
  assert.throws(() => readZip(duplicate), /重复条目/);
});

test('npm pack 检查全部实际组件：检查真正 tarball 的内容，包含 private 包', () => {
  const output = command(['pack-check']);
  assert.equal(output.split('\n').filter((line) => line.includes('npm pack 已检查')).length, 17);
});

test('npm pack 使用降级后内容并禁用生命周期脚本，临时副本不污染源码', () => temporary((root) => {
  const setup = fixture(root);
  const prepared = prepareComponent(setup.component, setup);
  const report = checkNpmPack(prepared);
  assert.equal(report.files, 3);
  assert.equal(report.private, true);
  assert.equal(existsSync(join(root, 'plugin/example/SHOULD_NOT_EXIST')), false);
  assert.deepEqual(readdirSync(join(root, 'plugin/example')).sort(), ['lib', 'package.json']);
}));

test('npm pack 拒绝 files 排除的运行入口', () => temporary((root) => {
  const setup = fixture(root);
  const path = join(root, 'plugin/example/package.json');
  const pkg = JSON.parse(readFileSync(path));
  pkg.files = ['lib/index.js'];
  writeFileSync(path, JSON.stringify(pkg));
  assert.throws(() => checkNpmPack(prepareComponent(setup.component, setup)), /npm 包缺少或修改了 lib\/client.js/);
}));

test('check 省略目录时只读核对旧资产路径，不要求新增 manifest', () => temporary((root) => {
  const setup = fixture(root);
  const prepared = prepareComponent(setup.component, setup);
  put(root, 'app/src/main/assets/example.zip', createZip(prepared.files));
  main(['check', '--component', 'example'], setup);
  assert.deepEqual(readdirSync(join(root, 'app/src/main/assets')), ['example.zip']);
}));

test('npm pack 拒绝未声明的多余文件', () => temporary((root) => {
  const setup = fixture(root);
  put(root, 'plugin/example/README.md', 'undeclared extra');
  assert.throws(() => checkNpmPack(prepareComponent(setup.component, setup)), /npm 包含意外文件 README.md/);
}));

test('组件测试源码不进入 zip 或 npm tarball', () => temporary((root) => {
  const setup = fixture(root);
  put(root, 'plugin/example/test/example.mjs', 'throw new Error("not a runtime file");');
  const prepared = prepareComponent(setup.component, setup);
  assert.ok(prepared.manifest.sourceFiles.some((file) => file.path === 'test/example.mjs'));
  assert.ok(!readZip(createZip(prepared.files)).has('test/example.mjs'));
  assert.equal(checkNpmPack(prepared).files, 3);
}));

test('显式安装同步 zip 和 Kotlin manifest，重复执行不改写一致目标', () => temporary((root) => {
  const setup = fixture(root);
  main(['build', '--out-dir', setup.out, '--install-assets'], setup);
  const assets = join(root, 'app/src/main/assets');
  assert.deepEqual(readdirSync(assets).sort(), ['example.manifest.json', 'example.zip']);
  for (const name of readdirSync(assets)) assert.deepEqual(readFileSync(join(assets, name)), readFileSync(join(setup.out, name)));
  const before = lstatSync(join(assets, 'example.zip')).mtimeMs;
  main(['build', '--out-dir', setup.out, '--install-assets'], setup);
  assert.equal(lstatSync(join(assets, 'example.zip')).mtimeMs, before);
}));

test('安装保留已有 zip 和 manifest 的符号链接并更新共享目标', () => temporary((root) => {
  const setup = fixture(root);
  const assets = join(root, 'app/src/main/assets');
  put(root, 'shared/example.zip', 'old zip');
  put(root, 'shared/example.manifest.json', 'old manifest');
  for (const name of ['example.zip', 'example.manifest.json']) symlinkSync(join(root, 'shared', name), join(assets, name));
  main(['build', '--out-dir', setup.out, '--install-assets'], setup);
  for (const name of ['example.zip', 'example.manifest.json']) {
    assert.ok(lstatSync(join(assets, name)).isSymbolicLink());
    assert.equal(readlinkSync(join(assets, name)), join(root, 'shared', name));
    assert.deepEqual(readFileSync(join(root, 'shared', name)), readFileSync(join(setup.out, name)));
  }
}));

test('任一产物损坏时全部资产保持原样', () => temporary((root) => {
  const setup = fixture(root);
  main(['build', '--out-dir', setup.out], setup);
  const prepared = prepareComponent(setup.component, setup);
  prepared.manifest.assetSha256 = digest(readFileSync(join(setup.out, 'example.zip')));
  const broken = { ...prepared, component: { ...prepared.component, id: 'broken', asset: 'broken.zip' } };
  put(setup.out, 'broken.zip', 'bad zip');
  put(root, 'app/src/main/assets/example.zip', 'existing');
  assert.throws(() => installAssets([prepared, broken], setup.out, join(root, 'app/src/main/assets')));
  assert.equal(readFileSync(join(root, 'app/src/main/assets/example.zip'), 'utf8'), 'existing');
  assert.deepEqual(readdirSync(join(root, 'app/src/main/assets')), ['example.zip']);
}));

test('悬空 symlink 在同步开始前失败，保留已有资产与链接', () => temporary((root) => {
  const setup = fixture(root);
  const assets = join(root, 'app/src/main/assets');
  put(root, 'app/src/main/assets/example.zip', 'existing');
  symlinkSync(join(root, 'missing-manifest'), join(assets, 'example.manifest.json'));
  assert.throws(() => main(['build', '--out-dir', setup.out, '--install-assets'], setup), /ENOENT/);
  assert.equal(readFileSync(join(assets, 'example.zip'), 'utf8'), 'existing');
  assert.ok(lstatSync(join(assets, 'example.manifest.json')).isSymbolicLink());
}));

test('install-assets 必须经过 build，不支持直接 check/pack-check 安装', () => temporary((root) => {
  const setup = fixture(root);
  for (const mode of ['check', 'pack-check']) assert.throws(() => main([mode, '--install-assets'], setup), /仅用于 build/);
  assert.deepEqual(readdirSync(join(root, 'app/src/main/assets')), []);
}));

test('Gradle 构建前任务调用统一工具，显式安装并使用 build 目录', () => {
  const gradle = readFileSync(join(ROOT, 'app/build.gradle.kts'), 'utf8');
  assert.match(gradle, /val buildBuiltinPluginAssets by tasks\.registering\(Exec::class\)/);
  assert.match(gradle, /workingDir\(rootProject\.projectDir\)/);
  assert.match(gradle, /rootProject\.file\("tools\/build-plugin-components\.mjs"\)/);
  assert.match(gradle, /"build", "--out-dir", layout\.buildDirectory\.dir\("generated\/plugin-components"\)/);
  assert.match(gradle, /"--install-assets"/);
  assert.match(gradle, /tasks\.named\("preBuild"\)\s*\{\s*dependsOn\(buildBuiltinPluginAssets\)/);
});

test('npm pack 拒绝入口未纳入组件清单', () => temporary((root) => {
  const setup = fixture(root);
  const path = join(root, 'plugin/example/package.json');
  const pkg = JSON.parse(readFileSync(path));
  pkg.dsh = { bundle: { patch: './missing.patch.yml' } };
  writeFileSync(path, JSON.stringify(pkg));
  assert.throws(() => checkNpmPack(prepareComponent(setup.component, setup)), /入口不在组件清单/);
}));
