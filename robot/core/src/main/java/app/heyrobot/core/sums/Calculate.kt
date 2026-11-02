// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.i18n.Lang
import java.math.BigDecimal

// Pure. The model's one sum tool, calculate (docs/plans/everyday-set-plan.md §4.4, D8), for a sum your words don't fit
// ("3 kids at $45 each plus HST"): the model picks the expression, code works it out and replies, reading the expression
// back ("3 × 45 × 1.13 = $152.55", "3 times 45 times 1.13 is 152 dollars 55."), and the model's turn ends there, so it never
// copies a number into its own words. The expression is untrusted text: at most [MAX_CHARS] characters, [MAX_TOKENS] pieces,
// nesting [MAX_DEPTH] deep, numbers of at most SumWords.MAX_DIGITS digits, whole powers up to Exact.MAX_POWER, every
// intermediate value under Exact.TOO_BIG squared. Units only from Sums' own list: no milligrams, micrograms, IU or cc, so a
// dose is never converted by code (§4.6).

/** What calculate was asked: [expression] as the model wrote it, and a conversion [from] one unit [to] another, both or neither. */
data class CalcAsk(val expression: String, val from: String? = null, val to: String? = null) {
    override fun toString() = "CalcAsk(chars=${expression.length}, units=${from != null})" // l10n: not text (log only: never a sum's numbers)
}

object Calculate {
    const val MAX_CHARS = 160
    const val MAX_TOKENS = 64
    const val MAX_DEPTH = 16

    /**
     * [ask] worked out, the reply in [lang]'s words (the turn's): the expression read back and its result, a conversion, or
     * why there is none ("You can't divide by zero.", "Robot couldn't read that sum.").
     */
    fun run(ask: CalcAsk, lang: Lang = Lang.EN): SumsAnswer {
        val e = parse(ask.expression) ?: return Sums.fail(CalcFail.UNREADABLE)
        if (ask.from == null && ask.to == null) return Sums.arith(e)
        val from = ask.from?.let(::unit)
        val to = ask.to?.let(::unit)
        if (from == null || to == null) return SumsAnswer(app.heyrobot.core.i18n.EverydayStrings.unreadable(), SumResult.UNKNOWN_UNIT)
        val v = when (val c = Exact.solve(e)) {
            is Calc.Fail -> return Sums.fail(c.why)
            is Calc.Ok -> if (c.money) return SumsAnswer(app.heyrobot.core.i18n.EverydayStrings.unreadable(), SumResult.UNKNOWN_UNIT) else c.v
        }
        return Sums.answer(SumsCommand.Convert(v, from, to, lang, weighed = !Units.compatible(from, to)))
    }

    /** A unit the model named ("km", "cups", "°F", "miles per hour"), from Sums' own names only; null for any other. */
    fun unit(name: String): Unit? {
        val atoms = SumWords.atoms(name) ?: return null
        return Units.NAMES.firstOrNull { it.first == atoms }?.second
    }

    /**
     * [s] as a sum: numbers ("64.50", "1,500", "64,50"), "$" before or after one, "%" after one, + - * × x ÷ / ^ **, "of"
     * (times: "18% of 64.50"), parentheses, sqrt() and √. Null when anything else is in it, or it is too long or too deep.
     */
    fun parse(s: String): Expr? {
        if (s.isBlank() || s.length > MAX_CHARS) return null
        val toks = lex(s) ?: return null
        if (toks.size > MAX_TOKENS) return null
        val p = P(toks)
        val e = p.sum() ?: return null
        return if (p.i == toks.size) e else null
    }

    private sealed interface T {
        data class Num(val v: BigDecimal) : T
        data class Sym(val c: String) : T
    }

    private fun lex(s0: String): List<T>? {
        val s = s0.lowercase().replace("**", "^").replace("×", "*").replace("÷", "/").replace("−", "-").replace(' ', ' ')
        val out = mutableListOf<T>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && s.getOrNull(i + 1)?.isDigit() == true) -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.' || (s[j] == ',' && s.getOrNull(j + 1)?.isDigit() == true))) j++
                    val raw = s.substring(i, j)
                    // "1,500.25" groups thousands; "64,50" is a decimal comma (two digits after it, as French writes cents).
                    val n = when {
                        Regex("\\d{1,3}(,\\d{3})+(\\.\\d+)?").matches(raw) -> raw.replace(",", "")
                        Regex("\\d+,\\d{1,2}").matches(raw) -> raw.replace(',', '.')
                        Regex("\\d*\\.?\\d+").matches(raw) || Regex("\\d+\\.").matches(raw) -> raw.trimEnd('.')
                        else -> return null
                    }
                    if (n.count { it.isDigit() } > SumWords.MAX_DIGITS) return null
                    out += T.Num(n.toBigDecimalOrNull() ?: return null)
                    i = j
                }
                c in "+-*/^()%$√" -> { out += T.Sym(c.toString()); i++ }
                c == 'x' -> { out += T.Sym("*"); i++ }
                s.startsWith("sqrt", i) -> { out += T.Sym("√"); i += 4 }
                s.startsWith("of", i) && (i + 2 == s.length || !s[i + 2].isLetter()) && (i == 0 || !s[i - 1].isLetter()) -> { out += T.Sym("*"); i += 2 }
                else -> return null
            }
        }
        return out
    }

    private class P(val t: List<T>) {
        var i = 0
        var depth = 0

        private fun sym(c: String): Boolean = (t.getOrNull(i) as? T.Sym)?.c == c
        private fun take(c: String): Boolean = sym(c).also { if (it) i++ }

        fun sum(): Expr? {
            var a = product() ?: return null
            while (true) {
                val op = when {
                    take("+") -> Op.PLUS
                    take("-") -> Op.MINUS
                    else -> return a
                }
                a = Expr.Bin(op, a, product() ?: return null)
            }
        }

        private fun product(): Expr? {
            var a = power() ?: return null
            while (true) {
                val op = when {
                    take("*") -> Op.TIMES
                    take("/") -> Op.DIVIDE
                    else -> return a
                }
                a = Expr.Bin(op, a, power() ?: return null)
            }
        }

        private fun power(): Expr? {
            val a = unary() ?: return null
            if (!take("^")) return a
            return nested { power() }?.let { Expr.Bin(Op.POWER, a, it) }
        }

        /** [f] one level deeper: parentheses, a sign, a root or a power inside another; null past [MAX_DEPTH]. */
        private fun nested(f: () -> Expr?): Expr? {
            if (++depth > MAX_DEPTH) return null
            return f().also { depth-- }
        }

        private fun unary(): Expr? {
            // F82: as a calculator and people read it, -3^2 is -(3^2); (-3)^2 is written with its parentheses.
            if (take("-")) return nested { power() }?.let { Expr.Neg(it) }
            if (take("+")) return nested { power() }
            if (take("√")) return nested { unary() }?.let { Expr.Root(it) }
            return postfix(atom() ?: return null)
        }

        private fun atom(): Expr? {
            if (take("(")) {
                val e = nested { sum() } ?: return null
                if (!take(")")) return null
                return Expr.Group(e)
            }
            val dollar = take("$")
            val n = t.getOrNull(i) as? T.Num ?: return null
            i++
            val money = dollar || take("$")
            return Expr.Num(n.v, money)
        }

        private fun postfix(e: Expr): Expr = if (take("%")) Expr.Pct(e) else e
    }
}
