// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.SentenceSplitter

// Pure. L4 (review-wikipedia-p4): in a car or a headset the copy's answer is said by sentence as it streams, not after
// the whole one-shot (up to 180 s). Only what a stopped turn would keep is said (WikiTurn.kept): each whole sentence, once
// the passages hold it (WikiAnswer.checked), in order; after one they don't hold, or a stream that started again, nothing
// more streamed is said, and the reply's end ([end]) shows and says the same words, the month line after them.
class WikiSpeech(private val found: WikiAnswer.Found) {
    private var at = 0
    private var off = false
    private val said = StringBuilder()

    /** Something was said. */
    val spoke: Boolean get() = said.isNotEmpty()

    /** The sentences of [soFar] (the one-shot's words so far) to say now. */
    fun feed(soFar: String): List<String> {
        if (off) return emptyList()
        if (soFar.length < at) return emptyList<String>().also { off = true }
        val cut = cut(found, soFar, at, ended = false)
        at = cut.end
        off = cut.off
        cut.sentences.forEach { if (said.isNotEmpty()) said.append(' '); said.append(it) }
        return cut.sentences
    }

    /** What the bubble and History keep ([shown]) and what is left to say ([rest]); [line]: the copy's month line, null without a date. */
    class End(val shown: String, val rest: String, val line: String?)

    /**
     * At the reply's end: [text] the one-shot's words, [reply] the finished reply's spoken words (null: the one-shot stopped).
     * M1 (review-wikipedia-l4): when something was said and the reply doesn't go on from it (it fell back to the passage's
     * lead, or the stream started again), the bubble, History and the voice keep the backed sentences and the month line,
     * so nothing is said twice. L4: a reply stopped after speech (ANSWER_MS, a :brain cancel) still says the month line.
     */
    fun end(text: String, reply: String?): End {
        val line = WikiAnswer.line(found)
        if (reply != null) after(reply)?.let { return End(reply, it, line) }
        if (said.isEmpty()) return End(kept(found, text), "", line)
        val body = kept(found, text).takeIf { after(it) != null } ?: said.toString()
        val shown = WikiAnswer.reply(found, body)
        return End(shown, after(shown).orEmpty(), line)
    }

    /**
     * L2 (review-wikipedia-l4): what [x] says after what was said, or null when it doesn't start with it. Only letters and
     * digits are compared, so a piece with none ("…", "[1].") that wasn't said doesn't make the whole reply said again.
     */
    private fun after(x: String): String? {
        val head = said.filter(Char::isLetterOrDigit)
        var i = 0
        var at = 0
        while (i < head.length) {
            val c = x.getOrNull(at++) ?: return null
            if (c.isLetterOrDigit() && c != head[i++]) return null
        }
        if (head.isNotEmpty() && x.getOrNull(at)?.isLetterOrDigit() == true) return null
        return x.substring(at).trimStart { !it.isLetterOrDigit() && it !in OPENS }
    }

    private class Cut(val sentences: List<String>, val end: Int, val off: Boolean)

    companion object {
        private val MARK = Regex("[.!?…]")

        /** WikiTurn.kept: the whole sentences of [text], marks removed, up to the first the passages don't hold. */
        fun kept(found: WikiAnswer.Found, text: String): String = cut(found, text, 0, ended = true).sentences.joinToString(" ")

        /**
         * A sentence ends at a mark before a space ("3." may be "3.5"); at the end of a stopped or finished text ([ended]) too,
         * unless after a digit (L3, review-wikipedia-l4: a stop inside "1.5"). Never after an abbreviation ("St. ", "M. ").
         */
        private fun ends(text: String, i: Int, ended: Boolean): Boolean {
            if (text[i] == '.' && SentenceSplitter.abbreviation(text, i)) return false
            val next = text.getOrNull(i + 1) ?: return ended && !(text[i] == '.' && text.getOrNull(i - 1)?.isDigit() == true)
            return next.isWhitespace()
        }

        private fun cut(found: WikiAnswer.Found, text: String, from: Int, ended: Boolean): Cut {
            var at = from
            val out = mutableListOf<String>()
            for (m in MARK.findAll(text, from)) {
                if (!ends(text, m.range.first, ended)) continue
                val ok = WikiAnswer.checked(found, text.substring(at, m.range.first + 1)) ?: return Cut(out, at, true)
                at = m.range.first + 1
                if (ok.any(Char::isLetterOrDigit)) out += ok
            }
            return Cut(out, at, false)
        }

        /** What may open a sentence (kept at the start of what's left). */
        private const val OPENS = "«\"“(¿¡"
    }
}
