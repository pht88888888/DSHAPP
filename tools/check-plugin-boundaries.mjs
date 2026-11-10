#!/usr/bin/env node
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const read = (path) => readFileSync(join(root, path), 'utf8');
const json = (path) => JSON.parse(read(path));
const catalog = json('tools/plugin-components.json');
const constants = read('app/src/main/java/com/dsh/android/engine/EngineConstants.kt');
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

check('组件清单覆盖移动插件实际目录', () => {
  const directories = readdirSync(join(root, 'mobile-ui-plugin'), { withFileTypes: true })
    .filter((entry) => entry.isDirectory()).map((entry) => `mobile-ui-plugin/${entry.name}`).sort();
  assert.deepEqual(catalog.components.filter((item) => item.source.startsWith('mobile-ui-plugin/'))
    .map((item) => item.source).sort(), directories);
});

for (const component of catalog.components) {
  check(`${component.id}: package 版本、入口与清单文件完整`, () => {
    const pkg = json(join(component.source, 'package.json'));
    assert.match(pkg.version, semver, 'package.version 必须是完整语义版本');
    assert.ok(Array.isArray(component.files) && component.files.includes('package.json'));
    assert.ok(component.asset && component.asset.endsWith('.zip'));
    assert.ok(component.assetConstant && new RegExp(`const val ${component.assetConstant} = "${component.asset.replaceAll('.', '\\.')}"`).test(constants), '资产名必须与 EngineConstants 对应');

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
      assert.ok(existsInManifest(component, entry), `入口 ${entry} 未列入 plugin-components.json`);
      read(join(component.source, entry));
    }

    const bundlePatch = pkg.dsh?.bundle?.patch;
    assert.equal(typeof bundlePatch, 'string', '组件必须声明标准 dsh.bundle.patch');
    if (bundlePatch) {
      const patch = bundlePatch.replace(/^\.\//, '');
      assert.ok(existsInManifest(component, patch), `bundle patch ${patch} 未列入组件清单`);
      read(join(component.source, patch));
      const builtinId = pkg.dsh?.android?.builtinId;
      if (builtinId) assert.equal(builtinId, component.id, 'android.builtinId 必须对应组件 ID');
    }

    for (const client of component.clientFiles || []) {
      assert.ok(existsInManifest(component, client), `client ${client} 未列入 files`);
      const clientSource = read(join(component.source, client));
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
  const pkg = json('mobile-ui-plugin/dsh-android-mobile/package.json');
  assert.ok(!(pkg.dsh?.client?.inject || []).some((name) => /account/i.test(name)), 'client inject 不应包含 account');
  const source = read('mobile-ui-plugin/dsh-android-mobile/lib/client.js');
  assert.doesNotMatch(source, /remote\.account|remote\.pluginManager|account\.getBalance/, '移动布局不得直接依赖账号/插件市场服务');
  assert.match(source, /exports\.inject\s*=\s*\[\s*\]/, '核心布局必须无启动服务依赖');
  assert.doesNotMatch(source, /mpInstallBalanceBridge|bubbleReportBalance|mpRegisterPrivilegedSection|mpRegisterBubbleSection|mpRegisterLogsSection|mpRegisterContactSection/, '已拆出的业务安装器不得回流核心');
  assert.doesNotMatch(source, /slots\.register\s*\([\s\S]{0,120}name:\s*["']settings\.section["']/, '核心不得注册业务设置分节');
  const host = read('mobile-ui-plugin/dsh-android-mobile/lib/index.js');
  assert.doesNotMatch(host, /privileged|\.remote|remote\.account|router\.|server\./, '核心 host 不得承载业务路由或账号服务');
});

check('移动设置、余额和宿主桥功能保持独立组件边界', () => {
  const mobile = read('mobile-ui-plugin/dsh-android-mobile/lib/client.js');
  const independent = [
    ['mobile-settings', 'mobile-ui-plugin/dsh-android-mobile-settings/lib/client.js', ['dsh-android-privileged', 'dsh-android-bubble', 'dsh-android-logs']],
    ['balance', 'mobile-ui-plugin/dsh-android-balance/lib/client.js', ['remote.account', 'getBalance']],
    ['host-bridge', 'mobile-ui-plugin/dsh-android-host-bridge/lib/client.js', ['onStatus', 'onTranscript', 'writeClipboard']],
  ];
  for (const [id, path, markers] of independent) {
    assert.ok(catalog.components.some((component) => component.id === id), `缺少独立组件 ${id}`);
    const source = read(path);
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
