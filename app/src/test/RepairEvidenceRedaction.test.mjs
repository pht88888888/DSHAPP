import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, isAbsolute, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('生产 Kotlin bounded/redacted engine/native/Web 故障证据（非设备）', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const jsonJar = process.env.ENGINE_TEST_JSON_JAR;
  assert.ok(jsonJar && isAbsolute(jsonJar) && existsSync(jsonJar), '显式真实 ENGINE_TEST_JSON_JAR 必填，不跳过');
  const temporary = mkdtempSync(join(tmpdir(), 'dsh-evidence-redaction-'));
  try {
    const harness = join(temporary, 'RepairEvidenceRedactionChecks.kt');
    writeFileSync(harness, readFileSync(join(root, 'app/src/test/RepairEvidenceRedactionChecks.kt.txt'), 'utf8'));
    const sources = ['runtime/engine/src/main/java/com/dsh/android/engine/EngineFailureProjection.kt',
      'app/src/main/java/com/dsh/android/repair/RepairEvidenceRedaction.kt',
      'app/src/main/java/com/dsh/android/repair/RepairFailureEvidence.kt'].map(path => join(root, path));
    const classpath = [runtime.stdlib, jsonJar].join(delimiter), output = join(temporary, 'classes');
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
      '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output, ...sources, harness], { encoding: 'utf8', timeout: 120000 });
    assert.equal(compile.status, 0, `真实生产Kotlin编译失败\n${compile.stdout}\n${compile.stderr}`);
    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter),
      'com.dsh.android.repair.RepairEvidenceRedactionChecksKt'], { encoding: 'utf8', timeout: 60000 });
    assert.equal(run.status, 0, `故障证据行为失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 18 production evidence-redaction checks; NOT Android\/device proof/);
    console.log(run.stdout.trim());
  } finally {
    assert.equal(resolve(temporary, '..'), resolve(tmpdir()));
    assert.ok(temporary.startsWith(join(tmpdir(), 'dsh-evidence-redaction-')));
    rmSync(temporary, { recursive: true, force: true });
  }
});
