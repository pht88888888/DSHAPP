/**
 * dsh-jiuling-remote-desktop —— 电脑端（client half，跑在 DSH Web GUI 里）。
 *
 * ## 这个文件做一件事
 *
 * 在**设置**里加一页「手机远程」，让客户完成配对：
 *
 * ```
 *   ① 填激活码 → 注册（服务器建隧道 + 建 DNS + 发令牌）
 *   ② 显示二维码 + 6 位配对码 → 客户在手机上输/扫
 *   ③ 完成
 * ```
 *
 * ## ⚠️ 为什么客户端半边必须是「薄」的
 *
 * 所有真正的动作（调配对服务器、下载 cloudflared、落盘凭据）都在 **host 半边**
 * 做。这里只负责界面 + 一次 `fetch` 到 `/api/dsh-remote/pair/*`。
 *
 * 原因有两条，都很实在：
 * 1. **浏览器侧拿不到文件系统**：写 `pair.json`、`chmod +x cloudflared` 都得在
 *    Node 侧做；
 * 2. **浏览器侧不该持有凭据**：`accessToken` 是这台电脑的门钥匙，它只需要存在于
 *    引擎进程的内存与磁盘上，没必要再往页面里塞一份。
 *
 * 所以这一页的 `fetch` 响应里**没有令牌**，只有状态、二维码 SVG 和提示文字。
 *
 * ## ⚠️ 语法约束（旧内核）
 *
 * 手机端有按 chrome73 降级编译的前端 bundle（`webview-compat.zip`）。电脑端的
 * 浏览器虽新，但**两端共用同一份源码风格**，所以这里同样**不用**可选链 `?.`、
 * 空值合并 `??`、`Object.fromEntries`、`Array.flat` 这些 chrome80+ 语法 ——
 * 免得将来哪一端复用这段代码时踩坑。
 *
 * @module dsh-jiuling-remote-desktop/client
 */

window.__ModuleLoader__.load({
	id: 'dsh-jiuling-remote-desktop',
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });

		var react = require('react');
		var h = react.createElement;

		/**
		 * 官方 UI 原子组件。
		 *
		 * ⚠️ **这是「长得像官方」的唯一正解**：官方各 client 包自己也是这么
		 * `require` 的。自己手搓 `div` + 写死颜色，代价是实打实的 —— 手机端那版
		 * 曾经写死 `background:var(--dsw-alias-bg-primary,#1b1b1f)`，而
		 * **`--dsw-alias-bg-primary` 这个令牌根本不存在**，于是永远走深色 fallback，
		 * 表现是「两大坨黑色、跟官方 UI 完全脱钩」。
		 */
		var primitives = require('@deepseek-ai/dsh-client-ui-primitives');
		var Button = primitives.Button;
		var Input = primitives.Input;
		var Pill = primitives.Pill;

		/** 配对接口（挂在 DSH 自己的 Web 端口上，见 host 半边的 `PAIR_ROUTE`）。 */
		var PAIR_API = '/api/dsh-remote/pair';

		/**
		 * 调一条配对接口。
		 *
		 * @param action - `/status` / `/register` / `/handshake` / `/cloudflared` / `/unpair`。
		 * @param body - 请求体；不传就发 GET。
		 * @returns 结果的 Promise（永不 reject，失败也返回 `{ok:false, message}`）。
		 */
		function callPair(action, body) {
			var options = { cache: 'no-store' };
			if (body !== undefined) {
				options.method = 'POST';
				options.headers = { 'content-type': 'application/json' };
				options.body = JSON.stringify(body);
			}
			return fetch(PAIR_API + action, options).then(
				function (response) {
					return response.json().then(
						function (payload) {
							return payload;
						},
						function () {
							return { ok: false, message: '宿主返回的不是 JSON（HTTP ' + response.status + '）' };
						},
					);
				},
				function (error) {
					return {
						ok: false,
						message: '无法连接宿主服务，请确认 DSH 仍在运行：' + String((error && error.message) || error),
					};
				},
			);
		}

		// ---------------------------------------------------------------- 样式
		//
		// ⚠️ 只声明**真实存在**的设计令牌。写不存在的令牌不会报错，会**静默**走
		// fallback，那是「看着不对劲又查不出原因」的典型来源。

		var CSS_TEXT = {
			page: 'display:flex;flex-direction:column;gap:16px;padding:4px 0 24px;max-width:640px;',
			card: 'display:flex;flex-direction:column;gap:12px;padding:16px;border:1px solid var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-md,10px);',
			title: 'color:var(--dsw-alias-label-primary);font-size:14px;font-weight:510;line-height:22px;margin:0;',
			desc: 'color:var(--dsw-alias-label-secondary);font-size:13px;line-height:20px;margin:0;',
			hint: 'color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:18px;margin:0;',
			row: 'display:flex;flex-wrap:wrap;align-items:center;gap:8px;',
			gap: 'flex:1 1 0;',
			error: 'color:var(--dsw-alias-state-error-primary);font-size:13px;line-height:20px;margin:0;',
			ok: 'color:var(--dsw-alias-state-success-primary,#3fb950);font-size:13px;line-height:20px;margin:0;',
			// ⚠️ 现在是 `<img>`（原来是承载 SVG 源码的 div，见 M6 那段注释），
			// 所以要自己带尺寸：`scale:1` 下 SVG 只有 41×41 像素，不放大看不清；
			// `image-rendering:pixelated` 让放大后的模块保持锐利（不然会被平滑糊掉）。
			qrBox:
				'display:block;width:164px;height:164px;padding:12px;box-sizing:border-box;background:#fff;border-radius:var(--dsw-radius-md,10px);image-rendering:pixelated;',
			code: 'font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:26px;font-weight:600;letter-spacing:6px;color:var(--dsw-alias-label-primary);',
			kv: 'display:flex;gap:8px;align-items:baseline;',
			kvKey: 'color:var(--dsw-alias-label-tertiary);font-size:12px;min-width:64px;',
			kvVal: 'color:var(--dsw-alias-label-primary);font-size:13px;word-break:break-all;',
			// 下载进度条：只沿用本文件已经在用的令牌（label-tertiary 做槽、
			// label-primary 做已下载部分），不引入任何未经验证的令牌。
			progressTrack: 'height:4px;border-radius:999px;background:var(--dsw-alias-label-tertiary);opacity:0.25;overflow:hidden;',
			progressBar: 'height:100%;border-radius:999px;background:var(--dsw-alias-label-primary);transition:width 200ms linear;',
		};

		/**
		 * 把 CSS 文本解析成 React 的 style **对象**。
		 *
		 * ⚠️ **这一步不能省**：React 的 `style` prop 只接受对象，给字符串会当场抛
		 * `Minified React error #62`，组件随即被 slot 系统 `abdicate` 退役 ——
		 * 表现是「设置里根本没有这一页」，而控制台只有一条难懂的 #62。
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

		var CSS = {};
		for (var styleKey in CSS_TEXT) CSS[styleKey] = toStyle(CSS_TEXT[styleKey]);

		/** 一个「键: 值」行。 */
		function kv(key, value) {
			return h(
				'div',
				// ⚠️ 这些行会被直接塞进 children 数组，**必须有 key**：
				// 数组形式的 children 少了 key，React 每次都告警
				//（"Each child in a list should have a unique key prop"），
				// 在开发面板里刷屏、掩盖真正的问题。
				{ key: 'kv-' + key, style: CSS.kv },
				h('span', { style: CSS.kvKey }, key),
				h('span', { style: CSS.kvVal }, value),
			);
		}

		// ------------------------------------------------------------ 配对页面

		/**
		 * 「手机远程」设置页。
		 *
		 * 三种状态：未配对（填激活码）/ 已配对（显示信息 + 生成配对码）/ 生成中。
		 */
		function RemoteDesktopPanel() {
			var statusState = react.useState(null);
			var status = statusState[0];
			var setStatus = statusState[1];

			var codeState = react.useState('');
			var code = codeState[0];
			var setCode = codeState[1];

			var busyState = react.useState('');
			var busy = busyState[0];
			var setBusy = busyState[1];

			var msgState = react.useState(null);
			var message = msgState[0];
			var setMessage = msgState[1];

			var pairState = react.useState(null);
			var pair = pairState[0];
			var setPair = pairState[1];

			// 下载进度（只在注册期间有意义）。
			//
			// ⚠️ 为什么必须有这个：`/register` 在**没有现成 cloudflared** 的机器上
			// 会阻塞着下载 55 MB，实测 67 秒（旧文案写「2–5 秒」）。这期间前端
			// 拿不到任何回应，界面完全静止 —— 客户会以为卡死，然后重复点或放弃。
			//
			// host 半边早就把进度记账好了（`pair.js` 的 `download.received/total`，
			// 注释写着「UI 要显示进度」），而且下载期间 `/status` 能并发响应
			//（实测 27 次采样全部拿到递增的字节数），所以这里只差把它读出来。
			var dlState = react.useState(null);
			var download = dlState[0];
			var setDownload = dlState[1];

			/** 刷新状态。 */
			var reload = react.useCallback(function () {
				return callPair('/status').then(function (payload) {
					if (payload && payload.ok === true) setStatus(payload);
					else setMessage({ kind: 'error', text: (payload && payload.message) || '读取状态失败' });
				});
			}, []);

			react.useEffect(function () {
				reload();
			}, [reload]);

			// 下载进行中才轮询进度；结束后停掉并清空。
			//
			// ⚠️ 触发条件必须同时包含 `environment`：下载已经从 `/register` 里
			// 搬到 `/environment`（见 host 半边注释）。只留 `register` 的话，
			// 「下载隧道组件」这条路上**一点进度都看不到**，55 MB 下几十秒
			// 界面静止，用户又会以为卡死 —— 那正是这次要修的另一半问题。
			react.useEffect(
				function () {
					if (busy !== 'environment' && busy !== 'register') {
						setDownload(null);
						return undefined;
					}
					var alive = true;
					var timer = setInterval(function () {
						callPair('/status').then(function (payload) {
							if (!alive) return;
							if (payload && payload.ok === true) setDownload(payload.download || null);
						});
					}, 500);
					return function () {
						alive = false;
						clearInterval(timer);
					};
				},
				[busy],
			);

			/** 提交激活码。 */
			var submit = react.useCallback(
				function () {
					if (code.trim() === '') {
						setMessage({ kind: 'error', text: '请先填写激活码' });
						return;
					}
					setBusy('register');
					setMessage(null);
					callPair('/register', { code: code.trim() }).then(function (payload) {
						setBusy('');
						if (payload && payload.ok === true) {
							setStatus(payload);
							setMessage({ kind: 'ok', text: payload.message || '配对成功' });
						} else {
							setMessage({ kind: 'error', text: (payload && payload.message) || '注册失败' });
						}
					});
				},
				[code],
			);

			/**
			 * 环境准备：检查依赖，缺就下载。
			 *
			 * ## ⚠️ 这一步必须排在「填激活码」**前面**（用户报障）
			 *
			 * 用户原话：
			 *   「用户经常会遇到绑定的时候依赖下载超时，导致激活码也失效了，
			 *    然后又安装不上的问题。改成用户可以自己提前点击下载依赖，
			 *    就是环境检查通过了之后，再输入激活码。」
			 *
			 * 以前下载是在 `/register` 里做的，而 `/register` 的第一步就已经把
			 * 激活码交给服务端了 —— 下载一超时，激活码**已经被消费**，
			 * 用户却看到「绑定失败」，重填重试就撞上「设备不符」。
			 *
			 * 现在拆成两步：这里慢慢下、可以重试，下好了再让用户填码。
			 */
			/*
			 * **下载渠道**（需求 4，2026-10-28）。
			 *
			 * 用户要求：「改成多个渠道嘛，让用户自行选择……走官方的渠道，
			 * 而且我建议默认就是走官方的。」
			 *
			 * `''` = 自动（先官方再中转），其余值是**强制只用那一个**。
			 */
			var channelState = react.useState('');
			var channel = channelState[0];
			var setChannel = channelState[1];

			var prepareEnv = react.useCallback(function () {
				setBusy('environment');
				setMessage(null);
				/*
				 * 只有用户明确选了某个渠道才传 `channel`。
				 * 选"自动"时传空对象，让 host 按默认顺序（官方 → 中转）依次尝试。
				 */
				callPair('/environment', channel === '' ? {} : { channel: channel }).then(function (payload) {
					setBusy('');
					/*
					 * ⚠️ 全渠道失败时 `message` 里已经带了**可直接复制的手动安装
					 * 提示词**（见 host 半边 `MANUAL_INSTALL_PROMPT`）。这里原样显示，
					 * 不要截断或改写 —— 用户就是要复制它。
					 */
					setMessage({
						kind: payload && payload.ok === true ? 'ok' : 'error',
						text: (payload && payload.message) || '未知结果',
					});
					return reload();
				});
			}, [reload, channel]);

			/** 生成配对码 + 二维码。 */
			var makeCode = react.useCallback(function () {
				setBusy('handshake');
				setMessage(null);
				callPair('/handshake', {}).then(function (payload) {
					setBusy('');
					if (payload && payload.ok === true) setPair(payload);
					else setMessage({ kind: 'error', text: (payload && payload.message) || '生成失败' });
				});
			}, []);

			/** 确保 cloudflared 在位（需要时自动下载）。 */
			var ensureBinary = react.useCallback(function () {
				setBusy('cloudflared');
				setMessage(null);
				callPair('/cloudflared', {}).then(function (payload) {
					setBusy('');
					setMessage({
						kind: payload && payload.ok === true ? 'ok' : 'error',
						text: (payload && payload.message) || '未知结果',
					});
					return reload();
				});
			}, [reload]);

			if (status === null) {
				return h('div', { style: CSS.page }, h('p', { style: CSS.hint }, '正在读取配对状态…'));
			}

			var children = [];

			// ---- 状态卡片 ----
			//
			// ⚠️ `Pill` **只有 `active` 一个视觉开关**（`Pill.js` 的签名是
			// `{ active = false, className, children, onClick, ...rest }`），
			// 传 `tone: 'success'` 会被 `...rest` 透传到 DOM 上变成
			// `<span tone="success">` —— React 会告警、样式也不会变。
			// 所以「已配对 / 未配对」用 `active` 区分，再用文字说清是哪一种。
			var statusRow;
			if (status.paired === true) {
				statusRow = h(
					'div',
					{ style: CSS.row },
					h(Pill, { active: true }, '已配对'),
					h('span', { style: CSS.hint }, status.hostname),
				);
			} else {
				statusRow = h(
					'div',
					{ style: CSS.row },
					h(Pill, null, '未配对'),
					h('span', { style: CSS.hint }, '还没有绑定手机，请填写激活码'),
				);
			}
			children.push(
				h(
					'div',
					{ key: 'status', style: CSS.card },
					h('h3', { style: CSS.title }, '这台电脑的手机远程'),
					h(
						'p',
						{ style: CSS.desc },
						'配对后，手机上的「电脑文件夹」入口就能直接进入这台电脑的目录、在这台电脑上开对话。公网只传文字，不映射桌面。',
					),
					statusRow,
					kv('平台', status.platform),
					kv(
						'隧道组件',
						status.cloudflared === ''
							? '未安装（点右边按钮下载）'
							: status.cloudflared,
					),
					status.paired === true
						? h(
								'div',
								{ style: CSS.row },
								h(
									Button,
									{
										// ⚠️ 已配对但组件没装好时，这个按钮**就是补下载入口**，
										// 所以给它主按钮样式，别让用户以为它只是「检查一下」。
										// 场景：用户在旧版本里配对成功但下载超时（本次修的正是这个），
										// 升级后不该逼他解绑重配一遍。
										variant: status.cloudflared === '' ? 'primary' : 'outline',
										size: 'sm',
										onClick: ensureBinary,
										disabled: busy !== '',
									},
									busy === 'cloudflared'
										? '正在下载…'
										: status.cloudflared === ''
											? '下载隧道组件'
											: '检查隧道组件',
								),
								h(
									Button,
									{
										variant: 'ghost',
										size: 'sm',
										onClick: function () {
											setBusy('unpair');
											callPair('/unpair', {}).then(function (payload) {
												setBusy('');
												setPair(null);
												setMessage({ kind: 'ok', text: (payload && payload.message) || '已解绑' });
												return reload();
											});
										},
										disabled: busy !== '',
									},
									'解绑',
								),
							)
						: null,
				),
			);

			// ---- 环境准备卡片（未配对时，**排在激活码前面**）----
			//
			// 顺序就是产品逻辑：先把依赖装好，再让用户填激活码。
			// 反过来的话，用户填完码才去下载 55 MB，一超时激活码就废了
			//（详见 host 半边 `/register` 的注释）。
			//
			// ⚠️ `status.cloudflared` 是空串表示没找到可执行文件。
			var envReady = typeof status.cloudflared === 'string' && status.cloudflared !== '';
			if (status.paired !== true) {
				children.push(
					h(
						'div',
						{ key: 'env', style: CSS.card },
						h(
							'div',
							{ style: CSS.row },
							h('h3', { style: CSS.title }, '第 1 步：准备环境'),
							envReady === true ? h(Pill, { active: true }, '已就绪') : h(Pill, null, '未就绪'),
						),
						h(
							'p',
							{ style: CSS.desc },
							'手机远程需要一个隧道组件（约 55 MB）。请**先点下面的按钮把它下好**，再去填激活码 —— '
								+ '这样万一网络慢、下载失败，你的激活码还没用掉，重试就行。',
						),
						envReady === true
							? h(
									'p',
									{ style: CSS.hint },
									'隧道组件已就绪：' + status.cloudflared,
								)
							: null,
						/*
						 * **下载渠道选择**（需求 4）。
						 *
						 * 用户原话：「改成多个渠道嘛，让用户自行选择，比如用户他可能
						 * 自己有网络环境，他在 GitHub 上面拉……而且我建议默认就是走官方的。」
						 *
						 * 默认选「官方（GitHub）」—— 不消耗作者服务器带宽，
						 * 也是用户明确要求的默认行为。
						 */
						h(
							'div',
							{ style: { marginTop: '4px', marginBottom: '8px' } },
							h('div', { style: CSS.hint }, '下载渠道'),
							h(
								'div',
								{ style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '6px' } },
								[
									{ id: '', label: '自动（官方优先）' },
									{ id: 'github', label: 'Cloudflare 官方' },
									{ id: 'mirror', label: '中转服务器' },
								].map(function (opt) {
									var active = channel === opt.id;
									return h(
										'button',
										{
											key: 'ch-' + (opt.id === '' ? 'auto' : opt.id),
											type: 'button',
											onClick: function () { setChannel(opt.id); },
											disabled: busy !== '',
											style: {
												padding: '5px 11px',
												borderRadius: '999px',
												fontSize: '12px',
												cursor: busy !== '' ? 'default' : 'pointer',
												border: active ? '1px solid rgba(80,160,255,0.9)' : '1px solid rgba(127,127,127,0.35)',
												background: active ? 'rgba(80,160,255,0.16)' : 'transparent',
												color: 'inherit',
												opacity: busy !== '' && !active ? 0.5 : 1,
											},
										},
										opt.label,
									);
								}),
							),
						),
						h(
							'div',
							{ style: CSS.row },
							h(
								Button,
								{
									variant: envReady === true ? 'outline' : 'primary',
									size: 'md',
									onClick: prepareEnv,
									disabled: busy !== '',
								},
								busy === 'environment'
									? '正在下载…'
									: envReady === true
										? '重新检查'
										: '下载隧道组件',
							),
							envReady === true
								? h('span', { style: CSS.hint }, '可以继续填激活码了')
								: h('span', { style: CSS.hint }, '下载期间请不要关闭这个页面'),
						),
						// 下载进度：轮询 `/status` 里的 `download` 记账。
						busy === 'environment' && download !== null && download.active === true
							? h(
									'div',
									{ key: 'env-download', style: CSS.card, role: 'status' },
									h(
										'p',
										{ style: CSS.hint },
										download.total > 0
											? '正在下载隧道组件 ' +
												Math.round((download.received / download.total) * 100) +
												'%（' +
												Math.round(download.received / 1048576) +
												' / ' +
												Math.round(download.total / 1048576) +
												' MB）'
											: '正在下载隧道组件…已收到 ' +
												Math.round(download.received / 1048576) +
												' MB',
									),
									download.total > 0
										? h('div', { style: CSS.progressTrack },
												h('div', {
													style: Object.assign({}, CSS.progressBar, {
														width: Math.min(100, (download.received / download.total) * 100) + '%',
													}),
												}),
											)
										: null,
								)
							: null,
					),
				);
			}

			// ---- 激活码卡片（未配对时） ----
			if (status.paired !== true) {
				children.push(
					h(
						'div',
						{ key: 'activate', style: CSS.card },
						h(
							'div',
							{ style: CSS.row },
							h('h3', { style: CSS.title }, '第 2 步：填写激活码'),
							envReady === true ? null : h(Pill, null, '等环境就绪'),
						),
						h(
							'p',
							{ style: CSS.desc },
							'激活码由服务方提供，一台电脑用一个。填完会自动为这台电脑建好专属的隧道与域名。',
						),
						// ⚠️ 环境没就绪时**完全不渲染输入框**：这是刻意的硬拦截。
						//
						// 用户报障的根因就是「先填码、后下载」—— 下载一超时激活码
						// 已经被消费。把入口卡住，用户就不会再踩一次。
						//
						// 为什么不是 `disabled`：`Input` 来自官方
						// `@deepseek-ai/dsh-client-ui-primitives`，本机没装这个包、
						// 无法确认它是否透传 `disabled`（官方 `Button` 是那种
						// 「只解构已知字段」的写法，透传失败的先例有过）。
						// 不渲染 = 零依赖，任何版本的 Input 都不会出岔子。
						envReady === true
							? h(
									'div',
									{ style: CSS.row },
									h(Input, {
										value: code,
										onChange: function (event) {
											setCode(event.target.value);
										},
										placeholder: '例如 CA01AEF6',
										'aria-label': '激活码',
										style: { minWidth: '220px' },
									}),
									h(
										Button,
										{
											variant: 'primary',
											size: 'md',
											onClick: submit,
											disabled: busy !== '',
										},
										busy === 'register' ? '正在绑定…' : '绑定',
									),
								)
							: h(
									'p',
									{ style: CSS.hint, role: 'status' },
									'请先完成上面的「第 1 步：准备环境」，下载好隧道组件后这里才会出现激活码输入框。',
								),
						h(
							'p',
							{ style: CSS.hint },
							busy === 'register'
								? '正在绑定…（只做注册，几秒就好；隧道组件已经在第 1 步下好了）'
								: '绑定约 2–5 秒，不会再下载任何东西。',
						),
						// ⚠️ 这里原来有一块「下载进度」，已删掉：下载搬到第 1 步
						//（`/environment`）去了，`/register` 不再下载任何东西。
						// 进度条留在这张卡片里只会让人以为「绑定又要下东西」。
					),
				);
			}

			// ---- 配对码卡片（已配对时） ----
			if (status.paired === true) {
				var pairBody;
				if (pair === null) {
					pairBody = h(
						'div',
						{ style: CSS.row },
						h(
							Button,
							{ variant: 'primary', size: 'md', onClick: makeCode, disabled: busy !== '' },
							busy === 'handshake' ? '正在生成…' : '生成配对码',
						),
						h('span', { style: CSS.hint }, '生成后在手机上输入即可，10 分钟内有效。'),
					);
				} else {
					pairBody = h(
						'div',
						{ style: { display: 'flex', flexDirection: 'column', gap: '12px' } },
						h(
							'div',
							{ style: CSS.row },
							h('span', { style: CSS.hint }, '在手机上输入这 6 位码：'),
							h('span', { style: CSS.code }, pair.pairCode),
						),
						// 二维码里只有那 6 位码，不含任何凭据 —— 见 host 半边 pair.js 的文件头。
						// ⚠️ 用 `<img src>` 而不是 `dangerouslySetInnerHTML`（外部审计 M6）：
						// 那是全仓唯一的原始 HTML sink，输入又来自网络对端。SVG 当图片渲染
						// 就不进 DOM 解析，这条通道从结构上关掉了。
						h('img', { style: CSS.qrBox, src: pair.qr, alt: '配对二维码' }),
						h(
							'div',
							{ style: CSS.row },
							h(
								Button,
								{ variant: 'ghost', size: 'sm', onClick: makeCode, disabled: busy !== '' },
								busy === 'handshake' ? '正在生成…' : '换一个',
							),
							h(
								Button,
								{
									variant: 'ghost',
									size: 'sm',
									onClick: function () {
										setPair(null);
									},
								},
								'收起',
							),
						),
					);
				}
				children.push(
					h(
						'div',
						{ key: 'connect', style: CSS.card },
						h('h3', { style: CSS.title }, '连接手机'),
						h(
							'p',
							{ style: CSS.desc },
							'在手机的 DSH 里打开「添加工作区」→「电脑」，输入下面的配对码。二维码可以直接扫，扫不出来就照着手输。',
						),
						pairBody,
					),
				);
			}

			if (message !== null) {
				children.push(h('p', { key: 'message', style: message.kind === 'ok' ? CSS.ok : CSS.error }, message.text));
			}

			children.push(
				h(
					'div',
					{ key: 'refresh', style: CSS.row },
					h(
						Button,
						{ variant: 'ghost', size: 'sm', onClick: reload, disabled: busy !== '' },
						'刷新状态',
					),
				),
			);

			return h('div', { style: CSS.page }, children);
		}

		// ------------------------------------------------------------ 注册进设置

		var SLOT = 'settings.section';
		var CELL_ID = 'dsh-jiuling-remote-desktop';
		var inject = ['slots'];

		function apply(ctx) {
			ctx.slots.inject(SLOT, function () {
				return ctx.slots.register(
					{
						name: SLOT,
						id: CELL_ID,
						// 排在官方页面之后（与 dsh-mcp-manager 同样的取舍：不打乱官方顺序）。
						order: 210,
						label: '手机远程',
					},
					RemoteDesktopPanel,
				);
			});
		}

		exports.apply = apply;
		exports.inject = inject;
		return module.exports;
	},
});
