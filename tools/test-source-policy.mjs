import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { checkSourcePolicy, countLines } from './check-source-policy.mjs';

const source = lines => 'package fixture\n' + '// line\n'.repeat(lines - 1);
const entry = (maxLines = 1002) => ({ path: 'low/src/main/kotlin/Large.kt', maxLines, owner: ':low', reason: 'Existing Window orchestration with owned lifecycle; reduce by responsibility.' });
function fixture(fn) {
  const root = mkdtempSync(join(tmpdir(), 'source-policy-'));
  const put = (path, content) => { mkdirSync(dirname(join(root, path)), {recursive:true}); writeFileSync(join(root, path), content); };
  const lock = (baselines = [], overrides = {}) => {
    const policy = {schemaVersion:1,maxNewFileLines:1000,baselines,...overrides};
    put('catalogs/source-policy.lock.json', JSON.stringify(policy)); return policy;
  };
  const check = options => checkSourcePolicy({root,modules:[':low'],...options});
  put('low/src/main/kotlin/Small.kt', source(2));
  try { fn({root,put,lock,check}); } finally {
    assert.equal(dirname(root), tmpdir()); assert.ok(root.startsWith(join(tmpdir(), 'source-policy-')));
    rmSync(root, {recursive:true,force:true});
  }
}
test('line counts include unterminated final lines and handle CRLF', () => {
  assert.equal(countLines(''),0); assert.equal(countLines('a'),1); assert.equal(countLines('a\n'),1);
  assert.equal(countLines('a\r\nb\r\n'),2); assert.equal(countLines(source(1000)),1000);
});
test('missing lock fails explicitly instead of treating an absent baseline as zero', () => fixture(({check}) => {
  assert.ok(check().errors.some(error => error.includes('Missing source policy baseline')));
}));
test('new ordinary source is accepted at 1000 lines and rejected at 1001', () => fixture(({put,lock,check}) => {
  lock(); put('low/src/main/kotlin/New.kt',source(1000)); assert.deepEqual(check().errors,[]);
  put('low/src/main/kotlin/New.kt',source(1001)); assert.ok(check().errors.some(error=>error.includes('1001 lines exceeds 1000')));
}));
test('historical source requires owner/reason and cannot grow over its exact limit', () => fixture(({put,lock,check}) => {
  lock([entry()]); put(entry().path,source(1002)); assert.deepEqual(check().errors,[]);
  put(entry().path,source(1003)); assert.ok(check().errors.some(error=>error.includes('1003 lines exceeds 1002')));
}));
test('historical source can shrink without immediately rewriting the old limit', () => fixture(({put,lock,check}) => {
  lock([entry()]); put(entry().path,source(1001)); assert.deepEqual(check().errors,[]);
}));
test('missing baseline source cannot be counted as an empty successful file', () => fixture(({lock,check}) => {
  lock([entry()]); assert.ok(check().errors.some(error=>error.includes('Baseline file missing')));
}));
test('duplicate, ownerless and reasonless exceptions fail', () => fixture(({put,lock,check}) => {
  put(entry().path,source(1002)); lock([{...entry(),owner:'',reason:''},entry()]);
  assert.ok(check().errors.some(error=>error.includes('owner and reason')));
  assert.ok(check().errors.some(error=>error.includes('Duplicate')));
}));
test('cannot raise the normal 1000-line policy in lock data', () => fixture(({lock,check}) => {
  lock([],{maxNewFileLines:3000}); assert.ok(check().errors.length);
}));
test('explicit prior policy prevents raising a historical maximum', () => fixture(({put,lock,check}) => {
  put(entry().path,source(1001)); const previousPolicy=lock([entry(1001)]); lock([entry(1002)]);
  assert.ok(check({previousPolicy}).errors.some(error=>error.includes('limit increased')));
}));
test('explicit prior policy prevents adding exceptions for newly oversized files', () => fixture(({put,lock,check}) => {
  const previousPolicy=lock(); put(entry().path,source(1002)); lock([entry()]);
  assert.ok(check({previousPolicy}).errors.some(error=>error.includes('Cannot add a new historical exception')));
}));
test('explicit prior policy accepts downward baseline changes', () => fixture(({put,lock,check}) => {
  put(entry().path,source(1001)); const previousPolicy=lock([entry(1002)]); lock([entry(1001)]);
  assert.deepEqual(check({previousPolicy}).errors,[]);
}));
test('test fixtures, build and Gradle caches never enter production scans', () => fixture(({put,lock,check}) => {
  lock();
  for (const path of ['low/src/test/kotlin/Fake.kt','low/src/androidTest/Fake.kt','low/src/testFixtures/Fake.kt','low/src/fixtures/Fake.kt','low/src/main/build/Fake.kt','low/src/main/.gradle/Fake.kt']) put(path,source(2000)+'// 修复 123\n');
  const result=check(); assert.deepEqual(result.errors,[]); assert.equal(result.files,1);
}));
test('numbered-fix policy reports the actual production source line', () => fixture(({put,lock,check}) => {
  lock(); put('low/src/main/kotlin/Small.kt','package fixture\n// 修复 123\nclass Small');
  assert.ok(check().errors.some(error=>error.includes('Small.kt:2: forbidden numbered fix')));
}));
test('missing required module source fails instead of silently skipping a module', () => fixture(({lock,check}) => {
  lock(); assert.ok(check({modules:[':low',':missing']}).errors.some(error=>error.includes('required module: :missing')));
}));
test('out-of-module or path-traversal baselines fail', () => fixture(({lock,check}) => {
  lock([{...entry(),path:'../outside.kt'}]); assert.ok(check().errors.some(error=>error.includes('Invalid baseline path')));
}));
