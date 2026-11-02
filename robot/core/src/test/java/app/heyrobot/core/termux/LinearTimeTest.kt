package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LinearTime on a made-up clock: the only time that passes is what each fake input says it cost. */
class LinearTimeTest {
    private var clock = 0L
    private val inputs = { div: Int -> listOf("a".repeat(800_000 / div)) }
    private fun run(costMs: (Int) -> Double) =
        LinearTime.assertHolds(inputs, { clock }) { s -> clock += (costMs(s.length) * 1_000_000).toLong() }

    @Test fun quadraticWorkFails() {
        val e = assertThrows(AssertionError::class.java) { run { n -> n.toDouble() * n / 1e9 } }
        assertTrue(e.message, e.message!!.contains("grows faster than linear"))
    }

    @Test fun cubicWorkFails() {
        assertThrows(AssertionError::class.java) { run { n -> n.toDouble() * n * n / 1e15 } }
    }

    @Test fun slowLinearWorkPasses() = run { n -> n / 100.0 } // 8 s for the big run, as under heavy load.

    @Test fun fastWorkPassesWithoutTheSmallRuns() {
        var runs = 0
        run { runs++; 1.0 }
        assertEquals(2, runs) // The warm-up and one big run.
    }

    @Test fun onePauseCannotFailIt() {
        var bigRuns = 0
        run { n -> if (n == 800_000) { if (bigRuns++ == 0) 60_000.0 else 2_000.0 } else 250.0 }
        assertEquals(2, bigRuns)
    }

    @Test fun aLinearSlowdownPastTheCeilingFails() {
        val e = assertThrows(AssertionError::class.java) { run { n -> n / 5.0 } } // 160 s.
        assertTrue(e.message, e.message!!.contains("ceiling"))
    }
}
