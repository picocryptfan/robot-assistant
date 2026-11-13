// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** seal-notes: a note framed as History's conversations are, with its own magic and additional data. */
class NoteEnvelopeTest {
    private class Soft(override val alias: String) : MemoryCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); c.updateAAD(aad)
            return iv + c.doFinal(plain)
        }
        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)); c.updateAAD(aad)
            return try { c.doFinal(sealed, 12, sealed.size - 12) } catch (e: AEADBadTagException) { throw MemoryKeyException(KeyProblem.BAD_DATA, e) }
        }
    }
    private val note = Note("n-1", "Door", "The code is 4321", 1_000L, 2_000L)
    private val k = Soft("robot_notes_v1")

    @Test fun sealsAndOpensWithoutPlainTextOnDisk() {
        val b = NoteEnvelope.seal(note, k, "n-1.enc")
        assertFalse(String(b, Charsets.ISO_8859_1).contains("4321"))
        assertEquals("RNOT", String(b, 0, 4, Charsets.UTF_8))
        assertEquals("robot_notes_v1", NoteEnvelope.frame.alias(b))
        assertEquals(SealedOpen.Ok(note), NoteEnvelope.open(b, { k }, "n-1.enc"))
    }

    @Test fun anotherNameOrAnotherStoresFrameNeverOpens() {
        val b = NoteEnvelope.seal(note, k, "n-1.enc")
        assertEquals(SealedOpen.Unreadable, NoteEnvelope.open(b, { k }, "n-2.enc"))
        // The same bytes under History's magic fail its tag: the kind is in the additional data too.
        val asHistory = "RHIS".toByteArray() + b.copyOfRange(4, b.size)
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open(asHistory, { k }, "n-1.enc"))
        // A History file is never a note.
        val c = StoredConversation("n-1", 1_000L, 2_000L, listOf(ChatTurn("u", TurnRole.USER, "hi", 1_000L)))
        assertEquals(SealedOpen.Unreadable, NoteEnvelope.open(HistoryEnvelope.seal(c, k, "n-1.enc"), { k }, "n-1.enc"))
    }

    @Test fun aNewerRobotsNoteIsTooNewAndADamagedVersionByteIsDamage() {
        val newer = SealedEnvelope("RNOT", "notes", 2).seal(NoteCodec.toJson(note).toByteArray(), k, "n-1.enc")
        assertEquals(SealedOpen.TooNew(2), NoteEnvelope.open(newer, { k }, "n-1.enc"))
        val damaged = NoteEnvelope.seal(note, k, "n-1.enc").also { it[4] = 2 }
        assertEquals(SealedOpen.Unreadable, NoteEnvelope.open(damaged, { k }, "n-1.enc"))
        assertTrue(NoteEnvelope.open(null, { k }, "n-1.enc") is SealedOpen.Unreadable)
    }
}
