// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. Replies are spoken sentence by sentence while they stream (spec item 4): the splitter hands
// out each sentence as soon as it is complete; SpeechText turns Markdown into something to say.

/**
 * Splits a streaming reply into pieces to speak. The first piece is short so audio starts fast (docs/voice-plan.md
 * 4.9): it goes out at the first clause break (, ; :) after at least [firstMinWords] words, or after
 * [firstMaxWords] words, if no sentence ended before. After that, whole sentences (the voice model speaks a whole
 * sentence per call and pays a fixed cost per call, so smaller pieces would only add pauses).
 */
class SentenceSplitter(private val maxChars: Int = 220, private val firstMinWords: Int = 5, private val firstMaxWords: Int = 12) {
    private val buf = StringBuilder()
    private var emitted = false

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
        if (out.isEmpty() && !emitted) firstChunk()?.let { out += it }
        if (out.isNotEmpty()) emitted = true
        return out
    }

    /** The short first piece, or null while the reply is still too short to cut. */
    private fun firstChunk(): String? {
        var words = 0
        var inWord = false
        for (i in buf.indices) {
            val c = buf[i]
            if (c.isWhitespace()) {
                if (inWord) {
                    words++
                    inWord = false
                    val prev = buf[i - 1]
                    val clause = (prev == ',' || prev == ';' || prev == ':') && words >= firstMinWords
                    if (clause || words >= firstMaxWords) {
                        val s = buf.substring(0, i).trim()
                        buf.delete(0, i)
                        return s.takeIf { it.isNotEmpty() }
                    }
                }
            } else {
                inWord = true
            }
        }
        return null
    }

    /** The rest, at the end of the reply. */
    fun flush(): String? = buf.toString().trim().also { buf.setLength(0) }.takeIf { it.isNotEmpty() }

    /** A new reply: its first piece is short again. */
    fun reset() {
        buf.setLength(0)
        emitted = false
    }

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

/**
 * Which parts of the reply being streamed are read aloud (the panel calls it on its main thread). One reply at a
 * time: text of any other reply, of a reply that was muted ("stop" said or tapped while it streams) or of one that
 * was cancelled is never spoken, and a cancelled reply's unfinished last sentence is dropped, not read out. The
 * next question speaks again.
 */
class ReplyVoice(private val splitter: SentenceSplitter = SentenceSplitter()) {
    private var reply: String? = null
    private var muted = false

    /** Reply [id] starts: its first piece is short again, and it is not muted. */
    fun start(id: String) {
        reply = id
        muted = false
        splitter.reset()
    }

    /** Streamed text of reply [id]: the pieces to say now (none with [speak] off, or for a muted or old reply). */
    fun feed(id: String, text: String, speak: Boolean): List<String> = if (speak && live(id)) splitter.feed(text) else emptyList()

    /** A whole sentence reply [id]'s turn says by itself (an action's result), or null if it must stay silent. */
    fun say(id: String, sentence: String, speak: Boolean): String? = sentence.takeIf { speak && live(id) }

    /** Reply [id] ended: the rest to say, or null (cancelled, muted, speech off, or not the current reply). */
    fun finish(id: String, cancelled: Boolean, speak: Boolean): String? {
        if (id != reply) return null
        val rest = if (speak && !muted && !cancelled) splitter.flush() else null
        splitter.reset()
        reply = null
        return rest
    }

    /** "Stop" while the reply streams (or Stop, or a new question by voice): nothing more of it is spoken. */
    fun mute() {
        if (reply != null) muted = true
        splitter.reset()
    }

    /** True while reply [id] is the current one and may still be spoken. */
    fun live(id: String): Boolean = id == reply && !muted
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
        // Emoji, pictographs, skin tones, joiners (the voices would spell out or garble them); the degree sign stays.
        .replace(Regex("""[\p{So}\p{Cs}\x{FE0F}\x{200D}\x{20E3}\x{1F3FB}-\x{1F3FF}&&[^\x{B0}]]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .let(::sayable)

    /**
     * Chinese, Japanese and Korean script (Han, kana, Hangul, Bopomofo, Yi, CJK symbols, punctuation and
     * compatibility forms, the supplementary ideograph planes).
     */
    private val CJK = Regex("""[\x{1100}-\x{11FF}\x{2E80}-\x{2FFF}\x{3000}-\x{9FFF}\x{A000}-\x{A4CF}\x{AC00}-\x{D7AF}\x{F900}-\x{FAFF}\x{FE30}-\x{FE4F}\x{FF00}-\x{FFEF}\x{20000}-\x{3134F}]+""")

    /**
     * Only what Robot's voices can say, and nothing sherpa-onnx would write to the log. CJK text is dropped: with no
     * lexicon, Kokoro (1.13.8) sends every U+4E00-9FFF character through its Chinese path, which logs it ("Ignore
     * OOV") whatever the debug setting, and the voices read English and French only. Fullwidth ASCII becomes ASCII.
     * Blank when no letter or digit is left: sherpa logs a piece it cannot turn into sounds whole ("Failed to convert").
     */
    fun sayable(s: String): String {
        val ascii = buildString(s.length) { for (c in s) append(if (c in '\uFF01'..'\uFF5E') c - 0xFEE0 else c) }
        val out = ascii.replace(CJK, " ").replace(Regex("""\(\s*\)|\[\s*]"""), " ").replace(Regex("""\s+"""), " ").replace(Regex(""" ([,.;:!?])"""), "$1")
            .trim().trimStart(',', ';', ':', '.', ' ')
        return if (out.any { it.isLetterOrDigit() }) out else ""
    }
}
