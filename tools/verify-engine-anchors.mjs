#!/usr/bin/env node
/** L1：显式锁定引擎/兼容归档的临时副本体检，不下载或隐读 checkout 资产。 */
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { requireKotlinTestInput, resolveKotlinTestRuntime } from './kotlin-test-runtime.mjs';

// Validate every required input before starting either suite.
requireKotlinTestInput('DSH_ENGINE_ARCHIVE');
requireKotlinTestInput('DSH_WEBVIEW_COMPAT_ARCHIVE');
resolveKotlinTestRuntime();

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const checks = [
  'app/src/test/engine-patch-registry.mjs',
  'app/src/test/builtin-plugin-update-patch.mjs',
];
for (const check of checks) {
  const entry = join(root, check);
  if (!existsSync(entry)) {
    console.error(`找不到引擎补丁体检入口: ${check}`);
    process.exit(2);
  }
  try {
    execFileSync(process.execPath, [entry, ...(check.endsWith('engine-patch-registry.mjs') ? ['--official'] : [])], { cwd: root, stdio: 'inherit' });
  } catch (error) {
    console.error(`引擎补丁体检失败: ${check}`);
    process.exit(Number.isInteger(error.status) ? error.status : 2);
  }
}
console.log('引擎补丁注册表与内置更新适配体检全部通过。');
