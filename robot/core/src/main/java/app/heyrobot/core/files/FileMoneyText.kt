// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.ListKind
import app.heyrobot.core.i18n.Phrase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// Pure. 1.4, Robot Files (job F10): FileMoney's answers in words, English and Québec French (core i18n FilesStrings).
// Two forms where a file's words could appear: [Said.text] is what the panel shows and History keeps in plain, so it names
// no merchant or file (F8's rule); [Said.spoken] is what Robot says, which may name a merchant, never a private file's
// (FilesForHelper.private: medical files, IDs, anything with a secret). Every number is code's.

/** A reply: [text] shown and kept, [spoken] said. */
data class Said(val text: Phrase, val spoken: Phrase = text)

object FileMoneyText {
    /** How many coming-up dates Robot says by name, and shows as cards. */
    const val NAMED = 3

    // ------------------------------------------------------------------ spending

    /** "You spent $312.40 on gas in September, from 9 receipts. 1 total needs checking, so it's left out." */
    fun spending(q: SpendQuery, r: Spending, today: LocalDate): Phrase {
        val check = if (r.check > 0) FilesStrings.spentCheck(r.check.toLong()) else null
        if (r.files == 0) {
            val none = when {
                r.kinds == setOf(FileKind.BILL) -> FilesStrings.spentNoneBills()
                FileKind.BILL in r.kinds -> FilesStrings.spentNoneBoth()
                else -> FilesStrings.spentNone()
            }
            return if (check == null) none else Phrase.join(none, check)
        }
        val amount = amounts(r.byCurrency)
        val count = when {
            r.kinds == setOf(FileKind.BILL) -> FilesStrings.countBills(r.files.toLong())
            FileKind.BILL in r.kinds -> FilesStrings.countBoth(r.files.toLong())
            else -> FilesStrings.countReceipts(r.files.toLong())
        }
        val what = q.category?.let(::on) ?: q.merchant?.let { FilesStrings.atMerchant("merchant" to it) }
        val whenText = period(q.from, q.to, today)
        val main = when {
            what != null && whenText != null -> FilesStrings.spent("amount" to amount, "what" to what, "when" to whenText, "count" to count)
            what != null -> FilesStrings.spentNoWhen("amount" to amount, "what" to what, "count" to count)
            whenText != null -> FilesStrings.spentNoWhat("amount" to amount, "when" to whenText, "count" to count)
            else -> FilesStrings.spentAll("amount" to amount, "count" to count)
        }
        return if (check == null) main else Phrase.join(main, check)
    }

    /** "$312.40 and 45.00 USD": one amount per currency, largest first. */
    fun amounts(byCurrency: Map<String?, Long>): Phrase =
        Phrase.ListOf(byCurrency.map { (c, v) -> FilesStrings.money("m" to Arg.Money(v, c)) }, ListKind.SUM)

    /** "on gas", « en essence »: a category as spending says it. */
    fun on(category: String): Phrase = when (category) {
        "fuel" -> FilesStrings.onFuel()
        "groceries" -> FilesStrings.onGroceries()
        "restaurants" -> FilesStrings.onRestaurants()
        "pharmacy" -> FilesStrings.onPharmacy()
        "hardware" -> FilesStrings.onHardware()
        "clothing" -> FilesStrings.onClothing()
        "electronics" -> FilesStrings.onElectronics()
        "transit" -> FilesStrings.onTransit()
        "utilities" -> FilesStrings.onUtilities()
        else -> FilesStrings.onOther()
    }

    /** "in September 2026", "in 2026", "on Monday October 12", "from August 3, 2026 to August 9, 2026"; null for no range. */
    fun period(from: LocalDate?, to: LocalDate?, today: LocalDate): Phrase? {
        if (from == null && to == null) return null
        val a = from ?: return null
        val b = to ?: today
        if (a == b) return FilesStrings.onDay("day" to Arg.Day(a))
        val month = YearMonth.from(a)
        if (a.dayOfMonth == 1 && YearMonth.from(b) == month && (b == month.atEndOfMonth() || b == today)) return FilesStrings.inMonth("month" to Arg.Month(a.year, a.monthValue))
        if (a.dayOfYear == 1 && b.year == a.year && (b == LocalDate.of(a.year, 12, 31) || b == today)) return FilesStrings.inYear("year" to a.year.toString())
        return FilesStrings.fromTo("from" to Arg.Date(a), "to" to Arg.Date(b))
    }

    // ------------------------------------------------------------------ what's coming up

    /** The last day "what's due [span]" looks at, from [today]; null: each kind's own window (FileDates.AHEAD). */
    fun until(span: Span, today: LocalDate): LocalDate? = when (span) {
        Span.SOON -> null
        Span.TODAY -> today
        Span.TOMORROW -> today.plusDays(1)
        Span.THIS_WEEK -> today.with(DayOfWeek.SUNDAY)
        Span.NEXT_WEEK -> today.with(DayOfWeek.SUNDAY).plusWeeks(1)
        Span.THIS_MONTH -> YearMonth.from(today).atEndOfMonth()
        Span.NEXT_MONTH -> YearMonth.from(today).plusMonths(1).atEndOfMonth()
        Span.ANY -> today.plusYears(ANY_YEARS)
    }

    /** "When does my passport expire": looked for this far ahead. */
    const val ANY_YEARS = 20L

    private fun span(span: Span, kind: DateKind): Phrase = when (span) {
        Span.SOON, Span.ANY -> FilesStrings.nextDays(FileDates.AHEAD.getValue(kind))
        Span.TODAY -> FilesStrings.today()
        Span.TOMORROW -> FilesStrings.tomorrow()
        Span.THIS_WEEK -> FilesStrings.thisWeek()
        Span.NEXT_WEEK -> FilesStrings.nextWeek()
        Span.THIS_MONTH -> FilesStrings.thisMonth()
        Span.NEXT_MONTH -> FilesStrings.nextMonth()
    }

    /**
     * "2 bills are due this week. The soonest is October 17, 2026." (no names: kept in History), and said with the first
     * [NAMED] by name ("Hydro-Québec, due October 17, 2026: $142.00"). For one file named by your words ([Span.ANY]): its
     * date ("Expires August 14, 2029.").
     */
    fun upcoming(kind: DateKind, span: Span, found: List<FileDate>): Said {
        if (span == Span.ANY) {
            val d = found.firstOrNull() ?: return Said(FilesStrings.noneNamed())
            val line = FilesStrings.sentence("s" to dateLine(d))
            return Said(line, Phrase.join(*found.take(NAMED).map { FilesStrings.sentence("s" to named(it)) }.toTypedArray()))
        }
        val s = span(span, kind)
        if (found.isEmpty()) return Said(when (kind) {
            DateKind.DUE -> FilesStrings.noneDue("span" to s)
            DateKind.EXPIRY -> FilesStrings.noneExpiry("span" to s)
            DateKind.RETURN -> FilesStrings.noneReturn("span" to s)
            DateKind.WARRANTY -> FilesStrings.noneWarranty("span" to s)
        })
        val n = found.size.toLong()
        val count = when (kind) {
            DateKind.DUE -> FilesStrings.dueCount(n, "span" to s)
            DateKind.EXPIRY -> FilesStrings.expiryCount(n, "span" to s)
            DateKind.RETURN -> FilesStrings.returnCount(n, "span" to s)
            DateKind.WARRANTY -> FilesStrings.warrantyCount(n, "span" to s)
        }
        val text = Phrase.join(count, FilesStrings.firstOn("date" to Arg.Date(found.first().date)))
        val spoken = Phrase.join(count, *found.take(NAMED).map { FilesStrings.sentence("s" to named(it)) }.toTypedArray())
        return Said(text, spoken)
    }

    /** "Due October 17, 2026: $142.00", "Expires August 14, 2029": a date's line on its card. */
    fun dateLine(d: FileDate): Phrase {
        val date = Arg.Date(d.date)
        val line = when (d.kind) {
            DateKind.DUE -> FilesStrings.dateDue("date" to date)
            DateKind.RETURN -> FilesStrings.dateReturn("date" to date)
            DateKind.WARRANTY -> FilesStrings.dateWarranty("date" to date)
            DateKind.EXPIRY -> FilesStrings.dateExpiry("date" to date)
        }
        val amount = if (d.kind == DateKind.DUE) amountOf(d.entry) else null
        return if (amount == null) line else FilesStrings.dateAmount("date" to line, "amount" to Arg.Money(amount.first, amount.second))
    }

    /** "Hydro-Québec, due October 17, 2026: $142.00": said, never a private file's name (its kind instead: "an ID, expires …"). */
    fun named(d: FileDate): Phrase = FilesStrings.namedDate("name" to (who(d.entry) ?: FilesStrings.kind(d.entry.kind)), "date" to dateLine(d))

    /** A file's total and currency when it doesn't need checking. */
    fun amountOf(e: FileEntry): Pair<Long, String?>? {
        if (e.fields["check"]?.value == "total") return null // l10n: not text (a field's code)
        val c = e.fields["total"]?.value?.let(FileFields::amountOf) ?: return null
        return c to e.fields["currency"]?.value
    }

    /**
     * What a file is called aloud: its merchant, else the name you gave it; null for a private file (medical, an ID, a
     * secret), or when it has neither (FX2, F13's note: a name Robot or the sender made, "2026-08-14 Costco 84.12 CAD" or
     * "IMG_2034.jpg", is never read out as it is; the caller says its kind).
     */
    fun who(e: FileEntry): Any? {
        if (FilesForHelper.private(e)) return null
        return e.fields["merchant"]?.value?.let(FileFields::cleanName) ?: e.name.takeIf { e.named }?.let { FileTools.value(it, FileTools.MAX_VALUE) }
    }

    /**
     * FX2 (F13's note): a file as Robot says it aloud: "a receipt from Costco on August 14, 2026", « un reçu de Costco du 14
     * août 2026 », "a bill from Bell", "a photo"; the name you gave it as you gave it; a private file (medical, an ID, a
     * secret) by its kind alone.
     */
    fun said(e: FileEntry): Phrase = more(e) ?: FilesStrings.kind(e.kind)

    /** [said], when it says more than the file's kind (its merchant, its day, your name for it); null for a private file. */
    fun more(e: FileEntry): Phrase? {
        val kind = FilesStrings.kind(e.kind)
        if (FilesForHelper.private(e)) return null
        if (e.named) FileTools.value(e.name, FileTools.MAX_VALUE)?.let { return Phrase.plain(it) }
        val m = e.fields["merchant"]?.value?.let(FileFields::cleanName)?.takeIf { it.isNotBlank() }
        val day = e.fields["date"]?.value?.let { try { LocalDate.parse(it) } catch (_: java.time.format.DateTimeParseException) { null } }
        return when {
            m != null && day != null -> FilesStrings.saidFromOn("kind" to Arg.Sub(kind), "merchant" to Arg.Sub(FilesStrings.of(m)), "date" to Arg.Date(day))
            m != null -> FilesStrings.saidFrom("kind" to Arg.Sub(kind), "merchant" to Arg.Sub(FilesStrings.of(m)))
            day != null -> FilesStrings.saidOn("kind" to Arg.Sub(kind), "date" to Arg.Date(day))
            else -> null
        }
    }

    /** add_event's title for a reminder of [d] ("Pay Hydro-Québec bill"): a merchant only from a file that isn't private. */
    fun eventTitle(d: FileDate): Phrase {
        val m = if (FilesForHelper.private(d.entry)) null else d.entry.fields["merchant"]?.value?.let(FileFields::cleanName)
        return when (d.kind) {
            DateKind.DUE -> m?.let { FilesStrings.eventDue("merchant" to Arg.Sub(FilesStrings.of(it))) } ?: FilesStrings.eventDueAny()
            DateKind.RETURN -> m?.let { FilesStrings.eventReturn("merchant" to it) } ?: FilesStrings.eventReturnAny()
            DateKind.WARRANTY -> m?.let { FilesStrings.eventWarranty("merchant" to it) } ?: FilesStrings.eventWarrantyAny()
            DateKind.EXPIRY -> if (d.entry.kind == FileKind.ID) FilesStrings.eventExpiryId() else FilesStrings.eventExpiryDoc()
        }
    }

    // ------------------------------------------------------------------ the morning brief

    /** A brief line: [shown] (the screen, a notification, History: no merchant) and [said] (aloud: a merchant unless private). */
    data class BriefSaid(val shown: Phrase, val said: Phrase)

    /**
     * The brief's files part on [today]: "Robot filed 3 things yesterday: 2 receipts and 1 bill.", then the reminders due,
     * at most [FileBrief.MAX_REMINDERS] by date ("Your Hydro-Québec bill is due Friday October 17: $142.00."), then how
     * many more. An ID's or a document's expiry is never named.
     */
    fun brief(b: FilesBrief, today: LocalDate): List<BriefSaid> {
        val out = ArrayList<BriefSaid>()
        if (b.filed.isNotEmpty()) FilesStrings.briefFiled(b.filed.size.toLong(), "kinds" to FilesStrings.kinds(b.filed)).let { out += BriefSaid(it, it) }
        for (d in b.reminders.take(FileBrief.MAX_REMINDERS)) out += briefLine(d, today)
        val more = b.reminders.size - FileBrief.MAX_REMINDERS
        if (more > 0) FilesStrings.briefMore(more.toLong()).let { out += BriefSaid(it, it) }
        return out
    }

    private fun day(d: LocalDate, today: LocalDate): Arg = when (d) {
        today -> Arg.Sub(FilesStrings.today())
        today.plusDays(1) -> Arg.Sub(FilesStrings.tomorrow())
        // « est due le vendredi 17 octobre »: a date after a verb takes « le » in French (FilesStrings.onDate).
        else -> Arg.Sub(FilesStrings.onDate("d" to if (d.isBefore(today.plusDays(7))) Arg.Day(d) else Arg.Date(d)))
    }

    private fun briefLine(d: FileDate, today: LocalDate): BriefSaid {
        val day = day(d.date, today)
        val m = if (FilesForHelper.private(d.entry)) null else d.entry.fields["merchant"]?.value?.let(FileFields::cleanName)
        return when (d.kind) {
            DateKind.DUE -> {
                val a = amountOf(d.entry)?.let { Arg.Money(it.first, it.second) }
                val shown = if (a == null) FilesStrings.briefDueNoAmount("day" to day) else FilesStrings.briefDue("day" to day, "amount" to a)
                val said = when {
                    m == null -> shown
                    a == null -> FilesStrings.briefDueSaidNoAmount("merchant" to Arg.Sub(FilesStrings.of(m)), "day" to day)
                    else -> FilesStrings.briefDueSaid("merchant" to Arg.Sub(FilesStrings.of(m)), "day" to day, "amount" to a)
                }
                BriefSaid(shown, said)
            }
            DateKind.RETURN -> FilesStrings.briefReturn("day" to day).let { BriefSaid(it, m?.let { x -> FilesStrings.briefReturnSaid("merchant" to x, "day" to day) } ?: it) }
            DateKind.WARRANTY -> FilesStrings.briefWarranty("day" to day).let { BriefSaid(it, m?.let { x -> FilesStrings.briefWarrantySaid("merchant" to x, "day" to day) } ?: it) }
            DateKind.EXPIRY -> (if (d.entry.kind == FileKind.ID) FilesStrings.briefExpiryId("day" to day) else FilesStrings.briefExpiryDoc("day" to day)).let { BriefSaid(it, it) }
        }
    }

    // ------------------------------------------------------------------ subscriptions

    /** "2 subscriptions in your bills: $47.97 a month." (no names), said with each ("Vidéotron: $35.00 a month"). */
    fun subscriptions(subs: List<Subscription>): Said {
        if (subs.isEmpty()) return Said(FilesStrings.subscriptionsNone("n" to FileSubscriptions.MIN_MONTHS))
        val n = subs.size.toLong()
        val monthly = FileSubscriptions.monthly(subs)
        val text = if (monthly.isEmpty()) FilesStrings.subscriptionsCount(n) else FilesStrings.subscriptions(n, "amounts" to amounts(monthly))
        val lines = subs.take(NAMED * 2).mapNotNull { s -> s.cents?.let { c -> FilesStrings.subscriptionLine("name" to s.merchant, "amount" to Arg.Money(c, s.currency)) } }
        return Said(text, if (lines.isEmpty()) text else Phrase.join(text, FilesStrings.subscriptionsList("list" to Phrase.ListOf(lines, ListKind.AND))))
    }

    // ------------------------------------------------------------------ clean-up

    /** Why a suggestion is there, in words. */
    fun why(c: Cleanup): Phrase = when (c.why) {
        CleanupWhy.DUPLICATE -> FilesStrings.cleanDuplicate()
        CleanupWhy.NEAR_DUPLICATE -> FilesStrings.cleanNear()
        CleanupWhy.PAST_TICKET -> FilesStrings.cleanTicket()
        CleanupWhy.RETURN_CLOSED -> FilesStrings.cleanReturn("date" to Arg.Date(FileViews.dateField(c.entry, DateKind.RETURN.field) ?: FileViews.day(c.entry)))
        CleanupWhy.OLD_BILL -> FilesStrings.cleanOldBill("n" to FileCleanup.OLD_BILL_YEARS)
    }

    /** "Keep until the end of 2031: …", for a tax file; null for others. */
    fun keepUntil(e: FileEntry): Phrase? = FileCleanup.keepUntil(e)?.let { FilesStrings.keepUntil("year" to it.toString()) }
}
