package com.dsh.android.bubble

import com.dsh.android.engine.EngineStatus

/** 应用装配提供引擎当前快照，悬浮球不依赖 Service 的实现。 */
fun interface BubbleStatusSource {
    fun currentStatus(): EngineStatus?
}

/** Activity 自身强持此端口，进程控制器只保存弱引用。 */
interface BubblePanelActions {
    fun sendText(text: String): Boolean
    fun stopRun(): Boolean
    fun openApp()
}
