// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Review L1: « montre-moi l'accès rapide » is answered in French, as its shorter forms are. */
class QuickFrenchReplyTest {
    private val now = ZonedDateTime.parse("2026-11-09T09:00:00-04:00")

    @Test fun everyFrenchShowQuickAccessIsAnsweredInFrench() {
        for (s in listOf("Montre-moi l'accès rapide", "montre moi l'accès rapide", "montre l'accès rapide", "Ouvre l'accès rapide")) {
            val cmd = RoutineRouter.route(s, now)
            assertEquals(s, RoutineCommand.Show(ShowPage.QUICK), cmd)
            assertTrue(s, RoutineRouter.french(s, cmd!!))
        }
    }
}
