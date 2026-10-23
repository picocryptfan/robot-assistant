// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

// Pure. 1.4, Robot Files' screens (files-1.4-plan.md §6.2 to §6.5; job F7): what the Files tab shows and what your taps
// change, over the index FileStore keeps. The app's screens (ui/files) only draw this and send its changes through the
// vault (its lock gate, files.lock, a fresh key per write); every rule is here, so the JVM tests run it whole.
//
//   - Home: Inbox ("need you" first: Robot's questions, Ask me first, the files it holds; then what is being read),
//     Recently filed (the last 10, with Undo for 7 days on what Robot or a rule filed), the drawers with their counts:
//     the starter drawers first (core Drawers), then yours.
//   - Your drawers: a drawer is the `drawer` a filed entry holds (FilingRun.move writes any name you give). An empty one
//     you made ("New drawer") is kept in the index's top-level "drawers" key, a list of names: sealed with the index
//     (names are your words), additive in index v1 (a Robot from before F7 keeps it as an unknown key). A name can't be a
//     starter drawer's id or name in either language.
//   - Changes you make (rename, a field, tags, people, a drawer renamed) are pure functions of the index, null when they
//     can't apply; Robot never renames a file you named, and a field you set says "Set by you".
//   - Search: BM25 (memory's Bm25Index and MemoryText, English and French folding) over each file's name, fields, tags,
//     drawer and kind (in both languages) and its stored text (F9 adds the filters and the model's tools).
//
// Files you just deleted (still within their Undo) are [hidden]: left out of every list, count and search.

/** One drawer as Files shows it: [id] a starter drawer's (core Drawers) or your drawer's name, and how many files it holds. */
data class DrawerRow(val id: String, val starter: Boolean, val count: Int)

/** Files' home (§6.2). [needYou]: in Inbox and waiting for you; [reading]: in Inbox, Robot still on it; [fileThem]: Ask me first's batch. */
data class FilesHome(
    val needYou: List<FileEntry>,
    val reading: List<FileEntry>,
    val fileThem: List<FileEntry>,
    val recent: List<FileEntry>,
    val drawers: List<DrawerRow>,
    val total: Int,
) {
    val inbox: Int get() = needYou.size + reading.size
    val empty: Boolean get() = total == 0
}

/** A drawer's sum for a filter (§6.3), by code: [byCurrency] cents and receipts counted, [check] left out ("Check the total"). */
data class DrawerTotals(val byCurrency: Map<String?, Pair<Long, Int>>, val check: Int)

object FileViews {
    /** Recently filed: the last 10 (§6.2). */
    const val RECENT = 10
    /** Undo on Recently filed, for 7 days (§6.2). */
    const val UNDO_MS = 7 * 24 * 3_600_000L
    /** The index's top-level key for your drawers' names (empty ones included). */
    const val DRAWERS_KEY = "drawers"
    /** Your drawers, at most (each a name of at most 40 characters). */
    const val MAX_DRAWERS = 100
    /** Search results, at most. */
    const val MAX_FOUND = 50

    // Home.

    /** In Inbox and waiting for you: a question, Ask me first, held (dangerous, unreadable, too big, Off), put back by you. */
    fun needsYou(e: FileEntry): Boolean = inInbox(e) && (e.kind == FileKind.DANGEROUS || e.damaged ||
        e.filing?.step in setOf(FilingStep.ASK, FilingStep.HELD, FilingStep.KEPT, FilingStep.UNKNOWN))

    /** In Inbox (a newer Robot's state counts as Inbox). */
    fun inInbox(e: FileEntry): Boolean = e.state != FileState.FILED

    fun home(idx: FileIndex, hidden: Set<FileId> = emptySet()): FilesHome {
        val all = idx.entries.filter { it.id !in hidden }
        val inbox = all.filter(::inInbox).sortedByDescending { it.from.at }
        val need = inbox.filter(::needsYou)
        val recent = all.filter { it.state == FileState.FILED && it.from.filedAt != null }.sortedByDescending { it.from.filedAt }.take(RECENT)
        return FilesHome(need, inbox - need.toSet(), need.filter { it.filing?.step == FilingStep.ASK && it.filing.why == FilingWhy.FILE_THEM },
            recent, drawers(idx, hidden), all.size)
    }

    /** Undo on a Recently filed line: what Robot or a rule filed, for 7 days (FilingRun.undo puts it back in Inbox). */
    fun canUndo(e: FileEntry, now: Long): Boolean = e.state == FileState.FILED && e.filing?.step == FilingStep.FILED &&
        (e.from.filedBy == FiledBy.ROBOT || e.from.filedBy == FiledBy.RULE) && e.from.filedAt?.let { now - it in 0..UNDO_MS } == true

    // Drawers.

    /** Every drawer: the starter ones in their order, then yours (the ones you made, then any other a file names), with counts. */
    fun drawers(idx: FileIndex, hidden: Set<FileId> = emptySet()): List<DrawerRow> {
        val counts = idx.entries.filter { it.id !in hidden && it.state == FileState.FILED }.mapNotNull { it.drawer }.groupingBy { it }.eachCount()
        // F12: a starter drawer travel mode hides isn't there (TravelMode.shown); yours already aren't.
        return Drawers.STARTER.filter { it !in idx.outOfSight }.map { DrawerRow(it, true, counts[it] ?: 0) } + yours(idx).map { DrawerRow(it, false, counts[it] ?: 0) }
    }

    /** Your drawers: those listed in the index (in your order), then the others files name, sorted. */
    fun yours(idx: FileIndex): List<String> {
        val listed = listed(idx)
        val named = idx.entries.mapNotNull { it.drawer }.filter { it !in Drawers.STARTER && it !in listed }.distinct().sortedBy { it.lowercase() }
        return listed + named
    }

    /** The names the index's "drawers" key lists (anything else in it is ignored, and kept as it was until a change). */
    private fun listed(idx: FileIndex): List<String> =
        (idx.extra[DRAWERS_KEY] as? List<*>).orEmpty().filterIsInstance<String>().filter { FileIndexCodec.label(it) && it !in Drawers.STARTER }.distinct()

    /** The files in [drawer], newest first (a receipt's or bill's own date, else when it came in). */
    fun inDrawer(idx: FileIndex, drawer: String, hidden: Set<FileId> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): List<FileEntry> =
        idx.entries.filter { it.id !in hidden && it.state == FileState.FILED && it.drawer == drawer }.sortedByDescending { sortKey(it, zone) }

    /** When a file is from: its date field, else its due date, else when it came in. */
    fun day(e: FileEntry, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        dateField(e, "date") ?: dateField(e, "due") ?: Instant.ofEpochMilli(e.from.at).atZone(zone).toLocalDate()

    private fun sortKey(e: FileEntry, zone: ZoneId): Long = day(e, zone).toEpochDay() * 86_400_000L + (e.from.at % 86_400_000L)

    fun dateField(e: FileEntry, key: String): LocalDate? = e.fields[key]?.value?.let {
        try { LocalDate.parse(it) } catch (_: DateTimeParseException) { null }
    }

    /** The months [files] are from, newest first ("2026-09"), for a drawer's filter chips. */
    fun months(files: List<FileEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Int, Int>> =
        files.map { day(it, zone).let { d -> d.year to d.monthValue } }.distinct().sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })

    fun inMonth(files: List<FileEntry>, year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): List<FileEntry> =
        files.filter { day(it, zone).let { d -> d.year == year && d.monthValue == month } }

    /** A drawer's totals (§6.3, receipts and bills): one sum per currency, by code; a total that needs checking is left out and counted. */
    fun totals(files: List<FileEntry>): DrawerTotals {
        val sums = LinkedHashMap<String?, Pair<Long, Int>>()
        var check = 0
        for (e in files) {
            if (e.kind != FileKind.RECEIPT && e.kind != FileKind.BILL) continue
            if (e.fields["check"]?.value == "total") { check++; continue } // l10n: not text (a field's code)
            val cents = e.fields["total"]?.value?.let(FileFields::amountOf) ?: continue
            val cur = e.fields["currency"]?.value
            val (sum, n) = sums[cur] ?: (0L to 0)
            sums[cur] = (sum + cents) to (n + 1)
        }
        return DrawerTotals(sums, check)
    }

    // Changes you make.

    private fun change(idx: FileIndex, id: FileId, f: (FileEntry) -> FileEntry?): FileIndex? {
        val i = idx.entries.indexOfFirst { it.id == id }
        if (i < 0) return null
        val next = try { f(idx.entries[i]) } catch (_: IllegalArgumentException) { null } ?: return null
        if (!FileIndexCodec.fits(next)) return null
        return idx.copy(entries = idx.entries.toMutableList().also { it[i] = next })
    }

    /** You renamed [id]: "named by you" from then on (Robot never renames it). Null for a name that cleans to nothing. */
    fun rename(idx: FileIndex, id: FileId, name: String): FileIndex? {
        val n = FileIndexCodec.cleanName(name).takeIf { it.isNotEmpty() } ?: return null
        return change(idx, id) { it.copy(name = n, named = true) }
    }

    /** The fields Files lets you set, in the order a file's page shows them. */
    val EDITABLE = listOf("merchant", "date", "total", "currency", "due", "expiry", "return_by", "warranty", "account", "slip", "year")

    /** The fields that hold a date (F10: a return's last day and a warranty's end too). */
    val DATE_FIELDS = setOf("date", "due", "expiry", "return_by", "warranty")

    /** A field's value as you typed it, cleaned and checked for its kind (an amount, a date, a code); null when it isn't one. */
    fun cleanField(key: String, value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return when (key) {
            "total" -> FileFields.amountOf(v)?.let(FileFields::plain)
            in DATE_FIELDS -> try { LocalDate.parse(v).toString() } catch (_: DateTimeParseException) { null }
            "currency" -> v.uppercase().takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }
            "year" -> v.toIntOrNull()?.takeIf { it in 1900..2200 }?.toString()
            "account" -> v.filter(Char::isDigit).takeLast(4).takeIf { it.length == 4 }
            else -> FileIndexCodec.cleanName(v).take(FileIndexCodec.MAX_FIELD_VALUE).trim().takeIf { it.isNotEmpty() }
        }
    }

    /**
     * You set [key] on [id] ("Set by you"); empty [value] removes it. A total or date you set answers "Check the total" or
     * "Check the date". Null when the value isn't one of that field's (an amount, a date) or the key isn't settable.
     */
    fun setField(idx: FileIndex, id: FileId, key: String, value: String): FileIndex? {
        if (key !in EDITABLE) return null
        val clean = if (value.isBlank()) null else cleanField(key, value) ?: return null
        return change(idx, id) { e ->
            val fields = LinkedHashMap(e.fields)
            if (clean == null) fields.remove(key) else fields[key] = FileField(clean, FieldBy.YOU)
            val check = fields["check"]?.value
            if ((key == "total" && check == "total") || (key == "date" && check == "date")) fields.remove("check")
            if (fields.size > FileIndexCodec.MAX_FIELD_COUNT) null else e.copy(fields = fields)
        }
    }

    /**
     * Your answer to "Check the total" on [id] in Inbox: the total as you set it, then filed as Robot would file it, by you
     * (Ask me first doesn't wait: your tap is the answer). Null when [total] isn't an amount or [id] isn't asking that.
     */
    fun answerTotal(idx: FileIndex, id: FileId, total: String, settings: FilesSettings, rules: List<FilingRule>, words: NameWords, now: Long): FileIndex? {
        val e = idx.entry(id) ?: return null
        if (e.state != FileState.INBOX || e.filing?.step != FilingStep.ASK || e.filing.why != FilingWhy.TOTAL) return null
        val set = setField(idx, id, "total", total) ?: return null
        val after = set.entry(id) ?: return null
        val next = when (val d = Filer.decide(after, after.kind, Filer.valuesOf(after.fields), settings, rules, yourWords = true, words = words, text = after.text)) {
            is FilingDecision.File -> after.copy(state = FileState.FILED, drawer = d.placement.drawer, name = d.placement.name, tags = d.placement.tags,
                filing = Filing(FilingStep.FILED, null, e.filing.tries, wasName = e.name, wasTags = e.tags), from = after.from.copy(filedBy = FiledBy.YOU, filedAt = now))
            is FilingDecision.Ask -> after.copy(filing = Filing(FilingStep.ASK, d.why, e.filing.tries, d.choices))
            is FilingDecision.Held -> after.copy(filing = Filing(FilingStep.HELD, d.why, e.filing.tries))
        }
        return change(set, id) { next }
    }

    /**
     * A tag you added (a drawer's id can't be one: drawers aren't tags in 1.4). F12: a known tag's words in either language
     * are stored as its code (FileTags: « affaires » is `business`), and `business` takes the place of `personal` and the
     * other way round. Null when it isn't a label or is there already.
     */
    fun addTag(idx: FileIndex, id: FileId, tag: String): FileIndex? {
        val t = FileTags.of(tag) ?: return null
        return change(idx, id) { e ->
            val without = FileTags.other(t)?.let { o -> e.tags - o } ?: e.tags
            if (t in e.tags || without.size >= FileIndexCodec.MAX_TAGS) null else e.copy(tags = without + t)
        }
    }

    fun removeTag(idx: FileIndex, id: FileId, tag: String): FileIndex? = change(idx, id) { e -> if (tag !in e.tags) null else e.copy(tags = e.tags - tag) }

    /** A person from People you linked ([person]: their id). Robot never links people on its own (D14). */
    fun addPerson(idx: FileIndex, id: FileId, person: String): FileIndex? {
        if (!FileIndexCodec.PERSON.matches(person)) return null
        return change(idx, id) { e -> if (person in e.people || e.people.size >= FileIndexCodec.MAX_PEOPLE) null else e.copy(people = e.people + person) }
    }

    fun removePerson(idx: FileIndex, id: FileId, person: String): FileIndex? = change(idx, id) { e -> if (person !in e.people) null else e.copy(people = e.people - person) }

    /** That you opened [id] with another app (its provenance's "opened"). */
    fun opened(idx: FileIndex, id: FileId): FileIndex? = change(idx, id) { e -> if (e.from.opened) null else e.copy(from = e.from.copy(opened = true)) }

    // Your drawers.

    /** Why a drawer's name can't be used, or null. */
    enum class DrawerProblem { EMPTY, TAKEN, FULL }

    /** [name] cleaned for a drawer, or null when nothing is left. */
    fun drawerName(name: String): String? = FileIndexCodec.cleanName(name).take(FileIndexCodec.MAX_LABEL).trim().takeIf { FileIndexCodec.label(it) }

    /** Whether [name] is a starter drawer's id or its name in English or French (a drawer of yours can't take it). */
    fun starterName(name: String): Boolean {
        val n = name.trim().lowercase()
        return Drawers.STARTER.any { id -> n == id || n == FilesStrings.drawer(id).en().lowercase() || n == FilesStrings.drawer(id).render(FR).lowercase() }
    }

    /** Whether [name] can be a new drawer of yours in [idx] ([except]: the drawer being renamed). */
    fun drawerProblem(idx: FileIndex, name: String, except: String? = null): DrawerProblem? {
        val n = drawerName(name) ?: return DrawerProblem.EMPTY
        if (starterName(n) || yours(idx).any { it != except && it.equals(n, ignoreCase = true) }) return DrawerProblem.TAKEN
        if (except == null && listed(idx).size >= MAX_DRAWERS) return DrawerProblem.FULL
        return null
    }

    /** "New drawer": [name] added to your drawers (empty until you move files into it). */
    fun addDrawer(idx: FileIndex, name: String): FileIndex? {
        if (drawerProblem(idx, name) != null) return null
        val n = drawerName(name) ?: return null
        return idx.copy(extra = idx.extra + (DRAWERS_KEY to (listed(idx) + n)))
    }

    /** Your drawer [old] renamed [name]: every file in it moves with it (§4.4: renaming a drawer renames it everywhere). */
    fun renameDrawer(idx: FileIndex, old: String, name: String): FileIndex? {
        if (old in Drawers.STARTER || old !in yours(idx) || drawerProblem(idx, name, except = old) != null) return null
        val n = drawerName(name) ?: return null
        val list = listed(idx).let { l -> if (old in l) l.map { if (it == old) n else it } else l + n }
        return FileIndex(idx.entries.map { if (it.drawer == old) it.copy(drawer = n) else it }, idx.extra + (DRAWERS_KEY to list))
    }

    /** Your drawer [name] removed, only once it is empty (a file is never left without a drawer). */
    fun removeDrawer(idx: FileIndex, name: String): FileIndex? {
        if (name in Drawers.STARTER || idx.entries.any { it.drawer == name }) return null
        val l = listed(idx)
        if (name !in l) return null
        return idx.copy(extra = idx.extra + (DRAWERS_KEY to (l - name)))
    }

    // Search.

    private val FR = Langs(Lang.FR, Lang.FR, Lang.FR)

    /** What a file is found by: its name, fields, tags, drawer and kind in both languages, and its stored text. */
    fun searchText(e: FileEntry): String = buildString {
        append(e.name).append('\n')
        for ((_, f) in e.fields) append(f.value).append(' ')
        append('\n')
        for (t in e.tags) append(t).append(' ')
        // F12: the tags Robot knows, in both languages ("business", « affaires »).
        for (t in e.tags) if (t in FileTags.KNOWN) FilesStrings.tag(t).let { p -> append(p.en()).append(' ').append(p.render(FR)).append(' ') }
        e.drawer?.let { d ->
            val p = FilesStrings.drawer(d)
            append('\n').append(p.en()).append(' ').append(p.render(FR))
        }
        if (e.kind != FileKind.UNKNOWN) FilesStrings.kind(e.kind).let { k -> append('\n').append(k.en()).append(' ').append(k.render(FR)) }
        // F9: everyday words for its kind and category in both languages, from Robot's own fields (FileSearch.words).
        FileSearch.words(e).takeIf { it.isNotEmpty() }?.let { append('\n').append(it) }
        append('\n').append(e.text)
    }

    /**
     * Files matching [query], best first, at most [MAX_FOUND]; none for a query of no words. F9: FileSearch reads the
     * amounts, days, kinds and drawers in it as filters, and BM25 ranks the rest.
     */
    fun search(idx: FileIndex, query: String, hidden: Set<FileId> = emptySet(), today: LocalDate = LocalDate.now()): List<FileEntry> {
        if (query.isBlank()) return emptyList()
        val q = FileSearch.parse(query, yours = yours(idx), today = today)
        if (q.empty) return emptyList()
        return FileSearch.find(idx, q, hidden, damaged = true)
    }
}
