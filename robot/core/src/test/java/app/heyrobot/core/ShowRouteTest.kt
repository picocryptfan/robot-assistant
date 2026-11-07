// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// H3: "show my history" and "show my calendar", English and French, open those pages; a locked phone is refused first.
class ShowRouteTest {
    private val now = ZonedDateTime.of(2026, 11, 3, 19, 0, 0, 0, ZoneId.of("America/Halifax"))
    private fun route(s: String) = RoutineRouter.route(s, now, emptyList(), id = "d")
    private val history = RoutineCommand.Show(ShowPage.HISTORY)
    private val calendar = RoutineCommand.Show(ShowPage.CALENDAR)

    @Test fun english() {
        listOf("Show my history", "show me my history.", "Hey Robot, open my history", "show my conversations").forEach { assertEquals(it, history, route(it)) }
        listOf("Show my calendar", "open my calendar please").forEach { assertEquals(it, calendar, route(it)) }
        listOf("show my history", "show my calendar").forEach { assertEquals(it, null, RoutineRouter.lang(it, route(it)!!)) }
    }

    @Test fun french() {
        listOf("Montre-moi mon historique", "ouvre mon historique", "Affiche mes conversations").forEach {
            assertEquals(it, history, route(it)); assertEquals(it, Lang.FR, RoutineRouter.lang(it, history))
        }
        listOf("Montre-moi mon agenda", "ouvre mon calendrier").forEach {
            assertEquals(it, calendar, route(it)); assertEquals(it, Lang.FR, RoutineRouter.lang(it, calendar))
        }
    }

    @Test fun longerTalkIsLeftToTheModel() = listOf("show my history teacher the essay", "what's on my calendar tomorrow", "my history exam is today")
        .forEach { assertTrue(it, route(it) !is RoutineCommand.Show) }

    @Test fun lockedPhoneIsRefused() {
        assertEquals(null, RoutineLockedPhone.refuseText(history, locked = false))
        assertTrue(RoutineLockedPhone.refuse(calendar, locked = true, fr = true)!!.startsWith("Déverrouille"))
    }

    @Test fun logCode() = assertEquals(RoutineCommandCode.SHOW, RoutineCommandCode.of(calendar))
}
