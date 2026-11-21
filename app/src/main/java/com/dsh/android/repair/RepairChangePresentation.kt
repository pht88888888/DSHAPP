package com.dsh.android.repair

import org.json.JSONObject

/** Native-only bounded presentation. JSON data is plain text, never HTML or executable content. */
internal object RepairChangePresentation {
    fun message(packageName: String, method: String, preview: JSONObject): String = buildString {
        appendLine(if (method == "rollback") "回退应用文件变更（敏感额外确认）" else "敏感应用文件变更")
        appendLine("本页仅批准此候选的一次应用或回退，不扩展任务权限，也不批准后续敏感变更。普通代码、配置和依赖在你已授权的维修任务内自动提交，不需要逐文件再次确认。")
        appendLine("目标：$packageName")
        appendLine("操作：${preview.optString("operation", "replace")}")
        appendLine("文件：${preview.optString("path").take(4096)}")
        appendLine("状态：${preview.optString("status")}")
        appendLine("原摘要：${preview.optString("original_sha256", "不存在")}")
        appendLine("候选摘要：${preview.optString("candidate_sha256", "不存在")}")
        appendLine("原大小：${preview.opt("original_size_bytes") ?: "未知"} 字节；候选大小：${preview.opt("candidate_size_bytes") ?: "未知"} 字节")
        appendLine("原权限：${preview.opt("original_mode") ?: "未知"}；候选权限：${preview.opt("candidate_mode") ?: "未知"}（十进制权限位）")
        appendLine("权限策略：${preview.optString("permissions_policy", "posix")}（platform_managed 表示外部存储权限由系统管理，不承诺 chmod）")
        preview.optJSONObject("patch")?.let { appendLine("局部补丁：${it.toString().take(2048)}") }
        appendLine("\n这不是只读诊断。修改、新建或删除可能影响配置、对话、数据库、插件、二进制及可执行代码；代码会以原应用身份执行并可能访问密钥。删除将移除指定文件或空目录；不执行递归删除。备份与回退仅对本次授权内且未发生后续冲突的事务有效。")
        appendLine("确认后会尝试关闭原 DSH 主进程和已识别的引擎进程，静止检查通过后应用或回退。这会中断请求、终端及未保存操作，可能丢失尚未落盘的内容；不会卸载或清除整个应用数据。无法确认进程身份或静止状态时拒绝，不强杀无关进程。")
        appendLine("\n原件预览（有界，可能截断；二进制以 base64 显示）：")
        appendLine(preview.optString("original_content").take(24000))
        appendLine("\n候选预览（有界，可能截断；不是大文件的完整内容）：")
        appendLine(preview.optString("candidate_content").take(24000))
        for (key in listOf("preview_encoding", "original_encoding", "candidate_encoding", "original_truncated", "candidate_truncated", "preview_truncated")) {
            if (preview.has(key)) appendLine("$key：${preview.opt(key)}")
        }
        for (key in listOf("original_patch_content", "candidate_patch_content", "original_patch_encoding", "candidate_patch_encoding", "original_patch_truncated", "candidate_patch_truncated", "original_patch_offset", "candidate_patch_offset")) {
            if (preview.has(key)) appendLine("$key：${preview.optString(key).take(24000)}")
        }
    }
}
