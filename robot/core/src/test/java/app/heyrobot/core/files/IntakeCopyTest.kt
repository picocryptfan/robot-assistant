// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * files-1.4-plan.md §3 (F5): one copy of a sender's bytes into incoming/, against senders that misbehave. An endless
 * stream stops at the cap; a claimed size only refuses early and never counts; a stream that stalls, trickles, sends
 * nothing or never ends a read is given up on time and its stream closed; one whose read ignores the close ends later and
 * what it staged is discarded; a sender's failure is told from Robot's own; empty, locked, full and low space are refused;
 * the type is sniffed from the bytes that were staged, never the sender's word. After every refusal nothing of the file is
 * left on disk.
 */
class IntakeCopyTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private var free = 100L * FileLimits.GIB
    private val keys = FakeFileKeys()
    private val dir by lazy { tmp.newFolder("files") }
    private val store by lazy { FileStore(dir, keys, "main", space = { free }) }
    private val discarded = AtomicInteger()
    private val short = IntakeCopy.Times(idleMs = 300, totalMs = 2_000, openMs = 300, stopMs = 1_000, tickMs = 20)

    private fun copier(times: IntakeCopy.Times = short) =
        IntakeCopy({ input, declared -> store.stage(input, declared) }, { discarded.incrementAndGet(); store.discard(it) }, times)

    @After fun noInflight() = FileStore.forgetInflight()

    /** Everything Robot keeps of files, by name: nothing of a refused file may be left. */
    private fun leftovers(): List<String> = dir.walk().filter { it.isFile && it.name != FileStore.LOCK && it.name != FileStore.INDEX }.map { it.name }.toList()

    private val pdf = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\ntrailer\n%%EOF\n".toByteArray()

    @Test fun aFileIsStagedHashedAndSniffedThenCommittedWithItsProvenance() {
        val r = copier().copy(ByteArrayInputStream(pdf), declared = pdf.size.toLong()) as Intake.Ready
        assertEquals(FileType.PDF, r.type)
        assertEquals(pdf.size.toLong(), r.staged.size)
        val e = store.commit(r.staged, FileIntake("receipt.pdf", FileSource.SHARE, app = "org.example.mail", type = r.type.mime))
        assertEquals(FileSource.SHARE, e.from.source)
        assertEquals("org.example.mail", e.from.app)
        assertEquals("application/pdf", e.from.type)
        assertEquals(r.staged.sha256, e.from.sha256)
        assertArrayEquals(pdf, store.readAll(e.id, FileLimits.MAX_FILE))
    }

    @Test fun theTypeIsTheBytesNeverTheName() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(200) { 1 }
        val r = copier().copy(ByteArrayInputStream(zip), declared = null) as Intake.Ready
        assertEquals(FileType.ZIP, r.type)
        store.discard(r.staged)
        assertEquals(emptyList<String>(), leftovers())
    }

    /** FX2 (files-1.4-review.md finding 14): a ZIP appended to a PDF, or markup past a text's first 4 KiB, comes in DANGEROUS. */
    @Test fun whatTheFirstBytesHideIsSeenAsItStreams() {
        val end = byteArrayOf(0x50, 0x4B, 5, 6) + ByteArray(18)
        val r = copier().copy(ByteArrayInputStream(pdf + ByteArray(70_000) + end), declared = null) as Intake.Ready
        assertEquals(FileType.ZIP, r.type)
        val text = "Paid in full.\n".repeat(500).toByteArray() + "<script>x()</script>".toByteArray()
        val t = copier().copy(ByteArrayInputStream(text), declared = null) as Intake.Ready
        assertEquals(FileType.HTML, t.type)
        val plain = copier().copy(ByteArrayInputStream("Paid in full.\n".repeat(500).toByteArray()), declared = null) as Intake.Ready
        assertEquals(FileType.TEXT, plain.type)
        for (x in listOf(r, t, plain)) store.discard(x.staged)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun anEndlessStreamStopsAtTheCap() {
        val endless = object : InputStream() {
            override fun read(): Int = 7
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(7, off, off + len); return len }
        }
        val r = copier(IntakeCopy.Times(idleMs = 20_000, totalMs = 120_000, tickMs = 20)).copy(endless, declared = 1_000)
        assertEquals(IntakeProblem.TOO_BIG, (r as Intake.Failed).problem)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aClaimOverTheCapIsRefusedBeforeAnyByteIsRead() {
        val read = AtomicBoolean()
        val untouched = object : InputStream() {
            override fun read(): Int { read.set(true); return -1 }
        }
        assertEquals(IntakeProblem.TOO_BIG, (copier().copy(untouched, declared = FileLimits.MAX_FILE + 1) as Intake.Failed).problem)
        assertFalse(read.get())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aClaimedSizeIsOnlyAClaim() {
        // Claims a large size and sends a little: what came is what counts.
        val r = copier().copy(ByteArrayInputStream(pdf), declared = 150L * FileLimits.MIB) as Intake.Ready
        assertEquals(pdf.size.toLong(), r.staged.size)
        store.discard(r.staged)
        // Claims a little and sends more: read in full (under the cap), the claim ignored.
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val r2 = copier().copy(ByteArrayInputStream(big), declared = 10) as Intake.Ready
        assertEquals(big.size.toLong(), r2.staged.size)
        store.discard(r2.staged)
        // A negative claim is no claim.
        val r3 = copier().copy(ByteArrayInputStream(pdf), declared = -5) as Intake.Ready
        store.discard(r3.staged)
        assertEquals(emptyList<String>(), leftovers())
    }

    /** A read that blocks until its stream is closed, as a pipe from a sender that stopped writing does. */
    private class Stalled(private val first: ByteArray = ByteArray(0)) : InputStream() {
        val closed = CountDownLatch(1)
        private var sent = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!sent && first.isNotEmpty()) {
                sent = true
                System.arraycopy(first, 0, b, off, first.size)
                return first.size
            }
            closed.await()
            throw IOException("closed")
        }
        override fun close() = closed.countDown()
    }

    @Test fun aSenderThatStopsSendingIsGivenUpAndItsStreamClosed() {
        val s = Stalled(pdf)
        val t0 = System.nanoTime()
        val r = copier().copy(s, declared = 1_000_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(IntakeProblem.TIMED_OUT, (r as Intake.Failed).problem)
        assertTrue("$ms ms", ms in 300..1_900)
        assertEquals(0L, s.closed.count)
        assertTrue(waitFor { leftovers().isEmpty() })
    }

    @Test fun aSenderThatSendsNothingIsGivenUp() {
        val s = Stalled()
        assertEquals(IntakeProblem.TIMED_OUT, (copier().copy(s, declared = null) as Intake.Failed).problem)
        assertTrue(waitFor { leftovers().isEmpty() })
    }

    @Test fun aStreamThatReadsNothingForeverIsGivenUp() {
        val zero = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertEquals(IntakeProblem.TIMED_OUT, (copier().copy(zero, declared = null) as Intake.Failed).problem)
        assertTrue(waitFor { leftovers().isEmpty() })
    }

    @Test fun aTrickleThatOutlastsTheWholeCopyIsGivenUp() {
        // A byte every 50 ms never trips the idle limit, but the copy's own deadline ends it.
        val trickle = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(50); b[off] = 1; return 1 }
        }
        val t0 = System.nanoTime()
        assertEquals(IntakeProblem.TIMED_OUT, (copier().copy(trickle, declared = null) as Intake.Failed).problem)
        assertTrue((System.nanoTime() - t0) / 1_000_000 in 2_000..3_900)
        assertTrue(waitFor { leftovers().isEmpty() })
    }

    @Test fun whatACopyStagesAfterItWasGivenUpIsDiscarded() {
        // The sender's stream ended, but staging takes longer than the copy's deadline (a slow fsync, the other process
        // holding the lock): the store finishes a whole file after the copy was given up. Nobody wants it, and it must not
        // stay in incoming/ (the next start would put it in Inbox without your tap).
        val release = CountDownLatch(1)
        val c = IntakeCopy({ input, declared ->
            val bytes = input.readBytes()
            release.await()
            store.stage(ByteArrayInputStream(bytes), declared)
        }, { discarded.incrementAndGet(); store.discard(it) }, short.copy(totalMs = 500, stopMs = 50))
        val r = c.copy(ByteArrayInputStream(pdf), declared = null)
        assertEquals(IntakeProblem.TIMED_OUT, (r as Intake.Failed).problem)
        release.countDown()
        assertTrue(waitFor { discarded.get() == 1 })
        assertTrue(waitFor { leftovers().isEmpty() })
    }

    @Test fun theSendersFailureIsToldFromRobots() {
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ > 3) throw IOException("sender went away")
                b.fill(1, off, off + len)
                return len
            }
        }
        assertEquals(IntakeProblem.UNREADABLE, (copier().copy(failing, declared = null) as Intake.Failed).problem)
        val weird = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SecurityException("no grant")
        }
        assertEquals(IntakeProblem.UNREADABLE, (copier().copy(weird, declared = null) as Intake.Failed).problem)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun emptyIsRefused() {
        assertEquals(IntakeProblem.EMPTY, (copier().copy(ByteArray(0)) as Intake.Failed).problem)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun lowSpaceSaysHowMuchMoreIsNeeded() {
        free = 1_200L * FileLimits.MIB
        val r = copier().copy(ByteArrayInputStream(pdf), declared = 600L * FileLimits.MIB) as Intake.Failed
        // A claim over the cap is TOO_BIG first; under it, the free-space rule: twice the file plus 1 GiB.
        assertEquals(IntakeProblem.TOO_BIG, r.problem)
        val r2 = copier().copy(ByteArrayInputStream(pdf), declared = 190L * FileLimits.MIB) as Intake.Failed
        assertEquals(IntakeProblem.LOW_SPACE, r2.problem)
        assertEquals(FileLimits.spaceToAdd(190L * FileLimits.MIB) - free, r2.needed)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun lockedAndKeyGoneAreRefused() {
        val r = copier().copy(ByteArrayInputStream(pdf), declared = null) as Intake.Ready
        store.commit(r.staged, FileIntake("a.pdf", FileSource.SHARE))
        keys.refuse = FileKeyProblem.LOCKED
        store.drop()
        assertEquals(IntakeProblem.LOCKED, (copier().copy(ByteArrayInputStream(pdf), declared = null) as Intake.Failed).problem)
        keys.refuse = FileKeyProblem.GONE
        assertEquals(IntakeProblem.KEY_GONE, (copier().copy(ByteArrayInputStream(pdf), declared = null) as Intake.Failed).problem)
        assertEquals(listOf("${r.staged.id.hex}.rf"), leftovers())
    }

    /** robot_files_v1 as Android leaves it after the screen lock was removed: there, but every use says it's gone. */
    private class StaleKeys(private val real: FakeFileKeys) : FileKeys by real {
        var stale = true
        var deletes = 0
        override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray =
            if (stale) throw FileKeyException(FileKeyProblem.GONE) else real.wrap(key, aad, create)
        override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray =
            if (stale) throw FileKeyException(FileKeyProblem.GONE) else real.unwrap(wrapped, aad)
        override fun delete() {
            deletes++
            stale = false
            real.delete()
        }
    }

    @Test fun aStaleKeyOverAnEmptyCabinetIsReplacedOnce() {
        val k = StaleKeys(FakeFileKeys(present = true))
        val s = FileStore(dir, k, "main", space = { free })
        val c = IntakeCopy({ input, declared -> s.stage(input, declared) }, { s.discard(it) }, short)
        val r = c.copy(ByteArrayInputStream(pdf), declared = null) as Intake.Ready
        assertEquals(1, k.deletes)
        s.commit(r.staged, FileIntake("a.pdf", FileSource.SHARE))
        assertEquals(1, (s.load() as IndexLoad.Loaded).index.entries.size)
        // Once something is sealed, a key that is gone is never replaced: the files are kept, and Settings › Files says so.
        k.stale = true
        s.drop()
        assertEquals(IntakeProblem.KEY_GONE, (c.copy(ByteArrayInputStream(pdf), declared = null) as Intake.Failed).problem)
        assertEquals(1, k.deletes)
        assertEquals(IndexLoad.KeyGone, s.load())
    }

    /**
     * FX2 (files-1.4-review.md K2): an index Start over kept aside (index.enc.bad), a half-written index or file, or any file
     * in the sealed folders is sealed with that key too: with any of them there, the key is never replaced (they could
     * still be restored with it). The replacement, when it happens, leaves a FILE_KEY code in the voice log.
     */
    @Test fun leftoversCountAsSealedSoTheStaleKeyStays() {
        for (left in listOf("index.enc.bad", ".index.enc.tmp", "blobs/${"a".repeat(32)}.rf.part", "thumbs/.${"b".repeat(32)}.rf.tmp", "blobs/odd-name",
                "incoming/voice/${"c".repeat(32)}.rf")) {
            dir.deleteRecursively()
            dir.mkdirs()
            File(dir, left).apply { parentFile.mkdirs(); writeBytes(ByteArray(64) { 7 }) }
            val k = StaleKeys(FakeFileKeys(present = true))
            val s = FileStore(dir, k, "main", space = { free })
            val c = IntakeCopy({ input, declared -> s.stage(input, declared) }, { s.discard(it) }, short)
            assertEquals(left, IntakeProblem.KEY_GONE, (c.copy(ByteArrayInputStream(pdf), declared = null) as Intake.Failed).problem)
            assertEquals(left, 0, k.deletes)
            assertTrue(left, File(dir, left).isFile)
        }
        // Nothing sealed: replaced, and logged as codes.
        dir.deleteRecursively()
        dir.mkdirs()
        val k = StaleKeys(FakeFileKeys(present = true))
        val s = FileStore(dir, k, "main", space = { free })
        val c = IntakeCopy({ input, declared -> s.stage(input, declared) }, { s.discard(it) }, short)
        val before = app.heyrobot.core.VoiceLog.entries().count { it.event == app.heyrobot.core.VoiceEvent.FILE_KEY }
        val r = c.copy(ByteArrayInputStream(pdf), declared = null) as Intake.Ready
        assertEquals(1, k.deletes)
        val keyLines = app.heyrobot.core.VoiceLog.entries().filter { it.event == app.heyrobot.core.VoiceEvent.FILE_KEY }
        assertEquals(before + 1, keyLines.size)
        // Newest first.
        assertEquals(FileProblem.KEY_GONE.id, keyLines.first().a)
        assertEquals(1, keyLines.first().b)
        s.discard(r.staged)
    }

    /**
     * FX3: a restore waiting at COMMIT keeps its sealed files in its own staging folder (outside this store's): with any of
     * them there, the key is never replaced either. Only its sealed names count (a plan.json left there doesn't).
     */
    @Test fun aRestoresStagingCountsAsSealedSoTheStaleKeyStays() {
        val staging = File(dir.parentFile, "restore-files")
        for (left in listOf("${"d".repeat(32)}.rf", "entries.rf", "${"e".repeat(32)}.rf.part")) {
            dir.deleteRecursively()
            staging.deleteRecursively()
            dir.mkdirs()
            File(staging, left).apply { parentFile.mkdirs(); writeBytes(ByteArray(64) { 7 }) }
            val k = StaleKeys(FakeFileKeys(present = true))
            val s = FileStore(dir, k, "main", space = { free }, restoreStaging = staging)
            val c = IntakeCopy({ input, declared -> s.stage(input, declared) }, { s.discard(it) }, short)
            assertEquals(left, IntakeProblem.KEY_GONE, (c.copy(ByteArrayInputStream(pdf), declared = null) as Intake.Failed).problem)
            assertEquals(left, 0, k.deletes)
            assertTrue(left, File(staging, left).isFile)
        }
        // A plan alone: nothing sealed there, so replaced as before.
        dir.deleteRecursively()
        staging.deleteRecursively()
        dir.mkdirs()
        File(staging, "plan.json").apply { parentFile.mkdirs(); writeText("{}") }
        val k = StaleKeys(FakeFileKeys(present = true))
        val s = FileStore(dir, k, "main", space = { free }, restoreStaging = staging)
        val c = IntakeCopy({ input, declared -> s.stage(input, declared) }, { s.discard(it) }, short)
        val r = c.copy(ByteArrayInputStream(pdf), declared = null) as Intake.Ready
        assertEquals(1, k.deletes)
        s.discard(r.staged)
        staging.deleteRecursively()
    }

    @Test fun aKeyThatIsOnlyNotNowIsNeverReplaced() {
        val keys = FakeFileKeys(present = true).apply { refuse = FileKeyProblem.LOCKED }
        val s = FileStore(dir, keys, "main", space = { free })
        val c = IntakeCopy({ input, declared -> s.stage(input, declared) }, { s.discard(it) }, short)
        assertEquals(IntakeProblem.LOCKED, (c.copy(ByteArrayInputStream(pdf), declared = null) as Intake.Failed).problem)
        assertTrue(keys.master != null)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun openingGivesUpOnASenderThatDoesntAnswer() {
        val c = copier()
        val cancelled = AtomicBoolean()
        val closedLate = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val v = c.within<String>({ cancelled.set(true) }, { if (it == "late") closedLate.countDown() }) {
            answer.await()
            "late"
        }
        assertNull(v)
        assertTrue(cancelled.get())
        answer.countDown()
        assertTrue(closedLate.await(2, TimeUnit.SECONDS))
        assertEquals("now", c.within<String>({}, {}) { "now" })
        assertEquals("no grant", try { c.within<String>({}, {}) { throw SecurityException("no grant") } } catch (x: SecurityException) { x.message })
    }

    private fun waitFor(ms: Long = 3_000, ok: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            if (ok()) return true
            Thread.sleep(20)
        }
        return ok()
    }
}
