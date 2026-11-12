#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, dirname, join, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { reference, sourceSnapshot, inspectPair } from './release/inspect.mjs';
import { checkRuntimeAssets } from './check-runtime-assets.mjs';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const DEVICE_SCENARIOS = ['cold-start', 'keep-alive', 'web-permissions-system', 'web-permissions-gecko', 'recovery', 'bubble-dispose', 'browser-dispose', 'upgrade-market-state'];
const LEVELS = ['L0', 'L1', 'L2', 'L3'];
const json = value => JSON.stringify(value, null, 2) + '\n';
const regular = path => existsSync(path) && statSync(path).isFile();
const outside = (root, path) => { const rel = relative(root, path); return isAbsolute(rel) || rel === '..' || rel.startsWith('../'); };
const quoted = value => JSON.stringify(String(value));

/** Verify an independently collected report and hash every referenced raw evidence file. */
export function validateDeviceEvidence(path, { source, now = Date.now() } = {}) {
  const report = JSON.parse(readFileSync(path, 'utf8'));
  assert.equal(report.schemaVersion, 1, 'Unsupported device evidence schema');
  assert.equal(report.kind, 'dsh-device-evidence', 'Independent device report is required');
  assert.ok(Number.isFinite(Date.parse(report.createdAt)) && Date.parse(report.createdAt) <= now + 60_000, 'Invalid device observation time');
  assert.ok(report.device?.serial && report.device?.model && report.device?.androidVersion, 'Real device identity is missing');
  assert.match(report.source?.commit ?? '', /^[a-f0-9]{40}$/);
  assert.match(report.source?.treeDigest ?? '', /^[a-f0-9]{64}$/);
  if (source) for (const key of ['commit', 'treeDigest']) assert.equal(report.source[key], source[key], `Device evidence belongs to different source ${key}`);
  assert.equal(report.apks?.length, 2, 'Both tested flavor artifacts are required');
  for (const flavor of ['system', 'gecko']) {
    const apk = report.apks.find(item => item.flavor === flavor);
    assert.ok(apk && isAbsolute(apk.path), `Missing explicit ${flavor} APK input`);
    assert.equal(reference(apk.path).sha256, apk.sha256, `${flavor} tested APK has changed`);
  }
  assert.ok(Array.isArray(report.checks), 'Device checks are missing');
  const names = new Set();
  for (const check of report.checks) {
    assert.ok(check.name && !names.has(check.name), 'Duplicate or unnamed device check'); names.add(check.name);
    assert.equal(check.level, 'L3'); assert.equal(check.status, 'passed', `${check.name} did not pass`);
    assert.ok(check.command, `${check.name} has no actual command`);
    assert.ok(Number.isSafeInteger(check.tests) && check.tests > 0, `${check.name} executed no tests`);
    assert.equal(check.skipped, 0, `${check.name} contains skipped tests`);
    assert.ok(Array.isArray(check.evidence) && check.evidence.length, `${check.name} has no raw evidence`);
    for (const input of check.evidence) {
      assert.ok(input.path && isAbsolute(input.path), 'Raw evidence paths must be explicit absolute paths');
      const actual = reference(input.path);
      assert.equal(actual.sha256, input.sha256, `Device evidence changed: ${input.path}`);
      assert.equal(actual.bytes, input.bytes, `Device evidence byte count changed: ${input.path}`);
      assert.ok(actual.bytes > 0, 'Empty device evidence is not proof');
    }
  }
  for (const scenario of DEVICE_SCENARIOS) assert.ok(names.has(scenario), `Missing real device scenario: ${scenario}`);
  return { report: reference(path), device: report.device, apks: report.apks, checks: report.checks };
}

function walk(directory, predicate) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? walk(path, predicate) : predicate(path) ? [path] : [];
  });
}
export function readJUnit(directory, since) {
  const files = walk(directory, path => /TEST-.*\.xml$/.test(path));
  assert.ok(files.length, `No real JUnit reports in ${directory}`);
  let tests = 0, skipped = 0, failures = 0;
  for (const path of files) {
    assert.ok(statSync(path).mtimeMs >= since, `Stale JUnit output: ${path}`);
    const text = readFileSync(path, 'utf8');
    const suite = text.match(/<testsuite\b([^>]*)>/)?.[1];
    assert.ok(suite, `Invalid JUnit report: ${path}`);
    const count = key => Number(suite.match(new RegExp(`\\b${key}=["'](\\d+)["']`))?.[1] ?? 0);
    tests += count('tests'); skipped += count('skipped'); failures += count('failures') + count('errors');
  }
  assert.ok(tests > 0, 'Android test tasks executed zero tests');
  assert.equal(skipped, 0, 'Android tests contain skips'); assert.equal(failures, 0, 'Android tests contain failures');
  return { tests, skipped, reports: files.map(reference) };
}

export function parseOptions(args) {
  const options = {};
  const allowed = new Set(['level', 'out', 'root', 'runtime-lock', 'device-evidence', 'system', 'gecko', 'sdk', 'generated-dir', 'build-tools']);
  for (let index = 0; index < args.length; index++) {
    const key = args[index].replace(/^--/, '');
    assert.ok(args[index].startsWith('--') && allowed.has(key), `Unknown option ${args[index]}`);
    assert.ok(args[index + 1] && !args[index + 1].startsWith('--'), `Missing value for ${args[index]}`);
    assert.equal(options[key], undefined, `Duplicate option ${args[index]}`);
    options[key] = args[++index];
  }
  assert.ok(LEVELS.includes(options.level), 'Select exactly one --level L0|L1|L2|L3');
  assert.ok(options.out && isAbsolute(options.out), '--out must be an explicit absolute report path');
  options.root = resolve(options.root || ROOT); options.out = resolve(options.out);
  assert.ok(outside(options.root, options.out), 'Store gate reports/evidence outside the source checkout');
  return options;
}

export function runGates(options) {
  const root = options.root, out = options.out, level = options.level;
  assert.ok(!existsSync(out), 'A gate report is immutable; choose a new --out path');
  const evidenceDir = `${out}.evidence`;
  mkdirSync(dirname(out), { recursive: true }); mkdirSync(evidenceDir, { recursive: false });
  const report = { schemaVersion: 1, createdAt: new Date().toISOString(), level, node: process.version, source: sourceSnapshot(root), checks: [] };
  let sequence = 0;
  function record(name, command, fn) {
    const path = join(evidenceDir, `${String(++sequence).padStart(2, '0')}-${name}.log`);
    let status = 'passed', data, error;
    try { data = fn(); } catch (caught) { status = caught.notRun ? 'not-run' : 'failed'; data = caught.data; error = caught.message; }
    writeFileSync(path, data?.log ?? (error ? `${error}\n` : json(data)));
    const check = { name, level, status, command, tests: data?.tests ?? 0, skipped: data?.skipped ?? 0, evidence: reference(path), ...(data?.inputs ? { inputs: data.inputs } : {}), ...(error ? { error } : {}) };
    report.checks.push(check); writeFileSync(out, json(report));
    console.log(`${level} ${name}: ${status} (${check.tests} tests, ${check.skipped} skipped)`);
    return check;
  }
  function requireInputs(condition, message) { if (!condition) { const error = new Error(message); error.notRun = true; throw error; } }
  function execute(name, binary, args, { cwd = root, test = false, env = process.env } = {}) {
    return record(name, [binary, ...args].map(quoted).join(' '), () => {
      const result = spawnSync(binary, args, { cwd, env, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      const log = `${result.stdout || ''}${result.stderr || ''}\nexit=${result.status}\n${result.error || ''}`;
      const tests = test ? Number(log.match(/^# tests (\d+)\s*$/m)?.[1] ?? 0) : 1;
      const skipped = test ? Number(log.match(/^# skipped (\d+)\s*$/m)?.[1] ?? -1) : 0;
      if (result.error || result.status !== 0 || tests <= 0 || skipped !== 0) {
        const error = new Error(`${name}: command failed, ran no tests, or skipped tests\n${log}`); error.data = { log, tests, skipped }; throw error;
      }
      return { log, tests, skipped };
    });
  }
  const nodeTests = (name, files, cwd = root, env = process.env) => execute(name, process.execPath, ['--test', '--test-reporter=tap', ...files], { cwd, test: true, env });
  if (level === 'L0') {
    execute('module-boundaries', process.execPath, ['tools/check-module-boundaries.mjs']);
    nodeTests('module-guard-behavior', ['tools/test-module-boundaries.mjs', 'tools/test-source-policy.mjs']);
    execute('architecture', 'bash', ['tools/check-architecture.sh'], { env: { ...process.env, DSH_ESBUILD_BINARY: join(root, 'tools/node_modules/.bin/esbuild') } });
    nodeTests('market-official-fixtures', ['test/market.mjs'], join(root, 'plugins/android/components/dsh-android-market'), { ...process.env, DSH_MARKET_REAL_ENGINE: '0' });
    nodeTests('mobile-features', ['plugins/android/components/dsh-android-balance/test/mobile-features.mjs']);
    nodeTests('plugin-components', ['tools/test-plugin-components.mjs']);
    nodeTests('release-workflow', ['tools/release/test/workflow.test.mjs']);
    nodeTests('native-recipes-contracts', ['tools/test-native-recipes.mjs']);
    nodeTests('remote-provenance-contracts', ['tools/test-remote-plugin-provenance.mjs']);
    execute('remote-source-lock', process.execPath, ['tools/check-remote-plugin-provenance.mjs']);
  } else if (level === 'L1') {
    const input = record('explicit-runtime-inputs', 'validate explicit L1 input paths and runtime asset lock', () => {
      for (const name of ['DSH_ENGINE_PACKAGE', 'DSH_ENGINE_ARCHIVE', 'DSH_WEBVIEW_COMPAT_ARCHIVE', 'ENGINE_TEST_JSON_JAR']) requireInputs(process.env[name] && isAbsolute(process.env[name]) && regular(process.env[name]), `Missing explicit real input ${name}`);
      requireInputs(process.env.JAVA_HOME && (process.env.KOTLIN_COMPILER_LIB || process.env.KOTLIN_COMPILER_CLASSPATH), 'L1 needs explicit JAVA_HOME and KOTLIN_COMPILER_LIB or KOTLIN_COMPILER_CLASSPATH; no implicit compiler');
      const lock = options['runtime-lock']; requireInputs(lock && isAbsolute(lock) && regular(lock), 'L1 needs explicit --runtime-lock');
      const result = checkRuntimeAssets({ root, lockPath: lock });
      const manifest = JSON.parse(readFileSync(lock, 'utf8'));
      const engine = manifest.assets.find(asset => asset.id === 'engine');
      assert.ok(engine, 'Runtime lock lacks engine asset');
      assert.equal(reference(process.env.DSH_ENGINE_ARCHIVE).sha256, engine.sha256, 'Explicit engine archive differs from runtime lock');
      const compat = manifest.assets.find(asset => asset.id === 'webview-compat');
      assert.ok(compat, 'Runtime lock lacks webview-compat asset');
      assert.equal(reference(process.env.DSH_WEBVIEW_COMPAT_ARCHIVE).sha256, compat.sha256, 'Explicit WebView compat archive differs from runtime lock');
      return { tests: result.assets, skipped: 0, inputs: [reference(lock), reference(process.env.DSH_ENGINE_PACKAGE), reference(process.env.DSH_ENGINE_ARCHIVE), reference(process.env.DSH_WEBVIEW_COMPAT_ARCHIVE), reference(process.env.ENGINE_TEST_JSON_JAR)] };
    });
    if (input.status === 'passed') {
      nodeTests('market-real-engine', ['test/market.mjs'], join(root, 'plugins/android/components/dsh-android-market'), { ...process.env, DSH_MARKET_REAL_ENGINE: '1' });
      nodeTests('real-kotlin-engine', ['tools/test-kotlin-runtime.mjs', 'app/src/test/engine-components.mjs', 'app/src/test/engine-patch-registry.mjs', 'app/src/test/builtin-plugin-update-patch.mjs', 'app/src/test/engine-constants-values.mjs', 'runtime/engine/src/main/java/com/dsh/android/engine/BuiltinPluginBootstrap.test.mjs']);
      execute('official-registry-engine', process.execPath, ['app/src/test/engine-patch-registry.mjs', '--official']);
    }
  } else if (level === 'L2') {
    const input = record('android-inputs', 'validate explicit JDK/Android SDK/runtime lock', () => {
      requireInputs(process.env.JAVA_HOME && regular(join(process.env.JAVA_HOME, 'bin/java')), 'L2 needs explicit JAVA_HOME');
      const sdk = options.sdk; requireInputs(sdk && isAbsolute(sdk) && existsSync(sdk), 'L2 needs explicit --sdk');
      const lock = options['runtime-lock']; requireInputs(lock && isAbsolute(lock) && regular(lock), 'L2 needs explicit --runtime-lock');
      const version = spawnSync(join(process.env.JAVA_HOME, 'bin/java'), ['-version'], { encoding: 'utf8' });
      assert.ok(version.status === 0 && /version "17[.\"]/.test(version.stderr), 'L2 requires JDK 17');
      const inputs = [reference(lock)];
      let log = version.stderr;
      if (process.env.DSH_GRADLE_BINARY !== undefined) {
        const binary = process.env.DSH_GRADLE_BINARY;
        requireInputs(isAbsolute(binary) && regular(binary), 'DSH_GRADLE_BINARY must be an absolute existing regular file');
        const toolchainPath = join(root, 'catalogs/toolchain.lock.json');
        const expected = JSON.parse(readFileSync(toolchainPath, 'utf8')).tools?.gradle;
        assert.ok(typeof expected === 'string' && expected.length > 0, 'Toolchain lock lacks tools.gradle');
        inputs.push(reference(binary), reference(toolchainPath));
        const gradleVersion = spawnSync(binary, ['--version'], { cwd: root, encoding: 'utf8' });
        log += `\n${quoted(binary)} --version\n${gradleVersion.stdout || ''}${gradleVersion.stderr || ''}\nexit=${gradleVersion.status}\n${gradleVersion.error || ''}`;
        const actual = (gradleVersion.stdout || '').match(/^Gradle ([^\r\n]+)\r?$/m)?.[1];
        if (gradleVersion.error || gradleVersion.status !== 0 || actual !== expected) {
          const error = new Error(`Explicit Gradle version must equal locked ${expected}; observed ${actual ?? 'unknown'}`);
          error.data = { log, inputs, tests: 1, skipped: 0 }; throw error;
        }
      }
      const result = checkRuntimeAssets({ root, lockPath: lock });
      return { tests: result.assets, inputs, log };
    });
    if (input.status === 'passed') {
      nodeTests('android-owner-behavior', [
        'app/src/main/java/com/dsh/android/ui/tests/ui-controllers.test.mjs',
        'feature/bubble/tests/bubble.test.mjs',
        'feature/browser/src/main/java/com/dsh/android/browser/BrowserLifecycle.test.mjs',
        'feature/browser/src/main/java/com/dsh/android/browser/BrowserMailbox.test.mjs',
        'bridge/transport/src/test/AtomicMailboxFiles.test.mjs',
        'core/logging/src/test/LogPrivacy.test.mjs',
        'runtime/engine/src/test/EngineFilePolicy.test.mjs',
        'app/src/test/BackupSafety.test.mjs',
        'app/src/test/WebClientHealth.test.mjs',
        'runtime/termux/src/main/java/com/dsh/android/termux/TermuxBootstrap.test.mjs',
        'web/host/src/test/WebHostLifecycle.test.mjs',
        'web/host/src/test/GeckoPermissions.test.mjs',
      ]);
      const since = Date.now();
      const gradleArgs = ['--offline', '--no-daemon', '--rerun-tasks', ':app:testSystemDebugUnitTest', ':app:testGeckoDebugUnitTest', ':app:lintSystemDebug', ':app:lintGeckoDebug', ':app:assembleSystemRelease', ':app:assembleGeckoRelease', '--console=plain'];
      const task = execute('android-tasks', process.env.DSH_GRADLE_BINARY ?? 'bash', process.env.DSH_GRADLE_BINARY !== undefined ? gradleArgs : ['gradlew', ...gradleArgs], { env: { ...process.env, ANDROID_HOME: options.sdk, ANDROID_SDK_ROOT: options.sdk } });
      if (task.status === 'passed') {
        record('android-junit-results', 'read fresh JUnit XML for both flavor unit-test tasks', () => {
          const results = ['testSystemDebugUnitTest', 'testGeckoDebugUnitTest'].map(name => readJUnit(join(root, 'app/build/test-results', name), since));
          return { tests: results.reduce((sum, item) => sum + item.tests, 0), skipped: 0, inputs: results.flatMap(item => item.reports) };
        });
        record('android-artifacts', 'inspect actual dual release APK manifests/signatures/runtime assets/plugin contents/lint outputs', () => {
          const apks = Object.fromEntries(['system', 'gecko'].map(flavor => [flavor, options[flavor] || join(root, `app/build/outputs/apk/${flavor}/release/app-${flavor}-release.apk`)]));
          for (const path of Object.values(apks)) assert.ok(regular(path) && statSync(path).mtimeMs >= since, `Missing/stale actual APK ${path}`);
          const lint = ['system', 'gecko'].map(flavor => join(root, `app/build/reports/lint-results-${flavor}Debug.xml`));
          for (const path of lint) { assert.ok(regular(path) && statSync(path).mtimeMs >= since, `Missing/stale lint report ${path}`); assert.ok(!/severity="(?:Error|Fatal)"/.test(readFileSync(path, 'utf8')), `Lint errors in ${path}`); }
          const runtimeAssets = JSON.parse(readFileSync(options['runtime-lock'], 'utf8')).assets;
          const products = inspectPair({ root, ...apks, sdk: options.sdk, buildTools: options['build-tools'], catalog: join(root, 'catalogs/plugin-components.json'), generatedDir: options['generated-dir'] || join(root, 'app/build/generated/plugin-components'), runtimeAssets });
          return { tests: products.length + lint.length, inputs: [...products, ...lint.map(reference)] };
        });
      }
    }
  } else {
    record('real-device-evidence', 'validate independently collected L3 device evidence and raw artifact hashes', () => {
      const path = options['device-evidence']; requireInputs(path && isAbsolute(path) && regular(path), 'L3 requires explicit --device-evidence; no device was run by this command');
      const actual = validateDeviceEvidence(path, { source: report.source });
      return { tests: actual.checks.reduce((sum, check) => sum + check.tests, 0), skipped: 0, inputs: [actual.report, ...actual.apks], log: json(actual) };
    });
  }
  report.completedAt = new Date().toISOString(); writeFileSync(out, json(report));
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const report = runGates(parseOptions(process.argv.slice(2)));
    if (report.checks.some(check => check.status !== 'passed')) process.exitCode = 1;
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
