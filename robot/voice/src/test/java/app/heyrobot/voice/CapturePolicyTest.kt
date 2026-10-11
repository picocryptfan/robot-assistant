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

    /** "Interrupt sensitivity": the talking lines replace the idle ones only while Robot speaks, and only when set. */
    @Test fun theTalkingWakeLinesApplyOnlyWhileRobotSpeaks() {
        val inputs = KeywordInputs()
        inputs.wakeLines = "▁HE Y ▁RO B O T :1.5 #0.25 @W1"
        assertEquals(inputs.wakeLines, inputs.wake(speaking = false))
        assertEquals(inputs.wakeLines, inputs.wake(speaking = true))
        assertEquals(app.heyrobot.core.InterruptSensitivity.MEDIUM, inputs.interrupt)
        inputs.talkWakeLines = "▁HE Y ▁RO B O T :1.75 #0.2 @W1"
        assertEquals("▁HE Y ▁RO B O T :1.75 #0.2 @W1", inputs.wake(speaking = true))
        assertEquals("▁HE Y ▁RO B O T :1.5 #0.25 @W1", inputs.wake(speaking = false))
    }

    /**
     * 0.5.0: while a conversation is transcribed (without "Hey robot works during a conversation") the wake phrases
     * sleep, whatever Robot is doing; "stop" while she talks is added by the spotter itself, so it still works. When the
     * session ends the phrases are back.
     */
    @Test fun aConversationPutsTheWakePhrasesToSleep() {
        val inputs = KeywordInputs()
        inputs.wakeLines = "▁HE Y ▁RO B O T :1.5 #0.25 @W1"
        inputs.talkWakeLines = "▁HE Y ▁RO B O T :1.75 #0.2 @W1"
        inputs.wakeOff = true
        assertEquals("", inputs.wake(speaking = false))
        assertEquals("", inputs.wake(speaking = true))
        inputs.wakeOff = false
        assertEquals("▁HE Y ▁RO B O T :1.5 #0.25 @W1", inputs.wake(speaking = false))
        assertEquals("▁HE Y ▁RO B O T :1.75 #0.2 @W1", inputs.wake(speaking = true))
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
        // 0.3.1: any word starting with it ("robotics" fires "robot" as easily as "robots").
        for (t in listOf("Robot's turn", "two robots", "ROBOT", "robotic", "Robotics is a broad field.")) assertTrue(t, r.containsMatchIn(t))
        for (t in listOf("a probe", "Roberta")) assertFalse(t, r.containsMatchIn(t))
        assertTrue(EchoGuard.wakeRegex(listOf("jarvis"))!!.containsMatchIn("Jarvis's"))
        assertNull(EchoGuard.wakeRegex(emptyList()))
    }

    @Test fun theBargeInParagraphHasNothingTheGuardsWouldCatch() {
        assertFalse(EchoGuard.soundsLikeStop(BargeTestLogic.PARAGRAPH))
        // Word by word, and wider than the guard: no s-t, up to three letters, p anywhere in a word.
        for (w in BargeTestLogic.PARAGRAPH.split(Regex("[^A-Za-z']+"))) assertFalse(w, Regex("st[a-z]{0,3}p", RegexOption.IGNORE_CASE).containsMatchIn(w))
        assertFalse(EchoGuard.wakeRegex(listOf("robot", "computer", "buddy", "jarvis", "hey"))!!.containsMatchIn(BargeTestLogic.PARAGRAPH))
    }

    @Test fun theTrickyParagraphIsAllWordsLikeStop() {
        // Every sentence has one, and it lasts about as long as the other paragraph.
        val sentences = BargeTestLogic.TRICKY_PARAGRAPH.split(Regex("(?<=[.!?])\\s+"))
        assertTrue(sentences.size >= 4)
        for (s in sentences) assertTrue(s, EchoGuard.soundsLikeStop(s))
        val words = BargeTestLogic.TRICKY_PARAGRAPH.split(Regex("\\s+")).size
        assertTrue("$words words", words in 45..70)
        val lower = BargeTestLogic.TRICKY_PARAGRAPH.lowercase()
        for (w in listOf("step", "steep", "stoop", "stamp", "stomp", "stump", "stupid", "strip", "stop", "nonstop", "doorstep")) assertTrue(w, lower.contains(w))
        // No wake phrase: only "stop" is measured.
        assertFalse(EchoGuard.wakeRegex(listOf("robot", "computer", "hey"))!!.containsMatchIn(BargeTestLogic.TRICKY_PARAGRAPH))
    }

    /**
     * 0.3.1: "stop" over Robot's voice. While she speaks, a second spotter listens, one that fires after 2 blank frames
     * (sherpa-onnx's C++ default), and the speech gate doesn't decide what it hears. When she is quiet the first spotter
     * (3 blank frames, as phase 0 measured it) and the gate work as before.
     */
    @Test fun whileRobotSpeaksTheSpotterHearsEveryFrame() {
        assertEquals(2, WakeWord.IDLE_TRAILING_BLANKS)
        assertEquals(1, WakeWord.TALK_TRAILING_BLANKS)
        val src = java.io.File(System.getProperty("user.dir"), "src/main/java/app/heyrobot/voice/WakeWord.kt").readText()
        assertTrue(src.contains("private val kws: KeywordSpotter = spotter(IDLE_TRAILING_BLANKS)"))
        assertTrue(src.substringAfter("fun loadTalkSpotter()").contains("spotter(TALK_TRAILING_BLANKS)"))
        val accept = src.substringAfter("fun accept(").substringBefore("private fun gate(")
        // The talk spotter only while she speaks, and a stream is dropped when its spotter isn't the one wanted.
        assertTrue(accept.contains("val spotter = if (stop != null) talkKws ?: kws else kws"))
        assertTrue(accept.contains("streamKws !== spotter"))
        // The bypass comes after the gate has run (it still feeds the log and the capture switch) and before its decision.
        val bypass = accept.indexOf("if (stop != null) {")
        assertTrue(bypass > accept.indexOf("gate(speech)") && bypass > accept.indexOf("preRoll.push(samples)"))
        assertTrue(bypass < accept.indexOf("speech && s == null"))
        // A stream is decoded by the spotter that made it; the audio before a hit, or before a pause, is never fed again.
        val feed = src.substringAfter("private fun feed(").substringBefore("fun reset()")
        assertTrue(feed.contains("val k = streamKws") && feed.contains("k.decode(s)") && !feed.contains("kws.decode"))
        assertTrue(feed.contains("preRoll.clear()"))
        assertTrue(src.substringAfter("fun resume()").substringBefore("fun release()").contains("preRoll.clear()"))
        val listener = java.io.File(System.getProperty("user.dir"), "src/main/java/app/heyrobot/voice/Listener.kt").readText()
        assertTrue(listener.contains("if (wasPaused && !paused) try { ww?.resume() }"))
        // Nor across the start or end of her speech.
        assertTrue(listener.contains("if (speaking != wasSpeaking) try { ww?.clearPreRoll() }"))
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
