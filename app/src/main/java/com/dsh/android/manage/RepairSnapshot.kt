package com.dsh.android.manage

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **修复前快照** —— 按计划声明的范围建立一致快照，失败可回滚。
 *
 * ## 设计说明 §6.4 / 验收矩阵第 5 条
 *
 * > 修复前建立**声明范围内的一致快照**，失败可在该范围恢复，
 * > **不产生循环重启**；外部和不可逆动作**不得报告为已自动撤销**。
 *
 * > §6.4：快照 manifest 记录**配置、启用组合、锁文件、实际包实体或
 * > 可恢复引用、链接目标与哈希**
 *
 * ## ⚠️ 为什么必须「按声明范围」而不是「整个目录」
 *
 * 计划里可能只改 `profiles/web/package.json` 一个文件。如果快照整个
 * `$DSH_HOME`（几百 MB），会有两个问题：
 *
 * | 问题 | 后果 |
 * |---|---|
 * | 耗时 | 修复前要等几十秒复制 |
 * | 占空间 | 快照本身可能让磁盘满 —— 反而制造故障 |
 *
 * 所以只快照**计划实际会碰到的路径**（由 [RepairControl.Step.target] 决定）。
 *
 * ## ⚠️ 第二个要点：不可逆动作**不能**靠快照撤销
 *
 * 文档：「外部和不可逆动作**不得报告为已自动撤销**」。
 *
 * 有些动作快照救不了：
 * - `DELETE_CONFIG`（删了配置，快照能恢复**文件**，但「用户已确认删除」这个决定不可撤销）
 * - 外部网络请求、Root 系统修改
 *
 * 所以 [RollbackResult] 必须能表达「恢复了什么」与「什么恢复不了」。
 */
object RepairSnapshot {

    private const val TAG = "DSH"

    /** 快照目录（在 `cache/` 下，系统可回收）。 */
    private const val SNAPSHOT_DIR = "repair-snapshots"

    /**
     * 快照中的一个条目。
     *
     * @param path 相对路径（相对于 `filesDir`）
     * @param sha256 内容哈希（**文档要求记录哈希**）
     * @param sizeBytes 字节数
     * @param isDir 是否目录
     * @param linkTarget 符号链接目标（`null` = 不是链接）
     * @param existed 快照时是否存在（`false` = 记录「当时不存在」，
     *   回滚时要删掉新建的）
     */
    data class Entry(
        val path: String,
        val sha256: String,
        val sizeBytes: Long,
        val isDir: Boolean,
        val linkTarget: String? = null,
        val existed: Boolean = true,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("path", path)
            put("sha256", sha256)
            put("size", sizeBytes)
            put("isDir", isDir)
            put("existed", existed)
            if (linkTarget != null) put("link", linkTarget)
        }

        companion object {
            fun from(o: JSONObject) = Entry(
                path = o.optString("path"),
                sha256 = o.optString("sha256"),
                sizeBytes = o.optLong("size", 0),
                isDir = o.optBoolean("isDir", false),
                linkTarget = o.optString("link").takeIf { it.isNotEmpty() },
                existed = o.optBoolean("existed", true),
            )
        }
    }

    /**
     * 一份快照。
     *
     * @param snapshotId 快照 ID
     * @param planId 对应的修复计划
     * @param createdAt 创建时刻
     * @param entries 条目清单
     * @param root 快照数据目录
     */
    data class Snapshot(
        val snapshotId: String,
        val planId: String,
        val createdAt: Long,
        val entries: List<Entry>,
        val root: File,
    ) {
        /** 快照占用的字节数。 */
        val sizeBytes: Long get() = entries.sumOf { if (it.isDir) 0L else it.sizeBytes }

        /** manifest 文件。 */
        fun manifestFile(): File = File(root, "manifest.json")

        fun saveManifest() {
            val o = JSONObject().apply {
                put("snapshotId", snapshotId)
                put("planId", planId)
                put("createdAt", createdAt)
                put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
            }
            manifestFile().writeText(o.toString())
        }

        companion object {
            fun load(root: File): Snapshot? = try {
                val o = JSONObject(File(root, "manifest.json").readText())
                val arr = o.optJSONArray("entries") ?: JSONArray()
                Snapshot(
                    snapshotId = o.optString("snapshotId"),
                    planId = o.optString("planId"),
                    createdAt = o.optLong("createdAt", 0),
                    entries = (0 until arr.length()).mapNotNull {
                        arr.optJSONObject(it)?.let { e -> Entry.from(e) }
                    },
                    root = root,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "读快照 manifest 失败: ${t.message}")
                null
            }
        }
    }

    /**
     * 回滚结果。
     *
     * ⚠️ **必须能表达「恢复不了什么」** —— 文档要求「不可逆动作不得报告为
     * 已自动撤销」。
     */
    data class RollbackResult(
        val restored: List<String>,
        val failed: List<Pair<String, String>>,
        /**
         * **无法通过快照撤销的动作**。
         *
         * 例如：`DELETE_CONFIG` 即使文件恢复了，「用户已确认删除」这个决定
         * 也无法撤销；外部网络请求更不可能。
         */
        val irreversible: List<String>,
    ) {
        val fullyRestored: Boolean get() = failed.isEmpty() && irreversible.isEmpty()

        /**
         * 一句话结论。
         *
         * ⚠️ **不假报已撤销**：有不可逆动作时必须点出来。
         */
        fun summary(): String = buildString {
            append("恢复 ${restored.size} 项")
            if (failed.isNotEmpty()) append("，${failed.size} 项失败")
            if (irreversible.isNotEmpty()) {
                append("；⚠️ ${irreversible.size} 项**无法撤销**：" +
                    irreversible.take(2).joinToString("、"))
            }
        }
    }

    /**
     * 建立快照。
     *
     * @param plan 修复计划（范围由它的 steps 决定）
     * @return `null` = 失败（**调用方必须中止修复** —— 没有快照就修是不安全的）
     */
    fun create(context: Context, plan: RepairControl.Plan): Snapshot? {
        val id = "snap-${System.currentTimeMillis()}"
        val root = File(File(context.cacheDir, SNAPSHOT_DIR), id)
        val dataRoot = File(root, "data")

        return try {
            dataRoot.mkdirs()
            val filesRoot = context.filesDir
            val entries = mutableListOf<Entry>()

            // ⚠️ 范围 = 计划里所有 step 的 target
            for (step in plan.steps) {
                val target = File(step.target)
                collectEntries(target, filesRoot, dataRoot, entries)
            }

            val snap = Snapshot(id, plan.planId, System.currentTimeMillis(), entries, root)
            snap.saveManifest()

            EventLog.record(
                "repair.snapshot.created",
                mapOf(
                    "snapshotId" to id,
                    "planId" to plan.planId,
                    "entries" to entries.size.toString(),
                    "bytes" to snap.sizeBytes.toString(),
                ),
            )
            Log.i(
                TAG,
                "修复快照已建立：$id（${entries.size} 项，${snap.sizeBytes / 1024} KB）",
            )
            snap
        } catch (t: Throwable) {
            Log.w(TAG, "建立快照失败: ${t.message}")
            EventLog.record("repair.snapshot.failed", mapOf("reason" to (t.message ?: "?")))
            runCatching { root.deleteRecursively() }
            null
        }
    }

    /**
     * 回滚到快照。
     *
     * ## ⚠️ 两条纪律
     *
     * 1. **只回滚快照范围内**的路径（文档：「可在**该范围**恢复」）
     * 2. **如实报告不可逆项**（文档：「不得报告为已自动撤销」）
     */
    fun rollback(context: Context, snap: Snapshot): RollbackResult {
        val restored = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val filesRoot = context.filesDir
        val dataRoot = File(snap.root, "data")

        for (e in snap.entries) {
            val target = File(filesRoot, e.path)
            try {
                if (!e.existed) {
                    // 快照时不存在 ⇒ 回滚时删掉（那是修复新建的）
                    if (target.exists() && target.deleteRecursively()) {
                        restored.add("${e.path}（删除新建）")
                    }
                    continue
                }
                val src = File(dataRoot, e.path)
                if (e.isDir) {
                    target.mkdirs()
                    restored.add("${e.path}/")
                    continue
                }
                if (!src.isFile) {
                    failed.add(e.path to "快照数据缺失")
                    continue
                }
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = true)
                // ⚠️ 回滚后校验哈希 —— 不然「恢复了」只是猜
                if (sha256Of(target) != e.sha256) {
                    failed.add(e.path to "恢复后哈希不符")
                } else {
                    restored.add(e.path)
                }
            } catch (t: Throwable) {
                failed.add(e.path to (t.message ?: "?"))
            }
        }

        // ⚠️ 不可逆项：从计划里推不出来（快照不知道 step 的 kind），
        //   所以这里只报「无法验证的部分」，具体由调用方补充。
        val result = RollbackResult(restored, failed, emptyList())

        EventLog.record(
            "repair.snapshot.rollback",
            mapOf(
                "snapshotId" to snap.snapshotId,
                "restored" to restored.size.toString(),
                "failed" to failed.size.toString(),
            ),
        )
        Log.i(TAG, "回滚完成：${result.summary()}")
        return result
    }

    /**
     * 清理旧快照（保留最近 [KEEP_SNAPSHOTS] 份）。
     *
     * ⚠️ 保留最近几份是为了「连续多次修复失败」时还能回到更早的状态。
     */
    fun cleanupOld(context: Context, keep: Int = KEEP_SNAPSHOTS) {
        val dir = File(context.cacheDir, SNAPSHOT_DIR)
        val all = dir.listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: return
        all.drop(keep).forEach { f ->
            Log.i(TAG, "清理旧快照：${f.name}")
            runCatching { f.deleteRecursively() }
        }
    }

    /** 保留的快照份数。 */
    private const val KEEP_SNAPSHOTS = 3

    /**
     * 删除一份快照（回滚成功后调用）。
     */
    fun discard(snap: Snapshot) {
        runCatching { snap.root.deleteRecursively() }
        EventLog.record("repair.snapshot.discard", mapOf("snapshotId" to snap.snapshotId))
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 收集一个目标（文件或目录）到快照。
     *
     * ⚠️ **`existed=false` 也要记录**：修复可能**新建**文件/目录，
     *   回滚时要把它们删掉。不记录的话「回滚」会留下垃圾。
     */
    private fun collectEntries(
        target: File,
        filesRoot: File,
        dataRoot: File,
        out: MutableList<Entry>,
    ) {
        val rel = relativeTo(target, filesRoot)

        if (!target.exists()) {
            // 记录「当时不存在」—— 回滚时删掉修复新建的
            out.add(
                Entry(
                    path = rel, sha256 = "", sizeBytes = 0,
                    isDir = false, existed = false,
                ),
            )
            return
        }

        // 符号链接：记录目标而不是复制内容
        //
        // ⚠️ 不跟随链接（文档：「禁止盲目追随链接卷入任意用户文件」）
        if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
            val link = runCatching {
                java.nio.file.Files.readSymbolicLink(target.toPath()).toString()
            }.getOrNull()
            out.add(
                Entry(
                    path = rel, sha256 = "", sizeBytes = 0,
                    isDir = false, linkTarget = link,
                ),
            )
            return
        }

        if (target.isDirectory) {
            out.add(Entry(rel + "/", "", 0, isDir = true))
            target.listFiles()?.forEach { child ->
                collectEntries(child, filesRoot, dataRoot, out)
            }
            return
        }

        if (target.isFile) {
            val dst = File(dataRoot, rel)
            dst.parentFile?.mkdirs()
            target.copyTo(dst, overwrite = true)
            out.add(
                Entry(
                    path = rel,
                    sha256 = sha256Of(target),
                    sizeBytes = target.length(),
                    isDir = false,
                ),
            )
        }
    }

    /** 相对 `filesDir` 的路径；不在其下时用绝对路径（回滚时会被跳过）。 */
    private fun relativeTo(f: File, root: File): String = try {
        val p = f.canonicalPath
        val r = root.canonicalPath
        if (p.startsWith(r + File.separator)) p.substring(r.length + 1) else f.absolutePath
    } catch (t: Throwable) {
        f.absolutePath
    }

    private fun sha256Of(f: File): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        ""
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：按计划声明范围快照、记录内容哈希/符号链接/「当时不存在」、
     *          回滚后校验哈希、如实报告失败项、旧快照轮换
     * ❌ 未做：
     *   - **「不可逆动作」的自动识别**（`RollbackResult.irreversible` 当前
     *     恒为空 —— 要填它需要知道 step 的 kind，而快照不持有计划）
     *   - 「冻结写者后建立一致性点」（文档要求 —— 需要停引擎，属更高层协调）
     *   - 「包实体/锁文件」的快照（当前只快照文件与目录，不解析依赖图）
     *   - 跨设备/跨版本的快照兼容性
     *
     * ⚠️ 一个重要的诚实说明：本对象**不保证**「修复一定可撤销」。
     *   它只保证「快照范围内的文件内容能恢复」。
     *   外部动作（网络、Root 修改）与「用户的决定」本身无法撤销 ——
     *   那正是文档要求「不得报告为已自动撤销」的原因。
     */
}
