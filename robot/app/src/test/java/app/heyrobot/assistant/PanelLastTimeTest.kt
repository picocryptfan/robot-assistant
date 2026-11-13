// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.lasttime.LastCandidate
import app.heyrobot.core.lasttime.LastRead
import app.heyrobot.core.lasttime.LastSource
import app.heyrobot.core.lasttime.LastTime
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// review-last-time M2 and M3: the panel's own reading of the calendar's days and of what it could read.
class PanelLastTimeTest {
    private val day = LocalDate.of(2026, 11, 8)
    private val utcMidnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val atlantic = ZoneId.of("America/Halifax")
    private val tonga = ZoneOffset.ofHours(13)

    @Test fun anAllDayEventKeepsItsDateInEveryZone() {
        for (z in listOf(atlantic, tonga, ZoneOffset.UTC)) assertEquals(z.toString(), day, PanelLastTime.eventDay(utcMidnight, true, z))
        // A timed event is on its own zone's day: 23:30 in Halifax is the next day in UTC.
        val late = day.atTime(23, 30).atZone(atlantic).toInstant().toEpochMilli()
        assertEquals(day, PanelLastTime.eventDay(late, false, atlantic))
        assertEquals(day.plusDays(1), PanelLastTime.eventDay(late, false, tonga))
        val ev = CalendarEvent(utcMidnight, utcMidnight + 86_400_000L, true, "Garage day", null, id = 7)
        for (z in listOf(atlantic, tonga)) assertEquals(day, PanelLastTime.gather(null, listOf(ev), emptyList(), z).second.single().day)
    }

    @Test fun whatCouldNotBeReadIsSaidAndNoneNamesOnlyTheRest() {
        val note = LastCandidate(LastSource.NOTE, "n1", "Groceries", "", day)
        val (found, cs) = PanelLastTime.gather(null, null, listOf(note), atlantic)
        assertEquals(LastRead(files = false, calendar = false), found.read)
        assertEquals(listOf("note:n1"), cs.map { it.key })
        val s = LastTime.answer(emptyList(), day, found.read).render(Langs.of(Lang.EN))
        assertTrue(s, s.startsWith("I found nothing about that in your notes. I couldn't look in your calendar") && "receipts, calendar" !in s && '\n' !in s)
        val all = PanelLastTime.gather(emptyList(), emptyList(), emptyList(), atlantic).first.read
        assertEquals(LastRead(), all)
        // A blank title never answers.
        assertTrue(PanelLastTime.gather(null, listOf(CalendarEvent(utcMidnight, utcMidnight, true, " ", null)), emptyList(), atlantic).second.isEmpty())
    }

    // review-last-time-b M2: a timed event counts once it has begun, so one later today isn't "today" yet.
    @Test fun aTimedEventLaterTodayIsNotFoundYet() {
        val now = System.currentTimeMillis()
        val later = CalendarEvent(now + 3_600_000L, now + 7_200_000L, false, "Dentist", null, id = 8)
        val began = CalendarEvent(now - 3_600_000L, now, false, "Haircut", null, id = 9)
        assertEquals(listOf("Haircut"), PanelLastTime.gather(null, listOf(later, began), emptyList(), atlantic).second.map { it.title })
    }

    // review-last-time-b M1: the rebuild stops for a question, heat, battery saver and the lock.
    @Test fun theRebuildStopsForAQuestionHeatBatterySaverAndTheLock() {
        val light = PowerManager.THERMAL_STATUS_LIGHT
        assertTrue(PanelLastTime.rebuildMay(locked = false, thermal = light, powerSave = false, asking = false))
        assertFalse(PanelLastTime.rebuildMay(locked = false, thermal = light, powerSave = false, asking = true))
        assertFalse(PanelLastTime.rebuildMay(locked = false, thermal = PowerManager.THERMAL_STATUS_MODERATE, powerSave = false, asking = false))
        assertFalse(PanelLastTime.rebuildMay(locked = false, thermal = light, powerSave = true, asking = false))
        assertFalse(PanelLastTime.rebuildMay(locked = true, thermal = light, powerSave = false, asking = false))
    }

    // review-last-time-b L1: a source whose read failed is said, and the none line leaves it out.
    @Test fun aSourceThatCouldNotBeReadIsSaidNotSearched() {
        val read = PanelLastTime.gather(emptyList(), emptyList(), emptyList(), atlantic, unread = setOf(LastSource.NOTE)).first.read
        val s = LastTime.answer(emptyList(), day, read).render(Langs.of(Lang.EN))
        assertEquals("I found nothing about that in your receipts or calendar. I couldn't read your notes just now.", s)
        val hit = LastTime.match("groceries", null, listOf(LastCandidate(LastSource.NOTE, "n1", "Groceries", "", day)), emptyMap(), day)
        val h = LastTime.answer(hit, day, LastRead(unread = setOf(LastSource.CALENDAR))).render(Langs.of(Lang.EN))
        assertTrue(h, "Groceries" in h && h.endsWith("I couldn't read your calendar just now."))
        val all = LastTime.answer(emptyList(), day, LastRead(unread = setOf(LastSource.RECEIPT, LastSource.CALENDAR, LastSource.NOTE))).render(Langs.of(Lang.EN))
        assertTrue(all, "found nothing" !in all && "receipts in Files" in all && "notes just now" in all)
    }

    // review-last-time-b L2: only a Files never set up says so; one that can't be loaded now is "couldn't read".
    @Test fun aFilesIndexThatCannotBeLoadedIsNotNotSetUp() {
        assertEquals(false, PanelLastTime.filesState(IndexLoad.Empty))
        for (l in listOf(IndexLoad.Locked, IndexLoad.KeyGone, IndexLoad.Unreadable, IndexLoad.TooNew(9))) assertEquals(l.toString(), null, PanelLastTime.filesState(l))
        val s = LastTime.answer(emptyList(), day, LastRead(unread = setOf(LastSource.RECEIPT))).render(Langs.of(Lang.EN))
        assertEquals("I found nothing about that in your calendar or notes. I couldn't read your receipts in Files just now.", s)
    }
}
