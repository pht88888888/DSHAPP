#!/usr/bin/env node
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadCatalog, prepareComponent, readEngineConstants } from '../tooling/plugins/build.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const read = (path) => readFileSync(join(root, path), 'utf8');
const json = (path) => JSON.parse(read(path));
// 对本地静态 import 的源码闭包检查职责，拆分后的薄入口不能隐藏业务回流。
const sourceClosure = (entry, seen = new Set()) => {
  const absolute = resolve(root, entry);
  assert.ok(absolute.startsWith(`${root}/`), `源码越界: ${entry}`);
  if (seen.has(absolute)) return '';
  seen.add(absolute);
  const source = readFileSync(absolute, 'utf8');
  const children = [...source.matchAll(/\b(?:import|export)\s+(?:[^;\n]*?\s+from\s*)?["'](\.[^"']+)["']/g)]
    .map((match) => sourceClosure(resolve(absolute, '..', match[1]), seen));
  return [source, ...children].join('\n');
};
const catalog = loadCatalog();
const componentPath = (id, path) => {
  const component = catalog.components.find(item => item.id === id);
  assert.ok(component, `缺少组件 ${id}`);
  return join(component.source, path);
};
const constants = readEngineConstants(root);
const existsInManifest = (component, path) => component.files.includes(path);
const errors = [];
const check = (label, action) => {
  try { action(); console.log(`✓ ${label}`); }
  catch (error) { errors.push(`${label}: ${error.message}`); console.error(`✗ ${label}: ${error.message}`); }
};
const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;

check('组件清单 schema 与组件 ID 唯一', () => {
  assert.equal(catalog.schemaVersion, 1);
  assert.equal(catalog.target, 'chrome73');
  assert.ok(Array.isArray(catalog.components) && catalog.components.length > 0);
  const ids = catalog.components.map((item) => item.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const key of ['asset', 'source', 'assetConstant']) {
    const values = catalog.components.map((item) => item[key]);
    assert.equal(new Set(values).size, values.length, `${key} 必须唯一`);
  }
});

check('组件清单覆盖 Android 内部组件实际目录', () => {
  const members = catalog.components.filter(item => item.bundledIn === 'android-bundle');
  const parents = new Set(members.map(item => dirname(item.source)));
  assert.equal(parents.size, 1, '内部组件必须共用一个源码目录');
  const parent = [...parents][0];
  const directories = readdirSync(join(root, parent), { withFileTypes: true })
    .filter(entry => entry.isDirectory()).map(entry => `${parent}/${entry.name}`).sort();
  assert.deepEqual(members.map(item => item.source).sort(), directories);
  assert.equal(members.length, 14);
});

for (const component of catalog.components) {
  check(`${component.id}: package 版本、入口与清单文件完整`, () => {
    const pkg = json(join(component.source, 'package.json'));
    assert.match(pkg.version, semver, 'package.version 必须是完整语义版本');
    assert.ok(Array.isArray(component.files) && component.files.includes('package.json'));
    assert.ok(component.asset && component.asset.endsWith('.zip'));
    assert.ok(component.assetConstant && new RegExp(`const val ${component.assetConstant} = "${component.asset.replaceAll('.', '\\.')}"`).test(constants), '资产名必须与 EngineConstants 对应');

    // 聚合 patch 由成员源码生成，检查真实构建结果而非要求重复维护一份源码。
    const generated = component.componentIds
      ? prepareComponent(loadCatalog().components.find((item) => item.id === component.id)).files
      : null;
    const includesFile = (file) => generated ? generated.has(file) : existsInManifest(component, file);
    const readEntry = (file) => generated ? assert.ok(generated.has(file), `聚合产物缺少 ${file}`) : read(join(component.source, file));
    const entries = new Set();
    if (pkg.main) entries.add(pkg.main.replace(/^\.\//, ''));
    const exportEntries = (target) => {
      if (typeof target === 'string') entries.add(target.replace(/^\.\//, ''));
      else if (target && typeof target === 'object') for (const value of Object.values(target)) exportEntries(value);
    };
    exportEntries(pkg.exports);
    if (pkg.dsh?.bundle?.patch) entries.add(pkg.dsh.bundle.patch.replace(/^\.\//, ''));
    assert.ok(entries.size > 0, '必须声明 main、exports 或 bundle patch 入口');
    for (const file of component.files) {
      assert.ok(!file.startsWith('/') && !file.split('/').includes('..'), `非法组件路径 ${file}`);
      read(join(component.source, file));
    }
    for (const entry of entries) {
      assert.ok(includesFile(entry), `入口 ${entry} 未列入 plugin-components.json 或聚合产物`);
      readEntry(entry);
    }

    const bundlePatch = pkg.dsh?.bundle?.patch;
    assert.equal(typeof bundlePatch, 'string', '组件必须声明标准 dsh.bundle.patch');
    if (bundlePatch) {
      const patch = bundlePatch.replace(/^\.\//, '');
      assert.ok(includesFile(patch), `bundle patch ${patch} 未列入组件清单或聚合产物`);
      readEntry(patch);
      const builtinId = pkg.dsh?.android?.builtinId;
      if (builtinId) assert.equal(builtinId, component.id, 'android.builtinId 必须对应组件 ID');
    }

    for (const client of component.clientFiles || []) {
      assert.ok(existsInManifest(component, client), `client ${client} 未列入 files`);
      const clientSource = sourceClosure(join(component.source, client));
      assert.match(clientSource, /window\.__ModuleLoader__\.load\s*\(/, `${client} 缺 ModuleLoader 入口`);
      const packageName = pkg.name;
      assert.ok(clientSource.includes(`id: "${packageName}"`) || clientSource.includes(`id: '${packageName}'`), `${client} ModuleLoader id 与 package.name 不符`);
      const clientExport = pkg.exports?.['./client'];
      const target = typeof clientExport === 'string' ? clientExport : clientExport?.default;
      assert.equal(target?.replace(/^\.\//, ''), client, 'exports[./client] 必须指向清单 client');
    }
  });
}

check('mobile 核心不等待账号服务', () => {
  const pkg = json(componentPath('mobile-ui', 'package.json'));
  assert.ok(!(pkg.dsh?.client?.inject || []).some((name) => /account/i.test(name)), 'client inject 不应包含 account');
  const source = sourceClosure(componentPath('mobile-ui', 'lib/client.js'));
  assert.doesNotMatch(source, /remote\.account|remote\.pluginManager|account\.getBalance/, '移动布局不得直接依赖账号/插件市场服务');
  const component = catalog.components.find(item => item.id === 'mobile-ui');
  let registered;
  vm.runInNewContext(prepareComponent(component).files.get('lib/client.js').toString(), {
    window: { __ModuleLoader__: { load(definition) { registered = definition; } } },
  });
  assert.equal(registered?.id, pkg.name, '真实构建产物必须注册原官方模块');
  const plugin = registered.factory(() => { throw new Error('核心工厂不得提前取得服务依赖'); });
  assert.ok(Array.isArray(plugin.inject), '核心工厂必须显式声明启动依赖');
  assert.deepEqual(Array.from(plugin.inject), [], '核心布局必须无启动服务依赖');
  assert.doesNotMatch(source, /mpInstallBalanceBridge|bubbleReportBalance|mpRegisterPrivilegedSection|mpRegisterBubbleSection|mpRegisterLogsSection|mpRegisterContactSection/, '已拆出的业务安装器不得回流核心');
  assert.doesNotMatch(source, /slots\.register\s*\([\s\S]{0,120}name:\s*["']settings\.section["']/, '核心不得注册业务设置分节');
  const host = read(componentPath('mobile-ui', 'lib/index.js'));
  assert.doesNotMatch(host, /privileged|\.remote|remote\.account|router\.|server\./, '核心 host 不得承载业务路由或账号服务');
});

check('移动设置、余额和宿主桥功能保持独立组件边界', () => {
  const mobile = sourceClosure(componentPath('mobile-ui', 'lib/client.js'));
  const independent = [
    ['mobile-settings', componentPath('mobile-settings', 'lib/client.js'), ['dsh-android-privileged', 'dsh-android-bubble', 'dsh-android-logs']],
    ['balance', componentPath('balance', 'lib/client.js'), ['remote.account', 'getBalance']],
    ['host-bridge', componentPath('host-bridge', 'lib/client.js'), ['onStatus', 'onTranscript', 'writeClipboard']],
  ];
  for (const [id, path, markers] of independent) {
    assert.ok(catalog.components.some((component) => component.id === id), `缺少独立组件 ${id}`);
    const source = sourceClosure(path);
    for (const marker of markers) assert.ok(source.includes(marker), `${id} 未承载预期功能标记 ${marker}`);
    for (const marker of markers) assert.ok(!mobile.includes(marker), `核心 mobile 功能回流：${marker}`);
  }
});

if (errors.length) {
  console.error(`插件边界守卫失败：${errors.length} 项`);
  process.exitCode = 1;
} else {
  console.log(`插件边界守卫通过：${catalog.components.length} 个组件`);
}
