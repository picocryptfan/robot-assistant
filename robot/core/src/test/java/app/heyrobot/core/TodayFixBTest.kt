// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.backup.BackupCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** today-fix-b: review findings 5 and 6. */
class TodayFixBTest {
    private fun route(q: String) = RoutineRouter.route(q, ZonedDateTime.now(), emptyList())!!

    @Test fun showMyCalendarWithTodayOffGoesToTheModelAsIn12() {
        val c = route("show my calendar")
        assertEquals(RoutineCommand.Show(ShowPage.CALENDAR), c)
        assertNull(ShowCalendar.routed(c) { false })
        assertEquals(c, ShowCalendar.routed(c) { true })
        // History isn't Today's: it stays a routine phrase.
        val h = route("show my history")
        assertEquals(h, ShowCalendar.routed(h) { false })
    }

    @Test fun handsFreeShowMyCalendarIsTheDayLineAndOpensNothing() {
        val c = route("ouvre mon agenda")
        assertEquals(RoutineCommand.Today, ShowCalendar.handsFree(c, handsFree = true))
        assertEquals(c, ShowCalendar.handsFree(c, handsFree = false))
        val h = RoutineCommand.Show(ShowPage.HISTORY)
        assertEquals(h, ShowCalendar.handsFree(h, handsFree = true))
    }

    @Test fun someoneElsesBackupNeverRestoresToday() {
        assertTrue(BackupCategory.HOME in BackupCategory.ONLY_YOURS)
    }

    @Test fun anOlderBackupWithTodayOffKeepsItOn() {
        val here = HomeSettings().turnedOn()
        assertTrue(here.restored(HomeSettings()).today)
        assertTrue(HomeSettings().restored(here).today)
    }
}
