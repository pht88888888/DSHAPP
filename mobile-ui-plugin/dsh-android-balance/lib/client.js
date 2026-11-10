window.__ModuleLoader__.load({
	id: "dsh-android-balance",
	factory: (require) => {
		"use strict";
		function apply(ctx) {
			/** 页面侧余额状态（模板渲染由原生做，这里只负责取数与算钱）。 */
			const mpBalance = {
				/** 充值钱包 "[{currency,balance}]"。 */
				wallets: [],
				/** 赠送钱包。 */
				bonusWallets: [],
				/** 上次单轮花费（USD）。 */
				costLast: 0,
				/** 本会话累计（USD）。 */
				costSession: 0,
				/** 上次错误。 */
				lastError: '',
				/** 是否正在拉取（防重入）。 */
				fetching: false,
			};
	
			/**
			 * DeepSeek 单价（USD / 1M tokens）。**估算值，不是账单**。
			 *
			 * 官方随时可能调价，这里写死必然过期；而且真实扣费只有官方后台知道。
			 * 界面上应理解为"大约花了多少"。
			 *
			 * ⚠️ 缓存命中单独一档：不区分的话会把便宜十倍的缓存读按原价算，虚高得离谱。
			 * ⚠️ **不算 cacheWriteTokens**：DeepSeek 不单独收缓存写费（与 OpenAI 不同，
			 *   别照抄那边的计价模型，会凭空多出一笔）。
			 */
			const MP_BALANCE_PRICING = {
				inputMiss: 0.27 / 1_000_000,
				inputHit: 0.07 / 1_000_000,
				output: 1.1 / 1_000_000,
			};
	
			/**
			 * 账号接口要的客户端版本号。
			 *
			 * ⚠️ 必须非空，否则官方那段 "accountClientMetadata()" 自己也抛
			 *   "this client build carries no DSH_CLIENT_VERSION"。
			 *   这里不跟着引擎版本走：它只用于平台侧的兼容性判断，写死一个
			 *   稳定的 App 版本号最省事，也不会因为引擎升级而失效。
			 */
			const MP_CLIENT_VERSION = "0.3.0";
	
			/**
			 * 把 token 用量折算成金额。
			 *
			 * @param {object} usage - 引擎的 "TokenUsage"。
			 * @returns {number} 估算金额（USD）。
			 */
			function mpCostOf(usage) {
				if (usage === null || typeof usage !== "object") return 0;
				const num = (v) => (typeof v === "number" && Number.isFinite(v) && v > 0 ? v : 0);
				return num(usage.inputTokens) * MP_BALANCE_PRICING.inputMiss
					+ num(usage.cacheReadTokens) * MP_BALANCE_PRICING.inputHit
					+ num(usage.outputTokens) * MP_BALANCE_PRICING.output;
			}
	
			/** 把当前余额推给原生。失败静默（徽标不更新而已，不影响页面）。 */
			function mpPushBalance() {
				const b = window.DSHAndroid;
				if (!b || typeof b.bubbleReportBalance !== "function") return;
				try {
					b.bubbleReportBalance(JSON.stringify({
						wallets: mpBalance.wallets,
						bonusWallets: mpBalance.bonusWallets,
						costLast: mpBalance.costLast,
						costSession: mpBalance.costSession,
						error: mpBalance.lastError,
					}));
				} catch (e) {
					console.warn("[dsh-android-mobile] 上报余额失败:", e && e.message);
				}
			}
	
			/**
			 * 拉一次余额。**账号通道优先**，无该通道时静默返回（不吵）。
			 *
			 * @param {object} remote - "ctx.get("remote")" 拿到的 remote 服务。
			 * @param {string} reason - 触发原因（日志用）。
			 */
			async function mpRefreshBalance(remote, reason, isDisposed) {
				if (isDisposed() || mpBalance.fetching) return;
				if (!remote?.account?.getBalance) {
					// ⚠️ 这里**必须留痕**：静默返回是真机上最坑的形态 ——
					//   用户点了球，看不出任何反应，日志里也什么都没有。
					try {
						window.DSHAndroid?.pageDiag?.(
							`余额刷新跳过(${reason}): remote.account.getBalance 不存在`,
						);
					} catch { /* ignore */ }
					return;
				}
				mpBalance.fetching = true;
				try {
					// ⚠️⚠️ **参数结构不能自己编。**
					//
					// 这里原来写的是 `{clientVersion, locale, utcOffsetSec}` —— 三个字段
					// 名字全错，网关直接拒：
					//   `typert gateway: account/getBalance: wire field "client" failed
					//    boundary validation`
					// 而页面侧的 catch 只把它记成一句"不可用"，徽标永远显示不出余额。
					//
					// 正确结构来自官方 `dsh-client-ui-settings-account` 的
					// `accountClientMetadata()`：
					//   `{ version, locale, timezoneOffsetSeconds }`
					// 且 `version` 必须非空，否则官方那段自己也抛。
					const out = await remote.account.getBalance({
						version: MP_CLIENT_VERSION,
						locale: "zh-CN",
						timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
					});
					if (isDisposed()) return;
					if (out?.ok && out.value?.status === "ready") {
						mpBalance.wallets = Array.isArray(out.value.value) ? out.value.value : [];
						mpBalance.bonusWallets = Array.isArray(out.value.bonusWallets) ? out.value.bonusWallets : [];
						mpBalance.lastError = "";
						console.log(`[dsh-android-mobile] 余额已刷新（${reason}）: ${mpBalance.wallets.length} 个钱包`);
						try {
							window.DSHAndroid?.pageDiag?.(
								`余额刷新(${reason}) 成功: ${mpBalance.wallets.length} 个钱包, 赠送 ${mpBalance.bonusWallets.length}`,
							);
						} catch { /* ignore */ }
					} else if (out?.ok && out.value?.status === "failed") {
						mpBalance.lastError = "账号余额接口返回失败";
						try {
							window.DSHAndroid?.pageDiag?.(`余额刷新(${reason}) 平台返回 failed`);
						} catch { /* ignore */ }
					} else if (out && out.ok === false) {
						mpBalance.lastError = `账号余额不可用: ${out.error?.message ?? out.error ?? "未知"}`;
						try {
							window.DSHAndroid?.pageDiag?.(
								`余额刷新(${reason}) 不可用: ${String(out.error?.message ?? out.error)}`,
							);
						} catch { /* ignore */ }
					} else {
						// `value === null` = 未登录。这不是错误，只是没这条路。
						mpBalance.lastError = "未登录 DeepSeek 账号";
						try {
							window.DSHAndroid?.pageDiag?.(
								`余额刷新(${reason}) 未登录: value=${JSON.stringify(out?.value)?.slice(0, 40)} status=${out?.value?.status}`,
							);
						} catch { /* ignore */ }
					}
					mpPushBalance();
				} catch (e) {
					if (isDisposed()) return;
					mpBalance.lastError = `余额通道异常: ${e?.message ?? e}`;
					try {
						window.DSHAndroid?.pageDiag?.(`余额刷新(${reason}) 抛异常: ${e && e.message}`);
					} catch { /* ignore */ }
					mpPushBalance();
				} finally {
					mpBalance.fetching = false;
				}
			}
	
			/**
			 * 读原生的徽标刷新配置。
			 *
			 * 原生存在 SharedPreferences 里，页面读不到，所以走桥问。
			 * **读不到就用默认值**，绝不能让刷新因为一次桥调用失败而整个不工作。
			 *
			 * @returns {{refreshEverySec:number, refreshOnTurnEnd:boolean, refreshOnTap:boolean}}
			 */
			function mpBadgeRefreshConfig() {
				const fallback = { refreshEverySec: 0, refreshOnTurnEnd: true, refreshOnTap: false };
				try {
					const raw = window.DSHAndroid?.bubbleBadgeConfig?.();
					if (typeof raw !== "string" || raw === "") return fallback;
					const o = JSON.parse(raw);
					return {
						refreshEverySec: Number.isFinite(o?.refreshEverySec) ? Math.max(0, o.refreshEverySec) : 0,
						refreshOnTurnEnd: o?.refreshOnTurnEnd !== false,
						refreshOnTap: o?.refreshOnTap === true,
						// ⚠️ 必须带下来：页面用它限流，读不到就只能瞎猜（曾经写死 20s）。
						tapRefreshMinIntervalSec: Number.isFinite(o?.tapRefreshMinIntervalSec)
							? o.tapRefreshMinIntervalSec
							: 3,
					};
				} catch {
					return fallback;
				}
			}
	
			function mpInstallBalanceBridge(ctx) {
				let refreshFn = null;
				let timer = null;
				let disposed = false;
				let offSession = null;
	
				// 独立余额插件声明 remote/account；账号缺席只挂起本包。
				let remote = null;
				try {
					remote = ctx.remote ?? null;
				} catch (e) {
					console.warn("[dsh-android-mobile] 取 remote 服务失败:", e && e.message);
				}
				if (!remote?.account?.getBalance) {
					// 这个引擎版本没有账号通道：安静退出，不装任何监听。
					// 徽标会一直显示"未登录"，那是**正确**的反馈，不是 bug。
					// ⚠️ 诊断日志带上 `remote` 的真实形状 —— 否则"通道不在"这句话
					//   既可能是没 inject 到，也可能是注进来了但结构不同，无从分辨。
					let shape = "null";
					try {
						shape = remote === null || remote === undefined
							? String(remote)
							: `keys=[${Object.keys(remote).slice(0, 12).join(",")}] account=${
								remote.account === undefined ? "undefined" : `{${Object.keys(remote.account).slice(0, 8).join(",")}}`
							} namespaces=${
								remote.namespaces === undefined
									? "undefined"
									: (() => {
										try { return `[${Object.keys(remote.namespaces).slice(0, 20).join(",")}]`; }
										catch { return String(remote.namespaces); }
									})()
							}`;
					} catch (e) {
						shape = `形状探测失败: ${e && e.message}`;
					}
					console.log(`[dsh-android-mobile] 无 remote.account 通道，余额桥不启用（remote ${shape}）`);
					return () => { disposed = true; };
				}
	
				refreshFn = (reason) => void mpRefreshBalance(remote, reason, () => disposed);
	
				// ── 定时刷新 ───────────────────────────────────────────
				// 配置由原生下发（`bubbleBadgeConfig`）。0 = 不启用。
				// ⚠️ 上限夹到 3600s：这是个会打平台接口的定时器，用户若配了 1 秒
				//   就是在拿自己的账号刷接口限流。
				const cfg = mpBadgeRefreshConfig();
				const everySec = Math.min(Math.max(cfg.refreshEverySec, 0), 3600);
	
				// ── 一次启动诊断：把真实形状打进 app.log ──────────────────
				//
				// ⚠️ 必须放在 `cfg` 之后：踩过 `Cannot access 'cfg' before initialization`，
				//   那行 TDZ 报错会把整段安装逻辑后面的代码一起带崩（静默）。
				//
				// 内容要自洽：有没有 account、有没有 getBalance，一眼能分辨是
				// "没 inject 到" 还是 "注进来了但结构不同"。
				try {
					const acct = remote?.account;
					mpDiag(
						"余额桥已装: remote=" + (remote ? "有" : "无") +
						" account=" + (acct ? "有" : "无") +
						" getBalance=" + (typeof acct?.getBalance) +
						" 定时=" + (cfg.refreshEverySec || 0) + "s" +
						" 点击刷新=" + (cfg.refreshOnTap ? "开" : "关") +
						" 点击限流=" + (cfg.tapRefreshMinIntervalSec || "?") + "s",
					);
				} catch (e) {
					mpDiag("余额桥诊断失败: " + (e && e.message));
				}
	
				if (everySec > 0) {
					timer = window.setInterval(() => refreshFn?.("定时"), everySec * 1000);
					console.log(`[dsh-android-mobile] 余额定时刷新已启用: ${everySec}s`);
				}
	
				// ── 点击刷新 ───────────────────────────────────────────
				// 原生侧在球被点击时经 `bubbleReportBalance` 之外的另一条路通知页面？
				// 不 —— 页面是**发起方**，原生不方便反向调页面里的 JS 函数。
				// 所以这里由原生在点击时写一个 `tapRefresh` 计数到一个可读的桥方法上，
				// 页面轮询它。轮询很轻（300ms 读一个数字），比反向注入 JS 稳。
				let lastTapSeq = 0;
				let lastTapAt = 0;
				// ⚠️ **必须跟用户的配置走，不能写死。**
				//
				//   这里原来写死 `20 * 1000`，而设置页给的默认值是 10 秒。后果：
				//   用户点一下球，10 秒内再点**一律被这里静默丢掉**，页面上完全
				//   没有反应 —— 用户原话「我点这个球…还是没有看到余额」，看着就像
				//   功能坏了。而原生侧本来就按 `tapRefreshMinIntervalSec` 限过一次，
				//   这里再叠一个更长的硬限流纯属帮倒忙。
				//
				//   现在读配置里的秒数（原生已夹在 1–600），且**再放宽容错**：
				//   万一配置没读到就用 3 秒兜底，而不是 20 秒。
				const tapMinGapMs = Math.max(
					1000,
					Math.min(600, Number(cfg.tapRefreshMinIntervalSec) || 3) * 1000,
				);
				let tapPoll = null;
				if (cfg.refreshOnTap) {
					tapPoll = window.setInterval(() => {
						try {
							const seq = window.DSHAndroid?.bubbleTapRefreshSeq?.();
							const n = typeof seq === "number" ? seq : Number(seq);
							if (!Number.isFinite(n) || n <= lastTapSeq) return;
							lastTapSeq = n;
							const now = Date.now();
							if (now - lastTapAt < tapMinGapMs) {
								mpDiag("点球刷新被限流: 距上次 " + Math.round((now - lastTapAt) / 1000) + "s < " + (tapMinGapMs / 1000) + "s");
								return;
							}
							lastTapAt = now;
							refreshFn?.("点击");
						} catch {
							/* 读不到就算了，下一轮再试 */
						}
					}, 300);
				}
	
				// 启动先拉一次：用户一进 App 就该看到余额。
				refreshFn("启动");
	
				// 每轮结束刷新。
				// ⚠️ 包 try/catch：余额是锦上添花，绝不能因为它在事件回调里抛错
				//   而影响会话本身。
				try {
					offSession = ctx.on?.("session/event", (_session, event) => {
						try {
							if (disposed) return;
							const type = event?.type;
							if (typeof type !== "string") return;
							if (type === "assistant/message" || type === "assistant/attempt") {
								// usage 在这两个事件上；`step/end` **不带**（已核对引擎源码）。
								const cost = mpCostOf(event?.data?.usage);
								if (cost > 0) {
									mpBalance.costLast = Number(cost.toFixed(6));
									mpBalance.costSession = Number((mpBalance.costSession + cost).toFixed(6));
									mpPushBalance();
								}
							} else if (type === "turn/end") {
								// 用户可以在徽标设置里关掉轮末刷新（比如他配了大额定时刷新）。
								if (mpBadgeRefreshConfig().refreshOnTurnEnd) refreshFn?.("轮末");
							}
						} catch (e) {
							console.warn("[dsh-android-mobile] 余额事件处理失败:", e && e.message);
						}
					});
				} catch (e) {
					console.warn("[dsh-android-mobile] 余额事件订阅失败:", e && e.message);
				}
	
				return () => {
					if (disposed) return;
					disposed = true;
					refreshFn = null;
					if (typeof offSession === "function") offSession();
					if (timer !== null) {
						window.clearInterval(timer);
						timer = null;
					}
					if (tapPoll !== null) {
						window.clearInterval(tapPoll);
						tapPoll = null;
					}
				};
			}

			const mpDiag = (msg) => { try { window.DSHAndroid?.pageDiag?.(String(msg)); } catch {} };
			const disposeBalanceBridge = mpInstallBalanceBridge(ctx);
			ctx.effect(() => disposeBalanceBridge);
		}
		const exports = { apply, inject: ["remote", "remote.account"] };

		return exports;
	}
});
