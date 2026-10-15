// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.content.Context
import android.os.SystemClock
import app.heyrobot.assistant.BrainKeeper
import app.heyrobot.assistant.BrainOneShots
import app.heyrobot.core.GemmaTranslator
import app.heyrobot.core.LangPair
import app.heyrobot.core.LineTranslator
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.MtEngine
import app.heyrobot.core.MtPriority
import app.heyrobot.core.RLog
import app.heyrobot.core.TranscriptEvent
import app.heyrobot.core.TranscriptLine
import app.heyrobot.core.TranslatePolicy
import app.heyrobot.core.TranslateUse
import app.heyrobot.core.Translated
import app.heyrobot.core.TranslationBacklog
import app.heyrobot.core.Translator
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.voice.ConversationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// VOICE PROCESS. One session's translations (docs/conversation-mode.md 4.1, 5.2, 5.3; phase 5.4). The interpreter: each
// turn in order, Robot's model first (on the TPU) with the fast engine standing in when the model hasn't started within
// 1.5 s, fails or fails a guard; with a model on the CPU, the fast engine first and the model standing in (core
// LineTranslator, TranslatePolicy). A transcript with Translate on: the fast engine at once (a first draft, marked
// "fast"), then Robot's model replaces it with a better one in the background, newest on screen first, one at a time,
// none more than 60 s behind, only with the screen on and the phone not too hot; a line you tap goes first. Without the
// fast engine (not in the build, its files not downloaded and verified, or resting after a failure: FastTranslation)
// Robot's model makes the first translations the same way. Every translation is checked by MtGuard and saved through
// the session's writer (the session's control thread); the voice log gets the engine, the time and the guard's verdict,
// never a word.

class ConversationTranslations(private val app: Context, private val pair: LangPair, private val out: Out) {
    interface Out {
        /** Line [i]'s translation into [to] by [by] (TranscriptEvent.BY_GEMMA or BY_BERGAMOT): save it and show it. */
        fun translated(i: Int, to: String, by: String, text: String)

        /** The interpreter's turn [l] from [from] to [to]: [r] (its text null when no engine gave one). */
        fun interpreted(l: TranscriptLine, from: String, to: String, r: Translated)

        /** Line [i] is being translated ([on]) or no longer. */
        fun working(i: Int, on: Boolean)

        /** Seconds into the session now (the lines' clock). */
        fun nowS(): Double

        /** The lines on screen now (null: count them all). */
        fun visible(): Set<Int>?
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lineTranslator by lazy { LineTranslator(ConversationEngine.langId(app)) { SystemClock.elapsedRealtime() } }
    private val turns = Channel<TranscriptLine>(Channel.UNLIMITED)
    private val fastLines = Channel<TranscriptLine>(Channel.UNLIMITED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private val backlog = TranslationBacklog()

    /** The transcript's Translate toggle. */
    @Volatile var transcript = false
        private set
    @Volatile var screenOn = true
    /** SEVERE heat: the fast engine only (docs/conversation-mode.md 1.4). */
    @Volatile var hot = false
    @Volatile private var closed = false

    init {
        scope.launch { for (l in turns) interpret(l) }
        scope.launch { for (l in fastLines) fastFirst(l) }
        scope.launch {
            for (u in wake) drain()
        }
    }

    /** A line Robot can translate: words, in one of the pair's languages. */
    private fun translatable(l: TranscriptLine): Boolean = l.text.isNotBlank() && pair.has(l.lang)

    /** Where Robot's model runs, or (not loaded) would run: this phone's model and place (0.4.7: Fast on a Pixel 9a's GPU or CPU). */
    private fun backend(): ModelBackend = BrainKeeper.state.value.backend ?: BrainKeeper.backendHere(app)

    /** The fast engine now, for the interpreter's turns ([MtPriority.INTERACTIVE]) or a transcript's lines ([MtPriority.BATCH]). */
    private fun fast(priority: MtPriority = MtPriority.INTERACTIVE): Translator? = FastTranslation.translator(app, pair, priority)

    private fun model(use: TranslateUse, fast: Boolean): GemmaTranslator = GemmaTranslator(BrainOneShots, use.priority, TranslatePolicy.budget(use, backend(), fast))

    // ------------------------------------------------------------------ the interpreter

    /** The interpreter heard turn [l]: translated in order, after the turns before it. */
    fun interpreterTurn(l: TranscriptLine) {
        if (closed || !translatable(l)) return
        out.working(l.i, true)
        turns.trySend(l)
    }

    private suspend fun interpret(l: TranscriptLine) {
        val from = l.lang ?: return
        val to = pair.other(from) ?: return
        try {
            val fast = fast()
            // Too hot: the fast engine only. Otherwise the model leads unless it runs on the CPU and the fast engine is there.
            val model = if (hot) null else model(TranslateUse.INTERPRETER, fast != null)
            val (lead, other) = TranslatePolicy.order(backend(), model, fast)
            val r = lineTranslator.firstThen(lead, other, l.text, from, to)
            log(r)
            r.text?.let { out.translated(l.i, to, by(r.engine), it) }
            out.interpreted(l, from, to, r)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            RLog.w("conv_interpret", t)
        } finally {
            out.working(l.i, false)
        }
    }

    // ------------------------------------------------------------------ transcript lines

    /** Translate on or off for the transcript; on also translates the [recent] lines that have none yet. */
    fun setTranscript(on: Boolean, recent: List<TranscriptLine> = emptyList()) {
        transcript = on
        if (!on) {
            synchronized(lock) { backlog.clear() }
            return
        }
        recent.filter { it.translations.isEmpty() }.forEach(::transcriptLine)
    }

    /** A transcript line was heard (or its language changed): the fast engine first, then the model. */
    fun transcriptLine(l: TranscriptLine) {
        if (closed || !transcript || !translatable(l)) return
        if (fast(MtPriority.BATCH) != null) fastLines.trySend(l) else queue(l, first = true)
    }

    /** You tapped "Better translation" under line [l]: the model's, first in line. */
    fun better(l: TranscriptLine) {
        if (closed || !translatable(l)) return
        queue(l, first = l.translations.isEmpty(), asked = true)
    }

    private suspend fun fastFirst(l: TranscriptLine) {
        val from = l.lang ?: return
        val to = pair.other(from) ?: return
        if (!transcript) return
        val f = fast(MtPriority.BATCH) ?: return queue(l, first = true)
        val r = lineTranslator.with(f, l.text, from, to)
        log(r)
        r.text?.let { out.translated(l.i, to, TranscriptEvent.BY_BERGAMOT, it) }
        // Translate turned off meanwhile: nothing more for Robot's model.
        if (transcript) queue(l, first = r.text == null)
    }

    private fun queue(l: TranscriptLine, first: Boolean, asked: Boolean = false) {
        val from = l.lang ?: return
        val to = pair.other(from) ?: return
        synchronized(lock) { backlog.add(TranslationBacklog.Item(l.i, l.t1, l.text, from, to, first, asked)) }
        wake.trySend(Unit)
    }

    /** The screen came on, the phone cooled down: the model goes on with the lines waiting. */
    fun poke() {
        wake.trySend(Unit)
    }

    /** One at a time ([TranslatePolicy.IN_FLIGHT]), while the screen is on and the phone isn't too hot. */
    private suspend fun drain() {
        while (!closed && TranslatePolicy.modelWork(screenOn, hot)) {
            val item = synchronized(lock) { backlog.next(out.nowS(), out.visible()) } ?: return
            out.working(item.i, true)
            try {
                val use = if (item.first) TranslateUse.TRANSCRIPT_FIRST else TranslateUse.TRANSCRIPT_UPGRADE
                val r = lineTranslator.with(model(use, fast() != null), item.text, item.from, item.to)
                log(r)
                r.text?.let { out.translated(item.i, item.to, TranscriptEvent.BY_GEMMA, it) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                RLog.w("conv_translate", t)
            } finally {
                out.working(item.i, false)
            }
        }
    }

    private fun by(engine: MtEngine?): String = if (engine == MtEngine.BERGAMOT) TranscriptEvent.BY_BERGAMOT else TranscriptEvent.BY_GEMMA

    /** Event 107: the engine, the time and the guard's verdict. */
    private fun log(r: Translated) {
        val e = r.engine ?: return
        VoiceLog.add(VoiceEvent.CONV_TRANSLATE, e, r.ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), r.verdict.id)
    }

    /** The session ended: everything waiting goes, and what runs in :brain is cancelled there. */
    fun close() {
        closed = true
        synchronized(lock) { backlog.clear() }
        turns.close()
        fastLines.close()
        wake.close()
        scope.cancel()
    }
}
