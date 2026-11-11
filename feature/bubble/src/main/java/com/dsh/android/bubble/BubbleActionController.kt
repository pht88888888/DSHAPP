package com.dsh.android.bubble

/** 动作决策与三个计时器的 owner；不持有 View、窗口坐标或动画对象。 */
internal class BubbleActionController(
    private val assets: Assets,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val now: () -> Long,
    private val isAttached: () -> Boolean,
    private val isDragging: () -> Boolean,
    private val canMove: () -> Boolean,
    private val onActionChanged: () -> Unit,
    private val onPresentationChanged: () -> Unit,
    private val onMoveRequested: () -> Unit,
    private val random: () -> Double = Math::random,
) {
    data class Config(
        val action: String = "",
        val actionMap: Map<String, String> = emptyMap(),
        val pools: Map<String, List<String>> = emptyMap(),
        val poolWeights: Map<String, Int> = emptyMap(),
        val moveEnabled: Boolean = false,
        val moveIntervalMs: Long = 20_000,
        val defaultAction: String = "default",
    )
    data class Presentation(val text: String = "", val action: String = "", val image: String = "")
    interface Assets {
        fun pick(pool: String, excluding: String): String?
        fun presentation(state: String): Presentation
    }

    private var active = false
    private var config = Config()
    var runState = "idle"
        private set
    var chainAction = ""
        private set
    var chainIsTurn = false
        private set
    var presentation = Presentation()
        private set
    var manualAction = ""
        private set
    var overrideAction = ""
        private set
    private var chainRepeats = 0
    private var manualUntil = 0L
    private var chainTask: Runnable? = null
    private var moveTask: Runnable? = null
    private var manualTask: Runnable? = null

    fun configure(next: Config, startTimers: Boolean = true) {
        active = startTimers
        config = next
        chainAction = ""
        chainIsTurn = false
        presentation = assets.presentation(runState)
        scheduleChain()
        scheduleMove()
    }

    fun start() {
        active = true
        scheduleChain()
        scheduleMove()
    }

    fun wantedAction(): String = when {
        config.action.isNotEmpty() -> config.action
        overrideAction.isNotEmpty() -> overrideAction
        isManualAlive() -> manualAction
        runState == "idle" && chainAction.isNotEmpty() -> chainAction
        presentation.action.isNotEmpty() -> presentation.action
        !config.actionMap[runState].isNullOrEmpty() -> config.actionMap.getValue(runState)
        runState != "idle" && !config.actionMap["idle"].isNullOrEmpty() -> config.actionMap.getValue("idle")
        else -> config.defaultAction
    }

    private fun isManualAlive(): Boolean {
        if (manualAction.isEmpty()) return false
        if (manualUntil == 0L || now() < manualUntil) return true
        clearManualAction()
        return false
    }

    fun playManualAction(action: String, holdMs: Long) {
        clearManualAction()
        manualAction = action
        manualUntil = if (holdMs > 0) now() + holdMs else 0L
        if (holdMs > 0) {
            val task = object : Runnable {
                override fun run() {
                    if (manualTask !== this) return
                    manualTask = null
                    clearManualAction()
                    onActionChanged()
                }
            }
            manualTask = task
            schedule(task, holdMs)
        }
        onActionChanged()
    }

    fun clearManualAction() {
        manualTask?.let(cancel)
        manualTask = null
        manualAction = ""
        manualUntil = 0L
    }

    fun applyRunState(next: String) {
        if (next == runState) return
        runState = next
        presentation = assets.presentation(next)
        if (next != "idle") {
            chainAction = ""
            cancelChain()
        } else {
            scheduleChain()
        }
        onPresentationChanged()
        if (config.action.isEmpty() && overrideAction.isEmpty()) onActionChanged()
    }

    fun setOverrideAction(action: String) {
        val next = action.trim()
        if (next == overrideAction) return
        overrideAction = next
        onActionChanged()
    }

    /** 同一动作可能属于两个池，因此转向标记只能在抽取时记录。 */
    fun consumeTurnFlip(): Boolean {
        if (!chainIsTurn) return false
        chainIsTurn = false
        return true
    }

    fun rollNextChainAction() {
        if (runState != "idle") return
        val weight = config.poolWeights["idle"] ?: 10
        if (weight <= 0) {
            chainAction = ""
            chainIsTurn = false
            return
        }
        val turnWeight = config.poolWeights["turn"] ?: 0
        if (turnWeight > 0 && !config.pools["turn"].isNullOrEmpty()) {
            if (random() * (weight + turnWeight) >= weight) {
                val turn = assets.pick("turn", chainAction)
                if (!turn.isNullOrEmpty()) {
                    chainAction = turn
                    chainIsTurn = true
                    return
                }
            }
        }
        chainIsTurn = false
        val picked = assets.pick("idle", chainAction) ?: run {
            chainAction = ""
            return
        }
        if (picked == chainAction) {
            if (++chainRepeats < 2) {
                chainAction = assets.pick("idle", chainAction) ?: picked
                return
            }
        } else {
            chainRepeats = 0
        }
        chainAction = picked
    }

    fun scheduleChain(delayMs: Long = 8_000) {
        cancelChain()
        if (!active || config.pools.isEmpty()) return
        val task = object : Runnable {
            override fun run() {
                if (chainTask !== this) return
                chainTask = null
                if (!isAttached() || runState != "idle") return
                if (!isDragging()) {
                    val before = chainAction
                    rollNextChainAction()
                    if (chainAction != before) onActionChanged()
                }
                scheduleChain(delayMs)
            }
        }
        chainTask = task
        schedule(task, delayMs)
    }

    fun cancelChain() {
        chainTask?.let(cancel)
        chainTask = null
    }

    fun scheduleMove() {
        cancelMove()
        if (!active || !config.moveEnabled) return
        val delay = (config.moveIntervalMs * (0.5 + random())).toLong()
        val task = object : Runnable {
            override fun run() {
                if (moveTask !== this) return
                moveTask = null
                if (!isAttached()) return
                if (canMove()) onMoveRequested()
                scheduleMove()
            }
        }
        moveTask = task
        schedule(task, delay)
    }

    fun cancelMove() {
        moveTask?.let(cancel)
        moveTask = null
    }

    fun release() {
        active = false
        cancelChain()
        cancelMove()
        clearManualAction()
        chainAction = ""
        chainIsTurn = false
        chainRepeats = 0
        presentation = Presentation()
    }

    companion object {
        fun deriveRunState(running: Boolean, activity: String, text: String): String {
            if (!running) return "idle"
            val act = activity.lowercase()
            if (text.contains("等待") || text.contains("确认") ||
                act.contains("approval") || act.contains("wait") || act.contains("input")) return "waiting"
            if (act.contains("tool") || act.contains("command") ||
                text.startsWith("正在运行") || text.contains("执行了")) return "working"
            return "thinking"
        }
    }
}
