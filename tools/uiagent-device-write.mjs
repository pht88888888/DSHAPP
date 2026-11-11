/**
 * 真机端到端**写动作**：让设备上的 agent 用 `ui` 工具真的去点界面，并核对界面确实变了。
 *
 * ## 为什么需要这个脚本（它和另外两个的分工）
 *
 * | 脚本 | 验的是 |
 * |---|---|
 * | `ui-probe-device-check.mjs` | 探针在真机内核上能跑（**只读**） |
 * | `uiagent-device-e2e.mjs` | 插件↔原生信箱通、坐标换算对（**只读 dump**） |
 * | 本脚本 | **写动作**：模型点一下，界面真的变了 |
 *
 * 前两个都只调 `dump`。而「让 AI 操作 App」这件事的成败全在写动作上，
 * 只读全绿完全不能推出「点得动」—— 探针里 `tap` 有一整套失败模式
 * （遮挡 / 屏幕外 / 事件被上层吃掉），每一个都返回 `ok:true` 而界面纹丝不动。
 *
 * ## 为什么选「打开导航」这个动作
 *
 * 它**可逆**：点一下侧栏滑入、再点一下（或点「收起侧边栏」）滑出。
 * 测试必须能在结束时把现场恢复原状 —— 否则用户下次拿起手机看到的是被改过的界面。
 *
 * ## 判据为什么不是「agent 说成功了」
 *
 * agent 的原话只是**它读到的回执**。真正的判据是**界面本身**：
 * 经 CDP 用探针读侧栏元素在视口里的坐标（收起时是负值，滑入后变成正值）。
 * 两者都要看：前者证明链路通，后者证明界面真的动了。
 *
 * ⚠️ 本脚本会**改动真机界面**，但全程可逆，结束时恢复原状（末尾会核对）。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const PROBE = join(HERE, '..', 'runtime', 'scripts', 'ui-probe.js');
const source = readFileSync(PROBE, 'utf8');
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
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 按原生形状调探针（`($source)(cmd)`）—— 与 `UiProbeBridge.evalOnce` 同形。 */
async function probe(cmd) {
  return JSON.parse(await ev(`JSON.stringify((${source})(${JSON.stringify(cmd)}))`));
}

let seq = 0;
async function api(endpoint, args = {}, timeoutMs = 20000) {
  const key = `__R${++id}_${seq++}`;
  const env = { type: 'client-request', rpcId: `${Date.now()}-${Math.random().toString(16).slice(2)}`, method: endpoint, payload: { args } };
  await ev(`(() => {
    window.${key} = null;
    const ac = new AbortController();
    setTimeout(() => ac.abort(), ${timeoutMs - 1500});
    fetch('api/' + ${JSON.stringify(endpoint)}, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: ${JSON.stringify(JSON.stringify(env))}, signal: ac.signal
    }).then(async (r) => { window.${key} = { status: r.status, body: await r.text() }; })
      .catch((e) => { window.${key} = { status: 0, body: String(e) }; });
    return 1;
  })()`);
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    await sleep(250);
    const v = await ev(`window.${key} ? JSON.stringify(window.${key}) : null`);
    if (v) return JSON.parse(v);
  }
  return { status: 0, body: '超时' };
}
const rpc = (r) => JSON.parse(r.body).result;
const proj = (r) => rpc(r)?.value?.values;

let pass = 0;
const fails = [];
const ok = (c, label, extra) => {
  if (c) { pass++; console.log(`  ✓ ${label}`); }
  else { fails.push(label); console.log(`  ✗ ${label}${extra ? ` — ${extra}` : ''}`); }
};

/** 读侧栏「设置」按钮的 CSS 中心 x —— 抽屉收起时是负值，滑入后为正值。 */
async function drawerX() {
  const d = await probe({ action: 'dump', limit: 120 });
  const s = (d.elements ?? []).find((e) => e.text === '设置');
  return s ? s.center.x : null;
}

// ── ① 起点：确认抽屉是收起的 ─────────────────────────────────────────
console.log('\n① 起点状态');
let x0 = await drawerX();
console.log(`     侧栏「设置」按钮 CSS x = ${x0}`);
if (x0 !== null && x0 >= 0) {
  console.log('     抽屉当前是开的，先点「收起侧边栏」关回去');
  await probe({ action: 'tap', text: '收起侧边栏' });
  await sleep(1200);
  x0 = await drawerX();
  console.log(`     关回后 x = ${x0}`);
}
ok(x0 !== null && x0 < 0, `起点：抽屉是收起的（x=${x0} 为负）`, String(x0));

// ── ② 让 agent 用 ui 工具点一下 ──────────────────────────────────────
console.log('\n② 让设备上的 agent 用 ui 工具执行写动作');
const sl = await api('session/list', { _request: {} });
const items = rpc(sl)?.value?.items ?? [];
ok(items.length > 0, `有 ${items.length} 个会话可用`);
if (!items.length) { ws.close(); process.exit(1); }
const sessionId = items[0].sessionId;
console.log(`     用 ${sessionId}`);

const before = proj(await api('session/projections', { request: { sessionId } }));
const turnsBefore = before?.sessionStats?.turns ?? 0;

const prompt = [
  '请依次调用 ui 工具完成两件事，不要用别的工具：',
  '1) {"action":"tap","text":"打开导航"}  —— 点开侧边栏抽屉',
  '2) {"action":"dump"}  —— 看看界面变成了什么样',
  '然后把第 1 步的 tap 回执（ok / onTarget / dispatched / covered 这几个字段）',
  '和第 2 步里「设置」这个元素的 center 坐标，原样贴出来。',
].join('\n');
const pr = await api('session/prompt', {
  request: {
    requestId: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    sessionId,
    mode: 'queue',
    content: [{ type: 'text', text: prompt }],
    clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  },
}, 30000);
console.log(`     session/prompt → HTTP ${pr.status}`);
ok(rpc(pr)?.ok === true, 'prompt 被接受');

// ── ③ 等 agent 干完 ─────────────────────────────────────────────────
console.log('\n③ 等 agent 执行（最多 3 分钟）');
const deadline = Date.now() + 180000;
let after = null;
let turn = null;
while (Date.now() < deadline) {
  await sleep(5000);
  const pj = proj(await api('session/projections', { request: { sessionId } }, 15000));
  if ((pj?.sessionStats?.turns ?? 0) > turnsBefore) {
    after = pj;
    turn = (pj.turnOutline ?? []).at(-1);
    if (turn?.response) break;
  }
  process.stdout.write('.');
}
console.log('');
ok(!!after, `轮数已从 ${turnsBefore} 推进`);
if (!after) { ws.close(); process.exit(1); }

const resp = String(turn?.response ?? '');
console.log(`     stats: steps=${after.sessionStats?.steps} toolMs=${after.sessionStats?.toolMs}`);
console.log(`     回复: ${resp.replace(/\s+/g, ' ').slice(0, 500)}`);

// ── ④ 链路判据：agent 拿到的 tap 回执 ────────────────────────────────
//
// ⚠️ **判据必须取自 DOM，不能取自模型的回复。**
//    第一版让模型「原样贴出回执」，它只抄了 `fmtElement` 那一行就把
//    `onTarget` 那行省了 —— 拿回复当证据会得出「渲染层没生效」的错误结论，
//    而真相是模型没抄。工具结果在聊天界面里是**渲染成 DOM** 的，
//    DOM 里的文本就是模型实际收到的 `message`，所以直接读 DOM。
console.log('\n④ 链路判据（页面 DOM 里渲染出来的工具结果原文）');
const rendered = await ev(`(() => {
  const kw = '已点击';
  const all = [...document.querySelectorAll('*')].filter((el) => (el.textContent || '').includes(kw));
  if (!all.length) return '';
  const deepest = all.filter((el) => ![...el.children].some((c) => (c.textContent || '').includes(kw)));
  return (deepest[0] ?? all[all.length - 1]).innerText || '';
})()`);
console.log(`     DOM 原文:\n${String(rendered).split('\n').map((l) => `       ${l}`).join('\n')}`);
ok(after.sessionStats?.toolMs > 0, `工具真的执行了（toolMs=${after.sessionStats?.toolMs}ms）`);
ok(rendered.length > 0, '页面里渲染出了 tap 的工具结果卡片');
// ⚠️ 这条同时证明两件事：渲染层把 `onTarget` 说了出来（本轮新加），
//    且拿到的确实是 tap 的真实回执而非模型编造。
ok(/onTarget:true/.test(rendered), '★ 渲染出的回执里有 onTarget:true（新渲染层生效）', String(rendered).slice(0, 200));
ok(/不是被上层吃掉的点击/.test(rendered), '★ 明确排除了「被上层吃掉」这一种失败');
ok(/事件: pointerdown/.test(rendered), '回执里带真实事件序列（不是编的）');

// ── ⑤ 界面判据：抽屉真的滑进来了吗 ──────────────────────────────────
console.log('\n⑤ 界面判据（不信回执，直接量界面）');
await sleep(1200);
const x1 = await drawerX();
console.log(`     侧栏「设置」按钮 CSS x = ${x0} → ${x1}`);
ok(x1 !== null && x1 >= 0, `★ 抽屉真的滑入视口了（x 从 ${x0} 变成 ${x1}）`, String(x1));

// ── ⑥ 收尾：把抽屉关回去，恢复现场 ──────────────────────────────────
console.log('\n⑥ 恢复现场');
await probe({ action: 'tap', text: '收起侧边栏' });
await sleep(1500);
let x2 = await drawerX();
if (x2 !== null && x2 >= 0) {
  // 「收起侧边栏」可能只在抽屉开着时才在视口内，退一步点主界面的「打开导航」开关
  await probe({ action: 'tap', text: '打开导航' });
  await sleep(1500);
  x2 = await drawerX();
}
console.log(`     恢复后 CSS x = ${x2}`);
ok(x2 !== null && x2 < 0, '现场已恢复（抽屉回到收起状态）', String(x2));

console.log(`\n${'═'.repeat(58)}`);
if (fails.length === 0) console.log(`真机写动作端到端全部通过：${pass} 项断言`);
else {
  console.log(`失败 ${fails.length} / 共 ${pass + fails.length}：`);
  fails.forEach((f) => console.log(`  ✗ ${f}`));
}
ws.close();
process.exit(fails.length === 0 ? 0 : 1);
