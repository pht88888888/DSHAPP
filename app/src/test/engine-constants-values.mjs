import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolveKotlinTestRuntime, resolveEngineConstantSources } from '../../../tools/kotlin-test-runtime.mjs';

const root = fileURLToPath(new URL('../../..', import.meta.url));
const goldenFile = fileURLToPath(new URL('./engine-constants-values.json', import.meta.url));
const baselineIndex = process.argv.indexOf('--baseline');
const baseline = baselineIndex >= 0 ? path.resolve(process.argv[baselineIndex + 1]) : null;
const namesIn = source => [...source.matchAll(/^[ \t]*(?:(?:internal|private|public)\s+)?(?:const\s+)?val\s+([A-Z][A-Z_0-9]*)\b/gm)].map(match => match[1]);
const current = resolveEngineConstantSources(root);
const names = current.flatMap(file => namesIn(fs.readFileSync(file, 'utf8'))).sort();
assert.equal(new Set(names).size, names.length, 'Duplicate constant declaration');
const runtime = resolveKotlinTestRuntime();
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'engine-constants-'));
const put = (name, text) => { const file = path.join(tmp, name); fs.writeFileSync(file, text); return file; };
const run = (command, args) => {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 120_000, maxBuffer: 4 * 1024 * 1024 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
};
try {
  const context = put('Context.kt', 'package android.content\nclass Context');
  const spec = put('BuiltinPluginSpec.kt', `package com.dsh.android.engine
internal data class BuiltinPluginSpec(val id:String,val packageName:String,val asset:String,val legacyDir:String?=null,val legacyPatch:String?=null,val legacyMarker:String?=null,val preferenceId:String=id,val entryId:String=packageName)`);
  const runner = put('ConstantValues.kt', `package com.dsh.android.engine.constants
import java.security.MessageDigest
import java.util.Base64
fun encode(value:Any?):String = when(value) {
 null -> "null"
 is String -> "string:"+Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
 is Regex -> "regex:"+encode(value.pattern)+":"+value.options.map{it.name}.sorted().joinToString(",")
 is Enum<*> -> "enum:"+value.javaClass.name+":"+value.name
 is Pair<*,*> -> "pair:"+encode(value.first)+":"+encode(value.second)
 is Iterable<*> -> "list:"+value.map{encode(it)}.joinToString("|")
 else -> value.javaClass.name+":"+value.toString()
}
fun main() {
 val values = listOf<Pair<String,Any?>>(
 ${names.map(name => `"${name}" to ${name}`).join(',\n ')}
 )
 for ((name,value) in values) {
  val bytes=encode(value).toByteArray(Charsets.UTF_8)
  val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
  println(name+"="+hash)
 }
}
`);
  const values = (sources, label) => {
    const output = path.join(tmp, label);
    run(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', runtime.stdlib, '-d', output, context, spec, ...sources, runner]);
    const text = run(runtime.java, ['-cp', [output, runtime.stdlib].join(path.delimiter), 'com.dsh.android.engine.constants.ConstantValuesKt']);
    return Object.fromEntries(text.trim().split('\n').map(line => line.split('=')));
  };
  const after = values(current, 'after');
  let expected;
  if (baseline) {
    assert.deepEqual(namesIn(fs.readFileSync(baseline, 'utf8')).sort(), names, 'Constant names changed during split');
    const before = put('BeforeConstants.kt', fs.readFileSync(baseline, 'utf8'));
    expected = values([before], 'before');
    if (process.argv.includes('--record-baseline')) {
      assert.ok(!fs.existsSync(goldenFile), 'Do not overwrite an existing baseline');
      fs.writeFileSync(goldenFile, `${JSON.stringify({ schemaVersion: 1, values: expected }, null, 2)}\n`);
    }
  } else {
    expected = JSON.parse(fs.readFileSync(goldenFile, 'utf8')).values;
  }
  assert.deepEqual(after, expected, 'Constant runtime byte values changed');
  const digest = createHash('sha256').update(JSON.stringify(after)).digest('hex');
  console.log(`engine constants: ${names.length} runtime byte values identical; 0 skipped; SHA-256 ${digest}`);
} finally {
  assert.ok(tmp.startsWith(path.join(os.tmpdir(), 'engine-constants-')));
  fs.rmSync(tmp, { recursive: true, force: true });
}
