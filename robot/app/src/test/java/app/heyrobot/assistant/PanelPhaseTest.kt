// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** The panel's phase when a turn ends (0.1.2 review, major 2): only the turn's own phases give way. */
class PanelPhaseTest {
    @Test fun aTurnsOwnPhaseEndsInSpeakingOrIdle() {
        for (p in listOf(Phase.THINKING, Phase.ANSWERING, Phase.CONFIRMING)) {
            assertEquals(Phase.SPEAKING, p.afterTurn(speaking = true))
            assertEquals(Phase.IDLE, p.afterTurn(speaking = false))
        }
    }

    @Test fun listeningForTheNextQuestionIsNotOverwritten() {
        // "Hey robot" during an answer: the stopped turn ends after listening started.
        for (p in listOf(Phase.LISTENING, Phase.TRANSCRIBING, Phase.SPEAKING, Phase.IDLE)) {
            assertEquals(p, p.afterTurn(speaking = true))
            assertEquals(p, p.afterTurn(speaking = false))
        }
    }

    /**
     * 0.4.6: "stop" (said, a tap, a volume key, "Stop speaking") while the reply is still being written ends the turn, as
     * Stop does, so a long story isn't written on for a minute after it with the mic dead; a waiting card stays.
     */
    @Test fun stopWhileTheReplyIsStillComingEndsTheTurn() {
        assertEquals(true, Phase.ANSWERING.stopEndsTurn(turnRunning = true))
        assertEquals(true, Phase.THINKING.stopEndsTurn(turnRunning = true))
        // Only the voice: a card waits for its yes or no, the reply is written and Robot reads it, or nothing runs.
        assertEquals(false, Phase.CONFIRMING.stopEndsTurn(turnRunning = true))
        for (p in listOf(Phase.SPEAKING, Phase.IDLE, Phase.LISTENING, Phase.TRANSCRIBING)) assertEquals(p.name, false, p.stopEndsTurn(turnRunning = true))
        for (p in Phase.entries) assertEquals(p.name, false, p.stopEndsTurn(turnRunning = false))
    }
}
