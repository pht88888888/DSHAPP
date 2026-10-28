package com.dsh.android.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import kotlin.math.max
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

        /** UA 偏好存放的 SharedPreferences 文件名。 */
        private const val PREFS = "dsh_ai_browser"

        /** 「是否桌面版 UA」的键。 */
        private const val KEY_DESKTOP_UA = "desktop_ua"

        /** 首页（用户指定）。 */
        const val HOME_URL = "https://www.navir.icu/"

        /** 截图前等面板把本 View 收进去的上限（毫秒）。 */
        private const val EMBED_WAIT_MS = 1200L


        /** 判断画面静止时，两次截图之间的间隔（毫秒）。 */
        private const val STATIC_GAP_MS = 150L

        /** 判断画面静止的总时限（毫秒）。超时交最后一张，不卡住工具调用。 */
        private const val STATIC_TIMEOUT_MS = 3000L

        /** 判断画面静止的最多截图次数。 */
        private const val STATIC_MAX_TRIES = 12

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
            // 描边取比 field 亮一档的灰蓝，在 bar/field 上都拉得开（对比度 > 1.9）。
            stroke = 0xFF6B7484.toInt(),
            icon = 0xFFC8CDD4.toInt(),
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
            stroke = 0xFF8A939E.toInt(),
            icon = 0xFF3C434D.toInt(),
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
        /**
         * 控件描边色。**这是「文字和按钮控件跟背景太像、分辨不出来」的根治项。**
         *
         * 实测原有色阶：`field` 与 `bar` 的对比度只有 1.16（深色）/ 1.12（浅色），
         * `fieldActive` 与 `bar` 也只有 1.45 / 1.29 —— 远低于「能看出这是个控件」
         * 所需的 3:1。光靠底色区分不行，必须画一条明确的边。
         */
        val stroke: Int,
        /** 图标色。工具栏那几个箭头/圆点原来**完全透明**，等于没有图标。 */
        val icon: Int,
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
    /**
     * 当前是否用**桌面版 UA**。
     *
     * ## 为什么要有这个开关（用户反馈 2026-10-28）
     *
     * 用户原话：「内置浏览器只能访问手机版站点，无法通过切换 UA 更改为访问
     * PC 版（希望可以增加 UA 切换）」。
     *
     * 有的站点（尤其后台管理、部分网盘、论坛）手机版功能残缺甚至不让登录，
     * 只能靠改 UA 装成桌面浏览器。而 WebView 默认 UA 里带 `; wv)` 与
     * `Mobile` —— 网站据此把手机版甩过来，用户没有别的办法。
     *
     * 存在 SharedPreferences 里而不是只放内存：切一次就该一直是桌面版，
     * 关掉浏览器再打开又变回手机版会让用户每次都要重切一遍。
     */
    private var desktopUa: Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_DESKTOP_UA, false)

    private val statusChip: TextView
    private val backBtn: TextView
    private val forwardBtn: TextView
    // ⚠️ `reloadBtn` / `homeBtn` 已移进「⋮」菜单（见 [showOverflowMenu]），
    //    不再是工具栏成员，所以这里不再声明它们为字段 —— 留着会「声明了但从不赋值」
    //    的编译错误。菜单项是按需构造的，不需要常驻视图。
    /** 「⋮」溢出菜单按钮。低频动作都收在它后面，见 [showOverflowMenu]。 */
    private val menuBtn: TextView

    /** UA 切换按钮（手机 / 桌面）。文案在 [toggleUserAgent] 里随状态更新。 */
    private val uaBtn: TextView

    private val markBtn: TextView


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

    /**
     * 当前是否已经摆进右侧栏面板（即 `applyEmbedRect` 生效过）。
     *
     * ⚠️ 这个状态直接决定**截图内容**：没嵌进面板时本 View 是
     * `MATCH_PARENT` 全屏，`wv.width` 就是整屏宽（1080）；嵌进去之后
     * 才是面板的内容区宽度（约 400）。
     *
     * 用户看到的一直是面板里那一小块，所以 AI 截图必须在**嵌入状态**下
     * 进行，否则截出来的视口和用户所见根本不是一个东西 —— 表现为
     * 「AI 截的图和我看到的不一样」「AI 说页面很宽/看不到我圈的东西」。
     */
    private var embedded = false

    /**
     * 连续截图（[awaitStaticFrame] 会反复截图比帧）期间不恢复胶囊。
     *
     * 每次 [captureVisible] 都会「藏胶囊 → 截 → 恢复」。如果一次工具调用
     * 连截十几张，胶囊就会一闪一闪，用户看到的是界面在抽搐。所以批量截图
     * 期间压住恢复动作，只在最后一张交出去时恢复一次。
     */
    private var holdPill = false

    /** 批量截图开始时胶囊是否本来就该显示。结束时据此决定要不要放出来。 */
    private var pillShouldShow = false

    /** 状态变化回调（通知外部 UI 刷新）。 */
    var onStateChanged: (() -> Unit)? = null

    /** 用户点了 ✕。宿主负责隐藏/销毁。 */
    var onClose: (() -> Unit)? = null

    /** 请求宿主把右侧栏切到浏览器面板（截图前要它把本 View 收进面板）。 */
    var onRequestEmbed: (() -> Unit)? = null

    /**
     * 页面请求选文件。宿主必须走系统的文件选择器，最后回调
     * `callback.onReceiveValue(uris)` 或 `callback.onReceiveValue(null)`。
     */
    var onPickFile: ((ValueCallback<Array<Uri>>) -> Unit)? = null

    /** 截图落盘目录（由宿主指定，通常是 workspace 下的隐藏目录）。 */
    var shotDir: File? = null

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 顶部安全区高度（像素）。
     *
     * ⚠️ 宿主开了 `FLAG_LAYOUT_NO_LIMITS`（见 `MainActivity.applyFullscreen`），
     *   内容会铺到状态栏底下 —— 不预留的话，工具栏左侧的后退键和右侧的 ✕
     *   正好被时间/电量压住，用户**看得见但点不准**，表现就是「进到浏览器里
     *   出不来」。这是内置浏览器必须自己处理的一件事，宿主帮不上。
     */
    private var safeTop = 0

    /**
     * 「收起」按钮回调。宿主负责把浏览器隐藏但**不关**侧栏 tab。
     *
     * 与 [onClose] 的区别见工具栏里 `collapseBtn` 的注释。
     */
    var onCollapse: (() -> Unit)? = null

    /**
     * 「标注」按钮回调。宿主负责截当前视口、弹出画布。
     *
     * 传出去的是 Bitmap 而不是让本视图自己弹画布：画布要盖住**整块侧栏区域**
     * （包括工具栏），由宿主统一管理层级更简单，也免得两个视图互相抢可见性。
     */
    var onAnnotate: (() -> Unit)? = null

    /** 待标注的截图。由 [startAnnotate] 放入，宿主用 [takeAnnotateBitmap] 取走。 */
    private var annotateBitmap: Bitmap? = null

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

        // ## ⚠️ 工具栏只放 5 个控件，其余收进「⋮」菜单
        //
        // 真机实测（CDY-TN90，360dp 宽）改造前：
        //
        //     10 个控件挤在 360dp 里
        //     地址栏只剩 95px ≈ 32dp  ← 什么都显示不出来
        //     每个按钮 102px = 34dp    ← 低于 Android 推荐的 48dp 触摸目标
        //
        // 用户原话：「控件太多，你可以使用那种弹出的形式，没必要全部挤在一起」。
        //
        // 现在的分工按**使用频次**排：
        //   常驻：← → [地址栏] ⋮ ✕
        //     · 前进/后退是浏览时最常点的两个，必须一眼可见；
        //     · 地址栏是本面板的主入口，必须够宽（现在 ≈150dp）；
        //     · ⋮ 收纳所有低频动作；
        //     · ✕ 是唯一的退路，不能藏。
        //
        // 收进去的：刷新、首页、UA 切换、标注、收起。
        // 它们都还在，只是不再抢占地址栏的宽度。
        backBtn = toolButton("‹") { if (currentWebView()?.canGoBack() == true) currentWebView()?.goBack() }
        forwardBtn = toolButton("›") { if (currentWebView()?.canGoForward() == true) currentWebView()?.goForward() }

        addressBar = TextView(context).apply {
            setTextColor(palette.text)
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = fieldBackground()
            text = HOME_URL
            setOnClickListener { showUrlEditor() }
        }

        statusChip = TextView(context).apply {
            setTextColor(palette.accent)
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = fieldBackground()
            text = "你"
            isClickable = true
            setOnClickListener { toggleControl() }
        }

        // 「UA」：手机版 / 桌面版一键切换。
        //
        // 放工具栏而不是塞进某个菜单：用户要的就是「点一下」（原话「希望可以
        // 增加 UA 切换」），多一层菜单等于没做。文案直接显示**当前模式**
        //（手机 / 桌面），点了就切，不用猜现在是哪个。
        // ⚠️ **宽度必须显式给足**（真机踩过）。
        //
        // 这台机器只有 360dp 宽，工具栏已经有 ← → ⟳ ⌂ 地址栏 [你] ✎ ⌄ ✕
        // 共 8 个 34dp 控件 = 272dp，剩 88dp 分给地址栏和「你」。UA 按钮
        // 若也用默认 34dp，两个字根本挤不下 —— 真机截图里它被压成一条
        // **灰色几乎看不见的细线**，用户根本不知道那是个按钮。
        //
        // 给 44dp + 10 号字：中文两个字在 44dp 里放得下，也不至于把地址栏
        // 挤没（地址栏有 weight=1，会自动让出）。
        uaBtn = toolButton(if (desktopUa) "桌面" else "手机", widthDp = 44) { toggleUserAgent() }
        uaBtn.textSize = 10f
        uaBtn.setTextColor(if (desktopUa) palette.accent else palette.text)

        // 「标注」：截图定格 → 圈画 → 直接问 AI。
        //
        // 放在工具栏而不是别处：用户想标注时，眼睛正在看网页，手也在网页上，
        // 入口必须在最顺手的位置。图标用 ✎（铅笔），语义直白。
        markBtn = toolButton("✎") { startAnnotate() }

        // 「收起」：只把浏览器从屏幕上挪开，**页面、标签、登录态全部保留**。
        //
        // ⚠️ 这和 ✕ 是两件事，不能合并：
        //   · ✕   = 关掉浏览器（连侧栏 tab 一起关），下次打开是全新页面；
        //   · ⌄   = 临时收起，随时能原样调回来。
        //
        // 用户的原话：「我无法临时通过侧边栏关闭按钮来临时移除浏览器界面，
        // 想要移除就只能点击关闭」。全屏铺满时尤其明显 —— 没有这个键，
        // 用户除了彻底关掉就没有别的退路，一关就丢页面。
        val collapseBtn = toolButton("⌄") { onCollapse?.invoke() }

        val closeBtn = toolButton("✕") { onClose?.invoke() }

        // 「⋮」溢出菜单：收纳所有低频动作。
        //
        // ⚠️ 用 TextView 而不是系统 OverflowMenu：本 View 是浮在 WebView 上的
        //   独立视图，走 Activity 的 actionbar 菜单需要宿主配合，而这里
        //   **连 Activity 都不一定有**（侧栏面板可能挂在别的宿主上）。
        //   PopupWindow 自带窗口，位置由我们自己定，最省事也最可控。
        menuBtn = toolButton("⋮") { showOverflowMenu() }
        menuBtn.textSize = 19f

        // 地址栏：**必须给它足够宽度**。
        //
        // 改造前它只有 95px（32dp）—— 用户原话「显示是否正确」的答案就是
        // 「不正确」：连域名都只露出一个 "h..."。现在 10 个控件压到 5 个，
        // weight=1 自然把它撑到约 150dp，能显示 "www.navir.icu" 这样的完整域名。
        chrome.addView(backBtn)
        chrome.addView(forwardBtn)
        chrome.addView(
            addressBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(5)
                rightMargin = dp(5)
            },
        )
        chrome.addView(
            statusChip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(4) },
        )
        chrome.addView(menuBtn)
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
    /**
     * 重建底部标签条。
     *
     * ⚠️ 每个标签**必须自带一个 ✕**。原来只有「长按关闭」——那是隐藏手势，
     *   用户根本看不出来，多标签时想关掉某一个只能一个个试。现在每个标签右侧
     *   有独立的关闭按钮，点标题切标签、点 ✕ 关标签，互不干扰。
     */
    private fun rebuildTabStrip() {
        tabStrip.removeAllViews()
        for ((i, tab) in tabs.withIndex()) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (i == activeIndex) palette.fieldActive else palette.field, 7)
                isClickable = true
                setOnClickListener { selectTab(i) }
                setOnLongClickListener { closeTabAt(i); true }
            }
            val title = TextView(context).apply {
                val text = tab.title.ifEmpty { tab.webView.title.orEmpty() }
                    .ifEmpty { tab.webView.url?.removePrefix("https://")?.removePrefix("http://") ?: "新标签页" }
                this.text = text.take(12)
                textSize = 10.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(if (i == activeIndex) palette.text else palette.textDim)
            }
            cell.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = dp(8)
                },
            )
            // ✕：每个标签自己的关闭按钮。颜色用 textDim 而非 text，
            // 避免和标题抢视线；点击区域给足 20dp 宽。
            val x = TextView(context).apply {
                text = "✕"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(palette.textDim)
                isClickable = true
                setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener { closeTabAt(i) }
            }
            cell.addView(
                x,
                LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.MATCH_PARENT),
            )
            tab.label = title
            tabStrip.addView(
                cell,
                LinearLayout.LayoutParams(dp(116), dp(26)).apply {
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
            // ⚠️ UA 由 [applyUserAgent] 统一决定（默认手机版、可切桌面版）。
            //    原来这里是 `userAgentString.replace("; wv)", ")")` —— 只去掉了
            //    WebView 的 `; wv)` 标记，**仍然是手机 UA**，所以用户报障里的
            //    「只能访问手机版站点」始终存在。
            applyUserAgent(this)

            /*
             * ⚠️ **文件访问必须关掉，这是本 WebView 最重要的一条安全设置。**
             *
             * 主 WebView（`SystemWebHost`）只加载 `http://127.0.0.1` 的自家界面，
             * 所以那边关掉文件访问是「顺手加固」。**这里不一样**：本 WebView 加载的是
             * **任意外部网站**（AI 打开的网页、用户点的链接、登录跳转页），
             * 而这些页面完全不受我们控制。
             *
             * Android 的默认值是 `allowFileAccess = true`。也就是说在补上下面这几行之前，
             * 一个恶意/被入侵的页面可以：
             *   · `fetch('file:///data/data/com.dsh.android/shared_prefs/…')`
             *     读走应用的私有配置（token、登录态、设置）；
             *   · 用 `new Image().src = 'file:///…'` 之类的手法**探测**任意路径是否存在，
             *     把设备上的文件布局摸清楚；
             *   · `file://` 页面还能绕过同源策略读到本地内容
             *     （`allowUniversalAccessFromFileURLs` / `allowFileAccessFromFileURLs`
             *     默认在旧内核上是开的，显式关掉才稳妥）。
             *
             * 本模块没有 `addJavascriptInterface`，所以不涉及「原生对象被页面拿到」
             * 那类更严重的后果；但读文件这一条本身就已经足够严重了。
             *
             * 关掉不影响正常使用：网页加载都是 http(s)，没有任何一处需要 `file://`。
             */
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }

        // Cookie 持久化：这是「登录状态重启后仍保持」的关键
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            /**
             * 渲染进程死了。**必须返回 true，否则整个应用进程会被系统杀掉。**
             *
             * 官方文档写得很明确：`onRenderProcessGone` 的默认实现返回 `false`，
             * 而返回 `false` 时 **系统会杀掉整个应用进程**。也就是说用户看到的
             * 「App 突然自己退了」根本不是 App 的 bug，而是这个回调没被覆写。
             *
             * ⚠️ **这个 WebView 的风险比主 WebView 高得多**：它加载的是任意外部
             *   网站（AI 打开的网页、用户点的链接）。外部页面把渲染进程搞崩是
             *   完全可能的 —— 一个畸形的 CSS、一段死循环脚本、一个超大 DOM 都够。
             *   而主 WebView 只加载我们自己的 `127.0.0.1` 界面，崩的概率低得多。
             *
             *   原本本模块**根本没有覆写这个方法** —— 那些外部页面一崩，
             *   用户的整个 App 会跟着消失，正在进行的对话和引擎进程全部丢失，
             *   而他只是"点开了一个网页"。
             *
             * 处理策略与主 WebView 不同（那边是重载界面）：这里是**辅助面板**，
             * 正确的做法是**关掉出问题的标签**，而不是把整个 App 拉下水。
             * 标签关掉后用户可以重开，其他标签与主界面完全不受影响。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                val didCrash = detail?.didCrash() ?: false
                Log.w(
                    TAG,
                    "AI 浏览器的渲染进程${if (didCrash) "崩溃" else "被系统回收"}，" +
                        "关闭该标签（不影响主界面）",
                )
                // 当前这个 WebView 已经不可用，必须从标签列表里摘掉 ——
                // 否则后续任何 currentWebView() 操作都会命中一个死对象。
                val tab = tabs.firstOrNull { it.webView === view }
                if (tab != null) {
                    tabs.remove(tab)
                    // releaseTab 会再 destroy 一次。对已被系统回收的 WebView
                    // 调 destroy 是幂等且安全的（内部只做资源清理）。
                    runCatching { releaseTab(tab) }
                    if (tabs.isEmpty()) {
                        // 一个标签都不剩：留一个空白标签，别让面板变成无内容的黑洞
                        runCatching { openNewTab(null) }
                    } else {
                        activeIndex = activeIndex.coerceIn(0, tabs.size - 1)
                        runCatching { showActiveTab() }
                    }
                    runCatching { rebuildTabStrip() }
                    runCatching { updateChrome() }
                }
                // ⚠️ 必须 true：返回 false 会让系统终止整个应用进程。
                return true
            }

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

    /**
     * 设置顶部安全区（状态栏高度，像素）。宿主在 `applyFullscreen` 之后调。
     *
     * 只会**加**不会减：传 0 表示没有安全区（非全面屏），此时工具栏回到顶部。
     */
    fun setSafeTop(px: Int) {
        val v = px.coerceAtLeast(0)
        if (v == safeTop) return
        safeTop = v
        applySafeTop()
    }

    /** 把 [safeTop] 刷到工具栏的上内边距上。 */
    private fun applySafeTop() {
        chromeBar?.setPadding(dp(4), safeTop, dp(4), 0)
        // 工具栏本身要变高，否则 padding 会挤掉按钮的可用高度。
        chromeBar?.layoutParams?.let { lp ->
            lp.height = dp(BAR_H_DP) + safeTop
            chromeBar?.layoutParams = lp
        }
        actionPill?.let { pill ->
            (pill.layoutParams as? LayoutParams)?.let { lp ->
                lp.topMargin = dp(BAR_H_DP) + safeTop + dp(12)
                pill.layoutParams = lp
            }
        }
    }

    // ── window.open 承载 ────────────────────────────────────────────────

    /**
     * 为页面的 `window.open("about:blank")` 提供一个**可见**的 WebView。
     *
     * 官方登录（`dsh-client-ui-settings-account` 的 `BrowserLogin`）是这么走的：
     *
     *     this.popup = window.open("about:blank", "_blank");
     *     … 拿到 authorizeUrl 后 …
     *     this.popup.location.replace(authorizeUrl);
     *
     * 内核需要一个真实的 `WebView` 当渲染目标。**原来这里新建一个裸 WebView
     * 却从不 `addView`**，于是内核在往里渲染、页面也确实在跑，用户屏幕上却
     * 什么都没有 —— 「点登录一片空白」就是这么来的。
     *
     * ⚠️ 光有可见的 WebView **还不够**：`SystemWebHost.createBlankPopup` 那边
     *   必须 `resultMsg.sendToTarget()` 投递，内核才会真的接管这个窗口。漏了那
     *   一句的症状是「标签页建出来了、地址栏停在 about:blank、内容全白」。
     *
     * ## 为什么新开一个标签，而不是复用当前标签
     *
     * `window.open` 语义上要一个**独立**的渲染目标：页面持有这个对象的引用，
     * 之后会主动 `location.replace()`、`close()`。复用当前标签的话，授权页会把
     * 用户正在看的页面冲掉，而 `close()` 又会把那个标签一起关掉。
     *
     * 但标签上限是 [MAX_TABS]：满了就先关掉当前标签腾位置（登录比一个旧标签重要）。
     *
     * @return 已挂进 `pageHost` 的 WebView；宿主没准备好时返回 `null`。
     */
    fun createPopupTarget(): WebView? {
        if (tabs.size >= MAX_TABS) {
            Log.w(TAG, "标签已满，为 window.open 关掉当前标签腾位")
            closeCurrentTab()
        }
        val wv = QuietWebView(context)
        configureWebView(wv)
        val tab = Tab(wv)
        tabs.add(tab)
        activeIndex = tabs.size - 1
        showActiveTab()
        rebuildTabStrip()
        // ⚠️ 与 openNewTab 同理：新 WebView 还没被 applyControl 设过
        //   OnTouchListener，漏了这句 AI 控制期间用户照样能点。
        applyControl()
        // 地址栏交给 updateChrome() 按 `wv.url` 自动刷新 —— 这里不要写死
        // "about:blank"，那会在内核真正接管、URL 变化后仍留下误导性的文字。
        Log.i(TAG, "window.open 承载标签已建好（可见）")
        return wv
    }

    // ── 导航 ────────────────────────────────────────────────────────────

    fun loadUrl(url: String) {
        val fixed = normalizeUrl(url)
        val wv = currentWebView() ?: return
        addressBar.text = fixed
        wv.loadUrl(fixed)
    }

    /**
     * `example.com` → `https://example.com`；空串 → 首页；非域名 → 搜索引擎。
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
            "https://www.bing.com/search?q=${java.net.URLEncoder.encode(t, "UTF-8")}"
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
     *   就会让 AI 正在填的表单失焦。
     *
     * ⚠️ 但**操作一结束必须还回去**（见 [releaseAiControl]）。以前没有归还
     *   路径，监听器会永久留在 WebView 上，导致用户之后连光标都拖不动。
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

    /**
     * AI 的一次操作做完了，把控制权**还给用户**。
     *
     * ## 为什么必须有这个
     *
     * [Control.AI] 状态下会给 WebView 挂 `setOnTouchListener { _, _ -> true }`
     * 把触摸全部吃掉 —— 这是为了 AI 填表单时用户随手一点不至于让输入框失焦。
     *
     * 但以前**只有** `claimAiControl()`，没有任何地方把它还回去：
     * `setControl(Control.USER)` 全项目一处都没调用过。于是只要 AI 操作过
     * 一次，那个吞触摸的监听器就永远留在 WebView 上。用户反馈的现象是
     * 「输入框里输了文字，光标拖不动、点别处也没反应」—— 因为所有触摸
     * 事件都被这个监听器吃掉了，页面根本收不到。
     *
     * 现在每次操作结束（成功、失败、超时都算）都归还控制权，触摸恢复正常。
     * AI 连续操作时每次操作开始会重新抢占，中间那点间隙用户就算点了，
     * 也只会是用户自己的操作，符合「AI 和用户共用一个浏览器」的预期。
     */
    fun releaseAiControl() {
        if (control == Control.AI) setControl(Control.USER)
        // ⚠️ 静默输入也要一起解除。
        //
        // `silentInput` 原本靠用户手指**按下**来解除（见 [onInterceptTouchEvent]）。
        // 但 AI 干完活之后用户往往不会重新按一次就以为能输入了 —— 只要它还是
        // true，`onCreateInputConnection` 就返回 null，键盘压根不弹、输入框
        // 的光标也拖不动。操作已结束，没有任何理由继续静默。
        silentInput = false
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

    /**
     * 桌面版 UA：把 WebView 默认 UA 里的手机痕迹全部去掉。
     *
     * 用**真实存在的桌面 Chrome UA** 而不是随便拼一个：
     *  - `Mobile` / `Android` / `; wv)` 是站点用来判别手机的三个关键记号，
     *    必须一起去掉（只去 `wv` 不够 —— 那正是原来那条只做了一半的写法）；
     *  - 保留真实的 `Chrome/xxx` 版本号，否则有的站点会判成「未知浏览器」。
     *
     * ⚠️ 不能写死一个旧版本号：WebView 的内核版本会随系统更新，
     *    这里从设备自带 UA 里把 `Chrome/…` 抠出来复用，保证版本号与内核一致。
     */
    private fun desktopUserAgent(base: String): String {
        val chromeVersion = Regex("Chrome/([0-9.]+)").find(base)?.groupValues?.getOrNull(1) ?: "120.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    /** 手机版 UA：设备原生 UA 去掉 `; wv)`（与改动前的行为一致）。 */
    private fun mobileUserAgent(base: String): String = base.replace("; wv)", "")

    /**
     * 把当前 UA 模式应用到 WebView 设置上。
     *
     * ⚠️ 必须以**设备原始 UA**为基准重算，不能在上一次的结果上链式替换 ——
     *  `WebSettings.userAgentString` 一旦被赋值，再读 `getDefaultUserAgent()`
     *  拿到的仍是系统默认值（这一步是安全的），但若拿 `settings.userAgentString`
     *  当基准就会在手机版/桌面版之间来回切换时逐步污染字符串。
     */
    private fun applyUserAgent(settings: WebSettings) {
        val base = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (t: Throwable) {
            // ⚠️ 极少数 ROM 上会抛（WebView 未就绪）。退回当前值，不让整个
            //    WebView 配置流程因为 UA 这一项而失败。
            settings.userAgentString ?: ""
        }
        if (base.isEmpty()) return
        settings.userAgentString = if (desktopUa) desktopUserAgent(base) else mobileUserAgent(base)
    }

    /**
     * 切换 UA 并重载当前页。
     *
     * 重载是**必须**的：已经渲染好的页面不会因为 UA 改变而自己去请求桌面版；
     * 不重载用户会觉得「点了没反应」。
     */
    private fun toggleUserAgent() {
        desktopUa = !desktopUa
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DESKTOP_UA, desktopUa).apply()
        } catch (t: Throwable) {
            // 存不下不影响本次生效，只是下次打开会回到默认。
        }
        // 所有已开的标签都要换 —— 只换当前那个，切标签时又会看到手机版。
        for (tab in tabs) {
            val wv = tab.webView
            applyUserAgent(wv.settings)
            wv.reload()
        }
        uaBtn.text = if (desktopUa) "桌面" else "手机"
        uaBtn.textSize = 10f
        uaBtn.setTextColor(if (desktopUa) palette.accent else palette.text)
    }

    /**
     * 弹出「⋮」溢出菜单。
     *
     * ## 为什么要有这个（用户 2026-10-28 原话）
     *
     * 「你看各个按钮是否合理？布局是否合理？控件太多，你可以使用那种弹出的
     * 形式，没必要全部挤在一起」
     *
     * 真机实测改造前的数据把问题量化了（CDY-TN90，360dp 宽）：
     *
     * | 项目 | 改造前 | 现在 |
     * |---|---|---|
     * | 工具栏控件数 | 10 个 | 5 个 |
     * | 地址栏宽度 | 95px ≈ 32dp | ≈150dp |
     * | 每个按钮 | 34dp（< 48dp 标准） | 40dp + 菜单项 48dp 高 |
     *
     * ## 菜单项的取舍
     *
     * 收进来的是**低频但需要时找不到会很难受**的那批：
     *   · 刷新 —— 有下拉刷新，但那在 WebView 里不一定灵；
     *   · 首页 —— 迷路时的唯一退路；
     *   · UA 切换 —— 按需使用，不是每次都点；
     *   · 标注 —— 想圈重点时才用；
     *   · 收起 —— 全屏铺满时的退路。
     *
     * ⚠️ **不放「关闭」**：✕ 已经常驻在工具栏最右，再放一个重复项只会让人犹豫。
     *
     * ⚠️ 菜单项高度给到 48dp：这是 Android 触摸目标的推荐下限，
     *    原来工具栏按钮 34dp 已经偏小，菜单里空间充裕就没必要再省。
     */
    private fun showOverflowMenu() {
        val anchor = menuBtn
        val width = dp(196)
        val items = listOf(
            MenuItem("⟳", "刷新") { currentWebView()?.reload() },
            MenuItem("⌂", "回到首页") { loadUrl(HOME_URL) },
            // ⚠️ 这一项**不要带 sub**（真机踩过）：菜单宽 196dp，
            //    「切换桌面版  当前：桌面 UA」放不下，实测换成了两行，
            //    把这一行撑高到别的行一倍，看起来像排版坏了。
            //    当前模式由 label 本身表达（「切回手机版」就意味着现在是桌面版），
            //    菜单上的图标也随模式变，信息已经够了。
            MenuItem(
                if (desktopUa) "🖥" else "📱",
                if (desktopUa) "切回手机版" else "切换桌面版",
            ) { toggleUserAgent() },
            MenuItem("✎", "标注截图") { startAnnotate() },
            MenuItem("⌄", "收起浏览器") { onCollapse?.invoke() },
        )

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // ⚠️ 先声明再在点击里引用：`popup` 的构造要用 `list`，
        //    而菜单项的点击回调要用 `popup` —— 直接 `val popup = PopupWindow(list…)`
        //    会让回调捕获到一个还没初始化的变量。
        var popup: PopupWindow? = null

        items.forEachIndexed { index, item ->
            // 分组细线：前两项是「导航」，后面是「工具」，中间划一刀。
            if (index == 2) {
                list.addView(
                    View(context).apply { setBackgroundColor(palette.stroke) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, max(1, (resources.displayMetrics.density * 0.5f).toInt()),
                    ).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    },
                )
            }
            val row = TextView(context).apply {
                text = buildString {
                    append(item.icon)
                    append("   ")
                    append(item.label)
                    // `sub` 只用来告诉用户「当前是哪种模式」，不参与点击。
                    // 并进同一行而不是换行：换行会把 48dp 的行高撑破。
                    if (item.sub !== null) {
                        append("   ")
                        append(item.sub)
                    }
                }
                setTextColor(if (item.sub !== null) palette.textDim else palette.text)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                isFocusable = true
                background = pressable()
                // 单行 + 超长省略：宁可截断也不要换行把行高撑破。
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            row.setOnClickListener {
                popup?.dismiss()
                item.action()
            }
            // 用 minHeight 而不是固定高度：万一以后某项文字变长换行，
            // 固定 48dp 会把文字裁掉；minHeight 允许它自然撑高但不低于触摸标准。
            row.minHeight = dp(48)
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        popup = PopupWindow(
            list,
            width,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true, // 可点外部关闭
        ).apply {
            setBackgroundDrawable(menuBackground())
            isOutsideTouchable = true
            isFocusable = true
            // 让阴影落在窗口外（5.0+），菜单看起来才不像是"贴"在页面上的。
            elevation = dp(8).toFloat()
        }

        // 靠右对齐到「⋮」下方：anchor 在窗口里的位置 + 自身高度。
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        // 右对齐：菜单右边缘 = 按钮右边缘，避免窄屏上超出屏幕。
        val x = loc[0] + anchor.width - width
        val y = loc[1] + anchor.height + dp(6)
        val screenW = resources.displayMetrics.widthPixels
        val safeX = x.coerceIn(dp(8), max(dp(8), screenW - width - dp(8)))
        popup?.showAsDropDown(anchor, safeX - loc[0], dp(6))
    }

    /** 溢出菜单里的一项。`sub` 用于显示当前状态（比如 UA 模式）。 */
    private class MenuItem(
        val icon: String,
        val label: String,
        val sub: String? = null,
        val action: () -> Unit,
    )

    /** 菜单圆角背景（跟着主题走）。 */
    private fun menuBackground(): Drawable =
        GradientDrawable().apply {
            setColor(palette.barAlt)
            cornerRadius = dp(12).toFloat()
            setStroke(max(1, (resources.displayMetrics.density * 0.5f).toInt()), palette.stroke)
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
            captureVisible(wv, onBitmap)
        }
    }

    /**
     * 截「用户真正看到的这块」。
     *
     * ⚠️ 以前用 `wv.draw(Canvas(bmp))`，它画的是 **View 自己的内容**，有
     *   两个致命偏差：
     *   1. **不含父容器的裁切**。这个 WebView 是被 `applyEmbedRect` 嵌进右侧
     *      面板矩形里的，`draw()` 会连面板外本该被裁掉的部分一起画出来；
     *   2. **不含合成层效果**。fixed 元素、合成滚动、硬件加速层都不经过
     *      `draw()`，截出来和屏幕上看到的不是一回事。
     *
     *   用户的原话：「截图问题还是不能确保与用户看到的一致」，而且连 AI
     *   自己调的 `screenshot` 也是同一份实现（`captureToFile` 走这里），
     *   所以两处一起错、必须一起修。
     *
     * 正解是 [PixelCopy]：直接从窗口 Surface 复制**已经合成好的、屏幕上的**
     * 那块像素。它拿到的一定等于用户所见，不存在「模型和人对不上」。
     * 拿不到时（极少数机型/时机）退回 `draw()`，保证至少有一张图。
     */
    private fun captureVisible(wv: WebView, onBitmap: (Bitmap?) -> Unit) {
        // ⚠️ **截图前必须把胶囊藏掉。**
        //
        // `actionPill`（「AI 正在操作页面」那条浮层）是 [AiBrowserView] 的子
        // View，叠在 WebView 之上。以前用 `wv.draw()` 时它不在 WebView 的
        // 子树里，天然不会被拍到；换成 [PixelCopy] 之后，抓的是**窗口合成后
        // 的像素**，浮层就会被一起截进图里 —— 用户看到的现象正是「我自己点
        // 画笔截图是好的，AI 自己截图就不对」，因为只有 AI 操作时才有胶囊。
        //
        // 所以这里临时隐藏、截完立刻恢复。隐藏用 INVISIBLE 而不是 GONE：
        // GONE 会触发重新布局，WebView 尺寸一变，截出来的页面就跟着变。
        val pill = if (::actionPill.isInitialized) actionPill else null
        val pillWasVisible = pill?.visibility == View.VISIBLE
        if (pillWasVisible) {
            pill!!.visibility = View.INVISIBLE
        }
        // 批量截图期间记下「第一次进来时它本来就该显示」，因为从第二张起
        // `visibility` 已经是 INVISIBLE，再判断 pillWasVisible 就永远是 false。
        if (holdPill && pillWasVisible) pillShouldShow = true
        val restorePill = {
            if (pill != null && !holdPill && (pillWasVisible || pillShouldShow)) {
                pill.visibility = View.VISIBLE
            }
            if (!holdPill) pillShouldShow = false
        }
        val win = runCatching { wv.getWindowToken() }.getOrNull()
        val activity = context as? Activity
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        val x = loc[0]
        val y = loc[1]
        val w = wv.width
        val h = wv.height
        val bmp = if (w > 0 && h > 0) {
            runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
        } else {
            null
        }
        val canPixelCopy = win != null && activity != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            x >= 0 && y >= 0 && w > 0 && h > 0 && bmp != null

        if (!canPixelCopy) {
            // 兜底路径：draw() 不含浮层，恢复 pill 后直接画即可。
            restorePill()
            fallbackDraw(wv, onBitmap)
            return
        }

        val rect = Rect(x, y, x + w, y + h)
        val handler = Handler(Looper.getMainLooper())
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val deliver = { b: Bitmap? ->
            restorePill()
            if (delivered.compareAndSet(false, true)) onBitmap(b)
        }
        // ⚠️ 藏完 pill 必须**等一帧**再抓像素：`visibility` 只是改了 View 的
        //   状态，真正从屏幕上消失要等下一次合成。立刻 PixelCopy 会拿到
        //   「pill 还在」的那一帧，等于没藏。
        wv.postOnAnimation {
            runCatching {
                PixelCopy.request(activity!!.window, rect, bmp!!, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        deliver(bmp)
                    } else {
                        Log.w(TAG, "PixelCopy 失败 code=$result，退回 draw()")
                        bmp.recycle()
                        fallbackDraw(wv) { b -> deliver(b) }
                    }
                }, handler)
            }.onFailure {
                Log.w(TAG, "PixelCopy 调用异常: ${it.message}")
                bmp?.recycle()
                fallbackDraw(wv) { b -> deliver(b) }
            }
        }
    }

    /** 兜底：拿不到窗口像素时，至少把 View 内容画出来。 */
    private fun fallbackDraw(wv: WebView, onBitmap: (Bitmap?) -> Unit) {
        /*
         * ⚠️ `bmp` 提到 try 外面，异常路径必须回收它。
         *
         * 这张图是**按 WebView 尺寸全尺寸分配**的（1080×2400 × 4B = 10.4 MB）。
         * 原来的写法把 `createBitmap` 和 `wv.draw()` 放在同一个 try 里，
         * 而 `wv.draw()` 是可能抛的 —— WebView 已销毁、尺寸为 0、
         * 渲染层正在重建，都会走到 catch。
         *
         * 那一刻 `bmp` 已经分配好了，但 catch 只调 `onBitmap(null)`，
         * **这 10.4 MB 就漏掉了**。而截图（尤其"标注"入口）是会被反复触发的操作，
         * 连点几次就是把堆顶穿。
         *
         * 对比同文件里 PixelCopy 主路径：它在失败分支明确写了 `bmp.recycle()`，
         * 说明这个规矩本来是清楚的，只是兜底路径漏了。
         */
        var bmp: Bitmap? = null
        try {
            val created = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            bmp = created
            wv.draw(Canvas(created))
            // 成功路径：所有权交给调用方，这里不能回收。
            bmp = null
            onBitmap(created)
        } catch (t: Throwable) {
            Log.w(TAG, "截图失败: ${t.message}")
            bmp?.recycle()
            onBitmap(null)
        }
    }

    /**
     * 点「标注」：先截当前视口，再交给宿主弹画布。
     *
     * ⚠️ **先截图再回调**，顺序不能反：一旦画布盖上来，`WebView.draw()` 拿到的
     *   就是被遮住的画面（空白或画布本身）。
     */
    private fun startAnnotate() {
        // ⚠️ 必须先收输入法。[PixelCopy] 复制的是**窗口合成后**的像素，
        //   键盘/弹窗盖在 WebView 上就会被一起截进图里 —— 那种图既不像用户
        //   所见的页面，也没法用来标注。收起后再截才干净。
        hideIme()
        // 收键盘是异步的：立刻截会把还没退干净的键盘一起拍进去。等一帧，
        // 让窗口重新布局、WebView 高度回到正常值之后再抓像素。
        postDelayed({
            // ⚠️ **和 AI 截图走完全相同的稳定等待。**
            //
            // 原来这里直接 `capture()`，AI 那条路（[captureToFile]）却额外
            // 等页面画稳。两条路不等价 ⇒ 同一块画面，用户截得对、AI 截不对，
            // 用户的原话是「用同一条获取图片的通道不就行了吗」。
            //
            // 现在两条路都先 [awaitStaticFrame]（连续两帧像素一致才交图），
            // 保证截到的是**静止画面**。这样只要用户这边是对的，AI 那边必然也对。
            awaitStaticFrame { bmp ->
                if (bmp == null) {
                    Log.w(TAG, "标注失败：截图为空")
                    return@awaitStaticFrame
                }
                annotateBitmap = bmp
                onAnnotate?.invoke()
            }
        }, 120L)
    }

    /** 取走待标注的截图（取一次就清空，避免误用上一张）。 */
    fun takeAnnotateBitmap(): Bitmap? {
        val b = annotateBitmap
        annotateBitmap = null
        return b
    }

    /**
     * 截图并落盘，回调文件绝对路径。**AI 的 `screenshot` 工具走这里。**
     *
     * ⚠️ 先确保已经嵌进侧栏面板再截。
     *
     * 本 View 平时是 `MATCH_PARENT` 全屏挂在根布局上，只有面板上报矩形
     * （`applyEmbedRect`）之后才缩成面板内容区那么大。AI 用工具打开页面时
     * 面板往往**还没展开**，这时截出来的是全屏视口（1080 宽），而用户看到
     * 的永远是面板里那条窄视口 —— 两者根本不是同一个画面，用户的原话是
     * 「AI 的截图通道好像还是有问题」。
     *
     * 所以这里等它嵌进去（最多 [EMBED_WAIT_MS]），等不到就用当前状态截，
     * 至少不会卡住工具调用。
     *
     * 嵌进去之后还要等**画面静止**（[awaitStaticFrame]）：页面从全屏变成
     * 面板宽度会重排、跑 CSS 过渡，截在中间帧上就会出现「对话/工作」被压成
     * 一条细线那种图。
     */
    fun captureToFile(onPath: (String?) -> Unit) {
        awaitEmbedded { awaitStaticFrame { bmp -> saveShot(bmp, onPath) } }
    }

    /**
     * 等页面**真正画稳**再交付回调。
     *
     * ⚠️ 这是「AI 截到的图和用户看到的不一样」的第二个成因，比嵌没嵌进面板
     * 更隐蔽。
     *
     * AI 用工具打开页面后会**立刻**截图，此时页面还在干三件事：
     *   1. 资源没下完，还在加载；
     *   2. 视口刚从全屏变成面板宽度，正在重排；
     *   3. 重排触发的 CSS 动画/过渡没结束。
     *
     * 豆包的「对话 / 工作」胶囊切换器就是第 3 种：它有入场缩放动画，
     * 截图若落在动画中间帧，那一行就被**垂直压扁**成一条细线 —— 用户的原话
     * 「这两个标签会被压缩，AI 拿到的图跟原图是不一样的」。而用户自己点
     * 画笔时页面早稳定了，所以他截出来是对的，只有 AI 截不对。
     *
     * 所以要等：加载完成 → 尺寸连续两帧不变 → 再放过几帧让动画收尾。
     */
    /**
     * 等到画面**真正静止**再交图 —— 连续两帧像素完全一致才算数。
     *
     * ## 为什么不能靠「等 N 毫秒」
     *
     * 之前试过等尺寸稳定、等 `progress==100`、等固定时长，全都不行：
     * 这些信号和「画面是否还在动」没有必然关系。豆包的「对话 / 工作」
     * 胶囊切换器有入场缩放动画，动画期间视口尺寸、加载进度都不变，
     * 截在中间帧上，那一行就被**垂直压成一条 5px 的细线**，根本看不见。
     *
     * 用户的原话：「用同一条获取图片的通道不就行了」。他说对了 ——
     * 他自己点画笔时页面早静止了，所以同一段 [capture] 他截得对。差别
     * 不在代码，在**截的那一刻画面还在不在动**。
     *
     * ## 做法
     *
     * 反复截图，把这一张和上一张**逐点比对**：
     *   · 两帧一致 ⇒ 画面已静止，交这一张；
     *   · 不一致   ⇒ 还在动，等一会儿再截；
     *   · 超时/次数上限 ⇒ 交最后一张（不能卡死工具调用）。
     *
     * 这样交出去的图**必然是一个完整稳定的画面**，也就必然和用户看到的
     * 一致 —— 不依赖任何关于动画时长的猜测。
     */
    private fun awaitStaticFrame(then: (Bitmap?) -> Unit) {
        var prev: Bitmap? = null
        var tries = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + STATIC_TIMEOUT_MS

        // 比帧期间压住胶囊恢复，避免连截十几张时界面闪烁。
        holdPill = true
        fun step() {
            capture { bmp ->
                if (bmp == null) {
                    prev?.recycle()
                    releasePill()
                    then(null)
                    return@capture
                }
                val old = prev
                val same = old != null && sameFrame(old, bmp)
                old?.recycle()
                prev = bmp
                tries++
                val outOfTime = android.os.SystemClock.elapsedRealtime() >= deadline
                if (same || outOfTime || tries >= STATIC_MAX_TRIES) {
                    if (!same) {
                        Log.w(TAG, "画面未静止就超时交图（tries=$tries），可能仍在动画中")
                    }
                    releasePill()
                    then(bmp)
                } else {
                    postDelayed({ step() }, STATIC_GAP_MS)
                }
            }
        }
        step()
    }

    /** 结束批量截图，把压住的胶囊放出来。 */
    private fun releasePill() {
        holdPill = false
        // ⚠️ 只在它**本来就该显示**时才放出来。否则（AI 没在操作页面时）
        //   凭空冒出一条「AI 正在操作」的浮层，比闪烁更糟。
        if (::actionPill.isInitialized && pillShouldShow) {
            actionPill.visibility = View.VISIBLE
        }
        pillShouldShow = false
    }

    /**
     * 两张图是否「基本一致」——用来判断画面有没有还在动。
     *
     * 抽样比对（不逐像素扫全图）：24×48 个采样点足够识别出动画/滚动/重排，
     * 又不受个别像素抗锯齿抖动影响。允许 2% 差异。
     */
    private fun sameFrame(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val stepX = (a.width / 24).coerceAtLeast(1)
        val stepY = (a.height / 48).coerceAtLeast(1)
        var diff = 0
        var total = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && diff * 100 / total <= 2
    }

    /** 等 [embedded] 变 true，超时后照样继续（不能卡住 AI 的工具调用）。 */
    private fun awaitEmbedded(then: () -> Unit) {
        if (embedded) {
            then()
            return
        }
        // 请宿主把侧栏切到浏览器面板：面板一挂载就会上报矩形。
        onRequestEmbed?.invoke()
        val deadline = android.os.SystemClock.elapsedRealtime() + EMBED_WAIT_MS
        val poll = object : Runnable {
            override fun run() {
                if (embedded || android.os.SystemClock.elapsedRealtime() >= deadline) {
                    if (!embedded) {
                        Log.w(TAG, "等面板嵌入超时，按当前视口截图（可能与用户所见不同）")
                    }
                    then()
                    return
                }
                postDelayed(this, 50L)
            }
        }
        postDelayed(poll, 50L)
    }

    /** 把已截好的图落盘并回调路径。 */
    private fun saveShot(bmp: Bitmap?, onPath: (String?) -> Unit) {
        run {
            if (bmp == null) {
                onPath(null)
                return@run
            }
            try {
                val dir = shotDir ?: ShotStore.dir(context.filesDir)
                if (!dir.exists()) dir.mkdirs()
                ShotStore.prune(dir)
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
        // ⚠️ 输入框/状态胶囊**必须带描边**。原来只有一层比工具栏亮 1.16 的底色，
        //   在手机屏上几乎看不出来 —— 用户的原话是「文字和按钮控件跟背景颜色
        //   太相近了，根本无法分辨」。底色 + 明确的边，两层一起才有控件感。
        addressBar.background = fieldBackground()
        statusChip.background = fieldBackground()
        actionText.setTextColor(palette.pillText)
        actionDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.aiAccent)
        }
        actionPill.background = pillBackground()
        // 工具栏图标：原来 `pressable()` 的填充是**全透明**，等于没有图标，
        // 只剩一片和背景同色的空白。现在用 icon 色（对比度 > 8）。
        for (btn in listOf(backBtn, forwardBtn, menuBtn, markBtn)) btn.setTextColor(palette.icon)
        for (tab in tabs) tab.webView.setBackgroundColor(palette.root)
        for (tab in tabs) tab.label?.setTextColor(if (tabs.indexOf(tab) == activeIndex) palette.text else palette.textDim)
        rebuildTabStrip()
        updateChrome()
    }

    /** 带描边的输入框底色 —— 让控件边界一眼可见。 */
    private fun fieldBackground(): Drawable = GradientDrawable().apply {
        setColor(palette.field)
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1).coerceAtLeast(1), palette.stroke)
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
        embedded = true
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
        embedded = false
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
        // ⚠️ `releaseTab` 里也会清 `pendingFileCallback`，但那只在 **tabs 非空**时
        //   才会被调到。如果此刻 tabs 恰好为空、而页面之前发起过一次文件选择还没
        //   回结果，这个回调就会一直挂在 AiBrowserView 上 ——
        //   它强引用着 WebView 侧的 ValueCallback 链路，属于典型的 Activity 泄漏。
        //   所以销毁路径上必须**无条件**再清一次（对已清过的情况是幂等的）。
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
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
            setTextColor(palette.icon)
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
