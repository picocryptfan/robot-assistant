// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.Note
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.NoteDelete
import app.heyrobot.core.NoteEnvelope
import app.heyrobot.core.SealedEnvelope
import java.io.File
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** seal-notes: Notes sealed at rest as History is, with the lock screen's notes merged at unlock. */
class NotesVaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val keys = SoftHistoryKeys()
    private var lockedNow = false
    private val dir by lazy { tmp.newFolder("notes") }
    private fun vault(crashAt: String? = null) = NotesVault(dir, keys, File(tmp.root, "notes.lock"), { lockedNow }) { if (it == crashAt) throw IllegalStateException("cut at $it") }
    private fun note(id: String, words: String, updated: Long = 2_000L) = Note(id, "", words, 1_000L, updated)
    private fun plain(n: Note) = File(dir, n.id + ".json").apply { writeText(NoteCodec.toJson(n)) }
    private fun names() = dir.list()!!.sorted()
    private fun noPlainWords(word: String) = dir.listFiles()!!.forEach { assertFalse(it.name, String(it.readBytes(), Charsets.ISO_8859_1).contains(word)) }
    /** The Keystore says "not now" (a KeyStoreException as the cause), as AndroidKeyStoreSpi does for a busy or locked key. */
    private fun notNow(alias: String) { keys.refuse[alias] = UnrecoverableKeyException("busy").apply { initCause(KeyStoreException("keystore")) } }

    /** merge-seal-124: 1.2.4's delete a note by voice, and its Undo, go through the sealed store: back whole, sealed, never plain. */
    @Test fun aVoiceDeleteAndItsUndoGoThroughTheSealedStore() {
        val v = vault()
        val n = v.add("gate code is on the fridge", "Gate", 3_000L)!!
        val undos = NoteDelete.Undos()
        val key = undos.delete(v, n)!!
        assertTrue(NoteDelete.isUndo(key))
        assertTrue(v.gone(n.id))
        assertTrue(undos.restore(v, key))
        assertEquals(n, vault().get(n.id))
        assertEquals(listOf("${n.id}.enc"), names())
        noPlainWords("fridge")
        assertFalse(undos.restore(v, key))
    }

    @Test fun aPlainNoteIsSealedReadBackAndRemoved() {
        plain(note("n1", "door code 4321"))
        assertEquals(listOf("door code 4321"), vault().list().map { it.text })
        assertEquals(listOf("n1.enc"), names())
        noPlainWords("4321")
        assertEquals("robot_notes_v1", NoteEnvelope.frame.alias(File(dir, "n1.enc").readBytes()))
    }

    @Test fun aCutAtEachStepLeavesOneWholeCopy() {
        for (at in listOf("seal", "written", "verified", "tmp")) {
            dir.listFiles()!!.forEach { it.delete() }
            plain(note("n1", "words $at"))
            if (at == "tmp") File(dir, ".n1.enc.tmp").writeBytes(byteArrayOf(1, 2, 3)) else vault(crashAt = at).list()
            // After the cut: the plain note is still whole, or the sealed one opens.
            val whole = NoteCodec.fromJson(File(dir, "n1.json").takeIf { it.isFile }?.readText() ?: "") != null ||
                (NoteEnvelope.open(File(dir, "n1.enc").readBytes(), { keys.cipher(it, false) }, "n1.enc") is app.heyrobot.core.SealedOpen.Ok)
            assertTrue(at, whole)
            assertEquals(at, listOf("words $at"), vault().list().map { it.text })
            assertEquals(at, listOf("n1.enc"), names())
        }
    }

    @Test fun aNoteTakenWhileLockedIsSealedUnderTheLockKeyAndMergedAtUnlock() {
        lockedNow = true
        val v = vault()
        val n = v.add("park on level 3", null, 5_000L)!!
        assertEquals(listOf("${n.id}.locked.enc"), names())
        assertEquals(NoteKeyAliases.LOCK_SCREEN, NoteEnvelope.frame.alias(File(dir, "${n.id}.locked.enc").readBytes()))
        noPlainWords("level 3")
        // Over the lock screen nothing is listed, the sealed notes can't be opened and their words never shown.
        assertTrue(v.list().isEmpty())
        lockedNow = false
        v.open()
        lockedNow = true
        assertEquals(NotesRead(emptyList(), notNow = 1), v.read())
        lockedNow = false
        v.open()
        assertEquals(listOf("${n.id}.enc"), names())
        assertEquals(listOf("park on level 3"), v.list().map { it.text })
    }

    @Test fun aMergeCutAfterItsWriteLeavesOneNote() {
        lockedNow = true
        val n = vault().add("milk", null, 5_000L)!!
        lockedNow = false
        try { vault(crashAt = "merged").open() } catch (_: Exception) {}
        assertEquals(listOf("${n.id}.enc", "${n.id}.locked.enc"), names())
        assertEquals(listOf("milk"), vault().list().map { it.text })
        assertEquals(listOf("${n.id}.enc"), names())
    }

    @Test fun anOlderJournalNeverReplacesANewerNote() {
        val v = vault()
        v.save(note("n1", "new words", 9_000L))
        lockedNow = true
        v.save(note("n1", "old words", 3_000L))
        lockedNow = false
        assertEquals(listOf("new words"), v.list().map { it.text })
        assertEquals(listOf("n1.enc"), names())
    }

    @Test fun theUnlockKeyNotUsableNowSendsTheSaveToTheJournalStillListed() {
        notNow(NoteKeyAliases.UNLOCKED)
        val v = vault()
        assertTrue(v.save(note("n1", "first")))
        assertEquals(listOf("n1.locked.enc"), names())
        // Never written where the list can't show it.
        assertEquals(listOf("first"), v.list().map { it.text })
        assertEquals(listOf("first"), v.whole().map { it.text })
        keys.refuse.clear()
        assertEquals(listOf("first"), v.list().map { it.text })
        assertEquals(listOf("n1.enc"), names())
    }

    @Test fun aNoteTheKeyCantOpenNowFailsWholeAndIsNeverKeptAside() {
        val v = vault()
        v.save(note("n1", "first"))
        v.drop()
        notNow(NoteKeyAliases.UNLOCKED)
        val r = v.read()
        assertEquals(1, r.notNow)
        assertEquals(0, r.aside)
        try { v.whole(); fail("whole") } catch (e: NotesNotWhole) { assertEquals(1, e.notNow) }
        assertEquals(listOf("n1.enc"), names())
    }

    @Test fun aKeyThatIsGoneKeepsItsFilesAsideAndANewKeyIsMade() {
        val v = vault()
        v.save(note("n1", "first"))
        v.drop()
        keys.gone += NoteKeyAliases.UNLOCKED
        assertEquals(1, v.read().aside)
        assertTrue(File(dir, "n1.enc.bad").isFile)
        assertTrue(v.save(note("n2", "second", 3_000L)))
        assertEquals(listOf("second"), v.list().map { it.text })
        assertEquals(listOf("n1.enc.bad", "n2.enc"), names())
    }

    @Test fun androidsCauselessInvalidatedKeyIsGoneNotNotNow() {
        val v = vault()
        v.save(note("n1", "first"))
        v.drop()
        keys.refuse[NoteKeyAliases.UNLOCKED] = UnrecoverableKeyException("User changed or deleted their auth credentials")
        val r = v.read()
        assertEquals(1, r.aside)
        assertEquals(0, r.notNow)
        assertTrue(v.save(note("n2", "second", 3_000L)))
        assertEquals(listOf("n1.enc.bad", "n2.enc"), names())
    }

    @Test fun aNewerRobotsNoteIsCountedLeftInPlaceAndFailsWhole() {
        val c = keys.cipher(NoteKeyAliases.UNLOCKED, true)!!
        File(dir, "n1.enc").writeBytes(SealedEnvelope("RNOT", "notes", 2).seal(NoteCodec.toJson(note("n1", "x")).toByteArray(), c, "n1.enc"))
        val v = vault()
        assertEquals(1, v.read().tooNew)
        try { v.whole(); fail("whole") } catch (e: NotesNotWhole) { assertEquals(1, e.tooNew) }
        assertEquals(listOf("n1.enc"), names())
    }

    @Test fun deleteTheseReachesANewerRobotsNoteSoBackupsAreWholeAgain() {
        val c = keys.cipher(NoteKeyAliases.UNLOCKED, true)!!
        File(dir, "n1.enc").writeBytes(SealedEnvelope("RNOT", "notes", 2).seal(NoteCodec.toJson(note("n1", "x")).toByteArray(), c, "n1.enc"))
        val v = vault()
        assertTrue(v.save(note("n2", "mine", 3_000L)))
        v.deleteAside()
        assertEquals(listOf("n2.enc"), names())
        assertEquals(listOf("n2"), v.whole().map { it.id })
    }

    // seal-notes-c 6: Delete these opens only the files a newer Robot wrote, not every note under the Keystore.
    @Test fun deleteTheseOpensOnlyANewerRobotsFiles() {
        val c = keys.cipher(NoteKeyAliases.UNLOCKED, true)!!
        File(dir, "n1.enc").writeBytes(SealedEnvelope("RNOT", "notes", 2).seal(NoteCodec.toJson(note("n1", "x")).toByteArray(), c, "n1.enc"))
        val v = vault()
        for (i in 2..5) assertTrue(v.save(note("n$i", "mine", 3_000L)))
        keys.opens = 0
        v.deleteAside()
        assertTrue("opens: ${keys.opens}", keys.opens <= 1)
        assertEquals((2..5).map { "n$it.enc" }, names())
    }

    // seal-small 3: the editor tells a newer Robot's note from one that can't be opened just now.
    @Test fun tooNewIsOnlyANewerRobotsNoteAndNeverWhileLocked() {
        val c = keys.cipher(NoteKeyAliases.UNLOCKED, true)!!
        File(dir, "n1.enc").writeBytes(SealedEnvelope("RNOT", "notes", 2).seal(NoteCodec.toJson(note("n1", "x")).toByteArray(), c, "n1.enc"))
        val v = vault()
        assertTrue(v.save(note("n2", "mine")))
        assertNull(v.get("n1"))
        assertTrue(v.tooNew("n1"))
        assertFalse(v.tooNew("n2"))
        assertFalse(v.tooNew("n3"))
        lockedNow = true
        assertFalse(v.tooNew("n1"))
    }

    @Test fun theEditorsSaveWhileLockedWritesNothingAndGetOpensNoCachedNote() {
        val v = vault()
        assertTrue(v.save(note("n1", "first")))
        assertEquals("first", v.get("n1")!!.text) // cached
        lockedNow = true
        assertNull(v.get("n1"))
        assertFalse(v.saveUnlocked(note("n1", "typed as it locked", 3_000L)))
        assertEquals(listOf("n1.enc"), names()) // nothing under the lock-screen key
        assertFalse(v.gone("n1"))
        lockedNow = false
        assertTrue(v.saveUnlocked(note("n1", "typed as it locked", 3_000L)))
        assertEquals("typed as it locked", v.get("n1")!!.text)
    }

    // seal-notes-c 3: the editor's save checks and writes under one lock: a note deleted underneath is never written back.
    @Test fun theEditorsSaveOfANoteDeletedUnderneathWritesNothing() {
        val v = vault()
        assertTrue(v.saveUnlocked(note("n1", "first")))
        assertEquals(true, v.saveIfThere(note("n1", "second", 3_000L)))
        assertEquals("second", v.get("n1")!!.text)
        assertTrue(v.delete("n1"))
        assertNull(v.saveIfThere(note("n1", "third", 4_000L)))
        assertTrue(v.gone("n1"))
        lockedNow = true
        assertTrue(v.save(note("n2", "over the lock")))
        assertEquals(false, v.saveIfThere(note("n2", "typed as it locked", 3_000L))) // there, but not written while locked
    }

    @Test fun aNoteThatCantBeOpenedNowIsNotGone() {
        val c = keys.cipher(NoteKeyAliases.UNLOCKED, true)!!
        File(dir, "n1.enc").writeBytes(SealedEnvelope("RNOT", "notes", 2).seal(NoteCodec.toJson(note("n1", "x")).toByteArray(), c, "n1.enc"))
        val v = vault()
        assertNull(v.get("n1"))
        assertFalse(v.gone("n1"))
        assertTrue(v.delete("n1"))
        assertTrue(v.gone("n1"))
    }

    @Test fun aNoteRenamedToAnotherIdIsDamageAndAnUnparseablePlainNoteIsKeptAside() {
        val v = vault()
        v.save(note("n1", "first"))
        File(dir, "n1.enc").renameTo(File(dir, "n2.enc"))
        File(dir, "n3.json").writeText("{not json")
        val r = vault().read()
        assertTrue(r.notes.isEmpty())
        assertEquals(2, r.aside)
        assertEquals(listOf("n2.enc.bad", "n3.json.bad"), names())
    }

    @Test fun deleteTakesEveryCopy() {
        val v = vault()
        v.save(note("n1", "sealed"))
        plain(note("n1", "plain", 1_500L))
        lockedNow = true
        v.save(note("n1", "journal", 9_000L))
        assertTrue(v.delete("n1"))
        lockedNow = false
        assertEquals(emptyList<String>(), names())
        assertNull(v.get("n1"))
        assertFalse(v.delete("n1"))
    }

    @Test fun aRestoredNoteIsStagedSealedAndMovedIn() {
        val v = vault()
        val staging = tmp.newFolder("staging")
        File(staging, "n1.enc").writeBytes(v.sealForRestore(note("n1", "restored 77")))
        assertFalse(String(File(staging, "n1.enc").readBytes(), Charsets.ISO_8859_1).contains("77"))
        v.moveIn(staging)
        assertEquals("restored 77", v.get("n1")?.text)
        assertEquals(listOf("n1.enc", "n1.json", "n1.locked.enc"), v.namesOf("n1").sorted())
    }

    @Test fun nothingOutsideTheStoreOpensTheNotesFolder() {
        val allowed = setOf("NotesVault.kt")
        val bad = File("src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name !in allowed }
            .filter { f -> f.readText().let { it.contains("NotesFiles.dir") || it.contains("NoteStore(") || Regex("""File\([^)]*"notes"\)""").containsMatchIn(it) } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun aFailedReadCountsTheNoteFiles() {
        val v = vault()
        v.save(note("n1", "first"))
        v.save(note("n2", "second"))
        plain(note("n3", "third"))
        val notAFolder = tmp.newFile("in-the-way")
        assertEquals(3, NotesVault(dir, keys, File(notAFolder, "notes.lock"), { lockedNow }).read().notNow)
        assertEquals(1, NotesVault(tmp.newFolder("none"), keys, File(notAFolder, "notes.lock"), { lockedNow }).read().notNow)
    }

    @Test fun aFailedReadIsHeldNotAnEmptyNotes() {
        val notAFolder = tmp.newFile("in-the-way")
        val r = NotesVault(dir, keys, File(notAFolder, "notes.lock"), { lockedNow }).read()
        assertTrue(r.notes.isEmpty())
        assertTrue(r.notNow > 0)
        assertEquals(app.heyrobot.core.NoteHeld.NOT_NOW, TodaySource.noteHeld(r, null))
        assertNull(TodaySource.quickAdd(r, null, "Row F", 9))
    }
}
