package app.heyrobot.core.web

import app.heyrobot.core.Action
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.AskWhy
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.GateDecision
import app.heyrobot.core.LockScreen
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Design A4.2, A5, A10.2, A10.3 and A14 "Gate": what happens to web_search between the model's call and the network: web
 * search on, today's cap, the query's check, Ask before searching, and the approval gate's own rules (the level, the
 * lock, content in the conversation).
 */
class WebGateTest {
    private val searxng = WebSettings(on = true, service = SearchService.SEARXNG_HOME, searxng = "192.168.1.20:8888")
    private val mine = QueryContext(said = listOf("who won the stanley cup this year"))
    private fun search(q: String = "who won the stanley cup this year") = Action.WebSearch(q)

    private fun ready(p: Prepared): Prepared.Ready = p as? Prepared.Ready ?: throw AssertionError("refused: $p")

    @Test fun theModelsCallIsReadWithItsQueryAndDepth() {
        assertEquals(ParsedCall.Ok(Action.WebSearch("stanley cup 2026", "read")),
            ToolCalls.parse(RawToolCall("web_search", mapOf("query" to "stanley cup 2026", "depth" to "read"))))
        assertEquals(ParsedCall.Ok(Action.WebSearch("descale breville")), ToolCalls.parse(RawToolCall("web_search", mapOf("q" to " descale breville "))))
        assertTrue(ToolCalls.parse(RawToolCall("web_search", emptyMap())) is ParsedCall.Invalid)
        assertEquals("Search the web for “x”", Action.WebSearch("x").describe())
        // A read that goes online, as the weather: Settings › Actions says "uses the internet"; Just do it by default.
        assertTrue(ToolName.WEB_SEARCH.reads && ToolName.WEB_SEARCH.online && !ToolName.WEB_SEARCH.internal)
        assertEquals(app.heyrobot.core.Approval.AUTO, RobotSettings().approval(ToolName.WEB_SEARCH))
    }

    /**
     * 1.0 audit (network-1): with Book lookups on, a book question also goes to Open Library: the consent card and the
     * "Sent to" line name it beside your SearXNG; a question about anything else names only the service.
     */
    @Test fun theCardNamesOpenLibraryWhenBooksGoThere() {
        val books = searxng.copy(books = true, ask = WebAsk.ALWAYS, encyclopedia = false)
        val q = "search the web for books about living with lymphoma"
        val p = ready(WebGate.prepare(search("books about lymphoma"), books, null, QueryContext(said = listOf(q)), 0, question = q))
        assertTrue(p.note, p.note!!.contains("Robot would send these words to your SearXNG and Open Library."))
        assertEquals(listOf(WebAlso.OPEN_LIBRARY), WebAlso.names(books, SearchService.SEARXNG_HOME, q))
        assertEquals("your SearXNG and Open Library", WebAlso.to(SearchService.SEARXNG_HOME, WebAlso.names(books, SearchService.SEARXNG_HOME, q)))
        // Not a book question, or Book lookups off: the service alone.
        val stanley = ready(WebGate.prepare(search(), books, null, mine, 0, question = "who won the stanley cup this year"))
        assertTrue(stanley.note, stanley.note!!.contains("Robot would send these words to your SearXNG.") && !stanley.note!!.contains("Open Library"))
        assertEquals(emptyList<String>(), WebAlso.names(books.copy(books = false), SearchService.SEARXNG_HOME, q))
        // A personal query's tap-only card names them too, even with Never ask.
        val personal = QueryContext(said = listOf("find books about it"), personal = listOf("Julie's lymphoma"))
        val tap = ready(WebGate.prepare(search("lymphoma books"), books.copy(ask = WebAsk.NEVER), null, personal, 0, question = "find books about it"))
        assertTrue(AskWhy.SEARCH_PERSONAL in tap.why)
        assertTrue(tap.note, tap.note!!.contains("to your SearXNG and Open Library."))
        // Three: commas, then "and".
        assertEquals("Brave, Wikipedia and Open Library", WebAlso.to(SearchService.BRAVE, listOf(WebAlso.WIKIPEDIA, WebAlso.OPEN_LIBRARY)))
    }

    @Test fun offOrNotSetUpNothingGoes() {
        val off = WebGate.prepare(search(), WebSettings(), null, mine, 0)
        assertEquals(ActionStatus.DISABLED, (off as Prepared.Refused).status)
        assertTrue(off.detail.contains("Settings › Web search"))
        // Set up, but your SearXNG's address missing: not ready.
        assertTrue(WebGate.prepare(search(), searxng.copy(searxng = null), null, mine, 0) is Prepared.Refused)
        // "No" with "Don't ask again" on the first card, newer than Settings: off.
        val no = WebConsentAnswer.of(WebConsentChoice.NEVER, at = 10)!!
        assertTrue(WebGate.prepare(search(), searxng.copy(askAt = 5), no, mine, 0) is Prepared.Refused)
        // Settings chose again since: on.
        assertTrue(WebGate.prepare(search(), searxng.copy(askAt = 20), no, mine, 0) is Prepared.Ready)
    }

    @Test fun todaysCapIsSaidAndNothingGoes() {
        val capped = WebGate.prepare(search(), searxng.copy(dailySearches = 2), null, mine, searchesToday = 2)
        assertEquals(ActionStatus.FAILED, (capped as Prepared.Refused).status)
        assertTrue(capped.detail, capped.detail.startsWith("That's today's limit of web searches (2)"))
        assertTrue(WebGate.prepare(search(), searxng.copy(dailySearches = 2), null, mine, searchesToday = 1) is Prepared.Ready)
        val c = WebCount().add(LocalDate.of(2026, 10, 11)).add(LocalDate.of(2026, 10, 11))
        assertEquals(2, c.on(LocalDate.of(2026, 10, 11)))
        assertEquals(0, c.on(LocalDate.of(2026, 10, 12)))
        assertEquals(c, WebCount.fromJson(c.toJson()))
        assertEquals(WebCount(), WebCount.fromJson("{oops"))
    }

    @Test fun theFirstSearchShowsACardAnsweredByATap() {
        val p = ready(WebGate.prepare(search(), searxng, null, mine, 0))
        assertEquals(listOf("Allow always", "Just this time", "No"), p.choices.map { it.label })
        assertEquals("Don't ask again", p.remember)
        assertTrue(AskWhy.ONLINE in p.why)
        assertEquals(AskWhy.ONLINE, CardAnswer.tapFor(p.reasons))
        assertEquals("Robot would send these words to your SearXNG. It may also open up to 3 result pages from this phone.", p.note)
        // Allow always and Just this time search; No doesn't; ticked, Allow always and No, don't ask again last.
        assertEquals(WebConsentChoice.ALWAYS, (p.choices[0].action as Action.WebSearch).consent)
        assertEquals(WebConsentChoice.ONCE, (p.choices[1].action as Action.WebSearch).consent)
        assertEquals(WebConsentChoice.NEVER, (p.choices[2].remember as Action.WebSearch).consent)
        assertTrue(WebGate.searches(WebConsentChoice.ONCE) && WebGate.searches(WebConsentChoice.ALWAYS) && WebGate.searches(null))
        assertFalse(WebGate.searches(WebConsentChoice.NO) || WebGate.searches(WebConsentChoice.NEVER))
        // The gate shows it even under Just do it, and a spoken yes can't answer it.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEB_SEARCH, false, RobotSettings(), p.reasons))
        assertEquals(CardAnswer.Does.TAP, CardAnswer.reply("yes", CardAnswer.Waiting(3, CardAnswer.tapFor(p.reasons)), true, false, spoken = true)!!.does)
        // After "Allow always" (its lasting answer newer than Settings): no card.
        val always = WebConsentAnswer.of(WebConsentChoice.ALWAYS, at = 10)!!
        val next = ready(WebGate.prepare(search(), searxng, always, mine, 0))
        assertTrue(next.why.isEmpty() && next.choices.isEmpty())
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.WEB_SEARCH, false, RobotSettings(), next.reasons))
    }

    @Test fun alwaysAskAndNeverAsk() {
        val always = ready(WebGate.prepare(search(), searxng.copy(ask = WebAsk.ALWAYS), null, mine, 0))
        assertEquals(setOf(AskWhy.SEARCH), always.why)
        assertTrue(always.choices.isEmpty())
        assertNull(CardAnswer.tapFor(always.reasons))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEB_SEARCH, false, RobotSettings(), always.reasons))
        assertEquals("Search", always.action.confirmLabel)
        val never = ready(WebGate.prepare(search(), searxng.copy(ask = WebAsk.NEVER), null, mine, 0))
        assertTrue(never.why.isEmpty())
        // Pages read through the service, or never read: the card promises no page from this phone.
        assertEquals(0, WebGate.pagesFromPhone(searxng.copy(readPages = ReadPages.NEVER)))
        assertEquals(0, WebGate.pagesFromPhone(searxng.copy(service = SearchService.BRAVE, readThrough = ReadThrough.SERVICE)))
        assertEquals(3, WebGate.pagesFromPhone(searxng.copy(readThrough = ReadThrough.SERVICE)))
        assertEquals("Robot would send these words to Brave.",
            ready(WebGate.prepare(search(), WebSettings(on = true, service = SearchService.BRAVE, readPages = ReadPages.NEVER), null, mine, 0)).note)
    }

    @Test fun theLevelTheLockAndContentStillApply() {
        val s = RobotSettings()
        // Locked: not on the lock screen by default (it would spend your key's credit, and nobody knows who asks).
        assertFalse(ToolName.WEB_SEARCH in LockScreen.DEFAULT)
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.WEB_SEARCH, true, s))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.WEB_SEARCH, true, s.copy(lockedTools = setOf(ToolName.WEB_SEARCH))))
        assertEquals(GateDecision.DISABLED, ApprovalGate.decide(ToolName.WEB_SEARCH, false, s.copy(approvals = s.approvals + (ToolName.WEB_SEARCH to app.heyrobot.core.Approval.OFF))))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEB_SEARCH, false, s.copy(approvals = s.approvals + (ToolName.WEB_SEARCH to app.heyrobot.core.Approval.ASK))))
        // Content in the conversation: a search you didn't ask for in your own words asks first; one you asked for runs.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEB_SEARCH, false, s, content = true, asked = false))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.WEB_SEARCH, false, s, content = true, asked = true))
        // A helper asking (W4): its card, with the helpers' settings.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEB_SEARCH, false, s, remote = true))
    }

    @Test fun aQueryWithPersonalDetailsNeverGoesAndBorrowedWordsShowTheirCard() {
        val refused = WebGate.prepare(search("email jo@example.com about the dentist"), searxng.copy(ask = WebAsk.NEVER), null, mine, 0)
        assertEquals(WebFail.QUERY_REFUSED.text(null), (refused as Prepared.Refused).detail)
        assertTrue(WebGate.prepare(search("call 514 555 0199"), searxng, null, mine, 0) is Prepared.Refused)
        // Read the calendar, then a search with a name only the calendar had: a card answered by a tap, the query in full.
        val turns = listOf(
            ChatTurn("1", TurnRole.USER, "what's on tomorrow?", 1),
            ChatTurn("2", TurnRole.ASSISTANT, "You see Dr. Okonkwo, the dentist, at 3 pm.", 2,
                actions = listOf(ActionRecord(ToolName.READ_CALENDAR, "Check your calendar for tomorrow", ActionStatus.DONE, content = true))),
        )
        val ctx = WebQueryContext.of(turns, "search the web for that dentist's phone number")
        assertTrue(ctx.isPersonal && "You see Dr. Okonkwo, the dentist, at 3 pm." in ctx.personal)
        val p = ready(WebGate.prepare(search("Okonkwo dentist phone number"), searxng.copy(ask = WebAsk.NEVER), null, ctx, 0))
        assertEquals(setOf(AskWhy.SEARCH_PERSONAL), p.why)
        assertEquals(AskWhy.SEARCH_PERSONAL, CardAnswer.tapFor(p.reasons))
        assertEquals(QueryCard.PERSONAL.note, p.note)
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEB_SEARCH, false, RobotSettings(), p.reasons))
        // Your own words go without one.
        assertTrue(ready(WebGate.prepare(search("dentist phone number"), searxng.copy(ask = WebAsk.NEVER), null, ctx, 0)).why.isEmpty())
        // A web answer is content Robot didn't write, never personal: its words show the plain card.
        val web = turns + ChatTurn("3", TurnRole.USER, "who won?", 3) + ChatTurn("4", TurnRole.ASSISTANT, "The Panthers beat the Oilers [1].", 4,
            actions = listOf(ActionRecord(ToolName.WEB_SEARCH, "Searched the web (your SearXNG) · nhl.com", ActionStatus.DONE, content = true)))
        val wctx = WebQueryContext.of(web, "and next year?")
        assertTrue("The Panthers beat the Oilers [1]." in wctx.untrusted)
        assertFalse("The Panthers beat the Oilers [1]." in wctx.personal)
        assertEquals(setOf(AskWhy.SEARCH), ready(WebGate.prepare(search("Oilers schedule"), searxng.copy(ask = WebAsk.NEVER), null, wctx, 0)).why)
        assertTrue(app.heyrobot.core.ClaudeContext.personal(web.drop(2)).isEmpty())
    }

    @Test fun yourTownOnlyWhenAskedAndNeverCoordinates() {
        val home = "Saint-Jean-sur-Richelieu, Quebec, Canada"
        assertEquals("pizza near me", WebGate.withTown("pizza near me", addTown = false, home = home))
        assertEquals("pizza near Saint-Jean-sur-Richelieu", WebGate.withTown("pizza near me", addTown = true, home = home))
        assertEquals("pizza", WebGate.withTown("pizza", addTown = true, home = home))
        assertEquals("pharmacie near Saint-Jean-sur-Richelieu", WebGate.withTown("pharmacie près de moi", addTown = true, home = home))
        assertEquals("pizza near me", WebGate.withTown("pizza near me", addTown = true, home = null))
        val p = ready(WebGate.prepare(search("pizza near me"), searxng.copy(ask = WebAsk.NEVER, addTown = true), null, QueryContext(said = listOf("pizza near me")), 0, home))
        assertEquals("pizza near Saint-Jean-sur-Richelieu", (p.action as Action.WebSearch).query)
    }

    @Test fun pricesKeysAndStatusWords() {
        assertEquals(0.005, WebPrices.dollars(SearchService.BRAVE), 1e-9)
        assertEquals(0.012 + 3 * 0.004, WebPrices.dollars(SearchService.KAGI, 3), 1e-9)
        assertEquals(0.0, WebPrices.dollars(SearchService.SEARXNG_HOME), 0.0)
        assertNull(WebKeyFormat.problem("BSAabcdefghijklmnop123"))
        assertTrue(WebKeyFormat.problem("has space key 12345") != null && WebKeyFormat.problem("short") != null && WebKeyFormat.problem("") != null)
        assertEquals("BSA…3456", WebKeyFormat.mask("BSAabcdefghij123456"))
        assertEquals("Searching the web…", WebStatus.searching("en"))
        assertEquals("Je cherche sur le web…", WebStatus.searching("fr"))
        assertEquals("Reading 3 pages…", WebStatus.reading(3, "en"))
        assertEquals("Je lis 3 pages…", WebStatus.reading(3, "fr"))
        assertEquals("Reading pages 2/3…", WebStatus.reading(3, "en", done = 2))
        assertEquals("No results.", WebOutcome.Failed(SearchService.SEARXNG_HOME, WebFail.NO_RESULTS).text)
        assertEquals("Robot can't reach your SearXNG off your home network.", WebOutcome.Failed(SearchService.SEARXNG_HOME, WebFail.NOT_HOME).text)
    }
}
