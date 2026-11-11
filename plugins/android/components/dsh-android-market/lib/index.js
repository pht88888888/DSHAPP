/**
 * dsh-android-market —— 宿主半（host half）。
 *
 * ## 这个插件做什么
 *
 * 官方 DSH 只有一个「输入包名安装」的对话框，**没有可浏览的市场**。本插件补上：
 * 从 npm 按关键词扫出插件目录，在设置页渲染成可搜索、可一键安装的列表。
 *
 * 分两层：
 *   · **宿主半**（本文件）—— 采集目录并挂在 HTTP 路由上。
 *   · **客户端半**（`lib/client.js`）—— 设置页的分节 UI，安装动作直接复用
 *     官方 `pluginManager` 的 Remote 服务（见下）。
 *
 * ## 为什么采集放在宿主半，而不是客户端半
 *
 * 浏览器进程不能直连 npm：那个页面的 CSP 与同源策略都不允许跨域请求
 * `registry.npmjs.org`。而宿主半跑在 Node 里，`fetch` 直连可用（实测 ~1.1s）。
 * 所以「扫」在宿主、「显示」在客户端，中间走本 App 自己的 HTTP 路由。
 *
 * ## 为什么安装动作**不**自己实现
 *
 * 官方 `@deepseek-ai/dsh-plugin-manager` 已经提供了完整且安全的安装通道：
 * `inspect(spec)` 先问 registry 这个包存在吗、是不是插件包，`installBundle(spec)`
 * 再交给 pnpm 装、处理注册表回退、构建脚本审批、版本兼容性检查、失败回滚。
 *
 * ⚠️ **刻意不自己 spawn pnpm**：那条路上有太多本项目已经踩过的东西 ——
 * pnpm 会把 profile 的「多余依赖」挪进 `node_modules/.ignored/`（见 AGENTS.md ⑥）、
 * `--allow-build` 会把作者可控的包名泄漏进 registry（dsh-market 因此废弃了
 * 一键安装）、装错包要恢复 `package.json` 与 `pnpm-lock.yaml`。这些官方都做好了。
 *
 * 所以客户端半调 `ctx.remote.pluginManager.installBundle(name)`，本文件完全不碰安装。
 *
 * ## 目录的来源：实时扫 + 离线种子回落
 *
 * 市场必须能看见**刚发布**的插件，所以首选实时扫描。但真机上网络可能不可达
 * （无网、代理不通、registry 超时），那时不能给用户一个空列表 ——
 * 于是回落包内 `lib/seed.json`（构建期由 `tools/market-collect.mjs` 生成）。
 *
 * 两者的结构**完全一致**（同一个 `collectCatalog()` 产出），客户端半不需要写两套解析。
 *
 * @module dsh-android-market
 */

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { collectCatalog, KEYWORD_COMMUNITY, KEYWORD_FEATURED, OFFICIAL_REGISTRY } from './collect.mjs';

export const name = 'dsh-android-market';

/** 目录接口挂在 web 组合的 HTTP 载体上，所以依赖 `webServer`。 */
export const inject = ['webServer'];

/** 客户端半轮询/拉取目录的路径。 */
const ROUTE = '/api/dsh-android/market';

/**
 * 实时扫描的结果缓存多久。
 *
 * 10 分钟：足够让「打开设置页来回切」不重复扫（一次扫要拉几百个 packument，
 * 首次约 5~15 秒），又短到「刚发布的插件」最多等 10 分钟就能看到 ——
 * 而且用户随时可以点「刷新」强制重扫。
 */
const CACHE_TTL_MS = 10 * 60 * 1000;

/**
 * 实时扫描的硬超时。
 *
 * 超时后**不再等**，直接回落种子（并把这个事实告诉界面）。
 * 给 20 秒：真机在正常网络下首次扫描约 5~15 秒，20 秒足够；
 * 而用户对着「加载中」的耐心也就这么长。
 */
const SCAN_TIMEOUT_MS = 20000;

/** 种子文件的绝对路径。与 `lib/index.js` 同目录。 */
const SEED_PATH = fileURLToPath(new URL('./seed.json', import.meta.url));

/**
 * 进程内的目录缓存。
 *
 * `pending` 是**在飞的扫描**：多个请求同时进来时共用同一次扫描，
 * 否则设置页打开 + 用户点刷新会并发跑两遍全量拉取，把 registry 打疼。
 */
let cache = null;
let pending = null;

/** 读种子（离线回落用）。读不到就返回一个空目录，不让市场整个崩掉。 */
async function readSeed() {
	try {
		const raw = await readFile(SEED_PATH, 'utf8');
		const parsed = JSON.parse(raw);
		return { ...parsed, source: 'seed' };
	} catch (error) {
		return {
			schemaVersion: 1,
			generatedAt: null,
			registry: OFFICIAL_REGISTRY,
			keywords: { community: KEYWORD_COMMUNITY, featured: KEYWORD_FEATURED },
			truncated: false,
			counts: { featured: 0, community: 0, total: 0 },
			plugins: [],
			source: 'seed',
			seedError: String(error?.message ?? error),
		};
	}
}

/**
 * 取一份目录。
 *
 * 顺序：内存缓存 → 实时扫描（带超时）→ 种子。
 *
 * @param {object} ctx - Cordis 上下文（用来打日志）。
 * @param {boolean} force - 跳过缓存，强制重扫（用户点「刷新」时）。
 */
async function getCatalog(ctx, force) {
	const now = Date.now();
	if (!force && cache && now - cache.at < CACHE_TTL_MS) {
		return { ...cache.value, cached: true };
	}

	// 已经在扫了：共用那一次，不再起第二个
	if (pending) return pending;

	pending = (async () => {
		try {
			const started = Date.now();
			const payload = await collectCatalog({
				signal: AbortSignal.timeout(SCAN_TIMEOUT_MS),
				onNote: (message) => ctx.logger?.warn?.(`[dsh-android-market] ${message}`),
			});
			const value = { ...payload, source: 'live' };
			cache = { at: Date.now(), value };
			ctx.logger?.info?.(
				`[dsh-android-market] 目录已更新：精选 ${payload.counts.featured} 个，` +
					`社区 ${payload.counts.community} 个（${Date.now() - started}ms）`,
			);
			return value;
		} catch (error) {
			// 实时扫描失败不是致命错误：回落种子，并把原因带回界面。
			// 用户看到的是「有一份可能过时的目录 + 为什么是过时的」。
			const reason = String(error?.message ?? error);
			ctx.logger?.warn?.(`[dsh-android-market] 实时扫描失败，回落离线种子: ${reason}`);
			const seed = await readSeed();
			return { ...seed, liveError: reason };
		} finally {
			pending = null;
		}
	})();

	return pending;
}

/**
 * 目录接口的**准入判据**：直接复用引擎自己那一套（外部审计 5.1 同类问题第二例）。
 *
 * ## ⚠️ 为什么原来那样是不行的
 *
 * 第一版只判了 `req.method !== 'GET'`。而本路由是**精确路径**注册的，
 * 引擎的 `match()` 是「精确表优先、再长前缀胜」⇒ `/api/dsh-android/market`(23)
 * 比 `/api`(4) 长，**引擎自己的 cookie 门根本不会被走到**。实测：
 * 带 `Host: evil.example` 的无凭据请求拿到 **200 + 全量目录**，而且
 * `?refresh=1` 能让手机去扫 200+ 个 npm packument（跨站 GET 同样能触发）。
 *
 * 攻击者是**手机上任意其它 App**（Android 的 loopback 跨 App 可达），
 * 前置条件只是「App 在跑」——端口固定 3080。
 *
 * ## 判据
 *
 * `connection.requestRejection(req)` 是引擎给页面用的那把唯一的门
 * （`dsh-client-connection`）：它查 `Host` 是不是 loopback + 会话 cookie。
 * 页面自己的 `fetch` 天然带着 cookie，所以这道门**对页面完全透明**。
 *
 * ## ⚠️ 取不到 `connection` 一律 **503（fail-closed）**，绝不降级
 *
 * 与手机桥（`dsh-remote-mobile/lib/index.js` 的 `bridgeRejection`）同款取舍，
 * 理由也一样：cordis 的 `ctx.get(name, strict)` 对尚未 ACTIVE 的提供者返回
 * `undefined`（`cordis/lib/index.js:770`），而 client-connection 的
 * `await BrowserAuth.create(...)` 排在 `provide('connection')` 之前 ⇒
 * 引擎启动 / 插件重载期间本路由**已经在收请求**，而服务还没就绪。
 *
 * 「服务缺失」是暂时不可用（界面显示 HTTP 503，点一下刷新就好），
 * 「降级到不查 cookie 的判据」是一直不安全 —— 后者严重得多。
 * `requestRejection` 自己抛错同样 503：判据没跑成就等于没有判据。
 *
 * @param ctx - Cordis 上下文。
 * @param req - 请求。
 * @returns 拒绝时的 HTTP 状态码，放行时 `undefined`。
 */
function marketRejection(ctx, req) {
	const connection = ctx.get?.('connection');
	if (connection === undefined || typeof connection.requestRejection !== 'function') {
		if (!marketGateWarned) {
			marketGateWarned = true;
			ctx.logger?.warn?.(
				'[dsh-android-market] connection 服务尚未就绪，目录接口一律 503（fail-closed，不降级到只判方法）',
			);
		}
		return 503;
	}
	try {
		return connection.requestRejection(req);
	} catch (error) {
		ctx.logger?.warn?.(
			`[dsh-android-market] connection.requestRejection 抛错，目录接口一律 503（fail-closed）: ${String(error?.message ?? error)}`,
		);
		return 503;
	}
}

let marketGateWarned = false;

/**
 * 挂上目录接口。
 *
 * @param {object} ctx - Cordis 上下文（已注入 `webServer`）。
 */
export function apply(ctx) {
	ctx.effect(
		() =>
			ctx.webServer.register({
				kind: 'exact',
				path: ROUTE,
				handler: (req, res) => {
					// ⚠️ **门在最前面**，方法判定在门之后：反过来的话，
					// 一个 `POST` 会先拿到 405 —— 而那本身就是一个「这个路由存在」
					// 的存在性信号，还给攻击者省了一次探测。
					const rejected = marketRejection(ctx, req);
					if (rejected !== undefined) {
						ctx.logger?.warn?.(
							`[dsh-android-market] 目录接口拒绝请求: HTTP ${rejected}`,
						);
						res.writeHead(rejected, { 'content-type': 'application/json; charset=utf-8' });
						res.end(JSON.stringify({ error: 'rejected', status: rejected }));
						return;
					}

					if (req.method !== 'GET' && req.method !== 'HEAD') {
						res.writeHead(405, { allow: 'GET, HEAD' });
						res.end();
						return;
					}

					// `?refresh=1` 强制重扫：界面上那个「刷新」按钮走这条。
					const force = /(?:\?|&)refresh=1(?:&|$)/.test(req.url ?? '');

					getCatalog(ctx, force)
						.then((body) => {
							res.writeHead(200, {
								'content-type': 'application/json; charset=utf-8',
								// 目录带时间戳与来源，不给任何一层缓存 ——
								// 缓存由上面那个 `cache` 变量管，HTTP 层再缓存一层会
								// 让「点刷新没反应」变得无从排查。
								'cache-control': 'no-store',
							});
							res.end(req.method === 'HEAD' ? undefined : JSON.stringify(body));
						})
						.catch((error) => {
							ctx.logger?.warn?.(`[dsh-android-market] 目录接口失败: ${error?.message ?? error}`);
							if (res.headersSent) {
								res.destroy();
								return;
							}
							res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
							res.end(JSON.stringify({ error: String(error?.message ?? error) }));
						});
				},
			}),
		'dsh-android-market: catalog route',
	);
}
