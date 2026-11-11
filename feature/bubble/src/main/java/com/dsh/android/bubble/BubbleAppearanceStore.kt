package com.dsh.android.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.dsh.android.log.DshLog as Log
import org.json.JSONObject
import java.io.File
import com.dsh.android.bubble.BubbleAppearance.Companion.AVATAR_FILE
import com.dsh.android.bubble.BubbleAppearance.Companion.EDGE_IMAGE_FILE
import com.dsh.android.bubble.BubbleAppearance.Companion.KEY_APPEARANCE
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_AVATAR_BASE64
import com.dsh.android.bubble.BubbleAppearance.Companion.MAX_EDGE_IMAGE_BASE64
import com.dsh.android.bubble.BubbleAppearance.Companion.PREFS

/** 外观 JSON 与自定义图片的持久化。配置键、图片标记和解码上限保持稳定。 */
internal object BubbleAppearanceStore {
    private const val TAG = Log.TAG
    private const val MAX_BUBBLE_IMAGE_PIXELS = 16_000_000L

    /**
     * 解码 data URI 形式的贴边图并落盘。
     *
     * 与 [writeAvatar] 同构：先解码确认是有效位图再写，避免把垃圾字节写进去、
     * 下次启动才在 BitmapFactory 那里失败。
     */
    fun writeEdgeImage(context: Context, image: String): File? {
        if (!image.startsWith("data:image/")) return null
        val comma = image.indexOf(',')
        if (comma < 0) return null
        val bytes = try {
            Base64.decode(image.substring(comma + 1), Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮球贴边图 base64 解码失败: ${t.message}")
            return null
        }
        // ⚠️ 同样**只解边界、不解像素** —— 与 [writeAvatar] 完全同构，理由见那边。
        //   体积闸在上游（`normalizeEdgeImage` 用 MAX_EDGE_IMAGE_BASE64 = 1.4 MB），
        //   而 1.4 MB 的高压缩 PNG 解成 ARGB_8888 一样能吃掉几百 MB。
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            Log.w(TAG, "悬浮球贴边图不是有效图片（解不出尺寸），已忽略")
            return null
        }
        if (probe.outWidth.toLong() * probe.outHeight > MAX_BUBBLE_IMAGE_PIXELS) {
            Log.w(
                TAG,
                "悬浮球贴边图尺寸过大（${probe.outWidth}x${probe.outHeight}），已忽略",
            )
            return null
        }
        return try {
            val f = File(context.filesDir, EDGE_IMAGE_FILE)
            f.writeBytes(bytes)
            Log.i(TAG, "已保存悬浮球贴边图（${bytes.size} 字节）")
            f
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮球贴边图落盘失败: ${t.message}")
            null
        }
    }

    /**
     * 读回已落盘的自定义贴边图。
     *
     * 贴边态是**竖长条**（默认 28dp 宽），解码目标取宽度即可 —— 按长边采样会
     * 因为高度大而过度降采样，探头形象反而糊掉。
     */
    fun readEdgeImage(context: Context, targetWidthPx: Int): Bitmap? {
        val f = File(context.filesDir, EDGE_IMAGE_FILE)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            // ⚠️ 只按**宽度**判断是否够采样：竖长条的 outHeight 天然很大，
            //   两个维度都要大于阈值才采样的话，这里会几乎永远不降采样。
            while (bounds.outWidth / (sample * 2) >= targetWidthPx * 2) sample *= 2
            BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "读取悬浮球贴边图失败: ${t.message}")
            null
        }
    }

    /**
     * 解码 data URI 形式的头像并落盘。
     *
     * ⚠️ **落盘而不是只留内存**：插件换的头像要能在 App 重启后仍然生效，
     *   否则用户会觉得「AI 换的皮肤过一会自己变回去了」。
     *
     * @return 落盘后的文件；非 data URI 或解码失败时返回 null。
     */
    fun writeAvatar(context: Context, avatar: String): File? {
        if (!avatar.startsWith("data:image/")) return null
        val comma = avatar.indexOf(',')
        if (comma < 0) return null
        val b64 = avatar.substring(comma + 1)
        val bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮球自定义头像 base64 解码失败: ${t.message}")
            return null
        }
        // ⚠️ 体积闸在上游：`normalizeAvatar()` 已用 MAX_AVATAR_BASE64 卡过
        //   base64 字符串长度（700 KB），所以这里不必再限字节数 ——
        //   重复加一道永远不触发的闸只会误导后来人以为这里是入口。
        //
        // ⚠️ 但**像素**必须在解码前卡：见下面 inJustDecodeBounds 的说明。
        //
        // 先确认是有效位图再落盘：把一堆垃圾字节写进去、下次启动才在
        //   BitmapFactory 那里失败，会让问题从"插件写错了"变成"重启后
        //   头像莫名其妙没了"，难查得多。
        //
        // ⚠️ 但**只解边界、不解像素**：这里唯一目的是校验，像素一个都用不到。
        //   全尺寸解码的成本与图片尺寸成正比 —— 一张 3 MB 的高压缩 PNG
        //   解成 8000×8000 就是 256 MB，低内存机直接 OOM。
        //   `inJustDecodeBounds` 只读文件头，判断效果一样、代价为零。
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
            Log.w(TAG, "悬浮球自定义头像不是有效图片（解不出尺寸），已忽略")
            return null
        }
        if (probe.outWidth.toLong() * probe.outHeight > MAX_BUBBLE_IMAGE_PIXELS) {
            Log.w(
                TAG,
                "悬浮球自定义头像尺寸过大（${probe.outWidth}x${probe.outHeight}），已忽略",
            )
            return null
        }
        return try {
            val f = File(context.filesDir, AVATAR_FILE)
            f.writeBytes(bytes)
            Log.i(TAG, "已保存悬浮球自定义头像（${bytes.size} 字节）")
            f
        } catch (t: Throwable) {
            Log.w(TAG, "悬浮球自定义头像落盘失败: ${t.message}")
            null
        }
    }

    /**
     * 读回已落盘的自定义头像。
     *
     * ⚠️ 用 `inSampleSize` 采样：插件可能塞一张 4000×4000 的图，而球的显示
     *   尺寸最大 160dp（约 480px @xxhdpi）。不采样会白占十几 MB 堆。
     */
    fun readAvatar(context: Context, targetPx: Int): Bitmap? {
        val f = File(context.filesDir, AVATAR_FILE)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (t: Throwable) {
            Log.w(TAG, "读取悬浮球自定义头像失败: ${t.message}")
            null
        }
    }

    /** 从 SharedPreferences 读回配置。缺失或损坏时返回默认值。 */
    fun load(context: Context): BubbleAppearance {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_APPEARANCE, null) ?: return BubbleAppearance()
        return BubbleAppearanceParser.merge(raw, BubbleAppearance(), context)
    }

    /**
     * 落盘。**每次 apply 都写**：插件改完就该在重启后仍然生效。
     *
     * ⚠️ **这里漏一个字段 = 那个字段只在本次运行有效，重启即回默认。**
     *   表现是"AI 改的时候看得见，重开 App 就没了" —— 加字段时务必同步这里，
     *   [load] 走 [merge]，`merge` 认的键这里都得写出来。
     */
    fun save(context: Context, appearance: BubbleAppearance) {
        val json = JSONObject().apply {
            put("state", appearance.state)
            put("shape", appearance.shape)
            put("shapePath", appearance.shapePath)
            put("shapeViewBox", appearance.shapeViewBox)
            put("size", appearance.size)
            // ⚠️ `0`（自动）也要写：`load()` 走 `merge`，缺键时保留的是
            //   `BubbleAppearance()` 的默认值 —— 默认恰好也是 0，所以这里
            //   漏写不会立刻出错，但**改用非 0 默认值的那一刻就会静默回退**。
            put("boxWidth", appearance.boxWidth)
            put("boxHeight", appearance.boxHeight)
            put("showDot", appearance.showDot)
            put("cornerRadius", appearance.cornerRadius)
            put("opacity", appearance.opacity.toDouble())
            put("bgOpacity", appearance.bgOpacity.toDouble())
            put("avatarOpacity", appearance.avatarOpacity.toDouble())
            put("avatarScale", appearance.avatarScale.toDouble())
            put("bg", appearance.bg)
            put("bg2", appearance.bg2)
            put("border", appearance.border)
            put("borderWidth", appearance.borderWidth)
            // ⚠️ 头像**不落进配置 JSON**：base64 可能几百 KB，写进
            //   SharedPreferences 会让每次读写都带上它。图片单独落盘（见
            //   writeAvatar），配置里只留一个标记表示"用自定义头像"。
            put("avatar", if (appearance.avatar.startsWith("data:image/")) AVATAR_FILE else appearance.avatar)
            // ⚠️ 轮播帧同理，甚至更要紧：帧字节在 `filesDir/bubble-frames/`
            //   （见 [BubbleFrames]），这里只存"有几帧、怎么播"。
            //   `frameCount` 必须落盘，否则重启后 `load()` 以为没有帧，
            //   磁盘上那堆文件再没人引用。
            put("frameCount", appearance.frameCount)
            put("frameIntervalMs", appearance.frameIntervalMs)
            put("frameLoop", appearance.frameLoop)
            put("spriteRows", appearance.spriteRows)
            put("spriteCols", appearance.spriteCols)
            // ⚠️ 动作名与状态映射必须落盘，否则重启后宠物就只会播默认动作 ——
            //   而磁盘上那几套动作帧还在，表现为"重开 App 宠物就不动了"。
            //   动作名空串也要写：它是"自动跟随"这个状态本身。
            put("action", appearance.action)
            put(
                "actionMap",
                JSONObject().apply {
                    for ((k, v) in appearance.actionMap) put(k, v)
                },
            )
            put("spin", appearance.spin)
            put("pulse", appearance.pulse)
            put("panelWidth", appearance.panelWidth)
            put("panelHeight", appearance.panelHeight)
            put("panelBg", appearance.panelBg)
            put("panelText", appearance.panelText)
            put("panelAccent", appearance.panelAccent)
            put("statusAutoHideMs", appearance.statusAutoHideMs)
            put("edgeSnap", appearance.edgeSnap)
            // ⚠️ 与 [avatar] 同理：data URI 不能直接落盘（几十万字符的 JSON），
            //   落盘时换成文件标记，字节另存 bubble-edge.bin。
            put(
                "edgeImage",
                if (appearance.edgeImage.startsWith("data:image/")) EDGE_IMAGE_FILE
                else appearance.edgeImage,
            )
            put("edgeWidth", appearance.edgeWidth)
            put("edgeHeight", appearance.edgeHeight)
            put("edgeOpacity", appearance.edgeOpacity.toDouble())
            put("edgeAnchor", appearance.edgeAnchor.toDouble())
            put("throwEnabled", appearance.throwEnabled)
            put("imeFlingEnabled", appearance.imeFlingEnabled)
            put("shakeEnabled", appearance.shakeEnabled)
            // 余额徽标整包落盘。**逐字段写而不是塞一个嵌套 JSON 字符串**：
            // 读回时 `BalanceBadge.merge` 要按字段判"缺省即保持"，嵌套字符串
            // 会让"某个字段没写"与"某个字段是空串"彻底分不开。
            put(
                "balanceBadge",
                JSONObject().apply {
                    val bb = appearance.balanceBadge
                    put("enabled", bb.enabled)
                    put("anchor", bb.anchor)
                    put("gapDp", bb.gapDp)
                    put("bg", bb.bg)
                    put("fg", bb.fg)
                    put("border", bb.border)
                    put("borderWidthDp", bb.borderWidthDp)
                    put("radiusDp", bb.radiusDp)
                    put("fontSizeSp", bb.fontSizeSp)
                    put("paddingHDp", bb.paddingHDp)
                    put("paddingVDp", bb.paddingVDp)
                    put("maxWidthDp", bb.maxWidthDp)
                    put("template", bb.template)
                    put("showCostFlash", bb.showCostFlash)
                    put("hideWhenIdle", bb.hideWhenIdle)
                    put("refreshOnTurnEnd", bb.refreshOnTurnEnd)
                    put("refreshEverySec", bb.refreshEverySec)
                    put("refreshOnTap", bb.refreshOnTap)
                    put("tapRefreshMinIntervalSec", bb.tapRefreshMinIntervalSec)
                },
            )
            // ⚠️ 池、权重、状态文案都必须落盘，否则重启后宠物只剩默认动作 ——
            //   而磁盘上那几十段素材还在，表现为"重开 App 宠物就不动了"。
            //   空对象/空数组也要写：它们正是"清空"这个状态本身。
            put(
                "pools",
                JSONObject().apply {
                    for ((k, v) in appearance.pools) put(k, org.json.JSONArray(v))
                },
            )
            put(
                "poolWeights",
                JSONObject().apply {
                    for ((k, v) in appearance.poolWeights) put(k, v)
                },
            )
            put(
                "workStatusTexts",
                org.json.JSONArray().apply {
                    for (tier in appearance.workStatusTexts) put(org.json.JSONArray(tier))
                },
            )
            put("moveEnabled", appearance.moveEnabled)
            put("moveIntervalSec", appearance.moveIntervalSec)
            put("moveMinDp", appearance.moveMinDp)
            put("moveMaxDp", appearance.moveMaxDp)
            // ⚠️ 状态配图存的是**落盘后的文件名**（见 [BubbleStatusImages]），
            //   不是原始路径 —— 插件可能传一个 /sdcard 上的临时路径，
            //   那个文件随时会被清掉，而配图必须跨重启活着。
            put(
                "statusImages",
                org.json.JSONArray().apply {
                    for (tier in appearance.statusImages) put(org.json.JSONArray(tier))
                },
            )
            put("statusImageSizeDp", appearance.statusImageSizeDp)
            put("flipOnTurn", appearance.flipOnTurn)
            put(
                "moveParams",
                JSONObject().apply {
                    for ((action, p) in appearance.moveParams) {
                        put(
                            action,
                            JSONObject().apply {
                                // ⚠️ null 不写键（而不是写 0）：0 是"原地踏步"
                                //   这个合法值，写 0 会把"跟随全局"改掉。
                                p.minDp?.let { put("minDp", it) }
                                p.maxDp?.let { put("maxDp", it) }
                            },
                        )
                    }
                },
            )
        }.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_APPEARANCE, json).apply()
    }
}
