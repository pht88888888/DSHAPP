package com.dsh.android.ui

import android.content.Context
import com.dsh.android.core.DshApplication
import com.dsh.android.log.DshLog as Log
import org.json.JSONObject
import com.dsh.android.bubble.BubbleA11yService
import com.dsh.android.bubble.BubbleAppearance
import com.dsh.android.bubble.FloatingBubble
import com.dsh.android.privilege.ShellPrivilege

/** 页面领域能力；仅保留应用 Context，不持有 Activity 或窗口。 */
internal class BubblePageBridge(context: Context) : BubblePageBridgeActions {
    private val app = context.applicationContext

    override fun bubbleState(): String = try {
        // ⚠️ 这几个字段是「悬浮气泡」设置页的**开关状态**，页面渲染时直接读这里。
        //   两个新开关都带能力判据：`imeFlingAvailable` 为 false 时页面必须把
        //   「键盘顶飞」置灰并显示 `imeFlingReason`，而不是让用户打开一个注定
        //   不生效的开关（读 `dumpsys input_method` 需要 DUMP 权限，普通应用没有）。
        val ap = BubbleAppearance.load(app)
        val priv = ShellPrivilege.status(app)
        JSONObject()
            .put("enabled", FloatingBubble.isEnabled(app))
            .put("granted", FloatingBubble.canDrawOverlays(app))
            .put("throwEnabled", ap.throwEnabled)
            .put("imeFlingEnabled", ap.imeFlingEnabled)
            .put("imeFlingAvailable", priv.available)
            .put("imeFlingReason", priv.reason ?: JSONObject.NULL)
            .put("shakeEnabled", ap.shakeEnabled)
            // 键盘避让所需的无障碍是否已开。与上两项独立：悬浮窗授权了、
            // 开关也打开了，没有无障碍照样不会避让键盘。
            .put("a11y", BubbleA11yService.isEnabled(app))
            .toString()
    } catch (t: Throwable) {
        Log.w(DshApplication.TAG, "读取气泡状态失败: ${t.message}")
        "{}"
    }

    override fun bubbleSetEnabled(enabled: Boolean) {
        Log.i(DshApplication.TAG, "设置页切换悬浮气泡: $enabled")
        FloatingBubble.setEnabled(app, enabled)
        if (!enabled) FloatingBubble.hideNow()
        // ⚠️ 这里**不弹关联权限的提示**：键盘避让有自己那张卡片（开关 + 去开启权限），
        //   在"开气泡"这个动作上顺手弹一个无障碍对话框，用户会不知道自己在确认什么。
    }

    override fun bubbleSetImeFling(enabled: Boolean) {
        val priv = ShellPrivilege.status(app)
        if (enabled && !priv.available) {
            ShellPrivilege.logDenied("键盘顶飞", priv)
            Log.i(DshApplication.TAG, "拒绝打开键盘顶飞：${priv.reason}")
            return
        }
        Log.i(DshApplication.TAG, "设置页切换键盘顶飞: $enabled")
        val cur = BubbleAppearance.load(app)
        BubbleAppearance.save(app, cur.copy(imeFlingEnabled = enabled))
        // 立即生效（同 [bubbleSetThrow]：只 save 不会让活的球重新读配置）。
        runCatching { FloatingBubble.applyAppearance(FloatingBubble.currentAppearanceJson()) }
            .onFailure { Log.w(DshApplication.TAG, "键盘顶飞即时生效失败: ${it.message}") }
    }

    override fun bubbleSetShake(enabled: Boolean) {
        Log.i(DshApplication.TAG, "设置页切换晃动甩飞: $enabled")
        val cur = BubbleAppearance.load(app)
        BubbleAppearance.save(app, cur.copy(shakeEnabled = enabled))
        runCatching { FloatingBubble.applyAppearance(FloatingBubble.currentAppearanceJson()) }
            .onFailure { Log.w(DshApplication.TAG, "晃动甩飞即时生效失败: ${it.message}") }
    }

    override fun bubbleReportBalance(json: String) {
        val snap = try {
            val o = JSONObject(json)
            BalanceSnapshot.fromWallets(
                rechargeJson = o.optJSONArray("wallets"),
                bonusJson = o.optJSONArray("bonusWallets"),
                costLast = runCatching {
                    java.math.BigDecimal(o.opt("costLast")?.toString() ?: "0")
                }.getOrDefault(java.math.BigDecimal.ZERO),
                previous = null,
            ).copy(
                costSession = runCatching {
                    java.math.BigDecimal(o.opt("costSession")?.toString() ?: "0")
                }.getOrDefault(java.math.BigDecimal.ZERO),
                error = o.optString("error", ""),
            )
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "解析余额载荷失败: ${t.message}")
            BalanceSnapshot(error = "载荷解析失败")
        }
        val ok = FloatingBubble.applyBalanceToActive(snap)
        Log.i(
            DshApplication.TAG,
            "页面上报余额: 钱包=${snap.wallets.size} 赠送=${snap.bonusWallets.size} " +
                "合计=${snap.total().toPlainString()} 本次=${snap.costLast.toPlainString()} " +
                "已送达=$ok",
        )
    }

    override fun bubbleBadgeConfig(): String {
        return try {
            val b = BubbleAppearance.load(app).balanceBadge
            JSONObject().apply {
                put("refreshEverySec", b.refreshEverySec)
                put("refreshOnTurnEnd", b.refreshOnTurnEnd)
                put("refreshOnTap", b.refreshOnTap)
                put("tapRefreshMinIntervalSec", b.tapRefreshMinIntervalSec)
            }.toString()
        } catch (t: Throwable) {
            Log.w(DshApplication.TAG, "读徽标刷新配置失败: ${t.message}")
            // 兜底给"只按轮末刷新"：最省接口、且一定有数据更新。
            // `tapRefreshMinIntervalSec` 用 3 而不是 20：读不到配置时应该尽量
            // 让功能可用，而不是把用户挡在门外（见页面侧那段注释）。
            """{"refreshEverySec":0,"refreshOnTurnEnd":true,"refreshOnTap":false,"tapRefreshMinIntervalSec":3}"""
        }
    }

    override fun bubbleTapRefreshSeq(): Int = FloatingBubble.tapRefreshSequence()

    override fun bubbleSetThrow(enabled: Boolean) {
        Log.i(DshApplication.TAG, "设置页切换拖拽抛掷: $enabled")
        val cur = BubbleAppearance.load(app)
        BubbleAppearance.save(app, cur.copy(throwEnabled = enabled))
        // 立即生效：把含新 `throwEnabled` 的整份配置推给活的球。
        runCatching { FloatingBubble.applyAppearance(FloatingBubble.currentAppearanceJson()) }
            .onFailure { Log.w(DshApplication.TAG, "拖拽抛掷即时生效失败: ${it.message}") }
    }

    override fun onTranscript(json: String) {
        FloatingBubble.onTranscript(json)
    }

    override fun bubbleApply(json: String): Boolean {
        Log.i(DshApplication.TAG, "插件下发悬浮球配置: ${json.take(200)}")
        return FloatingBubble.applyAppearance(json)
    }

    override fun bubbleAppearance(): String = FloatingBubble.currentAppearanceJson()
}
