import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, writeFileSync, rmSync, readdirSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

let toolingRoot = path.dirname(fileURLToPath(import.meta.url));
while (!existsSync(path.join(toolingRoot, 'tools/kotlin-test-runtime.mjs'))) {
  const parent = path.dirname(toolingRoot);
  assert.notEqual(parent, toolingRoot, 'Repository Kotlin test tooling is missing');
  toolingRoot = parent;
}
const { resolveKotlinTestRuntime, resolveRepositoryRoot } = await import(pathToFileURL(path.join(toolingRoot, 'tools/kotlin-test-runtime.mjs')));

// Compile real production controllers, dispatcher and Web bridge contracts. No Gradle or downloads.
test('production UI controllers, browser coordination and bridge dispatch', () => {
  const runtime = resolveKotlinTestRuntime();
  const checkout = resolveRepositoryRoot(import.meta.url);
  const root = fileURLToPath(new URL('../', import.meta.url));
  const platform = process.env.ANDROID_PLATFORM_JAR
    ?? path.join(process.env.ANDROID_SDK_ROOT ?? process.env.ANDROID_HOME ?? '', 'platforms/android-35/android.jar');
  assert.ok(existsSync(platform), 'Set ANDROID_PLATFORM_JAR or Android SDK path to the compileSdk 35 platform');
  const temp = mkdtempSync(path.join(tmpdir(), 'dsh-ui-controllers-'));
  const run = (command, args) => {
    const result = spawnSync(command, args, { encoding: 'utf8', timeout: 60_000 });
    assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
    return result.stdout;
  };
  try {
    // Compile the real immutable appearance model and frame-delay method; only Android
    // persistence is excluded from this JVM-only harness.
    const appearance = readFileSync(path.join(root, 'SystemBarAppearance.kt'), 'utf8');
    const model = appearance.slice(appearance.indexOf('data class SystemBarAppearance('), appearance.indexOf('    companion object {'));
    const constants = [...appearance.matchAll(/        const val ([A-Z_]+) = ([^\n]+)/g)]
      .map(([, name, value]) => `const val ${name} = ${value}`).join('\n');
    const appearanceFile = path.join(temp, 'SystemBarAppearance.kt');
    writeFileSync(appearanceFile, `package com.dsh.android.ui\n${constants}\n${model}}\n`);
    const harness = path.join(temp, 'UiControllersChecks.kt');
    writeFileSync(harness, readFileSync(new URL('UiControllersChecks.kt.txt', import.meta.url), 'utf8'));
    const output = path.join(temp, 'classes');
    const coroutinesVersion = /kotlinx-coroutines-android:([^"']+)/.exec(readFileSync(path.join(checkout, 'app/build.gradle.kts'), 'utf8'))?.[1];
    assert.ok(coroutinesVersion, 'App coroutines dependency version is required');
    const coroutinesCache = path.join(process.env.GRADLE_USER_HOME ?? path.join(homedir(), '.gradle'),
      'caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', coroutinesVersion);
    const coroutines = process.env.KOTLIN_COROUTINES_JAR ?? (existsSync(coroutinesCache)
      ? readdirSync(coroutinesCache).map(hash => path.join(coroutinesCache, hash, `kotlinx-coroutines-core-jvm-${coroutinesVersion}.jar`)).find(existsSync)
      : undefined);
    assert.ok(coroutines && existsSync(coroutines), 'Set KOTLIN_COROUTINES_JAR to the provisioned App coroutines version');
    const classpath = [runtime.stdlib, platform, coroutines].join(path.delimiter);
    const web = path.join(checkout, 'web/contract/src/main/java/com/dsh/android/web');
    run(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
      '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', output,
      ...['RuntimePermissionQueue', 'SessionDownloadService', 'SystemBarFlashSequence', 'WebRecoveryController',
        'ImeLayoutController', 'PageBackNavigation', 'BootOverlayPresenter', 'FirstFrameAwaiter',
        'BrowserPaneCoordinator', 'NativePageBridgeActions', 'NativePageBridgeDispatcher', 'BrowserActivityPill']
        .map(name => path.join(root, `${name}.kt`)),
      ...['PageBridge', 'BridgeProtocol'].map(name => path.join(web, `${name}.kt`)), appearanceFile, harness]);
    const result = run(runtime.java, ['--add-modules', 'jdk.httpserver', '-cp', [output, classpath].join(path.delimiter),
      'com.dsh.android.ui.UiControllersChecksKt']);
    assert.match(result, /passed 34 production behavior checks/);
    console.log(result.trim());
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
});
