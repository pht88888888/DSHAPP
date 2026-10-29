/**
 * `agent/inbox/spliced` 前置校验的回归测试（2026-10-29 真机报错）。
 *
 * ## 为什么这是最高优先级
 *
 * 用户真机报错：
 *   历史加载失败： failed to project session "…":
 *   invalid persisted inbox splice at session seq 5 (gateway/internal)
 *
 * 这条错会让**整个会话永久打不开** —— 每次打开都重放到同一处就炸，
 * 用户唯一的出路是删掉重同步。而它的成因极其隐蔽：两端事件序列只差一条，
 * 「插入用户消息」那条被当「已写过」丢掉，「消费它」那条却照写。
 *
 * 所以这道防线是「宁可少一个气泡，也不能让会话变砖」，
 * 而下面第一条用例就是**原样复现用户的现场**。
 */
import assert from 'node:assert';
import fs from 'node:fs';

const here = new URL('.', import.meta.url);
const src = fs.readFileSync(new URL('../lib/index.js', here), 'utf8');

/** 从真实源码里抽出 `rejectInboxSplice`，避免测试与实现各写一份。 */
function loadRejectFn() {
	const lines = src.split('\n');
	const at = lines.findIndex((l) => l.includes('function rejectInboxSplice'));
	assert.ok(at >= 0, '在 lib/index.js 里找不到 rejectInboxSplice');
	let depth = 0;
	let started = false;
	const out = [];
	for (let i = at; i < lines.length; i += 1) {
		out.push(lines[i]);
		for (const ch of lines[i]) {
			if (ch === '{') {
				depth += 1;
				started = true;
			} else if (ch === '}') {
				depth -= 1;
			}
		}
		if (started && depth === 0) break;
	}
	return new Function(`${out.join('\n')}; return rejectInboxSplice;`)();
}

const reject = loadRejectFn();
let pass = 0;
let fail = 0;
const T = (name, cond, extra) => {
	if (cond) {
		pass += 1;
		console.log('  ✓', name);
	} else {
		fail += 1;
		console.log('  ✗', name + (extra ? `  ${extra}` : ''));
	}
};

console.log('\n══ inbox splice 前置校验 ══');

/*
 * ① 复现用户现场：本地日志里「插入」那条丢了（被当成已写过），
 *    「消费」那条（电脑 seq 5）却还在。重放到消费时 inbox 是空的。
 */
const brokenLog = [
	{ type: 'permission/preset' },
	{ type: 'sandbox/mode' },
	{ type: 'approval/policy' },
	{ type: 'session/end-seed' },
];
const rejected = reject({ log: brokenLog }, { target: 'next-turn', start: 0, removedCount: 1, inserted: [] });
T(
	'**空 inbox 上的 removedCount:1 被拒收**（正是 seq 5 那个错）',
	rejected !== null,
	String(rejected),
);

// ② 正常链路：先插入、再消费 ⇒ 必须放行（不能误伤正常会话）
const okLog = [
	{ type: 'agent/inbox/spliced', data: { target: 'next-turn', start: 0, removedCount: 0, inserted: [{ id: 'm1' }] } },
];
T('插入后再消费 ⇒ 放行', reject({ log: okLog }, { target: 'next-turn', start: 0, removedCount: 1, inserted: [] }) === null);

// ③ 插入被丢弃 ⇒ 消费必然被拒（防线真正生效的形态）
T(
	'插入被丢弃后，消费必然被拒',
	reject({ log: [] }, { target: 'next-turn', start: 0, removedCount: 1, inserted: [] }) !== null,
);

// ④ 多条排队时按下标消费
const twoLog = [
	{ type: 'agent/inbox/spliced', data: { target: 'next-turn', start: 0, removedCount: 0, inserted: [{ id: 'a' }] } },
	{ type: 'agent/inbox/spliced', data: { target: 'next-turn', start: 1, removedCount: 0, inserted: [{ id: 'b' }] } },
];
T('两条消息消费第 0 条 ⇒ 放行', reject({ log: twoLog }, { target: 'next-turn', start: 0, removedCount: 1, inserted: [] }) === null);
T('两条消息消费第 5 条 ⇒ 拒绝', reject({ log: twoLog }, { target: 'next-turn', start: 5, removedCount: 1, inserted: [] }) !== null);

// ⑤ 边界：越界 start / 未知 target / 拿不到日志
T('start 越界被拒', reject({ log: okLog }, { target: 'next-turn', start: 9, removedCount: 0, inserted: [] }) !== null);
T('未知 target 被拒', reject({ log: okLog }, { target: 'nope', start: 0, removedCount: 0, inserted: [] }) !== null);
T('拿不到日志 ⇒ 保守拒绝', reject({}, { target: 'next-turn', start: 0, removedCount: 0, inserted: [] }) !== null);

// ⑥ 两个队列分别计数（next-turn 有 1 条 ≠ next-step 也有 1 条）
T(
	'next-turn 与 next-step 分别计数',
	reject({ log: okLog }, { target: 'next-step', start: 0, removedCount: 1, inserted: [] }) !== null,
);

// ⑦ 校验确实接在写入路径上（防止函数存在但没被调用 —— 以前踩过）
T(
	'**校验真的接在 append 之前**（不是只定义没调用）',
	/mirrorEvent[\s\S]{0,20000}?rejectInboxSplice\(session, data\)[\s\S]{0,2000}?session\.append\(event\.type, data, opts\)/.test(src),
);

console.log(`\n════ ${pass} 通过, ${fail} 失败 ════`);
process.exit(fail === 0 ? 0 : 1);
