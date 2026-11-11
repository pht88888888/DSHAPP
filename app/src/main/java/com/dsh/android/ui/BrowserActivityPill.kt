package com.dsh.android.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 对话界面上的浏览器操作提示；只拥有自己的视图、动画和收起计时器。 */
internal class BrowserActivityPill(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { hide() }
    private var closed = false
    private val label = TextView(context).apply {
        setTextColor(0xFFF2F4F7.toInt())
        textSize = 12.5f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    init {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(9), dp(17), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xE620242B.toInt())
        }
        elevation = dp(8).toFloat()
        alpha = 0f
        visibility = View.GONE
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE0A33A.toInt())
            }
        }
        addView(dot, LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(9) })
        addView(label)
    }

    fun showAction(text: String) {
        if (closed) return
        label.text = text
        if (visibility != View.VISIBLE) {
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            scaleX = 0.92f
            scaleY = 0.92f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
        }
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 2600L)
    }

    fun hideSoon() {
        if (closed) return
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 900L)
    }

    fun close() {
        closed = true
        handler.removeCallbacks(hideTask)
        animate().cancel()
    }

    private fun hide() {
        if (closed || visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(180L)
            .withEndAction { if (visibility == View.VISIBLE) visibility = View.GONE }
            .start()
    }
}
