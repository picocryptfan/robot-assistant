package app.heyrobot.core.termux

import org.junit.Assert.fail

/**
 * Catches catastrophic backtracking by how the time grows, not by the clock alone, so a busy machine can't fail it.
 * A flat limit did: 1.3 s with nothing else running, 12.6 s of 10 s at load 15 (1 Nov 2026).
 *
 * For each input, one run at full size is timed against [FACTOR] runs at 1/[FACTOR] of it, one after the other, so
 * both see the same load. In linear time they cost about the same (measured: 0.9 to 1.0 on every input); in quadratic
 * time the big run costs [FACTOR] times as much. A big run under [FLOOR_MS] is too short to compare and passes: most
 * inputs take under 100 ms with nothing else running, the slowest 188 ms. A pause during a round can't fail the test,
 * because only growth that shows in all [ROUNDS] rounds does. [CEILING_MS] still catches a linear slowdown, at about
 * 90 times the measured total. Checked by adding a scan from every position to SecretMask.mask, which costs only
 * 365 ms at 200,000 characters: hostileInputStaysFast failed on it (ratio 3.6).
 */
internal object LinearTime {
    const val FACTOR = 8
    const val FLOOR_MS = 100L
    const val MAX_RATIO = 3.0
    const val ROUNDS = 3
    const val CEILING_MS = 120_000L

    /** [inputs] gives the hostile inputs with their sizes divided by its argument. */
    fun assertHolds(inputs: (Int) -> List<String>, now: () -> Long = System::nanoTime, work: (String) -> Unit) {
        val big = inputs(1)
        val small = inputs(FACTOR)
        small.forEach(work) // Warm up, so the small runs don't carry the start-up cost.
        val start = now()
        for (i in big.indices) assertGrowth(i, big[i], small[i], now, work)
        val ms = (now() - start) / 1_000_000
        if (ms >= CEILING_MS) fail("took $ms ms, ceiling $CEILING_MS ms")
    }

    private fun assertGrowth(i: Int, big: String, small: String, now: () -> Long, work: (String) -> Unit) {
        var last = ""
        repeat(ROUNDS) {
            val t0 = now()
            work(big)
            val t1 = now()
            val bigMs = (t1 - t0) / 1_000_000
            if (bigMs < FLOOR_MS) return
            repeat(FACTOR) { work(small) }
            val smallTotal = now() - t1
            if ((t1 - t0).toDouble() < smallTotal * MAX_RATIO) return
            last = "$bigMs ms, $FACTOR runs at 1/$FACTOR size ${smallTotal / 1_000_000} ms"
        }
        fail("input $i (${big.length} characters) grows faster than linear: $last")
    }
}
