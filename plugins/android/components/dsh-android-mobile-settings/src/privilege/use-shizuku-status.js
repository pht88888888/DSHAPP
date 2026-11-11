export function useShizukuStatus(React, refresh, t) {
  const SHIZUKU_EMPTY = { installed: false, binder: false, granted: false, uid: -1, version: 0 };
  const [shizuku, setShizuku] = React.useState(SHIZUKU_EMPTY);
  // 授权是异步的（用户在 Shizuku 自己的窗口里点「允许」），期间按钮要锁住。
  const [shizukuBusy, setShizukuBusy] = React.useState(false);
  const shizukuTimers = React.useRef(null);
  if (shizukuTimers.current === null) shizukuTimers.current = new Set();

  /** 读一次 Shizuku 状态（不触发任何动作）。 */
  const readShizuku = React.useCallback(() => {
  	const bridge = window.DSHAndroid;
  	if (!bridge || typeof bridge.shizukuState !== "function") {
  		setShizuku(SHIZUKU_EMPTY);
  		return;
  	}
  	try {
  		const raw = bridge.shizukuState();
  		const o = typeof raw === "string" ? JSON.parse(raw) : (raw || {});
  		setShizuku({
  			installed: !!o.installed,
  			binder: !!o.binder,
  			granted: !!o.granted,
  			uid: Number.isFinite(Number(o.uid)) ? Number(o.uid) : -1,
  			version: Number(o.version) || 0,
  		});
  	} catch (e) {
  		console.warn("[dsh-android-mobile] 读取 Shizuku 状态失败:", e && e.message);
  		setShizuku(SHIZUKU_EMPTY);
  	}
  }, []);

  React.useEffect(() => { readShizuku(); }, [readShizuku]);

  // 从 Shizuku 授权窗口回来时刷新 —— 那是**别的应用**的界面，本页拿不到
  // 回调，只能靠 visibilitychange / focus（与 root 同一手法）。
  React.useEffect(() => {
  	const onVisible = () => { if (!document.hidden) readShizuku(); };
  	document.addEventListener("visibilitychange", onVisible);
  	window.addEventListener("focus", onVisible);
  	return () => {
  		document.removeEventListener("visibilitychange", onVisible);
  		window.removeEventListener("focus", onVisible);
  	};
  }, [readShizuku]);

  React.useEffect(() => {
  	const timers = shizukuTimers.current;
  	return () => {
  		timers.forEach((id) => window.clearInterval(id));
  		timers.clear();
  	};
  }, []);

  /**
   * 点「授权」：拉起 Shizuku 的确认窗口，然后轮询授权结果。
   *
   * ⚠️ **必须轮询**：授权结论是 Shizuku 异步给的，原生侧只登记了一个
   *   监听器（见 "MainActivity.setupShizukuPermissionListener"），
   *   页面拿不到任何 promise。
   * ⚠️ 授权成功后再跑一次 "refresh()"："identity" 那条结论来自 runner
   *   的探测（宿主半 HTTP 路由），与 Shizuku 的授权状态是**两件事** ——
   *   只更新 Shizuku 状态的话，上面的「当前状态」徽章还停在「未开启」，
   *   用户会以为授权没生效。**不需要重启 App**：runner 每次执行命令时
   *   才现连 Shizuku。
   */
  const grantShizuku = React.useCallback(() => {
  	const bridge = window.DSHAndroid;
  	if (!bridge || typeof bridge.shizukuRequestPermission !== "function") {
  		window.alert(t("shizukuNoBridge"));
  		return;
  	}
  	try {
  		bridge.shizukuRequestPermission();
  	} catch (e) {
  		console.warn("[dsh-android-mobile] 请求 Shizuku 授权失败:", e && e.message);
  		return;
  	}
  	setShizukuBusy(true);
  	const startedAt = Date.now();
  	const timer = window.setInterval(() => {
  		const b = window.DSHAndroid;
  		let granted = false;
  		try {
  			const raw = b && b.shizukuState ? b.shizukuState() : "{}";
  			granted = !!(typeof raw === "string" ? JSON.parse(raw) : (raw || {})).granted;
  		} catch (e) { /* 读失败当作还没授权，继续等 */ }
  		// ⚠️ 上限 20 秒：用户可能正在那个窗口上犹豫，也可能压根没打开。
  		//   到点就解锁按钮并**照常读一次**，让界面显示真实状态 ——
  		//   不能停在「等待授权…」上不动。
  		if (granted || Date.now() - startedAt > 20000) {
  			window.clearInterval(timer);
  			shizukuTimers.current.delete(timer);
  			readShizuku();
  			setShizukuBusy(false);
  			if (granted) refresh();
  		}
  	}, 600);
  	shizukuTimers.current.add(timer);
  }, [readShizuku, refresh, t]);
  return { shizuku, shizukuBusy, readShizuku, grantShizuku };
}
