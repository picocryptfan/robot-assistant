package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The :mt process's IPC (docs/conversation-mode.md 4.5) and its queue of waiting lines. */
class MtProtocolTest {
    private fun roundTrip(what: Int, m: MtMsg) = assertEquals(m, MtMsg.decode(what, m.toJson().toString()))

    @Test fun everyMessageSurvivesTheWire() {
        roundTrip(MtProtocol.REQ_LOAD, MtMsg.Load("fr-en"))
        roundTrip(MtProtocol.REQ_TRANSLATE, MtMsg.Translate("en-fr", listOf("Where is the nearest pharmacy?", "lol ok 🙂"), 1_500, MtPriority.INTERACTIVE))
        roundTrip(MtProtocol.REQ_CANCEL, MtMsg.Cancel(7))
        roundTrip(MtProtocol.REQ_STATUS, MtMsg.Empty)
        roundTrip(MtProtocol.REQ_UNLOAD, MtMsg.Empty)
        roundTrip(MtProtocol.EV_UNLOADED, MtMsg.Empty)
        roundTrip(MtProtocol.EV_STATUS, MtMsg.Status(true, listOf("en-fr", "fr-en"), 3, MtMemory(301_000, 320_000)))
        roundTrip(MtProtocol.EV_LOADED, MtMsg.Loaded("fr-en", mapOf("en-fr" to 205L, "fr-en" to 174L), MtMemory(290_000, 293_000)))
        roundTrip(MtProtocol.EV_TRANSLATED, MtMsg.Translated(listOf("Yes", null, "Où est la pharmacie la plus proche ?"), listOf(12L, 0L, 23L), 1, 0, MtMemory.UNKNOWN))
        for (e in MtError.entries) roundTrip(MtProtocol.EV_ERROR, MtMsg.Error(e))
    }

    @Test fun whatIsNotUnderstoodDecodesToNothing() {
        assertNull(MtMsg.decode(MtProtocol.REQ_TRANSLATE, "{"))
        assertNull(MtMsg.decode(MtProtocol.REQ_TRANSLATE, """{"direction":"fr-en","lines":["a"],"deadlineMs":5,"priority":"SOON"}"""))
        assertNull(MtMsg.decode(MtProtocol.EV_ERROR, """{"error":"ON_FIRE"}"""))
        assertNull(MtMsg.decode(999, "{}"))
        assertTrue(MtProtocol.REQUESTS.intersect(MtProtocol.EVENTS).isEmpty())
        assertEquals("app.heyrobot.mt.MtService", MtProtocol.SERVICE_CLASS)
        // Codes in the voice log stay what they were.
        assertEquals(MtError.entries.size, MtError.entries.map { it.id }.toSet().size)
        assertEquals(1, MtError.NO_ENGINE.id)
        assertEquals(MtError.TIMEOUT, MtError.of("TIMEOUT"))
    }

    @Test fun aTranslateRequestIsBounded() {
        fun t(dir: String = "fr-en", lines: List<String> = listOf("Oui"), deadline: Long = 1_000) = MtMsg.Translate(dir, lines, deadline, MtPriority.BATCH).problem()
        assertNull(t())
        assertNull(t(lines = List(MtPolicy.MAX_LINES) { "Oui" }))
        assertNull(t(lines = listOf("a".repeat(MtPolicy.MAX_CHARS))))
        assertEquals(MtError.BAD_REQUEST, t(dir = "de-en"))
        assertEquals(MtError.BAD_REQUEST, t(lines = emptyList()))
        assertEquals(MtError.BAD_REQUEST, t(lines = List(MtPolicy.MAX_LINES + 1) { "Oui" }))
        assertEquals(MtError.BAD_REQUEST, t(lines = listOf("a".repeat(MtPolicy.MAX_CHARS + 1))))
        assertEquals(MtError.BAD_REQUEST, t(deadline = 0))
    }

    @Test fun memoryComesFromProcStatus() {
        val status = "Name:\tapp.heyrobot:mt\nVmPeak:\t 9000000 kB\nVmHWM:\t  320512 kB\nVmRSS:\t  301234 kB\nThreads:\t12\n"
        assertEquals(MtMemory(301_234, 320_512), MtMemory.parse(status))
        assertEquals(MtMemory.UNKNOWN, MtMemory.parse(null))
        assertEquals(MtMemory(-1, 5), MtMemory.parse("VmHWM: 5 kB"))
    }

    @Test fun anInteractiveLineWaitsAtMostForTheLineBeingTranslated() {
        val q = MtQueue()
        val batch = q.add(1, "fr-en", listOf("a", "b", "c"), MtPriority.BATCH, 10_000)!!
        val first = q.take(0) as MtQueue.Task.Line
        assertEquals(batch to 0, first.job to first.index)
        // An interactive request arrives while "a" is being translated: it is next.
        val now = q.add(2, "en-fr", listOf("x"), MtPriority.INTERACTIVE, 10_000)!!
        assertEquals(MtQueue.Task.Line(now, 0), q.take(10))
        now.out[0] = "X"
        assertEquals(MtQueue.Task.Finished(now), q.take(20))
        assertEquals(MtQueue.Task.Line(batch, 1), q.take(30))
        assertEquals(MtQueue.Task.Line(batch, 2), q.take(40))
        assertEquals(MtQueue.Task.Finished(batch), q.take(50))
        assertEquals(0, batch.timedOut)
        assertNull(q.take(60))
        assertTrue(q.isEmpty)
    }

    @Test fun sameIsFirstInFirstOut() {
        val q = MtQueue()
        val a = q.add(1, "fr-en", listOf("a"), MtPriority.BATCH, 10_000)!!
        val b = q.add(2, "fr-en", listOf("b"), MtPriority.BATCH, 10_000)!!
        assertEquals(MtQueue.Task.Line(a, 0), q.take(0))
        assertEquals(MtQueue.Task.Finished(a), q.take(0))
        assertEquals(MtQueue.Task.Line(b, 0), q.take(0))
    }

    @Test fun linesNotStartedByTheDeadlineComeBackEmpty() {
        val q = MtQueue()
        val j = q.add(1, "fr-en", listOf("a", "b", "c"), MtPriority.BATCH, 100)!!
        assertEquals(MtQueue.Task.Line(j, 0), q.take(50))
        j.out[0] = "A"
        // The deadline passed while "a" was translated: "b" and "c" are never started.
        assertEquals(MtQueue.Task.Finished(j), q.take(100))
        assertEquals(2, j.timedOut)
        assertEquals(listOf("A", null, null), j.out.toList())
    }

    @Test fun aCancelledRequestFinishesAtOnce() {
        val q = MtQueue()
        val j = q.add(1, "fr-en", listOf("a", "b"), MtPriority.BATCH, 10_000)!!
        q.take(0)
        assertTrue(q.cancel(1))
        assertFalse(q.cancel(2))
        val done = q.take(1) as MtQueue.Task.Finished
        assertTrue(done.job.cancelled)
        assertEquals(1, j.timedOut)
    }

    @Test fun tooManyWaitingLinesIsBusyAndAnUnloadAnswersEveryone() {
        val q = MtQueue(maxWaiting = 4)
        assertTrue(q.add(1, "fr-en", listOf("a", "b", "c"), MtPriority.BATCH, 10_000) != null)
        assertNull(q.add(2, "fr-en", listOf("d", "e"), MtPriority.INTERACTIVE, 10_000))
        assertTrue(q.add(3, "fr-en", listOf("d"), MtPriority.INTERACTIVE, 10_000) != null)
        assertEquals(4, q.waiting)
        assertEquals(listOf(1, 3), q.drain().map { it.id })
        assertTrue(q.isEmpty)
        assertEquals(0, q.waiting)
    }
}
