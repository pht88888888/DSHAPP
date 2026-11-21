package com.dsh.android.repair.assistant.runtime

import java.net.URI

/** Pure launch/navigation policy; never renders or logs the authenticated URL. */
internal object RepairRuntimePolicy {
    private val readyLine = Regex("""^dsh web: (http://127\.0\.0\.1:([0-9]{1,5})/\?token=[A-Za-z0-9_-]{16,})\s*$""")

    fun readyUrl(line: String): String? {
        val match = readyLine.matchEntire(line) ?: return null
        val port = match.groupValues[2].toIntOrNull() ?: return null
        return match.groupValues[1].takeIf { port in 1..65535 }
    }

    fun allowsNavigation(url: String, expectedPort: Int): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host
        uri.scheme == "http" && host == "127.0.0.1" &&
            uri.port == expectedPort && uri.rawUserInfo == null && uri.rawFragment == null &&
            expectedPort in 1..65535
    }.getOrDefault(false)

    fun allowsResource(url: String, expectedPort: Int): Boolean =
        allowsNavigation(url, expectedPort) || url == "about:blank" ||
            (url.startsWith("blob:") && allowsNavigation(url.removePrefix("blob:"), expectedPort)) ||
            url.startsWith("data:image/") || url.startsWith("data:font/")
}
