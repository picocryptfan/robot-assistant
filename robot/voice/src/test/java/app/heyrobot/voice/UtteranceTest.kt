package app.heyrobot.voice

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

    @Test fun aQuestionEndsWhenTheVadFlagDrops() {
        // The VAD reports speech from 0.6 s and drops its flag 0.5 s after the speech ended at 3.0 s.
        val e = Endpointer()
        val ev = run(e, 6_000) { t -> t in 600..3_500 }
        assertEquals(listOf(Endpointer.Event.SPEECH, Endpointer.Event.END), ev.map { it.first })
        assertTrue(ev[1].second in 3_500..3_540)
        assertTrue(e.heardSpeech)
        // Once ended, nothing more.
        assertEquals(Endpointer.Event.NONE, e.onFrame(true, frame))
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
}
