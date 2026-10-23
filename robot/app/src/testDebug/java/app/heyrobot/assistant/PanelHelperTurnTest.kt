// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.Card
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Cue
import app.heyrobot.core.DoneReason
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperCommand
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Shared
import app.heyrobot.core.ToolName
import app.heyrobot.core.TtsStop
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnView
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.helpers.Pricing
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.helpers.HelperOutcome
import app.heyrobot.helpers.HelperSession
import app.heyrobot.helpers.HelperTurnListener
import app.heyrobot.helpers.HelperTurnReport
import app.heyrobot.voice.SpeechToText
import java.time.ZonedDateTime
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A helper's turn in the panel (PanelHelperTurn, from PanelController in 1.1). What may go to a helper is core
 * HelperContext's and HelperTrust's (HelperContextTest, HelperSettingsTest); here, the panel's side: a question outside a
 * helper's conversation goes alone, never with the conversation before it; the screen goes only after its card is tapped,
 * and a no makes no session at all; a helper joining a personal conversation asks Include, and Start fresh sends none of
 * it; on the lock screen a helper not allowed there (or any helper, during a phone lock for someone else) gets no session,
 * only the locked line, and its conversation ends; without the Network permission only the network card; Robot checking
 * by itself sends exactly the blocks it was given, and a helper it can't reach gives the question back; the app route
 * hands your question to the helper's app. Then (1.1) the answer streamed into its own bubble in its voice without a copied
 * clock, the cost line and sources, a declined answer, a cut-off empty one, RESET ending the conversation, falling back
 * after a failed turn, a trusted helper joining without the card (never an untrusted one), the photo through lastImage, and
 * a conversation reusing its session; each checks what went to the helper against HelperContext's blocks from the same
 * inputs, as PanelController built them before the split. No test covered the panel's side before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelHelperTurnTest {
    private class FakeStt : SpeechToText {
        override var available = true
        override fun start(listener: SpeechToText.Listener, shortAnswer: Boolean) {}
        override fun stop() {}
        override fun cancel() {}
        override fun release() {}
    }

    /**
     * A session that records what it was asked and answers "Paris." ([deltas], piece by piece), then declines if it
     * [declines], and ends with [outcome]; [lastReport]: the turn's numbers.
     */
    private class FakeSession(fresh: Boolean = false) : HelperSession {
        /** Fresh until its first question, as a real session. */
        override var fresh = fresh
        val asked = mutableListOf<List<HelperBlock>>()
        var deltas = listOf("Paris.")
        var declines = false
        var outcome: HelperOutcome = HelperOutcome.Done()
        override var lastReport = HelperTurnReport()
        override val offered = emptySet<ToolName>()
        override suspend fun ask(blocks: List<HelperBlock>, plan: HelperPlan, listener: HelperTurnListener): HelperOutcome {
            asked += blocks
            fresh = false
            deltas.forEach(listener::text)
            if (declines) listener.declined()
            return outcome
        }
        override suspend fun answer(results: List<Map<String, Any?>>, listener: HelperTurnListener): HelperOutcome = HelperOutcome.Done()
        override fun abandon(results: List<Map<String, Any?>>, stopped: Boolean) = Unit
    }

    private val ctx = RuntimeEnvironment.getApplication()
    private val scope = MainScope()
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", streaming = true))))
    private val replies = PanelReplies(state)
    private val voice = ReplyVoice().also { it.start("r1") }
    private val helperMode = PanelHelperMode(scope, state) { false }
    private val screen = PanelScreenShare(scope, state, { true }, { 0L }, { Langs.EN }) { true to true }
    private val cardVoice = PanelCardVoice(state, replies, screen, object : PanelCardVoice.Port {
        override val main = Handler(Looper.getMainLooper())
        override val stt: SpeechToText = FakeStt()
        override val settings = RobotSettings()
        override val speaking = false
        override val shown = true
        override val spokenTurn = false
        override val turnLangs = Langs.EN
        override fun screenLangs() = Langs.EN
        override val routineTapOnly: Pair<String, String>? = null
        override fun wakePaused(paused: Boolean) {}
        override fun notify(text: String) {}
        override fun speakRobot(text: String) {}
        override fun said(p: Phrase) = p.render(Langs.EN)
        override fun speak(text: String) {}
        override fun cue(c: Cue) {}
        override fun confirm(actionId: String, yes: Boolean) {}
        override fun choose(actionId: String, index: Int) {}
    })

    /** A server on your network: no key, its API, a known price (free). */
    private val server = HelperConfig("h2", "home", "My server", "m")
    private var settings = RobotSettings(helpers = listOf(server))
    private val confirmations = Confirmations()
    private var locked = false
    private var lockOn = false
    private var someoneElse = false
    private var network = true
    private var session = FakeSession()
    private var made: HelperLink.Made? = null
    private var ids = 0
    /** Every session asked for: whether it was a conversation. */
    private val sessions = mutableListOf<Boolean>()
    private val cards = mutableListOf<Card>()
    private var handedToApp: String? = "none"
    private var handOff: TurnModel? = null
    /** What the port was asked, in order: "reply <text>", "finish <id> <reason>", "fail <id> <text> <noReply>", "notice <text>". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it
    /** What was said aloud, in order: "helper <sentence> <voice>", "robot <text>", "stop <why>". */
    private val spoken = mutableListOf<String>()
    /** The price each finished reply was given, in millionths of a dollar. */
    private val costs = mutableListOf<Long>()
    private var keySaved = false
    private var lastImage: ByteArray? = null
    private var lastThumb: Bitmap? = null

    private val helperTurn = PanelHelperTurn(scope, state, replies, voice, helperMode, screen, cardVoice, object : PanelHelperTurn.Port {
        override val ctx get() = this@PanelHelperTurnTest.ctx
        override val settings get() = this@PanelHelperTurnTest.settings
        override val turnLangs = Langs.EN
        override val buttonLangs = Langs.EN
        override fun screenLangs() = Langs.EN
        override val confirmations get() = this@PanelHelperTurnTest.confirmations
        override fun newId() = "id${ids++}"
        override fun locked() = locked
        override fun phoneLockOn() = lockOn
        override fun someoneElse() = someoneElse
        override fun networkAllowed() = network
        override fun keySaved(h: HelperConfig?) = keySaved
        override fun session(s: RobotSettings, h: HelperConfig, conversation: Boolean): HelperLink.Made {
            sessions += conversation
            return made ?: HelperLink.Made.Ok(session)
        }
        override val userRegion = "CA"
        override val lastImage get() = this@PanelHelperTurnTest.lastImage
        override val lastThumb get() = this@PanelHelperTurnTest.lastThumb
        override fun handedToApp(text: String?) { handedToApp = text }
        override fun said(p: Phrase) = p.render(Langs.EN)
        override fun notify(p: Phrase) { calls += "notice ${p.render(Langs.EN)}" }
        override fun speakRobot(text: String) { spoken += "robot $text" }
        override fun speakSentence(s: String, byHelper: Boolean, voice: String?) { spoken += (if (byHelper) "helper" else "robot") + " $s $voice" }
        override fun stopVoice(why: TtsStop) { spoken += "stop $why" }
        override fun sayInto(replyId: String, text: Phrase) { replies.setText(replyId, text.render(Langs.EN)) }
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) { calls += "reply ${text.render(Langs.EN)}" }
        override fun finishReply(replyId: String, reason: DoneReason, costMicros: Long) {
            calls += "finish $replyId $reason"
            costs += costMicros
        }
        override fun failReply(replyId: String, message: String, noReply: Boolean) { calls += "fail $replyId $message $noReply" }
        override fun closeLiveConversation() {}
        override suspend fun leaveForApp() { calls += "leave" }
        override fun view(replyId: String): TurnView = view(replyId, "Claude")
        override fun view(replyId: String, helper: String): TurnView = object : TurnView {
            override fun phase(p: TurnPhase) {}
            override fun card(c: Card) { cards += c }
            override val text get() = replies.text(replyId).orEmpty()
            override fun replaceText(text: String) = replies.setText(replyId, text)
            override fun paragraphBreak() {}
            override fun say(sentence: String) {}
        }
        // As TurnRunner ends a turn with no calls: done for its reason, or failed with the message.
        override suspend fun helperRun(replyId: String, rounds: TurnModel, h: HelperConfig, s: RobotSettings, asked: Set<ToolName>, session: HelperSession): TurnResult =
            when (val o = rounds.ask()) {
                is ModelOutcome.Done -> TurnResult(o.reason)
                is ModelOutcome.Failed -> TurnResult(null, failure = o.message)
                is ModelOutcome.Calls -> throw AssertionError("no calls expected")
            }
        override suspend fun handOffRun(replyId: String, model: TurnModel): TurnResult {
            handOff = model
            return TurnResult(DoneReason.END, launchedApp = true)
        }
    })

    private val words = HelperWords(HelperWho.of(server))
    private val savedSettingsFor = MemoryPanel.settingsFor

    @Before fun setUp() { MemoryPanel.settingsFor = { MemorySettings() } }

    @After fun tearDown() {
        MemoryPanel.settingsFor = savedSettingsFor
        scope.cancel()
    }

    /** Earlier turns that are personal: a screen question and Robot's answer about it. */
    private val personalHistory = listOf(
        ChatTurn("u0", TurnRole.USER, "what does this say", 1L, attachment = Attachment.SCREEN),
        ChatTurn("a0", TurnRole.ASSISTANT, "Your balance is 4471 dollars.", 2L),
    )

    private fun texts(blocks: List<HelperBlock>) = blocks.filterIsInstance<HelperBlock.Text>().map { it.text }

    private fun ask(cmd: HelperCommand?, said: String, attachment: Attachment = Attachment.NONE, sText: String? = null, history: List<ChatTurn> = emptyList(),
        h: HelperConfig = server) =
        // A card nobody answers fails the test rather than hanging it.
        runBlocking { withTimeout(5_000) { helperTurn.run("r1", cmd, h, said, attachment, sText, null, null, history, voiceAsked = false) } }

    /**
     * Runs [cmd] until its card waits, answers it [yes], and lets the turn end; the card. [noSession]: no session is made
     * while it waits (a share card comes first; a joining helper's session is made before its Include card, as before).
     */
    private fun askThroughCard(cmd: HelperCommand?, said: String, yes: Boolean, attachment: Attachment = Attachment.NONE, sText: String? = null,
        history: List<ChatTurn> = emptyList(), noSession: Boolean = true, h: HelperConfig = server, image: ByteArray? = null, thumb: Bitmap? = null): UiAction = runBlocking {
        val job = launch { helperTurn.run("r1", cmd, h, said, attachment, sText, image, thumb, history, voiceAsked = false) }
        withTimeout(5_000) { while (!confirmations.waiting) delay(5) }
        val card = replies.pendingCard()!!
        // Nothing has gone while the card waits.
        assertTrue(session.asked.isEmpty())
        if (noSession) assertTrue(sessions.isEmpty())
        confirmations.answer(card.id, yes)
        job.join()
        card
    }

    @Test fun aQuestionOutsideAHelpersConversationGoesAloneEvenAfterAPersonalOne() {
        ask(HelperCommand.Ask("what is the capital of France", helper = server.id), "ask my server what is the capital of France", history = personalHistory)
        assertEquals(listOf(false), sessions)
        val sent = texts(session.asked.single())
        assertEquals(2, sent.size)
        assertEquals("what is the capital of France", sent.last())
        assertTrue(sent.toString(), sent.none { it.contains("4471") || it.contains("what does this say") })
        assertEquals("Paris.", replies.text("r1"))
        assertEquals(listOf("finish r1 END"), calls)
    }

    @Test fun theScreenGoesOnlyAfterItsCardIsTapped() {
        val no = askThroughCard(HelperCommand.Ask("what is this", extra = Attachment.SCREEN, helper = server.id), "ask my server about my screen", yes = false,
            sText = "Balance 4471")
        assertEquals(ToolName.ASK_HELPER, no.tool)
        assertEquals("Balance 4471", no.body)
        assertTrue(sessions.isEmpty() && session.asked.isEmpty())
        assertEquals(listOf("reply ${words.nothingSentText.render(Langs.EN)}"), calls)
        assertEquals(ActionStatus.DECLINED, cards.last().status)
        calls.clear()
        askThroughCard(HelperCommand.Ask("what is this", extra = Attachment.SCREEN, helper = server.id), "ask my server about my screen", yes = true,
            sText = "Balance 4471")
        assertEquals(listOf(false), sessions)
        val sent = texts(session.asked.single())
        assertTrue(sent.toString(), sent.any { it.contains("Balance 4471") })
        assertEquals("what is this", sent.last())
        assertEquals(ActionStatus.DONE, cards.last().status)
    }

    @Test fun joiningAPersonalConversationAsksIncludeAndStartFreshSendsNoneOfIt() {
        session = FakeSession(fresh = true)
        val history = personalHistory + ChatTurn("u1", TurnRole.USER, "go get my server", 3L)
        // The helper's conversation began with the turns before it.
        helperMode.start(server, joinedAt = 2)
        askThroughCard(HelperCommand.Join("and then?", helper = server.id), "go get my server and then?", yes = false, history = history, noSession = false)
        assertEquals(listOf(true), sessions)
        val fresh = texts(session.asked.single())
        assertTrue(fresh.toString(), fresh.none { it.contains("4471") || it.contains("joining a conversation") })
        assertEquals("and then?", fresh.last())
        // Include: the conversation before it goes.
        helperMode.end()
        session = FakeSession(fresh = true)
        sessions.clear()
        helperMode.start(server, joinedAt = 2)
        askThroughCard(HelperCommand.Join("and then?", helper = server.id), "go get my server and then?", yes = true, history = history, noSession = false)
        val included = texts(session.asked.single())
        assertTrue(included.toString(), included.any { it.contains("joining a conversation") && it.contains("4471") })
    }

    @Test fun onTheLockScreenOnlyAHelperAllowedThereAndNeverForSomeoneElse() {
        locked = true
        helperMode.start(server, joinedAt = 0)
        ask(null, "what is the capital of France")
        assertTrue(sessions.isEmpty())
        assertEquals(listOf("reply ${words.lockedText.render(Langs.EN)}"), calls)
        assertNull(state.value.helperMode)
        // Allowed on the lock screen, but during a phone lock for someone else: still nothing goes.
        settings = settings.copy(helpers = listOf(server.copy(locked = true)))
        someoneElse = true
        lockOn = true
        calls.clear()
        runBlocking { helperTurn.run("r1", null, server.copy(locked = true), "what is the capital of France", Attachment.NONE, null, null, null, emptyList(), false) }
        assertTrue(sessions.isEmpty())
        assertEquals(listOf("reply ${LockStrings.closedNow().render(Langs.EN)}"), calls)
        // Allowed there, and no one else's lock: it goes.
        someoneElse = false
        calls.clear()
        runBlocking { helperTurn.run("r1", null, server.copy(locked = true), "what is the capital of France", Attachment.NONE, null, null, null, emptyList(), false) }
        assertEquals(listOf(false), sessions)
    }

    @Test fun robotCheckingByItselfIsHeldToTheSameLockAndNetworkRules() = runBlocking {
        val blocks = listOf(HelperBlock.Text("context"), HelperBlock.Text("what is the capital of France"))
        locked = true
        assertFalse(helperTurn.escalate("r1", "what is the capital of France", blocks, false, reuseBubble = true, h = server))
        assertTrue(sessions.isEmpty())
        assertEquals(listOf("reply ${words.lockedText.render(Langs.EN)}"), calls)
        locked = false
        network = false
        calls.clear()
        assertFalse(helperTurn.escalate("r1", "what is the capital of France", blocks, false, reuseBubble = true, h = server))
        assertTrue(sessions.isEmpty())
        assertEquals(ActionStatus.FAILED, cards.single().status)
        assertEquals(listOf("finish r1 END"), calls)
        network = true
        calls.clear()
        assertFalse(helperTurn.escalate("r1", "what is the capital of France", blocks, false, reuseBubble = true, h = server))
        // Exactly what it was given goes.
        assertEquals(listOf(false), sessions)
        assertTrue(session.asked.single() === blocks || session.asked.single() == blocks)
        assertEquals(server.id, state.value.turns.single { it.id == "r1" }.helper)
    }

    @Test fun aHelperRobotCantReachGivesTheQuestionBackOnlyWhenItMay() = runBlocking {
        made = HelperLink.Made.Not(HelperFailure.UNREACHABLE)
        val blocks = listOf(HelperBlock.Text("q"))
        // Before Robot's own model ran (Automatic): the reply is Robot's again, with a notice why.
        assertTrue(helperTurn.escalate("r1", "q", blocks, false, reuseBubble = true, h = server, fallBack = true))
        assertFalse(state.value.turns.single { it.id == "r1" }.fromHelper)
        assertTrue(calls.single().startsWith("notice "))
        calls.clear()
        // Otherwise the failure is the reply's, and it isn't counted as Robot failing to answer.
        assertFalse(helperTurn.escalate("r1", "q", blocks, false, reuseBubble = true, h = server))
        assertEquals(1, calls.size)
        assertTrue(calls.single(), calls.single().startsWith("fail r1 ") && calls.single().endsWith(" false"))
    }

    @Test fun theAppRouteHandsYourQuestionToTheHelpersApp() {
        val app = HelperConfig("h1", "anthropic", "Jarvis", "m")
        runBlocking { helperTurn.run("r1", null, app, "what is the capital of France", Attachment.NONE, null, null, null, emptyList(), false) }
        assertTrue(sessions.isEmpty())
        assertEquals("what is the capital of France", handedToApp)
        val call = (runBlocking { handOff!!.ask() } as ModelOutcome.Calls).calls.single()
        assertEquals(ToolName.ASK_HELPER.id, call.name)
        assertEquals(mapOf("question" to "what is the capital of France", "helper" to "h1"), call.args)
        assertEquals(listOf("finish r1 END", "leave"), calls)
    }

    // ---------------------------------------------------------------- 1.1 tests: what the split's own tests left uncovered

    /** A helper with a price Robot can't look up (not a server on your network): its cost line isn't free. */
    private val jarvis = HelperConfig("h1", "anthropic", "Jarvis", "m")

    private fun reply(id: String) = state.value.turns.single { it.id == id }

    /** Blocks as values: a text's words, a picture's bytes (the same array, compared by identity). */
    private fun values(blocks: List<HelperBlock>): List<Any> = blocks.map {
        when (it) {
            is HelperBlock.Text -> it.text
            is HelperBlock.Image -> it.jpeg
        }
    }

    /**
     * [sent] is exactly [expected] (HelperContext's blocks from the same inputs, as PanelController built them before the
     * split), except the first block's date, which is today's: there both are the context line for Canada.
     */
    private fun assertSent(expected: List<HelperBlock>, sent: List<HelperBlock>) {
        assertEquals(expected.size, sent.size)
        for (b in listOf(expected.first(), sent.first())) {
            val t = (b as HelperBlock.Text).text
            assertTrue(t, t.startsWith("[Today is ") && t.endsWith(" The user is in Canada.]"))
        }
        assertEquals(values(expected.drop(1)), values(sent.drop(1)))
    }

    private fun now() = ZonedDateTime.now()

    @Test fun aHelpersAnswerStreamsIntoItsOwnBubbleInItsVoiceWithoutTheClockItCopied() = runBlocking {
        state.update { it.copy(speak = true) }
        session.deltas = listOf("[Now 14:05] The capital ", "of France is Paris. ", "It sits on the Seine.")
        val blocks = listOf(HelperBlock.Text("context"), HelperBlock.Text("what is the capital of France"))
        assertFalse(helperTurn.escalate("r1", "what is the capital of France", blocks, false, reuseBubble = false, h = server))
        // A bubble of its own, under the helper's name; Robot's reply is left as it was.
        val b = reply("id0")
        assertEquals("The capital of France is Paris. It sits on the Seine.", b.text)
        assertTrue(b.fromHelper)
        assertEquals(server.id, b.helper)
        assertEquals(server.name, b.by)
        assertTrue(b.home)
        assertEquals("", reply("r1").text)
        assertFalse(reply("r1").fromHelper)
        assertEquals(listOf("finish id0 END"), calls)
        // "Checking with …" in Robot's voice, then the answer in the helper's, never the copied clock.
        assertEquals("robot ${words.checkingSaid.render(Langs.EN)}", spoken.first())
        val helperSaid = spoken.drop(1)
        assertTrue(spoken.toString(), helperSaid.isNotEmpty() && helperSaid.all { it.startsWith("helper ") && it.endsWith(" ${server.voice}") })
        assertTrue(spoken.toString(), helperSaid.none { it.contains("14:05") })
        assertTrue(spoken.toString(), b.text.startsWith(helperSaid.joinToString(" ") { it.removePrefix("helper ").removeSuffix(" ${server.voice}") }))
        assertTrue(session.asked.single() === blocks)
    }

    @Test fun theCostLineSaysWhatAnAnswerCostAndItsSourcesShowOnlyWhenItSearched() = runBlocking {
        val blocks = listOf(HelperBlock.Text("q"))
        settings = settings.copy(helperShowCost = true)
        suspend fun answer(h: HelperConfig, report: HelperTurnReport): UiTurn {
            session.lastReport = report
            val before = ids
            helperTurn.escalate(null, "q", blocks, false, reuseBubble = false, h = h)
            // Exactly what it was given goes.
            assertTrue(session.asked.last() === blocks)
            return reply("id$before")
        }
        val paid = answer(jarvis, HelperTurnReport(dollars = 0.0123, requests = 2, searches = 1, domains = listOf("nasa.gov")))
        assertEquals(Pricing.lineText(0.0123, free = false)!!.render(Langs.EN), paid.cost)
        assertEquals(listOf("nasa.gov"), paid.sources)
        assertEquals(Pricing.micros(0.0123), costs.last())
        // Domains without a search are not its sources.
        assertEquals(emptyList<String>(), answer(jarvis, HelperTurnReport(dollars = 0.0123, requests = 2, domains = listOf("nasa.gov"))).sources)
        // A price Robot couldn't tell: "cost unknown", and nothing counted.
        assertEquals(PanelStrings.costUnknown().render(Langs.EN), answer(jarvis, HelperTurnReport(dollars = 0.5, requests = 1, costKnown = false)).cost)
        assertEquals(0L, costs.last())
        // No request made, a free server, or the line turned off: none.
        assertNull(answer(jarvis, HelperTurnReport(requests = 0)).cost)
        assertNull(answer(server, HelperTurnReport(requests = 1)).cost)
        settings = settings.copy(helperShowCost = false)
        assertNull(answer(jarvis, HelperTurnReport(dollars = 0.0123, requests = 2)).cost)
        assertEquals(Pricing.micros(0.0123), costs.last())
    }

    @Test fun aDeclinedAnswerBecomesRobotsOwnWordsInRobotsVoice() {
        state.update { it.copy(speak = true) }
        session.deltas = listOf("Here is how you ")
        session.declines = true
        session.outcome = HelperOutcome.Declined
        ask(HelperCommand.Ask("how do I pick a lock", helper = server.id), "ask my server how do I pick a lock")
        assertSent(HelperContext.oneShot("how do I pick a lock", now(), "CA"), session.asked.single())
        val r = reply("r1")
        assertEquals(words.declinedText.render(Langs.EN), r.text)
        assertFalse(r.fromHelper)
        assertNull(r.helper)
        assertEquals(listOf("stop ${TtsStop.OTHER}", "robot ${words.declinedText.render(Langs.EN)}"), spoken)
        assertEquals(listOf("finish r1 END"), calls)
    }

    @Test fun anAnswerCutOffBeforeItSaidAnythingSaysSoAndOneWithWordsKeepsThem() {
        session.deltas = emptyList()
        session.outcome = HelperOutcome.Done(cut = true)
        ask(HelperCommand.Ask("explain everything", helper = server.id), "ask my server explain everything")
        assertSent(HelperContext.oneShot("explain everything", now(), "CA"), session.asked.single())
        assertEquals(words.cutEmptyText.render(Langs.EN), reply("r1").text)
        assertFalse(reply("r1").fromHelper)
        assertEquals(listOf("finish r1 LENGTH"), calls)
        calls.clear()
        replies.setText("r1", "")
        session.deltas = listOf("Everything began ")
        ask(HelperCommand.Ask("explain everything", helper = server.id), "ask my server explain everything")
        assertEquals("Everything began ", reply("r1").text)
        assertTrue(reply("r1").fromHelper)
        assertEquals(listOf("finish r1 LENGTH"), calls)
    }

    @Test fun aConversationRobotCanNoLongerContinueStartsOverAndAnotherFailureKeepsIt() {
        state.update { it.copy(speak = true) }
        val reset = HelperFailure.RESET.sayText(HelperWho.of(server)).render(Langs.EN)
        session.outcome = HelperOutcome.Failed(HelperFailure.RESET)
        helperMode.start(server, joinedAt = 0)
        ask(null, "and then?")
        assertEquals(listOf(true), sessions)
        // A conversation already under way: only the question goes.
        assertEquals(values(HelperContext.next("and then?")), values(session.asked.single()))
        assertNull(state.value.helperMode)
        assertNull(helperMode.session)
        assertEquals(listOf("fail r1 $reset true"), calls)
        assertEquals("robot $reset", spoken.last())
        // Busy: said and failed, and the conversation (and its session) stays.
        calls.clear()
        val busy = HelperFailure.OVERLOADED.sayText(HelperWho.of(server)).render(Langs.EN)
        session.outcome = HelperOutcome.Failed(HelperFailure.OVERLOADED)
        helperMode.start(server, joinedAt = 0)
        ask(null, "and then?")
        assertEquals(values(HelperContext.next("and then?")), values(session.asked.last()))
        assertEquals(server.id, state.value.helperMode)
        assertTrue(helperMode.session === session)
        assertEquals(listOf("fail r1 $busy true"), calls)
    }

    @Test fun aHelperThatFailsBeforeItSaidAnythingGivesTheQuestionBackOnlyWhenItMay() = runBlocking {
        val blocks = listOf(HelperBlock.Text("q"))
        val unreachable = HelperFailure.UNREACHABLE.sayText(HelperWho.of(server))
        session.deltas = emptyList()
        session.outcome = HelperOutcome.Failed(HelperFailure.UNREACHABLE)
        // Automatic, before Robot's own model ran: Robot's reply again, emptied, and a notice why.
        replies.setText("r1", "Let me see.")
        assertTrue(helperTurn.escalate("r1", "q", blocks, false, reuseBubble = true, h = server, fallBack = true))
        val r = reply("r1")
        assertFalse(r.fromHelper)
        assertEquals("", r.text)
        assertEquals(Phase.THINKING, state.value.phase)
        assertNull(state.value.helperStatus)
        assertEquals(listOf("notice ${PanelStrings.answeredInstead("why" to unreachable).render(Langs.EN)}"), calls)
        // It had begun to answer: the failure is the reply's.
        calls.clear()
        session.deltas = listOf("Par")
        assertFalse(helperTurn.escalate("r1", "q", blocks, false, reuseBubble = true, h = server, fallBack = true))
        assertEquals(listOf("fail r1 ${unreachable.render(Langs.EN)} true"), calls)
        // Not before Robot's model (no fallBack), or a failure that isn't about reaching it: the reply's too.
        calls.clear()
        replies.setText("r1", "")
        session.deltas = emptyList()
        assertFalse(helperTurn.escalate("r1", "q", blocks, false, reuseBubble = true, h = server))
        assertEquals(listOf("fail r1 ${unreachable.render(Langs.EN)} true"), calls)
        calls.clear()
        session.outcome = HelperOutcome.Failed(HelperFailure.RATE_LIMITED)
        assertFalse(helperTurn.escalate("r1", "q", blocks, false, reuseBubble = true, h = server, fallBack = true))
        assertEquals(listOf("fail r1 ${HelperFailure.RATE_LIMITED.sayText(HelperWho.of(server)).render(Langs.EN)} true"), calls)
        // Each time, exactly what it was given went.
        assertEquals(4, session.asked.size)
        assertTrue(session.asked.all { it === blocks })
    }

    /** A conversation that is personal without a screen or photo in it: what Robot found in your notes. */
    private val notesHistory = listOf(
        ChatTurn("u0", TurnRole.USER, "what's in my notes", 1L),
        ChatTurn("a0", TurnRole.ASSISTANT, "Your passport renewal is in July.", 2L,
            actions = listOf(ActionRecord(ToolName.FIND_NOTES, "Notes", ActionStatus.DONE, content = true))),
    )

    /** "Go get …" [h] with [history] before it: answered by itself (no card) or through the card, answered [yes]; what went. */
    private fun join(h: HelperConfig, history: List<ChatTurn>, misheard: Boolean = false, card: Boolean, yes: Boolean = true): List<HelperBlock> {
        helperMode.end()
        session = FakeSession(fresh = true)
        sessions.clear()
        val cmd = HelperCommand.Join("and then?", misheard = misheard, helper = h.id)
        if (card) askThroughCard(cmd, "go get ${h.name} and then?", yes = yes, history = history, noSession = false, h = h)
        else ask(cmd, "go get ${h.name} and then?", history = history, h = h)
        assertEquals(listOf(true), sessions)
        return session.asked.single()
    }

    @Test fun aTrustedHelperJoinsWithoutTheCardAndAnUntrustedOneNeverDoes() {
        val trusted = server.copy(trust = true)
        settings = RobotSettings(helpers = listOf(trusted))
        // Called by its name, nothing captured in it: the conversation goes, with no card.
        assertSent(HelperContext.join(notesHistory, "and then?", now(), "CA", include = true), join(trusted, notesHistory, card = false))
        assertTrue(cards.isEmpty())
        // Not trusted: the card, and Start fresh sends none of it.
        settings = RobotSettings(helpers = listOf(server))
        assertSent(HelperContext.join(notesHistory, "and then?", now(), "CA", include = false), join(server, notesHistory, card = true, yes = false))
        assertEquals(HelperContext.includeQuestion(notesHistory), cards.last().summary)
        // Trusted, but a screen was in it, or the name was only heard: the card too.
        settings = RobotSettings(helpers = listOf(trusted))
        assertSent(HelperContext.join(personalHistory, "and then?", now(), "CA", include = true), join(trusted, personalHistory, card = true))
        assertSent(HelperContext.join(notesHistory, "and then?", now(), "CA", include = false, misheard = true),
            join(trusted, notesHistory, misheard = true, card = true, yes = false))
    }

    @Test fun whatRobotRemembersJoinsATrustedHelperOnlyWithMemoryForTrustedHelpers() {
        val trusted = server.copy(trust = true)
        settings = RobotSettings(helpers = listOf(trusted))
        val remembered = listOf(ChatTurn("u0", TurnRole.USER, "when is my sister's birthday", 1L),
            ChatTurn("a0", TurnRole.ASSISTANT, "May 3.", 2L, memoryIds = listOf("m1")))
        assertSent(HelperContext.join(remembered, "and then?", now(), "CA", include = false), join(trusted, remembered, card = true, yes = false))
        MemoryPanel.settingsFor = { MemorySettings(helpers = HelperMemory.TRUSTED_HELPERS) }
        assertSent(HelperContext.join(remembered, "and then?", now(), "CA", include = true), join(trusted, remembered, card = false))
    }

    @Test fun thePhotoAskedAboutAfterwardsGoesFromTheConversationOnlyAfterItsCard() {
        val photo = byteArrayOf(1, 2, 3)
        val thumb = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        lastImage = photo
        lastThumb = thumb
        val card = askThroughCard(HelperCommand.Ask("what is this", extra = Attachment.PHOTO, helper = server.id), "ask my server about the photo", yes = true)
        assertTrue(card.picture === thumb)
        assertNull(card.body)
        assertEquals(PanelStrings.sendTo("what" to PanelStrings.thePhoto(), "name" to server.name).render(Langs.EN), card.summary)
        assertSent(HelperContext.oneShot("what is this", now(), "CA", Shared(image = photo)), session.asked.single())
        // The photo asked about now goes, not the conversation's last one.
        session = FakeSession()
        sessions.clear()
        val taken = byteArrayOf(4, 5)
        val takenThumb = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = askThroughCard(HelperCommand.Ask("and this?", helper = server.id), "ask my server and this?", yes = true, attachment = Attachment.PHOTO,
            image = taken, thumb = takenThumb)
        assertTrue(second.picture === takenThumb)
        assertSent(HelperContext.oneShot("and this?", now(), "CA", Shared(image = taken)), session.asked.single())
        // No photo at all: nothing is asked.
        lastImage = null
        lastThumb = null
        session = FakeSession()
        sessions.clear()
        calls.clear()
        ask(HelperCommand.Ask("what is this", extra = Attachment.PHOTO, helper = server.id), "ask my server about the photo")
        assertTrue(sessions.isEmpty() && session.asked.isEmpty())
        assertEquals(listOf("reply ${PanelStrings.noScreenOrPhoto("name" to server.name).render(Langs.EN)}"), calls)
    }

    @Test fun aHelpersConversationKeepsItsSessionAndSendsOnlyTheNextQuestion() {
        session = FakeSession(fresh = true)
        helperMode.start(server, joinedAt = 0)
        ask(null, "who wrote Hamlet")
        assertSent(HelperContext.join(emptyList(), "who wrote Hamlet", now(), "CA"), session.asked.single())
        assertTrue(helperMode.session === session)
        // The next question: the same session, and only the question.
        ask(null, "and when?")
        assertEquals(listOf(true), sessions)
        assertEquals(2, session.asked.size)
        assertEquals(values(HelperContext.next("and when?")), values(session.asked.last()))
        assertTrue(helperMode.session === session)
        assertEquals(server.id, state.value.helperMode)
    }
}
