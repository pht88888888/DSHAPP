/**
 * `ui` 工具**插件侧**的文件信箱契约验收。
 *
 * 为什么单独验这一层：整条链路是三跳的 ——
 *
 *     模型 ──▶ 插件(本文件所验) ──ui-cmd.json──▶ 原生 Kotlin ──▶ 页面探针
 *                    ◀──ui-result.json──┘
 *
 * 另外两跳在 PC 侧已有验收（`ui-probe-verify.mjs` 验页面探针、真机验原生），
 * **只有中间这一跳既不在页面里、也不需要真机** —— 它是纯 Node + 文件系统，
 * 所以能在本机**用真的插件代码**驱动，只需一个假的原生对端。
 *
 * 验的是四件在真机上表现为「偶发、看起来随机」的事：
 *   ① `id` 配对 —— 上一轮的回执被当成本轮答案；
 *   ② 串行化   —— 并发调用互相覆盖，前一条从未执行；
 *   ③ 超时语义 —— 超时被当成成功（模型以为点了，界面没动）；
 *   ④ 原子写   —— 原生读到写了一半的 JSON。
 *
 * 用法：node tools/uiagent-bridge-verify.mjs
 * 退出码 0 = 全部通过。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const PLUGIN = path.resolve('plugins/android/components/dsh-android-uiagent/lib/index.js');

// ────────────────────────────────────────────────────────────────────
// 断言
// ────────────────────────────────────────────────────────────────────
let pass = 0;
const failures = [];

function ok(cond, label, extra) {
	if (cond) {
		pass += 1;
		console.log(`  ✓ ${label}`);
	} else {
		failures.push(label);
		console.log(`  ✗ ${label}${extra ? ` — ${extra}` : ''}`);
	}
}

function section(title) {
	console.log(`\n── ${title} ${'─'.repeat(Math.max(0, 44 - title.length))}`);
}

function sleep(ms) {
	return new Promise((r) => setTimeout(r, ms));
}

// ────────────────────────────────────────────────────────────────────
// 假原生：只实现「读 ui-cmd.json → 写 ui-result.json」那一侧
// ────────────────────────────────────────────────────────────────────
function startFakeNative(home, opts = {}) {
	const cmdPath = path.join(home, 'ui-cmd.json');
	const resPath = path.join(home, 'ui-result.json');
	const seen = [];          // 收到的每条命令（含收到的时刻）
	const concurrent = [];    // 同时存在的命令数（>1 即并发写，是缺陷）
	let inFlight = 0;
	let stop = false;

	const timer = setInterval(() => {
		let text;
		try { text = fs.readFileSync(cmdPath, 'utf8'); } catch { return; }
		let cmd;
		try { cmd = JSON.parse(text); } catch {
			// ③ 读到写了一半的文件：**不算错误**，下一轮再看。
			//    这里刻意放大计数，因为「插件必须原子写」正是被验的事。
			concurrent.push('PARSE_FAIL');
			return;
		}
		// 同一份命令可能被读两次（轮询很快），只在 id 变化时算「新命令」。
		if (seen.length > 0 && seen[seen.length - 1].id === cmd.id) return;

		seen.push({ id: cmd.id, action: cmd.action, at: Date.now(), raw: cmd });
		inFlight += 1;
		concurrent.push(inFlight);

		if (opts.holdMs) {
			// 故意慢：模拟「tap 之后等界面变化」那段耗时。
			setTimeout(() => { inFlight -= 1; }, opts.holdMs);
		} else {
			inFlight -= 1;
		}

		const reply = {
			id: cmd.id,
			ok: true,
			action: cmd.action,
			echo: cmd.value ?? null,
			...(opts.replyExtra ?? {}),
		};
		if (opts.dropIds?.includes(cmd.id)) return;   // 故意不回执（模拟原生挂了）
		if (opts.wrongIdAlways) {
			// 故意总用**别的** id 回执：验「按 id 配对」是否真的生效 ——
			// 探针必须认出来这不是给自己，而不是把内容照单全收。
			fs.writeFileSync(resPath, JSON.stringify({ ...reply, id: cmd.id + 7 }), 'utf8');
			return;
		}
		if (opts.delayMs) {
			setTimeout(() => { try { fs.writeFileSync(resPath, JSON.stringify(reply), 'utf8'); } catch {} }, opts.delayMs);
			return;
		}
		try { fs.writeFileSync(resPath, JSON.stringify(reply), 'utf8'); } catch {}
	}, opts.pollMs ?? 5);

	return {
		seen,
		concurrent,
		stop() { stop = true; clearInterval(timer); },
		get stopped() { return stop; },
	};
}

// ────────────────────────────────────────────────────────────────────
// 载入插件并取出工具
// ────────────────────────────────────────────────────────────────────
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'uiagent-bridge-'));
process.env.DSH_HOME = home;

const mod = await import(pathToFileURL(PLUGIN).href);

// 抓注册进来的工具定义（假 ctx）
let tool = null;
const ctx = {
	logger: { warn: (...a) => console.log(`    [插件警告] ${a.join(' ')}`) },
	tools: { register(t) { tool = t; } },
};

console.log(`插件: ${path.relative(process.cwd(), PLUGIN)}`);
console.log(`信箱: ${home}`);

section('注册与前置条件');
mod.apply(ctx);
ok(tool !== null, 'DSH_HOME 存在时注册了 ui 工具');
ok(tool?.name === 'ui', `工具名是 ui（实际 ${tool?.name}）`);
ok(Array.isArray(tool?.parameters?.required) && tool.parameters.required.includes('action'),
	'action 是必填参数');
const actions = tool?.parameters?.properties?.action?.enum ?? [];
ok(actions.includes('overlay'), '动作枚举里有本轮新增的 overlay');
ok(actions.includes('dump') && actions.includes('tap') && actions.includes('wait'),
	'基本动作（dump/tap/wait）都在枚举里');
ok(tool?.parameters?.properties?.clickableOnly?.type === 'boolean',
	'schema 暴露了 clickableOnly（dump 只看能点的）');
ok(tool?.parameters?.properties?.clickable?.type === 'boolean',
	'schema 暴露了 clickable（wait 要求真能点）');
ok(tool?.parameters?.properties?.additionalProperties === undefined
	|| tool?.parameters?.additionalProperties === false,
	'额外字段被禁止（避免模型瞎编参数）');
ok(actions.includes('map') && actions.includes('where') && actions.includes('goto'),
	'动作枚举里有本轮新增的 map/where/goto');
ok(tool?.parameters?.properties?.screen?.type === 'string',
	'schema 暴露了 screen（goto 的目标屏）');
ok(tool?.parameters?.properties?.detail?.type === 'boolean',
	'schema 暴露了 detail（map 是否列按钮）');

// ────────────────────────────────────────────────────────────────────
section('① id 配对：上一轮的回执不能当本轮答案');
{
	// 预埋一份「上一轮」的回执，id 与本轮不同。
	fs.writeFileSync(path.join(home, 'ui-result.json'),
		JSON.stringify({ id: 999, ok: true, from: '上一轮' }), 'utf8');

	const native = startFakeNative(home);
	const r = await tool.execute({ action: 'ping' });
	native.stop();

	ok(r.ok === true, 'ping 拿到回执');
	ok(r.details?.from === undefined, '没把上一轮的陈旧回执当成本轮结果');
	ok(r.details?.id === 1, `回执 id 与本轮请求一致（实际 ${r.details?.id}）`);
	ok(native.seen.length === 1, `原生只收到 1 条命令（实际 ${native.seen.length}）`);
	ok(native.seen[0]?.action === 'ping', '透传给原生的 action 正确');
}

section('② 串行化：并发调用不能互相覆盖');
{
	// 关键场景：模型在同一轮里并行发起多个工具调用。
	// 延迟回执，让「串行」与「并发」在耗时上可区分：若插件没有闸门，三条命令
	// 会同时落进同一个文件、只有最后一条被执行。
	const native = startFakeNative(home, { pollMs: 5, delayMs: 50 });
	const t0 = Date.now();
	const results = await Promise.all([
		tool.execute({ action: 'dump' }),
		tool.execute({ action: 'info' }),
		tool.execute({ action: 'text' }),
	]);
	const elapsed = Date.now() - t0;
	native.stop();

	ok(native.seen.length === 3, `三条命令都被原生收到（实际 ${native.seen.length}）`);
	const gotActions = native.seen.map((c) => c.action).join(',');
	ok(gotActions === 'dump,info,text', `执行顺序与发起顺序一致（实际 ${gotActions}）`);
	ok(!native.concurrent.includes('PARSE_FAIL'), '原生从未读到写了一半的命令文件（原子写生效）');
	ok(Math.max(...native.concurrent) === 1, `任一瞬间信箱里只有一条命令（实际峰值 ${Math.max(...native.concurrent)}）`);
	ok(results.every((r) => r.ok === true), '三个并发调用各自拿到自己的回执');
	ok(results[0].details?.action === 'dump' && results[1].details?.action === 'info'
		&& results[2].details?.action === 'text', '回执没有串台（各自对各自的 action）');
	// 三次各 50ms 回执 + 80ms 轮询 ⇒ 串行下至少 ~150ms；真并发会 ≈ 130ms 且只剩 1 条命令。
	// 这条只是辅助证据，主证据是上面的顺序与峰值并发。
	ok(elapsed >= 120, `耗时符合串行特征（${elapsed}ms）`);
}

section('③ id 对不上的回执不能被误认成功（闸门随后必须放行）');
{
	// 假原生先回一份**别人的**回执，再回正确的 —— 插件必须跳过前者、接受后者。
	// （比「一律不回执、等 20 秒超时」快得多，验的是同一件事。）
	const cmdPath = path.join(home, 'ui-cmd.json');
	const resPath = path.join(home, 'ui-result.json');
	const native = {
		seen: [],
		stop() { clearInterval(timer); },
	};
	const timer = setInterval(() => {
		let cmd;
		try { cmd = JSON.parse(fs.readFileSync(cmdPath, 'utf8')); } catch { return; }
		if (native.seen.includes(cmd.id)) return;
		native.seen.push(cmd.id);
		// ① 先投一份 id 对不上的（模拟上一轮遗留 / 串台）
		fs.writeFileSync(resPath, JSON.stringify({ id: cmd.id + 7, ok: true, action: 'WRONG' }), 'utf8');
		// ② 40ms 后投正确的那份
		setTimeout(() => {
			try {
				fs.writeFileSync(resPath, JSON.stringify({ id: cmd.id, ok: true, action: cmd.action }), 'utf8');
			} catch {}
		}, 40);
	}, 5);

	const r1 = await tool.execute({ action: 'ping' });
	native.stop();

	ok(r1.ok === true, 'ping 最终拿到回执');
	ok(r1.details?.action === 'ping',
		`拿到的是**正确 id** 的那份，而不是 id 对不上的（实际 ${r1.details?.action}）`);
	ok(r1.details?.id !== native.seen[0] + 7, '错误 id 的回执没有被接受');

	// 闸门是否放行 —— 后续调用必须照常工作。
	const native2 = startFakeNative(home, { pollMs: 5 });
	const r2 = await tool.execute({ action: 'ping' });
	native2.stop();
	ok(r2.ok === true, '后续调用照常工作（闸门没有卡死）');
}

section('④ 超时不能返回成功');
{
	// 原生永不回执。为了不真等 20 秒，这里直接检查插件声明的超时值与文案。
	const src = fs.readFileSync(PLUGIN, 'utf8');
	const m = /const WAIT_TIMEOUT_MS = (\d+);/.exec(src);
	ok(m !== null, '插件里能读到 WAIT_TIMEOUT_MS');
	const waitMs = Number(m?.[1]);
	ok(waitMs >= 12_000,
		`插件超时(${waitMs}ms)必须大于原生单次超时(12000ms)，否则原生还没答就判超时`);

	// 检查超时分支**不返回 ok**
	const idx = src.indexOf('等待原生回执超时');
	const around = idx >= 0 ? src.slice(Math.max(0, idx - 300), idx + 200) : '';
	ok(/ok:\s*false/.test(around), '超时分支明确返回 ok:false（绝不假装成功）');
	ok(/info/.test(around), '超时文案引导模型用 info 复查，而不是让它猜');
	ok(/可能已经变化，也可能没有|可能/.test(around),
		'超时文案如实说明「界面可能变了也可能没变」，不替模型下结论');
}

section('⑤ 字段原样透传（不做 text ↔ value 翻译）');
{
	const native = startFakeNative(home, { pollMs: 5 });
	const r = await tool.execute({ action: 'tap', text: '发送' });
	native.stop();

	const got = native.seen[0]?.raw ?? {};
	ok(got.text === '发送', 'tap 的 text 原样透传到原生（没被当成输入内容删掉）');
	ok(got.value === undefined, 'tap 没有被塞进 value');
	ok(r.ok === true, 'tap 调用成功');
}
{
	const native = startFakeNative(home, { pollMs: 5 });
	await tool.execute({ action: 'type', sel: '#msg', value: '你好' });
	native.stop();
	const got = native.seen[0]?.raw ?? {};
	ok(got.value === '你好', 'type 的 value 原样透传');
	ok(got.sel === '#msg', 'type 的定位条件 sel 原样透传');
}

section('⑥ 参数校验：非法输入要报明确失败而不是发给原生');
{
	// 用「不存在的 home」不好造，改为直接看：校验失败时命令文件不应被写出来。
	const cmdPath = path.join(home, 'ui-cmd.json');
	try { fs.unlinkSync(cmdPath); } catch { /* 干净就行 */ }
	const r = await tool.execute({ action: '不存在的动作' });
	ok(r.ok === false, '未知 action 被拦下');
	ok(r.message && r.message.length > 0, '拦下时给出了可读的原因');
	ok(!fs.existsSync(cmdPath), '未知 action 没有往信箱里写命令（校验在发送之前）');
}

section('⑦ 渲染层：模型读到的是文本，遮挡信息排在元素清单之前');
{
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			matched: 2,
			blocked: 2,
			elements: [
				{ text: '发送', tag: 'button', sel: '#send', rect: { x: 10, y: 20, w: 30, h: 30 }, covered: true, blockedBy: { tag: 'div', cls: '_mask_' } },
				{ text: '取消', tag: 'button', sel: '#cancel', rect: { x: 50, y: 20, w: 30, h: 30 }, covered: true, blockedBy: { tag: 'div', cls: '_mask_' } },
			],
			overlay: { tag: 'div', cls: '_mask_', sel: 'html > body > div._mask_', rect: { x: 0, y: 0, w: 360, h: 780 } },
			warning: '当前有覆盖视口大部分区域的遮挡层，下面的元素点了不会有反应。',
			viewport: { w: 360, h: 780 },
		},
	});
	const r = await tool.execute({ action: 'dump' });
	native.stop();

	const msg = r.message ?? '';
	const iOverlay = msg.indexOf('遮挡层');
	const iFirstEl = msg.indexOf('发送');
	ok(iOverlay >= 0, 'dump 文本里有遮挡层警告');
	ok(iFirstEl >= 0, 'dump 文本里列出了元素');
	ok(iOverlay < iFirstEl,
		`遮挡警告排在元素清单之前（遮挡@${iOverlay}，首个元素@${iFirstEl}）—— 模型扫清单前就被拦下`);
	ok(/点不动|被遮挡/.test(msg), '文案直说「点不动/被遮挡」，不是含糊的警告');
	ok(/被遮挡←/.test(msg),
		'元素行上直接标出遮挡者（模型逐行扫清单时对那条就不会照点）');
	ok(/此刻真正能点/.test(msg),
		'提示用 overlay 看「此刻真正能点的」，而不是误导成「遮挡层里面的」（官方弹窗里按钮是遮罩的兄弟节点）');
}

section('⑦.5 渲染层：屏幕外是第二类「点不到」，不能被混进遮挡那套话术');
{
	// ⚠️ 这一段的两个 case 都是「点了不会有反应」，但**处置办法相反**：
	//   遮挡 → 先关掉挡在前面的东西；屏幕外 → 先把界面切到它所在的状态。
	//   渲染层若把屏幕外也套上遮挡的文案，模型会去查 overlay，方向就错了。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			matched: 2,
			blocked: 0,
			offscreen: 1,
			elements: [
				{ text: '发送', tag: 'button', sel: '#send', rect: { x: 10, y: 20, w: 30, h: 30 } },
				{ text: '打开侧边栏', tag: 'button', sel: '#sb', rect: { x: -312, y: 36, w: 36, h: 36 }, offscreen: true, inViewport: true },
			],
			viewport: { w: 360, h: 780 },
		},
	});
	const r = await tool.execute({ action: 'dump' });
	native.stop();

	const msg = r.message ?? '';
	ok(/屏幕外/.test(msg), 'dump 文本里报出「屏幕外」这一类');
	ok(/屏幕外·点不到/.test(msg),
		'元素行上直接标出「屏幕外·点不到」（与「被遮挡←」是两个不同的标记）');
	ok(!/被遮挡←/.test(msg), '没有屏幕外元素被误标成「被遮挡←」');
	ok(/先把对应面板\/状态切出来/.test(msg),
		'给出的处置办法是「切状态」而不是「关掉遮挡层」—— 两者方向相反');
}

section('⑦.55 渲染层：全屏「面板」不能套用遮挡那套话术（否则模型会去关掉当前屏幕）');
{
	// ⚠️ 这是本轮修掉的第二个真实缺陷。设置面板 / 右侧边栏 / 会话抽屉都是覆盖
	//    全屏的容器，探针早期版本一律报成「遮挡层」，渲染层于是打出「先关掉它」
	//    —— 模型照着去点「返回」，那一屏**永远进不去**（实测：右侧边栏那一屏被
	//    引去点「工作区文件」再点「关闭」，绕两圈才回到原位）。
	//    面板是**当前屏幕**，它里面的按钮可以正常点。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			matched: 3,
			blocked: 14,
			panel: { tag: 'div', cls: 'wCInkW_panel', sel: 'body > div:nth-of-type(3) > div:nth-of-type(2)', rect: { x: 0, y: 0, w: 360, h: 780 } },
			note: '当前屏幕是一个覆盖全屏的面板（wCInkW_panel），它里面的按钮可以点；它**下面**那层元素点不动是正常的，不要去关掉这个面板。',
			elements: [
				{ text: '返回', tag: 'button', sel: '#back', rect: { x: 12, y: 6, w: 36, h: 36 } },
				{ text: '通用设置', tag: 'button', sel: '#general', rect: { x: 10, y: 92, w: 340, h: 50 } },
				{ text: '发送', tag: 'button', sel: '#send', rect: { x: 300, y: 470, w: 34, h: 34 }, covered: true, blockedBy: { tag: 'div', cls: 'wCInkW_panel' } },
			],
			viewport: { w: 360, h: 780 },
		},
	});
	const r = await tool.execute({ action: 'dump' });
	native.stop();

	const msg = r.message ?? '';
	ok(/面板/.test(msg), `dump 文本里报出「面板」这一类: ${JSON.stringify(msg.slice(0, 120))}`);
	ok(!/先关掉它/.test(msg),
		'没有把面板说成「先关掉它」—— 那句话会把模型引去点「返回」，永远进不了这一屏');
	ok(!/有遮挡层盖住界面/.test(msg), '面板屏不报「有遮挡层盖住界面」');
	ok(/里面.*可以正常点|可以正常点/.test(msg), '说清面板**里面**的按钮可以正常点');
	ok(/正常/.test(msg) && /下面/.test(msg), '说清面板下面那层点不动是正常的，不是故障');
}

section('⑦.56 渲染层：overlay 对面板报 kind:panel，不报遮挡');
{
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			present: false,
			kind: 'panel',
			layer: null,
			panel: { tag: 'div', cls: 'wCInkW_panel', sel: 'body > div:nth-of-type(3) > div:nth-of-type(2)' },
			clickable: [
				{ text: '返回', tag: 'button', sel: '#back', rect: { x: 12, y: 6, w: 36, h: 36 } },
				{ text: '通用设置', tag: 'button', sel: '#general', rect: { x: 10, y: 92, w: 340, h: 50 } },
			],
			count: 2,
			viewport: { w: 360, h: 780 },
		},
	});
	const r = await tool.execute({ action: 'overlay' });
	native.stop();

	const msg = r.message ?? '';
	ok(!/当前没有遮挡层，界面可以正常操作/.test(msg),
		'面板屏不能套用「没有遮挡层、可以正常操作」那句（会漏掉「这就是你的屏幕」）');
	ok(/面板/.test(msg), 'overlay 文本里说明当前是面板');
	ok(/不用关掉它|不是遮挡层/.test(msg), '明确说不用关掉它');
	ok(/返回/.test(msg), '仍把面板里面能点的按钮列出来');
}

section('⑦.6 渲染层：tap 报 offscreen 时不能被 onTarget:true 掩盖过去');
{
	// ⚠️ 这是真机上最容易骗过模型的一种返回：`onTarget` 是 **true**（事件确实
	//    发到了目标元素上），只有 `offscreen` 说明屏幕上没有坐标可点。
	//    渲染层若只判 `onTarget === false`，这条警告永远显示不出来。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			dispatched: ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'],
			onTarget: true,
			offscreen: true,
			hint: '元素中心点在视口外（CSS -312,54，视口 360×780），真手指在屏幕上没有可点的坐标。',
			element: { text: '打开侧边栏', tag: 'button', sel: '#sb', rect: { x: -312, y: 36, w: 36, h: 36 }, offscreen: true },
		},
	});
	const r = await tool.execute({ action: 'tap', sel: '#sb' });
	native.stop();

	const msg = r.message ?? '';
	ok(/视口外/.test(msg), 'tap 渲染出「视口外」警告（onTarget 为 true 时也必须出现）');
	ok(/不会有任何反应|没有可点的坐标/.test(msg), '说清后果是「不会有反应」');
	ok(!/点击被上层元素接收/.test(msg),
		'没有误用「被上层元素接收」那句遮挡话术 —— 那会把模型引向 overlay');
	// ⚠️ 屏幕外时**不能**报「已送达目标」的正向确认 —— 那两句会自相矛盾。
	ok(!/事件已送达目标元素本身/.test(msg),
		'★ 屏幕外时不报「事件已送达目标」的正向确认（否则与上面的警告自相矛盾）');
}

section('⑦.61 渲染层：tap 成功时必须给出「点着了」的正向确认');
{
	// ⚠️ 真机实测倒逼出来的：`ok:true` 完全不能表达「点着了没有」——
	//    tap 有三种「执行成功但没点着」的情形（遮挡 / 屏幕外 / 面板之下），
	//    三种都返回 `ok:true`。光报一句「✓ 已点击」，模型无从判断该不该信这次点击。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			dispatched: ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'],
			onTarget: true,
			offscreen: false,
			element: { text: '打开导航', tag: 'button', sel: '#nav', rect: { x: 8, y: 8, w: 36, h: 36 } },
		},
	});
	const r = await tool.execute({ action: 'tap', sel: '#nav' });
	native.stop();

	const msg = r.message ?? '';
	ok(/onTarget:true/.test(msg), '★ 成功点击时明说 onTarget:true（模型据此判断该不该信）');
	ok(/不是被上层吃掉的点击/.test(msg), '顺带排除「被上层吃掉」这一种失败');
	// ⚠️ 措辞刻意是「事件送达」而不是「点击生效」：事件送达 ≠ 界面响应，
	//    后者要靠下一步 dump 看。写成「生效」会让模型跳过复核。
	ok(!/点击已生效|已经生效|操作成功/.test(msg),
		'★ 不写成「点击已生效」—— 事件送达不等于界面响应，那会让模型跳过复核');
}

section('⑦.62 渲染层：onTarget:false 但无上层元素 —— 不能说成「被上层接收」');
{
	// ⚠️ 第三态：`onTarget:false` 且 `hitTopmost` 为空。这时**没有上层元素**，
	//    说「被上层元素接收」是错的。真实成因是事件落到了空白处（元素刚被
	//    重渲染掉、或坐标处本来就没有东西）。第一版把它并进遮挡分支，
	//    模型会照着去查 overlay 白费一轮。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			dispatched: ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'],
			onTarget: false,
			covered: false,
			hitTopmost: null,
			element: { text: '发送', tag: 'button', sel: '#send', rect: { x: 300, y: 470, w: 34, h: 34 } },
		},
	});
	const r = await tool.execute({ action: 'tap', sel: '#send' });
	native.stop();

	const msg = r.message ?? '';
	ok(/没有落到任何元素上/.test(msg), '★ 说清「点击没有落到任何元素上」');
	ok(!/被上层元素接收/.test(msg), '★ 不说「被上层元素接收」—— 根本没有上层元素');
	ok(/重渲染/.test(msg), '给出真实成因（点击瞬间被重渲染掉）');
}

section('⑦.63 渲染层：type 的 clear 生效时要说出来');
{
	// ⚠️ 真机上 `clear:true` 对 composer（Lexical）曾完全无效：`insertText('')`
	//    是 no-op，旧内容留下、新内容被追加，回执里的 `value` 是一长串拼接结果，
	//    模型看不出是自己没清掉。修好之后必须把「清过了」报出来，
	//    否则模型仍无法区分「clear 生效了」与「clear 被忽略了」。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			how: 'execCommand(异步生效)',
			cleared: 'selectAll+insertText+Backspace',
			value: 'ONLY_NEW',
			applied: null,
			element: { text: '描述你想要构建的内容', tag: 'div', sel: '#ce' },
		},
	});
	const r = await tool.execute({ action: 'type', sel: '#ce', value: 'ONLY_NEW', clear: true });
	native.stop();

	const msg = r.message ?? '';
	ok(/已先清空原内容/.test(msg), '★ clear 生效时明说「已先清空原内容」');
	ok(/selectAll\+insertText\+Backspace/.test(msg), '报出用的是哪条清空路径');
}

section('⑦.64 渲染层：没设 clear 时不能谎报清空');
{
	// ⚠️ 反向断言：`cleared` 为 null（没设 clear）时**不能**出现「已先清空」。
	//    谎报清空会让模型以为旧内容没了，进而基于错误前提往下走。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			how: 'execCommand(异步生效)',
			cleared: null,
			value: '追加在旧内容后面',
			applied: null,
			element: { text: '描述你想要构建的内容', tag: 'div', sel: '#ce' },
		},
	});
	const r = await tool.execute({ action: 'type', sel: '#ce', value: '追加在旧内容后面' });
	native.stop();

	const msg = r.message ?? '';
	ok(!/已先清空原内容/.test(msg), '★ 没设 clear 时不谎报清空');
}

section('⑦.65 渲染层：tap 被面板盖住时，说「不在当前屏幕上」而不是「被遮挡」');
{
	// ⚠️ 两种 `onTarget:false` 的处置办法相反：
	//   - 被**空遮罩**盖住 → 先关掉弹窗；
	//   - 在**面板下面**   → 它根本不在当前屏幕上，该做的是切回下面那层或改点面板里的。
	//   探针把两者分开报（`panel` vs `overlay`），渲染层必须跟着分开。
	const native = startFakeNative(home, {
		pollMs: 5,
		replyExtra: {
			dispatched: ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'],
			onTarget: false,
			covered: true,
			panel: { tag: 'div', cls: 'wCInkW_panel', sel: 'body > div:nth-of-type(3) > div:nth-of-type(2)' },
			hitTopmost: { tag: 'div', cls: 'wCInkW_panel', sel: 'body > div:nth-of-type(3) > div:nth-of-type(2)' },
			hint: '这个元素在当前面板**下面**（面板是「wCInkW_panel」），所以点不到。',
			element: { text: '发送', tag: 'button', sel: '#send', rect: { x: 300, y: 470, w: 34, h: 34 }, covered: true },
		},
	});
	const r = await tool.execute({ action: 'tap', sel: '#send' });
	native.stop();

	const msg = r.message ?? '';
	ok(/面板/.test(msg), 'tap 文本里报出「面板」');
	ok(/下面/.test(msg), '说清该元素在面板**下面**，不在当前屏幕上');
	ok(!/先处理挡在前面的元素|点击被上层元素接收/.test(msg),
		'没有套用遮挡的话术 —— 那会把模型引去关掉一个本该留着的面板');
}

section('⑦.7 渲染层：wait clickable 的语义已含「在屏幕内」');
{
	const native = startFakeNative(home, {
		pollMs: 5,
		// ⚠️ `attempts` 是原生**真的会回传**的字段（UiProbeBridge.runWait 的两条
		//    路径都 put 了它），假对端也要照带 —— 否则渲染出来是「检查了 undefined
		//    次」，那是夹具不完整造成的假象，不是产品缺陷。
		replyExtra: { satisfied: false, attempts: 40, element: { text: '打开侧边栏', tag: 'button', sel: '#sb', offscreen: true } },
	});
	const r = await tool.execute({ action: 'wait', sel: '#sb', clickable: true });
	native.stop();
	ok(r.ok === true, 'wait 回执本身成功');
	ok(/检查了 40 次/.test(r.message ?? ''),
		`wait 把「没等到」如实渲染出来、且带上了检查次数: ${JSON.stringify(r.message)}`);
	ok(!/undefined/.test(r.message ?? ''),
		'渲染里没有 undefined（原生一定会回传 attempts，渲染层别引用别的字段名）');
}

section('⑧ 渲染层：type 的三态 applied 不能被当布尔');
{
	const three = [
		{ applied: true, how: 'execCommand', value: 'x', want: '不报错' },
		{ applied: null, how: 'execCommand(异步生效)', value: '', want: '说明异步' },
		{ applied: false, how: 'textContent(未生效)', value: '', want: '警告未写入' },
	];
	for (const c of three) {
		const native = startFakeNative(home, {
			pollMs: 5,
			replyExtra: { applied: c.applied, how: c.how, value: c.value, element: { tag: 'div', sel: '#ed' } },
		});
		const r = await tool.execute({ action: 'type', sel: '#ed', value: 'x' });
		native.stop();
		const msg = r.message ?? '';
		if (c.applied === true) {
			ok(!/⚠️/.test(msg), 'applied:true 时不报警告（不误报失败）');
		} else if (c.applied === null) {
			ok(/异步/.test(msg) && !/没有真正写进去/.test(msg),
				'applied:null 被当作「异步生效」而不是失败（contenteditable 的正常路径）');
		} else {
			ok(/没有真正写进去/.test(msg), 'applied:false 明确报「没写进去」并给建议');
		}
	}
}

section('⑨ 未就绪时不该注册（避免模型拿到一个永远失败的工具）');
{
	const saved = process.env.DSH_HOME;
	process.env.DSH_HOME = '';
	let t2 = null;
	const ctx2 = { logger: { warn: () => {} }, tools: { register: (t) => { t2 = t; } } };
	// 重新载入模块（cache-busting 需要不同 URL）
	const mod2 = await import(`${pathToFileURL(PLUGIN).href}?v=2`);
	mod2.apply(ctx2);
	ok(t2 === null, 'DSH_HOME 为空时不注册工具');
	process.env.DSH_HOME = saved;
}

// ────────────────────────────────────────────────────────────────────
section('⑩ map / where / goto：插件侧的组合动作');
{
	// 内建屏幕表就是它们的数据源，直接从生成物里读 —— 「插件里的表和生成物一致」
	// 本身就是一条要验的事（有人手改过 screens.js 的话，这里会露出来）。
	const screensMod = await import(pathToFileURL(
		path.resolve('plugins/android/components/dsh-android-uiagent/lib/screens.js')).href);
	const atlas = screensMod.ATLAS;

	ok(atlas.screens.length === 17, `内建表有 17 屏（实际 ${atlas.screens.length}）`);
	ok(atlas.screens.every((s) => s.expect && (s.expect.text || s.expect.prefix || s.expect.aria || s.expect.sel)),
		'每屏都有判据（没有判据的屏无法被 where 认出来）');
	// ⚠️ 上面这条原来写的是 `s.expect.text`，把 `terminal` 屏（判据是
	//    `{sel:'.xterm-screen'}`）和 `model-picker` 屏（`{prefix:'模型 '}`）误判成
	//    「没有判据」。判据的四种形态 `text` / `prefix` / `aria` / `sel` 是**等价的**
	//    —— `checkExpect` 直接把 spec 转给探针的 `find`，探针内部 `locate()` 自己
	//    分派。所以断言该查的是「有任一种判据」，不是「有 text」。
	ok(atlas.screens.every((s) => Array.isArray(s.enter)),
		'每屏都有 enter 路线数组');

	/**
	 * 会「演」的假原生：维护一个「当前在哪一屏」，**只在当前这一屏**上判判据。
	 *
	 * ⚠️ 「只在当前屏判」是要害，第一版写错了：当时让 `find` 去**逐屏比对**
	 *   （拿每一屏自己的判据比对传来的判据），于是**每一屏都命中** ——
	 *   `where` 报「17 屏同时成立」，`goto` 的「是否已在该屏」则永远为真、
	 *   直接从不到重载那一步。真机上的原生探针只在**当前 DOM** 上查，
	 *   所以「当前屏」才是这个模型的正确形状。
	 */
	function startStage(home, opts = {}) {
		const cmdPath = path.join(home, 'ui-cmd.json');
		const resPath = path.join(home, 'ui-result.json');
		// 清掉上一节残留的命令/回执 —— 否则新 stage 一启动就把旧命令当成自己的，
		// 「本地动作不碰信箱」这类断言会被误判成失败。
		for (const f of [cmdPath, resPath]) { try { fs.unlinkSync(f); } catch {} }
		const seen = [];
		let here = opts.start ?? 'hero';   // 当前在哪一屏（按屏 id）
		let stopped = false;

		/** 判据在**当前屏**上是否成立（复刻探针 `wait`/`find` 的匹配语义）。 */
		const holds = (spec) => {
			const s = atlas.screens.find((x) => x.id === here);
			if (!s || !spec || typeof spec.text !== 'string') return false;
			const hay = s.expect.text;
			return spec.exact === true ? hay === spec.text : hay.includes(spec.text);
		};

		const timer = setInterval(() => {
			let cmd;
			try { cmd = JSON.parse(fs.readFileSync(cmdPath, 'utf8')); } catch { return; }
			if (cmd.id === seen[seen.length - 1]?.id) return;
			seen.push({ id: cmd.id, action: cmd.action, raw: cmd });

			const reply = { id: cmd.id, ok: true, action: cmd.action };
			if (cmd.action === 'reload') {
				// 重载回基态 —— 这正是 `goto` 依赖的行为
				here = opts.afterReload ?? 'hero';
			} else if (cmd.action === 'ping') {
				reply.url = 'http://127.0.0.1:3080/';
				reply.interactive = 15;
				reply.viewport = { w: 360, h: 780, dpr: 3 };
			} else if (cmd.action === 'overlay') {
				reply.present = false;
				reply.kind = null;
			} else if (cmd.action === 'find') {
				const hit = holds(cmd);
				reply.ok = hit;
				reply.element = hit
					? { tag: 'button', text: atlas.screens.find((x) => x.id === here).expect.text, covered: false, offscreen: false }
					: null;
				if (!hit) reply.error = '找不到元素';
			} else if (cmd.action === 'wait') {
				reply.satisfied = holds(cmd);
				reply.attempts = 1;
				if (!reply.satisfied) reply.error = '等待超时（10000ms）—— 条件始终没有成立';
			} else if (cmd.action === 'tap') {
				// 点中某屏的路线步 ⇒ 走到那一屏。简化但**方向正确**：
				// 路线与屏对不上（生成物坏了）时这里就走不到，goto 会如实失败。
				// 多个屏共用同一标签（如「设置」）时取**第一个** —— 与真实导航一致：
				// 点「设置」进的是设置面板，不是某个子页。
				const label = cmd.text ?? cmd.aria;
				const dest = atlas.screens.find((s) => s.enter.some((e) => (e.text ?? e.aria) === label));
				if (dest && !opts.ignoreTaps) here = dest.id;
				reply.onTarget = true;
				reply.element = { tag: 'button', text: label ?? null };
			}
			try { fs.writeFileSync(resPath, JSON.stringify(reply), 'utf8'); } catch {}
		}, 5);

		return {
			seen,
			get here() { return here; },
			set here(v) { here = v; },
			stop() { stopped = true; clearInterval(timer); },
			get stopped() { return stopped; },
		};
	}

	// ── map：纯本地，一个字节都不该写进信箱 ────────────────────────
	{
		const stage = startStage(home);
		const r = await tool.execute({ action: 'map' });
		// 等一拍，确认没有迟到的信箱写入
		await sleep(60);
		stage.stop();
		ok(r.ok === true, 'map 成功');
		ok(stage.seen.length === 0,
			`map 完全不碰信箱（本地动作；实际发了 ${stage.seen.length} 条 —— 发出去的话探针会回「未知 action」）`);
		ok(/17 屏/.test(r.message ?? ''), 'map 说清了屏数');
		ok(atlas.screens.every((s) => (r.message ?? '').includes(s.id)),
			'map 列出了每一屏的 id');
		ok(!/undefined/.test(r.message ?? ''), 'map 渲染里没有 undefined');
	}

	// ── map detail：列按钮 ─────────────────────────────────────────
	{
		const stage = startStage(home);
		const r = await tool.execute({ action: 'map', detail: true });
		stage.stop();
		ok(stage.seen.length === 0, 'map detail 也不碰信箱');
		const sample = atlas.screens[1].els[0][0];
		ok((r.message ?? '').includes(sample),
			`map detail 列出了具体按钮（找「${sample}」）`);
	}

	// ── where：认出当前屏 ──────────────────────────────────────────
	{
		const stage = startStage(home, { start: 'settings-priv' });
		const r = await tool.execute({ action: 'where' });
		stage.stop();
		ok(stage.seen.every((s) => s.action === 'find'),
			`where 只用 find 探测（**不能用 wait** —— 原生 wait 固定轮询 10 秒，17 屏就是 170 秒；实际用的: ${[...new Set(stage.seen.map((s) => s.action))].join(',')}）`);
		ok(r.details?.screen?.id === 'settings-priv',
			`where 认出了 设置 › 特权权限（实际 ${JSON.stringify(r.details?.screen?.id)}）`);
		ok(/特权权限/.test(r.message ?? ''), 'where 的文本里报了屏标题');
		// ⚠️ 这里原来找的是「开启引导」—— 那是 `settings-priv` 的**判据文案**，
		//    不是按钮。它在 dump 里 `role` 为空、且被 `covered/offscreen` 之一盖住，
		//    于是被 `build-uiagent-screens.mjs` 的 `els` 过滤器挡在按钮表之外
		//    （那是**正确设计**：说明文字不是按钮，列进按钮表只会诱使模型去点）。
		//    断言改成查一个**真在 `els` 里**的按钮，从表本身取，免得再写死。
		const sampleEl = atlas.screens.find((s) => s.id === 'settings-priv')?.els?.[0]?.[0];
		ok(sampleEl && (r.message ?? '').includes(sampleEl),
			`where 顺带列出了该屏的按钮（找「${sampleEl}」）`);
	}

	// ── where：认不出时**不猜** ────────────────────────────────────
	{
		// 把「当前屏」设成一个不存在的 id ⇒ 所有判据都不成立
		const stage = startStage(home, { start: '__nope__' });
		const r = await tool.execute({ action: 'where' });
		stage.stop();
		ok(r.details?.screen === null,
			'认不出时 screen 是 null，而不是硬挑一个最像的（猜错会让模型在错误界面继续点）');
		ok(/认不出/.test(r.message ?? ''), 'where 如实报「认不出」');
		ok(/dump/.test(r.message ?? ''), 'where 给了下一步建议');
	}

	// ── goto：已经在这一屏 ⇒ **不重载** ────────────────────────────
	{
		const stage = startStage(home, { start: 'hero' });
		const r = await tool.execute({ action: 'goto', screen: 'hero' });
		stage.stop();
		ok(r.ok === true, 'goto 已在该屏时成功');
		ok(!stage.seen.some((s) => s.action === 'reload'),
			`已在该屏时**不重载**（重载要几秒且会打断用户；实际发了 reload=${stage.seen.filter((s) => s.action === 'reload').length} 次）`);
		ok(/已经就在/.test(r.message ?? ''), 'goto 如实说明「已经就在」，没假装做了什么');
	}

	// ── goto：走路线过去 ───────────────────────────────────────────
	{
		const stage = startStage(home, { start: 'hero' });
		const r = await tool.execute({ action: 'goto', screen: 'settings-group' });
		stage.stop();
		ok(stage.seen.some((s) => s.action === 'reload'), 'goto 先重载回基态');
		ok(stage.seen.some((s) => s.action === 'tap' && s.raw.text === '设置'),
			'goto 点了路线第一步「设置」');
		ok(stage.seen.some((s) => s.action === 'tap' && s.raw.text === '交流群'),
			'goto 点了路线第二步「交流群」');
		ok(stage.here === 'settings-group', `假原生真的走到了目标屏（实际 ${stage.here}）`);
		ok(r.ok === true, 'goto 成功');
		ok(/交流群/.test(r.message ?? ''), 'goto 的文本里报了屏标题');
		ok(/过程:/.test(r.message ?? '') && /tick|tap/.test(r.message ?? '') !== false,
			'goto 报出了过程步骤（模型需要它来判断卡在哪）');
		ok(!/undefined/.test(r.message ?? ''), 'goto 渲染里没有 undefined');
	}

	// ── goto：按标题也能查（宽松匹配） ─────────────────────────────
	{
		const stage = startStage(home, { start: 'hero' });
		const r = await tool.execute({ action: 'goto', screen: '交流群' });
		stage.stop();
		ok(r.ok === true, 'goto 接受标题子串（「交流群」→ settings-group）');
		ok(r.details?.screen === 'settings-group',
			`标题匹配落到正确的屏（实际 ${r.details?.screen}）`);
	}

	// ── goto：到不了 ⇒ 如实失败并指出卡在哪一步 ────────────────────
	{
		// ignoreTaps：假原生的「点」不改变状态 ⇒ 判据永远不成立
		const stage = startStage(home, { start: 'hero', ignoreTaps: true, afterReload: 'hero' });
		const r = await tool.execute({ action: 'goto', screen: 'plugins-panel' });
		stage.stop();
		ok(r.ok === false, '到不了时 goto 报失败（不能假报成功）');
		ok(/没等到/.test(r.message ?? ''), 'goto 说明了失败原因是「判据没成立」');
		ok(/where/.test(r.message ?? ''), 'goto 指出下一步该用 where 看实际在哪');
		ok(stage.seen.filter((s) => s.action === 'tap').length >= 2,
			`到不了时**原地重点**（不是重载重来 —— 重载会撞同一个时序竞态；实际 tap ${stage.seen.filter((s) => s.action === 'tap').length} 次）`);
	}

	// ── goto：屏名拼错 ⇒ 明确报错，且不碰信箱 ──────────────────────
	{
		const stage = startStage(home);
		const r = await tool.execute({ action: 'goto', screen: 'no-such-screen' });
		await sleep(60);
		stage.stop();
		ok(r.ok === false, '未知屏名报失败');
		ok(stage.seen.length === 0, '未知屏名时一个字节都没写信箱（本地就拦下了）');
		ok(/可用/.test(r.message ?? ''), '报错里列出了可用的屏 id');
	}

	// ── goto：缺 screen 参数 ⇒ 校验拦下 ────────────────────────────
	{
		const stage = startStage(home);
		const r = await tool.execute({ action: 'goto' });
		await sleep(60);
		stage.stop();
		ok(r.ok === false && /screen/.test(r.message ?? ''), 'goto 缺 screen 时报错并点名该字段');
		ok(stage.seen.length === 0, '缺参数时不写信箱');
	}
}


section('⑪ 注册契约：output 声明（真机上唯一会「整个插件不激活」的坑）');
{
	/*
	 * 这一节的存在理由是一次**真机才发现**的失败：
	 *
	 *   [engine] dsh-android-uiagent (.../lib/index.js):
	 *     TypeError: tool "ui" must declare output { schema, render, presentationMeta? }
	 *   [engine] dsh: warning: 1 entry did not activate
	 *
	 * 也就是说 `ui` 工具**从未注册成功过**，而 PC 侧 111 项断言全绿 —— 因为它们
	 * 直接调 `tool.execute()`，绕过了 `ctx.tools.register` 的校验。
	 * 引擎的 register 是**硬校验**（`dsh-tools/lib/index.js:2881`）：
	 *
	 *   if (output === void 0 || typeof output !== 'object'
	 *       || typeof output.render !== 'function'
	 *       || (output.presentationMeta !== void 0 && typeof output.presentationMeta !== 'function'))
	 *     throw new TypeError(...)
	 *   assertSupportedJsonSchema(output.schema)
	 *
	 * 症状是**静默的**：插件不激活、没有 ui 工具、logcat 里只有一行 warning，
	 * 界面完全正常 —— 模型只是「不知道有这个能力」。
	 *
	 * ⚠️ 用**引擎自己的断言函数**判，不自己复刻规则：schema 子集
	 *   （type/oneOf/properties/required/additionalProperties/items/enum/const）
	 *   的边界很细，复刻一份迟早与引擎分叉。
	 */
	const engineDir = process.env.UIAGENT_ENGINE_DIR;
	if (!engineDir || !fs.existsSync(path.join(engineDir, 'lib/index.js'))) {
		console.log('  ⚠️ 跳过：未设 UIAGENT_ENGINE_DIR（指向已解包的 @deepseek-ai/dsh-tools）');
		console.log('     取法见 AGENTS.md「PC 侧自动化验收」—— 从 APK 的 assets/engine.bin 里解出来');
	} else {
		const dshTools = await import(pathToFileURL(path.join(engineDir, 'lib/index.js')).href);
		ok(typeof tool?.output?.render === 'function',
			'工具声明了 output.render（缺了它 register 直接抛 TypeError，插件整个不激活）');
		ok(typeof tool?.output?.schema === 'object' && tool.output.schema !== null,
			'工具声明了 output.schema');

		try {
			dshTools.assertSupportedJsonSchema(tool.output.schema);
			ok(true, 'output.schema 落在引擎支持的子集里（引擎自己的断言函数判的）');
		} catch (e) {
			ok(false, `output.schema 不合法: ${e.message}`);
		}

		// 三条代表性返回值要真能过 schema —— 空 schema 与收窄都能在这里露出来
		const samples = [
			['dump/本地动作', { ok: true, message: 'x', details: { ok: true, detail: false } }],
			['失败返回', { ok: false, message: 'x' }],
			['tap 的 details', { ok: true, message: 'x', details: { ok: true, elements: [{ sel: '#a' }] } }],
		];
		for (const [label, v] of samples) {
			const viol = dshTools.validateJsonSchemaValue(tool.output.schema, v, 'value');
			ok(viol.length === 0, `${label} 的返回值能过 output.schema`, viol.join('; '));
			try {
				const r = tool.output.render({ action: 'dump' }, v);
				ok(Array.isArray(r) && r.every((x) => typeof x?.text === 'string'),
					`${label} 的 render 返回 text 块数组`);
			} catch (e) {
				ok(false, `${label} 的 render 抛错: ${e.message}`);
			}
		}
	}
}

if (failures.length === 0) {
	console.log(`全部通过：${pass} 项断言`);
} else {
	console.log(`失败 ${failures.length} 项 / 共 ${pass + failures.length} 项：`);
	for (const f of failures) console.log(`  ✗ ${f}`);
}
try { fs.rmSync(home, { recursive: true, force: true }); } catch {}
process.exit(failures.length === 0 ? 0 : 1);
