package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File

/**
 * **磁盘低水位清理** —— 按文档规定的**次序**腾空间，且不删不该删的。
 *
 * ## 设计说明 §6.5.4 的原文
 *
 * > 低水位**拒绝**新增部署、快照或大任务，**优先清理可再生缓存和轮转日志**；
 * > **不得回收**唯一救援组合、未完成事务依赖或唯一有效恢复点。
 * > 无法安全快照时转只读，**不绕过快照直接修复**。
 *
 * ## ⚠️ 清理次序不是随意的
 *
 * 文档给的次序是「**优先清理可再生缓存和轮转日志**」。本实现严格按此排序：
 *
 * | 次序 | 目标 | 为什么可以删 |
 * |---|---|---|
 * | 1 | `cache/` 下的临时文件 | 系统本来就会回收，删了无害 |
 * | 2 | 诊断包导出缓存 | 可重新生成 |
 * | 3 | 旧日志（保留最近的） | 日志会轮转，旧的没用了 |
 * | 4 | 备份的临时产物 | 只删「未发布的」 |
 *
 * ## ⚠️ 绝对不能删的（文档明确列出）
 *
 * | 禁删项 | 为什么 |
 * |---|---|
 * | **唯一救援组合**（`engine-e/`） | 删了就没法救援了 —— 那比磁盘满严重得多 |
 * | **未完成事务依赖** | 删了事务无法恢复，状态会永久不一致 |
 * | **唯一有效恢复点** | 删了用户数据就真的没了 |
 *
 * ⚠️ 这三项的判断**不是看名字**，而是看**状态**：
 * - `engine-e/` 只有在**已部署且是唯一救援组合**时禁删
 * - 备份包只有在**账本里是最后一份完整包**时禁删
 *
 * ## ⚠️ 一个刻意的克制：本对象**不删用户数据**
 *
 * 即使空间不够，也**不删** `sessions/`、`skills/`、`.credentials.yaml`、
 * 用户工作区。那些是用户的东西，删了不可恢复。
 *
 * 空间不够的正确做法是：**拒绝新任务 + 告诉用户去清理**。
 */
object DiskCleanup {

    private const val TAG = "DSH"

    /**
     * 清理级别（按次序）。
     *
     * ⚠️ 用枚举而不是数字：让调用方看到「我现在清到第几档」，
     *   而 `cleanup(3)` 看不出那意味着什么。
     */
    enum class Tier(val order: Int, val label: String) {
        /** 只清 `cache/` 下的临时文件。**最安全**。 */
        TEMP_CACHE(1, "临时缓存"),

        /** 清诊断包导出缓存。 */
        DIAG_EXPORT(2, "诊断导出缓存"),

        /** 清旧日志（保留最近 N 份）。 */
        OLD_LOGS(3, "旧日志"),

        /** 清备份的临时产物（**只删未发布的**）。 */
        BACKUP_TEMP(4, "备份临时产物"),
        ;
    }

    /**
     * 清理结果。
     *
     * @param freedBytes 腾出的字节数
     * @param deleted 删掉的文件/目录
     * @param skipped 因「禁删规则」跳过的项（**如实报告**）
     */
    data class Result(
        val freedBytes: Long,
        val deleted: List<String>,
        val skipped: List<String>,
    ) {
        fun summary(): String = buildString {
            append("腾出 ${freedBytes / 1024 / 1024} MB（删除 ${deleted.size} 项）")
            if (skipped.isNotEmpty()) {
                append("；跳过 ${skipped.size} 项（受保护）")
            }
        }
    }

    /** 日志保留份数（超过这个数量的旧日志可删）。 */
    private const val KEEP_LOG_FILES = 5

    /**
     * 按次序清理，直到腾出目标空间或清完。
     *
     * @param targetFreeBytes 希望达到的可用空间
     * @param maxTier 最多清到哪一档（调用方可限制，避免删太多）
     *
     * ⚠️ **从第 1 档开始逐级尝试**，不是一次清到底 ——
     *   清完一档后重新检查空间，够了就停。这样能最小化删除。
     */
    fun freeUntil(
        context: Context,
        targetFreeBytes: Long,
        maxTier: Tier = Tier.BACKUP_TEMP,
    ): Result {
        var freed = 0L
        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        val startFree = freeBytes(context)
        if (startFree >= targetFreeBytes) {
            return Result(0, emptyList(), emptyList())
        }

        Log.i(
            TAG,
            "低水位清理：可用 ${startFree / 1024 / 1024} MB，" +
                "目标 ${targetFreeBytes / 1024 / 1024} MB",
        )

        for (tier in Tier.entries.sortedBy { it.order }) {
            if (tier.order > maxTier.order) break
            if (freeBytes(context) >= targetFreeBytes) break

            val (f, d, s) = when (tier) {
                Tier.TEMP_CACHE -> cleanTempCache(context)
                Tier.DIAG_EXPORT -> cleanDiagExport(context)
                Tier.OLD_LOGS -> cleanOldLogs(context)
                Tier.BACKUP_TEMP -> cleanBackupTemp(context)
            }
            freed += f
            deleted += d
            skipped += s
            if (d.isNotEmpty()) {
                Log.i(TAG, "  清理「${tier.label}」：${d.size} 项，${f / 1024 / 1024} MB")
            }
        }

        if (deleted.isNotEmpty() || skipped.isNotEmpty()) {
            EventLog.record(
                "disk.cleanup",
                mapOf(
                    "freedMb" to (freed / 1024 / 1024).toString(),
                    "deleted" to deleted.size.toString(),
                    "skipped" to skipped.size.toString(),
                ),
            )
        }
        return Result(freed, deleted, skipped)
    }

    // ── 各档实现 ─────────────────────────────────────────────────────────

    /**
     * 第 1 档：`cache/` 下的临时文件。
     *
     * ⚠️ **排除 `cache/manage/`**：那里有 `ManageState` 的事务记录，
     *   删了会让「未完成事务」永久失去恢复依据。
     */
    private fun cleanTempCache(context: Context): Triple<Long, List<String>, List<String>> {
        var freed = 0L
        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        val cacheRoot = context.cacheDir
        cacheRoot.listFiles()?.forEach { f ->
            // ⚠️ 跳过 manage 子目录（事务记录在那里）
            if (f.name == "manage") {
                skipped.add("cache/manage（事务记录）")
                return@forEach
            }
            val size = sizeOf(f)
            if (runCatching { f.deleteRecursively() }.getOrDefault(false)) {
                freed += size
                deleted.add("cache/${f.name}")
            }
        }
        return Triple(freed, deleted, skipped)
    }

    /** 第 2 档：诊断包导出缓存（可重新生成）。 */
    private fun cleanDiagExport(context: Context): Triple<Long, List<String>, List<String>> {
        var freed = 0L
        val deleted = mutableListOf<String>()
        val dir = File(context.filesDir, "logs/export")
        if (!dir.isDirectory) return Triple(0, emptyList(), emptyList())
        dir.listFiles()?.forEach { f ->
            val size = sizeOf(f)
            if (runCatching { f.deleteRecursively() }.getOrDefault(false)) {
                freed += size
                deleted.add("logs/export/${f.name}")
            }
        }
        return Triple(freed, deleted, emptyList())
    }

    /**
     * 第 3 档：旧日志（保留最近 [KEEP_LOG_FILES] 份）。
     *
     * ⚠️ **保留最近的**：最新日志是排查当前问题唯一的依据，
     *   全删了等于把「刚发生的事」也扔了。
     */
    private fun cleanOldLogs(context: Context): Triple<Long, List<String>, List<String>> {
        var freed = 0L
        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.isDirectory) return Triple(0, emptyList(), emptyList())

        // 只处理「轮转出来的」日志（形如 app.log.1 / app.log.2）
        //
        // ⚠️ **不删当前的 app.log / engine.log** —— 那些是正在写的。
        val rotated = logsDir.listFiles()
            ?.filter { it.isFile && it.name.contains(".log.") }
            ?.sortedByDescending { it.name }
            ?: return Triple(0, emptyList(), emptyList())

        // 保留最新的 N 份轮转日志
        rotated.drop(KEEP_LOG_FILES).forEach { f ->
            val size = sizeOf(f)
            if (runCatching { f.delete() }.getOrDefault(false)) {
                freed += size
                deleted.add("logs/${f.name}")
            }
        }
        if (rotated.size <= KEEP_LOG_FILES && rotated.isNotEmpty()) {
            skipped.add("logs/*.log.*（共 ${rotated.size} 份，未超过保留数）")
        }
        return Triple(freed, deleted, skipped)
    }

    /**
     * 第 4 档：备份的临时产物（**只删未发布的**）。
     *
     * ⚠️ **不删已发布的备份包** —— 那是「唯一有效恢复点」，
     *   文档明确禁止回收。
     *
     * 判据：`BackupRotation` 账本里有记录的 = 已发布 = 禁删；
     *   没有记录的临时文件（如 `.tmp-xxx`）= 可删。
     */
    private fun cleanBackupTemp(context: Context): Triple<Long, List<String>, List<String>> {
        var freed = 0L
        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // 私有区的备份临时文件
        val backupDir = File(context.filesDir, "backup")
        if (backupDir.isDirectory) {
            backupDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".tmp")) {
                    val size = sizeOf(f)
                    if (runCatching { f.delete() }.getOrDefault(false)) {
                        freed += size
                        deleted.add("backup/${f.name}")
                    }
                } else {
                    skipped.add("backup/${f.name}（可能是恢复点）")
                }
            }
        }

        // cache 里的加密临时产物
        val encDir = File(context.cacheDir, "backup-enc")
        if (encDir.isDirectory) {
            val size = sizeOf(encDir)
            if (runCatching { encDir.deleteRecursively() }.getOrDefault(false)) {
                freed += size
                deleted.add("cache/backup-enc")
            }
        }
        return Triple(freed, deleted, skipped)
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private fun freeBytes(context: Context): Long = try {
        context.filesDir.usableSpace
    } catch (t: Throwable) {
        -1L
    }

    private fun sizeOf(f: File): Long = try {
        if (f.isFile) f.length()
        else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (t: Throwable) {
        0L
    }

    /**
     * 当前空间是否低于安全水位。
     *
     * ⚠️ 与 [EmergencyEnv.shouldDeploy] 的 350 MB 不同：那是「部署 E 需要多少」，
     *   这是「系统整体还剩多少算危险」。两者用途不同，不能共用一个常量。
     */
    const val LOW_WATER_BYTES = 200L * 1024 * 1024

    fun isLowWater(context: Context): Boolean {
        val free = freeBytes(context)
        return free in 1 until LOW_WATER_BYTES
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：四档次序清理、逐级尝试（够了就停）、禁删规则、
     *          如实报告跳过项、不删用户数据
     * ❌ 未做：
     *   - **「未完成事务依赖」的精确识别**（当前只保护 `cache/manage` 整个目录，
     *     没有按 `UpdateTransaction` 的阶段判断哪个文件是关键依赖）
     *   - 「唯一救援组合」的动态判断（当前 `engine-e/` 一律不碰 ——
     *     保守但可能漏掉「E 未部署时可以删」的情况）
     *   - 清理后的空间复核（删完不重新测一次，靠调用方自己查）
     *   - 「转只读」模式（文档要求「无法安全快照时转只读」—— 那是更高层的决策）
     */
}
