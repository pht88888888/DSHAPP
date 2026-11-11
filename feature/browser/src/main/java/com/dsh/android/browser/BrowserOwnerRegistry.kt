package com.dsh.android.browser

/** 宿主身份与一次绑定的代际。旧 owner 的 detach 不会撤销后来绑定的宿主。 */
internal class BrowserOwnerRegistry<T> {
    class Binding<T> internal constructor(val owner: Any, val generation: Long, val value: T) {
        internal val listeners = LinkedHashSet<() -> Unit>()
    }

    @Volatile
    var current: Binding<T>? = null
        private set
    private var generation = 0L

    fun attach(owner: Any, value: T): Binding<T> {
        val previous: List<() -> Unit>
        val binding: Binding<T>
        synchronized(this) {
            previous = current?.listeners?.toList().orEmpty()
            current?.listeners?.clear()
            binding = Binding(owner, ++generation, value)
            current = binding
        }
        previous.forEach { runCatching(it) }
        return binding
    }

    fun detach(owner: Any) {
        val callbacks = synchronized(this) {
            val binding = current ?: return
            if (binding.owner !== owner) return
            current = null
            ++generation
            binding.listeners.toList().also { binding.listeners.clear() }
        }
        callbacks.forEach { runCatching(it) }
    }

    fun clear() {
        val owner = current?.owner ?: return
        detach(owner)
    }

    fun isCurrent(binding: Binding<T>): Boolean = current === binding

    fun onInvalidated(binding: Binding<T>, callback: () -> Unit): () -> Unit {
        val active = synchronized(this) {
            if (current === binding) {
                binding.listeners.add(callback)
                true
            } else false
        }
        if (!active) callback()
        return { synchronized(this) { binding.listeners.remove(callback) }; Unit }
    }
}
