import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// 编译并执行真实的备份导入安全逻辑（zip 条目路径解析 + 跨 flavor 路径改写）。
//
// 为什么用「抽取真实声明」而不是复制一份实现：UserDataImporter 是 object，依赖
// android.content.Context 与 DshApplication，整类在 JVM 上无法构造；但它那两个
// 决定安全性的**纯函数**只依赖 java.io.File。这里按声明原文抽取到 fixture 里编译，
// 不给它们写第二份实现（与 web/host 的双内核生命周期用例同一手法）。
const root = new URL('../../../', import.meta.url);
const { resolveKotlinTestRuntime, resolveRepositoryRoot } = await import(
  new URL('tools/kotlin-test-runtime.mjs', root).href
);

const sourcePath = 'app/src/main/java/com/dsh/android/backup/UserDataImporter.kt';

/** 取单行声明（无花括号的 `val`）。 */
function lineDeclaration(text, marker) {
  const start = text.indexOf(marker);
  assert.ok(start >= 0, `缺少生产声明：${marker}`);
  return text.slice(start, text.indexOf('\n', start));
}

/** 取出从 marker 开始的整段声明（含花括号体），注释/字符串里的括号不参与配对。 */
function declaration(text, marker) {
  const start = text.indexOf(marker);
  assert.ok(start >= 0, `缺少生产声明：${marker}`);
  let depth = 0;
  let quote = false;
  let line = false;
  let block = false;
  const open = text.indexOf('{', start);
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    const next = text[i + 1];
    if (line) { if (c === '\n') line = false; continue; }
    if (block) { if (c === '*' && next === '/') { block = false; i++; } continue; }
    if (quote) { if (c === '\\') i++; else if (c === '"') quote = false; continue; }
    if (c === '/' && next === '/') { line = true; i++; continue; }
    if (c === '/' && next === '*') { block = true; i++; continue; }
    if (c === '"') { quote = true; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return text.slice(start, i + 1);
  }
  throw new Error(`声明未闭合：${marker}`);
}

test('备份导入拒绝 zip-slip 且只改写已知包名的绝对路径', () => {
  const runtime = resolveKotlinTestRuntime();
  const checkout = resolveRepositoryRoot(import.meta.url);
  const source = join(checkout, sourcePath);
  assert.ok(existsSync(source), `缺少真实输入：${source}`);
  const text = readFileSync(source, 'utf8');
  const known = lineDeclaration(text, 'private val KNOWN_PACKAGES').replace(/^private /, '');
  const rewrite = declaration(text, 'private fun rewritePackagePaths').replace(/^private /, '');
  const resolve = declaration(text, 'private fun safeResolve').replace(/^private /, '');

  const temp = mkdtempSync(join(tmpdir(), 'dsh-backup-safety-'));
  try {
    const fixture = join(temp, 'BackupSafetyFixture.kt');
    writeFileSync(fixture, `package com.dsh.android.backup
import java.io.File

object BackupSafetyFixture {
    ${known}
    ${rewrite}
    ${resolve}
}
`);
    const checks = join(checkout, 'app/src/test/BackupSafetyChecks.kt.txt');
    assert.ok(existsSync(checks), `缺少行为检查：${checks}`);
    const harness = join(temp, 'BackupSafetyChecks.kt');
    writeFileSync(harness, readFileSync(checks, 'utf8'));
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib].join(delimiter);
    const compile = spawnSync(runtime.java, [
      '-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
      '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output, fixture, harness,
    ], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `备份安全逻辑编译失败\n${compile.stdout}\n${compile.stderr}`);

    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter), 'com.dsh.android.backup.BackupSafetyChecksKt'], {
      encoding: 'utf8', timeout: 60_000,
    });
    assert.equal(run.status, 0, `备份安全行为检查失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 5 production behavior checks/);
    console.log(run.stdout.trim());
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
