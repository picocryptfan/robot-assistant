// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * wiki-article-pick: the articles the starters' own indexes gave on the Pixel 8 (1.4.0-rc3, wiki-p5-measure), as
 * libzim gives them for the pinned files: the title index's suggestions are redirects, full text gives articles.
 */
class WikiArticlePickTest {
    private val now = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneId.of("America/Halifax"))

    /** A copy whose title hits may be redirects: [redirects] maps a hit's title to the article it leads to. */
    private class Zim(override val lang: String, val suggest: List<String>, val full: List<String>, val redirects: Map<String, String>,
        val text: Map<String, String>) : WikiCopy {
        override val date: String? = "2026-09-14"
        private fun path(t: String) = t.replace(' ', '_')
        override fun titles(query: String, max: Int) = suggest.take(max).map { WikiCopy.Hit(path(it), it) }
        override fun search(query: String, max: Int) = full.take(max).map { WikiCopy.Hit(path(it), it) }
        override fun article(path: String): WikiCopy.Page? {
            val title = (suggest + full + redirects.values).firstOrNull { path(it) == path } ?: return null
            val to = redirects[title] ?: title
            return WikiCopy.Page(path(to), to, "<p>${text[to] ?: "$to is an article."}</p>")
        }
    }

    // The English starter's suggestions for "Halifax", in its order: all four are redirects.
    private val halifax = Zim("en", listOf("Halifax Disaster", "Halifax NS", "Halifax, England", "Edward Halifax"),
        listOf("Halifax, Nova Scotia", "Halifax, West Yorkshire", "Halifax Explosion"),
        mapOf("Halifax Disaster" to "Halifax Explosion", "Halifax NS" to "Halifax, Nova Scotia", "Halifax, England" to "Halifax, West Yorkshire",
            "Edward Halifax" to "Edward Wood, 1st Earl of Halifax"),
        mapOf("Halifax, Nova Scotia" to "Halifax is the capital of the Canadian province of Nova Scotia.",
            "Halifax, West Yorkshire" to "Halifax is a town in the Metropolitan Borough of Calderdale, in West Yorkshire, England."))

    @Test fun `a redirect is judged by the article it leads to, so Halifax is the city in Nova Scotia`() {
        val (hits, byTitle) = WikiAnswer.find("Halifax", halifax)
        assertEquals(true, byTitle)
        assertEquals(listOf("Halifax, Nova Scotia", "Halifax, West Yorkshire"), hits.map { it.title })
        assertEquals("Halifax, Nova Scotia", WikiAnswer.answer("Where is Halifax?", listOf(halifax), now)!!.articles.first().title)
    }

    @Test fun `two redirects to one article take one place`() {
        val z = Zim("en", listOf("Halifax NS", "Halifax, N.S.", "Halifax Disaster"), emptyList(),
            mapOf("Halifax NS" to "Halifax, Nova Scotia", "Halifax, N.S." to "Halifax, Nova Scotia", "Halifax Disaster" to "Halifax Explosion"), emptyMap())
        assertEquals(listOf("Halifax, Nova Scotia", "Halifax Explosion"), WikiAnswer.find("Halifax", z).first.map { it.title })
    }

    @Test fun `an exact redirect keeps its exactness when it shares an article with another hit`() {
        // L1 (review-wiki-which-pick): "Halifax NS" comes first and isn't exact; "Halifax, N.S." to the same article is.
        val z = Zim("en", listOf("Halifax NS", "Halifax, England", "Halifax, N.S."), emptyList(),
            mapOf("Halifax NS" to "Halifax Regional Municipality", "Halifax, England" to "Halifax, West Yorkshire",
                "Halifax, N.S." to "Halifax Regional Municipality"), emptyMap())
        assertEquals(listOf("Halifax Regional Municipality", "Halifax, West Yorkshire"), WikiAnswer.find("Halifax", z).first.map { it.title })
    }

    // The French starter: no title fits the clause, and full text's first article shares only its bracket's word.
    private val planets = Zim("fr", emptyList(), listOf("Vénus (planète)", "Satellite naturel", "Fusée (astronautique)"), emptyMap(),
        mapOf("Vénus (planète)" to "Vénus est la sixième plus grosse planète du Système solaire par masse et par diamètre."))

    @Test fun `a word shared only in a title's bracket finds nothing, so the largest planet isn't Venus`() {
        assertEquals(emptyList<WikiCopy.Hit>(), WikiAnswer.find("planète est la plus grosse du système solaire", planets).first)
        assertNull(WikiAnswer.answer("Quelle planète est la plus grosse du système solaire?", listOf(planets), now))
    }
}
