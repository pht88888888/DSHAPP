import { MP_PRIV_API, MP_PRIV_NS, MP_PRIV_ZH, MP_PRIV_EN } from "./messages.js";
import { MP_PRIV_S, MP_PRIV_TONE, mpCopyText } from "../settings-styles.js";
import { useRootStatus } from "./use-root-status.js";
import { useShizukuStatus } from "./use-shizuku-status.js";

export function createPrivilegedSection(require) {
  let mpPrivReact = null;
  function mpPrivSection(props) {
  	const React = mpPrivReact;
  	const S = MP_PRIV_S;
  	const t = typeof props.t === "function" ? props.t : (key) => MP_PRIV_ZH[key] ?? key;
  	const [state, setState] = React.useState({ phase: "loading" });
  	// ⚠️ **软截止**：探测真正返回前，界面不能一直锁在「检测中」。
  	//
  	//   真机反馈的现象：首次安装打开本页，「当前状态」一直显示「检测中」，
  	//   既没有结论也没有任何按钮（那个「重新检测」按钮在 loading 期间是
  	//   `disabled` 的），只能硬等——而宿主半的探测超时是 **45 秒**，
  	//   用户描述为「等几分钟」。
  	//
  	//   为什么 45 秒砍不掉：那个预算是 runner 最坏路径（三条通道级联 +
  	//   adbd 重启）的上限，砍了会把「慢但成功」误判成失败。
  	//   但**界面不必陪着一起等** —— 超过这个软截止就先按「还没结论」渲染，
  	//   把按钮还给用户；真结果回来时照常覆盖（`phase` 变 ready/error）。
  	const SOFT_DEADLINE_MS = 8000;
  	const [softExpired, setSoftExpired] = React.useState(false);
  	const requestCleanup = React.useRef(null);
  	React.useEffect(() => () => requestCleanup.current?.(), []);
  	const refresh = React.useCallback(() => {
  		requestCleanup.current?.();
  		let alive = true;
  		setState({ phase: "loading" });
  		setSoftExpired(false);
  		// 到点就把界面从「检测中」解锁。真正的 fetch 不取消：它回来时
  		// 下面的 then/catch 会用真实结论覆盖掉这个临时视图。
  		const softTimer = setTimeout(() => { if (alive) setSoftExpired(true); }, SOFT_DEADLINE_MS);
  		window.fetch(MP_PRIV_API, { cache: "no-store" })
  			.then((res) => {
  				if (!res.ok) throw new Error("HTTP " + res.status);
  				return res.json();
  			})
  			.then((data) => { if (alive) { clearTimeout(softTimer); setState({ phase: "ready", data }); } })
  			.catch((error) => {
  				if (alive) {
  					clearTimeout(softTimer);
  					setState({ phase: "error", message: String((error && error.message) || error) });
  				}
  			});
  		requestCleanup.current = () => { alive = false; clearTimeout(softTimer); };
  		return requestCleanup.current;
  	}, []);
  	React.useEffect(refresh, [refresh]);

  	// ══ root（uid 0）状态 ══════════════════════════════════════════
  	//
  	// 与上面的 uid 2000 状态**来源不同**：那条走宿主半的 HTTP 路由（探测要
  	// spawn runner），而 root 的结论在**原生**侧（探测要起 `su` 进程、可能弹
  	// 授权框），所以走 JS 桥 `window.DSHAndroid.rootState()`。
  	//
  	// ⚠️ 桥上的 `rootState()` **只读缓存、不触发探测** —— 它跑在 WebView 的
  	//   JS 线程上，同步探测会把页面卡住。重新探测走 `rootRecheck()`，那是
  	//   后台线程，界面靠下面的轮询拿结果。
  	const { root, rootBusy, readRoot, recheckRoot } = useRootStatus(React);

  	// ══ Shizuku 状态 ═══════════════════════════════════════════════
  	//
  	// 与上面两块**来源又不同**：Shizuku 的「装了没 / 服务在跑没 / 授权没」
  	// 全在**原生**侧（要问 PackageManager、要问 binder），所以走 JS 桥
  	// `window.DSHAndroid.shizukuState()`，与 root 同一套语义（只读缓存、
  	// 不触发探测，同步返回 JSON 文本）。
  	//
  	// ⚠️ **四个字段必须分开存、分开判**（见 PageBridge.shizukuState 的表）：
  	//   合并成一个布尔的话，「没装」和「装了没启动」会给出同一个结论，
  	//   而这两者要引导用户做的事完全不同（去下载 vs 去打开它）。
  	const { shizuku, shizukuBusy, readShizuku, grantShizuku } = useShizukuStatus(React, refresh, t);

  	const data = state.phase === "ready" ? state.data : null;
  	// ⚠️ 软截止到期后**不再算 loading**：下一步提示与按钮可用性都按正常态渲染，
  	//   用户立刻拿回控制权（见 SOFT_DEADLINE_MS）。
  	//   真结果回来时 `state.phase` 会变成 ready/error，一切照常覆盖。
  	const loading = state.phase === "loading" && !softExpired;
  	// ⚠️ 但**徽章文案**不能因为软截止就改成「未开启」—— 那时根本没有结论，
  	//   报「未开启」是假话（真结果可能是「已开启」）。只有拿到真结论
  	//   （ready/error）才允许改徽章，否则一律维持「检测中」。
  	const settled = state.phase !== "loading";
  	const identityOk = !!(data && data.identity && data.identity.ok);
  	const tone = !settled ? "unknown"
  		: state.phase === "error" ? "off"
  			: identityOk ? "on" : "off";
  	const badgeText = !settled ? t("statusUnknown")
  		: state.phase === "error" ? t("failed")
  			: identityOk ? t("statusOn") : t("statusOff");

  	// 当前通道 —— 只有固定端口那条**不依赖无线调试开关**，所以要显式区分，
  	// 否则用户看到「已开启」也不知道关了开关会不会断。
  	const channel = data && data.identity ? data.identity.channel : "none";
  	const channelText = channel === "shizuku" ? t("chShizuku")
  		: channel === "legacy" ? t("chStable")
  			: channel === "wireless" ? t("chWireless") : "";

  	// ── 系统版本：决定走哪条引导路径 ──────────────────────
  	//
  	// ⚠️ **Android 10 及以下没有「无线调试」** —— 那是 Android 11（API 30）
  	//   才有的功能。在 Android 10 上还把它摆在最上面、还挂「推荐」、还给出
  	//   「配对无线调试」按钮，用户照着去系统设置里找只会找不到（真机反馈过）。
  	//
  	// 判据用 UA 里的 `Android <版本>`：浏览器进程拿不到 `Build.VERSION.SDK_INT`，
  	//   而 UA 是现成的、不需要为它新增一条原生桥。
  	const androidMajor = (() => {
  		const m = /Android\s+(\d+)/i.exec(navigator.userAgent || "");
  		return m ? Number.parseInt(m[1], 10) : 0;
  	})();
  	// 版本判不出来时**当作不支持**（走电脑引导）：电脑引导在所有版本上都成立，
  	// 而无线调试在 Android 10 上根本不存在。判错方向宁可保守。
  	const wirelessSupported = androidMajor >= 11;

  	// 「下一步该做什么」—— 由当前状态推出来，摆在首屏。
  	// 用户不该为了知道点哪里而读完全部卡片。
  	const keyReady = !!(data && data.key
  		&& (data.key.origin === "imported" || data.key.origin === "paired"));
  	let next = null;
  	if (data) {
  		// 失败分类（runner 那条 `诊断: kind=…` 行）。没拿到时是 "unknown"，
  		// 下面的分支会自动退回通用引导 —— 与改动前的行为一致。
  		const kind = data.identity && data.identity.kind ? data.identity.kind : "unknown";
  		// 四类「密钥侧」的问题，正解都是**再配对一次**，只是原因不同。
  		const keyKind = kind === "no-key" || kind === "key-revoked"
  			|| kind === "key-unauthorized" || kind === "key-blocked";
  		// ⚠️ **Shizuku 的优先级判据**（本次重做新增，是这次布局调整里
  		//   唯一改变「用户实际会去点哪个按钮」的地方）。
  		//
  		//   理由：内置的两条路都以「设备上有个可连的 adbd」为前提，而
  		//   Android 10 上那意味着每次重启都得插电脑。Shizuku 不需要 adbd、
  		//   重启也有效 —— 所以只要它**有可能**走得通，就该排在前面。
  		//
  		//   判据用 `installed || binder` 而不是 `granted`：
  		//     · 装了但没启动 → 用户点一下就能启动，值得推
  		//     · 没装 → 也值得推（下载一次，之后一劳永逸）
  		//   两者都比「去插电脑」轻。真机反馈的那位用户正是这种情况。
  		const shizukuViable = shizuku.installed || shizuku.binder;
  		if (identityOk) {
  			next = channel === "wireless"
  				? { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") }
  				: { text: t("nextAllGood"), action: null };
  		} else if (shizuku.granted) {
  			// Shizuku 已授权但 runner 还没跟上 —— 只要重新检测，不用重启。
  			next = { text: t("shizukuNextGrantedStale"), action: null };
  		} else if (shizukuViable) {
  			// 有 Shizuku 可用，就别再让用户去碰 adb 那条路。
  			// ⚠️ 这里**必须给按钮**：只写一句「去启动 Shizuku」而不给入口，
  			//   用户得自己在应用列表里找 Shizuku —— 而本页明明知道该点哪里。
  			//   用 `onClick` 而不是 `action`：授权走的是 grantShizuku（要轮询
  			//   结果），不是 doAction 里那种一发即忘的动作。
  			next = shizuku.binder
  				? { text: t("shizukuNextUngranted"), onClick: grantShizuku, label: t("shizukuGrant") }
  				: { text: t("shizukuNextStopped"), action: "shizukuOpen", label: t("shizukuOpen") };
  		} else if (keyKind) {
  			// ⚠️ 只在系统真支持无线调试时才给配对按钮 —— 否则那个按钮在
  			//    Android 10 上永远走不通，点了只是白等（与下面同一条约束）。
  			const text = kind === "no-key"
  				? t(wirelessSupported ? "nextNoKey" : "nextNoKeyPc")
  				: kind === "key-revoked" ? t("nextRevoked")
  					: kind === "key-unauthorized" ? t("nextUnauthorized")
  						: t("nextKeyBlocked");
  			next = wirelessSupported
  				? { text, action: "pair", label: t(kind === "no-key" ? "wlPair" : "nextPair") }
  				: { text, action: null };
  		} else if (kind === "wireless-stale" || kind === "no-channel") {
  			// 这两类的正解是**切到固定端口**：固定端口一旦就绪就不再依赖
  			// 无线调试开关，恰好绕开「无线调试关着 ⇒ 无线通道必然不可用」。
  			next = {
  				text: t(kind === "wireless-stale" ? "nextWirelessStale" : "nextNoChannel"),
  				action: "switchTcp",
  				label: t("wlTcp"),
  			};
  		} else if (kind === "adb-missing" || kind === "timeout") {
  			// 这两类没有能替用户按的按钮：前者要重装 App，后者只是 adbd
  			// 还在重启（等一会儿点「重新检测」）。给一句可执行的说明就够，
  			// **不要**摆一个点了没用的按钮。
  			next = { text: t(kind === "adb-missing" ? "nextAdbMissing" : "nextTimeout"), action: null };
  		} else if (data.key && data.key.origin === "generated" && wirelessSupported) {
  			// 只有系统真支持无线调试时，才引导去配对 —— 否则那个按钮
  			// 在 Android 10 上永远走不通，点了只是白等。
  			next = { text: t("nextPairHint"), action: "pair", label: t("wlPair") };
  		} else if (keyReady) {
  			next = { text: t("nextSwitchHint"), action: "switchTcp", label: t("wlTcp") };
  		} else {
  			next = {
  				text: wirelessSupported ? t("nextChoose") : t("nextChoosePc"),
  				action: null,
  			};
  		}
  	}

  	// 两个原生动作都要走 JS 桥 —— 配对码得经通知栏输入，而通知是 Android
  	// 的能力，Node 侧（宿主半的 HTTP 路由）够不到。
  	const doAction = (action) => {
  		const b = window.DSHAndroid;
  		const fn = action === "pair" ? "startWirelessPairing"
  			: action === "switchTcp" ? "switchToTcpMode"
  				: action === "shizukuOpen" ? "shizukuOpenManager"
  					: action === "shizukuDownload" ? "shizukuOpenDownload" : null;
  		if (fn === null) return;
  		if (!b || typeof b[fn] !== "function") { window.alert(t("wlNoBridge")); return; }
  		// ⚠️ 配对的一切交互（提示、配对码输入框、取消按钮）**都挂在那条常驻
  		//   通知上**，通知被系统关掉时原生侧会直接拒绝开始（见
  		//   `AdbPairingNotifier.start`）。不在这里先说清楚的话，用户看到的是
  		//   「点了配对，什么都没发生」—— 然后反复点，永远不知道为什么。
  		if (action === "pair") {
  			try {
  				if (typeof b.pairingNotificationsEnabled === "function"
  					&& !b.pairingNotificationsEnabled()) {
  					window.alert(t("wlNoNotify"));
  					return;
  				}
  			} catch (e) { /* 查不了就照常走，原生侧还有一道拦截 */ }
  		}
  		b[fn]();
  		// 只有配对需要回写「进行中」—— 它是一条要用户离开本页去系统设置的
  		// 长流程；「切到固定端口」是几秒钟的原生调用，不需要。
  		if (action === "pair") setPairing(true);
  	};

  	/** 取消配对：停掉原生侧那条常驻监听，并把本页状态复位。 */
  	const cancelPairing = () => {
  		const b = window.DSHAndroid;
  		if (b && typeof b.cancelWirelessPairing === "function") b.cancelWirelessPairing();
  		setPairing(false);
  	};

  	// 无线端口三态：真的在听 / 文件里有值但端口已死 / 没发现。
  	// ⚠️ 中间那态以前会显示成绿灯（只查文件在不在），真机撞到过 ——
  	// 无线调试关着、35589 早已没人监听，界面却报「无线端口 35589」。
  	const wlPort = data && data.wireless ? data.wireless.connect : null;
  	const wlStale = data && data.wireless ? data.wireless.stale : null;
  	const [showDetails, setShowDetails] = React.useState(false);

  	const rows = [];
  	if (data) {
  		rows.push({
  			key: "runner", label: t("rowRunner"), ok: data.runner.ok,
  			value: data.runner.ok ? `${t("ready")} · ${data.runner.size} ${t("bytes")}` : t("missing"),
  		});
  		// ⚠️ 徽章不能只看「密钥文件在不在」：**App 现场生成的密钥设备并不信任**，
  		// 显示绿点会让人以为可以用了。
  		//
  		// 三态（判据见 assets/dsh-android-shell.mjs 的 `keyOrigin()`）：
  		//   imported / paired → 绿点，设备已信任，直接能用
  		//   generated         → 黄点，必须先配对
  		//   null              → 灰点，**来源未知**（标记文件是后加的，
  		//                       老安装的密钥没有它，无法回溯推断）
  		// 未知时**故意不猜**成「已导入」—— 那正是这次要修的误报。
  		rows.push({
  			key: "key", label: t("rowKey"), ok: keyReady,
  			warn: data.key.ok && data.key.origin === "generated",
  			unknown: data.key.ok && !keyReady && data.key.origin !== "generated",
  			value: data.key.ok
  				? `${data.key.origin === "generated" ? t("generated")
  					: data.key.origin === "paired" ? t("paired")
  						: data.key.origin === "imported" ? t("imported")
  							: t("keyOriginUnknown")} · ${data.key.size} ${t("bytes")}${data.key.importedNow ? " · " + t("justImported") : ""}`
  				: t("notImported"),
  		});
  		rows.push({
  			key: "identity", label: t("rowIdentity"), ok: identityOk,
  			value: identityOk
  				? `${t("uidOk")}${data.identity && data.identity.channel === "legacy" ? " · " + t("chStable")
  					: data.identity && data.identity.channel === "wireless" ? " · " + t("chWireless") : ""}`
  				: `${t("uidApp")}${data.identity && data.identity.reason ? " · " + data.identity.reason : ""}`,
  		});
  	}

  	// 复制结果按**对象**存（不是布尔、也不是纯字符串）：两个块共用一个
  	// 「最近复制的是哪条」，点第二条时第一条会自己复位成「复制」，
  	// 不会两块同时显示「已复制」让人分不清到底复制了哪条。
  	// 存对象而非字符串还有个作用：连点同一个按钮时 `at` 每次都不同，
  	// 保证 state 一定变化 → effect 重跑 → 复位计时器被重新计时。
  	const [copied, setCopied] = React.useState(null);
  	const copyMounted = React.useRef(true);
  	React.useEffect(() => {
  		copyMounted.current = true;
  		return () => { copyMounted.current = false; };
  	}, []);
  	React.useEffect(() => {
  		if (copied === null) return undefined;
  		const timer = window.setTimeout(() => setCopied(null), 2000);
  		return () => window.clearTimeout(timer);
  	}, [copied]);

  	// 是否已经发起过配对（点了「配对无线调试」但还没取消）。
  	//
  	// ⚠️ 这个状态**必须让用户看见**，否则点了按钮后界面毫无变化，用户会以为
  	// 没点上而反复点 —— 而真实情况是「正在等你去系统里打开那个界面」。
  	// 只存本地 state：真正的监听状态在原生侧，这里只是给个即时反馈，
  	// 不需要（也不该）为此多开一条查询接口。
  	const [pairing, setPairing] = React.useState(false);
  	const e = React.createElement;
  	// 命令块 = 等宽代码 + 复制按钮。**按钮不是锦上添花**：本插件的全局
  	// CSS 把 `user-select` 压成 `none`（见文件头 4c），命令长按选不中，
  	// 而用户是在电脑终端里敲这些命令，只能照着手机屏幕抄 —— 没有按钮就得
  	// 逐字符手抄 `%USERPROFILE%\.android\adbkey`。
  	const cmdBlock = (text) => e("div", { key: text, style: S.cmdRow },
  		e("code", { style: S.cmd }, text),
  		e("button", {
  			type: "button",
  			style: S.cmdBtn,
  			onClick: () => {
  				mpCopyText(text).then((ok) => { if (copyMounted.current) setCopied({ text, ok, at: Date.now() }); });
  			},
  		}, copied && copied.text === text
  			? (copied.ok ? t("copied") : t("copyFailed"))
  			: t("copy")),
  	);
  	const guideSteps = [
  		e("li", { key: 0, style: S.step }, t("step1")),
  		e("li", { key: 1, style: S.step }, t("step2"), cmdBlock(t("cmd2"))),
  		e("li", { key: 2, style: S.step }, t("step3"), cmdBlock(t("cmd3"))),
  		e("li", { key: 3, style: S.step }, t("step4")),
  	];

  	// ── 首屏：结论 + 下一步 ────────────────────────────────
  	// 原来的首屏是一张「执行通道 / ADB 密钥 / 实际身份」的技术表格，
  	// 用户得自己把三行翻译成「我现在能不能用、不能的话点哪里」。
  	// 现在把结论摆在最上面，明细折进「查看详情」。
  	const head = e("div", { style: S.card },
  		e("div", { style: S.cardHead },
  			e("h3", { style: S.cardTitle }, t("statusTitle")),
  			e("span", { style: { ...S.badge, color: MP_PRIV_TONE[tone].color, background: MP_PRIV_TONE[tone].bg } }, badgeText),
  		),
  		// 已开启时把「走哪条通道」直接写在结论下面 —— 这是用户唯一需要
  		// 知道的运行期事实（关无线调试/关 Wi-Fi 会不会断）。
  		identityOk && channelText
  			? e("p", { style: S.summary }, channelText)
  			: null,
  		next ? e("div", { style: S.next },
  			e("span", { style: S.nextText }, next.text),
  			// 两种按钮：`action` 走 doAction（原生一侧的一发即忘动作），
  			// `onClick` 走组件内的回调（Shizuku 授权要轮询结果）。
  			next.onClick ? e("button", {
  				type: "button",
  				style: S.btnPrimary,
  				disabled: shizukuBusy,
  				onClick: next.onClick,
  			}, shizukuBusy ? t("shizukuGranting") : next.label)
  				: next.action ? e("button", {
  					type: "button",
  					style: S.btnPrimary,
  					onClick: () => doAction(next.action),
  				}, next.label) : null,
  		) : null,
  		// ⚠️ 软截止到期、真结果还没回来时，明确告诉用户「还在探测、你可以不等」，
  		//   并直接给出「重新匹配」入口 —— 而不是留一个没有结论也没有按钮的死界面。
  		//   真机反馈原话：「你一检测没有就是没有了呀，没有就让用户重新走匹配流程啊，
  		//   我要这里等几分钟？」这条分支就是那个诉求的落点。
  		softExpired && state.phase === "loading" ? e("div", { style: S.next },
  			e("span", { style: S.nextText }, t(wirelessSupported ? "softPending" : "softPendingPc")),
  			// ⚠️ 只有系统真支持无线调试时才给配对按钮 —— Android 10 上配对
  			//   根本不存在，摆一个点了必然白等的按钮比不给更糟（与上面
  			//   `keyKind` 分支同一条约束）。
  			wirelessSupported ? e("button", {
  				type: "button",
  				style: S.btnPrimary,
  				onClick: () => doAction("pair"),
  			}, t("wlPair")) : null,
  		) : null,
  		state.phase === "error" ? e("p", { style: S.error }, state.message) : null,
  		e("div", { style: S.actions },
  			e("button", {
  				type: "button",
  				style: S.btn,
  				// ⚠️ 只在**软截止之前**禁用。到点即解锁，用户不必陪着探测一起等 ——
  				//   真机反馈的「一直显示检测中、又没有按钮、又没有停止」正是
  				//   这里一直 `disabled` 造成的。
  				disabled: loading,
  				onClick: refresh,
  			}, loading ? t("checking") : t("recheck")),
  			data ? e("button", {
  				type: "button",
  				style: S.btnQuiet,
  				onClick: () => setShowDetails((v) => !v),
  			}, showDetails ? t("hideDetails") : t("showDetails")) : null,
  		),
  		// 明细：定位问题时才需要，默认收起。
  		showDetails && data ? e("div", { style: S.details },
  			rows.map((row) => e("div", { key: row.key, style: S.row },
  				e("span", {
  					style: {
  						...S.dot,
  						background: (row.ok ? MP_PRIV_TONE.on
  							: row.warn ? MP_PRIV_TONE.warn
  								: row.unknown ? MP_PRIV_TONE.unknown
  									: MP_PRIV_TONE.off).color,
  					},
  				}),
  				e("span", { style: S.rowLabel }, row.label),
  				e("span", { style: S.rowValue }, row.value),
  			)),
  		) : null,
  	);

  	// ── 三条开启路径：统一成同一种卡片结构 ────────────────────
  	//
  	// ⚠️ **这是本次布局重做的核心。** 原布局里三条路各有各的形状：
  	//   无线调试是一张普通卡（按钮常驻三个）、电脑引导是一个 details、
  	//   Shizuku 根本没有；顺序还随系统版本翻转（Android 10 电脑在前、
  	//   Android 11+ 无线在前）。结果是用户每次打开看到的排版都不一样，
  	//   得重新认一遍哪里是标题、哪里是按钮。
  	//
  	//   现在三条路**结构完全一致**，顺序也**固定**（Shizuku → 无线 → 电脑）：
  	//     标题 + 状态徽章
  	//     一句话说明
  	//     关键事实行（可选）
  	//     按钮行
  	//     折起的步骤（可选）
  	//   动线因此是固定的：先看徽章判断这条路通不通，再看按钮做下一步。
  	const pathCard = (opts) => e("div", { style: S.card },
  		e("div", { style: S.cardHead },
  			e("h3", { style: S.cardTitle }, opts.title),
  			e("span", {
  				style: { ...S.badge, color: opts.tone.color, background: opts.tone.bg },
  			}, opts.badge),
  		),
  		opts.intro ? e("p", { style: S.intro }, opts.intro) : null,
  		...(opts.rows || []),
  		opts.actions && opts.actions.length > 0
  			? e("div", { style: S.actions }, ...opts.actions)
  			: null,
  		opts.steps && opts.steps.length > 0 ? e("details", { style: S.foldInline },
  			e("summary", { style: S.foldHead }, t("pathShowSteps")),
  			e("div", { style: S.foldBody }, e("ol", { style: S.steps }, opts.steps)),
  		) : null,
  		...(opts.notes || []),
  	);

  	// ① Shizuku —— 唯一不依赖 adbd 的那条，所以固定排第一。
  	//
  	// ⚠️ 四态徽章：装了 / 服务在跑 / 已授权 是**三个独立事实**，
  	//   合并的话「没装」和「装了没启动」会显示成同一个结论，
  	//   而这两者要用户做的事完全不同（去下载 vs 去打开它）。
  	const szTone = !shizuku.installed ? MP_PRIV_TONE.off
  		: !shizuku.binder ? MP_PRIV_TONE.warn
  			: !shizuku.granted ? MP_PRIV_TONE.warn
  				: MP_PRIV_TONE.on;
  	const szBadge = !shizuku.installed ? t("shizukuAbsent")
  		: !shizuku.binder ? t("shizukuInstalled")
  			: !shizuku.granted ? t("shizukuUngranted")
  				: t("shizukuGranted");
  	// 主按钮按状态换：没装→下载，装了没跑→打开它，跑了没授权→授权，
  	// 已授权→不需要主按钮（只留「重新检测」）。
  	//
  	// ⚠️ **一次只给一个主按钮**：原布局在同一屏里摆了五个同等分量的按钮
  	//   （配对/切端口/取消/重新检测/查看详情），用户不知道点哪个才对。
  	const szPrimary = !shizuku.installed
  		? e("button", {
  			type: "button", style: S.btnPrimary,
  			onClick: () => doAction("shizukuDownload"),
  		}, t("shizukuDownload"))
  		: !shizuku.binder
  			? e("button", {
  				type: "button", style: S.btnPrimary,
  				onClick: () => doAction("shizukuOpen"),
  			}, t("shizukuOpen"))
  			: !shizuku.granted
  				? e("button", {
  					type: "button", style: S.btnPrimary,
  					disabled: shizukuBusy,
  					onClick: grantShizuku,
  				}, shizukuBusy ? t("shizukuGranting") : t("shizukuGrant"))
  				: null;
  	const szCard = pathCard({
  		title: t("shizukuTitle"),
  		// 徽章兼「推荐」位：Shizuku 在**两种系统上都能用**且重启有效，
  		// 所以无论 Android 版本都是首推的那条（这正是本次加它的理由）。
  		tone: szTone,
  		badge: szBadge,
  		// ⚠️ intro 是**动态的「现在该做什么」**，不是固定的功能说明。
  		//   固定说明只在「没装」时才有信息量（那时用户确实不知道 Shizuku
  		//   是什么）；装了的人再看一遍等于白占一屏。
  		//   这与首屏 `next` 那条是同一条原则：先说下一步，再说原理。
  		intro: !shizuku.installed ? t("shizukuNextAbsent")
  			: !shizuku.binder ? t("shizukuNextStopped")
  				: !shizuku.granted ? t("shizukuNextUngranted")
  					// ⚠️ 第四种：Shizuku 已授权，但 runner 的探测结论还没跟上
  					//   （identityOk 为假）。此时说「已就绪」与上面的徽章矛盾，
  					//   必须说清「点一下重新检测就好」。
  					: identityOk ? t("shizukuNextGranted")
  						: t("shizukuNextGrantedStale"),
  		rows: [
  			e("div", { key: "svc", style: S.row },
  				e("span", { style: { ...S.dot, background: szTone.color } }),
  				e("span", { style: S.rowLabel }, t("shizukuRowService")),
  				e("span", { style: S.rowValue },
  					!shizuku.installed ? t("shizukuServiceAbsent")
  						: shizuku.binder ? t("shizukuServiceRunning")
  							: t("shizukuServiceStopped")),
  			),
  			// 服务身份/版本只在服务真的在跑时才有值 —— 没跑时显示「未运行」
  			// 是重复上面那行，所以干脆不渲染。
  			shizuku.binder ? e("div", { key: "uid", style: S.row },
  				e("span", { style: { ...S.dot, background: MP_PRIV_TONE.on.color } }),
  				e("span", { style: S.rowLabel }, t("shizukuRowUid")),
  				e("span", { style: S.rowValue }, shizuku.uid >= 0 ? `uid ${shizuku.uid}` : "—"),
  			) : null,
  			shizuku.binder ? e("div", { key: "ver", style: S.row },
  				e("span", { style: { ...S.dot, background: MP_PRIV_TONE.unknown.color } }),
  				e("span", { style: S.rowLabel }, t("shizukuRowVersion")),
  				e("span", { style: S.rowValue }, shizuku.version > 0 ? String(shizuku.version) : "—"),
  			) : null,
  		],
  		actions: [szPrimary, e("button", {
  			type: "button", style: S.btn,
  			onClick: readShizuku,
  		}, t("shizukuRecheck"))].filter(Boolean),
  		steps: [
  			e("li", { key: 0, style: S.step }, t("shizukuStep1")),
  			e("li", { key: 1, style: S.step }, t("shizukuStep2")),
  			e("li", { key: 2, style: S.step }, t("shizukuStep3")),
  			e("li", { key: 3, style: S.step }, t("shizukuStep4")),
  		],
  		notes: [e("p", { key: "note", style: S.note }, t("shizukuNote"))],
  	});

  	// ② 无线调试 —— Android 10 上给一张**纯说明卡**，不再是整张消失。
  	//
  	// ⚠️ 这一处改过两轮，方向是相反的，别再改回去：
  	//   原布局保留整张卡、只把配对按钮藏掉 ⇒ 用户看到一张标题写着
  	//   「无线调试」、里面有四个步骤、却没有任何按钮可点的卡，照着做白做
  	//   （真机反馈的「按步骤走了没成功、一切都跟没动一样」有一部分就是它）。
  	//   改成整张不渲染之后又矫枉过正 —— 用户在 ADB 标签下**根本看不到**
  	//   还有这么一条路，只会以为本应用漏做了。
  	//
  	//   现在：**行还在、点得进去、里面说清为什么不支持**。可见性与
  	//   「不骗用户去点一个走不通的按钮」两个要求同时满足 —— 判据是
  	//   「有没有可点的动作」，而不是「这一块在不在」。
  	const wlCard = !wirelessSupported
  		? e("div", { style: S.card },
  			e("div", { style: S.cardHead },
  				e("h3", { style: S.cardTitle }, t("wlTitle")),
  				e("span", {
  					style: {
  						...S.badge,
  						color: MP_PRIV_TONE.unknown.color,
  						background: MP_PRIV_TONE.unknown.bg,
  					},
  				}, t("pathUnavailable")),
  			),
  			e("p", { style: S.intro }, t("wlAbsentNote")),
  		)
  		: pathCard({
  		title: t("wlTitle"),
  		tone: identityOk && channel === "wireless" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off,
  		badge: identityOk && channel === "wireless" ? t("pathReady") : t("pathTodo"),
  		intro: t("wlIntro"),
  		rows: [
  			// 无线端口是「有没有发现到」的最直接证据 —— 没有它，
  			// 用户只能靠「配对按钮点了没反应」来猜，无从下手。
  			// 三态：真的在听（绿）/ 文件里有值但端口已死（灰，说明无线调试关了）/
  			// 没发现（灰）。中间那态以前会误报成绿灯。
  			data ? e("div", { key: "port", style: S.row },
  				e("span", {
  					style: {
  						...S.dot,
  						background: wlPort ? MP_PRIV_TONE.on.color
  							: wlStale ? MP_PRIV_TONE.unknown.color : MP_PRIV_TONE.off.color,
  					},
  				}),
  				e("span", { style: S.rowLabel }, t("wlPort")),
  				e("span", { style: S.rowValue }, wlPort ? String(wlPort)
  					: wlStale ? t("wlPortStale") : t("wlPortNone")),
  			) : null,
  		],
  		actions: [
  			e("button", {
  				type: "button", style: S.btnPrimary,
  				disabled: pairing,
  				onClick: () => doAction("pair"),
  			}, pairing ? t("wlPairing") : t("wlPair")),
  			// ⚠️ 「取消配对」只在**真的在配对时**才出现。原布局让它常驻，
  			//   于是一个没有任何进行中操作的页面上摆着「取消配对」——
  			//   用户会以为有什么正在跑，或者点了它以为取消了什么。
  			pairing ? e("button", {
  				type: "button", style: S.btn,
  				onClick: cancelPairing,
  			}, t("wlCancel")) : null,
  			e("button", {
  				type: "button", style: S.btn,
  				onClick: () => doAction("switchTcp"),
  			}, t("wlTcp")),
  		].filter(Boolean),
  		steps: [
  			e("li", { key: 0, style: S.step }, t("wlStep1")),
  			e("li", { key: 1, style: S.step }, t("wlStep2")),
  			e("li", { key: 2, style: S.step }, t("wlStep3")),
  			e("li", { key: 3, style: S.step }, t("wlStep4")),
  		],
  		notes: [
  			// 等待期间把「接下来该做什么」直接铺在按钮下面 —— 用户点了配对之后
  			// 眼神还在这块区域，跑到别处去提示等于没提示。
  			pairing ? e("p", { key: "hint", style: S.note }, t("wlPairingHint")) : null,
  			e("p", { key: "tcp", style: S.note }, t("wlTcpHint")),
  			// ⚠️ 这条必须留：用户看到「输入配对码」的第一反应是「让 App 自己填」
  			//   或者「复制粘贴」，而 SPAKE2 要求人工输入。不说清就会反复试。
  			e("p", { key: "code", style: S.note }, t("wlPairHint")),
  		].filter(Boolean),
  	});

  	// ③ 电脑引导（固定端口）—— 所有版本上都成立，所以永远渲染。
  	const pcCard = pathCard({
  		title: wirelessSupported ? t("pcTitle") : t("pcTitlePrimary"),
  		tone: identityOk && channel === "legacy" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off,
  		badge: identityOk && channel === "legacy" ? t("pathReady") : t("pathTodo"),
  		intro: t("pcIntro"),
  		// Android 10 上「无线调试」那张卡整张不渲染，所以它为什么不存在的
  		// 那句话挪到这里 —— 否则用户会以为本应用漏做了这个功能。
  		notes: [!wirelessSupported
  			? e("p", { key: "wl-absent", style: S.note }, t("wlAbsentNote"))
  			: null].filter(Boolean),
  		actions: [e("button", {
  			type: "button", style: S.btn,
  			onClick: () => doAction("switchTcp"),
  		}, t("wlTcp"))],
  		// ⚠️ 这里的步骤**自带命令块**，不能走 pathCard 的纯文本 steps ——
  		//   所以整段作为 details 内容直接传进来。命令必须能一键复制：
  		//   本插件的全局 CSS 把 user-select 压成 none，长按选不中。
  		steps: guideSteps,
  	});

  	// ── 注意事项（折起）────────────────────────────────
  	const notesCard = e("details", { style: S.fold },
  		e("summary", { style: S.foldHead }, t("notesTitle")),
  		e("ul", { style: S.notes },
  			[t("note1"), t("note2"), t("note3")].map((text, index) => e("li", { key: index, style: S.note }, text)),
  		),
  	);

  	// ── root（uid 0）二级界面 ─────────────────────────────
  	//
  	// 为什么单独一个标签：uid 2000 与 root 是**两条独立的通道**，可用性互不
  	// 影响（没 root 的设备照样能用 uid 2000）。所以不能把 root 塞进 ADB
  	// 那个标签里 —— 那个标签只回答「uid 2000 能不能用」。
  	//
  	// ⚠️ 三态各自对应**完全不同的下一步**（去授权 / 什么都不用做 / 去检测），
  	//   所以文案必须分开，不能合成一句「root 不可用」。
  	const rootTone = !root.known ? MP_PRIV_TONE.unknown
  		: root.granted ? MP_PRIV_TONE.on
  			: root.state === "DENIED" ? MP_PRIV_TONE.warn : MP_PRIV_TONE.off;
  	const rootBadge = !root.known ? t("rootUnknown")
  		: root.granted ? t("rootOn")
  			: root.state === "DENIED" ? t("rootDenied") : t("rootAbsent");
  	// ⚠️ `granted` 与 `mounted` 必须分开判：前者是「探测结论」，后者是
  	//   「本次启动真的挂上了 root 工具」。工具走 `--patch`，启动时定死 ——
  	//   用户在设置页点「重新检测」把结论刷成 GRANTED 的那一刻，引擎已经在跑，
  	//   这个会话里**没有** root 工具。此时若还说「已注册」，就是在说假话。
  	const rootNext = !root.known ? t("rootNextUnknown")
  		: root.granted
  			? (root.mounted ? t("rootNextOn") : t("rootNextOnPendingReload"))
  			: root.state === "DENIED" ? t("rootNextDenied") : t("rootNextAbsent");
  	// 检测时间：结论是 24 小时缓存的，用户需要知道这是刚测的还是昨天的。
  	const rootAtText = root.at > 0 ? new Date(root.at).toLocaleString() : t("rootDetailNever");

  	const rootCard = e("div", { style: S.card },
  		e("div", { style: S.cardHead },
  			e("h3", { style: S.cardTitle }, t("rootTitle")),
  			e("span", { style: { ...S.badge, color: rootTone.color, background: rootTone.bg } }, rootBadge),
  		),
  		e("p", { style: S.intro }, t("rootIntro")),
  		e("div", { style: S.next },
  			e("span", { style: S.nextTextWide }, rootNext),
  			e("button", {
  				type: "button",
  				style: S.btnPrimary,
  				disabled: rootBusy,
  				onClick: recheckRoot,
  			}, rootBusy ? t("rootChecking") : t("rootRecheck")),
  		),
  		e("div", { style: S.row },
  			e("span", { style: { ...S.dot, background: rootTone.color } }),
  			e("span", { style: S.rowLabel }, t("rootRowDetail")),
  			// 结论原文由原生给出（`su=/system/bin/su, id -u → 0` 这类），
  			// 直接透出比翻译成一句话更有助于排查。
  			e("span", { style: S.rowValue }, root.known && root.detail ? root.detail : t("rootDetailNever")),
  		),
  		e("div", { style: S.row },
  			e("span", { style: { ...S.dot, background: MP_PRIV_TONE.unknown.color } }),
  			e("span", { style: S.rowLabel }, t("rootRowAt")),
  			e("span", { style: S.rowValue }, rootAtText),
  		),
  		e("p", { style: S.note }, t("rootNote")),
  	);

  	// ══ 标签页布局 ════════════════════════════════════════════════
  	//
  	// ⚠️ **这是第二次重做，与上一版的结构差异必须说清，否则很容易改回去。**
  	//
  	//   上一版把三条通道**全部平铺**在一屏上（Shizuku 卡 + 无线卡 + 电脑引导卡
  	//   + root 卡），理由是「让用户看到还有别的路」。实测的代价是：一屏里
  	//   四五张结构相似的卡纵向排开，每张都带徽章、说明、按钮、折叠步骤，
  	//   用户在 360px 宽的屏上要滑很久才能看完，而且**看完也不知道该点哪张**
  	//   —— 卡片之间的层级全靠徽章文字区分，那太弱了。
  	//
  	//   现在：三条通道各占一个标签，**标签栏本身就是导航** —— 点标签直接切到
  	//   该通道的二级界面，一屏一件事。
  	//
  	// ⚠️ **不要额外加一层「概览列表」。** 中途试过「标签栏 + 三行概览卡」，
  	//   结果是同一组信息（通道名 + 状态）在一屏里说两遍，而且两处的点击
  	//   行为一样、视觉分量却不同，用户会以为它们是两回事。标签栏的圆点
  	//   已经承担了「不点进去就知道哪条通」的职责，够了。
  	//
  	//   两条约束仍然成立（与上一版一致，不能丢）：
  	//     ① 三条路都**看得见** —— 标签栏常驻，不存在「藏起来」
  	//     ② 顺序固定 Shizuku → ADB → root，不按系统版本翻转
  	//     ③ 走不通的路**不给按钮**，但说明必须看得到（见 wlCard）
  	//
  	// 默认落在**第一个标签**（Shizuku）：它是唯一不依赖 adbd、重启也有效的
  	// 通道，对「Android 10 且只有有线 adb」那类设备是唯一可用的 ——
  	// 打开就停在最可能解决问题的那一页，而不是一个什么都没有的总览。
  	const [tab, setTab] = React.useState("shizuku");

  	// 三条通道在标签圆点上的状态色。
  	// ⚠️ 圆点色与卡片徽章色**必须同源**（都用 `MP_PRIV_TONE` 里那几档）——
  	//   分开算迟早会不一致，那种不一致用户一眼就能看到。
  	//
  	// ⚠️ 标签上**不摆徽章文字**。窄屏上三个标签平分 360px，每个只有
  	//   ~110px，「Shizuku」已经占掉大半，再塞「待配置 / 已就绪」会被
  	//   挤成省略号；而状态本身点进去就能看到，圆点已经足够传达「通/不通」。
  	const adbTone = identityOk && channel === "legacy" ? MP_PRIV_TONE.on : MP_PRIV_TONE.off;

  	const tabs = [
  		// ⚠️ **Shizuku 排第一**：它不依赖 adbd、重启也有效，是唯一对
  		//   「Android 10 且只有有线 adb」那类设备真正可用的通道。
  		//   ADB 排在它后面不是因为没用，而是因为它的两条走法都有前提。
  		//
  		// ⚠️ 标签只带 `label` / `tone` / `body` 三样，**不带标题与说明**
  		//   —— 那是卡片自己的事（`szCard`/`wlCard`/`pcCard`/`rootCard` 都自带
  		//   `cardHead` + intro）。中途试过在标签栏下面再加一块「通道标题 +
  		//   说明」的头部，结果是同一句话在屏幕上出现两遍，而且两处都是
  		//   「标题 + 徽章」的形状，用户会以为点错了地方。
  		{
  			id: "shizuku", label: t("tabShizuku"), tone: szTone,
  			body: szCard,
  		},
  		{
  			id: "adb", label: t("tabAdb"), tone: adbTone,
  			// ADB 二级界面 = 无线调试卡（Android 11+）或它的说明卡（Android 10）
  			//  + 电脑引导卡。两张都留着：它们同属「ADB 这条路」，只是走法不同。
  			//
  			// ⚠️ 两张卡之前先铺一段统摄说明。没有它，用户看到两张并列的卡
  			//   会以为是两条互不相干的路 —— 而它们其实共用同一套密钥与 adbd。
  			body: e("div", { style: S.stack },
  				e("p", { style: S.intro }, t("adbIntro")),
  				wlCard,
  				pcCard,
  			),
  		},
  		{
  			id: "root", label: t("tabRoot"), tone: rootTone,
  			body: rootCard,
  		},
  	];
  	// 找不到就退回第一个 —— `tab` 是本地 state，不会有非法值，但
  	// `find` 返回 undefined 时若直接解引用会在渲染期抛错、整页白屏。
  	const active = tabs.find((item) => item.id === tab) || tabs[0];

  	return e("div", { style: S.section },
  		e("h2", { style: S.heading }, t("title")),
  		e("p", { style: S.intro }, t("intro")),

  		// 常驻结论条：无论切到哪个标签都看得见 —— 它是「现在到底能不能用」的
  		// 唯一答案，不该因为切了标签就消失。
  		head,

  		e("div", { style: S.stack },
  			e("div", { style: S.tabBar },
  				...tabs.map((item) => e("button", {
  					key: item.id,
  					type: "button",
  					style: item.id === active.id ? { ...S.tab, ...S.tabActive } : { ...S.tab },
  					onClick: () => setTab(item.id),
  				},
  					e("span", { style: { ...S.tabDot, background: item.tone.color } }),
  					e("span", { style: S.tabLabel }, item.label),
  				)),
  			),
  			e("div", { style: S.panel }, active.body),
  			notesCard,
  		),
  	);
  }

  /**
   * 把「特权权限」分节注册进官方设置页的一级导航。
   *
   * 用 "ctx.inject" 等 "slots" / "locale" 就绪：本插件声明的 "inject" 是空数组
   * （客户端半刻意不声明依赖，避免因某个服务缺失而整个不激活），所以这里按需
   * 开一个子插件来等这两个服务。
   *
   * @param {object} ctx - 浏览器插件上下文。
   */
  function mpRegisterPrivilegedSection(ctx) {
  	if (mpPrivReact === null) {
  		try { mpPrivReact = require("react"); } catch (e) {
  			console.warn("[dsh-android-mobile] require('react') 失败，跳过特权权限分节:", e && e.message);
  			mpPrivReact = null;
  		}
  	}
  	if (!mpPrivReact) return;   // 拿不到 React 就静默跳过，不影响移动端 UI 适配
  	try {
  		ctx.inject(["slots", "locale"], (scope) => {
  			// ⚠️ **必须用 `scope` 上的服务，不能用外层 `ctx`。**
  			//   Cordis 的 `ctx.locale` 要求**当前 fiber 声明过** `locale` 依赖，
  			//   否则抛 `cannot get property "locale" without inject`。
  			//   本插件的 `inject` 是空数组（客户端半刻意不声明依赖），所以外层
  			//   `ctx.locale` 一定不可用；`scope` 是这个 `ctx.inject` 子 fiber，
  			//   它的依赖表里有 `slots`/`locale`，从它取才对。
  			//   （官方 ui-approval 写 `ctx.locale` 是因为它的 inject 里声明了 locale，
  			//    照抄那行在本插件里必炸 —— 这里踩过一次。）
  			const t = scope.locale.bind(MP_PRIV_NS);
  			scope.effect(
  				() => scope.locale.register(MP_PRIV_NS, { zh: MP_PRIV_ZH, en: MP_PRIV_EN }),
  				"dsh-android-mobile: 特权权限字典",
  			);
  			scope.slots.inject("settings.section", () => scope.slots.register({
  				name: "settings.section",
  				// 刻意不叫 "privileged"：官方若将来加同名分节，我们的会把它顶掉。
  				id: "dsh-android-privileged",
  				// 官方 order：account=-10、general=0、models=10、plugins=15、
  				// agent-presets=20。排在所有官方分节之后，不打乱用户熟悉的顺序。
  				order: 25,
  				label: () => t("nav"),
  				locale: MP_PRIV_NS,
  			}, mpPrivSection));
  		});
  	} catch (e) {
  		/* 注册失败只损失这一个分节，绝不能连累移动端 UI 适配。
  		   但**要打日志**：静默吞异常会让「分节没出现」变成无从下手的哑谜。 */
  		console.warn("[dsh-android-mobile] 注册特权权限分节失败:", e && (e.stack || e.message || e));
  	}
  }
  return mpRegisterPrivilegedSection;
}
