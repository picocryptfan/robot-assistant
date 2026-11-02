// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Sums' exact arithmetic (everyday-set-plan.md §4.1, §4.2, D6): decimals, money in cents, splits, limits. */
class ExactTest {
    private fun n(s: String) = Expr.Num(BigDecimal(s))
    private fun ok(e: Expr): BigDecimal = (Exact.solve(e) as Calc.Ok).v

    /** The plan's own example: 3 × 45 × 1.13 is 152.55; binary floating point gives 152.549999…, which rounds to 152.54. */
    @Test fun threeTimesFortyFiveTimesOnePointOneThreeIsExact() {
        val v = ok(Expr.Bin(Op.TIMES, Expr.Bin(Op.TIMES, n("3"), n("45")), n("1.13")))
        assertEquals(0, BigDecimal("152.55").compareTo(v))
        assertEquals(15255L, Exact.cents(v))
        assertEquals("152.54", String.format(java.util.Locale.US, "%.2f", Math.floor(3 * 45 * 1.13 * 100) / 100))
        assertTrue(3 * 45 * 1.13 < 152.55)
    }

    @Test fun moneyRoundsHalfUpToTheCent() {
        assertEquals(1161L, Exact.cents(Exact.percentOf(BigDecimal(18), BigDecimal("64.50"))))
        assertEquals(1161L, Exact.cents(BigDecimal("11.605")))
        assertEquals(1160L, Exact.cents(BigDecimal("11.6049")))
        assertEquals(-1161L, Exact.cents(BigDecimal("-11.605")))
    }

    @Test fun aSplitsLeftoverCentsGoToTheFirstShares() {
        assertEquals(listOf(3334L, 3333L, 3333L), Exact.split(10000, 3))
        assertEquals(listOf(4001L, 4000L, 4000L), Exact.split(12001, 3))
        assertEquals(listOf(4000L, 4000L, 4000L), Exact.split(12000, 3))
        assertEquals(listOf(2L, 2L, 1L, 1L), Exact.split(6, 4))
        for (c in listOf(1L, 99L, 10001L, 123457L)) for (k in 2..9) assertEquals(c, Exact.split(c, k).sum())
    }

    @Test fun precedenceAndPercentages() {
        // 64.50 + 13% is 64.50 and 13% of it; 18% alone is 0.18.
        assertEquals(0, BigDecimal("72.885").compareTo(ok(Expr.Bin(Op.PLUS, n("64.50"), Expr.Pct(n("13"))))))
        assertEquals(0, BigDecimal("68").compareTo(ok(Expr.Bin(Op.MINUS, n("80"), Expr.Pct(n("15"))))))
        assertEquals(0, BigDecimal("11.61").compareTo(ok(Expr.Bin(Op.TIMES, Expr.Pct(n("18")), n("64.50")))))
        assertEquals(0, BigDecimal("1024").compareTo(ok(Expr.Bin(Op.POWER, n("2"), n("10")))))
        assertEquals(0, BigDecimal("0.25").compareTo(ok(Expr.Bin(Op.POWER, n("2"), Expr.Neg(n("2"))))))
    }

    @Test fun squareRootsByNewton() {
        assertEquals("1.414213562373095048801688724209698", Exact.sqrt(BigDecimal(2))!!.toPlainString())
        assertEquals(0, BigDecimal(12).compareTo(Exact.sqrt(BigDecimal(144))))
        assertEquals(null, Exact.sqrt(BigDecimal(-1)))
        assertEquals(Calc.Fail(CalcFail.NEGATIVE_ROOT), Exact.solve(Expr.Root(n("-4"))))
    }

    @Test fun whatHasNoAnswerSaysWhy() {
        assertEquals(Calc.Fail(CalcFail.DIVIDE_BY_ZERO), Exact.solve(Expr.Bin(Op.DIVIDE, n("5"), n("0"))))
        assertEquals(Calc.Fail(CalcFail.DIVIDE_BY_ZERO), Exact.solve(Expr.Bin(Op.POWER, n("0"), n("-1"))))
        assertEquals(Calc.Fail(CalcFail.POWER), Exact.solve(Expr.Bin(Op.POWER, n("2"), n("0.5"))))
        assertEquals(Calc.Fail(CalcFail.POWER), Exact.solve(Expr.Bin(Op.POWER, n("9"), Expr.Bin(Op.POWER, n("9"), n("9")))))
        assertEquals(Calc.Fail(CalcFail.TOO_BIG), Exact.solve(Expr.Bin(Op.POWER, n("10"), n("21"))))
        assertTrue(Exact.solve(Expr.Bin(Op.POWER, n("10"), n("20"))) is Calc.Ok)
        // Intermediate values are bounded too: no 10^999 on the way to something small.
        assertEquals(Calc.Fail(CalcFail.TOO_BIG), Exact.solve(Expr.Bin(Op.DIVIDE, Expr.Bin(Op.POWER, n("10"), n("999")), Expr.Bin(Op.POWER, n("10"), n("998")))))
    }

    @Test fun moneyIsDollarsTimesNumbers() {
        assertEquals(Calc.Ok(BigDecimal("135"), true).money, (Exact.solve(Expr.Bin(Op.TIMES, Expr.Num(BigDecimal(45), money = true), n("3"))) as Calc.Ok).money)
        assertFalse((Exact.solve(Expr.Bin(Op.DIVIDE, Expr.Num(BigDecimal(120), true), Expr.Num(BigDecimal(40), true))) as Calc.Ok).money)
        assertTrue((Exact.solve(Expr.Bin(Op.PLUS, Expr.Num(BigDecimal(1), true), Expr.Num(BigDecimal(2), true))) as Calc.Ok).money)
    }

    @Test fun roundedMeansMoreThanShown() {
        assertFalse(Exact.rounded(BigDecimal("2.500000"), 6))
        assertTrue(Exact.rounded(BigDecimal("0.3333333"), 6))
        assertFalse(Exact.rounded(BigDecimal("11.61"), 2))
        assertTrue(Exact.rounded(BigDecimal("11.605"), 2))
    }
}
