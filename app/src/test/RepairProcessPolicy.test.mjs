import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, isAbsolute, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('真实 Kotlin 目标停止策略只选择已知目标并拒绝 PID 复用和无关 Node', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const androidJar = process.env.ANDROID_PLATFORM_JAR;
  assert.ok(androidJar && isAbsolute(androidJar) && existsSync(androidJar), '需要显式真实 Android API JAR');
  const temp = mkdtempSync(join(tmpdir(), 'dsh-repair-process-'));
  try {
    const harness = join(temp, 'RepairProcessPolicyChecks.kt');
    writeFileSync(harness, readFileSync(join(root, 'app/src/test/RepairProcessPolicyChecks.kt.txt'), 'utf8'));
    const source = join(root, 'app/src/main/java/com/dsh/android/repair/RepairEngineQuiescence.kt');
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib, androidJar].join(delimiter);
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath,
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
      '-classpath', classpath, '-d', output, source, harness], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `生产进程策略编译失败\n${compile.stdout}\n${compile.stderr}`);
    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter),
      'com.dsh.android.repair.RepairProcessPolicyChecksKt'], { encoding: 'utf8', timeout: 60_000 });
    assert.equal(run.status, 0, `进程策略行为失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 10 production process policy checks/);
    console.log(run.stdout.trim());
  } finally {
    assert.equal(join(temp, '..'), tmpdir());
    assert.ok(temp.startsWith(join(tmpdir(), 'dsh-repair-process-')));
    rmSync(temp, { recursive: true, force: true });
  }
});
