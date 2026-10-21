import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const packageURL = new URL('../dsh-android-layout-preferences/package.json', import.meta.url);
const ownerURL = new URL('../dsh-android-layout-preferences/index.js', import.meta.url);

// Metadata needs no install. Never substitute a mock schema or resolve from a
// live DSH host: dependencies must resolve from the production owner package.
test('configuration owner declares its real production schema dependency and bundle', () => {
  const pkg = JSON.parse(readFileSync(packageURL, 'utf8'));
  assert.equal(pkg.name, '@local/dsh-android-layout-preferences');
  assert.equal(pkg.type, 'module');
  assert.equal(pkg.dependencies['@deepseek-ai/schemastery'], '3.18.5');
  assert.equal(pkg.dsh.bundle.patch, './cordis.patch.yml');
});

async function loadOwner(t) {
  // Missing source is a failure, not an unavailable dependency skip.
  readFileSync(ownerURL, 'utf8');
  const requireOwner = createRequire(ownerURL);
  try {
    requireOwner.resolve('@deepseek-ai/schemastery');
  } catch (error) {
    if (error.code !== 'MODULE_NOT_FOUND') throw error;
    t.skip('Production dependency @deepseek-ai/schemastery is not installed; install dependencies in dsh-android-layout-preferences to exercise its real schema');
    return null;
  }
  // Broken transitive dependencies and invalid production code must fail.
  return import(ownerURL.href);
}

test('actual configuration owner imports and validates optional live modes and history', async t => {
  const owner = await loadOwner(t);
  if (!owner) return;
  assert.equal(owner.name, 'dsh-android-layout-preferences');
  assert.deepEqual(owner.inject, ['settings']);
  const schema = owner.Config;
  assert.equal(schema({}).layoutMode.get(), undefined);
  assert.equal(schema({}).lastLayout.get(), undefined);
  for (const mode of ['auto', 'mobile', 'official']) {
    assert.equal(schema({ layoutMode: mode }).layoutMode.get(), mode);
  }
  assert.throws(() => schema({ layoutMode: 'invalid' }));
  for (const actual of ['mobile', 'official']) {
    assert.equal(schema({ lastLayout: actual }).lastLayout.get(), actual);
  }
  assert.throws(() => schema({ lastLayout: 'auto' }));
  assert.throws(() => schema({ lastLayout: 'invalid' }));
  assert.equal(schema.dict.layoutMode.meta.volatile, true);
  assert.equal(schema.dict.lastLayout.meta.volatile, true);
});

test('configuration owner configures only the official settings service through an effect', async t => {
  const owner = await loadOwner(t);
  if (!owner) return;
  const calls = [];
  const cleanup = () => {};
  const effects = [];
  owner.apply({
    effect(fn) { effects.push(fn()); },
    settings: { configure(options) { calls.push(options); return cleanup; } }
  });
  assert.deepEqual(calls, [{ auto: false }]);
  assert.deepEqual(effects, [cleanup]);
});
