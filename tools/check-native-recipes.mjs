#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const digest = data => createHash('sha256').update(data).digest('hex');
export function inside(root, path) {
  if (typeof path !== 'string' || !path || isAbsolute(path) || path.includes('\\') || path.split('/').some(part => !part || part === '.' || part === '..')) throw new Error(`非法仓库路径: ${path}`);
  const result = resolve(root, path);
  if (existsSync(result)) {
    const rel = relative(realpathSync(root), realpathSync(result));
    if (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) throw new Error(`文件越界: ${path}`);
  }
  return result;
}
export function validateRecipes(lock, root = ROOT) {
  if (lock.schemaVersion !== 1 || !Array.isArray(lock.recipes) || lock.recipes.length !== 3 || !Array.isArray(lock.externalArtifacts) || !Array.isArray(lock.retired)) throw new Error('native配方schema/集合无效');
  if (lock.toolchain?.ndkRevision !== '27.0.12077973' || lock.toolchain?.api !== 26 || lock.toolchain?.abi !== 'arm64-v8a' || lock.toolchain?.compiler !== 'aarch64-linux-android26-clang') throw new Error('NDK/API/ABI/compiler不符合已记录配方');
  const ids = new Set();
  for (const recipe of lock.recipes) {
    if (!/^[a-z0-9-]+$/.test(recipe.id) || ids.has(recipe.id)) throw new Error(`重复或非法配方id: ${recipe.id}`);
    ids.add(recipe.id);
    if (!['shared-library', 'executable'].includes(recipe.kind) || !/^[A-Za-z0-9.-]+\.bin$/.test(recipe.output) || !Array.isArray(recipe.args) || !recipe.args.includes('{source}') || !recipe.args.includes('{output}')) throw new Error(`${recipe.id}: 缺少输出/命令输入`);
    if (recipe.args.filter(arg => arg === '{source}').length !== 1 || recipe.args.filter(arg => arg === '{output}').length !== 1 || recipe.args.some(arg => typeof arg !== 'string' || arg.includes('\0'))) throw new Error(`${recipe.id}: 非法配方参数`);
    if (!recipe.args.includes('-O2') || !recipe.args.includes('-Wall') || recipe.args[recipe.args.indexOf('-o') + 1] !== '{output}') throw new Error(`${recipe.id}: 编译参数不完整`);
    if (recipe.kind === 'shared-library' && !['-shared', '-fPIC', '-ldl'].every(flag => recipe.args.includes(flag))) throw new Error(`${recipe.id}: 共享库参数不完整`);
    if (!Array.isArray(recipe.requiredSymbols) || !recipe.requiredSymbols.length || recipe.requiredSymbols.some(name => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(name))) throw new Error(`${recipe.id}: ELF符号契约缺失`);
    const source = inside(root, recipe.source?.path);
    if (!recipe.source.path.startsWith('native/') || !lstatSync(source).isFile() || !/^[a-f0-9]{64}$/.test(recipe.source.sha256) || digest(readFileSync(source)) !== recipe.source.sha256) throw new Error(`${recipe.id}: 源码摘要不一致`);
    const license = inside(root, recipe.license?.path);
    if (recipe.license.spdx !== 'MIT' || digest(readFileSync(license)) !== recipe.license.sha256) throw new Error(`${recipe.id}: 许可证摘要不一致`);
    if (!recipe.evidence?.path || !recipe.evidence?.detail || recipe.verification?.androidBuild !== 'not-run' || recipe.verification?.byteReproduction !== 'unverified') throw new Error(`${recipe.id}: 来源/验证状态缺失`);
    inside(root, recipe.evidence.path);
  }
  const runtime = JSON.parse(readFileSync(inside(root, lock.runtimeAssetLock), 'utf8'));
  if (runtime.schemaVersion !== 1 || !Array.isArray(runtime.assets)) throw new Error('运行资产锁schema无效');
  for (const recipe of lock.recipes) {
    const asset = runtime.assets.find(asset => asset.id === recipe.id);
    if (!asset || asset.sha256 !== recipe.verification.existingAssetSha256) throw new Error(`${recipe.id}: 配方观察制品与运行锁不一致`);
  }
  const externalIds = new Set();
  for (const external of lock.externalArtifacts) {
    if (!external.id || externalIds.has(external.id) || !external.runtimeAssetId || !/^[a-f0-9]{64}$/.test(external.observedArtifactSha256) || !external.upstream?.location || !Array.isArray(external.gaps) || !external.gaps.length || external.recipeStatus !== 'incomplete') throw new Error(`外部配方来源缺口不明确: ${external.id}`);
    externalIds.add(external.id);
    if (!Object.hasOwn(external.upstream, 'version') || !Object.hasOwn(external.upstream, 'integrity')) throw new Error(`${external.id}: 未明确上游版本/完整性缺口`);
    const asset = runtime.assets.find(asset => asset.id === external.runtimeAssetId);
    if (!asset || asset.sha256 !== external.observedArtifactSha256 || asset.source?.location !== external.upstream.location || asset.source?.version !== external.upstream.version || asset.source?.upstreamIntegrity !== external.upstream.integrity) throw new Error(`${external.id}: 外部来源与运行锁不一致`);
  }
  if (!lock.retired.some(item => item.id === 'keyinject' && item.status === 'retired' && item.reason)) throw new Error('keyinject必须明确下线，不列为active缺源');
  return { schemaVersion: 1, status: 'passed', recipes: lock.recipes.map(recipe => ({ id: recipe.id, source: recipe.source.path, sha256: recipe.source.sha256 })), externalGaps: lock.externalArtifacts.length, assetAccess: false, androidBuild: 'not-run' };
}
export function loadRecipes(root = ROOT, lockPath = resolve(root, 'catalogs/native-recipes.lock.json')) {
  const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
  return { lock, report: validateRecipes(lock, root) };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.length && (args.length !== 2 || args[0] !== '--lock')) throw new Error('用法: node tools/check-native-recipes.mjs [--lock FILE]');
    console.log(JSON.stringify(loadRecipes(ROOT, args[1] ? resolve(args[1]) : undefined).report, null, 2));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
