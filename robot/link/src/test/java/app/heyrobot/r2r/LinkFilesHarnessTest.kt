// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileSeal
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.XferSeal
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.XferState
import app.heyrobot.core.link.requests.FileAccept
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.seal.PublicWrap
import org.junit.After
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
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * robot-to-robot-plan.md §3.6, §3.7, §4.1 (job L8): a file between two Robots in one JVM, and what happens to a peer that lies:
 * wrong size, wrong hash, a resume past the end, chunks for an offer never accepted, a file over the limit. Nothing reaches
 * incoming/link/ until you accepted it and its hash passed.
 */
class LinkFilesHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()
    private val random = SecureRandom()
    private val push = FakePushServer()
    private val torNet = FakeTorNetwork()

    @After fun tearDown() { for (r in robots) r.node?.close() }

    private class Bot(val r: TestRobot, val env: TestDeskEnv, val t: LinkTransfers, val dirs: TransferDirs, val notices: MutableList<DeskNoticeKind>)

    private val inbox = object : PublicWrap {
        val pair = PAIR
        override fun wrap(plain: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run { init(Cipher.ENCRYPT_MODE, pair.public); doFinal(plain) }
        override fun unwrap(wrapped: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run { init(Cipher.DECRYPT_MODE, pair.private); doFinal(wrapped) }
    }

    private fun robot(name: String, free: () -> Long = { Long.MAX_VALUE / 8 }): Bot {
        val env = TestDeskEnv()
        val dir = tmp.newFolder()
        val dirs = TransferDirs(File(dir, "link"), File(dir, "files"))
        var t: LinkTransfers? = null
        val r = TestRobot(dir, FakeEnv(t0), null, 0, torNet, push, name, deskEnv = env, more = { st ->
            listOf(LinkTransfers(st, dirs, inbox, st.log(), space = free).also { t = it })
        })
        robots += r
        val notices = java.util.concurrent.CopyOnWriteArrayList<DeskNoticeKind>()
        r.n().attachTransfers(t!!) { notices += it.kind; r.desk!!.notice(it) }.get(10, TimeUnit.SECONDS)
        r.n().start(); r.n().wake()
        return Bot(r, env, t!!, dirs, notices)
    }

    private fun LinkStateStore.log() = app.heyrobot.core.link.LinkLog(null) { 0L }

    private fun until(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun idle(vararg bs: Bot) {
        repeat(3) { for (b in bs) { b.r.n().pump().get(10, TimeUnit.SECONDS); b.r.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun active(r: TestRobot, not: Set<String> = emptySet()): String {
        until("an active pairing") { r.n().pairings().get().any { it.value.name == "ACTIVE" && it.key !in not } }
        return r.n().pairings().get().entries.first { it.value.name == "ACTIVE" && it.key !in not }.key
    }

    private fun pair(a: Bot, b: Bot): Pair<String, String> {
        val beforeA = a.r.n().pairings().get().keys; val beforeB = b.r.n().pairings().get().keys
        val code = a.r.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.r.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        return active(a.r, beforeA) to active(b.r, beforeB)
    }

    private fun level(b: Bot, pairing: String, cap: LinkCapability, level: LinkLevel) {
        if (level == LinkLevel.AUTO) assertTrue(b.r.n().setLevel(pairing, cap, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        assertTrue(b.r.n().setLevel(pairing, cap, level).get(10, TimeUnit.SECONDS))
    }

    private fun <T> on(b: Bot, block: () -> T): T = b.r.n().onLinkThread(block).get(10, TimeUnit.SECONDS)
    private fun views(b: Bot): List<TransferView> = on(b) { b.t.views() }
    private fun sha(b: ByteArray) = LinkBytes.hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun send(from: Bot, pairing: String, body: LinkBody) {
        val r = LinkRequest.of(body, from.r.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.r.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
    }

    private class Sent(val offer: FileOffer, val key: ByteArray, val id: String, val plain: ByteArray)

    /** What main does: the copy sealed, then offered. [claim]: what the offer says, which a liar makes different. */
    private fun offer(from: Bot, pairing: String, plain: ByteArray, kind: OfferKind = OfferKind.entries.first { it != OfferKind.DANGEROUS }, shaSaid: String = sha(plain), name: String = "receipt.pdf"): Sent {
        val id = LinkIds.newId(); val file = LinkIds.newId(); val key = ByteArray(32).also(random::nextBytes)
        val copy = from.dirs.copy(id); copy.parentFile!!.mkdirs()
        copy.outputStream().use { o -> XferSeal.writer(o, file, key).use { it.write(plain) } }
        val offer = FileOffer(file, name, kind, plain.size.toLong(), shaSaid, OfferWhy.entries.first())
        assertTrue(on(from) { from.t.offer(id, pairing, offer, key, LinkRequests.DEFAULT_TTL) })
        return Sent(offer, key, id, plain)
    }

    private fun chunks(plain: ByteArray, key: ByteArray, file: String): List<ByteArray> {
        val out = ByteArrayOutputStream()
        XferSeal.writer(out, file, key).use { it.write(plain) }
        val all = out.toByteArray()
        val list = ArrayList<ByteArray>()
        var at = XferSeal.HEADER
        while (at < all.size) { val n = minOf(65_552, all.size - at); list += all.copyOfRange(at, at + n); at += n }
        return list
    }

    private fun arrivedFiles(b: Bot) = b.dirs.arrived.listFiles().orEmpty().toList()
    private fun state(b: Bot, file: String, out: Boolean) = views(b).firstOrNull { it.offer.file == file && it.out == out }?.state

    private fun pumpUntil(a: Bot, b: Bot, what: String, check: () -> Boolean) = try {
        until(what) { idle(a, b); check() }
    } catch (e: AssertionError) {
        throw AssertionError("${e.message}: ${views(a).map { it.state }} ${views(b).map { it.state }} ${a.r.n().status().get()} ${b.r.n().status().get()}")
    }

    /** A connection of [pairing] for feeding chunks by hand, as a peer that isn't this code would. */
    private fun conn(proven: Boolean = true): LiveConn {
        val (x, _) = PipeDuplex.pair()
        return LiveConn(x, app.heyrobot.core.link.LinkWay.DIRECT, false, t0).also { it.proven = proven }
    }

    private fun anOffer(sam: Bot, matt: Bot, pSam: String, plain: ByteArray): Sent {
        val s = offer(sam, pSam, plain)
        pumpUntil(sam, matt, "the card") { on(matt) { matt.r.desk!!.view()!!.cards.size } == 1 }
        return s
    }

    /** Matt accepts, the sender's copy held back, so the key arrives and no honest chunk does: the test is the peer. */
    private fun acceptedWithoutChunks(sam: Bot, matt: Bot, pSam: String, plain: ByteArray): Sent {
        val s = anOffer(sam, matt, pSam, plain)
        sam.dirs.copy(s.id).delete()
        val card = on(matt) { matt.r.desk!!.view()!!.cards.single() }
        assertTrue(on(matt) { matt.r.desk!!.answer(card.id, DeskChoice.YES) })
        pumpUntil(sam, matt, "the key") { state(matt, s.offer.file, false) == XferState.RECEIVING }
        return s
    }

    @Test fun aFileGoesAfterYouAcceptAndArrivesSealedAndWhole() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val plain = bytes(300_000)
        val s = anOffer(sam, matt, pSam, plain)
        assertEquals("no record, no key, no bytes until you accept", null, state(matt, s.offer.file, false))
        assertEquals(XferState.OFFERED, state(sam, s.offer.file, true))
        assertEquals(emptyList<File>(), arrivedFiles(matt))

        val card = on(matt) { matt.r.desk!!.view()!!.cards.single() }
        assertEquals(DeskYes.ACCEPT, card.yes)
        assertTrue(on(matt) { matt.r.desk!!.answer(card.id, DeskChoice.YES) })
        pumpUntil(sam, matt, "it arrived") { state(matt, s.offer.file, false) == XferState.ARRIVED }
        pumpUntil(sam, matt, "the sender was told") { state(sam, s.offer.file, true) == XferState.SENT }
        assertFalse("the sender's sealed copy goes", sam.dirs.copy(s.id).exists())

        val v = views(matt).single()
        assertTrue("the peer's id isn't a path", v.id != s.offer.file)
        val f = matt.dirs.arrivedFile(v.id)
        assertEquals(listOf(f), arrivedFiles(matt))
        assertArrayEquals(plain, FileSeal.arrivedReader(f.inputStream(), FileId.of(v.id), inbox).use { it.readBytes() })
        assertEquals("its chunks are gone", 0, matt.dirs.parts.listFiles().orEmpty().size)
        assertTrue(DeskNoticeKind.FILES in matt.notices)

        assertTrue(on(matt) { matt.t.filed(v.id, true) })
        assertEquals(emptyList<File>(), arrivedFiles(matt))
        assertEquals(XferState.FILED, views(matt).single().state)
    }

    @Test fun aWrongHashLeavesNothingAnywhereAndTheSenderIsTold() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val s = offer(sam, pSam, bytes(100_000), shaSaid = sha(bytes(4)))
        pumpUntil(sam, matt, "the card") { on(matt) { matt.r.desk!!.view()!!.cards.size } == 1 }
        val card = on(matt) { matt.r.desk!!.view()!!.cards.single() }
        assertTrue(on(matt) { matt.r.desk!!.answer(card.id, DeskChoice.YES) })
        pumpUntil(sam, matt, "it failed") { state(matt, s.offer.file, false) == XferState.FAILED }
        assertEquals(emptyList<File>(), arrivedFiles(matt))
        assertEquals(0, matt.dirs.parts.listFiles().orEmpty().size)
        assertTrue(DeskNoticeKind.FILE_FAILED in matt.notices)
        pumpUntil(sam, matt, "the sender was told") { state(sam, s.offer.file, true) == XferState.FAILED }
    }

    @Test fun chunksAndAKeyForAnOfferNeverAcceptedAreDroppedAndWriteNothing() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val plain = bytes(100_000)
        val s = anOffer(sam, matt, pSam, plain)
        // A key for it, unasked.
        send(sam, pSam, app.heyrobot.core.link.requests.FileKey(s.offer.file, s.key))
        idle(sam, matt)
        assertNull(state(matt, s.offer.file, false))
        // Its start frame and chunks.
        val c = conn()
        assertFalse(matt.t.chunk(c, pMatt, XferSeal.start(s.offer.file, 0)))
        assertEquals(0, matt.dirs.parts.listFiles().orEmpty().size)
        assertEquals(emptyList<File>(), arrivedFiles(matt))
        // And another pairing's id for an offer it never made gets nothing either.
        assertFalse(matt.t.chunk(conn(), LinkIds.newId(), XferSeal.start(s.offer.file, 0)))
    }

    @Test fun anOfferNeverAcceptedRunsOutAndItsCopyGoes() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val s = anOffer(sam, matt, pSam, bytes(10))
        assertTrue(sam.dirs.copy(s.id).exists())
        sam.r.env.clock.addAndGet((LinkRequests.DEFAULT_TTL + 1) * 1000)
        idle(sam)
        assertEquals(XferState.EXPIRED, state(sam, s.offer.file, true))
        assertFalse(sam.dirs.copy(s.id).exists())
    }

    @Test fun aPeerThatLiesAboutTheSizeFailsAtTheFirstChunkThatDoesntFit() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val plain = bytes(100)
        val s = acceptedWithoutChunks(sam, matt, pSam, plain)
        val c = conn()
        assertTrue(matt.t.chunk(c, pMatt, XferSeal.start(s.offer.file, 0)))
        assertFalse(matt.t.chunk(c, pMatt, chunks(bytes(200_000), s.key, s.offer.file)[0]))
        pumpUntil(sam, matt, "failed") { state(matt, s.offer.file, false) == XferState.FAILED }
        assertEquals(emptyList<File>(), arrivedFiles(matt))
        assertEquals(0, matt.dirs.parts.listFiles().orEmpty().size)
    }

    @Test fun aCutTransferResumesFromTheLastGoodChunkAndNeverPastIt() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val plain = bytes(200_000)
        val s = acceptedWithoutChunks(sam, matt, pSam, plain)
        val all = chunks(plain, s.key, s.offer.file)
        val c = conn()
        assertTrue(matt.t.chunk(c, pMatt, XferSeal.start(s.offer.file, 0)))
        assertTrue(matt.t.chunk(c, pMatt, all[0]))
        assertTrue(matt.t.chunk(c, pMatt, all[1]))
        // A start that isn't exactly the next chunk (past the end, or back over what it has) closes the connection and keeps the prefix.
        for (from in listOf(all.size.toLong(), 9_999L, 0L, 3L)) assertFalse("$from", matt.t.chunk(conn(), pMatt, XferSeal.start(s.offer.file, from)))
        // Bad tags on a connection that hasn't proved itself cost it the connection, never the transfer.
        repeat(5) {
            val x = conn(proven = false)
            assertTrue(matt.t.chunk(x, pMatt, XferSeal.start(s.offer.file, 2)))
            assertFalse(matt.t.chunk(x, pMatt, chunks(plain, bytes(32), s.offer.file)[2]))
        }
        assertEquals(XferState.RECEIVING, state(matt, s.offer.file, false))
        // Picks up from chunk 2.
        val d = conn()
        assertTrue(matt.t.chunk(d, pMatt, XferSeal.start(s.offer.file, 2)))
        assertTrue(matt.t.chunk(d, pMatt, all[2]))
        assertTrue(matt.t.chunk(d, pMatt, all[3]))
        pumpUntil(sam, matt, "arrived") { state(matt, s.offer.file, false) == XferState.ARRIVED }
        val v = views(matt).single()
        assertArrayEquals(plain, FileSeal.arrivedReader(matt.dirs.arrivedFile(v.id).inputStream(), FileId.of(v.id), inbox).use { it.readBytes() })
    }

    @Test fun threeBadTagsOnAProvenConnectionFailTheTransfer() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val plain = bytes(100_000)
        val s = acceptedWithoutChunks(sam, matt, pSam, plain)
        repeat(3) {
            val x = conn()
            assertTrue(matt.t.chunk(x, pMatt, XferSeal.start(s.offer.file, 0)))
            assertFalse(matt.t.chunk(x, pMatt, chunks(plain, bytes(32), s.offer.file)[0]))
        }
        pumpUntil(sam, matt, "failed") { state(matt, s.offer.file, false) == XferState.FAILED }
        assertEquals(emptyList<File>(), arrivedFiles(matt))
    }

    @Test fun aResumePastTheEndSendsNothingAndAStrangersAcceptIsIgnored() {
        val sam = robot("Sam"); val matt = robot("Matt"); val other = robot("Other")
        val (pSam, pMatt) = pair(sam, matt)
        val (pSamOther, pOther) = pair(sam, other)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val plain = bytes(100_000)
        val s = anOffer(sam, matt, pSam, plain)
        // Accepted by a third Robot: nothing happens to Matt's offer, no key goes to it.
        send(other, pOther, FileAccept(s.offer.file))
        idle(sam, other)
        assertEquals(XferState.OFFERED, state(sam, s.offer.file, true))
        assertNull(views(other).firstOrNull())
        // Matt's, for a chunk past the file's last, before and after the key.
        send(matt, pMatt, FileAccept(s.offer.file, 2))
        idle(sam, matt)
        assertEquals("no chunks before its key", XferState.OFFERED, state(sam, s.offer.file, true))
        send(matt, pMatt, FileAccept(s.offer.file))
        pumpUntil(sam, matt, "accepted") { state(sam, s.offer.file, true) == XferState.SENDING }
        send(matt, pMatt, FileAccept(s.offer.file, 2))
        idle(sam, matt)
        assertEquals(XferState.SENDING, state(sam, s.offer.file, true))
        assertEquals(0, sam.r.n().status().get().connections.coerceAtMost(0))
    }

    /** L16, finding 15: asking again and again gets one key, and the file only so many times from the same place. */
    @Test fun anOfferIsAcceptedOnce() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val s = anOffer(sam, matt, pSam, bytes(100_000))
        sam.r.env.network.set(false)
        repeat(3) { send(matt, pMatt, FileAccept(s.offer.file)) }
        until("read") { state(sam, s.offer.file, true) == XferState.SENDING }
        idle(matt)
        assertEquals(1, sam.r.n().onLinkThread { sam.r.state.outbox.values.count { it.type == RequestType.FILE_KEY.code } }.get(10, TimeUnit.SECONDS))
        val limit = FileResumes(1_000)
        assertTrue(limit.allow(0))
        repeat(FileResumes.AGAIN) { assertTrue(limit.allow(0)) }
        assertFalse(limit.allow(0))
        assertTrue("further along is what a bad connection does", limit.allow(1))
    }

    /** F26: claiming to get further each time buys about three more copies of the file, and then nothing. */
    @Test fun aResumeThatKeepsGettingFurtherStopsAtThreeCopiesMore() {
        val limit = FileResumes(10)
        for (i in 0L until 10) limit.sent(i)
        var from = 1L
        var resent = 0L
        while (from < 10 && limit.allow(from)) { for (i in from until 10) { limit.sent(i); resent++ }; from++ }
        assertTrue("stopped at $from", from < 10)
        assertTrue("$resent", resent in 30L..39L)
    }

    /** F26: a bad connection re-sends only what was lost at each cut, however many cuts there are. */
    @Test fun aBadConnectionCutsManyTimesAndStillFinishes() {
        val limit = FileResumes(1_000)
        var i = 0L
        while (i < 1_000) {
            assertTrue("$i", limit.allow(maxOf(0, i - 2)))
            for (k in maxOf(0, i - 2)..minOf(i + 2, 999)) limit.sent(k)
            i += 3
        }
    }

    /** F117: a wrong length is seen before the tag, so on an unproven connection it costs the connection and not the transfer. */
    @Test fun aWrongLengthOnAnUnprovenConnectionLeavesTheTransferAlone() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        val plain = bytes(100_000)
        val s = acceptedWithoutChunks(sam, matt, pSam, plain)
        val x = conn(proven = false)
        assertTrue(matt.t.chunk(x, pMatt, XferSeal.start(s.offer.file, 0)))
        assertFalse(matt.t.chunk(x, pMatt, ByteArray(7)))
        idle(matt)
        assertEquals(XferState.RECEIVING, state(matt, s.offer.file, false))
        val y = conn(proven = true)
        assertTrue(matt.t.chunk(y, pMatt, XferSeal.start(s.offer.file, 0)))
        assertFalse(matt.t.chunk(y, pMatt, ByteArray(7)))
        until("failed") { state(matt, s.offer.file, false) == XferState.FAILED }
    }

    /** F72: their unpair ends what's coming, its key and copies, and a file not yet filed. */
    @Test fun theirUnpairEndsTransfersAndWhatWasNotFiled() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.AUTO)
        val done = offer(sam, pSam, bytes(1_000))
        pumpUntil(sam, matt, "arrived") { state(matt, done.offer.file, false) == XferState.ARRIVED }
        assertEquals(1, arrivedFiles(matt).size)
        val coming = acceptedWithoutChunks(sam, matt, pSam, bytes(60_000_000))
        val id = views(matt).first { it.offer.file == coming.offer.file }.id
        assertTrue(matt.t.chunk(conn(), pMatt, XferSeal.start(coming.offer.file, 0)))
        assertTrue(sam.r.n().unpair(pSam).get(10, TimeUnit.SECONDS))
        pumpUntil(sam, matt, "ended") { on(matt) { matt.r.state.pairings[pMatt]?.state } == PairingState.ENDED }
        assertEquals(XferState.FAILED, state(matt, coming.offer.file, false))
        assertEquals(XferState.FAILED, state(matt, done.offer.file, false))
        assertTrue(arrivedFiles(matt).isEmpty())
        assertFalse(matt.dirs.part(id).exists())
    }

    @Test fun onItsOwnOnlyWithinTheLimitsThatStay() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.AUTO)
        // Small and plain: accepted, no card.
        val s = offer(sam, pSam, bytes(1_000))
        pumpUntil(sam, matt, "arrived on its own") { state(matt, s.offer.file, false) == XferState.ARRIVED }
        assertTrue(views(matt).single().alone)
        // A dangerous type: always a card, however small.
        val d = offer(sam, pSam, bytes(1_000), kind = OfferKind.DANGEROUS)
        pumpUntil(sam, matt, "a card") { on(matt) { matt.r.desk!!.view()!!.cards.size } == 1 }
        assertNull(state(matt, d.offer.file, false))
        // Over 50 MiB on its own: refused by the policy, so a card, with no copy made here (the rule is core's, this is its use).
        assertFalse(app.heyrobot.core.link.LinkPolicy.fileAlone(LinkLimits.FILE_AUTO_BYTES + 1, OfferKind.entries.first { it != OfferKind.DANGEROUS },
            app.heyrobot.core.link.SlidingSum(LinkLimits.DAY, LinkLimits.FILE_BYTES_PER_DAY), 1))
    }

    @Test fun lockedAndOnItsOwnItStillArrivesAndWaitsForFiles() {
        val sam = robot("Sam"); val matt = robot("Matt")
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.AUTO)
        matt.env.locked = true
        matt.env.settings = matt.env.settings.copy(whileLocked = true)
        val s = offer(sam, pSam, bytes(70_000))
        pumpUntil(sam, matt, "arrived") { state(matt, s.offer.file, false) == XferState.ARRIVED }
        assertEquals(1, arrivedFiles(matt).size)
    }

    @Test fun noRoomKeepsTheCardUntilYouDeclineAndUnpairingLeavesNoCopyAndKeepsTheHistory() {
        val sam = robot("Sam"); val matt = robot("Matt") { 1024L }
        val (pSam, pMatt) = pair(sam, matt)
        level(matt, pMatt, LinkCapability.FILES, LinkLevel.ASK)
        val s = anOffer(sam, matt, pSam, bytes(1_000))
        val card = on(matt) { matt.r.desk!!.view()!!.cards.single() }
        // F62: no room: your yes isn't reported as done, nothing starts, and the card stays.
        assertFalse(on(matt) { matt.r.desk!!.answer(card.id, DeskChoice.YES) })
        assertNull(state(matt, s.offer.file, false))
        assertEquals(card.id, on(matt) { matt.r.desk!!.view()!!.cards.single().id })
        assertTrue(on(matt) { matt.r.desk!!.answer(card.id, DeskChoice.DECLINE) })
        pumpUntil(sam, matt, "declined") { state(sam, s.offer.file, true) == XferState.DECLINED }
        assertFalse(sam.dirs.copy(s.id).exists())
        val t = offer(sam, pSam, bytes(1_000))
        assertTrue(sam.dirs.copy(t.id).exists())
        assertTrue(sam.r.n().unpair(pSam).get(10, TimeUnit.SECONDS))
        assertFalse(sam.dirs.copy(t.id).exists())
        assertEquals(setOf(XferState.DECLINED, XferState.FAILED), views(sam).map { it.state }.toSet())
    }

    private companion object {
        val PAIR = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    }
}
