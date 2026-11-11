// dsh-core-memory — 回复风格设置页（browser half）
//
// 由 dsh-client-modules 加载为 /plugins/dsh-core-memory/client.js，经 vendored
// cordis Loader 的 lazy-CJS 模块表执行（window.__ModuleLoader__.load）。
// 与 host 半的通信走同源 fetch：GET/POST /dsh-core-memory/persona。
//
// 注册一个设置页（slot `settings.section`，id `persona`）：
//   · 顶部总开关：启用 / 停用风格注入
//   · 预设卡片列表：点选即写入，选中项高亮
//   · 「自定义」卡片：展开多行编辑框，失焦或点保存写入
// 所有颜色走主题 token，亮/暗主题通用。
window.__ModuleLoader__.load({
	id: 'dsh-core-memory',
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
		var React = require('react');

		var ENDPOINT = '/dsh-core-memory/persona';
		var DEFAULT_MAX = 4000;

		exports.inject = ['slots'];
		exports.apply = function apply(ctx) {
			var slots = ctx.get('slots');
			if (slots === undefined) return;

			ctx.effect(function () {
				var style = document.createElement('style');
				// 真实主题 token（见 Theme.listTokens）：
				//   --dsw-alias-bg-base / bg-layer-1 / bg-layer-2 / bg-overlay
				//   --dsw-alias-border-l1 / border-l2
				//   --dsw-alias-label-primary / label-secondary
				//   --dsw-alias-brand-primary / state-*
				// 每个都带 fallback，主题缺失时仍可读。
				style.textContent =
					// 页面容器
					'.persona-page{display:flex;flex-direction:column;gap:18px;padding:2px 2px 28px;box-sizing:border-box;max-width:720px}' +
					'.persona-page *{box-sizing:border-box}' +
					// 顶部总开关：一张独立的分组卡
					'.persona-bar{display:flex;align-items:center;justify-content:space-between;gap:14px;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.18));border-radius:12px;padding:13px 16px;background:var(--dsw-alias-bg-layer-1,transparent)}' +
					'.persona-bar-label{font-weight:600;font-size:13.5px;color:var(--dsw-alias-label-primary,inherit);letter-spacing:.01em}' +
					'.persona-bar-sub{font-size:11.5px;color:var(--dsw-alias-label-secondary,inherit);margin-top:3px;line-height:1.5}' +
					// 分组标题（小写字母间距，建立层级）
					'.persona-group-label{font-size:11.5px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:var(--dsw-alias-label-secondary,inherit);opacity:.8;margin:0 0 -8px 4px}' +
					'.persona-hint{color:var(--dsw-alias-label-secondary,inherit);font-size:12px;line-height:1.65}' +
					'.persona-error{color:var(--dsw-alias-state-error-primary,#e5484d);font-size:12px;line-height:1.6}' +
					'.persona-ok{color:var(--dsw-alias-state-success-primary,#30a46c);font-size:12px;line-height:1.6}' +
					// 选项列表：整体一块，内部用分隔线（而非一张张独立卡片）
					'.persona-grid{display:flex;flex-direction:column;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.18));border-radius:12px;overflow:hidden;background:var(--dsw-alias-bg-layer-1,transparent)}' +
					'.persona-card{position:relative;display:flex;align-items:flex-start;gap:12px;text-align:left;width:100%;border:0;border-top:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.14));background:transparent;color:inherit;padding:12px 15px;cursor:pointer;transition:background .14s ease}' +
					'.persona-card:first-child{border-top:0}' +
					'.persona-card:hover{background:var(--dsw-alias-bg-layer-2,rgba(128,128,128,.06))}' +
					'.persona-card:focus-visible{outline:2px solid var(--dsw-alias-brand-primary,#4a7dff);outline-offset:-2px}' +
					// 选中态：极克制的底色 + 左侧品牌色条，不用整块高饱和蓝
					'.persona-card.on{background:var(--dsw-alias-bg-layer-2,rgba(128,128,128,.07))}' +
					'.persona-card.on:before{content:"";position:absolute;left:0;top:0;bottom:0;width:3px;background:var(--dsw-alias-brand-primary,#4a7dff)}' +
					'.persona-card:disabled{opacity:.5;cursor:default}' +
					// 选中标记：圆环 + 实心点，尺寸克制
					'.persona-dot{flex:none;width:16px;height:16px;border-radius:50%;border:1.5px solid var(--dsw-alias-border-l2,rgba(128,128,128,.45));margin-top:1px;position:relative;transition:border-color .14s ease}' +
					'.persona-card.on .persona-dot{border-color:var(--dsw-alias-brand-primary,#4a7dff)}' +
					'.persona-card.on .persona-dot:after{content:"";position:absolute;inset:3.5px;border-radius:50%;background:var(--dsw-alias-brand-primary,#4a7dff)}' +
					'.persona-body{display:flex;flex-direction:column;gap:3px;min-width:0;flex:1}' +
					'.persona-name{font-weight:600;font-size:13px;color:var(--dsw-alias-label-primary,inherit)}' +
					'.persona-desc{font-size:11.5px;color:var(--dsw-alias-label-secondary,inherit);line-height:1.5}' +
					// 自定义编辑区
					'.persona-editor{display:flex;flex-direction:column;gap:9px;width:100%;border-top:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.14));padding-top:11px;margin-top:11px}' +
					'.persona-textarea{width:100%;min-height:104px;resize:vertical;border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.24));border-radius:9px;background:var(--dsw-alias-bg-base,transparent);color:var(--dsw-alias-label-primary,inherit);padding:10px 12px;font-size:12.5px;line-height:1.65;font-family:inherit;transition:border-color .14s ease}' +
					'.persona-textarea::placeholder{color:var(--dsw-alias-label-secondary,inherit);opacity:.55}' +
					'.persona-textarea:focus{outline:none;border-color:var(--dsw-alias-brand-primary,#4a7dff)}' +
					'.persona-editor-foot{display:flex;align-items:center;justify-content:space-between;gap:12px}' +
					'.persona-count{font-size:11px;color:var(--dsw-alias-label-secondary,inherit);font-variant-numeric:tabular-nums}' +
					'.persona-count.over{color:var(--dsw-alias-state-error-primary,#e5484d)}' +
					'.persona-actions{display:flex;gap:8px}' +
					'.persona-btn{border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.3));background:transparent;color:var(--dsw-alias-label-primary,inherit);border-radius:8px;padding:5px 13px;font-size:12px;cursor:pointer;transition:background .14s ease,border-color .14s ease}' +
					'.persona-btn:hover:not(:disabled){background:var(--dsw-alias-bg-layer-2,rgba(128,128,128,.08))}' +
					'.persona-btn.primary{border-color:transparent;background:var(--dsw-alias-brand-primary,#4a7dff);color:#fff}' +
					'.persona-btn.primary:hover:not(:disabled){opacity:.9}' +
					'.persona-btn:disabled{opacity:.45;cursor:default}' +
					// 开关
					'.persona-switch{position:relative;width:38px;height:22px;border-radius:999px;border:none;cursor:pointer;padding:0;transition:background .16s ease;background:var(--dsw-alias-border-l2,rgba(128,128,128,.35));flex:none}' +
					'.persona-switch.on{background:var(--dsw-alias-state-success-primary,#30a46c)}' +
					'.persona-switch:disabled{opacity:.45;cursor:default}' +
					'.persona-knob{position:absolute;top:2px;left:2px;width:18px;height:18px;border-radius:50%;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.18);transition:transform .16s ease}' +
					'.persona-switch.on .persona-knob{transform:translateX(16px)}';
				document.head.appendChild(style);
				return function () { style.remove(); };
			}, 'dsh-core-memory: persona stylesheet');

			function Switch(props) {
				return React.createElement('button', {
					className: 'persona-switch' + (props.on ? ' on' : ''),
					disabled: !!props.disabled,
					'aria-pressed': !!props.on,
					title: props.title || '',
					onClick: props.onClick,
				}, React.createElement('span', { className: 'persona-knob' }));
			}

			function PersonaSection() {
				var dataState = React.useState(null);
				var data = dataState[0], setData = dataState[1];
				var errState = React.useState(null);
				var error = errState[0], setError = errState[1];
				var okState = React.useState(null);
				var notice = okState[0], setNotice = okState[1];
				var busyState = React.useState(false);
				var busy = busyState[0], setBusy = busyState[1];
				var draftState = React.useState('');
				var draft = draftState[0], setDraft = draftState[1];
				var touchedState = React.useState(false);
				var touched = touchedState[0], setTouched = touchedState[1];
				// 本地展开自定义编辑区（尚未写盘）：点「自定义」但草稿为空时先展开，等保存再写
				var localCustomState = React.useState(false);
				var localCustom = localCustomState[0], setLocalCustom = localCustomState[1];

				var maxLen = (data && data.maxCustom) || DEFAULT_MAX;

				var reload = React.useCallback(function () {
					var alive = true;
					fetch(ENDPOINT, { headers: { accept: 'application/json' } })
						.then(function (r) { return r.json(); })
						.then(function (view) {
							if (!alive) return;
							if (view && view.error) throw new Error(view.error);
							setData(view);
							setError(null);
							setDraft((view && view.persona && view.persona.custom) || '');
							setTouched(false);
						})
						.catch(function (e) {
							if (alive) setError(String(e && e.message ? e.message : e));
						});
					return function () { alive = false; };
				}, []);

				React.useEffect(function () { return reload(); }, [reload]);

				var save = React.useCallback(function (patch, okText) {
					setBusy(true);
					setNotice(null);
					fetch(ENDPOINT, {
						method: 'POST',
						headers: { 'content-type': 'application/json' },
						body: JSON.stringify(patch),
					})
						.then(function (r) { return r.json().then(function (v) { return { status: r.status, value: v }; }); })
						.then(function (out) {
							if (out.value && out.value.error) throw new Error(out.value.error);
							if (out.status !== 200) throw new Error('保存失败（HTTP ' + out.status + '）');
							setData(function (cur) { return cur ? Object.assign({}, cur, { persona: out.value.persona, presets: out.value.presets || cur.presets }) : out.value; });
							if (out.value.persona) setDraft(out.value.persona.custom || '');
							setTouched(false);
							setLocalCustom(false);
							setError(null);
							setNotice(okText);
						})
						.catch(function (e) { setError(String(e && e.message ? e.message : e)); })
						.then(function () { setBusy(false); });
				}, []);

				if (error && !data) {
					return React.createElement('div', { className: 'persona-page' },
						React.createElement('div', { className: 'persona-error' }, '加载失败：' + error),
						React.createElement('button', { className: 'persona-btn', onClick: reload }, '重试'));
				}
				if (!data) {
					return React.createElement('div', { className: 'persona-page' },
						React.createElement('div', { className: 'persona-hint' }, '加载中…'));
				}

				var persona = data.persona || { enabled: true, preset: 'default', custom: '' };
				var presets = data.presets || [];
				var over = draft.length > maxLen;

				var children = [];

				children.push(React.createElement('div', { className: 'persona-bar', key: 'bar' },
					React.createElement('div', null,
						React.createElement('div', { className: 'persona-bar-label' }, '启用回复风格'),
						React.createElement('div', { className: 'persona-bar-sub' }, '关闭后不注入任何风格指令，使用模型原生语气')),
					Switch({
						on: !!persona.enabled,
						disabled: busy,
						title: '风格注入总开关',
						onClick: function () { save({ enabled: !persona.enabled }, !persona.enabled ? '已启用' : '已停用'); },
					})));

				children.push(React.createElement('div', { className: 'persona-hint', key: 'hint' },
					'所选风格会注入每个会话的系统提示，修改后在下一次模型组装时生效。'));

				if (error) children.push(React.createElement('div', { className: 'persona-error', key: 'err' }, error));
				if (notice) children.push(React.createElement('div', { className: 'persona-ok', key: 'ok' }, notice));

				// 分组标题：为选项列表建立视觉层级
				children.push(React.createElement('div', { className: 'persona-group-label', key: 'gl' }, '选择风格'));

				var cards = presets.map(function (p) {
					var on = persona.preset === p.id;
					var expanded = p.id === 'custom' && (on || localCustom);
					var cardChildren = [
						React.createElement('span', { className: 'persona-dot', key: 'dot' }),
						React.createElement('span', { className: 'persona-body', key: 'body' },
							React.createElement('span', { className: 'persona-name' }, p.name),
							React.createElement('span', { className: 'persona-desc' }, p.summary),
							// 编辑区放在 body 内部，自然与名称文字左对齐、占满剩余宽度
							expanded ? React.createElement('div', { className: 'persona-editor', key: 'editor' },
								React.createElement('textarea', {
									className: 'persona-textarea',
									value: draft,
									placeholder: '描述你想要的回复风格，例如：\n- 语气：轻松但专业\n- 句式：先给结论，再列要点\n- 用词：少用形容词\n- 参考口吻：「直接说重点。」',
									onChange: function (e) { setDraft(e.target.value); setTouched(true); setNotice(null); },
									onClick: function (e) { e.stopPropagation(); },
								}),
								React.createElement('div', { className: 'persona-editor-foot' },
									React.createElement('span', { className: 'persona-count' + (over ? ' over' : '') },
										draft.length + ' / ' + maxLen + ' 字符' + (over ? '（超出上限）' : '')),
									React.createElement('span', { className: 'persona-actions' },
										React.createElement('button', {
											className: 'persona-btn primary',
											disabled: busy || over || !draft.trim(),
											onClick: function (e) { e.stopPropagation(); save({ preset: 'custom', custom: draft }, '自定义风格已保存'); },
										}, '保存')))) : null,
						),
					];
					var onClick = function () {
						if (p.id === 'custom') {
							// 「自定义」先本地展开编辑区（不写盘）：已有草稿则直接切换为自定义风格，
							// 否则等用户填写后点「保存」再写入，避免把空占位文本写进配置。
							if (draft.trim()) save({ preset: 'custom', custom: draft }, '已切换到自定义风格');
							else setLocalCustom(true);
						} else {
							save({ preset: p.id }, '已切换到「' + p.name + '」');
						}
					};
					return React.createElement('div', {
						className: 'persona-card' + (on ? ' on' : ''),
						key: p.id,
						role: 'button',
						tabIndex: busy ? -1 : 0,
						onClick: function () { if (!busy && !(p.id === 'custom' && expanded)) onClick(); },
						onKeyDown: function (e) { if (!busy && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); if (!(p.id === 'custom' && expanded)) onClick(); } },
					}, cardChildren);
				});

				children.push(React.createElement('div', { className: 'persona-grid', key: 'grid' }, cards));

				return React.createElement('div', { className: 'persona-page' }, children);
			}

			ctx.effect(function () {
				return slots.inject('settings.section', function () {
					return slots.register(
						{ name: 'settings.section', id: 'persona', order: 17, label: 'AI 回复风格' },
						PersonaSection
					);
				});
			}, 'dsh-core-memory: persona settings section');
		};
		return module.exports;
	},
});
