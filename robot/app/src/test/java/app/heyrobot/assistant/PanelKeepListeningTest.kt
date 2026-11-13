// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.TtsStop
import app.heyrobot.core.headset.PanelFollowUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel's follow-up window (idea 7): when it opens, how it ends, and a card's own listen left as it was. */
class PanelKeepListeningTest {
    private class Fake : PanelKeepListening.Host {
        var t = 0L
        override var idle = true
        override var speaking = false
        override var hearing = false
        override var listening = false
        override var inCall = false
        var beeps = 0
        var listens = 0
        var stops = 0
        private val queue = mutableListOf<Pair<Long, () -> Unit>>()
        override fun now() = t
        override fun post(delayMs: Long, run: () -> Unit) { queue += (t + delayMs) to run }
        override fun beep() { beeps++ }
        override fun listen() { listens++; listening = true }
        override fun stopQuietly() { stops++; listening = false }
        /** Runs what's due up to [ms] from now, in order. */
        fun advance(ms: Long) {
            val end = t + ms
            while (true) {
                val next = queue.filter { it.first <= end }.minByOrNull { it.first } ?: break
                queue.remove(next); t = next.first; next.second()
            }
            t = end
        }
    }

    private val on = PanelFollowUp.After(on = true, spoken = true, stopped = false, shown = true, handsFree = false, waitsForWords = false)

    @Test fun opensWithABeepOnceRobotStopsTalking() {
        val h = Fake(); val k = PanelKeepListening(h)
        h.speaking = true
        k.answered(on, speaking = true)
        h.advance(2_000)
        assertEquals("not while the answer is said", 0, h.listens)
        h.speaking = false; k.speakingStopped(); h.advance(PanelKeepListening.SETTLE_MS)
        assertEquals(1, h.beeps); assertEquals(1, h.listens); assertTrue(k.open)
    }

    @Test fun aSentenceBetweenTwoDoesNotOpenIt() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = true)
        k.speakingStopped(); h.speaking = true; h.advance(PanelKeepListening.SETTLE_MS)
        assertEquals(0, h.listens)
        h.speaking = false; k.speakingStopped(); h.advance(PanelKeepListening.SETTLE_MS)
        assertEquals(1, h.listens)
    }

    @Test fun silenceEndsItQuietlyAfterFiveSeconds() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        h.advance(PanelFollowUp.WINDOW_MS - 1)
        assertEquals(0, h.stops)
        h.advance(1)
        assertEquals(1, h.stops); assertFalse(k.open)
        assertEquals("one beep, no chime for nothing said", 1, h.beeps)
    }

    @Test fun someoneTalkingAtTheEndKeepsItOpen() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        h.hearing = true; h.advance(PanelFollowUp.WINDOW_MS + 1_000)
        assertEquals(0, h.stops)
        h.hearing = false; h.advance(PanelFollowUp.RECHECK_MS)
        assertEquals(1, h.stops)
    }

    @Test fun thatsAllEndsItAndTheNextQuestionGoesOn() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        assertTrue("nothing sent", k.heard("Thanks, that's all"))
        assertFalse(k.open)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        assertFalse("sent as the next question", k.heard("And Sunday?"))
        assertFalse(k.open)
        h.listening = false; h.advance(PanelFollowUp.WINDOW_MS * 2)
        assertEquals("its timer is gone with it", 0, h.stops)
    }

    @Test fun noSpeechEndsItQuietlyOtherErrorsShow() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        assertTrue(k.missed(noSpeech = true))
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        assertFalse(k.missed(noSpeech = false))
    }

    @Test fun offTypedOrACardsListenOpenNothing() {
        listOf(on.copy(on = false), on.copy(spoken = false), on.copy(waitsForWords = true), on.copy(handsFree = true)).forEach {
            val h = Fake(); val k = PanelKeepListening(h)
            k.answered(it, speaking = false); h.advance(10_000)
            assertEquals(it.toString(), 0, h.listens + h.beeps)
            assertFalse(k.heard(""))
        }
    }

    @Test fun somethingElseUnderWayTakesItsPlace() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = true)
        h.idle = false; h.speaking = false; k.speakingStopped(); h.advance(PanelKeepListening.SETTLE_MS)
        h.idle = true; k.speakingStopped(); h.advance(PanelKeepListening.SETTLE_MS)
        assertEquals("no stale window later", 0, h.listens)
    }

    @Test fun hidingOrANewTurnCancelsIt() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); k.cancel(); h.advance(1_000)
        assertEquals(0, h.listens)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS); k.cancel(); h.advance(PanelFollowUp.WINDOW_MS)
        assertEquals(0, h.stops); assertFalse(k.open)
    }

    @Test fun anAnswerStoppedWhileSpeakingOpensNothing() {
        for (why in listOf(TtsStop.TAP, TtsStop.STOP_WORD, TtsStop.VOLUME_KEY, TtsStop.BUTTON, TtsStop.CLOSED, TtsStop.OTHER)) {
            val h = Fake(); val k = PanelKeepListening(h)
            h.speaking = true; k.answered(on, speaking = true)
            k.voiceStopped(PanelKeepListening.byYou(why)); h.speaking = false; k.speakingStopped(); h.advance(10_000)
            assertEquals(why.toString(), 0, h.beeps + h.listens)
        }
    }

    @Test fun aStopWhileTheWindowIsOpenEndsTheMic() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        k.voiceStopped(PanelKeepListening.byYou(TtsStop.NEW_QUESTION))
        assertTrue("Robot's own stop leaves it", k.open)
        k.voiceStopped(PanelKeepListening.byYou(TtsStop.TAP))
        assertFalse(k.open); assertEquals(1, h.stops)
    }

    @Test fun aCallOrARingOpensNothingTheOrdinaryCaseStillOpens() {
        val h = Fake(); val k = PanelKeepListening(h)
        h.inCall = true; k.answered(on, speaking = false); h.advance(10_000)
        assertEquals(0, h.beeps + h.listens)
        h.inCall = false; k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        assertEquals(1, h.beeps); assertTrue(k.open)
    }

    @Test fun speechInTheWindowIsCutEightSecondsAfterItStarts() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        h.advance(1_000); h.hearing = true
        h.advance(PanelFollowUp.SPEECH_MS - PanelFollowUp.RECHECK_MS * 2)
        assertEquals(0, h.stops)
        h.advance(PanelFollowUp.RECHECK_MS * 4)
        assertEquals(1, h.stops); assertFalse(k.open)
    }

    @Test fun itReopensAtMostThreeTimesInARowThenEndsWithNoSound() {
        val h = Fake(); val k = PanelKeepListening(h)
        repeat(5) {
            k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
            if (k.open) assertFalse(k.heard("And Sunday?"))
        }
        assertEquals("the first and three more", 1 + PanelFollowUp.MAX_REOPENS, h.beeps)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        assertEquals("a question you start yourself begins again", 2 + PanelFollowUp.MAX_REOPENS, h.beeps)
    }

    @Test fun aListenThatNeverStartsClearsTheMark() {
        val h = Fake(); val k = PanelKeepListening(h)
        k.answered(on, speaking = false); h.advance(PanelKeepListening.SETTLE_MS)
        h.listening = false; h.advance(PanelFollowUp.WINDOW_MS)
        assertFalse(k.open)
        assertFalse("your own listen's \"thanks\" isn't swallowed", k.heard("thanks"))
    }

    /** L1 (review-wikipedia-l4): the copy's answer cut short by Robot (a timeout) isn't yours. */
    @Test fun aCopyStopIsNotYours() { org.junit.Assert.assertFalse(PanelKeepListening.byYou(TtsStop.COPY)) }
}
