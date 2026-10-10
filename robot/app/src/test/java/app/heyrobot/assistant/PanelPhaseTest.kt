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
}
