package com.dsh.android.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * **备份清单** —— 描述「这个包里有什么」。
 *
 * ## ⚠️ 清单在**加密载荷内**，不在明文头
 *
 * 设计说明 §5.3.2：
 *
 * > **文件名、清单及内容哈希置于加密载荷**；外部头只保留解析必需的格式/KDF
 * > 参数与随机信息，**不泄露插件列表或秘密**。
 *
 * 所以这个对象只会被序列化进加密流，绝不写进 `.dshbak` 的明文头。
 *
 * ## 清单记什么
 *
 * 设计说明 §5.3.3：
 *
 * > 受认证清单包含**源范围、完整/部分标志、依赖闭包、DSH/Node/ABI/配置版本、
 * > profile revision、创建时间及文件哈希**，不依赖外部文件时间排序。
 */
data class BackupManifest(
    /** 清单格式版本。**改动字段语义时必须 +1**。 */
    val manifestVersion: Int = 1,

    /** 创建时刻（毫秒）。 */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * 包是否**完整**。
     *
     * ⚠️ 文档要求「部分包不挤占三份完整包」。所以这个标志必须如实：
     * 有任何一项收集失败就是 `false`，不能因为「大部分都在」就说完整。
     */
    val complete: Boolean = true,

    /** 来源环境的 id（`a` / `r` / `e`）。R/E 不该进用户包，记下来便于校验。 */
    val sourceEnv: String = "a",

    /** profile 名（`web`）。 */
    val profile: String = "web",

    /** 目标版本的标识（用于导入时提示「这份备份来自 0.2.0，当前是 0.2.1」）。 */
    val appVersion: String = "",
    val nodeVersion: String = "",
    val abi: String = "",

    /** 纳入包内的顶层条目（相对 $DSH_HOME）。 */
    val entries: List<Entry> = emptyList(),

    /** 收集失败的项（**必须如实列出，不能静默跳过**）。 */
    val missing: List<String> = emptyList(),

    /**
     * 范围外的依赖闭包（如 pnpm store 的外链实体）。
     *
     * ⚠️ 文档：「属于插件执行闭包的外部实体经路径映射与允许列表纳入，
     * 禁止盲目追随链接卷入任意用户文件。缺失闭包…均须逐项报告」。
     */
    val externalRefs: List<String> = emptyList(),
) {
    /**
     * 一个纳入包内的条目。
     *
     * @param path 相对路径
     * @param size 字节数
     * @param sha256 内容哈希（前 16 字节十六进制）—— 用于导入后逐文件校验
     * @param mode 权限位（Unix mode）。`0` = 未记录
     * @param isDir 是否目录（空目录也要记，否则恢复后目录结构会缺）
     */
    data class Entry(
        val path: String,
        val size: Long,
        val sha256: String,
        val mode: Int = 0,
        val isDir: Boolean = false,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("manifestVersion", manifestVersion)
        put("createdAt", createdAt)
        put("complete", complete)
        put("sourceEnv", sourceEnv)
        put("profile", profile)
        put("appVersion", appVersion)
        put("nodeVersion", nodeVersion)
        put("abi", abi)
        put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("path", e.path)
                    put("size", e.size)
                    put("sha256", e.sha256)
                    put("mode", e.mode)
                    put("isDir", e.isDir)
                })
            }
        })
        put("missing", JSONArray().apply { missing.forEach { put(it) } })
        put("externalRefs", JSONArray().apply { externalRefs.forEach { put(it) } })
    }

    companion object {
        fun fromJson(o: JSONObject): BackupManifest {
            fun strList(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
            }

            val entries = mutableListOf<Entry>()
            o.optJSONArray("entries")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    entries.add(
                        Entry(
                            path = e.optString("path"),
                            size = e.optLong("size", 0),
                            sha256 = e.optString("sha256"),
                            mode = e.optInt("mode", 0),
                            isDir = e.optBoolean("isDir", false),
                        ),
                    )
                }
            }

            return BackupManifest(
                manifestVersion = o.optInt("manifestVersion", 1),
                createdAt = o.optLong("createdAt", 0),
                complete = o.optBoolean("complete", false),
                sourceEnv = o.optString("sourceEnv", "a"),
                profile = o.optString("profile", "web"),
                appVersion = o.optString("appVersion", ""),
                nodeVersion = o.optString("nodeVersion", ""),
                abi = o.optString("abi", ""),
                entries = entries,
                missing = strList("missing"),
                externalRefs = strList("externalRefs"),
            )
        }
    }
}
