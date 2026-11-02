// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileSeal
import app.heyrobot.core.link.requests.FileAccept
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.OfferFields
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.seal.PublicWrap
import app.heyrobot.core.seal.SealStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher

/** robot-to-robot-plan.md §3.6 (job L8): the transfer's header, its chunks, and what a peer that lies gets. */
class LinkTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private val random = SecureRandom()
    private val file = "ab".repeat(16)
    private val key = ByteArray(32).also(random::nextBytes)
    private val inbox = object : PublicWrap {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        override fun wrap(plain: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run { init(Cipher.ENCRYPT_MODE, pair.public); doFinal(plain) }
        override fun unwrap(wrapped: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run { init(Cipher.DECRYPT_MODE, pair.private); doFinal(wrapped) }
    }

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun sha(b: ByteArray) = LinkBytes.hex(MessageDigest.getInstance("SHA-256").digest(b))

    /** The chunks a sender makes of [plain], one array each. */
    private fun chunks(plain: ByteArray, k: ByteArray = key, id: String = file): List<ByteArray> {
        val out = ByteArrayOutputStream()
        XferSeal.writer(out, id, k).use { it.write(plain) }
        val all = out.toByteArray()
        val list = ArrayList<ByteArray>()
        var at = XferSeal.HEADER
        while (at < all.size) {
            val n = minOf(SealStream.SEALED, all.size - at)
            list += all.copyOfRange(at, at + n)
            at += n
        }
        return list
    }

    @Test fun aCopyIsExactlyTheSizeItsOfferSays() {
        for (n in listOf(1, 65_535, 65_536, 65_537, 200_000)) {
            val c = chunks(bytes(n))
            assertEquals(XferSeal.chunks(n.toLong()), c.size.toLong())
            assertEquals(XferSeal.sealedSize(n.toLong()), XferSeal.HEADER + c.sumOf { it.size }.toLong())
            c.forEachIndexed { i, b -> assertEquals(b.size, XferSeal.chunkLength(n.toLong(), i.toLong())) }
            assertEquals(-1, XferSeal.chunkLength(n.toLong(), c.size.toLong()))
        }
        // The largest chunk fits the largest live envelope the plan sets (§3.7).
        assertEquals(LinkCarrier.LIVE.maxEnvelope, LinkEnvelope.HEADER + SealStream.SEALED)
    }

    @Test fun aStartFrameReadsBackAndAnythingElseIsRefused() {
        assertEquals(file to 7L, XferSeal.readStart(XferSeal.start(file, 7)))
        assertNull(XferSeal.readStart(XferSeal.start(file, 7).copyOf(XferSeal.START - 1)))
        assertNull(XferSeal.readStart(XferSeal.start(file, 7).also { it[0] = 'X'.code.toByte() }))
        assertNull(XferSeal.readStart(XferSeal.start(file, 7).also { it[9] = 2 }))
        assertNull(XferSeal.readStart(ByteArray(SealStream.SEALED)))
    }

    @Test fun chunksInOrderKeepAnAuthenticPrefixThatSurvivesACut() {
        val plain = bytes(200_000)
        val c = chunks(plain)
        val part = File(tmp.newFolder(), "x.xfr")
        val a = XferIn(part, plain.size.toLong(), file, key)
        assertTrue(a.canStart(0)); assertFalse(a.canStart(1))
        assertEquals(XferStep.MORE, a.take(c[0]))
        assertEquals(XferStep.MORE, a.take(c[1]))
        // A cut in the middle of the next chunk: the prefix is what counts when it opens again.
        part.appendBytes(c[2].copyOf(100))
        val b = XferIn(part, plain.size.toLong(), file, key)
        assertEquals(2, b.good)
        assertEquals(c[0].size + c[1].size.toLong(), part.length())
        assertTrue(b.canStart(2)); assertFalse(b.canStart(0)); assertFalse(b.canStart(3))
        assertEquals(XferStep.MORE, b.take(c[2]))
        assertEquals(XferStep.WHOLE, b.take(c[3]))
        assertFalse("nothing to resume once whole", b.canStart(4))
        assertEquals("nothing past the last chunk is taken", XferStep.LIE, b.take(c[3]))
    }

    @Test fun aPeerThatLiesAboutTheSizeIsCaughtByTheFirstChunkThatDoesntFit() {
        val part = File(tmp.newFolder(), "x.xfr")
        // Offers 100 bytes and sends a full chunk.
        val a = XferIn(part, 100, file, key)
        assertEquals(XferStep.LIE, a.take(chunks(bytes(70_000))[0]))
        assertEquals(0, part.length())
        // Offers 200,000 and ends early: its short "last" chunk is too short for chunk 0.
        val b = XferIn(part, 200_000, file, key)
        assertEquals(XferStep.LIE, b.take(chunks(bytes(100))[0]))
        // Offers one chunk's worth and sends the first of more: a full chunk not sealed as the last one doesn't open.
        val c = XferIn(part, SealStream.CHUNK.toLong(), file, key)
        assertEquals(XferStep.BAD_TAG, c.take(chunks(bytes(70_000))[0]))
        assertEquals(0, part.length())
    }

    @Test fun achunkOutOfOrderOrUnderAnotherKeyOrOfferIsNotKept() {
        val plain = bytes(200_000)
        val c = chunks(plain)
        val part = File(tmp.newFolder(), "x.xfr")
        val a = XferIn(part, plain.size.toLong(), file, key)
        assertEquals(XferStep.BAD_TAG, a.take(c[1]))
        assertEquals(XferStep.BAD_TAG, a.take(chunks(plain, bytes(32))[0]))
        assertEquals(XferStep.BAD_TAG, a.take(chunks(plain, key, "cd".repeat(16))[0]))
        assertEquals(0, a.good); assertEquals(0, part.length())
        assertEquals(XferStep.MORE, a.take(c[0]))
    }

    private fun whole(plain: ByteArray): File {
        val part = File(tmp.newFolder(), "x.xfr")
        val a = XferIn(part, plain.size.toLong(), file, key)
        for (x in chunks(plain)) a.take(x)
        assertTrue(a.whole)
        return part
    }

    @Test fun onlyTheOfferedSizeAndHashLeaveAFormat2File() {
        val plain = bytes(100_000)
        val part = whole(plain)
        val temp = File(tmp.newFolder(), "x.arr")
        val id = FileId.of("11".repeat(16))!!
        assertEquals(XferEnd.WRONG_HASH, XferArrival.seal(part, temp, file, key, plain.size.toLong(), sha(bytes(9)), id, inbox, random))
        assertFalse("nothing of it is left", temp.exists())
        assertEquals(XferEnd.OK, XferArrival.seal(part, temp, file, key, plain.size.toLong(), sha(plain), id, inbox, random))
        assertArrayEquals(plain, FileSeal.arrivedReader(temp.inputStream(), id, inbox).use { it.readBytes() })
        // It's format 2: Files' own reader refuses it, and no process reads it without robot_inbox_v1.
        assertEquals(XferEnd.WRONG_SIZE, XferArrival.seal(part, temp, file, key, plain.size.toLong() - 1, sha(plain), id, inbox, random))
        assertFalse(temp.exists())
        // A changed chunk in the prefix: dropped too.
        part.writeBytes(part.readBytes().also { it[100] = (it[100] + 1).toByte() })
        assertEquals(XferEnd.DAMAGED, XferArrival.seal(part, temp, file, key, plain.size.toLong(), sha(plain), id, inbox, random))
        assertFalse(temp.exists())
    }

    @Test fun aViewCarriesWhatMainNeedsAndAnOfferWithAPathInItsNameIsLeftOut() {
        val offer = FileOffer(file, "receipt.pdf", OfferKind.entries.first(), 10, sha(bytes(1)), OfferWhy.entries.first(), "cd".repeat(16), OfferFields(merchant = "Bean", total = 8412, currency = "CAD"))
        val v = TransferView("11".repeat(16), "22".repeat(16), false, XferState.ARRIVED, offer, 1, 5, 6, true)
        val back = TransferView.decodeAll(TransferView.encodeAll(listOf(v))).single()
        assertEquals(offer, back.offer); assertEquals(XferState.ARRIVED, back.state); assertTrue(back.alone)
        val bad = TransferView("11".repeat(16), "22".repeat(16), false, XferState.ARRIVED, offer.copy(name = "../../evil.so"), 1, 5, 6, true)
        assertEquals(emptyList<TransferView>(), TransferView.decodeAll(TransferView.encodeAll(listOf(bad))))
    }

    /** A request as a peer could write it, whatever this Robot's own encoder allows: [from] replaced by [to] in the plaintext. */
    private fun forged(b: app.heyrobot.core.link.requests.LinkBody, from: String, to: String): RequestRead {
        assertEquals(from.length, to.length)
        val plain = String(LinkRequests.encode(LinkRequest.of(b, 1_790_000_000, "en", 3600))!!, Charsets.UTF_8)
        assertTrue(from in plain)
        return LinkRequests.decode(plain.replace(from, to).toByteArray(Charsets.UTF_8), LinkRequests.LIVE)
    }

    @Test fun onTheWireANameWithAPathAndAFileOverTheLimitAreRefusedAndAResumeSaysFromWhere() {
        val max = 200L * 1024 * 1024
        val good = FileOffer(file, "abc.pdf", OfferKind.entries.first(), max, sha(bytes(1)), OfferWhy.entries.first())
        assertTrue(forged(good, "abc.pdf", "abc.pdf") is RequestRead.Ok)
        for (name in listOf("../.pdf", "ab/.pdf", "ab\\\\pdf")) assertFalse(name, forged(good, "abc.pdf", name.padEnd(7, 'x').take(if (name.contains('\\')) name.length else 7)) is RequestRead.Ok)
        assertFalse("over 200 MiB", forged(good, "$max", "${max + 1}") is RequestRead.Ok)
        assertFalse("nothing", forged(good.copy(size = 10), "\"size\":10", "\"size\":00") is RequestRead.Ok)
        assertEquals(FileAccept(file, 3200), (forged(FileAccept(file, 3200), "3200", "3200") as RequestRead.Ok).request.body)
        assertEquals(FileAccept(file), (forged(FileAccept(file), file, file) as RequestRead.Ok).request.body)
        assertFalse("past any file's last chunk", forged(FileAccept(file, 3200), "3200", "3201") is RequestRead.Ok)
    }
}
