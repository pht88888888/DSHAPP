package com.dsh.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
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
        }

        w.addJavascriptInterface(Bridge(), "BubbleNative")
        w.webViewClient = object : WebViewClient() {
            /**
             * 渲染进程死了。**必须返回 true，否则整个应用进程会被系统杀掉。**
             *
             * 官方文档：`onRenderProcessGone` 默认实现返回 `false`，而返回 `false`
             * 时**系统会终止整个应用进程** —— 用户看到的就是「App 突然自己退了」。
             *
             * ⚠️ 这是项目里**第三个**独立 WebView（另外两个是主 WebView 和
             *   AI 浏览器），前两个都已覆写，唯独这里漏了。
             *
             * 而这一处的崩溃概率其实不低：它循环播放视频，
             * `mediaPlaybackRequiresUserGesture = false` + 硬件解码器，
             * 素材编码不规范（桌宠素材由用户/插件提供，来源很杂）时解码器崩溃
             * 是常见情况。崩一次整个 App 就没了。
             *
             * 处理策略：这是**纯装饰层**，正确做法是彻底释放它并退化成静态首帧，
             * 而不是让用户面对"桌宠一播视频 App 就退"。释放后 `videoLayer` 为 null，
             * 上层逻辑本来就有「没有视频层就显示图片」的路径。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                Log.w(
                    TAG,
                    "悬浮球视频层的渲染进程${if (detail?.didCrash() == true) "崩溃" else "被回收"}，" +
                        "释放视频层、退回静态显示",
                )
                // 只摘掉这一个 WebView，不碰 container 的其它内容。
                runCatching { (view?.parent as? android.view.ViewGroup)?.removeView(view) }
                runCatching { view?.destroy() }
                if (view === web) {
                    web = null
                    pageReady = false
                    pending = null
                    current = ""
                }
                // ⚠️ 必须 true：返回 false 会让系统终止整个应用进程。
                return true
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
    pause: function(){ try { active.pause(); } catch (e) {} return 'ok'; },
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
