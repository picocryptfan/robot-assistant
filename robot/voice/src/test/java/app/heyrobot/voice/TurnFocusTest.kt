// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.media.AudioManager
import app.heyrobot.core.Playback
import app.heyrobot.core.output.VolumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1, other apps' sound while you ask (TurnFocus): the focus asked for in each phase (a question listening, a short
 * answer, a long answer, OutputGuard's DUCK_OTHERS and PAUSE_OTHERS), the voice's request nested in the question's (one
 * requester: letting go of the voice never hands the focus back mid-turn), the release on every way a turn ends, as the
 * panel reports it (PanelMediaPause: keep while busy, end when idle, end now when closed), the safety limit, and calls
 * left alone. The port records what Android was asked, in order; its clock is driven by hand.
 */
class TurnFocusTest {
    private class FakePort : FocusKeeper.Port {
        var mode = AudioManager.MODE_NORMAL
        var ours = false
        val asked = mutableListOf<String>()
        /** Pending timers by runnable, with the time they fire. */
        val timers = LinkedHashMap<Runnable, Long>()
        var now = 0L
        override fun mayTake() = FocusPlan.mayTake(mode, ours)
        override fun request(gain: Int, p: Playback) { asked += "take ${name(gain)}${if (p == Playback.CALL) " call" else ""}" }
        override fun abandon() { asked += "give" }
        override fun later(r: Runnable, ms: Long) { timers[r] = now + ms }
        override fun cancel(r: Runnable) { timers.remove(r) }

        /** Moves the clock on by [ms], running each timer that comes due. */
        fun pass(ms: Long) {
            val until = now + ms
            while (true) {
                val next = timers.entries.filter { it.value <= until }.minByOrNull { it.value } ?: break
                timers.remove(next.key)
                now = next.value
                next.key.run()
            }
            now = until
        }

        fun name(g: Int) = when (g) {
            FocusPlan.EXCLUSIVE -> "exclusive"
            FocusPlan.TRANSIENT -> "transient"
            FocusPlan.MAY_DUCK -> "duck"
            else -> "?$g"
        }
    }

    private val port = FakePort()
    private val k = FocusKeeper(port)
    private val sherpa = Any()
    private val phoneVoice = Any()
    private val grace = FocusKeeper.GRACE_MS

    /** The panel's view of a turn's phases (PanelMediaPause.check on each change). */
    private fun busy() = k.keep()
    private fun idle() = k.end(now = false)
    private fun speak(on: Boolean, owner: Any = sherpa, p: Playback = Playback.ASSISTANT) = k.speaking(owner, p, on)

    // ------------------------------------------------------------------ the focus per phase

    @Test fun thePlanPerPhase() {
        // A question listening (and everything after it in the turn): exclusive, the music pauses.
        assertEquals(FocusPlan.EXCLUSIVE, FocusPlan.gain(turn = true, speaking = false, pause = false, mayTake = true))
        assertEquals(FocusPlan.EXCLUSIVE, FocusPlan.gain(turn = true, speaking = true, pause = false, mayTake = true))
        assertEquals(FocusPlan.EXCLUSIVE, FocusPlan.gain(turn = true, speaking = true, pause = true, mayTake = true))
        // Outside a question: a short line ducks, a long answer pauses.
        assertEquals(FocusPlan.MAY_DUCK, FocusPlan.gain(turn = false, speaking = true, pause = false, mayTake = true))
        assertEquals(FocusPlan.TRANSIENT, FocusPlan.gain(turn = false, speaking = true, pause = true, mayTake = true))
        assertEquals(FocusPlan.NONE, FocusPlan.gain(turn = false, speaking = false, pause = true, mayTake = true))
        // During a call: never more than the voice asked before 1.1.
        assertEquals(FocusPlan.NONE, FocusPlan.gain(turn = true, speaking = false, pause = false, mayTake = false))
        assertEquals(FocusPlan.MAY_DUCK, FocusPlan.gain(turn = true, speaking = true, pause = true, mayTake = false))
        // Android's own values, so a typo can't swap them.
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, FocusPlan.EXCLUSIVE)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, FocusPlan.TRANSIENT)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, FocusPlan.MAY_DUCK)
    }

    @Test fun outputGuardsVolumeMapsToTheVoicesAsk() {
        assertTrue(FocusPlan.pauses(VolumePolicy.PAUSE_OTHERS))
        for (v in listOf(VolumePolicy.NORMAL, VolumePolicy.DUCK_OTHERS, VolumePolicy.CALL)) assertFalse(v.name, FocusPlan.pauses(v))
    }

    @Test fun listeningTakesExclusiveFocusBeforeAnything() {
        assertTrue(k.listen())
        assertEquals(listOf("take exclusive"), port.asked)
        assertTrue(k.holding)
    }

    @Test fun aShortAnswerOutsideAQuestionDucks() {
        k.volume(VolumePolicy.DUCK_OTHERS)
        speak(true)
        speak(false)
        assertEquals(listOf("take duck", "give"), port.asked)
    }

    @Test fun aLongAnswerOutsideAQuestionPauses() {
        k.volume(VolumePolicy.PAUSE_OTHERS)
        speak(true)
        speak(false)
        assertEquals(listOf("take transient", "give"), port.asked)
    }

    @Test fun aLongSentenceLaterInTheRunTurnsTheDuckIntoAPauseAndNeverBack() {
        k.volume(VolumePolicy.DUCK_OTHERS)
        speak(true)
        k.volume(VolumePolicy.PAUSE_OTHERS)
        // The music has paused now, so OutputGuard sees nothing playing: the run stays paused.
        k.volume(VolumePolicy.NORMAL)
        speak(false)
        // The next run starts from its own first sentence.
        k.volume(VolumePolicy.DUCK_OTHERS)
        speak(true)
        speak(false)
        assertEquals(listOf("take duck", "take transient", "give", "take duck", "give"), port.asked)
    }

    @Test fun callAudioKeepsItsAttributesOutsideAQuestion() {
        speak(true, p = Playback.CALL)
        assertEquals(listOf("take duck call"), port.asked)
    }

    // ------------------------------------------------------------------ nesting

    @Test fun theVoiceNestsInsideTheQuestionAndNeverHandsTheFocusBack() {
        k.listen()
        busy() // transcribing
        busy() // thinking
        k.volume(VolumePolicy.PAUSE_OTHERS)
        speak(true)
        // A streamed answer: the voice stops between two sentences while the next is written.
        speak(false)
        port.pass(2_000)
        speak(true)
        speak(false)
        assertEquals("only the question's request, never let go while the turn runs", listOf("take exclusive"), port.asked)
        assertEquals(FocusPlan.EXCLUSIVE, k.gain)
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun theVoiceInCallAudioKeepsTheQuestionsRequest() {
        k.listen()
        port.ours = true // CallAudio.begin: Robot's own communication mode
        port.mode = AudioManager.MODE_IN_COMMUNICATION
        speak(true, p = Playback.CALL)
        speak(false, p = Playback.CALL)
        port.ours = false
        port.mode = AudioManager.MODE_NORMAL
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun thePhonesVoiceTakingOverFromRobotsKeepsTheTurn() {
        k.listen()
        speak(true, sherpa)
        // SherpaTextToSpeech.fallBack: it lets go, the phone's voice carries on.
        speak(false, sherpa)
        speak(true, phoneVoice)
        speak(false, phoneVoice)
        assertEquals(listOf("take exclusive"), port.asked)
    }

    // ------------------------------------------------------------------ every way a turn ends

    /** A spoken question, its answer said aloud, then the panel idle: the music comes back after the grace. */
    @Test fun theAnswerSpoken() {
        k.listen()
        busy(); busy()
        speak(true)
        busy() // the panel shows "speaking"
        speak(false)
        idle()
        port.pass(grace - 1)
        assertEquals(listOf("take exclusive"), port.asked)
        port.pass(1)
        assertEquals(listOf("take exclusive", "give"), port.asked)
        assertFalse(k.holding)
    }

    @Test fun theReplyOnlyOnScreen() {
        k.listen()
        busy(); busy(); busy() // transcribing, thinking, answering on screen
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun cancelledWhileListening() {
        k.listen()
        // Stop: the question is dropped and the panel goes idle.
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun cancelledWhileTheAnswerIsSpoken() {
        k.listen()
        busy()
        speak(true)
        idle() // Stop: the panel is idle at once, the voice stops a moment later
        port.pass(grace * 3)
        assertEquals("not while the voice still plays", listOf("take exclusive"), port.asked)
        speak(false)
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun thePanelClosed() {
        k.listen()
        busy()
        k.end(now = true)
        assertEquals("at once, without the grace", listOf("take exclusive", "give"), port.asked)
        assertTrue(port.timers.isEmpty())
    }

    @Test fun thePanelClosedWhileTheVoiceFinishes() {
        k.listen()
        speak(true)
        k.end(now = true)
        assertEquals(listOf("take exclusive"), port.asked)
        speak(false)
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun nothingHeard() {
        k.listen()
        idle() // "I didn't catch that": a chime, the panel idle
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun nothingHeardWithSorrySaidAloud() {
        k.listen()
        speak(true) // "Sorry, I didn't catch that."
        idle()
        speak(false)
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun anError() {
        k.listen()
        busy(); busy()
        idle() // failReply
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun aHeadsetSessionsFollowUpWindowClosing() {
        k.listen()
        busy()
        speak(true)
        speak(false)
        // The panel is idle, but the session holds the turn (PanelMediaPause): its chime, then the follow-up listen.
        busy()
        port.pass(850)
        k.listen()
        port.pass(6_000)
        // The window closes with nothing said (stopListeningQuietly): the panel idle, the session over.
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun aListenThatFollowsAtOnceKeepsTheMusicPaused() {
        k.listen()
        speak(true)
        speak(false)
        idle()
        // 0.7's listen 350 ms after a message read aloud, or a card's answer.
        port.pass(350)
        k.listen()
        port.pass(grace * 2)
        assertEquals(listOf("take exclusive"), port.asked)
    }

    @Test fun theSafetyLimit() {
        k.listen()
        busy() // thinking, and then nothing more ever comes
        port.pass(FocusKeeper.SAFETY_MS - 1)
        assertEquals(listOf("take exclusive"), port.asked)
        port.pass(1)
        assertEquals(listOf("take exclusive", "give"), port.asked)
        assertFalse(k.holding)
        // The panel ending the turn later changes nothing.
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun theSafetyLimitCountsFromTheLastThingHeardSpokenOrDone() {
        k.listen()
        port.pass(40_000)
        busy()
        port.pass(40_000)
        speak(true)
        // An answer read for longer than the limit keeps the music paused.
        port.pass(FocusKeeper.SAFETY_MS * 2)
        assertEquals(listOf("take exclusive"), port.asked)
        speak(false)
        port.pass(FocusKeeper.SAFETY_MS)
        assertEquals(listOf("take exclusive", "give"), port.asked)
    }

    @Test fun theSafetyLimitIsAMinute() {
        assertEquals(60_000L, FocusKeeper.SAFETY_MS)
        assertTrue(FocusKeeper.GRACE_MS in 351L..600L)
    }

    // ------------------------------------------------------------------ left alone

    @Test fun aTypedQuestionTakesNoFocusOfItsOwn() {
        busy(); busy()
        idle()
        port.pass(grace)
        speak(true)
        speak(false)
        assertEquals("only the voice's own request, as before", listOf("take duck", "give"), port.asked)
    }

    @Test fun anotherAppsCallIsLeftAlone() {
        port.mode = AudioManager.MODE_IN_COMMUNICATION
        assertFalse(k.listen())
        busy()
        speak(true)
        k.volume(VolumePolicy.PAUSE_OTHERS)
        speak(false)
        idle()
        port.pass(FocusKeeper.SAFETY_MS)
        assertEquals("nothing more than the voice asked before 1.1", listOf("take duck", "give"), port.asked)
        assertTrue(port.timers.isEmpty())
    }

    @Test fun noCallModeIsEverTakenOverEvenWhileRobotHoldsARoute() {
        // A phone call, its ringing or call screening: never, even while a headset session holds Robot's route.
        for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT, -1)) for (ours in listOf(false, true)) {
            assertFalse("$mode $ours", FocusPlan.mayTake(mode, ours))
            val p = FakePort().also { it.mode = mode; it.ours = ours }
            val keeper = FocusKeeper(p)
            assertFalse(keeper.listen())
            keeper.volume(VolumePolicy.PAUSE_OTHERS)
            keeper.speaking(sherpa, Playback.ASSISTANT, true)
            keeper.speaking(sherpa, Playback.ASSISTANT, false)
            keeper.end(now = true)
            assertEquals("$mode $ours: only the voice's ducking, as before 1.1", listOf("take duck", "give"), p.asked)
        }
        // Another app's call in communication mode, but not Robot's own.
        assertFalse(FocusPlan.mayTake(AudioManager.MODE_IN_COMMUNICATION, robotHolds = false))
        assertTrue(FocusPlan.mayTake(AudioManager.MODE_IN_COMMUNICATION, robotHolds = true))
        assertTrue(FocusPlan.mayTake(AudioManager.MODE_NORMAL, robotHolds = false))
        assertTrue(FocusPlan.mayTake(AudioManager.MODE_NORMAL, robotHolds = true))
    }

    @Test fun aHeadsetSessionsOwnCommunicationModeIsNoCall() {
        port.mode = AudioManager.MODE_IN_COMMUNICATION
        port.ours = true
        assertTrue(k.listen())
        assertEquals(listOf("take exclusive"), port.asked)
    }

    @Test fun aCallStartingInTheTurnIsNotTakenOver() {
        k.listen()
        port.mode = AudioManager.MODE_IN_COMMUNICATION
        speak(true)
        speak(false)
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "take duck", "give"), port.asked)
    }

    @Test fun musicYouStartInTheTurnIsNotPausedAgain() {
        k.listen()
        // "Play music": the player takes the focus for good.
        k.lostForGood()
        speak(true) // "OK."
        speak(false)
        k.listen() // the session's follow-up
        idle()
        port.pass(grace)
        assertEquals(listOf("take exclusive", "give"), port.asked)
        // The next question pauses again.
        k.listen()
        assertEquals(listOf("take exclusive", "give", "take exclusive"), port.asked)
    }

    @Test fun aLateLossAfterLettingGoChangesNothing() {
        k.listen()
        idle()
        port.pass(grace)
        k.lostForGood()
        speak(true)
        assertEquals(listOf("take exclusive", "give", "take duck"), port.asked)
    }

    @Test fun keepAndEndWithoutAQuestionDoNothing() {
        busy()
        idle()
        k.end(now = true)
        port.pass(FocusKeeper.SAFETY_MS)
        assertEquals(emptyList<String>(), port.asked)
        assertTrue(port.timers.isEmpty())
    }
}
