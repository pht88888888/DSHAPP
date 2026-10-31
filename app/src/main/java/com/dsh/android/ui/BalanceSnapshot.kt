package com.dsh.android.ui
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import com.dsh.android.bubble.BubbleAppearance

/**
 * 余额与花费的**运行态**快照（不落盘 —— 余额随时会变，落盘只会显示一个过期的数）。
 *
 * ## 为什么单独一个类
 *
 * `BubbleAppearance` 是「用户/AI 配好的**想要什么**」，本类则是「**实际是多少**」。
 * 两者混在一起会带来两个具体问题：
 *
 * ① 余额每次刷新都写一次 SharedPreferences —— 官方接口若被设成 5 分钟一刷，
 *    一天就是 288 次无意义的磁盘写；
 * ② 重启后先显示的会是上次的旧余额，而用户会以为那就是现在还有多少钱。
 *
 * 所以这里**只在内存里**，进程重启就重新拉一次。
 *
 * @property wallets 充值钱包（每币种一个）。
 * @property bonusWallets 赠送钱包。
 * @property costLast 上一次 API 请求花了多少（同 [currency] 单位）。
 * @property costSession 本会话累计花费。
 * @property at 上次成功刷新的时刻（ms）。0 = 从没成功过。
 * @property error 上次失败的原因；空串 = 没有错误。
 */
data class BalanceSnapshot(
    val wallets: List<Wallet> = emptyList(),
    val bonusWallets: List<Wallet> = emptyList(),
    val costLast: BigDecimal = BigDecimal.ZERO,
    val costSession: BigDecimal = BigDecimal.ZERO,
    val at: Long = 0L,
    val error: String = "",
) {
    /**
     * 一个钱包。
     *
     * ⚠️ 余额用**字符串**保存而不是 Double：官方返回的就是 decimal string
     *   （`AccountWallet.balance: string`，见引擎的类型定义），转成 Double
     *   再显示会出现 `12.340000000000001` 这种尾巴，或者在 0.01 附近四舍五入错。
     */
    data class Wallet(val currency: String, val balance: String) {
        /** 币种符号。未知币种原样返回代码，便于排查。 */
        val symbol: String
            get() = when (currency.uppercase()) {
                "CNY" -> "¥"
                "USD" -> "$"
                else -> currency.uppercase()
            }

        /** 解析成 BigDecimal；解析失败按 0 处理（永不抛）。 */
        fun amount(): BigDecimal =
            runCatching { BigDecimal(balance) }.getOrDefault(BigDecimal.ZERO)
    }

    /** 可用的主币种（优先 CNY；没有 CNY 时取第一个）。没有钱包时为 null。 */
    val primary: Wallet?
        get() = wallets.firstOrNull { it.currency.equals("CNY", true) } ?: wallets.firstOrNull()

    /** 充值 + 赠送 合计（同币种相加）。 */
    fun total(): BigDecimal {
        var sum = BigDecimal.ZERO
        for (w in wallets) sum = sum.add(w.amount())
        for (w in bonusWallets) sum = sum.add(w.amount())
        return sum
    }

    /** 有没有任何可用数据（刷新成功过）。 */
    val ready: Boolean get() = at > 0L && wallets.isNotEmpty()

    /** 赠送总额。 */
    fun bonusTotal(): BigDecimal {
        var sum = BigDecimal.ZERO
        for (w in bonusWallets) sum = sum.add(w.amount())
        return sum
    }

    /**
     * 按模板渲染成徽标文字。
     *
     * 占位符见 [BalanceBadge.Companion.TEMPLATE_TOKENS]。未知占位符**原样保留**，
     * 这样用户一眼能看出自己写错了哪个，而不是得到一个被悄悄吃掉一段的文字。
     *
     * @param template 形如 `"{currency}{balance}"`。
     * @returns 渲染结果；数据未就绪时返回空串（调用方据此隐藏徽标）。
     */
    fun render(template: String): String {
        if (!ready) return ""
        val p = primary ?: return ""
        val values = mapOf(
            "balance" to money(total()),
            "recharge" to money(p.amount()),
            "bonus" to money(bonusTotal()),
            "currency" to p.symbol,
            "cost" to money(costLast),
            "costSession" to money(costSession),
        )
        var out = template
        for ((k, v) in values) out = out.replace("{$k}", v)
        return out.trim()
    }

    /** 金额格式化：两位小数，去掉无意义的负零。 */
    private fun money(v: BigDecimal): String {
        val r = v.setScale(2, java.math.RoundingMode.DOWN)
        return if (r.compareTo(BigDecimal.ZERO) == 0) "0.00" else r.toPlainString()
    }

    /** 序列化成 JSON（下发给插件/工具，也用于日志）。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("ready", ready)
        put("at", at)
        put("error", error)
        put("total", total().toPlainString())
        put("bonus", bonusTotal().toPlainString())
        put("currency", primary?.currency ?: "")
        put("symbol", primary?.symbol ?: "")
        put("costLast", costLast.toPlainString())
        put("costSession", costSession.toPlainString())
        put(
            "wallets",
            JSONArray().apply {
                for (w in wallets) put(JSONObject().put("currency", w.currency).put("balance", w.balance))
            },
        )
        put(
            "bonusWallets",
            JSONArray().apply {
                for (w in bonusWallets) put(JSONObject().put("currency", w.currency).put("balance", w.balance))
            },
        )
    }

    companion object {
        /**
         * 从「钱包数组 + 赠送数组」构造。
         *
         * @param rechargeJson `[{currency,balance},…]`
         * @param bonusJson 同上
         * @param costLast 本次花费
         * @param previous 上一次的快照（用于累加会话花费）
         */
        fun fromWallets(
            rechargeJson: JSONArray?,
            bonusJson: JSONArray?,
            costLast: BigDecimal,
            previous: BalanceSnapshot?,
        ): BalanceSnapshot {
            fun parse(arr: JSONArray?): List<Wallet> {
                if (arr == null) return emptyList()
                val out = ArrayList<Wallet>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val cur = o.optString("currency", "").trim()
                    val bal = o.optString("balance", "").trim()
                    if (cur.isEmpty() || bal.isEmpty()) continue
                    out.add(Wallet(cur, bal))
                }
                return out
            }
            val session = (previous?.costSession ?: BigDecimal.ZERO).add(costLast)
            return BalanceSnapshot(
                wallets = parse(rechargeJson),
                bonusWallets = parse(bonusJson),
                costLast = costLast,
                costSession = session,
                at = System.currentTimeMillis(),
            )
        }
    }
}
