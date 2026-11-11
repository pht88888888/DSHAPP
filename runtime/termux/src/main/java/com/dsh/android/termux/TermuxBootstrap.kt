package com.dsh.android.termux

import android.content.Context
import android.os.Build
import com.dsh.android.log.DshLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Termux 安装与修复的编排入口。进程级锁和跨进程文件锁始终由本类统一拥有。
 * 归档、Node、路径、布局、apt 与镜像组件只拥有各自事务，不反向持有引导器。
 */
class TermuxBootstrap(
    private val context: Context,
    fallbackApplicationId: String = context.packageName,
) {
    private val dataDir = context.filesDir.absolutePath
    private val rootDir get() = File(dataDir, "termux")
    val prefixDir get() = File(rootDir, "usr")
    val homeDir get() = File(rootDir, "home")
    private val marker get() = File(prefixDir, ".bootstrap_done")

    // 修复代际与 Node 标记独立；升级仍由原标记判断，不能因拆分改变安装判据。
    private val rewriteMarker get() = File(prefixDir, ".paths_rewritten_v7")

    // 优先从真实 filesDir 反推身份；app 可显式提供后备包名，避免依赖 library BuildConfig。
    private val elfRootReplacement: String = run {
        val fallback = "/data/data/$fallbackApplicationId/"
        require(fallback.length == OLD_TERMUX_ROOT.length) {
            "Termux fallback applicationId must contain 15 characters"
        }
        val real = runCatching { context.filesDir?.parentFile?.name }.getOrNull()
        if (real.isNullOrEmpty()) {
            fallback
        } else {
            val candidate = "/data/data/$real/"
            // 长度不对就退回：宁可回到「改造前那个已知会分叉的行为」，
            // 也不要用一个会破坏 ELF 的替换串。
            if (candidate.length == OLD_TERMUX_ROOT.length) candidate else fallback
        }
    }

    private val paths by lazy { TermuxPathRewriter(dataDir, elfRootReplacement) }
    private val layout by lazy { TermuxPrefixLayout(dataDir, rootDir, prefixDir, homeDir) }
    private val archive by lazy { TermuxArchiveInstaller(prefixDir) { context.assets.open(it) } }
    private val node by lazy {
        TermuxNodeRuntime(prefixDir, { context.assets.open(it) }) { paths.rewriteLocked(prefixDir) }
    }
    private val apt by lazy { TermuxAptConfig(rootDir, prefixDir, elfRootReplacement) }
    private val mirrorConfig by lazy { TermuxMirrors(dataDir, prefixDir, homeDir) }

    val isReady: Boolean get() = marker.exists()
    val nodeBinary: File get() = node.nodeBinary
    val mirrors: Map<String, String> get() = mirrorConfig.mirrors

    /** 文件锁位于 prefix 外，避免 bootstrap 重建目录时删除正在使用的锁文件。 */
    private inline fun <T> withPrefixFileLock(block: () -> T): T {
        val lockFile = File(rootDir, ".prefix.lock")
        lockFile.parentFile?.mkdirs()
        return RandomAccessFile(lockFile, "rw").use { raf ->
            val lock = raf.channel.lock()
            try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /** Install bootstrap from assets → prefix dir. Safe to call multiple times. */
    suspend fun install() {
        if (isReady) return
        Log.i("TERMUX", "install start arch=$termuxArch")
        withContext(Dispatchers.IO) {
            prefixLock.withLock {
                withPrefixFileLock {
                    // 获得文件锁后再次检查标记，避免删除另一实例刚装好的 prefix。
                    installLocked()
                }
            }
        }
    }

    private fun installLocked() {
        if (isReady) return
        Log.i(TAG, "Extracting Termux bootstrap from assets")
        rootDir.mkdirs()
        homeDir.mkdirs()
        prefixDir.deleteRecursively()
        prefixDir.mkdirs()
        archive.extract("bootstrap-${termuxArch}.zip")

        // 别名先于 ELF 重写；dpkg 假根先于 apt 配置。顺序与原安装流程一致。
        layout.createPrefixAliases()
        layout.ensureDpkgDirs()
        layout.ensureAptInstdirRoot()
        paths.rewriteLocked(prefixDir)
        apt.installGpgKeys(prefixDir)
        File(prefixDir, "tmp").mkdirs()
        File(prefixDir, "var/cache/apt/archives/partial").mkdirs()
        File(prefixDir, "var/lib/apt/lists/partial").mkdirs()
        mirrorConfig.writeSourcesList()
        apt.writeAptConfig()
        mirrorConfig.applyMirrorConfigs(loadMirrors())
        marker.writeText("ok")
        rewriteMarker.writeText("ok")
        Log.i(TAG, "Termux bootstrap installed ($prefixDir)")
    }

    fun ensureMirrorsConfigured() = mirrorConfig.ensureMirrorsConfigured()

    suspend fun installNodeRuntime(): Boolean = withContext(Dispatchers.IO) {
        prefixLock.withLock { withPrefixFileLock { node.installLocked() } }
    }

    /** 保持安全模式 API：是否尝试了修复，而非安装成功判据。 */
    fun repairNodeRuntimeIfNeeded(): Boolean = prefixLock.withLock {
        withPrefixFileLock { node.repairIfNeededLocked() }
    }

    fun ensureNodeRuntimePatched() {
        if (!nodeBinary.isFile) return
        try {
            // 走锁：本方法与 DshApplication 的 installNodeRuntime 是**两个不同实例**
            // 上的两条并发路径，都会原地重写同一个 47 MB 的 node 二进制。
            prefixLock.withLock { withPrefixFileLock { paths.rewriteLocked(prefixDir) } }
        } catch (e: Exception) {
            Log.w("TERMUX", "ensure node patched: ${e.message}")
        }
    }

    /**
     * Ensure shebangs and paths are rewritten for the current installation.
     * Safe to call every launch: fast on already-correct files (read + skip),
     * and fixes existing installations that upgraded from a broken APK
     * without re-extracting the 30 MB bootstrap zip.
     */
    fun ensurePathsRewritten() {
        if (prefixDir.exists() && !rewriteMarker.exists()) {
            Log.i("TERMUX", "rewriting Termux paths")
            prefixLock.withLock {
                withPrefixFileLock {
                    layout.createPrefixAliases()
                    layout.ensureDpkgDirs()
                    layout.ensureAptInstdirRoot()
                    paths.rewriteLocked(prefixDir)
                    // 修复已有安装的 apt 配置，但不改用户选择的 sources.list。
                    apt.writeAptConfig()
                    rewriteMarker.writeText("ok")
                }
            }
        }
    }

    fun getShellEnv(originalPath: String = "/system/bin"): Map<String, String> =
        mirrorConfig.getShellEnv(originalPath)

    fun loadMirrors(): Map<String, String> = mirrorConfig.loadMirrors()

    suspend fun probeMirrors(force: Boolean = false): Map<String, String> =
        mirrorConfig.probeMirrors(force)

    private val termuxArch: String get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    private companion object {
        const val TAG = "TermuxBootstrap"
        const val OLD_TERMUX_ROOT = "/data/data/com.termux/files"
        // Application 与 EngineManager 持有不同实例；实例锁不能保护同一 prefix。
        val prefixLock = ReentrantLock()
    }
}
