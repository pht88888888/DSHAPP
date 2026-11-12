import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// 编译并执行真实的 runtime/engine EngineFilePolicy（工作区入口链接 + 凭据文件权限收窄）。
// 平台替身只有一个 android.util.Log：DshLog 转发到它，其余全走真实生产代码。
const root = new URL('../../../../', import.meta.url);
const { resolveKotlinTestRuntime, resolveRepositoryRoot } = await import(
  new URL('tools/kotlin-test-runtime.mjs', root).href
);

// 只补齐 DshLog 真正调用的那几个签名，方法名/参数与 android.util.Log 一致。
const androidLog = `package android.util
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7
    fun v(tag: String, msg: String): Int = 0
    fun v(tag: String, msg: String, tr: Throwable?): Int = 0
    fun d(tag: String, msg: String): Int = 0
    fun d(tag: String, msg: String, tr: Throwable?): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String, tr: Throwable?): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String, tr: Throwable?): Int = 0
    fun e(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String, tr: Throwable?): Int = 0
    fun wtf(tag: String, msg: String): Int = 0
    fun wtf(tag: String, msg: String, tr: Throwable?): Int = 0
    fun println(priority: Int, tag: String, msg: String): Int = 0
    fun isLoggable(tag: String, level: Int): Boolean = false
    fun getStackTraceString(t: Throwable?): String = t?.stackTraceToString() ?: ""
}
`;

test('工作区入口链接与凭据文件权限的真实生产行为', () => {
  const runtime = resolveKotlinTestRuntime();
  const checkout = resolveRepositoryRoot(import.meta.url);
  const engine = join(checkout, 'runtime/engine/src/main/java/com/dsh/android/engine');
  const sources = [
    join(engine, 'EngineFilePolicy.kt'),
    join(engine, 'constants/EngineFilePermissions.kt'),
    join(engine, 'EngineConstants.kt'),
    join(checkout, 'core/logging/src/main/java/com/dsh/android/log/DshLog.kt'),
  ];
  for (const path of sources) assert.ok(existsSync(path), `缺少真实输入：${path}`);
  const checks = join(checkout, 'runtime/engine/src/test/EngineFilePolicyChecks.kt.txt');
  assert.ok(existsSync(checks), `缺少行为检查：${checks}`);

  const temp = mkdtempSync(join(tmpdir(), 'dsh-engine-file-policy-'));
  try {
    const fixture = join(temp, 'AndroidLog.kt');
    writeFileSync(fixture, androidLog);
    const harness = join(temp, 'EngineFilePolicyChecks.kt');
    writeFileSync(harness, readFileSync(checks, 'utf8'));
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib].join(delimiter);
    const compile = spawnSync(runtime.java, [
      '-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
      '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output,
      ...sources, fixture, harness,
    ], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `EngineFilePolicy 编译失败\n${compile.stdout}\n${compile.stderr}`);

    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter), 'com.dsh.android.engine.EngineFilePolicyChecksKt'], {
      encoding: 'utf8', timeout: 60_000,
    });
    assert.equal(run.status, 0, `EngineFilePolicy 行为检查失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 7 production behavior checks/);
    console.log(run.stdout.trim());
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
