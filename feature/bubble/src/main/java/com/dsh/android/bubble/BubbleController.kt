package com.dsh.android.bubble

import com.dsh.android.engine.EngineStatus
import com.dsh.android.ui.BalanceSnapshot
import com.dsh.android.ui.ChatTranscript
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** 进程级策略与最近快照；窗口销毁不会重置它，也不持有 Activity 或窗口资源。 */
internal class BubbleController {
    enum class Visibility { KEEP, HIDE, EXPAND, COLLAPSE }

    @Volatile var enabled = false
    @Volatile var foreground = true
        private set
    @Volatile var overlayProbe: Boolean? = null
    @Volatile var status: EngineStatus? = null
        private set
    @Volatile var transcript: ChatTranscript? = null
        private set
    @Volatile var balance: BalanceSnapshot? = null
        private set
    private val tapSequence = AtomicInteger()
    private var panelOwner: WeakReference<Any>? = null
    private var panelPort: WeakReference<BubblePanelActions>? = null

    fun acceptStatus(value: EngineStatus) { status = value }
    fun acceptTranscript(value: ChatTranscript) { transcript = value }
    fun acceptBalance(value: BalanceSnapshot) { balance = value }
    fun tapRefreshSequence(): Int = tapSequence.get()
    fun recordTapRefresh() { tapSequence.incrementAndGet() }

    fun onBackground(state: String): Boolean {
        foreground = false
        return enabled && overlayProbe != false && state != "hidden"
    }

    fun onForeground(state: String): Boolean {
        foreground = true
        return state == "auto"
    }

    fun configurationVisibility(state: String): Visibility {
        if (!enabled || overlayProbe == false) return Visibility.KEEP
        return when (state) {
            "hidden" -> Visibility.HIDE
            "expanded" -> Visibility.EXPAND
            "collapsed" -> Visibility.COLLAPSE
            else -> Visibility.KEEP
        }
    }

    @Synchronized fun attachPanelActions(owner: Any, port: BubblePanelActions) {
        panelOwner = WeakReference(owner)
        panelPort = WeakReference(port)
    }

    @Synchronized fun detachPanelActions(owner: Any) {
        if (panelOwner?.get() !== owner) return
        panelOwner = null
        panelPort = null
    }

    @Synchronized fun panelActions(): BubblePanelActions? {
        if (panelOwner?.get() == null) {
            panelOwner = null
            panelPort = null
            return null
        }
        return panelPort?.get()
    }
}
