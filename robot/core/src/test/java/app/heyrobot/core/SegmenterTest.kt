// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversation mode's segments (docs/conversation-mode.md 2.1, 1.3), frame by frame (512 samples, 32 ms). */
class SegmenterTest {
    private val fs = 512L
    private val pad = 3_200L

    /** Feeds [n] frames; returns the segments they closed. */
    private fun Segmenter.run(n: Int, speech: Boolean, level: Float = if (speech) 1f else 0.01f, robot: Boolean = false): List<Segment> =
        (0 until n).mapNotNull { feed(speech, level, robot) }

    @Test fun aQuarterSecondOfSilenceSplits() {
        val s = Segmenter()
        assertEquals(emptyList<Segment>(), s.run(50, false))
        assertEquals(emptyList<Segment>(), s.run(31, true))
        // 7 frames (224 ms) of silence: still one segment ...
        assertEquals(emptyList<Segment>(), s.run(7, false))
        assertEquals(emptyList<Segment>(), s.run(31, true))
        // ... 8 frames (256 ms) close it.
        val first = s.run(8, false).single()
        assertEquals(SegmentEnd.SILENCE, first.ended)
        assertEquals(50 * fs, first.speechStart)
        assertEquals((50 + 31 + 7 + 31) * fs, first.speechEnd)
        assertEquals(62 * 32, first.speechMs)
        // The next one opens on the next speech and closes the same way.
        s.run(31, true)
        assertEquals(1, s.run(8, false).size)
    }

    @Test fun segmentsGetPaddingButNeverOverlap() {
        val s = Segmenter()
        s.run(50, false)
        s.run(40, true)
        val a = s.run(8, false).single()
        assertEquals(50 * fs - pad, a.start)
        assertEquals(a.speechEnd + pad, a.end)
        s.run(40, true)
        val b = s.run(8, false).single()
        // 8 frames apart: the second's padding stops where the first ended.
        assertEquals(a.end, b.start)
        assertTrue(b.speechStart - pad < a.end)
        assertEquals(b.speechEnd + pad, b.end)
        // At the very start of a session there is nothing before to pad with.
        val c = Segmenter()
        c.run(20, true)
        assertEquals(0L, c.run(8, false).single().start)
    }

    @Test fun aBlipIsNotSpeech() {
        val s = Segmenter()
        s.run(10, false)
        s.run(5, true)
        assertEquals(emptyList<Segment>(), s.run(20, false))
        assertTrue(!s.open)
    }

    @Test fun fifteenSecondsAreCutAtTheQuietestFrameOfTheLastTwo() {
        val s = Segmenter()
        s.run(10, false)
        val levels = FloatArray(700) { 1f }
        levels[300] = 0.05f   // quieter, but 9.6 s in: outside the last 2 s
        levels[440] = 0.1f    // 14.1 s in: the cut
        levels[460] = 0.3f
        val out = ArrayList<Segment>()
        for (k in 0 until 700) s.feed(true, levels[k], false)?.let { out += it }
        val cut = out.single()
        assertEquals(SegmentEnd.CAP, cut.ended)
        assertEquals((10 + 440 + 1) * fs, cut.end)
        assertTrue(cut.end - cut.start <= 15_000 * 16 + pad)
    }

    @Test fun theRestAfterACutStartsAtTheCut() {
        val s = Segmenter()
        val out = ArrayList<Segment>()
        for (k in 0 until 600) s.feed(true, if (k == 450) 0.1f else 1f, false)?.let { out += it }
        for (k in 0 until 8) s.feed(false, 0.01f, false)?.let { out += it }
        assertEquals(2, out.size)
        assertEquals(SegmentEnd.CAP, out[0].ended)
        assertEquals(451 * fs, out[0].end)
        assertEquals(out[0].end, out[1].start)
        assertEquals(SegmentEnd.SILENCE, out[1].ended)
        assertEquals(600 * fs + pad, out[1].end)
    }

    @Test fun aPauseTheDetectorHeardIsPreferredForTheCut() {
        val s = Segmenter()
        val out = ArrayList<Segment>()
        // Speech all along, but the VAD heard a short pause at frame 430 (louder than the quietest speech frame).
        for (k in 0 until 470) s.feed(k != 430, if (k == 430) 0.5f else if (k == 455) 0.2f else 1f, false)?.let { out += it }
        assertEquals(431 * fs, out.single().end)
    }

    @Test fun robotsVoiceAndThreeHundredMillisecondsAfterAreSilence() {
        val s = Segmenter()
        // Speech while Robot talks is never a segment.
        assertEquals(emptyList<Segment>(), s.run(60, true, robot = true))
        // Nor in the 300 ms after (10 frames) ...
        assertEquals(emptyList<Segment>(), s.run(10, true))
        assertTrue(!s.open)
        // ... but right after that it is, padded no further back than the end of the gated stretch.
        s.run(30, true)
        val seg = s.run(8, false).single()
        assertEquals(70 * fs, seg.speechStart)
        assertEquals(70 * fs, seg.start)
    }

    @Test fun anOpenSegmentClosesCleanlyWhenRobotStartsTalking() {
        val s = Segmenter()
        s.run(20, false)
        s.run(40, true)
        // Robot speaks (and the person goes on talking over her): her voice counts as silence.
        val seg = s.run(20, true, robot = true).single()
        assertEquals(SegmentEnd.GATE, seg.ended)
        assertEquals(60 * fs, seg.speechEnd)
        // No padding into her voice: the gated stretch began right at the end of the speech.
        assertEquals(60 * fs, seg.end)
    }

    @Test fun flushClosesTheOpenSegment() {
        val s = Segmenter()
        s.run(5, false)
        s.run(40, true)
        val seg = s.flush()
        assertNotNull(seg)
        assertEquals(SegmentEnd.FLUSH, seg!!.ended)
        assertEquals(45 * fs, seg.end)
        assertNull(s.flush())
    }

    @Test fun theSplitPointIsTheQuietestFrameAwayFromTheEdges() {
        val s = Segmenter()
        val out = ArrayList<Segment>()
        // 5 s of speech: quiet at frame 5 (too near the start) and frame 90 (the split).
        for (k in 0 until 156) s.feed(true, when (k) { 5 -> 0.01f; 90 -> 0.1f; else -> 1f }, false)?.let { out += it }
        for (k in 0 until 8) s.feed(false, 0.01f, false)?.let { out += it }
        assertEquals(90 * fs, out.single().split)
        // A second of speech has no room for a split.
        val t = Segmenter()
        t.run(31, true)
        assertNull(t.run(8, false).single().split)
    }

    @Test fun linesOfOneSpeakerAndLanguageReadAsOneParagraph() {
        fun line(i: Int, t0: Double, t1: Double, spk: String = "S1", lang: String? = "fr") = TranscriptLine(i, t0, t1, spk, lang, "l$i")
        val lines = listOf(line(1, 0.0, 2.0), line(2, 3.0, 5.0), line(3, 7.0, 8.0), line(4, 8.5, 9.0, lang = "en"), line(5, 9.2, 10.0, spk = "S2", lang = "en"),
            line(6, 10.1, 11.0, spk = "?", lang = "en"), line(7, 11.1, 12.0, spk = "?", lang = "en"))
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(7)), DisplayMerge.paragraphs(lines).map { p -> p.map { it.i } })
    }
}
