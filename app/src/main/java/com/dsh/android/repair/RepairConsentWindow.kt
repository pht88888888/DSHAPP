package com.dsh.android.repair

/** A sliding idle lease is valid only within the same boot and pair of app installs. */
internal data class RepairConsentWindow(val until: Long, val boot: Int, val install: String) {
    fun accepts(now: Long, currentBoot: Int, currentInstall: String): Boolean =
        boot == currentBoot && install == currentInstall && now >= 0 && until > now && until - now <= IDLE_TIMEOUT_MS

    /** Activity may extend a live grant; expired/replaced grants can never be resurrected. */
    fun renew(now: Long, currentBoot: Int, currentInstall: String): RepairConsentWindow? =
        if (accepts(now, currentBoot, currentInstall)) copy(until = now + IDLE_TIMEOUT_MS) else null

    companion object { const val IDLE_TIMEOUT_MS = com.dsh.android.repair.contract.RepairProtocol.IDLE_TIMEOUT_MS }
}
