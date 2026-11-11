#!/usr/bin/env node
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { inspectGateReports, inspectPair, inspectRuntimeLock, json, reference, sha256, sourceSnapshot, verifySourceRecord } from './inspect.mjs';
import { advanceRelease } from './workflow.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const HELP = `DSH Android 分步发布工具（不构建、不默认连接远程）
source-record --out FILE                           构建前记录源码状态
inspect --system APK --gecko APK --generated-dir DIR [--development]
prepare --out DIR --source-record FILE --gate-report FILE [--development]
check --candidate DIR                              重验不可变候选
publish|promote|archive --candidate DIR --adapter MODULE [--development]
通用参数：--root DIR --runtime-lock FILE --catalog FILE --generated-dir DIR
          --system APK --gecko APK --sdk DIR --aapt FILE --apksigner FILE
--development 允许dirty/未绑定源码，仅生成开发候选；正式流程拒绝。
远程阶段必须显式提供本地adapter模块；本仓库不附带真实网盘adapter。
`;
function argumentsOf(argv) {
  const [command = 'help', ...args] = argv;
  const values = { command, gateReport: [] };
  const allowed = new Set(['out', 'root', 'runtime-lock', 'catalog', 'generated-dir', 'system', 'gecko', 'sdk', 'aapt', 'apksigner', 'build-tools', 'source-record', 'gate-report', 'candidate', 'adapter']);
  for (let i = 0; i < args.length; i++) {
    const name = args[i].replace(/^--/, '');
    if (args[i] === '--development') { values.development = true; continue; }
    if (!args[i].startsWith('--') || !allowed.has(name) || !args[i + 1] || args[i + 1].startsWith('--')) throw new Error(`未知参数或缺少值: ${args[i]}`);
    const key = name.replace(/-([a-z])/g, (_, char) => char.toUpperCase());
    if (key === 'gateReport') values.gateReport.push(resolve(args[++i]));
    else values[key] = args[++i];
  }
  return values;
}
function defaults(values) {
  const root = resolve(values.root || ROOT);
  return { ...values, root,
    system: resolve(values.system || join(root, 'app/build/outputs/apk/system/release/app-system-release.apk')),
    gecko: resolve(values.gecko || join(root, 'app/build/outputs/apk/gecko/release/app-gecko-release.apk')),
    catalog: resolve(values.catalog || join(root, 'catalogs/plugin-components.json')),
    generatedDir: resolve(values.generatedDir || join(root, 'app/build/generated/plugin-components')),
    runtimeLock: resolve(values.runtimeLock || join(root, 'catalogs/runtime-assets.lock.json')),
  };
}
function requireValue(value, label) { if (!value) throw new Error(`缺少 ${label}`); return value; }
function createImmutable(path, value) {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(json(value));
  writeFileSync(path, bytes, { flag: 'wx', mode: 0o444 });
}
function runtimeAssets(options) {
  if (!existsSync(options.runtimeLock)) {
    if (!options.development) throw new Error(`缺少运行资产锁: ${options.runtimeLock}`);
    return null;
  }
  return inspectRuntimeLock(options.runtimeLock, options.root);
}
export function prepareCandidate(options) {
  const output = resolve(requireValue(options.out, '--out'));
  if (existsSync(output)) throw new Error('候选目录必须不存在，防止覆盖不可变制品');
  const sourceBefore = sourceSnapshot(options.root);
  if (!options.development && sourceBefore.dirty) throw new Error('正式候选拒绝 dirty 工作树；开发检查请显式 --development');
  const runtime = runtimeAssets(options);
  const apks = inspectPair({ ...options, runtimeAssets: runtime?.assets || [] });
  const sourceProof = options.sourceRecord ? verifySourceRecord(resolve(options.sourceRecord), options.root, apks) : { source: sourceBefore, sourceBinding: 'unverified-existing-artifact', sourceRecord: null };
  if (!options.development && !sourceProof.sourceRecord) throw new Error('正式候选需要构建前 --source-record，不能将旧APK绑定到新源码');
  const gates = inspectGateReports(options.gateReport || [], options.development ? [] : ['L0', 'L1', 'L2', 'L3']);
  if (JSON.stringify(sourceBefore) !== JSON.stringify(sourceSnapshot(options.root))) throw new Error('检查期间源码发生变化，停止生成候选');
  const systemVersion = apks[0].versionName;
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(systemVersion)) throw new Error('非法版本文件名');
  const products = apks.map(apk => ({ ...apk, fileName: `DSH${systemVersion}${apk.flavor === 'gecko' ? '-webview' : ''}.apk` }));
  const releaseId = sha256(json({ source: sourceProof.source, apks: products.map(apk => apk.sha256), runtime: runtime?.sha256, catalog: reference(options.catalog).sha256 }));
  const manifest = { schemaVersion: 1, releaseId, createdAt: new Date().toISOString(), development: options.development === true, label: options.development ? 'DEVELOPMENT — NOT FOR FORMAL DISTRIBUTION' : 'FORMAL CANDIDATE', ...sourceProof, apks: products, runtime, gates, catalog: reference(options.catalog), inputs: { root: options.root, generatedDir: options.generatedDir }, scope: 'Checks attest APK contents and source record; external reports remain separately auditable evidence.' };
  mkdirSync(output, { recursive: true });
  for (const apk of products) {
    copyFileSync(apk.path, join(output, apk.fileName));
    if (reference(join(output, apk.fileName)).sha256 !== apk.sha256) throw new Error('候选复制摘要不一致');
  }
  createImmutable(join(output, 'release-manifest.json'), manifest);
  return manifest;
}
export function checkCandidate(directory, tools = {}) {
  const candidate = resolve(directory);
  const path = join(candidate, 'release-manifest.json');
  const manifest = JSON.parse(readFileSync(path));
  if (manifest.schemaVersion !== 1 || manifest.apks?.length !== 2) throw new Error('候选 manifest 格式错误');
  const apks = Object.fromEntries(manifest.apks.map(apk => [apk.flavor, join(candidate, apk.fileName)]));
  for (const apk of manifest.apks) if (reference(join(candidate, apk.fileName)).sha256 !== apk.sha256) throw new Error(`候选摘要已变化: ${apk.fileName}`);
  if (reference(manifest.catalog.path).sha256 !== manifest.catalog.sha256) throw new Error('候选组件清单已变化');
  if (manifest.runtime && reference(manifest.runtime.path).sha256 !== manifest.runtime.sha256) throw new Error('候选运行资产锁已变化');
  for (const gate of manifest.gates) if (reference(gate.path).sha256 !== gate.sha256) throw new Error('候选门禁报告已变化');
  const options = { ...tools, ...apks, ...manifest.inputs, catalog: manifest.catalog.path, development: manifest.development, runtimeAssets: manifest.runtime?.assets || [] };
  const actual = inspectPair(options);
  for (let i = 0; i < actual.length; i++) if (actual[i].sha256 !== manifest.apks[i].sha256) throw new Error('重新检查的APK不匹配');
  if (JSON.stringify(sourceSnapshot(manifest.inputs.root)) !== JSON.stringify(manifest.source)) throw new Error('源码已变化，候选只能按原记录审计，不能继续发布');
  if (!manifest.development) {
    if (manifest.source.dirty || manifest.sourceBinding !== 'source-record-and-artifact-time') throw new Error('正式候选来源不合格');
    inspectGateReports(manifest.gates.map(gate => gate.path), ['L0', 'L1', 'L2', 'L3']);
  }
  return { status: 'passed', development: manifest.development, releaseId: manifest.releaseId, apks: actual.map(apk => ({ flavor: apk.flavor, sha256: apk.sha256, certificateSha256: apk.certificateSha256, plugins: apk.plugins.length })) };
}
export async function main(argv = process.argv.slice(2)) {
  const options = defaults(argumentsOf(argv));
  if (['help', '--help', '-h'].includes(options.command)) { console.log(HELP); return; }
  if (options.command === 'source-record') {
    const out = resolve(requireValue(options.out, '--out'));
    mkdirSync(dirname(out), { recursive: true });
    createImmutable(out, { schemaVersion: 1, createdAt: new Date().toISOString(), source: sourceSnapshot(options.root) });
    console.log(json({ sourceRecord: out }));
  } else if (options.command === 'inspect') {
    const runtime = runtimeAssets(options);
    const apks = inspectPair({ ...options, verifySources: false, runtimeAssets: runtime?.assets || [] });
    console.log(json({ schemaVersion: 1, sourceBinding: 'unverified-existing-artifact', apks, runtime }));
  } else if (options.command === 'prepare') {
    console.log(json(prepareCandidate(options)));
  } else if (options.command === 'check') {
    console.log(json(checkCandidate(requireValue(options.candidate, '--candidate'), options)));
  } else if (['publish', 'promote', 'archive'].includes(options.command)) {
    requireValue(options.candidate, '--candidate');
    requireValue(options.adapter, '--adapter');
    checkCandidate(options.candidate, options);
    const module = await import(pathToFileURL(resolve(options.adapter)).href);
    if (typeof module.createAdapter !== 'function') throw new Error('adapter 模块必须导出 createAdapter');
    const adapter = await module.createAdapter({ candidate: resolve(options.candidate) });
    console.log(json(await advanceRelease(options.candidate, options.command, adapter, { allowDevelopment: options.development === true })));
  } else throw new Error(`未知阶段: ${options.command}`);
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(error.stack || error.message); process.exitCode = 1; });
