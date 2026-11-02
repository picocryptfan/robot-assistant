// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LS5's pure parts (lists-plan.md §7.3): a note made into items (lines, bullets, numbers, a one-line note's commas; the
 * spoken rules for articles and quantities; secrets and overlong lines skipped and counted; duplicates once; more than 20
 * added in one change), Share as text (open items only, with quantities), and a new list named as a default one.
 */
class ListsScreensTest {
    private val t0 = 1_761_150_000_000
    private val ids = CountingIds()
    private val who = ListsWho(ItemBy.YOU, ItemHow.NOTE, t0)
    private val start = ListsEdits.firstUse(t0, ids)
    private val shopping = start.lists[0].id

    @Test fun aNoteOfLinesAndBullets() {
        val n = NoteToList.read("- oat milk\n* 2 litres of milk\n• the eggs\n\n1. bread\n2) Coffee beans\n[ ] soap\n[x] tape\n☐ salt and pepper")
        assertEquals(listOf("oat milk", "milk", "eggs", "bread", "Coffee beans", "soap", "tape", "salt and pepper"), n.items.map { it.text })
        assertEquals("2 L", n.items[1].qty)
        assertEquals(0, n.skipped)
    }

    @Test fun aOneLineNoteIsSplitAtItsCommas() {
        assertEquals(listOf("eggs", "bread", "oat milk"), NoteToList.read("eggs, bread, oat milk").items.map { it.text })
        // Lines are never split at commas or "and": a to-do keeps its words.
        assertEquals(listOf("Call Mom and Dad", "pay rent, hydro"), NoteToList.read("Call Mom and Dad\npay rent, hydro").items.map { it.text })
        assertEquals(listOf("du lait", "œufs").map { ItemText.item(it).text }, NoteToList.read("du lait, des œufs").items.map { it.text })
    }

    @Test fun whatCantBeAnItemIsSkippedAndCounted() {
        val n = NoteToList.read("eggs\nmy PIN is 4821\n" + "x".repeat(121) + "\nEggs\n   \n-")
        assertEquals(listOf("eggs"), n.items.map { it.text })
        assertEquals(2, n.skipped)
        assertEquals(NoteItems(emptyList(), 0), NoteToList.read(""))
    }

    @Test fun moreThanTwentyInOneChange() {
        val items = (1..45).map { NewItem("thing $it") }
        val e = NoteToList.add(start, shopping, items, who, ids) as ListsEdit.Done
        assertTrue(e.changed)
        assertEquals(45, e.items.size)
        val l = e.file.list(shopping)!!
        assertEquals(items.map { it.text }, l.open.map { it.text })
        assertTrue(l.items.all { it.how == ItemHow.NOTE && it.by == ItemBy.YOU })
        assertEquals(ListsRefusal.EMPTY, (NoteToList.add(start, shopping, emptyList(), who, ids) as ListsEdit.No).why)
        // At a cap, none: 500 items already.
        val full = NoteToList.add(start, shopping, (1..500).map { NewItem("n$it") }, who, ids) as ListsEdit.Done
        assertEquals(ListsRefusal.LIST_FULL, (NoteToList.add(full.file, shopping, listOf(NewItem("one more"), NewItem("two")), who, ids) as ListsEdit.No).why)
    }

    @Test fun shareAsText() {
        var f = (ListsEdits.add(start, shopping, listOf(NewItem("oat milk", "2 L"), NewItem("eggs"), NewItem("bread")), who, ids) as ListsEdit.Done).file
        val bread = f.list(shopping)!!.items.single { it.text == "bread" }.id
        f = (ListsEdits.tick(f, shopping, bread, who) as ListsEdit.Done).file
        assertEquals("Shopping\n- Oat milk (2 L)\n- Eggs", ListsShare.text("Shopping", f.list(shopping)!!))
        assertEquals("To-do", ListsShare.text("To-do", f.lists[1]))
    }

    @Test fun aNewListNamedAsADefaultOne() {
        assertEquals(ListRole.SHOPPING, ListsNames.role("épicerie"))
        assertEquals(ListRole.TODO, ListsNames.role("To do"))
        assertNull(ListsNames.role("Camping"))
        // Shopping is there: a second one is refused.
        assertEquals(ListsRefusal.NAME_TAKEN, (ListsNames.make(start, "Shopping", who, ids) as ListsEdit.No).why)
        // Gone (deleted): made again by its role.
        val noShopping = (ListsEdits.deleteList(start, shopping) as ListsEdit.Done).file
        val e = ListsNames.make(noShopping, "Épicerie", who, ids) as ListsEdit.Done
        val made = e.file.list(e.list)!!
        assertEquals(ListRole.SHOPPING, made.role)
        assertNull(made.name)
        val camping = ListsNames.make(start, "Camping", who, ids) as ListsEdit.Done
        assertEquals("Camping", camping.file.list(camping.list)!!.name)
    }
}
