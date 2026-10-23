// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.memory.Bm25Index
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// Pure. 1.4, Robot Files' search (files-1.4-plan.md §6.5, §5.1; job F9): what the Files screen's search field, the
// panel's "find my …" and the model's find_files all go through.
//
// BM25 (memory's Bm25Index and MemoryText: accents and case folded, French elisions cut, light stems) over each file's
// search text (FileViews.searchText: its name, fields, tags, drawer and kind in both languages, its stored text), plus
// the words F0a found missing (files-1.4-measurements.md §7): a receipt doesn't print "gas", "hydro" or "groceries", and
// a French one never says "receipt". So each entry also carries, from Robot's own fields only (its kind and its spending
// category, never anything the model or the file wrote), everyday words in English and French for them: "fuel gas
// gasoline essence carburant", "utilities hydro electricity électricité", "receipt reçu". BM25 can't search an amount
// ("84.12" is two words to it), so amounts and dates are read out of the words by Robot's own parsers and searched
// through fields: "over 100 $", « plus de 50 $ », "84.12"; "August" (the last one), "last year", « l'an dernier »,
// « en août 2026 ». A kind's word ("receipts", « factures ») is a filter too, and a drawer one more.
//
// Nothing here logs, and a query is never kept.

/** An amount filter, in cents: a total over [over], under [under], or exactly [exactly]. */
data class AmountFilter(val over: Long? = null, val under: Long? = null, val exactly: Long? = null) {
    fun matches(cents: Long): Boolean = (over == null || cents > over) && (under == null || cents < under) && (exactly == null || cents == exactly)

    override fun toString(): String = "AmountFilter(${over != null}, ${under != null}, ${exactly != null})" // l10n: not text (never your words in a log)
}

/**
 * What a search asks: the words left for BM25 ([terms]), and the filters read out of them: a [kind], a [drawer] (a
 * starter drawer's id or your drawer's name), the days ([from] to [to], inclusive) and an [amount].
 */
data class FileQuery(
    val terms: String,
    val kind: FileKind? = null,
    val drawer: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val amount: AmountFilter? = null,
) {
    /** Nothing to search or filter on: every file matches. */
    val empty: Boolean get() = terms.isBlank() && kind == null && drawer == null && from == null && to == null && amount == null

    override fun toString(): String = "FileQuery(${terms.isNotEmpty()}, $kind, ${drawer != null}, ${from != null || to != null}, ${amount != null})" // l10n: not text (never your words in a log)
}

object FileSearch {
    /** Search results, at most (as the Files screen lists them). */
    const val MAX_FOUND = FileViews.MAX_FOUND

    // ------------------------------------------------------------------ what a file is found by

    /** Everyday words for a kind, in both languages (search words, never shown). */
    private val KIND_WORDS: Map<FileKind, String> = mapOf(
        FileKind.RECEIPT to "receipt receipts reçu reçus purchase achat", // l10n: not text (search words)
        FileKind.BILL to "bill bills invoice invoices statement facture factures compte relevé", // l10n: not text (search words)
        FileKind.DOCUMENT to "document documents papier papiers", // l10n: not text (search words)
        FileKind.ID to "id identity card carte identité pièce", // l10n: not text (search words)
        FileKind.TAX_SLIP to "tax slip slips impôt impôts feuillet feuillets", // l10n: not text (search words)
        FileKind.TICKET to "ticket tickets booking reservation billet billets réservation voyage trip", // l10n: not text (search words)
        FileKind.MEDICAL to "medical health santé médical", // l10n: not text (search words)
        FileKind.PHOTO to "photo photos picture image", // l10n: not text (search words)
        FileKind.VIDEO to "video videos vidéo vidéos", // l10n: not text (search words)
        FileKind.NOTE to "note notes", // l10n: not text (search words)
        FileKind.TRANSCRIPT to "transcript conversation transcription", // l10n: not text (search words)
    )

    /** Everyday words for a spending category (MerchantTable.CATEGORIES), in both languages. */
    private val CATEGORY_WORDS: Map<String, String> = mapOf(
        "groceries" to "groceries grocery food épicerie épiceries alimentation nourriture", // l10n: not text (search words)
        "fuel" to "fuel gas gasoline petrol essence carburant gaz", // l10n: not text (search words)
        "restaurants" to "restaurant restaurants meal food coffee repas café resto", // l10n: not text (search words)
        "pharmacy" to "pharmacy drugstore pharmacie", // l10n: not text (search words)
        "hardware" to "hardware tools quincaillerie outils", // l10n: not text (search words)
        "clothing" to "clothing clothes vêtements linge", // l10n: not text (search words)
        "electronics" to "electronics électronique", // l10n: not text (search words)
        "transit" to "transit bus metro subway transport autobus", // l10n: not text (search words)
        "utilities" to "utilities utility hydro electricity power internet phone électricité téléphone cellulaire", // l10n: not text (search words)
    )

    /** F13: two words that name another category than their first word's: « gaz naturel » is billed, not gasoline (« gaz » in Québec). */
    private val CATEGORY_PHRASES: Map<String, String> = mapOf("gaz naturel" to "utilities", "natural gas" to "utilities") // l10n: not text (search words)

    /**
     * The words added to a file's search text: its kind's and its category's, in both languages, from Robot's own fields (a
     * category a field holds, else the one Robot's merchant table gives the merchant field). Nothing the file says.
     */
    fun words(e: FileEntry): String = buildString {
        KIND_WORDS[e.kind]?.let { append(it) }
        category(e)?.let { CATEGORY_WORDS[it] }?.let { if (isNotEmpty()) append(' '); append(it) }
    }

    /** [e]'s spending category: its own field, else the one Robot's merchant table gives its merchant (F10: spending uses it too). */
    fun category(e: FileEntry): String? = e.fields["category"]?.value?.takeIf { it in CATEGORY_WORDS } // l10n: not text (a field's name)
        ?: e.fields["merchant"]?.value?.let(MerchantTable::categoryOf) // l10n: not text (a field's name)

    /** Each category's words folded (FilesRouter.fold), for [categoryOf]. */
    private val CATEGORY_OF: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for ((c, words) in CATEGORY_WORDS) for (w in words.split(' ')) out.putIfAbsent(FilesRouter.fold(w), c)
        for (c in MerchantTable.CATEGORIES) out.putIfAbsent(c, c)
        out
    }

    /**
     * F10: the category [words] name ("gas", « essence », "groceries", « épicerie »), the same words a search finds a file by;
     * null when they name none. One word, or two read as one ("hardware store" is "hardware").
     */
    fun categoryOf(words: String): String? {
        val w = FilesRouter.fold(words).trim().split(' ').filter { it.isNotBlank() }
        if (w.isEmpty() || w.size > 2) return null
        CATEGORY_PHRASES[w.joinToString(" ")]?.let { return it }
        CATEGORY_OF[w.joinToString(" ")]?.let { return it }
        val found = w.mapNotNull { CATEGORY_OF[it] ?: CATEGORY_OF[it.removeSuffix("s")] }.distinct()
        return found.singleOrNull()
    }

    // ------------------------------------------------------------------ reading a query

    /**
     * [query] read into words and filters; [whenText] (find_files' `when`, "August", "last year") and [drawerText] (its
     * `drawer`) when given apart. [yours]: your drawers' names; [today]: for "August" (the last one) and "last month". A
     * drawer that doesn't exist is searched as words.
     */
    fun parse(query: String, whenText: String? = null, drawerText: String? = null, yours: Collection<String> = emptyList(),
        today: LocalDate = LocalDate.now()): FileQuery {
        var w = " " + key(query) + " "
        // Amounts first (a year's four digits are never one: an amount has cents or a currency, or a word that compares).
        val amount = FileAmounts.read(w)?.also { w = it.rest }?.filter
        // Days: from `when`, else from the query's own words.
        val fromWhen = whenText?.let { FileWhen.read(" " + key(it) + " ", today) }
        val inQuery = FileWhen.read(w, today)
        if (inQuery != null) w = inQuery.rest
        val days = fromWhen ?: inQuery
        // A kind's word, the longest first ("tax slips" before "slips").
        var kind: FileKind? = null
        for (k in KINDS) {
            val at = w.indexOf(" $k ")
            if (at >= 0) {
                kind = KIND_OF[k]
                // A word narrower than its kind ("lease", « bail ») is searched for too.
                if (k !in SPECIFIC) w = w.substring(0, at) + " " + w.substring(at + k.length + 1)
                break
            }
        }
        // A drawer: given apart, or named in the words ("in Car", "in my Taxes drawer").
        var drawer = drawerText?.let { FilesRouter.drawer(it, yours) }
        val extra = if (drawerText != null && drawer == null) key(drawerText) else ""
        if (drawer == null) IN_DRAWER.find(w)?.let { m ->
            FilesRouter.drawer(m.groupValues[1], yours)?.let { d -> drawer = d; w = w.removeRange(m.range) + " " }
        }
        val terms = (w + " " + extra).split(' ', '\'').filter { it.isNotBlank() && it !in FILLER }.joinToString(" ")
        return FileQuery(terms, kind, drawer, days?.from, days?.to, amount)
    }

    /** Words that ask, and small words, which a search leaves out ("find", "show me", « trouve », "files"). */
    private val FILLER: Set<String> = FilesRouter.FILLER + setOf("find", "show", "me", "get", "look", "search", "for", "where", "is", "are", "what", "which", // l10n: not text (words Robot hears)
        "any", "some", "file", "files", "fichier", "fichiers", "trouve", "montre", "moi", "cherche", "ou", "est", "sont", "quel", "quels", "quelle", "quelles", // l10n: not text (words Robot hears)
        "with", "avec", "dans", "in", "en", "on", "sur", "to", "a", "au", "aux", "et", "and", "or", "ou", "s", "drawer", "tiroir", "total", "totals", "amount") // l10n: not text (words Robot hears)

    private val KIND_OF: Map<String, FileKind> = FilesRouter.KIND_ONE + FilesRouter.KIND_MANY
    private val KINDS: List<String> = KIND_OF.keys.map(::key).sortedByDescending { it.length }
    private val SPECIFIC: Set<String> = FilesRouter.SPECIFIC.map(::key).toSet()
    /** "in Car", "in my Taxes drawer", « dans Auto », « dans le tiroir Auto »: at the end of the words. */
    private val IN_DRAWER = Regex(" (?:in|from|under|dans|sous) (?:the |my |le |la |mon |ma |l')?(?:drawer |tiroir )?((?:[\\p{L}\\d]+ ){0,2}?[\\p{L}\\d]+)(?: drawer| folder)? *$") // l10n: not text (a parser's pattern)

    /** Folded (lower case, accents off, one character for one) and tidied: what the parsers read. */
    private fun key(s: String): String = FilesRouter.fold(s.replace('’', '\'').replace('\u00A0', ' ').replace('\u202F', ' '))
        .replace(Regex("(?<!\\d),|,(?!\\d)"), " ").replace(Regex("[\\s;!?\"()]+"), " ").trim() // l10n: not text (a parser's pattern)

    // ------------------------------------------------------------------ finding

    /**
     * The files [q] names in [idx], best first: the filters (kind, drawer, days by FileViews.day, a total's amount), then BM25
     * over what is left when [q] has words, else the newest first. [hidden]: files waiting for a delete's Undo. A damaged
     * file is found only by the Files screen's search ([damaged]: you may want to delete it), never by voice or the model.
     */
    fun find(idx: FileIndex, q: FileQuery, hidden: Set<FileId> = emptySet(), zone: ZoneId = ZoneId.systemDefault(), limit: Int = MAX_FOUND,
        damaged: Boolean = false): List<FileEntry> {
        val pool = idx.entries.filter { e ->
            e.id !in hidden && (damaged || !e.damaged) && (q.kind == null || e.kind == q.kind) && (q.drawer == null || e.drawer == q.drawer) && days(e, q, zone) && amount(e, q.amount)
        }.sortedWith(compareByDescending<FileEntry> { FileViews.day(it, zone) }.thenByDescending { it.from.at })
        if (q.terms.isBlank()) return pool.take(limit)
        val byId = pool.associateBy { it.id.hex }
        return Bm25Index.build(pool.map { it.id.hex to FileViews.searchText(it) }).search(q.terms, limit).mapNotNull { byId[it.id] }
    }

    /** All the files [q] names (for counts and totals), best first. */
    fun all(idx: FileIndex, q: FileQuery, hidden: Set<FileId> = emptySet(), zone: ZoneId = ZoneId.systemDefault()): List<FileEntry> =
        find(idx, q, hidden, zone, limit = FileLimits.MAX_FILES)

    private fun days(e: FileEntry, q: FileQuery, zone: ZoneId): Boolean {
        if (q.from == null && q.to == null) return true
        val d = FileViews.day(e, zone)
        return (q.from == null || !d.isBefore(q.from)) && (q.to == null || !d.isAfter(q.to))
    }

    /** A total's amount: only files with a total that doesn't need checking. */
    private fun amount(e: FileEntry, f: AmountFilter?): Boolean {
        if (f == null) return true
        if (e.fields["check"]?.value == "total") return false // l10n: not text (a field's code)
        val cents = e.fields["total"]?.value?.let(FileFields::amountOf) ?: return false // l10n: not text (a field's name)
        return f.matches(cents)
    }
}

/** Days a search asks for: [from] to [to], inclusive; [rest]: the words without them. */
internal data class FileDays(val from: LocalDate, val to: LocalDate, val rest: String)

/**
 * Days in words, English and Québec French: a month ("August", « août », with a year or not: without one, the last
 * August that has begun), a year ("2025", "in 2025"), "this month", "last month", "this year", "last year", "this week",
 * "last week", "today", "yesterday", « ce mois-ci », « le mois dernier », « cette année », « l'an dernier », « l'année
 * dernière », « cette semaine », « la semaine dernière », « aujourd'hui », « hier ». Pure; never a date in the future.
 */
internal object FileWhen {
    private val MONTHS: Map<String, Int> = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december", // l10n: not text (words Robot hears)
    ).withIndex().associate { it.value to it.index + 1 } + listOf(
        "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre", // l10n: not text (words Robot hears)
    ).withIndex().associate { it.value to it.index + 1 } + mapOf("jan" to 1, "feb" to 2, "aug" to 8, "sept" to 9, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12) // l10n: not text (words Robot hears)

    private fun alt(words: Collection<String>) = words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private const val LEAD = "(?:from |in |of |for |during |since |de |d'|du |en |au mois de |au mois d'|du mois de |du mois d'|pendant |depuis )?" // l10n: not text (a parser's pattern)
    /** "may" alone is a verb as often as a month: only with a year, or after "in", "from" or « en », « de ». */
    private val MONTH = Regex(" $LEAD(${alt(MONTHS.keys)})(?: (?:of )?((?:19|20)\\d{2}))?(?= )") // l10n: not text (a parser's pattern)
    private val YEAR = Regex(" (?:from |in |of |for |during |de |d'|du |en |l'annee |annee )?((?:19|20)\\d{2})(?= )") // l10n: not text (a parser's pattern)
    private val RELATIVE: List<Pair<Regex, (LocalDate) -> Pair<LocalDate, LocalDate>>> = listOf(
        Regex(" (?:from |in |for |of |during )?(?:the )?last month(?= )| (?:du |le |au |pendant le )?mois (?:dernier|passe)(?= )") to { t: LocalDate -> // l10n: not text (a parser's pattern)
            YearMonth.from(t).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() } },
        Regex(" (?:from |in |for |of |during )?this month(?= )| (?:de |en )?ce mois(?:-ci| ci)?(?= )") to { t: LocalDate -> t.withDayOfMonth(1) to t }, // l10n: not text (a parser's pattern)
        Regex(" (?:from |in |for |of |during )?(?:the )?last year(?= )| (?:de |en |pendant )?l'(?:an|annee) (?:dernier|derniere|passe|passee)(?= )") to { t: LocalDate -> // l10n: not text (a parser's pattern)
            LocalDate.of(t.year - 1, 1, 1) to LocalDate.of(t.year - 1, 12, 31) },
        Regex(" (?:from |in |for |of |during )?this year(?= )| (?:de |en )?cette annee(?= )") to { t: LocalDate -> LocalDate.of(t.year, 1, 1) to t }, // l10n: not text (a parser's pattern)
        Regex(" (?:from |in |for |of |during )?(?:the )?last week(?= )| (?:de |pendant )?la semaine (?:derniere|passee)(?= )") to { t: LocalDate -> // l10n: not text (a parser's pattern)
            t.with(DayOfWeek.MONDAY).minusWeeks(1).let { it to it.plusDays(6) } },
        Regex(" (?:from |in |for |of |during )?this week(?= )| (?:de )?cette semaine(?= )") to { t: LocalDate -> t.with(DayOfWeek.MONDAY) to t }, // l10n: not text (a parser's pattern)
        Regex(" (?:from )?yesterday(?= )| (?:d')?hier(?= )") to { t: LocalDate -> t.minusDays(1) to t.minusDays(1) }, // l10n: not text (a parser's pattern)
        Regex(" (?:from )?today(?= )| (?:d')?aujourd'hui(?= )") to { t: LocalDate -> t to t }, // l10n: not text (a parser's pattern)
    )

    /** The days [w] (folded, with a space at each end) names, and the words without them; null when it names none. */
    fun read(w: String, today: LocalDate): FileDays? {
        for ((re, range) in RELATIVE) re.find(w)?.let { m -> val (a, b) = range(today); return FileDays(a, b, w.removeRange(m.range) + " ") }
        MONTH.findAll(w).firstOrNull { m -> m.groupValues[1] != "may" || m.groupValues[2].isNotEmpty() || m.value.trimStart().length > "may".length }?.let { m ->
            val month = MONTHS.getValue(m.groupValues[1])
            val year = m.groupValues[2].toIntOrNull() ?: if (month <= today.monthValue) today.year else today.year - 1
            val ym = YearMonth.of(year, month)
            return FileDays(ym.atDay(1), ym.atEndOfMonth(), w.removeRange(m.range) + " ")
        }
        YEAR.find(w)?.let { m ->
            val y = m.groupValues[1].toInt()
            return FileDays(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), w.removeRange(m.range) + " ")
        }
        return null
    }
}

/** An amount a search asks for, and the words without it. */
internal data class FileAmountRead(val filter: AmountFilter, val rest: String)

/**
 * Amounts in words: "over 100 $", "more than $50", "under 20", "between 50 and 100", "84.12", "$84.12", « plus de 100 $ »,
 * « moins de 20 $ », « entre 50 et 100 $ », « 84,12 $ ». A number alone is an amount only with cents or a currency (so
 * "2026" stays a year, "3" a word). Pure.
 */
internal object FileAmounts {
    private const val N = "(?:\\$ ?)?(\\d{1,7}(?:[.,]\\d{1,2})?)(?: ?(?:\\$|dollars?|cad|usd|piastres?|bucks))?" // l10n: not text (a parser's pattern)
    private val BETWEEN = Regex(" (?:between|entre) $N (?:and|et) $N(?= )") // l10n: not text (a parser's pattern)
    private val OVER = Regex(" (?:over|more than|above|greater than|at least|plus de|plus que|au-dessus de|au dessus de|au moins|superieur a|superieurs a) $N(?= )") // l10n: not text (a parser's pattern)
    private val UNDER = Regex(" (?:under|less than|below|at most|moins de|moins que|en dessous de|en-dessous de|au plus|inferieur a|inferieurs a) $N(?= )") // l10n: not text (a parser's pattern)
    /** An amount alone: with cents ("84.12", « 84,12 ») or a currency ("$84", « 84 $ »). */
    private val EXACT = Regex(" (?:\\$ ?(\\d{1,7}(?:[.,]\\d{2})?)|(\\d{1,7}[.,]\\d{2})(?: ?(?:\\$|dollars?|cad|usd))?|(\\d{1,7}) ?(?:\\$|dollars?|cad|usd|piastres?))(?= )") // l10n: not text (a parser's pattern)

    fun cents(s: String): Long? {
        val t = s.replace(',', '.')
        val whole = t.substringBefore('.').toLongOrNull() ?: return null
        val frac = t.substringAfter('.', "").let { if (it.isEmpty()) 0L else it.padEnd(2, '0').take(2).toLong() }
        return whole * 100 + frac
    }

    fun read(w: String): FileAmountRead? {
        BETWEEN.find(w)?.let { m ->
            val a = cents(m.groupValues[1]) ?: return null
            val b = cents(m.groupValues[2]) ?: return null
            return FileAmountRead(AmountFilter(over = minOf(a, b) - 1, under = maxOf(a, b) + 1), w.removeRange(m.range) + " ")
        }
        OVER.find(w)?.let { m -> cents(m.groupValues[1])?.let { return FileAmountRead(AmountFilter(over = it), w.removeRange(m.range) + " ") } }
        UNDER.find(w)?.let { m -> cents(m.groupValues[1])?.let { return FileAmountRead(AmountFilter(under = it), w.removeRange(m.range) + " ") } }
        EXACT.find(w)?.let { m ->
            val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            cents(v)?.let { return FileAmountRead(AmountFilter(exactly = it), w.removeRange(m.range) + " ") }
        }
        return null
    }
}
