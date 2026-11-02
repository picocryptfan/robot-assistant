// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lists-plan.md §14's ListsJournalTest: adds while locked, the cap, merging twice, a crash at every step, the names
 * refreshed, and never read to answer.
 */
class ListsJournalTest {
    private val t0 = 1_761_150_000_000
    private val ids = CountingIds()
    private val you = ListsWho(ItemBy.YOU, ItemHow.VOICE, t0)

    private val start: ListsFile = ListsEdits.firstUse(t0, ids).let { f ->
        val packing = (ListsEdits.make(f, "Bagages de Noël", null, you, ids) as ListsEdit.Done).file
        val withItems = (ListsEdits.add(packing, f.lists[0].id, listOf(NewItem("eggs"), NewItem("bread")), you, ids) as ListsEdit.Done).file
        ListsFile(withItems.lists + ListsList(ids(), name = "Their list", made = t0, from = ListFrom.BACKUP))
    }
    private val shopping = start.lists[0].id
    private val todo = start.lists[1].id
    private val packing = start.lists[2].id

    private fun append(j: ListsJournalFile, list: ListsId, vararg items: NewItem, at: Long = t0 + 60_000) =
        ListsJournal.append(j, list, items.toList(), at, ids)
    private fun ok(a: JournalAppend) = a as JournalAppend.Ok
    private fun bytes(f: ListsFile) = ListsCodec.encode(f)

    @Test fun theSnapshotHoldsYourListsNames() {
        val j = ListsJournal.snapshot(start)
        assertEquals(listOf(JournalList(shopping, ListRole.SHOPPING), JournalList(todo, ListRole.TODO), JournalList(packing, null, "bagages de noel")), j.lists)
        assertEquals(0, j.waiting)
        // Matching while locked: by name, folded; a role list by its role, or by its role's names in either language.
        assertEquals(packing, ListsJournal.named(j, "Bagages de noël")!!.id)
        assertEquals(packing, ListsJournal.named(j, "BAGAGES DE NOEL!")!!.id)
        assertEquals(shopping, ListsJournal.named(j, "épicerie")!!.id)
        assertEquals(todo, ListsJournal.named(j, "to do")!!.id)
        assertEquals(shopping, ListsJournal.withRole(j, ListRole.SHOPPING)!!.id)
        assertNull(ListsJournal.named(j, "camping"))
        assertNull(ListsJournal.named(j, "their list"))
        assertNull(ListsJournal.named(j, "!!"))
        // Refreshed: a renamed list, a role list renamed (its role stays), a deleted one gone; the adds kept.
        val waiting = ok(append(j, packing, NewItem("sunscreen"))).journal
        var f = (ListsEdits.rename(start, shopping, "Groceries", you) as ListsEdit.Done).file
        f = (ListsEdits.deleteList(f, packing) as ListsEdit.Done).file
        val again = ListsJournal.snapshot(f, waiting)
        assertEquals(listOf(JournalList(shopping, ListRole.SHOPPING, "groceries"), JournalList(todo, ListRole.TODO)), again.lists)
        assertEquals(1, again.waiting)
    }

    @Test fun addsWhileLockedAndTheCap() {
        val j = ListsJournal.snapshot(start)
        val a = ok(append(j, shopping, NewItem("oat milk", "2 L"), NewItem("eggs")))
        assertEquals(2, a.journal.waiting)
        assertEquals(2, a.ids.toSet().size)
        assertEquals(JournalAppend.No(JournalRefusal.NO_LIST), append(j, ids(), NewItem("tent")))
        assertEquals(JournalAppend.No(JournalRefusal.SECRET), append(j, shopping, NewItem("my PIN 4821")))
        assertEquals(JournalAppend.No(JournalRefusal.EMPTY), append(j, shopping))
        assertEquals(JournalAppend.No(JournalRefusal.TOO_LONG), append(j, shopping, NewItem("e".repeat(121))))
        // 50 waiting at most; nothing of a refused add is kept.
        var k = a.journal
        repeat(48) { k = ok(append(k, todo, NewItem("task $it"))).journal }
        assertEquals(50, k.waiting)
        assertEquals(JournalAppend.No(JournalRefusal.FULL), append(k, todo, NewItem("one more")))
        assertEquals(JournalAppend.No(JournalRefusal.FULL), append(ok(append(a.journal, todo, *Array(20) { NewItem("t$it") })).journal.let { x ->
            var y = x
            repeat(28) { y = ok(append(y, todo, NewItem("u$it"))).journal }
            y
        }, todo, NewItem("v1"), NewItem("v2")))
        // At their longest, 50 adds stay far under 64 KiB.
        var big = ListsJournal.snapshot(start)
        repeat(50) { big = ok(append(big, todo, NewItem("🥚".repeat(59) + it, "x".repeat(16)))).journal }
        assertTrue(ListsJournal.fits(big))
        assertTrue(ListsJournal.encode(big).size < ListsLimits.MAX_JOURNAL / 2)
    }

    @Test fun theMerge() {
        val j = ok(append(ListsJournal.snapshot(start), shopping, NewItem("oat milk", "2 L"), NewItem("Eggs"), at = t0 + 5)).journal
        val j2 = ok(append(j, packing, NewItem("sunscreen"), at = t0 + 6))
        val m = ListsJournal.merge(start, j2.journal)
        assertEquals(listOf(2, 1, 0, 0), listOf(m.added, m.already, m.waiting, m.dropped))
        assertEquals(0, m.journal.waiting)
        assertEquals(ListsJournal.snapshot(m.file), m.journal)
        val milk = m.file.list(shopping)!!.live.single { it.text == "oat milk" }
        assertEquals(listOf(ItemBy.LOCKED, ItemHow.VOICE, t0 + 5, t0 + 5, "2 L", 1L), listOf(milk.by, milk.how, milk.at, milk.upd, milk.qty, milk.rev))
        assertEquals(ItemBy.LOCKED, milk.hist.single().by)
        // Each new item keeps the id the add was given (for Undo, and for merging twice).
        assertEquals(j2.ids.single(), m.file.list(packing)!!.items.single().id)
        // "Eggs" was there: left as it was.
        assertEquals(ItemBy.YOU, m.file.list(shopping)!!.live.single { it.text == "eggs" }.by)
    }

    /** Merging twice changes nothing; so a crash between writing lists.enc and emptying the journal loses nothing and adds nothing twice. */
    @Test fun mergingTwiceAndACrashAtEveryStep() {
        // A ticked item put back, a quantity left as it was (LS10), a new item, the same item twice: every way an add can land.
        var f = (ListsEdits.tick(start, shopping, start.list(shopping)!!.items[1].id, you) as ListsEdit.Done).file
        f = (ListsEdits.add(f, todo, listOf(NewItem("call the bank")), you, ids) as ListsEdit.Done).file
        var j = ListsJournal.snapshot(f)
        j = ok(append(j, shopping, NewItem("bread"), NewItem("oat milk"), NewItem("Oat milk", "1 L"))).journal
        j = ok(append(j, todo, NewItem("call the bank", "x2"))).journal
        val once = ListsJournal.merge(f, j)
        // Step 1, the crash before lists.enc is written: the journal and the lists as they were; the merge again gives the same.
        val retry = ListsJournal.merge(f, j)
        assertArrayEquals(bytes(once.file), bytes(retry.file))
        // Step 2, the crash after lists.enc is written, before the journal is emptied: the same journal on the merged lists.
        val twice = ListsJournal.merge(once.file, j)
        assertArrayEquals(bytes(once.file), bytes(twice.file))
        assertEquals(0, twice.added)
        assertEquals(0, twice.journal.waiting)
        // LS10 (L6): adds already merged change nothing, so lists.enc isn't written again.
        assertFalse(twice.changed)
        // Step 3, the journal emptied: nothing left to merge.
        val thrice = ListsJournal.merge(once.file, once.journal)
        assertArrayEquals(bytes(once.file), bytes(thrice.file))
        assertFalse(thrice.changed)
        val l = once.file.list(shopping)!!
        assertFalse(l.live.single { it.text == "bread" }.done)
        // LS10 (M3): a quantity is an edit, unlocked only; the merge leaves it as it was.
        assertNull(l.live.single { it.text == "oat milk" }.qty)
        assertNull(once.file.list(todo)!!.live.single().qty)
        assertEquals(listOf(1, 1, 2), listOf(once.added, once.putBack, once.qtyKept))
    }

    /**
     * LS10 (M3, §4.6, §5, D3): an add said while locked never changes what's there beyond what "add" means. An item already
     * open keeps its quantity, unchanged (its rev too); a ticked one comes back, its history saying "locked" (the Lists
     * screens mark it), and the counts say so apart from "already".
     */
    @Test fun aLockedAddLeavesAQuantityAloneAndCountsAPutBack() {
        var f = (ListsEdits.add(start, shopping, listOf(NewItem("oat milk")), you, ids) as ListsEdit.Done).file
        val eggs = f.list(shopping)!!.live.single { it.text == "eggs" }
        f = (ListsEdits.tick(f, shopping, eggs.id, you) as ListsEdit.Done).file
        val oat = f.list(shopping)!!.live.single { it.text == "oat milk" }
        val said = (ItemText.read("40 litres of oat milk") as ItemsRead.Ok).items + (ItemText.read("eggs") as ItemsRead.Ok).items
        assertEquals(listOf(NewItem("oat milk", "40 L"), NewItem("eggs")), said)
        val m = ListsJournal.merge(f, ok(append(ListsJournal.snapshot(f), shopping, *said.toTypedArray())).journal)
        val l = m.file.list(shopping)!!
        assertEquals(oat, l.item(oat.id))
        assertNull(l.item(oat.id)!!.qty)
        val back = l.item(eggs.id)!!
        assertFalse(back.done)
        assertEquals(ItemBy.YOU, back.by)
        assertEquals(ItemBy.LOCKED, back.hist.last().by)
        assertEquals(listOf(0, 1, 1, 0, 0, 0), listOf(m.added, m.putBack, m.qtyKept, m.already, m.waiting, m.dropped))
        assertEquals(0, m.journal.waiting)
    }

    @Test fun addsThatCantGoInNow() {
        // A full list: the add waits. Its list deleted: the add is dropped and counted. A secret that got in anyway: dropped.
        var full = start
        repeat(25) { k -> full = (ListsEdits.add(full, todo, List(20) { NewItem("t${k * 20 + it}") }, you, ids) as ListsEdit.Done).file }
        var j = ListsJournal.snapshot(full)
        j = ok(append(j, todo, NewItem("one more"))).journal
        j = ok(append(j, packing, NewItem("sunscreen"))).journal
        val planted = ListsJournalFile(j.lists, j.adds + JournalAdd(ids(), shopping, "my PIN 4821", null, t0), j.extra)
        val gone = (ListsEdits.deleteList(full, packing) as ListsEdit.Done).file
        val m = ListsJournal.merge(gone, planted)
        assertEquals(listOf(0, 0, 1, 2), listOf(m.added, m.already, m.waiting, m.dropped))
        assertEquals(1, m.journal.waiting)
        assertEquals(gone, m.file)
        // Room made: it goes in at the next merge.
        val cleared = (ListsEdits.remove(m.file, todo, listOf(m.file.list(todo)!!.items[0].id), you) as ListsEdit.Done).file
        val later = ListsJournal.merge(cleared, m.journal)
        assertEquals(listOf(1, 0), listOf(later.added, later.waiting))
    }

    /** The journal is never read to answer: its adds are core's alone; the app can count them and merge them, nothing else. */
    @Test fun neverReadToAnswer() {
        val j = ok(append(ListsJournal.snapshot(start), shopping, NewItem("zq9marker2"))).journal
        val getters = ListsJournalFile::class.java.methods.map { it.name }
        assertFalse(getters.contains("getAdds"))
        assertTrue(getters.any { it.startsWith("getAdds$") })
        assertFalse(j.toString().contains("zq9marker"))
        assertFalse(j.adds.single().toString().contains("zq9marker"))
        assertFalse(ListsJournal.merge(start, j).toString().contains("zq9marker"))
    }

    @Test fun theJsonIsStrict() {
        val j = ok(append(ListsJournal.snapshot(start), shopping, NewItem("oat milk", "2 L"))).journal
        val text = String(ListsJournal.encode(j))
        assertEquals(JournalParse.Ok(j), ListsJournal.decode(text.toByteArray()))
        assertTrue(text.startsWith("{\"v\":1,\"lists\":[{\"id\":\"${shopping.hex}\",\"role\":\"shopping\"}"))
        // Unknown keys at the top and in a waiting add are kept.
        val extra = text.replace("\"at\":${t0 + 60_000}}", "\"at\":${t0 + 60_000},\"peer\":\"p-1\"}").dropLast(1) + ",\"note\":[1]}"
        val read = (ListsJournal.decode(extra.toByteArray()) as JournalParse.Ok).journal
        assertEquals(extra, String(ListsJournal.encode(read)))
        assertEquals(JournalParse.TooNew(2), ListsJournal.decode("{\"v\":2}".toByteArray()))
        for (bad in listOf(text.replace("\"oat milk\"", "5"), text.replace("\"role\":\"shopping\"", "\"role\":\"packing\""),
            text.replace("\"role\":\"shopping\"", "\"role\":\"shopping\",\"x\":1"), text.replace(",\"adds\":[", ",\"adds\":{").replace("]}", "}}"),
            text.replace("\"qty\":\"2 L\"", "\"qty\":\"\""), text.replace("\"oat milk\"", "\"oat\\nmilk\""), "{\"v\":1,\"lists\":[]}", "{\"v\":0,\"lists\":[],\"adds\":[]}",
            "{\"v\":1,\"lists\":[],\"adds\":[]} x")) {
            assertEquals(bad, JournalParse.Damaged, ListsJournal.decode(bad.toByteArray()))
        }
        assertEquals(JournalParse.Damaged, ListsJournal.decode(ByteArray(ListsLimits.MAX_JOURNAL + 1) { ' '.code.toByte() }))
        // The same add id twice isn't a journal Robot wrote.
        val add = j.adds.single()
        assertFalse(runCatching { ListsJournalFile(j.lists, listOf(add, add)) }.isSuccess)
    }
}
