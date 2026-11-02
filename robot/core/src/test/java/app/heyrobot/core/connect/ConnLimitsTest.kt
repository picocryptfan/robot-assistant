// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillState
import app.heyrobot.core.skills.SkillStateFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D16: 30 a minute (or yours), 500 a day, a skill's runs an hour; counts and times only, bounded, and a clock going back never locks you out. */
class ConnLimitsTest {
    private val c = ConnFixtures.ntfy()
    private val t0 = 1_761_000_000_000L

    @Test fun thirtyAMinute() {
        var s = LimitState()
        for (i in 0 until 30) {
            assertNull("call $i", ConnLimits.connection(s, c, t0 + i * 1000L))
            s = ConnLimits.recordCall(s, c.id, t0 + i * 1000L)
        }
        assertEquals(ConnFailure.LIMIT_MINUTE, ConnLimits.connection(s, c, t0 + 30_000L))
        // A minute after the first, one slot is free again.
        assertNull(ConnLimits.connection(s, c, t0 + 60_000L))
        // Your own number, 1 to 60.
        assertEquals(ConnFailure.LIMIT_MINUTE, ConnLimits.connection(ConnLimits.recordCall(LimitState(), c.id, t0), c.copy(perMinute = 1), t0 + 1))
        assertEquals("Robot's limit for your Jarvis's ntfy was reached: 30 a minute.", ConnFailure.LIMIT_MINUTE.text(c.name, limit = 30).en())
    }

    @Test fun fiveHundredADay() {
        var s = LimitState()
        for (i in 0 until 500) s = ConnLimits.recordCall(s, c.id, t0 + i * 120_000L)
        val end = t0 + 499 * 120_000L
        assertEquals(500, s.calls.getValue(c.id).size)
        assertEquals(ConnFailure.LIMIT_DAY, ConnLimits.connection(s, c, end + 120_000L))
        // A day after the first call, it frees up.
        assertNull(ConnLimits.connection(s, c, t0 + ConnLimits.DAY_MS))
        // Storage stays bounded: never more than 500 a connection, nothing older than a day.
        for (i in 0 until 50) s = ConnLimits.recordCall(s, c.id, end + i)
        assertEquals(500, s.calls.getValue(c.id).size)
        s = ConnLimits.recordCall(s, c.id, end + 2 * ConnLimits.DAY_MS)
        assertEquals(listOf(end + 2 * ConnLimits.DAY_MS), s.calls.getValue(c.id))
    }

    @Test fun aClockGoingBackNeverLocksYouOut() {
        var s = LimitState()
        for (i in 0 until 30) s = ConnLimits.recordCall(s, c.id, t0 + i)
        assertEquals(ConnFailure.LIMIT_MINUTE, ConnLimits.connection(s, c, t0 + 100))
        // The clock goes back an hour: times after now are dropped, not counted.
        val back = t0 - ConnLimits.HOUR_MS
        assertNull(ConnLimits.connection(s, c, back))
        s = ConnLimits.recordCall(s, c.id, back)
        assertEquals(listOf(back), s.calls.getValue(c.id))
    }

    @Test fun aSkillsRunsAnHour() {
        val k = SkillFixtures.goodNight()
        var s = LimitState()
        for (i in 0 until 12) {
            assertTrue(ConnLimits.skill(s, k, t0 + i))
            s = ConnLimits.recordRun(s, k.id, t0 + i)
        }
        assertTrue(!ConnLimits.skill(s, k, t0 + 12))
        assertTrue(ConnLimits.skill(s, k, t0 + ConnLimits.HOUR_MS))
        assertTrue(ConnLimits.skill(s, k.copy(perHour = 13), t0 + 12))
    }

    @Test fun pruneKeepsOnlyWhatExists() {
        var s = ConnLimits.recordCall(LimitState(), c.id, t0)
        s = ConnLimits.recordCall(s, "c-gone01", t0)
        s = ConnLimits.recordRun(s, "s-gone01", t0)
        s = ConnLimits.recordRun(s, "s-night1", t0)
        val p = ConnLimits.prune(s, t0 + 1, setOf(c.id), setOf("s-night1"))
        assertEquals(setOf(c.id), p.calls.keys)
        assertEquals(setOf("s-night1"), p.runs.keys)
        assertEquals(LimitState(), ConnLimits.prune(s, t0 + 2 * ConnLimits.DAY_MS, setOf(c.id), setOf("s-night1")))
    }

    /** Kept in skills_state.json: counts and times only, read back leniently, capped. */
    @Test fun inSkillsState() {
        var l = ConnLimits.recordCall(LimitState(), c.id, t0)
        l = ConnLimits.recordRun(l, "s-night1", t0 + 5)
        val st = SkillState(limits = l)
        val text = SkillStateFile.encode(st)
        assertEquals(st, SkillStateFile.decode(text))
        assertTrue(text.contains("\"limits\""))
        // Nothing to keep: the file is as before.
        assertTrue(!SkillStateFile.encode(SkillState()).contains("limits"))
        // What can't be read is left out: a bad id, a wrong type, too many.
        val bad = """{"v":1,"runs":{},"limits":{"calls":{"bad id":[1],"c-ntfy01":[1,"x"],"c-ok0001":[1,2]},"runs":{"s-night1":[${(0..60).joinToString(",")}]}}}"""
        assertEquals(LimitState(calls = mapOf("c-ok0001" to listOf(1L, 2L))), SkillStateFile.decode(bad).limits)
        // A skill deleted takes its runs with it.
        val kept = SkillStateFile.record(st, "s-other1", app.heyrobot.core.skills.SkillRunRecord(6L, 1, 1), setOf("s-other1"))
        assertEquals(emptyMap<String, List<Long>>(), kept.limits.runs)
        assertEquals(l.calls, kept.limits.calls)
    }
}
