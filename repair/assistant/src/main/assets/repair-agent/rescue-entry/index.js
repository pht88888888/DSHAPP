import { isAbsolute, resolve, dirname, join } from 'node:path';
import { open, lstat, realpath, rename, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readNativeState, validateTheme, writeEvidenceRoute } from './state.js';
const requireEngine = createRequire(process.env.DSH_REPAIR_ENGINE_PACKAGE);
const { TypertRemoteService } = await import(pathToFileURL(requireEngine.resolve('@deepseek-ai/dsh-typert-protocol')).href);
class AndroidRescue extends TypertRemoteService {
  constructor(ctx) { super(ctx, 'androidRescue'); }
  authorize() { return requestAuthorization(); }
  disconnect() { return requestDisconnect(); }
  status() { return readConnectionState(); }
  validateUi() { return publishControl('validateUi'); }
  themeSnapshot(theme) { return storeTheme(theme); }
  async bindSession(sessionId) {
    if (typeof sessionId !== 'string' || sessionId.length > 256 || !sessionId.trim()) throw new Error('REPAIR_SESSION_INVALID');
    const state = await readNativeState(true);
    if (state.phase !== 'connected' || !state.binding) throw new Error('REPAIR_AUTHORIZATION_REQUIRED');
    // Routing only: never grants native access, changes target or creates a session.
    await writeEvidenceRoute(state.binding, sessionId);
    return { bound: true };
  }
}
import { constants } from 'node:fs';

export const name = 'dsh-android-rescue-entry';
export const inject = ['typert', 'systemPrompt'];
async function publishControl(action) {
  if (!['authorize', 'disconnect', 'validateUi'].includes(action)) throw new Error('REPAIR_CONTROL_ACTION_INVALID');
  const path = process.env.DSH_REPAIR_CONTROL_FILE;
  if (!path || !isAbsolute(path) || resolve(path) !== path) throw new Error('REPAIR_CONTROL_UNAVAILABLE');
  const parent = dirname(path);
  const stat = await lstat(parent);
  if (!stat.isDirectory() || stat.isSymbolicLink() || await realpath(parent) !== parent) throw new Error('REPAIR_CONTROL_UNAVAILABLE');
  try { await lstat(path); return { requested: true }; }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const temporary = `${path}.${randomUUID()}.tmp`;
  try {
    const fd = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try { await fd.writeFile(JSON.stringify({ action }) + '\n'); await fd.sync(); }
    finally { await fd.close(); }
    await rename(temporary, path);
  } finally { await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; }); }
  return { requested: true };
}
export const requestAuthorization = () => publishControl('authorize');
export const requestDisconnect = () => publishControl('disconnect');
export const readConnectionState = () => readNativeState();
export async function storeTheme(theme) {
  const value = validateTheme(theme);
  const control = process.env.DSH_REPAIR_CONTROL_FILE;
  if (!control || !isAbsolute(control) || resolve(control) !== control) throw new Error('REPAIR_CONTROL_UNAVAILABLE');
  const parent = dirname(control), stat = await lstat(parent);
  if (!stat.isDirectory() || stat.isSymbolicLink() || await realpath(parent) !== parent) throw new Error('REPAIR_CONTROL_UNAVAILABLE');
  const file = join(parent, 'theme-snapshot.json'), temporary = `${file}.${randomUUID()}.tmp`;
  try {
    const fd = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try { await fd.writeFile(JSON.stringify(value)); await fd.sync(); } finally { await fd.close(); }
    await rename(temporary, file);
  } finally { await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; }); }
  return { stored: true };
}
export function remoteParameters(method) {
  if (!['themeSnapshot','bindSession'].includes(method)) return [];
  const name = method === 'themeSnapshot' ? 'theme' : 'sessionId';
  return [{name,wire:name,source:'json',codec:{mode:'strict',typeSymbol:`@dsh-android/repair-profile/${name}`,create:()=>({parse:value=>{
    if (name === 'theme') return validateTheme(value);
    if (typeof value !== 'string' || !value.trim() || value.length > 256) throw new Error('REPAIR_SESSION_INVALID');
    return value;
  }})}}];
}
export function apply(ctx) {
  // User-only authority entry. No corresponding model tool and no target/PID/grant arguments.
  new AndroidRescue(ctx);
  ctx.systemPrompt.section({
    name: 'android-rescue-target-scope', order: ctx.systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_SUFFIX') + 1,
    text: () => {
      return 'You are running inside the separate DSH rescue assistant app com.dsh.fixhelp, not the original applications com.dsh.android (System) or com.dsh.webview (Gecko). Normal modes retain their normal tools for this assistant workspace. Errors or screenshots naming an original application concern that external target, not this assistant. Access or modification of an original target must use native rescue tools only. If the current preset has no rescue tools, guide the user to Settings → 原 DSH 连接 → 开始修复; do not invent tools or use ordinary filesystem/shell tools against the target. If authorized rescue file operations repeatedly return io_error, report a target-side file channel failure, not presumed authorization expiry. Do not repeat unchanged failed requests, inspect this assistant\'s own engine/logs/APK as a substitute, or prescribe reauthorization/target launch without evidence. Never assert exact target file contents (including shebang position, blank lines or BOM) until read_file succeeds. ';
    },
  });
  ctx.typert.register({
    package: '@dsh-android/repair-profile', face: 'host', model: {}, schemas: [],
    invocations: ['authorize', 'disconnect', 'status', 'validateUi', 'themeSnapshot', 'bindSession'].map(method => ({ id: `@dsh-android/repair-profile#${method}`, service: 'androidRescue', namespace: 'androidRescue', method, parameters: remoteParameters(method), result: { mode: 'src-json' }, invocation: { kind: 'direct' } })),
  });
}
