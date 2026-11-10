// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Handler
import android.os.Looper
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Cue
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.voice.SpeechToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
 * fix-covered-partial-b (review-covered-partial-b M2, Lows): car mode's refused Yes on an action card, through
 * PanelCardVoice itself with a fake microphone and voice. Robot's own line never goes into a listen still open; the
 * line's listen is once only; "say no" only when something listens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoveredCardVoiceTest {
    private val log = mutableListOf<String>()
    private val en = Langs.of(Lang.EN)

    private inner class Stt : SpeechToText {
        override val available = true
        var starts = 0
        var listener: SpeechToText.Listener? = null
        override fun start(listener: SpeechToText.Listener, shortAnswer: Boolean) { starts++; this.listener = listener; log += "listen" }
        override fun stop() {}
        override fun cancel() { log += "cancel" }
        override fun release() {}
    }

    private val stt = Stt()
    private val card = UiAction("c1", null, "Text Sam: on my way", ActionStatus.PENDING, null)

    private fun voice(hearing: Phase?, settings: RobotSettings = RobotSettings()): Pair<PanelCardVoice, MutableStateFlow<PanelState>> {
        val state = MutableStateFlow(PanelState("conv", turns = listOf(UiTurn("t1", TurnRole.ASSISTANT, "", actions = listOf(card))),
            phase = Phase.CONFIRMING, cardHearing = hearing))
        val share = PanelScreenShare(CoroutineScope(Job()), state, { true }, { 0L }, { en }, { null to null })
        val port = object : PanelCardVoice.Port {
            override val main = Handler(Looper.getMainLooper())
            override val stt: SpeechToText get() = this@CoveredCardVoiceTest.stt
            override val settings = settings
            override val speaking = false
            override val shown = true
            override val spokenTurn = true
            override val turnLangs = en
            override fun screenLangs() = en
            override val routineTapOnly: Pair<String, String>? = null
            override fun wakePaused(paused: Boolean) {}
            override fun notify(text: String) {}
            override fun speakRobot(text: String) { log += "say:$text" }
            override fun said(p: Phrase) = p.render(en)
            override fun speak(text: String) { log += "say:$text" }
            override fun cue(c: Cue) {}
            override fun confirm(actionId: String, yes: Boolean) { log += "confirm:$yes" }
            override fun choose(actionId: String, index: Int) {}
            override val carQuiet = true
            override val carTrusted = true
        }
        return PanelCardVoice(state, PanelReplies(state), share, port) to state
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    private val said get() = log.filter { it.startsWith("say:") }

    @Test fun anOpenListenIsCancelledBeforeTheLine() {
        val (v, state) = voice(Phase.LISTENING)
        v.covered("c1")
        val cancel = log.indexOf("cancel")
        assertTrue("the card's listen is cancelled before Robot's line: $log", cancel >= 0 && cancel < log.indexOfFirst { it.startsWith("say:") })
        assertNull("the card no longer shows it listens", state.value.cardHearing)
    }

    @Test fun theLinesListenIsOnceOnly() {
        val (v, _) = voice(null)
        v.covered("c1")
        v.speakingStopped(); idle()
        assertEquals("one listen after the line", 1, stt.starts)
        stt.listener!!.onFinal("hmm"); idle()
        v.speakingStopped(); idle()
        assertEquals("no second listen: $log", 1, stt.starts)
        assertEquals("no reminder said: $said", 1, said.size)
    }

    @Test fun noListenNoSayNo() {
        val (v, _) = voice(null, RobotSettings(voiceCards = false))
        v.covered("c1")
        v.speakingStopped(); idle()
        assertEquals("nothing listens", 0, stt.starts)
        assertFalse("never 'say no' when nothing listens: $said", said.single().contains("say no", ignoreCase = true))
        assertFalse(said.single().contains("Say yes or no"))
    }
}
