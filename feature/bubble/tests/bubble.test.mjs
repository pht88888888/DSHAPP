import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { resolveKotlinTestRuntime, resolveRepositoryRoot } from '../../../tools/kotlin-test-runtime.mjs';

const root = resolveRepositoryRoot(import.meta.url);
const fixtures = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures');
const expected = { transcript: 10, frames: 4, status: 4, motion: 4, balance: 3, content: 4, controller: 11, actions: 18, startup: 2 };
let runtime;
let temporary;

function source(packagePath, candidates) {
  for (const prefix of candidates) {
    const filename = path.join(root, prefix, 'src/main/java/com/dsh/android', packagePath);
    if (fs.existsSync(filename)) return filename;
  }
  throw new Error(`Missing real Kotlin source: ${packagePath}; searched ${candidates.join(', ')}`);
}

function execute(args) {
  const result = spawnSync(runtime.java, args, { encoding: 'utf8', timeout: 60_000, maxBuffer: 4 * 1024 * 1024 });
  assert.ifError(result.error);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

before(() => {
  runtime = resolveKotlinTestRuntime();
  temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-bubble-l0-'));
  const names = ['BubbleTranscriptRenderer', 'BubbleFramePlayer', 'BubbleStatusPresenter', 'BubblePhysics',
    'BubbleMotionController', 'BubbleBalancePresenter', 'BubbleContentAnimator', 'BubbleController',
    'BubbleActionController', 'BubbleHostPorts'];
  const sources = names.map(name => source(`bubble/${name}.kt`, ['feature/bubble', 'app']));
  sources.push(...['ui/ChatTranscript.kt', 'ui/BalanceSnapshot.kt', 'engine/EngineStatus.kt']
    .map(name => source(name, ['core/contracts', 'app'])));
  sources.push(...fs.readdirSync(fixtures).filter(name => name.endsWith('.kt')).sort().map(name => path.join(fixtures, name)));
  execute(['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-nowarn', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', runtime.stdlib,
    '-d', temporary, ...sources]);
  for (const name of names.filter(name => name !== 'BubbleHostPorts')) {
    assert.ok(fs.existsSync(path.join(temporary, 'com/dsh/android/bubble', `${name}.class`)), `${name} artifact missing`);
  }
});

after(() => {
  if (!temporary) return;
  const resolved = fs.realpathSync(temporary);
  assert.equal(resolved, path.resolve(temporary));
  assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
  assert.ok(path.basename(resolved).startsWith('dsh-bubble-l0-'));
  fs.rmSync(resolved, { recursive: true, force: true });
});

for (const [suite, count] of Object.entries(expected)) {
  test(`bubble L0 ${suite}: ${count} Kotlin behavior cases`, () => {
    const output = execute(['-cp', `${temporary}${path.delimiter}${runtime.stdlib}`, 'com.dsh.android.bubble.ChecksKt', suite]);
    assert.equal(output.split('\n').filter(line => line.startsWith('PASS ')).length, count, output);
    assert.ok(output.includes(`RESULT: ${count} checks passed; 0 skipped`), output);
    process.stdout.write(output);
  });
}
