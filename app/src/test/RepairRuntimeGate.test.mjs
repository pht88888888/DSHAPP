import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('production repair gate fails closed and revalidates native permits (narrow host OS fixture)', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const json = process.env.ENGINE_TEST_JSON_JAR;
  assert.ok(json && existsSync(json), 'ENGINE_TEST_JSON_JAR must point to actual host JSON jar');
  const temp = mkdtempSync(join(tmpdir(), 'dsh-runtime-gate-'));
  try {
    const fixtures = join(root, 'app/src/test/fixtures/gate');
    const sources = readdirSync(fixtures).filter(x => x.endsWith('.kt.txt')).map(name => {
      const output = join(temp, name.replace(/\.txt$/, ''));
      writeFileSync(output, readFileSync(join(fixtures, name))); return output;
    });
    for (const [input, output] of [
      ['app/src/test/fixtures/repair-file-io/AndroidSystem.kt.txt', 'AndroidSystem.kt'],
      ['app/src/test/RepairRuntimeGateChecks.kt.txt', 'RepairRuntimeGateChecks.kt'],
    ]) {
      const file = join(temp, output);
      let text = readFileSync(join(root, input), 'utf8');
      if (output === 'AndroidSystem.kt') text = text.replace('val st_ino: Long)', 'val st_ino: Long, val st_uid: Int = 0)').replace('object Os {', 'object Os {\n fun stat(path: String): StructStat = lstat(path)');
      writeFileSync(file, text); sources.push(file);
    }
    const processFixture = join(temp, 'ProcessPolicy.kt');
    writeFileSync(processFixture, `package com.dsh.android.repair
object RepairEngineQuiescence { object ProcessPolicy {
 fun startTime(raw: String): String? = raw.substringAfterLast(") ").trim().split(Regex("\\\\s+")).getOrNull(19)
} }`);
    sources.push(processFixture);
    sources.push(join(root, 'app/src/main/java/com/dsh/android/repair/RepairRuntimeGate.kt'));
    sources.push(join(root, 'repair/contract/src/main/java/com/dsh/android/repair/contract/RepairProtocol.kt'));
    const cp = [runtime.stdlib, json].join(delimiter);
    const classes = join(temp, 'classes');
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath,
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
      '-classpath', cp, '-d', classes, ...sources], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `Gate production source compile failed\n${compile.stdout}\n${compile.stderr}`);
    const data = join(temp, 'data'); mkdirSync(data);
    const run = spawnSync(runtime.java, ['--add-opens=java.base/java.io=ALL-UNNAMED', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
      '-cp', [classes, cp].join(delimiter), 'com.dsh.android.repair.RepairRuntimeGateChecksKt', data],
      { encoding: 'utf8', timeout: 30_000 });
    assert.equal(run.status, 0, `Gate behavior failed\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 37 production repair gate checks/);
    console.log(run.stdout.trim());
  } finally {
    assert.equal(join(temp, '..'), tmpdir()); assert.ok(temp.startsWith(join(tmpdir(), 'dsh-runtime-gate-')));
    rmSync(temp, { recursive: true, force: true });
  }
});

test('validation uses normal engine with no background Activity, health, stable observation and atomic evidence', () => {
  const root = resolveRepositoryRoot(import.meta.url);
  const source = readFileSync(join(root, 'app/src/main/java/com/dsh/android/repair/RepairValidationService.kt'), 'utf8');
  assert.match(source, /START_NOT_STICKY/);
  assert.match(source, /ensureExtracted\(ASSET_STAMP\)/);
  assert.match(source, /host\.start\(startupTimeoutMs = \(accepted\.deadlineElapsed - SystemClock\.elapsedRealtime\(\)\)\.coerceAtLeast\(1\)\)/);
  assert.match(source, /startForeground\(NOTIFICATION/);
  assert.match(source, /stopForeground\(STOP_FOREGROUND_REMOVE\)/);
  assert.match(source, /active \?: return@withTimeout/);
  assert.match(source, /if \(!writeAttempt\(task, attempt, if \(outcome == "engine_ready"\) "passed" else "failed"/);
  assert.ok(source.indexOf('writeAttempt(task, attempt, "starting"') < source.indexOf('RepairRuntimeGate.acquire('));
  assert.ok(source.indexOf('withTimeout(300000L)') < source.indexOf('ensureRuntimeInitialized(accepted)'));
  assert.match(source, /service_foreground_failed/);
  assert.match(source, /responseCode == 200/);
  assert.match(source, /delay\(1500\)/);
  assert.match(source, /AtomicFile/);
  assert.match(source, /if \(!writeAttempt/);
  assert.match(source, /withContext\(NonCancellable\)/);
  assert.match(source, /stopForRepair/);
  assert.doesNotMatch(source, /startActivity|destroyForcibly|START_STICKY\b/);
  const engine = readFileSync(join(root, 'runtime/engine/src/main/java/com/dsh/android/engine/EngineManager.kt'), 'utf8');
  const application = readFileSync(join(root, 'app/src/main/java/com/dsh/android/core/DshApplication.kt'), 'utf8');
  assert.equal((engine.match(/\.destroyForcibly\(\)/g) ?? []).length, 1);
  assert.match(engine, /if \(!executionGate\.allowForceStop\(\)\) return false\s+p\.destroyForcibly\(\)/);
  assert.match(engine, /if \(!stopEngineResources\(\)\)/);
  assert.match(application, /override fun allowForceStop\(\) = .*RepairRuntimeGate\.activePermit\(\) == null &&\s+!.*RepairRuntimeGate\.blocked/);
});
