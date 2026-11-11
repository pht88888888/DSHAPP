package com.dsh.android.ui

import java.io.File
import java.util.UUID

/** 浏览器面板模式与待发标注的唯一状态所有者，不持有视图或 Activity。 */
internal class BrowserPaneCoordinator {
    enum class Mode { USER, TOOL }
    data class State(val mode: Mode = Mode.USER, val visible: Boolean = false, val embedded: Boolean = false)
    data class Annotation(val id: String, val file: File, val question: String)

    @Volatile var state = State()
        private set
    private val identity = UUID.randomUUID().toString()
    private var canvasGeneration = 0L
    private var annotationSequence = 0L
    private var pending: Annotation? = null
    private var closed = false

    @Synchronized fun openByUser() {
        if (!closed) state = state.copy(mode = Mode.USER)
    }

    @Synchronized fun prepareForTool() {
        if (!closed) state = state.copy(mode = Mode.TOOL)
    }

    @Synchronized fun syncVisibility(visible: Boolean) {
        if (!closed) state = state.copy(visible = visible)
    }

    @Synchronized fun embedded() {
        if (!closed) state = state.copy(visible = true, embedded = true)
    }

    @Synchronized fun hide(clearEmbedding: Boolean = false) {
        if (!closed) state = state.copy(visible = false, embedded = if (clearEmbedding) false else state.embedded)
    }

    /** 新画布使旧画布的发送回调失效；已提交的标注仍可被页面取走。 */
    @Synchronized fun beginAnnotation(): Long? = if (closed) null else ++canvasGeneration

    @Synchronized fun cancelAnnotation(canvas: Long): Boolean {
        if (closed || canvas != canvasGeneration) return false
        canvasGeneration++
        return true
    }

    @Synchronized fun submitAnnotation(canvas: Long, file: File, question: String): Boolean {
        if (closed || canvas != canvasGeneration) return false
        pending = Annotation("$identity-${++annotationSequence}", file, question)
        canvasGeneration++
        return true
    }

    /** 编码不持锁；旧同步协议取走即消费，异步协议只窥视并等待精确身份 ack。 */
    fun readAnnotation(consume: Boolean, encode: (Annotation) -> String): String {
        val item = synchronized(this) {
            if (closed) null else pending?.also { if (consume) pending = null }
        } ?: return ""
        val encoded = encode(item)
        return synchronized(this) {
            if (closed || (!consume && pending?.id != item.id)) "" else encoded
        }
    }

    @Synchronized fun acknowledgeAnnotation(id: String) {
        if (!closed && pending?.id == id) pending = null
    }

    @Synchronized fun close() {
        closed = true
        canvasGeneration++
        pending = null
        state = State()
    }
}
