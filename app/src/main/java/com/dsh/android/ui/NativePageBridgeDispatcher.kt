package com.dsh.android.ui

import com.dsh.android.web.AnnotationSource
import com.dsh.android.web.PageBridge

/** 只装配领域能力，不拥有窗口、配置、队列或业务状态。 */
internal class NativePageBridgeDispatcher(
    private val activity: ActivityPageBridgeActions,
    private val bubble: BubblePageBridgeActions,
    private val privilege: PrivilegePageBridgeActions,
) : PageBridge, AnnotationSource {
    override fun onStatus(json: String): Unit = activity.onStatus(json)
    override fun onImeWillShow(): Unit = activity.onImeWillShow()
    override fun startWirelessPairing(): Unit = privilege.startWirelessPairing()
    override fun cancelWirelessPairing(): Unit = privilege.cancelWirelessPairing()
    override fun pairingNotificationsEnabled(): Boolean = privilege.pairingNotificationsEnabled()
    override fun onUiReady(): Unit = activity.onUiReady()
    override fun reportWebError(message: String): Unit = activity.reportWebError(message)
    override fun switchToTcpMode(): Unit = activity.switchToTcpMode()
    override fun reloadWebUi(): Unit = activity.reloadWebUi()
    override fun bubbleState(): String = bubble.bubbleState()
    override fun bubbleSetEnabled(enabled: Boolean): Unit = bubble.bubbleSetEnabled(enabled)
    override fun bubbleSetImeFling(enabled: Boolean): Unit = bubble.bubbleSetImeFling(enabled)
    override fun bubbleSetShake(enabled: Boolean): Unit = bubble.bubbleSetShake(enabled)
    override fun bubbleReportBalance(json: String): Unit = bubble.bubbleReportBalance(json)
    override fun bubbleBadgeConfig(): String = bubble.bubbleBadgeConfig()
    override fun bubbleTapRefreshSeq(): Int = bubble.bubbleTapRefreshSeq()
    override fun writeClipboard(text: String): Boolean = activity.writeClipboard(text)
    override fun pageDiag(text: String): Unit = activity.pageDiag(text)
    override fun bubbleSetThrow(enabled: Boolean): Unit = bubble.bubbleSetThrow(enabled)
    override fun bubbleRequestOverlay(): Unit = activity.bubbleRequestOverlay()
    override fun openAiBrowser(): Unit = activity.openAiBrowser()
    override fun aiBrowserLayout(json: String): Unit = activity.aiBrowserLayout(json)
    override fun aiBrowserDetach(): Unit = activity.aiBrowserDetach()
    override fun aiBrowserState(): String = activity.aiBrowserState()
    override fun aiAnnotateResult(): String = activity.aiAnnotateResult()
    override fun aiAnnotateReport(message: String): Unit = activity.aiAnnotateReport(message)
    override fun keyboardAvoidState(): String = activity.keyboardAvoidState()
    override fun keyboardAvoidSetEnabled(enabled: Boolean): Boolean = activity.keyboardAvoidSetEnabled(enabled)
    override fun keyboardAvoidRequestPermission(): Unit = activity.keyboardAvoidRequestPermission()
    override fun onTranscript(json: String): Unit = bubble.onTranscript(json)
    override fun bubbleApply(json: String): Boolean = bubble.bubbleApply(json)
    override fun bubbleAppearance(): String = bubble.bubbleAppearance()
    override fun rootState(): String = privilege.rootState()
    override fun rootRecheck(): Unit = privilege.rootRecheck()
    override fun shizukuState(): String = privilege.shizukuState()
    override fun shizukuRequestPermission(): Unit = activity.shizukuRequestPermission()
    override fun shizukuOpenManager(): Unit = activity.shizukuOpenManager()
    override fun shizukuOpenDownload(): Unit = activity.shizukuOpenDownload()
    override fun openLogs(): Unit = activity.openLogs()
    override fun logSummary(): String = activity.logSummary()
    override fun uploadNativeBegin(taskId: String, url: String, headersJson: String): Boolean = activity.uploadNativeBegin(taskId, url, headersJson)
    override fun uploadNativeWrite(taskId: String, base64: String): Boolean = activity.uploadNativeWrite(taskId, base64)
    override fun uploadNativeFinish(taskId: String): Unit = activity.uploadNativeFinish(taskId)
    override fun uploadNativeAbort(taskId: String): Unit = activity.uploadNativeAbort(taskId)
    override fun peekAnnotation(): String = activity.peekAnnotation()
    override fun acknowledgeAnnotation(annotationId: String) = activity.acknowledgeAnnotation(annotationId)
}
