package com.dsh.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real owner transitions, without Android framework calls or timing sleeps. */
class UiOwnerStateTest {
    @Test fun permissionsCoalesceAndKeepDifferentRequestsSerial() {
        val launched = mutableListOf<String>()
        val answers = mutableListOf<String>()
        val queue = RuntimePermissionQueue({ false }, { launched.add(it) }, { _, failure -> throw failure })
        queue.request("audio") { answers.add("first:$it") }
        queue.request("audio") { answers.add("second:$it") }
        queue.request("camera") { answers.add("camera:$it") }
        assertEquals(listOf("audio"), launched)
        queue.finish(true)
        assertEquals(listOf("first:true", "second:true"), answers)
        assertEquals(listOf("audio", "camera"), launched)
        queue.finish(false)
        assertEquals("camera:false", answers.last())
        assertNull(queue.activePermission)
    }

    @Test fun callbackFailureDoesNotLoseOtherSinksAndReentryCreatesNewBatch() {
        val launched = mutableListOf<String>()
        val answers = mutableListOf<Boolean>()
        var failures = 0
        val queue = RuntimePermissionQueue({ false }, { launched.add(it) }, { _, _ -> failures++ })
        queue.request("audio") {
            queue.request("audio") { answers.add(it) }
            error("consumer disappeared")
        }
        queue.request("audio") { answers.add(it) }
        queue.finish(true)
        assertEquals(1, failures)
        assertEquals(listOf(true), answers)
        assertEquals(listOf("audio", "audio"), launched)
        queue.finish(false)
        assertEquals(listOf(true, false), answers)
    }

    @Test fun destructionSettlesPendingOnceAndRejectsCallbackReentry() {
        val answers = mutableListOf<String>()
        var launches = 0
        val queue = RuntimePermissionQueue({ false }, { launches++ }, { _, failure -> throw failure })
        queue.request("audio") {
            answers.add("audio:$it")
            queue.request("late") { answers.add("late:$it") }
        }
        queue.request("camera") { answers.add("camera:$it") }
        queue.close()
        queue.close()
        queue.finish(true)
        assertEquals(listOf("audio:false", "late:false", "camera:false"), answers)
        assertEquals(1, launches)
        assertNull(queue.activePermission)
    }

    @Test fun staleAckCannotConsumeReplacementAtSameFilePath() {
        val pane = BrowserPaneCoordinator()
        val file = File("same.png")
        assertTrue(pane.submitAnnotation(pane.beginAnnotation()!!, file, "first"))
        val first = pane.readAnnotation(false) { it.id }
        assertTrue(pane.submitAnnotation(pane.beginAnnotation()!!, file, "second"))
        val second = pane.readAnnotation(false) { it.id }
        assertNotEquals(first, second)
        pane.acknowledgeAnnotation(first)
        assertEquals("second", pane.readAnnotation(false) { it.question })
        pane.acknowledgeAnnotation(second)
        assertEquals("", pane.readAnnotation(false) { it.question })
    }

    @Test fun replacingCanvasRejectsOldCancelAndOldSend() {
        val pane = BrowserPaneCoordinator()
        val old = pane.beginAnnotation()!!
        val current = pane.beginAnnotation()!!
        assertFalse(pane.cancelAnnotation(old))
        assertFalse(pane.submitAnnotation(old, File("old.png"), "old"))
        assertTrue(pane.submitAnnotation(current, File("current.png"), "current"))
        assertEquals("current", pane.readAnnotation(false) { it.question })
    }

    @Test fun destroyedPaneDiscardsEncodingAndCannotBeReopenedByLateCallbacks() {
        val pane = BrowserPaneCoordinator()
        val canvas = pane.beginAnnotation()!!
        assertTrue(pane.submitAnnotation(canvas, File("pending.png"), "pending"))
        assertEquals("", pane.readAnnotation(false) { pane.close(); it.question })
        assertNull(pane.beginAnnotation())
        assertFalse(pane.submitAnnotation(canvas, File("late.png"), "late"))
        assertFalse(pane.cancelAnnotation(canvas))
        pane.embedded()
        pane.openByUser()
        assertFalse(pane.state.visible)
        assertFalse(pane.state.embedded)
    }

    @Test fun destroyedFirstFrameOwnerDropsAnAlreadyDequeuedDeadline() {
        val scheduler = Scheduler()
        val frames = mutableListOf<() -> Unit>()
        var timeout = 0
        var ready = 0
        val awaiter = FirstFrameAwaiter(scheduler, frames::add, {}, { timeout++ })
        awaiter.await { ready++ }
        val pending = scheduler.tasks.single()
        awaiter.close()
        assertTrue(scheduler.tasks.isEmpty())
        pending.run()
        frames.single()()
        assertEquals(0, timeout)
        assertEquals(0, ready)
    }

    @Test fun overlayFailureButtonsAreStateSpecificAndNotTimerActions() {
        val scheduler = Scheduler()
        val rendered = mutableListOf<BootOverlayPresenter.State>()
        val overlay = BootOverlayPresenter(scheduler, { "" }, rendered::add, {})
        overlay.arm()
        val pending = scheduler.tasks.single()
        overlay.reopen()
        val failed = BootOverlayPresenter.State("engine failed", "details", null, true, BootOverlayPresenter.Action.RetryEngine)
        overlay.show(failed)
        pending.run()
        assertEquals(listOf(failed), rendered)
    }

    private class Scheduler : UiTaskScheduler {
        val tasks = mutableListOf<Runnable>()
        override fun postDelayed(task: Runnable, delayMs: Long) { tasks.add(task) }
        override fun remove(task: Runnable) { tasks.remove(task) }
    }
}
