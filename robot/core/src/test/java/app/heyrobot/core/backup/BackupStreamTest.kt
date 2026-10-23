// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.seal.StreamNonce
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The STREAM of 64 KiB AES-GCM chunks: round trips at the edges, a wrong passphrase (chunk 0), a file
 * cut at every chunk boundary and inside chunks, bytes after the end, a flipped bit in the header, a chunk and a tag,
 * chunks swapped, repeated and dropped, two files spliced, and nonces that never repeat.
 */
class BackupStreamTest {
    private val c = BackupFormat.CHUNK
    private val s = BackupFormat.SEALED
    private val h = BackupFormat.HEADER_SIZE

    private fun data(n: Int, seed: Int = n) = Random(seed).nextBytes(n)

    private fun open(file: ByteArray, key: BackupKey = BackupKit.key()): ByteArray = BackupKit.decrypt(file, key)

    private fun problem(file: ByteArray, key: BackupKey = BackupKit.key()) = BackupKit.problem { open(file, key) }

    /** A file of [chunks] chunks, the last one [lastLen] bytes. */
    private fun file(chunks: Int, lastLen: Int = c / 2): ByteArray = BackupKit.encrypt(data((chunks - 1) * c + lastLen, chunks))

    @Test fun roundTripsAtTheEdges() {
        for (n in listOf(1, 2, c - 1, c, c + 1, 2 * c, 2 * c + 1, 5 * 1024 * 1024)) {
            val d = data(n)
            val f = BackupKit.encrypt(d)
            val chunks = (n + c - 1) / c
            assertEquals("size of $n", h + n.toLong() + chunks * BackupFormat.TAG, f.size.toLong())
            assertArrayEquals("$n bytes", d, open(f))
        }
    }

    @Test fun neverEmpty() {
        val out = ByteArrayOutputStream()
        val w = BackupWriter(out, BackupKit.header(), BackupKit.key())
        assertTrue(runCatching { w.close() }.isFailure)
        // A file of nothing but the header (or one cut there) is incomplete.
        assertEquals(OpenProblem.INCOMPLETE, problem(BackupKit.header().encode()))
    }

    @Test fun writesInAnyPieces() {
        val d = data(3 * c + 17)
        val out = ByteArrayOutputStream()
        BackupWriter(out, BackupKit.header(), BackupKit.key()).use { w ->
            var i = 0
            val r = Random(5)
            while (i < d.size) {
                val k = minOf(d.size - i, r.nextInt(1, 20_000))
                if (k == 1) w.write(d[i].toInt()) else w.write(d, i, k)
                i += k
            }
        }
        assertArrayEquals(BackupKit.encrypt(d), out.toByteArray())
        // And reads back one byte at a time as well as in pieces.
        val r = BackupReader(ByteArrayInputStream(out.toByteArray(), h, out.size()), BackupKit.header(), BackupKit.key())
        val got = ByteArrayOutputStream()
        while (true) {
            val b = r.read()
            if (b < 0) break
            got.write(b)
        }
        assertArrayEquals(d, got.toByteArray())
        assertEquals(-1, r.read())
    }

    @Test fun aWrongPassphraseFailsAtChunkZero() {
        for (f in listOf(file(1), file(3), file(2, c))) {
            assertEquals(OpenProblem.WRONG_PASSPHRASE, problem(f, BackupKit.key(seed = 2)))
        }
        // Nothing of chunk 0 is released: the very first read throws.
        val r = BackupReader(ByteArrayInputStream(file(3), h, file(3).size), BackupKit.header(), BackupKit.key(seed = 2))
        assertEquals(OpenProblem.WRONG_PASSPHRASE, BackupKit.problem { r.read(ByteArray(10)) })
        assertEquals(0L, r.released)
    }

    @Test fun aChangedHeaderReadsAsAWrongPassphrase() {
        val f = file(3)
        // The salt, the app version, the KDF's passes: every chunk's additional data, so chunk 0 fails.
        for (at in listOf(20, 35, 41, 15)) {
            val bad = f.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            val p = BackupKit.problem { BackupHeader.parse(bad); open(bad) }
            assertTrue("byte $at: $p", p == OpenProblem.WRONG_PASSPHRASE || p == OpenProblem.KDF_REFUSED)
        }
        // The magic, the format, the flags, the chunk size, the reserved bytes: refused before the passphrase.
        for (at in listOf(0, 9, 11, 39, 47)) {
            val bad = f.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            val p = BackupKit.problem { BackupHeader.parse(bad) }
            assertTrue("byte $at: $p", p == OpenProblem.NOT_A_BACKUP || p == OpenProblem.NEWER_FORMAT)
        }
    }

    @Test fun truncationIsCaughtEverywhere() {
        val f = file(4)
        // At every chunk boundary (the header's end included): incomplete.
        for (k in 0..3) assertEquals("cut after $k chunks", OpenProblem.INCOMPLETE, problem(f.copyOf(h + k * s)))
        // Inside every chunk: an error, and never a whole read.
        for (k in 0..3) for (inside in listOf(1, 15, 16, 17, 1000, c, s - 1)) {
            val len = h + k * s + inside
            if (len >= f.size) continue
            val p = problem(f.copyOf(len))
            assertNotNull("cut at $len", p)
            if (k > 0) assertEquals("cut at $len", OpenProblem.INCOMPLETE, p)
        }
        // The last chunk cut short too.
        for (cut in 1..40) assertNotNull(problem(f.copyOf(f.size - cut)))
    }

    @Test fun bytesAfterTheEndAreRefused() {
        // After a full last chunk: that chunk opens as the last one, so the extra bytes are a change.
        val full = file(2, c)
        assertEquals(OpenProblem.CHANGED, problem(full + ByteArray(1)))
        assertEquals(OpenProblem.CHANGED, problem(full + ByteArray(s)))
        assertEquals(OpenProblem.CHANGED, problem(full + full.copyOfRange(h, h + s)))
        // After a short last chunk: refused too (it no longer opens as the last one).
        val short = file(3)
        for (extra in listOf(1, 16, 100, s)) assertNotNull(problem(short + ByteArray(extra)))
    }

    @Test fun aFlippedBitInAChunkOrATagIsCaught() {
        val f = file(4)
        for (k in 1..3) for (at in listOf(0, 100, c - 1, c, s - 1)) {
            val pos = h + k * s + at
            if (pos >= f.size) continue
            val bad = f.copyOf().also { it[pos] = (it[pos].toInt() xor 0x40).toByte() }
            val p = problem(bad)
            assertNotNull("chunk $k byte $at", p)
            // A full chunk's change is a change; the last, short chunk's can't be told from a cut.
            if (k < 3) assertEquals("chunk $k byte $at", OpenProblem.CHANGED, p)
        }
        // Chunk 0's change reads as a wrong passphrase: Robot can't tell them apart.
        val bad0 = f.copyOf().also { it[h + 5] = (it[h + 5].toInt() xor 1).toByte() }
        assertEquals(OpenProblem.WRONG_PASSPHRASE, problem(bad0))
    }

    private fun chunks(f: ByteArray): List<ByteArray> = (h until f.size step s).map { f.copyOfRange(it, minOf(it + s, f.size)) }

    private fun join(f: ByteArray, parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(f, 0, h)
        parts.forEach(out::write)
        return out.toByteArray()
    }

    @Test fun chunksSwappedRepeatedOrDroppedAreCaught() {
        val f = file(5)
        val p = chunks(f)
        assertEquals(5, p.size)
        // Swapped.
        assertEquals(OpenProblem.CHANGED, problem(join(f, listOf(p[0], p[2], p[1], p[3], p[4]))))
        assertEquals(OpenProblem.WRONG_PASSPHRASE, problem(join(f, listOf(p[1], p[0], p[2], p[3], p[4]))))
        // Repeated.
        assertEquals(OpenProblem.CHANGED, problem(join(f, listOf(p[0], p[1], p[1], p[2], p[3], p[4]))))
        // Dropped from the middle: chunk 2's bytes at 1's place.
        assertEquals(OpenProblem.CHANGED, problem(join(f, listOf(p[0], p[2], p[3], p[4]))))
        // The last chunk dropped: the file ends after a chunk that isn't the last.
        assertEquals(OpenProblem.INCOMPLETE, problem(join(f, p.dropLast(1))))
        // A middle chunk can't be made to look like the last one without the key.
        assertNotNull(problem(join(f, listOf(p[0], p[1]))))
    }

    @Test fun twoBackupsSplicedAreCaught() {
        val a = file(3)
        // Same passphrase, another backup: its own salt, so its own key.
        val otherSalt = ByteArray(16) { 9 }
        val keyB = BackupKey(BackupKdf.deriveFrom(BackupKdf.password("same passphrase"), otherSalt, BackupFixtures.LIGHT))
        val keyA = BackupKey(BackupKdf.deriveFrom(BackupKdf.password("same passphrase"), BackupKit.SALT, BackupFixtures.LIGHT))
        val ha = BackupKit.header(kdf = BackupFixtures.LIGHT)
        val hb = BackupKit.header(salt = otherSalt, kdf = BackupFixtures.LIGHT)
        val fa = BackupKit.encrypt(data(3 * c), ha, keyA)
        val fb = BackupKit.encrypt(data(3 * c, 99), hb, keyB)
        // A's header with B's chunks, and A's first chunk with B's others.
        assertEquals(OpenProblem.WRONG_PASSPHRASE, problem(join(fa, chunks(fb)), keyA))
        assertEquals(OpenProblem.CHANGED, problem(join(fa, listOf(chunks(fa)[0]) + chunks(fb).drop(1)), keyA))
        assertEquals(3, chunks(a).size)
    }

    @Test fun noncesNeverRepeat() {
        val seen = HashSet<String>()
        for (i in 0L until 1000L) for (last in listOf(false, true)) assertTrue(seen.add(StreamNonce.of(i, last).joinToString(",")))
        assertEquals(12, StreamNonce.of(0, false).size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 1), StreamNonce.of(258, true))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0), StreamNonce.of(BackupFormat.MAX_CHUNKS - 1, false))
        assertTrue(runCatching { StreamNonce.of(BackupFormat.MAX_CHUNKS, false) }.isFailure)
        // The same plaintext in every chunk gives different ciphertext each time (the counter), and in another file (the salt).
        val f = BackupKit.encrypt(ByteArray(3 * c))
        val p = chunks(f)
        assertNotEquals(p[0].toList(), p[1].toList())
        val g = BackupKit.encrypt(ByteArray(3 * c), BackupKit.header(salt = ByteArray(16)), BackupKit.key(seed = 3))
        assertNotEquals(chunks(g)[0].toList(), p[0].toList())
    }

    @Test fun aReaderThatFailedStaysFailed() {
        val f = file(3)
        val bad = f.copyOf().also { it[h + s + 10] = (it[h + s + 10].toInt() xor 1).toByte() }
        val r = BackupReader(ByteArrayInputStream(bad, h, bad.size - h), BackupKit.header(), BackupKit.key())
        val buf = ByteArray(c)
        var n = 0
        while (n < c) n += r.read(buf, n, c - n)
        assertEquals(OpenProblem.CHANGED, BackupKit.problem { r.read(buf) })
        assertEquals(OpenProblem.CHANGED, BackupKit.problem { r.read(buf) })
        assertEquals(c.toLong(), r.released)
    }
}
