import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, lstatSync, readFileSync, readlinkSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { readZip } from '../../tooling/plugins/build.mjs';

export const sha256 = data => createHash('sha256').update(data).digest('hex');
export const json = value => `${JSON.stringify(value, null, 2)}\n`;
export function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, ...options });
  if (result.error || result.status !== 0) throw new Error(`${command} 失败: ${result.error?.message || result.stderr || result.stdout || result.status}`);
  return result.stdout;
}
export function reference(file) {
  const path = resolve(file);
  const bytes = readFileSync(path);
  return { path, bytes: bytes.length, sha256: sha256(bytes) };
}
export function sourceSnapshot(root) {
  const commit = run('git', ['rev-parse', 'HEAD'], { cwd: root }).trim();
  const status = run('git', ['status', '--porcelain=v1', '--untracked-files=all'], { cwd: root });
  const paths = [...new Set(run('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root }).split('\0').filter(Boolean))].sort();
  const hash = createHash('sha256');
  for (const path of paths) {
    // Git仍记录dirty；源码摘要不读取本地签名/凭据文件的内容。
    if (/(^|\/)(?:\.env(?:\..*)?|keystore\.properties|local\.properties)$|\.(?:jks|keystore|p12|pfx|pem|key)$/i.test(path)) continue;
    const absolute = resolve(root, path);
    if (!existsSync(absolute)) { hash.update(json([path, 'missing'])); continue; }
    const stat = lstatSync(absolute);
    if (stat.isSymbolicLink()) hash.update(json([path, 'symlink', readlinkSync(absolute)]));
    else if (stat.isFile()) hash.update(json([path, stat.mode & 0o777, sha256(readFileSync(absolute))]));
    else hash.update(json([path, 'non-file']));
  }
  return { commit, dirty: status.length > 0, treeDigest: hash.digest('hex'), status };
}
export function toolPaths(options = {}) {
  const sdk = options.sdk || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  const tools = options.buildTools || '35.0.0';
  return { aapt: options.aapt || (sdk ? join(sdk, 'build-tools', tools, 'aapt') : 'aapt'), apksigner: options.apksigner || (sdk ? join(sdk, 'build-tools', tools, 'apksigner') : 'apksigner') };
}
export function parseBadging(text) {
  const pkg = text.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m);
  const target = text.match(/^targetSdkVersion:'(\d+)'/m);
  if (!pkg || !target) throw new Error('aapt 未返回完整 package/targetSdkVersion');
  return { applicationId: pkg[1], versionCode: Number(pkg[2]), versionName: pkg[3], targetSdk: Number(target[1]), debuggable: /^application-debuggable/m.test(text) };
}
export function parseCertificates(text) {
  const values = [...text.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9:]+)\s*$/gm)].map(match => match[1].replaceAll(':', '').toLowerCase()).sort();
  if (values.length !== 1 || !/^[a-f0-9]{64}$/.test(values[0])) throw new Error('必须恰有一个可核验的 APK 签名证书');
  return values[0];
}
function zipAssets(apk, names) {
  const code = `import sys,json,zipfile,base64\nwith zipfile.ZipFile(sys.argv[1]) as z:\n names=json.loads(sys.argv[2])\n for name in names:\n  if z.namelist().count(name)!=1: raise ValueError('APK entry missing or duplicate: '+name)\n print(json.dumps({name:base64.b64encode(z.read(name)).decode() for name in names}))`;
  const entries = JSON.parse(run('python3', ['-c', code, apk, JSON.stringify(names)]));
  return new Map(Object.entries(entries).map(([name, content]) => [name, Buffer.from(content, 'base64')]));
}
export function inspectApk(apk, flavor, options) {
  const tools = toolPaths(options);
  const file = reference(apk);
  const badging = parseBadging(run(tools.aapt, ['dump', 'badging', file.path]));
  const expectedId = { system: 'com.dsh.android', gecko: 'com.dsh.webview' }[flavor];
  if (!expectedId || badging.applicationId !== expectedId || badging.applicationId.length !== 15 || badging.targetSdk !== 28) throw new Error(`${flavor}: applicationId/targetSdk 不符合既有身份`);
  if (!options.development && badging.debuggable) throw new Error(`${flavor}: 正式候选不能使用 debuggable APK`);
  const signature = run(tools.apksigner, ['verify', '--verbose', '--print-certs', file.path]);
  if (!options.development && /^Signer #\d+ certificate DN:.*CN=Android Debug(?:,|$)/mi.test(signature)) throw new Error(`${flavor}: 正式候选不能使用Android Debug证书`);
  const certificateSha256 = parseCertificates(signature);
  const catalog = JSON.parse(readFileSync(options.catalog, 'utf8'));
  const top = catalog.components.filter(component => !component.bundledIn);
  const android = top.find(component => component.id === 'android-bundle');
  if (catalog.schemaVersion !== 1 || top.length !== 4 || android?.componentIds?.length !== 14 || new Set(android.componentIds).size !== 14) throw new Error('必须是四个顶层出厂包和一个含14组件的 Android bundle');
  const names = top.flatMap(component => [`assets/${component.asset}`, `assets/${component.id}.manifest.json`]);
  const entries = zipAssets(file.path, names);
  const runtimeEntries = (options.runtimeAssets || []).map(asset => {
    const apkEntry = asset.apkEntry || (asset.path?.includes('/assets/') ? `assets/${asset.path.split('/assets/').slice(1).join('/assets/')}` : null);
    if (!apkEntry || !apkEntry.startsWith('assets/') || apkEntry.split('/').includes('..')) throw new Error(`运行资产缺少合法apkEntry: ${asset.id}`);
    return { ...asset, apkEntry };
  });
  if (runtimeEntries.length) {
    const code = `import sys,json,zipfile,hashlib\nwith zipfile.ZipFile(sys.argv[1]) as z:\n result={}\n for entry in json.loads(sys.argv[2]):\n  if z.namelist().count(entry)!=1: raise ValueError('APK runtime missing or duplicate: '+entry)\n  digest=hashlib.sha256(); size=0\n  with z.open(entry) as stream:\n   while True:\n    data=stream.read(1024*1024)\n    if not data: break\n    size+=len(data); digest.update(data)\n  result[entry]={'sha256':digest.hexdigest(),'bytes':size}\n print(json.dumps(result))`;
    const actual = JSON.parse(run('python3', ['-c', code, file.path, JSON.stringify(runtimeEntries.map(asset => asset.apkEntry))]));
    for (const asset of runtimeEntries) if (actual[asset.apkEntry]?.sha256 !== asset.sha256 || actual[asset.apkEntry]?.bytes !== asset.bytes) throw new Error(`${flavor}: APK运行资产不符合锁 ${asset.id}`);
  }
  const plugins = top.map(component => {
    const zip = entries.get(`assets/${component.asset}`);
    const manifestBytes = entries.get(`assets/${component.id}.manifest.json`);
    const manifest = JSON.parse(manifestBytes);
    const expectedZip = readFileSync(join(options.generatedDir, component.asset));
    const expectedManifest = readFileSync(join(options.generatedDir, `${component.id}.manifest.json`));
    if (!zip.equals(expectedZip) || !manifestBytes.equals(expectedManifest)) throw new Error(`${flavor}/${component.id}: APK 与本次生成资产不一致`);
    if (manifest.id !== component.id || !Array.isArray(manifest.files) || !manifest.files.length) throw new Error(`${component.id}: manifest 身份/逐文件清单缺失`);
    const contents = readZip(zip);
    if (contents.size !== manifest.files.length || new Set(manifest.files.map(item => item.path)).size !== manifest.files.length) throw new Error(`${component.id}: 逐文件闭包不一致`);
    for (const item of manifest.files) {
      const bytes = contents.get(item.path);
      if (!bytes || bytes.length !== item.size || sha256(bytes) !== item.sha256) throw new Error(`${component.id}: 文件摘要不一致 ${item.path}`);
    }
    const pkg = JSON.parse(contents.get('package.json') || 'null');
    if (pkg?.name !== manifest.package || pkg?.version !== manifest.version) throw new Error(`${component.id}: package/manifest不一致`);
    if (component.id === 'android-bundle' && (pkg.bundledDependencies?.length !== 14 || new Set(pkg.bundledDependencies).size !== 14)) throw new Error('Android bundle未包含14个独立命名组件');
    if (!Array.isArray(manifest.sourceFiles) || !manifest.sourceFiles.length) throw new Error(`${component.id}: 缺少源码摘要`);
    const sourceMismatches = [];
    const sourceRoot = manifest.sourceRoot === 'repository' ? resolve(options.root) : resolve(options.root, component.source);
    if (relative(resolve(options.root), sourceRoot).startsWith('..')) throw new Error(`${component.id}: 源码根目录越界`);
    for (const source of manifest.sourceFiles) {
      const path = resolve(sourceRoot, source.path);
      const rel = relative(sourceRoot, path);
      if (rel.startsWith('..') || rel.startsWith('/')) throw new Error(`${component.id}: 源码路径越界 ${source.path}`);
      if (!existsSync(path) || sha256(readFileSync(path)) !== source.sha256) sourceMismatches.push(source.path);
    }
    if (options.verifySources !== false && sourceMismatches.length) throw new Error(`${component.id}: 生成产物不对应当前源码 ${sourceMismatches[0]}`);
    return { id: component.id, package: manifest.package, version: manifest.version, revision: manifest.revision, sourceDigest: manifest.sourceDigest, sourceCheck: { status: sourceMismatches.length ? 'stale' : 'matched', mismatches: sourceMismatches }, tools: { target: manifest.target, esbuildVersion: manifest.esbuildVersion }, zipSha256: sha256(zip), manifestSha256: sha256(manifestBytes), files: manifest.files.length };
  });
  return { flavor, ...file, ...badging, certificateSha256, plugins, tools: { aapt: run(tools.aapt, ['version']).trim(), apksigner: run(tools.apksigner, ['version']).trim(), node: process.version } };
}
export function inspectPair(options) {
  const apks = ['system', 'gecko'].map(flavor => inspectApk(options[flavor], flavor, options));
  const [system, gecko] = apks;
  if (system.versionCode !== gecko.versionCode || gecko.versionName !== `${system.versionName}-gecko` || system.certificateSha256 !== gecko.certificateSha256) throw new Error('双包版本号/版本码/证书不匹配（gecko 应使用 -gecko 后缀）');
  if (JSON.stringify(system.plugins) !== JSON.stringify(gecko.plugins)) throw new Error('双包出厂插件不一致');
  return apks;
}
export function verifySourceRecord(path, root, apks) {
  const record = JSON.parse(readFileSync(path, 'utf8'));
  const current = sourceSnapshot(root);
  if (record.schemaVersion !== 1 || JSON.stringify(record.source) !== JSON.stringify(current)) throw new Error('构建前源码记录与当前工作树不一致');
  const timestamp = Date.parse(record.createdAt);
  if (!Number.isFinite(timestamp) || apks.some(apk => statSync(apk.path).mtimeMs < timestamp)) throw new Error('APK 早于构建前源码记录，不能绑定当前源码');
  return { source: current, sourceBinding: 'source-record-and-artifact-time', sourceRecord: reference(path) };
}
export function inspectRuntimeLock(path, root) {
  const lock = JSON.parse(readFileSync(path, 'utf8'));
  if (lock.schemaVersion !== 1 || !Array.isArray(lock.assets) || !lock.assets.length) throw new Error('运行资产锁格式无效');
  const ids = new Set();
  for (const asset of lock.assets) {
    if (!asset.id || ids.has(asset.id) || !/^[a-f0-9]{64}$/.test(asset.sha256) || !Number.isSafeInteger(asset.bytes) || !asset.source || !asset.build) throw new Error(`运行资产锁记录不完整: ${asset.id}`);
    ids.add(asset.id);
    const actual = reference(resolve(root, asset.path));
    if (actual.sha256 !== asset.sha256 || actual.bytes !== asset.bytes) throw new Error(`运行资产不符合锁: ${asset.id}`);
  }
  return { ...reference(path), assets: lock.assets };
}
export function inspectGateReports(paths, requiredLevels) {
  const reports = paths.map(path => {
    const report = JSON.parse(readFileSync(path, 'utf8'));
    if (report.schemaVersion !== 1 || !Array.isArray(report.checks)) throw new Error(`门禁报告格式无效: ${path}`);
    return { ...reference(path), checks: report.checks };
  });
  for (const level of requiredLevels) {
    const checks = reports.flatMap(report => report.checks).filter(check => check.level === level);
    if (!checks.length || checks.some(check => check.status !== 'passed' || !check.command || !check.evidence || (check.skipped || 0) !== 0)) throw new Error(`门禁 ${level} 缺失/未执行/失败/跳过`);
  }
  return reports;
}
