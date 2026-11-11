package com.dsh.android.log

import android.os.Build

/** App-owned build identity supplied before the earliest boot/crash records. */
data class RuntimeAppInfo(
    val versionName: String,
    val versionCode: Int,
    val flavor: String,
    val applicationId: String,
)

/** Safe before initialization; the application installs its identity in attachBaseContext. */
object VersionInfo {
    @Volatile
    private var info = RuntimeAppInfo("", 0, "", "")

    fun initInfo(appInfo: RuntimeAppInfo) {
        info = appInfo
    }

    val versionName: String get() = info.versionName
    val versionCode: Int get() = info.versionCode
    val flavor: String get() = info.flavor

    /** filesDir's parent is the actual installed identity, including repackaged builds. */
    val applicationId: String
        get() = runCatching {
            LogContext.filesDirOrNull()?.parentFile?.name
                ?.takeIf { it.isNotEmpty() }
                ?: info.applicationId
        }.getOrDefault(info.applicationId)

    fun summary(): String {
        val snapshot = info
        return "${snapshot.versionName} (${snapshot.versionCode}) ${snapshot.flavor}"
    }

    fun deviceSummary(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) / ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
}
