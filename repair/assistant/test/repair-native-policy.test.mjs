import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, isAbsolute, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

function removeTestDirectory(directory) {
  const absolute = resolve(directory);
  assert.equal(dirname(absolute), resolve(tmpdir()));
  assert.ok(absolute.startsWith(join(resolve(tmpdir()), 'dsh-repair-native-')));
  rmSync(absolute, { recursive: true, force: true });
}

test('真实原生授权、确认票据、跨进程锁与损坏 shebang 证据投影行为', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const jsonJar = process.env.ENGINE_TEST_JSON_JAR;
  assert.ok(jsonJar && isAbsolute(jsonJar) && existsSync(jsonJar), '需要显式真实 ENGINE_TEST_JSON_JAR');
  const temporary = mkdtempSync(join(tmpdir(), 'dsh-repair-native-'));
  try {
    // A deterministic host Node syntax fixture, NOT an Android/model repair acceptance test.
    const bin = join(temporary, 'data/user/0/com.dsh.android/files/engine/node_modules/@deepseek-ai/dsh/lib/bin.js');
    mkdirSync(dirname(bin), { recursive: true });
    const original = '#!/usr/bin/env node\nconsole.log("fixture-secret");\n';
    writeFileSync(bin, original);
    const check = () => spawnSync(process.execPath, ['--check', bin], { encoding: 'utf8', timeout: 10_000 });
    assert.equal(check().status, 0, '未损坏的原件必须先通过 host Node 语法检查');
    writeFileSync(bin, '// deliberately broken fixture\n' + original);
    const damaged = check();
    assert.notEqual(damaged.status, 0);
    assert.match(damaged.stderr, /bin\.js:2/);
    assert.match(damaged.stderr, /SyntaxError: Invalid or unexpected token/);
    const stdout = join(temporary, 'child-output.txt');
    writeFileSync(stdout, damaged.stdout + damaged.stderr);
    const harness = join(temporary, 'RepairNativePolicyChecks.kt');
    writeFileSync(harness, readFileSync(join(root, 'repair/assistant/test/RepairNativePolicyChecks.kt.txt'), 'utf8'));
    const sources = [
      'repair/contract/src/main/java/com/dsh/android/repair/contract/RepairProtocol.kt',
      'repair/contract/src/main/java/com/dsh/android/repair/contract/RepairJsonLimits.kt',
      'app/src/main/java/com/dsh/android/repair/RepairConsentWindow.kt',
      'app/src/main/java/com/dsh/android/repair/RepairChangeApproval.kt',
      'app/src/main/java/com/dsh/android/repair/RepairFailureEvidence.kt',
      'app/src/main/java/com/dsh/android/repair/RepairEvidenceRedaction.kt',
      'runtime/engine/src/main/java/com/dsh/android/engine/EngineRepairLock.kt',
      'runtime/engine/src/main/java/com/dsh/android/engine/EngineFailureProjection.kt',
    ].map(path => join(root, path));
    const output = join(temporary, 'classes');
    const classpath = [runtime.stdlib, jsonJar].join(delimiter);
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath,
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
      '-classpath', classpath, '-d', output, ...sources, harness], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `生产原生策略 Kotlin 编译失败\n${compile.stdout}\n${compile.stderr}`);
    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter),
      'com.dsh.android.repair.RepairNativePolicyChecksKt', temporary, stdout], { encoding: 'utf8', timeout: 60_000 });
    assert.equal(run.status, 0, `原生策略行为失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 19 production native-policy behavior checks/);
    console.log(run.stdout.trim());
    // Restore the exact retained bytes manually to prove only fixture recoverability.
    writeFileSync(bin, original);
    assert.equal(readFileSync(bin, 'utf8'), original);
    assert.equal(check().status, 0, '手工恢复原件后必须再次通过 host Node 语法检查');
    console.log('PASS host Node rejected line-2 shebang and accepted exact manually restored fixture; not model/Android proof');
  } finally { removeTestDirectory(temporary); }
});
