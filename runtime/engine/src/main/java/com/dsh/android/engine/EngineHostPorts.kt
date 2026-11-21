package com.dsh.android.engine

import java.io.File

/** 应用装配领域命令信箱。引擎决定启动时机，各信箱保持独立生命周期。 */
fun interface EngineCommandMailboxes {
    fun start(home: File)
}

/** Optional APK-owned additions, composed after the normal Android launch setup. */
fun interface EngineLaunchAdditions {
    fun prepare(engineDirectory: File): EngineLaunchExtension
}

/** App-owned execution fence; engine never depends on target repair/provider implementation. */
fun interface EngineExecutionGate {
    fun allowed(): Boolean
    /** Normal hosts retain legacy stop behavior; repair hosts must explicitly fence escalation. */
    fun allowForceStop(): Boolean = true
}

data class EngineLaunchExtension(
    val patches: List<File> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
)
