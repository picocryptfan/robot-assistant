// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Web search's defaults and file form, set-up's order, the first search's card, the User-Agents and the log's numbers. */
class WebSettingsTest {
    @Test fun offUntilSetUp() {
        val d = WebSettings()
        assertFalse(d.on)
        assertNull(d.service)
        assertFalse(d.ready)
        assertEquals(WebAsk.FIRST_TIME, d.ask)
        // 1.0: Encyclopedia lookups on by default, now that Robot publishes a contact for Wikipedia's rules.
        assertTrue(d.encyclopedia)
        assertFalse(d.books)
        assertEquals(ReadPages.WHEN_NEEDED, d.readPages)
        assertEquals(ReadThrough.PHONE, d.readThrough)
        assertEquals(3, d.pages)
        assertEquals(SafeSearch.MODERATE, d.safeSearch)
        assertFalse(d.addTown)
        assertEquals(CurrentInfo.ROBOT_FIRST, d.currentInfo)
        assertTrue(d.helpersMayUse)
        assertEquals(KeepSources.DOMAINS, d.keepSources)
        assertEquals(100, d.dailySearches)
        assertFalse(d.lockScreen)
        assertTrue(d.poweredByBrave)
    }

    @Test fun readyNeedsTheServiceAndItsAddress() {
        assertFalse(WebSettings(on = true, service = SearchService.SEARXNG_HOME).ready)
        assertTrue(WebSettings(on = true, service = SearchService.SEARXNG_HOME, searxng = "192.168.1.20:8888").ready)
        assertFalse(WebSettings(on = true, service = SearchService.SEARXNG_PUBLIC, searxng = "192.168.1.20:8888").ready)
        assertTrue(WebSettings(on = true, service = SearchService.KAGI).ready)
        assertFalse(WebSettings(on = false, service = SearchService.KAGI).ready)
    }

    @Test fun fileFormRoundTripsAndAnOlderFileReadsAsOff() {
        assertEquals(WebSettings(), WebSettings.fromJson(null))
        assertEquals(WebSettings(), WebSettings.fromJson(JSONObject()))
        val s = WebSettings(on = true, service = SearchService.BRAVE, searxng = "https://s.example", ask = WebAsk.NEVER, askAt = 42, encyclopedia = true,
            books = true, readPages = ReadPages.NEVER, readThrough = ReadThrough.SERVICE, pages = 5, safeSearch = SafeSearch.STRICT, addTown = true,
            currentInfo = CurrentInfo.HELPER_FIRST, helpersMayUse = false, keepSources = KeepSources.NONE, dailySearches = 7, lockScreen = true,
            poweredByBrave = false)
        assertEquals(s, WebSettings.fromJson(JSONObject(s.toJson().toString())))
        // Nothing secret in the file: keys live in the Keystore.
        assertFalse(s.toJson().toString().contains("key", ignoreCase = true))
    }

    @Test fun oddValuesFallBackToDefaults() {
        val o = JSONObject("""{"on": true, "service": "DUCKDUCKGO", "ask": "SOMETIMES", "pages": 99, "dailySearches": -3, "readPages": 5, "searxng": "  "}""")
        val s = WebSettings.fromJson(o)
        assertNull(s.service)
        assertFalse(s.on)
        assertEquals(WebAsk.FIRST_TIME, s.ask)
        assertEquals(WebSettings.MAX_PAGES, s.pages)
        assertEquals(1, s.dailySearches)
        assertEquals(ReadPages.WHEN_NEEDED, s.readPages)
        assertNull(s.searxng)
    }

    @Test fun setUpSuggestsKagiFirstWithoutAHomeServer() {
        assertEquals(SearchService.KAGI, WebSetup.suggested(hasHomeServer = false))
        assertEquals(SearchService.SEARXNG_HOME, WebSetup.suggested(hasHomeServer = true))
        // 1.0: DuckDuckGo's free Instant Answers with the free ones, after the paid services and your own SearXNG.
        assertEquals(listOf(SearchService.KAGI, SearchService.BRAVE, SearchService.MOJEEK, SearchService.SEARXNG_HOME, SearchService.DDG_INSTANT,
            SearchService.SEARXNG_PUBLIC, SearchService.WIKIPEDIA_ONLY), WebSetup.order(false))
        assertEquals(SearchService.entries.toSet(), WebSetup.order(true).toSet())
        // A public SearXNG is offered, never suggested.
        assertTrue(WebSetup.order(false).indexOf(SearchService.SEARXNG_PUBLIC) > 0)
        assertEquals("Google, Bing, Startpage and DuckDuckGo have no full search API that Robot can use: they open in your browser instead.", WebSetup.NOT_OFFERED)
        // The consent says who gets your words (Wikipedia too for a who, what or where question, Encyclopedia lookups being on by
        // default) and the pages Settings allow.
        assertEquals("When you ask, Robot sends your search words to Kagi. A who, what or where question also goes to Wikipedia. To answer, it may " +
            "open up to 3 of the result pages from this phone, like a browser without cookies: those sites see your IP address. Nothing else leaves the phone.",
            WebSetup.consent(SearchService.KAGI, WebSettings()))
        assertTrue(SearchService.BRAVE.needsKey && SearchService.KAGI.needsKey && SearchService.MOJEEK.needsKey)
        assertFalse(SearchService.SEARXNG_HOME.needsKey)
        assertEquals(SearchService.entries.size, SearchService.entries.map { it.code }.toSet().size)
    }

    /** The consent says what leaves the phone as PRIVACY.md does: the pages from Settings' slider (1 to 5), and every service asked. */
    @Test fun theConsentFollowsTheSettings() {
        fun c(service: SearchService, s: WebSettings = WebSettings()) = WebSetup.consent(service, s)
        val wiki = "A who, what or where question also goes to Wikipedia."
        val books = "A question about books also goes to Open Library."
        assertTrue(c(SearchService.BRAVE, WebSettings(pages = 5)).contains("it may open up to 5 of the result pages"))
        assertTrue(c(SearchService.BRAVE, WebSettings(pages = 1)).contains("it may open 1 result page from this phone, like a browser without cookies: that site sees your IP address."))
        // Pages read through the service, or none read: no page from this phone.
        for (s in listOf(WebSettings(readPages = ReadPages.NEVER), WebSettings(readThrough = ReadThrough.SERVICE))) {
            assertEquals("When you ask, Robot sends your search words to Brave. $wiki Nothing else leaves the phone.", c(SearchService.BRAVE, s))
        }
        // Wikipedia only while Encyclopedia lookups are on, Open Library only while Book lookups are, and nothing more about
        // Wikipedia when it is the service itself.
        assertEquals("When you ask, Robot sends your search words to Mojeek. $books Nothing else leaves the phone.",
            c(SearchService.MOJEEK, WebSettings(encyclopedia = false, books = true, readPages = ReadPages.NEVER)))
        assertTrue(c(SearchService.MOJEEK, WebSettings(books = true)).startsWith("When you ask, Robot sends your search words to Mojeek. $wiki $books To answer"))
        assertFalse(c(SearchService.MOJEEK, WebSettings(encyclopedia = false)).contains("Wikipedia"))
        assertTrue(c(SearchService.WIKIPEDIA_ONLY).startsWith("When you ask, Robot sends your search words to Wikipedia. To answer"))
        assertEquals(1, Regex("Wikipedia").findAll(c(SearchService.WIKIPEDIA_ONLY)).count())
        // The one being set up counts, not the one in use.
        assertEquals(c(SearchService.KAGI), c(SearchService.KAGI, WebSettings(service = SearchService.DDG_INSTANT)))
    }

    @Test fun theFirstSearchShowsATapOnlyCard() {
        val c = WebConsent.card(WebAsk.FIRST_TIME, SearchService.SEARXNG_HOME, "who won the stanley cup this year", pages = 3)!!
        assertEquals("Search the web for “who won the stanley cup this year”", c.title)
        assertEquals("Robot would send these words to your SearXNG. It may also open up to 3 result pages from this phone.", c.note)
        assertEquals(listOf("Allow always", "Just this time", "No"), c.buttons)
        assertEquals("Don't ask again", c.remember)
        assertTrue(c.tapOnly)
        assertEquals("Robot would send these words to Kagi.", WebConsent.card(WebAsk.FIRST_TIME, SearchService.KAGI, "q", pages = 0)!!.note)
        val always = WebConsent.card(WebAsk.ALWAYS, SearchService.BRAVE, "q", 1)!!
        assertEquals(listOf("Search", "Cancel"), always.buttons)
        assertFalse(always.tapOnly)
        assertNull(always.remember)
        assertNull(WebConsent.card(WebAsk.NEVER, SearchService.BRAVE, "q", 3))
    }

    @Test fun theCardsLastingAnswerCountsWhileNewer() {
        val settings = WebSettings(on = true, service = SearchService.KAGI, ask = WebAsk.FIRST_TIME, askAt = 1_000)
        val always = WebConsentAnswer.of(WebConsentChoice.ALWAYS, 2_000)!!
        assertEquals(WebAsk.NEVER, WebConsent.ask(settings, always))
        assertTrue(WebConsent.on(settings, always))
        // Settings chose again afterwards: Settings win.
        assertEquals(WebAsk.FIRST_TIME, WebConsent.ask(settings.copy(askAt = 3_000), always))
        // "No" with "Don't ask again": no search at all.
        val never = WebConsentAnswer.of(WebConsentChoice.NEVER, 2_000)!!
        assertFalse(WebConsent.on(settings, never))
        assertTrue(WebConsent.on(settings.copy(askAt = 3_000), never))
        assertNull(WebConsentAnswer.of(WebConsentChoice.ONCE, 1))
        assertNull(WebConsentAnswer.of(WebConsentChoice.NO, 1))
        assertFalse(WebConsent.on(WebSettings(), null))
        assertEquals(always, WebConsentAnswer.fromJson(always.toJson()))
        assertEquals(never, WebConsentAnswer.fromJson(never.toJson()))
        assertNull(WebConsentAnswer.fromJson("{oops"))
        assertNull(WebConsentAnswer.fromJson("""{"ask": "MAYBE"}"""))
        assertNull(WebConsentAnswer.fromJson(null))
        assertEquals("web_answer.json", WebConsentAnswer.FILE)
    }

    @Test fun userAgents() {
        assertEquals("Robot/0.7.0 (ai.robot_app; Android voice assistant; hey@robot-app.ai)", WebUserAgent.api("0.7.0"))
        assertEquals("Robot/0.7.0 (ai.robot_app; Android voice assistant)", WebUserAgent.api("0.7.0", null))
        assertEquals("Robot/0.7.0 (ai.robot_app; Android voice assistant; robot@example.org)", WebUserAgent.api("0.7.0", " robot@example.org "))
        // 1.0 rc1: the "-" stays, as the downloads send it (AppId.product).
        assertEquals("Robot/0.7.0-betax (ai.robot_app; Android voice assistant)", WebUserAgent.api("0.7.0-beta (x)", null))
        assertEquals("Robot/0 (ai.robot_app; Android voice assistant)", WebUserAgent.api("", null))
        assertEquals("Mozilla/5.0 (Linux; Android 17) Robot", WebUserAgent.PAGE)
        val h = WebUserAgent.pageHeaders("fr")
        assertEquals(listOf("User-Agent", "Accept", "Accept-Language"), h.map { it.first })
        assertEquals("fr", h.last().second)
        assertFalse(h.any { it.first.equals("Cookie", true) || it.first.equals("Referer", true) })
        assertEquals(2, WebUserAgent.pageHeaders("fr-CA; q=1, en").size)
    }

    @Test fun logLinesAreNumbersOnly() {
        val lines = listOf(
            WebLogLine.search(SearchService.SEARXNG_HOME, 8, 950),
            WebLogLine.pages(2, listOf(PageSkip.NOT_PUBLIC), 4_200),
            WebLogLine.answer(2_431, 61, 3_100),
            WebLogLine.failed(WebFail.KEY_REFUSED),
        )
        assertEquals(listOf("web_search svc=1 n=8 ms=950", "web_pages ok=2 skipped=1 ms=4200 why=4", "web_answer in=2430 out=61 first_ms=3100", "web_failed why=7"),
            lines.map { it.logcat() })
        for (l in lines) assertTrue(l.logcat(), l.logcat().matches(Regex("web_[a-z]+( [a-z_]+=\\d+)+")))
        assertEquals(listOf(160, 161, 162, 163, 164), WebLogEvent.entries.map { it.reservedId })
        // 1.0: a search in your browser, by the engine's code and whose words, never the words.
        assertEquals("browser_search engine=1 by=1 fallback=1", WebLogLine.browser(BrowserEngine.STARTPAGE, own = true, fallback = true).logcat())
        assertEquals("browser_search engine=7 by=2 fallback=0", WebLogLine.browser(BrowserEngine.GOOGLE, own = false, fallback = false).logcat())
        assertTrue(WebLogEvent.entries.all { it.reservedId in 160..169 })
        assertEquals(WebFail.entries.size, WebFail.entries.map { it.id }.toSet().size)
    }

    @Test fun failuresInFixedWords() {
        assertEquals("Your SearXNG isn't answering", WebFail.NOT_ANSWERING.text(SearchService.SEARXNG_HOME))
        assertEquals("Robot can't reach your SearXNG off your home network", WebFail.NOT_HOME.text(SearchService.SEARXNG_HOME))
        assertEquals("The search took too long", WebFail.TIMEOUT.text(SearchService.KAGI))
        assertEquals("No results", WebFail.NO_RESULTS.text(SearchService.KAGI))
        assertEquals("Kagi refused the key", WebFail.KEY_REFUSED.text(SearchService.KAGI))
        assertEquals("The search service didn't answer properly", WebFail.SERVICE.text(null))
        assertEquals("That search looked like it contained personal details, so Robot didn't send it.", WebFail.QUERY_REFUSED.text(null))
    }
}
