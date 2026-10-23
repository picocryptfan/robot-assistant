// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.ListenPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Utterance segmentation and partial-decode scheduling, in 32 ms frames as the listener delivers them. */
class UtteranceTest {
    private val frame = 512
    private fun ms(samples: Long) = samples * 1000 / Endpointer.RATE

    /** Runs frames with the VAD flag from [speech] (a time in ms -> flag); returns the events with their time. */
    private fun run(e: Endpointer, totalMs: Int, speech: (Long) -> Boolean): List<Pair<Endpointer.Event, Long>> {
        val out = mutableListOf<Pair<Endpointer.Event, Long>>()
        var t = 0L
        // Fixed 32 ms steps: once the endpointer has ended it stops counting, so its clock can't drive the loop.
        while (t < totalMs) {
            t += ms(frame.toLong())
            val ev = e.onFrame(speech(t), frame)
            if (ev != Endpointer.Event.NONE) out += ev to t
        }
        return out
    }

    /**
     * The Silero VAD's speech flag as sherpa-onnx keeps it, for speech that is really there at [spoken] (ms -> true):
     * up after [Endpointer.VAD_MIN_SPEECH_S] of speech, down after [Endpointer.VAD_MIN_SILENCE_S] of silence, speech
     * coming back inside that silence keeping it up.
     */
    private class VadFlag(private val spoken: (Long) -> Boolean) {
        private val minSpeech = (Endpointer.VAD_MIN_SPEECH_S * Endpointer.RATE).toLong()
        private val minSilence = (Endpointer.VAD_MIN_SILENCE_S * Endpointer.RATE).toLong()
        private var now = 0L
        private var startedAt = -1L
        private var quietSince = -1L
        private var up = false

        /** The flag after the 32 ms frame ending at [t] ms (frames in order, one call each). */
        fun at(t: Long): Boolean {
            now += 512
            if (spoken(t)) {
                quietSince = -1
                if (up) return true
                if (startedAt < 0) startedAt = now
                if (now - startedAt < minSpeech) return false
                up = true
                return true
            }
            startedAt = -1
            if (!up) return false
            if (quietSince < 0) quietSince = now
            if (now - quietSince < minSilence) return true
            quietSince = -1
            up = false
            return false
        }
    }

    private fun pause(ms: Int) = Endpointer(Endpointer.Config(silenceSamples = Endpointer.windowFor(ms)))

    /** Speech from 0.5 s to 2 s, a pause of [gapMs], speech again for 1.2 s, then silence up to 10 s. */
    private fun withPause(e: Endpointer, gapMs: Int) = run(e, 10_000, VadFlag { t -> t in 500..2_000 || t in (2_000 + gapMs)..(3_200 + gapMs) }::at)

    @Test fun aQuestionEndsAfterTheChosenPause() {
        // Speech from 0.6 s to 3.0 s: the flag drops about 0.25 s later, and the question ends 1 s (Normal) after that.
        val e = Endpointer()
        val ev = run(e, 6_000, VadFlag { t -> t in 600..3_000 }::at)
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.END), ev.map { it.first })
        assertTrue(ev[1].second.toString(), ev[1].second in 4_200..4_350)
        assertTrue(e.heardSpeech)
        // Once ended, nothing more.
        assertEquals(Endpointer.Event.NONE, e.onFrame(true, frame))
    }

    @Test fun aPauseOfPointEightSecondsDoesNotEndTheQuestionAtNormal() {
        val ev = withPause(Endpointer(), 800)
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.END), ev.map { it.first })
        // It ends after the second part, not in the pause.
        assertTrue(ev[1].second.toString(), ev[1].second > 4_000)
    }

    @Test fun theSamePauseEndsTheQuestionAtShort() {
        val ev = withPause(pause(ListenPause.SHORT.ms), 800)
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.END), ev.map { it.first })
        assertTrue(ev[1].second.toString(), ev[1].second in 2_000..2_900)
    }

    @Test fun everyChoiceWaitsThroughAShorterPauseAndEndsOnALongerOne() {
        for (p in ListenPause.entries) {
            val through = withPause(pause(p.ms), p.ms - 100)
            assertTrue("$p: ${through.last()}", through.last().second > 3_100 + p.ms)
            val cut = withPause(pause(p.ms), p.ms + 300)
            assertTrue("$p: ${cut.last()}", cut.last().first == Endpointer.Event.END && cut.last().second < 2_000 + p.ms + 300)
        }
    }

    @Test fun speechComingBackResetsTheCount() {
        // Four pauses of 0.7 s: 2.8 s of silence in all, none of them long enough at Normal.
        val talk = listOf(500L..1_500L, 2_200L..3_000L, 3_700L..4_500L, 5_200L..6_000L, 6_700L..7_500L)
        val ev = run(Endpointer(), 12_000, VadFlag { t -> talk.any { t in it } }::at)
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.END), ev.map { it.first })
        assertTrue(ev[1].second.toString(), ev[1].second in 8_700..8_850)
    }

    @Test fun aHeldButtonIgnoresAThreeSecondPauseAndEndsOnRelease() {
        val e = Endpointer()
        e.held = true
        val ev = run(e, 10_000, VadFlag { t -> t in 500..2_000 || t in 5_000..6_000 }::at)
        assertEquals(listOf(Endpointer.Event.SPEECH), ev.map { it.first })
        // Letting go: SpeechToText.stop() ends it (decode what there is); the endpointer, no longer held, ends it too.
        e.held = false
        assertEquals(Endpointer.Event.END, e.onFrame(false, frame))
        assertEquals(Endpointer.Event.NONE, e.cut())
    }

    @Test fun aHeldButtonWaitsPastTheNoSpeechTimeoutButNotPastTheCap() {
        val e = Endpointer()
        e.held = true
        assertEquals(emptyList<Pair<Endpointer.Event, Long>>(), run(e, 10_000) { false })
        val ev = run(e, 30_000) { false }
        assertEquals(listOf(Endpointer.Event.MAX_LENGTH), ev.map { it.first })
    }

    @Test fun aCardsYesEndsAfterAboutPointSixSeconds() {
        val e = Endpointer(Endpointer.Config(silenceSamples = Endpointer.CARD_WINDOW_SAMPLES))
        val ev = run(e, 3_000, VadFlag { t -> t in 300..700 }::at)
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.END), ev.map { it.first })
        assertTrue(ev[1].second.toString(), ev[1].second in 1_250..1_400)
    }

    @Test fun silenceGivesUpAfterSixSeconds() {
        val e = Endpointer()
        val ev = run(e, 10_000) { false }
        assertEquals(listOf(Endpointer.Event.NO_SPEECH), ev.map { it.first })
        assertTrue(ev[0].second in 6_000..6_040)
        assertFalse(e.heardSpeech)
    }

    @Test fun aLongQuestionIsCappedAtThirtySeconds() {
        val e = Endpointer()
        val ev = run(e, 40_000) { t -> t > 1_000 }
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.MAX_LENGTH), ev.map { it.first })
        assertTrue(ev[1].second in 30_000..30_040)
    }

    @Test fun lateSpeechWithinTheTimeoutStillCounts() {
        val e = Endpointer(Endpointer.Config(noSpeechSamples = 2L * Endpointer.RATE))
        val ev = run(e, 5_000) { t -> t in 1_900..3_000 }
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.END), ev.map { it.first })
    }

    /** Partial starts (in ms) for speech from [speechMs], each decode taking [decodeMs], frames up to [untilMs]. */
    private fun partials(speechMs: Long, untilMs: Long, decodeMs: Long, queuedUntilMs: Long = 0): List<Pair<Long, PartialScheduler.Decision>> {
        val p = PartialScheduler()
        val out = mutableListOf<Pair<Long, PartialScheduler.Decision>>()
        var runningUntil = -1L
        var t = 0L
        while (t < untilMs) {
            t += 32
            val start = if (t >= speechMs) speechMs * Endpointer.RATE / 1000 else -1L
            val d = p.decide(t * Endpointer.RATE / 1000, start, queued = t < queuedUntilMs && out.isNotEmpty(), running = t < runningUntil)
            if (d != PartialScheduler.Decision.NONE) {
                out += t to d
                runningUntil = t + decodeMs
            }
        }
        return out
    }

    @Test fun partialsStartAfterOneSecondOfSpeechThenEveryPointSevenSeconds() {
        val starts = partials(speechMs = 500, untilMs = 4_000, decodeMs = 300)
        assertTrue(starts.all { it.second == PartialScheduler.Decision.START })
        val times = starts.map { it.first }
        assertTrue(times.toString(), times.first() in 1_500..1_532)
        times.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", b - a in 700..732) }
        assertEquals(4, times.size)
    }

    @Test fun aSlowDecodeIsNeverDoubledUp() {
        // Decodes of 1.2 s: the next starts only once the previous one is done, not every 0.7 s.
        val times = partials(speechMs = 0, untilMs = 6_000, decodeMs = 1_200).map { it.first }
        times.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", b - a >= 1_200 && b - a <= 1_240) }
    }

    @Test fun aPartialStillWaitingIsReplaced() {
        // While the model loads, partials queue up behind it: each new one replaces the waiting one.
        val p = PartialScheduler()
        val s = 0L
        assertEquals(PartialScheduler.Decision.START, p.decide(Endpointer.RATE.toLong(), s, queued = false, running = false))
        assertEquals(PartialScheduler.Decision.NONE, p.decide(Endpointer.RATE * 3L / 2, s, queued = true, running = false))
        assertEquals(PartialScheduler.Decision.REPLACE, p.decide(Endpointer.RATE * 2L, s, queued = true, running = false))
        // No speech yet: never.
        assertEquals(PartialScheduler.Decision.NONE, PartialScheduler().decide(Endpointer.RATE * 5L, -1, queued = false, running = false))
    }

    /** 0.1.2 review, minor 6: a question whose microphone stops still ends. */
    @Test fun aQuestionCutShortEndsWithWhatWasHeard() {
        val e = Endpointer()
        run(e, 2_000) { t -> t >= 600 }
        // The capture stops mid-sentence: decode what there is.
        assertEquals(Endpointer.Event.END, e.cut())
        assertEquals(Endpointer.Event.NONE, e.cut())
        assertEquals(Endpointer.Event.NONE, e.onFrame(true, frame))
        // Nothing said before it stopped.
        val quiet = Endpointer()
        run(quiet, 1_000) { false }
        assertEquals(Endpointer.Event.NO_SPEECH, quiet.cut())
        // A question that already ended is not ended twice.
        val done = Endpointer()
        run(done, 6_000) { t -> t in 600..3_500 }
        assertEquals(Endpointer.Event.NONE, done.cut())
    }

    @Test fun theWatchdogOnlyCatchesAStalledCapture() {
        val cfg = Endpointer.Config()
        // A microphone that delivers frames always ends by samples first (30 s cap), well before the wall-clock cap.
        assertTrue(cfg.watchdogMs > cfg.maxSamples * 1000 / Endpointer.RATE)
        assertTrue(cfg.watchdogMs <= 40_000)
        assertEquals(cfg.watchdogMs, Endpointer().watchdogMs)
    }

    /** 0.1.2 review, minor 9: "stop" is not a wake word, and the ring holds what a question after "hey robot" needs. */
    @Test fun onlyHeyRobotMarksTheStartOfAQuestion() {
        assertFalse(PreRoll.marksQuestion(WakeWord.STOP))
        assertTrue(PreRoll.marksQuestion("HEY_ROBOT"))
    }

    @Test fun preRollReachesBackToTheWakeWordAndNoFurtherThanTheRing() {
        val rate = Endpointer.RATE.toLong()
        assertTrue(PreRoll.SINCE_WAKE_MS <= PreRoll.RING_MS)
        // "Hey robot" 1.2 s ago: the question starts there.
        assertEquals((1.2 * rate).toInt(), PreRoll.samples((1.2 * rate).toLong()))
        // 2.5 s ago (the limit): all of it, which the ring still holds.
        assertEquals((2.5 * rate).toInt(), PreRoll.samples(PreRoll.SINCE_WAKE_MS * rate / 1000))
        // Longer ago, or never (a huge or negative distance): a button press gets 0.3 s.
        assertEquals((0.3 * rate).toInt(), PreRoll.samples(PreRoll.SINCE_WAKE_MS * rate / 1000 + 1))
        assertEquals((0.3 * rate).toInt(), PreRoll.samples(Long.MAX_VALUE / 2))
        assertEquals((0.3 * rate).toInt(), PreRoll.samples(-5))
        // Never more than the ring.
        assertEquals((PreRoll.RING_MS * rate / 1000).toInt(), PreRoll.samples(10 * rate, sinceWakeMs = 20_000))
    }
}
