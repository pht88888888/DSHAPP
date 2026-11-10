window.__ModuleLoader__.load({
	id: "dsh-android-market",
	factory: (require) => {
		"use strict";
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

		/**
		 * dsh-android-market —— 客户端半（浏览器侧）。
		 *
		 * 在设置页注册一个「插件市场」分节：从宿主半的 `/api/dsh-android/market`
		 * 拿目录，渲染成可搜索、可一键安装的列表。
		 *
		 * ## 为什么安装要借官方 `pluginManager` 的 Remote 服务
		 *
		 * 本文件**不自己 spawn pnpm**。官方 `@deepseek-ai/dsh-plugin-manager` 已经
		 * 提供了完整的安装通道：registry 回退、构建脚本审批、版本兼容性检查、
		 * 失败时恢复 `package.json` 与 `pnpm-lock.yaml`。自己再写一遍只会重复踩
		 * AGENTS.md ⑥ 里那些坑（pnpm 驱逐「多余依赖」、`--allow-build` 泄漏包名）。
		 *
		 * 安装固定精确版本；已安装插件通过官方 `updateBundle` 更新并保留启用状态。
		 *
		 * ## ⚠️ 取服务必须用 `scope` 上的，不能用外层 `ctx`
		 *
		 * Cordis 的 `ctx.remote` 要求**当前 fiber 声明过**该依赖，否则抛
		 * `cannot get property "remote" without inject`。本插件的 `exports.inject`
		 * 是空数组（客户端半刻意不声明依赖），所以外层 `ctx` 上取不到；
		 * `ctx.inject([...])` 的子 fiber `scope` 才是能取的那个。
		 * 这一点在移动端 UI 插件里踩过（见其 `lib/client.js` 的注释）。
		 */

		/** 目录接口。与宿主半 `lib/index.js` 的 ROUTE 常量必须一致。 */
		const MARKET_API = "/api/dsh-android/market";

		/** 本插件的 locale 命名空间。 */
		const MARKET_NS = "dsh-android-market";

		/**
		 * 分节在设置页里的排序位。
		 *
		 * 官方 order：account=-10、general=0、models=10、plugins=15、agent-presets=20；
		 * 本 App 的「特权权限」是 25。排在它之后，不打乱用户熟悉的顺序。
		 */
		const MARKET_ORDER = 26;

		const MARKET_ZH = {
			nav: "插件市场",
			intro:
				"从 npm 收录的 DSH 插件。精选区人工审核，社区区自动收录。",
			search: "搜索插件名或说明",
			refresh: "刷新",
			refreshing: "扫描中…",
			tabAll: "全部",
			tabFeatured: "精选",
			tabCommunity: "社区",
			tabInstalled: "已安装",
			loading: "正在扫描 npm…",
			empty: "没有匹配的插件",
			emptyCommunity:
				"社区区还是空的 —— 任何插件包在 package.json 里加上 dshapp-plugin 关键词后发布，就会出现在这里。",
			loadingHint: "首次扫描要拉几百个包的元数据，大约 5~15 秒。",
			featured: "精选",
			installed: "已安装",
			notInstallable: "不可安装",
			install: "安装",
			installing: "安装中…",
			installed2: "已安装",
			allowAndRetry: "允许脚本并重试",
			uninstall: "卸载",
			removing: "卸载中…",
			confirmRemove: "确定卸载这个插件？",
			riskTitle: "兼容性提示",
			seedWarn: "网络不可达，这是随 App 打包的离线目录，可能不是最新的。",
			liveWarn: "实时扫描失败，已回落到离线目录：",
			truncated: "目录过大，可能被截断。",
			by: "作者",
			updated: "更新于",
			home: "主页",
			repo: "仓库",
			errorPrefix: "安装失败：",
			removeErrorPrefix: "卸载失败：",
			okApplied: "服务端已应用，页面插件由热更新同步",
			okRestart: "版本已保存，需要重启引擎后生效",
			okOverridden: "版本已保存，但被更高优先级的配置覆盖",
			update: "更新",
			updating: "更新中…",
			checking: "检查更新中…",
			checkUpdates: "检查更新",
			currentVersion: "当前 v{version}",
			updateTarget: "更新至 v{version}",
			versionMismatch: "目标 v{target}，实际 v{actual}；请重新检查安装状态",
			installedUnavailable: "无法读取已安装状态，暂不能安装或更新，请重试",
			managerUnavailable: "插件管理器不可用",
			cancelled: "操作已取消",
			restored: "旧版恢复结果：",
			updateCheckFailed: "检查更新失败：",
			localSource: "本地或内置版本",
			operationFailed: "操作失败：",
			restartReason: "原因：",
			warnings: "提示：",
			approvedRetry: "允许脚本并安装选定版本",
			verifyFailed: "版本已保存，但读取安装状态失败，请重试核对",
			clientSynced: "页面插件已热更新",
			clientPending: "页面插件尚未确认同步，可刷新页面后检查",
			pageReload: "当前页面需要刷新后加载新版",
			clientFailed: "页面插件加载失败：",
			loadFailed: "读取市场目录失败：",
			retry: "重试",
			detail: "详情",
			hideDetail: "收起",
			shown: "显示 {n} 个",
			// ── 官方 pluginManager 的拒绝码 → 人话 ──────────────────────
			// ⚠️ 官方 `managementError()` 返回的是 `{code, diagnostic}`，
			//   **没有 `message` 字段**，所以这里必须按 code 映射，
			//   再不行才退回 diagnostic / JSON。见 index.js:1108。
			errCode: {
				"invalid-spec": "包名不合法",
				"unknown-plugin": "官方清单里没有这个插件",
				"not-bundle": "这个包不是 DSH 插件（缺 dsh.bundle.patch）",
				"ambiguous-install": "装完之后无法确定是哪一个包",
				"incompatible-version": "与当前引擎版本不兼容",
				"stale-approval": "脚本授权已过期，请重新点安装",
				"management-required": "该插件是插件管理器自身的一部分，不能在这里改动",
				"not-removable": "这个插件不可卸载",
				"stop-profile": "该插件正在被当前 profile 使用，需先停掉",
				"bundle-in-use": "该插件正在运行中，无法卸载",
				unaddressable: "这个条目不能在这里管理",
				"operation-error": "操作失败",
			},
			errRaw: "（原始信息：" ,
			errIncompat: "不兼容：",
			// packageResult.kind —— pnpm 失败分类，比 code 更具体。
			errKind: {
				network: "网络不可达（npm 源连不上，检查代理/网络）",
				timeout: "下载超时",
				integrity: "包完整性校验失败",
				"pnpm-missing": "设备上没有 pnpm（插件管理器不可用）",
				"not-found": "npm 上没有这个包",
				"no-matching-version": "没有匹配的版本",
				"disk-full": "设备存储空间不足",
				permission: "文件权限被拒",
				"build-blocked": "依赖脚本被拦下，需要你批准",
				unknown: "未知错误",
			},
			errPackage: "pnpm：",
		};

		const MARKET_EN = {
			nav: "Marketplace",
			intro:
				"DSH plugins collected from npm. Featured ones are reviewed by hand.",
			search: "Search name or description",
			refresh: "Refresh",
			refreshing: "Scanning…",
			tabAll: "All",
			tabFeatured: "Featured",
			tabCommunity: "Community",
			tabInstalled: "Installed",
			loading: "Scanning npm…",
			empty: "No matching plugin",
			emptyCommunity:
				"The community section is still empty — publish a plugin with the dshapp-plugin keyword and it shows up here.",
			loadingHint: "The first scan reads a few hundred package manifests; about 5-15 seconds.",
			featured: "Featured",
			installed: "Installed",
			notInstallable: "Not installable",
			install: "Install",
			installing: "Installing…",
			installed2: "Installed",
			allowAndRetry: "Allow scripts and retry",
			uninstall: "Remove",
			removing: "Removing…",
			confirmRemove: "Remove this plugin?",
			riskTitle: "Compatibility notes",
			seedWarn: "The network is unreachable; this catalog ships with the app and may be stale.",
			liveWarn: "The live scan failed, so the offline catalog is shown:",
			truncated: "The catalog is large and may be truncated.",
			by: "by",
			updated: "updated",
			home: "Homepage",
			repo: "Repository",
			errorPrefix: "Install failed: ",
			removeErrorPrefix: "Remove failed: ",
			okApplied: "Applied on the host; client plugins sync through hot updates",
			okRestart: "Version saved; restart the engine to apply",
			okOverridden: "Version saved, but overridden by higher-priority configuration",
			update: "Update",
			updating: "Updating…",
			checking: "Checking updates…",
			checkUpdates: "Check updates",
			currentVersion: "Current v{version}",
			updateTarget: "Update to v{version}",
			versionMismatch: "Requested v{target}, installed v{actual}; check installation again",
			installedUnavailable: "Could not read installed bundles; retry before installing or updating",
			managerUnavailable: "Plugin manager unavailable",
			cancelled: "Operation cancelled",
			restored: "Previous version restore result: ",
			updateCheckFailed: "Update check failed: ",
			localSource: "Local or built-in version",
			operationFailed: "Operation failed: ",
			restartReason: "Reason: ",
			warnings: "Notes: ",
			approvedRetry: "Allow scripts and install selected version",
			verifyFailed: "Version saved, but installation could not be read; retry to verify",
			clientSynced: "Client plugin hot update confirmed",
			clientPending: "Client sync is not yet confirmed; refresh the page to check",
			pageReload: "Refresh this page to load the new version",
			clientFailed: "Client plugin failed: ",
			errCode: {
				"invalid-spec": "Invalid package spec",
				"unknown-plugin": "Not in the official plugin inventory",
				"not-bundle": "Not a DSH plugin (missing dsh.bundle.patch)",
				"ambiguous-install": "Could not tell which package was installed",
				"incompatible-version": "Incompatible with the current engine version",
				"stale-approval": "The script approval expired; press install again",
				"management-required": "Part of the plugin manager itself; cannot be changed here",
				"not-removable": "This plugin cannot be removed",
				"stop-profile": "In use by the current profile; stop it first",
				"bundle-in-use": "This plugin is running and cannot be removed",
				unaddressable: "This entry cannot be managed here",
				"operation-error": "Operation failed",
			},
			errRaw: " (raw: ",
			errIncompat: "Incompatible: ",
			loadFailed: "Could not read the marketplace catalog: ",
			retry: "Retry",
			detail: "Details",
			hideDetail: "Collapse",
			shown: "{n} shown",
		};

		const MARKET_S = {
			section: {
				maxWidth: "760px",
				display: "flex",
				flexDirection: "column",
				// 10px 而不是 12px：分节里有 5 个纵向块，每处省 2px 就是一屏多出 10px。
				gap: "10px",
				color: "var(--dsw-alias-label-primary, #222)",
			},
			intro: {
				margin: 0,
				fontSize: "12px",
				lineHeight: "17px",
				color: "var(--dsw-alias-label-tertiary, #888)",
			},
			toolbar: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
			search: {
				flex: "1 1 180px",
				minWidth: 0,
				padding: "7px 10px",
				borderRadius: "8px",
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				background: "transparent",
				color: "inherit",
				font: "inherit",
				fontSize: "13px",
			},
			btn: {
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				background: "transparent",
				color: "inherit",
				font: "inherit",
				fontSize: "13px",
				padding: "6px 14px",
				borderRadius: "8px",
				cursor: "pointer",
			},
			btnPrimary: {
				border: "0.5px solid transparent",
				background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
				color: "#fff",
				font: "inherit",
				fontSize: "13px",
				fontWeight: 600,
				padding: "6px 14px",
				borderRadius: "8px",
				cursor: "pointer",
			},
			btnQuiet: {
				border: "0.5px solid transparent",
				background: "transparent",
				color: "var(--dsw-alias-label-tertiary, #888)",
				font: "inherit",
				fontSize: "13px",
				padding: "6px 10px",
				borderRadius: "8px",
				cursor: "pointer",
			},
			// 分段控件：用一排小胶囊做分区切换，比下拉框少一次点击。
			tabs: { display: "flex", gap: "6px", flexWrap: "wrap" },
			tab: {
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				background: "transparent",
				color: "inherit",
				font: "inherit",
				fontSize: "12px",
				padding: "4px 11px",
				borderRadius: "999px",
				cursor: "pointer",
				whiteSpace: "nowrap",
			},
			tabOn: {
				border: "0.5px solid transparent",
				background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)",
				color: "inherit",
				font: "inherit",
				fontSize: "12px",
				fontWeight: 600,
				padding: "4px 11px",
				borderRadius: "999px",
				cursor: "pointer",
				whiteSpace: "nowrap",
			},
			meta: {
				margin: 0,
				fontSize: "12px",
				lineHeight: "18px",
				color: "var(--dsw-alias-label-tertiary, #888)",
			},
			// 列表容器：卡片间距压到 8px。移动端一屏能多放半张卡，比"透气"更值钱。
			list: { display: "flex", flexDirection: "column", gap: "8px" },
			card: {
				border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)",
				borderRadius: "10px",
				padding: "10px 12px",
				display: "flex",
				flexDirection: "column",
				gap: "6px",
			},
			// ⚠️ `nowrap` + 包名 `minWidth:0` 是这个布局的关键：
			//   360px 宽的卡片里，包名（等宽字体，`@jiuling/dsh-mcp-manager` 有 25 字符）
			//   与版本号必须**同处一行**，靠省略号截断包名，而不是让版本掉到第二行 ——
			//   掉行会让标题行从 22px 变 43px，每张卡凭空多 20px。
			cardHead: { display: "flex", alignItems: "baseline", gap: "6px", flexWrap: "nowrap" },
			// 包名用等宽：这是要照着核对/搜索的标识符，不是自然语言。
			pkgName: {
				margin: 0,
				flex: "1 1 auto",
				minWidth: 0,
				fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
				fontSize: "13px",
				fontWeight: 600,
				whiteSpace: "nowrap",
				overflow: "hidden",
				textOverflow: "ellipsis",
			},
			pkgVer: {
				flex: "0 0 auto",
				fontSize: "12px",
				color: "var(--dsw-alias-label-tertiary, #888)",
				fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
			},
			badge: {
				flex: "0 0 auto",
				fontSize: "11px",
				fontWeight: 600,
				padding: "2px 8px",
				borderRadius: "999px",
				whiteSpace: "nowrap",
			},
			// 描述默认**两行截断**（`-webkit-line-clamp`）。
			//
			// ⚠️ 这是本次布局优化里省得最多的一条：原来描述全展开，实测 80px（4 行），
			//   而卡片总共才 194px —— 光描述就吃掉四成高度。截断到两行后省 40px，
			//   且用户点「详情」就能看全文，信息一点没丢。
			desc: {
				margin: 0,
				fontSize: "12px",
				lineHeight: "17px",
				wordBreak: "break-word",
				display: "-webkit-box",
				WebkitBoxOrient: "vertical",
				WebkitLineClamp: "2",
				overflow: "hidden",
			},
			descFull: { margin: 0, fontSize: "12px", lineHeight: "17px", wordBreak: "break-word" },
			riskBox: {
				display: "flex",
				flexDirection: "column",
				gap: "3px",
				padding: "7px 9px",
				borderRadius: "8px",
				background: "rgba(232,89,12,0.08)",
			},
			riskHead: { fontSize: "12px", fontWeight: 600, color: "#e8590c" },
			riskItem: { margin: 0, fontSize: "11px", lineHeight: "16px", color: "var(--dsw-alias-label-secondary, #555)" },
			// 操作行：徽章靠左（`marginRight:auto` 把它和右侧按钮推开），按钮靠右。
			// 这样徽章从标题行挪下来，标题行才能保持单行。
			actions: { display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" },
			actionsLead: { marginRight: "auto", display: "flex", alignItems: "center", gap: "6px" },
			// 小号按钮：卡片里的动作不该和设置页的主按钮一样大。
			btnSm: {
				border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
				background: "transparent",
				color: "inherit",
				font: "inherit",
				fontSize: "12px",
				padding: "5px 12px",
				borderRadius: "7px",
				cursor: "pointer",
				whiteSpace: "nowrap",
			},
			btnSmPrimary: {
				border: "0.5px solid transparent",
				background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
				color: "#fff",
				font: "inherit",
				fontSize: "12px",
				fontWeight: 600,
				padding: "5px 12px",
				borderRadius: "7px",
				cursor: "pointer",
				whiteSpace: "nowrap",
			},
			links: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" },
			link: { fontSize: "12px", color: "var(--dsw-alias-label-link, #2b6cb0)", textDecoration: "none" },
			notice: {
				margin: 0,
				fontSize: "12px",
				lineHeight: "18px",
				padding: "8px 10px",
				borderRadius: "8px",
				background: "rgba(240,140,0,0.10)",
				color: "#b45309",
			},
			error: { margin: 0, fontSize: "12px", lineHeight: "18px", color: "#c92a2a", wordBreak: "break-word" },
			ok: { margin: 0, fontSize: "12px", lineHeight: "18px", color: "#2f9e44" },
			// 加载态用一个轻量的骨架行，不用转圈：真机上转圈在长任务里更显焦虑。
			skeleton: {
				height: "58px",
				borderRadius: "10px",
				border: "0.5px solid var(--dsw-alias-border-l2, #eee)",
				background: "var(--dsw-alias-interactive-bg-hover, #f7f7f7)",
			},
			empty: {
				margin: 0,
				fontSize: "13px",
				lineHeight: "20px",
				color: "var(--dsw-alias-label-tertiary, #888)",
				padding: "16px 0",
			},
		};

		const MARKET_TONE = {
			// 精选用琥珀：比绿色少一分"成功"感，多一分"被挑选过"的意味，
			// 也不会和「已安装」的绿色撞在一起。
			featured: { color: "#b45309", bg: "rgba(180,83,9,0.12)" },
			installed: { color: "#2f9e44", bg: "rgba(47,158,68,0.12)" },
			broken: { color: "#c92a2a", bg: "rgba(201,42,42,0.12)" },
			neutral: { color: "#868e96", bg: "rgba(134,142,150,0.12)" },
		};

		/** 缓存的 React 引用。`require` 可能失败，那时静默跳过整个分节。 */
		let marketReact = null;

		/**
		 * 把 `{f}` / `{c}` 这类占位符替换成实际值。
		 *
		 * 刻意不做完整的 i18n 插值：本分节只有两条带占位的文案，写一个 20 行的
		 * 通用格式化器不划算。
		 */
		function marketFill(text, values) {
			return String(text).replace(/\{(\w+)\}/g, (m, key) =>
				Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : m,
			);
		}

		/** 把 ISO 时间截成 `YYYY-MM-DD`；解析不了就返回 null（界面直接不显示这一行）。 */
		function marketDay(iso) {
			if (!iso) return null;
			const d = new Date(iso);
			if (Number.isNaN(d.getTime())) return null;
			const p = (n) => String(n).padStart(2, "0");
			return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
		}

		/** 小胶囊徽章。 */
		function marketBadge(React, tone, text) {
			return React.createElement(
				"span",
				{ style: { ...MARKET_S.badge, color: tone.color, background: tone.bg } },
				text,
			);
		}

		/**
		 * 把官方 `pluginManager` 的失败结果翻译成一句人能看懂的话。
		 *
		 * ⚠️ **这里踩过一次坑，别改回去**：官方 `managementError()`
		 * （`@deepseek-ai/dsh-plugin-manager/lib/index.js:1108`）返回的是
		 *
		 *   ManagementFailure → `{ code, incompatible? }`
		 *   其它异常          → `{ code: "operation-error", diagnostic }`
		 *
		 * **两种都没有 `message` 字段**。早先这里读 `error.message`，于是永远
		 * 取到 undefined，一律显示兜底文案「安装失败」——界面上就是
		 * 「安装失败：安装失败」，真实原因（比如 `not-bundle`）被整个吞掉，
		 * 排查时完全无从下手。
		 *
		 * 取值优先级：`packageResult.kind`（pnpm 失败分类，最具体）→ `code` 的人话
		 * → `diagnostic`（pnpm 原始输出）→ `incompatible` 明细 → 最后才 JSON 兜底。
		 *
		 * @param {object} error - 官方返回的 `error` 对象（`{code, diagnostic?, incompatible?}`）。
		 * @param {object} t - 文案表。
		 * @param {object} [change] - 官方返回的 `ChangeResult`，用来取 `packageResult.kind`。
		 * @returns {string} 可读文案（永不为空）。
		 */
		function marketErrorText(error, t, change) {
			const kinds = t("errKind") || {};
			const kind = change && change.packageResult && change.packageResult.kind;
			const kindText = kind ? kinds[kind] || kind : "";
			if (!error) return kindText || t("errCode")["operation-error"];
			const code = typeof error.code === "string" ? error.code : "";
			const named = (t("errCode") && t("errCode")[code]) || "";
			// diagnostic 是 pnpm 的原样输出，信息量最大，优先跟在后头。
			const detail = error.diagnostic
				? String(error.diagnostic).trim()
				: error.incompatible && error.incompatible.length
					? t("errIncompat") +
						error.incompatible
							.map((i) => [i.name, i.version, i.runtimeVersion].filter(Boolean).join(" "))
							.join(", ")
					: "";
			// kind 比 code 更能说明原因（code 往往是笼统的 operation-error）。
			const head = kindText || named;
			if (head && detail) return head + t("errRaw") + detail + ")";
			if (head) return head;
			if (detail) return detail;
			if (code) return code;
			try {
				return JSON.stringify(error);
			} catch (_e) {
				return String(error);
			}
		}

		/**
		 * 「插件市场」分节组件。
		 *
		 * @param {object} props - 槽位注入面（只用 `t`）。
		 * @param {object} scope - `ctx.inject` 的子 fiber，用来取 `remote` 服务。
		 * @returns {object} React 元素。
		 */
		function marketSection(props, scope) {
			const React = marketReact;
			const S = MARKET_S;
			const t = typeof props.t === "function" ? props.t : (key) => MARKET_ZH[key] ?? key;

			const [catalog, setCatalog] = React.useState({ phase: "loading" });
			const [installed, setInstalled] = React.useState({ bundles: new Map(), phase: "idle" });
			const [updates, setUpdates] = React.useState({});
			const busy = React.useRef(new Set());
			const mounted = React.useRef(true);
			React.useEffect(() => {
				mounted.current = true;
				return () => { mounted.current = false; };
			}, []);
			const [query, setQuery] = React.useState("");
			const [tab, setTab] = React.useState("all");
			/** 每个包名 → 它的操作状态（安装中/失败/成功/待批脚本）。 */
			const [ops, setOps] = React.useState({});
			const [expanded, setExpanded] = React.useState({});
			const [, setClientRevision] = React.useState(0);
			React.useEffect(() => {
				const modules = scope.get("modules");
				if (!modules || !modules.entries) return;
				const notify = () => setClientRevision((value) => value + 1);
				const offState = modules.entries.state.subscribe(notify);
				const offRelease = modules.entries.releaseChange.subscribe(notify);
				return () => { offState(); offRelease(); };
			}, [scope]);
			const clientRevision = (name) => {
				const modules = scope.get("modules");
				const row = modules && modules.manifest.modules.find((entry) => entry.id === name);
				return row && row.rev;
			};

			/**
			 * 读一次市场目录。
			 *
			 * @param {boolean} force - true 时带 `?refresh=1`，让宿主半跳过缓存重扫。
			 */
			const loadCatalog = React.useCallback((force) => {
				let alive = true;
				setCatalog((prev) => ({ phase: prev.data ? "ready" : "loading", data: prev.data, busy: true }));
				const url = force ? MARKET_API + "?refresh=1" : MARKET_API;
				window
					.fetch(url, { cache: "no-store" })
					.then((res) => {
						if (!res.ok) throw new Error("HTTP " + res.status);
						return res.json();
					})
					.then((data) => {
						if (alive) setCatalog({ phase: "ready", data, busy: false });
					})
					.catch((error) => {
						if (alive) {
							setCatalog({
								phase: "error",
								message: String((error && error.message) || error),
								busy: false,
							});
						}
					});
				return () => {
					alive = false;
				};
			}, []);

			/**
			 * 读一次「已装插件」清单。
			 *
			 * 走官方 `pluginManager.listBundles()`：这是**唯一**能反映真实安装状态的
			 * 来源（市场目录只说明 npm 上有什么）。取不到时保留上次清单，
			 * 暂停安装与更新，避免把已安装插件误当成未安装。
			 */
			const loadInstalled = React.useCallback(() => {
				const manager = scope && scope.remote && scope.remote.pluginManager;
				if (!manager || typeof manager.listBundles !== "function") {
					setInstalled((prev) => ({ ...prev, phase: "unavailable" }));
					return Promise.resolve();
				}
				return manager
					.listBundles()
					.then((result) => {
						if (!result || !result.ok) {
							setInstalled((prev) => ({ ...prev, phase: "unavailable" }));
							return;
						}
						const bundles = new Map();
						for (const b of result.value ?? []) {
							if (b && b.installed && b.name) bundles.set(b.name, b);
						}
						if (mounted.current) setInstalled({ bundles, phase: "ready" });
						return bundles;
					})
					.catch(() => {
						if (mounted.current) setInstalled((prev) => ({ ...prev, phase: "unavailable" }));
					});
			}, [scope]);

			const checkUpdates = React.useCallback(async (bundles) => {
				const manager = scope && scope.remote && scope.remote.pluginManager;
				if (!manager || typeof manager.listVersions !== "function" || !bundles) return;
				const queue = [...bundles.values()].filter((bundle) => bundle.updatable);
				let cursor = 0;
				const worker = async () => {
					while (cursor < queue.length && mounted.current) {
						const bundle = queue[cursor++];
						setUpdates((prev) => ({ ...prev, [bundle.name]: { phase: "checking", installed: bundle.version } }));
						try {
							const reply = await manager.listVersions(bundle.name);
							if (!reply || !reply.ok) throw new Error(marketErrorText(reply && reply.error, t));
							const value = reply.value;
							if (!value || value.status !== "listed") throw new Error(value && value.reason || "Invalid version list");
							const target = value.versions.find((version) => version.bundle && !version.incompatible && !version.deprecated && !version.prerelease);
							if (mounted.current) setUpdates((prev) => ({ ...prev, [bundle.name]: { phase: "ready", installed: value.installed, target: target && target.version, registry: value.registry } }));
						} catch (error) {
							if (mounted.current) setUpdates((prev) => ({ ...prev, [bundle.name]: { phase: "error", message: String(error.message || error) } }));
						}
					}
				};
				await Promise.all([worker(), worker(), worker()]);
			}, [scope]);

			React.useEffect(() => loadCatalog(false), [loadCatalog]);
			React.useEffect(() => { loadInstalled().then(checkUpdates); }, [loadInstalled, checkUpdates]);

			/**
			 * 装一个插件。
			 *
			 * `approvedBuilds` 非空时表示「用户点了允许脚本并重试」——
			 * 官方服务会在 pnpm 运行前把这些包的脚本权限记进当前 profile。
			 */
			const install = React.useCallback(
				(name, targetVersion, approvedBuilds, enabled = true, registry) => {
					const manager = scope && scope.remote && scope.remote.pluginManager;
					if (!manager || typeof manager.installBundle !== "function") {
						setOps((prev) => ({ ...prev, [name]: { phase: "error", message: "插件管理器不可用" } }));
						return;
					}
					if (busy.current.has(name) || !targetVersion || installed.phase !== "ready") return;
					busy.current.add(name);
					const previousRevision = clientRevision(name);
					setOps((prev) => ({ ...prev, [name]: { phase: "installing", targetVersion, previousRevision } }));
					const options = { enabled, saveExact: true };
					if (registry !== undefined) options.registry = registry;
					if (approvedBuilds && approvedBuilds.length > 0) options.approvedBuilds = approvedBuilds;
					manager
						.installBundle(name + "@" + targetVersion, options)
						.then((result) => {
							if (!result || !result.ok) {
								setOps((prev) => ({
									...prev,
									[name]: {
										phase: "error",
										message: marketErrorText(
											result && result.error,
											t,
											result && result.value,
										),
									},
								}));
								return;
							}
							const value = result.value ?? {};
							// `pendingBuilds` 非空 = pnpm 拦下了依赖脚本，要用户明确批准。
							// 这不是失败，是一个需要用户决定的中间态。
							if (value.pendingBuilds && value.pendingBuilds.length > 0) {
								setOps((prev) => ({
									...prev,
									[name]: { phase: "pending", builds: value.pendingBuilds, targetVersion, enabled, registry },
								}));
								return;
							}
							if (value.application === "failed" || value.application === "cancelled" || value.error) {
								setOps((prev) => ({
									...prev,
									[name]: { phase: "error", message: marketErrorText(value.error, t, value) },
								}));
								return;
							}
							return loadInstalled().then((bundles) => {
								const actual = bundles && bundles.get(name);
								if (!actual || actual.version !== targetVersion || value.version && value.version !== targetVersion) {
									setOps((prev) => ({ ...prev, [name]: { phase: "error", message: actual ? marketFill(t("versionMismatch"), { target: targetVersion, actual: value.version && value.version !== targetVersion ? value.version : actual.version || "?" }) : t("verifyFailed") } }));
									return;
								}
								setOps((prev) => ({ ...prev, [name]: { phase: "done", application: value.application, restartReason: value.restartReason, warnings: value.warnings, previousRevision } }));
							});
						})
						.catch((error) => {
							setOps((prev) => ({
								...prev,
								[name]: { phase: "error", message: String((error && error.message) || error) },
							}));
						})
						.finally(() => { busy.current.delete(name); });
				},
				[scope, loadInstalled, installed.phase],
			);

			const update = React.useCallback(async (name, target) => {
				const manager = scope && scope.remote && scope.remote.pluginManager;
				const bundle = installed.bundles.get(name);
				if (!manager || !bundle || !bundle.updatable || !target || !target.target || busy.current.has(name)) return;
				busy.current.add(name);
				const previousRevision = clientRevision(name);
				setOps((prev) => ({ ...prev, [name]: { phase: "updating", targetVersion: target.target, previousRevision } }));
				try {
					const reply = await manager.updateBundle(name, target.target, { registry: target.registry });
					if (!reply || !reply.ok) throw new Error(marketErrorText(reply && reply.error, t));
					const outcome = reply.value || {};
					const value = outcome.result || {};
					const restore = outcome.restore;
					const bundles = await loadInstalled();
					if (value.pendingBuilds && value.pendingBuilds.length) {
						setOps((prev) => ({ ...prev, [name]: { phase: "error", message: marketErrorText(value.error, t, value) + " · " + value.pendingBuilds.join(", "), restore } }));
						return;
					}
					if (value.application === "failed" || value.application === "cancelled" || value.error || outcome.step !== "install") {
						setOps((prev) => ({ ...prev, [name]: { phase: "error", message: value.application === "cancelled" ? t("cancelled") : marketErrorText(value.error, t, value), restore } }));
						return;
					}
					const actual = bundles && bundles.get(name);
					if (!actual || actual.version !== target.target || value.version && value.version !== target.target) {
						throw new Error(actual ? marketFill(t("versionMismatch"), { target: target.target, actual: value.version && value.version !== target.target ? value.version : actual.version || "?" }) : t("verifyFailed"));
					}
					setOps((prev) => ({ ...prev, [name]: { phase: "done", application: value.application, restartReason: value.restartReason, warnings: value.warnings, previousRevision } }));
					setUpdates((prev) => ({ ...prev, [name]: { phase: "ready", installed: actual.version } }));
				} catch (error) {
					setOps((prev) => ({ ...prev, [name]: { phase: "error", message: String(error.message || error) } }));
				} finally {
					busy.current.delete(name);
				}
			}, [scope, installed, loadInstalled]);

			/** 卸载一个已装插件。走官方 `removeBundle()`，同样不自己碰 pnpm。 */
			const remove = React.useCallback(
				(name) => {
					const manager = scope && scope.remote && scope.remote.pluginManager;
					if (!manager || typeof manager.removeBundle !== "function") return;
					if (busy.current.has(name)) return;
					busy.current.add(name);
					setOps((prev) => ({ ...prev, [name]: { phase: "removing" } }));
					manager
						.removeBundle(name)
						.then((result) => {
							if (!result || !result.ok) {
								setOps((prev) => ({
									...prev,
									[name]: {
										phase: "error",
										message: marketErrorText(
											result && result.error,
											t,
											result && result.value,
										),
									},
								}));
								return;
							}
							const value = result.value ?? {};
							if (value.application === "failed" || value.application === "cancelled" || value.error) {
								setOps((prev) => ({
									...prev,
									[name]: { phase: "error", message: marketErrorText(value.error, t, value) },
								}));
								return;
							}
							setOps((prev) => {
								const next = { ...prev };
								delete next[name];
								return next;
							});
							loadInstalled();
						})
						.catch((error) => {
							setOps((prev) => ({
								...prev,
								[name]: { phase: "error", message: String((error && error.message) || error) },
							}));
						})
						.finally(() => { busy.current.delete(name); });
				},
				[scope, loadInstalled],
			);

			const data = catalog.data;
			const catalogPlugins = (data && data.plugins) || [];
			const catalogNames = new Set(catalogPlugins.map((plugin) => plugin.name));
			const plugins = [...catalogPlugins, ...[...installed.bundles.values()].filter((bundle) => !catalogNames.has(bundle.name)).map((bundle) => ({ name: bundle.name, version: bundle.version, description: bundle.description, installable: true }))];
			const installedNames = new Set(installed.bundles.keys());

			// 过滤 + 分区。搜索是**本地**的：目录一次就全在手里（几百条），
			// 再往宿主半发一次请求没有意义，还会让每次按键都有网络延迟。
			const q = query.trim().toLowerCase();
			const filtered = plugins.filter((p) => {
				if (tab === "featured" && !p.featured) return false;
				if (tab === "community" && p.featured) return false;
				if (tab === "installed" && !installedNames.has(p.name)) return false;
				if (!q) return true;
				return (
					String(p.name).toLowerCase().includes(q) ||
					String(p.description || "").toLowerCase().includes(q)
				);
			});

			const tabs = [
				{ id: "all", label: t("tabAll"), n: plugins.length },
				{ id: "featured", label: t("tabFeatured"), n: data ? data.counts.featured : 0 },
				{ id: "community", label: t("tabCommunity"), n: data ? data.counts.community : 0 },
				{ id: "installed", label: t("tabInstalled"), n: installedNames.size },
			];

			/** 渲染一张插件卡。 */
			const renderCard = (p) => {
				const op = ops[p.name] || { phase: "idle" };
				const isInstalled = installedNames.has(p.name);
				const bundle = installed.bundles.get(p.name);
				const available = updates[p.name];
				const open = !!expanded[p.name];
				const day = marketDay(p.publishedAt);
				const risks = p.risks || [];

				const badges = [];
				if (p.featured) badges.push(marketBadge(React, MARKET_TONE.featured, t("featured")));
				if (isInstalled) badges.push(marketBadge(React, MARKET_TONE.installed, t("installed")));
				if (!p.installable) badges.push(marketBadge(React, MARKET_TONE.broken, t("notInstallable")));

				const actions = [];
				if (op.phase === "installing" || op.phase === "updating") {
					actions.push(React.createElement("span", { key: "busy", style: S.meta }, t(op.phase)));
				} else if (op.phase === "removing") {
					actions.push(React.createElement("span", { key: "busy", style: S.meta }, t("removing")));
				} else if (op.phase === "pending") {
					// pnpm 拦下了依赖脚本：这不是失败，是一个需要用户拍板的中间态。
					actions.push(
						React.createElement(
							"button",
							{
								key: "allow",
								type: "button",
								style: S.btnSmPrimary,
								onClick: () => install(p.name, op.targetVersion, op.builds, op.enabled, op.registry),
							},
							t("approvedRetry"),
						),
					);
				} else if (isInstalled) {
					if (bundle.updatable) {
						actions.push(React.createElement("button", {
							key: "update", type: "button", style: S.btnSmPrimary,
							disabled: installed.phase !== "ready" || available && available.phase === "checking",
							onClick: () => available && available.target ? update(p.name, available) : checkUpdates(new Map([[p.name, bundle]])),
						}, available && available.phase === "checking" ? t("checking") : available && available.target ? marketFill(t("updateTarget"), { version: available.target }) : t("checkUpdates")));
					}
					if (bundle.removable) actions.push(
						React.createElement(
							"button",
							{
								key: "remove",
								type: "button",
								style: S.btnSm,
								onClick: () => {
									if (window.confirm(t("confirmRemove") + "\n" + p.name)) remove(p.name);
								},
							},
							t("uninstall"),
						),
					);
				} else if (p.installable) {
					actions.push(
						React.createElement(
							"button",
							{
								key: "install",
								type: "button",
								style: S.btnSmPrimary,
								disabled: installed.phase !== "ready" || !p.version,
								onClick: () => install(p.name, p.version),
							},
							t("install"),
						),
					);
				}

				const children = [
					React.createElement(
						"div",
						{ key: "head", style: S.cardHead },
						React.createElement("h4", { style: S.pkgName, title: p.name }, p.name),
						p.version ? React.createElement("span", { style: S.pkgVer }, "v" + p.version) : null,
					),
				];

				// 描述：默认两行截断，展开详情时给全文。
				// 这是本布局里省高度最多的一处（详见 MARKET_S.desc 的注释）。
				if (p.description) {
					children.push(
						React.createElement("p", { key: "desc", style: open ? S.descFull : S.desc }, p.description),
					);
				}

				// 风险只在**展开详情**或**不可安装**时显示：每张卡都挂一段橙色警告，
				// 会让真正有问题的那张淹没在噪声里。
				if (risks.length > 0 && (open || !p.installable)) {
					children.push(
						React.createElement(
							"div",
							{ key: "risk", style: S.riskBox },
							React.createElement("span", { style: S.riskHead }, t("riskTitle")),
							...risks.map((r, i) => React.createElement("p", { key: i, style: S.riskItem }, r)),
						),
					);
				}

				if (bundle) children.push(React.createElement("p", { key: "installed", style: S.meta }, marketFill(t("currentVersion"), { version: bundle.version || "?" }) + (!bundle.updatable ? " · " + t("localSource") : "")));
				if (available && available.phase === "error") children.push(React.createElement("p", { key: "check-error", style: S.error }, t("updateCheckFailed") + available.message));
				if (op.phase === "error") {
					children.push(React.createElement("p", { key: "err", style: S.error }, t("operationFailed") + op.message));
				}
				if (op.restore) {
					const restore = op.restore;
					const text = restore.error || restore.application === "failed" || restore.application === "cancelled" ? marketErrorText(restore.error, t, restore) : restore.application;
					children.push(React.createElement("p", { key: "restore", style: S.notice }, t("restored") + (restore.version ? "v" + restore.version + " · " : "") + text));
				}
				if (op.phase === "done") {
					const text =
						op.application === "restart-required"
							? t("okRestart")
							: op.application === "overridden"
								? t("okOverridden")
								: t("okApplied");
					children.push(React.createElement("p", { key: "ok", style: S.ok }, text));
					const modules = scope.get("modules");
					if (modules && modules.entries && op.application === "applied") {
						const state = modules.entries.state.getSnapshot();
						const failure = state.failures.find((entry) => entry.id === p.name);
						const revision = clientRevision(p.name);
						const loader = scope.get("loader");
						const entry = loader && [...loader.entries()].find((item) => item.options.name === p.name);
						const clientText = modules.entries.releaseChange.getSnapshot() ? t("pageReload") : failure ? t("clientFailed") + failure.message : revision && revision !== op.previousRevision && !state.syncing && entry && entry.fiber && entry.fiber.state === 2 ? t("clientSynced") : revision || op.previousRevision ? t("clientPending") : "";
						if (clientText) children.push(React.createElement("p", { key: "client", style: failure ? S.error : S.notice }, clientText));
					}
					if (op.restartReason) children.push(React.createElement("p", { key: "reason", style: S.notice }, t("restartReason") + op.restartReason));
					if (op.warnings && op.warnings.length) children.push(React.createElement("p", { key: "warnings", style: S.notice }, t("warnings") + op.warnings.join(" · ")));
				}

				const links = [];
				if (p.repository) {
					links.push(
						React.createElement(
							"a",
							{ key: "repo", href: p.repository, target: "_blank", rel: "noreferrer", style: S.link },
							t("repo"),
						),
					);
				}
				if (p.homepage) {
					links.push(
						React.createElement(
							"a",
							{ key: "home", href: p.homepage, target: "_blank", rel: "noreferrer", style: S.link },
							t("home"),
						),
					);
				}

				// 操作行：徽章靠左、按钮靠右。
				//
				// ⚠️ 徽章**刻意从标题行挪到这里**：标题行塞进「包名 + 版本 + 精选 + 已安装」
				//   四样东西后必然换行（实测标题行 43px），而挪到操作行后标题恒定单行（22px）。
				//   省下的高度直接换成"一屏多看一张卡"。
				children.push(
					React.createElement(
						"div",
						{ key: "act", style: S.actions },
						React.createElement("div", { style: S.actionsLead }, ...badges),
						...actions,
						React.createElement(
							"button",
							{
								type: "button",
								style: S.btnSm,
								onClick: () => setExpanded((prev) => ({ ...prev, [p.name]: !prev[p.name] })),
							},
							open ? t("hideDetail") : t("detail"),
						),
					),
				);

				// 展开后才显示作者/时间/链接这类次要信息：
				// 首屏只留「这是什么、能不能装」。
				if (open) {
					const bits = [];
					if (p.publisher) bits.push(t("by") + " " + p.publisher);
					if (day) bits.push(t("updated") + " " + day);
					if (p.license) bits.push(String(p.license));
					if (p.dsh && p.dsh.client) bits.push("client");
					if (bits.length > 0) {
						children.push(React.createElement("p", { key: "meta", style: S.meta }, bits.join(" · ")));
					}
					if (links.length > 0) {
						children.push(React.createElement("div", { key: "links", style: S.links }, ...links));
					}
				}

				return React.createElement("div", { key: p.name, style: S.card }, ...children);
			};

			// ── 组装分节 ────────────────────────────────────────────────
			const body = [];

			// ⚠️ **刻意不放 h3 标题**：设置页的导航栏已经显示了本分节的名字
			//   （就是 `register()` 里那个 `label`），再放一个同名 h3 是重复信息，
			//   而且白占 40px 首屏高度。说明文案同理压成一行。
			body.push(React.createElement("p", { key: "intro", style: S.intro }, t("intro")));
			if (installed.phase === "unavailable") body.push(React.createElement("p", { key: "installed-error", style: S.error }, t("installedUnavailable")));

			body.push(
				React.createElement(
					"div",
					{ key: "bar", style: S.toolbar },
					React.createElement("input", {
						type: "search",
						style: S.search,
						placeholder: t("search"),
						value: query,
						onChange: (e) => setQuery(e.target.value),
					}),
					React.createElement(
						"button",
						{
							type: "button",
							style: S.btn,
							disabled: !!catalog.busy,
							onClick: () => { loadCatalog(true); loadInstalled().then(checkUpdates); },
						},
						catalog.busy ? t("refreshing") : t("refresh"),
					),
				),
			);

			body.push(
				React.createElement(
					"div",
					{ key: "tabs", style: S.tabs },
					...tabs.map((item) =>
						React.createElement(
							"button",
							{
								key: item.id,
								type: "button",
								style: item.id === tab ? S.tabOn : S.tab,
								onClick: () => setTab(item.id),
							},
							item.label + " " + item.n,
						),
					),
				),
			);

			// 目录来源与筛选提示。
			//
			// ⚠️ **常态下这一行不出现**：上面那排 tab 已经写着「全部 6 / 精选 6 / 社区 0」，
			//   再来一行「精选 6 · 社区 0」纯属重复，白占 18px。
			//   只在两种情况显示：① 用户筛掉了东西（需要知道"还剩几个"）；
			//   ② 目录来自离线种子（需要知道"这份可能过时"）。
			if (data) {
				if (data.source === "seed") {
					body.push(
						React.createElement(
							"p",
							{ key: "seedwarn", style: S.notice },
							t("seedWarn") + (data.liveError ? " " + t("liveWarn") + data.liveError : ""),
						),
					);
				}
				if (filtered.length !== plugins.length) {
					body.push(
						React.createElement(
							"p",
							{ key: "shown", style: S.meta },
							marketFill(t("shown"), { n: filtered.length }),
						),
					);
				}
				if (data.truncated) {
					body.push(React.createElement("p", { key: "trunc", style: S.notice }, t("truncated")));
				}
			}

			if (catalog.phase === "error") {
				body.push(
					React.createElement("p", { key: "err", style: S.error }, t("loadFailed") + catalog.message),
				);
				body.push(
					React.createElement(
						"div",
						{ key: "retry", style: S.actions },
						React.createElement(
							"button",
							{ type: "button", style: S.btn, onClick: () => loadCatalog(true) },
							t("retry"),
						),
					),
				);
			} else if (catalog.phase === "loading") {
				body.push(
					React.createElement("p", { key: "loading", style: S.meta }, t("loading") + " " + t("loadingHint")),
				);
				body.push(
					React.createElement(
						"div",
						{ key: "skel", style: S.list },
						React.createElement("div", { style: S.skeleton }),
						React.createElement("div", { style: S.skeleton }),
						React.createElement("div", { style: S.skeleton }),
					),
				);
			} else if (filtered.length === 0) {
				// 社区区为空是**冷启动的正常状态**，不是错误 —— 文案要说清怎么让它有内容。
				const hint = tab === "community" && !q ? t("emptyCommunity") : t("empty");
				body.push(React.createElement("p", { key: "empty", style: S.empty }, hint));
			} else {
				body.push(
					React.createElement("div", { key: "list", style: S.list }, ...filtered.map(renderCard)),
				);
			}

			return React.createElement("div", { style: S.section }, ...body);
		}

		/**
		 * 注册设置页的「插件市场」分节。
		 *
		 * @param {object} ctx - 浏览器插件上下文。
		 */
		function apply(ctx) {
			try {
				marketReact = require("react");
			} catch (e) {
				console.warn("[dsh-android-market] require('react') 失败，跳过市场分节:", e && e.message);
				marketReact = null;
			}
			if (!marketReact) return;

			try {
				// ⚠️ 必须声明 `remote.pluginManager`：安装/卸载动作全靠它。
				//   声明了但服务不在时，Cordis 不会调用这个回调（分节不出现），
				//   这比渲染出一个点了没反应的按钮要好。
				ctx.inject(["slots", "locale", "remote", "remote.pluginManager"], (scope) => {
					// ⚠️ 用 `scope.locale` 而不是外层 `ctx.locale` —— 理由见文件头。
					const t = scope.locale.bind(MARKET_NS);
					scope.effect(
						() => scope.locale.register(MARKET_NS, { zh: MARKET_ZH, en: MARKET_EN }),
						"dsh-android-market: 市场文案",
					);
					scope.slots.inject("settings.section", () =>
						scope.slots.register(
							{
								name: "settings.section",
								id: "dsh-android-market",
								order: MARKET_ORDER,
								label: () => t("nav"),
								locale: MARKET_NS,
							},
							(slotProps) => marketSection(slotProps, scope),
						),
					);
				});
			} catch (e) {
				// 注册失败只损失这一个分节，不能连累其它插件。
				// 但要打日志：静默吞异常会让「分节没出现」变成无从下手的哑谜。
				console.warn("[dsh-android-market] 注册市场分节失败:", e && (e.stack || e.message || e));
			}
		}

		exports.apply = apply;
		exports.inject = [];
		return module.exports;
	},
});
