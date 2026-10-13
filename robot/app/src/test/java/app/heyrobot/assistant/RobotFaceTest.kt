package app.heyrobot.assistant

import app.heyrobot.ui.common.RobotFace
import app.heyrobot.ui.common.faceDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

/** The robot in the panel's header for every phase, with a card listening for yes or no, and while Robot's voice reads aloud. */
class RobotFaceTest {
    /** Each phase alone: nothing heard for a card, the voice quiet (the hand-off's table). */
    @Test fun everyPhaseHasItsFace() {
        val want = mapOf(
            Phase.IDLE to RobotFace.IDLE,
            Phase.CONFIRMING to RobotFace.IDLE,
            Phase.LISTENING to RobotFace.LISTENING,
            Phase.TRANSCRIBING to RobotFace.LISTENING,
            Phase.THINKING to RobotFace.TYPING,
            Phase.ANSWERING to RobotFace.TYPING,
            Phase.SPEAKING to RobotFace.SPEAKING,
        )
        assertEquals(Phase.entries.toSet(), want.keys)
        for ((p, f) in want) assertEquals(p.name, f, p.face(cardHearing = null, talking = false))
    }

    /** A card listening for "yes" or "no" (the phase stays CONFIRMING) shows the listening robot, while it hears and writes it down. */
    @Test fun aCardListeningForYesOrNoListens() {
        for (hearing in listOf(Phase.LISTENING, Phase.TRANSCRIBING)) {
            assertEquals(RobotFace.LISTENING, Phase.CONFIRMING.face(cardHearing = hearing, talking = false))
            assertEquals(RobotFace.LISTENING, Phase.CONFIRMING.face(cardHearing = hearing, talking = true))
        }
    }

    /** Robot's voice reading aloud: the speaking robot in every phase but listening, which wins. */
    @Test fun theVoiceSpeaksUnlessRobotListens() {
        val want = mapOf(
            Phase.IDLE to RobotFace.SPEAKING,
            Phase.CONFIRMING to RobotFace.SPEAKING,
            Phase.THINKING to RobotFace.SPEAKING,
            Phase.ANSWERING to RobotFace.SPEAKING,
            Phase.SPEAKING to RobotFace.SPEAKING,
            Phase.LISTENING to RobotFace.LISTENING,
            Phase.TRANSCRIBING to RobotFace.LISTENING,
        )
        assertEquals(Phase.entries.toSet(), want.keys)
        for ((p, f) in want) assertEquals(p.name, f, p.face(cardHearing = null, talking = true))
    }

    /** The state the panel draws from: its phase, a card's listening and the voice. */
    @Test fun thePanelStateGivesTheFace() {
        assertEquals(RobotFace.IDLE, PanelState("c1").face)
        assertEquals(RobotFace.TYPING, PanelState("c1", phase = Phase.ANSWERING).face)
        assertEquals(RobotFace.SPEAKING, PanelState("c1", phase = Phase.ANSWERING, talking = true).face)
        assertEquals(RobotFace.LISTENING, PanelState("c1", phase = Phase.CONFIRMING, cardHearing = Phase.LISTENING).face)
    }

    /** The speaking robot waits out the pause between two sentences before it turns idle or typing; nothing else waits. */
    @Test fun onlyTheSpeakingFaceWaits() {
        for (from in RobotFace.entries) for (to in RobotFace.entries) {
            val want = if (from == RobotFace.SPEAKING && (to == RobotFace.IDLE || to == RobotFace.TYPING)) 400L else 0L
            assertEquals("$from -> $to", want, faceDelayMs(from, to))
        }
    }
}
