// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.web

import app.heyrobot.helpers.web.readability.Readability4J
import app.heyrobot.helpers.web.readability.model.ReadabilityOptions
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design A14 "Readability (vendored)": a subset of Mozilla's test pages, Robot's copy of Readability4J on jsoup 1.23.2
 * giving the same article text as the 1.0.8 library on jsoup 1.11.2 (its expected.html), within whitespace. (Of the 19
 * pages tried, 16 matched; these ten are kept small.)
 */
class ReadabilityPagesTest {
    private val dir = File(javaClass.getResource("/web/readability")!!.toURI())

    private fun text(html: String) = Jsoup.parse(html).text().replace(Regex("\\s+"), " ").trim()

    @Test fun theArticleTextIsTheLibrarysOnEveryPage() {
        val pages = dir.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        assertEquals(10, pages.size)
        for (p in pages) {
            val a = Readability4J("http://fakehost/test/page.html", File(p, "source.html").readText(),
                ReadabilityOptions(additionalClassesToPreserve = listOf("caption"))).parse()
            val want = text(File(p, "expected.html").readText())
            assertTrue(p.name, want.length > 100)
            assertEquals(p.name, want, text(a.content ?: ""))
        }
    }
}
