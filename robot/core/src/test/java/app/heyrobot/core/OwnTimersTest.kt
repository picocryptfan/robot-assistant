// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTimersTest {
    private val t0 = 1_800_000_000_000L
    private fun add(l: List<OwnTimer>, name: String, s: Long, now: Long = t0) = (OwnTimers.add(l, name, s, now) as OwnTimers.Added.Ok).timers

    // A missed ring is the worst failure: these come first.
    @Test fun aRestartAfterTheEndStillRingsLateWithinTheHour() {
        val l = OwnTimers.decode(OwnTimers.encode(add(emptyList(), "pasta", 540)))
        val boot = t0 + 540_000 + 20 * 60_000
        assertEquals(listOf("pasta"), OwnTimers.due(l, boot).map { it.name })
        assertTrue(OwnTimers.ringsAt(OwnTimers.due(l, boot).single(), boot))
        assertEquals(t0 + 540_000, OwnTimers.nextAt(l))
    }

    @Test fun overAnHourLateItIsToldAsMissedNotRung() {
        val l = add(emptyList(), "pasta", 60)
        val boot = t0 + 60_000 + OwnTimers.LATE_MS + 1
        val due = OwnTimers.due(l, boot).single()
        assertFalse(OwnTimers.ringsAt(due, boot))
        val after = OwnTimers.rang(l, setOf(due.id), boot, missed = setOf(due.id))
        assertTrue(OwnTimers.ringing(after, boot).isEmpty())
        assertTrue(OwnTimers.kept(after, boot).isEmpty())
    }

    @Test fun anOverdueTimerIsKeptUntilItRingsHoweverLongTheGap() {
        val l = add(emptyList(), "eggs", 60)
        assertEquals(1, OwnTimers.kept(l, t0 + 7 * 86_400_000L).size)
    }

    @Test fun theListLivesOnDiskWholeSoAKilledProcessLosesNothing() {
        val l = OwnTimers.rang(add(add(emptyList(), "pasta", 540), "", 60), setOf(2), t0 + 60_000)
        assertEquals(l, OwnTimers.decode(OwnTimers.encode(l)))
        assertEquals(emptyList<OwnTimer>(), OwnTimers.decode("not json"))
    }

    @Test fun severalAtOnceRingInTurnAndTheAlarmGoesToTheNext() {
        var l = add(add(add(emptyList(), "rice", 180), "eggs", 120), "pasta", 540)
        assertEquals(t0 + 120_000, OwnTimers.nextAt(l))
        val now = t0 + 120_000
        l = OwnTimers.rang(l, OwnTimers.due(l, now).map { it.id }.toSet(), now)
        assertEquals(listOf("eggs"), OwnTimers.ringing(l, now).map { it.name })
        assertEquals(t0 + 180_000, OwnTimers.nextAt(l))
        assertEquals(listOf("rice", "pasta"), OwnTimers.running(l, now).map { it.name })
    }

    @Test fun aRingStopsByItselfAfterTenMinutes() {
        val l = OwnTimers.rang(add(emptyList(), "", 60), setOf(1), t0 + 60_000)
        assertEquals(1, OwnTimers.ringing(l, t0 + 60_000 + OwnTimers.RING_MS - 1).size)
        assertTrue(OwnTimers.ringing(l, t0 + 60_000 + OwnTimers.RING_MS).isEmpty())
        assertTrue(OwnTimers.ringing(OwnTimers.stopped(l, setOf(1)), t0 + 61_000).isEmpty())
    }

    @Test fun namesStayApartAndTheLimitsHold() {
        val l = add(add(emptyList(), "pasta", 60), "Pasta", 60)
        assertEquals(listOf("pasta", "Pasta 2"), l.map { it.name })
        assertEquals(listOf(1, 2), l.map { it.id })
        var many = emptyList<OwnTimer>()
        repeat(OwnTimers.MAX) { many = add(many, "t$it", 60) }
        assertEquals(OwnTimers.Added.Full, OwnTimers.add(many, "one more", 60, t0))
        assertEquals(OwnTimers.Added.TooLong, OwnTimers.add(emptyList(), "", OwnTimers.MAX_SECONDS + 1, t0))
        assertNull(OwnTimers.nextAt(OwnTimers.cancelled(l, setOf(1, 2))))
    }

    @Test fun findMatchesTheWordsNotTheArticlesOrAccents() {
        val l = add(add(emptyList(), "pâtes", 60), "boiled eggs", 60)
        assertEquals(listOf("pâtes"), OwnTimers.find(l, "les pates").map { it.name })
        assertEquals(listOf("boiled eggs"), OwnTimers.find(l, "the eggs").map { it.name })
        assertEquals(listOf("boiled eggs"), OwnTimers.find(l, "egg timer").map { it.name })
        assertTrue(OwnTimers.find(l, "rice").isEmpty())
    }

    @Test fun aClockChangeDoesNotMoveATimerSetThisBootButAfterARebootTheWallClockEndStands() {
        val wall = 1_800_000_000_000L
        val t = (OwnTimers.add(emptyList(), "pasta", 600, wall) as OwnTimers.Added.Ok).timers
        val stamped = OwnTimers.rebase(t, wall, 50_000, 4)
        // Ten seconds on, the clock is set an hour ahead: 590 seconds are still left.
        val moved = wall + 3_600_000 + 10_000
        assertEquals(moved + 590_000, OwnTimers.rebase(stamped, moved, 60_000, 4).single().ends)
        // After a reboot, elapsed time starts again: the wall-clock end stands.
        assertEquals(wall + 600_000, OwnTimers.rebase(stamped, moved, 1_000, 5).single().ends)
        // No boot count: wall clock only.
        assertEquals(wall + 600_000, OwnTimers.rebase(stamped, moved, 60_000, -1).single().ends)
        assertEquals(stamped, OwnTimers.decode(OwnTimers.encode(stamped)))
    }
}
