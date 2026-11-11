export function useRootStatus(React) {
  const [root, setRoot] = React.useState({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
  const [rootBusy, setRootBusy] = React.useState(false);
  // 在跑的轮询计时器。用 ref 而不是普通变量：`recheckRoot` 是 useCallback，
  // 闭包捕获的必须是一个**跨渲染稳定**的容器，否则每次渲染新建一个 Set，
  // 卸载时的清理就清不到真正的计时器。
  const rootTimers = React.useRef(null);
  if (rootTimers.current === null) rootTimers.current = new Set();

  /** 读一次缓存里的 root 结论（不触发探测）。 */
  const readRoot = React.useCallback(() => {
  	const bridge = window.DSHAndroid;
  	if (!bridge || typeof bridge.rootState !== "function") {
  		setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
  		return;
  	}
  	try {
  		// 桥返回 JSON 字符串（@JavascriptInterface 只支持基本类型与 String）。
  		const raw = bridge.rootState();
  		const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
  		setRoot({
  			known: !!o.known,
  			state: String(o.state || ""),
  			granted: !!o.granted,
  			detail: String(o.detail || ""),
  			at: Number(o.at) || 0,
  			// ⚠️ 这是「本会话里 root 工具真的挂上了没有」，与 granted 不是一回事：
  			//   overlay 只在引擎启动时读一次，重新检测把结论刷成 GRANTED 之后
  			//   工具并不会凭空出现。见下面的 rootNextOnPending。
  			mounted: !!o.mounted,
  		});
  	} catch (e) {
  		console.warn("[dsh-android-mobile] 读取 root 状态失败:", e && e.message);
  		setRoot({ known: false, state: "", granted: false, detail: "", at: 0, mounted: false });
  	}
  }, []);

  React.useEffect(() => { readRoot(); }, [readRoot]);

  /**
   * 请求重新检测，然后**轮询**直到结论变化。
   *
   * ⚠️ 必须轮询，不能等一个返回值：原生侧是「立即返回、后台线程里探测」，
   *   因为探测最多要等 5 秒（首次还会弹授权框），同步等会把页面卡死。
   * ⚠️ 轮询上限（10 秒）要比原生的探测超时（5 秒）宽，否则会在原生还没
   *   出结论时就把按钮恢复，用户以为检测完了、看到的却是旧结论。
   * ⚠️ 判据是**时间戳变化**（"at"），不是结论变化：连续两次都测出
   *   「无 root」时结论字符串一样，只看结论会一直轮询到超时。
   */
  const recheckRoot = React.useCallback(() => {
  	const bridge = window.DSHAndroid;
  	if (!bridge || typeof bridge.rootRecheck !== "function") return;
  	let before = 0;
  	try {
  		const raw = bridge.rootState();
  		before = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
  	} catch (e) { /* 拿不到旧时间戳就退化成「轮询到超时」，仍然正确 */ }
  	try {
  		bridge.rootRecheck();
  	} catch (e) {
  		console.warn("[dsh-android-mobile] 请求重新检测 root 失败:", e && e.message);
  		return;
  	}
  	setRootBusy(true);
  	const startedAt = Date.now();
  	const timer = window.setInterval(() => {
  		const b = window.DSHAndroid;
  		let at = 0;
  		try {
  			const raw = b && b.rootState ? b.rootState() : "{}";
  			at = Number((typeof raw === "string" ? JSON.parse(raw) : (raw || {})).at) || 0;
  		} catch (e) { /* 读失败当作还没出结果，继续等 */ }
  		if (at > before || Date.now() - startedAt > 10000) {
  			window.clearInterval(timer);
  			rootTimers.current.delete(timer);
  			readRoot();
  			setRootBusy(false);
  		}
  	}, 700);
  	// 记账以便卸载时清理（本函数没有 cleanup 出口，所以交给下面的 effect）。
  	rootTimers.current.add(timer);
  }, [readRoot]);

  // 组件卸载时清掉所有在跑的轮询 —— 否则用户切走分节后计时器仍在打桥。
  React.useEffect(() => {
  	const timers = rootTimers.current;
  	return () => {
  		timers.forEach((id) => window.clearInterval(id));
  		timers.clear();
  	};
  }, []);

  // 从授权界面回来后刷新：给 root 授权是在 Magisk 之类的**别的应用**里完成的，
  // 本页拿不到任何回调，只能靠 visibilitychange / focus（与气泡分节同一手法）。
  React.useEffect(() => {
  	const onVisible = () => { if (!document.hidden) readRoot(); };
  	document.addEventListener("visibilitychange", onVisible);
  	window.addEventListener("focus", onVisible);
  	return () => {
  		document.removeEventListener("visibilitychange", onVisible);
  		window.removeEventListener("focus", onVisible);
  	};
  }, [readRoot]);
  return { root, rootBusy, readRoot, recheckRoot };
}
