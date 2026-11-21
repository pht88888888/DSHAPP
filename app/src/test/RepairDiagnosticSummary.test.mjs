import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, isAbsolute, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('诊断摘要执行真实 Kotlin 投影，隔离启动轮次且不返回自由文本', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const jsonJar = process.env.ENGINE_TEST_JSON_JAR;
  assert.ok(jsonJar && isAbsolute(jsonJar) && existsSync(jsonJar), '需要显式真实 ENGINE_TEST_JSON_JAR');
  const sources = [
    'app/src/main/java/com/dsh/android/repair/RepairDiagnosticSummary.kt',
    'repair/contract/src/main/java/com/dsh/android/repair/contract/RepairProtocol.kt',
  ].map(name => join(root, name));
  for (const source of sources) assert.ok(existsSync(source));
  const temp = mkdtempSync(join(tmpdir(), 'dsh-repair-summary-'));
  try {
    const checks = join(temp, 'RepairDiagnosticSummaryChecks.kt');
    writeFileSync(checks, readFileSync(join(root, 'app/src/test/RepairDiagnosticSummaryChecks.kt.txt'), 'utf8'));
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib, jsonJar].join(delimiter);
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath,
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
      '-classpath', classpath, '-d', output, ...sources, checks], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `诊断生产投影编译失败\n${compile.stdout}\n${compile.stderr}`);
    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter),
      'com.dsh.android.repair.RepairDiagnosticSummaryChecksKt'], { encoding: 'utf8', timeout: 60_000 });
    assert.equal(run.status, 0, `诊断投影行为失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 7 production behavior checks/);
    console.log(run.stdout.trim());
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
