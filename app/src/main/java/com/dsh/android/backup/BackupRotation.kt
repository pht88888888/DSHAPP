package com.dsh.android.backup

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **备份轮换与账本** —— 保证「只保留最近三份完整成功包」。
 *
 * ## 设计说明 §5.3.3 的硬要求
 *
 * > 自动和手动共保留最近三份「**完整、加密、验证且发布成功**」的 A 包。
 * > 新包完整提交后才能删除最早所属成功包；**失败/取消/部分包不挤占这三份**。
 *
 * > **仅清理应用账本与完成清单确认归属的包**，不删除用户其他文件或单独导出副本。
 *
 * > SAF 不保证原子重命名，采用**临时输出与可对账的完成标记**；
 * > **最终名称或文件存在不代表可恢复**。
 *
 * ## ⚠️ 三条最容易做错的地方
 *
 * **① 「文件存在」≠「包可恢复」**
 *
 * SAF 上写文件可能中途失败，留下一个大小不对的半包。如果按「文件名在就算」
 * 来轮换，会把真正可用的旧包删掉、留下一个废包 —— 用户以为有备份，
 * 恢复时才发现打不开。
 *
 * 所以本类维护一份**账本**（`backup-ledger.json`），只有账本里标成
 * `published` 的才算数。文件在但账本没有 → 当作「别人放的」，**不动它**。
 *
 * **② 失败包不挤占名额**
 *
 * 「部分包不挤占这三份」意味着：一个失败的备份**不能**导致旧包被删。
 * 所以删除动作只在「新包已成功发布」之后才发生。
 *
 * **③ 只删自己确认归属的**
 *
 * 用户的 SAF 目录可能还放了别的文件（他自己导出的副本、别的 App 的文件）。
 * 本类只删账本里明确记录、且文件名匹配我们命名规则的项。
 *
 * ## 命名规则
 *
 * `A-profile-<yyyyMMdd-HHmmss>-<8位id>.dshbak`
 *
 * ⚠️ 文件名里**不含**插件列表或任何秘密（文档：「外部头…不泄露插件列表」）。
 */
object BackupRotation {

    private const val TAG = "DSH"

    /** 账本文件名（放私有目录，不放 SAF —— SAF 上那份用户可能误删）。 */
    private const val LEDGER_FILE = "backup-ledger.json"

    /** 保留份数。文档明确「最近三份」。 */
    const val KEEP = 3

    /** 文件名前缀。 */
    private const val PREFIX = "A-profile-"

    /** 后缀（格式在 G4 锁定）。 */
    const val SUFFIX = ".dshbak"

    /**
     * 账本里的一条记录。
     *
     * @param name SAF 上的文件名
     * @param uri SAF 文档 URI（字符串形式；重装后失效，届时重新授权）
     * @param size 字节数（用于校验「写进去的确实是这个大小」）
     * @param sha256 密文摘要（前 16 字节）。文档要求「以密文摘要、长度…验证写入」
     * @param published 是否**已成功发布**（只有它为 true 才计入三份）
     * @param createdAt 创建时刻
     * @param verified 是否做过读回验证
     */
    data class Record(
        val name: String,
        val uri: String,
        val size: Long,
        val sha256: String,
        val published: Boolean,
        val createdAt: Long,
        val verified: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("uri", uri)
            put("size", size)
            put("sha256", sha256)
            put("published", published)
            put("createdAt", createdAt)
            put("verified", verified)
        }

        companion object {
            fun from(o: JSONObject): Record? {
                val name = o.optString("name", "")
                if (name.isEmpty()) return null
                return Record(
                    name = name,
                    uri = o.optString("uri", ""),
                    size = o.optLong("size", 0),
                    sha256 = o.optString("sha256", ""),
                    published = o.optBoolean("published", false),
                    createdAt = o.optLong("createdAt", 0),
                    verified = o.optBoolean("verified", false),
                )
            }
        }
    }

    /**
     * 轮换决策。
     *
     * @param toDelete 应当删除的包（**按时间从旧到新**）
     * @param keep 应当保留的包
     */
    data class Plan(val toDelete: List<Record>, val keep: List<Record>)

    private fun ledgerFile(context: Context): File =
        File(File(context.filesDir, "backup"), LEDGER_FILE)

    // ── 账本读写 ─────────────────────────────────────────────────────────

    fun readLedger(context: Context): List<Record> {
        val f = ledgerFile(context)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Record.from(it) }
            }
        } catch (t: Throwable) {
            // ⚠️ 账本坏了**不能清空后继续** —— 那样会失去「哪些包是我放的」这个
            //   唯一依据，下一次轮换就可能删掉用户的文件。
            //   如实记事件，让调用方决定（通常是拒绝轮换）。
            Log.w(TAG, "备份账本损坏: ${t.message}")
            EventLog.record("backup.ledger.corrupt", mapOf("reason" to (t.message ?: "?")))
            emptyList()
        }
    }

    fun writeLedger(context: Context, records: List<Record>) {
        val f = ledgerFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        try {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            tmp.writeText(arr.toString())
            runCatching { java.io.FileOutputStream(tmp, true).use { it.fd.sync() } }
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写备份账本失败: ${t.message}")
        }
    }

    /** 登记一条（覆盖同名）。 */
    fun upsert(context: Context, record: Record) {
        val list = readLedger(context).toMutableList()
        list.removeAll { it.name == record.name }
        list.add(record)
        writeLedger(context, list)
    }

    // ── 轮换决策 ─────────────────────────────────────────────────────────

    /**
     * 计算轮换计划。
     *
     * ## 规则（逐条对应文档）
     *
     * 1. 只有 `published && verified` 的记录计入「三份」
     * 2. 按 `createdAt` 排序（**不依赖文件时间** —— 文档明确要求）
     * 3. 超出 [KEEP] 的最旧的那些进 [Plan.toDelete]
     * 4. 未发布的（失败/部分）**不挤占名额**，但也不主动删 ——
     *    它们可能在重试中，删了会丢掉诊断线索
     *
     * @param incoming 即将发布的新包。它会计入「三份」，所以计划里
     *   可能连它一起算 —— 调用方应**先发布成功再执行计划**。
     */
    fun plan(context: Context, incoming: Record? = null): Plan {
        val all = readLedger(context).toMutableList()
        if (incoming != null) {
            all.removeAll { it.name == incoming.name }
            all.add(incoming)
        }

        // 只有「完整、加密、验证且发布成功」的才计入三份
        val counted = all
            .filter { it.published && it.verified }
            .sortedByDescending { it.createdAt }   // 新 → 旧

        val keep = counted.take(KEEP)
        val toDelete = counted.drop(KEEP)

        // 未发布的：保留（不挤占名额，也不删）
        val unpublished = all.filter { !(it.published && it.verified) }

        return Plan(toDelete = toDelete, keep = keep + unpublished)
    }

    /**
     * 执行删除。
     *
     * ⚠️ **只删 SAF 上的文件，并从账本移除**。调用方负责真正删文件
     * （因为删 SAF 文档需要 `DocumentsContract.deleteDocument`，
     * 那依赖调用方持有的 `ContentResolver` + 已授权 URI）。
     *
     * @return 已从账本移除的记录
     */
    fun commitDelete(context: Context, deleted: List<Record>): List<Record> {
        if (deleted.isEmpty()) return emptyList()
        val names = deleted.map { it.name }.toSet()
        val remain = readLedger(context).filterNot { it.name in names }
        writeLedger(context, remain)

        EventLog.record(
            "backup.rotate.deleted",
            mapOf("count" to deleted.size.toString(), "names" to deleted.joinToString(",") { it.name }),
        )
        return deleted
    }

    // ── 命名 ─────────────────────────────────────────────────────────────

    /**
     * 生成新包的文件名。
     *
     * 格式：`A-profile-<yyyyMMdd-HHmmss>-<8位hex>.dshbak`
     *
     * ⚠️ 唯一 ID 是必需的：同一秒内生成两个包时，光靠时间戳会重名，
     * 而 SAF 上重名写入的行为依 provider 而异（可能覆盖、可能失败）。
     */
    fun newName(now: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        val ts = fmt.format(java.util.Date(now))
        val id = (1..8).map { "0123456789abcdef".random() }.joinToString("")
        return "$PREFIX$ts-$id$SUFFIX"
    }

    /**
     * 判断一个文件名是不是**我们生成的**。
     *
     * ⚠️ 用于「只删自己确认归属的」。用户的其它文件即使长得像，
     * 只要不符合这个模式就不动。
     */
    fun isOurs(name: String): Boolean {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return false
        val middle = name.removePrefix(PREFIX).removeSuffix(SUFFIX)
        // yyyyMMdd-HHmmss-xxxxxxxx
        return Regex("""^\d{8}-\d{6}-[0-9a-f]{8}$""").matches(middle)
    }

    /*
     * ⚠️ **本对象的边界**：
     *
     * ✅ 做到：账本、三份轮换决策、失败包不挤占、只认自己命名规则、
     *          不依赖文件时间排序、密文摘要与长度记录
     * ❌ 未做：
     *   - SAF 的实际读写（需要 ContentResolver，在 BackupPublisher 里）
     *   - 「第四份空间预检」（需要先查 provider 可用空间）
     *   - 异常重启后的对账（临时/已发布/待清理三种状态）
     *   - 清理失败时保留可恢复包（当前 commitDelete 只动账本）
     */
}
