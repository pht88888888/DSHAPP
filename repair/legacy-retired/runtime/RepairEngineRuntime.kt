package com.dsh.android.repair.assistant.runtime

import android.content.Context
import android.os.Build
import com.dsh.android.engine.EngineAssetInstaller
import com.dsh.android.engine.EngineCompatibilityInstaller
import com.dsh.android.engine.EngineRuntimeDependencies
import com.dsh.android.termux.TermuxBootstrap
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Independent runtime owner. Only neutral installers are reused; EngineManager and
 * the main app's profiles, credentials, environment, privileged runners are never used.
 * Child output is consumed privately and discarded (it may contain URL tokens/keys).
 */
internal class RepairEngineRuntime(private val context: Context) : Closeable {
    private val closed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    @Volatile private var process: Process? = null

    data class Ready(val url: String, val port: Int)

    fun start(target: String, mailbox: File, webViewMajor: Int, progress: (String) -> Unit): Ready {
        check(owner.compareAndSet(null, this)) { "已有修复引擎正在运行，请先关闭旧会话" }
        starting.set(true)
        try {
            return startOwned(target, mailbox, webViewMajor, progress)
        } finally {
            starting.set(false)
            if (process == null && !stopping.get()) owner.compareAndSet(this, null)
        }
    }

    private fun startOwned(target: String, mailbox: File, webViewMajor: Int, progress: (String) -> Unit): Ready {
        check(target in setOf("com.dsh.android", "com.dsh.webview"))
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "仅支持 arm64 设备" }
        check(!closed.get()) { "修复会话已关闭" }
        val root = File(context.filesDir, "repair-runtime").apply { mkdirs() }
        val engine = File(root, "engine")
        val targetRoot = File(root, "targets/$target").apply { mkdirs() }
        val home = File(targetRoot, "home").apply { mkdirs() }
        val dshHome = File(targetRoot, "dsh-home").apply { mkdirs() }
        val workspace = File(targetRoot, "workspace").apply { mkdirs() }
        val boot = TermuxBootstrap(context)
        progress("正在准备独立 Node 环境；首次启动需要解包，请稍候…")
        runBlocking {
            // Order matters: Node requires libraries/aliases installed by bootstrap first.
            boot.install()
            check(boot.installNodeRuntime()) { "Node 运行环境准备失败" }
        }
        boot.ensureNodeRuntimePatched()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val stamp = "${packageInfo.lastUpdateTime}-repair-runtime-v1"
        runBlocking {
            check(EngineAssetInstaller(context.assets, root, File(context.applicationInfo.sourceDir), engine)
                .ensureExtracted(stamp, { progress("正在准备独立 DSH 引擎 $it%") }, {})) {
                "空间不足，无法解包独立引擎"
            }
        }
        check(!closed.get())
        progress("正在准备官方 Web 界面和修复专用预设…")
        val dependencies = EngineRuntimeDependencies(context, engine, boot.prefixDir, boot.nodeBinary)
        dependencies.ensureNativeAddon()
        dependencies.ensureSystemAddon { patchFlock(engine) }
        dependencies.ensureSharp()
        val compatibility = EngineCompatibilityInstaller(context.assets, root, engine, false)
        compatibility.ensureWebViewCompat(webViewMajor)
        compatibility.ensureWebCompat()
        val fetchGuard = checkNotNull(compatibility.ensureFetchGuard()) { "网络兼容模块准备失败" }
        val linkCompat = checkNotNull(compatibility.ensureLinkCompat()) { "会话持久化模块准备失败" }
        check(File(engine, "node_modules/node-addon-require-builtin-android-arm64/prebuilt/android-arm64-napi-v9.node").isFile)
        check(File(engine, "node_modules/@deepseek-ai/node-addon-system-android-arm64/bin/system.node").isFile)
        val profile = File(targetRoot, "repair-agent").apply { mkdirs() }
        copyAssetTree("repair-agent", profile)
        check(File(profile, "package.json").isFile && File(profile, "cordis.patch.yml").isFile &&
            File(profile, "repair.patch.yml").isFile && File(profile, "tools/index.js").isFile)
        // Independent per-user model settings live only in this private DSH_HOME.
        // The repair-specific final overlay is APK-owned and never writable from Web settings.
        File(dshHome, "cordis.patch.yml").writeText("[]\n")
        val launcher = File(root, "repair-launch.mjs").apply { writeText(LAUNCHER) }
        val enginePackage = File(engine, "node_modules/@deepseek-ai/dsh/package.json")
        val prefix = boot.prefixDir.absolutePath
        val builder = ProcessBuilder(boot.nodeBinary.absolutePath, launcher.absolutePath)
            .directory(workspace).redirectErrorStream(true)
        builder.environment().apply {
            // Explicit allowlist; do not inherit root/shizuku/model keys/proxy/Node preloads.
            clear()
            put("PATH", "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin")
            put("PREFIX", prefix)
            put("HOME", home.absolutePath)
            put("DSH_HOME", dshHome.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("LANG", "C.UTF-8")
            put("ANDROID_ROOT", "/system")
            put("ANDROID_DATA", "/data")
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("CURL_CA_BUNDLE", "$prefix/etc/tls/cert.pem")
            put("NODE_COMPILE_CACHE", File(context.cacheDir, "repair-node-cache").absolutePath)
            put("NODE_OPTIONS", "--max-old-space-size=512 --import ${fetchGuard.absolutePath}")
            put("LD_PRELOAD", linkCompat.absolutePath)
            put("DSH_TELEMETRY_DISABLED", "1")
            put("DSH_REPAIR_MAILBOX", mailbox.absolutePath)
            put("DSH_REPAIR_ASSETS", profile.absolutePath)
            put("DSH_REPAIR_PROFILE", profile.absolutePath)
            put("DSH_REPAIR_TOOLS_MODULE", File(profile, "tools/index.js").absolutePath)
            put("DSH_REPAIR_ENGINE_PACKAGE", enginePackage.absolutePath)
        }
        val ready = CompletableFuture<Ready>()
        synchronized(this) {
            check(!closed.get())
            process = builder.start()
        }
        val child = checkNotNull(process)
        Thread({
            try {
                child.inputStream.bufferedReader().use { reader ->
                    // Bound each output line; do not save, Log, render or include raw child output in exceptions.
                    val line = StringBuilder()
                    var overflow = false
                    while (true) {
                        val next = reader.read()
                        if (next == -1) break
                        if (next == '\n'.code) {
                            if (!overflow) RepairRuntimePolicy.readyUrl(line.toString().trimEnd('\r'))?.let { url ->
                                ready.complete(Ready(url, URI(url).port))
                            }
                            line.setLength(0)
                            overflow = false
                        } else if (line.length < 8192) line.append(next.toChar()) else overflow = true
                    }
                }
                ready.completeExceptionally(IllegalStateException("独立修复引擎未能启动；原生诊断仍可使用"))
            } catch (_: Exception) {
                ready.completeExceptionally(IllegalStateException("独立修复引擎连接已关闭"))
            }
        }, "repair-engine-output").apply { isDaemon = true; start() }
        return try {
            ready.get(180, TimeUnit.SECONDS).also { check(!closed.get()) }
        } catch (_: Exception) {
            close()
            throw IllegalStateException("独立修复引擎启动失败或超时；请返回原生诊断页")
        }
    }

    override fun close() {
        closed.set(true)
        val child = synchronized(this) {
            process?.also { stopping.set(true); process = null }
        } ?: return
        child.destroy()
        // Keep ownership until Node actually exits. New sessions cannot overwrite live libraries.
        Thread({
            try {
                if (!child.waitFor(6, TimeUnit.SECONDS)) {
                    child.destroyForcibly()
                    child.waitFor(2, TimeUnit.SECONDS)
                }
                runCatching { child.inputStream.close() }
            } finally {
                owner.compareAndSet(this, null)
            }
        }, "repair-engine-stop").apply { isDaemon = true; start() }
    }

    private fun copyAssetTree(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            // Only this single profile patch is user-editable, through the restricted model
            // settings ConfigEditor. APK-owned hardening/tool code always refreshes.
            if (path == "repair-agent/cordis.patch.yml" && destination.isFile) return
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            destination.mkdirs()
            children.forEach { name ->
                check(name != "." && name != ".." && !name.contains('/'))
                copyAssetTree("$path/$name", File(destination, name))
            }
        }
    }

    private fun patchFlock(engine: File) {
        val file = File(engine, "node_modules/@deepseek-ai/node-addon-system/lib/flock.js")
        val before = "if (platform !== 'linux' && platform !== 'darwin') {"
        val after = "if (platform !== 'linux' && platform !== 'darwin' && platform !== 'android') {"
        val text = file.readText()
        check(before in text || after in text) { "当前引擎不支持 Android 会话锁" }
        if (before in text) file.writeText(text.replace(before, after))
    }

    companion object {
        private val owner = AtomicReference<RepairEngineRuntime?>(null)
        // Uses the installed official composition/runProfile API, not a replacement server/UI.
        internal val LAUNCHER = """
            import { createRequire } from 'node:module';
            import { pathToFileURL } from 'node:url';
            const anchor = process.env.DSH_REPAIR_ENGINE_PACKAGE;
            const require = createRequire(anchor);
            const load = name => import(pathToFileURL(require.resolve(name)).href);
            const { loadProfileDirectory } = await load('@deepseek-ai/dsh-app-boot');
            const { createLaunchEnvironmentSnapshot } = await load('@deepseek-ai/dsh-launch-environment');
            const { runProfile } = await load('@deepseek-ai/dsh/profile-boot');
            const profile = loadProfileDirectory('dsh', process.env.DSH_REPAIR_PROFILE, anchor, { userLayer: true });
            const environment = createLaunchEnvironmentSnapshot([{ source: 'process', values: process.env }]);
            await runProfile({ environment, profile: 'fixhelp', resolvedProfile: { profile, installAnchor: anchor },
              patchFiles: [process.env.DSH_REPAIR_ASSETS + '/repair.patch.yml'], args: ['--port', '0', '--no-open'] });
        """.trimIndent()
    }
}
