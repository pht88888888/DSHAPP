import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { resolveKotlinTestRuntime, resolveRepositoryRoot, resolveEngineConstantSources, resolveProjectKotlinSource } from '../../../tools/kotlin-test-runtime.mjs';

const root = resolveRepositoryRoot(import.meta.url);
const runtime = resolveKotlinTestRuntime();
assert.ok(process.env.ENGINE_TEST_JSON_JAR, 'Set ENGINE_TEST_JSON_JAR to the existing pinned org.json 20240303 jar; no download is performed');
const jsonJar = path.resolve(process.env.ENGINE_TEST_JSON_JAR);
assert.ok(fs.statSync(jsonJar).isFile(), `ENGINE_TEST_JSON_JAR is not a file: ${jsonJar}`);
assert.equal(createHash('sha256').update(fs.readFileSync(jsonJar)).digest('hex'), '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed', 'ENGINE_TEST_JSON_JAR must match the pinned org.json 20240303 SHA-256');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'engine-components-'));
const run = (command, args) => {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 120_000 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
};
try {
  const { jars } = runtime;
  const fixture = (name, text) => { const file = path.join(tmp, name); fs.writeFileSync(file, text); return file; };
  const sources = [
    fixture('Context.kt', 'package android.content\nclass Context'),
    fixture('AssetManager.kt', `package android.content.res
import java.io.File
class AssetManager(val root: File) {
 var opens = 0
 fun open(name: String): java.io.InputStream { opens++; return File(root, name).inputStream() }
}`),
    fixture('BuiltinPluginSpec.kt', `package com.dsh.android.engine
internal data class BuiltinPluginSpec(val id:String,val packageName:String,val asset:String,val legacyDir:String?=null,val legacyPatch:String?=null,val legacyMarker:String?=null,val preferenceId:String=id,val entryId:String=packageName)`),
    fixture('Log.kt', `package com.dsh.android.log
object DshLog {
 fun i(tag:String, message:String) {}
 fun w(tag:String, message:String) {}
 fun e(tag:String, message:String) {}
}
object BootState { val bootId = "fixture" }
object EventLog { fun record(name:String, details:Map<String,String>) {} }
object RescueBoot {
 const val DIR_NAME = "rescue"
 enum class Tier(val id:String) { NORMAL("normal"), ISOLATED("isolated"), RESCUE("rescue") }
 data class Failure(val requiredDidNotActivate:Int?, val tail:List<String>)
 fun parseFailure(bootId:String, exitCode:Int?, tailLines:List<String>, at:Long) = Failure(null, tailLines.takeLast(100))
}`),
    fixture('EngineManager.kt', `package com.dsh.android.engine
class EngineManager {
 internal class ProbeResult(val ready:Boolean,val exitCode:Int?,val requiredDidNotActivate:Int?,val tail:List<String>)
 internal class IsolationResult(val removed:List<String>,val reason:String,val attempts:Int,val verified:Boolean)
}`),
  ];
  const node = path.join(tmp, 'node');
  fs.symlinkSync(process.execPath, node);
  const program = path.join(tmp, 'fixture.cjs');
  fs.writeFileSync(program, `const fs = require('node:fs');
fs.writeFileSync(process.env.PID_FILE, String(process.pid));
if (process.env.MODE === 'ready') {
 console.log(JSON.stringify({ args: process.argv.slice(2), env: process.env.CUSTOM, cwd: process.cwd() }));
 console.log('dsh web: http://127.0.0.1:43911/?token=fixture');
 setInterval(() => {}, 1000);
} else if (process.env.MODE === 'fail') {
 console.log('fixture failure'); process.exit(7);
} else { setInterval(() => {}, 1000); }
`);
  sources.push(fixture('Runner.kt', String.raw`package com.dsh.android.engine
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import android.content.res.AssetManager
import com.dsh.android.engine.constants.*
import com.dsh.android.log.RescueBoot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray

fun main(args:Array<String>) = runBlocking {
 val root=File(args[0]); var count=0
 fun verify(ok:Boolean, message:String) { check(ok) { message }; count++ }
 fun file(name:String, text:String):File = File(root,name).also { it.parentFile.mkdirs(); it.writeText(text) }
 fun zip(target:File, entries:Map<String,ByteArray>) { target.parentFile.mkdirs(); ZipOutputStream(target.outputStream()).use { out -> entries.forEach { (name,data) -> out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() } } }
 fun digest(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
 fun archive(name:String, content:String):ByteArray {
  val header=ByteArray(512); name.toByteArray().copyInto(header)
  (content.toByteArray().size.toString(8).padStart(11,'0')+"\u0000").toByteArray().copyInto(header,124)
  header[156]='0'.code.toByte()
  return ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { out -> out.write(header); out.write(content.toByteArray()); out.write(ByteArray((512-content.toByteArray().size%512)%512)); out.write(ByteArray(1024)) } }.toByteArray()
 }
 val assetsRoot=File(root,"assets").also{it.mkdirs()};val assets=AssetManager(assetsRoot)
 val engine=File(root,"engine")
 val archiveFile=File(assetsRoot,ASSET_NAME).also{it.writeBytes(archive("payload.txt","new engine"))}
 val apk=File(root,"test.apk");zip(apk,mapOf("assets/$ASSET_NAME" to archiveFile.readBytes()))
 val installer=EngineAssetInstaller(assets,root,apk,engine)
 val progress=ArrayList<Int>(); val failures=ArrayList<String>()
 installer.ensureExtracted("v1",progress::add,failures::add)
 verify(File(engine,"payload.txt").readText()=="new engine","asset payload missing")
 verify(File(engine,".stamp").readText()=="v1","asset stamp missing")
 verify(progress.first()==0 && progress.last()==100,"asset progress incomplete")
 val opens=assets.opens;installer.ensureExtracted("v1",{error("unchanged stamp extracted")},failures::add)
 verify(assets.opens==opens,"unchanged asset reopened")
 archiveFile.writeText("corrupt")
 verify(runCatching { installer.ensureExtracted("v2",{},failures::add) }.isFailure,"corrupt archive accepted")
 verify(File(engine,".stamp").readText()=="v1" && File(engine,"payload.txt").readText()=="new engine","failed install replaced current engine")
 verify(!File(root,"engine.staging").exists() && failures.size==1,"failed staging not cleaned")
 archiveFile.writeBytes(archive("payload.txt","new engine"))

 val dist=File(engine,"node_modules/@deepseek-ai/dsh-web-frontend/dist").also{it.mkdirs()}
 File(dist,"index.html").writeText("<html><script type=\"module\" src=\"app.js\"></script></html>")
 File(assetsRoot,POLYFILL_FILE).writeText("globalThis.polyfilled=true;")
 val compatibility=EngineCompatibilityInstaller(assets,root,engine,false)
 compatibility.ensureWebCompat();val html=File(dist,"index.html").readText();compatibility.ensureWebCompat()
 verify(html.contains(POLYFILL_TAG) && File(dist,"index.html").readText()==html,"polyfill injection not idempotent")
 File(assetsRoot,FETCH_GUARD_ASSET).writeText("export function install() {}")
 val guard=compatibility.ensureFetchGuard()!!
 File(assetsRoot,FETCH_GUARD_ASSET).writeText("invalid asset")
 verify(compatibility.ensureFetchGuard()==guard && guard.readText()=="export function install() {}","invalid guard replaced working import")
 file(KEYINJECT_FILE,"legacy");verify(compatibility.ensureKeyInject()==null && !File(root,KEYINJECT_FILE).exists(),"retired keyinject not cleaned")
 val compatName="$COMPAT_PREFIX_DIST/app.js";val transformed="legacy compatible".toByteArray()
 val metadata=JSONObject().put("schemaVersion",1).put("target","chrome73").put("engineDigest",digest(archiveFile.readBytes())).put("polyfillDigest",digest(File(assetsRoot,POLYFILL_FILE).readBytes())).put("files",JSONObject().put(compatName,digest(transformed)))
 zip(File(assetsRoot,WEBVIEW_COMPAT_ASSET),mapOf(".compat-manifest.json" to metadata.toString().toByteArray(), WEBVIEW_COMPAT_STAMP_ENTRY to "compat-v1".toByteArray(), compatName to transformed))
 compatibility.ensureWebViewCompat();verify(compatibility.webViewCompatApplied && File(dist,"app.js").readText()=="legacy compatible","compat outputs missing")
 File(dist,"app.js").writeText("tampered");compatibility.ensureWebViewCompat()
 verify(File(dist,"app.js").readText()=="legacy compatible","compat stamp hid corrupted output")

 val home=File(root,"dsh").also{it.mkdirs()};val defaults=ProfileDefaultsInstaller(home)
 defaults.seedDefaultSettings();val user=File(home,"profiles/$PROFILE_NAME/$PROFILE_PATCH_FILENAME")
 verify(user.readText()==DEFAULT_SETTINGS_PATCH,"default settings missing")
 user.writeText("# custom\n- id: custom\n  config: {value: user}\n");val custom=user.readText();defaults.seedDefaultSettings()
 verify(user.readText()==custom,"defaults overwrote user settings")
 val seeded=defaults.mergeDefaultPreset("# keep\n[]\n")
 verify(seeded.startsWith("# keep") && !seeded.lines().any{it=="[]"} && seeded.contains("default: razor"),"default preset seed malformed")
 verify(defaults.mergeDefaultPreset(seeded)==seeded,"preset seed not idempotent")
 val ownProvider="- id: llm-pi-ai\n  name: '@deepseek-ai/dsh-llm-pi-ai'\n  config:\n    providers:\n      custom:\n        baseURL: 'https://user.example'\n"
 val migrated=KeyPoolProfileConfig.mergeKeyPoolProvider(ownProvider)
 verify(migrated.contains("https://user.example") && migrated.contains("agnes:"),"provider migration lost user provider")
 verify(KeyPoolProfileConfig.mergeKeyPoolProvider(migrated)==migrated,"provider seed not idempotent")
 val stripped=defaults.stripBuiltinAgnesText(migrated)
 verify(stripped.contains("https://user.example") && !stripped.contains("      agnes:"),"legacy provider cleanup removed user provider")
 verify(defaults.stripBuiltinAgnesText(stripped)==stripped,"legacy cleanup not idempotent")
 val overlays=ProfileOverlayWriter(home,File(root,"workspace")){true}
 val instructions=File(home,INSTRUCTIONS_FILE).also{it.writeText("USER BEFORE\n")}
 overlays.ensurePrivilegedPrompt(true);instructions.appendText("USER AFTER\n");overlays.ensurePrivilegedPrompt(false)
 verify(instructions.readText().contains("USER BEFORE") && instructions.readText().contains("USER AFTER"),"prompt section overwrote user text")
 val prompt=instructions.readText();overlays.ensurePrivilegedPrompt(false)
 verify(instructions.readText()==prompt,"prompt section not idempotent")
 overlays.ensureRescuePrompt(RescueBoot.Tier.RESCUE);overlays.ensureRescuePrompt(RescueBoot.Tier.NORMAL)
 verify(instructions.readText().contains("本机**没有**处于降级启动模式"),"rescue prompt remained stale")
 val quarantinePatch=overlays.writeQuarantinePatch(listOf("bad-plugin"))!!
 verify(quarantinePatch.readText().contains("disabled: true"),"quarantine overlay missing")
 verify(overlays.writeQuarantinePatch(emptyList())==null && !quarantinePatch.exists(),"quarantine overlay not cleared")

 val manifest=file("dsh/profiles/web/package.json","{\n \"custom\": \"keep exact whitespace\", \"dsh\": {\"profile\": {\"bundles\": [\"@deepseek-ai/core\",\"bad\",\"good\"]}}}\n")
 val original=manifest.readText(); var temporarySeen=false
 val tx=PluginIsolationTransaction(home,engine){_,_ ->
  val rows=JSONObject(manifest.readText()).getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
  temporarySeen=(0 until rows.length()).map{rows.getString(it)}==listOf("@deepseek-ai/core","good")
  throw IllegalStateException("probe interrupted")
 }
 verify(runCatching{tx.probeWithBundleFilter("web",listOf("good"),emptyList())}.isFailure,"probe fixture did not throw")
 verify(temporarySeen && manifest.readText()==original && !File(manifest.parentFile,"package.json.bak-probe").exists(),"filter failed to restore exact original after exception")
 val bad=file("broken-dir/payload","retain me").parentFile
 verify(tx.quarantine(bad,"fixture") && !bad.exists(),"directory quarantine failed")
 verify(root.listFiles()!!.any{it.name.startsWith("broken-dir.bak-") && File(it,"payload").readText()=="retain me"},"quarantine deleted original data")
 val isolation=PluginIsolationTransaction(home,engine){_,_ ->
  val rows=JSONObject(manifest.readText()).getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
  val keep=(0 until rows.length()).map{rows.getString(it)}
  EngineManager.ProbeResult("bad" !in keep,if("bad" in keep) 1 else null,if("bad" in keep) 1 else null,emptyList())
 }
 val isolated=isolation.isolateBadBundles(emptyList())!!
 verify(isolated.verified && isolated.removed==listOf("bad"),"isolation failed to find one fatal bundle")
 verify(File(manifest.parentFile,"package.json.bak-l1").readText()==original,"L1 backup changed original bytes")
 val finalRows=JSONObject(manifest.readText()).getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
 verify((0 until finalRows.length()).map{finalRows.getString(it)}==listOf("@deepseek-ai/core","good"),"L1 duplicated core or removed healthy bundle")

 val workspace=File(root,"workspace").also{it.mkdirs()};val node=File(root,"node");val program=File(root,"fixture.cjs")
 val env=linkedMapOf("MODE" to "ready","CUSTOM" to "snapshot", "PID_FILE" to File(root,"pid").absolutePath)
 val patchFiles=mutableListOf(file("first.yml","[]"),file("second.yml","[]"))
 val spec=EngineLaunchSpec(node,program,"web",patchFiles,workspace,env,3080)
 env["CUSTOM"]="mutated";patchFiles.clear()
 verify(spec.environment["CUSTOM"]=="snapshot" && spec.overlays.size==2,"launch snapshot mutated through inputs")
 verify(runCatching{(spec.environment as MutableMap)["CUSTOM"]="mutated"}.isFailure,"launch environment exposed mutation")
 val probe=spec.forProbe("rescue",spec.overlays)
 verify(probe.environment==spec.environment && probe.profile=="rescue" && probe.port==0,"probe diverged from real environment")
 val expected=listOf(node.absolutePath,program.absolutePath,"--profile","rescue","--patch",spec.overlays[0].absolutePath,"--patch",spec.overlays[1].absolutePath,"--port","0","--no-open")
 verify(probe.arguments==expected,"launcher flags after inner port")
 val runner=EngineProbeRunner();val ready=runner.probeBoot(probe,3000)!!
 verify(ready.ready && ready.tail.any{it.contains("snapshot") && it.contains(workspace.absolutePath)},"probe did not use exact cwd/env")
 fun dead() = !ProcessHandle.of(File(root,"pid").readText().toLong()).map{it.isAlive}.orElse(false)
 verify(dead(),"ready probe child survived return")
 val failedSpec=EngineLaunchSpec(node,program,"web",emptyList(),workspace,spec.environment+mapOf("MODE" to "fail"),0)
 val failed=runner.probeBoot(failedSpec,3000)!!
 verify(!failed.ready && failed.exitCode==7 && failed.tail.contains("fixture failure"),"exit failure lost code or tail")
 val hungSpec=EngineLaunchSpec(node,program,"web",emptyList(),workspace,spec.environment+mapOf("MODE" to "hang"),0)
 verify(runner.probeBoot(hungSpec,300)?.ready==false && dead(),"timeout probe left child alive")
 val concurrent=runner.runDumpConfig(ProcessBuilder(node.absolutePath,"-e","process.stdout.write('a'.repeat(150000)); process.stderr.write('b'.repeat(150000))"),3000)!!
 verify(concurrent.exitCode==0 && concurrent.stdout.trim().length==150000 && concurrent.stderr.trim().length==150000,"dump pipes blocked or truncated")
 val hung=ProcessBuilder(node.absolutePath,program.absolutePath).apply{environment().putAll(hungSpec.environment)}
 verify(runner.runDumpConfig(hung,300)==null && dead(),"dump timeout left child alive")
 val interruptThread=Thread{runner.probeBoot(hungSpec,10000)};interruptThread.start()
 val until=System.currentTimeMillis()+2000
 while(File(root,"pid").readText().toLong().let{!ProcessHandle.of(it).map{p->p.isAlive}.orElse(false)} && System.currentTimeMillis()<until) Thread.sleep(10)
 interruptThread.interrupt();interruptThread.join(4000)
 verify(!interruptThread.isAlive && dead(),"interrupted probe left child alive")

 val monitor=EngineMemoryMonitor({true},{false},{error("unexpected restart")})
 val child=ProcessBuilder(node.absolutePath,"-e","setInterval(()=>{},1000)").start()
 fun watchers()=Thread.getAllStackTraces().keys.filter{it.isAlive && it.name=="dsh-heap-watch"}
 try {
  monitor.start(child,3072,0.85);val first=watchers().single();monitor.start(child,3072,0.85);first.join(1000)
  verify(!first.isAlive && watchers().size==1,"monitor replacement leaked old worker")
  val last=watchers().single();monitor.stop();last.join(1000)
  verify(!last.isAlive && watchers().isEmpty(),"monitor stop left worker alive")
 } finally {monitor.stop();child.destroyForcibly();child.waitFor()}
 println("engine components: $count assertions passed; 0 skipped")
}
`));
  const components = ['TarGz', 'EngineAssetInstaller', 'EngineCompatibilityInstaller', 'EngineLaunchSpec', 'EngineProbeRunner', 'ProfileDefaultsInstaller', 'KeyPoolProfileConfig', 'ProfileOverlayWriter', 'PluginIsolationTransaction', 'PluginSyntaxPreflight', 'EngineMemoryMonitor'];
  sources.push(...resolveEngineConstantSources(root), ...components.map(name => resolveProjectKotlinSource(root, `engine/${name}.kt`)));
  const classes = path.join(tmp, 'classes');
  const classpath = [...jars, jsonJar].join(path.delimiter);
  run(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', classes, ...sources]);
  process.stdout.write(run(runtime.java, ['-cp', [classes, classpath].join(path.delimiter), 'com.dsh.android.engine.RunnerKt', tmp]));
} finally {
  assert.ok(tmp.startsWith(path.join(os.tmpdir(), 'engine-components-')));
  fs.rmSync(tmp, { recursive: true, force: true });
}
