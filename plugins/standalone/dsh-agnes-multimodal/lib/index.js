/**
 * dsh-agnes-multimodal — host half.
 *
 * Fusion of three former packages into one:
 *   dsh-agconfig  → the 多模态配置 settings page (account pool persistence API)
 *   dsh-agimage   → the `generate_image` model tool + PNG serving route
 *   dsh-agvideo   → the `generate_video` model tool
 *
 * Everything shares one state file, one trust fence, one account pool, one
 * media resolver, and one HTTP path. The former dsh-agvideo used a PowerShell
 * subprocess to talk to the API; that is gone — both tools now use Node's
 * native fetch, so there is no shell escaping to get wrong and no pwsh
 * dependency.
 *
 * State file: $DSH_HOME/ag-multimodal.json — the SAME file the old dsh-agconfig
 * wrote, so an existing account pool keeps working with no re-entry.
 *
 * Host API (POST JSON to /agnes/api, loopback/trusted-origin only):
 *   { "method": "get" }                                        → { ok:true, value:{...} }
 *   { "method": "set", "patch": { "accounts": [...] } }        → { ok:true }
 *   { "method": "test", "payload": { "endpoint", "key" } }     → { ok:true|false, reason }
 *
 * Image route (GET, same fence): /agnes/img?ref=<urlencoded attachment ref>
 *
 * Model-context rule (inherited from both tools): the tool result render is
 * PLAIN TEXT ONLY — no image block, no URL, no base64. The model never sees the
 * media; the browser fetches it through the routes above.
 */

import { readFileSync, writeFileSync, renameSync, mkdirSync, appendFileSync } from "node:fs";
import { join, dirname, resolve, extname } from "node:path";
import { homedir } from "node:os";

/** Plugin identity for cordis.yml rows. */
export const name = "dsh-agnes-multimodal";

/** Services required before mounting: tool registry, web routes, engine trust fence. */
export const inject = ["tools", "webServer", "connection"];

/** State file name inside the DSH home directory (shared with the old dsh-agconfig). */
const STATE_FILENAME = "ag-multimodal.json";
/** Diagnostic log file name inside the DSH home directory. */
const LOG_FILENAME = "agnes-multimodal.log";
/** Route prefix owned by this plugin (config API). */
const API_PREFIX = "/agnes/api";
/** Max accepted request body (an account pool is tiny; keep a sane cap). */
const MAX_BODY_BYTES = 4 * 1024 * 1024;
/** Default workspace root used when the sandbox-policy service is absent. */
const DEFAULT_CWD = join(homedir(), "Desktop", "dsh");

/** Built-in default endpoint (CN station). */
const DEFAULT_ENDPOINT = "https://api.agnes-ai.cn/v1";
/** Built-in default key, used only when the user has configured no account. */
const DEFAULT_API_KEY = "sk-2BsAqJq1OrbcQBfUPhHLZVclSxZn0SwZtaHkXymAlX6kC4GX";

/** Image model id. */
const IMAGE_MODEL = "agnes-image-2.1-flash";
/** Video model id. */
const VIDEO_MODEL = "agnes-video-2.5-flash";

// ── paths ──────────────────────────────────────────────────────────────────

/** Absolute path of the DSH home directory. */
function dshHome() {
	return process.env.DSH_HOME && process.env.DSH_HOME.length > 0 ? process.env.DSH_HOME : join(homedir(), ".dsh");
}

/** Absolute path of the shared multimodal config state file. */
function statePath() {
	return join(dshHome(), STATE_FILENAME);
}

// ── state file ─────────────────────────────────────────────────────────────

/** Read the state object; `{}` when absent or corrupt. */
function readState() {
	try {
		const parsed = JSON.parse(readFileSync(statePath(), "utf8"));
		return parsed !== null && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
	} catch {
		return {};
	}
}

/** Persist the state object atomically (tmp + rename, direct-write fallback). */
function writeState(state) {
	const file = statePath();
	mkdirSync(dirname(file), { recursive: true });
	const tmp = `${file}.tmp`;
	const body = JSON.stringify(state);
	// The state file holds API keys, so keep it owner-only on POSIX
	// (mode is ignored on Windows).
	writeFileSync(tmp, body, { encoding: "utf8", mode: 0o600 });
	try {
		renameSync(tmp, file);
	} catch {
		writeFileSync(file, body, { encoding: "utf8", mode: 0o600 });
	}
}

/** Append one diagnostic record to the shared log; never throws. */
function appendLog(record) {
	try {
		appendFileSync(join(dshHome(), LOG_FILENAME), JSON.stringify(record) + "\n", "utf8");
	} catch {
		// diagnostics must never break generation
	}
}

/**
 * 内置号：App 端注入的号池条目。
 *
 * ⚠️ **必须与 `EngineManager.KEY_PLACEHOLDER` 逐字一致。**
 *
 * ## 号池已改为服务端网关
 *
 * 以前这里是占位符 `sk-ANON…`，由 `libkeyinject.so` 在 TLS 层（`SSL_write`）
 * 换成真密钥；endpoint 必须用国际站域名，因为要靠等长改写 Host 头做两站路由。
 * **那条链路已整体下线**。
 *
 * 现在：
 * - `endpoint` = 自建网关 `agnes.jiulingssh.xyz`
 * - `key`      = 网关令牌
 *
 * 真密钥、两站路由、429 换号重试全部由服务器负责。令牌随 APK 分发、不是秘密，
 * 网关侧靠限流兜底；真密钥永不落设备。
 */
const BUILTIN_KEY = "sk-agnes-54a388522b26770c2b45aab2f5a22c0700b6be4c0f325a96";
const BUILTIN_ENDPOINT = "https://agnes.jiulingssh.xyz/v1";

/**
 * **旧版**的 TLS 注入占位符，仅用于识别老配置里残留的内置行。
 *
 * ⚠️ 不要把它当有效凭据使用 —— 没有注入库会去替换它，发出去必然 401。
 * `isBuiltinAccount()` 认它，是为了把老行识别成内置行并覆盖成新令牌，
 * 否则用户会留下一条永远失败的内置号。
 */
const LEGACY_BUILTIN_KEY = "sk-ANON00000000000000000000000000000000000000000000";

/** 服务端权威的内置号条目 —— 客户端传来的内置条目一律以它为准。 */
function builtinAccount() {
	return { endpoint: BUILTIN_ENDPOINT, key: BUILTIN_KEY, builtin: true };
}

/**
 * 是否是内置号。
 *
 * 标记优先、占位符兜底：老配置文件里没有 `builtin` 标记（App 早期版本播的种
 * 只有 endpoint/key 两个字段），只认标记会让那一行变成「可删可改的普通账号」，
 * 于是用户能把它删掉、内置号池就丢了。
 *
 * ⚠️ 必须同时认**新旧两种** key：升级到网关方案后，老设备上留的是
 * `LEGACY_BUILTIN_KEY`（`sk-ANON…`），只认新的会把它当成用户自建账号。
 */
function isBuiltinAccount(row) {
	return row !== null && typeof row === "object"
		&& (row.builtin === true || row.key === BUILTIN_KEY || row.key === LEGACY_BUILTIN_KEY);
}

/** 把一行规整成账号条目；不合法返回 undefined。 */
function normalizeRow(row) {
	if (row === null || typeof row !== "object") return undefined;
	if (typeof row.endpoint !== "string" || typeof row.key !== "string") return undefined;
	const key = row.key.trim();
	if (key.length === 0) return undefined;
	return { endpoint: row.endpoint, key };
}

/** 读取账号池的原始数组（含旧的单密钥格式），不做内置号处理。 */
function readRawAccounts() {
	try {
		const parsed = JSON.parse(readFileSync(statePath(), "utf8"));
		if (parsed !== null && typeof parsed === "object") {
			if (Array.isArray(parsed.accounts)) return parsed.accounts;
			// backward compat: legacy single key becomes one CN account
			if (typeof parsed.agnesApiKey === "string" && parsed.agnesApiKey.trim().length > 0) {
				return [{ endpoint: DEFAULT_ENDPOINT, key: parsed.agnesApiKey.trim() }];
			}
		}
	} catch {
		// file absent or corrupt — treat as empty
	}
	return [];
}

/**
 * 把账号池拆成「内置号」与「用户号」两部分。
 *
 * ## ⚠️ 内置号不可删除、不可更改
 *
 * 客户端传来的内置条目**一律丢弃**，用服务端的 [builtinAccount] 重建。所以改它的
 * endpoint、改它的 key、甚至去掉 `builtin` 标记都无效 —— 改完存回来又被换回权威值。
 * 保护放在服务端而不是只靠 UI 禁用：UI 是建议，这里是强制。
 *
 * ## 顺序
 *
 * 内置号**恒在第一位**，用户号跟在后面。所以界面上第一行永远是那条灰色的「内置」，
 * 用户新加的号追加在其后。
 */
function splitAccounts(raw) {
	const list = Array.isArray(raw) ? raw : [];
	const users = [];
	for (const row of list) {
		if (isBuiltinAccount(row)) continue;
		const normalized = normalizeRow(row);
		if (normalized !== undefined) users.push(normalized);
	}
	return { builtin: builtinAccount(), users };
}

/**
 * Account pool used by the tools.
 *
 * ## ⚠️ 用户号优先于内置号
 *
 * 用户号**排在前面先试**，内置号恒在最后兜底 —— 两者共用一个池子。理由：内置号池
 * 是「开箱即用」的免费额度，用户填自己的 key 通常正是为了先用自己那份额度；
 * 让用户号先被轮询到，就保证了「自己的号优先消耗、用完/不够时才落到内置」。
 * 内置号始终留在池尾，所以两个工具在任何情况下都可用。
 *
 * 返回的数组里内置号**恒在最后一位**，且带 `builtin: true` 标记。
 */
function readAccounts() {
	const { builtin, users } = splitAccounts(readRawAccounts());
	return [...users, builtin];
}

/** Root of an endpoint (strip a trailing /v1). */
function endpointRoot(endpoint) {
	return endpoint.replace(/\/v1\/?$/, "");
}

// ── trust fence (loopback / configured authority + same-origin markers) ────

/** Normalized URL of a Host-header authority, or undefined when unparsable. */
function parseAuthority(authority) {
	try {
		return new URL(`http://${authority}`);
	} catch {
		return undefined;
	}
}

/** Whether a normalized URL hostname names the local loopback authority. */
function isLoopbackHostname(hostname) {
	if (hostname === "localhost" || hostname === "[::1]") return true;
	const parts = hostname.split(".");
	return parts.length === 4
		&& parts[0] === "127"
		&& parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255);
}

/** Canonical authority form: hostname, or hostname:port when a port was written. */
function canonicalAuthority(entry, entryUrl) {
	const port = entryUrl.port !== "" ? entryUrl.port : new URL(`https://${entry}`).port;
	return port === "" ? entryUrl.hostname : `${entryUrl.hostname}:${port}`;
}

/** Assert one configured `trustedHosts` entry is a bare `host[:port]` authority. */
function assertTrustedAuthority(entry) {
	const entryUrl = parseAuthority(entry);
	if (entryUrl !== undefined && canonicalAuthority(entry, entryUrl) === entry.toLowerCase()) return;
	throw new Error(`dsh-agnes-multimodal: trustedHosts entry ${JSON.stringify(entry)} is not a bare host[:port] authority`);
}

/** Whether the request authority matches a trustedHosts entry (exact or port-less). */
function isTrustedAuthority(hostUrl, trustedHosts) {
	return trustedHosts.some((entry) => {
		assertTrustedAuthority(entry);
		const entryUrl = parseAuthority(entry);
		if (entryUrl === undefined) return false;
		return canonicalAuthority(entry, entryUrl) === entryUrl.hostname
			? entryUrl.hostname === hostUrl.hostname
			: entryUrl.host === hostUrl.host;
	});
}

/**
 * Decide whether one request may reach the plugin routes.
 *
 * DSH 0.2.1 删掉了 `webRuntime` 服务，信任围栏统一收进 `connection`：
 * `connection.requestRejection(req)` 返回 HTTP 状态码 = 拒绝，`undefined` = 放行。
 * 它同时查 Host 是 loopback + 会话 cookie，比原来那套只查 Host/Origin 的判据更严
 * （原来那套是 DNS-rebinding 防御，不是鉴权）。
 *
 * ⚠️ 取不到 `connection` 一律 **503（fail-closed）**，绝不降级：
 * cordis 的 `ctx.get(name)` 对尚未 ACTIVE 的提供者返回 undefined，而引擎启动期间
 * 本路由已经在收请求 —— 「服务缺失」是暂时不可用（刷新一下就好），
 * 「降级到只查 Host」是一直不安全。与 `dsh-android-market` 同款取舍。
 *
 * @param ctx - Cordis 上下文。
 * @param req - 请求。
 * @returns 拒绝时的 HTTP 状态码，放行时 `undefined`。
 */
function agnesRejection(ctx, req) {
	const connection = ctx.get?.("connection");
	if (connection === undefined || typeof connection.requestRejection !== "function") {
		if (!agnesGateWarned) {
			agnesGateWarned = true;
			ctx.logger?.warn?.(
				"[dsh-agnes-multimodal] connection 服务尚未就绪，接口一律 503（fail-closed，不降级到只判 Host）",
			);
		}
		return 503;
	}
	try {
		return connection.requestRejection(req);
	} catch (error) {
		ctx.logger?.warn?.(
			`[dsh-agnes-multimodal] connection.requestRejection 抛错，接口一律 503（fail-closed）: ${String(error?.message ?? error)}`,
		);
		return 503;
	}
}

let agnesGateWarned = false;


// ── JSON body / response helpers ───────────────────────────────────────────

/** Sentinel: the request body exceeded MAX_BODY_BYTES (respond 413, not 400). */
const PAYLOAD_TOO_LARGE = Symbol("payload-too-large");

/** Read a JSON request body, capped at MAX_BODY_BYTES. */
function readJsonBody(req) {
	return new Promise((resolve) => {
		const chunks = [];
		let size = 0;
		let aborted = false;
		req.on("data", (chunk) => {
			size += chunk.length;
			if (size > MAX_BODY_BYTES && !aborted) {
				aborted = true;
				req.destroy();
				resolve(PAYLOAD_TOO_LARGE);
				return;
			}
			if (!aborted) chunks.push(chunk);
		});
		req.on("end", () => {
			if (aborted) return;
			try {
				resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
			} catch {
				resolve(null);
			}
		});
		req.on("error", () => {
			if (!aborted) resolve(null);
		});
	});
}

/** Write a JSON response with the given status code. */
function writeJson(res, status, value) {
	res.writeHead(status, {
		"content-type": "application/json",
		"cache-control": "no-store"
	});
	res.end(JSON.stringify(value));
}

// ── shared HTTP + media helpers ────────────────────────────────────────────

/** fetch + text + best-effort JSON parse, with an abort-based timeout. */
async function fetchJson(url, options, timeoutMs) {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), timeoutMs);
	try {
		const response = await fetch(url, { ...options, signal: controller.signal });
		const text = await response.text();
		let body;
		try { body = JSON.parse(text); } catch { body = undefined; }
		return { response, body, text };
	} finally {
		clearTimeout(timer);
	}
}

/** MIME type for a local media file, by extension. */
function mimeFor(file) {
	const ext = extname(file).toLowerCase();
	return ({
		".jpg": "image/jpeg",
		".jpeg": "image/jpeg",
		".png": "image/png",
		".webp": "image/webp",
		".gif": "image/gif",
		".mp3": "audio/mpeg",
		".wav": "audio/wav",
		".m4a": "audio/mp4"
	})[ext] || "image/png";
}

/**
 * Resolve one media entry to something the API accepts.
 * URL / data URI pass through; a 64-hex sha256 resolves against the durable
 * attachment store; anything else is tried as a local path. Unresolved values
 * are returned unchanged so the API can report the real error.
 */
function resolveMedia(value, cwd) {
	if (typeof value !== "string" || value.length === 0) return value;
	if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) return value;
	const candidates = [];
	const match = value.match(/([0-9a-fA-F]{64})/);
	if (match !== null) {
		candidates.push(join(dshHome(), "attachments", "v1", "objects", match[1].slice(0, 2), match[1]));
	}
	candidates.push(resolve(cwd, value));
	for (const file of candidates) {
		try {
			const bytes = readFileSync(file);
			return `data:${mimeFor(file)};base64,${bytes.toString("base64")}`;
		} catch {
			// Try the next candidate; unresolved values remain unchanged.
		}
	}
	return value;
}

/** Round-robin picker over a pool (one account per call, wrapping around). */
function roundRobin(pool) {
	let i = 0;
	return () => {
		const account = pool[i % pool.length];
		i += 1;
		return account;
	};
}

// ── config API ─────────────────────────────────────────────────────────────

/** Probe one API key with GET {endpoint}/models — zero cost, no side effects. */
async function probeAccount(endpoint, key) {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), 20000);
	try {
		const response = await fetch(endpoint.replace(/\/+$/, "") + "/models", {
			headers: { Authorization: `Bearer ${key}` },
			signal: controller.signal
		});
		if (response.status === 200) return { ok: true, httpStatus: 200, reason: "ok" };
		if (response.status === 401 || response.status === 403) return { ok: false, httpStatus: response.status, reason: "invalid" };
		return { ok: false, httpStatus: response.status, reason: "other" };
	} catch {
		return { ok: false, httpStatus: -1, reason: "network" };
	} finally {
		clearTimeout(timer);
	}
}

/**
 * 读出给客户端的状态：账号池一律规范化成「用户号在前、内置号恒在最后」。
 *
 * 客户端拿到的就是它要渲染的，所以顺序在这里定死一次，UI 不必再排。这样也保证了
 * 老配置文件（内置号排在别处、或压根没有内置号）在界面上第一眼就是对的。
 */
function stateForClient() {
	const state = readState();
	const { builtin, users } = splitAccounts(state.accounts);
	// 保留 `accounts` 之外的其它键（未来新增的配置项不该被这里吞掉）。
	return { ...state, accounts: [...users, builtin] };
}

/** Handle one fenced config API request. */
async function handleApi(req, res) {
	if (req.method !== "POST") {
		writeJson(res, 405, { ok: false, error: { code: "method-error", message: "method not allowed" } });
		return;
	}
	const contentType = typeof req.headers["content-type"] === "string" ? req.headers["content-type"].toLowerCase() : "";
	if (!contentType.startsWith("application/json")) {
		writeJson(res, 415, { ok: false, error: { code: "unsupported-media-type", message: "content-type must be application/json" } });
		return;
	}
	const payload = await readJsonBody(req);
	if (payload === PAYLOAD_TOO_LARGE) {
		writeJson(res, 413, { ok: false, error: { code: "payload-too-large", message: "request body too large" } });
		return;
	}
	if (payload === null || typeof payload !== "object" || typeof payload.method !== "string") {
		writeJson(res, 400, { ok: false, error: { code: "bad-request", message: "bad request" } });
		return;
	}
	if (payload.method === "get") {
		writeJson(res, 200, { ok: true, value: stateForClient() });
		return;
	}
	if (payload.method === "set") {
		const patch = payload.patch;
		if (patch === null || typeof patch !== "object" || Array.isArray(patch)) {
			writeJson(res, 400, { ok: false, error: { code: "bad-request", message: "patch must be a plain object" } });
			return;
		}
		// Merge into the current state. `accounts` is the account pool: an array
		// of { endpoint, key } rows (empty-key rows are dropped). Other keys:
		// string sets, null removes, absent keys untouched, so concurrent
		// writers are safe.
		const next = readState();
		for (const [key, value] of Object.entries(patch)) {
			if (key === "accounts") {
				if (value === null) {
					// ⚠️ 删掉整个账号池也不行：内置号必须还在。
					next.accounts = [builtinAccount()];
				} else if (Array.isArray(value)) {
					// ⚠️ 内置号在这里被**强制重建**：客户端传来的内置条目一律丢弃，
					// 用服务端的权威值替代。所以改它的 key、改它的 endpoint、
					// 去掉 `builtin` 标记都无效 —— 存回来又被换回去。
					// 保护必须在服务端，UI 禁用只是第一道。
					const { users } = splitAccounts(value);
					next.accounts = [...users, builtinAccount()];
				}
				continue;
			}
			if (value === null) delete next[key];
			else if (typeof value === "string") next[key] = value;
		}
		writeState(next);
		writeJson(res, 200, { ok: true });
		return;
	}
	if (payload.method === "test") {
		const target = payload.payload;
		if (target === null || typeof target !== "object" || typeof target.endpoint !== "string" || typeof target.key !== "string") {
			writeJson(res, 400, { ok: false, error: { code: "bad-request", message: "payload must be { endpoint, key }" } });
			return;
		}
		writeJson(res, 200, await probeAccount(target.endpoint, target.key));
		return;
	}
	writeJson(res, 404, { ok: false, error: { code: "not-found", message: `unknown method "${payload.method}"` } });
}

// ── image tool ─────────────────────────────────────────────────────────────

/** Per-key request window used by the image scheduler. */
const RPM_WINDOW_MS = 60000;
/** Requests allowed per key per window. */
const RPM_LIMIT = 20;
/** Consecutive throttle hits before the global breaker cools down. */
const THROTTLE_TRIGGER = 12;
/** Global breaker cooldown. */
const THROTTLE_COOLDOWN_MS = 30000;

/** Register the `generate_image` model tool. */
function registerImageTool(ctx) {
	const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

	/** One generation attempt against one account. */
	const attempt = async (prompt, idx, account, args, cwd, size, images) => {
		const url = account.endpoint.replace(/\/+$/, "") + "/images/generations";
		const bodyObj = {
			model: IMAGE_MODEL,
			prompt,
			size,
			extra_body: { response_format: "url" }
		};
		// `ratio` only applies to the tier sizes.
		if (args.ratio !== undefined && ["1K", "2K", "3K", "4K"].includes(size)) bodyObj.ratio = String(args.ratio);
		if (images.length > 0) bodyObj.extra_body.image = images.map((value) => resolveMedia(value, cwd));
		const { response, body, text } = await fetchJson(url, {
			method: "POST",
			headers: { Authorization: `Bearer ${account.key}`, "Content-Type": "application/json" },
			body: JSON.stringify(bodyObj)
		}, 360000);
		if (!response.ok) return { ok: false, status: response.status, message: body?.error?.message || text.slice(0, 500) };
		const item = Array.isArray(body?.data) ? body.data[0] : undefined;
		if (item === undefined) return { ok: false, status: 0, message: "Unexpected API response" };
		const task = body.task_id || `image-${Date.now()}-${idx}`;
		const file = join(cwd, `${task}.png`);
		if (item.url) {
			const download = await fetch(item.url);
			if (!download.ok) return { ok: false, status: download.status, message: "download failed" };
			writeFileSync(file, Buffer.from(await download.arrayBuffer()));
		} else if (item.b64_json) {
			writeFileSync(file, Buffer.from(item.b64_json, "base64"));
		} else {
			return { ok: false, status: 0, message: "no url or b64 in response" };
		}
		return { ok: true, status: 0, url: item.url || "", task_id: task, file };
	};

	ctx.tools.register({
		name: "generate_image",
		description:
			"Generate or edit images with the Agnes AI image API (agnes-image-2.1-flash). "
			+ "prompt is an array of instructions — each generates one image. "
			+ "WHEN THE USER ATTACHES/SENDS AN IMAGE AND ASKS TO MODIFY, TRANSFORM, STYLE-TRANSFER, OR COMBINE IT: "
			+ "this tool DOES support image editing — pass the user's image in the image parameter. "
			+ "The user's attached image appears in your context as 'Image sha256:<64-hex>'; "
			+ "put that 64-character hex value into the image parameter (the plugin resolves it automatically). "
			+ "1 image = img2img edit; 2+ images = multi-image composition. "
			+ "You may also pass a local file path, a public HTTPS URL, or a data-URI base64 as an image entry. "
			+ "size accepts tiers 1K/2K/3K/4K (optionally combined with ratio, e.g. size=2K ratio=16:9) or legacy exact sizes. "
			+ "Images are displayed in the conversation UI by default (cached locally); pass show=false only for very large batches to skip display. "
			+ "The generated images are shown to the USER in the UI, never to you: "
			+ "the tool result returns only plain text — a success note plus each requested prompt. "
			+ "Do not echo image URLs, base64, or binary data into the conversation; "
			+ "when you summarize the result, describe the images according to the requested prompts.",
		parameters: {
			type: "object",
			additionalProperties: false,
			properties: {
				prompt: {
					type: "array",
					items: { type: "string" },
					description: "Array of image description prompts. Each prompt generates one image. English recommended for best results. For img2img or multi-image composition, describe the desired transformation or combination."
				},
				size: {
					type: "string",
					enum: ["1K", "2K", "3K", "4K", "1024x768", "768x1024", "1024x1024"],
					description: "Output size. Use tier 1K/2K/3K/4K with ratio for best results. 1K=fast, 2K=high quality, 3K/4K=very high quality but slow. Legacy exact sizes also work. Default: 1K"
				},
				ratio: {
					type: "string",
					enum: ["1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9"],
					description: "Aspect ratio (only used with tier size 1K/2K/3K/4K). Default: 1:1. 16:9 for widescreen, 9:16 for portrait mobile, 21:9 for ultra-wide."
				},
				image: {
					type: "array",
					items: { type: "string" },
					description: "Reference images for image editing (1 image: transform/style-transfer) or multi-image composition (2+ images: combine). Each entry: the 64-char sha256 hex of a user-attached image (from 'Image sha256:...' in the user message), a local file path, a public HTTPS URL, or a data-URI base64 — sha256 and local paths are converted to data URIs automatically. Optional."
				},
				show: {
					type: "boolean",
					description: "When false, skip caching/displaying the images (default: true — images are shown in the conversation UI)."
				}
			},
			required: ["prompt"]
		},
		output: {
			schema: {
				type: "object",
				additionalProperties: true,
				properties: {
					results: {
						type: "array",
						description: "Array of generated image results.",
						items: {
							type: "object",
							additionalProperties: true,
							properties: {
								url: { type: "string", description: "Remote URL of the generated image." },
								task_id: { type: "string", description: "Agnes API task id." },
								localPath: { type: "string", description: "Workspace path of the downloaded PNG (only when show=true)." },
								attachmentRef: { type: "object", description: "Attachment ref for conversation display (only when show=true)." }
							}
						}
					}
				}
			},
			// The generated PNG rides along as a real `image` content block, which
			// is the only thing the Web Client renders: its `imageReferences()`
			// walks the result content and the Chat shell turns each ref into a
			// session-authorized URL through the `loadImage` prop.
			//
			// `offloaded: true` keeps the model context text-only, exactly as the
			// former text-only render did: the request projection replaces the
			// block with placeholder text naming the attachment, while the Web
			// Client ignores the flag and still displays the image. That is the
			// shipped mechanism (see @deepseek-ai/dsh-compaction-image-offload),
			// so no hand-written PNG route is needed any more.
			render: (args, value) => {
				const prompts = Array.isArray(args.prompt) ? args.prompt : [args.prompt];
				const results = Array.isArray(value.results) ? value.results : [value];
				const count = results.length;
				const blocks = [];
				results.forEach((r, i) => {
					const line = "[agnes-plugin] Image " + (i + 1) + "/" + count + " generated successfully.\nRequested prompt: " + prompts[i];
					blocks.push({ type: "text", text: r && r.localPath ? line + "\nlocalPath: " + r.localPath : line });
					if (r && r.attachmentRef) blocks.push({ type: "image", attachment: r.attachmentRef, offloaded: true });
				});
				return blocks;
			}
		},
		async execute(args, exec) {
			const prompts = Array.isArray(args.prompt) ? args.prompt : [args.prompt];
			const size = args.size || "1K";
			const show = args.show !== false;
			const images = Array.isArray(args.image)
				? args.image.filter((x) => typeof x === "string" && x.length > 0)
				: [];
			const accounts = readAccounts();
			const policy = ctx.get("sandboxPolicy");
			const cwd = policy !== undefined ? policy.workspaceRoot : DEFAULT_CWD;
			const fs = ctx.get("fs");
			const attachments = ctx.get("attachments");
			const aborted = () => exec.signal !== undefined && exec.signal.aborted;

			// Scheduler: the international station is the fast lane. Every INTL
			// key is an independent queue slot, so batch capacity is
			// intlKeys x 10; keys are round-robin'd so load spreads evenly.
			// Images go out in batches of `capacity` with 5s gaps. If no INTL
			// key is configured the whole pool is used as-is.
			const CN_MARKER = "api.agnes-ai.cn";
			const intlPool = accounts.filter((a) => !a.endpoint.includes(CN_MARKER));
			const activePool = intlPool.length > 0 ? intlPool : accounts;
			const capacity = activePool.length * 10;
			const jobs = prompts.map((prompt, idx) => ({ prompt, idx, state: "pending", result: null }));
			const nextAcc = roundRobin(activePool);

			const finishJob = async (job, result) => {
				const response = { task_id: result.task_id };
				if (result.url) response.url = result.url;
				if (show && result.file) {
					if (fs === undefined || attachments === undefined) throw new Error("fs/attachments service not available");
					const target = await fs.resolve(result.file);
					const bytes = await fs.readBytes(target, exec.signal, 20971520);
					const ref = await attachments.saveImage({ data: bytes, mediaType: "image/png", name: "generated_image_" + job.idx + ".png" });
					response.attachmentRef = ref;
					response.localPath = result.file;
				}
				job.result = response;
				job.state = "done";
			};

			// 429/401 both mean "this account is rate-limited", so a global
			// breaker cools down after repeated hits. Per-key RPM: each account
			// allows ~20 requests per 60s window; acquireKey waits for the
			// oldest request to slide out before firing the next batch.
			let throttleHits = 0;
			let lastCooldown = 0;
			const keyWindows = new Map();
			const acquireKey = async (key) => {
				let arr = keyWindows.get(key);
				if (arr === undefined) {
					arr = [];
					keyWindows.set(key, arr);
				}
				for (;;) {
					if (aborted()) throw new Error("Image generation cancelled while rate-limiting");
					const now = Date.now();
					while (arr.length > 0 && arr[0] <= now - RPM_WINDOW_MS) arr.shift();
					if (arr.length < RPM_LIMIT) {
						arr.push(now);
						return;
					}
					await sleep(arr[0] - (now - RPM_WINDOW_MS) + 100);
				}
			};

			const runJob = async (job) => {
				let backoff = 2000;
				for (let a = 1; a <= 8; a++) {
					if (aborted()) throw new Error("Image " + (job.idx + 1) + " cancelled");
					if (job.state === "done") return;
					const acc = nextAcc();
					await acquireKey(acc.key);
					let result;
					try {
						result = await attempt(job.prompt, job.idx, acc, args, cwd, size, images);
					} catch (error) {
						result = { ok: false, status: -1, message: "throw: " + (error && error.message ? error.message : String(error)) };
					}
					appendLog({ t: new Date().toISOString(), phase: "image", idx: job.idx, key: acc.key.slice(0, 10), endpoint: acc.endpoint, status: result.status, ok: result.ok === true, msg: result.message || "", attempt: a });
					if (result.ok === true) {
						await finishJob(job, result);
						return;
					}
					if (result.status === 429 || result.status === 401) {
						throttleHits += 1;
						if (throttleHits >= THROTTLE_TRIGGER && Date.now() - lastCooldown > THROTTLE_COOLDOWN_MS * 2) {
							lastCooldown = Date.now();
							throttleHits = 0;
							appendLog({ t: new Date().toISOString(), phase: "breaker", idx: job.idx, msg: "throttle cooldown " + THROTTLE_COOLDOWN_MS + "ms" });
							await sleep(THROTTLE_COOLDOWN_MS);
							continue;
						}
					}
					await sleep(backoff);
					backoff = Math.min(16000, backoff * 2);
				}
				job.state = "failed";
			};

			for (let i = 0; i < jobs.length; i += capacity) {
				if (i > 0) await sleep(5000);
				await Promise.all(jobs.slice(i, i + capacity).map((job) => runJob(job)));
			}

			const failed = jobs.filter((job) => job.state !== "done");
			if (failed.length > 0) {
				throw new Error(failed.length + " image(s) failed to generate: " + failed.map((job) => job.idx + 1).join(", "));
			}
			return { results: jobs.map((job) => job.result) };
		}
	});
}

// ── video tool ─────────────────────────────────────────────────────────────

/** Per-account hard rate limit: each account accepts 1 task per minute. */
const VIDEO_RPM_MS = 60000;
/** Poll cadence while waiting for a task to finish. */
const VIDEO_POLL_MS = 3000;
/** Backoff after a 429 while polling. */
const VIDEO_POLL_429_MS = 6000;
/** Give up on one task after this long. */
const VIDEO_DEADLINE_MS = 10 * 60 * 1000;
/** account index -> last task start (module-level: the limit spans calls). */
const videoAccountBusy = new Map();
/** Round-robin cursor for the video account pool. */
let videoRrIndex = 0;

/** Pick the next account with a free slot; when all are busy report the wait. */
function pickVideoAccount(accounts) {
	const n = accounts.length;
	for (let i = 0; i < n; i++) {
		const idx = (videoRrIndex + i) % n;
		const elapsed = Date.now() - (videoAccountBusy.get(idx) || 0);
		if (elapsed >= VIDEO_RPM_MS) {
			videoRrIndex = (idx + 1) % n;
			videoAccountBusy.set(idx, Date.now());
			return { account: accounts[idx], waitSec: 0 };
		}
	}
	let minRemain = Infinity;
	for (let i = 0; i < n; i++) {
		const remain = VIDEO_RPM_MS - (Date.now() - (videoAccountBusy.get(i) || 0));
		if (remain < minRemain) minRemain = remain;
	}
	return { account: null, waitSec: Math.max(1, Math.ceil(minRemain / 1000)) };
}

/** Register the `generate_video` model tool. */
function registerVideoTool(ctx) {
	const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

	/**
	 * Create one async video task and poll it to completion.
	 * Returns { ok:true, video_id, url, status, localPath } or a failure record.
	 */
	const runOne = async (prompt, args, account, cwd, aborted) => {
		const createBase = account.endpoint.replace(/\/+$/, "") + "/videos";
		const queryBase = endpointRoot(account.endpoint) + "/agnesapi";
		const mode = args.mode || "text";
		const body = {
			model: VIDEO_MODEL,
			prompt,
			mode,
			seconds: String(args.seconds || "5"),
			size: "720P",
			aspect_ratio: args.aspect_ratio || "16:9"
		};
		if (typeof args.seed === "number") body.seed = args.seed;
		if (mode === "keyframe") {
			if (args.first_frame) body.first_frame = resolveMedia(String(args.first_frame), cwd);
			if (args.last_frame) body.last_frame = resolveMedia(String(args.last_frame), cwd);
			if (body.first_frame === undefined && body.last_frame === undefined) {
				throw new Error("keyframe mode needs first_frame and/or last_frame");
			}
		}
		if (mode === "reference") {
			const images = Array.isArray(args.images) ? args.images.map(String).filter((x) => x.length > 0) : [];
			const audios = Array.isArray(args.audios) ? args.audios.map(String).filter((x) => x.length > 0) : [];
			if (images.length > 0) body.images = images.map((value) => resolveMedia(value, cwd));
			if (audios.length > 0) body.audios = audios;
			if (body.images === undefined && body.audios === undefined) {
				throw new Error("reference mode needs images and/or audios");
			}
		}

		const created = await fetchJson(createBase, {
			method: "POST",
			headers: { Authorization: `Bearer ${account.key}`, "Content-Type": "application/json" },
			body: JSON.stringify(body)
		}, 60000);
		if (!created.response.ok) {
			return { ok: false, status: created.response.status, message: created.body?.error?.message || created.text.slice(0, 500) };
		}
		const videoId = created.body?.video_id;
		if (!videoId) return { ok: false, status: 0, message: "no video_id in response" };

		const deadline = Date.now() + VIDEO_DEADLINE_MS;
		while (Date.now() < deadline) {
			if (aborted()) throw new Error("Video generation cancelled");
			await sleep(VIDEO_POLL_MS);
			let poll;
			try {
				poll = await fetchJson(queryBase + "?video_id=" + encodeURIComponent(videoId) + "&model_name=" + VIDEO_MODEL, {
					headers: { Authorization: `Bearer ${account.key}` }
				}, 30000);
			} catch {
				continue;
			}
			if (poll.response.status === 429) {
				await sleep(VIDEO_POLL_429_MS);
				continue;
			}
			const status = poll.body?.status;
			if (status === "completed") {
				const url = poll.body?.url || "";
				let localPath = "";
				try {
					const download = await fetch(url);
					if (download.ok) {
						localPath = join(cwd, videoId + ".mp4");
						writeFileSync(localPath, Buffer.from(await download.arrayBuffer()));
					}
				} catch {
					// A failed download still leaves a playable remote URL.
					localPath = "";
				}
				return { ok: true, status: "completed", video_id: videoId, url, localPath };
			}
			if (status === "failed") {
				return { ok: false, status: "failed", video_id: videoId, message: JSON.stringify(poll.body?.error || {}) };
			}
		}
		return { ok: false, status: "timeout", video_id: videoId };
	};

	ctx.tools.register({
		name: "generate_video",
		description:
			"Generate a short AI video with the Agnes AI video API (agnes-video-2.5-flash, currently free, 720P only). "
			+ "When the user supplies images, CHOOSE THE MODE BY INTENT:\n"
			+ "- reference: the user wants the character/subject IN the image to PERFORM A NEW ACTION (dance, run, walk, turn, act) while keeping its appearance/style — pass the image(s) in images and refer to them as <Picture N> in the prompt. Best when the user describes an action or motion for the subject.\n"
			+ "- keyframe: the user wants THE IMAGE ITSELF to come alive as the video first frame — same exact composition as the image, then camera movement or subtle motion (push-in, pan, wind, lighting). Pass the image in first_frame (optionally last_frame). Best when the user says things like \"make this image move\", \"camera push in on this\", or only describes camera/animation on the still scene.\n"
			+ "- text: no image provided, prompt-only.\n"
			+ "A single uploaded portrait + 'make this character dance' → reference. A single image + 'animate this scene, slow zoom' → keyframe. If in doubt with one still image and an action verb, prefer reference.\n"
			+ "seconds is a string 4-12 (default 5); aspect_ratio: 16:9, 9:16, 1:1, 4:3, 3:4, 21:9 (default 16:9). Media (first_frame/last_frame/images) accept public HTTPS URLs, local file paths, or the 64-char sha256 hex of a user-attached image (from 'Image sha256:...' in the user message) — the plugin resolves them. "
			+ "Generation is asynchronous and takes roughly 30s-2min; the plugin polls until done and the videos play in the conversation UI. "
			+ "prompt accepts a single string OR an array of strings: an array generates multiple videos in ONE call, "
			+ "each taking one account slot and running concurrently (N configured accounts = N concurrent videos; "
			+ "extra prompts queue until a slot frees on the 1 video/min per-account limit). "
			+ "The videos are shown to the USER in the UI, never to you: the tool result returns only plain text. Do not echo video URLs or binary data.",
		parameters: {
			type: "object",
			additionalProperties: false,
			properties: {
				prompt: {
					type: ["string", "array"],
					description: "Video content description, or an array of descriptions to generate multiple videos in one call. Reference mode can use <Picture N> and <Audio N> to refer to provided media."
				},
				mode: {
					type: "string",
					enum: ["text", "keyframe", "reference"],
					description: "Workflow: text=prompt only; keyframe=the given image becomes the exact first frame and animates from that composition; reference=the subject/style in the image performs a new action while keeping appearance. See description for intent-based selection."
				},
				seconds: {
					type: "string",
					enum: ["4", "5", "6", "7", "8", "9", "10", "11", "12"],
					description: "Video duration in seconds (string '4'-'12'). Default: '5'"
				},
				aspect_ratio: {
					type: "string",
					enum: ["21:9", "16:9", "4:3", "1:1", "3:4", "9:16"],
					description: "Aspect ratio. Default: 16:9. Use 9:16 for portrait images."
				},
				first_frame: {
					type: "string",
					description: "The image becomes the video EXACT first frame (same composition, then animate). URL, local path, or sha256 of attached image. keyframe mode."
				},
				last_frame: {
					type: "string",
					description: "The image becomes the video END frame. URL, local path, or sha256. keyframe mode. At least one of first/last required."
				},
				images: {
					type: "array",
					items: { type: "string" },
					description: "Subject/style reference images (URL, local path, or sha256; max 5). reference mode: the subject keeps its appearance while performing the action described in the prompt."
				},
				audios: {
					type: "array",
					items: { type: "string" },
					description: "Reference audio URLs. reference mode."
				},
				seed: {
					type: "integer",
					description: "Random seed for reproducible results."
				},
				show: {
					type: "boolean",
					description: "When false, skip showing the video card (default: true — video is shown in the conversation UI)."
				}
			},
			required: ["prompt"]
		},
		output: {
			schema: {
				type: "object",
				additionalProperties: true,
				properties: {
					video_id: { type: "string", description: "Agnes video task id." },
					url: { type: "string", description: "MP4 URL of the generated video." },
					status: { type: "string", description: "Final status (completed)." },
					videos: {
						type: "array",
						description: "All generated videos when prompt was an array.",
						items: {
							type: "object",
							additionalProperties: true,
							properties: {
								video_id: { type: "string" },
								url: { type: "string" },
								status: { type: "string" },
								prompt: { type: "string" },
								localPath: { type: "string", description: "Workspace path of the downloaded MP4." }
							}
						}
					}
				}
			},
			render: (args, value) => {
				const list = Array.isArray(value.videos) && value.videos.length > 0 ? value.videos : [value];
				const lines = list.map((v, i) => {
					const base = "[agnes-plugin] Video " + (i + 1) + "/" + list.length + " generated successfully.\nvideo_id: " + (v.video_id || "") + "\nRequested prompt: " + String(v.prompt !== undefined ? v.prompt : (Array.isArray(args.prompt) ? args.prompt[i] : args.prompt || ""));
					return v.localPath ? base + "\nlocalPath: " + v.localPath : base;
				});
				return [{ type: "text", text: lines.join("\n\n") }];
			},
			presentationMeta: (args, value) => {
				const list = Array.isArray(value.videos) && value.videos.length > 0 ? value.videos : [value];
				return {
					shown: args.show !== false,
					videos: list.map((v) => ({
						url: v.url || "",
						video_id: v.video_id || "",
						prompt: String(v.prompt !== undefined ? v.prompt : (Array.isArray(args.prompt) ? args.prompt : args.prompt || ""))
					}))
				};
			}
		},
		async execute(args, exec) {
			const accounts = readAccounts();
			const rawPrompts = Array.isArray(args.prompt)
				? args.prompt.map((p) => (p === null || p === undefined ? "" : String(p)))
				: [args.prompt === null || args.prompt === undefined ? "" : String(args.prompt)];
			const prompts = rawPrompts.filter((p) => p.trim().length > 0);
			if (prompts.length === 0) throw new Error("prompt required");
			const policy = ctx.get("sandboxPolicy");
			const cwd = policy !== undefined ? policy.workspaceRoot : DEFAULT_CWD;
			const aborted = () => exec.signal !== undefined && exec.signal.aborted;

			// Each job claims one account slot (1 video/min per account); when
			// every slot is busy the job waits for the earliest to free, so a
			// batch with more prompts than accounts queues at N videos/min.
			const runJob = async (prompt) => {
				for (;;) {
					if (aborted()) throw new Error("Video generation cancelled");
					const pick = pickVideoAccount(accounts);
					if (pick.account === null) {
						await sleep(Math.max(1000, pick.waitSec * 1000));
						continue;
					}
					const account = pick.account;
					const MAX_ATTEMPTS = 3;
					for (let attemptNo = 1; attemptNo <= MAX_ATTEMPTS; attemptNo++) {
						const result = await runOne(prompt, args, account, cwd, aborted);
						appendLog({ t: new Date().toISOString(), phase: "video", key: account.key.slice(0, 10), endpoint: account.endpoint, status: result.status, ok: result.ok === true, msg: result.message || "", attempt: attemptNo });
						if (result.ok === true) {
							return { video_id: result.video_id, url: result.url, status: result.status, prompt, localPath: result.localPath || "" };
						}
						if (result.status === "failed" || result.status === "timeout") {
							throw new Error(result.status === "failed" ? "Video failed: " + (result.message || "") : "Video timed out after 10 minutes");
						}
						if (attemptNo >= MAX_ATTEMPTS) {
							throw new Error("Video create failed after " + attemptNo + " attempts: " + (result.message || "unknown error"));
						}
						await sleep(3000);
					}
				}
			};

			const videos = await Promise.all(prompts.map((p) => runJob(p)));
			if (prompts.length === 1) {
				const v = videos[0];
				return { video_id: v.video_id, url: v.url, status: v.status, localPath: v.localPath || "", videos };
			}
			return { videos, status: "completed" };
		}
	});
}

// ── entry ──────────────────────────────────────────────────────────────────

/**
 * Host loader entry: mount the config API, the PNG route, and both tools.
 * @param ctx - host cordis context (tools, webServer, connection).
 */
export function apply(ctx) {
	// Config API: account pool read/write + key probe.
	ctx.effect(() => ctx.webServer.register({
		kind: "prefix",
		path: API_PREFIX,
		handler: async (req, res) => {
			const rejected = agnesRejection(ctx, req);
			if (rejected !== undefined) {
				writeJson(res, rejected, { ok: false, error: { code: "forbidden", message: "forbidden" } });
				return;
			}
			try {
				await handleApi(req, res);
			} catch (error) {
				console.error("[dsh-agnes-multimodal] config API error:", error);
				writeJson(res, 500, { ok: false, error: { code: "internal", message: "internal error" } });
			}
		}
	}), "dsh-agnes-multimodal: config API routes");

	registerImageTool(ctx);
	registerVideoTool(ctx);
}
