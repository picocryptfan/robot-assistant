// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lists-plan.md §14's ListsMergeTest: the union by id, the higher rev wins, ties, tombstones beat older edits, your
 * backup merged by id, role or name, someone else's lists kept apart, and the caps.
 */
class ListsMergeTest {
    private val t0 = 1_761_150_000_000
    private val ids = CountingIds()
    private fun id(n: Int) = ListsId.of("%032x".format(n))!!
    private fun item(n: Int, text: String = "item $n", rev: Long = 1, upd: Long = t0, done: Boolean = false, gone: Long? = null, by: ItemBy = ItemBy.YOU) =
        ListItem(id(1000 + n), text, null, done, n * 1000L, t0, upd, by, ItemHow.VOICE, rev, gone, listOf(ListChange(ItemOp.ADD, t0, by, ItemHow.VOICE)))
    private val roleName: (ListRole) -> String = { if (it == ListRole.SHOPPING) "Shopping" else "To-do" }

    private fun add(file: ListsFile, incoming: ListsFile, yours: Boolean = true) =
        (ListsMerge.add(file, incoming, yours, ids, roleName) as ListsMergeResult.Ok).merged

    @Test fun theItemRule() {
        val mine = item(1, "eggs", rev = 2, upd = t0 + 10)
        // The higher rev wins, whatever the times say.
        assertSame(mine, ListsMerge.item(mine, item(1, "eggs!", rev = 1, upd = t0 + 99)))
        val newer = item(1, "brown eggs", rev = 3, upd = t0 + 1)
        assertSame(newer, ListsMerge.item(mine, newer))
        // Equal revs: the later upd; equal again: this phone's.
        val later = item(1, "brown eggs", rev = 2, upd = t0 + 11)
        assertSame(later, ListsMerge.item(mine, later))
        assertSame(mine, ListsMerge.item(mine, item(1, "white eggs", rev = 2, upd = t0 + 10)))
        // A removal raises rev: a tombstone beats the older edits it follows, and a later edit beats an older tombstone.
        val tomb = item(1, "eggs", rev = 3, upd = t0 + 20, gone = t0 + 20)
        assertSame(tomb, ListsMerge.item(mine, tomb))
        val edited = item(1, "eggs", rev = 4, upd = t0 + 30, done = true)
        assertSame(edited, ListsMerge.item(tomb, edited))
    }

    @Test fun theListRule() {
        val mine = ListsList(id(1), name = "Packing", made = t0, rev = 1, items = listOf(item(1), item(2, rev = 2), item(3)))
        val theirs = ListsList(id(1), name = "Cottage", made = t0 - 5, rev = 2, items = listOf(item(4), item(2, "item 2 edited", rev = 3), item(1, rev = 1, upd = t0 + 99)))
        val m = ListsMerge.list(mine, theirs)
        // Union by id: mine in their order, then theirs not in mine; each item in both by the item rule.
        assertEquals(listOf(id(1001), id(1002), id(1003), id(1004)), m.items.map { it.id })
        assertEquals(listOf("item 1", "item 2 edited", "item 3", "item 4"), m.items.map { it.text })
        // The list's own fields from the higher list rev; made, the earlier.
        assertEquals(listOf("Cottage", 2L, t0 - 5), listOf(m.name, m.rev, m.made))
        assertEquals("Packing", ListsMerge.list(mine, theirs.copy(rev = 1)).name)
        // Merging a list with itself, or merging twice, changes nothing.
        assertEquals(mine, ListsMerge.list(mine, mine))
        assertEquals(m, ListsMerge.list(m, theirs))
    }

    @Test fun aJoinedListInYourBackupIsThatListHere() {
        val sam = "a".repeat(32)
        val l = ListsList(id(50), name = "Trip", made = t0, from = ListFrom.PEER, items = listOf(item(51)), extra = mapOf(ListsShared.SHARED to listOf(sam), ListsShared.ORIGIN to sam))
        val m = add(ListsFile(listOf(l)), ListsFile(listOf(l.copy(items = listOf(item(51), item(52))))))
        val only = m.file.lists.single()
        assertEquals(id(50), only.id)
        assertEquals(ListFrom.PEER, only.from)
        assertEquals(listOf(sam), ListsShared.with(only))
        assertEquals(2, only.items.size)
    }

    @Test fun yourBackupIsMergedByIdRoleOrName() {
        val here = ListsEdits.firstUse(t0, ids).let { f ->
            (ListsEdits.add(f, f.lists[0].id, listOf(NewItem("eggs")), ListsWho(ItemBy.YOU, ItemHow.VOICE, t0), ids) as ListsEdit.Done).file
        }
        // The backup: its own Shopping (another id, as a reset phone makes new defaults), a Packing list, and a list
        // "to do" named as this phone's To-do is.
        val backup = ListsFile(listOf(
            ListsList(id(1), role = ListRole.SHOPPING, made = t0 - 100, items = listOf(item(1, "oat milk"), item(2, "bread", done = true))),
            ListsList(id(2), name = "Packing", made = t0 - 100, items = listOf(item(3, "sunscreen"))),
            ListsList(id(3), name = "to do", made = t0 - 100, items = listOf(item(4, "call the bank"))),
        ), extra = mapOf("later" to true))
        val r = add(here, backup)
        assertEquals(listOf(1, 2, 4), listOf(r.listsAdded, r.listsMerged, r.itemsAdded))
        val f = r.file
        assertEquals(3, f.lists.size)
        assertEquals(here.lists[0].id, f.withRole(ListRole.SHOPPING)!!.id)
        assertEquals(listOf("eggs", "oat milk"), f.withRole(ListRole.SHOPPING)!!.open.map { it.text })
        assertEquals(listOf("bread"), f.withRole(ListRole.SHOPPING)!!.done.map { it.text })
        assertEquals(listOf("call the bank"), f.withRole(ListRole.TODO)!!.open.map { it.text })
        assertEquals(null, f.withRole(ListRole.TODO)!!.name)
        assertEquals(id(2), f.lists.single { it.name == "Packing" }.id)
        assertEquals(mapOf("later" to true), f.extra)
        // The same backup again: nothing new.
        val again = add(f, backup)
        assertEquals(f, again.file)
        assertEquals(listOf(0, 0, 0), listOf(again.listsAdded, again.itemsAdded, again.itemsChanged))
        // A newer copy of an item wins; an older one doesn't.
        val newer = backup.copy(lists = listOf(backup.lists[1].copy(items = listOf(item(3, "sunscreen SPF 50", rev = 2)))))
        val n = add(f, newer)
        assertEquals(1, n.itemsChanged)
        assertEquals("sunscreen SPF 50", n.file.list(id(2))!!.items.single().text)
        assertEquals(n.file, add(n.file, backup).file)
        // Your backup holding someone else's "Shopping" from an earlier restore: it stays apart from yours.
        val theirs = ListsList(id(7), name = "Shopping", made = t0, from = ListFrom.BACKUP, items = listOf(item(7, "beer", by = ItemBy.BACKUP)))
        val withTheirs = add(n.file, ListsFile(listOf(theirs)))
        assertEquals(listOf(1, 0), listOf(withTheirs.listsAdded, withTheirs.listsMerged))
        assertEquals(n.file.lists, withTheirs.file.lists.take(3))
        assertEquals(ListFrom.BACKUP, withTheirs.file.list(id(7))!!.from)
        assertEquals(withTheirs.file, add(withTheirs.file, ListsFile(listOf(theirs))).file)
    }

    @Test fun someoneElsesListsStayApart() {
        val here = ListsEdits.firstUse(t0, ids)
        val theirs = ListsFile(listOf(
            ListsList(id(1), role = ListRole.SHOPPING, made = t0 - 100, items = listOf(item(1, "oat milk"), item(2, "beer", done = true))),
            ListsList(id(2), name = "Camping", made = t0 - 100, items = listOf(item(3, "tent"))),
        ))
        val r = add(here, theirs, yours = false)
        assertEquals(listOf(2, 0, 3), listOf(r.listsAdded, r.listsMerged, r.itemsAdded))
        val f = r.file
        // Yours untouched: your Shopping still has the role, and nothing of theirs.
        assertEquals(here.lists, f.lists.take(2))
        val shop = f.lists[2]
        assertEquals(listOf(null, "Shopping", ListFrom.BACKUP), listOf(shop.role, shop.name, shop.from))
        assertTrue(f.lists.drop(2).all { l -> l.from == ListFrom.BACKUP && l.items.all { it.by == ItemBy.BACKUP && it.hist.all { c -> c.by == ItemBy.BACKUP } } })
        // The same backup restored again: merged into the lists that came from it, not added twice.
        val again = add(f, theirs, yours = false)
        assertEquals(f, again.file)
        assertEquals(listOf(0, 2), listOf(again.listsAdded, again.listsMerged))
        // A copy of your own lists in someone else's backup: never merged into yours; new ids where an id is taken here.
        val copy = ListsFile(listOf(here.lists[0].copy(items = listOf(item(9, "eggs")))))
        val mine = (ListsEdits.add(here, here.lists[0].id, listOf(NewItem("eggs")), ListsWho(ItemBy.YOU, ItemHow.VOICE, t0), { id(1009) }) as ListsEdit.Done).file
        val c = add(mine, copy, yours = false).file
        assertEquals(3, c.lists.size)
        assertEquals(mine.lists, c.lists.take(2))
        val apart = c.lists[2]
        assertNotEquals(here.lists[0].id, apart.id)
        assertNotEquals(id(1009), apart.items.single().id)
        assertEquals(ListFrom.BACKUP, apart.from)
    }

    /**
     * LS10 (L4, §6, §9): someone else's backup brings none of the keys this Robot doesn't know (1.5's "shared", "origin",
     * "peer", a change's "who"), and no item that looks like a secret from any backup: skipped and counted. Your own backup
     * keeps its unknown keys.
     */
    @Test fun someoneElsesListsArriveCleanAndNoBackupBringsASecret() {
        val keys = mapOf("shared" to listOf("a1b2"), "origin" to "pairing-7")
        val change = ListChange(ItemOp.ADD, t0, ItemBy.YOU, ItemHow.VOICE, extra = mapOf("who" to "Sam"))
        val eggs = item(1, "eggs").copy(extra = mapOf("peer" to "p-9"), hist = listOf(change))
        val card = item(2, "4111 1111 1111 1111")
        val theirs = ListsFile(listOf(ListsList(id(50), name = "Cottage", made = t0, items = listOf(eggs, card), extra = keys)))
        val m = add(ListsFile(), theirs, yours = false)
        val l = m.file.lists.single()
        assertEquals(emptyMap<String, Any?>(), l.extra)
        assertEquals(listOf("eggs"), l.items.map { it.text })
        assertEquals(emptyMap<String, Any?>(), l.items.single().extra)
        assertEquals(emptyMap<String, Any?>(), l.items.single().hist.single().extra)
        assertEquals(listOf(1, 1), listOf(m.itemsAdded, m.itemsSkipped))
        // Restored again into the list it made (merged by id): still nothing of either.
        val again = add(m.file, theirs, yours = false)
        assertEquals(listOf("eggs"), again.file.lists.single().items.map { it.text })
        assertEquals(1, again.itemsSkipped)
        // Your own backup keeps its unknown keys; its card number is skipped all the same.
        val mine = add(ListsFile(), theirs, yours = true)
        val yours = mine.file.lists.single()
        assertEquals(keys, yours.extra)
        assertEquals(mapOf("peer" to "p-9"), yours.items.single().extra)
        assertEquals(mapOf("who" to "Sam"), yours.items.single().hist.single().extra)
        assertEquals(listOf("eggs"), yours.items.map { it.text })
        assertEquals(1, mine.itemsSkipped)
    }

    @Test fun theCaps() {
        // A list past 500: the oldest tombstones go, then the backup's items that don't fit.
        val here = ListsFile(listOf(ListsList(id(1), name = "Big", made = t0, items = List(490) { item(it) } + List(5) { item(490 + it, gone = t0 + it) })))
        val backup = ListsFile(listOf(ListsList(id(1), name = "Big", made = t0, items = List(20) { item(600 + it) })))
        val r = add(here, backup)
        val big = r.file.list(id(1))!!
        assertEquals(500, big.items.size)
        assertEquals(0, big.items.count { !it.live })
        assertEquals(listOf(10, 10), listOf(r.itemsAdded, r.itemsSkipped))
        assertEquals((600 until 610).map { id(1000 + it) }, big.items.takeLast(10).map { it.id })
        // Lists past 50 aren't added.
        val fifty = ListsFile(List(50) { ListsList(id(it + 1), name = "L$it", made = t0) })
        val more = ListsFile(listOf(ListsList(id(99), name = "One more", made = t0)))
        val m = add(fifty, more)
        assertEquals(listOf(0, 1), listOf(m.listsAdded, m.listsSkipped))
        // Over 2 MiB: nothing. (LS10: a quantity that isn't a card number, which no backup brings now.)
        val long = "🥚".repeat(57)
        val hist = List(8) { ListChange(ItemOp.EDIT, t0, ItemBy.YOU, ItemHow.TYPED) }
        fun full(l: Int) = ListsList(id(100_000 * (l + 1)), name = "List $l", made = t0,
            items = List(500) { ListItem(id(100_000 * (l + 1) + it + 1), "$long $it", "x".repeat(16), false, it * 1000L, t0, t0, ItemBy.YOU, ItemHow.TYPED, 9, null, hist) })
        var lots = ListsFile()
        var l = 0
        while (ListsCodec.fits(ListsFile(lots.lists + full(l)))) lots = ListsFile(lots.lists + full(l++))
        assertEquals(ListsMergeResult.TooBig, ListsMerge.add(lots, ListsFile(listOf(full(l))), true, ids, roleName))
    }
}
