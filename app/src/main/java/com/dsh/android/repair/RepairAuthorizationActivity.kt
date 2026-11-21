package com.dsh.android.repair

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dsh.android.R
import com.dsh.android.repair.contract.RepairProtocol as Protocol
import java.util.concurrent.Executors

/** Signed native task authorization; theme extras are visual, never authorization input. */
class RepairAuthorizationActivity : AppCompatActivity() {
    private var dialogue: AlertDialog? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var palette = RepairNativeTheme.fallback(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (caller != packageName && (caller != Protocol.ASSISTANT_PACKAGE || !assistantInstalled())) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.getBooleanExtra(EXTRA_RESTART, false)) {
            // Owner-app entry: end this repair safely, then boot the app normally to verify it.
            showRestartConfirm()
            return
        }
        worker.execute {
            val consent = RepairAuthorization(this)
            val loaded = RepairNativeTheme.load(this, intent.getStringExtra(RepairNativeTheme.EXTRA))
            val grant = consent.currentGrant()
            val state = runCatching { RepairTaskCoordinator.snapshot(this) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                palette = loaded
                showConsent(caller, grant, state?.optString("phase"))
            }
        }
    }

    private fun showRestartConfirm() {
        val builder = RepairNativeTheme.builder(this, palette,
            getString(R.string.repair_restart_title), getString(R.string.repair_restart_message))
            .setPositiveButton(R.string.repair_restart_confirm, null)
            .setNeutralButton(R.string.repair_restart_only_end, null)
            .setNegativeButton(R.string.repair_restart_cancel, null)
            .setOnCancelListener { finish() }
        dialogue = builder.create().also { dialog ->
            dialog.setOnShowListener {
                RepairNativeTheme.protect(dialog, palette)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { endAndRestart(dialog) }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { requestTask(dialog, false) }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss(); finish() }
            }
            dialog.show()
        }
    }

    private fun endAndRestart(dialog: AlertDialog) {
        RepairNativeTheme.busy(dialog, true)
        worker.execute {
            val accepted = runCatching { RepairTaskCoordinator.end(this) }.getOrNull()?.optBoolean("ok") == true
            // end() resolves asynchronously: wait for a bounded terminal phase.
            var phase: String? = null
            if (accepted) {
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    phase = runCatching { RepairTaskCoordinator.snapshot(this).optString("phase") }.getOrNull()
                    if (phase == "complete" || phase == "recovery_required") break
                    Thread.sleep(200)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    phase == "complete" -> restartTarget()
                    else -> {
                        RepairNativeTheme.busy(dialog, false)
                        Toast.makeText(this, R.string.repair_restart_incomplete, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** The fence is gone; start a fresh instance so normal runtime initialization runs. */
    private fun restartTarget() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Toast.makeText(this, R.string.repair_consent_failed, Toast.LENGTH_LONG).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { startActivity(launch) }.onFailure {
            Toast.makeText(this, R.string.repair_consent_failed, Toast.LENGTH_LONG).show()
            return
        }
        finish()
    }

    private fun showConsent(caller: String?, grant: String?, phase: String?) {
        val app = if (packageName == Protocol.TARGET_PACKAGES[0]) "System" else "Gecko"
        val available = assistantInstalled()
        val message = buildString {
            append(getString(R.string.repair_consent_message))
            if (grant != null) append("\n\n").append(getString(R.string.repair_consent_active))
            if (!phase.isNullOrBlank()) append("\n\n").append(getString(R.string.repair_task_phase, phase.take(80)))
            if (!available) append("\n\n").append(getString(R.string.repair_companion_missing))
        }
        val builder = RepairNativeTheme.builder(this, palette, getString(R.string.repair_consent_title, app), message)
            // Always available, even after a grant expired: coordinator safely resolves stale gates.
            .setNegativeButton(R.string.repair_consent_stop, null)
            .setOnCancelListener { finish() }
        if (available) {
            builder.setPositiveButton(R.string.repair_consent_allow, null)
            if (caller == packageName) builder.setNeutralButton(R.string.repair_open_assistant) { _, _ -> openAssistant() }
        }
        dialogue = builder.create().also { dialog ->
            dialog.setOnShowListener {
                RepairNativeTheme.protect(dialog, palette)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { requestTask(dialog, false) }
                if (available) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { requestTask(dialog, true) }
            }
            dialog.show()
        }
    }

    private fun requestTask(dialog: AlertDialog, begin: Boolean) {
        RepairNativeTheme.busy(dialog, true)
        worker.execute {
            val result = runCatching {
                if (!begin) RepairTaskCoordinator.end(this)
                else {
                    val consent = RepairAuthorization(this)
                    val existing = consent.currentGrant()
                    val grant = if (existing != null && consent.renew(existing)) existing else consent.allow()
                    checkNotNull(grant) { "Unable to save authorization" }
                    RepairTaskCoordinator.begin(this, grant)
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result?.optBoolean("ok") == true) {
                    // This confirms only queuing, never stopping/repair/restore completion.
                    setResult(RESULT_OK)
                    Toast.makeText(this, if (begin) R.string.repair_task_queued else R.string.repair_task_end_queued, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    RepairNativeTheme.busy(dialog, false)
                    Toast.makeText(this, R.string.repair_consent_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openAssistant() {
        val launch = packageManager.getLaunchIntentForPackage(Protocol.ASSISTANT_PACKAGE)
        if (launch == null) Toast.makeText(this, R.string.repair_companion_missing, Toast.LENGTH_LONG).show()
        else runCatching { startActivity(launch) }.onFailure {
            Toast.makeText(this, R.string.repair_consent_failed, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        dialogue?.dismiss()
        // Do not interrupt an accepted consent/end operation if the Activity is recreated.
        worker.shutdown()
        super.onDestroy()
    }

    private fun assistantInstalled(): Boolean =
        packageManager.checkSignatures(packageName, Protocol.ASSISTANT_PACKAGE) == PackageManager.SIGNATURE_MATCH

    companion object {
        /** Owner-app fence only: end repair, then boot normally so the user sees the result. */
        const val EXTRA_RESTART = "restart_verify"
    }
}
