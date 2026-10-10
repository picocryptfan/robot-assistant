package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure text helpers: matching an app name, preparing screen text, and light Markdown for chat bubbles.

/** A launchable app: its label and an opaque key (package/activity) the executor uses to start it. */
data class AppEntry(val label: String, val key: String)

object AppMatcher {
    private val FILLER = setOf("app", "the", "application", "my", "open")

    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
    }

    /** 0 = no match, 100 = the same name. */
    fun score(query: String, label: String): Int {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        val qc = q.replace(" ", "")
        val lc = l.replace(" ", "")
        return when {
            q == l || qc == lc -> 100
            lc.startsWith(qc) -> 80
            qc.startsWith(lc) && lc.length >= 3 -> 70
            l.split(' ').any { it == q || it.startsWith(q) } && q.length >= 3 -> 60
            lc.contains(qc) && qc.length >= 3 -> 50
            qc.length >= 4 && editDistance(qc, lc) <= (if (qc.length >= 8) 2 else 1) -> 40
            else -> 0
        }
    }

    /** The best match, or null if nothing is close enough. Ties go to the shorter label. */
    fun best(query: String, apps: List<AppEntry>): AppEntry? = apps
        .map { it to score(query, it.label) }
        .filter { it.second >= 40 }
        .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
        .firstOrNull()?.first

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
     * blank and repeated lines dropped, cut at [maxChars] on a line boundary. Null when nothing is left.
     */
    fun build(pieces: List<String>, maxChars: Int = Limits.SCREEN_TEXT_CHARS): String? {
        val lines = mutableListOf<String>()
        val seen = HashSet<String>()
        for (p in pieces) {
            for (raw in p.split('\n')) {
                val line = raw.replace(Regex("\\s+"), " ").trim()
                if (line.isEmpty() || !seen.add(line)) continue
                lines += line
            }
        }
        if (lines.isEmpty()) return null
        val sb = StringBuilder()
        for (l in lines) {
            val add = if (sb.isEmpty()) l else "\n$l"
            if (sb.length + add.length > maxChars) {
                if (sb.isEmpty()) sb.append(l.take(maxChars - 1))
                sb.append("\n…")
                break
            }
            sb.append(add)
        }
        return sb.toString()
    }
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
