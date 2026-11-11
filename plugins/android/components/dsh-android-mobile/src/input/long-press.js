export function setupLongPress(mpIsMobile) {
  const listeners = [];
  const listen = (type, handler, options) => {
    document.addEventListener(type, handler, options);
    listeners.push(() => document.removeEventListener(type, handler, options));
  };
  // ── 4e. 非消息区域长按拦截：不触发系统文字选择器 ──
  // 策略：touchstart 非 passive 允许我们在需要时 preventDefault；
  //       只在长按时（≥400ms 且无明显移动）才阻止，短按/滚动正常走原生。
  var mpLongPressStart = null;
  var mpLongPressMoved = false;
  var MP_LONGPRESS_THRESHOLD = 400;
  var MP_LONGPRESS_MOVE_THRESHOLD = 12;

  function mpIsMessageArea(el) {
  	if (!el) return false;
  	if (el.closest(".wSkVaW_viewArea")) return true;
  	if (el.closest("input") || el.closest("textarea") || el.closest("[contenteditable]")) return true;
  	return false;
  }

  listen("touchstart", function(e) {
  	if (!mpIsMobile()) return; // 修复 40
  	if (e.touches.length !== 1) return;
  	var t = e.touches[0];
  	mpLongPressStart = { x: t.clientX, y: t.clientY, time: Date.now() };
  	mpLongPressMoved = false;
  }, false);

  listen("touchmove", function(e) {
  	if (!mpIsMobile()) return; // 修复 40
  	if (!mpLongPressStart) return;
  	var t = e.touches[0];
  	var dx = Math.abs(t.clientX - mpLongPressStart.x);
  	var dy = Math.abs(t.clientY - mpLongPressStart.y);
  	if (dx > MP_LONGPRESS_MOVE_THRESHOLD || dy > MP_LONGPRESS_MOVE_THRESHOLD) {
  		mpLongPressStart = null;
  	}
  }, false);

  listen("touchend", function(e) {
  	if (!mpIsMobile()) return; // 修复 40
  	if (!mpLongPressStart) return;
  	var elapsed = Date.now() - mpLongPressStart.time;
  	if (elapsed < MP_LONGPRESS_THRESHOLD) {
  		mpLongPressStart = null;
  		return;
  	}
  	// 长按超时，检查是否在消息区域
  	var t = e.changedTouches[0];
  	var target = document.elementFromPoint(t.clientX, t.clientY);
  	if (mpIsMessageArea(target)) {
  		mpLongPressStart = null;
  		return; // 消息区域：让原生长按行为正常执行
  	}
  	// 非消息区域长按：阻止系统文本选择器
  	e.preventDefault();
  	mpLongPressStart = null;
  }, false);

  // contextmenu 兜底（调试工具偶尔触发）
  listen("contextmenu", function(e) {
  	// ⚠️ 修复 40：此前**无条件** preventDefault → 桌面端右键菜单被我们吃掉，
  	// 这是「电脑那边也被改了」里最影响日常使用的之一。
  	if (!mpIsMobile()) return; // 修复 40
  	if (!mpIsMessageArea(e.target)) {
  		e.preventDefault();
  	}
  }, false);

  return () => { for (const dispose of listeners.splice(0)) dispose(); };
}
