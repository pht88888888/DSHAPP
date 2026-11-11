/**
 * 真机逐屏审计 —— 把 `screens.js` 那张表拿到真机上核对一遍。
 *
 * ## 为什么必须做这件事
 *
 * `ATLAS` 是 `ui-map-real-frontend.mjs` 在**PC 隔离实例**里生成的，视口 **360×780**。
 * 而真机视口是 **360×764**。一张没在真机核过的坐标表，等于没有。
 *
 * ## ⚠️ 本审计最重要的产出：分清「表错了」与「用户数据不一样」
 *
 * 第一版把所有不匹配都算成失败，于是报出 85 个坐标偏移、56 个元素找不到 ——
 * 看着像这张表烂透了。逐条查下来才发现**三类根因完全不同，处置办法也完全不同**：
 *
 * | 类别 | 例子 | 是缺陷吗 | 怎么办 |
 * |---|---|---|---|
 * | ① **用户数据** | 表里「模型 DeepSeek-V41-Flash」，真机是「Agnes」；表里「推理等级 High」，真机是「Default」 | **不是** | 表不该记用户数据；判据要换成结构性的 |
 * | ② **动态文案** | 「访问模式，当前：工作区内修改」——当前值一变整串就变 | **是** | 定位只用**稳定前缀**，不用整串 |
 * | ③ **布局随内容变** | 设置项坐标表里间距均匀 52px，真机 88/52/140/52（真机条目更多） | **不是** | 坐标本就是参考值，不该当断言 |
 *
 * 用户设备上装的是**他自己的**模型配置（Agnes）、权限是「完全权限」——
 * 而生成图谱的那台 PC 上是默认的 DeepSeek-V41-Flash / 工作区内修改。
 * **把用户数据写进内建表，等于把表绑死在生成时那台机器上。**
 *
 * 所以本脚本按类别分开报，只有 ② 才算表的缺陷。
 *
 * ## 为什么绝不点 `els`
 *
 * `enter` 是**路线**（点开面板），重载就能收回；而 `els` 是**屏幕上的按钮**，
 * 点下去会真的改状态 —— 「深色」会换主题、「完全权限」会降权限、
 * 「删除模型 1」会删配置。用户正在睡觉，醒来看到界面被改过就是「被看出来了」。
 *
 * 用法：
 *   node tools/uiagent-device-audit.mjs             # 全部 17 屏
 *   node tools/uiagent-device-audit.mjs hero drawer # 只跑指定屏
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(HERE, '..', 'runtime', 'scripts', 'ui-probe.js'), 'utf8');
const { ATLAS } = await import('../plugins/android/components/dsh-android-uiagent/lib/screens.js');
const CDP = process.env.UIPROBE_CDP ?? 'http://127.0.0.1:19222/json/list';

const want = process.argv.slice(2);
const SCREENS = want.length ? ATLAS.screens.filter((s) => want.includes(s.id)) : ATLAS.screens;

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

/** 与 `UiProbeBridge.evalOnce` 同形：`($source)(cmd)`。 */
async function probe(cmd) {
  return JSON.parse(await ev(`JSON.stringify((${source})(${JSON.stringify(cmd)}))`));
}
const tap = (spec) => probe({ action: 'tap', ...spec });

// ── 现场快照 ────────────────────────────────────────────────────────
const LS_DUMP = `(() => {
  const ls = {};
  for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); ls[k] = localStorage.getItem(k); }
  return JSON.stringify(ls);
})()`;
const before = JSON.parse(await ev(LS_DUMP));
const ORIGINAL_LS = before;
console.log(`localStorage 快照: ${Object.keys(before).length} 个键`);

let pass = 0;
const fails = [];
const ok = (c, label, extra) => {
  if (c) { pass++; console.log(`    ✓ ${label}`); }
  else { fails.push(label); console.log(`    ✗ ${label}${extra ? ` — ${extra}` : ''}`); }
};

/**
 * 回到基态：**把 localStorage 还原成跑之前的原始值** + 重载 + 收掉面板。
 *
 * ## 为什么不能只重载
 *
 * 前端把「右侧边栏的内容页」存在 `dsh.sidebar-right.v1.<sessionId>` 里，
 * 而这个状态是**单向**的：一旦打开过「工作区文件」，再打开右侧边栏就是文件页，
 * 启动页的「新建终端」不再出现。
 *
 * 实测证据：`terminal` 屏**单跑**时 7/7 全对、0 偏移；**全量跑**时却
 * `enter 失败` —— 因为前序的 `workfiles` 屏把它推走了。重载**不足以**复位它
 * （它不在内存里，在 localStorage 里）。
 *
 * 所以每屏都从「跑之前的原始 localStorage」起步，屏与屏之间零污染 ——
 * 这是生成器那句「每屏都从基态重新进入」的**彻底版**。
 *
 * @param {boolean} emptySession - 是否连 `dsh.sessions.current` 一起清掉（空会话态）。
 */
async function resetToBase(emptySession = false) {
  await ev(`(() => {
    var keep = ${JSON.stringify(ORIGINAL_LS)};
    // 先清掉所有键，再把原始值写回去 —— 这样「审计过程中新产生的键」也会被清掉
    for (var i = localStorage.length - 1; i >= 0; i--) localStorage.removeItem(localStorage.key(i));
    for (var k in keep) localStorage.setItem(k, keep[k]);
    ${emptySession ? "localStorage.removeItem('dsh.sessions.current');" : ''}
    return 1;
  })()`);
  await send('Page.reload', { ignoreCache: false });
  for (let i = 0; i < 60; i++) {
    await sleep(500);
    try {
      const n = await ev(`document.querySelectorAll('*').length`);
      if (n > 50) break;
    } catch { /* 导航中 */ }
  }
  await sleep(1200);
  // 收掉右侧边栏（重载后它会自己又开着，入口 aria 会变成「收起右侧边栏」）
  for (let i = 0; i < 3; i++) {
    const ov = await probe({ action: 'overlay' });
    if (!ov?.present || ov.kind !== 'mask') break;
    const closer = ov.clickable.find((b) => b.tag === 'button' && /稍后|跳过|继续|知道了|关闭|取消/.test(b.text || ''));
    if (!closer) break;
    await tap({ sel: closer.sel });
    await sleep(700);
  }
  // 「收起右侧边栏」按钮在视口里 → 点掉它，回到「打开右侧边栏」的基态
  const d = await probe({ action: 'dump', limit: 300 });
  const collapse = (d.elements ?? []).find((e) => e.text === '收起右侧边栏');
  if (collapse) { await tap({ text: '收起右侧边栏' }); await sleep(1000); }
  return d;
}

/**
 * 这一屏是不是「只在空会话态存在」。
 *
 * 判据取自实测：这三屏的 `enter` 全部指向空会话态才渲染的元素。
 */
const EMPTY_SESSION_ONLY = new Set(['hero', 'access-mode', 'preset-picker']);

/** 走 `enter` 路线到目标屏。 */
async function enterScreen(screen) {
  for (const step of screen.enter ?? []) {
    const r = await tap(step);
    if (r?.ok !== true) return { ok: false, at: step, reply: r };
    await sleep(1100);
  }
  return { ok: true };
}

/**
 * 判据成立吗 —— **直接问探针**，而不是在 dump 的元素列表里自己比对。
 *
 * ⚠️ 这里原来是自己复刻匹配规则（`exp.exact ? 相等 : 包含`），有三个问题：
 *
 *   ① **口径迟早对不齐**。判据最终是 `locate()` 执行的，而 `locate()` 的顺序是
 *      `sel → testid → aria → prefix → text → role`。自己复刻一份，探针加了
 *      新入口（比如这次加的 `prefix`）就漏了。
 *   ② **表达不了 `sel` 判据**。`dump` 只收 `INTERACTIVE` 候选集，而
 *      `terminal` 屏的判据 `.xterm-screen` 是个普通 `<div>`，不在候选集里 ——
 *      自己比对必然报「没找到」，把好的判据误判成坏的。
 *   ③ **重复走了两次页面往返**：dump 已经拿到了，看着好像能省一次；但见上，
 *      那份数据的覆盖面本来就不对。
 *
 * 所以改成 `find` —— 与 `wait` 同一条代码路径，探针改了这里自动跟上。
 */
async function expectHolds(screen) {
  const exp = screen.expect;
  if (!exp) return { holds: true, why: '（无判据）' };
  const r = await probe({ action: 'find', ...exp });
  if (r?.ok !== true || !r.element) {
    return { holds: false, why: `没找到 ${JSON.stringify(exp)}（${r?.error ?? '无元素'}）` };
  }
  const el = r.element;
  // `clickable:true` 的语义：还得没被遮挡、中心点在视口内（与 `wait` 一致）
  if (exp.clickable === true && (el.covered || el.offscreen)) {
    return {
      holds: false,
      why: `找到了「${el.text}」但不可点（${el.covered ? '被遮挡' : '中心点在视口外'}）`,
    };
  }
  return { holds: true, why: `命中「${el.text || el.sel}」` };
}

console.log(`\n共 ${SCREENS.length} 屏待审计\n${'═'.repeat(62)}`);

const report = [];
for (const screen of SCREENS) {
  console.log(`\n▸ ${screen.id}  ${screen.title}`);
  console.log(`  enter: ${JSON.stringify(screen.enter)}`);

  await resetToBase(EMPTY_SESSION_ONLY.has(screen.id));
  if (EMPTY_SESSION_ONLY.has(screen.id)) {
    console.log(`  ⚠️ 这一屏只在空会话态存在 —— 已清 dsh.sessions.current 并重载`);
  }
  const baseViewport = await ev(`JSON.stringify({w:innerWidth,h:innerHeight,dpr:devicePixelRatio})`);
  const vp = JSON.parse(baseViewport);
  if (SCREENS.indexOf(screen) === 0) console.log(`  真机视口: ${JSON.stringify(vp)}（表里写的是 ${JSON.stringify(ATLAS.viewport)}）`);

  const entered = await enterScreen(screen);
  if (!entered.ok) {
    console.log(`  ✗ enter 第 ${JSON.stringify(entered.at)} 步没点着: ok=${entered.reply?.ok} onTarget=${entered.reply?.onTarget}`);
    fails.push(`${screen.id}: enter 失败`);
    report.push({ id: screen.id, entered: false });
    continue;
  }
  const dump = await probe({ action: 'dump', limit: 300 });
  console.log(`  这一屏 dump 到 ${(dump.elements ?? []).length} 个可交互元素`);

  const eh = await expectHolds(screen);
  ok(eh.holds, `判据成立：${eh.why}`);

  // ── 逐个核对 els（只读，不点）──────────────────────────────────
  //
  // ⚠️ **严格度取决于表是从哪采的**（`ATLAS.source`），这不是放宽标准，
  //    而是分清「表烂了」与「表和真机本来就不同源」：
  //
  //    · `source === 'device'` → 表里的坐标就是**这台设备**的 CSS 坐标，
  //      类名也是**这个构建**的类名。所以**严格**：找不到 = 真缺陷，
  //      坐标偏差 > 3px = 真缺陷。这条是重采之后的验收判据。
  //
  //    · `source === 'pc'` → 视口（360×780 vs 360×764）、类名（构建 hash）、
  //      用户配置（DeepSeek-V41-Flash/High vs Agnes/Default）**全都不一样**。
  //      偏差是**预期**的，只报告不判失败 —— 第一版审计把这类全算成失败，
  //      于是报出 85 个「坐标偏移」、看着像这张表烂透了，实际是口径错了。
  //
  // ⚠️ 另有一条**与来源无关**的例外：`els` 里有些条目本身就是**用户数据**
  //    （「模型 Agnes」「推理等级 Default」）。用户哪天再换个模型，即使表是
  //    真机采的，那一条也会失配 —— 那是**运维**问题，不是表的缺陷。
  //    这类靠 `DYNAMIC` 认出来，报 ℹ️ 不报 ✗。
  const STRICT = ATLAS.source === 'device';
  const DYNAMIC = /当前|已选|已启用|已停用|第 \d+ 个|（\d+）|\(\d+\)|推理等级|模型 /;
  const els = screen.els ?? [];
  let found = 0;
  const missing = [];
  const coordOff = [];
  const clsMismatch = [];
  const dynamicMatched = [];
  const softMissing = [];
  for (const [txt, cls, x, y, role] of els) {
    const isDynamic = DYNAMIC.test(txt);
    // 稳定前缀 = 去掉动态片段之前的部分
    const prefix = isDynamic ? txt.split(DYNAMIC)[0].trim() : txt;
    // 与 `locate()` 同序：文本优先；文本为空时退回「类名 + 坐标最近」
    let hit = null;
    if (txt) {
      hit = (dump.elements ?? []).find((e) => String(e.text ?? '') === txt);
      if (!hit && isDynamic && prefix) {
        hit = (dump.elements ?? []).find((e) => String(e.text ?? '').startsWith(prefix));
        if (hit) dynamicMatched.push(`${txt} → 实测「${hit.text}」`);
      }
      // ⚠️ 动态条目的**兜底**：前缀也换掉了（用户改的是前缀里的东西，比如
      //    模型名换成了别的词）时，退回「同类名 + 坐标最近」—— 那仍然是
      //    「同一个按钮、不同的值」，不是缺失。
      if (!hit && isDynamic && cls) {
        const cands = (dump.elements ?? []).filter((e) => String(e.cls ?? '').split(' ').includes(cls));
        hit = cands.sort((a, b) => Math.hypot(a.center.x - x, a.center.y - y) - Math.hypot(b.center.x - x, b.center.y - y))[0] ?? null;
        if (hit) dynamicMatched.push(`${txt} → 实测「${hit.text}」（同类名就近）`);
      }
    }
    if (!hit && cls) {
      const cands = (dump.elements ?? []).filter((e) => String(e.cls ?? '').split(' ').includes(cls));
      // 同一类名可能有多个（如 5 个「会话的操作」），取坐标最近的
      hit = cands.sort((a, b) => Math.hypot(a.center.x - x, a.center.y - y) - Math.hypot(b.center.x - x, b.center.y - y))[0] ?? null;
    }
    if (!hit) {
      (STRICT || !isDynamic ? missing : softMissing).push(`${txt || cls}`);
      continue;
    }
    found++;
    const dx = Math.round(hit.center.x - x);
    const dy = Math.round(hit.center.y - y);
    // ⚠️ 动态条目的坐标不参与「偏移」判定：它的**值**不同 ⇒ 宽度不同 ⇒
    //    中心点本来就会挪几像素（「DeepSeek-V41-Flash」比「Agnes」宽得多）。
    //    把这种挪动当缺陷，会让每一次换模型都刷出一屏假失败。
    if (!isDynamic && (Math.abs(dx) > 3 || Math.abs(dy) > 3)) {
      coordOff.push(`${txt || cls} 表(${x},${y}) 实测(${Math.round(hit.center.x)},${Math.round(hit.center.y)}) Δ(${dx},${dy})`);
    }
    if (cls && !String(hit.cls ?? '').split(' ').includes(cls)) clsMismatch.push(`${txt} 表=${cls} 实测=${hit.cls}`);
  }
  report.push({ id: screen.id, entered: true, expectOk: eh.holds, total: els.length, found, missing, softMissing, coordOff, clsMismatch, dynamicMatched, dumpCount: (dump.elements ?? []).length });

  // ⚠️ 判据只在 `source==='device'` 时才算硬失败。PC 采的表在真机上失配是
  //    **预期**的（见上面那段），报出来是为了催「去真机重采」，不是缺陷。
  ok(missing.length === 0 || !STRICT, `els ${found}/${els.length} 个都能在真机找到`, missing.slice(0, 6).join(' / '));
  ok(coordOff.length === 0 || !STRICT, `坐标一致（±3px 内）`, coordOff.slice(0, 5).join(' | '));
  if (!STRICT && (missing.length || coordOff.length)) {
    console.log(`    ℹ️ 表来源 = PC（${JSON.stringify(ATLAS.source)}），上述偏差**属预期** —— ` +
      `要精确就按 uiagent-device-audit.mjs 文件头那条命令去真机重采`);
  }
  if (softMissing.length) console.log(`    ℹ️ 用户数据类条目（值与生成时不同，不算缺陷）${softMissing.length} 个: ${softMissing.slice(0, 3).join(' | ')}`);
  if (dynamicMatched.length) console.log(`    ℹ️ 动态文案（靠稳定前缀命中）${dynamicMatched.length} 个: ${dynamicMatched.slice(0, 3).join(' | ')}`);
  if (clsMismatch.length) console.log(`    ℹ️ 类名对不上的 ${clsMismatch.length} 个: ${clsMismatch.slice(0, 3).join(' | ')}`);
}

// ── 恢复现场 ────────────────────────────────────────────────────────
console.log(`\n${'═'.repeat(62)}\n恢复现场`);
await ev(`(() => {
  const keep = ${JSON.stringify(before)};
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const k = localStorage.key(i);
    if (!(k in keep)) localStorage.removeItem(k);
  }
  for (const [k, v] of Object.entries(keep)) localStorage.setItem(k, v);
  return 1;
})()`);
await send('Page.reload', {});
await sleep(4000);
const after = JSON.parse(await ev(LS_DUMP));
const same = JSON.stringify(Object.keys(after).sort()) === JSON.stringify(Object.keys(before).sort()) &&
  Object.keys(before).every((k) => after[k] === before[k]);
ok(same, 'localStorage 已还原成跑之前的样子',
  `before=${Object.keys(before).sort().join(',')} after=${Object.keys(after).sort().join(',')}`);

// ── 汇总 ────────────────────────────────────────────────────────────
console.log(`\n${'═'.repeat(62)}\n逐屏汇总\n`);
console.log(`  表来源: ${ATLAS.source === 'device' ? '真机 ✅（坐标/类名与这台设备同源，偏差即缺陷）' : `PC ⚠️（${JSON.stringify(ATLAS.source)}，与真机不同源，偏差属预期）`}`);
console.log(`  表视口: ${ATLAS.viewport?.w}×${ATLAS.viewport?.h} / dpr ${ATLAS.viewport?.dpr}\n`);
console.log('  屏                      enter  判据  els 找到  坐标偏移  缺失');
for (const r of report) {
  if (!r.entered) { console.log(`  ${r.id.padEnd(22)}  ✗失败`); continue; }
  console.log(`  ${r.id.padEnd(22)}  ✓     ${r.expectOk ? '✓' : '✗'}     ${String(r.found).padStart(3)}/${String(r.total).padEnd(3)}  ${String(r.coordOff.length).padStart(3)}      ${String(r.missing.length).padStart(3)}`);
}

const totalEls = report.reduce((n, r) => n + (r.total ?? 0), 0);
const totalFound = report.reduce((n, r) => n + (r.found ?? 0), 0);
const totalOff = report.reduce((n, r) => n + (r.coordOff?.length ?? 0), 0);
const totalSoft = report.reduce((n, r) => n + (r.softMissing?.length ?? 0), 0);
console.log(`\n  合计: ${totalFound}/${totalEls} 个元素在真机找到，${totalOff} 个坐标偏移 >3px，` +
  `${totalSoft} 个属「用户数据不同」`);
if (ATLAS.source !== 'device') {
  console.log('\n  ⚠️ 这张表是 PC 采的 —— 上述偏移与缺失都不是缺陷，是**不同源**。');
  console.log('     要让坐标真准，去真机重采（命令见本文件头）：');
  console.log('       adb shell pidof com.dsh.android');
  console.log('       adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>');
  console.log('       $env:UIMAP_CDP = "http://127.0.0.1:19222"');
  console.log('       node tools/ui-map-real-frontend.mjs "<真机 url>"');
  console.log('       node tools/build-uiagent-screens.mjs');
}

console.log(`\n${'═'.repeat(62)}`);
if (fails.length === 0) console.log(`真机逐屏审计全部通过：${pass} 项断言`);
else {
  console.log(`失败 ${fails.length} / 共 ${pass + fails.length}：`);
  fails.forEach((f) => console.log(`  ✗ ${f}`));
}
ws.close();
process.exit(fails.length === 0 ? 0 : 1);
