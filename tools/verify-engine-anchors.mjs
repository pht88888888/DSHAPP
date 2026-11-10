#!/usr/bin/env node
/** 对 engine.bin 临时副本执行真实 Kotlin 补丁注册表及内置更新事务体检。 */
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

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
    execFileSync(process.execPath, [entry], { cwd: root, stdio: 'inherit' });
  } catch (error) {
    console.error(`引擎补丁体检失败: ${check}`);
    process.exit(Number.isInteger(error.status) ? error.status : 2);
  }
}
console.log('引擎补丁注册表与内置更新适配体检全部通过。');
