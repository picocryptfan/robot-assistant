// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.memory.MemoryChoice
import app.heyrobot.core.helpers.QuestionKind
import app.heyrobot.core.helpers.HelperChoice
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationGuard
import app.heyrobot.core.EscalationTrigger
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperContext
import app.heyrobot.core.Personal
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When Robot brings a helper in by itself (PanelEscalation, from PanelController in 1.1). The policy is core
 * EscalationPolicy's and what may go HelperContext's and HelperTrust's (EscalationTest, HelperContextTest); here, the
 * panel's side: Automatic sends only your question from a personal conversation, and the last turns from one that isn't;
 * a screen question is offered with its screen frozen in the payload, never sent by itself (unless "Send personal content
 * to helpers automatically" is on); on the lock screen a helper not allowed there is neither asked nor offered, only the
 * unlock line; after "not Jarvis" nothing is; Robot's own answer comes first when the model handed over without a word,
 * and one that stands ends the turn; a used-up budget turns Automatic into an offer, said once a day; the pre-router's
 * strong signal asks the helper before Robot's model, with the same rule for what goes, at most once a turn. Then (1.1)
 * "I'm not sure" with neither an API nor an installed app, Robot's own answer failing, finding something on the web (with
 * Automatic only), opening an app, or being stopped, and an own answer that needs today's sources re-decided into an offer
 * for a helper that can't search. No test covered the panel's side of escalation before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelEscalationTest {
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", streaming = true))))
    private val replies = PanelReplies(state)
    private val chat = mutableListOf<ChatTurn>()
    /** A server on your network: no key, its API, a known price (free); "When Robot needs help" left as it is (Automatic). */
    private val server = HelperConfig("h2", "home", "My server", "m")
    private var settings = RobotSettings(helpers = listOf(server))
    private var locked = false
    private var budget = false
    private var declined = false
    /** What escalate was given, and what it returns (false: the helper answered). */
    private class Asked(val replyId: String?, val blocks: List<HelperBlock>, val reuseBubble: Boolean, val fallBack: Boolean)
    private val asked = mutableListOf<Asked>()
    private var fellBack = false
    /** The helper Robot is expected to ask. */
    private var asks = server
    private val offered = mutableListOf<PanelOffers.Pending>()
    private val webFound = mutableListOf<WebOutcome>()
    /** What the port was asked, in order: "say <text>", "finish <id> <reason>", "drop <id>", "fail <id> <text>", "notice <text>", "web", "leave". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    private val escalation = PanelEscalation(state, chat, object : PanelEscalation.Port {
        override val ctx get() = RuntimeEnvironment.getApplication()
        override val settings get() = this@PanelEscalationTest.settings
        override val turnLangs = Langs.EN
        override fun said(p: Phrase) = p.render(Langs.EN)
        override fun locked() = locked
        override fun keySaved(h: HelperConfig?) = false
        override suspend fun budgetReached(s: RobotSettings, h: HelperConfig?) = budget
        override val helperDeclined get() = declined
        override val userRegion = "CA"
        override fun personalNow(replyId: String?): Set<Personal> = HelperContext.personal(chat)
        override fun notify(text: String) { calls += "notice $text" }
        override fun speakRobot(text: String) {}
        override fun sayInto(replyId: String, text: Phrase) { calls += "say ${text.render(Langs.EN)}" }
        override fun finishReply(replyId: String, reason: DoneReason) { calls += "finish $replyId $reason" }
        override fun finishOrDrop(replyId: String) { calls += "drop $replyId" }
        override fun failReply(replyId: String, message: String) { calls += "fail $replyId $message" }
        override suspend fun leaveForApp() { calls += "leave" }
        override suspend fun webAnswer(replyId: String, question: String, history: List<ChatTurn>, found: WebOutcome) {
            calls += "web"
            webFound += found
        }
        override fun offer(replyId: String, o: PanelOffers.Pending) { offered += o }
        override suspend fun escalate(replyId: String?, question: String, blocks: List<HelperBlock>, voiceAsked: Boolean, reuseBubble: Boolean, h: HelperConfig,
            fallBack: Boolean): Boolean {
            assertEquals(asks.id, h.id)
            asked += Asked(replyId, blocks, reuseBubble, fallBack)
            replyId?.let { replies.setText(it, "The helper's answer.") }
            return fellBack
        }
        override suspend fun rememberedChoice(kind: QuestionKind) = remembered[kind]
        override suspend fun askChoice(replyId: String, h: HelperConfig): HelperChoice.Picked? {
            cardsFor += h.id
            return picked
        }
        override suspend fun handOff(replyId: String, question: String, h: HelperConfig) { calls += "app ${h.id} $question" }
        override fun keepOffer(replyId: String, k: KeepChoice) { keeps += k }
    })
    private val remembered = HashMap<QuestionKind, MemoryChoice>()
    private var picked: HelperChoice.Picked? = null
    private val cardsFor = mutableListOf<String>()
    private val keeps = mutableListOf<KeepChoice>()
    private val hard = "explain why the sky is blue step by step"
    private val manyParts = "how does a heat pump work and why is it cold outside and when should I change the filter"
    private fun before(q: String = hard, s: RobotSettings = settings, guard: EscalationGuard = EscalationGuard(), target: HelperConfig? = server, attachment: Attachment = Attachment.NONE) =
        runBlocking { this@PanelEscalationTest.settings = s; escalation.beforeModel("r1", q, chat.toList(), attachment, false, guard, s, target, false, false) }
    private val askFirst get() = settings.copy(help = Escalation.ASK)

    // ---- "Try it here or ask your helper"

    @Test fun beyondRobot_itSaysSoAndOffers_withoutSendingAWord() {
        val b = before(s = askFirst)
        assertEquals(listOf(server.id), cardsFor)
        assertEquals(0, asked.size)
        // Silence: Robot tries, the offer stays under its answer.
        assertEquals(EscalationTrigger.PRE_ROUTER, b!!.offerAfter)
        assertEquals(true, b.hold)
    }

    /** phone-rc3-2: these three, typed, a server with no key, Ask first. The same with a spoken question. */
    @Test fun thePhonesQuestions_goToTheCardBeforeRobotsModel_typedOrSpoken() {
        settings = askFirst
        for (q in listOf(hard, "compare the pros and cons of a heat pump and a furnace", "write me a cover letter for a barista job")) for (spoken in listOf(false, true)) {
            cardsFor.clear()
            assertNull(q, app.heyrobot.core.HelperRouter.route(q, app.heyrobot.core.HelperName.all(askFirst)))
            assertFalse(app.heyrobot.core.FastPath.of(null, false, Attachment.NONE) == app.heyrobot.core.FastPath.Way.HELPER)
            val b = runBlocking { escalation.beforeModel("r1", q, chat.toList(), Attachment.NONE, spoken, EscalationGuard(), askFirst, server, false, false) }
            assertEquals(q, listOf(server.id), cardsFor)
            assertEquals(q, 0, asked.size)
            // What the phone showed: an offer under an answer that didn't hedge. Only a card nobody answered leaves one here.
            assertEquals(q, EscalationTrigger.PRE_ROUTER, b!!.offerAfter)
            assertEquals(q, true, b.hold)
        }
    }

    @Test fun aQuestionWithoutASignal_noCardAndNoOfferBeforeRobotsAnswer() {
        val b = before(q = "what's the capital of France", s = askFirst)
        assertEquals(emptyList<String>(), cardsFor)
        assertNull(b!!.offerAfter)
    }

    private fun after(b: PanelEscalation.Before, trigger: EscalationTrigger = b.offerAfter!!) = runBlocking {
        escalation.afterLocal("r1", hard, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), trigger, offerOnly = !b.hold, needsSearch = b.needsSearch,
            helper = b.helper, hold = b.hold)
    }

    @Test fun nobodyAnsweredTheCard_theOfferUnderRobotsAnswerStillSaysItWasALot() {
        val b = before(s = askFirst)!!
        replied("Sunlight scatters.")
        after(b)
        assertTrue(offered.single().beyond)
        assertEquals(0, asked.size)
    }

    @Test fun nobodyAnsweredTheCard_theModelHandedOver_itStillSaysSo() {
        val b = before(s = askFirst)!!
        after(b, EscalationTrigger.LOCAL_TOOL)
        assertTrue(offered.single().beyond)
    }

    @Test fun aHedge_onAnotherQuestion_doesntSayItWasALot() {
        before(s = askFirst)
        val b = before(q = "what's the capital of France", s = askFirst)!!
        replied("I'm not sure.")
        after(b, EscalationTrigger.HEDGE)
        assertFalse(offered.single().beyond)
    }

    @Test fun manyParts_seenWithoutTheModel_asksEvenUnderAutomatic_neverSendsItself() {
        val b = before(q = manyParts)
        assertEquals(listOf(server.id), cardsFor)
        assertEquals(0, asked.size)
        assertEquals(true, b!!.hold)
    }

    @Test fun pickingTheHelper_sendsOnlyTheQuestion_thenOffersToRemember() {
        personal()
        picked = HelperChoice.Picked.HELPER
        assertNull(before(s = askFirst))
        assertEquals(listOf(hard), texts(asked.single().blocks).drop(1).ifEmpty { texts(asked.single().blocks) })
        assertFalse(texts(asked.single().blocks).any { "4471" in it })
        assertEquals(false, keeps.single().robot)
        assertEquals(QuestionKind.THINKING, keeps.single().kind)
        assertEquals(server.id, keeps.single().helper)
        // F22: the conversation now holds a helper's words, so only a tap saves the route.
        assertTrue(keeps.single().tapOnly)
    }

    @Test fun pickingHere_runsTheModel_noOffer_andOffersToRemember() {
        picked = HelperChoice.Picked.HERE
        val b = before(s = askFirst)!!
        assertNull(b.offerAfter)
        assertEquals(true, b.hold)
        assertEquals(true, b.keep!!.robot)
        assertEquals(0, asked.size)
    }

    @Test fun whenRobotCantAnswerOff_meansNoOfferNoLineNoRoute() {
        val off = settings.copy(help = Escalation.OFF)
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", false)
        val b = before(s = off)!!
        assertEquals(0, cardsFor.size + asked.size + calls.size)
        assertNull(b.offerAfter)
        assertEquals(0, before(q = manyParts, s = off.copy(helpers = emptyList()), target = null).let { calls.size + cardsFor.size })
    }

    @Test fun noHelperSetUp_saysSoOnce_andTries() {
        val none = settings.copy(helpers = emptyList())
        assertNull(before(s = none, target = null)!!.offerAfter)
        before(s = none, target = null)
        assertEquals(1, calls.count { it.startsWith("notice ") && "no helper" in it })
        assertEquals(0, cardsFor.size)
        escalation.newConversation()
        before(s = none, target = null)
        assertEquals(2, calls.size)
    }

    @Test fun aHelperOnButNotReady_isntCalledNoHelper() {
        before(s = settings.copy(helpers = listOf(server.copy(enabled = true))), target = null)
        assertEquals(0, calls.size)
    }

    @Test fun anythingThatActsNeverGetsTheChoiceNorARoute() {
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", false)
        remembered[QuestionKind.WRITING] = MemoryChoice("WRITING", false)
        for (q in listOf("text Sam why I am late and explain step by step", "call mum and compare the plans", "write a message to Sam")) {
            before(q = q, s = askFirst)
            assertEquals(q, 0, cardsFor.size + asked.size)
        }
    }

    // ---- remembered

    @Test fun rememberedHelper_goesAsAutomaticWould_withoutAsking() {
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", false)
        assertNull(before(s = askFirst))
        assertEquals(1, asked.size)
        assertEquals(0, cardsFor.size)
    }

    @Test fun rememberedHelper_refusedStaysRefused() {
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", false)
        // Locked, and the helper isn't allowed there.
        locked = true
        val b = before(s = askFirst)!!
        assertEquals(0, asked.size + cardsFor.size)
        assertNull(b.offerAfter)
        locked = false
        // A screen goes on a tap only.
        before(s = askFirst, attachment = Attachment.SCREEN)
        assertEquals(0, asked.size)
        // "Not Jarvis" holds.
        declined = true
        before(s = askFirst)
        assertEquals(0, asked.size)
        declined = false
        // Personal: as under Automatic, the question goes and what's personal stays here.
        personal()
        assertNull(before(s = askFirst))
        assertFalse(texts(asked.single().blocks).any { "4471" in it })
        asked.clear()
        chat.clear()
        // Once a turn.
        before(s = askFirst, guard = EscalationGuard().also { it.take(fromHelper = false) })
        assertEquals(0, asked.size)
        // The budget.
        budget = true
        assertEquals(EscalationTrigger.PRE_ROUTER, before(s = askFirst)!!.offerAfter)
        assertEquals(0, asked.size)
    }

    @Test fun rememberedRobot_holdsEvenUnderAutomatic_andAHedgeIsOnlyOffered() {
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", true)
        val guard = EscalationGuard()
        val b = before(guard = guard)!!
        assertEquals(0, asked.size + cardsFor.size)
        assertEquals(true, b.hold)
        assertNull(b.offerAfter)
        replied("I don't know.")
        runBlocking { escalation.afterLocal("r1", hard, chat.toList(), Attachment.NONE, null, null, false, guard, EscalationTrigger.HEDGE, hold = b.hold) }
        assertEquals(0, asked.size)
        assertEquals(1, offered.size)
    }

    @Test fun aRememberedHelperThatIsOff_followsAskFirst_nothingGoesByItself() {
        val other = HelperConfig("h3", "home", "Other", "m", enabled = false)
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", false, other.id)
        // Ask first: the card, as with no route, for the usual helper.
        before(s = askFirst.copy(helpers = listOf(server, other)))
        assertEquals(listOf(server.id), cardsFor)
        assertEquals(0, asked.size)
        // Removed, the same.
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", false, "gone")
        cardsFor.clear()
        before(s = askFirst)
        assertEquals(listOf(server.id), cardsFor)
        assertEquals(0, asked.size)
        // Automatic: as any question there, with the last turns as the policy says, and no route's words.
        assertNull(before(s = settings.copy(help = Escalation.AUTO, helpers = listOf(server, other))))
        assertEquals(1, asked.size)
    }

    private val sql = "How do I add a column in SQL?"

    @Test fun aRouteToRobot_holdsAQuestionWithAnActionWord() {
        assertNull(HelperChoice.kindOf(sql))
        remembered[QuestionKind.CODE] = MemoryChoice("CODE", true)
        val b = before(q = sql)!!
        assertEquals(0, asked.size + cardsFor.size)
        assertTrue(b.hold)
    }

    @Test fun aRouteToAHelper_namesWhoAnActionWordQuestionGoesTo_andAskFirstStillAsks() {
        val home = HelperConfig("h3", "home", "Home", "m")
        val both = settings.copy(helpers = listOf(server, home))
        remembered[QuestionKind.CODE] = MemoryChoice("CODE", false, home.id)
        // Ask first: an offer for a tap, never sent by itself on the strength of the route.
        val b = before(q = sql, s = both.copy(help = Escalation.ASK))!!
        assertEquals(0, asked.size)
        assertEquals(home.id, b.helper?.id)
        assertEquals(EscalationTrigger.PRE_ROUTER, b.offerAfter)
        // Automatic: to the helper you named, not the usual one.
        asks = home
        assertNull(before(q = sql, s = both.copy(help = Escalation.AUTO)))
        assertEquals(1, asked.size)
    }

    @Test fun aSavedRoute_sendsOnlyYourQuestion() {
        chat += ChatTurn("u0", TurnRole.USER, "my dentist is Dr. Lee", 1L)
        chat += ChatTurn("a0", TurnRole.ASSISTANT, "Good to know.", 2L)
        remembered[QuestionKind.THINKING] = MemoryChoice("THINKING", false)
        assertNull(before())
        assertFalse(texts(asked.single().blocks).toString(), texts(asked.single().blocks).any { "Dr. Lee" in it })
        assertTrue(texts(asked.single().blocks).any { it == hard })
    }

    private val words = HelperWords(HelperWho.of(server))
    private val savedSettingsFor = MemoryPanel.settingsFor

    @Before fun setUp() { MemoryPanel.settingsFor = { MemorySettings() } }
    @After fun tearDown() { MemoryPanel.settingsFor = savedSettingsFor }

    private val question = "what is the capital of France"

    /** A conversation that is personal: a screen question and Robot's answer about it. */
    private fun personal() {
        chat += ChatTurn("u0", TurnRole.USER, "what does this say", 1L, attachment = Attachment.SCREEN)
        chat += ChatTurn("a0", TurnRole.ASSISTANT, "Your balance is 4471 dollars.", 2L)
    }

    /** Robot's reply r1, finished and in the conversation, saying [text] (a hedge reaches escalation this way). */
    private fun replied(text: String) {
        replies.setText("r1", text)
        chat += ChatTurn("r1", TurnRole.ASSISTANT, text, 3L)
    }

    private fun texts(blocks: List<HelperBlock>) = blocks.filterIsInstance<HelperBlock.Text>().map { it.text }

    private fun afterHedge(attachment: Attachment = Attachment.NONE, screen: String? = null, guard: EscalationGuard = EscalationGuard()) = runBlocking {
        // The history is what came before the question (send's snapshot): not the question, not this reply.
        escalation.afterLocal("r1", question, chat.filter { it.id != "r1" }, attachment, screen, null, false, guard, EscalationTrigger.HEDGE)
    }

    @Test fun automaticSendsOnlyYourQuestionFromAPersonalConversation() {
        personal()
        replied("I don't know.")
        afterHedge()
        val a = asked.single()
        assertNull(a.replyId)
        assertTrue(!a.reuseBubble && !a.fallBack)
        assertEquals(listOf(question), texts(a.blocks).drop(1))
        assertEquals(listOf("drop r1"), calls)
        assertTrue(offered.isEmpty())
    }

    @Test fun automaticSendsTheLastTurnsOfAConversationThatIsntPersonal() {
        chat += ChatTurn("u0", TurnRole.USER, "I'm planning a trip", 1L)
        chat += ChatTurn("a0", TurnRole.ASSISTANT, "Where to?", 2L)
        replied("I don't know.")
        afterHedge()
        assertTrue(texts(asked.single().blocks).any { it.contains("I'm planning a trip") })
    }

    @Test fun aScreenQuestionIsOfferedWithItsScreenFrozenNeverSentByItself() {
        replied("I can't tell.")
        afterHedge(Attachment.SCREEN, screen = "Balance 4471")
        assertTrue(asked.isEmpty())
        val o = offered.single()
        assertTrue(o.api)
        assertEquals(server.id, o.helper)
        assertTrue(texts(o.payload!!.blocks).any { it.contains("Balance 4471") })
        assertTrue(Personal.SCREEN in o.payload!!.personal)
        // "Send personal content to helpers automatically": then it goes along, by itself.
        offered.clear()
        settings = settings.copy(personalToHelpers = true)
        afterHedge(Attachment.SCREEN, screen = "Balance 4471")
        assertTrue(offered.isEmpty())
        assertTrue(texts(asked.single().blocks).any { it.contains("Balance 4471") })
    }

    @Test fun onTheLockScreenAHelperNotAllowedThereIsNeitherAskedNorOffered() {
        locked = true
        runBlocking { escalation.afterLocal("r1", question, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), EscalationTrigger.LOCAL_TOOL) }
        assertTrue(asked.isEmpty() && offered.isEmpty())
        assertEquals(listOf("say ${words.unlockToCheckText.render(Langs.EN)}", "finish r1 END"), calls)
        // Allowed on the lock screen: Robot checks with it.
        settings = settings.copy(helpers = listOf(server.copy(locked = true)))
        calls.clear()
        runBlocking { escalation.afterLocal("r1", question, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), EscalationTrigger.LOCAL_TOOL) }
        assertEquals(1, asked.size)
    }

    @Test fun afterNotJarvisNothingIsAskedOrOffered() {
        declined = true
        replied("I don't know.")
        afterHedge()
        runBlocking { assertNotNull(escalation.beforeModel("r1", "explain why the sky is blue step by step", emptyList(), Attachment.NONE, false, EscalationGuard(),
            settings, server, false, false)) }
        assertTrue(asked.isEmpty() && offered.isEmpty())
        assertEquals(listOf("drop r1"), calls)
    }

    @Test fun robotsOwnAnswerComesFirstAndOneThatStandsEndsTheTurn() {
        var ran = 0
        fun own(text: String): suspend () -> TurnResult = {
            ran++
            replies.setText("r1", text)
            TurnResult(DoneReason.END)
        }
        // The model handed over without a word: its own answer first; it stands, so no helper is asked.
        runBlocking { escalation.afterLocal("r1", question, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), EscalationTrigger.LOCAL_TOOL,
            ownAnswer = own("Paris.")) }
        assertEquals(1, ran)
        assertTrue(asked.isEmpty() && offered.isEmpty())
        assertEquals(listOf("finish r1 END", "finish r1 END"), calls)
        // An unsure one: the helper is asked after it.
        replies.setText("r1", "")
        runBlocking { escalation.afterLocal("r1", question, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), EscalationTrigger.LOCAL_TOOL,
            ownAnswer = own("I don't know.")) }
        assertEquals(2, ran)
        assertEquals(1, asked.size)
        // The model said something before handing over: its words are the answer, and the model isn't asked again.
        replies.setText("r1", "Let me check.")
        runBlocking { escalation.afterLocal("r1", question, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), EscalationTrigger.LOCAL_TOOL,
            ownAnswer = own("Paris.")) }
        assertEquals(2, ran)
    }

    @Test fun aUsedUpBudgetOffersInsteadAndSaysSoOnceADay() {
        budget = true
        replied("I don't know.")
        afterHedge()
        afterHedge()
        assertTrue(asked.isEmpty())
        assertEquals(2, offered.size)
        assertEquals(listOf("notice ${words.budgetText.render(Langs.EN)}"), calls)
    }

    @Test fun thePreRoutersStrongSignalAsksBeforeRobotsModelOnceATurnWithTheSameRuleForWhatGoes() {
        personal()
        val guard = EscalationGuard()
        val before = runBlocking { escalation.beforeModel("r1", "explain why the sky is blue step by step", chat.toList(), Attachment.NONE, false, guard,
            settings, server, false, false) }
        // The helper took the question: Robot's model doesn't run.
        assertNull(before)
        val a = asked.single()
        assertEquals("r1", a.replyId)
        assertTrue(a.reuseBubble && a.fallBack)
        assertEquals(listOf("explain why the sky is blue step by step"), texts(a.blocks).drop(1))
        // The same turn doesn't escalate again.
        replied("I don't know.")
        afterHedge(guard = guard)
        assertEquals(1, asked.size)
        assertEquals(listOf("drop r1"), calls)
        // A helper that couldn't be reached gave it back: Robot's model answers, with nothing left to offer.
        fellBack = true
        val back = runBlocking { escalation.beforeModel("r1", "explain why the sky is blue step by step", emptyList(), Attachment.NONE, false, EscalationGuard(),
            settings, server, false, false) }
        assertNotNull(back)
        assertNull(back!!.offerAfter)
        // Ask me first: offered after Robot's answer instead.
        val ask = settings.copy(help = Escalation.ASK)
        settings = ask
        val later = runBlocking { escalation.beforeModel("r1", "explain why the sky is blue step by step", emptyList(), Attachment.NONE, false, EscalationGuard(),
            ask, server, false, false) }
        assertEquals(EscalationTrigger.PRE_ROUTER, later!!.offerAfter)
        assertFalse(later.needsSearch)
        assertEquals(2, asked.size)
        // A question with the screen or a photo never goes before Robot's model, nor is offered for it, even with personal
        // content allowed to go by itself.
        settings = RobotSettings(helpers = listOf(server), personalToHelpers = true)
        val screen = runBlocking { escalation.beforeModel("r1", "explain why the sky is blue step by step", emptyList(), Attachment.SCREEN, false,
            EscalationGuard(), settings, server, false, false) }
        assertNull(screen!!.offerAfter)
        settings = settings.copy(help = Escalation.ASK)
        val asked2 = runBlocking { escalation.beforeModel("r1", "explain why the sky is blue step by step", emptyList(), Attachment.PHOTO, false,
            EscalationGuard(), settings, server, false, false) }
        assertNull(asked2!!.offerAfter)
        assertEquals(2, asked.size)
    }

    // ---------------------------------------------------------------- 1.1 tests: what the split's own tests left uncovered

    /** A helper with an app and no key: no API Robot can use, only its app. */
    private val chatgpt = HelperConfig("h1", "openai", "ChatGPT", "m")

    /** The model handed over (ask_claude) with r1 still empty; [own]: Robot's own answer, if it runs one. */
    private fun handedOver(own: (suspend () -> TurnResult)? = null) = runBlocking {
        escalation.afterLocal("r1", question, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), EscalationTrigger.LOCAL_TOOL, ownAnswer = own)
    }

    /** Robot's own answer: [text] in r1, then [result]. */
    private fun own(text: String, result: TurnResult = TurnResult(DoneReason.END)): suspend () -> TurnResult = {
        replies.setText("r1", text)
        result
    }

    @Test fun withNoApiAndNoAppRobotIsNotSureAndOffersNothing() {
        settings = RobotSettings(helpers = listOf(chatgpt))
        handedOver()
        assertEquals(listOf("say ${PanelStrings.notSure().render(Langs.EN)}", "drop r1"), calls)
        assertTrue(offered.isEmpty() && asked.isEmpty())
        // After Robot's own answer said nothing either: the same line, and the reply ends.
        calls.clear()
        replies.setText("r1", "")
        handedOver(own(""))
        assertEquals(listOf("say ${PanelStrings.notSure().render(Langs.EN)}", "finish r1 END"), calls)
        // Its app installed: the app is offered, with nothing frozen to send but your question.
        calls.clear()
        replies.setText("r1", "")
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(PackageInfo().apply {
            packageName = chatgpt.presetOf.app!!
            applicationInfo = ApplicationInfo().apply { packageName = chatgpt.presetOf.app!! }
        })
        handedOver()
        val o = offered.single()
        assertFalse(o.api)
        assertNull(o.payload)
        assertEquals(chatgpt.id, o.helper)
        assertEquals(question, o.question)
        assertEquals(listOf("say ${PanelStrings.helperCouldHelp("name" to chatgpt.name).render(Langs.EN)}", "finish r1 END"), calls)
    }

    @Test fun robotsOwnAnswerThatFailedSaysWhyOnlyWhenItSaidNothing() {
        // No helper to ask: the turn is Robot's own.
        settings = RobotSettings()
        handedOver(own("", TurnResult(null, failure = "The model stopped.")))
        assertEquals(listOf("fail r1 The model stopped."), calls)
        calls.clear()
        handedOver(own("Paris, I think.", TurnResult(null, failure = "The model stopped.")))
        assertEquals(listOf("finish r1 END", "finish r1 END"), calls)
        assertTrue(offered.isEmpty() && asked.isEmpty())
    }

    @Test fun automaticAnswersFromRobotsOwnSearchAndAskFirstOffersInstead() {
        val found = WebOutcome.Failed(null, WebFail.NO_INTERNET)
        handedOver(own("", TurnResult(DoneReason.END, web = found)))
        assertEquals(listOf("web"), calls)
        assertTrue(webFound.single() === found)
        assertTrue(offered.isEmpty() && asked.isEmpty())
        // Ask me first: the search's result isn't Robot's answer; the helper is offered.
        calls.clear()
        settings = settings.copy(help = Escalation.ASK)
        replies.setText("r1", "")
        handedOver(own("", TurnResult(DoneReason.END, web = found)))
        assertFalse("web" in calls)
        assertEquals(listOf("say ${words.ratherText.render(Langs.EN)}", "finish r1 END"), calls)
        assertEquals(1, offered.size)
        assertTrue(asked.isEmpty())
    }

    @Test fun anAppRobotsOwnAnswerOpenedEndsTheTurnAndThePanelGoes() {
        handedOver(own("", TurnResult(DoneReason.END, launchedApp = true)))
        assertEquals(listOf("finish r1 END", "leave"), calls)
        assertTrue(offered.isEmpty() && asked.isEmpty())
    }

    @Test fun aStoppedOwnAnswerEndsTheTurnWithNothingAfterIt() {
        handedOver(own("", TurnResult(DoneReason.CANCELLED)))
        assertEquals(listOf("finish r1 CANCELLED"), calls)
        assertTrue(offered.isEmpty() && asked.isEmpty())
        // Stopped while it ran (the turn cancelled): nothing at all follows.
        calls.clear()
        VoiceLog.resetForTests()
        runBlocking {
            val turn = launch {
                escalation.afterLocal("r1", question, emptyList(), Attachment.NONE, null, null, false, EscalationGuard(), EscalationTrigger.LOCAL_TOOL,
                    ownAnswer = {
                        currentCoroutineContext().job.cancel()
                        TurnResult(DoneReason.END)
                    })
            }
            turn.join()
            assertTrue(turn.isCancelled)
        }
        assertTrue(calls.toString(), calls.isEmpty())
        assertTrue(offered.isEmpty() && asked.isEmpty())
        assertTrue(VoiceLog.entries().none { it.event == VoiceEvent.OWN_ANSWER })
    }

    @Test fun anOwnAnswerThatNeedsTodaysSourcesIsOfferedWhenTheHelperCantSearch() {
        handedOver(own("I don't have access to the internet."))
        assertTrue(asked.isEmpty())
        val o = offered.single()
        assertTrue(o.api && o.asWell)
        assertEquals(listOf("finish r1 END", "finish r1 END"), calls)
        // Unsure for another reason: the helper is asked by itself.
        calls.clear()
        offered.clear()
        replies.setText("r1", "")
        handedOver(own("I don't know."))
        assertEquals(1, asked.size)
        assertTrue(offered.isEmpty())
    }
}
