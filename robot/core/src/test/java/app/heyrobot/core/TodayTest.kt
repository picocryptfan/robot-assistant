// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

// Today's day line (H1): clock edges, midnight, all-day, empty, no permission, the evening's tomorrow, timers, French.
class TodayTest {
    private val zone = ZoneId.of("America/Halifax")
    private fun at(h: Int, m: Int = 0, day: Int = 3) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone)
    private fun ms(h: Int, m: Int = 0, day: Int = 3) = at(h, m, day).toInstant().toEpochMilli()
    private fun ev(title: String, h: Int, m: Int = 0, mins: Int = 60, day: Int = 3) = CalendarEvent(ms(h, m, day), ms(h, m, day) + mins * 60_000L, false, title, null)
    private fun allDay(title: String, day: Int = 3): CalendarEvent {
        val d = LocalDate.of(2026, 11, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarEvent(d, d + 86_400_000L, true, title, null)
    }
    private fun line(now: ZonedDateTime, events: List<CalendarEvent>?, lang: Lang = Lang.EN, reminders: List<String> = emptyList(), todo: Int? = null,
        timers: List<Long> = emptyList()): String = plain(BriefComposer.shown(Today.line(Today.picture(DayInput(now, events, reminders, todo, timers)), lang), lang))
    private fun now(now: ZonedDateTime, events: List<CalendarEvent>?, timers: List<Long> = emptyList()): String =
        BriefComposer.shown(Today.rightNow(Today.picture(DayInput(now, events, timers = timers)), Lang.EN), Lang.EN)
    private fun plain(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2019', '\'')

    private val day = listOf(ev("Standup", 9, 30, 30), ev("Dentist", 14, 30), ev("Pick up the car", 17), ev("Standup", 9, 30, 30, day = 4))

    @Test fun morningSaysNextAndTheCountAfter() {
        assertEquals("Next: Standup at 9:30, then 2 more.", line(at(8), day))
    }

    @Test fun underWaySaysNowUntilThenNext() {
        assertEquals("Now: Standup, until 10. Next: Dentist at 2:30, then 1 more.", line(at(9, 45), day))
        assertEquals("Now: Standup, until 10.", now(at(9, 45), day))
    }

    @Test fun anEventThatJustEndedIsGone() {
        assertEquals("Next: Dentist at 2:30, then 1 more.", line(at(10), day))
    }

    @Test fun lastEventThenNothingElse() {
        assertEquals("Now: Pick up the car, until 6.", line(at(17, 30), day))
        assertEquals("Nothing else today. Tomorrow starts with Standup at 9:30.", line(at(18, 0), day))
    }

    @Test fun eveningWithEventsLeftStillGivesTomorrow() {
        val evening = listOf(ev("Concert", 20), ev("Gym", 7, day = 4))
        assertEquals("Next: Concert at 8. Tomorrow starts with Gym at 7.", line(at(18, 30), evening))
        assertEquals("Next: Concert at 8.", line(at(16), evening))
    }

    @Test fun emptyDayIsAFinishedLine() {
        assertEquals("Nothing else today.", line(at(10), emptyList()))
        assertEquals("Nothing else today.", now(at(10), emptyList()))
        assertEquals("Nothing else today. Nothing on your calendar tomorrow.", line(at(21), emptyList()))
    }

    @Test fun allDayEventsAreNamedAndTomorrowsAllDayFirst() {
        val events = listOf(allDay("Remembrance Day", 11), ev("Parade", 10, day = 11), allDay("Holiday", 12))
        assertEquals("All day: Remembrance Day. Next: Parade at 10.", line(at(8, day = 11), events))
        assertEquals("All day: Remembrance Day. Nothing else today. Tomorrow: Holiday, all day.", line(at(12, day = 11), events))
    }

    @Test fun justBeforeMidnightTomorrowIsTheNextDay() {
        val events = listOf(ev("Night shift", 0, 0, 480, day = 4), ev("Late call", 23, 30, 15))
        assertEquals("Next: Late call at 11:30. Tomorrow starts with Night shift at midnight.", line(at(23, 0), events))
        assertEquals("Now: Night shift, until 8.", line(at(0, 5, day = 4), events))
    }

    @Test fun overnightEventSaysNowWithoutAnEnd() {
        assertEquals("Now: Conference.", line(at(15), listOf(ev("Conference", 14, mins = 24 * 60))))
    }

    @Test fun noPermissionSaysSoAndNeverNothingElse() {
        val s = line(at(10), null, reminders = listOf("bring the passport"))
        assertEquals("Robot can't read your calendar. Reminder: bring the passport.", s)
        assertFalse(s.contains("Nothing else"))
        assertEquals("Robot can't read your calendar.", now(at(10), null))
    }

    @Test fun remindersTodoAndTimers() {
        val t = ms(15, 15)
        assertEquals("Your timer ends at 3:15. Next: Dentist at 2:30, then 1 more. 2 reminders today, the first: call the bank. 3 things on your to-do list.",
            line(at(14, 5), day, reminders = listOf("call the bank", "water the plants"), todo = 3, timers = listOf(t, ms(13))))
        assertEquals("Your timer ends at 3:15.", now(at(14, 5), day, listOf(t)))
        assertEquals("2 timers running; the first ends at 3:15. Nothing else today.", line(at(15), emptyList(), timers = listOf(ms(15, 40), t)))
    }

    @Test fun longTitlesAreCutAndEmptyOnesNamed() {
        val long = "x".repeat(200)
        assertTrue(line(at(8), listOf(ev(long, 9))).length < 100)
        assertEquals("Next: an untitled event at 9.", line(at(8), listOf(ev("  ", 9))))
    }

    @Test fun french() {
        assertEquals("En ce moment : Standup, jusqu'à 10 h. Ensuite : Dentist à 14 h 30, puis 1 autre.", line(at(9, 45), day, Lang.FR))
        assertEquals("Rien d'autre aujourd'hui. Demain commence avec Standup à 9 h 30.", line(at(19), day, Lang.FR))
        assertEquals("Rappel : appeler la banque. 1 chose sur ta liste de choses à faire. Rien d'autre aujourd'hui. Rien à ton agenda demain.",
            line(at(20), emptyList(), Lang.FR, listOf("appeler la banque"), todo = 1))
    }

    @Test fun timersRecordKeepsOnlyRunningOnes() {
        val list = TodayTimers.add(listOf(100L, 5_000L), 9_000L, nowMs = 1_000L)
        assertEquals(listOf(5_000L, 9_000L), list)
        assertEquals(list, TodayTimers.decode(TodayTimers.encode(list)))
        assertEquals(emptyList<Long>(), TodayTimers.decode("{\"v\":2,\"ends\":[5]}"))
        assertEquals(emptyList<Long>(), TodayTimers.decode("not json"))
        assertEquals(TodayTimers.MAX, (1..20).fold(emptyList<Long>()) { l, i -> TodayTimers.add(l, 10_000L + i, 0) }.size)
    }
}
