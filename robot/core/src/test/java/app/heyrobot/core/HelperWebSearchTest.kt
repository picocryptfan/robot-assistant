// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.SearchProvider
import app.heyrobot.core.web.SearchResult
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebGate
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.core.web.WebSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4 (websearch-termux-design.md, W-19): "Let helpers use Robot's search". web_search is a helper's client tool only for a
 * helper that can't search itself, with the switch on and web search set up; each call goes through the helper's card
 * rules and Robot's own search checks; what it finds goes back to the helper as content; one search per answer.
 */
class HelperWebSearchTest {
    private class FakeModel(private val first: ModelOutcome, private val next: (Int) -> ModelOutcome = { ModelOutcome.Done(DoneReason.END) }) : TurnModel {
        val answers = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        override suspend fun ask() = first
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            answers += results
            return next(answers.size)
        }
        override fun close() {}
        override fun abandon(results: List<Pair<String, Map<String, Any?>>>) {}
        override fun stopped(results: List<Pair<String, Map<String, Any?>>>) {}
    }

    private class FakeActions(var settings0: RobotSettings = RobotSettings()) : TurnActions {
        override val openNamed get() = false
        override fun firstOpenWithoutCard(): Boolean = false
        val ran = mutableListOf<Action>()
        var prepare: (Action) -> Prepared = { Prepared.Ready(it) }
        /** The panel's three-argument prepare (TurnRunner's): [remote] is true for a helper's call. */
        var prepareTurn: ((Action, Boolean) -> Prepared)? = null
        var locked0 = false
        var result: (Action) -> Executed = { Executed(ActionStatus.DONE, "Sent to Brave", web = found) }
        override val locked get() = locked0
        override val settings get() = settings0
        override suspend fun prepare(action: Action): Prepared = prepare.invoke(action)
        override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
            prepareTurn?.invoke(action, remote) ?: prepare(action)
        override suspend fun run(action: Action): Executed {
            ran += action
            return result(action)
        }
    }

    private class FakeView : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) {
            cards[c.id] = c
            if (c.status == ActionStatus.PENDING) pending.trySend(c.id)
        }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
        override fun holdClaims(on: Boolean) {}
    }

    private var ids = 0
    private fun search(q: String) = RawToolCall("web_search", mapOf("query" to q))
    private val web = WebSettings(on = true, service = SearchService.BRAVE)

    /** A helper's turn where every card is answered yes; [cards] counts the cards that waited. */
    private fun helperTurn(model: FakeModel, actions: FakeActions, offered: Set<ToolName> = setOf(ToolName.WEB_SEARCH)): Triple<TurnResult, FakeView, Int> =
        runBlocking {
            val view = FakeView()
            val conf = Confirmations()
            var cards = 0
            val answering = launch { for (id in view.pending) { cards++; conf.answer(id, true) } }
            val result = withTimeout(10_000) {
                async { TurnRunner(model, actions, view, conf, untrusted = true, remote = true, offered = offered) { "w${++ids}" }.run() }.await()
            }
            answering.cancel()
            Triple(result, view, cards)
        }

    @Test fun offeredOnlyToAHelperThatCantSearchWithTheSwitchOnAndSearchSetUp() {
        val s = RobotSettings()
        assertTrue(HelperTools.offersSearch(s, web, null, helperSearches = false))
        // A helper that searches the web itself is never offered Robot's search.
        assertFalse(HelperTools.offersSearch(s, web, null, helperSearches = true))
        // "Let helpers use Robot's search" off, or web search not set up: no tool.
        assertFalse(HelperTools.offersSearch(s, web.copy(helpersMayUse = false), null, helperSearches = false))
        assertFalse(HelperTools.offersSearch(s, WebSettings(), null, helperSearches = false))
        // Offered last, so the other tools' bytes stay where they were; not offered by default.
        assertEquals(HelperTools.WEB_SEARCH_SPEC, HelperTools.tools(s, webSearch = true).last())
        assertEquals(HelperTools.tools(s), HelperTools.tools(s, webSearch = true).dropLast(1))
        assertTrue(HelperTools.tools(s).none { it.tool == ToolName.WEB_SEARCH })
    }

    @Test fun theSchemaIsQueryOnly() {
        assertEquals(listOf("query"), HelperTools.WEB_SEARCH_SPEC.params.map { it.name })
        val spec = HelperTools.spec(ToolName.WEB_SEARCH)
        assertNull(HelperTools.check(spec, mapOf("query" to "ferry times")))
        assertNotNull(HelperTools.check(spec, mapOf("query" to "ferry times", "depth" to "read")))
        assertNotNull(HelperTools.check(spec, emptyMap()))
    }

    @Test fun aHelperNotOfferedTheSearchCantCallIt() {
        val actions = FakeActions()
        val model = FakeModel(ModelOutcome.Calls(listOf(search("ferry times"))))
        val (_, view, _) = helperTurn(model, actions, offered = HelperTools.OFFERED.toSet())
        assertTrue(actions.ran.isEmpty())
        assertEquals(listOf(ActionStatus.INVALID), view.cards.values.map { it.status })
        // Known to the parser now, so it fails for not being offered (before W4 it was an unknown tool).
        assertTrue(model.answers.single().single().second.values.any { it == ToolStrings.notOffered().en() })
    }

    /** review-helper-web-search (Medium): "Add my town to local searches" never adds your town to a helper's query. */
    @Test fun aHelpersSearchGoesWithoutYourTown() {
        val town = web.copy(addTown = true)
        val home = "Dartmouth, Nova Scotia, Canada"
        val ctx = QueryContext(said = listOf("find me a pharmacy"))
        fun query(p: Prepared) = ((p as Prepared.Ready).action as Action.WebSearch).query
        assertEquals("pharmacy near Dartmouth", query(WebGate.prepare(Action.WebSearch("pharmacy near me"), town, null, ctx, 0, home)))
        assertEquals("pharmacy near me", query(WebGate.prepare(Action.WebSearch("pharmacy near me"), town, null, ctx, 0, home, helper = true)))
        val actions = FakeActions()
        actions.prepareTurn = { a, remote -> WebGate.prepare(a as Action.WebSearch, town, null, ctx, 0, home, helper = remote) }
        helperTurn(FakeModel(ModelOutcome.Calls(listOf(search("pharmacy near me")))), actions)
        assertEquals("pharmacy near me", (actions.ran.single() as Action.WebSearch).query)
    }

    /** review-helper-web-search (Low, HelperLink.kt:125): the switch turned off after the session offered the tool. */
    @Test fun theHelpersSwitchIsReadAtCallTime() {
        val ctx = QueryContext(said = listOf("ferry times"))
        val off = web.copy(helpersMayUse = false)
        assertTrue(WebGate.prepare(Action.WebSearch("ferry times"), off, null, ctx, 0) is Prepared.Ready)
        val actions = FakeActions()
        actions.prepareTurn = { a, remote -> WebGate.prepare(a as Action.WebSearch, off, null, ctx, 0, helper = remote) }
        val (_, view, _) = helperTurn(FakeModel(ModelOutcome.Calls(listOf(search("ferry times")))), actions)
        assertTrue(actions.ran.isEmpty())
        assertEquals(listOf(ActionStatus.DISABLED), view.cards.values.map { it.status })
    }

    @Test fun webSearchTurnedOffAtCallTimeRunsNothing() {
        val ctx = QueryContext(said = listOf("ferry times"))
        val actions = FakeActions()
        actions.prepareTurn = { a, remote -> WebGate.prepare(a as Action.WebSearch, WebSettings(), null, ctx, 0, helper = remote) }
        val (_, view, _) = helperTurn(FakeModel(ModelOutcome.Calls(listOf(search("ferry times")))), actions)
        assertTrue(actions.ran.isEmpty())
        assertEquals(listOf(ActionStatus.DISABLED), view.cards.values.map { it.status })
    }

    @Test fun webSearchOffInActionsAtCallTimeRunsNothing() {
        val actions = FakeActions(RobotSettings(approvals = mapOf(ToolName.WEB_SEARCH to Approval.OFF)))
        helperTurn(FakeModel(ModelOutcome.Calls(listOf(search("ferry times")))), actions)
        assertTrue(actions.ran.isEmpty())
    }

    /** web_search's "Works on the lock screen" is Off by default: a helper's search waits for the phone to unlock. */
    @Test fun aLockedPhoneRunsNoSearch() {
        val actions = FakeActions().apply { locked0 = true }
        val (_, view, _) = helperTurn(FakeModel(ModelOutcome.Calls(listOf(search("ferry times")))), actions)
        assertTrue(actions.ran.isEmpty())
        assertEquals(listOf(ActionStatus.NEEDS_UNLOCK), view.cards.values.map { it.status })
    }

    /** The cap is per answer, not per round: a second search in the next round is skipped too. */
    @Test fun theCapHoldsAcrossRounds() {
        val actions = FakeActions()
        val model = FakeModel(ModelOutcome.Calls(listOf(search("ferry times")))) { n ->
            if (n == 1) ModelOutcome.Calls(listOf(search("ferry prices"))) else ModelOutcome.Done(DoneReason.END)
        }
        helperTurn(model, actions)
        assertEquals(1, actions.ran.size)
        assertEquals("not_done_too_many_actions", model.answers[1].single().second["status"])
    }

    @Test fun theResultsGoBackToTheHelperAsContentAndTheTurnGoesOn() {
        val actions = FakeActions()
        val model = FakeModel(ModelOutcome.Calls(listOf(search("ferry times"))))
        val (result, _, cards) = helperTurn(model, actions)
        assertEquals(1, actions.ran.size)
        // "Card when a helper asks Robot to do something" is on by default: the search waited for its card.
        assertEquals(1, cards)
        // Not the panel's own web answer: the helper gets the results and answers itself.
        assertNull(result.web)
        val response = model.answers.single().single().second
        assertEquals("done", response["status"])
        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertEquals("Halifax ferry schedule", results.first { it["title"] != null }["title"])
        assertTrue(results.any { it["passage"] == "The ferry leaves every 30 minutes." })
    }

    @Test fun withHelperCardsOffASearchStillWaitsForItsCard() {
        // ApprovalGate: a helper's search sends its query out and its results to the helper, so helper cards off isn't enough.
        val actions = FakeActions(RobotSettings(helperActionCards = false, safetyCards = SafetyCards.UNSURE_ONLY))
        val model = FakeModel(ModelOutcome.Calls(listOf(search("ferry times"))))
        val (_, _, cards) = helperTurn(model, actions)
        assertEquals(1, actions.ran.size)
        assertEquals(1, cards)
    }

    @Test fun withPersonalContentToHelpersAndHelperCardsOffASearchRunsWithoutACard() {
        val actions = FakeActions(RobotSettings(helperActionCards = false, personalToHelpers = true, safetyCards = SafetyCards.UNSURE_ONLY))
        val model = FakeModel(ModelOutcome.Calls(listOf(search("ferry times"))))
        val (_, _, cards) = helperTurn(model, actions)
        assertEquals(1, actions.ran.size)
        assertEquals(0, cards)
    }

    @Test fun aSecondSearchInOneAnswerIsOverTheCap() {
        val actions = FakeActions()
        val model = FakeModel(ModelOutcome.Calls(listOf(search("ferry times"), search("ferry prices"))))
        val (_, view, _) = helperTurn(model, actions)
        assertEquals(1, actions.ran.size)
        val responses = model.answers.single()
        assertEquals("done", responses[0].second["status"])
        assertEquals("not_done_too_many_actions", responses[1].second["status"])
        assertEquals(ActionStatus.SKIPPED, view.cards.values.last().status)
    }

    /**
     * Robot's own searches' rule (QueryCheck, 0.7's memory): a word from what Robot remembers that you didn't say never goes
     * to a search service, tapped or not. A helper's query goes through the same prepare, so it is refused, and nothing runs.
     */
    @Test fun aQueryCarryingAMemoryItemIsRefusedAsForRobotsOwnSearches() {
        val ctx = QueryContext(said = listOf("any films on tonight"), memory = listOf("My sister Maribel lives in Dartmouth"))
        val prepared = WebGate.prepare(Action.WebSearch("Maribel Dartmouth films tonight"), web, null, ctx, 0)
        assertTrue(prepared is Prepared.Refused)
        val actions = FakeActions()
        actions.prepare = { WebGate.prepare(it as Action.WebSearch, web, null, ctx, 0) }
        val model = FakeModel(ModelOutcome.Calls(listOf(search("Maribel Dartmouth films tonight"))))
        helperTurn(model, actions)
        assertTrue(actions.ran.isEmpty())
        assertEquals("failed", model.answers.single().single().second["status"])
    }

    @Test fun aFailedSearchStaysAFailure() {
        val failed = Executed(ActionStatus.FAILED, "No network", web = WebOutcome.Failed(SearchService.BRAVE, app.heyrobot.core.web.WebFail.OFF))
        val r = HelperTools.forHelper(failed)
        assertNull(r.web)
        assertNull(r.results)
        assertEquals(ActionStatus.FAILED, r.status)
    }

    private companion object {
        val found = WebOutcome.Found(
            SearchService.BRAVE, "ferry times", "en",
            listOf(SearchResult("Halifax ferry schedule", "https://example.org/ferry", "Times and fares", SearchProvider.BRAVE)),
            passages = listOf(app.heyrobot.core.web.Passage(1, 0, "The ferry leaves every 30 minutes.")),
        )
    }
}
