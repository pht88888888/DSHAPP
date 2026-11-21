import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, isAbsolute, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

const scenarios = [
  'new_grant_is_not_task', 'begin_reaches_editable', 'permit_identity_and_phase',
  'permit_deadline_and_revoke', 'report_requires_bound_identity', 'failed_report_is_terminal',
  'ordinary_apply_is_queued', 'grant_covers_sensitive_apply', 'task_complete_grant_read_only',
  'restart_has_no_durable_permit', 'stage_admission_blocks_validate_not_end',
  'ui_requires_fresh_engine_validation', 'ui_success_completes_and_keeps_read_grant',
  'ticket_expiry_oneuse_and_digest', 'validate_target_cannot_request_ui',
  'validation_permit_rejected_fails_fast', 'validation_slow_handshake_does_not_fail_at_fifteen_seconds',
  'validation_foreign_success_cannot_handshake', 'validation_runtime_exit_fails_fast',
];

test('真实生产维修协调者：任务准入、PID/轮次permit、撤销与只读收尾（窄端口替身，非设备）', () => {
  const root = resolveRepositoryRoot(import.meta.url);
  const runtime = resolveKotlinTestRuntime();
  const jsonJar = process.env.ENGINE_TEST_JSON_JAR;
  assert.ok(jsonJar && isAbsolute(jsonJar) && existsSync(jsonJar), '需要显式真实 JSON JAR');
  const temp = mkdtempSync(join(tmpdir(), 'dsh-repair-task-'));
  try {
    const harness = join(temp, 'RepairTaskCoordinatorChecks.kt');
    writeFileSync(harness, readFileSync(join(root, 'app/src/test/RepairTaskCoordinatorChecks.kt.txt'), 'utf8'));
    const fixtures = join(root, 'app/src/test/fixtures/task');
    const sources = readdirSync(fixtures).filter(name => name.endsWith('.kt')).map(name => join(fixtures, name));
    // Compile unmodified production sources; no source-string substitution or imitation state machine.
    sources.push(join(root, 'app/src/main/java/com/dsh/android/repair/RepairTaskCoordinator.kt'),
      join(root, 'app/src/main/java/com/dsh/android/repair/RepairChangeApproval.kt'),
      join(root, 'runtime/engine/src/main/java/com/dsh/android/engine/EngineRepairLock.kt'), harness);
    const classpath = [runtime.stdlib, jsonJar].join(delimiter);
    const output = join(temp, 'classes');
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath,
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
      '-classpath', classpath, '-d', output, ...sources], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `生产协调者编译失败\n${compile.stdout}\n${compile.stderr}`);
    const failures = [];
    for (const scenario of scenarios) {
      const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter),
        'com.dsh.android.repair.RepairTaskCoordinatorChecksKt', scenario], { encoding: 'utf8', timeout: 15_000 });
      if (run.status !== 0) failures.push(`${scenario}: ${run.stdout}\n${run.stderr}`);
      else { assert.match(run.stdout, new RegExp(`PASS ${scenario}`)); console.log(run.stdout.trim()); }
    }
    assert.deepEqual(failures, [], `协调者行为失败\n${failures.join('\n')}`);
    console.log(`passed ${scenarios.length} production task coordinator scenarios; 0 skipped`);
  } finally {
    assert.equal(resolve(temp, '..'), resolve(tmpdir()));
    assert.ok(temp.startsWith(join(tmpdir(), 'dsh-repair-task-')));
    rmSync(temp, { recursive: true, force: true });
  }
});
