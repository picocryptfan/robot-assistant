// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryEnvelope
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryKeyException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lists-plan.md §14's ListsEnvelopeTest, with a real AES-GCM cipher: the round trip; a changed byte, header or alias; a
 * memory file or the journal offered as lists.enc (and the other way round); the damaged, newer, key-gone and locked
 * states; and that update writes nothing over a file it couldn't read.
 */
class ListsEnvelopeTest {
    private val key = SoftCipher("test_lists_key")
    private val other = SoftCipher("test_lists_lock")
    private val keys = mapOf(key.alias to key, other.alias to other)
    private val cipherFor: (String) -> MemoryCipher? = { keys[it] }
    private val ids = CountingIds()

    private val file: ListsFile = ListsEdits.firstUse(1_761_150_000_000, ids).let { f ->
        (ListsEdits.add(f, f.lists[0].id, listOf(NewItem("oat milk", "2 L"), NewItem("eggs")), ListsWho(ItemBy.YOU, ItemHow.VOICE, 1_761_150_000_001), ids) as ListsEdit.Done).file
    }

    private fun flip(b: ByteArray, at: Int) = b.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    @Test fun roundTrip() {
        val bytes = ListsEnvelope.seal(file, key)
        assertArrayEquals("RLST".toByteArray() + byteArrayOf(1, key.alias.length.toByte()) + key.alias.toByteArray(), bytes.copyOfRange(0, 6 + key.alias.length))
        assertEquals(key.alias, ListsEnvelope.alias(bytes))
        assertEquals(ListsLoad.Loaded(file), ListsEnvelope.open(bytes, cipherFor))
        // The Keystore's IV is random: the same lists sealed twice differ, and both open.
        val again = ListsEnvelope.seal(file, key)
        assertFalse(bytes.contentEquals(again))
        assertEquals(ListsLoad.Loaded(file), ListsEnvelope.open(again, cipherFor))
        // Nothing of the lists is in the bytes.
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("oat milk"))
        // Sealed under the other key, it opens with that one (the lock-screen switch's re-key, LS2).
        assertEquals(ListsLoad.Loaded(file), ListsEnvelope.open(ListsEnvelope.seal(file, other), cipherFor))
    }

    @Test fun aChangedByteHeaderOrAlias() {
        val bytes = ListsEnvelope.seal(file, key)
        val body = 6 + key.alias.length
        // Every byte after the header (IV, ciphertext, tag) is authenticated.
        for (at in listOf(body, body + 11, body + 12, bytes.size / 2, bytes.size - 16, bytes.size - 1)) {
            assertEquals("byte $at", ListsLoad.Unreadable, ListsEnvelope.open(flip(bytes, at), cipherFor))
        }
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(bytes.copyOf(bytes.size - 1), cipherFor))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(bytes.copyOf(body + 12), cipherFor))
        // The magic.
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(flip(bytes, 0), cipherFor))
        // The version: a newer frame is never read (and so never written over); 0 isn't one.
        assertEquals(ListsLoad.TooNew(2), ListsEnvelope.open(bytes.copyOf().also { it[4] = 2 }, cipherFor))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(bytes.copyOf().also { it[4] = 0 }, cipherFor))
        // The alias: another key's name asks for that key, which can't open it; a name no key has is a key gone.
        val swapped = ListsEnvelope.seal(file, key).let { b -> "RLST".toByteArray() + byteArrayOf(1, other.alias.length.toByte()) + other.alias.toByteArray() + b.copyOfRange(body, b.size) }
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(swapped, cipherFor))
        assertEquals(ListsLoad.KeyGone, ListsEnvelope.open(flip(bytes, 6), cipherFor))
        // A broken length.
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(bytes.copyOf().also { it[5] = 0 }, cipherFor))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(bytes.copyOf().also { it[5] = 65 }, cipherFor))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(bytes.copyOf(5), cipherFor))
    }

    @Test fun memorysFileAndTheJournalAreNotLists() {
        // memory.enc offered as lists.enc: another magic.
        val memory = MemoryEnvelope.seal(MemoryFile(), key)
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(memory, cipherFor))
        // Even with Lists' magic put on it, its additional data says memory: the tag fails.
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open("RLST".toByteArray() + memory.copyOfRange(4, memory.size), cipherFor))
        // The journal offered as lists.enc, and lists.enc as the journal: another magic, and another file name in the additional data.
        val journal = ListsEnvelope.sealJournal(ListsJournal.snapshot(file), other)
        assertEquals(other.alias, ListsEnvelope.journalAlias(journal))
        assertNull(ListsEnvelope.alias(journal))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(journal, cipherFor))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open("RLST".toByteArray() + journal.copyOfRange(4, journal.size), cipherFor))
        val lists = ListsEnvelope.seal(file, other)
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.openJournal(lists, cipherFor))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.openJournal("RLSJ".toByteArray() + lists.copyOfRange(4, lists.size), cipherFor))
        assertEquals(ListsLoad.Loaded(ListsJournal.snapshot(file)), ListsEnvelope.openJournal(journal, cipherFor))
        // And lists.enc isn't memory's either.
        assertEquals(app.heyrobot.core.memory.MemoryLoad.Unreadable, MemoryEnvelope.open(ListsEnvelope.seal(file, key), { key }))
        assertArrayEquals("robot-lists-v1|lists.enc".toByteArray(), ListsEnvelope.aad(ListsEnvelope.FILE))
        assertArrayEquals("robot-lists-v1|locked.enc".toByteArray(), ListsEnvelope.aad(ListsEnvelope.JOURNAL))
    }

    @Test fun theStates() {
        val bytes = ListsEnvelope.seal(file, key)
        assertEquals(ListsLoad.Empty, ListsEnvelope.open(null, cipherFor))
        assertEquals(ListsLoad.Empty, ListsEnvelope.open(ByteArray(0), cipherFor))
        assertEquals(ListsLoad.KeyGone, ListsEnvelope.open(bytes) { null })
        assertEquals(ListsLoad.Locked, ListsEnvelope.open(bytes) { throw MemoryKeyException(KeyProblem.LOCKED) })
        key.problem = KeyProblem.LOCKED
        assertEquals(ListsLoad.Locked, ListsEnvelope.open(bytes, cipherFor))
        key.problem = KeyProblem.GONE
        assertEquals(ListsLoad.KeyGone, ListsEnvelope.open(bytes, cipherFor))
        key.problem = null
        assertTrue(ListsEnvelope.open(bytes, cipherFor).writable)
        // A frame that opens but holds something else: damaged; a newer lists file inside: too new.
        fun inner(json: String): ByteArray {
            val body = key.seal(json.toByteArray(), ListsEnvelope.aad(ListsEnvelope.FILE))
            return "RLST".toByteArray() + byteArrayOf(1, key.alias.length.toByte()) + key.alias.toByteArray() + body
        }
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(inner("not json"), cipherFor))
        assertEquals(ListsLoad.Unreadable, ListsEnvelope.open(inner("{\"v\":1,\"lists\":[{}]}"), cipherFor))
        assertEquals(ListsLoad.TooNew(3), ListsEnvelope.open(inner("{\"v\":3,\"lists\":[]}"), cipherFor))
        for (s in listOf(ListsLoad.Locked, ListsLoad.KeyGone, ListsLoad.Unreadable, ListsLoad.TooNew(2))) assertFalse(s.writable)
    }

    /** Never written over a file Robot couldn't read; nothing written when the change is refused, except the first use's lists. */
    @Test fun updateWritesOnlyOverAFileItRead() {
        val bytes = ListsEnvelope.seal(file, key)
        val tick: (ListsFile) -> ListsFile? = { f ->
            (ListsEdits.tick(f, f.lists[0].id, f.lists[0].items[0].id, ListsWho(ItemBy.YOU, ItemHow.CARD, 1_761_150_000_009)) as ListsEdit.Done).file
        }
        val (load, out) = ListsEnvelope.update(bytes, key, cipherFor, { error("not first use") }, tick)
        assertTrue((load as ListsLoad.Loaded).value.lists[0].items[0].done)
        assertEquals(load, ListsEnvelope.open(out, cipherFor))
        for (bad in listOf(flip(bytes, bytes.size - 1), bytes.copyOf().also { it[4] = 2 })) {
            assertNull(ListsEnvelope.update(bad, key, cipherFor, { ListsFile() }, tick).second)
        }
        key.problem = KeyProblem.LOCKED
        assertEquals(ListsLoad.Locked to null, ListsEnvelope.update(bytes, other, cipherFor, { ListsFile() }, tick))
        key.problem = null
        assertEquals(ListsLoad.KeyGone to null, ListsEnvelope.update(bytes, key, { null }, { ListsFile() }, tick))
        // A refused change writes nothing.
        assertNull(ListsEnvelope.update(bytes, key, cipherFor, { ListsFile() }) { null }.second)
        // No file yet: the first use's lists, written even when the change is refused, so their ids stay.
        val first = ListsEdits.firstUse(5, CountingIds(0x9000))
        val (l0, b0) = ListsEnvelope.update(null, key, cipherFor, { first }) { null }
        assertEquals(ListsLoad.Loaded(first), l0)
        assertEquals(ListsLoad.Loaded(first), ListsEnvelope.open(b0, cipherFor))
        // Once a file exists, even with no lists left in it, the defaults never come back.
        val none = ListsEnvelope.seal(ListsFile(), key)
        val (l1, _) = ListsEnvelope.update(none, key, cipherFor, { first }) { it }
        assertEquals(ListsLoad.Loaded(ListsFile()), l1)
    }
}
