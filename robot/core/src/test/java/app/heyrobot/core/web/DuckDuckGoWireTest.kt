package app.heyrobot.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0, DuckDuckGo's Instant Answer API (DuckDuckGoWire): the request carries only the words and the app's name, the
 * answer becomes results with their sources (written from the API's own answer shape, never fetched here), and the card
 * credits DuckDuckGo and the source. Short answers only: no page is read.
 */
class DuckDuckGoWireTest {
    private fun sample(name: String) = javaClass.classLoader!!.getResourceAsStream("web/$name")!!.readBytes().toString(Charsets.UTF_8)
    private fun results(a: SearchAnswer) = (a as SearchAnswer.Results).results
    private val ua = WebUserAgent.api("1.0.0")

    @Test fun theRequestCarriesTheWordsAndTheAppsNameOnly() {
        val r = DuckDuckGoWire.request("  Canadiens de Montréal ", ua)
        assertEquals("GET", r.method)
        assertEquals("https://api.duckduckgo.com/?q=Canadiens%20de%20Montr%C3%A9al&format=json&no_html=1&skip_disambig=1&no_redirect=1&t=heyrobot", r.url)
        assertEquals(ua, r.header("User-Agent"))
        assertEquals(listOf("User-Agent", "Accept"), r.headers.map { it.first })
        assertEquals("api.duckduckgo.com", r.host)
        assertFalse(r.toString().contains("Canadiens"))
    }

    @Test fun anAbstractItsSourceAndTheOfficialSite() {
        // The API answers 202 as often as 200, with the same body.
        for (status in listOf(200, 202)) {
            val a = DuckDuckGoWire.parse(status, sample("duckduckgo_abstract.json"), "Canadiens")
            val r = results(a)
            assertEquals(listOf("https://en.wikipedia.org/wiki/Montreal_Canadiens", "https://www.nhl.com/canadiens", "https://duckduckgo.com/Bell_Centre",
                "https://duckduckgo.com/Montreal_Forum", "https://duckduckgo.com/Stanley_Cup"), r.map { it.url })
            assertEquals("Montreal Canadiens", r[0].title)
            assertEquals("The Montreal Canadiens are a professional ice hockey team based in Montreal. They play in the Atlantic Division of the NHL.", r[0].snippet)
            assertEquals(listOf(r[0].snippet), r[0].extra)
            assertEquals("Montreal Canadiens: Official site", r[1].title)
            assertEquals("Bell Centre", r[2].title)
            assertTrue(r.all { it.provider == SearchProvider.DUCKDUCKGO })
        }
    }

    @Test fun aComputedAnswerIsCreditedToDuckDuckGosPage() {
        val r = results(DuckDuckGoWire.parse(200, sample("duckduckgo_answer.json"), "2+2"))
        assertEquals(1, r.size)
        assertEquals("https://duckduckgo.com/?q=2%2B2", r[0].url)
        assertEquals("2 + 2 = 4", r[0].snippet)
        assertEquals(listOf("2 + 2 = 4"), (DuckDuckGoWire.parse(200, sample("duckduckgo_answer.json"), "2+2") as SearchAnswer.Results).answers)
    }

    @Test fun nothingIsNoResultsAndFailuresAreSaid() {
        assertEquals(emptyList<SearchResult>(), results(DuckDuckGoWire.parse(202, sample("duckduckgo_empty.json"), "how are you")))
        assertEquals("DuckDuckGo has no short answer for that", WebFail.NO_RESULTS.text(SearchService.DDG_INSTANT))
        assertEquals("No results", WebFail.NO_RESULTS.text(SearchService.BRAVE))
        assertEquals(SearchAnswer.Failed(WebFail.BUSY, 30), DuckDuckGoWire.parse(429, "", "x", "30"))
        assertEquals(SearchAnswer.Failed(WebFail.NOT_ANSWERING), DuckDuckGoWire.parse(503, null, "x"))
        assertEquals(SearchAnswer.Failed(WebFail.SERVICE), DuckDuckGoWire.parse(200, "<html>", "x"))
        assertEquals(SearchAnswer.Failed(WebFail.SERVICE), DuckDuckGoWire.parse(301, null, "x"))
    }

    @Test fun shortAnswersOnlyFreeAndCredited() {
        val s = WebSettings(on = true, service = SearchService.DDG_INSTANT)
        assertTrue(s.ready)
        assertFalse(SearchService.DDG_INSTANT.needsKey || SearchService.DDG_INSTANT.needsAddress)
        assertEquals("≈ free", SearchService.DDG_INSTANT.cost)
        assertEquals(0.0, WebPrices.dollars(SearchService.DDG_INSTANT), 0.0)
        // No page is read, and the consent and the first search's card say only where the words go.
        assertEquals(0, WebGate.pagesFromPhone(s))
        assertEquals("When you ask, Robot sends your search words to DuckDuckGo. Nothing else leaves the phone.", WebSetup.consent(SearchService.DDG_INSTANT))
        assertEquals("Robot would send these words to DuckDuckGo.", WebConsent.note(SearchService.DDG_INSTANT, WebGate.pagesFromPhone(s)))
        // The card: "Results from DuckDuckGo", and Wikipedia's licence for its text.
        val found = results(DuckDuckGoWire.parse(200, sample("duckduckgo_abstract.json"), "Canadiens"))
        val cited = Cited("", found.take(1).mapIndexed { i, r -> WebSource(i + 1, r.title, r.url, r.provider) }, emptyList())
        val card = AnswerCard.of(SearchService.DDG_INSTANT, "Canadiens", cited)
        assertEquals("Results from DuckDuckGo", card.credits.first())
        assertTrue(card.credits.toString(), card.credits.any { it.contains("Wikipedia") && it.contains("CC BY-SA 4.0") })
        assertEquals("Web search with DuckDuckGo Instant Answers: 1 results in 300 ms",
            app.heyrobot.core.VoiceLogText.describe(app.heyrobot.core.VoiceLogEntry(0, app.heyrobot.core.VoiceEvent.WEB_SEARCH, 7, 1, 300)))
    }
}
