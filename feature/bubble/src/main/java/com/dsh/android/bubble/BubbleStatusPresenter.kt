package com.dsh.android.bubble

/** 内容去重与自动隐藏的单一所有者；窗口创建、淡出及移除由宿主处理。 */
internal class BubbleStatusPresenter(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpire: () -> Unit,
) {
    data class Content(
        val text: String,
        val running: Boolean,
        val overrideMs: Int?,
        val image: String,
    )

    private var current: Content? = null
    private var expiry: Runnable? = null

    /** 相同内容不会重新计时。显式常驻与默认时长仍视作不同内容。 */
    fun present(content: Content, defaultMs: Int): Boolean {
        if (content == current) return false
        current = content
        cancelExpiry()
        val ms = content.overrideMs ?: defaultMs
        if (ms > 0) {
            val task = object : Runnable {
                override fun run() {
                    if (expiry !== this) return
                    expiry = null
                    onExpire()
                }
            }
            expiry = task
            schedule(task, ms.toLong())
        }
        return true
    }

    /** 显示语义或外观改变时，下次同一内容也重新展示。 */
    fun invalidate() {
        current = null
    }

    fun clear() {
        cancelExpiry()
        current = null
    }

    private fun cancelExpiry() {
        expiry?.let(cancel)
        expiry = null
    }
}
