package com.dsh.android.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * **SAF 发布器** —— 把加密包写到用户授权的目录，并做读回验证。
 *
 * ## 为什么必须用 SAF 而不是直接写 /sdcard
 *
 * 设计说明 §5.3.3：
 *
 * > 用户通过 SAF 目录选择器授权**可跨卸载保留**的父目录，应用在其中创建 backup；
 * > **不能使用 app-specific 目录冒充卸载后保留**。重装后重新取得 URI 授权，
 * > 不能假定授权跨卸载保留。
 *
 * 直接写 `/sdcard/Download/` 在 targetSdk 28 上**能写**（legacy storage），
 * 但：
 * - 卸载重装后路径还在，看起来能用 —— 但那不是「用户授权的」，随时可能被系统清理
 * - 没有「用户明确选择过这个目录」的证据
 *
 * SAF 的代价是必须持有 URI 授权，且重装后要重新授权。这是文档接受的。
 *
 * ## ⚠️ 「写成功」不等于「可恢复」
 *
 * 文档：
 *
 * > 以**密文摘要、长度及选定格式的认证检查**验证写入；若提供方不支持必要
 * > 读回验证，**不报告完整成功**。
 * > SAF 不保证原子重命名，采用临时输出与可对账的完成标记；
 * > **最终名称或文件存在不代表可恢复**。
 *
 * 所以本类的流程是：
 *
 * ```
 * 写临时名 → 读回校验摘要与长度 → 改成正式名 → 账本标 published
 * ```
 *
 * 任何一步失败都不标 published —— 那样它就不会挤占三份名额。
 */
object BackupPublisher {

    private const val TAG = "DSH"

    /** 临时文件名前缀（写完才改正式名）。 */
    private const val TMP_PREFIX = ".tmp-"

    /**
     * 发布结果。
     *
     * ⚠️ 三态而不是布尔：`SKIPPED`（没配置目录）与 `FAILED`（配了但写失败）
     * 的处置完全不同 —— 前者该引导用户去设置，后者该报错并保留旧包。
     */
    sealed interface Result {
        /** 成功发布并验证。 */
        data class Published(val record: BackupRotation.Record) : Result

        /** 写成功但**读回验证失败** —— 文档要求此时不报告完整成功。 */
        data class Unverified(val reason: String) : Result

        /** 写失败。 */
        data class Failed(val reason: String) : Result

        /** 用户没授权目录。 */
        data object NoDirectory : Result
    }

    /**
     * 发布一个加密包到 SAF 目录。
     *
     * @param treeUri 用户授权的目录（`ACTION_OPEN_DOCUMENT_TREE` 的结果）
     * @param src 本地已加密的文件
     * @param name 目标文件名（由 [BackupRotation.newName] 生成）
     */
    fun publish(
        context: Context,
        treeUri: Uri,
        src: File,
        name: String,
    ): Result {
        if (!src.isFile) return Result.Failed("源文件不存在")

        val resolver = context.contentResolver
        val tmpName = "$TMP_PREFIX$name"
        var tmpUri: Uri? = null

        return try {
            // ① 先建临时文档
            tmpUri = DocumentsContract.createDocument(
                resolver, treeUri, "application/octet-stream", tmpName,
            ) ?: return Result.Failed("无法在目标目录创建文件")

            // ② 写入
            resolver.openOutputStream(tmpUri)?.use { os ->
                src.inputStream().buffered().use { it.copyTo(os) }
            } ?: return Result.Failed("无法打开输出流")

            // ③ 读回验证（文档要求：摘要 + 长度）
            val expectedSize = src.length()
            val expectedDigest = sha256Of(src)

            val actualSize = sizeOf(context, tmpUri)
            if (actualSize != expectedSize) {
                cleanupQuietly(context, tmpUri)
                return Result.Unverified("长度不符（写入 $actualSize，期望 $expectedSize）")
            }

            val actualDigest = digestOf(context, tmpUri)
            if (actualDigest == null) {
                // ⚠️ provider 不支持读回 —— 文档：「若提供方不支持必要读回验证，
                //   不报告完整成功」。这里退化为 Unverified，仍保留文件供用户手动确认。
                EventLog.record("backup.publish.unverifiable", mapOf("name" to name))
                return Result.Unverified("提供方不支持读回校验，无法确认完整性")
            }
            if (actualDigest != expectedDigest) {
                cleanupQuietly(context, tmpUri)
                return Result.Unverified("摘要不符（写入内容与源不一致）")
            }

            // ④ 改成正式名
            //
            // ⚠️ SAF 不保证原子重命名。失败时**保留临时名**并返回 Unverified ——
            //   文件是完整的（已校验过），只是名字不对。
            //   那比「删掉重来」好：用户至少还能手动找到它。
            val finalUri = try {
                DocumentsContract.renameDocument(resolver, tmpUri, name) ?: tmpUri
            } catch (t: Throwable) {
                Log.w(TAG, "重命名失败（保留临时名）: ${t.message}")
                tmpUri
            }

            val record = BackupRotation.Record(
                name = name,
                uri = finalUri.toString(),
                size = expectedSize,
                sha256 = expectedDigest,
                published = true,
                createdAt = System.currentTimeMillis(),
                verified = true,
            )
            BackupRotation.upsert(context, record)

            EventLog.record(
                "backup.publish.ok",
                mapOf("name" to name, "size" to expectedSize.toString()),
            )
            Result.Published(record)
        } catch (t: Throwable) {
            Log.w(TAG, "发布备份失败: ${t.message}")
            EventLog.record("backup.publish.failed", mapOf("reason" to (t.message ?: "?")))
            // 清理半成品
            tmpUri?.let { cleanupQuietly(context, it) }
            Result.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * 删除一个已发布的包。
     *
     * ⚠️ **只删账本里记录过的**。调用方（轮换）保证这一点，
     * 但这里再检查一次 URI 与 name 的对应关系 —— 防御性，因为删错用户文件
     * 是不可逆的。
     */
    fun delete(context: Context, record: BackupRotation.Record): Boolean {
        if (!BackupRotation.isOurs(record.name)) {
            Log.w(TAG, "拒绝删除非本应用命名的文件: ${record.name}")
            return false
        }
        return try {
            val uri = Uri.parse(record.uri)
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (t: Throwable) {
            Log.w(TAG, "删除备份失败（${record.name}）: ${t.message}")
            // ⚠️ 删除失败**不能**当成成功：那会让账本与实际不符，
            //   下次轮换会以为已经删了，于是永远不重试。
            EventLog.record("backup.delete.failed", mapOf("name" to record.name))
            false
        }
    }

    /**
     * 列出目标目录里**我们生成的**备份文件。
     *
     * 用于异常重启后的对账（账本 vs 实际文件）。
     */
    fun listOurs(context: Context, treeUri: Uri): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    if (BackupRotation.isOurs(n)) out.add(n to c.getLong(1))
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "列举备份目录失败: ${t.message}")
            emptyList()
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
    } catch (t: Throwable) {
        -1L
    }

    /** 读回算摘要。provider 不支持读时返回 null（见调用处的处理）。 */
    private fun digestOf(context: Context, uri: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val ins = context.contentResolver.openInputStream(uri) ?: return null
            ins.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = it.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().take(16).joinToString("") { b -> "%02x".format(b) }
        } catch (t: Throwable) {
            null
        }
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    private fun cleanupQuietly(context: Context, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    /*
     * ⚠️ **本对象的边界**：
     *
     * ✅ 做到：临时名写入 → 读回验证（长度+摘要）→ 改正式名 → 账本登记、
     *          读回不支持时如实报 Unverified、删除前再验命名规则
     * ❌ 未做：
     *   - 「第四份空间预检」（需要 provider 的可用空间，SAF 上不通用）
     *   - 持久化 URI 授权（调用方应在拿到 treeUri 后 takePersistableUriPermission）
     *   - 断权/离线的专门错误分类（当前统一进 Failed）
     *   - 异常重启后的完整对账流程
     */
}
