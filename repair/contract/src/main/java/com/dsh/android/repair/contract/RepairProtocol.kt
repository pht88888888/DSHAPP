package com.dsh.android.repair.contract

/** Diagnostic and lease-control Binder contract between the Android target and its repair companion. */
object RepairProtocol {
    const val VERSION = 4
    const val METHOD_RUNTIME_PERMIT = "repair_runtime_permit"
    const val METHOD_RUNTIME_REPORT = "repair_runtime_report"
    const val ASSISTANT_PACKAGE = "com.dsh.fixhelp"
    val TARGET_PACKAGES = listOf("com.dsh.android", "com.dsh.webview")
    const val AUTH_ACTIVITY = "com.dsh.android.repair.RepairAuthorizationActivity"
    const val PROCESS_SUFFIX = ":repair"
    const val METHOD_STATUS = "status"
    const val METHOD_DIAGNOSTICS = "diagnostics"
    const val METHOD_HEARTBEAT = "heartbeat"
    const val METHOD_REVOKE = "revoke"
    const val KEY_PROTOCOL = "protocol"
    const val KEY_TARGET = "target"
    const val KEY_VERSION_NAME = "versionName"
    const val KEY_VERSION_CODE = "versionCode"
    const val KEY_AUTHORIZED = "authorized"
    const val KEY_RESULT = "result"
    const val KEY_REPORT = "report"
    const val KEY_GRANT_ID = "grantId"
    const val EXTRA_GRANT_ID = "grantId"
    const val RESULT_OK = "ok"
    const val RESULT_UNAUTHORIZED = "unauthorized"
    const val MAX_REPORT_CHARS = 4096
    const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L
    const val METHOD_AGENT = "agent"
    const val EXTRA_ARGS = "args"
    const val EXTRA_OPERATION = "operation"
    const val EXTRA_APPROVAL = "approval"
    const val KEY_JSON = "json"
    const val APPROVAL_ACTIVITY = "com.dsh.android.repair.RepairChangeApprovalActivity"
    const val MAX_JSON_BYTES = 768 * 1024

    fun authority(packageName: String) = "$packageName.repair"
    fun permission(packageName: String) = "$packageName.permission.REPAIR"
}
