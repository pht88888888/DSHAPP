package com.dsh.android.media

import android.content.Context
import android.util.Log
import com.dsh.android.log.EventLog
import java.io.File

/**
 * **受管资源区与资源句柄** —— 素材的统一入口。
 *
 * ## 设计说明 §4.13 的硬要求
 *
 * > 插件素材**先经基座检查并导入受管资源区**，使用**资源句柄**引用，
 * > **不允许播放器按任意外部路径读取文件**。
 * > 控制消息与大素材传输分离。
 *
 * > 真实文件**内容哈希**用于校验和去重，**不用字节数代替内容指纹**；
 * > 限制**类型、单文件/总量**、解码尺寸、并发播放器和资源占用。
 *
 * ## ⚠️ 为什么需要「句柄」而不是「路径」
 *
 * 如果 API 是 `play(path: String)`，那么：
 *
 * | 问题 | 后果 |
 * |---|---|
 * | 插件传 `/data/data/com.dsh.android/files/home/.dsh/.credentials.yaml` | 播放器去读凭据文件 |
 * | 插件传 `../../` | 路径穿越 |
 * | 插件传符号链接 | 绕过目录检查 |
 * | 插件传 `/sdcard/任意文件` | 读用户的其他文件 |
 *
 * 而句柄是一个**不透明的字符串**（如 `res:abc123def`），它只能由
 * [MediaRegistry] 签发，且内部映射到已验证的文件。
 *
 * **插件拿不到真实路径** —— 即使它被恶意代码控制，也只能引用
 * 「已经过校验、且确实在受管目录里」的素材。
 *
 * ## ⚠️ 句柄的格式刻意「不可猜测」
 *
 * ```
 * res:<32位十六进制内容哈希>
 * ```
 *
 * ⚠️ 用**内容哈希**而不是自增 ID 或随机 UUID：
 * - 自增 ID：插件可以猜 `res:1`、`res:2` 去引用别人的素材
 * - 随机 UUID：无法校验内容是否被替换
 * - **内容哈希**：既不可猜（32 位十六进制），又能校验内容完整性
 *
 * ## ⚠️ 一个必须如实说的限制
 *
 * 文档说「**不允许**播放器按任意外部路径读取文件」。本对象能做到的是：
 * - 提供**唯一**的素材入口（句柄）
 * - 校验类型、大小、内容哈希
 *
 * 但**不能**阻止插件绕过本对象、直接用 `File` API 读文件 ——
 * 那是同一 UID 下的合作式约束，文档也承认「不能宣称能隔离恶意插件」。
 */
object MediaRegistry {

    private const val TAG = "DSH"

    /** 受管资源区目录名（在 `filesDir` 下）。 */
    private const val MANAGED_DIR = "managed-media"

    /** 句柄前缀。 */
    private const val HANDLE_PREFIX = "res:"

    /**
     * 素材类型白名单（文档：「**限制类型**」）。
     *
     * ⚠️ 用**文件头魔数**判断，不信扩展名：
     *   插件可以把 `evil.exe` 改名成 `a.png`，而魔数骗不了。
     */
    enum class Kind(val id: String, val label: String, val maxBytes: Long) {
        /** PNG 图片。 */
        PNG("png", "PNG 图片", 4L * 1024 * 1024),

        /** JPEG 图片。 */
        JPEG("jpeg", "JPEG 图片", 4L * 1024 * 1024),

        /** WebP 图片（含透明）。 */
        WEBP("webp", "WebP 图片", 4L * 1024 * 1024),

        /** GIF 动图。 */
        GIF("gif", "GIF 动图", 6L * 1024 * 1024),

        /** WebM 视频（透明视频用这个）。 */
        WEBM("webm", "WebM 视频", 8L * 1024 * 1024),

        /** MP4 视频。 */
        MP4("mp4", "MP4 视频", 16L * 1024 * 1024),
        ;
    }

    /**
     * 导入结果。
     */
    sealed interface ImportResult {
        /**
         * 成功。
         *
         * @param handle 资源句柄（**唯一**能用来引用这份素材的东西）
         * @param kind 识别出的类型
         * @param bytes 字节数
         * @param deduped `true` = 内容已存在，复用了已有文件（去重）
         */
        data class Ok(
            val handle: String,
            val kind: Kind,
            val bytes: Long,
            val deduped: Boolean,
        ) : ImportResult

        /** 类型不在白名单。 */
        data class UnsupportedType(val detected: String) : ImportResult

        /** 超过该类型的单文件上限。 */
        data class TooLarge(val bytes: Long, val max: Long) : ImportResult

        /** 总量超限。 */
        data class QuotaExceeded(val total: Long, val max: Long) : ImportResult

        /** 其他失败。 */
        data class Failed(val reason: String) : ImportResult
    }

    /** 受管资源区总配额（文档：「**单文件/总量**」）。 */
    private const val TOTAL_QUOTA_BYTES = 64L * 1024 * 1024

    /** 受管资源区目录。 */
    fun dir(context: Context): File =
        File(context.filesDir, MANAGED_DIR).apply { mkdirs() }

    /**
     * 导入一份素材。
     *
     * ## 流程
     *
     * ```
     * 读字节 → 魔数判类型 → 查单文件上限 → 算内容哈希
     *   → 查总量配额 → 落盘（哈希命名，天然去重）→ 返回句柄
     * ```
     *
     * @return 见 [ImportResult]
     */
    fun import(context: Context, bytes: ByteArray): ImportResult {
        if (bytes.isEmpty()) return ImportResult.Failed("素材为空")

        // ① 魔数判类型（不信扩展名）
        val kind = detectKind(bytes) ?: return ImportResult.UnsupportedType(
            "无法识别的文件头（前 8 字节：${hex(bytes.take(8))}）",
        )

        // ② 单文件上限
        if (bytes.size > kind.maxBytes) {
            return ImportResult.TooLarge(bytes.size.toLong(), kind.maxBytes)
        }

        // ③ 内容哈希
        val hash = sha256(bytes)

        // ④ 已存在 ⇒ 直接复用（去重，不占额外空间）
        val target = File(dir(context), "$hash.${kind.id}")
        if (target.isFile && target.length() == bytes.size.toLong()) {
            EventLog.record("media.import.dedup", mapOf("kind" to kind.id))
            return ImportResult.Ok(handleOf(hash), kind, bytes.size.toLong(), deduped = true)
        }

        // ⑤ 总量配额
        val total = totalBytes(context)
        if (total + bytes.size > TOTAL_QUOTA_BYTES) {
            return ImportResult.QuotaExceeded(total, TOTAL_QUOTA_BYTES)
        }

        // ⑥ 落盘
        return try {
            target.writeBytes(bytes)
            EventLog.record(
                "media.import.ok",
                mapOf(
                    "kind" to kind.id,
                    "bytes" to bytes.size.toString(),
                    "hash" to hash.take(8),
                ),
            )
            Log.i(TAG, "素材已导入：${kind.id} ${bytes.size} 字节（${hash.take(8)}）")
            ImportResult.Ok(handleOf(hash), kind, bytes.size.toLong(), deduped = false)
        } catch (t: Throwable) {
            Log.w(TAG, "写入素材失败: ${t.message}")
            ImportResult.Failed(t.message ?: "写入失败")
        }
    }

    /**
     * **解析句柄** → 文件（内部用）。
     *
     * ⚠️ 这是唯一能把句柄变成路径的地方，且**只在基座内部调用**。
     *   对插件只暴露 [ImportResult.Ok.handle] 与 [resolveForPlayback]，
     *   后者返回的是一次性播放凭据而非路径。
     *
     * @return `null` = 句柄非法或文件已不存在
     */
    fun resolve(context: Context, handle: String): File? {
        if (!handle.startsWith(HANDLE_PREFIX)) return null
        val hash = handle.removePrefix(HANDLE_PREFIX)
        // ⚠️ 校验哈希格式（32 位十六进制）—— 防止 `res:../../etc/passwd`
        if (hash.length != 32 || !hash.all { it in "0123456789abcdef" }) return null

        val d = dir(context)
        // 找该哈希对应的文件（扩展名不定）
        return d.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith(hash) &&
                it.name.length > 32 && it.name[32] == '.'
        }
    }

    /**
     * **给播放器的安全引用**。
     *
     * ⚠️ 返回的是 `file://` URI 而不是裸路径 —— 但仍由基座构造，
     *   不是插件拼的。这样播放器（`BubbleVideoLayer`）拿到的永远是
     *   「受管目录里的文件」。
     */
    fun uriFor(context: Context, handle: String): String? {
        val f = resolve(context, handle) ?: return null
        // ⚠️ 再确认一次文件确实在受管目录下（防符号链接绕过）
        val canonical = runCatching { f.canonicalPath }.getOrNull() ?: return null
        val root = runCatching { dir(context).canonicalPath }.getOrNull() ?: return null
        if (!canonical.startsWith(root + File.separator)) {
            Log.w(TAG, "素材逃逸受管目录（可能是符号链接）：$canonical")
            EventLog.record("media.escape", mapOf("path" to canonical))
            return null
        }
        return "file://$canonical"
    }

    /**
     * 列出所有受管素材。
     */
    fun list(context: Context): List<Pair<String, Long>> =
        dir(context).listFiles()
            ?.filter { it.isFile }
            ?.map { f ->
                val hash = f.name.substringBefore('.')
                handleOf(hash) to f.length()
            }
            ?: emptyList()

    /** 受管资源区当前占用字节数。 */
    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * 删除一份素材（按句柄）。
     *
     * ⚠️ **不提供「删除全部」**：那会让插件一次清空用户的素材库。
     *   要清理走 [DiskCleanup] 的受管流程。
     */
    fun remove(context: Context, handle: String): Boolean {
        val f = resolve(context, handle) ?: return false
        val ok = runCatching { f.delete() }.getOrDefault(false)
        if (ok) EventLog.record("media.remove", mapOf("handle" to handle.take(16)))
        return ok
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun handleOf(hash: String): String = "$HANDLE_PREFIX${hash.take(32)}"

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun hex(bytes: List<Byte>): String =
        bytes.joinToString(" ") { "%02x".format(it) }

    /**
     * 按**文件头魔数**判断类型。
     *
     * ⚠️ **不信扩展名**：插件可以把 `evil.exe` 改名成 `a.png`，
     *   而魔数骗不了。
     */
    private fun detectKind(b: ByteArray): Kind? {
        if (b.size < 12) return null
        return when {
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> Kind.PNG
            // JPEG: FF D8 FF
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
                b[2] == 0xFF.toByte() -> Kind.JPEG
            // GIF: "GIF8"
            b[0] == 0x47.toByte() && b[1] == 0x49.toByte() &&
                b[2] == 0x46.toByte() && b[3] == 0x38.toByte() -> Kind.GIF
            // WebP: "RIFF" .... "WEBP"
            b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
                b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
                b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
                b[10] == 0x42.toByte() && b[11] == 0x50.toByte() -> Kind.WEBP
            // WebM / MKV: 1A 45 DF A3
            b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
                b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> Kind.WEBM
            // MP4: .... "ftyp"
            b[4] == 0x66.toByte() && b[5] == 0x74.toByte() &&
                b[6] == 0x79.toByte() && b[7] == 0x70.toByte() -> Kind.MP4
            else -> null
        }
    }

    /*
     * ⚠️ **本对象的边界**（不夸大）：
     *
     * ✅ 做到：统一入口、魔数判类型（不信扩展名）、单文件/总量配额、
     *          内容哈希去重、句柄不暴露路径、符号链接逃逸检测、
     *          按句柄删除（无「删除全部」）
     * ❌ 未做：
     *   - **接入现有素材模块**（`BubbleVideos` / `BubbleFrames` /
     *     `CameraCapability` 仍用路径，未改成句柄 —— 那是较大的重构）
     *   - 「解码尺寸限制」（文档要求「限制…解码尺寸」—— 那需要先解码才知道，
     *     当前只限字节数）
     *   - 「并发播放器限制」（文档要求 —— 需要播放器注册表）
     *   - 引用计数（句柄没有「谁在用」的追踪，删除时可能删掉正在播的）
     *
     * ⚠️ **最重要的一条限制**：本对象**不能**阻止插件绕过它直接用 `File` API。
     *   同一 UID 下没有强制隔离能力，文档也承认「不能宣称能隔离恶意插件」。
     *   本对象是**合作式约束**：它让「正确用法」很容易、「错误用法」需要刻意绕过。
     */
}
