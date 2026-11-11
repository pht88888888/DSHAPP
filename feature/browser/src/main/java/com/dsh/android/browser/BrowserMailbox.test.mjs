import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { after, before, test } from 'node:test';

const source = dirname(fileURLToPath(import.meta.url));
let work;
let runtime;
let repositoryRoot;
async function loadRuntime() {
  let root = source;
  while (!existsSync(join(root, 'settings.gradle.kts')) || !existsSync(join(root, 'tools/kotlin-test-runtime.mjs'))) {
    const parent = dirname(root);
    assert.notEqual(parent, root, '找不到仓库测试工具');
    root = parent;
  }
  const helper = await import(pathToFileURL(join(root, 'tools/kotlin-test-runtime.mjs')).href);
  repositoryRoot = helper.resolveRepositoryRoot(import.meta.url);
  assert.equal(repositoryRoot, root);
  return helper.resolveKotlinTestRuntime();
}
const cases = `package com.dsh.android.browser
import java.io.File

fun main(args: Array<String>) {
 val root = File(System.getProperty("java.io.tmpdir"), args.single()).apply { mkdirs() }
 val failures = ArrayList<String>()
 fun mailbox() = BrowserCommandMailbox(root) { message, _ -> failures.add(message) }
 fun command() = File(root, "browser-cmd.json")
 fun processing() = File(root, "browser-cmd.processing.json")
 fun result() = File(root, "browser-result.json")
 fun claim(box: BrowserCommandMailbox) = box.claim { it.startsWith("{") }!!
 when (args.single()) {
  "mailbox-next-request" -> {
   command().writeText("{old}")
   val box = mailbox(); val request = claim(box)
   check(!request.recovered && processing().readText() == "{old}" && !command().exists())
   command().writeText("{new}")
   check(box.complete(request, 1, "old-result"))
   check(result().readText() == "old-result" && command().readText() == "{new}" && !processing().exists())
   check(claim(box).raw == "{new}")
  }
  "mailbox-duplicate-id" -> {
   val box = mailbox(); var executions = 0
   fun poll() {
    val request = claim(box)
    val response = box.completed(7)?.response ?: "result".also { executions++ }
    check(box.complete(request, 7, response))
   }
   command().writeText("{request}"); poll()
   command().writeText("{request}"); poll()
   check(executions == 1 && result().readText() == "result")
   val restarted = mailbox(); check(restarted.completed(7)?.response == "result")
  }
  "mailbox-result-failure" -> {
   val box = mailbox(); command().writeText("{request}"); val request = claim(box)
   check(result().mkdir()) // 用目录阻止结果文件原子替换，真实触发 I/O 失败。
   check(!box.complete(request, 9, "reply"))
   check(processing().exists() && box.completed(9)?.response == "reply")
   command().writeText("{next}")
   check(result().delete())
   val restarted = mailbox(); val recovered = claim(restarted)
   check(recovered.recovered && restarted.completed(9)?.response == "reply")
   check(restarted.complete(recovered, 9, restarted.completed(9)!!.response))
   check(command().readText() == "{next}" && result().readText() == "reply")
   check(failures.size == 1)
  }
  "mailbox-journal-failure" -> {
   val box = mailbox(); command().writeText("{request}"); val request = claim(box)
   val journal = File(root, "browser-cmd.receipt"); check(journal.mkdir())
   check(!box.complete(request, 10, "memory-result"))
   check(box.completed(10)?.response == "memory-result" && processing().exists() && !result().exists())
   check(journal.delete())
   check(box.complete(request, 10, box.completed(10)!!.response))
   check(!processing().exists() && result().readText() == "memory-result")
  }
  "mailbox-restart-interrupted" -> {
   command().writeText("{uncertain-action}")
   claim(mailbox())
   val restarted = mailbox(); val request = claim(restarted)
   check(request.recovered && restarted.completed(11) == null)
   // 上层收到 recovered 且无回执必须报中断，不自动重放可能已经发生的网页动作。
   check(restarted.complete(request, 11, "interrupted"))
   check(result().readText() == "interrupted")
  }
  "mailbox-malformed" -> {
   val box = mailbox(); command().writeText("broken")
   check(box.claim { it.startsWith("{") } == null && command().readText() == "broken")
   command().writeText("{fixed}"); check(claim(box).raw == "{fixed}")
  }
  "owner-generation" -> {
   val registry = BrowserOwnerRegistry<String>(); val oldOwner = Any(); val newOwner = Any()
   val old = registry.attach(oldOwner, "old"); var cancellations = 0
   registry.onInvalidated(old) { cancellations++ }
   val current = registry.attach(newOwner, "current")
   check(cancellations == 1 && !registry.isCurrent(old) && registry.isCurrent(current))
   registry.detach(oldOwner); check(registry.isCurrent(current))
   registry.detach(newOwner); check(registry.current == null && cancellations == 1)
  }
  "owner-late-callback" -> {
   val registry = BrowserOwnerRegistry<String>(); val owner = Any()
   val old = registry.attach(owner, "old"); var effects = 0
   val deliver = { if (registry.isCurrent(old)) effects++ }
   val replacement = registry.attach(Any(), "new")
   deliver(); check(effects == 0 && registry.isCurrent(replacement))
   var cancelled = 0
   registry.onInvalidated(old) { cancelled++ }; check(cancelled == 1)
   val detach = registry.onInvalidated(replacement) { cancelled++ }
   detach(); registry.clear(); check(cancelled == 1)
  }
  else -> error("未知测试")
 }
 println("PASS " + args.single())
}
`;

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024 });
  assert.equal(result.status, 0, `${command} 失败\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}
before(async () => {
  runtime = await loadRuntime();
  work = mkdtempSync(join(tmpdir(), 'dsh-browser-mailbox-'));
  const fixture = join(work, 'BrowserMailboxCases.kt');
  writeFileSync(fixture, cases);
  run(runtime.java, ['-cp', runtime.classpath,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', runtime.stdlib, '-d', join(work, 'classes'), fixture,
    join(source, 'BrowserOwnerRegistry.kt'), join(source, 'BrowserCommandMailbox.kt'),
    join(repositoryRoot, 'bridge/transport/src/main/java/com/dsh/android/bridge/transport/AtomicMailboxFiles.kt')]);
});
after(() => {
  if (!work) return;
  assert.equal(dirname(work), tmpdir());
  assert.ok(work.startsWith(join(tmpdir(), 'dsh-browser-mailbox-')));
  rmSync(work, { recursive: true });
});
for (const name of ['mailbox-next-request', 'mailbox-duplicate-id', 'mailbox-result-failure',
  'mailbox-journal-failure', 'mailbox-restart-interrupted', 'mailbox-malformed', 'owner-generation', 'owner-late-callback']) {
  test(name, () => {
    const output = run(runtime.java, ['-cp', `${join(work, 'classes')}${delimiter}${runtime.stdlib}`,
      `-Djava.io.tmpdir=${work}`, 'com.dsh.android.browser.BrowserMailboxCasesKt', name]);
    assert.match(output, new RegExp(`PASS ${name}`));
  });
}
