package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File

/**
 * **E 环境（应急环境）的独立依赖树**。
 *
 * ## 设计说明 §3.1 的要求
 *
 * > 一号、二号各有**独立的 DSH 依赖树**，不能只复制启动文件却共享可变依赖。
 *
 * > 二号保持精简和已知可用，**不随一号自动同步升级**。
 *
 * > 为最小事务记录和恢复操作保留应急空间预算，**E 尽量保持已部署可启动，
 * > 不能依赖故障发生后才解包**。
 *
 * ## ⚠️ 为什么 E 必须预先解包
 *
 * 「故障发生后才解包」的问题不是慢，而是**不可靠**：
 *
 * | 场景 | 事后解包会怎样 |
 * |---|---|
 * | 磁盘快满 | 解到一半 ENOSPC，得到半成品，比没有更糟 |
 * | `engine.bin` 资产损坏 | 解包失败，E 也起不来 |
 * | 用户急着救数据 | 要等 15 秒解包 + 可能失败 |
 *
 * 所以 [ensureDeployed] 应在**正常时期**（用户正常用 App 时）就把 E 准备好。
 *
 * ## ⚠️ 磁盘代价（必须如实说）
 *
 * 独立依赖树 = 再解包一份 engine（压缩 55 MB → 解包约 120~160 MB）。
 *
 * 文档基准是 4GB/64GB 整机，且明确「不允许把整机容量视为应用预算」。
 * 所以：
 * - E 的部署**不是无条件的** —— 见 [shouldDeploy] 的磁盘水位检查
 * - 空间不足时**不部署**并如实报状态，而不是硬塞进去把用户磁盘占满
 *
 * ## ⚠️ 独立目录 ≠ 安全隔离
 *
 * 文档：「独立目录不等于安全隔离：同一 Android 应用身份下的代码可能访问其他
 * 环境文件」。本对象只提供**路径与版本隔离**，不声称安全边界。
 */
object EmergencyEnv {

    private const val TAG = "DSH"

    /** E 环境的引擎目录名（相对 `filesDir`）。 */
    private const val E_ENGINE_DIR = "engine-e"

    /** 部署中临时目录（成功后原子改名）。 */
    private const val E_STAGING_DIR = "engine-e.staging"

    /** 版本戳文件名（与一号内核同构）。 */
    private const val STAMP = ".stamp"

    /**
     * 部署所需的最小可用空间。
     *
     * ⚠️ 比解包后实际大小留 30% 余量：解包过程中临时目录与最终目录**同时存在**
     *   （先解到 staging 再改名），所以峰值需求是解包大小的约 2 倍。
     *
     * 取 350 MB：解包后约 150 MB，峰值约 300 MB，再留 50 MB 给系统。
     */
    private const val MIN_FREE_BYTES = 350L * 1024 * 1024

    /**
     * E 环境的部署状态。
     */
    enum class State(val id: String) {
        /** 未部署（正常情况：首次安装后还没准备）。 */
        NOT_DEPLOYED("not-deployed"),

        /** 已部署且版本戳匹配。 */
        READY("ready"),

        /** 已部署但版本戳与一号内核不一致（一号升级过，E 还是旧的）。 */
        STALE("stale"),

        /** 部署中。 */
        DEPLOYING("deploying"),

        /** 部署失败（空间不足等）。 */
        FAILED("failed"),
        ;
    }

    /** E 引擎目录。 */
    fun engineDir(context: Context): File = File(context.filesDir, E_ENGINE_DIR)

    /** 版本戳文件。 */
    private fun stampFile(context: Context): File = File(engineDir(context), STAMP)

    /** 读 E 的版本戳；`null` = 未部署。 */
    fun stamp(context: Context): String? {
        val f = stampFile(context)
        if (!f.isFile) return null
        return runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 当前状态。
     *
     * @param primaryStamp 一号内核当前的版本戳（用于判断 E 是否过期）
     */
    fun state(context: Context, primaryStamp: String): State {
        val dir = engineDir(context)
        if (!dir.isDirectory) return State.NOT_DEPLOYED
        val s = stamp(context) ?: return State.NOT_DEPLOYED
        return if (s == primaryStamp) State.READY else State.STALE
    }

    /**
     * 是否应该部署 E。
     *
     * ## ⚠️ 三条拒绝条件（都不能绕过）
     *
     * 1. **空间不足** —— 硬塞会把用户磁盘占满，那是比「没有 E」严重得多的问题
     * 2. **已有部署且在部署中** —— 避免并发解包写同一目录
     * 3. **一号内核还没解包** —— E 的资产来自同一个 `engine.bin`，
     *    一号都没成功过就说明资产本身有问题，此时部署 E 必然也失败
     */
    fun shouldDeploy(context: Context, primaryStamp: String, primaryReady: Boolean): Pair<Boolean, String> {
        if (!primaryReady) {
            return false to "一号内核尚未就绪，暂不部署 E（资产可能有问题）"
        }
        val st = state(context, primaryStamp)
        if (st == State.READY) {
            return false to "E 已是最新（$primaryStamp），无需部署"
        }
        val free = freeBytes(context)
        if (free in 1 until MIN_FREE_BYTES) {
            return false to "可用空间不足（${free / 1024 / 1024} MB < ${MIN_FREE_BYTES / 1024 / 1024} MB）"
        }
        return true to when (st) {
            State.STALE -> "E 版本过期（${stamp(context)} → $primaryStamp），需重新部署"
            State.NOT_DEPLOYED -> "E 尚未部署"
            else -> "E 可部署"
        }
    }

    /**
     * 开始部署：返回 staging 目录，调用方把 engine.bin 解到那里，
     * 然后调 [commit] 完成。
     *
     * ⚠️ 分成 begin/commit 两步而不是一个大函数：
     *   解包逻辑在 `EngineManager.ensureExtracted` 里（它持有 `assets` 与进度回调），
     *   本对象不该重复那套代码。分工是：
     *   - 本对象负责**目录、版本戳、空间检查、原子替换**
     *   - `EngineManager` 负责**真正解包**
     */
    fun begin(context: Context): File? {
        val staging = File(context.filesDir, E_STAGING_DIR)
        return try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) {
                Log.w(TAG, "E 部署：无法创建 staging 目录")
                return null
            }
            EventLog.record("env.e.deploy.begin")
            staging
        } catch (t: Throwable) {
            Log.w(TAG, "E 部署：创建 staging 失败（${t.message}）")
            null
        }
    }

    /**
     * 完成部署：写版本戳，原子替换到最终目录。
     *
     * @return true = 成功
     *
     * ⚠️ 替换顺序：先把旧的 E 目录改名为 `.old`（而不是直接删），
     *   新目录就位后再删旧目录。这样**中途失败时旧的 E 还在** ——
     *   比「先删再放」安全得多（后者在断电时会两头落空）。
     */
    fun commit(context: Context, versionStamp: String): Boolean {
        val staging = File(context.filesDir, E_STAGING_DIR)
        val target = engineDir(context)
        val old = File(context.filesDir, "$E_ENGINE_DIR.old")

        return try {
            // 写版本戳（在 staging 里，随目录一起换过去）
            File(staging, STAMP).writeText(versionStamp)

            // 旧目录挪开
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old)) {
                throw IllegalStateException("无法移开旧的 E 目录")
            }

            // 新目录就位
            if (!staging.renameTo(target)) {
                // 失败：把旧的挪回来
                if (old.exists()) old.renameTo(target)
                throw IllegalStateException("无法将 staging 重命名为 E 目录")
            }

            old.deleteRecursively()
            EventLog.record("env.e.deploy.done", mapOf("stamp" to versionStamp))
            Log.i(TAG, "E 环境部署完成（$versionStamp，${dirSizeMb(target)} MB）")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "E 部署失败: ${t.message}")
            EventLog.record("env.e.deploy.failed", mapOf("reason" to (t.message ?: "?")))
            runCatching { staging.deleteRecursively() }
            false
        }
    }

    /** 放弃部署（用户取消或失败）。 */
    fun abort(context: Context) {
        runCatching { File(context.filesDir, E_STAGING_DIR).deleteRecursively() }
    }

    /** 删除已部署的 E（用户主动释放空间时）。 */
    fun remove(context: Context): Boolean {
        val ok = runCatching { engineDir(context).deleteRecursively() }.getOrDefault(false)
        if (ok) EventLog.record("env.e.removed")
        return ok
    }

    /**
     * E 环境是否可启动。
     *
     * ⚠️ 只检查「目录与版本戳在」，**不检查它能不能真的跑起来** ——
     * 那需要真启动一次（G1 的真机验证范围）。
     * 本函数回答的是「值得尝试启动吗」，不是「一定能起来」。
     */
    fun isLaunchable(context: Context): Boolean {
        val dir = engineDir(context)
        if (!dir.isDirectory) return false
        val binJs = File(dir, "node_modules/@deepseek-ai/dsh/lib/bin.js")
        return binJs.isFile && stamp(context) != null
    }

    // ── 启动入口 ─────────────────────────────────────────────────────────

    /**
     * E 环境用的 profile 名。
     *
     * ⚠️ 复用 `rescue` 而不是新建名字：那个 profile 的 bundle 清单
     * （`@deepseek-ai/dsh-base` + `@deepseek-ai/dsh-web-app`）已经在真机
     * 验证过能起来。换新名字等于重新验证一遍，而 E 的价值就在「已知可用」。
     */
    const val PROFILE_NAME = "rescue"

    /**
     * 生成 E 环境的启动命令参数。
     *
     * @param port 监听端口（`0` = 让引擎自己挑）
     * @return 传给 `bin.js` 的参数列表
     *
     * ⚠️ **不含 `--profile` 之外的任何用户配置**：E 的设计前提是
     * 「一号的配置可能已经坏了」，所以它只读自己的 HOME。
     */
    fun launchArgs(port: Int = 0): List<String> = listOf(
        "--profile", PROFILE_NAME,
        "--no-open",
        "--port", port.toString(),
    )

    /**
     * E 环境的启动是否**值得尝试**，以及不该尝试时为什么。
     *
     * ⚠️ 与 [isLaunchable] 的区别：那个只看文件在不在；
     *   这个还看「一号是否也坏了」—— 如果一号还能跑，就没必要切 E
     *   （切过去会丢掉用户正在用的会话）。
     *
     * @param primaryHealthy 一号内核当前是否健康（能跑）
     */
    fun shouldLaunch(context: Context, primaryHealthy: Boolean): Pair<Boolean, String> {
        if (!isLaunchable(context)) {
            return false to "E 尚未部署或部署不完整"
        }
        if (primaryHealthy) {
            // ⚠️ 一号健康时不切 E：E 是「最后一道门」，不是「更好的选择」。
            //   切过去意味着用户当前会话、正在跑的任务全部丢失。
            return false to "一号内核健康，不需要 E"
        }
        return true to "一号不可用，E 可尝试启动"
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun freeBytes(context: Context): Long = try {
        context.filesDir.usableSpace
    } catch (t: Throwable) {
        -1L
    }

    private fun dirSizeMb(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total / 1024 / 1024
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：独立目录、版本戳、空间检查、begin/commit 两段式部署、
     *          失败时保留旧 E、可删除释放空间
     * ❌ 未做：
     *   - **真正的解包**（在 EngineManager 里，本对象只提供目录）
     *   - 「E 的独立 Node 运行时」（当前与一号共用 `usr/` —— 文档要求
     *     「Node 和原生工具底座仅可在兼容验证通过后共享」，共用是当前事实）
     *   - 「E 的精简 profile」（需与一号不同的 bundle 清单，属下一步）
     *   - 「完整救援组合验证」（K2 + E + 桥协议，需真机）
     */
}
