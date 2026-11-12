package com.dsh.android.engine

import com.dsh.android.log.DshLog as Log
import java.io.File
import java.security.MessageDigest

/**
 * `--dump-config` 结果的**输入指纹缓存**：输入没变就不再花 0.6~2.3 秒跑一次 node。
 *
 * ## 为什么值得缓存
 *
 * [EngineManager.preflight] 每次启动都完整跑一次 `--dump-config`，为的是「profile 层
 * 被模型改坏 → 引擎 exit 1 → App 没有任何自救路径」这件事。真机冷启动实测这一段
 * 占 0.6~2.3 秒（进程启动 + 读整棵 engine/），而它验证的输入**在两次启动之间几乎
 * 从不变化**：用户不会每秒改一次 `cordis.patch.yml`。
 *
 * ## 为什么缓存「结果」而不是「跳过体检」
 *
 * 缓存的是 dump 的 `exitCode/stdout/stderr` 三个返回值，于是 [EngineManager.preflight]
 * 的判定逻辑（required 条目缺失、被 skip 的 bundle、隔离后复检）**一行都不用改**，
 * 照旧在这份输出上跑一遍。也就是说：命中缓存时得到的结论，与真跑一次完全同构；
 * 唯一的差别是没有再起一个 node 进程。
 *
 * ## 什么情况下不会命中（安全边界）
 *
 * 1. 输入指纹变了 —— 见 [fingerprint]：node 与引擎入口的 `size+mtime`、
 *    profile 目录下每个配置的名字/大小/时间/（小文件）内容摘要、引擎 `.stamp`、
 *    资产版本戳，任一变化即失效。
 * 2. 上次那次 dump **不干净**（`exitCode != 0`）—— 只缓存成功结果，
 *    坏配置永远走完整路径，绝不让缓存掩盖损坏。
 * 3. 缓存超过 [MAX_AGE_MS]（24 小时）—— 防系统升级、内核更新这类外部变化。
 * 4. 文件读不了/被截断/格式不认识 —— 一律当没命中（缓存坏了只损失一次偷懒机会）。
 *
 * ⚠️ 不用 `mtime` 单打一：`cp -p`、解包、时钟调整都能糊弄它。小文件（[INLINE_LIMIT] 内）
 *    额外算 SHA-1 内容摘要，profile 层的 yml/json 都在这个量级。
 */
internal class PreflightDumpCache(
    private val dshHome: File,
    private val engineDir: File,
    private val profileName: String,
    private val assetStamp: () -> String,
) {

    private val cacheFile = File(dshHome, ".preflight-dump")

    private var pending: EngineProbeRunner.DumpResult? = null
    private var pendingNode: File? = null
    private var pendingEntry: File? = null

    /** 命中就返回上次那份 dump 结果；否则 null（调用方照旧真跑）。 */
    fun cached(node: File, entry: File): EngineProbeRunner.DumpResult? = try {
        val lines = if (cacheFile.isFile) cacheFile.readLines() else emptyList()
        val hit = when {
            lines.size < 4 -> miss("无缓存")
            lines[0].trim() != assetStamp() -> miss("资产版本戳变了")
            System.currentTimeMillis() - (lines[2].trim().toLongOrNull() ?: 0L) !in 0 until MAX_AGE_MS -> miss("缓存过期")
            lines[1].trim() != fingerprint(node, entry) -> miss("体检输入变了")
            else -> null
        }
        if (hit != null) null else EngineProbeRunner.DumpResult(
            exitCode = lines[3].trim().toInt(),
            stdout = decode(lines.getOrElse(4) { "" }),
            stderr = decode(lines.getOrElse(5) { "" }),
        )
    } catch (t: Throwable) {
        miss("缓存读取失败: ${t.message}")
        null
    }

    private fun miss(why: String): String? {
        Log.i(TAG, "启动前体检缓存未命中（$why），照旧真跑 --dump-config")
        return why
    }

    /**
     * 只记住**干净**的成功结果：`exitCode != 0` 说明配置真有问题，下次必须真跑。
     *
     * ⚠️ 这里**不落盘**，只把载荷拿在手里 —— 指纹要等引擎起来之后再算，见 [markReady]。
     *    原因是引擎启动过程自己会改写 `profiles/…` 下的文件（补模板、写默认值），
     *    若在启动**前**算指纹，下次启动读到的永远是「被上次启动改过」的状态，
     *    缓存一次都不会命中（第一版就是这么写的，实测 100% miss）。
     */
    fun store(node: File, entry: File, result: EngineProbeRunner.DumpResult) {
        pending = if (result.exitCode == 0) result else null
        pendingNode = node
        pendingEntry = entry
        if (result.exitCode != 0) cacheFile.delete()
    }

    /** 引擎真的就绪了：用**此刻**的输入状态落盘，下次启动读到的就是同一份。 */
    fun markReady() {
        val result = pending ?: return
        val node = pendingNode ?: return
        val entry = pendingEntry ?: return
        try {
            dshHome.mkdirs()
            val temp = File(dshHome, ".preflight-dump.tmp")
            val payload = buildString {
                appendLine(assetStamp())
                appendLine(fingerprint(node, entry))
                appendLine(System.currentTimeMillis())
                appendLine(result.exitCode)
                appendLine(encode(result.stdout))
                appendLine(encode(result.stderr))
            }
            temp.writeText(payload)
            if (!temp.renameTo(cacheFile)) {
                cacheFile.writeText(payload)
                temp.delete()
            }
        } catch (t: Throwable) {
            // 缓存只是加速手段：写不进去就下次照旧真跑。
        }
    }

    /**
     * 体检输入指纹 —— 覆盖 [EngineManager.preflight] 真正读到的那几处。
     *
     * 插件**本体**不在里面：它们在 `engine/` 与 `$DSH_HOME/node_modules`，
     * 坏了会由引擎自己报 `1 entry did not activate`，那条路不靠这份缓存兜。
     */
    private fun fingerprint(node: File, entry: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun feed(text: String) {
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        feed("v1")
        feed(assetStamp())
        for (file in listOf(node, entry)) {
            feed(file.absolutePath)
            feed("${file.length()}@${file.lastModified()}")
        }
        feed(File(engineDir, ".stamp").let { if (it.isFile) it.readText().trim() else "no-stamp" })
        val profileDir = File(dshHome, "profiles/$profileName")
        feed(profileDir.absolutePath)
        val files = profileDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(CACHE_PREFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
        for (file in files) {
            feed(file.name)
            feed(file.length().toString())
            if (file.length() <= INLINE_LIMIT) {
                // 小文件只认**内容**：引擎运行期自己会重写这些文件（时间戳天天变、内容一字未改），
                // 按 mtime 记账会让缓存几乎永不命中；内容相同则 dump 结果必然相同。
                feed(runCatching { file.readBytes().contentHash() }.getOrDefault("unreadable"))
            } else {
                feed(file.lastModified().toString())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.contentHash(): String =
        MessageDigest.getInstance("SHA-1").run {
            update(this@contentHash)
            digest().joinToString("") { "%02x".format(it) }
        }

    /** 单行化存储：dump 输出是多行 JSON，基码后压成一行，杜绝行数解析歧义。 */
    private fun encode(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun decode(text: String): String =
        runCatching { String(java.util.Base64.getDecoder().decode(text.trim()), Charsets.UTF_8) }
            .getOrDefault("")

    private companion object {
        const val TAG = "EngineManager"

        /** 缓存最多信 24 小时。 */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** 内容摘要只对这么小的文件算；profile 层的配置都是几 KB。 */
        const val INLINE_LIMIT = 256L * 1024

        /** 缓存自己写的临时文件不算输入。 */
        const val CACHE_PREFIX = ".preflight"
    }
}
