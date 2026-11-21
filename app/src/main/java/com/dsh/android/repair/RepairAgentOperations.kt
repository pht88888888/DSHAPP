package com.dsh.android.repair

import android.content.Context
import com.dsh.android.repair.contract.RepairProtocol as Protocol
import org.json.JSONObject

/** Native task authority and lifecycle are separate from model-authored file requests. */
internal object RepairAgentOperations {
    private val methods = setOf("status", "evidence", "list_files", "read_file", "stage_edit", "stage_patch", "stage_file",
        "list_changes", "apply_changes", "rollback", "verify_target", "validate_target", "prepare_ui")
    fun execute(context: Context, grant: String, method: String, argsText: String, approval: String?): String {
        require(method in methods) { "Unknown agent operation" }
        require(argsText.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_JSON_BYTES)
        require(com.dsh.android.repair.contract.RepairJsonLimits.accepts(argsText))
        return RepairTaskCoordinator.execute(context, grant, method, JSONObject(argsText), approval).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_JSON_BYTES)
        }
    }
}
