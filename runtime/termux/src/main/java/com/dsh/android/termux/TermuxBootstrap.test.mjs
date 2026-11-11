import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { mkdtempSync, writeFileSync, readdirSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { resolveKotlinTestRuntime } from '../../../../../../../../../tools/kotlin-test-runtime.mjs';

// Explicit compiler input; no Gradle, engine, Android assets, or device is needed.
// KOTLIN_COMPILER_LIB=<Gradle lib directory> node --test <this file>
const sourceDir = path.dirname(fileURLToPath(import.meta.url));
const runtime = resolveKotlinTestRuntime();
let temp;
let runtimeClasspath;
const run = (command, args) => {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 90_000 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
};

before(() => {
  const { stdlib } = runtime;
  const coroutines = runtime.jars.find(file => /kotlinx-coroutines-core-jvm-/.test(file));
  assert.ok(coroutines, 'Kotlin compiler inputs must include the existing kotlinx-coroutines-core-jvm jar');
  temp = mkdtempSync(path.join(tmpdir(), 'termux-transactions-'));
  const stubs = {
    'Context.kt': `package android.content
import android.content.res.AssetManager
import java.io.File
class Context(val filesDir: File, val assets: AssetManager) { val packageName = "com.dsh.android" }`,
    'AssetManager.kt': `package android.content.res
import java.io.File
class AssetManager(private val root: File) { fun open(name: String) = File(root, name).inputStream() }`,
    'Build.kt': `package android.os
object Build { val SUPPORTED_ABIS = arrayOf("arm64-v8a") }`,
    'Os.kt': `package android.system
import java.nio.file.Files
import java.nio.file.Paths
object Os { fun symlink(target: String, link: String) { Files.createSymbolicLink(Paths.get(link), Paths.get(target)) } }`,
    'Log.kt': `package com.dsh.android.log
object DshLog { fun i(tag: String, message: String) {}; fun w(tag: String, message: String) {} }`,
    'Runner.kt': runnerSource(),
  };
  for (const [name, content] of Object.entries(stubs)) writeFileSync(path.join(temp, name), content);
  const sources = readdirSync(sourceDir).filter(name => name.endsWith('.kt')).map(name => path.join(sourceDir, name));
  sources.push(path.join(sourceDir, 'util/AtomicTextFile.kt'), ...Object.keys(stubs).map(name => path.join(temp, name)));
  const output = path.join(temp, 'classes');
  runtimeClasspath = [output, stdlib, coroutines].join(path.delimiter);
  run(runtime.java, ['-cp', runtime.classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [stdlib, coroutines].join(path.delimiter), '-d', output, ...sources]);
});

after(() => {
  if (!temp) return;
  const resolved = realpathSync(temp);
  assert.equal(path.dirname(resolved), realpathSync(tmpdir()));
  assert.ok(path.basename(resolved).startsWith('termux-transactions-'));
  rmSync(resolved, { recursive: true, force: true });
});

const cases = [
  ['archive', 'bootstrap blocks traversal and restores executable/symlink entries'],
  ['paths', 'ELF rewrite is equal length, idempotent, and skips symlinks'],
  ['unequal', 'unequal ELF replacements preserve binary bytes'],
  ['node-install', 'Node installs links and rewrites before validating the marker'],
  ['node-cached', 'healthy cached Node does not reinstall'],
  ['node-repair', 'corrupt cached Node is reinstalled and repair semantics are preserved'],
  ['node-failed', 'failed Node validation never creates an installed marker'],
  ['node-missing', 'absent prefix does not open the Node asset'],
  ['mirrors', 'custom apt source survives mirror application and shell environment stays stable'],
  ['mirror-cache', 'fresh explicit probe cache does not execute a probe'],
  ['layout', 'prefix aliases preserve real files and dpkg layout points to the prefix'],
  ['apt', 'apt hooks retain equal-length runtime identity and keyring semantics'],
  ['bootstrap', 'full install retains markers, runtime identity, order-sensitive paths, and idempotence'],
  ['repair-path', 'existing-prefix repair preserves user apt source and never opens bootstrap assets'],
  ['identity', 'runtime filesDir wins over fallback and fallback identity is validated'],
  ['concurrent', 'two bootstrap instances serialize destructive prefix installation'],
];
for (const [mode, title] of cases) {
  test(title, () => assert.match(run(runtime.java, ['-cp', runtimeClasspath, 'com.dsh.android.termux.RunnerKt', mode]), /PASS/));
}

function runnerSource() { return String.raw`package com.dsh.android.termux

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

private const val OLD = "/data/data/com.termux/files"
private const val NEW = "/data/data/com.dsh.androie/"
private const val MARKER = ".node_runtime_26.4.0-2"
private val GOOD_NODE = "#!/bin/sh\nprintf 'v26.4.0\\n'\n".toByteArray()

private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}
private fun put(root: File, path: String, bytes: ByteArray): File = File(root, path).apply {
    parentFile.mkdirs()
    writeBytes(bytes)
}
private fun elf(text: String) = byteArrayOf(0x7f, 69, 76, 70) + text.toByteArray()
private fun executable(root: File, path: String, bytes: ByteArray): File = put(root, path, bytes).apply { setExecutable(true, false) }

fun main(args: Array<String>) {
    val root = Files.createTempDirectory("termux-case-").toFile().canonicalFile
    try {
        val data = File(root, "com.dsh.androie/files").apply { mkdirs() }
        val prefix = File(data, "termux/usr").apply { mkdirs() }
        val home = File(data, "termux/home").apply { mkdirs() }
        val assets = File(root, "assets").apply { mkdirs() }
        fun mirrors() = TermuxMirrors(data.path, prefix, home)
        fun bootstrap() = TermuxBootstrap(Context(data, AssetManager(assets)))
        fun bootstrapAsset() = put(assets, "bootstrap-aarch64.zip", zip(
            "bin/bash" to ("#!" + OLD + "/usr/bin/sh\n").toByteArray(),
            "lib/libfixture.so" to elf("x" + OLD + "/usr/lib\u0000"),
            "share/termux-keyring/test.gpg" to byteArrayOf(1, 2, 3)
        ))
        when (args.single()) {
            "archive" -> {
                val archive = zip("../outside" to byteArrayOf(1), "bin/sh" to byteArrayOf(2),
                    "SYMLINKS.txt" to (OLD + "/usr/bin/sh←bin/alias\n").toByteArray())
                TermuxArchiveInstaller(prefix) { ByteArrayInputStream(archive) }.extract("fixture")
                check(!File(prefix.parentFile, "outside").exists())
                check(File(prefix, "bin/sh").canExecute())
                check(Files.readSymbolicLink(File(prefix, "bin/alias").toPath()).toString() == File(prefix, "bin/sh").path)
                check(!File(prefix, "SYMLINKS.txt").exists())
            }
            "paths", "unequal" -> {
                val bytes = elf("head" + OLD + "/usr/lib\u0000tail" + OLD + "/home")
                val binary = executable(prefix, "bin/tool", bytes)
                val script = executable(prefix, "bin/script", ("#!" + OLD + "/usr/bin/sh\n").toByteArray())
                val outside = put(root, "outside/text", OLD.toByteArray())
                Files.createSymbolicLink(File(prefix, "file-link").toPath(), outside.toPath())
                Files.createSymbolicLink(File(prefix, "dir-link").toPath(), outside.parentFile.toPath())
                val replacement = if (args.single() == "unequal") "short" else NEW
                val rewriter = TermuxPathRewriter(data.path, replacement)
                rewriter.rewriteLocked(prefix)
                check(binary.length() == bytes.size.toLong())
                check(binary.canExecute() && script.canExecute())
                check(outside.readText() == OLD)
                check(script.readText().contains(data.path))
                if (replacement == "short") check(binary.readBytes().contentEquals(bytes))
                else check(binary.readBytes().contentEquals(elf("head" + NEW + "/usr/lib\u0000tail" + NEW + "/home")))
                val after = binary.readBytes()
                rewriter.rewriteLocked(prefix)
                check(after.contentEquals(binary.readBytes()))
                check(prefix.listFiles()!!.none { it.name.endsWith(".patched") })
            }
            "node-install", "node-cached", "node-repair", "node-failed", "node-missing" -> {
                var opened = 0
                var rewritten = 0
                val mode = args.single()
                val payload = if (mode == "node-failed") "#!/bin/sh\necho wrong\n".toByteArray() else GOOD_NODE
                val archive = zip("bin/node" to payload, "lib/libx.so.1" to byteArrayOf(1),
                    "LINKS.txt" to "lib/libx.so libx.so.1\nlib/broken.so missing.so\n".toByteArray(),
                    "../node-escape" to byteArrayOf(7))
                val runtime = TermuxNodeRuntime(prefix, { opened++; ByteArrayInputStream(archive) }, { rewritten++ })
                if (mode == "node-missing") {
                    check(prefix.canonicalPath.startsWith(root.path + File.separator))
                    prefix.deleteRecursively()
                    check(!runtime.installLocked() && !runtime.repairIfNeededLocked())
                    check(opened == 0)
                } else if (mode == "node-cached") {
                    executable(prefix, "bin/node", GOOD_NODE)
                    File(prefix, MARKER).writeText("unchanged")
                    check(runtime.installLocked())
                    check(!runtime.repairIfNeededLocked())
                    check(opened == 0 && rewritten == 0)
                    check(File(prefix, MARKER).readText() == "unchanged")
                } else {
                    if (mode == "node-repair") {
                        executable(prefix, "bin/node", "#!/bin/sh\nexit 2\n".toByteArray())
                        File(prefix, MARKER).writeText("old")
                        check(runtime.repairIfNeededLocked())
                    } else check(runtime.installLocked() == (mode != "node-failed"))
                    check(opened == 1 && rewritten == 1)
                    check(File(prefix, MARKER).exists() == (mode != "node-failed"))
                    check(!File(prefix.parentFile, "node-escape").exists())
                    check(Files.isSymbolicLink(File(prefix, "lib/libx.so").toPath()))
                    check(!Files.isSymbolicLink(File(prefix, "lib/broken.so").toPath()))
                    check(File(prefix, "bin").listFiles()!!.none { it.name.endsWith(".part") })
                }
            }
            "mirrors", "mirror-cache" -> {
                if (args.single() == "mirror-cache") {
                    val state = MirrorProbe.defaults().toMutableMap()
                    state[MirrorProbe.KEY_APT_WRITTEN] = state.getValue(MirrorProbe.ECO_APT)
                    put(data, MirrorProbe.STATE_FILE, MirrorProbe.encodeState(state).toByteArray())
                    val cached = mirrors().loadMirrors()
                    runBlocking { check(mirrors().probeMirrors() == cached) }
                    check(!File(prefix, "bin/" + MirrorProbe.PROBE_CMD).exists())
                } else {
                    val body = "deb https://user.example.invalid/repo stable main\n"
                    val sources = put(prefix, "etc/apt/sources.list", body.toByteArray())
                    mirrors().ensureMirrorsConfigured()
                    check(sources.readText() == body)
                    val env = mirrors().getShellEnv("/custom/bin")
                    check(env["PREFIX"] == prefix.path && env["HOME"] == home.path)
                    check(env["PATH"] == prefix.path + "/bin:" + prefix.path + "/bin/applets:/custom/bin")
                    check(env["LD_LIBRARY_PATH"] == prefix.path + "/lib")
                    check(env["NPM_CONFIG_REGISTRY"] != null || env["npm_config_registry"] != null)
                }
            }
            "layout" -> {
                val existing = put(data.parentFile, "usr", "keep".toByteArray())
                val layout = TermuxPrefixLayout(data.path, prefix.parentFile, prefix, home)
                layout.createPrefixAliases()
                layout.ensureDpkgDirs()
                layout.ensureAptInstdirRoot()
                check(existing.readText() == "keep")
                check(File(prefix, "etc/dpkg/dpkg.cfg").isFile)
                check(Files.readSymbolicLink(File(prefix.parentFile, "aptroot/data/data/com.termux").toPath()).toString() == data.parentFile.path)
            }
            "apt" -> {
                TermuxPrefixLayout(data.path, prefix.parentFile, prefix, home).ensureDpkgDirs()
                val config = TermuxAptConfig(prefix.parentFile, prefix, NEW)
                config.writeAptConfig()
                val dpkg = File(prefix, "etc/dpkg/dpkg.cfg").readText()
                check(dpkg.contains("force-script-chrootless"))
                check(dpkg.contains("post-invoke " + File(prefix, "etc/apt/hooks/boo-fix-paths.sh").path))
                val hook = File(prefix, "etc/apt/hooks/boo-fix-paths.sh")
                check(hook.canExecute() && hook.readText().contains(NEW))
                put(prefix, "share/termux-keyring/test.gpg", byteArrayOf(1))
                config.installGpgKeys(prefix)
                put(prefix, "share/termux-keyring/test.gpg", byteArrayOf(2))
                config.installGpgKeys(prefix)
                check(File(prefix, "etc/apt/trusted.gpg.d/test.gpg").readBytes().contentEquals(byteArrayOf(1)))
                check(runCatching { TermuxAptConfig(prefix.parentFile, prefix, "short").writeAptConfig() }.isFailure)
            }
            "bootstrap" -> {
                bootstrapAsset()
                val instance = bootstrap()
                runBlocking { instance.install() }
                check(instance.isReady)
                check(File(prefix, ".paths_rewritten_v7").isFile)
                check(File(prefix, "lib/libfixture.so").readText().contains(NEW))
                check(File(prefix, "bin/bash").readText().contains(data.path))
                check(File(prefix, "etc/apt/hooks/boo-fix-paths.sh").readText().contains(NEW))
                check(File(data.parentFile, "usr").canonicalFile == prefix.canonicalFile)
                check(File(prefix.parentFile, ".prefix.lock").isFile)
                val sentinel = put(prefix, "keep", byteArrayOf(3))
                runBlocking { bootstrap().install() }
                check(sentinel.isFile)
                check(instance.nodeBinary == File(prefix, "bin/node"))
            }
            "repair-path" -> {
                val source = put(prefix, "etc/apt/sources.list", "user-source".toByteArray())
                executable(prefix, "bin/tool", elf(OLD))
                bootstrap().ensurePathsRewritten()
                check(source.readText() == "user-source")
                check(File(prefix, "bin/tool").readText().contains(NEW))
                check(File(prefix, ".paths_rewritten_v7").isFile)
                check(!File(prefix, ".bootstrap_done").exists())
            }
            "identity" -> {
                executable(prefix, "bin/tool", elf(OLD))
                TermuxBootstrap(Context(data, AssetManager(assets)), "com.dsh.webview").ensurePathsRewritten()
                check(File(prefix, "bin/tool").readText().contains(NEW))
                val oddData = File(root, "short/files").apply { mkdirs() }
                val oddPrefix = File(oddData, "termux/usr").apply { mkdirs() }
                executable(oddPrefix, "bin/tool", elf(OLD))
                TermuxBootstrap(Context(oddData, AssetManager(assets)), "com.dsh.webview").ensurePathsRewritten()
                check(File(oddPrefix, "bin/tool").readText().contains("/data/data/com.dsh.webview/"))
                check(runCatching { TermuxBootstrap(Context(data, AssetManager(assets)), "short") }.exceptionOrNull() is IllegalArgumentException)
            }
            "concurrent" -> {
                bootstrapAsset()
                val ready = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val tasks = (1..2).map {
                        pool.submit { ready.await(); runBlocking { bootstrap().install() } }
                    }
                    ready.countDown()
                    tasks.forEach { it.get() }
                    check(bootstrap().isReady)
                    check(File(prefix, ".paths_rewritten_v7").isFile)
                    check(File(prefix, "lib/libfixture.so").readText().contains(NEW))
                } finally { pool.shutdownNow() }
            }
            else -> error("Unknown mode")
        }
        println("PASS " + args.single())
    } finally {
        check(root.parentFile.canonicalFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
        check(root.name.startsWith("termux-case-"))
        root.deleteRecursively()
    }
}
`; }
