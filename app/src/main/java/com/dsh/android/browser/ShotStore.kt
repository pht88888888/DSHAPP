package com.dsh.android.browser

import android.util.Log
import java.io.File

/**
 * 截图目录的落盘与清理。
 *
 * ## 为什么单独抽一个文件
 *
 * 写这个目录的地方有两个，各自独立：
 * - [AiBrowserView.saveShot] —— AI 用工具「看屏幕」时截的图（`shot-*.png`）
 * - [AnnotateView.exportBitmap] —— 用户圈画后合成的图（`mark-*.png`）
 *
 * 两处原来都只写不删。分开各写一份清理逻辑会重蹈覆辙（改一处漏一处，
 * 而且这种漏洞**完全静默**：没人会发现），所以统一走这里。
 *
 * ## 为什么要清理
 *
 * 每张都是 **100% 质量的 PNG**（全屏 1080×2400 量级，一张 2–8 MB），
 * 而写入频率由模型决定 —— 排查一个问题连着看十几屏很常见。
 * 不清理的话应用私有目录会一直涨，用户在系统「存储」里只看到 DSH 越来越大，
 * 却查不到是哪个文件，也没法自己删。
 *
 * ## 为什么留最近几张就够
 *
 * 截图是**一次性投递**的：落盘后立刻把路径交给工具调用，模型读到图就不再需要
 * 原文件。留几张只为给「刚发生的事」留个现场（出问题能回看），不是长期存储。
 */
internal object ShotStore {

    private const val TAG = "ShotStore"

    /**
     * 目录里最多保留几张（`shot-*` 与 `mark-*` 合计）。
     *
     * 12 张 ≈ 一次排查的现场量，够回看；最多占用约 100 MB 以内。
     */
    const val MAX_SHOTS = 12

    /** 取（并按需创建）截图目录。 */
    fun dir(fallbackRoot: File): File =
        File(fallbackRoot, "browser-shots").apply { if (!exists()) mkdirs() }

    /**
     * 只留最近 [MAX_SHOTS] 张，其余删掉。
     *
     * 在**写新图之前**调用，这样刚写的图不会被自己删掉。
     *
     * ⚠️ 只碰本模块命名的 `shot-*.png` / `mark-*.png`，不动目录里任何别的文件 ——
     *   这个目录也可能被用户或别的功能放东西进去。
     * ⚠️ 删除失败一律吞掉：清理是尽力而为，绝不能因为它失败而让截图本身失败。
     */
    fun prune(dir: File, keep: Int = MAX_SHOTS) {
        try {
            val all = dir.listFiles { f ->
                f.isFile &&
                    f.name.endsWith(".png") &&
                    (f.name.startsWith("shot-") || f.name.startsWith("mark-"))
            } ?: return
            // 留出本次即将写入的那一张的位置。
            val budget = (keep - 1).coerceAtLeast(0)
            if (all.size <= budget) return
            all.sortedByDescending { it.lastModified() }
                .drop(budget)
                .forEach { stale ->
                    if (stale.delete()) {
                        Log.i(TAG, "清理旧截图: ${stale.name}")
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "清理旧截图失败（不影响本次写入）: ${t.message}")
        }
    }
}
