package com.dsh.android.bubble
import android.content.Context
import android.util.Base64
import com.dsh.android.log.DshLog as Log
import java.io.File
import com.dsh.android.core.DshApplication

/**
 * 悬浮球的**视频素材**存储 —— 「像 dsh-pet 那样用透明 webm 做动作」的底座。
 *
 * ## 为什么必须有这一层
 *
 * [BubbleFrames] 只能表达「N 张静态图按时间轮播」，而 dsh-pet 那一整套宠物动效
 * （待机呼吸、抛掷、落地挤压、各种碎碎念）全是**带 alpha 通道的 VP9 webm**。
 * 没有视频这一层，AI 能做出的最好东西就是"一张精灵图转圈" —— 这正是
 * 「悬浮球只能放精灵图，做不了桌面宠物」的由来。
 *
 * ⚠️ **原生 Android 没有能播透明 webm 的 View**：`MediaPlayer`/`VideoView` 走
 *   `SurfaceView`/`TextureView`，两者都不做 alpha 合成；MediaCodec 的 VP9 解码器
 *   也不输出 alpha side data。真机实测（CDY-TN90 / Android 10 / Chromium 114）
 *   唯一可行的路是 **WebView**：`video` → `canvas` 这条路径保留 alpha
 *   （四角像素 `a≈0`，透明背景占 82.9%）。
 *   所以本层落盘的字节由 [FloatingBubble] 里那个**内容层 WebView**渲染，而悬浮球
 *   本身的逻辑（拖拽、抛掷、物理、状态映射、面板）全部仍在原生 Kotlin 里 ——
 *   WebView 只是"内容层的一个渲染后端"，不是悬浮球的实现。
 *
 * ## 落盘布局（与 [BubbleFrames] 同构，两套素材共用一个动作名空间）
 *
 * ```
 * filesDir/bubble-videos/0.webm              ← 默认动作
 * filesDir/bubble-videos/sets/idle/0.webm    ← 命名动作
 * ```
 *
 * ⚠️ **默认动作直接用 `bubble-videos/` 本身**（不是 `sets/default/`），与
 *   [BubbleFrames] 逐字同构。两边的 `default` 语义必须一致，否则 `actionMap`
 *   里写的同一个动作名会在一边命中、另一边落空。
 *
 * ## 与帧集的分工
 *
 * 内容层优先级是**视频 > 帧集 > 单张头像**。视频是"高质量动效"，帧集是
 * "能跑就行"的兜底 —— 后者不需要任何解码器支持，在极端设备上仍能工作。
 * 所以**两者不是替代关系**：没有视频素材时，悬浮球的行为与加这一层之前完全一致。
 *
 * ## 输入形态（AI 怎么把素材送进来）
 *
 * [write] 的 `payload` 三种形态都收，因为 AI 弄到素材的路子不止一条：
 *
 * | 形态 | 典型用法 |
 * |---|---|
 * | `data:video/webm;base64,…` | 小文件，随工具参数直接带过来 |
 * | 纯 base64 | 同上，省掉前缀 |
 * | **绝对路径** | AI 先用 `bash` 把 webm 下载/生成到工作区，再只传路径 |
 *
 * ⚠️ **路径这一路是必须的，不是便利**：dsh-pet 单个 webm 就有 194KB~1.3MB，
 *   base64 后还要再涨 33%，几十个动作走参数会撑爆工具调用。传路径让字节走文件
 *   系统，参数里只有几十字节。
 */
object BubbleVideos {

    private const val TAG = DshApplication.TAG

    /** 素材根目录（在 `filesDir` 下）。与 [BubbleFrames.DIR] 并列。 */
    const val DIR = "bubble-videos"

    /** 命名动作的父目录（在 [DIR] 下）。 */
    const val SETS = "sets"

    /**
     * 默认动作名。
     *
     * ⚠️ 直接复用 [BubbleFrames.DEFAULT_ACTION]，**不要各写一份字面量**：
     *   两边一旦漂移，`actionMap` 里同一个名字就会一边命中一边落空。
     */
    const val DEFAULT_ACTION = BubbleFrames.DEFAULT_ACTION

    /**
     * 一个动作的素材文件名。
     *
     * 保留 `0` 这个编号（而不是叫 `video.webm`）是为了与 [BubbleFrames] 的
     * `0.bin` 对齐 —— 两边的"第 0 个文件"含义相同，将来要支持一个动作多段视频
     * 时按编号续下去即可。
     */
    const val FILE_NAME = "0.webm"

    /** 单个视频的 base64 长度上限（约 6MB 原始字节）。 */
    const val MAX_VIDEO_BASE64 = 8 * 1024 * 1024

    /** 单个视频的原始字节上限。dsh-pet 最大的一个是 1.3MB，留足余量。 */
    const val MAX_VIDEO_BYTES = 6 * 1024 * 1024

    /** 动作数量上限。与 [BubbleFrames.MAX_ACTIONS] 同口径。 */
    const val MAX_ACTIONS = 24

    /**
     * 某个动作的素材目录。
     *
     * [DEFAULT_ACTION] → `filesDir/bubble-videos`；其余 → `…/sets/<名>`。
     * 动作名非法时按默认动作处理（调用方通常已用 [BubbleFrames.normalizeAction] 把关）。
     */
    fun dir(context: Context, action: String = DEFAULT_ACTION): File {
        val name = BubbleFrames.normalizeAction(action) ?: DEFAULT_ACTION
        return if (name == DEFAULT_ACTION) {
            File(context.filesDir, DIR)
        } else {
            File(File(File(context.filesDir, DIR), SETS), name)
        }
    }

    /** 命名动作的父目录（可能不存在）。 */
    fun setsDir(context: Context): File = File(File(context.filesDir, DIR), SETS)

    /** 某个动作的素材文件。 */
    fun file(context: Context, action: String = DEFAULT_ACTION): File =
        File(dir(context, action), FILE_NAME)

    /** 某个动作是否可用（文件存在且非空）。 */
    fun available(context: Context, action: String = DEFAULT_ACTION): Boolean {
        val f = file(context, action)
        return f.isFile && f.length() > 0
    }

    /**
     * 磁盘上已有视频的动作名：默认动作（有素材时）在最前，其余按名字排序。
     *
     * 与 [BubbleFrames.actionNames] 同一套语义 —— **磁盘即注册表**，
     * 没有"注册动作名"这一步，少一步就能写错一步。
     */
    fun actionNames(context: Context): List<String> {
        val out = ArrayList<String>()
        if (available(context)) out += DEFAULT_ACTION
        val subs = setsDir(context).listFiles() ?: return out
        for (f in subs) {
            if (!f.isDirectory) continue
            val name = BubbleFrames.normalizeAction(f.name) ?: continue
            if (name == DEFAULT_ACTION) continue
            if (!available(context, name)) continue
            out += name
        }
        return out.sortedWith(compareBy({ it != DEFAULT_ACTION }, { it }))
    }

    /**
     * 写入某个动作的视频素材。**全有或全无**：失败不动已有的素材。
     *
     * @param payload data URI / 纯 base64 / 绝对路径，见类注释的表格。
     * @param action 动作名。
     * @return 写入的字节数；失败返回 null。
     */
    fun write(context: Context, payload: String, action: String = DEFAULT_ACTION): Int? {
        val name = BubbleFrames.normalizeAction(action) ?: run {
            Log.w(TAG, "悬浮球视频动作名非法，已放弃写入")
            return null
        }
        if (actionNames(context).size >= MAX_ACTIONS && !available(context, name)) {
            Log.w(TAG, "悬浮球视频动作过多（上限 $MAX_ACTIONS），已放弃写入 $name")
            return null
        }

        val bytes = readPayload(payload) ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.size > MAX_VIDEO_BYTES) {
            Log.w(TAG, "视频过大（${bytes.size} > $MAX_VIDEO_BYTES），已放弃写入 $name")
            return null
        }
        // ⚠️ 校验魔数：传错文件（png/文本）时**当场拒绝**，而不是让 WebView 在
        //   运行时静默播不出来 —— 后者的症状是"球不见了"，极难定位。
        if (!isWebm(bytes)) {
            Log.w(TAG, "素材不是 webm（魔数不符），已放弃写入 $name")
            return null
        }

        val target = dir(context, name)
        if (!target.isDirectory && !target.mkdirs()) {
            Log.w(TAG, "无法创建视频素材目录（动作 $name）")
            return null
        }

        // 先写临时文件再改名：写到一半被杀会留下半个文件，WebView 那边表现为
        // 偶发播不出来。临时文件与目标**同目录**，改名才是原子的。
        val tmp = File(target, "$FILE_NAME.tmp")
        val dst = File(target, FILE_NAME)
        return try {
            tmp.writeBytes(bytes)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                Log.w(TAG, "视频素材改名失败（动作 $name）")
                tmp.delete()
                null
            } else {
                Log.i(TAG, "悬浮球视频素材已就位: $name = ${bytes.size} 字节")
                bytes.size
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写入视频素材失败: ${t.message}")
            tmp.delete()
            null
        }
    }

    /**
     * 清空某个动作的视频素材。
     *
     * ⚠️ 只清这一个动作，不动其它动作 —— 与 [BubbleFrames.clear] 同理：
     *   "删掉 thinking 动作"不该顺手把整个宠物的 idle 也删掉。
     */
    fun clear(context: Context, action: String = DEFAULT_ACTION) {
        val d = dir(context, action)
        File(d, FILE_NAME).delete()
        File(d, "$FILE_NAME.tmp").delete()
        // 命名动作的目录清空后一并删掉，否则 actionNames() 会扫到空目录。
        if (d != File(context.filesDir, DIR)) d.delete()
    }

    /** 某个动作素材的 `file://` URL（给 WebView 用）。不存在返回 null。 */
    fun urlOf(context: Context, action: String = DEFAULT_ACTION): String? {
        val f = file(context, action)
        return if (f.isFile && f.length() > 0) "file://" + f.absolutePath else null
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * 把三种输入形态统一成字节。
     *
     * ⚠️ **路径判定放在 base64 之前**：绝对路径以 `/` 开头，不可能与 base64 或
     *   data URI 混淆；反过来先试 base64 解码，一个路径字符串会被 `Base64.decode`
     *   解成垃圾字节而不报错，症状是"素材写进去了但播不出来"。
     */
    private fun readPayload(payload: String): ByteArray? {
        val p = payload.trim()
        if (p.isEmpty()) return null

        if (p.startsWith("/") || p.startsWith("file://")) {
            val path = p.removePrefix("file://")
            val f = File(path)
            if (!f.isFile) {
                Log.w(TAG, "视频源文件不存在: $path")
                return null
            }
            if (f.length() > MAX_VIDEO_BYTES) {
                Log.w(TAG, "视频源文件过大（${f.length()} > $MAX_VIDEO_BYTES）: $path")
                return null
            }
            return try {
                f.readBytes()
            } catch (t: Throwable) {
                Log.w(TAG, "读取视频源文件失败: ${t.message}")
                null
            }
        }

        if (p.length > MAX_VIDEO_BASE64) {
            Log.w(TAG, "视频 base64 过长（${p.length} > $MAX_VIDEO_BASE64）")
            return null
        }
        val comma = p.indexOf(',')
        val b64 = if (p.startsWith("data:") && comma > 0) p.substring(comma + 1) else p
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(TAG, "视频 base64 解码失败: ${t.message}")
            null
        }
    }

    /**
     * 是否是 Matroska/WebM 容器（EBML 魔数 `1A 45 DF A3`）。
     *
     * 只认容器头，**不解析编码格式** —— 判断"是不是 VP9"要读 CodecID，那需要
     * 一个完整的 EBML 解析器，而这里要挡的只是"传错文件"这一类错误。
     */
    private fun isWebm(b: ByteArray): Boolean =
        b.size >= 4 &&
            b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
            b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()
}
