/*
 * DSH Android 桥 —— 页面侧的那一半（content script）。
 *
 * ## 为什么需要这个文件（而不是像 WebView 那样一个 addJavascriptInterface 了事）
 *
 * GeckoView **没有** `addJavascriptInterface`，也**没有** `evaluateJavascript`
 * 的等价物；官方要求所有「原生 ↔ 页面」通信都走 WebExtension 的 native
 * messaging。所以 `window.DSHAndroid` 这个对象得由我们自己造出来。
 *
 * ## 为什么由 content script **直连**原生，而不是经 background 中转
 *
 * 直觉上「content → background → 原生」更规整，但 GeckoView **没有实现
 * `browser.tabs` API**（GeckoView 里根本没有"标签页"这个概念，session 才是）。
 * 于是 background 侧既没法枚举标签页、也没法 `tabs.sendMessage` 把消息发回
 * content script —— 而**原生 → 页面**的两个方向（推快照、`evaluateJavascript`）
 * 恰恰都要走那条路。中转方案在 GeckoView 上是**不通**的。
 *
 * 官方文档明确支持直连：只要 manifest 里有 `nativeMessagingFromContent` +
 * `geckoViewAddons` 权限，content script 就能自己 `connectNative`。原生侧用
 * `session.getWebExtensionController().setMessageDelegate(...)` 接（**不能**用
 * `extension.setMessageDelegate` —— 后者只接 background 的消息，
 * 见 mozilla/geckoview#220）。
 *
 * ## ⚠️ 核心陷阱：content script 运行在**隔离世界**
 *
 * content script 与页面共享 DOM，但**不共享全局对象**。在隔离世界里写
 * `window.DSHAndroid = {...}`，页面里的 `window.DSHAndroid` 依然是 undefined。
 * 页面侧（`mobile-ui-plugin/dsh-android-mobile/lib/client.js`）读的是**页面世界**
 * 的 window，所以那样写会得到「桥不存在」的降级路径：设置页的气泡开关、
 * root 状态、无线调试配对**全部静默不可用**。
 *
 * 而且光有 `exportFunction(fn)` 也**不够**：
 *
 * · 它**必须传第二个参数**（目标作用域）—— 少传会抛
 *   `Error: Function requires at least 2 arguments`（真机实测，就是本文件第一版）。
 * · 即便传了，**函数体仍在自己（隔离世界）的 compartment 里执行** —— 函数体里
 *   写的 `window`、`console`、`HTMLAnchorElement` 全是隔离世界那一份。
 *
 * 所以本文件遵守两条规则：
 *
 * 1. 凡是**要交给页面世界调用**的函数 → `exportFunction(fn, pageWindow)`；
 *    凡是**要给页面世界的对象** → `cloneInto(obj, pageWindow)`；
 *    凡是**要改页面世界的东西**（console、原型链）→ 走 `pageWindow.eval(字符串)`，
 *    在页面世界原生地改，不跨 compartment 传对象。
 * 2. `pageWindow` 一律取 `window.wrappedJSObject`（Xray waiver）。
 *
 * ## 同步语义怎么保（这是与 system 版对齐的关键）
 *
 * 页面侧是**同步**用法：
 *
 *     const raw = bridge.rootState();        // 直接拿返回值
 *     const o = JSON.parse(raw);
 *
 * 而 native messaging 只能异步。这里分两类处理：
 *
 * · **无返回值的方法**（onStatus / onImeWillShow / …）→ 直接 fire-and-forget，
 *   页面侧本来就不等结果。
 * · **有返回值的方法**（`pairingNotificationsEnabled` / `bubbleState` /
 *   `rootState`）→ 读**本地缓存**。exportFunction 导出的函数从页面世界调用时
 *   同步返回原始类型，于是页面拿到的就是同步的字符串 —— 无需改页面代码。
 *
 *   缓存的刷新靠原生**主动推送快照**。这与 system 版的可观测行为一致：
 *   `rootRecheck()` 之后界面靠轮询 `rootState()` 等 `at` 时间戳变化，而推送会
 *   更新那个时间戳。**唯一差别是首次读取可能比 system 版晚几十毫秒**
 *   （等第一份快照到达），页面侧的降级分支能容忍它。
 */

'use strict';

/** 原生推来的只读快照（页面侧经导出函数**同步**读它）。 */
const snapshot = {
  root: '{}',
  bubble: '{}',
  appearance: '{}',
  pairingEnabled: false,
  // 日志摘要。⚠️ 与 root/bubble 同一套缓存语义：native messaging 只能异步，
  //   而设置页的 `logSummary()` 是**同步**读取（见 system 版的同名字段）。
  logSummary: '{}',
  // Shizuku 状态（uid 2000 的第二条来源）。同样是页面侧**同步**读取
  //   （`bridge.shizukuState()`），只能经快照缓存。
  //   ⚠️ 它必须进快照还有一个更硬的理由：Shizuku 的授权是**异步**回来的
  //     （用户在 Shizuku 自己的确认界面上点「允许」），设置页轮询时读的就是
  //     这个缓存 —— 不推的话用户授权完，界面永远停在「未授权」。
  shizuku: '{}',
};

/** 是否已经装过（同一次 document 生命周期内只装一次）。 */
let installed = false;

/** 与原生侧的常驻端口；null 表示还没连上（或刚断开）。 */
let nativePort = null;

/** 端口断开后的重连定时器。 */
let reconnectTimer = 0;

/** 把一条消息发给原生；端口没通时静默丢弃。 */
function send(msg) {
  if (!nativePort) return;
  try {
    nativePort.postMessage(Object.assign({ __dsh: true }, msg));
  } catch (e) {
    /* 端口刚断：下一次重连会补上 hello */
  }
}

/**
 * 把一条诊断信息打到宿主的 logcat（TAG = `DSH`）。
 *
 * ⚠️ **不要用 `console.error` 代替它**：实测 content script 的 console 输出
 *   **不会**进 logcat（`GeckoRuntimeSettings.consoleOutput(true)` 只管页面世界
 *   那一份）；只有**未捕获异常**才会以 `Web Content: [JavaScript Error: ...]`
 *   出现。于是「桥没装上」这件事在日志里是完全静默的 —— 而它的后果是设置页
 *   三块功能静默失效，属于最难查的一类。所以诊断一律经端口回报给原生去打。
 */
function log(text) {
  const line = String(text);
  // ⚠️ 端口没通时**缓存**而不是丢弃：`installBridge()` 跑在 connectNative 之前
  //   （桥要先装好，页面脚本才会在 document_start 就见到 DSHAndroid），
  //   那一批最有价值的诊断刚好落在"端口还没通"的窗口里。丢了就等于没有。
  if (!nativePort) {
    if (pendingLogs.length < PENDING_LOG_LIMIT) pendingLogs.push(line);
    return;
  }
  send({ type: 'log', text: line });
}

/** 端口建立前攒下的诊断，见 [log]。 */
const pendingLogs = [];

/** 攒下的诊断条数上限（防注入失败被反复触发时无限增长）。 */
const PENDING_LOG_LIMIT = 50;

/** 建立与原生侧的常驻端口。 */
function connectNative() {
  try {
    nativePort = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    // 原生侧还没 setMessageDelegate（扩展装好但委托未挂）时会走到这里。
    scheduleReconnect();
    return;
  }

  nativePort.onMessage.addListener(onNativeMessage);

  nativePort.onDisconnect.addListener(() => {
    nativePort = null;
    // ⚠️ 断开后**必须重连**：页面刷新、扩展重载都会断一次，而断开期间
    //   原生推的快照全丢，页面侧的同步读取会一直读到初始空值。
    scheduleReconnect();
  });

  // 端口一通就报到：原生据此拿到端口引用，并把首份快照推过来。
  // 没有这一条，页面侧 `rootState()` / `bubbleState()` 永远读到空值 —— 且不报错。
  send({ type: 'hello' });

  // 把端口建立前攒下的诊断补发出去，见 [log]。
  for (const line of pendingLogs.splice(0)) send({ type: 'log', text: line });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = 0;
    connectNative();
  }, RECONNECT_DELAY_MS);
}

/** 原生 → 页面。 */
function onNativeMessage(msg) {
  if (!msg || typeof msg !== 'object') return;

  // 快照：更新本地缓存（页面侧的同步读取就靠它）。
  if (msg.type === 'snapshot') {
    if (typeof msg.root === 'string') snapshot.root = msg.root;
    if (typeof msg.bubble === 'string') snapshot.bubble = msg.bubble;
    if (typeof msg.appearance === 'string') snapshot.appearance = msg.appearance;
    if (typeof msg.pairingEnabled === 'boolean') snapshot.pairingEnabled = msg.pairingEnabled;
    if (typeof msg.logSummary === 'string') snapshot.logSummary = msg.logSummary;
    if (typeof msg.shizuku === 'string') snapshot.shizuku = msg.shizuku;
    return;
  }

  // 原生要求页面执行一段 JS，并把结果回给原生。
  if (msg.type === 'eval') {
    let result;
    try {
      // ⚠️ 求值必须在**页面世界**（`wrappedJSObject.eval`），否则拿不到页面脚本
      //   定义的 `window.__DSH_MOBILE_BACK__` 之类，会永远返回「桥不存在」。
      // ⚠️ 结果必须转成**原始类型**再跨 compartment 返回，对象会被拒。
      const value = window.wrappedJSObject.eval(String(msg.script));
      result = JSON.stringify(value === undefined ? null : value);
    } catch (e) {
      // 抛错时回一个可识别的形状：宿主只关心"是不是 true"，异常一律当未消费。
      result = JSON.stringify({ __dshError: String((e && e.message) || e) });
    }
    send({ type: 'evalResult', id: msg.id, result: result });
    return;
  }

  // 原生要求重装桥（页面刷新后隔离世界重建，页面世界的 DSHAndroid 会丢）。
  if (msg.type === 'installBridge') {
    installed = false;
    installBridge();
  }
}

/**
 * 在页面世界注入的那段脚本（字符串，由 `pageWindow.eval` 求值）。
 *
 * ⚠️ **必须是一段字符串**，不能是跨 compartment 传过去的函数对象 —— 见文件头
 *   规则 1。它跑在页面世界，所以 `console` / `HTMLAnchorElement` 都是页面自己
 *   那一份，包装才真的生效。
 *
 * 它做两件 GeckoView **没有回调**、而 system 版靠内核回调做的事：
 *
 * | 事 | system 版 | 这里 |
 * |---|---|---|
 * | console 转发到宿主日志 | `WebChromeClient.onConsoleMessage` | 包 `console.*` |
 * | 接管下载 | `setDownloadListener` | 包 `HTMLAnchorElement.prototype.click` |
 */
const PAGE_SHIM_SOURCE = `(function (post) {
  'use strict';

  // ⚠️ post 存在 window 上而不是闭包里：content script 重跑时（页面导航、
  //   扩展重载）会传进来**新的** post（闭包着新的原生端口），而 console 的包装
  //   已经装在页面世界、不会重装。把 post 挂在 window 上、包装函数每次读它，
  //   就能让旧的包装自动用上新端口 —— 否则页面刷新后 console 转发会静默失效
  //   （旧 post 指向已断的端口，send 会静默丢弃）。
  window.__DSH_SHIM_POST__ = post;

  function forward(level, args) {
    try {
      var p = window.__DSH_SHIM_POST__;
      if (typeof p !== 'function') return;
      var text = Array.prototype.map.call(args, function (a) {
        if (typeof a === 'string') return a;
        try { return JSON.stringify(a); } catch (e) { return String(a); }
      }).join(' ');
      p('console', [level + ': ' + text]);
    } catch (e) { /* 转发失败绝不影响页面本身 */ }
  }

  // ⚠️ 防重复包装：content script 每次页面导航都会重跑，而页面世界的 console /
  //   原型链在 SPA 内部跳转时并不重建（同一个 window 对象）。不防的话每导航一次
  //   就多包一层，一条 console 会被转发 N 次。
  if (window.__DSH_SHIM_INSTALLED__) return;
  window.__DSH_SHIM_INSTALLED__ = true;

  // ── console 转发 ────────────────────────────────────────────────────
  // ⚠️ 必须保留原函数并照常调用 —— 页面（React）可能依赖 console 的副作用，
  //   吞掉输出会让线上排查少一条线索。
  try {
    ['log', 'info', 'warn', 'error'].forEach(function (level) {
      var orig = console[level];
      if (typeof orig !== 'function') return;
      console[level] = function () {
        forward(level, arguments);
        return orig.apply(console, arguments);
      };
    });
  } catch (e) { /* console 被冻结等极端情况：跳过，不影响桥 */ }

  // ── 下载拦截 ────────────────────────────────────────────────────────
  // ⚠️ GeckoView **没有** setDownloadListener，而 DSH 的会话日志导出走的是
  //   dsh-session-log-export/lib/client.js 的 downloadUrl()：
  //
  //       const anchor = document.createElement("a");
  //       anchor.href = url; anchor.download = filename;
  //       anchor.click();
  //
  //   **那个 anchor 从不插入 DOM**，所以事件不会冒泡到 document，
  //   在 document 上做事件委托是**拦不到的**。唯一可靠的接缝是包住
  //   HTMLAnchorElement.prototype.click。
  //
  // ⚠️ 注释里**不能出现反引号**：本段整体是模板字符串，一个反引号就会提前
  //   终止它（这里踩过一次，症状是 content script 整体不执行、连 diag 都没有）。
  //   提到标识符一律用「」代替。
  //
  // ## 为什么是「页面世界自己 fetch + 分块回传」，而不是让内核导航
  //
  // 踩过两条路，都错，记下来免得再走：
  //
  // ① 「回报原生、由宿主自己发 HTTP」→ **必然 401**。会话导出接口是纯
  //    cookie 鉴权，而 GeckoView **没有任何读取 cookie 的 API**（StorageController
  //    只有 cookie 横幅那几个方法），cookie 存在内核自己的库里。
  //
  // ② 「用 GeckoSession.Loader 起一次导航，让内核自己带 cookie」→ **真机拿到
  //    403 forbidden，而且那个错误页把整个 GUI 覆盖掉了**。两点教训：
  //    引擎的准入检查（dsh-client-connection 的 isTrustedApiRequest）看的
  //    host / sec-fetch-site / origin 三项，**原生发起的导航与页面发起的 fetch
  //    不是同一组取值**；更致命的是**任何非 attachment 的响应都会被当成主文档
  //    渲染**，一次失败就把界面顶掉。
  //
  // ③ 正解：在**页面世界**用 fetch 拉 —— 这与 GUI 平时调 API 是**同一条通路、
  //    同一组请求头**，准入检查必然通过；而且响应是数据不是导航，失败最多是
  //    一次 fetch 报错，**永远不会覆盖界面**。cookie 由 fetch 默认的
  //    credentials 自动带上。
  //
  // ⚠️ 二进制经 native messaging 只能走 JSON，所以用 base64 分块。
  //
  // ⚠️ **每条分块都自带 url 与文件名，不另发一条「开始」消息**：单独的开场消息
  //   被内核的 messaging 校验拒过一次（logcat 里只有
  //   「Invalid event data property args」，原生收不到，而分块本身能通），
  //   结果就是分块到了却没有进行中的下载、全被丢弃。让元信息随第一条分块一起
  //   到达，这条链路上就没有「必须先收到某条消息」的隐含依赖了。
  var DOWNLOAD_CHUNK_BYTES = 65536;

  function handleDownload(url, filename) {
    var p = window.__DSH_SHIM_POST__;
    if (typeof p !== 'function') return;
    var name = String(filename || '');
    fetch(url, { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.arrayBuffer();
      })
      .then(function (buf) {
        var u8 = new Uint8Array(buf);
        for (var off = 0; off < u8.length; off += DOWNLOAD_CHUNK_BYTES) {
          var slice = u8.subarray(off, Math.min(off + DOWNLOAD_CHUNK_BYTES, u8.length));
          var s = '';
          for (var i = 0; i < slice.length; i++) s += String.fromCharCode(slice[i]);
          p('downloadChunk', [btoa(s), url, name]);
        }
        p('downloadEnd', [url, name]);
      })
      .catch(function (e) {
        p('downloadError', [url, String((e && e.message) || e)]);
      });
  }

  try {
    var proto = HTMLAnchorElement.prototype;
    var origClick = proto.click;
    proto.click = function () {
      try {
        var dl = this.getAttribute && this.getAttribute('download');
        var href = this.href || '';
        if (dl !== null && dl !== undefined && /^https?:/i.test(href)) {
          // ⚠️ 吞掉这次 click（**不**放行原生 click）：那个 anchor **从不插入
          //   DOM**，它自己导航既不可靠、又会把响应当主文档渲染成错误页。
          handleDownload(href, dl);
          return;
        }
      } catch (e) { /* 判定失败就照常走原生 click */ }
      return origClick.apply(this, arguments);
    };
  } catch (e) { /* 极端情况：跳过下载拦截，其余功能不受影响 */ }
})`;

/**
 * 在页面世界构建 `window.DSHAndroid` 的那段源码（字符串）。
 *
 * ## 为什么非得是一段字符串 + eval
 *
 * 试过两条更"自然"的路，都在真机上挂了，记下来免得再走：
 *
 * | 写法 | 结果 |
 * |---|---|
 * | `pageWindow.DSHAndroid = { ... }` | 页面世界拿到的是隔离世界对象的 Xray wrapper，读属性 **Permission denied** |
 * | `pageWindow.DSHAndroid = cloneInto({ ... }, pageWindow)` | **`Permission denied to pass a Function via structured clone`** —— cloneInto 走结构化克隆，而结构化克隆**不能传函数** |
 * | `pageWindow.eval(源码)(导出的函数…)` | ✅ |
 *
 * 关键认识：**函数只能经 `exportFunction` 跨边界**，不能经 `cloneInto`。
 * 所以对象必须**在页面世界构造**（这样它天然属于页面世界，无需克隆），
 * 而它要调用的那几个原生侧函数以参数形式传进去（它们是 exportFunction 的
 * 返回值，跨边界是合法的）。
 */
const BRIDGE_INSTALLER_SOURCE = `(function (post, getRoot, getBubble, getPairingEnabled, getAppearance, getLogSummary, getShizuku) {
  // ⚠️ **无条件覆盖**，不能写成「已有就跳过」。content script 重跑时传进来的
  //   post 是**新的**函数对象（闭包着新的原生端口），而旧 DSHAndroid 里的方法
  //   还指着**旧的** post → 旧端口（可能已断）。保留旧对象的结果是页面调了方法
  //   却发不出去，且不报错。重新赋一遍的代价只是几个函数对象。
  //
  // ⚠️ 这段源码本身是外层模板字符串的内容：**里面绝不能出现反引号**（会提前
  //   终止那个模板字符串）。所以注释里提到标识符一律用「」而不是 \` \`。
  window.DSHAndroid = {
    onStatus: function (json) { post('onStatus', [String(json)]); },
    onImeWillShow: function () { post('onImeWillShow', []); },
    startWirelessPairing: function () { post('startWirelessPairing', []); },
    cancelWirelessPairing: function () { post('cancelWirelessPairing', []); },
    pairingNotificationsEnabled: function () { return getPairingEnabled(); },
    onUiReady: function () { post('onUiReady', []); },
    reportWebError: function (m) { post('reportWebError', [String(m)]); },
    switchToTcpMode: function () { post('switchToTcpMode', []); },
    // ⋯ 菜单「刷新」的安全重载。⚠️ 页面侧绝不能自己 location.reload() ——
    //   整页重载不带启动时那条 token ⇒ 全站 401（见 PageBridge.reloadWebUi 的 KDoc）。
    reloadWebUi: function () { post('reloadWebUi', []); },
    bubbleState: function () { return getBubble(); },
    bubbleSetEnabled: function (enabled) { post('bubbleSetEnabled', [!!enabled]); },
    bubbleRequestOverlay: function () { post('bubbleRequestOverlay', []); },
    onTranscript: function (json) { post('onTranscript', [String(json)]); },
    // ⚠️ 这里**必须**乐观返回 true：native messaging 只能异步，而 system 版
    //   是同步返回 Boolean 的。为了两个 flavor 对插件**可观测行为一致**，
    //   这里返回「已投递」而不是「已应用」—— 返回值语义是"接受了吗"。
    //   真正的失败会经 log 通道进 logcat（原生侧 applyAppearance 里记日志）。
    bubbleApply: function (json) { post('bubbleApply', [String(json)]); return true; },
    bubbleAppearance: function () { return getAppearance(); },
    rootState: function () { return getRoot(); },
    rootRecheck: function () { post('rootRecheck', []); },
    // 日志与诊断。⚠️ 与 system 版**逐字对齐**（那边是 addJavascriptInterface 的
    //   同步方法）：「logSummary()」同步返回 JSON 文本，「openLogs()」拉起原生界面。
    //   这里同步读的是**快照缓存** —— 原生在 pushSnapshot 里一并推来。
    //
    //   ⚠️ 缓存是**旧值**：设置页在用户从日志界面返回时调本方法，而那一刻原生
    //     还没推新快照（缓存停在握手时那一份），用户会看到清空/上传前的旧数字。
    //     所以这里**顺带请求一次重推**；页面侧的延迟二次读拿到新值。
    logSummary: function () {
      post('logsRefresh', []);
      return getLogSummary();
    },
    openLogs: function () { post('openLogs', []); },
    // Shizuku（uid 2000 的第二条来源，见 ShizukuChannel）。
    //   ⚠️ 本段是**模板字符串**的内容，注释里一律用「」包住标识符 ——
    //     写反引号会提前终止模板，症状是整个 content script 不执行
    //     （见文件头那三个「静默炸掉整个文件」的注释陷阱）。
    //   「shizukuState()」同步读快照缓存（与 rootState 同一语义）；
    //   三个动作都是**异步投递**，返回值语义是「接受了吗」。
    shizukuState: function () { return getShizuku(); },
    shizukuRequestPermission: function () { post('shizukuRequestPermission', []); },
    shizukuOpenManager: function () { post('shizukuOpenManager', []); },
    shizukuOpenDownload: function () { post('shizukuOpenDownload', []); },
    // 原生上传兜底通道（见 system 版 NativeUploader 的类注释）。
    //   ⚠️ 四个方法都**乐观返回 true**：native messaging 只能异步，返回值送不
    //     回去。真正结果走「window.__dshNativeUploadDone」回调，polyfill 侧
    //     另有超时兜底 —— 所以这里返回 true 不会造成「静默卡住」。
    //   ⚠️ 本段是模板字符串内容，注释里用「」包住标识符，不要写反引号。
    uploadNativeBegin: function (taskId, url, headersJson) {
      post('uploadNativeBegin', [String(taskId), String(url), String(headersJson || '')]);
      return true;
    },
    uploadNativeWrite: function (taskId, base64) {
      post('uploadNativeWrite', [String(taskId), String(base64)]);
      return true;
    },
    uploadNativeFinish: function (taskId) {
      post('uploadNativeFinish', [String(taskId)]);
      return true;
    },
    uploadNativeAbort: function (taskId) {
      post('uploadNativeAbort', [String(taskId)]);
      return true;
    },
  };
})`;

/**
 * 一次性自检，把本文件依赖的几个特权 API 是否可用打进 logcat。
 *
 * ⚠️ **刻意保留**：这几个 API 都是 Firefox 内部的、**没有公开文档**的
 *   （`exportFunction` / `wrappedJSObject` 都不在 MDN 的 WebExtension 文档里，
 *   只对 privileged extension 开放）。哪天 GeckoView 收紧策略，症状会是
 *   「界面能开、设置页三块静默失效」这种最难查的样子 —— 有这一行就能一眼定位。
 */
function diag() {
  const parts = ['ef=' + typeof exportFunction];
  const pw = window.wrappedJSObject;
  parts.push('wjo=' + typeof pw);
  if (!pw) return parts.join(' ');
  try {
    parts.push('eval=' + JSON.stringify(pw.eval('1+1')));
  } catch (e) {
    parts.push('eval=ERR:' + ((e && e.message) || e));
  }
  try {
    pw.__DSH_DIAG__ = 'ok';
    parts.push('write=' + pw.__DSH_DIAG__);
  } catch (e) {
    parts.push('write=ERR:' + ((e && e.message) || e));
  }
  return parts.join(' ');
}

/**
 * 把桥装进**页面世界**。
 *
 * ⚠️ 目标作用域一律是 `window.wrappedJSObject`（页面世界的 window），**不是**
 *   隔离世界的 `window` —— 后者赋值页面完全看不到。
 */
function installBridge() {
  if (installed) return;

  const pageWindow = window.wrappedJSObject;
  if (!pageWindow) {
    // 极端情况：拿不到页面世界的 window（about: 页面等）。桥装不上，
    // 页面侧会走「无桥」降级分支 —— 必须留痕，否则表现为"莫名其妙不能用"。
    log('[dsh-bridge] 拿不到 wrappedJSObject，桥未安装');
    return;
  }
  installed = true;
  log('[dsh-bridge] diag: ' + diag());

  // 页面 → 原生 的动作通道（跨世界调用，参数是原始类型）。
  // ⚠️ 第二个参数是目标作用域，**不能省** —— 省了会抛
  //   "Function requires at least 2 arguments"（真机实测）。
  // ⚠️ `type` **必须**是 'invoke'：原生侧按 `type` 分派（见 GeckoWebHost
  //   .handleExtensionMessage）。漏了它消息不会报错，只是**静默丢弃** ——
  //   页面所有动作（含 onUiReady）全部不到原生。
  const post = exportFunction(function (call, args) {
    send({ type: 'invoke', call: String(call), args: args || [] });
  }, pageWindow);

  // 页面世界的 console / 下载接缝。用 eval 在页面世界里原生地装，见
  // PAGE_SHIM_SOURCE 的说明。
  try {
    pageWindow.eval(PAGE_SHIM_SOURCE)(post);
  } catch (e) {
    log('[dsh-bridge] 页面垫片注入失败（console 转发与下载拦截不可用）: ' +
      ((e && e.message) || e));
  }

  // 原生 → 页面 的同步读取通道。exportFunction 导出的函数从页面世界调用时
  // **同步返回原始类型**，这正是页面侧 `const raw = bridge.rootState()` 要的语义。
  const getRoot = exportFunction(function () { return String(snapshot.root); }, pageWindow);
  const getBubble = exportFunction(function () { return String(snapshot.bubble); }, pageWindow);
  const getPairingEnabled = exportFunction(function () { return !!snapshot.pairingEnabled; }, pageWindow);
  // 悬浮球外观配置也走同一套「原生推快照、页面同步读缓存」的语义。
  const getAppearance = exportFunction(function () { return String(snapshot.appearance); }, pageWindow);
  // 日志摘要同构：原生推快照、页面同步读缓存。设置页那一块靠它判断有没有桥。
  const getLogSummary = exportFunction(function () { return String(snapshot.logSummary); }, pageWindow);
  // Shizuku 状态同构：原生推快照、页面同步读缓存。
  const getShizuku = exportFunction(function () { return String(snapshot.shizuku); }, pageWindow);

  try {
    // 在页面世界现造安装器，再把导出函数喂进去 —— 对象因此在页面世界出生，
    // 不需要（也不能）克隆。见 BRIDGE_INSTALLER_SOURCE 的说明。
    pageWindow.eval(BRIDGE_INSTALLER_SOURCE)(
      post, getRoot, getBubble, getPairingEnabled, getAppearance, getLogSummary, getShizuku);
    log('[dsh-bridge] 桥已装入页面世界: ' + typeof pageWindow.DSHAndroid);
  } catch (e) {
    // 桥装不上 ⇒ 设置页的气泡开关 / root 状态 / 无线调试配对三块静默失效。
    // 这是**功能性**失败，必须明确记日志。
    log('[dsh-bridge] 桥安装失败: ' + ((e && e.message) || e));
  }
}

// ⚠️ 与 GeckoWebHost.NATIVE_APP 必须一致（`setMessageDelegate` 的 nativeApp 参数）。
const NATIVE_APP = 'browser';

/** 端口断开后的重连间隔（ms）。 */
const RECONNECT_DELAY_MS = 1000;

// `run_at: document_start` ⇒ 这里 window 已存在，可以直接装。
installBridge();
connectNative();
