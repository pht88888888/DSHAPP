package com.dsh.android.bubble

import com.dsh.android.ui.BalanceSnapshot
import java.math.BigDecimal

/** 内存余额、花费变化和徽标展示判据；不拥有 WindowManager 或实际位置。 */
internal class BubbleBalancePresenter {
    data class Model(val text: String, val visible: Boolean)

    var snapshot: BalanceSnapshot? = null
        private set
    private var lastCost = BigDecimal.ZERO

    /** 返回是否发生真实数值变化；小数位不同但金额相同不闪烁。 */
    fun submit(value: BalanceSnapshot): Boolean {
        val changed = value.costLast.compareTo(lastCost) != 0
        snapshot = value
        lastCost = value.costLast
        return changed
    }

    fun present(enabled: Boolean, hideWhenIdle: Boolean, template: String): Model {
        val text = snapshot?.render(template) ?: ""
        val idle = hideWhenIdle && (snapshot?.costLast?.compareTo(BigDecimal.ZERO) ?: 0) <= 0
        return Model(text, enabled && text.isNotEmpty() && !idle)
    }
}
