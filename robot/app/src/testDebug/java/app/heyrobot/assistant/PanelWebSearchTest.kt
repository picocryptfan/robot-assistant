// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Handler
import android.os.Looper
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebAsk
import app.heyrobot.core.web.WebConsentAnswer
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.core.web.WebRoute
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.web.WebStatus
import app.heyrobot.helpers.web.WebSearch
import app.heyrobot.helpers.web.WebStep
import app.heyrobot.sys.FileMemoryVault
import app.heyrobot.sys.SoftMemoryKeys
import app.heyrobot.sys.WebAnswers
import app.heyrobot.sys.WebStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Web search in the panel (PanelWebSearch, from PanelController in 1.1). What QueryCheck is given decides what may go to a
 * search service, so most of this is about that: everything a read tool returned in this conversation (and no web
 * result) counts as personal until the conversation changes, a conversation loaded from History has reads it no longer
 * holds, the screen's text counts only once it went with a question, the cards' content bodies count but not the web
 * search's own, and memory the model was given is named. Then the panel's side of a search: its status only while a turn
 * runs and each step said once, the status cleared after the run whatever happened, a failed or stopped search's reply,
 * and your own "search the web for …" going as your words to the direct call, to Robot's own search once it is set up and
 * on, and a search still going at its deadline failing as timed out (that test waits the real 20 s). The search's steps are
 * posted to the main looper, so the looper drives them. Not covered: answer()'s Found path, which needs Robot's model
 * (WebReply.answer runs BrainOneShots, which has no stand-in).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelWebSearchTest {
    private val scope = MainScope()
    private val looper = shadowOf(Looper.getMainLooper())
    private val state = MutableStateFlow(PanelState("c"))
    private val replies = PanelReplies(state)
    private val screen = PanelScreenShare(scope, state, { true }, { 0L }, { Langs.EN }) { true to true }
    private val chat = mutableListOf<ChatTurn>()
    private val voice = ReplyVoice()

    private var turnSaid: String? = "look that up"
    private var search: (Abort, Action.WebSearch) -> Executed = { _, _ -> throw AssertionError("no search expected") }
    private var direct: (RawToolCall) -> TurnResult = { throw AssertionError("no direct call expected") }
    /** What the port was asked, in order: "say <text>", "finish <id> <reason>", "fail <id> <text>", "leave", "speak <s>". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it
    private val directCalls = mutableListOf<RawToolCall>()
    private val directUntrusted = mutableListOf<Boolean>()
    private val ctx = RuntimeEnvironment.getApplication()

    private val web = PanelWebSearch(state, replies, screen, chat, voice, object : PanelWebSearch.Port {
        override val ctx get() = RuntimeEnvironment.getApplication()
        override val main = Handler(Looper.getMainLooper())
        override val settings = RobotSettings()
        override val turnSaid get() = this@PanelWebSearchTest.turnSaid
        override val turnLangs = Langs.EN
        override fun search(abort: Abort, action: Action.WebSearch) = this@PanelWebSearchTest.search(abort, action)
        override suspend fun runDirect(replyId: String, call: RawToolCall, untrusted: Boolean): TurnResult {
            directCalls += call
            directUntrusted += untrusted
            return direct(call)
        }
        override fun said(p: Phrase) = "said: " + p.render(Langs.EN)
        override fun sayInto(replyId: String, text: Phrase) { calls += "say ${text.render(Langs.EN)}" }
        override fun finishReply(replyId: String, reason: DoneReason) { calls += "finish $replyId $reason" }
        override fun failReply(replyId: String, message: String) { calls += "fail $replyId $message" }
        override suspend fun leaveForApp() { calls += "leave" }
        override fun speakSentence(s: String) { calls += "speak $s" }
    })

    // Memory: a real vault with a software key (as MemoryPanelTest), put back afterwards.
    private val dir: File = Files.createTempDirectory("panel-web").toFile()
    private val vault = FileMemoryVault(dir, SoftMemoryKeys())
    private var memory = MemorySettings()
    private val saved = listOf(MemoryPanel.vaultFor, MemoryPanel.settingsFor, MemoryPanel.lockedNow)
    private val savedClock = MemoryPanel.clock
    private val savedZone = MemoryPanel.zone

    @Before fun setUp() {
        MemoryPanel.vaultFor = { vault }
        MemoryPanel.settingsFor = { memory }
        MemoryPanel.lockedNow = { false }
        MemoryPanel.clock = { 1_790_000_000_000L }
        MemoryPanel.zone = { ZoneOffset.UTC }
        MemoryPanel.drop()
        MemoryPanel.newConversation()
    }

    @Suppress("UNCHECKED_CAST")
    @After fun end() {
        scope.cancel()
        MemoryPanel.drop()
        MemoryPanel.newConversation()
        MemoryPanel.vaultFor = saved[0] as () -> app.heyrobot.core.memory.MemoryVault
        MemoryPanel.settingsFor = saved[1] as () -> MemorySettings
        MemoryPanel.lockedNow = saved[2] as () -> Boolean
        MemoryPanel.clock = savedClock
        MemoryPanel.zone = savedZone
        dir.deleteRecursively()
        WebStore.file(ctx).delete()
        WebAnswers.file(ctx).delete()
    }

    private fun idle() = looper.idleFor(Duration.ZERO)

    private fun user(text: String, attachment: Attachment = Attachment.NONE, memoryIds: List<String> = emptyList()) =
        ChatTurn("u${chat.size}", TurnRole.USER, text, 0L, attachment, memoryIds = memoryIds)

    private fun read(vararg results: Map<String, Any?>, body: String? = null) = Executed(ActionStatus.DONE, "Found it", results = results.toList(), body = body)

    private fun ctx(): QueryContext = web.context()

    @Test fun whatReadToolsReturnedIsPersonalForQueryCheckButAWebResultIsNot() {
        chat += user("what's on my calendar")
        web.noteRead(read(mapOf("title" to "Oncology with Dr. Tremblay", "where" to mapOf("place" to "Moncton Hospital")), body = "Tuesday 10:00"))
        // A search's own results never count as what you read (they are content, carded by QueryCheck elsewhere).
        web.noteRead(read(mapOf("title" to "Rituximab trial results")).copy(web = WebOutcome.Failed(null, WebFail.TIMEOUT)))
        // Nothing returned: nothing noted, not even the card's body.
        web.noteRead(Executed(ActionStatus.DONE, "Nothing found", results = emptyList(), body = "No events"))
        web.noteRead(Executed(ActionStatus.DONE, "Done", body = "Lights on"))
        val c = ctx()
        assertTrue(c.personal.toString(), c.personal.containsAll(listOf("Oncology with Dr. Tremblay", "Moncton Hospital", "Tuesday 10:00")))
        assertFalse(c.personal.toString(), c.personal.any { it.contains("Rituximab") || it == "No events" || it == "Lights on" })
        assertEquals(listOf("what's on my calendar", "look that up"), c.said)
    }

    @Test fun aNewConversationForgetsItsReadsAndOneFromHistoryCountsWhatItNoLongerHolds() {
        chat += user("read my notes")
        chat += ChatTurn("a1", TurnRole.ASSISTANT, "You have a note about Julie.", 0L, actions = listOf(ActionRecord(ToolName.FIND_NOTES, "Notes", ActionStatus.DONE, content = true)))
        web.noteRead(read(mapOf("text" to "Julie's lab results")))
        assertTrue(ctx().personal.contains("Julie's lab results"))
        assertFalse("every read is held: nothing is blind", ctx().blindPersonal)
        web.forgetReads(allKnown = false)
        assertFalse(ctx().personal.contains("Julie's lab results"))
        assertTrue("a loaded conversation's earlier reads aren't held: any word not yours may be from them", ctx().blindPersonal)
        web.forgetReads(allKnown = true)
        assertFalse(ctx().blindPersonal)
    }

    @Test fun theScreensTextCountsOnlyOnceItWentWithAQuestion() {
        assertTrue(screen.onText(listOf("Balance 4,210.55 Account ending 0042"), "com.bank", 0, 1))
        chat += user("hello")
        assertFalse(ctx().untrusted.toString(), ctx().untrusted.any { it.contains("4,210.55") })
        chat += user("what's this", Attachment.SCREEN)
        assertTrue(ctx().untrusted.toString(), ctx().untrusted.any { it.contains("4,210.55") })
    }

    @Test fun theCardsContentBodiesArePersonalButTheWebSearchsOwnIsNot() {
        state.update {
            it.copy(turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", actions = listOf(
                UiAction("n", ToolName.FIND_NOTES, "Notes", ActionStatus.DONE, null, body = "Passport renewal July", content = true),
                UiAction("w", ToolName.WEB_SEARCH, "Searched", ActionStatus.DONE, null, body = "example.org", content = true),
                UiAction("t", ToolName.SET_TIMER, "Timer", ActionStatus.DONE, null, body = "5 minutes")))))
        }
        val p = ctx().personal
        assertTrue(p.toString(), p.contains("Passport renewal July"))
        assertFalse(p.toString(), p.contains("example.org") || p.contains("5 minutes"))
    }

    @Test fun memoryTheModelWasGivenIsNamedForQueryCheck() {
        val r = MemoryPanel.route("Remember that my sister's birthday is May 3", false) ?: throw AssertionError("not a memory command")
        MemoryPanel.command(r, false, true)
        val item = (vault.load() as MemoryLoad.Loaded).file.items.single()
        chat += user("when is it")
        assertEquals(emptyList<String>(), ctx().memory)
        chat += user("and her gift", memoryIds = listOf(item.id))
        assertEquals(listOf(item.text), ctx().memory)
    }

    @Test fun eachStepShowsWhileATurnRunsAndIsSaidOnce() {
        state.update { it.copy(phase = Phase.THINKING, speak = true, turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", streaming = true))) }
        voice.start("r1")
        web.step(WebStep.Searching, "en")
        // From the search's own thread: nothing happens until the main thread runs it.
        assertNull(state.value.helperStatus)
        idle()
        assertEquals(WebStatus.searchingText.render(Langs.EN), state.value.helperStatus)
        assertEquals(listOf("speak said: " + WebStatus.searchingText.render(Langs.EN)), calls)
        web.step(WebStep.Reading(3, 0), "en")
        idle()
        assertEquals(WebStatus.readingText(3, null).render(Langs.EN), state.value.helperStatus)
        web.step(WebStep.Reading(3, 2), "en")
        idle()
        assertEquals(WebStatus.readingText(3, 2).render(Langs.EN), state.value.helperStatus)
        assertEquals(listOf(WebStatus.searchingText, WebStatus.readingText(3)).map { "speak said: " + it.render(Langs.EN) }, calls)
        // Replies not spoken: the status still shows, nothing is said.
        state.update { it.copy(speak = false) }
        web.step(WebStep.Searching, "en")
        idle()
        assertEquals(2, calls.size)
        // No turn running (Stop came first): the status line stays as it was.
        state.update { it.copy(phase = Phase.IDLE, helperStatus = null, speak = true) }
        web.step(WebStep.Reading(4, 0), "en")
        idle()
        assertNull(state.value.helperStatus)
    }

    @Test fun theRunPassesTheSearchsResultOnAndClearsTheStatusWhateverHappened() = runBlocking {
        val done = Executed(ActionStatus.DONE, "Found", web = WebOutcome.Failed(null, WebFail.NO_INTERNET))
        var on: Thread? = null
        search = { _, _ -> on = Thread.currentThread(); done }
        state.update { it.copy(phase = Phase.THINKING, helperStatus = "Reading 3 pages…") }
        assertSame(done, web.run(Action.WebSearch("weather moncton")))
        assertNull(state.value.helperStatus)
        assertTrue("the search runs on a thread of its own", on != null && on != Thread.currentThread())
        state.update { it.copy(helperStatus = "Searching the web…") }
        search = { _, _ -> throw IllegalStateException("socket closed") }
        try {
            web.run(Action.WebSearch("weather moncton"))
            fail("the failure goes on to the turn")
        } catch (e: IllegalStateException) {
            assertEquals("socket closed", e.message)
        }
        assertNull(state.value.helperStatus)
    }

    @Test fun aStoppedSearchEndsTheReplyAndAFailedOneSaysWhy() = runBlocking {
        state.update { it.copy(helperStatus = "Searching the web…") }
        web.answer("r1", "weather", emptyList(), WebOutcome.Failed(null, WebFail.STOPPED))
        assertEquals(listOf("finish r1 CANCELLED"), calls)
        assertNull(state.value.helperStatus)
        calls.clear()
        val failed = WebOutcome.Failed(null, WebFail.TIMEOUT)
        web.answer("r1", "weather", emptyList(), failed)
        assertEquals(listOf("say " + failed.sentence.render(Langs.EN), "finish r1 END"), calls)
    }

    @Test fun yourOwnSearchGoesAsYourWordsAndItsTurnEndsAsItSays() = runBlocking {
        // "Search in my browser for …": the browser search, with your words and the engine you named.
        direct = { TurnResult(DoneReason.END, launchedApp = true) }
        web.direct("r1", WebRoute.Browser("ferry times digby", null), "search in my browser for ferry times digby", emptyList(), untrusted = false)
        assertEquals(RawToolCall(ToolName.BROWSER_SEARCH.id, mapOf("query" to "ferry times digby")), directCalls.single())
        assertEquals(listOf("finish r1 END", "leave"), calls)
        // "Search the web for …" while Robot's own web search is off (as on a new phone): your browser instead.
        calls.clear()
        directCalls.clear()
        direct = { TurnResult(null) }
        web.direct("r2", WebRoute.Search("ferry times digby"), "search the web for ferry times digby", emptyList(), untrusted = true)
        assertEquals(RawToolCall(ToolName.BROWSER_SEARCH.id, mapOf("query" to "ferry times digby")), directCalls.single())
        assertEquals(listOf("finish r2 END"), calls)
        // A failure is the reply's; a search that ran is answered from what it found (here, why it found nothing).
        calls.clear()
        direct = { TurnResult(DoneReason.END, failure = "The search didn't start.") }
        web.direct("r3", WebRoute.Search("ferry"), "search the web for ferry", emptyList(), untrusted = false)
        assertEquals(listOf("fail r3 The search didn't start."), calls)
        calls.clear()
        val failed = WebOutcome.Failed(null, WebFail.NO_INTERNET)
        direct = { TurnResult(DoneReason.END, web = failed) }
        web.direct("r4", WebRoute.Search("ferry"), "search the web for ferry", emptyList(), untrusted = false)
        assertEquals(listOf("say " + failed.sentence.render(Langs.EN), "finish r4 END"), calls)
    }

    /** Robot's own web search set up and on (your SearXNG at home), as BrowserSearchToolTest writes it. */
    private fun webOn() {
        WebStore.file(ctx).parentFile?.mkdirs()
        WebStore.file(ctx).writeText(WebSettings(on = true, service = SearchService.SEARXNG_HOME, searxng = "http://192.168.1.5:8080", askAt = 1_000L).toJson().toString())
    }

    @Test fun withRobotsOwnSearchOnYourWordsGoToItAsTheyAre() = runBlocking {
        webOn()
        direct = { TurnResult(DoneReason.END) }
        web.direct("r1", WebRoute.Search("ferry times digby"), "search the web for ferry times digby", emptyList(), untrusted = true)
        assertEquals(RawToolCall(ToolName.WEB_SEARCH.id, mapOf("query" to "ferry times digby")), directCalls.single())
        assertEquals(listOf(true), directUntrusted)
        assertEquals(listOf("finish r1 END"), calls)
        // "Google …" too: Robot's own search, never an engine named for it.
        directCalls.clear()
        web.direct("r2", WebRoute.Search("ferry times digby", google = true), "google ferry times digby", emptyList(), untrusted = false)
        assertEquals(RawToolCall(ToolName.WEB_SEARCH.id, mapOf("query" to "ferry times digby")), directCalls.single())
        assertEquals(listOf(true, false), directUntrusted)
        // A later "Don't ask again" on a No turned it off: your browser instead.
        directCalls.clear()
        WebAnswers.save(ctx, WebConsentAnswer(WebAsk.FIRST_TIME, off = true, at = 2_000L))
        web.direct("r3", WebRoute.Search("ferry times digby"), "search the web for ferry times digby", emptyList(), untrusted = false)
        assertEquals(RawToolCall(ToolName.BROWSER_SEARCH.id, mapOf("query" to "ferry times digby")), directCalls.single())
    }

    @Test fun aSearchStillGoingAtItsDeadlineFailsAsTimedOutAndIsToldToStop() = runBlocking {
        VoiceLog.resetForTests()
        var given: Abort? = null
        val stopped = CountDownLatch(1)
        search = { a, _ ->
            given = a
            a.onCancel { stopped.countDown() }
            stopped.await(60, TimeUnit.SECONDS)
            Executed(ActionStatus.DONE, "Found it, too late")
        }
        state.update { it.copy(phase = Phase.THINKING, helperStatus = "Reading 3 pages…") }
        val t0 = System.nanoTime()
        val r = web.run(Action.WebSearch("weather moncton"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(20_000L, WebSearch.DEADLINE_MS)
        assertTrue("waited $ms ms", ms >= WebSearch.DEADLINE_MS)
        assertEquals(ActionStatus.FAILED, r.status)
        val o = r.web as WebOutcome.Failed
        assertEquals(WebFail.TIMEOUT, o.why)
        assertEquals(o.sentence.en(), r.detail)
        assertTrue("the search was told to stop", given!!.cancelled)
        assertNull(state.value.helperStatus)
        val e = VoiceLog.entries().single()
        assertEquals(VoiceEvent.WEB_FAILED, e.event)
        assertEquals(WebFail.TIMEOUT.id, e.a)
    }
}
