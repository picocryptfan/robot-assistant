package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Speaker labels in a session and in the transcript (docs/conversation-mode.md 3.2, 3.3, 5.3; phase 5.3). */
class SpeakersTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A unit vector in 8 dimensions at [deg] degrees from axis 0 towards axis [axis]. */
    private fun v(axis: Int, deg: Double = 90.0): FloatArray = FloatArray(8).also {
        val r = Math.toRadians(deg)
        it[0] = cos(r).toFloat()
        it[axis] += sin(r).toFloat()
    }
    private val a = v(1, 0.0)
    private val b = v(2)
    private val c = v(3)
    private val fr = LineLang("fr", false)
    private val en = LineLang("en", false)

    private fun ConversationSpeakers.say(e: FloatArray?, at: Long, ms: Int = 2_000) = assign(e, ms, at, at + ms).label

    @Test fun labelsAreOnlyTheOnesRobotGives() {
        for (ok in listOf("S1", "S12", "S999", "S1′", "S2′′", "Me", "?")) assertTrue(ok, SpeakerLabels.valid(ok))
        for (bad in listOf("", "S0", "S1000", "s1", "S1'", "Marie", "S1\n", " S1", "Me2", "??")) assertFalse(bad, SpeakerLabels.valid(bad))
        assertEquals(0, SpeakerLabels.colour("Me"))
        assertEquals(2, SpeakerLabels.colour("S2"))
        assertEquals(4, SpeakerLabels.colour("S1′"))
        assertEquals(-1, SpeakerLabels.colour("?"))
        assertEquals("S4", SpeakerLabels.next(listOf("Me", "S1", "S3′", "?")))
        assertEquals("S1", SpeakerLabels.next(emptyList()))
        assertEquals("′", SpeakerLabels.resumeMark(listOf("S1", "S2")))
        assertEquals("′′", SpeakerLabels.resumeMark(listOf("S1", "S1′", "Me")))
        assertEquals("Marie Tremblay", SpeakerLabels.cleanName("  Marie \n Tremblay "))
        assertEquals(SpeakerLabels.MAX_NAME, SpeakerLabels.cleanName("x".repeat(100)).length)
    }

    @Test fun offEveryLineIsOneSpeakerAsBefore() {
        val s = ConversationSpeakers(enabled = false, me = c)
        assertEquals("S1", s.say(a, 0))
        assertEquals("S1", s.say(b, 3_000))
        assertEquals("S1", s.say(null, 6_000, 300))
        assertFalse(s.seeded)
        assertEquals(emptyList<Pair<String, String>>(), s.proposals())
    }

    @Test fun twoPeopleTwoLabelsAndShortLinesInherit() {
        val s = ConversationSpeakers(enabled = true)
        assertEquals("S1", s.say(a, 0))
        assertEquals("S2", s.say(b, 3_000))
        // "Oui" right after S2: too short to compare, S2's.
        assertEquals("S2", s.say(null, 5_200, 400))
        assertEquals("S1", s.say(v(1, 15.0), 8_000))
        assertEquals(2, s.count)
    }

    @Test fun theInterpreterCapsAtTwoAndMeIsSeeded() {
        val s = ConversationSpeakers(enabled = true, maxSpeakers = ConversationSettings.INTERPRETER_SPEAKERS, me = c)
        assertTrue(s.seeded)
        assertEquals("Me", s.say(v(3, 80.0).also { it[3] = 1f }, 0))
        assertEquals("S1", s.say(a, 3_000))
        // A third voice: the nearest of the two.
        assertEquals(2, s.count)
        assertTrue(s.say(b, 6_000) in setOf("Me", "S1"))
        // Your voiceprint deleted mid-session: "Me" is no longer told apart.
        assertTrue(s.forgetMe())
        assertFalse(s.seeded)
        assertEquals(1, s.count)
    }

    @Test fun aShortLineLeansOnItsSpeakersUsualLanguage() {
        val s = ConversationSpeakers(enabled = true)
        s.heard(0, "S1", fr, a)
        s.heard(1, "S1", fr, a)
        s.heard(2, "S2", en, b)
        assertEquals("fr", s.usualLang("S1"))
        assertEquals("en", s.usualLang("S2"))
        assertNull(s.usualLang("?"))
        // Labels from a prior don't make a majority; your fix does.
        s.heard(3, "S3", LineLang("en", true), c)
        assertNull(s.usualLang("S3"))
        s.corrected("S3", "fr")
        assertEquals("fr", s.usualLang("S3"))
        // The labeller takes it for a line whose own words can't tell ("5 h 30"), before the previous line's language.
        val lab = LineLabeller(LangId.load(File(System.getProperty("user.dir") ?: ".", "src/main/assets/" + LangId.ASSET).inputStream()), "en")
        assertEquals("en", lab.label("Thanks a lot, see you tomorrow then.").second.lang)
        val (_, l) = lab.label("5 h 30", speakerLang = "fr")
        assertEquals("fr", l.lang)
        assertTrue(l.fromPrior)
        assertEquals("en", lab.label("5 h 30").second.lang)
    }

    @Test fun mergesApplyToLaterLinesAndProposalsCanBeTurnedDown() {
        val s = ConversationSpeakers(enabled = true)
        s.say(a, 0)
        s.say(v(5, 74.0), 3_000) // cosine 0.28 with A: a new speaker
        assertEquals(emptyList<Pair<String, String>>(), s.proposals())
        s.merge("S2", "S1")
        assertEquals("S1", s.say(v(5, 74.0), 6_000))
        // A speaker from before a resume (not in memory): the label maps.
        val r = ConversationSpeakers(enabled = true, mark = "′")
        assertEquals("S1′", r.say(a, 0))
        r.heard(0, "S1′", fr, a)
        r.merge("S1′", "S1")
        assertEquals("S1", r.say(v(1, 10.0), 3_000))
        assertEquals("fr", r.usualLang("S1"))
        assertEquals("fr", r.usualLang("S1′"))
        // A second voice that drifts towards the first (each line nearer the second): proposed, then turned down.
        val q = ConversationSpeakers(enabled = true)
        assertEquals(listOf("S1", "S2", "S2", "S2"), listOf(q.say(a, 0), q.say(v(5, 74.0), 3_000), q.say(v(5, 40.0), 6_000), q.say(v(5, 30.0), 9_000)))
        assertEquals(listOf("S2" to "S1"), q.proposals())
        // Proposals change nothing; "not the same person" isn't asked again.
        assertEquals("S2", q.say(v(5, 35.0), 12_000))
        q.decline("S2", "S1")
        assertEquals(emptyList<Pair<String, String>>(), q.proposals())
    }

    @Test fun aMovedLineTeachesItsNewSpeaker() {
        val s = ConversationSpeakers(enabled = true)
        s.say(a, 0)
        s.say(v(5, 74.0), 3_000)
        // Without teaching, a voice between the two at 45° from S2 goes to S1; teach S2 two lines like it, and it doesn't.
        val probe = v(5, 30.0)
        val other = ConversationSpeakers(enabled = true)
        other.say(a, 0)
        other.say(v(5, 74.0), 3_000)
        assertEquals("S1", other.say(probe, 6_000))
        s.heard(7, "S1", fr, v(5, 45.0))
        s.heard(8, "S1", fr, v(5, 40.0))
        s.moved(7, "S2")
        s.moved(8, "S2")
        assertEquals("S2", s.say(probe, 6_000))
        s.clear()
        assertEquals(0, s.count)
    }

    @Test fun theSpeechWithoutItsPaddingIsEmbedded() {
        val seg = Segment(start = 16_000, end = 16_000 + 3 * 16_000, speechStart = 16_000 + 3_200, speechEnd = 16_000 + 3 * 16_000 - 3_200, speechMs = 2_600,
            ended = SegmentEnd.SILENCE, split = null)
        val audio = FloatArray(3 * 16_000) { it.toFloat() }
        val sp = SpeakerAudio.speech(seg, audio)!!
        assertEquals(3 * 16_000 - 6_400, sp.size)
        assertEquals(3_200f, sp[0])
        assertNull(SpeakerAudio.speech(seg.copy(speechMs = 900), audio))
        assertEquals(2_200L to 4_800L, SpeakerAudio.clock(1.0, seg))
    }

    // ------------------------------------------------------------------ your changes, as the log stores them

    private val t: Transcript = TranscriptFold().apply {
        apply(TranscriptEvent.Line(0, 1.0, 3.0, "S1", "fr", "Salut, ça va bien? Oui très bien."))
        apply(TranscriptEvent.Line(1, 4.0, 5.0, "S2", "en", "Fine, thanks."))
        apply(TranscriptEvent.Line(2, 6.0, 6.4, "?", "en", "OK."))
        apply(TranscriptEvent.Line(3, 7.0, 9.0, "S3", "fr", "Moi aussi."))
    }.result()

    @Test fun renameMergeMeMoveAndSplitAreCheckedFirst() {
        assertEquals(TranscriptEvent.Rename("S1", "Marie"), TranscriptEdits.rename(t, "S1", " Marie "))
        assertNull(TranscriptEdits.rename(t, "S9", "Luc"))
        assertNull(TranscriptEdits.rename(t, "?", "Luc"))
        assertNull(TranscriptEdits.rename(TranscriptFold(t).apply(TranscriptEvent.Rename("S1", "Marie")).result(), "S1", "Marie"))
        assertEquals(TranscriptEvent.Merge("S3", "S1"), TranscriptEdits.merge(t, "S3", "S1"))
        assertNull(TranscriptEdits.merge(t, "S1", "S1"))
        assertNull(TranscriptEdits.merge(t, "?", "S1"))
        assertNull(TranscriptEdits.merge(t, "S1", "?"))
        assertNull(TranscriptEdits.merge(t, "S1", "S9"))
        assertEquals(TranscriptEvent.Me("S2"), TranscriptEdits.me(t, "S2"))
        assertNull(TranscriptEdits.me(t, "?"))
        assertEquals(TranscriptEvent.Speaker(2, "S2"), TranscriptEdits.move(t, 2, "S2"))
        assertEquals(TranscriptEvent.Speaker(1, "S4"), TranscriptEdits.move(t, 1, SpeakerLabels.next(t.speakers)))
        assertNull(TranscriptEdits.move(t, 1, "S2"))
        assertNull(TranscriptEdits.move(t, 1, "Marie"))
        assertNull(TranscriptEdits.move(t, 9, "S1"))
        val at = TranscriptEdits.wordStarts(t.line(0)!!.text)[4]
        assertEquals(TranscriptEvent.Split(0, at, 4, "S2"), TranscriptEdits.split(t, 0, at, 4, "S2"))
        assertEquals(TranscriptEvent.Split(0, at, 4, null), TranscriptEdits.split(t, 0, at, 4, "S1"))
        assertNull(TranscriptEdits.split(t, 0, 0, 4, "S2"))
        assertNull(TranscriptEdits.split(t, 0, at, 3, "S2"))
        assertNull(TranscriptEdits.split(t, 0, at, 4, "Luc"))
        assertEquals(listOf("S1", "S2", "S3"), TranscriptEdits.targets(t))
    }

    @Test fun withAnEnrolledVoiceThisIsMeIsAMerge() {
        val withMe = TranscriptFold(t).apply(TranscriptEvent.Line(4, 10.0, 12.0, "Me", "en", "Hello.")).result()
        assertEquals(TranscriptEvent.Merge("S2", "Me"), TranscriptEdits.me(withMe, "S2"))
        // "Me" is never merged into someone else.
        assertNull(TranscriptEdits.merge(withMe, "Me", "S1"))
        assertEquals("You", withMe.nameOf("Me"))
        assertEquals(listOf("Me", "S1", "S2", "S3"), TranscriptEdits.targets(withMe))
        assertFalse(withMe.oneVoice)
        assertTrue(TranscriptFold().apply(TranscriptEvent.Line(0, 1.0, 2.0, "S1", "fr", "Oui.")).result().oneVoice)
    }

    @Test fun editsRoundTripThroughTheLog() {
        val edits = listOf(
            TranscriptEdits.rename(t, "S1", "Marie")!!,
            TranscriptEdits.merge(t, "S3", "S1")!!,
            TranscriptEdits.move(t, 2, "S2")!!,
            TranscriptEdits.split(t, 0, TranscriptEdits.wordStarts(t.line(0)!!.text)[4], 4, "S2")!!,
            TranscriptEdits.me(t, "S2")!!,
        )
        val base = t.lines.joinToString("") { l -> TranscriptCodec.encode(TranscriptEvent.Line(l.i, l.t0, l.t1, l.speaker, l.lang, l.text)) + "\n" }
        val log = base + edits.joinToString("") { TranscriptCodec.encode(it) + "\n" }
        for (e in edits) assertEquals(e, TranscriptCodec.decode(TranscriptCodec.encode(e)))
        val r = TranscriptCodec.read(log)
        assertEquals(listOf("S1", "S2", "S2", "S2", "S1"), r.lines.map { it.speaker })
        assertEquals(listOf("Salut, ça va bien?", "Oui très bien.", "Fine, thanks.", "OK.", "Moi aussi."), r.lines.map { it.text })
        assertEquals("Marie", r.nameOf("S1"))
        assertEquals("You", r.nameOf("S2"))
        // The snapshot keeps all of it.
        val snap = TranscriptCodec.fromSnapshot(TranscriptCodec.snapshot(r))!!
        assertEquals(r.lines, snap.lines)
        assertEquals(r.names, snap.names)
        assertEquals(r.merges, snap.merges)
        assertEquals(r.me, snap.me)
    }

    // ------------------------------------------------------------------ on storage

    private val t0 = 1_760_191_320_000L

    @Test fun theLiveWriterAndTheLibraryEditAndMetaFollows() {
        val s = TranscriptStore(File(tmp.root, "transcripts"))
        val w = TranscriptWriter.start(s, "20261011-1402-5300", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        w.line(t0) { i -> TranscriptEvent.Line(i, 1.0, 3.0, "S1", "fr", "Salut, ça va bien? Oui très bien.") }
        w.line(t0) { i -> TranscriptEvent.Line(i, 4.0, 5.0, "S2", "en", "Fine, thanks.") }
        w.line(t0) { i -> TranscriptEvent.Line(i, 6.0, 8.0, "S3", "en", "Me too.") }
        // During the session: a split takes the writer's next index.
        val split = w.edit { tr, n -> TranscriptEdits.split(tr, 0, TranscriptEdits.wordStarts(tr.line(0)!!.text)[4], n, "S2") } as TranscriptEvent.Split
        assertEquals(3, split.n)
        assertEquals(TranscriptEvent.Rename("S2", "Luc"), w.edit { tr, _ -> TranscriptEdits.rename(tr, "S2", "Luc") })
        assertNull(w.edit { tr, _ -> TranscriptEdits.rename(tr, "S2", "Luc") })
        // The next line after the split gets a new index.
        assertEquals(4, w.line(t0) { i -> TranscriptEvent.Line(i, 9.0, 10.0, "S1", "fr", "Bon.") }!!.i)
        assertNull(w.close(t0 + 60_000, similar = listOf("S3" to "S2")))
        val m = s.meta(w.id)!!
        assertEquals(listOf("S1", "S2", "S3"), m.speakers.map { it.id })
        assertEquals("Luc", m.speakers[1].name)
        assertEquals(listOf("S3" to "S2"), m.similar)
        assertEquals(m, TranscriptMeta.fromJson(m.toJson()))
        // Afterwards, in the library: the proposal is taken, meta follows; a new split gets an index never used.
        val merge = s.edit(w.id) { tr, _ -> TranscriptEdits.merge(tr, "S3", "S2") }
        assertEquals(TranscriptEvent.Merge("S3", "S2"), merge)
        assertEquals(emptyList<Pair<String, String>>(), s.meta(w.id)!!.similar)
        assertEquals(listOf("S1", "S2"), s.meta(w.id)!!.speakers.map { it.id })
        val again = s.edit(w.id) { tr, n -> TranscriptEdits.split(tr, 1, 6, n, null) } as TranscriptEvent.Split
        assertEquals(5, again.n)
        assertNull(s.edit(w.id) { tr, _ -> TranscriptEdits.merge(tr, "S9", "S1") })
        val tr = s.load(w.id)!!
        assertEquals(listOf("Salut, ça va bien?", "Oui très bien.", "Fine,", "thanks.", "Me too.", "Bon."), tr.lines.map { it.text })
        assertEquals(listOf("S1", "S2", "S2", "S2", "S2", "S1"), tr.lines.map { it.speaker })
        // "Not the same person" takes a proposal away without an event.
        val d = s.meta(w.id)!!.copy(similar = listOf("S2" to "S1"))
        s.writeMeta(d)
        assertTrue(s.decline(w.id, "S1", "S2"))
        assertEquals(emptyList<Pair<String, String>>(), s.meta(w.id)!!.similar)
        // A deleted transcript takes no edit.
        s.delete(w.id)
        assertNull(s.edit(w.id) { tr2, _ -> TranscriptEdits.rename(tr2, "S1", "Marie") })
    }

    @Test fun metaKeepsOnlyProposalsThatStillMakeSense() {
        val m = TranscriptMeta("20261011-1402-5301", "", t0, similar = listOf("S3" to "S1", "S2" to "S1", "S1" to "S1"))
        val after = m.withSpeakers(t)
        assertEquals(listOf("S3" to "S1", "S2" to "S1"), after.similar)
        assertEquals(listOf("S2" to "S1"), after.without("S1", "S3").similar)
        assertEquals(listOf(SpeakerInfo("S1", null, 1), SpeakerInfo("S2", null, 2), SpeakerInfo("?", null, -1), SpeakerInfo("S3", null, 3)), after.speakers)
        // Unreadable proposals in the file are dropped.
        val o = org.json.JSONObject(m.toJson()).put("similar", org.json.JSONArray("""[["S3","S1"],["x","S1"],["S2"],"bad"]"""))
        assertEquals(listOf("S3" to "S1"), TranscriptMeta.fromJson(o.toString())!!.similar)
        assertNotNull(TranscriptMeta.fromJson(org.json.JSONObject(m.toJson()).apply { remove("similar") }.toString()))
    }
}
