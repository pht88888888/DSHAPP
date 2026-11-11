export function createCredentialKeyboard(mpIsMobile) {
  const MP_KEY_ATTR = "data-mp-key";

  /**
   * ══ 修复 68 的真正生效点：在**原型层**拦掉 "type=password" ══
   *
   * 为什么必须有这一层 —— 这是本修复第二版才搞明白的关键机制：
   *
   * 官方那两个凭据输入框除了 "type="password"" 之外，还带 **"autoFocus: true"**
   * （"dsh-client-ui-settings-models/lib/client.js" L1699 与 L2552）。React 把
   * 元素插入 DOM 的**同一帧**就让它获得焦点，WebView 在那时按「密码框」建立了
   * 输入连接并把 "inputType" 定成 "TYPE_TEXT_VARIATION_WEB_PASSWORD"(0xe1)。
   *
   * 而 **WebView 不会为已经建立的连接重算 "inputType"**。所以「先让 password
   * 存在、再由 MutationObserver 改成 text」这条路**必然失败**：改属性发生在
   * 聚焦之后，输入法早就拿到密码型类型了。
   *
   * 真机实测（Huawei CDY-TN90 / Android 10，"dumpsys input_method" 读实时值）：
   *
   *   只做事后改属性（第一版行为）：  DOM type=text、ts=none  →  inputType=0xe1 ✗
   *   本层拦截，password 从未出现：   DOM type=text、ts=none  →  inputType=0x80a1 ✓
   *
   * 顺带排除掉的几个嫌疑（都实测过，**不是**触发条件，别再去改它们）：
   *   · "data-mp-key" 标记本身 —— 带它的纯净 input 仍是 0x0
   *   · CSS "-webkit-text-security" —— 已确认可读，焦点框实测 "ts=none"
   *   · blur + focus 重建连接 —— 实测刷不动 "inputType"
   *
   * 做法：重定义 "HTMLInputElement.prototype" 的 "type" 访问器与 "setAttribute"，
   * 把写入的 ""password"" 就地换成 ""text""。React 完全无感 —— 它写 password、
   * 读回来的也是它自己以为的 password（React 的受控逻辑只认自己的 props，
   * 不读 DOM 属性），而真实 DOM 里**从头到尾没有出现过 password**。
   *
   * ⚠️ 只拦 "type"，**不碰 value / onChange / required**：官方那两个框是受控组件，
   *    字符由 React 状态持有，本层不介入数据流。
   * ⚠️ "autocomplete" 仍交给下面的 MutationObserver 摘（它不影响输入连接，
   *    早一步晚一步都行）。
   * ⚠️ 只在移动端生效（修复 40 的惯例）：桌面端没有安全键盘问题，不该被改。
   */
  function installPasswordTypeShim() {
  	if (!mpIsMobile()) return () => {};
  	try {
  		const proto = HTMLInputElement.prototype;
  		const desc = Object.getOwnPropertyDescriptor(proto, "type");
  		if (!desc || typeof desc.set !== "function") return () => {};
  		const origSet = desc.set;
  		const origGet = desc.get;
  		const origSetAttribute = proto.setAttribute;

  		Object.defineProperty(proto, "type", {
  			configurable: true,
  			enumerable: desc.enumerable,
  			get() { return origGet.call(this); },
  			set(value) {
  				// 只改写入值，不改语义：调用方以为写的是 password，DOM 拿到 text。
  				origSet.call(this, String(value).toLowerCase() === "password" ? "text" : value);
  			},
  		});
  		proto.setAttribute = function (name, value) {
  			if (String(name).toLowerCase() === "type" && String(value).toLowerCase() === "password") {
  				return origSetAttribute.call(this, "type", "text");
  			}
  			return origSetAttribute.call(this, name, value);
  		};
  		// 只还原这两个，不做通用 hook 复原：本函数只改这两处。
  		return () => {
  			try {
  				Object.defineProperty(proto, "type", desc);
  				proto.setAttribute = origSetAttribute;
  			} catch (err) { /* 静默 */ }
  		};
  	} catch (err) {
  		return () => {}; // 拦截失败就退化成下面 MutationObserver 的事后补救
  	}
  }

  function setupPlainKeyboardForCredentials() {
  	if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（桌面无安全键盘）

  	// ⚠️ **顺序要紧**：拦截器必须在任何 React 渲染之前装上，否则第一帧的
  	//    `type=password` + autoFocus 又会把输入连接定成密码型（见上面长注释）。
  	const disposeShim = installPasswordTypeShim();

  	/** 把一处密码输入框降级成普通文本框 + 打标记。返回是否真的改了。 */
  	function soften(el) {
  		if (!el || el.nodeType !== 1 || el.tagName !== "INPUT") return false;
  		// ⚠️ **先看现在是不是密码框，不是就一个字节都不动。**
  		//   ① 安全：只处理真正的密码框，页面里普通的 `input[type=email]`
  		//      （登录页之类）不被误标。
  		//   ② 幂等：改成 text 之后再调用会走到这里直接返回，不产生任何写入 ⇒
  		//      MutationObserver 不会自激。
  		//   装了拦截器之后，`type=password` 几乎不会出现（写进去就变 text），
  		//   所以这里通常不命中 —— 它现在主要是**兜底**：万一 React 用
  		//   `innerHTML`/`cloneNode` 等绕过 setter 的路径造出密码框，
  		//   或拦截器因环境差异没装上，仍然能补救。
  		if ((el.getAttribute("type") || "").toLowerCase() !== "password") return false;
  		try {
  			el.setAttribute(MP_KEY_ATTR, "1");
  			// 主触发点：`type=password` ⇒ WebView 的 inputType 变成
  			// TYPE_TEXT_VARIATION_PASSWORD ⇒ 输入法切安全键盘。
  			el.setAttribute("type", "text");
  			// 次要触发点：`autocomplete="new-password"` 是 Chromium 的 autofill
  			// 提示（AutofillManager 会把它作为 hint 交给输入法），部分国产输入法
  			// 据此也判成密码字段。**整个移除属性**而不是改成 "off" —— 后者会让
  			// Chromium 置上 `IME_FLAG_NO_PERSONALIZED_LEARNING`，又是一条「敏感
  			// 字段」信号，等于换一个可能触发安全键盘的理由。
  			if (el.hasAttribute("autocomplete")) el.removeAttribute("autocomplete");
  			return true;
  		} catch (err) { return false; } // 静默：绝不打断设置页
  	}

  	/**
  	 * 兜底扫描 + 摘 "autocomplete"。
  	 *
  	 * ⚠️ 装了拦截器之后，这里**不再**是主路径（password 压根不会出现），
  	 *    它的职责变成两件：
  	 *      ① 万一拦截器没生效（环境差异）时的补救；
  	 *      ② 摘掉 "autocomplete="new-password"" —— 拦截器不管这个属性。
  	 *    所以判断条件放宽成「带 data-mp-key 或 autocomplete 的输入框」，
  	 *    而不是只认 "type=password"。
  	 */
  	function tidy(el) {
  		if (!el || el.nodeType !== 1 || el.tagName !== "INPUT") return false;
  		let changed = false;
  		try {
  			if ((el.getAttribute("type") || "").toLowerCase() === "password") {
  				changed = soften(el) || changed;
  			}
  			// autocomplete 只有"像密码框"的输入框才需要摘 —— 判据是官方那两处
  			// 都会带 `new-password`，普通输入框的 autocomplete（如 "email"）
  			// 是页面正常语义，不该动。
  			if (el.getAttribute("autocomplete") === "new-password") {
  				el.removeAttribute("autocomplete");
  				changed = true;
  			}
  		} catch (err) { /* 静默 */ }
  		return changed;
  	}

  	function scan(root) {
  		if (!root || typeof root.querySelectorAll !== "function") return;
  		try {
  			tidy(root); // root 自身可能就是那个输入框（addedNodes 分支）
  			root.querySelectorAll("input").forEach(tidy);
  		} catch (err) { /* 静默 */ }
  	}

  	scan(document);

  	const obs = new MutationObserver((records) => {
  		for (const r of records) {
  			// attributes 分支：React 把 type / autocomplete 设回去时立刻改回来。
  			// tidy 自身幂等（无需改动时不产生写入）⇒ 不会自激循环。
  			if (r.type === "attributes") { tidy(r.target); continue; }
  			for (const n of r.addedNodes) scan(n);
  		}
  	});
  	// subtree + childList 抓懒挂载（设置页是按需打开的对话框）；
  	// attributes 抓 React 回写。
  	obs.observe(document.documentElement || document.body, {
  		childList: true, subtree: true,
  		attributes: true, attributeFilter: ["type", "autocomplete"],
  	});

  	return () => {
  		try { obs.disconnect(); } catch (err) { /* 静默 */ }
  		disposeShim();
  	};
  }

  return setupPlainKeyboardForCredentials;
}
