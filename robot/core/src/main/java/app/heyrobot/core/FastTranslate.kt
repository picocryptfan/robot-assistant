// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. The fast engine as the voice process offers it to a session's translations (docs/conversation-mode.md 4.1, 4.5,
// 4.6; phase 5.4): Bergamot over the :mt process (BergamotTranslator), only when this build has its library and every
// direction of the pair is downloaded and verified (the mark :mt itself checks before a load). Otherwise there is no
// fast engine (null): Robot's model translates alone, with TranslatePolicy's longer deadlines, and the screens say so.
// An engine that turns out unusable while offered (its files didn't load, its process stopped) rests for a minute, so a
// broken fast engine doesn't keep shortening the model's deadlines (1.5 s to start) for nothing. Its errors reach
// LineTranslator as TranslateFailure, logged as codes like the model's.

/** Whether the fast engine can translate a pair on this phone now. */
enum class FastAvailability {
    /** This build has no librobotmt.so (made without tools/build_robotmt.sh), or the :mt process couldn't load it. */
    NO_ENGINE,
    /** A pair the fast engine has no files for. */
    NO_PAIR,
    /** None of its files are downloaded and checked (Settings › Conversation). */
    NOT_DOWNLOADED,
    /** Some are, not every direction's: a download or its install is under way, or a file changed since it was checked. */
    PARTIAL,
    /** It failed in a way that may last (its files didn't load, its process stopped): Robot's model alone for a while. */
    RESTING,
    READY,
}

/**
 * Decides whether the fast engine is offered for a pair, and makes its translator. [check] looks at this phone (the
 * library and the verified mark); its answer holds for [CHECK_MS], so a line every few seconds costs no file reads, and
 * a download finished or files deleted in Settings show within that. Thread-safe.
 */
class FastEngineGate(private val clock: () -> Long, private val check: (LangPair) -> FastAvailability) {
    private val checked = HashMap<LangPair, Pair<Long, FastAvailability>>()
    private var restUntil = Long.MIN_VALUE
    private var noEngine = false

    @Synchronized
    fun availability(pair: LangPair): FastAvailability {
        if (noEngine) return FastAvailability.NO_ENGINE
        val now = clock()
        if (now < restUntil) return FastAvailability.RESTING
        checked[pair]?.let { (at, a) -> if (now - at in 0 until CHECK_MS) return a }
        return check(pair).also { checked[pair] = now to it }
    }

    fun offers(pair: LangPair): Boolean = availability(pair) == FastAvailability.READY

    /**
     * The fast engine for [pair] at [priority] (interactive: the interpreter's turn; batch: a transcript's line), over
     * [transport] (asked for only when the engine is offered), or null when it isn't.
     */
    fun translator(pair: LangPair, priority: MtPriority, transport: () -> MtTransport): Translator? =
        if (offers(pair)) FastTranslator(BergamotTranslator(transport(), deadline(priority), priority), this) else null

    /** The engine answered [e]: an engine that can't work for now stops being offered; a line's own failure changes nothing. */
    @Synchronized
    fun failed(e: MtError) {
        when (e) {
            MtError.NO_ENGINE -> noEngine = true
            MtError.LOAD_FAILED, MtError.DISCONNECTED -> restUntil = clock() + REST_MS
            // Its files went (deleted in Settings, or changed): the next line looks again.
            MtError.NOT_DOWNLOADED -> checked.clear()
            MtError.BAD_REQUEST, MtError.BUSY, MtError.FAILED, MtError.CANCELLED, MtError.TIMEOUT -> {}
        }
    }

    /** Look again at the next line, and try an engine that rested (a session starts). */
    @Synchronized
    fun recheck() {
        checked.clear()
        restUntil = Long.MIN_VALUE
    }

    companion object {
        /** How long a look at the library and the verified mark holds. */
        const val CHECK_MS = 10_000L
        /** How long an engine that couldn't work rests (Robot's model translates alone meanwhile). */
        const val REST_MS = 60_000L

        /** How long :mt may take to start a line at [priority]. */
        fun deadline(priority: MtPriority): Long =
            if (priority == MtPriority.INTERACTIVE) MtPolicy.LINE_DEADLINE_MS else MtPolicy.BACKLOG_LINE_DEADLINE_MS

        /**
         * What the phone has: [inBuild], librobotmt.so is in this build; [b], the pair's files (null: none);
         * [vouched], the files its verified mark vouches for (core MtMark).
         */
        fun availability(inBuild: Boolean, b: MtBundle?, vouched: Set<String>): FastAvailability = when {
            !inBuild -> FastAvailability.NO_ENGINE
            b == null -> FastAvailability.NO_PAIR
            b.files.none { it.name in vouched } -> FastAvailability.NOT_DOWNLOADED
            b.directions.any { d -> !vouched.containsAll(b.filesOf(d).map { it.name }) } -> FastAvailability.PARTIAL
            else -> FastAvailability.READY
        }
    }
}

/** [inner]'s errors as TranslateFailure (logged as codes, like the model's), each told to [gate] first. */
class FastTranslator(private val inner: BergamotTranslator, private val gate: FastEngineGate) : Translator {
    override val engine: MtEngine get() = inner.engine

    override suspend fun translate(text: String, from: String, to: String): String = try {
        inner.translate(text, from, to)
    } catch (e: MtException) {
        gate.failed(e.error)
        throw TranslateFailure(why(e.error))
    }

    companion object {
        fun why(e: MtError): TranslateFailure.Why = when (e) {
            MtError.TIMEOUT -> TranslateFailure.Why.TIMEOUT
            // Too many lines waiting in :mt: this one never started.
            MtError.BUSY -> TranslateFailure.Why.NOT_STARTED
            MtError.CANCELLED -> TranslateFailure.Why.CANCELLED
            MtError.NO_ENGINE, MtError.NOT_DOWNLOADED, MtError.LOAD_FAILED, MtError.DISCONNECTED -> TranslateFailure.Why.UNAVAILABLE
            MtError.BAD_REQUEST, MtError.FAILED -> TranslateFailure.Why.FAILED
        }
    }
}
