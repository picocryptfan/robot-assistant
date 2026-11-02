// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.i18n.Lang
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

// Pure. Sums' own words for numbers (docs/plans/everyday-set-plan.md §4.2, D6): digits as each language writes them ("1,500"
// is fifteen hundred in English, « 1,5 » one and a half in French, « 1 500 » fifteen hundred), and numbers said in words,
// "eighteen", "sixty-four fifty", "three and a half", "point five" / « dix-huit », « soixante-quatre et cinquante », « virgule
// cinq », « quatre-vingt-dix ». Then money ("$64.50", « 64,50 $ », "64 dollars 50"), percentages ("18%", "18 percent", « 18
// pour cent ») and units (Units.NAMES). When.kt's own number words stay private and untouched.

/** A piece of a sum as [SumWords] reads it. */
sealed interface Tok {
    /** A number: [money] in dollars, [pct] a percentage. */
    data class N(val v: BigDecimal, val money: Boolean = false, val pct: Boolean = false) : Tok
    /** A unit. */
    data class U(val unit: Unit) : Tok
    /** Any other word or sign, accents dropped and in lower case ("plus", "+", "of", « de »). */
    data class W(val w: String) : Tok
}

object SumWords {
    /** Text this long or longer is never a sum (a sum fits in a sentence). */
    const val MAX_CHARS = 160

    /** A number of more digits than this isn't something anyone adds up aloud. */
    const val MAX_DIGITS = 24

    /** [s] in lower case, without accents, curly quotes made plain: how every Sums pattern reads words. */
    fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace('’', '\'').replace('‘', '\'').replace(' ', ' ').replace(' ', ' ')

    /**
     * [text] as atoms: words, numbers as written, and the signs a sum uses each on its own ("$", "%", "+", "/", "°f"); French
     * elision split (« c'est » "c est", « d'Advil » "d advil"), English's apostrophe dropped ("what's" "whats"), the end's
     * punctuation gone. Null for a character no sum has ("401(k)" is fine: its letters make it no sum later).
     */
    fun atoms(text: String): List<String>? {
        if (text.length >= MAX_CHARS) return null
        var s = plain(text).trim()
        s = s.replace(Regex("[?!¿¡«»\"“”…;:]"), " ")
        s = s.replace(Regex("\\b(qu|[cdjlmnst])'(?=\\p{L})"), "$1 ").replace("'", "")
        s = s.replace("**", " ^ ").replace("−", "-").replace("–", "-").replace("—", "-")
        // A hyphen between letters joins a number's words (sixty-four, dix-huit); elsewhere it is a minus.
        s = s.replace(Regex("(?<=\\p{L})-(?=\\p{L})"), " ").replace("-", " - ")
        s = s.replace(Regex("([$%/^*×÷+()√=x])")) { m -> if (m.value == "x") m.value else " ${m.value} " }
        // Digits stuck to a unit or a sign ("5km", "350°f", "60mph", "7x8"); a letter then digits stays one word ("p3", "5g" is 5 g).
        s = s.replace(Regex("(?<=\\d)(?=°)"), " ").replace(Regex("(?<=\\d)(?=[a-z])"), " ").replace(Regex("(?<=\\d x)(?=\\d)"), " ")
        // A sentence's own full stop or comma ("…of 80." or "…, please"), never a decimal mark between digits.
        s = s.replace(Regex("(?<!\\d)[.,]|[.,](?!\\d)"), " ")
        val out = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return out.takeIf { it.isNotEmpty() }
    }

    private val FR_ONLY = setOf("combien", "font", "fait", "fois", "divise", "multiplie", "moins", "pourboire", "taxe", "taxes", "tvh", "tps", "tvq", "en",
        "dans", "de", "des", "du", "la", "le", "les", "c", "est", "quel", "quelle", "jours", "semaines", "avant", "depuis", "jusqu", "tasses", "tasse",
        "convertis", "convertir", "calcule", "sur", "avec", "pour", "virgule", "et", "demi", "demie", "puissance", "racine", "carree", "carre", "cube",
        "separe", "partage", "chacun", "entre", "parts", "rabais", "pourcentage", "quoi", "tombe", "semaine", "jour", "date", "d", "l", "ca",
        "milles", "livres", "pieds", "pouces", "degres", "kilometres", "metres", "litre", "onces", "heures", "piasse", "piasses", "piastre", "piastres", "cenne", "cennes",
        "donne", "reste", "dodos", "escompte", "remise")
    private val EN_ONLY = setOf("what", "whats", "how", "many", "much", "is", "of", "in", "to", "times", "divided", "by", "multiplied", "minus", "tip", "with",
        "split", "ways", "until", "since", "days", "weeks", "the", "a", "an", "convert", "calculate", "percent", "off", "and", "power", "square", "root",
        "squared", "cubed", "into", "on", "for", "between", "each", "people", "there", "are", "tax", "plus", "miles", "pounds", "feet", "inches",
        "degrees", "kilometers", "meters", "liters", "ounces", "hours", "week", "day", "date", "does", "fall", "from", "now", "today")

    /** The language [atoms] are in, by their words: French when more of them are French only; English otherwise. */
    fun lang(atoms: List<String>): Lang {
        val fr = atoms.count { it in FR_ONLY }
        val en = atoms.count { it in EN_ONLY }
        return if (fr > en) Lang.FR else Lang.EN
    }

    /**
     * [text] as Sums' pieces in [lang]: numbers (in digits or words, with their money and percent marks), units and other
     * words. Null when a number can't be read the way [lang] writes numbers or has too many digits.
     */
    fun tokens(text: String, lang: Lang): List<Tok>? = atoms(text)?.let { tokens(it, lang) }

    fun tokens(atoms0: List<String>, lang: Lang): List<Tok>? {
        // « pour cent », "per cent", "percent" are a percent sign.
        val atoms = mutableListOf<String>()
        var i = 0
        while (i < atoms0.size) {
            val a = atoms0[i]
            val b = atoms0.getOrNull(i + 1)
            when {
                (a == "pour" || a == "per") && b == "cent" -> { atoms += "%"; i += 2 }
                a == "percent" || a == "pourcent" -> { atoms += "%"; i++ }
                else -> { atoms += a; i++ }
            }
        }
        // French groups thousands with a space: « 1 500 ».
        val merged = mutableListOf<String>()
        for (a in atoms) {
            val last = merged.lastOrNull()
            if (lang == Lang.FR && last != null && Regex("\\d{1,3}(?: ?\\d{3})*").matches(last) && Regex("\\d{3}").matches(a) && merged.size >= 1) {
                merged[merged.size - 1] = "$last $a"
            } else merged += a
        }
        val raw = mutableListOf<Tok>()
        i = 0
        while (i < merged.size) {
            val a = merged[i]
            if (a.first().isDigit()) {
                raw += Tok.N(digits(a, lang) ?: return null)
                i++
                continue
            }
            val (n, used) = words(merged, i, lang)
            if (used > 0) {
                raw += Tok.N(n ?: return null)
                i += used
                continue
            }
            // A unit's name, the longest that fits; a one-letter one ("l", "m", "g", "h") only after a number.
            val unit = Units.NAMES.firstOrNull { (said, _) ->
                i + said.size <= merged.size && said.indices.all { merged[i + it] == said[it] } && (said.size > 1 || said[0].length > 1 || raw.lastOrNull() is Tok.N)
            }
            if (unit != null) {
                raw += Tok.U(unit.second)
                i += unit.first.size
                continue
            }
            raw += Tok.W(a)
            i++
        }
        return marks(raw)
    }

    /** "$" and dollars, cents and percent signs put on their numbers. */
    private fun marks(t: List<Tok>): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        fun w(k: Int): String? = (t.getOrNull(k) as? Tok.W)?.w
        while (i < t.size) {
            val x = t[i]
            when {
                x is Tok.W && x.w == "$" && t.getOrNull(i + 1) is Tok.N -> {
                    out += (t[i + 1] as Tok.N).copy(money = true)
                    i += 2
                }
                x is Tok.N && w(i + 1) == "%" -> { out += x.copy(pct = true); i += 2 }
                x is Tok.N && w(i + 1) == "$" -> { out += x.copy(money = true); i += 2 }
                x is Tok.N && w(i + 1) in DOLLARS -> {
                    // "64 dollars 50", "64 dollars and 50 cents", « 64 dollars et 50 ».
                    var j = i + 2
                    if (w(j) == "and" || w(j) == "et") j++
                    val c = t.getOrNull(j) as? Tok.N
                    if (c != null && !c.pct && !c.money && c.v.scale() <= 0 && c.v.signum() >= 0 && c.v < BigDecimal(100) && (t.getOrNull(j + 1) !is Tok.U)) {
                        out += Tok.N(x.v.add(c.v.movePointLeft(2)), money = true)
                        i = j + 1
                        if (w(i) in CENTS) i++
                    } else {
                        out += x.copy(money = true)
                        i += 2
                    }
                }
                x is Tok.N && w(i + 1) in CENTS -> { out += Tok.N(x.v.movePointLeft(2), money = true); i += 2 }
                else -> { out += x; i++ }
            }
        }
        return out
    }

    private val DOLLARS = setOf("dollar", "dollars", "bucks", "buck", "piasse", "piasses", "piastre", "piastres")
    private val CENTS = setOf("cent", "cents", "cenne", "cennes", "sous")

    /** Digits as [lang] writes them: "1,500.25" and "1,5" in English; « 1,5 », « 1 500 » and "1.5" in French. */
    fun digits(a: String, lang: Lang): BigDecimal? {
        val s = a.replace(" ", "")
        if (s.count { it.isDigit() } > MAX_DIGITS) return null
        val t = when {
            lang == Lang.EN && Regex("\\d{1,3}(,\\d{3})+(\\.\\d+)?").matches(s) -> s.replace(",", "")
            // E10: "1.000" and « 1,000 » may be a thousand or a one: not guessed (a whole part of 0 is plainly a decimal).
            Regex("[1-9]\\d{0,2}[.,]\\d{3}").matches(s) -> return null
            Regex("\\d+(,\\d+)?").matches(s) && (lang == Lang.FR || !Regex("\\d+,\\d{3}").matches(s)) -> s.replace(',', '.')
            Regex("\\d+(\\.\\d+)?").matches(s) -> s
            else -> return null
        }
        return t.toBigDecimalOrNull()
    }

    private val EN_SMALL = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen").withIndex().associate { (i, w) -> w to i }
    private val EN_TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val FR_SMALL = mapOf("zero" to 0, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8,
        "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16)
    private val FR_TENS = mapOf("vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60)
    private val SCALES = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L,
        "cent" to 100L, "cents" to 100L, "mille" to 1_000L, "millions" to 1_000_000L, "milliard" to 1_000_000_000L, "milliards" to 1_000_000_000L)

    private fun small(w: String, lang: Lang): Int? = if (lang == Lang.FR) FR_SMALL[w] else EN_SMALL[w]
    private fun tens(w: String, lang: Lang): Int? = if (lang == Lang.FR) FR_TENS[w] else EN_TENS[w]

    /**
     * A number said in words at [atoms][[at]]: its value and how many atoms it took (0: none starts there). Null value:
     * words that start a number but don't make one.
     */
    private fun words(atoms: List<String>, at: Int, lang: Lang): Pair<BigDecimal?, Int> {
        var i = at
        fun w(k: Int = 0): String? = atoms.getOrNull(i + k)
        val fr = lang == Lang.FR
        // Where a number may start: a number word; "a" or "un" only before a scale or a half ("a hundred", « un virgule cinq »).
        val first = w() ?: return null to 0
        val starts = small(first, lang) != null || tens(first, lang) != null || (first == "hundred" || first == "thousand" || (fr && (first == "mille" || first == "cent"))) ||
            ((first == "a" || first == "an" || first == "one") && w(1) in setOf("hundred", "thousand", "million", "half", "quarter")) ||
            (first == "one" && !fr) || (fr && (first == "un" || first == "une") && w(1) in setOf("virgule", "million", "milliard")) ||
            ((first == "point" && !fr || first == "virgule" && fr) && decimalPart(atoms, i + 1, lang).second > 0) || (first == "half" && !fr)
        if (!starts) return null to 0
        var total = 0L
        var cur = 0L
        var any = false
        /** The last word added: 1 a unit or a teen, 2 a tens word, 3 a scale. */
        var last = 0
        /** The last thousand-or-larger scale said (0: none yet). */
        var big = 0L
        var frac: BigDecimal? = null
        loop@ while (true) {
            val x = w() ?: break
            val s = small(x, lang) ?: if (!fr && x == "one") 1 else if ((x == "un" || x == "une") && fr && (last == 2 || !any)) 1 else null
            val t = tens(x, lang)
            when {
                (x == "a" || x == "an") && !any && w(1) in setOf("hundred", "thousand", "million") -> { cur = 1; any = true; last = 1; i++ }
                (x == "a" || x == "an") && w(1) == "half" && !any -> { frac = BigDecimal("0.5"); any = true; i += 2; break@loop }
                (x == "a" || x == "an") && w(1) == "quarter" && !any -> { frac = BigDecimal("0.25"); any = true; i += 2; break@loop }
                x == "half" && !any && !fr -> { frac = BigDecimal("0.5"); any = true; i++; break@loop }
                s != null && (!any || last == 3 || (last == 2 && (s in 1..9 || (fr && cur % 100 in setOf(60L, 80L) && s in 10..16)))) -> {
                    // « soixante dix », « quatre-vingt-onze »; "sixty four".
                    cur += s; any = true; last = 1; i++
                }
                fr && s != null && last == 1 && cur % 100 in setOf(10L, 70L, 90L) && s in 7..9 -> { cur += s; i++ } // « dix-sept », « soixante-dix-sept »
                t != null && fr && t == 20 && last == 1 && cur % 100 == 4L -> { cur += 76; last = 2; i++ } // « quatre-vingts »
                t != null && (!any || last == 3) -> { cur += t; any = true; last = 2; i++ }
                x in SCALES && !(x == "cent" && fr && atoms.getOrNull(i - 1) == "pour") && (any || x == "hundred" || x == "thousand" || x == "mille" || x == "cent") -> {
                    val scale = SCALES.getValue(x)
                    if (x.startsWith("cent")) {
                        if (!fr) break@loop
                        cur = (if (cur == 0L) 1 else cur) * 100
                    } else if (scale == 100L) {
                        cur = (if (cur == 0L) 1 else cur) * 100
                    } else if (big != 0L && scale == big && cur != 0L) {
                        return null to (i + 1 - at) // "two thousand three thousand": no one number.
                    } else if (big != 0L && scale >= big) {
                        // F85: "two thousand million" is 2,000 millions: the scale takes everything said so far.
                        total = try { Math.multiplyExact(total + cur, scale) } catch (_: ArithmeticException) { return null to (i + 1 - at) }
                        cur = 0
                        big = scale
                    } else {
                        total += (if (cur == 0L) 1 else cur) * scale
                        cur = 0
                        big = scale
                    }
                    any = true; last = 3; i++
                }
                (x == "and" && !fr && last == 3 && (w(1)?.let { small(it, lang) ?: tens(it, lang) } != null)) -> i++ // "one hundred and five"
                (x == "and" && !fr && any && w(1) == "a" && w(2) in setOf("half", "quarter")) -> { frac = if (w(2) == "half") BigDecimal("0.5") else BigDecimal("0.25"); i += 3; break@loop }
                (x == "et" && fr && any && w(1) in setOf("demi", "demie", "quart")) -> { frac = if (w(1) == "quart") BigDecimal("0.25") else BigDecimal("0.5"); i += 2; break@loop }
                (x == "et" && fr && last == 2 && (w(1) == "un" || w(1) == "une" || w(1) == "onze")) -> i++ // « vingt et un », « soixante et onze »
                (x == "point" && !fr || x == "virgule" && fr) -> {
                    // "point five", "point two five", « virgule cinq »: digit by digit, or one number ("point twenty five").
                    val (digits, used) = decimalPart(atoms, i + 1, lang)
                    if (used == 0) return null to 0
                    frac = BigDecimal("0.$digits")
                    i += 1 + used
                    break@loop
                }
                // "sixty-four fifty", « soixante-quatre et cinquante »: dollars and cents, said as people say a price.
                any && total == 0L && cur in 1..999 && last == 1 && (t != null || (s != null && s >= 10)) && !fr -> {
                    val (c, used) = cents(atoms, i, lang)
                    if (used == 0) break@loop
                    frac = c; i += used; break@loop
                }
                x == "et" && fr && any && total == 0L && cur in 1..999 && (w(1)?.let { tens(it, lang) ?: small(it, lang)?.takeIf { n -> n >= 10 } } != null) -> {
                    val (c, used) = cents(atoms, i + 1, lang)
                    if (used == 0) break@loop
                    frac = c; i += 1 + used; break@loop
                }
                else -> break@loop
            }
        }
        if (!any && frac == null) return null to 0
        val whole = BigDecimal.valueOf(total + cur)
        return (if (frac != null) whole.add(frac) else whole) to (i - at)
    }

    /** The digits said after "point" or « virgule » at [atoms][[at]], and atoms taken (0: none). */
    private fun decimalPart(atoms: List<String>, at: Int, lang: Lang): Pair<String, Int> {
        val sb = StringBuilder()
        var k = at
        while (k < atoms.size) {
            val (n, used) = decimalWord(atoms, k, lang)
            if (used == 0) break
            sb.append(n)
            k += used
        }
        return sb.toString() to (k - at)
    }

    /** One number after the point (F48): "twenty five" is 25, « soixante-quinze » 75, « quatre-vingt-dix » 90, "five" 5; atoms taken (0: none). */
    private fun decimalWord(atoms: List<String>, at: Int, lang: Lang): Pair<Int, Int> {
        val fr = lang == Lang.FR
        fun unit(w: String?): Int? = w?.let { small(it, lang) ?: if (fr && (it == "un" || it == "une")) 1 else null }
        val a = atoms[at]
        val eighty = fr && a == "quatre" && atoms.getOrNull(at + 1)?.let { tens(it, lang) } == 20
        val base = if (eighty) 80 else tens(a, lang) ?: return (unit(a) ?: if (!fr && a == "oh") 0 else return 0 to 0) to 1
        val taken = if (eighty) 2 else 1
        val next = atoms.getOrNull(at + taken)
        // « vingt et un », « soixante et onze ».
        if (fr && next == "et") {
            val after = atoms.getOrNull(at + taken + 1)
            if (after == "un" || after == "une" || after == "onze") return base + unit(after)!! to taken + 2
        }
        val u = unit(next)
        val fits = u != null && (u in 1..9 || (fr && base % 20 == 0 && base != 20 && base != 40 && u in 10..19))
        return (if (fits) base + u!! else base) to (if (fits) taken + 1 else taken)
    }

    /** Cents said as a number from 10 to 99 at [atoms][[at]] ("fifty", "ninety-nine", « cinquante »): as a fraction, and atoms taken. */
    private fun cents(atoms: List<String>, at: Int, lang: Lang): Pair<BigDecimal?, Int> {
        val a = atoms.getOrNull(at) ?: return null to 0
        val t = tens(a, lang)
        val s = small(a, lang)
        val n = when {
            t != null -> t + (atoms.getOrNull(at + 1)?.let { small(it, lang)?.takeIf { d -> d in 1..9 } } ?: 0)
            s != null && s >= 10 -> s
            else -> return null to 0
        }
        val used = if (t != null && atoms.getOrNull(at + 1)?.let { small(it, lang)?.takeIf { d -> d in 1..9 } } != null) 2 else 1
        return BigDecimal.valueOf(n.toLong(), 2) to used
    }
}
