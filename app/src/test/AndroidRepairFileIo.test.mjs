import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, isAbsolute, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('真实生产 Android 文件 adapter：窄 OS 仿真验证可信根、NOFOLLOW、事务与 FD 释放（非真机/SELinux）', () => {
  const runtime = resolveKotlinTestRuntime();
  const root = resolveRepositoryRoot(import.meta.url);
  const jsonJar = process.env.ENGINE_TEST_JSON_JAR;
  assert.ok(jsonJar && isAbsolute(jsonJar) && existsSync(jsonJar), '需要显式真实 JSON JAR');
  const temp = mkdtempSync(join(tmpdir(), 'dsh-android-file-io-'));
  try {
    const fixtureRoot = join(root, 'app/src/test/fixtures/repair-file-io');
    const harnesses = [
      ['AndroidRepairFileIoChecks.kt', join(root, 'app/src/test/AndroidRepairFileIoChecks.kt.txt')],
      ['AndroidSystem.kt', join(fixtureRoot, 'AndroidSystem.kt.txt')],
      ['ParcelFileDescriptor.kt', join(fixtureRoot, 'ParcelFileDescriptor.kt.txt')],
    ].map(([name, source]) => {
      const target = join(temp, name);
      writeFileSync(target, readFileSync(source, 'utf8'));
      return target;
    });
    const sources = ['RepairFileIo.kt', 'AndroidRepairFileIo.kt', 'RepairFileBroker.kt']
      .map(name => join(root, 'app/src/main/java/com/dsh/android/repair/files', name));
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib, jsonJar].join(delimiter);
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath,
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
      '-classpath', classpath, '-d', output, ...sources, ...harnesses], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `生产 adapter 与窄 OS 仿真编译失败\n${compile.stdout}\n${compile.stderr}`);
    const boot = process.env.REPAIR_BOOT_FIXTURE;
    assert.ok(isAbsolute(boot) && existsSync(boot), '需要真实 dsh-app-boot lib/index.js 回归输入 REPAIR_BOOT_FIXTURE');
    const run = spawnSync(runtime.java, ['--add-opens=java.base/java.io=ALL-UNNAMED',
      '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '-cp', [output, classpath].join(delimiter),
      'com.dsh.android.repair.files.AndroidRepairFileIoChecksKt', boot], { encoding: 'utf8', timeout: 60_000 });
    assert.equal(run.status, 0, `生产 adapter OS 仿真行为失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed \d+ production adapter checks \(host OS simulation; NOT Android\/SELinux\)/);
    console.log(run.stdout.trim());
  } finally {
    assert.equal(join(temp, '..'), tmpdir());
    assert.ok(temp.startsWith(join(tmpdir(), 'dsh-android-file-io-')));
    rmSync(temp, { recursive: true, force: true });
  }
});
