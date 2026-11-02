// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.ListOp
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.ListItem as WireItem
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListItem
import app.heyrobot.core.lists.ListsCodec
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsJournal
import app.heyrobot.core.lists.ListsLimits
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsParse
import app.heyrobot.core.lists.ListsRefusal
import app.heyrobot.core.lists.NewItem
import app.heyrobot.core.lists.ListsShared
import app.heyrobot.core.lists.ListsWho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ListShareTest {
    private val now = 1_800_000_000_000L
    private val sam = "a".repeat(32)
    private val eve = "b".repeat(32)
    private val r1 = "1".repeat(32)
    private val r2 = "2".repeat(32)
    private val r9 = "9".repeat(32)
    private var n = 0
    private fun id(): String = "c" + (++n).toString(16).padStart(31, '0')
    private val listId = "d".repeat(32)
    private fun wire(item: String, text: String = "milk", rev: Long = 1, done: Boolean = false, qty: String? = null) = WireItem(item, rev, text, done, 0, qty)
    private fun offer(items: List<WireItem>, list: String = listId, name: String = "Trip") = LsOffer(list, name, 1, 1, items)

    /** A list joined from Sam, as an accepted offer leaves it. */
    private fun joined(vararg items: WireItem): ListsFile = ListShare.join(ListsFile(), sam, r1, offer(items.toList()), now).file
    private fun list(f: ListsFile) = f.list(ListsId.of(listId)!!)!!

    private fun op(f: ListsFile, o: ListOp, req: String = r2, pairing: String = sam, list: String = listId) =
        ListShare.apply(f, pairing, req, LsOp(list, listOf(o)), now + 1)

    @Test fun anOfferMakesAListThatIsNotYours() {
        val f = joined(wire(id()))
        val l = list(f)
        assertEquals(ListFrom.PEER, l.from)
        assertFalse(l.yours)
        assertEquals(listOf(sam), ListsShared.with(l))
        assertEquals(sam, ListsShared.origin(l))
        assertTrue(ListsShared.peerText(l))
        assertEquals(ItemBy.PEER, l.items.single().by)
        assertEquals(ItemHow.LINK, l.items.single().how)
        assertEquals(sam, l.items.single().hist.single().extra[ListsShared.WHO])
    }

    @Test fun whatAPeerWroteSurvivesTheFile() {
        val f = joined(wire(id()))
        val back = (ListsCodec.decode(ListsCodec.encode(f)) as ListsParse.Ok).file
        assertEquals(f, back)
        assertEquals(ListFrom.PEER, list(back).from)
        assertEquals(1L, list(back).items.single().extra[ListsShared.SENT])
    }

    @Test fun partsInAnyOrderMakeTheSameList() {
        val a = wire(id()); val b = wire(id(), "eggs")
        val p1 = LsOffer(listId, "Trip", 1, 2, listOf(a)); val p2 = LsOffer(listId, "Trip", 2, 2, listOf(b))
        val x = ListShare.join(ListShare.join(ListsFile(), sam, r1, p1, now).file, sam, r2, p2, now).file
        val y = ListShare.join(ListShare.join(ListsFile(), sam, r2, p2, now).file, sam, r1, p1, now).file
        assertEquals(list(x).items.map { it.id }.toSet(), list(y).items.map { it.id }.toSet())
        assertEquals(2, list(x).items.size)
    }

    @Test fun anOfferCanNeverReachAListOfYours() {
        val mine = ListsFile(listOf(ListsList(ListsId.of(listId)!!, name = "Private", made = now)))
        val r = ListShare.join(mine, sam, r1, offer(listOf(wire(id()))), now)
        assertSame(mine, r.file)
        assertFalse(r.changed)
    }

    @Test fun anOfferCanNotReachAListFromSomeoneElse() {
        val f = joined(wire(id()))
        val r = ListShare.join(f, eve, r2, offer(listOf(wire(id(), "from eve"))), now)
        assertSame(f, r.file)
    }

    @Test fun offersStopAtTheListCap() {
        val full = ListsFile((1..ListsLimits.MAX_LISTS).map { ListsList(ListsId.of(it.toString(16).padStart(32, 'e'))!!, name = "L$it", made = now) })
        assertFalse(ListShare.join(full, sam, r1, offer(listOf(wire(id()))), now).changed)
    }

    @Test fun anOpForAListNotSharedWithThatPeerChangesNothing() {
        val item = id()
        val f = joined(wire(item))
        val r = op(f, ListOp(item, 5, wire(item, "eve was here", 5)), pairing = eve)
        assertSame(f, r.file)
        assertEquals(1, r.refused)
        val unknown = op(f, ListOp(item, 5, wire(item, "x", 5)), list = "f".repeat(32))
        assertSame(f, unknown.file)
        // Their share ended: nothing more from them, though the list and its origin stay.
        val left = ListShare.left(f, ListsId.of(listId)!!)
        assertSame(left, op(left, ListOp(item, 5, wire(item, "late", 5))).file)
    }

    @Test fun anOpCanNotTakeAnItemFromAnotherList() {
        val secret = ListsId.of(id())!!
        val mine = ListsList(ListsId.of("e".repeat(32))!!, name = "Private", made = now,
            items = listOf(ListItem(secret, "my words", pos = 0, at = now, upd = now, by = ItemBy.YOU, how = ItemHow.TYPED)))
        val f = joined(wire(id())).let { it.copy(lists = it.lists + mine) }
        val r = op(f, ListOp(secret.hex, 9, wire(secret.hex, "overwritten", 9)))
        assertSame(f, r.file)
        assertEquals(1, r.refused)
        val gone = op(f, ListOp(secret.hex, 9))
        assertSame(f, gone.file)
    }

    @Test fun theOrderRuleDecides() {
        val item = id()
        val f = joined(wire(item, "first", 3))
        // An old counter loses.
        val old = op(f, ListOp(item, 2, wire(item, "old", 2)))
        assertSame(f, old.file); assertEquals(1, old.stale)
        // A higher one wins, and keeps the request that carried it.
        val hi = op(f, ListOp(item, 4, wire(item, "new", 4)), r2).file
        assertEquals("new", list(hi).items.single().text)
        assertEquals(r2, list(hi).items.single().extra[ListsShared.REQ])
        // A tie goes to the higher request id, whichever arrives first.
        val low = "0".repeat(32)
        assertEquals(1, op(hi, ListOp(item, 4, wire(item, "tie low", 4)), low).stale)
        assertEquals("tie high", list(op(hi, ListOp(item, 4, wire(item, "tie high", 4)), r9).file).items.single().text)
        // The same request again is no change.
        assertEquals(1, op(hi, ListOp(item, 4, wire(item, "new", 4)), r2).stale)
    }

    @Test fun aCounterCanNotJumpPastYourEdits() {
        val item = id()
        val f = joined(wire(item, "first", 1))
        assertEquals(1, op(f, ListOp(item, 1 + LinkOrder.MAX_JUMP + 1, wire(item, "pinned", 1 + LinkOrder.MAX_JUMP + 1))).refused)
        val fresh = id()
        assertEquals(1, op(f, ListOp(fresh, LinkOrder.MAX_JUMP + 1, wire(fresh, "pinned new", LinkOrder.MAX_JUMP + 1))).refused)
        assertTrue(op(f, ListOp(item, 1 + LinkOrder.MAX_JUMP, wire(item, "ok", 1 + LinkOrder.MAX_JUMP))).changed)
    }

    @Test fun aTieAgainstYourUnsentChangeEndsTheSameOnBothPhones() {
        val item = id(); val listI = ListsId.of(listId)!!; val itemI = ListsId.of(item)!!
        val f = joined(wire(item, "first", 1))
        val mine = (ListsEdits.edit(f, listI, itemI, "mine", app.heyrobot.core.lists.ListsWho(ItemBy.YOU, ItemHow.TYPED, now + 1)) as ListsEdit.Done).file
        val rev = list(mine).items.single().rev
        // Theirs at the same counter, under an id lower than what it would need to win.
        val r = op(mine, ListOp(item, rev, wire(item, "theirs", rev)), "0".repeat(32))
        assertEquals(1, r.stale)
        val after = list(r.file).items.single()
        assertEquals("mine", after.text)
        assertEquals(rev + 1, after.rev)
        assertTrue(ListShare.owed(after))
    }

    @Test fun aHugeItemIsRefusedNotACrash() {
        val a = id(); val b = id(); val c = id(); val d = id()
        val f = joined(wire(a))
        val emoji = "😀".repeat(120) // 120 characters on the wire, 240 in Lists' count
        val longQty = wire(b, "ok", qty = "😀".repeat(16))
        val r = ListShare.apply(f, sam, r2, LsOp(listId, listOf(
            ListOp(c, 1, wire(c, emoji)), ListOp(b, 1, longQty), ListOp(d, 1, wire(d, "fine")))), now)
        assertEquals(2, r.refused); assertEquals(1, r.applied)
        assertNull(list(r.file).item(ListsId.of(c)!!))
    }

    @Test fun aSecretIsRefusedAsYourOwnWouldBe() {
        val a = id()
        val r = ListShare.apply(joined(wire(id())), sam, r2, LsOp(listId, listOf(ListOp(a, 1, wire(a, "4111 1111 1111 1111")))), now)
        assertEquals(1, r.refused)
    }

    @Test fun aPeerStopsAt400NewItems() {
        var f = joined(wire(id()))
        repeat(13) { f = ListShare.apply(f, sam, r2, LsOp(listId, List(40) { val i = id(); ListOp(i, 1, wire(i)) }), now).file }
        val size = list(f).items.size
        assertEquals("400 new ones, and the offer's one isn't counted", ListShare.PEER_ITEMS + 1, size)
        val more = id()
        assertEquals(1, ListShare.apply(f, sam, r2, LsOp(listId, listOf(ListOp(more, 1, wire(more)))), now).refused)
    }

    @Test fun aRemovalBeforeItsAddKeepsTheItemGone() {
        val item = id()
        val f = joined(wire(id()))
        val gone = op(f, ListOp(item, 2)).file
        assertNotNull(list(gone).item(ListsId.of(item)!!)!!.gone)
        assertEquals(1, op(gone, ListOp(item, 1, wire(item, "late add", 1))).stale)
        assertEquals(1, list(gone).live.size)
    }

    @Test fun whatYouChangeIsOwedAndStopsBeingOwedOnceSent() {
        val item = id(); val listI = ListsId.of(listId)!!; val itemI = ListsId.of(item)!!
        val f = joined(wire(item))
        assertTrue(ListShare.owedOps(list(f), 40).isEmpty())
        val mine = (ListsEdits.tick(f, listI, itemI, app.heyrobot.core.lists.ListsWho(ItemBy.YOU, ItemHow.TYPED, now)) as ListsEdit.Done).file
        val ops = ListShare.owedOps(list(mine), 40)
        assertEquals(item, ops.single().item)
        assertTrue(ops.single().state!!.done)
        val sent = ListShare.sent(mine, listI, ops, r9)
        assertTrue(ListShare.owedOps(list(sent), 40).isEmpty())
        assertEquals(r9, list(sent).items.single().extra[ListsShared.REQ])
        // A removal goes as a removal, and a batch is never over 40.
        val many = (1..50).fold(sent) { acc, _ -> val i = id(); acc.with(list(acc).copy(items = list(acc).items + ListItem(ListsId.of(i)!!, "x", pos = 0, at = now, upd = now, by = ItemBy.YOU, how = ItemHow.TYPED))) }
        assertEquals(40, ListShare.owedOps(list(many), 500).size)
        assertEquals(7, ListShare.owedOps(list(many), 7).size)
    }

    @Test fun offersSplitIn40sAndAWholeListFits() {
        val items = (0 until 500).map { ListItem(ListsId.of(it.toString(16).padStart(32, 'c'))!!, "😀".repeat(60), "😀".repeat(8), pos = it.toLong(), at = now, upd = now, by = ItemBy.YOU, how = ItemHow.TYPED) }
        val l = ListsList(ListsId.of(listId)!!, name = "Trip", made = now, items = items)
        val parts = ListShare.parts(l, "Trip")
        assertEquals(13, parts.size)
        assertEquals(500, parts.sumOf { it.items.size })
        assertTrue(parts.all { it.items.size <= 40 && it.parts == 13L })
        // The largest part, the largest op and each one's request fit a request on the wire.
        parts.forEach { assertNotNull(LinkRequests.encode(LinkRequest.of(it, now / 1000, "en"), LinkRequests.LIVE)) }
        val ops = ListShare.owedOps(l, 40)
        assertNotNull(LinkRequests.encode(LinkRequest.of(LsOp(listId, ops), now / 1000, "en"), LinkRequests.LIVE))
    }

    @Test fun anEmptyListStillMakesOneOffer() {
        assertEquals(1, ListShare.parts(ListsList(ListsId.of(listId)!!, name = "Trip", made = now), "Trip").size)
    }

    @Test fun aSharedRemovalWaitsForTheOtherRobot() {
        val item = id(); val listI = ListsId.of(listId)!!
        val f = joined(wire(item))
        val day = 86_400_000L
        val removed = (ListsEdits.remove(f, listI, listOf(ListsId.of(item)!!), app.heyrobot.core.lists.ListsWho(ItemBy.YOU, ItemHow.TYPED, now)) as ListsEdit.Done).file
        assertNotNull("not told yet, past 30 days", list(ListsEdits.purge(removed, now + 31 * day)).item(ListsId.of(item)!!))
        assertNull("not told, past 90 days", list(ListsEdits.purge(removed, now + 91 * day)).item(ListsId.of(item)!!))
        val told = ListShare.sent(removed, listI, ListShare.owedOps(list(removed), 40), r9)
        assertNull("told, past 30 days", list(ListsEdits.purge(told, now + 31 * day)).item(ListsId.of(item)!!))
        val left = ListShare.left(removed, listI)
        assertNull("not shared any more", list(ListsEdits.purge(left, now + 31 * day)).item(ListsId.of(item)!!))
    }

    @Test fun aSharedListIsNotInTheJournalsSnapshot() {
        val mine = ListsList(ListsId.of("e".repeat(32))!!, name = "Mine", made = now)
        val shared = ListShare.shared(ListsFile(listOf(mine)), mine.id, sam)
        assertEquals(1, ListsJournal.snapshot(ListsFile(listOf(mine))).lists.size)
        assertEquals(0, ListsJournal.snapshot(shared).lists.size)
        assertEquals(0, ListsJournal.snapshot(joined(wire(id()))).lists.size)
        // Words of a person stay in your list after you stop sharing it: still not read on the lock screen.
        val left = ListShare.left(ListShare.join(ListsFile(), sam, r1, offer(listOf(wire(id()))), now).file, ListsId.of(listId)!!)
        assertEquals(0, ListsJournal.snapshot(left).lists.size)
    }

    @Test fun theModelNeverSendsToAPersonWithoutYourTap() {
        val mine = ListsList(ListsId.of("e".repeat(32))!!, name = "Mine", made = now)
        val shared = ListShare.shared(ListsFile(listOf(mine)), mine.id, sam).lists.single()
        assertTrue(ListsShared.modelNeedsNoCard(mine, untrusted = false, yourWords = true))
        assertFalse(ListsShared.modelNeedsNoCard(shared, untrusted = false, yourWords = true))
        assertFalse(ListsShared.modelNeedsNoCard(list(joined(wire(id()))), untrusted = false, yourWords = true))
    }

    @Test fun theRecordRoundTripsAndRefusesLies() {
        val ids = List(13) { id() }
        val rec = ListShareRec(listId, sam, ListShareState.GATHERING, 5, 13, 0b1_1111, 200, ListShareRec.hash("Trip"), ids, true)
        assertEquals(rec, ListShareRec.decode(rec.encode()))
        assertTrue(rec.encode().size <= ListShareRec.MAX_BYTES)
        assertEquals(5, rec.partsSeen)
        assertNull(ListShareRec.decode(rec.encode() + 0))
        assertNull(ListShareRec.decode(rec.encode().copyOf(20)))
        val bad = rec.encode().also { it[36] = 99 }
        assertNull(ListShareRec.decode(bad))
        assertEquals(1, ListShareView.decodeAll(ListShareView.encodeAll(listOf(ListShareView(rec, 60))))!!.size)
        assertNull(ListShareView.decodeAll(byteArrayOf(0, 1)))
    }

    // ------------------------------------------------------------------ what a pass does

    private fun view(list: String, pairing: String, state: ListShareState, at: Long = now / 1000, room: Int = 60, lost: Boolean = false) =
        ListShareView(ListShareRec(list, pairing, state, at, lost = lost), room)

    @Test fun aSharedListWithNoRecordHasEndedAndItsCopyStays() {
        val f = joined(wire(id()))
        val p = ListShare.plan(f, emptyList(), emptyList(), now / 1000)
        assertEquals(listOf(ListsId.of(listId)!! to sam), p.ended)
        assertTrue(p.send.isEmpty())
        val left = ListShare.left(f, ListsId.of(listId)!!)
        assertTrue(ListsShared.with(list(left)).isEmpty())
        assertEquals(1, list(left).items.size)
        // Still another person's words: the lock screen and the model go on treating it so.
        assertTrue(ListsShared.peerText(list(left)))
        assertTrue(ListShare.plan(left, emptyList(), emptyList(), now / 1000).ended.isEmpty())
    }

    @Test fun aRecordWhoseListIsntSharedWithItGoes() {
        val f = joined(wire(id()))
        val mine = view(listId, sam, ListShareState.JOINED)
        val other = view(listId, "b".repeat(32), ListShareState.JOINED)
        val missing = view("e".repeat(32), sam, ListShareState.JOINED)
        val p = ListShare.plan(f, listOf(mine, other, missing), listOf(mine), now / 1000)
        assertEquals(listOf(other.rec, missing.rec), p.leave)
    }

    @Test fun anOfferNotYetMarkedOnTheListIsGivenTime() {
        val s = now / 1000
        val young = view("e".repeat(32), sam, ListShareState.OFFERED, at = s - ListShare.OFFER_GRACE_S)
        assertTrue(ListShare.plan(ListsFile(), listOf(young), listOf(young), s).leave.isEmpty())
        val old = view("e".repeat(32), sam, ListShareState.OFFERED, at = s - ListShare.OFFER_GRACE_S - 1)
        assertEquals(listOf(old.rec), ListShare.plan(ListsFile(), listOf(old), listOf(old), s).leave)
        // A card you haven't answered holds no list: it stays.
        val card = view("e".repeat(32), sam, ListShareState.CARD)
        assertTrue(ListShare.plan(ListsFile(), listOf(card), listOf(card), s).leave.isEmpty())
    }

    @Test fun onlyAJoinedRecordSendsAndOnlyWhatItsRoomAllows() {
        val a = id(); val b = id()
        val f = ListShare.shared(joined(wire(a), wire(b)), ListsId.of(listId)!!, sam)
        val l = list(f)
        val owed = f.with(l.copy(items = l.items.map { it.copy(rev = 5) }))
        fun plan(v: ListShareView) = ListShare.plan(owed, listOf(v), listOf(v), now / 1000)
        assertTrue(plan(view(listId, sam, ListShareState.OFFERED)).send.isEmpty())
        assertTrue(plan(view(listId, sam, ListShareState.JOINED, room = 0)).send.isEmpty())
        assertEquals(1, plan(view(listId, sam, ListShareState.JOINED, room = 1)).send.single().ops.size)
        assertEquals(2, plan(view(listId, sam, ListShareState.JOINED)).send.single().ops.size)
        // Lost: everything's owed again first.
        val lost = plan(view(listId, sam, ListShareState.JOINED, lost = true))
        assertTrue(lost.send.isEmpty())
        assertEquals(1, lost.lost.size)
        assertTrue(list(ListShare.forgetSent(f, ListsId.of(listId)!!)).items.all(ListShare::owed))
    }

    @Test fun aTiedChangeOfYoursKeepsItsHigherCounter() {
        val a = id()
        val f = joined(wire(a))
        val me = ListsWho(ItemBy.YOU, ItemHow.CARD, now)
        val mine = (ListsEdits.tick(f, ListsId.of(listId)!!, ListsId.of(a)!!, me) as ListsEdit.Done).file
        val r = op(mine, ListOp(a, 2, wire(a, rev = 2)))
        assertTrue("the bump is written", r.changed)
        assertEquals(3L, list(r.file).item(ListsId.of(a)!!)!!.rev)
    }

    @Test fun whatTheLinkRefusesIsCleanedOnTheWire() {
        val a = id(); val b = id(); val c = id()
        val base = joined(wire(a), wire(b), wire(c))
        val l0 = list(base)
        fun at(s: String, q: String?) = { x: ListItem -> x.copy(text = s, qty = q, rev = 2) }
        val l = l0.copy(items = listOf(at("cafe\u0301", "1\uFEFF")(l0.items[0]), at("\uFEFFflag", null)(l0.items[1]), at("\uFEFF", null)(l0.items[2])))
        val ops = ListShare.owedOps(l, 40)
        assertEquals("nothing left to carry: it stays here, and blocks nothing", 2, ops.size)
        for (o in ops) {
            assertTrue(o.state!!.text, LinkText.ok(o.state!!.text, RequestCaps.LIST_ITEM))
            o.state!!.qty?.let { assertTrue(it, LinkText.ok(it, RequestCaps.LIST_QTY)) }
        }
        assertEquals("caf\u00E9", ops.first { it.item == a }.state!!.text)
        val part = ListShare.parts(l, "Tr\uFEFFip").single()
        assertEquals("Trip", part.name)
        assertEquals(2, part.items.size)
        assertTrue("a name with nothing left isn't offered", ListShare.parts(l, "\uFEFF").isEmpty())
    }

    @Test fun sharingAgainCarriesARemoval() {
        val a = id(); val b = id()
        val id = ListsId.of(listId)!!
        val first = ListShare.shared(joined(wire(a), wire(b)), id, sam)
        val me = ListsWho(ItemBy.YOU, ItemHow.CARD, now)
        val removed = (ListsEdits.remove(ListShare.left(first, id), id, listOf(ListsId.of(a)!!), me) as ListsEdit.Done).file
        val again = list(ListShare.shared(removed, id, sam))
        assertTrue("still owed", ListShare.owed(again.item(ListsId.of(a)!!)!!))
        assertFalse(ListShare.owed(again.item(ListsId.of(b)!!)!!))
        val op = ListShare.owedOps(again, 40).single()
        assertEquals(a, op.item)
        assertNull(op.state)
    }

    @Test fun aPeersNewItemsAndGrowthLeaveYouRoom() {
        var f = joined(wire(id()))
        var refused = 0
        repeat(11) { b ->
            val r = ListShare.apply(f, sam, (b + 16).toString(16).padStart(32, '3'), LsOp(listId, List(40) { val x = id(); ListOp(x, 1, wire(x)) }), now + 1)
            f = r.file; refused += r.refused
        }
        assertEquals("400 new ones, and the offer's one isn't counted", ListShare.PEER_ITEMS + 1, list(f).items.size)
        assertTrue(refused > 0)
        val pad = "x".repeat(ListShare.PEER_FILE)
        val big = f.copy(extra = f.extra + ("pad" to pad))
        val n = id()
        val grows = ListShare.apply(big.copy(lists = listOf(list(f).copy(items = list(f).items.take(10)))), sam, r9, LsOp(listId, listOf(ListOp(n, 1, wire(n)))), now + 1)
        assertEquals("past the peer's share of the file it's refused", 1, grows.refused)
        assertEquals("and you're told", 1, grows.full)
        assertNull(list(grows.file).item(ListsId.of(n)!!))
        assertTrue(ListsShared.PEER_FULL in list(grows.file).extra)
    }

    @Test fun noRoomForAYesIsReported() {
        val own = (0 until ListsLimits.MAX_LISTS).map { ListsList(ListsId.of(id())!!, name = "L" + it, made = now) }
        val full = ListsFile(own)
        val v = view(listId, sam, ListShareState.JOINED)
        val p = ListShare.plan(full, listOf(v), listOf(v), now / 1000)
        assertEquals(listOf(v.rec), p.leave)
        assertEquals(listOf(v.rec), p.noRoom)
        val room = ListShare.plan(ListsFile(own.drop(1)), listOf(v), listOf(v), now / 1000)
        assertEquals(listOf(v.rec), room.leave)
        assertTrue(room.noRoom.isEmpty())
    }

    private fun fillTo(items: Int): ListsFile {
        val f = joined(wire(id()))
        val more = List(items - 1) { ListItem(ListsId.of(id())!!, "x", pos = 0, at = now, upd = now, by = ItemBy.PEER, how = ItemHow.LINK, rev = 2, gone = now) }
        return f.with(list(f).copy(items = list(f).items + more))
    }

    @Test fun aNewItemAt500EntriesIsRefusedAndMarked() {
        val f = fillTo(ListsLimits.MAX_ITEMS)
        val n = id()
        val r = op(f, ListOp(n, 1, wire(n)))
        assertEquals(1, r.refused); assertEquals(1, r.full); assertEquals(0, r.applied)
        assertTrue("the mark has to be saved", r.changed)
        assertNull(list(r.file).item(ListsId.of(n)!!))
        assertTrue(ListsShared.PEER_FULL in list(r.file).extra)
    }

    @Test fun anItemPast400LiveIsMarkedAndSurvivesTheFile() {
        var f = joined(wire(id()))
        repeat(10) { f = ListShare.apply(f, sam, r2, LsOp(listId, List(40) { val i = id(); ListOp(i, 1, wire(i)) }), now).file }
        assertFalse("400 fit: nothing to say", ListsShared.PEER_FULL in list(f).extra)
        val n = id()
        val r = op(f, ListOp(n, 1, wire(n)))
        assertEquals(1, r.full)
        val back = (ListsCodec.decode(ListsCodec.encode(r.file)) as ListsParse.Ok).file
        assertTrue(ListsShared.PEER_FULL in list(back).extra)
        assertFalse("read and dismissed", ListsShared.PEER_FULL in list(ListShare.fullSeen(r.file, ListsId.of(listId)!!)).extra)
    }

    @Test fun onlyRoomCountsAsFull() {
        val f = fillTo(ListsLimits.MAX_ITEMS)
        val gone = id()
        val removal = op(f, ListOp(gone, 1))
        assertEquals("a removal of what isn't here loses nothing", 0, removal.full)
        assertFalse(removal.changed)
        val roomy = joined(wire(id()))
        val jump = id()
        assertEquals("a counter too far isn't about room", 0, op(roomy, ListOp(jump, LinkOrder.MAX_JUMP + 1, wire(jump, rev = LinkOrder.MAX_JUMP + 1))).full)
        val secret = id()
        assertEquals("nor are words refused", 0, op(roomy, ListOp(secret, 1, wire(secret, "4111 1111 1111 1111"))).full)
        assertFalse(ListsShared.PEER_FULL in list(roomy).extra)
    }

    @Test fun whatAPeerTakesCanBePutBack() {
        val a = id(); val b = id()
        val lid = ListsId.of(listId)!!
        val me = ListsWho(ItemBy.YOU, ItemHow.TYPED, now)
        val f0 = joined(wire(a, text = "eggs"), wire(b))
        val f = (ListsEdits.tick((ListsEdits.tick(f0, lid, ListsId.of(a)!!, me) as ListsEdit.Done).file, lid, ListsId.of(b)!!, me) as ListsEdit.Done).file
        val sent = ListShare.sent(f, lid, ListShare.owedOps(list(f), 40), r1)
        assertTrue(ListShare.takenByPeer(list(sent)).isEmpty())
        val gone = op(sent, ListOp(a, 3))
        assertEquals(1, gone.taken)
        val rewrote = op(gone.file, ListOp(b, 3, wire(b, text = "bread", rev = 3, done = true)), req = r9)
        assertEquals(1, rewrote.taken)
        val rows = ListShare.takenByPeer(list(rewrote.file))
        assertEquals(setOf(a, b), rows.map { it.id.hex }.toSet())
        assertEquals("milk", list(rewrote.file).item(ListsId.of(b)!!)!!.extra[ListsShared.WAS])
        var back = (ListShare.putBack(rewrote.file, lid, ListsId.of(a)!!, me) as ListsEdit.Done).file
        back = (ListShare.putBack(back, lid, ListsId.of(b)!!, me) as ListsEdit.Done).file
        val l = list(back)
        assertTrue(l.item(ListsId.of(a)!!)!!.live)
        assertEquals("milk", l.item(ListsId.of(b)!!)!!.text)
        assertTrue(ListShare.takenByPeer(l).isEmpty())
        assertTrue("your putting back goes to them", ListShare.owed(l.item(ListsId.of(a)!!)!!) && ListShare.owed(l.item(ListsId.of(b)!!)!!))
    }

    @Test fun aPeerChangingOnlyItsOwnAdditionsIsNoNews() {
        val a = id()
        val r = op(joined(wire(a)), ListOp(a, 2))
        assertEquals(0, r.taken)
        assertTrue(ListShare.takenByPeer(list(r.file)).isEmpty())
    }

    @Test fun anAcceptedOfferDoesNotUseUpAPeersRoom() {
        var f = ListShare.join(ListsFile(), sam, r1, offer(List(400) { wire(id()) }), now).file
        val x = id()
        val r = ListShare.apply(f, sam, r2, LsOp(listId, listOf(ListOp(x, 1, wire(x)))), now)
        assertEquals(1, r.applied)
        assertEquals(0, r.refused)
        f = r.file
        assertEquals(401, list(f).items.size)
    }

    @Test fun tickingYourItemsDoesNotUseUpAPeersRoom() {
        val ids = List(400) { id() }
        val items = ids.mapIndexed { k, x -> ListItem(ListsId.of(x)!!, "thing $k", null, false, k.toLong(), now, now, ItemBy.YOU, ItemHow.TYPED, 1, null, emptyList()) }
        val f = ListsFile(listOf(ListsList(ListsId.of(listId)!!, name = "Trip", made = now, items = items, extra = mapOf(ListsShared.SHARED to listOf(sam)))))
        val ticked = ListShare.apply(f, sam, r2, LsOp(listId, ids.mapIndexed { k, x -> ListOp(x, 2, wire(x, text = "thing $k", rev = 2, done = true)) }), now)
        assertEquals(400, ticked.applied)
        val x = id()
        val r = ListShare.apply(ticked.file, sam, r9, LsOp(listId, listOf(ListOp(x, 1, wire(x)))), now)
        assertEquals(1, r.applied)
        assertEquals(0, r.refused)
    }

    @Test fun whatAPeerRemovedLeavesRoomAndCanNotComeBackPastTheCap() {
        val offered = id()
        var f = joined(wire(offered))
        val theirs = List(400) { id() }
        f = ListShare.apply(f, sam, r2, LsOp(listId, theirs.map { ListOp(it, 1, wire(it)) }), now).file
        f = ListShare.apply(f, sam, r2, LsOp(listId, theirs.take(50).map { ListOp(it, 2) }), now).file
        val more = List(50) { id() }
        val added = ListShare.apply(f, sam, r2, LsOp(listId, more.map { ListOp(it, 1, wire(it)) }), now)
        assertEquals("a removal gives its room back", 50, added.applied)
        f = added.file
        val comesBack = ListShare.apply(f, sam, r9, LsOp(listId, listOf(ListOp(theirs[0], 3, wire(theirs[0], rev = 3)))), now)
        assertEquals("the cap holds for what returns too, or a peer could fill your list", 1, comesBack.refused)
        assertFalse(list(comesBack.file).item(ListsId.of(theirs[0])!!)!!.live)
        f = ListShare.apply(f, sam, r2, LsOp(listId, listOf(ListOp(offered, 2))), now).file
        val offerBack = ListShare.apply(f, sam, r9, LsOp(listId, listOf(ListOp(offered, 3, wire(offered, rev = 3)))), now)
        assertEquals("the offer isn't limited", 1, offerBack.applied)
    }

    @Test fun eightChangesOfTheirsDoNotHideARewriteOfYours() {
        val a = id(); val lid = ListsId.of(listId)!!; val aid = ListsId.of(a)!!
        val who = ListsWho(ItemBy.YOU, ItemHow.TYPED, now)
        val f0 = (ListsEdits.tick(joined(wire(a)), lid, aid, who) as ListsEdit.Done).file
        val mine = ListShare.sent(f0, lid, ListShare.owedOps(list(f0), 40), r1)
        var f = mine
        repeat(8) { k -> f = op(f, ListOp(a, 3L + k, wire(a, rev = 3L + k, done = k % 2 == 1)), req = r9).file }
        assertTrue("your own change has left the history", list(f).item(aid)!!.hist.all { it.by == ItemBy.PEER })
        val back = (ListsCodec.decode(ListsCodec.encode(f)) as ListsParse.Ok).file
        val rewrote = op(back, ListOp(a, 11, wire(a, text = "junk", rev = 11)), req = r9)
        assertEquals(1, rewrote.taken)
        assertEquals(listOf(a), ListShare.takenByPeer(list(rewrote.file)).map { it.id.hex })
        val put = (ListShare.putBack(rewrote.file, lid, aid, who) as ListsEdit.Done).file
        assertEquals("milk", list(put).item(aid)!!.text)
        val removed = op(back, ListOp(a, 11), req = r9)
        assertEquals(1, removed.taken)
        assertEquals(listOf(a), ListShare.takenByPeer(list(removed.file)).map { it.id.hex })
    }

    @Test fun putBackAfterARewriteAndARemovalBringsBackYourWords() {
        val a = id(); val lid = ListsId.of(listId)!!; val aid = ListsId.of(a)!!
        val who = ListsWho(ItemBy.YOU, ItemHow.TYPED, now)
        val f0 = (ListsEdits.tick(joined(wire(a)), lid, aid, who) as ListsEdit.Done).file
        val mine = ListShare.sent(f0, lid, ListShare.owedOps(list(f0), 40), r1)
        val rewrote = op(mine, ListOp(a, 3, wire(a, text = "junk", rev = 3, done = true)))
        val gone = op(rewrote.file, ListOp(a, 4), req = r9)
        assertEquals(listOf(a), ListShare.takenByPeer(list(gone.file)).map { it.id.hex })
        assertEquals("milk", list(gone.file).item(aid)!!.extra[ListsShared.WAS])
        val back = ListShare.putBack(gone.file, lid, aid, who) as ListsEdit.Done
        val i = list(back.file).item(aid)!!
        assertTrue(back.changed)
        assertTrue(i.live)
        assertEquals("milk", i.text)
        assertTrue(ListShare.takenByPeer(list(back.file)).isEmpty())
        assertEquals("milk", ListShare.owedOps(list(back.file), 40).single().state!!.text)
    }

    @Test fun aRemovalAfterYourOwnEditPutsBackYourLatestWords() {
        val a = id(); val lid = ListsId.of(listId)!!; val aid = ListsId.of(a)!!
        val who = ListsWho(ItemBy.YOU, ItemHow.TYPED, now)
        val f0 = (ListsEdits.tick(joined(wire(a)), lid, aid, who) as ListsEdit.Done).file
        val mine = ListShare.sent(f0, lid, ListShare.owedOps(list(f0), 40), r1)
        val rewrote = op(mine, ListOp(a, 3, wire(a, text = "junk", rev = 3, done = true))).file
        val put = (ListShare.putBack(rewrote, lid, aid, who) as ListsEdit.Done).file
        val edited = (ListsEdits.edit(put, lid, aid, "bread", who) as ListsEdit.Done).file
        val sent = ListShare.sent(edited, lid, ListShare.owedOps(list(edited), 40), r1)
        val gone = op(sent, ListOp(a, list(sent).item(aid)!!.rev + 1), req = r9)
        val back = (ListShare.putBack(gone.file, lid, aid, who) as ListsEdit.Done).file
        assertEquals("bread", list(back).item(aid)!!.text)
        assertTrue(list(back).item(aid)!!.live)
    }

    @Test fun aPutBackThatCanNotBringBackYourWordsChangesNothing() {
        val a = id(); val lid = ListsId.of(listId)!!; val aid = ListsId.of(a)!!
        val who = ListsWho(ItemBy.YOU, ItemHow.TYPED, now)
        val f0 = (ListsEdits.tick(joined(wire(a)), lid, aid, who) as ListsEdit.Done).file
        val mine = ListShare.sent(f0, lid, ListShare.owedOps(list(f0), 40), r1)
        val rewrote = op(mine, ListOp(a, 3, wire(a, text = "junk", rev = 3, done = true))).file
        val gone = op(rewrote, ListOp(a, 4), req = r9).file
        val again = (ListsEdits.add(gone, lid, listOf(NewItem("milk", null)), who) { ListsId.of(id())!! } as ListsEdit.Done).file
        assertEquals(ListsEdit.No(ListsRefusal.ALREADY), ListShare.putBack(again, lid, aid, who))
        assertEquals(listOf(a), ListShare.takenByPeer(list(again)).map { it.id.hex })
    }

    @Test fun aRewriteThatAlsoTicksStillGetsPutBack() {
        val a = id(); val lid = ListsId.of(listId)!!; val aid = ListsId.of(a)!!
        val who = ListsWho(ItemBy.YOU, ItemHow.TYPED, now)
        val f0 = (ListsEdits.edit(joined(wire(a)), lid, aid, "oat milk", who) as ListsEdit.Done).file
        val mine = ListShare.sent(f0, lid, ListShare.owedOps(list(f0), 40), r1)
        val rewrote = op(mine, ListOp(a, 3, wire(a, text = "junk", rev = 3, done = true)))
        assertEquals(1, rewrote.taken)
        assertEquals(listOf(a), ListShare.takenByPeer(list(rewrote.file)).map { it.id.hex })
        assertEquals("oat milk", list((ListShare.putBack(rewrote.file, lid, aid, who) as ListsEdit.Done).file).item(aid)!!.text)
        val ticked = op(rewrote.file, ListOp(a, 4, wire(a, text = "junk", rev = 4, done = false)), req = r9)
        assertEquals("a change that keeps the words keeps the row", listOf(a), ListShare.takenByPeer(list(ticked.file)).map { it.id.hex })
    }

    /** Two phones on one list: this one, where you wrote "oat milk" and the other Robot has it, and theirs. */
    private inner class Phones {
        val a = id(); val lid = ListsId.of(listId)!!; val aid = ListsId.of(a)!!
        val who = ListsWho(ItemBy.YOU, ItemHow.TYPED, now)
        var mine = joined(wire(a)); var theirs = joined(wire(a))
        var reqs = 0
        var taken = 0
        fun req() = "e" + (++reqs).toString(16).padStart(31, '0')
        fun done(e: ListsEdit) = (e as ListsEdit.Done).file
        fun send() { val ops = ListShare.owedOps(list(mine), 40); val r = req(); theirs = ListShare.apply(theirs, sam, r, LsOp(listId, ops), now + 1).file; mine = ListShare.sent(mine, lid, ops, r) }
        fun get(edit: (ListsFile) -> ListsEdit) {
            theirs = done(edit(theirs))
            val ops = ListShare.owedOps(list(theirs), 40); val r = req()
            val got = ListShare.apply(mine, sam, r, LsOp(listId, ops), now + 1)
            mine = got.file; theirs = ListShare.sent(theirs, lid, ops, r); taken = got.taken
        }
        fun theyEdit(words: String) = get { ListsEdits.edit(it, lid, aid, words, who) }
        fun theyTick() = get { ListsEdits.tick(it, lid, aid, who) }
        fun theyRemove() = get { ListsEdits.remove(it, lid, listOf(aid), who) }
        fun theyRestore(words: String) = get { ListsEdits.edit(done(ListsEdits.restore(it, lid, listOf(aid), who)), lid, aid, words, who) }
        fun rows() = ListShare.takenByPeer(list(mine)).map { it.id.hex }
        fun was() = list(mine).item(aid)!!.extra[ListsShared.WAS]
        fun putBackAndAgree(words: String) {
            mine = done(ListShare.putBack(mine, lid, aid, who))
            send()
            val x = list(mine).item(aid)!!; val y = list(theirs).item(aid)!!
            assertEquals(words, x.text)
            assertEquals(listOf(x.text, x.done, x.live, x.rev), listOf(y.text, y.done, y.live, y.rev))
            assertTrue(rows().isEmpty())
            assertTrue(ListShare.owedOps(list(mine), 40).isEmpty() && ListShare.owedOps(list(theirs), 40).isEmpty())
        }
        init { mine = done(ListsEdits.edit(mine, lid, aid, "oat milk", who)); send() }
    }

    @Test fun aTickWithTheSameWordsKeepsPutBack() = with(Phones()) {
        theyEdit("junk")
        assertEquals(1, taken)
        theyTick()
        assertEquals("nothing new has gone: no second notice", 0, taken)
        assertEquals(listOf(a), rows())
        assertEquals("oat milk", was())
        putBackAndAgree("oat milk")
        assertTrue("the tick stays: only the words were yours", list(mine).item(aid)!!.done)
    }

    @Test fun theirWordsGoingBackToYoursEndPutBack() = with(Phones()) {
        theyEdit("junk")
        theyEdit("oat milk")
        assertEquals("nothing of yours is hidden", 0, taken)
        assertTrue(rows().isEmpty())
        assertEquals(null, was())
    }

    @Test fun yourOwnLaterEditEndsPutBackEvenIfTheyTickAfter() = with(Phones()) {
        theyEdit("junk")
        mine = done(ListsEdits.edit(mine, lid, aid, "bread", who)); send()
        theyTick()
        assertEquals(0, taken)
        assertTrue(rows().isEmpty())
        assertEquals(null, was())
    }

    @Test fun aRestoreWithNewWordsTellsYouAndKeepsYourWords() = with(Phones()) {
        theyRemove()
        assertEquals(1, taken)
        theyRestore("junk")
        assertEquals(1, taken)
        assertEquals(listOf(a), rows())
        assertEquals("oat milk", was())
        putBackAndAgree("oat milk")
    }

    @Test fun aRewriteARemovalAndARestoreWithNewerWordsStillPutBackYours() = with(Phones()) {
        theyEdit("junk")
        theyRemove()
        theyRestore("more junk")
        assertEquals(1, taken)
        assertEquals("oat milk", was())
        putBackAndAgree("oat milk")
    }

    @Test fun aRewriteARemovalAndARestoreOfTheSameWordsKeepsPutBackQuietly() = with(Phones()) {
        theyEdit("junk")
        theyRemove()
        get { ListsEdits.restore(it, lid, listOf(aid), who) }
        assertEquals(0, taken)
        assertEquals(listOf(a), rows())
        putBackAndAgree("oat milk")
    }

    @Test fun aRestoreWithYourOwnWordsIsNothingToTellYou() = with(Phones()) {
        theyEdit("junk")
        theyRemove()
        theyRestore("oat milk")
        assertEquals(0, taken)
        assertTrue(rows().isEmpty())
        assertEquals(null, was())
        theyRemove()
        get { ListsEdits.restore(it, lid, listOf(aid), who) }
        assertEquals(0, taken)
        assertTrue(rows().isEmpty())
    }

    @Test fun whatYouRemovedYourselfIsNotTakenWhenTheyRestoreIt() = with(Phones()) {
        mine = done(ListsEdits.remove(mine, lid, listOf(aid), who)); send()
        theyRestore("junk")
        assertEquals(0, taken)
        assertTrue(rows().isEmpty())
        assertEquals(null, was())
    }

    @Test fun aRemovalOfSomethingNeverSeenLeavesNoWordsToPutBack() {
        val a = id(); val b = id()
        val f = op(joined(wire(a)), ListOp(b, 2)).file
        val back = op(f, ListOp(b, 3, wire(b, text = "bread", rev = 3)), req = r9)
        assertEquals(0, back.taken)
        assertEquals(null, list(back.file).item(ListsId.of(b)!!)!!.extra[ListsShared.WAS])
    }

    @Test fun whoseWordsAListHolds() {
        val f = joined(wire(id()))
        assertEquals(setOf(sam), ListsShared.peers(list(f)))
        val locked = ListsShared.forLockScreen(f)
        assertTrue(locked.lists.isEmpty())
    }
}
