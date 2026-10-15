// SPDX-License-Identifier: GPL-3.0-only
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

/** Transcripts on storage, the live session's writer and crash recovery (docs/conversation-mode.md 1.6, 6). */
class TranscriptStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_760_191_320_000L
    private fun store() = TranscriptStore(File(tmp.root, "transcripts"))
    private fun line(i: Int, t: Double, text: String = "line $i") = TranscriptEvent.Line(i, t, t + 2, "S1", "fr", text)

    @Test fun aSessionWritesLinesMetaSnapshotAndHeartbeat() {
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-7f3a", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        assertEquals(SessionStatus.RUNNING, s.meta(w.id)!!.status)
        assertEquals(listOf(SpeakerInfo("S1")), s.meta(w.id)!!.speakers)
        assertEquals(w.id, s.heartbeat()!!.id)
        val a = w.line(t0 + 5_000) { i -> line(i, 3.0) }!!
        val b = w.line(t0 + 9_000) { i -> line(i, 7.0) }!!
        assertEquals(listOf(0, 1), listOf(a.i, b.i))
        assertNull(w.event(TranscriptEvent.Lang(0, "en")))
        assertTrue(w.beat(t0 + 30_000))
        assertEquals(SessionHeartbeat(w.id, ConvMode.TRANSCRIBE, t0, t0 + 30_000, t0 + 9_000, 2), s.heartbeat())
        // Every record is in the log, each on its own line, as they happened.
        val log = File(s.dir(w.id), TranscriptFiles.LOG).readText()
        assertEquals(3, log.lines().count { it.isNotEmpty() })
        val read = s.load(w.id)!!
        assertEquals(listOf("en", "fr"), read.lines.map { it.lang })
        assertNull(w.close(t0 + 60_000))
        assertEquals(SessionStatus.ENDED, s.meta(w.id)!!.status)
        assertEquals(t0 + 60_000, s.meta(w.id)!!.endedAt)
        assertNull(s.heartbeat())
        // The snapshot folds the same transcript.
        assertEquals(read.lines, TranscriptCodec.fromSnapshot(File(s.dir(w.id), TranscriptFiles.SNAPSHOT).readText())!!.lines)
        assertEquals(listOf(w.id), s.list().map { it.id })
    }

    @Test fun aSnapshotEveryTwoHundredEvents() {
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-0001", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        repeat(199) { k -> w.line(t0) { i -> line(i, k * 3.0) } }
        assertFalse(File(s.dir(w.id), TranscriptFiles.SNAPSHOT).exists())
        w.line(t0) { i -> line(i, 999.0) }
        val snap = TranscriptCodec.fromSnapshot(File(s.dir(w.id), TranscriptFiles.SNAPSHOT).readText())!!
        assertEquals(200, snap.records)
        assertEquals(200, snap.lines.size)
    }

    @Test fun aDeletedTranscriptIsNeverWrittenBack() {
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-0002", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        w.line(t0) { i -> line(i, 1.0) }
        // The main app deletes it (another store object, as in another process).
        val main = store()
        assertTrue(main.delete(w.id))
        assertFalse(s.dir(w.id)!!.exists())
        assertNull(main.heartbeat())
        // The live session's next writes fail as MISSING and create nothing.
        assertNull(w.line(t0 + 1_000) { i -> line(i, 5.0) })
        assertEquals(IoCode.MISSING, w.failed)
        assertEquals(IoCode.MISSING, w.snapshot())
        assertFalse(w.beat(t0 + 2_000))
        assertEquals(IoCode.MISSING, w.close(t0 + 3_000))
        assertFalse(s.dir(w.id)!!.exists())
        assertNull(s.heartbeat())
        assertEquals(emptyList<String>(), File(tmp.root, "transcripts").list()!!.filter { !it.startsWith(".") })
        // Told by the Cmd, the voice process forgets it even if something recreated the folder.
        s.dir(w.id)!!.mkdirs()
        s.forget(w.id)
        assertEquals(IoCode.MISSING, s.append(w.id, line(9, 1.0)))
        assertFalse(s.exists(w.id))
        // And can't create it again.
        assertFalse(s.create(TranscriptMeta(w.id, "", t0)))
    }

    @Test fun deleteAllAndTheTrash() {
        val s = store()
        val a = TranscriptWriter.start(s, "20261011-1402-00a1", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        TranscriptWriter.start(s, "20261011-1403-00a2", t0 + 60_000, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!.close(t0 + 90_000)
        File(s.root, TranscriptStore.TRASH + "20261011-1300-0000-1").apply { mkdirs(); File(this, "lines.jsonl").writeText("x") }
        assertEquals(listOf("20261011-1403-00a2", a.id), s.list().map { it.id })
        assertEquals(2, s.deleteAll())
        assertEquals(emptyList<TranscriptMeta>(), s.list())
        assertNull(s.heartbeat())
        assertEquals(emptyList<String>(), s.root.list()!!.toList())
        assertNull(a.line(t0) { i -> line(i, 1.0) })
    }

    @Test fun renameKeepsTheRestAndAnEmptyTitleMeansTheDate() {
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-0003", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        w.close(t0 + 60_000)
        assertTrue(s.rename(w.id, "  Lunch \n with Marie "))
        assertEquals("Lunch with Marie", s.meta(w.id)!!.title)
        assertEquals(SessionStatus.ENDED, s.meta(w.id)!!.status)
        assertTrue(s.rename(w.id, ""))
        assertEquals("", s.meta(w.id)!!.title)
        assertFalse(s.rename("20261011-1402-ffff", "x"))
        assertFalse(s.rename("../evil", "x"))
    }

    @Test fun recoveryOffersResumeAfterACrashAndClosesAfterYourStop() {
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-0004", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        w.line(t0 + 5_000) { i -> line(i, 3.0) }
        w.line(t0 + 125_000) { i -> line(i, 120.0) }
        w.beat(t0 + 150_000)
        // The process died: the heartbeat is still there. Its own session (none in a new process) doesn't hold it.
        assertNull(s.recover(serviceRunning = true, stoppedByUser = false))
        val r = s.recover(serviceRunning = false, stoppedByUser = false)!!
        assertEquals(Recovered(w.id, SessionRecovery.Action.OFFER_RESUME, 2, 2, t0 + 125_000), r)
        assertEquals(SessionStatus.INTERRUPTED, s.meta(w.id)!!.status)
        assertEquals(t0 + 125_000, s.meta(w.id)!!.endedAt)
        assertNull(s.heartbeat())
        assertNull(s.recover(serviceRunning = false, stoppedByUser = false))
        // You stopped Robot in Active apps: closed as ended, no Resume.
        val w2 = TranscriptWriter.start(s, "20261011-1402-0005", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        assertEquals(SessionRecovery.Action.CLOSE, s.recover(false, stoppedByUser = true)!!.action)
        assertEquals(SessionStatus.ENDED, s.meta(w2.id)!!.status)
        // A heartbeat for a transcript that was deleted meanwhile: just removed.
        val w3 = TranscriptWriter.start(s, "20261011-1402-0006", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        val beat = File(s.root, SessionHeartbeat.FILE).readText()
        assertTrue(store().delete(w3.id))
        File(s.root, SessionHeartbeat.FILE).writeText(beat)
        assertNull(s.recover(false, false))
        assertNull(s.heartbeat())
        assertFalse(s.dir(w3.id)!!.exists())
    }

    @Test fun resumeGoesOnWithNewIndicesAndAGap() {
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-0007", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        w.line(t0 + 5_000) { i -> line(i, 3.0) }
        w.line(t0 + 30_000) { i -> line(i, 10.0) }
        w.event(TranscriptEvent.Split(1, 2, 2))
        w.event(TranscriptEvent.Delete(2))
        // The crash cut the last record in half.
        File(s.dir(w.id), TranscriptFiles.LOG).appendText("{\"e\":\"line\",\"i\":3,\"t0\":20")
        w.beat(t0 + 30_000)
        s.recover(false, false)!!
        val r = TranscriptWriter.resume(s, w.id, t0 + 300_000)!!
        assertEquals(SessionStatus.RUNNING, s.meta(w.id)!!.status)
        // Indices 0..2 were used (2 by the split, then deleted): the next line is 3, never a reused one.
        val l = r.line(t0 + 301_000) { i -> line(i, r.clock(t0 + 299_000)) }!!
        assertEquals(3, l.i)
        assertEquals(299.0, l.t0, 0.001)
        val t = s.load(w.id)!!
        // The gap from where the transcript stopped to the resume.
        assertEquals(listOf(TranscriptGap(30.0, 300.0, TranscriptEvent.GAP_CRASH)), t.gaps)
        assertEquals(listOf(0, 1, 3), t.lines.map { it.i })
        // The torn record is its own (skipped) line now; the fold's count matches the log's.
        r.close(t0 + 400_000)
        val records = TranscriptCodec.records(File(s.dir(w.id), TranscriptFiles.LOG).readText()).first.size
        assertEquals(records, TranscriptCodec.fromSnapshot(File(s.dir(w.id), TranscriptFiles.SNAPSHOT).readText())!!.records)
        assertEquals(t.lines, s.load(w.id)!!.lines)
        assertEquals(3, TranscriptWriter.maxIndex(File(s.dir(w.id), TranscriptFiles.LOG).readText()))
        // A transcript that is gone can't be resumed.
        assertNull(TranscriptWriter.resume(s, "20261011-1402-ffff", t0))
    }

    /**
     * A save that fails while the folder is there is an I/O error, not a delete (the session would stop as if you had
     * deleted it, and leave the file unclosed), and its line's index is never given to another line.
     */
    @Test fun aFailedSaveIsNotADeleteAndItsIndexIsNotReused() {
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-0009", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        w.line(t0) { i -> line(i, 1.0) }!!
        val log = File(s.dir(w.id), TranscriptFiles.LOG)
        // For a moment the log can't be opened (here: something else in its place).
        val saved = log.readText()
        assertTrue(log.delete() && log.mkdir())
        assertNull(w.line(t0 + 1_000) { i -> line(i, 3.0) })
        assertEquals(IoCode.WRITE, w.failed)
        assertTrue(log.delete())
        log.writeText(saved)
        assertEquals(2, w.line(t0 + 2_000) { i -> line(i, 5.0) }!!.i)
        assertNull(w.failed)
        assertEquals(listOf(0, 2), s.load(w.id)!!.lines.map { it.i })
        // The writer's count of records is still the file's, so the snapshot and the log agree.
        assertNull(w.close(t0 + 3_000))
        assertEquals(TranscriptCodec.records(log.readText()).first.size,
            TranscriptCodec.fromSnapshot(File(s.dir(w.id), TranscriptFiles.SNAPSHOT).readText())!!.records)
        assertEquals(SessionStatus.ENDED, s.meta(w.id)!!.status)
    }

    @Test fun badIdsAreRefused() {
        val s = store()
        assertNull(s.dir("../x"))
        assertEquals(IoCode.OTHER, s.append("nope", line(0, 0.0)))
        assertFalse(s.create(TranscriptMeta("a/b", "", t0)))
        assertNotNull(TranscriptWriter.start(s, "20261011-1402-0008", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN))
        // The same id twice: refused.
        assertNull(TranscriptWriter.start(s, "20261011-1402-0008", t0, ConvMode.TRANSCRIBE, LangPair.FR_EN))
        assertEquals(-1, TranscriptWriter.maxIndex(""))
    }

    /** What a session writes exports as the library shares it (docs/conversation-mode.md 6.3): one speaker, a fix, a pause. */
    @Test fun aRecordedTranscriptExportsAsMarkdownAndText() {
        val utc = java.time.ZoneId.of("UTC")
        val start = java.time.LocalDateTime.of(2026, 10, 11, 14, 2).atZone(utc).toInstant().toEpochMilli()
        val s = store()
        val w = TranscriptWriter.start(s, "20261011-1402-00b1", start, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        w.line(start + 5_000) { i -> TranscriptEvent.Line(i, 3.0, 5.5, "S1", "fr", "On se reprend-tu demain?") }
        w.line(start + 9_000) { i -> TranscriptEvent.Line(i, 6.0, 6.4, "S1", "fr", "OK.", prior = true) }
        w.event(TranscriptEvent.Lang(1, "en"))
        w.event(TranscriptEvent.Gap(10.0, 70.0, TranscriptEvent.GAP_PAUSE))
        w.line(start + 75_000) { i -> TranscriptEvent.Line(i, 71.0, 73.0, "S1", "en", "Sure, *tomorrow* works.") }
        w.close(start + 80_000)
        val meta = s.meta(w.id)!!
        val shown = meta.copy(title = TranscriptTitles.shown(meta, utc))
        val md = TranscriptExport.markdown(shown, s.load(w.id)!!, ExportOptions(), utc)
        assertEquals("""# Conversation · 11 Oct, 14:02

- Date: 11 October 2026, 14:02–14:03 (1 min)
- Speakers: S1
- Languages: English, French
- Transcribed on this phone by Robot (Parakeet v3).

**[14:02:03] S1 (FR):** On se reprend-tu demain?

**[14:02:06] S1 (EN):** OK.

*\[paused 14:02–14:03\]*

**[14:03:11] S1 (EN):** Sure, \*tomorrow\* works.
""", md)
        assertEquals("Transcript 2026-10-11 1402.md", TranscriptExport.fileName(meta, "md", utc))
        val txt = TranscriptExport.text(shown, s.load(w.id)!!, ExportOptions(times = false), utc)
        assertTrue(txt.contains("\nS1 (FR): On se reprend-tu demain?\n"))
        assertTrue(txt.contains("[paused 14:02–14:03]"))
        // Speech let go when decoding fell too far behind says so; a kind from a newer Robot reads as nothing recorded.
        assertEquals("[not transcribed 14:02–14:03]", TranscriptExport.gapText(meta, TranscriptGap(10.0, 70.0, TranscriptEvent.GAP_BEHIND), utc))
        assertEquals("[nothing recorded 14:02–14:03]", TranscriptExport.gapText(meta, TranscriptGap(10.0, 70.0, "later"), utc))
    }
}
