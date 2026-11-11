package com.dsh.android.browser

/** 浏览器控制权和输入策略；不持有 View、Activity 或工具线程。 */
internal class BrowserControlSession {
    enum class Holder { AI, USER }

    var holder: Holder = Holder.USER
        private set

    @Volatile
    var silentInput: Boolean = false

    @Volatile
    var silentForTool: Boolean = false

    @Volatile
    var isUserTouching: Boolean = false
        private set

    val allowsInputConnection: Boolean get() = !silentInput || isUserTouching

    fun setHolder(value: Holder) {
        holder = value
    }

    fun touchStarted() {
        isUserTouching = true
        silentInput = false
    }

    fun touchEnded() {
        isUserTouching = false
    }

    fun release() {
        holder = Holder.USER
        silentInput = false
    }

    fun close() {
        release()
        silentForTool = false
        isUserTouching = false
    }
}
