package com.dsh.android

import com.dsh.android.log.DshLog as Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 插件清单（`profiles/<p>/package.json` 的 `dsh.profile.bundles`）的读写。
 *
 * ## 为什么需要它
 *
 * 真机现场（OPPO PHY110）：用户在手机上写插件写到一半退出 App，留下的
 * `client.js` 里多了一个 `)`。而前端把**所有插件的 client 半合并成一批**
 * 下发（`/plugins/??a/client.js,b/client.js,…`），一个段有语法错误就让**整批**
 * 解析失败 ⇒ 界面变成 `Failed to load plugins` 整页错误。
 *
 * 引擎完全健康（`dsh-gateway-ui: 已激活`）、用户数据完好，但界面进不去。
 * 当时唯一的救法是：在电脑上用 adb 把那个 bundle 从 `bundles` 里摘掉，再重启。
 *
 * **这个类就是把那一步做成 App 内可用的操作** —— 因为普通用户没有 adb、
 * 也没有第二台设备，只能卸载重装，而卸载会清掉全部会话与凭据。
 *
 * ## 三条不变量
 *
 * ① **只动 `dsh.profile.bundles` 这一个数组**，其余字段原样保留。
 *    `package.json` 里还有 `dependencies`（插件从哪来），摘 bundle 不碰它 ——
 *    这样「装回去」只是把名字写回数组，不需要重新下载。
 * ② **写之前必须能读回来**。`package.json` 非法 JSON 是引擎起不来的头号原因
 *    （见 `EngineManager` 的启动前体检），所以：先备份、写临时文件、**回读校验**、
 *    再原子替换。任一步失败就还原，绝不留下半份文件。
 * ③ **官方 bundle 不可摘**（「@deepseek-ai/」开头的那些）。摘掉 `dsh-web-app`
 *    等于把界面本身删了，那不是「修」是「毁」。由 [isProtected] 判定。
 */
object PluginManifest {

    private const val TAG = "PluginManifest"

    /** bundle 清单所在的键路径。 */
    private const val KEY_DSH = "dsh"
    private const val KEY_PROFILE = "profile"
    private const val KEY_BUNDLES = "bundles"

    /**
     * 官方 bundle 的包名前缀。
     *
     * ⚠️ **不可摘**：`@deepseek-ai/dsh-base` 是引擎本体、`dsh-web-app` 是界面本身。
     *    摘掉它们得到的不是「能用的界面」，而是「更彻底的起不来」——
     *    用户点一下就从「界面坏」变成「引擎都起不来」，比不给这个按钮更糟。
     *
     * ⚠️ 本文件里**提到官方包名时一律用「」包裹**（例如「@deepseek-ai/dsh-base」），
     *    绝不写带通配符的形式 —— KDoc 里出现 `@deepseek-ai/` 紧跟 `*` 会
     *    提前闭合块注释，吞掉文件后半段，报一连串 Unresolved reference。
     *    这是本工程记录过的第 1 条注释陷阱（见 AGENTS.md ⑦.7）。
     */
    private const val OFFICIAL_PREFIX = "@deepseek-ai/"

    /** 一个 bundle 在清单里的样子。 */
    data class Entry(
        /** 包名，例如 `dsh-gateway-ui`。 */
        val name: String,
        /** 官方 bundle（不可摘）。 */
        val isProtected: Boolean,
    )

    /**
     * 读清单。
     *
     * @return 成功时 `(manifestFile, bundles, 是否官方)`；读不出来返回 null
     *         （调用方据此显示「读不到插件清单」而不是一个空列表 ——
     *         空列表与「读失败」语义完全不同，前者会让用户以为插件没了）。
     */
    fun read(profileDir: File): Pair<JSONObject, List<Entry>>? {
        val f = File(profileDir, "package.json")
        if (!f.isFile) return null
        val root = runCatching { JSONObject(f.readText()) }.getOrElse {
            Log.w(TAG, "插件清单不是合法 JSON（${it.message}）—— 界面里会提示这个事实")
            return null
        }
        val bundles = bundlesOf(root)
        val entries = bundles.map { Entry(it, isProtected(it)) }
        return root to entries
    }

    /** 取出 `dsh.profile.bundles`；缺失时返回空列表（不抛）。 */
    private fun bundlesOf(root: JSONObject): List<String> {
        val arr = root.optJSONObject(KEY_DSH)
            ?.optJSONObject(KEY_PROFILE)
            ?.optJSONArray(KEY_BUNDLES)
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    /** 官方 bundle 不可摘。 */
    fun isProtected(name: String): Boolean = name.startsWith(OFFICIAL_PREFIX)

    /**
     * 把 [name] 从清单里摘掉（或装回去），**原子地**写回。
     *
     * ## 为什么「写回前先回读校验」
     *
     * 这个动作的全部意义是「把界面修好」。而一份写坏的 `package.json`
     * 会让引擎**彻底起不来** —— 用户从「界面打不开」变成「App 打不开」，
     * 比不做还糟。所以：备份 → 写 `.tmp` → **回读 `.tmp` 确认能解析且内容符合预期**
     * → 原子替换。任何一步失败都还原并如实报错。
     *
     * @param remove true = 摘掉，false = 装回去
     * @return 成功与否；失败时第二项是**能直接显示给用户的原因**。
     */
    fun setBundle(profileDir: File, name: String, remove: Boolean): Pair<Boolean, String> {
        if (isProtected(name)) {
            // ⚠️ 这一条不是防御性编程，是真会发生：列表里本来就不显示官方
            //   bundle，但用户可能从日志里看到名字后手动要求 —— 说清为什么不行。
            return false to DshApplication.instance.getString(R.string.plugin_core_required, name)
        }

        val f = File(profileDir, "package.json")
        val root = runCatching { JSONObject(f.readText()) }.getOrElse {
            return false to DshApplication.instance.getString(R.string.plugin_manifest_invalid, it.message)
        }

        val current = bundlesOf(root)
        val target = if (remove) current - name else (current + name).distinct()
        if (target == current) {
            return true to if (remove) DshApplication.instance.getString(R.string.plugin_already_absent, name) else DshApplication.instance.getString(R.string.plugin_already_present, name)
        }

        // 写回 `dsh.profile.bundles`，其余字段一字不动（不变量①）
        val dsh = root.optJSONObject(KEY_DSH) ?: JSONObject().also { root.put(KEY_DSH, it) }
        val profile = dsh.optJSONObject(KEY_PROFILE) ?: JSONObject().also { dsh.put(KEY_PROFILE, it) }
        profile.put(KEY_BUNDLES, JSONArray(target))

        val text = root.toString(2)
        // 不变量②：备份 → 写 tmp → 回读校验 → 原子替换
        val bak = File(profileDir, "package.json.bak-plugin-toggle")
        val tmp = File(profileDir, "package.json.tmp-toggle")
        return runCatching {
            if (f.isFile) f.copyTo(bak, overwrite = true)
            tmp.writeText(text)
            // ⚠️ 回读校验：确认 tmp 真的能被解析、且 bundles 就是我们要的那个数组。
            //   只检查「写得出来」是不够的 —— 磁盘满、写到一半被杀都会留下
            //   一个「写成功了但内容不全」的文件。
            val check = JSONObject(tmp.readText())
            val got = bundlesOf(check)
            if (got.toSet() != target.toSet()) {
                error(DshApplication.instance.getString(R.string.plugin_verify_mismatch, target.size, got.size))
            }
            if (!tmp.renameTo(f)) {
                // rename 失败（跨设备/被占用）就退回直接写，再回读一次
                f.writeText(text)
                val again = bundlesOf(JSONObject(f.readText()))
                if (again.toSet() != target.toSet()) error(DshApplication.instance.getString(R.string.plugin_direct_verify_mismatch))
                tmp.delete()
            }
            Log.i(TAG, "插件清单已更新: ${if (remove) "摘掉" else "装回"} $name，" +
                "bundles 现为 ${target.size} 项")
            true to if (remove) DshApplication.instance.getString(R.string.plugin_removed, name) else DshApplication.instance.getString(R.string.plugin_restored, name)
        }.getOrElse { e ->
            // ⚠️ 失败要还原：备份存在就写回去，宁可回到「界面坏」也不能变成
            //   「引擎起不来」（那是更彻底、更不可逆的坏法）。
            Log.e(TAG, "写插件清单失败，尝试还原: ${e.message}", e)
            runCatching { if (bak.isFile) bak.copyTo(f, overwrite = true) }
            runCatching { tmp.delete() }
            false to DshApplication.instance.getString(R.string.plugin_write_failed, e.message)
        }
    }

    /**
     * 从 `profiles/web/package.json` 推导**上一个**坏 bundle 的候选。
     *
     * 用于「一键摘掉刚装的插件」：插件是用户最后装的那个，所以取清单**末尾**
     * 的非官方项。这与 `EngineManager.isolateBadBundles` 的二分思路不同 ——
     * 那个要试启动十几秒一轮，这个零成本且命中率极高（刚装完就坏是主要路径）。
     *
     * @return 候选包名；没有可摘的返回 null。
     */
    fun lastAddedCandidate(profileDir: File): String? =
        read(profileDir)?.second?.lastOrNull { !it.isProtected }?.name
}
