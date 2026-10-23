// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2 (job 4, design Step 3): the reading budget's arithmetic, where it applies, and what it cuts first. */
class PrefillBudgetTest {
    private val window = ContextWindow.MAX_TOKENS - ContextWindow.REPLY_RESERVE

    @Test fun theThresholdsComeFromTheTargetAndRobotsPrompt() {
        assertEquals(8.0, PrefillBudget.TARGET_SECONDS, 0.0)
        // Robot's whole prompt (1,500 tokens) in 8 s, and today's window (3,328) in 8 s.
        assertEquals(187.5, PrefillBudget.COMPACT_BELOW_TPS, 0.0)
        assertEquals(416.0, PrefillBudget.CAP_BELOW_TPS, 0.0)
        assertEquals(3_328, window)
        assertEquals(53.0, PrefillBudget.UNKNOWN_CPU_TPS, 0.0)
        assertTrue(PrefillBudget.MIN_HISTORY_TOKENS in 1 until window)
    }

    @Test fun theTpuAndTheGpuNeverGetABudget() {
        for (b in listOf(ModelBackend.TPU, ModelBackend.GPU, null)) {
            for (measured in listOf(null, 1.0, 20.0, 53.0, 186.0, 500.0, Double.NaN, -1.0)) {
                for (estimate in listOf(null, 1.0, 107.0, 300.0, 500.0)) {
                    val p = PrefillBudget.of(b, measured, estimate)
                    assertEquals("$b $measured $estimate", PrefillBudget.NONE, p)
                    assertFalse(p.compact)
                    assertEquals(emptyList<PromptCut>(), p.cuts)
                }
            }
        }
        assertEquals(-1, PrefillBudget.NONE.tokens)
    }

    @Test fun aMeasuredSpeedComesBeforeTheEstimate() {
        val p = PrefillBudget.of(ModelBackend.CPU, measuredTps = 53.0, estimateTps = 160.0)
        assertEquals(PrefillBudget(424, compact = true, readTps = 53.0, measured = true), p)
        // A faster measurement than the estimate wins too: a CPU measured at 300 tokens a second keeps the full prompt,
        // with only the history capped (2,400 tokens).
        val q = PrefillBudget.of(ModelBackend.CPU, measuredTps = 300.0, estimateTps = 107.0)
        assertEquals(PrefillBudget(2_400, compact = false, readTps = 300.0, measured = true), q)
        assertEquals(listOf(PromptCut.HISTORY), q.cuts)
    }

    @Test fun withoutAMeasurementTheEstimateThenTheSlowestKnownCpu() {
        assertEquals(PrefillBudget(856, compact = true, readTps = 107.0), PrefillBudget.of(ModelBackend.CPU, null, 107.0))
        for (bad in listOf(0.0, -1.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("$bad", PrefillBudget(856, compact = true, readTps = 107.0), PrefillBudget.of(ModelBackend.CPU, bad, 107.0))
            assertEquals("$bad", PrefillBudget(424, compact = true, readTps = 53.0), PrefillBudget.of(ModelBackend.CPU, bad, bad))
        }
        assertEquals(PrefillBudget(424, compact = true, readTps = 53.0), PrefillBudget.of(ModelBackend.CPU, null, null))
    }

    @Test fun tokensAreTheSpeedTimesTheTargetRoundedDown() {
        for ((tps, tokens) in listOf(0.01 to 0, 0.2 to 1, 1.0 to 8, 46.9 to 375, 70.0 to 560, 107.2 to 857, 160.0 to 1_280, 187.4 to 1_499, 415.9 to 3_327)) {
            val p = PrefillBudget.of(ModelBackend.CPU, tps, null)
            assertEquals("$tps", tokens, p.tokens)
            assertTrue(p.tokens >= 0)
        }
    }

    @Test fun theEdgesOfTheTwoSteps() {
        // Below 187.5 tokens a second: the compact form and the cap. From 187.5: the cap alone. From 416: nothing.
        assertTrue(PrefillBudget.of(ModelBackend.CPU, 187.49, null).compact)
        assertEquals(PromptCut.entries, PrefillBudget.of(ModelBackend.CPU, 187.49, null).cuts)
        val at = PrefillBudget.of(ModelBackend.CPU, 187.5, null)
        assertFalse(at.compact)
        assertEquals(1_500, at.tokens)
        assertEquals(listOf(PromptCut.HISTORY), at.cuts)
        assertEquals(3_327, PrefillBudget.of(ModelBackend.CPU, 415.99, null).tokens)
        assertEquals(PrefillBudget.NONE, PrefillBudget.of(ModelBackend.CPU, 416.0, null))
        assertEquals(PrefillBudget.NONE, PrefillBudget.of(ModelBackend.CPU, 5_000.0, null))
    }

    @Test fun theCutsComeInTheirOrder() {
        // History first (a cap alone, on a CPU that reads at least 187.5 tokens a second), then the compact form's four.
        assertEquals(listOf(PromptCut.HISTORY, PromptCut.EXAMPLES, PromptCut.PARAMETERS, PromptCut.MEMORY, PromptCut.OPENING), PromptCut.entries)
        assertEquals(listOf(PromptCut.HISTORY), PrefillBudget(2_000, compact = false).cuts)
        assertEquals(PromptCut.entries, PrefillBudget(400, compact = true).cuts)
        // Every budget that compacts caps the history too.
        for (tps in listOf(1.0, 53.0, 107.0, 160.0, 187.0)) {
            val p = PrefillBudget.of(ModelBackend.CPU, tps, null)
            assertTrue(p.compact && p.tokens >= 0 && p.cuts.first() == PromptCut.HISTORY)
        }
    }

    @Test fun withoutABudgetTheHistoryIsTodays() {
        for (fixed in listOf(0, 310, 1_171, 2_600, 3_400)) for (turn in listOf(8, 40, 900)) for (max in listOf(2_048, 3_000, 4_096)) {
            assertEquals(ContextWindow.historyBudget(fixed, turn, max), PrefillBudget.history(fixed, turn, max, -1))
            assertEquals(ContextWindow.historyBudget(fixed, turn, max), PrefillBudget.NONE.history(fixed, turn, max))
        }
    }

    @Test fun aBudgetCapsTheHistoryButKeepsTheLastExchangeOrTwo() {
        val fixed = 900
        val turn = 30
        // A budget with room: what's left of it after the fixed part and the new turn.
        assertEquals(1_000, PrefillBudget.history(fixed, turn, 4_096, 1_930))
        // Never below the floor, however slow the reading ...
        assertEquals(PrefillBudget.MIN_HISTORY_TOKENS, PrefillBudget.history(fixed, turn, 4_096, 0))
        assertEquals(PrefillBudget.MIN_HISTORY_TOKENS, PrefillBudget.history(fixed, turn, 4_096, 424))
        assertEquals(PrefillBudget.MIN_HISTORY_TOKENS, PrefillBudget.history(fixed, turn, 4_096, fixed + turn + PrefillBudget.MIN_HISTORY_TOKENS))
        assertEquals(PrefillBudget.MIN_HISTORY_TOKENS + 1, PrefillBudget.history(fixed, turn, 4_096, fixed + turn + PrefillBudget.MIN_HISTORY_TOKENS + 1))
        // ... and never above today's window.
        val today = ContextWindow.historyBudget(fixed, turn, 4_096)
        assertEquals(today, PrefillBudget.history(fixed, turn, 4_096, 100_000))
        assertEquals(today, PrefillBudget.history(fixed, turn, 4_096, Int.MAX_VALUE))
        // A window already too small for the floor stays as small as today (ContextWindow.fit keeps nothing at 0 or less).
        assertEquals(ContextWindow.historyBudget(3_300, 120, 4_096), PrefillBudget.history(3_300, 120, 4_096, 300))
        assertTrue(PrefillBudget.history(3_300, 120, 4_096, 300) < 0)
        assertEquals(150, PrefillBudget.history(3_000, 178, 4_096, 0))
        // The budget's own method is the same arithmetic.
        val p = PrefillBudget.of(ModelBackend.CPU, 107.0, null)
        assertEquals(PrefillBudget.history(fixed, turn, 4_096, 856), p.history(fixed, turn, 4_096))
    }

    @Test fun aCappedHistoryKeepsTheNewestWholeExchanges() {
        val history = (1..20).flatMap { i ->
            listOf(PromptTurn(PromptRole.USER, "Question $i about the weekend plans and the shopping list"),
                PromptTurn(PromptRole.MODEL, "Answer $i: buy rice, beans, peppers and limes, and plan dinner for Sunday at six."))
        }
        val p = PrefillBudget.of(ModelBackend.CPU, 107.0, null)
        val fixed = 700
        val turn = 20
        val budget = p.history(fixed, turn)
        val kept = ContextWindow.fit(history, budget)
        assertTrue(kept.sumOf(TokenEstimate::turn) <= budget)
        assertTrue(kept.isNotEmpty() && kept.size < history.size)
        assertEquals(history.takeLast(kept.size), kept)
        assertEquals(PromptRole.USER, kept.first().role)
        // Without the budget, the same history keeps far more.
        assertTrue(ContextWindow.fit(history, ContextWindow.historyBudget(fixed, turn)).size > kept.size * 3)
    }

    /** Robot's estimates as the budget reads them: the low end of each range, in tokens a second. */
    @Test fun theEstimatesReadingSpeeds() {
        val pixel10 = Phone("Google", "Pixel 10 Pro", "Google", "Tensor G5", 15_500_000_000L)
        val pixel8 = Phone("Google", "Pixel 8", "Google", "Tensor G3", 7_600_000_000L)
        val galaxy = Phone("samsung", "SM-S931W", "QTI", "SM8750", 11_500_000_000L)
        assertEquals(500.0, Speeds.readTps(BrainModel.FAST, ModelBackend.TPU, pixel10)!!, 0.0)
        assertEquals(160.0, Speeds.readTps(BrainModel.GENERAL, ModelBackend.CPU, pixel10)!!, 0.0)
        assertEquals(107.0, Speeds.readTps(BrainModel.GENERAL, ModelBackend.CPU, pixel8)!!, 0.0)
        assertEquals(107.0, Speeds.readTps(BrainModel.GENERAL, ModelBackend.CPU, galaxy)!!, 0.0)
        assertEquals(300.0, Speeds.readTps(BrainModel.GENERAL, ModelBackend.GPU, galaxy)!!, 0.0)
        assertEquals(70.0, Speeds.readTps(BrainModel.SMARTER, ModelBackend.CPU, pixel10)!!, 0.0)
        assertEquals(46.0, Speeds.readTps(BrainModel.UNCENSORED, ModelBackend.CPU, pixel8)!!, 0.0)
        assertNull(Speeds.readTps(BrainModel.OWN, ModelBackend.CPU, pixel8))
        // Every CPU estimate Robot has is a slow path: the compact form.
        for (p in listOf(pixel10, pixel8, galaxy)) for (m in BrainModel.entries) {
            val b = PrefillBudget.of(ModelBackend.CPU, null, Speeds.readTps(m, ModelBackend.CPU, p))
            assertTrue("$m ${p.model}", b.compact && b.tokens in 0 until 1_500)
        }
    }

    /**
     * Where each model runs on each phone (ModelCatalogue.backend, the GPU on or off, failed or not): on the TPU and the GPU
     * no budget at all, whatever was measured; on the CPU a budget, from the estimate.
     */
    @Test fun everyPixelsFastPathKeepsItsPromptAndEveryCpuGetsTheBudget() {
        val phones = Phones.PIXELS.map { Phone("Google", it.first, totalRam = 7_600_000_000L) } + listOf(
            Phone("Google", "Pixel 8", "Google", "Tensor G3", 7_600_000_000L),
            Phone("Google", "Pixel 9 Pro", "Google", "zumapro", 15_500_000_000L),
            Phone("Google", "Pixel 10 Pro", "Google", "Tensor G5", 15_500_000_000L),
            Phone("Google", "Pixel 11", "Google", "Tensor G6", 11_500_000_000L),
            Phone("samsung", "SM-S931W", "QTI", "SM8750", 11_500_000_000L),
            Phone("samsung", "SM-S921B", "Samsung", "s5e9945", 7_600_000_000L),
            Phone("Xiaomi", "23129RN51X", "Mediatek", "MT6769", 3_700_000_000L),
            Phone("", "", "", "", 0),
        )
        var cpu = 0
        var fast = 0
        for (p in phones) for (m in BrainModel.entries) for (on in listOf(true, false)) for (failed in listOf(false, true)) {
            val backend = ModelCatalogue.backend(m, p, on, failed)
            for (measured in listOf(null, 20.0, 150.0, 600.0)) {
                val b = PrefillBudget.of(backend, measured, Speeds.readTps(m, backend, p))
                if (backend == ModelBackend.CPU) {
                    cpu++
                    if (measured == 600.0) assertEquals(PrefillBudget.NONE, b) else assertTrue("${p.model} $m", b.compact && b.tokens >= 0)
                } else {
                    fast++
                    assertEquals("${p.model} $m $backend", PrefillBudget.NONE, b)
                }
            }
        }
        assertTrue("cpu $cpu fast $fast", cpu > 100 && fast > 100)
    }
}
