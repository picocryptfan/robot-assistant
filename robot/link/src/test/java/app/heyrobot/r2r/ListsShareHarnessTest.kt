// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.ListShareItem
import app.heyrobot.core.link.ListShareRec
import app.heyrobot.core.link.ListShareState
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.ListItem
import app.heyrobot.core.link.requests.ListOp
import app.heyrobot.core.link.requests.LsLeave
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 1.5, shared lists between two Robots in one JVM: one offer is one card, whatever its parts and however a peer lies about
 * them; nothing is opened for :voice before your tap; `ls.op` is taken only for a list joined with that pairing.
 */
class ListsShareHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()
    @After fun tearDown() { for (r in robots) r.node?.close() }

    private val list = "d".repeat(32)
    private var n = 0
    private fun id(): String = (++n).toString(16).padStart(32, 'c')
    private fun item(rev: Long = 1) = ListItem(id(), rev, "milk", false, 0, null)
    private fun items(k: Int) = List(k) { item() }

    private class Bot(val r: TestRobot, val env: TestDeskEnv, val listBox: MemoryBox, val parseAway: java.util.concurrent.atomic.AtomicInteger) {
        val st get() = r.state
        val h get() = r.desk!!.lists!!
    }

    private fun robot(name: String, push: FakePushServer, boxMax: Int = app.heyrobot.core.link.LinkInbox.MAX): Bot {
        val env = TestDeskEnv()
        val lb = MemoryBox(boxMax) { env.locked }
        val away = java.util.concurrent.atomic.AtomicInteger(0)
        val r = TestRobot(tmp.newFolder(), FakeEnv(t0), null, 0, null, push, name, deskEnv = env,
            more = { st -> listOf(ListsShareHandler(st, lb, RequestDecoder { plain, cap -> if (away.getAndUpdate { maxOf(0, it - 1) } > 0) null else StandInParser.decode(plain, cap) }, env)) })
        robots += r; r.n().start(); r.n().wake()
        return Bot(r, env, lb, away)
    }

    private fun until(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) { assertTrue("timed out: $what", System.currentTimeMillis() < end); Thread.sleep(20) }
    }

    private fun idle(vararg bs: Bot) {
        repeat(3) { for (b in bs) { b.r.n().pump().get(10, TimeUnit.SECONDS); b.r.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun active(r: TestRobot, not: Set<String> = emptySet()): String {
        until("an active pairing") { r.n().pairings().get().any { it.value == PairingState.ACTIVE && it.key !in not } }
        return r.n().pairings().get().entries.first { it.value == PairingState.ACTIVE && it.key !in not }.key
    }

    private fun pair(a: Bot, b: Bot): Pair<String, String> {
        val code = a.r.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.r.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val pa = active(a.r); val pb = active(b.r)
        assertTrue(a.r.n().setLevel(pa, LinkCapability.LISTS, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        assertTrue(b.r.n().setLevel(pb, LinkCapability.LISTS, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        return pa to pb
    }

    private fun send(from: Bot, pairing: String, body: LinkBody): LinkRequest {
        val r = LinkRequest.of(body, from.r.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.r.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
        return r
    }

    private fun <T> on(b: Bot, block: () -> T): T = b.r.n().onLinkThread(block).get(10, TimeUnit.SECONDS)
    private fun offer(part: Long, parts: Long, k: Int = 1, name: String = "Trip", l: String = list) = LsOffer(l, name, part, parts, items(k))
    private fun rec(b: Bot, pairing: String, l: String = list, onThread: Boolean = false): ListShareRec? =
        if (onThread) b.st.listShares[ListShareRec.key(pairing, l)] else on(b) { b.st.listShares[ListShareRec.key(pairing, l)] }
    private fun cards(b: Bot) = on(b) { b.r.desk!!.view()!!.cards }
    private fun pending(b: Bot) = on(b) { ListShareItem.decodeAll(b.h.pending())!! }
    private fun answer(b: Bot, how: DeskChoice): Boolean { val id = cards(b).single().id; return on(b) { b.r.desk!!.answer(id, how) } }
    private fun two(): Triple<Bot, Bot, Pair<String, String>> { val push = FakePushServer(); val a = robot("a", push); val c = robot("c", push); return Triple(a, c, pair(a, c)) }

    /** F3: a change the sender has dropped stays when no reader can be reached, and is there on the next pass. */
    @Test fun aReaderThatIsAwayKeepsTheChangesWaiting() {
        val (a, c, p) = two(); val (pa, pc) = p
        c.env.locked = true
        send(a, pa, offer(1, 1)); idle(a, c)
        assertEquals(ListShareState.CARD, rec(c, pc)!!.state)
        c.env.locked = false
        assertEquals(1, cards(c).size)
        assertTrue(answer(c, DeskChoice.YES))
        c.parseAway.set(1)
        assertTrue(pending(c).isEmpty())
        assertEquals(1, c.listBox.count())
        assertEquals(1, pending(c).size)
    }

    @Test fun anOfferInPartsIsOneCardAndOpensOnlyAfterYourTap() {
        val (a, c, p) = two(); val (pa, pc) = p
        c.env.locked = true
        send(a, pa, offer(3, 3)); send(a, pa, offer(1, 3)); idle(a, c)
        assertEquals(2, c.listBox.count())
        send(a, pa, offer(2, 3)); idle(a, c)
        assertEquals(ListShareState.CARD, rec(c, pc)!!.state)
        c.env.locked = false
        assertEquals(1, cards(c).size)
        assertTrue("nothing opens before your tap", pending(c).isEmpty())
        assertTrue(answer(c, DeskChoice.YES))
        assertEquals(ListShareState.JOINED, rec(c, pc)!!.state)
        val got = pending(c)
        assertEquals(3, got.size)
        assertEquals(setOf(1L, 2L, 3L), got.map { (it.request.read()!!.body as LsOffer).part }.toSet())
        assertEquals(pc, got[0].pairing)
        on(c) { c.h.applied(got.map { it.id }) }
        assertEquals(0, c.listBox.count())
    }

    @Test fun yourOfferJoinsOnTheirYesAndChangesGoBothWays() {
        val (a, c, p) = two(); val (pa, pc) = p
        assertNotNull(on(a) { a.h.offerPart(pa, offer(1, 1), a.r.n().deskOut) })
        assertEquals(ListShareState.OFFERED, rec(a, pa)!!.state)
        idle(a, c)
        answer(c, DeskChoice.YES); idle(a, c); idle(a, c)
        assertEquals(ListShareState.JOINED, rec(a, pa)!!.state)
        pending(c).let { g -> on(c) { c.h.applied(g.map { it.id }) } }
        val i = item(2)
        val sent = on(c) { c.h.sendOps(pc, LsOp(list, listOf(ListOp(i.item, 2, i))), c.r.n().deskOut) }
        assertNotNull(sent)
        assertEquals(listOf(sent), rec(c, pc)!!.ids)
        idle(a, c); idle(a, c)
        assertEquals(1, pending(a).size)
        assertTrue("acknowledged", rec(c, pc)!!.ids.isEmpty())
    }

    @Test fun yourDeclineLeavesNothing() {
        val (a, c, p) = two(); val (pa, pc) = p
        assertNotNull(on(a) { a.h.offerPart(pa, offer(1, 2), a.r.n().deskOut) })
        assertNotNull(on(a) { a.h.offerPart(pa, offer(2, 2), a.r.n().deskOut) })
        idle(a, c)
        answer(c, DeskChoice.DECLINE); idle(a, c); idle(a, c)
        assertNull(rec(c, pc)); assertEquals(0, c.listBox.count())
        assertNull("the offer ended on their side too", rec(a, pa))
    }

    @Test fun aPeerLyingAboutPartsGetsNoCard() {
        val (a, c, p) = two(); val (pa, pc) = p
        // The count changes between parts: the whole offer goes.
        send(a, pa, offer(1, 3)); send(a, pa, offer(2, 5)); idle(a, c)
        assertNull(rec(c, pc)); assertEquals(0, c.listBox.count()); assertTrue(cards(c).isEmpty())
        // The name on the card must be the name of every part.
        send(a, pa, offer(1, 2, name = "Trip")); send(a, pa, offer(2, 2, name = "Other")); idle(a, c)
        assertNull(rec(c, pc)); assertTrue(cards(c).isEmpty())
        // The same part many times is one part; never a card.
        repeat(5) { send(a, pa, offer(1, 2)) }; idle(a, c)
        assertEquals(1, rec(c, pc)!!.partsSeen); assertEquals(1, c.listBox.count()); assertTrue(cards(c).isEmpty())
    }

    @Test fun moreThanAListsItemsEndsTheOffer() {
        val (a, c, p) = two(); val (pa, pc) = p
        // Parts of 40 are too big for the harness's push, and a live carrier isn't what's tested: 12 parts' count set directly.
        send(a, pa, offer(1, 13)); idle(a, c)
        on(c) { c.st.put(rec(c, pc, onThread = true)!!.copy(items = 499)) }
        send(a, pa, LsOffer(list, "Trip", 13, 13, items(2))); idle(a, c)
        assertNull(rec(c, pc)); assertEquals(0, c.listBox.count()); assertTrue(cards(c).isEmpty())
    }

    @Test fun offersGatheringAreCappedPerRobot() {
        val (a, c, p) = two(); val (pa, pc) = p
        repeat(5) { i -> send(a, pa, offer(1, 2, l = (i + 1).toString().padStart(32, 'e'))) }; idle(a, c)
        assertEquals(ListsShareHandler.MAX_GATHERING, on(c) { c.st.listShares.values.count { it.pairing == pc } })
    }

    @Test fun anOpForAListNotSharedIsDeclinedAndNeverKept() {
        val (a, c, p) = two(); val (pa, pc) = p
        val i = item()
        send(a, pa, LsOp(list, listOf(ListOp(i.item, 1, i)))); idle(a, c)
        assertEquals(0, c.listBox.count())
        // Nor for one on a card: no change before your yes.
        send(a, pa, offer(1, 1)); idle(a, c)
        send(a, pa, LsOp(list, listOf(ListOp(i.item, 1, i)))); idle(a, c)
        assertEquals(0, c.listBox.count())
        assertEquals(ListShareState.CARD, rec(c, pc)!!.state)
    }

    @Test fun aLeaveTakesBackAnOfferStillOnACard() {
        val (a, c, p) = two(); val (pa, pc) = p
        send(a, pa, offer(1, 2)); send(a, pa, offer(2, 2)); idle(a, c)
        assertEquals(ListShareState.CARD, rec(c, pc)!!.state)
        assertEquals(1, cards(c).size)
        assertEquals(1, c.listBox.count())
        send(a, pa, LsLeave(list)); idle(a, c)
        assertNull(rec(c, pc))
        assertEquals("the parts it held went too", 0, c.listBox.count())
        assertTrue("the card comes down", cards(c).isEmpty())
    }

    @Test fun aLeaveTakesBackAnOfferStillGathering() {
        val (a, c, p) = two(); val (pa, pc) = p
        send(a, pa, offer(1, 2)); idle(a, c)
        assertEquals(ListShareState.GATHERING, rec(c, pc)!!.state)
        send(a, pa, LsLeave(list)); idle(a, c)
        assertNull(rec(c, pc))
        assertEquals(0, c.listBox.count())
    }

    @Test fun offStopsYourChangesGoing() {
        val (a, c, p) = two(); val (pa, pc) = p
        send(a, pa, offer(1, 1)); idle(a, c)
        answer(c, DeskChoice.YES); idle(a, c)
        val i = item(2)
        val batch = LsOp(list, listOf(ListOp(i.item, 2, i)))
        assertTrue(c.r.n().setLevel(pc, LinkCapability.LISTS, LinkLevel.OFF).get(10, TimeUnit.SECONDS))
        assertNull(on(c) { c.h.sendOps(pc, batch, c.r.n().deskOut) })
        assertTrue(c.r.n().setLevel(pc, LinkCapability.LISTS, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        assertNotNull(on(c) { c.h.sendOps(pc, batch, c.r.n().deskOut) })
    }

    @Test fun aLeaveEndsItAndOnlyForThatRobot() {
        val push = FakePushServer(); val a = robot("a", push); val c = robot("c", push)
        val (pa, pc) = pair(a, c)
        send(a, pa, offer(1, 1)); idle(a, c)
        answer(c, DeskChoice.YES); idle(a, c)
        assertEquals(ListShareState.JOINED, rec(c, pc)!!.state)
        // A list id it never shared: nothing moves.
        send(a, pa, LsLeave("e".repeat(32))); idle(a, c)
        assertEquals(ListShareState.JOINED, rec(c, pc)!!.state)
        send(a, pa, LsLeave(list)); idle(a, c)
        assertNull(rec(c, pc))
        val i = item()
        pending(c).let { g -> on(c) { c.h.applied(g.map { it.id }) } }
        send(a, pa, LsOp(list, listOf(ListOp(i.item, 1, i)))); idle(a, c)
        assertEquals("nothing kept after it ended", 0, c.listBox.count())
    }

    @Test fun aFullBoxIsBusyAndNothingIsLost() {
        val push = FakePushServer(); val a = robot("a", push); val c = robot("c", push, boxMax = 1)
        val (pa, pc) = pair(a, c)
        send(a, pa, offer(1, 1)); idle(a, c)
        answer(c, DeskChoice.YES); idle(a, c)
        val i = item()
        send(a, pa, LsOp(list, listOf(ListOp(i.item, 1, i)))); idle(a, c)
        assertEquals("the part is in the box; the op was told busy", 1, c.listBox.count())
        assertEquals(ListShareState.JOINED, rec(c, pc)!!.state)
        assertEquals(1, pending(c).size)
    }

    @Test fun whatYouSendKeepsTheHoursLimit() {
        val (a, c, p) = two(); val (pa, pc) = p
        send(a, pa, offer(1, 1)); idle(a, c)
        answer(c, DeskChoice.YES); idle(a, c)
        fun batch(k: Int) = LsOp(list, items(k).map { ListOp(it.item, 1, it) })
        assertNotNull(on(c) { c.h.sendOps(pc, batch(40), c.r.n().deskOut) })
        assertNull(on(c) { c.h.sendOps(pc, batch(40), c.r.n().deskOut) })
        assertNotNull(on(c) { c.h.sendOps(pc, batch(20), c.r.n().deskOut) })
        assertEquals(0, on(c) { c.h.views(c.r.n().deskOut.now()) }.single().room)
    }

    @Test fun theyAreNeverOnTheDesksLists() {
        val (a, c, p) = two(); val (pa, _) = p
        on(a) { a.h.offerPart(pa, offer(1, 1), a.r.n().deskOut) }; idle(a, c)
        assertTrue(on(a) { a.r.desk!!.view()!!.lines }.isEmpty())
    }

    @Test fun aRecordSurvivesAReadOfTheState() {
        val (a, c, p) = two(); val (pa, _) = p
        on(a) { a.h.offerPart(pa, offer(1, 1), a.r.n().deskOut) }; idle(a, c)
        val r = rec(a, pa)!!
        assertEquals(r, ListShareRec.decode(r.encode()))
        assertTrue((app.heyrobot.core.link.LinkPurpose.LIST_SHARE.maxBytes) >= ListShareRec.MAX_BYTES)
    }
}
