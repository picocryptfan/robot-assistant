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

    var samples = 0L; private set
    /** Sample index where speech was first detected, or -1. */
    var speechStart = -1L; private set
    private var speaking = false
    private var done = false

    val heardSpeech: Boolean get() = speechStart >= 0

    /** One frame of [n] samples with the VAD's flag after it; returns what that means (once [END], [NO_SPEECH] or [MAX_LENGTH], always NONE). */
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

    companion object {
        const val RATE = 16_000
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
