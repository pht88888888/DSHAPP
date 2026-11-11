#!/usr/bin/env node
/**
 * 按图谱逐屏截图 —— 给「无限画布」提供素材。
 *
 * 图谱 `tools/ui-map-real-frontend.json` 里每屏都带一条 `enter` 步骤链
 * （从基态一路点到该屏），本脚本照那条链走一遍，每屏截一张 PNG。
 *
 *     node tools/ui-map-shots.mjs [--out docs/ui-canvas/shots]
 *
 * ## 与图谱脚本的关系
 *
 * 图谱脚本（`ui-map-real-frontend.mjs`）负责**采集元素**并做断言；本脚本只负责
 * **截图**，不重复它的断言逻辑。两者共用同一套「基态还原 + 逐屏进入」语义：
 *
 * 1. 先快照 localStorage（跑完原样写回 —— 不留痕）
 * 2. 每屏从 `hero` 重来（`Page.navigate` + 还原 localStorage）
 * 3. 照 `enter` 链逐节点点，每步都用探针确认 `onTarget`
 * 4. 截屏（`adb exec-out screencap`，比 CDP 截图更贴近用户实际看到的）
 *
 * ## 前置
 *
 *     adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>
 *     # App 必须在前台，否则页面网络冻结（截图本身不受影响，但页面可能不刷新）
 */

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');

const argv = process.argv.slice(2);
const outIdx = argv.indexOf('--out');
const OUT = outIdx >= 0 ? argv[outIdx + 1] : join(ROOT, 'docs', 'ui-canvas', 'shots');

const CDP = process.env.UIPROBE_CDP ?? 'http://127.0.0.1:19222/json/list';
const ADB = process.env.ANDROID_UI_ADB ??
  join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const DEVICE = process.env.ANDROID_UI_DEVICE ?? '';
const APP_ID = process.env.ANDROID_UI_APP ?? 'com.dsh.android';

const MAP = JSON.parse(readFileSync(join(ROOT, 'tools', 'ui-map-real-frontend.json'), 'utf8'));
const PROBE_SRC = readFileSync(join(ROOT, 'runtime', 'scripts', 'ui-probe.js'), 'utf8');

/**
 * 图谱之外的补充屏。
 *
 * ⚠️ 图谱（`ui-map-real-frontend.json`）只收了「设置」的 7 个子页，但设置列表实际
 *    有 9 项 —— `AI 回复风格` 与 `多模态配置` 没有 `enter` 链，所以不在图谱里。
 *    画布要「每个界面都放上去」，就得在这里补上。
 *
 * 格式与图谱里的 `screen` 完全一致，进入链同样是「基态 → 点设置 → 点该项」。
 */
const EXTRA = [
  {
    id: 'settings-style',
    title: '设置 › AI 回复风格',
    enter: [{ spec: { text: '设置' } }, { spec: { text: 'AI 回复风格' } }],
  },
  {
    id: 'settings-multimodal',
    title: '设置 › 多模态配置',
    enter: [{ spec: { text: '设置' } }, { spec: { text: '多模态配置' } }],
  },
];

const SCREENS = [...MAP.screens, ...EXTRA];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function adb(args, raw = false) {
  const full = DEVICE ? ['-s', DEVICE, ...args] : args;
  return execFileSync(ADB, full, { encoding: raw ? 'buffer' : 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

// ── CDP ─────────────────────────────────────────────────────────────────
const list = await (await fetch(CDP)).json();
const page = list.find((t) => t.type === 'page' && /^https?:/.test(t.url || ''));
if (!page) { console.error('✗ 没有 page 目标'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data); const p = pending.get(m.id);
  if (p) { pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result); }
});
await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej); });

function send(method, params) {
  const myId = ++id;
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => { pending.delete(myId); reject(new Error(`CDP ${method} 超时`)); }, 30000);
    pending.set(myId, { resolve: (v) => { clearTimeout(t); resolve(v); }, reject: (e) => { clearTimeout(t); reject(e); } });
    ws.send(JSON.stringify({ id: myId, method, params }));
  });
}
async function ev(expr) {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? JSON.stringify(r.exceptionDetails));
  return r.result.value;
}
const probe = async (cmd) => JSON.parse(await ev(`JSON.stringify((${PROBE_SRC})(${JSON.stringify(cmd)}))`));

const URL_PAGE = page.url;
const LS_DUMP = `(() => { const o = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); o[k] = localStorage.getItem(k); } return JSON.stringify(o); })()`;
const ORIGINAL_LS = JSON.parse(await ev(LS_DUMP));
console.log(`localStorage 快照: ${Object.keys(ORIGINAL_LS).length} 个键（跑完原样写回）`);

/** 还原到基态（hero）并重载页面。 */
async function resetToBase() {
  await ev(`(() => {
    var keep = ${JSON.stringify(ORIGINAL_LS)};
    for (var i = localStorage.length - 1; i >= 0; i--) localStorage.removeItem(localStorage.key(i));
    for (var k in keep) localStorage.setItem(k, keep[k]);
    return 1;
  })()`);
  await send('Page.navigate', { url: URL_PAGE });
  for (let i = 0; i < 60; i++) {
    await sleep(400);
    try {
      const n = await ev(`document.querySelectorAll('*').length`);
      if (n > 50) { await sleep(700); return; }
    } catch { /* 导航中 */ }
  }
  console.log('  ⚠️ 前端似乎没挂载起来');
}

/** 关掉空遮罩（首启向导可能多步）。 */
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

/** 收起可能残留的面板（右侧边栏状态会被持久化，重载后可能还开着）。 */
async function closePanels() {
  for (let i = 0; i < 3; i++) {
    const d = await probe({ action: 'dump', limit: 400 });
    const closer = d.elements.find((e) => /收起右侧边栏|收起侧边栏/.test(e.text || ''));
    if (!closer) return;
    await probe({ action: 'tap', aria: closer.text });
    await sleep(600);
  }
}

/** 截屏存盘。 */
function shoot(name) {
  const buf = adb(['exec-out', 'screencap', '-p'], true);
  const file = join(OUT, `${name}.png`);
  writeFileSync(file, buf);
  return buf.length;
}

// ── 主流程 ───────────────────────────────────────────────────────────────
mkdirSync(OUT, { recursive: true });
console.log(`输出目录: ${OUT}\n`);

const manifest = [];
for (const sc of SCREENS) {
  const name = sc.id;
  process.stdout.write(`[${name}] `);
  try {
    await resetToBase();
    await dismissOverlays();
    await closePanels();

    // 照 enter 链逐节点
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
    if (!ok) { manifest.push({ id: name, title: sc.title, shot: null, error: 'enter 链有一步没点通' }); continue; }

    await sleep(400);
    const bytes = shoot(name);
    const title = await ev(`document.title`);
    console.log(`✓ ${bytes} B  (${sc.title || ''})`);
    manifest.push({ id: name, title: sc.title, shot: `${name}.png`, bytes });
  } catch (e) {
    console.log(`\n  ✗ ${e.message}`);
    manifest.push({ id: name, title: sc.title, shot: null, error: e.message });
  }
}

// 还原用户状态
console.log('\n还原 localStorage...');
await ev(`(() => {
  var keep = ${JSON.stringify(ORIGINAL_LS)};
  for (var i = localStorage.length - 1; i >= 0; i--) localStorage.removeItem(localStorage.key(i));
  for (var k in keep) localStorage.setItem(k, keep[k]);
  return 1;
})()`);
await send('Page.navigate', { url: URL_PAGE });
await sleep(2500);
await dismissOverlays();

const after = JSON.parse(await ev(LS_DUMP));
const same = Object.keys(ORIGINAL_LS).sort().join('|') === Object.keys(after).sort().join('|') &&
  Object.keys(ORIGINAL_LS).every((k) => ORIGINAL_LS[k] === after[k]);
console.log(same ? '✓ localStorage 与快照一致' : `⚠️ localStorage 有差异：${JSON.stringify(after).slice(0, 200)}`);

writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ url: URL_PAGE, viewport: MAP.viewport, dpr: MAP.dpr, shots: manifest }, null, 2));
const good = manifest.filter((m) => m.shot).length;
console.log(`\n截图完成: ${good}/${manifest.length} 张`);
ws.close();
