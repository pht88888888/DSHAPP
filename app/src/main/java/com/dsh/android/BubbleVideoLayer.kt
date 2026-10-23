package com.dsh.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dsh.android.log.DshLog as Log
import java.io.File

/**
 * 悬浮球内容层的**视频渲染后端** —— 让球能播带 alpha 通道的 webm。
 *
 * ## 为什么是 WebView（而不是 MediaPlayer / VideoView）
 *
 * 原生 Android **没有任何 View 能播透明视频**：`MediaPlayer`/`VideoView` 走
 * `SurfaceView`/`TextureView`，两者都不参与 alpha 合成；MediaCodec 的 VP9 解码器
 * 也不输出 alpha side data。真机实测（CDY-TN90 / Android 10 / Chromium 114）
 * 唯一保得住 alpha 的路是 **WebView 的 `video` → `canvas`**：
 * 透明背景占 82.9%、四角像素 `a≈0`。
 *
 * ⚠️ **WebView 只是"内容层的一个渲染后端"，不是悬浮球的实现。** 拖拽、抛掷、
 *   物理、状态映射、面板、贴边折叠全部仍在原生 Kotlin 里 —— 这正是它与
 *   「把整个球做成一个网页」的区别：后者的触摸、焦点、输入法、层级全都要重来一遍，
 *   而本 App 的悬浮窗契约（[FloatingBubble] 里那几条）已经踩平了那些坑。
 *
 * ## 用 canvas 而不是直接 `<video>`
 *
 * 直接显示 `<video>` 有两个问题：① 各内核对"透明视频"的合成路径不一致，canvas
 * 是逐帧重绘、alpha 由我们自己保证；② 挤压/拉伸（squash & stretch）要靠
 * `ctx.drawImage` 的目标矩形做，CSS transform 在缩放时会把边缘插值成半透明。
 *
 * ⚠️ **全程不调用 `getImageData`/`toDataURL`**：那两个会把 canvas 标记成"被污染"，
 *   在 `file://` 下直接抛 SecurityError。绘制本身不需要读回像素。
 *
 * ## 动作切换
 *
 * 一次只播一个动作（单 `<video>` 元素，切源即换动作）。播放循环由原生决定：
 * - `loop = true`（待机类）：JS 自己无限循环，**不回调**，省掉每轮的进程往返；
 * - `loop = false`（一次性动作）：播完回调 [onEnded]，由原生挑下一个动作。
 *
 * ⚠️ **`loop=false` 时必须显式 `video.loop=false`**：上一轮若是待机动作，`loop`
 *   还停在 `true`，不重置的话一次性动作会无声地循环下去，表现为"做完这个动作
 *   就卡住不换"。
 */
class BubbleVideoLayer(
    private val context: Context,
    /** 一次性动作播完时的回调（参数是刚播完的动作名）。在主线程调用。 */
    private val onEnded: (String) -> Unit,
) {

    private companion object {
        const val TAG = DshApplication.TAG

        /** 播放器 HTML 的落点目录（在 `filesDir` 下）。 */
        const val PLAYER_DIR = "bubble-player"
        const val PLAYER_FILE = "player.html"
    }

    /** 承载 WebView 的容器（`bubbleCircle` 里的那一层）。 */
    private var host: android.widget.FrameLayout? = null

    private var web: WebView? = null

    /** 页面是否已加载完成（未完成时 evaluateJavascript 会丢）。 */
    private var pageReady = false

    /** 当前正在播的动作名。 */
    private var current = ""

    /** 等待页面就绪后要补播的动作（`play` 早于 `onPageFinished` 时用）。 */
    private var pending: Triple<String, String, Boolean>? = null

    /** 页面加载失败后是否已经报过（避免刷日志）。 */
    private var warned = false

    /**
     * **受管内容的白名单目录**。
     *
     * ⚠️ 两个目录都必须放行：
     * - `bubble-player/`：我们生成的播放器页面
     * - `bubble-videos/`：视频素材（页面通过 `<video src>` 引用）
     *
     * ⚠️ 用**目录列表**而不是单个 root：页面和素材在不同目录，
     *   只放行一个会让另一个加载失败（表现为黑屏，且不报错）。
     */
    private val mediaRoots: List<java.io.File> by lazy {
        listOf(
            java.io.File(context.filesDir, PLAYER_DIR),
            java.io.File(context.filesDir, com.dsh.android.BubbleVideos.DIR),
        )
    }

    /** 是否已经创建过 WebView（创建很贵，只在真有视频素材时做）。 */
    val created: Boolean get() = web != null

    /** 当前动作名。 */
    val action: String get() = current

    /**
     * 惰性创建 WebView 并挂到 [container] 上。
     *
     * ⚠️ **必须惰性**：一个 WebView 常驻要几十 MB 内存，而绝大多数用户根本不会
     *   放视频素材（球就是一张头像）。没有视频时连 WebView 都不该存在。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(container: android.widget.FrameLayout) {
        host = container
        if (web != null) return

        val html = ensurePlayerFile() ?: run {
            Log.w(TAG, "视频播放器页面写入失败，视频层不可用")
            return
        }

        val w = WebView(context)
        // 透明背景：窗口本身已是 TRANSLUCENT，这里再压掉 WebView 自己的白底。
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        // ⚠️ WebView 默认会消费触摸 —— 悬浮球的拖动/点击靠父容器的
        //   onInterceptTouchEvent 抢回来（见 BubbleTouchFrame）。
        w.isClickable = false
        w.isFocusable = false
        w.isLongClickable = false

        w.settings.apply {
            javaScriptEnabled = true
            // 素材与页面都在 filesDir 里，都是 file:// —— 必须放行同源文件访问。
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowContentAccess = false
            // ⚠️ 关掉缩放：球本身就是最终尺寸，让内核再缩一次会糊。
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // 视频要自动播放：muted + 允许无手势播放（WebView 侧由 muted 兜住）。
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = false

            // ── 安全加固（开发指导 §G6）──────────────────────────────────
            //
            // 文档：「媒体 WebView **只加载受管内容，不开放任意网页或插件脚本
            // 执行口**」。
            //
            // ⚠️ **下面每一项都必须显式关**，不能依赖默认值 ——
            //   其中两条的默认值在旧 API 上是**开**的：
            //
            //   | 设置 | 默认 | 风险 |
            //   |---|---|---|
            //   | allowFileAccessFromFileURLs | API<16 为 true | file:// 页面可读**其他** file:// |
            //   | allowUniversalAccessFromFileURLs | API<16 为 true | file:// 页面可读**任意**本地文件并发网络请求 |
            //
            //   本页面是通过 `file://` 加载的（见下面的 loadUrl），所以这两条
            //   一旦为 true，页面里任何脚本都能把 `/data/data/com.dsh.android/`
            //   下的凭据文件读出来 —— 而页面里有我们注入的 `BubbleNative` 桥。
            //
            //   当前页面内容是我们自己生成的（受管），但**纵深防御**：
            //   万一素材路径被替换成攻击者可控的内容，这几道门能挡住升级。
            allowFileAccess = true              // 需要读我们自己的 html/视频文件
            allowContentAccess = false          // 不需要 content:// 
            allowFileAccessFromFileURLs = false // ⚠️ 关键：file:// 不能读其他 file://
            allowUniversalAccessFromFileURLs = false // ⚠️ 关键：file:// 不能读任意文件
            blockNetworkLoads = true            // ⚠️ 媒体层不需要任何网络
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 关掉「安全浏览」：它会在后台拉 Google 的名单，而我们这里
            // 只加载本地受管内容，没有这个需求（也少一个网络出口）。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }

        w.addJavascriptInterface(Bridge(), "BubbleNative")
        w.webViewClient = object : WebViewClient() {
            /**
             * **拦截一切非受管导航**（开发指导 §G6）。
             *
             * 文档：「媒体 WebView **只加载受管内容，不开放任意网页**」。
             *
             * ⚠️ 为什么必须拦：页面里有我们注入的 `BubbleNative` 桥，
             *   如果页面被导航到任意网址，那个网站的脚本就能调用桥 ——
             *   那是「把桥交给了外部内容」。
             *
             * ⚠️ 判据用「是不是我们的文件」而不是「是不是 file://」：
             *   后者允许加载 `/data/data/com.dsh.android/` 下任意文件
             *   （比如凭据），那正是要防的。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return true   // 拿不到就拦
                return !isManagedUrl(url)
            }

            /**
             * 拦掉**所有子资源**加载，只放行受管文件。
             *
             * ⚠️ 与 `shouldOverrideUrlLoading` 分工不同：那个只管「页面导航」，
             *   这个管 `<video src=...>`、`<img src=...>` 这类子资源。
             *   只拦导航的话，页面里的 `<img src="https://evil/x.png">` 仍会发出去
             *   —— 那是一个**网络信标**，能泄露用户在线状态。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return blockedResponse()
                return if (isManagedUrl(url)) null else blockedResponse()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                // 补播：页面加载期间来的 play() 会丢，这里补上。
                pending?.let { (a, src, loop) ->
                    pending = null
                    playInternal(a, src, loop)
                }
            }
        }

        // ⚠️ 禁止创建新窗口：`target=_blank` 或 `window.open()` 会绕过
        //   WebViewClient 的导航拦截（新窗口有自己的 client）。
        //   返回 false = 不创建，且**不**把 URL 交给系统浏览器。
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false
        }

        container.addView(
            w,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        web = w
        w.loadUrl("file://" + html.absolutePath)
        Log.i(TAG, "悬浮球视频层已创建: ${html.absolutePath}")
    }

    /**
     * 这个 URL 是否属于**受管内容**。
     *
     * 判据（三条都要满足）：
     * 1. 必须是 `file://`
     * 2. 解析后的**真实路径**必须在我们给这个层准备的目录内
     * 3. 不能是目录本身
     *
     * ⚠️ 用 `canonicalPath` 而不是字符串前缀比较：`file:///a/b/../../etc/passwd`
     *   这种路径字符串前缀能过、真实路径却在外面 —— 那是典型的路径穿越。
     */
    private fun isManagedUrl(url: String): Boolean {
        if (!url.startsWith("file://")) return false
        return try {
            val path = java.net.URI(url).path ?: return false
            val f = java.io.File(path).canonicalFile
            // 必须严格在**某个**白名单目录之下
            // （相等不算 —— 那些是目录，不该被当页面加载）
            return mediaRoots.any { root ->
                val r = try { root.canonicalFile } catch (t: Throwable) { return@any false }
                f.path.startsWith(r.path + java.io.File.separator)
            }
        } catch (t: Throwable) {
            // ⚠️ 解析失败一律**拒绝**（fail closed）：宁可白屏也不要放行
            Log.w(TAG, "媒体层：URL 解析失败，拒绝加载（$url）")
            false
        }
    }

    /** 被拦下的请求返回一个空响应（而不是让 WebView 自己去请求）。 */
    private fun blockedResponse(): android.webkit.WebResourceResponse =
        android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

    /**
     * 切到某个动作并开始播放。
     *
     * @param action 动作名（用于回调与去重）。
     * @param url 素材的 `file://` URL。
     * @param loop 是否无限循环（待机类动作 true）。
     */
    fun play(action: String, url: String, loop: Boolean) {
        val w = web ?: return
        if (!pageReady) {
            pending = Triple(action, url, loop)
            return
        }
        if (action == current) return
        playInternal(action, url, loop)
    }

    /** 停掉播放但保留 WebView（球被移除 / App 回前台时省电）。 */
    fun pause() {
        if (web == null) return
        web?.evaluateJavascript("window.__pet && window.__pet.pause && window.__pet.pause()", null)
    }

    /** 恢复播放。 */
    fun resume() {
        if (web == null) return
        web?.evaluateJavascript("window.__pet && window.__pet.resume && window.__pet.resume()", null)
    }

    /** 彻底释放（球被隐藏时）。 */
    fun release() {
        val w = web ?: return
        web = null
        pageReady = false
        pending = null
        current = ""
        runCatching {
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.destroy()
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private fun playInternal(action: String, url: String, loop: Boolean) {
        current = action
        val js = "window.__pet && window.__pet.play(" +
            "${quote(action)}, ${quote(url)}, ${if (loop) "true" else "false"})"
        web?.evaluateJavascript(js, null)
    }

    /** 拼一个 JS 字符串字面量。用 JSON 转义，避免路径里的反斜杠/引号破坏脚本。 */
    private fun quote(s: String): String = org.json.JSONObject.quote(s)

    /**
     * 把播放器页面写到磁盘（内容不变就跳过）。
     *
     * ⚠️ **写成文件而不是 `loadDataWithBaseURL`**：后者的 origin 是 opaque，
     *   在 `file://` 下加载视频会被判跨源而失败，且各内核行为不一致。落地成文件
     *   后页面与素材同属 `file://`，同源判定稳定。
     */
    private fun ensurePlayerFile(): File? {
        return try {
            val dir = File(context.filesDir, PLAYER_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val f = File(dir, PLAYER_FILE)
            if (!f.isFile || f.readText() != PLAYER_HTML) f.writeText(PLAYER_HTML)
            f
        } catch (t: Throwable) {
            Log.w(TAG, "写入视频播放器页面失败: ${t.message}")
            null
        }
    }

    /**
     * 页面 → 原生的回调。
     *
     * ⚠️ `@JavascriptInterface` 的方法**必须 public 且返回基本类型或 String**，
     *   返回自定义对象会被静默丢弃。
     */
    inner class Bridge {
        /** 一次性动作播完。 */
        @JavascriptInterface
        fun onEnded(action: String) {
            // ⚠️ **必须写 `this@BubbleVideoLayer`**：`Bridge` 自己也有个 `onEnded`
            //   成员函数，直接写 `onEnded(action)` 会解析成**递归调用自己** ——
            //   构造参数被同名成员遮蔽了。症状极具迷惑性：桥那侧日志一切正常
            //   （每帧都打印"onEnded action=walk"），而原生永远收不到事件，
            //   表现为「一次性动作播完不换下一个、转向动作播完不翻面」。
            this@BubbleVideoLayer.onEnded(action)
        }

        /** JS 侧出错（解码失败、文件缺失…）。 */
        @JavascriptInterface
        fun onError(action: String, message: String) {
            if (warned) return
            warned = true
            Log.w(TAG, "视频播放失败（动作 $action）: $message")
        }
    }

    /**
     * 播放器页面。
     *
     * 每帧 `clearRect` + `drawImage`：**先清空再绘制**，否则上一帧的像素会留在
     * canvas 上，表现为"动作拖影"。alpha 由 canvas 自身保证，不需要额外处理。
     *
     * ⚠️ 页面里**不能出现反引号**（Kotlin 原始字符串会被提前终止），也不能出现
     *   `$`（会被当模板插值）。本页已按此约束写，改动时留意。
     */
    private val PLAYER_HTML: String
        get() = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  html,body{margin:0;padding:0;background:transparent;overflow:hidden;width:100%;height:100%}
  #c{display:block;width:100%;height:100%}
</style>
</head>
<body>
<canvas id="c"></canvas>
<script>
(function(){
  var cv = document.getElementById('c');
  var ctx = cv.getContext('2d');
  var video = document.createElement('video');
  video.muted = true;
  video.playsInline = true;
  video.preload = 'auto';
  video.setAttribute('webkit-playsinline', '');
  var curAction = '';
  var raf = 0;

  // ── 双缓冲：两个 video 元素轮流当"当前"与"下一段" ──────────────────────
  //
  // ⚠️ **这是修「切动画会闪一下」的关键。**
  //
  //   原实现只有一个 video：'video.src = src; video.load(); video.play()'。
  //   'load()' 会**立刻丢弃当前解码缓冲**，而新视频要等 'readyState >= 2'
  //   才有可画的帧；这期间 draw() 里那个 'readyState >= 2' 判断为假，
  //   canvas 被 clearRect 清空后什么都没画 ⇒ 至少闪一帧（网络/解码慢时更久）。
  //
  //   改成两个元素后，切换流程是：
  //     ① 让"待命"的那个去 load + 预解码（此时**旧的那个仍在画**）
  //     ② 等它 readyState >= 3（HAVE_FUTURE_DATA，已有一帧可画）
  //     ③ 原子地把 'active' 指针切过去，再从这一帧开始画
  //   全程屏幕上一直有画面，没有空窗。
  var A = makeVideo();
  var B = makeVideo();
  var active = A;          // 正在画的那个
  var standby = B;         // 正在预加载的那个
  var pendingSpec = null;  // 预加载完成后要接管的 {action, src, loop}

  function makeVideo(){
    var v = document.createElement('video');
    v.muted = true;
    v.playsInline = true;
    v.preload = 'auto';
    v.setAttribute('webkit-playsinline', '');
    return v;
  }

  function syncSize(){
    var r = cv.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    var w = Math.max(1, Math.round(r.width * dpr));
    var h = Math.max(1, Math.round(r.height * dpr));
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
  }

  function draw(){
    syncSize();
    var W = cv.width, H = cv.height;
    // ⚠️ **不能每帧都 clearRect**。
    //
    //   原实现无条件清空再画，于是任何一帧"暂时没有可画内容"都会露出空白。
    //   现在只在**确实画了新内容**时才清空并重绘；没有新内容就保留上一帧，
    //   屏幕上是"静止"而不是"闪一下"。
    var v = active;
    if (v.readyState >= 2 && v.videoWidth > 0) {
      var vw = v.videoWidth, vh = v.videoHeight;
      var s = Math.min(W / vw, H / vh);
      var dw = vw * s, dh = vh * s;
      ctx.clearRect(0, 0, W, H);
      ctx.drawImage(v, (W - dw) / 2, (H - dh) / 2, dw, dh);
    }
    // readyState < 2 时**保留上一帧**（不清屏）—— 这正是消除闪烁的关键。
    raf = requestAnimationFrame(draw);
  }

  function wire(el){
    el.addEventListener('ended', function(){
      if (el.loop) return;
      // 只有**当前正在画的那个**播完才算数：待命元素是预加载用的，
      // 它"播完"没有任何语义（而且它多半是 paused）。
      if (el !== active) return;
      try { BubbleNative.onEnded(curAction); } catch (e) {}
    });
    el.addEventListener('error', function(){
      if (el !== active && el !== standby) return;
      var m = el.error ? ('code ' + el.error.code) : 'unknown';
      try { BubbleNative.onError(curAction, m); } catch (e) {}
    });
  }
  wire(A); wire(B);

  /**
   * 把 standby 换上来的那一刻。
   *
   * 前提：standby 已经 readyState >= 3（有可画帧）。此时：
   * ① 旧 active 暂停（省解码）；
   * ② active/standby 互换；
   * ③ 新 active 开始播 —— **屏幕上从头到尾都有画面**。
   */
  function promote(){
    if (!pendingSpec) return;
    var spec = pendingSpec;
    pendingSpec = null;

    var old = active;
    active = standby;
    standby = old;
    curAction = spec.action;
    active.loop = !!spec.loop;

    var p = active.play();
    if (p && p.catch) { p.catch(function(e){
      try { BubbleNative.onError(spec.action, String(e)); } catch (x) {}
    }); }
    try { standby.pause(); } catch (e) {}
  }

  /**
   * 让 standby 预加载一段素材。
   *
   * ⚠️ **'load()' 之后不能马上 'play()' 就完事**：新源在 readyState 到达 3
   *   之前没有可画帧。所以这里挂一次性监听，等到位了再 [promote]。
   *   超时兜底 600ms：万一 'canplay' 没来（部分内核在 paused 状态下不触发），
   *   也强行切过去 —— 宁可切到一个空帧，也不要宠物永远停在旧动作上。
   */
  function preload(spec){
    pendingSpec = spec;
    standby.loop = !!spec.loop;
    standby.src = spec.src;
    standby.load();
    // paused 元素也要尝试 play()，否则某些内核不预解码
    var p0 = standby.play();
    if (p0 && p0.catch) { p0.catch(function(){}); }

    var done = false;
    function ready(){
      if (done) return;
      done = true;
      standby.removeEventListener('canplay', ready);
      standby.removeEventListener('loadeddata', ready);
      promote();
    }
    standby.addEventListener('canplay', ready);
    standby.addEventListener('loadeddata', ready);
    setTimeout(function(){
      if (done) return;
      if (standby.readyState >= 2) { ready(); }
      else {
        // 实在等不到：先把旧的继续留着，避免空屏；600ms 后再试一次。
        done = true;
        setTimeout(function(){ if (pendingSpec) { pendingSpec = spec; preload(spec); } }, 600);
      }
    }, 600);
  }

  window.__pet = {
    play: function(action, src, loop){
      // 同一个动作重复下发：什么都不做（避免无谓地重载、白闪）。
      if (action === curAction && !pendingSpec) return 'ok';
      preload({ action: action, src: src, loop: !!loop });
      return 'ok';
    },
    // ⚠️ 必须暂停**两个**元素：页面是 A/B 双缓冲（`active` + `standby`），
    //   只暂停 active 的话 standby 仍在解码 —— 而它恰恰是「预加载下一个动作」
    //   的那个，会一直循环解码直到被 promote。实测这是「球隐藏了但还在发热」
    //   的一个来源。
    pause: function(){ try { active.pause(); } catch (e) {} try { standby.pause(); } catch (e) {} return 'ok'; },
    resume: function(){ var p = active.play(); if (p && p.catch) { p.catch(function(){}); } return 'ok'; },
    current: function(){ return curAction; },
    ready: function(){ return active.readyState; }
  };

  raf = requestAnimationFrame(draw);
})();
</script>
</body>
</html>
""".trimIndent()
}
