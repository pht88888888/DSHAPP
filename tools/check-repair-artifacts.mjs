#!/usr/bin/env node
import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseBadging, parseCertificates, reference, run, toolPaths } from './release/inspect.mjs';

// Read the actual compiled APK XML tree, not a source manifest or a stale merge output.
function elements(text, tag) {
  const lines = text.split('\n');
  const result = [];
  for (let i = 0; i < lines.length; i++) {
    const match = lines[i].match(/^(\s*)E: ([^ ]+)/);
    if (!match || match[2] !== tag) continue;
    const attrs = {};
    for (let j = i + 1; j < lines.length; j++) {
      if (/^\s*E:/.test(lines[j])) break;
      const attr = lines[j].match(/A: (?:android:)?(\w+)(?:\([^)]*\))?=(.*)$/);
      if (attr) attrs[attr[1]] = attr[2].match(/^"([^"]*)"/)?.[1] ?? attr[2];
    }
    result.push(attrs);
  }
  return result;
}

export function inspectRepairArtifacts({ root, sdk, buildTools, system, gecko, assistant, since = 0 }) {
  const tools = toolPaths({ sdk, buildTools });
  const paths = {
    system: system || join(root, 'app/build/outputs/apk/system/release/app-system-release.apk'),
    gecko: gecko || join(root, 'app/build/outputs/apk/gecko/release/app-gecko-release.apk'),
    assistant: assistant || join(root, 'repair/assistant/build/outputs/apk/release/assistant-release.apk'),
  };
  const products = Object.entries(paths).map(([kind, path]) => {
    assert.ok(statSync(path).mtimeMs >= since, `Missing/stale repair artifact: ${path}`);
    const info = parseBadging(run(tools.aapt, ['dump', 'badging', path]));
    const cert = parseCertificates(run(tools.apksigner, ['verify', '--verbose', '--print-certs', path]));
    const xml = run(tools.aapt, ['dump', 'xmltree', path, 'AndroidManifest.xml']);
    assert.equal(info.debuggable, false, `${kind}: release must not be debuggable`);
    const expected = { system: 'com.dsh.android', gecko: 'com.dsh.webview', assistant: 'com.dsh.fixhelp' }[kind];
    assert.equal(info.applicationId, expected);
    assert.equal(info.targetSdk, 28);
    if (kind === 'assistant') {
      const originalXml = run(tools.aapt, ['dump', 'xmltree', paths.system, 'AndroidManifest.xml']);
      const permissions = elements(xml, 'uses-permission').map(item => item.name);
      const originalPermissions = elements(originalXml, 'uses-permission').map(item => item.name);
      const normalize = name => name.replaceAll('com.dsh.android', 'com.dsh.fixhelp');
      for (const permission of originalPermissions) assert.ok(permissions.includes(normalize(permission)), `Original permission removed: ${permission}`);
      for (const permission of ['com.dsh.android.permission.REPAIR', 'com.dsh.webview.permission.REPAIR']) assert.ok(permissions.includes(permission));
      for (const tag of ['activity', 'service', 'provider', 'receiver']) {
        const actual = elements(xml, tag);
        for (const original of elements(originalXml, tag)) {
          const found = actual.find(item => item.name === original.name);
          assert.ok(found, `Original ${tag} removed: ${original.name}`);
          for (const key of ['exported', 'process', 'permission', 'authorities']) {
            if (original[key] != null) assert.equal(found[key], key === 'authorities' || key === 'permission' ? normalize(original[key]) : original[key], `${tag} ${key} differs: ${original.name}`);
          }
        }
      }
      const session = elements(xml, 'service').find(item => item.name.endsWith('.RepairSessionService'));
      assert.equal(session?.exported, '(type 0x12)0x0');
      const activity = elements(xml, 'activity').find(item => item.name === 'com.dsh.android.ui.MainActivity');
      assert.ok(activity && activity.exported === '(type 0x12)0xffffffff');
      assert.ok(!elements(xml, 'activity').some(item => /Repair(?:Agent|Assistant)Activity$/.test(item.name)));
      assert.ok(xml.includes('android.intent.category.LAUNCHER'));
      assert.equal(elements(xml, 'application')[0].allowBackup, elements(originalXml, 'application')[0].allowBackup);
      // Compare actual ZIP bytes, not source staging: repair is strictly additive.
      // dexopt profiles are generated against each APK's distinct dex checksums.
      const check = `import sys,zipfile,hashlib,json\na,b=map(zipfile.ZipFile,sys.argv[1:])\nnames=[n for n in a.namelist() if n.startswith(('assets/','lib/')) and not n.startswith('assets/dexopt/') and not n.endswith('/')]\nfor n in names:\n assert n in b.namelist(), 'Original APK entry missing: '+n\n assert a.read(n)==b.read(n), 'Original APK entry changed: '+n\nprint(json.dumps({'originalEntriesPreserved':len(names)}))`;
      const preservation = JSON.parse(run('python3', ['-c', check, paths.system, path]));
      assert.ok(preservation.originalEntriesPreserved > 20);
    } else {
      const permission = `${expected}.permission.REPAIR`;
      assert.equal(elements(xml, 'permission').find(item => item.name === permission)?.protectionLevel, '(type 0x11)0x2');
      const provider = elements(xml, 'provider').find(item => item.name === 'com.dsh.android.repair.RepairProvider');
      assert.ok(provider, `${kind}: repair provider missing`);
      assert.equal(provider.authorities, `${expected}.repair`);
      assert.equal(provider.permission, permission);
      assert.equal(provider.process, ':repair');
      assert.equal(provider.exported, '(type 0x12)0xffffffff');
      assert.equal(provider.grantUriPermissions, '(type 0x12)0x0');
      const activity = elements(xml, 'activity').find(item => item.name === 'com.dsh.android.repair.RepairAuthorizationActivity');
      assert.ok(activity, `${kind}: authorization activity missing`);
      assert.equal(activity.permission, permission);
      assert.equal(activity.process, ':repair');
      assert.equal(activity.exported, '(type 0x12)0xffffffff');
      const approval = elements(xml, 'activity').find(item => item.name === 'com.dsh.android.repair.RepairChangeApprovalActivity');
      assert.ok(approval, `${kind}: executable approval activity missing`);
      assert.equal(approval.permission, permission);
      assert.equal(approval.process, ':repair');
      assert.equal(approval.exported, '(type 0x12)0xffffffff');
      const taskService = elements(xml, 'service').find(item => item.name === 'com.dsh.android.repair.RepairTaskService');
      assert.equal(taskService?.exported, '(type 0x12)0x0');
      assert.equal(taskService?.process, ':repair');
      const validationService = elements(xml, 'service').find(item => item.name === 'com.dsh.android.repair.RepairValidationService');
      assert.equal(validationService?.exported, '(type 0x12)0x0');
      assert.ok(!validationService?.process || validationService.process === expected);
    }
    return { kind, ...reference(path), ...info, certificateSha256: cert };
  });
  assert.equal(new Set(products.map(item => item.certificateSha256)).size, 1, 'All three APKs must use the same signing certificate');
  for (const flavor of ['System', 'Gecko', '']) {
    const path = flavor ? join(root, `app/build/reports/lint-results-${flavor.toLowerCase()}Debug.xml`)
      : join(root, 'repair/assistant/build/reports/lint-results-debug.xml');
    assert.ok(statSync(path).mtimeMs >= since, `Missing/stale lint report: ${path}`);
    assert.ok(!/severity="(?:Error|Fatal)"/.test(readFileSync(path, 'utf8')), `Lint errors in ${path}`);
  }
  return products;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  console.log(JSON.stringify(inspectRepairArtifacts({ root, sdk: process.env.ANDROID_HOME }), null, 2));
}
