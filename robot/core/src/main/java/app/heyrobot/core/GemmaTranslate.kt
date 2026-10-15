// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.util.Locale

// Pure. Translation by Robot's own model in conversation mode (docs/conversation-mode.md 4.1-4.2, phase 5.4), and the
// rules around both engines: GemmaTranslator (a Translator over :brain's one-shot request, with the plan's Québec French
// and English prompts), TranslatePolicy (which engine leads, and each request's deadlines by where the model runs),
// LineTranslator (an engine's answer checked by MtGuard; the interpreter's model first, the fast engine when the model
// didn't start in 1.5 s, failed or failed a guard), TranslationBacklog (a transcript's better translations: newest
// first, one at a time, none more than 60 s behind) and InterpreterVoice (who reads a translation aloud). The fast
// engine (Bergamot over :mt, offered by FastEngineGate when its files are downloaded and verified) is another
// Translator: everything here works with it or without it.

/** Why a translation didn't come from an engine (logged as a code, never the words). */
class TranslateFailure(val why: Why) : Exception(why.name) {
    enum class Why {
        /** A direction Robot doesn't translate. */
        DIRECTION,
        /** Robot's model hadn't started on it in time (others were ahead): the fast engine instead. */
        NOT_STARTED,
        TIMEOUT,
        /** No model to ask: none loaded (and not waited for), none chosen, or its process down. */
        UNAVAILABLE,
        /** Too long for the model's context. */
        TOO_LONG,
        CANCELLED,
        FAILED,
    }
}

/**
 * Robot's model as a translator (docs/conversation-mode.md 4.2): one one-shot per line at [priority], with the fixed
 * prompt for the direction (Prompt.TRANSLATE_FR_EN / TRANSLATE_EN_FR), no tools, no earlier lines. The caller checks the
 * answer with MtGuard, as for the fast engine.
 */
class GemmaTranslator(private val transport: OneShotTransport, private val priority: Priority, private val budget: OneShotBudget) : Translator {
    override val engine: MtEngine get() = MtEngine.GEMMA

    override suspend fun translate(text: String, from: String, to: String): String {
        val system = Prompt.translate(from, to) ?: throw TranslateFailure(TranslateFailure.Why.DIRECTION)
        val t = text.trim()
        if (t.isEmpty()) return ""
        return when (val r = transport.run(request(t, system), budget)) {
            is OneShotResult.Done -> r.text
            OneShotResult.NotStarted -> throw TranslateFailure(TranslateFailure.Why.NOT_STARTED)
            is OneShotResult.TimedOut -> throw TranslateFailure(TranslateFailure.Why.TIMEOUT)
            OneShotResult.Cancelled -> throw TranslateFailure(TranslateFailure.Why.CANCELLED)
            is OneShotResult.Failed -> throw TranslateFailure(when {
                r.unavailable -> TranslateFailure.Why.UNAVAILABLE
                r.code == BrainError.TOO_LONG -> TranslateFailure.Why.TOO_LONG
                else -> TranslateFailure.Why.FAILED
            })
        }
    }

    fun request(text: String, system: String): BrainMsg.OneShot = BrainMsg.OneShot(OneShotKind.TRANSLATE, priority, system, text, maxChunks(text))

    companion object {
        /** A translation's cap in chunks (about tokens): MtGuard's longest output (2.5 times the input) and a margin. */
        fun maxChunks(text: String): Int = (TokenEstimate.text(text) * 5 / 2 + 24).coerceIn(32, 600)
    }
}

/** Where a translation is for, which sets its priority and deadlines. */
enum class TranslateUse(val priority: Priority) {
    /** The interpreter's turn: someone waits to hear it. */
    INTERPRETER(Priority.INTERPRETER),
    /** A transcript line's first translation (no fast one to show meanwhile). */
    TRANSCRIPT_FIRST(Priority.TRANSCRIPT),
    /** A transcript line's better translation, in the background. */
    TRANSCRIPT_UPGRADE(Priority.TRANSCRIPT),
}

/**
 * Which engine leads and how long Robot's model may take (docs/conversation-mode.md 4.1). The TPU's model answers a line
 * in about a second (EST), so it leads the interpreter, with the fast engine taking over when it hasn't started within
 * [START_WITHIN_MS]. A model on the CPU (0.4.1's E4B, or your own file) writes about 8 tokens a second, so with the fast
 * engine there it leads instead and the model only improves transcript lines; without it, the model is the only engine,
 * with longer deadlines. A short-context model (0.4.3, chat only) translates too: a one-shot is chat only anyway. 0.4.7's
 * GPU (Fast on a Pixel 9a's Mali) counts as the CPU here: about 8 to 14 tokens a second by the estimates, about half the
 * TPU's and no faster than a Tensor G4's CPU with the same file ([slow]).
 */
object TranslatePolicy {
    /** A translation someone waits for that Robot's model hasn't started within this goes to the fast engine instead. */
    const val START_WITHIN_MS = 1_500L
    /** A transcript line's better translation is skipped when the line ended more than this many seconds ago. */
    const val BEHIND_S = 60.0
    /** Better translations in flight at once. */
    const val IN_FLIGHT = 1
    /** First translations waiting (no fast engine) beyond this: the oldest go untranslated (tap a line to ask). */
    const val MAX_WAITING = 30
    /** A first translation (no fast engine) waits this long for Robot's model at most (the panel's question, the interpreter). */
    const val FIRST_WAIT_MS = 120_000L

    /**
     * Robot's model writes a line well under the interpreter's deadlines only on the TPU: on the CPU, and on 0.4.7's GPU
     * (estimated, unmeasured), it gets the longer ones and the fast engine leads when it is there. Unknown (null): as before.
     */
    fun slow(backend: ModelBackend?): Boolean = backend == ModelBackend.CPU || backend == ModelBackend.GPU

    /** Robot's model leads the interpreter: on the TPU, or when there is no fast engine. */
    fun modelLeads(backend: ModelBackend?, fast: Boolean): Boolean = !fast || !slow(backend)

    /**
     * The interpreter's engines in order, for LineTranslator.firstThen: Robot's [model] first and the [fast] engine
     * standing in, unless the model runs on the CPU and the fast engine is there; then the fast engine first, the model
     * only when it fails. Either may be null (no fast engine; the model resting at SEVERE heat).
     */
    fun <T : Any> order(backend: ModelBackend?, model: T?, fast: T?): Pair<T?, T?> =
        if (modelLeads(backend, fast != null)) model to fast else fast to model

    /**
     * The model works on transcript lines in the background (better translations; first ones too without the fast
     * engine) only with the screen on, and not at SEVERE heat ([hot]: the fast engine only). It goes on with the lines
     * on screen when the screen comes back.
     */
    fun modelWork(screenOn: Boolean, hot: Boolean): Boolean = screenOn && !hot

    fun budget(use: TranslateUse, backend: ModelBackend?, fast: Boolean): OneShotBudget {
        val cpu = slow(backend)
        return when (use) {
            TranslateUse.INTERPRETER -> when {
                fast -> OneShotBudget(START_WITHIN_MS, if (cpu) 20_000 else 8_000, waitForLoad = false)
                cpu -> OneShotBudget(10_000, 30_000)
                else -> OneShotBudget(8_000, 15_000)
            }
            TranslateUse.TRANSCRIPT_FIRST -> if (fast) OneShotBudget(START_WITHIN_MS, if (cpu) 30_000 else 15_000, waitForLoad = false)
                else OneShotBudget(FIRST_WAIT_MS, if (cpu) 45_000 else 20_000)
            // Waiting longer than a line may be behind makes it stale anyway.
            TranslateUse.TRANSCRIPT_UPGRADE -> OneShotBudget((BEHIND_S * 1000).toLong(), if (cpu) 60_000 else 20_000, waitForLoad = false)
        }
    }
}

/**
 * A line's translation as shown: [text] (null when no engine gave one that passed the guards), the [engine] that made
 * it (the fast one is marked "fast"), its [verdict] (the last one tried when [text] is null), how long it took, and
 * whether the fast engine stood in for the model ([fellBack], with the model's [why]).
 */
data class Translated(
    val text: String?,
    val engine: MtEngine?,
    val verdict: MtVerdict,
    val ms: Long,
    val fellBack: Boolean = false,
    val why: TranslateFailure.Why? = null,
) {
    val fast: Boolean get() = engine == MtEngine.BERGAMOT
}

/** One engine's answer, checked; and the interpreter's choice between the two. */
class LineTranslator(private val id: LangId, private val clock: () -> Long) {
    /**
     * [t]'s translation of [text] from [from] to [to], checked by MtGuard. A failure is a verdict (TIMEOUT for an engine
     * that didn't answer in time, EMPTY otherwise), never an exception, except cancellation of the caller.
     */
    suspend fun with(t: Translator, text: String, from: String, to: String): Translated {
        val t0 = clock()
        val p = MtGuard.prepare(text, t.engine)
        val raw = try {
            MtGuard.restore(t.translate(p.text, from, to), p)
        } catch (f: TranslateFailure) {
            val v = if (f.why == TranslateFailure.Why.TIMEOUT || f.why == TranslateFailure.Why.NOT_STARTED) MtVerdict.TIMEOUT else MtVerdict.EMPTY
            return Translated(null, t.engine, v, clock() - t0, why = f.why)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (_: Exception) {
            return Translated(null, t.engine, MtVerdict.EMPTY, clock() - t0, why = TranslateFailure.Why.FAILED)
        }
        val r = MtGuard.check(text, raw, to, id)
        return Translated(r.text, t.engine, r.verdict, clock() - t0)
    }

    /**
     * The interpreter (docs/conversation-mode.md 4.1): [lead] first (Robot's model, unless TranslatePolicy says the fast
     * engine leads); the [other] when the first didn't start in time, failed, or failed a guard. Null engines are
     * skipped. The result says whether the second stood in.
     */
    suspend fun firstThen(lead: Translator?, other: Translator?, text: String, from: String, to: String): Translated {
        if (lead == null && other == null) return Translated(null, null, MtVerdict.EMPTY, 0, why = TranslateFailure.Why.UNAVAILABLE)
        val first = lead?.let { with(it, text, from, to) }
        if (first?.text != null) return first
        val second = other?.let { with(it, text, from, to) } ?: return first!!
        return if (second.text != null) second.copy(fellBack = lead != null, why = first?.why) else first ?: second
    }
}

/**
 * A transcript's translations still to make by Robot's model (docs/conversation-mode.md 4.1): the newest lines first
 * (the visible ones before the others), [TranslatePolicy.IN_FLIGHT] at a time. A better translation is skipped when its
 * line ended more than [TranslatePolicy.BEHIND_S] ago; a first translation (no fast engine) waits, but only the newest
 * [TranslatePolicy.MAX_WAITING]. A line you tap ("Better translation") goes first and is never skipped.
 */
class TranslationBacklog {
    data class Item(val i: Int, val t1: Double, val text: String, val from: String, val to: String, val first: Boolean, val asked: Boolean = false)

    private val items = LinkedHashMap<Int, Item>()

    val size: Int get() = items.size

    /** Adds (or replaces) line [it]'s request; the oldest first translations go beyond the cap. */
    fun add(it: Item) {
        items.remove(it.i)
        items[it.i] = it
        val firsts = items.values.filter { x -> x.first && !x.asked }.sortedBy { x -> x.t1 }
        if (firsts.size > TranslatePolicy.MAX_WAITING) firsts.take(firsts.size - TranslatePolicy.MAX_WAITING).forEach { x -> items.remove(x.i) }
    }

    fun remove(i: Int) { items.remove(i) }

    fun clear() = items.clear()

    fun has(i: Int): Boolean = i in items

    /**
     * The next one to make at [nowS] (seconds into the session), taken off the list: a line you asked for, then the
     * newest of [visible] (null: all count as visible), then the newest of the rest. Better translations more than
     * [TranslatePolicy.BEHIND_S] behind are dropped on the way.
     */
    fun next(nowS: Double, visible: Set<Int>? = null): Item? {
        items.values.filter { !it.first && !it.asked && nowS - it.t1 > TranslatePolicy.BEHIND_S }.forEach { items.remove(it.i) }
        val pick = items.values.filter { it.asked }.maxByOrNull { it.t1 }
            ?: items.values.filter { visible == null || it.i in visible }.maxByOrNull { it.t1 }
            ?: items.values.maxByOrNull { it.t1 }
            ?: return null
        items.remove(pick.i)
        return pick
    }
}

/**
 * Who reads a translation aloud in the interpreter (docs/conversation-mode.md 5.2): English in Robot's own voice;
 * French in Kokoro's French voice (Siwis, speaker 30, espeak "fr") when Robot speaks with Kokoro. Null: Robot's own
 * voice as set ([TextToSpeech.speak]); otherwise the speaker id and language for speakOther.
 */
object InterpreterVoice {
    fun forLang(robot: VoiceChoice, lang: String): Pair<Int, String?>? = when {
        lang == "fr" && robot.bundle == VoiceBundle.KOKORO -> VoiceChoice.SIWIS.speaker to "fr"
        // Robot's own voice is the French one: English in Kokoro's default English voice.
        lang == "en" && robot == VoiceChoice.SIWIS -> VoiceChoice.HEART.speaker to null
        else -> null
    }

    /** The language's name as the interpreter's halves say it. */
    fun name(lang: String): String = when (lang) {
        "fr" -> "Français"
        "en" -> "English"
        else -> lang.uppercase(Locale.ROOT)
    }
}
