/**
 * 真机「持久化状态」快照 —— 为逐屏审计准备「跑完不留痕」。
 *
 * ## 为什么需要它
 *
 * 前端会持久化好几样**单向**的东西（进了就回不去）：
 * 当前会话、右侧边栏的开关、右侧边栏的**内容页**。
 * 逐屏审计必须走 `enter`（也就是点击）才能到那些屏，而点击会把这些状态推过去 ——
 * 用户在睡觉，醒来看到被改过的界面就是「被看出来了」。
 *
 * 重载**不足以**恢复它们（这正是 `ui-map-real-frontend.mjs` 里那三次栽跟头的根因），
 * 所以先摸清它们存在哪、能不能快照+还原。
 *
 * 用法：node tools/uiagent-state-snapshot.mjs [--restore <file>]
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(HERE, '..', 'runtime', 'scripts', 'ui-probe.js'), 'utf8');
const CDP = process.env.UIPROBE_CDP ?? 'http://127.0.0.1:19222/json/list';

const list = await (await fetch(CDP)).json();
const page = list.find((t) => t.type === 'page' && /^https?:/.test(t.url));
if (!page) { console.error('✗ 没有 page 目标'); process.exit(1); }
console.log(`页面: ${page.url}`);

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  const p = pending.get(m.id);
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

const DUMP = `(() => {
  const ls = {}, ss = {};
  for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); ls[k] = localStorage.getItem(k); }
  for (let i = 0; i < sessionStorage.length; i++) { const k = sessionStorage.key(i); ss[k] = sessionStorage.getItem(k); }
  return JSON.stringify({ ls, ss });
})()`;

const snap = JSON.parse(await ev(DUMP));
console.log(`\nlocalStorage  ${Object.keys(snap.ls).length} 个键`);
for (const [k, v] of Object.entries(snap.ls)) {
  console.log(`  ${k}  =  ${String(v).length} 字节  ${String(v).slice(0, 160).replace(/\n/g, ' ')}`);
}
console.log(`\nsessionStorage  ${Object.keys(snap.ss).length} 个键`);
for (const [k, v] of Object.entries(snap.ss)) {
  console.log(`  ${k}  =  ${String(v).length} 字节  ${String(v).slice(0, 160)}`);
}

// 侧栏 / 抽屉 / 面板的当前状态 —— 这些是「会被审计推过去」的显性状态
const uiState = await ev(`(() => {
  const txt = (el) => (el ? (el.innerText || el.textContent || '').trim().slice(0, 60) : null);
  const nav = document.querySelector('.mp-nav-btn');
  const navRect = nav ? nav.getBoundingClientRect() : null;
  return JSON.stringify({
    navBtn: txt(nav),
    navLeft: navRect ? Math.round(navRect.left) : null,
    bodyButtons: [...document.querySelectorAll('body > button')].map((b) => txt(b)),
    tabs: [...document.querySelectorAll('[role="tab"]')].map((b) => txt(b)),
    hasComposer: !!document.querySelector('[role="textbox"]'),
  });
})()`);
console.log(`\n界面状态:\n${JSON.stringify(JSON.parse(uiState), null, 2)}`);

const out = process.argv[3] ?? join(HERE, '..', 'tools', 'uiagent-state-snapshot.json');
writeFileSync(out, JSON.stringify(snap, null, 2), 'utf8');
console.log(`\n已写出快照: ${out}`);
ws.close();
