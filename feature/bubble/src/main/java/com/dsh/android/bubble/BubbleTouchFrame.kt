package com.dsh.android.bubble
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 悬浮球收起态的容器：一个**可以把触摸全部截获给自己**的 FrameLayout。
 *
 * ## 为什么需要它
 *
 * 收起态的触摸（拖动、点击展开）由 [FloatingBubble.installTouch] 挂在 `bubbleCircle`
 * 这个 ViewGroup 上。正常情况没问题，但**内容层多了一个 WebView 之后就不行了**：
 * WebView 的触摸处理在自己的 native 层，它会消费掉 `ACTION_DOWN`，于是父容器的
 * `onTouchListener` 根本不会被调用 —— 症状是「球能显示，但拖不动、点不开」。
 *
 * 让 WebView 不消费事件是没有可靠办法的（`isEnabled=false`、`setClickable(false)`
 * 都挡不住 WebView 覆写过的 `onTouchEvent`）。**唯一可靠的位置是父容器的
 * `onInterceptTouchEvent`**：它在事件分发到子 View **之前**执行，返回 true 即可
 * 把整串手势收归自己，子 View 会收到一个 `ACTION_CANCEL` 并被彻底绕过。
 *
 * ## 只在需要时拦截
 *
 * [interceptAll] 为 false 时行为与普通 FrameLayout **逐字相同** —— 也就是说
 * 没有视频素材的用户，悬浮球的事件分发路径与加这一层之前完全一致，不存在回归面。
 * 只有内容层真的换成视频（WebView 可见）时，[FloatingBubble] 才把它打开。
 */
class BubbleTouchFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 是否把触摸全部截获给自己。
     *
     * 由 [FloatingBubble] 按「内容层当前是不是视频」设置。
     */
    var interceptAll: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        interceptAll || super.onInterceptTouchEvent(ev)
}
