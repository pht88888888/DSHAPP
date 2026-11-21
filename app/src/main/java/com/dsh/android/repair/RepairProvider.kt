package com.dsh.android.repair

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.dsh.android.BuildConfig
import com.dsh.android.repair.contract.RepairProtocol as Protocol

/** Diagnostic reads and lease control are restricted to the paired assistant. */
class RepairProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val owner = checkNotNull(context)
        // Internal lifecycle capability is never available to the cross-package assistant.
        if (method in setOf(Protocol.METHOD_RUNTIME_PERMIT, Protocol.METHOD_RUNTIME_REPORT)) {
            require(arg == null && extras != null)
            val pid = Binder.getCallingPid()
            if (Binder.getCallingUid() != owner.applicationInfo.uid || pid == android.os.Process.myPid()) throw SecurityException("Runtime identity required")
            val entry = java.io.File("/proc/$pid")
            val name = java.io.File(entry, "cmdline").inputStream().use { input ->
                val bytes = ByteArray(8192); val n = input.read(bytes); require(n > 0)
                String(bytes, 0, n, Charsets.UTF_8).substringBefore('\u0000')
            }
            if (name != owner.packageName) throw SecurityException("Exact target main process required")
            val first = java.io.File(entry, "stat").readText().let(RepairEngineQuiescence.ProcessPolicy::startTime)
                ?: throw SecurityException("Unknown runtime start identity")
            require(android.system.Os.stat(entry.path).st_uid == owner.applicationInfo.uid)
            val second = java.io.File(entry, "stat").readText().let(RepairEngineQuiescence.ProcessPolicy::startTime)
            check(first == second)
            val identity = Binder.clearCallingIdentity()
            return try {
                if (method == Protocol.METHOD_RUNTIME_PERMIT) RepairTaskCoordinator.runtimePermit(owner, pid, first, extras)
                else RepairTaskCoordinator.runtimeReport(owner, pid, first, extras)
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        owner.enforceCallingPermission(Protocol.permission(owner.packageName), "Repair permission required")
        val uid = Binder.getCallingUid()
        val pm = owner.packageManager
        // Framework validates callingPackage against the Binder UID; requiring the unique
        // package closes ambiguity if a custom build uses a shared UID.
        if (callingPackage != Protocol.ASSISTANT_PACKAGE ||
            pm.getPackagesForUid(uid)?.singleOrNull() != Protocol.ASSISTANT_PACKAGE ||
            pm.checkSignatures(uid, owner.applicationInfo.uid) != PackageManager.SIGNATURE_MATCH
        ) throw SecurityException("Unrecognized repair assistant")

        require(method == Protocol.METHOD_STATUS || method == Protocol.METHOD_DIAGNOSTICS ||
            method == Protocol.METHOD_HEARTBEAT || method == Protocol.METHOD_REVOKE || method == Protocol.METHOD_AGENT) {
            "Unsupported repair method"
        }
        val grantId = if (method == Protocol.METHOD_STATUS) null else
            extras?.getString(Protocol.EXTRA_GRANT_ID)?.takeIf { it.isNotBlank() && it.length <= 64 }
                ?: throw IllegalArgumentException("A valid active repair grant is required")
        if (method == Protocol.METHOD_STATUS) require(arg == null && extras == null) {
            "Repair status does not take arguments"
        } else if (method == Protocol.METHOD_AGENT) {
            val allowed = setOf(Protocol.EXTRA_GRANT_ID, Protocol.EXTRA_ARGS, Protocol.EXTRA_OPERATION, Protocol.EXTRA_APPROVAL)
            require(arg == null && extras != null && extras.keySet().all { it in allowed })
        } else require(arg == null && extras?.keySet() == setOf(Protocol.EXTRA_GRANT_ID)) {
            "Repair operation requires only the active grant"
        }
        val identity = Binder.clearCallingIdentity()
        try {
            val consent = RepairAuthorization(owner)
            if (method == Protocol.METHOD_AGENT) {
                val operationExtras = checkNotNull(extras)
                val bytes = checkNotNull(operationExtras.getByteArray(Protocol.EXTRA_ARGS))
                require(bytes.size <= Protocol.MAX_JSON_BYTES)
                val json = RepairAgentOperations.execute(owner, checkNotNull(grantId),
                    checkNotNull(operationExtras.getString(Protocol.EXTRA_OPERATION)),
                    String(bytes, Charsets.UTF_8), operationExtras.getString(Protocol.EXTRA_APPROVAL))
                return Bundle().apply { putByteArray(Protocol.KEY_JSON, json.toByteArray(Charsets.UTF_8)) }
            }
            val currentGrant = consent.currentGrant()
            val grantMatches = method == Protocol.METHOD_STATUS || grantId == currentGrant
            val report = if (method == Protocol.METHOD_DIAGNOSTICS && grantMatches) {
                RepairDiagnostics.report(owner.filesDir)
            } else null
            val authorized = grantMatches && consent.currentGrant() == currentGrant && currentGrant != null
            val accepted = when (method) {
                Protocol.METHOD_STATUS -> true
                Protocol.METHOD_HEARTBEAT -> authorized && consent.renew(checkNotNull(grantId))
                Protocol.METHOD_REVOKE -> if (authorized) {
                    RepairTaskCoordinator.end(owner)
                    consent.revoke(checkNotNull(grantId))
                } else false
                else -> authorized
            }
            return Bundle().apply {
                putInt(Protocol.KEY_PROTOCOL, Protocol.VERSION)
                putString(Protocol.KEY_TARGET, owner.packageName)
                putString(Protocol.KEY_VERSION_NAME, BuildConfig.VERSION_NAME)
                putLong(Protocol.KEY_VERSION_CODE, BuildConfig.VERSION_CODE.toLong())
                putBoolean(Protocol.KEY_AUTHORIZED, authorized && accepted && method != Protocol.METHOD_REVOKE)
                if (method == Protocol.METHOD_STATUS && authorized) {
                    putString(Protocol.KEY_GRANT_ID, currentGrant)
                }
                if (method == Protocol.METHOD_DIAGNOSTICS && accepted && report != null) {
                    putString(Protocol.KEY_RESULT, Protocol.RESULT_OK)
                    putString(Protocol.KEY_REPORT, report)
                } else if (method == Protocol.METHOD_STATUS || accepted) {
                    putString(Protocol.KEY_RESULT, Protocol.RESULT_OK)
                } else {
                    putString(Protocol.KEY_RESULT, Protocol.RESULT_UNAUTHORIZED)
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = unsupported()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Use the repair status protocol")
}
