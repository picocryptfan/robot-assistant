package app.heyrobot.core

import java.util.Locale

// Pure. Translation in conversation mode (docs/conversation-mode.md 4): the language pair, the engines' common
// interface, and the guards every translation passes before it is shown or spoken (MtGuard). Gemma (through :brain's
// one-shot request, phase 5.4) and Bergamot ([BergamotTranslator], over the :mt process) both implement Translator.

/** The languages a conversation translates between. 0.5.0 has one pair; more follow the same shape. */
enum class LangPair(val id: String, val first: String, val second: String, val title: String) {
    FR_EN("fr-en", "fr", "en", "French ↔ English");

    fun has(lang: String?): Boolean = lang == first || lang == second

    /** The other language of the pair, or null for a language outside it. */
    fun other(lang: String?): String? = when (lang) {
        first -> second
        second -> first
        else -> null
    }

    companion object {
        val DEFAULT = FR_EN

        fun of(id: String?): LangPair = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** What MtGuard decided about one translation (logged as this code, never the words). */
enum class MtVerdict(override val id: Int, override val title: String) : LogCode {
    OK(1, "kept"),
    LANGUAGE(2, "not in the language asked for"),
    LENGTH(3, "much shorter or longer than what was said"),
    /** A repeated sentence was removed ("Yes. Yes."): the translation is kept. */
    REPEAT(4, "kept, a repeat removed"),
    TIMEOUT(5, "too slow"),
    LINES(6, "too many lines"),
    EMPTY(7, "empty");

    /** The translation may be shown and spoken. */
    val usable: Boolean get() = this == OK || this == REPEAT
}

/** A checked translation: [text] is what to show (null when [verdict] isn't usable). */
data class MtResult(val text: String?, val verdict: MtVerdict)

/** A translation engine. Throws when it can't answer; the caller logs the failure as a code. */
interface Translator {
    val engine: MtEngine

    suspend fun translate(text: String, from: String, to: String): String
}

/**
 * How a client reaches the :mt process (the app's MtLink: a Messenger binding). Throws [MtException] when the service
 * answers with an error or goes away.
 */
interface MtTransport {
    suspend fun translate(direction: String, lines: List<String>, deadlineMs: Long, priority: MtPriority): MtMsg.Translated
}

/**
 * Bergamot, the fast engine, over the :mt process (docs/conversation-mode.md 4.1, 4.5). It takes off a short line's full
 * stop before the engine sees it and puts it back after ([MtGuard.prepare]); the caller then runs [MtGuard.check], as for
 * Gemma. [translate] is one line someone waits for (the interpreter's fallback): interactive, [lineDeadlineMs].
 * [translateAll] is a backlog (a transcript's lines): batch priority, so an interactive line goes first.
 */
class BergamotTranslator(private val transport: MtTransport, private val lineDeadlineMs: Long = MtPolicy.LINE_DEADLINE_MS) : Translator {
    override val engine: MtEngine get() = MtEngine.BERGAMOT

    override suspend fun translate(text: String, from: String, to: String): String {
        val d = direction(from, to)
        val p = MtGuard.prepare(text, engine)
        if (p.text.isBlank()) return ""
        val r = transport.translate(d.id, listOf(p.text), lineDeadlineMs, MtPriority.INTERACTIVE)
        val out = r.out.firstOrNull() ?: throw MtException(if (r.failed > 0) MtError.FAILED else MtError.TIMEOUT)
        return MtGuard.restore(out, p)
    }

    /**
     * Each line's translation, in order; null for a line the engine didn't reach before [deadlineMs] (from when :mt gets
     * the request) or failed on. A blank line stays blank without asking the engine. At most [MtPolicy.MAX_LINES] lines.
     */
    suspend fun translateAll(lines: List<String>, from: String, to: String, deadlineMs: Long, priority: MtPriority = MtPriority.BATCH): List<String?> {
        val d = direction(from, to)
        if (lines.size > MtPolicy.MAX_LINES) throw MtException(MtError.BAD_REQUEST)
        val prepared = lines.map { MtGuard.prepare(it, engine) }
        val asked = prepared.withIndex().filter { it.value.text.isNotBlank() }
        val out = arrayOfNulls<String>(lines.size)
        prepared.forEachIndexed { i, p -> if (p.text.isBlank()) out[i] = "" }
        if (asked.isNotEmpty()) {
            val r = transport.translate(d.id, asked.map { it.value.text }, deadlineMs, priority)
            asked.forEachIndexed { k, (i, p) -> out[i] = r.out.getOrNull(k)?.let { MtGuard.restore(it, p) } }
        }
        return out.toList()
    }

    private fun direction(from: String, to: String): MtDirection =
        MtBundle.entries.firstNotNullOfOrNull { it.direction(from, to) } ?: throw MtException(MtError.BAD_REQUEST)
}

/**
 * The guards on every translation (docs/conversation-mode.md 4.2), for both engines: the output is in the target
 * language, about as long as what was said, at most a few lines, without a label, quotes or notes around it, and
 * without Bergamot's doubled sentence ("Oui." → "Yes. Yes."). A meaning error ("à cinq ans" for "at five") gets
 * through: that is why Gemma improves transcript lines and leads the interpreter.
 */
object MtGuard {
    const val MIN_RATIO = 0.4
    const val MAX_RATIO = 2.5
    /** Characters of slack on both length bounds, so a short line may change length a lot ("OK" → "D'accord"). */
    const val SLACK = 8
    const val MAX_NEWLINES = 2
    /** Bergamot's input of at most this many words loses its final full stop ([prepare]). */
    const val SHORT_WORDS = 2

    /** The text an engine gets, and whether a full stop was taken off it ([restore] puts it back). */
    data class Prepared(val text: String, val stop: Boolean)

    /**
     * Bergamot doubles a short sentence that ends with a full stop ("Oui." → "Yes. Yes.", "Non." → "No. No."; without
     * the stop, "Oui" → "Yes"): for at most [SHORT_WORDS] words the stop goes, and comes back after. Gemma's input is
     * left alone.
     */
    fun prepare(source: String, engine: MtEngine): Prepared {
        val t = source.trim()
        if (engine != MtEngine.BERGAMOT || LangId.words(t).size > SHORT_WORDS || !t.endsWith(".") || t.endsWith("..")) return Prepared(t, false)
        return Prepared(t.dropLast(1).trimEnd(), true)
    }

    fun restore(output: String, p: Prepared): String {
        val t = output.trim()
        return if (p.stop && t.isNotEmpty() && t.last() !in ".!?…") "$t." else t
    }

    private val LABEL = Regex("""^\s*(?:translation|traduction|english|anglais|french|français|francais|en|fr)\s*(?:\([^)]*\))?\s*:\s*""",
        RegexOption.IGNORE_CASE)
    private val NOTE_LINE = Regex("""^\s*[(*_]*\s*(?:note|notes|remarque|n\.b\.|literally|littéralement|translator's note|note du traducteur)\b""",
        RegexOption.IGNORE_CASE)
    private val NOTE_TAIL = Regex("""\s*\((?:note|remarque|literally|littéralement|or)\b[^)]*\)\s*$""", RegexOption.IGNORE_CASE)
    private val QUOTES = listOf("\"" to "\"", "“" to "”", "«" to "»", "‘" to "’", "„" to "“", "'" to "'")

    /** Takes off what models wrap a translation in: a leading "Translation:", quotes around it, notes after it. */
    fun clean(output: String): String {
        var t = output.trim()
        // Notes: from the first line that starts one, and a parenthesised note at the very end.
        val lines = t.lines()
        val cut = lines.indexOfFirst { NOTE_LINE.containsMatchIn(it) }
        if (cut > 0) t = lines.take(cut).joinToString("\n").trim()
        t = t.replace(NOTE_TAIL, "").trim()
        t = t.replace(LABEL, "").trim()
        for ((open, close) in QUOTES) {
            if (t.length >= open.length + close.length + 1 && t.startsWith(open) && t.endsWith(close)) {
                val inner = t.substring(open.length, t.length - close.length)
                // Only a pair around the whole text: "Il a dit « oui » et « non »" keeps its quotes.
                if (!inner.contains(open) && !inner.contains(close)) t = inner.trim()
                break
            }
        }
        return t
    }

    private val SENTENCE = Regex("""[^.!?…]+[.!?…]*""")

    /** The sentences of [t], trimmed, the empty ones dropped. */
    fun sentences(t: String): List<String> = SENTENCE.findAll(t).map { it.value.trim() }.filter { it.any(Char::isLetterOrDigit) }.toList()

    /** One sentence said, the same sentence written twice or more ("Yes. Yes."): once. */
    fun collapseRepeats(source: String, output: String): String {
        if (sentences(source).size != 1) return output
        val s = sentences(output)
        if (s.size < 2) return output
        val key = { x: String -> x.lowercase(Locale.ROOT).trimEnd('.', '!', '?', '…', ' ') }
        return if (s.all { key(it) == key(s[0]) }) s[0] else output
    }

    private fun len(s: String): Int = s.codePointCount(0, s.length)

    /**
     * The verdict on [output], a translation of [source] into [to]. [id] tells the languages apart: the output must be
     * in [to], or not sure when it has at most two words; a source that is itself not sure (a name, a number) skips
     * that check. [timedOut]: the engine didn't answer in time.
     */
    fun check(source: String, output: String?, to: String, id: LangId, timedOut: Boolean = false): MtResult {
        if (timedOut) return MtResult(null, MtVerdict.TIMEOUT)
        val cleaned = clean(output ?: "")
        if (cleaned.isEmpty() || cleaned.none(Char::isLetterOrDigit)) return MtResult(null, MtVerdict.EMPTY)
        if (cleaned.count { it == '\n' } > MAX_NEWLINES) return MtResult(null, MtVerdict.LINES)
        val collapsed = collapseRepeats(source, cleaned)
        val repeat = collapsed != cleaned
        val src = len(source.trim())
        val out = len(collapsed)
        if (out > MAX_RATIO * src + SLACK || out < MIN_RATIO * src - SLACK) return MtResult(null, MtVerdict.LENGTH)
        val g = id.detect(collapsed)
        val wrong = when {
            g.lang != null -> g.lang != to
            g.words <= 2 -> false
            else -> id.detect(source).lang != null
        }
        if (wrong) return MtResult(null, MtVerdict.LANGUAGE)
        return MtResult(collapsed, if (repeat) MtVerdict.REPEAT else MtVerdict.OK)
    }
}
