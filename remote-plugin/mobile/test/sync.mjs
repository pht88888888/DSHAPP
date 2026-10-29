/**
 * 三个「同步类」bug 的回归测试。
 *
 * 这三条都是用户 2026-10-29 报的，而且都属于**「改了十几版都没修好」**的
 * 类型 —— 因为它们都不是「某个函数没调」，而是**判据/类型/数据源本身错了**：
 *
 *   ① 模型列表不同步：模型与预设用了两套不一致的判据
 *      （`isRemoteSession` 缺路径形态兜底，`currentIsRemote` 有）。
 *   ② 同步来的对话没按时间排序：电脑侧把 `updatedAt`（number）
 *      按 string 判断 ⇒ 恒 null ⇒ 排序永远不生效。
 *   ③ 电脑上置顶的对话没同步置顶：宿主根本没把 pin 信息下发。
 *
 * 所以这里的测试全部针对**根因**，而不是「某个函数被调用了」：
 *
 *   · ① 造一个**空** `mirrorSlugs`（刚启动时的真实状态），断言
 *        「路径在 remote/workspaces/ 下」的会话仍被判成电脑会话；
 *   · ② 断言电脑侧 `updatedAt` 原样是 **number**，且排序按数字降序；
 *   · ③ 断言宿主 `listWorkspaces` 的每条会话都带 `pinned` 字段。
 *
 * 运行：node test/sync.mjs
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

let pass = 0;
let fail = 0;
const T = (name, cond, extra) => {
	if (cond) {
		pass += 1;
		console.log('  ✓', name);
	} else {
		fail += 1;
		console.log('  ✗', name + (extra ? '  ' + extra : ''));
	}
};

const here = new URL('.', import.meta.url);
const clientPath = new URL('../lib/client.js', here);
const desktopPath = new URL('../../desktop/lib/index.js', here);

const clientSrc = fs.readFileSync(clientPath, 'utf8');
const desktopSrc = fs.readFileSync(desktopPath, 'utf8');

/* ══════════════════════════════════════════════════════════════════
 * ① 模型列表同步：两条判据必须一致
 * ══════════════════════════════════════════════════════════════════ */
console.log('\n══ ① 模型列表同步判据 ══');

/*
 * 静态检查第一层：`isRemoteSession` 内部必须调用 `isMirrorPath`。
 *
 * 为什么静态检查有效：这个 bug 的本质就是「函数体里少了那条兜底」。
 * 只要它回到「直接读 mirrorSlugs」的写法，用户那个 bug 就回来了，
 * 而动态测试很难覆盖「冷启动 + 空快照 + 新建对话页」这套组合。
 */
T('client.js 定义了统一判据 isMirrorPath', /function isMirrorPath\s*\(/.test(clientSrc));
T(
	'isRemoteSession 走统一判据（不再自己读 mirrorSlugs）',
	/function isRemoteSession[\s\S]{0,600}?return isMirrorPath\(cwd\)/.test(clientSrc),
);
T(
	'isRemoteSession 的 pending 分支也走统一判据',
	/function isRemoteSession[\s\S]{0,900}?return isMirrorPath\(pending\)/.test(clientSrc),
);
T(
	'currentIsRemote（预设侧）也走统一判据',
	/return isMirrorPath\(path\)/.test(clientSrc) &&
		/return isMirrorPath\(pendingPath\)/.test(clientSrc),
);

/*
 * 静态检查第二层：`isMirrorPath` 里必须有**路径形态兜底**。
 *
 * 这是「空快照」那一半的修复。原来的 `isRemoteSession` 只有
 * `mirrorSlugs[slug] === true`，空快照时恒 false。
 */
const mirrorPathBody = (() => {
	const at = clientSrc.indexOf('function isMirrorPath');
	if (at < 0) return '';
	return clientSrc.slice(at, at + 2000);
})();
T(
	'isMirrorPath 有 remote/workspaces/ 路径形态兜底',
	mirrorPathBody.includes("path.indexOf('remote/workspaces/') !== -1"),
);
T(
	'isMirrorPath 兼容 Windows 反斜杠',
	mirrorPathBody.includes("path.indexOf('remote\\\\workspaces\\\\') !== -1"),
);

/*
 * 动态检查：把 `isMirrorPath` 的两条判据抽出来跑真实用例。
 *
 * 这里不装载整个 client.js（它依赖 jsdom 和大量官方服务），
 * 而是**按同一份判据**验证行为，并且用「空 mirrorSlugs」这个
 * 用户真机上一定会遇到的初始状态。
 */
const mirrorSlugs = {}; // ⚠️ 空快照 —— 模拟 loadMirrorSlugs() 还没回来
const mirrorSlugOf = (p) => {
	const at = p.search(/[\\/]workspaces[\\/]/);
	if (at < 0) return undefined;
	return p.slice(at).split(/[\\/]/)[2];
};
const isMirrorPath = (p) => {
	if (typeof p !== 'string' || p === '') return false;
	const slug = mirrorSlugOf(p);
	if (slug !== undefined && mirrorSlugs[slug] === true) return true;
	if (p.indexOf('remote/workspaces/') !== -1) return true;
	if (p.indexOf('remote\\workspaces\\') !== -1) return true;
	return false;
};

T(
	'**空 mirrorSlugs 时，镜像目录仍判成电脑工作区**（这就是那个老 bug）',
	isMirrorPath('/data/user/0/com.dsh/files/home/.dsh/remote/workspaces/a1b2c3d4') === true,
);
T(
	'手机本地目录仍判成本地',
	isMirrorPath('/storage/emulated/0/Documents/proj') === false,
);
T(
	'Windows 镜像目录也判对',
	isMirrorPath('C:\\Users\\me\\.dsh\\remote\\workspaces\\a1b2c3d4') === true,
);
T('空串 / 非字符串安全', isMirrorPath('') === false && isMirrorPath(undefined) === false);
T(
	'⚠️ 状态目录 dsh-remote/ 不会被误判成镜像工作区',
	isMirrorPath('/data/user/0/com.dsh/files/home/.dsh/dsh-remote') === false,
);

/* ══════════════════════════════════════════════════════════════════
 * ② 排序：updatedAt 必须是数字，且按数字降序
 * ══════════════════════════════════════════════════════════════════ */
console.log('\n══ ② 会话按时间排序 ══');

/*
 * ⚠️ 断言前**必须剥掉注释**：本版修复的注释里正引用了那句错误写法
 * 作为「原来的样子」的说明。不剥注释的话，测试会把讲解文字当成代码，
 * 于是「修好了却报失败」—— 这种假失败会让人去改产品代码，比没测试更糟。
 */
const stripComments = (src) =>
	src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');

const desktopCode = stripComments(desktopSrc);

T(
	'桌面插件不再用 typeof === string 判断 updatedAt',
	!/typeof item\.updatedAt === 'string'/.test(desktopCode),
);
T(
	'桌面插件按 number 判断 updatedAt',
	/typeof item\.updatedAt === 'number'/.test(desktopCode),
);
T(
	'排序不再用 localeCompare 比时间戳',
	!/localeCompare\(String\(a\.updatedAt/.test(desktopCode) &&
		!/localeCompare\(String\(b\.updatedAt/.test(desktopCode),
);
T(
	'排序改成数字相减',
	/return right - left/.test(desktopCode),
);

/*
 * 动态：用官方 `updatedAt` 的真实取值方式造数据，验证排序结果。
 *
 * 官方 `updatedAt(header, metadata) = Math.max(header.createdAt, metadata.lastPromptAt ?? 0)`。
 */
const updatedAtOf = (createdAt, lastPromptAt) => Math.max(createdAt, lastPromptAt ?? 0);
const rows = [
	{ sessionId: 'old', updatedAt: updatedAtOf(1000, 5000) },
	{ sessionId: 'newest', updatedAt: updatedAtOf(2000, 900000) },
	{ sessionId: 'mid', updatedAt: updatedAtOf(3000, 50000) },
];
const sorted = [...rows].sort((a, b) => {
	const left = typeof a.updatedAt === 'number' ? a.updatedAt : -1;
	const right = typeof b.updatedAt === 'number' ? b.updatedAt : -1;
	return right - left;
});
T(
	'**数字排序得到正确的时间降序**',
	JSON.stringify(sorted.map((r) => r.sessionId)) === JSON.stringify(['newest', 'mid', 'old']),
	JSON.stringify(sorted.map((r) => r.sessionId)),
);

/*
 * 老代码为什么必然排错 —— 把那个 bug 复现一遍，证明测试有效。
 */
const legacySort = (items) =>
	[...items].sort((a, b) =>
		String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')),
	);
const legacyRows = rows.map((r) => ({
	sessionId: r.sessionId,
	// 老代码的取值方式：非字符串一律 null
	updatedAt: typeof r.updatedAt === 'string' ? r.updatedAt : null,
}));
const legacySorted = legacySort(legacyRows).map((r) => r.sessionId);
T(
	'（复现）老代码把数字全变 null ⇒ 排序结果错乱',
	JSON.stringify(legacySorted) !== JSON.stringify(['newest', 'mid', 'old']),
	JSON.stringify(legacySorted),
);

/*
 * ②b 时间覆盖逻辑的行为验证（从**真实源码**里抽函数跑，不是另写一份实现）。
 *
 * 这一组是本轮修复里最容易写错的部分：判据必须是
 * 「本地时间戳 ≈ 建壳时刻 ⇒ 假值 ⇒ 用电脑时间」，而不能是「取 max」——
 * 后者在所有同步会话上都是空操作（假值必然比电脑时间大），
 * 代码看起来完全正确，用户看到的却还是「没修」。
 */
console.log('\n══ ②b 时间覆盖的真实行为 ══');

{
	const lines = clientSrc.split('\n');
	const grab = (marker) => {
		const at = lines.findIndex((l) => l.includes(marker));
		if (at < 0) throw new Error('找不到 ' + marker);
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
		return out.join('\n');
	};
	const clockSrc = grab('var applyDesktopClock = function');
	const wrapSrc = grab('var wrapSessionListClock = function');
	const run = (times, syncedAt, ver) =>
		new Function(`
			var desktopTimes = arguments[0], desktopSyncedAt = arguments[1], metaVersion = arguments[2];
			${clockSrc}
			${wrapSrc}
			return { applyDesktopClock, wrapSessionListClock };
		`)(times, syncedAt, ver);

	const mkList = (rows) => {
		const byId = {};
		const ids = [];
		for (const r of rows) {
			byId[r.id] = { id: r.id, updatedAt: r.at };
			ids.push(r.id);
		}
		const snap = { ids, byId, phase: 'ready' };
		return {
			_snap: snap,
			getSnapshot() {
				return snap;
			},
			subscribe() {
				return () => {};
			},
			update() {},
			set() {},
		};
	};
	const rank = (s) =>
		Object.values(s.byId)
			.map((r) => ({ id: r.id, rank: r.updatedAt }))
			.sort((x, y) => y.rank - x.rank)
			.map((r) => r.id);

	// 真实的场景：同步发生在 T0，所有空壳的本地时间戳都 ≈ T0
	const T0 = 1700000000000;
	const list = mkList([
		{ id: 'a', at: T0 + 10 },
		{ id: 'b', at: T0 + 11 },
		{ id: 'c', at: T0 + 12 },
	]);
	// 电脑上的真实时间：a 最新、c 最老（都**早于** T0，这是关键）
	const w = run(
		{ a: T0 - 3600000, b: T0 - 7200000, c: T0 - 10800000 },
		{ a: T0, b: T0, c: T0 },
		1,
	).wrapSessionListClock(list);
	const got = w.getSnapshot();

	T(
		'**假值被电脑时间替换，哪怕电脑时间更小**',
		got.byId.a.updatedAt === T0 - 3600000,
		String(got.byId.a.updatedAt),
	);
	T(
		'**排序结果 = 电脑上的真实时间顺序**',
		JSON.stringify(rank(got)) === JSON.stringify(['a', 'b', 'c']),
		JSON.stringify(rank(got)),
	);

	// 真值：用户在同步之后两天真的在手机上聊过 ⇒ 不能被拉回去
	const w2 = run({ x: T0 - 3600000 }, { x: T0 }, 1).wrapSessionListClock(
		mkList([{ id: 'x', at: T0 + 172800000 }]),
	);
	T(
		'**同步后真聊过的会话不被拉回去**',
		w2.getSnapshot().byId.x.updatedAt === T0 + 172800000,
	);

	// 老版本同步的会话没有建壳时刻 ⇒ 退回取 max（不倒退）
	const w3 = run({ y: T0 - 5000 }, {}, 1).wrapSessionListClock(mkList([{ id: 'y', at: T0 }]));
	T('无建壳时刻时取 max（不倒退）', w3.getSnapshot().byId.y.updatedAt === T0);

	// 没有电脑数据 ⇒ 原样返回同一个对象（零开销、身份不变）
	const plain = mkList([{ id: 'z', at: 42 }]);
	T(
		'**没有电脑时间时返回同一对象**（不改身份，React 不会重渲染）',
		run({}, {}, 0).wrapSessionListClock(plain).getSnapshot() === plain._snap,
	);
	T('快照身份稳定（同一源 + 同版本 ⇒ 同一对象）', w.getSnapshot() === w.getSnapshot());

/*
 * ⑩ hook 引用必须跟着换 —— 本轮排序/置顶失效的**真正原因**。
 *
 * 官方侧边栏读数据走 slot hook，不走 `ctx.sessions.list`：
 *   dsh-client-ui-session/lib/client.js:1106
 *     ctx.slots.provideRoot({ hooks: { sessions: ctx.sessions.list } })
 *   → dsh-client-ui-renderer/lib/client.js:1681 rebuildRootBinding()
 *     里 copyUnique() **按引用拷贝**
 *
 * 也就是说 hook 在 ui-session 插件加载那一刻就抓住了旧对象。
 * 只做 `ctx.sessions.list = wrapped` 的话，界面读的仍是旧对象
 * ⇒ 排序与置顶的代码**从未被调用过**。
 *
 * 这几条断言锁住「改了属性还要改 hook 引用」这个容易被后人"优化"掉的关键点。
 */
{
	T(
		'**包装后同步替换 slots hook 里冻结的引用**',
		/_rootContributions/.test(clientSrc) && /hooks\.sessions = wrapped/.test(clientSrc),
	);
	T(
		'替换后重建 root binding（否则界面拿不到新引用）',
		/rebuildRootBinding/.test(clientSrc),
	);
	T(
		'拿不到官方内部结构时静默跳过（不让整个插件报错）',
		/replace\s*sessions hook|替换 sessions hook 引用失败/.test(clientSrc),
	);
	T(
		'只在真的换掉了对象时才重建（避免无意义重建）',
		/swapped > 0/.test(clientSrc),
	);
}

	/*
	 * ⑧ 本地 `updatedAt` **缺失**时也必须用电脑时间。
	 *
	 * 这是用户截图上真实可见的形态：手机侧那批会话**没有任何相对时间标签**
	 *（电脑端每条都有「3分钟」「1小时」），正是 `updatedAt` 为 undefined 的表现。
	 * 上一版代码遇到非数字就 `continue`，于是「最该被修的会话」恰好被跳过 ——
	 * 用户看到的就是「重新同步了还是没排序」。
	 */
	const missing = mkList([{ id: 'm', at: undefined }]);
	const wm = run({ m: T0 - 999 }, {}, 1).wrapSessionListClock(missing);
	T(
		'**本地没有 updatedAt 时也覆盖成电脑时间**',
		wm.getSnapshot().byId.m.updatedAt === T0 - 999,
		String(wm.getSnapshot().byId.m.updatedAt),
	);

	// 0 / null 同样属于「不可用」，必须覆盖（否则会被排到最后）
	const zero = mkList([{ id: 'z0', at: 0 }]);
	T(
		'本地 updatedAt 为 0 时也覆盖',
		run({ z0: T0 - 500 }, {}, 1).wrapSessionListClock(zero).getSnapshot().byId.z0.updatedAt === T0 - 500,
	);
}

/* ══════════════════════════════════════════════════════════════════
 * ③ 置顶：宿主必须下发 pinned，前端必须调官方 pinSession
 * ══════════════════════════════════════════════════════════════════ */
console.log('\n══ ③ 置顶状态同步 ══');

T(
	'桌面插件读 workspaceRegistry.pinnedSessionIds',
	/pinnedSessionIds/.test(desktopSrc),
);
T(
	'listWorkspaces 的每条会话带 pinned 字段',
	/listWorkspaces[\s\S]{0,6000}?pinned: pinnedSet\.has\(sessionId\)/.test(desktopSrc),
);
T(
	'listWorkspaceSessions 的每条会话带 pinned 字段',
	/pinned: pinnedOf\(item\.sessionId\)/.test(desktopSrc),
);
T('前端有 desktopMeta 桥的读取端', /bridge\('desktopMeta'/.test(clientSrc));
T('前端调官方 workspaces.pinSession', /workspaces\.pinSession\(target\)/.test(clientSrc));
T(
	'置顶只在会话「本地已知」后才做（byId 命中检查）',
	/if \(byId\[sessionId\] === undefined\) continue;/.test(clientSrc),
);
T(
	'置顶失败有退避，不会每帧打 RPC',
	/now - last < 10000/.test(clientSrc),
);

/*
 * ⑪ 数据到位必须**主动**重试置顶。
 *
 * 订阅只在 `sessions.list` 快照变化时触发，而置顶数据是异步拉回来的。
 * 时序：apply() 建订阅（desktopPins 还是空）→ 数据到位 → 快照没变
 * ⇒ 回调不跑 ⇒ 置顶永远不生效。所以必须有一次主动重试。
 */
T(
	'**数据到位后主动重试置顶**（不能只等快照变化）',
	/firePinRetry\(\)/.test(clientSrc) && /pinRetryHooks\.push/.test(clientSrc),
);
T(
	'重试在 adoptDesktopMeta 里被触发',
	/desktopPins = nextPins;[\s\S]{0,600}?firePinRetry\(\)/.test(clientSrc),
);
T(
	'每条重试都包 try/catch（置顶失败不能影响主流程）',
	/pinRetryHooks\[at\]\(\)[\s\S]{0,200}?catch/.test(clientSrc),
);
T(
	'时间表有版本号，数据更新时缓存会失效',
	/metaVersion \+= 1/.test(clientSrc),
);

/* ══════════════════════════════════════════════════════════════════
 * ④ 宿主落盘：desktop-meta.json 的读写必须能往返
 * ══════════════════════════════════════════════════════════════════ */
console.log('\n══ ④ 宿主元数据落盘往返 ══');

T(
	'mobile index.js 用独立文件存元数据（不挤进 sessions.json）',
	/DESKTOP_META_PATH = join\(STATE_DIR, 'desktop-meta\.json'\)/.test(
		fs.readFileSync(new URL('../lib/index.js', here), 'utf8'),
	),
);

/*
 * 「建壳时刻」必须端到端走通：宿主记录 → 落盘 → 桥 → 前端判据。
 *
 * 少一环就是「代码看起来修了、用户看到还是没修」—— 这一批 bug
 * 反复出现的典型原因。
 */
const mobileSrc = fs.readFileSync(new URL('../lib/index.js', here), 'utf8');
T('宿主有 syncedAt 的读取器', /function loadDesktopSyncedAt\(\)/.test(mobileSrc));
T('宿主在创建空壳时记下时刻', /desktopSyncedAt\[sessionId\] = Date\.now\(\)/.test(mobileSrc));
T('宿主把 syncedAt 一并写盘', /saveDesktopMeta\(desktopTimes, desktopPins, desktopSyncedAt/.test(mobileSrc));
/*
 * ⑨ 「补记账」必须排在 `syncedSessions.has()` 的 `continue` **之前**。
 *
 * 否则老版本同步出来的会话永远走不到 `rememberDesktopMeta`
 * ⇒ 时间表恒空 ⇒ 排序与置顶对这批会话永远不生效，
 * 而且用户「重新同步」多少次都是同一个结果（这正是他报障时的感受）。
 */
{
	const fnStart = mobileSrc.indexOf("case 'autoSyncDesktop'");
	const fnEnd = mobileSrc.indexOf("case 'syncConversations'");
	const slice = mobileSrc.slice(fnStart, fnEnd > fnStart ? fnEnd : fnStart + 20000);
	const atMeta = slice.indexOf('io.rememberDesktopMeta?.(sessionId, item?.updatedAt, item?.pinned)');
	const atSkip = slice.indexOf('if (io.syncedSessions.has(sessionId))');
	T(
		'**补记账排在「已同步就跳过」之前**（否则老会话永远补不上）',
		atMeta >= 0 && atSkip >= 0 && atMeta < atSkip,
		`meta@${atMeta} skip@${atSkip}`,
	);
}
T('desktopMeta 桥下发 syncedAt', /syncedAt: \{ \.\.\.\(io\.desktopSyncedAt/.test(mobileSrc));
T('前端接收 syncedAt', /var nextSynced = \{\};/.test(clientSrc));
T('前端用建壳时刻判假值', /isFake = local <= syncedAt \+ 120000/.test(clientSrc));

/*
 * 动态往返：模拟 readDesktopMeta 的校验逻辑，确认脏数据进不来。
 */
const validate = (raw) => {
	const empty = { times: {}, pins: {} };
	let parsed;
	try {
		parsed = JSON.parse(raw);
	} catch {
		return empty;
	}
	if (parsed === null || typeof parsed !== 'object') return empty;
	const times = {};
	const rawTimes = parsed.times;
	if (rawTimes !== null && typeof rawTimes === 'object') {
		for (const [k, v] of Object.entries(rawTimes)) {
			if (typeof k !== 'string' || k === '') continue;
			if (typeof v !== 'number' || !Number.isFinite(v) || v < 0) continue;
			times[k] = v;
		}
	}
	const pins = {};
	const rawPins = parsed.pins;
	if (rawPins !== null && typeof rawPins === 'object') {
		for (const [k, v] of Object.entries(rawPins)) {
			if (typeof k === 'string' && k !== '' && v === true) pins[k] = true;
		}
	}
	return { times, pins };
};

const roundTrip = validate(
	JSON.stringify({ times: { a: 1700000000000, b: 'oops', c: -5, d: 1.5 }, pins: { a: true, b: 1, c: false } }),
);
T('合法时间戳保留', roundTrip.times.a === 1700000000000);
T('**字符串时间戳被丢弃**（否则排序得 NaN）', roundTrip.times.b === undefined);
T('负数时间戳被丢弃', roundTrip.times.c === undefined);
T('小数时间戳保留（不误伤合法值）', roundTrip.times.d === 1.5);
T('只有严格 true 才算置顶', roundTrip.pins.a === true && roundTrip.pins.b === undefined && roundTrip.pins.c === undefined);
T('损坏的 JSON 返回空结构', JSON.stringify(validate('{not json')) === JSON.stringify({ times: {}, pins: {} }));

/*
 * syncedAt 也要按同一套规则校验：它的用途是「和本地时间戳做比较」，
 * 混进字符串/NaN 会让 `local <= syncedAt + 120000` 恒为 false ⇒
 * 判假值永远失败 ⇒ 排序修复静默失效。
 */
const validateSynced = (raw) => {
	let parsed;
	try {
		parsed = JSON.parse(raw);
	} catch {
		return {};
	}
	const out = {};
	const src = parsed?.syncedAt;
	if (src !== null && typeof src === 'object') {
		for (const [k, v] of Object.entries(src)) {
			if (typeof k !== 'string' || k === '') continue;
			if (typeof v !== 'number' || !Number.isFinite(v) || v < 0) continue;
			out[k] = v;
		}
	}
	return out;
};
const syncedOut = validateSynced(
	JSON.stringify({ syncedAt: { ok: 1700000000000, bad: '1700', neg: -1, nan: null } }),
);
T('建壳时刻只保留合法数字', Object.keys(syncedOut).length === 1 && syncedOut.ok === 1700000000000, JSON.stringify(syncedOut));

/* ══════════════════════════════════════════════════════════════════ */

console.log('\n════════ ' + pass + ' 通过, ' + fail + ' 失败 ════════');
process.exit(fail === 0 ? 0 : 1);
