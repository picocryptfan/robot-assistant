// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

// Pure. The everyday set's exact arithmetic (docs/plans/everyday-set-plan.md §4.1, §4.2, D6): decimals as BigDecimal with
// 34 significant digits (IEEE's decimal128), money as Long cents rounded half up, a split's leftover cents to the first
// shares. 3 × 45 × 1.13 is 152.55 here; in binary floating point it is 152.549999…, which rounds to 152.54. Only methods
// Android has had since API 1 (no BigDecimal.sqrt: Newton's method instead). Robot to Robot's receipt split (its L9) can
// use [Exact.split] rather than write its own.

/** An operator of an [Expr]. */
enum class Op { PLUS, MINUS, TIMES, DIVIDE, POWER }

/**
 * A sum as code reads it, from your words (SumsRouter) or the model's expression (calculate). [Pct]: a percentage, a
 * hundredth ("18% of 64.50"); after a plus or a minus it is of the amount before it ("64.50 + 13%" is 64.50 × 1.13), as a
 * calculator and people take it. [Group]: parentheses the model wrote, kept for reading the expression back.
 */
sealed interface Expr {
    data class Num(val v: BigDecimal, val money: Boolean = false) : Expr
    data class Pct(val e: Expr) : Expr
    data class Bin(val op: Op, val a: Expr, val b: Expr) : Expr
    data class Neg(val e: Expr) : Expr
    data class Root(val e: Expr) : Expr
    data class Group(val e: Expr) : Expr
}

/** Why a sum has no answer. */
enum class CalcFail(val id: Int) {
    DIVIDE_BY_ZERO(1),
    TOO_BIG(2),
    NEGATIVE_ROOT(3),
    /** A power that isn't a whole number ("2 to the power of 0.5"), or one too large to work out. */
    POWER(4),
    UNREADABLE(5),
}

/** A sum's answer: [v] exactly (34 digits), [money] when it is an amount in dollars. */
sealed interface Calc {
    data class Ok(val v: BigDecimal, val money: Boolean) : Calc
    data class Fail(val why: CalcFail) : Calc
}

object Exact {
    /** 34 significant digits, as decimal128. */
    val MC: MathContext = MathContext(34, RoundingMode.HALF_EVEN)

    /** At most this many digits after the point on screen and aloud (§4.5); "about" when more were dropped. */
    const val SHOWN = 6

    /** A result this large or larger is too big to say ("one sextillion"); an intermediate one too (no 9^9^9). */
    val TOO_BIG: BigDecimal = BigDecimal("1E21")

    /** The largest whole power worked out: a bigger one can't fit under [TOO_BIG] unless its base is about 1. */
    const val MAX_POWER = 999

    private val HUNDRED = BigDecimal(100)
    private val TWO = BigDecimal(2)

    /** [x] in cents, rounded half up ($11.605 is $11.61). */
    fun cents(x: BigDecimal): Long = x.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

    /** [cents] as dollars. */
    fun dollars(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    /** [p] percent of [x]. */
    fun percentOf(p: BigDecimal, x: BigDecimal): BigDecimal = x.multiply(p).divide(HUNDRED, MC)

    /** [a] as a percentage of [b]; null when [b] is 0. */
    fun percentage(a: BigDecimal, b: BigDecimal): BigDecimal? = if (b.signum() == 0) null else a.multiply(HUNDRED).divide(b, MC)

    /**
     * [cents] in [n] shares that add up to it exactly: each the same, the leftover cents one each to the first shares
     * ($100 in 3: $33.34, $33.33, $33.33). A negative amount splits the same way, signs kept.
     */
    fun split(cents: Long, n: Int): List<Long> {
        require(n > 0) // l10n: not text (a programming error)
        val sign = if (cents < 0) -1 else 1
        val a = Math.abs(cents)
        val each = a / n
        val left = (a % n).toInt()
        return List(n) { i -> sign * (each + if (i < left) 1 else 0) }
    }

    /** The square root of [x] to 34 digits by Newton's method; null for a negative number. */
    fun sqrt(x: BigDecimal): BigDecimal? {
        if (x.signum() < 0) return null
        if (x.signum() == 0) return BigDecimal.ZERO
        val mc = MathContext(MC.precision + 4, RoundingMode.HALF_EVEN)
        var r = BigDecimal(Math.sqrt(x.toDouble()).takeIf { it.isFinite() && it > 0 } ?: 1.0, mc)
        repeat(60) {
            val next = r.add(x.divide(r, mc)).divide(TWO, mc)
            if (next.compareTo(r) == 0) return next.round(MC)
            r = next
        }
        return r.round(MC)
    }

    /** [x] shown with at most [decimals] digits leaves something out: the reply says "about". */
    fun rounded(x: BigDecimal, decimals: Int): Boolean = x.stripTrailingZeros().scale() > decimals

    /** [e]'s answer, or why it has none. Money: an amount in dollars times or divided by plain numbers, or two amounts added. */
    fun solve(e: Expr): Calc = try {
        val (v, m) = value(e)
        if (v.abs() >= TOO_BIG) Calc.Fail(CalcFail.TOO_BIG) else Calc.Ok(v, m == 1)
    } catch (f: Failed) {
        Calc.Fail(f.why)
    } catch (_: ArithmeticException) {
        Calc.Fail(CalcFail.TOO_BIG)
    }

    private class Failed(val why: CalcFail) : Exception() // l10n: not text (control flow)

    /** A value and its power of money (1: dollars; 0: a plain number; 2: dollars times dollars, not money). */
    private fun value(e: Expr): Pair<BigDecimal, Int> {
        val r = when (e) {
            is Expr.Num -> e.v to (if (e.money) 1 else 0)
            is Expr.Group -> value(e.e)
            is Expr.Neg -> value(e.e).let { (v, m) -> v.negate() to m }
            is Expr.Pct -> value(e.e).let { (v, _) -> v.divide(HUNDRED, MC) to 0 }
            is Expr.Root -> value(e.e).let { (v, m) -> (sqrt(v) ?: throw Failed(CalcFail.NEGATIVE_ROOT)) to (if (m == 0) 0 else 2) }
            is Expr.Bin -> {
                val pct = e.b as? Expr.Pct
                if (pct != null && (e.op == Op.PLUS || e.op == Op.MINUS)) {
                    // "64.50 + 13%": the amount and 13% of it.
                    val (a, m) = value(e.a)
                    val p = value(pct.e).first
                    val part = percentOf(p, a)
                    (if (e.op == Op.PLUS) a.add(part, MC) else a.subtract(part, MC)) to m
                } else {
                    val (a, ma) = value(e.a)
                    val (b, mb) = value(e.b)
                    when (e.op) {
                        Op.PLUS -> a.add(b, MC) to maxOf(ma, mb)
                        Op.MINUS -> a.subtract(b, MC) to maxOf(ma, mb)
                        Op.TIMES -> a.multiply(b, MC) to ma + mb
                        Op.DIVIDE -> if (b.signum() == 0) throw Failed(CalcFail.DIVIDE_BY_ZERO) else a.divide(b, MC) to ma - mb
                        Op.POWER -> {
                            val n = try { b.stripTrailingZeros().intValueExact() } catch (_: ArithmeticException) { throw Failed(CalcFail.POWER) }
                            if (Math.abs(n) > MAX_POWER) throw Failed(CalcFail.POWER)
                            if (n < 0 && a.signum() == 0) throw Failed(CalcFail.DIVIDE_BY_ZERO)
                            a.pow(n, MC) to (if (ma == 0) 0 else 2)
                        }
                    }
                }
            }
        }
        if (r.first.abs() >= TOO_BIG.multiply(TOO_BIG)) throw Failed(CalcFail.TOO_BIG)
        return r
    }
}
