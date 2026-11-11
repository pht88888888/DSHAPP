/**
 * DSH Android 插件市场 —— 目录采集核心。
 *
 * 从 npm registry 按两个关键词拉取插件目录：
 *
 *   · `dshapp-plugin`          社区区（任何人 `npm publish` 带上即可）
 *   · `dshapp-plugin-featured` 精选区（维护者人工审核后补上该关键词再发版）
 *
 * 一个包同时带两个关键词时按**精选**归类，不会重复出现。
 *
 * ## 这个模块被两处共用
 *
 *   · `lib/index.js`（宿主半）—— 设置页打开市场时**实时**扫一次，带缓存。
 *   · `tools/market-collect.mjs`（构建期 CLI）—— 产出一份**离线种子**，
 *     打进插件包里，宿主半扫不到网时回落到它。
 *
 * 单一真源的意义：种子与实时结果**结构完全一致**，客户端半不需要写两套解析。
 *
 * ## 为什么是关键词不是 GitHub topic
 *
 * 项目早期评估过扫 GitHub `topic:`，放弃，两条硬理由：
 *   1. **未认证 GitHub API 只有 60 次/小时**（本机实测 `core remaining=0/60`），
 *      每个用户都扫一遍必然撞限流；npm registry 的 search 端点无此问题。
 *   2. GitHub 那条路只覆盖「开源了源码」的包，而市场要能**一键安装**就必须有
 *      npm 包 —— 关键词扫描天然只返回已发布的包，不需要额外判断。
 *
 * ## ⚠️ search 端点的分页硬顶
 *
 * 实测（2026-10-15）：`from` 超过 **6000** 之后**静默重置回第一条**，不报错、
 * 不返回错误码。不处理的话会把前面几千条**重复采集一遍**，而且看不出异常。
 * 本模块因此在 `from >= PAGE_LIMIT` 处硬停，并给结果打 `truncated: true`。
 *
 * 当前两个关键词都远低于该上限（精选 6 个），所以这条只是防线。
 * 真到那一天的正解是改用 CouchDB `_all_docs` 全量拉取再本地过滤。
 *
 * @module dsh-android-market/collect
 */

/** 社区区关键词。任何 DSH 插件包带上它就会被收录。 */
export const KEYWORD_COMMUNITY = 'dshapp-plugin';

/** 精选区关键词。维护者人工审核后补上再发版，优先级高于社区区。 */
export const KEYWORD_FEATURED = 'dshapp-plugin-featured';

/**
 * 扫描用的 registry。
 *
 * ⚠️ **必须是 npm 官方源**，不能跟随本机镜像配置。实测（2026-10-15）：
 * npmmirror 对 `keywords:dshapp-plugin` 返回 `total=0` —— 镜像的关键词索引
 * 有明显滞后，新关键词在镜像上根本搜不到。而插件的**安装**走镜像没问题
 * （packument 与 tarball 都同步得很快）。所以这里是「查目录用官方源、
 * 装包用镜像源」，两者刻意不同。
 */
export const OFFICIAL_REGISTRY = 'https://registry.npmjs.org';

/** search 端点的分页硬顶（见文件头）。 */
const PAGE_LIMIT = 5000;

/** 单页条数。官方上限 250。 */
const PAGE_SIZE = 250;

/** 单次请求超时。 */
const TIMEOUT_MS = 30000;

/** 拉 packument 的并发数。太低会慢，太高容易被 registry 限流。 */
const CONCURRENCY = 8;

/**
 * 已知在 Android 上**不可用或缺原生绑定**的依赖。
 *
 * 判据来自 AGENTS.md 的既有结论（每一条都是真机踩过的）：
 *   · `sharp`            → ⑧ 无 android 预编译，装 wasm32 变体才能用
 *   · `@vscode/ripgrep`  → ⑤ 官方 optionalDependencies 没有 android 变体
 *   · `node-pty`         → ⑩ 无 android 绑定，侧栏终端打不开
 *   · `node-addon-*`     → 官方无 android 平台包，启动即 No usable native binding
 *
 * ⚠️ **这不是黑名单，是提示**。插件装了这些依赖不代表一定坏 —— 它可能自己
 * 塞了 android 产物（本 App 处理 sharp 就是这个做法）。所以只标警告，不隐藏。
 */
const NATIVE_RISK = [
	{ re: /^sharp$/, why: 'sharp 无 android 预编译，需 wasm32 变体' },
	{ re: /^@img\/sharp/, why: 'sharp 的 wasm 变体，需随包分发' },
	{ re: /^@vscode\/ripgrep/, why: 'ripgrep 无 android 平台包' },
	{ re: /^node-pty$/, why: 'node-pty 无 android 绑定' },
	{ re: /^node-addon-/, why: '原生插件，官方无 android 平台包' },
	{ re: /^@deepseek-ai\/node-addon-/, why: '原生插件，官方无 android 平台包' },
	{ re: /^better-sqlite3$/, why: '原生模块，需 android 预编译' },
	{ re: /^@napi-rs\//, why: 'N-API 原生模块，需 android 产物' },
];

/** 带超时的 fetch + JSON 解析。不读环境代理。 */
async function fetchJson(url, timeoutMs, signal) {
	const timeout = AbortSignal.timeout(timeoutMs);
	// 调用方的取消信号与超时信号合成一个：任一触发都中断请求。
	const composed = signal ? AbortSignal.any([timeout, signal]) : timeout;
	const res = await fetch(url, { signal: composed });
	if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText} :: ${url}`);
	return res.json();
}

/**
 * 按关键词翻页拉取包名。
 *
 * ⚠️ **必须验重复**：分页硬顶之后 `from` 会被静默重置，只看返回条数发现不了。
 * 这里用「本页首条是否等于之前某页首条」做兜底检测，命中即停并标记 truncated。
 *
 * @returns {{packages: string[], truncated: boolean}}
 */
async function searchKeyword(registry, keyword, timeoutMs, signal, onNote) {
	const seen = new Set();
	const firsts = new Set();
	const packages = [];
	let truncated = false;

	for (let from = 0; from < PAGE_LIMIT; from += PAGE_SIZE) {
		const url =
			`${registry}/-/v1/search?text=${encodeURIComponent(`keywords:${keyword}`)}` +
			`&size=${PAGE_SIZE}&from=${from}`;
		const json = await fetchJson(url, timeoutMs, signal);
		const objects = json.objects ?? [];
		if (objects.length === 0) break;

		// 分页重置检测：首条与之前某页首条相同 ⇒ from 被忽略了
		const first = objects[0]?.package?.name;
		if (first && firsts.has(first)) {
			truncated = true;
			onNote?.(
				`${keyword}: from=${from} 起返回重复内容（分页被重置），提前停止。` +
					`目录可能大于 ${PAGE_LIMIT} 条。`,
			);
			break;
		}
		if (first) firsts.add(first);

		for (const o of objects) {
			const name = o?.package?.name;
			if (!name || seen.has(name)) continue;
			seen.add(name);
			packages.push(name);
		}

		// 收满就不必再翻
		if (packages.length >= (json.total ?? 0)) break;
	}

	return { packages, truncated };
}

/**
 * 拉单个包的最新 manifest，提取市场需要的全部展示与判据字段。
 *
 * 这里用的是 **packument**（`/<name>`）而不是 search 的返回体 —— 只有它带
 * `dependencies` / `dsh` / `repository`，而自动风险标记全靠这几个字段。
 */
async function fetchManifest(registry, name, timeoutMs, signal) {
	const url = `${registry}/${name.replace('/', '%2F')}`;
	let json;
	try {
		json = await fetchJson(url, timeoutMs, signal);
	} catch (err) {
		return { name, error: err.message };
	}

	const latest = json['dist-tags']?.latest;
	const v = latest ? json.versions?.[latest] : null;
	if (!v) return { name, error: `版本 ${latest ?? '(无 dist-tags)'} 的 manifest 缺失` };

	const deps = {
		...(v.dependencies ?? {}),
		...(v.optionalDependencies ?? {}),
		...(v.peerDependencies ?? {}),
	};

	// 原生依赖风险（只警告，不隐藏）
	const risks = [];
	for (const [dep] of Object.entries(deps)) {
		for (const r of NATIVE_RISK) {
			if (r.re.test(dep)) risks.push(`${dep} —— ${r.why}`);
		}
	}

	const hasBundle = Boolean(v.dsh?.bundle?.patch);
	const hasClient = Boolean(v.dsh?.client);

	// 没有 dsh.bundle 就**装不上**（这正是官方市场文档里最常见的被拒原因）
	if (!hasBundle) {
		risks.push('未声明 dsh.bundle.patch —— 无法作为插件包安装');
	}

	const repo = v.repository;
	const repoUrl = typeof repo === 'string' ? repo : (repo?.url ?? null);

	return {
		name: v.name ?? name,
		version: latest,
		description: v.description ?? '',
		keywords: v.keywords ?? [],
		license: v.license ?? null,
		author: typeof v.author === 'string' ? v.author : (v.author?.name ?? null),
		publisher: json.maintainers?.[0]?.name ?? null,
		homepage: v.homepage ?? null,
		repository: repoUrl ? repoUrl.replace(/^git\+/, '').replace(/\.git$/, '') : null,
		engines: v.engines ?? null,
		dsh: {
			bundle: hasBundle,
			client: hasClient,
			manifestVersion: v.dsh?.manifestVersion ?? null,
			dshReleases: v.dsh?.compatibility?.dshReleases ?? null,
		},
		installable: hasBundle,
		risks,
		publishedAt: json.time?.[latest] ?? null,
	};
}

/** 按并发上限跑一批异步任务，保持结果顺序。 */
async function mapLimit(items, limit, fn) {
	const out = new Array(items.length);
	let next = 0;
	const workers = new Array(Math.min(limit, items.length)).fill(0).map(async () => {
		for (;;) {
			const i = next++;
			if (i >= items.length) return;
			out[i] = await fn(items[i], i);
		}
	});
	await Promise.all(workers);
	return out;
}

/**
 * 采集一次完整目录。
 *
 * @param {object} [options]
 * @param {string} [options.registry]  扫描用的 registry，默认 npm 官方源。
 * @param {string} [options.community] 社区区关键词。
 * @param {string} [options.featured]  精选区关键词。
 * @param {number} [options.timeoutMs] 单次请求超时。
 * @param {number} [options.concurrency] 拉 manifest 的并发数。
 * @param {AbortSignal} [options.signal] 取消信号（客户端断开时用）。
 * @param {(done: number, total: number) => void} [options.onProgress] 进度回调。
 * @param {(message: string) => void} [options.onNote] 诊断信息回调。
 * @returns {Promise<object>} 目录载荷（结构见文件头）。
 */
export async function collectCatalog(options = {}) {
	const registry = (options.registry ?? OFFICIAL_REGISTRY).replace(/\/+$/, '');
	const communityKw = options.community ?? KEYWORD_COMMUNITY;
	const featuredKw = options.featured ?? KEYWORD_FEATURED;
	const timeoutMs = options.timeoutMs ?? TIMEOUT_MS;
	const concurrency = options.concurrency ?? CONCURRENCY;
	const signal = options.signal;
	const onNote = options.onNote;

	const community = await searchKeyword(registry, communityKw, timeoutMs, signal, onNote);
	const featured = await searchKeyword(registry, featuredKw, timeoutMs, signal, onNote);

	// 精选优先：同一个包两个关键词都带时归精选，且只出现一次
	const featuredSet = new Set(featured.packages);
	const onlyCommunity = community.packages.filter((n) => !featuredSet.has(n));
	const all = [...featured.packages, ...onlyCommunity];

	let done = 0;
	const manifests = await mapLimit(all, concurrency, async (name) => {
		const m = await fetchManifest(registry, name, timeoutMs, signal);
		options.onProgress?.(++done, all.length);
		return m;
	});

	const ok = manifests.filter((m) => !m.error);
	const failed = manifests.filter((m) => m.error);

	// 精选/社区分区：拿 manifest 里的真实关键词判定，不依赖扫描顺序
	const payload = {
		schemaVersion: 1,
		generatedAt: new Date().toISOString(),
		registry,
		keywords: { community: communityKw, featured: featuredKw },
		truncated: community.truncated || featured.truncated,
		counts: { featured: 0, community: 0, total: ok.length },
		plugins: [],
	};

	for (const m of ok) {
		const isFeatured = (m.keywords ?? []).includes(featuredKw);
		payload.counts[isFeatured ? 'featured' : 'community']++;
		payload.plugins.push({ ...m, featured: isFeatured });
	}

	// 精选在前，其余按名称排序（顺序稳定，便于 diff）
	payload.plugins.sort((a, b) => {
		if (a.featured !== b.featured) return a.featured ? -1 : 1;
		return a.name.localeCompare(b.name);
	});

	if (failed.length > 0) {
		payload.failed = failed.map((f) => ({ name: f.name, error: f.error }));
	}

	return payload;
}
