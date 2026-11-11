package com.dsh.android.ui

/** 领域提供者的最小协议；窗口与 Intent 等动作仍由 Activity 实现。 */
internal interface BubblePageBridgeActions {
    fun bubbleState(): String
    fun bubbleSetEnabled(enabled: Boolean): Unit
    fun bubbleSetImeFling(enabled: Boolean): Unit
    fun bubbleSetShake(enabled: Boolean): Unit
    fun bubbleReportBalance(json: String): Unit
    fun bubbleBadgeConfig(): String
    fun bubbleTapRefreshSeq(): Int
    fun bubbleSetThrow(enabled: Boolean): Unit
    fun onTranscript(json: String): Unit
    fun bubbleApply(json: String): Boolean
    fun bubbleAppearance(): String
}

internal interface PrivilegePageBridgeActions {
    fun startWirelessPairing(): Unit
    fun cancelWirelessPairing(): Unit
    fun pairingNotificationsEnabled(): Boolean
    fun rootState(): String
    fun rootRecheck(): Unit
    fun shizukuState(): String
}

internal interface ActivityPageBridgeActions {
    fun onStatus(json: String): Unit
    fun onImeWillShow(): Unit
    fun onUiReady(): Unit
    fun reportWebError(message: String): Unit
    fun switchToTcpMode(): Unit
    fun reloadWebUi(): Unit
    fun writeClipboard(text: String): Boolean
    fun pageDiag(text: String): Unit
    fun bubbleRequestOverlay(): Unit
    fun openAiBrowser(): Unit
    fun aiBrowserLayout(json: String): Unit
    fun aiBrowserDetach(): Unit
    fun aiBrowserState(): String
    fun aiAnnotateResult(): String
    fun aiAnnotateReport(message: String): Unit
    fun keyboardAvoidState(): String
    fun keyboardAvoidSetEnabled(enabled: Boolean): Boolean
    fun keyboardAvoidRequestPermission(): Unit
    fun shizukuRequestPermission(): Unit
    fun shizukuOpenManager(): Unit
    fun shizukuOpenDownload(): Unit
    fun openLogs(): Unit
    fun logSummary(): String
    fun uploadNativeBegin(taskId: String, url: String, headersJson: String): Boolean
    fun uploadNativeWrite(taskId: String, base64: String): Boolean
    fun uploadNativeFinish(taskId: String): Unit
    fun uploadNativeAbort(taskId: String): Unit
    fun peekAnnotation(): String
    fun acknowledgeAnnotation(annotationId: String)
}
