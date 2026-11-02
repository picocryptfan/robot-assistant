// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.FolderRec
import app.heyrobot.core.link.FolderState
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.seal.PublicWrap
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.XferSeal
import app.heyrobot.core.link.XferState
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.FoldJoin
import app.heyrobot.core.link.requests.FoldLeave
import app.heyrobot.core.link.requests.FoldOp
import app.heyrobot.core.link.requests.FoldOpKind
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * L11: shared folders between two Robots in one JVM. Asking first, then in step on its own, through L8's one way in, with a peer that
 * lies: over a cap, for a folder never joined, a path in a name, a removal of what it didn't add, a Robot that left.
 */
class SharedFoldersHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()
    private val random = SecureRandom()
    private val push = FakePushServer()
    private val torNet = FakeTorNetwork()

    @After fun tearDown() { for (r in robots) r.node?.close() }

    private class Bot(val r: TestRobot, val env: TestDeskEnv, val t: LinkTransfers, val dirs: TransferDirs, val notices: MutableList<DeskNoticeKind>) {
        val st get() = r.state
        val f get() = r.desk!!.folders!!
    }

    private val inbox = object : PublicWrap {
        override fun wrap(plain: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run { init(Cipher.ENCRYPT_MODE, PAIR.public); doFinal(plain) }
        override fun unwrap(wrapped: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run { init(Cipher.DECRYPT_MODE, PAIR.private); doFinal(wrapped) }
    }

    private fun robot(name: String): Bot {
        val env = TestDeskEnv()
        val dir = tmp.newFolder()
        val dirs = TransferDirs(File(dir, "link"), File(dir, "files"))
        var t: LinkTransfers? = null
        var folders: SharedFolders? = null
        val r = TestRobot(dir, FakeEnv(t0), null, 0, torNet, push, name, deskEnv = env, more = { st ->
            val x = LinkTransfers(st, dirs, inbox, app.heyrobot.core.link.LinkLog(null) { 0L }, space = { Long.MAX_VALUE / 8 })
            t = x
            listOf(x, SharedFolders(st, x).also { folders = it })
        })
        robots += r
        val notices = CopyOnWriteArrayList<DeskNoticeKind>()
        val tell: (app.heyrobot.core.link.DeskNotice) -> Unit = { notices += it.kind; r.desk!!.notice(it) }
        r.n().attachTransfers(t!!, tell).get(10, TimeUnit.SECONDS)
        folders!!.notice = tell
        r.n().start(); r.n().wake()
        return Bot(r, env, t!!, dirs, notices)
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun idle() {
        repeat(4) { for (b in robots) { b.n().pump().get(10, TimeUnit.SECONDS); b.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun pair(a: Bot, b: Bot): Pair<String, String> {
        val code = a.r.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.r.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        until("active") { listOf(a, b).all { x -> x.r.n().pairings().get().values.any { it == PairingState.ACTIVE } } }
        val pa = a.r.n().pairings().get().keys.first(); val pb = b.r.n().pairings().get().keys.first()
        for (x in listOf(a to pa, b to pb)) assertTrue(x.first.r.n().setLevel(x.second, LinkCapability.FOLDERS, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        return pa to pb
    }

    private fun <T> on(b: Bot, block: () -> T): T = b.r.n().onLinkThread(block).get(10, TimeUnit.SECONDS)
    private fun out(b: Bot) = b.r.desk!!.deskOut!!
    private fun send(from: Bot, pairing: String, body: LinkBody) {
        val r = LinkRequest.of(body, from.r.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.r.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
    }
    private fun cards(b: Bot) = on(b) { b.r.desk!!.view()!!.cards }
    private fun yes(b: Bot) { val c = cards(b).first(); assertTrue(on(b) { b.r.desk!!.answer(c.id, DeskChoice.YES) }) }
    private fun no(b: Bot) { val c = cards(b).first(); assertTrue(on(b) { b.r.desk!!.answer(c.id, DeskChoice.DECLINE) }) }
    private fun rec(b: Bot, pairing: String, fold: String) = b.st.folders[FolderRec.key(pairing, fold)]
    private fun views(b: Bot) = on(b) { b.t.views() }
    private fun sha(b: ByteArray) = LinkBytes.hex(MessageDigest.getInstance("SHA-256").digest(b))

    private class Pairs(val sam: Bot, val kim: Bot, val samP: String, val kimP: String)

    private fun two(): Pairs { val s = robot("sam"); val k = robot("kim"); val (a, b) = pair(s, k); return Pairs(s, k, a, b) }

    /** Sam invites, Kim says yes: the folder's id, joined on both sides. */
    private fun joined(x: Pairs): String {
        val fold = on(x.sam) { x.sam.f.invite(x.samP, "Trip", out(x.sam)) }!!
        idle()
        yes(x.kim); idle()
        assertEquals(FolderState.JOINED, rec(x.sam, x.samP, fold)!!.state)
        assertEquals(FolderState.JOINED, rec(x.kim, x.kimP, fold)!!.state)
        return fold
    }

    private fun offer(file: String = LinkIds.newId(), name: String = "a.pdf", size: Long = 100, kind: OfferKind = OfferKind.DOCUMENT, plain: ByteArray = ByteArray(size.toInt())) =
        FileOffer(file, name, kind, size, sha(plain), OfferWhy.FOLDER)

    /** A real file of Sam's in the folder: its op, then L8's offer, as FilesToRobot does it. */
    private fun addFile(x: Pairs, fold: String, plain: ByteArray): FileOffer {
        val file = LinkIds.newId(); val id = LinkIds.newId(); val key = ByteArray(32).also(random::nextBytes)
        val copy = x.sam.dirs.copy(id); copy.parentFile!!.mkdirs()
        copy.outputStream().use { o -> XferSeal.writer(o, file, key).use { it.write(plain) } }
        val o = offer(file, size = plain.size.toLong(), plain = plain)
        assertNotNull(on(x.sam) { x.sam.f.add(x.samP, fold, file, o.name, o.size, out(x.sam)) })
        assertTrue(on(x.sam) { x.sam.t.offer(id, x.samP, o, key, LinkRequests.DEFAULT_TTL) })
        return o
    }

    private fun arrived(x: Pairs, o: FileOffer) {
        val end = System.currentTimeMillis() + 30_000
        while (views(x.kim).none { it.offer.file == o.file && it.state == XferState.ARRIVED }) {
            assertTrue("it never came: ${views(x.kim).map { it.state }} from ${views(x.sam).map { it.state }}", System.currentTimeMillis() < end)
            idle()
        }
    }

    /** What LinkService calls goes through the desk's guard: nothing of yours moves while locked or under the phone lock. */
    @Test fun yourOwnStepsWaitForAnUnlockedPhone() {
        val x = two()
        val desk = x.sam.r.desk!!
        assertNotNull(on(x.sam) { desk.mine { "ok" } })
        x.sam.env.locked = true
        assertNull(on(x.sam) { desk.mine { "ok" } })
        x.sam.env.locked = false
        x.sam.env.phoneLocked = true
        assertNull(on(x.sam) { desk.mine { "ok" } })
    }

    @Test fun joiningAsksFirstAndNothingCountsBeforeYourYes() {
        val x = two()
        val fold = on(x.sam) { x.sam.f.invite(x.samP, "Trip", out(x.sam)) }!!
        assertEquals(FolderState.INVITED, rec(x.sam, x.samP, fold)!!.state)
        idle()
        assertEquals("one card", 1, cards(x.kim).size)
        assertNull("no record until you tap", rec(x.kim, x.kimP, fold))
        val file = LinkIds.newId()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, file)); idle()
        send(x.sam, x.samP, offer(file)); idle()
        assertTrue("no file came in", views(x.kim).isEmpty())
        yes(x.kim); idle()
        assertEquals(FolderState.JOINED, rec(x.kim, x.kimP, fold)!!.state)
        assertEquals(FolderState.JOINED, rec(x.sam, x.samP, fold)!!.state)
        assertTrue("what came before stays out", rec(x.kim, x.kimP, fold)!!.items.isEmpty())
    }

    @Test fun aNoEndsIt() {
        val x = two()
        val fold = on(x.sam) { x.sam.f.invite(x.samP, "Trip", out(x.sam)) }!!
        idle(); no(x.kim); idle()
        assertNull(rec(x.sam, x.samP, fold)); assertNull(rec(x.kim, x.kimP, fold))
        assertTrue(DeskNoticeKind.FOLDER_ENDED in x.sam.notices)
    }

    @Test fun aFileComesInThroughTheOneWayInWithNoTapAndNoCard() {
        val x = two()
        val fold = joined(x)
        val plain = ByteArray(40_000).also(random::nextBytes)
        val o = addFile(x, fold, plain)
        arrived(x, o)
        val v = views(x.kim).single()
        assertTrue("marked as no one's tap, so what can run stays refused", v.alone)
        assertTrue("no card", cards(x.kim).isEmpty())
        val line = rec(x.kim, x.kimP, fold)!!.items.single()
        assertEquals(o.size, line.size); assertEquals(o.name, line.name); assertFalse(line.mine)
        assertTrue(rec(x.sam, x.samP, fold)!!.items.single().mine)
        assertTrue("sealed for later", x.kim.dirs.arrivedFile(v.id).exists())
        assertFalse("the plain bytes never sit in the file", String(x.kim.dirs.arrivedFile(v.id).readBytes(), Charsets.ISO_8859_1).contains(String(plain.copyOf(32), Charsets.ISO_8859_1)))
    }

    @Test fun aFoldersOfferWithAWrongHashNeverBecomesAFile() {
        val x = two()
        val fold = joined(x)
        val plain = ByteArray(40_000).also(random::nextBytes)
        val file = LinkIds.newId(); val id = LinkIds.newId(); val key = ByteArray(32).also(random::nextBytes)
        val copy = x.sam.dirs.copy(id); copy.parentFile!!.mkdirs()
        copy.outputStream().use { o -> XferSeal.writer(o, file, key).use { it.write(plain) } }
        assertNotNull(on(x.sam) { x.sam.f.add(x.samP, fold, file, "a.pdf", plain.size.toLong(), out(x.sam)) })
        assertTrue(on(x.sam) { x.sam.t.offer(id, x.samP, offer(file, size = plain.size.toLong(), plain = ByteArray(1)), key, LinkRequests.DEFAULT_TTL) })
        until("it ended") { views(x.kim).any { it.state.final } }
        assertEquals(XferState.FAILED, views(x.kim).single().state)
        assertFalse(x.kim.dirs.arrivedFile(views(x.kim).single().id).exists())
    }

    @Test fun aFoldersOfferForNothingYouJoinedIsDeclinedWithNoCardWhateverTheFilesLevelSays() {
        val x = two()
        assertTrue(x.kim.r.n().setLevel(x.kimP, LinkCapability.FILES, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        send(x.sam, x.samP, offer()); idle()
        assertTrue("no card", cards(x.kim).isEmpty())
        assertTrue("no transfer", views(x.kim).isEmpty())
        assertEquals("and they're told no", app.heyrobot.core.link.requests.AckState.DECLINED, on(x.kim) { x.kim.f.offer(x.kimP, offer(), out(x.kim)) })
    }

    /** The level goes off between a file's line and its offer: the file still doesn't come. */
    @Test fun aFolderLevelTurnedOffStopsAFileWhoseLineCameFirst() {
        val x = two()
        val fold = joined(x)
        val o = offer()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, o.file)); idle()
        assertEquals(1, rec(x.kim, x.kimP, fold)!!.items.size)
        assertTrue(x.kim.r.n().setLevel(x.kimP, LinkCapability.FOLDERS, LinkLevel.OFF).get(10, TimeUnit.SECONDS))
        send(x.sam, x.samP, o); idle()
        assertTrue(views(x.kim).isEmpty())
    }

    /** Nothing moves on the lock screen: an offer waits for the phone, and comes when it opens. */
    @Test fun aFilesOfferWaitsWhileThePhoneIsLocked() {
        val x = two()
        val fold = joined(x)
        val o = offer()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, o.file)); idle()
        x.kim.env.phoneLocked = true
        send(x.sam, x.samP, o); idle()
        assertTrue(views(x.kim).isEmpty())
    }

    @Test fun aFolderLevelOffStopsFiles() {
        val x = two()
        val fold = joined(x)
        assertTrue(x.kim.r.n().setLevel(x.kimP, LinkCapability.FOLDERS, LinkLevel.OFF).get(10, TimeUnit.SECONDS))
        addFile(x, fold, ByteArray(1000)); idle()
        assertTrue(views(x.kim).isEmpty())
        assertTrue("the op too", rec(x.kim, x.kimP, fold)!!.items.isEmpty())
    }

    @Test fun anOpForAFolderYouNeverJoinedChangesNothing() {
        val x = two()
        val fold = LinkIds.newId()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, LinkIds.newId())); idle()
        assertTrue(x.kim.st.folders.isEmpty())
        send(x.sam, x.samP, FoldLeave(fold)); idle()
        assertTrue("no notice for a folder you never had", DeskNoticeKind.FOLDER_ENDED !in x.kim.notices)
        val real = joined(x)
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, LinkIds.newId())); idle()
        assertTrue("the folder you did join is untouched", rec(x.kim, x.kimP, real)!!.items.isEmpty())
    }

    @Test fun aPathInANameIsRefusedAtEveryDoor() {
        val x = two()
        for (bad in listOf("../up", "a/b", "a\\b", "..", ".")) {
            send(x.sam, x.samP, FoldJoin(LinkIds.newId(), bad))
        }
        idle()
        assertTrue("no card for any", cards(x.kim).isEmpty())
        assertTrue(x.kim.st.folders.isEmpty())
        assertNull(on(x.sam) { x.sam.f.invite(x.samP, "../up", out(x.sam)) })
        // In an offer: it never reads as an offer at all, so there's nothing to file.
        val fold = joined(x)
        val file = LinkIds.newId()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, file)); idle()
        val wire = LinkRequests.wire(LinkRequest.of(offer(file, name = "../../etc/x"), t0 / 1000, "en", LinkRequests.DEFAULT_TTL))
        assertFalse("a path in an offer's name doesn't read", LinkRequests.decode(wire, LinkRequests.LIVE) is app.heyrobot.core.link.requests.RequestRead.Ok)
        assertTrue(views(x.kim).isEmpty())
    }

    @Test fun aDangerousTypeIsNeverTakenForAFolder() {
        val x = two()
        val fold = joined(x)
        val file = LinkIds.newId()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, file)); idle()
        send(x.sam, x.samP, offer(file, name = "a.apk", kind = OfferKind.DANGEROUS)); idle()
        assertTrue(views(x.kim).isEmpty())
        assertTrue("the line's gone", rec(x.kim, x.kimP, fold)!!.items.single().gone)
    }

    @Test fun overACapIsRefusedAndWhatsThereStays() {
        val x = two()
        val fold = joined(x)
        val full = rec(x.kim, x.kimP, fold)!!
        // Near the cap: a file offered past 1 GiB, then past the 500th.
        on(x.kim) {
            x.kim.st.put(full.copy(items = List(LinkLimits.FOLDER_FILES - 1) { app.heyrobot.core.link.FolderItem(LinkIds.newId(), LinkIds.newId(), false, 1, LinkLimits.FOLDER_BYTES / 500, "x", 1) }))
        }
        val file = LinkIds.newId()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, file)); idle()
        assertEquals("the 500th line fits", 500, rec(x.kim, x.kimP, fold)!!.live.size)
        send(x.sam, x.samP, offer(file, size = LinkLimits.FOLDER_BYTES / 500 + 1_000_000, plain = ByteArray(1))); idle()
        assertTrue("past 1 GiB", views(x.kim).isEmpty())
        assertEquals(499, rec(x.kim, x.kimP, fold)!!.live.size)
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, LinkIds.newId())); idle()
        assertEquals(500, rec(x.kim, x.kimP, fold)!!.live.size)
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, LinkIds.newId())); idle()
        assertEquals("the 501st file", 500, rec(x.kim, x.kimP, fold)!!.live.size)
    }

    @Test fun aRemovalOfWhatTheyDidntAddChangesNothingAndTheirOwnNeverTouchesAFile() {
        val x = two()
        val fold = joined(x)
        // Kim's own line: Sam lies with a removal, and an item that isn't there.
        val mine = LinkIds.newId()
        assertNotNull(on(x.kim) { x.kim.f.add(x.kimP, fold, mine, "mine.pdf", 10, out(x.kim)) })
        idle()
        val item = rec(x.kim, x.kimP, fold)!!.items.single().item
        send(x.sam, x.samP, FoldOp(fold, item, 5, FoldOpKind.REMOVE, null)); idle()
        assertFalse("what Kim added stays", rec(x.kim, x.kimP, fold)!!.item(item)!!.gone)
        // Sam's own file comes in, then goes: off the folder, the copy stays.
        val o = addFile(x, fold, ByteArray(30_000).also(random::nextBytes))
        arrived(x, o)
        val v = views(x.kim).first { it.offer.file == o.file }
        val line = rec(x.sam, x.samP, fold)!!.items.first { it.file == o.file }
        assertTrue(on(x.sam) { x.sam.f.remove(x.samP, fold, line.item, out(x.sam)) }); idle()
        assertTrue(rec(x.kim, x.kimP, fold)!!.item(line.item)!!.gone)
        assertTrue("your copy stays", x.kim.dirs.arrivedFile(v.id).exists())
        // Used once: a removed line can't come back, nor can you remove what isn't yours from your side.
        send(x.sam, x.samP, FoldOp(fold, line.item, 9, FoldOpKind.ADD, LinkIds.newId())); idle()
        assertTrue(rec(x.kim, x.kimP, fold)!!.item(line.item)!!.gone)
        assertFalse(on(x.kim) { x.kim.f.remove(x.kimP, fold, line.item, out(x.kim)) })
    }

    @Test fun aRemovalStopsAFileOnItsWay() {
        val x = two()
        val fold = joined(x)
        val file = LinkIds.newId(); val item = LinkIds.newId()
        send(x.sam, x.samP, FoldOp(fold, item, 1, FoldOpKind.ADD, file)); idle()
        send(x.sam, x.samP, offer(file, size = 5000, plain = ByteArray(5000))); idle()
        assertEquals("accepted, no key yet", XferState.ACCEPTED, views(x.kim).single().state)
        send(x.sam, x.samP, FoldOp(fold, item, 2, FoldOpKind.REMOVE, null)); idle()
        assertEquals(XferState.FAILED, views(x.kim).single().state)
        send(x.sam, x.samP, offer(file, size = 5000, plain = ByteArray(5000))); idle()
        assertEquals("and it doesn't come back", 1, views(x.kim).size)
    }

    @Test fun aRobotThatLeftGetsNowhereAndYourCopiesStay() {
        val x = two()
        val fold = joined(x)
        val o = addFile(x, fold, ByteArray(20_000).also(random::nextBytes))
        arrived(x, o)
        val v = views(x.kim).single()
        assertTrue(on(x.sam) { x.sam.f.leave(x.samP, fold, out(x.sam)) }); idle()
        assertNull(rec(x.sam, x.samP, fold)); assertNull(rec(x.kim, x.kimP, fold))
        assertTrue("Kim's told", DeskNoticeKind.FOLDER_ENDED in x.kim.notices)
        assertTrue(x.kim.dirs.arrivedFile(v.id).exists())
        val file = LinkIds.newId()
        send(x.sam, x.samP, FoldOp(fold, LinkIds.newId(), 1, FoldOpKind.ADD, file)); idle()
        send(x.sam, x.samP, offer(file)); idle()
        assertTrue(x.kim.st.folders.isEmpty())
        assertEquals("nothing more came", 1, views(x.kim).size)
    }

    @Test fun aLeaveLetsAJoinInAgainOnlyByAnotherCard() {
        val x = two()
        val fold = joined(x)
        send(x.sam, x.samP, FoldJoin(fold, "Again")); idle()
        assertTrue("already there: no second card", cards(x.kim).isEmpty())
        assertEquals("Trip", rec(x.kim, x.kimP, fold)!!.name)
    }

    @Test fun recordsRoundTripAndAWrongOneIsRefused() {
        val x = two()
        val fold = joined(x)
        assertNotNull(on(x.sam) { x.sam.f.add(x.samP, fold, LinkIds.newId(), "a.pdf", 10, out(x.sam)) })
        val r = rec(x.sam, x.samP, fold)!!
        val back = FolderRec.decode(FolderRec.encode(r))!!
        assertEquals(r.items.single().name, back.items.single().name)
        assertEquals(r.state, back.state)
        assertNull(FolderRec.decode(FolderRec.encode(r).copyOf(10)))
        assertNotNull(FolderRec.decodeAll(FolderRec.encodeAll(listOf(r))))
    }

    private companion object {
        val PAIR = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    }
}
