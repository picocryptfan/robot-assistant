package app.heyrobot.core.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MemoryCodecTest {
    /**
     * A stand-in for the Keystore AES-GCM cipher: a random 12-byte IV, the bytes XORed with a key stream, and an 8-byte
     * tag over the additional data and the plain text. Enough to check the frame, tampering and the error paths.
     */
    private class FakeCipher(override val alias: String = "robot_memory_v1", private val key: Int = 0x5A) : MemoryCipher {
        var locked = false
        var gone = false

        private fun tag(aad: ByteArray, plain: ByteArray): ByteArray {
            var h = 1469598103934665603L xor key.toLong()
            for (b in aad + plain) { h = h xor (b.toLong() and 0xFF); h *= 1099511628211L }
            return ByteArray(8) { (h ushr (8 * it)).toByte() }
        }

        override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = Random.nextBytes(12)
            val body = ByteArray(plain.size) { (plain[it].toInt() xor key xor iv[it % 12].toInt()).toByte() }
            return iv + body + tag(aad, plain)
        }

        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
            if (locked) throw MemoryKeyException(KeyProblem.LOCKED)
            if (gone) throw MemoryKeyException(KeyProblem.GONE)
            if (sealed.size < 20) throw MemoryKeyException(KeyProblem.BAD_DATA)
            val iv = sealed.copyOfRange(0, 12)
            val body = sealed.copyOfRange(12, sealed.size - 8)
            val plain = ByteArray(body.size) { (body[it].toInt() xor key xor iv[it % 12].toInt()).toByte() }
            if (!tag(aad, plain).contentEquals(sealed.copyOfRange(sealed.size - 8, sealed.size))) throw MemoryKeyException(KeyProblem.BAD_DATA)
            return plain
        }
    }

    private val t0 = 1_760_000_000_000L
    private fun item(n: Int, text: String = "Item number $n", kind: MemoryKind = MemoryKind.FACT, pinned: Boolean = false) =
        MemoryItem(MemoryIds.newId(t0 + n), text, kind, pinned = pinned, createdAt = t0 + n)

    private val full = MemoryFile(
        items = listOf(
            MemoryItem("m_00000000aaaaaa", "My sister Julie's birthday is May 3", source = MemorySource.SAID, language = "en",
                about = listOf("r:1234"), createdAt = t0, updatedAt = t0 + 5, uses = 3, usedAt = t0 + 9),
            MemoryItem("m_00000000bbbbbb", "Call me Sam", MemoryKind.PREFERENCE, pinned = true, source = MemorySource.TYPED, createdAt = t0 + 1),
            MemoryItem("m_00000000cccccc", "The door code is 4512", secret = true, source = MemorySource.SETTINGS, createdAt = t0 + 2),
            MemoryItem("m_00000000dddddd", "Dinner at Mom's Sunday 6 pm", source = MemorySource.CONTENT, sourceLabel = "a message from Mom",
                language = "fr", createdAt = t0 + 3, expiresAt = t0 + 100_000),
        ),
        suggestions = listOf(MemorySuggestion("s_00000000eeeeee", "Léo has hockey on Tuesdays", MemoryKind.FACT, t0 + 4)),
        negatives = listOf("I had pizza"),
    )

    @Test fun jsonRoundTrips() {
        val p = MemoryCodec.read(MemoryCodec.toJson(full))
        assertEquals(MemoryParse.Ok(full, 0), p)
        assertEquals(MemoryParse.Ok(MemoryFile()), MemoryCodec.read(null))
        assertEquals(MemoryParse.Ok(MemoryFile()), MemoryCodec.read("  "))
    }

    @Test fun damagedEntriesAreDroppedAndUnknownsReadSafely() {
        val json = """{"v":1,"items":[
            {"id":"../etc","text":"bad id"},
            {"id":"m_00000000aaaaaa","text":""},
            {"id":"m_00000000bbbbbb","text":"kept","kind":"SOMETHING","source":"FROM_THE_FUTURE","pinned":true,"secret":true},
            {"id":"m_00000000bbbbbb","text":"same id twice"},
            "not an object",
            {"id":"m_00000000cccccc","text":"${"x".repeat(400)}"}
          ],"suggestions":[{"id":"nope","text":"x"},{"id":"s_00000000dddddd","text":"ok","created":5}],"negatives":["", "no"]}"""
        val p = MemoryCodec.read(json) as MemoryParse.Ok
        assertEquals(listOf("m_00000000bbbbbb", "m_00000000cccccc"), p.file.items.map { it.id })
        val kept = p.file.items[0]
        // An unknown kind is a fact; an unknown source is content, never your own words; a secret is never pinned.
        assertEquals(MemoryKind.FACT, kept.kind)
        assertEquals(MemorySource.CONTENT, kept.source)
        assertTrue(kept.tainted)
        assertTrue(kept.secret)
        assertFalse(kept.pinned)
        assertEquals(MemoryLimits.MAX_CHARS, p.file.items[1].text.length)
        assertEquals(listOf("s_00000000dddddd"), p.file.suggestions.map { it.id })
        assertEquals(listOf("no"), p.file.negatives)
        assertEquals(5, p.dropped)
    }

    @Test fun damagedOrNewerFilesAreNotRead() {
        assertEquals(MemoryParse.Damaged, MemoryCodec.read("not json"))
        assertEquals(MemoryParse.Damaged, MemoryCodec.read("[]"))
        assertEquals(MemoryParse.Damaged, MemoryCodec.read("{}"))
        assertEquals(MemoryParse.Damaged, MemoryCodec.read("""{"v":1}"""))
        assertEquals(MemoryParse.Damaged, MemoryCodec.read("""{"v":1,"items":[{"id":"m_00000000aaaaaa","te"""))
        assertEquals(MemoryParse.TooNew(2), MemoryCodec.read("""{"v":2,"items":[]}"""))
    }

    @Test fun theEncryptedFileHoldsNoPlainText() {
        val c = FakeCipher()
        val bytes = MemoryEnvelope.seal(full, c)
        assertArrayEquals("RMEM".toByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(1, bytes[4].toInt())
        assertEquals("robot_memory_v1", MemoryEnvelope.alias(bytes))
        val latin = String(bytes, Charsets.ISO_8859_1)
        for (w in listOf("Julie", "4512", "Sam", "items", "Mom")) assertFalse(w, latin.contains(w))
        assertEquals(MemoryLoad.Loaded(full), MemoryEnvelope.open(bytes, { if (it == c.alias) c else null }))
    }

    @Test fun lockedGoneTamperedAndRenamedFiles() {
        val c = FakeCipher()
        val bytes = MemoryEnvelope.seal(full, c)
        val cipherFor = { a: String -> if (a == c.alias) c else null }
        c.locked = true
        assertEquals(MemoryLoad.Locked, MemoryEnvelope.open(bytes, cipherFor))
        c.locked = false
        c.gone = true
        assertEquals(MemoryLoad.KeyGone, MemoryEnvelope.open(bytes, cipherFor))
        c.gone = false
        // No key under the alias (the lock-screen key after a re-key, say).
        assertEquals(MemoryLoad.KeyGone, MemoryEnvelope.open(bytes, { null }))
        // One byte changed, cut short, another file's name, not memory at all, a newer format.
        val flipped = bytes.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertEquals(MemoryLoad.Unreadable, MemoryEnvelope.open(flipped, cipherFor))
        assertEquals(MemoryLoad.Unreadable, MemoryEnvelope.open(bytes.copyOf(30), cipherFor))
        assertEquals(MemoryLoad.Unreadable, MemoryEnvelope.open(bytes, cipherFor, fileName = "index.enc"))
        assertEquals(MemoryLoad.Unreadable, MemoryEnvelope.open("{\"v\":1}".toByteArray(), cipherFor))
        assertEquals(MemoryLoad.TooNew(9), MemoryEnvelope.open(bytes.copyOf().also { it[4] = 9 }, cipherFor))
        assertEquals(MemoryLoad.Empty, MemoryEnvelope.open(null, cipherFor))
        assertEquals(MemoryLoad.Empty, MemoryEnvelope.open(ByteArray(0), cipherFor))
        // Sealed JSON that isn't memory's.
        val other = MemoryEnvelope.seal(MemoryFile(), c).let { b -> b.copyOfRange(0, 6 + c.alias.length) + c.seal("[1,2]".toByteArray(), MemoryEnvelope.aad("memory.enc")) }
        assertEquals(MemoryLoad.Unreadable, MemoryEnvelope.open(other, cipherFor))
    }

    @Test fun aFileRobotCannotReadIsNeverWrittenOver() {
        val c = FakeCipher()
        val cipherFor = { a: String -> if (a == c.alias) c else null }
        val add = { f: MemoryFile -> MemoryEdits.add(f, item(1)).file }
        // No file yet: the change is sealed.
        val (load0, first) = MemoryEnvelope.update(null, c, cipherFor, change = add)
        assertTrue(load0 is MemoryLoad.Loaded)
        assertNotNull(first)
        assertEquals(1, (MemoryEnvelope.open(first, cipherFor) as MemoryLoad.Loaded).file.items.size)
        // Damaged, locked, newer: nothing to write, and the change never ran.
        var ran = false
        val spy = { f: MemoryFile -> ran = true; f }
        assertEquals(MemoryLoad.Unreadable to null, MemoryEnvelope.update("garbage".toByteArray(), c, cipherFor, change = spy))
        c.locked = true
        assertEquals(MemoryLoad.Locked to null, MemoryEnvelope.update(first, c, cipherFor, change = spy))
        c.locked = false
        assertEquals(MemoryLoad.TooNew(2), MemoryEnvelope.update(first!!.copyOf().also { it[4] = 2 }, c, cipherFor, change = spy).first)
        assertFalse(ran)
        assertFalse(MemoryLoad.Unreadable.writable)
        assertFalse(MemoryLoad.KeyGone.writable)
        assertTrue(MemoryLoad.Empty.writable)
        assertNull(MemoryLoad.Locked.current)
    }

    @Test fun idsAreSafeAndSortByTime() {
        val a = MemoryIds.newId(1_000)
        val b = MemoryIds.newId(2_000_000_000_000)
        assertTrue(MemoryIds.valid(a) && MemoryIds.valid(b))
        assertTrue(a < b)
        assertTrue(MemoryIds.newId(5, prefix = 's').startsWith("s_"))
        for (bad in listOf(null, "", "m_", "../m_123456", "m_ABCDEF", "x_123456", "m_12345")) assertFalse("$bad", MemoryIds.valid(bad))
    }

    @Test fun preferencesPinThemselvesUntilSix() {
        var f = MemoryFile()
        for (n in 1..6) {
            val a = MemoryEdits.add(f, item(n, "Preference number $n", MemoryKind.PREFERENCE))
            assertEquals(AddOutcome.SAVED, a.outcome)
            assertTrue(a.item!!.pinned)
            f = a.file
        }
        val seventh = MemoryEdits.add(f, item(7, "Preference number 7", MemoryKind.PREFERENCE))
        assertEquals(AddOutcome.SAVED_PIN_FULL, seventh.outcome)
        assertFalse(seventh.item!!.pinned)
        assertNull(MemoryEdits.pin(seventh.file, seventh.item!!.id, true))
        val unpinned = MemoryEdits.pin(seventh.file, f.items[0].id, false)!!
        assertNotNull(MemoryEdits.pin(unpinned, seventh.item!!.id, true))
    }

    @Test fun addingCleansDeduplicatesAndRefusesWhenFull() {
        val a = MemoryEdits.add(MemoryFile(), item(1, "  My   sister's birthday\nis May 3  "))
        assertEquals("My sister's birthday is May 3", a.item!!.text)
        assertEquals(AddOutcome.ALREADY_KEPT, MemoryEdits.add(a.file, item(2, "my sister's birthday is may 3.")).outcome)
        assertEquals(AddOutcome.EMPTY, MemoryEdits.add(a.file, item(3, "   ")).outcome)
        assertEquals(MemoryLimits.MAX_CHARS, MemoryEdits.add(MemoryFile(), item(4, "y".repeat(1000))).item!!.text.length)
        // A secret is found even when the caller didn't mark it, and is never pinned.
        val s = MemoryEdits.add(MemoryFile(), item(5, "My bank PIN is 4512", MemoryKind.PREFERENCE, pinned = true))
        assertTrue(s.item!!.secret)
        assertFalse(s.item!!.pinned)
        assertNull(MemoryEdits.pin(s.file, s.item!!.id, true))
        val many = MemoryFile(items = (1..MemoryLimits.MAX_ITEMS).map { item(it) })
        assertTrue(Added(many, null, AddOutcome.FULL).nearFull)
        assertEquals(AddOutcome.FULL, MemoryEdits.add(many, item(99_999, "One too many")).outcome)
    }

    @Test fun forgetUndoEditAndUse() {
        var f = MemoryFile()
        for (n in 1..3) f = MemoryEdits.add(f, item(n)).file
        val (less, gone) = MemoryEdits.forget(f, listOf(f.items[1].id))
        assertEquals(2, less.items.size)
        assertEquals(1, gone.size)
        assertEquals(3, MemoryEdits.restore(less, gone).items.size)
        assertEquals(3, MemoryEdits.restore(MemoryEdits.restore(less, gone), gone).items.size)
        val edited = MemoryEdits.edit(f, f.items[0].id, "The door code is 7788", t0 + 50)!!
        assertTrue(edited.items[0].secret)
        assertEquals(t0 + 50, edited.items[0].updatedAt)
        assertNull(MemoryEdits.edit(f, "m_nosuchitem00", "x", 1))
        assertNull(MemoryEdits.edit(f, f.items[0].id, "  ", 1))
        val used = MemoryEdits.used(f, listOf(f.items[2].id), t0 + 60)
        assertEquals(1, used.items[2].uses)
        assertEquals(t0 + 60, used.items[2].usedAt)
    }

    @Test fun sweepDropsWhatEnded() {
        val day = MemoryLimits.DAY_MS
        val now = t0 + 30 * day
        val f = MemoryFile(
            items = listOf(item(1).copy(expiresAt = now - 2 * day), item(2).copy(expiresAt = now - day / 2), item(3)),
            suggestions = listOf(MemorySuggestion("s_00000000aaaaaa", "old", MemoryKind.FACT, now - 15 * day),
                MemorySuggestion("s_00000000bbbbbb", "new", MemoryKind.FACT, now - day)),
            negatives = (1..300).map { "n$it" },
        )
        val s = MemoryEdits.sweep(f, now)
        // Ended more than a day ago: gone. Ended today: kept in the file, but no question uses it.
        assertEquals(listOf(f.items[1].id, f.items[2].id), s.items.map { it.id })
        assertEquals(listOf(f.items[2].id), MemoryEdits.live(s.items, now).map { it.id })
        assertEquals(listOf("new"), s.suggestions.map { it.text })
        assertEquals(MemoryLimits.MAX_NEGATIVES, s.negatives.size)
        assertEquals("n300", s.negatives.last())
    }

    @Test fun keepingAndDismissingSuggestions() {
        val f = MemoryFile(suggestions = listOf(MemorySuggestion("s_00000000aaaaaa", "Léo has hockey on Tuesdays", MemoryKind.FACT, t0),
            MemorySuggestion("s_00000000bbbbbb", "I had pizza", MemoryKind.FACT, t0)))
        val kept = MemoryEdits.keep(f, "s_00000000aaaaaa", t0 + 1, newId = { "m_00000000kkkkkk" })
        assertEquals(MemorySource.SUGGESTED, kept.item!!.source)
        assertEquals(listOf("s_00000000bbbbbb"), kept.file.suggestions.map { it.id })
        val no = MemoryEdits.dismiss(kept.file, "s_00000000bbbbbb", dontSuggest = true)
        assertTrue(no.suggestions.isEmpty())
        assertEquals(listOf("I had pizza"), no.negatives)
    }

    @Test fun logLinesNeverHoldTheWords() {
        val i = full.items[0]
        assertFalse(i.toString().contains("Julie"))
        assertFalse(full.suggestions[0].toString().contains("Léo"))
        assertFalse(full.toString().contains("4512"))
    }
}
