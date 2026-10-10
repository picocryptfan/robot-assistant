package app.heyrobot.voice

// Pure. While Robot speaks, the wake-word model also listens for "STOP" (docs/voice-plan.md 4.13, barge-in). The
// microphone hears Robot too, and on the build machine the keyword model fired on Robot's own "stop", "bus stop",
// "stopwatch" and "step by step" (and on none of 13 sentences without such a word). So a "STOP" that arrives while
// a piece of speech containing such a word is playing, or shortly after (speaker-to-microphone echo plus the
// keyword model's lag), is taken for Robot's own voice and ignored. The text itself is never kept: only whether a
// piece was risky and where its audio lies in the output stream.

class EchoGuard(private val rate: Int, private val beforeMs: Int = 300, private val afterMs: Int = 1_500) {
    private class Piece(val start: Long, val end: Long)

    private val risky = ArrayDeque<Piece>()

    /** A piece of speech was written to the output stream at frames [start, end). */
    @Synchronized
    fun onPiece(start: Long, end: Long, text: String) {
        if (end <= start || !soundsLikeStop(text)) return
        risky.addLast(Piece(start, end))
        while (risky.size > 32) risky.removeFirst()
    }

    /** The output was flushed (stop) or ran dry: frame numbers start again at 0. */
    @Synchronized
    fun reset() = risky.clear()

    /** Could a "STOP" heard while the output plays frame [played] be Robot's own voice? */
    @Synchronized
    fun mayBeEcho(played: Long): Boolean {
        val before = beforeMs.toLong() * rate / 1000
        val after = afterMs.toLong() * rate / 1000
        return risky.any { played >= it.start - before && played <= it.end + after }
    }

    companion object {
        /** "stop", "stops", "stopped", "stopwatch", "step", "stoop", "stamp", "strip"...: generous on purpose. */
        private val STOP_LIKE = Regex("""\bst[a-z]{0,2}p""", RegexOption.IGNORE_CASE)

        fun soundsLikeStop(text: String): Boolean = STOP_LIKE.containsMatchIn(text)
    }
}
