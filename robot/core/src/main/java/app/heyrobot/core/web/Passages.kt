package app.heyrobot.core.web

import app.heyrobot.core.TokenEstimate
import kotlin.math.ln

// Pure. Which parts of the pages Gemma reads (design A6.1 Passages, A8): the pages' text is cut into passages, one a
// paragraph (shorter paragraphs and list items join the next until 25 words, longer ones are cut at sentences), ranked
// against the question with BM25 (k1 1.2, b 0.75) over every passage of every page read (a section heading's words count
// half), the lead passage of a page gets a small bonus, and the best are taken until the budget is full: 2,000 tokens in
// all, 900 at most from one page. They go to the prompt in page order, so each page's part reads as it was written.

/**
 * A piece of a page: [source] the number of the result it came from (1-based, as the answer cites it), [index] its
 * place in that page, [text] what Gemma reads, [heading] the section it is under (ranked with it, not shown), [tokens]
 * Gemma's estimate, [score] its BM25 score for the question.
 */
data class Passage(val source: Int, val index: Int, val text: String, val heading: String? = null, val tokens: Int = TokenEstimate.text(text),
    val score: Double = 0.0)

object Passages {
    const val K1 = 1.2
    const val B = 0.75
    /** Every page's passages together, in Gemma's tokens (A8). */
    const val TOTAL_TOKENS = 2_000
    /** One page's at most. */
    const val PAGE_TOKENS = 900
    /** A paragraph shorter than this joins the next one in its section… */
    const val MIN_WORDS = 25
    /** …and a longer block is cut at sentences into pieces of at most this many. */
    const val MAX_WORDS = 160
    /** A section heading's words count this much against the passage's own. */
    const val HEADING_WEIGHT = 0.5
    /** The first passage of a page usually says what the page is about. */
    const val LEAD_BONUS = 1.2
    /** Lets a lead passage that matches nothing still come before other passages that match nothing. */
    private const val LEAD_FLOOR = 0.01

    private val SENTENCE = Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}\\p{N}\"“«(])")
    /** Footnote marks ("[2]", "[note 1]", "[citation needed]") that would look like the answer's own [n] marks. */
    private val FOOTNOTE = Regex("\\[(?:\\d{1,3}|[a-z]|note \\d+|citation needed|réf\\. nécessaire|n \\d+)]", RegexOption.IGNORE_CASE)

    /** A page's blocks as passages, numbered from 0 in page order. Headings label what follows them. */
    fun split(source: Int, page: PageContent): List<Passage> {
        val out = ArrayList<Passage>()
        val buf = StringBuilder()
        var words = 0
        var heading: String? = null
        var bufHeading: String? = null
        fun emit() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) out += Passage(source, out.size, t, bufHeading)
            buf.setLength(0)
            words = 0
        }
        for (b in page.blocks) {
            if (b.kind == PageBlock.Kind.HEADING) {
                emit()
                heading = clean(b.text)
                continue
            }
            val text = clean(b.render())
            if (text.isEmpty()) continue
            for (piece in pieces(text)) {
                val n = piece.split(' ').size
                if (words > 0 && words + n > MAX_WORDS) emit()
                if (buf.isEmpty()) bufHeading = heading
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(piece)
                words += n
                if (words >= MIN_WORDS) emit()
            }
        }
        emit()
        return out
    }

    /** Footnote marks and stray whitespace out. */
    fun clean(s: String): String = FOOTNOTE.replace(s, "").replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.;:!?])"), "$1").trim()

    /** [text] in pieces of at most [MAX_WORDS] words, cut at sentence ends when it can. */
    private fun pieces(text: String): List<String> {
        if (text.split(' ').size <= MAX_WORDS) return listOf(text)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var n = 0
        for (sentence in text.split(SENTENCE)) {
            val words = sentence.split(' ').filter { it.isNotEmpty() }
            if (words.size > MAX_WORDS) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0); n = 0 }
                words.chunked(MAX_WORDS).forEach { out += it.joinToString(" ") }
                continue
            }
            if (n + words.size > MAX_WORDS && cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0); n = 0 }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(sentence.trim())
            n += words.size
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** [passages] with their BM25 score for [question] (stop words out, light stemming, English and French). */
    fun rank(question: String, passages: List<Passage>): List<Passage> {
        if (passages.isEmpty()) return passages
        val terms = WebWords.terms(question).distinct()
        // Term frequencies, a heading's words at half weight; a passage's length likewise.
        val docs = passages.map { p ->
            val tf = HashMap<String, Double>()
            WebWords.terms(p.text).forEach { tf[it] = (tf[it] ?: 0.0) + 1.0 }
            p.heading?.let { h -> WebWords.terms(h).forEach { tf[it] = (tf[it] ?: 0.0) + HEADING_WEIGHT } }
            tf
        }
        val lengths = docs.map { it.values.sum() }
        val avg = lengths.sum() / docs.size
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.keys.forEach { t -> df[t] = (df[t] ?: 0) + 1 } }
        val n = docs.size.toDouble()
        return passages.mapIndexed { i, p ->
            val tf = docs[i]
            var s = 0.0
            for (t in terms) {
                val f = tf[t] ?: continue
                val idf = ln(1 + (n - (df[t] ?: 0) + 0.5) / ((df[t] ?: 0) + 0.5))
                s += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * lengths[i] / avg.coerceAtLeast(1.0)))
            }
            if (p.index == 0) s = s * LEAD_BONUS + LEAD_FLOOR
            p.copy(score = s)
        }
    }

    /**
     * The passages of [pages] (each a source number and its text) that best answer [question], within [totalTokens] and
     * [pageTokens] a page, in page order. Passages that match nothing are left out, except a page's lead when nothing
     * better fills the room.
     */
    fun select(question: String, pages: List<Pair<Int, PageContent>>, totalTokens: Int = TOTAL_TOKENS, pageTokens: Int = PAGE_TOKENS): List<Passage> {
        val ranked = rank(question, pages.flatMap { (source, page) -> split(source, page) })
        val chosen = ArrayList<Passage>()
        val perPage = HashMap<Int, Int>()
        var total = 0
        for (p in ranked.filter { it.score > 0 }.sortedByDescending { it.score }) {
            val cost = p.tokens + OVERHEAD
            if (total + cost > totalTokens || (perPage[p.source] ?: 0) + cost > pageTokens) continue
            chosen += p
            total += cost
            perPage[p.source] = (perPage[p.source] ?: 0) + cost
        }
        return chosen.sortedWith(compareBy({ pages.indexOfFirst { pg -> pg.first == it.source } }, { it.index }))
    }

    /** What a passage costs in the prompt besides its text ("[2] nhl.com: ", the line break). */
    const val OVERHEAD = 8
}
