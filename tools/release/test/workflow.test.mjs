import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { advanceRelease, DIRECTORIES } from '../workflow.mjs';
import { inspectGateReports, json, parseBadging, parseCertificates, sha256 } from '../inspect.mjs';

function fixture(t, overrides = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-release-test-'));
  t.after(() => { assert.ok(resolve(directory).startsWith(resolve(tmpdir()) + '/dsh-release-test-')); rmSync(directory, { recursive: true, force: true }); });
  const apks = ['system', 'gecko'].map(flavor => {
    const fileName = `DSH1.0.0${flavor === 'gecko' ? '-webview' : ''}.apk`;
    const data = Buffer.from(`new-${flavor}`);
    writeFileSync(join(directory, fileName), data);
    return { flavor, fileName, sha256: sha256(data), bytes: data.length };
  });
  const manifest = { schemaVersion: 1, releaseId: 'a'.repeat(64), development: false, source: { dirty: false }, sourceBinding: 'source-record-and-artifact-time', apks, ...overrides };
  writeFileSync(join(directory, 'release-manifest.json'), json(manifest));
  const old = ['DSH0.9.0.apk', 'DSH0.9.0-webview.apk'].map(name => ({ name, sha256: sha256(name), bytes: name.length }));
  const remote = new Map([[DIRECTORIES.latest, old.map(entry => ({ ...entry }))]]);
  const calls = [];
  let failure;
  const adapter = {
    failNext(operation, match, after = false) { failure = { operation, match, after }; },
    entries(path) { return remote.get(path) || []; },
    async list({ directory }) { return adapter.entries(directory).map(entry => ({ ...entry })); },
    async upload(input) {
      calls.push(['upload', input.name]);
      const fail = failure?.operation === 'upload' && input.name.includes(failure.match);
      if (fail && !failure.after) { failure = null; throw new Error('offline upload failure'); }
      remote.set(input.directory, [...adapter.entries(input.directory), { name: input.name, sha256: input.sha256, bytes: input.bytes }]);
      if (fail) { failure = null; throw new Error('offline upload unknown outcome'); }
    },
    async move(input) {
      calls.push(['move', input.from, input.to, input.name]);
      const fail = failure?.operation === 'move' && input.name.includes(failure.match);
      if (fail && !failure.after) { failure = null; throw new Error('offline move failure'); }
      const entry = adapter.entries(input.from).find(entry => entry.name === input.name);
      assert.ok(entry, `move source missing: ${input.name}`);
      remote.set(input.to, [...adapter.entries(input.to), entry]);
      remote.set(input.from, adapter.entries(input.from).filter(candidate => candidate.name !== input.name));
      if (fail) { failure = null; throw new Error('offline move unknown outcome'); }
    },
  };
  return { directory, adapter, remote, old, calls, manifest, state: () => JSON.parse(readFileSync(join(directory, 'release-state.json'))) };
}

test('双包上传、提升后才归档，最终准确保留新双包', async t => {
  const f = fixture(t);
  await advanceRelease(f.directory, 'publish', f.adapter);
  assert.deepEqual(f.adapter.entries(DIRECTORIES.latest), f.old);
  await advanceRelease(f.directory, 'promote', f.adapter);
  assert.equal(f.adapter.entries(DIRECTORIES.latest).length, 4);
  assert.equal(f.adapter.entries(DIRECTORIES.history).length, 0);
  const state = await advanceRelease(f.directory, 'archive', f.adapter);
  assert.equal(state.phase, 'archived');
  assert.deepEqual(f.adapter.entries(DIRECTORIES.latest).map(entry => entry.name).sort(), f.manifest.apks.map(entry => entry.fileName).sort());
  assert.deepEqual(f.adapter.entries(DIRECTORIES.history), f.old);
});

test('缺少第二候选时禁止提升，旧双包完全保留', async t => {
  const f = fixture(t);
  f.adapter.failNext('upload', 'webview');
  await assert.rejects(advanceRelease(f.directory, 'publish', f.adapter), /offline upload/);
  await assert.rejects(advanceRelease(f.directory, 'promote', f.adapter), /双包未完整/);
  assert.deepEqual(f.adapter.entries(DIRECTORIES.latest), f.old);
  assert.equal(f.state().lastError.phase, 'promote');
});

test('上传部分失败后重试只上传缺失包', async t => {
  const f = fixture(t);
  f.adapter.failNext('upload', 'webview');
  await assert.rejects(advanceRelease(f.directory, 'publish', f.adapter));
  await advanceRelease(f.directory, 'publish', f.adapter);
  assert.equal(f.calls.filter(call => call[0] === 'upload' && call[1] === 'DSH1.0.0.apk').length, 1);
  assert.equal(f.state().phase, 'published');
});

test('上传成功但响应丢失，重试查询远程不重复上传', async t => {
  const f = fixture(t);
  f.adapter.failNext('upload', 'DSH1.0.0.apk', true);
  await assert.rejects(advanceRelease(f.directory, 'publish', f.adapter), /unknown outcome/);
  await advanceRelease(f.directory, 'publish', f.adapter);
  assert.equal(f.calls.filter(call => call[0] === 'upload' && call[1] === 'DSH1.0.0.apk').length, 1);
});

test('提升第二包失败不归档，重试恢复原旧包快照', async t => {
  const f = fixture(t);
  await advanceRelease(f.directory, 'publish', f.adapter);
  f.adapter.failNext('move', 'webview');
  await assert.rejects(advanceRelease(f.directory, 'promote', f.adapter), /offline move/);
  assert.deepEqual(f.state().previous, f.old);
  await assert.rejects(advanceRelease(f.directory, 'archive', f.adapter), /新双包未/);
  assert.equal(f.adapter.entries(DIRECTORIES.history).length, 0);
  await advanceRelease(f.directory, 'promote', f.adapter);
  await advanceRelease(f.directory, 'archive', f.adapter);
  assert.deepEqual(f.adapter.entries(DIRECTORIES.history), f.old);
});

test('移动成功但响应丢失，按目标摘要恢复而不重复移动', async t => {
  const f = fixture(t);
  await advanceRelease(f.directory, 'publish', f.adapter);
  f.adapter.failNext('move', 'DSH1.0.0.apk', true);
  await assert.rejects(advanceRelease(f.directory, 'promote', f.adapter), /unknown outcome/);
  await advanceRelease(f.directory, 'promote', f.adapter);
  assert.equal(f.calls.filter(call => call[0] === 'move' && call[3] === 'DSH1.0.0.apk').length, 1);
});

test('归档部分失败非成功状态，重试完成且不重复已归档文件', async t => {
  const f = fixture(t);
  await advanceRelease(f.directory, 'publish', f.adapter);
  await advanceRelease(f.directory, 'promote', f.adapter);
  f.adapter.failNext('move', 'webview');
  await assert.rejects(advanceRelease(f.directory, 'archive', f.adapter));
  assert.equal(f.state().phase, 'promoted');
  assert.equal(f.state().lastError.phase, 'archive');
  await advanceRelease(f.directory, 'archive', f.adapter);
  await advanceRelease(f.directory, 'archive', f.adapter);
  assert.equal(f.calls.filter(call => call[0] === 'move' && call[3] === 'DSH0.9.0.apk').length, 1);
});

test('当前目录非完整旧双包时禁止移动', async t => {
  const f = fixture(t);
  f.remote.set(DIRECTORIES.latest, [f.old[0]]);
  await advanceRelease(f.directory, 'publish', f.adapter);
  await assert.rejects(advanceRelease(f.directory, 'promote', f.adapter), /完整旧双包/);
  assert.equal(f.calls.filter(call => call[0] === 'move').length, 0);
});

test('同名不同摘要不可覆盖', async t => {
  const f = fixture(t);
  f.remote.set(`${DIRECTORIES.candidates}/${f.manifest.releaseId}`, [{ name: 'DSH1.0.0.apk', sha256: 'b'.repeat(64), bytes: 10 }]);
  await assert.rejects(advanceRelease(f.directory, 'publish', f.adapter), /不可覆盖/);
  assert.equal(f.calls.length, 0);
});

test('dirty、开发及未绑定源码候选拒绝正式分发', async t => {
  for (const override of [{ development: true }, { source: { dirty: true } }, { sourceBinding: 'unverified-existing-artifact' }]) {
    const f = fixture(t, override);
    await assert.rejects(advanceRelease(f.directory, 'publish', f.adapter), /禁止正式分发/);
    assert.equal(f.calls.length, 0);
  }
});

test('候选内容和manifest变化阻止恢复', async t => {
  const f = fixture(t);
  await advanceRelease(f.directory, 'publish', f.adapter);
  writeFileSync(join(f.directory, 'release-manifest.json'), json({ ...f.manifest, createdAt: 'changed' }));
  await assert.rejects(advanceRelease(f.directory, 'promote', f.adapter), /不可变 manifest/);
  const other = fixture(t);
  writeFileSync(join(other.directory, 'DSH1.0.0.apk'), 'changed');
  await assert.rejects(advanceRelease(other.directory, 'publish', other.adapter), /内容已变化/);
});

test('真实工具输出解析严格检查身份、target和签名', () => {
  assert.deepEqual(parseBadging("package: name='com.dsh.android' versionCode='115' versionName='0.5.0' platformBuildVersionName='15'\ntargetSdkVersion:'28'\n"), { applicationId: 'com.dsh.android', versionCode: 115, versionName: '0.5.0', targetSdk: 28, debuggable: false });
  assert.throws(() => parseBadging("package: name='x'"), /未返回完整/);
  assert.equal(parseCertificates(`Signer #1 certificate SHA-256 digest: ${'A'.repeat(64)}\n`), 'a'.repeat(64));
  assert.throws(() => parseCertificates('Verified'), /签名证书/);
});

test('正式门禁缺输入、not-run或skip都不能假绿', t => {
  const f = fixture(t);
  const path = join(f.directory, 'gates.json');
  for (const check of [{ level: 'L3', status: 'not-run' }, { level: 'L3', status: 'passed', command: 'device', evidence: 'result', skipped: 1 }]) {
    writeFileSync(path, json({ schemaVersion: 1, checks: [check] }));
    assert.throws(() => inspectGateReports([path], ['L3']), /门禁 L3/);
  }
  assert.throws(() => inspectGateReports([], ['L0']), /门禁 L0/);
  writeFileSync(path, json({ schemaVersion: 1, checks: [{ level: 'L3', status: 'passed', command: 'device', evidence: 'result', tests: 3, skipped: 0 }] }));
  assert.equal(inspectGateReports([path], ['L3']).length, 1);
});
