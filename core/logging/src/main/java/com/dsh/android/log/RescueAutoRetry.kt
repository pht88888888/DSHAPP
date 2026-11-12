package com.dsh.android.log

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 降级层级「粘死」的自愈额度 —— 次数存在**独立**文件 `rescue/auto-retry.json`。
 *
 * ## 要解决的死锁（真机事实，2026-11-12，CDY-TN90）
 *
 * `targetProfile` 只看 `RescueBoot.tier()`；而 `tier()` 只在「用**正常** profile
 * 起成功」或用户点「退出救援模式」时复位。于是：
 *
 *   · 正常 profile 启动 → 前端慢，被误判 → 写 `tier=RESCUE`
 *   · 之后每次启动都用救援 profile → **永远不会**再走「正常 profile 起成功」
 *   · ⇒ 层级再也复位不了，用户看到「救援模式」横幅永久挂着，
 *     而他的插件配置其实一点问题都没有
 *
 * 真机现场：升级后首次冷启动因前端慢（14 秒才 ready）被误判降级，随后**连续两次
 * 启动**都停在 rescue，`onUiReady` 一次都没再出现（救援 profile 不加载移动端插件）。
 *
 * ## 为什么次数不写进 `state.json`
 *
 * `state.json` 是**给用户和诊断包看**的层级快照（schemaVersion + tier + since +
 * 原因 + 隔离清单），它的字段每加一个都是对既有读者的改动。而「这个降级周期里
 * 自动重试过几次」纯粹是**内部额度**，谁也读不到、也不需要读。放独立文件的好处：
 * `RescueBoot` 一行不用改（体积守则里有历史基线），且额度自成一个可删对象 ——
 * 删掉它等于「重新发一次额度」，语义直观。
 *
 * ## 判据（三个条件，缺一不可）
 *
 * ① 当前层级确实是 [RescueBoot.Tier.RESCUE] —— ISOLATED / QUARANTINED 都用 `web`
 *    profile，不存在这个问题；SAFE_MODE 是用户显式选择的处境，更不能偷偷改。
 * ② 距进入该层级已超过 [MIN_AGE_MS]（冷却，防「刚降级就立刻撞一次」的抖动）。
 * ③ 本周期里自动重试次数 < [MAX_ATTEMPTS]（上限，防死循环）。
 *
 * ⚠️ **不改 `since`**：横幅上的「从什么时候开始降级」必须保持真实时刻，
 *   否则用户会看到时间每次启动都往后跳。
 *
 * ⚠️ 调用方（`EngineManager`）必须把「本次是自愈重试」记成一个标记并在整轮启动里
 *   保持可见 —— 那一轮 `tier()` 里躺着的是**过期**值，凡是按 tier 决定要不要另作
 *   处理的地方都得知道（否则会立刻把重试推回救援，自愈完全无效）。
 */
object RescueAutoRetry {

    private const val FILE = "auto-retry.json"
    private const val SCHEMA = 1

    /**
     * 一次降级态里最多自动回正常 profile 重试几次。
     *
     * 2 的理由：第一次用来「爬回误判」；万一这次又降级（真坏），第二次给用户手动
     * 改完配置后的下一轮启动留一次机会。再多就不是自愈而是死循环 —— 每次都是
     * 14~19 秒的启动 + 一次引擎重启，用户会以为 App 卡了。
     */
    const val MAX_ATTEMPTS = 2

    /**
     * 距进入降级态至少隔多久才允许自动重试。
     *
     * 5 分钟：足够冷却，避免「刚降级就立刻回正常 profile 再撞一次」的抖动；
     * 又不至于让用户修好插件后还得等很久。
     */
    const val MIN_AGE_MS = 5 * 60 * 1000L

    private const val TAG = "DSH"

    private fun file(): File? {
        val dir = RescueBoot.dirOrNull() ?: return null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            File(dir, FILE)
        } catch (t: Throwable) {
            null
        }
    }

    /** 当前降级周期里已自动重试的次数；层级不是 RESCUE 时一律视为 0（并顺手归零落盘）。 */
    fun attempts(): Int {
        val tier = RescueBoot.tier().tier
        if (tier != RescueBoot.Tier.RESCUE) {
            // ⚠️ 层级已经回正常 ⇒ 周期结束，额度自动归零。用「读时清理」而不是
            //   在 `rescue.tier.reset` 那里加一个调用点：少一个会被漏掉的地方。
            if (peek() != 0) write(0)
            return 0
        }
        return peek()
    }

    private fun peek(): Int {
        val f = file() ?: return 0
        if (!f.isFile) return 0
        return try {
            JSONObject(f.readText()).optInt("attempts", 0).coerceAtLeast(0)
        } catch (t: Throwable) {
            // 文件坏了就当没额度记录 —— 绝不能因为「读不到额度」让启动失败。
            0
        }
    }

    private fun write(n: Int): Boolean {
        val f = file() ?: return false
        return try {
            val json = JSONObject()
                .put("schemaVersion", SCHEMA)
                .put("attempts", n)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "救援：自动重试额度写入失败: ${t.message}")
            false
        }
    }

    /**
     * 本次启动要不要**无视降级层级、回正常 profile 再试一次**。
     *
     * 满足判据时把额度 +1 并**落盘**（进程被杀也不白送），返回 true。
     */
    fun shouldRetry(now: Long = System.currentTimeMillis()): Boolean {
        val cur = RescueBoot.tier()
        if (cur.tier != RescueBoot.Tier.RESCUE) return false
        val used = attempts()
        if (used >= MAX_ATTEMPTS) {
            Log.w(
                TAG,
                "救援：本次降级周期已自动重试 $used 次，不再自动回正常 profile" +
                    "（要重试请点横幅上的「退出救援模式」）",
            )
            return false
        }
        if (now - cur.since < MIN_AGE_MS) return false
        val next = used + 1
        val ok = write(next)
        Log.w(
            TAG,
            "救援：自动回正常 profile 重试（第 $next/$MAX_ATTEMPTS 次，" +
                "距进入 ${cur.tier.id} 已 ${(now - cur.since) / 1000}s，落盘=$ok）",
        )
        return true
    }
}
