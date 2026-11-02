// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.ListKind
import app.heyrobot.core.i18n.Phrase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Pure. What Robot says to a sum, all by code (docs/plans/everyday-set-plan.md §4.5): the numbers worked out exactly (Exact),
// shown with at most 6 decimals, money to the cent rounded half up, "about" whenever anything was rounded; the words from
// the catalog (EverydayStrings), so the screen and the voice each get their own form in English and Québec French
// ("$64.50" / « 64,50 $ » on screen, "64 dollars 50" / « 64 dollars et 50 » aloud).

/** How a sum ended, for the voice log (VoiceEvent.SUMS's b, CALC_TOOL's a). */
enum class SumResult(override val id: Int, override val title: String) : app.heyrobot.core.LogCode {
    EXACT(1, "exact"),
    /** Shown rounded: the reply says "about". */
    ROUNDED(2, "rounded"),
    DIVIDE_BY_ZERO(3, "divided by zero"),
    TOO_BIG(4, "too big"),
    NO_ROOT(5, "no square root"),
    POWER(6, "a power Robot doesn't work out"),
    /** "$80 plus HST": Robot asked which rate. */
    ASKED_RATE(7, "asked which rate"),
    /** A volume asked as a weight: Robot said why not. */
    NO_DENSITY(8, "a volume as a weight"),
    /** The model's expression couldn't be read (calculate). */
    UNREADABLE(9, "unreadable"),
    /** The model named a unit Sums doesn't know (calculate). */
    UNKNOWN_UNIT(10, "an unknown unit"),
}

/** A sum's reply: [text] to show and say, how it ended, and [rates] to offer on chips when Robot asked which. */
data class SumsAnswer(val text: Phrase, val result: SumResult, val rates: List<BigDecimal> = emptyList()) {
    override fun toString() = "SumsAnswer($result)" // l10n: not text (log only: never a sum's numbers)
}

object Sums {
    /** HST by province, canada.ca's GST/HST calculator page, read 23 Oct 2026 (PUBLISHED): Ontario 13%, Nova Scotia 14% since 1 April 2025, 15% elsewhere. */
    val HST_RATES: List<BigDecimal> = listOf(BigDecimal(13), BigDecimal(14), BigDecimal(15))

    private val S = EverydayStrings

    /** [cmd]'s reply. */
    fun answer(cmd: SumsCommand): SumsAnswer = when (cmd) {
        is SumsCommand.Arith -> arith(cmd.expr)
        is SumsCommand.PercentOf -> {
            val r = Exact.percentOf(cmd.p, cmd.x)
            val (q, rounded) = result(r, cmd.money)
            SumsAnswer(S.percentOf("p" to pct(cmd.p), "x" to said(cmd.x, cmd.money), "r" to q), code(rounded))
        }
        is SumsCommand.WhatPercent -> {
            val r = Exact.percentage(cmd.a, cmd.b) ?: return SumsAnswer(S.divideByZero(), SumResult.DIVIDE_BY_ZERO)
            val rounded = Exact.rounded(r, 2)
            val p = pct(r, 2).let { if (rounded) S.about("x" to it) else it }
            SumsAnswer(S.whatPercent("a" to said(cmd.a, cmd.money), "p" to p, "b" to said(cmd.b, cmd.money)), code(rounded))
        }
        is SumsCommand.AddPercent -> {
            val part = Exact.percentOf(cmd.p, cmd.x)
            if (cmd.money) {
                val x = Exact.cents(cmd.x)
                val partC = Exact.cents(part)
                val r = if (cmd.minus) x - partC else x + partC
                val rounded = Exact.rounded(part, 2)
                val q = about(dollars(r), rounded)
                val text = when {
                    cmd.off -> S.discount("p" to pct(cmd.p), "x" to dollars(x), "r" to q, "s" to about(dollars(partC), rounded))
                    cmd.minus -> S.minusPercent("x" to dollars(x), "p" to pct(cmd.p), "r" to q)
                    else -> S.plusPercent("x" to dollars(x), "p" to pct(cmd.p), "r" to q)
                }
                SumsAnswer(text, code(rounded))
            } else {
                val r = if (cmd.minus) cmd.x.subtract(part, Exact.MC) else cmd.x.add(part, Exact.MC)
                val (q, rounded) = result(r, false)
                val text = when {
                    cmd.off -> S.discount("p" to pct(cmd.p), "x" to said(cmd.x, false), "r" to q, "s" to result(part, false).first)
                    cmd.minus -> S.minusPercent("x" to said(cmd.x, false), "p" to pct(cmd.p), "r" to q)
                    else -> S.plusPercent("x" to said(cmd.x, false), "p" to pct(cmd.p), "r" to q)
                }
                SumsAnswer(text, code(rounded))
            }
        }
        is SumsCommand.Tip -> {
            val x = Exact.cents(cmd.x)
            val exact = Exact.percentOf(cmd.p, Exact.dollars(x))
            val tip = Exact.cents(exact)
            val rounded = Exact.rounded(exact, 2)
            val msg = if (an(cmd.p)) S.tipAn else S.tipA
            SumsAnswer(msg("p" to pct(cmd.p), "x" to dollars(x), "t" to about(dollars(tip), rounded), "total" to dollars(x + tip)), code(rounded))
        }
        is SumsCommand.Tax -> tax(cmd)
        is SumsCommand.Split -> split(cmd)
        is SumsCommand.Convert -> convert(cmd)
        is SumsCommand.Days -> days(cmd)
        is SumsCommand.Weekday -> SumsAnswer(S.weekday("day" to day(cmd.date, cmd.today)), SumResult.EXACT)
        is SumsCommand.DateIn -> {
            val n = if (cmd.weeks) cmd.n * 7L else cmd.n.toLong()
            val date = cmd.today.plusDays(n)
            SumsAnswer(S.dateIn("span" to span(n, cmd.weeks), "day" to day(date, cmd.today)), SumResult.EXACT)
        }
    }

    /** [p]% at [p]'s own decimals (at most [max]): "18%" / « 18 % », "18 percent" aloud. */
    fun pct(p: BigDecimal, max: Int = Exact.SHOWN): Phrase = S.percent("n" to Arg.Dec(p, max))

    /** An amount as said (all its decimals, up to [Exact.SHOWN]): money in dollars and cents. */
    fun said(v: BigDecimal, money: Boolean): Phrase = if (money) dollars(Exact.cents(v)) else S.number("n" to Arg.Dec(v, Exact.SHOWN))

    fun dollars(cents: Long): Phrase = S.money("m" to Arg.Dollars(cents))

    private fun about(p: Phrase, rounded: Boolean): Phrase = if (rounded) S.about("x" to p) else p

    private fun code(rounded: Boolean) = if (rounded) SumResult.ROUNDED else SumResult.EXACT

    /** A result: money to the cent, a number to [Exact.SHOWN] decimals, "about" when that dropped something. */
    fun result(v: BigDecimal, money: Boolean): Pair<Phrase, Boolean> {
        val rounded = Exact.rounded(v, if (money) 2 else Exact.SHOWN)
        val q = if (money) dollars(Exact.cents(v)) else S.number("n" to Arg.Dec(v, Exact.SHOWN))
        return about(q, rounded) to rounded
    }

    /** English says "an 18% tip", "an 8% tip", "an 11% tip": the number's first sound is a vowel. */
    fun an(p: BigDecimal): Boolean {
        val whole = p.abs().toBigInteger().toString()
        return whole.startsWith("8") || (whole.length % 3 == 2 && (whole.startsWith("11") || whole.startsWith("18")))
    }

    /** [e] worked out and read back: "12 × 7 = 84" on screen, "12 times 7 is 84." aloud. */
    fun arith(e: Expr): SumsAnswer = when (val c = Exact.solve(e)) {
        is Calc.Fail -> fail(c.why)
        is Calc.Ok -> {
            val (q, rounded) = result(c.v, c.money)
            SumsAnswer(S.equals("expr" to expr(e), "result" to q), code(rounded))
        }
    }

    /** What a sum without an answer says. */
    fun fail(why: CalcFail): SumsAnswer = when (why) {
        CalcFail.DIVIDE_BY_ZERO -> SumsAnswer(S.divideByZero(), SumResult.DIVIDE_BY_ZERO)
        CalcFail.TOO_BIG -> SumsAnswer(S.tooBig(), SumResult.TOO_BIG)
        CalcFail.NEGATIVE_ROOT -> SumsAnswer(S.negativeRoot(), SumResult.NO_ROOT)
        CalcFail.POWER -> SumsAnswer(S.wholePowers("max" to Exact.MAX_POWER), SumResult.POWER)
        CalcFail.UNREADABLE -> SumsAnswer(S.unreadable(), SumResult.UNREADABLE)
    }

    /** [e] read back in words: numbers as said, each operator in the language's words ("×" / "times" / « fois »). */
    fun expr(e: Expr): Phrase = when (e) {
        is Expr.Num -> said(e.v, e.money)
        is Expr.Pct -> (e.e as? Expr.Num)?.let { pct(it.v) } ?: S.percentOfExpr("a" to expr(e.e))
        is Expr.Neg -> S.neg("a" to expr(e.e))
        is Expr.Root -> S.root("a" to expr(e.e))
        is Expr.Group -> S.group("a" to expr(e.e))
        is Expr.Bin -> when (e.op) {
            Op.PLUS -> S.plus("a" to expr(e.a), "b" to expr(e.b))
            Op.MINUS -> S.minus("a" to expr(e.a), "b" to expr(e.b))
            Op.TIMES -> S.times("a" to expr(e.a), "b" to expr(e.b))
            Op.DIVIDE -> S.divide("a" to expr(e.a), "b" to expr(e.b))
            Op.POWER -> S.power("a" to expr(e.a), "b" to expr(e.b))
        }
    }

    private fun taxName(t: TaxName): Phrase = when (t) {
        TaxName.HST -> S.taxHst()
        TaxName.GST -> S.taxGst()
        TaxName.QST -> S.taxQst()
        TaxName.PST -> S.taxPst()
        TaxName.TAX -> S.taxTax()
    }

    private fun tax(cmd: SumsCommand.Tax): SumsAnswer {
        val p = cmd.p ?: return if (cmd.tax == TaxName.HST) SumsAnswer(S.whichHst(), SumResult.ASKED_RATE, HST_RATES)
            else SumsAnswer(S.whichRate(), SumResult.ASKED_RATE)
        val x = Exact.cents(cmd.x)
        val exact = Exact.percentOf(p, Exact.dollars(x))
        val t = Exact.cents(exact)
        val rounded = Exact.rounded(exact, 2)
        val name = taxName(cmd.tax)
        return SumsAnswer(S.tax("x" to dollars(x), "p" to pct(p), "name" to name, "total" to dollars(x + t), "t" to about(dollars(t), rounded)), code(rounded))
    }

    /** [cmd] at the rate you picked on its card. */
    fun withRate(cmd: SumsCommand.Tax, rate: BigDecimal): SumsAnswer = tax(cmd.copy(p = rate))

    private fun split(cmd: SumsCommand.Split): SumsAnswer {
        if (!cmd.money) {
            val each = cmd.x.divide(BigDecimal(cmd.n), Exact.MC)
            val (q, rounded) = result(each, false)
            return SumsAnswer(S.splitEven("x" to said(cmd.x, false), "n" to cmd.n, "each" to q), code(rounded))
        }
        val x = Exact.cents(cmd.x)
        val shares = Exact.split(x, cmd.n)
        val big = shares.first()
        val small = shares.last()
        if (big == small) return SumsAnswer(S.splitEven("x" to dollars(x), "n" to cmd.n, "each" to dollars(big)), SumResult.EXACT)
        // A leftover cent goes to the first shares, each named, so they add up: "$33.34, $33.33 and $33.33".
        if (cmd.n <= 6) return SumsAnswer(S.splitList("x" to dollars(x), "n" to cmd.n, "shares" to Arg.list(shares.map(::dollars))), SumResult.EXACT)
        val k = shares.count { it == big }
        return SumsAnswer(S.splitGroups("x" to dollars(x), "n" to cmd.n, "big" to dollars(big), "k" to k, "small" to dollars(small), "rest" to cmd.n - k),
            SumResult.EXACT)
    }

    /** [v] in [u]: "4 cups of 250 mL" / « 4 tasses de 250 mL », with [u]'s decimals (Units.decimals) or [decimals]. */
    fun unit(u: Unit, v: BigDecimal, decimals: Int = Units.decimals(u)): Phrase = S.unit(u)("n" to Arg.Dec(v, decimals))

    private fun converted(v: BigDecimal, from: Unit, to: Unit): Pair<Phrase, Boolean>? {
        val r = Units.convert(v, from, to) ?: return null
        val decimals = Units.decimals(to, r)
        val rounded = Exact.rounded(r, decimals)
        return about(unit(to, r, decimals), rounded) to rounded
    }

    private fun convert(cmd: SumsCommand.Convert): SumsAnswer {
        if (cmd.weighed) return SumsAnswer(S.weighed(), SumResult.NO_DENSITY)
        val (to, rounded) = converted(cmd.v, cmd.from, cmd.to) ?: return SumsAnswer(S.weighed(), SumResult.NO_DENSITY)
        val from = unit(cmd.from, cmd.v, Exact.SHOWN)
        val parts = mutableListOf(S.convert("from" to from, "to" to to))
        var anyRounded = rounded
        // Q6: a Canadian measure has its US one named alongside.
        val usTo = cmd.to.us
        val usFrom = cmd.from.us
        if (usTo != null && usTo != cmd.from) converted(cmd.v, cmd.from, usTo)?.let { (q, r) -> parts += S.convertUs("q" to q); anyRounded = anyRounded || r }
        else if (usFrom != null && usFrom != cmd.to && cmd.from != cmd.to) converted(cmd.v, usFrom, cmd.to)?.let { (q, r) ->
            parts += S.convertFromUs("q" to unit(usFrom, cmd.v, Exact.SHOWN), "r" to q); anyRounded = anyRounded || r
        }
        return SumsAnswer(Phrase.Join(parts), code(anyRounded))
    }

    private fun days(cmd: SumsCommand.Days): SumsAnswer {
        val n = if (cmd.since) ChronoUnit.DAYS.between(cmd.date, cmd.today) else ChronoUnit.DAYS.between(cmd.today, cmd.date)
        val day = day(cmd.date, cmd.today)
        val text = when {
            n == 0L -> S.today("day" to day)
            n < 0 && !cmd.since -> S.ago("span" to span(-n, cmd.weeks), "day" to day)
            n < 0 -> S.until("span" to span(-n, cmd.weeks), "day" to day)
            cmd.since -> S.since("span" to span(n, cmd.weeks), "day" to day)
            else -> S.until("span" to span(n, cmd.weeks), "day" to day)
        }
        return SumsAnswer(text, SumResult.EXACT)
    }

    /** [n] days, or in weeks and days: "4 weeks", "20 weeks and 2 days" / « 20 semaines et 2 jours ». */
    private fun span(n: Long, weeks: Boolean): Phrase {
        if (!weeks) return S.days(n)
        val w = n / 7
        val d = n % 7
        return when {
            w == 0L -> S.days(d)
            d == 0L -> S.weeks(w)
            else -> Phrase.ListOf(listOf(S.weeks(w), S.days(d)), ListKind.AND)
        }
    }

    /** "Friday November 20" / « vendredi 20 novembre », with its year when it isn't [today]'s. */
    private fun day(date: LocalDate, today: LocalDate): Phrase =
        if (date.year == today.year) S.day("day" to Arg.Day(date)) else S.dayYear("day" to Arg.Day(date), "year" to date.year.toString())
}
