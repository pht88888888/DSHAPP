package com.dsh.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.dsh.android.log.DshLog as Log
import java.io.File
import java.security.MessageDigest

/**
 * 悬浮球**状态配图**存储 —— 电脑端桌面宠物头顶那种"表情包 / 状态图"的底座。
 *
 * ## 为什么是「落盘 + 引用」而不是把图片塞进配置
 *
 * 配置（`appearance`）最终写进 SharedPreferences 的一个字符串里，而状态图
 * 一张就是几十到几百 KB。7 个档位 × 每档几张 = 好几 MB，塞进 SharedPreferences
 * 会让**每次读写配置**都带上它（配置在拖拽落点、状态变化时都会被读）。
 *
 * 所以与 [BubbleFrames]/[BubbleVideos] 同一套做法：字节落盘，配置里只留**文件名**。
 *
 * ## 落盘布局
 *
 * ```
 * filesDir/bubble-status-images/<sha1 前 16 位>.<ext>
 * ```
 *
 * ⚠️ **按内容摘要命名**（不是按档位/序号）：同一张图被两个档位引用时只落一份；
 *   重复下发同一张图也是幂等的（不会越写越多）。代价是文件名不可读 —— 但那个
 *   名字对 AI 和用户都没有意义，它只是配置里的一个引用。
 *
 * ⚠️ **不在这里做档位分组**：档位是配置的事（`statusImages[档位][下标]`），
 *   存储只负责"把字节变成名字"。分成两层会让"同一张图配到两个档位"变成两份文件。
 *
 * ## 输入形态
 *
 * 与 [BubbleVideos.write] 逐字同构，三种都收：
 *
 * | 形态 | 典型用法 |
 * |---|---|
 * | `data:image/png;base64,…` | 小图，随工具参数直接带过来 |
 * | 纯 base64 | 同上，省掉前缀 |
 * | **绝对路径** | AI 先用 `bash` 把图下载/生成到工作区，再只传路径 |
 *
 * ⚠️ **路径这一路对状态图比对视频更要紧**：表情包常常是用户从相册/网盘里挑的
 *   现成图片，那些文件本来就在磁盘上，没必要绕一圈 base64。
 */
object BubbleStatusImages {

    private const val TAG = DshApplication.TAG

    /** 素材根目录（在 `filesDir` 下）。与 [BubbleFrames.DIR] 并列。 */
    const val DIR = "bubble-status-images"

    /** 单张图的 base64 长度上限（约 3MB 原始字节）。 */
    const val MAX_IMAGE_BASE64 = 4 * 1024 * 1024

    /**
     * 单张图的原始字节上限。
     *
     * 状态图是**头顶的小图**（默认 96dp），不是壁纸。3MB 足够放一张 1000×1000 的
     * PNG；再大只说明传错了文件（比如整张截图）。
     */
    const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    /**
     * 允许入库的最大像素数（宽×高）。
     *
     * ⚠️ 与 [MAX_IMAGE_BYTES] 是**两个不同的闸**：前者限压缩后字节数（传输体积），
     *   这里限解码后像素数（内存）。两者缺一不可 ——
     *   一个 3 MB 的高压缩 PNG 可以轻易超过 6000×6000。
     *
     * 取 16 MP ≈ 4096×4096：状态图显示尺寸通常不到 200 dp，16 MP 已经远超需要；
     * 而 ARGB_8888 下 16 MP 占 64 MB，是「还能解、但再大就该拒」的边界。
     */
    private const val MAX_STATUS_IMAGE_PIXELS = 16_000_000L

    /**
     * 磁盘上保留的图片数量上限。
     *
     * ⚠️ 这个上限**不是**「配置里能配几张」，而是"垃圾桶别长满"：AI 反复试错会
     *   留下一堆没人引用的旧图，配置里最多只引用 7×N 张。超限时按**最后修改时间**
     *   删最旧的，所以正在用的那些（刚写进来的）不会被误删。
     */
    const val MAX_FILES = 64

    /** 素材根目录。 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /**
     * 按存储名取文件。
     *
     * ⚠️ 名字必须**只含文件名部分**：配置里的引用是 `BubbleStatusImages` 自己
     *   写进去的，但配置可以被手改，带 `../` 的名字会指到目录外去。
     */
    fun file(context: Context, name: String): File? {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/') || n.contains('\\') || n.contains("..")) return null
        return File(dir(context), n)
    }

    /** 某个存储名是否可用（文件存在且非空）。 */
    fun available(context: Context, name: String): Boolean {
        val f = file(context, name) ?: return false
        return f.isFile && f.length() > 0
    }

    /**
     * 把一张图写进存储，返回**存储名**（失败返回 null）。
     *
     * ⚠️ 落盘前必须能解码成位图：写进去一堆垃圾字节、等到显示时才在
     *   `BitmapFactory` 那里失败，会让问题从"AI 传错了"变成"图莫名其妙不显示"。
     *   与 [BubbleAppearance.writeAvatar] 同一道闸。
     */
    fun write(context: Context, payload: String): String? {
        val bytes = readPayload(payload) ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.size > MAX_IMAGE_BYTES) {
            Log.w(TAG, "状态图过大（${bytes.size} > $MAX_IMAGE_BYTES），已放弃")
            return null
        }
        // ⚠️ **只解边界，不解像素。**
        //
        //   原来这里用 `decodeByteArray(bytes, …)` 全尺寸解码，只为判断「是不是
        //   有效图片」。而 MAX_IMAGE_BYTES = 3 MB 限的是**压缩后**大小 ——
        //   一张 3 MB 的 PNG 完全可能是 8000×8000，解成 ARGB_8888 就是
        //   8000 × 8000 × 4 = **256 MB**，低内存机必然 OOM。
        //
        //   而这次解码的唯一目的是校验，像素一个都用不到。`inJustDecodeBounds`
        //   只读文件头填 outWidth/outHeight，**不分配像素内存**，
        //   判断「是不是图片 / 能不能解出来」的效果完全一样，
        //   代价从几百 MB 降到零。这与本文件 [load] 的实现保持一致。
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            Log.w(TAG, "状态图不是有效图片（解不出尺寸），已忽略")
            return null
        }
        // 顺带挡住「能解出尺寸但大到离谱」的图 —— 显示时也必然 OOM。
        // 与 [load] 里 inSampleSize 的判据同源，但这里直接拒绝入库。
        if (probe.outWidth.toLong() * probe.outHeight > MAX_STATUS_IMAGE_PIXELS) {
            Log.w(
                TAG,
                "状态图尺寸过大（${probe.outWidth}x${probe.outHeight} > " +
                    "$MAX_STATUS_IMAGE_PIXELS 像素），已放弃",
            )
            return null
        }
        val ext = extensionOf(bytes)
        val name = digest(bytes) + "." + ext
        val target = file(context, name)
            ?: return null
        // 已经写过同一张图：直接复用，不重写（幂等）。
        if (target.isFile && target.length() == bytes.size.toLong()) return name

        val d = dir(context)
        if (!d.isDirectory && !d.mkdirs()) {
            Log.w(TAG, "无法创建状态图目录")
            return null
        }
        // 先写临时文件再改名：写到一半被杀会留下半张图，显示时表现为
        // 偶发的花屏/半截图，比"没有图"难查得多。
        val tmp = File(d, name + ".tmp")
        return try {
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "状态图改名失败: $name")
                tmp.delete()
                null
            } else {
                prune(context)
                Log.i(TAG, "悬浮球状态图已就位: $name = ${bytes.size} 字节")
                name
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入状态图失败: ${t.message}")
            tmp.delete()
            null
        }
    }

    /**
     * 把配置里的一个引用**归一成存储名**：已经是磁盘上的名字就原样返回，
     * 否则当成新素材写进去。
     *
     * ⚠️ **两种输入必须都认，这正是本函数存在的理由**：配置里的引用有两种来源 ——
     *   ① 上一次下发后由本层写进去的存储名（`load()` 读回来的就是它）；
     *   ② 插件这次新传的路径 / data URI。
     *   只认②会让「AI 改个尺寸」这种操作把已有的图当成路径重新解析一遍而失败，
     *   于是配图凭空消失。所以先查磁盘、再写。
     *
     * @return 存储名；写不进去返回 null（调用方应把它从配置里剔掉）。
     */
    fun ensure(context: Context, payload: String): String? {
        val p = payload.trim()
        if (p.isEmpty()) return null
        // ① 已经是存储名（且文件在）→ 原样保留，一个字节都不碰。
        if (!p.startsWith("/") && !p.startsWith("file://") && !p.startsWith("data:") &&
            !p.contains('/') && !p.contains('\\')
        ) {
            if (available(context, p)) return p
            Log.w(TAG, "状态图引用已失效，已忽略: $p")
            return null
        }
        return write(context, p)
    }

    /**
     * 读回一张状态图。
     *
     * ⚠️ 用 `inSampleSize` 采样：AI 可能传一张 4000×4000 的图，而状态图最大
     *   显示 240dp（约 720px @xxhdpi）。不采样会白占十几 MB 堆 —— 而状态图是
     *   跟着状态切换反复加载的，堆压力比头像大得多。
     */
    fun read(context: Context, name: String, targetPx: Int): Bitmap? {
        val f = file(context, name) ?: return null
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(maxOf(w, h), targetPx.coerceAtLeast(1))
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (t: Throwable) {
            Log.w(TAG, "读取状态图失败: ${t.message}")
            null
        }
    }

    /** 清空整个状态图目录（配置里的引用由调用方负责一并清掉）。 */
    fun clear(context: Context) {
        val d = dir(context)
        val subs = d.listFiles() ?: return
        for (f in subs) f.delete()
        d.delete()
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /** 取不大于 `target` 的最大 2 的幂采样率（与 [BubbleAppearance.readAvatar] 同口径）。 */
    private fun sampleSize(src: Int, target: Int): Int {
        var s = 1
        while (src / (s * 2) >= target) s *= 2
        return s
    }

    /**
     * 超限时按**最后修改时间**删最旧的。
     *
     * ⚠️ 不能删"不在当前配置里的"：本层拿不到配置，而且配置可能是刚被替换、
     *   旧图还等着回滚。按时间删最旧是最保守的判据 —— 刚写进来的那些天然安全。
     */
    private fun prune(context: Context) {
        val subs = dir(context).listFiles()?.filter { it.isFile } ?: return
        if (subs.size <= MAX_FILES) return
        val excess = subs.size - MAX_FILES
        subs.sortedBy { it.lastModified() }.take(excess).forEach {
            if (it.delete()) Log.i(TAG, "状态图超出上限，已清理: ${it.name}")
        }
    }

    /**
     * 把三种输入形态统一成字节。
     *
     * ⚠️ **路径判定放在 base64 之前**，理由与 [BubbleVideos.readPayload] 逐字相同：
     *   一个路径字符串会被 `Base64.decode` 解成垃圾字节**而不报错**。
     */
    private fun readPayload(payload: String): ByteArray? {
        val p = payload.trim()
        if (p.isEmpty()) return null

        if (p.startsWith("/") || p.startsWith("file://")) {
            val path = p.removePrefix("file://")
            val f = File(path)
            if (!f.isFile) {
                Log.w(TAG, "状态图源文件不存在: $path")
                return null
            }
            if (f.length() > MAX_IMAGE_BYTES) {
                Log.w(TAG, "状态图源文件过大（${f.length()} > $MAX_IMAGE_BYTES）: $path")
                return null
            }
            return try {
                f.readBytes()
            } catch (t: Throwable) {
                Log.w(TAG, "读取状态图源文件失败: ${t.message}")
                null
            }
        }

        if (p.length > MAX_IMAGE_BASE64) {
            Log.w(TAG, "状态图 base64 过长（${p.length} > $MAX_IMAGE_BASE64）")
            return null
        }
        val comma = p.indexOf(',')
        val b64 = if (p.startsWith("data:") && comma > 0) p.substring(comma + 1) else p
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(TAG, "状态图 base64 解码失败: ${t.message}")
            null
        }
    }

    /**
     * 按魔数猜扩展名。
     *
     * ⚠️ 只影响文件名，**不影响解码**（`BitmapFactory` 认内容不认后缀）。
     *   但留一个对的后缀，将来用别的工具直接看这个目录时才不至于全是 `.bin`。
     */
    private fun extensionOf(b: ByteArray): String = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[2] == 0xFF.toByte() -> "jpg"
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "webp"
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() -> "gif"
        else -> "img"
    }

    /** 内容摘要前 16 位十六进制（够区分，又不会让文件名长得离谱）。 */
    private fun digest(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1").digest(b)
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append("%02x".format(md[i]))
        return sb.toString()
    }
}
