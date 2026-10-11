package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order :brain serves requests in once conversation mode shares Gemma (docs/conversation-mode.md 4.2). */
class BrainQueueTest {
    private fun job(id: Int, p: Priority) = BrainQueue.Job(id, p)

    @Test fun thePanelsNewestQuestionStillWins() {
        val q = BrainQueue()
        assertEquals(job(1, Priority.PANEL), q.submit(job(1, Priority.PANEL)).start)
        // A newer question cancels the running panel reply and waits for it to end.
        val second = q.submit(job(2, Priority.PANEL))
        assertTrue(second.cancelRunning)
        assertNull(second.start)
        // A third before the reply has stopped: the second never runs, the running one is not cancelled twice.
        val third = q.submit(job(3, Priority.PANEL))
        assertFalse(third.cancelRunning)
        assertEquals(listOf(job(2, Priority.PANEL)), third.dropped)
        assertEquals(job(3, Priority.PANEL), q.done(1).start)
        assertEquals(BrainQueue.Step(), q.done(1))
        assertEquals(job(3, Priority.PANEL), q.running)
    }

    @Test fun anInterpreterTurnIsNeverCancelledByThePanel() {
        val q = BrainQueue()
        q.submit(job(1, Priority.INTERPRETER))
        val panel = q.submit(job(2, Priority.PANEL))
        assertFalse(panel.cancelRunning)
        assertEquals(1, q.position(2))
        // More interpreter and transcript turns go behind the panel, in the order they came.
        q.submit(job(3, Priority.TRANSCRIPT))
        q.submit(job(4, Priority.INTERPRETER))
        q.submit(job(5, Priority.INTERPRETER))
        assertEquals(listOf(2, 4, 5, 3), q.queue.map { it.id })
        assertEquals(0, q.position(1))
        assertEquals(4, q.position(3))
        assertEquals(-1, q.position(99))
        assertEquals(2, q.done(1).start?.id)
        assertEquals(4, q.done(2).start?.id)
        assertEquals(5, q.done(4).start?.id)
        assertEquals(3, q.done(5).start?.id)
        assertNull(q.done(3).start)
        assertNull(q.running)
    }

    @Test fun aSummaryStepGivesWayAndIsQueuedAgain() {
        val q = BrainQueue()
        q.submit(job(1, Priority.SUMMARY))
        q.submit(job(2, Priority.SUMMARY))
        val turn = q.submit(job(3, Priority.INTERPRETER))
        assertTrue(turn.cancelRunning)
        // The cancelled step ends; the interpreter turn starts and the step waits again, ahead of the next summary step.
        val after = q.done(1)
        assertEquals(job(3, Priority.INTERPRETER), after.start)
        assertEquals(job(1, Priority.SUMMARY), after.requeued)
        assertEquals(listOf(1, 2), q.queue.map { it.id })
        // Another summary step never cancels one.
        assertFalse(q.submit(job(4, Priority.SUMMARY)).cancelRunning)
        assertEquals(1, q.done(3).start?.id)
        assertTrue(q.submit(job(5, Priority.PANEL)).cancelRunning)
        assertEquals(5, q.done(1).start?.id)
        assertEquals(listOf(1, 2, 4), q.queue.map { it.id })
    }

    @Test fun theClientCanCancelWhatWaitsOrRuns() {
        val q = BrainQueue()
        q.submit(job(1, Priority.SUMMARY))
        q.submit(job(2, Priority.TRANSCRIPT))
        // The transcript line was 60 s behind: dropped while it waits.
        q.submit(job(3, Priority.INTERPRETER))
        assertEquals(listOf(job(2, Priority.TRANSCRIPT)), q.cancel(2).dropped)
        // The running step was already giving way; cancelling it too means it is not queued again.
        assertEquals(BrainQueue.Step(), q.cancel(1))
        val end = q.done(1)
        assertNull(end.requeued)
        assertEquals(3, end.start?.id)
        assertTrue(q.cancel(3).cancelRunning)
        assertEquals(BrainQueue.Step(), q.cancel(42))
    }

    @Test fun aTranscriptTranslationGivesWayToThePanelAndTheInterpreter() {
        assertTrue(BrainQueue.yields(Priority.TRANSCRIPT, Priority.PANEL))
        assertTrue(BrainQueue.yields(Priority.TRANSCRIPT, Priority.INTERPRETER))
        assertFalse(BrainQueue.yields(Priority.TRANSCRIPT, Priority.TRANSCRIPT))
        assertFalse(BrainQueue.yields(Priority.TRANSCRIPT, Priority.SUMMARY))
        assertFalse(BrainQueue.yields(Priority.INTERPRETER, Priority.PANEL))
        assertFalse(BrainQueue.yields(Priority.PANEL, Priority.INTERPRETER))
        val q = BrainQueue()
        q.submit(job(1, Priority.TRANSCRIPT))
        q.submit(job(2, Priority.TRANSCRIPT))
        assertTrue(q.submit(job(3, Priority.PANEL)).cancelRunning)
        assertTrue(q.givingWay(1))
        val after = q.done(1)
        assertEquals(3, after.start?.id)
        assertEquals(1, after.requeued?.id)
        // Back ahead of the other line, behind nothing else.
        assertEquals(listOf(1, 2), q.queue.map { it.id })
    }

    @Test fun whileThePanelWaitsForItsToolResultsOnlyPanelRequestsStart() {
        val q = BrainQueue()
        assertEquals(1, q.submit(job(1, Priority.PANEL)).start?.id)
        q.hold(true)
        assertNull(q.done(1).start)
        // A translation waits for the tool round; the tool results (a panel request) go first.
        assertNull(q.submit(job(2, Priority.INTERPRETER)).start)
        assertNull(q.submit(job(3, Priority.SUMMARY)).start)
        assertEquals(1, q.position(2))
        assertEquals(4, q.submit(job(4, Priority.PANEL)).start?.id)
        // The tool round asks for more tools: still held.
        assertNull(q.done(4).start)
        assertEquals(BrainQueue.Step(), q.hold(true))
        // The reply ends: the interpreter turn starts at once.
        assertEquals(2, q.hold(false).start?.id)
        assertEquals(3, q.done(2).start?.id)
    }

    @Test fun releasingTheHoldWhileARequestRunsStartsNothingYet() {
        val q = BrainQueue()
        q.submit(job(1, Priority.PANEL))
        q.hold(true)
        q.submit(job(2, Priority.TRANSCRIPT))
        assertEquals(BrainQueue.Step(), q.hold(false))
        assertEquals(2, q.done(1).start?.id)
    }

    @Test fun anUnloadedModelDropsEveryoneWaiting() {
        val q = BrainQueue()
        q.submit(job(1, Priority.INTERPRETER))
        q.submit(job(2, Priority.PANEL))
        q.submit(job(3, Priority.SUMMARY))
        assertEquals(listOf(2, 3), q.dropWaiting().map { it.id })
        assertTrue(q.queue.isEmpty())
        assertNull(q.done(1).start)
    }
}
