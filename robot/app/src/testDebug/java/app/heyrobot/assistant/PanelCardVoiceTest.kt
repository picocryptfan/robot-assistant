// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Handler
import android.os.Looper
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Cue
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ShowSource
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.voice.SpeechToText
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Answering a card by voice (PanelCardVoice, from PanelController in 1.1): a spoken yes answers the newest waiting card
 * and no other, a no or "cancel" declines it, anything else gets one more listen and then only a chime; a stopped
 * listening's late words answer nothing; Robot listens only while the panel is open, a card waits and you are talking to
 * it (spoken replies, a spoken question or a wake-word panel), and with spoken replies only 350 ms after its voice has
 * stopped; a routine card that only a tap answers takes no spoken yes, and a card that needs a tap says why. What counts
 * as yes or no is core CardAnswer's (CardAnswerTest); here, what the panel does with it. The speech callbacks are posted
 * to the main looper, so the looper drives them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelCardVoiceTest {
    /** The speech recognizer: each start's listener and whether it was a short answer; cancels counted. */
    private class FakeStt : SpeechToText {
        override var available = true
        val starts = mutableListOf<Pair<SpeechToText.Listener, Boolean>>()
        var cancels = 0
        val last: SpeechToText.Listener get() = starts.last().first
        override fun start(listener: SpeechToText.Listener, shortAnswer: Boolean) { starts += listener to shortAnswer }
        override fun stop() {}
        override fun cancel() { cancels++ }
        override fun release() {}
    }

    private val scope = MainScope()
    private val looper = shadowOf(Looper.getMainLooper())
    private val state = MutableStateFlow(PanelState("c"))
    private val replies = PanelReplies(state)
    private var shown = true
    private val screen = PanelScreenShare(scope, state, { shown }, { 0L }, { Langs.EN }) { true to true }
    private val stt = FakeStt()
    private var settings = RobotSettings()
    private var speaking = false
    private var spokenTurn = true
    private var tapOnly: Pair<String, String>? = null
    private var wakePaused: Boolean? = null
    /** What the card buttons were given, as "confirm <id> <yes>" or "choose <id> <index>". */
    private val answers = mutableListOf<String>()
    private val notices = mutableListOf<String>()
    private val said = mutableListOf<String>()
    private val plain = mutableListOf<String>()
    private val cues = mutableListOf<Cue>()

    private val voice = PanelCardVoice(state, replies, screen, object : PanelCardVoice.Port {
        override val main = Handler(Looper.getMainLooper())
        override val stt: SpeechToText get() = this@PanelCardVoiceTest.stt
        override val settings get() = this@PanelCardVoiceTest.settings
        override val speaking get() = this@PanelCardVoiceTest.speaking
        override val shown get() = this@PanelCardVoiceTest.shown
        override val spokenTurn get() = this@PanelCardVoiceTest.spokenTurn
        override val turnLangs = Langs.EN
        override fun screenLangs() = Langs.EN
        override val routineTapOnly get() = tapOnly
        override fun wakePaused(paused: Boolean) { wakePaused = paused }
        override fun notify(text: String) {
            notices += text
            state.update { it.copy(notice = Notice(text)) }
        }
        override fun speakRobot(text: String) { said += text }
        override fun said(p: Phrase) = p.render(Langs.EN)
        override fun speak(text: String) { plain += text }
        override fun cue(c: Cue) { cues += c }
        override fun confirm(actionId: String, yes: Boolean) { answers += "confirm $actionId $yes" }
        override fun choose(actionId: String, index: Int) { answers += "choose $actionId $index" }
    })

    @After fun end() = scope.cancel()

    private fun card(id: String, status: ActionStatus = ActionStatus.PENDING, choices: List<String> = emptyList(), tapFor: AskWhy? = null,
        personal: Boolean = false) = UiAction(id, null, "Text Mom", status, null, choices = choices, tapFor = tapFor, personal = personal)

    /** Replies holding [cards] (one reply per list), the turn waiting on a card, replies [speak]ing or not. */
    private fun wait(vararg cards: List<UiAction>, speak: Boolean = false) = state.update { st ->
        st.copy(phase = Phase.CONFIRMING, speak = speak, turns = cards.mapIndexed { i, a -> UiTurn("r$i", TurnRole.ASSISTANT, "", actions = a) })
    }

    private fun idle() = looper.idleFor(Duration.ZERO)

    private val hint = PanelStrings.cardHint().render(Langs.EN)

    @Test fun aSpokenYesConfirmsTheNewestWaitingCardAndOnlyIt() {
        wait(listOf(card("old")), listOf(card("new"), card("done", ActionStatus.DONE)))
        voice.waiting()
        assertEquals(1, stt.starts.size)
        assertTrue("a card's answer is a short one", stt.starts.single().second)
        assertEquals(Phase.LISTENING, state.value.cardHearing)
        assertEquals(true, wakePaused)
        stt.last.onPartial("ye")
        idle()
        assertEquals("ye", state.value.partial)
        stt.last.onEndOfSpeech()
        idle()
        assertEquals(Phase.TRANSCRIBING, state.value.cardHearing)
        assertEquals(false, wakePaused)
        stt.last.onFinal("yes")
        assertEquals("nothing happens before the main thread takes it", emptyList<String>(), answers)
        idle()
        assertEquals(listOf("confirm new true"), answers)
        assertNull(state.value.cardHearing)
        assertEquals("", state.value.partial)
        assertEquals("the phase stays the turn's", Phase.CONFIRMING, state.value.phase)
    }

    @Test fun noAndCancelDeclineAndATypedYesStillWorksWithVoiceAnswersOff() {
        wait(listOf(card("a")))
        assertTrue(voice.answer("no", spoken = true))
        assertTrue(voice.answer("cancel", spoken = true))
        assertEquals(listOf("confirm a false", "confirm a false"), answers)
        answers.clear()
        settings = settings.copy(voiceCards = false)
        assertFalse(voice.answer("yes", spoken = true))
        assertEquals(emptyList<String>(), answers)
        assertTrue(voice.answer("yes", spoken = false))
        assertEquals(listOf("confirm a true"), answers)
        // Off: a waiting card doesn't open the microphone either.
        voice.waiting()
        assertEquals(0, stt.starts.size)
    }

    @Test fun anUnrelatedPhraseAnswersNothingAndGetsOneMoreListenThenOnlyAChime() {
        wait(listOf(card("a")))
        assertFalse(voice.answer("what's the weather tomorrow", spoken = true))
        assertFalse(voice.answer("yes and text dad too", spoken = true))
        voice.waiting()
        stt.last.onFinal("what's the weather tomorrow")
        idle()
        assertEquals(emptyList<String>(), answers)
        assertEquals(listOf(hint), notices)
        assertEquals("one more listen", 2, stt.starts.size)
        stt.last.onFinal("hmm, maybe")
        idle()
        assertEquals(emptyList<String>(), answers)
        assertEquals(listOf(Cue.CHIME), cues)
        assertEquals("no third listen", 2, stt.starts.size)
        assertNull(state.value.cardHearing)
        // A new card starts the count over.
        voice.waiting()
        stt.last.onFinal("")
        idle()
        assertEquals(4, stt.starts.size)
    }

    @Test fun withSpokenRepliesTheRetrySaysHowToAnswerAndListensOnlyOnceRobotHasStopped() {
        wait(listOf(card("a")), speak = true)
        voice.waiting()
        assertEquals(listOf(TurnStrings.sayYesOrNo().render(Langs.EN)), said)
        assertEquals("not while Robot talks", 0, stt.starts.size)
        // A sentence ends between two: the voice is still talking 350 ms later, so nothing listens.
        speaking = true
        voice.speakingStopped()
        looper.idleFor(Duration.ofMillis(400))
        assertEquals(0, stt.starts.size)
        speaking = false
        voice.speakingStopped()
        looper.idleFor(Duration.ofMillis(349))
        assertEquals(0, stt.starts.size)
        looper.idleFor(Duration.ofMillis(1))
        assertEquals(1, stt.starts.size)
        // Once it listened, the voice stopping again opens nothing more.
        voice.speakingStopped()
        looper.idleFor(Duration.ofSeconds(1))
        assertEquals(1, stt.starts.size)
        // A miss: the hint, the prompt again, and another listen after the voice.
        stt.last.onFinal("pizza")
        idle()
        assertEquals(listOf(hint), notices)
        assertEquals(2, said.size)
        assertEquals(1, stt.starts.size)
        voice.speakingStopped()
        looper.idleFor(Duration.ofMillis(350))
        assertEquals(2, stt.starts.size)
    }

    @Test fun aPromptAlreadySaidIsNotSaidAgainButStillListens() {
        wait(listOf(card("a")), speak = true)
        voice.saidPrompt()
        voice.waiting()
        assertEquals(emptyList<String>(), said)
        voice.speakingStopped()
        looper.idleFor(Duration.ofMillis(350))
        assertEquals(1, stt.starts.size)
        // Only for that card: the next one is prompted again.
        voice.stop()
        voice.waiting()
        assertEquals(1, said.size)
    }

    @Test fun aStoppedListeningsLateWordsAnswerNothing() {
        wait(listOf(card("a")))
        voice.stop()
        assertEquals("nothing to cancel", 0, stt.cancels)
        voice.waiting()
        val first = stt.last
        voice.stop()
        assertEquals(1, stt.cancels)
        assertNull(state.value.cardHearing)
        assertEquals(false, wakePaused)
        first.onFinal("yes")
        first.onError(SpeechToText.Reason.NO_SPEECH, "x")
        first.onEndOfSpeech()
        idle()
        assertEquals(emptyList<String>(), answers)
        assertEquals(emptyList<String>(), notices)
        assertNull(state.value.cardHearing)
        // A new listening: the old one's words still answer nothing, the new one's do.
        voice.micPressed()
        assertEquals(2, stt.starts.size)
        first.onFinal("yes")
        idle()
        assertEquals(emptyList<String>(), answers)
        assertEquals(Phase.LISTENING, state.value.cardHearing)
        stt.last.onFinal("no")
        idle()
        assertEquals(listOf("confirm a false"), answers)
    }

    @Test fun silenceOrAnErrorEndsTheListeningWithANotice() {
        wait(listOf(card("a")))
        voice.waiting()
        stt.last.onError(SpeechToText.Reason.NO_SPEECH, "nothing heard")
        idle()
        assertEquals(listOf(hint), notices)
        assertNull(state.value.cardHearing)
        assertEquals(false, wakePaused)
        voice.micPressed()
        stt.last.onError(SpeechToText.Reason.MIC, "Another app is using the microphone.")
        idle()
        assertEquals(listOf(hint, "Another app is using the microphone."), notices)
        assertEquals(emptyList<String>(), answers)
    }

    @Test fun theMicrophoneOpensOnlyWhileTheCardWaitsInAnOpenPanelAndYouAreTalkingToRobot() {
        // Typed: no spoken replies, no spoken question, opened from Robot's app.
        spokenTurn = false
        wait(listOf(card("a")))
        voice.waiting()
        assertEquals(0, stt.starts.size)
        // A wake-word panel counts as talking to Robot.
        screen.start(ShowSource.WAKE, 0)
        voice.waiting()
        assertEquals(1, stt.starts.size)
        voice.stop()
        // The panel closed, no card waits any more, or the turn isn't waiting on one: nothing listens.
        shown = false
        voice.waiting()
        voice.micPressed()
        shown = true
        wait(listOf(card("a", ActionStatus.DONE)))
        voice.waiting()
        voice.micPressed()
        wait(listOf(card("a")))
        state.update { it.copy(phase = Phase.THINKING) }
        voice.micPressed()
        assertEquals(1, stt.starts.size)
        // Already listening: not twice.
        wait(listOf(card("a")))
        voice.micPressed()
        voice.micPressed()
        assertEquals(2, stt.starts.size)
        voice.stop()
        // No speech recognizer: the waiting card doesn't try; the mic says why.
        stt.available = false
        voice.waiting()
        voice.micPressed()
        assertEquals(2, stt.starts.size)
        assertEquals(listOf(PanelStrings.noVoiceInput().render(Langs.EN)), notices)
    }

    @Test fun aRoutineCardOnlyATapAnswersTakesNoSpokenYesButANo() {
        tapOnly = "r" to "Tap Run to run it."
        wait(listOf(card("r")), speak = true)
        assertTrue(voice.answer("yes", spoken = true))
        assertTrue(voice.answer("yes", spoken = false))
        assertEquals(emptyList<String>(), answers)
        assertEquals(listOf("Tap Run to run it.", "Tap Run to run it."), notices)
        assertEquals(listOf("Tap Run to run it.", "Tap Run to run it."), plain)
        assertTrue(voice.answer("no", spoken = true))
        assertEquals(listOf("confirm r false"), answers)
        // Another card: a yes answers it.
        wait(listOf(card("s")))
        assertTrue(voice.answer("yes", spoken = true))
        assertEquals(listOf("confirm r false", "confirm s true"), answers)
    }

    @Test fun aCardThatNeedsATapSaysWhyAndACardWithOneChoiceTakesIt() {
        wait(listOf(card("who", tapFor = AskWhy.WHO)), speak = true)
        assertTrue(voice.answer("yes", spoken = true))
        assertEquals(emptyList<String>(), answers)
        assertEquals(listOf(TurnStrings.tapUnsure().render(Langs.EN)), notices)
        assertEquals(listOf(TurnStrings.tapUnsure().render(Langs.EN)), said)
        wait(listOf(card("two", choices = listOf("Mom, mobile", "Mom, home"))))
        assertTrue(voice.answer("yes", spoken = true))
        assertEquals(TurnStrings.tapOne().render(Langs.EN), notices.last())
        wait(listOf(card("share", personal = true)))
        assertTrue(voice.answer("yes", spoken = true))
        assertEquals(emptyList<String>(), answers)
        settings = settings.copy(voiceYesPersonal = true)
        assertTrue(voice.answer("yes", spoken = true))
        wait(listOf(card("one", choices = listOf("Mom, mobile"))))
        assertTrue(voice.answer("go ahead", spoken = true))
        assertEquals(listOf("confirm share true", "choose one 0"), answers)
        // No card waits: nothing to answer.
        wait(listOf(card("one", ActionStatus.DONE)))
        assertFalse(voice.answer("yes", spoken = true))
    }
}
