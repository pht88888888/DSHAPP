package com.dsh.android.repair.assistant

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.dsh.android.repair.RepairAssistantHost
import com.dsh.android.repair.contract.RepairProtocol as Protocol
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Adds deliberate native authorization to the original UI; progress/errors belong to its settings page. */
class RepairHostController(private val activity: Activity) : RepairAssistantHost {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2))
    private val client = RepairClient(activity)
    private val root = File(activity.filesDir, "repair-mailboxes").apply { mkdirs() }
    private val status = RepairConnectionState(root)
    private val control = File(root, "control-request.json")
    private var bridge: RepairAgentBridge? = null
    private var dialog: AlertDialog? = null
    private var foreground = false
    private var closed = false
    private var generation = 0L
    private var querying: Pair<String, Boolean>? = null
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !foreground) return
            if (control.isFile) {
                // Fixed zero-argument UI intent only; neither model nor UI can supply a target/grant.
                val action = runCatching {
                    require(control.length() in 1..256 && !java.nio.file.Files.isSymbolicLink(control.toPath()))
                    JSONObject(control.readText()).let { value ->
                        require(value.keys().asSequence().toSet() == setOf("action"))
                        value.getString("action").takeIf { it in setOf("authorize", "disconnect", "validateUi") }
                    }
                }.getOrNull()
                runCatching { java.nio.file.Files.deleteIfExists(control.toPath()) }
                when (action) {
                    "authorize" -> open(fromOverlay = false)
                    "disconnect" -> { overlayFeedback = false; pendingFeedback = null; disconnect() }
                    "validateUi" -> bridge?.takeIf { it.isOpen() }?.validateUi()
                }
            }
            main.postDelayed(this, 300L)
        }
    }

    init {
        bridge = RepairAgentBridge.current()
        if (bridge == null && pendingAuthorization == null && !disconnecting) {
            runCatching { status.initializeDisconnected() }
        }
    }

    override fun open() = open(fromOverlay = true)

    private fun open(fromOverlay: Boolean) {
        if (closed || !foreground || dialog != null || querying != null || pendingAuthorization != null) return
        if (disconnecting) return
        overlayFeedback = fromOverlay
        pendingFeedback = null
        val active = bridge?.takeIf { it.isOpen() }
        if (active != null) {
            dialog = AlertDialog.Builder(activity)
                .setTitle("已连接 ${label(active.target)}")
                .setMessage("结束连接不影响普通对话。若要切换目标，请先结束连接，再明确选择新目标；新目标需要新对话，旧对话不能自动获取新授权。")
                .setPositiveButton("保持连接", null)
                .setNegativeButton("结束连接") { _, _ -> dismiss(); disconnect() }
                .create().also(::showDialog)
            return
        }
        val available = Protocol.TARGET_PACKAGES.map { client.availability(it) }
        dialog = AlertDialog.Builder(activity)
            .setTitle("连接要修复的 DSH")
            .setItems(available.map { entry ->
                val version = entry.version?.let { " · $it" }.orEmpty()
                "${label(entry.target)}$version · ${entry.failure?.let(::failureDetail) ?: "可连接"}"
            }.toTypedArray()) { _, index ->
                dialog = null
                val entry = available[index]
                if (entry.failure != null) showFailure(entry.target, entry.failure) else select(entry.target, true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create().also(::showDialog)
    }

    private fun showDialog(candidate: AlertDialog) {
        candidate.setOnDismissListener { if (dialog === candidate) dialog = null }
        candidate.show()
    }

    private fun select(target: String, allowAuthorize: Boolean) {
        querying = target to allowAuthorize
        val request = ++generation
        publish("checking", target, "正在检查 ${label(target)} 的维护通道…")
        execute {
            val reply = client.query(target, Protocol.METHOD_STATUS)
            main.post {
                if (!valid(request)) return@post
                querying = null
                when (reply) {
                    is RepairClient.Reply.Status -> {
                        val grant = reply.state.grantId
                        if (reply.state.authorized && grant != null && grant != blockedGrant) connect(target, grant)
                        else if (reply.state.authorized && grant == blockedGrant) {
                            publish("error", target, "上次授权尚未确认撤销。请在原应用维护页撤销后再连接。")
                        } else if (allowAuthorize) authorize(target)
                        else publish("disconnected", target, "目标未开启授权。可再次点击连接并在原应用中确认。")
                    }
                    is RepairClient.Reply.Failed -> {
                        // Some OEMs refuse to cold-start the private provider from a background
                        // association. One explicit native consent Activity can start that process.
                        // This never grants access: trust is rechecked and target confirmation is
                        // still required. After its result allowAuthorize is false (no retry loop).
                        if (allowAuthorize && reply.reason == RepairClient.Failure.NO_REPLY) {
                            val availability = client.availability(target)
                            if (availability.failure == null) authorize(target)
                            else showFailure(target, availability.failure)
                        } else showFailure(target, reply.reason)
                    }
                    else -> publish("error", target, "目标未返回有效的维护状态，请重试。")
                }
            }
        }
        main.postDelayed({
            if (valid(request) && querying != null) {
                generation++
                querying = null
                publish("error", target, "连接超时，请确认原 DSH 已更新并稍后重试。")
            }
        }, 10_000L)
    }

    private fun authorize(target: String) {
        pendingAuthorization = target
        publish("authorizing", target, "请在 ${label(target)} 的原生维护页明确确认授权；返回后将继续连接。")
        runCatching {
            @Suppress("DEPRECATION")
            val intent = Intent().setComponent(ComponentName(target, Protocol.AUTH_ACTIVITY))
            status.themeSnapshot()?.let { intent.putExtra("repair_theme", it) }
            activity.startActivityForResult(intent, AUTH_REQUEST)
        }.onFailure {
            pendingAuthorization = null
            publish("error", target, "无法打开目标维护授权页，请确认原应用已更新。")
        }
    }

    private fun connect(target: String, grant: String) {
        publish("connecting", target, "正在建立受限修复连接…")
        RepairSessionService.ensureChannel(activity)
        if (!RepairSessionService.canNotify(activity.getSystemService(NotificationManager::class.java))) {
            publish("error", target, "连接需要常驻通知。请在系统设置中允许本应用通知后重试。")
            return
        }
        runCatching {
            check(RepairAgentBridge.current() == null) { "Connection already exists" }
            val mailbox = File(root, "current").apply { mkdirs() }
            val attached = RepairAgentBridge.attach(activity, target, grant, mailbox.canonicalPath)
            bridge = attached
            activity.startForegroundService(Intent(activity, RepairSessionService::class.java)
                .setAction(RepairSessionService.ACTION_START)
                .putExtra(RepairSessionService.EXTRA_TARGET, target)
                .putExtra(Protocol.EXTRA_GRANT_ID, grant))
            attached.markActive()
            publish("connected", target, "已连接 ${label(target)}。本次维修已授权；普通修改可直接提交，特殊敏感操作仍需确认。先后台验证，最后由你点击验证原应用界面。", attached.binding)
        }.onFailure {
            bridge?.close()
            bridge = null
            publish("error", target, "无法建立修复连接，请重试。普通 DSH 对话不受影响。")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (bridge?.onActivityResult(requestCode, resultCode, data) == true) return true
        if (requestCode != AUTH_REQUEST) return false
        val target = pendingAuthorization ?: return true
        pendingAuthorization = null
        if (resultCode != Activity.RESULT_OK) {
            publish("disconnected", target, "未开启修复授权。普通 DSH 对话仍可正常使用。")
        } else {
            // Result may precede resume: queue the query rather than dropping the first click.
            pendingQuery = target to false
            if (foreground) reconnectPending()
        }
        return true
    }

    private fun reconnectPending() {
        pendingQuery?.let { pendingQuery = null; select(it.first, it.second) }
    }

    override fun onStart() {
        if (closed) return
        foreground = true
        bridge?.takeIf { it.isOpen() }?.rebind(activity)
        main.removeCallbacks(poll)
        main.post(poll)
        reconnectPending()
        showPendingFeedback()
    }

    override fun onPause() {
        foreground = false
        bridge?.detach(activity)
        main.removeCallbacks(poll)
        querying?.let { pendingQuery = it; querying = null; generation++ }
    }

    override fun onUserInteraction() { bridge?.markActive() }

    private fun disconnect() {
        if (disconnecting) return
        generation++
        querying = null
        pendingQuery = null
        pendingAuthorization = null
        dismiss()
        val active = (bridge ?: RepairAgentBridge.current())?.takeIf { it.isOpen() }
        val target = active?.target
        val grant = active?.grant
        active?.close()
        bridge = null
        activity.stopService(Intent(activity, RepairSessionService::class.java))
        if (target == null || grant == null) {
            publish("disconnected", null, "修复连接已结束。普通对话不受影响。")
            return
        }
        disconnecting = true
        publish("disconnecting", target, "正在撤销目标维护授权…")
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish: (Boolean) -> Unit = { revoked ->
            if (completed.compareAndSet(false, true)) main.post {
                disconnecting = false
                if (!revoked) blockedGrant = grant
                publish(if (revoked) "disconnected" else "error", target,
                    if (revoked) "修复连接及授权已结束。普通对话不受影响。"
                    else "本地连接已结束，但无法确认原应用授权已撤销。请在原应用维护页撤销后再连接。")
            }
        }
        try { worker.execute { finish(runCatching { client.revoke(target, grant) }.getOrDefault(false)) } }
        catch (_: RejectedExecutionException) { finish(false) }
        main.postDelayed({ finish(false) }, 10_000L)
    }

    override fun close() {
        closed = true
        foreground = false
        generation++
        querying?.let { pendingQuery = it; querying = null }
        main.removeCallbacks(poll)
        dismiss()
        // Activity destruction/rotation is not a user disconnect. The notified service owns the lease.
        bridge?.detach(activity)
        bridge = null
        worker.shutdownNow()
    }

    private fun execute(action: () -> Unit) {
        try { worker.execute(action) } catch (_: RejectedExecutionException) {
            val target = querying?.first
            querying = null
            publish("error", target, "维护通道仍在处理先前请求，请稍后重试。")
        }
    }
    private fun valid(request: Long) = !closed && foreground && request == generation
    private fun label(target: String) = if (target == "com.dsh.android") "System" else "Gecko"
    private fun dismiss() { dialog?.dismiss(); dialog = null }
    private fun publish(phase: String, target: String?, message: String, binding: String? = null) {
        runCatching { status.publish(phase, target, message, binding) }
        // A native-only error overlay cannot see the Web settings projection. Give that
        // deliberate action one terminal result; settings-originated actions stay inline.
        if (overlayFeedback && phase in setOf("connected", "disconnected", "error", "expired")) {
            overlayFeedback = false
            pendingFeedback = message
            showPendingFeedback()
        }
    }
    private fun showPendingFeedback() {
        if (closed || !foreground || activity.isFinishing || dialog != null) return
        val text = pendingFeedback ?: return
        pendingFeedback = null
        dialog = AlertDialog.Builder(activity).setTitle("修复连接状态").setMessage(text)
            .setPositiveButton("知道了", null).create().also(::showDialog)
    }
    private fun failureDetail(reason: RepairClient.Failure): String = when (reason) {
        RepairClient.Failure.NOT_INSTALLED -> "未安装"
        RepairClient.Failure.NO_PROVIDER, RepairClient.Failure.INCOMPATIBLE -> "版本尚不支持维护连接，请更新"
        RepairClient.Failure.WRONG_SIGNATURE -> "签名不匹配，勿卸载原应用"
        RepairClient.Failure.ACCESS_DENIED -> "拒绝连接，请检查版本及授权"
        else -> "暂时无法连接，请稍后重试"
    }
    private fun showFailure(target: String, reason: RepairClient.Failure) {
        publish("error", target, "${label(target)}：${failureDetail(reason)}。普通 DSH 对话仍可正常使用。")
    }
    private companion object {
        const val AUTH_REQUEST = 7302
        // Process-local lifecycle continuity only. No durable credential or automatic authorization.
        var pendingAuthorization: String? = null
        var pendingQuery: Pair<String, Boolean>? = null
        var disconnecting = false
        var blockedGrant: String? = null
        var overlayFeedback = false
        var pendingFeedback: String? = null
    }
}
