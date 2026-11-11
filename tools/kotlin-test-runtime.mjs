import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

/** Find the checkout root from a test URL, independent of module nesting. */
export function resolveRepositoryRoot(moduleUrl) {
  let directory = path.dirname(fileURLToPath(moduleUrl));
  while (true) {
    if (isFile(path.join(directory, 'settings.gradle.kts'))
        && isFile(path.join(directory, 'tools/kotlin-test-runtime.mjs'))) return directory;
    const parent = path.dirname(directory);
    if (parent === directory) throw new Error(`Repository root is missing above ${moduleUrl}; expected settings.gradle.kts and tools/kotlin-test-runtime.mjs.`);
    directory = parent;
  }
}

const compilerPattern = /^kotlin-compiler-embeddable-([\d.]+)\.jar$/;
const stdlibPattern = /^kotlin-stdlib-[\d.]+\.jar$/;
const requiredKotlinVersion = '2.0.21';
const isFile = filename => { try { return fs.statSync(filename).isFile(); } catch { return false; } };
const isDirectory = filename => { try { return fs.statSync(filename).isDirectory(); } catch { return false; } };
const jarsIn = dir => isDirectory(dir)
  ? fs.readdirSync(dir).filter(name => name.endsWith('.jar')).sort().map(name => path.join(dir, name))
  : [];
const hasCompiler = jars => jars.some(filename => compilerPattern.test(path.basename(filename)));

function expandClasspath(value) {
  return value.split(path.delimiter).filter(Boolean).flatMap(entry => {
    const absolute = path.resolve(entry);
    return path.basename(absolute) === '*' ? jarsIn(path.dirname(absolute)) : [absolute];
  });
}

// Inspect only existing Gradle distributions, never global installs or downloaded inputs.
function gradleLibraries(env, homeDir) {
  const candidates = [];
  if (env.GRADLE_HOME) candidates.push(path.resolve(env.GRADLE_HOME, 'lib'));
  const cache = env.GRADLE_USER_HOME || path.join(homeDir, '.gradle');
  const distributions = path.join(cache, 'wrapper', 'dists');
  if (!isDirectory(distributions)) return candidates;
  const directories = dir => fs.readdirSync(dir, { withFileTypes: true })
    .filter(entry => entry.isDirectory()).map(entry => entry.name).sort().reverse();
  for (const distribution of directories(distributions)) {
    if (!distribution.startsWith('gradle-')) continue;
    const distributionDir = path.join(distributions, distribution);
    for (const hash of directories(distributionDir)) {
      const hashDir = path.join(distributionDir, hash);
      // Standard wrapper layout: dists/gradle-<v>-bin/<hash>/gradle-<v>/lib.
      for (const install of directories(hashDir)) {
        if (install.startsWith('gradle-')) candidates.push(path.join(hashDir, install, 'lib'));
      }
    }
  }
  return candidates;
}

function commandOutput(java, args, env, run) {
  const result = run(java, args, { encoding: 'utf8', env, timeout: 20_000, maxBuffer: 1024 * 1024 });
  if (result.error || result.status !== 0) {
    throw new Error(`Kotlin test tool validation failed: ${java} ${args.join(' ')}\n${result.error ?? ''}\n${result.stdout ?? ''}\n${result.stderr ?? ''}`);
  }
  return `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
}

/**
 * Resolve existing local tools and validate JDK 17 / Kotlin 2.0.21.
 * Explicit configuration is authoritative: an invalid value fails instead of falling back.
 * `env`, `homeDir`, and `run` allow isolated discovery tests; normal callers pass no arguments.
 */
export function resolveKotlinTestRuntime({ env = process.env, homeDir = os.homedir(), run = spawnSync } = {}) {
  let jars;
  let lib;
  if (env.KOTLIN_COMPILER_CLASSPATH) {
    jars = expandClasspath(env.KOTLIN_COMPILER_CLASSPATH);
    lib = env.KOTLIN_COMPILER_LIB ? path.resolve(env.KOTLIN_COMPILER_LIB) : undefined;
  } else if (env.KOTLIN_COMPILER_LIB) {
    lib = path.resolve(env.KOTLIN_COMPILER_LIB);
    jars = jarsIn(lib);
  } else {
    const candidates = gradleLibraries(env, homeDir);
    lib = candidates.find(candidate => jarsIn(candidate).some(filename =>
      path.basename(filename) === `kotlin-compiler-embeddable-${requiredKotlinVersion}.jar`));
    if (lib) jars = jarsIn(lib);
  }
  if (!jars?.length || !hasCompiler(jars)) {
    throw new Error('Kotlin compiler is missing. Set KOTLIN_COMPILER_CLASSPATH or KOTLIN_COMPILER_LIB, or provide an existing GRADLE_HOME / Gradle wrapper cache. No tool downloads are performed.');
  }
  for (const filename of jars) {
    if (!isFile(filename)) throw new Error(`Kotlin compiler classpath input does not exist: ${filename}`);
  }
  const stdlib = env.KOTLIN_STDLIB_JAR
    ? path.resolve(env.KOTLIN_STDLIB_JAR)
    : jars.find(filename => stdlibPattern.test(path.basename(filename)))
      ?? (lib ? jarsIn(lib).find(filename => stdlibPattern.test(path.basename(filename))) : undefined);
  if (!stdlib || !isFile(stdlib)) {
    throw new Error('Kotlin stdlib is missing. Set KOTLIN_STDLIB_JAR or include kotlin-stdlib-2.0.21.jar in the compiler inputs.');
  }
  const java = env.JAVA_HOME
    ? path.join(path.resolve(env.JAVA_HOME), 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java';
  const javaOutput = commandOutput(java, ['-version'], env, run);
  const javaVersion = javaOutput.match(/(?:version\s+"|openjdk\s+)(\d+(?:[.\d]+)?)/)?.[1];
  if (!javaVersion || javaVersion.split('.')[0] !== '17') {
    throw new Error(`Kotlin tests require JDK 17; received ${javaVersion ?? 'unrecognized java -version output'}. Set JAVA_HOME explicitly.`);
  }
  const classpath = [...new Set(jars)].join(path.delimiter);
  const compilerOutput = commandOutput(java, ['-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-version', '-no-stdlib', '-no-reflect'], env, run);
  const compilerVersion = compilerOutput.match(/kotlinc-jvm\s+([^\s]+)/)?.[1];
  if (compilerVersion !== requiredKotlinVersion) {
    throw new Error(`Kotlin tests require compiler ${requiredKotlinVersion}; received ${compilerVersion ?? 'unrecognized compiler output'}. Select the matching compiler inputs.`);
  }
  if (path.basename(stdlib) !== `kotlin-stdlib-${requiredKotlinVersion}.jar`) {
    throw new Error(`Kotlin tests require kotlin-stdlib-${requiredKotlinVersion}.jar; received ${path.basename(stdlib)}.`);
  }
  return { classpath, stdlib, java, jars: [...new Set(jars)], lib, compilerVersion, javaVersion };
}

/** Explicit L1 archive inputs; no checkout asset or global-engine fallback. */
export function requireKotlinTestInput(name, env = process.env) {
  const input = env[name];
  if (!input) throw new Error(`Missing required L1 input ${name}. Set it to an existing locked archive; no implicit assets or downloads are used.`);
  const filename = path.resolve(input);
  if (!isFile(filename)) throw new Error(`L1 input ${name} is not an existing file: ${filename}`);
  return filename;
}

/** Source set for the constants split; do not assume all literals live in one file. */
export function resolveEngineConstantSources(root) {
  const roots = [
    path.join(root, 'runtime/engine/src/main/java/com/dsh/android/engine'),
    path.join(root, 'app/src/main/java/com/dsh/android/engine'),
  ];
  const engine = roots.find(candidate => isFile(path.join(candidate, 'EngineConstants.kt'))
    || isDirectory(path.join(candidate, 'constants')));
  if (!engine) throw new Error(`Engine constants source set is missing. Checked: ${roots.join(', ')}`);
  const sources = [];
  const legacy = path.join(engine, 'EngineConstants.kt');
  if (isFile(legacy)) sources.push(legacy);
  const walk = dir => {
    if (!isDirectory(dir)) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const filename = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(filename);
      else if (entry.isFile() && entry.name.endsWith('.kt')) sources.push(filename);
    }
  };
  walk(path.join(engine, 'constants'));
  if (!sources.length) throw new Error(`Engine constants source set is empty: ${engine}`);
  return sources;
}

/** Resolve moved sources during the app -> physical module migration. */
export function resolveProjectKotlinSource(root, packageRelativePath) {
  const candidates = [
    path.join(root, 'runtime/engine/src/main/java/com/dsh/android', packageRelativePath),
    path.join(root, 'core/logging/src/main/java/com/dsh/android', packageRelativePath),
    path.join(root, 'app/src/main/java/com/dsh/android', packageRelativePath),
  ];
  const source = candidates.find(isFile);
  if (!source) throw new Error(`Kotlin test source is missing: ${packageRelativePath}\nChecked: ${candidates.join(', ')}`);
  return source;
}
