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

    @Test fun theSwitchBackWaitsWhileTheWakeGateHearsSpeech() {
        // A "hey robot" said just after Robot stops would straddle the switch: wait, up to MAX_HOLD_MS.
        assertEquals(COMMUNICATION, CapturePolicy.next(COMMUNICATION, RECOGNITION, questionOpen = false, gateSpeech = true, heldMs = 500))
        assertEquals(RECOGNITION, CapturePolicy.next(COMMUNICATION, RECOGNITION, questionOpen = false, gateSpeech = true, heldMs = CapturePolicy.MAX_HOLD_MS))
        assertEquals(RECOGNITION, CapturePolicy.next(COMMUNICATION, RECOGNITION, questionOpen = false, gateSpeech = false, heldMs = 500))
        // Robot's own voice is speech too: the switch into the call mode never waits for silence.
        assertEquals(COMMUNICATION, CapturePolicy.next(RECOGNITION, COMMUNICATION, questionOpen = false, gateSpeech = true, heldMs = 0))
    }

    /**
     * The wake lines are built on another thread while the listener starts; a value copied into the listener before
     * it was published was lost when the build finished in between, and the wake word stayed deaf. The listener now
     * reads the shared inputs on every frame, so whatever the order, it sees the lines.
     */
    @Test fun aListenerStartedBeforeTheWakeLinesAreBuiltStillGetsThem() {
        val inputs = KeywordInputs()
        val builtAt = java.util.concurrent.CountDownLatch(1)
        val published = java.util.concurrent.CountDownLatch(1)
        // The listener is made (and starts reading frames) with the lines still empty...
        val seen = mutableListOf<String>()
        val frames = Thread {
            seen += inputs.wakeLines
            builtAt.await()
            published.countDown()
            repeat(3) { seen += inputs.wakeLines }
        }
        frames.start()
        // ... and the worker finishes the build before the listener is published.
        val worker = Thread {
            inputs.wakeLines = "▁HE Y ▁RO B O T :1.5 #0.25 @W1"
            builtAt.countDown()
        }
        worker.start()
        worker.join()
        published.await()
        frames.join()
        assertEquals("", seen.first())
        assertEquals("▁HE Y ▁RO B O T :1.5 #0.25 @W1", seen.last())
        // Speaking likewise: a listener swapped in while Robot speaks still listens for "stop".
        inputs.speaking = true
        assertTrue(inputs.speaking)
    }

    @Test fun theSystemVoiceCountsAsStuckWhenQuiet() {
        var q = 0
        for (busy in listOf(false, false)) q = SpeechWatchdog.next(q, busy)
        assertFalse(SpeechWatchdog.stuck(q, ready = true))
        q = SpeechWatchdog.next(q, busy = false)
        assertTrue(SpeechWatchdog.stuck(q, ready = true))
        // Starting takes longer before it counts.
        assertFalse(SpeechWatchdog.stuck(q, ready = false))
        assertTrue(SpeechWatchdog.stuck(SpeechWatchdog.STARTING_CHECKS, ready = false))
        // Any speech resets the count.
        assertEquals(0, SpeechWatchdog.next(5, busy = true))
    }

    @Test fun callAudioTakesTheModeOnlyFromNormal() {
        assertTrue(CallAudio.mayTake(android.media.AudioManager.MODE_NORMAL, alreadyOurs = false))
        // Another app's call: Robot plays as assistant audio instead.
        assertFalse(CallAudio.mayTake(android.media.AudioManager.MODE_IN_COMMUNICATION, alreadyOurs = false))
        assertFalse(CallAudio.mayTake(android.media.AudioManager.MODE_IN_CALL, alreadyOurs = false))
        assertTrue(CallAudio.mayTake(android.media.AudioManager.MODE_IN_COMMUNICATION, alreadyOurs = true))
    }

    /** Each capture switch must unregister the old record's recording callback, or a thread and a registration leak. */
    @Test fun everyClosedCaptureUnregistersItsCallback() {
        val src = java.io.File(System.getProperty("user.dir"), "src/main/java/app/heyrobot/voice/Listener.kt").readText()
        val close = src.substringAfter("private fun close(r: AudioRecord)").substringBefore("\n    }")
        assertTrue(close.contains("unregisterAudioRecordingCallback"))
        assertTrue(close.indexOf("unregisterAudioRecordingCallback") < close.indexOf("release()"))
        // reopen and stop release through close(); the only other release() calls are before a callback exists.
        assertTrue(src.substringAfter("private fun reopen(").substringBefore("fun stop(").contains("rec?.let(::close)"))
        assertTrue(src.substringAfter("fun stop(").contains("rec?.let(::close)"))
        val registerAt = src.indexOf("registerAudioRecordingCallback(callbackExec")
        val openStarted = src.substringAfter("private fun openStarted(").substringBefore("private fun close(")
        assertTrue(openStarted.substringAfter("registerAudioRecordingCallback(callbackExec").contains("recCallback = cb"))
        assertTrue(registerAt > 0)
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

    @Test fun aStretchOfRobotTalkingIsSummedForTheLog() {
        val r = SpeakRun(rate = 16_000)
        assertNull(r.end())
        // 1 s of her residual at -60 dBFS with the gate shut, then 0.5 s of you at -30 dBFS with it open; "stop" listened
        // for all along except the first 0.5 s.
        repeat(1000) { r.frame(16, -60.0, gate = false, stop = it >= 500) }
        repeat(500) { r.frame(16, -30.0, gate = true, stop = true) }
        val (gateMs, minusDb, stopPct) = r.end()!!
        assertEquals(500, gateMs)
        assertEquals(50, minusDb)
        assertEquals(66, stopPct)
        // It starts over, and silence counts as -120 dBFS, not minus infinity.
        assertNull(r.end())
        r.frame(16, Double.NEGATIVE_INFINITY, gate = false, stop = false)
        assertEquals(Triple(0, 120, 0), r.end())
    }

    @Test fun gateSpeechIsThinnedForTheLog() {
        val g = GateSpeechLog(minGapMs = 10_000)
        assertEquals(1200 to 1, g.onSpeech(1200, 0))
        assertNull(g.onSpeech(800, 2_000))
        assertNull(g.onSpeech(3000, 5_000))
        assertEquals(3000 to 3, g.onSpeech(500, 12_000))
    }
}
