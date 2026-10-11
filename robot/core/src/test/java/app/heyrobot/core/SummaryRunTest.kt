package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Robot's own summary run step by step (docs/conversation-mode.md 7.1, phase 5.6): every call within the model's window
 * at SUMMARY priority, the notes merged, a stopped run going on from its notes, the number check and the [time] links,
 * and summaries on storage.
 */
class SummaryRunTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneOffset.UTC
    private val start = ZonedDateTime.of(2026, 10, 11, 14, 0, 0, 0, zone).toInstant().toEpochMilli()

    /** Robot's model as a fake: a note per part, a summary per merge; [failAt] fails that request. */
    private class FakeModel(val failAt: Int = -1, val maxTokens: Int = 4096) : OneShotTransport {
        val asked = mutableListOf<BrainMsg.OneShot>()
        override suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult {
            asked += req
            if (asked.size - 1 == failAt) return OneShotResult.Failed(BrainError.ENGINE)
            // Every call fits the loaded model's window, with its reply's room, and goes at SUMMARY priority.
            val call = SummaryCall(req.system, req.text, req.maxChunks)
            check(SummaryPlanner.fits(call, maxTokens)) { "too long for $maxTokens" }
            check(req.priority == Priority.SUMMARY)
            return OneShotResult.Done(if (req.kind == OneShotKind.SUMMARY_MAP) "- [14:00] Marie will send the file by Friday."
                else "Overview: Marie and the user planned the week. To do: Marie sends the file by Friday [14:00].", DoneReason.END)
        }
    }

    private fun lines(minutes: Int): List<String> = (0 until minutes * 12).map { k ->
        SummaryPlanner.line("%02d:%02d".format(14 + k / 720, (k / 12) % 60), if (k % 2 == 0) "Marie" else "User", if (k % 3 == 0) "fr" else "en",
            if (k % 3 == 0) "On se reprend demain pour le budget?" else "Can you call the garage tomorrow and ask about the brakes?")
    }

    private val budget = SummaryBudget.of(ModelBackend.TPU)

    @Test fun anHourIsMappedThenMerged() = runBlocking {
        val model = FakeModel()
        val progress = mutableListOf<LocalSummary.Progress>()
        val notes = mutableMapOf<Int, String>()
        val r = LocalSummary(model, budget).run(lines(60), "en", 4096, onProgress = { progress += it }, onNote = { k, n -> notes[k] = n })
        r as LocalSummary.Outcome.Done
        val plan = SummaryPlanner.plan(lines(60), "en", 4096)
        assertEquals(plan.parts, r.parts)
        assertTrue(r.parts.toString(), r.parts in 2..SummaryPlanner.FAN_IN)
        assertEquals(plan.parts, model.asked.count { it.kind == OneShotKind.SUMMARY_MAP })
        assertEquals(1, model.asked.count { it.kind == OneShotKind.SUMMARY_REDUCE })
        assertEquals(plan.parts, notes.size)
        assertTrue(r.text.startsWith("Overview:"))
        // The progress bar moves forward to the end.
        assertEquals(progress, progress.sortedBy { it.step })
        assertEquals(progress.last().steps, progress.last().step)
        // Every call has a fixed prompt: the map's and the merge's, nothing else.
        assertEquals(setOf(Prompt.SUMMARY_MAP, Prompt.SUMMARY_REDUCE), model.asked.map { it.system }.toSet())
    }

    @Test fun threeHoursMergeInLevelsAndAShortContextGetsMoreParts() = runBlocking {
        val model = FakeModel()
        val r = LocalSummary(model, budget).run(lines(180), "fr", 4096) as LocalSummary.Outcome.Done
        assertTrue(r.parts > SummaryPlanner.FAN_IN)
        assertTrue(model.asked.count { it.kind == OneShotKind.SUMMARY_REDUCE } >= 3)
        assertEquals(Prompt.SUMMARY_MAP_FR, model.asked.first().system)
        // 0.4.3's short-context model: smaller parts, every call within its 2,048 tokens.
        val short = FakeModel(maxTokens = 2048)
        val s = LocalSummary(short, budget).run(lines(30), "en", 2048) as LocalSummary.Outcome.Done
        val full = SummaryPlanner.plan(lines(30), "en", 4096)
        assertTrue(s.parts > full.parts)
    }

    @Test fun aStoppedSummaryGoesOnFromItsNotes() = runBlocking {
        val first = FakeModel(failAt = 2)
        val kept = mutableMapOf<Int, String>()
        val r = LocalSummary(first, budget).run(lines(60), "en", 4096, onNote = { k, n -> kept[k] = n })
        assertEquals(LocalSummary.Outcome.Failed("Robot's model ran into a problem.", 2), r)
        assertEquals(setOf(0, 1), kept.keys)
        // Again, with the notes kept: parts 0 and 1 aren't asked again.
        val again = FakeModel()
        val parts = SummaryPlanner.plan(lines(60), "en", 4096).parts
        val cached = (0 until parts).map { kept[it] }
        val done = LocalSummary(again, budget).run(lines(60), "en", 4096, cached) as LocalSummary.Outcome.Done
        assertEquals(parts - 2, again.asked.count { it.kind == OneShotKind.SUMMARY_MAP })
        assertTrue(done.text.isNotEmpty())
        // The draft's key changes with the lines, the language or the context.
        val k = SummaryDraft.key(lines(60), "en", 4096)
        assertEquals(k, SummaryDraft.key(lines(60), "en", 4096))
        assertTrue(k != SummaryDraft.key(lines(61), "en", 4096) && k != SummaryDraft.key(lines(60), "fr", 4096) && k != SummaryDraft.key(lines(60), "en", 2048))
        val d = SummaryDraft(k, cached)
        assertEquals(d, SummaryDraft.fromJson(d.toJson()))
    }

    @Test fun nothingToSummarize() = runBlocking {
        val r = LocalSummary(FakeModel(), budget).run(emptyList(), "en", 4096)
        assertTrue(r is LocalSummary.Outcome.Failed)
    }

    private fun transcript(): Pair<TranscriptMeta, Transcript> {
        val f = TranscriptFold()
        f.apply(TranscriptEvent.Line(0, 0.0, 4.0, "S2", "fr", "On se voit vendredi à 18 h 30 chez Julien?"))
        f.apply(TranscriptEvent.Line(1, 125.0, 129.0, "Me", "en", "Sure, I'll bring three bottles."))
        f.apply(TranscriptEvent.Translation(0, "en", TranscriptEvent.BY_GEMMA, "See you Friday at 6:30 at Julien's?"))
        f.apply(TranscriptEvent.Rename("S2", "Marie"))
        return TranscriptMeta("20261011-1400-0002", "", start, start + 300_000, SessionStatus.ENDED) to f.result()
    }

    @Test fun theNumberCheckAndTheTimeLinks() {
        val (meta, t) = transcript()
        val summary = "Marie and the User meet Friday at 18 h 30 [14:00] at Julien's. The user brings 3 bottles [14:02]. Paul pays 40 $ [14:09]."
        val misses = SummaryCheckFor.misses(summary, meta, t, zone)
        assertEquals(listOf("14:09", "40 $", "Paul"), misses.map { it.text }.sorted())
        val links = SummaryCheckFor.links(summary, meta, t, zone)
        assertEquals(listOf(0, 1), links.map { it.second })
        assertEquals("[14:00]", summary.substring(links[0].first))
    }

    @Test fun summariesAreKeptWithTheTranscript() {
        val s = TranscriptStore(File(tmp.root, "transcripts"))
        val w = TranscriptWriter.start(s, "20261011-1400-0003", start, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        assertEquals(emptyList<Pair<Int, SummaryRecord>>(), s.summaries(w.id))
        val robot = SummaryRecord("robot", BrainModel.FAST.id, SummaryRecord.VIA_PHONE, start, "Overview…", parts = 2)
        assertEquals(1, s.addSummary(w.id, robot))
        val claude = robot.copy(by = "claude-opus-5-5", model = "claude-opus-5-5", via = SummaryRecord.VIA_API, consent = true, sentAt = start + 1)
        assertEquals(2, s.addSummary(w.id, claude))
        assertEquals(listOf(1 to robot, 2 to claude), s.summaries(w.id))
        assertTrue(s.deleteSummary(w.id, 1))
        assertEquals(listOf(2 to claude), s.summaries(w.id))
        assertEquals(3, s.addSummary(w.id, robot))
        // The draft of one under way.
        val d = SummaryDraft("k", listOf("- a", null))
        assertNull(s.saveDraft(w.id, d))
        assertEquals(d, s.draft(w.id))
        s.clearDraft(w.id)
        assertNull(s.draft(w.id))
        // Deleted with the transcript; nothing written for a deleted one.
        assertTrue(s.delete(w.id))
        assertNull(s.addSummary(w.id, robot))
        assertEquals(emptyList<Pair<Int, SummaryRecord>>(), s.summaries(w.id))
    }
}
