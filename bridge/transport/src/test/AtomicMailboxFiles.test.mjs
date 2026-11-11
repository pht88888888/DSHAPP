import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { after, before, test } from 'node:test';

const here = dirname(fileURLToPath(import.meta.url));
let work;
let runtime;
let repositoryRoot;
async function loadRuntime() {
  let root = here;
  while (!existsSync(join(root, 'settings.gradle.kts')) || !existsSync(join(root, 'tools/kotlin-test-runtime.mjs'))) {
    const parent = dirname(root);
    assert.notEqual(parent, root, '找不到仓库测试工具');
    root = parent;
  }
  const helper = await import(pathToFileURL(join(root, 'tools/kotlin-test-runtime.mjs')).href);
  repositoryRoot = helper.resolveRepositoryRoot(import.meta.url);
  return helper.resolveKotlinTestRuntime();
}

const cases = `package com.dsh.android.bridge.transport
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun main(args: Array<String>) {
 val root = File(System.getProperty("java.io.tmpdir"), args.single()).apply { mkdirs() }
 fun temporaryFiles() = root.listFiles()!!.filter { it.name.startsWith(".") && it.name.endsWith(".tmp") }
 fun expectIoFailure(action: () -> Unit) {
  var failed = false
  try { action() } catch (_: IOException) { failed = true }
  check(failed) { "应抛出真实文件系统异常" }
 }
 when (args.single()) {
  "replace-utf8" -> {
   val target = File(root, "browser-result.json").apply { writeText("old") }
   val text = "中文回执\\n✓"
   AtomicMailboxFiles.publishText(target, text)
   check(target.readText() == text && temporaryFiles().isEmpty())
  }
  "binary-publication" -> {
   val target = File(root, "browser-cmd.receipt")
   val bytes = byteArrayOf(0, 1, -1, 13, 10, 127)
   AtomicMailboxFiles.publish(target, bytes)
   check(target.readBytes().contentEquals(bytes) && temporaryFiles().isEmpty())
  }
  "missing-parent" -> {
   val target = File(root, "missing/result.json")
   expectIoFailure { AtomicMailboxFiles.publishText(target, "new") }
   check(!target.parentFile!!.exists() && root.listFiles()!!.isEmpty())
  }
  "move-failure-cleanup" -> {
   val target = File(root, "result.json").apply { mkdirs() }
   val marker = File(target, "existing").apply { writeText("unchanged") }
   expectIoFailure { AtomicMailboxFiles.publishText(target, "new") }
   check(marker.readText() == "unchanged" && temporaryFiles().isEmpty())
  }
  "old-receipt-survives-failure" -> {
   // 目标名称合法，但唯一临时文件的前缀加随机后缀超过文件系统名称上限。
   // 真正的 createTempFile 失败必须原样抛出，不能退回 writeText 截断旧回执。
   val target = File(root, "r".repeat(240)).apply { writeText("valid-old-receipt") }
   expectIoFailure { AtomicMailboxFiles.publishText(target, "must-not-overwrite") }
   check(target.readText() == "valid-old-receipt" && temporaryFiles().isEmpty())
  }
  "concurrent-whole-results" -> {
   val target = File(root, "result.json")
   val a = "A".repeat(96 * 1024)
   val b = "B".repeat(96 * 1024)
   target.writeText("initial")
   val start = CountDownLatch(1)
   val firstRead = CountDownLatch(1)
   val writersDone = CountDownLatch(2)
   val stopped = AtomicBoolean(false)
   val reads = AtomicInteger(0)
   val failure = AtomicReference<Throwable?>()
   val reader = Thread {
    try {
     start.await()
     while (!stopped.get()) {
      val text = target.readText()
      check(text == "initial" || text == a || text == b) { "读到半个结果" }
      reads.incrementAndGet()
      firstRead.countDown()
     }
    } catch (error: Throwable) { failure.compareAndSet(null, error); firstRead.countDown() }
   }.apply { isDaemon = true; start() }
   for (text in listOf(a, b)) Thread {
    try {
     start.await(); firstRead.await()
     repeat(30) { AtomicMailboxFiles.publishText(target, text) }
    } catch (error: Throwable) { failure.compareAndSet(null, error) }
    finally { writersDone.countDown() }
   }.apply { isDaemon = true; start() }
   start.countDown()
   try { check(writersDone.await(10, TimeUnit.SECONDS)) { "并发发布未结束" } }
   finally { stopped.set(true); reader.join(2_000L) }
   failure.get()?.let { throw it }
   check(reads.get() > 0 && temporaryFiles().isEmpty())
   check(target.readText() == a || target.readText() == b)
  }
  else -> error("未知测试")
 }
 println("PASS " + args.single())
}
`;

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024, timeout: 30_000 });
  assert.equal(result.status, 0, `${command} 失败\n${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

before(async () => {
  runtime = await loadRuntime();
  work = mkdtempSync(join(tmpdir(), 'dsh-atomic-mailbox-'));
  const fixture = join(work, 'AtomicMailboxCases.kt');
  writeFileSync(fixture, cases);
  run(runtime.java, ['-cp', runtime.classpath,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', runtime.stdlib, '-d', join(work, 'classes'), fixture,
    join(repositoryRoot, 'bridge/transport/src/main/java/com/dsh/android/bridge/transport/AtomicMailboxFiles.kt')]);
});
after(() => {
  if (!work) return;
  assert.equal(dirname(work), tmpdir());
  assert.ok(work.startsWith(join(tmpdir(), 'dsh-atomic-mailbox-')));
  rmSync(work, { recursive: true });
});
for (const name of ['replace-utf8', 'binary-publication', 'missing-parent', 'move-failure-cleanup',
  'old-receipt-survives-failure', 'concurrent-whole-results']) {
  test(name, () => {
    const output = run(runtime.java, ['-cp', `${join(work, 'classes')}${delimiter}${runtime.stdlib}`,
      `-Djava.io.tmpdir=${work}`, 'com.dsh.android.bridge.transport.AtomicMailboxCasesKt', name]);
    assert.match(output, new RegExp(`PASS ${name}`));
  });
}
