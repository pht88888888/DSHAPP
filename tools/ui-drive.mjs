#!/usr/bin/env node
/**
 * 真机 UI 驱动 —— **给我（PC 侧的 agent）用的**，不经过设备上的 agent。
 *
 *     PC(我) ──CDP WebSocket──▶ 手机 WebView 页面世界 ──探针──▶ DOM
 *
 * 这条路与设备上那个 `ui` 工具插件**互不相干**：那个是给设备上的 AI 用的，
 * 走的是「文件信箱 + 原生轮询」；我这条直连 CDP，没有中间环节。
 *
 * ## 前置（App 重启后必须重做）
 *
 *     adb shell am start -n com.dsh.android/.MainActivity        # ⚠️ 必须在前台
 *     adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>
 *
 * ⚠️ **App 在后台时页面网络全挂**（实测连 `favicon.ico` 都不回，DOM 却 5ms 正常），
 * 所以任何取 DOM 之外东西的操作都会超时 —— 先 `am start` 拉到前台。
 *
 * ## 用法
 *
 *     node tools/ui-drive.mjs dump [--all]            列当前屏元素（默认只列视口内的）
 *     node tools/ui-drive.mjs find <子串>             按文本子串找元素
 *     node tools/ui-drive.mjs tap <文本> [--exact]    点它（**自动等动画结束**）
 *     node tools/ui-drive.mjs wait <文本> [--ms 8000] 等它进入视口
 *     node tools/ui-drive.mjs eval <js>               在页面世界求值（逃生口）
 *
 * ## ⚠️ 两条踩过的坑，已内建
 *
 * 1. **动画未结束时点抽屉内元素无效**：元素中心点还在视口外（实测 CSS -86），
 *    探针回执 `offscreen:true`，点击事件发出去了但界面没反应。
 *    所以 `tap` 先轮询到 `center` 进入视口再点。
 * 2. **`dump` 默认滤掉视口外元素**：抽屉收起时里面的元素仍在 DOM 里，
 *    用「元素存在」当判据会误判（实测栽过一次）。要全量加 `--all`。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const CDP = process.env.UIPROBE_CDP ?? 'http://127.0.0.1:19222/json/list';

const argv = process.argv.slice(2);
const cmd = argv[0];
const rest = argv.slice(1).filter((a) => !a.startsWith('--'));
const flag = (name) => argv.includes(`--${name}`);
const opt = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};

const list = await (await fetch(CDP)).json();
const page = list.find((t) => t.type === 'page' && /^https?:/.test(t.url));
if (!page) { console.error('✗ 没有 page 目标（App 在跑吗？CDP 转发做了吗？）'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data); const p = pending.get(m.id);
  if (p) { pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result); }
});
await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej); });
function send(method, params) {
  const myId = ++id;
  return new Promise((resolve, reject) => { pending.set(myId, { resolve, reject }); ws.send(JSON.stringify({ id: myId, method, params })); });
}
async function ev(expr) {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? JSON.stringify(r.exceptionDetails));
  return r.result.value;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const src = readFileSync(join(HERE, '..', 'runtime', 'scripts', 'ui-probe.js'), 'utf8');
const probe = async (c) => JSON.parse(await ev(`JSON.stringify((${src})(${JSON.stringify(c)}))`));

const inVp = (e) => e.inViewport && !e.offscreen;
const brief = (e) => `${String(Math.round(e.center.x)).padStart(5)},${String(Math.round(e.center.y)).padStart(4)}  ${(e.role || e.tag).padEnd(9)} "${String(e.text).slice(0, 46)}"`;

/** 轮询直到 [pred] 成立（或超时）。 */
async function until(label, pred, ms = 8000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < ms) {
    const d = await probe({ action: 'dump', limit: 400 });
    last = d;
    const hit = (d.elements ?? []).find(pred);
    if (hit) return { hit, d, waited: Date.now() - t0 };
    await sleep(300);
  }
  return { hit: null, d: last, waited: Date.now() - t0 };
}

if (cmd === 'dump') {
  const d = await probe({ action: 'dump', limit: 400 });
  const els = flag('all') ? d.elements : d.elements.filter(inVp);
  console.log(`页面: ${d.url}`);
  console.log(`视口 ${d.viewport.w}×${d.viewport.h} dpr=${d.viewport.dpr}  |  ${els.length} 个元素${flag('all') ? '（含视口外）' : '（仅视口内）'}\n`);
  for (const e of els) console.log('  ' + brief(e));
} else if (cmd === 'find') {
  const q = rest.join(' ');
  const d = await probe({ action: 'dump', limit: 400 });
  const hits = (d.elements ?? []).filter((e) => String(e.text).includes(q));
  console.log(`含「${q}」的元素 ${hits.length} 个：`);
  for (const e of hits) console.log(`  ${brief(e)}${inVp(e) ? '' : '  ⚠视口外'}`);
} else if (cmd === 'wait') {
  const want = rest.join(' ');
  const { hit, waited } = await until(want, (e) => String(e.text).includes(want), +opt('ms', 8000));
  console.log(hit ? `✓ ${waited}ms 后「${want}」进入视口 @(${Math.round(hit.center.x)},${Math.round(hit.center.y)})` : `✗ ${waited}ms 内没等到「${want}」进入视口`);
  process.exitCode = hit ? 0 : 1;
} else if (cmd === 'tap') {
  const want = rest.join(' ');
  const exact = flag('exact');
  const match = (e) => (exact ? e.text === want : String(e.text).includes(want));
  // ⚠️ 先等到它进入视口再点 —— 动画未结束时中心点还在视口外，点了没反应
  const { hit, waited } = await until(want, (e) => match(e) && inVp(e), +opt('ms', 8000));
  if (!hit) {
    const d = await probe({ action: 'dump', limit: 400 });
    const off = (d.elements ?? []).find(match);
    console.log(off
      ? `✗ 「${want}」在 DOM 里但 ${waited}ms 内没进视口（CSS ${Math.round(off.center.x)},${Math.round(off.center.y)}）—— 它所在的面板/状态没切出来`
      : `✗ 找不到「${want}」（${waited}ms）`);
    process.exit(1);
  }
  const r = await probe({ action: 'tap', text: want, ...(exact ? { exact: true } : {}) });
  console.log(`✓ 点了「${hit.text}」@(${Math.round(hit.center.x)},${Math.round(hit.center.y)})  等了 ${waited}ms 进视口`);
  console.log(`  ok=${r.ok} onTarget=${r.onTarget} covered=${r.covered} dispatched=${JSON.stringify(r.dispatched)}`);
  if (!r.ok) { console.log('  回执:', JSON.stringify(r).slice(0, 300)); process.exit(1); }
} else if (cmd === 'eval') {
  const out = await ev(rest.join(' '));
  console.log(typeof out === 'string' ? out : JSON.stringify(out, null, 2));
} else {
  console.log('用法: dump [--all] | find <子串> | tap <文本> [--exact] | wait <文本> | eval <js>');
  process.exitCode = 1;
}
ws.close();
