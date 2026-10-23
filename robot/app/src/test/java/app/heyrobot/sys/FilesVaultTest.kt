// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileKeyException
import app.heyrobot.core.files.FileKeyProblem
import app.heyrobot.core.files.FileProblem
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.files.FileUsage
import app.heyrobot.core.files.IndexLoad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * files-1.4-plan.md §2.4 and D2, the app's vault on a real folder with robot_files_v1 in software (KeystoreFileKeys over
 * [SoftFileKeystore], so the wrap and unwrap are the phone's code): nothing made on disk until a file comes; the lock gate
 * (a locked phone gets Locked, never the index kept in RAM); the copy in RAM dropped and read again; the key gone;
 * Delete them all refused while locked and done after; recovery at a start while locked and again after the unlock.
 */
class FilesVaultTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val keystore = SoftFileKeystore()
    private var locked = false
    private lateinit var dir: File

    private fun vault(process: String = "main"): FilesVault {
        if (!::dir.isInitialized) dir = File(tmp.root, "files")
        return FilesVault(dir, KeystoreFileKeys(keystore), process, space = { 100L shl 30 }, locked = { locked })
    }

    /** What :voice does when a file comes (F5): its own store on the same folder, the same key. */
    private fun addFromVoice(bytes: ByteArray, name: String = "receipt.pdf") =
        FileStore(dir, KeystoreFileKeys(keystore), "voice", space = { 100L shl 30 }).add(ByteArrayInputStream(bytes), FileIntake(name, FileSource.SHARE))

    private fun lock(on: Boolean) {
        locked = on
        keystore.locked = on
    }

    @Test fun nothingIsMadeOnDiskUntilAFileComes() {
        val v = vault()
        assertEquals(IndexLoad.Empty, v.load())
        assertEquals(FileUsage(0, 0), v.usage())
        assertNull(v.recover())
        assertNull(v.startOver())
        assertTrue(v.deleteAll())
        assertFalse(v.exists())
        assertFalse(dir.exists())
        assertEquals(0, keystore.generated)
    }

    @Test fun theOtherProcesssFileIsReadThroughTheKey() {
        val v = vault()
        val e = addFromVoice(ByteArray(5_000) { it.toByte() })
        val index = (v.load() as IndexLoad.Loaded).index
        assertEquals(listOf(e.id), index.entries.map { it.id })
        assertEquals(1, v.usage().files)
        // The index's header holds a 60-byte wrap made by the real wrap code.
        assertTrue(File(dir, FileStore.INDEX).length() > 96)
    }

    @Test fun aLockedPhoneGetsLockedNeverTheIndexInRam() {
        val v = vault()
        addFromVoice(ByteArray(10))
        assertTrue(v.load() is IndexLoad.Loaded)
        // Locked: the copy in RAM is dropped and nothing is read, whether or not the key would answer.
        locked = true
        assertEquals(IndexLoad.Locked, v.load())
        assertEquals(IndexLoad.Locked, v.update { it })
        assertNull(v.startOver())
        assertFalse(v.deleteAll())
        // Unlocked again, while the key still can't answer (the Keystore busy): read from disk, so Locked too, not RAM.
        locked = false
        keystore.locked = true
        assertEquals(IndexLoad.Locked, v.load())
        keystore.locked = false
        assertTrue(v.load() is IndexLoad.Loaded)
        // What the files take is counted with no key at all.
        lock(true)
        assertEquals(1, v.usage().files)
    }

    @Test fun droppedFromRamAndReadAgain() {
        val v = vault()
        addFromVoice(ByteArray(10))
        assertTrue(v.load() is IndexLoad.Loaded)
        // In RAM: the key isn't asked again while the index on disk is the same.
        keystore.locked = true
        assertTrue(v.load() is IndexLoad.Loaded)
        // Screen-off: dropped, so the next read needs the key.
        v.drop()
        assertEquals(IndexLoad.Locked, v.load())
        keystore.locked = false
        assertTrue(v.load() is IndexLoad.Loaded)
    }

    @Test fun theKeyGoneIsSaidAndEveryFileKept() {
        val v = vault()
        addFromVoice(ByteArray(10))
        addFromVoice(ByteArray(20))
        assertTrue(v.load() is IndexLoad.Loaded)
        keystore.keys.clear()
        v.drop()
        assertEquals(IndexLoad.KeyGone, v.load())
        assertEquals(FileUsage(2, v.usage().bytes), v.usage())
        // Recovery never throws a file away for it, and makes no key.
        val r = v.recover()!!
        assertEquals(IndexLoad.KeyGone, r.load)
        assertEquals(2, v.usage().files)
        assertEquals(0, keystore.generated)
        // A new file can't be added over a lost key: it would need a new key, and the old files theirs.
        assertEquals(FileProblem.KEY_GONE, try { addFromVoice(ByteArray(1)); null } catch (x: FileException) { x.problem })
        assertEquals(0, keystore.generated)
    }

    @Test fun deleteThemAllIsRefusedWhileLockedThenTakesEverythingAndTheKey() {
        val v = vault()
        addFromVoice(ByteArray(10))
        lock(true)
        assertFalse(v.deleteAll())
        assertEquals(1, v.usage().files)
        lock(false)
        assertTrue(v.deleteAll())
        assertEquals(FileUsage(0, 0), v.usage())
        assertEquals(IndexLoad.Empty, v.load())
        assertEquals(listOf(KeystoreFileKeys.ALIAS), keystore.deleted)
        assertFalse(keystore.contains(KeystoreFileKeys.ALIAS))
        // Only robot_files_v1: memory's keys and every other alias are left alone.
        assertTrue(keystore.deleted.none { it != KeystoreFileKeys.ALIAS })
    }

    /**
     * F7: a delete you confirmed (its tap refused while locked) still happens when the phone locks during its 10-second
     * Undo, as on the phone: the vault locked and robot_files_v1 refusing too. The file's wrapped keys are destroyed and
     * its bytes go at once; the entry, which needs the key to rewrite the index, stays hidden by the journal and leaves
     * the index after the unlock. The other files are untouched.
     */
    @Test fun aConfirmedDeleteGoesThroughWhileLocked() {
        val v = vault()
        val gone = addFromVoice(ByteArray(3_000) { 7 }, "a.pdf")
        val kept = addFromVoice(ByteArray(2_000) { 9 }, "b.pdf")
        assertTrue(v.load() is IndexLoad.Loaded)
        lock(true)
        val d = v.delete(listOf(gone.id))!!
        assertEquals(1, d.removed)
        assertTrue(d.waiting)
        assertEquals(1, v.usage().files)
        lock(false)
        // Nothing of it can be opened, and it is in no list, before the recovery and after.
        assertEquals(FileProblem.MISSING, (runCatching { v.open(gone.id) }.exceptionOrNull() as FileException).problem)
        assertEquals(listOf(kept.id), (v.load() as IndexLoad.Loaded).index.entries.map { it.id })
        assertFalse(v.recover()!!.waiting)
        assertEquals(listOf(kept.id), (v.load() as IndexLoad.Loaded).index.entries.map { it.id })
        assertArrayEquals(ByteArray(2_000) { 9 }, v.open(kept.id).use { it.readAll(10_000) })
    }

    @Test fun recoveryAtALockedStartWaitsForTheUnlock() {
        val v = vault()
        addFromVoice(ByteArray(10))
        assertTrue(v.load() is IndexLoad.Loaded)
        lock(true)
        val r = v.recover()!!
        assertEquals(IndexLoad.Locked, r.load)
        assertTrue(r.waiting)
        lock(false)
        val after = v.recover()!!
        assertFalse(after.waiting)
        assertTrue(after.load is IndexLoad.Loaded)
    }

    @Test fun theRealWrapIsSixtyBytesFreshEachTimeAndBoundToItsData() {
        val k = KeystoreFileKeys(keystore)
        val key = ByteArray(32) { it.toByte() }
        val a = k.wrap(key, "robot-files-v1|1|aa".toByteArray(), create = false)
        val b = k.wrap(key, "robot-files-v1|1|aa".toByteArray(), create = false)
        assertEquals(60, a.size)
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(key, k.unwrap(a, "robot-files-v1|1|aa".toByteArray()))
        assertEquals(FileKeyProblem.BAD_DATA, problem { k.unwrap(a, "robot-files-v1|1|ab".toByteArray()) })
        assertEquals(FileKeyProblem.BAD_DATA, problem { k.unwrap(a.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }, "robot-files-v1|1|aa".toByteArray()) })
        assertEquals(FileKeyProblem.BAD_DATA, problem { k.unwrap(a.copyOf(59), "robot-files-v1|1|aa".toByteArray()) })
        // Another phone's key (another robot_files_v1): its wraps don't open here.
        assertEquals(FileKeyProblem.BAD_DATA, problem { KeystoreFileKeys(SoftFileKeystore()).unwrap(a, "robot-files-v1|1|aa".toByteArray()) })
        // No key: gone, for a wrap that may not make one and for every unwrap.
        keystore.keys.clear()
        assertEquals(FileKeyProblem.GONE, problem { k.wrap(key, ByteArray(1), create = false) })
        assertEquals(FileKeyProblem.GONE, problem { k.unwrap(a, "robot-files-v1|1|aa".toByteArray()) })
        assertEquals(0, keystore.generated)
    }

    /** F5's calls: stage, commit, discard, a duplicate, open, a thumbnail, all through the lock gate. */
    @Test fun addingAndReadingGoThroughTheGate() {
        val v = vault()
        val pdf = "%PDF-1.7 receipt".toByteArray()
        val staged = v.stage(ByteArrayInputStream(pdf), pdf.size.toLong())
        assertNull(v.duplicateOf(staged.sha256))
        val e = v.commit(staged, FileIntake("receipt.pdf", FileSource.SHARE, type = "application/pdf"))
        assertEquals(e, v.duplicateOf(staged.sha256))
        assertArrayEquals(pdf, v.open(e.id).use { it.readAll(1_000) })
        assertNull(v.thumb(e.id))
        v.putThumb(e.id, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), v.thumb(e.id))
        // Locked: every call refused (and the copy in RAM dropped), nothing said about a duplicate.
        lock(true)
        assertEquals(FileProblem.LOCKED, fileProblem { v.stage(ByteArrayInputStream(pdf)) })
        assertEquals(FileProblem.LOCKED, fileProblem { v.open(e.id) })
        assertEquals(FileProblem.LOCKED, fileProblem { v.thumb(e.id) })
        assertEquals(FileProblem.LOCKED, fileProblem { v.putThumb(e.id, byteArrayOf(1)) })
        assertNull(v.duplicateOf(staged.sha256))
        lock(false)
    }

    /** A staged file whose commit meets a locked phone is discarded with it: nothing lands in Inbox at the next start. */
    @Test fun aCommitRefusedWhileLockedLeavesNothingBehind() {
        val v = vault()
        val staged = v.stage(ByteArrayInputStream(ByteArray(500) { 3 }))
        lock(true)
        assertEquals(FileProblem.LOCKED, fileProblem { v.commit(staged, FileIntake("a.bin", FileSource.SHARE)) })
        lock(false)
        assertEquals(0, v.recover()!!.adopted)
        assertEquals(0, (v.load() as IndexLoad.Loaded).index.entries.size)
        // Discarding needs no key: it works while locked too.
        val other = v.stage(ByteArrayInputStream(ByteArray(500) { 4 }))
        lock(true)
        v.discard(other)
        lock(false)
        assertEquals(0, v.recover()!!.adopted)
        assertTrue(File(dir, "incoming/main").listFiles().orEmpty().isEmpty())
    }

    private fun fileProblem(block: () -> Unit): FileProblem? = try {
        block()
        null
    } catch (x: FileException) {
        x.problem
    }

    private fun problem(block: () -> Unit): FileKeyProblem? = try {
        block()
        null
    } catch (x: FileKeyException) {
        x.problem
    }
}
