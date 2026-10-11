package app.heyrobot.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page's readable text (design A7 "Extraction"): hidden text, scripts, styles, forms, navigation and invisible Unicode
 * never reach Gemma; headings, paragraphs and lists do; caps and the "needs JavaScript" rule hold.
 */
class PageTextTest {
    private fun sample(name: String) = javaClass.classLoader!!.getResourceAsStream("web/pages/$name")!!.readBytes().toString(Charsets.UTF_8)

    /** Enough text around [html] for the page to count (300 characters). */
    private fun page(html: String) = PageText.html("<p>${FILLER}</p>$html")

    private fun text(html: String) = page(html).text

    private val FILLER = "Descaling keeps an espresso machine working: scale from hard water narrows the pipes, slows the pump and " +
        "makes the coffee taste flat. Most makers suggest running a descale cycle every two or three months, or sooner with very hard water, " +
        "and always with a cleaner the maker approves. Skipping it for a year can block the machine for good."

    @Test fun aHostileArticleKeepsOnlyItsVisibleText() {
        val p = PageText.html(sample("hostile_article.html"))
        val t = p.text
        assertFalse(t, t.contains("SECRET"))
        assertFalse(t, t.contains("Ignore previous instructions"))
        assertFalse(t, t.contains("cookies"))
        assertFalse(t, t.contains("Home") || t.contains("Guides"))
        assertEquals("How to descale a Breville Barista Express – Coffee Notes", p.title)
        assertFalse(p.needsJavaScript)
        assertEquals(PageBlock(PageBlock.Kind.HEADING, "How to descale a Breville Barista Express", 1), p.blocks[0])
        assertEquals("By Sam Writer · 2 March 2026", p.blocks[1].text)
        assertTrue(t, t.contains("An empty comment ends at once, so this paragraph about descaling stays."))
        val items = p.blocks.filter { it.kind == PageBlock.Kind.ITEM }.map { it.text }
        assertEquals(listOf(
            "Breville descaling powder, or citric acid",
            "A container that holds at least 2 litres",
            "Empty the drip tray and fill the tank with the solution.",
            "Press and hold the 1 CUP and 2 CUP buttons together for 5 seconds.",
            "Turn the dial to hot water, then to steam, and let the tank run empty.",
        ), items)
        assertEquals(listOf("What you need", "Steps"), p.blocks.filter { it.kind == PageBlock.Kind.HEADING && it.level == 2 }.map { it.text })
        assertTrue(t, t.contains("Rinse the tank and repeat with clean water. Zerowidth characters and a bidi override txet vanish, and so does an invisible tag sentence."))
        assertTrue(t, t.contains("Raw invisible characters too: softhyphenated, joinedword, and a tag run right here."))
        assertFalse(t.any { Invisible.isInvisible(it.code) || Character.isSurrogate(it) })
        assertTrue(t.contains("• Breville descaling powder"))
    }

    @Test fun aJavaScriptAppShellNeedsJavaScript() {
        val p = PageText.html(sample("js_app_shell.html"))
        assertTrue(p.needsJavaScript)
        assertTrue(p.blocks.isEmpty())
        assertEquals("Shop", p.title)
        assertTrue(PageText.html("").needsJavaScript)
        assertTrue(PageText.html("<p>Too short.</p>").needsJavaScript)
    }

    @Test fun hiddenBlocksCloseWhereABrowserClosesThem() {
        // A block start closes an open <p>: "shown" is outside the hidden paragraph.
        assertTrue(text("<p hidden>secret<div>shown here</div>").contains("shown here"))
        assertFalse(text("<p hidden>secret<div>shown here</div>").contains("secret"))
        // A formatting end tag doesn't close the hidden block opened inside it.
        val b = text("<b><p hidden>secret one</b>secret two</p>after")
        assertFalse(b, b.contains("secret"))
        assertTrue(b, b.contains("after"))
        // A stray end tag doesn't escape a hidden span.
        assertFalse(text("<span style=\"display:none\">x</div>secret</span>").contains("secret"))
        assertFalse(text("<div hidden><p>secret a<p>secret b</div>after").contains("secret"))
        assertTrue(text("<div hidden><p>secret a<p>secret b</div>after").contains("after"))
        // "<div/>" doesn't self-close: what follows is inside the hidden div.
        assertFalse(text("<div hidden/>secret</div>after").contains("secret"))
        // A hidden element never closed hides the rest of the page.
        assertFalse(text("<div style='display:none'>secret <p>more secret").contains("secret"))
        // Nested hidden inside visible, visible after.
        assertEquals(FILLER + "\n\nbefore after", text("<div>before <span hidden>secret</span>after</div>"))
    }

    @Test fun rawTextElementsAreSkippedWhole() {
        val t = text("<script>var s = \"</div><p>secret</p>\";</script><SCRIPT type=x>secret</SCRIPT ><style>p::after{content:'secret'}</style>" +
            "<textarea><p>secret</p></textarea><xmp><b>secret</b></xmp><p>visible</p>")
        assertFalse(t, t.contains("secret"))
        assertTrue(t, t.contains("visible"))
        // An unclosed script hides the rest.
        assertFalse(text("<script>secret <p>more").contains("secret"))
        // <plaintext> never ends.
        assertFalse(text("<plaintext><p>secret</p>").contains("secret"))
    }

    @Test fun commentsAndOddMarkup() {
        assertTrue(text("<p>a<!-- secret --> b</p>").endsWith("a b"))
        assertTrue(text("<p>a<!-- secret --!> b</p>").endsWith("a b"))
        assertFalse(text("<p>a<!-- secret never closed <p>b").contains("secret"))
        assertTrue(text("<p>a<?php echo 'secret'; ?> b</p>").endsWith("a b"))
        assertTrue(text("<p>1 < 2 and 3 > 2</p>").endsWith("1 < 2 and 3 > 2"))
        assertTrue(text("<p>&lt;script&gt;alert(1)&lt;/script&gt; is text</p>").endsWith("<script>alert(1)</script> is text"))
        assertTrue(text("<p>Caf&eacute; &amp; cr&egrave;me &#233;t&#xE9; &amp</p>").endsWith("Café & crème été &"))
        assertTrue(text("<p title='a > b'>quoted > in attributes</p>").endsWith("quoted > in attributes"))
        // A tag cut off by the end of the page is no tag.
        assertTrue(text("<p>end</p><div class=\"x").endsWith("end"))
    }

    @Test fun structureIsKept() {
        val p = page("<h2>Prices</h2><table><tr><th>Model</th><th>Price</th></tr><tr><td>Barista Express</td><td>$699</td></tr></table>" +
            "<pre>line one\n  line two</pre><blockquote>A quote.</blockquote><p>One<br>line<br><br>Two</p><dl><dt>Term</dt><dd>Meaning</dd></dl>")
        val kinds = p.blocks.drop(1).map { it.kind to it.text }
        assertEquals(listOf(
            PageBlock.Kind.HEADING to "Prices",
            PageBlock.Kind.ROW to "Model · Price",
            PageBlock.Kind.ROW to "Barista Express · \$699",
            PageBlock.Kind.PRE to "line one\n  line two",
            PageBlock.Kind.QUOTE to "A quote.",
            PageBlock.Kind.PARAGRAPH to "One line",
            PageBlock.Kind.PARAGRAPH to "Two",
            PageBlock.Kind.ITEM to "Term",
            PageBlock.Kind.ITEM to "Meaning",
        ), kinds)
    }

    @Test fun linkListsAndConsentParagraphsAreDropped() {
        val t = text("<div><a href=/a>Sports</a> <a href=/b>Politics</a> <a href=/c>Weather</a></div>" +
            "<p>This website uses cookies to ensure you get the best experience. Learn more.</p><p>Nous utilisons des cookies pour améliorer le site.</p>" +
            "<p>A sentence with <a href=/x>one link</a> in the middle of much longer ordinary prose that stays.</p>")
        assertFalse(t, t.contains("Sports"))
        assertFalse(t, t.contains("cookies"))
        assertTrue(t, t.contains("A sentence with one link in the middle"))
        // A recipe for cookies isn't a cookie banner.
        assertTrue(text("<div class=\"cookie-recipe\"><p>Cream the butter and sugar.</p></div>").contains("Cream the butter"))
    }

    @Test fun mainContentIsPreferred() {
        val long = "This is the article itself, long enough to stand on its own as the page's main text. ".repeat(5)
        val p = PageText.html("<div class=\"promo-strip\"><p>Outside text that a reader skips.</p></div><div><p>Also outside the article.</p></div>" +
            "<article><p>$long</p></article>")
        assertEquals(1, p.blocks.size)
        assertEquals(long.trim(), p.blocks[0].text)
        // Too little inside <main>: the whole page.
        val q = PageText.html("<main><p>Short.</p></main><p>$long</p>")
        assertEquals(2, q.blocks.size)
    }

    @Test fun headIsClosedByBodyContent() {
        assertTrue(PageText.html("<html><head><title>T</title><p>$FILLER</p>").text.startsWith("Descaling keeps"))
        assertEquals("T", PageText.html("<html><head><title>T</title><p>$FILLER</p>").title)
    }

    @Test fun dialogsShowOnlyWhenOpen() {
        assertTrue(text("<dialog open><p>An open dialog is on screen.</p></dialog>").contains("An open dialog"))
        assertFalse(text("<dialog><p>secret</p></dialog>").contains("secret"))
    }

    @Test fun capsHold() {
        val big = "<p>" + "Descaling keeps an espresso machine working well. ".repeat(30_000) + "</p>"
        assertTrue(big.length > PageText.MAX_INPUT)
        val p = PageText.html(big)
        assertTrue(p.truncated)
        assertTrue(p.chars <= PageText.MAX_TEXT)
        val many = (1..2_000).joinToString("") { "<p>Paragraph number $it says something about descaling the machine.</p>" }
        val m = PageText.html(many)
        assertTrue(m.truncated)
        assertTrue(m.chars <= PageText.MAX_TEXT)
        assertTrue(m.text.startsWith("Paragraph number 1 says"))
        // Deeply nested markup is read to a depth, then dropped (and quickly).
        val deep = "<p>$FILLER</p>" + "<div>".repeat(200_000) + "<p>secret</p>"
        val start = System.nanoTime()
        val d = PageText.html(deep)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        assertTrue(d.truncated)
        assertEquals(FILLER, d.text)
        val legit = "<div>".repeat(50) + "<p>$FILLER</p>" + "</div>".repeat(50)
        assertFalse(PageText.html(legit).truncated)
    }

    @Test fun inlineStylesThatHide() {
        for (s in listOf("display:none", "display: none !important", "visibility:hidden", "visibility: collapse", "opacity:0", "opacity:.05",
            "opacity: 5%", "font-size:0", "font-size: 0.1em", "font-size:2px", "font: 0/0 a", "width:0", "height:0px", "max-height: 0",
            "position:absolute; left:-9999px", "position: fixed; top: -2000px", "text-indent:-9999em", "clip: rect(0,0,0,0)",
            "clip-path: inset(50%)", "transform: scale(0)", "color: transparent", "color:#fff;background-color:#fff", "color:red;background:red")) {
            assertTrue(s, PageText.styleHides(s))
        }
        for (s in listOf("display:block", "opacity:0.9", "font-size:16px", "width:100%", "height:auto", "position:absolute;left:10px",
            "color:#333;background:#fff", "margin-left:-20px", "")) {
            assertFalse(s, PageText.styleHides(s))
        }
    }

    @Test fun attributesThatHide() {
        assertTrue(PageText.hides(mapOf("hidden" to "")))
        assertTrue(PageText.hides(mapOf("aria-hidden" to " TRUE ")))
        assertFalse(PageText.hides(mapOf("aria-hidden" to "false")))
        assertTrue(PageText.hides(mapOf("class" to "content sr-only")))
        assertTrue(PageText.hides(mapOf("role" to "navigation")))
        assertTrue(PageText.hides(mapOf("id" to "onetrust-banner-sdk")))
        assertTrue(PageText.hides(mapOf("class" to "gdpr-wall")))
        assertTrue(PageText.hides(mapOf("class" to "comments-area")))
        assertFalse(PageText.hides(mapOf("class" to "comment-content article-body")))
        assertTrue(PageText.hides(mapOf("class" to "CookieBar")))
        assertFalse(PageText.hides(mapOf("class" to "cookie-recipe")))
        assertFalse(PageText.hides(mapOf("class" to "entry-content", "id" to "post-1")))
        assertFalse(PageText.hides(emptyMap()))
    }

    @Test fun bytesAreDecodedWithTheirCharset() {
        val latin = "<p>Café crème</p>".toByteArray(Charsets.ISO_8859_1)
        assertEquals("<p>Café crème</p>", PageText.decode(latin, "text/html; charset=ISO-8859-1"))
        assertEquals("<meta charset=\"windows-1252\"><p>Café</p>", PageText.decode("<meta charset=\"windows-1252\"><p>Café</p>".toByteArray(charset("windows-1252")), "text/html"))
        assertEquals("<p>été</p>", PageText.decode("<p>été</p>".toByteArray(Charsets.UTF_8), null))
        assertEquals("<p>été</p>", PageText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<p>été</p>".toByteArray(Charsets.UTF_16LE), "text/html; charset=utf-8"))
        assertEquals("<p>x</p>", PageText.decode("<p>x</p>".toByteArray(), "text/html; charset=no-such-charset"))
        assertEquals(PageText.MAX_INPUT, PageText.decode(ByteArray(PageText.MAX_INPUT + 10) { 'a'.code.toByte() }, null).length)
    }

    @Test fun plainTextAndMarkdown() {
        val p = PageText.plain("First paragraph.\n\n\nSecond​ paragraph,\n wrapped.")
        assertEquals(listOf("First paragraph.", "Second paragraph, wrapped."), p.blocks.map { it.text })
        assertFalse(p.needsJavaScript)
        assertNull(p.title)
        assertEquals("Heading\n\nSome bold text and a link.\n\n• one\n\n• two",
            PageText.markdownToText("## Heading\nSome **bold** text and [a link](https://x.example).\n* one\n* two\n![img](https://x.example/i.png)"))
    }
}
