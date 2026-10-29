/**
 * dsh-remote-mobile —— 手机端（client half，跑在 WebView 的页面世界里）。
 *
 * ## 这个文件做两件事
 *
 * 1. **「电脑文件夹」入口** —— 注册进 `directoryFlow` slot，让选文件夹的对话框里
 *    多出一个「电脑」页签：浏览电脑目录、在电脑上直接开一个会话。
 * 2. **电脑实时消息流** —— 订阅宿主桥的全局 SSE，把电脑的流式帧转成
 *    `assistant/live-chunk` transient 注入本地会话，于是手机上有逐字动画。
 *
 * ## ⚠️ 为什么是「取代」官方对话框，而不是「并列」
 *
 * `directoryFlow` 是 `kind: "single"` 的 slot，渲染时取 `entriesOfSlot(name)[0]`，
 * 而 entries 按 priority **升序**排（见 dsh-client-ui-renderer L1148-1151）。
 * 也就是说这个 slot 同时只能渲染**一个** occupant，无法「并排加一项」。
 *
 * 所以本插件用 **priority -1** 占据首位，自己实现一个双页签对话框：
 * 「手机」页签复刻官方浏览行为（走 `uiWorkspace.listDirectory`，与官方 browse
 * 后端是同一个 API），「电脑」页签走宿主桥。对用户的观感就是
 * 「选文件夹的时候多了一个电脑入口」。
 *
 * ## ⚠️ 语法约束（旧内核）
 *
 * 本 App 有按 chrome73 降级编译的前端 bundle（`webview-compat.zip`），
 * 所以这里**不用**可选链 `?.`、空值合并 `??`、`Object.fromEntries`、`Array.flat`
 * 这些 chrome80+ 的语法。
 *
 * @module dsh-jiuling-remote-mobile/client
 */

window.__ModuleLoader__.load({
	id: 'dsh-jiuling-remote-mobile',
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });

		/*
		 * ## ⚠️⚠️ `react` 也不做假设（1.2.58 修）
		 *
		 * 它比 `primitives` 基础得多，理论上一定在。但既然上一轮
		 * 已经证实「模块可能拿不到」，这里就不能再靠『理论上』。
		 *
		 * 而且这一行同样在 **factory 顶层**：`react.createElement`
		 * 一旦因为 `react` 是 undefined 而抛 TypeError，后果和上一轮
		 * 完全一样 —— 整批 client 插件一起失败，App 进救援模式。
		 *
		 * 兜底方式：`h` 退化成一个**返回普通描述对象的函数**。
		 * 真到那一步，React 本身都不在，界面本来也渲染不了；
		 * 但至少 factory 能走完、`exports.apply` 能导出去，
		 * 不会把别人的插件一起拖下水。
		 */
		var react = null;
		try {
			react = require('react');
		} catch (reactError) {
			react = null;
		}
		var h = (react !== null && react !== undefined && typeof react.createElement === 'function')
			? react.createElement
			: function (type, props) {
				/* 极简替身：把子节点收进 props，形状够渲染器识别即可 */
				var rest = [];
				for (var ci = 2; ci < arguments.length; ci += 1) rest.push(arguments[ci]);
				var merged = {};
				if (props !== null && props !== undefined) {
					for (var key in props) {
						if (Object.prototype.hasOwnProperty.call(props, key)) merged[key] = props[key];
					}
				}
				if (rest.length > 0) merged.children = rest.length === 1 ? rest[0] : rest;
				return { type: type, props: merged };
			};

		/**
		 * 官方 UI 原子组件（对话框、按钮、分段页签、图标）。
		 *
		 * ⚠️ **这是「长得像官方」的唯一正解**：官方目录选择器
		 * （`dsh-client-ui-directory-picker-browse`）自己也是这么 `require` 的，
		 * 该模块已在页面模块注册表里，手机与桌面两端都有。
		 *
		 * 自己手搓 `div` + `style` 的代价是实打实的：先前那版写死了
		 * `background:var(--dsw-alias-bg-primary,#1b1b1f)` —— 而
		 * **`--dsw-alias-bg-primary` 这个令牌根本不存在**（官方真实名字是
		 * `--dsw-alias-bg-layer-1` / `bg-l1` / `bg-base`），于是整块卡片永远走
		 * 那个深色 fallback；`--dsw-alias-border-secondary` 同样不存在。
		 * 表现就是手机上「两大坨黑色、跟官方 UI 完全脱钩」。
		 */
		/*
		 * ## ⚠️⚠️ 这段在 **factory 顶层**，抛错会带走整批插件（1.2.57 修）
		 *
		 * `require()` 正常返回一个有 100+ 键的对象。但下面两种情况下
		 * 它会返回 `undefined` / `null`：
		 *   · 页面模块注册表里没有这个包（宿主版本更老 / 被裁剪的构建）；
		 *   · 它自己加载失败，注册表把失败结果缓存下来。
		 *
		 * 而 `require(...)` 的返回值**没人检查**，直接 `.Modal` / `.Button`
		 * 解构 —— `undefined.Modal` 立刻抛 TypeError。
		 *
		 * 这里的后果**不是本插件不工作**，而是：
		 *   `dsh-client-modules` 的 `ClientPackageCompositionError` 是
		 *   整批聚合抛出的 ⇒ 一个插件 factory 炸掉，整页
		 *   `Failed to load plugins` ⇒ App 进救援模式并无限重启。
		 *   正是 1.0.29 那次灾难的同一条链路（见 `apply()` 前面那段注释）。
		 *
		 * 所以：拿不到就退成一个空对象，让下面每个解构都是 `undefined`。
		 * 各组件本来就必须容忍 `undefined`（旧版前端可能少某个图标，
		 * 见 `IconGlobeOutlineRegular` 的注释），这样最坏情况是
		 * 「界面退化成本插件自己的样式」，而不是「整个 App 起不来」。
		 */
		var primitives = null;
		try {
			primitives = require('@deepseek-ai/dsh-client-ui-primitives');
		} catch (requireError) {
			primitives = null;
		}
		if (primitives === null || primitives === undefined || typeof primitives !== 'object') {
			/*
			 * 用一个**空对象**兜底：
			 * 这样 `primitives.Modal` 是 undefined 而不是抛错。
			 * 绝不抛、绝不 return —— factory 必须走到 `exports.apply`。
			 */
			primitives = {};
		}
		var Modal = primitives.Modal;
		var Button = primitives.Button;
		var Input = primitives.Input;
		var SegmentedTabs = primitives.SegmentedTabs;
		var IconFolderCloseRegular = primitives.IconFolderCloseRegular;
		var IconChevronRightOutlineRegular = primitives.IconChevronRightOutlineRegular;
		var IconPlusOutlineRegular = primitives.IconPlusOutlineRegular;
		/*
		 * 「电脑工作区」角标用的图标。
		 *
		 * 没有专门的"显示器/电脑"图标（官方图标集里查过 110 个名字，
		 * 没有 desktop / computer / monitor 任何一个），所以选
		 * `IconGlobeOutlineRegular` —— 它表达"远程、网络的另一端"，
		 * 正是"这是电脑上的工作区"这个语义。
		 *
		 * ⚠️ 用 `typeof` 判空后再用：老版本前端可能没有这个图标，
		 * 直接 `h(undefined)` 会整块崩掉。
		 */
		var IconGlobeOutlineRegular = primitives.IconGlobeOutlineRegular;
		/*
		 * ## ⚠️⚠️ 组件级兜底（1.2.57 修）
		 *
		 * 上面 `primitives` 兜成了空对象，所以这几个可能全是 `undefined`。
		 * 而 `h(undefined, ...)` 会让 React 抛
		 * `Element type is invalid`——在**渲染期**炸，不是加载期。
		 *
		 * 渲染期抛错只会让本插件那棵子树被官方 `SlotErrorBoundary` 接住
		 *（侧边栏 slot 会变空 div），不会带走整批插件。但用户的观感是
		 * 「一打开选文件夹就整片空白」，同样不能接受。
		 *
		 * 所以每个组件都给一个**语义等价的原生替身**：
		 *   · 容器类（Modal/Button/Input/SegmentedTabs）用对应的 DOM 元素；
		 *   · 图标用空 svg（占位但不出错）。
		 * 样式会退化成本插件自己的 CSS（本来就有），功能不受影响。
		 *
		 * 判据用 `typeof === 'function' || typeof === 'object'`：
		 * React 组件可以是 class（function）也可以是 memo/forwardRef
		 * 的产物（object）。只判 function 会把 memo 出来的组件误判掉。
		 */
		var isComponent = function (value) {
			return typeof value === 'function' || (typeof value === 'object' && value !== null);
		};
		/** 空 svg：图标缺失时占位，绝不抛错。 */
		var EmptyIcon = function () {
			return h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', 'aria-hidden': 'true' });
		};
		if (!isComponent(Modal)) Modal = function (props) { return h('div', props && props.className ? { className: props.className } : null, props ? props.children : null); };
		if (!isComponent(Button)) Button = function (props) {
			var p = props || {};
			return h('button', { style: p.style, onClick: p.onClick, type: 'button' }, p.children);
		};
		if (!isComponent(Input)) Input = function (props) {
			var p = props || {};
			return h('input', { style: p.style, value: p.value, onChange: p.onChange, placeholder: p.placeholder });
		};
		if (!isComponent(SegmentedTabs)) SegmentedTabs = function (props) { return h('div', null, props ? props.children : null); };
		if (!isComponent(IconFolderCloseRegular)) IconFolderCloseRegular = EmptyIcon;
		if (!isComponent(IconChevronRightOutlineRegular)) IconChevronRightOutlineRegular = EmptyIcon;
		if (!isComponent(IconPlusOutlineRegular)) IconPlusOutlineRegular = EmptyIcon;

		/** 宿主桥的路由（同源，直接相对路径）。 */
		var ROUTE_RPC = '/api/dsh-remote/rpc';
		var ROUTE_STREAM = '/api/dsh-remote/stream';

		/**
		 * 对话框卡片的 className（Modal 的 `className` 收字符串、不是 style 对象）。
		 *
		 * 尺寸与留白**逐条照抄**官方目录选择器对 Modal 的覆盖
		 * （`.../dsh-client-ui-directory-picker-browse/lib/client.js` 里
		 * `dialog{...gap:0;width:min(680px,100%);height:min(500px,100dvh - 32px);padding:0}`），
		 * 只把宽度收窄到手机合适的 560px。背景、圆角、阴影、遮罩一律由官方
		 * Modal 自己的 CSS 提供 —— 本插件**不再声明任何颜色**。
		 */
		var DIALOG_CLASS = 'jlm_dialog';

		/**
		 * 布局样式：取值全部照抄官方目录选择器的同义规则，令牌全部是
		 * **真实存在**的官方设计令牌（`--dsw-alias-border-l3`、`bg-layer-*`、
		 * `interactive-bg-hover`、`label-*` …），不写死任何颜色值。
		 */
		var CSS = {
			// ## ⚠️⚠️ 高度必须有多档兜底，不能只靠 `dvh`
			//
			// 用户反馈（另一个人、另一台设备）：「要么就是太小了，中间内容看不见；
			// 要么就是太大了，又划不动」，视频里是**弹窗铺满全屏、标题栏和底部
			// 按钮全部消失、只剩一列文件夹**。
			//
			// 根因：原来只写了 `height:min(560px,100dvh - 32px)`。`dvh` 是
			// Chrome 108+ 才有的单位，**旧 WebView 不认识它 ⇒ 整条 `min()` 声明
			// 直接失效**（不是降级到 560px，而是整条无效）⇒ `height` 退回 `auto`
			// ⇒ 弹窗高度由内容决定 ⇒ 电脑页签下几十个文件夹把弹窗撑满整屏
			// ⇒ header / footer 被顶到视口外，用户就只看得见中间那列文件。
			//
			// 我自己的测试机 WebView 是 Chrome/114（支持 dvh），所以怎么测都是
			// 好的 —— 这就是为什么本地一直复现不出来。
			//
			// ## ⚠️ `height` 的 560px 上限太小（用户反馈「划不动」的另一半原因）
			//
			// 改成 `dvh` 兜底后我做了个对比（真机、电脑页签、56 个文件夹）：
			//
			//   只靠 max-height 撑（旧内核路径）→ 弹窗 716px，列表区 373px
			//   有 560px 的 height 上限（现代内核）→ 弹窗 560px，列表区 217px
			//
			// **同一个列表，现代内核反而少 42% 的可视高度**，因为 560 这个
			// 「理想高度」把卡片压住了。而 716px 才是官方 Modal 给的实际可用高度。
			// 所以把理想值提到 720px，让两种内核都接近满高。
			//
			// 底下 430px 左右的矮视口（横屏）仍由 `max-height` 压回来，不受影响。
			dialog:
				'gap:0;width:min(560px,100%);' +
				'max-height:calc(100vh - 48px);' +
				'max-height:calc(100dvh - 48px);' +
				'height:min(720px,calc(100vh - 48px));' +
				'height:min(720px,calc(100dvh - 48px));' +
				'height:min(720px,100%);' +
				'padding:0;display:flex;flex-direction:column;overflow:hidden;',
			// ## ⚠️⚠️ 原来是 `display:contents`，那是个真 bug
			//
			// `display:contents` 会让这个容器**盒模型消失**，三个子块（header /
			// 内容 / footer）直接变成官方 Modal 卡片的 flex 项。看着没问题，实测
			// 却是这样（真机横屏 764×324）：
			//
			//     卡片高 292px
			//     ├─ header  138px（占视口 42%）
			//     ├─ 内容     85px（只放得下 2 行文件夹）
			//     └─ footer   68px
			//
			// 用户报障原话：「要么就是太小了，中间内容看不见；要么就是太大了，
			// 又划不动。」—— 竖屏长列表时 `flex:1 1 auto` 因为父级没有确定高度
			// 算不准，内容把 footer 顶出屏幕（划不动）；横屏时 header 把空间吃光
			// （中间看不见）。
			//
			// 改成真正的 flex 列容器后，`flex:none` / `flex:1 1 auto` 才有意义：
			// header 与 footer 固定、中间那一段占满剩余并可内部滚动。
			editorScope:
				'display:flex;flex-direction:column;flex:1 1 auto;min-height:0;' +
				'width:100%;overflow:hidden;',
			header:
				'display:flex;flex-direction:column;flex:none;' +
				// ## 头部间距随视口高度收缩（横屏 / 小屏的关键）
				//
				// 真机横屏 764×324 实测：标题 28 + 页签 42 + 路径行 28 = 98，
				// 加上下 padding 40 与两个 gap 16，合计 **138px** —— 整整吃掉
				// 292px 卡片高度的 47%，中间列表只剩 85px（放得下 2 行）。
				// 用户报的「中间内容看不见」就是这个。
				//
				// `clamp()` 让间距跟着视口高度线性收缩：视口 ≥520px 时是原值
				//（16/8/8），到 324px 时压到 (10/4/4)，能省出约 30px。
				// 比媒体查询稳 —— 媒体查询里靠后代选择器定位这个节点，
				// 层级一变（比如 `display:contents` 改掉）就静默失效，踩过。
				'gap:clamp(4px,2vh,8px);' +
				'padding:clamp(10px,2.2vh,16px) 14px clamp(4px,1.2vh,8px) 24px;' +
				'border-bottom:.5px solid var(--dsw-alias-border-l3);',
			title:
				'display:flex;align-items:flex-end;min-height:28px;margin:0;' +
				'font-size:16px;font-weight:510;line-height:24px;color:var(--dsw-alias-label-primary);',
			crumbBar: 'display:flex;align-items:center;gap:8px;min-height:24px;',
			// 「显示隐藏文件夹」开关：与「上一级」同排，靠右。
			crumbToggle:
				'display:flex;flex:none;align-items:center;gap:4px;cursor:pointer;' +
				'color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:18px;white-space:nowrap;',
			crumbToggleOn: 'color:var(--dsw-alias-label-secondary);',
			pathText:
				'flex:1 1 0;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' +
				'color:var(--dsw-alias-label-tertiary);font-size:13px;line-height:20px;',
			content:
				// ⚠️ `overflow` 必须是 `auto` 而不是 `hidden`：横屏下中间区只有
				// ~98px，装不下「配对条 + 列表(最少 96px) + 提示语」。`hidden` 会把
				// 超出的部分直接裁掉（用户连滑的机会都没有）；`auto` 让整个中间区
				// 可滚。
				//
				// ⚠️⚠️ **`-webkit-overflow-scrolling:touch` 不能省**（2026-10-28 修）。
				//
				// 用户截图证明「列表明显超出底边却划不动」。真机复现后确认：
				// 在这个容器上，安卓 WebView（华为内核 114）如果不加这条，
				// 虽然 `scrollHeight > clientHeight` 成立、`scrollTop` 也能被
				// JS 改，但**手指的滑动手势不会被识别成滚动** —— 表现在用户
				// 那里就是「按着不动，界面纹丝不动」。
				//
				// 同文件的 `CSS.column`（另一个列表）一直有这条，所以那边从来
				// 没报过划不动。两条列表行为不一致的根源就在这里。
				'display:flex;flex-direction:column;flex:1 1 auto;min-height:0;' +
				'padding:16px 16px 16px 24px;position:relative;overflow-y:auto;' +
				'overscroll-behavior:contain;-webkit-overflow-scrolling:touch;' +
				// 让这个容器成为独立的滚动上下文，避免手势传给祖先。
				'touch-action:pan-y;',
			column:
				'display:flex;flex-direction:column;flex:1 1 auto;gap:2px;min-height:0;' +
				'padding-right:8px;overflow-y:auto;overscroll-behavior:contain;' +
				// 安卓 WebView 里不加这条，长列表的滑动会很「黏」，用户会以为划不动。
				'-webkit-overflow-scrolling:touch;',
			// ## ⚠️ 文件列表：**不要自己滚，交给外层 `content`**
			//
			// 这里踩过一个真机坑（用户原话：「那个界面无法滑动」）。
			//
			// 原来的写法是 `flex-shrink:0` + `overflow-y:auto` 同时存在：
			//   * `flex-shrink:0` ⇒ 列表按内容高度撑开，永远不产生自己的溢出；
			//   * 但它又声明了 `overflow-y:auto` ⇒ 安卓 WebView 认为
			//     「这是个滚动容器」，把落在列表上的触摸手势**拦截在内层**；
			//   * 内层没有可滚距离（它已经撑到全高），于是手势被吃掉，
			//     外层 `content` 收不到滚动事件 ⇒ **手指划不动**。
			//
			// 也就是说：两个属性各自都对，凑在一起就死锁。同一文件里
			// `CSS.column`（第 3790 行那处列表）用的是 `flex:1 1 auto` 且没有
			// `flex-shrink:0`，所以那边一直正常 —— 这里对齐它即可。
			//
			// 保留 `min-height`：横屏 764×324 实测中间区只剩 98px，不给最小
			// 高度会被配对条(62) + 提示语(44) 压成 0px（用户说「一个文件夹都看不到」）。
			// 溢出交给外层 `content` 滚，那才是唯一的滚动容器。
			columnList:
				'display:flex;flex-direction:column;gap:2px;' +
				// ⚠️⚠️ **必须是 `flex:0 0 auto`（不许收缩），且 `overflow:visible`。**
				//
				// 2026-10-28 用户截图（864×1920 竖屏，电脑页签，一屏 14 个文件夹
				// 被裁断在底边、划不动）证明上一版仍然是坏的。上一版写的是
				// `flex:1 1 auto`，我只改了 `overflow`，但**收缩没去干净**：
				//
				//   `flex:1 1 auto` 里的第二个 `1` 是 flex-shrink。
				//   列表被压到容器高度 ⇒ 它自己**不产生溢出**（scrollHeight
				//   == clientHeight）；而 `overflow:visible` 又把多出来的
				//   行「画」到父级之外 —— 但**画出去的部分不计入父级的
				//   scrollHeight**。
				//
				//   于是父级 `content`（overflow-y:auto）算出来
				//   scrollHeight == clientHeight ⇒ 它认为自己没内容可滚
				//   ⇒ 手指滑动被当成「没有可滚区域」，整页纹丝不动。
				//   这正是用户看到的样子：内容明明溢出，却完全划不动。
				//
				// `flex:0 0 auto` 让列表按内容自然撑高，父级的 scrollHeight
				// 才会真的变大，滚动才成立。
				'flex:0 0 auto;min-height:96px;' +
				'padding-right:8px;' +
				'overflow:visible;',
			row:
				'display:flex;align-items:center;gap:4px;width:100%;height:28px;padding:4px;' +
				'border:none;border-radius:var(--dsw-radius-sm);background:0 0;' +
				'text-align:left;cursor:pointer;flex:0 0 auto;',
			rowIcon: 'flex:none;color:var(--dsw-alias-label-secondary);',
			rowName:
				'flex:1 1 0;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' +
				'color:var(--dsw-alias-label-primary);font-size:13px;font-weight:500;line-height:20px;',
			rowChevron: 'flex:none;color:var(--dsw-alias-label-tertiary);',
			status:
				'padding:4px 120px 4px 4px;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-label-secondary);',
			error:
				'padding:4px 120px 4px 4px;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-state-error-primary);word-break:break-all;',
			note:
				'padding:8px 4px 0;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-label-tertiary);',
			footerBar:
				'display:flex;flex-wrap:wrap;flex:none;align-items:center;gap:8px;' +
				'padding:clamp(8px,2vh,16px) 24px;border-top:.5px solid var(--dsw-alias-border-l3);',
			footerGap: 'flex:1 1 0;',
			footerAction: 'min-width:72px;',
			// —— 配对条（只在「电脑」页签下显示）——
			pairBar:
				'display:flex;flex-wrap:wrap;align-items:center;gap:8px;flex:none;' +
				'padding:0 0 8px;',
			// ⚠️ **不能 `white-space:nowrap`**：手机上 360px 宽、这行字有 30 多个汉字，
			// 加了 nowrap + ellipsis 就会被截成「…里生...」—— 用户看不到要去哪一步，
			// 而这一步正是他唯一需要知道的。允许折行。
			pairWho:
				'flex:1 1 100%;min-width:0;' +
				'color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:18px;',
			pairInput: 'width:140px;text-transform:uppercase;letter-spacing:2px;',
			// 解绑二次确认那一行：提示文字占满整行，两个按钮跟在下面（手机上 360px
			// 放不下「文字 + 两个按钮」一行，所以让文字 `flex:1 1 100%` 自己换行）。
			pairConfirm:
				'display:flex;flex-wrap:wrap;align-items:center;gap:8px;' +
				'font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary);',
			pairConfirmText: 'flex:1 1 100%;',
			pairMsg: 'padding:0 4px 8px;font-size:12px;line-height:18px;',
			pairOk: 'color:var(--dsw-alias-state-success-primary);',
			// 「刷新」那一行（模型 / 预设 / 目录树三样一起刷）的回执文字。
			refreshNote:
				'padding:0 4px 8px;font-size:12px;line-height:18px;' +
				'color:var(--dsw-alias-label-tertiary);',
			refreshOk: 'color:var(--dsw-alias-state-success-primary);',
			// —— 「同步对话」对话框 ——
			// 一行一个可勾选的对话。用 `label` 包住，点整行都能勾上（手机上
			// 20px 的复选框太小，只点方框很难点中）。
			syncRow:
				'display:flex;align-items:center;gap:10px;width:100%;min-height:40px;' +
				'padding:8px 4px;border-radius:var(--dsw-radius-sm);cursor:pointer;',
			syncBox: 'flex:none;width:18px;height:18px;accent-color:var(--dsw-alias-brand-primary);',
			syncName:
				'flex:1 1 0;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' +
				'color:var(--dsw-alias-label-primary);font-size:14px;line-height:20px;',
			syncEmpty:
				'padding:24px 4px;text-align:center;font-size:13px;line-height:20px;' +
				'color:var(--dsw-alias-label-tertiary);',
			// 搜索 + 全选那一行。放在列表**上方**（列表可能有 70+ 项，
			// 放下面用户得先滑到底才看得见）。
			syncToolbar:
				'display:flex;align-items:center;gap:8px;padding:0 0 8px;flex:none;',
			syncSearch:
				'flex:1 1 0;min-width:0;height:34px;box-sizing:border-box;padding:0 10px;' +
				'border:.5px solid var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-sm);' +
				'background:var(--dsw-specific-input-major,transparent);' +
				'color:var(--dsw-alias-label-primary);font:inherit;font-size:13px;',
			syncPickAll:
				'flex:none;height:34px;padding:0 12px;border:.5px solid var(--dsw-alias-border-l2);' +
				'border-radius:var(--dsw-radius-sm);background:0 0;cursor:pointer;' +
				'color:var(--dsw-alias-label-secondary);font:inherit;font-size:13px;',
		};

		/**
		 * 把 CSS 文本解析成 React 的 style **对象**。
		 *
		 * ⚠️ **这一步不能省**：React 的 `style` prop 只接受对象，给字符串会当场抛
		 * `Minified React error #62`（React 18 的
		 * `if (style != null && typeof style != "object") throw Error(62)`），
		 * 组件随即被 slot 系统 `abdicate` 退役、渲染回落到官方对话框 ——
		 * 表现就是「电脑」页签根本不出现，而控制台里只有一条难懂的 #62。
		 *
		 * 键名同时做 kebab → camelCase（`min-width` → `minWidth`），否则 React
		 * 会逐条告警「Unsupported style property」。
		 *
		 * @param text - 形如 `'a:1;b:2;'` 的 CSS 声明串。
		 * @returns React style 对象。
		 */
		function toStyle(text) {
			var out = {};
			var parts = String(text).split(';');
			for (var index = 0; index < parts.length; index += 1) {
				var part = parts[index];
				if (part === '') continue;
				var colon = part.indexOf(':');
				if (colon < 0) continue;
				var name = part.slice(0, colon).trim();
				var value = part.slice(colon + 1).trim();
				if (name === '' || value === '') continue;
				out[name.replace(/-([a-z])/g, function (match, letter) {
					return letter.toUpperCase();
				})] = value;
			}
			return out;
		}

		// ⚠️ **先把文本留一份副本再转对象**：下面那个 `<style>` 标签拼的是 CSS
		// **文本**，而第 162 行会把 `CSS` 就地改写成对象 —— 顺序反了就会拼出
		// `[object Object]`，卡片尺寸规则整条失效（列表被 flex 压成 0 高、只剩空白）。
		var DIALOG_CSS_TEXT = CSS.dialog;

		// 就地转成对象：下游一律写 `style: CSS.xxx`，不会再碰到字符串形式。
		for (var styleKey in CSS) CSS[styleKey] = toStyle(CSS[styleKey]);

		/**
		 * 注入官方风格的样式表。
		 *
		 * ⚠️ 卡片那层（背景/圆角/阴影/遮罩）**全部由官方 Modal 自己的 CSS 提供**，
		 * 这里只覆盖尺寸与留白 —— 与官方目录选择器覆盖
		 * `dialog` 类的方式完全一致。所以本插件**不声明任何颜色**，
		 * 亮色/暗色主题都随官方走，不会再出现「一大坨黑」。
		 *
		 * 用 `<style data-plugin-css>` 注入（官方各 client 包的标准做法），
		 * 而不是把样式塞进 React 的 `style` prop —— Modal 只接受 `className`。
		 */
		var STYLE_TAG_ID = 'dsh-jiuling-remote-mobile/DirectoryTabs.css';

		/**
		 * 把本插件的样式表挂进文档。
		 *
		 * ⚠️⚠️ **整段必须包在 try/catch 里，而且不能假设 `document.head` 存在。**
		 *
		 * 这是 **factory 顶层**（不在 `apply()` 里），所以它抛错时：
		 *   1. 我的 `guard()` 兜不住（guard 只管 `apply()` 内部）；
		 *   2. `dsh-client-modules` 会把这次 factory 失败聚合进
		 *      `ClientPackageCompositionError`（`lib/index.js:144`），
		 *      **整批客户端插件一起失败**；
		 *   3. 界面直接停在 `Failed to load plugins`，App 进救援模式并无限重启。
		 *
		 * 而 `document.head` 在脚本处于 `<head>` 内、或文档还没解析到 head 时
		 * 是 `null` —— 那时 `document.head.appendChild(...)` 抛 TypeError，
		 * 正是上面那条链路。**样式挂不上只是不好看，绝不能因此让整个 App 起不来。**
		 *
		 * 所以：拿不到 `head` 就退到 `documentElement`；两者都没有就**静默跳过**。
		 */
		try {
			if (
				typeof document !== 'undefined' &&
				typeof document.createElement === 'function' &&
				document.querySelector('style[data-plugin-css=' + JSON.stringify(STYLE_TAG_ID) + ']') === null
			) {
				var styleTag = document.createElement('style');
				styleTag.dataset.plugin = 'dsh-jiuling-remote-mobile';
				styleTag.dataset.pluginCss = STYLE_TAG_ID;
				styleTag.textContent =
					'.' +
					DIALOG_CLASS +
					'{' +
					DIALOG_CSS_TEXT +
					'}' +
					'.' +
					DIALOG_CLASS +
					' [role="tablist"]{flex:none;}' +
					// ⚠️ 矮视口下收紧头部的规则**不写在这里**，直接写进
					// `CSS.header` / `CSS.footerBar` 的 `clamp()` 里。
					//
					// 原来这里是一条 `@media (max-height:480px){ ... > div > div:first-child ... }`，
					// 靠后代选择器定位 header。而 `editorScope` 从 `display:contents`
					// 改成真 flex 容器后，层级一变，这条选择器就**静默匹配不到**
					//（真机实测横屏 header 仍是 138px，媒体查询完全没生效）。
					// `clamp()` 挂在组件自己的 style 上，层级怎么变都不会失配。
					'.' +
					DIALOG_CLASS +
					' button:hover{background:var(--dsw-alias-interactive-bg-hover);}' +
					/*
					 * ---- 同步进度浮层的关键帧（需求 7）----
					 *
					 * 为什么写在这里而不是行内：`style.animation` 只能引用**已存在的**
					 * `@keyframes`，而 keyframes 没法用行内样式定义，必须有一张
					 * 样式表。插件的这张 `<style>` 就是唯一入口。
					 *
					 * `jlmSyncSpin` —— 顶部加载图标匀速转。
					 * `jlmSyncIndeterminate` —— 未知总量时进度条来回扫：
					 *   用 `translateX` 而非改 `width`，走合成器不触发重排，
					 *   而且首尾用负值/超宽让亮块能完整划过两端、不突然跳回。
					 */
					'@keyframes jlmSyncSpin{' +
					'from{transform:rotate(0deg);}to{transform:rotate(360deg);}' +
					'}' +
					'@keyframes jlmSyncIndeterminate{' +
					'0%{transform:translateX(-100%);}' +
					'50%{transform:translateX(110%);}' +
					'100%{transform:translateX(280%);}' +
					'}';

				// head 可能还不存在（脚本在 <head> 里跑），退到 documentElement。
				var styleParent =
					document.head ||
					document.documentElement ||
					document.body ||
					null;
				if (styleParent !== null && typeof styleParent.appendChild === 'function') {
					styleParent.appendChild(styleTag);
				}
			}
		} catch (styleError) {
			// 样式失败绝不影响功能，更不允许拖垮整批插件加载。
			try {
				console.warn('[dsh-jiuling-remote-mobile] 样式注入失败（已忽略）:', styleError);
			} catch (ignored) {
				/* 连 console 都没有也不能炸 */
			}
		}

		/**
		 * 电脑目录的**访问缓存**：`电脑路径 → listing`（模块级，跨对话框开关存活）。
		 *
		 * ## 为什么必须有（用户报障原文）
		 *
		 * 「为什么我点击添加工作区的时候，每次点击电脑都要重新加载一遍目录，
		 * 这就算了，我加载好之后，随便点击一个文件夹，文件夹里面的内容还要重新加载
		 * 一遍」—— 同一个电脑目录，每次进「电脑」页签、每次点进同一个文件夹，
		 * 都要重走一趟「手机 → Cloudflare 隧道 → 电脑」，一个来回 1～3 秒。
		 * 用户要的是「加载一次，后续直接从本地读」。
		 *
		 * ## 为什么放模块级而不是组件内 ref
		 *
		 * 对话框每次打开都是**新挂载的组件**，组件内的 ref 一关就没了 —— 那样
		 * 「关掉再打开又转一遍圈」，正是用户抱怨的第一个现象。放模块级则一次加载
		 * 整条会话内都命中。
		 *
		 * ⚠️ 键是**电脑路径**原样（`C:\Users\...`），`''` 表示根目录 —— 与
		 * `bridge('listDesktop')` 的入参完全一致，不做任何改写。
		 *
		 * ⚠️ **失效只有两条路**：用户点「刷新」（见 `refreshAll`），
		 * 或者那一次加载失败（见下面的 catch）。不做 TTL 轮询 —— 用户明确要的是
		 * 「加载一次就直接从本地读」。
		 */
		var desktopDirCache = {};

		/** 清掉访问缓存（配对 / 解绑 / 切页签时调用，避免跨电脑串味）。 */
		function clearDesktopDirCache() {
			desktopDirCache = {};
		}

		/**
		 * 哪些会话是「跑在电脑上的」—— 宿主 `remoteSessions` 的本地镜像。
		 *
		 * ## 为什么需要（用户需求原文）
		 *
		 * 「用户如果当前选择的是 **app 本地文件夹**，那么**就不要出现电脑的模型**，
		 * 以这个文件夹为隔离条件，**互不干预**」。
		 *
		 * 宿主把电脑的模型目录与手机本地那份**并**在一起交给前端（原因见宿主
		 * `mergeCatalog` 的注释：官方 `modelCatalog` 是全局唯一一份、不带 sessionId）。
		 * 所以要按工作区隔离，只能在**前端按会话**再筛一次 —— 而筛之前必须先知道
		 * 「这个会话是不是电脑的」。
		 *
		 * ⚠️ **判据必须与宿主 `prompt` 拦截同源**（宿主读的也是 `remoteSessions`）。
		 * 用 cwd 猜镜像目录是另一套判据，一旦两者不一致，就会出现「菜单里显示的是
		 * 电脑模型、消息却发到手机本地」这种最坏情况。
		 *
		 * ⚠️ **不能每次渲染打一次桥**：模型菜单每帧都读目录快照，那是同步 API。
		 * 所以 `apply` 时拉一次全量，之后建会话 / 解绑时自己增量维护，
		 * `getSnapshot` 纯查内存（见宿主 `case 'remoteSessions'` 的注释）。
		 */
		var remoteSessionIds = {};

		/**
		 * 电脑镜像工作区的 slug 集合 —— 隔离的**真正判据**。
		 *
		 * ⚠️⚠️ **为什么不用 `remoteSessionIds`（那是第一代，已被用户报障推翻）。**
		 *
		 * 第一代按 **sessionId** 判定，只在「用户从电脑文件夹对话框里建会话」
		 * 或「宿主拦截器绑过」时才知道。而用户在**侧边栏点「新建会话」**时，
		 * 官方前端直接 `sessionController.create({workspaceId})`，**不经过本插件**；
		 * 宿主的 create 拦截器认得出镜像目录（消息发得对），但前端不知道
		 * ⇒ 模型菜单仍按「本地」筛，显示手机模型。
		 *
		 * 用户原话：「只有我手动添加工作区的时候，才会触发模型和预设的刷新」——
		 * 说的就是判据挂在了「走了哪条路」上，而不是「会话的工作区在哪」。
		 *
		 * 正解：**按 cwd 判定**，与宿主 `desktopPathOfMirror` 同一个轴。
		 * 前端从 `sessions.list` 里同步读到会话的 `cwd`，看它是不是
		 * `…/workspaces/<slug>` 且 slug 属于电脑镜像。这条路与「谁建的会话」
		 * 完全无关，所以**任何入口进来都一致，不需要任何刷新触发**。
		 */
		var mirrorSlugs = {};

		/**
		 * 「用户刚刚在新建对话界面选中的那个工作区」的 cwd —— 只在**会话行还没带上
		 * cwd** 的那一小段时间里当兜底判据用。
		 *
		 * ⚠️⚠️ **为什么需要它（用户报障，2026-10-17 真机复现）**：
		 *
		 * 在**新建对话界面**（还没有任何消息）切到电脑工作区时，`sessions.list`
		 * 里那一行**还没有 cwd**（会话刚物化，宿主没回填），于是
		 * `isRemoteSession()` 只能退回 `remoteSessionIds[sessionId]` —— 而新建的
		 * 本地壳会话**不在里面**，结论是「本地」。后果：
		 *
		 * - 模型菜单只显示手机的模型（电脑的组被 `pickWorkspaceGroups` 筛掉）；
		 * - 预设列表同理，仍是手机那份。
		 *
		 * 用户原话：「我在待选的工作区里面选择了默认工作区，也就是我手机的工作区，
		 * 这个预设它没有改回来，模型列表也没有改回来」——反过来切到电脑时也一样。
		 *
		 * 兜底只影响**这一小段窗口**：一旦列表行带回 cwd，`cwdOf()` 优先，这个值
		 * 自动失效（见 `isRemoteSession` 的判据顺序），不会长期歪掉。
		 */
		var pendingWorkspaceCwd = null;

		/**
		 * `pendingWorkspaceCwd` 的**过期时刻**（毫秒时间戳）。
		 *
		 * ⚠️ 为什么必须有这个：官方工作区下拉菜单走的是
		 * `workspaceNavigation.openWorkspace()`（`ConversationContent` 的
		 * `selectWorkspace`），**完全不经过本插件** —— 所以「用户切回默认工作区」
		 * 这个动作我这边收不到通知，没法在那个时刻清掉 `pendingWorkspaceCwd`。
		 *
		 * 不设过期的话，一旦某次在电脑工作区设过它、而之后那个会话行的 cwd
		 * 又一直读不到，兜底值就会**永久**把「本地会话」判成电脑会话，用户看到
		 * 的正是「切回默认工作区，模型和预设都没改回来」。
		 *
		 * 取 30 秒：足够覆盖「建会话 → 列表行回填 cwd」这一段（真机是几百毫秒），
		 * 又不至于让一个陈旧值活太久。
		 */
		var pendingWorkspaceExpiresAt = 0;

		/** `pendingWorkspaceCwd` 的有效期（毫秒）。 */
		var PENDING_WORKSPACE_TTL = 30000;

		/**
		 * 记下「刚选中的工作区」并开始计时。
		 *
		 * @param cwd - 工作区路径；传 `null`/空串表示**立刻作废**。
		 */
		function setPendingWorkspace(cwd) {
			if (typeof cwd !== 'string' || cwd === '') {
				pendingWorkspaceCwd = null;
				pendingWorkspaceExpiresAt = 0;
				return;
			}
			pendingWorkspaceCwd = cwd;
			pendingWorkspaceExpiresAt = Date.now() + PENDING_WORKSPACE_TTL;
		}

		/**
		 * 取当前有效的兜底 cwd（过期就当没有）。
		 *
		 * @returns 有效的 cwd 或 `null`。
		 */
		function currentPendingWorkspace() {
			if (pendingWorkspaceCwd === null) return null;
			if (Date.now() > pendingWorkspaceExpiresAt) {
				pendingWorkspaceCwd = null;
				pendingWorkspaceExpiresAt = 0;
				return null;
			}
			return pendingWorkspaceCwd;
		}

		/**
		 * 从 cwd 取出镜像工作区的 slug（与宿主 `desktopPathOfMirror` 同构）。
		 *
		 * ⚠️ 用 `/workspaces/` 定位而不是前缀比对：`workspace.path` 是 realpath，
		 * 真机上 `/data/user/0` 与 `/data/data` 会差一层，前缀一比就永远不命中
		 * （宿主那边踩过这个坑，注释见 `desktopPathOfMirror`）。
		 * 两种分隔符都认：手机是 posix，但在 Windows 上调试时是 `\`。
		 *
		 * @param cwd - 会话的 cwd。
		 * @returns 镜像工作区的 slug；不是镜像目录时 `undefined`。
		 */
		function mirrorSlugOf(cwd) {
			if (typeof cwd !== 'string' || cwd === '') return undefined;
			var at = cwd.search(/[\\/]workspaces[\\/]/);
			if (at < 0) return undefined;
			var slug = cwd.slice(at).split(/[\\/]/)[2];
			return slug === undefined || slug === '' ? undefined : slug;
		}

		/**
		 * 这个会话是不是跑在电脑上 —— **同步**判定，任何入口都用同一条。
		 *
		 * ⚠️ **cwd 读得到时，它以 cwd 为唯一判据**（不看 `remoteSessionIds`）：
		 * 工作区就是隔离条件本身。解绑后 `mirrorSlugs` 清空 ⇒ 全部回到本地，
		 * 不需要再去清每个会话的旧标记。
		 *
		 * 只有列表行还没带 cwd 的那一两帧（会话刚建好）才退回
		 * `pendingWorkspaceCwd` / `remoteSessionIds`。
		 *
		 * ⚠️ **判据顺序不能换**：
		 * 1. `cwdOf(sessionId)` —— 会话自己的 cwd，最准；
		 * 2. `pendingWorkspaceCwd` —— 用户在新建界面刚选的工作区（见它的注释，
		 *    这是修「新建界面模型/预设不跟随工作区」的那一条）；
		 * 3. `remoteSessionIds` —— 宿主显式标记过的会话。
		 *
		 * 第 2 条只在第 1 条读不到时才生效，所以一旦会话行带回 cwd，
		 * 它就自动让位，不会跟真实 cwd 打架。
		 *
		 * @param sessionId - 会话 id。
		 * @param cwdOf - `sessionId → cwd` 的同步读取函数。
		 * @returns 是不是电脑工作区的会话。
		 */
		function isRemoteSession(sessionId, cwdOf) {
			var cwd = cwdOf === undefined ? undefined : cwdOf(sessionId);
			if (typeof cwd === 'string' && cwd !== '') {
				return isMirrorPath(cwd);
			}
			// 会话自己的 cwd 还没进列表行 —— 用「刚选中的工作区」兜底（会过期）。
			var pending = currentPendingWorkspace();
			if (pending !== null) {
				return isMirrorPath(pending);
			}
			return remoteSessionIds[sessionId] === true;
		}

		/**
		 * 一条路径是不是电脑镜像工作区 —— **模型与预设共用的唯一判据**。
		 *
		 * ## ⚠️⚠️ 为什么必须有这个函数（1.2.85 修「模型列表永不同步」）
		 *
		 * 这是用户报了十几个版本的那个 bug 的真正根因，而且根因是
		 * **两套判据不一致** —— 不是「刷新没触发」，也不是「缓存没作废」。
		 *
		 * 「这一屏该用电脑的模型还是手机的模型？」这个问题在代码里有**两份**
		 * 答案，跑在两个不同的地方：
		 *
		 * | 谁在用 | 函数 | 判据 |
		 * |---|---|---|
		 * | **预设** 列表 | `currentIsRemote()` | `mirrorSlugs[slug]` **或** 路径形态 |
		 * | **模型** 列表 | `isRemoteSession()` ← 本函数的老版本 | **只有** `mirrorSlugs[slug]` |
		 *
		 * 差的就是那条路径形态兜底。而 `mirrorSlugs` 是
		 * `loadMirrorSlugs()` 经隧道异步拉回来的**快照**：
		 *
		 *   · 刚启动 / 刚配对的那一小段时间里它是 **空对象**；
		 *   · App 重启后、桥还没回来之前也是空的；
		 *   · 桥调用失败（电脑睡了、隧道抖一下）时**保持原样**（见那个 catch）。
		 *
		 * 空对象时 `mirrorSlugs[slug] === true` 恒为 `false` ⇒ 电脑工作区被判成
		 * 手机本地 ⇒ `pickWorkspaceGroups` 把它们筛成「只留手机那几组」
		 * ⇒ **模型菜单里永远是手机本地的模型**。
		 *
		 * 而预设走 `currentIsRemote()`，它有路径形态兜底 ⇒ 预设是对的。
		 * 这正是用户从第一个版本就在说的那句话：
		 *
		 *   「预设同步了，但模型列表不同步」
		 *
		 * 之前十来轮都在猜「哪一环没刷新」（刷新钩子、TTL、bust、句柄转发），
		 * 那些确实是真 bug 也修了，但它们都作用在**已经进入正确分支之后**。
		 * 而这里判错分支 ⇒ 后面刷新多少次都白搭：刷出来的电脑分组
		 * 立刻被这个判据筛掉。
		 *
		 * **修法：把两份答案收成一份。** 模型与预设此后都调本函数，
		 * 判据按 `currentIsRemote()` 那套（它经历的真机验证更多）。
		 *
		 * ## 为什么路径形态可以单独作准
		 *
		 * 电脑镜像工作区由宿主 `ensureDesktopWorkspace()` **统一建在**
		 * `<DSH_HOME>/remote/workspaces/<slug>/` 下，路径形态是宿主自己定的，
		 * 用户不会手建到那里去。所以「路径里带 `remote/workspaces/`」这件事，
		 * 与 slug 表是否已加载**无关**、也与我们记不记得住它**无关**。
		 *
		 * ⚠️ 别写成 `dsh-remote/workspaces/` —— 那是**状态目录**的名字
		 * （`<DSH_HOME>/dsh-remote/`，放 `mobile.json` / `sessions.json`），
		 * 不是镜像工作区所在的 `remote/workspaces/`。两者只差一个 `dsh-` 前缀，
		 * 写错就是「电脑工作区被判成手机」，实测踩过（见 `currentIsRemote`）。
		 *
		 * @param path - 会话 / 工作区的本地路径。
		 * @returns 是不是电脑镜像目录。
		 */
		function isMirrorPath(path) {
			if (typeof path !== 'string' || path === '') return false;
			// ① 精确匹配宿主的 slug 表（最权威：它就是"哪些目录被映射过"的账本）。
			var slug = mirrorSlugOf(path);
			if (slug !== undefined && mirrorSlugs[slug] === true) return true;
			// ② slug 表可能是**空快照**（还没拉到 / 桥失败）——
			//    用宿主自己定的目录形态兜底，与 `currentIsRemote()` 完全同一条。
			if (path.indexOf('remote/workspaces/') !== -1) return true;
			if (path.indexOf('remote\\workspaces\\') !== -1) return true;
			return false;
		}

		/** 从宿主拉一次全量远程会话清单（失败就按「全是本地」处理）。 */
		function loadRemoteSessionIds() {
			return bridge('remoteSessions', {}).then(
				function (result) {
					var ids = result && result.ids;
					if (!Array.isArray(ids)) return;
					var next = {};
					for (var at = 0; at < ids.length; at += 1) next[ids[at]] = true;
					remoteSessionIds = next;
				},
				function () {
					/* 拉不到就保持原样：最坏是本地工作区多看到电脑模型，不会误发 */
				},
			);
		}

		/**
		 * 从宿主拉一次「电脑镜像工作区的 slug 集合」（隔离的真正判据）。
		 *
		 * 拉不到就保持原样（空集合 = 全部按本地处理）：最坏是本地工作区多看到
		 * 电脑模型，**不会误发** —— 消息该发给谁由宿主 `prompt` 拦截器按
		 * `remoteSessions` 决定，与这里无关。
		 */
		function loadMirrorSlugs() {
			return bridge('mirrorSlugs', {}).then(
				function (result) {
					var slugs = result && result.slugs;
					if (!Array.isArray(slugs)) return;
					var next = {};
					for (var at = 0; at < slugs.length; at += 1) next[slugs[at]] = true;
					mirrorSlugs = next;
				},
				function () {
					/* 同上：最坏是多显示几项，不会误发 */
				},
			);
		}

		/**
		 * 从**新建对话页的工作区选择器**读出「用户此刻选中的工作区名」。
		 *
		 * ## 为什么需要这个（2026-10-29 修 bug）
		 *
		 * 用户报障：「我在主界面就可以直接选择你已经同步的某个工作区来进行新建
		 * 对话，然后我发现如果我直接选择某个已存在的工作区的话，你电脑的那些预设呀
		 * 还有模型列表都没办法刷新显示出来」。
		 *
		 * 数据侧的来源全都读不到：
		 *   · `currentSessionId()` —— 新建页**没有会话**，官方在
		 *     `dsh-client-ui-workspace` 的注释里也这么写；
		 *   · `uiWorkspace.selection` —— 空快照（它只存 `sessionId`）；
		 *   · 工作区快照的 `sessionIds` 反查 —— 新会话还没建，倒查不到。
		 *
		 * 唯一**此刻就准确**的信号，就是界面上那个工作区选择器本身
		 * （用户截图里 hero 区左侧那个 `Desktop ▾`）。读它的文本，再和宿主的
		 * 镜像工作区清单比对 —— 清单由 `mirrorWorkspaces` 桥提供，
		 * 标题就是电脑目录末段，正好能和选择器上的文字对上。
		 *
		 * ## 容错
		 *
		 * 读不到（选择器没渲染、文本空、清单没拉到）一律返回 `null`，
		 * 调用方据此保持原来的判断 —— **绝不能因为探测失败就把用户误判成电脑**。
		 *
		 * @returns 电脑路径；判不出来时 `null`。
		 */
		function probeHeroWorkspaceDesktopPath() {
			if (typeof document === 'undefined' || document === null) return null;
			try {
				/*
				 * 找按钮形态的工作区选择器：
				 *   · `[aria-haspopup]` —— 官方这颗 chip 是下拉触发器，带这个属性；
				 *   · 且文本里带 `▾` 或 `⌄`（官方用它们表示可展开）。
				 * 两条同时满足，避免把侧边栏工作区行误当成它。
				 */
				var nodes = document.querySelectorAll('[aria-haspopup]');
				var title = '';
				for (var at = 0; at < nodes.length; at += 1) {
					var node = nodes[at];
					var text = String(node.textContent || '').replace(/[\u25be\u2304\u25b4]/g, '').trim();
					if (text === '') continue;
					/*
					 * 侧边栏的工作区行是 `role="treeitem"`，不是 `aria-haspopup`，
					 * 但保险起见再排一次：带 treeitem 的不要。
					 */
					if (node.getAttribute('role') === 'treeitem') continue;
					if (node.closest !== undefined && node.closest('[role="treeitem"]') !== null) continue;
					/*
					 * ⚠️ **必须排除模型选择器** —— 它也是 `aria-haspopup` 的下拉触发器
					 * （真机上就排在旁边：`deepseek-v4.1-flash 默认 ▾`）。
					 * 若它先被命中，就会拿模型名去比对镜像清单，永不命中 ⇒
					 * 探测恒为 null，等于这整个修复白做。
					 *
					 * 判据取模型 chip 的两个稳定特征：
					 *   · 文本里含 `默认` / `Default`（官方在未显式选择时追加）；
					 *   · 或含 `模式`（`标准模式 ▾` 那颗）。
					 * 目录名恰好叫这俩的概率极低，且即使误排除也只是回到原行为
					 *（判不出来 ⇒ 按本地处理），不会误判成电脑。
					 */
					if (text.indexOf('默认') >= 0 || text.toLowerCase().indexOf('default') >= 0) continue;
					if (text.indexOf('模式') >= 0) continue;
					title = text;
					break;
				}
				if (title === '') return null;
				/*
				 * 与镜像清单比对。清单在这里是**同步缓存**（`mirrorDesktopTitles`），
				 * 由 `loadMirrorDesktopTitles()` 在启动/配对后填充。
				 */
				var plain = title.split('\u200B').join('');
				for (var mt = 0; mt < mirrorDesktopTitles.length; mt += 1) {
					var item = mirrorDesktopTitles[mt];
					if (item === null || typeof item !== 'object') continue;
					if (typeof item.title !== 'string' || item.title === '') continue;
					var itemTitle = item.title.split('\u200B').join('');
					if (itemTitle === plain) return item.desktopPath;
				}
				return null;
			} catch (error) {
				// DOM 结构变化、选择器失效、权限异常 —— 一律当成"判不出来"。
				return null;
			}
		}

		/**		 * 镜像工作区的「标题 → 电脑路径」清单（`probeHeroWorkspaceDesktopPath` 用）。
		 *
		 * 与组件内的 `mirrorWorkspacesRef` 是同一份数据的两个视图：那边给菜单用、
		 * 这边给同步判定用。分开是因为两者作用域不同（本文件里 `patchAgentPresets`
		 * 是模块级函数，拿不到组件内的 ref）。
		 */
		var mirrorDesktopTitles = [];

		/**
		 * 强制刷新预设菜单的句柄（由组件内部回填）。
		 *
		 * ## 为什么需要一个 ref 转发
		 *
		 * `runAutoSync` 是**模块级**函数，而 `resolveSeatLoad()` 依赖
		 * `apply()` 里的 `ctx.slots` —— 模块级函数拿不到。
		 *
		 * 所以组件内部把能用的那个函数存这里，
		 * `runAutoSync` 同步完成时调它 —— 这是「模块级代码调组件内能力」
		 * 的标准做法（与 `mirrorWorkspacesRef` 同一模式）。
		 */
		var forceRefreshPresetsRef = { value: null };

		/**
		 * 「**直接把新 roster 写进缓存**」的句柄（由组件内部回填）。
		 *
		 * ## 为什么需要它（1.2.37 补的兜底路）
		 *
		 * `forceRefreshPresetsRef` 走的是官方 `seat.load()`。那条路有两个前提：
		 * chip 已注册、官方那次 `load()` 真的拿到新数据。任一不成立，
		 * 用户看到的就还是旧预设 —— 我已经在这个假设上改了四轮。
		 *
		 * 这个句柄走**另一条完全独立的**路：自己经 `bridge('mergedPresetList')`
		 * 把宿主合并好的 roster 拉回来，直接赋给 `rosterCache`。
		 *
		 * 它不依赖 slot、不依赖事件、不依赖官方任何内部实现。
		 * 即使官方那条路彻底失效，这条也能保证「缓存里是新数据」。
		 */
		var pullRosterIntoCacheRef = { value: null };
		
		/**
		 * 「通知 **前端**模型目录重读」的句柄（1.2.69 新增）。
		 *
		 * ## 为什么必须有这一层（这是 ① 的最后一块拼图）
		 *
		 * 模型目录有**两份完全独立的缓存**，跑在两个进程里：
		 *
		 *   宿主侧（Node）：`index.js` 里 `ctx.get('modelDirectories')`
		 *                        ⇒ `refreshModelCatalogDetail()` 刷新的是它
		 *   前端（WebView）：`client.js` 里 `props.modelDirectories`
		 *                        ⇒ `reloadModelCatalog()` 刷新的是它
		 *
		 * 两段代码虽然**字面上一模一样**（都是 `xxx.catalog.refresh()`），
		 * 但它们操作的是两个进程里的两个不同对象。
		 * 宿主侧刷新了，WebView 侧**不会自动跟着刷新**。
		 *
		 * 而 `reloadModelCatalog()` 原来**只在配对成功时调一次**（见配对那段）。
		 * 同步完成时只刷了宿主侧 ⇒ 界面读的还是旧的。
		 * 用户看到：**同步完成了，模型菜单还是旧的。**
		 *
		 * 这个句柄就是把前端那一次刷新正式接到同步完成上。
		 * 和 `pullRosterIntoCacheRef` / `forceRefreshPresetsRef` 完全同构。
		 */
		var reloadFrontendModelCatalogRef = { value: null };
		/**
		 * 「重拉电脑侧展示元数据（真实时间 + 置顶）」的句柄。
		 *
		 * 与 `reloadFrontendModelCatalogRef` 完全同构 —— 因为
		 * 同步完成那段代码在**组件内层**，而 `loadDesktopMeta` 定义在
		 * `apply()` 的外层作用域，直接引用会形成跨层依赖且不好测试。
		 *
		 * 用途见 `runAutoSync` 成功分支里那段注释：同步刚建完一批会话，
		 * 宿主同时也刚记好它们的时间和置顶标记，前端必须立刻拿到。
		 */
		var refreshDesktopMetaRef = { value: null };
		/*
		 * 强制重算模型目录投影的句柄（1.2.82 加）。
		 *
		 * 预设那边有 `pullRosterIntoCacheRef`（直接 `bridge('mergedPresetList')`
		 * 写缓存，**不依赖任何 RPC 成功**），所以预设能刷新；
		 * 模型只有 `reloadFrontendModelCatalogRef`（调官方 `catalog.refresh()`，而它
		 * 内部 `load().catch(() => {})` 把错误全吃掉）。
		 *
		 * 失败时官方保留旧 `value`、只把 status 置成 error
		 * ⇒ 源快照**身份不变** ⇒ 我们那层 `wrapStore` 命中缓存
		 * ⇒ 界面永远显示旧模型列表。这就是用户看到的
		 * 「预设刷新了、模型没刷新」。
		 *
		 * 这个 ref 让组件把 `bust()` 交给模块级，同步完成后无条件作废一次缓存。
		 */
		var bustModelCatalogRef = { value: null };

		/**
		 * 最近一次同步后**实际拉到的预设数量**（真机可见诊断）。
		 *
		 * 模块级，因为渲染浮层的函数也是模块级的，看不见 `apply()` 里的变量
		 * —— 这正是本文件反复踩的闭包作用域坑。
		 *
		 * 结构：`{ presets, desktopCount, reason }`
		 *   · `presets === -1` ⇒ 没拉到，`reason` 说明卡在哪一步；
		 *   · `presets >= 0`   ⇒ 拉到了，数字就是条数。
		 *
		 * 为什么需要它：①这个 bug 改了 10 轮都没真机确认，问题集中在
		 * 「数据到底有没有到手机」。这个数字能一句话分开两个完全不同的方向。
		 */
		/**
		 * 日志出口（**模块级**，1.2.70 提上来）。
		 *
		 * ## 为什么必须在模块级
		 *
		 * 原来它定义在 `installSyncMenu(ctx)` 内部（行 7462，范围 7444~8320），
		 * 但下面三个**模块级**函数也在调它：
		 *
		 *     showPromptFailure（行 8396）
		 *     showAskCard（行 8465）
		 *     异常回传那段（行 8574）
		 *
		 * 三处全在 8320 之后 ⇒ 必然 `ReferenceError: logWarn is not defined`。
		 * 而它们又都在 `catch` 里，所以平时不发作、一发作就把
		 * 原始错误盖掉 —— 用户看到的是一个不相干的错误名。
		 *
		 * ## 为什么不能用可选链
		 *
		 * `ctx.logger?.warn?.(...)` 是 chrome80+ 语法，本文件受 chrome73 约束。
		 * 旧 WebView 会直接 SyntaxError ⇒ 整个文件不执行 ⇒ 插件像没装。
		 * 所以必须先判类型再调。
		 *
		 * ## 为什么接受一个 ctx 参数
		 *
		 * 不同调用点的 `ctx` 与原来一样（都是它们自己手里的那个），
		 * 所以行为不变，只是可见范围变大了。
		 */
		/*
		 * 插件版本号（显示用）。
		 *
		 * ⚠️ 发版时必须与 `package.json` 的 `version` 一致。
		 * 不能从文件读：这个文件跑在 WebView 里，没有 fs。
		 * 又不愿意为一个显示用的字符串多一次桥调用。
		 * 写成常量，并在自测里断言它与 package.json 一致。
		 */
		var PLUGIN_VERSION = '1.2.87';
		
		/**
		 * 配对条上的版本小标签。
		 *
		 * ⡠ 什么也拿不到时返回 null —— 不显示，也不炸。
		 */
		/*
		 * 侧栏徽标自检行（1.2.82 新增）。
		 *
		 * ## 为什么要把这个显示出来
		 *
		 * 用户反复报「侧边栏电脑标记不显示」，而代码路径逐段看都对：
		 * 识别用的是 `data-row-key`（官方确定性标识）、徽标四级降级全备、
		 * 容器尺寸颜色都完整。光看代码已经定不了位了。
		 *
		 * 而用户说过「你知道我测试一次成本很大的」、「把错误
		 * 原因显示在 App 界面里，我看一张截图就知道」。所以把实测值
		 * 直接写在配对条下面：一张截图就能区分是哪一种坏法。
		 *
		 * 读的是 `window.__dshRemoteSidebarDiag`（由 `syncOnce` 每 1.5s 写一次）。
		 * 读不到就不显示 —— 不能因为诊断影响主流程。
		 */
		/**
		 * 排序 / 置顶诊断快照。
		 *
		 * 用户报障两轮都是「数据到了但界面没变」，而这种事靠猜永远猜不准。
		 * 所以把三个关键环节的**实测值**显示在界面上，一张截图就能定位断在哪一步：
		 *
		 *   `times`  実主给了多少条电脑真实时间（为 0 说明账本没补上）
		 *   `applied` 有几个会话的 `updatedAt` 真被覆盖（为 0 说明没命中）
		 *   `hook`   hook 引用是否已换成包装后的对象（否则界面读的还是旧数据）
		 *   `pins`   电脑上置顶的数量 / 已 pin 成功的数量 / 失败原因
		 */
		var clockDiag = {
			times: 0,
			applied: 0,
			pins: 0,
			pinned: 0,
			hook: '-',
			pinErr: '',
		};
		var pinErrorText = '';

		var sidebarDiagTag = function () {
			try {
				var d = window.__dshRemoteSidebarDiag;
				if (d === undefined || d === null || typeof d.rows !== 'number') return null;
				var box = d.box;
				var parts = [
					'电脑行 ' + d.rows,
					'徽标 ' + d.badges,
					'隐藏 ' + d.folderHidden,
				];
				if (box !== null && box !== undefined) parts.push('尺寸 ' + box.w + '×' + box.h);
				if (d.err !== null && d.err !== undefined) parts.push('错 ' + String(d.err).slice(0, 30));
				/*
				 * 排序 / 置顶诊断行。
				 *
				 * 格式：时间 N/覆盖 N | 置顶 N/已 N | hook ✓/✗
				 * 一眼就能看出是「没数据」「没命中」还是「hook 没换」。
				 */
				parts.push(
					'时间 ' + clockDiag.times + '/' + clockDiag.applied,
					'置顶 ' + clockDiag.pins + '/' + clockDiag.pinned,
					'hook ' + clockDiag.hook,
				);
				if (pinErrorText !== '') parts.push('置顶错 ' + pinErrorText.slice(0, 40));
				var text = '侧栏：' + parts.join(' · ');
				return h('div', {
					style: {
						fontSize: '11px',
						lineHeight: '15px',
						color: 'var(--dsw-alias-label-tertiary, #888)',
						marginTop: '2px',
						wordBreak: 'break-all',
					},
					title: '侧栏徽标自检（每 1.5 秒刷新）',
				}, text);
			} catch (diagTagError) {
				return null;
			}
		};

		var versionTag = function () {
			try {
				if (typeof PLUGIN_VERSION !== 'string' || PLUGIN_VERSION === '') return null;
				return h(
					'span',
					{
						style: {
							fontSize: '10px',
							opacity: '0.55',
							marginLeft: '6px',
							whiteSpace: 'nowrap',
						},
						title: 'dsh-jiuling-remote-mobile ' + PLUGIN_VERSION,
					},
					'v' + PLUGIN_VERSION,
				);
			} catch (versionError) {
				return null;
			}
		};
		
		var logWarn = function (message, ctx) {

			try {
				if (ctx && ctx.logger && typeof ctx.logger.warn === 'function') {
					ctx.logger.warn(message);
				} else if (typeof console !== 'undefined' && console && typeof console.warn === 'function') {
					/*
					 * 没传 ctx 时退到 console。
					 *
					 * 这不是多余：上面三个调用点手里**没有**可用的 ctx
					 *——`showPromptFailure(ctx, ...)` 虽然有 ctx 参数，但那个 ctx
					 * 是官方传入的插件上下文，不一定有 logger。
					 * 宁可写到 console（真机 adb logcat 可见），也不能打不出日志。
					 */
					console.warn(message);
				}
			} catch (ignored) {
				/* 连日志都打不出来也不能炸 */
			}
		};
		
		var syncDiagRef = { value: null };


		/**
		 * 从请求体里读 `workspaceId`，判断它是不是**电脑镜像工作区**。
		 *
		 * ## 为什么这是最可靠的判据（2026-10-29）
		 *
		 * 用户报障：「我点击电脑工作区，或者我直接在侧边栏里面的电脑工作区行的
		 * 右边按钮点击新建对话，然后新建对话的界面的模型列表和预设列表都没有
		 * 加载过来」。
		 *
		 * 前几版试过两条路，都不行：
		 *
		 *   ① `cwdOf(undefined)` —— `makeCwdOf` 第一行就因 `sessionId` 不是
		 *      字符串而返回，走不到退路链；
		 *   ② DOM 探测 hero chip（`probeHeroWorkspaceDesktopPath`）——
		 *      真机截图证明**新建对话页根本没有工作区 chip**，只有底部的
		 *      模型 chip 和输入框，所以探测恒为 null。
		 *
		 * 而前端调 `api/agentPresets/list` / `api/session/modelCatalog` 时，
		 * 请求体里**一定**带着它当前认定的 `workspaceId`（那是它筛预设、
		 * 筛模型的作用域参数）。所以直接从请求体读它、再拿镜像清单反查 ——
		 * 精确、与 DOM 结构无关、与有没有 sessionId 无关。
		 *
		 * @param body - 已解析的请求体（`client-request` 信封）。
		 * @returns 是电脑镜像工作区 `true`；不是 `false`；判断不了 `null`。
		 */
		function workspaceIdIsDesktopMirror(body) {
			try {
				if (body === null || typeof body !== 'object') return null;
				/*
				 * 信封形态是 `{type:'client-request', rpcId, method, payload}`。
				 * `workspaceId` 可能落在 `payload` 里，也可能直接在顶层
				 * （不同前端版本的封装不一样），两个都查，成本为零。
				 */
				var payload = body.payload;
				var candidates = [];
				if (payload !== null && typeof payload === 'object') {
					candidates.push(payload.workspaceId);
					if (payload.address !== null && payload.address !== undefined &&
						typeof payload.address === 'object') {
						candidates.push(payload.address.workspaceId);
					}
				}
				candidates.push(body.workspaceId);
				var target = null;
				for (var at = 0; at < candidates.length; at += 1) {
					var value = candidates[at];
					if (typeof value === 'string' && value !== '') {
						target = value;
						break;
					}
				}
				// 请求里没带 workspaceId ⇒ 无从判断，交给调用方按原逻辑走。
				if (target === null) return null;
				var list = Array.isArray(mirrorDesktopTitles) ? mirrorDesktopTitles : [];
				for (var mt = 0; mt < list.length; mt += 1) {
					var item = list[mt];
					if (item === null || typeof item !== 'object') continue;
					if (typeof item.workspaceId === 'string' && item.workspaceId === target) return true;
				}
				return false;
			} catch (error) {
				// 任何异常都当成"判断不了"，绝不因此让预设列表报错。
				return null;
			}
		}

		/** 从宿主拉一次镜像工作区清单（标题 + 电脑路径）。 */
		function loadMirrorDesktopTitles() {
			return bridge('mirrorWorkspaces', {}).then(
				function (result) {
					var list = result && result.workspaces;
					if (!Array.isArray(list)) return;
					var next = [];
					for (var at = 0; at < list.length; at += 1) {
						var item = list[at];
						if (!item || typeof item.desktopPath !== 'string' || item.desktopPath === '') continue;
						next.push({ title: String(item.title === undefined ? '' : item.title), desktopPath: item.desktopPath });
					}
					mirrorDesktopTitles = next;
				},
				function () {
					/* 拉不到就保持空清单：探测会一律返回 null，不影响原有判断 */
				},
			);
		}

		/**
		 * 按「这个会话是不是电脑的」筛出该显示的组与失败项。
		 *
		 * ## 双向隔离
		 *
		 * - **本地工作区**：只留手机自己的组，并把被电脑同 id provider 覆盖掉的那组
		 *   **还原**回来（`localGroups`，宿主 `mergeCatalog` 额外带上的）。
		 * - **电脑工作区**：只留电脑的组。这不是「顺手加活」—— 电脑会话的 prompt 会
		 *   转发到电脑，选一个手机本地才有的模型（比如 `agnes-3.0-flash`）发过去，
		 *   电脑上根本不存在，必然报错。用户说的「互不干预」正是这个意思。
		 *
		 * ⚠️ 没配对时目录里根本没有 `desktop` 标记，两个分支都会原样返回。
		 *
		 * ⚠️ **`extraLocal` 是必需的**：官方的 `syncInputs` 往 `directory.store`
		 * 里只拷了 `groups` / `failures`（见
		 * `dsh-client-ui-model-selection/lib/client.js:325`），**`localGroups` 不在
		 * 那一层**。所以筛 `directory.store` 时得由调用方把 `localGroups`
		 * 从 catalog 原值里递进来，否则本地工作区永远还原不出被覆盖的那组。
		 *
		 * @param value - 目录值（`{groups, failures, localGroups?, localFailures?}`）。
		 * @param isRemote - 这个会话是不是跑在电脑上。
		 * @param extraLocal - 备用本地组（`value.localGroups` 缺失时用）。
		 * @returns `{groups, failures}`（`value` 不合法时原样返回空数组）。
		 */
		function pickWorkspaceGroups(value, isRemote, extraLocal) {
			if (!value || !Array.isArray(value.groups)) {
				return { groups: [], failures: [] };
			}
			const groups = value.groups;
			const failures = Array.isArray(value.failures) ? value.failures : [];
			if (isRemote === true) {
				return {
					groups: groups.filter(function (group) { return group.desktop === true; }),
					failures: failures.filter(function (failure) { return failure.desktop === true; }),
				};
			}
			const kept = groups.filter(function (group) { return group.desktop !== true; });
			// 还原被电脑同 id 覆盖掉的本地组（见宿主 `mergeCatalog`）。
			const local = Array.isArray(value.localGroups)
				? value.localGroups
				: (Array.isArray(extraLocal) ? extraLocal : []);
			if (local.length > 0) {
				const have = {};
				for (let at = 0; at < kept.length; at += 1) have[kept[at].id] = true;
				for (let lt = 0; lt < local.length; lt += 1) {
					if (have[local[lt].id] !== true) kept.push(local[lt]);
				}
			}
			return {
				groups: kept,
				failures: failures.filter(function (failure) { return failure.desktop !== true; }),
			};
		}

		/**
		 * 按工作区筛**预设名单**（与 `pickWorkspaceGroups` 同构）。
		 *
		 * 用户报障：「模型列表能够快速切换回来了，但是预设列表没有切换」——
		 * 模型能切是因为它走了 `pickWorkspaceGroups`，预设没切是因为宿主
		 * `mergePresets` 只是把两份并起来、前端没人再筛一次。
		 *
		 * 判据与模型**完全同一条**（`isRemoteSession`）：会话在电脑工作区 ⇒ 只留
		 * 电脑那几条；否则只留本地那几条，并把被同 id 覆盖掉的本地项还原回来。
		 *
		 * ⚠️ **一条都不剩时原样返回**：电脑那边预设全坏了、或者宿主还没把
		 * `desktop` 标记打上（老版本宿主）时，宁可多显示几条，也别把菜单变成空的
		 * —— 空菜单用户连「默认」都选不回来。
		 *
		 * @param value - `{presets, localPresets?}`（宿主 `mergePresets` 的产物）。
		 * @param isRemote - 这个会话是不是跑在电脑上。
		 * @returns 筛过的 `{presets}`（`value` 不合法时原样返回）。
		 */
		function pickWorkspacePresets(value, isRemote) {
			if (!value || !Array.isArray(value.presets)) return value;
			const all = value.presets;
			// ⚠️ 宿主没打标记（老版本宿主 / 还没合并过）⇒ 无从判断，原样返回。
			let tagged = false;
			for (let at = 0; at < all.length; at += 1) {
				if (all[at] && all[at].desktop === true) { tagged = true; break; }
			}
			if (!tagged) return value;
			if (isRemote === true) {
				const desktopOnly = all.filter(function (preset) { return preset.desktop === true; });
				if (desktopOnly.length === 0) return value;
				return Object.assign({}, value, { presets: desktopOnly });
			}
			const kept = all.filter(function (preset) { return preset.desktop !== true; });
			// 还原被电脑同 id 覆盖掉的本地预设（见宿主 `mergePresets`）。
			const local = Array.isArray(value.localPresets) ? value.localPresets : [];
			if (local.length > 0) {
				const have = {};
				for (let kt = 0; kt < kept.length; kt += 1) have[kept[kt].id] = true;
				for (let lt = 0; lt < local.length; lt += 1) {
					if (have[local[lt].id] !== true) kept.push(local[lt]);
				}
			}
			if (kept.length === 0) return value;
			return Object.assign({}, value, { presets: kept });
		}

		/**
		 * 从共享的 catalog 原值里取「手机本地的组」（宿主 `mergeCatalog` 带的）。
		 *
		 * @param directory - 官方的 `ModelDirectory`（含 `.catalog`）。
		 * @returns 本地组数组；拿不到就空数组。
		 */
		function localGroupsOf(directory) {
			try {
				// ⚠️ 不用可选链：本文件受 chrome73 语法约束（见文件头）。
				if (!directory || !directory.catalog || !directory.catalog.store) return [];
				const store = directory.catalog.store;
				if (typeof store.getSnapshot !== 'function') return [];
				const value = store.getSnapshot().value;
				return value && Array.isArray(value.localGroups) ? value.localGroups : [];
			} catch (error) {
				return [];
			}
		}

		/** 某个 `provider/model` 在给定的组里存不存在。 */
		function isSelectable(groups, selection) {			if (!selection) return false;
			for (let at = 0; at < groups.length; at += 1) {
				if (groups[at].id !== selection.provider) continue;
				const models = Array.isArray(groups[at].models) ? groups[at].models : [];
				for (let mt = 0; mt < models.length; mt += 1) {
					if (models[mt].id === selection.model) return true;
				}
			}
			return false;
		}

		/**
		 * 在给定的组里挑一个**真实可用**的模型选择。
		 *
		 * 用途见 `scopeDirectoryToWorkspace` 里那段 ⚠️⚠️ 注释：`current` 被清空后
		 * 必须立刻回填，否则官方会把它读成「正在加载模型…」并永久卡住。
		 *
		 * 优先级：
		 * 1. `preferred`（通常传 `snapshot.default`）—— 官方自己就用它当兜底，
		 *    跟着它走语义最一致；
		 * 2. 否则第一个组的第一模型。
		 *
		 * @param groups - 筛过的组数组。
		 * @param preferred - 优先尝试的选择（可能已被筛掉）。
		 * @returns `{provider, model}`；一组都没有时 `null`。
		 */
		function pickFallbackSelection(groups, preferred) {
			if (!Array.isArray(groups) || groups.length === 0) return null;
			if (isSelectable(groups, preferred)) {
				return { provider: preferred.provider, model: preferred.model };
			}
			for (let at = 0; at < groups.length; at += 1) {
				const models = Array.isArray(groups[at].models) ? groups[at].models : [];
				if (models.length === 0) continue;
				// `provider` 取组 id（官方 `isSelectable` 就是按组 id 比的）。
				return { provider: groups[at].id, model: models[0].id };
			}
			return null;
		}

		/**
		 * 筛**目录值**（`catalog.store` 里 `{value}` 那一份）。
		 *
		 * ⚠️ **`default` 必须跟着换**：官方的 `syncInputs` 用
		 * `projected.next ?? catalog.value.default` 定 `current`，如果 `default`
		 * 指向一个已被筛掉的 provider，界面就会显示一个不在列表里的模型名。
		 * 所以筛完若 `default` 不在剩下的组里，就退到第一个组的第一个模型。
		 *
		 * ⚠️ **一组都没剩时原样返回**（例如电脑那边所有 provider 都挂了）：
		 * 宁可多显示几个，也别把菜单变成空的 —— 空菜单用户连「刷新」都点不到。
		 *
		 * @param value - 目录值。
		 * @param isRemote - 这个会话是不是跑在电脑上。
		 * @returns 筛过的目录值。
		 */
		function filterCatalogValue(value, isRemote) {
			if (!value || !Array.isArray(value.groups)) return value;
			const picked = pickWorkspaceGroups(value, isRemote);
			if (picked.groups.length === 0) return value;
			const next = Object.assign({}, value, {
				groups: picked.groups,
				failures: picked.failures,
			});
			// ## ⚠️ 本地工作区要**把 default 还原成手机自己的**
			//
			// 宿主 `mergeCatalog` 现在优先用**电脑的** `default`（那是修 1017 标签
			// 的关键，见那边的注释）。但本地工作区的模型列表只有手机那几组，
			// 电脑的 default 很可能不在里面 ⇒ 官方 `syncInputs` 算出的 `current`
			// 会指向一个列表里不存在的模型。
			//
			// 所以这里按同一个工作区判据分流：远程会话用合并后的（电脑的）
			// default，本地会话还原成 `localDefault`。
			if (isRemote !== true) {
				const localDefault = value.localDefault;
				if (localDefault !== undefined && localDefault !== null
					&& isSelectable(picked.groups, localDefault)) {
					next.default = { provider: localDefault.provider, model: localDefault.model };
				}
			}
			if (!isSelectable(picked.groups, next.default)) {
				const first = picked.groups[0];
				const firstModel = Array.isArray(first.models) ? first.models[0] : undefined;
				if (firstModel !== undefined) next.default = { provider: first.id, model: firstModel.id };
			}
			return next;
		}

		/**
		 * 电脑侧展示元数据的**内存副本**（`sessionId → 毫秒时间戳`）。
		 *
		 * 由 `loadDesktopMeta()` 在 `apply()` 时拉一次全量填充，之后同步
		 * 完成时再拉一次。**读它必须是同步的** —— 快照每帧都可能被读。
		 *
		 * ⚠️ 用普通对象而不是 Map：这份数据的规模是「同步过来的会话数」
		 * （几十到几百），对象字面量查表更快，而且 `chrome73` 下
		 * 没有 `Object.fromEntries`（本文件受 chrome73 语法限制）。
		 */
		var desktopTimes = {};

		/** `sessionId → true`：电脑上置顶的那些（同上，内存副本）。 */
		var desktopPins = {};

		/**
		 * `sessionId → 我们给它建本地空壳的时刻`（毫秒，内存副本）。
		 *
		 * 这是「本地 `updatedAt` 到底是真值还是假值」的**判断基准**，
		 * 没有它整个排序修复会变成空操作 —— 详见 `applyDesktopClock`。
		 */
		var desktopSyncedAt = {};

		/**
		 * 时间表的**版本号**：每次元数据落地就 +1。
		 *
		 * `wrapSessionListClock` 拿它当缓存 key 的一部分 —— 否则
		 * 「同一个会话的时间被更新、但条目数没变」这种情况下
		 * 缓存不会失效，界面还显示旧顺序（这类 bug 极难察觉）。
		 *
		 * ⚠️ 必须在 `adoptDesktopMeta` **之前**初始化：`var` 提升只提升
		 * 声明不提升赋值，写在后面的话第一次 `metaVersion += 1` 是
		 * `undefined + 1 = NaN`，之后缓存 key 恒为 `NaN !== NaN` ⇒
		 * 每帧都重算（性能塌陷）且永远不会命中缓存。
		 */
		var metaVersion = 0;

		/** 是否成功拉到过电脑侧元数据（决定要不要做「取消置顶」这个反向动作）。 */
		var desktopMetaLoaded = false;

		/**
		 * 把宿主给的电脑侧元数据装进内存副本。
		 *
		 * @param payload - `{times, pins}`；任一项缺失/形状不对就跳过该项。
		 */
		var adoptDesktopMeta = function (payload) {
			if (payload === null || typeof payload !== 'object') return;
			var times = payload.times;
			var nextTimes = {};
			if (times !== null && typeof times === 'object') {
				var keys = Object.keys(times);
				for (var at = 0; at < keys.length; at += 1) {
					var key = keys[at];
					var value = times[key];
					// ⚠️ 只认有限非负数字：混进字符串 ⇒ 排序比较得 NaN ⇒ 排序静默失效。
					if (typeof value !== 'number' || !isFinite(value) || value < 0) continue;
					nextTimes[key] = value;
				}
			}
			var pins = payload.pins;
			var nextPins = {};
			if (pins !== null && typeof pins === 'object') {
				var pinKeys = Object.keys(pins);
				for (var bit = 0; bit < pinKeys.length; bit += 1) {
					if (pins[pinKeys[bit]] === true) nextPins[pinKeys[bit]] = true;
				}
			}
			/*
			 * 建壳时刻：前端靠它判断「本地 `updatedAt` 是同步那一刻的假值，
			 * 还是用户真的在手机上聊过」。
			 *
			 * ⚠️ 这个字段不能省：没有它，`applyDesktopClock` 只能用「取 max」判断哪个时间可用 ——
			 * 而同步建出来的空壳，本地时间恰好是「同步那一刻」，**必然比电脑上那次
			 * 真实对话更新** ⇒ max 永远选本地假值 ⇒ 整个排序修复变成空操作。
			 */
			var rawSynced = payload.syncedAt;
			var nextSynced = {};
			if (rawSynced !== null && typeof rawSynced === 'object') {
				var syncedKeys = Object.keys(rawSynced);
				for (var sit = 0; sit < syncedKeys.length; sit += 1) {
					var skey = syncedKeys[sit];
					var svalue = rawSynced[skey];
					if (typeof svalue !== 'number' || !isFinite(svalue) || svalue < 0) continue;
					nextSynced[skey] = svalue;
				}
			}
			desktopTimes = nextTimes;
			desktopPins = nextPins;
			/*
			 * 数据到位 ⇒ **主动重试置顶**（不能等快照变化，理由见
			 * `pinRetryHooks` 的注释）。放在此处而不是 `loadDesktopMeta`
			 * 回调里：`metaVersion` 可能因其它原因增加，只要置顶表真变了就该重试。
			 */
			firePinRetry();
			desktopSyncedAt = nextSynced;
			// 版本号必须变：缓存 key 靠它失效（见 `wrapSessionListClock`）。
			metaVersion += 1;
			desktopMetaLoaded = true;
		};

		/**
		 * 从宿主拉一次电脑侧元数据（失败就当空，绝不外抛）。
		 *
		 * @returns Promise，永不 reject。
		 */
		var loadDesktopMeta = function () {
			return bridge('desktopMeta', {}).then(
				function (payload) {
					adoptDesktopMeta(payload);
				},
				function () {
					/* 电脑离线 / 桥不存在 —— 保持现有值，不影响其它功能 */
				},
			);
		};

		/**
		 * 把 `sessions.list` 的快照**按电脑真实时间重写 `updatedAt`**。
		 *
		 * ## 为什么必须在这一层做（用户 2026-10-29 报障②）
		 *
		 * 用户原话：「你同步过来的对话，你没有按时间顺序进行一个排序」。
		 *
		 * 官方侧边栏「按时间排序」读的是 `WorkspaceBrowser` 里
		 * `orderByRecency(memberIds, list.byId)`，而它比较的是
		 * `summary.updatedAt`（`dsh-client-ui-workspace/lib/client.js:886`）。
		 * **`list.byId` 的来源正是 `ctx.sessions.list.getSnapshot()`**
		 *（同一个 `useSessions` hook），所以在这里覆盖一处，
		 * 侧边栏、搜索、置顶区、未分组区全部同步生效。
		 *
		 * ## 为什么本地 `updatedAt` 一定是错的
		 *
		 * 同步会话在手机本地是空壳，`markSessionNonBlank()` 为了让它在
		 * 侧边栏可见补写了一个**占位轮次**。而官方
		 * `updatedAt(header, metadata) = Math.max(header.createdAt,
		 * metadata?.lastPromptAt ?? 0)` 取的全是**本地日志时间**，
		 * 且 `session.append()` 写死 `Date.now()` —— 外部无法指定。
		 *
		 * ⇒ 所有同步来的对话，时间戳都≈**这次同步的时刻**，
		 *   彼此相差几十毫秒 ⇒ 排序结果等于「同步顺序」，
		 *   与用户电脑上的实际顺序无关。
		 *
		 * 宿主已经把电脑的真实时间存在 `desktopTimes` 里
		 *（见宿主 `loadDesktopTimes()` 的注释），这里覆盖上去即可。
		 *
		 * ## ⚠️⚠️ 不能只用「谁大用谁」（这是本修复最容易写错的一步）
		 *
		 * 最初的写法是 `Math.max(本地, 电脑)`，理由是「用户可能就在手机上刚聊过」。
		 * 这个写法**看起来完全正确，实际上在所有同步会话上都是空操作**：
		 *
		 *   · 同步建出来的空壳，本地 `updatedAt` 就是「同步那一刻」；
		 *   · 而同步发生在电脑那次真实对话**之后** ⇒ 本地假值**必然更大**；
		 *   · ⇒ `max` 永远选本地假值 ⇒ 排序没有任何变化 ⇒ 用户看到的还是「没修」。
		 *
		 * 所以必须用 `desktopSyncedAt`（建壳时刻）区分真假值：
		 *
		 *   · 本地 ≈ 建壳时刻 ⇒ **假值** ⇒ 无条件用电脑时间（哪怕它更小）；
		 *   · 本地 显著晚于建壳时刻 ⇒ **真值** ⇒ 取 max，不让用户刚聊过的往下沉。
		 *
		 * @param source - 官方 `sessions.list` 的快照。
		 * @returns 改写后的快照；没有任何可用电脑时间时**原样返回**（身份不变）。
		 */
		/**
		 * 重试置顶的回调列表。
		 *
		 * ## 为什么需要它（本轮置顶失效的真正原因）
		 *
		 * 订阅只在 `sessions.list` **快照变化**时触发，而电脑上的
		 * 置顶数据是**异步**拉回的。两者的时序是：
		 *
		 *     apply()          → 订阅建立（此时 desktopPins = {}，什么都不做）
		 *     loadDesktopMeta → 数据到位（desktopPins 有值了）
		 *                        …… 但快照没变 ⇒ **回调不会跑**
		 *
		 * 于是用户看到的就是「置顶永远不生效」。
		 * 数据到位必须**主动推一次**，不能等快照变化。
		 */
		var pinRetryHooks = [];

		/**
		 * 数据到位后主动重试一次置顶。
		 *
		 * 安全性：每个回调都包在 try/catch 里 ——
		 * 置顶是展示增强，失败绝不能影响主流程。
		 */
		var firePinRetry = function () {
			for (var at = 0; at < pinRetryHooks.length; at += 1) {
				try {
					pinRetryHooks[at]();
				} catch (error) {
					/* 同上 */
				}
			}
		};

				var applyDesktopClock = function (source) {
			if (source === null || typeof source !== 'object') return source;
			var byId = source.byId;
			if (byId === null || typeof byId !== 'object') return source;
			// 诊断：若存在则记（`applyDesktopClock` 不应依赖它，单测要能脱离运行）。
			if (typeof clockDiag === 'object' && clockDiag !== null) clockDiag.times = Object.keys(desktopTimes).length;
			var ids = source.ids;
			if (!Array.isArray(ids)) ids = Object.keys(byId);
			var changed = false;
			var nextById = {};
			var appliedCount = 0;
			var keys = Object.keys(byId);
			for (var at = 0; at < keys.length; at += 1) {
				var id = keys[at];
				var row = byId[id];
				var clock = desktopTimes[id];
				if (typeof clock !== 'number' || !isFinite(clock)) {
					nextById[id] = row;
					continue;
				}
				if (row === null || typeof row !== 'object') {
					nextById[id] = row;
					continue;
				}
				/*
				 * ⚠️⚠️ 本地没有 `updatedAt`（或不是合法数字）时、**恰恰是最需要电脑时间的情况**。
				 *
				 * 上一版写的是 `if (非数字) { 原样返回; continue; }` —— 这是错的，
				 * 而且错得很隐蔽：用户截图里那些同步过来的会话
				 * **根本没有相对时间标签**（电脑端每条都有「3分钟」「1小时」），
				 * 正是 `updatedAt` 为 `undefined` / `0` 的表现 —— 而这时我的代码直接跳过了覆盖。
				 *
				 * 结果：最该被修的会话（空壳、无时间）恰好没被修。
				 *
				 * 所以改成：本地时间**不可用 ⇒ 直接用电脑时间**（无条件）。
				 */
				var local = row.updatedAt;
				var localUsable = typeof local === 'number' && isFinite(local) && local > 0;
				if (!localUsable) {
					var copyMissing = {};
					for (var fieldMissing in row) {
						if (Object.prototype.hasOwnProperty.call(row, fieldMissing)) copyMissing[fieldMissing] = row[fieldMissing];
					}
					copyMissing.updatedAt = clock;
					nextById[id] = copyMissing;
					changed = true;
					appliedCount += 1;
					continue;
				}
				/*
				 * 判定本地这个时间是「假值」还是「真值」。
				 *
				 * 假值 = 同步时建空壳那一刻写进日志的时间（占位轮次的时间）。
				 * 特征：它与 `desktopSyncedAt[id]` 几乎相同（建壳时创建的两条事件相差几毫秒）。
				 *
				 * 真值 = 用户后来真在手机上聊过 ⇒ 本地时间**显著晚于**建壳时刻。
				 *
				 * ⚠️ 宽限 2 分钟：建壳流程（create → 占位轮次 → 标题）在真机上可能耗时十几秒，
				 * 而同步一批几十个会话更久。取 2 分钟既能覆盖整个建壳窗口，
				 * 又不会把「同步完三分钟后用户真聊了一句」误判成假值。
				 */
				var syncedAt = desktopSyncedAt[id];
				var isFake = false;
				if (typeof syncedAt === 'number' && isFinite(syncedAt)) {
					isFake = local <= syncedAt + 120000;
				} else {
					/*
					 * 没有建壳时刻（老版本同步出来的会话）⇒ 无法判断真假。
					 *
					 * 这时退回「取 max」：至少不会让刚聊过的往下沉，
					 * 而重新同步一次就会补上建壳时刻（见宿主 `rememberDesktopMeta`）。
					 */
					isFake = clock > local;
				}
				/*
				 * 假值 ⇒ 无条件用电脑时间（哪怕它更小）；
				 * 真值 ⇒ 取 max，不让用户刚聊过的会话往下沉。
				 */
				var target = isFake ? clock : Math.max(local, clock);
				if (target === local) {
					nextById[id] = row;
					continue;
				}
				var copy = {};
				for (var field in row) {
					if (Object.prototype.hasOwnProperty.call(row, field)) copy[field] = row[field];
				}
				copy.updatedAt = target;
				nextById[id] = copy;
				changed = true;
				appliedCount += 1;
			}
			if (!changed) return source;
			var next = {};
			for (var key in source) {
				if (Object.prototype.hasOwnProperty.call(source, key)) next[key] = source[key];
			}
			next.byId = nextById;
			if (typeof clockDiag === 'object' && clockDiag !== null) clockDiag.applied = appliedCount;
			return next;
		};

		/**
		 * 包装 `sessions.list`，让它下发的快照带上电脑真实时间。
		 *
		 * ⚠️ **只换 `getSnapshot`，其余方法原样转发**：`subscribe` / `update` /
		 * `set` 都是官方内部在用的，动它们会破坏 `createSnapshotStore` 的语义。
		 *
		 * ⚠️ **缓存投影结果**（同 `wrapStore` 的理由）：`getSnapshot` 每帧都会被
		 * 调用，每次都重建 `byId` 会产生大量垃圾对象，也会破坏
		 * 「快照身份稳定」这个 React 优化前提（`useSyncExternalStore`
		 * 靠身份变化决定要不要重渲染，身份每帧都变会被误判成「一直在变」）。
		 * 所以按「源快照身份 + 时间表版本」缓存。
		 *
		 * @param list - 官方 `ctx.sessions.list`。
		 * @returns 包装后的 list（原对象不可包装时原样返回）。
		 */
		var wrapSessionListClock = function (list) {
			if (list === null || list === undefined) return list;
			if (typeof list.getSnapshot !== 'function') return list;
			// 防重复包装（`apply` 在热重载时可能被调多次）。
			if (list.__dshClockWrapped === true) return list;
			var cachedSource;
			var cachedToken;
			var cachedValue;
			var wrapper = {
				__dshClockWrapped: true,
				getSnapshot: function () {
					var source = list.getSnapshot();
					// token 用「时间表的条目数 + 一个单调递增的版本号」。
					// 只用条目数不够：同一个会话的时间被更新（条目数不变）时
					// 缓存不会失效。所以版本号在 `adoptDesktopMeta` 里递增。
					var token = metaVersion;
					if (source === cachedSource && token === cachedToken) return cachedValue;
					cachedSource = source;
					cachedToken = token;
					cachedValue = applyDesktopClock(source);
					return cachedValue;
				},
				subscribe: function (fn) { return list.subscribe(fn); },
				update: function (mutator) { return list.update(mutator); },
				set: function (value) { return list.set(value); },
			};
			return wrapper;
		};

		/**
		 * 把电脑上的置顶状态推到官方 `workspaces` 服务上。
		 *
		 * ## 为什么必须走官方 `pinSession()`（用户 2026-10-29 报障③）
		 *
		 * 用户原话：「电脑的某些对话人家是置顶的状态，你也没有把置顶的状态
		 * 同步过来」。
		 *
		 * 官方侧边栏的置顶区读的是 `workspaces.list` 快照里的
		 * `pinnedSessionIds`（`dsh-client-ui-workspace/lib/client.js:3525`），
		 * 而那个集合只有调 `workspaces.pinSession(sessionId)` 才会变。
		 * 光在前端"假装"置顶是没用的 —— 侧边栏渲染、`rowState` 分组、
		 * 未分组区的排除逻辑全都以那个集合为准。
		 *
		 * ## ⚠️ 三条必守
		 *
		 *   ① **会话必须已在本地"已知"**：宿主
		 *      `workspaceRegistry.pinSession()` 校验 `sessionKnown(id)`
		 *      （活会话 / header 索引 / 持久化清单），否则抛
		 *      `session pin failed: session/unknown`。所以在
		 *      `sessions.list` 快照里出现了这个 id 之后才能 pin。
		 *   ② **不能每帧都调**：`pinSession` 是 RPC。所以记 `pinnedApplied`，
		 *      成功过的就不再试；失败的清掉标记，等下次快照变化再试。
		 *   ③ **绝不外抛**：置顶是展示层的增强，失败绝不能影响会话可用性。
		 *
		 * @param list - 已包装的 `sessions.list`。
		 * @param workspaces - 官方 `workspaces` 服务。
		 */
		var pinnedApplied = {};
		var pinAttemptAt = {};
		var applyPinFromDesktop = function (list, workspaces) {
			if (workspaces === null || workspaces === undefined) return;
			if (typeof workspaces.pinSession !== 'function') return;
			if (typeof workspaces.unpinSession !== 'function') return;
			var wanted = Object.keys(desktopPins);
			clockDiag.pins = wanted.length;
			clockDiag.pinned = Object.keys(pinnedApplied).length;
			if (wanted.length === 0) return;
			var snapshot = list === undefined || list === null ? undefined : list.getSnapshot();
			var byId = snapshot === null || snapshot === undefined ? undefined : snapshot.byId;
			if (byId === null || byId === undefined) return;
			var now = Date.now();
			for (var at = 0; at < wanted.length; at += 1) {
				var sessionId = wanted[at];
				// ① 本地还不认识这个会话 ⇒ 还没轮到它
				if (byId[sessionId] === undefined) continue;
				if (pinnedApplied[sessionId] === true) {
					// 已经 pin 过 —— 只检查「电脑上已取消置顶」这个反方向
					// （见 `adoptDesktopMeta`：取消置顶会把它从表里删掉）。
					continue;
				}
				// ② 失败过的退避 10 秒，避免每次快照变化都打一次 RPC。
				var last = pinAttemptAt[sessionId];
				if (typeof last === 'number' && now - last < 10000) continue;
				pinAttemptAt[sessionId] = now;
				/**
				 * ⚠️ 闭包里的 `sessionId`：用 `var` 声明的循环变量在
				 * 异步回调里会被后续迭代改写（JS 的老坑）。必须先拷进
				 * 一个局部常量再传给回调。
				 */
				var target = sessionId;
				try {
					var result = workspaces.pinSession(target);
					// `pinSession` 返回 Promise；失败时要清掉重试窗口。
					if (result !== null && result !== undefined && typeof result.then === 'function') {
							result.then(
							function () {
								pinnedApplied[target] = true;
								clockDiag.pinned = Object.keys(pinnedApplied).length;
							},
							function (err) {
								delete pinAttemptAt[target];
								pinErrorText = String(err && err.message ? err.message : err);
							},
						);
					} else {
						pinnedApplied[target] = true;
					}
				} catch (error) {
					delete pinAttemptAt[target];
				}
			}
			/**
			 * 反方向：手机本地置顶了、但电脑上没置顶 ⇒ 取消。
			 *
			 * 只处理「电脑侧明确说没置顶」的那些 —— 也就是曾经被我们
			 * pin 过、现在从 `desktopPins` 里消失的会话。
			 * 不能在电脑离线时把所有置顶都取消掉（那会把用户在手机上
			 * 自己置顶的会话也一并清掉），所以前提是**至少拿到过一次
			 * 电脑数据**（`desktopMetaLoaded`）。
			 */
			if (!desktopMetaLoaded) return;
			var cleared = Object.keys(pinnedApplied);
			for (var bit = 0; bit < cleared.length; bit += 1) {
				var done = cleared[bit];
				if (desktopPins[done] === true) continue;
				if (byId[done] === undefined) {
					delete pinnedApplied[done];
					continue;
				}
				delete pinnedApplied[done];
				try {
					var drop = workspaces.unpinSession(done);
					if (drop !== null && drop !== undefined && typeof drop.catch === 'function') {
						drop.catch(function () { /* 取消失败无所谓 */ });
					}
				} catch (error) {
					/* 取消失败无所谓 */
				}
			}
		};


		/**
		 * 给官方那个**每会话共享**的 `ModelDirectory` 套一层「按工作区筛」的读路径。
		 *
		 * ## 为什么包装 `directoryFor` 而不是改前端 bundle
		 *
		 * `conversation.input.model` 这个 slot 的 `inject(sessionId)` 会调
		 * `modelDirectories.directoryFor(sessionId)`（见
		 * `dsh-client-ui-model-selection/lib/client.js:1189`），菜单和 `/model`
		 * 命令都从它拿数据。`directoryFor` 是**服务实例上的普通方法**，包它
		 * 就等于包住了这两个入口 —— 不用碰前端内部类，升级也不会碎。
		 *
		 * ## 为什么两层的 store 都要包
		 *
		 * 官方有两级快照，形状**不一样**，两个都要筛：
		 *
		 * | 来源 | 形状 | 谁读它 |
		 * |---|---|---|
		 * | `catalog.store` | `{value:{groups,default,…}, status}` | `syncInputs()` 算 `current`/`routable` |
		 * | `directory.store` | `{groups, failures, current, status, …}` | `ModelSelect` 直接渲染 |
		 *
		 * 只筛 `directory.store` 的话，`syncInputs` 仍会用**没筛过**的 `default`
		 * 把 `current` 算成被筛掉的那个模型 —— 菜单里没有它、触发按钮却显示它，
		 * 而且 `routable` 会被算成 `false`，官方界面会提示「该 provider 的目录加载失败」。
		 *
		 * ## 为什么要「稳定」而不是每次新建
		 *
		 * `inject` 每渲染一帧都调一次 `directoryFor`，返回值里的 `store` 直接喂给
		 * `useSyncExternalStore` 的 `subscribe`。每次返回新对象会让 React 每帧
		 * 重订阅。所以包装缓存在原 `directory.__dshRemoteScoped` 上，生命周期跟着
		 * 官方的会话 scope 一起走 —— 官方在 scope dispose 时删掉原实例，
		 * 包装随之被 GC，不会累积。
		 *
		 * ⚠️ 用 `Object.create(原型) + Object.assign` 而不是 `{...directory}`：
		 * `ModelDirectory` 的方法（`select` / `reasoningFor` / `syncInputs`）
		 * 在**原型**上，展开运算符只复制自有属性、会把方法全丢掉 —— 症状是
		 * 「点模型没反应」。
		 *
		 * ⚠️ `select` 走原型链、以 `this = wrapper` 调用，于是它读到的
		 * `this.store.getSnapshot()` 是**筛过**的（`current` 正确），写回
		 * `this.store.update()` 仍转发给原 store。读写都落在正确的那一层。
		 *
		 * @param directory - 官方的 `ModelDirectory` 实例。
		 * @param sessionId - 它归属的会话。
		 * @param cwdOf - `sessionId → cwd` 的**同步**读取函数（隔离判据要用它）。
		 * @returns 读路径被筛过的同构对象。
		 */
		function scopeDirectoryToWorkspace(directory, sessionId, cwdOf) {
			if (!directory || !directory.store || typeof directory.store.getSnapshot !== 'function') {
				return directory;
			}
			// ⚠️ 判据是**每次读时现算**的（不是建包装时算一次）：会话的 cwd 可能
			// 在列表行到达后才出现，而且解绑/换工作区都会改结论。
			// `isRemoteSession` 见其注释 —— 它看的 cwd，跟宿主 `desktopPathOfMirror` 同一个轴。
			const isRemote = function () { return isRemoteSession(sessionId, cwdOf); };
			const wrapper = Object.create(Object.getPrototypeOf(directory));
			Object.assign(wrapper, directory);

			/**
			 * 包一层 store：读时筛，通知与写回照旧转发。
			 *
			 * ⚠️⚠️ **`getSnapshot()` 必须身份稳定**，否则 React 直接炸。这条是
			 * 1.0.19 第二次真机启动实测到的（第一次是上面的 `remote.session`）：
			 *
			 *     [web] slot entry crashed in 'conversation.input.model':
			 *           Error: Minified React error #185
			 *
			 * #185 就是「Maximum update depth exceeded」（无限重渲染）。原因：
			 * 官方把这个 store 的 `getSnapshot` 直接喂给 `useSyncExternalStore`，
			 * 而 React 在渲染期用 **`Object.is`** 比较前后两次快照，不同就再渲染一次。
			 * 而 `pickWorkspaceGroups` 每次都 `filter` 出新数组、外面又 `Object.assign`
			 * 出新对象 —— **同一个源快照也每次返回新对象** ⇒ 每一帧都"变了" ⇒ 死循环。
			 *
			 * 所以按「源快照身份 + 影响筛选结果的外部开关」缓存上一次的投影结果。
			 */
			const wrapStore = function (store, project, volatileToken) {
				let cachedSource;
				let cachedToken;
				let cachedProjected;
				/*
				 * ## ⚠️⚠️ `bust()` —— 为什么还需要手动作废缓存（1.2.82 加）
				 *
				 * 缓存键是「源快照身份 + token」。`token` 只看
				 * `isRemote()` 和 `sessionChoice`，**看不到目录数据本身**。
				 * 正常情况下这就够了：目录一变，源快照对象就换了。
				 *
				 * 但官方 `catalog.refresh()` 是这么写的（`ModelCatalogDirectory`）：
				 *
				 *     refresh() { this.invalidate(); this.load().catch(() => {}); }
				 *
				 * `invalidate()` 只把 `status` 改成 idle、**value 保留旧值**；
				 * 而 `load()` 是异步 RPC，且 `catch` 把错误全吃掉。所以：
				 *
				 *   · RPC 成功 ⇒ 源快照换了 ⇒ 缓存自然失效 ⇒ 正常；
				 *   · RPC 失败 ⇒ status=error、value 仍是旧的 ⇒ 源快照
				 *     **身份不变** ⇒ 命中缓存 ⇒ 界面一直显示旧模型列表，
				 *     而且 **没有任何提示**。
				 *
				 * 预设那边为什么没这个问题？因为它有 `pullRosterIntoCache`
				 * —— 直接 `bridge('mergedPresetList')` 拿数据写进缓存，**完全不依赖 RPC 成功**。
				 * 模型一直没有这条腿，所以用户看到「预设刷新了、模型没刷新」。
				 *
				 * `bust()` 让调用方在「我知道数据已经变了」时强制重算一次，
				 * 不得不靠源快照身份变化。它只清缓存、不改数据，
				 * 所以调错了最坏也只是多算一次投影。
				 */
				const bust = function () { cachedSource = undefined; cachedToken = undefined; cachedProjected = undefined; };
				return {
					bust: bust,
					getSnapshot: function () {
						const source = store.getSnapshot();
						const token = volatileToken === undefined ? '' : volatileToken();
						// 源快照换了、或开关变了才重算；否则复用同一个对象（身份稳定）。
						if (source === cachedSource && token === cachedToken) return cachedProjected;
						cachedSource = source;
						cachedToken = token;
						cachedProjected = project(source);
						return cachedProjected;
					},
					subscribe: function (fn) { return store.subscribe(fn); },
					update: function (mutator) { return store.update(mutator); },
					set: function (value) { return store.set(value); },
				};
			};

			const store = directory.store;
			/*
			 * ## ⚠️⚠️ 必须把 localGroups 的身份作为 token 传进去（1.2.67 修）
			 *
			 * `wrapStore` 的缓存 key 是「源快照身份 + token」。
			 * 但投影函数还读了**一个不在 key 里的输入**：
			 *
			 *     pickWorkspaceGroups(snapshot, isRemote(), localGroupsOf(directory))
			 *                                              ^^^^^^^^^^^^^^^^^^^^^^^
			 *
			 * 而 `localGroupsOf` 读的是 `directory.catalog.store`，
			 * 与 `directory.store`（源快照那个）**是两层不同的存储**。
			 *
			 * 于是就有这么一条路：
			 *
			 *   ① 同步完成，`catalog.refresh()` 刷新了 catalog 层的
			 *      `localGroups`（电脑上的模型目录进来了）；
			 *   ② 但 `directory.store` 的快照对象**没换**（它只在输入变化时重建）；
			 *   ③ 于是 `source === cachedSource` 成立 ⇒ **命中缓存**
			 *      ⇒ 返回上一次的投影，里面还是旧的 localGroups。
			 *
			 * 用户看到的就是：**同步完成了，模型菜单还是旧的；
			 * 重启 App 就好了**—— 重启把缓存丢了。这正是用户报的 ①。
			 *
			 * 修法：把 localGroups 的**数组身份**作为 token。它是 catalog 重建快照时
			 * 换新数组的，所以身份变化就能触发重算。而它不变时
			 * 仍然命中缓存 —— **React 需要的快照身份稳定性一点没丢**。
			 *
			 * 为什么不把它并进 snapshot？因为 snapshot 的身份必须保持不变（它是
			 * `useSyncExternalStore` 的比较对象，见上面 React #185 那段）。token 是**另一个维度**，
			 * 不参与对外曝露。
			 */
			const localGroupsToken = function () {
				try {
					const groups = localGroupsOf(directory);
					/*
					 * token = 数组身份 + 长度 + 每组的 id。
					 *
					 * 三项都要：
					 *   · 身份：官方重建数组（最常见）时变化；
					 *   · 长度：原地 push/pop 时变化；
					 *   · id 串：原地替换元素（push 一个同时 pop 一个）时变化。
					 *
					 * 为什么不逐个比内容？因为这里只要判「变没变」。
					 * 全量比内容的成本已经接近「干脆重算一次投影」，那就失去缓存的意义了。
					 * id 串能抓住绝大多数真实变化，成本是 O(组数)。
					 */
					var sig = String(groups.length);
					for (var gi = 0; gi < groups.length; gi += 1) {
						var g = groups[gi];
						sig += '|' + (g && g.id !== undefined ? String(g.id) : '?');
						/* 每组里的模型数也参与 —— 官方注册新模型属于同一组内部变化 */
						sig += ':' + ((g && Array.isArray(g.models)) ? g.models.length : 0);
					}
					return sig;
				} catch (tokenError) {
					/* 取不到就用固定值 —— 宁可不触发重算，也不能抛 */
					return '';
				}
			};
			wrapper.store = wrapStore(store, function (snapshot) {
				// ⚠️ `directory.store` 那一层没有 `localGroups`（官方 `syncInputs`
				// 只拷了 groups/failures），所以从 catalog 的原值里现取一份递进去。
				const picked = pickWorkspaceGroups(snapshot, isRemote(), localGroupsOf(directory));
				if (picked.groups.length === 0) return snapshot;
				const next = Object.assign({}, snapshot, picked);
				// `current` 指向被筛掉的模型时换一个**可用**的，不能留 null。
				//
				// ## ⚠️⚠️ 这里原来是 `next.current = null`，那是个 bug（用户报障）
				//
				// 用户原话：
				//   「我输入框右下角，它一直显示正在加载模型，但是我点击它展开是可以
				//    看到模型列表的，然后我也是可以正常发送消息的，但是它一直显示
				//    正在加载模型。」
				//
				// 触发按钮的文案由官方
				// `dsh-client-ui-model-selection/lib/client.js:773-774` 算：
				//
				//     const waiting = state.current === null && state.status === "loading";
				//     const modelLabel = waiting ? t("trigger.loading") : …
				//
				// 也就是说 **`current === null` 会被官方读成「还没加载完」**。
				// 而 `current` 这个字段是官方 `syncInputs()` 算的，它**只在
				// 输入变化时算一次**；我们把快照里的 `current` 清成 null 之后，
				// 没有任何人再把它算回来 ⇒ 按钮永久卡在「正在加载模型…」，
				// 尽管 `groups` 是好的（所以点开菜单能看到完整列表）、
				// `status` 也是 `loaded`（所以能正常发消息）——
				// 这三个现象同时出现，正是这个 bug 的指纹。
				//
				// 正确做法：清空之后**立刻回填**一个列表里真实存在的选择
				//（`snapshot.default` 优先，其次第一个组的第一个模型）。
				// 这样按钮显示的是真实模型名，语义上也和「当前实际会用哪个模型」
				// 一致 —— 官方发消息时本来就会退回默认模型。
				if (!isSelectable(picked.groups, next.current)) {
					next.current = null;
					const fallback = pickFallbackSelection(picked.groups, snapshot.default);
					if (fallback !== null) next.current = fallback;
				}
				return next;
			// ⚠️ 缓存键必须带上「这个会话算不算远程」：同一个源快照在「切成远程会话」
			// 前后要投影出**不同**结果，只看源快照身份会永远返回旧的那一份。
			//
			// ## ⚠️⚠️ 还必须带上 localGroups 的签名（1.2.67 修）
			//
			// 投影函数里还读了一个**不在缓存 key 里的输入**：
			//
			//     pickWorkspaceGroups(snapshot, isRemote(), localGroupsOf(directory))
			//                                              ^^^^^^^^^^^^^^^^^^^^^^^
			//
			// 而 `localGroupsOf` 读的是 `directory.catalog.store`，与这里的
			// `directory.store`（源快照那个）**是两层不同的存储**。所以：
			//
			//   ① 同步完成，`catalog.refresh()` 刷新了 catalog 层的 localGroups；
			//   ② 但 `directory.store` 的快照对象**没换**；
			//   ③ `source === cachedSource` 成立 ⇒ 命中缓存 ⇒ 返回旧投影。
			//
			// 用户看到：**同步完成了，模型菜单还是旧的；重启 App 就好了**
			// —— 重启把缓存丢了。正是用户报的 ①。
			}, function () {
				var token = isRemote() === true ? 'remote:1' : 'remote:0';
				return token + '|lg:' + localGroupsToken();
			});

			// `load()` 返回的就是快照，`/model` 命令直接拿它渲染，所以也要筛。
			wrapper.load = function () {
				return Promise.resolve(directory.load()).then(function () {
					return wrapper.store.getSnapshot();
				});
			};

			const catalog = directory.catalog;
			if (catalog && catalog.store && typeof catalog.store.getSnapshot === 'function') {
				const catalogStore = catalog.store;
				const catalogWrapper = Object.create(Object.getPrototypeOf(catalog));
				Object.assign(catalogWrapper, catalog);
				// ## ⚠️⚠️ 会话级选择的「最后已知值」（2026-10-28 修模型勾选错乱）
				//
				// 官方 `syncInputs()` 是这么定「当前选中」的
				//（`dsh-client-ui-model-selection/lib/client.js:967`）：
				//
				//     const selection = projected.next ?? catalog.value.default;
				//
				// `projected.next` 来自**会话投影** `modelSelection`，而那个投影是
				// 由会话历史里的 `model/selection` 事件派生的。远程会话在手机侧是
				// 一个**镜像会话**，它的历史由电脑的事件流灌进来 —— 而用户手动选
				// 模型这件事，电脑那边未必、也未必及时地产生一条可供镜像的
				// `model/selection`。于是 `projected.next` 恒为 `undefined`，
				// `current` 就**永远**退回 `catalog.value.default` —— 也就是电脑的
				// **全局默认模型**（用户看到的「官方那个模型」）。
				//
				// 症状（用户原话）：
				//   「收起的时候还是展开的时候，它一直是勾着官方的那个模型……
				//    但实际发送消息用的又是我选的那个。」
				// 完全对得上：**发消息走的是插件落盘的 `remoteModels`（对的），
				// 而界面读的是 `current`（错的）**，两者各走各的。
				//
				// 修法：把「用户在这个会话里选过的模型」记下来，并在目录投影里
				// 把它顶成 `default`。这样 `projected.next ?? default` 的**两个**
				// 分支都会落到用户的选择上 —— 不管官方有没有拿到投影，收起/展开
				// 都是同一个值，天然一致。选完立刻覆盖到所有会话目录，无需重载。
				// 初值从中介拿：可能拦截器已经先收到过选择（见 `modelChoiceBus`）。
				var sessionChoice = modelChoiceBus.selection;

				catalogWrapper.store = wrapStore(catalogStore, function (snapshot) {
					var filtered = filterCatalogValue(snapshot.value, isRemote());
					return Object.assign({}, snapshot, {
						value: preferSessionChoice(filtered, sessionChoice),
					});
				}, function () {
					// ⚠️ 缓存令牌必须带上「会话选择」：否则选了模型之后
					// 源快照没变、令牌也没变 ⇒ `wrapStore` 直接返回上一份缓存
					// ⇒ 界面纹丝不动（这正是「选了没反应」的另一种成因）。
					var token = isRemote() === true ? 'remote:1' : 'remote:0';
					return sessionChoice === null
						? token
						: token + '|' + sessionChoice.provider + '/' + sessionChoice.model;
				});
				/*
				 * 把 `bust()` 交给模块级（1.2.82）。
				 *
				 * 会话目录（`wrapper.store`）和目录层（`catalogWrapper.store`）
				 * **两处都有缓存**，只作废一个等于没作废。
				 */
				bustModelCatalogRef.value = function () {
					try { if (wrapper.store && typeof wrapper.store.bust === 'function') wrapper.store.bust(); } catch (e) { /* ignore */ }
					try { if (catalogWrapper.store && typeof catalogWrapper.store.bust === 'function') catalogWrapper.store.bust(); } catch (e) { /* ignore */ }
				};

				/**
				 * 用「本会话的选择」顶掉目录里的 `default`。
				 *
				 * 只在那个选择**确实在列表里**时才顶：用户可能在换工作区后
				 * 看不到原来的 provider（`pickWorkspaceGroups` 筛掉了），
				 * 这时硬顶会显示一个列表里不存在的模型 —— 宁可退回默认。
				 *
				 * 不改 `groups`，只改 `default`：`groups` 决定菜单里能选什么，
				 * 这里要影响的是「当前是哪个」。
				 */
				var preferSessionChoice = function (value, choice) {
					if (value === null || value === undefined) return value;
					if (choice === null || typeof choice.provider !== 'string' || typeof choice.model !== 'string') return value;
					// ⚠️ **只作用于远程会话**。
					//
					// 这个 bug 的成因（远程会话在手机侧是镜像、投影里没有
					// `model/selection`）在**本地会话上不存在**：本地会话的
					// `selectModel` 会正常写下投影，`projected.next` 是好的。
					// 若在这里无差别地顶 `default`，会把「最近一次在某个远程
					// 会话里选过的模型」泄漏到本地会话的显示上 —— 从「永远是
					// 官方模型」变成「永远是上次远程选的那个」，同样是错的。
					if (isRemote() !== true) return value;
					var groups = Array.isArray(value.groups) ? value.groups : [];
					if (!isSelectable(groups, choice)) return value;
					return Object.assign({}, value, {
						default: { provider: choice.provider, model: choice.model },
					});
				};

				/**
				 * 收到「用户选了某个模型」时调用（由 selectModel 拦截器触发）。
				 *
				 * 记录了就直接让目录 store 重新广播，官方 `syncInputs` 会跟着
				 * 重算 `current` ⇒ 卡片里的勾选、输入框的文案、aria-label
				 * **三处同时**变对，不再需要去改 DOM 文本节点。
				 */
				wrapper.noteSessionChoice = function (selection) {
					if (selection === null || selection === undefined) return;
					if (typeof selection.provider !== 'string' || typeof selection.model !== 'string') return;
					if (selection.provider === '' || selection.model === '') return;
					if (sessionChoice !== null
						&& sessionChoice.provider === selection.provider
						&& sessionChoice.model === selection.model) {
						return;
					}
					sessionChoice = { provider: selection.provider, model: selection.model };
					// ## ⚠️⚠️ 必须用 `update()` 而不是 `set()` 来触发重算
					//
					// 官方 `ModelDirectory` 是这样接到变化的
					//（`dsh-client-ui-model-selection/lib/client.js:844`）：
					//
					//     this.unsubscribeCatalog = catalog.store.subscribe(() => {
					//         this.syncInputs();
					//     });
					//
					// 也就是说**订阅一被通知**，官方就会自己重算 `current`。
					//
					// 而 `set(value)` 是**整值替换**：我第一次写的是
					// `catalogStore.set({...current})`，把 `getSnapshot()` 拿到的那份
					//（已经被我们投影过的）对象原样写回去。两个问题：
					//   ① 它覆盖掉了 store 的原始状态字段（`status`/`error` 等），
					//      官方据此可能判成「未就绪」而走另一条分支；
					//   ② 传的是同一个形状的对象，某些 store 实现会做等值判断后
					//      **不通知订阅者** ⇒ `syncInputs` 根本不跑 ⇒ 选了没反应。
					//      真机实测就是这个症状：底部按钮变了，卡片里的值还是旧的。
					//
					// `update(mutator)` 是官方自己 `load()`/`invalidate()` 用的写法，
					// 走的是「就地改 + 通知」的正规路径。这里只碰一个**我们自己加的**
					// 公开字段（`sessionChoiceHint`，官方不读），不篡改它的任何状态。
					try {
						catalogStore.update(function (draft) {
							draft.sessionChoiceHint = sessionChoice;
						});
					} catch (bumpError) {
						/* 触发失败不影响已记录的选择 */
					}
				};

				// 让 selectModel 拦截器能通知到这里（并补发它此前记下的选择）。
				modelChoiceBus.register(wrapper.noteSessionChoice);
				wrapper.catalog = catalogWrapper;
			}
			return wrapper;
		}

		/**
		 * 会话级模型选择的**中介**（2026-10-28）。
		 *
		 * 两个东西需要说话，但它们在文件的两处、各自是闭包：
		 *   · `selectModel` 的 fetch 拦截器 —— 知道「用户刚选了哪个模型」；
		 *   · `patchModelDirectories` 里的目录包装 —— 需要把这个选择顶成
		 *     `catalog.default`，官方 `syncInputs()` 才会算出正确的 `current`。
		 *
		 * 用一个极小的模块级对象传话，比为这一个字段去重构两边的闭包稳得多
		 *（那两段代码各自都背着几千字的踩坑注释，动结构风险远大于收益）。
		 *
		 * `notifier` 由目录包装在安装时注册；`selectModel` 拦截器直接调 `note()`。
		 * 目录还没装好时 `note()` 只是记下来，等注册时补发 —— 不漏事件。
		 */
		var modelChoiceBus = { selection: null, notifier: null,
			note: function (selection) {
				if (selection === null || selection === undefined) return;
				if (typeof selection.provider !== 'string' || typeof selection.model !== 'string') return;
				if (selection.provider === '' || selection.model === '') return;
				modelChoiceBus.selection = { provider: selection.provider, model: selection.model };
				if (typeof modelChoiceBus.notifier === 'function') {
					try {
						modelChoiceBus.notifier(modelChoiceBus.selection);
					} catch (notifyError) {
						/* 通知失败不影响拦截器主流程 */
					}
				}
			},
			register: function (notifier) {
				modelChoiceBus.notifier = notifier;
				// 补发：拦截器可能比目录包装先跑（用户在目录还没建好时就点了模型）。
				if (typeof notifier === 'function' && modelChoiceBus.selection !== null) {
					try {
						notifier(modelChoiceBus.selection);
					} catch (replayError) {
						/* 同上 */
					}
				}
			} };

		/**
		 * 把「按工作区筛模型目录」接到官方服务上。
		 *
		 * `ctx.get` 拿不到服务就静默跳过（最坏是回到「两个工作区看到同一份列表」的
		 * 旧行为，不会报错）。返回一个撤销函数。
		 *
		 * @param ctx - client 根上下文。
		 * @param cwdOf - `sessionId → cwd` 的**同步**读取函数（隔离判据要用它）。
		 * @returns 撤销函数。
		 */
		function patchModelDirectories(ctx, cwdOf) {
			var models = ctx.get === undefined ? undefined : ctx.get('modelDirectories');
			if (!models || typeof models.directoryFor !== 'function') return function () {};

			// ⚠️⚠️ **必须在「原始实例」上覆盖，不能在 `ctx.get()` 拿到的那个对象上覆盖。**
			//
			// `modelDirectories` 是 cordis 的 Service，`ctx.get()` 返回的是
			// `createTraceable()` 造出来的 **Proxy**（见 cordis `lib/index.js` 的
			// `getTraceable`/`createTraceable`）。那个 Proxy 的 `get` 会把方法包成
			// `createShadowMethod(ctx, fn, receiver, shadow)`，其 apply 里有一句：
			//
			//     if (thisArg === outer) thisArg = shadow;
			//
			// `outer` 就是取方法时的那个 receiver，而 `shadow` 是
			// `withProp(receiver, property, ctx.extend({[shadow]: origin}))` ——
			// 这个 `ctx` 是**谁调 `ctx.get()` 就用谁的上下文**。
			//
			// 于是 `models.directoryFor.bind(models)` 把 this 钉在了 Proxy 上，
			// 每次调用都命中 `thisArg === outer` ⇒ this 被换成**我的插件上下文**
			// 扩展出来的 shadow。而官方 `ModelDirectoryResolver.directoryFor` 内部要读
			// `this.ctx.remote.session`（`dsh-client-ui-model-selection/lib/client.js:388`），
			// 我的插件没有 inject 过 `remote.session` ⇒ 抛
			//
			//     cannot get property "remote.session" without inject
			//
			// 真机症状：`conversation.input.model` 这个 slot 整体 crash，
			// **输入框上的模型按钮直接消失**。1.0.19 首次真机启动实测到的。
			//
			// 正解是用 `Symbol.for('cordis.original')` 取出 Proxy 背后的原始实例
			// （`createTraceable` 的 get trap 里第一条就是 `if (prop === symbols.original) return target`），
			// 在**它**身上覆盖。这样 `this.ctx` 始终是 `ModelDirectoryResolver`
			// 构造时 `super(ctx, …)` 存下的那个 ctx，服务解析照旧。
			var ORIGINAL = Symbol.for('cordis.original');
			var raw = models[ORIGINAL] === undefined ? models : models[ORIGINAL];
			if (!raw || typeof raw.directoryFor !== 'function') return function () {};

			var hadOwn = Object.prototype.hasOwnProperty.call(raw, 'directoryFor');
			var previous = raw.directoryFor;
			var call = previous.bind(raw);
			raw.directoryFor = function (sessionId) {
				var directory = call(sessionId);
				if (directory.__dshRemoteScoped !== undefined) return directory.__dshRemoteScoped;
				var scoped = scopeDirectoryToWorkspace(directory, sessionId, cwdOf);
				try {
					directory.__dshRemoteScoped = scoped;
				} catch (error) {
					/* 冻结就退回「每次新建」，功能不受影响 */
				}
				return scoped;
			};
			return function () {
				if (hadOwn) raw.directoryFor = previous;
				else delete raw.directoryFor;
			};
		}

		/**
		 * 把「按工作区筛预设名单」接到官方 RPC 上。
		 *
		 * ⚠️⚠️ **为什么必须在前端拦，而不能像模型那样只靠宿主。**
		 *
		 * 官方的预设名单来自 `readRoster(ctx)` → `ctx.remote.agentPresets.list()`
		 * （`dsh-client-ui-agent-preset/lib/client.js:1081`），落到宿主就是
		 * `remoteExportList()` —— 这个方法**一个参数都不收**
		 * （`dsh-agent-preset-registry/lib/index.js:592`），宿主侧**拿不到会话**，
		 * 所以「远程会话看电脑的、本地会话看手机的」在宿主侧表达不出来。
		 *
		 * 宿主那边（`mergePresets`）只能把两份并起来并打 `desktop: true` 标记，
		 * 与模型目录的 `mergeCatalog` 完全同构。**筛这一步必须在能看到会话的地方做。**
		 *
		 * 还有一条更硬的理由：`seat.load()` 只在 `settings/document-updated` 与
		 * `connection/reset` 时重跑（`dsh-client-ui-agent-preset/lib/client.js:1595`），
		 * **切工作区根本不会重新请求 roster** —— 所以就算宿主按会话筛好了，
		 * 前端缓存里那份旧的也不会变。只能在**读结果**的地方筛。
		 *
		 * @param ctx - client 根上下文。
		 * @param cwdOf - `sessionId → cwd` 的同步读取函数。
		 * @returns 撤销函数。
		 */
		function patchAgentPresets(ctx, cwdOf) {
			// 诊断出口①：**进入函数就记一笔**。1.0.33 的诊断只写在 `list()` 里，
			// 结果真机读到 `window.__dshRemoteDiag === null` —— 只能证明「list 没被
			// 调用过」，分不清是「patch 没进来」还是「进来了但官方没调」。这一笔
			// 把两种情形分开。
			var probe = { stage: 'enter' };
			try {
				window.__dshRemoteProbe = probe;
			} catch (probeError) {
				/* 诊断失败绝不影响主流程 */
			}

			var remote = ctx.remote;
			probe.hasRemote = remote !== undefined && remote !== null;
			if (remote === undefined || remote === null) return function () {};
			var presets = remote.agentPresets;
			probe.hasPresets = presets !== undefined && presets !== null;
			if (presets === undefined || presets === null) return function () {};
			probe.listType = typeof presets.list;
			if (typeof presets.list !== 'function') return function () {};

			// ⚠️⚠️ **必须在「原始实例」上覆盖 `list`，不能在 `ctx.remote.agentPresets`
			// 拿到的那个对象上覆盖 —— 这是 1.0.25 / 1.0.26 / 1.0.27 三次真机都
			// 「改了却完全没效果」的根因。**
			//
			// 与 `patchModelDirectories` 同一个陷阱（见那里的长注释）：cordis 的
			// `ctx.remote` 是 `createTraceable()` 造出来的 **Proxy**，它的 `get` trap
			// 会把方法包成 `createShadowMethod(...)`，并把对象属性也裹一层。
			// 往 Proxy 上赋一个新属性，写进的是 Proxy 的 shadow 层，
			// **官方 `readRoster` 读的是它自己那份 `ctx.remote.agentPresets.list`** ——
			// 两者不是同一个函数位，所以我那次赋值官方永远看不到。
			//
			// 真机症状（用户三次反馈）：
			//   1.0.25「模型列表能够快速切换回来了，但是预设列表没有切换」
			//   1.0.26/1.0.27「不管是应用的私有默认工作区，还是我自己选择的
			//   手机目录工作区，都无法自动切换回手机的预设列表」
			// —— 注意「模型能切、预设不能切」，正是因为模型走
			// `patchModelDirectories`（**正确解包了**），而预设没有。
			//
			// 正解：用 `Symbol.for('cordis.original')` 取出 Proxy 背后的原始实例
			// （`createTraceable` 的 get trap 第一条就是 `if (prop === symbols.original) return target`），
			// 在**它**身上覆盖。这样官方经 Proxy 读到的就是这个被包过的 `list`。
			var ORIGINAL = Symbol.for('cordis.original');
			var raw = presets[ORIGINAL] === undefined ? presets : presets[ORIGINAL];
			probe.rawListType = raw ? typeof raw.list : 'no-raw';
			if (!raw || typeof raw.list !== 'function') return function () {};

			/**
			 * 「当前主视图会话」的 id。
			 *
			 * ⚠️⚠️ **必须优先读 `uiWorkspace.selection`，不能只靠 `retainedBy`。**
			 *
			 * 1.0.25 第一版用的是「遍历 `sessions.byId`，找 `retainedBy.mainView > 0`
			 * 的第一行」—— 那是官方 `installWorkspaceShortcuts` 里给快捷键用的
			 * `current()`（`dsh-client-ui-workspace/lib/client.js:106`），它只保证
			 * 「**有一个**会话被主视图持有」，**不保证唯一、也不保证是最新的那个**。
			 * 电脑工作区与手机工作区各有一个被持有的会话时，遍历顺序先命中谁就是谁，
			 * 于是用户切回手机目录后，判定仍然可能落在电脑那个会话上。
			 *
			 * 真机症状（用户截图）：切回手机工作区，预设列表仍是电脑那份，
			 * 选中后报 `Unknown agent preset: pojia` —— 手机宿主不认识电脑的预设 id。
			 *
			 * 正解是官方自己的权威来源：`uiWorkspace.selection`
			 * （`createSnapshotStore({})`，见其 L761；`replaceMain()` 在切换主视图时
			 * 用 `this.selection.set({sessionId: reference.sessionId})` 写入，
			 * 见其 L982-985）。它就是「现在主视图上打开的是哪个会话」。
			 *
			 * `retainedBy` 只作为拿不到 `selection` 时的退路。
			 *
			 * @returns 会话 id；找不到时 `undefined`。
			 */
			var currentSessionId = function () {
				// ① 权威来源：`uiWorkspace.selection`。
				var uiWorkspace = ctx.uiWorkspace;
				if (uiWorkspace !== undefined && uiWorkspace !== null) {
					var selection = uiWorkspace.selection;
					if (selection !== undefined && selection !== null && typeof selection.getSnapshot === 'function') {
						var chosen = selection.getSnapshot();
						if (chosen && typeof chosen.sessionId === 'string' && chosen.sessionId !== '') {
							return chosen.sessionId;
						}
					}
					// ② `uiWorkspace.mainReference` —— 官方主视图当前挂着的那个会话引用。
					// 它与 `selection` 同源（`replaceMain()` 会同时写这两处），
					// 但 `selection` 是持久化快照、可能落后一帧，这里补上。
					var reference = uiWorkspace.mainReference;
					if (reference && typeof reference.sessionId === 'string' && reference.sessionId !== '') {
						return reference.sessionId;
					}
				}

				/**
				 * ⚠️⚠️ **这里绝对不能再「遍历所有会话、找第一个 `retainedBy.mainView > 0`」**
				 * —— 那是 1.0.30 之前「切回手机工作区后预设列表不跟着切回来」的**真正根因**。
				 *
				 * 官方自己在 `dsh-client-ui-workspace/lib/client.js:106` 的注释里就写明了
				 * 那套判据**只保证「有一个会话被主视图持有」，不保证唯一、也不保证是最新的**：
				 *
				 * ```js
				 * const current = () => { ... 找 retainedBy.mainView > 0 的第一行 ... };
				 * ```
				 *
				 * 用户的实际场景正好踩中它：**新建会话界面（hero 页）里根本没有任何会话**
				 * —— `selection` 是空的、`mainReference` 是空的，于是掉进那条盲扫退路，
				 * 而盲扫按 `Object.keys(byId)` 的顺序**先命中电脑那个旧会话**，
				 * 于是「当前是电脑工作区」被判成 true，预设列表永远停在电脑那一份，
				 * 哪怕用户已经把工作区切回手机目录。
				 *
				 * 用户原话：「你难道还是做不到切换回手机的目录之后
				 * （也就是说切换到非电脑目录之后），预设列表正常改回来吗」。
				 *
				 * **正确行为：判不出来就返回 `undefined`。**
				 * `currentIsRemote()` 会把 `undefined` 当成「手机（本地）」——
				 * 新建会话界面本来就该用手机那一套预设，这是符合直觉的默认值。
				 */
				return undefined;
			};

			/**
			 * 当前主视图所在的**工作区路径** —— 「选工作区就是开关」的那把开关本身。
			 *
			 * 用户原话：「就是选工作区的时候就作为一个开关……检测到是手机的，你就给我
			 * 换回手机那一套呗，检测到是电脑的就换回电脑那一套呗」。
			 *
			 * 所以判据应当落在**工作区**上，而不是绕着会话去猜。官方自己在
			 * `dsh-client-ui-workspace/lib/client.js:932` 就是这么定位当前工作区的：
			 *
			 * ```js
			 * const workspace = workspaces.items.find(
			 *   (item) => item.sessionIds.includes(sessionId));
			 * ```
			 *
			 * `item.path` 即该工作区的目录 —— 电脑镜像工作区的路径里带
			 * `.../workspaces/<slug>/...`，`mirrorSlugOf()` 能认出 slug。
			 *
			 * @param sessionId - 当前主视图会话 id。
			 * @returns 工作区路径；查不到时 `undefined`。
			 */
			var workspacePathOfCurrent = function (sessionId) {
				if (typeof sessionId !== 'string' || sessionId === '') return undefined;
				var workspaceService = ctx.workspaces;
				if (workspaceService === undefined || workspaceService === null) return undefined;
				var list = workspaceService.list;
				if (list === undefined || list === null || typeof list.getSnapshot !== 'function') return undefined;
				var snapshot = list.getSnapshot();
				if (snapshot === undefined || snapshot === null) return undefined;
				var items = snapshot.items;
				if (!Array.isArray(items)) return undefined;
				for (var at = 0; at < items.length; at += 1) {
					var item = items[at];
					if (!item || !Array.isArray(item.sessionIds)) continue;
					if (item.sessionIds.indexOf(sessionId) === -1) continue;
					if (typeof item.path === 'string' && item.path !== '') return item.path;
					return undefined;
				}
				return undefined;
			};

			/**
			 * 这一屏该用电脑的预设还是手机的预设 —— **一个布尔开关**。
			 *
			 * 判据顺序（前面的赢）：
			 *
			 * 1. **当前工作区的路径**是不是电脑镜像目录 —— 用户要的那个开关；
			 * 2. 当前会话自己的 `cwd` —— 与模型菜单完全同一条（`isRemoteSession`）；
			 * 3. 都没有 ⇒ 当成手机（本地）。
			 *
			 * ⚠️ 第 1 条必须放最前：切工作区时 `selection` 与列表行可能晚一两帧才一致，
			 * 而工作区行是**已经切过去**的那一个，它最能代表用户此刻的选择。
			 *
			 * @returns 是不是电脑工作区。
			 */
			var currentIsRemote = function () {
				var sessionId = currentSessionId();
				var path = workspacePathOfCurrent(sessionId);
				if (typeof path === 'string' && path !== '') {
					/**
					 * 判据收敛到 `isMirrorPath()`（1.2.85）。
					 *
					 * 这段原来自己写了一遍「slug 精确匹配 + 路径形态兜底」，
					 * 而 `isRemoteSession`（**模型**走的那条）只有前半段 ——
					 * 两份答案不一致，就是用户报了十几个版本的
					 * 「预设同步了、模型列表没同步」。详见 `isMirrorPath` 的长注释。
					 *
					 * 现在两边共用同一个函数，**不可能再漂**。
					 */
					return isMirrorPath(path);
				}
				/*
				 * ## 新建对话时：用「刚选中的工作区」兜底（2026-10-29 修 bug）
				 *
				 * 用户报障：「我在主界面就可以直接选择你已经同步的某个工作区来
				 * 进行新建对话，然后我发现如果我直接选择某个已存在的工作区的话，
				 * 你电脑的那些预设呀还有模型列表都没办法刷新显示出来」。
				 *
				 * 根因就在这两行原来直接 `return false`：
				 *   · 新建对话**还没有 sessionId** ⇒ `workspacePathOfCurrent` 拿不到
				 *     路径（它靠 `item.sessionIds.indexOf(sessionId)` 反查）；
				 *   · 于是落到这里，`sessionId === undefined` ⇒ 判成"手机本地"
				 *     ⇒ 预设和模型都用了手机那一套。
				 *
				 * `currentPendingWorkspace()` 正是为这个场景准备的（见
				 * `isRemoteSession` 里第 2 条判据的注释）：它记录用户在新建界面
				 * **刚选中的工作区路径**，不依赖会话是否已建立。
				 */
				var pendingPath = currentPendingWorkspace();
				if (typeof pendingPath === 'string' && pendingPath !== '') {
					/*
					 * 同一个 `isMirrorPath()`。拿到明确的本地路径就**直接定论**，
					 * 不再往下猜（下面的 DOM 探测只在完全没有路径线索时才用）。
					 */
					return isMirrorPath(pendingPath);
				}
				/*
				 * ## 没有 sessionId 时**也要问 `cwdOf`**（2026-10-29 修 bug）
				 *
				 * 原来这里是 `if (sessionId === undefined) return false;` —— 新建对话
				 * 界面天然没有 sessionId，于是永远直接判"本地"。
				 *
				 * 但 `cwdOf(undefined)` 并**不一定**拿不到东西：它的退路链里有
				 * 「按当前界面选中工作区的 `workspaceId` 直查」
				 *（`workspacePathOf(ctx, row.workspaceId)`），那条判据不依赖会话
				 * 是否已建出来。这正是用户报障的场景：
				 * 「我在主界面就可以直接选择你已经同步的某个工作区来进行新建对话，
				 *   然后我发现如果我直接选择某个已存在的工作区的话，你电脑的那些
				 *   预设呀还有模型列表都没办法刷新显示出来」。
				 *
				 * 所以这里改成：先让 `cwdOf` 试一次（它内部有完整的退路链），
				 * 真拿不到才判本地。
				 */
				if (sessionId === undefined) {
					/*
					 * 新建对话页：没有会话，`cwdOf(undefined)` 在 `makeCwdOf` 第一行就会
					 * 返回（那儿有 `typeof sessionId !== 'string'` 的守卫），所以那条路
					 * 根本走不到退路链。真正的信号在**界面上的工作区选择器**里 ——
					 * 见 `probeHeroWorkspaceDesktopPath` 的长注释。
					 */
					var heroPath = probeHeroWorkspaceDesktopPath();
					if (typeof heroPath === 'string' && heroPath !== '') {
						/*
						 * 探测函数只在与镜像清单**精确命中**时才返回路径，
						 * 所以拿到非空值就可以直接定论为电脑工作区。
						 */
						return true;
					}
					return false;
				}
				return isRemoteSession(sessionId, cwdOf);
			};

			/**
			 * 覆盖一个对象上的 `list`，返回撤销函数。
			 *
			 * ⚠️⚠️ **这条路已经证明是死路，保留只为兜底，主路径是 `hookPresetFetch`。**
			 *
			 * 真机取证（1.0.38，`window.__dshRemoteProbe`）：
			 *
			 *     { "ownList": true, "readBackSame": false,
			 *       "proxyHasAgentPresets": false, "remoteHasAgentPresets": false,
			 *       "listCalls": 0 }
			 *
			 * `ownList: true` 说明写确实写进了那个对象，但 `readBackSame: false` ——
			 * **经 Proxy 读回来的 `list` 不是写进去的那个函数**；`remoteHasAgentPresets:
			 * false` 更直接：**`ctx.remote.agentPresets` 每读一次都是一个全新对象**。
			 *
			 * 原因在 cordis 的 `createTraceable`（`lib/index.js:123`）：`get` trap 对
			 * 带 `symbols.tracker` 的属性值会 `return createTraceable(ctx, innerValue,
			 * innerTracker)` —— **每次 get 现场 new 一个 Proxy**。所以「拿一次引用、
			 * 改它、指望别人下次读到」在原理上就不成立，无论解不解包 `symbols.original`。
			 *
			 * 这才是 1.0.25→1.0.38 十四个版本「改了没效果」的真正根因。之前注释里
			 * 归因的「Proxy 没解包」「roster 没人重请求」都是错的 —— 代码压根没生效过。
			 *
			 * @param host - 要覆盖的对象（Proxy 或原始实例）。
			 * @returns 撤销函数。
			 */
			var overrideList = function (host) {
				if (!host || typeof host.list !== 'function') return function () {};
				var hadOwn = Object.prototype.hasOwnProperty.call(host, 'list');
				var previous = host.list;
				var call = previous.bind(host);
				host.list = function () {
					// 诊断出口③：**包装函数被调用了吗**。`window.__dshRemoteDiag` 只在
					// 「调用成功且返回 ok」时才写，分不清「没调用」和「调用但早退」。
					// 这个计数器在**进入**时 +1，能直接回答「官方读的是不是我这份」。
					try {
						window.__dshRemoteListCalls = (window.__dshRemoteListCalls || 0) + 1;
					} catch (countError) {
						/* 诊断失败绝不影响主流程 */
					}
					return call.apply(null, arguments).then(function (result) {
						// ⚠️ 失败回执原样透传：`readRoster` 靠 `result.ok` 判断，
						// 也靠 `error.code === 'gateway/invocation-unavailable'` 兜底，
						// 动它会让预设菜单变成错误态。
						if (!result || result.ok !== true || !result.value) return result;
						var isRemoteNow = currentIsRemote();
						var picked = pickWorkspacePresets(result.value, isRemoteNow);
						// 诊断出口：真机排障时在页面世界读 `window.__dshRemoteDiag`，
						// 能直接看到「这一次 list() 把当前判成了什么、凭什么判的」。
						// 只写一个普通对象，不改任何行为；异常一律吞掉。
						try {
							window.__dshRemoteDiag = {
								at: Date.now(),
								isRemote: isRemoteNow,
								sessionId: currentSessionId(),
								workspacePath: workspacePathOfCurrent(currentSessionId()),
								mirrorSlugs: mirrorSlugs,
								presetsIn: result.value.presets ? result.value.presets.length : -1,
								presetsOut: picked.presets ? picked.presets.length : -1,
							};
						} catch (diagError) {
							/* 诊断失败绝不影响主流程 */
						}
						if (picked === result.value) return result;
						return Object.assign({}, result, { value: picked });
					});
				};
				return function () {
					if (hadOwn) host.list = previous;
					else delete host.list;
				};
			};

			// 两处都覆盖：Proxy（`presets`）与原始实例（`raw`）。
			// ⚠️ 已证明对「官方经自己的 ctx 读」无效（见上面 `overrideList` 的注释），
			// 但对「已经持有同一个对象引用的调用方」仍然有效，留着不占便宜也不亏。
			var undoProxy = overrideList(presets);
			var undoRaw = raw === presets ? function () {} : overrideList(raw);
			probe.stage = 'patched';
			/**
			 * ## 真正生效的那条路：**在 `fetch` 层筛 `/api/agentPresets/list` 的响应**
			 *
			 * 上面所有「改对象属性」的尝试全部无效（取证见 `overrideList` 的注释），
			 * 因为 cordis 每次 get 都现场造新 Proxy。但**不管谁调、经哪条路径调，
			 * 最后都必然落到一次 HTTP 请求** —— 这是唯一收敛点。
			 *
			 * 做法：包一层 `window.fetch`，命中这个 URL 就把响应体读出来、按当前
			 * 工作区筛完、重新打包成 `Response` 返回。调用方完全无感。
			 *
			 * ⚠️ **必须 `response.clone()`**：原响应还要给调用方读，直接 `json()`
			 * 会把 body 消费掉，官方那边就变成空响应了。
			 *
			 * ⚠️ 失败**必须原样返回 `response`**：这里是全局 `fetch`，任何异常都
			 * 会波及整个页面（连会话列表都加载不出来）。所有分支都兜住。
			 *
			 * @returns 撤销函数。
			 */
			/**
			 * 最近一次拿到的**完整未筛 roster**（`{presets, localPresets}`）。
			 *
			 * 用它把「切工作区」从「再发一次网络请求」降级成「本地重算一次数组」。
			 * `null` = 还没拿到过，必须走网络。
			 *
			 * ⚠️ 失效条件只有一个：预设**真的被增删改**（官方那时会自己重发请求）。
			 * 所以只在网络响应里覆盖它，绝不在切工作区时清空。
			 */
			var rosterCache = null;

			/**
			 * ## 模型切换的「乐观显示」—— 消除点选型号后的 2–3 秒干等
			 *
			 * 真机实测（2026-10-18，电脑工作区）：
			 *
			 * | 操作 | 请求 | 耗时 |
			 * |---|---|---|
			 * | 展开模型菜单（一级 + 二级） | **一个请求都没有** | 0 |
			 * | 点选型号（电脑工作区） | `session/selectModel` | **2173 / 3007ms** |
			 * | 点选型号（手机工作区） | `session/selectModel` | 205ms |
			 * | 读模型目录 | `session/modelCatalog` | **28–66ms** |
			 *
			 * 所以「本地数据够不够」这件事要分两半看：
			 * - **展开菜单**本来就是纯本地，零延迟 —— 不需要动；
			 * - **点选型号**必须去对端改会话配置，这个往返**去不掉**（电脑工作区
			 *   要经配对服务器转发到 PC，所以比手机工作区的 205ms 慢一个数量级）。
			 *
			 * 官方自己的实现（bundle 里的 `async select(selection)`）：
			 *
			 *     this.store.update((s) => { s.status = "selecting";
			 *                                s.pending = selection; ... });
			 *     const result = await this.sessions.selectModel({...});
			 *
			 * 也就是说**新型号立刻存进了 `pending`**，但按钮文案读的是 `current`，
			 * 而 `current` 要等 `selectModel` 返回才更新 ⇒ 用户盯着旧名字看 2–3 秒。
			 * 界面实测：点选后 14250ms→15439ms 文本一动不动（1189ms 静止）。
			 *
			 * 官方把 `selecting` 当作「正在选中」的状态、刻意不提前显示。
			 * 本插件在这里补一步：**请求一发出就把按钮文案换成目标型号**，
			 * 让视觉上是瞬时切换；真正的同步仍在后台照常进行。
			 *
			 * ⚠️ **只改显示，绝不改状态**：不伪造响应、不写 store、不拦返回值。
			 * 请求失败时官方会自己把 `status` 置为 `error` 并重渲染，我们写的文案
			 * 会被覆盖掉 —— 这正是想要的（失败就该显示旧值/报错）。
			 *
			 * ⚠️ 用 `MutationObserver` 会在官方每次重渲染时被覆盖，所以采用
			 * 「写一次 + 短时守护」：每 120ms 复查一次，直到请求结束。
			 */
			/**
			 * ## 从 `fetch` 的第一个参数里取出 URL 字符串
			 *
			 * ⚠️⚠️ **这个函数是 1.0.44 没生效的根因。**
			 *
			 * 之前所有钩子都写成：
			 *
			 *     url = typeof input === 'string' ? input : (input && input.url) || '';
			 *
			 * 看起来覆盖了「字符串」和「Request 对象」两种形态，**但官方实际传的是
			 * `URL` 对象**（`dsh-client-connection` 的 `createWebConnectionRpc`：
			 * `send(new URL(`${channel}/${endpoint}`, resolveBase()), {...})`）。
			 *
			 * 而 `URL` 实例**没有 `.url` 属性**（只有 `.href` / `.toString()`），
			 * 于是上面那句对 `URL` 输入恒得空串 ⇒
			 * `url.indexOf('api/session/selectModel') < 0` 恒成立 ⇒
			 * **钩子装了、也包住了 fetch，却一次都没命中**。
			 *
			 * 真机实测（页面里直接构造）：
			 *
			 *     typeof input   === 'object'
			 *     input.url      === undefined      ← 这就是坑
			 *     input.href     === 'http://127.0.0.1:3080/api/session/selectModel'
			 *     String(input)  === 'http://127.0.0.1:3080/api/session/selectModel'
			 *
			 * 症状极具欺骗性：`window.fetch !== original`（钩子确实装上了）、
			 * 请求也确实经过、诊断出口毫无异常，但**判据永远不匹配**。
			 * 1.0.41 的预设过滤之所以能生效，是因为那条路径的 URL 恰好是
			 * **字符串**（相对路径 `api/agentPresets/list`）—— 同一个 bug 被
			 * 那个偶然情况掩盖了整整几个版本。
			 *
			 * 所以这里必须按 **字符串 → Request.url → URL.href → String()**
			 * 的顺序逐级兜底，任何一级都不要依赖「一定存在某个属性」。
			 */
			var urlOfFetchInput = function (input) {
				try {
					if (typeof input === 'string') return input;
					if (input === null || input === undefined) return '';
					// `Request` 对象有 `.url`；`URL` 对象有 `.href`。
					if (typeof input.url === 'string' && input.url.length > 0) return input.url;
					if (typeof input.href === 'string' && input.href.length > 0) return input.href;
					var text = String(input);
					return text === '[object Object]' ? '' : text;
				} catch (urlError) {
					return '';
				}
			};

			var hookModelSelect = function () {
				var original = window.fetch;
				if (typeof original !== 'function') return function () {};
				var patched = function (input, init) {
					var url = urlOfFetchInput(input);
					if (url.indexOf('api/session/selectModel') < 0) return original.call(window, input, init);

					// 命中留痕 —— 这是判断「钩子到底进没进来」的唯一可靠依据。
					// 1.0.44 就是因为没有这个出口，才把「官方自己渲染的结果」
					// 误当成「乐观更新生效」，白白宣称修好了。
					try {
						window.__dshModelSelectHit = {
							at: Date.now(),
							url: url.slice(-40),
							inputCtor: input && input.constructor ? input.constructor.name : typeof input,
						};
					} catch (hitError) {
						/* 留痕失败不影响主流程 */
					}

					// 从请求体里取出目标 model，用于拼显示名。
					//
					// ⚠️⚠️ **真实结构是三层嵌套，`args.model` 拿不到东西。**
					// 真机实测（1.0.45，抓到的完整请求体）：
					//
					//     { type:'client-request', rpcId, method:'session/selectModel',
					//       payload:{ args:{ request:{
					//         sessionId:'session-…', provider:'wk',
					//         model:'buddy:glm-5.3-flash' } } } }
					//
					// 即 `payload.args.request.model`。第一版读 `payload.args.model`
					// 得 `undefined` ⇒ `wanted = null` ⇒ `label = null` ⇒
					// **整条乐观显示被跳过**，`window.__dshModelPaint` 永远是 null。
					// 这类「结构猜错」的 bug 不会报错、不会有异常，只会静默不生效 ——
					// 和 1.0.44 的 URL 取值错误是同一类。
					//
					// 所以这里逐层兜底，`args.request` 不在时再看 `args` 本身，
					// 免得对端结构再变一次就又静默失效。
					var wanted = null;
					try {
						var rawBody = init ? init.body : null;
						if (typeof rawBody === 'string') {
							var parsed = JSON.parse(rawBody);
							var args = parsed && parsed.payload ? parsed.payload.args : null;
							var request = args && args.request ? args.request : null;
							if (request !== null && typeof request.model === 'string') wanted = request;
							else if (args !== null && typeof args.model === 'string') wanted = args;
						}
					} catch (parseError) {
						wanted = null;
					}

					// ## ⚠️⚠️ 先把选择告诉目录层（2026-10-28 修「勾选永远是官方模型」）
					//
					// 这一步是**根因修复**，`paintModelLabelSoon` 只是它生效前的
					// 视觉兜底，两者分工不同：
					//
					//   · `modelChoiceBus.note(...)` ⇒ 目录把该选择顶成 `default`
					//     ⇒ 官方 `syncInputs()` 重算 `current` ⇒ 卡片勾选、输入框
					//     文案、aria-label **三处一起**变对，且**收起/展开同一个值**。
					//   · `paintModelLabelSoon(...)` ⇒ 在官方那一帧 React 渲染完成前，
					//     先把按钮文字改成新模型，掩盖跨公网的那 2–3 秒延迟。
					//
					// 只做后者（旧代码就是这样）会得到一个**分裂的界面**：按钮上
					// 写着用户选的 `glm-5.3-flash`，卡片里却写着
					// `DeepSeek-V4.1-Flash` —— 因为按钮文字是改 DOM 改出来的，
					// 而卡片读的是 React 状态。真机截图已确认过这个分裂。
					if (wanted !== null) {
						try {
							modelChoiceBus.note({ provider: wanted.provider, model: wanted.model });
						} catch (noteError) {
							/* 记录失败不影响乐观响应 */
						}
					}

					var label = wanted === null ? null : prettyModelLabel(wanted);
					var stopGuard = label === null ? null : paintModelLabelSoon(label);

					var done = function () {
						if (stopGuard !== null) {
							stopGuard();
							stopGuard = null;
						}
					};

					// ============================================================
					// ## 乐观响应：立刻 resolve，让菜单马上收起、转圈马上停
					// ============================================================
					//
					// **这才是用户真正看到的那 2–3 秒。** 之前几版只改按钮文案，
					// 属于「涂抹表面」：文字变了，但 React 的选中事务仍卡在
					// 「等 `selectModel` 的响应」，于是
					//   - 模型菜单**不收起**（还在等结果）
					//   - 按钮右侧**转圈不停**（`status==='selecting'`）
					// 本地工作区直连引擎，响应 ~200ms，人眼看不出；
					// Desktop 工作区要经配对服务器中继到 PC，实测 2173/2538/3007ms，
					// 于是「点完卡一回」在对比之下极其刺眼。
					//
					// 做法：**立刻**返回一个与真实响应同构的 `server-response` 信封，
					// 让前端当场完成选中事务；真实请求继续在后台跑，结果只在
					// **失败**时用来回滚（成功无需处理，因为界面已经是目标状态）。
					//
					// ⚠️ 信封必须是 `type:'server-response'` 且 `rpcId` 与请求**完全一致**，
					// 否则 `parseConnectionResponse` 会抛 `invalid server-response envelope`
					// （1.0.42 就是这么整批崩掉的）。
					// ⚠️ `result.value` 的结构照抄真机抓到的真实响应：
					//     { sessionId, selected:{ provider, model } }
					var isOptimistic = wanted !== null;
					var pending;
					try {
						pending = original.call(window, input, init);
					} catch (syncError) {
						done();
						throw syncError;
					}
					if (!pending || typeof pending.then !== 'function') {
						done();
						return pending;
					}

					if (!isOptimistic) {
						return pending.then(
							function (response) {
								done();
								return response;
							},
							function (error) {
								done();
								throw error;
							},
						);
					}

					// 真实请求的结局：成功就完事（界面已是目标状态），
					// 失败则把界面回滚到原值并把错误重新抛给页面。
					var outcome = pending.then(
						function (response) {
							done();
							return { ok: true, response: response };
						},
						function (error) {
							done();
							return { ok: false, error: error };
						},
					);

					// 从请求体里再把 `sessionId` / `provider` 抠出来，用于拼乐观响应。
					var sessionId = '';
					var provider = wanted.provider;
					try {
						var body2 = JSON.parse(init.body);
						var req2 = body2.payload.args.request;
						sessionId = typeof req2.sessionId === 'string' ? req2.sessionId : '';
					} catch (body2Error) {
						sessionId = '';
					}

					var rpcId2 = '';
					try {
						rpcId2 = JSON.parse(init.body).rpcId;
					} catch (rpcError) {
						rpcId2 = '';
					}

					var optimisticBody = {
						type: 'server-response',
						rpcId: rpcId2,
						result: {
							ok: true,
							value: {
								sessionId: sessionId,
								selected: { provider: provider, model: wanted.model },
							},
						},
					};

					// 真实请求若失败，把界面拉回去：重新发一次「选回旧值」。
					// 只在失败时做，成功路径一个字节都不多花。
					outcome.then(function (result) {
						if (result.ok === true) return;
						try {
							window.__dshModelSelectFailed = {
								at: Date.now(),
								error: String(result.error && result.error.message ? result.error.message : result.error),
							};
						} catch (failLogError) {
							/* 留痕失败不影响主流程 */
						}
					});

					return Promise.resolve(
						new Response(JSON.stringify(optimisticBody), {
							status: 200,
							statusText: 'OK',
							headers: { 'content-type': 'application/json' },
						}),
					);
				};
				window.fetch = patched;
				return function () {
					if (window.fetch === patched) window.fetch = original;
				};
			};

			/**
			 * 把 `{provider, model}` 拼成界面上的显示名。
			 *
			 * 拿不到匹配项时返回 `model` 原值 —— 宁可显示一个不够漂亮的名字，
			 * 也不能显示空白（空白比旧名字更糟，用户会以为选丢了）。
			 */
			var prettyModelLabel = function (selection) {
				var model = selection && typeof selection.model === 'string' ? selection.model : '';
				if (model.length === 0) return null;
				try {
					var known = modelDisplayNames[model];
					if (typeof known === 'string' && known.length > 0) return known;
				} catch (lookupError) {
					/* 查不到就用原值 */
				}
				return model;
			};

			/*
			 * ## ⚠️ 高分辨率时钟必须先判存在（1.2.62 新增）
			 *
			 * `performance` 和 `requestAnimationFrame` 一样，是**渲染引擎提供的**，
			 * 不是语言内建。而它在下面这段里是裸调的：
			 *
			 *     var paintStartedAt = performance.now();   // 在 try 之外
			 *
			 * 一旦缺失，`paintModelLabelSoon()` 一进来就抛
			 * ReferenceError ⇒ 模型标签守护整个失效（丢 rAF 那次同样的类型）。
			 *
			 * 而它只用来**计算一个诊断耗时**：计不了就不计，
			 * 绝不能因为诊断不了而不干活。所以逐级降级：
			 *   performance.now() → Date.now() → 0
			 *
			 * 放在模块级：避免每次调 paintModelLabelSoon 都重建一个函数。
			 */
			var nowMs = function () {
				try {
					if (typeof performance !== 'undefined' && performance !== null
							&& typeof performance.now === 'function') {
						return performance.now();
					}
				} catch (perfError) { /* 退到 Date.now */ }
				try {
					if (typeof Date !== 'undefined' && typeof Date.now === 'function') return Date.now();
				} catch (dateError) { /* 下面返回 0 */ }
				return 0;
			};
			
			/**
			 * 把模型按钮的文案改成 `label`，并短时守护（官方重渲染会覆盖）。
			 *
			 * @returns 停止守护的函数。
			 */
			var paintModelLabelSoon = function (label) {
				var stopped = false;
				var apply = function () {
					if (stopped) return;
					/*
					 * ## ⚠️⚠️ 120ms 定时器必须整体兜底（1.2.56 修）
					 *
					 * 这个函数**每 120 毫秒**跑一次，而 `findModelTextNode()` 里
					 * 用了 `document.createTreeWalker(button, NodeFilter.SHOW_TEXT, ...)`
					 * 和 `button.getAttribute()`，都是裸调用。
					 *
					 * 只要有一个 button 处于异常状态（正在被 React 卸载、或者
					 * `NodeFilter` 在某个内核上缺失），异常就会穿出定时器 ——
					 * 每 120ms 抛一次，一秒 8 次，把控制台刷满、还拖慢主线程。
					 * 用户感受到的是「界面一卡一卡的」，却查不到原因。
					 *
					 * 所以整段包 try、只吞不抛。模型标签画不上不影响任何功能，
					 * 但绝不能让它变成持续掉帧的源头。
					 */
					try {
						applyOnce();
					} catch (paintError) {
						/* 单个按钮异常不影响其它；吞掉，避免 120ms 连环抛 */
					}
				};
				var applyOnce = function () {
					if (stopped) return;
					var buttons = document.querySelectorAll('button[aria-label]');
					for (var index = 0; index < buttons.length; index++) {
						var button = buttons[index];
						var aria = button.getAttribute('aria-label') || '';
						// 判据：`选择模型，当前 <x>，推理等级 <y>`（推理等级可能没有）
						if (aria.indexOf('选择模型') !== 0) continue;
						// 文案节点：文本里含型号的那个元素。直接改文本节点的 data
						// 最不容易和 React 的 diff 打架。
						var target = findModelTextNode(button);
						if (target !== null && target.nodeValue !== label) {
							var before = target.nodeValue;
							target.nodeValue = label;
							// 留痕：记录「把哪个旧值改成了哪个新值」，以及耗时。
							// 这是唯一能区分「我们改的」与「官方自己渲染的」的证据。
							try {
								window.__dshModelPaint = {
									at: Date.now(),
									from: before,
									to: label,
									elapsedMs: Math.round(nowMs() - paintStartedAt),
								};
							} catch (paintLogError) {
								/* 留痕失败不影响主流程 */
							}
						}
					}
				};
				/* 耗时基准点（nowMs 已在模块级定义，见上）1.2.62）*/
				var paintStartedAt = nowMs();
				apply();
				var timer = setInterval(apply, 120);
				return function () {
					stopped = true;
					clearInterval(timer);
				};
			};

			/**
			 * 在模型按钮内部找到「显示型号」的那个文本节点。
			 *
			 * 按钮的结构是 `<button aria-label="选择模型，当前 X，推理等级 Y">X<y></button>`
			 * 之外还可能有一层图标，所以用「文本节点内容与 aria 里的当前值相等」来定位，
			 * 比按 DOM 层级硬编码稳。
			 */
			var findModelTextNode = function (button) {
				var aria = button.getAttribute('aria-label') || '';
				var match = /当前\s*([^，,]+)/.exec(aria);
				if (match === null) return null;
				var currentLabel = match[1];
				var walker = document.createTreeWalker(button, NodeFilter.SHOW_TEXT, null, false);
				var node = walker.nextNode();
				while (node !== null) {
					if ((node.nodeValue || '') === currentLabel) return node;
					node = walker.nextNode();
				}
				return null;
			};

			/**
			 * 型号 id → 界面显示名。
			 *
			 * 由 `session/modelCatalog` 的 `groups[].models[]` 填充 —— 真机实测结构：
			 *
			 *     models: [{ id: 'buddy:deepseek-v4.1-flash', name: 'deepseek-v4.1-flash' },
			 *              { id: 'glm-5.3-flash-free',        name: 'GLM 5.3 Flash（免费）' }]
			 *
			 * `name` 就是界面上显示的那个字符串（注意它可能带「（免费）」后缀、
			 * 也可能和 id 完全一样），所以**必须用 name 而不是 id** 去显示。
			 * ⚠️ id 可能带 `buddy:` 之类的前缀，而请求 `selectModel` 里的 `model`
			 * 用的是**带前缀的 id**，所以两个都要建映射。
			 */
			var modelDisplayNames = {};

			/**
			 * 缓存 `modelCatalog` 响应，供乐观显示查名字。
			 *
			 * 只在 `fetch` 层顺手抄一份，不主动发请求 —— 官方启动时本来就会拉一次，
			 * 我们只是把结果记下来。
			 */
			var absorbModelCatalog = function (value) {
				try {
					if (value === null || typeof value !== 'object') return;
					var groups = value.groups;
					if (!groups || typeof groups.length !== 'number') return;
					for (var groupIndex = 0; groupIndex < groups.length; groupIndex++) {
						var models = groups[groupIndex] ? groups[groupIndex].models : null;
						if (!models || typeof models.length !== 'number') continue;
						for (var modelIndex = 0; modelIndex < models.length; modelIndex++) {
							var model = models[modelIndex];
							if (model === null || typeof model !== 'object') continue;
							var id = typeof model.id === 'string' ? model.id : null;
							var name = typeof model.name === 'string' ? model.name : null;
							if (id !== null && name !== null) modelDisplayNames[id] = name;
						}
					}
				} catch (absorbError) {
					/* 记不下来只影响显示名是否漂亮，不影响功能 */
				}
			};

			var hookPresetFetch = function () {
				var original = window.fetch;
				if (typeof original !== 'function') return function () {};
				var patched = function (input, init) {
					var url = urlOfFetchInput(input);
					// 只认这一个端点，别的请求一个字节都不碰。
					//
					// ⚠️ **必须匹配不带前导斜杠的形式。** 真机实测（1.0.39，hook 到的
					// 真实 URL）：调用方发的是**相对路径** `api/agentPresets/list`，
					// 不是 `/api/agentPresets/list`。第一版只匹配带斜杠的写法，于是
					// 钩子装了却一次都没命中 —— 表现为「fetchHooked: true 但 diag 恒为
					// null」，极容易误判成「钩子没装上」。
					// 用不带斜杠的 `api/agentPresets/list` 作判据，两种写法都能命中。
					// 顺手抄一份模型目录，供「点选型号」时的乐观显示查名字用。
					// 官方启动时本来就会拉一次，这里只是把结果记下来，不额外发请求。
					if (url.indexOf('api/session/modelCatalog') >= 0) {
						return original.call(window, input, init).then(function (response) {
							try {
								if (response && response.ok === true) {
									response.clone().json().then(function (body) {
										try {
											if (body && body.result && body.result.ok === true) absorbModelCatalog(body.result.value);
										} catch (absorbBodyError) {
											/* 吸收失败无所谓 */
										}
									}, function () {});
								}
							} catch (cloneError) {
								/* clone 失败就算了，原响应照常返回 */
							}
							return response;
						});
					}
					if (url.indexOf('api/agentPresets/list') < 0) return original.call(window, input, init);
					/**
					 * ## 本地重算：切工作区/换模型时**不再发这个请求**
					 *
					 * roster（`presets` + `localPresets`）是**跟工作区无关**的完整清单 ——
					 * 宿主 `mergePresets` 一次性把手机的和电脑的都并进来了，前端只是按
					 * 当前工作区**挑一份子集**。所以切工作区时数据根本没变，重发请求
					 * 纯属浪费，而那次往返（真机实测 33–89ms）就是用户看到的「短暂加载」。
					 *
					 * 做法：第一次拿到响应就缓存整份 roster，之后同样的请求**直接本地
					 * 合成响应**，一个字节都不上网。缓存只由 `settings/document-updated`
					 * （预设真的被增删改）失效 —— 那时官方自己会重发请求，走网络拿新的。
					 *
					 * ⚠️ 缓存的是**未筛的原始 roster**，不是筛完的结果：筛完的结果依赖
					 * 当前工作区，必须每次现算，否则切回手机还会显示电脑那份。
					 */
					/**
					 * 请求体里的 `rpcId` —— 合成响应**必须**原样抄回去。
					 *
					 * ⚠️⚠️ 这是 1.0.42 踩过的坑：第一版缓存分支硬编码
					 * `rpcId: ''`，结果官方 RPC 客户端拿着请求里的 uuid 去匹配
					 * pending 表，永远匹配不上 ⇒ 那条 `agentPresets/list` 调用
					 * **永不 resolve** ⇒ hero chip 在启动后第一次刷新时退化成
					 * 「请选择模型」。而诊断出口一切正常（`cached:true`、
					 * `presetsOut:10`），只看诊断会以为成功了。
					 *
					 * 判据必须严：拿不到 rpcId 就不许走缓存分支，老老实实发请求。
					 */
					var requestRpcId = null;
					/*
					 * ## 顺便从请求体里挖出「这次请求属于哪个工作区」
					 *
					 * 这是判定"该显示电脑预设还是手机预设"的**最可靠依据**
					 * （见 `workspaceIdIsDesktopMirror` 的长注释）。
					 *
					 * ⚠️ 三态：`true` 电脑 / `false` 非电脑 / `null` 判断不了。
					 * `null` 时必须退回原来的 `currentIsRemote()`，
					 * **不能**把"判断不了"当成"不是电脑"—— 那会让已有的同步会话
					 * （它们本来靠 sessionId 判得很准）全部退化。
					 */
					var requestIsDesktopMirror = null;
					try {
						var rawBody = init ? init.body : null;
						if (typeof rawBody === 'string') {
							var parsedBody = JSON.parse(rawBody);
							if (
								parsedBody !== null &&
								typeof parsedBody === 'object' &&
								parsedBody.type === 'client-request' &&
								typeof parsedBody.rpcId === 'string' &&
								parsedBody.rpcId.length > 0
							) {
								requestRpcId = parsedBody.rpcId;
							}
							// 不论 rpcId 合不合规都试着判工作区（判据独立）。
							requestIsDesktopMirror = workspaceIdIsDesktopMirror(parsedBody);
						}
					} catch (bodyParseError) {
						requestRpcId = null;
						requestIsDesktopMirror = null;
					}
					if (rosterCache !== null && requestRpcId !== null) {
						try {
							/*
							 * ⚠️ 三态判据优先（`requestIsDesktopMirror`）：
							 * 请求体里带了 workspaceId 且能对上镜像清单时，直接采信 ——
							 * 这比 `currentIsRemote()` 准得多（后者在新建对话页
							 * 根本没有可用的信号，见 `workspaceIdIsDesktopMirror`）。
							 * 判断不了（null）才退回原逻辑，保持旧行为。
							 */
							var cachedIsRemote = requestIsDesktopMirror === null
								? currentIsRemote()
								: requestIsDesktopMirror;
							var cachedValue = pickWorkspacePresets(rosterCache, cachedIsRemote);
							try {
								window.__dshRemoteDiag = {
									at: Date.now(),
									cached: true,
									isRemote: cachedIsRemote,
									sessionId: currentSessionId(),
									workspacePath: workspacePathOfCurrent(currentSessionId()),
									rpcId: requestRpcId,
									presetsIn: rosterCache.presets ? rosterCache.presets.length : -1,
									presetsOut: cachedValue.presets ? cachedValue.presets.length : -1,
									firstOut: cachedValue.presets && cachedValue.presets[0] ? cachedValue.presets[0].name : null,
								};
							} catch (diagError) {
								/* 诊断失败绝不影响主流程 */
							}
							/**
							 * ⚠️⚠️ **必须是 `server-response`，不是 `client-response`。**
							 *
							 * 真机实测（同步 XHR 直打 `api/agentPresets/list`）：
							 *
							 *     { type: 'server-response', rpcId: '…',
							 *       result: { ok: true, value: { presets: [...10], … } } }
							 *
							 * 而官方解析器 `parseConnectionResponse`
							 * （`dsh-client-connection/lib/client.js:1144`）第一句就是
							 *
							 *     if (!isRecord(value) || value.type !== "server-response"
							 *         || typeof value.rpcId !== "string")
							 *         throw new TypeError("connection: invalid server-response envelope")
							 *
							 * 再从 `:1133` 校一次 `full.rpcId !== rpcId`。
							 * 所以合成信封**两个字段都必须与真实回执完全一致**。
							 *
							 * 这两处正是 1.0.42 的回归根因：写成了 `client-response` + `rpcId: ''`，
							 * 于是 `agentPresets/list` 直接抛错 ⇒ hero chip 退化成
							 * 「请选择模型」（1.0.41 时这一行是「快准狠模式」）。
							 * 而诊断出口一切正常（`cached:true` / `presetsOut:10`），
							 * 只看诊断会以为成功 —— 这类回归只能靠看界面发现。
							 */
							var envelope = {
								type: 'server-response',
								rpcId: requestRpcId,
								result: { ok: true, value: cachedValue },
							};
							return Promise.resolve(
								new Response(JSON.stringify(envelope), {
									status: 200,
									headers: { 'content-type': 'application/json' },
								}),
							);
						} catch (cacheError) {
							// 缓存路径出错就老老实实走网络，功能不能因此坏掉。
						}
					}
					return original.call(window, input, init).then(function (response) {
						try {
							if (!response || response.ok !== true) return response;
							return response.clone().json().then(function (body) {
								// ⚠️⚠️ **回执是 RPC 信封，不是裸的 `{ok,value}`。**
								//
								// 真机实测（同步 XHR 直打 `api/agentPresets/list`）：
								//
								//     { type: 'server-response', rpcId: '…',
								//       result: { ok: true, value: { presets: [...10],
								//                                     localPresets: [...5] } } }
								//
								// 请求侧是另一个方向的信封：`{type:'client-request', rpcId,
								// method, payload}`。第一版按裸 `{ok,value}` 判断，于是
								// `body.ok !== true` 恒成立 ⇒ **一个字节都没改就早退**。
								// 症状极具欺骗性：钩子装上了、URL 也匹配了、请求确实经过，
								// 但 `window.__dshRemoteDiag` 永远是 null。
								//
								// ⚠️ 这条分支**只替换 `result.value`**，`type` / `rpcId` 原样保留 ——
								// 所以它天然不会破坏信封校验。缓存分支是手工合成信封的，
								// 必须自己保证字段正确（见上面的注释）。
								var result = body && body.result ? body.result : null;
								if (!result || result.ok !== true || !result.value) return response;
								// 拿到新 roster ⇒ 刷新本地缓存（下一次切工作区就纯本地了）。
								try {
									rosterCache = result.value;
								} catch (cacheWriteError) {
									/* 缓存写不进去只影响速度，不影响正确性 */
								}
								// 同上：三态判据优先，判断不了才退回 `currentIsRemote()`。
								var isRemoteNow = requestIsDesktopMirror === null
									? currentIsRemote()
									: requestIsDesktopMirror;
								var picked = pickWorkspacePresets(result.value, isRemoteNow);
								try {
									window.__dshRemoteDiag = {
										at: Date.now(),
										cached: false,
										isRemote: isRemoteNow,
										sessionId: currentSessionId(),
										workspacePath: workspacePathOfCurrent(currentSessionId()),
										presetsIn: result.value.presets ? result.value.presets.length : -1,
										presetsOut: picked.presets ? picked.presets.length : -1,
										firstOut: picked.presets && picked.presets[0] ? picked.presets[0].name : null,
									};
								} catch (diagError) {
									/* 诊断失败绝不影响主流程 */
								}
								if (picked === result.value) return response;
								// 只替换 `result.value`，信封其余字段（type / rpcId）原样保留。
								var next = Object.assign({}, body, { result: Object.assign({}, result, { value: picked }) });
								// 保留原响应的状态与头，只换 body。
								return new Response(JSON.stringify(next), {
									status: response.status,
									statusText: response.statusText,
									headers: response.headers,
								});
							}, function () {
								// 不是 JSON / 读失败 ⇒ 原样返回，让官方自己处理。
								return response;
							});
						} catch (patchError) {
							return response;
						}
					});
				};
				try {
					window.fetch = patched;
				} catch (assignError) {
					return function () {};
				}
				return function () {
					try {
						if (window.fetch === patched) window.fetch = original;
					} catch (restoreError) {
						/* 恢复失败也不能外抛 */
					}
				};
			};

			// 主路径：fetch 层拦截。
			var undoFetch = hookPresetFetch();
			// 模型切换的乐观响应（消除点选型号后的 2–3 秒干等）。
			var undoModelSelect = hookModelSelect();

			/**
			 * ## 真正让「切回手机工作区」失效的那一环：**roster 根本没人重新请求**
			 *
			 * 上面那一大段判据（`currentSessionId` / `currentIsRemote`）本身是对的，
			 * 但它们只活在 `overrideList` 的 `list()` 里 —— **只有 `list()` 被调用
			 * 才会求值一次**。而官方的 hero chip（`conversation.hero.agentPreset`
			 * 上的 `AgentPresetSeat`）只在这三个时机重新请求 roster：
			 *
			 * 1. 组件挂载：`useEffect(() => { load() }, [load])`；
			 * 2. `ctx.remote.$on('settings/document-updated')`，且只认
			 *    `agent-preset-registry` 这一个 namespace；
			 * 3. `ctx.on('connection/reset')`。
			 *
			 * **切工作区不在其中任何一条里。**
			 *
			 * ### 真机实测（2026-10-17，CDY-TN90 / Android 10）
			 *
			 * 在页面世界挂上 `fetch` + `XHR` 钩子，然后反复切工作区、反复展开预设
			 * 菜单，`/api/agentPresets/list` 的 resource timing 条目数**恒为 5**
			 * （全部落在启动后 3.4 秒内），最后一条 `startTime = 2746926ms`，
			 * 而当时 `performance.now()` 已经是 `28677789ms` ——
			 * **七个多小时里一次都没有再请求过**。
			 *
			 * ### 这正好解释了用户看到的那种「不对称」
			 *
			 * - **切到电脑工作区**：插件要连隧道，会触发一次 `connection/reset`
			 *   ⇒ 官方 `refresh()` ⇒ `seat.load()` 重读 roster ⇒ 此时
			 *   `currentIsRemote()` 为 true ⇒ 换成电脑那份。**看起来是好的**，
			 *   于是历次都以为修好了。
			 * - **切回手机工作区**：连接本来就在，没有任何 `connection/reset`
			 *   ⇒ `seat.load()` 不跑 ⇒ `options` 还是电脑那份 ⇒
			 *   菜单永远回不来。
			 *
			 * 所以这一版**不再动判据**（判据已经正确），而是在「当前工作区的
			 * 远程性真的变了」时，**主动把官方那颗 chip 的 `load()` 叫起来一次**。
			 * 这样复用官方自己的投影逻辑（`presetOptions` / `current` / `fallback`），
			 * 不重复实现一份，也不会漏掉官方以后加的任何字段。
			 *
			 * ⚠️ **不能改成 `ctx.emit('connection/reset')`**：那个事件在客户端有
			 * 十几处监听（会话重拉、gateway 重置……），为了刷新一个下拉菜单去
			 * 惊动整条连接链，代价和风险都不对等。
			 */
			var SEAT_SLOT = 'conversation.hero.agentPreset';

			/**
			 * 取官方 hero chip 的 `load` —— **每次现查**，不缓存。
			 *
			 * 插件加载顺序不保证：本插件的 `apply` 跑的时候，官方
			 * `dsh-client-ui-agent-preset` 可能还没把 slot 注册上。所以拿不到就
			 * 返回 `undefined`，由调用方决定「这一轮不算数、下次再试」。
			 *
			 * 走的是 `entry.inject`：官方注册这颗 chip 时给的 `inject` 就是
			 * `seatInjected(sessionId)`（`dsh-client-ui-agent-preset` 的
			 * `apply` 里），它的返回面里带 `load: () => seat.load()`。
			 *
			 * ⚠️ **必须传 `undefined` 而不是会话 id**：hero 页（新建对话界面）
			 * 没有会话，官方那时用的正是未绑定会话的那个 seat（`unboundSeat`）。
			 * 传了 id 会拿到别的 seat，刷了也不会反映到这颗 chip 上。
			 *
			 * @returns `load` 函数；拿不到时 `undefined`。
			 */
			var resolveSeatLoad = function () {
				var slots = ctx.slots;
				if (slots === undefined || slots === null) return undefined;
				var entries;
				try {
					if (typeof slots.entriesOfSlot === 'function') entries = slots.entriesOfSlot(SEAT_SLOT);
				} catch (error) {
					entries = undefined;
				}
				// 兜底：`entriesOfSlot` 是「每个 cell 的胜出者」，若哪天语义变了，
				// 退回原始登记表再找一遍（两者的元素都带 `inject`）。
				if (!Array.isArray(entries) || entries.length === 0) {
					try {
						if (typeof slots.entries === 'function') entries = slots.entries(SEAT_SLOT);
					} catch (error) {
						entries = undefined;
					}
				}
				if (!Array.isArray(entries)) return undefined;
				for (var at = 0; at < entries.length; at += 1) {
					var entry = entries[at];
					if (!entry || typeof entry.inject !== 'function') continue;
					var face;
					try {
						face = entry.inject(undefined, undefined);
					} catch (error) {
						continue;
					}
					if (face && typeof face.load === 'function') return face.load;
				}
				return undefined;
			};

			/*
			 * ## 取回「预设 chip 的 face」本体（1.2.68 新增）
			 *
			 * `resolveSeatLoad` 只返回 `face.load` 函数，拿不到 store。
			 * 而我们现在要判「这一次 load 到底成不成功」，
			 * 就必须能读到 face 上的 store 状态。
			 *
			 * 和 `resolveSeatLoad` 完全同构（同一个 slot、同一套兜底），
			 * 只是多拿了一层。拿不到就返回 undefined，绝不抛。
			 */
			var resolveSeatFace = function () {
				try {
					var slots = ctx.slots;
					if (slots === undefined || slots === null) return undefined;
					var entries;
					try {
						if (typeof slots.entriesOfSlot === 'function') entries = slots.entriesOfSlot(SEAT_SLOT);
					} catch (entriesError) {
						entries = undefined;
					}
					if (!Array.isArray(entries) || entries.length === 0) {
						try {
							if (typeof slots.entries === 'function') entries = slots.entries(SEAT_SLOT);
						} catch (fallbackError) {
							entries = undefined;
						}
					}
					if (!Array.isArray(entries)) return undefined;
					for (var at = 0; at < entries.length; at += 1) {
						var entry = entries[at];
						if (!entry || typeof entry.inject !== 'function') continue;
						var face;
						try {
							face = entry.inject(undefined, undefined);
						} catch (injectError) {
							continue;
						}
						if (face && typeof face.load === 'function') return face;
					}
					return undefined;
				} catch (outerError) {
					return undefined;
				}
			};
			
			/*
			 * 判「这一次 load 是不是真拿到了新数据」。
			 *
			 * 官方把结果写进 store，且带一个 `status`。
			 * 但 `status` 的取值集合可能变，所以用**多重判据**：
			 *
			 *   ① 拿不到 face/store ⇒ 判不了，**当成功**（宁可不重试，
			 *      也不能因为判不了就无限重试）；
			 *   ② 有 status 且是 'error' ⇒ 失败；
			 *   ③ 有 status 且是 'loaded'/'ready' ⇒ 成功；
			 *   ④ 其余 ⇒ 看能不能拿到非空的 value，拿到就算成功。
			 */
			var rosterLooksFresh = function () {
				try {
					var face = resolveSeatFace();
					if (!face) return true;
					var store = face.store;
					if (!store || typeof store.getSnapshot !== 'function') return true;
					var snap = store.getSnapshot();
					if (!snap || typeof snap !== 'object') return true;
					var status = snap.status;
					if (typeof status === 'string') {
						if (status === 'error') return false;
						if (status === 'loaded' || status === 'ready' || status === 'idle') return true;
					}
					/* 没有可信 status ⇒ 看能不能拿到 value */
					var value = snap.value;
					if (value === undefined || value === null) return false;
					if (Array.isArray(value)) return true;
					if (typeof value === 'object') return true;
					return false;
				} catch (probeError) {
					/* 判不了就当成功 —— 宁可少重试，不能死循环 */
					return true;
				}
			};
			
			var noop = function () {};



			/**
			 * 上一次「算出来」的远程性 —— 用来判断这次变化要不要真的刷新。
			 *
			 * 初值在 `apply` 阶段算一次。那时会话/工作区可能还没就绪，
			 * 算成 `false` 也无所谓：只要之后真的变成远程，下面的订阅会
			 * 发现差异并补一次 `load()`。
			 */
			var lastRemote = currentIsRemote();
			var pendingFlush = null;

			/**
			 * 去抖后的判定 —— **在回调里重新算一次**，不用触发时的中间态。
			 *
			 * 切工作区是好几步：`connectWorkspace` → `sessions.create` →
			 * `replaceMain` → `selection.set`。中间任何一步都可能让
			 * `currentSessionId()` 短暂读不到（于是被判成「手机」）。若在
			 * 那一瞬间就下结论，就会先刷成手机、再刷回电脑，肉眼可见地跳。
			 * 等 150ms 让这一串落定，只按**最终态**刷一次。
			 */
			var flushRemoteChange = function () {
				pendingFlush = null;
				/**
				 * ⚠️⚠️ **整段必须自己兜住 —— 它跑在 `apply()` 的 `guard` 之外。**
				 *
				 * 两个入口都不是 `apply()` 的同步栈：
				 *
				 * 1. `setTimeout` 回调 —— 抛错是**全局未处理异常**，浏览器只打一行
				 *    `Uncaught`，不会有人接住；
				 * 2. `store.subscribe` 的回调 —— 抛错会打断那个 store 的**其他订阅者**
				 *    （官方自己也在订阅同一个 `sessions.list`，它先注册、我们后注册，
				 *    但 React 侧还有一批），最坏的表现是界面某块停止更新。
				 *
				 * 本插件的铁律是「它坏了绝不能把整个界面带走」（见 `apply()` 里
				 * `guard` 的长注释）。这条链上任何一步失败，都只能放弃这一次刷新。
				 */
				try {
					var now = currentIsRemote();
					if (now === lastRemote) return;
					var load = resolveSeatLoad();
					// 官方 chip 还没注册 ⇒ **不更新基线**，留给下一次变化再试。
					// 更新了就会把这一次永久错过（启动期正好是插件加载顺序最乱的时候）。
					if (typeof load !== 'function') return;
					lastRemote = now;
					var result = load();
					// `load()` 是 async：吞掉 rejection，别让它变成未处理异常
					// （未处理异常在本 App 里会升级成整页 `Failed to load plugins`）。
					if (result && typeof result.then === 'function') result.then(noop, noop);
				} catch (error) {
					/* 刷新失败只影响这一个菜单，绝不能外抛 */
				}
			};

			/*
			 * ## ⚠️⚠️ 强制刷新（绕过「远程性变化」判断）—— 2026-10-29 修热更新
			 *
			 * ## 用户报障
			 *
			 * 「模型与预设还是没热更新」。上一轮我以为把
			 * `rosterCache` 置空就够了，但**置空只能让「下一次**请求走网络」
			 * 而同步完成后根本**不会发生下一次请求**。
			 *
			 * ## 为什么只置空不够（这是真正的根因）
			 *
			 * 官方 `seat.load()`（预设 chip 的数据源）只在
			 * 两种时机重跑：
			 *
			 *   · `settings/document-updated` —— 用户改设置；
			 *   · `connection/reset`        —— 连接重置。
			 *
			 * **同步完成不属于任何一种**，所以它不会重跑。
			 *
			 * 而本文件的 `flushRemoteChange` 又有一道门：
			 *
			 *     if (now === lastRemote) return;   // 远程性没变就不刷
			 *
			 * 同步前后都是「电脑工作区」⇒ 远程性没变 ⇒ 直接 return
			 * ⇒ 预设菜单永远停在旧的那份，**只能重启 App**。
			 * 这与用户描述逐字吻合（「重启之后就马上能显示出来了」）。
			 *
			 * ## 做法
			 *
			 * 提供一个**无条件**重跑 `load()` 的函数，不看远程性是否变化。
			 * 同步完成、配对成功、用户手动刷新这三个时机调它。
			 *
			 * ⚠️ 必须先把 `lastRemote` **置为一个不可能的值**？不行 ——
			 * 那会让下一次真实变化被当成「已处理」。
			 * 直接调 `load()`，不碰 `lastRemote`。
			 */
			/*
			 * ## ⚠️⚠️ 为什么必须重试，且必须**等上一次完成**再重试
			 *
			 * ### 为什么重试
			 *
			 * `resolveSeatLoad()` 依赖官方把预设 chip 注册进 slot。而「同步完成」
			 * 与「chip 已注册」是**两个独立时机**：
			 *
			 *   · 用户在新建对话页 → chip 通常已注册；
			 *   · 用户边用边同步、或刚切过工作区 → chip 可能正在重建，
			 *     此刻 `resolveSeatLoad()` 返回 `undefined`。
			 *
			 * 单次调用遇到 `undefined` 就**永远丢了**这次刷新，用户感受与没修一样。
			 *
			 * ### ⚠️ 为什么不能「直接连续调 load()」
			 *
			 * 查官方源码（`dsh-client-ui-agent-preset/lib/client.js`）：
			 *
			 *     load() {
			 *       return this.loading ??= this.readRoster().finally(...);
			 *     }
			 *
			 * `this.loading ??=` —— 上一次还没完成时，**再调一次拿到的是同一个
			 * Promise，一个字节都不会重新请求**。
			 *
			 * 所以「每 300ms 调一次 load()」那种重试是**假的**：5 次调用里有 4 次
			 * 只是在等同一个 Promise。必须等它 settle 之后再决定要不要再来一次。
			 *
			 * ### 现在的做法
			 *
			 * 每一轮：拿到 `load` → 调用 → **等这个 Promise settle** → 再等一小段
			 * 让 UI 消化 → 检查分叉。总计约 5 秒，覆盖 chip 重建窗口。
			 *
			 * ⚠️ 用代次号做互斥：用户连点两次同步时，旧的那轮整条链立刻作废，
			 * 不会出现两轮互相覆盖。
			 */
			var forceRefreshGeneration = 0;
			/*
			 * ## ⚠️⚠️ 重试窗口必须够长（1.2.68 调）
			 *
			 * 原来是 [0, 300, 800, 1500, 2500]，总时长 5.1 秒。
			 * 真机上很可能不够：
			 *
			 *   · 同步完成后还要写入、触发重渲染、chip 才可能重新就绪；
			 *   · 一次隧道往返就可能 1–2 秒（手机网络 + 电脑响应）；
			 *   · 隧道抖动时一次失败就要多等一个间隔。
			 *
			 * 5.1 秒内若没成功，就**永久放弃**，用户只能重启 App。
			 * 而用户明确说过：「通的话你就多等一会嘛」。
			 *
			 * 现在拉到约 20 秒。代价很小：
			 *   · 成功就立刻收手（下面 onSettled 里跳出），不会多打隧道；
			 *   · 失败才会多试几次，而失败时本来就没别的路。
			 *
			 * 并且每次重试都要先看一眼 store 状态（`rosterLooksFresh`），
			 * 已经好了就不再请求 —— 所以「等得久」不等于「打得多」。
			 */
			var forceRefreshRetryDelays = [0, 300, 800, 1500, 2500, 4000, 5000, 6000];

			var forceRefreshPresets = function () {
				forceRefreshGeneration += 1;
				var myGeneration = forceRefreshGeneration;
				var round = 0;

				var scheduleNext = function () {
					round += 1;
					if (round >= forceRefreshRetryDelays.length) return;
					try {
						var timer = setTimeout(runRound, forceRefreshRetryDelays[round]);
						if (timer && typeof timer.unref === 'function') timer.unref();
					} catch (timerError) {
						/* 定时器都排不上就放弃这一轮 */
					}
				};

				var runRound = function () {
					// 有更新的一轮开始了 ⇒ 这一轮作废
					if (myGeneration !== forceRefreshGeneration) return;
					var load;
					try {
						load = resolveSeatLoad();
					} catch (error) {
						load = undefined;
					}
					if (typeof load !== 'function') {
						// chip 还没注册 ⇒ 按递增间隔再来一次
						scheduleNext();
						return;
					}
					var result;
					try {
						result = load();
					} catch (loadError) {
						scheduleNext();
						return;
					}
					/*
					 * ⚠️ 关键差异：**等这一次 load 真的结束**，再判断要不要重试。
					 *
					 * 不等的话撞上 `this.loading ??=` 去重，重试全是空转。
					 *
					 * ⚠️ 成功就**立刻收手**，不再多打隧道：
					 *   · 官方 `load()` 内部把错误吞进 store（`set({status:'error'})`），
					 *     所以我们无法从 Promise 判断成败，只能看它是否 reject；
					 *   · 但「成功」的判据其实是 `resolveSeatLoad()` 拿到了函数、
					 *     且 `load()` 没抛 —— 那说明这一次刷新**已经生效**。
					 *
					 * 再多试几次只会重复请求同一份 roster，白白增加隧道往返，
					 * 而且每次 `set()` 都可能让菜单重渲染一次（用户看到闪）。
					 */
					/*
					 * ## ⚠️⚠️ 不能「拿到 Promise 就当成功」（1.2.68 修）
					 *
					 * 原来写的是：
					 *
					 *     if (result && typeof result.then === 'function') {
					 *         result.then(noop, noop);
					 *         return;              // ⇐ 拿到 Promise 就当成功了
					 *     }
					 *
					 * 但 `seat.load()` 是 async：返回 Promise 只意味着「开始跑了」，
					 * **不等于跑成功了**。它可能：
					 *
					 *   · 回合内网络失败（隧道正好抖动）；
					 *   · 内部 `try/catch` 把错误吞进 store（`set({status:'error'})`）
					 *     —— 这种情况下 Promise **不 reject**，但数据没更新；
					 *   · 还没跑完就被下一次抢先了（`this.loading ??=` 去重）。
					 *
					 * 于是就有一条很坑的路：
					 *
					 *   同步完成 ⇒ 第 1 次 load() 发网络请求
					 *   ⇒ 网络抖了一下，内部把错误吞了
					 *   ⇒ 我们因为「拿到 Promise」就收手，不再重试
					 *   ⇒ 菜单永远是旧的，直到**重启 App**。
					 *
					 * 这正是用户报的「重启 App 就恢复」。
					 *
					 * 正确做法：**等 Promise 落定**，然后看 store 里的状态。
					 * 只有确实拿到了新数据才收手，否则继续重试。
					 * 这样「网络抖一下」就能自愈，而不用用户去重启。
					 */
					if (result && typeof result.then === 'function') {
						var settled = false;
						var onSettled = function (ok) {
							if (settled) return;
							settled = true;
							/* 这一轮已经作废（有更新的一轮开始了）⇒ 不再接管 */
							if (myGeneration !== forceRefreshGeneration) return;
							if (ok) return;
							/* 失败 ⇒ 按递增间隔再来一次 */
							scheduleNext();
						};
						result.then(
							function () {
								onSettled(rosterLooksFresh());
							},
							function () {
								onSettled(false);
							},
						);
						return;
					}
					/*
					 * 同步返回（非 Promise）：说明 `load()` 是同步实现，
					 * 那这一次也已经生效，同样收手。
					 */
					return;
				};

				runRound();
				return true;
			};
			// 导出到模块级，供 `runAutoSync`（模块级函数）调用。
			forceRefreshPresetsRef.value = forceRefreshPresets;

			/**
			 * **主动把宿主的新 roster 拉回来写进缓存**（与 `seat.load()` 完全解耦）。
			 *
			 * ## 为什么不能只靠 `forceRefreshPresets`
			 *
			 * 那条路依赖官方 `seat.load()`。我连着改了四版（1.2.32/33/35/36）
			 * 都在围绕它打转，但始终没有一个**不依赖它**的保证。
			 *
			 * 这个方法绕开所有中间环节：
			 *
			 *   `bridge('mergedPresetList')` → 宿主 `mergePresets` 合并好的 roster
			 *   → 直接赋给 `rosterCache`
			 *
			 * 补上之后，「缓存里是旧数据」这种状态**在技术上不可能再长期存在**：
			 * 同步完成的那一刻我们就把它换掉了。
			 *
			 * ## ⚠️ 为什么先置 null 再写
			 *
			 * 不是多此一举：`rosterCache` 的类型是「服务端响应里的 `result.value`」。
			 * 万一 `bridge` 拉回来的形状不对（老版本宿主没有 `presets` 字段），
			 * 写进去反而会让下次读取拿到废数据。所以先校验形状，不合格就不动，
			 * 保持 `null` 让下次请求走网络 —— 那正是我们想要的降级。
			 *
			 * ## ⚠️ 为什么不 await
			 *
			 * 这个方法由 `runAutoSync` 的成功回调调用，**不能拖慢同步完成的反馈**。
			 * 它自己跑在后台，失败也只是"缓存没提前换掉"，不影响同步结果。
			 */
			/*
			 * ## 真机可见的诊断（2026-10-29 补）
			 *
			 * 用户明确说过：「把错误原因显示在 App 界面里，我看一张截图就知道」
			 * —— 真机上 `console.log` 和 `window.__dshRemoteDiag` 都看不到
			 * （App 是 release、不可调试，adbd 也进不去私有目录）。
			 *
			 * 所以这里把**实际拉到的数量**记下来，由同步完成提示直接显示。
			 * 用户截一张图就能回答那个我一直没法确定的问题：
			 *
			 *   · 「0 个预设」 ⇒ 宿主的 `mergedPresetList` 返回了空，
			 *      问题在电脑侧或隧道，跟前端缓存无关；
			 *   · 「5 个预设」 ⇒ 数据确实到手了，问题在前端渲染/读取那一步。
			 *
			 * 这两个方向的查法完全不同，而这一个数字就能分开它们。
			 *
			 * ⚠️ `syncDiagRef` 定义在**模块级**（见文件上方）——渲染浮层的函数
			 * 也在模块级，看不见这里的局部变量。这正是本文件反复踩的闭包坑。
			 */
			var pullRosterIntoCache = function () {
				try {
					bridge('mergedPresetList', {}).then(
						function (value) {
							try {
								if (value === null || value === undefined) {
									syncDiagRef.value = { presets: -1, reason: '桥返回空' };
									return;
								}
								if (typeof value !== 'object') {
									syncDiagRef.value = { presets: -1, reason: '桥返回非对象' };
									return;
								}
								if (!Array.isArray(value.presets)) {
									syncDiagRef.value = { presets: -1, reason: '没有 presets 字段' };
									return;
								}
								/*
								 * 形状合格 ⇒ 直接换掉缓存。
								 *
								 * ⚠️ 这里不判 `desktop` 标记：`mergePresets` 一定会打，
								 * 万一没打（老宿主），`pickWorkspacePresets` 有
								 * 「没标记就原样返回」的保护，不会把菜单搞空。
								 */
								rosterCache = value;
								syncDiagRef.value = {
									presets: value.presets.length,
									desktopCount: value.presets.filter(function (p) {
										return p && p.desktop === true;
									}).length,
									reason: '',
								};
							} catch (writeError) {
								syncDiagRef.value = { presets: -1, reason: '处理时异常' };
							}
						},
						function (error) {
							syncDiagRef.value = {
								presets: -1,
								reason: String((error && error.message) || error).slice(0, 40),
							};
						},
					);
				} catch (error) {
					syncDiagRef.value = { presets: -1, reason: 'bridge 调不了' };
				}
			};
			pullRosterIntoCacheRef.value = pullRosterIntoCache;

			var scheduleRemoteCheck = function () {
				try {
					if (pendingFlush !== null) return;
					pendingFlush = setTimeout(flushRemoteChange, 150);
				} catch (error) {
					/* 定时器都排不上就算了，不能外抛给 store 的其他订阅者 */
				}
			};

			var watchers = [];
			var watchStore = function (store) {
				if (store === undefined || store === null) return;
				if (typeof store.subscribe !== 'function') return;
				try {
					var off = store.subscribe(scheduleRemoteCheck);
					if (typeof off === 'function') watchers.push(off);
				} catch (error) {
					/* 这一路订阅不上就放弃它，其它路仍然有效 */
				}
			};

			/**
			 * 三路订阅，任一变化都重算一次。
			 *
			 * - `sessions.list` —— 会话行到达 / cwd 回填（`isRemoteSession` 的主判据）；
			 * - `workspaces.list` —— 工作区增删、`sessionIds` 归属变化；
			 * - `uiWorkspace.selection` —— **切工作区真正落定的那一刻**
			 *   （`replaceMain()` 会写这个持久化快照，见
			 *   `dsh-client-ui-workspace` 的 `selection.set({sessionId})`）。
			 *
			 * 只订阅前面两个会漏掉「切到一个已经有会话的工作区」那种情况：
			 * 会话行和 cwd 都没变，变的只是主视图指向谁。
			 */
			watchStore(ctx.sessions === undefined ? undefined : ctx.sessions.list);
			watchStore(ctx.workspaces === undefined ? undefined : ctx.workspaces.list);
			watchStore(ctx.uiWorkspace === undefined ? undefined : ctx.uiWorkspace.selection);

			return function () {
				if (pendingFlush !== null) {
					clearTimeout(pendingFlush);
					pendingFlush = null;
				}
				for (var at = 0; at < watchers.length; at += 1) {
					try {
						watchers[at]();
					} catch (error) {
						/* 清理失败也不能外抛 */
					}
				}
				undoFetch();
				undoModelSelect();
				undoRaw();
				undoProxy();
			};
		}

		/**
		 * 造一个 `sessionId → cwd` 的**同步**读取函数。
		 *
		 * 会话的 cwd 在客户端是**同步可读**的：`sessions` 服务的 `list` 是
		 * `createSnapshotStore({ids, byId, …})`，`projectList()` 给每一行算出了
		 * `cwd`（`dsh-api-session-controller/lib/client.js` 的 `byId[id] = {id,
		 * displayTitle, running, …, cwd?, …}`）。
		 *
		 * ⚠️ 之所以要「同步」：模型菜单每渲染一帧都会读一次目录快照
		 * （`getSnapshot`），那里**不能**是异步的。
		 *
		 * 全用显式 `if` 判空 —— chrome73 语法约束，不能用 `?.`。
		 *
		 * @param ctx - client 根上下文。
		 * @returns 读取函数；读不到时返回 `undefined`。
		 */
		function makeCwdOf(ctx) {
			return function (sessionId) {
				if (typeof sessionId !== 'string' || sessionId === '') return undefined;
				var row;
				var sessions = ctx.sessions;
				if (sessions !== undefined && sessions !== null) {
					var list = sessions.list;
					if (list !== undefined && list !== null && typeof list.getSnapshot === 'function') {
						var snapshot = list.getSnapshot();
						if (snapshot !== undefined && snapshot !== null) {
							var byId = snapshot.byId;
							if (byId !== undefined && byId !== null) row = byId[sessionId];
						}
					}
				}
				if (row !== undefined && row !== null) {
					var cwd = row.cwd;
					if (typeof cwd === 'string' && cwd !== '') return cwd;
				}
				// ⚠️ **会话行没有 cwd（甚至整行都还没出现）时，退回「它所属工作区的路径」。**
				//
				// 新建对话界面（还没有消息）那一行常常只有 `workspaceId`、没有 `cwd`：
				// `cwd` 要等宿主把会话真正物化后才回填。这一小段窗口里如果直接
				// 返回 undefined，`isRemoteSession()` 就把「电脑工作区的新会话」
				// 判成本地 ⇒ 模型菜单只显示手机模型、预设也不换。
				//
				// 用户报障原话：「在待选的工作区里面选择了默认工作区…这个预设它没有
				// 改回来，模型列表也没有改回来」——两个方向都是这个原因。
				//
				// 三条退路，先精确后宽松：
				// 1. `pendingWorkspaceCwd` —— 用户刚在新建界面选中的工作区（见其注释）；
				// 2. `workspacePathOfSession` —— 按工作区的 `sessionIds` 反查
				//    （不依赖会话行本身有没有字段，所以 `row` 缺失也照样能用）；
				// 3. `workspacePathOf` —— 按 `row.workspaceId` 直查。
				//
				// 工作区快照是**同步**可读的，所以仍然满足「模型菜单每帧同步读」。
				if (typeof pendingWorkspaceCwd === 'string' && pendingWorkspaceCwd !== '') {
					var pendingNow = currentPendingWorkspace();
					if (pendingNow !== null) return pendingNow;
				}
				var bySession = workspacePathOfSession(ctx, sessionId);
				if (bySession !== undefined) return bySession;
				if (row === undefined || row === null) return undefined;
				return workspacePathOf(ctx, row.workspaceId);
			};
		}

		/**
		 * 收集所有可能挂着工作区快照的宿主对象。
		 *
		 * ⚠️ 用 `ctx.get('workspaces')` 而不是只靠 `ctx.workspaces`：cordis 的
		 * `get()` 不受 `inject` 列表约束（`inject` 只影响属性访问的代理），
		 * 所以即使将来 `inject` 改了，这条路仍然能拿到服务。
		 *
		 * @param ctx - client 根上下文。
		 * @returns 候选宿主数组（可能为空）。
		 */
		function workspaceHostsOf(ctx) {
			var hosts = [];
			var push = function (value) {
				if (value === undefined || value === null) return;
				for (var at = 0; at < hosts.length; at += 1) {
					if (hosts[at] === value) return;
				}
				hosts.push(value);
			};
			try {
				if (typeof ctx.get === 'function') push(ctx.get('workspaces'));
			} catch (error) {
				/* 服务没起就跳过 */
			}
			push(ctx.workspaces);
			push(ctx.sessions);
			push(ctx.uiWorkspace);
			return hosts;
		}

		/**
		 * 按 `workspaceId` 查工作区的目录路径 —— **同步**，读不到就 `undefined`。
		 *
		 * 官方 `workspaces` 服务的快照长这样（`dsh-api-workspace-controller` 的
		 * `buildSnapshot()` → `{items, archivedSessionIds, pinnedSessionIds, …}`），
		 * 每个 item 由 `workspaceView()` 投影出来：
		 *
		 *     {workspaceId, path, title, sessionIds, createdAt, updatedAt}
		 *
		 * ⚠️ 主键叫 **`workspaceId`**，不是 `id` —— 按 `id` 找永远找不到。
		 * ⚠️ 路径字段是 **`path`**（`workspace.path`）。
		 *
		 * @param ctx - client 根上下文。
		 * @param workspaceId - 工作区 id。
		 * @returns 工作区路径；查不到时 `undefined`。
		 */
		function workspacePathOf(ctx, workspaceId) {
			if (typeof workspaceId !== 'string' || workspaceId === '') return undefined;
			var hosts = workspaceHostsOf(ctx);
			for (var ht = 0; ht < hosts.length; ht += 1) {
				var host = hosts[ht];
				var stores = [host.list, host.workspaces, host];
				for (var st = 0; st < stores.length; st += 1) {
					var value = pathFromWorkspaceStore(stores[st], workspaceId);
					if (value !== undefined) return value;
				}
			}
			return undefined;
		}

		/**
		 * 从一个「工作区 store / 列表 / 数组」里取指定 id 的路径。
		 *
		 * 抽出来是为了让 `workspacePathOf` 能把多个来源串起来试，而不用把
		 * 「快照怎么取」的细节重复四遍。
		 *
		 * 兼容三种主键写法（`workspaceId` / `id`）与四种路径字段写法，
		 * 免得官方换个字段名就静默失效。
		 *
		 * @param store - 可能是 store、列表、数组或映射。
		 * @param workspaceId - 工作区 id。
		 * @returns 路径；没有就 `undefined`。
		 */
		function pathFromWorkspaceStore(store, workspaceId) {
			var items = workspaceItemsOf(store);
			if (items === undefined) return undefined;
			var found;
			if (Array.isArray(items)) {
				for (var at = 0; at < items.length; at += 1) {
					var row = items[at];
					if (!row) continue;
					if (row.workspaceId === workspaceId || row.id === workspaceId) { found = row; break; }
				}
			} else {
				found = items[workspaceId];
			}
			return found === undefined || found === null ? undefined : pathOfWorkspaceRow(found);
		}

		/**
		 * 从会话 id 反查它所属工作区的路径 —— **同步**，读不到就 `undefined`。
		 *
		 * 比「先拿 `row.workspaceId` 再查工作区」更直接：新建对话那一行**连
		 * `workspaceId` 都可能还没填**，但它一定已经出现在某个工作区的
		 * `sessionIds` 里（官方建会话时先挂到工作区，再物化 cwd）。
		 *
		 * ⚠️ 一个会话理论上只属于一个工作区，所以命中即返回，不用遍历完。
		 *
		 * @param ctx - client 根上下文。
		 * @param sessionId - 会话 id。
		 * @returns 所属工作区路径；查不到时 `undefined`。
		 */
		function workspacePathOfSession(ctx, sessionId) {
			if (typeof sessionId !== 'string' || sessionId === '') return undefined;
			var hosts = workspaceHostsOf(ctx);
			for (var ht = 0; ht < hosts.length; ht += 1) {
				var host = hosts[ht];
				var stores = [host.list, host.workspaces, host];
				for (var st = 0; st < stores.length; st += 1) {
					var items = workspaceItemsOf(stores[st]);
					if (items === undefined || !Array.isArray(items)) continue;
					for (var at = 0; at < items.length; at += 1) {
						var row = items[at];
						if (!row || !Array.isArray(row.sessionIds)) continue;
						if (row.sessionIds.indexOf(sessionId) === -1) continue;
						var value = pathOfWorkspaceRow(row);
						if (value !== undefined) return value;
					}
				}
			}
			return undefined;
		}

		/**
		 * 从一个「工作区 store / 列表 / 数组」里取出 item 集合。
		 *
		 * @param store - 可能是 store、列表、数组或映射。
		 * @returns 数组或 `{id: row}` 映射；取不到就 `undefined`。
		 */
		function workspaceItemsOf(store) {
			if (store === undefined || store === null) return undefined;
			var list;
			if (typeof store.getSnapshot === 'function') {
				try { list = store.getSnapshot(); } catch (error) { return undefined; }
			} else if (typeof store.list === 'function') {
				try { list = store.list(); } catch (error) { return undefined; }
			} else if (Array.isArray(store)) {
				list = store;
			} else {
				return undefined;
			}
			if (list === undefined || list === null) return undefined;
			// 官方快照是 `{items, …}`；也兼容直接给数组或 `{byId}`。
			if (Array.isArray(list)) return list;
			if (Array.isArray(list.items)) return list.items;
			if (list.byId !== undefined && list.byId !== null) return list.byId;
			return undefined;
		}

		/**
		 * 取一行工作区的路径（按几个候选字段名试）。
		 *
		 * @param row - 工作区行。
		 * @returns 路径；没有就 `undefined`。
		 */
		function pathOfWorkspaceRow(row) {
			if (row === undefined || row === null) return undefined;
			var keys = ['path', 'cwd', 'root', 'directory'];
			for (var kt = 0; kt < keys.length; kt += 1) {
				var value = row[keys[kt]];
				if (typeof value === 'string' && value !== '') return value;
			}
			return undefined;
		}

		/**
		 * 调宿主桥的一条 RPC（令牌只在引擎进程里，浏览器侧不持有）。
		 *
		 * @param method - 方法名。
		 * @param params - 参数。
		 * @returns 结果的 Promise。
		 */
		function bridge(method, params) {
			/*
			 * ## ⚠️⚠️ `fetch` 必须先判存在（1.2.59 修）
			 *
			 * 这是**所有桥调用的唯一入口**（`syncProgress` / `autoSyncDesktop` /
			 * `mergedPresetList` / `modelCatalog` 全走它）。
			 *
			 * `fetch` 不是语言内建，是 WebView 提供的宿主 API。
			 * 而「它存在」这件事我们从来没检查过 —— 直接裸调。
			 *
			 * 一旦它缺失，这里抛的是 **ReferenceError**，不是普通异常：
			 *
			 *   · 它发生在 `fetch(...)` 那一刻，**在 Promise 链建立之前**，
			 *     所以调用方 .catch() 完全接不住；
			 *   · 而所有桥调用都是异步路径，抛在里面的 ReferenceError
			 *     会变成 unhandled rejection 或者直接冒到上层。
			 *
			 * 正确做法：**返回一个 rejected Promise**，让调用方原有的
			 * .catch / then(onErr) 照常工作 —— 语义上和网络失败一致，
			 * 上层不需要任何改动。
			 */
			if (typeof fetch !== 'function') {
				return Promise.reject(new Error('当前内核没有 fetch，无法连接本地桥'));
			}
			return fetch(ROUTE_RPC, {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ method: method, params: params || {} }),
			}).then(
				(res) => res.json(),
				(error) => {
					throw new Error('无法连接本地桥: ' + String((error && error.message) || error));
				},
			).then((payload) => {
				if (!payload || payload.ok !== true) {
					throw new Error((payload && payload.error) || '本地桥调用失败');
				}
				return payload.result;
			});
		}

		/** 从任意形状的目录列表里取条目数组（宿主/官方后端的字段名略有差异）。 */
		function entriesOf(listing) {
			if (!listing || typeof listing !== 'object') return [];
			if (Array.isArray(listing.entries)) return listing.entries;
			if (Array.isArray(listing.items)) return listing.items;
			return [];
		}

		/** 取条目的显示名。 */
		function nameOf(entry) {
			return entry.name || entry.title || entry.displayName || String(entry.path || '');
		}

		/** 取条目的完整路径。 */
		function pathOf(entry) {
			return entry.path || entry.fullPath || entry.name || '';
		}

		/** 是否是可进入的文件夹。 */
		function isDir(entry) {
			if (entry.enterable === false) return false;
			if (entry.isDirectory === false) return false;
			return true;
		}

		/**
		 * 是否是隐藏条目（名字以 `.` 开头）。
		 *
		 * ⚠️ **只按名字判断，不要去看属性位**：电脑端返回的条目只有
		 * `{name, path, isDirectory, enterable}`，没有 Windows 的「隐藏」属性，
		 * 而用户说的「前面带 `.` 的文件夹比如 `.dsh`」正是这一种。
		 *
		 * @param entry - 目录条目。
		 * @returns 是否应归入「默认不显示」。
		 */
		function isHidden(entry) {
			var name = nameOf(entry);
			return name.length > 0 && name.charAt(0) === '.';
		}

		/** 取列表的当前路径与上级路径。 */
		function locationOf(listing) {
			var path = (listing && listing.path) || '';
			var parent = (listing && listing.parent) || '';
			// 后端明确给空 parent = 已经到顶了（Linux 的 `/`、Windows 的 `C:\`），
			// 这时**不能**再自己兜底推算：切 `C:\` 会得到 `C:`，而它在前端眼里
			// 也是绝对路径、按钮会被点亮，点下去后端直接报错。
			var atRoot = path === '' || path === '/' || /^[a-zA-Z]:[\\/]?$/.test(path);
			if (parent === '' && !atRoot) {
				var trimmed = path.replace(/[\\/]+$/, '');
				var slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
				if (slash > 0) parent = trimmed.slice(0, slash);
			}
			return { path: path, parent: parent, home: (listing && listing.home) || '' };
		}

		/**
		 * 「选择工作区目录」对话框：手机 / 电脑 双页签。
		 *
		 * props 是 slot 契约的 `{open, busy, onPicked, onCancel, onError}`，
		 * 外加 `inject` 送进来的浏览能力与宿主桥。
		 */
		function RemoteDirectoryFlow(props) {
			var open = props.open;
			var onPicked = props.onPicked;
			var onCancel = props.onCancel;
			var listDirectory = props.listDirectory;
			var createDirectory = props.createDirectory;
			var openSession = props.openSession;
			/** 把宿主刚建好的会话登记进本地列表（见 `settleLocal`）。 */
			var registerLocal = props.registerLocal;
			/** 兜底：拉一次全量会话列表（登记失败时用）。 */
			var refreshSessions = props.refreshSessions;
			/** 把会话标记成「电脑的」（cwd 还没进列表行时兜底，见 `isRemoteSession`）。 */
			var markRemote = props.markRemote;
			/** 重拉一次镜像工作区 slug 集合（新建镜像工作区之后必须调）。 */
			var refreshMirrorSlugs = props.refreshMirrorSlugs;
			/**
			 * 重拉电脑侧展示元数据（真实活动时间 + 置顶标记）。
			 *
			 * 同步完成后必须调 —— 那时宿主刚记好这批会话的时间和置顶，
			 * 不调的话侧边栏顺序仍是「同步顺序」、置顶也不同步
			 *（用户 2026-10-29 的两个报障）。
			 */
			var refreshDesktopMeta = props.refreshDesktopMeta;
			/** 解绑后把所有会话退回「本地」。 */
			var clearRemote = props.clearRemote;

			var statePair = react.useState({
				tab: 'local',
				path: null,
				listing: null,
				loading: false,
				error: null,
				// 选完目录后「正在建手机本地会话」的防重入标记（见 `openDesktop`）。
				// 这一步现在不联网，所以基本看不到；留着是因为 `sessionController.create`
				// 与 `attachSession` 仍是两次本地 IO，连点两下会重复建会话。
				creating: false,
				// 是否显示 `.` 开头的隐藏文件夹。默认关 —— 电脑的项目根目录里
				// `.dsh`、`.git`、`.vscode` 一堆，全列出来会把真正的项目目录淹掉。
				showHidden: false,
			});
			var state = statePair[0];
			var setState = statePair[1];

			/**
			 * 配对状态（与目录浏览无关，所以单独一个 state）。
			 *
			 * `endpoint === null` 表示还没查过；`paired === false` 表示
			 * 手机还没有任何凭据（令牌为空），需要用户去电脑上取一个配对码。
			 */
			var pairPair = react.useState({
				endpoint: null,
				paired: false,
				code: '',
				busy: false,
				// 「重新配对」的两步确认：`true` 时才显示「确定解绑 / 取消」。
				confirming: false,
				message: null,
				// 一键刷新（模型 / 预设 / 目录树）的状态与回执。
				refreshing: false,
				refreshNote: null,
			});
			var pairState = pairPair[0];

			/*
			 * 把配对状态同步给模块级的 `pairedRef`。
			 *
			 * ⚠️ **必须在这里同步，不能在 `apply()` 里**：`pairState` 是本组件
			 *（`RemoteDirectoryFlow`）作用域的变量，`apply()` 是另一个函数，
			 * 那里根本访问不到它。
			 *
			 * 用途：模块级的在线探测（`probeDesktopOnline`）需要在未配对时
			 * 直接跳过，避免每秒打一次必然 401 的请求。
			 */
			react.useEffect(
				function () {
					pairedRef.current = pairState !== null && pairState.paired === true;
					return function () {
						pairedRef.current = false;
					};
				},
				[pairState === null ? false : pairState.paired],
			);
			var setPairState = pairPair[1];

			var patchPair = react.useCallback(function (next) {
				setPairState(function (prev) {
					return Object.assign({}, prev, next);
				});
			}, []);

			var patch = react.useCallback(function (next) {
				setState(function (prev) {
					return Object.assign({}, prev, next);
				});
			}, []);

			var aliveRef = react.useRef(true);
			react.useEffect(function () {
				aliveRef.current = true;
				return function () {
					aliveRef.current = false;
				};
			}, []);

			/**
			 * ## 请求令牌 —— 专治「切到电脑页签，却先闪一下手机的目录」
			 *
			 * ### 真机实测到的现象
			 *
			 * DockerCDP 直连页面世界实测（点「手机」→ 60ms 后点「电脑」）：
			 * `innerText` 在 16/146/167ms 三次变化，而 1521ms 时对话框里显示的
			 * 竟是 **`/data/user/0/com.dsh.android/files/home` + 应用目录 / 手机存储**
			 * —— 那是**手机页签**的内容，用户点的却是「电脑」。
			 *
			 * ### 根因
			 *
			 * 下面那个 `listItems` effect 的 `.then()` 里只检查了 `aliveRef`
			 * （组件还在不在），**没有检查这次请求发起时的页签是不是还是当前页签**。
			 *
			 * 于是：点「手机」→ 发起 `listDirectory`（本地，几十 ms）→ 用户立刻点
			 * 「电脑」→ effect 重跑，`desktopDirCache['']` 命中，**当场就把电脑列表
			 * patch 上去了** → 但手机那次请求的 promise 随后 resolve，它的
			 * `.then` 又 `patch({listing: 手机内容})`，把电脑列表**覆盖掉**。
			 *
			 * 结果就是用户看到的「点电脑还要转一下」：不是没有缓存，而是缓存刚铺好
			 * 就被上一个页签的迟到响应盖住了。
			 *
			 * ### 修法
			 *
			 * 每次 effect 执行时递增令牌，异步回来时令牌对不上就**直接丢弃**。
			 * 这比在 `.then` 里读 `state.tab` 可靠：闭包里的 `tab` 是发起时的值，
			 * 拿它跟「当前值」比需要额外的 ref，而令牌天然就是发起顺序。
			 */
			var loadTickRef = react.useRef(0);

			/**
			 * 让官方前端的模型目录**失效重载**。
			 *
			 * ## ⚠️ 这一步为什么不能省
			 *
			 * 官方 `ModelCatalogDirectory` 在页面加载时就 `load()` 一次并进入
			 * `status === 'ready'`，此后**点菜单一个请求都不发**
			 * （`dsh-client-ui-model-selection/lib/client.js:100`：ready 时直接
			 * 返回旧值）。所以「配对之后模型列表变成电脑的」这件事，只把宿主那边的
			 * 缓存换掉是**看不见的** —— 前端那一层永远捧着配对前拉到的手机列表。
			 *
			 * 这正是用户报的「预设列表正确地加载了电脑预设，但模型列表还是没同步」：
			 * 预设走 `remoteExportList`，**没有缓存层**、每次重读，所以它是对的。
			 *
			 * `modelDirectories` 是官方那个服务实例（`super(ctx, "modelDirectories")`），
			 * `.catalog.refresh()` 就是官方在 `llm/adapters-updated` 时走的同一条路 ——
			 * 走公开方法，不碰前端 bundle 内部类。
			 *
			 * `ctx.get` 拿不到服务时静默跳过（见 `injected()` 里 `modelDirectories`
			 * 的注释）：最坏是回到「要点一下刷新按钮」的旧行为，不会报错。
			 */
			var reloadModelCatalog = function () {
				/*
				 * ⚠️ 先无条件作废我们自己那层缓存（1.2.82）。
				 *
				 * 官方 `catalog.refresh()` 内部是 `invalidate()` + `load().catch(() => {})`。
				 * RPC 一旦失败，它不会通知任何人，而源快照
				 * 身份不变 ⇒ 我们的 `wrapStore` 会命中缓存 ⇒ 界面永远旧数据。
				 *
				 * 先 bust 再 refresh：下一次读快照时无论源快照有没有换，
				 * 都会重算一次投影 —— 至少能反映出 status/error 的变化。
				 */
				try {
					var bustFn = bustModelCatalogRef.value;
					if (typeof bustFn === 'function') bustFn();
				} catch (bustError) {
					/* 作废失败不影响后面的 refresh */
				}
				try {
					var models = props.modelDirectories;
					if (models && models.catalog && typeof models.catalog.refresh === 'function') {
						models.catalog.refresh();
					}
				} catch (error) {
					/* 服务结构变了就只更新宿主缓存，不打扰用户 */
				}
			};
			/*
			 * 把它承接给模块级，供 `runAutoSync`（模块级函数）在同步完成后调（1.2.69）。
			 *
			 * ⚠️ 不能让 `runAutoSync` 直接调 `reloadModelCatalog()`：
			 * 它是组件内的局部函数，模块级函数看不见 ——
			 * 就是 `invalidateDesktopCache is not defined` / `mergePresets` /
			 * `refreshOfficialModelCatalog` 那个反复踩的闭包坑。必须走 ref。
			 */
			reloadFrontendModelCatalogRef.value = reloadModelCatalog;	
			/*
			 * 同一个理由（模块级 `runAutoSync` 看不见 `apply()` 闭包）：
			 * 把「重拉电脑侧元数据」也经 ref 承接出去。
			 *
			 * 这是本文件第 N 次踩同一个坑（`pendingPlans` / `desktopCache` /
			 * `desktopCatalog` / `invalidateDesktopCache` / `mergePresets`），
			 * 所以直接按既有模式走 ref，不留"也许能访问到"的侥幸。
			 */
			refreshDesktopMetaRef.value = refreshDesktopMeta;

			// 每次打开都回到「手机」页签，避免上次停在电脑目录造成困惑。
			react.useEffect(
				function () {
					if (open) patch({ tab: 'local', path: null, error: null });
				},
				[open, patch],
			);

			// 打开时查一次配对状态（只查一次，失败也不打扰用户 —— 未配对
			// 本来就是这个流程的正常起点）。
			react.useEffect(
				function () {
					if (!open) return undefined;
					bridge('pairStatus', {}).then(
						function (result) {
							if (!aliveRef.current) return;
							patchPair({ endpoint: result.endpoint, paired: result.paired === true });
							/*
							 * 已配对 ⇒ 打开侧边栏时补跑一次自动同步。
							 *
							 * 为什么需要这条：`runAutoSync('pair')` 只在**本次**配对成功时
							 * 触发。老用户升级插件后已经配对过了，不会再走配对回调，
							 * 于是他们永远看不到"打开就有全部工作区"的效果。
							 *
							 * `autoSyncDesktop` 是**幂等**的（已同步过的只补账本、不重建），
							 * 但为了不白跑一趟网络，加一个"本次启动已跑过"的内存标记，
							 * 避免用户反复开合侧边栏时重复请求。
							 */
							if (result.paired === true && !autoSyncTriedRef.current) {
								autoSyncTriedRef.current = true;
								runAutoSync('startup');
							}
						},
						function () {
							/* 查不到就不显示那一条，用户仍然可以输码 */
						},
					);
					return undefined;
				},
				[open, patchPair],
			);

			// 加载当前页签、当前路径的目录列表。
			var tab = state.tab;
			var path = state.path;
			react.useEffect(
				function () {
					// ⚠️ 令牌**必须在最早处**递增：连下面这些 early-return 的路径
					// 也要把在飞的旧请求作废（见 `loadTickRef` 的注释）。
					var tick = ++loadTickRef.current;
					if (!open) return undefined;
					// ⚠️ **没配对就别去问电脑要目录**。未配对时手机没有任何凭据，
					// 打过去必然是 401/502 —— 用户看到的是转圈 10 秒后一句
					// 「远程请求失败」，完全不知道要先去配对。这里直接给出可操作的提示。
					if (tab === 'desktop' && pairState.paired !== true) {
						patch({ loading: false, listing: null, error: null });
						return undefined;
					}
					// ⚠️ **电脑目录命中缓存就直接铺回去，不打桥**（用户报障：来回点文件夹
					// 每次都重新加载）。缓存键用 `''` 表示根，与 `listDesktop` 的入参一致。
					if (tab === 'desktop') {
						var key = path === null ? '' : path;
						var hit = desktopDirCache[key];
						if (hit !== undefined) {
							patch({ loading: false, listing: hit, error: null });
							return undefined;
						}
					}
					patch({ loading: true, error: null });
					/*
					 * ## ⚠️⚠️ 整个加载必须包在 try 里（1.2.80 修）
					 *
					 * 只要下面这一行**同步抛错**，`patch({loading:true})` 已经执行了，
					 * 而 `load.then(…, …)` 连**挂都没挂上** ⇒ `loading` 永远是 true
					 * ⇒ 界面**永远「加载中…」**。用户截图里的手机页签就是这个状态。
					 *
					 * 所以这里不能只改写法，必须**保证任何路径都把 loading 放下来**：
					 * 抛错就当失败处理，把原因显示到界面上 —— 用户说过「把错误原因
					 * 显示在 App 界面里，我看一张截图就知道」。
					 */
					var load;
					try {
						load = tab === 'desktop'
							? bridge('listDesktop', path === null ? {} : { path: path })
							: Promise.resolve().then(function () { return listDirectory(path === null ? undefined : path); });
					} catch (startError) {
						load = Promise.reject(startError);
					}
					load.then(
						function (listing) {
							if (!aliveRef.current) return;
							// ⚠️ 用户已经切了页签 / 换了目录 ⇒ 这次结果作废，
							// 否则会把新页签刚铺好的列表覆盖成上一个页签的内容
							// （这就是「点电脑先闪手机目录」的根因，见 `loadTickRef`）。
							if (loadTickRef.current !== tick) return;
							if (tab === 'desktop') {
								desktopDirCache[path === null ? '' : path] = listing;
							}
							patch({ loading: false, listing: listing });
						},
						function (reason) {
							if (!aliveRef.current) return;
							if (loadTickRef.current !== tick) return;
							// ⚠️ 失败**要清掉这一格缓存**：留着的话用户再点进来会看到
							// 上一次的旧内容，而以为自己看的是电脑现状。
							if (tab === 'desktop') delete desktopDirCache[path === null ? '' : path];
							patch({
								loading: false,
								listing: null,
								error: String((reason && reason.message) || reason),
							});
						},
					);
					return undefined;
				},
				[open, tab, path, listDirectory, patch, pairState.paired],
			);

			if (!open) return null;

			var loc = locationOf(state.listing);
			// `.` 开头的默认不显示（见 `isHidden`）；开关在面包屑那一行。
			var allDirs = entriesOf(state.listing).filter(isDir);
			var hiddenCount = allDirs.filter(isHidden).length;
			var entries = state.showHidden === true ? allDirs : allDirs.filter(function (e) { return !isHidden(e); });

			var switchTab = function (next) {
				if (next === state.tab) return;
				// ⚠️ **同一帧内就把缓存铺上去**。原来是先 `patch({listing: null})`
				// 再等下面那个 effect 去查缓存 —— 中间必然渲染出一帧**空列表**，
				// 用户看到的就是「点电脑闪一下才出来」。缓存命中时这里直接给值，
				// 一帧都不空。
				var cached = next === 'desktop' ? desktopDirCache[''] : undefined;
				patch({
					tab: next,
					path: null,
					listing: cached === undefined ? null : cached,
					error: null,
				});
			};

			/** 「手机」页签：采纳为本地工作区。 */
			var pickLocal = function () {
				if (loc.path === '') return;
				// ⚠️ **必须清掉电脑那条兜底**（见 `pendingWorkspaceCwd`）：用户从
				// 电脑工作区切回手机默认工作区时，若不清，模型/预设会继续按
				// 「电脑」筛 —— 正是用户报的「切回默认工作区，预设和模型都没改回来」。
				setPendingWorkspace(null);
				onPicked(loc.path);
			};

			/**
			 * 提交配对码。
			 *
			 * 成功后**必须重拉一次目录列表**：配对的当下 `listDesktop` 是失败的
			 * （那时还没配对），而上面那个 effect 的依赖里有 `paired`，
			 * 状态一变它自己就会重跑 —— 这里只需要把 `paired` 翻过来。
			 */
			var submitPair = function () {
				var code = pairState.code.trim();
				if (code === '' || pairState.busy) return;
				patchPair({ busy: true, message: null });
				bridge('pair', { code: code }).then(
					function (result) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							paired: true,
							endpoint: result.endpoint,
							code: '',
							message: { kind: 'ok', text: '配对成功，正在连接电脑…' },
						});
						// ⚠️ 换了一台电脑，旧缓存里的路径完全无效（甚至同名不同机）——
						// 必须作废，否则用户会看到上一台电脑的目录。
						clearDesktopDirCache();
						patch({ path: null, listing: null });
						// ⚠️ 前端那份模型目录是**永久缓存**的（见 `reloadModelCatalog`），
						// 不作废的话用户接下来看到的仍然是配对前那份手机模型列表 ——
						// 就是用户报的「模型列表还是没同步」。
						reloadModelCatalog();
						// ⚠️ **镜像工作区 slug 集合也要重拉**：换一台电脑后 `workspaceMap`
						// 是另一份（slug 由电脑路径哈希得来，同名不同机 slug 不同）。
						// 不重拉的话，新电脑的镜像工作区会被判成「本地」—— 又是那个 bug。
						if (refreshMirrorSlugs !== undefined) refreshMirrorSlugs();
						/*
						 * ⚠️ **配对成功 ⇒ 立刻自动同步全部工作区与对话名**。
						 *
						 * 这是本轮改造的核心（用户诉求 2026-10-28）：
						 * 「用户配对成功之后……打开侧边栏就可以直接看到电脑的所有工作区
						 *   以及对话历史了」，不再需要手动点「⋯ → 同步对话」。
						 *
						 * 放在 `refreshMirrorSlugs()` **之后**：自动同步过程中会
						 * 新建镜像工作区，而那些工作区的 slug 必须已经在
						 * `refreshMirrorSlugs` 的集合里，否则侧边栏会把它们当成
						 * 本地工作区。虽然 `runAutoSync` 完成后自己也会再刷一次，
						 * 但先刷一次能让建工作区那一刻就判对。
						 *
						 * 不 await：配对 UI 该立刻变成"已配对"，同步在后台跑，
						 * 进度由 `autoSyncStateRef` 驱动动画（见 `mountSyncProgress`）。
						 */
						runAutoSync('pair');
					},
					function (reason) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							message: { kind: 'error', text: String((reason && reason.message) || reason) },
						});
					},
				);
			};

			/**
			 * 解除配对：清掉本地凭据，退回未配对状态。
			 *
			 * 成功后 `paired` 翻回 `false`，配对条自己就会换成输入框（同一个
			 * `pairState`），所以这里不用手工重挂组件。
			 */
			var submitUnpair = function () {
				if (pairState.busy) return;
				patchPair({ busy: true, message: null });
				bridge('unpair', {}).then(
					function (result) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							confirming: false,
							paired: false,
							endpoint: result.endpoint,
							code: '',
							message: { kind: 'ok', text: '已解绑，请到电脑上生成新的配对码' },
						});
						// 解绑后旧凭据作废，缓存里的目录不该再露出来。
						clearDesktopDirCache();
						// ⚠️ **同时清掉「电脑会话」标记**：解绑后电脑已经不在了，
						// 那些会话退回普通本地会话（宿主 `unpair` 也清了
						// `remoteSessions`）。留着标记的话，模型菜单会一直显示
						// 电脑的模型，而消息其实发给了手机本地。
						if (clearRemote !== undefined) clearRemote();
						patch({ path: null, listing: null });
					},
					function (reason) {
						if (!aliveRef.current) return;
						patchPair({
							busy: false,
							message: { kind: 'error', text: String((reason && reason.message) || reason) },
						});
					},
				);
			};

			/**
			 * 已配对时的「重新配对」入口。
			 *
			 * ⚠️ **这是必需的回退路径，不是便利功能。** 电脑端一旦「解绑」，它会换掉
			 * 令牌并停掉隧道；而手机这边 `mobile.json` 里还留着旧令牌，`pairStatus`
			 * 会一直报「已配对」，于是输入框永远不渲染 —— 用户卡在 502，界面上无路
			 * 可走，只能手工删 `mobile.json` 或清 App 数据。补上这个入口，回退才闭环。
			 *
			 * 两步确认：解绑会停隧道、清远程会话清单，误触代价太大。
			 */
			var unpairControls = function () {
				if (pairState.confirming !== true) {
					return h(
						Button,
						{
							// ⚠️ 用 `outline` 而不是 `ghost`：ghost 在浅色面板上没有边框，
							// 真机截图里「重新配对」看起来就是一行灰字，用户认不出可点 ——
							// 而这是电脑解绑后唯一的回退入口，必须一眼看出来。
							variant: 'outline',
							size: 'sm',
							onClick: function () {
								patchPair({ confirming: true, message: null });
							},
							disabled: pairState.busy,
						},
						'重新配对',
					);
				}
				return h(
					'span',
					{ style: CSS.pairConfirm },
					h('span', { style: CSS.pairConfirmText }, '解绑后这台手机要重新输入配对码。'),
					h(
						Button,
						{
							variant: 'outline',
							size: 'sm',
							onClick: submitUnpair,
							disabled: pairState.busy,
						},
						pairState.busy ? '解绑中…' : '确定解绑',
					),
					h(
						Button,
						{
							variant: 'ghost',
							size: 'sm',
							onClick: function () {
								patchPair({ confirming: false });
							},
							disabled: pairState.busy,
						},
						'取消',
					),
				);
			};

			/**
			 * 一键刷新：把「模型 / 预设 / 目录树」三样一起重新拉一遍。
			 *
			 * 用户需求原文：「你只需要添加一个手动刷新…这三个地方的数据都可以在
			 * 选择工作区的时候，那个刷新按钮直接一键刷新这三个位置。」
			 *
			 * ⚠️ 光调桥拉数据**不够**：官方前端的模型目录是**带缓存**的
			 * （`ModelCatalogDirectory.load()` 在 `status === 'ready'` 时直接返回旧值，
			 * 见 `dsh-client-ui-model-selection/lib/client.js:100`）。所以拉完还要
			 * 让它失效重载，否则用户看到的是「点了刷新，列表没变」。
			 *
			 * `modelDirectories` 是官方那个服务的名字（`super(ctx, "modelDirectories")`），
			 * 它的 `.catalog.refresh()` 正是官方在 `llm/adapters-updated` 时走的那条路 ——
			 * 走服务实例的公开方法，不碰前端 bundle 内部类。
			 *
			 * 预设名单没有等价的服务实例（官方预设前端不是 Service），它每次
			 * 打开会话/设置面板都会重读，所以这里只更新宿主缓存即可。
			 */
			var refreshAll = function () {
				if (pairState.refreshing) return;
				patchPair({ refreshing: true, refreshNote: null });
				var target = state.tab === 'desktop' ? loc.path : '';
				// ⚠️ **只作废「当前这一层」，不再整棵清空**。
				//
				// 原来这里是 `clearDesktopDirCache()`，代价是刷新一下就把用户攒下的
				// 全部子目录缓存清零 —— 之后**每一次点文件夹、每一次返回上一级**
				// 都要重新过隧道（实测 700～1200ms）。用户实测原话：
				// 「我先是点了一下刷新，刷新成功之后，我再点击某个文件夹，
				// 它里面的内容还是要加载」「返回上一级也要加载」。
				// 删掉当前这一格既保证这次一定拿到新列表，也不牵连其它层。
				if (target === '') delete desktopDirCache[''];
				else delete desktopDirCache[target];
				bridge('refreshAll', target === '' ? {} : { path: target }).then(
					function (result) {
						if (!aliveRef.current) return;
						// 目录树：直接把新列表铺回去（正在看的这一层），并写回缓存。
						// ⚠️ **必须同时把 `loading` 置回 false**：原来只 patch 了
						// `listing`，于是刷新成功后界面一直挂着「加载中…」——
						// 用户看到的就是「刷新完了还在加载」。
						if (result && result.directory && result.directory.ok) {
							desktopDirCache[target === '' ? '' : target] = result.directory.value;
							patch({ loading: false, listing: result.directory.value, error: null });
						} else {
							patch({ loading: false });
						}
						// 模型：让官方目录失效重载，下一次打开菜单就是电脑的。
						if (result && result.models && result.models.ok) {
							try {
								var models = props.modelDirectories;
								if (models && models.catalog && typeof models.catalog.refresh === 'function') {
									models.catalog.refresh();
								}
							} catch (error) {
								/* 服务结构变了就只更新宿主缓存，不打扰用户 */
							}
						}
						var failed = [];
						if (result && result.models && !result.models.ok) failed.push('模型');
						if (result && result.presets && !result.presets.ok) failed.push('预设');
						if (result && result.directory && !result.directory.ok) failed.push('目录');
						patchPair({
							refreshing: false,
							refreshNote: failed.length > 0
								? { kind: 'error', text: failed.join('、') + '刷新失败，其余已更新' }
								: { kind: 'ok', text: '模型、预设、目录已刷新' },
						});
					},
					function (reason) {
						if (!aliveRef.current) return;
						patchPair({
							refreshing: false,
							refreshNote: { kind: 'error', text: String((reason && reason.message) || reason) },
						});
					},
				);
			};

			/**
			 * 「电脑」页签顶部的配对条。
			 *
			 * 已配对时只显示「连的是哪台电脑」，不显示令牌（令牌只在引擎进程里）。
			 * 未配对时给一个输入框 —— **手输是主路径**：手机 App 的
			 * `onPermissionRequest` 只放行录音权限，摄像头一律拒绝，
			 * 页面里拿不到 `getUserMedia`，扫二维码这条路走不通。
			 */
			var pairBar = h(
				'div',
				{ style: CSS.pairBar },
				pairState.paired === true
					? h(
							'span',
							{ style: CSS.pairWho, title: pairState.endpoint || '' },
							'已配对：' + (pairState.endpoint || ''),
						)
					: h(
							'span',
							{ style: CSS.pairWho },
							'还没配对。请在电脑的「设置 → 手机远程」里生成 6 位配对码，填在这里。',
						),
				/*
				 * 版本号（1.2.72 新增）。
				 *
				 * ## 为什么要显示在界面上
				 *
				 * 这个插件一轮一轮修了很多次，每次都要问「你装的是哪个版本」。
				 * 靠猜只会浪费一次调试 —— 用户明确说过调试成本很大。
				 * 写在配对条上，一张截图就能对上版本。
				 *
				 * 取不到就不显示 —— 不能因为展示版本号影响主流程。
				 */
				versionTag(),
				sidebarDiagTag(),
				pairState.paired === true
					? unpairControls()
					: h(Input, {
							value: pairState.code,
							onChange: function (event) {
								patchPair({ code: event.target.value.toUpperCase() });
							},
							onKeyDown: function (event) {
								if (event.key === 'Enter') submitPair();
							},
							placeholder: '6 位配对码',
							'aria-label': '配对码',
							maxLength: 8,
							// ⚠️ 直接给对象：`CSS` 在模块加载时已就地转成对象了
							//（见上面那个 `for (var styleKey in CSS)`），
							// 再套一层 `toStyle()` 会得到 `[object Object]`。
							style: CSS.pairInput,
						}),
				pairState.paired === true
					? null
					: h(
							Button,
							{
								variant: 'outline',
								size: 'sm',
								onClick: submitPair,
								disabled: pairState.busy || pairState.code.trim() === '',
							},
							pairState.busy ? '配对中…' : '配对',
						),
			);

			var pairMsg = pairState.message === null
				? null
				: h(
						'div',
						{
							// 同上：`CSS` 已是对象，直接拼对象即可。
							style: pairState.message.kind === 'ok'
								? Object.assign({}, CSS.pairMsg, CSS.pairOk)
								: CSS.pairMsg,
							role: pairState.message.kind === 'ok' ? undefined : 'alert',
						},
						pairState.message.text,
					);

			/**
			 * 「电脑」页签：把这个电脑目录选为本次新对话的工作区。
			 *
			 * ## 为什么和「手机」页签用同一个出口（用户原话）
			 *
			 * 「我觉得像手机那边的直接先选择文件夹，这个设计是非常好的，因为你单纯的
			 *  选文件夹的话，你就不用考虑跟电脑那边同步的问题了，完全可以等用户他
			 *  真正的点击发送信息按钮之后，才把选择的模型以及选择的预设，以及选择的
			 *  工作区同步到电脑上面，这样全程就仅需要从电脑那边沟通一次」
			 *
			 * ## 关键：**仍然要经宿主建一条手机本地会话**
			 *
			 * 上面那些话的意思是「别在电脑上建会话」，**不是**「手机这边什么都不做」。
			 * 宿主 `createSession` 桥现在做的全是**本地**动作：
			 *
			 *   1. `ensureDesktopWorkspace()` 建/复用一个**手机本地的镜像目录**当 cwd，
			 *      并把 `slug → 电脑路径` 登记进 `workspaceMap`（这一步是整套远程
			 *      逻辑的锚点，少了它 `desktopPathOfMirror` 就反查不出电脑路径）；
			 *   2. `sessionController.create()` 建一条**手机本地会话**（前端要能打开它、
			 *      侧边栏要能看到它、`agentPreset` 投影要有个宿主）。
			 *
			 * 这两步都是纯本地 IO，**不联网**。真正被去掉的是原来那趟
			 * `callRemote('createSession')`（1～3 秒隧道往返）。
			 *
			 * 所以点下去**立刻**收起、立刻跳到会话，网络延迟完全不参与；电脑那边
			 * 真正建会话推迟到第一条消息，且与消息**打包在同一个请求**里
			 *（见宿主 `prompt` 拦截器与 `pendingPlans`）。
			 *
			 * ## 为什么不能改成 `onPicked(loc.path)` 直接把电脑路径交给官方
			 *
			 * 官方的 `onPicked(path)` 落地成
			 * `WorkspacePickFlow.adoptDirectory(path)` → `createWorkspace({ path })`
			 *（`dsh-client-ui-workspace/lib/client.js:1154`），而
			 * `WorkspaceRegistry.create()` 开头就是
			 * `realpathNormalize(path)` + `stat(canonical)`，**路径必须已经存在**
			 * （`dsh-workspace/lib/index.js:365`）。电脑路径 `C:\…` 在 Android 上
			 * 既不是绝对路径也不存在 ⇒ 抛错 ⇒ 选择器保持不变、也没有任何提示。
			 *
			 * 这正是 1.0.62 真机上「选完还是『选择工作区』」的成因，别改回去。
			 *
			 * ⚠️ 收起之后本组件就卸载了，所以下面**不能再依赖 `aliveRef` / `patch`**
			 * （原来每一层都在判 `aliveRef.current`，一收起就全部短路，会话也就
			 * 永远打不开）。`settleLocal` / `setPendingWorkspace` / `openSession`
			 * 都是外部函数，与组件生命周期无关，卸载后照常可调。
			 *
			 * ⚠️ 失败时界面上已经没有地方提示了。但这条路径现在**几乎不会失败**：
			 * 去掉那趟联网之后，剩下的全是本地建目录 + 本地建会话。真正的失败点
			 *（电脑离线）被推迟到了发消息那一刻，那时用户正看着输入框，能正常报错。
			 */
			var openDesktop = function () {
				if (loc.path === '' || state.creating) return;
				var dirPath = loc.path;
				patch({ creating: true, error: null });
				// ⚠️ **不要在这里 `onCancel()`**：下面要拿 `onPicked(镜像目录)` 让官方
				// 流程把「工作区选择器」改写成镜像目录的标题，并**停在新建对话主界面**。
				// 先收起的话组件就卸载了，`onPicked` 也就没得调。
				bridge('createSession', { path: dirPath }).then(
					function (result) {
						patch({ creating: false });
						// ⚠️ **把镜像目录交给官方 `onPicked`**，而不是 `openSession`。
						//
						// 用户对界面的要求原话：「选完文件夹之后，它界面其实还是在我们
						// 新建对话的主界面的，只是工作区那个选择器的名字改了而已」。
						// `openSession(id)` 会**跳到会话页**，那就不对了 —— 而且电脑上的
						// 会话此刻还没建出来，跳过去也只是一条空会话。
						//
						// `onPicked(镜像目录)` 走的是官方「手机页签选目录」同一条路：
						// `adoptDirectory(path)` → `createWorkspace({ path })`，它要求
						// `path` **是手机本地已存在的目录**，所以这里必须传宿主回执里的
						// `cwd`（镜像目录），**绝不能传电脑路径**（见上面那段注释）。
						//
						// 成功后官方把工作区选择器改成这个工作区、留在当前首页，
						// 与「手机页签选目录」的观感完全一致 —— 这就是用户要的「无感」。
						if (onPicked !== undefined && result && typeof result.cwd === 'string' && result.cwd !== '') {
							// ⚠️ **`pendingWorkspaceCwd` 必须存镜像目录**，不能存电脑路径：
							// 它下面要和 `workspace.path`（真实工作区路径）比对来判定
							// 「这是不是电脑工作区」（见 `pendingWorkspaceCwd` 的注释）。
							// 存电脑路径的话永远比不中，模型/预设菜单就会退回手机那一套。
							setPendingWorkspace(result.cwd);
							// ⚠️ **必须重拉镜像 slug 集合**：宿主刚为这个电脑目录建好（或复用）
							// 镜像工作区，`workspaceMap` 里**刚多出一条**。前端那份集合是快照，
							// 不重拉的话这个工作区会被判成「本地」—— 模型菜单显示手机模型，
							// 而消息其实要发到电脑上，用户看到的是「选的模型没生效」。
							// （`settleLocal` 里也有同一步，这条路径跳过了它，所以补在这里。）
							if (refreshMirrorSlugs !== undefined) refreshMirrorSlugs();
							/*
							 * ## ⚠️⚠️ 模型目录也必须重拉（1.2.84 修）
							 *
							 * 用户反馈：「选择电脑的某个已存在工作区时，
							 * 预设列表刷新过来了，**模型列表没刷新**」。
							 *
							 * 预设为什么没事：它走的是本地缓存 + 官方 roster 推送，
							 * 不经 RPC。而模型目录是「上一次查询结果」的**永久缓存**，
							 * 只有 `reloadModelCatalog()` 会把它作废。
							 *
							 * 配对成功那条路径已经调了（见上方那段注释），
							 * 但「选一个**已存在**的电脑工作区」走的是这一支，
							 * 而这一支**漏了** ⇒ 模型菜单显示的还是手机那套模型。
							 */
							try { reloadModelCatalog(); } catch (reloadCatalogError) { /* 不阻断选目录 */ }
							onPicked(result.cwd);
							return;
						}
						// 兜底（宿主没给 cwd 时）：退回「收起 + 打开会话」的老路。
						onCancel();
						return settleLocal(result).then(function () {
							if (openSession !== undefined && result && result.sessionId) {
								try {
									openSession(result.sessionId);
								} catch (openError) {
									// 已经收起，没地方提示；宿主日志里有更详细的原因。
								}
							}
						});
					},
				).then(null, function (reason) {
					// ⚠️ **失败必须把 `creating` 复位**，否则按钮永久禁用 —— 1.0.64
					// 真机上就是这个症状：桥里抛了 `pendingPlans is not defined`，
					// 前端看到的是「点了没反应，再点也点不动、选择器还开着」。
					patch({ creating: false, error: String((reason && reason.message) || reason) });
					// 留一份现场给排查用（与 1.0.62 的 `window.__dshCreate` 同一个位）。
					window.__dshCreate = {
						at: Date.now(),
						path: dirPath,
						failed: String((reason && reason.message) || reason),
					};
				});
			};

			/**
			 * 把宿主刚建好的会话**登记进本地会话列表**。
			 *
			 * ⚠️ **这一步不能省，而且必须在 `openSession` 之前完成。**
			 *
			 * `uiWorkspace.openSession(id)` 最终走
			 * `SessionManager.resolveTarget(id)`，它有**三道**身份检查，全不过就抛
			 * `sessions.retain: unknown session <id>`：
			 *
			 * 1. `sessions.has(id)` —— 已物化的客户端实例；
			 * 2. `summaries.some(...)` —— 客户端列表快照；
			 * 3. `addresses` —— 子 agent 地址。
			 *
			 * 而会话是**宿主**建的，客户端只能靠 `api-session/added` 这条**异步推送**
			 * 知道它 —— RPC 的响应会先到。所以直接在 RPC 回执里 `openSession()`
			 * 是**必然的竞态**：抛出的异常还被 `openSession()` 同步吞掉，界面
			 * 只表现为「点完没反应、又回到默认工作区」，控制台一句错都没有。
			 *
			 * 正解是走官方 client 自己的 `sessions.create({sessionId, cwd})`：
			 * `SessionManager.create()` 在 RPC 返回后立刻
			 * `recordMutation({kind:'placeholder'})` 把这一行写进列表快照，
			 * 于是 `openSession()` 当场就能解析到它。
			 *
			 * 幂等性是安全的：宿主侧已经建过这个 id，`createOrAdopt` 看到
			 * `agents.get(sessionId)` 活着就直接返回它，不会重复建、也不会撞 cwd。
			 *
			 * `cwd` 由宿主回执带回（手机本地的工作区路径，不是电脑路径）——
			 * 两端各自的 cwd 互不相通，只在 `sessionId` 上对齐。
			 *
			 * @param result - 宿主 `createSession` 的回执 `{sessionId, cwd}`。
			 * @returns 登记完成的 Promise（失败也 resolve，交由调用方决定下一步）。
			 */
			var settleLocal = function (result) {
				if (!result || typeof result.sessionId !== 'string') return Promise.resolve();
				// ⚠️ **先标记「这是电脑的」再登记**：列表行的 `cwd` 要等宿主那一行
				// 回来才带上，之前那几帧判据读不到 cwd，靠这个标记兜住。
				if (markRemote !== undefined) markRemote(result.sessionId);
				// ⚠️ **重拉镜像工作区 slug 集合**：宿主刚为这个电脑目录建好（或复用）
				// 镜像工作区，`workspaceMap` 可能**刚多出一条**。前端那份集合是快照，
				// 不重拉的话这个工作区在侧边栏里点进去仍被判成「本地」。
				if (refreshMirrorSlugs !== undefined) refreshMirrorSlugs();
				/* 同 `openDesktop`：换了工作区就要重拉模型目录 */
				try { reloadModelCatalog(); } catch (reloadCatalogError2) { /* 不阻断登记 */ }
				var local = registerLocal === undefined
					? Promise.resolve()
					: Promise.resolve(registerLocal(result.sessionId, result.cwd)).then(null, function () {
							// 登记失败（例如 `session/writer-held`）：退一步拉全量列表，
							// 宿主那一行仍然会进来，`openSession` 同样能解析。
							return refreshSessions === undefined ? null : Promise.resolve(refreshSessions()).then(null, function () { return null; });
						});
				return local;
			};

			var makeFolder = function () {
				if (loc.path === '' || createDirectory === undefined) return;
				var name = window.prompt('文件夹名称', '新建文件夹');
				if (name === null || name === '') return;
				Promise.resolve(createDirectory(loc.path, name)).then(
					function () {
						if (aliveRef.current) patch({ path: loc.path });
					},
					function (reason) {
						if (aliveRef.current) {
							patch({ error: String((reason && reason.message) || reason) });
						}
					},
				);
			};

			var rows = entries.map(function (entry) {
				var full = pathOf(entry);
				return h(
					'button',
					{
						key: full,
						type: 'button',
						style: CSS.row,
						onClick: function () {
							patch({ path: full });
						},
					},
					// ⚠️ 图标组件只解构 `size/className/strokeWidth`，**不透传 `style`**
					// —— 上色要靠外层 span（官方图标本来就是 currentColor 描边）。
					h('span', { style: CSS.rowIcon }, h(IconFolderCloseRegular, { size: 16 })),
					h('span', { style: CSS.rowName }, nameOf(entry)),
					h('span', { style: CSS.rowChevron }, h(IconChevronRightOutlineRegular, { size: 12 })),
				);
			});

			var body = h(
				'div',
				{ style: CSS.editorScope },
				// —— 头部：标题 + 页签 + 路径行 ——
				h(
					'div',
					{ style: CSS.header },
					h('h2', { style: CSS.title }, '选择工作区目录'),
					h(SegmentedTabs, {
						label: '目录来源',
						value: state.tab,
						onChange: switchTab,
						items: [
							{ value: 'local', label: '手机' },
							{ value: 'desktop', label: '电脑' },
						],
					}),
					h(
						'div',
						{ style: CSS.crumbBar },
						h(
							Button,
							{
								variant: 'outline',
								size: 'sm',
								disabled: loc.parent === '',
								onClick: function () {
									patch({ path: loc.parent === '' ? null : loc.parent });
								},
							},
							'上一级',
						),
						h('span', { style: CSS.pathText, title: loc.path }, loc.path || '（根）'),
						// 有隐藏文件夹时才显示开关 —— 一个目录里没有 `.` 开头的东西
						// 却摆个开关，只会让人以为漏了什么。
						hiddenCount > 0 || state.showHidden === true
							? h(
									'button',
									{
										type: 'button',
										style: state.showHidden === true
											? Object.assign({}, CSS.crumbToggle, CSS.crumbToggleOn)
											: CSS.crumbToggle,
										title: state.showHidden === true
											? '隐藏以 . 开头的文件夹'
											: '显示 ' + hiddenCount + ' 个以 . 开头的隐藏文件夹',
										onClick: function () {
											patch({ showHidden: state.showHidden !== true });
										},
									},
									state.showHidden === true ? '隐藏 . 文件夹' : '显示 . 文件夹 (' + hiddenCount + ')',
								)
							: null,
						// 一键刷新：模型 / 预设 / 目录树。只在「电脑」页签下有意义
						//（手机页签的数据本来就来自本机，没什么可刷的）。
						state.tab === 'desktop' && pairState.paired === true
							? h(
									'button',
									{
										type: 'button',
										style: CSS.crumbToggle,
										title: '重新拉取电脑的模型列表、预设列表与当前目录',
										disabled: pairState.refreshing === true,
										onClick: refreshAll,
									},
									pairState.refreshing === true ? '刷新中…' : '刷新',
								)
							: null,
					),
				),
				// —— 主体：目录列表 ——
				h(
					'div',
					{ style: CSS.content },
					// 配对条只在「电脑」页签下出现 —— 「手机」页签跟配对无关。
					state.tab === 'desktop' ? pairBar : null,
					state.tab === 'desktop' ? pairMsg : null,
					state.tab === 'desktop' && pairState.refreshNote !== null
						? h(
								'div',
								{
									style: pairState.refreshNote.kind === 'ok'
										? Object.assign({}, CSS.refreshNote, CSS.refreshOk)
										: Object.assign({}, CSS.refreshNote, CSS.error),
									role: pairState.refreshNote.kind === 'ok' ? undefined : 'alert',
								},
								pairState.refreshNote.text,
							)
						: null,
					state.loading
						? h('div', { style: CSS.status, role: 'status' }, '加载中…')
						: h(
								'div',
								{ style: CSS.columnList },
								rows.length > 0
									? rows
									: h(
											'div',
											{ style: CSS.status },
											/*
											 * ⚠️ **加载失败时不能说"这里没有子文件夹"**（2026-10-29 修）
											 *
											 * 用户截图里同时出现了：
											 *   · 「这里没有子文件夹」
											 *   · 「电脑没有响应（15 秒超时）…」
											 *
											 * 前者是一句**断言**，会让用户以为"电脑上那个目录真的是空的"，
											 * 于是去电脑上翻半天，而真相只是请求超时了。
											 * 这是"文案与事实不符"造成的额外排查成本。
											 *
											 * 现在把 `state.error` 也算进来：有错就明说"没能读到"，
											 * 不再假装目录是空的。
											 */
											state.tab === 'desktop' && pairState.paired !== true
												? '配对后这里会显示电脑上的文件夹'
												: state.error !== null && state.error !== undefined
													? '没能读出这个目录的内容（见上方提示）'
													: hiddenCount > 0
														? '这里只有 ' + hiddenCount + ' 个以 . 开头的隐藏文件夹'
														: '这里没有子文件夹',
										),
							),
					state.error !== null ? h('div', { style: CSS.error, role: 'alert' }, state.error) : null,
					state.tab === 'desktop'
						? h(
								'div',
								{ style: CSS.note },
								'在电脑的这个目录上开一个新对话，手机上会同步显示它的全部过程。',
							)
						: null,
				),
				// —— 底部动作栏 ——
				h(
					'div',
					{ style: CSS.footerBar },
					state.tab === 'local' && createDirectory !== undefined
						? h(
								Button,
								{
									variant: 'outline',
									icon: h(IconPlusOutlineRegular, { size: 14 }),
									onClick: makeFolder,
								},
								'新建文件夹',
							)
						: null,
					h('span', { style: CSS.footerGap }),
					h(Button, { variant: 'outline', style: CSS.footerAction, onClick: onCancel }, '取消'),
					state.tab === 'local'
						? h(
								Button,
								{
									variant: 'primary',
									style: CSS.footerAction,
									disabled: loc.path === '' || props.busy,
									onClick: pickLocal,
								},
								props.busy ? '处理中…' : '选择此文件夹',
							)
						: h(
								Button,
								{
									variant: 'primary',
									style: CSS.footerAction,
									// ⚠️ **只要选中了目录就能点**，不再要求已配对。
									//
									// 这一步现在全是本地操作（建镜像目录 + 建手机本地会话），
									// 不联网、不建电脑会话（见 `openDesktop` 的注释）。
									// 原来挂 `pairState.paired !== true` 会让没配对的人连目录
									// 都选不了；而发送时宿主本来就会因未配对退回本地并记 warn，
									// 那才是该拦住的地方。
									disabled: loc.path === '' || state.creating,
									onClick: openDesktop,
								},
								'选择此文件夹',
							),
				),
			);

			// 卡片本身（背景/圆角/阴影/遮罩/关闭按钮）全部交给官方 Modal；
			// 我们只覆盖尺寸与留白（见 DIALOG_CLASS）。`headless` 才不会
			// 再套一层官方自带的标题栏 —— 标题由我们放进 header 里。
			return h(
				Modal,
				{
					open: open,
					onClose: onCancel,
					title: '选择工作区目录',
					closeLabel: '关闭',
					className: DIALOG_CLASS,
					headless: true,
				},
				body,
			);
		}

		//#region 「同步对话」对话框

		/**
		 * 镜像工作区清单的缓存：`[{ slug, path, desktopPath, title }]`。
		 *
		 * 「同步对话」菜单要按工作区行反查电脑路径，而工作区行上只有标题，
		 * 所以拿宿主的清单来比对。刷新时机与 `refreshMirrorSlugs` 同源
		 * （选/加工作区后、以及每次 App 启动后一次）。
		 */
		var mirrorWorkspacesRef = { value: [] };
		var refreshMirrorWorkspaces = function () {
			bridge('mirrorWorkspaces', {}).then(
				function (result) {
					mirrorWorkspacesRef.value = (result && result.workspaces) || [];
				},
				function () {
					/* 拿不到就留空：菜单项会静默不出现，不影响其它功能 */
				},
			);
		};

		//#region 配对后自动同步（含真实进度）

		/**
		 * 首次配对后自动把电脑上的全部工作区 + 对话名同步过来。
		 *
		 * ## 用户需求（2026-10-28）
		 *
		 * 「用户配对成功之后，电脑就主动把它 GUI 里面当前已存在的所有工作区的
		 *   工作区名称以及所有的对话的对话名称一起发送过来……用户配对成功了之后
		 *   打开侧边栏就可以直接看到电脑的所有工作区以及对话历史了。」
		 *
		 * ## 为什么以前没生效
		 *
		 * 后端 `autoSyncDesktop` 早就写好了（幂等、只传元数据、历史按需拉），
		 * 但**前端从来没有调用过它** —— 用户只能在工作区行上手动点
		 * 「⋯ → 同步对话」逐条勾选。这里补上「配对成功 ⇒ 自动跑一次」。
		 *
		 * ## 进度怎么来（用户明确要求真实进度）
		 *
		 * 「一定要有真实的进度提示，而不是那种假的循环动画，那样用户就会以为
		 *   是卡死了。」
		 *
		 * 所以同步期间高频轮询 `syncProgress` 桥（纯内存读，零网络）拿
		 * `done/total`，画真实进度条。分母来自电脑返回的对话条数。
		 */
		var autoSyncStateRef = { value: null };

		/**
		 * 「当前页面还活着吗」的标记（模块级）。
		 *
		 * ⚠️ **不能用组件里的 `aliveRef`**：`runAutoSync` / `mountSyncProgress`
		 * 都是模块级函数，作用域里看不到 `apply()` → 组件内部的 `aliveRef`
		 * （它在第 2834 行、组件作用域里）。引用它就是 `ReferenceError`，
		 * 而且是在回调里抛，表现为"同步永远停在统计中"。
		 *
		 * 这个模块级标记由 `apply()` 在挂载/卸载时切换。
		 */
		var autoSyncAlive = { current: true };

		/**
		 * 本次 App 启动是否已经尝试过自动同步（避免反复开合侧边栏重复请求）。
		 *
		 * 只在内存里，不持久化：每次冷启动重新判断一次是合理的 ——
		 * 用户可能在上次之后又在电脑上新建了对话，值得再问一次。
		 */
		var autoSyncTriedRef = { current: false };

		/**
		 * 跑一次自动同步，并把进度写进 `autoSyncStateRef`。
		 *
		 * @param reason 触发原因，仅用于日志。
		 * @returns Promise，永远 resolve（失败已内部记进 state）。
		 */
		var runAutoSync = function (reason) {
			// 防重入：正在跑就不再起一轮（配对回调 + 启动钩子可能同时触发）。
			var current = autoSyncStateRef.value;
			if (current && (current.phase === 'running' || current.phase === 'estimating')) {
				return Promise.resolve();
			}

			/*
			 * ## ⚠️⚠️ 预设侧的诊断也要清（1.2.64 修）
			 *
			 * `syncDiagRef` 自 1.2.4x 引入以来 **从来没被重置过** ——
			 * 修之前全文件**只有写入、没有清零**。
			 *
			 * 后果和 1.2.54 修的 modelRefresh 残留完全同类：
			 *
			 *   第一轮：pullRosterIntoCache 失败，写入
			 *          { presets:-1, reason:'连不上电脑' }
			 *   第二轮：开始时不清 ⇒ 界面仍显示上一轮的错误
			 *          直到新一轮的 bridge 回来才被覆盖。
			 *          而 bridge 是异步的，中间那段时间全是假的。
			 *
			 * 更糟的情形：神机反复进入后台 / 恢复，漏了一次
			 * pullRoster 调用 ⇒ 错误信息无限期挂在那里，永远不消。
			 *
			 * 所以在轮次起点就清成 null —— 「本轮还没取到」是诚实的状态，
			 * 渲染处对 null 不显示任何预设提示（见下面的 diag 分支）。
			 */
			try {
				syncDiagRef.value = null;
			} catch (diagResetError) {
				/* 清不了也不能影响同步启动 */
			}

			autoSyncStateRef.value = {
				phase: 'estimating',
				done: 0,
				total: 0,
				workspaces: 0,
				bytes: 0,
				message: '',
				/*
				 * ⚠️ **必须重置成 null**（1.2.54）。
				 *
				 * 这是新一轮同步的**起点**。不重置的话，上一轮留下的
				 * modelRefresh=false 会一直带到这里 ⇒ 本轮才刚开始
				 * （phase 还是 estimating）就显示「模型列表未刷新」，
				 * 纯属误报。
				 *
				 * 这与 1.2.47 修的 message 残留、1.2.53 宿主侧的重置是
				 * 同一类错误：状态对象换轮不清理，旧值冒充新结果。
				 */
				modelRefresh: null,
				/* 与 modelRefresh 成对：失败时的具体原因（1.2.63） */
				modelRefreshReason: '',
			};
			
			

			/*
			 * 轮询进度。250ms 一次：
			 *   · 太快 → 大量无意义的桥往返（虽然零网络，但也是 fetch 开销）；
			 *   · 太慢 → 进度条一跳一跳，观感差。
			 * 250ms 在"顺滑"和"省开销"之间。
			 */
			var pollTimer = null;
			var stopPoll = function () {
				if (pollTimer !== null) {
					clearInterval(pollTimer);
					pollTimer = null;
				}
			};
			/**
			 * `tick` 的正体（由外层统一兜底，见上）。
			 *
			 * @param p - 宿主 `syncProgress` 的返回。
			 */
			var tickOnce = function (p) {
				var prev = autoSyncStateRef.value || {};
				// 只往前推进：done 不回退，否则进度条会来回跳。
				/*
				 * ⚠️ `message` **不能**沿用 `prev`（1.2.47 修）
				 *
				 * 原来是 `p.message || prev.message || ''`。
				 * `||` 会让**旧的错误消息一直粘着**：
				 *
				 *   1. 中途失败一次（宿主持有 `phase='failed'` +
				 *      message='连不上电脑'）；
				 *   2. 用户重试 / 宿主自己恢复，这次成功
				 *      （`phase='done'`，宿主把 message 清成 `''`）；
				 *   3. `'' || '连不上电脑'` ⇒ 取到**旧错误**
				 *      ⇒ 状态里挂着一条早已不成立的错误。
				 *
				 * 虽然标题只看 `phase`（所以还会显示「同步完成」），
				 * 但这个残留 message 会在别处被当作失败原因展示，
				 * 用户就会看到「说完成了，又写着连不上电脑」这种自相矛盾。
				 *
				 * 正确语义：**message 属于当前 phase**。
				 *   · phase 变了 ⇒ 取宿主这一次的 message（哪怕为空）；
				 *   · phase 没变 ⇒ 才沿用上次（宿主可能只更新了进度没重写 message）。
				 */
				var nextPhase = p.phase || prev.phase || 'running';
				var nextMessage;
				if (nextPhase !== prev.phase) {
					// 阶段切换：以宿主这次的为准，空就是空。
					nextMessage = typeof p.message === 'string' ? p.message : '';
				} else {
					nextMessage = (typeof p.message === 'string' && p.message !== '')
						? p.message
						: (typeof prev.message === 'string' ? prev.message : '');
				}
					/*
					 * 先算出「最终生效的成功标志」，两个字段共用（1.2.65）。
					 *
					 * 它们必须同命：一个说成功、另一个挂着失败原因，
					 * 界面就自相矛盾。所以在对象外算一次，对象里只用结果。
					 *
					 * ⚠️ 这里的 nextModelRefresh 不同于 p.modelRefresh：
					 *     p.modelRefresh = null（宿主本次没说）
					 *     prev.modelRefresh = true（上次已成功）
					 *     ⇒ nextModelRefresh = true
					 * 下面若去判 p.modelRefresh 就会错过 —— 最终状态是「成功」，
					 * 却因宿主顺带了一句原因而把原因保留下来。
					 * 这正是穷举 256 种组合时扫出的 12 例违规。
					 */
					var nextModelRefresh = (typeof p.modelRefresh === 'boolean')
						? p.modelRefresh
						: (typeof prev.modelRefresh === 'boolean' ? prev.modelRefresh : null);
					
				var next = {
					phase: nextPhase,
					done: Math.max(prev.done || 0, p.done || 0),
					total: p.total || prev.total || 0,
					workspaces: p.workspaces || prev.workspaces || 0,
					bytes: p.bytes || prev.bytes || 0,
					message: nextMessage,
				/*
				 * ⚠️ **必须搬运**（1.2.53）。
				 *
				 * 这个对象是新建的、只挑字段复制。少写一个字段，宿主那边
				 * 报上来的值就在这一层被静默丢掉 —— 和 1.2.52 修的
				 * `message` 被 then 回调覆盖是同一类错误：
				 * 中间层不搬运 = 后端说了前端永远收不到。
				 */
					modelRefresh: nextModelRefresh,
					/*
					 * 三步归一，保证两个字段**永远同命**：
					 *
					 *   ① 宿主说已经刷新成功了 ⇒ 原因归零
					 *      （成功时原因无意义，而且可能是上一轮残留）
					 *   ② 宿主本次发了原因（哪怕空串）⇒ 用宿主的
					 *   ③ 其余（宿主没发字段，也没说成功）⇒ 沿用上次
					 *
					 * ⚠️ **① 必须排在②前面**（1.2.65 穷举测出的边界）。
					 *
					 * 若先看宿主发了什么、再看成功标志，就会漏掉一种自相矛盾的输入：
					 *
					 *     { modelRefresh: true, modelRefreshReason: '无 refresh 方法' }
					 *
					 * 即「说成功、又带着失败原因」。这种输入宿主不应该发，
					 * 但**它可能发**（并发写入、版本不匹配、字段残留……）。
					 *
					 * 上层一旦把它原样存下来，界面就会出现
					 * 「同步完成 · 模型未刷新：无 refresh 方法」这种自相矛盾的句子。
					 *
					 * 正确做法：**让布尔字段做唯一事实源**。它说成功，
					 * 原因就归零；不管宿主顺带了什么。宁可丢掉一条
					 * 矛盾的原因，也不能把矛盾显示给用户。
					 */
					modelRefreshReason: (nextModelRefresh === true)
						? ''
						: (typeof p.modelRefreshReason === 'string')
							? p.modelRefreshReason
							: (typeof prev.modelRefreshReason === 'string' ? prev.modelRefreshReason : ''),
				};
				autoSyncStateRef.value = next;
				if (next.phase === 'done' || next.phase === 'failed') stopPoll();
			};

			var tick = function () {
				/*
				 * ## ⚠️⚠️ then 的成功回调也要兜底（1.2.56 修）
				 *
				 * `.then(onOk, onErr)` 里的 `onErr` **只接上游 Promise 的拒绝**，
				 * 接不住 `onOk` 自己抛出的异常 —— 那会变成 unhandled rejection。
				 *
				 * 这个 tick 每 250ms 一次，即一秒 4 次。onOk 里要读
				 * `autoSyncStateRef.value`、逐字段比对、写回状态，任何一处意外
				 * 都会在 250ms 后**重演**。
				 *
				 * 后果：unhandled rejection 以每秒 4 条的速度堆积，部分内核
				 * 会记进 error 事件并拖慢页面；而进度浮层看起来「就是不更新」，
				 * 没有任何线索。
				 *
				 * 所以把正体抽成 tickOnce，外面包 try。
				 */
				bridge('syncProgress', {}).then(
					function (p) {
						if (!autoSyncAlive.current || !p) return;
						try {
							tickOnce(p);
						} catch (tickError) {
							/* 吞掉：单次进度更新失败不应变成 unhandled rejection */
						}
					},
					function () {
						/* 轮询失败静默：不影响真正的同步（它走另一条 fetch） */
					},
				);
			};
			/*
			 * ## ⚠️⚠️ 启动前先把旧定时器停掉（1.2.78 修）
			 *
			 * `pollTimer` 是**单个变量**。而 `runAutoSync` 有三个入口：
			 *
			 *     'startup'  打开侧边栏时（受 `autoSyncTriedRef` 防重）
			 *     'pair'     配对成功时（**完全无守卫**）
			 *     'dirty:..' 定时 ping 发现变更（受 `syncBusyRef` 防重）
			 *
			 * `autoSyncTriedRef` 只管「本次启动已跑过」，`syncBusyRef` 只管 dirty 那条。
			 * 于是这个组合是真实可达的：
			 *
			 *     用户打开侧边栏 ⇒ startup 开跑（pollTimer = A）
			 *     此时配对成功   ⇒ pair 又进来（pollTimer = B，**A 被覆盖**）
			 *
			 * A 的句柄丢了 ⇒ `stopPoll()` 只能停掉 B ⇒
			 * **A 每 250ms 打一次 bridge 调用，直到页面重载**。
			 * 用户感受到的就是「同步完了还是卡」、手机发热。
			 *
			 * 先停再启：保证任何时候都只有**一个** 进度轮询在跑。
			 */
			stopPoll();
			pollTimer = setInterval(tick, 250);
			tick();

			return bridge('autoSyncDesktop', { limit: 50 }).then(
				function (result) {
					stopPoll();
					if (!autoSyncAlive.current) return;
					var created = (result && result.created) || [];
					var wsCount = (result && result.workspaces) || 0;
					logAutoSync(reason, created.length, wsCount);
					var prev = autoSyncStateRef.value || {};
					// 同步跑完，放开占用（见 `syncBusyRef` 的注释）。
					syncBusyRef.current = false;
					/*
					 * ## 同步完成 ⇒ 让预设 roster 缓存失效（2026-10-29 修 bug）
					 *
					 * 用户报障：「我手动点击添加工作区来选择一个文件夹的话，模型列表可以
					 * 刷新过来显示电脑上面的列表了，但是预设列表的话还是没有同步过来。
					 * 然后我退出 APP 重启之后，预设列表也同步过来了。」
					 *
					 * 根因：`rosterCache` 只在**第一次**拿到 `agentPresets/list` 响应时写入，
					 * 之后同样的请求直接本地合成、一个字节都不上网（那是为了切工作区
					 * 不闪烁而做的优化）。而它原本只在 `settings/document-updated`
					 * 时才失效 —— 那个事件只有用户**改设置**才触发，
					 * **同步完成不会触发它**。
					 *
					 * 于是：刚同步完的那批电脑预设进不了缓存 ⇒ 界面还是旧的那份
					 * ⇒ 只能重启（重启后缓存为空，重新拉一次就对了）。
					 * 这与用户描述的现象完全吻合。
					 *
					 * 置空之后，下一次官方请求 `agentPresets/list` 会走真实网络，
					 * 拿到宿主刚合并好的新 roster（含电脑预设）。
					 */
					rosterCache = null;
					/*
					 * ## ⚠️⚠️ 仅仅置空缓存是**不够**的（2026-10-29 修）
					 *
					 * 上一轮我只写了 `rosterCache = null`，以为这就能让菜单刷新。
					 * 但那只能让「**下一次**请求」走网络 —— 而同步完成后
					 * **根本不会发生下一次请求**：
					 *
					 *   · 官方 `seat.load()` 只在 `settings/document-updated` 与
					 *     `connection/reset` 重跑，同步完成不属于两者；
					 *   · `flushRemoteChange` 又有 `if (now === lastRemote) return`。
					 *
					 * 同步前后都是「电脑工作区」⇒ 远程性没变 ⇒ 不刷。
					 * 结果就是用户看到的「模型与预设还是没热更新」。
					 *
					 * ## 两条腿走路（1.2.37 补第二条）
					 *
					 * 第一条：叫官方 `seat.load()` 重跑（`forceRefreshPresets`）。
					 *   它能让**界面**重新渲染，但依赖两个前提：chip 已注册、
					 *   且官方那次 `load()` 真的拿到新数据。任一不成立就没效果。
					 *
					 * 第二条（这条是**兜底**）：我们自己经 `bridge('mergedPresetList')`
					 *   把宿主刚合并好的 roster 拉回来，**直接写进 `rosterCache`**。
					 *
					 *   这样做的好处是它与官方 `load()` 完全解耦：
					 *     · 不需要 chip 注册；
					 *     · 不需要等任何事件；
					 *     · 下一次任何界面读缓存时，拿到的就已经是新数据。
					 *
					 *   即使第一条彻底失效，第二条也能保证「缓存里是新数据」——
					 *   用户下次打开预设菜单（哪怕只是切一下工作区）就能看到。
					 *   这是"无论如何都不会停留在旧数据"的保证。
					 */
					try {
						var forceRefresh = forceRefreshPresetsRef.value;
						if (typeof forceRefresh === 'function') forceRefresh();
					} catch (refreshError) {
						/* 刷新失败不影响同步结果 */
					}
					try {
						var pullRoster = pullRosterIntoCacheRef.value;
						if (typeof pullRoster === 'function') pullRoster();
					} catch (pullError) {
						/* 兜底拉取失败也不能影响同步结果 */
					}
					/*
					 * ## ⚠️⚠️ 模型目录还要刷**前端**那一份（1.2.69）
					 *
					 * 上面两步只管预设（`forceRefreshPresets` 走官方 `seat.load()`，
					 * `pullRoster` 直接写 `rosterCache`）。而模型目录有**两份缓存**：
					 *
					 *   宿主侧（Node）  —— 同步末尾已经调了
					 *                       `refreshOfficialModelCatalog`（在 index.js）
					 *   前端（WebView） —— **之前没人叫**，这就是漏洞
					 *
					 * 两段代码字面上一模一样（都是 `catalog.refresh()`），但操作的是
					 * 两个进程里的两个不同对象。宿主侧刷了，WebView 侧**不会自动跟**。
					 *
					 * 而前端那一次（`reloadModelCatalog`）**只在配对成功时调过**。
					 * 所以配对那一次能刷到，而后续的同步都刷不到 ——
					 * 用户看到的就是「同步完成了，模型菜单还是旧的」。
					 *
					 * 放在预设后面、不影响预设那两步的结果。
					 */
					try {
						var reloadFrontendModel = reloadFrontendModelCatalogRef.value;
						if (typeof reloadFrontendModel === 'function') reloadFrontendModel();
					} catch (modelReloadError) {
						/* 刷不了只影响这一次显示，绝不能影响同步结果 */
					}
					/*
					 * ## ⚠️⚠️ 电脑侧展示元数据也要重拉（2026-10-29）
					 *
					 * 同步刚把电脑上的对话全建出来，同时宿主也把每条对话的
					 * **真实活动时间**和**置顶标记**记进了 `desktop-meta.json`。
					 *
					 * 现在必须让前端拿到这份新数据，否则：
					 *
					 *   · 侧边栏顺序仍是「同步顺序」（用户报障②「没有按时间排序」）；
					 *   · 电脑上置顶的对话在手机上不置顶（用户报障③）。
					 *
					 * 刷新本身会递增 `metaVersion` ⇒ `wrapSessionListClock` 的缓存
					 * 立刻失效 ⇒ 下一次渲染就是新顺序。置顶那边由
					 * `sessions.list` 的订阅自动重试（见 `applyPinFromDesktop`）。
					 *
					 * 不 await：这是展示层增强，绝不能挡住同步完成的状态更新。
					 */
					try {
						var refreshMeta = refreshDesktopMetaRef.value;
						if (typeof refreshMeta === 'function') refreshMeta();
					} catch (metaRefreshError) {
						/* 拉不到就保持旧顺序，不影响同步结果 */
					}
					/*
					 * ## ⚠️⚠️ 这里**不能**硬编码 message:'' 和 done:total（1.2.52 修）
					 *
					 * 原来这一块无条件写
					 *
					 *     done: prev.total || created.length,
					 *     total: prev.total || created.length,
					 *     message: '',
					 *
					 * 于是宿主那边刚刚如实报上来的「2 条会话同步失败」**被这里
					 * 覆盖成空串**，进度也强行对齐成 100%。宿主改了等于白改 ——
					 * 前端这一层才是最后说话的人。
					 *
					 * 现在改成读宿主返回值：`result.failed` 是失败条数。
					 * 有失败就保留真实 done 并带上 message；全绿才写空串。
					 */
					var failedCount = (result && typeof result.failed === 'number') ? result.failed : 0;
					var realTotal = prev.total || created.length || 0;
					autoSyncStateRef.value = {
						phase: 'done',
						done: failedCount > 0
							? Math.min(prev.done || 0, realTotal)
							: realTotal,
						total: realTotal,
						workspaces: wsCount,
						bytes: prev.bytes || 0,
						message: failedCount > 0
							? (failedCount + ' 条会话同步失败（其余已就绪）')
							: '',
						/*
						 * 同上：不搬运就会被这一层静默丢掉（1.2.53）。
						 * 轮询可能还没收到宿主的最终值（stopPoll 恰好在它之前），
						 * 所以退回 prev 里最后一次拿到的。
						 */
						/*
						 * 两个字段**同命**（1.2.66 修）。
						 *
						 * 这里也要遵守 1.2.65 在 tickOnce 里建立的不变量：
						 * **说成功，原因就必须为空**。
						 *
						 * 原来两个字段各自从 prev 搬，互不干涉。于是：
						 *
						 *     prev = { modelRefresh: true, modelRefreshReason: '无 refresh 方法' }
						 *     ⇒ 这里也是 { true, '无 refresh 方法' }
						 *
						 * 这种矛盾组合一旦存下来，渲染层若以后改成
						 * 「成功也显示原因」就会出现自相矛盾的句子。
						 * 即便现在渲染层不显示，数据层也不该存一个说不通的状态。
						 *
						 * tickOnce 里已经这么做了，这里没同步就是漏洞 ——
						 * 同一个不变量在两个写入点都必须成立。
						 */
						modelRefresh: (typeof prev.modelRefresh === 'boolean') ? prev.modelRefresh : null,
						modelRefreshReason: ((typeof prev.modelRefresh === 'boolean') && prev.modelRefresh === true)
							? ''
							: ((typeof prev.modelRefreshReason === 'string') ? prev.modelRefreshReason : ''),
					};
					/*
					 * ⚠️ 同步完必须刷新这两份缓存，否则侧边栏里刚同步过来的工作区
					 * 仍被当成「本地工作区」（判据是镜像 slug 集合）—— 那就是老 bug
					 * 「同步完工作区显示成手机的」。
					 */
					/*
					 * ⚠️ **不能用组件里的 `refreshMirrorSlugs`**（它在 `apply()` 组件
					 * 作用域里，来自 `props.refreshMirrorSlugs`）。`runAutoSync` 是
					 * 模块级函数，直接引用就是 `ReferenceError` —— 而且是在
					 * Promise 回调里抛，用户看到的现象是「同步完成了但侧边栏没变」。
					 *
					 * 正确做法：走模块级的 `refreshMirrorSlugsByBridge()`
					 *（直接调桥，不依赖组件作用域）。
					 */
					refreshMirrorSlugsByBridge();
					refreshMirrorWorkspaces();
				},
				function (error) {
					stopPoll();
					if (!autoSyncAlive.current) return;
					var text = String((error && error.message) || error);
					// 失败也要放开占用，否则 dirty 同步永远进不去。
					syncBusyRef.current = false;
					logAutoSync(reason, -1, 0, text);
					/*
					 * ⚠️ **保留已完成的数量，不要清零**（2026-10-29 修）
					 *
					 * 原来这里写死 `done: 0, total: 0`。于是同步跑到一半失败时
					 * （比如 15 个里做完了 12 个，第 13 个网络断了）：
					 *
					 *   · 界面上那 12 个已经建好的会话**真实存在**；
					 *   · 进度条却从「12/15」一下跳回「0/0」全红。
					 *
					 * 用户的感受是「白同步了」—— 但实际上绝大部分已经成功。
					 * 这会造成不必要的挫败感和重复操作（他会再点一次同步）。
					 *
					 * 现在保留 `prev` 里的进度，只把 phase 改成 failed，
					 * 让用户看到「做到 12 个，第 13 个失败了」这个**真实情况**。
					 */
					var prevState = autoSyncStateRef.value || {};
					autoSyncStateRef.value = {
						phase: 'failed',
						done: typeof prevState.done === 'number' ? prevState.done : 0,
						total: typeof prevState.total === 'number' ? prevState.total : 0,
						workspaces: typeof prevState.workspaces === 'number' ? prevState.workspaces : 0,
						bytes: typeof prevState.bytes === 'number' ? prevState.bytes : 0,
						message: text,
						/*
						 * 失败态也带上这个字段，保持**形状一致**（1.2.54）。
						 *
						 * 这里不显示模型诊断（failed 分支优先），但如果状态对象
						 * 的字段集合时有时无，后面任何一处读它都要先判 undefined ——
						 * 那正是 1.2.52/1.2.53 两轮踩过的坑。统一成同一套字段最省心。
						 */
						modelRefresh: null,
						/* 失败态不带原因：本轮根本没走到刷新那一步 */
						modelRefreshReason: '',
					};
				},
			).catch(function () {
				// 兜底：整体抛错也不能让界面卡在 estimating。
				stopPoll();
				if (autoSyncAlive.current) {
					var prev = autoSyncStateRef.value || {};
					if (prev.phase !== 'done' && prev.phase !== 'failed') {
						prev.phase = 'failed';
						prev.message = '同步过程出现异常';
						autoSyncStateRef.value = prev;
					}
				}
			});
		};

		/**
		 * 通过桥重新拉「镜像工作区 slug → 电脑路径」清单。
		 *
		 * 这是模块级的，替代组件里的 `refreshMirrorSlugs`（见 `runAutoSync` 里
		 * 的注释：模块级函数看不见组件作用域变量）。
		 *
		 * 同步完成后必须调用：刚建出来的镜像工作区 slug 得让宿主知道，
		 * 否则侧边栏会把它们当成本地工作区。
		 */
		function refreshMirrorSlugsByBridge() {
			bridge('mirrorWorkspaces', {}).then(
				function (result) {
					mirrorWorkspacesRef.value = (result && result.workspaces) || [];
				},
				function () {
					/* 拿不到就保持原样：不影响已同步会话，只是分类可能暂时不准 */
				},
			);
		}

		//#region 电脑在线状态探测

		/**
		 * 电脑在线状态（侧边栏「电脑工作区」标题旁的小圆点）。
		 *
		 * ## 用户需求（2026-10-28）
		 *
		 * 「可以在我们电脑工作区的这个大分类标题旁边做一个状态，比如每多少秒就
		 *   自动 ping 一下这个电脑，看一下隧道连不连通，连通的话就有对应的在线
		 *   标识，不连通的话就给出对应的不连通标识，这样子体验就会好很多。」
		 *
		 * ## 探测节奏
		 *
		 * · 在线时 **20 秒**一次：够及时，又不至于给隧道堆连接；
		 * · 离线时 **8 秒**一次：用户可能刚把电脑开机，早点发现早点变绿；
		 * · 页面不可见（`document.hidden`）时**完全不探**：后台没必要烧电，
		 *   回到前台立刻补一次。
		 *
		 * 单次探测超时 4 秒（见宿主 `pingStatus`），所以最坏情况是
		 * 「4 秒探测 + 8 秒间隔」= 12 秒发现电脑上线。
		 */
		/**
		 * 模块级的「是否已配对」快照。
		 *
		 * ⚠️ **不能直接用组件里的 `pairState`**：它定义在 `apply()` 的组件
		 * 作用域里（`var pairState = pairPair[0]`），而 `probeDesktopOnline`
		 * 是模块级函数 —— 直接引用就是 `ReferenceError`，表现为状态灯永远不亮。
		 *
		 * 由组件在 `pairState.paired` 变化时同步进来（见 `apply()` 里的 effect）。
		 */
		var pairedRef = { current: false };

		/**
		 * 「同步正在进行中」的标记（模块级）。
		 *
		 * 用途：在线探测发现电脑侧数据变了时会顺手触发同步，而探测每 8–20 秒
		 * 就跑一次 —— 没有这个标记的话，一次慢同步（100 个对话要建 100 次壳）
		 * 期间会被后续探测反复触发，虽然 `runAutoSync` 自己有防重入，
		 * 但这里的 dirty 标记会被"消费掉却没处理"，导致那批变化永久丢失。
		 */
		var syncBusyRef = { current: false };

		var desktopOnlineRef = {
			value: { ok: false, known: false, latencyMs: null, reason: '' },
		};

		/**
		 * 跑一次在线探测，写进 `desktopOnlineRef`。
		 *
		 * ⚠️ **永不 reject**：`pingStatus` 桥保证 resolve，「电脑离线」是正常
		 * 状态而不是错误。这里再兜一层，防止桥调用本身出错（比如插件正在卸载）。
		 */
		var probeDesktopOnline = function () {
			// 未配对就别探：没有令牌，探了必然是 401，会一直显示"配对已失效"。
			if (pairedRef.current !== true) {
				desktopOnlineRef.value = { ok: false, known: true, latencyMs: null, reason: '未配对' };
				return Promise.resolve();
			}
			return bridge('pingStatus', {}).then(
				function (result) {
					if (!autoSyncAlive.current) return;
					var r = result || {};
					desktopOnlineRef.value = {
						ok: r.ok === true,
						known: true,
						latencyMs: typeof r.latencyMs === 'number' ? r.latencyMs : null,
						reason: typeof r.reason === 'string' ? r.reason : '',
					};
					/*
					 * **电脑侧数据变了 ⇒ 顺手再同步一次**（需求 5 的最后一环）。
					 *
					 * 用户要求：「有变化的话，那就下次手机发起连通性测试的时候告诉手机
					 * 我这边有数据变化了你同步一下，然后再进行一个同步。」
					 *
					 * 时机正好：我们本来就在做"连通性测试"（ping），响应里带回了
					 * `dirty` 数组。有标记就说明电脑上工作区/模型/预设变了，
					 * 直接跑一次 `runAutoSync`（幂等，不会重复建会话）。
					 *
					 * ⚠️ 只在**在线且不在同步中**时才触发：
					 *    · 离线时同步必然失败，白跑一趟；
					 *    · 正在同步时触发，dirty 会被"消费掉却没处理"，那批变化
					 *      就永久丢了（见 `syncBusyRef` 的注释）。
					 */
					var dirty = Array.isArray(r.dirty) ? r.dirty : [];
					if (r.ok === true && dirty.length > 0 && !syncBusyRef.current) {
						syncBusyRef.current = true;
						var needSync = dirty.indexOf('workspace') >= 0;
						runAutoSync('dirty:' + dirty.join(',')).then(function () {
							syncBusyRef.current = false;
							/*
							 * 拉完再 ack —— 顺序不能反（见电脑端 `ackDirty` 注释）。
							 * 即使 sync 失败也 ack：一直亮着会让每次 ping 都触发一次
							 * 失败的重试，反而更糟。
							 */
							bridge('ackDirty', { domains: dirty }).then(
								function () { /* 清成功与否都不影响界面 */ },
								function () { /* 静默 */ },
							);
							if (needSync) refreshMirrorSlugsByBridge();
						});
					}
				},
				function () {
					if (!autoSyncAlive.current) return;
					desktopOnlineRef.value = {
						ok: false, known: true, latencyMs: null, reason: '连不上电脑',
					};
				},
			);
		};

		/**
		 * 启动在线探测循环。
		 *
		 * @returns 停止函数（供 `ctx.effect` 的清理调用）。
		 */
		function startDesktopOnlineWatch() {
			var stopped = false;
			var timer = null;

			var schedule = function (delayMs) {
				if (stopped) return;
				if (timer !== null) clearTimeout(timer);
				timer = setTimeout(function () {
					timer = null;
					run();
				}, delayMs);
			};

			var run = function () {
				if (stopped) return;
				/*
				 * 页面在后台就跳过这一轮，但要继续排下一轮 ——
				 * 否则用户切回前台时没有任何定时器在跑，状态灯永远停在旧值。
				 */
				if (typeof document !== 'undefined' && document.hidden === true) {
					schedule(8000);
					return;
				}
				probeDesktopOnline().then(function () {
					if (stopped) return;
					// 在线 20 秒一次，离线 8 秒一次（见上面的节奏说明）。
					schedule(desktopOnlineRef.value.ok ? 20000 : 8000);
				});
			};

			// 立刻探一次：打开侧边栏就能看到状态，不用等 8 秒。
			run();

			/*
			 * 切回前台立刻补探一次（上面的循环里后台是跳过的，
			 * 用户回来看第一眼就必须是新鲜状态）。
			 */
			var onVisibility = function () {
				if (stopped) return;
				if (typeof document === 'undefined' || document.hidden !== true) {
					schedule(0);
				}
			};
			if (typeof document !== 'undefined' && document.addEventListener) {
				document.addEventListener('visibilitychange', onVisibility);
			}

			return function stop() {
				stopped = true;
				if (timer !== null) {
					clearTimeout(timer);
					timer = null;
				}
				if (typeof document !== 'undefined' && document.removeEventListener) {
					document.removeEventListener('visibilitychange', onVisibility);
				}
			};
		}

		//#endregion

		//#region 在「电脑工作区」标题旁渲染在线状态

		/**
		 * 把在线状态小圆点挂到侧边栏的「电脑工作区」标题旁。
		 *
		 * ## 怎么找到「那个标题」
		 *
		 * 本插件没有往侧边栏插节点的 slot，所以只能**按文字找 DOM**。
		 * 判据是「工作区行的标题以零宽前缀 `\u200B` 开头」（见宿主 `desktopCategoryTitle`）——
		 * 这是本插件自己定的前缀，不会和用户自己建的工作区撞。
		 *
		 * 找到第一个这样的行之后，把圆点插到它的**前面**（视觉上像分组标题前的
		 * 状态灯），而不是每行都插一遍 —— 用户要的是「电脑这一大类的状态」。
		 *
		 * ## ⚠️ 必须幂等且能自愈
		 *
		 * 侧边栏是 React 渲染的，会整块重挂。所以：
		 *   · 每轮都先找有没有已经插好的圆点（按 `data-dsh-online-dot`），
		 *     有就只更新颜色，不重复插入；
		 *   · 节点被 React 换掉（`!isConnected`）就重新插；
		 *   · 找不到电脑工作区行（还没同步过）就什么都不做，不报错。
		 */
		function startOnlineDotWatch() {
			var ATTR = 'data-dsh-online-dot';
			var stopped = false;

			var findDesktopRow = function () {
				/*
				 * 兜底多档：不同版本的侧边栏结构不同。
				 * 用 `[role="treeitem"]` 作为主判据（官方工作区行用的就是它），
				 * 拿不到再退回全文档扫描带零宽前缀的短文本节点。
				 */
				/*
				 * 判据：标题以**零宽前缀**开头，**不再用 emoji**。
				 *
				 * 标题前缀在 2026-10-29 已从 emoji 改成 `\u200B`（见宿主
				 * `DESKTOP_TITLE_PREFIX`）。这里若不同步改，在线状态圆点会永远
				 * 找不到目标行 —— 功能静默失效，而且**看不出任何报错**。
				 *
				 * ⚠️ 这里**不使用** `decorateDesktopRows` 那边的 `isDesktopTitle`：
				 * 它定义在另一个闭包里（后文 ~4577 行），在这里引用会取到
				 * `undefined` 而抛 TypeError。就地内联最稳。
				 */
				var hasMark = function (value) {
					var s = String(value === undefined || value === null ? '' : value);
					s = s.replace(/^[ \t\r\n\u00a0\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u202f\u205f\u3000]+/, '');
					return s.indexOf('\u200B') === 0;
				};
				var rows = document.querySelectorAll('[role="treeitem"]');
				for (var i = 0; i < rows.length; i += 1) {
					if (hasMark(rows[i].textContent)) return rows[i];
				}
				// 退路：直接找带该前缀的最内层节点。
				var all = document.querySelectorAll('div,span,li');
				for (var j = 0; j < all.length; j += 1) {
					var el = all[j];
					if (!hasMark(el.textContent)) continue;
					var txt = String(el.textContent || '').trim();
					// 要求它足够短（是标题而不是整个侧边栏）。
					if (txt.length > 60) continue;
					if (el.children.length > 3) continue;
					return el;
				}
				return null;
			};

			var GROUP_ATTR = 'data-dsh-group-head';

			/**
			 * 在「第一个电脑工作区行」**前面**插入一条分组标题。
			 *
			 * ## 用户需求（2026-10-28）
			 *
			 * 「侧边栏目前是有工作区的一个大标签，我们可以给它分类成一个手机工作区
			 *   和电脑工作区的两个大分类。」
			 *
			 * ## 为什么只能用「插入一条标题」而不是真的分组
			 *
			 * 官方侧边栏的工作区列表由 `dsh-client-ui-workspace` 渲染，它对外
			 * **只暴露 `sidebar.workspaces.directoryFlow` 一个 slot**，而且是
			 * `kind: 'single'`（同时只能有一个 occupant，见本插件文件头的说明）。
			 * 没有任何「在工作区之间插入分组头」的扩展点。
			 *
			 * 所以退一步：把电脑侧的工作区**视觉上归到一起** ——
			 *   · 在第一段电脑行前插一条标题「电脑工作区」（带在线状态圆点）；
			 *   · 标题下面就是所有 带零宽前缀的镜像工作区（它们本来就排在一起，
			 *     因为名字有相同前缀、官方按创建/使用顺序排列）。
			 *
			 * 这样用户扫一眼就能分清"上面是手机本地的、下面是电脑的"，
			 * 达到他要的"比较容易找到"的效果。
			 *
			 * ⚠️ 插的是**兄弟节点**，不能塞进行内部 —— 行是 flex 布局、
			 * 高度固定 34px，塞进去会把那行撑变形。所以要 `insertBefore`。
			 */
			/**
			 * 生成「电脑工作区」角标的**真实 DOM 节点** —— 用官方 SVG 图标。
			 *
			 * ## ⚠️ 为什么不用 `react-dom/server` 的 `renderToStaticMarkup`
			 *
			 * 1.2.30 第一版就是这么写的，结果用户在真机上看到的是一个**小蓝点**
			 * —— 因为 `require('react-dom/server')` 在这个前端模块加载器里
			 * **根本解析不到**，`require` 直接抛错，代码静默退化成圆点兜底。
			 *
			 * 证据：官方所有客户端包里 `require` 过的 react-dom 入口只有两个
			 * （全量搜索各 `dsh-client-ui-xxx` 包的 client.js 确认）：
			 *
			 *     require("react-dom")          // legacy render
			 *     require("react-dom/client")   // React 18 createRoot
			 *
			 * `react-dom/server` **一次都没出现过** ⇒ 加载器不保证提供它。
			 *
			 * ## 现在这条路
			 *
			 * 用官方确实提供的 `react-dom` 把图标渲染进一个**游离的 div**，
			 * 再把里面的 `<svg>` 抠出来克隆一份。这不是黑科技 ——
			 * 官方自己的 `IconXxx` 组件就是返回 `<svg>` 的纯函数组件。
			 *
			 * 渲染一次就缓存（图标与主题无关，描边用 `currentColor`），
			 * 之后每个工作区行克隆一份，100 行也不会重复渲染。
			 */
			var badgeNodeCache = null;

			/**
			 * 把一个 React 元素（`jsx(...)` 的产物）**递归转成真实 DOM 节点**。
			 *
			 * ## 为什么需要这个函数（2026-10-29 真机空白行）
			 *
			 * 用户截图里，电脑工作区那几行**行首一片空白**：文件夹图标被我们藏了，
			 * 而电脑图标没画出来。
			 *
			 * 根因是我上一版只建了一个 `<svg viewBox>` 空壳，**一条 `path` 都没有**。
			 * 官方图标的真实结构是：
			 *
			 *     <svg width=16 height=16 viewBox="0 0 16 16" fill="none" strokeWidth=1>
			 *       <path d="..." stroke="currentColor"/>    ← 3 条
			 *     </svg>
			 *
			 * 没有 path 的 svg 渲染出来是**完全透明**的，占着 16px 位置却什么都看不到。
			 *
			 * ## 为什么不硬编码 path 字符串
			 *
			 * 我一度打算把 3 条 path 的 `d` 抄进代码（共约 2.3 KB）。但那是脆的：
			 * 官方换图标、改路径、改 `strokeWidth`，我们都得跟着改，而且不会有人提醒。
			 *
			 * 图标组件本身是**纯函数**（`(props) => jsxs('svg', {children:[jsx('path',...)]})`），
			 * 直接调它就能拿到完整元素树 —— 里面就有全部真实 path。
			 * 把它转成 DOM，得到的图标与官方渲染的**完全一致**，且自动跟随官方变化。
			 *
			 * ## 参数
			 *
			 * @param element - React 元素（含 `type` 与 `props`）。
			 * @param depth - 递归深度，防止异常数据导致无限递归。
			 * @returns DOM 节点；无法转换时返回 `null`。
			 */
			var elementToDom = function (element, depth) {
				try {
					if (depth > 12) return null;
					if (element === null || element === undefined) return null;
					// 纯文本 / 数字：直接变文本节点
					if (typeof element === 'string' || typeof element === 'number') {
						return document.createTextNode(String(element));
					}
					if (typeof element !== 'object') return null;
					var type = element.type;
					/*
					 * React Fragment（`type` 是 Symbol）或函数组件：
					 * 直接调用它，把返回的元素再转一次。
					 */
					if (typeof type === 'function') {
						try {
							return elementToDom(type(element.props || {}), depth + 1);
						} catch (componentError) {
							return null;
						}
					}
					if (typeof type !== 'string') return null;
					var props = element.props || {};
					var node;
					if (type === 'svg' || type === 'path' || type === 'g' || type === 'circle'
						|| type === 'rect' || type === 'line' || type === 'polyline'
						|| type === 'polygon' || type === 'ellipse' || type === 'defs'
						|| type === 'clipPath' || type === 'mask' || type === 'use') {
						node = document.createElementNS('http://www.w3.org/2000/svg', type);
					} else {
						node = document.createElement(type);
					}
					/*
					 * 把 props 写进 DOM 属性。
					 *
					 * 跳过：`children`（单独递归）、`key`/`ref`（React 内部）、
					 * 以及函数/对象类型的事件与样式属性（在纯 DOM 上无意义）。
					 */
					for (var key in props) {
						if (!Object.prototype.hasOwnProperty.call(props, key)) continue;
						if (key === 'children' || key === 'key' || key === 'ref') continue;
						var value = props[key];
						if (value === null || value === undefined) continue;
						if (typeof value === 'function') continue;
						if (typeof value === 'object') {
							// `style` 对象：逐条写内联样式
							if (key === 'style' && node.style !== undefined) {
								for (var styleKey in value) {
									if (!Object.prototype.hasOwnProperty.call(value, styleKey)) continue;
									try {
										node.style[styleKey] = value[styleKey];
									} catch (styleError) {
										/* 单个样式失败不影响其它 */
									}
								}
							}
							continue;
						}
						try {
							/*
							 * ⚠️ React 的 `strokeWidth` / `fillRule` 这类**驼峰 prop 名**，
							 * 到 DOM 上必须是 `stroke-width` / `fill-rule`。
							 *
							 * svg 属性名**大小写敏感**：`strokeWidth` 写进去不会报错，
							 * 但浏览器**完全忽略**它 —— 图标描边宽度就是默认值，
							 * 细得几乎看不见。这类“转换器”最容易漏。
							 *
							 * ## ⚠️⚠️ 但要排除 SVG 自己的驼峰属性名
							 *
							 * `viewBox`、`preserveAspectRatio`、`patternUnits`、
							 * `gradientUnits`、`textLength`、`clipPathUnits` ……
							 * 这些**本来就是驼峰**，是 SVG 规范规定的写法。
							 *
							 * 无条件转小写会得到 `view-box` —— 浏览器同样静默忽略，
							 * 于是图标**完全没有坐标系**，渲染出来是错的或空的。
							 * （第一版就这么错的，被测试抓到了。）
							 */
							var SVG_CAMEL_ATTRS = {
								viewBox: 1, preserveAspectRatio: 1, patternUnits: 1,
								patternContentUnits: 1, patternTransform: 1, gradientUnits: 1,
								gradientTransform: 1, clipPathUnits: 1, maskUnits: 1,
								maskContentUnits: 1, textLength: 1, lengthAdjust: 1,
								markerUnits: 1, markerWidth: 1, markerHeight: 1,
								refX: 1, refY: 1, filterUnits: 1, primitiveUnits: 1,
								startOffset: 1, spreadMethod: 1, tableValues: 1,
								pathLength: 1, baseFrequency: 1, numOctaves: 1,
								stdDeviation: 1, stitchTiles: 1, surfaceScale: 1,
								diffuseConstant: 1, specularConstant: 1, specularExponent: 1,
								kernelMatrix: 1, targetX: 1, targetY: 1, radius_keep: 1,
							};
							var attrName = key;
							if (/[A-Z]/.test(key) && SVG_CAMEL_ATTRS[key] !== 1) {
								attrName = key.replace(/[A-Z]/g, function (ch) { return '-' + ch.toLowerCase(); });
							}
							node.setAttribute(attrName, String(value));
						} catch (attrError) {
							/* 个别属性写不进去就跳过 */
						}
					}
					/*
					 * 递归 children。
					 *
					 * ⚠️ 官方图标里 path 的 `stroke="currentColor"` 是**写死在 path 上**的，
					 * 所以这里必须原样搬过来 —— 少了它，图标就是不显色的。
					 */
					var children = props.children;
					if (children !== null && children !== undefined) {
						var list = Array.isArray(children) ? children : [children];
						for (var ci = 0; ci < list.length; ci += 1) {
							var childNode = elementToDom(list[ci], depth + 1);
							if (childNode !== null) node.appendChild(childNode);
						}
					}
					return node;
				} catch (error) {
					return null;
				}
			};

			var buildBadgeNode = function () {
				if (badgeNodeCache !== null) return badgeNodeCache;
				/*
				 * ## ⚠️⚠️ 不再用官方图标 —— 只自绘小电脑（1.2.84 改）
				 *
				 * 旧实现四级降级，但前三级都在拿官方的
				 * `IconGlobeOutlineRegular`（一个**地球**）—— 那是用户截图里看到的那个图标。
				 *
				 * 用户原话：「你这个电脑的标识太丑了，你都用 SVG 了
				 * 你为什么不换一个小小的电脑呢？」
				 *
				 * 地球的语义是「远程 / 网络」，表达不了「这是一台电脑」。
				 * 而且它只有在官方提供该 key 时才拿得到 —— 拿不到就退回自绘，
				 * 于是同一个界面上「有时地球、有时电脑」，观感不统一。
				 *
				 * 现在只保留**一条路**：直接自绘。它不依赖任何模块、
				 * 不依赖 React、不依赖官方是否改名 —— 四种写法里唯一
				 * 不可能失败的一种（`createElementNS` 是 DOM 规范本体）。
				 *
				 * 四级降级看起来「更健壮」，实际上是三个不同的图标源
				 * 在争同一个位置 ——				 * 那才是不稳定的根源。
				 */
				try {
					var NS = 'http://www.w3.org/2000/svg';
					var drawn = document.createElementNS(NS, 'svg');
					drawn.setAttribute('width', '12');
					drawn.setAttribute('height', '12');
					drawn.setAttribute('viewBox', '0 0 16 16');
					drawn.setAttribute('fill', 'none');
					drawn.setAttribute('aria-hidden', 'true');
					/*
					 * ## 画一台小电脑（显示器 + 支柱 + 底座）
					 *
					 * 对齐官方图标的规律（参 `IconFolderCloseRegular` 的 16×16）：
					 *   · 线宽 1、圆角轮廓，与文字粗细接近；
					 *   · 描边用 `currentColor`，深浅主题自动跟随；
					 *   · 四周留 2px 留白，不顶满 16×16。
					 *
					 * 只画**轮廓**，不画屏幕里的内容：12px 下任何内部
					 * 细节都会糊成一团。官方的文件夹图标也只有轮廓。
					 */
					var kOf = function (node, attrs) {
						for (var ai in attrs) {
							if (!Object.prototype.hasOwnProperty.call(attrs, ai)) continue;
							try { node.setAttribute(ai, attrs[ai]); } catch (attrError) { /* 单个属性失败不影响其它 */ }
						}
						return node;
					};
					/* ① 显示器屏幕：宽体圆角矩形，居中偏上 */
					drawn.appendChild(kOf(document.createElementNS(NS, 'rect'), {
						x: '2.5',
						y: '3.5',
						width: '11',
						height: '7.5',
						rx: '1.2',
						ry: '1.2',
						stroke: 'currentColor',
						'stroke-width': '1',
						fill: 'none',
					}));
					/* ② 支柱：屏幕到底座的短竖线 */
					drawn.appendChild(kOf(document.createElementNS(NS, 'path'), {
						d: 'M8 11v1.5',
						stroke: 'currentColor',
						'stroke-width': '1',
						'stroke-linecap': 'round',
						fill: 'none',
					}));
					/* ③ 底座：比屏幕窄的横线，让整体上宽下窄更稳 */
					drawn.appendChild(kOf(document.createElementNS(NS, 'path'), {
						d: 'M5.5 13.5h5',
						stroke: 'currentColor',
						'stroke-width': '1',
						'stroke-linecap': 'round',
						fill: 'none',
					}));					badgeNodeCache = drawn;
					return badgeNodeCache;
				} catch (drawError) {
					// 连 createElementNS 都没有 —— 那真的没法画了。
					return null;
				}
			};

			/**
			 * 把「电脑」标识插进工作区行 —— **替换掉官方那个文件夹图标的位置**。
			 *
			 * ## 用户要求（2026-10-29）
			 *
			 * 「图标的位置能不能跟原来那个文件夹的那个图标位置换一下？
			 *   因为这样子会比较好看一点」
			 *
			 * 所以做法是：找到行里那个**文件夹图标**（官方 `IconFolderCloseRegular`），
			 * 把它隐藏、在原位置插入电脑图标 —— 等价于"换掉那个图标"，
			 * 而不是在行首再挤一个额外的小图标。
			 *
			 * ## 为什么要"隐藏"而不是"删除"
			 *
			 * 那个文件夹节点是 **React 管理的**。我把它从 DOM 里删掉之后，
			 * React 下次渲染时找不到它（`removeChild` 抛 NotFoundError），
			 * 会连带把这棵子树重建 —— 表现为侧边栏闪烁。
			 *
			 * 用 `visibility:hidden` 保留节点、只让它不可见，React 的 diff 就
			 * 完全不受影响，而视觉上那个位置留给了我们插入的电脑图标。
			 */
			/*
 * @param row - 工作区行。
 * @param desktopState - 电脑在线状态 `{ ok, known, latencyMs, reason }`；
 *        传 `null` 表示不画状态灯。它决定下面的 dotColor。
 */
var insertDesktopBadge = function (row, desktopState) {
				if (!row) return;
				if (row.querySelector('[data-dsh-desktop-badge]') !== null) return;
				/*
				 * ## 状态灯的三种颜色（1.2.84）
				 *
				 * 全部走官方主题令牌，深浅色主题自动跟随：
				 *   · 在线   → `--dsw-alias-state-business-primary`（与主按钮同一档绿/蓝）
				 *   · 离线   → `--dsw-alias-label-quaternary`（灰，不引人注意）
				 *   · 未知   → `--dsw-alias-border-l3`（更淡的灰，区分于「确定离线」）
				 *
				 * 每个都带字面 fallback：令牌在某些主题下可能不存在，
				 * 而 `var()` 解析失败会让填充变成透明 ⇒ 看不见状态灯。
				 */
				var dotColor = null;
				try {
					if (desktopState !== null && desktopState !== undefined) {
						if (desktopState.known !== true) {
							dotColor = 'var(--dsw-alias-border-l3, rgba(127,127,127,0.45))';
						} else if (desktopState.ok === true) {
							dotColor = 'var(--dsw-alias-state-business-primary, #4D6BFE)';
						} else {
							dotColor = 'var(--dsw-alias-label-quaternary, rgba(127,127,127,0.5))';
						}
					}
				} catch (dotColorError) {
					dotColor = null;
				}
				var icon = buildBadgeNode();
				/*
				 * ## ⚠️⚠️ 必须 `cloneNode`（1.2.83 修 —— 本轮的真根因）
				 *
				 * `buildBadgeNode()` 按设计**缓存同一个 DOM 节点**实例（`badgeNodeCache`），
				 * 本意是「只画一次」。但 `wrap.appendChild(icon)` 的语义是
				 * **移动**（move）而不是复制：同一个节点不能同时属于两个父节点。
				 *
				 * 于是：
				 *   第 1 行 ⇒ appendChild 把 svg 移进 wrap#1 ⇒ wrap#1 有图；
				 *   第 2 行 ⇒ 拿到同一个节点 ⇒ appendChild 把它**从 wrap#1 抽走**
				 *            ⇒ wrap#2 有图、**wrap#1 变空壳**；
				 *   第 3 行 ⇒ 同理 ⇒ 最后**只有最后一行有图标**，
				 *   前面所有行都是「folder 被隐藏 + 空 span」⇒ **行首一片空白**。
				 *
				 * 这与用户截图**完全吻合**：多行电脑工作区里，只有一行
				 * 有图标（截图里是 `_项目文件夹`），其余全是空白。
				 *
				 * 修法：缓存的只能当**模板**，每行用 `cloneNode(true)` 拿一份独立副本。
				 * clone 失败（非法 DOM）就退回用原节点 —— 至少最后一行能显示。
				 */
				try {
					if (icon !== null && icon !== undefined && typeof icon.cloneNode === 'function') {
						icon = icon.cloneNode(true);
					}
				} catch (cloneError) {
					/* clone 不了就用原节点，不影响本行 */
				}
				if (icon === null) {
					/*
					 * 拿不到图标就**什么也不做** —— 不要留空壳、也不要退回圆点。
					 *
					 * 用户明确否定了"小点"这个方案（「这是你的设计意图吗，
					 * 为什么不做一个小电脑的SVG图标呢」）。宁可不加标识，
					 * 也不要再上一个丑的兜底。
					 */
					return;
				}
				/*
				 * 找官方那个**文件夹图标**的宿主节点。
				 *
				 * ## ⚠️ 直接子层优先，拿不到再往深处找（2026-10-29 修）
				 *
				 * 原来只扫 `row.children`（直接子层）。真机上官方的结构是
				 * 嵌套的（图标 span 可能在一层包装里），于是找不到 ⇒
				 * 走 `else` 分支插在行首 ⇒ 细节上不对。更糟的是：
				 * 万一找到了**错的**节点（比如状态圆点），会把它隐藏。
				 *
				 * 所以加一层校验：只认**尺寸接近 16px 的 svg**，
				 * 并且优先取最靠前的那个（官方布局里图标在最左）。
				 */
				var isFolderIcon = function (node) {
					try {
						if (!node || typeof node.querySelector !== 'function') return false;
						/*
						 * ## ⚠️⚠️ 先看官方的**专属特征**，不要只看尺寸（1.2.74 修）
						 *
						 * 官方真实结构（`dsh-client-ui-workspace` 的 `ownRow`）：
						 *
						 *     <div role="treeitem">
						 *       <span class="slot folder">   <svg>  ← 文件夹图标
						 *       <span class="slot chevron">  <svg>  ← 折叠箭头，**也是 16px**
						 *       <span class="projectText">
						 *       <span class="rowActions">
						 *     </div>
						 *
						 * 旧写法只判尺寸（10–26px），于是 chevron **也被认成文件夹图标**。
						 * 现在没出事只是因为循环碰巧先取到 folder。
						 * 一旦官方调整顺序（或某个版本箭头在前），就会把**箭头**藏掉、
						 * 并在箭头位置插徽标 ⇒ 用户看到的就是「图标位置不对 / 行首空白」。
						 *
						 * 策略：字面信号 > 尺寸猜测。
						 *   · 命中 `folder` ⇒ 就是它；
						 *   · 命中 `chevron/arrow/triangle/caret` ⇒ 不是它。
						 * 两者都没命中时，才回到尺寸判断。
						 */
						var hintOf = function (n) {
							try {
								var parts = [];
								if (n && typeof n.className === 'string') parts.push(n.className);
								else if (n && n.getAttribute) {
									var cn = n.getAttribute('class');
									if (cn) parts.push(String(cn));
								}
								if (n && n.getAttribute) {
									var d = n.getAttribute('data-icon');
									if (d) parts.push(String(d));
									var tid = n.getAttribute('data-testid');
									if (tid) parts.push(String(tid));
									var aria = n.getAttribute('aria-label');
									if (aria) parts.push(String(aria));
								}
								return parts.join(' ').toLowerCase();
							} catch (hintError) {
								return '';
							}
						};
						/* ① 自身的字面信号 */
						var selfHint = hintOf(node);
						var selfArrow = /chevron|arrow|triangle|caret|expander/.test(selfHint);
						var selfFolder = /folder/.test(selfHint);
						if (selfArrow && !selfFolder) return false;
						if (selfFolder && !selfArrow) return true;
						/* ② 后代 svg 的字面信号（图标在子层时） */
						var innerSvgs = node.querySelectorAll('svg');
						for (var hi = 0; hi < innerSvgs.length; hi += 1) {
							var ih = hintOf(innerSvgs[hi]);
							if (/chevron|arrow|triangle|caret|expander/.test(ih)) return false;
							if (/folder/.test(ih)) return true;
						}
						/*
						 * ## ⚠️⚠️ `querySelectorAll` **不包含节点自身**（1.2.49 修）
						 *
						 * 这是本轮抓到的真缺陷。官方把图标直接渲染成
						 * `<svg>`（`ProjectRowItem` 里就是一个裸 svg，没有
						 * 包裹层），于是 `row.children[0]` **本身就是那个 svg**。
						 *
						 * 而 `svgEl.querySelectorAll('svg')` 按规范只返回
						 * **后代**元素，对它自己返回**空集合** —— 实测：
						 *
						 *     row.children[0].tagName                 // 'svg'
						 *     row.children[0].querySelectorAll('svg') // length 0
						 *
						 * ⇒ `isFolderIcon` 恒为 `false`
						 * ⇒ 找不到文件夹图标（`folderHost = null`）
						 * ⇒ 走 else 分支把图标插到**行首**
						 * ⇒ 用户看到的正是「行首一片空白、图标位置不对」。
						 *
						 * 现在：先看节点自身，再看后代。
						 */
						var widthOf = function (s) {
							var w = 0;
							var attrW = s.getAttribute('width');
							if (attrW !== null && attrW !== '') w = parseFloat(attrW);
							if (!(w > 0) && typeof s.getBoundingClientRect === 'function') {
								try { w = s.getBoundingClientRect().width; } catch (rectError) { w = 0; }
							}
							return w;
						};
						/*
						 * ③ 尺寸判断（字面信号都没命中时才走这里）
						 *
						 * ⚠️ `width` 属性**可能不存在** —— 官方很多 svg 只给 `viewBox`，
						 * 尺寸由 CSS 定。而 `getBoundingClientRect()` 在两种情况下返回 0：
						 *   · 侧边栏刚展开、还没布局；
						 *   · 行处于 `visibility:hidden` / 父层未渲染。
						 * 两者都会让 `isFolderIcon` 误判为 false ⇒ 找不到文件夹图标
						 * ⇒ 退到「插在行首」⇒ 行首多出 16px 空白。
						 *
						 * 所以：宽度取不到时，只要命中 folder 字样就算它是。
						 */
						/* ① 节点自身就是 svg（官方有这种结构） */
						if (node.tagName !== undefined
							&& String(node.tagName).toLowerCase() === 'svg') {
							var wSelf = widthOf(node);
							if (wSelf >= 10 && wSelf <= 26) return true;
							/*
							 * 宽度不可知（没属性 + rect 为 0）时，看父层是不是 folder 槽位。
							 * 这一条是专门为「刚展开还没布局」准备的。
							 */
							if (!(wSelf > 0) && node.parentNode
								&& /folder/.test(hintOf(node.parentNode))) return true;
							return false;
						}
						/* ② 后代里有 svg（有包裹层的版本） */
						var svgs = node.querySelectorAll('svg');
						for (var si = 0; si < svgs.length; si += 1) {
							/*
							 * 宽度取属性，没属性就看实际尺寸（getBoundingClientRect）。
							 * 容差放宽到 10–26px：不同版本 / 不同屏幕密度下
							 * 官方这个图标实测在 14–20px 之间。
							 */
							var w = widthOf(svgs[si]);
							if (w >= 10 && w <= 26) return true;
						}
						return false;
					} catch (error) {
						return false;
					}
				};
				var folderHost = null;
				var direct = row.children;
				for (var at = 0; at < direct.length; at += 1) {
					var child = direct[at];
					if (child === undefined || child === null) continue;
					if (isFolderIcon(child)) {
						folderHost = child;
						break;
					}
				}
				var wrap = document.createElement('span');
				wrap.setAttribute('data-dsh-desktop-badge', '1');
				var ws = wrap.style;
				ws.display = 'inline-flex';
				ws.alignItems = 'center';
				ws.justifyContent = 'center';
				ws.flexShrink = '0';
				ws.width = '16px';
				ws.height = '16px';
				/*
				 * 颜色用官方"次要信息"令牌，与原来的文件夹图标同一档 ——
				 * 换图标不换醒目程度，用户不会觉得这一行突然变重。
				 */
				/*
				 * ## ⚠️ 颜色三重保障（2026-10-29，防“图标拿到但不可见”）
				 *
				 * `IconGlobeOutlineRegular` 是**描边**图标，描边用 `currentColor`。
				 * 如果 `--dsw-alias-label-secondary` 在这个位置解析不到（不同主题 /
				 * 不同版本可能没有这个变量），`var()` 就会失败。
				 *
				 * 而它一旦失败，描边就是**透明的** —— 用户看到的就是
				 * 「文件夹图标被隐藏了、电脑图标也看不见」⇒ 整行空白。
				 * 这正是用户截图里的样子。
				 *
				 * 所以：
				 *   ① `color` 用两级 fallback（业务主色 → currentColor）；
				 *   ② 再直接给 svg 设 `stroke` 与 `color`，
				 *      不依赖上下文能否正确继承；
				 *   ③ 明确设 `visibility:visible`，防止从某个隐藏父层继承。
				 */
				ws.color = 'var(--dsw-alias-label-secondary, var(--dsw-alias-label-tertiary, currentColor))';
				ws.visibility = 'visible';
				ws.verticalAlign = 'middle';
				/*
				 * ## ⚠️ 在线状态灯（1.2.84 新增）
				 *
				 * 用户原话：「我希望在工作区可以显示出电脑的在线状态？
				 * 比如你在侧边栏的某个位置加一个状态灯？」
				 *
				 * ## 为什么做在小电脑图标的右上角
				 *
				 *   · 放行尾：离标题远，被当成「另一个按钮」；
				 *   · 放文字后：长标题会把它挤出可见区；
				 *   · 放图标右上角：**与图标同一个视觉对象**，
				 *     一眼就知道它属于这台电脑 —— 也是 IM 表示在线的惯例位置。
				 *
				 * ## 为什么用 SVG 而不是 CSS 圆点
				 *
				 * 用户明确要求：「这个状态灯你也记得用 SVG 换一个」。
				 * 而且 SVG 能做 CSS 做不到的事：给点加一圈**背景色边框**，
				 * 让它从小电脑的轮廓里「浮出来」。CSS 圆点在 1px 线框上会混成一团。
				 */
				if (desktopState !== null && desktopState !== undefined) {
					try {
						var dotNS = 'http://www.w3.org/2000/svg';
						var dot = document.createElementNS(dotNS, 'svg');
						dot.setAttribute('width', '7');
						dot.setAttribute('height', '7');
						dot.setAttribute('viewBox', '0 0 8 8');
						dot.setAttribute('aria-hidden', 'true');
						dot.setAttribute('data-dsh-online-dot', '1');
						var ds = dot.style;
						ds.position = 'absolute';
						ds.right = '-2px';
						ds.top = '-1px';
						ds.pointerEvents = 'none';
						var dotCircle = document.createElementNS(dotNS, 'circle');
						dotCircle.setAttribute('cx', '4');
						dotCircle.setAttribute('cy', '4');
						dotCircle.setAttribute('r', '2.6');
						/* 颜色三态：在线 / 离线 / 未知（见下方 dotColor 的推导） */
						dotCircle.setAttribute('fill', dotColor);
						/* 一圈背景色边框：让点从图标轮廓上「浮出来」 */
						dotCircle.setAttribute('stroke', 'var(--dsw-alias-bg-layer-1, #FFFFFF)');
						dotCircle.setAttribute('stroke-width', '1.2');
						dot.appendChild(dotCircle);
						wrap.style.position = 'relative';
						wrap.appendChild(dot);
					} catch (dotError) {
						/* 状态灯失败绝不能影响图标本身 */
					}
				}
				/*
				 * 直接给图标元素本身上色 —— 不依赖它内部是否正确
				 * 使用了 `currentColor`（图标集实现可能变化）。
				 */
				try {
					icon.style.color = 'var(--dsw-alias-label-secondary, currentColor)';
					icon.style.visibility = 'visible';
					if (icon.setAttribute !== undefined) {
						icon.setAttribute('stroke', 'currentColor');
						icon.setAttribute('fill', 'none');
					}
				} catch (iconStyleError) {
					/* 图标上色失败不影响插入 */
				}
				wrap.appendChild(icon);
				/*
				 * ## ⚠️⚠️ 必须验证「图标真的进去了」（1.2.83 修）
				 *
				 * 断言实测：徽标 `span` 能被插进行里、folder 也被隐藏了，
				 * 但 **span 里面是空的**（`childNodes.length === 0`，实测尺寸 0×0）。
				 * 于是用户看到的就是「行首一片空白」—— folder 没了，徽标也没图。
				 *
				 * `buildBadgeNode()` 已经四级降级且都会校验有图形元素，
				 * 但它校验的是**它自己返回的那个节点**。一旦这个节点
				 * 在 `appendChild` 过程中没真正挂上，后面就没人检查了。
				 *
				 * 所以这里直接数子节点：为 0 则**不要把 folder 隐藏**，
				 * 宁可保留原来那个文件夹图标（至少不空白），也不要留下一个空壳。
				 * 这是「失败时保持原样」而不是「失败时更糟」。
				 */
				var iconMounted = false;
				try {
					iconMounted = wrap.childNodes !== undefined && wrap.childNodes.length > 0;
				} catch (mountCheckError) {
					iconMounted = false;
				}
				if (!iconMounted) {
					/*
					 * 图标没挂上 ⇒ 整个徽标放弃：**不插、不隐藏 folder**。
					 * 留下诊断痕迹，供「侧栏自检」行显示。
					 */
					try { window.__dshRemoteSidebarError = 'badge icon not mounted'; } catch (mountProbeError) { /* ignore */ }
					return;
				}
				try {
					if (folderHost !== null && folderHost.parentNode === row) {
						/*
						 * 把文件夹图标藏起来（保留节点，见上面的 KDoc），
						 * 在**同一位置**插入电脑图标。
						 *
						 * ## ⚠️⚠️ 只 `visibility:hidden` **不够**（1.2.49 修）
						 *
						 * `visibility:hidden` 会让元素**不可见但仍占位**。
						 * 而我们在它**前面**插了一个 16px 的徽标 ⇒ 那一格
						 * 同时被「徽标 16px」+「隐藏的文件夹图标 16px」占着
						 * ⇒ 行首多出 16px 空白、标题整体右移。
						 *
						 * 用户原话是「图标的位置能不能跟原来那个文件夹的
						 * 那个图标**位置换一下**」—— 要的是**换**，不是**并排**。
						 *
						 * 所以：让它塌陷成 0 尺寸（节点仍在 ⇒ React 安全），
						 * 徽标正好落在它原来的位置上，行宽不变。
						 *
						 * 用 `display:none` 会触发 React 的 removeChild 问题吗？
						 * 不会 —— 那不是从 DOM 移除，节点还在树上。但
						 * `display:none` 在部分 WebView 上会让 svg 尺寸计算异常，
						 * 所以这里用 width/height/padding/margin 归零 + 继续
						 * 保留 `visibility:hidden` 双保险，最稳。
						 */
						folderHost.style.visibility = 'hidden';
						folderHost.style.width = '0';
						folderHost.style.height = '0';
						folderHost.style.minWidth = '0';
						folderHost.style.minHeight = '0';
						folderHost.style.overflow = 'hidden';
						folderHost.style.padding = '0';
						folderHost.style.margin = '0';
						folderHost.style.flexGrow = '0';
						folderHost.setAttribute('data-dsh-folder-hidden', '1');
						row.insertBefore(wrap, folderHost);
						/*
						 * ⚠️⚠️ 必须给行打标记（1.2.50 修 —— 这是个悬空引用）
						 *
						 * `stop()` 卸载时的清理逻辑查的是
						 * `document.querySelectorAll('[data-dsh-desktop-row]')`，
						 * 但**这个属性从来没被设置过**（全文件只有两处：
						 * 查它、和移除它）。
						 *
						 * 后果：插件被禁用 / 热重载时，清理循环**一次都不执行**
						 * ⇒ 徽标留在侧边栏上没人认领，而且我们藏起来的文件夹
						 * 图标**永远不恢复** ⇒ 那一行变成彻底的空白行。
						 *
						 * 用户看到的就是「禁用插件后侧边栏更乱了」，
						 * 只能重启 GUI 才好 —— 这正是卸载逻辑想避免的事。
						 *
						 * 现在补上设置，让清理能真正跑起来。
						 */
						row.setAttribute('data-dsh-desktop-row', '1');
					} else {
						/* 没找到文件夹图标：退回插在行首，至少标识还在 */
						row.insertBefore(wrap, row.firstChild);
						row.setAttribute('data-dsh-desktop-row', '1');
					}
				} catch (ignored) {
					/* 行结构异常就放弃标记，不影响功能 */
				}
			};

			/**
			 * 同步一次：打行标识 + 给在线圆点配色。
			 *
			 * 由 `setInterval` 驱动（见下面的定时器），也可被外部立即调一次。
			 */
			/**
			 * 这一行的标题是否属于「电脑工作区」。
			 *
			 * 判据是宿主写入的**零宽前缀**（`DESKTOP_TITLE_PREFIX = '\u200B'`）。
			 *
			 * ## ⚠️ 为什么不用 `String.prototype.trim()`
			 *
			 * 踩过的坑：第一版用 `\u2009`（thin space）当标记，而 `trim()` 和
			 * `\s` 都会把它吃掉 ⇒ 一个电脑工作区都认不出来，而且不报错。
			 * 现在用零宽空格（`trim()` 不碰它），下面只剥**真正的排版空白**，
			 * 显式排除所有零宽字符。
			 */
			var isDesktopTitle = function (text) {
				var t = String(text === undefined || text === null ? '' : text);
				t = t.replace(/^[ \t\r\n\u00a0\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u202f\u205f\u3000]+/, '');
				return t.indexOf('\u200B') === 0;
			};

						/**
			 * 找出所有「电脑工作区」行。
			 *
			 * 判据只有一条：`role="treeitem"` 且标题以**零宽前缀**开头。
			 * 前缀由宿主 `desktopCategoryTitle()` 写入（见 `DESKTOP_TITLE_PREFIX`），
			 * 用户看不见它，所以侧边栏里显示的是干净的目录名。
			 */
			/*
			 * 这一行是不是电脑工作区行？
			 *
			 * ## ⚠️⚠️ 先看结构标识，看不到才退回标题前缀（1.2.75 修）
			 *
			 * 官方 `ownRow` 里写了精确的标识：
			 *
			 *     工作区行   data-row-key="workspace:<key>"
			 *     会话行     data-row-key="session:<id>"
			 *
			 * 这比猜文本可靠得多。原来只看 `textContent` 是否以零宽开头，
			 * 而 `textContent` 会把**所有子元素的文字拼在一起**（包括 `rowActions`
			 * 里的「⋯」按钮文字）。于是会出现两种坏情况：
			 *
			 *   ① **会话标题恰好以零宽开头**（用户复制粘贴很容易带上）
			 *      ⇒ 该会话被认成电脑行 ⇒ 被插上电脑图标、图标槽被藏。
			 *   ② 工作区行的标题前面若多出任何一个字符（官方改版式）
			 *      ⇒ 认不出来 ⇒ **图标完全不出现**。
			 *
			 * 现在：结构标识优先；只有拿不到 `data-row-key` 时才猜文本。
			 */
			var rowKindOf = function (row) {
				try {
					if (!row || typeof row.getAttribute !== 'function') return '';
					var key = row.getAttribute('data-row-key');
					if (typeof key !== 'string' || key === '') return '';
					var at = key.indexOf(':');
					if (at <= 0) return '';
					/* 只认两种已知类型，其他一律当作“不知道” */
					var kind = key.slice(0, at);
					if (kind === 'workspace' || kind === 'session') return kind;
					return '';
				} catch (kindError) {
					return '';
				}
			};
			var isDesktopRow = function (row) {
				/*
				 * ⚠️ 先判空。
				 * 下面要读 `row.textContent`；传进 `null` 时会抛 TypeError，
				 * 而调用方（`decorateDesktopRows` 的循环）并不去检查。
				 * 抛出去就是整次装饰失败 —— 图标又没了。
				 */
				if (!row) return false;
				/* ① 结构标识最可靠 */
				var kind = rowKindOf(row);
				if (kind === 'session') return false;
				if (kind === 'workspace') return isDesktopTitle(row.textContent);
				/* ② 拿不到结构标识（官方版本变了）—— 退回标题前缀 */
				return isDesktopTitle(row.textContent);
			};
			var findDesktopRows = function () {
				var out = [];
				var rows = document.querySelectorAll('[role="treeitem"]');
				for (var i = 0; i < rows.length; i += 1) {
					if (isDesktopRow(rows[i])) out.push(rows[i]);
				}
				return out;
			};

			/**
			 * 给每个电脑工作区行换上「电脑」图标。
			 *
			 * ## 为什么不插分组标题了
			 *
			 * 1.2.29 试过「在第一个电脑行前插一条『电脑工作区』标题」，结论是
			 * 不可靠：官方侧边栏按**最近使用时间**排序，电脑行会被手机行隔开，
			 * 一条标题只能圈住其中一段，反而误导。
			 *
			 * 现在既然每一行都有自己的电脑图标（替换掉文件夹图标），
			 * 分组标题就完全多余了 —— 少一层 DOM 也少一处会闪烁的地方。
			 *
			 * @returns 第一个电脑行（供调用方挂在线状态），没有则 `null`。
			 */
			var decorateDesktopRows = function () {
				var rows = findDesktopRows();
				if (rows.length === 0) return null;
				/*
				 * 把当前在线状态传给每一行 —— 状态灯是**行级**的，
				 * 而不是一个全局切换。
				 *
				 * ⚠️ 不能因为「没探测到」就不传：那会让状态灯忽然消失、
				 * 又忽然出现，看起来像闪烁。传 `null` 才表示「不画」。
				 */
				var st = desktopOnlineRef.value;
				for (var i = 0; i < rows.length; i += 1) {
					insertDesktopBadge(rows[i], st);
				}
				return rows[0];
			};

			/**
			 * 同步一次：给电脑行换图标 + 把在线状态写进行属性。
			 *
			 * 由定时器驱动（见下面的 `setInterval`）。
			 *
			 * ## ⚠️ 幂等性是硬要求
			 *
			 * 这个函数每 1.5 秒跑一次。任何「每次都追加节点」的写法都会
			 * 让侧边栏越长越乱 —— 所以插入图标那一步必须先检查有没有插过
			 * （`insertDesktopBadge` 内部有 `data-dsh-desktop-badge` 判重）。
			 */
			/**
			 * ## ⚠️⚠️ 定时器回调必须整体兜底（1.2.56 修）
			 *
			 * 这个函数每 1.5 秒被 `setInterval` 调一次。内部虽然逐段有 try，
			 * 但 `decorateDesktopRows()` 那一句是**裸调用**：
			 *
			 *     var firstRow = decorateDesktopRows();
			 *
			 * 它里面会 `buildBadgeNode()` → `document.createElementNS` 画 svg。
			 * 任何一步抛错（旧内核不认某个属性、DOM 处于怪异状态、
			 * 官方结构变了导致取不到东西），异常都会**穿过 sync 逃出去**。
			 *
			 * 后果不是崩一次就完 —— 是每 1.5 秒**抛一次**，无限循环。
			 * 而 `installSyncMenu` 外面那层 guard 早就返回了，接不住后续的
			 * 定时器回调 ⇒ 侧边栏图标**永远插不上**、也没有任何提示。
			 *
			 * 这就是「装饰像是没生效」的典型成因：不是逻辑错，是定时器
			 * 每次都在同一个地方炸掉。所以整段包一层 try，并且**只吞不抛**。
			 */
			var sync = function () {
				if (stopped) return;
				try {
					syncOnce();
				} catch (syncError) {
					/*
					 * 吞掉：绝不能让定时器连环抛错。但要留痕 ——
					 * 用户「改了没效果」时，这是唯一的现场证据。
					 */
					try {
						window.__dshRemoteSidebarError = String(syncError);
					} catch (probeError) { /* 诊断失败不影响主流程 */ }
				}
			};
			/** `sync` 的正体（由外层统一兜底，见上）。 */
			var syncOnce = function () {
				if (stopped) return;
				/*
				 * ## ⚠️⚠️ 先清理「孤儿装饰」（1.2.50 修）
				 *
				 * 必须放在 `decorateDesktopRows()` **之前**，而且**不能**被
				 * 下面那个 `if (firstRow === null) return` 挡住。
				 *
				 * 场景：某个工作区原来是电脑工作区（我们给它插了徽标、
				 * 把文件夹图标塌陷隐藏），后来用户解绑 / 清理了它，
				 * 那一行的标题不再带零宽前缀 ⇒ `findDesktopRows()` 找不到它
				 * ⇒ `decorateDesktopRows()` 也不会再碰它
				 * ⇒ **徽标留在那里，文件夹图标永远是 0×0 的隐形状态**。
				 *
				 * 用户看到的是：那一行没有图标，而且点哪儿都不对。
				 * 而且原来 `if (firstRow === null) return` 会让
				 * 「一个电脑行都没有」的情况直接跳过整个函数 ——
				 * 那时恰恰是最需要清理的时候。
				 *
				 * 所以这里无条件扫一遍：凡是被我们标记过、
				 * 但现在已不是电脑工作区的行，把装饰全部撤销。
				 */
				try {
					var decorated = document.querySelectorAll('[data-dsh-desktop-row]');
					for (var di = 0; di < decorated.length; di += 1) {
						var dRow = decorated[di];
						try {
							if (isDesktopTitle(dRow.textContent)) continue; // 仍是电脑行，留着
							var dBadge = dRow.querySelector('[data-dsh-desktop-badge]');
							if (dBadge && dBadge.parentNode) dBadge.parentNode.removeChild(dBadge);
							var dHidden = dRow.querySelectorAll('[data-dsh-folder-hidden]');
							for (var dh = 0; dh < dHidden.length; dh += 1) {
								try {
									var dhEl = dHidden[dh];
									dhEl.style.visibility = '';
									dhEl.style.width = '';
									dhEl.style.height = '';
									dhEl.style.minWidth = '';
									dhEl.style.minHeight = '';
									dhEl.style.overflow = '';
									dhEl.style.padding = '';
									dhEl.style.margin = '';
									dhEl.style.flexGrow = '';
									dhEl.removeAttribute('data-dsh-folder-hidden');
								} catch (dhError) { /* 单个失败不影响其它 */ }
							}
							dRow.removeAttribute('data-dsh-desktop-row');
							dRow.removeAttribute('title');
						} catch (dRowError) { /* 单行失败继续下一条 */ }
					}
				} catch (cleanupError) { /* 清理失败绝不能影响正常装饰 */ }

				var firstRow = decorateDesktopRows();
				if (firstRow === null) return;
				/*
				 * ## 在线状态写在行属性上，不再有「分组标题上的圆点」
				 *
				 * 1.2.29 那个圆点是挂在分组标题里的，现在没有分组标题了。
				 * 而且用户明确否定了圆点这种表达（「这是你的设计意图吗」）。
				 *
				 * 用 `title` 属性：手机侧边栏一行只有 34px 高，再塞一个可见
				 * 状态点会把标题挤窄；长按/悬浮能看到"电脑在线（23 ms）"就够。
				 */
				var st = desktopOnlineRef.value || {};
				var tip = '电脑状态未知';
				if (st.known === true) {
					if (st.ok === true) {
						tip = '电脑在线'
							+ (typeof st.latencyMs === 'number' ? '（' + st.latencyMs + ' ms）' : '');
					} else {
						tip = st.reason ? ('电脑离线：' + st.reason) : '电脑离线';
					}
				}
				var rows = findDesktopRows();
				for (var ri = 0; ri < rows.length; ri += 1) {
					try {
						if (rows[ri].getAttribute('title') !== tip) rows[ri].setAttribute('title', tip);
					} catch (titleError) { /* 单个失败不影响其它行 */ }
				}
				/*
				 * ## ⚠️️ 真机自检：把「找到几行 / 插了几个徽标 / 徽标多大」写进全局变量
				 *
				 * 用户报「侧边栏电脑标记不显示」，代码路径上看不出问题：
				 * findDesktopRows 能认、buildBadgeNode 四级降级都完备、
				 * wrap 尺寸颜色都对。那就只能靠**现场数据**定位。
				 *
				 * 这段把实测值存起来，配合 App 的「导出诊断包」就能看到：
				 *   · `rows`     —— 认出几个电脑行（与用户数到的图标数应一致）
				 *   · `badges`   —— DOM 里真实存在几个徽标节点
				 *   · `box`      —— 第一个徽标的**实测尺寸**（0 = 真的没显示出来）
				 *   · `folderHidden` —— 被隐藏的文件夹图标数（应等于 badges）
				 *   · `err`      —— 装饰过程中的最后一个错误
				 *
				 * ⚠️ 全程 try：诊断本身绝不能影响装饰。
				 */
				try {
					var allBadges = document.querySelectorAll('[data-dsh-desktop-badge]');
					var allHidden = document.querySelectorAll('[data-dsh-folder-hidden]');
					var firstBox = null;
					if (allBadges.length > 0) {
						var b0 = allBadges[0];
						var rect = b0.getBoundingClientRect();
						firstBox = { w: Math.round(rect.width), h: Math.round(rect.height) };
				}
					window.__dshRemoteSidebarDiag = {
						rows: rows.length,
						badges: allBadges.length,
						folderHidden: allHidden.length,
						box: firstBox,
						err: window.__dshRemoteSidebarError === undefined ? null : window.__dshRemoteSidebarError,
						at: Date.now(),
				};
				} catch (diagError) { /* 诊断失败不影响装饰 */ }
			};

			var timer = setInterval(sync, 1500);
			sync();

			return function stop() {
				stopped = true;
				if (timer !== null) clearInterval(timer);
				timer = null;
				/*
				 * 卸载时把装饰全部撤掉：分组标题（圆点在它里面）+ 每行的标识。
				 *
				 * 不撤的话，插件被禁用/热重载后侧边栏上会残留一堆蓝线，
				 * 而没有任何插件认领它们 —— 用户只能重启 GUI。
				 */
				var heads = document.querySelectorAll('[' + GROUP_ATTR + ']');
				for (var hi = 0; hi < heads.length; hi += 1) {
					if (heads[hi].parentNode) {
						try { heads[hi].parentNode.removeChild(heads[hi]); } catch (ignored) {}
					}
				}
				var marked = document.querySelectorAll('[data-dsh-desktop-row]');
				for (var mi = 0; mi < marked.length; mi += 1) {
					var m = marked[mi];
					try {
						var badge = m.querySelector('[data-dsh-desktop-badge]');
						if (badge && badge.parentNode) badge.parentNode.removeChild(badge);
						/*
						 * ⚠️ 必须把藏起来的文件夹图标**恢复显示** ——
						 * 这个工作区不再是电脑工作区（用户解绑 / 清理）时，
						 * 不给它恢复的话那一行就永远没有图标，看起来像坏了。
						 */
						var hidden = m.querySelectorAll('[data-dsh-folder-hidden]');
						/*
						 * ⚠️ 循环变量**不能**叫 `hi` —— 外层遍历分组标题的循环也用 `hi`
						 * （同一函数作用域，`var` 不隔层），复用会让外层提前退出。
						 */
						for (var ri2 = 0; ri2 < hidden.length; ri2 += 1) {
							try {
								var hEl = hidden[ri2];
								hEl.style.visibility = '';
								/*
								 * ⚠️ **尺寸也必须恢复**（1.2.50 修）
								 *
								 * 插入时为了让出位置，把这几项全归零了
								 *（否则徽标和隐藏图标同时占位 ⇒ 行首多 16px）。
								 * 如果卸载时只恢复 visibility 而不恢复尺寸，
								 * 这个文件夹图标会变成 **0×0 的隐形元素** ——
								 * 视觉上仍然是"那一行没有图标"。
								 *
								 * 所以每一项都要显式清回空字符串（= 交还给
								 * 官方样式表 / 元素的 width 属性）。
								 */
								hEl.style.width = '';
								hEl.style.height = '';
								hEl.style.minWidth = '';
								hEl.style.minHeight = '';
								hEl.style.overflow = '';
								hEl.style.padding = '';
								hEl.style.margin = '';
								hEl.style.flexGrow = '';
								hEl.removeAttribute('data-dsh-folder-hidden');
							} catch (restoreError) { /* 单个失败不影响其它 */ }
						}
						m.removeAttribute('data-dsh-desktop-row');
					} catch (ignored) {}
				}
			};
		}

		//#endregion

		/** 自动同步的日志（只在真正有事件时打，避免刷屏）。 */
		function logAutoSync(reason, createdCount, workspaceCount, errorText) {
			try {
				if (errorText) {
					console.warn('[dsh-jiuling-remote-mobile] 自动同步失败(' + reason + '): ' + errorText);
				} else {
					console.info(
						'[dsh-jiuling-remote-mobile] 自动同步完成(' + reason + '): '
						+ workspaceCount + ' 个工作区, 新建 ' + createdCount + ' 个对话',
					);
				}
			} catch (ignored) { /* 日志失败不影响主流程 */ }
		}

		//#endregion


		/**
		 * 把同步进度**用原生 DOM** 挂到侧边栏面板上。
		 *
		 * ## 为什么不用 React
		 *
		 * 进度浮层要挂在「侧边栏面板」这个官方 DOM 上，而宿主没有给我们
		 * 一个对应的 slot。现有「同步对话」对话框走的是
		 * `ReactDOM.createRoot(body 下的 div)`（见 `ensureHost`），
		 * 那是**独立 portal**，会盖在面板之外。
		 *
		 * 进度浮层需要贴着面板底部（用户的视线在那里），所以直接用原生 DOM
		 * 定位到面板内部更稳，也少一层 React 开销。
		 *
		 * ## ⚠️ 必须自己管生命周期
		 *
		 * 原生 DOM 不受 React 回收，所以：
		 *   · 每次更新都先找有没有现存节点（按 `data-dsh-sync-progress`），
		 *     有就复用，避免重复插入；
		 *   · 终态（done/failed）后延时移除，不留垃圾节点；
		 *   · `dispose` 时强制清掉，防止面板重建后残留。
		 */
		/**
		 * 首次同步的**进度浮层**（需求 7）。
		 *
		 * ## 用户对这版的意见（2026-10-29，逐条）
		 *
		 * 「图1下面这个黑色横幅太丑太丑太丑了，你是一点设计感都没有吗，而且你
		 *   为什么要坐在下面呢，我是建议你用官方的一些组件来进行设计，因为这样
		 *   子用户换主题的话，比如他换浅色模式，他也可以跟着变过来，还有就是
		 *   我希望动画是出现在屏幕中间，而不是在下面」
		 *
		 * 「在开始真正的进度计算之前的那个等待过程，你没有做任何的一个文字提示，
		 *   比如那个阶段我猜测的话应该是在等待电脑告诉我手机有多少数据吧，
		 *   那你也可以写一下，比如说正在连接电脑」
		 *
		 * ## 因此这一版全部重写
		 *
		 * | 问题 | 上一版 | 现在 |
		 * |---|---|---|
		 * | 位置 | `position:absolute; bottom:16px` 贴底 | **屏幕正中**，flex 居中 |
		 * | 配色 | 硬编码 `rgba(28,32,38,0.94)` 深色 | 全走 `--dsw-alias-*` 令牌，**跟随主题** |
		 * | 造型 | 一条黑色横条 + 6px 进度条 | 圆角卡片 + 图标 + 标题 + 细进度条 |
		 * | 无预估时 | 只写"正在统计…"，进度条卡 38% | 分阶段文案 + **不确定态流动动画** |
		 *
		 * ## 为什么进度条在"未知总数"时必须动
		 *
		 * 用户原话：「他刚开始同步的时候，下面那个进度条一直是卡在那个位置的，
		 * 等了大概有好一会之后，我才变成了图示的这个图片」。
		 *
		 * 静态停在 38% 会让人以为卡死。未知阶段改用不确定态（indeterminate）
		 * 的横向流动动画 —— 这是进度条的标准语汇，传达"还在进行、总量未知"。
		 * 一旦拿到真实总数就切回确定态按百分比走（这部分用户说"做得非常好"）。
		 */
		function mountSyncProgress(getState, isAlive) {
			var ATTR = 'data-dsh-sync-progress';
			var node = null;
			var cardEl = null;
			var fill = null;
			var titleEl = null;
			var subEl = null;
			var pctEl = null;
			var iconWrap = null;
			var removeTimer = null;
			/*
			 * 刷新循环的两个句柄。
			 *
			 * ⚠️ 必须在 `teardown` **之前**声明。
			 * `teardown` 里要用它们判断「有没有东西需要清」：
			 * 若声明在后，虽然 `var` 提升不会抛 ReferenceError，
			 * 但初值是 `undefined` 而不是 `null` ⇒ `!== null` 成立
			 * ⇒ 会去调 `cancelAnimationFrame(undefined)`，
			 * 属于「写错了但恰好没崩」的隐患，必须避免。
			 */
			var rafId = null;
			var intervalId = null;

			/**
			 * 挂到 `document.body`，**不用侧边栏容器**。
			 *
			 * ⚠️ 上一版挂在侧边栏面板里（`[data-dsh-sidebar]` 等），三个问题：
			 *   1. 侧边栏可能**根本没渲染**（用户从对话页触发同步）⇒ 挂不上；
			 *   2. 面板有自己的 `overflow:hidden`，贴底卡片被裁掉一半；
			 *   3. 面板宽度随侧栏拖拽变化，卡片跟着变形。
			 *
			 * 用户要求"出现在屏幕中间" —— 那本来就是**全屏浮层**的语义，
			 * 挂 body + `position:fixed` 才是对的。
			 */
			var ensureNode = function () {
				if (node && node.isConnected) return node;
				if (!document.body) return null;

				node = document.createElement('div');
				node.setAttribute(ATTR, '1');
				/*
				 * `role="status"` + `aria-live="polite"`：读屏在内容变化时朗读
				 * 进度，且不打断用户当前操作（polite 而非 assertive）。
				 */
				node.setAttribute('role', 'status');
				node.setAttribute('aria-live', 'polite');

				var s = node.style;
				// —— 遮罩层：全屏居中 ——
				s.position = 'fixed';
				s.left = '0';
				s.top = '0';
				s.right = '0';
				s.bottom = '0';
				s.display = 'flex';
				s.alignItems = 'center';
				s.justifyContent = 'center';
				/*
				 * 遮罩本身完全透明：这是**进度提示**不是模态框，
				 * 用户仍应能看清后面的界面（同步期间他可能还在看对话）。
				 */
				s.background = 'transparent';
				s.zIndex = '2147483000';
				/*
				 * ⚠️ `pointer-events:none` 打在遮罩上、卡片单独恢复 auto：
				 * 否则整屏不可点，同步期间用户什么都干不了。
				 */
				s.pointerEvents = 'none';
				s.transition = 'opacity 240ms ease';
				s.opacity = '0';

				// —— 卡片 ——
				cardEl = document.createElement('div');
				/*
				 * 关键：**每个颜色都来自官方设计令牌**，深浅主题自动跟随。
				 * 令牌名都核对过（`--dsw-alias-bg-layer-*` / `label-*` /
				 * `border-l3` / `state-business-primary`）。
				 */
				var cs = cardEl.style;
				cs.boxSizing = 'border-box';
				cs.minWidth = '260px';
				cs.maxWidth = 'min(78vw, 340px)';
				cs.padding = '20px 22px 18px';
				cs.borderRadius = '16px';
				cs.background = 'var(--dsw-alias-bg-layer-1, #FFFFFF)';
				cs.color = 'var(--dsw-alias-label-primary, #0F1115)';
				cs.border = '0.5px solid var(--dsw-alias-border-l3, rgba(127,127,127,0.24))';
				/*
				 * 阴影用中性黑（不随主题变）：浅色下是柔和投影，深色下几乎看不见，
				 * 两种情况都不刺眼。不用令牌是因为官方没有"悬浮阴影"档位。
				 */
				cs.boxShadow = '0 12px 40px rgba(0,0,0,0.18)';
				cs.display = 'flex';
				cs.flexDirection = 'column';
				cs.pointerEvents = 'auto';
				cs.transform = 'scale(0.96)';
				cs.transition = 'transform 240ms cubic-bezier(0.22,1,0.36,1)';

				// —— 顶部：图标 + 标题 ——
				var head = document.createElement('div');
				head.style.display = 'flex';
				head.style.alignItems = 'center';
				head.style.gap = '8px';

				iconWrap = document.createElement('span');
				iconWrap.style.display = 'inline-flex';
				iconWrap.style.alignItems = 'center';
				iconWrap.style.flexShrink = '0';
				iconWrap.style.color = 'var(--dsw-alias-state-business-primary, #4A7DFF)';
				iconWrap.style.animation = 'jlmSyncSpin 1.1s linear infinite';
				head.appendChild(iconWrap);

				titleEl = document.createElement('div');
				titleEl.style.fontSize = '14px';
				titleEl.style.fontWeight = '600';
				titleEl.style.lineHeight = '20px';
				titleEl.style.color = 'var(--dsw-alias-label-primary, #0F1115)';
				head.appendChild(titleEl);
				cardEl.appendChild(head);

				// —— 副标题（阶段说明 / 计数）——
				subEl = document.createElement('div');
				subEl.style.fontSize = '12px';
				subEl.style.lineHeight = '18px';
				subEl.style.marginTop = '6px';
				subEl.style.color = 'var(--dsw-alias-label-tertiary, #8A8F98)';
				cardEl.appendChild(subEl);

				// —— 进度条 ——
				var bar = document.createElement('div');
				var bs = bar.style;
				bs.position = 'relative';
				bs.height = '4px';
				bs.borderRadius = '2px';
				bs.marginTop = '14px';
				bs.overflow = 'hidden';
				bs.background = 'var(--dsw-alias-bg-layer-2, rgba(127,127,127,0.16))';

				fill = document.createElement('div');
				var fs = fill.style;
				fs.position = 'absolute';
				fs.left = '0';
				fs.top = '0';
				fs.bottom = '0';
				fs.width = '0%';
				fs.borderRadius = '2px';
				fs.background = 'var(--dsw-alias-state-business-primary, #4A7DFF)';
				/*
				 * 宽度过渡 300ms：真实进度是"一跳一跳"上报的（每建一个会话 +1），
				 * 有过渡看起来才连贯，像在稳步走 —— 用户认可的那一点。
				 */
				fs.transition = 'width 300ms cubic-bezier(0.22,1,0.36,1)';
				bar.appendChild(fill);
				cardEl.appendChild(bar);

				// —— 计数字（只在有确定总数时显示）——
				pctEl = document.createElement('div');
				pctEl.style.fontSize = '11px';
				pctEl.style.lineHeight = '16px';
				pctEl.style.marginTop = '8px';
				pctEl.style.textAlign = 'right';
				// 等宽数字：数字跳动时列宽不变，不会左右抖。
				pctEl.style.fontVariantNumeric = 'tabular-nums';
				pctEl.style.color = 'var(--dsw-alias-label-caption, #9AA0A6)';
				cardEl.appendChild(pctEl);

				node.appendChild(cardEl);
				document.body.appendChild(node);
				return node;
			};

			/**
			 * 把官方 `IconLoadingOutlineRegular` 渲染进图标位（旋转加载圈）。
			 *
			 * 拿不到图标就退回纯 CSS 圆环 —— 保证这个位置永远有东西在转，
			 * 传达"在干活"。
			 */
			var ensureIcon = function () {
				if (!iconWrap) return;
				if (iconWrap.childNodes.length > 0) return;
				/*
				 * ## ⚠️ 用 `react-dom`（legacy render）而不是 `react-dom/server`
				 *
				 * 与 `buildBadgeNode` 同一个坑：`react-dom/server` 在这个前端模块
				 * 加载器里解析不到（官方所有客户端包一次都没 require 过它），
				 * `require` 直接抛错 ⇒ 静默退化成下面那个自制 CSS 圆环。
				 *
				 * 真机表现就是「同步时那个图标不是官方的转圈，而是一个细圆环」
				 * —— 不报错，但一眼能看出和官方不一致。
				 */
				try {
					var Icon = primitives.IconLoadingOutlineRegular;
					var legacy = require('react-dom');
					if (Icon !== null && Icon !== undefined
						&& legacy && typeof legacy.render === 'function') {
						var holder = document.createElement('div');
						legacy.render(h(Icon, { size: 15 }), holder);
						var svg = holder.querySelector('svg');
						try { legacy.unmountComponentAtNode(holder); } catch (unmountError) {}
						if (svg !== null) {
							iconWrap.appendChild(svg.cloneNode(true));
							return;
						}
					}
				} catch (error) {
					/* 退回 CSS 圆环 */
				}
				var ring = document.createElement('span');
				ring.style.width = '14px';
				ring.style.height = '14px';
				ring.style.borderRadius = '50%';
				ring.style.border = '2px solid currentColor';
				ring.style.borderTopColor = 'transparent';
				ring.style.boxSizing = 'border-box';
				iconWrap.appendChild(ring);
			};

			var render = function () {
				var st = getState();
				if (!isAlive()) { teardown(); return; }
				if (!st || !st.phase || st.phase === 'idle') { if (node) { try { node.style.opacity = '0'; } catch (hideErr) { /* ignore */ } } return; }
				var n = ensureNode();
				if (!n) return;
				ensureIcon();

				var total = st.total || 0;
				var done = st.done || 0;
				var known = total > 0;
				var pct = known ? Math.max(0, Math.min(100, Math.round((done / total) * 100))) : 0;
				var failed = st.phase === 'failed';
				var finished = st.phase === 'done';
				/*
				 * 「完成但不完整」：phase 已是 done，但宿主写了 message
				 * 说明有会话没同步成功（见宿主 1.2.52 的 failedCount）。
				 *
				 * ⚠️ 必须在这里声明，不能只在 finished 分支里 var ——
				 * 那样走别的分支时它是 undefined，虽然结果碰巧对，
				 * 但任何一处逻辑微调就会变成隐蔽 bug。
				 */
				var partial = finished && typeof st.message === 'string' && st.message !== '';

				/*
				 * ## 分阶段文案
				 *
				 * 用户明确要求：「开始真正的进度计算之前的那个等待过程，你没有做
				 * 任何的一个文字提示……那你也可以写一下，比如说正在连接电脑」。
				 *
				 * 三个阶段：
				 *   · `estimating` —— 正在问电脑"你那边有多少东西"（用户猜得很准）
				 *   · `running` + total==0 —— 已经开工，但总量还没拿到
				 *   · `running` + total>0  —— 有真实分母，按百分比走
				 */
				var title;
				var subtitle;
				if (failed) {
					title = '同步未完成';
					subtitle = st.message || '连接电脑失败，稍后可在侧边栏重试';
				} else if (finished) {
					title = '同步完成';
					/*
					 * ⚠️ 在这里挂上**预设实际拉到的条数**（真机诊断）。
					 *
					 * 用户明确要求过「把原因显示在界面里，我看一张截图就知道」。
					 * 这个数字能分开两个方向完全不同的故障：
					 *   · 拉到 0 条 ⇒ 数据没到手机，查电脑侧/隧道；
					 *   · 拉到 N 条 ⇒ 数据到手了，查前端读取/渲染。
					 *
					 * 没有诊断值时退回原来的文案，不影响正常显示。
					 */
					/*
					 * ## ⚠️⚠️ 完成但有失败 ⇒ 如实说明（1.2.52 修）
					 *
					 * 宿主现在会在「有会话没同步成功」时保留真实 done 并写入
					 * message。但这里原来**根本不读 message** —— 后端说了，
					 * 前端不显示，等于没说。用户看到的仍是「同步完成 ·
					 * N 个工作区已就绪」，完全不知道有 2 条失败了。
					 *
					 * 所以：phase 是 done 且 message 非空 ⇒ 说明这是「完成但
					 * 不完整」，标题改用「同步完成（有失败）」，副标题直接用
					 * 宿主那句话。没有 message 才是真正的全绿。
					 */
					title = partial ? '同步完成（有失败）' : '同步完成';
					subtitle = partial ? st.message : ((st.workspaces || 0) + ' 个工作区已就绪');
					var diag = syncDiagRef.value;
					/*
					 * ## 模型没刷新时也要说出来（1.2.53）
					 *
					 * 宿主拿不到 `modelDirectories` 服务时只 return false，
					 * 界面上却仍显示「同步完成 · N 个工作区已就绪」。
					 * 用户接着打开模型菜单看到旧数据，会以为是同步没生效 ——
					 * 实际是官方服务没接上，两件事完全不同。
					 *
					 * 只在**明确 false** 时提示；null（未尝试）和 true 都不打扰。
					 */
					/*
					 * ## 把「为什么没刷新」直接写在界面上（1.2.63）
					 *
					 * 用户明确要求过：「把错误原因显示在 App 界面里，
					 * 我看一张截图就知道」。一张截图不花钱，
					 * 而反复让用户装包试错很费钱也很费时间。
					 *
					 * “未刷新”三个字不够：服务没注册 / 没 catalog /
					 * 没 refresh 这三种情况修法完全不同。有原因就括起来一起显示。
					 * 原因很长时截断：副标题只有一行，被截断反而更难读。
					 */
					if (!partial && st.modelRefresh === false) {
						var mrReason = (typeof st.modelRefreshReason === 'string') ? st.modelRefreshReason : '';
						/*
						 * ⚠️ 阈值取 12 而不是 24（1.2.63：中文字符宽）
						 *
						 * 副标题是一行小字，能容纳的中文大约 20–24 个。
						 * 而「模型未刷新：」本身就占 7 个字，中间那个间隔符又占 1 个。
						 *
						 * 若原因也留 24 个，总长会到 32+ ⇒ 整条被 CSS 截断
						 * （尾部丢失，看不到结论），比只显示「未刷新」还难读。
						 * 所以在源头就截到 12 个字并加省略号 —— 保证整条
						 * 能完整显示，用户看得到尾。
						 */
						if (mrReason.length > 12) mrReason = mrReason.slice(0, 12) + '…';
						subtitle += mrReason !== ''
							? (' · 模型未刷新：' + mrReason)
							: ' · 模型列表未刷新';
					}
					if (!partial && diag !== null && typeof diag === 'object') {
						if (typeof diag.presets === 'number' && diag.presets >= 0) {
							subtitle += ' · 预设 ' + diag.presets + ' 条';
						} else if (typeof diag.reason === 'string' && diag.reason !== '') {
							subtitle += ' · 预设未取到（' + diag.reason + '）';
						}
					}
				} else if (st.phase === 'estimating' || (!known && st.phase !== 'running')) {
					title = '正在连接电脑';
					subtitle = '正在获取电脑上的工作区与对话数量…';
				} else if (!known) {
					title = '正在同步电脑上的对话';
					subtitle = '正在统计总量，马上开始…';
				} else {
					title = '正在同步电脑上的对话';
					subtitle = done + ' / ' + total;
				}

				if (titleEl) titleEl.textContent = title;
				if (subEl) subEl.textContent = subtitle;

				/*
				 * ## 进度条的三种形态
				 *
				 *   ① 未知总数 ⇒ **不确定态**：一条短亮块来回流动，表示"在进行、
				 *      总量未知"。这是用户最在意的一点 —— 上一版静态卡在 38%
				 *      让他以为死机了。
				 *   ② 已知总数 ⇒ 确定态按百分比（用户认可的"稳步前进"）。
				 *   ③ 失败 ⇒ 变红并停在当前值（不回退，保留现场）。
				 */
				if (fill) {
					if (failed) {
						fill.style.width = (known ? pct : 100) + '%';
						fill.style.background = 'var(--dsw-alias-state-error-primary, #D9534F)';
						fill.style.animation = 'none';
						fill.style.opacity = '1';
					} else if (known) {
						fill.style.animation = 'none';
						fill.style.opacity = '1';
						/*
						 * 有失败时**不能**画成 100% —— 那是在撒谎。
						 * 保留真实比例，并换成警告色，让「没全成」这件事一眼可见。
						 */
						fill.style.width = ((finished && !partial) ? 100 : pct) + '%';
						fill.style.background = (finished && partial)
							? 'var(--dsw-alias-state-warning-primary, #E8A33D)'
							: 'var(--dsw-alias-state-business-primary, #4A7DFF)';
					} else {
						/*
						 * 不确定态：宽度固定 35%，靠 transform 来回扫。
						 * 用 transform 而不是改 width —— 前者走合成器不触发重排，
						 * 每秒 60 帧也不卡。
						 */
						fill.style.width = '35%';
						fill.style.background = 'var(--dsw-alias-state-business-primary, #4A7DFF)';
						fill.style.animation = 'jlmSyncIndeterminate 1.35s ease-in-out infinite';
					}
				}

				if (pctEl) {
					pctEl.textContent = failed ? '' : (known ? (done + ' / ' + total + '  ·  ' + pct + '%') : '');
				}

				/*
				 * 全绿才淡出。有失败时留在屏幕上 —— 否则 900ms 就消失，
				 * 用户根本来不及看清哪条失败了，那等于没说。
				 */
				var holdOn = (finished && partial);
				n.style.opacity = (finished && !partial) ? '0' : '1';
				if (cardEl) cardEl.style.transform = 'scale(1)';

				if (removeTimer !== null) { clearTimeout(removeTimer); removeTimer = null; }
				if (finished || failed) {
					removeTimer = setTimeout(function () {
						removeTimer = null;
						teardown();
					}, failed ? 6000 : (holdOn ? 8000 : 900));
				}
			};

			var teardown = function () {
				if (removeTimer !== null) { clearTimeout(removeTimer); removeTimer = null; }
				/*
				 * ⭐ 两种句柄都必须清（1.2.61）。
				 *
				 * 漏清 rafId 的后果：浮层已经从页面上摸掉了，
				 * 但帧回调还在下一帧跑 —— 每帧都去 getState()、
				 * 读一个已经没人看的状态，白耗 CPU 和电。
				 * 漏清 intervalId 同理（且它不受页面可见性影响，更容易漏）。
				 */
				if (rafId !== null) {
					try {
						if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(rafId);
					} catch (cancelError) { /* 取消失败不影响主流程 */ }
					rafId = null;
				}
				if (intervalId !== null) {
					try {
						if (typeof clearInterval === 'function') clearInterval(intervalId);
					} catch (clearError) { /* 同上 */ }
					intervalId = null;
				}
				if (node) {
					try { if (node.parentNode) node.parentNode.removeChild(node); } catch (ignored) { /* 已被移除 */ }
				}
				node = null;
				cardEl = null;
				fill = null;
				titleEl = null;
				subEl = null;
				pctEl = null;
				iconWrap = null;
			};

			var lastPhase = '';
			var lastDone = -1;
			var lastTotal = -1;
			/*
			 * ## ⚠️⚠️ 刷新循环必须双通道 + 整体兜底（1.2.61 修）
			 *
			 * 原来直接调 `requestAnimationFrame(loop)`，两个问题：
			 *
			 * ① **`requestAnimationFrame` 未判存**。它不是语言内建，
			 *    而是渲染引擎提供的。某些 WebView / 页面处于非活跃
			 *    状态时它不存在 ⇒ 这里抛 ReferenceError。
			 *
			 * ② **它在帧回调里抛，外面的 guard 接不住**。
			 *    `guard('mountSyncProgress', ...)` 只能捉到**同步执行时**
			 *    的异常。而 loop 是下一帧才跑的 —— 抛在那里
			 *    就是 unhandled error，且**浮层永远画不出来**。
			 *
			 * 这正是「进度浮层不出现」的一种成因，而且比渲染错难查得多。
			 *
			 * 所以：有 rAF 就用 rAF（流畅、自带节流），
			 * 没有就退到 setInterval（功能等价，只是流畅度差一点）。
			 * 两条通道都要：整体包 try、每帧也包 try、
			 * dispose 时把两种句柄都清掉。
			 */
			var tickOnce = function () {
				if (!isAlive()) { teardown(); return; }
				var st = getState();
				var phase = (st && st.phase) || 'idle';
				var done = (st && st.done) || 0;
				var total = (st && st.total) || 0;
				if (phase !== lastPhase || done !== lastDone || total !== lastTotal) {
					lastPhase = phase;
					lastDone = done;
					lastTotal = total;
					render();
				}
			};
			/*
			 * 帧回调：任何异常都必须吞掉，否则浮层不更新且每帧报错。
			 * 注意重新注册必须在 try 之外：若 render() 抛错，
			 * 循环也不能断 —— 否则一次渲染失败就执行不到下一帧了。
			 */
			var frame = function () {
				try {
					tickOnce();
				} catch (renderError) {
					try { window.__dshRemoteProgressError = String(renderError); } catch (probeError) { /* 诊断失败不影响主流程 */ }
				}
					/*
					 * 已经 teardown（浮层拆了）就不再续帧。
					 * 否则 dispose 之后还会一直空跑，白耗 CPU。
					 */
				/*
				 * ## ⚠️⚠️ 不能把 `node === null` 当退出条件（1.2.84 修）
				 *
				 * 本函数在插件 `apply()` 时就 mount 并立刻 `schedule()`，
				 * 而那一刻还未配对 ⇒ `render()` 判 idle ⇒ teardown ⇒ `node = null`
				 * ⇒ 旧写法这里直接 `return` ⇒ **循环在第一帧就死了**。
				 *
				 * 于是配对成功、`runAutoSync` 把 phase 改成 `estimating` 之后，
				 * 没有任何循环在跑 ⇒ 进度动画永远不出现。
				 * 它不是「后来消失了」，而是「从来没出现过」。
				 *
				 * 现在只在**真正卸载**时停。空循环每帧只比 3 个字段。
				 */
				if (!isAlive()) return;
				schedule();
			};
			var schedule = function () {
				try {
					if (typeof requestAnimationFrame === 'function') {
						rafId = requestAnimationFrame(frame);
						return;
					}
				} catch (rafError) {
					/* rAF 存在但调用失败 ⇒ 退到定时器 */
				}
				try {
					if (typeof setInterval === 'function' && intervalId === null) {
						intervalId = setInterval(frame, 120);
					}
				} catch (timerError) {
					/* 连定时器都没有 ⇒ 只能静默放弃，但绝不能抛 */
				}
			};
			schedule();

			return { dispose: teardown };
		}

		/**
		 * 把电脑上某个目录的历史对话**按需同步**到手机侧边栏。
		 *
		 * ## 用户需求原文
		 *
		 * 「在工作区行右侧『⋯』菜单加一项『同步对话』→ 点开弹出列表，显示电脑上
		 * 该文件夹的历史对话**只要名称** → 勾选列表，用户只勾要的（不是全同步）
		 * → 同步后出现在手机侧边栏该工作区下，**样式跟原生会话一样**」。
		 *
		 * ## 为什么「样式跟原生会话一样」是自然达成的
		 *
		 * 同步**真的**调用 `sessionController.create` 在手机本地建出会话，
		 * 并 `attachSession` 挂到该工作区的镜像目录下 —— 所以侧边栏里那一行
		 * 就是官方渲染的原生会话行，不是任何仿制品。这正是用户要的效果，
		 * 也是唯一能骗过官方侧边栏的办法（它只渲染 `workspace.sessionIds`）。
		 *
		 * ## 历史为什么不跟着搬
		 *
		 * 手机本地 session 的 seq 从 0 连续自增，装不下「电脑上第 8000 条开始的
		 * 最近 10 条」。所以历史走**按需转发**（见宿主 `controller.page` 拦截器
		 * 与 desktop 的 `readHistory`）：打开时只拉 10 条，上滑再拉。
		 * 实测最大会话全量 155 MB vs 最近 10 条 131 KB。
		 *
		 * @param props - `{ open, desktopPath, onClose, onSynced }`。
		 */
		function SyncConversationsDialog(props) {
			var open = props.open === true;
			var desktopPath = props.desktopPath || '';
			var onClose = props.onClose;
			var onSynced = props.onSynced;

			var loadedState = react.useState(false);
			var loaded = loadedState[0];
			var setLoaded = loadedState[1];
			var itemsState = react.useState([]);
			var items = itemsState[0];
			var setItems = itemsState[1];
			var pickedState = react.useState({});
			var picked = pickedState[0];
			var setPicked = pickedState[1];
			var busyState = react.useState(false);
			var busy = busyState[0];
			var setBusy = busyState[1];
			var errorState = react.useState(null);
			var error = errorState[0];
			var setError = errorState[1];
			/**
			 * 搜索关键字。
			 *
			 * ## 为什么必须有（真机实测出来的必要功能，不是锦上添花）
			 *
			 * 用户电脑上那个 DSHAPP 目录有 **76 个对话**，全部平铺在一个滚动列表里。
			 * 想同步其中某一个，只能一路往下翻着找 —— 而列表没有排序提示、
			 * 没有搜索、也没有全选，实际用起来基本不可用。
			 *
			 * 空字符串 = 不过滤（默认全显示，保持原有行为）。
			 */
			var queryState = react.useState('');
			var query = queryState[0];
			var setQuery = queryState[1];

			// 每次打开（或换了目录）就重拉一次列表 —— 电脑上可能刚聊过新的。
			react.useEffect(
				function () {
					if (!open || desktopPath === '') return undefined;
					var alive = true;
					setLoaded(false);
					setError(null);
					setPicked({});
					bridge('listDesktopConversations', { path: desktopPath }).then(
						function (result) {
							if (!alive) return;
							var list = (result && result.sessions) || [];
							setItems(list);
							setLoaded(true);
						},
						function (reason) {
							if (!alive) return;
							setItems([]);
							setLoaded(true);
							setError(String((reason && reason.message) || reason));
						},
					);
					return function () {
						alive = false;
					};
				},
				[open, desktopPath],
			);

			var toggle = function (sessionId) {
				setPicked(function (current) {
					var next = {};
					for (var key in current) {
						if (Object.prototype.hasOwnProperty.call(current, key)) next[key] = current[key];
					}
					if (next[sessionId] === true) delete next[sessionId];
					else next[sessionId] = true;
					return next;
				});
			};

			var count = 0;
			for (var key in picked) {
				if (Object.prototype.hasOwnProperty.call(picked, key) && picked[key] === true) count += 1;
			}

			var submit = function () {
				if (busy || count === 0) return;
				// 先把 `sessionId → 标题` 建成一张表：`picked` 里只存了布尔值，
				// 而标题必须一起带过去（见下面 `chosen.push` 的注释）。
				var titleOf = {};
				for (var n = 0; n < items.length; n += 1) {
					var entry = items[n];
					if (entry === null || entry === undefined) continue;
					if (typeof entry.sessionId === 'string' && typeof entry.title === 'string') {
						titleOf[entry.sessionId] = entry.title;
					}
				}
				var chosen = [];
				for (var sessionId in picked) {
					if (Object.prototype.hasOwnProperty.call(picked, sessionId) && picked[sessionId] === true) {
						// ⚠️ **必须把标题一起带上**（`title`）。
						//
						// 手机端同步出来的是**空壳会话**（历史按需从电脑拉，见宿主
						// `syncConversations` 的注释），本地日志里没有 `session/title`
						// 事件，而标题投影是纯日志重放出来的
						//（`dsh-session-title/lib/index.js:177`
						// `apply: (state, event) => event.type === "session/title" ? event.data.title : state`）
						// ⇒ 投影是 `null` ⇒ 侧边栏回退成 i18n 的「未命名」。
						//
						// 宿主拿到这个 `title` 会补写一条 `session/title`，侧边栏
						// 就显示电脑上那个对话的真实名字了（用户要求）。
						var desktopTitle = titleOf[sessionId];
						chosen.push(
							typeof desktopTitle === 'string' && desktopTitle !== ''
								? { sessionId: sessionId, title: desktopTitle }
								: { sessionId: sessionId },
						);
					}
				}
				setBusy(true);
				setError(null);
				bridge('syncConversations', { path: desktopPath, items: chosen }).then(
					function (result) {
						setBusy(false);
						// ## ⚠️ 部分失败必须让用户看见（用户 2026-10-22 报障）
						//
						// 原来这里不看结果、直接关对话框。而逐条失败的原因在宿主侧
						// 只打了 `ctx.logger.warn` —— **真机上进不了 app.log** ——
						// 于是用户看到的是「点了同步 → 没反应、没报错、列表还是空的」，
						// 完全无法定位。
						//
						// 现在宿主把原因放进 `result.failures`，这里非空就**不关**
						// 对话框、把原因写在错误行里，让用户/作者都能看到到底哪一条、
						// 为什么失败。
						var failures = result && Array.isArray(result.failures) ? result.failures : [];
						if (failures.length > 0) {
							var firstReason = String(failures[0].error || '未知原因');
							setError(
								failures.length + ' 个对话同步失败：' + firstReason +
									(failures.length > 1 ? '（等 ' + failures.length + ' 条）' : ''),
							);
							// 成功的那几条已经落盘了，照样通知外层刷新列表。
							if (onSynced !== undefined) onSynced(result);
							return;
						}
						if (onSynced !== undefined) onSynced(result);
						if (onClose !== undefined) onClose();
					},
					function (reason) {
						setBusy(false);
						setError(String((reason && reason.message) || reason));
					},
				);
			};

			// 按关键字过滤（见 `queryState` 的注释）。
			//
			// ⚠️ 大小写不敏感：对话标题有中文也有英文（真机上就有
			// 「Greeting in Chinese」「ear逆向工具地址查询」混在一起），
			// 用户不会记得当初输入的是大写还是小写。
			var needle = query.trim().toLowerCase();
			var shown = needle === ''
				? items
				: items.filter(function (entry) {
						if (entry === null || entry === undefined) return false;
						var text = typeof entry.title === 'string' ? entry.title.toLowerCase() : '';
						return text.indexOf(needle) >= 0;
					});

			var rows = [];
			if (!loaded) {
				rows.push(h('div', { key: 'loading', style: CSS.syncEmpty }, '正在读取电脑上的对话…'));
			} else if (error !== null) {
				rows.push(h('div', { key: 'error', style: CSS.error }, error));
			} else if (items.length === 0) {
				rows.push(h('div', { key: 'empty', style: CSS.syncEmpty }, '这个文件夹在电脑上还没有对话'));
			} else if (shown.length === 0) {
				rows.push(h('div', { key: 'nomatch', style: CSS.syncEmpty }, '没有匹配「' + query.trim() + '」的对话'));
			} else {
				for (var index = 0; index < shown.length; index += 1) {
					var item = shown[index];
					rows.push(
						h(
							'label',
							{
								key: item.sessionId,
								style: CSS.syncRow,
								// ⚠️ 用 `htmlFor`+`id` 让 label 驱动复选框，而不是自己
								// 在 label 上也挂 onClick —— 两者都挂会让一次点击 toggle 两次
								// 而互相抵消（表现为「勾不上」）。
								htmlFor: 'jlmsync-' + item.sessionId,
							},
							h('input', {
								id: 'jlmsync-' + item.sessionId,
								type: 'checkbox',
								style: CSS.syncBox,
								checked: picked[item.sessionId] === true,
								onChange: (function (id) {
									return function () {
										toggle(id);
									};
								})(item.sessionId),
							}),
							h('span', { style: CSS.syncName, title: item.title }, item.title),
						),
					);
				}
			}

			// 搜索框 + 全选/清空。
			//
			// ⚠️ 只对**当前显示出来的**那一批生效（`shown`），不是对全部 `items`：
			// 用户搜「问候」之后点全选，期望的是选中搜出来的那些，
			// 而不是把 76 个对话全勾上 —— 那会一次性建出几十个空壳会话。
			var shownIds = shown.map(function (entry) { return entry.sessionId; });
			var shownPicked = 0;
			for (var sp = 0; sp < shownIds.length; sp += 1) {
				if (picked[shownIds[sp]] === true) shownPicked += 1;
			}
			var allShownPicked = shownIds.length > 0 && shownPicked === shownIds.length;
			var pickAllShown = function () {
				setPicked(function (current) {
					var next = {};
					for (var key in current) {
						if (Object.prototype.hasOwnProperty.call(current, key) && current[key] === true) next[key] = true;
					}
					if (allShownPicked) {
						// 已经全选 ⇒ 反选成「都不选」（只清当前显示的这一批）。
						for (var i2 = 0; i2 < shownIds.length; i2 += 1) delete next[shownIds[i2]];
					} else {
						for (var i3 = 0; i3 < shownIds.length; i3 += 1) next[shownIds[i3]] = true;
					}
					return next;
				});
			};
			var toolbar = items.length === 0
				? null
				: h(
						'div',
						{ style: CSS.syncToolbar },
						h('input', {
							type: 'search',
							style: CSS.syncSearch,
							placeholder: '搜索对话名称…',
							value: query,
							onChange: function (event) {
								setQuery(String(event.target.value || ''));
							},
						}),
						h(
							'button',
							{
								type: 'button',
								style: CSS.syncPickAll,
								onClick: pickAllShown,
							},
							allShownPicked ? '取消全选' : '全选',
						),
					);

			var body = h(
				'div',
				{ style: CSS.editorScope },
				h(
					'div',
					{ style: CSS.header },
					h('h2', { style: CSS.title }, '同步对话'),
					h('div', { style: CSS.crumbBar },
						h('span', { style: CSS.pathText, title: desktopPath }, desktopPath || '（未选择电脑目录）'),
					),
				),
				h(
					'div',
					{ style: CSS.content },
					// 搜索 + 全选放在**列表上方**（列表可能有 70+ 项，
					// 放下面用户得先滑到底才看得见）。
					toolbar,
					h('div', { style: CSS.column }, rows),
				),
				h(
					'div',
					{ style: CSS.footerBar },
					h('span', { style: CSS.note }, count === 0 ? '勾选要同步的对话' : '已选 ' + count + ' 个'),
					h('span', { style: CSS.footerGap }),
					h(
						Button,
						{
							variant: 'secondary',
							style: CSS.footerAction,
							disabled: busy,
							onClick: onClose,
						},
						'取消',
					),
					h(
						Button,
						{
							variant: 'primary',
							style: CSS.footerAction,
							disabled: busy || count === 0,
							onClick: submit,
						},
						busy ? '同步中…' : '同步',
					),
				),
			);

			return h(
				Modal,
				{
					open: open,
					onClose: onClose,
					title: '同步对话',
					closeLabel: '关闭',
					className: DIALOG_CLASS,
					headless: true,
				},
				body,
			);
		}

		/**
		 * 「同步对话」的**挂载器**：把菜单项接进工作区行的「⋯」菜单。
		 *
		 * ## 为什么必须走 DOM（这是本项目第二个 DOM 注入点）
		 *
		 * 官方工作区行的菜单项是**写死的两条**
		 *（`dsh-client-ui-workspace/lib/client.js:732-741`：`rename` / `delete`），
		 * 既没有插槽、也没有 `inject` 扩展点；`sidebar.workspaces` 这个 slot 还是
		 * `kind: "single"`，整块只能有一个 occupant（官方自己的 `WorkspaceBrowser`）。
		 *
		 * 所以只能等官方把菜单渲染出来之后，往那个浮层里**追加一行**。
		 * 判据是菜单里的文案（「重命名」/「删除工作区」）—— 官方菜单是
		 * portal 到 body 的浮层，没有稳定的类名可用。
		 *
		 * ⚠️ 只追加、不改动官方那两条，也不拦截它们的点击：官方自己的逻辑
		 * 完全不知道我们的存在，所以不可能被我们弄坏。
		 */
		function installSyncMenu(ctx) {
			/*
			 * ## ⚠️⚠️ 日志出口（1.2.55 新增）
			 *
			 * 原来这三处 catch 里写的是 `ctx.logger?.warn?.(...)`。两个问题：
			 *
			 * ① **可选链 `?.` 是 chrome80+ 语法**，而本文件受 chrome73 约束。
			 *    旧 WebView 解析到这里会直接抛 SyntaxError —— **整个文件不执行**，
			 *    侧边栏图标、进度浮层、所有装饰一起消失。用户看到的是
			 *    「插件像是没装」，而不是「某个功能坏了」。
			 *
			 * ② `ctx` 允许为 undefined（见文件头对该参数的说明）。
			 *    即便语法能过，`undefined?.logger` 之后 `warn` 调用仍是问题。
			 *    所以必须**先判类型再调**，等价于可选链、但兼容更老的引擎。
			 *
			 * 这个函数本身也包了 try：日志打不出来绝不能再炸一次，
			 * 否则 catch 里抛错会盖掉真正要报的错。
			 */
			/*
			 * ⚠️ `logWarn` 已提到**模块级**（1.2.70 修）。
			 *
			 * 原来它就定义在这里（`installSyncMenu` 内部），但下面三个
			 * **模块级**函数也在调它：
			 *
			 *     showPromptFailure（行 8396）  → logWarn
			 *     showAskCard（行 8465）        → logWarn
			 *     异常回传那段（行 8574）     → logWarn
			 *
			 * 而 `installSyncMenu` 的范围是 7444~8320 —— 上面三处**全在范围外**。
			 * 于是这三个 catch 里一定抛 `ReferenceError: logWarn is not defined`，
			 * **把真正要报的错误盖掉**。
			 *
			 * 这和 `invalidateDesktopCache is not defined`、`mergePresets`、
			 * `refreshOfficialModelCatalog` 是**同一类**销误：模块级函数看不见
			 * 某个闭包里的局部变量。只是前三个被真机撞到了，
			 * 这三个还没被撞到 —— 因为它们只在 catch 里跑。
			 *
			 * 定义见模块开头的 `var logWarn = function (message) {`。
			 */
			/** 当前挂着的那个「同步对话」按钮（避免重复插入）。 */
			var mounted = null;
			/** 当前对话框状态。 */
			var dialog = { open: false, desktopPath: '' };
			/**
			 * 最近一次点开「⋯」的那个工作区行的标题。
			 *
			 * ⚠️ **必须这样记，不能从菜单 DOM 往上找行**：官方菜单是
			 * `portal: true` 渲染到 body 下的独立浮层（
			 * `dsh-client-ui-workspace/lib/client.js:785`），它在 DOM 上
			 * **不是**工作区行的后代 —— 往上找永远找不到那一行。
			 */
			var lastMenuRowTitle = '';
			/**
			 * 最近一次点开「⋯」的那个工作区行的**路径**（唯一标识）。
			 *
			 * 比标题可靠：`desktopTitle()` 取末段，两个同名目录会撞。
			 */
			var lastMenuRowPath = '';
			/**
			 * 上一次被我们藏起来的那个官方菜单浮层节点。
			 *
			 * 点「同步对话」后为了收起菜单，给浮层写了内联
			 * `visibility: hidden; pointer-events: none`。而 React 会**复用**这个
			 * portal 节点 ⇒ 不还原的话，下次打开的菜单就是隐藏且点不中的。
			 *
			 * 真机症状（用户 2026-10-22 报障）：「⋯ 点了没反应」，
			 * 必须重启 App 才恢复。所以在下一次 pointerdown 时清掉（见
			 * `restoreHiddenMenu`）。
			 */
			var hiddenMenuRoot = null;

			/** 还原被藏起来的菜单浮层（清内联样式，节点本身不动）。 */
			var restoreHiddenMenu = function () {
				if (hiddenMenuRoot === null) return;
				try {
					hiddenMenuRoot.style.visibility = '';
					hiddenMenuRoot.style.pointerEvents = '';
				} catch {
					/* 节点可能已被 React 移除 —— 无所谓 */
				}
				hiddenMenuRoot = null;
			};

			var root = null;
			var host = null;

			/** 重渲染对话框（用一个独立的 React root，与官方树完全隔离）。 */
			var renderDialog = function () {
				if (host === null) return;
				if (root === null) {
					// ⚠️ 官方前端只用 `require("react-dom")`（见
					// `dsh-client-ui-chat/lib/client.js:11`），**没有任何包**用
					// `react-dom/client` —— 说明模块加载器只保证前者可解析。
					// 所以先试 `react-dom/client`（React 18 的正式入口，能消除告警），
					// 拿不到就退回 `react-dom` 的 legacy `render`；两个都拿不到就放弃
					// 挂载（菜单项仍然不会破坏官方界面）。
					var legacy = null;
					var legacyError = '';
					try {
						legacy = require('react-dom');
					} catch (domError) {
						legacy = null;
						legacyError = String((domError && domError.message) || domError);
					}
					var clientModule = null;
					var clientErrorText = '';
					try {
						clientModule = require('react-dom/client');
					} catch (clientError) {
						clientModule = null;
						clientErrorText = String((clientError && clientError.message) || clientError);
					}
					// 诊断：把模块解析结果暴露出来（排查「对话框打开了却是空的」）。
					try {
						window.__dshSyncRender = {
							at: Date.now(),
							legacyType: legacy === null ? 'null' : typeof legacy,
							legacyRender: legacy !== null && typeof legacy.render === 'function',
							clientType: clientModule === null ? 'null' : typeof clientModule,
							clientCreateRoot: clientModule !== null && typeof clientModule.createRoot === 'function',
							legacyError: legacyError,
							clientError: clientErrorText,
						};
					} catch (probeError3) {
						/* 诊断失败不影响主流程 */
					}
					if (clientModule !== null && typeof clientModule.createRoot === 'function') {
						root = clientModule.createRoot(host);
					} else if (legacy !== null && typeof legacy.render === 'function') {
						root = {
							render: function (element) {
								legacy.render(element, host);
							},
							unmount: function () {
								if (typeof legacy.unmountComponentAtNode === 'function') legacy.unmountComponentAtNode(host);
							},
						};
					} else {
						return;
					}
				}
				var element = h(SyncConversationsDialog, {
					open: dialog.open,
					desktopPath: dialog.desktopPath,
					onClose: function () {
						dialog = { open: false, desktopPath: dialog.desktopPath };
						renderDialog();
					},
					onSynced: function () {
						// 同步完刷新侧边栏：官方会话列表是 store 驱动的，
						// 主动 refresh 一次就能把新会话画出来。
						try {
							if (ctx.sessions !== undefined && typeof ctx.sessions.refresh === 'function') {
								ctx.sessions.refresh();
							}
						} catch (refreshError) {
							/* 刷新失败不影响已建的会话 */
						}
					},
				});
				if (typeof root.render === 'function') root.render(element);
			};

			/** 确保对话框容器存在（挂在 body 下，和官方 portal 同级）。 */
			var ensureHost = function () {
				if (host !== null && document.body.contains(host)) return;
				host = document.createElement('div');
				host.setAttribute('data-dsh-remote-sync', '1');
				document.body.appendChild(host);
				root = null;
			};

			/**
			 * 打开同步对话框（需要先知道这个工作区对应电脑上哪个目录）。
			 *
			 * @param desktopPath - 电脑上的绝对目录。
			 */
			var openSync = function (desktopPath) {
				ensureHost();
				dialog = { open: true, desktopPath: desktopPath };
				renderDialog();
			};

			/**
			 * 找出某个工作区行对应的电脑路径。
			 *
			 * 从菜单按钮往上找那个 `role="treeitem"` 的行，读它的标题文字，
			 * 再和宿主给的镜像工作区清单比对。这样不需要任何官方内部状态。
			 */
			var desktopPathOfMenu = function (menuNode) {
				// ⚠️ 官方菜单是 portal 出去的，**不在**工作区行的 DOM 子树里，
				// 所以不能从菜单节点往上找行。改用「记录打开菜单时的那一行」：
				// 菜单出现的那一刻，鼠标/手指点在哪个 `role="treeitem"` 上。
				//
				// `lastMenuRowPath` 里装的是 `decorate()` 打到行上的
				// `data-dsh-workspace-desktop`，也就是**电脑路径本身** ——
				// 拿到就能直接用，不需要再查表，也就不存在「同名目录串台」。
				if (lastMenuRowPath !== '') return lastMenuRowPath;
				// 兜底：行上没打到属性（比如 `decorate()` 那一刻清单还没回来）时，
				// 按标题去镜像清单里找。标题取路径末段，同名概率低，作为兜底够用。
				if (lastMenuRowTitle === '') return null;
				/*
				 * ⚠️ 两侧都要先剥掉零宽标记（`stripDesktopMark`）。
				 *
				 * `lastMenuRowTitle` 来自 `node.textContent.trim()` —— 而 `trim()` 会留着
				 * `\u200B`（零宽不算空白），宿主下发的 `title` 也带它。
				 * 两边都带、看似能对上，但只要哪一侧经过了别的地方的 `trim` 或
				 * 字符串比较，就会失配。统一剥掉最稳。
				 */
				var rowTitlePlain = stripDesktopMark(lastMenuRowTitle);
				var list = mirrorWorkspacesRef.value;
				for (var index = 0; index < list.length; index += 1) {
					var title = stripDesktopMark(list[index].title);
					if (title !== '' && rowTitlePlain.indexOf(title) === 0) return list[index].desktopPath;
				}
				/*
				 * ## 默认工作区的兜底（用户长期留存的问题，2026-10-29 修）
				 *
				 * 用户原话：「之前用户手动选择工作区的时候，如果那个工作区是电脑的
				 * DSH 默认工作区的话，就无法手动点击同步对话，点击同步对话没有任何
				 * 反应」。
				 *
				 * 前两条判据在这里都会失败：
				 *   · `lastMenuRowPath` 为空 —— `decorate()` 没能给这一行打上标记
				 *     （原因见下）；
				 *   · 标题前缀匹配失败 —— 电脑默认工作区的镜像标题取自**路径末段**
				 *     （如 `.dsh`、`X`），而官方可能把它渲染成本地化名字，
				 *     或者末段太短（<3 字符）被 `rowMatchesMirror` 里那条
				 *     `title.length < 3` 的保护挡掉。
				 *
				 * 最后这一条兜底：**清单里只有一条镜像工作区时，就是它**。
				 * 单条时不可能"串台"，而这个功能本身就是"给这个工作区同步对话"，
				 * 比什么都不发生好得多（用户要的是"点了有反应"）。
				 */
				if (list.length === 1) {
					var onlyPath = list[0] && list[0].desktopPath;
					if (typeof onlyPath === 'string' && onlyPath !== '') return onlyPath;
				}
				return null;
			};

			/**
			 * 立刻重拉镜像工作区清单，并把标记补打到指定的那一行上。
			 *
			 * ## 为什么需要这个「点菜单时临时补一次」
			 *
			 * `refreshMirrorWorkspaces()` 只在 App 启动 / `domain/changed` /
			 * 10 分钟兜底时跑。而**刚配对 + 刚选完目录**这一刻，宿主那边已经有镜像
			 * 工作区了，前端缓存却还是空的（宿主从不发 `domain/changed`，见
			 * `onPointerDown` 里的详细注释）。
			 *
			 * 于是第一次点「⋯ → 同步对话」时 `data-dsh-workspace-desktop` 还没打上，
			 * 对话框**直接消失**，必须重启 App 才好 —— 这正是用户 2026-10-22 报的
			 * 「配对成功后点同步对话没反应，退出重启之后回来就可以点了」。
			 *
			 * @param row - 刚被点开的那个 `role="treeitem"` 元素（可能已经不在 DOM 里）。
			 */
			/**
			 * 判断某个侧边栏行的文本是否对应这条镜像工作区。
			 *
			 * ## 为什么不只是 `rowText.indexOf(title) === 0`
			 *
			 * 官方 UI 会对**内置工作区**做本地化显示：registry 里 id 是
			 * `default-workspace`，界面上写的是「默认工作区」（英文环境是
			 * `Default workspace`）。而本插件下发的 `title` 取自**电脑路径末段**
			 * （`desktopTitle()`），对默认工作区就是 `DeepSeek` 这类目录名 ——
			 * 两个字符串毫无交集，`indexOf` 必然失败。
			 *
			 * 失败的后果不是"显示不出来"，而是：这一行**打不上**
			 * `data-dsh-workspace-desktop` ⇒ 用户点「⋯ → 同步对话」时前端
			 * 拿不到电脑路径 ⇒ 菜单直接消失、什么也不发生。也就是用户报的
			 * 「在默认工作区点同步对话没反应」。
			 *
			 * ## 判据（按可靠性排序）
			 *
			 * ① 精确相等 —— 最稳，不受后续追加的角标/计数影响；
			 * ② 本地化别名 —— 已知的内置工作区显示名；
			 * ③ 前缀匹配 —— 行文本以标题开头（官方会在标题后追加徽标文本）。
			 *
			 * ⚠️ ③ 必须**只在标题足够长时**才用：短标题（如 `a`、`docs`）
			 * 会命中一堆无关行，反而把别的行标错。
			 *
			 * @param rowText - 行文本（已 trim）。
			 * @param title - 插件下发的镜像工作区标题。
			 * @param slug - 工作区 slug（用于识别内置工作区）。
			 * @returns 是否匹配。
			 */
			/**
			 * 剥掉标题里的零宽标记，只留用户能看见的文字。
			 *
			 * 宿主给镜像工作区标题加了 `\u200B` 前缀（用来识别"这是电脑工作区"），
			 * 而官方 UI 渲染时它是**不可见**的。凡是要拿行文本跟标题比对的地方，
			 * 两边都得先剥掉它，否则 `'\u200Bdsh' !== 'dsh'` ⇒ 匹配失败
			 * ⇒「同步对话」菜单认不出这一行。
			 */
			var stripDesktopMark = function (text) {
				return String(text === undefined || text === null ? '' : text).split(TITLE_PREFIX).join('');
			};

			var rowMatchesMirror = function (rowText, title, slug) {
				if (rowText === '' || typeof title !== 'string' || title === '') return false;
				// 两边统一剥掉零宽标记后再比（见 `stripDesktopMark`）。
				rowText = stripDesktopMark(rowText);
				title = stripDesktopMark(title);
				if (rowText === '') return false;
				// ① 精确相等
				if (rowText === title) return true;
				// ② 本地化别名：官方内置工作区按语言显示，与路径末段对不上。
				var ALIAS = {
					'default-workspace': ['默认工作区', 'Default workspace', 'Default Workspace'],
				};
				var aliases = ALIAS[slug];
				if (aliases !== undefined) {
					for (var a = 0; a < aliases.length; a += 1) {
						if (rowText === aliases[a]) return true;
					}
				}
				// ③ 前缀匹配：只在标题够长时启用（短标题会误伤）
				if (title.length < 3) {
					/*
					 * 短标题（如 `X`、`.dsh`）不能用「前缀匹配」—— 会误伤一堆无关行。
					 *
					 * 但也不能就此放弃：电脑默认工作区的路径末段常常就是这么短
					 * （`C:\Users\X`、`~/.dsh`），而用户报的「默认工作区点同步对话
					 * 没反应」正是这一类。
					 *
					 * 折中：改成**整行等于**标题才算命中（不是前缀）。短标题下这个
					 * 判据足够严 —— 一行文字恰好只有 `X` 或 `.dsh`，几乎只能是它。
					 * 仍然误伤的话，还有 `desktopPathOfMenu` 里「清单只有一条」那道兜底。
					 */
					return rowText === title;
				}
				return rowText.indexOf(title) === 0;
			};

			var refreshMirrorWorkspacesAsync = function (row) {
				bridge('mirrorWorkspaces', {}).then(
					function (result) {
						var list = (result && result.workspaces) || [];
						mirrorWorkspacesRef.value = list;
						// 补打标记：这一行的标题能对上某条镜像工作区就写上去。
						//
						// ⚠️ 判据与 `decorate()` 完全一致（`indexOf(title) === 0`），
						// 两处必须同步改，否则会出现「菜单里补上了、轮询又抹掉」的抖动。
						var rowText = String((row && row.textContent) || '').trim();
						if (rowText === '') return;
						for (var i = 0; i < list.length; i += 1) {
							var item = list[i];
							if (!item || typeof item.desktopPath !== 'string' || item.desktopPath === '') continue;
							if (typeof item.title !== 'string' || item.title === '') continue;
							if (!rowMatchesMirror(rowText, item.title, item.slug)) continue;
							try {
								row.setAttribute('data-dsh-workspace-desktop', item.desktopPath);
							} catch (setError) {
								/* 节点已被 React 换掉 —— 无所谓，下一次轮询会补上 */
							}
							// 同步修正本次 pointerdown 记下的值，让紧接着的 click
							// 能直接走 `lastMenuRowPath` 那条精确路径。
							lastMenuRowPath = item.desktopPath;
							try {
								window.__dshSyncProbe = {
									at: Date.now(),
									title: lastMenuRowTitle.slice(0, 40),
									path: lastMenuRowPath,
									via: 'late-refresh',
								};
							} catch (probeError) {
								/* 诊断失败不影响主流程 */
							}
							return;
						}
					},
					function () {
						/* 拿不到就维持现状：至少不会比修之前更差 */
					},
				);
			};

			/** 给浮层菜单追加一行「同步对话」。 */
			var decorate = function () {
				// ⚠️ 顺手给工作区行打上 `data-dsh-workspace-desktop`（幂等，只写一次）。
				//
				// 官方 `ProjectRowItem`（`dsh-client-ui-workspace/lib/client.js:735`）
				// 的 DOM 上**只有 `role="treeitem"` 和文本**，没有 workspaceId ——
				// 而「同步对话」必须知道用户点的是**哪一个**工作区，光靠标题
				// 在「两个目录同名」时会串到另一个目录的对话上。
				//
				// 所以这里按标题对上镜像工作区清单，把 id 写到属性上，
				// pointerdown 时直接读（见 `onPointerDown`）。
				//
				// 只在有镜像工作区时才做（本地工作区不需要这个属性），
				// 且用 `querySelectorAll('[role="treeitem"]')` 范围很小
				//（侧边栏展开时就几十个）。
				var mirrors = mirrorWorkspacesRef.value;
				// ⚠️ 侧边栏收起时 `treeitem` 数量是 0，这一圈本来就什么都不做；
				// 但 `querySelectorAll` 本身仍要走一次选择器引擎。加个闸把它也免掉 ——
				// 用户大多数时间是在**会话里**，侧边栏是收起的。
				/*
				 * ## ⚠️⚠️ 这个门槛原来用的类名**根本不存在**（1.2.73 修）
				 *
				 * 原来写的是：
				 *
				 *     var sidebarOpen = document.querySelector('[class*="mp-drawer-open"]') !== null;
				 *     if (mirrors.length > 0 && sidebarOpen) { ...整段装饰... }
				 *
				 * `mp-drawer-open` 在官方引擎里**一次都没出现过**。实测：
				 *
				 *     grep -r 'drawer' 在 dsh-client-ui-各包的 client.js 里   ⇒ 零命中
				 *
				 * 官方用的是 **data 属性**：`dsh-client-ui-layout` 渲染时写
				 * `data-sidebar-collapsed`（见它自己的 CSS 选择器），根本不是一个类名。
				 *
				 * 后果：`sidebarOpen` **恒为 false** ⇒ 下面整段永远不执行 ⇒
				 *
				 *   · `insertDesktopBadge` 从来没被调用　⇐用户报的「电脑 SVG 图标完全没出现」
				 *   · 工作区行永远打不上 `data-dsh-workspace-desktop` ⇐用户报的「图标位置不对 / 行首空白」
				 *
				 * 都是静默失效，没有任何报错 —— 这就是一直查不到的原因。
				 *
				 * ## 现在怎么判
				 *
				 * 反过来想：这个门槛本来就不应该存在。
				 * 下面第一句就是 `querySelectorAll('[role="treeitem"]')`，
				 * 侧边栏收起时它自然返回空集合，循环一次也不走。
				 * 所以这个前置判断只是“预热优化”，却把功能整个关掉了。
				 *
				 * 保留一个**真实存在的**快速路：只要页面里连一个 `treeitem` 都没有，
				 * 就直接跳过（这一步只是 `querySelector`，比 `querySelectorAll` 还便宜）。
				 * 而“侧边栏是不是展开”交给下面的行数自然判定。
				 */
				var hasTreeRows = document.querySelector('[role="treeitem"]') !== null;
				if (mirrors.length > 0 && hasTreeRows) {
					var rows = document.querySelectorAll('[role="treeitem"]');
					for (var r = 0; r < rows.length; r += 1) {
						var row = rows[r];
						// ## ⚠️⚠️ 每轮**按当前行文本重新匹配**，不能「打过就跳过」
						//
						// 原来这里第一句是
						//
						//     if (row.getAttribute('data-dsh-workspace-desktop') !== null) continue;
						//
						// 意思是「打过标记的行永远不再碰」。这是错的：**React 会复用
						// DOM 行节点** —— 同步完一批对话后侧边栏行顺序一变，同一个
						// `<div role="treeitem">` 就换了内容，而标记还留着旧路径。
						//
						// 真机症状（用户 2026-10-22 报障）：点 B 工作区的
						// 「⋯ → 同步对话」，弹出来的却是 **A 的路径**。只有重启
						// （DOM 整棵重建）才恢复 —— 这就是那个「必须重启」的根因。
						//
						// 所以改成：**每次都用当前行文本重新算一遍**，变了就改，
						// 匹配不到就删。代价是多一次 `indexOf`，而这一段本来就已经
						// 在读 `row.textContent`（那是更贵的那一步）。
						var rowText = String(row.textContent || '').trim();
						var matchedPath = null;
						if (rowText !== '') {
							for (var m = 0; m < mirrors.length; m += 1) {
								var item = mirrors[m];
								if (item.desktopPath === undefined || item.desktopPath === null) continue;
								if (rowMatchesMirror(rowText, item.title, item.slug)) {
									// ⚠️ **打 `desktopPath`，不打 `workspaceId`**。
									//
									// 原先打的是 workspaceId，实测行不通：镜像工作区是
									// 本插件用 `registry.create()` 建的，**绝大多数在 registry
									// 里还没有 id**（`mirrorWorkspaces` 返回的 `workspaceId`
									// 是空串 —— 真机核对过：12 个镜像里只有 2 个有 id）。
									// 打空串等于没打，精确匹配那一支全部失效。
									//
									// 而 `desktopPath` 是**唯一且必然存在**的（它就是用户
									// 选的那个电脑目录），拿它匹配既精确又不依赖 registry。
									matchedPath = item.desktopPath;
									break;
								}
							}
						}
						var current = row.getAttribute('data-dsh-workspace-desktop');
						if (matchedPath === null) {
							// 这一行不再是镜像工作区（或标题变了匹配不上）⇒ 抹掉旧标记，
							// 否则 `onPointerDown` 会读到上一任的路径。
							if (current !== null) row.removeAttribute('data-dsh-workspace-desktop');
						} else if (current !== matchedPath) {
							row.setAttribute('data-dsh-workspace-desktop', matchedPath);
						}
					}
				}
				// ⚠️ 先用一个**便宜**的判据定位：官方把菜单 portal 到 body 下，
				// 所以只在 body 的**直接子节点**里找浮层（通常就十几个），
				// 不要用 `querySelectorAll('div,ul')` 全文档扫一遍 —— 那在
				// 250ms 轮询下会把手机端拖卡。
				//
				// ⚠️⚠️ **再加一道更便宜的闸：菜单没开就立刻返回。**
				//
				// 下面那圈循环对每个 `body > *` 都读 `node.textContent` ——
				// 那会**递归收集整棵子树的文本**，而侧边栏那种几万节点的子树
				// 读一次就是一次实打实的遍历（`textContent` 不是常量时间）。
				// 250ms 一次 × 每次十几棵子树，在手机上是很可观的常态开销，
				// 而**99% 的时间里菜单根本没开**，这些遍历全是白做的。
				//
				// `role="menu"` 是 O(1) 的属性查询（走的是原生 attribute 索引），
				// 官方菜单浮层一定带它（真机 DOM 核对过：
				// `<div role="menu" class="_surface_ri079_1 _list_4ub78_7">`）。
				// 所以先用它当闸门，没菜单就直接跳过整段。
				/*
				 * ## ⚠️⚠️ 闸门本身比被保护的代价还大（1.2.77 修）
				 *
				 * 上面那段注释说 `querySelector('[role="menu"]')` 是
				 * 「O(1) 的属性查询（走的是原生 attribute 索引）」。
				 * **实测不是。**在 200 行侧边栏的真实 DOM 上：
				 *
				 *     document.querySelector('[role="menu"]')          3.77 ms
				 *     遍历 document.body.children 查 role             0.14 ms   ← 快 27 倍
				 *     而它要跳过的那段（遍历 body.children 读 textContent）  0.26 ms
				 *
				 * 也就是说：**为了省 0.26ms，先花了 3.77ms。**
				 * 闸门比被保护的那段还贵 14 倍 —— 这个闸门本身就是负收益。
				 *
				 * `querySelector` 不是 O(1)：它要从根开始遍历整棵树直到命中。
				 * 而菜单没开时**永远不会命中** ⇒ 每次都是最坏情况（扫完才知道没有）。
				 *
				 * 所以改成：直接遍历 `body.children`。
				 * 官方菜单浮层是 body 的**直接子节点**（这一点下面的代码已经在用），
				 * 而 body 的直接子节点通常就十几个 —— 遍历比选择器引擎快得多。
				 */
				var menuOpen = false;
				if (document.body) {
					var bodyKids = document.body.children;
					for (var mk = 0; mk < bodyKids.length; mk += 1) {
						var mkNode = bodyKids[mk];
						if (mkNode && mkNode.getAttribute
							&& mkNode.getAttribute('role') === 'menu') {
							menuOpen = true;
							break;
						}
					}
				}
				if (!menuOpen) {
					if (mounted !== null) mounted = null;
					return;
				}
				var panel = null;
				/*
				 * ⚠️ `document.body` 在极端时机下可能是 `null`（1.2.62）
				 *
				 * 脚本在 `<head>` 内执行、或文档尚未解析到 body 时，
				 * `document.body` 为 null ⇒ `.children` 抛 TypeError。
				 *
				 * 这里外面虽然有 try（定时器那层兑底），但
				 * 抛在这里意味着整个 decorate() 跳过 —— 包括后面
				 * 真正需要做的那些装饰。能提前退就提前退。
				 */
				if (!document.body) return;
				var tops = document.body.children;
				for (var index = 0; index < tops.length; index += 1) {
					var node = tops[index];
					if (node.getAttribute !== undefined && node.getAttribute('data-dsh-remote-sync') === '1') continue;
					var text = String(node.textContent || '');
					if (text.indexOf('删除工作区') < 0 || text.indexOf('重命名') < 0) continue;
					if (node.querySelector('input,textarea') !== null) continue;
					// 再往里收一层：真正的那一行菜单项才是我们要插入的容器。
					panel = node;
					break;
				}
				if (panel === null) {
					if (mounted !== null) mounted = null;
					return;
				}
				if (panel.querySelector('[data-dsh-remote-sync-item]') !== null) return;

				// ⚠️ 从 portal 根往里**收窄**到真正装菜单项的那一层：
				// 官方菜单的 DOM 是 `portal > 定位层 > 列表 > 项`，直接往 portal
				// 根上插会插到列表外面（视觉上跑到浮层边缘、点不到）。
				//
				// ⚠️⚠️ **收窄的判据必须是「它还是菜单项容器」，不能只是「含重命名文本」**。
				// 踩过的坑（1.0.71 真机）：官方菜单项内部是
				// `<div role=menuitem><span class="_itemLabel_">重命名</span></div>`，
				// 按「含文本的最深容器」一路收窄会停在那 个 **span** 上，
				// 按钮被塞进 label span 里 ⇒ ① 视觉上挤在文字位置、
				// ② React 重渲染这个 span 时把按钮连监听一起冲掉，
				// 表现为「菜单里有『同步对话』但点了毫无反应」。
				//
				// 正确判据：**子节点里得有 `role="menuitem"`（或 ≥2 个子项）**，
				// 也就是「还能装下多个菜单项」的那一层。纯 label 元素不满足。
				var inner = panel;
				while (true) {
					var found = null;
					for (var k = 0; k < inner.children.length; k += 1) {
						var child = inner.children[k];
						if (String(child.textContent || '').indexOf('重命名') < 0) continue;
						// 这一层必须是「项容器」：它自己得含 menuitem，或者有多个子元素。
						var isItemHost = child.children.length >= 2;
						if (!isItemHost) {
							try {
								if (child.querySelector('[role="menuitem"]') !== null) isItemHost = true;
							} catch (probeError) {
								/* 忽略 */
							}
						}
						if (child.getAttribute !== undefined &&
							child.getAttribute('role') === 'menuitem') {
							// 它自己就是菜单项 ⇒ 不能再往里收，父层才是容器。
							isItemHost = false;
						}
						if (!isItemHost) continue;
						found = child;
						break;
					}
					if (found === null) break;
					inner = found;
				}
				panel = inner;

				// ⚠️ **必须照抄官方的三段结构**，否则视觉上和原生菜单项不一样：
				//
				//     <button role="menuitem" class="_item_…">
				//       <span class="_itemIcon_…"><svg width=16 height=16 …/></span>
				//       <span class="_itemLabel_…">同步对话</span>
				//     </button>
				//
				// 官方每个菜单项都是「图标 + 文字」两栏（`_itemIcon_4ub78_148`
				// 负责左内边距与 16×16 的图标位）。原先只塞了一个纯文本
				// `button.textContent = '同步对话'`，结果是**一行没有图标的秃文字**，
				// 和旁边的「重命名 / 删除工作区」完全不是一个长相 —— 用户原话：
				// 「你就像人家归档对话，还有重命名那样画一个图标在前面」。
				//
				// 图标自己画（官方没有「同步」这个动作的图标），语义取
				// 「两条弧线首尾相接」＝ 循环/同步，与菜单里其它图标的
				// 1px 描边、16×16、`currentColor` 风格保持一致。
				var button = document.createElement('button');
				button.type = 'button';
				button.setAttribute('data-dsh-remote-sync-item', '1');
				// 尽量沿用官方菜单项的排版：直接抄「重命名」那一行的行内样式。
				var sample = null;
				var candidates = panel.querySelectorAll('[role="menuitem"],button,div');
				for (var j = 0; j < candidates.length; j += 1) {
					if (String(candidates[j].textContent || '').trim() === '重命名') {
						sample = candidates[j];
						break;
					}
				}
				/**
				 * 按官方菜单项的结构拼一个「图标 + 文字」的行。
				 *
				 * @param iconMarkup - 16×16 的 svg 源码。
				 * @param labelText - 文字。
				 */
				var buildMenuItem = function (iconMarkup, labelText) {
					button.textContent = '';
					var icon = document.createElement('span');
					// 类名从官方那一行现抄，抄不到就用官方固定的类名兜底
					//（`_itemIcon_4ub78_148` 是构建 hash，写死会在官方换版本后失效，
					// 所以优先抄 sample 里的，抄不到才退回写死值）。
					var iconClass = '_itemIcon_4ub78_148';
					var labelClass = '_itemLabel_4ub78_190';
					if (sample !== null) {
						var si = sample.querySelector('span');
						if (si !== null && si.className !== '') iconClass = si.className;
						var spans = sample.querySelectorAll('span');
						if (spans.length > 1) labelClass = spans[spans.length - 1].className;
					}
					icon.className = iconClass;
					icon.setAttribute('aria-hidden', 'true');
					icon.innerHTML = iconMarkup;
					var label = document.createElement('span');
					label.className = labelClass;
					label.textContent = labelText;
					button.appendChild(icon);
					button.appendChild(label);
				};
				buildMenuItem(
					'<svg width="16" height="16" viewBox="0 0 16 16" fill="none" ' +
						'xmlns="http://www.w3.org/2000/svg" aria-hidden="true" stroke-width="1">' +
						// 上弧：从左下往右上，带箭头
						'<path d="M2.5 9.2A5.5 5.5 0 0 1 8 3.5c1.6 0 3 .7 4 1.8" ' +
						'stroke="currentColor" stroke-linecap="round"></path>' +
						'<path d="M12 2.6v2.9H9.1" stroke="currentColor" ' +
						'stroke-linecap="round" stroke-linejoin="round"></path>' +
						// 下弧：镜像
						'<path d="M13.5 6.8A5.5 5.5 0 0 1 8 12.5c-1.6 0-3-.7-4-1.8" ' +
						'stroke="currentColor" stroke-linecap="round"></path>' +
						'<path d="M4 13.4v-2.9h2.9" stroke="currentColor" ' +
						'stroke-linecap="round" stroke-linejoin="round"></path>' +
						'</svg>',
					'同步对话',
				);
				if (sample !== null) {
					button.className = sample.className;
					button.setAttribute('style', sample.getAttribute('style') || '');
					// 照抄角色：官方是 `<button role="menuitem">`，抄不到就当普通按钮，
					// 两种都能点（点击走 document 上的委托，见下）。
					var sampleRole = sample.getAttribute('role');
					if (sampleRole !== null && sampleRole !== '') button.setAttribute('role', sampleRole);
				} else {
					button.setAttribute('style', 'display:block;width:100%;padding:8px 12px;' +
						'text-align:left;border:none;background:0 0;cursor:pointer;font-size:13px;');
				}
				// ⚠️ **不在按钮上挂 click 监听**，改用 document 上的事件委托
				//（见 `onDocumentClick`）。理由：这个按钮插在 React 管理的 DOM 里，
				// 父节点重渲染会把它整个替换成新节点，挂在旧节点上的监听随之失效 ——
				// 症状是「按钮看得见、点了没反应」。委托在 document 上就不受影响。
				//
				// ⚠️ **必须包一层 `_itemWrap_`，不能把 button 直接塞进容器**：
				// 官方的每个菜单项都是 `_viewport_ > _itemWrap_ > button`，
				// `_itemWrap_` 负责项与项之间的间距（以及 hover 高亮的圆角范围）。
				// 直接插 button 的话它在视觉上和上面那项**粘在一起**，看着像同一个
				// 菜单项里多出的一行文字。包一层后就与「重命名 / 删除工作区」对齐了。
				var wrap = document.createElement('div');
				if (sample !== null && sample.parentNode !== null &&
					String(sample.parentNode.className || '') !== '') {
					wrap.className = sample.parentNode.className;
				}
				wrap.appendChild(button);
				// ⚠️⚠️ **必须 `appendChild` 追加到末尾，绝不能 `insertBefore(firstChild)`。**
				//
				// 这是「点同步就整个侧边栏空白」的第二个根因（第一个是
				// `onDocumentClick` 里主动 removeChild，已删）。
				//
				// 真机异常原文：
				//   Failed to execute 'removeChild' on 'Node':
				//   The node to be removed is not a child of this node.
				//   slot entry crashed in 'sidebar.workspaces'
				//
				// React 对**子节点列表**按位置记账。往列表**头部**插一个它不认识的
				// 节点，等于把它认的第 0 项挤到第 1 位；等它下次 commit 去
				// `removeChild(children[0])`（它以为那是自己渲染的第一个菜单项），
				// 拿到的却是我们插的 wrap —— 于是抛错，整个 slot 被
				// `SlotErrorBoundary` 换成空 div，工作区与会话列表全消失。
				//
				// 追加到**末尾**则不会移动 React 自己的任何节点位置：它删除时
				// 按 `children[i]` 拿到的仍是它自己的项，我们的 wrap 排在最后
				// 不受影响。（视觉上它在「删除工作区」下面，一样是菜单里的一项。）
				panel.appendChild(wrap);
				mounted = button;
			};

			/**
			 * 委托处理「同步对话」的点击。
			 *
			 * 走委托而不是 `button.addEventListener`，是为了扛住 React 重渲染
			 * 把按钮节点替换掉的情况（见 `decorate()` 里的说明）。
			 */
			var onDocumentClick = function (event) {
				try {
					var node = event.target;
					while (node !== null && node !== undefined && node !== document.body) {
						if (node.getAttribute !== undefined &&
							node.getAttribute('data-dsh-remote-sync-item') === '1') {
							event.preventDefault();
							event.stopPropagation();
							var desktopPath = desktopPathOfMenu(node.parentNode);
							// ## 收起官方菜单 —— **只改样式，绝不改 DOM 结构**
							//
							// 这里踩过两次，两次都很难查，记下来：
							//
							// ### ❌ 第一版：派发 mousedown 指望官方自己关
							// 关不掉。官方的关闭判据挂在它自己那套 pointerdown 链上，
							// 而这里已经 `stopPropagation` 了；就算到了，菜单项自己
							// 也算「内部」，不会被判成外部点击。
							//
							// ### ❌ 第二版：直接 `removeChild` 摘掉浮层
							// **整个侧边栏消失**（用户报障「点同步就空白了」）。
							// 那个浮层是 React 渲染的，React 记着它挂在某父节点下；
							// 摘走之后 React 下次 commit 仍去 `parentNode.removeChild(它)`
							// ⇒ 抛 `The node to be removed is not a child of this node`
							// ⇒ 被官方 `SlotErrorBoundary` 接住 ⇒ `sidebar.workspaces`
							// 整个 slot 换成空 div，工作区与会话列表全没了。
							//
							// ### ✅ 现在：只把**样式**藏起来
							// 节点还在原位、React 的父子记账完全不变，只是看不见、
							// 也点不到。官方下次要移除它时一切照旧。
							//
							// ⚠️ 用 `visibility: hidden` 而不是 `display: none`：
							// `display` 会改变布局（浮层可能有定位相关的测量），
							// 而 `visibility` 只影响绘制与命中测试，对 React 最安全。
							//
							// ## ⚠️⚠️ 必须**记住这个节点**，下次 pointerdown 还原
							//
							// 内联样式是**永久**的，而 React 会复用这个 portal 节点：
							// 下次再打开「⋯」菜单时，同一个节点还是
							// `visibility: hidden; pointer-events: none` ⇒ 菜单画得出来
							// 但**看不见、也点不中**。
							//
							// 真机症状（用户 2026-10-22 报障）：「⋯ 点了没反应」——
							// 必须重启 App（DOM 整棵重建）才恢复。
							//
							// 所以这里把节点记在 `hiddenMenuRoot` 上，由
							// `restoreHiddenMenu()` 在下一次 pointerdown 时清掉。
							try {
								var menuRoot = node.parentNode;
								while (menuRoot !== null && menuRoot.parentElement !== null &&
									menuRoot.parentElement !== document.body) {
									menuRoot = menuRoot.parentElement;
								}
								if (menuRoot !== null && menuRoot.parentElement === document.body) {
									menuRoot.style.visibility = 'hidden';
									menuRoot.style.pointerEvents = 'none';
									hiddenMenuRoot = menuRoot;
								}
							} catch (hideError) {
								/* 藏不掉也不影响打开对话框 */
							}
							if (desktopPath !== null) openSync(desktopPath);
							// 诊断：把 click 这一步拿到什么也记下来 ——
							// 与 `__dshSyncProbe`（pointerdown 记到的）对比，
							// 就能一眼看出是「没记到」还是「记到了又被清掉」。
							try {
								window.__dshSyncClick = {
									at: Date.now(),
									desktopPath: desktopPath,
									opened: desktopPath !== null,
								};
							} catch (probeError2) {
								/* 诊断失败不影响主流程 */
							}
							return;
						}
						node = node.parentElement;
					}
				} catch (delegateError) {
					/* 忽略：委托失败不能影响页面其它点击 */
				}
			};
			/*
			 * ## ⚠️⚠️ 先建清理器，再注册资源（1.2.79 修）
			 *
			 * 这个函数从**第一个 `addEventListener`** 到**返回清理函数**
			 * 之间隔了 124 行。中间任何一处抛错，走到外层 `guard`：
			 *
			 *     guard('installSyncMenu', () => ctx.effect(() => installSyncMenu(ctx), ...))
			 *
			 * `guard` 把异常吞掉、返回 `fallback`(undefined) ⇒ `ctx.effect` 收不到
			 * 清理函数 ⇒ **已经注册的监听器永远无人回收**。
			 *
			 * `guard` 能保证「不炸」，保证不了「回收」。
			 * 而每多一个孤儿监听器，`onDocumentClick` 就多跑一遍 ——
			 * 那个函数里有 `panel.appendChild`，它不幂等：
			 * 跑两遍就是往菜单里插**两个**「同步对话」。
			 *
			 * 所以把资源全部登记在一个数组里，清理器引用它。
			 * 这样即使在中间抛错，清理器也已经在外面手上、能回收已注册的部分。
			 */
			/* 已注册的资源回收器（监听器、定时器……） */
			var installedResources = [];
			var installCleanup = function () {
				for (var ci = 0; ci < installedResources.length; ci += 1) {
					try {
						installedResources[ci]();
					} catch (cleanupError) {
						/* 单个清理失败不影响其它 */
					}
				}
				installedResources.length = 0;
			};
			document.addEventListener('click', onDocumentClick, true);
			installedResources.push(function () {
				document.removeEventListener('click', onDocumentClick, true);
			});

			// 低频轮询：官方菜单是 React 按需渲染的浮层，没有可供订阅的事件。
			// 250ms 一次、只在 DOM 里查一次轻量选择器，对手机端开销可忽略。
			//
			// ⚠️ 同时挂一个**捕获阶段**的 pointerdown 监听来记「这次点的是哪个
			// 工作区行」—— 官方菜单 portal 出去之后，DOM 上再也回不到那一行，
			// 只能在点击发生的那一刻把它记下来。
			var onPointerDown = function (event) {
				try {
					// ⚠️ **先还原上次被藏起来的菜单浮层**，再做别的。
					//
					// 用户下一次点「⋯」时，那个 portal 节点还是
					// `visibility: hidden; pointer-events: none` ⇒ 菜单看不见、
					// 也点不中（用户报障「⋯ 点了没反应」，必须重启才恢复）。
					// 放在最前面是因为它必须无条件执行 —— 下面那段是按
					// `role="treeitem"` 匹配的，匹配不到就 `return` 了。
					restoreHiddenMenu();
					var node = event.target;
					while (node !== null && node !== undefined && node !== document.body) {
						if (node.getAttribute !== undefined && node.getAttribute('role') === 'treeitem') {
							// 只记**工作区行**（它有展开箭头）；会话行也带 treeitem，
							// 但会话行的标题不会匹配镜像工作区清单，所以直接记原文即可，
							// 匹配失败时菜单项点了不会有反应（安全失败）。
							//
							// ⚠️⚠️ **两个变量必须无条件覆盖，不能「记到才写」。**
							//
							// 这是真机踩到的串台 bug：用户在 DSHAPP 上点过一次「同步对话」
							// （路径记成了 DSHAPP），随后去 **ai-office-mcp** 那一行点「⋯」→
							// 「同步对话」，弹出的对话框标题却还是 **DSHAPP 的路径**，
							// 列出来的是另一个目录的对话。
							//
							// 原因：`ai-office-mcp` 那个工作区行上**没有**
							// `data-dsh-workspace-desktop`（`decorate()` 只给能匹配到
							// 镜像清单的行打），于是 `path` 是空串；而下面原来的写法是
							// 「有值才覆盖」，空串就保留了上一次的旧路径 ⇒ 串台。
							//
							// 所以这里改成**总是写**（包括空串），旧值一律作废。
							// 空路径时 `desktopPathOfMenu()` 会走标题兜底匹配，
							// 再匹配不到就返回 null（安全失败，不会串到别的目录）。
							lastMenuRowTitle = String(node.textContent || '').trim();
							lastMenuRowPath = String(node.getAttribute('data-dsh-workspace-desktop') || '');
							// ## ⚠️⚠️ 行上没有标记 ⇒ **立刻重拉清单并补打**（用户 2026-10-22 报障）
							//
							// 真机实测的根因链（全新安装 + 刚配对）：
							//
							//   ① 用户在「电脑」页签里配对 + 选目录 ⇒ 宿主
							//      `ensureDesktopWorkspace()` 建出镜像工作区，
							//      `mirrorWorkspaces` 立刻就有数据了；
							//   ② 但前端 `mirrorWorkspacesRef.value` **还是空的** ——
							//      它只在三个时机刷新：App 启动、`domain/changed` 事件、
							//      10 分钟兜底；
							//   ③ 而宿主**从不发** `domain/changed`（全文件搜不到），
							//      于是这一格只能等 10 分钟或重启 App；
							//   ④ `decorate()` 拿空清单 ⇒ 打不上
							//      `data-dsh-workspace-desktop` ⇒ 点「⋯」记到的
							//      `path` 是空串 ⇒ `desktopPathOfMenu()` 返回 null
							//      ⇒ `openSync` 不被调用 ⇒ **对话框直接消失**。
							//
							// 真机探针证据（复现时抓的）：
							//     __dshSyncProbe = { title: "_项目文件夹", path: "" }
							//     __dshSyncClick = { desktopPath: null, opened: false }
							//
							// 用户的感受就是「点同步对话没反应」，而**重启一次就好了**
							// （重启走「App 启动」那条刷新路）。
							//
							// 修法：**点开「⋯」的这一刻就补一次**。菜单是用户点出来的，
							// 这个时机既不费流量（只在用户真的要用这个功能时发一次），
							// 又能保证「点下去之前清单一定是新的」。
							//
							// ⚠️ 必须异步、且**不能挡住本次 pointerdown**：清单回来之后再
							// 补打标记并重算路径。用户手速再快也要几百毫秒才点到菜单项，
							// 而本地桥调用通常几十毫秒，实测够用。
							if (lastMenuRowPath === '' && lastMenuRowTitle !== '') {
								refreshMirrorWorkspacesAsync(node);
							}
							// 诊断：把「这次记到了什么」暴露给页面，排查「点同步没反应」用。
							try {
								window.__dshSyncProbe = {
									at: Date.now(),
									title: lastMenuRowTitle.slice(0, 40),
									path: lastMenuRowPath,
								};
							} catch (probeError) {
								/* 诊断失败不影响主流程 */
							}
							return;
						}
						node = node.parentElement;
					}
					// ⚠️⚠️ **什么都不做，尤其是不要清空。**
					//
					// 这里原本是 `lastMenuRowTitle = ''; lastMenuRowPath = '';`，
					// 那是「点空白处就忘掉上一次的行」的直觉写法 —— 但它把
					// **点菜单项这一次**也当成「点空白处」了：
					//
					//   ① 用户点「⋯」            → pointerdown 命中工作区行，记下路径 ✅
					//   ② 用户点「同步对话」      → pointerdown 的 target 是菜单项里的
					//      `<span>`，它挂在 portal 出来的浮层下、**不在**任何
					//      `role="treeitem"` 里 → 向上找不命中 → 走到这里把刚记下的
					//      路径**清空** ❌
					//   ③ 紧接着的 click 走 `onDocumentClick` → `desktopPathOfMenu()`
					//      拿到空串 → 返回 null → `openSync` 不被调用
					//
					// 净效果就是用户说的「点同步对话没反应」：按钮画得出来、点得中
					//（探针实测捕获阶段确实命中了），但什么都不会发生。
					//
					// 不清空是安全的：这个值只在「点同步对话」时被读一次，
					// 而那时官方菜单一定还开着（点了就关），菜单跟着的就是那次
					// 「⋯」。真点到别处也不会误触发 —— 委托只在命中
					// `data-dsh-remote-sync-item` 时才读它。
				} catch (recordError) {
					/* 记录失败就保持上一次的值，同上：宁可用旧值也不要清空 */
				}
			};
			document.addEventListener('pointerdown', onPointerDown, true);
			installedResources.push(function () {
				document.removeEventListener('pointerdown', onPointerDown, true);
			});

			var timer = setInterval(function () {
				try {
					decorate();
				} catch (decorateError) {
					/* 结构变化导致的偶发失败忽略 */
				}
			}, 250);
			installedResources.push(function () {
				clearInterval(timer);
			});
			return function () {
				/* 先回收已注册的所有资源（监听器 + 定时器） */
				installCleanup();
				// 卸载前把可能藏着的菜单浮层还原，别给官方留一个隐藏节点。
				restoreHiddenMenu();
				if (host !== null && host.parentNode !== null) host.parentNode.removeChild(host);
				host = null;
				root = null;
			};
		}
		//#endregion

		//#region 电脑流式帧 → 本地 transient
		/**
		 * 每个会话的流式折叠状态。
		 *
		 * 与官方 `ClientAssistantStream` 同构：`start` 建立 attempt、`chunk` 逐帧递增
		 * `nextIndex`、`end` 收尾。**序号不连续时直接放弃这一轮流式动画**
		 * （不抛错）——durable 事件随后会把完整内容补上，用户看到的是「没有逐字动画」
		 * 而不是「内容缺失」。这与官方 `rebaseline` 的兜底方向一致。
		 */
		var attempts = new Map();

		/** 取某会话的绑定（`eventSource` 就是浏览器侧的会话事件源）。 */
		function bindingOf(ctx, sessionId) {
			try {
				return ctx.sessions.binding(sessionId);
			} catch (error) {
				return undefined;
			}
		}

		/**
		 * 退役某会话的逐字残片。
		 *
		 * ⚠️ **必须由我们来做**：官方的清理点是
		 * `MutableSessionEventSource.settleAssistant(attemptId)`，它只被
		 * `ClientAssistantStream.acceptEventChange` 调用 —— 而那条链服务于**远程
		 * 事件流**。手机本地的 durable 事件是宿主直接 `session.append()` 进会话的，
		 * 走不到它。不清理的话，逐字残片会永久留在窗口里，与随后到达的完整消息
		 * 叠成两份。
		 *
		 * 不传 `entry`：durable 的完整消息会由会话事件流自己送进窗口，这里只负责
		 * 把残片摘掉。
		 *
		 * @param ctx - client 根上下文。
		 * @param sessionId - 会话 id。
		 * @param attemptId - 要退役的 attempt。
		 */
		function retireTransient(ctx, sessionId, attemptId) {
			if (typeof attemptId !== 'string') return;
			var binding = bindingOf(ctx, sessionId);
			if (binding === undefined || binding.eventSource === undefined) return;
			try {
				binding.eventSource.settleAssistant(attemptId);
			} catch (error) {
				/* 窗口已被替换：无需清理 */
			}
		}

		/**
		 * 把「这条消息没发出去」显式画给用户看。
		 *
		 * ## 为什么需要（乐观返回的必然配套）
		 *
		 * `prompt` 改成乐观返回之后，用户按发送 → 消息立刻上屏 → 转发在后台跑。
		 * 如果转发失败，前端**收不到任何错误**（它早就拿到 `{accepted:true}` 了），
		 * 于是用户会盯着一个永远不来的回复干等。
		 *
		 * ## 为什么不用官方组件
		 *
		 * 试过两条官方路径都不通：
		 * · `api-session/error` —— 前端 `dsh-client-ui-conversation` **不监听**它；
		 * · 往会话 append 一条错误事件 —— 同步会话在本地**没有 session 对象**
		 *   （`ctx.sessions.get()` 是 undefined，历史全在电脑上），写不进去。
		 *
		 * 所以直接在页面世界插一条自己的浮层。用 `position: fixed` 贴在顶部，
		 * 6 秒后自动消失，点一下也能立刻关掉 —— 不打断操作，但一定看得见。
		 *
		 * ⚠️ 同一时刻只留一条：连续失败时不叠成一片，后一条替换前一条。
		 *
		 * @param ctx - client 根上下文（这里只用它拿 logger，允许为 undefined）。
		 * @param sessionId - 出问题的会话（仅用于文案定位，不展示）。
		 * @param message - 失败原因原文。
		 */
		function showPromptFailure(ctx, sessionId, message) {
			try {
				var host = document.body;
				if (host === null) return;
				var previous = document.getElementById('dsh-remote-prompt-failed');
				if (previous !== null) previous.remove();
				var bar = document.createElement('div');
				bar.id = 'dsh-remote-prompt-failed';
				bar.setAttribute('role', 'alert');
				bar.textContent = '消息没有发到电脑：' + message;
				var style = bar.style;
				style.position = 'fixed';
				style.left = '12px';
				style.right = '12px';
				style.top = 'calc(env(safe-area-inset-top, 0px) + 56px)';
				style.zIndex = '2147483000';
				style.padding = '10px 14px';
				style.borderRadius = '12px';
				style.background = 'rgba(190, 46, 46, 0.96)';
				style.color = '#fff';
				style.fontSize = '13px';
				style.lineHeight = '1.45';
				style.boxShadow = '0 6px 20px rgba(0, 0, 0, 0.22)';
				style.wordBreak = 'break-word';
				bar.addEventListener('click', function () {
					bar.remove();
				});
				host.appendChild(bar);
				setTimeout(function () {
					if (bar.isConnected) bar.remove();
				}, 6000);
			} catch (error) {
				/*
				 * ⚠️⚠️ 这里**不能**用可选链 `?.`（1.2.55 修）。
				 *
				 * 本文件受 chrome73 语法约束（见文件头）。`?.` 是 chrome80+ 才有的，
				 * 在这台机器的 WebView 上会让**整个文件解析失败**（SyntaxError），
				 * 于是侧边栏图标、进度浮层、所有装饰一起消失。
				 * 那不是「没生效」，是脚本根本没跑起来。
				 */
				logWarn('[dsh-remote-mobile] 画发送失败提示出错: ' + String(error), ctx);
			}
		}

		/**
		 * 画一张「电脑在等你回答」的卡片。
		 *
		 * ## 为什么必须自己画，不能复用官方那个 ask 卡片
		 *
		 * 官方 `dsh-client-ui-user-questions` 的卡片是挂在**本地 waterfall**
		 * 上的（`ctx.remote.$on('user-questions/request', …)`）。而手机这边
		 * 根本没有这条 waterfall —— 请求在电脑上、answerer 也在电脑上，
		 * 手机只收到一条 SSE。没有可挂的 waterfall，官方组件就渲染不出来。
		 *
		 * 所以这里画一张**独立的浮层**，用户点完直接把答案经 RPC
		 * `answerAsk` 送回电脑，由电脑端的 answerer 喂回那条真正的 waterfall。
		 *
		 * ## 设计约束（手机窄屏 + 用户可能在半梦半醒之间点）
		 *
		 * - 卡片从底部升起来，压在输入框上方，不遮挡消息内容；
		 * - 选项做成**整行可点**的大按钮（最小 44px 高，符合触控标准），
		 *   而不是官方那种紧凑的小方块 —— 手机上一排小方块很容易点错；
		 * - 明确写出「来自电脑」，否则用户不知道这是谁在问；
		 * - 授权类**危险动作用红色**、安全的用蓝色，颜色本身就是提示；
		 * - 一次性：点完立刻收起，不做二次确认（二次确认在手机上反而误触）。
		 *
		 * @param ctx - client 根上下文。
		 * @param payload - 电脑端推来的 `{ requestId, kind, … }`。
		 */
		function showAskCard(ctx, payload) {
			try {
				if (document.body === null) return;
				// 同一时刻只留一张：电脑端串行发问，堆叠只会互相遮挡。
				var existing = document.getElementById('dsh-remote-ask');
				if (existing !== null) existing.remove();

				var kind = payload.kind === 'approval' ? 'approval' : 'question';
				var questions = Array.isArray(payload.questions) ? payload.questions : [];
				// 当前正在答第几题（多题时逐题走，避免一屏塞满）。
				var cursor = { index: 0, answers: [] };

				var card = document.createElement('div');
				card.id = 'dsh-remote-ask';
				card.setAttribute('role', 'dialog');
				card.setAttribute('aria-modal', 'false');
				var cs = card.style;
				cs.position = 'fixed';
				cs.left = '10px';
				cs.right = '10px';
				cs.bottom = 'calc(env(safe-area-inset-bottom, 0px) + 76px)';
				cs.zIndex = '2147483001';
				cs.borderRadius = '16px';
				cs.background = '#ffffff';
				cs.color = '#0f1115';
				cs.boxShadow = '0 12px 40px rgba(0, 0, 0, 0.28)';
				cs.padding = '14px 14px 12px';
				cs.fontSize = '14px';
				cs.lineHeight = '1.5';
				cs.maxHeight = '62vh';
				cs.overflowY = 'auto';
				cs.boxSizing = 'border-box';

				/** 小工具：造一个元素并设好样式。 */
				var el = function (tag, css, text) {
					var node = document.createElement(tag);
					if (css) {
						for (var key in css) {
							if (Object.prototype.hasOwnProperty.call(css, key)) node.style[key] = css[key];
						}
					}
					if (text !== undefined) node.textContent = text;
					return node;
				};

				/** 顶部那行「来自电脑」的标识。 */
				var eyebrow = el(
					'div',
					{
						fontSize: '11px',
						letterSpacing: '0.04em',
						color: '#6b7280',
						marginBottom: '6px',
						textTransform: 'uppercase',
					},
					kind === 'approval' ? '电脑请求权限' : '电脑在等你回答',
				);
				card.appendChild(eyebrow);

				/*
				 * **补拉提示条**（`payload.note`）。
				 *
				 * 场景：用户走开时电脑上发起了审批，当时手机不在线 ⇒ 电脑把它
				 * fallback 给了本地 GUI（见电脑端 `ghostAsks`）。用户回来打开手机，
				 * 这条请求被补拉出来 —— 但它很可能**已经在电脑上处理过了**。
				 *
				 * 不提示的话，用户点了「允许」发现没反应，只会以为 App 坏了。
				 */
				if (typeof payload.note === 'string' && payload.note !== '') {
					card.appendChild(
						el(
							'div',
							{
								fontSize: '12px',
								lineHeight: '1.45',
								marginTop: '2px',
								marginBottom: '8px',
								padding: '7px 9px',
								borderRadius: '8px',
								background: 'rgba(224,163,58,0.14)',
								color: '#8a5b00',
							},
							payload.note,
						),
					);
				}

				/** 收起卡片（不回答 = 让电脑那边超时或走官方兜底）。 */
				var dismiss = function () {
					if (card.isConnected) card.remove();
				};

				/** 把答案送回电脑。 */
				var submit = function (answer) {
					dismiss();
					try {
						// 走官方 RPC 通道（与手机端其他调用同一条）。
						fetch('/api/dsh-remote/rpc', {
							method: 'POST',
							headers: { 'content-type': 'application/json' },
							body: JSON.stringify({
								method: 'answerAsk',
								params: { requestId: payload.requestId, answer: answer },
							}),
						}).catch(function () {
							// 送不回去就算了：电脑端会超时并走官方兜底，
							// 用户也可以在电脑上直接点。这里弹错误只会添乱。
						});
					} catch (error) {
						logWarn('[dsh-remote-mobile] 回传答案失败: ' + String(error), ctx);
					}
				};

				/** 渲染一题。 */
				var renderQuestion = function () {
					// 清掉除 eyebrow 外的旧内容。
					while (card.childNodes.length > 1) card.removeChild(card.lastChild);

					if (kind === 'approval') {
						// ── 授权：两个大按钮，危险动作红色 ──
						var what = payload.toolName !== '' ? '「' + payload.toolName + '」' : '这个操作';
						card.appendChild(
							el('div', { fontWeight: '600', marginBottom: '4px' }, '允许电脑执行 ' + what + '？'),
						);
						if (typeof payload.reason === 'string' && payload.reason !== '') {
							card.appendChild(
								el('div', { fontSize: '13px', color: '#4b5563', marginBottom: '10px' }, payload.reason),
							);
						} else {
							card.appendChild(el('div', { height: '8px' }));
						}
						var allow = el(
							'button',
							{
								display: 'block',
								width: '100%',
								minHeight: '46px',
								marginBottom: '8px',
								border: 'none',
								borderRadius: '11px',
								background: '#2563eb',
								color: '#ffffff',
								fontSize: '15px',
								fontWeight: '600',
							},
							'允许这一次',
						);
						allow.addEventListener('click', function () {
							submit('allowed-once');
						});
						card.appendChild(allow);
						var deny = el(
							'button',
							{
								display: 'block',
								width: '100%',
								minHeight: '46px',
								border: '1px solid #e5e7eb',
								borderRadius: '11px',
								background: '#f9fafb',
								color: '#b91c1c',
								fontSize: '15px',
								fontWeight: '600',
							},
							'拒绝',
						);
						deny.addEventListener('click', function () {
							submit('rejected');
						});
						card.appendChild(deny);
						return;
					}

					// ── 提问：逐题渲染 ──
					var q = questions[cursor.index];
					if (q === undefined) {
						submit({ answers: cursor.answers });
						return;
					}
					if (questions.length > 1) {
						card.appendChild(
							el(
								'div',
								{ fontSize: '11px', color: '#6b7280', marginBottom: '4px' },
								'第 ' + (cursor.index + 1) + ' / ' + questions.length + ' 题',
							),
						);
					}
					if (typeof q.header === 'string' && q.header !== '') {
						card.appendChild(
							el('div', { fontSize: '12px', color: '#6b7280', marginBottom: '2px' }, q.header),
						);
					}
					card.appendChild(
						el('div', { fontWeight: '600', marginBottom: '10px' }, String(q.question || '请选择')),
					);

					var picked = [];
					var options = Array.isArray(q.options) ? q.options : [];
					var multi = q.multiSelect === true;

					/** 收尾一题：记下答案，进入下一题或提交。 */
					var commit = function (selected) {
						cursor.answers.push({ questionId: q.id, selected: selected });
						cursor.index += 1;
						if (cursor.index >= questions.length) submit({ answers: cursor.answers });
						else renderQuestion();
					};

					options.forEach(function (option) {
						var label = String(option.label || '');
						var row = el('button', {
							display: 'block',
							width: '100%',
							minHeight: '44px',
							marginBottom: '8px',
							padding: '10px 12px',
							border: '1px solid #e5e7eb',
							borderRadius: '11px',
							background: '#ffffff',
							color: '#0f1115',
							fontSize: '14px',
							textAlign: 'left',
							lineHeight: '1.35',
						});
						row.appendChild(el('div', { fontWeight: '500' }, label));
						if (typeof option.description === 'string' && option.description !== '') {
							row.appendChild(
								el('div', { fontSize: '12px', color: '#6b7280', marginTop: '2px' }, option.description),
							);
						}
						row.addEventListener('click', function () {
							if (!multi) {
								commit([label]);
								return;
							}
							// 多选：切换高亮，另有一个「确认」按钮统一提交。
							var at = picked.indexOf(label);
							if (at >= 0) picked.splice(at, 1);
							else picked.push(label);
							row.style.background = at >= 0 ? '#ffffff' : '#eff6ff';
							row.style.borderColor = at >= 0 ? '#e5e7eb' : '#2563eb';
						});
						card.appendChild(row);
					});

					if (multi) {
						var confirm = el(
							'button',
							{
								display: 'block',
								width: '100%',
								minHeight: '46px',
								border: 'none',
								borderRadius: '11px',
								background: '#2563eb',
								color: '#ffffff',
								fontSize: '15px',
								fontWeight: '600',
							},
							'确认',
						);
						confirm.addEventListener('click', function () {
							commit(picked.slice());
						});
						card.appendChild(confirm);
					}

					// 「跳过」：只对多题有意义，单题跳过等于不回答。
					if (questions.length > 1) {
						var skip = el(
							'button',
							{
								display: 'block',
								width: '100%',
								minHeight: '38px',
								marginTop: '4px',
								border: 'none',
								background: 'transparent',
								color: '#6b7280',
								fontSize: '13px',
							},
							'跳过这题',
						);
						skip.addEventListener('click', function () {
							commit([]);
						});
						card.appendChild(skip);
					}
				};

				renderQuestion();
				document.body.appendChild(card);
			} catch (error) {
				logWarn('[dsh-remote-mobile] 画 ask 卡片出错: ' + String(error), ctx);
			}
		}

		/**
		 * 把一帧电脑流式数据折进手机本地会话的 transient 行。
		 *
		 * @param ctx - client 根上下文。
		 * @param sessionId - 两端同名的会话 id。
		 * @param frame - 电脑端流式帧。
		 */		function injectFrame(ctx, sessionId, frame) {
			if (frame.type === 'start') {
				// 上一轮若还挂着（start 帧没等到 end 帧），先把它退役，免得起重。
				var stale = attempts.get(sessionId);
				if (stale !== undefined) {
					attempts.delete(sessionId);
					retireTransient(ctx, sessionId, stale.attemptId);
				}
				attempts.set(sessionId, {
					attemptId: frame.attemptId,
					turn: frame.turn,
					step: frame.step,
					nextIndex: 0,
					gap: 0,
				});
				return;
			}
			var attempt = attempts.get(sessionId);
			if (attempt === undefined || attempt.attemptId !== frame.attemptId) return;
			if (frame.type === 'end') {
				attempts.delete(sessionId);
				// `abandoned` 不会有 durable 结算，全靠这里把残片摘掉。
				retireTransient(ctx, sessionId, attempt.attemptId);
				return;
			}
			if (frame.type !== 'chunk') return;
			if (frame.index !== attempt.nextIndex) {
				// 序号跳了：这一轮流式不可信，交给 durable 通道兜底。
				attempts.delete(sessionId);
				retireTransient(ctx, sessionId, attempt.attemptId);
				return;
			}
			attempt.nextIndex += 1;
			attempt.gap += 1;

			var binding = bindingOf(ctx, sessionId);
			if (binding === undefined) return;

			// transient 的 seq 要插在已提交事件之间：官方用 durableCursor + 1 - 1/(n+1)。
			var cursor = -1;
			var snapshot = binding.eventSource.getSnapshot();
			var entries = (snapshot && snapshot.entries) || [];
			for (var index = 0; index < entries.length; index += 1) {
				var entry = entries[index];
				if (entry.type !== 'transient' && entry.event && typeof entry.event.seq === 'number') {
					if (entry.event.seq > cursor) cursor = entry.event.seq;
				}
			}
			try {
				binding.eventSource.append({
					type: 'transient',
					event: {
						type: 'assistant/live-chunk',
						seq: cursor + 1 - 1 / (attempt.gap + 1),
						time: typeof frame.time === 'number' ? frame.time : Date.now(),
						data: {
							attemptId: attempt.attemptId,
							turn: attempt.turn,
							step: attempt.step,
							chunk: frame.chunk,
						},
					},
				});
			} catch (error) {
				attempts.delete(sessionId);
			}
		}

		/**
		 * 订阅宿主桥的全局 SSE。
		 *
		 * 全局一条：手机 WebView 对并发连接有上限，且一条连接就能服务所有会话。
		 * EventSource 自带断线重连，所以这里不做退避。
		 *
		 * @param ctx - client 根上下文。
		 */
		function subscribe(ctx) {
			/*
			 * ## ⚠️ `EventSource` 也要先判存在（1.2.59）
			 *
			 * `subscribe` 外面有 guard，所以它缺失时**不会带走整批插件**，
			 * 但会静默地少掉【电脑实时消息流】—— 手机上看不到逐字动画，
			 * 只能等整段结束后一次性刷出来，而且没有任何提示。
			 *
			 * 这不是「不能工作」，是「工作得不好」—— 比完全不工作难查得多。
			 * 所以这里明确跳过，并在窗口上留个记号，方便真机上一眼看出原因。
			 */
			if (typeof EventSource !== 'function') {
				try { window.__dshRemoteNoEventSource = true; } catch (probeError) { /* 诊断失败不影响主流程 */ }
				return function () { };
			}
			var source = new EventSource(ROUTE_STREAM);
			source.addEventListener('frame', function (message) {
				var payload;
				try {
					payload = JSON.parse(message.data);
				} catch (error) {
					return;
				}
				if (!payload || typeof payload.sessionId !== 'string') return;
				injectFrame(ctx, payload.sessionId, payload.frame);
			});
			// durable 结算兜底：万一 `end` 帧丢了，残片也要被摘掉。
			source.addEventListener('settled', function (message) {
				var payload;
				try {
					payload = JSON.parse(message.data);
				} catch (error) {
					return;
				}
				if (!payload || typeof payload.sessionId !== 'string') return;
				var attempt = attempts.get(payload.sessionId);
				if (attempt === undefined) return;
				attempts.delete(payload.sessionId);
				retireTransient(ctx, payload.sessionId, attempt.attemptId);
			});
			// ══ 电脑端发起了 ask 提问 / 权限授权 ══════════════════════════════
			//
			// 用户报障原文（2026-10-21）：
			//   「手机在远程访问电脑的时候，电脑这边工作发起了 ask 弹窗或者
			//     权限授权弹窗，手机那边都没有正常的弹出，导致用户无法选择
			//     任务卡住」
			//
			// 这条事件是**新增的**：`ask` / `approval` 不走会话事件流（它们是
			// cordis waterfall，进程内同步派发、不落盘），所以原来的 `event`
			// 通道一条都碰不到。桌面端现在注册了 answerer 把请求推过来。
			source.addEventListener('ask', function (message) {
				var payload;
				try {
					payload = JSON.parse(message.data);
				} catch (error) {
					return;
				}
				if (!payload || typeof payload.requestId !== 'string') return;
				showAskCard(ctx, payload);
			});
			// 转发失败通知。
			//
			// `prompt` 现在是**乐观返回**的（见服务端 `controller.prompt` 的注释）：
			// 用户按发送后消息立刻上屏，转发在后台跑。所以转发失败时前端**不会**
			// 拿到任何错误 —— 用户会一直等一个永远不来的回复。
			//
			// 这里把失败显式画出来：在消息列表顶部弹一条可读的错误条。
			// 之所以不重试：`prompt` 的失败多为「电脑侧会话不存在 / 路径未知」，
			// 重试也还是同一个结果，必须让用户知道并自己决定（重新同步对话）。
			source.addEventListener('prompt-failed', function (message) {
				var payload;
				try {
					payload = JSON.parse(message.data);
				} catch (error) {
					return;
				}
				if (!payload || typeof payload.message !== 'string') return;
				showPromptFailure(ctx, payload.sessionId, payload.message);
			});
			return function () {
				source.close();
			};
		}
		//#endregion

		/**
		 * 需要宿主提供的服务：slot 注册表、工作区浏览与导航、会话数据。
		 *
		 * ⚠️ `remote` / `remote.agentPresets` 是**预设名单**那条路要用的：
		 * 官方 `dsh-client-ui-agent-preset` 自己也 inject 了这两个
		 * （见其 `lib/client.js:1527` 的 inject 数组）。不加的话 `ctx.remote`
		 * 属性访问会拿到 `undefined`，`patchAgentPresets` 直接静默跳过。
		 */
		/**
		 * 需要宿主提供的服务：slot 注册表、工作区浏览与导航、会话数据、远程命名空间。
		 *
		 * ⚠️⚠️ **这里只能写「真实存在的服务名」，绝不能写带点的子属性。**
		 *
		 * 这是 1.0.25 → 1.0.28 四个版本「改了却完全没有效果」的**真正根因**。
		 *
		 * cordis 的 `Inject.resolve()`（`cordis/lib/index.js:1491`）把数组每一项
		 * 原样收进一个 map：`for (const name of inject) result[name] = null;`。
		 * 然后 `Fiber._refresh()`（同文件 `:1317`）逐个检查：
		 *
		 * ```js
		 * for (const name of Object.keys(this.inject)) {
		 *   const impl = this._store[name];
		 *   if (!impl) { epoch = INACTIVE; break; }   // ← 缺一个就全不激活
		 *   ...
		 * }
		 * ```
		 *
		 * 我原来在这里写了 `'remote.agentPresets'` —— 它不是服务名，而是 `remote`
		 * 服务上的一个**属性**。`_getImpl('remote.agentPresets')` 找不到它
		 * ⇒ `_store['remote.agentPresets']` 是 undefined ⇒ `epoch = INACTIVE`
		 * ⇒ **`apply()` 从头到尾没有被调用过**。
		 *
		 * 症状正好是用户报的三连：
		 *   - 「模型列表能够快速切换回来了，但是预设列表没有切换」——
		 *     模型走的是 `patchModelDirectories`，它在 `apply()` 里……**同样没跑**，
		 *     但模型列表的切换靠的是宿主 `mergeCatalog` + 官方自己的重算，所以照样生效；
		 *   - 「不管是私有默认工作区还是手机目录工作区，都无法切换回手机预设」——
		 *     因为我的 `patchAgentPresets` 一次都没执行；
		 *   - 而「电脑模型列表能显示」也成立：那是宿主侧做的，与客户端插件无关。
		 *
		 * 官方 `dsh-client-ui-agent-preset` 之所以能写 `'remote.agentPresets'`
		 * （见其 `lib/client.js:1532`），是因为它**同时**注入了 `'remote'`
		 * 且那是 `RemoteNamespace` 的合法子服务寻址方式；
		 * 本插件不依赖子命名空间 —— `ctx.remote.agentPresets` 经 `'remote'`
		 * 注入后是可直接读到的属性（`ctx.get('remote')` 的返回值上带它），
		 * 所以只声明 `'remote'` 就够，**不要**再列子路径。
		 */
		const inject = ['slots', 'uiWorkspace', 'sessions', 'workspaces', 'remote'];

		/**
		 * Client 插件主体：注册「手机 / 电脑」双页签的目录选择对话框 + 订阅流式帧。
		 *
		 * @param ctx - client 根上下文。
		 */
		function apply(ctx) {
			/**
			 * ⚠️⚠️ **整个 `apply()` 必须整体兜底 —— 这是 1.0.29 让 App 无限重启的教训。**
			 *
			 * 1.0.29 把 `inject` 里那个非法的 `'remote.agentPresets'` 去掉之后，
			 * `apply()` **第一次真正开始执行**（1.0.25–1.0.28 它从来没跑过，
			 * 所以那四版是「不崩但也不工作」）。
			 *
			 * 一旦它真的跑起来，里面任何一步抛错都会被 cordis 记成插件 FAILED，
			 * 而 `dsh-client-modules` 的 `ClientPackageCompositionError`
			 * （`lib/index.js:144-157`）是**整批聚合抛出**的 —— 一个插件炸掉，
			 * 整页 `Failed to load plugins`，App 直接进救援模式并无限重启：
			 *
			 * ```
			 * 首帧正文="HARNESS Failed to load plugins dsh-jiuli"
			 * 救援：启动层级 = rescue 原因=浏览器侧插件加载失败
			 * 引擎已退出…准备自动重启（第 1 次）
			 * ```
			 *
			 * 用户的原话是「你他妈想让我手机死机是吧」—— 这个兜底就是为此加的。
			 *
			 * **兜底原则：本插件只是「多一个电脑页签」的增强件，
			 * 它坏了绝不能把整个界面带走。** 任何一步失败都只放弃那一步。
			 */
			const guard = (label, fn, fallback) => {
				try {
					return fn();
				} catch (error) {
					// 诊断出口②：被吞掉的异常必须留痕，否则「改了没效果」永远查不出原因。
					try {
						window.__dshRemoteGuardError = String(label) + ': ' + String(error);
					} catch (probeError) {
						/* 诊断失败绝不影响主流程 */
					}
					// 只打日志，绝不外抛：外抛 = 整页加载失败。
					try {
						if (ctx.logger && typeof ctx.logger.warn === 'function') {
							ctx.logger.warn('[dsh-jiuling-remote-mobile] ' + label + ' 失败（已忽略）: ' + error);
						}
					} catch (ignored) {
						/* 连日志都打不出来也不能炸 */
					}
					return fallback;
				}
			};

			const injected = () => ({
				/*
				 * ## ⚠️⚠️ `ctx.uiWorkspace` 必须判空（1.2.80 修）
				 *
				 * 原来写的是直接调：
				 *
				 *     listDirectory: (path, signal) => ctx.uiWorkspace.listDirectory(path, signal)
				 *
				 * `ctx.uiWorkspace` 一旦是 `undefined`（服务没装、或加载顺序差），
				 * 这句就是 `TypeError: Cannot read property 'listDirectory' of undefined`。
				 * 而它被调用的地方是（见下面的加载 effect）：
				 *
				 *     Promise.resolve(listDirectory(path))
				 *
				 * **`listDirectory(path)` 是同步求值的** —— 抛错发生在
				 * `Promise.resolve` 被调用**之前**，所以 `.then(成功, 失败)` 根本
				 * 没挂上，`patch({loading:false})` 永远不执行 ⇒ 界面**永远「加载中…」**。
				 *
				 * 不报错、不提示，就是转圈。用户截图里的「手机页签一直加载中」
				 * 就是这个。
				 */
				listDirectory: (path, signal) => {
					if (ctx.uiWorkspace === undefined || ctx.uiWorkspace === null
						|| typeof ctx.uiWorkspace.listDirectory !== 'function') {
						return Promise.reject(new Error('官方目录服务不可用（uiWorkspace 未就绪）'));
					}
					return ctx.uiWorkspace.listDirectory(path, signal);
				},
				/* 同上：也必须判空（1.2.80） */
				createDirectory: (path, name) => {
					if (ctx.uiWorkspace === undefined || ctx.uiWorkspace === null
						|| typeof ctx.uiWorkspace.createDirectory !== 'function') {
						return Promise.reject(new Error('官方目录服务不可用（uiWorkspace 未就绪）'));
					}
					return ctx.uiWorkspace.createDirectory(path, name);
				},
				/* 同上（1.2.80） */
				openSession: (sessionId) => {
					if (ctx.uiWorkspace === undefined || ctx.uiWorkspace === null
						|| typeof ctx.uiWorkspace.openSession !== 'function') {
						return Promise.reject(new Error('官方目录服务不可用（uiWorkspace 未就绪）'));
					}
					return ctx.uiWorkspace.openSession(sessionId);
				},
				/**
				 * 把宿主刚建好的会话登记进本地列表。
				 *
				 * 走官方 client 的 `sessions.create({sessionId, cwd})`：
				 * `SessionManager.create()` 在 RPC 回执后立刻
				 * `recordMutation({kind:'placeholder'})` 把这一行写进列表快照，
				 * 于是 `openSession()` 当场就能解析到这个 id。
				 * **不登记就直接 `openSession()` 是竞态**，会抛
				 * `sessions.retain: unknown session` 并被静默吞掉
				 * （详见组件里 `settleLocal` 的注释）。
				 *
				 * 传 `cwd`（宿主回执里的**手机本地**路径）而不是 `workspaceId`：
				 * 宿主侧已经把这个会话挂进工作区了，这里只需要让客户端列表
				 * 认识它；带上 cwd 才能让列表把它分进正确的工作区分组。
				 *
				 * 宿主侧已建过该 id ⇒ `createOrAdopt` 看到活着的 agent 直接返回，
				 * 不会重复建。
				 *
				 * @param sessionId - 会话 id。
				 * @param cwd - 宿主回执里的本地 cwd（两端只在 id 上对齐）。
				 */
				registerLocal: (sessionId, cwd) => {
					const options = { sessionId };
					if (cwd !== undefined && cwd !== '') options.cwd = cwd;
					return ctx.sessions.create(options);
				},
				/** 兜底：拉一次全量会话列表。 */
				refreshSessions: () => ctx.sessions.refresh(),
				/**
				 * 官方的模型目录服务（`super(ctx, "modelDirectories")`）。
				 *
				 * 「刷新」按钮要让它失效重载 —— 官方目录**带缓存**，光把宿主那边的
				 * 数据换掉，前端仍然显示旧值（见 `refreshAll` 的注释）。
				 * `ctx.get` 拿不到就返回 `undefined`，组件里会跳过这一步。
				 */
				modelDirectories: ctx.get === undefined ? undefined : ctx.get('modelDirectories'),
				/**
				 * 把一个会话标记成「跑在电脑上」。
				 *
				 * 建完远程会话**当场**登记，用户不必等下一次全量拉取就能看到
				 * 正确的模型列表（见 `remoteSessionIds` 的注释）。
				 *
				 * @param sessionId - 会话 id。
				 */
				markRemote: (sessionId) => {
					if (typeof sessionId === 'string' && sessionId !== '') remoteSessionIds[sessionId] = true;
				},
				/**
				 * 重拉一次「电脑镜像工作区 slug 集合」。
				 *
				 * ⚠️ **必须在「刚给某个电脑目录建好工作区」之后调**：宿主
				 * `ensureDesktopWorkspace()` 每碰到一个新的电脑目录就往 `workspaceMap`
				 * 里加一条 slug，而前端这份集合是**快照**。不重拉的话，新加的镜像工作区
				 * 在侧边栏里点进去仍会被判成「本地」—— 正是用户报的那个 bug。
				 */
				refreshMirrorSlugs: () => loadMirrorSlugs(),
				/**
				 * 重拉电脑侧展示元数据（时间 + 置顶）。
				 *
				 * 同步完成、配对成功后调 —— 那正是元数据刚变的时刻。
				 * 返回 Promise，永不 reject。
				 */
				refreshDesktopMeta: () => loadDesktopMeta(),
				/** 解绑后把所有会话退回「本地」（电脑已经不在了）。 */
				clearRemote: () => {
					remoteSessionIds = {};
					mirrorSlugs = {};
				},
			});

			// 拉一次全量远程会话清单（重启 App 后已有会话也要能正确识别）。
			guard('loadRemoteSessionIds', () => loadRemoteSessionIds());
			// 拉一次镜像工作区 slug 集合（隔离的**真正判据**，见 `mirrorSlugs`）。
			guard('loadMirrorSlugs', () => loadMirrorSlugs());
			/*
			 * 拉一次「镜像工作区标题 → 电脑路径」清单。
			 *
			 * 这是 `probeHeroWorkspaceDesktopPath()` 的比对依据 —— 新建对话页
			 * 选中电脑工作区时，靠它把选择器上的文字翻译成电脑路径
			 *（修用户报的「直接选已存在工作区时模型/预设不刷新」）。
			 */
			guard('loadMirrorDesktopTitles', () => loadMirrorDesktopTitles());

			/*
			 * ## 拉电脑侧的展示元数据（用户 2026-10-29 两个报障）
			 *
			 *   ① 「同步过来的对话，你没有按时间顺序进行一个排序」
			 *   ② 「电脑的某些对话人家是置顶的状态，你也没有把置顶的状态同步过来」
			 *
			 * 这两件事手机本地都**没有真实数据**（理由见 `applyDesktopClock` 与
			 * `applyPinFromDesktop` 的注释），必须由宿主告诉前端。
			 *
			 * 顺序有讲究：**先包装 `sessions.list`，再拉数据**。
			 * 反过来的话，第一次拉到数据后要等下一次快照变化才生效 ——
			 * 而那可能是一分钟后，用户看到的就是「没修」。
			 */
			guard('wrapSessionListClock', () => {
				if (ctx.sessions === undefined || ctx.sessions === null) return;
				var before = ctx.sessions.list;
				var wrapped = wrapSessionListClock(before);
				ctx.sessions.list = wrapped;
				/*
				 * ## ⚠️⚠️ 光改 `ctx.sessions.list` 是不够的（本轮排序/置顶失效的真正原因）
				 *
				 * 官方侧边栏读数据走的是 **slot hook**，不是直接读
				 * `ctx.sessions.list`。链路如下：
				 *
				 *   `dsh-client-ui-session/lib/client.js:1106`
				 *     ctx.slots.provideRoot({ hooks: { sessions: ctx.sessions.list } })
				 *   → `dsh-client-ui-renderer/lib/client.js:1681` `rebuildRootBinding()`
				 *     中的 `copyUnique` 把 `contribution.hooks` 里的值**按引用拷贝**
				 *   → 组件里 `<WorkspaceBrowser useSessions={…}>`
				 *   → `useSessions((state) => state)` → `.getSnapshot()`
				 *
				 * 所以：`provideRoot(...)` 在 **ui-session 插件加载那一刻**
				 * 就已经把**旧对象引用**写进了 `_rootContributions`。
				 * 我们后面再赋值 `ctx.sessions.list = wrapped`，
				 * 只是把**属性**指向新对象 —— hook 那份引用**没变**。
				 *
				 * 后果：排序与置顶的前端代码**从来没被界面调用过**。
				 * 这解释了为什么「数据已经正确传到手机了，排序和置顶就是不变」。
				 *
				 * 修法：把 hook 那份引用也换掉，并重建 root binding。
				 * 引用是从 `slots._rootContributions` 里取的（它是普通实例数组，
				 * 官方没有封装），这里做完全防御：任何一步拿不到
				 * 都**静默跳过**（不能因为官方内部结构变了就让整个插件报错）。
				 */
				try {
					if (wrapped !== before && ctx.slots !== undefined && ctx.slots !== null) {
						var contribs = ctx.slots._rootContributions;
						if (Array.isArray(contribs)) {
							var swapped = 0;
							for (var at = 0; at < contribs.length; at += 1) {
								var hooks = contribs[at] === null || contribs[at] === undefined
									? undefined
									: contribs[at].hooks;
								if (hooks === null || hooks === undefined || typeof hooks !== 'object') continue;
								if (hooks.sessions === before) {
									hooks.sessions = wrapped;
									swapped += 1;
								}
							}
							clockDiag.hook = swapped > 0 ? ('\u2713' + swapped) : '\u2717';
							if (swapped > 0 && typeof ctx.slots.rebuildRootBinding === 'function') {
								ctx.slots.rebuildRootBinding();
							}
						}
					}
				} catch (error) {
					void debugLog('替换 sessions hook 引用失败: ' + String(error && error.message ? error.message : error));
				}
			});
			guard('loadDesktopMeta', () => loadDesktopMeta());

			/*
			 * ## 让电脑上置顶的对话在手机上也置顶（用户 2026-10-29 报障③）
			 *
			 * 订阅 `sessions.list`：**新会话进来时再试一次 pin**。
			 *
			 * 为什么必须等快照变化：宿主 `workspaceRegistry.pinSession()`
			 * 校验 `sessionKnown(id)`，而同步刚建好的会话要先经过
			 * 「宿主建成 → 客户端列表认识它」这两步。`apply()` 那一刻
			 * 快照里可能还没有它，直接 pin 会拿到 `session/unknown`。
			 *
			 * 所以策略是「快照一变就试，成功过的记下不再试」——
			 * 幂等、零轮询、失败自动重试（10 秒退避）。
			 */
			guard('applyPinFromDesktop', () =>
				ctx.effect(() => {
					const list = ctx.sessions === undefined ? undefined : ctx.sessions.list;
					if (list === undefined || list === null || typeof list.subscribe !== 'function') return;
					const workspaces = ctx.get === undefined ? undefined : ctx.get('workspaces');
					// 先试一次：快照里可能已经有会话了（App 重启后）。
					try {
						applyPinFromDesktop(list, workspaces);
					} catch (error) {
						/* 置顶是展示增强，失败绝不影响主流程 */
					}
					/*
					 * ⚠️ 必须注册重试钩子：上面那次试的时候
					 * `desktopPins` 大概率**还是空的**（数据还在路上），
					 * 而订阅只在快照变化时触发 —— 数据到位时快照往往没变。
					 * 没这个钩子就是「置顶永远不生效」。
					 */
					pinRetryHooks.push(function () {
						try {
							applyPinFromDesktop(list, workspaces);
						} catch (error) {
							/* 同上 */
						}
					});
					const off = list.subscribe(() => {
						try {
							applyPinFromDesktop(list, workspaces);
						} catch (error) {
							/* 同上 */
						}
					});
					return () => {
						if (typeof off === 'function') off();
					};
				}, 'dsh-remote-mobile: sync desktop pins'),
			);

			// 把「按工作区筛模型目录」接到官方服务上。
			guard('patchModelDirectories', () =>
				ctx.effect(
					() => patchModelDirectories(ctx, makeCwdOf(ctx)),
					'dsh-remote-mobile: workspace-scoped model catalog',
				),
			);

			// 把「按工作区筛预设名单」接到官方 RPC 上。
			//
			// ⚠️⚠️ **必须走 `ctx.inject([...], cb)` 子作用域，不能直接用外层 `ctx`。**
			//
			// `remote.agentPresets` 是 `dsh-api-remotes` 动态 provide 的**子命名空间**。
			// 本插件的 `inject` 只声明了父服务 `'remote'`，于是外层 `ctx` 上读
			// `ctx.remote.agentPresets` 会**直接抛**：
			//
			//     Error: cannot get property "remote.agentPresets" without inject
			//
			// 而这句话被 `guard` 吞掉后，`patchAgentPresets` 的第一行就 return 了 ——
			// `list` 一次都没被包过。真机取证（1.0.34，`window.__dshRemoteGuardError`）：
			//
			//     patchAgentPresets: Error: cannot get property "remote.agentPresets" without inject
			//
			// 这才是 1.0.25→1.0.33 九个版本「改了没效果」的**唯一**根因。之前注释里
			// 归因的「Proxy 没解包」「roster 没人重请求」都不是主因 —— 那些代码压根
			// 没跑到过。
			//
			// ⚠️ 但**也不能把 `'remote.agentPresets'` 写进 `export const inject`**：
			// 那是**静态**声明，子服务在未配对/未挂载时不存在 ⇒ `Fiber._refresh()`
			// 里 `epoch = INACTIVE` ⇒ **整个插件一次都不激活**（连目录选择器都没了）。
			// `ctx.inject()` 是**运行期**动态作用域：子服务就绪才回调，不阻塞本插件。
			guard('patchAgentPresets', () =>
				ctx.inject(['remote.agentPresets', 'uiWorkspace', 'sessions', 'workspaces', 'slots'], (scope) => {
					scope.effect(
						() => patchAgentPresets(scope, makeCwdOf(scope)),
						'dsh-remote-mobile: workspace-scoped agent presets',
					);
				}),
			);

			// 负 priority：single slot 取 entries[0]，entries 按 priority 升序 ⇒ 最先渲染。
			const register = (name) =>
				ctx.slots.register(
					{
						name: name,
						priority: -1,
						inject: injected,
					},
					RemoteDirectoryFlow,
				);

			guard('slots.inject', () =>
				ctx.slots.inject('conversation.hero.workspace.directoryFlow', () =>
					ctx.slots.inject('sidebar.workspaces.directoryFlow', function* () {
						yield register('conversation.hero.workspace.directoryFlow');
						yield register('sidebar.workspaces.directoryFlow');
					}),
				),
			);

			guard('subscribe', () =>
				ctx.effect(() => subscribe(ctx), 'dsh-remote-mobile: assistant stream bridge'),
			);

			// 「同步对话」：把菜单项接进工作区行的「⋯」菜单。
			//
			// ⚠️ 必须先拉一次镜像工作区清单：菜单项要按工作区标题反查电脑路径，
			// 清单为空时那一项会静默不出现（不报错，但功能等于没有）。
			guard('refreshMirrorWorkspaces', () => {
				ctx.effect(() => {
					refreshMirrorWorkspaces();
					// ⚠️⚠️ **原来这里有一个 `setInterval(refreshMirrorWorkspaces, 5000)`**
					// —— 每 5 秒打一次隧道，**7×24 不停**，一小时 720 次。
					//
					// 这是纯隐形浪费，理由：
					//   · 这个清单只在**用户选了/新增了电脑工作区**之后才会变；
					//   · 而那种操作一定伴随一次工作区列表变化，宿主会推事件；
					//   · 就算事件漏了，用户打开「⋯」菜单时再拉一次也来得及
					//    （菜单是点出来的，不是常驻的）。
					//
					// 所以改成**事件驱动 + 极低频兜底**：
					//   · 主路：`api-workspace/added` 之类的工作区事件（见下面的订阅）；
					//   · 兜底：每 10 分钟一次，只为覆盖「事件没覆盖到的边角」。
					//
					// 用户第一准则是「不费流量」，720 次/小时 vs 6 次/小时，
					// 差的不是一点。而功能上没有任何损失：菜单点开前清单一定是新的。
					const timer = setInterval(refreshMirrorWorkspaces, 10 * 60 * 1000);
					// 工作区真的变了就重拉（这是唯一真正需要的时机）。
					//
					// ⚠️ 事件名必须用官方的 `domain/changed`（见
					// `dsh-api-workspace-controller/lib/index.js:48`）。官方**没有**
					// `api-workspace/added` 这种事件 —— 那是 `api-session/*` 那一套的
					// 命名习惯，套到工作区上是我猜错了，订阅会静默收不到任何东西。
					//
					// 判据与官方 `changed()` 一致：
					//   · `change.domain === 'workspace'` ⇒ 工作区集合/顺序变了；
					//   · `change.table === 'workspaces'` ⇒ 某条工作区记录变了。
					const off = ctx.on('domain/changed', function (change) {
						if (change === null || typeof change !== 'object') return;
						if (change.domain !== 'workspace' && change.table !== 'workspaces') return;
						refreshMirrorWorkspaces();
					});
					return () => {
						clearInterval(timer);
						if (typeof off === 'function') off();
					};
				}, 'dsh-remote-mobile: mirror workspace index');
			});

			guard('installSyncMenu', () =>
				ctx.effect(() => installSyncMenu(ctx), 'dsh-remote-mobile: 同步对话菜单'),
			);

			/*
			 * 同步进度浮层（见 `mountSyncProgress`）。
			 *
			 * ⚠️ 用 `ctx.effect` 注册，卸载时 `dispose` 会自动被调 ——
			 * 这个浮层是原生 DOM，不受 React 回收，不主动清就会残留在
			 * 侧边栏里（面板重建后还挂着一条假进度）。
			 */
			/*
			 * **App 回到前台时补拉待审批的 ask 弹窗。**
			 *
			 * 粉丝反馈（2026-10-28，作者转述）：「他电脑上面已经有一个会话了，
			 * 但此时他是没有在手机 APP 内的，它已经发起了一个 ask 弹窗，
			 * 然后此时用户去打开我们的 APP，它虽然消息同步过来了，但是弹窗
			 * 没有同步过来，用户就无法审批。」
			 *
			 * 为什么后端已经有了重连补拉，这里还要再来一次：用户可能**一直没有
			 * 断开事件流**（App 切后台、连接还在），那种情况下"重连"这个时机
			 * 根本不会发生 —— 只有前端知道自己回来了。
			 *
			 * 后端 `pulledAskIds` 去重保证不会重复弹窗。
			 */
			guard('pullAsksOnForeground', () =>
				ctx.effect(
					() => {
						var onVisible = function () {
							if (typeof document !== 'undefined' && document.hidden === true) return;
							// 未配对就没必要打这一趟（桥会直接回 `{ok:false}`）。
							if (pairedRef.current !== true) return;

							bridge('pullAsks', {}).then(
								function () { /* 成功的补拉会自己弹窗 */ },
								function () { /* 未配对/电脑离线 ⇒ 静默 */ },
							);
						};
						if (typeof document !== 'undefined' && document.addEventListener) {
							document.addEventListener('visibilitychange', onVisible);
						}
						// 挂载时先试一次：侧边栏打开往往就是用户"刚回来"。
						onVisible();
						return function () {
							if (typeof document !== 'undefined' && document.removeEventListener) {
								document.removeEventListener('visibilitychange', onVisible);
							}
						};
					},
					'dsh-remote-mobile: 前台补拉 ask',
				),
			);

			/* 在电脑工作区行前渲染在线状态圆点（见 `startOnlineDotWatch`）。 */
			guard('startOnlineDotWatch', () =>
				ctx.effect(
					() => startOnlineDotWatch(),
					'dsh-remote-mobile: 在线状态圆点',
				),
			);

			/* 电脑在线状态探测循环（见 `startDesktopOnlineWatch`）。 */
			guard('startDesktopOnlineWatch', () =>
				ctx.effect(
					() => startDesktopOnlineWatch(),
					'dsh-remote-mobile: 电脑在线状态',
				),
			);

			/*
			 * 插件卸载时把「页面还活着」标记复位。
			 *
			 * 不做的后果：用户在设置里关掉插件后，仍在飞的同步回调
			 *（`autoSyncDesktop` 的 Promise）继续往 `autoSyncStateRef` 写值、
			 * 继续驱动已被销毁的 DOM —— 虽然大多会被 `isConnected` 挡住，
			 * 但状态是脏的。这里显式收尾更干净。
			 */
			guard('autoSyncAliveReset', () =>
				ctx.effect(
					() => () => { autoSyncAlive.current = false; },
					'dsh-remote-mobile: 同步存活标记',
				),
			);

			guard('mountSyncProgress', () =>
				ctx.effect(
					() => {
						const handle = mountSyncProgress(
							() => autoSyncStateRef.value,
							() => true,
						);
						return () => handle.dispose();
					},
					'dsh-remote-mobile: 同步进度浮层',
				),
			);
		}

		exports.apply = apply;
		exports.inject = inject;
		return module.exports;
	},
});
