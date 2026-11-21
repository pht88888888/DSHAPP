import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, isAbsolute, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('真实 Kotlin 文件候选事务覆盖约束、原子发布、崩溃恢复与冲突安全回滚', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const jsonJar = process.env.ENGINE_TEST_JSON_JAR;
  const androidJar = process.env.ANDROID_PLATFORM_JAR;
  for (const input of [jsonJar, androidJar]) assert.ok(input && isAbsolute(input) && existsSync(input), '需要显式真实 JSON 和 Android API JAR');
  const temp = mkdtempSync(join(tmpdir(), 'dsh-repair-files-'));
  try {
    const harness = join(temp, 'RepairFileBrokerChecks.kt');
    writeFileSync(harness, readFileSync(join(root, 'app/src/test/RepairFileBrokerChecks.kt.txt'), 'utf8'));
    const sources = ['RepairFileIo.kt', 'AndroidRepairFileIo.kt', 'RepairFileBroker.kt']
      .map(name => join(root, 'app/src/main/java/com/dsh/android/repair/files', name));
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib, jsonJar, androidJar].join(delimiter);
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath,
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
      '-classpath', classpath, '-d', output, ...sources, harness], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `生产文件事务编译失败\n${compile.stdout}\n${compile.stderr}`);
    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter),
      'com.dsh.android.repair.files.RepairFileBrokerChecksKt'], { encoding: 'utf8', timeout: 60_000 });
    assert.equal(run.status, 0, `文件事务行为失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 19 production behavior checks/);
    console.log(run.stdout.trim());
  } finally {
    assert.equal(join(temp, '..'), tmpdir());
    assert.ok(temp.startsWith(join(tmpdir(), 'dsh-repair-files-')));
    rmSync(temp, { recursive: true, force: true });
  }
});
