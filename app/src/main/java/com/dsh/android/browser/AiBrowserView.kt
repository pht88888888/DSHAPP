package com.dsh.android.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Environment
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

/**
 * AI 浏览器 —— 一个**独立于官方界面**的真 WebView。
 *
 * ## 为什么不用引擎自带的侧栏浏览器
 *
 * 那个是 **iframe** 方案，而 GitHub（`X-Frame-Options: deny`）、Google（CSP）
 * 这类网站**拒绝被嵌入**。也就是说 iframe 方案连登录页都加载不出来 ——
 * 而「让 AI 登录网站」恰恰是需求的核心。
 *
 * 原生 WebView 没有这个限制，而且：
 * - 渲染在本地，**零延迟**（对比服务端跑 Chromium 再截图流式推送）
 * - Cookie 天然持久
 * - 用户可以直接手指操作，和普通浏览器一样
 * - **不消耗任何服务器资源**（每个用户跑在自己手机上）
 *
 * ## 与官方界面 WebView 的关系
 *
 * 两者是**独立的 WebView 实例**，各自有独立的浏览状态。共享同一个
 * `WebView` 数据目录（Android 默认行为），所以 Cookie 是共享的 ——
 * ⚠️ 这意味着 AI 登录某网站后，官方界面也会带上那份 Cookie。目前判断
 * 是可接受的（官方界面只用 `dsh-auth-*` 自己的 cookie），但如果以后发现
 * 互相污染，可以用 `WebView.setDataDirectorySuffix()` 隔离。
 *
 * ## 控制权仲裁
 *
 * AI 和用户操作同一个 View，同时操作会互相干扰（AI 输入文字时用户一点就跑焦）。
 * 所以有 [Control]：**一次只有一方能操作**，切换由用户决定（点状态按钮）。
 *
 * ## ⚠️ 不要再用 `ImageButton` 装文字
 *
 * `ImageButton` 继承的是 `ImageView`（`View` 的直接子类），**不是 `ViewGroup`**。
 * 早先的实现在 `ImageButton(context).apply { addView(tv, ...) }` 里加子视图 ——
 * Kotlin 的隐式接收者解析失败后会一路找到**外层 [FrameLayout] 的 `addView`**，
 * 于是那些 `TextView` 被加到了根布局左上角（被页面盖住），而按钮本身是个
 * 空的 `ImageButton`：**透明、无 src、宽高 0** ⇒ 工具栏上什么都不显示。
 * 这个失败是完全静默的（编译通过、不报错、只是看不见）。
 * 现在一律用 `TextView` 当按钮，不再有任何子视图。
 */
class AiBrowserView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "AiBrowser"

        /** 首页。选 GitHub 是因为它同时具备「登录表单 + 大量可点元素」两个特征。 */
        const val HOME_URL = "https://github.com"

        /** 工具栏高度（dp）。进度条与页面容器都按它对齐，**只能有一个来源**。 */
        private const val BAR_H_DP = 46

        /** 深色：贴近 DSH 深色主题（body 背景实测 rgb(67,69,74)）。 */
        internal val DARK_PALETTE = Palette(
            bar = 0xFF22252B.toInt(),
            barAlt = 0xFF1B1E23.toInt(),
            field = 0xFF2C3037.toInt(),
            fieldActive = 0xFF3A3F48.toInt(),
            text = 0xFFE8EAED.toInt(),
            textDim = 0xFF9AA0A8.toInt(),
            root = 0xFF1B1E23.toInt(),
            pill = 0xF22C3037.toInt(),
            pillText = 0xFFF2F4F7.toInt(),
            accent = 0xFF6FA8DC.toInt(),
            aiAccent = 0xFFE0A33A.toInt(),
        )

        /** 浅色：贴近 DSH 浅色主题（body 背景实测 rgb(248,249,250)）。 */
        internal val LIGHT_PALETTE = Palette(
            bar = 0xFFF1F3F5.toInt(),
            barAlt = 0xFFE8EAED.toInt(),
            field = 0xFFE4E7EB.toInt(),
            fieldActive = 0xFFD3D8DE.toInt(),
            text = 0xFF1F2328.toInt(),
            textDim = 0xFF6B7280.toInt(),
            root = 0xFFFFFFFF.toInt(),
            pill = 0xF2FFFFFF.toInt(),
            pillText = 0xFF1F2328.toInt(),
            accent = 0xFF2C6FB5.toInt(),
            aiAccent = 0xFFB4700C.toInt(),
        )
    

        /** 标签条高度（dp）。只在标签数 > 1 时占位。 */
        private const val TABBAR_H_DP = 34

        /** 标签数上限。每个标签一个 WebView，各自吃几十 MB，硬顶住 6 个。 */
        private const val MAX_TABS = 6
    }

    /** 谁在操作。 */
    enum class Control { AI, USER }

    /**
     * 浏览器外框的配色。深/浅两套，跟随 DSH 主题。
     *
     * ## 为什么要从 DSH 那边取，而不是自己定死
     *
     * 这个浏览器是**贴在 DSH 界面里**的（右侧栏面板），不是独立窗口。它自
     * 己一套紫黑配色摆在 DSH 浅色主题旁边，看起来像从别的 App 里抠出来的一块。
     * 而原生这个 WebView 是**独立实例**，既不知道 DSH 现在是深是浅，也拿不到
     * DSH 的配色变量 —— 只能由面板插件（跑在 DSH 页面里）读 `getComputedStyle`
     * 后顺路上报，见 `browser-pane/lib/client.js` 的 `themePayload()`。
     *
     * ## 为什么不是「一个背景色 + 算亮度」
     *
     * 试过从上报的 `bg` 反推整套色阶（叠白/叠黑算百分比），结果是灰阶偏移，
     * 跟 DSH 的观感差一截。DSH 的主题色本来就只有深浅两种，直接各写一套
     * 精确色值更可控，也不会因为某个站点背景透明就算出一片死黑。
     *
     * @param dark 是否深色主题。
     * @param bgHint DSH 报上来的背景色（`rgb(r, g, b)` 或 `#rrggbb`），可能为空。
     */
    internal data class Palette(
        val bar: Int,
        val barAlt: Int,
        val field: Int,
        val fieldActive: Int,
        val text: Int,
        val textDim: Int,
        val root: Int,
        val pill: Int,
        val pillText: Int,
        val accent: Int,
        val aiAccent: Int,
    )

    private var dark: Boolean = true
    private var palette: Palette = DARK_PALETTE


    /**
     * 一个标签页 = 一个独立 WebView。
     *
     * 之所以用**多个 WebView 实例**而不是一个 WebView 换 URL：切换标签时
     * 不能丢页面状态（登录到一半切走再切回来，表单要还在）。
     */
    private inner class Tab(val webView: WebView) {
        var title: String = ""
        var label: TextView? = null
        /** 这个标签自己的「等加载完成」队列。 */
        val loadWaiters = ArrayDeque<(Boolean) -> Unit>()
    }

    /**
     * 静默输入：**AI 操作页面期间**禁止这个 WebView 弹软键盘。
     *
     * **为什么需要**：AI 点一下输入框（`clickJs` 里有 `target.focus()`、
     * `typeJs` 也要 focus），Chromium 内核就向系统申请输入法。用户此时可能
     * 正看着主界面别处 —— 手机却突然弹出安全键盘，等于 AI 隔着界面抢走了
     * 用户的焦点。这不是「AI 在操作浏览器」该有的副作用。
     *
     * **为什么按「AI 操作期间」而不是「面板是否可见」**：侧栏面板每次上报
     * 矩形都会走 `applyEmbedRect`，而 `BrowserBridge` 每次取 view 都会调
     * `browserOrNull()` → `openAiBrowser()`。可见性在 AI 连续操作期间一直是
     * 「可见」，用它当开关根本挡不住 AI 自己引发的 focus。
     *
     * 置 true 后 [QuietWebView] 的 `onCreateInputConnection` 返回 null，
     * 内核拿不到输入连接就不会拉起 IME；页面里的 `focus()`、`click()`、
     * 输入值写入（走原生 setter）都不受影响。
     *
     * **用户触摸会强制解除**（见 [onTouchEvent] 的覆盖），所以用户想自己输入
     * 时不会被挡住。
     */
    @Volatile
    var silentInput: Boolean = false
        set(value) {
            field = value
            if (value) hideIme()
        }

    /** 收起当前输入法。 */
    private fun hideIme() {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    /**
     * 静默模式下不创建输入连接的 WebView。
     *
     * `onCreateInputConnection` 返回 null ⇒ 内核认为没有输入法可用 ⇒
     * 不会申请显示软键盘。这是唯一可靠的做法：`WebSettings` 没有对应开关，
     * 而 `InputMethodManager.hideSoftInputFromWindow` 只能事后收起，
     * 挡不住「弹一下再收」的闪现。
     *
     * ⚠️ **真实触摸要放行**：`isUserTouching` 由 [onInterceptTouchEvent] 在
     *   用户手指按下时置位。用户主动点输入框时必须能正常弹出键盘，否则
     *   「AI 和用户共用一个浏览器」里的接管就废了。
     */
    private inner class QuietWebView(context: Context) : WebView(context) {
        override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo):
            android.view.inputmethod.InputConnection? {
            if (silentInput && !isUserTouching) return null
            return super.onCreateInputConnection(outAttrs)
        }
    }

    /** 用户手指是否正按在浏览器上（触摸期间一律允许弹键盘）。 */
    @Volatile
    private var isUserTouching: Boolean = false

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                isUserTouching = true
                // 用户接管：解除静默，后续他点输入框能正常弹键盘。
                silentInput = false
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL,
            -> isUserTouching = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    // ── 子视图 ──────────────────────────────────────────────────────────

    private val pageHost = FrameLayout(context)
    private val tabBar: LinearLayout
    private val tabStrip: LinearLayout
    private val addressBar: TextView
    private val progress: ProgressBar
    private val statusChip: TextView
    private val backBtn: TextView
    private val forwardBtn: TextView

    /**
     * 「AI 正在做什么」胶囊。
     *
     * 用 lateinit 而不是在声明处初始化：它要加到本 View 上，而 `init {}` 里
     * 才开始装配子视图，声明顺序在 `addView` 之前拿不到 context 的合适时机。
     */
    private lateinit var actionPill: LinearLayout

    /** 胶囊里的呼吸圆点与文字。 */
    private lateinit var actionDot: View
    private lateinit var actionText: TextView

    /** 顶部工具栏。留字段是为了 [applyPalette] 能重刷它的底色。 */
    private var chromeBar: LinearLayout? = null

    private val tabs = ArrayList<Tab>()
    private var activeIndex = -1

    /** 当前控制方。默认用户（AI 没在操作时，用户随时能用）。 */
    private var control: Control = Control.USER

    /** 状态变化回调（通知外部 UI 刷新）。 */
    var onStateChanged: (() -> Unit)? = null

    /** 用户点了 ✕。宿主负责隐藏/销毁。 */
    var onClose: (() -> Unit)? = null

    /**
     * 页面请求选文件。宿主必须走系统的文件选择器，最后回调
     * `callback.onReceiveValue(uris)` 或 `callback.onReceiveValue(null)`。
     */
    var onPickFile: ((ValueCallback<Array<Uri>>) -> Unit)? = null

    /** 截图落盘目录（由宿主指定，通常是 workspace 下的隐藏目录）。 */
    var shotDir: File? = null

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    init {
        setBackgroundColor(palette.root)

        val barH = dp(BAR_H_DP)
        val tabH = dp(TABBAR_H_DP)

        // ── 工具栏 ──────────────────────────────────────────────────────
        val chrome = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.bar)
            setPadding(dp(4), 0, dp(4), 0)
        }
        chromeBar = chrome

        backBtn = toolButton("←") { if (currentWebView()?.canGoBack() == true) currentWebView()?.goBack() }
        forwardBtn = toolButton("→") { if (currentWebView()?.canGoForward() == true) currentWebView()?.goForward() }
        val reloadBtn = toolButton("⟳") { currentWebView()?.reload() }
        val homeBtn = toolButton("⌂") { loadUrl(HOME_URL) }

        addressBar = TextView(context).apply {
            setTextColor(palette.text)
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = rounded(palette.field, 8)
            text = HOME_URL
            setOnClickListener { showUrlEditor() }
        }

        statusChip = TextView(context).apply {
            setTextColor(palette.accent)
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(palette.field, 8)
            text = "你"
            isClickable = true
            setOnClickListener { toggleControl() }
        }

        val closeBtn = toolButton("✕") { onClose?.invoke() }

        chrome.addView(backBtn)
        chrome.addView(forwardBtn)
        chrome.addView(reloadBtn)
        chrome.addView(homeBtn)
        chrome.addView(
            addressBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            },
        )
        chrome.addView(
            statusChip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(4) },
        )
        chrome.addView(closeBtn)

        // ── 标签条（底部）──────────────────────────────────────────────
        tabStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.barAlt)
            setPadding(dp(4), 0, dp(4), 0)
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(
                        tabStrip,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(toolButton("＋", 32) { openNewTab(null) })
            visibility = View.GONE
        }

        // ── 加载进度 ────────────────────────────────────────────────────
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }

        addView(pageHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = barH
            bottomMargin = 0
        })
        addView(chrome, LayoutParams(LayoutParams.MATCH_PARENT, barH))
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = barH })

        // ── AI 动作胶囊（浮在页面顶部，紧贴工具栏下方）──────────────────
        // 用户打开侧边栏时只能看到浏览器，看不到对话流。没有这条反馈，
        // AI 每次操作他都只能对着一个不动的页面干等，不知道是在干活还是卡住了。
        // 外层容器负责圆角渐变底，里面横排「呼吸小圆点 + 文字」。
        // 用容器而不是把背景画在 TextView 上：圆点要和文字垂直居中对齐，
        // 且左右 padding 要对称（TextView 自带的 drawableLeft 对不齐多行文字）。
        actionPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(9), dp(17), dp(9))
            background = pillBackground()
            visibility = View.GONE
            elevation = dp(8).toFloat()
            alpha = 0f
        }

        // 呼吸小圆点：让胶囊看起来"活着"，而不是一段静止文字。
        actionDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.aiAccent)
            }
        }
        actionPill.addView(
            actionDot,
            LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(9) },
        )

        actionText = TextView(context).apply {
            setTextColor(palette.pillText)
            textSize = 12.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        actionPill.addView(actionText)

        addView(
            actionPill,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = barH + dp(12)
                leftMargin = dp(16)
                rightMargin = dp(16)
                // 屏幕上方居中（用户明确要求）。用 CENTER_HORIZONTAL 而不是
                // START：胶囊宽度是随文字变的，靠左会随文案长度左右跳。
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            },
        )

        addView(
            tabBar,
            LayoutParams(LayoutParams.MATCH_PARENT, tabH).apply { gravity = Gravity.BOTTOM },
        )

        openNewTab(HOME_URL)
    }

    // ── AI 动作胶囊 ─────────────────────────────────────────────────────
    //
    // 用户打开侧边栏时只能看到浏览器，看不到对话流。没有这条反馈，AI 每次
    // 操作他都只能对着一个不动的页面干等，不知道是在干活还是卡住了。
    //
    // ## 停留策略（用户明确要求）
    //
    // · **每个状态至少显示 5 秒** —— 一个 click 往往只花 80ms，之前「操作结束
    //   就淡出」的结果是文字一闪而过，根本看不清。
    // · **新状态直接替换**，不排队、不等待 —— 排队会让胶囊里的文字远远落后于
    //   AI 的实际动作，反而更让人困惑。
    //
    // 于是用两个时间戳来判断：`pillShownAt` 记它什么时候出现，`pillDueAt` 记
    // 最早什么时候可以收。操作结束时只把「到点该收」这件事挂上去，真正收不收
    // 由 [pillSweep] 按 5 秒下限决定。

    /** 胶囊当前这轮文字的起始时刻（`SystemClock.elapsedRealtime()`）。 */
    private var pillShownAt = 0L

    /** 最早可以收走的时刻。新状态进来会重置它。 */
    private var pillDueAt = 0L

    /** 这轮操作是否已经结束（结束后才允许按 5 秒下限收走）。 */
    private var pillOpDone = false

    /** 胶囊最短停留时长（用户要求 5 秒）。 */
    private val PILL_MIN_MS = 5_000L

    /** 到点后淡出的时长。 */
    private val PILL_FADE_MS = 260L

    /**
     * 显示/更新「AI 正在做什么」胶囊。
     *
     * 文案与当前显示相同则不重置计时（否则 AI 连发同一条描述时，胶囊会一直
     * 赖在屏幕上不消失）。
     */
    fun showAction(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            hideActionNow()
            return
        }
        val pill = actionPill
        val now = android.os.SystemClock.elapsedRealtime()
        if (pill.visibility == View.VISIBLE && actionText.text.toString() == t) {
            // 同一句描述：只是新一轮操作，重新给满 5 秒，但不重新播入场动画。
            pillOpDone = false
            pillDueAt = now + PILL_MIN_MS
            return
        }
        pill.removeCallbacks(pillSweep)
        pill.animate().cancel()
        actionText.text = t
        pillOpDone = false
        pillShownAt = now
        pillDueAt = now + PILL_MIN_MS
        if (pill.visibility != View.VISIBLE) {
            pill.alpha = 0f
            pill.visibility = View.VISIBLE
        }
        // 入场：轻微放大 + 淡入。比纯淡入「灵动」一点，又不至于晃眼。
        pill.scaleX = 0.92f
        pill.scaleY = 0.92f
        pill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        startPulse()
    }

    /**
     * 圆点呼吸：透明度 1 → 0.35 → 1，1.1s 一轮，无限循环。
     *
     * 为什么值得加：胶囊本来是**静止**的一段文字，看起来像贴在页面上的标签。
     * 有了呼吸，它读起来是「正在发生」而不是「一条过期的通知」。animation
     * 走 ViewPropertyAnimator 而不是 ValueAnimator，省掉手动 cancel 的样板。
     */
    private fun startPulse() {
        val dot = actionDot
        dot.animate().cancel()
        dot.alpha = 1f
        dot.animate()
            .alpha(0.35f)
            .setDuration(1_100L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                dot.animate()
                    .alpha(1f)
                    .setDuration(1_100L)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction { startPulse() }
                    .start()
            }
            .start()
    }

    /** 停掉呼吸（收胶囊时用，否则动画会在隐藏的 View 上空转）。 */
    private fun stopPulse() {
        actionDot.animate().cancel()
        actionDot.alpha = 1f
    }

    /**
     * 操作结束：**不立刻收**，交给 [pillSweep] 按 5 秒下限处理。
     *
     * 注意这里已经**没有**延迟参数了：收不收由「至少显示 5 秒」这条规则决定，
     * 调用方不需要（也不应该）自己算时长。
     */
    fun hideActionSoon() {
        pillOpDone = true
        scheduleSweep()
    }

    /** 立刻收走胶囊（超时、异常等「操作没正常结束」的路径用）。 */
    fun hideActionNow() {
        val pill = actionPill
        stopPulse()
        pill.removeCallbacks(pillSweep)
        pill.animate().cancel()
        pill.visibility = View.GONE
        pill.alpha = 0f
        pillOpDone = false
        pillDueAt = 0L
    }

    /** 安排一次到点检查：不早于 `pillDueAt`。 */
    private fun scheduleSweep() {
        val pill = actionPill
        pill.removeCallbacks(pillSweep)
        if (!pillOpDone) return
        val wait = (pillDueAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        pill.postDelayed(pillSweep, wait)
    }

    /**
     * 到点检查：真的到点了才淡出。
     *
     * ⚠️ 必须**再核一次**时间，不能直接 fade：`postDelayed` 可能因为主线程
     *   忙（页面正在加载）而延后，也可能反过来被提前调度。以时间戳为准。
     */
    private val pillSweep = object : Runnable {
        override fun run() {
            val pill = actionPill
            if (pill.visibility != View.VISIBLE) return
            if (!pillOpDone) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < pillDueAt) {
                pill.postDelayed(this, pillDueAt - now)
                return
            }
            pill.animate().cancel()
            pill.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(PILL_FADE_MS)
                .withEndAction {
                    pill.visibility = View.GONE
                    pill.scaleX = 1f
                    pill.scaleY = 1f
                    stopPulse()
                }
                .start()
        }
    }

    /**
     * 胶囊底：渐变描边 + 半透明填充。
     *
     * 用户嫌之前"太朴素"（纯色圆角 + 1dp 灰边，确实就是个普通标签）。这里叠三层：
     *
     * 1. **渐变描边**（不透明层 → 用 [GradientDrawable.setStroke] 加不了渐变，
     *    所以整体做一个「外圈渐变 + 内圈填充」的 [LayerDrawable] 套娃）：
     *    外层是 accent 系的双色渐变圆角矩形，内层比外层小 1.5dp、用实心底色盖住，
     *    露出来那一圈就是渐变描边。
     * 2. **柔和高光**：内层再叠一层从上到下的白色 → 透明线性渐变（深色主题下
     *    透明度低一些，浅色下略高），做出一点立体感，不是死板的纯色块。
     * 3. **圆角 24dp**：接近半高的胶囊形，比 22dp 更"圆润"。
     *
     * ⚠️ 描边宽度用 1.5dp 而不是 1dp：1dp 在 3x 屏上太细，渐变基本看不出来。
     */
    private fun pillBackground(): Drawable {
        val radius = dp(24).toFloat()
        val strokeW = dp(1.5f.toInt().coerceAtLeast(1)).coerceAtLeast(1)

        // 渐变描边：accent → aiAccent 斜向过渡。
        val ring = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                (if (dark) 0xFF7FB2E8 else 0xFF2C6FB5).toInt(),
                (if (dark) 0xFFE0A33A else 0xFFB4700C).toInt(),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0x00000000)   // 只当描边用，中间会被内层盖住
        }

        // 内层填充：半透明底 + 顶部高光。
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (dark) 0xF2383D45.toInt() else 0xF7FFFFFF.toInt(),
                if (dark) 0xF22A2E35.toInt() else 0xF2F4F6F8.toInt(),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

        val inner = android.graphics.drawable.InsetDrawable(fill, strokeW, strokeW, strokeW, strokeW)
        return android.graphics.drawable.LayerDrawable(arrayOf(ring, inner))
    }

    // ── 标签管理 ────────────────────────────────────────────────────────

    private fun currentTab(): Tab? = tabs.getOrNull(activeIndex)

    private fun currentWebView(): WebView? = currentTab()?.webView

    /** 新建标签并切过去。`url == null` 时用首页。 */
    fun openNewTab(url: String?): Boolean {
        if (tabs.size >= MAX_TABS) {
            Log.w(TAG, "标签已达上限 $MAX_TABS，不再新建")
            return false
        }
        val wv = QuietWebView(context)
        configureWebView(wv)
        val tab = Tab(wv)
        tabs.add(tab)
        activeIndex = tabs.size - 1
        showActiveTab()
        wv.loadUrl(normalizeUrl(url ?: HOME_URL))
        rebuildTabStrip()
        // ⚠️ 必须重新套一次控制权：applyControl 是**逐个 WebView** 设
        //    OnTouchListener 的，上面刚 new 的这个还没被设过。AI 控制期间
        //    新开标签，如果漏了这句，状态条显示「AI 操作中」但用户照样能点
        //    —— 正是「显示 AI 操作却可以操作」那个 bug。
        applyControl()
        return true
    }

    /** 关掉当前标签；关到只剩一个时等价于点关闭整个浏览器。 */
    fun closeCurrentTab() {
        val tab = currentTab() ?: return
        if (tabs.size <= 1) {
            onClose?.invoke()
            return
        }
        tabs.remove(tab)
        releaseTab(tab)
        if (activeIndex >= tabs.size) activeIndex = tabs.size - 1
        showActiveTab()
        rebuildTabStrip()
        updateChrome()
    }

    /** 切到第 [index] 个标签（越界忽略）。 */
    fun selectTab(index: Int): Boolean {
        if (index !in tabs.indices || index == activeIndex) return false
        activeIndex = index
        showActiveTab()
        rebuildTabStrip()
        updateChrome()
        return true
    }

    /** 当前所有标签的 `[标题, url]`，给 AI 看。 */
    fun listTabs(): List<Triple<Int, String, String>> =
        tabs.mapIndexed { i, t -> Triple(i, t.title.ifEmpty { t.webView.title ?: "" }, t.webView.url ?: "") }

    private fun showActiveTab() {
        pageHost.removeAllViews()
        val wv = currentWebView() ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        pageHost.addView(
            wv,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun releaseTab(tab: Tab) {
        try {
            drainWaiters(tab, false)
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.stopLoading()
            tab.webView.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "销毁标签失败: ${t.message}")
        }
    }

    /** 重建底部标签条。只有一个标签时整条隐藏、页面容器不吃边距。 */
    private fun rebuildTabStrip() {
        tabStrip.removeAllViews()
        for ((i, tab) in tabs.withIndex()) {
            val label = TextView(context).apply {
                val text = tab.title.ifEmpty { tab.webView.title.orEmpty() }
                    .ifEmpty { tab.webView.url?.removePrefix("https://")?.removePrefix("http://") ?: "新标签页" }
                this.text = text.take(14)
                textSize = 10.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), 0, dp(9), 0)
                setTextColor(if (i == activeIndex) palette.text else palette.textDim)
                background = rounded(if (i == activeIndex) palette.fieldActive else 0x00000000, 7)
                isClickable = true
                setOnClickListener { selectTab(i) }
                setOnLongClickListener { closeTabAt(i); true }
            }
            tab.label = label
            tabStrip.addView(
                label,
                LinearLayout.LayoutParams(dp(96), dp(26)).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                },
            )
        }
        val multi = tabs.size > 1
        tabBar.visibility = if (multi) View.VISIBLE else View.GONE
        (pageHost.layoutParams as? LayoutParams)?.let {
            val want = if (multi) dp(TABBAR_H_DP) else 0
            if (it.bottomMargin != want) {
                it.bottomMargin = want
                pageHost.layoutParams = it
            }
        }
    }

    private fun closeTabAt(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (tabs.size <= 1) {
            onClose?.invoke()
            return
        }
        tabs.remove(tab)
        releaseTab(tab)
        if (activeIndex >= tabs.size) activeIndex = tabs.size - 1
        else if (index < activeIndex) activeIndex -= 1
        showActiveTab()
        rebuildTabStrip()
        updateChrome()
    }

    // ── WebView 配置 ────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.setBackgroundColor(palette.root)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // ⚠️ 现代网站几乎都是响应式，但 targetSdk=28 时 WebView 默认会
            //    用旧版 viewport。这两项让网页按手机宽度正常渲染。
            useWideViewPort = true
            loadWithOverviewMode = true
            // 允许缩放：有些登录页元素很小
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // ⚠️ mixedContent 用 ALWAYS_ALLOW：部分网站的登录跳转是 http 中转，
            //    默认 NEVER_ALLOW 会让那些流程断在中间。安全性由「AI 只访问
            //    用户认可的站点」这个使用场景承担，不是这一项。
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 保存表单（用户名等会记住）
            saveFormData = true
            userAgentString = userAgentString.replace("; wv)", ")")
        }

        // Cookie 持久化：这是「登录状态重启后仍保持」的关键
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (sameWebView(view)) {
                    progress.visibility = View.VISIBLE
                    progress.progress = 0
                }
                updateChrome()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // ⚠️ 每次加载完成都 flush —— 否则登录 Cookie 可能只在
                //    WebView 内存里，App 被系统杀掉就丢了。
                CookieManager.getInstance().flush()
                // ⚠️ **再补一次延迟 flush**，这条是给 AJAX 登录用的。
                //    提交登录表单后页面**不跳转**，JS 拿到 token 才写 Cookie /
                //    localStorage，那一刻 `onPageFinished` 早就跑完了。
                //    延迟 2.5s 是给内核的存储层留出真正落盘的时间：
                //    Cookie 有 `flush()` 可以立刻催，但 **localStorage 没有**
                //    （Chromium 自己按周期把 LevelDB 刷盘），只能等。
                //    豆包这类站点的登录态主要在 localStorage，光 flush Cookie 不够。
                postDelayed({ runCatching { CookieManager.getInstance().flush() } }, 2500L)
                tabOf(view)?.let {
                    it.title = view?.title.orEmpty()
                    drainWaiters(it, true)
                }
                if (sameWebView(view)) {
                    progress.visibility = View.GONE
                    updateChrome()
                    rebuildTabStrip()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // 全部在内部打开，不跳系统浏览器 —— 否则 AI 就失去了那个页面
                return false
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!sameWebView(view)) return
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val tab = tabOf(view) ?: return
                tab.title = title.orEmpty()
                rebuildTabStrip()
            }

            /**
             * 文件上传。
             *
             * ⚠️ **必须返回 true**：返回 false 会让系统走默认处理，在部分机型上
             *   直接抛 `ActivityNotFoundException`（页面白屏）。
             * ⚠️ 无论宿主选没选，`callback` 都必须收到一次 `onReceiveValue`，
             *   否则页面永久卡在「正在等待文件」。
             */
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val cb = filePathCallback
                val picker = onPickFile
                if (cb == null || picker == null) {
                    cb?.onReceiveValue(null)
                    pendingFileCallback = null
                    return true
                }
                picker(cb)
                return true
            }
        }

        // 下载：存到 App 专属外部目录（不需要任何存储权限，文件管理器可见）
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /**
     * 把一次下载交给系统的 `DownloadManager`。
     *
     * 落到 `Android/data/com.dsh.android/files/Download/` —— 这是 App 专属外部
     * 目录，**不需要 `WRITE_EXTERNAL_STORAGE`**（targetSdk 28 下写公共
     * Download 目录是要权限的，而为了下一张图片去申请存储权限不值得）。
     */
    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        try {
            val name = guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                userAgent?.let { addRequestHeader("User-Agent", it) }
                mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                setTitle(name)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, name,
                )
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("开始下载：$name")
            Log.i(TAG, "下载已入队: $url → Download/$name")
        } catch (t: Throwable) {
            Log.w(TAG, "下载失败: ${t.message}")
            toast("下载失败：${t.message}")
        }
    }

    /** 从 `Content-Disposition` 或 URL 末段猜文件名，并去掉路径分隔符。 */
    private fun guessFileName(url: String, cd: String?, mime: String?): String {
        var raw = ""
        if (!cd.isNullOrBlank()) {
            val m = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(cd)
            raw = m?.groupValues?.getOrNull(1).orEmpty()
            if (raw.isNotEmpty()) {
                raw = try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (t: Throwable) {
                    raw
                }
            }
        }
        if (raw.isBlank()) {
            raw = Uri.parse(url).lastPathSegment.orEmpty()
        }
        raw = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimStart('.')
        if (raw.isBlank()) {
            raw = "download-" + System.currentTimeMillis() +
                when {
                    mime?.contains("png") == true -> ".png"
                    mime?.contains("jpeg") == true -> ".jpg"
                    mime?.contains("pdf") == true -> ".pdf"
                    else -> ""
                }
        }
        return raw.take(80)
    }

    // ── 导航 ────────────────────────────────────────────────────────────

    fun loadUrl(url: String) {
        val fixed = normalizeUrl(url)
        val wv = currentWebView() ?: return
        addressBar.text = fixed
        wv.loadUrl(fixed)
    }

    /**
     * `github.com` → `https://github.com`；空串 → 首页；非域名 → 搜索引擎。
     *
     * 搜索走 GitHub 而不是 Google/Bing：后者在国内手机上经常连不上，
     * 会让「AI 搜一下」变成一次 10 秒的等待超时。
     */
    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return HOME_URL
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://")) return t
        if (t.startsWith("about:")) return t
        return if (t.contains('.') && !t.contains(' ')) {
            "https://$t"
        } else {
            "https://github.com/search?q=${java.net.URLEncoder.encode(t, "UTF-8")}"
        }
    }

    private fun showUrlEditor() {
        val input = android.widget.EditText(context).apply {
            setText(currentWebView()?.url ?: HOME_URL)
            setSelectAllOnFocus(true)
            setTextColor(palette.text)
            setHintTextColor(palette.textDim)
            setBackgroundColor(palette.field)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("打开网址")
            .setView(input)
            .setPositiveButton("前往") { _, _ -> loadUrl(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 控制权 ──────────────────────────────────────────────────────────

    /**
     * 切换控制权。
     *
     * ⚠️ AI 操作时**吃掉所有触摸**（返回 true 不往下传），否则用户随手一点
     *   就会让 AI 正在填的表单失焦。用户点状态按钮切回自己后恢复正常。
     */
    private fun toggleControl() {
        control = if (control == Control.AI) Control.USER else Control.AI
        applyControl()
    }

    fun setControl(holder: Control) {
        control = holder
        applyControl()
    }

    /** AI 执行动作前抢占控制权（用户点 chip 可随时收回）。 */
    fun claimAiControl() {
        if (control != Control.AI) setControl(Control.AI)
    }

    fun currentControl(): Control = control

    @SuppressLint("ClickableViewAccessibility")
    private fun applyControl() {
        for (tab in tabs) {
            if (control == Control.AI) {
                tab.webView.setOnTouchListener { _, _ -> true }   // 吃掉触摸
            } else {
                tab.webView.setOnTouchListener(null)
            }
        }
        updateChrome()
        onStateChanged?.invoke()
    }

    private fun updateChrome() {
        val wv = currentWebView()
        val canBack = wv?.canGoBack() == true
        val canForward = wv?.canGoForward() == true
        backBtn.alpha = if (canBack) 1f else 0.3f
        forwardBtn.alpha = if (canForward) 1f else 0.3f
        statusChip.text = if (control == Control.AI) "AI 操作中" else "你"
        statusChip.setTextColor(
            if (control == Control.AI) palette.aiAccent else palette.accent,
        )
        wv?.url?.let { if (it != addressBar.text) addressBar.text = it }
    }

    // ── 给 AI 用的接口 ──────────────────────────────────────────────────

    /**
     * 执行 JS 并返回结果。
     *
     * ⚠️ 返回值必须用 [JSONTokener] 解包，不能用 `removeSurrounding("\"")`：
     *    `evaluateJavascript` 回的是 **JSON 字面量**，里面 `\"` `\n` `\\` 都是
     *    转义过的。手工去引号在简单字符串上碰巧能work，一旦回传的是 JSON
     *    对象（读 DOM 就是）就会留下成片的 `\"`，模型解析必失败。
     */
    fun evaluate(js: String, onResult: (String?) -> Unit) {
        val wv = currentWebView()
        if (wv == null) {
            onResult(null)
            return
        }
        wv.evaluateJavascript(js) { result ->
            onResult(unwrapJsResult(result))
        }
    }

    private fun unwrapJsResult(result: String?): String? {
        if (result == null || result == "null") return null
        return try {
            when (val v = JSONTokener(result).nextValue()) {
                is String -> v
                JSONObject.NULL -> null
                else -> v.toString()
            }
        } catch (t: Throwable) {
            result
        }
    }

    /** 读当前页面 URL。 */
    fun currentUrl(): String = currentWebView()?.url ?: ""

    /**
     * 往当前 WebView 注入一个**真实**按键（走 View 的输入分发，不是 JS 合成事件）。
     *
     * 为什么不能用 JS 的 `dispatchEvent(new KeyboardEvent(...))`：
     *   合成事件的 `isTrusted` 恒为 `false`，浏览器**不执行默认行为**。
     *   于是 Tab 不移动焦点、Enter 不提交表单、方向键不移动光标——页面上的
     *   keydown 监听器能收到事件，但什么也不会发生。只有真实按键才触发默认行为，
     *   这里用 [android.view.View.dispatchKeyEvent] 从 View 根部分发下去。
     *
     * @return 当前标签是否存在（没有 WebView 时返回 false）。
     */
    fun dispatchKey(keyCode: Int): Boolean {
        val wv = currentWebView() ?: return false
        // 按键只有在 WebView 持有输入焦点时才会被分发到页面。
        // AI 接管期间用户可能点过别处（地址栏、面板按钮），焦点不在
        // WebView 上时按键会被别处吃掉，表现仍是「提示成功但没反应」。
        //
        // ⚠️ 但**面板不可见时绝不能抢焦点**：此时用户在主界面别处，
        //    requestFocus + requestFocus 会让 IME（安全键盘）突然弹出来。
        //    静默模式下 WebView 本来就是隐藏的，按键照样能分发到页面
        //    （dispatchKeyEvent 不要求视图可见），所以不抢焦点没有副作用。
        if (!silentInput && !wv.hasFocus()) wv.requestFocus()
        wv.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        wv.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        return true
    }

    fun goBack() { currentWebView()?.let { if (it.canGoBack()) it.goBack() } }
    fun goForward() { currentWebView()?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() { currentWebView()?.reload() }

    /** 当前标签能不能后退（宿主返回键用）。 */
    fun canGoBack(): Boolean = currentWebView()?.canGoBack() == true

    /** 标签总数（宿主返回键用）。 */
    fun tabsCount(): Int = tabs.size

    /** 当前标签序号（0 起；无标签时 -1）。 */
    fun activeTabIndex(): Int = activeIndex

    /** 等当前标签加载完成（AI 导航后调它）。 */
    fun awaitLoad(onDone: (Boolean) -> Unit) {
        val tab = currentTab()
        if (tab == null) {
            onDone(false)
            return
        }
        tab.loadWaiters.addLast(onDone)
    }

    private fun drainWaiters(tab: Tab, ok: Boolean) {
        while (tab.loadWaiters.isNotEmpty()) {
            val cb = tab.loadWaiters.removeFirst()
            try {
                cb(ok)
            } catch (t: Throwable) {
                Log.w(TAG, "加载回调抛异常: ${t.message}")
            }
        }
    }

    /**
     * 截当前视口。
     *
     * ⚠️ `WebView.draw()` **必须在主线程**，而且只能拿到**可见视口** ——
     *   整页长图需要滚动拼接，这里刻意不做（拼接出来的图模型也读不清）。
     */
    fun capture(onBitmap: (Bitmap?) -> Unit) {
        post {
            val wv = currentWebView()
            if (wv == null || wv.width <= 0 || wv.height <= 0) {
                onBitmap(null)
                return@post
            }
            try {
                val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                wv.draw(Canvas(bmp))
                onBitmap(bmp)
            } catch (t: Throwable) {
                Log.w(TAG, "截图失败: ${t.message}")
                onBitmap(null)
            }
        }
    }

    /** 截图并落盘，回调文件绝对路径。 */
    fun captureToFile(onPath: (String?) -> Unit) {
        capture { bmp ->
            if (bmp == null) {
                onPath(null)
                return@capture
            }
            try {
                val dir = shotDir ?: File(context.filesDir, "browser-shots")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "shot-${System.currentTimeMillis()}.png")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                Log.i(TAG, "截图已保存: ${f.absolutePath}")
                onPath(f.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "截图落盘失败: ${t.message}")
                onPath(null)
            }
        }
    }

    // ── 嵌入定位 ────────────────────────────────────────────────────────
    //
    // 官方右侧边栏的「AI 浏览器」是一个**网页面板**，而原生 WebView 不能
    // 放进 DOM 里（iframe 会被 X-Frame-Options / CSP 拒绝，那正是本方案存在的
    // 原因）。所以做法是：前端把面板内容区的矩形（CSS 视口坐标）上报给原生，
    // 原生把本 View 摆到屏幕上那块区域 —— 视觉上就嵌在 tab 下面。
    //
    // 代价：矩形变了要**持续上报**（侧栏宽度、键盘、旋转都会变），由前端的
    // ResizeObserver 负责。

    /**
     * 套用 DSH 当前主题的配色。
     *
     * 由 `MainActivity.aiBrowserLayout()` 在每次面板上报矩形时调用（面板插件
     * 顺路带上了 `dark` / `bg`）。主题没变时**直接返回** —— 这个方法是逐帧
     * 可能被调的，没必要每帧重设一遍所有子视图的背景。
     *
     * @param isDark DSH 是否深色主题。
     * @param bgHint DSH 报上来的背景色，形如 `rgb(67, 69, 74)` / `#43454A`。可空。
     */
    fun applyTheme(isDark: Boolean, bgHint: String) {
        // `bgHint` 目前只做兜底校验、不参与配色：DSH 的深浅两套色值都是
        // 固定的，直接各写一套比从背景色反推色阶准确得多（反推出来是灰阶
        // 偏移，观感差一截）。留着参数是为了将来 DSH 若支持自定义主题色，
        // 这里可以直接接上，不用再改一遍签名。
        @Suppress("UNUSED_EXPRESSION") bgHint
        if (isDark == dark) return
        dark = isDark
        palette = if (isDark) DARK_PALETTE else LIGHT_PALETTE
        applyPalette()
    }

    /** 把 [palette] 刷到所有已建好的子视图上。 */
    private fun applyPalette() {
        setBackgroundColor(palette.root)
        chromeBar?.setBackgroundColor(palette.bar)
        tabBar.setBackgroundColor(palette.barAlt)
        addressBar.setTextColor(palette.text)
        addressBar.background = rounded(palette.field, 8)
        statusChip.background = rounded(palette.field, 8)
        actionText.setTextColor(palette.pillText)
        actionDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.aiAccent)
        }
        actionPill.background = pillBackground()
        for (btn in listOf(backBtn, forwardBtn)) btn.setTextColor(palette.text)
        for (tab in tabs) tab.webView.setBackgroundColor(palette.root)
        for (tab in tabs) tab.label?.setTextColor(if (tabs.indexOf(tab) == activeIndex) palette.text else palette.textDim)
        rebuildTabStrip()
        updateChrome()
    }

    /**
     * 摆到指定矩形（相对**宿主根布局**的像素）。
     *
     * @param left 左边缘（px，相对根布局）
     * @param top 上边缘（px，相对根布局）
     * @param width 宽度（px）
     * @param height 高度（px）
     */
    fun applyEmbedRect(left: Int, top: Int, width: Int, height: Int) {
        val lp = layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(width, height)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val curLeft = lp.leftMargin
        val curTop = lp.topMargin
        if (lp.width == w && lp.height == h &&
            curLeft == left && curTop == top &&
            visibility == View.VISIBLE
        ) {
            return   // 没变就别动（收起动画期间会每帧来一次）
        }

        // ⚠️ **不能用 requestLayout() 移动** —— 那会触发整棵视图树重新测量/布局，
        //   WebView 的表面（SurfaceView / 合成层）随之重建，收起动画期间每帧一次，
        //   屏幕上就是**一闪一闪**（实测录屏里帧差在 160 和 3 之间来回跳）。
        //
        // 正确做法是两条路分开：
        //   · 尺寸没变、只是位置变了（收起动画就是这种）→ offsetLeftAndRight /
        //     offsetTopAndBottom，只平移，不测量、不重建表面；
        //   · 尺寸变了 → 改 layoutParams 的宽高再 requestLayout（这种场景很少：
        //     键盘弹出、旋转、侧栏拖宽，一次性，不会闪）。
        val sizeChanged = lp.width != w || lp.height != h
        if (sizeChanged) {
            lp.width = w
            lp.height = h
            layoutParams = lp
            requestLayout()
        }
        // 平移放在尺寸处理之后：offset* 改的是当前实测位置，requestLayout 之后
        // 会被重新布局覆盖，所以尺寸变了就等下一次上报再对齐位置。
        if (!sizeChanged) {
            val dx = left - curLeft
            val dy = top - curTop
            if (dx != 0 || dy != 0) {
                lp.leftMargin = left
                lp.topMargin = top
                offsetLeftAndRight(dx)
                offsetTopAndBottom(dy)
            }
        }
    }

    /** 退回全屏铺满（面板关掉、或宿主想全屏展示时）。 */
    fun clearEmbedRect() {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == LayoutParams.MATCH_PARENT && lp.leftMargin == 0 && lp.topMargin == 0) {
            return
        }
        lp.width = LayoutParams.MATCH_PARENT
        lp.height = LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.topMargin = 0
        layoutParams = lp
        requestLayout()
    }

    /** 释放：必须在宿主销毁时调用，否则 WebView 泄漏。 */
    fun destroy() {
        for (tab in tabs.toList()) releaseTab(tab)
        tabs.clear()
        pageHost.removeAllViews()
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private fun sameWebView(v: WebView?): Boolean = v != null && v === currentWebView()

    private fun tabOf(v: WebView?): Tab? = tabs.firstOrNull { it.webView === v }

    /**
     * 纯文字按钮。
     *
     * **不要**换成 `ImageButton`（它没有子视图概念，见类注释）。
     */
    private fun toolButton(label: String, widthDp: Int = 34, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(palette.text)
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(38))
            background = pressable()
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Int): Drawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun pressable(): Drawable {
        val content = GradientDrawable().apply {
            setColor(0x00000000)
            cornerRadius = dp(7).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, null)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
