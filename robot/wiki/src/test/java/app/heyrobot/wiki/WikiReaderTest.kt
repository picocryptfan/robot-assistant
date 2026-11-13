// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiReaderTest {
    private fun opened(z: FakeZim = FakeZim()) = WikiReader(z).also { assertTrue(it.open(3) != null) }

    @Test fun opensWithInfoAndCleanMetadata() {
        val i = WikiReader(FakeZim()).open(3)!!
        assertEquals(4L, i.articles)
        assertTrue(i.titleIndex && i.fullText)
        assertEquals("Wikipedia top", i.title) // the control character dropped
        assertEquals("eng", i.language)
        assertEquals("2026-09-01", i.date)
    }

    @Test fun noLibraryOrNoFileOpensNothing() {
        assertNull(WikiReader(FakeZim(available = false)).open(3))
        val r = WikiReader(FakeZim())
        assertNull(r.open(-1))
        assertFalse(r.isOpen)
        assertEquals(emptyList<WikiHit>(), r.titles("Louis"))
        assertNull(r.article("Louis_Riel"))
    }

    @Test fun findsLouisRielByTitleAndFullText() {
        val r = opened()
        assertEquals(listOf(WikiHit("Louis_Riel", "Louis Riel")), r.titles("Louis Riel"))
        assertEquals(listOf("Louis_Riel", "Manitoba"), r.search("Louis Riel").map { it.path })
    }

    @Test fun queriesAreCleanedBeforeTheLibrary() {
        val z = FakeZim()
        val r = opened(z)
        r.titles("  Louis\u0000​   Riel \n")
        assertEquals("Louis Riel", z.lastQuery)
        r.search("x".repeat(500))
        assertEquals(WikiReader.MAX_QUERY, z.lastQuery!!.length)
        z.lastQuery = null
        assertEquals(emptyList<WikiHit>(), r.titles(" \u0007 "))
        assertNull(z.lastQuery) // a blank query never reaches it
    }

    @Test fun hitsAreBoundedDedupedAndClean() {
        val z = FakeZim()
        val r = opened(z)
        z.raw = listOf("A", "First", "A", "Again", "", "Blank path", "B\nC", "Bad path", "Ville_de_Québec", "", "p".repeat(600), "Long")
        assertEquals(listOf(WikiHit("A", "First"), WikiHit("Ville_de_Québec", "Ville de Québec")), r.titles("q"))
        z.raw = List(60) { "P$it" }
        assertEquals(WikiReader.MAX_HITS, r.search("q", max = 99).size)
        assertEquals(1, r.search("q", max = 0).size)
        z.raw = listOf("odd")
        assertEquals(emptyList<WikiHit>(), r.search("q"))
    }

    @Test fun articleFollowsRedirectsAndIsCut() {
        val r = opened()
        val a = r.article("Riel")!!
        assertEquals("Louis_Riel", a.path)
        assertEquals("Louis Riel", a.title)
        assertTrue(a.html.startsWith("<p><b>Louis Riel</b>"))
        assertEquals("Montréal", r.article("Montréal")!!.title)
        assertNull(r.article("Nowhere"))
        assertNull(r.article(""))
        val big = "<p>" + "é".repeat(WikiReader.MAX_HTML_BYTES) + "</p>"
        val cut = opened(FakeZim(pages = mapOf("Big" to ("Big" to big)))).article("Big")!!
        assertTrue(cut.html.encodeToByteArray().size <= WikiReader.MAX_HTML_BYTES)
        assertFalse(cut.html.endsWith("�"))
    }

    @Test fun reopeningAndClosingReleaseTheHandle() {
        val z = FakeZim()
        val r = WikiReader(z)
        r.open(3)
        r.open(4)
        assertEquals(2, z.opened)
        assertEquals(1, z.closed)
        r.close()
        r.close()
        assertEquals(2, z.closed)
        assertFalse(r.isOpen)
    }
}
