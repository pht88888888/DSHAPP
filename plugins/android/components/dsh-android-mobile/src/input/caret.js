export function createCaretFix(mpIsMobile) {
  function setupCaretFix() {
  	if (!mpIsMobile()) return () => {}; // 修复 40：仅移动端（必须返回空清理函数）

  	/** 把视口坐标换成 Range；拿不到返回 null。 */
  	function rangeFromPoint(x, y) {
  		try {
  			// 标准名（Chromium 128+ 起是标准）与旧前缀名都试。
  			if (typeof document.caretPositionFromPoint === "function") {
  				const pos = document.caretPositionFromPoint(x, y);
  				if (pos && pos.offsetNode) {
  					const r = document.createRange();
  					r.setStart(pos.offsetNode, pos.offset);
  					r.collapse(true);
  					return r;
  				}
  			}
  			if (typeof document.caretRangeFromPoint === "function") {
  				const r = document.caretRangeFromPoint(x, y);
  				if (r) return r;
  			}
  		} catch (e) { /* 老内核不支持就算了 */ }
  		return null;
  	}

  	const onClick = (e) => {
  		try {
  			// 只在「真正的点击」上动手：键盘修饰键一律让给官方（扩选/选段）。
  			if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return;
  			// 输入法组字中不动 selection，否则会丢字。
  			if (e.isComposing) return;

  			// ⚠️ **必须取编辑器根节点，不能用 e.target 当容器**。
  			//   点文字时 target 是内部文本节点所在的行内元素（Lexical 渲染成
  			//   span），点空白处可能是 root 自己，点到 <br> 又换一个 ——
  			//   用 target 做 contains 判据会漏掉一大半情形。
  			//   判据分两步：① 点击位置在 composer 内（用 closest 判断归属）；
  			//   ② 落点也在同一个编辑器根内。
  			const t = e.target;
  			if (!t || !t.closest) return;
  			// 只认 composer 输入区，别去动对话正文（那里不需要打字光标）。
  			const root = t.closest("[data-composer-input], .uV2eYG_root");
  			if (!root) return;

  			// 真正的编辑元素：优先带 data-lexical-editor 的那个，
  			// 否则退回 contenteditable 本体。
  			const editor = root.matches("[contenteditable]")
  				? root
  				: (root.querySelector('[contenteditable="true"]') || root);
  			if (!editor || !editor.isContentEditable) return;

  			const sel = window.getSelection && window.getSelection();
  			if (!sel || sel.rangeCount === 0) return;
  			// 已经有选区（不是光标）⇒ 用户可能在选字，别抢。
  			if (!sel.isCollapsed) return;

  			const want = rangeFromPoint(e.clientX, e.clientY);
  			if (!want) return;
  			// 落点必须在**编辑器根**里，否则说明算到了别处（例如浮层）。
  			if (!editor.contains(want.startContainer)) return;

  			// 与内核算出来的位置比对：同一个节点且偏移差 ≤1 就不动。
  			const cur = sel.getRangeAt(0);
  			if (cur.startContainer === want.startContainer &&
  				Math.abs(cur.startOffset - want.startOffset) <= 1) {
  				return;
  			}

  			// ⚠️ **必须延后一拍再改，原因有两个**：
  			//
  			//   ① composer 是 Lexical 编辑器（`data-lexical-editor="true"`），
  			//      它自己也监听 click 并会把 selection 写回内部 model。
  			//      我们若在同一个事件里抢先改，随后就被它覆盖 ——
  			//      用户看到「光标闪一下又跳回行尾」，比不修还糟。
  			//      延后到本次事件全部处理完（setTimeout 0）再改，顺序才对。
  			//   ② Lexical 通过 `selectionchange` 同步 model。改完 DOM selection
  			//      后浏览器会自己派发该事件，Lexical 就跟着更新 ——
  			//      不需要（也拿不到）它的内部 API。
  			//
  			//   ⚠️ 不要试图在 `__lexicalEditor.update()` 里改 DOM selection：
  			//      那个回调是给 Lexical 节点操作用的，在里面动 DOM selection
  			//      不会写进它的 model，反而与 reconcile 打架。延后 + 让浏览器
  			//      派发 selectionchange 是唯一不需要碰内部 API 的路径。
  			const x = e.clientX, y = e.clientY;
  			setTimeout(function () {
  				try {
  					const w = rangeFromPoint(x, y);
  					if (!w) return;
  					if (!editor.contains(w.startContainer)) return;
  					const s2 = window.getSelection && window.getSelection();
  					if (!s2) return;
  					// 用户可能在这几十毫秒里开始选字了，再确认一次。
  					if (!s2.isCollapsed) return;
  					s2.removeAllRanges();
  					s2.addRange(w);
  				} catch (e2) { /* 延后失败也不影响输入 */ }
  			}, 0);
  		} catch (err) { /* 光标修正失败绝不影响输入 */ }
  	};

  	document.addEventListener("click", onClick, true);
  	return () => document.removeEventListener("click", onClick, true);
  }

  return setupCaretFix;
}
