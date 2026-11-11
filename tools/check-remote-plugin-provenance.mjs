#!/usr/bin/env node
/** 默认离线检查锁和旧副本；--live 对真实 npm/GitHub 重新核对，失败不降级。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync, lstatSync } from 'node:fs';
import { dirname, resolve, relative, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const REPOSITORY = 'https://github.com/pht88888888/dsh-jiuling-remote-plugins';
export const PACKAGES = [
  { id: 'mobile', name: 'dsh-jiuling-remote-mobile', source: 'dsh-remote-mobile', legacyPath: 'remote-plugin/mobile' },
  { id: 'desktop', name: 'dsh-jiuling-remote-desktop', source: 'dsh-remote-desktop', legacyPath: 'remote-plugin/desktop' },
];
const API = 'https://api.github.com/repos/pht88888888/dsh-jiuling-remote-plugins';
const sha = (value, algorithm = 'sha256', encoding = 'hex') => createHash(algorithm).update(value).digest(encoding);
const canonical = value => JSON.stringify(value, (_, item) => item && !Array.isArray(item) && typeof item === 'object' ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item);
export const digest = value => sha(canonical(value));
const safePath = path => typeof path === 'string' && path.length > 0 && !isAbsolute(path) && !path.includes('\\') && !path.split('/').some(part => part === '..' || part === '.' || part === '');
function inside(root, path) {
  assert.ok(safePath(path), `非法来源路径 ${path}`);
  const target = resolve(root, path), local = relative(root, target);
  assert.ok(!isAbsolute(local) && local !== '..' && !local.startsWith('../'), `来源越界 ${path}`);
  return target;
}

/** 只接受单根普通文件 tar；PAX 仅用于 long path 元数据，不接受链接/设备。 */
export function unpackTarball(compressed, expectedRoot) {
  const tar = gunzipSync(compressed, { maxOutputLength: 64 * 1024 * 1024 });
  const files = new Map();
  let offset = 0, pax = {};
  while (offset + 512 <= tar.length) {
    const header = tar.subarray(offset, offset + 512);
    if (header.every(byte => byte === 0)) break;
    const text = (start, length) => header.subarray(start, start + length).toString().replace(/\0.*$/s, '');
    const size = Number.parseInt(text(124, 12).trim() || '0', 8);
    assert.ok(Number.isSafeInteger(size) && size >= 0 && offset + 512 + size <= tar.length, 'tar 文件大小无效');
    let checksum = 0;
    for (let i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i];
    assert.equal(checksum, Number.parseInt(text(148, 8).trim(), 8), 'tar 头校验失败');
    const data = tar.subarray(offset + 512, offset + 512 + size);
    offset += 512 + Math.ceil(size / 512) * 512;
    const type = text(156, 1);
    if (type === 'x' || type === 'g') {
      const attributes = {};
      let cursor = 0;
      while (cursor < data.length) {
        const space = data.indexOf(32, cursor);
        const length = Number(data.subarray(cursor, space).toString());
        assert.ok(space > cursor && length > 0 && cursor + length <= data.length, 'PAX 记录无效');
        const record = data.subarray(space + 1, cursor + length - 1).toString();
        const equal = record.indexOf('=');
        attributes[record.slice(0, equal)] = record.slice(equal + 1);
        cursor += length;
      }
      if (type === 'x') pax = attributes;
      continue;
    }
    const prefix = text(345, 155);
    const name = pax.path || (prefix ? `${prefix}/${text(0, 100)}` : text(0, 100));
    pax = {};
    if (type === '5') continue;
    assert.ok(type === '0' || type === '', `tar 不接受链接或特殊文件: ${name}`);
    const slash = name.indexOf('/');
    assert.ok(slash > 0, `tar 缺少单根目录: ${name}`);
    const root = name.slice(0, slash), local = name.slice(slash + 1);
    assert.ok(expectedRoot ? root === expectedRoot : /^[a-zA-Z0-9_.-]+$/.test(root), `tar 根目录不符: ${root}`);
    assert.ok(safePath(local), `tar 路径越界: ${name}`);
    assert.ok(!files.has(local), `tar 重复路径: ${local}`);
    files.set(local, Buffer.from(data));
  }
  assert.ok(files.size > 0, 'tarball 不含真实文件');
  return files;
}

export function verifyPublished(metadata, compressed) {
  assert.match(metadata.name, /^dsh-jiuling-remote-(mobile|desktop)$/);
  assert.match(metadata.version, /^\d+\.\d+\.\d+$/);
  assert.match(metadata.gitHead, /^[a-f0-9]{40}$/);
  const expectedUrl = `https://registry.npmjs.org/${metadata.name}/-/${metadata.name}-${metadata.version}.tgz`;
  assert.equal(metadata.dist.tarball, expectedUrl, 'npm tarball 必须来自官方精确版本');
  assert.match(metadata.dist.integrity, /^sha512-[A-Za-z0-9+/]+={0,2}$/);
  assert.equal(`sha512-${sha(compressed, 'sha512', 'base64')}`, metadata.dist.integrity, 'npm tarball integrity 不符');
  assert.equal(sha(compressed, 'sha1'), metadata.dist.shasum, 'npm tarball shasum 不符');
  const files = unpackTarball(compressed, 'package');
  const pkg = JSON.parse(files.get('package.json'));
  assert.equal(pkg.name, metadata.name); assert.equal(pkg.version, metadata.version);
  assert.ok(files.has(pkg.main.replace(/^\.\//, '')), 'npm 包缺少宿主入口');
  assert.ok(files.has(pkg.exports['./client'].replace(/^\.\//, '')), 'npm 包缺少客户端入口');
  assert.equal(pkg.dsh.client.platform, 'web');
  assert.ok(files.has(pkg.dsh.bundle.patch.replace(/^\.\//, '')), 'npm 包缺少 bundle patch');
  return files;
}

export function compareFiles(left, right) {
  return [...new Set([...left.keys(), ...right.keys()])].sort().map(path => {
    const before = left.get(path), after = right.get(path);
    return { path, status: !before ? 'right-only' : !after ? 'left-only' : before.equals(after) ? 'same' : 'changed', leftSha256: before ? sha(before) : null, rightSha256: after ? sha(after) : null };
  });
}
const inventory = files => [...files].sort(([a], [b]) => a.localeCompare(b, 'en')).map(([path, data]) => ({ path, bytes: data.length, sha256: sha(data) }));
const subtree = (files, prefix) => new Map([...files].filter(([path]) => path.startsWith(`${prefix}/`)).map(([path, data]) => [path.slice(prefix.length + 1), data]));
function tracked(root, prefix) {
  return execFileSync('git', ['ls-files', '-z', '--', prefix], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
}
function localSnapshot(root, prefix) {
  const files = new Map();
  for (const path of tracked(root, prefix)) {
    const target = inside(root, path);
    assert.ok(lstatSync(target).isFile() && !lstatSync(target).isSymbolicLink(), `旧副本不是普通文件 ${path}`);
    files.set(path.slice(prefix.length + 1), readFileSync(target));
  }
  assert.ok(files.size > 0, `旧副本快照为空 ${prefix}`);
  return files;
}
function npmSource(files) {
  const pkg = JSON.parse(files.get('package.json'));
  // npm files 声明的潜在生产入口；缺失声明也会进入差异结果，不能当作发布包相同。
  return new Map([...files].filter(([path]) => path === 'package.json' || (pkg.files || []).some(entry => path === entry || path.startsWith(`${entry}/`))));
}
function legacyUnique(legacy, repository) {
  return [...legacy].filter(([path, bytes]) => !repository.get(path)?.equals(bytes)).map(([path, bytes]) => {
    const text = bytes.toString('utf8'), upstream = repository.get(path)?.toString('utf8') || '';
    const lines = text.split('\n'), upstreamLines = new Set(upstream.split('\n'));
    const distinct = lines.filter(line => line.trim() && !upstreamLines.has(line));
    return { path, status: repository.has(path) ? 'diverged' : 'legacy-only', sha256: sha(bytes), distinctLines: distinct.length, distinctLinesSha256: sha(distinct.join('\n')) };
  });
}

export function scanReferences(root = ROOT) {
  const references = [];
  const excluded = new Set(['catalogs/remote-plugins.lock.json', 'tools/check-remote-plugin-provenance.mjs', 'tools/test-remote-plugin-provenance.mjs', 'docs/architecture/remote-plugin-provenance.md']);
  for (const path of tracked(root, '.')) {
    if (excluded.has(path) || !/\.(?:md|mjs|js|json|yml|yaml|kt|kts|py|sh|ps1|psm1)$/i.test(path)) continue;
    if (!existsSync(inside(root, path))) continue;
    const lines = readFileSync(inside(root, path), 'utf8').split('\n');
    for (let line = 0; line < lines.length; line++) {
      if (/remote-plugin[\\/]|dsh-jiuling-remote-plugins|pair-server/.test(lines[line])) {
        const kind = path.startsWith('remote-plugin/') ? 'legacy-internal' : path.startsWith('docs/') || path.startsWith('README') ? 'documentation' : 'production';
        references.push({ path, line: line + 1, kind, textSha256: sha(lines[line]) });
      }
    }
  }
  return references;
}

let githubToken;
function githubHeaders(url) {
  if (!url.startsWith(`${API}/`) && url !== API) return {};
  if (githubToken === undefined) {
    githubToken = process.env.GH_TOKEN || process.env.GITHUB_TOKEN || '';
    if (!githubToken) {
      try { githubToken = execFileSync('gh', ['auth', 'token'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 5000 }).trim(); }
      catch { /* 未登录时仍直接读取真实公开 API；限流会原样失败。 */ }
    }
  }
  return githubToken ? { authorization: `Bearer ${githubToken}` } : {};
}
async function request(url, { json = false, allowMissingCommit = false } = {}) {
  const response = await fetch(url, { signal: AbortSignal.timeout(30000), headers: { 'user-agent': 'dsh-android-provenance-audit', accept: json ? 'application/json' : 'application/octet-stream', ...githubHeaders(url) } });
  if (allowMissingCommit && [404, 422].includes(response.status)) return { status: response.status, body: await response.json() };
  if (!response.ok) throw new Error(`真实来源请求失败 ${response.status}: ${url}`);
  return { status: response.status, body: json ? await response.json() : Buffer.from(await response.arrayBuffer()) };
}

/** 只读取官方 registry 与固定独立仓库；无 fixture、缓存或本地 checkout 回退。 */
export async function collectLiveAudit({ root = ROOT } = {}) {
  const [repoResponse, branchesResponse, tagsResponse, ...published] = await Promise.all([
    request(API, { json: true }), request(`${API}/branches?per_page=100`, { json: true }), request(`${API}/tags?per_page=100`, { json: true }),
    ...PACKAGES.map(pkg => request(`https://registry.npmjs.org/${pkg.name}/latest`, { json: true })),
  ]);
  assert.ok(branchesResponse.body.length < 100 && tagsResponse.body.length < 100, '仓库 refs 超过单页，必须先扩展分页审计');
  const defaultBranch = repoResponse.body.default_branch;
  const refs = branchesResponse.body.map(branch => ({ name: branch.name, sha: branch.commit.sha })).sort((a, b) => a.name.localeCompare(b.name));
  const tags = tagsResponse.body.map(tag => ({ name: tag.name, sha: tag.commit.sha })).sort((a, b) => a.name.localeCompare(b.name));
  const revision = refs.find(ref => ref.name === defaultBranch)?.sha;
  assert.match(revision || '', /^[a-f0-9]{40}$/);
  const archiveUrl = `https://codeload.github.com/pht88888888/dsh-jiuling-remote-plugins/tar.gz/${revision}`;
  const archive = (await request(archiveUrl)).body;
  const repositoryFiles = unpackTarball(archive, `dsh-jiuling-remote-plugins-${revision}`);
  const packages = await Promise.all(PACKAGES.map(async (definition, index) => {
    const metadata = published[index].body;
    assert.equal(metadata.name, definition.name);
    assert.equal(metadata.dist.tarball, `https://registry.npmjs.org/${definition.name}/-/${definition.name}-${metadata.version}.tgz`);
    assert.match(metadata.gitHead, /^[a-f0-9]{40}$/);
    const [tarballResponse, commitResponse] = await Promise.all([
      request(metadata.dist.tarball), request(`${API}/commits/${metadata.gitHead}`, { json: true, allowMissingCommit: true }),
    ]);
    if (commitResponse.status === 422) assert.equal(commitResponse.body.message, `No commit found for SHA: ${metadata.gitHead}`, 'GitHub 422 必须明确为缺少该发布提交');
    const tarball = tarballResponse.body;
    const files = verifyPublished(metadata, tarball);
    const repository = subtree(repositoryFiles, definition.source);
    assert.ok(repository.size > 0, `独立仓库缺少 ${definition.source}`);
    const repoPackage = JSON.parse(repository.get('package.json'));
    const legacy = localSnapshot(root, definition.legacyPath);
    const oldPackage = JSON.parse(legacy.get('package.json'));
    return {
      ...definition,
      published: { version: metadata.version, gitHead: metadata.gitHead, metadataUrl: `https://registry.npmjs.org/${definition.name}/${metadata.version}`, latestUrl: `https://registry.npmjs.org/${definition.name}/latest`, tarball: metadata.dist.tarball, integrity: metadata.dist.integrity, shasum: metadata.dist.shasum, archiveSha256: sha(tarball), archiveBytes: tarball.length, files: inventory(files) },
      gitHead: { url: `${API}/commits/${metadata.gitHead}`, httpStatus: commitResponse.status, message: commitResponse.body?.message || null, accessible: commitResponse.status === 200, revision: commitResponse.body?.sha || null },
      repository: { version: repoPackage.version, files: inventory(repository), publishedComparison: compareFiles(files, npmSource(repository)) },
      legacy: { version: oldPackage.version, status: 'legacy-reference-only', files: inventory(legacy), publishedComparison: compareFiles(npmSource(legacy), files), repositoryUnique: legacyUnique(legacy, repository) },
    };
  }));
  const ancillary = new Map();
  for (const directory of ['remote-plugin/pair-server', 'remote-plugin/_test']) {
    for (const [path, bytes] of localSnapshot(root, directory)) ancillary.set(`${directory}/${path}`, bytes);
  }
  const body = {
    schemaVersion: 1, observedAt: new Date().toISOString(), evidence: 'live-npm-and-github',
    policy: { publishedBaseline: 'npm-latest-observed', productionSource: 'independent-repository', legacy: 'reference-only-preserve-unresolved', automaticPublishing: false, sameVersionImpliesSameContent: false },
    repository: { url: REPOSITORY, defaultBranch, revision, refs, tags, archive: { url: archiveUrl, sha256: sha(archive), bytes: archive.length } },
    packages, ancillary: { status: 'preserve-unmigrated-pairing-and-tests', files: inventory(ancillary) }, references: scanReferences(root),
  };
  return validateLock({ ...body, auditSha256: digest(body) });
}

function validateInventory(files, label) {
  assert.ok(Array.isArray(files) && files.length > 0, `${label} 文件快照为空`);
  const paths = new Set();
  for (const file of files) {
    assert.ok(safePath(file.path) && !paths.has(file.path), `${label} 文件路径重复或非法`); paths.add(file.path);
    assert.match(file.sha256, /^[a-f0-9]{64}$/); assert.ok(Number.isSafeInteger(file.bytes) && file.bytes >= 0);
  }
}
function compareInventories(left, right) {
  const before = new Map(left.map(file => [file.path, file.sha256]));
  const after = new Map(right.map(file => [file.path, file.sha256]));
  return [...new Set([...before.keys(), ...after.keys()])].sort().map(path => ({
    path, status: !before.has(path) ? 'right-only' : !after.has(path) ? 'left-only' : before.get(path) === after.get(path) ? 'same' : 'changed',
    leftSha256: before.get(path) || null, rightSha256: after.get(path) || null,
  }));
}
const runtimeInventory = files => files.filter(file => file.path === 'package.json' || file.path === 'cordis.patch.yml' || file.path.startsWith('lib/'));
export function validateLock(lock) {
  const { auditSha256, ...body } = lock;
  assert.equal(digest(body), auditSha256, '审计锁自身摘要漂移');
  assert.equal(lock.schemaVersion, 1); assert.equal(lock.evidence, 'live-npm-and-github');
  assert.ok(Number.isFinite(Date.parse(lock.observedAt)));
  assert.equal(lock.repository.url, REPOSITORY); assert.match(lock.repository.revision, /^[a-f0-9]{40}$/);
  assert.ok(lock.repository.refs.some(ref => ref.name === lock.repository.defaultBranch && ref.sha === lock.repository.revision));
  assert.equal(lock.repository.archive.url, `https://codeload.github.com/pht88888888/dsh-jiuling-remote-plugins/tar.gz/${lock.repository.revision}`);
  assert.match(lock.repository.archive.sha256, /^[a-f0-9]{64}$/); assert.ok(lock.repository.archive.bytes > 0);
  assert.ok(Array.isArray(lock.repository.tags));
  for (const group of [lock.repository.refs, lock.repository.tags]) {
    const names = new Set();
    for (const ref of group) { assert.ok(typeof ref.name === 'string' && ref.name.length > 0 && !names.has(ref.name)); names.add(ref.name); assert.match(ref.sha, /^[a-f0-9]{40}$/); }
  }
  assert.equal(lock.policy.publishedBaseline, 'npm-latest-observed'); assert.equal(lock.policy.productionSource, 'independent-repository');
  assert.equal(lock.policy.sameVersionImpliesSameContent, false); assert.equal(lock.policy.automaticPublishing, false);
  assert.equal(lock.policy.legacy, 'reference-only-preserve-unresolved');
  assert.equal(lock.packages.length, PACKAGES.length);
  for (const definition of PACKAGES) {
    const pkg = lock.packages.find(pkg => pkg.id === definition.id);
    assert.ok(pkg, `缺少发布来源 ${definition.id}`);
    for (const [key, value] of Object.entries(definition)) assert.equal(pkg[key], value);
    const published = pkg.published;
    assert.match(published.version, /^\d+\.\d+\.\d+$/); assert.match(published.gitHead, /^[a-f0-9]{40}$/);
    assert.equal(published.tarball, `https://registry.npmjs.org/${pkg.name}/-/${pkg.name}-${published.version}.tgz`);
    assert.equal(published.metadataUrl, `https://registry.npmjs.org/${pkg.name}/${published.version}`);
    assert.equal(published.latestUrl, `https://registry.npmjs.org/${pkg.name}/latest`);
    assert.match(published.integrity, /^sha512-[A-Za-z0-9+/]{86}==$/); assert.match(published.shasum, /^[a-f0-9]{40}$/);
    assert.match(published.archiveSha256, /^[a-f0-9]{64}$/); assert.ok(published.archiveBytes > 0);
    assert.equal(pkg.gitHead.url, `${API}/commits/${published.gitHead}`);
    assert.ok([200, 404, 422].includes(pkg.gitHead.httpStatus));
    if (pkg.gitHead.httpStatus === 422) assert.equal(pkg.gitHead.message, `No commit found for SHA: ${published.gitHead}`);
    assert.equal(pkg.gitHead.accessible, pkg.gitHead.httpStatus === 200);
    assert.equal(pkg.gitHead.revision, pkg.gitHead.accessible ? published.gitHead : null);
    validateInventory(published.files, pkg.name); validateInventory(pkg.repository.files, `${pkg.id} repository`); validateInventory(pkg.legacy.files, `${pkg.id} legacy`);
    assert.match(pkg.repository.version, /^\d+\.\d+\.\d+$/); assert.match(pkg.legacy.version, /^\d+\.\d+\.\d+$/);
    assert.equal(pkg.legacy.status, 'legacy-reference-only');
    for (const entry of ['package.json', 'lib/index.js', 'lib/client.js', 'cordis.patch.yml']) assert.ok(published.files.some(file => file.path === entry), `发布入口缺失 ${entry}`);
    assert.deepEqual(pkg.repository.publishedComparison, compareInventories(published.files, runtimeInventory(pkg.repository.files)), '发布与独立源码差异记录必须覆盖完整运行文件');
    assert.deepEqual(pkg.legacy.publishedComparison, compareInventories(runtimeInventory(pkg.legacy.files), published.files), '旧副本与发布差异记录必须覆盖完整运行文件');
    const upstream = new Map(pkg.repository.files.map(file => [file.path, file.sha256]));
    const unique = pkg.legacy.files.filter(file => upstream.get(file.path) !== file.sha256);
    assert.deepEqual(pkg.legacy.repositoryUnique.map(file => ({ path: file.path, sha256: file.sha256, status: file.status })), unique.map(file => ({ path: file.path, sha256: file.sha256, status: upstream.has(file.path) ? 'diverged' : 'legacy-only' })), '旧副本独有差异不能漏报');
    for (const file of pkg.legacy.repositoryUnique) { assert.ok(Number.isSafeInteger(file.distinctLines) && file.distinctLines >= 0); assert.match(file.distinctLinesSha256, /^[a-f0-9]{64}$/); }
  }
  validateInventory(lock.ancillary.files, '配对服务及历史测试');
  assert.equal(lock.ancillary.status, 'preserve-unmigrated-pairing-and-tests');
  assert.ok(Array.isArray(lock.references));
  return lock;
}

export function checkLocal({ root = ROOT, lockPath = join(root, 'catalogs/remote-plugins.lock.json') } = {}) {
  const lock = validateLock(JSON.parse(readFileSync(lockPath, 'utf8')));
  let checked = 0;
  for (const pkg of lock.packages) {
    const actual = inventory(localSnapshot(root, pkg.legacyPath));
    assert.deepEqual(actual, pkg.legacy.files, `旧副本 ${pkg.id} 漂移，必须重做独有改动审计`); checked += actual.length;
  }
  const ancillary = new Map();
  for (const directory of ['remote-plugin/pair-server', 'remote-plugin/_test']) {
    for (const [path, bytes] of localSnapshot(root, directory)) ancillary.set(`${directory}/${path}`, bytes);
  }
  assert.deepEqual(inventory(ancillary), lock.ancillary.files, '配对/历史测试文件清单或摘要漂移');
  checked += ancillary.size;
  const catalogPath = join(root, 'catalogs/plugin-components.json');
  const catalog = JSON.parse(readFileSync(catalogPath, 'utf8'));
  assert.ok(catalog.components.every(component => !component.source.startsWith('remote-plugin/') && !component.source.includes('dsh-jiuling-remote')), '旧远程副本不能进入 APK 出厂组件');
  const references = scanReferences(root);
  const production = references.filter(item => item.kind === 'production' && !item.path.startsWith('remote-plugin/'));
  assert.deepEqual(production, [], '仍有工作入口指向旧远程生产副本');
  for (const reference of references.filter(item => item.path === 'docs/agents/plugin-updates.md')) {
    const line = readFileSync(join(root, reference.path), 'utf8').split('\n')[reference.line - 1];
    assert.ok(!/(?:在\s*`?remote-plugin\/(?:mobile|desktop)`?\s*(?:执行|运行|进行)|(?:cd|pushd)\s+`?remote-plugin\/(?:mobile|desktop))/.test(line), '官方插件更新指南仍引导旧副本生产操作');
  }
  return { lock, checked, skipped: 0 };
}
export function compareLive(locked, live) {
  validateLock(live);
  for (const pkg of locked.packages) {
    const actual = live.packages.find(item => item.id === pkg.id);
    assert.deepEqual(actual.published, pkg.published, `${pkg.id} npm 发布基线漂移；需要显式重新审计`);
    assert.deepEqual(actual.gitHead, pkg.gitHead, `${pkg.id} npm gitHead 可追溯性变化`);
    assert.deepEqual(actual.repository, pkg.repository, `${pkg.id} 独立源码变化；需要重新核对发布差异`);
  }
  assert.deepEqual(live.repository, locked.repository, '独立仓库 refs 或归档漂移；需要重新审计');
  return { packages: live.packages.length, missingGitHeads: live.packages.filter(pkg => !pkg.gitHead.accessible).length, sourceDifferences: live.packages.reduce((count, pkg) => count + pkg.repository.publishedComparison.filter(file => file.status !== 'same').length, 0), skipped: 0 };
}
export async function main(args = process.argv.slice(2)) {
  assert.ok(args.length === 0 || (args.length === 1 && args[0] === '--live'), '用法: node tools/check-remote-plugin-provenance.mjs [--live]');
  const local = checkLocal();
  if (args[0] === '--live') {
    const result = compareLive(local.lock, await collectLiveAudit());
    console.log(`远程插件真实证据与锁一致：${result.packages} 个 npm tarball/integrity/refs已核对，${result.missingGitHeads} 个发布 gitHead 仍不可达，${result.sourceDifferences} 个源码文件差异仍保留，0 跳过`);
  } else console.log(`远程插件离线来源契约通过：2 个已审计发布基线，${local.checked} 个本地文件摘要一致，0 跳过；不代表已重查线上 latest`);
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
