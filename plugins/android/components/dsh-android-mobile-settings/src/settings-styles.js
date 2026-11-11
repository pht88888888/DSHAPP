export const MP_PRIV_S = {
	section: { maxWidth: "760px", display: "flex", flexDirection: "column", gap: "12px", color: "var(--dsw-alias-label-primary, #222)" },
	heading: { margin: 0, fontSize: "18px", fontWeight: 600 },
	intro: { margin: 0, fontSize: "13px", lineHeight: "20px", color: "var(--dsw-alias-label-tertiary, #888)" },
	card: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "12px 14px", display: "flex", flexDirection: "column", gap: "10px" },
	cardHead: { display: "flex", alignItems: "center", gap: "8px" },
	cardTitle: { margin: 0, fontSize: "15px", fontWeight: 600 },
	badge: { marginLeft: "auto", fontSize: "12px", fontWeight: 600, padding: "3px 9px", borderRadius: "999px", whiteSpace: "nowrap" },
	btn: { border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
	row: { display: "flex", alignItems: "baseline", gap: "8px", fontSize: "13px", lineHeight: "19px" },
	dot: { flex: "0 0 auto", width: "7px", height: "7px", borderRadius: "50%", transform: "translateY(-1px)" },
	rowLabel: { flex: "0 0 auto", color: "var(--dsw-alias-label-tertiary, #888)" },
	rowValue: { flex: "1 1 auto", minWidth: 0, wordBreak: "break-word" },
	steps: { margin: 0, padding: "0 0 0 20px", display: "flex", flexDirection: "column", gap: "9px" },
	step: { fontSize: "13px", lineHeight: "20px" },
	// 命令块：独立成行 + 可横向滚动。窄屏上命令很长，
	// 换行会把路径断在中间导致抄错，所以宁可横向滚。
	// ⚠️ 这里**刻意不**设 user-select：本插件的全局 CSS 是
	//   `[data-mobile] * { user-select: none !important }`，内联会被压掉，
	//   写了也是死代码。**正因如此才必须有复制按钮** —— 命令长按选不中，
	//   而这台设备上用户是在电脑终端里敲，只能照着手机屏幕抄。
	cmdRow: { display: "flex", alignItems: "stretch", gap: "6px", marginTop: "6px" },
	cmd: { flex: "1 1 auto", minWidth: 0, display: "block", padding: "7px 9px", borderRadius: "6px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)", fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "12px", lineHeight: "18px", overflowX: "auto", whiteSpace: "pre" },
	// 固定宽度：文案在「复制 / 已复制 / 复制失败」之间切换，
	// 不固定的话按钮会随字数变宽，把左边的命令块挤得来回跳。
	cmdBtn: { flex: "0 0 auto", display: "flex", alignItems: "center", justifyContent: "center", minWidth: "62px", border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", background: "transparent", color: "inherit", font: "inherit", fontSize: "12px", padding: "0 8px", borderRadius: "6px", cursor: "pointer", whiteSpace: "nowrap" },
	notes: { margin: 0, padding: "0 0 0 18px", display: "flex", flexDirection: "column", gap: "6px" },
	note: { fontSize: "12px", lineHeight: "18px", color: "var(--dsw-alias-label-tertiary, #888)" },
	error: { margin: 0, fontSize: "12px", lineHeight: "18px", color: "#c92a2a" },

	// ── 重设计后新增 ────────────────────────────────────────
	// 首屏「下一步」块：把「点哪里」摆在结论正下方，用户不必自己从
	// 三行技术明细里翻译出该做什么。
	stack: { display: "flex", flexDirection: "column", gap: "12px" },
	summary: { margin: 0, fontSize: "13px", lineHeight: "19px" },
	next: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", padding: "9px 10px", borderRadius: "8px", background: "var(--dsw-alias-interactive-bg-hover, #f2f2f2)" },
	nextText: { flex: "1 1 160px", minWidth: 0, fontSize: "13px", lineHeight: "19px" },			// root 卡片专用的「下一步」文案样式：与上面那条的区别只有换行基线。
	//
	// ⚠️ **不复用 `nextText`**：它的 `flex: "1 1 160px"` 在 360 CSS px 宽的
	//   卡片里会把长文案挤成每行十来个字的一窄列（root 那三条文案都挺长）。
	//   用 100% 作基线则文案先占满整行、按钮自然掉到下一行，读起来才是一段话。
	//   之所以单独开一条而不是改上面那条：上面那条是 uid 2000 卡片在用的，
	//   改它会连带改掉那张已经定型的卡片 —— 只修自己新加的那张。
	nextTextWide: { flex: "1 1 100%", minWidth: 0, fontSize: "13px", lineHeight: "19px" },
	actions: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
	// 主按钮：整页只有「配对」和「下一步」用实心，避免到处都在喊。
	btnPrimary: { border: "0.5px solid transparent", background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", color: "#fff", font: "inherit", fontSize: "13px", fontWeight: 600, padding: "6px 14px", borderRadius: "8px", cursor: "pointer" },
	// 次要动作（取消配对 / 查看详情）用弱化样式，和主按钮拉开层级。
	btnQuiet: { border: "0.5px solid transparent", background: "transparent", color: "var(--dsw-alias-label-tertiary, #888)", font: "inherit", fontSize: "13px", padding: "6px 10px", borderRadius: "8px", cursor: "pointer" },
	details: { display: "flex", flexDirection: "column", gap: "8px", paddingTop: "4px", borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)" },
	// 折叠块：引导与注意事项默认收起，让首屏只剩结论。
	fold: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", padding: "10px 14px" },
	foldHead: { fontSize: "14px", fontWeight: 600, cursor: "pointer" },
	foldBody: { display: "flex", flexDirection: "column", gap: "12px", paddingTop: "10px" },
	// ⚠️ 这里**不能**引用 `MP_PRIV_TONE` —— 它定义在本对象**之后**，
	// 在模块求值期引用会抛 `Cannot access 'MP_PRIV_TONE' before
	// initialization`（整个插件 import 失败、界面直接白屏，踩过一次）。
	// 所以颜色写死，与 MP_PRIV_TONE.on 保持一致。
	tagRecommended: { marginLeft: "auto", fontSize: "11px", fontWeight: 600, padding: "2px 8px", borderRadius: "999px", color: "#2f9e44", background: "rgba(47,158,68,0.12)", whiteSpace: "nowrap" },

	// ── 布局重做新增 ────────────────────────────────────────
	// 三条路径之间的分组标题。比 `heading`（h2，18px）小一号 ——
	// 它是分节内部的次级标题，与分节标题同分量会让层级糊掉。
	groupTitle: { margin: "4px 0 0", fontSize: "15px", fontWeight: 600 },
	// 卡片**内部**的折叠块：与顶层 `fold`（自己有边框、独立成块）区分开 ——
	// 卡片里再套一个带边框的块会出现「框里套框」的双线，看着很脏。
	// 这里只用一条上分隔线。
	foldInline: { borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)", paddingTop: "8px" },

	// ── 标签页（ADB / Shizuku / root）───────────────────────
	//
	// 下划线式标签：底边一条通栏分隔线，选中项盖一条 2px 主色指示条。
	// ⚠️ 指示条靠 `marginBottom: "-0.5px"` **压在**那条分隔线上 —— 不压的话
	//   选中项的下划线会比容器底边高半个像素，视觉上「浮」起来。
	//   这是纯 CSS 手法，不需要额外的定位容器。
	tabBar: {
		display: "flex", alignItems: "stretch", gap: "2px",
		borderBottom: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)",
	},
	tab: {
		flex: "1 1 0", minWidth: 0,
		display: "flex", alignItems: "center", justifyContent: "center", gap: "6px",
		border: "none", background: "transparent",
		color: "var(--dsw-alias-label-tertiary, #888)",
		font: "inherit", fontSize: "14px",
		padding: "9px 4px 10px", cursor: "pointer",
		borderBottom: "2px solid transparent", marginBottom: "-0.5px",
	},
	tabActive: {
		color: "var(--dsw-alias-label-primary, #222)", fontWeight: 600,
		borderBottom: "2px solid var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
	},
	// 标签上的状态点：进页面之前就能看出哪条通道是通的，不必逐个点开。
	tabDot: { flex: "0 0 auto", width: "7px", height: "7px", borderRadius: "50%" },
	// 窄屏上「Shizuku」比「ADB」宽得多，不省略的话三个标签宽度会被撑歪。
	tabLabel: { minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
	// 标签页内容区：与常驻结论条拉开一点距离，让「上面是总览、下面是当前页」成立。
	panel: { display: "flex", flexDirection: "column", gap: "12px", paddingTop: "2px" },

	// ── 「交流群」分节 ──────────────────────────────────────
	// 二维码容器：居中 + 一点上下留白，避免图片紧贴卡片边框。
	qrWrap: { display: "flex", justifyContent: "center", padding: "4px 0 2px" },
	// 显示尺寸取 240px 且不超过卡片宽度。
	// ⚠️ 内联源图是 640px 的 WebP，缩到 240 CSS px 显示。实测（dpr=3 渲染
	//   截图后交给 zxing 解码）400px 的源图就已够扫，640px 是留了余量：
	//   用户可能会用另一台设备对着屏幕拍，拍摄角度/对焦都会损失有效分辨率。
	// ⚠️ 不设 `imageRendering: "pixelated"`：那是为放大低分辨率图准备的，
	//   这里是从 640 缩到 240（缩小），平滑重采样反而更利于扫描识别。
	qrImg: { width: "240px", maxWidth: "100%", height: "auto", display: "block", borderRadius: "8px", background: "#fff" },
	// 群号用等宽字体且**放大一号**：这是要照着念/照着手输的数字，
	// 与周围的说明文字同等字号会显得不够醒目。
	mpGroupNo: { flex: "1 1 auto", minWidth: 0, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: "15px", fontWeight: 600, letterSpacing: "0.5px" },
	// ── 「甩动手感」开关行 ───────────────────────────────────
	// 一行一个开关：左边名字+说明，右边一枚自绘开关。
	// ⚠️ 不用 <input type="checkbox">：本插件的全局 CSS 会把原生控件压成
	//   系统默认样式，在深色主题下几乎看不见。自绘只用两个 div。
	swRow: { display: "flex", alignItems: "flex-start", gap: "12px", padding: "10px 0", borderTop: "0.5px solid var(--dsw-alias-border-l2, #eee)" },
	swMain: { flex: "1 1 auto", minWidth: 0, display: "flex", flexDirection: "column", gap: "3px" },
	swName: { fontSize: "14px", fontWeight: 600 }, 
	swHint: { fontSize: "12px", lineHeight: "18px", color: "var(--dsw-alias-label-tertiary, #888)" },
	swWhy: { fontSize: "12px", lineHeight: "18px", color: "#c92a2a" },
	// 轨道：关=灰、开=主色。宽高固定，thumb 用绝对定位左右滑。
	swTrack: { flex: "0 0 auto", position: "relative", width: "44px", height: "26px", borderRadius: "999px", border: "none", padding: 0, cursor: "pointer", transition: "background 0.18s ease", marginTop: "2px" },
	swThumb: { position: "absolute", top: "3px", width: "20px", height: "20px", borderRadius: "50%", background: "#fff", boxShadow: "0 1px 3px rgba(0,0,0,0.28)", transition: "left 0.18s ease" },
	// ── 「外观控制面板」用的控件 ─────────────────────────────
	// 分组标题：比 cardTitle 轻一档，用来在长面板里切段。
	grpTitle: { margin: "14px 0 2px", fontSize: "13px", fontWeight: 700, letterSpacing: "0.4px", color: "var(--dsw-alias-label-tertiary, #888)", textTransform: "uppercase" },
	// 一行控件：左标签 + 右值。比 swRow 紧凑（无下边框），因为同类行会连排。
	// ⚠️ 标签列 116px（不是 104px）：本页最长的标签是「显示余额徽标」6 个汉字，
	//   14px 字号下约需 84px，加上行内 padding 与 gap，104px 会被 `ellipsis`
	//   截成「显示余额徽标…」。给到 116px 刚好放下，又不至于挤压右侧控件。
	ctlRow: { display: "grid", gridTemplateColumns: "116px 1fr auto", alignItems: "center", gap: "10px", padding: "7px 0" },
	ctlLabel: { minWidth: 0, fontSize: "14px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
	ctlValue: { flex: "0 0 auto", fontSize: "13px", fontVariantNumeric: "tabular-nums", color: "var(--dsw-alias-label-tertiary, #888)", minWidth: "46px", textAlign: "right" },
	// 滑块：原生 range，但只保留轨道与滑块，颜色跟主题走。
	// ⚠️ accent-color 是关键：不设的话安卓 WebView 会画成系统蓝，与主题撞色。
	slider: { flex: "1 1 0", minWidth: 0, accentColor: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", height: "28px", margin: 0 },
	// 分段选择器：等宽按钮排一行，选中项反色。比 <select> 好按，也比它好看。
	/**
	 * 分段选择器。
	 *
	 * ⚠️ **不能用 "whiteSpace: nowrap" + 等分平分**：本页的「出现位置」有 6 个
	 *   中文选项，等分后每格只有约 48px，而「右上」「右下」这类两字标签在
	 *   13px 字号下需要约 34px + 左右内边距，于是全部被 "ellipsis" 截成
	 *   「右…」「左…」—— 六个按钮长得一模一样，完全没法选。
	 *
	 *   改为：**允许换行**（"flexWrap"）+ 按钮按内容宽度（"0 0 auto"），
	 *   放不下就折到第二行。宁可多占一行，也不能让文字读不出来。
	 */
	seg: { display: "flex", flexWrap: "wrap", flex: "1 1 0", minWidth: 0, gap: "6px" },
	segBtn: { flex: "0 0 auto", minWidth: 0, border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", textAlign: "center", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "6px 11px", borderRadius: "8px", cursor: "pointer", whiteSpace: "nowrap" },
	segBtnOn: { background: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)", borderColor: "transparent", color: "#fff", fontWeight: 600 },
	// 颜色选择：圆形色块 + 原生取色器叠在上面（透明点击层）。
	// ⚠️ 不用 type=color 直接可见：它渲染出来是个方块，跟整页的圆角风格不搭。
	/**
	 * 色块圆点。
	 *
	 * ⚠️⚠️ **"position: relative" 与固定宽高必须留在这一层**，因为里面的
	 *   "<input type="color">" 是 "position: absolute; inset: 0"，它靠这个
	 *   元素建立包含块。
	 *
	 * 踩过：真机上这三个色块的 input 尺寸变成了 **360×764（整个面板）**，
	 * 于是透明的 input 盖住了下面所有行 ——
	 *   ① 页面无法上下滑动（滑动事件被它吃掉）；
	 *   ② 点分组标题「收起」时命中的是它，弹出系统颜色选择器，分组永远收不起来。
	 * 根因就是包含块没建立起来时 "inset: 0" 会退回最近的可定位祖先（= 整个面板）。
	 *
	 * ⚠️ 另外补 "display: block" + "boxSizing: border-box"：这两个色块在
	 *   "display: grid" 的 "ctlRow" 里，"flex: 0 0 auto" 对 grid 子项**无效**，
	 *   不加的话 span 会按内容宽度撑开，"width/height" 反而不稳。
	 */
	swatchWrap: {
		position: "relative",
		display: "block",
		flex: "0 0 auto",
		width: "30px",
		height: "30px",
		boxSizing: "border-box",
		borderRadius: "50%",
		border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
		overflow: "hidden",
	},
	swatchInput: {
		position: "absolute",
		left: 0,
		top: 0,
		width: "100%",
		height: "100%",
		// ⚠️ 不要用 `inset: 0` —— 旧内核（本 App 目标 chrome74）对 `inset`
		//   简写的支持不完整，退化成不定位时就会铺满整个可定位祖先。
		//   显式写 left/top/width/height 最稳。
		opacity: 0,
		border: "none",
		padding: 0,
		margin: 0,
		cursor: "pointer",
	},
	// 数字输入：比滑块精确，用于尺寸这类要打准的值。
	numInput: { flex: "0 0 auto", width: "64px", border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)", borderRadius: "6px", background: "transparent", color: "inherit", font: "inherit", fontSize: "13px", padding: "5px 7px", textAlign: "right" },
	// 可折叠分组：长面板必须能折叠，否则一屏塞不下、用户永远滚不到底。
	acc: { border: "0.5px solid var(--dsw-alias-border-l2, #e2e2e2)", borderRadius: "10px", marginTop: "10px", overflow: "hidden" },
	accHead: { display: "flex", alignItems: "center", gap: "8px", padding: "11px 14px", cursor: "pointer", fontSize: "14px", fontWeight: 600, background: "var(--dsw-alias-interactive-bg-hover, #fafafa)" },
	// ── 余额徽标「显示内容」的编辑器 ──────────────────────────────
	//
	// 三段式：预览 → 当前配方（可删）→ 可加的词（已用则禁用）。
	bbEditor: { display: "flex", flexDirection: "column", gap: "8px", padding: "8px 0 4px" },
	bbPreviewRow: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" },
	// 预览用的"假徽标"：故意做得和真实徽标同形（深底浅字、圆角胶囊），
	// 让用户一眼认出"这就是球旁边那个东西"。
	bbPreviewBadge: {
		background: "rgba(28, 30, 34, 0.92)",
		color: "#fff",
		fontSize: "13px",
		padding: "3px 10px",
		borderRadius: "999px",
		fontVariantNumeric: "tabular-nums",
		whiteSpace: "nowrap",
		maxWidth: "100%",
		overflow: "hidden",
		textOverflow: "ellipsis",
	},
	bbTokenRow: { display: "flex", flexWrap: "wrap", gap: "6px", alignItems: "center" },
	// 「可加的词」：浅底、有点击感。
	bbToken: {
		border: "0.5px solid var(--dsw-alias-border-l2, #dcdcdc)",
		background: "var(--dsw-alias-bg-layer-2, #f4f4f4)",
		color: "var(--dsw-alias-label-primary, #333)",
		font: "inherit",
		fontSize: "12px",
		padding: "4px 10px",
		borderRadius: "999px",
		cursor: "pointer",
	},
	// 已经用过的词：置灰且不可点，避免同一个词被点进模板十几遍。
	bbTokenUsed: {
		opacity: 0.38,
		cursor: "not-allowed",
		background: "transparent",
	},
	// 「当前配方」里的胶囊：带删除按钮，视觉上比"可加的词"更实。
	bbChip: {
		display: "inline-flex",
		alignItems: "center",
		gap: "3px",
		border: "0.5px solid var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
		background: "rgba(43, 108, 176, 0.10)",
		color: "var(--dsw-alias-interactive-bg-primary, #2b6cb0)",
		fontSize: "12px",
		padding: "3px 4px 3px 10px",
		borderRadius: "999px",
	},
	// 字面文字（如「剩 」）：不带删除键的普通说明，用中性色。
	bbChipLit: {
		display: "inline-flex",
		alignItems: "center",
		gap: "3px",
		border: "0.5px dashed var(--dsw-alias-border-l2, #dcdcdc)",
		background: "transparent",
		color: "var(--dsw-alias-label-secondary, #666)",
		fontSize: "12px",
		padding: "3px 4px 3px 10px",
		borderRadius: "999px",
	},
	// 未知 token（用户可能在别处写错）：灰底警告色，但不丢。
	bbChipUnknown: {
		display: "inline-flex",
		alignItems: "center",
		gap: "3px",
		border: "0.5px solid #e8590c",
		background: "rgba(232, 89, 12, 0.10)",
		color: "#e8590c",
		fontSize: "12px",
		padding: "3px 4px 3px 10px",
		borderRadius: "999px",
	},
	bbChipX: {
		border: "none",
		background: "transparent",
		color: "inherit",
		font: "inherit",
		fontSize: "14px",
		lineHeight: "1",
		padding: "0 5px",
		cursor: "pointer",
		opacity: 0.75,
	},
	accBody: { display: "flex", flexDirection: "column", padding: "2px 14px 10px" },
	accCount: { marginLeft: "auto", fontSize: "11px", fontWeight: 400, color: "var(--dsw-alias-label-tertiary, #999)" },
};

export const MP_PRIV_TONE = {
	on: { color: "#2f9e44", bg: "rgba(47,158,68,0.12)" },
	off: { color: "#e8590c", bg: "rgba(232,89,12,0.12)" },
	// 「还不能用，但没坏」—— 目前只有一种情况：密钥是 App 现场生成的，
	// 设备尚未信任，需要走一次配对。用橙色会和「缺失」混淆，所以单列一色。
	warn: { color: "#f08c00", bg: "rgba(240,140,0,0.12)" },
	unknown: { color: "#868e96", bg: "rgba(134,142,150,0.12)" },
};

/**
 * 把一段文本写进系统剪贴板。
 *
 * ⚠️ **必须两条路都留**：官方前端跑在 "http://127.0.0.1:3080" 上，
 * 而 "navigator.clipboard" 只在**安全上下文**（https / localhost 域名 /
 * file:）里存在 —— "127.0.0.1" 虽是回环地址、Chromium 也把它算作
 * potentially trustworthy，但 WebView 上这个判定随内核版本而变，
 * 不能赌。所以先用 "navigator.clipboard"，失败或不存在时退回
 * "document.execCommand("copy")" + 临时 textarea 的老办法。
 *
 * @param {string} text - 要复制的文本。
 * @returns {Promise<boolean>} 是否成功。
 */
export function mpCopyText(text) {
	const fallback = () => {
		try {
			const area = document.createElement("textarea");
			area.value = text;
			// 必须在文档里且可聚焦，execCommand 才会作用于它。
			// 用 fixed + 透明把它藏起来，避免点按钮时页面跳动。
			area.setAttribute("readonly", "");
			area.style.position = "fixed";
			area.style.top = "0";
			area.style.left = "0";
			area.style.opacity = "0";
			area.style.pointerEvents = "none";
			document.body.appendChild(area);
			area.select();
			area.setSelectionRange(0, text.length);
			const ok = document.execCommand("copy");
			document.body.removeChild(area);
			return ok;
		} catch (error) {
			console.warn("[dsh-android-mobile] 复制到剪贴板失败:", error && error.message);
			return false;
		}
	};
	try {
		if (navigator.clipboard && navigator.clipboard.writeText) {
			return navigator.clipboard.writeText(text).then(() => true, fallback);
		}
	} catch (error) {
		/* 落到 fallback */
	}
	return Promise.resolve(fallback());
}
