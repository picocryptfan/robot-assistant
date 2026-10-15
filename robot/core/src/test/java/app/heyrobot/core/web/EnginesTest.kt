// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.BrowserSearchAction
import app.heyrobot.core.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.0: the engines a search in your browser opens and every engine's and service's privacy level (Engines.kt): each
 * address keeps your words exactly (accents and all), each level comes from the facts its policy states, and the browser
 * path never reaches the network.
 */
class EnginesTest {
    @Test fun eachEnginesAddressCarriesTheWordsPercentEncoded() {
        val q = "café crème à Montréal"
        val enc = "caf%C3%A9%20cr%C3%A8me%20%C3%A0%20Montr%C3%A9al"
        assertEquals(mapOf(
            BrowserEngine.STARTPAGE to "https://www.startpage.com/sp/search?query=$enc",
            BrowserEngine.DUCKDUCKGO to "https://duckduckgo.com/?q=$enc",
            BrowserEngine.BRAVE to "https://search.brave.com/search?q=$enc",
            BrowserEngine.MOJEEK to "https://www.mojeek.com/search?q=$enc",
            BrowserEngine.QWANT to "https://www.qwant.com/?q=$enc",
            BrowserEngine.ECOSIA to "https://www.ecosia.org/search?q=$enc",
            BrowserEngine.GOOGLE to "https://www.google.com/search?q=$enc",
            BrowserEngine.BING to "https://www.bing.com/search?q=$enc",
        ), BrowserEngine.entries.associateWith { it.url(q) })
        // Québec French: elisions, cedillas, the œ ligature; and characters that would end or change the query.
        assertEquals("https://duckduckgo.com/?q=l%27%C3%A9cole%20fran%C3%A7aise%20%C5%93uvre%20%C2%AB%20F%C3%AAte%20%C2%BB",
            BrowserEngine.DUCKDUCKGO.url("l'école française œuvre « Fête »"))
        assertEquals("https://www.google.com/search?q=C%2B%2B%20%26%20C%23%20100%25%20%3Fa%3Db%2Fc",
            BrowserEngine.GOOGLE.url("C++ & C# 100% ?a=b/c"))
        // Spaces around the words go; inside, each is %20 (never "+", which an engine reads as a plus).
        assertEquals("https://www.bing.com/search?q=a%20%20b", BrowserEngine.BING.url("  a  b "))
        for (e in BrowserEngine.entries) {
            val u = e.url("x y")
            assertTrue(u, u.startsWith("https://${e.host}/") && u.endsWith("x%20y"))
            // One query parameter and nothing about you: no language, region, account or Robot's name.
            assertEquals(u, 1, u.count { it == '=' })
            assertEquals(e.name, e.host, WebUrl.parse(u)?.host)
        }
    }

    @Test fun everyEngineAndServiceHasItsLevelFromItsFacts() {
        assertEquals(mapOf(
            BrowserEngine.STARTPAGE to PrivacyLevel.HIGH, BrowserEngine.DUCKDUCKGO to PrivacyLevel.HIGH, BrowserEngine.BRAVE to PrivacyLevel.HIGH,
            BrowserEngine.MOJEEK to PrivacyLevel.HIGH, BrowserEngine.QWANT to PrivacyLevel.MEDIUM, BrowserEngine.ECOSIA to PrivacyLevel.MEDIUM,
            BrowserEngine.GOOGLE to PrivacyLevel.LOW, BrowserEngine.BING to PrivacyLevel.LOW,
        ), BrowserEngine.entries.associateWith { it.level })
        assertEquals(mapOf(
            SearchService.SEARXNG_HOME to PrivacyLevel.HIGH, SearchService.KAGI to PrivacyLevel.HIGH, SearchService.BRAVE to PrivacyLevel.MEDIUM,
            SearchService.MOJEEK to PrivacyLevel.HIGH, SearchService.SEARXNG_PUBLIC to PrivacyLevel.LOW, SearchService.WIKIPEDIA_ONLY to PrivacyLevel.MEDIUM,
            SearchService.DDG_INSTANT to PrivacyLevel.HIGH,
        ), SearchService.entries.associateWith { it.level })
        // A level is its facts: nothing is High that keeps your words with your IP address, profiles you, or answers to no policy.
        for (kept in listOf(false, true)) for (profiles in listOf(false, true)) for (accountable in listOf(false, true)) {
            val l = PrivacyFacts(kept, profiles, accountable).level
            assertEquals(l == PrivacyLevel.HIGH, !kept && !profiles && accountable)
            assertEquals(l == PrivacyLevel.LOW, profiles || !accountable)
        }
    }

    @Test fun thePrivacyTableIsComplete() {
        assertEquals(BrowserEngine.entries.size, BrowserEngine.entries.map { it.code }.toSet().size)
        assertEquals(BrowserEngine.entries.size, BrowserEngine.entries.map { it.id }.toSet().size)
        for (e in BrowserEngine.entries) {
            for (field in listOf(e.title, e.operator, e.country, e.index)) assertTrue(e.name, field.isNotBlank())
            assertTrue(e.policy, e.policy.startsWith("https://"))
            assertTrue(e.name, e.names.isNotEmpty() && e.names.all { it == WebWords.fold(it) })
            // Every spoken name finds its engine, and so does its id (the tool's argument, the settings file).
            for (n in e.names + e.id + e.title) assertEquals(n, e, BrowserEngine.of(n))
        }
        assertEquals(setOf("Startpage", "DuckDuckGo", "Brave Search", "Mojeek", "Qwant", "Ecosia", "Google", "Bing"), BrowserEngine.entries.map { it.title }.toSet())
        // Robot reads none of these engines' results: the services that do are set-up's, each with its facts.
        for (s in SearchService.entries) assertNotNull(s.name, s.level)
        assertNull(BrowserEngine.of("yahoo"))
        assertEquals(BrowserEngine.GOOGLE, BrowserEngine.of("Google Search"))
        assertEquals(BrowserEngine.DUCKDUCKGO, BrowserEngine.of("Duck Duck Go"))
    }

    @Test fun theDefaultIsStartpage() {
        assertEquals(BrowserEngine.STARTPAGE, BrowserEngine.DEFAULT)
        assertEquals(PrivacyLevel.HIGH, BrowserEngine.DEFAULT.level)
        assertEquals(BrowserEngine.STARTPAGE, WebSettings().browserEngine)
        assertEquals(BrowserEngine.DEFAULT, BrowserEngine.entries.first())
    }

    @Test fun thePublicSearxngListIsShortAndHttpsOnly() {
        assertTrue(PublicSearxng.LIST.size in 3..6)
        assertEquals(PublicSearxng.LIST.size, PublicSearxng.LIST.toSet().size)
        for (u in PublicSearxng.LIST) {
            assertNotNull(u, SearxngWire.base(u, public = true))
            assertTrue(u, PublicSearxng.host(u).isNotEmpty() && !PublicSearxng.host(u).contains('/'))
        }
    }

    /** "No network from the browser path": the action isn't online, and its code names nothing that could send. */
    @Test fun theBrowserPathNeverReachesTheNetwork() {
        val a = BrowserSearchAction("café", BrowserEngine.DUCKDUCKGO)
        assertFalse(a.online)
        assertFalse(a.stoppable)
        assertFalse(ToolName.BROWSER_SEARCH.online)
        assertFalse(ToolName.BROWSER_SEARCH.reads)
        assertTrue(ToolName.BROWSER_SEARCH.internal)
        assertFalse(a.worksLocked(ToolName.entries.toSet()))
        val src = File(System.getProperty("user.dir") ?: ".", "src/main/java/app/heyrobot/core")
        for (f in listOf("web/Engines.kt", "web/BrowserSearch.kt", "BrowserActions.kt")) {
            val code = File(src, f).readText()
            for (net in listOf("java.net", "HttpURLConnection", "Socket", "okhttp", "SearchRequest", "WebTransport", "openConnection")) {
                assertFalse("$f names $net", code.contains(net))
            }
        }
    }
}
