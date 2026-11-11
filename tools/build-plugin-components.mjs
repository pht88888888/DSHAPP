#!/usr/bin/env node
/** 兼容旧命令与 import；唯一实现位于 tooling/plugins/build.mjs。 */
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { main } from '../tooling/plugins/build.mjs';
export * from '../tooling/plugins/build.mjs';
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
