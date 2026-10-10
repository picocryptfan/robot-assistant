package app.heyrobot.core

// Pure. Replies are spoken sentence by sentence while they stream (spec item 4): the splitter hands
// out each sentence as soon as it is complete; SpeechText turns Markdown into something to say.

class SentenceSplitter(private val maxChars: Int = 220) {
    private val buf = StringBuilder()

    /** Adds streamed text; returns the sentences it completed (possibly none). */
    fun feed(chunk: String): List<String> {
        buf.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val end = boundary(buf) ?: break
            val s = buf.substring(0, end).trim()
            buf.delete(0, end)
            if (s.isNotEmpty()) out += s
        }
        // A very long run without punctuation: cut at the last comma or space so speech can start.
        if (buf.length > maxChars) {
            val cut = buf.lastIndexOfAny(charArrayOf(',', ';', ':'), maxChars).takeIf { it > maxChars / 3 }
                ?: buf.lastIndexOf(" ", maxChars).takeIf { it > 0 } ?: maxChars
            val s = buf.substring(0, cut + 1).trim()
            buf.delete(0, cut + 1)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    /** The rest, at the end of the reply. */
    fun flush(): String? = buf.toString().trim().also { buf.setLength(0) }.takeIf { it.isNotEmpty() }

    fun reset() = buf.setLength(0)

    /** End index (exclusive) of the first complete sentence in [b], or null. */
    private fun boundary(b: CharSequence): Int? {
        var i = 0
        while (i < b.length) {
            val c = b[i]
            if (c == '\n') {
                // A blank line or a list line ends a sentence.
                if (i > 0) return i + 1
            } else if (c == '.' || c == '!' || c == '?' || c == '…') {
                // Need the next character to decide (a decimal point, "e.g.", or the end of the stream so far).
                var j = i + 1
                while (j < b.length && b[j] in ".!?…\"')”’") j++
                if (j >= b.length) return null
                if (b[j].isWhitespace() && !abbreviation(b, i) && !(c == '.' && isNumberedListMarker(b, i))) return j
                i = j - 1
            }
            i++
        }
        return null
    }

    private fun abbreviation(b: CharSequence, dot: Int): Boolean {
        var k = dot - 1
        while (k >= 0 && b[k].isLetter()) k--
        val word = b.subSequence(k + 1, dot).toString().lowercase()
        return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter() && dot >= 2 && b[dot - 2] == '.')
    }

    /** "1. " at the start of a line is a list marker, not the end of a sentence. */
    private fun isNumberedListMarker(b: CharSequence, dot: Int): Boolean {
        var k = dot - 1
        while (k >= 0 && b[k].isDigit()) k--
        return k < dot - 1 && (k < 0 || b[k] == '\n')
    }

    companion object {
        private val ABBREVIATIONS = setOf("e.g", "i.e", "mr", "mrs", "ms", "dr", "st", "vs", "etc", "approx", "no", "min", "sec")
    }
}

object SpeechText {
    /** Markdown and symbols removed so a voice reads it naturally; blank when nothing is left to say. */
    fun clean(s: String): String = s
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""https?://\S+"""), "a link")
        .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        .replace(Regex("""(?m)^\s*([-*+•]|\d+[.)])\s+"""), "")
        .replace(Regex("""(?m)^\s*>\s?"""), "")
        .replace("```", " ")
        .replace(Regex("""(\*\*|__|\*|`|~~)"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
