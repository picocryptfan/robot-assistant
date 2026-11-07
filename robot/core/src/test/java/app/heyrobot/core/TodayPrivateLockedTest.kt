// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// Today fix (review-today-first, finding 1): private events locked away never make the day sound empty.
class TodayPrivateLockedTest {
    private val zone = ZoneId.of("America/Halifax")
    private val at = ZonedDateTime.of(2026, 11, 3, 15, 0, 0, 0, zone)
    private fun ms(h: Int) = at.withHour(h).toInstant().toEpochMilli()
    private val unlock = "Unlock your phone to hear your private events."

    private fun line(events: List<CalendarEvent>?, locked: Boolean, lang: Lang = Lang.EN) =
        BriefComposer.shown(Today.line(Today.picture(DayInput(at, events, privateLocked = locked)), lang), lang)

    @Test fun theDayLineSaysToUnlockInsteadOfNothingElse() {
        assertEquals("Nothing else today.", line(emptyList(), locked = false))
        assertEquals(unlock, line(emptyList(), locked = true))
        assertFalse(line(listOf(CalendarEvent(ms(9), ms(10), false, "Standup", null)), locked = true).contains("Nothing else"))
        assertEquals(unlock, BriefComposer.shown(Today.rightNow(Today.picture(DayInput(at, emptyList(), privateLocked = true)), Lang.EN), Lang.EN))
        assertEquals("Déverrouille ton téléphone pour entendre tes événements privés.", line(emptyList(), locked = true, lang = Lang.FR))
    }

    @Test fun aPhoneEventStillComesFirst() {
        val l = line(listOf(CalendarEvent(ms(16), ms(17), false, "Dentist", null)), locked = true)
        assertTrue(l, l.startsWith("Next: Dentist at 4") && l.endsWith(unlock))
    }

    @Test fun theBriefsCalendarPartNeverSaysNothingOnYourCalendar() {
        fun cal(events: List<CalendarEvent>?, locked: Boolean) = BriefComposer.lines(BriefItem.CALENDAR,
            BriefInput(at, Lang.EN, listOf(BriefItem.CALENDAR), events = events, privateLocked = locked), place = true).joinToString(" ") { it.text }
        assertEquals("Nothing on your calendar today.", cal(emptyList(), locked = false))
        assertEquals(unlock, cal(emptyList(), locked = true))
        val one = cal(listOf(CalendarEvent(ms(16), ms(17), false, "Dentist", null)), locked = true)
        assertTrue(one, one.startsWith("You have 1 event today") && one.endsWith(unlock))
    }
}
