// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.seal.SealStream
import app.heyrobot.core.seal.SealWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import kotlin.random.Random

/**
 * files-1.4-plan.md §2.3 and §11's FileSealTest: the envelope round trip at every edge, the 96-byte header parsed strictly
 * (each field, each truncation), a changed header, chunk or tag, a cut file, a header or wrapped key from another file or
 * purpose, the wrong robot_files_v1, a locked or gone key, wrap type 2, a fresh key for every stream, and the caps. Every
 * failure is a [FileException]: no plaintext, no other exception reaches the caller.
 */
class FileSealTest {
    private val c = SealStream.CHUNK
    private val random = SecureRandom()
    private val keys = FakeFileKeys()
    private val idA = FileId.of("0123456789abcdef0123456789abcdef")!!
    private val idB = FileId.of("fedcba9876543210fedcba9876543210")!!

    private fun data(n: Int, seed: Int = n) = Random(seed).nextBytes(n)

    private fun seal(plain: ByteArray, purpose: SealPurpose = SealPurpose.FILE, id: FileId = idA, k: FileKeys = keys): ByteArray {
        val out = ByteArrayOutputStream()
        FileSeal.writer(out, purpose, id, k, create = false, random = random).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, purpose: SealPurpose = SealPurpose.FILE, id: FileId? = idA, k: FileKeys = keys): ByteArray =
        FileSeal.readAll(ByteArrayInputStream(sealed), purpose, id, k)

    /** What reading [sealed] gives: "ok <size>", or the problem; anything else thrown is a crash and fails the test. */
    private fun problem(sealed: ByteArray, purpose: SealPurpose = SealPurpose.FILE, id: FileId? = idA, k: FileKeys = keys): FileProblem? = try {
        open(sealed, purpose, id, k)
        null
    } catch (x: FileException) {
        x.problem
    }

    private fun flip(b: ByteArray, at: Int, mask: Int = 1) = b.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }

    @Test fun roundTripsFromNothingUp() {
        for (n in listOf(0, 1, 15, 16, 17, c - 1, c, c + 1, 2 * c, 3 * c + 7, 2 * 1024 * 1024 + 3)) {
            val d = data(n)
            val s = seal(d)
            assertEquals("size of $n", FileSeal.sealedSize(n.toLong()), s.size.toLong())
            assertArrayEquals("$n bytes", d, open(s))
        }
        for (p in SealPurpose.entries) assertArrayEquals(data(100), open(seal(data(100), p), p))
    }

    @Test fun theHeaderIsTheTableByteForByte() {
        val s = seal(data(10))
        val h = FileSeal.parse(s)
        assertEquals(96, FileSeal.HEADER_SIZE)
        assertArrayEquals("ROBOTFIL".toByteArray(), s.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(0, 1), s.copyOfRange(8, 10))
        assertEquals(1, s[10].toInt())
        assertEquals(1, s[11].toInt())
        assertArrayEquals(idA.bytes(), s.copyOfRange(12, 28))
        assertArrayEquals(byteArrayOf(0, 60), s.copyOfRange(28, 30))
        assertArrayEquals(h.wrapped, s.copyOfRange(30, 90))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), s.copyOfRange(90, 94))
        assertArrayEquals(byteArrayOf(0, 0), s.copyOfRange(94, 96))
        assertArrayEquals(s.copyOf(96), h.encode())
        assertEquals(SealPurpose.FILE, h.purpose)
        assertEquals(idA, h.id)
        // The three purposes' codes and caps.
        assertEquals(listOf(1, 2, 3), SealPurpose.entries.map { it.code })
        assertEquals(listOf(200L * 1024 * 1024, 64L * 1024, 32L * 1024 * 1024), SealPurpose.entries.map { it.maxBytes })
    }

    @Test fun everyTruncatedHeaderIsRefused() {
        val s = seal(data(10))
        for (len in 0 until 96) assertEquals("cut at $len", FileProblem.DAMAGED, problem(s.copyOf(len)))
    }

    @Test fun everyHeaderFieldIsCheckedStrictly() {
        val s = seal(data(c + 3))
        fun with(at: Int, vararg v: Int) = s.copyOf().also { b -> v.forEachIndexed { i, x -> b[at + i] = x.toByte() } }
        assertEquals(FileProblem.DAMAGED, problem(with(0, 'r'.code)))
        assertEquals(FileProblem.NEWER, problem(with(8, 0, 2)))
        assertEquals(FileProblem.DAMAGED, problem(with(8, 0, 0)))
        assertEquals(FileProblem.DAMAGED, problem(with(10, 0)))
        assertEquals(FileProblem.DAMAGED, problem(with(10, 4)))
        // Wrap type 2 is 1.5's (a public-key wrap): this Robot refuses it as a newer one's; any other as damaged.
        assertEquals(FileProblem.NEWER, problem(with(11, 2)))
        assertEquals(FileProblem.DAMAGED, problem(with(11, 0)))
        assertEquals(FileProblem.DAMAGED, problem(with(11, 3)))
        assertEquals(FileProblem.DAMAGED, problem(with(28, 0, 59)))
        assertEquals(FileProblem.DAMAGED, problem(with(28, 1, 60)))
        assertEquals(FileProblem.DAMAGED, problem(with(90, 0, 0, 0x40, 0)))
        assertEquals(FileProblem.DAMAGED, problem(with(94, 0, 1)))
        // Every byte of the header, flipped: refused, and only ever as damaged or newer.
        for (at in 0 until 96) {
            val p = problem(flip(s, at))
            assertTrue("byte $at: $p", p == FileProblem.DAMAGED || p == FileProblem.NEWER)
        }
    }

    @Test fun everyByteOfASealedFileIsAuthenticated() {
        val s = seal(data(300))
        for (at in s.indices) for (mask in listOf(1, 0x80)) assertTrue("byte $at", problem(flip(s, at, mask)) != null)
        // A two-chunk file: a flip in each chunk and each tag.
        val t = seal(data(c + 100))
        for (at in listOf(96, 96 + 1000, 96 + c - 1, 96 + c, 96 + c + 15, 96 + c + 16, 96 + c + 16 + 50, t.size - 1))
            assertEquals("byte $at", FileProblem.DAMAGED, problem(flip(t, at)))
    }

    @Test fun aCutFileIsRefusedWhereverItIsCut() {
        val s = seal(data(3 * c + 500))
        val h = FileSeal.HEADER_SIZE
        val sealed = SealStream.SEALED
        for (k in 0..3) for (inside in listOf(0, 1, 15, 16, 17, 1000, c, sealed - 1)) {
            val len = h + k * sealed + inside
            if (len < s.size) assertEquals("cut at $len", FileProblem.DAMAGED, problem(s.copyOf(len)))
        }
        for (cut in 1..40) assertEquals(FileProblem.DAMAGED, problem(s.copyOf(s.size - cut)))
        // Bytes after the end, chunks swapped or dropped.
        for (extra in listOf(1, 16, sealed)) assertEquals(FileProblem.DAMAGED, problem(s + ByteArray(extra)))
        val chunk = { i: Int -> s.copyOfRange(h + i * sealed, minOf(h + (i + 1) * sealed, s.size)) }
        assertEquals(FileProblem.DAMAGED, problem(s.copyOf(h) + chunk(1) + chunk(0) + chunk(2) + chunk(3)))
        assertEquals(FileProblem.DAMAGED, problem(s.copyOf(h) + chunk(0) + chunk(2) + chunk(3)))
        assertEquals(FileProblem.DAMAGED, problem(s.copyOf(h) + chunk(0) + chunk(1) + chunk(2)))
    }

    /** A stream read piece by piece releases only checked chunks and never ends normally when the file is cut or changed. */
    @Test fun aStreamingReadNeverEndsCleanlyOnADamagedFile() {
        val d = data(3 * c + 500)
        val s = seal(d)
        for (bad in listOf(s.copyOf(s.size - 1), flip(s, 96 + 2 * SealStream.SEALED + 7), s.copyOf(96 + 2 * SealStream.SEALED))) {
            val r = FileSeal.reader(ByteArrayInputStream(bad), SealPurpose.FILE, idA, keys)
            val got = ByteArrayOutputStream()
            val buf = ByteArray(10_000)
            val failed = try {
                while (true) {
                    val k = r.read(buf)
                    if (k < 0) break
                    got.write(buf, 0, k)
                }
                false
            } catch (x: FileException) {
                assertEquals(FileProblem.DAMAGED, x.problem)
                true
            }
            assertTrue(failed)
            // What was released is the file's own beginning, a whole number of chunks.
            assertTrue(got.size() % c == 0 && got.size() < d.size)
            assertArrayEquals(d.copyOf(got.size()), got.toByteArray())
        }
    }

    @Test fun aHeaderOrAWrappedKeyFromAnotherFileOrPurposeIsRefused() {
        val a = seal(data(500, 1), id = idA)
        val b = seal(data(500, 2), id = idB)
        val aThumb = seal(data(500, 3), SealPurpose.THUMBNAIL, idA)
        // B's header on A's body, read where A belongs: B's id isn't A's.
        assertEquals(FileProblem.DAMAGED, problem(b.copyOf(96) + a.copyOfRange(96, a.size)))
        // ... and with the id changed to A's: B's wrapped key doesn't open under A's id.
        val bAsA = (b.copyOf(96) + a.copyOfRange(96, a.size)).also { idA.bytes().copyInto(it, 12) }
        assertEquals(FileProblem.DAMAGED, problem(bAsA))
        // A's header with B's wrapped key in it.
        val aWithBsKey = a.copyOf().also { b.copyInto(it, 30, 30, 90) }
        assertEquals(FileProblem.DAMAGED, problem(aWithBsKey))
        // A's thumbnail's wrapped key in A's file header (same id, another purpose).
        val aWithThumbKey = a.copyOf().also { aThumb.copyInto(it, 30, 30, 90) }
        assertEquals(FileProblem.DAMAGED, problem(aWithThumbKey))
        // A thumbnail read as a file, and one relabelled as a file.
        assertEquals(FileProblem.DAMAGED, problem(aThumb, SealPurpose.FILE))
        assertEquals(FileProblem.DAMAGED, problem(aThumb.copyOf().also { it[10] = 1 }, SealPurpose.FILE))
        // The index reads any id, but only an index.
        assertArrayEquals(data(500, 1), open(seal(data(500, 1), SealPurpose.INDEX, idB), SealPurpose.INDEX, id = null))
        assertEquals(FileProblem.DAMAGED, problem(a, SealPurpose.INDEX, id = null))
        // Two files spliced chunk by chunk (each its own key).
        val x = seal(data(2 * c + 9, 4), id = idA)
        val y = seal(data(2 * c + 9, 5), id = idA)
        assertEquals(FileProblem.DAMAGED, problem(x.copyOf(96 + SealStream.SEALED) + y.copyOfRange(96 + SealStream.SEALED, y.size)))
    }

    @Test fun theWrongKeyALockedOneAndAGoneOne() {
        val s = seal(data(1000))
        assertEquals(FileProblem.DAMAGED, problem(s, k = FakeFileKeys(seed = 2)))
        assertEquals(FileProblem.KEY_GONE, problem(s, k = FakeFileKeys(present = false)))
        val locked = FakeFileKeys().apply { refuse = FileKeyProblem.LOCKED }
        assertEquals(FileProblem.LOCKED, problem(s, k = locked))
        val invalidated = FakeFileKeys().apply { refuse = FileKeyProblem.GONE }
        assertEquals(FileProblem.KEY_GONE, problem(s, k = invalidated))
        // A Keystore that throws something of its own: not now, never gone.
        assertEquals(FileProblem.LOCKED, problem(s, k = FakeFileKeys().apply { boom = true }))
        // Sealing: locked and gone throw before a byte is written; a new cabinet makes the key.
        for (k in listOf(locked, FakeFileKeys(present = false))) {
            val out = ByteArrayOutputStream()
            assertTrue(runCatching { FileSeal.writer(out, SealPurpose.FILE, idA, k, create = false, random = random) }.exceptionOrNull() is FileException)
            assertEquals(0, out.size())
        }
        val fresh = FakeFileKeys(present = false)
        val out = ByteArrayOutputStream()
        FileSeal.writer(out, SealPurpose.FILE, idA, fresh, create = true, random = random).use { it.write(data(5)) }
        assertEquals(1, fresh.created)
        assertArrayEquals(data(5), open(out.toByteArray(), k = fresh))
    }

    @Test fun everyStreamGetsANewKey() {
        val d = data(c)
        val sealed = (1..40).map { seal(d, SealPurpose.entries[it % 3]) }
        assertEquals(40, keys.keys.size)
        assertEquals(40, keys.keys.toSet().size)
        // The same plaintext sealed twice shares no chunk (another key, so other ciphertext).
        val a = sealed[3].copyOfRange(96, 96 + SealStream.SEALED)
        val b = sealed[6].copyOfRange(96, 96 + SealStream.SEALED)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun theCapsHoldOnBothSides() {
        // A thumbnail: 64 KiB is fine, one byte more is refused before it is sealed.
        assertEquals(c, open(seal(data(c)), SealPurpose.FILE).size)
        val out = ByteArrayOutputStream()
        val w = FileSeal.writer(out, SealPurpose.THUMBNAIL, idA, keys, create = false, random = random)
        w.write(data(c))
        assertEquals(FileProblem.TOO_BIG, runCatching { w.write(1) }.exceptionOrNull().let { (it as FileException).problem })
        // A stream over its purpose's cap (made here past the writer's check) is refused when read, and nothing of the
        // chunk past the cap is released.
        val key = ByteArray(32).also(random::nextBytes)
        val header = FileSealHeader(SealPurpose.THUMBNAIL, idA, keys.wrap(key, FileSeal.keyAad(SealPurpose.THUMBNAIL, idA), false)).encode()
        val big = ByteArrayOutputStream().also { o -> o.write(header); SealWriter(o, header, key, allowEmpty = true).use { it.write(data(c + 1)) } }.toByteArray()
        assertEquals(FileProblem.TOO_BIG, problem(big, SealPurpose.THUMBNAIL))
        // readAll's own cap.
        val s = seal(data(1000))
        assertEquals(1000, FileSeal.readAll(ByteArrayInputStream(s), SealPurpose.FILE, idA, keys, max = 1000).size)
        assertEquals(FileProblem.TOO_BIG, (runCatching { FileSeal.readAll(ByteArrayInputStream(s), SealPurpose.FILE, idA, keys, max = 999) }.exceptionOrNull() as FileException).problem)
        assertEquals(0, FileSeal.readAll(ByteArrayInputStream(seal(ByteArray(0))), SealPurpose.FILE, idA, keys, max = 0).size)
        // The caps keep an older Robot's restore working: every one is under the 1 GiB it reads through.
        assertTrue(SealPurpose.entries.all { it.maxBytes < app.heyrobot.core.backup.BackupCaps.UNKNOWN_ENTRY })
        assertTrue(SealPurpose.entries.all { it.maxBytes % c == 0L })
    }

    /** Random bytes and random changes: only ever a FileException, and a read that succeeds gives the very bytes sealed. */
    @Test fun noInputCrashesTheReader() {
        val r = Random(42)
        val d = data(2 * c + 77)
        val s = seal(d)
        repeat(300) {
            val junk = ByteArray(r.nextInt(0, 400)).also(r::nextBytes)
            assertTrue(runCatching { open(junk) }.exceptionOrNull() is FileException)
            val headerish = s.copyOf(96).also { h -> repeat(r.nextInt(1, 4)) { h[r.nextInt(96)] = r.nextInt(256).toByte() } } + junk
            val x = runCatching { open(headerish) }.exceptionOrNull()
            assertTrue("$x", x is FileException)
            val mutated = s.copyOf().also { m -> repeat(r.nextInt(1, 5)) { m[r.nextInt(m.size)] = r.nextInt(256).toByte() } }
            val got = runCatching { open(mutated) }
            if (got.isSuccess) assertArrayEquals(d, got.getOrThrow()) else assertTrue("${got.exceptionOrNull()}", got.exceptionOrNull() is FileException)
        }
        // An input stream that fails part way: its own IOException, never anything else.
        val failing = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 500) s[n - 1].toInt() and 0xFF else throw IOException("disk")
        }
        assertTrue(runCatching { FileSeal.readAll(failing, SealPurpose.FILE, idA, keys) }.exceptionOrNull() is IOException)
    }
}
