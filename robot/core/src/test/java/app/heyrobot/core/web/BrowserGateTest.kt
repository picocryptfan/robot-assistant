// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.AskWhy
import app.heyrobot.core.BrowserSearchAction
import app.heyrobot.core.GateDecision
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.Prompt
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.StepClass
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.messages.ActionOrigin
import app.heyrobot.core.messages.MessageQuarantine
import app.heyrobot.core.messages.MessageTaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.0: browser_search between the call and your browser (BrowserGate): your own words open at once; the model's words
 * pass QueryCheck and always show their card first; the lock and a message in the conversation still apply.
 */
class BrowserGateTest {
    private val default = BrowserEngine.STARTPAGE
    private fun ctx(said: String, untrusted: List<String> = emptyList(), personal: List<String> = emptyList(), memory: List<String> = emptyList()) =
        QueryContext(said = listOf(said), untrusted = untrusted, personal = personal, memory = memory)

    private fun ready(p: Prepared): Prepared.Ready = p as? Prepared.Ready ?: throw AssertionError("not ready: $p")

    @Test fun yourOwnWordsOpenAtOnce() {
        val said = "search DuckDuckGo for 514-555-0199 reverse lookup"
        val r = ready(BrowserGate.prepare(BrowserSearchAction("514-555-0199 reverse lookup", BrowserEngine.DUCKDUCKGO), said, ctx(said), default))
        assertTrue(r.action is BrowserSearchAction && (r.action as BrowserSearchAction).own)
        assertEquals(BrowserEngine.DUCKDUCKGO, (r.action as BrowserSearchAction).engine)
        assertTrue(r.why.isEmpty())
        // "Search the web for …" while Robot's own search is off: your words, your default engine.
        val web = "search the web for café crème"
        val w = ready(BrowserGate.prepare(BrowserSearchAction("café crème"), web, ctx(web), default))
        assertEquals(BrowserSearchAction("café crème", default, own = true), w.action)
        assertTrue(w.why.isEmpty())
        // Not opened by a yes: the gate runs it (your own words asked for it), unlocked.
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.BROWSER_SEARCH, false, RobotSettings(), w.why, asked = true))
    }

    @Test fun theModelsWordsPassQueryCheckAndShowTheirCardFirst() {
        val said = "look it up on Google"
        // Words from nowhere in the conversation: still a card, since your browser sends them with its cookies.
        val plain = ready(BrowserGate.prepare(BrowserSearchAction("Stanley Cup winner 2026"), said, ctx(said), default))
        assertEquals(setOf(AskWhy.SEARCH), plain.why)
        assertEquals(BrowserEngine.GOOGLE, (plain.action as BrowserSearchAction).engine)
        assertFalse((plain.action as BrowserSearchAction).own)
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.BROWSER_SEARCH, false, RobotSettings(), plain.why))
        // A word from something Robot read on your phone: the card needs a tap.
        val dentist = ready(BrowserGate.prepare(BrowserSearchAction("Tremblay dentist phone", BrowserEngine.DUCKDUCKGO), "check that on DuckDuckGo",
            ctx("check that on DuckDuckGo", personal = listOf("Dentist, Dr Tremblay, 3 pm")), default) { "note" })
        assertEquals(setOf(AskWhy.SEARCH_PERSONAL), dentist.why)
        assertTrue(dentist.note!!.startsWith(QueryCard.PERSONAL.note) && dentist.note!!.endsWith("note"))
        // 1.0: "look that dentist up on DuckDuckGo" names something said before, so it isn't your own words: even "that dentist"
        // as the model's query isn't opened at once, and the dentist's name from the calendar needs a tap.
        val ref = "look that dentist up on DuckDuckGo"
        val echo = ready(BrowserGate.prepare(BrowserSearchAction("that dentist", BrowserEngine.DUCKDUCKGO), ref, ctx(ref), default))
        assertFalse((echo.action as BrowserSearchAction).own)
        assertEquals(setOf(AskWhy.SEARCH), echo.why)
        val named = ready(BrowserGate.prepare(BrowserSearchAction("Tremblay dentist phone"), ref, ctx(ref, personal = listOf("Dentist, Dr Tremblay, 3 pm")), default))
        assertEquals(setOf(AskWhy.SEARCH_PERSONAL), named.why)
        assertEquals(BrowserEngine.DUCKDUCKGO, (named.action as BrowserSearchAction).engine)
        // A page's word: a card a yes may answer.
        val page = ready(BrowserGate.prepare(BrowserSearchAction("Zorblax review"), "search that in my browser",
            ctx("search that in my browser", untrusted = listOf("Zorblax is the best")), default))
        assertEquals(setOf(AskWhy.SEARCH), page.why)
        // Never: an email, a long number, what Robot remembers about you.
        for ((q, c) in listOf("jo@example.com" to ctx(said), "card 4111 1111 1111 1111" to ctx(said),
            "Rex vet" to ctx(said, memory = listOf("My dog is called Rex")))) {
            val p = BrowserGate.prepare(BrowserSearchAction(q, BrowserEngine.GOOGLE), said, c, default)
            assertTrue("$q $p", p is Prepared.Refused && p.status == ActionStatus.FAILED)
        }
        // The model's words that happen to be what you said in another phrase aren't yours this turn.
        val other = ready(BrowserGate.prepare(BrowserSearchAction("cats", BrowserEngine.BING), "search Google for cats", ctx("search Google for cats"), default))
        assertFalse((other.action as BrowserSearchAction).own)
        assertEquals(setOf(AskWhy.SEARCH), other.why)
    }

    /**
     * Your own words go where your phrase sent them: the engine it names, Google for "google …", else your default. A model
     * call (with a photo or the screen, the model answers the phrase) that sends them to another engine shows the card.
     */
    @Test fun yourWordsGoOnlyWhereYouSentThemWithoutACard() {
        val browser = "search in my browser for café crème"
        val elsewhere = ready(BrowserGate.prepare(BrowserSearchAction("café crème", BrowserEngine.GOOGLE), browser, ctx(browser), default))
        assertFalse((elsewhere.action as BrowserSearchAction).own)
        assertEquals(setOf(AskWhy.SEARCH), elsewhere.why)
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.BROWSER_SEARCH, false, RobotSettings(), elsewhere.why, asked = true))
        val web = "search the web for café crème"
        assertFalse((ready(BrowserGate.prepare(BrowserSearchAction("café crème", BrowserEngine.BING), web, ctx(web), default)).action as BrowserSearchAction).own)
        // Your default named, or none: at once.
        for (e in listOf(null, default)) {
            val r = ready(BrowserGate.prepare(BrowserSearchAction("café crème", e), browser, ctx(browser), default))
            assertEquals(BrowserSearchAction("café crème", default, own = true), r.action)
            assertTrue(r.why.isEmpty())
        }
        // "Google cats" while Robot's own web search is off opens Google at once, as said.
        val g = ready(BrowserGate.prepare(BrowserSearchAction("cats", BrowserEngine.GOOGLE), "google cats", ctx("google cats"), default))
        assertEquals(BrowserSearchAction("cats", BrowserEngine.GOOGLE, own = true), g.action)
    }

    @Test fun theEngineIsTheOneNamedTheOneSaidOrYourDefault() {
        assertEquals(BrowserEngine.BING, BrowserGate.engine(BrowserSearchAction("x", BrowserEngine.BING), "look it up on Google", default))
        assertEquals(BrowserEngine.GOOGLE, BrowserGate.engine(BrowserSearchAction("x"), "look it up on Google", default))
        assertEquals(BrowserEngine.MOJEEK, BrowserGate.engine(BrowserSearchAction("x"), "look it up in my browser", BrowserEngine.MOJEEK))
    }

    @Test fun neverOnTheLockScreenAndATapAfterAMessage() {
        val s = RobotSettings()
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.BROWSER_SEARCH, true, s, asked = true))
        // Not even if a saved file listed it: it isn't a choice of "Works on the lock screen".
        assertFalse(ToolName.BROWSER_SEARCH in app.heyrobot.core.LockScreen.CHOICES)
        assertTrue(ApprovalGate.lockedOut(BrowserSearchAction("x", BrowserEngine.GOOGLE), true, s.copy(lockedTools = s.lockedTools + ToolName.BROWSER_SEARCH)))
        assertEquals("Unlock your phone, then ask again.", ApprovalGate.unlockLine(ToolName.BROWSER_SEARCH))
        // After a message, even your own search waits for a tap (0.7's quarantine), as Robot's own does.
        val taint = MessageTaint(conversations = setOf("thread-1"))
        assertEquals(setOf(AskWhy.MESSAGE), MessageQuarantine.why(BrowserSearchAction("x", BrowserEngine.GOOGLE, own = true), taint, ActionOrigin.USER_WORDS))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.BROWSER_SEARCH, false, s, asked = true, messages = true))
        // A routine never opens one, and a helper is never offered it.
        assertEquals(StepClass.NEVER, RoutineGate.classOf(ToolName.BROWSER_SEARCH))
        assertFalse(ToolName.BROWSER_SEARCH in app.heyrobot.core.HelperTools.OFFERED)
    }

    @Test fun theModelsCallIsReadLikeTheOthers() {
        fun parse(vararg a: Pair<String, Any?>) = ToolCalls.parse(RawToolCall("browser_search", mapOf(*a)))
        assertEquals(ParsedCall.Ok(BrowserSearchAction("Stanley Cup winner", BrowserEngine.DUCKDUCKGO)), parse("query" to "Stanley Cup winner", "engine" to "DuckDuckGo"))
        assertEquals(ParsedCall.Ok(BrowserSearchAction("x", BrowserEngine.GOOGLE)), parse("q" to "x", "engine" to "Google Search"))
        assertEquals(ParsedCall.Ok(BrowserSearchAction("x")), parse("query" to "x"))
        assertTrue(parse("query" to "x", "engine" to "yahoo") is ParsedCall.Invalid)
        assertTrue(parse("engine" to "google") is ParsedCall.Invalid)
        // Described with two short parameters, and its line only when it is.
        val json = ToolSpecs.of(ToolName.BROWSER_SEARCH).openApiJson()
        assertTrue(json, json.contains("\"browser_search\"") && json.contains("\"query\"") && json.contains("\"engine\""))
        val now = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZoneId.of("America/Toronto"))
        assertTrue(Prompt.system(now, locked = false, browser = true).contains(Prompt.BROWSER))
        assertFalse(Prompt.system(now, locked = false).contains("browser_search"))
        assertEquals("Search Startpage for “café” in your browser", BrowserSearchAction("café", BrowserEngine.STARTPAGE).describe())
        assertTrue(Prompt.textCallHint(listOf(ToolName.BROWSER_SEARCH)).contains("call:browser_search{"))
    }
}
