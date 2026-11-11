#!/usr/bin/env node
/**
 * 把真机上每个界面**原样**抓成独立可离线打开的 HTML。
 *
 *     node tools/ui-snapshot-html.mjs [--out docs/ui-themes/pages]
 *
 * ## 为什么不手抄 HTML
 *
 * 界面本身就是 Web（WebView 里的官方前端 + `dsh-android-mobile` 插件渲染出的
 * React DOM），所以**直接抓 DOM + CSSOM 就是原样界面**，比照着截图手写准得多：
 * 手抄永远对不齐类名、间距、圆角，改主题时改的也就不是真实生效的那一层。
 *
 * ## 抓的是什么
 *
 * 对每一屏：
 *   1. 照图谱的 `enter` 链点到该屏（复用 `ui-map-shots.mjs` 的语义）
 *   2. 取 `document.documentElement.outerHTML`（**页面世界里的活 DOM**）
 *   3. 把 145 个 stylesheet 的规则**全部序列化**成一份内联 `<style>`
 *      —— 2 个外链 CSS 的内容也一并并进去，于是产物零外部依赖
 *   4. 剥掉 `<script>`（离线快照不需要 JS，留着反而会去连引擎端口）
 *   5. 把主题令牌单独抽出来放进 `theme.css`，**换肤只改这一个文件**
 *
 * ## 主题令牌是哪一层
 *
 * 官方主题插件（`dsh-client-ui-theme`）把颜色全挂在 `--dsw-*` 上：
 * `--dsw-alias-label-primary`（正文）、`--dsw-alias-bg-base`（底）、
 * `--dsw-specific-*`（局部）…… 改这些就等于换肤。
 *
 * ⚠️ **抓到的是 CSS 计算前的规则文本**，不是 `getComputedStyle` 的结果 —— 后者
 *    会把 `var()` 展开成定值，改起来就没法调了。
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');

const argv = process.argv.slice(2);
const outIdx = argv.indexOf('--out');
const OUT = outIdx >= 0 ? argv[outIdx + 1] : join(ROOT, 'docs', 'ui-themes', 'pages');

const CDP = process.env.UIPROBE_CDP ?? 'http://127.0.0.1:19222/json/list';
const ADB = process.env.ANDROID_UI_ADB ??
  join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const DEVICE = process.env.ANDROID_UI_DEVICE ?? '';
const APP_ID = process.env.ANDROID_UI_APP ?? 'com.dsh.android';

const MAP = JSON.parse(readFileSync(join(ROOT, 'tools', 'ui-map-real-frontend.json'), 'utf8'));
const PROBE_SRC = readFileSync(join(ROOT, 'runtime', 'scripts', 'ui-probe.js'), 'utf8');

/** 图谱之外的补充屏（与 ui-map-shots.mjs 保持一致）。 */
const EXTRA = [
  { id: 'settings-style',      title: '设置 › AI 回复风格', enter: [{ spec: { text: '设置' } }, { spec: { text: 'AI 回复风格' } }] },
  { id: 'settings-multimodal', title: '设置 › 多模态配置', enter: [{ spec: { text: '设置' } }, { spec: { text: '多模态配置' } }] },
];
const SCREENS = [...MAP.screens, ...EXTRA];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── CDP 连接（可重建）────────────────────────────────────────────────────
//
// ⚠️ **App 会在跑的过程中自己重启**（实测：抓到第 15 屏时 pid 从 2953 变成 11097），
//    重启后 webview_devtools_remote_<pid> 这个 localabstract 名就变了，转发随之
//    失效，表现为 `Runtime.evaluate 超时`（旧 WebSocket 还连着，但对面已经没了）。
//    所以连接要能**整体重建**，不能只连一次。
//
// ⚠️ 超时不能设太长：连接已死时每条命令都要等满超时，19 屏就是几十分钟白等。

let ws = null, URL_PAGE = null;
let id = 0; const pending = new Map();

function adb(args) {
  return execFileSync(ADB, DEVICE ? ['-s', DEVICE, ...args] : args,
    { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
}

/** 重做端口转发并连上当前的 page 目标。App 重启后调用。 */
async function connect() {
  if (ws) { try { ws.close(); } catch { /* 已断 */ } ws = null; }
  pending.clear();

  // 1) 把 App 拉到前台（后台时页面网络冻结，且 pid 可能已变）
  try {
    adb(['shell', 'am', 'start', '-n', `${APP_ID}/.MainActivity`]);
  } catch { /* 已在前台时这条命令也可能报 warning，忽略 */ }
  await sleep(1500);

  // 2) 重做转发：pid 每次重启都会变，端口要重新绑到新的 localabstract
  const pid = adb(['shell', 'pidof', APP_ID]).trim().split(/\s+/)[0];
  if (!pid) throw new Error(`${APP_ID} 没有进程`);
  const port = new URL(CDP).port;
  try { adb(['forward', '--remove', `tcp:${port}`]); } catch { /* 没绑过 */ }
  adb(['forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`]);

  // 3) 连新的 WebSocket
  const list = await (await fetch(CDP)).json();
  const page = list.find((t) => t.type === 'page' && /^https?:/.test(t.url || ''));
  if (!page) throw new Error('没有 page 目标');
  // ⚠️ 引擎端口每次启动都是随机分配的（`--port 0`），App 重启后页面 URL 会变，
  //    所以要跟着更新，不能一直用第一次那个。
  URL_PAGE = page.url;

  ws = new WebSocket(page.webSocketDebuggerUrl);
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data); const p = pending.get(m.id);
    if (p) { pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result); }
  });
  await new Promise((res, rej) => {
    ws.addEventListener('open', res);
    ws.addEventListener('error', rej);
    setTimeout(() => rej(new Error('WebSocket 连接超时')), 15000);
  });
  return pid;
}

/** 单条命令超时（连接死掉时快速失败，交给上层重建）。 */
const CMD_TIMEOUT = 20000;

function send(method, params) {
  const myId = ++id;
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => { pending.delete(myId); reject(new Error(`CDP ${method} 超时`)); }, CMD_TIMEOUT);
    pending.set(myId, { resolve: (v) => { clearTimeout(t); resolve(v); }, reject: (e) => { clearTimeout(t); reject(e); } });
    ws.send(JSON.stringify({ id: myId, method, params }));
  });
}
async function ev(expr) {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? JSON.stringify(r.exceptionDetails));
  return r.result.value;
}

/** 带重连的求值：超时/连接错误 → 重建连接 → 重试一次。 */
async function evRetry(expr) {
  try { return await ev(expr); }
  catch (e) {
    if (!/超时|WebSocket|ECONN|closed/i.test(e.message)) throw e;
    const pid = await connect();
    console.log(`  ↻ 连接已重建 (pid ${pid})`);
    return await ev(expr);
  }
}
const probe = async (cmd) => JSON.parse(await evRetry(`JSON.stringify((${PROBE_SRC})(${JSON.stringify(cmd)}))`));

await connect();
console.log(`已连接 (pid ${adb(['shell', 'pidof', APP_ID]).trim().split(/\s+/)[0]})`);

const LS_DUMP = `(() => { const o = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); o[k] = localStorage.getItem(k); } return JSON.stringify(o); })()`;
const ORIGINAL_LS = JSON.parse(await evRetry(LS_DUMP));
console.log(`localStorage 快照: ${Object.keys(ORIGINAL_LS).length} 个键（跑完原样写回）`);

/** 用户原本所在的会话 id。 */
const ORIGINAL_CURRENT = (() => {
  try { return JSON.parse(ORIGINAL_LS['dsh.sessions.current'] ?? '{}').sessionId ?? null; }
  catch { return null; }
})();
console.log(`用户当前会话: ${ORIGINAL_CURRENT ?? '(无)'}`);

/**
 * 只在**空会话态**才存在的屏。
 *
 * ⚠️⚠️ **这几屏必须先进空会话态，否则抓到的是用户自己的会话内容** —— 实测踩到：
 *    `hero` 抓成了用户正在进行的「创建浅绿色界面插件」对话（含他的提问与
 *    AI 回复正文）。那既是**用户隐私**，也让「起点界面」这个快照名不副实。
 *
 * ⚠️ 另外这一组的界面元素也只在空会话态**存在**：`选择模型` / `访问模式` /
 *    `打开右侧边栏` 三个入口都挂在 Hero 的输入框区，切换到真实会话后它们就没了，
 *    表现为 `tap` 直接 `ok=false`（元素找不到）。
 *
 * 图谱脚本 `ui-map-real-frontend.mjs:613` 只标了三个（它按屏顺序跑，靠前几屏
 * 蒙对了）；实测需要连 `model-picker` / `right-sidebar` 一起算进来才稳。
 */
const EMPTY_SESSION_ONLY = new Set([
  'hero', 'access-mode', 'preset-picker', 'model-picker', 'right-sidebar',
]);

/** `--only a,b,c` —— 只重抓这几屏（补抓时用，省得 19 屏全跑一遍）。 */
const onlyIdx = argv.indexOf('--only');
const ONLY = onlyIdx >= 0 ? new Set(argv[onlyIdx + 1].split(',').map((s) => s.trim())) : null;

/** 跑之前的会话 id 集合 —— 收尾时靠差集归档本次跑出来的空会话。 */
async function listSessionIds() {
  await evRetry(`(() => { window.__rpc0 = null;
    fetch('/api/session/list', { method:'POST', headers:{'content-type':'application/json'},
      body: JSON.stringify({ type:'client-request', rpcId:'q0', method:'session/list', payload:{ args:{ _request:{} } } }) })
      .then(r=>r.text()).then(t=>{ window.__rpc0=t; }).catch(e=>{ window.__rpc0='ERR '+e; });
    return 1; })()`);
  for (let i = 0; i < 40; i++) {
    await sleep(250);
    const v = await evRetry('window.__rpc0');
    if (v) {
      try { return new Set((JSON.parse(v)?.result?.value?.items ?? []).map((x) => x.sessionId)); }
      catch { return null; }
    }
  }
  return null;
}
const ORIGINAL_SESSION_IDS = await listSessionIds();
console.log(`跑之前的会话: ${ORIGINAL_SESSION_IDS ? `${ORIGINAL_SESSION_IDS.size} 个` : '(没读到，收尾跳过归档)'}`);

// ── 基态 / 遮挡 / 面板（与 ui-map-shots.mjs 同一套语义）────────────────────
/**
 * 回到**基态**（Hero）并重载页面。
 *
 * ⚠️ `emptySession` 的顺序**不能反**：必须先把原始快照整体写回（`keep` 里就带着
 *    正确的 `dsh.sessions.current`），**再**按需删它。反过来（先清后补）会撞上
 *    前端 `reuseOrCreateBlank()`：它找不到可复用的 blank 会话就**自己新建一个**，
 *    用户的界面从此停在新建的空会话上 —— 这正是「不能被用户看出」要防的事。
 *    根因见 `ui-map-real-frontend.mjs:537-556`。
 */
async function resetToBase(emptySession = false) {
  await evRetry(`(() => {
    var keep = ${JSON.stringify(ORIGINAL_LS)};
    for (var i = localStorage.length - 1; i >= 0; i--) localStorage.removeItem(localStorage.key(i));
    for (var k in keep) localStorage.setItem(k, keep[k]);
    ${emptySession ? "localStorage.removeItem('dsh.sessions.current');" : ''}
    return 1;
  })()`);
  await send('Page.navigate', { url: URL_PAGE });
  for (let i = 0; i < 60; i++) {
    await sleep(400);
    try { if (await evRetry(`document.querySelectorAll('*').length`) > 50) { await sleep(700); return; } } catch { /* 导航中 */ }
  }
  console.log('  ⚠️ 前端似乎没挂载起来');
}

/** 把 `dsh.sessions.current` 写回用户原本的会话。 */
async function restoreCurrentSession(tag) {
  if (!ORIGINAL_CURRENT) return;
  await evRetry(`localStorage.setItem('dsh.sessions.current', ` +
    `${JSON.stringify(JSON.stringify({ sessionId: ORIGINAL_CURRENT }))}); 1`);
  console.log(`  ${tag}已把 current 写回用户会话 ${ORIGINAL_CURRENT}`);
}

async function dismissOverlays() {
  for (let round = 1; round <= 5; round++) {
    const ov = await probe({ action: 'overlay' });
    if (!ov.present || ov.kind !== 'mask') return;
    const SKIP = /稍后|跳过|以后|知道了|我知道了|继续|确定|关闭|取消|好$/;
    const closer = ov.clickable.find((b) => b.tag === 'button' && SKIP.test(b.text || '')) ??
      ov.clickable.filter((b) => b.tag === 'button').pop() ?? ov.clickable[0];
    if (!closer) return;
    await probe({ action: 'tap', sel: closer.sel });
    await sleep(700);
  }
}

async function closePanels() {
  for (let i = 0; i < 3; i++) {
    const d = await probe({ action: 'dump', limit: 400 });
    const closer = d.elements.find((e) => /收起右侧边栏|收起侧边栏/.test(e.text || ''));
    if (!closer) return;
    await probe({ action: 'tap', aria: closer.text });
    await sleep(600);
  }
}

// ── 抓取表达式 ───────────────────────────────────────────────────────────
//
// 全部在**页面世界**跑（那里才能读全部 CSSOM）。
//
// ⚠️ 一次 `Runtime.evaluate` 的返回值要能结构化克隆回来，而 outerHTML 有 500KB+
//    量级 —— 所以直接返回字符串，别包成对象再 JSON.stringify（会翻倍）。

/**
 * 1) 序列化全部样式表。
 *    · 外链 CSS 的 `cssRules` 与内联 `<style>` 一样可读（同源），所以一视同仁。
 *    · `@font-face` 的 `url("./fonts/x.woff2")` 是相对页面根的 —— 离线产物里
 *      字体缺失只会退化成系统字体，不影响布局与配色，故不下载字体。
 */
const GRAB_CSS = `(() => {
  const parts = [];
  for (const sheet of document.styleSheets) {
    let rules; try { rules = sheet.cssRules; } catch (e) { continue; }
    for (const r of rules) parts.push(r.cssText);
  }
  return parts.join('\\n');
})()`;

/** 2) 抽主题令牌规则（只留含 --dsw-* 自定义属性的规则，换肤就改这些）。 */
const GRAB_THEME = `(() => {
  const parts = [];
  for (const sheet of document.styleSheets) {
    let rules; try { rules = sheet.cssRules; } catch (e) { continue; }
    for (const r of rules) {
      if (!r.style) continue;
      let has = false;
      for (const p of r.style) if (p.startsWith('--dsw-') || p.startsWith('--ds-')) { has = true; break; }
      if (has) parts.push(r.cssText);
    }
  }
  return parts.join('\\n');
})()`;

/**
 * 3) 取页面骨架。
 *    · 剥 `<script>` —— 离线快照不需要 JS，留着会去连引擎端口然后报错刷屏。
 *    · 剥 `<link rel=stylesheet>` —— 样式已内联，留着会去请求不存在的路径。
 *    · 保留 `html`/`body` 的**内联 style**：那里挂着
 *      `color-scheme`、`--dsh-content-font-size` 这类运行期量，剥了就变样。
 *    · 冻结动画：`animation-play-state: paused`，快照是静态的。
 */
const GRAB_HTML = `(() => {
  const clone = document.documentElement.cloneNode(true);
  for (const s of clone.querySelectorAll('script')) s.remove();
  for (const l of clone.querySelectorAll('link')) l.remove();
  for (const s of clone.querySelectorAll('style')) s.remove();
  for (const t of clone.querySelectorAll('template')) t.remove();
  // ⚠️ **title 元素必须剥掉** —— 官方前端把它设成「当前会话标题」，而会话标题
  //    就是用户那句话（实测抓到「创建浅绿色界面插件 — DeepSeek Harness」）。
  //    留着等于把**用户数据**写进每一屏快照（19 屏里 17 屏中招），
  //    也会让每屏的文件头都跟着用户会话漂。快照要的是**界面结构**，不是用户状态。
  //    （⚠️ 本段在模板字符串里，注释中**不能出现反引号**，否则模板提前终止。）
  const t = clone.querySelector('title');
  if (t) t.remove();
  // ⚠️ 会话列表行（drawer 屏）里带着**用户的会话标题、时间、session id** ——
  //    标题就是用户那句话（实测「创建浅绿色界面插件」）。
  //    但列表本身是抽屉的固有内容，**结构不能删**（要拿它设计列表行样式），
  //    所以只把**文字换成占位符**，标签与 class 全部原样保留。
  //    锚点用 data-row-key（业务属性，跨构建稳定），不用 CSS Modules 的 hash 类名。
  for (const row of clone.querySelectorAll('[data-row-key^="session:"]')) {
    row.setAttribute('data-row-key', 'session:会话ID');
    for (const el of row.querySelectorAll('*')) {
      for (const n of el.childNodes) {
        if (n.nodeType === 3 && n.nodeValue && n.nodeValue.trim()) n.nodeValue = '会话标题';
      }
    }
    for (const el of row.querySelectorAll('[aria-label]')) {
      el.setAttribute('aria-label', '会话操作');
    }
  }
  // ⚠️ DOM 上还有两个**带 session id 值**的属性（data-conversation-session、
  //    data-sidebar-right-session），它们是用户状态，且会让快照每次抓都不一样。
  //    实测 app.css 里**没有任何选择器按这两个属性的值匹配**（只按属性名），
  //    所以把值换成占位符是安全的；属性名保留，样式照旧命中。
  for (const attr of ['data-conversation-session', 'data-sidebar-right-session']) {
    for (const el of clone.querySelectorAll('[' + attr + ']')) {
      el.setAttribute(attr, '会话ID');
    }
  }
  return clone.outerHTML;
})()`;

// ── 主流程 ───────────────────────────────────────────────────────────────
mkdirSync(OUT, { recursive: true });
console.log(`输出目录: ${OUT}\n`);

/** 每屏的标题（给 gallery 用）。 */
const TITLES = {};

/**
 * 快照专用：让动画**立刻跳到终态**。
 *
 * ⚠️ 不能用 `animation-play-state: paused` —— 那会停在**起始帧**，而前端大量用
 *    淡入/滑入动画（起始帧 `opacity: 0`），于是整屏文字全变透明。
 *    实测症状：`drawer.html` 里 `新会话`/`插件`/`工作区` 都在 DOM 里，渲染出来却是空的。
 *    改成 `animation-duration: 0s` 才是「直接到终点」。
 */
const STATIC_CSS = `
/* 快照专用：动画立刻跳到终态（不是暂停在起始帧），过渡直接禁用 */
*, *::before, *::after {
  animation-duration: 0s !important;
  animation-delay: 0s !important;
  transition: none !important;
}
`;

/**
 * 快照专用：把内容压进手机宽度。
 *
 * ⚠️ 真机上页面靠**运行时测量的内联样式**定宽（`--mp-hdr-h`、抽屉宽等由 JS 写入
 *    `html` 的 style），离线快照里那些量是按当时状态算的；换个渲染窗口就可能溢出。
 *    这里给根元素钉死手机视口宽度，保证 19 屏在浏览器里看起来都是手机比例。
 */
const SNAPSHOT_VIEWPORT_CSS = `
html { width: 360px !important; max-width: 360px !important; overflow-x: hidden !important; }
body { width: 360px !important; max-width: 360px !important; overflow-x: hidden !important; }
`;

/**
 * 样式是**全站共享**的（19 屏抓到的是同一份 611 KB），所以落成两个公共文件，
 * 各屏只引用它们 —— 否则 19 × 611 KB ≈ 12 MB 全是重复内容。
 *
 * ⚠️ 各屏 `<head>` 里**不内联**样式，改用 `<link>`：这样改一次 `app.css`
 *    19 屏全变，正是「设计主题」要的效果。
 */
const ASSETS = join(OUT, '..', 'assets');

/**
 * 快照页尾部的主题注入脚本。
 *
 * 作用：让页面可以用 `?theme=<URL-encoded JSON>` 覆盖主题变量，供主题实验室
 * 在 iframe 里实时预览。
 *
 * ⚠️ **必须放在 `</body>` 前**（而不是 `<head>` 里）：层叠顺序按**文档顺序**决定，
 *    放最后才能盖住 `theme-base.css` / `theme.css`。
 *
 * ⚠️ `file://` 下 iframe 与父页是不同源，读不到 `contentDocument`，所以只能靠
 *    URL 参数传值 —— 这是这个脚本存在的唯一理由。
 */
const THEME_INJECTOR = `<script>
(function () {
  var m = /[?&]theme=([^&]*)/.exec(location.search);
  if (!m) return;
  var vars;
  try { vars = JSON.parse(decodeURIComponent(m[1])); } catch (e) { return; }
  var css = ':root, body, body * {';
  for (var k in vars) if (/^--/.test(k)) css += k + ':' + vars[k] + ' !important;';
  css += '}';
  var el = document.createElement('style');
  el.id = '__theme_live';
  el.textContent = css;
  document.body.appendChild(el);
})();
</script>
`;

/**
 * ⚠️ `bodyHtml` 是 `documentElement.outerHTML`，**本身就是一份完整的
 *    `<html>…</html>`**（自带 `</body></html>`）。所以注入器**不能**简单拼在它
 *    后面 —— 那样 script 落在第一个 `</html>` 之外，浏览器直接丢弃，
 *    表现为「带 theme 参数和不带参数的截图字节数完全一样」，即**实时预览静默失效**。
 *    正确位置是 body 闭合标签**之前**。
 *
 * ⚠️ 上面那个闭合标签必须写成**真正的 `</script>`**。写成 JS 里常见的转义形态
 *    （反斜杠加斜杠）在 node 模板字符串里会原样输出成带反斜杠的文本，那在真实 HTML
 *    里**不是合法闭合标签** —— 浏览器把整段脚本当成未闭合内容吞掉，脚本永不执行。
 *    症状与上面那条**一模一样**（截图字节数相同），极难分辨。
 */
function injectBeforeBodyEnd(html, snippet) {
  const i = html.lastIndexOf('</body>');
  if (i < 0) return html + snippet;           // 兜底：真没有就追加
  // ⚠️ 剥掉 bodyHtml 自带的 `</html>`（它在 `</body>` **之后**），否则外层模板
  //    还会再写一个，输出里就有两个 `</html>`。
  const tail = html.slice(i).replace(/<\/html>\s*$/i, '');
  return html.slice(0, i) + snippet + tail;
}

function pageHtml(title, id, extraCssName, bodyHtml) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=360, initial-scale=1">
<title>${title} — DSH Android 界面</title>
<!--
  由 tools/ui-snapshot-html.mjs 从真机抓取生成，**请勿手改**。
  界面 id: ${id}

  样式加载顺序（后面的覆盖前面的）：
    1. ../assets/app.css         官方前端原始样式（自动生成，别动）
    2. ../assets/extra-<id>.css  本屏专属样式，仅少数屏有（自动生成，别动）
    3. ../assets/theme-base.css  官方主题令牌基线（自动生成，别动）
    4. ../assets/theme.css       ★ 你的主题 —— 改这一个文件
-->
<link rel="stylesheet" href="../assets/app.css">
${extraCssName ? `<link rel="stylesheet" href="../assets/${extraCssName}">\n` : ''}<link rel="stylesheet" href="../assets/theme-base.css">
<link rel="stylesheet" href="../assets/theme.css">
</head>
${injectBeforeBodyEnd(bodyHtml, THEME_INJECTOR)}
</html>
`;
}

const manifest = [];

/**
 * 自检：所有注入页面的 JS 片段里**不能出现反引号**。
 *
 * ⚠️ 这些片段本身是 JS 模板字符串，片段里任何一个反引号（**包括注释里的**）都会
 *    提前终止模板，报成 `ReferenceError: xxx is not defined` —— 报错位置还指向
 *    被截断的那半句注释，与真因差着十万八千里。本文件已因此栽过两次。
 *
 * ⚠️ 必须放在**所有片段定义之后**（const 有 TDZ，提前引用会抛
 *    `Cannot access ... before initialization`）。
 */
for (const [name, src] of Object.entries({ GRAB_HTML, GRAB_CSS, GRAB_THEME, THEME_INJECTOR })) {
  if (src.includes('\u0060')) {
    console.error(`\n✗ 自检失败：${name} 的源码里出现了反引号 —— ` +
      `模板字符串会提前终止。请把它改成普通引号或去掉。\n`);
    process.exit(1);
  }
}

// ⚠️ 注入器里那个闭合标签必须是**真的闭合标签**。JS 里惯用的「反斜杠转义斜杠」
//    写法在模板字符串里会原样输出，浏览器认不出、把整段脚本吞掉，
//    而症状（主题实时预览静默失效）与「位置放错」完全一样。
if (/<\\\/script>/i.test(THEME_INJECTOR)) {
  console.error('\n✗ 自检失败：THEME_INJECTOR 的 script 闭合标签带了反斜杠转义，' +
    '浏览器不会把它当闭合标签（脚本整段失效）。请写成真正的闭合标签。\n');
  process.exit(1);
}
if (!/<\/script>\s*$/.test(THEME_INJECTOR.trimEnd())) {
  console.error('\n✗ 自检失败：THEME_INJECTOR 结尾不是合法的 script 闭合标签。\n');
  process.exit(1);
}
let baseCss = null;          // 全站共享样式（第一屏抓到的即为基准）
const themeSeen = new Set(); // 已并入 theme.css 的令牌规则
const themeParts = [];       // 去重后的主题令牌
const extraScreens = [];     // 有专属样式的屏（如 terminal 多出 xterm 的 70 KB）

for (const sc of SCREENS) {
  const name = sc.id;
  if (ONLY && !ONLY.has(name)) continue;
  process.stdout.write(`[${name}] `);
  // 空会话态那几屏要先清 `dsh.sessions.current`（否则抓到的是用户的真实对话）
  const emptySession = EMPTY_SESSION_ONLY.has(name);
  try {
    await resetToBase(emptySession);
    await dismissOverlays();
    await closePanels();

    let ok = true;
    for (const step of sc.enter ?? []) {
      const spec = step.spec ?? {};
      const r = await probe({ action: 'tap', ...spec });
      if (!r.ok || r.onTarget === false) {
        console.log(`\n  ⚠️ 点 ${JSON.stringify(spec)} 失败: ok=${r.ok} onTarget=${r.onTarget} ${r.hint ?? ''}`);
        ok = false;
        break;
      }
      await sleep(650);
    }
    if (!ok) { manifest.push({ id: name, ok: false, error: 'enter 链有一步没点通' }); continue; }

    await sleep(400);
    const css = await evRetry(GRAB_CSS);
    const themeCss = await evRetry(GRAB_THEME);
    const html = await evRetry(GRAB_HTML);

    // 主题令牌：全站共用一份，逐条去重
    for (const line of themeCss.split('\n')) {
      const key = line.trim();
      if (key && !themeSeen.has(key)) { themeSeen.add(key); themeParts.push(key); }
    }

    // 样式：第一屏作为基准，其余屏只存「比基准多出来的部分」
    let extraName = null;
    if (baseCss === null) {
      baseCss = css;
    } else if (css !== baseCss) {
      extraName = `extra-${name}.css`;
      extraScreens.push({ id: name, file: extraName, bytes: css.length });
    }

    const title = sc.title || name;
    TITLES[name] = title;
    writeFileSync(join(OUT, `${name}.html`), pageHtml(title, name, extraName, html));
    manifest.push({
      id: name, title, ok: true, html: `${name}.html`,
      htmlBytes: html.length, extraCss: extraName,
    });
    console.log(`✓ html ${html.length} B${extraName ? ` (+ ${extraName}, 基准样式不同)` : ''}`);
  } catch (e) {
    console.log(`\n  ✗ ${e.message}`);
    manifest.push({ id: name, ok: false, error: e.message });
  } finally {
    // ⚠️ 空会话态那三屏**跑完立刻**把 current 写回用户会话，不能等全部跑完 ——
    //    否则中途一旦中断，用户的界面就停在新建的空会话上了。
    if (emptySession) await restoreCurrentSession(`[${name}] `);
  }
}

// ── 落公共样式 ───────────────────────────────────────────────────────────
mkdirSync(ASSETS, { recursive: true });

/**
 * 解析 `var()` 引用链，返回 变量名 → 最终色值。
 *
 * 官方把颜色分成两层：`--dsw-alias-*`（语义层，**几乎全是 `var(...)` 引用**）和
 * `--dsw-static-*`（色板层，真正的十六进制）。主题实验室要给人改的是**语义层**，
 * 所以必须顺着引用走到色板层，否则每个核心变量都显示成「非纯色」、取色器点不动。
 *
 * 同名变量在文件里会出现多次（浅色/深色两套，选择器不同）—— 后出现的覆盖先出现的，
 * 与 CSS 层叠顺序一致（`theme-base.css` 就是按 CSSOM 顺序序列化出来的）。
 */
function buildLabData() {
  // ⚠️ 优先读**磁盘上**的 theme-base.css，而不是本次内存里的 themeParts ——
  //    `--only` 补抓模式只跑了 1~3 屏，themeParts 会残缺；磁盘那份才是全量基线。
  const baseFile = join(ASSETS, 'theme-base.css');
  const css = existsSync(baseFile) ? readFileSync(baseFile, 'utf8') : themeParts.join('\n');
  const raw = {};
  const re = /(--(?:dsw|ds)-[a-z0-9\-]+)\s*:\s*([^;]+);/gi;
  let m;
  while ((m = re.exec(css))) raw[m[1]] = m[2].trim();

  const resolve = (name, depth = 0) => {
    const v = raw[name];
    if (v === undefined || depth > 8) return v;
    const ref = /^var\(\s*(--[a-z0-9\-]+)\s*(?:,[^)]*)?\)$/i.exec(v);
    return ref ? resolve(ref[1], depth + 1) : v;
  };
  const tokens = {};
  for (const k of Object.keys(raw)) tokens[k] = resolve(k) ?? raw[k];

  const screens = SCREENS.filter((s) => existsSync(join(OUT, `${s.id}.html`)))
    .map((s) => ({ id: s.id, title: s.title || s.id }));

  const nResolved = Object.keys(raw).filter((k) => raw[k] !== tokens[k]).length;
  console.log(`  · lab-data.js: ${screens.length} 屏 / ${Object.keys(tokens).length} 个变量` +
    `（其中 ${nResolved} 个 var() 引用已解析成色值）`);

  return `// 由 tools/ui-snapshot-html.mjs 生成 —— 内联进 lab.html 以便 file:// 直接打开。\n` +
    `// ⚠️ 自动生成，不要手改；重跑抓取即可刷新。\n` +
    `// ⚠️ __TOKENS__ 里的值已做 var() 链解析（alias 层 → static 色板层）。\n` +
    `window.__SCREENS__ = ${JSON.stringify(screens)};\n` +
    `window.__TOKENS__ = ${JSON.stringify(tokens)};\n`;
}

// ⚠️ `--only` 是**补抓**模式：公共样式与 manifest 保持不动，只重写被选中的那几个
//    html，免得把已经抓好的 16 屏从 manifest 里抹掉。
if (ONLY) {
  console.log(`\n补抓模式（--only ${[...ONLY].join(',')}）：公共样式与 manifest 未改动`);
  const mf = join(OUT, '..', 'manifest.json');
  if (existsSync(mf)) {
    const prev = JSON.parse(readFileSync(mf, 'utf8'));
    for (const m of manifest) {
      if (!m.ok) continue;
      const i = prev.screens.findIndex((s) => s.id === m.id);
      if (i >= 0) prev.screens[i] = m; else prev.screens.push(m);
    }
    prev.url = URL_PAGE;
    writeFileSync(mf, JSON.stringify(prev, null, 2));
    console.log(`已合并进 ${mf}`);
  }
} else {

writeFileSync(join(ASSETS, 'app.css'),
  `/* 官方前端原始样式 —— 由 tools/ui-snapshot-html.mjs 从真机 CSSOM 序列化而来。\n` +
  `   ⚠️ 自动生成，不要手改；换肤请改 theme.css。 */\n\n${baseCss}\n\n${STATIC_CSS}\n${SNAPSHOT_VIEWPORT_CSS}\n`);

// ⚠️ 令牌基线（`--dsw-*` 那 43 KB）是**官方值**，重跑抓取会覆盖它。
//    用户自己的主题写在 `theme.css` 里 —— 那个文件**只在不存在时创建**，
//    所以重跑抓取不会把用户改好的主题冲掉。
writeFileSync(join(ASSETS, 'theme-base.css'),
  `/* 官方主题令牌基线 —— 从真机 CSSOM 抽出的所有 --dsw-* / --ds-* 声明。\n` +
  `   ⚠️ 自动生成，不要手改；要覆盖哪个令牌就在 theme.css 里重写它。 */\n\n` +
  themeParts.join('\n') + '\n');

const themeFile = join(ASSETS, 'theme.css');
if (!existsSync(themeFile)) {
  writeFileSync(themeFile, `/* ══════════════════════════════════════════════════════════════════
   你的主题 —— 改这一个文件，19 屏界面同时生效
   ══════════════════════════════════════════════════════════════════

   官方前端把颜色全挂在 CSS 变量上（见 theme-base.css），所以换肤 = 重写变量。
   这里写的规则会覆盖基线，因为它在 theme-base.css **之后**加载。

   常用的几个（值取自 theme-base.css，抄过来改掉即可）：

     --dsw-alias-bg-base          页面底色
     --dsw-alias-bg-elevated      卡片 / 浮层底色
     --dsw-alias-label-primary    正文
     --dsw-alias-label-secondary  次要文字
     --dsw-alias-brand-primary    品牌色（按钮、选中态）
     --dsw-alias-border-l1        一级描边

   改完存盘，浏览器刷新即可看到效果（无需重跑抓取）。

   示例 —— 把品牌色换成青绿、底色换成暖白，取消注释即生效：

   :root, body {
     --dsw-alias-brand-primary: #0d9488;
     --dsw-alias-bg-base: #fffbf5;
   }
   ══════════════════════════════════════════════════════════════════ */

/* 在下面写你的覆盖规则 */
`);
  console.log('  · 已创建 assets/theme.css（你的主题，重跑抓取不会覆盖它）');
}

// ⚠️ 差异屏存的是「与基准不同的那一整份」，不是真正的 diff —— 序列化文本做
//    行级 diff 收益不大（611 KB 里往往只差几行），而整份存下来更直白。
for (const e of extraScreens) {
  console.log(`  · ${e.id} 样式与基准不同（${e.bytes} B），已单独存 ${e.file}`);
}
}   // ← 结束 `if (ONLY) { ... } else { ... }`

// ── 生成 lab-data.js：把数据内联，让 lab.html 在 file:// 下双击也能用 ──────
//
// ⚠️ 不内联的话，`lab.html` 里的 `fetch('manifest.json')` 在 `file://` 下会被
//    浏览器按跨源直接禁掉，页面只剩一句「请用本地服务器打开」。
//
// ⚠️ 令牌值必须做一次 `var()` 链解析：官方把颜色分了两层，**alias 层全是引用**
//    （`--dsw-alias-bg-base: var(--dsw-static-neutral-bluish-00)`），真正的色值在
//    `--dsw-static-*` 那层。不解析的话，lab 左侧**最该改的那几个**（页面底色、
//    正文色、品牌色）全显示成「非纯色」、取色器点不动 —— 那样主题实验室没法用。
//
// ⚠️ 放在 if/else **之外**：补抓模式（`--only`）也要刷新它，否则新增的屏不会
//    出现在 lab 的屏选择器里。
writeFileSync(join(OUT, '..', 'lab-data.js'), buildLabData());
console.log('  · 已写 lab-data.js（内联数据，lab.html 双击可开）');

// ── 还原用户状态 ─────────────────────────────────────────────────────────
console.log('\n还原 localStorage...');
await evRetry(`(() => {
  var keep = ${JSON.stringify(ORIGINAL_LS)};
  for (var i = localStorage.length - 1; i >= 0; i--) localStorage.removeItem(localStorage.key(i));
  for (var k in keep) localStorage.setItem(k, keep[k]);
  return 1;
})()`);
await send('Page.navigate', { url: URL_PAGE });
await sleep(2500);
await dismissOverlays();

const after = JSON.parse(await evRetry(LS_DUMP));
const same = Object.keys(ORIGINAL_LS).sort().join('|') === Object.keys(after).sort().join('|') &&
  Object.keys(ORIGINAL_LS).every((k) => ORIGINAL_LS[k] === after[k]);
console.log(same ? '✓ localStorage 与快照一致' : `⚠️ localStorage 有差异：${JSON.stringify(after).slice(0, 200)}`);

// ── 归档「本次跑出来的空会话」─────────────────────────────────────────────
//
// ⚠️ 空会话态那三屏每跑一次，前端都可能自建一个 blank 会话（`reuseOrCreateBlank`）。
//    不收走的话，用户列表里会多出几个「新会话」—— 这就是「能被用户看出」。
//
// ⚠️ 只归档**新增的且 blank 为 true** 的；新增的非空会话一律不碰（可能是用户
//    自己开的，宁可留着也不能误删）。
if (ORIGINAL_SESSION_IDS) {
  const post = async (method, payload) => {
    await evRetry(`(() => { window.__rpc = null;
      fetch('/api/${method}', { method:'POST', headers:{'content-type':'application/json'},
        body: JSON.stringify({ type:'client-request', rpcId:'r'+Date.now(),
          method:${JSON.stringify(method)}, payload:${JSON.stringify(payload)} }) })
        .then(r=>r.text()).then(t=>{ window.__rpc=t; }).catch(e=>{ window.__rpc='ERR '+e; });
      return 1; })()`);
    for (let i = 0; i < 40; i++) {
      await sleep(250);
      const v = await evRetry('window.__rpc');
      if (v) { try { return JSON.parse(v); } catch { return { raw: v }; } }
    }
    return null;
  };
  const listRes = await post('session/list', { args: { _request: {} } });
  const items = listRes?.result?.value?.items ?? [];
  const fresh = items.filter((it) => !ORIGINAL_SESSION_IDS.has(it.sessionId));
  const blanks = fresh.filter((it) => it.blank === true);
  console.log(`  会话数: 跑之前 ${ORIGINAL_SESSION_IDS.size} → 现在 ${items.length}` +
    `（新增 ${fresh.length}，其中空会话 ${blanks.length}）`);
  for (const it of blanks) {
    const arc = await post('workspace/archiveSession', { args: { request: { sessionId: it.sessionId } } });
    console.log(`  ${arc?.result?.ok === true ? '✅' : '❌'} 归档本次跑出的空会话 ${it.sessionId}`);
  }
  const nonBlank = fresh.filter((it) => it.blank !== true);
  if (nonBlank.length > 0) {
    console.log(`  ℹ️ 另有 ${nonBlank.length} 个新增的**非空**会话，未动（可能是用户自己建的）:`);
    for (const it of nonBlank) console.log(`     ${it.sessionId}`);
  }
}

// ⚠️ 补抓模式（`--only`）**不能**在这里重写 manifest —— 上面已经合并过了，
//    再写一次会把其余 16 屏从 manifest 里抹掉（实测踩到：manifest 只剩 3 屏）。
if (!ONLY) {
  writeFileSync(join(OUT, '..', 'manifest.json'),
    JSON.stringify({ url: URL_PAGE, viewport: MAP.viewport, dpr: MAP.dpr, screens: manifest, titles: TITLES }, null, 2));
}

const good = manifest.filter((m) => m.ok).length;
console.log(`\n抓取完成: ${good}/${manifest.length} 屏`);
ws.close();
