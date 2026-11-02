// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.WhenParser
import app.heyrobot.core.i18n.Lang
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// Pure. The everyday set's sums (docs/plans/everyday-set-plan.md §4.3, D6): "what's an 18% tip on $64.50", "350 °F in
// Celsius", « combien font 12 fois 7 », taken only when every word is a number, an operator, a unit, money, a percentage,
// a date or a fixed lead-in, and worked out by code (Sums). Any other word means it isn't a sum Robot should take, and the
// question goes on as before: that closed vocabulary is what keeps dose questions out ("ibuprofen", "tablets", "child",
// « comprimés » aren't sum words, and milligrams aren't a unit here, §4.6). The panel asks it after MusicRouter (§2.1, D1).
// A command never shows its numbers in a log ([toString]: its kind only).

/** What a sum is, for the voice log (VoiceEvent.SUMS's a). */
enum class SumKind(override val id: Int, override val title: String) : app.heyrobot.core.LogCode {
    ARITHMETIC(1, "arithmetic"), PERCENT(2, "a percentage"), TIP(3, "a tip"), TAX(4, "tax"), SPLIT(5, "a split"), UNIT(6, "a unit"), DATE(7, "a date")
}

/** A tax named with an amount: its name in the reply, and its rate when Robot knows it without asking. */
enum class TaxName(val id: Int) {
    /** Harmonized sales tax: 13%, 14% or 15% by province ([Sums.HST_RATES]); Robot asks which. */
    HST(1),
    /** The federal GST alone: 5% (canada.ca, read 23 Oct 2026). */
    GST(2),
    /** Québec's QST, and a province's PST: Robot holds no rate for them (UNVERIFIED on their official pages), so it asks. */
    QST(3),
    PST(4),
    /** "tax", « taxes »: which one isn't said. */
    TAX(5),
}

/** What [SumsRouter] heard, worked out by [Sums]. */
sealed interface SumsCommand {
    /** The language the phrase was heard in, for the reply and its decimal mark. */
    val lang: Lang
    val kind: SumKind

    /** "12 times 7", "the square root of 2", "$45 times 3". */
    data class Arith(val expr: Expr, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.ARITHMETIC
        override fun toString() = "Arith($lang)" // l10n: not text (log only: never a sum's numbers)
    }

    /** [p] percent of [x] ("18% of 64.50"). */
    data class PercentOf(val p: BigDecimal, val x: BigDecimal, val money: Boolean, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.PERCENT
        override fun toString() = "PercentOf($lang)" // l10n: not text (log only)
    }

    /** [a] as a percentage of [b] ("what percent is 12 of 80"). */
    data class WhatPercent(val a: BigDecimal, val b: BigDecimal, val money: Boolean, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.PERCENT
        override fun toString() = "WhatPercent($lang)" // l10n: not text (log only)
    }

    /** [x] plus or minus [p] percent of it ("64.50 plus 13%"); [off]: a discount ("15% off $80"), which says what you save. */
    data class AddPercent(val x: BigDecimal, val p: BigDecimal, val minus: Boolean, val off: Boolean, val money: Boolean, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.PERCENT
        override fun toString() = "AddPercent($lang)" // l10n: not text (log only)
    }

    /** A [p] percent tip on [x] dollars. */
    data class Tip(val p: BigDecimal, val x: BigDecimal, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.TIP
        override fun toString() = "Tip($lang)" // l10n: not text (log only)
    }

    /** [x] dollars plus [tax] at [p] percent; [p] null: not said, so Robot asks which rate. */
    data class Tax(val x: BigDecimal, val p: BigDecimal?, val tax: TaxName, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.TAX
        override fun toString() = "Tax($tax, ${if (p == null) "no rate" else "rate"}, $lang)" // l10n: not text (log only)
    }

    /** [x] split [n] ways, [money] in dollars (a leftover cent to the first shares). */
    data class Split(val x: BigDecimal, val n: Int, val money: Boolean, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.SPLIT
        override fun toString() = "Split($lang)" // l10n: not text (log only)
    }

    /** [v] [from] in [to]; [weighed]: a volume asked as a weight or back ("2 cups of flour in grams"), which code can't do. */
    data class Convert(val v: BigDecimal, val from: Unit, val to: Unit, override val lang: Lang, val weighed: Boolean = false) : SumsCommand {
        override val kind get() = SumKind.UNIT
        override fun toString() = "Convert(${from.dim}, $lang)" // l10n: not text (log only)
    }

    /** Days (or weeks, [weeks]) from [today] to [date]; [since]: counted back to a day past. */
    data class Days(val today: LocalDate, val date: LocalDate, val weeks: Boolean, val since: Boolean, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.DATE
        override fun toString() = "Days($lang)" // l10n: not text (log only)
    }

    /** The day of the week of [date] ("what day of the week is Christmas"). */
    data class Weekday(val today: LocalDate, val date: LocalDate, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.DATE
        override fun toString() = "Weekday($lang)" // l10n: not text (log only)
    }

    /** The date [n] days (or weeks) after [today] ("what's the date in 3 weeks"). */
    data class DateIn(val today: LocalDate, val n: Int, val weeks: Boolean, override val lang: Lang) : SumsCommand {
        override val kind get() = SumKind.DATE
        override fun toString() = "DateIn($lang)" // l10n: not text (log only)
    }
}

object SumsRouter {
    /** The most shares a split takes, and the most days or weeks counted ahead. */
    const val MAX_SHARES = 100
    const val MAX_AHEAD = 10_000

    /** Lead-ins taken off the front (and "please" off the end): they say a sum is asked, and nothing else. */
    private val LEADS: List<List<String>> = listOf("whats", "what is", "what are", "how much is", "how much are", "calculate", "compute",
        "convert", "please", "combien font", "combien fait", "combien ca fait", "ca fait combien", "c est combien", "c est quoi", "calcule", "calcule moi",
        "convertis", "convertir", "convertis moi", "combien c est", "c est", "ca fait", "ca donne", "ca donne combien", "combien ca donne", "dis moi", "il y a", "y a",
        "peux tu calculer", "peux tu me calculer", "tu peux calculer", "tu peux me calculer", "peux tu me dire", "peux tu me dire combien fait", "coudonc", "eille", "heille")
        .map { it.split(' ') }.sortedByDescending { it.size }
    private val TAILS: List<List<String>> = listOf("please", "s il te plait", "s il vous plait", "svp", "stp", "merci", "combien", "ca fait combien", "ca donne combien",
        "c est combien", "ca fait quoi", "ca donne quoi", "c est quoi", "ca fait combien chacun", "ca donne combien chacun", "c est combien chacun")
        .map { it.split(' ') }.sortedByDescending { it.size }

    /** The question in the middle: « 5 milles, ça fait combien de kilomètres » and « 350 °F, c'est combien en Celsius » read as « 5 milles en kilomètres ». */
    private val ASKED_IN_THE_MIDDLE = Regex("(?<=\\S) (?:ca fait|ca donne|c est) combien (?:de|d|en) (?=\\S)")

    /**
     * [text] as a sum, or null when any word isn't a sum's (the question goes on to the routers after it, then the model).
     * [now]: what dates are counted from.
     */
    fun route(text: String, now: ZonedDateTime = ZonedDateTime.now()): SumsCommand? {
        val all = SumWords.atoms(text) ?: return null
        // Atoms have no accents, so « à » counts as English's "a" there: said with its accent, it's French (« 64 $ séparé à 4 »).
        val lang = if (Regex("(?<!\\p{L})à(?!\\p{L})").containsMatchIn(text)) Lang.FR else SumWords.lang(all)
        var a = all
        var cut = true
        while (cut) {
            cut = false
            LEADS.firstOrNull { l -> a.size > l.size && a.subList(0, l.size) == l }?.let { a = a.drop(it.size); cut = true }
            TAILS.firstOrNull { l -> a.size > l.size && a.subList(a.size - l.size, a.size) == l }?.let { a = a.dropLast(it.size); cut = true }
        }
        if (a.isEmpty()) return null
        a = a.joinToString(" ").replace(ASKED_IN_THE_MIDDLE, " en ").split(' ')
        dates(a, now, lang)?.let { return it }
        val t = SumWords.tokens(a, lang) ?: return null
        return tip(t, lang) ?: tax(t, lang) ?: taxOn(t, lang) ?: off(t, lang) ?: percentOf(t, lang) ?: whatPercent(t, lang) ?: addPercent(t, lang) ?: split(t, lang) ?:
            convert(t, lang) ?: if (notASum(text, all, t)) null else arithmetic(t, lang)
    }

    /**
     * E10: "7-11", "9/11", "24/7" and "555-0123" are names, dates and numbers more often than sums. Digits stuck to a dash or
     * a slash, with no other operator, count only when you asked for a sum in words; a phone number's or a date's shape never does.
     */
    private fun notASum(text: String, all: List<String>, t: List<Tok>): Boolean {
        val stuck = STUCK.findAll(text).count()
        if (stuck == 0) return false
        if (NOT_A_SUM_SHAPE.containsMatchIn(text)) return true
        return t.count { it is Tok.W } == stuck && SUM_WORDS.none { it in all } && !all.windowed(2).contains(listOf("how", "much"))
    }

    private val STUCK = Regex("(?<=\\d)[-–—/](?=\\d)")
    private val NOT_A_SUM_SHAPE = Regex("(?<!\\d)(?:\\d{3}-\\d{4}|\\d{3}-\\d{3}-\\d{4}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}/\\d{1,2}/\\d{2,4})(?!\\d)")
    private val SUM_WORDS = setOf("calculate", "compute", "calcule", "calculer", "combien")

    // ------------------------------------------------------------------ tokens

    private fun Tok?.w(vararg ws: String): Boolean = this is Tok.W && w in ws
    /** A number that isn't a percentage. */
    private val Tok?.num: Tok.N? get() = (this as? Tok.N)?.takeIf { !it.pct }
    /** A percentage. */
    private val Tok?.pct: Tok.N? get() = (this as? Tok.N)?.takeIf { it.pct && !it.money }
    private fun List<Tok>.at(i: Int): Tok? = getOrNull(i)

    // ------------------------------------------------------------------ tips, tax, percentages

    /** "an 18% tip on $64.50", "18% tip on 64.50", "tip of 18% on $64.50", "tip on $64.50 at 18%"; « un pourboire de 18 % sur 64,50 $ ». */
    private fun tip(t0: List<Tok>, lang: Lang): SumsCommand? {
        val t1 = if (t0.firstOrNull().w("a", "an", "un", "the", "le")) t0.drop(1) else t0
        // « combien de pourboire sur 64 $ à 18 % », « combien je laisse de pourboire », and « c'est combien de pourboire » once its lead is gone.
        val asked = TIP_ASKED.firstOrNull { q -> q.indices.all { t1.at(it).w(q[it]) } && t1.at(q.size).w("pourboire", "tip") }
        val t = if (asked != null) t1.drop(asked.size) else t1
        fun ok(p: Tok.N?, x: Tok.N?) = if (p != null && x != null) SumsCommand.Tip(p.v, x.v, lang) else null
        return when {
            t.size == 4 && t[1].w("tip") && t[2].w("on", "for", "of") -> ok(t[0].pct, t[3].num)
            t.size == 3 && t[1].w("tip") -> ok(t[0].pct, t[2].num)
            t.size == 5 && t[0].w("tip") && t[1].w("of") && t[3].w("on", "for") -> ok(t[2].pct, t[4].num)
            t.size == 5 && t[0].w("tip") && t[1].w("on", "for") && t[3].w("at", "of") -> ok(t[4].pct, t[2].num)
            // Québec also says « tip »: « un tip de 15 % sur 40 $ », « 15 % de tip sur 40 $ ».
            t.size == 5 && t[0].w("pourboire", "tip") && t[1].w("de", "d") && t[3].w("sur", "pour") -> ok(t[2].pct, t[4].num)
            t.size == 5 && t[1].w("de", "d") && t[2].w("pourboire", "tip") && t[3].w("sur", "pour") -> ok(t[0].pct, t[4].num)
            t.size == 5 && t[0].w("pourboire", "tip") && t[1].w("sur", "pour") && t[3].w("a", "de") -> ok(t[4].pct, t[2].num)
            else -> null
        }
    }

    private val TIP_ASKED = listOf("combien je laisse de", "combien je donne de", "combien de", "de").map { it.split(' ') }

    private val TAXES = mapOf("hst" to TaxName.HST, "tvh" to TaxName.HST, "gst" to TaxName.GST, "tps" to TaxName.GST, "qst" to TaxName.QST,
        "tvq" to TaxName.QST, "pst" to TaxName.PST, "tvp" to TaxName.PST, "tax" to TaxName.TAX, "taxes" to TaxName.TAX, "taxe" to TaxName.TAX)

    /** "$80 plus HST", "$80 with 15% tax", "$80 plus 13% HST"; « 80 $ plus la TVH », « 80 $ avec 15 % de taxes ». */
    private fun tax(t: List<Tok>, lang: Lang): SumsCommand? {
        val x = t.at(0).num ?: return null
        if (!t.at(1).w("plus", "with", "and", "+", "avec", "et")) return null
        var i = 2
        val p = t.at(i).pct?.also { i++ }
        if (t.at(i).w("de", "d", "of")) i++
        if (t.at(i).w("la", "les", "the", "l")) i++
        val name = (t.at(i) as? Tok.W)?.w?.let { TAXES[it] } ?: return null
        i++
        // "HST tax" is HST.
        if (t.at(i).w("tax") && name != TaxName.TAX) i++
        val after = if (p == null && t.at(i).w("at", "a", "de")) t.at(i + 1).pct?.also { i += 2 } else null
        if (i != t.size) return null
        val rate = p ?: after
        return SumsCommand.Tax(x.v, rate?.v ?: if (name == TaxName.GST) BigDecimal(5) else null, name, lang)
    }

    /** « la TPS sur 80 $ », « les taxes sur 80 $ », « 15 % de taxes sur 80 $ »: without a rate Robot asks, as with « 80 $ plus la TVH ». */
    private fun taxOn(t: List<Tok>, lang: Lang): SumsCommand? {
        var i = 0
        val p = t.at(0).pct
        if (p != null) {
            if (!t.at(1).w("de", "d")) return null
            i = 2
        } else if (t.at(0).w("la", "les", "l", "de", "d")) i = 1
        val name = (t.at(i) as? Tok.W)?.w?.let { TAXES[it] } ?: return null
        if (!t.at(i + 1).w("sur") || i + 3 != t.size) return null
        val x = t.at(i + 2).num ?: return null
        return SumsCommand.Tax(x.v, p?.v ?: if (name == TaxName.GST) BigDecimal(5) else null, name, lang)
    }

    /** "15% off $80", "a 15% discount on $80", "$80 with 15% off"; « 15 % de rabais sur 80 $ », « un rabais de 15 % sur 80 $ ». */
    private fun off(t0: List<Tok>, lang: Lang): SumsCommand? {
        val t = if (t0.firstOrNull().w("a", "an", "un")) t0.drop(1) else t0
        fun ok(p: Tok.N?, x: Tok.N?) = if (p != null && x != null) SumsCommand.AddPercent(x.v, p.v, minus = true, off = true, money = x.money, lang = lang) else null
        val rabais = arrayOf("rabais", "reduction", "escompte", "remise", "solde")
        return when {
            t.size == 3 && t[1].w("off") -> ok(t[0].pct, t[2].num)
            t.size == 4 && t[1].w("off", "discount") && t[2].w("on", "of") -> ok(t[0].pct, t[3].num)
            t.size == 4 && t[1].w("with") && t[3].w("off") -> ok(t[2].pct, t[0].num)
            t.size == 5 && t[1].w("de", "d") && t[2].w(*rabais) && t[3].w("sur") -> ok(t[0].pct, t[4].num)
            t.size == 5 && t[0].w(*rabais) && t[1].w("de", "d") && t[3].w("sur") -> ok(t[2].pct, t[4].num)
            // « 80 $ avec 20 % de rabais », « 80 $ à 20 % d'escompte ».
            t.size == 5 && t[1].w("avec", "a") && t[3].w("de", "d") && t[4].w(*rabais) -> ok(t[2].pct, t[0].num)
            else -> null
        }
    }

    /** "18% of 64.50"; « 18 % de 64,50 ». */
    private fun percentOf(t: List<Tok>, lang: Lang): SumsCommand? {
        if (t.size != 3 || !t[1].w("of", "de", "du", "d")) return null
        val p = t[0].pct ?: return null
        val x = t[2].num ?: return null
        return SumsCommand.PercentOf(p.v, x.v, x.money, lang)
    }

    /**
     * "what percent is 12 of 80", "12 is what percent of 80", "12 out of 80 as a percentage"; « 12, c'est quel pourcentage de
     * 80 », « quel pourcentage de 80 fait 12 », « 12 sur 80 en pourcentage ».
     */
    private fun whatPercent(t: List<Tok>, lang: Lang): SumsCommand? {
        val pc = arrayOf("%", "percentage", "pourcentage")
        fun ok(a: Tok.N?, b: Tok.N?) = if (a != null && b != null) SumsCommand.WhatPercent(a.v, b.v, a.money && b.money, lang) else null
        return when {
            t.size == 6 && t[0].w("what") && t[1].w(*pc) && t[2].w("is") && t[4].w("of") -> ok(t[3].num, t[5].num)
            t.size == 7 && t[0].w("what") && t[1].w(*pc) && t[2].w("is") && t[4].w("out") && t[5].w("of") -> ok(t[3].num, t[6].num)
            t.size == 6 && t[1].w("is") && t[2].w("what") && t[3].w(*pc) && t[4].w("of") -> ok(t[0].num, t[5].num)
            t.size == 7 && t[1].w("out") && t[2].w("of") && t[4].w("as", "in") && t[5].w("a") && t[6].w(*pc) -> ok(t[0].num, t[3].num)
            t.size == 6 && t[1].w("out") && t[2].w("of") && t[4].w("in") && t[5].w(*pc) -> ok(t[0].num, t[3].num)
            t.size == 7 && t[1].w("c") && t[2].w("est") && t[3].w("quel") && t[4].w(*pc) && t[5].w("de", "d") -> ok(t[0].num, t[6].num)
            t.size == 6 && t[0].w("quel") && t[1].w(*pc) && t[2].w("de", "d") && t[4].w("fait", "font", "est", "represente") -> ok(t[5].num, t[3].num)
            t.size == 5 && t[1].w("sur") && t[3].w("en") && t[4].w(*pc) -> ok(t[0].num, t[2].num)
            else -> null
        }
    }

    /** "64.50 plus 13%", "$80 minus 15%"; « 64,50 plus 13 % », « 80 $ moins 15 % ». */
    private fun addPercent(t: List<Tok>, lang: Lang): SumsCommand? {
        if (t.size != 3) return null
        val x = t[0].num ?: return null
        val p = t[2].pct ?: return null
        return when {
            t[1].w("plus", "+", "and") -> SumsCommand.AddPercent(x.v, p.v, minus = false, off = false, money = x.money, lang = lang)
            t[1].w("minus", "-", "less", "moins") -> SumsCommand.AddPercent(x.v, p.v, minus = true, off = false, money = x.money, lang = lang)
            else -> null
        }
    }

    /**
     * "$120 split three ways", "split $100 3 ways", "split $100 between 4 people", "$100 divided between 4"; « 120 $ divisé en
     * 3 », « sépare 100 $ en 4 », « 100 $ partagé entre 4 personnes ».
     */
    private fun split(t0: List<Tok>, lang: Lang): SumsCommand? {
        val t = t0.toMutableList()
        val personnes = t.lastOrNull().w("personnes", "parts", "chacun")
        if (t.lastOrNull().w("people", "persons", "personnes", "parts", "each", "chacun")) t.removeAt(t.size - 1)
        val verb = arrayOf("split", "divided", "shared", "divise", "separe", "partage", "divisee", "separee", "partagee", "splitte")
        val way = arrayOf("ways", "way")
        val into = arrayOf("between", "among", "en", "entre", "in", "into")
        fun ok(x: Tok.N?, n: Tok.N?): SumsCommand? {
            if (x == null || n == null || n.money) return null
            val k = try { n.v.stripTrailingZeros().intValueExact() } catch (_: ArithmeticException) { return null }
            if (k < 2 || k > MAX_SHARES) return null
            return SumsCommand.Split(x.v, k, x.money, lang)
        }
        return when {
            t.size == 4 && t[1].w(*verb) && t[3].w(*way) -> ok(t[0].num, t[2].num)
            t.size == 4 && t[0].w(*verb) && t[3].w(*way) -> ok(t[1].num, t[2].num)
            t.size == 4 && t[0].w(*verb) && t[2].w(*into) -> ok(t[1].num, t[3].num)
            // "$100 divided by 4" is a division (arithmetic); "$100 split between 4" or « divisé en 4 » a split.
            t.size == 4 && t[1].w(*verb) && t[2].w(*into) -> ok(t[0].num, t[3].num)
            // « 64 $ entre 4 », « 64 $ pour 4 personnes », « 64 $ divisé par 4 personnes », and Québec's « séparé à 4 », « séparé par 4 ».
            t.size == 3 && t[1].w("entre") -> ok(t[0].num, t[2].num)
            t.size == 3 && t[1].w("pour") && personnes -> ok(t[0].num, t[2].num)
            t.size == 4 && t[1].w("divise", "divisee") && t[2].w("par") && personnes -> ok(t[0].num, t[3].num)
            t.size == 4 && t[1].w("separe", "separee", "partage", "partagee", "divise", "divisee", "splitte") && t[2].w("a") -> ok(t[0].num, t[3].num)
            t.size == 4 && t[1].w("separe", "separee", "partage", "partagee") && t[2].w("par") -> ok(t[0].num, t[3].num)
            else -> null
        }
    }

    // ------------------------------------------------------------------ units

    /** Ingredients a volume of which someone may ask as a weight: the only words Sums takes in "2 cups of flour in grams". */
    private val INGREDIENTS = setOf("flour", "sugar", "brown", "icing", "butter", "rice", "oats", "milk", "water", "oil", "salt", "honey", "cocoa",
        "cheese", "farine", "sucre", "beurre", "riz", "avoine", "lait", "eau", "huile", "sel", "miel", "cacao", "fromage", "brun", "glace")

    private val IN = arrayOf("in", "to", "into", "en", "dans")

    /**
     * "350 °F in Celsius", "5 miles to km", "convert 5 miles to km", "how many cups in a litre", "how many ounces are there in a
     * pound", "60 mph in km/h", "350 degrees in Celsius" (degrees alone: the other scale); « 5 milles en kilomètres »,
     * « combien de tasses dans un litre ». A volume as a weight ("2 cups of flour in grams") is taken to say why not.
     */
    private fun convert(t0: List<Tok>, lang: Lang): SumsCommand? {
        var t = t0
        // "how many cups (are there) in a litre", « combien de tasses (y a-t-il) dans un litre ».
        val howMany = when {
            t.size >= 4 && t[0].w("how") && t[1].w("many", "much") -> 2
            t.size >= 4 && t[0].w("combien") && t[1].w("de", "d") -> 2
            // « ça fait combien de tasses dans un litre », once its lead is gone.
            t.size >= 4 && t[0].w("de", "d") && t[1] is Tok.U -> 1
            else -> 0
        }
        if (howMany > 0) {
            val to = (t.at(howMany) as? Tok.U)?.unit ?: return null
            var i = howMany + 1
            if (t.at(i).w("are") && t.at(i + 1).w("there")) i += 2
            if (t.at(i).w("y") && t.at(i + 1).w("a") && t.at(i + 2).w("t") && t.at(i + 3).w("il")) i += 4
            if (t.at(i).w("il") && t.at(i + 1).w("y") && t.at(i + 2).w("a")) i += 3
            // « combien de kilomètres ça fait 5 milles ».
            if (t.at(i).w("ca") && t.at(i + 1).w("fait")) i += 2
            else if (t.at(i).w("in", "per", "dans", "en", "pour")) i++
            else return null
            val (v, used) = amount(t, i) ?: return null
            val from = (t.at(i + used) as? Tok.U)?.unit ?: return null
            if (i + used + 1 != t.size) return null
            return conversion(v, from, to, lang)
        }
        // "[a] N unit (of ingredient)? in unit".
        val (v, used) = amount(t, 0) ?: return null
        t = t.drop(used)
        var from: Unit? = (t.at(0) as? Tok.U)?.unit
        val degrees = from == null && t.at(0).w("degrees", "degree", "degres", "degre")
        if (from == null && !degrees) return null
        var i = 1
        var weighed = false
        if (from != null && from.dim == Dimension.VOLUME && t.at(i).w("of", "de", "d")) {
            var k = i + 1
            while (k < t.size && (t[k] as? Tok.W)?.w in INGREDIENTS) k++
            if (k == i + 1) return null
            i = k
            weighed = true
        }
        if (!t.at(i).w(*IN)) return null
        i++
        if (t.at(i).w("degrees", "degres")) i++
        val to = (t.at(i) as? Tok.U)?.unit ?: return null
        if (i + 1 != t.size) return null
        if (degrees) from = when (to) {
            Unit.CELSIUS -> Unit.FAHRENHEIT
            Unit.FAHRENHEIT -> Unit.CELSIUS
            else -> return null
        }
        val f = from ?: return null
        if (weighed) return if (to.dim == Dimension.MASS) SumsCommand.Convert(v, f, to, lang, weighed = true) else null
        return conversion(v, f, to, lang)
    }

    private fun conversion(v: BigDecimal, from: Unit, to: Unit, lang: Lang): SumsCommand? = when {
        Units.compatible(from, to) -> SumsCommand.Convert(v, from, to, lang)
        from.dim == Dimension.VOLUME && to.dim == Dimension.MASS || from.dim == Dimension.MASS && to.dim == Dimension.VOLUME ->
            SumsCommand.Convert(v, from, to, lang, weighed = true)
        else -> null
    }

    /** An amount before a unit at [i]: a number, or "a", "an", « un », « une » (1), or none ("in a litre" names a litre); and how many tokens it took. */
    private fun amount(t: List<Tok>, i: Int): Pair<BigDecimal, Int>? {
        val n = t.at(i)
        return when {
            n is Tok.N && !n.pct && !n.money -> n.v to 1
            n.w("a", "an", "un", "une") -> BigDecimal.ONE to 1
            n is Tok.U -> BigDecimal.ONE to 0
            else -> null
        }
    }

    // ------------------------------------------------------------------ arithmetic

    /**
     * Numbers and operators only, at least one operator: "12 times 7", "15 plus 27 minus 4", "2 to the power of 10", "the
     * square root of 2", "5 squared", "$45 times 3"; « 12 fois 7 », « 350 divisé par 7 », « 2 à la puissance 10 », « la racine
     * carrée de 2 ». Usual precedence: powers, then times and divided, then plus and minus.
     */
    private fun arithmetic(t: List<Tok>, lang: Lang): SumsCommand? {
        // "a quarter of 120", "half of 80": a fraction of a number.
        val f = t.at(0).num
        if (t.size == 3 && t[1].w("of", "de") && f != null && !f.money && f.v > BigDecimal.ZERO && f.v < BigDecimal.ONE) {
            val x = t[2].num ?: return null
            return SumsCommand.Arith(Expr.Bin(Op.TIMES, Expr.Num(f.v), Expr.Num(x.v, x.money)), lang)
        }
        val p = Parser(t, lang)
        val e = p.sum() ?: return null
        if (p.i != t.size || !p.operated) return null
        return SumsCommand.Arith(e, lang)
    }

    /** A recursive reading of operators over tokens; [operated]: at least one operator was read. Nesting is capped. */
    private class Parser(val t: List<Tok>, val lang: Lang) {
        var i = 0
        var operated = false
        private var depth = 0

        private fun seq(vararg ws: String): Boolean = ws.indices.all { (t.getOrNull(i + it) as? Tok.W)?.w == ws[it] }
        private fun take(vararg ws: String): Boolean = seq(*ws).also { if (it) { i += ws.size; operated = true } }

        fun sum(): Expr? {
            var a = product() ?: return null
            while (true) {
                val op = when {
                    take("plus") || take("+") || (lang == Lang.FR && take("et")) -> Op.PLUS
                    take("minus") || take("-") || take("moins") || take("less") -> Op.MINUS
                    else -> return a
                }
                val b = product() ?: return null
                a = Expr.Bin(op, a, b)
            }
        }

        private fun product(): Expr? {
            var a = power() ?: return null
            while (true) {
                val op = when {
                    take("times") || take("x") || take("×") || take("*") || take("multiplied", "by") || take("fois") || take("multiplie", "par") -> Op.TIMES
                    take("divided", "by") || take("/") || take("÷") || take("over") || take("divise", "par") || take("sur") -> Op.DIVIDE
                    else -> return a
                }
                val b = power() ?: return null
                a = Expr.Bin(op, a, b)
            }
        }

        private fun power(): Expr? {
            val a0 = unary() ?: return null
            // E10: the sign is the base's, as said, and the line shows it: (-2)^2, never -2^2.
            val a = if (a0 is Expr.Neg) Expr.Group(a0) else a0
            return when {
                take("squared") || take("au", "carre") -> Expr.Bin(Op.POWER, a, Expr.Num(BigDecimal(2)))
                take("cubed") || take("au", "cube") -> Expr.Bin(Op.POWER, a, Expr.Num(BigDecimal(3)))
                take("to", "the", "power", "of") || take("to", "the", "power") || take("^") || take("a", "la", "puissance") || take("puissance") ||
                    take("exposant") -> {
                    if (++depth > 16) return null
                    Expr.Bin(Op.POWER, a, power().also { depth-- } ?: return null)
                }
                else -> a0
            }
        }

        private fun unary(): Expr? {
            if (++depth > 16) return null
            return unary0().also { depth-- }
        }

        private fun unary0(): Expr? {
            val before = operated
            return when {
                // E10: a sign alone isn't a sum ("minus 5").
                take("minus") || take("-") || take("negative") || take("moins") -> { operated = before; Expr.Neg(unary() ?: return null) }
                take("the", "square", "root", "of") || take("square", "root", "of") || take("sqrt") || take("√") || take("la", "racine", "carree", "de") ||
                    take("racine", "carree", "de") || take("la", "racine", "carree", "d") || take("racine", "carree", "d") -> Expr.Root(unary() ?: return null)
                else -> {
                    val n = t.getOrNull(i) as? Tok.N ?: return null
                    i++
                    if (n.pct) Expr.Pct(Expr.Num(n.v)) else Expr.Num(n.v, n.money)
                }
            }
        }
    }

    // ------------------------------------------------------------------ dates

    /**
     * "how many days until November 20", "how many weeks since June 1", "what day of the week is Christmas", "what's the date
     * in 3 weeks", "how long until Halloween"; « combien de jours avant le 20 novembre », « combien de semaines depuis le 1er
     * juin », « quel jour de la semaine tombe Noël », « quelle date dans 3 semaines ». The day must read as one through
     * WhenParser (When.kt untouched) or be one of [SumDates]' fixed holidays: "my dentist appointment" isn't, and goes on.
     */
    private fun dates(a: List<String>, now: ZonedDateTime, lang: Lang): SumsCommand? {
        val s = a.joinToString(" ")
        val today = now.toLocalDate()
        // atoms splits « 1er » as it does "5km"; WhenParser reads it whole.
        fun day(words: String, past: Boolean): LocalDate? = SumDates.read(words.replace(Regex("\\b1 er\\b"), "1er"), now, lang, past)
        fun weeks(w: String) = w == "weeks" || w == "semaines"
        Regex("^(?:how many |how much )?(days|weeks) (?:are there |is it |left )?(?:until|till|til|to|before) (.+)$").find(s)?.let { m ->
            return SumsCommand.Days(today, day(m.groupValues[2], false) ?: return null, weeks(m.groupValues[1]), since = false, lang = lang)
        }
        Regex("^how long (?:is it )?(?:until|till|til|before) (.+)$").find(s)?.let { m ->
            return SumsCommand.Days(today, day(m.groupValues[1], false) ?: return null, weeks = false, since = false, lang = lang)
        }
        Regex("^(?:how many )?(days|weeks) (?:has it been |have passed |is it )?(?:since|from) (.+?)(?: to today| until today| till today)?$").find(s)?.let { m ->
            return SumsCommand.Days(today, day(m.groupValues[2], true) ?: return null, weeks(m.groupValues[1]), since = true, lang = lang)
        }
        Regex("^what day (?:of the week )?(is|was|will be|does) (.+?)(?: fall on| land on| on)?$").find(s)?.let { m ->
            return SumsCommand.Weekday(today, day(m.groupValues[2], m.groupValues[1] == "was") ?: return null, lang)
        }
        Regex("^(?:the date |what date |what date is it |what day is it |what will the date be |what day will it be )?(?:in )?(.+) (days|weeks) (?:from now|from today)$")
            .find(s)?.let { m -> return dateIn(today, m.groupValues[1], weeks(m.groupValues[2]), lang) }
        Regex("^(?:the date|what date|what date is it|what day is it|what will the date be|what day will it be|what will be the date) in (.+) (days|weeks)$")
            .find(s)?.let { m -> return dateIn(today, m.groupValues[1], weeks(m.groupValues[2]), lang) }
        // French.
        Regex("^(?:(?:il )?reste )?(?:combien )?(?:de |d )?(jours|semaines|dodos) (?:reste t il |il reste |reste |y a t il |il y a |ca fait )?(?:avant|jusqu a|jusqu au|d ici|d ici a|d ici au) (.+)$")
            .find(s)?.let { m ->
                return SumsCommand.Days(today, day(m.groupValues[2], false) ?: return null, weeks(m.groupValues[1]), since = false, lang = lang)
            }
        Regex("^dans combien (?:de |d )?(jours|semaines) (?:est|c est|tombe|arrive|sera) (.+)$").find(s)?.let { m ->
            return SumsCommand.Days(today, day(m.groupValues[2], false) ?: return null, weeks(m.groupValues[1]), since = false, lang = lang)
        }
        // « Noël, c'est dans combien de jours ».
        Regex("^(.+?) (?:c est|est|tombe|arrive) dans combien (?:de |d )?(jours|semaines)$").find(s)?.let { m ->
            return SumsCommand.Days(today, day(m.groupValues[1], false) ?: return null, weeks(m.groupValues[2]), since = false, lang = lang)
        }
        Regex("^(?:combien )?(?:de |d )?(jours|semaines) (?:se sont ecoules |ca fait |sont passes )?depuis (.+)$").find(s)?.let { m ->
            return SumsCommand.Days(today, day(m.groupValues[2], true) ?: return null, weeks(m.groupValues[1]), since = true, lang = lang)
        }
        Regex("^quel jour (?:de la semaine )?(?:(tombe|est|sera|etait|c est|tombera) )?(.+?)$").find(s)?.let { m ->
            return SumsCommand.Weekday(today, day(m.groupValues[2], m.groupValues[1] == "etait") ?: return null, lang)
        }
        Regex("^(.+) (?:tombe|tombera|c est) quel jour(?: de la semaine)?$").find(s)?.let { m ->
            return SumsCommand.Weekday(today, day(m.groupValues[1], false) ?: return null, lang)
        }
        Regex("^(?:la date |quelle date |quelle date on sera |on sera quel jour |quel jour on sera |la date sera |quelle date on va etre |on va etre quel jour |quel jour on va etre |ca va etre quel jour )dans (.+) (jours|semaines)$").find(s)
            ?.let { m -> return dateIn(today, m.groupValues[1], weeks(m.groupValues[2]), lang) }
        // « dans 3 semaines, on va être quel jour ».
        Regex("^dans (.+) (jours|semaines) (?:on va etre|on sera|ca va etre|c est) (?:quel jour|quelle date)$").find(s)
            ?.let { m -> return dateIn(today, m.groupValues[1], weeks(m.groupValues[2]), lang) }
        return null
    }

    private fun dateIn(today: LocalDate, n: String, weeks: Boolean, lang: Lang): SumsCommand? {
        val k = if (n == "a" || n == "une" || n == "un") BigDecimal.ONE else {
            val t = SumWords.tokens(n.split(' '), lang) ?: return null
            (t.singleOrNull() as? Tok.N)?.takeIf { !it.pct && !it.money }?.v ?: return null
        }
        val count = try { k.stripTrailingZeros().intValueExact() } catch (_: ArithmeticException) { return null }
        if (count < 1 || count > MAX_AHEAD) return null
        return SumsCommand.DateIn(today, count, weeks, lang)
    }
}

/**
 * A day named in a sum's words: through WhenParser's public reading (When.kt unchanged, D6), or a fixed holiday it doesn't
 * know ("Christmas", « Noël », "Canada Day"). WhenParser puts a month and day already past in next year, and a weekday in the
 * coming week: [past] ("since June 1", "since Monday") takes the last one instead.
 */
object SumDates {
    private val HOLIDAYS: Map<String, Pair<Int, Int>> = buildMap {
        fun put(m: Int, d: Int, vararg names: String) = names.forEach { put(it, m to d) }
        put(12, 25, "christmas", "christmas day", "xmas", "noel", "jour de noel", "le jour de noel")
        put(12, 24, "christmas eve", "la veille de noel", "veille de noel")
        put(1, 1, "new years", "new years day", "new year", "jour de l an", "le jour de l an", "nouvel an", "le nouvel an")
        put(12, 31, "new years eve", "la veille du jour de l an", "veille du jour de l an")
        put(10, 31, "halloween", "l halloween")
        put(7, 1, "canada day", "la fete du canada", "fete du canada")
        put(2, 14, "valentines day", "valentines", "valentine day", "la saint valentin", "saint valentin", "la st valentin")
        put(11, 11, "remembrance day", "le jour du souvenir", "jour du souvenir")
        put(6, 24, "la saint jean", "la st jean", "saint jean baptiste day", "st jean baptiste day", "la saint jean baptiste", "la fete nationale",
            "fete nationale", "saint jean")
    }

    private val WEEKDAYS = Regex("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\\b")

    /** The day [words0] names, from [now], in [lang]'s words then the other's; null when they don't name one day. */
    fun read(words0: String, now: ZonedDateTime, lang: Lang, past: Boolean): LocalDate? {
        val today = now.toLocalDate()
        val words = words0.removePrefix("the ").trim()
        HOLIDAYS[words.removeSuffix(" this year").removeSuffix(" cette annee").trim()]?.let { (m, d) ->
            var date = LocalDate.of(today.year, m, d)
            if (!past && date < today) date = date.plusYears(1)
            if (past && date > today) date = date.minusYears(1)
            return date
        }
        val m = WhenParser.reads(words, now, lang) ?: return null
        if (!m.dated || m.clock || m.relative) return null
        var date = m.span.start.toLocalDate()
        val year = Regex("\\b\\d{4}\\b").containsMatchIn(words)
        if (past && date > today && !year) {
            date = if (WEEKDAYS.containsMatchIn(words) && ChronoUnit.DAYS.between(today, date) <= 7) date.minusWeeks(1) else date.minusYears(1)
        }
        return date
    }
}
