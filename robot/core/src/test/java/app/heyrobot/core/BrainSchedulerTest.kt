package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * :brain's scheduler (docs/conversation-mode.md 4.2, phase 5.4) with a fake engine: what each client hears (EV_QUEUED,
 * the start, EV_DONE when dropped or cancelled), work that ends inside its own start, two clients numbering alike, the
 * hold for the panel's tool results and its lapse.
 */
class BrainSchedulerTest {
    private var now = 0L
    private val events = mutableListOf<String>()
    private val sched = BrainScheduler({ now })

    /** A request as GemmaEngine runs it, writing what its client would get. */
    private inner class FakeWork(val name: String, override val priority: Priority, val failAtStart: Boolean = false) : BrainScheduler.Work {
        var id = -1
        var starts = 0
        var stopped: Boolean? = null

        override fun start(id: Int) {
            this.id = id
            starts++
            events += "$name:start"
            // Refused before it is sent (too long, not loaded): it ends inside its start.
            if (failAtStart) {
                events += "$name:error"
                sched.ended(id)
            }
        }

        override fun stop(giveWay: Boolean) {
            stopped = giveWay
            events += "$name:stop" + if (giveWay) "(give way)" else ""
        }

        override fun queued(position: Int) { events += "$name:queued $position" }

        override fun dropped(unloaded: Boolean) { events += "$name:" + if (unloaded) "not loaded" else "cancelled" }

        /** The engine's run ended: a terminal event, unless it stopped to give way. */
        fun end() {
            val gaveWay = stopped == true
            if (!gaveWay) events += "$name:done"
            stopped = null
            sched.ended(id, terminalSent = !gaveWay)
        }
    }

    private val panel = Any()
    private val voice = Any()

    private fun submit(client: Any, req: Int, w: FakeWork): FakeWork = w.also { sched.submit(BrainScheduler.Key(client, req), it) }

    @Test fun aWaitingRequestIsToldItsPlaceAndWhenItStarts() {
        val a = submit(voice, 1, FakeWork("interp", Priority.INTERPRETER))
        val q = submit(panel, 1, FakeWork("panel", Priority.PANEL))
        assertEquals(listOf("interp:queued 0", "interp:start", "panel:queued 1"), events)
        // The panel's question waits for the interpreter turn: never a cancel.
        assertNull(a.stopped)
        a.end()
        assertEquals(listOf("interp:queued 0", "interp:start", "panel:queued 1", "interp:done", "panel:queued 0", "panel:start"), events)
        assertEquals(q.id, sched.running)
    }

    @Test fun twoClientsWithTheSameNumbersNeverCancelEachOther() {
        val a = submit(voice, 7, FakeWork("summary", Priority.SUMMARY))
        val b = submit(panel, 7, FakeWork("panel", Priority.PANEL))
        events.clear()
        // The panel cancels its own request 7, which waits behind the summary giving way.
        sched.cancel(BrainScheduler.Key(panel, 7))
        assertEquals(listOf("panel:cancelled"), events)
        assertEquals(true, a.stopped)
        assertEquals(-1, sched.position(BrainScheduler.Key(panel, 7)))
        assertEquals(0, sched.position(BrainScheduler.Key(voice, 7)))
        assertEquals(-1, b.id)
    }

    @Test fun aSummaryStepGivesWayStartsOverAndIsCancelledCleanly() {
        val s = submit(voice, 1, FakeWork("summary", Priority.SUMMARY))
        val i = submit(voice, 2, FakeWork("interp", Priority.INTERPRETER))
        assertEquals(true, s.stopped)
        events.clear()
        s.end()
        // No EV_DONE for the step that gave way: EV_QUEUED again (its client drops what it had), then the turn runs.
        assertEquals(listOf("summary:queued 1", "interp:queued 0", "interp:start"), events)
        events.clear()
        i.end()
        assertEquals(listOf("interp:done", "summary:queued 0", "summary:start"), events)
        assertEquals(2, s.starts)
        // Giving way again, then cancelled by its client meanwhile: it ends as cancelled, not queued again.
        submit(panel, 1, FakeWork("panel", Priority.PANEL))
        assertEquals(true, s.stopped)
        sched.cancel(BrainScheduler.Key(voice, 1))
        events.clear()
        s.end()
        assertEquals(listOf("summary:cancelled", "panel:queued 0", "panel:start"), events)
    }

    @Test fun workThatEndsInsideItsStartLetsTheNextOneStart() {
        submit(voice, 1, FakeWork("a", Priority.INTERPRETER))
        submit(voice, 2, FakeWork("b", Priority.INTERPRETER, failAtStart = true))
        val c = submit(voice, 3, FakeWork("c", Priority.TRANSCRIPT))
        val a = FakeWork("x", Priority.PANEL)
        events.clear()
        // a ends: b starts and fails at once, c starts next, all in order.
        sched.ended(sched.running!!)
        assertEquals(listOf("b:queued 0", "b:start", "b:error", "c:queued 0", "c:start"), events)
        assertEquals(c.id, sched.running)
        assertEquals(-1, a.id)
    }

    @Test fun thePanelsNewestQuestionDropsAWaitingOne() {
        val first = submit(panel, 1, FakeWork("q1", Priority.PANEL))
        submit(panel, 2, FakeWork("q2", Priority.PANEL))
        assertEquals(false, first.stopped)
        submit(panel, 3, FakeWork("q3", Priority.PANEL))
        assertTrue("q2:cancelled" in events)
        events.clear()
        first.end()
        assertEquals(listOf("q1:done", "q3:queued 0", "q3:start"), events)
    }

    @Test fun theHoldForToolResultsKeepsOneShotsWaitingThenLapses() {
        val q = submit(panel, 1, FakeWork("panel", Priority.PANEL))
        // The reply asks for tools: the hold goes on before it ends.
        sched.holdForTools(true)
        q.end()
        submit(voice, 1, FakeWork("interp", Priority.INTERPRETER))
        assertTrue(sched.holding)
        assertEquals(listOf("panel:queued 0", "panel:start", "panel:done", "interp:queued 1"), events)
        // The tool results start at once (a panel request).
        val r = submit(panel, 2, FakeWork("results", Priority.PANEL))
        assertEquals(r.id, sched.running)
        sched.holdForTools(false)
        assertNull(sched.holdEndsAt())
        r.end()
        assertTrue(events.last() == "interp:start")
        // Another tool round whose card stays open: the hold lapses after TOOL_HOLD_MS, never before.
        events.clear()
        val again = submit(panel, 3, FakeWork("again", Priority.PANEL))
        assertTrue(events.isEmpty() || events == listOf("again:queued 1"))
        sched.ended(sched.running!!)
        sched.holdForTools(true)
        again.end()
        submit(voice, 2, FakeWork("line", Priority.TRANSCRIPT))
        assertEquals(BrainScheduler.TOOL_HOLD_MS, sched.holdEndsAt())
        now = BrainScheduler.TOOL_HOLD_MS - 1
        sched.expire()
        assertFalse(events.contains("line:start"))
        now = BrainScheduler.TOOL_HOLD_MS
        sched.expire()
        assertTrue(events.contains("line:start"))
        assertFalse(sched.holding)
        // Still waiting for the same results: the lapsed hold doesn't come back until that wait ends.
        sched.holdForTools(true)
        assertFalse(sched.holding)
        sched.holdForTools(false)
        sched.holdForTools(true)
        assertTrue(sched.holding)
    }

    @Test fun anUnloadEndsEveryoneWaitingAsNotLoaded() {
        val a = submit(voice, 1, FakeWork("a", Priority.INTERPRETER))
        submit(panel, 1, FakeWork("p", Priority.PANEL))
        submit(voice, 2, FakeWork("s", Priority.SUMMARY))
        events.clear()
        sched.dropWaiting()
        assertEquals(listOf("p:not loaded", "s:not loaded"), events)
        a.end()
        assertNull(sched.running)
        assertTrue(sched.waiting.isEmpty())
    }

    /** Asked to give way just as it ended on its own (its cap reached): its client has its end, so it is never run again. */
    @Test fun aStepThatEndedAsItWasAskedToGiveWayIsNotRunAgain() {
        val s = submit(voice, 1, FakeWork("summary", Priority.SUMMARY))
        submit(panel, 1, FakeWork("panel", Priority.PANEL))
        assertEquals(true, s.stopped)
        events.clear()
        // The engine's run had already ended (LENGTH): it sends EV_DONE, not a requeue.
        events += "summary:done"
        sched.ended(s.id, terminalSent = true)
        assertEquals(listOf("summary:done", "panel:queued 0", "panel:start"), events)
        assertTrue(sched.waiting.isEmpty())
        assertEquals(1, s.starts)
    }
}
