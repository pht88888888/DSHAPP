/**
 * 连**真机** WebView 的 CDP，验证第 3 跳（原生 → 页面探针）在真机内核上真的跑得通。
 *
 * ## 为什么必须单独验这个
 *
 * `ui-probe-verify.mjs` 跑在 PC 的 Chrome 上，而真机用的是**设备自带 WebView 内核**
 * （本机 Android 10）。探针在页面世界里**不走任何转译**（原样 eval），所以
 * 「PC Chrome 上跑得通」推不出「真机内核上跑得通」—— 语法、API、行为都可能有差别。
 *
 * ## 调用方式必须与原生逐字一致
 *
 * 探针源码是 `function (CMD) {...}` 的**函数表达式**，`UiProbeBridge.evalOnce()`
 * （`UiProbeBridge.kt:315`）的调用形状是：
 *
 *     ($source)(${cmd.toString()})
 *
 * 也就是说**不是常驻注入**：每次调用现把整个源码求值一次，源码内部自带装填逻辑
 * （`ui-probe.js:1201` 检查 `__v !== VERSION` 就重装）。所以「页面里没有
 * `__DSH_UI_PROBE__`」在没调用过之前是**正常状态**，不是故障。
 *
 * 本脚本照抄这个形状，唯一差别是发起者由原生换成 CDP —— 落到的仍是同一个页面世界。
 *
 * 用法：
 *   adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>
 *   node tools/ui-probe-device-check.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const PROBE = join(HERE, '..', 'runtime', 'scripts', 'ui-probe.js');
const CDP = process.env.UIPROBE_CDP ?? 'http://127.0.0.1:19222/json/list';

const source = readFileSync(PROBE, 'utf8');
console.log(`探针源码: ${PROBE}  ${Buffer.byteLength(source)} 字节`);

// ⚠️ 版本号**从源码里读**，不写死。写死的后果是「改探针必须记得改这里」——
//   而漏改的表现是「本脚本报失败，但真机上一切正常」，最容易被误判成资产没生效。
const EXPECT_VERSION = Number(/var VERSION = (\d+);/.exec(source)?.[1]);
if (!Number.isInteger(EXPECT_VERSION)) {
	console.error('✗ 没能从 ui-probe.js 里解析出 VERSION');
	process.exit(1);
}
console.log(`源码声明 VERSION = ${EXPECT_VERSION}`);

const list = await (await fetch(CDP)).json();
// 只挑真正的页面：扩展后台页 / worker 的视口是 0，查不到元素。
const page = list.find((t) => t.type === 'page' && /^https?:/.test(t.url));
if (!page) {
  console.error('✗ 没找到 page 类型的调试目标。拿到的:', list.map((t) => `${t.type} ${t.url}`));
  process.exit(1);
}
console.log(`目标页面: ${page.url}\n`);

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
  return new Promise((resolve, reject) => {
    pending.set(myId, { resolve, reject });
    ws.send(JSON.stringify({ id: myId, method, params }));
  });
}

/**
 * 按原生形状调一次探针：`($source)(cmd)`，结果取 JSON 字符串。
 *
 * 原生侧 `evaluateJavascript` 的回调拿到的是**字符串**（system 侧还会被二次编码，
 * 见 `UiProbeBridge.parseProbeResult`）。这里让表达式自己 `JSON.stringify`，
 * 免得 CDP 的 returnByValue 把对象序列化得和真机不一样。
 */
async function probe(cmd) {
  const expr = `JSON.stringify((${source})(${JSON.stringify(cmd)}))`;
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) {
    const d = r.exceptionDetails.exception?.description ?? JSON.stringify(r.exceptionDetails);
    throw new Error(`探针在真机内核上抛错: ${d}`);
  }
  return JSON.parse(r.result.value);
}

let pass = 0;
const fails = [];
const ok = (c, label, extra) => {
  if (c) { pass++; console.log(`  ✓ ${label}`); }
  else { fails.push(label); console.log(`  ✗ ${label}${extra ? ` — ${extra}` : ''}`); }
};

// ── ① 真机内核上能不能跑起来 ──────────────────────────────────────────
console.log('① 探针在真机内核上启动');
let ping;
try {
  ping = await probe({ action: 'ping' });
} catch (e) {
  console.log(`  ✗ ${e.message}`);
  ws.close();
  process.exit(1);
}
ok(ping.ok === true, `ping 返回 ok（v=${ping.v}）`, JSON.stringify(ping).slice(0, 200));
ok(ping.v === EXPECT_VERSION, `探针版本与源码一致（源码 ${EXPECT_VERSION}，真机 ${ping.v}）`);
ok(ping.viewport && ping.viewport.w > 0, `读到视口 ${JSON.stringify(ping.viewport)}`);
ok(typeof ping.interactive === 'number' && ping.interactive > 0,
  `认出 ${ping.interactive} 个可交互元素`);

// ── ② 装填是否幂等（连调两次，第二次不该重装） ──────────────────────
const ping2 = await probe({ action: 'ping' });
ok(ping2.ok === true && ping2.v === ping.v, '二次调用仍然正常（装填幂等）');

// ── ③ 真机上真的能看见界面 ────────────────────────────────────────────
console.log('\n② 探针在真机上读界面');
const dump = await probe({ action: 'dump' });
ok(dump.ok === true, 'dump 返回 ok');
const els = dump.elements ?? [];
ok(els.length > 0, `dump 拿到 ${els.length} 个元素`);

const clickable = els.filter((e) => !e.covered && !e.offscreen);
ok(clickable.length > 0, `其中 ${clickable.length} 个当前可点（未被遮挡、未出视口）`);

// ── ④ 遮挡识别：语义最绕的一条 ────────────────────────────────────────
const ov = await probe({ action: 'overlay' });
ok(ov.ok === true, 'overlay 返回 ok');
ok(typeof ov.present === 'boolean' && typeof ov.blocking === 'boolean',
  `present/blocking 是两个独立字段（present=${ov.present} blocking=${ov.blocking}）`);
ok(ov.kind === null || ov.kind === 'mask' || ov.kind === 'panel',
  `kind 取值合法（${JSON.stringify(ov.kind)}）`);

// ── ⑤ 定位能力：模型真正要用的那一步 ──────────────────────────────────
console.log('\n③ 真机上定位一个按钮');
const target = clickable.find((e) => e.text && e.text.trim().length > 1);
if (target) {
  const spec = target.text.slice(0, 12);
  const f = await probe({ action: 'find', text: spec });
  // `find` 的返回形状是 `{ element, viewport }`（ui-probe.js:991），不是数组。
  const hit = f.element ?? null;
  ok(f.ok === true && !!hit, `find("${spec}") 命中`);
  if (hit) {
    ok(hit.center && typeof hit.center.x === 'number',
      `拿到 CSS 坐标 (${hit.center.x},${hit.center.y})`);
    ok(hit.ref && hit.sel, `带 ref(${hit.ref}) 与 sel`);
  } else {
    ok(false, 'find 没返回 element', JSON.stringify(f).slice(0, 300));
  }
} else {
  ok(false, '页面上找不到任何带文字的按钮，无法验证 find');
}

// ── ⑥ 把当前屏的按钮清单打出来（这就是本工具的落地价值） ──────────────
console.log('\n真机当前屏可点元素:');
for (const e of clickable.slice(0, 20)) {
  const t = (e.text || e.testid || e.aria || '').replace(/\s+/g, ' ').slice(0, 30);
  const sc = e.screenCenter ? `屏(${e.screenCenter.x},${e.screenCenter.y})` : '';
  console.log(`  "${t}"  CSS(${e.center.x},${e.center.y})  ${sc}  [${e.role}]`);
}
if (clickable.length > 20) console.log(`  … 另有 ${clickable.length - 20} 个`);

ws.close();
console.log(`\n${'═'.repeat(56)}`);
if (fails.length === 0) console.log(`真机探针全部通过：${pass} 项断言`);
else {
  console.log(`失败 ${fails.length} / 共 ${pass + fails.length}：`);
  fails.forEach((f) => console.log(`  ✗ ${f}`));
}
process.exit(fails.length === 0 ? 0 : 1);
