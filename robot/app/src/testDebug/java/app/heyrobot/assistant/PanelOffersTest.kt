// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Executed
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperPayload
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Shared
import app.heyrobot.core.TtsStop
import app.heyrobot.core.TurnRole
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The offer under a reply (PanelOffers, from PanelController in 1.1). What a payload holds is core HelperContext's and
 * what a yes may send HelperTrust's (HelperContextTest, HelperSettingsTest); here, the panel's side: an offer shows under
 * its reply, one at a time, and a new conversation forgets it; a spoken or typed yes never sends your screen (only the tap
 * does), and the tap sends exactly the payload frozen when the offer was made, as the panel's one turn; nothing is taken
 * while a turn runs; a helper turned off since says so; the app's offer never opens the app on the lock screen, and
 * otherwise hands your question to it; dismissing or a helper's key going drops it. Then (1.1) the offer said aloud (an
 * app's by the app's name) and an app that didn't open saying why with the panel kept. No test covered offers before the
 * move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelOffersTest {
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "Paris, I think."),
        UiTurn("r2", TurnRole.ASSISTANT, "Maybe."))))
    /** A server on your network: no key, its API, a known price (free). */
    private val server = HelperConfig("h2", "home", "My server", "m")
    /** A helper with an app (the route a hand-off takes). */
    private val app = HelperConfig("h1", "openai", "ChatGPT", "m")
    private var settings = RobotSettings(helpers = listOf(server, app))
    private var running = false
    private var locked = false
    private var executed = Executed(ActionStatus.DONE, "Opened.", launchedApp = true)
    /** What escalate was given: the reply id, the blocks and whether it was the panel's one turn. */
    private var escalated: List<HelperBlock>? = null
    private var escalatedId: String? = null
    private var inTurn = false
    private var handedToApp: String? = "none"
    /** What the port was asked, in order: "notice <text>", "speak <text>", "stop <why>", "execute <action>", "leave". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from the offers' coroutines while the test waits on it

    private val offers = PanelOffers(CoroutineScope(Dispatchers.Unconfined), state, object : PanelOffers.Port {
        override val settings get() = this@PanelOffersTest.settings
        override val turnLangs = Langs.EN
        override val running get() = this@PanelOffersTest.running
        override fun said(p: Phrase) = p.render(Langs.EN)
        override fun locked() = locked
        override fun unlockFirst(p: Phrase) = p
        override fun notify(text: String) { calls += "notice $text" }
        override fun notify(p: Phrase) { calls += "notice ${p.render(Langs.EN)}" }
        override fun speakRobot(text: String) { calls += "speak $text" }
        override fun stopVoice(why: TtsStop) { calls += "stop $why" }
        override fun handedToApp(text: String?) { handedToApp = text }
        override fun execute(action: Action): Executed {
            calls += "execute $action"
            return executed
        }
        override suspend fun leaveForApp() { calls += "leave" }
        override fun helperBubble(h: HelperConfig) = "b1"
        override fun startTurn(replyId: String, block: suspend () -> Unit) {
            inTurn = true
            runBlocking { block() }
            inTurn = false
        }
        override suspend fun escalate(replyId: String?, question: String, blocks: List<HelperBlock>, voiceAsked: Boolean, reuseBubble: Boolean, h: HelperConfig,
            fallBack: Boolean): Boolean {
            assertTrue("only as the panel's one turn", inTurn)
            assertEquals(server.id, h.id)
            assertTrue(reuseBubble && !fallBack)
            escalatedId = replyId
            escalated = blocks
            return false
        }
    })

    private val words = HelperWords(HelperWho.of(server))
    private val now = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC)

    /** What a tap would send for "what is this", with your screen's text (as an offer for a screen question is made). */
    private fun screenPayload(): HelperPayload = HelperContext.payload("what is this", emptyList(), now, "CA", emptySet(), Attachment.SCREEN,
        Shared(screenText = "Balance 4471"))

    private fun plainPayload(): HelperPayload = HelperContext.payload("what is the capital of France", listOf(ChatTurn("u0", TurnRole.USER, "hi", 1L)), now, "CA", emptySet())

    private fun offerOf(id: String) = state.value.turns.single { it.id == id }.offer

    @Test fun anOfferShowsUnderItsReplyOneAtATimeAndANewConversationForgetsIt() {
        offers.show("r1", PanelOffers.Pending(true, "what is the capital of France", plainPayload(), false, helper = server.id))
        assertEquals("My server", offerOf("r1")!!.name)
        offers.show("r2", PanelOffers.Pending(true, "what is the capital of France", plainPayload(), false, helper = server.id))
        assertNull(offerOf("r1"))
        assertNotNull(offerOf("r2"))
        assertEquals("r2", offers.waiting { it == "r2" })
        // A helper no longer in the settings: nothing is offered.
        offers.drop()
        offers.show("r1", PanelOffers.Pending(true, "q", plainPayload(), false, helper = "gone"))
        assertTrue(state.value.turns.all { it.offer == null })
        // A new conversation forgets its offers: a tap on what is left does nothing.
        offers.show("r1", PanelOffers.Pending(true, "q", plainPayload(), false, helper = server.id))
        offers.clear()
        offers.accept("r1", tapped = true)
        assertNull(escalated)
        assertNull(offers.waiting { true })
    }

    @Test fun aYesNeverSendsYourScreenOnlyTheTapDoesAndItSendsExactlyWhatWasFrozen() {
        val payload = screenPayload()
        offers.show("r1", PanelOffers.Pending(true, "what is this", payload, false, helper = server.id))
        offers.accept("r1", tapped = false)
        assertNull(escalated)
        assertNotNull(offerOf("r1"))
        assertEquals(listOf("notice ${words.tapToIncludeText(payload.personal).render(Langs.EN)}"), calls)
        calls.clear()
        offers.accept("r1", tapped = true)
        assertSame(payload.blocks, escalated)
        assertEquals("b1", escalatedId)
        assertTrue(escalated!!.filterIsInstance<HelperBlock.Text>().any { it.text.contains("Balance 4471") })
        assertNull(offerOf("r1"))
        assertEquals(listOf("stop ${TtsStop.NEW_QUESTION}"), calls)
        // It's gone: a second tap sends nothing more.
        escalated = null
        offers.accept("r1", tapped = true)
        assertNull(escalated)
    }

    @Test fun aYesSendsAnOfferWithNothingPersonalInIt() {
        val payload = plainPayload()
        offers.show("r1", PanelOffers.Pending(true, "what is the capital of France", payload, true, helper = server.id))
        offers.accept("r1", tapped = false)
        assertSame(payload.blocks, escalated)
    }

    @Test fun nothingIsTakenWhileATurnRunsAndAHelperTurnedOffSaysSo() {
        offers.show("r1", PanelOffers.Pending(true, "q", plainPayload(), false, helper = server.id))
        running = true
        offers.accept("r1", tapped = true)
        assertNull(escalated)
        assertNotNull(offerOf("r1"))
        running = false
        settings = settings.copy(helpers = listOf(server.copy(enabled = false), app))
        offers.accept("r1", tapped = true)
        assertNull(escalated)
        assertNull(offerOf("r1"))
        assertEquals(listOf("notice ${words.offText.render(Langs.EN)}"), calls)
    }

    @Test fun theAppsOfferNeverOpensItOnTheLockScreenAndOtherwiseHandsItYourQuestion() {
        offers.show("r1", PanelOffers.Pending(false, "what is the capital of France", null, false, helper = app.id))
        locked = true
        offers.accept("r1", tapped = true)
        assertEquals(listOf("stop ${TtsStop.NEW_QUESTION}", "notice ${PanelStrings.unlockToOpenApp("appName" to "ChatGPT").render(Langs.EN)}"), calls)
        assertEquals("none", handedToApp)
        // Unlocked: the question goes to the app, and the panel steps aside once it opened.
        locked = false
        calls.clear()
        offers.show("r1", PanelOffers.Pending(false, "what is the capital of France", null, false, helper = app.id))
        offers.accept("r1", tapped = true)
        runBlocking { withTimeout(5_000) { while ("leave" !in calls) delay(5) } }
        assertEquals("what is the capital of France", handedToApp)
        assertEquals(listOf("stop ${TtsStop.NEW_QUESTION}", "execute ${Action.AskHelper("what is the capital of France", helper = app.id)}", "leave"), calls)
        assertNull(escalated)
    }

    @Test fun dismissingOrAHelpersKeyGoingDropsIt() {
        offers.show("r1", PanelOffers.Pending(true, "q", plainPayload(), false, helper = server.id))
        offers.dismiss("r1")
        assertNull(offerOf("r1"))
        offers.accept("r1", tapped = true)
        assertNull(escalated)
        offers.show("r1", PanelOffers.Pending(true, "q", plainPayload(), false, helper = server.id))
        offers.dropIf { !it.api }
        assertNotNull(offerOf("r1"))
        offers.dropIf { it.api && it.helper == server.id }
        assertNull(offerOf("r1"))
    }

    // ---------------------------------------------------------------- 1.1 tests: what the split's own tests left uncovered

    @Test fun anOfferIsSaidAloudSoYouKnowAYesWillDo() {
        state.update { it.copy(speak = true) }
        offers.show("r1", PanelOffers.Pending(true, "q", plainPayload(), false, helper = server.id))
        offers.show("r2", PanelOffers.Pending(true, "q", plainPayload(), false, asWell = true, helper = server.id))
        // An app's offer names the app, whatever you called the helper.
        settings = settings.copy(helpers = listOf(server, app.copy(name = "Jarvis")))
        offers.show("r1", PanelOffers.Pending(false, "q", null, false, helper = app.id))
        assertEquals(listOf(words.offerText, words.offerAsWellText, PanelStrings.wantOpenApp("appName" to "ChatGPT")).map { "speak ${it.render(Langs.EN)}" }, calls)
        assertEquals("Jarvis", offerOf("r1")!!.name)
        // Replies not spoken: shown, not said.
        calls.clear()
        state.update { it.copy(speak = false) }
        offers.show("r2", PanelOffers.Pending(true, "q", plainPayload(), false, helper = server.id))
        assertNotNull(offerOf("r2"))
        assertTrue(calls.isEmpty())
    }

    @Test fun anAppThatDidntOpenSaysWhyAndThePanelStays() {
        for (r in listOf(Executed(ActionStatus.NO_APP, "ChatGPT isn't installed."), Executed(ActionStatus.FAILED, "ChatGPT didn't open.", launchedApp = true))) {
            executed = r
            calls.clear()
            offers.show("r1", PanelOffers.Pending(false, "what is the capital of France", null, false, helper = app.id))
            offers.accept("r1", tapped = true)
            runBlocking { withTimeout(5_000) { while (calls.none { it.startsWith("notice ") }) delay(5) } }
            assertEquals(listOf("stop ${TtsStop.NEW_QUESTION}", "execute ${Action.AskHelper("what is the capital of France", helper = app.id)}", "notice ${r.detail}"), calls)
            assertEquals("what is the capital of France", handedToApp)
        }
    }
}
