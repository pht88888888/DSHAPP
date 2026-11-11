import { existsSync, readFileSync, writeFileSync, renameSync, unlinkSync, openSync, closeSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { json, sha256, reference } from './inspect.mjs';

export const DIRECTORIES = Object.freeze({ latest: 'jiulingShare/DSH APP/当前最新版', history: 'jiulingShare/DSH APP/历史版本', candidates: 'jiulingShare/DSH APP/候选版本' });
const safeName = name => typeof name === 'string' && /^[A-Za-z0-9._-]+\.apk$/.test(name);
function validateEntry(entry) {
  if (!entry || !safeName(entry.name) || !/^[a-f0-9]{64}$/.test(entry.sha256) || !Number.isSafeInteger(entry.bytes) || entry.bytes <= 0) throw new Error('adapter 返回的文件身份不完整');
  return entry;
}
function same(actual, expected) { return actual?.sha256 === expected.sha256 && actual?.bytes === expected.bytes; }
function atomicState(path, state) {
  const target = resolve(path);
  const temporary = `${target}.tmp`;
  // 两条绝对路径都来自固定候选目录，只在同目录替换状态文件。
  if (resolve(temporary, '..') !== resolve(target, '..')) throw new Error('状态文件路径越界');
  writeFileSync(temporary, json(state), { mode: 0o600 });
  renameSync(temporary, target);
}
async function listing(adapter, directory) {
  const entries = await adapter.list({ directory });
  if (!Array.isArray(entries)) throw new Error('adapter.list 必须返回数组');
  const apks = entries.filter(entry => entry.name?.endsWith('.apk')).map(validateEntry);
  if (new Set(apks.map(entry => entry.name)).size !== apks.length) throw new Error(`${directory}: 同名 APK 不可判定`);
  return apks;
}
function get(entries, expected) {
  const found = entries.find(entry => entry.name === expected.name);
  if (found && !same(found, expected)) throw new Error(`不可覆盖同名不同内容 APK: ${expected.name}`);
  return found;
}
function requirePair(entries) {
  if (entries.length === 0) return;
  const system = entries.filter(entry => !entry.name.endsWith('-webview.apk'));
  const gecko = entries.filter(entry => entry.name.endsWith('-webview.apk'));
  if (entries.length !== 2 || system.length !== 1 || gecko.length !== 1 || system[0].name.replace(/\.apk$/, '-webview.apk') !== gecko[0].name) throw new Error('当前最新版不是完整旧双包，须先人工恢复目录状态');
}
/** Adapter只提供list/upload/move；所有方法必须支持按name+sha256+bytes重试，不允许覆盖。 */
export async function advanceRelease(candidateDir, phase, adapter, { allowDevelopment = false } = {}) {
  if (!['publish', 'promote', 'archive'].includes(phase)) throw new Error(`非法发布阶段: ${phase}`);
  const directory = resolve(candidateDir);
  const manifestPath = join(directory, 'release-manifest.json');
  const manifestBytes = readFileSync(manifestPath);
  const manifest = JSON.parse(manifestBytes);
  if (manifest.schemaVersion !== 1 || !/^[a-f0-9]{64}$/.test(manifest.releaseId) || manifest.apks?.length !== 2) throw new Error('候选 manifest 无效');
  if ((manifest.development || manifest.source?.dirty || manifest.sourceBinding !== 'source-record-and-artifact-time') && !allowDevelopment) throw new Error('开发/dirty/未绑定源码候选禁止正式分发');
  const expected = manifest.apks.map(apk => ({ name: apk.fileName, sha256: apk.sha256, bytes: apk.bytes, file: join(directory, apk.fileName) }));
  requirePair(expected);
  for (const apk of expected) {
    if (!safeName(apk.name)) throw new Error('非法 APK 文件名');
    const actual = reference(apk.file);
    if (!same(actual, apk)) throw new Error(`候选 APK 内容已变化: ${apk.name}`);
  }
  for (const method of ['list', 'upload', 'move']) if (typeof adapter?.[method] !== 'function') throw new Error(`adapter 缺少 ${method}`);
  const statePath = join(directory, 'release-state.json');
  const lockPath = join(directory, 'release-state.lock');
  const lock = openSync(lockPath, 'wx');
  closeSync(lock);
  try {
    const manifestSha256 = sha256(manifestBytes);
    const state = existsSync(statePath) ? JSON.parse(readFileSync(statePath)) : { schemaVersion: 1, manifestSha256, phase: 'checked', completed: {}, errors: [] };
    if (state.schemaVersion !== 1 || state.manifestSha256 !== manifestSha256) throw new Error('发布状态不属于该不可变 manifest');
    const staging = `${DIRECTORIES.candidates}/${manifest.releaseId}`;
    const save = () => atomicState(statePath, state);
    const record = (key, entry) => { state.completed[key] = { name: entry.name, sha256: entry.sha256, bytes: entry.bytes }; save(); };
    try {
      if (phase === 'publish') {
        for (const apk of expected) {
          // 未知结果重试时先查真实远程状态，不能只信本地completed。
          let found = get(await listing(adapter, staging), apk);
          if (!found) {
            await adapter.upload({ directory: staging, ...apk, operationId: `${manifest.releaseId}:upload:${apk.name}` });
            found = get(await listing(adapter, staging), apk);
          }
          if (!found) throw new Error(`上传后未确认候选: ${apk.name}`);
          record(`uploaded:${apk.name}`, found);
        }
        for (const apk of expected) if (!get(await listing(adapter, staging), apk)) throw new Error('候选双包未齐备');
        if (!['promoted', 'archived'].includes(state.phase)) state.phase = 'published';
      } else if (phase === 'promote') {
        const latest = await listing(adapter, DIRECTORIES.latest);
        if (!state.previous) {
          const old = latest.filter(entry => !expected.some(apk => get([entry], apk)));
          requirePair(old);
          state.previous = old;
          save(); // 任何移动前持久化旧双包，断线后仍可继续归档。
        }
        for (const apk of expected) {
          const already = get(latest, apk);
          if (!already && !get(await listing(adapter, staging), apk)) throw new Error('双包未完整上传，禁止提升');
        }
        for (const apk of expected) {
          let found = get(await listing(adapter, DIRECTORIES.latest), apk);
          if (!found) {
            await adapter.move({ from: staging, to: DIRECTORIES.latest, name: apk.name, sha256: apk.sha256, bytes: apk.bytes, operationId: `${manifest.releaseId}:promote:${apk.name}` });
            found = get(await listing(adapter, DIRECTORIES.latest), apk);
          }
          if (!found) throw new Error(`提升后未确认: ${apk.name}`);
          record(`promoted:${apk.name}`, found);
        }
        if (state.phase !== 'archived') state.phase = 'promoted';
      } else {
        if (!state.previous) throw new Error('尚无提升前旧双包快照，禁止归档');
        for (const apk of expected) if (!get(await listing(adapter, DIRECTORIES.latest), apk)) throw new Error('新双包未在当前最新版齐备，禁止归档旧包');
        for (const old of state.previous) {
          let found = get(await listing(adapter, DIRECTORIES.history), old);
          if (!found) {
            if (!get(await listing(adapter, DIRECTORIES.latest), old)) throw new Error(`旧包既不在当前也不在历史目录: ${old.name}`);
            await adapter.move({ from: DIRECTORIES.latest, to: DIRECTORIES.history, name: old.name, sha256: old.sha256, bytes: old.bytes, operationId: `${manifest.releaseId}:archive:${old.name}` });
            found = get(await listing(adapter, DIRECTORIES.history), old);
          }
          if (!found) throw new Error(`归档后未确认: ${old.name}`);
          record(`archived:${old.name}`, found);
        }
        const final = await listing(adapter, DIRECTORIES.latest);
        if (final.length !== 2 || expected.some(apk => !get(final, apk))) throw new Error('归档后当前最新版仍非准确的新双包');
        for (const old of state.previous) if (!get(await listing(adapter, DIRECTORIES.history), old)) throw new Error('历史目录缺少旧包');
        state.phase = 'archived';
      }
      state.lastError = null;
      save();
      return state;
    } catch (error) {
      state.lastError = { phase, message: error.message };
      state.errors.push(state.lastError);
      save();
      throw error;
    }
  } finally {
    // 仅删除本次成功创建且绝对路径已固定的锁，不删除候选或远程对象。
    if (resolve(lockPath) !== join(directory, 'release-state.lock')) throw new Error('锁路径不符');
    unlinkSync(lockPath);
  }
}
