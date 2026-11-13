// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.memory.MemoryText
import java.security.SecureRandom

// Pure. Robot Lists (lists-plan.md §2, §4.4, §9): named lists of short items, kept in one file sealed whole
// (ListsEnvelope, lists.enc) and never on disk unsealed. A list has a name or a role (shopping, to-do) and items; an item
// is a line of text, an optional quantity and a ticked state, with who added it and how, a change counter and its last
// changes. Removed items stay hidden as tombstones for 30 days (EST) so Undo and, in 1.5, other Robots can see the removal.
// Items are content, never instructions (§8.4): nothing here puts one in a log, a prompt or toString.
//
// Every invariant the file must keep is checked when a value is built (init), so ListsEdits and ListsMerge can't make a
// file ListsCodec would refuse, and ListsCodec refuses whatever breaks one: ids unique across the whole file, at most one
// list per role, the caps of §2.4, one-line text.

/** §2.4's caps (EST), and the other fixed numbers of the file. */
object ListsLimits {
    const val MAX_LISTS = 50
    /** Items in one list, Done and tombstones included. */
    const val MAX_ITEMS = 500
    /** Items in one spoken add. */
    const val MAX_ADD = 20
    /** Changes kept in an item's history. */
    const val MAX_HIST = 8
    /** The lists' JSON, before sealing. */
    const val MAX_FILE = 2 * 1024 * 1024
    /** Adds waiting in the locked journal, and its JSON's size. */
    const val MAX_JOURNAL_ADDS = 50
    const val MAX_JOURNAL = 64 * 1024
    /** Items read aloud before "and 4 more" (LS3). */
    const val MAX_READ_ALOUD = 12
    const val MAX_NAME = 40
    const val MAX_TEXT = 120
    const val MAX_QTY = 16
    /** How long a removed item stays as a tombstone. */
    const val TOMBSTONE_MS = 30L * 24 * 60 * 60 * 1000
    /** On a shared list a tombstone stays until the other Robot has it, or this long (lists-plan.md §9). */
    const val SHARED_TOMBSTONE_MS = 90L * 24 * 60 * 60 * 1000
    /** An item's place: added order × 1,000; Move to top takes the smallest minus 1,000. */
    const val POS_STEP = 1000L
}

/** A list's or an item's id: 16 random bytes as 32 lower-case hex characters. Never made from a name, never reused. */
class ListsId private constructor(val hex: String) {
    override fun equals(other: Any?): Boolean = other is ListsId && other.hex == hex
    override fun hashCode(): Int = hex.hashCode()
    override fun toString(): String = hex

    companion object {
        const val BYTES = 16
        private val HEX = Regex("[0-9a-f]{32}")

        /** [hex] when it is an id, else null. */
        fun of(hex: String?): ListsId? = hex?.takeIf { it.length == 2 * BYTES && HEX.matches(it) }?.let(::ListsId)

        fun new(random: SecureRandom): ListsId = ListsId(ByteArray(BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) })
    }
}

/**
 * The role of a default list (§2.2): Robot finds it by any of its usual names in either language, and while you haven't
 * renamed it, it is called by its role's name in the app's language. [names] are those names in both languages, compared
 * (never shown) so no other list of yours takes one: the words a screen or a reply shows come from LS3's catalog, which
 * says the same. JSON: the lower-case name.
 */
enum class ListRole(val names: List<String>) {
    SHOPPING(listOf("Shopping", "Épicerie")), // l10n: not text (names compared, never shown: ListsStrings says them)
    TODO(listOf("To-do", "À faire")); // l10n: not text (names compared, never shown: ListsStrings says them)

    val json: String get() = name.lowercase(java.util.Locale.ROOT)

    companion object {
        /**
         * Review Low 7: the names a skill's "add to <name>" leaves to Lists: your lists' and the roles', only while Lists is
         * on (off, Lists hears none of them, so a skill called "Shopping" gets its step).
         */
        fun forSkills(yours: List<String>, listsOn: Boolean): List<String> =
            if (listsOn) yours + entries.flatMap { it.names } else emptyList()
    }
}

/** Whose a list is: yours, or from someone else's backup (§6). UNKNOWN: a newer Robot's, never taken for yours. */
enum class ListFrom { YOU, BACKUP, UNKNOWN, PEER }

/**
 * Who an item came from (§4.4): you; [LOCKED], said to the phone while it was locked, maybe by someone else; [CONTENT], a
 * screen, a page or a message, through a card; [BACKUP], someone else's backup. [PEER] is reserved for 1.5 (another
 * person's Robot): 1.4 reads it and never writes it. UNKNOWN: a newer Robot's, never taken for yours.
 */
enum class ItemBy { YOU, LOCKED, CONTENT, BACKUP, PEER, UNKNOWN }

/** How an item came or changed (§4.4). UNKNOWN: a newer Robot's. */
enum class ItemHow { VOICE, TYPED, CARD, WIDGET, MODEL, NOTE, RESTORE, UNKNOWN, LINK }

/** What one change to an item was (§4.4's hist). UNKNOWN: a newer Robot's. */
enum class ItemOp { ADD, EDIT, TICK, UNTICK, MOVE, REMOVE, RESTORE, UNKNOWN }

/** One change in an item's history. [extra]: keys this Robot doesn't know, kept (1.5 may say which person). */
data class ListChange(
    val op: ItemOp,
    val at: Long,
    val by: ItemBy,
    val how: ItemHow,
    val extra: Map<String, Any?> = emptyMap(),
    /** Known keys whose value this Robot didn't know ("op" → "share"), written back while unchanged. */
    internal val raw: Map<String, String> = emptyMap(),
) {
    init {
        require(at >= 0)
        require(known(op, ItemOp.UNKNOWN, "op", raw) && known(by, ItemBy.UNKNOWN, "by", raw) && known(how, ItemHow.UNKNOWN, "how", raw))
        FileIndexCodec.checkExtra(extra)
    }

    override fun toString(): String = "ListChange($op, $by, $how)" // l10n: not text (codes for logs and tests)
}

/**
 * One item. [text]: as said or typed, cleaned (ItemText); [qty]: "2 L", "3"; [done]: ticked; [pos]: its place; [at]: when
 * added; [upd]: when last changed (shown, never used for an order); [by] and [how]; [rev]: raised by every change; [gone]:
 * when it was removed (a tombstone, hidden); [hist]: the last [ListsLimits.MAX_HIST] changes. [extra]: keys this Robot
 * doesn't know, kept, 1.5's reserved "peer" among them.
 */
data class ListItem(
    val id: ListsId,
    val text: String,
    val qty: String? = null,
    val done: Boolean = false,
    val pos: Long,
    val at: Long,
    val upd: Long,
    val by: ItemBy,
    val how: ItemHow,
    val rev: Long = 1,
    val gone: Long? = null,
    val hist: List<ListChange> = emptyList(),
    val extra: Map<String, Any?> = emptyMap(),
    internal val raw: Map<String, String> = emptyMap(),
) {
    init {
        require(ListsText.line(text, ListsLimits.MAX_TEXT))
        require(qty == null || ListsText.line(qty, ListsLimits.MAX_QTY))
        require(at >= 0 && upd >= 0 && rev >= 0 && (gone == null || gone >= 0))
        require(hist.size <= ListsLimits.MAX_HIST)
        require(known(by, ItemBy.UNKNOWN, "by", raw) && known(how, ItemHow.UNKNOWN, "how", raw))
        FileIndexCodec.checkExtra(extra)
    }

    /** Not removed: shown, counted, matched. */
    val live: Boolean get() = gone == null

    override fun toString(): String = "ListItem($id, done=$done, rev=$rev, gone=${gone != null})" // l10n: not text (never an item's words in a log)
}

/**
 * One list. [role] (shopping, to-do) or [name] (yours for it; absent for a role list you haven't renamed), [made],
 * [rev] (raised by every change to the list itself: its name), [from], [items] in the file's order (shown by [ListItem.pos]).
 * [extra]: keys this Robot doesn't know, kept, 1.5's reserved "shared" and "origin" among them.
 */
data class ListsList(
    val id: ListsId,
    val role: ListRole? = null,
    val name: String? = null,
    val made: Long,
    val rev: Long = 1,
    val from: ListFrom = ListFrom.YOU,
    val items: List<ListItem> = emptyList(),
    val extra: Map<String, Any?> = emptyMap(),
    internal val raw: Map<String, String> = emptyMap(),
) {
    init {
        require(name == null || ListsText.line(name, ListsLimits.MAX_NAME))
        // A list is called something: its name, its role, or a newer Robot's role this one keeps.
        require(name != null || role != null || raw.containsKey("role"))
        require(made >= 0 && rev >= 0)
        require(items.size <= ListsLimits.MAX_ITEMS)
        require(known(from, ListFrom.UNKNOWN, "from", raw))
        FileIndexCodec.checkExtra(extra)
    }

    /** Items not removed, in their order. */
    val live: List<ListItem> get() = items.filter { it.live }.sortedBy { it.pos }
    val open: List<ListItem> get() = live.filter { !it.done }
    val done: List<ListItem> get() = live.filter { it.done }

    fun item(id: ListsId): ListItem? = items.firstOrNull { it.id == id }

    /** Yours: not from someone else's backup, nor a newer Robot's that this one can't place. */
    val yours: Boolean get() = from == ListFrom.YOU

    /** The names this list answers to when names are compared: yours for it, else its role's in both languages. */
    val names: List<String> get() = if (name != null) listOf(name) else role?.names.orEmpty()

    override fun toString(): String = "ListsList($id, $role, ${items.size} items)" // l10n: not text (never a list's name in a log)
}

/** Every list, in order, and the top's keys this Robot doesn't know, kept. */
data class ListsFile(val lists: List<ListsList> = emptyList(), val extra: Map<String, Any?> = emptyMap()) {
    init {
        require(lists.size <= ListsLimits.MAX_LISTS)
        require(lists.mapTo(HashSet()) { it.id }.size == lists.size)
        val itemIds = HashSet<ListsId>()
        for (l in lists) for (i in l.items) require(itemIds.add(i.id))
        val roles = lists.mapNotNull { it.role }
        require(roles.toSet().size == roles.size)
        FileIndexCodec.checkExtra(extra)
    }

    fun list(id: ListsId): ListsList? = lists.firstOrNull { it.id == id }
    fun withRole(role: ListRole): ListsList? = lists.firstOrNull { it.role == role }

    /** The list holding the item [id], tombstones included. */
    fun holding(item: ListsId): ListsList? = lists.firstOrNull { l -> l.items.any { it.id == item } }

    /** [list] put in place of the list with its id. */
    fun with(list: ListsList): ListsFile = copy(lists = lists.map { if (it.id == list.id) list else it })

    override fun toString(): String = "ListsFile(${lists.size} lists)" // l10n: not text (toString for logs and tests)
}

/** A value this Robot didn't know is UNKNOWN only with the string it read, which is written back as it was. */
private fun <E : Enum<E>> known(e: E, unknown: E, key: String, raw: Map<String, String>): Boolean = e != unknown || raw.containsKey(key)

/** Text rules shared by the model, the codec and the edits. */
internal object ListsText {
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** One line of 1 to [max] characters: no control or direction characters, not blank, no space at either end. */
    fun line(s: String, max: Int): Boolean = s.length in 1..max && FileIndexCodec.plain(s) && s.isNotBlank() && s.trim() == s

    /** Two names or items are the same when this is: case, accents, spaces and punctuation aside ("To-do" is "todo"). */
    fun key(s: String): String = MemoryText.fold(s).replace(NOT_WORD, "")

    /** Folded for matching, words kept apart: "Noël gifts!" → "noel gifts". */
    fun fold(s: String): String = MemoryText.fold(s).replace(NOT_WORD, " ").trim()
}
