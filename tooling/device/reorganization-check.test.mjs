import test from 'node:test';
import assert from 'node:assert/strict';
import { redact, parsePackageMetadata } from './reorganization-check.mjs';

// Synthetic values only; no device, app logs, or credentials are read by these tests.
test('error redaction removes token, bearer and email values', () => {
  const input = 'https://localhost/?token=sample-secret&email=user@example.invalid Bearer example-bearer access_token=another-secret api-key=example-key';
  const actual = redact(input);
  for (const secret of ['sample-secret', 'user@example.invalid', 'example-bearer', 'another-secret', 'example-key']) {
    assert.ok(!actual.includes(secret), `Synthetic secret survived redaction: ${secret}`);
  }
});
test('installed package parser persists only explicit platform metadata', () => {
  const actual = parsePackageMetadata(`Packages:
  versionCode=114 minSdk=26 targetSdk=28
  versionName=0.4.4
  dataDir=/data/user/0/com.dsh.android
  flags=[ SYSTEM HAS_CODE DEBUGGABLE ]
  unrelated-user-content=must-not-survive
`);
  assert.deepEqual(actual, { versionCode: 114, versionName: '0.4.4', minSdk: 26, targetSdk: 28,
    dataDir: '/data/user/0/com.dsh.android', debuggable: true });
  assert.ok(!JSON.stringify(actual).includes('must-not-survive'));
});
test('package parser rejects unexpected data directory ownership', () => {
  assert.equal(parsePackageMetadata('dataDir=/data/user/0/another.package').dataDir, undefined);
});
