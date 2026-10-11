package app.heyrobot.core.helpers.wire

// Pure. Some models write their reasoning into the answer between <think> and </think> (DeepSeek-R1 on some hosts,
// llama.cpp with --reasoning-format none, Groq's raw format, many models on a home server). Reasoning is never shown or
// spoken: this filter takes it out of a stream of text pieces, even when a tag is split between two pieces ("<thi" then
// "nk>"). The echoed turn keeps the text as it came; only what reaches the bubble and the voice is filtered.

class ThinkTagFilter {
    private var inside = false
    /** Text held back because it may be the start of a tag. */
    private val pending = StringBuilder()

    /** Reasoning was seen (for "… is thinking"). */
    var sawThinking = false
        private set

    /** Inside a <think> block now. */
    val thinking: Boolean get() = inside

    /** The visible part of [piece] (possibly held back until the next piece shows it isn't a tag). */
    fun feed(piece: String): String {
        pending.append(piece)
        val out = StringBuilder()
        while (pending.isNotEmpty()) {
            val tag = if (inside) CLOSE else OPEN
            val at = pending.indexOf(tag)
            if (at >= 0) {
                if (!inside) out.append(pending, 0, at)
                pending.delete(0, at + tag.length)
                inside = !inside
                if (inside) sawThinking = true
                continue
            }
            // Keep back the longest end of the text that could still become the tag.
            val keep = partial(pending, tag)
            if (!inside) out.append(pending, 0, pending.length - keep)
            pending.delete(0, pending.length - keep)
            break
        }
        return out.toString()
    }

    /** The end of the stream: text held back that never became a tag is shown; an unclosed block stays hidden. */
    fun flush(): String {
        val rest = if (inside) "" else pending.toString()
        pending.setLength(0)
        return rest
    }

    private fun partial(s: CharSequence, tag: String): Int {
        for (n in minOf(tag.length - 1, s.length) downTo 1) {
            if (s.regionMatches(s.length - n, tag, 0, n)) return n
        }
        return 0
    }

    private fun CharSequence.regionMatches(start: Int, other: String, otherStart: Int, len: Int): Boolean {
        for (i in 0 until len) if (this[start + i] != other[otherStart + i]) return false
        return true
    }

    companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
