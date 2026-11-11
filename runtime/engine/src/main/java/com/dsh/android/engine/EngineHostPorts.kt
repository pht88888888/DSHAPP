package com.dsh.android.engine

import java.io.File

/** 应用装配领域命令信箱。引擎决定启动时机，各信箱保持独立生命周期。 */
fun interface EngineCommandMailboxes {
    fun start(home: File)
}

/** 只读活动状态；引擎不依赖 Android Service 或界面实现。 */
fun interface EngineActivitySource {
    fun isBusy(): Boolean
}
