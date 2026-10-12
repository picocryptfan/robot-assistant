package app.heyrobot.core.web

import java.nio.charset.Charset
import java.util.Locale

// Pure. A result page's readable text (design A7 "Extraction"), in plain Kotlin: :core takes no HTML library (0.9: the
// voice process's helpers module parses a page with jsoup, drops what [hidden] says, and runs its copy of Readability4J;
// the article it finds comes back here as HTML), so this is a small tokenizer that follows the HTML parsing rules where
// they decide what is hidden, plus a light main-content pick. No JavaScript, no CSS files, no images, no links followed.
//
// Dropped with everything inside: script, style, noscript, template, iframe, svg, math, form and its controls, nav,
// aside, footer, head, object, canvas, media, a dialog that isn't open, comments; any element with `hidden`,
// `aria-hidden="true"`, an inline style that hides it (display:none, visibility:hidden, opacity near 0, font-size 0,
// a zero width or height, clipped, pushed off screen, transparent or background-coloured text), a hiding utility class
// (hidden, d-none, sr-only, visually-hidden…), a navigation, banner, dialog or similar role, or a class or id that says
// cookie banner, consent, newsletter, share buttons, comments or the like. Text hidden by a stylesheet survives (no CSS
// is read): a known limit. Kept: headings, paragraphs, list items, quotes, preformatted text, table rows. Then:
// invisible Unicode removed (WebText's Invisible: zero-width, bidi controls, the tag block), whitespace collapsed,
// consent paragraphs dropped by phrase, link lists dropped, 40,000 characters at most; under 300 characters the page
// "needs JavaScript". Input: 1 MiB at most.

/** One block of a page's text. [level]: a heading's 1 to 6. */
data class PageBlock(val kind: Kind, val text: String, val level: Int = 0) {
    enum class Kind { HEADING, PARAGRAPH, ITEM, QUOTE, PRE, ROW }

    /** As text: "• " before a list item. */
    fun render(): String = if (kind == Kind.ITEM) "• $text" else text
}

/** A page's readable text: its [title] (from <title>), its [blocks]; [needsJavaScript] when too little text came; [truncated] when a cap cut it. */
data class PageContent(val title: String?, val blocks: List<PageBlock>, val needsJavaScript: Boolean, val truncated: Boolean) {
    val text: String get() = blocks.joinToString("\n\n") { it.render() }
    val chars: Int get() = blocks.sumOf { it.text.length }
}

object PageText {
    /** HTML read at most (characters; the fetcher stops at 1 MiB of bytes). */
    const val MAX_INPUT = 1 shl 20
    /** Text kept from a page at most. */
    const val MAX_TEXT = 40_000
    /** Less text than this: an app shell that needs JavaScript. */
    const val MIN_TEXT = 300

    private val MD_HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
    private val BR_BREAK = Regex("\\n\\s*\\n")
    private val SPACE = Regex("[ \\t\\r\\n\\f\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val BLANK_LINES = Regex("\\n\\s*\\n+")

    /**
     * The bytes of a page as text: the charset from [contentType], else a BOM, else a `<meta charset>` in the first
     * 1,024 bytes, else UTF-8. At most [MAX_INPUT] bytes are read.
     */
    fun decode(bytes: ByteArray, contentType: String?): String {
        val b = if (bytes.size > MAX_INPUT) bytes.copyOf(MAX_INPUT) else bytes
        val bom = when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            else -> null
        }
        if (bom != null) return String(b, bom.second, b.size - bom.second, bom.first)
        val named = charset(contentType) ?: charset(String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1).let(::metaCharset))
        return String(b, named ?: Charsets.UTF_8)
    }

    private fun charset(s: String?): Charset? {
        val name = s?.let { Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]{1,40})", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) } ?: return null
        return try { Charset.forName(name) } catch (_: Exception) { null }
    }

    private fun metaCharset(head: String): String? =
        Regex("<meta[^>]{0,200}charset\\s*=\\s*[\"']?[A-Za-z0-9._:-]{1,40}", RegexOption.IGNORE_CASE).find(head)?.value

    /** A page's HTML as its readable text. */
    fun html(html: String): PageContent {
        val truncatedInput = html.length > MAX_INPUT
        val walk = Walker(if (truncatedInput) html.substring(0, MAX_INPUT) else html).run()
        return finish(walk.title, walk.blocks, truncatedInput || walk.tooDeep, MIN_TEXT)
    }

    /** Plain text (a text/plain page, Wikipedia's intro, page text a service supplied): paragraphs at blank lines. */
    fun plain(text: String): PageContent {
        val t = if (text.length > MAX_INPUT) text.substring(0, MAX_INPUT) else text
        val blocks = t.split(BLANK_LINES).map { Raw(PageBlock.Kind.PARAGRAPH, 0, it, 0, main = false) }
        return finish(null, blocks, text.length > MAX_INPUT, minText = 1)
    }

    /** Text with paragraphs: invisible characters out, spaces collapsed inside each paragraph, one blank line between. */
    fun cleanParagraphs(text: String): String =
        Invisible.strip(text).split(BLANK_LINES).map { it.replace(SPACE, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            .let { SearchWire.cut(it, MAX_TEXT) }

    /**
     * Markdown a service returns as page text (Brave's LLM context, Kagi's extract) as plain paragraphs: links and images
     * become their words, heading and list marks and emphasis go, HTML entities are decoded.
     */
    fun markdownToText(md: String): String {
        val lines = Entities.decode(md).lines().map { line ->
            val heading = MD_HEADING.containsMatchIn(line)
            line.replace(Regex("!\\[([^\\]]{0,300})]\\([^)]{0,2000}\\)"), "")
                .replace(Regex("\\[([^\\]]{0,300})]\\([^)]{0,2000}\\)"), "$1")
                .replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
                .replace(Regex("^\\s*(?:[*+-]|\\d{1,3}[.)])\\s+"), "• ")
                .replace(Regex("(\\*\\*|__|\\*|`)"), "")
                .trim()
                .let { if (heading && it.isNotEmpty()) "§$it" else it }
        }
        // A heading or list item is its own paragraph.
        val out = StringBuilder()
        for (l in lines) {
            when {
                l.isEmpty() -> out.append("\n\n")
                l.startsWith("• ") -> out.append("\n\n").append(l).append("\n\n")
                l.startsWith("§") -> out.append("\n\n").append(l.substring(1)).append("\n\n")
                else -> out.append(l).append(' ')
            }
        }
        return cleanParagraphs(out.toString())
    }

    /** Short paragraphs that are a cookie or consent banner (English and French), whatever element held them. */
    private val CONSENT = listOf(
        "we use cookies", "this site uses cookies", "this website uses cookies", "uses cookies to", "accept all cookies", "accept cookies",
        "cookie policy", "cookie settings", "manage cookies", "manage your cookie", "your privacy choices", "consent to the use of cookies",
        "nous utilisons des cookies", "ce site utilise des cookies", "utilise des cookies", "accepter les cookies", "tout accepter",
        "gerer les cookies", "parametres des cookies", "politique de cookies", "politique relative aux cookies", "vos choix en matiere de confidentialite",
    )

    private fun consent(text: String): Boolean {
        if (text.length > 600) return false
        val f = WebWords.fold(text)
        return CONSENT.any { f.contains(it) }
    }

    /** A block as the walker found it, before cleaning. */
    internal class Raw(val kind: PageBlock.Kind, val level: Int, val text: String, val linkChars: Int, val main: Boolean)

    /** [minText]: less text than this is no page ([MIN_TEXT] for HTML: it needs JavaScript). */
    private fun finish(title: String?, raw: List<Raw>, truncatedInput: Boolean, minText: Int): PageContent {
        val cleaned = raw.mapNotNull { r ->
            val t = Invisible.strip(r.text).let { if (r.kind == PageBlock.Kind.PRE) it.lines().joinToString("\n") { l -> l.replace('\t', ' ').trimEnd() }.trim('\n') else it.replace(SPACE, " ").trim() }
            if (t.isEmpty() || consent(t)) return@mapNotNull null
            // A block that is mostly links (a menu that isn't in a <nav>, "related" lists) isn't the page's text.
            if (r.kind != PageBlock.Kind.HEADING && r.linkChars > 0 && r.linkChars >= t.length * 0.6 && t.length < 400) return@mapNotNull null
            Raw(r.kind, r.level, t, r.linkChars, r.main)
        }
        // <article>, <main> or role="main" with enough text: only that.
        val main = cleaned.filter { it.main }
        val chosen = if (main.sumOf { it.text.length } >= maxOf(minText, MIN_TEXT)) main else cleaned
        val blocks = ArrayList<PageBlock>()
        var total = 0
        var truncated = truncatedInput
        for (r in chosen) {
            if (total >= MAX_TEXT) { truncated = true; break }
            val room = MAX_TEXT - total
            val text = if (r.text.length > room) { truncated = true; SearchWire.cut(r.text, room) } else r.text
            blocks += PageBlock(r.kind, text, r.level)
            total += text.length
        }
        // Headings alone at the end say nothing.
        while (blocks.isNotEmpty() && blocks.last().kind == PageBlock.Kind.HEADING) blocks.removeAt(blocks.size - 1)
        val chars = blocks.sumOf { it.text.length }
        val cleanTitle = title?.let { Invisible.strip(it).replace(SPACE, " ").trim() }?.takeIf { it.isNotEmpty() }?.let { SearchWire.cut(it, SearchWire.TITLE_CHARS) }
        return PageContent(cleanTitle, if (chars < minText) emptyList() else blocks, needsJavaScript = chars < minText, truncated = truncated)
    }

    // ------------------------------------------------------------------ the walker

    /** Elements dropped with everything inside them. */
    private val DROP = setOf(
        "script", "style", "noscript", "template", "iframe", "svg", "math", "form", "nav", "aside", "footer", "head", "select", "option",
        "optgroup", "button", "textarea", "label", "object", "canvas", "video", "audio", "picture", "map", "noembed", "noframes", "frameset",
        "datalist", "menu", "dialog", "fieldset", "legend", "meter", "progress", "output", "slot",
    )
    /** Elements whose content is raw text up to their end tag (never parsed as markup). */
    private val RAW = setOf("script", "style", "noscript", "iframe", "noembed", "noframes", "xmp", "textarea", "title", "plaintext")
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "keygen", "image")
    /** Elements that start and end a block of text. */
    private val BLOCKS = setOf(
        "address", "article", "aside", "blockquote", "body", "caption", "center", "dd", "details", "dialog", "div", "dl", "dt", "fieldset",
        "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "html", "li", "main", "menu",
        "nav", "ol", "p", "pre", "section", "summary", "table", "tbody", "tfoot", "thead", "tr", "ul", "listing",
    )
    /** A start tag of these closes an open <p> (HTML's "close a p element"). */
    private val CLOSES_P = setOf(
        "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl", "fieldset", "figcaption", "figure",
        "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "listing", "main", "menu", "nav", "ol", "p", "pre",
        "section", "summary", "table", "ul", "xmp", "plaintext",
    )
    /** HTML's "special" elements: an end tag for something else never closes past one of these. */
    private val SPECIAL = BLOCKS + setOf("applet", "marquee", "object", "template", "td", "th", "caption", "button", "select", "textarea", "iframe", "li",
        "img", "input", "embed", "frameset", "noembed", "noframes", "noscript", "plaintext", "script", "style", "xmp", "head", "title")
    /** Scope boundaries: a block end tag doesn't reach past these. */
    private val SCOPE = setOf("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template", "svg", "math")
    /** What may stand in <head> without ending it. */
    private val HEAD_CONTENT = setOf("meta", "link", "title", "style", "script", "noscript", "base", "template", "head")
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val HIDING_CLASSES = setOf(
        "hidden", "d-none", "sr-only", "visually-hidden", "visuallyhidden", "screen-reader-text", "screen-reader-only", "invisible", "hide",
        "is-hidden", "u-hidden", "display-none", "offscreen", "element-invisible", "a11y-hidden", "hidden-xs", "hidden-sm", "sr-only-focusable",
    )
    private val HIDING_ROLES = setOf("navigation", "banner", "contentinfo", "complementary", "search", "dialog", "alertdialog", "menu", "menubar", "toolbar", "tooltip")
    /** A class or id with one of these in it: always dropped. */
    private val ALWAYS_UNLIKELY = listOf("consent", "gdpr", "onetrust", "didomi", "cookiebot", "cookielaw", "newsletter", "paywall")
    /** "cookie" with one of these is a cookie banner ("cookie-notice", "cookieBar"); "cookie" alone may be a recipe. */
    private val COOKIE_WITH = listOf("banner", "notice", "bar", "law", "popup", "dialog", "modal", "policy", "settings", "wall", "alert", "message",
        "disclaimer", "accept", "info")
    /** A class or id with one of these words: dropped unless it also says it holds the content. */
    private val UNLIKELY = setOf(
        "comment", "comments", "share", "sharing", "social", "sidebar", "advert", "advertisement", "ad", "ads", "sponsor", "sponsored", "promo",
        "popup", "modal", "breadcrumb", "breadcrumbs", "related", "disqus", "subscribe", "signup", "banner", "masthead", "skip", "toolbar",
        "pagination", "pager", "outbrain", "taboola", "widget",
    )
    private val MAYBE = setOf("article", "content", "main", "body", "post", "story", "entry", "text", "column", "page")

    private class Open(val name: String, val hidden: Boolean, val main: Boolean, val link: Boolean)

    /** Open elements at most: a page nested deeper is read only up to there (the rest is dropped, never shown). */
    const val MAX_DEPTH = 1024

    private class Walker(private val h: String) {
        private val stack = ArrayList<Open>()
        /** How many of each element are open, so "is a <p> open?" costs nothing on deep pages. */
        private val openCount = HashMap<String, Int>()
        private var hiddenDepth = 0
        private var mainDepth = 0
        private var linkDepth = 0
        private val buf = StringBuilder()
        private var bufLinkChars = 0
        private var bufMain = false
        val blocks = ArrayList<Raw>()
        var title: String? = null
        /** The page was too deeply nested to read to its end. */
        var tooDeep = false
        private var i = 0

        fun run(): Walker {
            while (i < h.length) {
                val lt = h.indexOf('<', i)
                if (lt < 0) { text(h.substring(i)); break }
                if (lt > i) text(h.substring(i, lt))
                i = lt
                tag()
            }
            flush()
            return this
        }

        private fun text(raw: String) {
            if (hiddenDepth > 0 || raw.isEmpty()) return
            val t = Entities.decode(raw)
            val pre = isOpen("pre") || isOpen("listing")
            val s = if (pre) t else t.replace(SPACE, " ")
            if (s.isBlank() && !pre) {
                if (buf.isNotEmpty() && buf.last() != ' ' && buf.last() != '\n') buf.append(' ')
                return
            }
            if (buf.isEmpty() || buf.last() == '\n') buf.append(s.trimStart()) else buf.append(s)
            if (linkDepth > 0) bufLinkChars += s.trim().length
            if (mainDepth > 0) bufMain = true
        }

        /** Ends the block of text gathered so far, of the kind the open elements give it. */
        private fun flush() {
            if (buf.isBlank()) { buf.setLength(0); bufLinkChars = 0; bufMain = false; return }
            var kind = PageBlock.Kind.PARAGRAPH
            var level = 0
            for (k in stack.indices.reversed()) {
                val n = stack[k].name
                when {
                    n in HEADINGS -> { kind = PageBlock.Kind.HEADING; level = n[1] - '0' }
                    n == "li" || n == "dt" || n == "dd" -> kind = PageBlock.Kind.ITEM
                    n == "blockquote" -> kind = PageBlock.Kind.QUOTE
                    n == "pre" || n == "listing" -> kind = PageBlock.Kind.PRE
                    n == "tr" -> kind = PageBlock.Kind.ROW
                    else -> continue
                }
                break
            }
            val text = buf.toString()
            if (kind == PageBlock.Kind.PRE) blocks += Raw(kind, level, text, bufLinkChars, bufMain)
            // Two <br> in a row end a paragraph; one is a space.
            else text.split(BR_BREAK).forEach { part -> blocks += Raw(kind, level, part.replace('\n', ' '), if (part.length == text.length) bufLinkChars else 0, bufMain) }
            buf.setLength(0)
            bufLinkChars = 0
            bufMain = false
        }

        private fun tag() {
            // At h[i] == '<'.
            when {
                h.startsWith("<!--", i) -> {
                    // A comment: "<!-->" and "<!--->" end at once; otherwise at "-->" (or "--!>"), or the end of the page.
                    val from = i + 4
                    i = when {
                        h.startsWith(">", from) -> from + 1
                        h.startsWith("->", from) -> from + 2
                        else -> listOf(h.indexOf("-->", from).let { if (it < 0) Int.MAX_VALUE else it + 3 }, h.indexOf("--!>", from).let { if (it < 0) Int.MAX_VALUE else it + 4 })
                            .minOrNull()!!.let { if (it == Int.MAX_VALUE) h.length else it }
                    }
                }
                h.startsWith("<!", i) || h.startsWith("<?", i) -> i = h.indexOf('>', i).let { if (it < 0) h.length else it + 1 }
                h.startsWith("</", i) -> {
                    val c = h.getOrNull(i + 2)
                    if (c == null || !c.isAsciiLetter()) {
                        // "</>" is nothing; "</ x>" a bogus comment.
                        i = if (c == '>') i + 3 else h.indexOf('>', i).let { if (it < 0) h.length else it + 1 }
                        return
                    }
                    val (name, end) = name(i + 2)
                    i = skipTag(end)
                    endTag(name)
                }
                h.getOrNull(i + 1)?.isAsciiLetter() == true -> startTag()
                else -> { text("<"); i++ }
            }
        }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        private fun name(from: Int): Pair<String, Int> {
            var j = from
            while (j < h.length && !h[j].isWhitespace() && h[j] != '/' && h[j] != '>') j++
            return h.substring(from, j).lowercase(Locale.ROOT) to j
        }

        /** Past the end of a tag whose attributes don't matter (quotes respected). */
        private fun skipTag(from: Int): Int {
            var j = from
            var quote: Char? = null
            while (j < h.length) {
                val c = h[j]
                if (quote != null) { if (c == quote) quote = null }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '>') return j + 1
                j++
            }
            return h.length
        }

        private fun startTag() {
            val (name, afterName) = name(i + 1)
            val attrs = HashMap<String, String>()
            var j = afterName
            var selfClosing = false
            var closed = false
            while (j < h.length) {
                val c = h[j]
                when {
                    c.isWhitespace() -> j++
                    c == '>' -> { j++; closed = true; break }
                    c == '/' -> { if (h.getOrNull(j + 1) == '>') { selfClosing = true; j += 2; closed = true; break } else j++ }
                    else -> {
                        val start = j
                        j++
                        while (j < h.length && !h[j].isWhitespace() && h[j] != '/' && h[j] != '>' && h[j] != '=') j++
                        val attr = h.substring(start, j).lowercase(Locale.ROOT)
                        while (j < h.length && h[j].isWhitespace()) j++
                        var value = ""
                        if (j < h.length && h[j] == '=') {
                            j++
                            while (j < h.length && h[j].isWhitespace()) j++
                            if (j < h.length && (h[j] == '"' || h[j] == '\'')) {
                                val q = h[j]
                                val close = h.indexOf(q, j + 1).let { if (it < 0) h.length else it }
                                value = h.substring(j + 1, close)
                                j = minOf(h.length, close + 1)
                            } else {
                                val s = j
                                while (j < h.length && !h[j].isWhitespace() && h[j] != '>') j++
                                value = h.substring(s, j)
                            }
                        }
                        if (attr !in attrs) attrs[attr] = Entities.decode(value)
                    }
                }
            }
            i = j
            // A tag cut off by the end of the page is no tag.
            if (!closed) { i = h.length; return }
            open(name, attrs, selfClosing)
        }

        private fun open(name: String, attrs: Map<String, String>, selfClosing: Boolean) {
            if (name == "br") {
                if (hiddenDepth == 0) {
                    if (buf.endsWith("\n")) { buf.append('\n'); flush() } else buf.append('\n')
                }
                return
            }
            if (name == "title" && title == null && !isOpen("svg") && !isOpen("math")) {
                title = Entities.decode(rawText("title"))
                return
            }
            // Implied ends (HTML's tree construction, the parts that decide what is hidden). Anything that belongs in the
            // body ends an open <head> (whose content is dropped).
            if (name !in HEAD_CONTENT && isOpen("head")) closeIfInScope("head", emptySet())
            if (name in CLOSES_P) closeIfInScope("p", boundary = SCOPE + "button")
            when (name) {
                "li" -> closeIfInScope("li", boundary = SCOPE + setOf("ul", "ol", "menu"))
                "dt", "dd" -> { closeIfInScope("dt", SCOPE + "dl"); closeIfInScope("dd", SCOPE + "dl") }
                "tr" -> closeIfInScope("tr", setOf("table", "tbody", "thead", "tfoot", "html", "template"))
                "td", "th" -> { closeIfInScope("td", setOf("tr", "table", "html", "template")); closeIfInScope("th", setOf("tr", "table", "html", "template")) }
                "tbody", "thead", "tfoot" -> closeIfInScope("tr", setOf("table", "html", "template"))
            }
            if (name in HEADINGS && stack.lastOrNull()?.name in HEADINGS) pop(stack.size - 1)
            if (name == "a" && isOpen("a")) closeIfInScope("a", SPECIAL)
            val hides = name in DROP && !(name == "dialog" && "open" in attrs) || hides(attrs)
            if (name in RAW) {
                // Its text is never markup: skipped whole (the title was taken above).
                rawText(name)
                if (name == "plaintext") i = h.length
                return
            }
            if (name in VOID) {
                if (name == "hr") flush()
                return
            }
            if (name in BLOCKS) flush()
            if ((name == "td" || name == "th") && buf.isNotBlank() && hiddenDepth == 0) buf.append(" · ")
            val main = name == "article" || name == "main" || attrs["role"]?.trim()?.lowercase(Locale.ROOT) == "main"
            // "<div/>" doesn't close a div in HTML (only void elements and foreign content self-close): it stays open. An
            // "<svg/>" icon, or "<path/>" inside one, closes at once.
            if (selfClosing && (name == "svg" || name == "math" || isOpen("svg") || isOpen("math"))) return
            if (stack.size >= MAX_DEPTH) { tooDeep = true; i = h.length; return }
            stack += Open(name, hides, main, name == "a")
            openCount[name] = (openCount[name] ?: 0) + 1
            if (hides) hiddenDepth++
            if (main) mainDepth++
            if (name == "a") linkDepth++
        }

        /** The raw text of [name] up to its end tag (case-insensitive), consumed. */
        private fun rawText(name: String): String {
            var j = i
            while (true) {
                val k = h.indexOf("</", j)
                if (k < 0) { val t = h.substring(i); i = h.length; return t }
                if (h.regionMatches(k + 2, name, 0, name.length, ignoreCase = true)) {
                    val after = h.getOrNull(k + 2 + name.length)
                    if (after == null || after.isWhitespace() || after == '/' || after == '>') {
                        val t = h.substring(i, k)
                        i = skipTag(k + 2 + name.length)
                        return t
                    }
                }
                j = k + 2
            }
        }

        private fun endTag(name: String) {
            when (name) {
                "br" -> { open("br", emptyMap(), false); return }
                "html", "body" -> return
            }
            if (!isOpen(name)) return
            // A block end tag closes it and everything open inside it, unless a scope boundary is in the way.
            // Any other end tag closes the nearest such element only if no special element is open inside it
            // (HTML's "any other end tag"; a formatting end tag across a block is ignored, which keeps a hidden block closed
            // only where a browser would close it).
            val boundary = if (name in SPECIAL) SCOPE else SPECIAL
            for (k in stack.indices.reversed()) {
                val n = stack[k].name
                if (n == name) { pop(k); return }
                if (n in boundary && !(name in setOf("td", "th", "tr", "tbody", "thead", "tfoot", "table", "caption") && n in setOf("td", "th", "tr", "tbody", "thead", "tfoot"))) return
            }
        }

        private fun isOpen(name: String) = (openCount[name] ?: 0) > 0

        private fun closeIfInScope(name: String, boundary: Set<String>) {
            if (!isOpen(name)) return
            for (k in stack.indices.reversed()) {
                val n = stack[k].name
                if (n == name) { pop(k); return }
                if (n in boundary) return
            }
        }

        /** Closes the element at [k] and everything opened after it. */
        private fun pop(k: Int) {
            val blockish = (k until stack.size).any { stack[it].name in BLOCKS }
            if (blockish) flush()
            while (stack.size > k) {
                val o = stack.removeAt(stack.size - 1)
                openCount[o.name] = (openCount[o.name] ?: 1) - 1
                if (o.hidden) hiddenDepth--
                if (o.main) mainDepth--
                if (o.link) linkDepth--
            }
        }
    }

    /**
     * The element [name] with [attrs] is dropped with everything inside it, by the same rules as this file's walker: for
     * a parser that builds the tree itself (0.9: the voice process's jsoup, before Readability runs). [attrs] by
     * lower-case name.
     */
    fun hidden(name: String, attrs: Map<String, String>): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return (n in DROP && !(n == "dialog" && "open" in attrs)) || n in RAW && n != "title" || hides(attrs)
    }

    /** The attributes hide the element (see the file's header). */
    internal fun hides(attrs: Map<String, String>): Boolean {
        if ("hidden" in attrs) return true
        if (attrs["aria-hidden"]?.trim()?.lowercase(Locale.ROOT) == "true") return true
        attrs["style"]?.let { if (styleHides(it)) return true }
        attrs["role"]?.trim()?.lowercase(Locale.ROOT)?.let { if (it in HIDING_ROLES) return true }
        val classes = attrs["class"]?.lowercase(Locale.ROOT)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        if (classes.any { it in HIDING_CLASSES }) return true
        val names = (classes + listOfNotNull(attrs["id"]?.lowercase(Locale.ROOT))).joinToString(" ")
        if (names.isEmpty()) return false
        if (ALWAYS_UNLIKELY.any { names.contains(it) }) return true
        if (names.contains("cookie") && COOKIE_WITH.any { names.contains(it) }) return true
        val words = names.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        return words.any { it in UNLIKELY } && words.none { it in MAYBE }
    }

    private val LENGTH = Regex("^(-?[0-9]*\\.?[0-9]+)([a-z%]*)$")

    /** An inline style that hides the element's text. */
    internal fun styleHides(style: String): Boolean {
        val decl = HashMap<String, String>()
        for (d in style.lowercase(Locale.ROOT).replace("!important", "").split(';')) {
            val colon = d.indexOf(':')
            if (colon <= 0) continue
            decl[d.substring(0, colon).trim()] = d.substring(colon + 1).trim().replace(Regex("\\s+"), " ")
        }
        fun num(v: String?): Pair<Double, String>? = v?.let { LENGTH.find(it.trim())?.let { m -> m.groupValues[1].toDouble() to m.groupValues[2] } }
        fun zero(v: String?) = num(v)?.first == 0.0
        val display = decl["display"]
        if (display == "none") return true
        if (decl["visibility"] == "hidden" || decl["visibility"] == "collapse") return true
        decl["opacity"]?.let { o -> num(o)?.let { (n, unit) -> if ((if (unit == "%") n / 100 else n) < 0.1) return true } }
        decl["font-size"]?.let { f -> num(f)?.let { (n, unit) -> if (n <= 0.0 || (unit == "px" && n < 3) || (unit == "pt" && n < 2) || ((unit == "em" || unit == "rem") && n < 0.2)) return true } }
        decl["font"]?.let { if (it.startsWith("0 ") || it.startsWith("0/") || it.startsWith("0px")) return true }
        if (listOf("width", "height", "max-width", "max-height").any { zero(decl[it]) }) return true
        decl["clip"]?.let { if (it.replace(" ", "").let { c -> c.startsWith("rect(0") || c.startsWith("rect(1px,1px,1px,1px") }) return true }
        decl["clip-path"]?.let { if (it.contains("inset(50%") || it.contains("inset(100%") || it.contains("circle(0")) return true }
        decl["transform"]?.let { if (it.contains("scale(0)") || it.contains("scale(0,") || it.contains("scalex(0)") || it.contains("scaley(0)")) return true }
        val positioned = decl["position"] == "absolute" || decl["position"] == "fixed"
        if (positioned && listOf("left", "top", "right", "bottom").any { num(decl[it])?.let { (n, unit) -> n <= -999 && unit != "%" } == true }) return true
        num(decl["text-indent"])?.let { (n, _) -> if (n <= -999) return true }
        val color = decl["color"]
        if (color == "transparent" || color == "rgba(0,0,0,0)" || color == "rgba(0, 0, 0, 0)") return true
        val background = decl["background-color"] ?: decl["background"]?.takeIf { !it.contains(' ') }
        if (color != null && background != null && color == background) return true
        return false
    }
}
