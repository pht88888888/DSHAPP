import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFile, mkdir, realpath, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

export const fixtureRoot = fileURLToPath(new URL('./market/', import.meta.url));
const manifestPath = resolve(fixtureRoot, 'manifest.json');
export const contractFiles = {
  manager: 'node_modules/@deepseek-ai/dsh-plugin-manager/lib/typert.remote-client.js',
  gateway: 'node_modules/@deepseek-ai/dsh-api-gateway/lib/client.node.js',
  remoteInfo: 'node_modules/@deepseek-ai/dsh-typert-protocol/lib/types/remote-info.js',
  protocol: 'node_modules/@deepseek-ai/dsh-typert-protocol/lib/index.js',
};
const packages = ['@deepseek-ai/dsh', '@deepseek-ai/dsh-plugin-manager', '@deepseek-ai/dsh-api-gateway', '@deepseek-ai/dsh-typert-protocol', 'zod'];
const metadataPaths = packages.map((name) => `node_modules/${name}/package.json`);
const licensePaths = packages.slice(0, -1).map((name) => `node_modules/${name}/LICENSE`);
const allPaths = [...Object.values(contractFiles), ...metadataPaths, ...licensePaths];
const digest = (data) => createHash('sha256').update(data).digest('hex');

function requiredPath(name) {
  const value = process.env[name];
  assert.ok(value && isAbsolute(value), `${name} must explicitly name an absolute path; no global installation or default engine archive is used`);
  return value;
}

export async function readRealInputs() {
  const enginePackage = requiredPath('DSH_ENGINE_PACKAGE');
  const archive = requiredPath('DSH_ENGINE_ARCHIVE');
  const installedRoot = dirname(await realpath(enginePackage));
  // Resolve only within the explicitly supplied installation: nested npm, a
  // flat node_modules tree, or pnpm's adjacent private/hoisted dependency roots.
  const adjacent = resolve(installedRoot, '../..');
  const dependencyRoots = [resolve(installedRoot, 'node_modules'), adjacent];
  const store = resolve(adjacent, '../..');
  if (basename(adjacent) === 'node_modules' && basename(store) === '.pnpm') dependencyRoots.push(resolve(store, 'node_modules'));
  const roots = new Map([['@deepseek-ai/dsh', installedRoot]]);
  for (const name of packages.slice(1)) {
    for (const root of dependencyRoots) {
      const dir = resolve(root, name);
      try {
        const pkg = JSON.parse(await readFile(resolve(dir, 'package.json'), 'utf8'));
        assert.equal(pkg.name, name);
        roots.set(name, dir);
        break;
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
      }
    }
    assert.ok(roots.has(name), `Missing installed ${name} next to explicit DSH_ENGINE_PACKAGE`);
  }
  const installed = Object.fromEntries(await Promise.all(allPaths.map(async (name) => {
    const [pkg, dir] = [...roots].find(([key]) => name.startsWith(`node_modules/${key}/`));
    return [name, await readFile(resolve(dir, name.slice(`node_modules/${pkg}/`.length)), 'utf8')];
  })));
  const shipped = JSON.parse(execFileSync('python3', ['-c', `
import json, sys, tarfile
with tarfile.open(sys.argv[1]) as archive:
 print(json.dumps({name: archive.extractfile(name).read().decode('utf-8') for name in json.loads(sys.argv[2])}))
`, archive, JSON.stringify(allPaths)], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 }));
  return { shipped, installed };
}

export async function readFixtures() {
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  assert.equal(manifest.schemaVersion, 1);
  const result = {};
  for (const [label, spec] of Object.entries(manifest.inputs)) {
    const sources = {};
    for (const [name, record] of Object.entries(spec.files)) {
      const data = await readFile(resolve(fixtureRoot, record.fixture), 'utf8');
      assert.equal(digest(data), record.sha256, `${label}: pinned official fixture hash mismatch: ${name}`);
      sources[name] = data;
    }
    result[label] = sources;
  }
  return { manifest, inputs: result };
}

export function toContract(sources) {
  return Object.fromEntries(Object.entries(contractFiles).map(([key, name]) => [key, sources[name]]));
}

export function assertPinned(real, fixture, manifest, label) {
  for (const [name, record] of Object.entries(manifest.inputs[label].files)) {
    assert.equal(digest(real[name]), record.sha256, `${label}: official input drift: ${name}; review versions and explicitly refresh contracts/engine/market (never auto-update during tests)`);
    assert.equal(real[name], fixture[name]);
  }
}

async function refresh() {
  assert.ok(process.argv.includes('--write'), 'Refreshing official fixtures requires --write plus both explicit engine inputs');
  const inputs = await readRealInputs();
  await mkdir(resolve(fixtureRoot, 'files'), { recursive: true });
  const manifest = { schemaVersion: 1, upstream: 'https://github.com/deepseek-ai/deepseek-harness', provenance: 'Unmodified files copied from explicitly supplied DSH engine archive and installed package; package metadata and upstream MIT notices are pinned with the source.', refresh: 'DSH_ENGINE_PACKAGE=/absolute/package.json DSH_ENGINE_ARCHIVE=/absolute/engine.bin node contracts/engine/market-fixtures.mjs --write', inputs: {} };
  for (const [label, sources] of Object.entries(inputs)) {
    const files = {};
    for (const [name, source] of Object.entries(sources)) {
      const sha256 = digest(source);
      const fixture = `files/${sha256}.${name.endsWith('LICENSE') ? 'LICENSE' : name.endsWith('.json') ? 'json' : 'js'}`;
      await writeFile(resolve(fixtureRoot, fixture), source);
      files[name] = { fixture, sha256, bytes: Buffer.byteLength(source) };
    }
    const versions = Object.fromEntries(packages.map((name) => [name, JSON.parse(sources[`node_modules/${name}/package.json`]).version]));
    manifest.inputs[label] = { versions, files };
  }
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log('Pinned unmodified shipped and installed official market contracts, package metadata, and MIT licenses. Review manifest/version/hash changes before accepting a refresh.');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await refresh();
