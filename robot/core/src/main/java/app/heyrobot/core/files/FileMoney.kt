// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

// Pure. 1.4, Robot Files' money and dates (files-1.4-plan.md §7, D9, D11; job F10). Everything here is code over the
// fields Robot's own parsers kept (FileFields) or you set: no model reads, adds or decides any of it.
//
//   Spending       "how much did I spend on gas in August": the sum of filed receipts' totals, in whole cents (Long, never
//                  a Double), one total per currency (no exchange rates, §7.3); a receipt whose total needs checking is left
//                  out and counted, and so is one held in Inbox for its total.
//   Dates          A bill's due date, a receipt's last day to return, a warranty's end, an ID's or a document's expiry: from
//                  the fields "due", "return_by", "warranty" and "expiry" only, never from a file's text or the model.
//   Reminders      "Add to my brief": a reminder kept in the file's own entry (the field "remind", sealed with the index),
//                  said in the morning brief while the phone is unlocked (FileBrief). The calendar is never written here:
//                  only add_event's card, which a tap answers (PanelFiles, ToolStrings), or the calendar app's own editor.
//   Subscriptions  The same merchant billed in 3 or more months in a row, the last of them recent: listed, never acted on.
//   Clean-up       Files that look no longer needed, each with why: suggestions only. Nothing here deletes or changes a
//                  file; a delete is your tap on its own dialog (D9). Tax files are never suggested: they are kept six years.

/** What a spending question asks: receipts (and bills, [bills]) of a [category] or from a [merchant] (folded words), from [from] to [to]. */
data class SpendQuery(val category: String? = null, val merchant: String? = null, val from: LocalDate? = null, val to: LocalDate? = null,
    val bills: Boolean = false) {
    override fun toString(): String = "SpendQuery($category, ${merchant != null}, ${from != null}, $bills)" // l10n: not text (never your words in a log)
}

/** A spending answer, by code: [byCurrency] cents (null: files that named no currency), the [files] added, the [check] left out. */
data class Spending(val byCurrency: Map<String?, Long>, val files: Int, val check: Int, val kinds: Set<FileKind>)

object FileSpending {
    /** The kinds a question adds up: receipts; bills too when the words say bills or the spending is on utilities (billed). */
    fun kinds(q: SpendQuery): Set<FileKind> = when {
        q.bills -> setOf(FileKind.BILL)
        q.category == "utilities" -> setOf(FileKind.RECEIPT, FileKind.BILL)
        else -> setOf(FileKind.RECEIPT)
    }

    /**
     * [e] is one of [q]'s files, whatever its total: of its kinds, its category (its own field, else Robot's merchant table)
     * or its merchant holding every word asked, and its day (FileViews.day: a receipt's own date, else when it came in) in
     * the range.
     */
    fun matches(e: FileEntry, q: SpendQuery, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (e.kind !in kinds(q) || e.damaged) return false
        if (q.category != null && FileSearch.category(e) != q.category) return false
        if (q.merchant != null) {
            val m = e.fields["merchant"]?.value?.let(::key) ?: return false
            if (q.merchant.split(' ').filter { it.isNotBlank() }.any { !m.contains(key(it)) }) return false
        }
        if (q.from != null || q.to != null) {
            val d = FileViews.day(e, zone)
            if ((q.from != null && d.isBefore(q.from)) || (q.to != null && d.isAfter(q.to))) return false
        }
        return true
    }

    /**
     * The sum [q] asks for in [idx]: filed files only; a total that needs checking (or none) is left out and counted, as is a
     * file held in Inbox for its total. Whole cents, exact (Math.addExact: an overflow throws rather than wraps).
     */
    fun sum(idx: FileIndex, q: SpendQuery, hidden: Set<FileId> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): Spending {
        val sums = LinkedHashMap<String?, Long>()
        var files = 0
        var check = 0
        for (e in idx.entries) {
            if (e.id in hidden || !matches(e, q, zone)) continue
            val heldForTotal = e.state != FileState.FILED && e.filing?.step == FilingStep.ASK && e.filing.why == FilingWhy.TOTAL
            if (e.state != FileState.FILED) { if (heldForTotal) check++; continue }
            val cents = e.fields["total"]?.value?.takeIf { e.fields["check"]?.value != "total" }?.let(FileFields::amountOf) // l10n: not text (a field's code)
            if (cents == null) { check++; continue }
            val cur = e.fields["currency"]?.value
            sums[cur] = Math.addExact(sums[cur] ?: 0L, cents)
            files++
        }
        val sorted = sums.entries.sortedByDescending { it.value }.associate { it.key to it.value }
        return Spending(sorted, files, check, kinds(q))
    }

    // Reading the words after "how much did I spend" / « combien j'ai dépensé ».

    private val AT = setOf("at", "chez") // l10n: not text (words Robot hears)
    private val ON = setOf("on", "for", "in", "en", "pour", "a", "au", "aux", "dans", "sur", "de", "d'", "du", "des") // l10n: not text (words Robot hears)
    private val SMALL = setOf("my", "the", "all", "total", "in total", "mon", "ma", "mes", "le", "la", "les", "l'", "tout", "en tout", "au total", "money", "argent") // l10n: not text (words Robot hears)
    private val BILLS = setOf("bill", "bills", "invoice", "invoices", "facture", "factures") // l10n: not text (words Robot hears)

    /**
     * [rest] (the words after the question's lead, folded as FilesRouter folds; [orig] the same as said) read into a query:
     * the days (FileWhen: "in August", "this month", « le mois passé »), then "at X" or « chez X » (a merchant), else a
     * category word ("gas", « essence », FileSearch's words), else the words left as a merchant. Null when the words left
     * are too many to be a merchant (a sentence, not a question about spending).
     */
    fun parse(rest: String, orig: String, today: LocalDate): SpendQuery? {
        val w = rest.trim().split(' ').filter { it.isNotBlank() }
        var said = orig.trim().split(' ').filter { it.isNotBlank() }
        if (said.size != w.size) said = w
        // The days, as FileWhen reads them; the words it took go from the words as said too (they line up word for word).
        val days = FileWhen.read(" " + w.joinToString(" ") + " ", today)
        var words = w
        if (days != null) {
            val left = days.rest.trim().split(' ').filter { it.isNotBlank() }
            var a = 0
            while (a < left.size && left[a] == w[a]) a++
            val n = w.size - left.size
            words = left
            said = said.take(a) + said.drop(a + n)
        }
        var i = 0
        var merchantMarked = false
        while (i < words.size && (words[i] in ON || words[i] in AT || words[i] in SMALL)) {
            if (words[i] in AT) merchantMarked = true
            i++
        }
        val keep = words.indices.filter { it >= i && words[it] !in SMALL && words[it] !in BILLS }
        val billsSaid = words.drop(i).any { it in BILLS }
        val content = keep.map { words[it] }.dropLastWhile { it in ON }
        val contentSaid = keep.map { said[it] }.take(content.size)
        if (content.size > 4) return null
        if (content.isEmpty()) return SpendQuery(from = days?.from, to = days?.to, bills = billsSaid)
        if (!merchantMarked) FileSearch.categoryOf(content.joinToString(" "))?.let { return SpendQuery(category = it, from = days?.from, to = days?.to, bills = billsSaid) }
        val name = contentSaid.joinToString(" ").trim('\'', ' ', ',')
        return SpendQuery(merchant = name.ifBlank { null }, from = days?.from, to = days?.to, bills = billsSaid)
    }

    private fun key(s: String): String = FileFields.fold(s).filter(Char::isLetterOrDigit)
}

// ---------------------------------------------------------------------------------------------- dates and reminders

/** The dates a file can hold that Robot reminds you of (§7.2). */
enum class DateKind(val field: String) { DUE("due"), RETURN("return_by"), WARRANTY("warranty"), EXPIRY("expiry") }

/** One of a file's dates: [entry]'s [kind], on [date]. */
data class FileDate(val entry: FileEntry, val kind: DateKind, val date: LocalDate) {
    override fun toString(): String = "FileDate(${entry.id}, $kind)" // l10n: not text (never a date or a name in a log)
}

object FileDates {
    /** How far ahead each kind is "coming up" (EST, §7.2: expiry 90 days ahead). */
    val AHEAD: Map<DateKind, Long> = mapOf(DateKind.DUE to 14L, DateKind.RETURN to 14L, DateKind.WARRANTY to 30L, DateKind.EXPIRY to 90L)

    /** How many days before its date a reminder is said in the brief (EST): a bill 2 days ahead, an expiry 30. */
    val LEAD: Map<DateKind, Long> = mapOf(DateKind.DUE to 2L, DateKind.RETURN to 2L, DateKind.WARRANTY to 7L, DateKind.EXPIRY to 30L)

    /** The kinds of file each date belongs to: a bill's due date, a receipt's return, a receipt's or a document's warranty, an ID's or a document's expiry. */
    private val KINDS: Map<DateKind, Set<FileKind>> = mapOf(
        DateKind.DUE to setOf(FileKind.BILL),
        DateKind.RETURN to setOf(FileKind.RECEIPT),
        DateKind.WARRANTY to setOf(FileKind.RECEIPT, FileKind.DOCUMENT),
        DateKind.EXPIRY to setOf(FileKind.ID, FileKind.DOCUMENT),
    )

    /** [e]'s dates, from its fields only (a date its text holds but its fields don't is no date here). */
    fun of(e: FileEntry): List<FileDate> = DateKind.entries.mapNotNull { k ->
        if (e.kind !in KINDS.getValue(k)) return@mapNotNull null
        FileViews.dateField(e, k.field)?.let { FileDate(e, k, it) }
    }

    /**
     * What is coming up in [idx] from [today]: filed files' dates of [kinds], each until [to] (null: its kind's [AHEAD]),
     * soonest first. Never a damaged file's, nor one waiting for a delete's Undo ([hidden]).
     */
    fun upcoming(idx: FileIndex, today: LocalDate, kinds: Set<DateKind> = DateKind.entries.toSet(), to: LocalDate? = null,
        hidden: Set<FileId> = emptySet()): List<FileDate> =
        idx.entries.asSequence().filter { it.state == FileState.FILED && !it.damaged && it.id !in hidden }
            .flatMap { of(it).asSequence() }
            .filter { d -> d.kind in kinds && !d.date.isBefore(today) && !d.date.isAfter(to ?: today.plusDays(AHEAD.getValue(d.kind))) }
            .sortedWith(compareBy<FileDate> { it.date }.thenBy { it.kind.ordinal })
            .toList()

    /** The brief a reminder of [d] goes in: [LEAD] days before its date, but never before [nextBrief]; null once its date has passed. */
    fun remindOn(d: FileDate, nextBrief: LocalDate): LocalDate? {
        if (d.date.isBefore(nextBrief)) return null
        val lead = d.date.minusDays(LEAD.getValue(d.kind))
        return if (lead.isBefore(nextBrief)) nextBrief else lead
    }

    /** The next morning brief at [now]: today's before 5:00, else tomorrow's (as "add to my brief" is, core BriefReminders.dayFor). */
    fun nextBrief(now: ZonedDateTime): LocalDate = app.heyrobot.core.BriefReminders.dayFor(now, tomorrow = false)
}

/**
 * Reminders you asked for ("Add to my brief"), kept in the file's own entry: the field "remind", "due:2026-10-15
 * warranty:2027-07-24" (the day each is said from). Sealed with the index, so the reminder (a merchant, an amount) never
 * sits in a plain file; said only by the brief, only while the phone is unlocked.
 */
object FileRemind {
    const val FIELD = "remind"

    /** [e]'s reminders: the day each date's reminder is said from. Anything unreadable in the field is ignored. */
    fun of(e: FileEntry): Map<DateKind, LocalDate> {
        val v = e.fields[FIELD]?.value ?: return emptyMap()
        val out = LinkedHashMap<DateKind, LocalDate>()
        for (t in v.split(' ')) {
            val k = DateKind.entries.firstOrNull { it.name.lowercase() == t.substringBefore(':') } ?: continue
            val d = try { LocalDate.parse(t.substringAfter(':')) } catch (_: java.time.format.DateTimeParseException) { continue }
            out[k] = d
        }
        return out
    }

    private fun encode(m: Map<DateKind, LocalDate>): String = m.entries.joinToString(" ") { (k, d) -> "${k.name.lowercase()}:$d" }

    /**
     * [idx] with [d]'s reminder set from [on] ([by]: you, or Robot on its own at "Add to my brief on its own"). Null when
     * the file is gone or its entry wouldn't fit. A reminder already set for that date stays as it was.
     */
    fun set(idx: FileIndex, d: FileDate, on: LocalDate, by: FieldBy = FieldBy.YOU): FileIndex? {
        val e = idx.entry(d.entry.id) ?: return null
        val now = of(e)
        if (now[d.kind] != null) return null
        val next = LinkedHashMap(now).apply { put(d.kind, on) }
        return put(idx, e, encode(next), by)
    }

    /** [idx] without [id]'s reminder for [kind]; null when it had none. */
    fun remove(idx: FileIndex, id: FileId, kind: DateKind): FileIndex? {
        val e = idx.entry(id) ?: return null
        val now = of(e)
        if (kind !in now) return null
        return put(idx, e, encode(now - kind), e.fields[FIELD]?.by ?: FieldBy.YOU)
    }

    private fun put(idx: FileIndex, e: FileEntry, value: String, by: FieldBy): FileIndex? {
        val fields = LinkedHashMap(e.fields)
        if (value.isEmpty()) fields.remove(FIELD) else fields[FIELD] = FileField(value, by)
        if (fields.size > FileIndexCodec.MAX_FIELD_COUNT) return null
        val next = try { e.copy(fields = fields) } catch (_: IllegalArgumentException) { return null }
        if (!FileIndexCodec.fits(next)) return null
        return idx.copy(entries = idx.entries.map { if (it.id == e.id) next else it })
    }

    /** The reminders the brief of [today] says: set for today or before, for a date that hasn't passed; soonest first. */
    fun due(idx: FileIndex, today: LocalDate, hidden: Set<FileId> = emptySet()): List<FileDate> =
        idx.entries.asSequence().filter { it.state == FileState.FILED && !it.damaged && it.id !in hidden }.flatMap { e ->
            val set = of(e)
            FileDates.of(e).asSequence().filter { d -> set[d.kind]?.let { on -> !on.isAfter(today) } == true && !d.date.isBefore(today) }
        }.sortedWith(compareBy<FileDate> { it.date }.thenBy { it.kind.ordinal }).toList()

    /**
     * "Add to my brief on its own" (Settings › Files' Reminders from files): [ids] (files just filed) with a reminder set,
     * by Robot, for each of their dates coming up ([FileDates.AHEAD]); the brief says each from [FileDates.remindOn].
     * Only ever a brief reminder: never the calendar (D11).
     */
    fun autoAdd(idx: FileIndex, ids: Collection<FileId>, now: ZonedDateTime): FileIndex {
        var out = idx
        val today = now.toLocalDate()
        val next = FileDates.nextBrief(now)
        for (id in ids) {
            val e = out.entry(id) ?: continue
            if (e.state != FileState.FILED || e.damaged) continue
            for (d in FileDates.of(e)) {
                if (d.date.isBefore(today) || d.date.isAfter(today.plusDays(FileDates.AHEAD.getValue(d.kind)))) continue
                val on = FileDates.remindOn(d, next) ?: continue
                set(out, d, on, FieldBy.READ)?.let { out = it }
            }
        }
        return out
    }
}

// ---------------------------------------------------------------------------------------------- the morning brief

/** What the brief says of your files: what Robot [filed] yesterday (their kinds) and the [reminders] due today. */
data class FilesBrief(val filed: List<FileKind>, val reminders: List<FileDate>) {
    val empty: Boolean get() = filed.isEmpty() && reminders.isEmpty()

    override fun toString(): String = "FilesBrief(${filed.size}, ${reminders.size})" // l10n: not text (counts for a log)
}

object FileBrief {
    /** Reminders said by name in one brief; the rest are counted. */
    const val MAX_REMINDERS = 3

    /** The brief's files part on [today]: files Robot or a rule filed yesterday (in [zone]), and the reminders due. */
    fun of(idx: FileIndex, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), hidden: Set<FileId> = emptySet()): FilesBrief {
        val yesterday = today.minusDays(1)
        val filed = idx.entries.filter { e ->
            e.id !in hidden && e.state == FileState.FILED && (e.from.filedBy == FiledBy.ROBOT || e.from.filedBy == FiledBy.RULE) &&
                e.from.filedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == yesterday } == true
        }.map { it.kind }
        return FilesBrief(filed, FileRemind.due(idx, today, hidden))
    }
}

// ---------------------------------------------------------------------------------------------- subscriptions

/** A merchant billed in [months] months in a row up to [last]: its latest bill's [cents] and [currency] (null: unchecked). */
data class Subscription(val merchant: String, val cents: Long?, val currency: String?, val months: Int, val last: YearMonth, val latest: FileId) {
    override fun toString(): String = "Subscription($months)" // l10n: not text (never a merchant in a log)
}

object FileSubscriptions {
    /** Months in a row that make a subscription (§7.2). */
    const val MIN_MONTHS = 3
    /** Its last bill at most this many months ago, or it has ended. */
    const val RECENT_MONTHS = 2L

    /**
     * Subscriptions in your bills: filed bills grouped by merchant (folded), each counted once per month (its own date, else
     * its due date, else when it came in); [MIN_MONTHS] or more months in a row ending in the last [RECENT_MONTHS] months.
     * Listed only: Robot never cancels, pays or contacts anyone about one.
     */
    fun find(idx: FileIndex, today: LocalDate, hidden: Set<FileId> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): List<Subscription> {
        val bills = idx.entries.filter { it.kind == FileKind.BILL && it.state == FileState.FILED && !it.damaged && it.id !in hidden }
        val now = YearMonth.from(today)
        val out = ArrayList<Subscription>()
        for ((_, group) in bills.groupBy { b -> b.fields["merchant"]?.value?.let { FileFields.fold(it).filter(Char::isLetterOrDigit) }.orEmpty() }) {
            val merchantKey = group.firstOrNull()?.fields?.get("merchant")?.value ?: continue
            if (merchantKey.isBlank()) continue
            val byMonth = group.groupBy { YearMonth.from(FileViews.day(it, zone)) }
            val months = byMonth.keys.sortedDescending()
            val last = months.first()
            if (last.isBefore(now.minusMonths(RECENT_MONTHS)) || last.isAfter(now)) continue
            var run = 1
            while (run < months.size && months[run] == last.minusMonths(run.toLong())) run++
            if (run < MIN_MONTHS) continue
            val latest = byMonth.getValue(last).maxBy { FileViews.day(it, zone) }
            val cents = latest.fields["total"]?.value?.takeIf { latest.fields["check"]?.value != "total" }?.let(FileFields::amountOf) // l10n: not text (a field's code)
            out += Subscription(latest.fields["merchant"]?.value ?: merchantKey, cents, latest.fields["currency"]?.value, run, last, latest.id)
        }
        return out.sortedWith(compareByDescending<Subscription> { it.cents ?: -1 }.thenBy { it.merchant.lowercase() })
    }

    /** What they come to a month, per currency, by code (unchecked ones left out). */
    fun monthly(subs: List<Subscription>): Map<String?, Long> {
        val out = LinkedHashMap<String?, Long>()
        for (s in subs) s.cents?.let { c -> out[s.currency] = Math.addExact(out[s.currency] ?: 0L, c) }
        return out
    }
}

// ---------------------------------------------------------------------------------------------- clean-up suggestions

/** Why a file looks no longer needed (§7.4). */
enum class CleanupWhy { DUPLICATE, NEAR_DUPLICATE, PAST_TICKET, RETURN_CLOSED, OLD_BILL }

/** A suggestion: [entry] looks no longer needed, for [why]; [of], the copy that stays (a duplicate's). Never a delete. */
data class Cleanup(val entry: FileEntry, val why: CleanupWhy, val of: FileId? = null) {
    override fun toString(): String = "Cleanup($why)" // l10n: not text (never a name in a log)
}

object FileCleanup {
    /** A bill older than this (by its own date) that isn't a tax file (§7.4: "paid more than 2 years ago"; Robot doesn't know paid). */
    const val OLD_BILL_YEARS = 2L
    /** The CRA and Revenu Québec: six years from the end of the year the records are for (§15, PUBLISHED; not legal advice). */
    const val TAX_YEARS = 6

    /** A tax file: a slip, or a file tagged "tax" or in Taxes. Never suggested. */
    fun tax(e: FileEntry): Boolean = e.kind == FileKind.TAX_SLIP || Drawers.TAX_TAG in e.tags || e.drawer == Drawers.TAXES

    /** The last year a tax file is kept to the end of: its slip's year (else its own day's) plus six. Null for others. */
    fun keepUntil(e: FileEntry, zone: ZoneId = ZoneId.systemDefault()): Int? {
        if (!tax(e)) return null
        val year = e.fields["year"]?.value?.toIntOrNull() ?: FileViews.day(e, zone).year
        return year + TAX_YEARS
    }

    /**
     * Files in [idx] that look no longer needed on [today], each once with its first reason: an exact duplicate (the same
     * SHA-256: the copy that came in later, never one you named), a near-duplicate (the same kind, merchant, date, total and
     * currency, each checked: the later copy), a ticket for a past date, a receipt whose return window closed with no
     * warranty still running, a bill more than two years old. Filed files only, never a tax file, a damaged file (it has its
     * own Delete) or one waiting for a delete's Undo ([hidden]). Suggestions only: this changes nothing.
     */
    fun suggest(idx: FileIndex, today: LocalDate, hidden: Set<FileId> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): List<Cleanup> {
        val pool = idx.entries.filter { it.state == FileState.FILED && !it.damaged && it.id !in hidden && !tax(it) }
        val out = LinkedHashMap<FileId, Cleanup>()
        // Exact duplicates.
        for ((_, same) in pool.filter { it.from.sha256 != null }.groupBy { it.from.sha256 }) {
            if (same.size < 2) continue
            val keep = keeper(same)
            for (e in same) if (e.id != keep.id && !e.named) out.putIfAbsent(e.id, Cleanup(e, CleanupWhy.DUPLICATE, keep.id))
        }
        // Near-duplicates: the same receipt or bill twice (a scan and a shared PDF of it).
        for ((_, same) in pool.filter { it.id !in out }.mapNotNull { e -> nearKey(e)?.let { it to e } }.groupBy({ it.first }, { it.second })) {
            if (same.size < 2) continue
            val keep = keeper(same)
            for (e in same) if (e.id != keep.id && !e.named) out.putIfAbsent(e.id, Cleanup(e, CleanupWhy.NEAR_DUPLICATE, keep.id))
        }
        for (e in pool) {
            if (e.id in out) continue
            val why = when (e.kind) {
                FileKind.TICKET -> FileViews.dateField(e, "date")?.takeIf { it.isBefore(today) }?.let { CleanupWhy.PAST_TICKET }
                FileKind.RECEIPT -> {
                    val back = FileViews.dateField(e, DateKind.RETURN.field)
                    val warranty = FileViews.dateField(e, DateKind.WARRANTY.field)
                    if (back != null && back.isBefore(today) && (warranty == null || warranty.isBefore(today))) CleanupWhy.RETURN_CLOSED else null
                }
                FileKind.BILL -> (FileViews.dateField(e, "date") ?: FileViews.dateField(e, "due"))
                    ?.takeIf { it.isBefore(today.minusYears(OLD_BILL_YEARS)) }?.let { CleanupWhy.OLD_BILL }
                else -> null
            } ?: continue
            out[e.id] = Cleanup(e, why)
        }
        return out.values.sortedWith(compareBy<Cleanup> { it.why.ordinal }.thenBy { FileViews.day(it.entry, zone) })
    }

    /** The copy that stays: one you named, else the first that came in. */
    private fun keeper(same: List<FileEntry>): FileEntry = same.firstOrNull { it.named } ?: same.minBy { it.from.at }

    /** A receipt's or a bill's kind, merchant, date, total and currency, each read and checked; null when one is missing. */
    private fun nearKey(e: FileEntry): String? {
        if (e.kind != FileKind.RECEIPT && e.kind != FileKind.BILL) return null
        if (e.fields["check"] != null) return null
        val merchant = e.fields["merchant"]?.value?.let { FileFields.fold(it).filter(Char::isLetterOrDigit) }?.takeIf { it.isNotEmpty() } ?: return null
        val date = FileViews.dateField(e, "date") ?: return null
        val total = e.fields["total"]?.value?.let(FileFields::amountOf) ?: return null
        val cur = e.fields["currency"]?.value ?: "-"
        return "${e.kind}|$merchant|$date|$total|$cur"
    }
}
