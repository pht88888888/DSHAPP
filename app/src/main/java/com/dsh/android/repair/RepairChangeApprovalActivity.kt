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

/** Sensitive fallback retains a single-candidate native ticket; ordinary task writes bypass this UI. */
class RepairChangeApprovalActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var palette = RepairNativeTheme.fallback(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingPackage != Protocol.ASSISTANT_PACKAGE ||
            packageManager.checkSignatures(packageName, Protocol.ASSISTANT_PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val grant = intent.getStringExtra(Protocol.EXTRA_GRANT_ID) ?: return finish()
        val change = intent.getStringExtra("change_id") ?: return finish()
        val method = intent.getStringExtra(Protocol.EXTRA_OPERATION) ?: return finish()
        if (method !in setOf("apply_changes", "rollback")) return finish()
        val consent = RepairAuthorization(this)
        // Theme preferences and streaming candidate validation stay off the main thread.
        worker.execute {
            val loaded = RepairNativeTheme.load(this, intent.getStringExtra(RepairNativeTheme.EXTRA))
            val captured = runCatching {
                // Streaming hashes must not hold the authorization lock: stop/revoke stays responsive.
                check(consent.currentGrant() == grant)
                val preview = RepairFileAccess.broker(this).previewChange(change, grant)
                check(preview.optBoolean("ok") && consent.currentGrant() == grant)
                val task = RepairTaskCoordinator.snapshot(this)
                val binding = RepairTaskCoordinator.approvalBinding(preview, task)
                check(consent.currentGrant() == grant)
                preview to binding
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                palette = loaded
                if (captured == null) {
                    Toast.makeText(this, R.string.repair_sensitive_failed, Toast.LENGTH_LONG).show()
                    finish()
                } else showApproval(grant, change, method, consent, captured.first, captured.second)
            }
        }
    }

    private fun showApproval(grant: String, change: String, method: String, consent: RepairAuthorization, preview: org.json.JSONObject, binding: String) {
        val message = RepairChangePresentation.message(packageName, method, preview)
        dialog = RepairNativeTheme.builder(this, palette, getString(R.string.repair_sensitive_title), message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.repair_sensitive_allow, null)
            .setOnCancelListener { finish() }
            .create().also { view ->
                view.setOnShowListener {
                    RepairNativeTheme.protect(view, palette)
                    view.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        RepairNativeTheme.busy(view, true)
                        worker.execute {
                            val token = runCatching {
                                // Snapshot before the grant lock: coordinator uses coordinator→grant order.
                                // A concurrent revision change after this check still invalidates consume.
                                val currentTask = RepairTaskCoordinator.snapshot(this)
                                val currentBinding = RepairTaskCoordinator.approvalBinding(preview, currentTask)
                                consent.withGrant(grant) {
                                    // Approve only the displayed preview, never an invisible refreshed candidate.
                                    check(currentBinding == binding)
                                    RepairChangeApproval.issue(grant, method, change, SystemClock.elapsedRealtime(), binding)
                                }
                            }.getOrNull()
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                if (token != null) setResult(RESULT_OK, Intent().putExtra(Protocol.EXTRA_APPROVAL, token))
                                else Toast.makeText(this, R.string.repair_sensitive_failed, Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                    }
                }
                view.show()
            }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        worker.shutdown()
        super.onDestroy()
    }
}
