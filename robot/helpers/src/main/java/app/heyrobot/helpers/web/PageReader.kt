// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.web

import app.heyrobot.core.web.PageContent
import app.heyrobot.core.web.PageText
import app.heyrobot.core.web.PageType
import app.heyrobot.helpers.web.readability.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import java.util.Locale

// VOICE PROCESS ONLY, :helpers module (0.9 web search, design A7 "Extraction"). A fetched page's readable text: jsoup
// parses it (no JavaScript, nothing loaded), every element core PageText says is hidden goes first (scripts, styles,
// forms, navigation, footers, anything hidden by an attribute or an inline style, cookie banners…), then Robot's copy
// of Readability4J (Firefox's Reader View) finds the article, and core PageText turns that into paragraphs, dropping
// invisible Unicode, consent paragraphs and link lists, at most 40,000 characters. A page Readability can't make sense
// of is read whole by PageText the same way; under 300 characters it "needs JavaScript".

object PageReader {
    /** Elements a page may hold at most for Readability (a bigger page is read by PageText alone). */
    const val MAX_ELEMENTS = 20_000

    fun read(page: PageFetcher.Page): PageContent {
        val text = PageText.decode(page.bytes, page.contentType)
        return when (page.kind) {
            PageType.Kind.TEXT -> PageText.plain(text)
            PageType.Kind.HTML -> html(text, page.url.toString())
        }
    }

    /** [html] from [url] as its readable text. */
    fun html(html: String, url: String): PageContent {
        val doc = try { Jsoup.parse(html, url) } catch (_: Throwable) { return PageText.html(html) }
        strip(doc)
        val whole = PageText.html(doc.outerHtml())
        if (doc.getAllElements().size > MAX_ELEMENTS) return whole
        val article = try {
            Readability4J(url, doc).parse().articleContent
        } catch (_: Throwable) {
            null
        } ?: return whole
        val title = doc.title().takeIf { it.isNotBlank() }
        val read = PageText.html("<html><head>" + (title?.let { "<title>" + escape(it) + "</title>" } ?: "") + "</head><body><article>" + article.html() +
            "</article></body></html>")
        // Readability's pick must say something: otherwise the whole page (already cleaned of what hides) is read.
        return if (read.needsJavaScript || read.chars < whole.chars / 10) whole.copy(title = whole.title ?: read.title) else read
    }

    /** Removes what never reaches Gemma: every element core PageText hides, and comments. */
    fun strip(doc: Document) {
        val gone = ArrayList<Node>()
        for (e in doc.getAllElements()) {
            // The document and its html, head (whose scripts and styles go one by one; the title stays) and body.
            if (e === doc || e.tagName() in KEEP) continue
            if (PageText.hidden(e.tagName(), attrs(e))) gone += e
        }
        // Removing an outer element removes what is inside it too.
        gone.forEach { if (it.parent() != null) it.remove() }
        comments(doc).forEach { it.remove() }
    }

    private fun attrs(e: Element): Map<String, String> = e.attributes().associate { it.key.lowercase(Locale.ROOT) to it.value }

    private val KEEP = setOf("#root", "html", "head", "body", "title")

    /** Every comment under [root], walked without recursion (a page may nest deeply). */
    private fun comments(root: Node): List<Comment> {
        val out = ArrayList<Comment>()
        val todo = ArrayDeque<Node>()
        todo.add(root)
        while (todo.isNotEmpty()) {
            val n = todo.removeLast()
            if (n is Comment) out += n else n.childNodes().forEach { todo.add(it) }
        }
        return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
