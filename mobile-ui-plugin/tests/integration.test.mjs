import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';

// Deliberately opt-in: never discover a live app, engine, profile, or workspace.
const engineRoot = process.env.DSH_ENGINE_ROOT;
const options = { skip: engineRoot ? false : 'Set DSH_ENGINE_ROOT to an installed DSH engine root to run real composition integration tests' };

function loadEngine(t) {
  const root = path.resolve(engineRoot);
  assert.ok(statSync(root).isDirectory(), 'DSH_ENGINE_ROOT must be an existing directory');
  const requireEngine = createRequire(path.join(root, 'package.json'));
  for (const dependency of ['@deepseek-ai/dsh-app-boot', 'yaml']) {
    try {
      requireEngine.resolve(dependency);
    } catch (error) {
      if (error.code !== 'MODULE_NOT_FOUND') throw error;
      t.skip(`DSH_ENGINE_ROOT is missing integration dependency ${dependency}`);
      return null;
    }
  }
  // Resolution failures skip; broken modules and incompatible APIs must fail.
  const { composeEntries } = requireEngine('@deepseek-ai/dsh-app-boot');
  const yaml = requireEngine('yaml');
  assert.equal(typeof composeEntries, 'function');
  return { composeEntries, yaml };
}

// This is a virtual composition entry: no plugin is loaded or host modified.
const native = [{ insert: [{ id: 'dsh-android-mobile', name: '@example/native-mobile' }] }];

test('native late-inserted entry cannot own profile overrides (regression mechanism)', options, t => {
  const engine = loadEngine(t);
  if (!engine) return;
  const warnings = [];
  const rows = engine.composeEntries([
    [{ id: 'dsh-android-mobile', config: { layoutMode: 'mobile' } }],
    native
  ], warning => warnings.push(warning));
  assert.equal(rows[0].config, undefined);
  assert.ok(warnings.some(warning => warning.includes('not found')));
});

test('profile-owned preferences replay before native overlay without duplicate layout entry', options, t => {
  const engine = loadEngine(t);
  if (!engine) return;
  const bundle = engine.yaml.parse(readFileSync(new URL('../dsh-android-layout-preferences/cordis.patch.yml', import.meta.url), 'utf8'));
  for (const mode of ['auto', 'mobile', 'official']) {
    const warnings = [];
    const rows = engine.composeEntries([
      bundle,
      [{ id: 'dsh-android-layout-preferences', config: { layoutMode: mode } }],
      native
    ], warning => warnings.push(warning));
    assert.equal(rows.find(row => row.id === 'dsh-android-layout-preferences').config.layoutMode, mode);
    assert.equal(rows.filter(row => row.id === 'dsh-android-layout-preferences').length, 1);
    assert.equal(rows.filter(row => row.id === 'dsh-android-mobile').length, 1);
    assert.equal(warnings.length, 0);
  }
});
