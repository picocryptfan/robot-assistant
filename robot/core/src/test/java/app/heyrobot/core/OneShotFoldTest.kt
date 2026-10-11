package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A one-shot's events as its client folds them (phase 5.4): the start, giving way, the end. */
class OneShotFoldTest {
    @Test fun aRequestThatWaitedThenAnswered() {
        val f = OneShotFold(t0 = 1_000)
        f.event(BrainMsg.Queued(2), 1_010)
        assertFalse(f.started)
        f.event(BrainMsg.Queued(0), 1_900)
        assertTrue(f.started)
        assertTrue(f.event(BrainMsg.Chunk("Hello"), 2_100))
        f.event(BrainMsg.Chunk(" there."), 2_200)
        f.event(BrainMsg.Done(DoneReason.END, 30, 200, 17.0, true), 2_300)
        assertEquals(OneShotResult.Done("Hello there.", DoneReason.END, waitedMs = 900, ms = 1_300), f.result)
        // Nothing after the end counts.
        assertFalse(f.event(BrainMsg.Chunk("more"), 2_400))
    }

    @Test fun aSummaryStepThatGaveWayStartsOverFromNothing() {
        val f = OneShotFold(0)
        f.event(BrainMsg.Queued(0), 0)
        f.event(BrainMsg.Chunk("- [14:03] half a note"), 10)
        // Queued again: it gave way; the half note goes.
        assertTrue(f.event(BrainMsg.Queued(1), 20))
        assertEquals("", f.soFar)
        assertEquals(1, f.restarts)
        assertFalse(f.started)
        f.event(BrainMsg.Queued(0), 30)
        f.event(BrainMsg.Chunk("- [14:03] a whole note"), 40)
        f.event(BrainMsg.Done(DoneReason.LENGTH, 0, 0, 0.0, true), 50)
        assertEquals("- [14:03] a whole note", (f.result as OneShotResult.Done).text)
        assertEquals(DoneReason.LENGTH, (f.result as OneShotResult.Done).reason)
    }

    @Test fun cancelledRefusedAndGone() {
        val c = OneShotFold(0)
        c.event(BrainMsg.Done(DoneReason.CANCELLED, -1, -1, -1.0, false), 5)
        assertEquals(OneShotResult.Cancelled, c.result)
        val e = OneShotFold(0)
        e.event(BrainMsg.Error(BrainError.TOO_LONG, null), 5)
        assertEquals(OneShotResult.Failed(BrainError.TOO_LONG), e.result)
        val n = OneShotFold(0)
        n.event(BrainMsg.Error(BrainError.NOT_LOADED, null), 5)
        assertEquals(OneShotResult.Failed(BrainError.NOT_LOADED, unavailable = true), n.result)
        // :brain died: the stream closed without an end.
        assertEquals(OneShotResult.Failed(BrainError.ENGINE, unavailable = true), OneShotFold(0).closed())
    }

    /** :brain stopped a broken reply (pad tokens, one character over and over): never a translation or a summary's note. */
    @Test fun aBrokenReplyIsAFailureNotAnAnswer() = runBlocking {
        val g = OneShotFold(0)
        g.event(BrainMsg.Queued(0), 0)
        g.event(BrainMsg.Chunk("Bonjour <pad><pad>"), 10)
        g.event(BrainMsg.Done(DoneReason.GARBAGE, 0, 0, 0.0, true), 20)
        assertEquals(OneShotResult.Failed(BrainError.ENGINE), g.result)
        // As the translator and the summary see it.
        val broken = object : OneShotTransport {
            override suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult = g.result!!
        }
        val e = try { GemmaTranslator(broken, Priority.TRANSCRIPT, OneShotBudget(null, 1_000)).translate("Hello there.", "en", "fr"); null }
            catch (f: TranslateFailure) { f.why }
        assertEquals(TranslateFailure.Why.FAILED, e)
        val s = LocalSummary(broken, OneShotBudget(null, 1_000)).run(listOf("[14:00] User (en): Hello there."), "en", 4096)
        assertTrue(s is LocalSummary.Outcome.Failed)
    }
}
