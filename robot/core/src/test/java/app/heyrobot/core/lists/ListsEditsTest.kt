// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lists-plan.md §14's ListsEditsTest: duplicates put back, quantities replaced, ticks, tombstones and their purge at 30
 * days, every change raises rev (and a change that changes nothing raises nothing), history kept to 8, the first-use
 * lists, the caps, secrets refused, unique names.
 */
class ListsEditsTest {
    @Test fun aMoveClearsTheSentMark() {
        val who = ListsWho(ItemBy.YOU, ItemHow.TYPED, 1_000L)
        var k = 0
        val ids = { ListsId.of((++k).toString(16).padStart(32, 'e'))!! }
        val a = ListsId.of("a".repeat(32))!!; val b = ListsId.of("b".repeat(32))!!; val i = ListsId.of("c".repeat(32))!!
        val x = ListItem(i, "milk", null, false, 0L, 1L, 1L, ItemBy.YOU, ItemHow.TYPED, 1L, null, emptyList(), mapOf(ListsShared.SENT to 1L, ListsShared.REQ to "1".repeat(32)))
        val f = ListsFile(listOf(ListsList(a, name = "A", made = 1L, items = listOf(x)), ListsList(b, name = "B", made = 1L)))
        val d = ListsEdits.moveTo(f, a, i, b, who, ids) as ListsEdit.Done
        val moved = d.file.list(b)!!.items.single()
        assertFalse(ListsShared.SENT in moved.extra)
        assertFalse(ListsShared.REQ in moved.extra)
        assertTrue(app.heyrobot.core.link.ListShare.owed(moved))
    }

    private val t0 = 1_761_150_000_000
    private val ids = CountingIds()
    private fun you(at: Long = t0, how: ItemHow = ItemHow.VOICE) = ListsWho(ItemBy.YOU, how, at)

    private val start = ListsEdits.firstUse(t0, ids)
    private val shopping = start.lists[0].id
    private val todo = start.lists[1].id

    private fun ok(e: ListsEdit): ListsEdit.Done = e as ListsEdit.Done
    private fun no(e: ListsEdit): ListsRefusal = (e as ListsEdit.No).why
    private fun add(f: ListsFile, vararg items: String, list: ListsId = shopping, at: Long = t0) =
        ok(ListsEdits.add(f, list, items.map { NewItem(it) }, you(at), ids))
    private fun item(f: ListsFile, text: String, list: ListsId = shopping) = f.list(list)!!.items.single { it.text == text }

    @Test fun firstUse() {
        assertEquals(listOf(ListRole.SHOPPING, ListRole.TODO), start.lists.map { it.role })
        assertEquals(listOf(null, null), start.lists.map { it.name })
        assertNotEquals(shopping, todo)
        assertTrue(start.lists.all { it.yours && it.items.isEmpty() && it.made == t0 && it.rev == 1L })
        // The names they answer to, in both languages.
        assertEquals(listOf("Shopping", "Épicerie"), start.lists[0].names)
        assertEquals(listOf("To-do", "À faire"), start.lists[1].names)
    }

    @Test fun addingNewItems() {
        val e = add(start, "oat milk", "eggs", at = t0 + 5)
        assertTrue(e.changed)
        val l = e.file.list(shopping)!!
        assertEquals(listOf("oat milk", "eggs"), l.open.map { it.text })
        assertEquals(listOf(1000L, 2000L), l.items.map { it.pos })
        val i = l.items[0]
        assertEquals(listOf(1L, t0 + 5, t0 + 5, ItemBy.YOU, ItemHow.VOICE), listOf(i.rev, i.at, i.upd, i.by, i.how))
        assertEquals(listOf(ListChange(ItemOp.ADD, t0 + 5, ItemBy.YOU, ItemHow.VOICE)), i.hist)
        assertEquals(e.items.map { it.item }, l.items.map { it.id })
        assertTrue(e.items.all { it.outcome == ItemOutcome.ADDED })
        // Ids are new: no list's and no other item's.
        val all = e.file.lists.map { it.id } + e.file.lists.flatMap { x -> x.items.map { it.id } }
        assertEquals(all.size, all.toSet().size)
    }

    /** Adding what's open: nothing changes (not even rev), unless a quantity is given; adding what's ticked puts it back. */
    @Test fun duplicatesAndQuantities() {
        val f = add(start, "oat milk").file
        val same = ok(ListsEdits.add(f, shopping, listOf(NewItem("Oat Milk")), you(t0 + 1), ids))
        assertFalse(same.changed)
        assertSame(f, same.file)
        assertEquals(ItemOutcome.ALREADY, same.items.single().outcome)
        val q = ok(ListsEdits.add(f, shopping, listOf(NewItem("oat milk", "2 L")), you(t0 + 2), ids))
        assertEquals(ItemDone(item(f, "oat milk").id, ItemOutcome.QTY, null), q.items.single())
        val x = item(q.file, "oat milk")
        assertEquals(listOf("2 L", 2L, t0 + 2, ItemOp.EDIT), listOf(x.qty, x.rev, x.upd, x.hist.last().op))
        // The same quantity again: nothing. Another: replaced, the old one kept for Undo.
        assertFalse(ok(ListsEdits.add(q.file, shopping, listOf(NewItem("oat milk", "2 L")), you(t0 + 3), ids)).changed)
        val q2 = ok(ListsEdits.add(q.file, shopping, listOf(NewItem("oat milk", "1 L")), you(t0 + 3), ids))
        assertEquals("2 L", q2.items.single().was)
        // Ticked, then said again: put back, its quantity kept when none is given.
        val ticked = ok(ListsEdits.tick(q2.file, shopping, x.id, you(t0 + 4))).file
        val back = ok(ListsEdits.add(ticked, shopping, listOf(NewItem("oat milk")), you(t0 + 5), ids))
        assertEquals(ItemOutcome.PUT_BACK, back.items.single().outcome)
        val y = item(back.file, "oat milk")
        assertEquals(listOf(false, "1 L", 5L, ItemOp.UNTICK), listOf(y.done, y.qty, y.rev, y.hist.last().op))
        // The same item twice in one add: once.
        val twice = add(start, "bread", "Bread")
        assertEquals(listOf(ItemOutcome.ADDED, ItemOutcome.ALREADY), twice.items.map { it.outcome })
        assertEquals(1, twice.file.list(shopping)!!.items.size)
        // A removed item said again is a new item; its tombstone stays hidden.
        val removed = ok(ListsEdits.remove(f, shopping, listOf(item(f, "oat milk").id), you(t0 + 6))).file
        val again = add(removed, "oat milk", at = t0 + 7)
        assertEquals(ItemOutcome.ADDED, again.items.single().outcome)
        assertEquals(2, again.file.list(shopping)!!.items.size)
        assertEquals(1, again.file.list(shopping)!!.live.size)
    }

    @Test fun ticksAndTheirUndo() {
        val f = add(start, "eggs", "bread").file
        val eggs = item(f, "eggs").id
        val t = ok(ListsEdits.tick(f, shopping, eggs, you(t0 + 1, ItemHow.WIDGET)))
        assertEquals(ItemOutcome.TICKED, t.items.single().outcome)
        assertEquals(listOf("bread"), t.file.list(shopping)!!.open.map { it.text })
        assertEquals(listOf("eggs"), t.file.list(shopping)!!.done.map { it.text })
        assertEquals(ListChange(ItemOp.TICK, t0 + 1, ItemBy.YOU, ItemHow.WIDGET), item(t.file, "eggs").hist.last())
        val again = ok(ListsEdits.tick(t.file, shopping, eggs, you(t0 + 2)))
        assertFalse(again.changed)
        assertSame(t.file, again.file)
        assertEquals(ItemOutcome.SAME, again.items.single().outcome)
        val u = ok(ListsEdits.untick(t.file, shopping, eggs, you(t0 + 3)))
        assertEquals(ItemOutcome.UNTICKED, u.items.single().outcome)
        assertFalse(item(u.file, "eggs").done)
        assertEquals(no(ListsEdits.tick(f, todo, eggs, you())), ListsRefusal.NO_ITEM)
        assertEquals(no(ListsEdits.tick(f, ids(), eggs, you())), ListsRefusal.NO_LIST)
    }

    @Test fun tombstonesAndTheirPurgeAt30Days() {
        val f = add(start, "eggs", "bread", "milk").file
        val (eggs, bread, milk) = f.list(shopping)!!.items.map { it.id }
        val r = ok(ListsEdits.remove(f, shopping, listOf(eggs), you(t0 + 10)))
        val tomb = r.file.list(shopping)!!.item(eggs)!!
        assertEquals(listOf(t0 + 10, 2L, ItemOp.REMOVE), listOf(tomb.gone, tomb.rev, tomb.hist.last().op))
        assertEquals(listOf("bread", "milk"), r.file.list(shopping)!!.live.map { it.text })
        // Removing it again: it isn't there. Restoring: back where it was, its tick as it was.
        assertEquals(ListsRefusal.NO_ITEM, no(ListsEdits.remove(r.file, shopping, listOf(eggs), you())))
        val back = ok(ListsEdits.restore(r.file, shopping, listOf(eggs), you(t0 + 11)))
        assertEquals(listOf("eggs", "bread", "milk"), back.file.list(shopping)!!.live.map { it.text })
        assertEquals(ItemOp.RESTORE, back.file.list(shopping)!!.item(eggs)!!.hist.last().op)
        // All or nothing.
        assertEquals(ListsRefusal.NO_ITEM, no(ListsEdits.remove(f, shopping, listOf(bread, ids()), you())))
        // Clear done takes the ticked ones; empty takes them all.
        val ticked = ok(ListsEdits.tick(f, shopping, milk, you(t0 + 1))).file
        val cleared = ok(ListsEdits.clearDone(ticked, shopping, you(t0 + 12)))
        assertEquals(listOf(ItemDone(milk, ItemOutcome.REMOVED)), cleared.items)
        assertEquals(listOf("eggs", "bread"), cleared.file.list(shopping)!!.live.map { it.text })
        assertFalse(ok(ListsEdits.clearDone(cleared.file, shopping, you(t0 + 13))).changed)
        val emptied = ok(ListsEdits.empty(cleared.file, shopping, you(t0 + 14)))
        assertEquals(setOf(eggs, bread), emptied.items.map { it.item }.toSet())
        assertTrue(emptied.file.list(shopping)!!.live.isEmpty())
        assertEquals(3, emptied.file.list(shopping)!!.items.size)
        // The purge: a tombstone stays 30 days, then goes; live items never do.
        val day = 24 * 60 * 60 * 1000L
        assertSame(emptied.file, ListsEdits.purge(emptied.file, t0 + 12 + 30 * day - 1))
        val purged = ListsEdits.purge(emptied.file, t0 + 12 + 30 * day)
        assertEquals(setOf(eggs, bread), purged.list(shopping)!!.items.map { it.id }.toSet())
        assertTrue(ListsEdits.purge(emptied.file, t0 + 14 + 30 * day).list(shopping)!!.items.isEmpty())
        assertSame(f, ListsEdits.purge(f, t0 + 1000 * day))
    }

    /** Every change raises the item's rev by one and adds itself to the history; the history keeps the last 8. */
    @Test fun everyChangeRaisesRevAndHistoryKeepsEight() {
        var f = add(start, "eggs").file
        val eggs = item(f, "eggs").id
        val steps: List<Pair<ItemOp, (ListsFile, Long) -> ListsEdit>> = listOf(
            ItemOp.TICK to { x, at -> ListsEdits.tick(x, shopping, eggs, you(at)) },
            ItemOp.UNTICK to { x, at -> ListsEdits.untick(x, shopping, eggs, you(at)) },
            ItemOp.EDIT to { x, at -> ListsEdits.edit(x, shopping, eggs, "free-range eggs", you(at, ItemHow.TYPED)) },
            ItemOp.EDIT to { x, at -> ListsEdits.quantity(x, shopping, eggs, "12", you(at, ItemHow.TYPED)) },
            ItemOp.REMOVE to { x, at -> ListsEdits.remove(x, shopping, listOf(eggs), you(at)) },
            ItemOp.RESTORE to { x, at -> ListsEdits.restore(x, shopping, listOf(eggs), you(at)) },
            ItemOp.TICK to { x, at -> ListsEdits.tick(x, shopping, eggs, you(at)) },
            ItemOp.UNTICK to { x, at -> ListsEdits.add(x, shopping, listOf(NewItem("free-range eggs")), you(at), ids) },
            ItemOp.EDIT to { x, at -> ListsEdits.quantity(x, shopping, eggs, null, you(at)) },
        )
        for ((k, s) in steps.withIndex()) {
            val before = f.list(shopping)!!.item(eggs)!!
            val e = ok(s.second(f, t0 + 100 + k))
            assertTrue("step $k", e.changed)
            f = e.file
            val after = f.list(shopping)!!.item(eggs)!!
            assertEquals("step $k", before.rev + 1, after.rev)
            assertEquals("step $k", t0 + 100 + k, after.upd)
            assertEquals("step $k", s.first, after.hist.last().op)
        }
        val x = f.list(shopping)!!.item(eggs)!!
        assertEquals(10L, x.rev)
        assertEquals(8, x.hist.size)
        assertEquals(steps.drop(1).map { it.first }, x.hist.map { it.op })
        // The list's own rev moves only with its name.
        assertEquals(1L, f.list(shopping)!!.rev)
    }

    @Test fun editsQuantitiesAndMoves() {
        val f = add(start, "eggs", "bread", "milk").file
        val (eggs, bread, milk) = f.list(shopping)!!.items.map { it.id }
        assertEquals(ListsRefusal.ALREADY, no(ListsEdits.edit(f, shopping, eggs, "Bread", you())))
        assertFalse(ok(ListsEdits.edit(f, shopping, eggs, "eggs ", you())).changed)
        assertEquals(ListsRefusal.SECRET, no(ListsEdits.edit(f, shopping, eggs, "my PIN 4821", you())))
        assertEquals(ListsRefusal.EMPTY, no(ListsEdits.edit(f, shopping, eggs, " . ", you())))
        assertEquals("brown eggs", item(ok(ListsEdits.edit(f, shopping, eggs, "brown eggs", you())).file, "brown eggs").text)
        val q = ok(ListsEdits.quantity(f, shopping, milk, "2 L", you()))
        assertEquals(ItemDone(milk, ItemOutcome.QTY, null), q.items.single())
        assertEquals(ListsRefusal.TOO_LONG, no(ListsEdits.quantity(f, shopping, milk, "x".repeat(17), you())))
        assertNull(item(ok(ListsEdits.quantity(q.file, shopping, milk, " ", you())).file, "milk").qty)
        // Move to top: before every other open item.
        val top = ok(ListsEdits.moveToTop(f, shopping, milk, you(t0 + 1)))
        assertEquals(listOf("milk", "eggs", "bread"), top.file.list(shopping)!!.live.map { it.text })
        assertEquals(0L, item(top.file, "milk").pos)
        assertFalse(ok(ListsEdits.moveToTop(top.file, shopping, milk, you())).changed)
        // Move to another list: a tombstone here, a new item there with its words, who added it and its history.
        val m = ok(ListsEdits.moveTo(f, shopping, bread, todo, you(t0 + 2, ItemHow.TYPED), ids))
        assertEquals(todo, m.list)
        val there = m.file.list(todo)!!.live.single()
        assertNotEquals(bread, there.id)
        assertEquals(listOf("bread", ItemBy.YOU, ItemHow.VOICE, t0, ItemOp.MOVE), listOf(there.text, there.by, there.how, there.at, there.hist.last().op))
        assertEquals(t0 + 2, m.file.list(shopping)!!.item(bread)!!.gone)
        assertEquals(listOf(ItemDone(there.id, ItemOutcome.MOVED), ItemDone(bread, ItemOutcome.REMOVED)), m.items)
        // Moving onto a list that has it ticked puts that one back.
        val todoEggs = add(f, "eggs", list = todo)
        val withTodo = ok(ListsEdits.tick(todoEggs.file, todo, todoEggs.items.single().item, you())).file
        val m2 = ok(ListsEdits.moveTo(withTodo, shopping, eggs, todo, you(), ids))
        assertEquals(ItemOutcome.PUT_BACK, m2.items[0].outcome)
        assertEquals(1, m2.file.list(todo)!!.live.size)
        assertEquals(ListsRefusal.NO_LIST, no(ListsEdits.moveTo(f, shopping, eggs, shopping, you(), ids)))
    }

    @Test fun listsNamesAndRoles() {
        val who = you()
        // Names are unique among your lists, case, accents and punctuation aside, and a role list answers to its role's names in both languages.
        for (taken in listOf("Shopping", "shopping", "ÉPICERIE", "epicerie", "To-do", "to do", "todo", "À faire", "a faire!")) {
            assertEquals(taken, ListsRefusal.NAME_TAKEN, no(ListsEdits.make(start, taken, null, who, ids)))
        }
        val packing = ok(ListsEdits.make(start, " Packing ", null, who, ids))
        val p = packing.file.list(packing.list)!!
        assertEquals(listOf("Packing", null, ListFrom.YOU, 1L), listOf(p.name, p.role, p.from, p.rev))
        assertEquals(ListsRefusal.NAME_TAKEN, no(ListsEdits.make(packing.file, "packing", null, who, ids)))
        assertEquals(ListsRefusal.ROLE_TAKEN, no(ListsEdits.make(start, null, ListRole.SHOPPING, who, ids)))
        assertEquals(ListsRefusal.EMPTY, no(ListsEdits.make(start, "  ", null, who, ids)))
        assertEquals(ListsRefusal.TOO_LONG, no(ListsEdits.make(start, "n".repeat(41), null, who, ids)))
        assertEquals(ListsRefusal.SECRET, no(ListsEdits.make(start, "PIN 4821", null, who, ids)))
        // A role made again once its list was deleted.
        val noShopping = ok(ListsEdits.deleteList(start, shopping)).file
        assertEquals(1, noShopping.lists.size)
        assertEquals(ListRole.SHOPPING, ok(ListsEdits.make(noShopping, null, ListRole.SHOPPING, who, ids)).let { it.file.list(it.list)!!.role })
        // Renaming: the list's rev rises; a role list keeps its role; its old role names are free once renamed.
        val renamed = ok(ListsEdits.rename(start, shopping, "Groceries", who))
        assertEquals(listOf("Groceries", ListRole.SHOPPING, 2L), renamed.file.list(shopping)!!.let { listOf(it.name, it.role, it.rev) })
        assertTrue(ListsEdits.make(renamed.file, "Épicerie", null, who, ids) is ListsEdit.Done)
        assertFalse(ok(ListsEdits.rename(renamed.file, shopping, "Groceries", who)).changed)
        assertEquals(ListsRefusal.NAME_TAKEN, no(ListsEdits.rename(renamed.file, shopping, "to do", who)))
        // A list from someone else's backup doesn't take a name from yours.
        val theirs = ListsFile(start.lists + ListsList(ids(), name = "Packing", made = t0, from = ListFrom.BACKUP))
        assertTrue(ListsEdits.make(theirs, "Packing", null, who, ids) is ListsEdit.Done)
        // 50 lists at most.
        var f = start
        repeat(48) { f = ok(ListsEdits.make(f, "List $it", null, who, ids)).file }
        assertEquals(ListsRefusal.LISTS_FULL, no(ListsEdits.make(f, "One more", null, who, ids)))
    }

    @Test fun secretsAndTheAddCaps() {
        assertEquals(ListsRefusal.SECRET, no(ListsEdits.add(start, shopping, listOf(NewItem("eggs"), NewItem("my PIN 4821")), you(), ids)))
        assertEquals(ListsRefusal.SECRET, no(ListsEdits.add(start, shopping, listOf(NewItem("4111 1111 1111 1111")), you(), ids)))
        assertEquals(ListsRefusal.TOO_MANY, no(ListsEdits.add(start, shopping, List(21) { NewItem("item $it") }, you(), ids)))
        assertEquals(ListsRefusal.EMPTY, no(ListsEdits.add(start, shopping, emptyList(), you(), ids)))
        assertEquals(ListsRefusal.TOO_LONG, no(ListsEdits.add(start, shopping, listOf(NewItem("e".repeat(121))), you(), ids)))
        assertEquals(ListsRefusal.EMPTY, no(ListsEdits.add(start, shopping, listOf(NewItem("eggs\nmilk")), you(), ids)))
        assertEquals(ListsRefusal.NO_LIST, no(ListsEdits.add(start, ids(), listOf(NewItem("eggs")), you(), ids)))
    }

    /** 500 items, tombstones included; the oldest tombstones make room, so clearing the done ones does free it. */
    @Test fun aFullList() {
        var f = start
        for (k in 0 until 25) f = ok(ListsEdits.add(f, shopping, List(20) { NewItem("item ${k * 20 + it}") }, you(t0 + k), ids)).file
        assertEquals(500, f.list(shopping)!!.items.size)
        assertEquals(ListsRefusal.LIST_FULL, no(ListsEdits.add(f, shopping, listOf(NewItem("one more")), you(), ids)))
        // Adding what's there needs no room.
        assertFalse(ok(ListsEdits.add(f, shopping, listOf(NewItem("item 7")), you(), ids)).changed)
        // Tick and clear five: their tombstones make room for five, the oldest removal first.
        val five = f.list(shopping)!!.items.take(5).map { it.id }
        for ((k, id) in five.withIndex()) f = ok(ListsEdits.tick(f, shopping, id, you(t0 + 100 + k))).file
        f = ok(ListsEdits.remove(f, shopping, listOf(five[4]), you(t0 + 200))).file
        f = ok(ListsEdits.clearDone(f, shopping, you(t0 + 300))).file
        assertEquals(500, f.list(shopping)!!.items.size)
        val one = ok(ListsEdits.add(f, shopping, listOf(NewItem("one more")), you(t0 + 400), ids)).file
        assertEquals(500, one.list(shopping)!!.items.size)
        assertNull(one.list(shopping)!!.item(five[4]))  // removed at t0 + 200: the oldest tombstone
        assertEquals(4, one.list(shopping)!!.items.count { !it.live })
        val five2 = ok(ListsEdits.add(one, shopping, List(4) { NewItem("more $it") }, you(t0 + 500), ids)).file
        assertEquals(ListsRefusal.LIST_FULL, no(ListsEdits.add(five2, shopping, listOf(NewItem("too many")), you(), ids)))
    }

    /** At 2 MiB, nothing is added: the file's own outcome, apart from a list's. */
    @Test fun aFullFile() {
        val long = "\uD83E\uDD5A".repeat(57)
        val hist = List(8) { ListChange(ItemOp.EDIT, t0, ItemBy.YOU, ItemHow.TYPED) }
        fun big(n: Int) = ListItem(ids(), "$long $n", "9".repeat(16), false, n * 1000L, t0, t0, ItemBy.YOU, ItemHow.TYPED, 9, null, hist)
        var f = start
        var n = 0
        while (true) {
            val next = ListsFile(f.lists + ListsList(ids(), name = "List $n", made = t0, items = List(500) { big(it) }))
            if (!ListsCodec.fits(next)) break
            f = next
            n++
        }
        // One more list, holding as many of those items as still fit: the file is then within one item of its cap.
        val lastId = ids()
        val items = List(500) { big(it) }
        fun with(m: Int) = ListsFile(f.lists + ListsList(lastId, name = "Last", made = t0, items = items.take(m)))
        var lo = 0
        var hi = 500
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (ListsCodec.fits(with(mid))) lo = mid else hi = mid - 1
        }
        assertTrue(lo < 500)
        var g = with(lo)
        var refused: ListsRefusal? = null
        for (k in 0 until 10) {
            when (val e = ListsEdits.add(g, lastId, listOf(NewItem("$long n$k", "x".repeat(16))), you(), ids)) {
                is ListsEdit.Done -> g = e.file
                is ListsEdit.No -> { refused = e.why; break }
            }
        }
        assertEquals(ListsRefusal.FILE_FULL, refused)
        assertTrue(ListsCodec.fits(g))
        assertTrue(g.list(lastId)!!.items.size < ListsLimits.MAX_ITEMS)
    }

    @Test fun whoMakesChanges() {
        // 1.4 never writes "peer" (§9), nor a value it doesn't know.
        assertFalse(runCatching { ListsWho(ItemBy.PEER, ItemHow.VOICE, 0) }.isSuccess)
        assertFalse(runCatching { ListsWho(ItemBy.UNKNOWN, ItemHow.VOICE, 0) }.isSuccess)
        assertFalse(runCatching { ListsWho(ItemBy.YOU, ItemHow.UNKNOWN, 0) }.isSuccess)
        for (by in listOf(ItemBy.YOU, ItemBy.LOCKED, ItemBy.CONTENT, ItemBy.BACKUP)) {
            val e = ok(ListsEdits.add(start, todo, listOf(NewItem("call the bank")), ListsWho(by, ItemHow.CARD, t0), ids))
            assertEquals(by, e.file.list(todo)!!.items.single().by)
        }
    }
}
