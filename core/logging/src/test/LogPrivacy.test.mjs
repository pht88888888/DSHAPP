import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// 编译并执行真实的 core/logging Redactor.kt（纯 Kotlin，无 Android 依赖）。
// 断言写在 RedactorChecks.kt.txt 里：每条都对应一种「实际会漏抹」的形态，
// 不改写实现，也不在 JS 侧重写一份正则。
const root = new URL('../../../../', import.meta.url);
const { resolveKotlinTestRuntime, resolveRepositoryRoot } = await import(
  new URL('tools/kotlin-test-runtime.mjs', root).href
);

test('日志脱敏兜住真实密钥形态（含历史漏抹回归）', () => {
  const runtime = resolveKotlinTestRuntime();
  const checkout = resolveRepositoryRoot(import.meta.url);
  const source = join(checkout, 'core/logging/src/main/java/com/dsh/android/log/Redactor.kt');
  const checks = join(checkout, 'core/logging/src/test/RedactorChecks.kt.txt');
  for (const path of [source, checks]) assert.ok(existsSync(path), `缺少真实输入：${path}`);

  const temp = mkdtempSync(join(tmpdir(), 'dsh-redactor-'));
  try {
    const harness = join(temp, 'RedactorChecks.kt');
    writeFileSync(harness, readFileSync(checks, 'utf8'));
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib].join(delimiter);
    const compile = spawnSync(runtime.java, [
      '-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
      '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output, source, harness,
    ], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `Redactor 编译失败\n${compile.stdout}\n${compile.stderr}`);

    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter), 'com.dsh.android.log.RedactorChecksKt'], {
      encoding: 'utf8', timeout: 60_000,
    });
    assert.equal(run.status, 0, `Redactor 行为检查失败\n${run.stdout}\n${run.stderr}`);
    // 场景数是断言的一部分：场景被删掉时这条检查必须失败，而不是静默变绿。
    assert.match(run.stdout, /passed 8 production behavior checks/);
    console.log(run.stdout.trim());
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
