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
}
