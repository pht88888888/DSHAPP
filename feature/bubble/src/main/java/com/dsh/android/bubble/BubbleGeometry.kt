package com.dsh.android.bubble

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.roundToInt
import com.dsh.android.feature.bubble.R
import com.dsh.android.bubble.BubbleAppearance.Companion.AVATAR_FILE
import com.dsh.android.bubble.BubbleAppearance.Companion.AVATAR_NONE

/** 根据外观和素材计算画布尺寸，所有窗口几何复用同一算法。 */
internal object BubbleGeometry {

    /**
     * 按**当前主体素材**的长宽比算画布尺寸（px）。
     *
     * 这是 `boxWidth`/`boxHeight` 为 0（默认）时的取值来源 ——「默认的时候，
     * 它就是根据主体的那个素材，长宽来自适应」。
     *
     * 素材来源按**内容层的优先级**逐条试（与 [FloatingBubble.applyContentLayer]
     * 的顺序一致，否则会出现"显示的素材和量出来的素材不是同一个"）：
     *
     * ① 视频层（`bubble-videos/<动作>.webm`）—— 见下 ⚠️
     * ② 动作帧集 / 精灵图 → [BubbleFrames.frameSize]
     * ③ 自定义头像 → 落盘文件的解码边界
     * ④ 内置头像 `bubble_avatar` 的固有尺寸
     *
     * 长边取 `size`，短边按素材比例缩放，**但不超过 `size`** —— 这样
     * 正方形素材得到的就是原来的 `size × size`（老行为逐字不变）。
     *
     * ⚠️ **视频层不参与量取**：webm 的分辨率要 demux 才拿得到，`BubbleVideos`
     *   没有这个能力（也是刻意不加，那要把整个容器解析搬进来）。所以有视频素材
     *   时按**正方形**算 —— 用户若需要别的比例，显式给 `boxWidth`/`boxHeight`。
     */
    fun autoBox(context: Context, appearance: BubbleAppearance, action: String): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val long = (appearance.size * density).roundToInt().coerceAtLeast(1)
        val ratio = subjectRatio(context, appearance, action)
        // 长边恒等于 size，短边按比例 —— 于是"正方形素材 = 老行为"自动成立。
        val w = if (ratio != null && ratio < 1f) (long * ratio).roundToInt() else long
        val h = if (ratio != null && ratio > 1f) (long / ratio).roundToInt() else long
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    /** 主体素材的 `宽/高`；量不到返回 null（按正方形处理）。 */
    private fun subjectRatio(context: Context, appearance: BubbleAppearance, action: String): Float? {
        val (w, h) = subjectSize(context, appearance, action) ?: return null
        if (w <= 0 || h <= 0) return null
        return w.toFloat() / h.toFloat()
    }

    /** 主体素材的像素长宽；量不到返回 null。 */
    private fun subjectSize(context: Context, appearance: BubbleAppearance, action: String): Pair<Int, Int>? {
        // ② 帧集 / 精灵图：磁盘上有这个动作的帧就用它。
        if (BubbleFrames.available(context, action)) {
            BubbleFrames.frameSize(context, action)?.let { return it }
        }
        // ③ 自定义头像：只解边界，不解码整张图。
        if (appearance.usesCustomAvatar) {
            val f = File(context.filesDir, AVATAR_FILE)
            if (f.isFile && f.length() > 0L) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) return opts.outWidth to opts.outHeight
            }
        }
        // ④ 内置头像：按**当前密度的那一份**量固有尺寸。
        if (appearance.avatar != AVATAR_NONE) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, R.drawable.bubble_avatar, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) return opts.outWidth to opts.outHeight
        }
        return null
    }

    /**
     * 画布的实际像素长宽（已解析「0 = 自动」）。
     *
     * ⚠️ **所有几何计算都必须走这里**，不能各处自己 `dp(appearance.size)` ——
     *   上一版就是因为有 8 处各算各的尺寸，改一个字段要追 8 个调用点。
     */
    fun boxPx(context: Context, appearance: BubbleAppearance, action: String): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val auto = autoBox(context, appearance, action)
        val rawW = if (appearance.boxWidth > 0) appearance.boxWidth else 0
        val rawH = if (appearance.boxHeight > 0) appearance.boxHeight else 0
        // 两个都没配 → 完全走自动。
        if (rawW == 0 && rawH == 0) return auto

        // ⚠️ **`boxWidth`/`boxHeight` 是「相对 `size` 的比例基准」，不是绝对值。**
        //
        //   原实现直接用它们当最终 dp，于是只要两个都配了，`size` 就被彻底
        //   架空 —— 真机反馈「调节直径没反应」。桌宠预设正是这种情形
        //   （`size:160, boxWidth:160, boxHeight:142`）。
        //
        //   现在的语义：
        //     `size` = 画布的**长边**，`boxWidth`/`boxHeight` 只表达**长宽比**。
        //   于是拖直径滑块能整体缩放，而画布形状保持不变。
        //
        //   举例（长宽比 160:142）：
        //     size=160 → 160 x 142
        //     size=320 → 320 x 284
        //   这正是用户对「直径」的直觉。
        //
        //   ⚠️ 素材本身是正方形（没配 boxWidth/boxHeight）时仍走 [autoBox]，
        //     行为与以前完全一致，不影响存量配置。
        val longDp = appearance.size
        val boxLong = maxOf(rawW, rawH)
        if (boxLong <= 0) return auto

        // 按「配的那个当长边」求比例，缺的那一轴由比例推出。
        val wDp: Int
        val hDp: Int
        if (rawW > 0 && rawH > 0) {
            // 两个都给：等比缩放到长边 = size。
            val k = longDp.toFloat() / boxLong
            wDp = (rawW * k).roundToInt()
            hDp = (rawH * k).roundToInt()
        } else if (rawW > 0) {
            // 只给宽：宽就是 size，高按素材比例。
            val ratio = if (auto.first > 0) auto.second.toFloat() / auto.first else 1f
            wDp = longDp
            hDp = (wDp * ratio).roundToInt()
        } else {
            // 只给高：高就是 size，宽按素材比例。
            val ratio = if (auto.second > 0) auto.first.toFloat() / auto.second else 1f
            hDp = longDp
            wDp = (hDp * ratio).roundToInt()
        }
        return (wDp * dm.density).roundToInt().coerceAtLeast(1) to
            (hDp * dm.density).roundToInt().coerceAtLeast(1)
    }
}
