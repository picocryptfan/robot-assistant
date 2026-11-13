// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.SentenceSplitter
import app.heyrobot.core.SpeechText

// Pure. Read it to me (docs/plans/read-it-to-me-plan.md §2): a long text cut into the pieces Robot speaks one by one.
// Whole sentences, cut by the panel's own SentenceSplitter (its abbreviations, decimals, times and list markers), so a
// reading says numbers and abbreviations as every reply does; the voice's PieceText and SpeechText.forEngine do the rest.
// A PDF's page text first loses its repeated headers and footers and its page numbers, and a word broken by a hyphen at a
// line's end is joined again.

/** One spoken piece: [text] for the voice, [start] and [end] (exclusive) where it sits in its [ReadText.text]. */
data class ReadPiece(val text: String, val start: Int, val end: Int)

/** A source's text made ready to read. The pieces' offsets point into [text]. */
class ReadText(val text: String, val pieces: List<ReadPiece>) {
    /** The piece holding [offset]: the last one starting at or before it (0 before the first, the last after the end). */
    fun pieceAt(offset: Int): Int {
        var lo = 0
        var hi = pieces.size - 1
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (pieces[mid].start <= offset) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}

object ReadChunker {
    /** The longest piece, in characters: a longer sentence is cut at a clause break or a space (SentenceSplitter). */
    const val MAX_PIECE = 300

    /** The most text one reading takes (a long book chapter); the rest is not read. */
    const val MAX_TEXT = 500_000

    /** A page's first and last [EDGE] lines are where its headers, footers and page numbers are looked for. */
    private const val EDGE = 2

    private val PAGE_NUMBER = Regex("""^(page|p\.|pg\.?)?\s*[-–—]?\s*\d{1,4}\s*([-–—]|(of|sur|de|/)\s*\d{1,4})?\s*$""", RegexOption.IGNORE_CASE) // l10n: not text (words a PDF prints)

    /** Plain text: a shared page or text, a note, a message. Its lines and blank lines end sentences. */
    fun plain(raw: String): ReadText = build(cap(raw).replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' '))

    /** A PDF's text, page by page (PdfRenderer's text API): wrapped lines become paragraphs again. */
    fun pdf(pages: List<String>): ReadText = build(cap(joinPages(pages)))

    private fun cap(s: String): String = if (s.length > MAX_TEXT) s.substring(0, MAX_TEXT) else s

    private fun build(text: String): ReadText {
        val split = SentenceSplitter(maxChars = MAX_PIECE, firstMinWords = Int.MAX_VALUE, firstMaxWords = Int.MAX_VALUE)
        val pieces = mutableListOf<ReadPiece>()
        var from = 0
        fun add(raw: String) {
            val at = text.indexOf(raw, from)
            if (at < 0) return
            from = at + raw.length
            val said = SpeechText.clean(raw)
            if (said.any { it.isLetterOrDigit() }) pieces += ReadPiece(said, at, at + raw.length)
        }
        var i = 0
        while (i < text.length) {
            // One word and the spaces after it at a time, so a long sentence is cut near MAX_PIECE.
            var j = i
            while (j < text.length && !text[j].isWhitespace()) j++
            while (j < text.length && text[j].isWhitespace()) j++
            split.feed(text.substring(i, j)).forEach(::add)
            i = j
        }
        split.flush()?.let(::add)
        return ReadText(text, pieces)
    }

    /** The pages' text as paragraphs: edges that repeat dropped, broken words joined, wrapped lines put together. */
    internal fun joinPages(pages: List<String>): String {
        val lines = pages.map { p -> p.replace("\r\n", "\n").split('\n', '\r').map { it.replace('\t', ' ').trim() } }
        val repeated = repeatedEdges(lines)
        val out = StringBuilder()
        for (page in lines) {
            val body = page.indices.filter { k -> !edge(page, k) || (!PAGE_NUMBER.matches(page[k]) && key(page[k]) !in repeated) }.map { page[it] }
            for (line in body) {
                if (line.isEmpty()) {
                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                    continue
                }
                if (out.isNotEmpty() && !out.endsWith("\n")) {
                    if (broken(out) && line[0].isLowerCase()) out.setLength(out.length - 1) else out.append(' ')
                }
                out.append(line)
            }
        }
        return out.toString()
    }

    /** [out] ends with a letter and a hyphen (or a soft hyphen): a word broken at the line's end. */
    private fun broken(out: StringBuilder): Boolean = out.length >= 2 && out[out.length - 2].isLetter() && (out.last() == '-' || out.last() == '\u00AD')

    /** Whether line [k] is among the first or last [EDGE] lines of [page] that have text. */
    private fun edge(page: List<String>, k: Int): Boolean {
        val full = page.indices.filter { page[it].isNotEmpty() }
        return k in full.take(EDGE) || k in full.takeLast(EDGE)
    }

    /** A line's words without its digits ("Lease 2026, page 3" and "Lease 2026, page 4" are one header). */
    private fun key(line: String): String = line.lowercase().replace(Regex("""\d+"""), "#").replace(Regex("""\s+"""), " ").trim()

    /** The edge lines that come back on at least half the pages (and on two at least): running headers and footers. */
    private fun repeatedEdges(pages: List<List<String>>): Set<String> {
        if (pages.size < 2) return emptySet()
        val seen = HashMap<String, Int>()
        for (page in pages) page.indices.filter { edge(page, it) }.map { key(page[it]) }.filter { it.isNotEmpty() }.toSet().forEach { seen[it] = (seen[it] ?: 0) + 1 }
        val need = maxOf(2, (pages.size + 1) / 2)
        return seen.filterValues { it >= need }.keys
    }
}
