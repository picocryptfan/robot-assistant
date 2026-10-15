// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** The transcript's append-only event log (docs/conversation-mode.md 6.1, 6.2). */
class TranscriptCodecTest {
    private val events = listOf(
        TranscriptEvent.Line(0, 1.5, 4.25, "S1", "fr", "Salut, ça va? On se voit à 17 h.", 9.3, words = listOf(0, 40, 72, 110, 150, 190, 220, 250, 270)),
        TranscriptEvent.Line(1, 5.0, 6.1, "S2", "en", "Yes, \"sure\".\nSee you then.", 4.0, prior = true, reheard = true),
        TranscriptEvent.Line(2, 7.0, 8.0, "?", null, "Euh"),
        TranscriptEvent.Translation(0, "en", TranscriptEvent.BY_BERGAMOT, "Hi, how are you? See you at 5 pm."),
        TranscriptEvent.Translation(0, "en", TranscriptEvent.BY_GEMMA, "Hey, how's it going? See you at 5."),
        TranscriptEvent.Edit(1, "Yes, sure. See you then."),
        TranscriptEvent.Speaker(2, "S1"),
        TranscriptEvent.Lang(2, "fr"),
        TranscriptEvent.Rename("S1", "Marie"),
        TranscriptEvent.Me("S2"),
        TranscriptEvent.Gap(10.0, 250.0, TranscriptEvent.GAP_MIC),
        TranscriptEvent.Delete(9),
        TranscriptEvent.Merge("S3", "S1"),
        TranscriptEvent.Split(0, 14, 3),
    )

    @Test fun everyEventRoundTripsOnOneLine() {
        for (e in events) {
            val line = TranscriptCodec.encode(e)
            assertFalse(line, line.contains('\n'))
            assertEquals(e, TranscriptCodec.decode(line))
        }
        // The plan's records read as they are written there.
        assertEquals(TranscriptEvent.Line(12, 73.42, 78.1, "S2", "fr", "Bon.", 0.93, words = listOf(0, 24, 40)),
            TranscriptCodec.decode("""{"e":"line","i":12,"t0":73.42,"t1":78.10,"spk":"S2","lang":"fr","lid":0.93,"text":"Bon.","ws":[0,24,40],"reheard":false}"""))
        assertEquals(TranscriptEvent.Translation(12, "en", "bergamot", "Good."), TranscriptCodec.decode("""{"e":"tr","i":12,"to":"en","by":"bergamot","text":"Good."}"""))
        assertEquals(TranscriptEvent.Merge("S3", "S1"), TranscriptCodec.decode("""{"e":"merge","from":"S3","to":"S1"}"""))
        assertEquals(TranscriptEvent.Gap(1.0, 2.5, "mic"), TranscriptCodec.decode("""{"e":"gap","t0":1,"t1":2.5,"why":"mic"}"""))
        // Times are kept to the centisecond.
        assertEquals(TranscriptEvent.Gap(1.23, 2.0, "mic"), TranscriptCodec.decode(TranscriptCodec.encode(TranscriptEvent.Gap(1.234567, 2.0, "mic"))))
        assertNull(TranscriptCodec.decode("{not json"))
        assertNull(TranscriptCodec.decode("""{"e":"line","i":1}"""))
        assertEquals(TranscriptEvent.Unknown("""{"e":"bookmark","i":3}"""), TranscriptCodec.decode("""{"e":"bookmark","i":3}"""))
    }

    @Test fun foldingAppliesEditsWithoutRewritingHistory() {
        val log = events.joinToString("") { TranscriptCodec.encode(it) + "\n" }
        val t = TranscriptCodec.read(log)
        assertEquals(events.size, t.records)
        assertEquals(0, t.skipped)
        // Line 0 was split at "…va?" into lines 0 and 3; line 1 was edited; line 2 moved to S1 (Marie) and set to French.
        assertEquals(listOf(0, 3, 1, 2), t.lines.map { it.i })
        val l0 = t.line(0)!!
        assertEquals("Salut, ça va?", l0.text)
        assertEquals("On se voit à 17 h.", t.line(3)!!.text)
        // The split falls on the fourth word ("On", 1.1 s in): times and word starts follow.
        assertEquals(2.6, l0.t1, 1e-9)
        assertEquals(2.6, t.line(3)!!.t0, 1e-9)
        assertEquals(listOf(0, 40, 72), l0.words)
        assertEquals(listOf(0, 40, 80, 110, 140, 160), t.line(3)!!.words)
        // Translations are dropped by a split (they no longer match) ...
        assertEquals(emptyMap<String, LineTranslation>(), l0.translations)
        val l1 = t.line(1)!!
        assertEquals("Yes, sure. See you then.", l1.text)
        assertTrue(l1.edited && l1.prior && l1.reheard)
        val l2 = t.line(2)!!
        assertEquals("S1" to "fr", l2.speaker to l2.lang)
        assertEquals("Marie", t.nameOf("S1"))
        assertEquals("S2", t.me)
        assertEquals("You", t.nameOf("S2"))
        assertEquals(listOf(TranscriptGap(10.0, 250.0, "mic")), t.gaps)
        assertEquals(listOf("S1", "S2"), t.speakers)
    }

    @Test fun aNewerTranslationReplacesTheFastOne() {
        val t = TranscriptCodec.read(events.take(5).joinToString("\n", postfix = "\n") { TranscriptCodec.encode(it) })
        val tr = t.line(0)!!.translations.getValue("en")
        assertEquals(TranscriptEvent.BY_GEMMA, tr.by)
        assertFalse(tr.fast)
        // Changing a line's language drops its translations (they are made again) and its "?".
        val again = TranscriptFold(t).apply(TranscriptEvent.Lang(0, "en")).result().line(0)!!
        assertEquals(emptyMap<String, LineTranslation>(), again.translations)
        assertFalse(again.prior)
    }

    @Test fun aTornLastLineIsDroppedAndUnknownEventsSkipped() {
        val good = TranscriptCodec.encode(events[0]) + "\n" + """{"e":"bookmark","i":0,"label":"later"}""" + "\n" + "garbage\n"
        val torn = good + TranscriptCodec.encode(events[1]).take(30)
        val t = TranscriptCodec.read(torn)
        assertEquals(listOf(0), t.lines.map { it.i })
        assertEquals(1, t.torn)
        assertEquals(3, t.records)
        assertEquals(2, t.skipped)
        // A line that ends with its line break is whole, even the last.
        assertEquals(0, TranscriptCodec.read(good).torn)
    }

    @Test fun appendingAfterACrashStartsOnAFreshLine() {
        val dir = java.nio.file.Files.createTempDirectory("transcript").toFile()
        try {
            val log = File(dir, TranscriptFiles.LOG)
            TranscriptFiles.append(log, TranscriptCodec.encode(events[0]))
            // A crash mid-write leaves half a record without its line break.
            log.appendText(TranscriptCodec.encode(events[1]).take(25))
            TranscriptFiles.append(log, TranscriptCodec.encode(events[2]))
            val t = TranscriptCodec.read(log.readText())
            assertEquals(listOf(0, 2), t.lines.map { it.i })
            assertEquals(1, t.skipped)
            assertEquals(0, t.torn)
            assertTrue(log.readText().endsWith("\n"))
            // One line per record, always.
            try {
                TranscriptFiles.append(log, "two\nlines")
                assertTrue(false)
            } catch (_: IllegalArgumentException) {}
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aSnapshotPlusTheRestEqualsTheWholeLog() {
        val lines = events.map { TranscriptCodec.encode(it) + "\n" }
        val full = TranscriptCodec.read(lines.joinToString(""))
        for (k in 0..events.size) {
            val snap = TranscriptCodec.snapshot(TranscriptCodec.read(lines.take(k).joinToString("")))
            assertEquals("after $k", full, TranscriptCodec.load(snap, lines.joinToString("")))
        }
        // A damaged snapshot is ignored.
        assertEquals(full, TranscriptCodec.load("{\"v\":1,", lines.joinToString("")))
        assertEquals(full, TranscriptCodec.load("{\"v\":99,\"records\":3,\"lines\":[]}", lines.joinToString("")))
        // Merges carry over a snapshot: a line that still arrives as S3 is S1's.
        val merged = TranscriptCodec.snapshot(full)
        val late = TranscriptCodec.load(merged, lines.joinToString("") + TranscriptCodec.encode(TranscriptEvent.Line(7, 300.0, 301.0, "S3", "fr", "Oui.")) + "\n")
        assertEquals("S1", late.line(7)!!.speaker)
    }

    @Test fun mergingMovesLinesAndNames() {
        val f = TranscriptFold()
        f.apply(TranscriptEvent.Line(0, 0.0, 1.0, "S1", "fr", "a"))
        f.apply(TranscriptEvent.Line(1, 2.0, 3.0, "S3", "fr", "b"))
        f.apply(TranscriptEvent.Rename("S3", "Luc"))
        f.apply(TranscriptEvent.Merge("S3", "S1"))
        var t = f.result()
        assertEquals(listOf("S1", "S1"), t.lines.map { it.speaker })
        assertEquals("Luc", t.nameOf("S1"))
        // A merge back the other way would loop: ignored.
        f.apply(TranscriptEvent.Merge("S1", "S3"))
        t = f.result()
        assertEquals(listOf("S1", "S1"), t.lines.map { it.speaker })
        // An empty name removes it; deleting a line removes it.
        f.apply(TranscriptEvent.Rename("S1", " "))
        f.apply(TranscriptEvent.Delete(0))
        t = f.result()
        assertEquals("S1", t.nameOf("S1"))
        assertEquals(listOf(1), t.lines.map { it.i })
    }

    @Test fun metaRoundTripsAndMemoryIsOffUnlessTurnedOn() {
        val m = TranscriptMeta("20261011-1402-7f3a", "Lunch with Marie", 1_791_000_000_000, 1_791_002_460_000, SessionStatus.ENDED, ConvMode.INTERPRET,
            LangPair.FR_EN, listOf(SpeakerInfo("S1", "Marie", 2), SpeakerInfo("S2", null, 0, me = true)), keepAudio = true, memory = true, important = true)
        assertEquals(m, TranscriptMeta.fromJson(m.toJson()))
        val bare = TranscriptMeta.fromJson("""{"id":"20261011-1402-7f3a","createdAt":5}""")!!
        assertFalse(bare.memory)
        assertEquals(SessionStatus.INTERRUPTED, bare.status)
        // A path in the id is refused.
        assertNull(TranscriptMeta.fromJson("""{"id":"../../shared_prefs","createdAt":5}"""))
    }

    @Test fun idsSortByTimeAndAreSafeNames() {
        val id = TranscriptIds.new(LocalDateTime.of(2026, 10, 11, 14, 2), java.util.Random(1))
        assertTrue(id, Regex("20261011-1402-[0-9a-f]{4}").matches(id))
        assertTrue(TranscriptIds.valid(id))
        for (bad in listOf("", "../x", "20261011-1402-7F3A", "20261011-1402-7f3a/..", "x20261011-1402-7f3a")) assertFalse(bad, TranscriptIds.valid(bad))
        assertTrue(TranscriptIds.new(LocalDateTime.of(2026, 10, 11, 14, 2)) < TranscriptIds.new(LocalDateTime.of(2026, 10, 11, 14, 3)))
        assertEquals("summary-2.json", TranscriptFiles.summary(2))
    }

    @Test fun theHeartbeatAndRecovery() {
        val beat = SessionHeartbeat("20261011-1402-7f3a", ConvMode.TRANSCRIBE, 1_000, 31_000, 29_500, 12)
        assertEquals(beat, SessionHeartbeat.fromJson(beat.toJson()))
        assertNull(SessionHeartbeat.fromJson("""{"id":"../x"}"""))
        assertNull(SessionHeartbeat.fromJson(null))
        assertEquals(SessionRecovery.Action.OFFER_RESUME, SessionRecovery.decide(beat, serviceRunning = false, stoppedByUser = false))
        assertEquals(SessionRecovery.Action.CLOSE, SessionRecovery.decide(beat, serviceRunning = false, stoppedByUser = true))
        assertEquals(SessionRecovery.Action.NONE, SessionRecovery.decide(beat, serviceRunning = true, stoppedByUser = false))
        assertEquals(SessionRecovery.Action.NONE, SessionRecovery.decide(null, serviceRunning = false, stoppedByUser = false))
        assertEquals(29_500L, SessionRecovery.savedUpTo(beat))
        assertEquals(31_000L, SessionRecovery.savedUpTo(beat.copy(lastLineAt = 0)))
    }
}
