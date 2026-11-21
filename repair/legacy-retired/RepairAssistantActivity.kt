package com.dsh.android.repair.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.dsh.android.repair.assistant.RepairClient.Failure
import com.dsh.android.repair.assistant.RepairClient.Reply
import com.dsh.android.repair.contract.RepairProtocol
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class RepairAssistantActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    // Binder cannot be reliably interrupted; cap outstanding workers and never wait on the UI thread.
    private val worker = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private var pending: Future<*>? = null
    private var generation = 0L
    private var foreground = false
    private var connected = false
    private var authorized = false
    private var target = RepairProtocol.TARGET_PACKAGES.first()
    private lateinit var client: RepairClient
    private lateinit var status: TextView
    private lateinit var reportHeading: TextView
    private lateinit var report: TextView
    private lateinit var refresh: Button
    private lateinit var authorize: Button
    private lateinit var diagnose: Button
    private lateinit var launch: Button
    private lateinit var startAgentButton: Button
    private lateinit var startSessionButton: Button
    private var grantId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = RepairClient(this)
        savedInstanceState?.getString(STATE_TARGET)?.takeIf { it in RepairProtocol.TARGET_PACKAGES }?.let {
            target = it
        }
        buildView()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refreshStatus()
    }

    override fun onPause() {
        foreground = false
        invalidateRequest()
        clearReport()
        super.onPause()
    }

    override fun onDestroy() {
        invalidateRequest()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only remember the chosen package. Re-check authorization after any recreation.
        outState.putString(STATE_TARGET, target)
    }

    private fun buildView() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = dp(20)
            setPadding(margin, margin, margin, margin)
        }
        scroll.addView(content)
        content.addView(text(R.string.assistant_name, 27f).apply { isAccessibilityHeadingCompat() })
        content.addView(text(R.string.intro, 16f))
        content.addView(text(R.string.read_only_notice, 14f))
        content.addView(text(R.string.choose_target, 18f).apply { isAccessibilityHeadingCompat() })
        val choices = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        RepairProtocol.TARGET_PACKAGES.forEach { pkg ->
            val choice = RadioButton(this).apply {
                id = View.generateViewId()
                text = targetLabel(pkg)
                minHeight = dp(48)
            }
            choices.addView(choice)
            if (pkg == target) choices.check(choice.id)
            choice.setOnClickListener {
                if (pkg != target) {
                    target = pkg
                    refreshStatus()
                }
            }
        }
        content.addView(choices)
        status = TextView(this).apply {
            textSize = 16f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(status)
        startAgentButton = button(content, R.string.start_repair_agent, ::openRepairAgent)
        refresh = button(content, R.string.refresh, ::refreshStatus)
        authorize = button(content, R.string.authorize, ::openAuthorization)
        diagnose = button(content, R.string.diagnose, ::requestDiagnosis)
        startSessionButton = button(content, R.string.start_session, ::startRepairSession)
        launch = button(content, R.string.open_target, ::openTarget)
        reportHeading = text(R.string.diagnosis_heading, 18f).apply { isAccessibilityHeadingCompat() }
        content.addView(reportHeading)
        report = TextView(this).apply {
            textSize = 15f
            // The report is bounded plain text, not HTML, URLs, or executable actions.
            autoLinkMask = 0
            linksClickable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(report)
        clearReport()
        updateButtons(false)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets -> applyInsets(view, insets) }
        scroll.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(view: View, insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        }
        return insets
    }

    private fun TextView.isAccessibilityHeadingCompat() {
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun text(id: Int, size: Float) = TextView(this).apply {
        setText(id)
        textSize = size
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(parent: LinearLayout, id: Int, clicked: () -> Unit) = Button(this).apply {
        setText(id)
        setOnClickListener { clicked() }
        parent.addView(this)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun targetLabel(pkg: String = target): String = getString(
        if (pkg == RepairProtocol.TARGET_PACKAGES.first()) R.string.target_system else R.string.target_gecko,
    )

    private fun refreshStatus() {
        connected = false
        authorized = false
        grantId = null
        clearReport()
        query(R.string.checking, RepairProtocol.METHOD_STATUS)
    }

    private fun requestDiagnosis() {
        if (connected && authorized) {
            clearReport()
            grantId?.let { query(R.string.diagnosing, RepairProtocol.METHOD_DIAGNOSTICS, it) }
        }
    }

    private fun query(message: Int, method: String, grant: String? = null) {
        execute(message, { client, pkg -> client.query(pkg, method, grant) }) { reply ->
            when (reply) {
                is Reply.Status -> showState(reply.state)
                is Reply.Diagnosis -> {
                    showState(reply.state)
                    report.text = reply.report
                    report.visibility = View.VISIBLE
                    reportHeading.visibility = View.VISIBLE
                }
                is Reply.Unauthorized -> {
                    showState(reply.state)
                    status.setText(if (reply.state.authorized) R.string.retry_diagnosis else R.string.authorization_expired)
                }
                is Reply.Failed -> {
                    connected = false
                    authorized = false
                    status.text = failureText(reply.reason)
                }
            }
        }
    }

    private fun showState(state: RepairClient.TargetState) {
        connected = true
        authorized = state.authorized
        grantId = if (authorized) state.grantId ?: grantId else null
        status.text = getString(
            R.string.connected, targetLabel(), state.versionName.ifBlank { getString(R.string.unknown_version) },
            state.versionCode, getString(if (authorized) R.string.is_authorized else R.string.not_authorized),
        )
    }

    private fun failureText(reason: Failure): String = getString(
        when (reason) {
            Failure.NOT_INSTALLED -> R.string.not_installed
            Failure.NO_PROVIDER -> R.string.provider_missing
            Failure.NO_REPLY -> R.string.no_reply
            Failure.WRONG_SIGNATURE -> R.string.wrong_signature
            Failure.ACCESS_DENIED -> R.string.access_denied
            Failure.INCOMPATIBLE -> R.string.incompatible_reply
            Failure.CONNECTION_FAILED -> R.string.connection_failed
        },
        targetLabel(),
    )

    @Suppress("DEPRECATION")
    private fun openAuthorization() {
        execute(R.string.opening, { client, pkg -> client.checkAuthorization(pkg) }) { failure ->
            clearReport()
            if (failure != null) {
                status.text = failureText(failure)
            } else {
                val intent = Intent().setComponent(ComponentName(target, RepairProtocol.AUTH_ACTIVITY))
                try {
                    // Do not use NEW_TASK: the target authorization page verifies the framework caller.
                    startActivityForResult(intent, AUTH_REQUEST)
                } catch (_: ActivityNotFoundException) {
                    status.text = getString(R.string.cannot_open_authorization, targetLabel())
                } catch (_: SecurityException) {
                    status.text = failureText(Failure.ACCESS_DENIED)
                }
            }
        }
        // Authorization is deliberately refreshed in onResume, not inferred from result extras.
    }

    private fun openRepairAgent() {
        val grant = grantId ?: return
        RepairSessionService.ensureChannel(this)
        if (!RepairSessionService.canNotify(getSystemService(android.app.NotificationManager::class.java))) {
            startRepairSession()
            return
        }
        val mailbox = java.io.File(filesDir, "repair-mailboxes/${java.util.UUID.randomUUID()}")
        check(mailbox.mkdirs())
        startRepairSession()
        startActivity(RepairAgentActivity.createIntent(this, target, mailbox.absolutePath, grant))
    }

    private fun startRepairSession() {
        val grant = grantId ?: return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        RepairSessionService.ensureChannel(this)
        if (!RepairSessionService.canNotify(manager)) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), SESSION_NOTIFY_REQUEST)
            status.setText(R.string.session_notification_required)
            return
        }
        val intent = Intent(this, RepairSessionService::class.java)
            .setAction(RepairSessionService.ACTION_START)
            .putExtra(RepairSessionService.EXTRA_TARGET, target)
            .putExtra(RepairProtocol.EXTRA_GRANT_ID, grant)
        try {
            startForegroundService(intent)
            status.setText(R.string.session_start_requested)
        } catch (_: RuntimeException) {
            status.setText(R.string.session_start_failed)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (foreground) RepairSessionService.noteInteraction(target, grantId)
    }

    private fun openTarget() {
        execute(R.string.opening, { client, pkg -> client.launchIntent(pkg) }) { intent ->
            if (intent == null) {
                status.text = getString(R.string.cannot_open_target, targetLabel())
            } else {
                try {
                    startActivity(intent)
                    // If it cannot show a screen (e.g. immediate crash), allow another action.
                    status.text = getString(R.string.open_requested, targetLabel())
                } catch (_: ActivityNotFoundException) {
                    status.text = getString(R.string.cannot_start_target, targetLabel())
                } catch (_: SecurityException) {
                    status.text = getString(R.string.cannot_start_target, targetLabel())
                }
            }
        }
    }

    private fun <T> execute(message: Int, work: (RepairClient, String) -> T, done: RepairAssistantActivity.(T) -> Unit) {
        if (!foreground) return
        invalidateRequest()
        val request = generation
        val pkg = target
        status.text = getString(message, targetLabel(pkg))
        updateButtons(true)

        val host = WeakReference(this)
        val callbackHandler = main
        val background = client
        try {
            pending = worker.submit {
                val result = try {
                    Result.success(work(background, pkg))
                } catch (e: RuntimeException) {
                    Result.failure(e)
                }
                callbackHandler.post {
                    val owner = host.get() ?: return@post
                    if (!owner.isCurrent(request, pkg)) return@post
                    owner.main.removeCallbacksAndMessages(null)
                    owner.pending = null
                    result.fold(
                        onSuccess = { owner.done(it) },
                        onFailure = { owner.status.text = owner.getString(R.string.connection_failed, owner.targetLabel()) },
                    )
                    owner.updateButtons(false)
                }
            }
            // This invalidates only our UI request. Binder may still return afterward.
            main.postDelayed({
                if (isCurrent(request, pkg)) {
                    invalidateRequest()
                    status.text = getString(R.string.request_timeout, targetLabel())
                    updateButtons(false)
                }
            }, REQUEST_TIMEOUT_MS)
        } catch (_: RejectedExecutionException) {
            status.text = getString(R.string.requests_busy, targetLabel())
            updateButtons(false)
        }
    }

    private fun isCurrent(request: Long, pkg: String): Boolean =
        foreground && generation == request && target == pkg

    private fun invalidateRequest() {
        generation++
        pending?.cancel(true)
        pending = null
        worker.purge()
        main.removeCallbacksAndMessages(null)
    }

    private fun updateButtons(busy: Boolean) {
        refresh.isEnabled = !busy
        authorize.isEnabled = !busy
        diagnose.isEnabled = !busy && connected && authorized
        startAgentButton.isEnabled = !busy && connected && authorized && grantId != null
        startSessionButton.isEnabled = !busy && connected && authorized && grantId != null
        launch.isEnabled = !busy
    }

    private fun clearReport() {
        report.text = ""
        report.visibility = View.GONE
        reportHeading.visibility = View.GONE
    }

    private companion object {
        const val STATE_TARGET = "repair.assistant.target"
        const val AUTH_REQUEST = 1
        const val SESSION_NOTIFY_REQUEST = 2
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
