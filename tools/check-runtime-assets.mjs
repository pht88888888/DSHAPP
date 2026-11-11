#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export function checkRuntimeAssets({ root = ROOT, lockPath = join(root, 'catalogs/runtime-assets.lock.json') } = {}) {
  const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
  assert.equal(lock.schemaVersion, 1, '不支持的运行资产锁');
  assert.ok(lock.assets.length > 0, '运行资产锁不能为空');
  const names = new Set();
  for (const asset of lock.assets) {
    assert.ok(!names.has(asset.id), `重复资产 ${asset.id}`);
    names.add(asset.id);
    const path = resolve(root, asset.path);
    const local = relative(root, path);
    assert.ok(!isAbsolute(local) && local !== '..' && !local.startsWith('../'), `资产越界 ${asset.path}`);
    assert.ok(asset.apkEntry.startsWith('assets/') && !asset.apkEntry.split('/').includes('..'), `非法 APK 路径 ${asset.apkEntry}`);
    assert.match(asset.sha256, /^[a-f0-9]{64}$/);
    let data;
    try { assert.ok(statSync(path).isFile()); data = readFileSync(path); }
    catch (error) { throw new Error(`缺少真实运行输入 ${asset.path}：${error.message}；请提供锁定资产，不能用跳过代替通过。`); }
    assert.equal(data.length, asset.bytes, `${asset.id} 字节数漂移`);
    assert.equal(createHash('sha256').update(data).digest('hex'), asset.sha256, `${asset.id} SHA-256 漂移`);
  }
  return { assets: lock.assets.length, skipped: 0 };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { const result = checkRuntimeAssets(); console.log(`运行资产锁通过：${result.assets} 项逐字节摘要一致，${result.skipped} 跳过`); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
