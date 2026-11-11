#!/usr/bin/env node
/** Read-only audit and source-bound L3 report preparation. No app mutation commands. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolveRepositoryRoot } from '../../tools/kotlin-test-runtime.mjs';
import { reference, sourceSnapshot, parseCertificates, parseBadging } from '../../tools/release/inspect.mjs';
import { DEVICE_SCENARIOS } from '../../tools/run-gates.mjs';

const ROOT = resolveRepositoryRoot(import.meta.url);
const PACKAGES = { system: 'com.dsh.android', gecko: 'com.dsh.webview' };
const json = value => JSON.stringify(value, null, 2) + '\n';
const outputRoot = path.join(ROOT, 'build/verification/device');

// Defense in depth for command errors. Raw app logs, profiles, session text, and UI dumps
// are never collected here; only allowlisted platform metadata is persisted.
export function redact(text) {
  return String(text)
    .replace(/\bBearer\s+[^\s"']+/gi, 'Bearer [REDACTED]')
    .replace(/([?&](?:token|key|secret|password|authorization|sessionId|email)=)[^\s&#"']*/gi, '$1[REDACTED]')
    .replace(/((?:access[_-]?token|refresh[_-]?token|token|secret|password|authorization|api[_-]?key)\s*[=:]\s*)["']?[^\s,"'}]+/gi, '$1[REDACTED]')
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, '[EMAIL]');
}
function run(command, args, { allowFailure = false, timeout = 60_000 } = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024 });
  if (!allowFailure && (result.error || result.status !== 0)) {
    // Do not print arbitrary command output; it can contain live URLs or user data.
    throw new Error(`Device command failed: ${path.basename(command)} ${args.slice(0, 3).join(' ')}; exit=${result.status}; ${redact(result.error?.message ?? '')}`);
  }
  return { status: result.status, stdout: result.stdout ?? '', stderr: result.stderr ?? '' };
}
function ignoredOutput(filename) {
  const resolved = path.resolve(filename);
  const relative = path.relative(outputRoot, resolved);
  assert.ok(relative && !relative.startsWith('..') && !path.isAbsolute(relative), 'Device evidence must be under ignored build/verification/device');
  assert.equal(run('git', ['check-ignore', '--quiet', '--', resolved], { allowFailure: true }).status, 0, 'Device evidence path must be gitignored');
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  return resolved;
}
function write(filename, value) {
  const target = ignoredOutput(filename);
  fs.writeFileSync(target, json(value), { mode: 0o600 });
  return reference(target);
}
export function parsePackageMetadata(text) {
  const capture = expression => text.match(expression)?.[1];
  return {
    versionCode: Number(capture(/^\s*versionCode=(\d+)/m)),
    versionName: capture(/^\s*versionName=([^\r\n]+)/m),
    minSdk: Number(capture(/versionCode=\d+ minSdk=(\d+)/)),
    targetSdk: Number(capture(/versionCode=\d+ minSdk=\d+ targetSdk=(\d+)/)),
    dataDir: capture(/^\s*dataDir=(\/data\/(?:user\/\d+|data)\/com\.dsh\.(?:android|webview))\s*$/m),
    debuggable: /^\s*(?:pkgFlags|flags)=\[[^\]\n]*\bDEBUGGABLE\b/m.test(text),
  };
}
export function auditDevice({ serial, adb = 'adb', apksigner, out }) {
  assert.ok(serial && apksigner && out, 'audit requires --serial, --apksigner, --out');
  const shell = (...args) => run(adb, ['-s', serial, 'shell', ...args]).stdout.trim();
  assert.equal(run(adb, ['-s', serial, 'get-state']).stdout.trim(), 'device');
  const device = {
    serial, model: shell('getprop', 'ro.product.model'),
    androidVersion: shell('getprop', 'ro.build.version.release'), sdk: Number(shell('getprop', 'ro.build.version.sdk')),
  };
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-device-cert-'));
  const packages = [];
  try {
    for (const [flavor, applicationId] of Object.entries(PACKAGES)) {
      const apkPaths = shell('pm', 'path', applicationId).split(/\r?\n/).filter(line => line.startsWith('package:')).map(line => line.slice(8));
      if (!apkPaths.length) { packages.push({ flavor, applicationId, installed: false }); continue; }
      const metadata = parsePackageMetadata(shell('dumpsys', 'package', applicationId));
      const basePath = apkPaths.find(filename => filename.endsWith('/base.apk')) ?? apkPaths[0];
      assert.match(basePath, /^\/data\/app\/[^\r\n]+\.apk$/);
      const localApk = path.join(temp, `${flavor}.apk`);
      run(adb, ['-s', serial, 'pull', basePath, localApk], { timeout: 120_000 });
      const signature = parseCertificates(run(apksigner, ['verify', '--print-certs', localApk]).stdout);
      const executable = run(adb, ['-s', serial, 'shell', 'run-as', applicationId, 'pwd'], { allowFailure: true });
      packages.push({ flavor, applicationId, installed: true, ...metadata, deviceApkPath: basePath,
        installedApkSha256: reference(localApk).sha256, certificateSha256: signature,
        privateDataReadableByRunAs: executable.status === 0 });
    }
  } finally {
    const resolved = fs.realpathSync(temp);
    assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('dsh-device-cert-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  }
  const report = { schemaVersion: 1, kind: 'dsh-device-readonly-audit', createdAt: new Date().toISOString(), device, packages,
    appOperations: [], note: 'Installed APKs are historical observations, not evidence of the current source. Certificate compatibility is pending new APK inputs.' };
  const evidence = write(out, report);
  return { report, evidence };
}

/**
 * Requires the lead's final build record: {source:{commit,treeDigest},apks:[{flavor,buildType,path,sha256}]}.
 * This only prepares a partial report. Audit, certificate compatibility and preparation do
 * not prove any runtime scenario. Missing platform/manual authorization stays not-run.
 */
export function prepareReport({ audit, sourceRecord, system, gecko, aapt, apksigner, out }) {
  assert.ok(audit && sourceRecord && system && gecko && aapt && apksigner && out,
    'prepare requires --audit, --source-record, --system, --gecko, --aapt, --apksigner, --out');
  const baseline = JSON.parse(fs.readFileSync(audit, 'utf8'));
  assert.equal(baseline.kind, 'dsh-device-readonly-audit');
  const record = JSON.parse(fs.readFileSync(sourceRecord, 'utf8'));
  const current = sourceSnapshot(ROOT);
  for (const key of ['commit', 'treeDigest']) assert.equal(record.source?.[key], current[key], `Build source ${key} differs from current checkout`);
  const apks = [];
  const compatibility = [];
  for (const [flavor, filename] of Object.entries({ system, gecko })) {
    const actual = { flavor, ...reference(filename) };
    const built = record.apks?.find(item => item.flavor === flavor);
    assert.ok(built && path.resolve(built.path) === actual.path && built.sha256 === actual.sha256, `No source-bound ${flavor} APK in build record`);
    assert.ok(['debug', 'release'].includes(built.buildType), `Explicit buildType is required for ${flavor} device evidence`);
    actual.buildType = built.buildType;
    const metadata = parseBadging(run(aapt, ['dump', 'badging', actual.path]).stdout);
    assert.equal(metadata.debuggable, built.buildType === 'debug', `${flavor} APK debuggable state does not match declared buildType`);
    assert.equal(metadata.applicationId, PACKAGES[flavor]);
    assert.equal(metadata.targetSdk, 28);
    const certificate = parseCertificates(run(apksigner, ['verify', '--print-certs', actual.path]).stdout);
    const installed = baseline.packages.find(item => item.flavor === flavor);
    const compatible = !installed?.installed || certificate === installed.certificateSha256;
    const versionCompatible = !installed?.installed || metadata.versionCode >= installed.versionCode;
    compatibility.push({ flavor, certificateCompatible: compatible, versionCompatible, dataPreservingUpgradeAllowed: compatible && versionCompatible });
    apks.push(actual);
  }
  const blockers = compatibility.filter(item => !item.dataPreservingUpgradeAllowed).map(item => `${item.flavor}: certificate or version is incompatible; do not install`);
  const report = { schemaVersion: 1, kind: 'dsh-device-evidence', createdAt: new Date().toISOString(), source: record.source,
    device: baseline.device, apks, compatibility, status: 'partial',
    checks: DEVICE_SCENARIOS.map(name => ({ name, level: 'L3', status: 'not-run', tests: 0, skipped: 0, command: '', evidence: [],
      reason: blockers.length ? blockers.join('; ') : 'Only read-only audit and source/certificate preflight completed; actual device scenario pending.' })) };
  return { report, evidence: write(out, report) };
}

function options(args) {
  const [mode, ...rest] = args;
  assert.ok(['audit', 'prepare'].includes(mode), 'Usage: reorganization-check.mjs audit|prepare --key value');
  const result = { mode };
  const allowed = new Set(['serial', 'adb', 'apksigner', 'out', 'audit', 'source-record', 'system', 'gecko', 'aapt']);
  for (let index = 0; index < rest.length; index += 2) {
    const key = rest[index]?.replace(/^--/, '');
    assert.ok(allowed.has(key) && rest[index + 1] && !rest[index + 1].startsWith('--'), `Invalid option ${rest[index]}`);
    result[key === 'source-record' ? 'sourceRecord' : key] = rest[index + 1];
  }
  return result;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const input = options(process.argv.slice(2));
    const result = input.mode === 'audit' ? auditDevice(input) : prepareReport(input);
    console.log(json({ kind: result.report.kind, device: result.report.device, packages: result.report.packages,
      status: result.report.status ?? 'audit-only', evidence: result.evidence, compatibility: result.report.compatibility }));
  } catch (error) { console.error(redact(error.message)); process.exitCode = 1; }
}
