// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Looper
import app.heyrobot.core.headset.SessionPhase
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 1.1, other apps' sound while you ask: the panel's side (PanelMediaPause). Its turn goes on while it is open and busy,
 * or a headset session is between its question and the end of its follow-up, and is over otherwise, whichever way the
 * turn ends: the answer spoken, the reply only on screen, Stop, an error, nothing heard, the follow-up window closing,
 * the panel closed. What the voice process then does with "keep" and "end" (the grace, the voice still speaking, the
 * safety limit) is voice TurnFocusTest's. The phase is followed on the main looper, so the looper drives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelMediaPauseTest {
    private val scope = MainScope()
    private val looper = shadowOf(Looper.getMainLooper())
    private val state = MutableStateFlow(PanelState("c"))
    private var shown = true
    private var session: SessionPhase? = null
    private val calls = mutableListOf<String>()

    private val pause = PanelMediaPause(state, object : PanelMediaPause.Port {
        override val shown get() = this@PanelMediaPauseTest.shown
        override val sessionHolds get() = PanelMediaPause.sessionHolds(session)
        override fun keep() { calls += "keep" }
        override fun end(now: Boolean) { calls += if (now) "end now" else "end" }
    })

    @Before fun start() {
        pause.start(scope)
        idle()
        calls.clear()
    }

    @After fun stop() = scope.cancel()

    private fun idle() = looper.idleFor(Duration.ZERO)

    private fun phases(vararg ps: Phase) = ps.forEach { p ->
        state.update { it.copy(phase = p) }
        idle()
    }

    @Test fun theRule() {
        for (p in Phase.entries) {
            assertEquals(p.name, p != Phase.IDLE, PanelMediaPause.inTurn(shown = true, phase = p, sessionHolds = false))
            assertTrue(p.name, PanelMediaPause.inTurn(shown = true, phase = p, sessionHolds = true))
            assertFalse("a closed panel holds nothing: ${p.name}", PanelMediaPause.inTurn(shown = false, phase = p, sessionHolds = true))
        }
        for (s in SessionPhase.entries) {
            assertEquals(s.name, s != SessionPhase.IDLE && s != SessionPhase.ENDED, PanelMediaPause.sessionHolds(s))
        }
        assertFalse(PanelMediaPause.sessionHolds(null))
    }

    @Test fun theAnswerSpoken() {
        phases(Phase.LISTENING, Phase.TRANSCRIBING, Phase.THINKING, Phase.ANSWERING, Phase.SPEAKING, Phase.IDLE)
        assertEquals(listOf("keep", "keep", "keep", "keep", "keep", "end"), calls)
    }

    @Test fun theReplyOnlyOnScreen() {
        phases(Phase.LISTENING, Phase.TRANSCRIBING, Phase.THINKING, Phase.ANSWERING, Phase.IDLE)
        assertEquals("end", calls.last())
        assertEquals(1, calls.count { it.startsWith("end") })
    }

    @Test fun cancelledWhileListeningOrThinking() {
        phases(Phase.LISTENING, Phase.IDLE)
        phases(Phase.LISTENING, Phase.TRANSCRIBING, Phase.THINKING, Phase.IDLE)
        assertEquals(listOf("keep", "end", "keep", "keep", "keep", "end"), calls)
    }

    @Test fun aCardWaitingIsStillTheTurn() {
        phases(Phase.LISTENING, Phase.THINKING, Phase.CONFIRMING, Phase.ANSWERING, Phase.IDLE)
        assertEquals(listOf("keep", "keep", "keep", "keep", "end"), calls)
    }

    @Test fun nothingHeardOrAnError() {
        // Nothing heard: listening ends in idle; an error (failReply) too.
        phases(Phase.LISTENING, Phase.IDLE, Phase.LISTENING, Phase.TRANSCRIBING, Phase.THINKING, Phase.IDLE)
        assertEquals(2, calls.count { it == "end" })
        assertEquals("end", calls.last())
    }

    @Test fun thePanelClosed() {
        phases(Phase.LISTENING, Phase.THINKING)
        pause.hidden()
        assertEquals(listOf("keep", "keep", "end now"), calls)
    }

    @Test fun aHeadsetSessionHoldsTheTurnUntilItsFollowUpWindowCloses() {
        session = SessionPhase.LISTENING
        phases(Phase.LISTENING, Phase.THINKING)
        session = SessionPhase.BUSY
        phases(Phase.SPEAKING, Phase.IDLE)
        // Between the answer and the follow-up's chime and listen, the panel is idle but the session isn't done.
        assertEquals(listOf("keep", "keep", "keep", "keep"), calls)
        session = SessionPhase.FOLLOW_UP
        phases(Phase.LISTENING)
        // The window closes with nothing said: listening stops quietly (the panel idle at once), then the session ends
        // and sessionEnded checks; the panel's own check follows on the looper.
        session = SessionPhase.ENDED
        state.update { it.copy(phase = Phase.IDLE) }
        pause.check()
        idle()
        assertEquals(listOf("keep", "keep", "keep", "keep", "keep", "end", "end"), calls)
    }

    @Test fun aSessionEndingAfterItsAnswerWithNoFollowUp() {
        session = SessionPhase.BUSY
        phases(Phase.LISTENING, Phase.THINKING, Phase.IDLE)
        assertEquals("keep", calls.last())
        session = SessionPhase.ENDED
        pause.check()
        assertEquals("end", calls.last())
    }

    @Test fun theSamePhaseTwiceIsOneCheck() {
        phases(Phase.THINKING)
        state.update { it.copy(partial = "more text") }
        idle()
        phases(Phase.THINKING)
        assertEquals(listOf("keep"), calls)
    }
}
