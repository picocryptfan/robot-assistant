package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translation by Robot's model and its guards (docs/conversation-mode.md 4.1-4.2, phase 5.4): the one-shot it sends,
 * what each way of failing becomes, the interpreter's fallback to the fast engine, the transcript's backlog, and who
 * reads a translation aloud.
 */
class GemmaTranslateTest {
    private val id: LangId by lazy { LangId.load(java.io.File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET).inputStream()) }
    private var now = 0L

    /** :brain as a fake: answers [answer], or ends as [result]; records what was asked. */
    private class FakeBrain(var answer: String = "", var result: OneShotResult? = null) : OneShotTransport {
        val asked = mutableListOf<Pair<BrainMsg.OneShot, OneShotBudget>>()
        override suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult {
            asked += req to budget
            return result ?: OneShotResult.Done(answer, DoneReason.END)
        }
    }

    /** A fast engine as a fake. */
    private class Fast(var answer: String, var fail: Boolean = false) : Translator {
        var calls = 0
        override val engine: MtEngine get() = MtEngine.BERGAMOT
        override suspend fun translate(text: String, from: String, to: String): String {
            calls++
            if (fail) throw IllegalStateException("down")
            return answer
        }
    }

    private val tpu = TranslatePolicy.budget(TranslateUse.INTERPRETER, ModelBackend.TPU, fast = true)

    @Test fun theOneShotCarriesThePlansPromptAndNothingElse() = runBlocking {
        val brain = FakeBrain("Are we picking this up again tomorrow?")
        val g = GemmaTranslator(brain, Priority.INTERPRETER, tpu)
        assertEquals("Are we picking this up again tomorrow?", g.translate("  On se reprend-tu demain?  ", "fr", "en"))
        val (req, budget) = brain.asked.single()
        assertEquals(OneShotKind.TRANSLATE, req.kind)
        assertEquals(Priority.INTERPRETER, req.priority)
        assertEquals(Prompt.TRANSLATE_FR_EN, req.system)
        assertEquals("On se reprend-tu demain?", req.text)
        assertTrue(req.maxChunks in 32..600)
        assertEquals(tpu, budget)
        g.translate("See you at five.", "en", "fr")
        assertEquals(Prompt.TRANSLATE_EN_FR, brain.asked.last().first.system)
        // Nothing to say, nothing asked.
        assertEquals("", g.translate("   ", "fr", "en"))
        assertEquals(2, brain.asked.size)
    }

    @Test fun eachWayOfFailingHasItsReason() = runBlocking {
        val brain = FakeBrain()
        val g = GemmaTranslator(brain, Priority.TRANSCRIPT, tpu)
        fun why(r: OneShotResult): TranslateFailure.Why? {
            brain.result = r
            return try { runBlocking { g.translate("Bonjour tout le monde", "fr", "en") }; null } catch (f: TranslateFailure) { f.why }
        }
        assertEquals(TranslateFailure.Why.NOT_STARTED, why(OneShotResult.NotStarted))
        assertEquals(TranslateFailure.Why.TIMEOUT, why(OneShotResult.TimedOut("Hello")))
        assertEquals(TranslateFailure.Why.CANCELLED, why(OneShotResult.Cancelled))
        assertEquals(TranslateFailure.Why.UNAVAILABLE, why(OneShotResult.Failed(BrainError.NOT_LOADED, unavailable = true)))
        assertEquals(TranslateFailure.Why.TOO_LONG, why(OneShotResult.Failed(BrainError.TOO_LONG)))
        assertEquals(TranslateFailure.Why.FAILED, why(OneShotResult.Failed(BrainError.ENGINE)))
        // A direction Robot doesn't translate is never asked.
        val n = brain.asked.size
        try { g.translate("Hallo", "de", "en") } catch (f: TranslateFailure) { assertEquals(TranslateFailure.Why.DIRECTION, f.why) }
        assertEquals(n, brain.asked.size)
    }

    @Test fun theModelsAnswerPassesTheGuards() = runBlocking {
        val lt = LineTranslator(id) { now }
        val g = GemmaTranslator(FakeBrain("Translation: \"I'm going to be ten minutes late, there's a lot of traffic.\""), Priority.INTERPRETER, tpu)
        val ok = lt.with(g, "Je vais être en retard de dix minutes, il y a beaucoup de trafic.", "fr", "en")
        assertEquals("I'm going to be ten minutes late, there's a lot of traffic.", ok.text)
        assertEquals(MtEngine.GEMMA, ok.engine)
        assertFalse(ok.fast)
        // An answer in the wrong language is not shown.
        val wrong = lt.with(GemmaTranslator(FakeBrain("Je vais être en retard de dix minutes, il y a beaucoup de trafic."), Priority.INTERPRETER, tpu),
            "Je vais être en retard de dix minutes, il y a beaucoup de trafic.", "fr", "en")
        assertNull(wrong.text)
        assertEquals(MtVerdict.LANGUAGE, wrong.verdict)
    }

    @Test fun theInterpreterFallsBackToTheFastEngine() = runBlocking {
        val lt = LineTranslator(id) { now }
        val fast = Fast("I'm going to be late.")
        // The model was busy (the panel's question ahead of it): not started in 1.5 s.
        val busy = GemmaTranslator(FakeBrain(result = OneShotResult.NotStarted), Priority.INTERPRETER, tpu)
        val r = lt.firstThen(busy, fast, "Je vais être en retard.", "fr", "en")
        assertEquals("I'm going to be late.", r.text)
        assertTrue(r.fast && r.fellBack)
        assertEquals(TranslateFailure.Why.NOT_STARTED, r.why)
        // The model's answer is good: the fast engine is never asked.
        fast.calls = 0
        val good = lt.firstThen(GemmaTranslator(FakeBrain("I'll be late."), Priority.INTERPRETER, tpu), fast, "Je vais être en retard.", "fr", "en")
        assertEquals("I'll be late.", good.text)
        assertFalse(good.fellBack)
        assertEquals(0, fast.calls)
        // A guard fails: the fast engine's answer instead.
        val looping = lt.firstThen(GemmaTranslator(FakeBrain("I\nam\ngoing\nto\nbe late"), Priority.INTERPRETER, tpu), fast, "Je vais être en retard.", "fr", "en")
        assertTrue(looping.fast)
        // No fast engine on this phone: the model's failure stands, nothing shown.
        val alone = lt.firstThen(busy, null, "Je vais être en retard.", "fr", "en")
        assertNull(alone.text)
        assertEquals(MtVerdict.TIMEOUT, alone.verdict)
        // Both down.
        val none = lt.firstThen(busy, Fast("", fail = true), "Je vais être en retard.", "fr", "en")
        assertNull(none.text)
        assertEquals(TranslateFailure.Why.UNAVAILABLE, lt.firstThen(null, null, "Oui", "fr", "en").why)
    }

    @Test fun theFastEngineKeepsItsShortLineRules() = runBlocking {
        // "Oui." loses its full stop before Bergamot and gets it back after (MtGuard.prepare / restore).
        val seen = mutableListOf<String>()
        val fast = object : Translator {
            override val engine = MtEngine.BERGAMOT
            override suspend fun translate(text: String, from: String, to: String): String { seen += text; return "Yes" }
        }
        val r = LineTranslator(id) { now }.with(fast, "Oui.", "fr", "en")
        assertEquals(listOf("Oui"), seen)
        assertEquals("Yes.", r.text)
    }

    @Test fun whoLeadsAndHowLongItMayTake() {
        // The TPU's model leads the interpreter; with the fast engine there, a model on the CPU doesn't.
        assertTrue(TranslatePolicy.modelLeads(ModelBackend.TPU, fast = true))
        assertFalse(TranslatePolicy.modelLeads(ModelBackend.CPU, fast = true))
        assertTrue(TranslatePolicy.modelLeads(ModelBackend.CPU, fast = false))
        // The interpreter's order: the second stands in when the first fails; a model resting at SEVERE heat is null.
        assertEquals("model" to "fast", TranslatePolicy.order(ModelBackend.TPU, "model", "fast"))
        assertEquals("fast" to "model", TranslatePolicy.order(ModelBackend.CPU, "model", "fast"))
        assertEquals("model" to null, TranslatePolicy.order(ModelBackend.CPU, "model", null))
        assertEquals(null to "fast", TranslatePolicy.order(ModelBackend.TPU, null, "fast"))
        assertEquals(TranslatePolicy.START_WITHIN_MS, tpu.startWithinMs)
        assertFalse(tpu.waitForLoad)
        // Without the fast engine there is nothing to fall back on: longer, and it waits for the model to load.
        val alone = TranslatePolicy.budget(TranslateUse.INTERPRETER, ModelBackend.TPU, fast = false)
        assertTrue(alone.startWithinMs!! > TranslatePolicy.START_WITHIN_MS && alone.waitForLoad)
        assertTrue(TranslatePolicy.budget(TranslateUse.INTERPRETER, ModelBackend.CPU, fast = false).totalMs > alone.totalMs)
        // A better translation waits its turn, at most as long as a line may be behind, and never loads the model for itself.
        val up = TranslatePolicy.budget(TranslateUse.TRANSCRIPT_UPGRADE, ModelBackend.TPU, fast = true)
        assertEquals(60_000L, up.startWithinMs)
        assertFalse(up.waitForLoad)
        assertTrue(TranslatePolicy.modelWork(screenOn = true, hot = false))
        assertFalse(TranslatePolicy.modelWork(screenOn = false, hot = false))
        assertFalse(TranslatePolicy.modelWork(screenOn = true, hot = true))
    }

    @Test fun theBacklogTakesTheNewestVisibleLineAndSkipsStaleUpgrades() {
        val b = TranslationBacklog()
        fun item(i: Int, t1: Double, first: Boolean = false, asked: Boolean = false) = TranslationBacklog.Item(i, t1, "ligne $i", "fr", "en", first, asked)
        b.add(item(1, 10.0))
        b.add(item(2, 20.0))
        b.add(item(3, 30.0))
        b.add(item(4, 40.0))
        // Line 2 and 3 on screen: the newest of them first.
        assertEquals(3, b.next(45.0, visible = setOf(2, 3))?.i)
        assertEquals(4, b.next(45.0, visible = setOf(9))?.i)
        // At 75 s, line 1 (ended at 10 s) is more than 60 s behind: dropped.
        assertEquals(2, b.next(75.0)?.i)
        assertNull(b.next(75.0))
        // A tapped line goes first and is never too old; a first translation is never skipped for age.
        b.add(item(5, 100.0))
        b.add(item(6, 5.0, asked = true))
        b.add(item(7, 6.0, first = true))
        assertEquals(6, b.next(300.0)?.i)
        assertEquals(7, b.next(300.0)?.i)
        assertNull(b.next(300.0))
        // Only the newest first translations wait.
        for (k in 0 until TranslatePolicy.MAX_WAITING + 5) b.add(item(100 + k, k.toDouble(), first = true))
        assertEquals(TranslatePolicy.MAX_WAITING, b.size)
        assertFalse(b.has(100))
        assertTrue(b.has(100 + TranslatePolicy.MAX_WAITING + 4))
    }

    @Test fun whoReadsATranslationAloud() {
        assertEquals(30 to "fr", InterpreterVoice.forLang(VoiceChoice.HEART, "fr"))
        assertNull(InterpreterVoice.forLang(VoiceChoice.HEART, "en"))
        assertEquals(VoiceChoice.HEART.speaker to null, InterpreterVoice.forLang(VoiceChoice.SIWIS, "en"))
        assertEquals(30 to "fr", InterpreterVoice.forLang(VoiceChoice.SIWIS, "fr"))
        // Kitten and the phone's voice have no French voice: Robot's own reads it.
        assertNull(InterpreterVoice.forLang(VoiceChoice.KITTEN, "fr"))
        assertNull(InterpreterVoice.forLang(VoiceChoice.SYSTEM, "fr"))
    }
}
