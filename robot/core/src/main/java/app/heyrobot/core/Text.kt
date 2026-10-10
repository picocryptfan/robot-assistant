package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure text helpers: matching an app name, preparing screen text, and light Markdown for chat bubbles.

/** A launchable app: its label and an opaque key (package/activity) the executor uses to start it. */
data class AppEntry(val label: String, val key: String)

/** How an app name matched the name the model gave. */
enum class MatchKind {
    EXACT,
    /** The label starts with the name ("organic" → Organic Maps), or the name is the label plus words ("files by google" → Files). */
    PREFIX,
    /** A word of the label is (or starts with) the name ("maps" → Organic Maps). */
    WORD,
    /** The name is inside a word of the label ("warden" → Bitwarden): shown on a card before anything opens. */
    CONTAINS,
}

data class AppMatch(val app: AppEntry, val kind: MatchKind) {
    /** Not clearly the app the person named: Robot shows the card (with the app's real name and icon) even if apps may just open. */
    val loose: Boolean get() = kind == MatchKind.CONTAINS
}

object AppMatcher {
    private val FILLER = setOf("app", "the", "application", "my", "open")

    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
    }

    /**
     * How [query] matches [label], or null. A name that only looks like a label (one or two letters off: "files"
     * against "Tiles") never matches: opening the wrong app is worse than saying it isn't installed.
     */
    fun kind(query: String, label: String): MatchKind? {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return null
        val qc = q.replace(" ", "")
        val lc = l.replace(" ", "")
        return when {
            q == l || qc == lc -> MatchKind.EXACT
            qc.length >= 3 && lc.startsWith(qc) -> MatchKind.PREFIX
            q.startsWith("$l ") -> MatchKind.PREFIX
            q.length >= 3 && l.split(' ').any { it.startsWith(q) } -> MatchKind.WORD
            qc.length >= 4 && lc.contains(qc) -> MatchKind.CONTAINS
            else -> null
        }
    }

    /** 0 = no match, 100 = the same name. */
    fun score(query: String, label: String): Int = when (kind(query, label)) {
        MatchKind.EXACT -> 100
        MatchKind.PREFIX -> 80
        MatchKind.WORD -> 60
        MatchKind.CONTAINS -> 50
        null -> 0
    }

    /** The best match, or null if nothing matches well enough. Ties go to the shorter label. */
    fun match(query: String, apps: List<AppEntry>): AppMatch? = apps
        .mapNotNull { a -> kind(query, a.label)?.let { AppMatch(a, it) } }
        .sortedWith(compareBy<AppMatch> { it.kind.ordinal }.thenBy { it.app.label.length })
        .firstOrNull()

    fun best(query: String, apps: List<AppEntry>): AppEntry? = match(query, apps)?.app

    fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }
}

object ScreenText {
    /**
     * The text pieces of the screen (AssistStructure nodes, in order) as one block for the prompt: trimmed,
     * blank and repeated lines dropped, cut on a line boundary at [maxTokens] (by [TokenEstimate]: a screen of
     * numbers costs far more tokens per character than prose) or [maxChars]. Null when nothing is left.
     */
    fun build(pieces: List<String>, maxChars: Int = Limits.SCREEN_TEXT_CHARS, maxTokens: Int = Limits.SCREEN_TEXT_TOKENS): String? {
        val lines = mutableListOf<String>()
        val seen = HashSet<String>()
        for (p in pieces) {
            for (raw in sanitize(p).split('\n')) {
                val line = raw.replace(Regex("\\s+"), " ").trim()
                if (line.isEmpty() || !seen.add(line)) continue
                lines += line
            }
        }
        if (lines.isEmpty()) return null
        val sb = StringBuilder()
        var tokens = 0
        for (l in lines) {
            val add = if (sb.isEmpty()) l else "\n$l"
            val t = TokenEstimate.text(add)
            if (sb.length + add.length > maxChars || tokens + t > maxTokens) {
                if (sb.isEmpty()) sb.append(cutToTokens(l.take(maxChars - 1), maxTokens - 2))
                sb.append("\n…")
                break
            }
            sb.append(add)
            tokens += t
        }
        return sb.toString()
    }

    private fun cutToTokens(s: String, max: Int): String {
        var n = s.length
        while (n > 0 && TokenEstimate.text(s.take(n)) > max) n = n * 3 / 4
        return s.take(n)
    }

    /** Chat-template control tokens a page could contain to impersonate the conversation. */
    private val CONTROL = Regex("""<\|?/?(?:start|end)_[a-z_]*\|?>|<\|[a-z_]*\|?>|<[a-z_]*\|>|<escape>""", RegexOption.IGNORE_CASE)

    /**
     * Screen text as data: the prompt quotes it between triple double quotes, so no run of three or more double
     * quotes may survive (it would close the quote), and nothing that looks like a chat-template control token.
     */
    fun sanitize(s: String): String = s.replace(CONTROL, "").replace(Regex("\"{3,}"), "\"")
}

/** Light Markdown for chat bubbles: paragraphs, headings, bullet and numbered lists, code blocks; bold, italic, inline code. */
object Markdown {
    enum class BlockKind { PARAGRAPH, HEADING, BULLET, NUMBERED, CODE }
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)
    data class Block(val kind: BlockKind, val spans: List<Span>, val marker: String = "")

    fun parse(src: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotBlank()) out += Block(BlockKind.PARAGRAPH, inline(para.toString().trim()))
            para.setLength(0)
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            when {
                t.startsWith("```") -> {
                    flushPara()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i]); i++
                    }
                    out += Block(BlockKind.CODE, listOf(Span(code.toString(), code = true)))
                }
                t.isEmpty() -> flushPara()
                Regex("^#{1,6}\\s+").containsMatchIn(t) -> { flushPara(); out += Block(BlockKind.HEADING, inline(t.replace(Regex("^#{1,6}\\s+"), ""))) }
                Regex("^[-*+•]\\s+").containsMatchIn(t) -> { flushPara(); out += Block(BlockKind.BULLET, inline(t.replace(Regex("^[-*+•]\\s+"), "")), "•") }
                Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> {
                    flushPara()
                    val m = Regex("^(\\d+)[.)]\\s+").find(t)!!
                    out += Block(BlockKind.NUMBERED, inline(t.substring(m.range.last + 1)), m.groupValues[1] + ".")
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flushPara()
        return out
    }

    /** The text without its markers ("Text **Mom**" → "Text Mom"), for the model's copy of a card. */
    fun plain(s: String): String = inline(s).joinToString("") { it.text }

    /** **bold**, *italic* / _italic_, `code`. Unclosed markers stay as text (useful while a reply streams). */
    fun inline(s: String): List<Span> {
        val out = mutableListOf<Span>()
        val re = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\*(?!\s)(.+?)(?<!\s)\*|(?<![A-Za-z0-9])_(?!\s)(.+?)(?<!\s)_(?![A-Za-z0-9])""")
        var last = 0
        for (m in re.findAll(s)) {
            if (m.range.first > last) out += Span(s.substring(last, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> Span(g[1], bold = true)
                g[2].isNotEmpty() -> Span(g[2], bold = true)
                g[3].isNotEmpty() -> Span(g[3], code = true)
                g[4].isNotEmpty() -> Span(g[4], italic = true)
                else -> Span(g[5], italic = true)
            }
            last = m.range.last + 1
        }
        if (last < s.length) out += Span(s.substring(last))
        return out
    }
}

object BootLogic {
    /** Notify after a restart only if Robot was the assistant before and has not started in this boot. */
    fun shouldNotify(everReady: Boolean, readyThisBoot: Boolean): Boolean = everReady && !readyThisBoot
}
