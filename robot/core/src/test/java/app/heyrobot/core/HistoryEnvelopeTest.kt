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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryEnvelopeTest {
    private class Soft(override val alias: String) : MemoryCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var problem: KeyProblem? = null
        override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); c.updateAAD(aad)
            return iv + c.doFinal(plain)
        }
        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
            problem?.let { throw MemoryKeyException(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)); c.updateAAD(aad)
            return try { c.doFinal(sealed, 12, sealed.size - 12) } catch (e: AEADBadTagException) { throw MemoryKeyException(KeyProblem.BAD_DATA, e) }
        }
    }

    private val conv = StoredConversation("c-1", 1_000L, 2_000L, listOf(ChatTurn("u", TurnRole.USER, "Remind me about the dentist", 1_000L)))
    private val k = Soft("robot_history_v1")

    @Test fun sealsAndOpensWithoutPlainTextOnDisk() {
        val b = HistoryEnvelope.seal(conv, k, "c-1.enc")
        assertFalse(String(b, Charsets.ISO_8859_1).contains("dentist"))
        assertEquals("robot_history_v1", HistoryEnvelope.alias(b))
        val o = HistoryEnvelope.open(b, { if (it == k.alias) k else null }, "c-1.enc")
        assertEquals(ConversationCodec.toJson(conv), ConversationCodec.toJson((o as HistoryOpen.Ok).conversation))
    }

    @Test fun anotherNameNeverOpens() {
        val b = HistoryEnvelope.seal(conv, k, "c-1.locked.enc")
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open(b, { k }, "c-1.enc"))
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open(b, { k }, "c-2.locked.enc"))
    }

    @Test fun eachProblemSaysWhich() {
        val b = HistoryEnvelope.seal(conv, k, "c-1.enc")
        assertEquals(HistoryOpen.KeyGone, HistoryEnvelope.open(b, { null }, "c-1.enc"))
        k.problem = KeyProblem.LOCKED
        assertEquals(HistoryOpen.Locked, HistoryEnvelope.open(b, { k }, "c-1.enc"))
        k.problem = KeyProblem.GONE
        assertEquals(HistoryOpen.KeyGone, HistoryEnvelope.open(b, { k }, "c-1.enc"))
        assertEquals(HistoryOpen.Locked, HistoryEnvelope.open(b, { throw MemoryKeyException(KeyProblem.LOCKED) }, "c-1.enc"))
        k.problem = null
        val cut = b.copyOf(b.size - 3)
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open(cut, { k }, "c-1.enc"))
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open("{}".toByteArray(), { k }, "c-1.enc"))
        // seal-history-fix-b 7: a layout this one can't check is a newer Robot's; this one's with its version byte damaged is damaged.
        val newer = b.copyOf().also { it[4] = 9; it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(HistoryOpen.TooNew(9), HistoryEnvelope.open(newer, { k }, "c-1.enc"))
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open(b.copyOf().also { it[4] = 9 }, { k }, "c-1.enc"))
        // VERSION 2 for the layout with the base; 1, the test builds' before it, is kept aside, never read as this one.
        assertEquals(2, b[4].toInt())
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open(b.copyOf().also { it[4] = 1 }, { k }, "c-1.enc"))
    }

    @Test fun theHeaderIsBoundToo() {
        val b = HistoryEnvelope.seal(conv, k, "c-1.enc")
        val other = "robot_history_v2".toByteArray(Charsets.UTF_8)
        System.arraycopy(other, 0, b, 6, other.size)
        assertEquals(HistoryOpen.Unreadable, HistoryEnvelope.open(b, { k }, "c-1.enc"))
    }

    @Test fun namesTellIdAndJournal() {
        assertEquals("c-1" to false, HistoryEnvelope.idOf("c-1.enc"))
        assertEquals("c-1" to true, HistoryEnvelope.idOf("c-1.locked.enc"))
        assertNull(HistoryEnvelope.idOf("c-1.json"))
        assertNull(HistoryEnvelope.idOf("c-1.enc.bad"))
        assertNull(HistoryEnvelope.idOf("../x.enc"))
        assertTrue(HistoryEnvelope.idOf(".c-1.enc.tmp") == null)
    }
}
