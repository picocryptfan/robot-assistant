// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.seal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The shared STREAM (1.4) on its own: what Robot Files needs beyond the backup's use of it (backup/BackupStreamTest and
 * BackupStreamPinTest cover the rest): an empty stream only where allowed and only as one chunk 0, caps on chunks and
 * bytes that release nothing past them, and the additional data bound to every chunk.
 */
class SealStreamTest {
    private val c = SealStream.CHUNK
    private val aad = "header".toByteArray()
    private fun key(seed: Int = 1) = ByteArray(32) { (it * 5 + seed).toByte() }

    private fun seal(plain: ByteArray, allowEmpty: Boolean = true, a: ByteArray = aad, k: ByteArray = key()): ByteArray {
        val out = ByteArrayOutputStream()
        SealWriter(out, a, k, allowEmpty).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, allowEmpty: Boolean = true, a: ByteArray = aad, k: ByteArray = key(), maxChunks: Long = SealStream.MAX_CHUNKS,
                     maxBytes: Long = Long.MAX_VALUE): ByteArray =
        SealReader(ByteArrayInputStream(sealed), a, k, allowEmpty, maxChunks, maxBytes).use { it.readBytes() }

    private fun problem(block: () -> Unit): SealProblem? = try {
        block()
        null
    } catch (x: SealException) {
        x.problem
    }

    @Test fun roundTripsFromNothingUp() {
        for (n in listOf(0, 1, c - 1, c, c + 1, 2 * c, 3 * c + 7)) {
            val d = Random(n).nextBytes(n)
            val s = seal(d)
            assertEquals("size of $n", n + SealStream.chunks(n.toLong()) * SealStream.TAG, s.size.toLong())
            assertArrayEquals("$n bytes", d, open(s))
        }
    }

    @Test fun anEmptyStreamIsOneTagAndOnlyWhereAllowed() {
        val s = seal(ByteArray(0))
        assertEquals(SealStream.TAG, s.size)
        assertEquals(0, open(s).size)
        // Where a stream may not be empty (a backup): the writer refuses, and the reader refuses the one empty chunk.
        assertTrue(runCatching { SealWriter(ByteArrayOutputStream(), aad, key()).close() }.isFailure)
        assertEquals(SealProblem.EMPTY, problem { open(s, allowEmpty = false) })
        // Nothing at all (not even the tag) is a cut stream.
        assertEquals(SealProblem.INCOMPLETE, problem { open(ByteArray(0)) })
        // Another key, or other additional data: never read as empty.
        assertTrue(problem { open(s, k = key(2)) } != null)
        assertTrue(problem { open(s, a = "other".toByteArray()) } != null)
    }

    /** An empty chunk after a full one (no writer makes one; built by hand with the key) is refused even where empty is allowed. */
    @Test fun anEmptyChunkAfterTheFirstIsRefused() {
        val first = seal(Random(1).nextBytes(c + 1)).copyOf(SealStream.SEALED)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, StreamNonce.of(1, true)))
        cipher.updateAAD(aad)
        val emptyLast = cipher.doFinal(ByteArray(0))
        val r = SealReader(ByteArrayInputStream(first + emptyLast), aad, key(), allowEmpty = true)
        val buf = ByteArray(c)
        var n = 0
        while (n < c) n += r.read(buf, n, c - n)
        assertEquals(SealProblem.EMPTY, problem { r.read() })
        assertEquals(c.toLong(), r.released)
    }

    @Test fun capsReleaseNothingPastThem() {
        val d = Random(3).nextBytes(2 * c + 10)
        val s = seal(d)
        assertArrayEquals(d, open(s, maxChunks = 3, maxBytes = d.size.toLong()))
        // One byte over: the chunk that crosses the cap is opened but never released.
        val r = SealReader(ByteArrayInputStream(s), aad, key(), true, SealStream.MAX_CHUNKS, d.size - 1L)
        val buf = ByteArray(c)
        var n = 0
        while (n < 2 * c) {
            val k = r.read(buf, 0, buf.size)
            n += k
        }
        assertEquals(SealProblem.TOO_BIG, problem { r.read() })
        assertEquals(2L * c, r.released)
        assertEquals(SealProblem.TOO_BIG, problem { open(s, maxChunks = 2) })
        // The writer stops at its own chunk cap (a third chunk is sealed only at the close, when it is the last).
        val w = SealWriter(ByteArrayOutputStream(), aad, key(), allowEmpty = true, maxChunks = 2)
        w.write(ByteArray(2 * c + 1))
        assertEquals(SealProblem.TOO_BIG, problem { w.close() })
    }

    @Test fun theAdditionalDataAndTheKeyAreBound() {
        val s = seal(Random(4).nextBytes(c + 5))
        assertEquals(SealProblem.FIRST_CHUNK, problem { open(s, a = "headex".toByteArray()) })
        assertEquals(SealProblem.FIRST_CHUNK, problem { open(s, k = key(9)) })
        assertTrue(runCatching { SealWriter(ByteArrayOutputStream(), aad, ByteArray(16)) }.isFailure)
        assertTrue(runCatching { SealReader(ByteArrayInputStream(s), aad, ByteArray(31)) }.isFailure)
    }

    /** The caller's key array is the caller's: the classes copy it, so zeroing it after set-up changes nothing. */
    @Test fun theKeyIsCopied() {
        val k = key()
        val out = ByteArrayOutputStream()
        val w = SealWriter(out, aad, k, allowEmpty = true)
        k.fill(0)
        w.use { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), open(out.toByteArray()))
    }
}
