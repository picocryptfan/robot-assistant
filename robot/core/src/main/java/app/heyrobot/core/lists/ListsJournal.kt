// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.StrictJson
import app.heyrobot.core.files.FileIndexCodec

// Pure. Adds said while the phone is locked (lists-plan.md §4.6, decision D3): locked.enc, sealed (ListsEnvelope) under
// the key that works while locked, holds your lists' ids, roles and folded names, refreshed at every unlocked write of
// lists.enc, and the adds waiting: item id, list id, words, quantity, time.
//
//   {"v":1,"lists":[{"id":"<32 hex>","role":"shopping"},{"id":"<32 hex>","name":"packing"}],
//    "adds":[{"id":"<32 hex>","list":"<32 hex>","text":"oat milk","qty":"2 L","at":1761150000000}]}
//
// The journal is never read to answer anything: a locked "what's on the shopping list" says to unlock even when adds
// wait. So the adds are internal to core: the app can count them ([ListsJournalFile.waiting]) and merge them, never read
// them. At unlock they're merged into lists.enc by item id ([ListsJournal.merge]): an add whose id is already there is
// skipped, so merging twice changes nothing and a crash between writing lists.enc and emptying the journal loses nothing.
// Each merged item is by "locked"; an item already there keeps its quantity, and a ticked one comes back (LS10). Only your lists are in it (not lists from someone else's backup); a list Robot can only
// name by a newer Robot's role isn't either.
//
// Reading is strict, as lists v1's: anything broken makes the journal Damaged (LS2 keeps it aside). Unknown keys are kept
// at the top and in an add while it waits; an add merged into a list leaves its unknown keys behind.

/** A list as the journal knows it, to match spoken names while the lists can't be opened: its role and folded name. */
data class JournalList(val id: ListsId, val role: ListRole? = null, val name: String? = null) {
    init {
        require(role != null || name != null)
        require(name == null || ListsText.line(name, ListsJournal.MAX_FOLDED))
    }

    override fun toString(): String = "JournalList($id, $role)" // l10n: not text (never a list's name in a log)
}

/** One add said while locked, waiting. [extra]: keys this Robot doesn't know, kept while it waits. */
data class JournalAdd(val id: ListsId, val list: ListsId, val text: String, val qty: String? = null, val at: Long, val extra: Map<String, Any?> = emptyMap()) {
    init {
        require(ListsText.line(text, ListsLimits.MAX_TEXT) && (qty == null || ListsText.line(qty, ListsLimits.MAX_QTY)) && at >= 0)
        FileIndexCodec.checkExtra(extra)
    }

    override fun toString(): String = "JournalAdd($id, $list)" // l10n: not text (never an item's words in a log)
}

/** The journal: your lists as it knows them, and the adds waiting (core only: never read to answer). */
class ListsJournalFile(val lists: List<JournalList> = emptyList(), internal val adds: List<JournalAdd> = emptyList(), val extra: Map<String, Any?> = emptyMap()) {
    init {
        require(lists.size <= ListsLimits.MAX_LISTS && lists.mapTo(HashSet()) { it.id }.size == lists.size)
        require(adds.size <= ListsLimits.MAX_JOURNAL_ADDS && adds.mapTo(HashSet()) { it.id }.size == adds.size)
        FileIndexCodec.checkExtra(extra)
    }

    /** How many adds wait for the unlock. */
    val waiting: Int get() = adds.size

    override fun equals(other: Any?): Boolean = other is ListsJournalFile && other.lists == lists && other.adds == adds && other.extra == extra
    override fun hashCode(): Int = (lists.hashCode() * 31 + adds.hashCode()) * 31 + extra.hashCode()
    override fun toString(): String = "ListsJournalFile(${lists.size} lists, ${adds.size} waiting)" // l10n: not text (counts only)
}

sealed class JournalParse {
    data class Ok(val journal: ListsJournalFile) : JournalParse()
    data class TooNew(val version: Int) : JournalParse()
    data object Damaged : JournalParse()
}

/** Why a locked add wasn't kept. */
enum class JournalRefusal {
    /** Not one of the lists the journal knows: "Unlock your phone to make one." */
    NO_LIST,
    /** 50 adds or 64 KiB waiting: "Robot can't add more while the phone is locked." */
    FULL,
    EMPTY,
    TOO_LONG,
    TOO_MANY,
    SECRET,
}

sealed class JournalAppend {
    /** [journal] with the adds, and their item ids (the ids the items will have once merged, for Undo). */
    data class Ok(val journal: ListsJournalFile, val ids: List<ListsId>) : JournalAppend()
    data class No(val why: JournalRefusal) : JournalAppend()
}

/**
 * What a merge did: [file] with the adds in; [journal], the adds still waiting (a full list or file) and the names
 * refreshed. [added] new items, [already] ones that were there open (or merged before), [waiting] still in the journal,
 * [dropped] ones whose list is gone or that break a rule (LS2 logs the counts). LS10 (M3): [putBack] ticked items an add put
 * back, and [qtyKept] adds of an item already open with another quantity, left as it was; apart from [already].
 */
data class JournalMerge(val file: ListsFile, val journal: ListsJournalFile, val added: Int, val already: Int, val waiting: Int, val dropped: Int,
    val putBack: Int = 0, val qtyKept: Int = 0) {
    /** The lists changed (LS10, L6): a new item or a put back; an add merged before, already there or dropped changes none. */
    val changed: Boolean get() = added > 0 || putBack > 0
    override fun toString(): String = "JournalMerge(added=$added, putBack=$putBack, qtyKept=$qtyKept, already=$already, waiting=$waiting, dropped=$dropped)" // l10n: not text (counts only)
}

object ListsJournal {
    const val VERSION = 1
    /** A folded name: folding can lengthen a name ("Œufs" → "oeufs"), so twice a name's cap. */
    const val MAX_FOLDED = 2 * ListsLimits.MAX_NAME

    /** [journal] with the lists of [file] it should know (yours, refreshed at every unlocked write); its adds kept. */
    fun snapshot(file: ListsFile, journal: ListsJournalFile = ListsJournalFile()): ListsJournalFile {
        // A name with no letters or digits ("🛒") can't be said; such a list is known by its role or not at all.
        // Never a list with another person's words or Robot in it: an add said while locked may be anyone's, and it would go to them.
        val lists = file.lists.filter { it.yours && !ListsShared.peerText(it) }.mapNotNull { l ->
            val folded = l.name?.let(ListsText::fold)?.takeIf { it.isNotEmpty() && it.length <= MAX_FOLDED }
            if (l.role == null && folded == null) null else JournalList(l.id, l.role, folded)
        }
        return ListsJournalFile(lists, journal.adds, journal.extra)
    }

    /** The list with [role], while locked. */
    fun withRole(journal: ListsJournalFile, role: ListRole): JournalList? = journal.lists.firstOrNull { it.role == role }

    /** The list called [spoken] ("packing", « Épicerie »), while locked: by its name, or a role list by its role's names. */
    fun named(journal: ListsJournalFile, spoken: String): JournalList? {
        val k = ListsText.key(spoken)
        if (k.isEmpty()) return null
        return journal.lists.firstOrNull { it.name != null && ListsText.key(it.name) == k }
            ?: journal.lists.firstOrNull { it.name == null && it.role != null && it.role.names.any { n -> ListsText.key(n) == k } }
    }

    /** [items] (read by ItemText) waiting for [list]. All or nothing. */
    fun append(journal: ListsJournalFile, list: ListsId, items: List<NewItem>, at: Long, ids: () -> ListsId): JournalAppend {
        if (items.isEmpty()) return JournalAppend.No(JournalRefusal.EMPTY)
        if (items.size > ListsLimits.MAX_ADD) return JournalAppend.No(JournalRefusal.TOO_MANY)
        for (i in items) ItemText.check(i)?.let {
            return JournalAppend.No(when (it) {
                ItemProblem.EMPTY -> JournalRefusal.EMPTY
                ItemProblem.TOO_LONG -> JournalRefusal.TOO_LONG
                ItemProblem.TOO_MANY -> JournalRefusal.TOO_MANY
                ItemProblem.SECRET -> JournalRefusal.SECRET
            })
        }
        if (journal.lists.none { it.id == list }) return JournalAppend.No(JournalRefusal.NO_LIST)
        if (journal.adds.size + items.size > ListsLimits.MAX_JOURNAL_ADDS) return JournalAppend.No(JournalRefusal.FULL)
        val used = journal.adds.mapTo(HashSet()) { it.id }
        val fresh = items.map { n ->
            var id = ids()
            var tries = 0
            while (!used.add(id)) {
                require(++tries < 16)
                id = ids()
            }
            JournalAdd(id, list, n.text, n.qty, at)
        }
        val next = ListsJournalFile(journal.lists, journal.adds + fresh, journal.extra)
        if (!fits(next)) return JournalAppend.No(JournalRefusal.FULL)
        return JournalAppend.Ok(next, fresh.map { it.id })
    }

    /**
     * The adds of [journal] merged into [file] (at unlock, before anything else changes the lists), by item id: an add
     * already merged is skipped; each new item is by "locked" and keeps the add's id and time. LS10 (M3, §4.6, §5): an add
     * never changes what's there beyond what "add" means: one already open is left as it was, its quantity too (a quantity is
     * an edit, unlocked only), and one ticked is put back with its quantity, its history saying "locked" (the Lists screens
     * mark it "Changed while the phone was locked"). Adds for a full list or file wait; adds whose list is gone, or that break
     * a rule now, are dropped and counted.
     */
    fun merge(file: ListsFile, journal: ListsJournalFile): JournalMerge {
        var f = file
        val left = ArrayList<JournalAdd>()
        var added = 0
        var putBack = 0
        var qtyKept = 0
        var already = 0
        var dropped = 0
        for (a in journal.adds) {
            if (f.holding(a.id) != null) { already++; continue }
            val l = f.list(a.list)
            if (l == null) { dropped++; continue }
            // As ListsEdits.add matches: the same words, live.
            val there = l.items.firstOrNull { it.live && ItemText.same(it.text, a.text) }
            if (there != null && !there.done) {
                if (a.qty != null && a.qty != there.qty) qtyKept++ else already++
                continue
            }
            val who = ListsWho(ItemBy.LOCKED, ItemHow.VOICE, a.at)
            when (val e = ListsEdits.add(f, a.list, listOf(NewItem(a.text, if (there == null) a.qty else null)), who) { a.id }) {
                is ListsEdit.Done -> {
                    f = e.file
                    when (e.items.single().outcome) {
                        ItemOutcome.ADDED -> added++
                        ItemOutcome.PUT_BACK -> putBack++
                        else -> already++
                    }
                }
                is ListsEdit.No -> when (e.why) {
                    ListsRefusal.LIST_FULL, ListsRefusal.FILE_FULL -> left += a
                    else -> dropped++
                }
            }
        }
        val next = snapshot(f, ListsJournalFile(journal.lists, left, journal.extra))
        return JournalMerge(f, next, added, already, left.size, dropped, putBack, qtyKept)
    }

    // The JSON.

    fun fits(j: ListsJournalFile): Boolean = text(j).toByteArray(Charsets.UTF_8).size <= ListsLimits.MAX_JOURNAL

    /** [j] as UTF-8 JSON; throws IllegalArgumentException over 64 KiB ([fits] first). */
    fun encode(j: ListsJournalFile): ByteArray = text(j).toByteArray(Charsets.UTF_8).also { require(it.size <= ListsLimits.MAX_JOURNAL) }

    private fun text(j: ListsJournalFile): String {
        val out = StringBuilder("{\"v\":$VERSION,\"lists\":[")  // l10n: not text (JSON of the journal)
        j.lists.forEachIndexed { i, l ->
            if (i > 0) out.append(',')
            val w = JsonOut.Obj(out)
            w.str("id", l.id.hex)
            l.role?.let { w.str("role", it.json) }
            l.name?.let { w.str("name", it) }
            w.done()
        }
        out.append("],\"adds\":[")  // l10n: not text (JSON of the journal)
        j.adds.forEachIndexed { i, a ->
            if (i > 0) out.append(',')
            val w = JsonOut.Obj(out)
            w.str("id", a.id.hex)
            w.str("list", a.list.hex)
            w.str("text", a.text)
            a.qty?.let { w.str("qty", it) }
            w.long("at", a.at)
            for ((k, v) in a.extra) if (k !in ADD_KEYS) w.field(k, v)
            w.done()
        }
        out.append(']')
        for ((k, v) in j.extra) if (k !in TOP_KEYS) {
            out.append(',')
            JsonOut.value(out, k)
            out.append(':')
            JsonOut.value(out, v)
        }
        return out.append('}').toString()
    }

    private val TOP_KEYS = setOf("v", "lists", "adds")  // l10n: not text (JSON keys of the journal)
    private val LIST_KEYS = setOf("id", "role", "name")  // l10n: not text (JSON keys of the journal)
    private val ADD_KEYS = setOf("id", "list", "text", "qty", "at")  // l10n: not text (JSON keys of the journal)

    /** The journal in [bytes]. Anything broken: Damaged. */
    fun decode(bytes: ByteArray): JournalParse {
        if (bytes.size > ListsLimits.MAX_JOURNAL) return JournalParse.Damaged
        val top = StrictJson.parse(bytes)?.value as? Map<*, *> ?: return JournalParse.Damaged
        val v = top["v"] as? Long ?: return JournalParse.Damaged
        if (v > VERSION) return JournalParse.TooNew(v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (v < 1) return JournalParse.Damaged
        return try {
            val lists = (top["lists"] as? List<*> ?: throw ListsCodec.Bad()).map { x ->
                @Suppress("UNCHECKED_CAST")
                val o = x as? Map<String, Any?> ?: throw ListsCodec.Bad()
                if (!LIST_KEYS.containsAll(o.keys)) throw ListsCodec.Bad()
                val role = if (o.containsKey("role")) ListRole.entries.firstOrNull { it.json == o["role"] } ?: throw ListsCodec.Bad() else null
                JournalList(ListsId.of(o["id"] as? String) ?: throw ListsCodec.Bad(), role, if (o.containsKey("name")) o["name"] as? String ?: throw ListsCodec.Bad() else null)
            }
            val adds = (top["adds"] as? List<*> ?: throw ListsCodec.Bad()).map { x ->
                @Suppress("UNCHECKED_CAST")
                val o = x as? Map<String, Any?> ?: throw ListsCodec.Bad()
                JournalAdd(
                    id = ListsId.of(o["id"] as? String) ?: throw ListsCodec.Bad(),
                    list = ListsId.of(o["list"] as? String) ?: throw ListsCodec.Bad(),
                    text = o["text"] as? String ?: throw ListsCodec.Bad(),
                    qty = if (o.containsKey("qty")) o["qty"] as? String ?: throw ListsCodec.Bad() else null,
                    at = o["at"] as? Long ?: throw ListsCodec.Bad(),
                    extra = o.filterKeys { it !in ADD_KEYS },
                )
            }
            @Suppress("UNCHECKED_CAST")
            JournalParse.Ok(ListsJournalFile(lists, adds, (top as Map<String, Any?>).filterKeys { it !in TOP_KEYS }))
        } catch (_: ListsCodec.Bad) {
            JournalParse.Damaged
        } catch (_: IllegalArgumentException) {
            JournalParse.Damaged
        } catch (_: ClassCastException) {
            JournalParse.Damaged
        }
    }
}
