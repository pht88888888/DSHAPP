package com.dsh.android.repair.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dsh.android.repair.assistant.runtime.RepairEngineRuntime
import com.dsh.android.repair.assistant.runtime.RepairRuntimePolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Official DSH Web GUI in its own APK/Node/profile. Native diagnostics remain the fallback. */
class RepairAgentActivity : Activity() {
    private val closing = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private var runtime: RepairEngineRuntime? = null
    private var bridge: RepairAgentBridge? = null
    private var web: WebView? = null
    private lateinit var status: TextView
    private var port = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20, 24, 32)
        window.navigationBarColor = Color.rgb(20, 24, 32)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = TextView(this).apply {
            setTextColor(Color.rgb(35, 40, 48))
            textSize = 14f
            setPadding(20, 12, 8, 12)
            text = getString(R.string.repair_agent_preparing)
        }
        header.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = getString(R.string.repair_agent_close)
            setOnClickListener { finish() }
        })
        layout.addView(header)
        layout.addView(TextView(this).apply {
            text = getString(R.string.repair_agent_privacy)
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(20, 0, 20, 12)
        })
        setContentView(layout)
        val target = intent.getStringExtra(EXTRA_TARGET)
        val grant = intent.getStringExtra(EXTRA_GRANT)
        val mailboxPath = intent.getStringExtra(EXTRA_MAILBOX)
        if (target !in setOf("com.dsh.android", "com.dsh.webview") || grant.isNullOrBlank() || mailboxPath.isNullOrBlank()) {
            status.setText(R.string.repair_agent_invalid_session)
            return
        }
        val mailbox = File(mailboxPath)
        val allowed = File(filesDir, "repair-mailboxes").canonicalFile
        if (!mailbox.isDirectory || mailbox.absoluteFile != mailbox.canonicalFile ||
            !mailbox.canonicalPath.startsWith(allowed.path + File.separator)) {
            status.setText(R.string.repair_agent_invalid_session)
            return
        }
        try {
            bridge = RepairAgentBridge.attach(this, target!!, grant, mailbox.absolutePath)
        } catch (_: Exception) {
            status.setText(R.string.repair_agent_invalid_session)
            return
        }
        val view = WebView(this)
        web = view
        layout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        configureWebView(view)
        val major = WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
        val owner = RepairEngineRuntime(applicationContext)
        runtime = owner
        worker.execute {
            try {
                val ready = owner.start(target!!, mailbox, major) { message ->
                    runOnUiThread { if (!closing.get()) status.text = message }
                }
                runOnUiThread {
                    if (!closing.get()) {
                        port = ready.port
                        status.text = getString(R.string.repair_agent_ready)
                        // Authenticated URL is memory-only: no logging, state Bundle, clipboard or intents.
                        view.loadUrl(ready.url)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!closing.get()) status.setText(R.string.repair_agent_start_failed)
                }
            }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        WebView.setWebContentsDebuggingEnabled(false)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !RepairRuntimePolicy.allowsNavigation(request.url.toString(), port)

            @Deprecated("Required for older WebView navigation callbacks")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !RepairRuntimePolicy.allowsNavigation(url, port)

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (RepairRuntimePolicy.allowsResource(request.url.toString(), port)) null
                else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        view.webChromeClient = object : WebChromeClient() {
            // Official client console messages may carry credentials; do not send to native logs.
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        view.setDownloadListener { _, _, _, _, _ ->
            status.setText(R.string.repair_agent_download_blocked)
        }
    }

    override fun onResume() {
        super.onResume()
        bridge?.markActive()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        bridge?.markActive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask must never rebind the running model to another target/grant.
        status.setText(R.string.repair_agent_existing_session)
    }

    @Deprecated("Activity result bridge used for explicit target authorization")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (bridge?.onActivityResult(requestCode, resultCode, data) != true) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        closing.set(true)
        // Revoke dispatcher access first; then stop independent Node and release WebView.
        bridge?.close()
        bridge = null
        runtime?.close()
        runtime = null
        worker.shutdownNow()
        web?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TARGET = "repair_agent_target"
        private const val EXTRA_MAILBOX = "repair_agent_mailbox"
        private const val EXTRA_GRANT = "repair_agent_grant"

        fun createIntent(context: Context, targetPackage: String, mailboxDirectoryAbsolute: String, grantId: String): Intent =
            Intent(context, RepairAgentActivity::class.java)
                .putExtra(EXTRA_TARGET, targetPackage)
                .putExtra(EXTRA_MAILBOX, mailboxDirectoryAbsolute)
                .putExtra(EXTRA_GRANT, grantId)
    }
}
