import { MP_PRIV_S, MP_PRIV_TONE } from "../settings-styles.js";

export function createLogsSection(require) {
  let mpPrivReact = null;
  const MP_LOGS_NS = "dsh-android-mobile.logs";
  const MP_LOGS_ZH = {
  	nav: "运行日志与诊断",
  	title: "运行日志与诊断",
  	intro: "应用、引擎、网页三方面的日志都会记下来。出问题时点进去看，或一键打包发给开发者 —— 包里带着设备信息、崩溃现场与自动结论。",
  	statusTitle: "当前状态",
  	statusHasError: "有错误",
  	statusHasCrash: "有崩溃现场",
  	statusOk: "正常",
  	statusUnknown: "读取中",
  	rowTotal: "日志条数",
  	rowWarn: "警告",
  	rowError: "错误",
  	rowCrash: "崩溃现场",
  	rowPending: "待上传",
  	rowLastExit: "上次退出",
  	open: "打开日志界面",
  	refresh: "刷新",
  	showDetails: "查看详情",
  	hideDetails: "收起详情",
  	nextHasCrash: "上次留下了崩溃现场，建议点「打开日志界面」导出诊断包发给开发者。",
  	nextHasError: "日志里有错误记录，可以点进去看看。",
  	nextOk: "一切正常。遇到问题随时回来这里。",
  	noBridge: "当前环境没有原生桥（DSHAndroid 不可用），无法打开日志界面。",
  	uploadOn: "已配置日志服务器（可在日志界面一键上传）",
  	uploadOff: "尚未配置日志服务器（只能导出到本地）",
  	howTitle: "怎么用",
  	how1: "点「打开日志界面」—— 那是原生界面，**引擎没起来时也能用**。",
  	how2: "界面里可以按级别（警告/错误）和来源（应用/引擎/网页/崩溃）筛选，也能搜关键字。",
  	how3: "「导出诊断包」生成一个 zip：含设备信息、三份日志、崩溃现场和一份自动结论（diagnosis.json）。",
  	how4: "「上传给开发者」直接发出去；失败会留在本地，下次启动自动重试。",
  	notesTitle: "几点说明",
  	note1: "日志会脱敏：token、api key、密钥一类的内容写盘前就被替换掉了。",
  	note2: "日志不记录会话正文，只记元信息（哪个会话、什么时间、什么动作）。",
  	note3: "启动/崩溃界面上也有一个「查看日志」按钮 —— 应用打不开时从那里进。",
  	note4: "「清空」会删掉已落盘的日志与崩溃现场，不可恢复。",
  };
  const MP_LOGS_EN = {
  	nav: "Logs & diagnostics",
  	title: "Logs & diagnostics",
  	intro: "Logs from the app, the engine and the web UI are all recorded. When something breaks, open the log viewer or bundle everything into one file for the developer — it includes device info, crash scenes and an automatic diagnosis.",
  	statusTitle: "Current status",
  	statusHasError: "Errors present",
  	statusHasCrash: "Crash scene present",
  	statusOk: "OK",
  	statusUnknown: "Reading",
  	rowTotal: "Log entries",
  	rowWarn: "Warnings",
  	rowError: "Errors",
  	rowCrash: "Crash scenes",
  	rowPending: "Pending upload",
  	rowLastExit: "Last exit",
  	open: "Open log viewer",
  	refresh: "Refresh",
  	showDetails: "Show details",
  	hideDetails: "Hide details",
  	nextHasCrash: "A crash scene was recorded. Open the log viewer and export a diagnostic bundle to send to the developer.",
  	nextHasError: "There are errors in the log — open the viewer to inspect them.",
  	nextOk: "All clear. Come back here whenever something misbehaves.",
  	noBridge: "No native bridge (DSHAndroid unavailable) in this environment — cannot open the log viewer.",
  	uploadOn: "Log server configured (one-tap upload in the viewer)",
  	uploadOff: "No log server configured yet (export to a local file instead)",
  	howTitle: "How to use it",
  	how1: "Tap \"Open log viewer\" — that is a native screen and works even when the engine never started.",
  	how2: "Filter by level (warnings/errors) and by source (app/engine/web/crash); search by keyword.",
  	how3: "\"Export bundle\" produces a zip with device info, three logs, crash scenes and an automatic diagnosis (diagnosis.json).",
  	how4: "\"Upload\" sends it straight away; if it fails the bundle stays locally and is retried on next launch.",
  	notesTitle: "Notes",
  	note1: "Logs are redacted: tokens, API keys and secrets are replaced before anything is written to disk.",
  	note2: "Message bodies are not logged — only metadata (which session, when, what action).",
  	note3: "The launch/crash overlay also has a \"View logs\" button, for when the app itself won't open.",
  	note4: "\"Clear\" deletes on-disk logs and crash scenes permanently.",
  };

  /**
   * 「运行日志与诊断」分节组件。
   *
   * 布局沿用「特权权限」「悬浮气泡」那套**结论优先**的结构：
   * head 卡给状态徽章 + 一条「下一步」+ 直达按钮，明细折进 "<details>"。
   *
   * ⚠️ 摘要数据来自 "DSHAndroid.logSummary()"（同步返回 JSON 字符串）。
   *   它刻意只读内存缓冲 + 一个小 JSON，**不读磁盘日志** —— 设置页每次
   *   打开都会调它，读几 MB 的文件算行数会明显卡顿。
   *
   * @param {object} props - 槽位注入面（只用 "t"）。
   * @returns {object} React 元素。
   */
  function mpLogsSection(props) {
  	const React = mpPrivReact;
  	const S = MP_PRIV_S;
  	const t = typeof props.t === "function" ? props.t : (key) => MP_LOGS_ZH[key] ?? key;
  	const [state, setState] = React.useState({ phase: "loading" });
  	const [showDetails, setShowDetails] = React.useState(false);

  	/**
  	 * 从原生读一次摘要。
  	 *
  	 * ⚠️ 桥**可能不存在**（非 App 环境、或桥被清理），先查再调 ——
  	 *   直接调会抛 "TypeError: Cannot read properties of undefined"。
  	 *
  	 * ⚠️ **必须补读一次**（"again=true" 的那趟）：gecko 版的 "logSummary()"
  	 *   读的是**快照缓存**（native messaging 只能异步，见 content.js），
  	 *   第一次拿到的可能是旧值；那边在读取时顺带请求原生重推快照，
  	 *   所以隔一拍再读就是新值。system 版是同步真值，补读无副作用。
  	 */
  	const refreshRef = React.useRef(null);
  	const refreshTimer = React.useRef(0);
  	const refresh = React.useCallback((again) => {
  		const bridge = window.DSHAndroid;
  		if (!bridge || typeof bridge.logSummary !== "function") {
  			setState({ phase: "ready", noBridge: true });
  			return;
  		}
  		try {
  			const raw = bridge.logSummary();
  			const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
  			setState({
  				phase: "ready",
  				noBridge: false,
  				total: o.total | 0,
  				warn: o.warn | 0,
  				error: o.error | 0,
  				crash: o.crash | 0,
  				pending: o.pending | 0,
  				lastExit: typeof o.lastExit === "string" ? o.lastExit : "",
  				uploadConfigured: !!o.uploadConfigured,
  			});
  		} catch (e) {
  			console.warn("[dsh-android-mobile] 读取日志摘要失败:", e && e.message);
  			setState({ phase: "ready", noBridge: true });
  		}
  		// ⚠️ 用 `!== true` 而不是 `!again`：本函数也挂在事件监听上，
  		//   那时第一个参数是 event 对象（truthy），会把补读吃掉。
  		if (again !== true) {
  			window.clearTimeout(refreshTimer.current);
  			refreshTimer.current = window.setTimeout(() => { if (refreshRef.current) refreshRef.current(true); }, 300);
  		}
  	}, []);
  	refreshRef.current = refresh;

  	React.useEffect(() => { refreshRef.current = refresh; refresh(); return () => { refreshRef.current = null; window.clearTimeout(refreshTimer.current); }; }, [refresh]);

  	/**
  	 * 从日志界面回来后刷新。
  	 *
  	 * ⚠️ 用户点「清空」或「上传」是在**另一个 Activity** 里做的，本页拿不到
  	 *   任何回调。只能监听 "visibilitychange" —— 不加的话用户清空完回来
  	 *   看到的还是旧数字，会以为没生效（悬浮气泡分节踩过同一个坑）。
  	 */
  	React.useEffect(() => {
  		const onVisible = () => { if (!document.hidden) refresh(); };
  		document.addEventListener("visibilitychange", onVisible);
  		window.addEventListener("focus", onVisible);
  		return () => {
  			document.removeEventListener("visibilitychange", onVisible);
  			window.removeEventListener("focus", onVisible);
  		};
  	}, [refresh]);

  	const open = () => {
  		const bridge = window.DSHAndroid;
  		if (bridge && typeof bridge.openLogs === "function") {
  			try { bridge.openLogs(); } catch (e) {
  				console.warn("[dsh-android-mobile] 打开日志界面失败:", e && e.message);
  			}
  		}
  	};

  	if (state.phase === "loading") {
  		return React.createElement("div", { style: S.section },
  			React.createElement("h2", { style: S.heading }, t("title")),
  			React.createElement("p", { style: S.intro }, t("statusUnknown")),
  		);
  	}

  	const noBridge = state.noBridge;
  	const crash = state.crash || 0;
  	const error = state.error || 0;
  	// 严重度按「有没有崩溃现场」> 「有没有错误」排 —— 崩溃现场是硬证据，
  	// 错误可能只是第三方路由的一次 429。
  	const tone = crash > 0 ? MP_PRIV_TONE.off : (error > 0 ? MP_PRIV_TONE.warn : MP_PRIV_TONE.on);
  	const badgeText = crash > 0 ? t("statusHasCrash") : (error > 0 ? t("statusHasError") : t("statusOk"));

  	let nextText = t("nextOk");
  	if (noBridge) nextText = t("noBridge");
  	else if (crash > 0) nextText = t("nextHasCrash");
  	else if (error > 0) nextText = t("nextHasError");

  	const dot = (ok) => React.createElement("span", {
  		style: { ...S.dot, background: ok ? MP_PRIV_TONE.on.color : MP_PRIV_TONE.off.color },
  	});
  	const row = (label, ok, value) => React.createElement("div", { style: S.row, key: label },
  		dot(ok),
  		React.createElement("span", { style: S.rowLabel }, label),
  		React.createElement("span", { style: S.rowValue }, value),
  	);

  	return React.createElement("div", { style: S.section },
  		React.createElement("h2", { style: S.heading }, t("title")),
  		React.createElement("p", { style: S.intro }, t("intro")),

  		React.createElement("div", { style: S.card },
  			React.createElement("div", { style: S.cardHead },
  				React.createElement("h3", { style: S.cardTitle }, t("statusTitle")),
  				React.createElement("span", {
  					style: { ...S.badge, color: tone.color, background: tone.bg },
  				}, badgeText),
  			),
  			React.createElement("div", { style: S.next },
  				React.createElement("span", { style: S.nextText }, nextText),
  				!noBridge ? React.createElement("div", { style: S.actions },
  					React.createElement("button", { style: S.btnPrimary, onClick: open }, t("open")),
  				) : null,
  			),
  			React.createElement("div", { style: S.actions },
  				React.createElement("button", { style: S.btn, onClick: refresh }, t("refresh")),
  				React.createElement("button", {
  					style: S.btnQuiet,
  					onClick: () => setShowDetails((v) => !v),
  				}, showDetails ? t("hideDetails") : t("showDetails")),
  			),
  			showDetails ? React.createElement("div", { style: S.details },
  				row(t("rowTotal"), (state.total || 0) > 0, String(state.total || 0)),
  				row(t("rowWarn"), (state.warn || 0) === 0, String(state.warn || 0)),
  				row(t("rowError"), (state.error || 0) === 0, String(state.error || 0)),
  				row(t("rowCrash"), crash === 0, String(crash)),
  				row(t("rowPending"), (state.pending || 0) === 0, String(state.pending || 0)),
  				row(t("rowLastExit"), true, state.lastExit || "—"),
  				row(t("rowTotal"), state.uploadConfigured, state.uploadConfigured ? t("uploadOn") : t("uploadOff")),
  			) : null,
  		),

  		React.createElement("details", { style: S.fold },
  			React.createElement("summary", { style: S.foldHead }, t("howTitle")),
  			React.createElement("div", { style: S.foldBody },
  				React.createElement("ul", { style: S.notes },
  					[t("how1"), t("how2"), t("how3"), t("how4")].map((line, i) =>
  						React.createElement("li", { style: S.note, key: i }, line)),
  				),
  			),
  		),

  		React.createElement("details", { style: S.fold },
  			React.createElement("summary", { style: S.foldHead }, t("notesTitle")),
  			React.createElement("div", { style: S.foldBody },
  				React.createElement("ul", { style: S.notes },
  					[t("note1"), t("note2"), t("note3"), t("note4")].map((line, i) =>
  						React.createElement("li", { style: S.note, key: i }, line)),
  				),
  			),
  		),
  	);
  }

  /**
   * 把「运行日志与诊断」注册进设置页一级导航。
   *
   * 与特权权限/悬浮气泡同一套接缝（"settings.section"），不需要碰导航 DOM。
   *
   * ⚠️ "order" 取 28：排在「悬浮气泡」（26）与「交流群」（27）之后，
   *   也就是设置列表最末。日志是**排障入口**，不该占据日常设置的显眼位置，
   *   但必须存在。
   *
   * @param {object} ctx - 浏览器插件上下文。
   */
  function mpRegisterLogsSection(ctx) {
  	if (mpPrivReact === null) {
  		try { mpPrivReact = require("react"); } catch (e) {
  			console.warn("[dsh-android-mobile] require('react') 失败，跳过日志分节:", e && e.message);
  			mpPrivReact = null;
  		}
  	}
  	if (!mpPrivReact) return;
  	try {
  		ctx.inject(["slots", "locale"], (scope) => {
  			// ⚠️ 必须用 `scope` 上的服务（原因见 mpRegisterPrivilegedSection 的注释）。
  			const t = scope.locale.bind(MP_LOGS_NS);
  			scope.effect(
  				() => scope.locale.register(MP_LOGS_NS, { zh: MP_LOGS_ZH, en: MP_LOGS_EN }),
  				"dsh-android-mobile: 日志字典",
  			);
  			scope.slots.inject("settings.section", () => scope.slots.register({
  				name: "settings.section",
  				id: "dsh-android-logs",
  				order: 28,
  				label: () => t("nav"),
  				locale: MP_LOGS_NS,
  			}, mpLogsSection));
  		});
  	} catch (e) {
  		console.warn("[dsh-android-mobile] 注册日志分节失败:", e && (e.stack || e.message || e));
  	}
  }
  return mpRegisterLogsSection;
}
