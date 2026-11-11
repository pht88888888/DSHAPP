#!/usr/bin/env node
/**
 * 内置插件统一构建与检查；只读源码，产物写显式目录，--install-assets 才同步到 Android 资产。
 * node tools/build-plugin-components.mjs build --out-dir /tmp/plugin-assets
 * node tools/build-plugin-components.mjs build --out-dir app/build/generated/plugin-components --install-assets
 * node tools/build-plugin-components.mjs check --out-dir /tmp/plugin-assets --component market
 * node tools/build-plugin-components.mjs check --component shell-tool # 比较现有 Android 资产
 * node tools/build-plugin-components.mjs pack-check [--component core-memory]
 * node --test tools/test-plugin-components.mjs
 *
 * 客户端固定 chrome73；clientBundles 仅合并本组件局部源码，官方依赖仍由 factory(require) 延迟取得。
 * zip 固定排序、时间(1980-01-01)、权限和 STORE 编码，跨时区/mtime 可复现。
 * manifest 单独落盘，保持 Android 旧 zip 条目布局；版本由 package.version+源码摘要生成。
 * pack-check 在临时副本执行真实 npm pack --ignore-scripts --offline，再解包逐文件检查。
 */
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { inflateRawSync } from 'node:zlib';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const DEFAULT_CATALOG = join(ROOT, 'catalogs/plugin-components.json');
const ASSETS = 'app/src/main/assets';
export function readEngineConstants(root = ROOT) {
  const base = ['runtime/engine/src/main/java/com/dsh/android/engine', 'app/src/main/java/com/dsh/android/engine']
    .map((directory) => join(root, directory))
    .find((directory) => existsSync(join(directory, 'EngineConstants.kt')));
  if (!base) throw new Error('Missing engine constants source');
  const files = [join(base, 'EngineConstants.kt')];
  const directory = join(base, 'constants');
  if (existsSync(directory)) files.push(...sourceFiles(directory).filter((file) => file.endsWith('.kt')).map((file) => join(directory, file)));
  return files.map((file) => readFileSync(file, 'utf8')).join('\n');
}
const hash = (data) => createHash('sha256').update(data).digest('hex');
const json = (data) => `${JSON.stringify(data, null, 2)}\n`;
const sorted = (items) => [...items].sort();

function safePath(path) {
  if (typeof path !== 'string' || !path || path.includes('\\') || path.includes('\0') || isAbsolute(path) || path.split('/').some((part) => !part || part === '.' || part === '..')) {
    throw new Error(`非法相对路径: ${path}`);
  }
  return path;
}

function inside(root, path) {
  const target = resolve(root, safePath(path));
  if (existsSync(target)) {
    const rel = relative(realpathSync(root), realpathSync(target));
    if (rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel)) throw new Error(`路径越界: ${path}`);
  }
  return target;
}

function sourceFiles(root, prefix = '') {
  const files = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === '.git') continue;
    const path = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isSymbolicLink()) throw new Error(`源码不接受符号链接: ${path}`);
    if (entry.isDirectory()) files.push(...sourceFiles(join(root, entry.name), path));
    else if (entry.isFile()) files.push(path);
  }
  return sorted(files);
}

export function loadCatalog(root = ROOT, catalogPath = DEFAULT_CATALOG) {
  const catalog = JSON.parse(readFileSync(catalogPath, 'utf8'));
  if (catalog.schemaVersion !== 1 || catalog.target !== 'chrome73' || !Array.isArray(catalog.components) || !catalog.components.length) throw new Error('不支持的组件清单或 target（必须 chrome73）');
  const ids = new Set();
  const assets = new Set();
  const constants = readEngineConstants(root);
  for (const component of catalog.components) {
    if (!/^[a-z0-9-]+$/.test(component.id) || ids.has(component.id)) throw new Error(`重复或非法组件 id: ${component.id}`);
    ids.add(component.id);
    safePath(component.asset);
    if (!component.asset.endsWith('.zip') || component.asset.includes('/') || assets.has(component.asset)) throw new Error(`重复或非法资产: ${component.asset}`);
    assets.add(component.asset);
    if (!/^[A-Z_]+$/.test(component.assetConstant) || !new RegExp(`const val ${component.assetConstant} = "${component.asset.replaceAll('.', '\\.')}"`).test(constants)) throw new Error(`${component.id}: 资产与 EngineConstants 不一致`);
    const directory = inside(root, component.source);
    if (!Array.isArray(component.files) || !component.files.includes('package.json')) throw new Error(`${component.id}: 缺少 package.json`);
    const files = [...component.files, ...(component.npmOnlyFiles || [])];
    if (new Set(files).size !== files.length) throw new Error(`${component.id}: 重复文件`);
    for (const file of files) {
      const path = inside(directory, file);
      if (!lstatSync(path).isFile() || lstatSync(path).isSymbolicLink()) throw new Error(`${component.id}: 文件无效 ${file}`);
    }
    const clients = component.clientFiles || [];
    for (const file of clients) if (!component.files.includes(file) || !file.endsWith('.js')) throw new Error(`${component.id}: 非法客户端条目 ${file}`);
    if (component.clientBundles && (!Array.isArray(component.clientBundles) || new Set(component.clientBundles).size !== component.clientBundles.length)) throw new Error(`${component.id}: 非法客户端合并清单`);
    for (const file of component.clientBundles || []) if (!clients.includes(file)) throw new Error(`${component.id}: 合并入口必须属于 clientFiles: ${file}`);
    for (const file of sourceFiles(directory).filter((file) => file.startsWith('lib/'))) {
      if (!component.files.includes(file)) throw new Error(`${component.id}: lib 文件未纳入 zip 清单: ${file}`);
    }
  }
  for (const component of catalog.components) {
    if (!component.componentIds) continue;
    if (!Array.isArray(component.componentIds) || !component.componentIds.length || new Set(component.componentIds).size !== component.componentIds.length) throw new Error(`${component.id}: 非法聚合组件清单`);
    component.members = component.componentIds.map((id) => {
      const member = catalog.components.find((candidate) => candidate.id === id);
      if (!member || member.componentIds || member.bundledIn !== component.id) throw new Error(`${component.id}: 非法聚合成员 ${id}`);
      return member;
    });
  }
  for (const component of catalog.components) if (component.bundledIn && !catalog.components.some((bundle) => bundle.id === component.bundledIn && bundle.componentIds?.includes(component.id))) throw new Error(`${component.id}: 聚合归属不一致`);
  return catalog;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, ...options });
  if (result.error || result.status !== 0) throw new Error(`${command} 执行失败: ${result.error?.message || result.stderr || result.stdout || result.status}`);
  return result.stdout;
}

export function findEsbuild() {
  if (process.env.DSH_ESBUILD_BINARY) return resolve(process.env.DSH_ESBUILD_BINARY);
  const require = createRequire(join(ROOT, 'tools/package.json'));
  try {
    const packagePath = require.resolve('esbuild/package.json');
    const binary = join(dirname(packagePath), 'bin/esbuild');
    if (existsSync(binary)) return binary;
  } catch { /* 复用既有 compat 构建工具的持久安装目录。 */ }
  const modules = join(tmpdir(), 'dsh-esbuild-tool/node_modules');
  const key = `${process.platform}-${process.arch}`;
  const candidates = [
    join(modules, '@esbuild', key, process.platform === 'win32' ? 'esbuild.exe' : 'bin/esbuild'),
    join(modules, 'esbuild', 'bin/esbuild'),
  ];
  const binary = candidates.find((path) => existsSync(path));
  if (!binary) throw new Error('未找到既有 esbuild；请用 DSH_ESBUILD_BINARY 指定已安装二进制（本工具不安装依赖）');
  return binary;
}

function prepareBundle(component, options) {
  const { root = ROOT, target = 'chrome73' } = options;
  const directory = inside(root, component.source);
  const ownSources = sourceFiles(directory).map((path) => ({ path, content: readFileSync(inside(directory, path)) }));
  const pkg = JSON.parse(ownSources.find((file) => file.path === 'package.json').content);
  const files = new Map(ownSources.filter((file) => component.files.includes(file.path)).map(({ path, content }) => [path, content]));
  const sourceInputs = ownSources.map(({ path, content }) => ({ path: `${component.source}/${path}`, content }));
  const inserts = [];
  const clients = [];
  const dependencies = {};
  const bundledDependencies = [];
  let esbuildVersion = null;
  for (const member of component.members) {
    const prepared = prepareComponent(member, options);
    sourceInputs.push(...prepared.sources.map(({ path, content }) => ({ path: `${member.source}/${path}`, content })));
    const prefix = `node_modules/${safePath(prepared.pkg.name)}/`;
    // 标准自带依赖保留原包名及元数据；官方 runtime 从 bundle anchor 收集依赖闭包。
    for (const [path, content] of prepared.files) files.set(`${prefix}${path}`, content);
    for (const path of member.clientFiles || []) clients.push(`${prefix}${path}`);
    const host = prepared.pkg.main?.replace(/^\.\//, '');
    if (!host || !prepared.files.has(host)) throw new Error(`${member.id}: 聚合成员缺少 host 入口`);
    dependencies[prepared.pkg.name] = prepared.pkg.version;
    bundledDependencies.push(prepared.pkg.name);
    const patchPath = prepared.pkg.dsh?.bundle?.patch?.replace(/^\.\//, '');
    const patch = prepared.files.get(patchPath)?.toString();
    if (!patch || !/^- insert:\r?\n/.test(patch) || (patch.match(/^      name:/gm) || []).length !== 1) throw new Error(`${member.id}: 聚合只接受单条平铺 insert patch`);
    inserts.push(patch.replace(/^- insert:\r?\n/, '').trimEnd());
    esbuildVersion ||= prepared.manifest.esbuildVersion;
  }
  files.set('cordis.patch.yml', Buffer.from(`- insert:\n${inserts.join('\n')}\n`));
  pkg.dependencies = dependencies;
  pkg.bundledDependencies = bundledDependencies;
  files.set('package.json', Buffer.from(json(pkg)));
  const sources = [...files].map(([path, content]) => ({ path, content }));
  sourceInputs.sort((a, b) => a.path.localeCompare(b.path, 'en'));
  const sourceDigest = hash(JSON.stringify(sourceInputs.map(({ path, content }) => [path, hash(content)])));
  const manifest = {
    id: component.id, package: pkg.name, version: pkg.version,
    revision: `${pkg.version}+sha256.${sourceDigest}`, source: component.source, sourceDigest,
    sourceRoot: 'repository',
    sourceFiles: sourceInputs.map(({ path, content }) => ({ path, sha256: hash(content) })),
    asset: component.asset, target, esbuildVersion,
    components: component.members.map(({ id }) => id),
    files: [...files].map(([path, content]) => ({ path, size: content.length, sha256: hash(content) })),
  };
  return { component: { ...component, files: [...files.keys()], clientFiles: clients }, directory, sources, pkg, files, manifest };
}

export function compileClient(directory, path, { target = 'chrome73', esbuildBinary = findEsbuild(), bundle = false } = {}) {
  const content = readFileSync(inside(directory, path));
  if (!bundle) return Buffer.from(run(esbuildBinary, [`--target=${target}`, '--log-level=error', `--sourcefile=${path}`], { input: content }));
  const temporary = mkdtempSync(join(tmpdir(), 'dsh-client-modules-'));
  try {
    const metadataPath = join(temporary, 'metafile.json');
    const outputPath = join(temporary, 'client.js');
    run(esbuildBinary, [path, '--bundle', '--packages=external', '--format=iife', '--tree-shaking=false', `--target=${target}`, '--log-level=error', `--metafile=${metadataPath}`, `--outfile=${outputPath}`], { cwd: directory });
    const metadata = JSON.parse(readFileSync(metadataPath, 'utf8'));
    for (const input of Object.keys(metadata.inputs)) {
      const resolved = realpathSync(resolve(directory, input));
      const local = relative(realpathSync(directory), resolved);
      if (local === '..' || local.startsWith(`..${sep}`) || isAbsolute(local) || local.split(sep).includes('node_modules')) throw new Error(`客户端仅允许本组件局部模块: ${input}`);
    }
    for (const output of Object.values(metadata.outputs)) {
      if (output.imports.some(({ external }) => external)) throw new Error('客户端官方依赖必须通过 ModuleLoader factory(require) 延迟取得，禁止静态外部 import');
    }
    return readFileSync(outputPath);
  } finally {
    const resolved = resolve(temporary);
    if (dirname(resolved) !== resolve(tmpdir()) || !resolved.startsWith(join(resolve(tmpdir()), 'dsh-client-modules-'))) throw new Error('临时客户端构建目录无效');
    rmSync(resolved, { recursive: true, force: true });
  }
}

export function prepareComponent(component, { root = ROOT, target = 'chrome73', esbuildBinary } = {}) {
  if (component.componentIds) return prepareBundle(component, { root, target, esbuildBinary });
  const directory = inside(root, component.source);
  const sources = sourceFiles(directory).map((path) => ({ path, content: readFileSync(inside(directory, path)) }));
  const sourceMap = new Map(sources.map(({ path, content }) => [path, content]));
  const pkg = JSON.parse(sourceMap.get('package.json').toString());
  if (typeof pkg.name !== 'string' || typeof pkg.version !== 'string' || !pkg.version) throw new Error(`${component.id}: package.name/version 缺失`);
  const sourceDigest = hash(JSON.stringify(sources.map(({ path, content }) => [path, hash(content)])));
  const files = new Map();
  const clients = component.clientFiles || [];
  let esbuildVersion = null;
  if (clients.length) {
    esbuildBinary ||= findEsbuild();
    esbuildVersion = run(esbuildBinary, ['--version']).trim();
  }
  for (const path of sorted(component.files)) {
    let content = sourceMap.get(path);
    if (clients.includes(path)) {
      content = compileClient(directory, path, { target, esbuildBinary, bundle: (component.clientBundles || []).includes(path) });
    }
    files.set(path, content);
  }
  const manifest = {
    id: component.id,
    package: pkg.name,
    version: pkg.version,
    revision: `${pkg.version}+sha256.${sourceDigest}`,
    source: component.source,
    sourceDigest,
    sourceFiles: sources.map(({ path, content }) => ({ path, sha256: hash(content) })),
    asset: component.asset,
    target,
    esbuildVersion,
    files: [...files].map(([path, content]) => ({ path, size: content.length, sha256: hash(content) })),
  };
  return { component, directory, sources, pkg, files, manifest };
}

function crc32(data) {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

export function createZip(files) {
  const local = [];
  const central = [];
  let offset = 0;
  for (const path of sorted(files.keys())) {
    safePath(path);
    const name = Buffer.from(path);
    const data = files.get(path);
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(0x800, 6); // UTF-8，STORE，时间固定零点。
    header.writeUInt16LE(33, 12); // DOS date: 1980-01-01。
    header.writeUInt32LE(crc32(data), 14);
    header.writeUInt32LE(data.length, 18);
    header.writeUInt32LE(data.length, 22);
    header.writeUInt16LE(name.length, 26);
    local.push(header, name, data);
    const record = Buffer.alloc(46);
    record.writeUInt32LE(0x02014b50);
    record.writeUInt16LE(0x314, 4); // Unix。
    header.copy(record, 6, 4, 30);
    record.writeUInt32LE((0o100644 * 65536) >>> 0, 38);
    record.writeUInt32LE(offset, 42);
    central.push(record, name);
    offset += header.length + name.length + data.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(files.size, 8);
  end.writeUInt16LE(files.size, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}

/** 从中央目录读取，兼容现有手工 ZIP 的 STORE/DEFLATE 与目录条目。 */
export function readZip(data) {
  let end = data.length - 22;
  const minimum = Math.max(0, data.length - 65557);
  while (end >= minimum && (data.readUInt32LE(end) !== 0x06054b50 || end + 22 + data.readUInt16LE(end + 20) !== data.length)) end--;
  if (end < minimum) throw new Error('ZIP 缺少结束记录');
  if (data.readUInt16LE(end + 4) || data.readUInt16LE(end + 6) || data.readUInt16LE(end + 8) !== data.readUInt16LE(end + 10)) throw new Error('不支持多卷 ZIP');
  const files = new Map();
  const seen = new Set();
  let cursor = data.readUInt32LE(end + 16);
  for (let index = 0; index < data.readUInt16LE(end + 10); index++) {
    if (data.readUInt32LE(cursor) !== 0x02014b50) throw new Error('ZIP 中央目录损坏');
    const flags = data.readUInt16LE(cursor + 8);
    const method = data.readUInt16LE(cursor + 10);
    const crc = data.readUInt32LE(cursor + 16);
    const size = data.readUInt32LE(cursor + 20);
    const originalSize = data.readUInt32LE(cursor + 24);
    const nameLength = data.readUInt16LE(cursor + 28);
    const path = data.subarray(cursor + 46, cursor + 46 + nameLength).toString();
    const localOffset = data.readUInt32LE(cursor + 42);
    cursor += 46 + nameLength + data.readUInt16LE(cursor + 30) + data.readUInt16LE(cursor + 32);
    safePath(path.endsWith('/') ? path.slice(0, -1) : path);
    if (seen.has(path)) throw new Error(`ZIP 重复条目: ${path}`);
    seen.add(path);
    if (flags & 1 || ![0, 8].includes(method)) throw new Error(`不支持的 ZIP 编码: ${path}`);
    if (data.readUInt32LE(localOffset) !== 0x04034b50) throw new Error(`ZIP 本地记录损坏: ${path}`);
    const localNameLength = data.readUInt16LE(localOffset + 26);
    const localName = data.subarray(localOffset + 30, localOffset + 30 + localNameLength).toString();
    if (localName !== path) throw new Error(`ZIP 条目名称不一致: ${path}`);
    const start = localOffset + 30 + localNameLength + data.readUInt16LE(localOffset + 28);
    const packed = data.subarray(start, start + size);
    const content = method === 0 ? packed : inflateRawSync(packed);
    if (content.length !== originalSize || crc32(content) !== crc) throw new Error(`ZIP 内容校验失败: ${path}`);
    if (!path.endsWith('/')) files.set(path, content);
  }
  return files;
}

export function compareFiles(expected, actual, label) {
  const errors = [];
  for (const [path, content] of expected) {
    if (!actual.has(path)) errors.push(`缺少 ${path}`);
    else if (!content.equals(actual.get(path))) errors.push(`内容不同 ${path}`);
  }
  for (const path of actual.keys()) if (!expected.has(path)) errors.push(`多余 ${path}`);
  if (errors.length) throw new Error(`${label}:\n${errors.join('\n')}`);
}

function entryPaths(pkg) {
  const entries = [];
  function collect(value) {
    if (typeof value === 'string') entries.push(value.replace(/^\.\//, ''));
    else if (value && typeof value === 'object') Object.values(value).forEach(collect);
  }
  collect(pkg.main);
  collect(pkg.exports);
  collect(pkg.dsh?.bundle?.patch);
  return [...new Set(entries)];
}

function temporary(action) {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-plugin-components-'));
  try { return action(directory); }
  finally {
    const resolved = resolve(directory);
    if (dirname(resolved) !== resolve(tmpdir()) || !resolved.startsWith(join(resolve(tmpdir()), 'dsh-plugin-components-'))) throw new Error(`拒绝清理意外路径: ${resolved}`);
    rmSync(resolved, { recursive: true, force: true });
  }
}

export function checkNpmPack(prepared, { verifyTarball } = {}) {
  return temporary((work) => {
    const stage = join(work, 'source');
    mkdirSync(stage);
    for (const { path, content } of prepared.sources) {
      const target = inside(stage, path);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, prepared.files.get(path) || content);
    }
    const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
    const output = run(npm, ['pack', '--json', '--ignore-scripts', '--offline', '--pack-destination', work, '--cache', join(work, 'cache')], { cwd: stage, shell: process.platform === 'win32' });
    let report;
    try { [report] = JSON.parse(output); } catch { throw new Error(`npm pack 返回无效 JSON: ${output}`); }
    if (!report || report.name !== prepared.pkg.name || report.version !== prepared.pkg.version) throw new Error(`${prepared.component.id}: npm pack 包信息不一致`);
    const expected = new Map(prepared.files);
    for (const path of prepared.component.npmOnlyFiles || []) expected.set(path, readFileSync(inside(prepared.directory, path)));
    for (const path of entryPaths(prepared.pkg)) if (!expected.has(path)) throw new Error(`${prepared.component.id}: 入口不在组件清单: ${path}`);
    const allowed = expected;
    const actual = new Map();
    const tarball = inside(work, report.filename);
    const archiveNames = run('tar', ['-tzf', tarball]).trim().split(/\r?\n/).filter(Boolean);
    const reported = sorted(report.files.map((file) => `package/${safePath(file.path)}`));
    if (JSON.stringify(sorted(archiveNames)) !== JSON.stringify(reported)) throw new Error(`${prepared.component.id}: npm pack 文件报告与 tarball 不一致`);
    const extract = join(work, 'extract');
    mkdirSync(extract);
    // 先验证全部条目，再解包；只接受 package/ 前缀和普通文件。
    for (const name of archiveNames) {
      if (!name.startsWith('package/')) throw new Error(`npm tarball 非法前缀: ${name}`);
      safePath(name);
    }
    run('tar', ['-xzf', tarball, '-C', extract]);
    for (const file of report.files) {
      if (!allowed.has(file.path)) throw new Error(`${prepared.component.id}: npm 包含意外文件 ${file.path}`);
      const path = inside(join(extract, 'package'), file.path);
      if (!lstatSync(path).isFile() || lstatSync(path).isSymbolicLink()) throw new Error(`npm tarball 非普通文件: ${file.path}`);
      const content = readFileSync(path);
      if (!content.equals(allowed.get(file.path))) throw new Error(`${prepared.component.id}: npm 内容不同 ${file.path}`);
      actual.set(file.path, content);
    }
    for (const [path, content] of expected) if (!actual.get(path)?.equals(content)) throw new Error(`${prepared.component.id}: npm 包缺少或修改了 ${path}`);
    verifyTarball?.({ tarball, directory: join(extract, 'package'), report });
    return { id: prepared.component.id, package: report.name, version: report.version, files: actual.size, private: prepared.pkg.private === true };
  });
}

/** 安装前先完整校验本次选择的全部 zip 与 manifest，再写入实际目标，保留现有 symlink。 */
export function installAssets(preparedComponents, outDir, assetDir) {
  const pending = [];
  for (const prepared of preparedComponents) {
    const zip = readFileSync(join(outDir, prepared.component.asset));
    compareFiles(prepared.files, readZip(zip), prepared.component.id);
    if (hash(zip) !== prepared.manifest.assetSha256) throw new Error(`${prepared.component.id}: zip 摘要不一致`);
    const manifestName = `${prepared.component.id}.manifest.json`;
    const manifest = readFileSync(join(outDir, manifestName));
    if (manifest.toString() !== json(prepared.manifest)) throw new Error(`${prepared.component.id}: manifest 与源码/工具版本不一致`);
    pending.push([join(assetDir, prepared.component.asset), zip], [join(assetDir, manifestName), manifest]);
  }
  // 先解析全部目标；悬空 symlink 会在写入前失败，避免替换共享链接或写到错误位置。
  const targets = pending.map(([path, content]) => {
    let stat;
    try { stat = lstatSync(path); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (stat && !stat.isSymbolicLink() && !stat.isFile()) throw new Error(`资产目标不是普通文件: ${path}`);
    const target = stat?.isSymbolicLink() ? realpathSync(path) : path;
    return [target, content];
  });
  if (new Set(targets.map(([path]) => resolve(path))).size !== targets.length) throw new Error('资产安装目标重复');
  mkdirSync(assetDir, { recursive: true });
  for (const [target, content] of targets) {
    if (!existsSync(target) || !readFileSync(target).equals(content)) writeFileSync(target, content);
  }
}

function usage() {
  return '用法: node tools/build-plugin-components.mjs <build|check|pack-check> [--component id] [--out-dir 目录] [--install-assets]\n构建必须指定新产物目录；build --install-assets 在全部产物校验后同步 zip+manifest 到 Android assets 并保留 symlink；check 省略目录时只读比较现有 Android 资产；pack-check 在临时副本检查并清理。';
}

export function main(argv = process.argv.slice(2), { root = ROOT, catalogPath = DEFAULT_CATALOG } = {}) {
  if (argv.includes('--help')) { console.log(usage()); return; }
  const mode = argv[0];
  if (!['build', 'check', 'pack-check'].includes(mode)) throw new Error(usage());
  let componentId;
  let outDir;
  let install = false;
  for (let index = 1; index < argv.length; index++) {
    const flag = argv[index];
    if (flag === '--install-assets') { install = true; continue; }
    if (!['--component', '--out-dir'].includes(flag) || !argv[index + 1] || argv[index + 1].startsWith('--')) throw new Error(`无效参数: ${flag}\n${usage()}`);
    if (flag === '--component') componentId = argv[++index];
    else outDir = resolve(argv[++index]);
  }
  if (mode === 'build' && !outDir) throw new Error('build 必须显式指定 --out-dir，避免覆盖现有资产');
  if (install && mode !== 'build') throw new Error('--install-assets 仅用于 build，必须先构建并校验全部产物');
  if (mode === 'pack-check' && outDir) throw new Error('pack-check 不保留产物，无需 --out-dir');
  const assetDir = resolve(root, ASSETS);
  if (mode === 'build' && (outDir === assetDir || existsSync(outDir) && realpathSync(outDir) === realpathSync(assetDir))) throw new Error('本工具禁止直接覆盖仓库现有资产，请指定独立产物目录');
  const catalog = loadCatalog(root, catalogPath);
  const components = catalog.components.filter((component) => componentId ? component.id === componentId : !component.bundledIn);
  if (!components.length) throw new Error(`未知组件: ${componentId}`);
  const manifests = [];
  const preparedComponents = [];
  const failures = [];
  for (const component of components) {
    try {
      const prepared = prepareComponent(component, { root, target: catalog.target });
      if (mode === 'pack-check') {
        const report = checkNpmPack(prepared);
        console.log(`${component.id}: npm pack 已检查 ${report.files} 个文件${report.private ? '（private 包，仅检查内容）' : ''}`);
        continue;
      }
      const zip = createZip(prepared.files);
      prepared.manifest.assetSha256 = hash(zip);
      manifests.push(prepared.manifest);
      preparedComponents.push(prepared);
      const directory = outDir || assetDir;
      const zipPath = join(directory, component.asset);
      const manifestPath = join(directory, `${component.id}.manifest.json`);
      if (mode === 'build') {
        mkdirSync(directory, { recursive: true });
        writeFileSync(zipPath, zip);
        writeFileSync(manifestPath, json(prepared.manifest));
        if (component.componentIds) {
          const stage = join(directory, 'npm', prepared.pkg.name);
          // 可直接在该目录 npm pack；只暂存运行文件，不执行源码包脚本。
          if (existsSync(stage)) {
            const intended = resolve(directory, 'npm', prepared.pkg.name);
            if (resolve(stage) !== intended || realpathSync(stage) !== intended || !prepared.pkg.name.match(/^[a-z0-9-]+$/)) throw new Error(`拒绝清理意外 npm 暂存目录: ${stage}`);
            rmSync(stage, { recursive: true });
          }
          for (const [path, content] of prepared.files) {
            const stagedPath = inside(stage, path);
            mkdirSync(dirname(stagedPath), { recursive: true });
            writeFileSync(stagedPath, content);
          }
        }
      } else {
        compareFiles(prepared.files, readZip(readFileSync(zipPath)), component.id);
        if (outDir && readFileSync(manifestPath, 'utf8') !== json(prepared.manifest)) throw new Error(`${component.id}: manifest 与源码/工具版本不一致`);
      }
      console.log(`${component.id}: ${mode === 'build' ? '已构建' : '逐文件一致'} ${prepared.files.size} 个文件，${prepared.manifest.revision}`);
    } catch (error) { failures.push(`${component.id}: ${error.message}`); }
  }
  if (failures.length) throw new Error(failures.join('\n'));
  if (install) {
    installAssets(preparedComponents, outDir, assetDir);
    console.log(`已同步 ${preparedComponents.length} 个经校验组件及 manifest 到 Android assets（保留符号链接）`);
  }
  return manifests;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try { main(); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
