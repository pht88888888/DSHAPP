package com.dsh.android.browser

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AI 浏览器的**原生侧那一半** —— 把「模型想开网页/读页面/点按钮」变成一次真实操作。
 *
 * ## 它在整条链路上的位置
 *
 * ```
 * 模型(Node 进程)                原生(App 进程)                 页面(WebView 内核)
 *   browser 工具 ──写 browser-cmd.json──▶  本类轮询
 *                                           └─ AiBrowserView.evaluate ──▶ 页面里的 JS
 *   browser 工具 ◀──读 browser-result.json── 本类写回执     ◀── JSON 结果 ───┘
 * ```
 *
 * 与 UI 探针桥 同构（同一个文件信箱模式、同样的轮询与
 * 原子写），但**信箱独立**：命令可能并发发起，共用一个文件会让两个调用互相
 * 覆盖请求 —— 那种失败是偶发的、最难查。
 *
 * ## 三条不变量（与其余几个桥完全相同，刻意保持一致）
 *
 * ① 请求带自增 `id`，回执按 id 配对。
 * ② **先写结果、再删请求** —— 反过来会丢回执。
 * ③ 命令文件损坏不是错误，下一轮再读，不刷日志。
 *
 * ## 为什么所有动作都要过一次主线程闸门
 *
 * `WebView` 的每个方法与 `AiBrowserView` 的每个子 View 操作都要求 `@UiThread`，
 * 而本类跑在 `dsh-browser` 后台线程上。所以「post 到主线程 → 等回调 → 唤醒轮询
 * 线程」是唯一安全的形状。等待用 [CountDownLatch]，**带超时**：页面崩溃时
 * `evaluateJavascript` 的回调**永远不会来**（`WebHost` 的契约明确写了这一点），
 * 没有超时就会把整条轮询线程永久堵死，之后所有 browser 调用都超时。
 */
object BrowserBridge {

    private const val TAG = "DSH"

    /** 轮询间隔。与 UI 探针桥 一致，够用且不烧电。 */
    private const val POLL_MS = 120L

    /** 单条命令的原生侧超时。比插件侧的等待上限短，保证插件能拿到原因而不是干等。 */
    private const val OP_TIMEOUT_MS = 18_000L

    /**
     * 宿主（`MainActivity`）提供的能力。
     *
     * ⚠️ 用 lambda 而不是存一个 `AiBrowserView` 引用：Activity 重建会换掉整个
     *   实例，存下来的那份是"尸体"，表现成「AI 操作了，但界面没动」。
     */
    interface BrowserHost {
        /**
         * 拿到（必要时**创建并显示**）浏览器实例，在主线程调用。
         *
         * 返回 null 表示此刻不能创建（Activity 正在销毁等）。
         */
        fun browserOrNull(): AiBrowserView?

        /** 浏览器此刻是否可见（不可见时 AI 操作会自动把它调出来）。 */
        fun isBrowserVisible(): Boolean

        /**
         * AI 正在做什么（模型的 `describe`，或退化文案）。
         *
         * 宿主据此在**它自己的界面**（对话页）上显示同样的状态胶囊 ——
         * [AiBrowserView] 内部那个胶囊是 WebView 的子 View，静默模式下
         * 整个 WebView 不可见，于是用户看不到任何反馈。
         *
         * 默认实现为空。
         */
        fun onToolActivity(text: String) {}

        /**
         * AI 的一次工具调用做完了。
         *
         * 宿主据此安排「把静默浏览器收起来」—— AI 用浏览器时不该长期占着
         * 用户的屏幕（用户原话：「默认就是 AI 自己在操作侧边栏里面的浏览器，
         * 不要影响用户」）。默认实现为空，方便其他实现不关心这件事。
         */
        fun onToolDone() {}
    }

    private data class HostSource(val home: File, val provider: () -> BrowserHost?)
    private data class CommandScope(
        val binding: BrowserOwnerRegistry.Binding<HostSource>,
        val pill: String,
    )

    private val owners = BrowserOwnerRegistry<HostSource>()
    private val legacyOwner = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollLock = Any()
    private var mailbox: BrowserCommandMailbox? = null
    private var mailboxHome: File? = null

    @Volatile
    private var worker: Thread? = null

    /** Activity 在主线程绑定；同一次绑定是所有 UI 操作的代际边界。 */
    @Synchronized
    fun attach(owner: Any, home: File, host: () -> BrowserHost?) {
        owners.attach(owner, HostSource(home, host))
        if (worker?.isAlive == true) return
        Thread(::loop, "dsh-browser").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
            worker = this
            start()
        }
    }

    /** 旧 Activity 的迟到 onDestroy 不会解绑新 Activity。 */
    fun detach(owner: Any) = owners.detach(owner)

    @Deprecated("Activity 请用 attach(owner, home, host) 并在 onDestroy 调 detach(owner)")
    fun start(home: File, host: () -> BrowserHost?) = attach(legacyOwner, home, host)

    @Synchronized
    fun stop() {
        owners.clear()
        val previous = worker
        worker = null
        previous?.interrupt()
    }

    // ── 独立 browser 信箱；领件和结果不与 UI/命令域混用 ─────────────────

    private fun loop() {
        val thread = Thread.currentThread()
        while (worker === thread && !thread.isInterrupted) {
            try {
                synchronized(pollLock) { pollOnce() }
                Thread.sleep(POLL_MS)
            } catch (interrupted: InterruptedException) {
                thread.interrupt()
            } catch (error: Throwable) {
                android.util.Log.w(TAG, "browser 轮询异常: ${error.message}")
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { thread.interrupt() }
            }
        }
    }

    private fun pollOnce() {
        val binding = owners.current ?: return
        val home = binding.value.home
        if (mailboxHome != home) {
            mailboxHome = home
            mailbox = BrowserCommandMailbox(home) { message, error ->
                android.util.Log.w(TAG, "$message: ${error.message}")
            }
        }
        val transport = mailbox ?: return
        val request = transport.claim { raw -> runCatching { JSONObject(raw) }.isSuccess } ?: return
        val command = JSONObject(request.raw)
        val id = command.optLong("id", -1L)
        val completed = transport.completed(id)
        val response = when {
            completed != null && completed.raw != request.raw -> err("重复请求 id 的内容不同；未重复执行浏览器操作")
                .put("id", id).toString()
            completed != null -> completed.response
            request.recovered -> err("原生命令执行被进程中断；为避免重复点击或输入，请确认页面状态后重新发起操作")
                .put("id", id).toString()
            else -> {
                val reply = try {
                    execCommand(command, binding)
                } catch (error: Throwable) {
                    err("原生处理异常: ${error.javaClass.simpleName}: ${error.message}")
                }
                reply.put("id", id).toString()
            }
        }
        transport.complete(request, id, response)
    }

    // ── 命令分派 ────────────────────────────────────────────────────────

    private fun execCommand(cmd: JSONObject, binding: BrowserOwnerRegistry.Binding<HostSource>): JSONObject {
        val action = cmd.optString("action", "").trim()
        if (action.isEmpty()) return err("缺少 action")
        // 模型给的「我正在做什么」，显示成胶囊给用户看。
        // 没传就退化成一句通用文案 —— 至少让用户知道 AI 还活着、没卡死。
        val scope = CommandScope(binding, cmd.optString("describe", "").trim().ifEmpty { "AI 正在操作页面…" })

        return when (action) {
            "open" -> opOpen(scope, cmd.optString("url", ""))
            "read" -> evalJson(scope, READ_JS)
            "click" -> evalJson(scope, clickJs(cmd), touchesKeyboard = true)
            "type" -> evalJson(scope, typeJs(cmd), touchesKeyboard = true)
            "press" -> opPress(scope, cmd.optString("key", "Enter"))
            "scroll" -> evalJson(scope, scrollJs(cmd))
            "screenshot" -> opScreenshot(scope)
            "back" -> opNav(scope) { it.goBack() }
            "forward" -> opNav(scope) { it.goForward() }
            "reload" -> opNav(scope) { it.reload() }
            "tabs" -> opTabs(scope, cmd)
            "eval" -> opEval(scope, cmd.optString("js", ""))
            "wait" -> opWait(scope, cmd)
            else -> err("未知 action: $action")
        }
    }

    /** 打开 / 导航到一个网址，并等页面加载完。 */
    private fun opOpen(scope: CommandScope, url: String): JSONObject {
        if (url.isBlank()) return err("open 需要 url")
        val loaded = mainThreadOp(scope) { view, done ->
            view.loadUrl(url)
            view.awaitLoad { ok ->
                done(okJson(mapOf("url" to view.currentUrl(), "loaded" to ok)))
            }
        }
        return loaded
    }

    private fun opNav(scope: CommandScope, fn: (AiBrowserView) -> Unit): JSONObject =
        mainThreadOp(scope) { view, done ->
            fn(view)
            // 后退/前进/重载之后同样要等页面落地，否则下一步 read 读到的还是旧页
            view.awaitLoad { ok -> done(okJson(mapOf("url" to view.currentUrl(), "loaded" to ok))) }
        }

    private fun opScreenshot(scope: CommandScope): JSONObject =
        mainThreadOp(scope) { view, done ->
            view.captureToFile { path ->
                if (path == null) done(err("截图失败"))
                else done(okJson(mapOf("path" to path)))
            }
        }

    private fun opTabs(scope: CommandScope, cmd: JSONObject): JSONObject {
        val sub = cmd.optString("sub", "list")
        return mainThreadOp(scope) { view, done ->
            when (sub) {
                "new" -> {
                    val url = cmd.optString("url", "")
                    val ok = view.openNewTab(url.ifBlank { null })
                    done(
                        if (ok) okJson(mapOf("url" to view.currentUrl(), "count" to view.tabsCount()))
                        else err("标签已达上限，先关掉一个再开"),
                    )
                }
                "close" -> {
                    view.closeCurrentTab()
                    done(okJson(mapOf("count" to view.tabsCount(), "url" to view.currentUrl())))
                }
                "select" -> {
                    val idx = cmd.optInt("index", -1)
                    val ok = view.selectTab(idx)
                    // count 必须带上：工具层文案是「现在 N 个标签」，
                    // 漏了这个字段就会渲染成「现在 ? 个标签」。
                    done(
                        if (ok) {
                            okJson(
                                mapOf(
                                    "url" to view.currentUrl(),
                                    "count" to view.tabsCount(),
                                    "active" to idx,
                                ),
                            )
                        } else {
                            err("没有第 $idx 个标签")
                        },
                    )
                }
                else -> {
                    val arr = JSONArray()
                    for ((i, title, url) in view.listTabs()) {
                        arr.put(JSONObject().put("index", i).put("title", title).put("url", url))
                    }
                    done(okJson(mapOf("tabs" to arr, "active" to view.activeTabIndex())))
                }
            }
        }
    }

    /** 等页面上出现某段文字（或某选择器）。最多等 `timeout` ms。 */
    private fun opWait(scope: CommandScope, cmd: JSONObject): JSONObject {
        val text = cmd.optString("text", "")
        val sel = cmd.optString("selector", "")
        if (text.isBlank() && sel.isBlank()) return err("wait 需要 text 或 selector")
        val limitMs = cmd.optLong("timeout", 8_000L).coerceIn(500L, OP_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + limitMs
        val js = waitJs(text, sel)
        while (System.currentTimeMillis() < deadline) {
            if (!owners.isCurrent(scope.binding)) return err("浏览器宿主已改变")
            val r = evalJson(scope, js)
            val found = r.optJSONObject("value")?.optBoolean("found") == true
                || r.optBoolean("found")
            if (found) return okJson(mapOf("found" to true, "elapsedMs" to (limitMs - (deadline - System.currentTimeMillis()))))
            try {
                Thread.sleep(250)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return err("浏览器等待已停止")
            }
        }
        return okJson(mapOf("found" to false))
    }

    // ── 主线程闸门 ──────────────────────────────────────────────────────

    /**
     * 在主线程对浏览器执行 [op]，等它回调一次。
     *
     * ⚠️ 超时是必须的：页面渲染进程被回收后 `evaluateJavascript` 的 callback
     *   **永远不来**。没有超时，这条轮询线程会被永久堵住。
     */
    /**
     * @param touchesKeyboard 这次操作是否可能让页面元素获得焦点（从而拉起输入法）。
     *   为 true 时会在执行前切到静默输入，避免 AI 在用户看不见的地方弹键盘。
     */
    private fun mainThreadOp(
        scope: CommandScope,
        touchesKeyboard: Boolean = false,
        op: (AiBrowserView, (JSONObject) -> Unit) -> Unit,
    ): JSONObject {
        val latch = CountDownLatch(1)
        val response = java.util.concurrent.atomic.AtomicReference<JSONObject?>()
        var view: AiBrowserView? = null
        var host: BrowserHost? = null
        var detach: () -> Unit = {}

        fun cleanup() {
            // owner 失效后的 View 由原 Activity 销毁；这里不触发旧宿主的状态回调。
            // 当前 owner 的超时/异常仍须归还控制权，且绝不重新调用 provider。
            if (owners.isCurrent(scope.binding)) {
                runCatching { view?.releaseAiControl() }
                runCatching { view?.hideActionSoon() }
                runCatching { host?.onToolDone() }
                runCatching { android.webkit.CookieManager.getInstance().flush() }
                mainHandler.postDelayed({
                    if (owners.isCurrent(scope.binding)) runCatching { android.webkit.CookieManager.getInstance().flush() }
                }, 2_500L)
            }
            view = null
            host = null
        }

        fun finish(value: JSONObject) {
            // 超时/解绑先关闭闸门，迟到回调不能清理后来操作的控制权和胶囊。
            if (!response.compareAndSet(null, value)) return
            detach()
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
            else mainHandler.post { cleanup() }
            latch.countDown()
        }

        detach = owners.onInvalidated(scope.binding) { finish(err("浏览器宿主已改变")) }
        if (response.get() != null) detach()
        val posted = mainHandler.post {
            if (response.get() != null) return@post
            if (!owners.isCurrent(scope.binding)) {
                finish(err("浏览器宿主已改变"))
                return@post
            }
            try {
                host = scope.binding.value.provider()
                if (response.get() != null || !owners.isCurrent(scope.binding)) {
                    finish(err("浏览器宿主已改变"))
                    return@post
                }
                val target = host?.browserOrNull()
                if (response.get() != null || !owners.isCurrent(scope.binding)) {
                    finish(err("浏览器宿主已改变"))
                    return@post
                }
                view = target
                if (target == null) {
                    finish(err("浏览器不可用（Activity 可能正在销毁）"))
                    return@post
                }
                target.claimAiControl()
                if (touchesKeyboard) target.silentInput = true
                target.showAction(scope.pill)
                host?.onToolActivity(scope.pill)
                if (response.get() == null && owners.isCurrent(scope.binding)) {
                    op(target) { result -> finish(result) }
                }
            } catch (error: Throwable) {
                finish(err("原生异常: ${error.javaClass.simpleName}: ${error.message}"))
            }
        }
        if (!posted) finish(err("主线程拒绝执行"))
        try {
            if (!latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                finish(err("等待页面响应超时（${OP_TIMEOUT_MS}ms），页面可能正在加载或已崩溃"))
            }
        } catch (interrupted: InterruptedException) {
            finish(err("浏览器操作已停止"))
            Thread.currentThread().interrupt()
        }
        return response.get() ?: err("浏览器操作已停止")
    }

    /**
     * 执行用户给的任意 JS，把结果原样包成 `{ok:true, value:<原始文本>}`。
     *
     * **不能复用 [evalJson]**：evalJson 会把页面返回值当 JSON 解析成回执对象，
     * 而 eval 的返回值是**用户的数据**——用户执行 `JSON.stringify({a:1})`
     * 得到 `{"a":1}`，被解析后就成了一个没有 `ok` 字段的回执，
     * 工具层一律渲染成「✗ 未知错误」。所以这里只做包装、不做解析。
     */
    private fun opEval(scope: CommandScope, js: String): JSONObject {
        if (js.isBlank()) return err("空的 JS")
        // 用户/模型写的 JS 里可能带 focus()，一律按会碰键盘处理。
        return mainThreadOp(scope, touchesKeyboard = true) { view, done ->
            view.evaluate(js) { result ->
                if (result == null) {
                    done(err("页面没有返回结果（可能已导航或崩溃）"))
                    return@evaluate
                }
                done(okJson(mapOf("value" to result)))
            }
        }
    }

    /** 执行一段 JS 并解析它返回的 JSON 字符串。 */
    private fun evalJson(scope: CommandScope, js: String, touchesKeyboard: Boolean = false): JSONObject {
        if (js.isBlank()) return err("空的 JS")
        return mainThreadOp(scope, touchesKeyboard) { view, done ->
            view.evaluate(js) { result ->
                if (result == null) {
                    done(err("页面没有返回结果（可能已导航或崩溃）"))
                    return@evaluate
                }
                val parsed = try {
                    JSONObject(result)
                } catch (t: Throwable) {
                    null
                }
                if (parsed != null) {
                    done(parsed)
                } else {
                    done(okJson(mapOf("value" to result)))
                }
            }
        }
    }

    // ── 结果构造 ────────────────────────────────────────────────────────

    private fun okJson(fields: Map<String, Any?>): JSONObject =
        JSONObject().put("ok", true).also { o -> fields.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    // ══════════════════════════════════════════════════════════════════
    // 页面侧 JS
    // ══════════════════════════════════════════════════════════════════

    /**
     * 枚举「可交互元素」的核心函数，拼进每一段 JS。
     *
     * 返回 `[{el, info}]`：`el` 是真 DOM 节点（用来点/输入），`info` 是可序列化的描述。
     * ⚠️ **枚举顺序必须稳定** —— 模型拿到的 `index` 就是按这个顺序数的，
     *   两次枚举顺序不一致会让「点第 3 个」点到别的东西上。
     *   顺序由 `querySelectorAll` 的文档序保证，只要不排序就是稳定的。
     */
    private val SCAN_FN = """
        function dshScan() {
          var sel = 'a[href],button,input,select,textarea,[role=button],[role=link],[role=checkbox],[role=tab],[role=menuitem],[contenteditable=true]';
          var nodes = document.querySelectorAll(sel);
          var list = [];
          for (var i = 0; i < nodes.length && list.length < 150; i++) {
            var el = nodes[i];
            var r = el.getBoundingClientRect();
            if (r.width <= 1 || r.height <= 1) continue;
            var st = window.getComputedStyle(el);
            if (st.visibility === 'hidden' || st.display === 'none' || parseFloat(st.opacity || '1') < 0.05) continue;
            var label = (el.innerText || el.value || el.getAttribute('aria-label')
              || el.getAttribute('placeholder') || el.getAttribute('title') || '')
              .replace(/\s+/g, ' ').trim().slice(0, 90);
            list.push({ el: el, info: {
              i: list.length,
              tag: el.tagName.toLowerCase(),
              type: el.getAttribute('type') || '',
              name: el.getAttribute('name') || '',
              id: el.id || '',
              text: label,
              placeholder: el.getAttribute('placeholder') || '',
              href: el.href ? String(el.href).slice(0, 200) : '',
              value: (el.type === 'password' ? '***' : String(el.value || '')).slice(0, 80),
              checked: el.checked === true,
              x: Math.round(r.left + r.width / 2),
              y: Math.round(r.top + r.height / 2)
            }});
          }
          return list;
        }
    """.trimIndent()

    /** 读页面：地址、标题、可见文本、可交互元素清单。 */
    private val READ_JS: String = """
        (function () {
          try {
            $SCAN_FN
            var list = dshScan();
            var infos = [];
            for (var i = 0; i < list.length; i++) infos.push(list[i].info);
            var body = document.body ? (document.body.innerText || '') : '';
            return JSON.stringify({
              // ⚠️ **必须有 ok:true**。工具层用 `reply.ok !== true` 判定失败，
              //   漏了这个字段 ⇒ 每次 read 都变成「✗ 未知错误」，
              //   模型就以为 read 用不了，连带 type/click（都靠 read 给的
              //   index 定位）一起废掉。其他 action 的成功分支都带 ok。
              ok: true,
              url: location.href,
              title: document.title,
              scrollY: Math.round(window.scrollY || 0),
              scrollHeight: document.body ? document.body.scrollHeight : 0,
              text: body.replace(/\n{3,}/g, '\n\n').slice(0, 5000),
              elements: infos
            });
          } catch (e) {
            return JSON.stringify({ ok: false, error: '读取页面失败: ' + String(e) });
          }
        })()
    """.trimIndent()

    /**
     * 点击。
     *
     * 三种定位：`index`（来自上一次 read）优先，其次 `selector`，再次 `text`。
     * ⚠️ 点击前 `scrollIntoView` —— 元素在视口外时 `el.click()` 在部分站点上
     *   会被 React 的合成事件层忽略（真实点击需要元素可见）。
     */
    private fun clickJs(cmd: JSONObject): String {
        val index = cmd.optInt("index", -1)
        val selector = cmd.optString("selector", "")
        val text = cmd.optString("text", "")
        return """
            (function () {
              try {
                $SCAN_FN
                var target = null;
                if ($index >= 0) {
                  var list = dshScan();
                  if (list[$index]) target = list[$index].el;
                }
                if (!target && ${jsStr(selector)} !== '') {
                  target = document.querySelector(${jsStr(selector)});
                }
                if (!target && ${jsStr(text)} !== '') {
                  var want = ${jsStr(text)};
                  var cands = document.querySelectorAll('a,button,[role=button],input[type=submit]');
                  for (var i = 0; i < cands.length; i++) {
                    var t = (cands[i].innerText || cands[i].value || '').replace(/\s+/g, ' ').trim();
                    if (t.indexOf(want) >= 0) { target = cands[i]; break; }
                  }
                }
                if (!target) {
                  return JSON.stringify({ ok: false, error: '没找到要点的元素（页面可能已变化，重新 read 一次）' });
                }
                try { target.scrollIntoView({ block: 'center' }); } catch (e) {}
                target.focus && target.focus();
                target.click();
                return JSON.stringify({ ok: true, clicked: (target.innerText || target.value || target.tagName || '').replace(/\s+/g, ' ').trim().slice(0, 80) });
              } catch (e) {
                return JSON.stringify({ ok: false, error: '点击失败: ' + String(e) });
              }
            })()
        """.trimIndent()
    }

    /**
     * 往输入框写文字。
     *
     * ⚠️ **必须用原生 setter + 派发 input 事件**，不能直接 `el.value = x`：
     *   React 的受控组件把 `value` 劫持在内部 state 上，直接赋值会让 DOM 与
     *   React state 不一致 —— 表现是「输入框里看得见字，但提交时是空的」。
     *   这是所有 React 站点（GitHub 新版、Google、Twitter）上最典型的坑。
     */
    private fun typeJs(cmd: JSONObject): String {
        val index = cmd.optInt("index", -1)
        val selector = cmd.optString("selector", "")
        val value = cmd.optString("value", "")
        val enter = cmd.optBoolean("enter", false)
        return """
            (function () {
              try {
                $SCAN_FN
                var target = null;
                if ($index >= 0) {
                  var list = dshScan();
                  if (list[$index]) target = list[$index].el;
                }
                if (!target && ${jsStr(selector)} !== '') {
                  target = document.querySelector(${jsStr(selector)});
                }
                if (!target) {
                  return JSON.stringify({ ok: false, error: '没找到输入框（重新 read 一次看 index）' });
                }
                try { target.scrollIntoView({ block: 'center' }); } catch (e) {}
                target.focus && target.focus();
                var v = ${jsStr(value)};
                var proto = target.tagName === 'TEXTAREA'
                  ? window.HTMLTextAreaElement.prototype
                  : window.HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                if (setter && setter.set) setter.set.call(target, v); else target.value = v;
                target.dispatchEvent(new Event('input', { bubbles: true }));
                target.dispatchEvent(new Event('change', { bubbles: true }));
                if (${enter} === true) {
                  target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
                  target.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, bubbles: true }));
                  if (target.form) {
                    try {
                      if (target.form.requestSubmit) target.form.requestSubmit(); else target.form.submit();
                    } catch (e) {}
                  }
                }
                return JSON.stringify({ ok: true, value: String(target.value || '').slice(0, 80) });
              } catch (e) {
                return JSON.stringify({ ok: false, error: '输入失败: ' + String(e) });
              }
            })()
        """.trimIndent()
    }

    /** 常见键名 → Android keyCode。用于把 press 变成真实按键注入。 */
    private fun keyCodeOf(key: String): Int? = when (key.trim().lowercase()) {
        "enter", "return" -> android.view.KeyEvent.KEYCODE_ENTER
        "tab" -> android.view.KeyEvent.KEYCODE_TAB
        "escape", "esc" -> android.view.KeyEvent.KEYCODE_ESCAPE
        "backspace" -> android.view.KeyEvent.KEYCODE_DEL
        "delete", "del" -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
        "space", " " -> android.view.KeyEvent.KEYCODE_SPACE
        "arrowup", "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
        "arrowdown", "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        "arrowleft", "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        "arrowright", "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        "home" -> android.view.KeyEvent.KEYCODE_MOVE_HOME
        "end" -> android.view.KeyEvent.KEYCODE_MOVE_END
        "pageup" -> android.view.KeyEvent.KEYCODE_PAGE_UP
        "pagedown" -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
        else -> null
    }

    /**
     * 给当前焦点元素发一个按键。
     *
     * **优先走原生注入**（[AiBrowserView.dispatchKey]）：只有真实按键才会触发
     * 浏览器默认行为（Tab 移焦点、Enter 提交表单、方向键移光标）。
     * 用 JS 合成事件时 `isTrusted=false`，页面监听器能收到但默认行为不执行，
     * 表现就是「提示按成功了，但焦点没动」。
     *
     * 键名不在映射表里时退回 JS 合成事件，至少让页面监听器收到事件。
     */
    private fun opPress(scope: CommandScope, key: String): JSONObject {
        // press 走真实按键分发，Enter 可能提交表单 ⇒ 同样会碰键盘。
        val code = keyCodeOf(key) ?: return evalJson(scope, pressJs(key), touchesKeyboard = true)
        return mainThreadOp(scope, touchesKeyboard = true) { view, done ->
            if (view.dispatchKey(code)) {
                done(okJson(mapOf("key" to key, "native" to true)))
            } else {
                done(err("没有可用的页面，按键没发出去"))
            }
        }
    }

    private fun pressJs(key: String): String = """
        (function () {
          try {
            var el = document.activeElement || document.body;
            var k = ${jsStr(key)};
            el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));
            el.dispatchEvent(new KeyboardEvent('keyup', { key: k, bubbles: true }));
            if (k === 'Enter' && el.form) {
              try { el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); } catch (e) {}
            }
            return JSON.stringify({ ok: true, key: k });
          } catch (e) {
            return JSON.stringify({ ok: false, error: String(e) });
          }
        })()
    """.trimIndent()

    private fun scrollJs(cmd: JSONObject): String {
        val dy = cmd.optInt("dy", 600)
        val to = cmd.optString("to", "")
        return if (to == "top") {
            "(function(){ window.scrollTo(0,0); return JSON.stringify({ok:true, y:0}); })()"
        } else if (to == "bottom") {
            "(function(){ window.scrollTo(0, document.body.scrollHeight); return JSON.stringify({ok:true, y:Math.round(window.scrollY)}); })()"
        } else {
            "(function(){ window.scrollBy(0, $dy); return JSON.stringify({ok:true, y:Math.round(window.scrollY)}); })()"
        }
    }

    private fun waitJs(text: String, selector: String): String = """
        (function () {
          try {
            var found = false;
            if (${jsStr(selector)} !== '') {
              found = document.querySelector(${jsStr(selector)}) !== null;
            } else {
              var body = document.body ? (document.body.innerText || '') : '';
              found = body.indexOf(${jsStr(text)}) >= 0;
            }
            return JSON.stringify({ ok: true, found: found });
          } catch (e) {
            return JSON.stringify({ ok: false, error: String(e) });
          }
        })()
    """.trimIndent()

    /**
     * 把 Kotlin 字符串安全地塞进 JS 单引号字面量。
     *
     * ⚠️ 只替换反斜杠、单引号、换行、回车与 `</` —— 漏掉任何一项都会让
     *   带引号的输入（密码里常有）直接把 JS 语法搞崩，而症状是
     *   `SyntaxError: Unexpected token`，完全指不到「是那条命令的内容有问题」。
     */
    private fun jsStr(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("</", "<\\/")
            // ⚠️ **必须转义 U+2028 / U+2029**。这两个字符在 JSON 里是合法字符，
            //   但在 JS **源码**里是行终止符 —— 原样嵌进单引号字符串会让整段
            //   JS 变成语法错误，`evaluateJavascript` 直接不执行、回调拿到
            //   null，模型看到的就是「read/type 用不了」。
            //   真实来源：从网页复制的文本（部分 CMS 的段落分隔符就是 U+2028）、
            //   以及 JSON.stringify 出来的值（它**不**转义这两个字符）。
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return "'$escaped'"
    }
}
