package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File
import java.security.MessageDigest

/**
 * **诊断副本** —— 让 `--dump-config` 在副本上跑，不碰原环境。
 *
 * ## 为什么必须这样
 *
 * 设计说明 §6.2：
 *
 * > 启动体检复用目标版本自身的 `--dump-config`/profile 解析…但前轮评审发现
 * > 锁定版本会调用 `prepareProfile`，并可能**重写 profile 配置、规范化
 * > manifest 或清理链接**，因此**不得直接在原 A 上把它当只读命令运行**。
 * > 先确认停写并复制完整受测布局到诊断副本，在副本运行，
 * > **记录规范化差异并验证原 A 哈希/元数据未变**。
 *
 * 开发指导 §G3 任务 3 说得更直接：
 *
 * > 停写后复制 profile 及必要安装布局；`--dump-config` **只在诊断副本执行**。
 * > 比较原 A 的文件树/必要元数据不变，副本规范化不回写。
 *
 * ## ⚠️ 现有实现的缺陷（G0 盘点时发现）
 *
 * `EngineManager.runDumpConfig()` 的 `DSH_HOME` 直接指向 `dshHome`（原 A），
 * 在 L4966 / L5013 各跑一次。如果 `prepareProfile()` 真会重写 profile，
 * **每次启动的体检都在改动用户配置**。
 *
 * ## 复制什么、不复制什么
 *
 * | 内容 | 复制 | 为什么 |
 * |---|---|---|
 * | `profiles/<名字>/package.json` | ✅ | 引擎读它决定加载哪些 bundle |
 * | `profiles/<名字>/cordis.patch.yml` | ✅ | 用户配置层，体检的主要对象 |
 * | `profiles/<名字>/node_modules/` | ❌ | 几百 MB，且体检不执行插件代码 |
 * | `.credentials.yaml` | ❌ | 体检不需要凭据；复制它等于多一份密钥副本 |
 *
 * ⚠️ **不复制 node_modules 是有代价的**：`prepareProfile` 可能检查依赖是否
 * 存在，缺了会报「包找不到」。所以本类只把这类错误**如实标出来**，
 * 不让调用方把它当成「profile 坏了」—— 那会误触发重解包。
 */
object ManageDiagCopy {

    private const val TAG = "DSH"

    /** 诊断副本目录名（在 cache 下，系统可回收）。 */
    private const val DIAG_DIR = "diag-profile"

    /**
     * 原 A 的文件指纹 —— 用于验证「体检没改动原 A」。
     *
     * @param files 参与计算的文件（只取必要元数据，不读全文）
     */
    data class Fingerprint(val entries: List<String>) {
        /** 两份指纹是否一致。 */
        fun matches(other: Fingerprint): Boolean = entries == other.entries

        /** 差异描述（诊断用）。 */
        fun diff(other: Fingerprint): String {
            if (matches(other)) return "无变化"
            val a = entries.toSet()
            val b = other.entries.toSet()
            val added = b - a
            val removed = a - b
            return buildString {
                if (removed.isNotEmpty()) append("消失 ${removed.size} 项: ${removed.take(3)}")
                if (removed.isNotEmpty() && added.isNotEmpty()) append("；")
                if (added.isNotEmpty()) append("新增 ${added.size} 项: ${added.take(3)}")
            }
        }
    }

    /**
     * 需要纳入指纹的文件（相对 profile 目录）。
     *
     * ⚠️ **只取元数据不取全文**：`cordis.patch.yml` 可能几十 KB，
     * 每次体检读一遍全文没有必要；而「文件是否被改」看 size + mtime 就够。
     */
    private val WATCHED = listOf("package.json", "cordis.patch.yml")

    /**
     * 计算某个环境 profile 的指纹。
     *
     * @param env 环境；⚠️ 现在只支持 A（R 的 profile 也适用，但调用方目前只用 A）
     */
    fun fingerprint(context: Context, env: ManageEnv.Env, profile: String): Fingerprint {
        val dir = File(ManageEnv.home(context, env), "profiles/$profile")
        val entries = mutableListOf<String>()
        for (name in WATCHED) {
            val f = File(dir, name)
            entries.add(
                if (f.isFile) "$name:${f.length()}:${f.lastModified()}:${sha256Short(f)}"
                else "$name:missing",
            )
        }
        return Fingerprint(entries)
    }

    /**
     * 建诊断副本。
     *
     * @return 副本的 `DSH_HOME`（调用方把它传给 `--dump-config`）；
     *   `null` = 复制失败，调用方应**放弃体检**而不是回退到原 A。
     *
     * ⚠️ **失败时必须放弃**：回退到「在原 A 上跑」正是文档禁止的。
     * 宁可这次不体检（下次启动再试），也不要改动用户配置。
     */
    fun prepare(context: Context, env: ManageEnv.Env, profile: String): File? {
        val srcHome = ManageEnv.home(context, env)
        val srcProfile = File(srcHome, "profiles/$profile")
        if (!srcProfile.isDirectory) {
            Log.w(TAG, "诊断副本：源 profile 不存在 ${srcProfile.absolutePath}")
            return null
        }

        val diagHome = File(context.cacheDir, DIAG_DIR)
        return try {
            // 每次都重建：上一次的副本可能来自旧配置，留着会得到误导性的结论
            diagHome.deleteRecursively()
            val dstProfile = File(diagHome, "profiles/$profile")
            dstProfile.mkdirs()

            for (name in WATCHED) {
                val src = File(srcProfile, name)
                if (src.isFile) src.copyTo(File(dstProfile, name), overwrite = true)
            }

            // ⚠️ 不复制 node_modules（见 KDoc 的表格）。
            //   但**建一个空目录**：某些引擎版本会检查它存在性，
            //   缺了会报「目录不存在」而不是「包找不到」—— 前者会被误判成 profile 坏了。
            File(dstProfile, "node_modules").mkdirs()

            EventLog.record(
                "manage.diagCopy.prepared",
                mapOf("env" to env.id, "profile" to profile),
            )
            Log.i(TAG, "诊断副本已就绪: ${diagHome.absolutePath}")
            diagHome
        } catch (t: Throwable) {
            Log.w(TAG, "建诊断副本失败: ${t.message}")
            EventLog.record("manage.diagCopy.failed", mapOf("reason" to (t.message ?: "?")))
            null
        }
    }

    /**
     * 校验「原环境没被改动」。
     *
     * @return true = 一致（体检确实只读了）；false = **原环境被改了**，必须告警
     *
     * ⚠️ 不一致时**不能静默**：那说明 `--dump-config` 真的会写盘，
     * 而我们所有的「体检是只读的」假设都错了。这是必须让人看到的事实。
     */
    fun verifyUnchanged(
        context: Context,
        env: ManageEnv.Env,
        profile: String,
        before: Fingerprint,
    ): Boolean {
        val after = fingerprint(context, env, profile)
        if (before.matches(after)) {
            EventLog.record("manage.diagCopy.unchanged", mapOf("env" to env.id))
            return true
        }
        val detail = before.diff(after)
        Log.w(TAG, "⚠️ 体检改动了原环境（$detail）—— dump-config 不是只读的")
        EventLog.record(
            "manage.diagCopy.MUTATED",
            mapOf("env" to env.id, "profile" to profile, "diff" to detail),
        )
        return false
    }

    /** 清理诊断副本。 */
    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, DIAG_DIR).deleteRecursively() }
    }

    private fun sha256Short(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().take(6).joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        "?"
    }

    /*
     * ⚠️ **本对象只准备副本与校验，不跑 dump-config**。
     *
     * 跑进程的逻辑在 `EngineManager.runDumpConfig()` 里（它持有 bootstrap 的
     * node 路径）。本对象只回答两件事：
     *   ① 副本建在哪（prepare 的返回值）
     *   ② 原环境有没有被改动（verifyUnchanged）
     *
     * 未实现：
     * - 「停写确认」后再复制（当前复制时 A 可能在运行，见 G3 任务 2）
     * - 复制 node_modules 的依赖清单（当前只建空目录）
     * - 副本规范化差异的记录（当前只比对原环境，不记录副本被改了什么）
     */
}
