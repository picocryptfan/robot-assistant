// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

/** Review L2: on Quick access, a move that names Today ("… on today", « … sur aujourd'hui ») moves Today, not a button. */
class QuickTodayNamedTest {
    private val now = ZonedDateTime.parse("2026-11-09T09:00:00-04:00")

    @Test fun namingTodayOnQuickAccessMovesToday() {
        for (s in listOf("put my calendar first on today", "mets mon agenda en premier sur aujourd'hui", "put my lists last on my day",
            "move my notes up on my today page", "descends mes listes dans ma journée")) {
            val cmd = RoutineRouter.route(s, now) as? RoutineCommand.Arrange
            assertNotNull(s, cmd)
            assertNull(s, QuickArrange.onQuick(cmd!!, HomeChoice.QUICK))
        }
    }
}
