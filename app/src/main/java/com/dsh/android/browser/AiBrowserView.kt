package com.dsh.android.browser

import com.dsh.android.R
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

        /** 标签条高度（dp）。只在标签数 > 1 时占位。 */
        private const val TABBAR_H_DP = 34

        /** 标签数上限。每个标签一个 WebView，各自吃几十 MB，硬顶住 6 个。 */
        private const val MAX_TABS = 6
    }

    /** 谁在操作。 */
    enum class Control { AI, USER }

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

    // ── 子视图 ──────────────────────────────────────────────────────────

    private val pageHost = FrameLayout(context)
    private val tabBar: LinearLayout
    private val tabStrip: LinearLayout
    private val addressBar: TextView
    private val progress: ProgressBar
    private val statusChip: TextView
    private val backBtn: TextView
    private val forwardBtn: TextView

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
        setBackgroundColor(0xFF0F1115.toInt())

        val barH = dp(BAR_H_DP)
        val tabH = dp(TABBAR_H_DP)

        // ── 工具栏 ──────────────────────────────────────────────────────
        val chrome = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1F2A.toInt())
            setPadding(dp(4), 0, dp(4), 0)
        }

        backBtn = toolButton("←") { if (currentWebView()?.canGoBack() == true) currentWebView()?.goBack() }
        forwardBtn = toolButton("→") { if (currentWebView()?.canGoForward() == true) currentWebView()?.goForward() }
        val reloadBtn = toolButton("⟳") { currentWebView()?.reload() }
        val homeBtn = toolButton("⌂") { loadUrl(HOME_URL) }

        addressBar = TextView(context).apply {
            setTextColor(0xFFDCE6F5.toInt())
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = rounded(0xFF232936.toInt(), 8)
            text = HOME_URL
            setOnClickListener { showUrlEditor() }
        }

        statusChip = TextView(context).apply {
            setTextColor(0xFF7EC8A0.toInt())
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(0xFF232936.toInt(), 8)
            text = context.getString(R.string.browser_you)
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
            setBackgroundColor(0xFF141922.toInt())
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
        addView(
            tabBar,
            LayoutParams(LayoutParams.MATCH_PARENT, tabH).apply { gravity = Gravity.BOTTOM },
        )

        openNewTab(HOME_URL)
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
        val wv = WebView(context)
        configureWebView(wv)
        val tab = Tab(wv)
        tabs.add(tab)
        activeIndex = tabs.size - 1
        showActiveTab()
        wv.loadUrl(normalizeUrl(url ?: HOME_URL))
        rebuildTabStrip()
        updateChrome()
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
                    .ifEmpty { tab.webView.url?.removePrefix("https://")?.removePrefix("http://") ?: context.getString(R.string.browser_new_tab) }
                this.text = text.take(14)
                textSize = 10.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), 0, dp(9), 0)
                setTextColor(if (i == activeIndex) 0xFFDCE6F5.toInt() else 0xFF7C8798.toInt())
                background = rounded(if (i == activeIndex) 0xFF2B3444.toInt() else 0x00000000, 7)
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
        wv.setBackgroundColor(0xFF0F1115.toInt())
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
            toast(context.getString(R.string.browser_download_starting, name))
            Log.i(TAG, "下载已入队: $url → Download/$name")
        } catch (t: Throwable) {
            Log.w(TAG, "下载失败: ${t.message}")
            toast(context.getString(R.string.browser_download_failed, t.message))
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
            setTextColor(0xFFDCE6F5.toInt())
            setHintTextColor(0xFF7C8798.toInt())
            setBackgroundColor(0xFF232936.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.browser_open_url))
            .setView(input)
            .setPositiveButton(context.getString(R.string.browser_go)) { _, _ -> loadUrl(input.text.toString()) }
            .setNegativeButton(context.getString(R.string.ui_cancel), null)
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
        statusChip.text = if (control == Control.AI) context.getString(R.string.browser_ai_control) else context.getString(R.string.browser_you)
        statusChip.setTextColor(
            if (control == Control.AI) 0xFFE0A33A.toInt() else 0xFF7EC8A0.toInt(),
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
            setTextColor(0xFFDCE6F5.toInt())
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
