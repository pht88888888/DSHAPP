#!/usr/bin/env node
/**
 * 安卓真机 UI 驱动 —— MCP stdio 服务（零依赖）
 *
 * 把「PC 侧驱动手机界面」这件事注册成**给 agent 用的工具**，不再需要每次写脚本。
 *
 *     agent ──MCP──▶ 本进程 ──CDP WebSocket──▶ 手机 WebView 页面世界 ──探针──▶ DOM
 *
 * 与设备上那个 `ui` 工具插件（`dsh-android-uiagent`）**互不相干**：那个是给**手机上的
 * AI** 用的，走「文件信箱 + 原生 120ms 轮询」；这条直连 CDP，没有中间环节，
 * 是给**电脑上的我**用的。
 *
 * ## 自动做的四件事（不用调用方操心）
 *
 * 1. 找 adb（`ANDROID_UI_ADB` 或常见安装位置）
 * 2. 找设备（`ANDROID_UI_DEVICE`，否则取第一个）
 * 3. 做 `adb forward` 端口转发（**App 重启后会自动重做**）
 * 4. 注入/复用页面探针（版本不符就重新注入）
 *
 * ## 两个踩过的坑，已内建
 *
 * - **点抽屉里的元素要先等动画结束**：元素中心点还在视口外时（实测 CSS -86），
 *   点击事件发出去了但界面纹丝不动。`ui_tap` 会先轮询到它进入视口再点。
 * - **「元素存在」不是判据**：抽屉收起时里面的元素仍在 DOM 里。
 *   所以 `ui_screen` 默认只列视口内的，`ui_tap` 报的是「能不能真点到」。
 *
 * ## 环境变量
 *
 * | 变量 | 默认 | 说明 |
 * |---|---|---|
 * | `ANDROID_UI_ADB` | 自动探测 | adb 可执行文件 |
 * | `ANDROID_UI_DEVICE` | 第一个设备 | 序列号 |
 * | `ANDROID_UI_PORT` | `19222` | CDP 转发端口 |
 * | `ANDROID_UI_APP` | `com.dsh.android` | 包名 |
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const PROBE_SRC = readFileSync(join(HERE, '..', 'runtime', 'scripts', 'ui-probe.js'), 'utf8');
/** 从探针源码里读版本号，别写死 —— 探针升版时这里自动跟上。 */
const PROBE_VERSION = Number((PROBE_SRC.match(/var\s+VERSION\s*=\s*(\d+)/) || [])[1] ?? 0);

const PORT = Number(process.env.ANDROID_UI_PORT ?? 19222);
const APP_ID = process.env.ANDROID_UI_APP ?? 'com.dsh.android';
const DEVICE_ENV = process.env.ANDROID_UI_DEVICE ?? '';

// ════════════════════════════════════════════════════════════════════════
// adb / 设备 / 转发
// ════════════════════════════════════════════════════════════════════════

function findAdb() {
  if (process.env.ANDROID_UI_ADB) return process.env.ANDROID_UI_ADB;
  const candidates = [
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
    process.env.ANDROID_HOME && join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe'),
    process.env.ANDROID_SDK_ROOT && join(process.env.ANDROID_SDK_ROOT, 'platform-tools', 'adb.exe'),
    'adb',
  ].filter(Boolean);
  for (const c of candidates) {
    if (c === 'adb' || existsSync(c)) return c;
  }
  throw new Error('找不到 adb：设 ANDROID_UI_ADB 指向 adb 可执行文件');
}

let ADB = null;
function adb(args, opts = {}) {
  ADB ??= findAdb();
  try {
    return execFileSync(ADB, ['-s', device(), ...args], {
      encoding: opts.raw ? 'buffer' : 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (e) {
    const msg = (e.stderr?.toString?.() || e.message || '').trim();
    throw new Error(`adb ${args.join(' ')} 失败：${msg}`);
  }
}

let _device = null;
function device() {
  if (_device) return _device;
  if (DEVICE_ENV) { _device = DEVICE_ENV; return _device; }
  ADB ??= findAdb();
  const out = execFileSync(ADB, ['devices'], { encoding: 'utf8' });
  const rows = out.split('\n').slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter((r) => r[1] === 'device');
  if (!rows.length) throw new Error('没有已连接的设备（adb devices 为空）');
  _device = rows[0][0];
  return _device;
}

/** 做端口转发。App 重启后 pid 会变，所以这里每次都重新查 pid。 */
function forward() {
  const pid = String(adb(['shell', 'pidof', APP_ID])).trim().split(/\s+/)[0];
  if (!pid) throw new Error(`${APP_ID} 没在运行（pidof 为空）`);
  const target = `localabstract:webview_devtools_remote_${pid}`;
  try { adb(['forward', '--remove', `tcp:${PORT}`]); } catch { /* 没建过，正常 */ }
  adb(['forward', `tcp:${PORT}`, target]);
  return { pid, target };
}

// ════════════════════════════════════════════════════════════════════════
// CDP 连接
// ════════════════════════════════════════════════════════════════════════

let ws = null;
let wsUrl = null;

async function listTargets() {
  const r = await fetch(`http://127.0.0.1:${PORT}/json/list`, { signal: AbortSignal.timeout(4000) });
  return r.json();
}

async function connect() {
  if (ws && ws.readyState === 1) return ws;

  let targets;
  try {
    targets = await listTargets();
  } catch {
    // 转发失效（App 重启是最常见的原因）—— 重做一次再试
    forward();
    targets = await listTargets();
  }

  const page = targets.find((t) => t.type === 'page' && /^https?:/.test(t.url || ''));
  if (!page) throw new Error(`没找到页面目标。现有 targets：${targets.map((t) => `${t.type}:${t.url}`).join(', ') || '(空)'}`);

  wsUrl = page.webSocketDebuggerUrl;
  const sock = new WebSocket(wsUrl);
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('CDP WebSocket 连接超时')), 8000);
    sock.addEventListener('open', () => { clearTimeout(t); res(); }, { once: true });
    sock.addEventListener('error', (e) => { clearTimeout(t); rej(new Error(`CDP WebSocket 失败：${e.message ?? e.type}`)); }, { once: true });
  });
  ws = sock;
  sock.addEventListener('close', () => { if (ws === sock) ws = null; });
  return ws;
}

let msgId = 0;
const pending = new Map();

function attach(sock) {
  sock.addEventListener('message', (e) => {
    let m; try { m = JSON.parse(e.data); } catch { return; }
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
  });
}

async function cdp(method, params) {
  const sock = await connect();
  attach(sock);
  const id = ++msgId;
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => { pending.delete(id); reject(new Error(`CDP ${method} 超时`)); }, 20000);
    pending.set(id, {
      resolve: (v) => { clearTimeout(t); resolve(v); },
      reject: (e) => { clearTimeout(t); reject(e); },
    });
    sock.send(JSON.stringify({ id, method, params }));
  });
}

async function evaluate(expression) {
  const r = await cdp('Runtime.evaluate', { expression, returnByValue: true });
  if (r.exceptionDetails) {
    throw new Error(r.exceptionDetails.exception?.description ?? JSON.stringify(r.exceptionDetails));
  }
  return r.result.value;
}

/**
 * 调探针。探针已驻留且版本对就直接 `run()`，否则把整份源码传过去重新安装。
 *
 * ⚠️ 探针是**函数表达式**（`function (CMD) {...}`），调用一次即自安装一次，
 * 不是常驻注入 —— 所以页面刷新后无需任何额外步骤，下一次调用会自动补上。
 */
async function probe(cmd) {
  const v = await evaluate('window.__DSH_UI_PROBE__ && window.__DSH_UI_PROBE__.__v');
  const expr = v === PROBE_VERSION
    ? `JSON.stringify(window.__DSH_UI_PROBE__.run(${JSON.stringify(cmd)}))`
    : `JSON.stringify((${PROBE_SRC})(${JSON.stringify(cmd)}))`;
  const raw = await evaluate(expr);
  return JSON.parse(raw);
}

// ════════════════════════════════════════════════════════════════════════
// 排版
// ════════════════════════════════════════════════════════════════════════

const inVp = (e) => e.inViewport && !e.offscreen;
const row = (e) => `  ${String(Math.round(e.center.x)).padStart(5)},${String(Math.round(e.center.y)).padStart(4)}  ` +
  `${(e.role || e.tag).padEnd(9)} "${String(e.text).slice(0, 52)}"`;

function renderScreen(d, opts = {}) {
  const L = [];
  L.push(`视口 ${d.viewport.w}×${d.viewport.h} dpr=${d.viewport.dpr}  |  ${d.count} 个元素` +
    (d.offscreen ? `（其中 ${d.offscreen} 个在视口外）` : ''));
  if (d.panel) L.push(`面板: ${d.panel.cls || d.panel.tag} —— 它**下面**那层点不动是正常的，不要关掉这个面板`);
  if (d.overlay) L.push(`⚠️ 遮挡层: ${d.overlay.cls || d.overlay.tag} —— 它下面的按钮都点不动，先关掉它`);
  if (d.note) L.push(`注: ${d.note}`);
  if (d.warning) L.push(`⚠️ ${d.warning}`);
  L.push('');
  const els = opts.all ? d.elements : d.elements.filter(inVp);
  if (!els.length) L.push('  （视口内没有可交互元素）');
  for (const e of els) L.push(row(e) + (inVp(e) ? '' : '  ⚠视口外'));
  if (!opts.all && d.elements.length > els.length) {
    L.push('');
    L.push(`（另有 ${d.elements.length - els.length} 个视口外元素未列出，加 all:true 可看全部）`);
  }
  return L.join('\n');
}

// ════════════════════════════════════════════════════════════════════════
// 工具
// ════════════════════════════════════════════════════════════════════════

/** 元素定位参数（探针 locate 的顺序：sel → testid → aria(精确) → text(子串) → prefix → role） */
const LOC = {
  text: { type: 'string', description: '按可见文案找（**子串匹配**，最常用）' },
  exact: { type: 'boolean', description: 'text 改为精确相等' },
  sel: { type: 'string', description: 'CSS 选择器（最精确，但类名带构建 hash）' },
  aria: { type: 'string', description: 'aria-label / title（**精确匹配**）' },
  testid: { type: 'string', description: 'data-testid' },
  prefix: { type: 'string', description: '文案前缀匹配' },
  role: { type: 'string', description: 'ARIA role，如 button / textbox' },
};

const TOOLS = [
  {
    name: 'ui_screen',
    description:
      '看手机当前屏幕上有什么。返回视口尺寸、遮挡层/面板状态，以及每个可点元素的**精确坐标**（CSS 像素）。\n' +
      '这是每次操作前的第一步：先看清楚再点。默认只列视口内的元素（视口外的元素仍在 DOM 里但点不到，所以不算数）。',
    inputSchema: {
      type: 'object',
      properties: {
        all: { type: 'boolean', description: '连视口外的元素一起列出（排查用）' },
        limit: { type: 'number', description: '最多列多少个（默认 300）' },
      },
      additionalProperties: false,
    },
    handler: async (a) => renderScreen(await probe({ action: 'dump', limit: a.limit ?? 300 }), a),
  },
  {
    name: 'ui_tap',
    description:
      '点一个元素。**会自动等它进入视口**（抽屉/弹窗动画没结束时中心点还在屏幕外，那时点了没反应）。\n' +
      '返回回执：ok / onTarget（是否真点到目标而不是被别的元素吃掉）/ dispatched（发出的原生事件）。\n' +
      '界面没反应时，回执里的 hint 会说明原因（被遮挡 / 在面板下面 / 在视口外）。',
    inputSchema: {
      type: 'object',
      properties: {
        ...LOC,
        waitMs: { type: 'number', description: '等它进入视口的最长时间，默认 8000ms' },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const want = a.text ?? a.aria ?? a.sel ?? a.testid ?? a.prefix;
      const match = (e) => (a.exact && a.text ? e.text === a.text : true);
      const spec = { ...a }; delete spec.waitMs;

      // ⚠️ 先轮询到「在视口内」再点 —— 否则事件发出去了、界面纹丝不动
      const deadline = Date.now() + (a.waitMs ?? 8000);
      let hit = null, last = null;
      while (Date.now() < deadline) {
        last = await probe({ action: 'dump', limit: 400 });
        hit = (last.elements ?? []).find((e) => inVp(e) && match(e) &&
          (!want || String(e.text).includes(want) || e.sel === want || e.aria === want));
        if (hit) break;
        await new Promise((r) => setTimeout(r, 250));
      }
      if (!hit) {
        const off = (last?.elements ?? []).find((e) => want && String(e.text).includes(want));
        return off
          ? `✗「${want}」在 DOM 里但没进视口（CSS ${Math.round(off.center.x)},${Math.round(off.center.y)}）—— 它所在的面板/状态没切出来`
          : `✗ 找不到「${want}」\n\n${renderScreen(last)}`;
      }
      const r = await probe({ action: 'tap', ...spec });
      const L = [
        `点了「${hit.text}」@(${Math.round(hit.center.x)},${Math.round(hit.center.y)})`,
        `ok=${r.ok}  onTarget=${r.onTarget}  covered=${r.covered ?? false}`,
        `dispatched=${JSON.stringify(r.dispatched)}`,
      ];
      if (r.hint) L.push(`hint: ${r.hint}`);
      if (r.blockedBy) L.push(`被挡: ${JSON.stringify(r.blockedBy).slice(0, 200)}`);
      return L.join('\n');
    },
  },
  {
    name: 'ui_type',
    description:
      '往输入框写字。`text` 用来**定位**输入框（例如「发消息或创建任务」），`value` 才是**要写进去的内容** —— ' +
      '这两个是不同的字段，别混。\n' +
      '⚠️ 写进 composer 会污染持久化的草稿状态，测完记得清空。',
    inputSchema: {
      type: 'object',
      properties: {
        ...LOC,
        value: { type: 'string', description: '要写进去的内容' },
        clear: { type: 'boolean', description: '先清空原有内容' },
        enter: { type: 'boolean', description: '写完按回车提交' },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const r = await probe({ action: 'type', ...a });
      const L = [
        `写入 ${JSON.stringify(a.value ?? '')} → how=${r.how}`,
        `element: ${JSON.stringify(r.element?.text ?? r.element?.sel ?? '?')}`,
        `读回的值: ${JSON.stringify(r.value)}`,
        `applied=${r.applied}（null 表示异步写入已发出，需下一步用 ui_screen/ui_read 复核）`,
      ];
      if (a.enter) L.push(`submitted=${JSON.stringify(r.submitted)}`);
      return L.join('\n');
    },
  },
  {
    name: 'ui_key',
    description: '按键（Enter / Escape / Tab / ArrowDown …）。不给定位参数就是发给当前焦点元素。',
    inputSchema: {
      type: 'object',
      properties: {
        key: { type: 'string', description: '键名，默认 Enter' },
        ...LOC,
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const r = await probe({ action: 'key', ...a });
      return `按键 ${a.key ?? 'Enter'}  dispatched=${JSON.stringify(r.dispatched)}\n目标: ${JSON.stringify(r.target?.text ?? '(当前焦点)')}`;
    },
  },
  {
    name: 'ui_scroll',
    description: '滚动。给 dy/dx 相对滚，给 to:"bottom"/"top" 滚到底/顶。可选 sel 指定容器。',
    inputSchema: {
      type: 'object',
      properties: {
        dy: { type: 'number', description: '纵向滚动量（正数向下）' },
        dx: { type: 'number', description: '横向滚动量' },
        to: { type: 'string', enum: ['top', 'bottom'], description: '直接滚到顶/底' },
        sel: { type: 'string', description: '要滚的容器（CSS 选择器）' },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const r = await probe({ action: 'scroll', ...a });
      return `容器 ${r.container}  ${r.before} → ${r.after}${r.moved === false ? '（没动）' : ''}`;
    },
  },
  {
    name: 'ui_read',
    description: '读界面上的**可见文本**（看不懂界面时用这个兜底），或读某个元素的 HTML。',
    inputSchema: {
      type: 'object',
      properties: {
        sel: { type: 'string', description: '容器选择器，默认整个 body' },
        limit: { type: 'number', description: '最多多少行，默认 200' },
        html: { type: 'boolean', description: '改为返回该元素的 HTML（排查用）' },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      if (a.html) {
        const r = await probe({ action: 'html', sel: a.sel, limit: a.limit ?? 2000 });
        return r.html;
      }
      const r = await probe({ action: 'text', sel: a.sel, limit: a.limit ?? 200 });
      return r.lines.join('\n') + (r.truncated ? `\n\n（共 ${r.total} 行，已截断）` : '');
    },
  },
  {
    name: 'ui_shot',
    description: '截手机当前屏幕（PNG）。用于人眼核对视觉效果 —— 元素清单看不出「长什么样」。',
    inputSchema: {
      type: 'object',
      properties: {
        save: { type: 'string', description: '另存到这个路径（可选）' },
      },
      additionalProperties: false,
    },
    handler: async (a) => {
      const buf = adb(['exec-out', 'screencap', '-p'], { raw: true });
      if (a.save) {
        const { writeFileSync } = await import('node:fs');
        writeFileSync(a.save, buf);
      }
      return {
        text: `截图 ${buf.length} 字节（PNG）${a.save ? `，已存 ${a.save}` : ''}`,
        image: { data: buf.toString('base64'), mimeType: 'image/png' },
      };
    },
  },
  {
    name: 'ui_eval',
    description:
      '逃生口：在手机 WebView 的**页面世界**里直接求值 JavaScript。前面七个工具覆盖不到时用。\n' +
      '⚠️ 页面世界的网络（fetch/XHR 到 /api/*）在 App 处于后台时会被冻结，别用它判断接口通不通。',
    inputSchema: {
      type: 'object',
      properties: { expr: { type: 'string', description: '要执行的 JS 表达式' } },
      required: ['expr'],
      additionalProperties: false,
    },
    handler: async (a) => {
      const v = await evaluate(`(() => { const r = (${a.expr}); return typeof r === 'string' ? r : JSON.stringify(r); })()`);
      return String(v);
    },
  },
];

// ════════════════════════════════════════════════════════════════════════
// MCP stdio 协议（JSON-RPC 2.0，换行分隔）
// ════════════════════════════════════════════════════════════════════════

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

async function handle(msg) {
  const { id, method, params } = msg;

  if (method === 'initialize') {
    // 回显客户端要的协议版本（MCP 规范：服务端支持就返回同一个）
    return {
      protocolVersion: params?.protocolVersion ?? '2025-06-18',
      capabilities: { tools: {} },
      serverInfo: { name: 'android-ui', version: '1.0.0' },
      instructions:
        '驱动一台真实安卓手机的界面。先 ui_screen 看清当前屏（拿到每个按钮的精确坐标），再 ui_tap/ui_type 操作。\n' +
        '这条路是 CDP 直连手机 WebView 的页面世界，不经过手机上的任何 AI，也不经过接口。',
    };
  }

  if (method === 'tools/list') {
    return {
      tools: TOOLS.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })),
    };
  }

  if (method === 'tools/call') {
    const tool = TOOLS.find((t) => t.name === params?.name);
    if (!tool) return { content: [{ type: 'text', text: `未知工具 ${params?.name}` }], isError: true };
    try {
      const out = await tool.handler(params.arguments ?? {});
      if (out && typeof out === 'object' && out.image) {
        return {
          content: [
            { type: 'text', text: out.text },
            { type: 'image', data: out.image.data, mimeType: out.image.mimeType },
          ],
        };
      }
      return { content: [{ type: 'text', text: String(out) }] };
    } catch (e) {
      return { content: [{ type: 'text', text: `✗ ${e.message ?? e}` }], isError: true };
    }
  }

  if (method === 'ping') return {};

  return undefined; // 通知（无 id）或未知方法
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', async (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    try {
      const result = await handle(msg);
      if (msg.id !== undefined && result !== undefined) send({ jsonrpc: '2.0', id: msg.id, result });
      else if (msg.id !== undefined) send({ jsonrpc: '2.0', id: msg.id, result: {} });
    } catch (e) {
      if (msg.id !== undefined) {
        send({ jsonrpc: '2.0', id: msg.id, error: { code: -32603, message: String(e.message ?? e) } });
      }
    }
  }
});
process.stdin.on('end', () => process.exit(0));
