package com.dsh.android.bubble

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import com.dsh.android.ui.ChatTranscript
import com.dsh.android.ui.BalanceSnapshot
import com.dsh.android.engine.EngineStatus
import java.math.BigDecimal

private var count = 0
private fun case(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
private fun close(a: Float, b: Float) = check(kotlin.math.abs(a - b) < 0.001f) { "$a != $b" }

private fun transcriptChecks() {
    val rows = LinearLayout()
    val scroll = ScrollView().apply { children.add(rows) }
    val renderer = BubbleTranscriptRenderer(Context())
    val style = BubbleTranscriptRenderer.Style(123, 456, 300)
    fun item(role: String, text: String, running: Boolean = false) = ChatTranscript.Item(role, text, running)
    fun submit(vararg items: ChatTranscript.Item) = renderer.submit(ChatTranscript("", false, items.toList()))
    fun body(row: View) = row.findViewById<TextView>(R.id.msgBody)
    case("transcript defers until attached and rendered") { submit(item("assistant", "one")); check(renderer.isDirty); check(rows.childCount == 0); renderer.attach(rows, scroll); renderer.render(style); check(!renderer.isDirty); check(rows.childCount == 1) }
    val first = rows.getChildAt(0)
    case("transcript fingerprint avoids replacement") { submit(item("assistant", "one")); renderer.render(style); check(rows.getChildAt(0) === first) }
    case("transcript prefix reuse updates text and appends") { submit(item("assistant", "two", true), item("tool", "tool")); renderer.render(style); check(rows.getChildAt(0) === first); check(body(first).text == "two"); check(rows.childCount == 2); check(first.findViewById<TextView>(R.id.msgRole).text == "AI · 正在输出") }
    case("transcript role changes rebuild") { submit(item("user", "three")); renderer.render(style); check(rows.getChildAt(0) !== first); check(body(rows.getChildAt(0)).maxWidth == 252) }
    case("transcript style invalidates role cache") { val old = rows.getChildAt(0); renderer.invalidateStyle(); renderer.render(style.copy(accentColor = 789)); check(rows.getChildAt(0) !== old) }
    case("transcript width refreshes max width") { renderer.render(style.copy(panelWidthPx = 200)); check(body(rows.getChildAt(0)).maxWidth == 168) }
    case("transcript preserves reading position") { scroll.tasks.clear(); rows.height = 2000; scroll.height = 100; scroll.scrollY = 0; submit(item("user", "four")); renderer.render(style); check(scroll.tasks.isEmpty()) }
    case("transcript bottom follow is canceled on detach") { scroll.scrollY = 2000; submit(item("user", "five")); renderer.render(style); check(scroll.tasks.size == 1); renderer.detach(); check(scroll.tasks.isEmpty()); check(renderer.isDirty) }
    case("transcript reattach restores latest content") { val newer = LinearLayout(); val newerScroll = ScrollView().apply { children.add(newer) }; renderer.attach(newer, newerScroll); renderer.render(style); check(body(newer.getChildAt(0)).text == "five"); check(rows.childCount == 1); newerScroll.runNext(); check(newerScroll.fullScrolls == 1) }
    case("empty transcript is renderable") { val empty = LinearLayout(); renderer.attach(empty, null); submit(); renderer.render(style); check(empty.childCount == 1); check((empty.getChildAt(0) as TextView).text.toString().contains("没有内容")) }
}

private fun frameChecks() {
    val host = View(); val player = BubbleFramePlayer(); val frames = mutableListOf<Int>()
    case("frame player per-frame delay and finite loop") { player.start(host, 3, listOf(10, 40, 50), 0, 99, { true }) { frames.add(it) }; check(host.tasks.single().second == 20L); host.runNext(); check(host.tasks.single().second == 40L); host.runNext(); host.runNext(); check(frames == listOf(1, 2)); check(host.tasks.isEmpty()); check(player.index == 2) }
    case("frame stop keeps cursor and removes callback") { player.start(host, 3, emptyList(), -1, 33, { true }) {}; player.stop(); check(player.index == 2); check(host.tasks.isEmpty()); player.reset(); check(player.index == 0) }
    case("frame invalid host stops without rendering") { player.start(host, 2, emptyList(), -1, 33, { false }) { error("invalid frame") }; host.runNext(); check(host.tasks.isEmpty()) }
    case("frame callback cannot restart canceled generation") { player.start(host, 2, emptyList(), -1, 33, { true }) { player.stop() }; host.runNext(); check(host.tasks.isEmpty()) }
}

private fun statusChecks() {
    val queued = mutableListOf<Pair<Runnable, Long>>(); var expires = 0
    val status = BubbleStatusPresenter({ r, ms -> queued.add(r to ms) }, { r -> queued.removeAll { it.first === r } }, { expires++ })
    val content = BubbleStatusPresenter.Content("work", true, null, "")
    case("status duplicate does not reset TTL") { check(status.present(content, 2000)); val task = queued.single().first; check(!status.present(content, 2000)); check(queued.single().first === task); task.run(); queued.clear(); check(expires == 1) }
    case("status content change cancels stale expiry") { status.invalidate(); status.present(content, 2000); val old = queued.single().first; status.present(content.copy(image = "new"), 2000); check(queued.size == 1); old.run(); check(expires == 1) }
    case("status explicit sticky cancels timer") { check(status.present(content.copy(overrideMs = 0), 2000)); check(queued.isEmpty()) }
    case("status clear suppresses queued callbacks") { status.present(content, 2000); val old = queued.single().first; status.clear(); old.run(); check(queued.isEmpty()); check(expires == 1) }
}

private fun motionChecks() {
    val physics = BubblePhysics(1f); val motion = BubbleMotionController(physics)
    val bounds = BubbleMotionController.Bounds(300f, 10f, 800f)
    case("motion idle and stop are safe") { check(motion.step(0, bounds) == null); motion.start(20f, 20f, 0f, 0f, 0, null); check(motion.isRunning); motion.stop(); check(!motion.isRunning) }
    case("motion clamps delayed frame duration") { motion.start(20f, 20f, 20f, 20f, 0, null); val delayed = motion.step(1_000_000_000, bounds)!!; motion.start(20f, 20f, 20f, 20f, 0, null); val normal = motion.step(50_000_000, bounds)!!; close(delayed.x, normal.x); close(delayed.y, normal.y) }
    case("motion preserves upward escape from IME floor") { motion.start(50f, 750f, 0f, -1000f, 0, 500f); val frame = motion.step(16_000_000, bounds)!!; check(frame.y > 500f && frame.y < 750f); check(!frame.resting) }
    case("motion hits walls and rests at override floor") { motion.start(250f, 100f, 1800f, -500f, 0, 600f); var t = 0L; var landed = false; var last: BubbleMotionController.Frame? = null; repeat(10000) { if (motion.isRunning) { t += 16_000_000; last = motion.step(t, bounds); landed = landed || last!!.landed; check(last!!.x in 0f..300f) } }; check(!motion.isRunning); check(landed); close(last!!.y, 600f) }
}

private fun balanceChecks() {
    val balance = BubbleBalancePresenter()
    val snapshot = BalanceSnapshot(listOf(BalanceSnapshot.Wallet("CNY", "12.34")), costLast = BigDecimal("1.20"), at = 1)
    case("balance hides until ready") { check(!balance.present(true, false, "{balance}").visible) }
    case("balance compares decimal values and retains snapshot") { check(balance.submit(snapshot)); check(!balance.submit(snapshot.copy(costLast = BigDecimal("1.2000")))); check(balance.snapshot!!.costLast == BigDecimal("1.2000")) }
    case("balance template and visibility") { val model = balance.present(true, false, "{currency}{balance}"); check(model.visible && model.text == "¥12.34"); check(!balance.present(false, false, "{balance}").visible); balance.submit(snapshot.copy(costLast = BigDecimal.ZERO)); check(!balance.present(true, true, "{balance}").visible) }
}

private fun contentChecks() {
    val avatar = View(); val video = View(); val animator = BubbleContentAnimator(BubblePhysics(1f))
    val style = BubbleContentAnimator.Style(60, true, 1f, false)
    fun active() = ValueAnimator.all.count { it.running }
    case("content spin and image video pulses coexist") { animator.apply(avatar, video, style); check(active() == 5); animator.apply(avatar, video, style); check(active() == 5); check(ValueAnimator.all.size == 5) }
    case("content facing recreates signed pulse") { animator.apply(avatar, video, style.copy(flipX = true)); check(active() == 5); val x = ValueAnimator.all.filterIsInstance<ObjectAnimator>().last { it.running && it.property == View.SCALE_X }; check(x.values.first() == -1f) }
    case("content disabling pulse restores facing and release cancels all") { animator.apply(avatar, video, style.copy(pulse = false, flipX = true)); check(active() == 1); close(video.scaleX, -1f); animator.release(); check(active() == 0) }
    case("content squash targets visible video and restores base") { avatar.visibility = View.GONE; animator.bind(avatar, video, style.copy(scale = 2f)); animator.squash(0.55f); val a = ValueAnimator.all.last(); a.tick(0.3f); check(video.scaleY != 2f); close(avatar.scaleY, 1f); a.finish(); close(video.scaleY, 2f); animator.release(); check(active() == 0) }
}

private fun controllerChecks() {
    val controller = BubbleController()
    val firstStatus = EngineStatus(true, "one", "body", "title", "#ffffff", "tool")
    val firstTranscript = ChatTranscript("thread", true, listOf(ChatTranscript.Item("user", "hi")))
    val firstBalance = BalanceSnapshot(listOf(BalanceSnapshot.Wallet("CNY", "8.50")), at = 1)
    case("controller starts with independent empty snapshots") { check(controller.status == null && controller.transcript == null && controller.balance == null) }
    case("status update leaves unrelated snapshots intact") { controller.acceptStatus(firstStatus); check(controller.status === firstStatus); check(controller.transcript == null && controller.balance == null) }
    case("transcript and balance fill only their own slots") { controller.acceptTranscript(firstTranscript); controller.acceptBalance(firstBalance); check(controller.status === firstStatus); check(controller.transcript === firstTranscript); check(controller.balance === firstBalance) }
    case("status replacement includes false and empty fields") { val next = EngineStatus(false, "", "", "", "", ""); controller.acceptStatus(next); check(controller.status === next); check(controller.transcript === firstTranscript && controller.balance === firstBalance) }
    case("empty transcript replaces old conversation without touching balance") { val next = ChatTranscript("", false, emptyList()); controller.acceptTranscript(next); check(controller.transcript === next); check(controller.balance === firstBalance) }
    case("latest failed balance replaces older wallet as a whole") { val next = BalanceSnapshot(error = "offline"); controller.acceptBalance(next); check(controller.balance === next); check(controller.status?.running == false && controller.transcript?.items?.isEmpty() == true) }
    case("visibility hide never clears process snapshots") { val status = controller.status; val transcript = controller.transcript; val balance = controller.balance; controller.enabled = true; check(controller.configurationVisibility("hidden") == BubbleController.Visibility.HIDE); controller.onForeground("auto"); controller.onBackground("auto"); check(controller.status === status && controller.transcript === transcript && controller.balance === balance) }
    case("disabled and failed overlay preserve existing visibility policy") { controller.enabled = false; check(!controller.onBackground("expanded")); check(controller.configurationVisibility("expanded") == BubbleController.Visibility.KEEP); controller.enabled = true; controller.overlayProbe = false; check(!controller.onBackground("auto")); check(controller.configurationVisibility("collapsed") == BubbleController.Visibility.KEEP) }
    case("unknown overlay allows real probe and forced foreground state") { controller.overlayProbe = null; check(controller.onBackground("auto")); check(!controller.foreground); check(!controller.onForeground("expanded")); check(controller.foreground); check(controller.configurationVisibility("expanded") == BubbleController.Visibility.EXPAND); check(controller.configurationVisibility("collapsed") == BubbleController.Visibility.COLLAPSE); check(controller.configurationVisibility("auto") == BubbleController.Visibility.KEEP) }
    case("one tap source is atomic across callers") { controller.recordTapRefresh(); controller.recordTapRefresh(); val workers = List(8) { Thread { repeat(1000) { controller.recordTapRefresh() } } }; workers.forEach(Thread::start); workers.forEach(Thread::join); check(controller.tapRefreshSequence() == 8002) }
    val oldOwner = Any(); val newOwner = Any()
    val oldPort = object : BubblePanelActions { override fun sendText(text: String) = true; override fun stopRun() = true; override fun openApp() {} }
    val newPort = object : BubblePanelActions { override fun sendText(text: String) = false; override fun stopRun() = false; override fun openApp() {} }
    case("stale Activity detach cannot remove new panel binding") { controller.attachPanelActions(oldOwner, oldPort); check(controller.panelActions() === oldPort); controller.attachPanelActions(newOwner, newPort); controller.detachPanelActions(oldOwner); check(controller.panelActions() === newPort); controller.detachPanelActions(newOwner); check(controller.panelActions() == null) }
}

private class ActionRig {
    var now = 0L; var attached = true; var dragging = false; var canWalk = true
    var changed = 0; var presentationChanges = 0; var moved = 0; var sampleCount = 0; var randomValue = 0.5
    var afterAction: (() -> Unit)? = null; var afterMove: (() -> Unit)? = null
    val tasks = mutableListOf<Pair<Runnable, Long>>()
    val picks = mutableMapOf<String, ArrayDeque<String?>>()
    val calls = mutableListOf<String>()
    val presentations = mutableMapOf<String, BubbleActionController.Presentation>()
    val actions = BubbleActionController(
        assets = object : BubbleActionController.Assets {
            override fun pick(pool: String, excluding: String): String? { calls.add(pool); return picks[pool]?.removeFirstOrNull() }
            override fun presentation(state: String): BubbleActionController.Presentation { sampleCount++; return presentations[state] ?: BubbleActionController.Presentation() }
        },
        schedule = { r, delay -> tasks.add(r to delay) }, cancel = { r -> tasks.removeAll { it.first === r } },
        now = { now }, isAttached = { attached }, isDragging = { dragging }, canMove = { canWalk },
        onActionChanged = { changed++; afterAction?.invoke() }, onPresentationChanged = { presentationChanges++ },
        onMoveRequested = { moved++; afterMove?.invoke() }, random = { randomValue },
    )
    fun config(action: String = "", move: Boolean = false, pools: Map<String, List<String>> = emptyMap(), weights: Map<String, Int> = emptyMap()) = BubbleActionController.Config(action, mapOf("idle" to "rest", "working" to "work"), pools, weights, move, 100)
    fun runNext() { val (task, delay) = tasks.removeAt(0); now += delay; task.run() }
}

private fun startupChecks() {
    case("configuration is available before window timers start") { val r = ActionRig(); r.actions.configure(r.config(action = "fixed", move = true, pools = mapOf("idle" to listOf("a"))), startTimers = false); check(r.actions.wantedAction() == "fixed" && r.tasks.isEmpty()); r.actions.start(); check(r.tasks.size == 2) }
    case("appearance replacement clears turn cursor and cancels pending schedules") { val r = ActionRig(); r.randomValue = 0.9; r.picks["turn"] = ArrayDeque(listOf("turn")); r.actions.configure(r.config(move = true, pools = mapOf("turn" to listOf("turn")), weights = mapOf("turn" to 10))); r.actions.rollNextChainAction(); check(r.actions.chainIsTurn); val old = r.tasks.map { it.first }; r.actions.configure(r.config(action = "new"), startTimers = false); old.forEach(Runnable::run); check(!r.actions.chainIsTurn && r.actions.chainAction.isEmpty()); check(r.actions.wantedAction() == "new" && r.tasks.isEmpty()) }
}

private fun actionChecks() {
    case("action fallback with no pools schedules nothing") { val r = ActionRig(); r.actions.configure(BubbleActionController.Config()); check(r.actions.wantedAction() == "default"); check(r.tasks.isEmpty()) }
    case("action priority fixed override manual state pool mapped fallback") {
        val r = ActionRig(); r.presentations["working"] = BubbleActionController.Presentation("busy", "pooled", "image")
        r.actions.configure(r.config("fixed")); r.actions.applyRunState("working"); r.actions.setOverrideAction("override"); r.actions.playManualAction("manual", 0)
        check(r.actions.wantedAction() == "fixed"); r.actions.configure(r.config()); check(r.actions.wantedAction() == "override"); r.actions.setOverrideAction("  "); check(r.actions.wantedAction() == "manual"); r.actions.clearManualAction(); check(r.actions.wantedAction() == "pooled")
        r.presentations.clear(); r.actions.configure(r.config()); check(r.actions.wantedAction() == "work"); r.actions.applyRunState("thinking"); check(r.actions.wantedAction() == "rest")
    }
    case("action presentation samples only on state or appearance change") { val r = ActionRig(); r.presentations["thinking"] = BubbleActionController.Presentation("thinking", "think", "pic"); r.actions.configure(r.config()); r.actions.applyRunState("thinking"); check(r.sampleCount == 2); repeat(20) { check(r.actions.wantedAction() == "think") }; r.actions.applyRunState("thinking"); check(r.sampleCount == 2 && r.presentationChanges == 1); check(r.actions.presentation.image == "pic") }
    case("manual action overrides busy state then expires by clock") { val r = ActionRig(); r.actions.configure(r.config()); r.actions.applyRunState("working"); r.actions.playManualAction("click", 50); check(r.actions.wantedAction() == "click"); r.now = 50; check(r.actions.wantedAction() == "work"); check(r.tasks.isEmpty()) }
    case("stale manual expiry cannot clear replacement pose") { val r = ActionRig(); r.actions.configure(r.config()); r.actions.playManualAction("old", 10); val stale = r.tasks.single().first; r.actions.playManualAction("drag", 0); stale.run(); check(r.actions.manualAction == "drag"); check(r.tasks.isEmpty()) }
    case("manual expiry emits one refresh and clears its task") { val r = ActionRig(); r.actions.configure(r.config()); r.actions.playManualAction("click", 50); val before = r.changed; r.runNext(); check(r.actions.manualAction.isEmpty()); check(r.changed == before + 1 && r.tasks.isEmpty()) }
    case("idle chain precedes state pool but never busy mapping") { val r = ActionRig(); r.presentations["idle"] = BubbleActionController.Presentation(action = "state-idle"); r.picks["idle"] = ArrayDeque(listOf("play")); r.actions.configure(r.config(pools = mapOf("idle" to listOf("play")))); r.actions.rollNextChainAction(); check(r.actions.wantedAction() == "play"); r.actions.applyRunState("working"); check(r.actions.chainAction.isEmpty()); check(r.actions.wantedAction() == "work"); check(r.tasks.isEmpty()) }
    case("chain repeat cursor retries at most once") { val r = ActionRig(); r.picks["idle"] = ArrayDeque(listOf("a", "a", "b")); r.actions.configure(r.config(pools = mapOf("idle" to listOf("a", "b")))); r.actions.rollNextChainAction(); r.actions.rollNextChainAction(); check(r.actions.chainAction == "b"); check(r.calls.size == 3) }
    case("turn flag is based on chosen pool and consumed once") { val r = ActionRig(); r.randomValue = 0.9; r.picks["turn"] = ArrayDeque(listOf("shared")); r.actions.configure(r.config(pools = mapOf("idle" to listOf("shared"), "turn" to listOf("shared")), weights = mapOf("turn" to 10))); r.actions.rollNextChainAction(); check(r.actions.chainAction == "shared" && r.actions.chainIsTurn); check(r.actions.consumeTurnFlip()); check(!r.actions.consumeTurnFlip()) }
    case("missing turn asset falls back to idle") { val r = ActionRig(); r.randomValue = 0.9; r.picks["idle"] = ArrayDeque(listOf("idle")); r.actions.configure(r.config(pools = mapOf("idle" to listOf("idle"), "turn" to listOf("missing")), weights = mapOf("turn" to 10))); r.actions.rollNextChainAction(); check(r.calls == listOf("turn", "idle")); check(r.actions.chainAction == "idle" && !r.actions.chainIsTurn) }
    case("zero idle weight suppresses random chain") { val r = ActionRig(); r.actions.configure(r.config(pools = mapOf("idle" to listOf("a")), weights = mapOf("idle" to 0))); r.actions.rollNextChainAction(); check(r.calls.isEmpty() && r.actions.chainAction.isEmpty()) }
    case("chain timer defers while dragging and advances after release") { val r = ActionRig(); r.picks["idle"] = ArrayDeque(listOf("a")); r.actions.configure(r.config(pools = mapOf("idle" to listOf("a")))); r.dragging = true; r.runNext(); check(r.calls.isEmpty() && r.tasks.size == 1); r.dragging = false; r.runNext(); check(r.actions.chainAction == "a" && r.tasks.size == 1) }
    case("detached chain and movement callbacks never continue polling") { val r = ActionRig(); r.actions.configure(r.config(move = true, pools = mapOf("idle" to listOf("a")))); r.attached = false; r.runNext(); r.runNext(); check(r.tasks.isEmpty()) }
    case("movement is independent of busy state and respects window gate") { val r = ActionRig(); r.actions.configure(r.config(move = true)); r.actions.applyRunState("working"); r.canWalk = false; r.runNext(); check(r.moved == 0 && r.tasks.single().second == 100L); r.canWalk = true; r.runNext(); check(r.moved == 1 && r.tasks.size == 1) }
    case("release removes chain move and pose timers and stale dispatches") { val r = ActionRig(); r.actions.configure(r.config(move = true, pools = mapOf("idle" to listOf("a")))); r.actions.playManualAction("click", 50); val old = r.tasks.map { it.first }; r.actions.release(); check(r.tasks.isEmpty() && r.actions.manualAction.isEmpty()); old.forEach(Runnable::run); check(r.tasks.isEmpty() && r.moved == 0) }
    case("synchronous hide during chain refresh cannot requeue") { val r = ActionRig(); r.picks["idle"] = ArrayDeque(listOf("a")); r.actions.configure(r.config(pools = mapOf("idle" to listOf("a")))); r.afterAction = { r.actions.release() }; r.runNext(); check(r.tasks.isEmpty()) }
    case("synchronous hide during walking request cannot requeue") { val r = ActionRig(); r.actions.configure(r.config(move = true)); r.afterMove = { r.actions.release() }; r.runNext(); check(r.tasks.isEmpty() && r.moved == 1) }
    case("state derivation gives approval priority over tool activity") { check(BubbleActionController.deriveRunState(true, "tool", "等待确认运行命令") == "waiting"); check(BubbleActionController.deriveRunState(true, "tool", "执行了") == "working"); check(BubbleActionController.deriveRunState(true, "", "...") == "thinking"); check(BubbleActionController.deriveRunState(false, "tool", "运行") == "idle") }
}

fun main(args: Array<String>) {
    when (args.single()) {
        "transcript" -> transcriptChecks()
        "frames" -> frameChecks()
        "status" -> statusChecks()
        "motion" -> motionChecks()
        "balance" -> balanceChecks()
        "content" -> contentChecks()
        "controller" -> controllerChecks()
        "startup" -> startupChecks()
        "actions" -> actionChecks()
        else -> error("Unknown suite")
    }
    println("RESULT: $count checks passed; 0 skipped")
}
