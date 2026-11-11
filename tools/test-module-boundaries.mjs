import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { checkModules, maskKotlin } from './check-module-boundaries.mjs';
import { validateDeviceEvidence, DEVICE_SCENARIOS, readJUnit, parseOptions } from './run-gates.mjs';
import { reference } from './release/inspect.mjs';

function fixture(fn, { deps = [], allowed = { ':low': [], ':high': [':low'] } } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'module-boundary-'));
  const put = (path, text) => { mkdirSync(dirname(join(root, path)), { recursive: true }); writeFileSync(join(root, path), text); };
  put('settings.gradle.kts', 'include(":low", ":high")');
  put('low/build.gradle.kts', 'android { namespace = "sample.low" }');
  put('high/build.gradle.kts', 'android { namespace = "sample.high" }\ndependencies { ' + deps.map(dep => `implementation(project("${dep}"))`).join('\n') + ' }');
  put('low/src/main/kotlin/Low.kt', 'package legacy.shared\nclass Value\nfun provider() = 1\nval constant = 2');
  put('high/src/main/kotlin/High.kt', 'package upper\nclass Consumer');
  const check = () => checkModules({ root, modules: [':low', ':high'], allowed });
  try { fn({ put, check, root }); } finally { assert.equal(dirname(root), tmpdir()); assert.ok(root.startsWith(join(tmpdir(), 'module-boundary-'))); rmSync(root, { recursive: true, force: true }); }
}

test('valid direct dependency resolves retained Kotlin packages', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package upper\nimport legacy.shared.Value\nclass Consumer(val value: Value)');
  assert.deepEqual(check().errors, []);
}, { deps: [':low'] }));
test('reverse import is checked using actual declaration module', () => fixture(({put,check}) => {
  put('low/src/main/kotlin/Low.kt', 'package legacy.shared\nimport upper.Consumer\nclass Value(val value: Consumer)');
  assert.ok(check().errors.some(e => e.includes('forbidden source dependency :low -> :high')));
}));
test('top-level function import alias cannot bypass owner mapping', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package upper\nimport legacy.shared.provider as hidden\nclass Consumer { fun run() = hidden() }');
  assert.ok(check().errors.some(e => e.includes('missing direct project dependency')));
}));
test('fully-qualified source references cannot bypass imports', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package upper\nclass Consumer { fun run() = legacy.shared.provider() }');
  assert.ok(check().errors.some(e => e.includes('legacy.shared.provider')));
}));
test('same-package references across module directories need direct dependency', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package legacy.shared\nclass Consumer(val value: Value)');
  assert.ok(check().errors.some(e => e.includes('legacy.shared.Value')));
}));
test('comments and ordinary strings do not create declarations or references', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package upper\n/* nested /* class Nope */ import legacy.shared.Value */\nclass Consumer { val msg = "legacy.shared.Value" }');
  assert.deepEqual(check().errors, []);
}));
test('missing settings module and build file fail closed', () => fixture(({put,check}) => {
  put('settings.gradle.kts', 'include(":low")');
  assert.ok(check().errors.some(e => e.includes('Missing settings include: :high')));
}));
test('unregistered Gradle project is rejected', () => fixture(({put,check}) => {
  put('settings.gradle.kts', 'include(":low", ":high", ":rogue")');
  assert.ok(check().errors.some(e => e.includes('Unregistered Gradle module')));
}));
test('Gradle cycles fail even where the policy permits edges', () => fixture(({put,check}) => {
  put('low/build.gradle.kts', 'dependencies { implementation(project(":high")) }');
  assert.ok(check().errors.some(e => e.includes('dependency cycle')));
}, { deps: [':low'], allowed: { ':low': [':high'], ':high': [':low'] } }));
test('forbidden Gradle dependency fails without source import', () => fixture(({put,check}) => {
  put('low/build.gradle.kts', 'dependencies { implementation(project(":high")) }');
  assert.ok(check().errors.some(e => e.includes('forbidden Gradle dependency')));
}));
test('duplicate declaration owned by different modules is rejected', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package legacy.shared\nclass Value');
  assert.ok(check().errors.some(e => e.includes('Ambiguous declaration')));
}));
test('generated R imports map to namespace owner and cannot reverse direction', () => fixture(({put,check}) => {
  put('low/src/main/kotlin/Low.kt', 'package legacy.shared\nimport sample.high.R\nclass Value');
  assert.ok(check().errors.some(e => e.includes('forbidden source dependency')));
}));
test('unresolved local import is not accepted as external dependency', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package upper\nimport com.dsh.android.missing.NoSuchType\nclass Consumer');
  assert.ok(check().errors.some(e => e.includes('unresolved project import')));
}));
test('executable string interpolation cannot hide fully-qualified dependencies', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package upper\nclass Consumer { val msg = "value=${legacy.shared.provider()}" }');
  assert.ok(check().errors.some(e => e.includes('legacy.shared.provider')));
}));
test('constructor properties are not exported as top-level providers', () => fixture(({put,check}) => {
  put('high/src/main/kotlin/High.kt', 'package legacy.shared\nclass Consumer(val render: () -> Unit)');
  put('low/src/main/kotlin/Low.kt', 'package legacy.shared\nclass Value { fun render() = Unit }');
  assert.deepEqual(check().errors, []);
}));
test('view binding is indexed from actual namespace and layout ownership', () => fixture(({put,check}) => {
  put('high/build.gradle.kts', 'android { namespace = "sample.high"; buildFeatures { viewBinding = true } }');
  put('high/src/main/res/layout/activity_main.xml', '<LinearLayout/>');
  put('high/src/main/kotlin/High.kt', 'package upper\nimport sample.high.databinding.ActivityMainBinding\nclass Consumer');
  assert.deepEqual(check().errors, []);
}));
test('device evidence contract accepts complete hashed fixtures (unit fixture, not real L3)', () => fixture(({root,put}) => {
  const report = deviceFixture(root, put);
  put('device.json', JSON.stringify(report));
  assert.equal(validateDeviceEvidence(join(root, 'device.json')).checks.length, DEVICE_SCENARIOS.length);
}));
function deviceFixture(root, put) {
  put('system.apk', 'unit-only system'); put('gecko.apk', 'unit-only gecko'); put('raw.log', 'UNIT FIXTURE, NOT DEVICE EVIDENCE');
  return { schemaVersion: 1, kind: 'dsh-device-evidence', createdAt: new Date().toISOString(), device: { serial: 'unit-fixture', model: 'fixture', androidVersion: 'fixture' }, source: { commit: 'a'.repeat(40), treeDigest: 'b'.repeat(64) },
    apks: ['system','gecko'].map(flavor => ({ flavor, ...reference(join(root, `${flavor}.apk`)) })),
    checks: DEVICE_SCENARIOS.map(name => ({ name, level: 'L3', status: 'passed', command: 'unit-fixture-only', tests: 1, skipped: 0, evidence: [reference(join(root, 'raw.log'))] })) };
}
test('device evidence refuses not-run, skipped, and zero-test checks', () => fixture(({root,put}) => {
  for (const change of [{status:'not-run'}, {skipped:1}, {tests:0}]) {
    const report = deviceFixture(root, put); Object.assign(report.checks[0], change); put('device.json', JSON.stringify(report));
    assert.throws(() => validateDeviceEvidence(join(root, 'device.json')));
  }
}));
test('device raw evidence cannot be replaced after observation', () => fixture(({root,put}) => {
  const report = deviceFixture(root, put); put('device.json', JSON.stringify(report)); put('raw.log', 'tampered');
  assert.throws(() => validateDeviceEvidence(join(root, 'device.json')), /evidence changed/);
}));
test('device report must cover every scenario and the current source', () => fixture(({root,put}) => {
  const report = deviceFixture(root, put); report.checks.pop(); put('device.json', JSON.stringify(report));
  assert.throws(() => validateDeviceEvidence(join(root, 'device.json')), /Missing real device scenario/);
  assert.throws(() => validateDeviceEvidence(join(root, 'device.json'), {source: {commit:'c'.repeat(40)}}), /different source/);
}));
test('missing/zero/skipped/failing Android tests never pass from exit code alone', () => fixture(({root,put}) => {
  assert.throws(() => readJUnit(join(root, 'junit'), 0), /No real JUnit/);
  for (const attributes of ['tests="0"', 'tests="3" skipped="1"', 'tests="3" errors="1"']) {
    put('junit/TEST-Example.xml', `<testsuite ${attributes}></testsuite>`);
    assert.throws(() => readJUnit(join(root, 'junit'), 0));
  }
  put('junit/TEST-Example.xml', '<testsuite tests="3" skipped="0" failures="0" errors="0"></testsuite>');
  assert.equal(readJUnit(join(root, 'junit'), 0).tests, 3);
  assert.throws(() => readJUnit(join(root, 'junit'), Date.now() + 10_000), /Stale JUnit/);
}));
test('gate CLI requires one explicit level and an external report path', () => {
  assert.throws(() => parseOptions(['--level','L0']), /--out/);
  assert.throws(() => parseOptions(['--level','L0','--level','L1','--out','/tmp/report.json']), /Duplicate/);
  assert.throws(() => parseOptions(['--level','L2','--root','/tmp/source','--out','/tmp/source/report.json']), /outside/);
  assert.equal(parseOptions(['--level','L3','--out','/tmp/report.json']).level, 'L3');
});

test('mask preserves offsets and handles escaped strings and nested comments', () => {
  const input = '/* a /* b */ c */\nval x = "a\\\"b"\nclass Real';
  const result = maskKotlin(input);
  assert.equal(result.length, input.length); assert.equal(result.split('\n').length, 3); assert.ok(result.includes('class Real'));
});
