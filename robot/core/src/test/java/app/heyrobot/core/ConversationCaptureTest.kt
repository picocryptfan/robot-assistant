package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Conversation mode's capture pieces that need no microphone (docs/conversation-mode.md 1, 2.1-2.3, phase 5.2). */
class ConversationCaptureTest {
    @Test fun theSpeechFlagHasHysteresis() {
        val v = VadFlag()
        assertEquals(listOf(false, true, true, true, false, false, true),
            listOf(0.4f, 0.6f, 0.45f, 0.36f, 0.34f, 0.49f, 0.5f).map { v.update(it) })
        v.reset()
        assertFalse(v.speech)
    }

    @Test fun theRingSlicesByPosition() {
        val r = SampleRing(10)
        r.push(FloatArray(6) { it.toFloat() })
        assertEquals(listOf(2f, 3f, 4f), r.slice(2, 5).toList())
        r.push(FloatArray(8) { (6 + it).toFloat() })
        assertEquals(14L, r.end)
        assertEquals(4L, r.start)
        // What fell out is not there; the rest is.
        assertEquals(listOf(4f, 5f, 6f), r.slice(0, 7).toList())
        assertEquals(listOf(12f, 13f), r.slice(12, 20).toList())
        assertEquals(0, r.slice(9, 9).size)
    }

    @Test fun framesAreGatheredAcrossChunks() {
        val a = FrameAssembler(4)
        val out = ArrayList<List<Float>>()
        a.push(floatArrayOf(1f, 2f, 3f)) { out += it.toList() }
        a.push(floatArrayOf(4f, 5f, 6f, 7f, 8f, 9f)) { out += it.toList() }
        assertEquals(listOf(listOf(1f, 2f, 3f, 4f), listOf(5f, 6f, 7f, 8f)), out)
    }

    @Test fun wordStartsComeFromTheTokens() {
        // As Parakeet v3 gives them (docs/conversation-mode.md 2.2): a token starting with a space starts a word.
        val h = Heard(" Ask not what", listOf(" A", "sk", " not", " what"), listOf(0.0f, 0.24f, 0.40f, 0.72f))
        assertEquals(listOf(0, 40, 72), h.wordStarts())
        assertEquals(3, h.words)
        assertEquals(listOf(0, 12), Heard("Bon matin", listOf("▁Bon", "▁mat", "in"), listOf(0f, 0.12f, 0.2f)).wordStarts())
        // Lengths that don't match: none.
        assertEquals(emptyList<Int>(), Heard("x", listOf(" x"), emptyList()).wordStarts())
        val joined = Heard.join(Heard("Hello there", listOf(" Hello", " there"), listOf(0f, 0.4f)), Heard("Bonjour", listOf(" Bonjour"), listOf(0.1f)), 2.0)
        assertEquals("Hello there Bonjour", joined.text)
        assertEquals(listOf(0, 40, 210), joined.wordStarts())
    }

    private val id: LangId by lazy { LangId.load(File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET).inputStream()) }

    @Test fun shortLinesLeanOnThePreviousLine() {
        val l = LineLabeller(id, owner = "en")
        assertEquals(LineLang("fr", false), l.label("On se reprend-tu demain après-midi chez ma mère?").second)
        // A line with no words says nothing: it takes the previous line's language, marked "FR?".
        assertEquals(LineLang("fr", true), l.label("5 h 30").second)
        assertEquals(LineLang("en", false), l.label("I'm running late because the bus never showed up.").second)
        // A lone name likewise.
        assertEquals(LineLang("en", true), l.label("Gatineau").second)
        l.corrected("fr")
        assertEquals(LineLang("fr", true), l.label("42").second)
        // With nothing before, a line with no words takes your language.
        assertEquals(LineLang("en", true), LineLabeller(id, "en").label("42").second)
        assertEquals(LineLang("fr", true), LineLabeller(id, "fr").label("42").second)
    }

    @Test fun aLineTakesTheSpeechTimesNotThePadding() {
        // Speech from 1.0 s to 3.0 s, decoded from 0.8 s (0.2 s of padding), in a run that began 60 s into the session.
        val seg = Segment(12_800, 51_200, 16_000, 48_000, 2_000, SegmentEnd.SILENCE, 32_000)
        val heard = Heard(" Bonjour tout le monde", listOf(" Bon", "jour", " tout", " le", " monde"), listOf(0.08f, 0.24f, 0.56f, 0.8f, 0.96f))
        val g = id.detect(heard.text)
        val line = ConversationLine.make(7, 60.0, seg, heard, g, LineLang("fr", false), reheard = false)
        assertEquals(7, line.i)
        assertEquals(61.0, line.t0, 1e-9)
        assertEquals(63.0, line.t1, 1e-9)
        assertEquals("S1", line.speaker)
        assertEquals("fr", line.lang)
        assertEquals("Bonjour tout le monde", line.text)
        // 8 cs into the padding counts as 0; the rest from the speech's start.
        assertEquals(listOf(0, 36, 60, 76), line.words)
        assertFalse(line.prior)
        // It round-trips through the log as it is.
        assertEquals(line, TranscriptCodec.decode(TranscriptCodec.encode(line)))
    }

    @Test fun minuteNumbersAreCountsOnly() {
        val m = MinuteStats()
        assertNull(m.take())
        m.segment(300, 0); m.segment(900, 2); m.segment(500, 1)
        m.line(LineLang("fr", false)); m.line(LineLang("en", false)); m.line(LineLang("fr", true)); m.line(LineLang("fr", false))
        assertEquals(Triple(3, 500, 2) to Triple(2, 1, 1), m.take())
        assertNull(m.take())
    }

    @Test fun rules() {
        assertEquals(ConvPause.CALL, ConversationRules.silencedBy(true))
        assertEquals(ConvPause.MIC_TAKEN, ConversationRules.silencedBy(false))
        assertEquals(TranscriptEvent.GAP_CALL, ConversationRules.gap(ConvPause.CALL))
        assertEquals(TranscriptEvent.GAP_MIC, ConversationRules.gap(ConvPause.MIC_TAKEN))
        assertEquals(TranscriptEvent.GAP_PAUSE, ConversationRules.gap(ConvPause.USER))
        assertTrue(ConversationRules.diskFull(100L * 1024 * 1024))
        assertFalse(ConversationRules.diskFull(300L * 1024 * 1024))
        // Unknown free space (-1) never stops a session.
        assertFalse(ConversationRules.diskFull(-1))
        val s = RobotSettings()
        assertTrue(ConversationRules.partialNow(s, screenOn = true, warm = false, queued = 0, sinceLastMs = 1_500))
        assertFalse(ConversationRules.partialNow(s, screenOn = false, warm = false, queued = 0, sinceLastMs = 9_000))
        assertFalse(ConversationRules.partialNow(s, screenOn = true, warm = true, queued = 0, sinceLastMs = 9_000))
        assertFalse(ConversationRules.partialNow(s, screenOn = true, warm = false, queued = 1, sinceLastMs = 9_000))
        assertFalse(ConversationRules.partialNow(s, screenOn = true, warm = false, queued = 0, sinceLastMs = 1_000))
        assertFalse(ConversationRules.partialNow(s.copy(convPartials = false), screenOn = true, warm = false, queued = 0, sinceLastMs = 9_000))
    }

    @Test fun titlesAndDurations() {
        val meta = TranscriptMeta("20261011-1402-7f3a", "", java.time.LocalDateTime.of(2026, 10, 11, 14, 2).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli())
        assertEquals("Conversation · 11 Oct, 14:02", TranscriptTitles.shown(meta, ZoneId.of("UTC")))
        assertEquals("Lunch with Marie", TranscriptTitles.shown(meta.copy(title = " Lunch with Marie "), ZoneId.of("UTC")))
        assertEquals("a b", TranscriptTitles.clean("  a \n b "))
        assertEquals(80, TranscriptTitles.clean("x".repeat(200)).length)
        assertEquals("under a minute", TranscriptTitles.duration(59_000))
        assertEquals("12 min", TranscriptTitles.duration(12 * 60_000L))
        assertEquals("1 h 05", TranscriptTitles.duration(65 * 60_000L))
    }

    @Test fun theOpenSegmentsStartIsKnown() {
        val s = Segmenter()
        assertNull(s.openStart)
        repeat(10) { s.feed(false, 0f, false) }
        s.feed(true, 1f, false)
        assertEquals(10 * 512L, s.openStart)
    }
}
