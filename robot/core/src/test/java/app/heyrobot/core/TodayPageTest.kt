// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

// The Today page (H2): what it shows and in what order, kept short; the week strip; the note's quick add; its settings file.
class TodayPageTest {
    private val zone = ZoneId.of("America/Halifax")
    private fun at(h: Int, m: Int = 0, day: Int = 3) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone)
    private fun ms(h: Int, m: Int = 0, day: Int = 3) = at(h, m, day).toInstant().toEpochMilli()
    private fun ev(title: String, h: Int, m: Int = 0, mins: Int = 60, day: Int = 3) = CalendarEvent(ms(h, m, day), ms(h, m, day) + mins * 60_000L, false, title, null)
    private fun allDay(title: String, day: Int = 3): CalendarEvent {
        val d = LocalDate.of(2026, 11, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarEvent(d, d + 86_400_000L, true, title, null)
    }
    private fun view(now: ZonedDateTime, events: List<CalendarEvent>?, reminders: List<String> = emptyList(), lists: List<ListGlance> = emptyList(),
        note: NoteGlance? = null, loose: Int = 0, robots: Int = 0) =
        TodayPage.view(TodayInput(Today.picture(DayInput(now, events, reminders)), events, lists, loose, robots, note))

    @Test fun rowsAreTodaysFromNowOnAllDayFirstAndCutAtFour() {
        val events = listOf(ev("Standup", 9, 30, 30), ev("Dentist", 14, 30), ev("Car", 17), ev("Gym", 18), ev("Supper", 19), ev("Call", 20),
            allDay("Holiday"), ev("Tomorrow's", 9, day = 4), ev("Yesterday's", 9, day = 2))
        val v = view(at(10), events)
        assertEquals(listOf("Holiday", "Dentist", "Car", "Gym"), v.rows.map { it.title })
        assertEquals(2, v.more)
        assertEquals("Dentist", v.next?.title)
    }

    @Test fun theEventUnderWayIsOnTopAndInBold() {
        val v = view(at(14, 45), listOf(ev("Dentist", 14, 30), ev("Car", 17)))
        assertEquals(listOf("Dentist", "Car"), v.rows.map { it.title })
        assertEquals("Dentist", v.next?.title)
    }

    @Test fun theWeekRunsMondayToSundayWithTodayAndItsCounts() {
        val v = view(at(10), listOf(ev("A", 9), ev("B", 11), ev("C", 13), ev("D", 15), ev("E", 9, day = 5)))
        assertEquals(7, v.week.size)
        assertEquals(DayOfWeek.MONDAY, v.week.first().date.dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 3), v.week.single { it.today }.date)
        assertEquals(4, v.week.single { it.today }.count)
        assertEquals(3, v.week.single { it.today }.dots)
        assertEquals(1, v.week.single { it.date.dayOfMonth == 5 }.count)
    }

    @Test fun onSundayTheReadReachesMondayForTomorrowsLine() {
        val r = TodayPage.range(LocalDate.of(2026, 11, 8), zone)
        assertEquals(LocalDate.of(2026, 11, 2).atStartOfDay(zone), r.from)
        assertEquals(LocalDate.of(2026, 11, 10).atStartOfDay(zone), r.to)
        assertEquals(LocalDate.of(2026, 11, 9).atStartOfDay(zone), TodayPage.range(LocalDate.of(2026, 11, 3), zone).to)
    }

    @Test fun withoutTheCalendarThereIsNoStripAndNoRows() {
        val v = view(at(10), null)
        assertFalse(v.readable)
        assertTrue(v.week.isEmpty() && v.rows.isEmpty())
    }

    @Test fun anEmptyDayIsFinishedNotFilled() {
        val v = view(at(20), listOf(ev("Standup", 9, 30, 30, day = 4)))
        assertTrue(v.rows.isEmpty())
        assertTrue(v.day.doneForToday)
        assertEquals("Standup", v.day.tomorrow?.title)
    }

    @Test fun listsWithNothingLeftGoAndTwoAtMostShow() {
        val v = view(at(10), emptyList(), lists = listOf(ListGlance("a", "Groceries", 4), ListGlance("b", "Done", 0), ListGlance("c", "To-do", 3), ListGlance("d", "Hardware", 1)))
        assertEquals(listOf("Groceries", "To-do"), v.lists.map { it.name })
    }

    @Test fun twoRemindersShowAndTheRestAreCounted() {
        val v = view(at(10), emptyList(), reminders = listOf("Library books", "Call the bank", "Water the plants", " "))
        assertEquals(listOf("Library books", "Call the bank"), v.reminders)
        assertEquals(1, v.moreReminders)
    }

    @Test fun countsNeverGoBelowNothing() {
        val v = view(at(10), emptyList(), loose = -1, robots = 2)
        assertEquals(0, v.looseEnds)
        assertEquals(2, v.fromRobots)
    }

    @Test fun timesReadAsEachLanguageWritesThem() {
        assertEquals("2:30", TodayPage.time(ms(14, 30), zone, Lang.EN))
        assertEquals("12:05", TodayPage.time(ms(0, 5), zone, Lang.EN))
        assertEquals("14 h 30", TodayPage.time(ms(14, 30), zone, Lang.FR))
        assertEquals("9 h", TodayPage.time(ms(9), zone, Lang.FR))
    }

    @Test fun thePinnedNoteWinsWhileItExistsElseTheNewest() {
        val old = Note("old", "Parking", "level 3, row F", 1, 1)
        val new = Note("new", "", "Groceries\nmilk\neggs\nbread", 2, 5)
        assertEquals("old" to true, TodayPage.pick(listOf(new, old), "old")?.let { it.first.id to it.second })
        assertEquals("new" to false, TodayPage.pick(listOf(old, new), "gone")?.let { it.first.id to it.second })
        assertNull(TodayPage.pick(emptyList(), null))
        val g = TodayPage.note(new, false)
        assertEquals("Groceries", g.title)
        assertEquals(listOf("milk", "eggs"), g.lines)
    }

    @Test fun aQuickAddGoesOnALineOfItsOwn() {
        val n = Note("n", "Parking", "level 3", 1, 1)
        assertEquals("level 3\nrow F", TodayPage.added(n, "  row F ", 9)?.text)
        assertEquals(9L, TodayPage.added(n, "row F", 9)?.updated)
        assertEquals("first", TodayPage.added(n.copy(text = ""), "first", 9)?.text)
        assertNull(TodayPage.added(n, "   ", 9))
        assertEquals(TodayPage.QUICK_ADD, TodayPage.added(n.copy(text = ""), "x".repeat(900), 9)?.text?.length)
    }

    @Test fun theOfferShowsOnceRobotIsReadyAndUntilAnswered() {
        val s = HomeSettings()
        assertFalse(s.today)
        assertTrue(s.offerShows(ready = true, guideUnderWay = false))
        assertFalse(s.offerShows(ready = false, guideUnderWay = false))
        assertFalse(s.offerShows(ready = true, guideUnderWay = true))
        assertFalse(s.declined().offerShows(ready = true, guideUnderWay = false))
        assertTrue(s.turnedOn().today)
        assertFalse(s.turnedOn().copy(today = false).offerShows(ready = true, guideUnderWay = false))
    }

    @Test fun theSettingsFileRoundTripsAndABadOneIsTheDefaults() {
        val s = HomeSettings(today = true, offer = TodayOffer.TAKEN, note = "abc")
        assertEquals(s, HomeSettings.fromJson(s.toJson()))
        assertEquals(HomeSettings(), HomeSettings.fromJson(null))
        assertEquals(HomeSettings(), HomeSettings.fromJson("{not json"))
        assertEquals(HomeSettings(), HomeSettings.fromJson("""{"v":2,"today":true}"""))
        assertEquals(TodayOffer.OPEN, HomeSettings.fromJson("""{"v":1,"today":true,"offer":"later"}""").offer)
        assertNull(HomeSettings.fromJson("""{"v":1,"note":""}""").note)
    }
}
