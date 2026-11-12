import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// 编译并执行真实的「浏览器侧那一半坏没坏」判据（WebClientHealth）。
//
// 与 BackupSafety 用例不同，这里**不需要抽取声明**：WebClientHealth 刻意不依赖任何
// Android 类型（没有 Context / Log / Bundle），整份生产文件可以直接编译执行 ——
// 判据本身也因此不可能"只在注释里成立"。
//
// 事故背景（CDY-TN90 / 2026-11-12）：旧判据 grep `首帧正文="HARNESS`，而那是
// polls=1 的快照，正常启动也会带上 ⇒ 健康启动被降级到救援模式且层级粘住；
// 同时用户换掉启动动画/主题后，真正的卡死再也检测不到。
const root = new URL('../../../', import.meta.url);
const { resolveKotlinTestRuntime, resolveRepositoryRoot } = await import(
  new URL('tools/kotlin-test-runtime.mjs', root).href
);

const sourcePath = 'app/src/main/java/com/dsh/android/ui/WebClientHealth.kt';
const checksPath = 'app/src/test/WebClientHealthChecks.kt.txt';

test('浏览器侧健康判据不依赖官方启动页文案', () => {
  const runtime = resolveKotlinTestRuntime();
  const checkout = resolveRepositoryRoot(import.meta.url);
  const source = join(checkout, sourcePath);
  const checks = join(checkout, checksPath);
  assert.ok(existsSync(source), `缺少真实输入：${source}`);
  assert.ok(existsSync(checks), `缺少行为检查：${checks}`);

  const sourceText = readFileSync(source, 'utf8');
  // 判据文件里**不允许**出现任何 Android 依赖，否则这条用例会悄悄退化成"注释保证"。
  // ⚠️ 只看**真正的 Android 依赖**：判据文件的包名本身就是 `com.dsh.android.ui`，
  //   用 'android.' 做子串会命中包名与 `dsh-android-mobile` 这些正常文字。
  for (const forbidden of ['import android', 'androidx.', 'android.content', 'android.util']) {
    assert.ok(!sourceText.includes(forbidden), `判据文件不得依赖 ${forbidden}（要在 JVM 上真跑）`);
  }
  // 判据里**不允许**再出现官方启动页的文案常量（本轮修掉的正是它）。
  assert.ok(
    !/HARNESS/.test(sourceText.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')),
    '判据代码里不得残留官方启动页文案（HARNESS）作为判据',
  );

  const temp = mkdtempSync(join(tmpdir(), 'dsh-web-client-health-'));
  try {
    const fixture = join(temp, 'WebClientHealth.kt');
    writeFileSync(fixture, sourceText);
    const harness = join(temp, 'WebClientHealthChecks.kt');
    writeFileSync(harness, readFileSync(checks, 'utf8'));
    const output = join(temp, 'classes');
    const classpath = [runtime.stdlib].join(delimiter);
    const compile = spawnSync(runtime.java, [
      '-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
      '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output, fixture, harness,
    ], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(compile.status, 0, `健康判据编译失败\n${compile.stdout}\n${compile.stderr}`);

    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter), 'com.dsh.android.ui.WebClientHealthChecksKt'], {
      encoding: 'utf8', timeout: 60_000,
    });
    assert.equal(run.status, 0, `健康判据行为检查失败\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /passed 13 production behavior checks/);
    console.log(run.stdout.trim());
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
