#!/usr/bin/env node
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve, relative, isAbsolute } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ROOT, MODULES } from './check-module-boundaries.mjs';

export const POLICY_PATH = 'catalogs/source-policy.lock.json';
export function countLines(text) {
  if (!text.length) return 0;
  return text.split(/\r\n|\n|\r/).length - (/\r\n$|[\n\r]$/.test(text) ? 1 : 0);
}
function scan(directory) {
  if (!existsSync(directory)) return [];
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (['build', '.gradle', 'test', 'tests', 'androidTest', 'testFixtures', 'fixtures', 'fixture', 'node_modules'].includes(entry.name)) continue;
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) result.push(...scan(path));
    else if (entry.isFile() && entry.name.endsWith('.kt')) result.push(path);
  }
  return result;
}

/** Previous lock is supplied explicitly by the CI base revision, never rewritten by this tool. */
export function previousPolicyAt(root, baseRef) {
  if (!baseRef || /^0+$/.test(baseRef)) return null;
  if (!/^[a-f0-9]{40}$/.test(baseRef)) throw new Error('Source policy --base-ref must be an exact 40-character commit SHA');
  const run = args => {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    if (result.error || result.status !== 0) throw new Error(`Cannot inspect source policy base ${baseRef}: ${result.stderr || result.error}`);
    return result.stdout;
  };
  run(['rev-parse', '--verify', `${baseRef}^{commit}`]);
  if (!run(['ls-tree', '--name-only', baseRef, '--', POLICY_PATH]).trim()) return null;
  return JSON.parse(run(['show', `${baseRef}:${POLICY_PATH}`]));
}

export function checkSourcePolicy({ root = ROOT, policyPath = resolve(root, POLICY_PATH), modules = MODULES, previousPolicy = null } = {}) {
  const errors = [], files = [], baselines = new Map();
  if (!existsSync(policyPath)) return { schemaVersion: 1, files: 0, baselines: 0, errors: [`Missing source policy baseline: ${policyPath}`], skipped: 0 };
  let policy;
  try { policy = JSON.parse(readFileSync(policyPath, 'utf8')); }
  catch (error) { return { schemaVersion: 1, files: 0, baselines: 0, errors: [`Invalid source policy baseline: ${error.message}`], skipped: 0 }; }
  if (policy.schemaVersion !== 1 || policy.maxNewFileLines !== 1000 || !Array.isArray(policy.baselines)) return { schemaVersion: 1, files: 0, baselines: 0, errors: ['Source policy must be schemaVersion 1, maxNewFileLines 1000, and explicit baselines'], skipped: 0 };
  const previousValid = !previousPolicy || (previousPolicy.schemaVersion === 1 && previousPolicy.maxNewFileLines === 1000 && Array.isArray(previousPolicy.baselines));
  const previous = new Map((previousPolicy && previousValid ? previousPolicy.baselines : []).map(entry => [entry.path, entry]));
  if (!previousValid) errors.push('Invalid previous source policy; cannot verify non-increasing limits');
  for (const entry of policy.baselines) {
    if (!entry || typeof entry !== 'object') { errors.push('Invalid source baseline entry'); continue; }
    const path = typeof entry.path === 'string' ? entry.path : '';
    const absolute = resolve(root, path);
    if (!path || isAbsolute(path) || path.includes('\\') || relative(root, absolute).startsWith('..') || !path.endsWith('.kt')) errors.push(`Invalid baseline path: ${path}`);
    if (baselines.has(path)) errors.push(`Duplicate source policy baseline: ${path}`);
    if (!Number.isSafeInteger(entry.maxLines) || entry.maxLines <= 1000) errors.push(`Historical baseline must have maxLines > 1000: ${path}`);
    if (typeof entry.owner !== 'string' || !entry.owner.trim() || typeof entry.reason !== 'string' || !entry.reason.trim()) errors.push(`Baseline requires explicit owner and reason: ${path}`);
    if (previousPolicy) {
      const before = previous.get(path);
      if (!before) errors.push(`Cannot add a new historical exception after the initial policy: ${path}`);
      else if (entry.maxLines > before.maxLines) errors.push(`Historical source policy limit increased: ${path} ${before.maxLines} -> ${entry.maxLines}`);
    }
    baselines.set(path, entry);
  }
  for (const module of modules) {
    const directory = resolve(root, module.slice(1).replaceAll(':', '/'), 'src');
    const sources = scan(directory);
    if (!sources.length) errors.push(`No production Kotlin source for required module: ${module}`);
    for (const absolute of sources) {
      const path = relative(root, absolute).replaceAll('\\', '/');
      const text = readFileSync(absolute, 'utf8'), lines = countLines(text);
      const baseline = baselines.get(path), limit = baseline?.maxLines ?? 1000;
      files.push({ path, module, lines, limit, historical: Boolean(baseline) });
      if (lines > limit) errors.push(`${path}: ${lines} lines exceeds ${limit}${baseline ? ' historical baseline' : '; no historical exception for new/ordinary sources'}`);
      // Keep the existing numbered-fix ban: it applies to source text, not mutable git history.
      for (const match of text.matchAll(/修复\s+[0-9]+/g)) errors.push(`${path}:${text.slice(0, match.index).split(/\r\n|\n|\r/).length}: forbidden numbered fix comment (${match[0]})`);
    }
  }
  const observed = new Set(files.map(file => file.path));
  for (const [path] of baselines) if (!observed.has(path)) errors.push(`Baseline file missing or outside production module sources: ${path}`);
  return { schemaVersion: 1, maxNewFileLines: 1000, files: files.length, baselines: baselines.size, history: previousPolicy ? 'compared-to-explicit-base' : 'no-previous-policy-supplied', largeFiles: files.filter(file => file.lines > 1000), errors: [...new Set(errors)], skipped: 0 };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2), options = {};
    for (let index = 0; index < args.length; index++) {
      if (!['--root', '--base-ref'].includes(args[index]) || !args[index + 1]) throw new Error('Usage: node tools/check-source-policy.mjs [--root DIR] [--base-ref EXACT_COMMIT_SHA]');
      options[args[index]] = args[++index];
    }
    const root = resolve(options['--root'] || ROOT), base = options['--base-ref'] || process.env.DSH_SOURCE_POLICY_BASE_REF;
    const result = checkSourcePolicy({ root, previousPolicy: previousPolicyAt(root, base) });
    console.log(JSON.stringify(result, null, 2)); if (result.errors.length) process.exitCode = 1;
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
