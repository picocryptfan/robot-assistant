// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.memory.SecretDetector

// Pure. Every change to the lists (lists-plan.md §2, §3.6, §4.4, §9), the same for voice, the card, the page, the widget,
// the journal's merge and restore: each takes the file and gives the next one, or a refusal and nothing changed.
//
// - Every change to an item raises its rev, sets upd and adds to its history (the last 8); a change that changes nothing
//   (ticking a ticked item, adding what's already there) raises nothing and adds nothing.
// - Removing an item leaves a tombstone (gone), hidden, purged after 30 days (EST): Undo puts it back, and 1.5 can carry
//   the removal to another Robot.
// - Adding what's already open replaces its quantity when one is given; adding what's ticked puts it back (§2.3, §3.6).
// - At a cap Robot says so and changes nothing (§2.4). Tombstones count among a list's 500 items; when an add or a move
//   needs room, the oldest tombstones go first (they're hidden, and in 1.4 only Undo uses them), so "Clear the done ones"
//   does make room, as the reply at the cap says.
// - Items that look like secrets are refused, whatever the way in (§8.4).
// - Names are unique among your lists, compared without case, accents or punctuation; a role list you haven't renamed
//   is called by its role's names in both languages, so a list named « Épicerie » can't sit beside an English Shopping.
// - 1.4 never writes "peer" (§9): [ListsWho] refuses it.

/** Who makes a change, how, and when (ms since 1970). 1.4 makes changes as you, while locked, from content or a backup. */
data class ListsWho(val by: ItemBy, val how: ItemHow, val at: Long) {
    init {
        require(by in setOf(ItemBy.YOU, ItemBy.LOCKED, ItemBy.CONTENT, ItemBy.BACKUP) && how != ItemHow.UNKNOWN && at >= 0)
    }
}

/** Why a change wasn't made: nothing changed. */
enum class ListsRefusal {
    /** The name is on a list you joined from another Robot. */
    PEER_NAME,
    NO_LIST,
    /** The item isn't on that list, or was removed. */
    NO_ITEM,
    /** The list holds 500 items, Done and tombstones included, and no tombstone can make room. */
    LIST_FULL,
    /** 50 lists already. */
    LISTS_FULL,
    /** The lists would be over 2 MiB. */
    FILE_FULL,
    /** More than 20 items in one add. */
    TOO_MANY,
    EMPTY,
    TOO_LONG,
    SECRET,
    /** Another of your lists is called that. */
    NAME_TAKEN,
    /** A list already has that role. */
    ROLE_TAKEN,
    /** An edit would make the item the same as another one on the list. */
    ALREADY,
}

/** What a change did to one item. */
enum class ItemOutcome { ADDED, ALREADY, QTY, PUT_BACK, TICKED, UNTICKED, EDITED, MOVED, REMOVED, RESTORED, SAME }

/** One item's part in a change: [was], its quantity before ([ItemOutcome.QTY], [ItemOutcome.PUT_BACK]), for Undo. */
data class ItemDone(val item: ListsId, val outcome: ItemOutcome, val was: String? = null) {
    override fun toString(): String = "ItemDone($item, $outcome)" // l10n: not text (codes for logs and tests)
}

/** What a change gave. */
sealed class ListsEdit {
    /** [file] after the change (the same file when [changed] is false), the [list] it was on, and each item's part. */
    data class Done(val file: ListsFile, val list: ListsId, val items: List<ItemDone> = emptyList(), val changed: Boolean = true) : ListsEdit()
    data class No(val why: ListsRefusal) : ListsEdit()
}

object ListsEdits {
    /** The lists made the first time Lists is used (Q2): Shopping and To-do. Only when there is no file at all (ListsEnvelope.update). */
    fun firstUse(at: Long, ids: () -> ListsId): ListsFile {
        val a = ids()
        var b = ids()
        while (b == a) b = ids()
        return ListsFile(listOf(ListsList(a, role = ListRole.SHOPPING, made = at), ListsList(b, role = ListRole.TODO, made = at)))
    }

    // Lists.

    /** A new list called [name], or the list for [role] (its name then follows the app's language). */
    fun make(file: ListsFile, name: String?, role: ListRole?, who: ListsWho, ids: () -> ListsId): ListsEdit {
        require(name != null || role != null)
        val clean = name?.let(ItemText::clean)
        if (clean != null) problem(clean, ListsLimits.MAX_NAME)?.let { return ListsEdit.No(it) }
        if (file.lists.size >= ListsLimits.MAX_LISTS) return ListsEdit.No(ListsRefusal.LISTS_FULL)
        if (role != null && file.withRole(role) != null) return ListsEdit.No(ListsRefusal.ROLE_TAKEN)
        val names = if (clean != null) listOf(clean) else role!!.names
        if (taken(file, names, except = null)) return ListsEdit.No(ListsRefusal.NAME_TAKEN)
        // A second list named like one you joined would look like it, and never reach the other person. A role list is still yours to make.
        if (clean != null && file.lists.any { l -> l.from == ListFrom.PEER && l.names.any { ListsText.key(it) == ListsText.key(clean) } }) return ListsEdit.No(ListsRefusal.PEER_NAME)
        val list = ListsList(newId(file, ids), role = role, name = clean, made = who.at)
        return done(file, ListsFile(file.lists + list, file.extra), list.id)
    }

    /** [list] renamed. A role list keeps its role: "shopping list" still finds it. */
    fun rename(file: ListsFile, list: ListsId, name: String, who: ListsWho): ListsEdit {
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        val clean = ItemText.clean(name)
        problem(clean, ListsLimits.MAX_NAME)?.let { return ListsEdit.No(it) }
        if (clean == l.name) return ListsEdit.Done(file, list, changed = false)
        if (taken(file, listOf(clean), except = list)) return ListsEdit.No(ListsRefusal.NAME_TAKEN)
        return done(file, file.with(l.copy(name = clean, rev = l.rev + 1)), list)
    }

    /** [list] gone, with its items (its own dialog, LS5). */
    fun deleteList(file: ListsFile, list: ListsId): ListsEdit {
        if (file.list(list) == null) return ListsEdit.No(ListsRefusal.NO_LIST)
        return done(file, file.copy(lists = file.lists.filter { it.id != list }), list)
    }

    /** Whether one of [names] is a name of one of your lists (other than [except]). */
    private fun taken(file: ListsFile, names: List<String>, except: ListsId?): Boolean {
        val keys = names.mapTo(HashSet(), ListsText::key)
        return file.lists.any { l -> l.id != except && l.yours && l.names.any { ListsText.key(it) in keys } }
    }

    private fun problem(name: String, max: Int): ListsRefusal? = when {
        name.isBlank() -> ListsRefusal.EMPTY
        name.length > max -> ListsRefusal.TOO_LONG
        !ListsText.line(name, max) -> ListsRefusal.EMPTY
        SecretDetector.isSecret(name) -> ListsRefusal.SECRET
        else -> null
    }

    // Items.

    /**
     * [items] added to [list], in order: new ones at the end; one already open there gets the quantity given (if any);
     * one ticked comes back. All or nothing: a secret, a cap or a missing list changes nothing.
     */
    fun add(file: ListsFile, list: ListsId, items: List<NewItem>, who: ListsWho, ids: () -> ListsId): ListsEdit {
        if (items.isEmpty()) return ListsEdit.No(ListsRefusal.EMPTY)
        if (items.size > ListsLimits.MAX_ADD) return ListsEdit.No(ListsRefusal.TOO_MANY)
        for (i in items) ItemText.check(i)?.let { return ListsEdit.No(refusal(it)) }
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        val now = l.items.toMutableList()
        val taken = HashSet<ListsId>()
        val out = ArrayList<ItemDone>()
        for (n in items) {
            val k = now.indexOfFirst { it.live && ItemText.same(it.text, n.text) }
            if (k < 0) {
                val id = newId(file, ids, taken)
                taken += id
                val pos = (now.maxOfOrNull { it.pos } ?: 0L) + ListsLimits.POS_STEP
                now += ListItem(id, n.text, n.qty, false, pos, who.at, who.at, who.by, who.how, 1, null, listOf(change(ItemOp.ADD, who)))
                out += ItemDone(id, ItemOutcome.ADDED)
                continue
            }
            val x = now[k]
            val qty = n.qty ?: x.qty
            when {
                x.done -> {
                    now[k] = changed(x.copy(done = false, qty = qty), ItemOp.UNTICK, who)
                    out += ItemDone(x.id, ItemOutcome.PUT_BACK, x.qty)
                }
                qty != x.qty -> {
                    now[k] = changed(x.copy(qty = qty), ItemOp.EDIT, who)
                    out += ItemDone(x.id, ItemOutcome.QTY, x.qty)
                }
                else -> out += ItemDone(x.id, ItemOutcome.ALREADY)
            }
        }
        val fitted = room(now) ?: return ListsEdit.No(ListsRefusal.LIST_FULL)
        return done(file, file.with(l.copy(items = fitted)), list, out)
    }

    /** [item] ticked: it moves under Done. */
    fun tick(file: ListsFile, list: ListsId, item: ListsId, who: ListsWho): ListsEdit = setDone(file, list, item, true, who)

    /** [item] unticked: back on the list. */
    fun untick(file: ListsFile, list: ListsId, item: ListsId, who: ListsWho): ListsEdit = setDone(file, list, item, false, who)

    private fun setDone(file: ListsFile, list: ListsId, item: ListsId, done: Boolean, who: ListsWho): ListsEdit =
        onItem(file, list, item) { x ->
            if (x.done == done) x to ItemOutcome.SAME
            else changed(x.copy(done = done), if (done) ItemOp.TICK else ItemOp.UNTICK, who) to if (done) ItemOutcome.TICKED else ItemOutcome.UNTICKED
        }

    /** [item]'s words changed to [text] (as typed: cleaned, no article taken off). */
    fun edit(file: ListsFile, list: ListsId, item: ListsId, text: String, who: ListsWho): ListsEdit {
        val clean = ItemText.clean(text)
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        val x = l.item(item)?.takeIf { it.live } ?: return ListsEdit.No(ListsRefusal.NO_ITEM)
        ItemText.check(NewItem(clean, x.qty))?.let { return ListsEdit.No(refusal(it)) }
        if (clean == x.text) return ListsEdit.Done(file, list, listOf(ItemDone(item, ItemOutcome.SAME)), changed = false)
        if (l.live.any { it.id != item && ItemText.same(it.text, clean) }) return ListsEdit.No(ListsRefusal.ALREADY)
        return done(file, file.with(l.copy(items = l.items.map { if (it.id == item) changed(it.copy(text = clean), ItemOp.EDIT, who) else it })), list,
            listOf(ItemDone(item, ItemOutcome.EDITED)))
    }

    /** [item]'s quantity set to [qty], or taken off when it is null or blank. */
    fun quantity(file: ListsFile, list: ListsId, item: ListsId, qty: String?, who: ListsWho): ListsEdit {
        val clean = qty?.let(ItemText::clean)?.takeIf { it.isNotEmpty() }
        val x = file.list(list)?.item(item)?.takeIf { it.live } ?: return ListsEdit.No(if (file.list(list) == null) ListsRefusal.NO_LIST else ListsRefusal.NO_ITEM)
        ItemText.check(NewItem(x.text, clean))?.let { return ListsEdit.No(refusal(it)) }
        return onItem(file, list, item) {
            if (it.qty == clean) it to ItemOutcome.SAME else changed(it.copy(qty = clean), ItemOp.EDIT, who) to ItemOutcome.QTY
        }.withWas(item, x.qty)
    }

    /** [item] first on its list ("Move to top", the only reordering in 1.4). */
    fun moveToTop(file: ListsFile, list: ListsId, item: ListsId, who: ListsWho): ListsEdit {
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        val top = l.live.firstOrNull()?.pos ?: 0L
        return onItem(file, list, item) { x ->
            if (l.live.first().id == x.id) x to ItemOutcome.SAME
            else changed(x.copy(pos = top - ListsLimits.POS_STEP), ItemOp.MOVE, who) to ItemOutcome.MOVED
        }
    }

    /**
     * [item] moved from [list] to [to]: a tombstone where it was, and on [to] a new item (a new id: ids are per place) with
     * its words, quantity, tick, who added it and how, its history and the keys this Robot doesn't know; or, when [to]
     * has it already, that one, as [add] would treat it. [ListsEdit.Done.list] is [to]; its items are the item on [to],
     * then the tombstone.
     */
    fun moveTo(file: ListsFile, list: ListsId, item: ListsId, to: ListsId, who: ListsWho, ids: () -> ListsId): ListsEdit {
        val from = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        val dest = file.list(to)?.takeIf { it.id != list } ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        val x = from.item(item)?.takeIf { it.live } ?: return ListsEdit.No(ListsRefusal.NO_ITEM)
        val there = dest.items.indexOfFirst { it.live && ItemText.same(it.text, x.text) }
        val now = dest.items.toMutableList()
        val result: ItemDone
        if (there >= 0) {
            val y = now[there]
            val qty = x.qty ?: y.qty
            result = when {
                y.done && !x.done -> ItemDone(y.id, ItemOutcome.PUT_BACK, y.qty).also { now[there] = changed(y.copy(done = false, qty = qty), ItemOp.UNTICK, who) }
                qty != y.qty -> ItemDone(y.id, ItemOutcome.QTY, y.qty).also { now[there] = changed(y.copy(qty = qty), ItemOp.EDIT, who) }
                else -> ItemDone(y.id, ItemOutcome.ALREADY)
            }
        } else {
            val id = newId(file, ids)
            val pos = (now.maxOfOrNull { it.pos } ?: 0L) + ListsLimits.POS_STEP
            // A new item here: what the old list's Robot was sent says nothing about this one, so it's owed to this list's.
            now += x.copy(id = id, pos = pos, upd = who.at, rev = 1, gone = null, hist = (x.hist + change(ItemOp.MOVE, who)).takeLast(ListsLimits.MAX_HIST), extra = x.extra - ListsShared.SENT - ListsShared.REQ)
            result = ItemDone(id, ItemOutcome.MOVED)
        }
        val fitted = room(now) ?: return ListsEdit.No(ListsRefusal.LIST_FULL)
        val left = from.copy(items = from.items.map { if (it.id == item) changed(it.copy(gone = who.at), ItemOp.MOVE, who) else it })
        return done(file, file.with(left).with(dest.copy(items = fitted)), to, listOf(result, ItemDone(item, ItemOutcome.REMOVED)))
    }

    /** [items] removed from [list]: tombstones, hidden, purged after 30 days. All or nothing. */
    fun remove(file: ListsFile, list: ListsId, items: List<ListsId>, who: ListsWho): ListsEdit {
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        if (items.isEmpty() || items.any { id -> l.item(id)?.live != true }) return ListsEdit.No(ListsRefusal.NO_ITEM)
        return gone(file, l, items.toSet(), who)
    }

    /** [items] put back from their tombstones (Undo of a removal), ticked or not as they were. All or nothing. */
    fun restore(file: ListsFile, list: ListsId, items: List<ListsId>, who: ListsWho): ListsEdit {
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        if (items.isEmpty() || items.any { l.item(it) == null }) return ListsEdit.No(ListsRefusal.NO_ITEM)
        val set = items.toSet()
        val out = ArrayList<ItemDone>()
        val next = l.items.map { x ->
            when {
                x.id !in set -> x
                x.live -> x.also { out += ItemDone(x.id, ItemOutcome.SAME) }
                else -> changed(x.copy(gone = null), ItemOp.RESTORE, who).also { out += ItemDone(x.id, ItemOutcome.RESTORED) }
            }
        }
        return done(file, file.with(l.copy(items = next)), list, out)
    }

    /** The ticked items of [list] removed (Undo restores them). */
    fun clearDone(file: ListsFile, list: ListsId, who: ListsWho): ListsEdit {
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        return gone(file, l, l.done.mapTo(HashSet()) { it.id }, who)
    }

    /** Every item of [list] removed, open ones too ("empty the shopping list", confirmed by a tap). */
    fun empty(file: ListsFile, list: ListsId, who: ListsWho): ListsEdit {
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        return gone(file, l, l.live.mapTo(HashSet()) { it.id }, who)
    }

    /** Tombstones removed for good once [ListsLimits.TOMBSTONE_MS] old (the vault, at each write). The same file when none is. */
    fun purge(file: ListsFile, now: Long): ListsFile {
        val cut = now - ListsLimits.TOMBSTONE_MS
        val sharedCut = now - ListsLimits.SHARED_TOMBSTONE_MS
        // A shared list keeps a tombstone until the other Robot has it, or 90 days: a removal it never heard of would come back.
        fun keep(l: ListsList, i: ListItem): Boolean = i.gone == null || i.gone > cut ||
            (ListsShared.with(l).isNotEmpty() && i.gone > sharedCut && i.extra[ListsShared.SENT] as? Long != i.rev)
        if (file.lists.all { l -> l.items.all { keep(l, it) } }) return file
        return file.copy(lists = file.lists.map { l -> l.copy(items = l.items.filter { keep(l, it) }) })
    }

    private fun gone(file: ListsFile, l: ListsList, ids: Set<ListsId>, who: ListsWho): ListsEdit {
        val out = ArrayList<ItemDone>()
        val next = l.items.map { x ->
            if (x.id in ids && x.live) changed(x.copy(gone = who.at), ItemOp.REMOVE, who).also { out += ItemDone(x.id, ItemOutcome.REMOVED) } else x
        }
        return done(file, file.with(l.copy(items = next)), l.id, out)
    }

    // Helpers.

    /** [x] after a change: its rev raised, upd set, the change in its history (the last 8). */
    internal fun changed(x: ListItem, op: ItemOp, who: ListsWho): ListItem =
        x.copy(rev = x.rev + 1, upd = who.at, hist = (x.hist + change(op, who)).takeLast(ListsLimits.MAX_HIST))

    private fun change(op: ItemOp, who: ListsWho) = ListChange(op, who.at, who.by, who.how)

    /** One live item of [list] changed by [f], which says what it did. */
    private fun onItem(file: ListsFile, list: ListsId, item: ListsId, f: (ListItem) -> Pair<ListItem, ItemOutcome>): ListsEdit {
        val l = file.list(list) ?: return ListsEdit.No(ListsRefusal.NO_LIST)
        val x = l.item(item)?.takeIf { it.live } ?: return ListsEdit.No(ListsRefusal.NO_ITEM)
        val (y, outcome) = f(x)
        if (outcome == ItemOutcome.SAME) return ListsEdit.Done(file, list, listOf(ItemDone(item, outcome)), changed = false)
        return done(file, file.with(l.copy(items = l.items.map { if (it.id == item) y else it })), list, listOf(ItemDone(item, outcome)))
    }

    private fun ListsEdit.withWas(item: ListsId, was: String?): ListsEdit =
        if (this is ListsEdit.Done) copy(items = items.map { if (it.item == item && it.outcome == ItemOutcome.QTY) it.copy(was = was) else it }) else this

    /**
     * [items] within a list's cap: as they are when they fit; else without as many of the oldest tombstones as needed; null
     * when even that isn't enough.
     */
    private fun room(items: List<ListItem>): List<ListItem>? {
        val over = items.size - ListsLimits.MAX_ITEMS
        if (over <= 0) return items
        val tombs = items.filter { !it.live }.sortedWith(compareBy({ it.gone }, { it.pos }))
        if (tombs.size < over) return null
        val drop = tombs.take(over).mapTo(HashSet()) { it.id }
        return items.filter { it.id !in drop }
    }

    /** The change made: [next] when it fits within 2 MiB, else FILE_FULL. */
    private fun done(before: ListsFile, next: ListsFile, list: ListsId, items: List<ItemDone> = emptyList()): ListsEdit {
        if (next == before) return ListsEdit.Done(before, list, items, changed = false)
        if (!ListsCodec.fits(next)) return ListsEdit.No(ListsRefusal.FILE_FULL)
        return ListsEdit.Done(next, list, items)
    }

    /** A fresh id that no list or item in [file] (nor [also]) has. */
    internal fun newId(file: ListsFile, ids: () -> ListsId, also: Set<ListsId> = emptySet()): ListsId {
        val used = HashSet<ListsId>()
        for (l in file.lists) {
            used += l.id
            for (i in l.items) used += i.id
        }
        repeat(16) {
            val id = ids()
            if (id !in used && id !in also) return id
        }
        throw IllegalStateException("ids") // l10n: not text (a broken random source)
    }

    private fun refusal(p: ItemProblem): ListsRefusal = when (p) {
        ItemProblem.EMPTY -> ListsRefusal.EMPTY
        ItemProblem.TOO_LONG -> ListsRefusal.TOO_LONG
        ItemProblem.TOO_MANY -> ListsRefusal.TOO_MANY
        ItemProblem.SECRET -> ListsRefusal.SECRET
    }
}
