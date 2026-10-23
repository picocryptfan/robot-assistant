// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.1 step 7, §7.1; job F6): Robot's own parsers. Every amount, date, currency
// and tax Robot keeps of a file is read here, from the file's own text (a PDF's text layer, a text file's words) or, for a
// picture, from the words the model copied out of it (its `text`), and the model's reading (FileReading) is only ever
// checked against that text: a value the text doesn't hold is not kept. The model read a file anyone could have written,
// so its values are data, and this file is the one place that opens them (Unread.readBy).
//
// What it reads (§7.1): amounts as "84.12", "84,12 $", "$84.12", "84.12 CAD", « 1 234,56 $ » (a no-break space U+00A0 or
// a narrow one U+202F between thousands); dates in ISO, "Aug 14, 2026", « 14 août 2026 », and numeric ones read day-first
// or month-first only when the other reading is impossible (05/14/2026, 14/05/2026), otherwise "Check the date"; the
// currency from symbols and codes, CAD when only "$" appears and the phone is in Canada (the caller says which dollar);
// taxes by name (GST, QST, HST, PST, TPS, TVQ, TVH, TVP).
//
// The traps F0a found (docs: F4's notes), and what answers them:
//   - The model gives the cash tendered (or the change, or the subtotal) as the total. A total is taken from the text's
//     own total line ("Total", "Montant total", "Amount due", …), never from a line that says cash, tendered, change,
//     comptant, monnaie, subtotal, sous-total or a tax; a model's total found only on such a line is a trap, counted, and
//     not kept.
//   - The model writes a tax rate as `taxes` ("14.975", "5%"). Taxes are read from the text's tax lines alone, and an
//     amount followed by "%" is a rate, never money; the model's `taxes` is only compared, never kept.
//   - Wrong-typed or injected values: FileReading already dropped values of the wrong type; here a value that doesn't
//     parse, or parses to something the text doesn't hold, is not kept ("Kind: medical", "Total: 0.01" written into a
//     receipt by someone else is only text, and a total must match the text's total line).
// When the text has one total line and the model says something else that the text can't explain, Robot doesn't guess:
// the total is marked "Check the total" (§7.3), and a receipt or a bill so marked stays in Inbox (Filer).

/** How sure Robot is of a field it read: [OK], it read it; [CHECK], the reading and the text disagree or the text is ambiguous. */
enum class FieldCheck { OK, CHECK }

/**
 * What Robot kept of one file, every value re-read here: [merchant] and [title] (letters, digits, spaces and &'.- only,
 * at most 40, and found in the text), [date] (the receipt's or the document's), [total] in cents (a bill's amount due),
 * [currency] (a code), [taxes] in cents by canonical name (gst, qst, hst, pst), [due], [period] (a bill's, "2026-09-01/
 * 2026-09-30"), [account] and [card] (last 4 digits only), [expiry], [slip] (T4, RL-1…), [year] (a slip's), [category].
 * [totalCheck], [dateCheck]: what needs you. [traps]: model values that matched a trap (the cash tendered, a tax rate),
 * [rejected]: model values the text doesn't hold (counts, for the voice log). F10: [returnBy] and [warranty], read from the
 * text's own labelled lines only (the model's reading has no such keys).
 */
data class FileFieldValues(
    val merchant: String? = null,
    val title: String? = null,
    val date: LocalDate? = null,
    val total: Long? = null,
    val currency: String? = null,
    val taxes: Map<String, Long> = emptyMap(),
    val due: LocalDate? = null,
    val period: String? = null,
    val account: String? = null,
    val card: String? = null,
    val expiry: LocalDate? = null,
    val slip: String? = null,
    val year: Int? = null,
    val category: String? = null,
    val totalCheck: Boolean = false,
    val dateCheck: Boolean = false,
    val traps: Int = 0,
    val rejected: Int = 0,
    /** F10: the last day to return it, from a line that says so ("Return by", « Retour avant le »): never a window Robot works out. */
    val returnBy: LocalDate? = null,
    /** F10: when its warranty ends, from a line that says so ("Warranty until", « Garantie jusqu'au »). */
    val warranty: LocalDate? = null,
) {
    /** Never a value in a log line. */
    override fun toString(): String = "FileFieldValues(total=${total != null}, check=$totalCheck/$dateCheck, traps=$traps, rejected=$rejected)" // l10n: not text (counts for a log)
}

object FileFields {
    /** Merchants and titles: §4.2's 40 characters. */
    const val MAX_NAME = 40
    /** The largest amount Robot keeps (a million dollars, in cents): a "total" past it is no receipt's. */
    const val MAX_CENTS = 100_000_000L
    /** Two amounts this close (in cents) are the same total (a rounding cent). */
    const val SAME_CENTS = 1L
    /** The tax rates Canada's receipts print (GST 5, QST 9.975, HST 13 to 15, PST 6 to 8, both together 14.975). */
    private val RATES = setOf("5", "6", "7", "8", "9.975", "10", "11", "12", "13", "14", "14.975", "15")

    // ------------------------------------------------------------------ amounts

    /** An amount on a line: [cents], its own currency marker (a code, "$" or null), whether "%" follows it (a rate). */
    data class Amount(val cents: Long, val marker: String?, val rate: Boolean, val line: Int)

    private val AMOUNT = Regex(
        "(?<![\\p{L}\\d.,])" + // l10n: not text (a parser's pattern)
            "(US\\$|USD\\s?\\$?|CAN\\$|CA\\$|C\\$|CAD\\s?\\$?|\\$|€|£)?[ \\u00A0\\u202F]?" + // l10n: not text (a parser's pattern)
            "(\\d{1,3}(?:[,. \\u00A0\\u202F]\\d{3})+|\\d{1,9})([.,])(\\d{2})(?![\\d]|[.,]\\d)" + // l10n: not text (a parser's pattern)
            "(?:[ \\u00A0\\u202F]?(\\$|€|£|CAD|USD|EUR|GBP)(?![\\p{L}]))?" + // l10n: not text (a parser's pattern)
            "(?:[ \\u00A0\\u202F]*(%))?", // l10n: not text (a parser's pattern)
    )

    /** Every amount in [line] (line number [n]): "84.12", "84,12 $", "$1,234.56", « 1 234,56 $ »; never a date's or a phone's digits. */
    fun amounts(line: String, n: Int = 0): List<Amount> = AMOUNT.findAll(line).mapNotNull { m ->
        val whole = m.groupValues[2]
        val sep = m.groupValues[3]
        // Thousands and decimals can't share a separator, and a period's thousands are commas (and the other way round).
        val group = whole.firstOrNull { !it.isDigit() }
        if (group != null) {
            if (whole.any { !it.isDigit() && it != group }) return@mapNotNull null
            if (sep == "." && group != ',') return@mapNotNull null
            if (sep == "," && group == ',') return@mapNotNull null
        }
        val digits = whole.filter(Char::isDigit)
        val cents = (digits.toLongOrNull() ?: return@mapNotNull null) * 100 + m.groupValues[4].toLong()
        if (cents > MAX_CENTS) return@mapNotNull null
        val marker = (m.groupValues[1].ifEmpty { m.groupValues[5] }).ifEmpty { null }?.let(::currencyOf)
        Amount(cents, marker, m.groupValues[6] == "%", n)
    }.toList()

    /**
     * A value the model wrote for an amount ("84.12", "$84.12", "84,12 $", 66): cents, or null when it isn't exactly one
     * amount (a rate, two numbers, words). A whole number is that many dollars.
     */
    fun amountOf(s: String): Long? {
        val t = s.trim()
        if (Regex("^\\d{1,9}$").matches(t)) return t.toLong() * 100 // l10n: not text (a parser's pattern)
        if (Regex("^\\d{1,9}\\.\\d$").matches(t)) return t.substringBefore('.').toLong() * 100 + t.substringAfter('.').toLong() * 10 // l10n: not text (a parser's pattern)
        val all = amounts(t)
        val one = all.singleOrNull() ?: return null
        return one.cents.takeIf { !one.rate }
    }

    /** "84.12": cents as the names and fields write them, the same in both languages. */
    fun plain(cents: Long): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    // ------------------------------------------------------------------ currency

    private val CODES = setOf("CAD", "USD", "EUR", "GBP")

    /** A currency marker as a code ("$" stays "$": which dollar is the caller's to say). */
    private fun currencyOf(m: String): String? {
        val t = m.replace(Regex("\\s"), "").uppercase(Locale.ROOT) // l10n: not text (a parser's pattern)
        return when {
            t.startsWith("US") -> "USD" // l10n: not text (a currency code)
            t.startsWith("CA") || t.startsWith("C$") || t == "CAN$" -> "CAD" // l10n: not text (a currency code)
            t == "$" -> "$"
            t == "€" || t == "EUR" -> "EUR" // l10n: not text (a currency code)
            t == "£" || t == "GBP" -> "GBP" // l10n: not text (a currency code)
            t in CODES -> t
            else -> null
        }
    }

    /** The currency codes and symbols [text] holds anywhere (as codes, "$" for a bare dollar sign). */
    private fun markers(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        val up = text.uppercase(Locale.ROOT)
        if (Regex("US\\$|\\bUSD\\b").containsMatchIn(up)) out += "USD" // l10n: not text (a parser's pattern)
        if (Regex("CAN?\\$|\\bC\\$|\\bCAD\\b").containsMatchIn(up)) out += "CAD" // l10n: not text (a parser's pattern)
        if ('€' in text || Regex("\\bEUR\\b").containsMatchIn(up)) out += "EUR" // l10n: not text (a parser's pattern)
        if ('£' in text || Regex("\\bGBP\\b").containsMatchIn(up)) out += "GBP" // l10n: not text (a parser's pattern)
        // A dollar sign that isn't part of US$, CA$, C$ or CAN$.
        if (Regex("(?<![A-Z])\\$").containsMatchIn(up)) out += "$" // l10n: not text (a parser's pattern)
        return out
    }

    /**
     * The currency of an amount whose own marker is [own] in [text]: its own code; else the one code the text holds; else
     * [dollar] (CAD on a Canadian phone) when the only marker is a bare "$"; else null (none, or more than one).
     */
    private fun currency(own: String?, text: String, dollar: String?): String? {
        if (own != null && own != "$") return own
        val codes = markers(text) - "$"
        if (codes.size == 1) return codes.single()
        if (codes.isEmpty() && (own == "$" || "$" in markers(text))) return dollar
        return null
    }

    // ------------------------------------------------------------------ lines and their words

    /** [s] folded for matching: lower case, no accents, no-break spaces as spaces, ’ as '. */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") // l10n: not text (a parser's pattern)
        .replace(' ', ' ').replace(' ', ' ').replace('’', '\'').lowercase(Locale.ROOT)

    private fun words(vararg w: String) = Regex("(?<![\\p{L}\\d])(" + w.joinToString("|") + ")(?![\\p{L}\\d])") // l10n: not text (a parser's pattern)

    private val SUBTOTAL = words("sub ?-?total", "sous ?-?total", "total partiel", "montant partiel") // l10n: not text (words Robot reads)
    private val TOTAL = words("total", "grand total", "montant total", "total a payer", "a payer", "amount due", "total due", "balance due", // l10n: not text (words Robot reads)
        "montant du", "montant exigible", "solde du", "solde a payer", "net a payer", "montant a payer", "total ttc", "amount payable", "total amount") // l10n: not text (words Robot reads)
    private val TENDER = words("cash", "comptant", "argent", "especes", "tendered", "tender", "montant recu", "change", "monnaie", "rendu", // l10n: not text (words Robot reads)
        "remis", "cash back", "remise en argent") // l10n: not text (words Robot reads)
    private val PAYMENT = words("paid", "paye", "payment", "paiement", "carte", "card") // l10n: not text (words Robot reads)
    private val INCLUDED = words("incl", "included", "including", "inclus", "incluses", "incluse", "comprises", "compris") // l10n: not text (words Robot reads)
    private val TAX = words("gst", "tps", "hst", "tvh", "qst", "tvq", "pst", "tvp", "rst", "tax", "taxe", "taxes", "tx") // l10n: not text (words Robot reads)
    private val NOT_TOTAL = words("savings", "saved", "economies", "economise", "rabais", "remise", "discount", "points", "items", "articles", // l10n: not text (words Robot reads)
        "nombre", "qty", "qte", "previous", "precedent", "last payment", "dernier paiement") // l10n: not text (words Robot reads)
    private val CARD = words("visa", "mastercard", "master card", "mc", "amex", "american express", "interac", "debit", "discover") // l10n: not text (words Robot reads)
    private val DUE = words("due date", "date due", "due by", "payable by", "pay by", "date d'echeance", "echeance", "a payer avant", // l10n: not text (words Robot reads)
        "payable avant", "date limite", "due on", "payer au plus tard") // l10n: not text (words Robot reads)
    private val EXPIRY = words("expiry", "expires", "expiry date", "exp", "date d'expiration", "expiration", "expire le", "expire", "valid until", // l10n: not text (words Robot reads)
        "valide jusqu'au", "valid thru", "valable jusqu'au") // l10n: not text (words Robot reads)
    private val DATE_LABEL = words("date", "transaction date", "date de transaction", "invoice date", "date de la facture", "billing date", // l10n: not text (words Robot reads)
        "date de facturation", "statement date", "date du releve", "issued", "emis le", "date d'emission") // l10n: not text (words Robot reads)
    private val PERIOD = words("billing period", "periode de facturation", "service period", "periode de consommation", "period", "periode") // l10n: not text (words Robot reads)
    private val ACCOUNT = words("account number", "account no", "account #", "acct", "numero de compte", "no de compte", "n° de compte", // l10n: not text (words Robot reads)
        "customer number", "numero de client", "no de client", "account", "compte") // l10n: not text (words Robot reads)
    /** F10: the last day to return what a receipt bought. Not "return date": a ticket's return flight has one. */
    private val RETURN = words("return by", "returns by", "return before", "return until", "returns until", "returnable until", "returns accepted until", // l10n: not text (words Robot reads)
        "return deadline", "last day to return", "last day for returns", "retour avant", "retours avant", "retour jusqu'au", "retours jusqu'au", // l10n: not text (words Robot reads)
        "retours acceptes jusqu'au", "date limite de retour", "date limite pour les retours", "retourner avant", "echange ou remboursement avant", // l10n: not text (words Robot reads)
        "remboursement avant") // l10n: not text (words Robot reads)
    /** F10: when a warranty ends. */
    private val WARRANTY = words("warranty until", "warranty ends", "warranty end", "warranty expires", "warranty expiry", "warranty expiration", // l10n: not text (words Robot reads)
        "warranty valid until", "under warranty until", "covered until", "garantie jusqu'au", "garantie valide jusqu'au", "garantie valable jusqu'au", // l10n: not text (words Robot reads)
        "fin de garantie", "fin de la garantie", "garantie expire", "expiration de la garantie") // l10n: not text (words Robot reads)
    private val OTHER_DATE = words("due date", "date due", "due by", "payable by", "pay by", "date d'echeance", "echeance", "a payer avant", // l10n: not text (words Robot reads)
        "payable avant", "date limite", "due on", "expiry", "expires", "expiration", "date d'expiration", "valid until", "valide jusqu'au", // l10n: not text (words Robot reads)
        "billing period", "periode de facturation", "service period", "periode", "period", // l10n: not text (words Robot reads)
        // F10: a return's last day and a warranty's end are never the document's own date.
        "return by", "returns by", "return before", "return until", "returns until", "returnable until", "returns accepted until", "return deadline", // l10n: not text (words Robot reads)
        "last day to return", "last day for returns", "retour avant", "retours avant", "retour jusqu'au", "retours jusqu'au", "retourner avant", // l10n: not text (words Robot reads)
        "remboursement avant", "warranty", "garantie", "covered until") // l10n: not text (words Robot reads)
    private val YEAR = words("year", "annee", "tax year", "annee d'imposition") // l10n: not text (words Robot reads)

    private val WARRANTY_OR_RETURN = Regex(WARRANTY.pattern + "|" + RETURN.pattern) // l10n: not text (a parser's pattern)

    /** Canonical tax names: TPS is GST, TVQ is QST, TVH is HST, TVP and RST are PST. */
    private val TAX_NAMES = listOf(
        words("gst", "tps") to "gst", words("hst", "tvh") to "hst", words("qst", "tvq") to "qst", // l10n: not text (words Robot reads)
        words("pst", "tvp", "rst") to "pst", // l10n: not text (words Robot reads)
    )

    /** What a line says it holds. */
    private enum class LineKind { TOTAL, SUBTOTAL, TENDER, PAYMENT, TAX, OTHER }

    private class Line(val raw: String, val folded: String, val amounts: List<Amount>) {
        val words: String = folded.replace(AMOUNT_LIKE, " ")
        val kind: LineKind = when {
            SUBTOTAL.containsMatchIn(words) -> LineKind.SUBTOTAL
            // « Total (taxes incluses) » is the total, not a tax.
            TOTAL.containsMatchIn(words) && INCLUDED.containsMatchIn(words) && !NOT_TOTAL.containsMatchIn(words) -> LineKind.TOTAL
            TAX.containsMatchIn(words) -> LineKind.TAX
            TENDER.containsMatchIn(words) -> LineKind.TENDER
            TOTAL.containsMatchIn(words) && !NOT_TOTAL.containsMatchIn(words) -> LineKind.TOTAL
            PAYMENT.containsMatchIn(words) || CARD.containsMatchIn(words) -> LineKind.PAYMENT
            else -> LineKind.OTHER
        }
    }

    private val AMOUNT_LIKE = Regex("[\\d.,$€£%]+") // l10n: not text (a parser's pattern)

    private fun lines(text: String): List<Line> = split(text).split('\n').mapIndexed { i, raw -> Line(raw, fold(raw), amounts(raw, i)) }

    /**
     * Where a line is cut before reading (F0a: 13 of 40 receipts came back from the model as one run-on line, "SUBTOTAL
     * 63.74 HST 13% 2.57 TOTAL 66.31 CASH 100.00"): before each amount's label, so each amount keeps its own label.
     */
    private val CUT = Regex("(?<![\\p{L}\\d])(sub ?-?total|sous ?-?total|grand total|montant total|total|amount due|balance due|montant du|montant a payer|" + // l10n: not text (a parser's pattern)
        "solde du|gst|tps|hst|tvh|qst|tvq|pst|tvp|cash|comptant|tendered|change|monnaie|rendu|visa|mastercard|interac|debit|due date|date d'echeance|" + // l10n: not text (a parser's pattern)
        "invoice date|date de la facture|billing date|statement date)(?![\\p{L}\\d])") // l10n: not text (a parser's pattern)

    /** [text] with a line break before each label that isn't a line's first words. Folding is per character, so indexes match. */
    private fun split(text: String): String {
        val folded = foldEach(text)
        val cuts = CUT.findAll(folded).map { it.range.first }.filter { i -> folded.substring(text.lastIndexOf('\n', i - 1) + 1, i).any(Char::isLetterOrDigit) }.toList()
        if (cuts.isEmpty()) return text
        val b = StringBuilder(text.length + cuts.size)
        var at = 0
        for (c in cuts) { b.append(text, at, c).append('\n'); at = c }
        return b.append(text, at, text.length).toString()
    }

    /** [s] folded one character for one: the same length, so a match's place in it is its place in [s]. */
    private fun foldEach(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s) {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: c
            b.append(when (d) { '\u00A0', '\u202F' -> ' '; '’' -> '\''; else -> d.lowercaseChar() })
        }
        return b.toString()
    }

    // ------------------------------------------------------------------ dates

    /** A date in the text: [date] when it reads one way only; else [candidates] (05/04/2026: May 4 or April 5). */
    data class DateFound(val date: LocalDate?, val candidates: Set<LocalDate>, val line: Int)

    private val MONTHS: List<Pair<String, Int>> = listOf(
        "january" to 1, "janvier" to 1, "janv" to 1, "jan" to 1, "february" to 2, "fevrier" to 2, "fevr" to 2, "feb" to 2, "fev" to 2, // l10n: not text (words Robot reads)
        "march" to 3, "mars" to 3, "mar" to 3, "april" to 4, "avril" to 4, "avr" to 4, "apr" to 4, "may" to 5, "mai" to 5, // l10n: not text (words Robot reads)
        "june" to 6, "juin" to 6, "jun" to 6, "july" to 7, "juillet" to 7, "juil" to 7, "jul" to 7, "august" to 8, "aout" to 8, "aug" to 8, // l10n: not text (words Robot reads)
        "september" to 9, "septembre" to 9, "sept" to 9, "sep" to 9, "october" to 10, "octobre" to 10, "oct" to 10, // l10n: not text (words Robot reads)
        "november" to 11, "novembre" to 11, "nov" to 11, "december" to 12, "decembre" to 12, "dec" to 12, // l10n: not text (words Robot reads)
    )
    private val MONTH_OF = MONTHS.toMap()
    private val MONTH_WORD = MONTHS.joinToString("|") { it.first }
    private val ISO = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)") // l10n: not text (a parser's pattern)
    private val NUMERIC = Regex("(?<![\\d/.-])(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})(?!\\d)") // l10n: not text (a parser's pattern)
    private val DAY_MONTH = Regex("(?<![\\p{L}\\d])(\\d{1,2})(?:er|st|nd|rd|th)?\\s+($MONTH_WORD)\\.?,?\\s+(\\d{4})(?!\\d)") // l10n: not text (a parser's pattern)
    private val MONTH_DAY = Regex("(?<![\\p{L}])($MONTH_WORD)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)") // l10n: not text (a parser's pattern)

    private fun date(y: Int, m: Int, d: Int): LocalDate? = try {
        if (y !in 1900..2199) null else LocalDate.of(y, m, d)
    } catch (_: DateTimeException) {
        null
    }

    /** Every date in [line] (number [n]): ISO, with a month's name in English or French, or numeric (§7.1's rule). */
    fun dates(line: String, n: Int = 0): List<DateFound> {
        val f = fold(line)
        val out = ArrayList<DateFound>()
        val taken = ArrayList<IntRange>()
        fun add(r: IntRange, d: DateFound) {
            if (taken.any { it.first <= r.last && r.first <= it.last }) return
            taken += r
            out += d
        }
        for (m in ISO.findAll(f)) date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { add(m.range, DateFound(it, setOf(it), n)) }
        for (m in DAY_MONTH.findAll(f)) date(m.groupValues[3].toInt(), MONTH_OF.getValue(m.groupValues[2]), m.groupValues[1].toInt())?.let { add(m.range, DateFound(it, setOf(it), n)) }
        for (m in MONTH_DAY.findAll(f)) date(m.groupValues[3].toInt(), MONTH_OF.getValue(m.groupValues[1]), m.groupValues[2].toInt())?.let { add(m.range, DateFound(it, setOf(it), n)) }
        for (m in NUMERIC.findAll(f)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = m.groupValues[3].toInt()
            val dayFirst = date(y, b, a)
            val monthFirst = date(y, a, b)
            val all = setOfNotNull(dayFirst, monthFirst)
            if (all.isEmpty()) continue
            add(m.range, DateFound(all.singleOrNull(), all, n))
        }
        return out.zip(taken).sortedBy { it.second.first }.map { it.first }
    }

    /** A date the model wrote ("2026-08-14", "Aug 14, 2026"): one reading, or null (none, two, or one that could be either). */
    fun dateOf(s: String): LocalDate? = dates(s).singleOrNull()?.date

    // ------------------------------------------------------------------ names

    /** Letters and digits only, folded: how a merchant or a title is looked for in the text. */
    private fun key(s: String): String = fold(s).filter(Char::isLetterOrDigit)

    /** [s] as a name part: letters, digits, spaces and &'.- (§4.2), at most [MAX_NAME], or null. */
    fun cleanName(s: String): String? {
        val kept = s.map { c -> if (c.isLetterOrDigit() || c in "&'.-") c else ' ' }.joinToString("")
        return kept.replace(Regex(" +"), " ").trim().take(MAX_NAME).trim().takeIf { it.length >= 2 && it.any(Char::isLetter) } // l10n: not text (a parser's pattern)
    }

    /** A merchant's name is at a receipt's or a bill's top: the first lines Robot looks for it in. EST. */
    const val MERCHANT_LINES = 6

    /** [text]'s first [MERCHANT_LINES] lines with words. */
    private fun head(text: String): String = text.split('\n').filter { it.isNotBlank() }.take(MERCHANT_LINES).joinToString("\n")

    /** Whether [name] appears in [text] (letters and digits, folded): a merchant or title the model made up isn't kept. */
    fun inText(name: String, text: String): Boolean {
        val k = key(name)
        return k.length >= 2 && key(text).contains(k)
    }

    // ------------------------------------------------------------------ the check

    /**
     * Robot's reading of [text] ([ownText]: the file's own words, a text layer or a text file; else a picture's words as the
     * model copied them), with the model's [reading] (or none) checked against it. [dollar]: the currency a bare "$" means here (CAD on a Canadian phone; null leaves it
     * unknown). [today]: for the dates' range. [merchants]: Robot's table (rules first: a known merchant in the text wins
     * over the model's word).
     */
    fun check(text: String?, ownText: Boolean, reading: FileReading?, dollar: String?, today: LocalDate, merchants: MerchantTable = MerchantTable): FileFieldValues {
        val t = text.orEmpty()
        val ls = lines(t)
        var traps = 0
        var rejected = 0

        // The merchant: Robot's table first, then the model's word if the text holds it.
        val known = merchants.find(t)
        val modelMerchant = reading?.merchant?.let(::cleanName)
        val merchant = known?.name ?: modelMerchant?.takeIf { inText(it, head(t)) }
        if (known == null && modelMerchant != null && merchant == null) rejected++
        val title = reading?.title?.let(::cleanName)?.let { tt -> if (inText(tt, t)) tt else { rejected++; null } }

        // The total.
        val totals = ls.filter { it.kind == LineKind.TOTAL }.flatMap { l -> l.amounts.filter { !it.rate } }
        val distinct = totals.map { it.cents }.distinct()
        val trapCents = ls.filter { it.kind == LineKind.TENDER || it.kind == LineKind.SUBTOTAL || it.kind == LineKind.TAX }
            .flatMap { l -> l.amounts.map { it.cents } }.toSet()
        val anywhere = ls.flatMap { l -> l.amounts.filter { !it.rate }.map { it.cents } }.toSet()
        val subtotal = ls.filter { it.kind == LineKind.SUBTOTAL }.flatMap { it.amounts }.firstOrNull { !it.rate }?.cents
        val taxes = taxes(ls)
        val sum = subtotal?.let { s -> s + taxes.values.sum() }?.takeIf { taxes.isNotEmpty() }
        val modelTotal = reading?.total?.readBy(::amountOf)
        // The tax rate written as `taxes` ("14.975", "5 %"): counted as a trap; the model's taxes are never kept anyway.
        val rawTaxes = reading?.taxes?.readBy { it }
        val modelTaxes = rawTaxes?.let { amountOf(it) }
        if (rawTaxes != null && (rawTaxes.contains('%') || rawTaxes.trim() in RATES)) traps++
        var totalCheck = false
        val total: Long? = when {
            distinct.size == 1 -> {
                val only = distinct.single()
                when {
                    modelTotal == null || same(only, modelTotal) -> only
                    // The model gave the cash tendered, the change, the subtotal or a tax as the total: the text's total stands.
                    // From a picture, a model that fell for it may have copied the rows a line off too (F0a: 3 of 4): check it.
                    modelTotal in trapCents || (modelTaxes != null && same(modelTotal, modelTaxes)) -> { traps++; if (!ownText) totalCheck = true; only }
                    // A text layer is the file's own words: its one total line stands, and the model's other number goes.
                    ownText -> { rejected++; only }
                    // A picture's words are the model's copy: when its own total and its copy disagree, Robot doesn't guess.
                    else -> { rejected++; totalCheck = true; only }
                }
            }
            distinct.size > 1 -> {
                // Several totals: the one the subtotal and taxes add up to; else Robot doesn't pick, and the model doesn't
                // either (a second "Total: 0.01" written into the file is a total line too, and the model may repeat it).
                val bySum = sum?.let { s -> distinct.firstOrNull { same(it, s) } }
                if (modelTotal != null && (bySum == null || !same(bySum, modelTotal))) { if (modelTotal in trapCents) traps++ else rejected++ }
                bySum ?: run { totalCheck = true; distinct.max() }
            }
            // No total line: the model's total if the text holds it outside a trap's line, else check.
            modelTotal != null -> when {
                modelTotal in trapCents -> { traps++; totalCheck = true; null }
                modelTotal in anywhere -> modelTotal
                else -> { rejected++; totalCheck = true; null }
            }
            sum != null -> sum.takeIf { s -> s in anywhere }
            else -> null
        }
        // A picture's words are the model's own copy, and a copy made from the same picture repeats its mistakes (F0a: 4 of
        // the 5 wrong totals passed "the total appears in the text"). So from a picture a total is kept only when it isn't
        // also the cash tendered or the change (the row F0a's wrong totals came from), and, when the copy has a subtotal
        // and taxes, they add up to it. Otherwise: "Check the total".
        if (!ownText && total != null && !totalCheck) {
            val onTender = ls.any { l -> l.kind == LineKind.TENDER && l.amounts.any { same(it.cents, total) } }
            if (onTender || (sum != null && !same(sum, total))) totalCheck = true
        }

        // The currency: the total's own marker, else the text's.
        val totalMarker = total?.let { c -> ls.flatMap { it.amounts }.firstOrNull { it.cents == c }?.marker }
        // Never the model's: F0a's model said USD for a bare "$" 9 times in 60; this rule never got one wrong.
        val currency = currency(totalMarker, t, dollar)

        // Dates. The document's own date is the one a date label marks ("Date", « Date de la facture »), else the first date
        // that no due, expiry or period label marks; the model's is kept only when it is that one. F0a's model gave a bill's
        // due date or a period's date as its date 13 times in 20: Robot checks rather than guesses.
        val found = ls.flatMap { dates(it.raw, ls.indexOf(it)) }
        // A line with two dates is a period's ("du 2026-08-01 au 2026-09-30").
        fun other(d: DateFound): Boolean = OTHER_DATE.containsMatchIn(ls[d.line].words) || found.count { it.line == d.line } >= 2 ||
            (d.line > 0 && OTHER_DATE.containsMatchIn(ls[d.line - 1].words) && ls[d.line - 1].amounts.isEmpty() && found.none { it.line == d.line - 1 })
        val labelled = found.filter { d -> DATE_LABEL.containsMatchIn(ls[d.line].words) && !other(d) }
        val plain = found.filter { !other(it) }
        val modelDate = reading?.date?.readBy(::dateOf)
        var dateCheck = false
        val date: LocalDate? = when {
            labelled.isNotEmpty() -> {
                val mine = labelled.first()
                when {
                    modelDate != null && mine.date != modelDate -> { rejected++; dateCheck = true; null }
                    mine.date == null -> { dateCheck = true; null }
                    else -> mine.date
                }
            }
            modelDate != null && plain.any { it.date == modelDate } -> modelDate
            // The text's date reads two ways (05/04/2026): the model's choice isn't a reason to pick one.
            modelDate != null && plain.any { modelDate in it.candidates } -> { dateCheck = true; null }
            modelDate != null -> { rejected++; dateCheck = true; null }
            else -> plain.firstOrNull()?.let { d -> d.date ?: run { dateCheck = true; null } }
        }?.takeIf { it.year >= 1990 && !it.isAfter(today.plusDays(1)) }

        val due = labelledDate(ls, found, DUE, reading?.due?.readBy(::dateOf)) { rejected++ }
        // F10: "Warranty expires …" is a warranty's end, never the document's expiry.
        val expiry = labelledDate(ls, found, EXPIRY, reading?.expiry?.readBy(::dateOf), except = WARRANTY_OR_RETURN) { rejected++ }
        val returnBy = labelledDate(ls, found, RETURN, null) {}
        val warranty = labelledDate(ls, found, WARRANTY, null) {}
        val period = ls.indices.firstNotNullOfOrNull { i ->
            if (!PERIOD.containsMatchIn(ls[i].words)) return@firstNotNullOfOrNull null
            val two = (found.filter { it.line == i || it.line == i + 1 }).mapNotNull { it.date }.distinct()
            if (two.size == 2) "${two.min()}/${two.max()}" else null
        }

        // Card and account: the last 4 digits only.
        val card = ls.firstNotNullOfOrNull { l ->
            val brand = CARD.find(l.words)?.value ?: return@firstNotNullOfOrNull null
            val last4 = LAST4.find(l.raw)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
            "${BRANDS[brand] ?: brand.replaceFirstChar { it.uppercase(Locale.ROOT) }} $last4"
        }
        val account = ls.indices.firstNotNullOfOrNull { i ->
            if (!ACCOUNT.containsMatchIn(ls[i].words)) return@firstNotNullOfOrNull null
            val digits = Regex("\\d[\\d -]{4,}\\d").find(ls[i].raw)?.value?.filter(Char::isDigit) ?: return@firstNotNullOfOrNull null // l10n: not text (a parser's pattern)
            digits.takeLast(4).takeIf { digits.length >= 6 }
        }

        // A tax slip's type and year.
        val slipInText = SLIP.find(fold(t))?.value?.let(::slipName)
        val modelSlip = reading?.slip?.readBy { s -> SLIP.find(fold(s))?.value?.let(::slipName) }
        val slip = slipInText ?: modelSlip?.takeIf { s -> SLIP.findAll(fold(t)).any { slipName(it.value) == s } }
        val year = ls.firstNotNullOfOrNull { l ->
            if (!YEAR.containsMatchIn(l.words)) return@firstNotNullOfOrNull null
            Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)").find(l.raw)?.value?.toInt() // l10n: not text (a parser's pattern)
        }?.takeIf { it <= today.year }

        val category = known?.category
        return FileFieldValues(merchant, title, date, total?.takeIf { it > 0 }, currency, taxes, due, period, account, card, expiry, slip, year, category,
            totalCheck, dateCheck, traps, rejected, returnBy = returnBy, warranty = warranty)
    }

    private fun same(a: Long, b: Long): Boolean = kotlin.math.abs(a - b) <= SAME_CENTS

    /**
     * A date on a line [label] marks (or the line after a label on its own): the text's. The model's [model] is kept only
     * when it is that date, or, with no such line, when the text holds it; otherwise none (Robot checks, never guesses).
     */
    private fun labelledDate(ls: List<Line>, found: List<DateFound>, label: Regex, model: LocalDate?, except: Regex? = null, reject: () -> Unit): LocalDate? {
        fun marks(i: Int) = label.containsMatchIn(ls[i].words) && (except == null || !except.containsMatchIn(ls[i].words))
        val near = found.filter { d -> marks(d.line) || (d.line > 0 && marks(d.line - 1) && ls[d.line - 1].amounts.isEmpty()) }
        if (near.isNotEmpty()) {
            val mine = near.first().date
            if (model != null && model != mine) { reject(); return null }
            return mine
        }
        if (model == null) return null
        if (found.any { it.date == model }) return model
        reject()
        return null
    }

    /** Taxes by canonical name from the text's tax lines: the line's amount that isn't a rate (TPS 5 % 4,00 → 4.00). */
    private fun taxes(ls: List<Line>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (l in ls) {
            if (l.kind != LineKind.TAX) continue
            val name = TAX_NAMES.firstOrNull { it.first.containsMatchIn(l.words) }?.second ?: continue
            val money = l.amounts.filter { !it.rate }
            // A line with two amounts (a base and a tax, "TPS sur 80,00 4,00") gives the last.
            val cents = money.lastOrNull()?.cents ?: continue
            if (name !in out) out[name] = cents
        }
        return out
    }

    private val LAST4 = Regex("(?:[*xX•#]{2,}[ -]?)+(\\d{4})(?!\\d)") // l10n: not text (a parser's pattern)
    private val BRANDS = mapOf("visa" to "Visa", "mastercard" to "Mastercard", "master card" to "Mastercard", "mc" to "Mastercard", "amex" to "Amex", // l10n: not text (brand names)
        "american express" to "Amex", "interac" to "Interac", "debit" to "Debit", "discover" to "Discover") // l10n: not text (brand names)

    private val SLIP = Regex("(?<![\\p{L}\\d])(t4a|t4e|t4rsp|t4rif|t4|t5008|t5|t3|t2202|rl-?\\d{1,2}|releve \\d{1,2})(?![\\p{L}\\d])") // l10n: not text (a parser's pattern)

    /** "rl1", "releve 1" → "RL-1"; "t4a" → "T4A". */
    private fun slipName(s: String): String {
        val f = s.replace(" ", "")
        val rl = Regex("^(?:rl-?|releve)(\\d{1,2})$").find(f) // l10n: not text (a parser's pattern)
        return if (rl != null) "RL-${rl.groupValues[1]}" else f.uppercase(Locale.ROOT) // l10n: not text (a slip's name)
    }
}

/**
 * Robot's own small table of merchants (§7.1): a name found in a file's text gives the merchant (as Robot writes it) and
 * its spending category. Your rules (FileRules) override it. Names are matched as whole words, folded.
 */
object MerchantTable {
    data class Merchant(val name: String, val category: String)

    /** The categories (§7.1), as fields store them. */
    val CATEGORIES = listOf("groceries", "fuel", "restaurants", "pharmacy", "hardware", "clothing", "electronics", "transit", "utilities", "other") // l10n: not text (field values)

    private val TABLE: List<Pair<List<String>, Merchant>> = listOf(
        listOf("costco") to Merchant("Costco", "groceries"), listOf("iga") to Merchant("IGA", "groceries"), // l10n: not text (merchant names)
        listOf("metro") to Merchant("Metro", "groceries"), listOf("maxi") to Merchant("Maxi", "groceries"), // l10n: not text (merchant names)
        listOf("provigo") to Merchant("Provigo", "groceries"), listOf("super c") to Merchant("Super C", "groceries"), // l10n: not text (merchant names)
        listOf("loblaws") to Merchant("Loblaws", "groceries"), listOf("sobeys") to Merchant("Sobeys", "groceries"), // l10n: not text (merchant names)
        listOf("no frills") to Merchant("No Frills", "groceries"), listOf("walmart") to Merchant("Walmart", "groceries"), // l10n: not text (merchant names)
        listOf("esso") to Merchant("Esso", "fuel"), listOf("shell") to Merchant("Shell", "fuel"), listOf("petro-canada", "petro canada") to Merchant("Petro-Canada", "fuel"), // l10n: not text (merchant names)
        listOf("ultramar") to Merchant("Ultramar", "fuel"), listOf("irving") to Merchant("Irving", "fuel"), listOf("couche-tard", "couche tard") to Merchant("Couche-Tard", "fuel"), // l10n: not text (merchant names)
        listOf("jean coutu") to Merchant("Jean Coutu", "pharmacy"), listOf("pharmaprix") to Merchant("Pharmaprix", "pharmacy"), // l10n: not text (merchant names)
        listOf("shoppers drug mart") to Merchant("Shoppers Drug Mart", "pharmacy"), listOf("uniprix") to Merchant("Uniprix", "pharmacy"), // l10n: not text (merchant names)
        listOf("familiprix") to Merchant("Familiprix", "pharmacy"), listOf("proxim") to Merchant("Proxim", "pharmacy"), // l10n: not text (merchant names)
        listOf("canadian tire") to Merchant("Canadian Tire", "hardware"), listOf("home depot") to Merchant("Home Depot", "hardware"), // l10n: not text (merchant names)
        listOf("rona") to Merchant("Rona", "hardware"), listOf("reno-depot", "reno depot") to Merchant("Réno-Dépôt", "hardware"), // l10n: not text (merchant names)
        listOf("bmr") to Merchant("BMR", "hardware"), listOf("tim hortons") to Merchant("Tim Hortons", "restaurants"), // l10n: not text (merchant names)
        listOf("mcdonald's", "mcdonalds") to Merchant("McDonald's", "restaurants"), listOf("starbucks") to Merchant("Starbucks", "restaurants"), // l10n: not text (merchant names)
        listOf("best buy") to Merchant("Best Buy", "electronics"), listOf("bureau en gros", "staples") to Merchant("Staples", "electronics"), // l10n: not text (merchant names)
        listOf("hydro-quebec", "hydro quebec") to Merchant("Hydro-Québec", "utilities"), listOf("hydro one") to Merchant("Hydro One", "utilities"), // l10n: not text (merchant names)
        listOf("energir") to Merchant("Énergir", "utilities"), listOf("videotron") to Merchant("Vidéotron", "utilities"), // l10n: not text (merchant names)
        listOf("bell canada") to Merchant("Bell", "utilities"), listOf("rogers") to Merchant("Rogers", "utilities"), // l10n: not text (merchant names)
        listOf("telus") to Merchant("Telus", "utilities"), listOf("fido") to Merchant("Fido", "utilities"), // l10n: not text (merchant names)
        listOf("stm") to Merchant("STM", "transit"), listOf("oc transpo") to Merchant("OC Transpo", "transit"), // l10n: not text (merchant names)
        listOf("presto") to Merchant("Presto", "transit"), // l10n: not text (merchant names; never "TTC": « toutes taxes comprises »)
        listOf("winners") to Merchant("Winners", "clothing"), listOf("simons") to Merchant("Simons", "clothing"), listOf("uniqlo") to Merchant("Uniqlo", "clothing"), // l10n: not text (merchant names)
    )
    private val PATTERNS = TABLE.map { (words, m) -> Regex("(?<![\\p{L}\\d])(" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\d])") to m } // l10n: not text (a parser's pattern)

    /** The first known merchant in [text] (the one nearest its top), or null. */
    fun find(text: String): Merchant? {
        val f = FileFields.fold(text)
        return PATTERNS.mapNotNull { (r, m) -> r.find(f)?.let { it.range.first to m } }.minByOrNull { it.first }?.second
    }

    /** Robot's category for [merchant] (a name a rule or the model gave), or null. */
    fun categoryOf(merchant: String): String? = find(merchant)?.category
}
