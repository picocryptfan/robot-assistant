// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.seal

import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.LinkSeal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.random.Random

/**
 * robot-to-robot-plan.md §2.4 and §3.6 (job L2): core/seal's envelope in both formats, through Robot to Robot's kind
 * (ROBOTLNK): format 1 under a Keystore AES key, format 2 under robot_inbox_v1's public key. Round trips, both headers
 * byte for byte, every field parsed strictly, a wrapped key moved to another stream, the RSA block's binding, locked and
 * gone keys, a fresh key per stream, and the caps. Every failure is an [EnvelopeException].
 */
class EnvelopeTest {
    private val c = SealStream.CHUNK
    private val random = SecureRandom()
    private val secret = FakeSecretWrap()
    private val inbox = FakeInboxWrap()
    private val kind = LinkSeal.KIND
    private val session = LinkSeal.purpose(LinkPurpose.SESSION)
    private val waiting = LinkSeal.purpose(LinkPurpose.WAITING)
    private val idA = ByteArray(16) { (it + 1).toByte() }
    private val idB = ByteArray(16) { (0x40 + it).toByte() }

    private fun data(n: Int, seed: Int = n) = Random(seed).nextBytes(n)

    private fun seal(plain: ByteArray, purpose: KindPurpose = session, id: ByteArray = idA, s: SecretWrap = secret, p: PublicWrap = inbox, create: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        Envelope.writer(out, kind, purpose, id, secret = s, public = p, create = create, random = random).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, purpose: KindPurpose = session, id: ByteArray? = idA, s: SecretWrap = secret, p: PublicWrap = inbox): ByteArray =
        Envelope.reader(ByteArrayInputStream(sealed), kind, purpose, id, secret = s, public = p).use { it.readAll(purpose.maxBytes.toInt()) }

    private fun problem(sealed: ByteArray, purpose: KindPurpose = session, id: ByteArray? = idA, s: SecretWrap = secret, p: PublicWrap = inbox): EnvelopeProblem? = try {
        open(sealed, purpose, id, s, p)
        null
    } catch (x: EnvelopeException) {
        x.problem
    }

    private fun flip(b: ByteArray, at: Int, mask: Int = 1) = b.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }

    @Test fun roundTripsInBothFormats() {
        for (n in listOf(0, 1, 15, 16, 17, c - 1, c, c + 1, 2 * c, 3 * c + 7)) {
            val d = data(n)
            val s = seal(d)
            assertEquals("format 1, $n", Envelope.sealedSize(1, n.toLong()), s.size.toLong())
            assertArrayEquals(d, open(s))
        }
        // A waiting request is at most 64 KiB, one chunk (FileSealTest runs format 2 over many, for files that arrived).
        for (n in listOf(0, 1, 100, c - 1, c)) {
            val d = data(n)
            val s = seal(d, waiting)
            assertEquals("format 2, $n", Envelope.sealedSize(2, n.toLong()), s.size.toLong())
            assertArrayEquals(d, open(s, waiting))
        }
    }

    @Test fun theFormat1HeaderIsTheTableByteForByte() {
        val s = seal(data(10))
        val h = Envelope.parse(kind, s)
        assertEquals(96, Envelope.headerSize(1))
        assertArrayEquals("ROBOTLNK".toByteArray(), s.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(0, 1), s.copyOfRange(8, 10))
        assertEquals(5, s[10].toInt())
        assertEquals(1, s[11].toInt())
        assertArrayEquals(idA, s.copyOfRange(12, 28))
        assertArrayEquals(byteArrayOf(0, 60), s.copyOfRange(28, 30))
        assertArrayEquals(h.wrapped, s.copyOfRange(30, 90))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), s.copyOfRange(90, 94))
        assertArrayEquals(byteArrayOf(0, 0), s.copyOfRange(94, 96))
        assertArrayEquals(s.copyOf(96), h.encode())
        // Format 1's wrapped key is GCM's with "robot-link-v1|<purpose>|<id>" as additional data.
        assertEquals("robot-link-v1|5|0102030405060708090a0b0c0d0e0f10", String(Envelope.keyAad(kind, session, idA)))
    }

    @Test fun theFormat2HeaderIsTheTableByteForByte() {
        val s = seal(data(10), waiting)
        val h = Envelope.parse(kind, s)
        assertEquals(420, Envelope.headerSize(2))
        assertArrayEquals("ROBOTLNK".toByteArray(), s.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(0, 2), s.copyOfRange(8, 10))
        assertEquals(6, s[10].toInt())
        assertEquals(2, s[11].toInt())
        assertArrayEquals(idA, s.copyOfRange(12, 28))
        assertArrayEquals(byteArrayOf(1, 0x80.toByte()), s.copyOfRange(28, 30))
        assertArrayEquals(h.wrapped, s.copyOfRange(30, 414))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), s.copyOfRange(414, 418))
        assertArrayEquals(byteArrayOf(0, 0), s.copyOfRange(418, 420))
        assertArrayEquals(s.copyOf(420), h.encode())
        // The RSA block holds the key, then "robot-inbox-v1|<magic>|<purpose>|<id>".
        val plain = inbox.unwrap(h.wrapped)
        assertEquals(32 + Envelope.keyAad(kind, waiting, idA).size, plain.size)
        assertEquals("robot-inbox-v1|ROBOTLNK|6|0102030405060708090a0b0c0d0e0f10", String(plain.copyOfRange(32, plain.size)))
    }

    @Test fun everyTruncatedHeaderIsRefused() {
        val one = seal(data(10))
        for (len in 0 until 96) assertEquals("format 1, cut at $len", EnvelopeProblem.DAMAGED, problem(one.copyOf(len)))
        val two = seal(data(10), waiting)
        for (len in 0 until 420) assertEquals("format 2, cut at $len", EnvelopeProblem.DAMAGED, problem(two.copyOf(len), waiting))
    }

    @Test fun everyHeaderFieldIsCheckedStrictly() {
        val one = seal(data(c + 3))
        val two = seal(data(c - 3), waiting)
        fun with(s: ByteArray, at: Int, vararg v: Int) = s.copyOf().also { b -> v.forEachIndexed { i, x -> b[at + i] = x.toByte() } }
        for ((s, p, w) in listOf(Triple(one, session, 60), Triple(two, waiting, 384))) {
            val end = 30 + w
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, 0, 'r'.code), p))
            assertEquals(EnvelopeProblem.NEWER, problem(with(s, 8, 0, 3), p))
            assertEquals(EnvelopeProblem.NEWER, problem(with(s, 8, 1, 0), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, 8, 0, 0), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, 10, 0), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, 10, 99), p))
            assertEquals(EnvelopeProblem.NEWER, problem(with(s, 11, 3), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, 11, 0), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, 28, (w - 1) shr 8, (w - 1) and 0xFF), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, end, 0, 0, 0x40, 0), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(with(s, end + 4, 0, 1), p))
            for (at in 0 until end + 6) {
                val q = problem(flip(s, at), p)
                assertTrue("byte $at: $q", q == EnvelopeProblem.DAMAGED || q == EnvelopeProblem.NEWER)
            }
        }
        // A purpose in the other format's header, and each format's wrap type in the other's.
        assertEquals(EnvelopeProblem.DAMAGED, problem(with(one, 10, 6)))
        assertEquals(EnvelopeProblem.DAMAGED, problem(with(two, 10, 5), waiting))
        assertEquals(EnvelopeProblem.DAMAGED, problem(with(one, 11, 2)))
        assertEquals(EnvelopeProblem.DAMAGED, problem(with(two, 11, 1), waiting))
        // Read as the other purpose: refused.
        assertEquals(EnvelopeProblem.DAMAGED, problem(one, waiting))
        assertEquals(EnvelopeProblem.DAMAGED, problem(two, session))
    }

    @Test fun everyByteIsAuthenticatedAndACutStreamIsRefused() {
        for ((s, p) in listOf(seal(data(300)) to session, seal(data(300), waiting) to waiting)) {
            for (at in s.indices) assertTrue("byte $at", problem(flip(s, at), p) != null)
            for (cut in 1..40) assertEquals(EnvelopeProblem.DAMAGED, problem(s.copyOf(s.size - cut), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(s + ByteArray(16), p))
        }
    }

    @Test fun aWrappedKeyMovedToAnotherStreamIsRefused() {
        for ((p, h, w) in listOf(Triple(session, 96, 60), Triple(waiting, 420, 384))) {
            val a = seal(data(500, 1), p, idA)
            val b = seal(data(500, 2), p, idB)
            // B's header on A's body; with A's id; A's header with B's wrapped key.
            assertEquals(EnvelopeProblem.DAMAGED, problem(b.copyOf(h) + a.copyOfRange(h, a.size), p))
            assertEquals(EnvelopeProblem.DAMAGED, problem((b.copyOf(h) + a.copyOfRange(h, a.size)).also { idA.copyInto(it, 12) }, p))
            assertEquals(EnvelopeProblem.DAMAGED, problem(a.copyOf().also { b.copyInto(it, 30, 30, 30 + w) }, p))
            // B read where A belongs.
            assertEquals(EnvelopeProblem.DAMAGED, problem(b, p, idA))
            assertArrayEquals(data(500, 2), open(b, p, id = null))
        }
        // Another purpose's wrapped key (same format, same id).
        val s = seal(data(500, 3), LinkSeal.purpose(LinkPurpose.SESSION), idA)
        val k = seal(data(500, 4), LinkSeal.purpose(LinkPurpose.PEER), idA)
        assertEquals(EnvelopeProblem.DAMAGED, problem(s.copyOf().also { k.copyInto(it, 30, 30, 90) }))
    }

    /** Format 2's binding lives in the RSA block: a block holding the right key without it, or with another's, is refused. */
    @Test fun theRsaBlockMustCarryItsOwnAdditionalData() {
        val key = ByteArray(32).also(random::nextBytes)
        fun forged(block: ByteArray): ByteArray {
            val header = EnvelopeHeader(kind, waiting, idA, block).encode()
            val out = ByteArrayOutputStream()
            out.write(header)
            SealWriter(out, header, key, allowEmpty = true).use { it.write(data(100)) }
            return out.toByteArray()
        }
        // The control: the key and its own additional data opens.
        assertArrayEquals(data(100), open(forged(inbox.wrap(key + Envelope.keyAad(kind, waiting, idA))), waiting))
        assertEquals(EnvelopeProblem.DAMAGED, problem(forged(inbox.wrap(key)), waiting))
        assertEquals(EnvelopeProblem.DAMAGED, problem(forged(inbox.wrap(key + Envelope.keyAad(kind, waiting, idB))), waiting))
        assertEquals(EnvelopeProblem.DAMAGED, problem(forged(inbox.wrap(key + Envelope.keyAad(kind, waiting, idA) + byteArrayOf(0))), waiting))
        // Another phone's robot_inbox_v1 can't open it.
        assertEquals(EnvelopeProblem.DAMAGED, problem(seal(data(10), waiting), waiting, p = FakeInboxWrap(FakeInboxWrap.OTHER)))
    }

    @Test fun lockedGoneAndTheWrongKey() {
        // Format 2: sealing works while locked; opening needs the unlock.
        val locked = FakeInboxWrap().apply { this.locked = true }
        val s = seal(data(1000), waiting, p = locked)
        assertEquals(EnvelopeProblem.LOCKED, problem(s, waiting, p = locked))
        assertArrayEquals(data(1000), open(s, waiting))
        assertEquals(EnvelopeProblem.GONE, problem(s, waiting, p = FakeInboxWrap().apply { gone = true }))
        // Format 1.
        val one = seal(data(1000))
        assertEquals(EnvelopeProblem.DAMAGED, problem(one, s = FakeSecretWrap(seed = 2)))
        assertEquals(EnvelopeProblem.GONE, problem(one, s = FakeSecretWrap(present = false)))
        assertEquals(EnvelopeProblem.LOCKED, problem(one, s = FakeSecretWrap().apply { refuse = KeyProblem.LOCKED }))
        assertEquals(EnvelopeProblem.GONE, problem(one, s = FakeSecretWrap().apply { refuse = KeyProblem.GONE }))
        assertEquals(EnvelopeProblem.LOCKED, problem(one, s = FakeSecretWrap().apply { boom = true }))
        // Sealing with no key: GONE unless asked to make one, which happens once.
        val none = FakeSecretWrap(present = false)
        val x = try { seal(data(1), s = none); null } catch (e: EnvelopeException) { e.problem }
        assertEquals(EnvelopeProblem.GONE, x)
        seal(data(1), s = none, create = true)
        seal(data(1), s = none, create = true)
        assertEquals(1, none.created)
    }

    @Test fun aFreshKeyForEveryStreamAndTheCaps() {
        val w = FakeSecretWrap()
        repeat(20) { seal(data(10), s = w) }
        assertEquals(20, w.keys.toSet().size)
        // Over a purpose's cap: refused before a byte past it is sealed.
        val peer = LinkSeal.purpose(LinkPurpose.PEER)
        val out = ByteArrayOutputStream()
        val big = try {
            Envelope.writer(out, kind, peer, idA, secret = secret, random = random).use { it.write(ByteArray(1025)) }
            null
        } catch (x: EnvelopeException) {
            x.problem
        }
        assertEquals(EnvelopeProblem.TOO_BIG, big)
        assertTrue(out.size() <= 96 + 16)
        // The RSA block's room: 318 bytes, the key and the longest additional data well inside it.
        assertEquals(318, Envelope.PUBLIC_PLAIN_MAX)
        assertTrue(32 + Envelope.keyAad(kind, waiting, idA).size < Envelope.PUBLIC_PLAIN_MAX)
        assertEquals(1, ByteBuffer.wrap(seal(data(1)), 8, 2).short.toInt())
    }
}
