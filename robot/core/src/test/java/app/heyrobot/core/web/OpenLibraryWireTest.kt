package app.heyrobot.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book lookups (design A2.2, A5; off by default): Open Library's search, keyless, as SearchResults the card credits. */
class OpenLibraryWireTest {
    private fun res(name: String) = javaClass.getResource("/web/$name")!!.readText()

    @Test fun theRequestCarriesTheWordsAndTheUserAgentOnly() {
        val r = OpenLibraryWire.request("Ursula K. Le Guin", WebUserAgent.api("0.9.0"))
        assertEquals("https://openlibrary.org/search.json?q=Ursula%20K.%20Le%20Guin&limit=5&fields=key,title,author_name,first_publish_year", r.url)
        assertEquals("Robot/0.9.0 (app.heyrobot; Android voice assistant)", r.header("User-Agent"))
        assertEquals("SearchRequest(GET openlibrary.org)", r.toString())
    }

    @Test fun worksBecomeResultsWithTheirAuthorsAndYear() {
        val a = OpenLibraryWire.parse(200, res("openlibrary_search.json")) as SearchAnswer.Results
        assertEquals(listOf("A Wizard of Earthsea", "The Left Hand of Darkness", "The Dispossessed & more"), a.results.map { it.title })
        assertEquals("https://openlibrary.org/works/OL59863W", a.results[0].url)
        assertEquals("by Ursula K. Le Guin · first published 1968", a.results[0].snippet)
        assertEquals("by Ursula K. Le Guin, Someone Else", a.results[2].snippet)
        assertTrue(a.results.all { it.provider == SearchProvider.OPEN_LIBRARY && it.domain == "openlibrary.org" })
        assertEquals(SearchAnswer.Failed(WebFail.BUSY, 30), OpenLibraryWire.parse(429, null, "30"))
        assertEquals(SearchAnswer.Failed(WebFail.SERVICE), OpenLibraryWire.parse(200, "{\"oops\":1}"))
    }

    @Test fun bookQuestionsAndTheCardsCredit() {
        assertTrue(OpenLibraryWire.wanted("find books by Ursula K. Le Guin"))
        assertTrue(OpenLibraryWire.wanted("les romans de Gabrielle Roy"))
        assertFalse(OpenLibraryWire.wanted("who won the Stanley Cup"))
        val s = WebSource(1, "A Wizard of Earthsea", "https://openlibrary.org/works/OL59863W", SearchProvider.OPEN_LIBRARY)
        val card = AnswerCard.of(SearchService.SEARXNG_HOME, "le guin books", Cited("A Wizard of Earthsea [1].", listOf(s), emptyList()))
        assertEquals(listOf("From Open Library"), card.credits)
    }
}
