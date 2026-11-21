package com.dsh.android.repair

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

internal class RepairAuthorization(private val context: Context) {
    fun currentGrant() = grant
    fun revoke(expected: String): Boolean { if (grant != expected) return false; grant = null; return true }
    fun <T> withGrant(expected: String, action: () -> T): T { check(grant == expected); return action() }
    companion object { @Volatile var grant: String? = "grant-fixture-12345678" }
}
internal object RepairTaskService { fun start(context: Context) = true; fun stop(context: Context) {} }
internal object RepairEngineQuiescence { fun stopKnownTarget(context: Context) = true; fun canModify(context: Context) = true }
internal object RepairDiagnostics { fun evidence(filesDir: File) = JSONObject() }
internal object RepairRuntimeGate {
    fun stopValidation(context: Context) = true
    fun stopTarget(context: Context) = true
    @Volatile var read: (() -> JSONObject?)? = null
    @Volatile var alive = true
    fun runtimeAlive(context: Context, pid: Int, start: String) = alive
    fun launchEngine(context: Context, task: String, attempt: String) = true
    fun readAttempt(context: Context, attempt: String): JSONObject? = read?.invoke()
}
internal object RepairFileAccess {
    val fixture = Broker()
    fun broker(context: Context) = fixture
    fun roots(context: Context) = mapOf("app" to context.filesDir.parentFile)
}
internal class Broker {
    @Volatile var path = "engine/index.js"
    @Volatile var blockEntered: CountDownLatch? = null
    @Volatile var blockRelease: CountDownLatch? = null
    @Volatile var calls = 0
    fun previewChange(change: String, grant: String) = JSONObject().put("ok", true).put("change_id", change)
        .put("path", path).put("operation", "edit").put("original_sha256", "a".repeat(64))
        .put("candidate_sha256", "b".repeat(64)).put("candidate_content", "const harmless = 1")
        .put("original_mode", 384).put("candidate_mode", 384)
    fun dispatch(method: String, args: JSONObject, grant: String): JSONObject {
        calls++
        if (method == "stage_edit") { blockEntered?.countDown(); blockRelease?.await() }
        return JSONObject().put("ok", true).put("changes", JSONArray())
    }
    fun reconcileForShutdown() = JSONObject().put("ok", true).put("unresolved", 0)
}
