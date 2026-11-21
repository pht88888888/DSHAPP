package com.dsh.android.repair.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.dsh.android.repair.contract.RepairProtocol

/** Side-effect-free queries. The Activity owns scheduling and discards obsolete replies. */
internal class RepairClient(context: Context) {
    private val app = context.applicationContext
    private val packages = app.packageManager

    data class TargetState(val versionName: String, val versionCode: Long, val authorized: Boolean, val grantId: String?)

    data class Availability(val target: String, val version: String?, val failure: Failure?)

    /** Local package inspection only: never obtains or extends a grant. */
    @Suppress("DEPRECATION")
    fun availability(target: String): Availability {
        require(target in RepairProtocol.TARGET_PACKAGES)
        return try {
            val info = packages.getPackageInfo(target, 0)
            val failure = checkAuthorization(target) ?: if (
                packages.resolveContentProvider(RepairProtocol.authority(target), 0)?.packageName != target ||
                packages.resolveActivity(Intent().setComponent(android.content.ComponentName(target, RepairProtocol.AUTH_ACTIVITY)), 0) == null
            ) Failure.NO_PROVIDER else null
            Availability(target, info.versionName?.take(128), failure)
        } catch (_: PackageManager.NameNotFoundException) {
            Availability(target, null, Failure.NOT_INSTALLED)
        } catch (_: RuntimeException) {
            Availability(target, null, Failure.CONNECTION_FAILED)
        }
    }

    fun heartbeat(target: String, grantId: String): Boolean =
        (query(target, RepairProtocol.METHOD_HEARTBEAT, grantId) as? Reply.Status)?.state?.authorized == true

    fun revoke(target: String, grantId: String): Boolean =
        (query(target, RepairProtocol.METHOD_REVOKE, grantId) as? Reply.Status)?.state?.authorized == false

    enum class Failure {
        NOT_INSTALLED, NO_PROVIDER, NO_REPLY, WRONG_SIGNATURE, ACCESS_DENIED, INCOMPATIBLE, CONNECTION_FAILED,
    }

    sealed interface Reply {
        data class Status(val state: TargetState) : Reply
        data class Diagnosis(val state: TargetState, val report: String) : Reply
        data class Unauthorized(val state: TargetState) : Reply
        data class Failed(val reason: Failure) : Reply
    }

    fun query(target: String, method: String, grantId: String? = null): Reply {
        check(target in RepairProtocol.TARGET_PACKAGES)
        check(method in setOf(RepairProtocol.METHOD_STATUS, RepairProtocol.METHOD_DIAGNOSTICS,
            RepairProtocol.METHOD_HEARTBEAT, RepairProtocol.METHOD_REVOKE))
        check((method == RepairProtocol.METHOD_STATUS) == (grantId == null))
        val authority = RepairProtocol.authority(target)
        return try {
            if (!isInstalled(target)) return Reply.Failed(Failure.NOT_INSTALLED)
            val provider = packages.resolveContentProvider(authority, 0)
                ?: return Reply.Failed(Failure.NO_PROVIDER)
            if (provider.packageName != target) return Reply.Failed(Failure.ACCESS_DENIED)
            checkSignature(target)?.let { return Reply.Failed(it) }
            val uri = Uri.Builder().scheme("content").authority(authority).build()
            val extras = if (method == RepairProtocol.METHOD_STATUS) null else Bundle().apply {
                putString(RepairProtocol.EXTRA_GRANT_ID, checkNotNull(grantId))
            }
            val reply = app.contentResolver.call(uri, method, null, extras)
                ?: return Reply.Failed(Failure.NO_REPLY)
            decode(target, method, reply)
        } catch (_: SecurityException) {
            Reply.Failed(Failure.ACCESS_DENIED)
        } catch (_: IllegalArgumentException) {
            // A previously resolved provider can disappear before call() acquires it.
            Reply.Failed(Failure.NO_REPLY)
        } catch (_: RuntimeException) {
            Reply.Failed(Failure.CONNECTION_FAILED)
        }
    }

    fun operation(target: String, grant: String, method: String, args: String, approval: String? = null): String {
        require(target in RepairProtocol.TARGET_PACKAGES)
        check(checkAuthorization(target) == null) { "Untrusted repair target" }
        require(args.toByteArray(Charsets.UTF_8).size <= RepairProtocol.MAX_JSON_BYTES)
        val extras = Bundle().apply {
            putString(RepairProtocol.EXTRA_GRANT_ID, grant)
            putString(RepairProtocol.EXTRA_OPERATION, method)
            putByteArray(RepairProtocol.EXTRA_ARGS, args.toByteArray(Charsets.UTF_8))
            if (approval != null) putString(RepairProtocol.EXTRA_APPROVAL, approval)
        }
        val uri = Uri.Builder().scheme("content").authority(RepairProtocol.authority(target)).build()
        val bytes = app.contentResolver.call(uri, RepairProtocol.METHOD_AGENT, null, extras)
            ?.getByteArray(RepairProtocol.KEY_JSON) ?: error("Missing operation response")
        require(bytes.size <= RepairProtocol.MAX_JSON_BYTES)
        val result = String(bytes, Charsets.UTF_8)
        require(result.toByteArray(Charsets.UTF_8).size <= RepairProtocol.MAX_JSON_BYTES)
        return result
    }

    private fun isInstalled(target: String): Boolean = try {
        packages.getApplicationInfo(target, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun checkAuthorization(target: String): Failure? {
        check(target in RepairProtocol.TARGET_PACKAGES)
        return if (isInstalled(target)) checkSignature(target) else Failure.NOT_INSTALLED
    }

    private fun checkSignature(target: String): Failure? = when (packages.checkSignatures(app.packageName, target)) {
        PackageManager.SIGNATURE_MATCH -> null
        PackageManager.SIGNATURE_NO_MATCH -> Failure.WRONG_SIGNATURE
        PackageManager.SIGNATURE_UNKNOWN_PACKAGE -> Failure.NO_REPLY
        else -> Failure.ACCESS_DENIED
    }

    fun launchIntent(target: String): Intent? {
        check(target in RepairProtocol.TARGET_PACKAGES)
        return packages.getLaunchIntentForPackage(target)?.takeIf { it.component?.packageName == target }
    }

    // Unlike getInt/getBoolean defaults, checking boxed types also rejects missing fields.
    @Suppress("DEPRECATION")
    private fun decode(target: String, method: String, reply: Bundle): Reply {
        val invalid = Reply.Failed(Failure.INCOMPATIBLE)
        if (reply.get(RepairProtocol.KEY_PROTOCOL) != RepairProtocol.VERSION ||
            reply.getString(RepairProtocol.KEY_TARGET) != target
        ) return invalid

        val versionName = reply.get(RepairProtocol.KEY_VERSION_NAME) as? String ?: return invalid
        val versionCode = reply.get(RepairProtocol.KEY_VERSION_CODE) as? Long ?: return invalid
        val authorized = reply.get(RepairProtocol.KEY_AUTHORIZED) as? Boolean ?: return invalid
        if (versionName.length > 128 || versionCode < 0) return invalid
        val grant = reply.get(RepairProtocol.KEY_GRANT_ID) as? String
        if (grant != null && (grant.isBlank() || grant.length > 64)) return invalid
        if (authorized && method == RepairProtocol.METHOD_STATUS && grant == null) return invalid
        if (!authorized && method == RepairProtocol.METHOD_STATUS && grant != null) return invalid
        val state = TargetState(versionName, versionCode, authorized, grant)
        val result = reply.getString(RepairProtocol.KEY_RESULT)
        return when {
            method == RepairProtocol.METHOD_STATUS && result == RepairProtocol.RESULT_OK -> Reply.Status(state)
            method == RepairProtocol.METHOD_HEARTBEAT && result == RepairProtocol.RESULT_OK && authorized ->
                Reply.Status(state)
            method == RepairProtocol.METHOD_REVOKE && result == RepairProtocol.RESULT_OK && !authorized ->
                Reply.Status(state)
            method == RepairProtocol.METHOD_DIAGNOSTICS && result == RepairProtocol.RESULT_UNAUTHORIZED ->
                Reply.Unauthorized(state)
            method == RepairProtocol.METHOD_DIAGNOSTICS && result == RepairProtocol.RESULT_OK && authorized -> {
                val report = reply.getString(RepairProtocol.KEY_REPORT) ?: return invalid
                if (report.length > RepairProtocol.MAX_REPORT_CHARS || report.isBlank()) return invalid
                Reply.Diagnosis(state, report)
            }
            else -> invalid
        }
    }
}
