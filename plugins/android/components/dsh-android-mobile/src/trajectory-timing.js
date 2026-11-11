export function createTrajectoryTiming(mpIsMobile, mpSessionsOf) {
  const MP_TIMING_UNAVAILABLE = "首 token 时间不可用";		const MP_TIMING_NOT_RECORDED = "未记录";
  const MP_TIMING_STEP_START_UNAVAILABLE = "步骤开始时间不可用";
  const MP_TIMING_DURATION_TOO_SHORT = "时长过短";
  const MP_TIMING_PENDING = "等待中";
  const MP_TIMING_USAGE_UNAVAILABLE = "用量不可用";
  const MP_TIMING_OUTPUT_UNAVAILABLE = "输出 token 数不可用";

  // 与官方 dsh-llm `isTokenDelta` 完全一致的判据：
  // 非空 text/reasoning delta，或带非空 argumentsDelta / 有 name 的 tool-call delta。
  function mpIsTokenDelta(chunk) {
  	if (!chunk) return false;
  	switch (chunk.type) {
  		case "text-delta":
  		case "reasoning-delta": return chunk.text !== "" && chunk.text !== void 0;
  		case "tool-call-delta": return (chunk.argumentsDelta !== "" && chunk.argumentsDelta !== void 0) || chunk.name !== void 0;
  		default: return false;
  	}
  }
  // 复现官方 `firstRunMemberTime`：packed run 的成员时间 = time0 起按 dt 累加。
  function mpRunMemberTime(run, predicate) {
  	const frags = run.type === "tool-call-chunks" ? run.args : run.texts;
  	if (!Array.isArray(frags)) return void 0;
  	let time = run.time0;
  	for (let i = 0; i < frags.length; i += 1) {
  		if (i > 0) {
  			const dt = Array.isArray(run.dt) ? run.dt[i - 1] : void 0;
  			if (typeof dt !== "number") return void 0;
  			time += dt;
  		}
  		if (predicate(frags[i])) return time;
  	}
  	return void 0;
  }
  // 复现官方 `assistantStreamFirstTokenTime`：扫一条 stream，返回首个 token 的时间。
  function mpFirstTokenTime(stream) {
  	if (!Array.isArray(stream)) return void 0;
  	for (const rec of stream) {
  		if (!rec) continue;
  		if (rec.type === "chunk") {
  			if (mpIsTokenDelta(rec.chunk) && typeof rec.time === "number") return rec.time;
  			continue;
  		}
  		if (rec.type === "tool-call-chunks") {
  			if (rec.name !== void 0 && typeof rec.time0 === "number") return rec.time0;
  			continue;
  		}
  		if (rec.type === "text-chunks" || rec.type === "reasoning-chunks") {
  			const t = mpRunMemberTime(rec, (f) => f !== "");
  			if (typeof t === "number") return t;
  		}
  	}
  	return void 0;
  }
  // 扫描当前会话事件窗口，建立 "turn:step" → { startTime, firstTokenTime, completedTime, outputTokens }
  // ⚠️ 只读叶子标量，不整对象序列化（事件是 live 数据）。
  function mpCollectTimings() {
  	const sessions = mpSessionsOf();
  	if (!sessions) return null;
  	let id;
  	try {
  		const list = sessions.list.getSnapshot();
  		id = list && list.current;
  	} catch (e) { return null; }
  	if (id === void 0 || id === null) return null;
  	let binding;
  	try { binding = sessions.binding(id); } catch (e) { return null; }
  	if (!binding) return null;
  	const src = binding.eventSource;
  	if (!src || typeof src.getSnapshot !== "function") return null;
  	let win;
  	try { win = src.getSnapshot(); } catch (e) { return null; }
  	const entries = win && Array.isArray(win.entries) ? win.entries : null;
  	if (!entries) return null;
  	const map = Object.create(null);
  	const keyOf = (d) => (d && d.turn !== void 0 && d.step !== void 0) ? (d.turn + ":" + d.step) : null;
  	for (const entry of entries) {
  		const ev = entry && entry.event;
  		if (!ev || !ev.data) continue;
  		const k = keyOf(ev.data);
  		if (k === null) continue;
  		let rec = map[k];
  		if (rec === void 0) { rec = map[k] = { startTime: null, firstTokenTime: null, completedTime: null, outputTokens: null }; }
  		if (ev.type === "step/start") {
  			if (rec.startTime === null && typeof ev.time === "number") rec.startTime = ev.time;
  			continue;
  		}
  		if (ev.type !== "assistant/message" && ev.type !== "assistant/attempt") continue;
  		const stream = ev.data.stream;
  		if (rec.firstTokenTime === null) {
  			const ft = mpFirstTokenTime(stream);
  			if (typeof ft === "number") rec.firstTokenTime = ft;
  		}
  		if (ev.type === "assistant/message") {
  			if (typeof ev.time === "number") rec.completedTime = ev.time;
  			const usage = ev.data.usage;
  			if (usage && Number.isFinite(usage.outputTokens)) rec.outputTokens = usage.outputTokens;
  		}
  	}
  	return map;
  }
  function mpFormatDuration(ms) {
  	if (ms < 1e3) return Math.round(ms) + " 毫秒";
  	return (ms / 1e3).toFixed(ms < 1e4 ? 2 : 1) + " 秒";
  }
  // 官方三行文案的等价复现（判据、顺序、格式全对齐官方实现）
  function mpTimingText(kind, m) {
  	if (!m) return null;
  	if (kind === "ttft") {
  		// ⚠️ 采集侧字段名是 startTime（对应官方的 stepStartTime）
  		if (m.startTime === null) return MP_TIMING_STEP_START_UNAVAILABLE;
  		if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
  		// ⚠️ 防御：任一非有限数都不算，绝不产出 "NaN 秒" 这种脏文案
  		if (!Number.isFinite(m.startTime) || !Number.isFinite(m.firstTokenTime)) return null;
  		return mpFormatDuration(Math.max(0, m.firstTokenTime - m.startTime));
  	}
  	if (kind === "generation") {
  		if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
  		if (m.completedTime === null) return MP_TIMING_PENDING;
  		if (!Number.isFinite(m.firstTokenTime) || !Number.isFinite(m.completedTime)) return null;
  		return mpFormatDuration(Math.max(0, m.completedTime - m.firstTokenTime));
  	}
  	if (kind === "throughput") {
  		if (m.outputTokens === null) return MP_TIMING_OUTPUT_UNAVAILABLE;
  		if (m.firstTokenTime === null) return MP_TIMING_UNAVAILABLE;
  		if (m.completedTime === null) return MP_TIMING_PENDING;
  		if (!Number.isFinite(m.firstTokenTime) || !Number.isFinite(m.completedTime) || !Number.isFinite(m.outputTokens)) return null;
  		const sec = (m.completedTime - m.firstTokenTime) / 1e3;
  		if (sec <= 0) return MP_TIMING_DURATION_TOO_SHORT;
  		return (m.outputTokens / sec).toFixed(1) + " tok/s";
  	}
  	return null;
  }
  // 在轨迹详情面板里就地回填。幂等：已回填过的用 dataset 标记跳过。
  function mpPatchTrajectoryTiming() {
  	if (!mpIsMobile()) return;
  	const details = document.querySelector(".Y0dWHa_details");
  	if (!details) return;
  	// 面板标题带「第 N 轮 · 步骤 M」→ 直接解析出 key，无需依赖表格行
  	const loc = details.querySelector(".Y0dWHa_detailsLocation");
  	if (!loc) return;
  	const text = (loc.textContent || "").trim();
  	const mm = text.match(/第\s*(\d+)\s*轮\s*·\s*步骤\s*(\d+)/);
  	if (!mm) return;
  	const key = mm[1] + ":" + mm[2];
  	// 只在有「不可用」时才干活，避免无谓开销
  	const dds = details.querySelectorAll("dt");
  	let hasUnavailable = false;
  	for (const dt of dds) {
  		const dd = dt.nextElementSibling;
  		if (dd && (dd.textContent || "").indexOf(MP_TIMING_UNAVAILABLE) >= 0) { hasUnavailable = true; break; }
  	}
  	if (!hasUnavailable) return;
  	const map = mpCollectTimings();
  	if (!map) return;
  	const m = map[key];
  	if (!m) return;
  	for (const dt of dds) {
  		const label = (dt.textContent || "").trim();
  		const dd = dt.nextElementSibling;
  		if (!dd) continue;
  		const cur = (dd.textContent || "").trim();
  		if (cur.indexOf(MP_TIMING_UNAVAILABLE) < 0) continue;
  		let kind = null;
  		if (label.indexOf("首 token") >= 0) kind = "ttft";
  		else if (label === "生成") kind = "generation";
  		else if (label === "吞吐量") kind = "throughput";
  		if (kind === null) continue;
  		const out = mpTimingText(kind, m);
  		if (out === null || out === MP_TIMING_UNAVAILABLE) continue;
  		dd.textContent = out;
  		dd.dataset.mpTimingPatched = kind;
  	}
  }

  return { mpPatchTrajectoryTiming };
}
