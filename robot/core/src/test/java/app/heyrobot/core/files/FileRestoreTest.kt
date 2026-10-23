// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

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
import java.security.MessageDigest
import kotlin.random.Random

/**
 * files-1.4-plan.md §2.8 (F11): a backup's files sealed again under this phone's robot_files_v1 into the restore's own
 * staging folder (never this store's), with fresh keys and never in the clear; then the repeatable commit: Replace's
 * deletes, the index, the files moved in, cut at every step and finished by running it again, after the store's own
 * recovery, without a file landing in Inbox or one restored being deleted.
 */
class FileRestoreTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private class Crash : Error()

    private val keys = FakeFileKeys()
    private val dir by lazy { tmp.newFolder("files") }
    private val staging by lazy { tmp.newFolder("restore-files") }

    private fun store(k: FileKeys = keys) = FileStore(dir, k, "voice", space = { 100L * FileLimits.GIB })

    @After fun noInflight() = FileStore.forgetInflight()

    private val marker = "zq9marker".toByteArray()
    private fun data(n: Int, seed: Int) = marker + Random(seed).nextBytes(n)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun loaded(s: FileStore) = (s.load() as IndexLoad.Loaded).index
    private fun id(n: Int) = FileId.of("%032x".format(n))!!
    private fun entry(id: FileId, b: ByteArray, name: String = "restored $id") =
        FileEntry(id, name, FileKind.RECEIPT, FileState.FILED, Provenance(FileSource.SHARE, 1L, b.size.toLong(), sha(b)), drawer = Drawers.RECEIPTS)
    private fun inTheClear(d: File) = d.walkTopDown().filter { it.isFile }.any { f -> f.readBytes().let { b -> b.indices.any { i -> b.size - i >= marker.size && marker.indices.all { b[i + it] == marker[it] } } } }

    @Test fun aFreshPhoneStartsItsCabinetAndTakesTheFilesSealed() {
        val keys = FakeFileKeys(present = false)
        val s = store(keys)
        assertEquals(FileIndex(), s.prepareRestore())
        assertEquals(1, keys.created)
        val a = data(70_000, 1)
        val b = data(0, 2)
        val ra = s.sealRestored(staging, id(1), ByteArrayInputStream(a))
        val rb = s.sealRestored(staging, id(2), ByteArrayInputStream(b))
        assertEquals(a.size.toLong(), ra.size)
        assertEquals(sha(a), ra.sha256)
        assertArrayEquals(a.copyOf(4096), ra.head)
        assertEquals(sha(b), rb.sha256)
        // Sealed in the staging folder, nothing in the clear, nothing in the store's own folders yet.
        assertFalse(inTheClear(staging))
        assertFalse(File(dir, "blobs").exists())
        val entries = listOf(entry(id(1), a), entry(id(2), b))
        s.sealRestoreIndex(File(staging, "entries.rf"), FileIndex(entries, mapOf("rethumb" to listOf(id(1).hex))))
        assertFalse(String(File(staging, "entries.rf").readBytes(), Charsets.ISO_8859_1).contains("restored"))
        val back = s.readRestoreIndex(File(staging, "entries.rf"))
        assertEquals(entries, back.entries)
        assertEquals(2, s.restoreIn(staging, back.entries, emptySet()) { it + back.extra })
        assertEquals(entries.map { it.id }, loaded(s).entries.map { it.id })
        assertEquals(listOf(id(1).hex), loaded(s).extra["rethumb"])
        assertArrayEquals(a, s.readAll(id(1), FileLimits.MAX_FILE))
        assertArrayEquals(b, s.readAll(id(2), FileLimits.MAX_FILE))
        // Each file a fresh key; the store's files never in the clear.
        assertEquals(keys.keys.size, keys.keys.toSet().size)
        assertFalse(inTheClear(dir))
        s.discardRestore(staging)
        assertEquals(emptyList<String>(), staging.list()!!.toList())
        // Again: nothing to add, nothing changes.
        assertEquals(0, s.restoreIn(staging, back.entries, emptySet()) { it + back.extra })
        assertEquals(2, loaded(s).entries.size)
    }

    @Test fun neverStagedInTheStoresOwnFolder() {
        val s = store()
        s.prepareRestore()
        for (inside in listOf(dir, File(dir, "blobs"), File(dir, "incoming/voice"))) {
            assertTrue(runCatching { s.sealRestored(inside, id(1), ByteArrayInputStream(data(10, 1))) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { s.restoreIn(inside, emptyList(), emptySet()) { it } }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    /** Replace: this phone's files go; one here with the backup's id and bytes keeps its sealed bytes and thumbnail, its entry the backup's. */
    @Test fun replaceDeletesThisPhonesAndKeepsTheSameBytes() {
        val s = store()
        val same = data(5_000, 7)
        val kept = s.add(ByteArrayInputStream(same), FileIntake("mine.pdf", FileSource.SHARE))
        s.putThumb(kept.id, ByteArray(100) { 1 })
        s.update { i -> i.copy(entries = i.entries.map { it.copy(thumb = true) }) }
        val other = s.add(ByteArrayInputStream(data(3_000, 8)), FileIntake("other.pdf", FileSource.SHARE))
        val sealedBefore = File(dir, "blobs/${kept.id.hex}.rf").readBytes()
        s.prepareRestore()
        val d = data(9_000, 9)
        s.sealRestored(staging, id(9), ByteArrayInputStream(d))
        val add = listOf(entry(kept.id, same, "from the backup"), entry(id(9), d))
        assertEquals(1, s.restoreIn(staging, add, setOf(kept.id, other.id)) { it })
        val idx = loaded(s)
        assertEquals(listOf(kept.id, id(9)), idx.entries.map { it.id })
        assertEquals("from the backup", idx.entry(kept.id)!!.name)
        assertTrue(idx.entry(kept.id)!!.thumb)
        assertArrayEquals(sealedBefore, File(dir, "blobs/${kept.id.hex}.rf").readBytes())
        assertFalse(File(dir, "blobs/${other.id.hex}.rf").exists())
        assertArrayEquals(d, s.readAll(id(9), FileLimits.MAX_FILE))
    }

    /** Cut at every step, the store's recovery (:voice's start) and then the commit again: the same end, nothing in Inbox. */
    @Test fun aCommitCutAtEveryStepIsFinishedByRunningItAgain() {
        val steps = listOf(FileStep.KEYS_DESTROYED, FileStep.UNLINKED, FileStep.ENTRIES_REMOVED, FileStep.INDEX_SEALED, FileStep.INDEX_WRITTEN, FileStep.MOVED)
        for ((n, at) in steps.withIndex()) {
            val root = tmp.newFolder("cut$n")
            val d = File(root, "files")
            val st = File(root, "restore").apply { mkdirs() }
            val k = FakeFileKeys()
            fun s() = FileStore(d, k, "voice", space = { 100L * FileLimits.GIB })
            val first = s()
            val old = first.add(ByteArrayInputStream(data(4_000, 20 + n)), FileIntake("old.pdf", FileSource.SHARE))
            first.prepareRestore()
            val x = data(80_000, 30 + n)
            val y = data(10, 40 + n)
            first.sealRestored(st, id(1), ByteArrayInputStream(x))
            first.sealRestored(st, id(2), ByteArrayInputStream(y))
            val add = listOf(entry(id(1), x), entry(id(2), y))
            first.step = { if (it == at) throw Crash() }
            assertTrue(at.name, runCatching { first.restoreIn(st, add, setOf(old.id)) { it } }.exceptionOrNull() is Crash)
            FileStore.forgetInflight()
            // The next start: the store's recovery, then the journal's finish again.
            val again = s()
            val r = again.recover()
            assertEquals(at.name, 0, r.adopted)
            again.restoreIn(st, add, setOf(old.id)) { it }
            val idx = loaded(again)
            assertEquals(at.name, listOf(id(1), id(2)), idx.entries.map { it.id })
            assertTrue(at.name, idx.entries.none { it.from.source == FileSource.RECOVERED || it.state == FileState.INBOX })
            assertArrayEquals(at.name, x, again.readAll(id(1), FileLimits.MAX_FILE))
            assertArrayEquals(at.name, y, again.readAll(id(2), FileLimits.MAX_FILE))
            assertFalse(at.name, File(d, "blobs/${old.id.hex}.rf").exists())
            assertEquals(at.name, emptyList<String>(), st.list()!!.filter { it.endsWith(".rf") })
        }
    }

    @Test fun lockedGoneAndDamaged() {
        val s = store()
        s.add(ByteArrayInputStream(data(10, 1)), FileIntake("a.pdf", FileSource.SHARE))
        keys.refuse = FileKeyProblem.LOCKED
        s.drop()
        assertEquals(FileProblem.LOCKED, (runCatching { s.prepareRestore() }.exceptionOrNull() as FileException).problem)
        keys.refuse = null
        s.prepareRestore()
        // A part written when the key went is removed, nothing left behind.
        keys.refuse = FileKeyProblem.LOCKED
        assertEquals(FileProblem.LOCKED, (runCatching { s.sealRestored(staging, id(3), ByteArrayInputStream(data(10, 3))) }.exceptionOrNull() as FileException).problem)
        assertEquals(emptyList<String>(), staging.list()!!.toList())
        keys.refuse = null
        // Sealed entries that were changed don't open.
        s.sealRestoreIndex(File(staging, "entries.rf"), FileIndex(listOf(entry(id(4), data(1, 4)))))
        val f = File(staging, "entries.rf")
        f.writeBytes(f.readBytes().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() })
        assertEquals(FileProblem.DAMAGED, (runCatching { s.readRestoreIndex(f) }.exceptionOrNull() as FileException).problem)
        assertEquals(FileProblem.MISSING, (runCatching { s.readRestoreIndex(File(staging, "none.rf")) }.exceptionOrNull() as FileException).problem)
    }

    /** Its key gone and nothing sealed (Delete them all first): a restore starts a new cabinet, as an add would. */
    @Test fun afterDeleteThemAllANewKeyIsMade() {
        val s = store()
        s.add(ByteArrayInputStream(data(10, 1)), FileIntake("a.pdf", FileSource.SHARE))
        keys.master = null
        s.drop()
        assertEquals(IndexLoad.KeyGone, s.load())
        assertEquals(FileProblem.KEY_GONE, (runCatching { s.prepareRestore() }.exceptionOrNull() as FileException).problem)
        s.deleteAll()
        assertEquals(FileIndex(), s.prepareRestore())
        assertTrue(keys.master != null)
        assertNull((s.load() as IndexLoad.Loaded).index.entries.firstOrNull())
    }

    /**
     * FX3: a restored file gets the second look intake gives (FileSniff.Watch over all of it), not its first bytes alone: a
     * ZIP appended to a JPEG, or markup past a text's first 4 KiB, comes back as what it really is; a plain one as it is.
     */
    @Test fun aRestoredFileGetsIntakesSecondLook() {
        val s = store(FakeFileKeys(present = false))
        s.prepareRestore()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(9_000) { 7 }
        val zipEnd = byteArrayOf(0x50, 0x4B, 5, 6) + ByteArray(18)
        assertEquals(app.heyrobot.core.parse.FileType.JPEG, s.sealRestored(staging, id(1), ByteArrayInputStream(jpeg)).type)
        assertEquals(app.heyrobot.core.parse.FileType.ZIP, s.sealRestored(staging, id(2), ByteArrayInputStream(jpeg + zipEnd)).type)
        val text = "Shopping list\n".repeat(400).toByteArray()
        assertEquals(app.heyrobot.core.parse.FileType.TEXT, s.sealRestored(staging, id(3), ByteArrayInputStream(text)).type)
        assertEquals(app.heyrobot.core.parse.FileType.HTML, s.sealRestored(staging, id(4), ByteArrayInputStream(text + "<script>x()</script>".toByteArray())).type)
        // What the first bytes alone say, as before FX3.
        assertEquals(app.heyrobot.core.parse.FileType.JPEG, app.heyrobot.core.parse.FileSniff.sniff((jpeg + zipEnd).copyOf(4096), (jpeg.size + zipEnd.size).toLong()))
    }

    /** FX3: a cut or garbled entries.rf reads as DAMAGED (a restore gives it up), never as a storage error to wait out. */
    @Test fun aCutEntriesFileIsDamagedNotNotNow() {
        val s = store()
        s.prepareRestore()
        val a = data(10, 1)
        val f = File(staging, "entries.rf")
        s.sealRestoreIndex(f, FileIndex(listOf(entry(id(1), a))))
        val whole = f.readBytes()
        for (cut in listOf(whole.copyOf(whole.size - 7), whole.copyOf(40), whole.copyOf(whole.size).also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() })) {
            f.writeBytes(cut)
            assertEquals(FileProblem.DAMAGED, (runCatching { s.readRestoreIndex(f) }.exceptionOrNull() as FileException).problem)
        }
    }
}
