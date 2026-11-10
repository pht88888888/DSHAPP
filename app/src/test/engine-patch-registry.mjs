import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../..', import.meta.url));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'engine-registry-'));
const run = (cmd, args) => {
  const result = spawnSync(cmd, args, { encoding: 'utf8' });
  assert.equal(result.status, 0, `${cmd}: ${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
};
try {
  const candidates = [process.env.KOTLIN_COMPILER_LIB, '/root/gradle-home/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/lib', '/root/.sdkman/candidates/gradle/9.3.0/lib'].filter(Boolean);
  const lib = candidates.find(dir => fs.existsSync(dir) && fs.readdirSync(dir).some(file => /^kotlin-compiler-embeddable-.*\.jar$/.test(file)));
  assert.ok(lib, 'Set KOTLIN_COMPILER_LIB to the existing Gradle lib directory');
  const jars = fs.readdirSync(lib).filter(file => file.endsWith('.jar')).map(file => path.join(lib, file));
  const stdlib = jars.find(file => /\/kotlin-stdlib-[\d.]+\.jar$/.test(file));
  const context = path.join(tmp, 'Context.kt');
  fs.writeFileSync(context, 'package android.content\nclass Context');
  const spec = path.join(tmp, 'BuiltinPluginSpec.kt');
  fs.writeFileSync(spec, `package com.dsh.android.engine
internal data class BuiltinPluginSpec(val id:String,val packageName:String,val asset:String,val legacyDir:String?=null,val legacyPatch:String?=null,val legacyMarker:String?=null,val preferenceId:String=id,val entryId:String=packageName)`);
  const runner = path.join(tmp, 'Runner.kt');
  fs.writeFileSync(runner, `package com.dsh.android.engine
import java.io.File
import com.dsh.android.engine.constants.*
fun main(args:Array<String>) {
 val root=File(args[0])
 if(args[1]=="list") {
  for(s in EngineFilePatches.registry) for(t in s.targets) println(t.path + if(t.prefix==null) "" else "/"+t.prefix+"*.js")
  return
 }
 var count=0
 fun checkThat(value:Boolean,message:String) { check(value) { message }; count++ }
 fun apply(id:String)=EngineFilePatches.apply(root,id)
 if(args[1]=="compat") {
  val image=EngineFilePatches.registry.single{it.id=="image-mime"}.targets.single().path
  val file=File(root,image)
  checkThat(!file.readText().contains("return value.startsWith(\\\"image/\\\")"),"compat unexpectedly retained earlier image patch")
  val result=apply("image-mime")
  println("compat image-mime "+result.status+" "+result.targets+" "+result.detail)
  checkThat(result.status==EngineFilePatches.Status.APPLIED,"image patch cannot match transformed compat client: "+result)
  val after=file.readText()
  checkThat(after.contains("return value.startsWith(\\\"image/\\\")"),"image patch body missing after compat")
  checkThat(apply("image-mime").status==EngineFilePatches.Status.ALREADY,"compat image patch not idempotent")
  checkThat(file.readText()==after,"compat second apply changed bytes")
 } else if(args[1]=="official") {
  checkThat(EngineFilePatches.registry.size==11,"expected 11 registered patches")
  for(s in EngineFilePatches.registry) {
   val result=apply(s.id)
   println(s.id+" "+result.status+" "+result.targets+" "+result.detail)
   checkThat(result.status==EngineFilePatches.Status.APPLIED || result.status==EngineFilePatches.Status.ALREADY,result.toString())
  }
  val before=root.walkTopDown().filter{it.isFile}.associate{it.relativeTo(root).path to it.readBytes().toList()}
  for(s in EngineFilePatches.registry) checkThat(apply(s.id).status==EngineFilePatches.Status.ALREADY,"not idempotent: "+s.id)
  val after=root.walkTopDown().filter{it.isFile}.associate{it.relativeTo(root).path to it.readBytes().toList()}
  checkThat(before==after,"second apply changed bytes")
 } else {
  fun put(relative:String,text:String):File=File(root,relative).also{it.parentFile.mkdirs();it.writeText(text)}
  val attachment=EngineFilePatches.registry.single{it.id=="attachment-durability"}
  val at=attachment.targets.single().path
  val original=ATTACH_SYNC_GUARD+"\\n"+ATTACH_FSYNC_GUARD
  val file=put(at,ATTACH_SYNC_GUARD+"\\nBROKEN")
  checkThat(apply(attachment.id).status==EngineFilePatches.Status.MISMATCH,"partial missing accepted")
  checkThat(file.readText()==ATTACH_SYNC_GUARD+"\\nBROKEN","partial mismatch wrote file")
  file.writeText(ATTACH_SYNC_ANCHOR+"\\n"+ATTACH_FSYNC_GUARD)
  checkThat(apply(attachment.id).status==EngineFilePatches.Status.APPLIED,"legacy partial patch not completed")
  checkThat(file.readText()==ATTACH_SYNC_ANCHOR+"\\n"+ATTACH_FSYNC_ANCHOR,"incorrect repaired body")
  file.writeText(original+"\\n"+ATTACH_SYNC_GUARD)
  checkThat(apply(attachment.id).status==EngineFilePatches.Status.MISMATCH,"duplicate literal accepted")
  checkThat(file.readText()==original+"\\n"+ATTACH_SYNC_GUARD,"duplicate wrote file")
  val process=EngineFilePatches.registry.single{it.id=="process-inspector"}.targets.single()
  put(process.path+"/runner-launch-a.js",INSPECTOR_GUARD)
  val duplicate=put(process.path+"/runner-launch-b.js",INSPECTOR_GUARD)
  checkThat(apply("process-inspector").status==EngineFilePatches.Status.MISMATCH,"ambiguous chunk accepted")
  duplicate.delete()
  checkThat(apply("process-inspector").status==EngineFilePatches.Status.APPLIED,"chunk not patched")
  val fail=EngineFilePatches.registry.single{it.id=="fail-loud"}
  val exit=put(fail.targets[0].path,FAIL_LOUD_EXIT_GUARD+"\\n"+FAIL_LOUD_EXIT_GUARD)
  put(fail.targets[1].path+"/profile-boot-test.js","BROKEN")
  checkThat(apply("fail-loud").status==EngineFilePatches.Status.MISMATCH,"release mismatch accepted")
  checkThat(exit.readText()==FAIL_LOUD_EXIT_GUARD+"\\n"+FAIL_LOUD_EXIT_GUARD,"cross-file preflight failed")
  put(fail.targets[1].path+"/profile-boot-test.js",FAIL_LOUD_RELEASE_GUARD)
  exit.writeText(FAIL_LOUD_EXIT_GUARD)
  checkThat(apply("fail-loud").status==EngineFilePatches.Status.MISMATCH,"expectedCount2 ignored")
  exit.writeText(FAIL_LOUD_EXIT_GUARD+"\\n"+FAIL_LOUD_EXIT_GUARD)
  checkThat(apply("fail-loud").status==EngineFilePatches.Status.APPLIED,"fail-loud group failed")
  val image=EngineFilePatches.registry.single{it.id=="image-mime"}.targets.single().path
  val img=put(image,"      "+IMG_TYPE_GUARD)
  checkThat(apply("image-mime").status==EngineFilePatches.Status.APPLIED,"space-indented image failed")
  checkThat(img.readText()==IMG_TYPE_ANCHOR.lines().joinToString("\\n"){"      "+it},"indent not preserved")
  checkThat(apply("image-mime").status==EngineFilePatches.Status.ALREADY,"image not idempotent")
  img.writeText("\\treturn value === \\\"image/gif\\\" || value === \\\"image/png\\\";")
  checkThat(apply("image-mime").status==EngineFilePatches.Status.APPLIED,"image fallback not preserved")
  img.writeText(IMG_TYPE_GUARD+"\\n"+IMG_TYPE_GUARD)
  checkThat(apply("image-mime").status==EngineFilePatches.Status.MISMATCH,"ambiguous image accepted")
  img.writeText("return value.startsWith(\\\"image/\\\")")
  checkThat(apply("image-mime").status==EngineFilePatches.Status.MISMATCH,"truncated applied marker accepted")
  val terminal=EngineFilePatches.registry.single{it.id=="terminal-shell"}.targets.single().path
  checkThat(apply("terminal-shell").status==EngineFilePatches.Status.MISSING,"missing not reported")
  File(root,terminal).parentFile.mkdirs()
  java.nio.file.Files.createSymbolicLink(File(root,terminal).toPath(),File("/proc/self/mem").toPath())
  checkThat(apply("terminal-shell").status==EngineFilePatches.Status.FAILURE,"IO failure not reported")
 }
 println("Kotlin assertions="+count+", skipped=0")
}`);
  const classes = path.join(tmp, 'classes');
  run('java', ['-cp', jars.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib, '-d', classes, context, spec, path.join(root, 'app/src/main/java/com/dsh/android/engine/EngineConstants.kt'), path.join(root, 'app/src/main/java/com/dsh/android/engine/EngineFilePatches.kt'), runner]);
  const java = (...args) => run('java', ['-cp', [classes, stdlib].join(path.delimiter), 'com.dsh.android.engine.RunnerKt', ...args]);
  const targets = java(tmp, 'list').trim().split('\n');
  const engine = path.join(tmp, 'engine');
  fs.mkdirSync(engine);
  run('python3', ['-c', `import tarfile,sys,pathlib,fnmatch,json
out=pathlib.Path(sys.argv[2]); patterns=json.loads(sys.argv[3])
with tarfile.open(sys.argv[1]) as t:
 for m in t:
  if m.isfile() and any(fnmatch.fnmatchcase(m.name,p) for p in patterns):
   p=out/m.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(t.extractfile(m).read())`, path.join(root, 'app/src/main/assets/engine.bin'), engine, JSON.stringify(targets)]);
  process.stdout.write(java(engine, 'official'));
  run('python3', ['-c', `import zipfile,sys,pathlib,hashlib,json
out=pathlib.Path(sys.argv[2])
with zipfile.ZipFile(sys.argv[1]) as z:
 manifest=json.loads(z.read('.compat-manifest.json'))
 name='engine/dsh-client-ui-conversation/lib/client.js'
 data=z.read(name)
 assert hashlib.sha256(data).hexdigest()==manifest['files'][name]
 for entry in z.infolist():
  if not entry.is_dir() and entry.filename.startswith('engine/'):
   p=out/'node_modules/@deepseek-ai'/entry.filename.removeprefix('engine/');p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(entry))
 print('Applied real compat engine overlay; conversation bytes='+str(len(data)))`, path.join(root, 'app/src/main/assets/webview-compat.zip'), engine]);
  process.stdout.write(java(engine, 'compat'));
  const fixtures = path.join(tmp, 'fixtures');
  fs.mkdirSync(fixtures);
  process.stdout.write(java(fixtures, 'fixtures'));
  console.log('真实注册表11项与失败场景全部通过，跳过0。');
} finally {
  assert.ok(tmp.startsWith(path.join(os.tmpdir(), 'engine-registry-')));
  fs.rmSync(tmp, { recursive: true, force: true });
}
