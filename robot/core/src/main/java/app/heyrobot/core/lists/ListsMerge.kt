// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

// Pure. Two copies of the lists made one (lists-plan.md §6, §9): restore's Add now (LS7), and the rule Robot to Robot's
// shared lists will use in 1.5, so it is built and tested before any list leaves the phone.
//
// The rule, per item, by id: the higher rev wins; equal revs, the later upd; equal again, this phone's. A removal raises
// rev like any change, so a tombstone beats the older edits it follows. A list's own fields (its name) follow the same
// rule with the list's rev. Items only on one side are kept.
//
// Restore's Add ([add]): with your own backup, a list of yours in both (by id, else by role, else by name) is merged, and
// a list only in the backup is added; a list your backup holds from someone else's is matched by id alone. With someone else's backup, their lists stay apart: each arrives as its own list marked from a
// backup, with no role (so your Shopping still takes your voice adds) and every item and change by "backup"; it is merged
// only into a list that came from a backup with the same id (the same backup restored again), never into yours.
// Caps: a list over 500 items loses its oldest tombstones first, then the backup's items that don't fit; lists past 50
// aren't added; all counted. Over 2 MiB, nothing changes.
// LS10 (L4): someone else's lists arrive clean: without the keys this Robot doesn't know on the list, its items and their
// changes (1.5's reserved "shared", "origin", "peer", a change's "who": anyone can make or edit a backup); and an item that
// looks like a secret (a code, a card number) never arrives from any backup, as every other way in refuses it. Both counted
// in itemsSkipped. Your own backup keeps its unknown keys.

/** What restore's Add did, in counts (LS7 says them). */
data class ListsMerged(
    val file: ListsFile,
    val listsAdded: Int = 0,
    val listsMerged: Int = 0,
    val itemsAdded: Int = 0,
    val itemsChanged: Int = 0,
    /** Lists past the cap of 50, not added. */
    val listsSkipped: Int = 0,
    /** Items that didn't fit their list, or whose id another list here already has. */
    val itemsSkipped: Int = 0,
) {
    override fun toString(): String = "ListsMerged($listsAdded, $listsMerged, $itemsAdded, $itemsChanged, $listsSkipped, $itemsSkipped)" // l10n: not text (counts only)
}

sealed class ListsMergeResult {
    data class Ok(val merged: ListsMerged) : ListsMergeResult()
    /** The result would be over 2 MiB: nothing changed. */
    data object TooBig : ListsMergeResult()
}

object ListsMerge {
    /** The item to keep of two with the same id: the higher rev; equal, the later upd; equal, [mine]. */
    fun item(mine: ListItem, theirs: ListItem): ListItem = when {
        theirs.rev != mine.rev -> if (theirs.rev > mine.rev) theirs else mine
        theirs.upd > mine.upd -> theirs
        else -> mine
    }

    /**
     * One list on both sides, kept under [mine]'s id: its own fields (name, role, rev, extra) from the higher list rev
     * (equal: mine); its items, mine in their order then theirs not in mine, each item in both by [item]. Its whose
     * ([ListsList.from]) is this phone's. Within a list's cap: past 500 items, the oldest tombstones go first, then
     * theirs not in mine, from the end.
     */
    fun list(mine: ListsList, theirs: ListsList): ListsList = merged(mine, theirs).first

    /** [list], and how many of theirs didn't fit. */
    private fun merged(mine: ListsList, theirs: ListsList): Pair<ListsList, Int> {
        val win = if (theirs.rev > mine.rev) theirs else mine
        val byId = theirs.items.associateBy { it.id }
        val have = mine.items.mapTo(HashSet()) { it.id }
        val (items, skipped) = fit(mine.items.map { m -> byId[m.id]?.let { item(m, it) } ?: m } + theirs.items.filter { it.id !in have }, have)
        return ListsList(mine.id, win.role, win.name, minOf(mine.made, theirs.made), maxOf(mine.rev, theirs.rev), mine.from, items, win.extra,
            win.raw.filterKeys { it == "role" } + mine.raw.filterKeys { it == "from" }) to skipped
    }

    /**
     * [incoming] (a backup's lists) added to [file]. [yours]: the backup is marked yours. [roleName]: a role's name in the
     * app's language, for someone else's role list, which arrives without its role and so needs a name.
     */
    fun add(file: ListsFile, incoming: ListsFile, yours: Boolean, ids: () -> ListsId, roleName: (ListRole) -> String): ListsMergeResult {
        val lists = file.lists.toMutableList()
        var r = ListsMerged(file)
        for (t0 in incoming.lists) {
            val t1 = if (yours) t0 else theirs(t0, roleName)
            val secrets = t1.items.count(::secret)
            val t = if (secrets == 0) t1 else t1.copy(items = t1.items.filterNot(::secret))
            // By id; a list of yours also by role, then by name. Someone else's (in their backup, or in yours from an
            // earlier restore) only ever by id, into a list that came from a backup: never into yours.
            // A joined list in your own backup is that list here, by id: never a second, empty one the share can't find.
            val at = lists.indexOfFirst { it.id == t.id && (yours && t.yours || it.from == ListFrom.BACKUP || (yours && it.from == ListFrom.PEER && t.from == ListFrom.PEER)) }.takeIf { it >= 0 }
                ?: if (!yours || !t.yours) null
                else lists.indexOfFirst { it.yours && t.role != null && it.role == t.role }.takeIf { it >= 0 }
                    ?: lists.indexOfFirst { l -> l.yours && t.names.any { n -> l.names.any { ListsText.key(it) == ListsText.key(n) } } }.takeIf { it >= 0 }
            if (at != null) {
                val mine = lists[at]
                // An item whose id another list here has stays where it is.
                val elsewhere = lists.filterIndexed { i, _ -> i != at }.flatMapTo(HashSet()) { l -> l.items.map { it.id } }
                val theirs = t.copy(id = mine.id, items = t.items.filter { it.id !in elsewhere })
                val (merged, skipped) = merged(mine, theirs)
                var m = merged
                // The role and name stay unique: when theirs would clash with another list's here, this phone's stay.
                if (lists.anyIndexed { i, l -> i != at && m.role != null && l.role == m.role } ||
                    (m.name != null && lists.anyIndexed { i, l -> i != at && l.yours && l.names.any { ListsText.key(it) == ListsText.key(m.name!!) } })) {
                    m = m.copy(role = mine.role, name = mine.name, raw = mine.raw)
                }
                val mineById = mine.items.associateBy { it.id }
                lists[at] = m
                r = r.copy(listsMerged = r.listsMerged + 1,
                    itemsAdded = r.itemsAdded + m.items.count { it.id !in mineById },
                    itemsChanged = r.itemsChanged + m.items.count { x -> mineById[x.id]?.let { it != x } == true },
                    itemsSkipped = r.itemsSkipped + skipped + secrets + (t.items.size - theirs.items.size))
                continue
            }
            if (lists.size >= ListsLimits.MAX_LISTS) {
                r = r.copy(listsSkipped = r.listsSkipped + 1)
                continue
            }
            val listIds = lists.mapTo(HashSet()) { it.id }
            val itemIds = lists.flatMapTo(HashSet()) { l -> l.items.map { it.id } }
            var n = t
            if (n.id in listIds) n = n.copy(id = ListsEdits.newId(ListsFile(lists), ids, listIds))
            var skipped = 0
            if (yours) {
                // Your list whose role another list here holds (a newer Robot's, from elsewhere) comes in under its name.
                if (n.role != null && lists.any { it.role == n.role }) n = n.copy(role = null, name = n.name ?: roleName(n.role!!))
                skipped = n.items.count { it.id in itemIds }
                n = n.copy(items = n.items.filter { it.id !in itemIds })
            } else {
                // Someone else's items keep their place under new ids when an id is taken here (a copy of your own lists).
                val taken = HashSet(itemIds)
                n = n.copy(items = n.items.map { x ->
                    if (x.id !in taken) x.also { taken += it.id } else x.copy(id = ListsEdits.newId(ListsFile(lists), ids, taken)).also { taken += it.id }
                })
            }
            lists += n
            r = r.copy(listsAdded = r.listsAdded + 1, itemsAdded = r.itemsAdded + n.items.size, itemsSkipped = r.itemsSkipped + skipped + secrets)
        }
        val extra = if (yours) incoming.extra + file.extra else file.extra
        val out = ListsFile(lists, extra)
        if (!ListsCodec.fits(out)) return ListsMergeResult.TooBig
        return ListsMergeResult.Ok(r.copy(file = out))
    }

    /**
     * Someone else's list as it arrives: from a backup, no role (named by it instead), every item and change by "backup"
     * (restore's RestoreTaint.list says the same), and none of the keys this Robot doesn't know (LS10, L4: a newer Robot
     * would read "shared", "origin" or "peer" as a pairing or a person this phone never had).
     */
    fun theirs(t: ListsList, roleName: (ListRole) -> String): ListsList {
        val name = t.name ?: t.role?.let(roleName) ?: t.raw["role"]
        return t.copy(role = null, name = name, from = ListFrom.BACKUP, raw = emptyMap(), extra = emptyMap(), items = t.items.map { x ->
            x.copy(by = ItemBy.BACKUP, raw = x.raw - "by", extra = emptyMap(), hist = x.hist.map { it.copy(by = ItemBy.BACKUP, raw = it.raw - "by", extra = emptyMap()) })
        })
    }

    /** An item that looks like a secret, as every other way in refuses one (ItemText.check, §8.4). */
    private fun secret(x: ListItem): Boolean = ItemText.check(NewItem(x.text, x.qty)) == ItemProblem.SECRET

    /** [all] within a list's cap: the oldest tombstones go first, then items not in [mine], from the end. The items and how many of theirs didn't fit. */
    private fun fit(all: List<ListItem>, mine: Set<ListsId>): Pair<List<ListItem>, Int> {
        var items = all
        var over = items.size - ListsLimits.MAX_ITEMS
        if (over <= 0) return items to 0
        val tombs = items.filter { !it.live }.sortedWith(compareBy({ it.gone }, { it.pos })).take(over).mapTo(HashSet()) { it.id }
        items = items.filter { it.id !in tombs }
        over = items.size - ListsLimits.MAX_ITEMS
        var skipped = 0
        if (over > 0) {
            val drop = items.filter { it.id !in mine }.takeLast(over).mapTo(HashSet()) { it.id }
            skipped = drop.size
            items = items.filter { it.id !in drop }
        }
        return items to skipped
    }

    private inline fun <T> List<T>.anyIndexed(p: (Int, T) -> Boolean): Boolean {
        forEachIndexed { i, x -> if (p(i, x)) return true }
        return false
    }
}
