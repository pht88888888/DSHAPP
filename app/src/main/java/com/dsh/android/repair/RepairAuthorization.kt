package com.dsh.android.repair

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import com.dsh.android.repair.contract.RepairProtocol

/** Both writers/readers live in :repair. No main-process or runtime state is needed. */
internal class RepairAuthorization(private val context: Context) {
    private val preferences = context.getSharedPreferences("repair-diagnostics-consent", Context.MODE_PRIVATE)

    fun currentGrant(): String? = synchronized(writeLock) {
        val boot = bootCount() ?: return@synchronized null
        val identity = installIdentity() ?: return@synchronized null
        val state = preferences.all
        val grant = state["grant"] as? String ?: return@synchronized null
        if (grant.isBlank() || grant.length > 64) return@synchronized null
        val window = RepairConsentWindow(
            state["until"] as? Long ?: return@synchronized null,
            state["boot"] as? Int ?: return@synchronized null,
            state["install"] as? String ?: return@synchronized null,
        )
        grant.takeIf { window.accepts(SystemClock.elapsedRealtime(), boot, identity) }
    }

    // Admission is short: task coordination serializes publication and cleanup. A revoke
    // immediately prevents new admission, while an already publishing single-file transaction
    // finishes/reconciles before the task releases its fence. Never hold this lock across IO.
    fun <T> withGrant(grant: String, action: () -> T): T {
        synchronized(writeLock) { check(currentGrant() == grant) { "Repair authorization expired or replaced" } }
        return action()
    }

    /** Extend only the exact still-active grant. Revoked/replaced grants cannot be resurrected. */
    fun renew(grantId: String): Boolean = synchronized(writeLock) {
        if (grantId.isBlank() || grantId.length > 64) return@synchronized false
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount() ?: return@synchronized false
        val identity = installIdentity() ?: return@synchronized false
        val state = preferences.all
        if (state["grant"] != grantId) return@synchronized false
        val window = RepairConsentWindow(
            state["until"] as? Long ?: return@synchronized false,
            state["boot"] as? Int ?: return@synchronized false,
            state["install"] as? String ?: return@synchronized false,
        ).renew(now, boot, identity) ?: return@synchronized false
        preferences.edit().putLong("until", window.until).commit()
    }

    fun allow(): String? = synchronized(writeLock) {
        val boot = bootCount() ?: return@synchronized null
        val identity = installIdentity() ?: return@synchronized null
        val grantId = java.util.UUID.randomUUID().toString()
        val saved = preferences.edit()
            .putLong("until", SystemClock.elapsedRealtime() + RepairConsentWindow.IDLE_TIMEOUT_MS)
            .putInt("boot", boot).putString("install", identity).putString("grant", grantId).commit()
        if (!saved) {
            preferences.edit().clear().commit()
            null
        } else grantId
    }

    fun revoke(grantId: String): Boolean = synchronized(writeLock) {
        if (grantId.isBlank() || grantId.length > 64 || preferences.getString("grant", null) != grantId) {
            return@synchronized false
        }
        preferences.edit().clear().commit()
    }

    private fun bootCount(): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installIdentity(): String? = runCatching {
        val pm = context.packageManager
        val target = pm.getPackageInfo(context.packageName, 0)
        val assistant = pm.getPackageInfo(RepairProtocol.ASSISTANT_PACKAGE, 0)
        if (pm.checkSignatures(target.packageName, assistant.packageName) != PackageManager.SIGNATURE_MATCH) null
        else "${target.firstInstallTime}:${target.lastUpdateTime}:${assistant.firstInstallTime}:${assistant.lastUpdateTime}"
    }.getOrNull()

    companion object {
        private val writeLock = Any()
    }
}
