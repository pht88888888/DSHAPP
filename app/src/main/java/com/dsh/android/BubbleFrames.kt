package com.dsh.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.dsh.android.log.DshLog as Log
import java.io.File

/**
 * 球的**多帧图片**存储与解码 —— 「轮播图片 / 轮播精灵图 / 桌面宠物多动作」的底座。
 *
 * ## 为什么单独一个类
 *
 * 单张头像（[BubbleAppearance.AVATAR_FILE]）只需要「一个文件 + 一次解码」。
 * 轮播要的是「N 张图 + 每张的停留时长 + 播几轮」，还要处理**精灵图切片**：
 * 一张大图按 rows×cols 切成 N 帧，那是最省体积的做法（也是游戏里的常规做法）。
 * 把这些塞进 [BubbleAppearance] 会让那个类变成杂物间。
 *
 * ## 为什么是「多套命名动作」而不是一套全局帧
 *
 * 一套全局帧只能表达「这个球会动」。桌面宠物要的是**同一个球在不同状态下做
 * 不同的动作**（待机呼吸、思考转圈、调工具时干活、等你确认时招手）。那需要
 * 「多套帧 + 一个状态到动作的映射」，而不是给每个状态加一个字段 —— 后者每来
 * 一个新愿望就要改一次接口，且 AI 想加第八种动作时无路可走。
 *
 * 动作名是**自由字符串**（`idle` / `thinking` / `think` / `cat-sleep` 都行），
 * 磁盘上有哪些动作就支持哪些 —— 接口不需要预先枚举。
 *
 * ## 落盘布局
 *
 * ```
 * filesDir/bubble-frames/                  ← 默认动作（= 老数据，零迁移）
 *   ├── 0.bin   第 0 帧
 *   ├── 1.bin   第 1 帧          ← 普通模式：一帧一个文件
 *   └── meta.json
 *
 * filesDir/bubble-frames/                  ← 精灵图模式：只有一个文件
 *   ├── 0.bin   整张精灵图
 *   └── meta.json  {"count":6,"sprite":{"rows":2,"cols":3}}
 *
 * filesDir/bubble-frames/sets/             ← 命名动作
 *   ├── thinking/0.bin, meta.json
 *   └── idle/0.bin, 1.bin, meta.json
 * ```
 *
 * ⚠️ **默认动作直接用 `bubble-frames/` 本身**，不是 `sets/default/`。所以 0.2.6
 *   及以前落下的帧集**零迁移**就是默认动作，不需要任何一次性搬到新布局的代码 ——
 *   那种迁移代码一旦写错就是「用户升级后动画全没了」。
 *
 * ⚠️ **命名动作必须放在 `sets/` 子目录里，不能平铺在根目录。** 根目录的 `0.bin`
 *   已经被默认动作占用了（帧文件名就是 `<index>.bin`），平铺会与帧文件撞名，
 *   表现为「写完一个动作，默认动作的帧被顶掉」。
 *
 * ⚠️ **整目录替换而不是逐帧增量写**：帧数会变（3 帧的轮播换成 5 帧的），
 *   增量写会留下第 4 帧的残影。写入前先删临时目录，成功后整体改名就位。
 *
 * ⚠️ **先写进临时目录再改名**：写到一半被杀会留下半个帧集，那会让球显示成
 *   一堆碎图。临时目录与目标**同父目录**，改名才是原子的；失败时旧帧集完好。
 *
 * ## 精灵图切片
 *
 * [loadFrame] 在精灵图模式下按 `rows × cols` 网格裁出第 `index` 格。
 * **行优先**（从左到右、从上到下），与 CSS `background-position` 的直觉一致，
 * 也与大多数游戏素材一致。
 */
object BubbleFrames {

    private const val TAG = DshApplication.TAG

    /** 默认动作的目录名（在 `filesDir` 下）。 */
    const val DIR = "bubble-frames"

    /** 命名动作的父目录（在 [DIR] 下）。 */
    const val SETS = "sets"

    /**
     * 默认动作名。它**就是** [DIR] 本身，所以老数据天然落在它上面。
     *
     * ⚠️ 这个名字是接口的一部分：`bubble` 工具的 `action` 字段传 `default`
     *   或空串都指它。改名等于破坏兼容。
     */
    const val DEFAULT_ACTION = "default"

    /** 动作名长度上限。 */
    const val MAX_ACTION_CHARS = 24

    /** 动作数量上限（每个动作最多 [MAX_FRAMES] 帧，加起来够用了）。 */
    const val MAX_ACTIONS = 24

    /** 元数据文件名。 */
    const val META = "meta.json"

    /** 单帧 base64 长度上限。与头像同一口径（约 512KB 原始数据）。 */
    const val MAX_FRAME_BASE64 = 700_000

    /** 帧数上限。再多就不是"轮播"而是"逐帧动画"，那该走视频而不是 base64。 */
    const val MAX_FRAMES = 64

    /** 单帧停留时长范围（ms）。 */
    const val MIN_DURATION_MS = 20
    const val MAX_DURATION_MS = 60_000

    /** 无限循环的 `loop` 取值。 */
    const val LOOP_FOREVER = -1

    /**
     * 规范化动作名；非法返回 null，空串返回 [DEFAULT_ACTION]。
     *
     * ⚠️ **白名单是必要的，不是洁癖**：动作名会变成目录名，放任 `..` 或带 `/`
     *   的字符串进来就是一次目录穿越（`sets/../../` 能写到 `filesDir` 外面）。
     *   限定 ASCII 字母数字加连字符下划线，中文名也一并挡掉 —— 中文能表达的意思
     *   用拼音/英文同样能表达，而路径里混非 ASCII 会在 adb / zip 环节反复咬人。
     */
    fun normalizeAction(name: String?): String? {
        val v = name?.trim()?.lowercase() ?: return DEFAULT_ACTION
        if (v.isEmpty()) return DEFAULT_ACTION
        if (v.length > MAX_ACTION_CHARS) return null
        for (c in v) {
            val ok = (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_'
            if (!ok) return null
        }
        return v
    }

    /** 一帧的落盘文件名。 */
    fun frameName(index: Int): String = "$index.bin"

    /**
     * 某个动作的帧集目录。
     *
     * [DEFAULT_ACTION] → `filesDir/bubble-frames`；其余 → `filesDir/bubble-frames/sets/<名>`。
     * 动作名非法时按默认动作处理（调用方通常已用 [normalizeAction] 把关）。
     */
    fun dir(context: Context, action: String = DEFAULT_ACTION): File {
        val name = normalizeAction(action) ?: DEFAULT_ACTION
        return if (name == DEFAULT_ACTION) {
            File(context.filesDir, DIR)
        } else {
            File(File(File(context.filesDir, DIR), SETS), name)
        }
    }

    /** 命名动作的父目录（可能不存在）。 */
    fun setsDir(context: Context): File = File(File(context.filesDir, DIR), SETS)

    /** 某个动作的帧集是否可用（目录存在且至少有一帧文件）。 */
    fun available(context: Context, action: String = DEFAULT_ACTION): Boolean {
        val d = dir(context, action)
        return d.isDirectory && d.listFiles()?.any { it.name.endsWith(".bin") } == true
    }

    /**
     * 磁盘上已有的动作名：默认动作（有帧时）在最前，其余按名字排序。
     *
     * 这是「接口不需要枚举」的落点 —— 插件问「现在有哪些动作」时不用维护一份
     * 清单，直接看磁盘。副作用是**动作只能通过 `frames` 建立、通过传空 `frames`
     * 删除**，没有"注册动作名"这一步，少一个能写坏的状态。
     */
    fun actionNames(context: Context): List<String> {
        val out = ArrayList<String>()
        if (available(context)) out += DEFAULT_ACTION
        val subs = setsDir(context).listFiles() ?: return out
        for (f in subs) {
            if (!f.isDirectory) continue
            val name = normalizeAction(f.name) ?: continue
            if (name == DEFAULT_ACTION) continue
            if (!available(context, name)) continue
            out += name
        }
        return out.sortedWith(compareBy({ it != DEFAULT_ACTION }, { it }))
    }

    /**
     * 写入某个动作的一整套帧。**全有或全无**：任何一步失败都不动已有的帧集。
     *
     * 两种模式由 [sprite] 决定：
     * - `sprite == null`（普通轮播）：[frames] 每项是一帧完整图片，落 N 个文件。
     * - `sprite != null`（精灵图）：[frames] **只取第一项**，那是整张精灵图；
     *   帧数 = `rows × cols`，落 1 个文件。
     *
     * @param frames 每帧的 data URI（`data:image/…;base64,…`）。
     * @param durations 每帧停留时长（ms）；为空或长度不符时用 [defaultDurationMs]。
     * @param sprite 精灵图网格 `rows × cols`；为 null 表示每帧是独立完整图片。
     * @param defaultDurationMs 缺省停留时长。
     * @param action 动作名（见 [normalizeAction]）。
     * @param loop 播放轮数；[LOOP_FOREVER] = 一直循环。用于「播一次作为提示」。
     * @return 写入的帧数；失败返回 null。
     */
    fun write(
        context: Context,
        frames: List<String>,
        durations: List<Int>,
        sprite: Pair<Int, Int>?,
        defaultDurationMs: Int,
        action: String = DEFAULT_ACTION,
        loop: Int = LOOP_FOREVER,
    ): Int? {
        if (frames.isEmpty()) return null
        val name = normalizeAction(action) ?: run {
            Log.w(TAG, "悬浮球动作名非法，已放弃写入")
            return null
        }

        // 精灵图：只解一张大图，帧数由网格算出。
        val count: Int
        val payloads: List<String>
        if (sprite != null) {
            count = sprite.first * sprite.second
            if (count <= 0 || count > MAX_FRAMES) {
                Log.w(TAG, "精灵图网格 ${sprite.first}×${sprite.second} 不合法（帧数 $count）")
                return null
            }
            payloads = listOf(frames[0])
        } else {
            count = minOf(frames.size, MAX_FRAMES)
            payloads = frames.subList(0, count)
        }

        // 先全部解码验证，再落盘 —— 避免"前 3 帧好了、第 4 帧是坏的"这种半成品。
        val decoded = ArrayList<ByteArray>(payloads.size)
        for ((i, uri) in payloads.withIndex()) {
            val bytes = decodeDataUri(uri)
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "动作 $name 的第 $i 帧不是可解析的 data URI，整批放弃")
                return null
            }
            decoded += bytes
        }

        val target = dir(context, name)
        val tmp = tmpFor(context, name)
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) {
            Log.w(TAG, "无法创建帧集临时目录（动作 $name）")
            return null
        }

        try {
            for (i in decoded.indices) {
                File(tmp, frameName(i)).writeBytes(decoded[i])
            }
            File(tmp, META).writeText(
                buildMeta(count, durations, sprite, defaultDurationMs, loop),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "写入帧集失败: ${t.message}")
            tmp.deleteRecursively()
            return null
        }

        if (!publish(tmp, target, oldFor(context, name))) {
            tmp.deleteRecursively()
            return null
        }
        Log.i(
            TAG,
            "悬浮球动作帧集已更新: $name = $count 帧" +
                (sprite?.let { "（精灵图 ${it.first}×${it.second}）" } ?: "") +
                (if (loop >= 0) "（播 $loop 轮）" else ""),
        )
        return count
    }

    /**
     * 只改某个动作的精灵图网格，复用已有的精灵图字节。
     *
     * 用于「AI 只改了 `spriteRows`/`spriteCols`、没重传图片」这一种情况 ——
     * 旧帧集是按旧网格切的，不重切就会显示成网格里的碎块。
     *
     * ⚠️ 只在**精灵图模式**下成立（磁盘上只有 `0.bin` 那一张整图）。普通轮播
     *   模式没有"整图"可切，返回 null 让调用方退回整图模式。
     *
     * @return 新的帧数；不可重切时返回 null。
     */
    fun rewriteSprite(
        context: Context,
        sprite: Pair<Int, Int>,
        action: String = DEFAULT_ACTION,
    ): Int? {
        val d = dir(context, action)
        val sheet = File(d, frameName(0))
        if (!sheet.isFile) return null
        val count = sprite.first * sprite.second
        if (count <= 0 || count > MAX_FRAMES) return null
        return try {
            // 只改 meta，字节不动 —— 这正是精灵图模式省空间的地方。
            val old = readMeta(context, action)
            val durations = old?.optJSONArray("durations")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it, 400) }
            } ?: emptyList()
            val defaultMs = durations.firstOrNull() ?: 400
            val loop = old?.optInt("loop", LOOP_FOREVER) ?: LOOP_FOREVER
            File(d, META).writeText(buildMeta(count, durations, sprite, defaultMs, loop))
            count
        } catch (t: Throwable) {
            Log.w(TAG, "重切精灵图失败: ${t.message}")
            null
        }
    }

    /**
     * 清空某个动作的帧集（传空 `frames` 时调用）。
     *
     * ⚠️ 只清这一个动作，不动其它动作 —— 那条路会让「AI 删掉 thinking 动作」
     *   顺手把整个宠物的 idle 动作也删掉。
     */
    fun clear(context: Context, action: String = DEFAULT_ACTION) {
        dir(context, action).deleteRecursively()
        tmpFor(context, action).deleteRecursively()
        oldFor(context, action).deleteRecursively()
    }

    /**
     * 读某个动作第 [index] 帧的位图。
     *
     * ⚠️ 精灵图模式下**磁盘上只有 `0.bin`**（那是整张精灵图），所以这里必须
     *   无条件读第 0 个文件再切片，而不是去读 `index.bin` —— 后者不存在，
     *   会让球在精灵图模式下永远显示空白。
     *
     * @param reqSize 期望边长（px）；只用于 `inSampleSize` 粗降采样，实际缩放交给 ImageView。
     * @param fallbackSprite meta 里没有网格时的兜底网格（老数据兼容）。
     */
    fun loadFrame(
        context: Context,
        index: Int,
        reqSize: Int,
        action: String = DEFAULT_ACTION,
        fallbackSprite: Pair<Int, Int>? = null,
    ): Bitmap? {
        val sprite = sprite(context, action) ?: fallbackSprite
        val d = dir(context, action)
        val fileIndex = if (sprite != null) 0 else index
        val file = File(d, frameName(fileIndex))
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (sprite == null) {
                decodeScaled(bytes, reqSize)
            } else {
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (full == null) {
                    null
                } else {
                    val cut = slice(full, index, sprite.first, sprite.second)
                    /*
                     * ⚠️ **`cut == null` 时也必须回收 `full`。**
                     *
                     * 原来的写法是 `if (cut != null && cut !== full) full.recycle()`，
                     * 只覆盖了「切成功」和「返回原图」两种情况。而 `slice` 内部
                     * 还有一条 `catch (t: Throwable) → return null` 的路径
                     * （切片失败，典型原因正是 OOM —— `createBitmap` 要再分配一块
                     * 单格位图）：
                     *
                     *   · cut == null  → 条件为假 → **整张精灵图不会被回收**；
                     *   · 而精灵图是整张图，1024² = 4 MB、2048² = 16 MB；
                     *   · `loadFrame` 是**逐帧调用**的，动画一播每帧漏一次。
                     *
                     * 也就是说最该省内存的那一刻（已经 OOM 了）反而漏掉最大的一块，
                     * 接下来必然连续 OOM —— 表现为「精灵图动画播几下就崩」。
                     *
                     * 改成 `if (cut !== full)`：它同时覆盖了两种要回收的情况 ——
                     *   · cut == null（切片失败，full 是唯一持有者）；
                     *   · cut 是新位图（切成功，full 已经没人要了）。
                     * 唯一不回收的是 `cut === full`（原图原样返回，回收它就是毁掉底图）。
                     */
                    if (cut !== full) full.recycle()
                    cut
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取动作 $action 的第 $index 帧失败: ${t.message}")
            null
        }
    }

    /** 帧数（读 meta；读不到就数文件）。 */
    fun frameCount(context: Context, action: String = DEFAULT_ACTION): Int {
        val meta = readMeta(context, action) ?: return countFiles(context, action)
        return meta.optInt("count", 0).takeIf { it > 0 } ?: countFiles(context, action)
    }

    /**
     * 一帧的像素长宽（精灵图按**单格**算）。探测不到返回 `null`。
     *
     * 唯一用途是**自动画布尺寸**要按主体素材的长宽比来算，所以只读
     * `inJustDecodeBounds` 的边界，不解码整张位图。
     */
    fun frameSize(context: Context, action: String = DEFAULT_ACTION): Pair<Int, Int>? {
        // 精灵图模式下第 0 个文件就是整张图，单格尺寸要从网格切出来。
        val file = File(dir(context, action), frameName(0))
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var w = opts.outWidth
            var h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            sprite(context, action)?.let { (rows, cols) ->
                w /= cols
                h /= rows
            }
            if (w <= 0 || h <= 0) null else w to h
        } catch (t: Throwable) {
            Log.w(TAG, "探测动作 $action 的帧尺寸失败: ${t.message}")
            null
        }
    }

    /** 每帧停留时长（ms）。meta 缺失或长度不符时返回空表，由调用方兜底。 */
    fun durations(context: Context, fallback: Int, action: String = DEFAULT_ACTION): List<Int> {
        val meta = readMeta(context, action) ?: return emptyList()
        val arr = meta.optJSONArray("durations") ?: return emptyList()
        val out = ArrayList<Int>(arr.length())
        for (i in 0 until arr.length()) {
            out += arr.optInt(i, fallback).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        }
        return out
    }

    /** 播放轮数；meta 缺失时返回 [fallback]（通常是外观配置里的全局 `frameLoop`）。 */
    fun loop(context: Context, fallback: Int = LOOP_FOREVER, action: String = DEFAULT_ACTION): Int {
        val meta = readMeta(context, action) ?: return fallback
        if (!meta.has("loop")) return fallback
        return meta.optInt("loop", fallback).coerceIn(-1, 100_000)
    }

    /** 精灵图网格；不是精灵图时返回 null。 */
    fun sprite(context: Context, action: String = DEFAULT_ACTION): Pair<Int, Int>? {
        val meta = readMeta(context, action) ?: return null
        val s = meta.optJSONObject("sprite") ?: return null
        val rows = s.optInt("rows", 0)
        val cols = s.optInt("cols", 0)
        return if (rows > 0 && cols > 0) rows to cols else null
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * 临时目录。**必须与目标同父目录** —— 跨目录 `renameTo` 在多数文件系统上
     * 退化成「复制 + 删除」，那就不是原子的了。
     */
    private fun tmpFor(context: Context, action: String): File {
        val target = dir(context, action)
        return File(target.parentFile, "${target.name}.tmp")
    }

    /** 备份目录，语义同 [tmpFor]。 */
    private fun oldFor(context: Context, action: String): File {
        val target = dir(context, action)
        return File(target.parentFile, "${target.name}.old")
    }

    /**
     * 把 [tmp] 原子地替换成 [target]。
     *
     * 顺序是「旧目录先挪成 .old → tmp 改名就位 → 删 .old」，失败时把 .old 挪回来。
     * 这样任何一步中断，磁盘上都至少有一份完整帧集。
     *
     * ⚠️ 第三个参数是**该动作的 .old 目录**，由调用方按动作算好传进来 ——
     *   早期版本在这里自己拼 `filesDir/bubble-frames.old`，多动作后会变成
     *   「所有动作共用一个备份目录」，两个动作先后写入就会互相删掉对方的备份。
     */
    private fun publish(tmp: File, target: File, backup: File): Boolean {
        backup.deleteRecursively()
        if (target.exists() && !target.renameTo(backup)) {
            // 挪不动就退化成"删了重建"：丢的是旧帧集，但新帧集一定能就位。
            target.deleteRecursively()
        }
        if (!tmp.renameTo(target)) {
            Log.w(TAG, "帧集改名失败，回滚")
            backup.renameTo(target)
            return false
        }
        backup.deleteRecursively()
        return true
    }

    private fun countFiles(context: Context, action: String): Int {
        val d = dir(context, action)
        if (!d.isDirectory) return 0
        var n = 0
        while (File(d, frameName(n)).isFile) n++
        return n
    }

    private fun readMeta(context: Context, action: String): org.json.JSONObject? = try {
        val f = File(dir(context, action), META)
        if (f.isFile) org.json.JSONObject(f.readText()) else null
    } catch (t: Throwable) {
        null
    }

    private fun buildMeta(
        count: Int,
        durations: List<Int>,
        sprite: Pair<Int, Int>?,
        defaultDurationMs: Int,
        loop: Int,
    ): String {
        val o = org.json.JSONObject()
        o.put("count", count)
        val arr = org.json.JSONArray()
        for (i in 0 until count) {
            val d = durations.getOrNull(i) ?: defaultDurationMs
            arr.put(d.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
        }
        o.put("durations", arr)
        if (sprite != null) {
            o.put("sprite", org.json.JSONObject().put("rows", sprite.first).put("cols", sprite.second))
        }
        o.put("loop", loop.coerceIn(LOOP_FOREVER, 100_000))
        return o.toString()
    }

    /**
     * 裁出精灵图的第 [index] 格（**行优先**）。
     *
     * ⚠️ `createBitmap` 在源图不可变时也能工作，但它返回的是**新位图**，源图要由
     *   调用方回收 —— 见 [loadFrame] 里的 `recycle()`。
     */
    private fun slice(src: Bitmap, index: Int, rows: Int, cols: Int): Bitmap? {
        if (rows <= 0 || cols <= 0) return src
        val total = rows * cols
        if (index < 0 || index >= total) return src
        val w = src.width / cols
        val h = src.height / rows
        if (w <= 0 || h <= 0) return src
        val r = index / cols
        val c = index % cols
        return try {
            Bitmap.createBitmap(src, c * w, r * h, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "精灵图切片失败(第 $index 格): ${t.message}")
            null
        }
    }

    /** 解 `data:image/…;base64,…` 或纯 base64 成字节。 */
    private fun decodeDataUri(uri: String): ByteArray? {
        if (uri.isEmpty()) return null
        val comma = uri.indexOf(',')
        val payload = if (uri.startsWith("data:") && comma > 0) uri.substring(comma + 1) else uri
        return try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 按 [reqSize] 粗降采样解码（只降 2 的幂次）。
     *
     * ⚠️ 只降采样、**不精确缩放**：精确缩放交给 ImageView 的 scaleType，
     *   那一步在 GPU 上做，比在 Java 里 `createScaledBitmap` 快得多。
     */
    private fun decodeScaled(bytes: ByteArray, reqSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val maxDim = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        if (reqSize > 0) {
            while (maxDim / (sample * 2) >= reqSize) sample *= 2
        }
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
        } catch (t: Throwable) {
            Log.w(TAG, "解码帧失败: ${t.message}")
            null
        }
    }
}
