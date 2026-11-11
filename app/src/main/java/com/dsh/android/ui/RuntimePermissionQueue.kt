package com.dsh.android.ui

/** 主线程拥有的运行时权限批队列；平台 launcher 仍由 Activity 注册。 */
internal class RuntimePermissionQueue(
    private val isGranted: (String) -> Boolean,
    private val launch: (String) -> Unit,
    private val onFailure: (String, Throwable) -> Unit,
) {
    private class Batch(val permission: String) {
        val sinks = mutableListOf<(Boolean) -> Unit>()
    }
    private val waiting = ArrayDeque<Batch>()
    private var active: Batch? = null
    private var delivering = false
    private var closed = false
    val activePermission: String? get() = active?.permission

    fun request(permission: String, sink: (Boolean) -> Unit) {
        if (closed) { deliver(permission, sink, false); return }
        val batch = active?.takeIf { it.permission == permission }
            ?: waiting.firstOrNull { it.permission == permission }
            ?: Batch(permission).also(waiting::addLast)
        batch.sinks.add(sink)
        drain()
    }

    fun finish(granted: Boolean) {
        val batch = active ?: return
        active = null
        delivering = true
        try {
            batch.sinks.forEach { deliver(batch.permission, it, granted) }
        } finally {
            delivering = false
            drain()
        }
    }

    /** 先关闭并摘除全部请求，销毁中的回调重入不能重新启动授权。 */
    fun close() {
        if (closed) return
        closed = true
        val pending = buildList {
            active?.let(::add)
            addAll(waiting)
        }
        active = null
        waiting.clear()
        pending.forEach { batch -> batch.sinks.forEach { deliver(batch.permission, it, false) } }
    }

    private fun drain() {
        if (closed || delivering || active != null || waiting.isEmpty()) return
        val batch = waiting.removeFirst()
        active = batch
        try {
            if (isGranted(batch.permission)) finish(true) else launch(batch.permission)
        } catch (failure: Exception) {
            onFailure(batch.permission, failure)
            finish(false)
        }
    }

    private fun deliver(permission: String, sink: (Boolean) -> Unit, granted: Boolean) {
        runCatching { sink(granted) }.onFailure { onFailure(permission, it) }
    }
}
