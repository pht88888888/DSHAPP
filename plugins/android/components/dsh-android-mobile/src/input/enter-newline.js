export function createEnterAsNewline(mpIsMobile) {
  let mpLastCompositionEnd = 0;
  const MP_ENTER_SEL = "[data-composer-input]";

  function setupEnterAsNewline() {
  	if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）

  	/** 中文/日文输入法正在组字时，回车属于「上屏」而不是「换行」。 */
  	function mpIsComposing(e) {
  		if (!e) return false;
  		if (e.isComposing === true || e.keyCode === 229) return true;
  		// 输入法上屏后紧跟的那次回车，浏览器可能已经把 isComposing 置回 false。
  		// 用「最近一次 compositionend 的时间」兜一下（官方宽限 10ms，这里给 80ms：
  		// 手机上输入法的时序比桌面慢，实测偶有超出 10ms 的情况）。
  		return mpLastCompositionEnd > 0 && (Date.now() - mpLastCompositionEnd) < 80;
  	}

  	const onKeyDown = (e) => {
  		try {
  			if (e.key !== "Enter") return;
  			// 已经是「换行」或「发送」的变体（用户外接键盘按了 Shift/Ctrl/Cmd）：
  			// 一律放行给官方，保持外接键盘上的桌面习惯不变。
  			if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
  			if (mpIsComposing(e)) return;
  			const ed = e.target && e.target.closest ? e.target.closest(MP_ENTER_SEL) : null;
  			if (!ed) return;

  			// 吃掉这一次 Enter，再补一次带 shiftKey 的 —— 官方把它当换行。
  			e.preventDefault();
  			e.stopPropagation();
  			// ⚠️ 派发目标优先用**真实焦点元素**：官方那条命令注册在编辑器实例的
  			//   root 上，而 `[data-composer-input]` 未必就是那个 root（可能只是
  			//   它的祖先）。`dispatchEvent` 只向上冒泡、**不进入子元素**，若从祖先
  			//   派发而监听在子元素上就永远收不到。焦点元素必然在编辑区内部，
  			//   且 `bubbles: true` 会一路冒泡经过 root ⇒ 两种情形都覆盖到。
  			let target = ed;
  			try {
  				const a = document.activeElement;
  				if (a && a !== document.body && ed.contains(a)) target = a;
  			} catch (err2) { /* 静默：拿不到焦点就用编辑区本体 */ }
  			const synth = new KeyboardEvent("keydown", {
  				key: "Enter", code: "Enter", keyCode: 13, which: 13,
  				shiftKey: true, bubbles: true, cancelable: true, composed: true,
  			});
  			target.dispatchEvent(synth);
  		} catch (err) { /* 静默：绝不打断输入 */ }
  	};

  	// 组字状态由编辑器自己维护，这里只需要知道「刚刚结束过一次组字」。
  	const onCompositionEnd = () => { mpLastCompositionEnd = Date.now(); };

  	document.addEventListener("keydown", onKeyDown, true);
  	document.addEventListener("compositionend", onCompositionEnd, true);
  	return () => {
  		document.removeEventListener("keydown", onKeyDown, true);
  		document.removeEventListener("compositionend", onCompositionEnd, true);
  	};
  }

  return setupEnterAsNewline;
}
