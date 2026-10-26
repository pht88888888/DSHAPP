/*
 * DSH UI 探针 —— 跑在**页面世界**里的一次性装填器，让原生/模型能精确地看和点界面。
 *
 * ## 为什么需要它
 *
 * 这个 App 的界面不是自绘的：它是官方 DSH Web GUI 跑在 WebView 里，按钮全是
 * React 渲染出来的 DOM。模型（跑在 Node 进程里）既看不到 DOM，也没有任何
 * 途径「点一下那个按钮」—— 于是「AI 自己测自己的 App」这件事在能力上是空的。
 *
 * 本文件补的就是这一段：把「枚举可交互元素 + 精确点击 + 输入 + 滚动 + 读文本」
 * 这套能力装进页面世界，挂成 `window.__DSH_UI_PROBE__`，由原生经
 * `WebHost.evaluateJavascript` 调用。
 *
 * ## 为什么是「每次调用都自带装填逻辑」而不是注入到 index.html
 *
 * ① **不碰用户的页面文件**。注入 `dist/index.html` 意味着改引擎的解包产物，
 *    而那份产物是按版本戳决定要不要重解包的 —— 加一段自己的脚本进去会让
 *    「引擎升级」与「探针升级」两个生命周期纠缠在一起。
 * ② **SPA 内部导航不重建 window**，所以探针装一次就一直在；只有整页导航才丢。
 *    每次调用都检查一遍版本（`__v`），丢了就自动补装，代价是稳态下多几十字节。
 * ③ 本文件是**函数表达式源码**，原生侧拼成 `(<本文件>)(<cmd JSON>)` 执行。
 *    这样 `cmd` 经 `JSON.stringify` 进来，没有任何字符串注入风险。
 *
 * ## ⚠️ 三条必须遵守的约束
 *
 * ① **不能用 ES6+ 的新语法**（可选链 `?.`、`??`、class 字段等）。本 App 要兼容
 *    降级到 chrome74 的老 WebView（见 AGENTS.md ⑯），而本文件**不经过**任何
 *    转译 —— 它是运行时直接 eval 的源码。用 `var` + `function` 最稳。
 * ② **必须返回一个可 JSON 序列化的值**。gecko 侧是
 *    `JSON.stringify(pageWindow.eval(script))`，system 侧是 WebView 自己序列化；
 *    返回 DOM 节点、函数、循环引用都会让整次调用废掉。
 * ③ **绝不能改变页面的可见外观**。不插样式表、不设元素属性、不写 document。
 *    所有改动都只在 `window.__DSH_UI_PROBE__` 这个我们自己造的对象上。
 *
 * ## 坐标系（这是「精确」二字的落点）
 *
 * `getBoundingClientRect()` 给的是 **CSS 像素的视口坐标**。要落到屏幕物理像素
 * 上还需两步换算，全部由原生侧完成（页面里拿不到 WebView 在屏幕上的位置）：
 *
 * ```
 * scale    = webView物理宽度 / 页面视口CSS宽度
 * 屏幕物理X = webView屏幕X + cssX * scale
 * ```
 *
 * 所以探针只负责返回 **CSS 坐标 + 视口尺寸**，换算交给原生 —— 那里才有
 * `getLocationOnScreen()`。这样探针不需要知道自己在什么容器里，两个 flavor 通吃。
 *
 * @param {object} CMD 原生命令，形如 `{action: 'dump', ...}`。由原生 JSON 编码。
 * @returns {object} 结果，必须可 JSON 序列化。
 */
function (CMD) {
  'use strict';

  /** 探针版本。**改了本文件的语义就要 +1** —— 页面上的旧探针靠它判断要不要换。 */
  var VERSION = 7;

  /** 结果里单个元素的文本/类名截断长度（防超大 JSON 撑爆 IPC）。 */
  var MAX_TEXT = 120;
  var MAX_CLASS = 80;
  var MAX_ELEMENTS = 300;

  /** `dump` 的默认条数上限。 */
  var DEFAULT_LIMIT = 60;

  // ────────────────────────────────────────────────────────────────────
  // 工具函数
  // ────────────────────────────────────────────────────────────────────

  /** 切成真数组（NodeList 没有 map/filter）。 */
  function toArray(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(list[i]);
    return out;
  }

  function trunc(s, n) {
    s = String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n) + '…' : s;
  }

  /** `CSS.escape` 的兜底（老 WebView 上可能没有）。 */
  function esc(s) {
    if (window.CSS && typeof CSS.escape === 'function') return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) {
      return '\\' + c.charCodeAt(0).toString(16) + ' ';
    });
  }

  /**
   * 元素**自身**是否可见（有尺寸、没被隐藏、能接指针事件）。
   *
   * ⚠️ 只看 `getBoundingClientRect()` 的尺寸是不够的：React 里大量用了
   *   `visibility:hidden` / `opacity:0` 做过渡，这类元素有尺寸但看不见，
   *   而**看不见的元素点了不会有任何反应** —— 那正是「AI 说点了但没生效」的
   *   头号原因。所以尺寸、visibility、display、opacity 四样都查。
   *
   * ⚠️ **本函数只查元素自身，不查「有没有别的东西盖在它上面」** —— 后者由
   *   `blocker()` 负责。两者必须分开：一个元素完全可能「自己看着很正常，
   *   但被全屏弹窗盖住」，那种情况下它 `isVisible` 为 true 而真机上点不动。
   */
  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    var st = window.getComputedStyle(el);
    if (!st) return false;
    if (st.display === 'none' || st.visibility === 'hidden' || st.visibility === 'collapse') return false;
    if (parseFloat(st.opacity) === 0) return false;
    if (st.pointerEvents === 'none') return false;
    return true;
  }

  /**
   * 取某个视口坐标上的**最上层**元素。
   *
   * ⚠️ `elementFromPoint` 对**视口外**的坐标返回 `null`，而那不是「被遮挡」，
   *   是「没显示在屏幕上」—— 两者必须分开，否则列表里滚出屏幕的元素会被
   *   全部误报成遮挡，把真正的问题淹掉。
   *
   * ⚠️ `pointer-events:none` 的元素会被内核自动跳过，这正是我们要的：
   *   它们不接事件，也就挡不住点击。
   */
  function topAt(x, y) {
    try {
      if (x < 0 || y < 0) return null;
      if (x > (window.innerWidth || 0) || y > (window.innerHeight || 0)) return null;
      return document.elementFromPoint(x, y);
    } catch (e) {
      return null;
    }
  }

  /** 两个元素是否有祖先/后代关系（这种「压住」是正常的，不算遮挡）。 */
  function related(a, b) {
    if (!a || !b) return false;
    if (a === b) return true;
    return !!((a.contains && a.contains(b)) || (b.contains && b.contains(a)));
  }

  /**
   * 元素是否**被别的东西盖住**（真机手指点不到它）。返回遮挡者，没被挡则 null。
   *
   * ⚠️ 这是本探针最重要的一条判据，也是第一版最大的缺口：只查元素自身样式
   *   会把「被全屏弹窗盖住的按钮」报成可用 —— 它有尺寸、`opacity` 是 1、
   *   `visibility` 是 visible，**看上去完全正常**，但真机上点它只会点到底下
   *   那层遮罩。模型于是挨个点、界面纹丝不动、还拿不到任何提示，
   *   只能猜「是不是选择器写错了」。
   *
   *   实测的典型案例：官方前端首启会弹「预览版说明」对话框（`z-index:1000`
   *   加一层覆盖全屏的 `_mask_`），此时探针会枚举出 16 个「可见」按钮，
   *   而它们**一个都点不动**；那个弹窗的按钮文案是「继续」，不认识它就关不掉。
   *
   * 判据与 `clickEl` 里的 `onTarget` 必须同源 —— 否则「dump 说能用」与
   * 「tap 说没点到」会互相矛盾，模型无从判断该信哪个。
   */
  function blocker(el) {
    if (!el || !el.getBoundingClientRect) return null;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return null;
    var top = topAt(r.left + r.width / 2, r.top + r.height / 2);
    if (!top || related(top, el)) return null;
    return top;
  }

  /** 元素是否在视口内（`dump` 里标记用，不参与过滤）。 */
  function inViewport(r) {
    return r.bottom > 0 && r.right > 0 &&
      r.top < (window.innerHeight || 0) && r.left < (window.innerWidth || 0);
  }

  /**
   * 元素的**中心点**是否落在视口内 —— 这是「真手指能不能碰到它」的判据。
   *
   * ⚠️ 与 [inViewport] **不是一回事，别合并**：那个问的是「有没有任意一部分露在
   *   视口里」，用于标记「滚出屏幕了」；这个问的是「中心点能不能被
   *   `elementFromPoint` 命中」。一个元素可以**一半露在屏幕外**（`inViewport`
   *   为 true）而中心点已经在视口外，那种情况下点击必然落空。
   */
  function inViewportPoint(r) {
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;
    return x >= 0 && y >= 0 && x < vw && y < vh;
  }

  /**
   * 元素中心点是否在**视口外**（真手指无处可点）。
   *
   * ⚠️ 这是与遮挡**并列**的第二类「点不到」，此前完全没有判据 —— 探针把屏幕外的
   *   元素报成 `onTarget:true`、`clickableOnly` 也照样列出来，`wait clickable:true`
   *   还报「等到了」。实测（抽屉收起后侧栏滑出视口，`left` 为负）表现为
   *   「AI 说点过了，界面纹丝不动」，与遮挡是同一类错觉，**但根因不同**：
   *   一个是被别的东西吃掉事件，一个是**根本没有任何坐标可点**。
   *
   *   `clickEl` 里已经调了 `scrollIntoView`，可它对 `position:fixed` 的元素无效
   *   （fixed 元素不参与滚动），所以「先滚一下就好了」这条路在这里不成立，
   *   必须如实报出来。
   */
  function offscreen(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    return !inViewportPoint(r);
  }

  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return {
      x: Math.round(r.left * 100) / 100,
      y: Math.round(r.top * 100) / 100,
      w: Math.round(r.width * 100) / 100,
      h: Math.round(r.height * 100) / 100
    };
  }

  /** 视口信息 —— 原生据此把 CSS 坐标换算成屏幕物理坐标。 */
  function viewportInfo() {
    var de = document.documentElement;
    return {
      w: window.innerWidth || (de && de.clientWidth) || 0,
      h: window.innerHeight || (de && de.clientHeight) || 0,
      dpr: window.devicePixelRatio || 1,
      scrollX: Math.round(window.pageXOffset || 0),
      scrollY: Math.round(window.pageYOffset || 0),
      docW: de ? de.scrollWidth : 0,
      docH: de ? de.scrollHeight : 0
    };
  }

  /**
   * 给元素生成一个**稳定的 CSS 选择器**（可回传给 `tap` 用）。
   *
   * 优先 `#id`（最稳），退化成 `tag:nth-of-type(...)` 逐级路径。
   */
  function cssPath(el) {
    if (!el || el.nodeType !== 1) return '';
    // 有 id 且全局唯一 —— 最优解
    if (el.id) {
      try {
        if (document.querySelectorAll('#' + esc(el.id)).length === 1) return '#' + esc(el.id);
      } catch (e) { /* 非法 id，忽略 */ }
    }
    var parts = [];
    var cur = el;
    var depth = 0;
    while (cur && cur.nodeType === 1 && cur !== document.documentElement && depth < 12) {
      var tag = cur.tagName.toLowerCase();
      var parent = cur.parentElement;
      if (parent) {
        var same = [];
        for (var i = 0; i < parent.children.length; i++) {
          if (parent.children[i].tagName === cur.tagName) same.push(parent.children[i]);
        }
        if (same.length > 1) tag += ':nth-of-type(' + (same.indexOf(cur) + 1) + ')';
      }
      if (cur.id) {
        parts.unshift('#' + esc(cur.id));
        break;
      }
      parts.unshift(tag);
      cur = parent;
      depth++;
    }
    return parts.join(' > ');
  }

  /**
   * 元素的**可读标签** —— 模型靠它认出「这是那个按钮」。
   *
   * 优先级：`aria-label` > `title` > 输入控件的 placeholder/value > 文本内容。
   * 这个顺序是有理由的：图标按钮（DSH 界面上有很多）没有文本，只有 aria-label；
   * 反过来，一个容器按钮的 innerText 可能包含整段子内容，用它会认错。
   */
  function labelOf(el) {
    var a = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'));
    if (a && String(a).trim()) return trunc(a, MAX_TEXT);
    var tag = el.tagName;
    if (tag === 'INPUT') {
      var t = el.type;
      if (t === 'checkbox' || t === 'radio' || t === 'range') return '';
      return trunc(el.getAttribute('placeholder') || el.value || '', MAX_TEXT);
    }
    if (tag === 'TEXTAREA') return trunc(el.getAttribute('placeholder') || el.value || '', MAX_TEXT);
    if (el.isContentEditable) return trunc(el.getAttribute('placeholder') || el.innerText || '', MAX_TEXT);
    return trunc(el.innerText || el.textContent || '', MAX_TEXT);
  }

  /** 元素的 `role`（显式写的优先，否则从标签推断）。 */
  function roleOf(el) {
    var r = el.getAttribute && el.getAttribute('role');
    if (r) return r;
    var tag = el.tagName;
    if (tag === 'BUTTON') return 'button';
    if (tag === 'A') return 'link';
    if (tag === 'INPUT') {
      var t = (el.type || 'text').toLowerCase();
      if (t === 'checkbox') return 'checkbox';
      if (t === 'radio') return 'radio';
      if (t === 'submit' || t === 'button') return 'button';
      return 'textbox';
    }
    if (tag === 'TEXTAREA') return 'textbox';
    if (tag === 'SELECT') return 'combobox';
    return '';
  }

  function testIdOf(el) {
    if (!el.getAttribute) return '';
    return el.getAttribute('data-testid') || el.getAttribute('data-test-id') ||
      el.getAttribute('data-test') || '';
  }

  /**
   * 元素的可点击性权重 —— `dump` 排序用。
   *
   * 权重高的排前面，模型就不用在一堆装饰性 div 里翻找真正的按钮。
   */
  function score(el) {
    var tag = el.tagName;
    var s = 0;
    if (tag === 'BUTTON') s += 100;
    if (tag === 'A' && el.getAttribute('href')) s += 90;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') s += 85;
    if (el.isContentEditable) s += 85;
    var role = el.getAttribute && el.getAttribute('role');
    if (role === 'button' || role === 'menuitem' || role === 'tab' || role === 'option') s += 70;
    if (testIdOf(el)) s += 40;
    if (el.getAttribute && el.getAttribute('aria-label')) s += 25;
    if (labelOf(el)) s += 12;
    return s;
  }

  /**
   * 「可交互」的候选选择器。
   *
   * ⚠️ **不能只查 `button`/`a`**：DSH 的界面是 React，大量按钮是带
   *   `role="button"` 或 `tabindex` 的 div。漏掉它们等于漏掉半个界面。
   */
  var INTERACTIVE = [
    'button',
    'a[href]',
    'input:not([type="hidden"])',
    'textarea',
    'select',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
    '[role="menuitemcheckbox"]',
    '[role="tab"]',
    '[role="option"]',
    '[role="checkbox"]',
    '[role="radio"]',
    '[role="switch"]',
    '[contenteditable="true"]',
    '[tabindex]:not([tabindex="-1"])',
    'summary',
    'label[for]'
  ].join(',');

  /**
   * 把一个元素描述成可回传的普通对象。
   *
   * ⚠️ 这里**只放原始类型** —— 返回 DOM 节点会让 JSON 序列化整个废掉。
   *
   * ⚠️ `covered` / `blockedBy` 是**必须**带上的：没有它们，调用方拿到的是一份
   *   「看着都能点」的清单，而真机上可能一个都点不动（见 `blocker()`）。
   */
  function describe(el, index) {
    var r = el.getBoundingClientRect();
    var b = blocker(el);
    var off = !inViewportPoint(r);
    return {
      ref: 'e' + index,
      sel: cssPath(el),
      tag: el.tagName.toLowerCase(),
      role: roleOf(el),
      text: labelOf(el),
      testid: testIdOf(el),
      cls: trunc(el.getAttribute && el.getAttribute('class') || '', MAX_CLASS),
      rect: rectOf(el),
      center: {
        x: Math.round((r.left + r.width / 2) * 100) / 100,
        y: Math.round((r.top + r.height / 2) * 100) / 100
      },
      inViewport: inViewport(r),
      // ⚠️ 与上面的 `inViewport` **语义不同，两个都要留着**：
      //   `inViewport` = 「有没有任意一部分露在屏幕里」（滚动列表标记用）；
      //   `offscreen`  = 「中心点在屏幕外 ⇒ 真手指点不到」（能不能点的判据）。
      //   合并成一个字段会让「滚出屏幕的列表项」与「滑出视口的侧栏」混为一谈。
      offscreen: off,
      // 「盖住了」与「盖它的是谁」成对出现：只报 true 而不报是谁，模型还得再查一轮。
      covered: !!b,
      blockedBy: b ? {
        sel: cssPath(b),
        tag: b.tagName.toLowerCase(),
        cls: trunc(b.getAttribute && b.getAttribute('class') || '', MAX_CLASS),
        text: labelOf(b)
      } : null,
      enabled: !(el.disabled === true || (el.getAttribute && el.getAttribute('aria-disabled') === 'true')),
      checked: typeof el.checked === 'boolean' ? el.checked : null,
      value: typeof el.value === 'string' ? trunc(el.value, MAX_TEXT) : null
    };
  }

  /** `dump` 用的候选收集（去重 + 可见性过滤 + 遮挡标注 + 排序 + 截断）。 */
  function collect(opts) {
    opts = opts || {};
    var limit = typeof opts.limit === 'number' && opts.limit > 0 ? Math.min(opts.limit, MAX_ELEMENTS) : DEFAULT_LIMIT;
    var needle = (opts.filter == null ? '' : String(opts.filter)).toLowerCase();
    var includeHidden = opts.includeHidden === true;
    // 「只要现在真能点的」—— 模型在弹窗挡着时最需要的开关
    var clickableOnly = opts.clickableOnly === true;

    var found = toArray(document.querySelectorAll(INTERACTIVE));
    var seen = [];
    var out = [];
    var blocked = 0;
    var offscreenCount = 0;

    for (var i = 0; i < found.length; i++) {
      var el = found[i];
      if (seen.indexOf(el) >= 0) continue;
      seen.push(el);
      if (!includeHidden && !isVisible(el)) continue;

      var d = describe(el, out.length);
      if (d.covered) blocked++;
      if (d.offscreen) offscreenCount++;
      // ⚠️ 「此刻真能点」= 既没被盖住、**中心点也在屏幕内**。只排除 `covered`
      //   会漏掉第二类点不到：元素滑到视口外（抽屉收起后的侧栏就是这样），
      //   它在屏幕上根本没有可点的坐标。`overlay` 的 `clickable` 用的正是
      //   这个列表，漏排会让模型去点一个「不在屏幕上的按钮」。
      if (clickableOnly && (d.covered || d.offscreen)) continue;
      if (needle) {
        var hay = (d.text + ' ' + d.testid + ' ' + d.cls + ' ' + d.sel + ' ' + d.role).toLowerCase();
        if (hay.indexOf(needle) < 0) continue;
      }
      d._score = score(el) + (d.covered || d.offscreen ? 0 : 1000); // 真能点的排前面
      out.push(d);
      if (out.length >= MAX_ELEMENTS) break;
    }

    // 可点击性高的排前面（截图/枚举时更符合直觉）
    out.sort(function (a, b) { return b._score - a._score; });
    var trimmed = out.slice(0, limit);
    for (var j = 0; j < trimmed.length; j++) delete trimmed[j]._score;
    return {
      matched: out.length, elements: trimmed, blocked: blocked,
      offscreen: offscreenCount, scanned: seen.length
    };
  }

  /**
   * 从 `el` 向上找「覆盖视口大部分区域」的那层壳。
   *
   * ⚠️ 遮挡层几乎总是「一个覆盖全屏的容器 + 里面的实际内容」。采样点命中的
   *   往往是**内容**（弹窗里的一段文字、一个按钮），它自己的 rect 很小 ——
   *   直接拿命中元素判面积会得出「没有遮挡」，而实际上全屏都被盖住了。
   *   往上找到那层壳，才能正确回答「现在谁盖着屏幕」。
   */
  function fullScreenAncestor(el) {
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;
    if (vw <= 0 || vh <= 0) return null;
    var cur = el;
    while (cur && cur !== document.body && cur !== document.documentElement) {
      var r = cur.getBoundingClientRect();
      if (r.width * r.height >= vw * vh * 0.5) {
        var st = window.getComputedStyle(cur);
        if (st && (st.position === 'fixed' || st.position === 'absolute')) return cur;
      }
      cur = cur.parentElement;
    }
    return null;
  }

  /**
   * 全屏层**内部**有多少个可见、可交互的元素。
   *
   * ⚠️ 这是区分「遮挡层」与「当前屏幕」的唯一判据，实测数据：
   *
   *   | 全屏层 | 内部可点数 | 是什么 |
   *   |---|---|---|
   *   | `_mask_o6lrb_18`（首启弹窗的遮罩） | **0** | 空装饰层，按钮是它的**兄弟** |
   *   | `wCInkW_panel`（设置面板） | **8** | 应用自己的全屏面板 |
   *   | `OUqwTW_panel`（右侧边栏） | **7** | 同上 |
   *
   *   官方弹窗的结构是 `_root_ > [_mask_（全屏空遮罩）, _dialog_（真内容）]` ——
   *   遮罩**自己什么都不装**，所以「里面一个能点的都没有」正是它的指纹。
   */
  function interactiveInside(layer) {
    if (!layer) return 0;
    var found = toArray(document.querySelectorAll(INTERACTIVE));
    var n = 0;
    for (var i = 0; i < found.length; i++) {
      var el = found[i];
      if (el === layer) continue;
      if (!layer.contains || !layer.contains(el)) continue;
      if (!isVisible(el)) continue;
      n++;
    }
    return n;
  }

  /**
   * 当前有没有**全屏级别的遮挡层**？有的话它是谁？
   *
   * ⚠️ 这是「现在这个界面能不能操作」的总开关。有全屏弹窗时，底下所有按钮都
   *   点不动，而它们各自的 `covered` 会指向同一个遮罩 —— 把遮罩单独报出来，
   *   模型一眼就知道「先关掉它」，而不是对着十几条 `covered:true` 发愣。
   *
   * ⚠️ **必须多点采样**。第一版只取视口中心一个点，结果命中弹窗里的文字，
   *   `fullScreenAncestor` 还没加时更是直接判成「没有遮挡」—— 于是出现了
   *   `overlay` 说「没有遮挡层」而同一时刻 `dump` 说「15 个元素被遮挡」的
   *   自相矛盾。两个字段对同一件事给出相反答案，比缺字段更糟。
   *
   * ⚠️ **必须区分「空遮罩」与「应用自己的全屏面板」**，否则会给出**相反的**
   *   处置建议。设置面板、右侧边栏、会话抽屉都是覆盖全屏的 `position:fixed`
   *   容器，一样会被采样点命中；第一版把它们全报成「遮挡层」，`dump` 于是
   *   打出一句「先关掉它（通常是弹窗里的某个按钮）再操作」—— 模型照着去点
   *   「返回」，于是**永远进不了设置**（实测：右侧边栏那一屏被这句误导后，
   *   脚本点开「工作区文件」又点「关闭」，绕了两圈才回到原位）。
   *
   *   两者在 DOM 上完全同构（都是全屏 fixed 容器），差别只有一个：**里面装
   *   不装当前屏幕的按钮**。所以用 `interactiveInside` 分：
   *     - `inside === 0` → `kind:'mask'`，是空装饰层，底下全点不动，必须先关掉；
   *     - `inside > 0`  → `kind:'panel'`，它就是**当前屏幕**，点它里面的按钮即可，
   *                       底下那层点不动是正常的，不是故障。
   */
  function overlay() {
    var vw = window.innerWidth || 0;
    var vh = window.innerHeight || 0;
    if (vw <= 0 || vh <= 0) return null;

    // 中心 + 四角内缩 + 四边中点，共 9 点：既能覆盖全屏层，又能避开单点落在
    // 弹窗内容（或页面上某个恰好很大的普通元素）上的偶然。
    var pts = [
      [0.5, 0.5], [0.15, 0.15], [0.85, 0.15], [0.15, 0.85], [0.85, 0.85],
      [0.5, 0.06], [0.5, 0.94], [0.06, 0.5], [0.94, 0.5]
    ];

    var tally = [];
    for (var i = 0; i < pts.length; i++) {
      var hit = topAt(Math.round(vw * pts[i][0]), Math.round(vh * pts[i][1]));
      if (!hit || hit === document.body || hit === document.documentElement) continue;
      var layer = fullScreenAncestor(hit);
      if (!layer) continue;
      var at = -1;
      for (var j = 0; j < tally.length; j++) if (tally[j].el === layer) { at = j; break; }
      if (at >= 0) tally[at].n++;
      else tally.push({ el: layer, n: 1 });
    }
    if (tally.length === 0) return null;

    tally.sort(function (a, b) { return b.n - a.n; });

    // ⚠️ 逐名次往下找，**不要**只看第一名。两类全屏层可能同时存在
    //    （弹窗盖在设置面板上），而它们的处置办法相反。判据：
    //      - 只在**多数**采样点都被它挡住时才认（少数点命中说明那只是页面上
    //        某个恰好很大的普通元素，不是「盖住了整个界面」）；
    //      - 且必须**里面一个能点的都没有** —— 装着自己按钮的全屏层是**当前
    //        屏幕**（设置面板 / 右侧边栏 / 抽屉），对它说「先关掉它」会把模型
    //        引到「返回」上去，永远进不了那一屏。详见 interactiveInside 注释。
    for (var k = 0; k < tally.length; k++) {
      if (tally[k].n < 5) break; // 已排序，后面的更少
      var el = tally[k].el;
      var inside = interactiveInside(el);
      var info = {
        sel: cssPath(el),
        tag: el.tagName.toLowerCase(),
        cls: trunc(el.getAttribute && el.getAttribute('class') || '', MAX_CLASS),
        text: labelOf(el),
        rect: rectOf(el),
        hits: tally[k].n,
        samples: pts.length,
        inside: inside
      };
      // 里面装着按钮 ⇒ 它是**当前屏幕**，不是遮挡。照样报出来（`kind:'panel'`），
      // 但调用方据此把措辞从「先关掉它」换成「这就是你现在的屏幕」。
      if (inside > 0) return { kind: 'panel', layer: info };
      return { kind: 'mask', layer: info };
    }
    return null;
  }

  // ────────────────────────────────────────────────────────────────────
  // 定位：把「请求」翻译成一个 DOM 元素
  // ────────────────────────────────────────────────────────────────────

  /** 按 CSS 选择器找（容错：非法选择器不抛，返回 null）。 */
  function bySel(sel) {
    if (!sel) return null;
    try {
      return document.querySelector(String(sel));
    } catch (e) {
      return null;
    }
  }

  /**
   * 按**文本**找可交互元素。
   *
   * ⚠️ 匹配的是 [labelOf] 的结果，且**优先精确相等**：DSH 界面上「发送」这种
   *   文本可能同时出现在按钮和它的提示里，纯子串匹配会认错。所以分两轮：
   *   先找完全相等的，找不到再退回子串包含。
   */
  function byText(text, exact) {
    if (!text) return null;
    var want = String(text).trim().toLowerCase();
    var found = toArray(document.querySelectorAll(INTERACTIVE));
    var partial = null;
    for (var i = 0; i < found.length; i++) {
      var el = found[i];
      if (!isVisible(el)) continue;
      var label = labelOf(el).toLowerCase();
      if (!label) continue;
      if (label === want) return el;
      if (!exact && partial === null && label.indexOf(want) >= 0) partial = el;
    }
    return exact ? null : partial;
  }

  /**
   * 按**稳定前缀**找可交互元素 —— 「文案里嵌着会变的值」那类元素的唯一抓手。
   *
   * ⚠️ 存在的理由（真机实测的缺陷，不是锦上添花）：
   *
   * DSH 界面上有一类元素，文案是「**固定标签 + 当前值**」拼出来的：
   *
   *     模型 agnes-3.0-flash
   *     推理等级 Default
   *     访问模式，当前：完全权限
   *     选择模型，当前 agnes-3.0-flash，推理等级 Default
   *
   * 它们**没有** `aria-label`（实测这几个都报 `text`），所以只能按文案找。
   * 而按文案找的两条路都用不了：
   *   · `text` 精确 —— 值一变整串就变（用户换个模型就永不命中）；
   *   · `text` 子串 —— 「模型」会命中每屏都在的那句长文案（等于没判）。
   *
   * 唯一稳定的东西是**前缀**。所以这里按前缀匹配。
   *
   * ⚠️⚠️ **`want` 不能 `trim()`** —— 这是本函数与前两个定位函数的**唯一但要害**
   *    的差别，删了就退化成子串匹配。
   *    反例实测：判据想表达「以『模型 』开头」以区分
   *        「模型 agnes-3.0-flash」（面板里那行）
   *        「选择模型，当前 agnes-3.0-flash，…」（每屏都有的触发按钮）
   *    靠的就是那个**尾空格**。`trim()` 会把它吃掉，两条就都命中了。
   *    （`labelOf` 的结果本身是 `trunc()` 过的、首尾已无空白，所以只需不动 `want`。）
   */
  function byPrefix(text) {
    if (text === undefined || text === null || text === '') return null;
    var want = String(text).toLowerCase();
    var found = toArray(document.querySelectorAll(INTERACTIVE));
    for (var i = 0; i < found.length; i++) {
      var el = found[i];
      if (!isVisible(el)) continue;
      var label = labelOf(el).toLowerCase();
      if (label && label.indexOf(want) === 0) return el;
    }
    return null;
  }

  /**
   * 统一的元素定位。**顺序即优先级**：
   *
   * ① `ref`/`sel` —— 精确（`ref` 是上一次 `dump` 给的，见下面的解析）
   * ② `testid` —— 开发留的锚点，最稳
   * ③ `text` —— 人话，最常用
   * ④ `aria` —— 图标按钮的唯一抓手
   *
   * ⚠️ `ref` 不能直接当选择器用：它是「上一次 dump 里的第 N 个」，两次调用之间
   *   页面可能已经重渲染。所以这里**不用 ref 定位**，只在返回里给出 `sel` 让
   *   调用方回传。硬用 ref 会产生「点到了完全不相干的元素」这种最难查的 bug。
   */
  function locate(spec) {
    spec = spec || {};
    var el = null;
    if (spec.sel) el = bySel(spec.sel);
    if (!el && spec.testid) {
      el = bySel('[data-testid="' + spec.testid + '"]') || bySel('[data-test-id="' + spec.testid + '"]');
    }
    if (!el && spec.aria) {
      // ⚠️ **必须同时查 `title`**，与 [labelOf] 的取值口径一致。
      //    `labelOf` 取的是 `aria-label || title`，dump 把它填进 `aria` 字段；
      //    若这里只查 `[aria-label]`，那么「只有 title、没有 aria-label」的按钮
      //    会被 dump 报出一个 `aria`，模型照着回传却**定位不到** ——
      //    症状是「找不到要点击的元素」，而那个按钮明明就在 dump 列表里。
      //    真机上 DSH 界面大量图标按钮只有 `title`，所以这条回落是必需的。
      var want = String(spec.aria);
      el = bySel('[aria-label="' + want.replace(/"/g, '\\"') + '"]') ||
        bySel('[title="' + want.replace(/"/g, '\\"') + '"]');
    }
    if (!el && spec.text) el = byText(spec.text, spec.exact === true);
    // ⚠️ `prefix` 与 `text` 是**并列的两个入口**，不是包含关系 —— 差别在
    //    「子串」与「以它开头」，以及 `prefix` **不吃尾空格**（`text` 会 trim）。
    //    见 [byPrefix] 的注释：就是那个尾空格让「模型 agnes-3.0-flash」能与
    //    每屏都有的「选择模型，当前 …」区分开。
    //
    //    位置说明：实际排在 `text` **之后**。两者语义上有重叠（前缀必是子串），
    //    但 `prefix` 更严格 —— 理论上该先试严格的。这里不调换，是因为
    //    **判据（`expect`）每次只给一个键**，两个分支不会同时命中，
    //    顺序对结果没有影响；调换反而要重跑一遍全部真机回归。
    //    （原先此处注释写成「排在 text 之前」，与代码不符，已改正。）
    if (!el && spec.prefix !== undefined) el = byPrefix(spec.prefix);
    // ⚠️ `role` 是**最后**的兜底，不是首选。它存在的理由是官方 composer：
    //   那个输入框是个 `contenteditable` 的 div，`text` 取到的是**占位符**
    //   （「发消息或创建任务, / 调用指令, @ 文件或对话」），而占位符会随文案
    //   改版而变 —— 实测就因为文案变了导致 `{action:'type', text:'描述你想要构建的内容'}`
    //   报「找不到输入元素」。`role` 是从 `role="textbox"` 读的，比文案稳。
    //
    //   放在最后是因为它最**不精确**：一屏里可能有多个 `textbox`（搜索框 + composer），
    //   所以它能用，但不如 `sel`/`aria` 精确 —— 文档里也按这个顺序推荐。
    if (!el && spec.role) {
      var wantRole = String(spec.role);
      var cands = document.querySelectorAll(INTERACTIVE);
      for (var ri = 0; ri < cands.length; ri++) {
        if (roleOf(cands[ri]) === wantRole && isVisible(cands[ri])) { el = cands[ri]; break; }
      }
      // 一个可见的都没有时，退回「存在即用」，免得报「找不到」而元素明明在 dump 里。
      if (!el) {
        for (var rj = 0; rj < cands.length; rj++) {
          if (roleOf(cands[rj]) === wantRole) { el = cands[rj]; break; }
        }
      }
    }
    return el;
  }

  // ────────────────────────────────────────────────────────────────────
  // 动作
  // ────────────────────────────────────────────────────────────────────

  /**
   * 滚到元素可见。
   *
   * ⚠️ 用 `block:'center'` 而不是默认的 `'start'`：贴顶的元素会被界面自己的
   *   固定顶栏盖住，点击落到顶栏上 —— 症状是「点了没反应」。
   */
  function reveal(el) {
    try {
      el.scrollIntoView({ block: 'center', inline: 'center' });
    } catch (e) {
      try { el.scrollIntoView(); } catch (e2) { /* 忽略 */ }
    }
  }

  /**
   * **像人一样**点一个元素。
   *
   * 为什么不是简单的 `el.click()`：很多 UI（拖拽、长按、需要 pointer 事件的
   * 自绘控件）监听的是 `pointerdown`/`mousedown` 而不是 `click`。而 React 18
   * 用根级事件委托，缺了前置事件时某些组件的内部状态机不会推进 —— 表现为
   * 「click 事件发了，但界面没变」。
   *
   * ⚠️ `elementFromPoint` 这一步不可省：目标可能被一个透明遮罩（弹窗背景、
   *   加载蒙层）盖住，此时真实接收事件的是遮罩。我们能做的是把事件发给**最上层
   *   的那个元素**，并把它报回去 —— 让调用方自己判断「是不是被挡了」。
   */
  function clickEl(el) {
    reveal(el);
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;

    var top = null;
    try { top = document.elementFromPoint(x, y); } catch (e) { /* 坐标越界 */ }
    var target = el;
    if (top && (top === el || el.contains(top) || (top.contains && top.contains(el)))) target = top;

    var base = {
      bubbles: true,
      cancelable: true,
      composed: true,
      clientX: x,
      clientY: y,
      screenX: x,
      screenY: y,
      button: 0,
      buttons: 1,
      view: window
    };

    try { if (typeof target.focus === 'function') target.focus(); } catch (e) { /* 不可聚焦 */ }

    var fired = [];
    function fire(type, ctor, extra) {
      try {
        var opts = base;
        if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) opts[k] = extra[k];
        var ev;
        if (ctor && window[ctor]) ev = new window[ctor](type, opts);
        else ev = new MouseEvent(type, opts);
        target.dispatchEvent(ev);
        fired.push(type);
      } catch (e) {
        // 单个事件失败不影响其余（老 WebView 没有 PointerEvent）
      }
    }

    fire('pointerdown', 'PointerEvent');
    fire('mousedown', 'MouseEvent');
    fire('pointerup', 'PointerEvent', { buttons: 0 });
    fire('mouseup', 'MouseEvent', { buttons: 0 });
    fire('click', 'MouseEvent', { buttons: 0 });

    // ⚠️ `onTarget` 的判据是「真实手指会不会点到目标」，而**不是**「我们把事件
    //   发给了谁」。这两件事必须分开报：
    //   - 事件**总是发给目标元素**（下面 target 的选取保证了这一点）：我们要执行
    //     的是「点这个元素」这个意图，即使有装饰层浮在上面也不该丢掉意图。
    //   - 但 `elementFromPoint` 的结果要如实报告，否则调用方无从知道「这一次点击
    //     在真机上会被谁吃掉」。
    //
    // ⚠️ 第一版的判据写成了 `target === el`，而 `target` 只在「命中元素与目标
    //   有祖先/后代关系」时才被改掉 —— 于是无关元素（遮罩、蒙层）压在上面时
    //   `target` 仍是 `el`，`onTarget` 恒为 true。**这个字段因此在所有情况下
    //   都报 true，等于没有**；而工具文档明确写着「onTarget:false 说明被遮挡」，
    //   那句话永远触发不了。判据必须直接看命中元素本身。
    var covered = false;
    if (top && top !== el) {
      var related = (el.contains && el.contains(top)) || (top.contains && top.contains(el));
      covered = !related;
    }

    return {
      dispatched: fired,
      hit: top ? describe(top, -1) : null,
      // 命中元素取不到时（坐标越界、元素已脱离文档）不妄断遮挡：报 true，
      // 让调用方按「没发现问题」理解，而不是凭空得到一次假警报。
      onTarget: !covered
    };
  }

  /**
   * 往输入框里写字，**并让 React 认账**。
   *
   * ⚠️ 这是整个探针里最容易被忽略、也最容易失败的一步。React 受控组件内部
   *   追踪 `value` 的「上一次的值」，直接 `el.value = 'x'` 会被它判定成
   *   「值没变」而**不触发 onChange** —— 那正是「AI 说填好了，但发送按钮
   *   仍然是灰的」的原因。正解是用原型上的原生 setter 绕开追踪，再补一个
   *   冒泡的 `input` 事件。
   *
   * 这条做法是 React 官方测试工具（react-dom/test-utils 的
   * `setNativeValue`）用的同一招，不是偏方。
   */
  /**
   * 写进去的内容是否**真的在**元素里了。
   *
   * ⚠️ 判据是「**包含**」而不是「完全相等」：`contenteditable` 的富文本编辑器
   *   （官方 composer 就是 Lexical）会把文本包进 `<p><span data-lexical-text>`，
   *   严格相等会把一次**成功**的写入误报成失败。
   */
  function applied(el, want) {
    var got = readText(el);
    if (want === '') return got === '';
    return got.indexOf(want) >= 0;
  }

  function setValue(el, text) {
    var s = String(text == null ? '' : text);
    if (el.isContentEditable) {
      try { el.focus(); } catch (e) { /* 不可聚焦 */ }

      // ⚠️ **`execCommand('insertText')` 的生效是异步的**，这是本函数最容易搞错的
      //   一点，实测过两轮才量清楚：
      //
      //     t=0ms   调用 execCommand → 返回 true
      //     t=0ms   同步读 textContent / innerText → **都还是空的**
      //     t=700ms 再读 → 内容已落地（`<p><span data-lexical-text>…</span></p>`）
      //
      //   官方 composer 是 Lexical，它把插入排进自己的 update cycle，所以
      //   **同步读必然为空**。第一版拿「同步读 == 期望值」当成功判据，于是
      //   每一次都判定失败、再走 `el.textContent = s` 兜底 —— 而那个兜底会把
      //   Lexical 精心维护的 `<p><span>` 结构拍成裸文本，破坏编辑器内部模型。
      //   表面上「内容进去了」，实际是两条路都在写同一个元素。
      //
      //   所以：**返回 true 就相信它**，不做同步覆盖；只有它返回 false 或抛异常
      //   时才用兜底。落地的复核交给调用方在下一轮 `find`/`text` 里做
      //   （见 `type` 结果里的 `applied` 字段说明）。
      var ok = false;
      try { ok = document.execCommand('insertText', false, s); } catch (e) { ok = false; }
      if (ok) return 'execCommand(异步生效)';

      // 兜底：`execCommand` 不可用（老内核/非聚焦）时才走这条。
      el.textContent = s;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return applied(el, s) ? 'textContent' : 'textContent(未生效)';
    }

    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, s);
    else el.value = s;

    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return 'native-setter';
  }

  /**
   * 全选一个 `contenteditable` 里的内容（供清空与替换用）。
   *
   * ⚠️ **每次都要重建选区**，不能复用上一次的：`execCommand` 会消耗掉选区
   *   （哪怕返回 `false` 也一样），第二次调用时 `getSelection()` 已经是空的。
   */
  function selectAllIn(el) {
    try { el.focus(); } catch (e) { /* 不可聚焦 */ }
    try {
      var sel = window.getSelection();
      var range = document.createRange();
      range.selectNodeContents(el);
      sel.removeAllRanges();
      sel.addRange(range);
      return true;
    } catch (e) { return false; }
  }

  /**
   * 清空一个 `contenteditable`（官方 composer 是 Lexical）。
   *
   * ⚠️ **这是本文件里唯一一处只能靠实测确定写法的地方**，真机（CDY-TN90 /
   *   Android 10 / 系统 WebView 114）逐个候选量过，结论如下：
   *
   *   | 写法 | 结果 |
   *   |---|---|
   *   | `insertText('')` | **no-op**（返回 `true` 但内容一字不动） |
   *   | 全选 + `delete` | **no-op**（返回 `true`，内容不动） |
   *   | 全选 + `insertText('\n')` | **no-op** |
   *   | 全选 + `insertText(非空)` | ✅ **整体替换成那一段** |
   *   | 全选 + 派发 `Backspace` `keydown` | 只删**一个**字符（DOM 选区没被 Lexical 采纳） |
   *   | ✅ 全选 + `insertText('X')` → 再派发一次 `Backspace` | **清空成功** |
   *
   *   也就是说「写空」这条路根本不存在（`execCommand` 对空串是 no-op），
   *   必须**先把它压成单个字符，再把那一个字符删掉**。
   *
   * ⚠️ 为什么不能退化成 `el.textContent = ''`：那会把 Lexical 维护的
   *   `<p><span data-lexical-text>` 结构拍平，编辑器内部模型与 DOM 就此不一致 ——
   *   界面上看着空了，但 Lexical 仍记着旧内容，下一次输入会把它吐回来。
   *   （`setValue` 的注释里记着同一类教训。）
   *
   * ⚠️ 删除用的 `Backspace` 必须**走 `pressKey`**：真机实测单独派发
   *   `KeyboardEvent` 也能删掉，但 Lexical 对 `code`/`keyCode` 都有检查，
   *   复用已有的 `pressKey` 才不会在两处各写一份按键形状。
   */
  function clearEditable(el) {
    var before = readText(el);
    if (before === '') return '已是空的';
    selectAllIn(el);
    var replaced = false;
    try { replaced = document.execCommand('insertText', false, 'X'); } catch (e) { replaced = false; }
    if (!replaced) {
      // 连替换都不行（非 Lexical 的普通 contenteditable）—— 这时 textContent 是安全的。
      el.textContent = '';
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return 'textContent';
    }
    // 压成单字符后光标就在它后面，一次 Backspace 即可删掉（不必重建选区）。
    pressKey(el, 'Backspace', {});
    return 'selectAll+insertText+Backspace';
  }

  /**
   * 读一个可编辑元素的当前文本。
   *
   * ⚠️ `contenteditable` 用 `textContent` 而不是 `innerText`：赋值走的就是
   *   `textContent`，而 `innerText` 是**布局相关**的（元素不可见/未布局时返回
   *   空串）。用 `innerText` 做校验会出现「刚写进去、校验说不相等」的假失败
   *   —— `how` 报 `textContent(未生效)` 而发送键明明已经变可用（踩过）。
   *   校验与被校验的必须是同一个量。
   */
  function readText(el) {
    if (!el) return '';
    if (el.isContentEditable) return String(el.textContent || '');
    return typeof el.value === 'string' ? el.value : '';
  }

  /** 按键。React 的 Enter 提交走 `keydown`，所以三个都发。 */
  function pressKey(el, key, modifiers) {
    modifiers = modifiers || {};
    var base = {
      key: key,
      code: key,
      bubbles: true,
      cancelable: true,
      composed: true,
      ctrlKey: modifiers.ctrl === true,
      shiftKey: modifiers.shift === true,
      altKey: modifiers.alt === true,
      metaKey: modifiers.meta === true
    };
    var target = el || document.activeElement || document.body;
    var fired = [];
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      try {
        target.dispatchEvent(new KeyboardEvent(type, base));
        fired.push(type);
      } catch (e) { /* 忽略 */ }
    });
    return fired;
  }

  /**
   * 滚动。
   *
   * ⚠️ 找「可滚动的祖先」是有必要的：DSH 的聊天区不是 `document` 在滚，而
   *   是里面某个 overflow 容器。只滚 `window` 会「成功但没有效果」。
   */
  function findScrollable(from) {
    var el = from;
    while (el && el !== document.body && el !== document.documentElement) {
      var st = window.getComputedStyle(el);
      if (st && /(auto|scroll|overlay)/.test(st.overflowY) && el.scrollHeight > el.clientHeight + 4) return el;
      el = el.parentElement;
    }
    var best = null;
    var bestGap = 0;
    var cands = toArray(document.querySelectorAll('div,main,section,ul,ol'));
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      var gap = c.scrollHeight - c.clientHeight;
      if (gap > bestGap && c.clientHeight > 80) {
        var s2 = window.getComputedStyle(c);
        if (s2 && /(auto|scroll|overlay)/.test(s2.overflowY)) { best = c; bestGap = gap; }
      }
    }
    return best;
  }

  // ────────────────────────────────────────────────────────────────────
  // action 分派
  // ────────────────────────────────────────────────────────────────────

  function ok(extra) {
    var out = { ok: true, v: VERSION, url: location.href, title: document.title };
    if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) out[k] = extra[k];
    return out;
  }

  function fail(why, extra) {
    var out = { ok: false, v: VERSION, error: why, url: location.href };
    if (extra) for (var k in extra) if (extra.hasOwnProperty(k)) out[k] = extra[k];
    return out;
  }

  function run(cmd) {
    cmd = cmd || {};
    var action = String(cmd.action || 'ping');

    // ── ping：探针活着吗、跑在哪个页面 ────────────────────────────────
    if (action === 'ping') {
      return ok({
        viewport: viewportInfo(),
        readyState: document.readyState,
        interactive: document.querySelectorAll(INTERACTIVE).length
      });
    }

    // ── dump：把界面上的按钮全列出来（带精确位置）────────────────────
    if (action === 'dump') {
      var got = collect(cmd);
      var ov = overlay();
      var out2 = {
        viewport: viewportInfo(),
        matched: got.matched,
        count: got.elements.length,
        scanned: got.scanned,
        blocked: got.blocked,
        offscreen: got.offscreen,
        elements: got.elements
      };
      // ⚠️ 有全屏遮挡层时，**必须**在顶层把这件事说出来。否则模型拿到的是一份
      //   看起来正常的清单（元素有尺寸、opacity 为 1），挨个点却全部无效 ——
      //   这正是「AI 说点过了但界面没动」的现场。
      //
      // ⚠️ 但**只有 `kind:'mask'` 才说「先关掉它」**。`kind:'panel'` 是应用
      //   自己的全屏面板（设置 / 右侧边栏 / 抽屉），它就是当前屏幕 —— 对它说
      //   「先关掉它」会把模型引去点「返回」，那一屏就永远进不去了。
      if (ov && ov.kind === 'mask') {
        out2.overlay = ov.layer;
        out2.warning = '当前有覆盖视口大部分区域的遮挡层（空遮罩，里面一个可点元素都没有），' +
          '它下面的按钮都点不动。先关掉它（通常是弹窗里的某个按钮）再操作。';
      } else if (ov && ov.kind === 'panel') {
        // 面板屏不算故障，但值得提一句：底下那层按钮点不动是**正常**的。
        out2.panel = ov.layer;
        out2.note = '当前屏幕是一个覆盖全屏的面板（' + (ov.layer.cls || ov.layer.tag) +
          '），它里面的按钮可以点；它**下面**那层元素点不动是正常的，不要去关掉这个面板。';
      }
      return ok(out2);
    }

    // ── overlay：现在有没有遮挡层、它是什么、此刻真正能点什么 ────────
    if (action === 'overlay') {
      var found = overlay();
      // ⚠️ 不要试图回答「遮挡层**里面**有哪些按钮」—— 实测官方前端的弹窗结构是
      //   `_root_ > [_mask_（全屏空遮罩）, _dialog_（真内容）]`，也就是**按钮是
      //   遮罩的兄弟节点，不是后代**。按后代找会得到「0 个可点元素」，而屏幕
      //   上明明有个「继续」可以点。
      //
      //   模型真正要问的是「**现在我能点什么**」，所以直接回答那个：列出当前
      //   **没有被遮挡**的可交互元素。这在任何 DOM 结构下都成立。
      var live = collect({ limit: 30, clickableOnly: true });
      // ⚠️ `present` = 「**有没有**覆盖全屏的层」，`kind` 才说明它是哪一种。
      //   不要把 `present` 定义成「有没有遮挡」—— 面板屏会因此报 `present:false`，
      //   而调用方（例如「关掉弹窗直到没有遮挡层」那种循环）看到 false 就以为
      //   界面干净了，实际上面板正盖着 composer，后面每一步都点不动。
      //   实测踩过：图谱脚本照 `present` 判，右侧边栏一直开着没被关掉，
      //   紧接着的 `tap 发送` 就报 `onTarget:false`。
      //
      //   `blocking` 才是「它是否挡着下面的东西」—— 只有空遮罩才挡。
      var isMask = !!found && found.kind === 'mask';
      return ok({
        present: !!found,
        kind: found ? found.kind : null,
        blocking: isMask,
        layer: found ? found.layer : null,
        panel: found && found.kind === 'panel' ? found.layer : null,
        clickable: live.elements,
        count: live.elements.length,
        blocked: live.blocked,
        offscreen: live.offscreen
      });
    }

    // ── find：查单个元素的详情 ──────────────────────────────────────
    if (action === 'find') {
      var f = locate(cmd);
      if (!f) return fail('找不到元素', { spec: cmd });
      var d = describe(f, 0);
      d.html = trunc(f.outerHTML || '', 400);
      return ok({ element: d, viewport: viewportInfo() });
    }

    // ── tap：点一下 ────────────────────────────────────────────────
    if (action === 'tap') {
      var t = locate(cmd);
      if (!t) return fail('找不到要点击的元素', { spec: cmd });
      if (!isVisible(t)) {
        // 找到了但看不见 —— 明确报出来，别让它退化成「点了没反应」
        return fail('元素存在但不可见（被隐藏或尺寸为 0）', { element: describe(t, 0) });
      }
      var info = describe(t, 0);
      var res = clickEl(t);
      var out = {
        element: info,
        dispatched: res.dispatched,
        hitTopmost: res.hit,
        onTarget: res.onTarget,
        covered: !!res.hit && !res.onTarget,
        blockedBy: res.onTarget ? null : res.hit
      };
      // ⚠️ **屏幕外**是第二类「点不到」，必须与遮挡分开报。
      //   它的表现与遮挡一模一样（界面纹丝不动、`ok` 还是 true），但处置办法
      //   完全相反：遮挡要「先关掉挡在前面的东西」，屏幕外要「先把界面切到
      //   它所在的那个状态」（例如抽屉收起时侧栏按钮滑出视口，得先点开导航）。
      //   不报出来的后果是模型照着「遮挡」的思路去查 overlay，白费一轮还查不到。
      if (info.offscreen) {
        out.offscreen = true;
        out.hint = '元素中心点在视口外（CSS ' + Math.round(info.center.x) + ',' +
          Math.round(info.center.y) + '，视口 ' + (window.innerWidth || 0) + '×' +
          (window.innerHeight || 0) + '），真手指在屏幕上没有可点的坐标 —— ' +
          '事件虽然发出去了，但界面上看不出任何反应。' +
          '先把它所在的面板/状态切出来（例如收起状态下先点开抽屉），再用 dump 确认它已进入视口。';
        return ok(out);
      }
      // ⚠️ 被遮挡时**把话说明白**。这是本探针最该省下的一轮来回：模型点不动时
      //   最想知道的是「谁挡着、我该先点谁」，而不是自己去猜选择器写错了没有。
      if (!res.onTarget) {
        // ⚠️ 目标被「当前屏幕那个面板」吃掉不算故障：面板底下的元素本来就点不到。
        //   只有**空遮罩**才是「先关掉它」的场景。
        var ovHit = overlay();
        if (ovHit && ovHit.kind === 'panel') {
          out.hint = '这个元素在当前面板**下面**（面板是「' + (ovHit.layer.cls || ovHit.layer.tag) +
            '」），所以点不到。它不在当前屏幕上 —— 要么先关掉面板回到下面那层，' +
            '要么改点面板里面的按钮。';
          out.panel = ovHit.layer;
        } else {
          out.hint = '点击被上层元素吃掉了，目标没收到事件。先处理挡在前面的元素' +
            (res.hit && res.hit.text ? '（它的文案是「' + res.hit.text + '」）' : '') +
            '，或用 overlay 动作查看当前遮挡层。';
          if (ovHit) out.overlay = ovHit.layer;
        }
      }
      return ok(out);
    }

    // ── type：往输入框写字 ─────────────────────────────────────────
    //
    // ⚠️ **内容取自 `value`，不是 `text`** —— 这两个字段在这里是**两种意思**：
    //   `text` 是「按可见标签找元素」（与 tap/find 共用同一套定位），
    //   `value` 才是「要写进去的内容」。第一版把两者都用 `text`，结果是
    //   「填输入框时定位条件被内容覆盖」—— 找一个标签等于所填内容的元素，
    //   必然找不到，而报错是「找不到输入元素」，完全指不到真因。
    if (action === 'type') {
      var inp = locate(cmd);
      if (!inp) return fail('找不到输入元素', { spec: cmd });
      // ⚠️ `clear` 必须走 `clearEditable`，不能靠 `setValue(inp, '')` —— 对
      //   `contenteditable` 来说「写空串」是 **no-op**（见 clearEditable 的表）。
      //   第一版就是那么写的，症状是「clear:true 报 ok，但输入框里旧内容还在」，
      //   接着 `type` 把新内容**追加**在旧内容后面，而返回的 `value` 是拼接结果 ——
      //   模型看到一长串莫名其妙的东西，完全不知道是自己没清掉。
      var cleared = cmd.clear === true ? clearEditable(inp) : null;
      var how = setValue(inp, cmd.value == null ? '' : cmd.value);
      var submitted = null;
      if (cmd.enter === true) submitted = pressKey(inp, 'Enter', cmd);
      // ⚠️ 用 `readText` 而不是 `inp.value`：`contenteditable` 元素的 `value` 是
      //   `undefined`，于是这里**恒报 null**，而输入框里明明已经有字了 ——
      //   调用方拿到 `ok:true, value:null` 完全无法判断到底写进去没有（踩过）。
      //
      // ⚠️ 但 `value` 对 `contenteditable` **不能**当成「写成功了」的证据：
      //   `execCommand` 是异步生效的（见 `setValue`），**同步读必然为空**。
      //   所以这里只报「当时读到的值」，是否真的落地由 `applied` 表达 ——
      //   它的语义是「同步可确认已写入」，为 null 表示「异步写入已发出，
      //   请在下一步用 find/text 复核」，**不是失败**。
      var actual = readText(inp);
      var wanted = cmd.value == null ? '' : String(cmd.value);
      var confirmed = applied(inp, wanted);
      var async = how.indexOf('execCommand') === 0;
      return ok({
        element: describe(inp, 0),
        how: how,
        cleared: cleared,
        value: trunc(actual, MAX_TEXT),
        applied: confirmed ? true : (async ? null : false),
        submitted: submitted
      });
    }

    // ── key：按键 ──────────────────────────────────────────────────
    if (action === 'key') {
      var kel = cmd.sel || cmd.text || cmd.prefix || cmd.aria || cmd.role ? locate(cmd) : null;
      var fired = pressKey(kel, String(cmd.key || 'Enter'), cmd);
      return ok({ dispatched: fired, target: kel ? describe(kel, 0) : null });
    }

    // ── scroll：滚动 ───────────────────────────────────────────────
    if (action === 'scroll') {
      var anchor = cmd.sel || cmd.text || cmd.prefix || cmd.role ? locate(cmd) : null;
      var box = cmd.container ? bySel(cmd.container) : findScrollable(anchor);
      var dy = typeof cmd.dy === 'number' ? cmd.dy : 0;
      var dx = typeof cmd.dx === 'number' ? cmd.dx : 0;

      if (cmd.to === 'bottom' || cmd.to === 'top') {
        var target = box || document.scrollingElement || document.documentElement;
        var before = target.scrollTop;
        target.scrollTop = cmd.to === 'bottom' ? target.scrollHeight : 0;
        return ok({
          container: box ? cssPath(box) : 'document',
          before: before,
          after: target.scrollTop
        });
      }

      var beforeY = box ? box.scrollTop : (window.pageYOffset || 0);
      if (box) { box.scrollTop = beforeY + dy; box.scrollLeft = box.scrollLeft + dx; }
      else window.scrollBy(dx, dy);
      var afterY = box ? box.scrollTop : (window.pageYOffset || 0);
      return ok({
        container: box ? cssPath(box) : 'window',
        before: beforeY,
        after: afterY,
        moved: afterY !== beforeY
      });
    }

    // ── text：读可见文本（模型看不懂界面时用这个兜底）────────────────
    if (action === 'text') {
      var root = cmd.sel ? bySel(cmd.sel) : document.body;
      if (!root) return fail('找不到容器', { sel: cmd.sel });
      var raw = root.innerText || root.textContent || '';
      var lines = raw.split('\n').map(function (s) { return s.replace(/\s+/g, ' ').trim(); })
        .filter(function (s) { return s.length > 0; });
      var maxLines = typeof cmd.limit === 'number' && cmd.limit > 0 ? cmd.limit : 200;
      return ok({
        lines: lines.slice(0, maxLines),
        total: lines.length,
        truncated: lines.length > maxLines
      });
    }

    // ── html：读某元素的 HTML（排查用）─────────────────────────────
    if (action === 'html') {
      var node = cmd.sel ? bySel(cmd.sel) : document.body;
      if (!node) return fail('找不到元素', { sel: cmd.sel });
      var html = node.outerHTML || '';
      var max = typeof cmd.limit === 'number' && cmd.limit > 0 ? cmd.limit : 2000;
      return ok({ html: html.slice(0, max), length: html.length });
    }

    // ── wait：等条件成立（**单次检查**，重试由原生侧做）─────────────
    if (action === 'wait') {
      var hit = cmd.sel || cmd.text || cmd.prefix || cmd.testid || cmd.aria || cmd.role ? locate(cmd) : null;
      var needVisible = cmd.visible !== false;
      var satisfied = !!(hit && (!needVisible || isVisible(hit)));
      // ⚠️ 被遮挡的元素**不算「等到了」**：调用方等它出现是为了点它，而它现在
      //   点不动。报 satisfied 会让「等 → 点」这条路在弹窗场景下静默失败。
      //   **屏幕外同理**（`clickable` 的语义是「现在真能点」）：抽屉收起时侧栏
      //   按钮滑出视口，`wait clickable:true` 报「等到了」，紧接着的 `tap`
      //   就必然空点 —— 与遮挡是同一类错误的两个来源，必须一起排掉。
      if (satisfied && cmd.clickable === true && (blocker(hit) || offscreen(hit))) satisfied = false;
      return ok({
        satisfied: satisfied,
        element: hit ? describe(hit, 0) : null
      });
    }

    // ── info：一次性把「现在是什么界面」讲清楚 ──────────────────────
    if (action === 'info') {
      var path = location.pathname + location.search;
      var got2 = collect({ limit: 12 });
      // ⚠️ `overlay` 只报**空遮罩**（真遮挡）；面板屏走 `panel` 字段。
      //   合并成一个字段会让调用方分不清「先关掉它」和「这就是你的屏幕」。
      var found2 = overlay();
      return ok({
        viewport: viewportInfo(),
        path: path,
        hash: location.hash,
        readyState: document.readyState,
        focused: document.activeElement ? describe(document.activeElement, 0) : null,
        overlay: found2 && found2.kind === 'mask' ? found2.layer : null,
        panel: found2 && found2.kind === 'panel' ? found2.layer : null,
        blocked: got2.blocked,
        offscreen: got2.offscreen,
        top: got2.elements,
        scrollable: (function () {
          var s = findScrollable(null);
          return s ? { sel: cssPath(s), top: s.scrollTop, height: s.scrollHeight, view: s.clientHeight } : null;
        })()
      });
    }

    // ── lastError：取走「最近一次兜底错误码 + 真实原因」 ─────────────
    //
    // 引擎把真正的失败包成 `session/agent-busy` 这类笼统错误码，真因藏在
    // `details.reason` 里（见 webview-polyfill.js 末尾那段）。这里把它读出来，
    // 让原生能弹 Toast —— 用户在手机上不用连电脑就能看到真正的原因。
    //
    // 取走即清空：同一条错误不该每次调用都重复弹。
    if (action === 'lastError') {
      if (typeof window.__DSH_TAKE_ERROR__ === 'function') {
        return ok(window.__DSH_TAKE_ERROR__());
      }
      return ok({});
    }

    return fail('未知 action: ' + action, {
      known: ['ping', 'dump', 'find', 'tap', 'type', 'key', 'scroll', 'text', 'html',
        'wait', 'info', 'overlay', 'lastError']
    });
  }

  // ────────────────────────────────────────────────────────────────────
  // 装填（幂等）
  // ────────────────────────────────────────────────────────────────────

  if (!window.__DSH_UI_PROBE__ || window.__DSH_UI_PROBE__.__v !== VERSION) {
    window.__DSH_UI_PROBE__ = {
      __v: VERSION,
      run: run,
      // 下面这些是给「页面内的调试脚本」用的入口，不影响任何既有行为。
      dump: function (o) { return collect(o); },
      locate: locate,
      describe: describe,
      cssPath: cssPath,
      click: clickEl,
      setValue: setValue,
      highlight: function (sel) {
        // ⚠️ 只在显式调用时画框，且用 fixed 定位、pointer-events:none，
        //   所以它**不会**改变点击行为，也不会留在页面上（用完自己删）。
        var el = bySel(sel);
        if (!el) return false;
        var r = el.getBoundingClientRect();
        var box = document.createElement('div');
        box.setAttribute('data-dsh-probe-mark', '1');
        box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;' +
          'border:2px solid #ff3b30;border-radius:4px;box-sizing:border-box;' +
          'left:' + r.left + 'px;top:' + r.top + 'px;' +
          'width:' + r.width + 'px;height:' + r.height + 'px;';
        document.body.appendChild(box);
        setTimeout(function () { if (box.parentNode) box.parentNode.removeChild(box); }, 1200);
        return true;
      }
    };
  }

  return window.__DSH_UI_PROBE__.run(CMD);
}
