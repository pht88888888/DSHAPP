package com.dsh.android.ui

/** adjustResize 下的键盘高度学习与预判；窗口、布局监听注册和 margin 写入由宿主负责。 */
internal class ImeLayoutController(
    private val scheduler: UiTaskScheduler,
    private val loadHeight: () -> Int,
    private val saveHeight: (Int) -> Unit,
    private val applyMargin: (Int, String) -> Unit,
) {
    private var cachedHeight = 0
    private var restHeight = 0
    private var rootWidth = 0
    private var prejudging = false
    private var closed = false
    private val restore = Runnable { restore("超时未见 insets") }

    fun load() { cachedHeight = loadHeight().coerceAtLeast(0) }

    fun willShow(currentRootHeight: Int) {
        if (closed || prejudging || cachedHeight <= 0 || imeHeight(currentRootHeight) > 0) return
        prejudging = true
        applyMargin(cachedHeight, "键盘预判：提前缩矮 ${cachedHeight}px")
        scheduler.remove(restore)
        scheduler.postDelayed(restore, 700L)
    }

    fun layout(width: Int, height: Int, oldHeight: Int) {
        if (closed || height <= 0) return
        if (width != rootWidth) {
            rootWidth = width
            restHeight = height
            load()
            return
        }
        if (height > restHeight) restHeight = height
        val ime = imeHeight(height)
        if (ime > 0) {
            if (ime != cachedHeight) {
                cachedHeight = ime
                saveHeight(ime)
            }
            if (prejudging) restore("真 insets 已到 ${oldHeight}→${height}")
        }
        // 自己修改 margin 也会触发布局；ime=0 不撤销预判，交给超时处理。
    }

    fun close() {
        closed = true
        scheduler.remove(restore)
        prejudging = false
    }

    private fun imeHeight(height: Int): Int {
        if (restHeight <= 0) return 0
        return (restHeight - height).takeIf { it > 150 } ?: 0
    }

    private fun restore(reason: String) {
        scheduler.remove(restore)
        if (!prejudging || closed) return
        prejudging = false
        applyMargin(0, "键盘预判结束（$reason）")
    }
}
