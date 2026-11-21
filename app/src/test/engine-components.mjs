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
 fun e(tag:String, message:String, throwable:Throwable? = null) {}
}`),
  ];
  const node = path.join(tmp, 'node');
  fs.symlinkSync(process.execPath, node);
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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

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
 verify(installer.ensureExtracted("v1",progress::add,failures::add),"asset installer did not report success")
 verify(File(engine,"payload.txt").readText()=="new engine","asset payload missing")
 verify(File(engine,".stamp").readText()=="v1","asset stamp missing")
 verify(progress.first()==0 && progress.last()==100,"asset progress incomplete")
 val opens=assets.opens
 verify(installer.ensureExtracted("v1",{error("unchanged stamp extracted")},failures::add),"asset stamp did not report ready")
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
 val legacyPrompt="<!-- dsh-android:rescue BEGIN（本段由客户端生成，勿手改）-->\nOLD CONTENT\n<!-- dsh-android:rescue END -->"
 val instructions=File(home,INSTRUCTIONS_FILE).also{it.writeText("USER BEFORE\n"+legacyPrompt+"\n")}
 val legacyQuarantine=file("dsh/quarantine.patch.yml","old patch\n")
 val legacyState=file("dsh/rescue/state.json","old state\n")
 val legacyProfile=file("dsh/profiles/rescue/package.json","old profile\n")
 overlays.ensurePrivilegedPrompt(true);instructions.appendText("USER AFTER\n");overlays.ensurePrivilegedPrompt(false)
 verify(instructions.readText().contains("USER BEFORE") && instructions.readText().contains("USER AFTER"),"prompt section overwrote user text")
 val prompt=instructions.readText();overlays.ensurePrivilegedPrompt(false)
 verify(instructions.readText()==prompt,"prompt section not idempotent")
 verify(instructions.readText().contains(legacyPrompt),"normal prompt rewrote legacy section")
 verify(legacyQuarantine.readText()=="old patch\n" && legacyState.readText()=="old state\n" && legacyProfile.readText()=="old profile\n","normal overlays touched legacy rescue data")
 verify(user.readText()==custom,"normal overlays touched the user patch")

 val node=File(root,"node");val program=File(root,"bin.js")
 val patches=listOf(file("first.yml","[]"),file("second.yml","[]"))
 val prefix=listOf(node.absolutePath,program.absolutePath,"--profile",PROFILE_NAME,"--patch",patches[0].absolutePath,"--patch",patches[1].absolutePath)
 verify(EngineLaunchSpec.buildEngineArgs(node,program,PROFILE_NAME,patches,0)==prefix+listOf("--port","0","--no-open"),"patch flags must precede inner port flag")
 verify(EngineLaunchSpec.buildEngineArgs(node,program,PROFILE_NAME,patches,null)==prefix+"--no-open","default port must be omitted")
 val url="http://127.0.0.1:43911/?token=fixture"
 verify(URL_PATTERN.find("dsh web: "+url)?.groupValues?.get(1)==url,"valid readiness line not recognized")
 verify(!URL_PATTERN.containsMatchIn("dsh web: http://127.0.0.1:43911/"),"readiness accepted missing token")

 val manifest=file("dsh/profiles/web/package.json", """{"dependencies":{"extra":"^1.0.0"},"custom":"keep me","dsh":{"profile":{"bundles":["@deepseek-ai/core","extra"]}}}""")
 val manifestBefore=manifest.readText()
 verify(!PluginManifest.setBundle(manifest.parentFile,"@deepseek-ai/core",remove=true).first && manifest.readText()==manifestBefore,"official bundle toggle altered user manifest")
 verify(PluginManifest.setBundle(manifest.parentFile,"extra",remove=true).first,"explicit plugin disable failed")
 val (disabled, entries)=requireNotNull(PluginManifest.read(manifest.parentFile))
 verify(entries.map { it.name }==listOf("@deepseek-ai/core") && disabled.getJSONObject("dependencies").getString("extra")=="^1.0.0" && disabled.getString("custom")=="keep me","plugin toggle did not preserve unrelated manifest fields")
 verify(PluginManifest.setBundle(manifest.parentFile,"extra",remove=false).first && PluginManifest.read(manifest.parentFile)?.second?.map { it.name }==listOf("@deepseek-ai/core","extra"),"explicit plugin re-enable failed")

 println("engine components: $count assertions passed; 0 skipped")
}
`));
  const components = ['TarGz', 'EngineAssetInstaller', 'EngineCompatibilityInstaller', 'EngineLaunchSpec', 'ProfileDefaultsInstaller', 'KeyPoolProfileConfig', 'ProfileOverlayWriter', 'PluginManifest'];
  sources.push(...resolveEngineConstantSources(root), ...components.map(name => resolveProjectKotlinSource(root, `engine/${name}.kt`)));
  const classes = path.join(tmp, 'classes');
  const classpath = [...jars, jsonJar].join(path.delimiter);
  run(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', classes, ...sources]);
  process.stdout.write(run(runtime.java, ['-cp', [classes, classpath].join(path.delimiter), 'com.dsh.android.engine.RunnerKt', tmp]));
} finally {
  assert.ok(tmp.startsWith(path.join(os.tmpdir(), 'engine-components-')));
  fs.rmSync(tmp, { recursive: true, force: true });
}
