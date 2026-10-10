package app.heyrobot.voice

// Pure (no Android, no sherpa-onnx): when a spoken question ends, and when to show the words so far. Time is
// counted in samples at 16 kHz, so the logic is the same in tests and on the phone. SherpaSpeechToText feeds it
// the end-of-utterance VAD's speech flag for every 32 ms frame.

/** Where a spoken question ends (docs/voice-plan.md 4.6). */
class Endpointer(private val cfg: Config = Config()) {
    data class Config(
        /** Nothing said this long after listening started: give up ("I didn't hear anything"). */
        val noSpeechSamples: Long = 6L * RATE,
        /** A question is capped here (the VAD's own cap is 20 s of continuous speech). */
        val maxSamples: Long = 30L * RATE,
        /**
         * Wall-clock cap: samples only count while the microphone delivers them, so a capture that stalls without
         * saying so must still end the question (the panel would say "Listening…" for ever).
         */
        val watchdogMs: Long = 35_000L,
    )

    enum class Event {
        NONE,
        /** The VAD says speech started (the first frame of it). */
        SPEECH,
        /** The VAD's speech flag dropped: it waits for its minimum silence (0.5 s) before it drops it. */
        END,
        NO_SPEECH,
        MAX_LENGTH,
    }

    @Volatile var samples = 0L; private set
    /** Sample index where speech was first detected, or -1. */
    @Volatile var speechStart = -1L; private set
    private var speaking = false
    private var done = false

    val heardSpeech: Boolean get() = speechStart >= 0

    /** How long the question may take by the wall clock ([Config.watchdogMs]). */
    val watchdogMs: Long get() = cfg.watchdogMs

    /** One frame of [n] samples with the VAD's flag after it; returns what that means (once [END], [NO_SPEECH] or [MAX_LENGTH], always NONE). */
    @Synchronized
    fun onFrame(speech: Boolean, n: Int): Event {
        if (done) return Event.NONE
        samples += n
        var ev = Event.NONE
        if (speech && speechStart < 0) {
            speechStart = samples - n
            ev = Event.SPEECH
        }
        val ended = speaking && !speech
        speaking = speech
        val out = when {
            ended -> Event.END
            samples >= cfg.maxSamples -> Event.MAX_LENGTH
            speechStart < 0 && samples >= cfg.noSpeechSamples -> Event.NO_SPEECH
            else -> ev
        }
        if (out == Event.END || out == Event.NO_SPEECH || out == Event.MAX_LENGTH) done = true
        return out
    }

    /**
     * The audio stopped coming (the microphone was closed under the question, or the watchdog fired): [Event.END]
     * if speech was heard (decode what there is), [Event.NO_SPEECH] if not, NONE once the question already ended.
     */
    @Synchronized
    fun cut(): Event {
        if (done) return Event.NONE
        done = true
        return if (heardSpeech) Event.END else Event.NO_SPEECH
    }

    companion object {
        const val RATE = 16_000
    }
}

/** Where a question's audio starts when it attaches to the always-on microphone (Listener.attach). */
object PreRoll {
    /** Recent audio the listener keeps: at least as far back as a question after "hey robot" may reach. */
    const val RING_MS = 3_000
    /** After "hey robot", the question starts where the keyword fired, if that was this recent. */
    const val SINCE_WAKE_MS = 2_500
    /** Otherwise a little audio from before the button press. */
    const val OTHERWISE_MS = 300

    /**
     * Does a keyword hit mark where the next question starts? "HEY ROBOT" does. "STOP" (said while Robot speaks)
     * does not: a mic press just after it would otherwise start the question with Robot's own words.
     */
    fun marksQuestion(keyword: String): Boolean = keyword != WakeWord.STOP

    /**
     * Pre-roll in samples at 16 kHz: everything since the last question-marking keyword if that was [sinceWake]
     * samples ago and at most [sinceWakeMs] (negative = none), else [otherwiseMs]; never more than the ring holds.
     */
    fun samples(sinceWake: Long, sinceWakeMs: Int = SINCE_WAKE_MS, otherwiseMs: Int = OTHERWISE_MS, ringMs: Int = RING_MS): Int {
        val rate = Endpointer.RATE.toLong()
        val n = if (sinceWake in 0..sinceWakeMs * rate / 1000) sinceWake else otherwiseMs * rate / 1000
        return n.coerceAtMost(ringMs * rate / 1000).toInt()
    }
}

/**
 * Live partial text by "simulated streaming" (docs/voice-plan.md 4.7): while the person speaks, re-decode the
 * whole buffer about every [intervalSamples], at most one decode at a time, none in the first [firstAfterSamples]
 * of speech. A partial still waiting to run (behind the model load) when a newer one is due is replaced
 * ([Decision.REPLACE]: the old one is cancelled). One already running cannot be stopped (native decode): no new one
 * starts until it is done, and then one starts at once, so a slow phone simply gets fewer partials.
 */
class PartialScheduler(
    private val intervalSamples: Long = 700L * Endpointer.RATE / 1000,
    private val firstAfterSamples: Long = 1L * Endpointer.RATE,
) {
    enum class Decision { NONE, START, REPLACE }

    private var lastDue = Long.MIN_VALUE / 2

    /**
     * At sample [now], with speech since [speechStart] (-1 = none yet) and the previous partial [queued] (submitted,
     * not started) or [running]: should a partial decode be submitted?
     */
    fun decide(now: Long, speechStart: Long, queued: Boolean, running: Boolean): Decision {
        if (speechStart < 0 || now - speechStart < firstAfterSamples) return Decision.NONE
        if (now - lastDue < intervalSamples || running) return Decision.NONE
        lastDue = now
        return if (queued) Decision.REPLACE else Decision.START
    }
}
