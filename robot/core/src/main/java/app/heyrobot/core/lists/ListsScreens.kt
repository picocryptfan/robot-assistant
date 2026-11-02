// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

// Pure. What the Lists screens do by code (lists-plan.md §7.2, §7.3; LS5), beside ListsEdits: a note made into a list's
// items ("Make a list from this note"), a list as text for the share sheet ("Share as text"), and a new list's name that
// is a default list's ("Shopping", « Épicerie »), which makes that list again rather than a second one of the same name.
// Nothing here logs or keeps a list's words.

/** The items a note gave ([items], in order, once each) and how many of its lines couldn't be items ([skipped]). */
data class NoteItems(val items: List<NewItem>, val skipped: Int) {
    override fun toString(): String = "NoteItems(${items.size}, skipped=$skipped)" // l10n: not text (never an item's words in a log)
}

object NoteToList {
    /** A line's bullet or number: "- ", "* ", "• ", "1. ", "2) ", "[ ] ", "[x] ", "☐ ", "✓ ". */
    private val BULLET = Regex("""^\s*(?:[-*•·◦‣–—+]|\d{1,3}[.)]|\[[ xX✓✔]?]|[☐☑☒✓✔])\s*""") // l10n: not text (a parser's pattern)

    /**
     * The items in a note's [text]: one per line, its bullet or number taken off; a note of one line, the pieces between
     * its commas. Each piece is read as a spoken one is (ItemText.item: the leading article off, a quantity read). A piece
     * that can't be an item (over 120 characters, a secret) is skipped and counted; the same item twice is added once.
     */
    fun read(text: String): NoteItems {
        val lines = text.lines().map { it.replace(BULLET, "").trim() }.filter { it.isNotEmpty() }
        val pieces = if (lines.size == 1) lines[0].split(',').map { it.trim() }.filter { it.isNotEmpty() } else lines
        val out = ArrayList<NewItem>()
        var skipped = 0
        for (p in pieces) {
            val item = ItemText.item(p)
            if (item.text.isEmpty()) continue
            if (ItemText.check(item) != null) {
                skipped++
                continue
            }
            if (out.none { ItemText.same(it.text, item.text) }) out += item
        }
        return NoteItems(out, skipped)
    }

    /** [items] added to [list], [ListsLimits.MAX_ADD] at a time, in one change: all of them or, at a cap, none. */
    fun add(file: ListsFile, list: ListsId, items: List<NewItem>, who: ListsWho, ids: () -> ListsId): ListsEdit {
        if (items.isEmpty()) return ListsEdit.No(ListsRefusal.EMPTY)
        var acc = ListsEdit.Done(file, list, changed = false)
        for (chunk in items.chunked(ListsLimits.MAX_ADD)) {
            when (val e = ListsEdits.add(acc.file, list, chunk, who, ids)) {
                is ListsEdit.No -> return e
                is ListsEdit.Done -> acc = ListsEdit.Done(e.file, list, acc.items + e.items, acc.changed || e.changed)
            }
        }
        return acc
    }
}

object ListsShare {
    /**
     * [list] as text for the share sheet, under its [name] as shown: its open items, one a line, each with its quantity
     * ("Shopping\n- Oat milk (2 L)\n- Eggs"). Ticked and removed items stay off.
     */
    fun text(name: String, list: ListsList): String = buildString {
        append(name)
        for (x in list.open) {
            append("\n- ").append(ItemText.shown(x.text))
            x.qty?.let { append(" (").append(it).append(')') }
        }
    }
}

object ListsNames {
    /** The default list [name] names in either language ("shopping", « À faire »), or null. */
    fun role(name: String): ListRole? = ListRole.entries.firstOrNull { r -> r.names.any { ItemText.same(it, name) } }

    /**
     * A new list called [name] from a screen: the default list it names again when you have none (made by its role, so
     * its name follows the app's language and "shopping list" finds it), else a list of that name.
     */
    fun make(file: ListsFile, name: String, who: ListsWho, ids: () -> ListsId): ListsEdit {
        val role = role(name)?.takeIf { file.withRole(it) == null }
        return ListsEdits.make(file, if (role != null) null else name, role, who, ids)
    }
}
