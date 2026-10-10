package app.heyrobot.voice

// Pure. While Robot speaks, the wake-word model also listens for "STOP" (docs/voice-plan.md 4.13, barge-in). The
// microphone hears Robot too, and on the build machine the keyword model fired on Robot's own "stop", "bus stop",
// "stopwatch" and "step by step" (and on none of 10 other sentences, "top story" and "the next step ... at the
// shop" among them; it caught 29 of 30 spoken "stop"s). So a "STOP" that arrives while
// a piece of speech containing such a word is playing, or shortly after (speaker-to-microphone echo plus the
// keyword model's lag), is taken for Robot's own voice and ignored. The text itself is never kept: only whether a
// piece was risky and where its audio lies in the output stream.
//
// 0.2.3: the same for the wake phrases, now that they are heard while Robot speaks: a piece that says the last word
// of an enabled phrase ("robot" of "hey robot") makes a wake heard while it plays count as her own voice.
//
// 0.3.1: the phone's voice (positions in ms of elapsedRealtime, from its start and done callbacks) is guarded sentence
// by sentence, as Robot's own voices are, instead of for the whole reply. The words stay generous ("step" fired
// "STOP" although its vowel differs, so "steep", "stoop", "stamp" and the like may too) until the barge-in test's
// tricky-words run measures them with 0.3.1's spotter; "stop" or "step" inside a word ("nonstop", "doorstep") counts
// now too. A wake phrase's word counts with any ending ("robotic"). Hits are checked against when their frame was
// read, not when the main thread gets to them, so the window after a piece is 1.2 s instead of 1.5 s.

class EchoGuard(private val rate: Int, private val beforeMs: Int = 300, private val afterMs: Int = AFTER_MS) {
    private class Piece(val start: Long, var end: Long, val stop: Boolean, val wake: Boolean)

    private val risky = ArrayDeque<Piece>()
    private var open: Piece? = null

    /** The wake phrases' words Robot might say herself ([wakeRegex]); null: none. */
    @Volatile var wakeWords: Regex? = null

    /**
     * A piece of speech starts at output frame [start]; its end is not known until it is synthesised, and its first
     * sentence may already play before then, so it counts as open-ended until [close].
     */
    fun open(start: Long, text: String) = open(start, soundsLikeStop(text), saysWakeWord(text))

    /** As [open], for a piece already found to say a word like "stop" ([stop]) or a wake phrase's word ([wake]). */
    @Synchronized
    fun open(start: Long, stop: Boolean, wake: Boolean) {
        // A piece whose end never came (a callback lost) ends where the next one starts.
        open?.let { if (it.end > start) it.end = start }
        open = null
        if (!stop && !wake) return
        val p = Piece(start, Long.MAX_VALUE / 4, stop, wake)
        risky.addLast(p)
        open = p
        while (risky.size > 32) risky.removeFirst()
    }

    /** The open piece ended at output frame [end] (or was dropped: [end] = its start). */
    @Synchronized
    fun close(end: Long) {
        open?.end = end
        open = null
    }

    /** [text] says the last word of an enabled wake phrase ([wakeWords]). */
    fun saysWakeWord(text: String): Boolean = wakeWords?.containsMatchIn(text) == true

    /** A whole piece written at frames [start, end). */
    fun onPiece(start: Long, end: Long, text: String) {
        open(start, text)
        close(end)
    }

    /** The output was flushed (stop) or ran dry: frame numbers start again at 0. */
    @Synchronized
    fun reset() {
        risky.clear()
        open = null
    }

    /** Could a "STOP" heard while the output plays frame [played] be Robot's own voice? */
    @Synchronized
    fun mayBeEcho(played: Long): Boolean = near(played) { it.stop }

    /** Could a wake phrase heard while the output plays frame [played] be Robot's own voice? */
    @Synchronized
    fun mayBeWakeEcho(played: Long): Boolean = near(played) { it.wake }

    /** Robot said a word like "stop" up to [ms] before frame [played] (more than the guard covers), for the voice log. */
    @Synchronized
    fun stopLikeWithin(played: Long, ms: Int): Boolean = near(played, ms) { it.stop }

    private fun near(played: Long, afterMs: Int = this.afterMs, kind: (Piece) -> Boolean): Boolean {
        val before = beforeMs.toLong() * rate / 1000
        val after = afterMs.toLong() * rate / 1000
        return risky.any { kind(it) && played >= it.start - before && played <= it.end + after }
    }

    companion object {
        /**
         * After a piece ends, a keyword heard this long can still be its echo: the sound leaves the speaker a little after
         * the track's head passes it, and the spotter reports a keyword up to about a second after it is said (a 320 ms
         * chunk, its lookahead and the blank frames after the word). Measured from when the hit's frame was read.
         */
        const val AFTER_MS = 1_200

        /** How far back STOP_HEARD notes a word like "stop" Robot said, outside the guard too. */
        const val NOTE_MS = 3_000

        /**
         * A word starting with "st", up to two letters and "p" ("stop", "step", "steep", "stoop", "stamp", "stomp",
         * "stump", "stupid", "strip"), or "stop"/"step" inside a word ("nonstop", "doorstep", "unstoppable"); not with
         * "ph" after it ("Stephen", "Christopher").
         */
        private val STOP_LIKE = Regex("""\bst[a-z]{0,2}p(?!h)|st[eo]p(?!h)""", RegexOption.IGNORE_CASE)

        fun soundsLikeStop(text: String): Boolean = STOP_LIKE.containsMatchIn(text)

        /** Any word starting with one of [words] ("robot", "Robot's", "robots", "robotic"), or null for none. */
        fun wakeRegex(words: List<String>): Regex? {
            val w = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (w.isEmpty()) return null
            return Regex("\\b(" + w.joinToString("|") { Regex.escape(it) } + ")[a-z']*", RegexOption.IGNORE_CASE)
        }
    }
}
