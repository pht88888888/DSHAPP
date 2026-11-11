#!/usr/bin/env node
/** 离线契约测试；合成 tarball 仅验证解析/完整性，绝不作为线上发布证据。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import test from 'node:test';
import { ROOT, checkLocal, collectLiveAudit, compareFiles, compareLive, digest, unpackTarball, validateLock, verifyPublished } from './check-remote-plugin-provenance.mjs';

const lock = () => JSON.parse(readFileSync(join(ROOT, 'catalogs/remote-plugins.lock.json')));
const hash = (value, type = 'sha256', encoding = 'hex') => createHash(type).update(value).digest(encoding);
function tarball(entries) {
  const chunks = [];
  for (const { path, content = '', type = '0' } of entries) {
    const data = Buffer.from(content), header = Buffer.alloc(512);
    header.write(path, 0, 100); header.write('0000644\0', 100); header.write('0000000\0', 108); header.write('0000000\0', 116);
    header.write(`${data.length.toString(8).padStart(11, '0')}\0`, 124); header.write('00000000000\0', 136);
    header.fill(32, 148, 156); header.write(type, 156); header.write('ustar\0', 257);
    const sum = header.reduce((sum, value) => sum + value, 0);
    header.write(`${sum.toString(8).padStart(6, '0')}\0 `, 148);
    chunks.push(header, data, Buffer.alloc((512 - data.length % 512) % 512));
  }
  return gzipSync(Buffer.concat([...chunks, Buffer.alloc(1024)]));
}
function fixture() {
  const pkg = { name: 'dsh-jiuling-remote-mobile', version: '0.0.0', main: './lib/index.js', exports: { './client': './lib/client.js' }, dsh: { client: { platform: 'web' }, bundle: { patch: './cordis.patch.yml' } } };
  const compressed = tarball([
    { path: 'package/package.json', content: JSON.stringify(pkg) }, { path: 'package/lib/index.js', content: 'export const fixture = true;' },
    { path: 'package/lib/client.js', content: 'window.__ModuleLoader__.load({id:"fixture"});' }, { path: 'package/cordis.patch.yml', content: '- insert: {}' },
  ]);
  return { compressed, metadata: { ...pkg, gitHead: '0'.repeat(40), dist: { tarball: 'https://registry.npmjs.org/dsh-jiuling-remote-mobile/-/dsh-jiuling-remote-mobile-0.0.0.tgz', integrity: `sha512-${hash(compressed, 'sha512', 'base64')}`, shasum: hash(compressed, 'sha1') } } };
}
function reseal(value) { const { auditSha256, ...body } = value; return { ...body, auditSha256: digest(body) }; }

test('真实已审计锁和保留旧副本在离线门禁逐文件一致，明确0跳过', () => {
  const result = checkLocal();
  assert.equal(result.lock.evidence, 'live-npm-and-github'); assert.equal(result.lock.packages.length, 2);
  assert.ok(result.checked > 40); assert.equal(result.skipped, 0);
  assert.equal(result.lock.policy.legacy, 'reference-only-preserve-unresolved');
  assert.ok(result.lock.packages.some(pkg => pkg.legacy.repositoryUnique.some(file => file.status === 'diverged')));
});

test('锁自身字段漂移、伪来源、改源地址和旧副本生产标记都会失败', () => {
  const changed = lock(); changed.packages[0].published.version = '999.0.0';
  assert.throws(() => validateLock(changed), /自身摘要漂移/);
  for (const mutate of [
    value => { value.evidence = 'fixture'; }, value => { value.repository.url = 'https://example.invalid/source'; },
    value => { value.packages[0].published.tarball = 'https://mirror.invalid/fake.tgz'; },
    value => { value.packages[0].legacy.status = 'production'; },
    value => { value.policy.sameVersionImpliesSameContent = true; },
    value => { value.packages[0].gitHead.accessible = !value.packages[0].gitHead.accessible; },
    value => { value.packages[0].legacy.files[0].path = '../../escape'; },
  ]) {
    const value = lock(); mutate(value); assert.throws(() => validateLock(reseal(value)));
  }
});

test('tarball 验证 SHA512/SHA1、精确版本、官方域名和官方加载入口', () => {
  const { metadata, compressed } = fixture();
  const files = verifyPublished(metadata, compressed); assert.equal(files.size, 4);
  const broken = Buffer.from(compressed); broken[broken.length - 1] ^= 1;
  assert.throws(() => verifyPublished(metadata, broken), /integrity/);
  assert.throws(() => verifyPublished({ ...metadata, version: '0.0.1' }, compressed), /精确版本/);
  assert.throws(() => verifyPublished({ ...metadata, dist: { ...metadata.dist, tarball: 'https://example.invalid/fake.tgz' } }, compressed), /精确版本/);
  assert.throws(() => verifyPublished({ ...metadata, dist: { ...metadata.dist, shasum: '0'.repeat(40) } }, compressed), /shasum/);
});

test('tarball 路径穿越、链接、重复条目、头损坏和空包不作为有效发布', () => {
  for (const entries of [
    [{ path: 'package/../escape' }], [{ path: 'package/link', type: '2' }],
    [{ path: 'package/index.js' }, { path: 'package/index.js' }], [{ path: 'other/index.js' }], [],
  ]) assert.throws(() => unpackTarball(tarball(entries), 'package'));
  assert.throws(() => unpackTarball(Buffer.from('not gzip'), 'package'));
});

test('文件比较覆盖新增、删除、同名改动，不能凭相同版本跳过内容核对', () => {
  const result = compareFiles(new Map([['same', Buffer.from('x')], ['change', Buffer.from('a')], ['old', Buffer.from('o')]]), new Map([['same', Buffer.from('x')], ['change', Buffer.from('b')], ['new', Buffer.from('n')]]));
  assert.deepEqual(result.map(item => [item.path, item.status]), [['change', 'changed'], ['new', 'right-only'], ['old', 'left-only'], ['same', 'same']]);
  assert.notEqual(result[0].leftSha256, result[0].rightSha256);
});

test('live 比较禁止发布版本、完整性、refs和同版本源码漂移', () => {
  const original = lock();
  for (const mutate of [
    value => { value.packages[0].published.archiveSha256 = '0'.repeat(64); },
    value => { value.packages[0].repository.files[0].sha256 = '0'.repeat(64); },
    value => { value.repository.refs.push({ name: 'new-branch', sha: '1'.repeat(40) }); },
    value => { value.repository.archive.sha256 = '1'.repeat(64); },
  ]) {
    const changed = structuredClone(original); mutate(changed);
    assert.throws(() => compareLive(original, reseal(changed)));
  }
});

test('真实输入不可达时live采集直接失败，不读取旧锁或fixture替代网络', async () => {
  const previous = globalThis.fetch, requests = [];
  globalThis.fetch = async url => { requests.push(url); throw new Error('测试模拟真实输入不可达'); };
  try {
    await assert.rejects(() => collectLiveAudit(), /真实输入不可达/);
    assert.ok(requests.some(url => url === 'https://registry.npmjs.org/dsh-jiuling-remote-mobile/latest'));
    assert.ok(requests.some(url => url.startsWith('https://api.github.com/repos/pht88888888/dsh-jiuling-remote-plugins')));
  } finally { globalThis.fetch = previous; }
});

test('CLI 不接受 fixture/source/cache/自定义registry，离线通过不声称刷新latest', () => {
  const script = join(ROOT, 'tools/check-remote-plugin-provenance.mjs');
  for (const args of [['--fixture'], ['--live', '--fixture'], ['--registry', 'https://example.invalid']]) {
    const result = spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
    assert.equal(result.status, 1); assert.match(result.stderr, /用法/);
  }
  const offline = spawnSync(process.execPath, [script], { encoding: 'utf8' });
  assert.equal(offline.status, 0, offline.stderr); assert.match(offline.stdout, /不代表已重查线上 latest/);
});
