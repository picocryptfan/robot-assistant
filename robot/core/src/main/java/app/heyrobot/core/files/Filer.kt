// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import java.time.format.DateTimeFormatter

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.1 steps 8 and 9, §4.4; job F6): deciding where a file goes. The kind (from
// the rules, else the model's word if the rules can live with it) gives the drawer by a fixed table and your rules; the
// name comes from a template over the fields FileFields checked; tags are the month, the merchant and "tax" for slips,
// plus your rules'. The model never writes a name and never picks a drawer: it can only offer a kind and values, and every
// value is re-read by Robot before it reaches a name.
//
// Then the kind's level (Settings › Files): File it on its own (filed, with Undo), Ask me first (it waits in Inbox for
// "File them"), Off (it stays in Inbox, not filed). Unclear at any level: a file whose kind no one settled, or a receipt or
// a bill whose total needs checking, stays in Inbox with Robot's question. Robot never guesses silently.
//
// Your own words ("file this") count as your answer to Ask me first; Off still holds (it is a setting you chose).

/** The starter drawers (§4.4): fixed ids stored in an entry's `drawer`; FilesStrings names them in each language. */
object Drawers {
    const val RECEIPTS = "receipts"
    const val BILLS = "bills"
    const val DOCUMENTS = "documents"
    const val MEDICAL = "medical"
    const val TAXES = "taxes"
    const val TRIPS = "trips"
    const val PHOTOS = "photos"
    const val CONVERSATIONS = "conversations"

    /** In the order Files shows them. */
    val STARTER = listOf(RECEIPTS, BILLS, DOCUMENTS, MEDICAL, TAXES, TRIPS, PHOTOS, CONVERSATIONS)

    /** A kind's drawer (§4.1's table); null for kinds that stay in Inbox (dangerous, unknown). */
    fun of(kind: FileKind, tags: Collection<String> = emptyList()): String? = when (kind) {
        FileKind.RECEIPT -> if (TAX_TAG in tags) TAXES else RECEIPTS
        FileKind.BILL -> BILLS
        FileKind.DOCUMENT, FileKind.ID -> if (TAX_TAG in tags) TAXES else DOCUMENTS
        FileKind.TAX_SLIP -> TAXES
        FileKind.TICKET -> TRIPS
        FileKind.MEDICAL -> MEDICAL
        FileKind.PHOTO, FileKind.VIDEO -> PHOTOS
        FileKind.TRANSCRIPT -> CONVERSATIONS
        FileKind.NOTE, FileKind.OTHER -> DOCUMENTS
        FileKind.DANGEROUS, FileKind.UNKNOWN -> null
    }

    /** The tag for tax files (§4.4). */
    const val TAX_TAG = "tax"
}

/** Where a file goes and what it is called: Robot's decision, never the model's. [by]: Robot on its own, a rule, or you. */
data class Placement(
    val kind: FileKind,
    val drawer: String,
    val name: String,
    val tags: List<String>,
    val fields: Map<String, FileField>,
    val by: FiledBy,
) {
    override fun toString(): String = "Placement($kind, $by)" // l10n: not text (never a name or a value in a log)
}

/** What Robot does with a file it read. */
sealed class FilingDecision {
    /** File it now. */
    data class File(val placement: Placement) : FilingDecision()
    /** It waits in Inbox for you: [why] KIND (with [choices]), TOTAL, or FILE_THEM (Ask me first: [placement] is what Robot would do). */
    data class Ask(val why: FilingWhy, val kind: FileKind?, val fields: Map<String, FileField>, val choices: List<FileKind> = emptyList(),
        val placement: Placement? = null) : FilingDecision()
    /** It stays in Inbox ([why]: OFF, its kind's filing is off). */
    data class Held(val why: FilingWhy, val kind: FileKind?, val fields: Map<String, FileField> = emptyMap()) : FilingDecision()
}

/** The words a name needs that differ by language: a bill's "due" (« échéance »). */
data class NameWords(val due: String)

object Filer {
    /** The kinds a "What kind of file is this?" question offers when the words point nowhere. */
    val DEFAULT_CHOICES = listOf(FileKind.RECEIPT, FileKind.BILL, FileKind.DOCUMENT, FileKind.PHOTO)

    /** A kind's level group (§4.1's table) as Settings › Files sets it. */
    fun level(kind: FileKind, s: FilesSettings): FileLevel = when (kind) {
        FileKind.RECEIPT -> s.receipts
        FileKind.BILL -> s.bills
        FileKind.DOCUMENT, FileKind.ID, FileKind.TAX_SLIP, FileKind.TICKET, FileKind.MEDICAL -> s.documents
        FileKind.PHOTO, FileKind.VIDEO -> s.photos
        FileKind.NOTE, FileKind.TRANSCRIPT, FileKind.OTHER -> s.other
        FileKind.DANGEROUS, FileKind.UNKNOWN -> FileLevel.OFF
    }

    /**
     * The decision for [entry], whose kind is [kind] (null: no one settled it; [choices] then are the kinds to ask about),
     * with Robot's [values]. [rules]: yours (FileRules.yours picks the one that applies); [yourWords]: you asked ("file
     * this"), so Ask me first doesn't wait; [words] for the name's language.
     */
    fun decide(entry: FileEntry, kind: FileKind?, values: FileFieldValues, settings: FilesSettings, rules: List<FilingRule>, yourWords: Boolean,
        words: NameWords, text: String? = null, choices: List<FileKind> = emptyList()): FilingDecision {
        val fields = fields(values)
        if (kind == null || kind == FileKind.UNKNOWN || kind == FileKind.DANGEROUS) {
            val offer = (choices.filter { it != FileKind.UNKNOWN && it != FileKind.DANGEROUS } + DEFAULT_CHOICES).distinct().take(4)
            return FilingDecision.Ask(FilingWhy.KIND, null, fields, offer)
        }
        val level = level(kind, settings)
        if (level == FileLevel.OFF) return FilingDecision.Held(FilingWhy.OFF, kind, fields)
        if ((kind == FileKind.RECEIPT || kind == FileKind.BILL) && (values.totalCheck || values.total == null))
            return FilingDecision.Ask(FilingWhy.TOTAL, kind, fields)
        val placement = place(entry, kind, values, fields, rules, words, text)
        if (level == FileLevel.ASK && !yourWords) return FilingDecision.Ask(FilingWhy.FILE_THEM, kind, fields, placement = placement)
        return FilingDecision.File(placement)
    }

    /** Where [entry] of [kind] goes and its name: your rule first (the most recent that matches), then Robot's table. */
    fun place(entry: FileEntry, kind: FileKind, values: FileFieldValues, fields: Map<String, FileField> = fields(values), rules: List<FilingRule> = emptyList(),
        words: NameWords, text: String? = null): Placement {
        val rule = FileRules.yours(rules, kind, values.merchant, text)
        val tags = LinkedHashSet<String>()
        tags += entry.tags
        if (kind == FileKind.TAX_SLIP) tags += Drawers.TAX_TAG
        val month = (values.date ?: values.due)?.takeIf { kind == FileKind.RECEIPT || kind == FileKind.BILL }?.let { MONTH.format(it) }
        month?.let { tags += it }
        values.merchant?.let(FileFields::cleanName)?.takeIf(FileIndexCodec::label)?.let { tags += it }
        rule?.tags?.filter(FileIndexCodec::label)?.let { tags += it }
        // F12: business or personal, never both; your rule's choice wins over a tag the file had.
        val kept = FileTags.exclusive(tags.toList(), rule?.tags?.firstOrNull { FileTags.other(it) != null }).take(FileIndexCodec.MAX_TAGS)
        val drawer = rule?.drawer?.takeIf(FileIndexCodec::label) ?: Drawers.of(kind, kept) ?: Drawers.DOCUMENTS
        val name = if (entry.named) entry.name else name(kind, values, rule?.nameFirst == true, words) ?: entry.name
        return Placement(kind, drawer, name, kept, fields, if (rule != null) FiledBy.RULE else FiledBy.ROBOT)
    }

    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM") // l10n: not text (a date pattern)

    /**
     * §4.4's templates over checked values only: receipts "2026-08-14 Costco 84.12 CAD", bills "2026-09 Hydro-Québec 142.00
     * CAD due 2026-10-17", slips "2025 T4 Employer", documents "Lease 2026-07-01"; [nameFirst]: "Costco 2026-08-14 84.12
     * CAD". Null when fewer than two parts are known (the file keeps its name) or for kinds named as they came (photos…).
     */
    fun name(kind: FileKind, v: FileFieldValues, nameFirst: Boolean, words: NameWords): String? {
        val money = v.total?.let { c -> FileFields.plain(c) + (v.currency?.let { " $it" } ?: "") }
        val who = v.merchant
        val parts: List<String?> = when (kind) {
            FileKind.RECEIPT -> if (nameFirst) listOf(who, v.date?.toString(), money) else listOf(v.date?.toString(), who, money)
            FileKind.BILL -> {
                val month = (v.date ?: v.due)?.let { MONTH.format(it) }
                val due = v.due?.let { "${words.due} $it" }
                if (nameFirst) listOf(who, month, money, due) else listOf(month, who, money, due)
            }
            FileKind.TAX_SLIP -> listOf(v.year?.toString(), v.slip, who)
            FileKind.DOCUMENT, FileKind.ID, FileKind.MEDICAL, FileKind.TICKET -> {
                // "Lease 2026-07-01": the title first in either style.
                listOf(v.title ?: who, (v.date ?: v.expiry)?.toString())
            }
            else -> return null
        }
        val have = parts.filterNotNull().filter { it.isNotBlank() }
        if (have.size < 2) return null
        return FileIndexCodec.cleanName(have.joinToString(" ")).takeIf { it.isNotEmpty() }
    }

    /** An entry's [fields] read back as values (for "File them", Move's name): only what parses, as [fields] wrote it. */
    fun valuesOf(fields: Map<String, FileField>): FileFieldValues {
        fun v(k: String) = fields[k]?.value
        fun day(k: String) = v(k)?.let { try { java.time.LocalDate.parse(it) } catch (_: java.time.format.DateTimeParseException) { null } }
        return FileFieldValues(
            merchant = v("merchant")?.let(FileFields::cleanName), date = day("date"), total = v("total")?.let(FileFields::amountOf),
            currency = v("currency")?.takeIf { it.length == 3 && it.all(Char::isUpperCase) },
            taxes = fields.filterKeys { it.startsWith("tax_") }.mapNotNull { (k, f) -> FileFields.amountOf(f.value)?.let { k.removePrefix("tax_") to it } }.toMap(),
            due = day("due"), period = v("period"), account = v("account"), card = v("card"), expiry = day("expiry"), slip = v("slip"),
            year = v("year")?.toIntOrNull(), category = v("category"), totalCheck = v("check") == "total", dateCheck = v("check") == "date", // l10n: not text (field codes)
            returnBy = day("return_by"), warranty = day("warranty"),
        )
    }

    /** [v] as an entry's fields, each "Read by Robot". */
    fun fields(v: FileFieldValues): Map<String, FileField> {
        val out = LinkedHashMap<String, FileField>()
        fun put(k: String, s: String?) {
            val x = s?.trim()?.takeIf { it.isNotEmpty() && it.length <= FileIndexCodec.MAX_FIELD_VALUE && FileIndexCodec.plain(it) } ?: return
            out[k] = FileField(x, FieldBy.READ)
        }
        put("merchant", v.merchant)
        put("date", v.date?.toString())
        put("total", v.total?.let(FileFields::plain))
        put("currency", v.currency)
        for ((n, c) in v.taxes) put("tax_$n", FileFields.plain(c))
        put("due", v.due?.toString())
        put("period", v.period)
        put("account", v.account)
        put("card", v.card)
        put("expiry", v.expiry?.toString())
        put("return_by", v.returnBy?.toString())
        put("warranty", v.warranty?.toString())
        put("slip", v.slip)
        put("year", v.year?.toString())
        put("category", v.category)
        if (v.totalCheck) put("check", "total") // l10n: not text (a field's code: "Check the total")
        else if (v.dateCheck) put("check", "date") // l10n: not text (a field's code: "Check the date")
        return out
    }
}
