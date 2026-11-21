import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

test('应用级根装配与原生变更说明：真实生产 factory/presentation，Context 窄替身，非 Android 验收', () => {
  const runtime = resolveKotlinTestRuntime(), root = resolveRepositoryRoot(import.meta.url);
  assert.ok(process.env.ENGINE_TEST_JSON_JAR, 'ENGINE_TEST_JSON_JAR required');
  const temp = mkdtempSync(join(tmpdir(), 'dsh-repair-root-'));
  try {
    const fixtures = {
      'Context.kt': `package android.content
import java.io.File
open class Context(val dataDir: File, private val device: File, private val external: Array<File?>, private val cache: Array<File?>, private val media: Array<File?>) {
 val filesDir = File(dataDir, "files")
 fun createDeviceProtectedStorageContext() = Context(device, device, emptyArray(), emptyArray(), emptyArray())
 fun getExternalFilesDirs(type: String?): Array<File?> = external
 val externalCacheDirs get() = cache
 val externalMediaDirs get() = media
}`,
      'Broker.kt': `package com.dsh.android.repair.files
import java.io.File
// Constructor capture only; this test does not execute broker transactions.
class RepairFileBroker(val filesDir: File, val roots: Map<String,File>)`,
      'Checks.kt': `package com.dsh.android.repair
import java.io.File
import org.json.JSONObject
fun main(args: Array<String>) {
 val base = File(args[0]); fun dir(name: String) = File(base,name).apply { mkdirs() }
 val app=dir("app"); val files=File(app,"files").apply {mkdir()};val device=dir("device");val ext=dir("ext");val second=dir("ext2");val cache=dir("cache");val media=dir("media")
 val context=android.content.Context(app,device,arrayOf(ext,null,second),arrayOf(cache,ext),arrayOf(media))
 val roots=RepairFileAccess.roots(context)
 check(roots.keys == setOf("app","device_app","external_files","external_files_3","external_cache","external_media")) { roots.toString() }
 check(roots["app"]==app.canonicalFile && roots["device_app"]==device.canonicalFile)
 check(RepairFileAccess.broker(context).filesDir == files)
 val body=JSONObject().put("operation","delete").put("path","app/files/profile/session.db").put("original_size_bytes",9000000000L).put("candidate_size_bytes",0)
  .put("original_mode",384).put("candidate_mode",384).put("original_content","<script>never execute</script>").put("candidate_content","x".repeat(100000)).put("candidate_truncated",true)
  .put("original_patch_content","ORIGINAL_NEAR_PATCH").put("candidate_patch_content","CANDIDATE_NEAR_PATCH").put("original_patch_encoding","base64").put("candidate_patch_offset",200000)
 val text=RepairChangePresentation.message("com.dsh.android","apply_changes",body)
 check(text.contains("delete") && text.contains("9000000000") && text.contains("app/files/profile/session.db"))
 check(text.contains("不执行递归删除") && text.contains("配置、对话、数据库") && text.contains("base64"))
 check(text.contains("<script>never execute</script>"))
 check(text.contains("ORIGINAL_NEAR_PATCH") && text.contains("CANDIDATE_NEAR_PATCH") && text.contains("200000"))
 check(text.length < 60000 && text.contains("candidate_truncated：true"))
 check(RepairChangePresentation.message("com.dsh.android","rollback",body).startsWith("回退"))
 println("passed production roots and bounded native presentation checks; NOT Android filesystem/consent acceptance")
}`,
    };
    const sources = Object.entries(fixtures).map(([name, contents]) => { const file = join(temp, name); writeFileSync(file, contents); return file; });
    const output = join(temp, 'classes'), classpath = [runtime.stdlib, process.env.ENGINE_TEST_JSON_JAR].join(delimiter);
    const compile = spawnSync(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output,
      join(root, 'app/src/main/java/com/dsh/android/repair/RepairFileAccess.kt'), join(root, 'app/src/main/java/com/dsh/android/repair/RepairChangePresentation.kt'), ...sources], { encoding: 'utf8', timeout: 120000 });
    assert.equal(compile.status, 0, compile.stdout + compile.stderr);
    const run = spawnSync(runtime.java, ['-cp', [output, classpath].join(delimiter), 'com.dsh.android.repair.ChecksKt', temp], { encoding: 'utf8', timeout: 60000 });
    assert.equal(run.status, 0, run.stdout + run.stderr);
    console.log(run.stdout.trim());
    const ops = readFileSync(join(root, 'app/src/main/java/com/dsh/android/repair/RepairAgentOperations.kt'), 'utf8');
    const bridge = readFileSync(join(root, 'repair/assistant/src/main/java/com/dsh/android/repair/assistant/RepairAgentBridge.kt'), 'utf8');
    for (const method of ['list_files', 'stage_patch', 'stage_file']) { assert.ok(ops.includes(`"${method}"`)); assert.ok(bridge.includes(`"${method}"`)); }
    assert.ok(ops.includes('RepairTaskCoordinator.execute(context'));
    const coordinator = readFileSync(join(root, 'app/src/main/java/com/dsh/android/repair/RepairTaskCoordinator.kt'), 'utf8');
    assert.ok(coordinator.includes('RepairFileAccess.broker(app).dispatch(method, args, grant)'));
    assert.ok(coordinator.includes('activeFileOperations++') && coordinator.includes('activeFileOperations--'), '文件通道准入必须与验证/提交互斥且不持有长授权锁');
  } finally {
    assert.equal(dirname(resolve(temp)), resolve(tmpdir())); assert.ok(temp.startsWith(join(tmpdir(), 'dsh-repair-root-')));
    rmSync(temp, { recursive: true, force: true });
  }
});
