package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * The fast engine as the voice process offers it (docs/conversation-mode.md 4.1, 4.5, 4.6; phase 5.4): only with the
 * library in the build and every direction downloaded and verified; resting after a failure that may last; and, with it
 * and without it, who translates the interpreter's turns and a transcript's lines, with which deadlines.
 */
class FastTranslateTest {
    @get:Rule val tmp = TemporaryFolder()

    private val b = MtBundle.FR_EN
    private val pair = LangPair.FR_EN
    private val id: LangId by lazy { LangId.load(File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET).inputStream()) }
    private var now = 0L

    /** :mt as a fake: answers each line with [answer], or throws [error]; records what was asked. */
    private class FakeMt(var answer: (String) -> String? = { "Hello" }, var error: MtError? = null) : MtTransport {
        val asked = mutableListOf<MtMsg.Translate>()
        override suspend fun translate(direction: String, lines: List<String>, deadlineMs: Long, priority: MtPriority): MtMsg.Translated {
            asked += MtMsg.Translate(direction, lines, deadlineMs, priority)
            error?.let { throw MtException(it) }
            val out = lines.map(answer)
            return MtMsg.Translated(out, lines.map { 20L }, out.count { it == null }, 0, MtMemory.UNKNOWN)
        }
    }

    /** :brain as a fake: answers [answer], or ends as [result]; records each request's budget. */
    private class FakeBrain(var answer: String = "", var result: OneShotResult? = null) : OneShotTransport {
        val budgets = mutableListOf<OneShotBudget>()
        override suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult {
            budgets += budget
            return result ?: OneShotResult.Done(answer, DoneReason.END)
        }
    }

    private fun names(vararg dirs: MtDirection) = dirs.flatMap { d -> b.filesOf(d).map { it.name } }.toSet()

    @Test fun offeredOnlyWithTheLibraryAndEveryDirectionVerified() {
        val fr = b.direction("fr", "en")!!
        val en = b.direction("en", "fr")!!
        val all = b.files.map { it.name }.toSet()
        assertEquals(FastAvailability.READY, FastEngineGate.availability(true, b, all))
        // A build made without tools/build_robotmt.sh: never, whatever is downloaded.
        assertEquals(FastAvailability.NO_ENGINE, FastEngineGate.availability(false, b, all))
        assertEquals(FastAvailability.NOT_DOWNLOADED, FastEngineGate.availability(true, b, emptySet()))
        // One direction installed (the install goes file by file), or one file of the other: not yet.
        assertEquals(FastAvailability.PARTIAL, FastEngineGate.availability(true, b, names(fr)))
        assertEquals(FastAvailability.PARTIAL, FastEngineGate.availability(true, b, all - "lex.50.50.enfr.s2t.bin"))
        assertEquals(FastAvailability.READY, FastEngineGate.availability(true, b, names(fr, en)))
        assertEquals(FastAvailability.NO_PAIR, FastEngineGate.availability(true, null, emptySet()))
    }

    /** The files as the main app installs them (sparse here: only their sizes and times count) and the mark it writes. */
    private fun install(dir: File, files: List<MtFile>, markAll: Boolean = false) {
        dir.mkdirs()
        val marks = (if (markAll) b.files else files).map { f ->
            val file = File(dir, f.name)
            if (f in files) RandomAccessFile(file, "rw").use { it.setLength(f.bytes) }
            InstalledFile(f.name, f.bytes, file.lastModified(), f.sha256)
        }
        File(dir, VoiceMark.FILE).writeText(VoiceMark.encode(marks))
    }

    @Test fun theVerifiedMarkOnDiskDecides() {
        val dir = MtBundle.dir(tmp.root, b)
        fun state() = FastEngineGate.availability(true, b, MtMark.vouched(b, dir))
        assertEquals(FastAvailability.NOT_DOWNLOADED, state())
        val frFiles = b.filesOf(b.direction("fr", "en")!!)
        install(dir, frFiles)
        assertEquals(FastAvailability.PARTIAL, state())
        // A mark that lists a file not on disk vouches for nothing of it.
        install(dir, frFiles, markAll = true)
        assertEquals(FastAvailability.PARTIAL, state())
        install(dir, b.files)
        assertEquals(FastAvailability.READY, state())
        // A file changed since it was checked (another time on disk) is no longer vouched for.
        assertTrue(File(dir, "vocab.fren.spm").setLastModified(File(dir, "vocab.fren.spm").lastModified() - 10_000))
        assertEquals(FastAvailability.PARTIAL, state())
        // Deleted in Settings.
        dir.deleteRecursively()
        assertEquals(FastAvailability.NOT_DOWNLOADED, state())
    }

    @Test fun theGateLooksAtThePhoneAtMostEveryTenSeconds() {
        var phone = FastAvailability.NOT_DOWNLOADED
        var looks = 0
        val gate = FastEngineGate({ now }) { looks++; phone }
        var links = 0
        fun translator() = gate.translator(pair, MtPriority.INTERACTIVE) { links++; FakeMt() }
        assertNull(translator())
        // Nothing is bound for an engine that isn't offered.
        assertEquals(0, links)
        // The download finishes: seen within CHECK_MS, without a look at every line meanwhile.
        phone = FastAvailability.READY
        now += 3_000
        assertNull(translator())
        assertEquals(1, looks)
        now += FastEngineGate.CHECK_MS
        assertNotNull(translator())
        assertEquals(2, looks)
        assertEquals(1, links)
        // A session starts: it looks again at once.
        phone = FastAvailability.PARTIAL
        gate.recheck()
        assertNull(translator())
        assertEquals(3, looks)
    }

    @Test fun anEngineThatCantWorkRestsAndALinesFailureDoesNot() = runBlocking {
        val gate = FastEngineGate({ now }) { FastAvailability.READY }
        val mt = FakeMt(error = MtError.DISCONNECTED)
        val lt = LineTranslator(id) { now }
        // :mt died (an abort in slimt, or Android): the line has no fast translation, and the engine rests.
        val down = lt.with(gate.translator(pair, MtPriority.INTERACTIVE) { mt }!!, "Je vais être en retard de dix minutes.", "fr", "en")
        assertNull(down.text)
        assertEquals(TranslateFailure.Why.UNAVAILABLE, down.why)
        assertEquals(FastAvailability.RESTING, gate.availability(pair))
        assertNull(gate.translator(pair, MtPriority.INTERACTIVE) { mt })
        now += FastEngineGate.REST_MS
        assertEquals(FastAvailability.READY, gate.availability(pair))
        // The same for files that didn't load; a new session tries again at once.
        mt.error = MtError.LOAD_FAILED
        assertNull(lt.with(gate.translator(pair, MtPriority.BATCH) { mt }!!, "Bonjour tout le monde", "fr", "en").text)
        assertEquals(FastAvailability.RESTING, gate.availability(pair))
        gate.recheck()
        assertEquals(FastAvailability.READY, gate.availability(pair))
        // One line too slow, or :mt too busy: only that line goes without (TIMEOUT, as for the model).
        mt.error = MtError.TIMEOUT
        val slow = lt.with(gate.translator(pair, MtPriority.INTERACTIVE) { mt }!!, "Bonjour tout le monde", "fr", "en")
        assertEquals(MtVerdict.TIMEOUT, slow.verdict)
        assertEquals(TranslateFailure.Why.TIMEOUT, slow.why)
        mt.error = MtError.BUSY
        assertEquals(TranslateFailure.Why.NOT_STARTED, lt.with(gate.translator(pair, MtPriority.INTERACTIVE) { mt }!!, "Bonjour tout le monde", "fr", "en").why)
        assertEquals(FastAvailability.READY, gate.availability(pair))
        // A library that didn't load in :mt stays off for this process.
        mt.error = MtError.NO_ENGINE
        lt.with(gate.translator(pair, MtPriority.INTERACTIVE) { mt }!!, "Bonjour tout le monde", "fr", "en")
        gate.recheck()
        now += FastEngineGate.REST_MS
        assertEquals(FastAvailability.NO_ENGINE, gate.availability(pair))
    }

    @Test fun filesDeletedWhileOfferedAreLookedForAgainAtTheNextLine() = runBlocking {
        var phone = FastAvailability.READY
        var looks = 0
        val gate = FastEngineGate({ now }) { looks++; phone }
        val mt = FakeMt(error = MtError.NOT_DOWNLOADED)
        val t = gate.translator(pair, MtPriority.BATCH) { mt }!!
        phone = FastAvailability.NOT_DOWNLOADED
        try {
            t.translate("Bonjour tout le monde", "fr", "en")
            fail()
        } catch (f: TranslateFailure) {
            assertEquals(TranslateFailure.Why.UNAVAILABLE, f.why)
        }
        assertNull(gate.translator(pair, MtPriority.BATCH) { mt })
        assertEquals(2, looks)
    }

    @Test fun theInterpretersTurnWaitsLeastATranscriptsLineGivesWay() = runBlocking {
        val gate = FastEngineGate({ now }) { FastAvailability.READY }
        val mt = FakeMt(answer = { if (it == "Oui") "Yes" else "I'm going to be late." })
        assertEquals("I'm going to be late.", gate.translator(pair, MtPriority.INTERACTIVE) { mt }!!.translate("Je vais être en retard.", "fr", "en"))
        assertEquals(MtMsg.Translate("fr-en", listOf("Je vais être en retard."), MtPolicy.LINE_DEADLINE_MS, MtPriority.INTERACTIVE), mt.asked.last())
        // A transcript line: batch, so an interpreter's line goes first in :mt; a longer deadline before the model steps in.
        val r = LineTranslator(id) { now }.with(gate.translator(pair, MtPriority.BATCH) { mt }!!, "Oui.", "fr", "en")
        assertEquals("Yes.", r.text)
        assertTrue(r.fast)
        assertEquals(MtMsg.Translate("fr-en", listOf("Oui"), MtPolicy.BACKLOG_LINE_DEADLINE_MS, MtPriority.BATCH), mt.asked.last())
        assertTrue(MtPolicy.BACKLOG_LINE_DEADLINE_MS in (MtPolicy.LINE_DEADLINE_MS + 1)..MtPolicy.MAX_DEADLINE_MS)
    }

    /** The interpreter's turn as ConversationTranslations makes it: the gate's engine, the policy's order and deadlines. */
    private suspend fun turn(gate: FastEngineGate, mt: MtTransport, brain: FakeBrain, backend: ModelBackend): Translated {
        val fast = gate.translator(pair, MtPriority.INTERACTIVE) { mt }
        val model = GemmaTranslator(brain, Priority.INTERPRETER, TranslatePolicy.budget(TranslateUse.INTERPRETER, backend, fast != null))
        val (lead, other) = TranslatePolicy.order(backend, model, fast)
        return LineTranslator(id) { now }.firstThen(lead, other, "Je vais être en retard de dix minutes.", "fr", "en")
    }

    @Test fun theInterpreterWithTheFastEngineOnTheTpu() = runBlocking {
        val gate = FastEngineGate({ now }) { FastAvailability.READY }
        val mt = FakeMt(answer = { "I'll be ten minutes late." })
        // The model leads, and must start within 1.5 s.
        val brain = FakeBrain("I'm going to be ten minutes late.")
        val r = turn(gate, mt, brain, ModelBackend.TPU)
        assertEquals(MtEngine.GEMMA, r.engine)
        assertEquals(OneShotBudget(TranslatePolicy.START_WITHIN_MS, 8_000, waitForLoad = false), brain.budgets.single())
        assertTrue(mt.asked.isEmpty())
        // The panel's question is ahead of it: the fast engine stands in, marked fast.
        brain.result = OneShotResult.NotStarted
        val stood = turn(gate, mt, brain, ModelBackend.TPU)
        assertEquals("I'll be ten minutes late.", stood.text)
        assertTrue(stood.fast && stood.fellBack)
        assertEquals(TranslateFailure.Why.NOT_STARTED, stood.why)
    }

    @Test fun theFastEngineLeadsWhenTheModelRunsOnTheCpu() = runBlocking {
        val gate = FastEngineGate({ now }) { FastAvailability.READY }
        val mt = FakeMt(answer = { "I'll be ten minutes late." })
        val brain = FakeBrain("I'm going to be ten minutes late.")
        val r = turn(gate, mt, brain, ModelBackend.CPU)
        assertTrue(r.fast)
        assertFalse(r.fellBack)
        assertTrue(brain.budgets.isEmpty())
        // The fast engine is down: the CPU model stands in this once, then translates alone with its own longer deadlines.
        mt.error = MtError.DISCONNECTED
        val stood = turn(gate, mt, brain, ModelBackend.CPU)
        assertEquals(MtEngine.GEMMA, stood.engine)
        assertTrue(stood.fellBack)
        assertEquals(OneShotBudget(TranslatePolicy.START_WITHIN_MS, 20_000, waitForLoad = false), brain.budgets.last())
        val alone = turn(gate, mt, brain, ModelBackend.CPU)
        assertEquals(MtEngine.GEMMA, alone.engine)
        assertFalse(alone.fellBack)
        assertEquals(OneShotBudget(10_000, 30_000), brain.budgets.last())
    }

    @Test fun withoutItsFilesRobotsModelTranslatesAloneWithLongerDeadlines() = runBlocking {
        val gate = FastEngineGate({ now }) { FastAvailability.NOT_DOWNLOADED }
        val mt = FakeMt()
        val brain = FakeBrain("I'm going to be ten minutes late.")
        val r = turn(gate, mt, brain, ModelBackend.TPU)
        assertEquals(MtEngine.GEMMA, r.engine)
        assertEquals(OneShotBudget(8_000, 15_000), brain.budgets.single())
        assertTrue(mt.asked.isEmpty())
        // The model down too: nothing to show, and nothing was sent to :mt.
        brain.result = OneShotResult.Failed(BrainError.NOT_LOADED, unavailable = true)
        assertNull(turn(gate, mt, brain, ModelBackend.TPU).text)
        assertTrue(mt.asked.isEmpty())
        // A transcript's first translation then waits for the model (no fast draft to show meanwhile).
        assertEquals(TranslatePolicy.FIRST_WAIT_MS, TranslatePolicy.budget(TranslateUse.TRANSCRIPT_FIRST, ModelBackend.TPU, fast = false).startWithinMs)
    }

    @Test fun aTranscriptLineGetsTheFastDraftThenTheModelsBetterOne() = runBlocking {
        val gate = FastEngineGate({ now }) { FastAvailability.READY }
        val lt = LineTranslator(id) { now }
        // Bergamot's draft at once (its meaning error, "five years old", is why the model's comes after).
        val first = lt.with(gate.translator(pair, MtPriority.BATCH) { FakeMt(answer = { "Can you pick me up at daycare at five years old?" }) }!!,
            "Peux-tu venir me chercher à la garderie à cinq heures?", "fr", "en")
        assertTrue(first.fast)
        assertNotNull(first.text)
        // The better one, in the background: it never loads the model for itself and is stale after 60 s.
        val budget = TranslatePolicy.budget(TranslateUse.TRANSCRIPT_UPGRADE, ModelBackend.TPU, fast = true)
        val better = lt.with(GemmaTranslator(FakeBrain("Can you pick me up at the daycare at five?"), Priority.TRANSCRIPT, budget),
            "Peux-tu venir me chercher à la garderie à cinq heures?", "fr", "en")
        assertEquals("Can you pick me up at the daycare at five?", better.text)
        assertFalse(better.fast)
        assertFalse(budget.waitForLoad)
        // With the fast draft there, the model's first translation (when the draft failed a guard) must start within 1.5 s.
        assertEquals(TranslatePolicy.START_WITHIN_MS, TranslatePolicy.budget(TranslateUse.TRANSCRIPT_FIRST, ModelBackend.TPU, fast = true).startWithinMs)
    }
}
