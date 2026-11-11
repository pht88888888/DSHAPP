import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { resolveKotlinTestRuntime, requireKotlinTestInput, resolveProjectKotlinSource, resolveEngineConstantSources } from './kotlin-test-runtime.mjs';

function fixture(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'kotlin-runtime-inputs-'));
  const put = (relative, text = '') => {
    const filename = path.join(root, relative);
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    fs.writeFileSync(filename, text);
    return filename;
  };
  const library = (relative = 'compiler lib') => {
    put(`${relative}/kotlin-compiler-embeddable-2.0.21.jar`);
    put(`${relative}/kotlin-stdlib-2.0.21.jar`);
    return path.join(root, relative);
  };
  try { fn({ root, put, library }); } finally {
    const resolved = fs.realpathSync(root);
    assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('kotlin-runtime-inputs-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  }
}
const fakeRun = (_java, args) => ({ status: 0, stdout: '', stderr:
  args[0] === '-version' ? 'openjdk version "17.0.19"' : 'info: kotlinc-jvm 2.0.21 (JRE 17.0.19)' });
const resolve = (root, env, run = fakeRun) => resolveKotlinTestRuntime({ env, homeDir: path.join(root, 'empty-home'), run });

test('missing tools fail explicitly without spawning or downloading', () => fixture(({ root }) => {
  assert.throws(() => resolve(root, {}, () => { throw new Error('unexpected process'); }), /Kotlin compiler is missing/);
}));
test('explicit library accepts paths with spaces and reports selected inputs', () => fixture(({ root, library }) => {
  const lib = library();
  const runtime = resolve(root, { KOTLIN_COMPILER_LIB: lib, JAVA_HOME: path.join(root, 'jdk 17') });
  assert.equal(runtime.lib, lib);
  assert.equal(runtime.stdlib, path.join(lib, 'kotlin-stdlib-2.0.21.jar'));
  assert.equal(runtime.java, path.join(root, 'jdk 17/bin', process.platform === 'win32' ? 'java.exe' : 'java'));
  assert.equal(runtime.compilerVersion, '2.0.21');
  assert.equal(runtime.jars.length, 2);
}));
test('invalid explicit compiler does not fall back to a valid Gradle installation', () => fixture(({ root, library }) => {
  library('gradle/lib');
  assert.throws(() => resolve(root, { KOTLIN_COMPILER_LIB: path.join(root, 'missing'), GRADLE_HOME: path.join(root, 'gradle') }), /compiler is missing/);
}));
test('classpath and explicit stdlib take precedence over compiler library', () => fixture(({ root, library }) => {
  const lib = library('explicit');
  const runtime = resolve(root, { KOTLIN_COMPILER_CLASSPATH: path.join(lib, '*'),
    KOTLIN_STDLIB_JAR: path.join(lib, 'kotlin-stdlib-2.0.21.jar'), KOTLIN_COMPILER_LIB: path.join(root, 'unused') });
  assert.deepEqual(runtime.jars.map(filename => path.dirname(filename)), [lib, lib]);
}));
test('missing explicit stdlib does not fall back silently', () => fixture(({ root, library }) => {
  assert.throws(() => resolve(root, { KOTLIN_COMPILER_LIB: library(), KOTLIN_STDLIB_JAR: path.join(root, 'missing.jar') }), /stdlib is missing/);
}));
test('missing classpath file is reported before Java is launched', () => fixture(({ root, library }) => {
  const lib = library();
  assert.throws(() => resolve(root, { KOTLIN_COMPILER_CLASSPATH: [path.join(lib, 'kotlin-compiler-embeddable-2.0.21.jar'), path.join(root, 'missing.jar')].join(path.delimiter) }), /classpath input does not exist/);
}));
test('GRADLE_HOME resolves only existing compatible compiler inputs', () => fixture(({ root, library }) => {
  library('gradle/lib');
  assert.equal(resolve(root, { GRADLE_HOME: path.join(root, 'gradle') }).lib, path.join(root, 'gradle/lib'));
}));
test('GRADLE_USER_HOME resolves an existing wrapper distribution', () => fixture(({ root, library }) => {
  const lib = library('gradle-cache/wrapper/dists/gradle-8.13-bin/hash/gradle-8.13/lib');
  assert.equal(resolve(root, { GRADLE_USER_HOME: path.join(root, 'gradle-cache') }).lib, lib);
}));
test('home Gradle cache is used without a machine-specific path', () => fixture(({ root, library }) => {
  const lib = library('home/.gradle/wrapper/dists/gradle-8.13-bin/hash/gradle-8.13/lib');
  assert.equal(resolveKotlinTestRuntime({ env: {}, homeDir: path.join(root, 'home'), run: fakeRun }).lib, lib);
}));
test('wrong JDK version fails before compiling test sources', () => fixture(({ root, library }) => {
  assert.throws(() => resolve(root, { KOTLIN_COMPILER_LIB: library() }, () => ({ status: 0, stdout: 'openjdk version "21.0.1"' })), /require JDK 17/);
}));
test('wrong compiler version is rejected', () => fixture(({ root, library }) => {
  assert.throws(() => resolve(root, { KOTLIN_COMPILER_LIB: library() }, (java, args) => args[0] === '-version'
    ? fakeRun(java, args) : { status: 0, stderr: 'info: kotlinc-jvm 2.1.0 (JRE 17)' }), /require compiler 2.0.21/);
}));
test('L1 archives must be explicit existing files', () => fixture(({ root, put }) => {
  assert.throws(() => requireKotlinTestInput('DSH_ENGINE_ARCHIVE', {}), /Missing required L1 input/);
  assert.throws(() => requireKotlinTestInput('DSH_ENGINE_ARCHIVE', { DSH_ENGINE_ARCHIVE: path.join(root, 'missing') }), /not an existing file/);
  const archive = put('locked/engine.bin');
  assert.equal(requireKotlinTestInput('DSH_ENGINE_ARCHIVE', { DSH_ENGINE_ARCHIVE: archive }), archive);
}));
test('Kotlin sources prefer moved modules and retain migration fallback', () => fixture(({ root, put }) => {
  const relative = 'engine/EngineFilePatches.kt';
  const old = put(`app/src/main/java/com/dsh/android/${relative}`);
  assert.equal(resolveProjectKotlinSource(root, relative), old);
  const moved = put(`runtime/engine/src/main/java/com/dsh/android/${relative}`);
  assert.equal(resolveProjectKotlinSource(root, relative), moved);
  assert.throws(() => resolveProjectKotlinSource(root, 'engine/Missing.kt'), /source is missing/);
}));
test('constants source sets include split files and prefer the physical module', () => fixture(({ root, put }) => {
  const old = put('app/src/main/java/com/dsh/android/engine/EngineConstants.kt');
  assert.deepEqual(resolveEngineConstantSources(root), [old]);
  const first = put('runtime/engine/src/main/java/com/dsh/android/engine/constants/A.kt');
  const nested = put('runtime/engine/src/main/java/com/dsh/android/engine/constants/prompts/B.kt');
  put('runtime/engine/src/main/java/com/dsh/android/engine/constants/not-source.txt');
  assert.deepEqual(resolveEngineConstantSources(root), [first, nested]);
}));
test('real selected compiler compiles and executes Kotlin assertions', () => fixture(({ root, put }) => {
  const runtime = resolveKotlinTestRuntime();
  const source = put('Smoke.kt', 'fun main() { check((1..4).sum() == 10); println("KOTLIN_RUNTIME_OK") }');
  const output = path.join(root, 'classes');
  const compiled = spawnSync(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', runtime.stdlib, '-d', output, source], { encoding: 'utf8', timeout: 30_000 });
  assert.equal(compiled.status, 0, `${compiled.error ?? ''}\n${compiled.stdout}\n${compiled.stderr}`);
  const executed = spawnSync(runtime.java, ['-cp', [output, runtime.stdlib].join(path.delimiter), 'SmokeKt'], { encoding: 'utf8', timeout: 10_000 });
  assert.equal(executed.status, 0, `${executed.error ?? ''}\n${executed.stderr}`);
  assert.match(executed.stdout, /KOTLIN_RUNTIME_OK/);
}));
