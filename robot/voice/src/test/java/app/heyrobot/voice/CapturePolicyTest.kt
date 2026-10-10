package app.heyrobot.voice

import app.heyrobot.core.BargeIn
import app.heyrobot.core.BargeTestLogic
import app.heyrobot.voice.CapturePolicy.Capture.COMMUNICATION
import app.heyrobot.voice.CapturePolicy.Capture.RECOGNITION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hear me while Robot talks": which microphone mode, when it may switch, and the wake-word echo guard. */
class CapturePolicyTest {
    private val never = Long.MAX_VALUE
    private val tail = CapturePolicy.TAIL_MS

    @Test fun echoCancellationWhileRobotSpeaksAndForATailAfter() {
        val m = BargeIn.ECHO_CANCEL
        assertEquals(COMMUNICATION, CapturePolicy.desired(m, speaking = true, msSinceSpoke = never, panelOpen = true, testing = false))
        assertEquals(COMMUNICATION, CapturePolicy.desired(m, speaking = false, msSinceSpoke = tail - 1, panelOpen = true, testing = false))
        assertEquals(RECOGNITION, CapturePolicy.desired(m, speaking = false, msSinceSpoke = tail, panelOpen = true, testing = false))
        // An open panel alone doesn't switch: questions get the speech recognition mode.
        assertEquals(RECOGNITION, CapturePolicy.desired(m, speaking = false, msSinceSpoke = never, panelOpen = true, testing = true))
        // Speaking with the panel closed (the voice test, the barge-in test) still gets it.
        assertEquals(COMMUNICATION, CapturePolicy.desired(m, speaking = true, msSinceSpoke = never, panelOpen = false, testing = false))
    }

    @Test fun offIsTheOldBehaviour() {
        for (speaking in listOf(true, false)) for (panel in listOf(true, false)) {
            assertEquals(RECOGNITION, CapturePolicy.desired(BargeIn.OFF, speaking, 0, panel, testing = true))
        }
    }

    @Test fun alwaysKeepsTheCallModeWhileThePanelIsOpen() {
        val m = BargeIn.ALWAYS
        assertEquals(COMMUNICATION, CapturePolicy.desired(m, speaking = false, msSinceSpoke = never, panelOpen = true, testing = false))
        assertEquals(COMMUNICATION, CapturePolicy.desired(m, speaking = false, msSinceSpoke = never, panelOpen = false, testing = true))
        assertEquals(COMMUNICATION, CapturePolicy.desired(m, speaking = true, msSinceSpoke = never, panelOpen = false, testing = false))
        // Panel closed and quiet: back to the best mode for the wake word.
        assertEquals(RECOGNITION, CapturePolicy.desired(m, speaking = false, msSinceSpoke = never, panelOpen = false, testing = false))
    }

    @Test fun neverSwitchesInTheMiddleOfAQuestion() {
        assertEquals(COMMUNICATION, CapturePolicy.next(COMMUNICATION, RECOGNITION, questionOpen = true))
        assertEquals(RECOGNITION, CapturePolicy.next(RECOGNITION, COMMUNICATION, questionOpen = true))
        assertEquals(RECOGNITION, CapturePolicy.next(COMMUNICATION, RECOGNITION, questionOpen = false))
        assertEquals(COMMUNICATION, CapturePolicy.next(RECOGNITION, COMMUNICATION, questionOpen = false))
    }

    @Test fun robotSayingAWakeWordIsGuardedLikeStop() {
        val rate = 24_000
        val g = EchoGuard(rate)
        g.wakeWords = EchoGuard.wakeRegex(listOf("robot", "jarvis"))
        g.onPiece(0, rate * 2L, "Hi, I'm Robot.")
        g.onPiece(rate * 2L, rate * 4L, "The weather is mild.")
        assertTrue(g.mayBeWakeEcho(rate * 1L))
        assertFalse(g.mayBeEcho(rate * 1L))
        assertTrue(g.mayBeWakeEcho(rate * 3L))
        assertFalse(g.mayBeWakeEcho(rate * 4L))
        val r = EchoGuard.wakeRegex(listOf("robot"))!!
        for (t in listOf("Robot's turn", "two robots", "ROBOT")) assertTrue(t, r.containsMatchIn(t))
        for (t in listOf("robotic", "a probe")) assertFalse(t, r.containsMatchIn(t))
        assertNull(EchoGuard.wakeRegex(emptyList()))
    }

    @Test fun theBargeInParagraphHasNothingTheGuardsWouldCatch() {
        assertFalse(EchoGuard.soundsLikeStop(BargeTestLogic.PARAGRAPH))
        assertFalse(EchoGuard.wakeRegex(listOf("robot", "computer", "buddy", "jarvis"))!!.containsMatchIn(BargeTestLogic.PARAGRAPH))
    }

    @Test fun gateSpeechIsThinnedForTheLog() {
        val g = GateSpeechLog(minGapMs = 10_000)
        assertEquals(1200 to 1, g.onSpeech(1200, 0))
        assertNull(g.onSpeech(800, 2_000))
        assertNull(g.onSpeech(3000, 5_000))
        assertEquals(3000 to 3, g.onSpeech(500, 12_000))
    }
}
