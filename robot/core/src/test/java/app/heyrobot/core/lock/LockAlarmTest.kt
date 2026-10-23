// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.AlarmCheck
import app.heyrobot.core.AlarmEnd
import app.heyrobot.core.AlarmSignal
import app.heyrobot.core.AlarmWatch
import app.heyrobot.core.NextAlarm
import app.heyrobot.core.SeenAlarm
import app.heyrobot.core.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Phase B: After the alarm, and One app first "when I turn off my alarm".
class LockAlarmTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun at(d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(LocalDateTime.of(2026, 10, d, h, mi), zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private fun clocks(now: ZonedDateTime, elapsed: Long = 5_000_000) = Clocks(now, elapsed, 3)

    private val weekdays = LockRule.WEEKDAYS
    private val wake = PhoneLock("lk_wake", "Wake up", LockRule.Alarm(TimeWindow.ALARM, 60, weekdays))
    private val read = PhoneLock("lk_read", "Reading first", LockRule.App(AppMode.FIRST, "org.example.reader", AppStart.Alarm, minutes = 20, latestMin = 180))
    private val file = LockFile(LockSettings(on = true), listOf(wake, read))

    // 2026-10-19 is a Monday, 2026-10-18 a Sunday.
    @Test fun aDismissedAlarmStartsItsLocks() {
        val r = LockSchedule.onAlarmEnd(file, LockState(), clocks(at(19, 6, 5)), AlarmEnd.DISMISSED, ms(at(19, 6)))
        assertEquals(listOf("lk_wake", "lk_read"), r.started.map { it.lock })
        val w = r.state.active.first { it.lock == "lk_wake" }
        assertEquals(ms(at(19, 7, 5)), w.end)
        assertEquals(5_000_000 + 3_600_000L, w.endElapsed)
        assertEquals(ms(at(19, 9, 5)), r.state.active.first { it.lock == "lk_read" }.end)
        // A second alarm while they are on doesn't start them again.
        assertTrue(LockSchedule.onAlarmEnd(file, r.state, clocks(at(19, 6, 30)), AlarmEnd.DISMISSED, ms(at(19, 6, 25))).started.isEmpty())
    }

    @Test fun aSnoozeStartsNothing() {
        val r = LockSchedule.onAlarmEnd(file, LockState(), clocks(at(19, 6, 5)), AlarmEnd.SNOOZED, ms(at(19, 6)))
        assertTrue(r.started.isEmpty())
        assertNull(r.state.missed)
        assertTrue(LockSchedule.onAlarmEnd(file, LockState(), clocks(at(19, 6, 5)), AlarmEnd.UNKNOWN, ms(at(19, 6))).state.active.isEmpty())
    }

    @Test fun aMissedAlarmWaitsForTheUnlock() {
        val m = LockSchedule.onAlarmEnd(file, LockState(), clocks(at(19, 6, 10)), AlarmEnd.MISSED, ms(at(19, 6)))
        assertTrue(m.started.isEmpty())
        assertEquals(MissedAlarm(ms(at(19, 6)), ms(at(19, 6, 10))), m.state.missed)
        val u = LockSchedule.onUnlock(file, m.state, clocks(at(19, 8, 0)))
        assertEquals(listOf("lk_wake", "lk_read"), u.started.map { it.lock })
        assertEquals(ms(at(19, 9)), u.state.active.first { it.lock == "lk_wake" }.end)
        assertNull(u.state.missed)
        // More than 3 hours later: nothing starts, and the wait is over.
        val late = LockSchedule.onUnlock(file, m.state, clocks(at(19, 9, 11)))
        assertTrue(late.started.isEmpty())
        assertNull(late.state.missed)
        // An unlock with nothing missed changes nothing.
        assertEquals(LockState(), LockSchedule.onUnlock(file, LockState(), clocks(at(19, 8))).state)
    }

    @Test fun outOfItsWindowOrDayNothingStarts() {
        // 13:00 is past 4:00–12:00.
        assertTrue(LockSchedule.onAlarmEnd(file, LockState(), clocks(at(19, 13, 5)), AlarmEnd.DISMISSED, ms(at(19, 13))).started.isEmpty())
        // Sunday isn't one of Wake up's days; Reading first (every day) still starts.
        assertEquals(listOf("lk_read"), LockSchedule.onAlarmEnd(file, LockState(), clocks(at(18, 6, 5)), AlarmEnd.DISMISSED, ms(at(18, 6))).started.map { it.lock })
        // A missed alarm no lock wants isn't kept.
        assertNull(LockSchedule.onAlarmEnd(file, LockState(), clocks(at(19, 13, 10)), AlarmEnd.MISSED, ms(at(19, 13))).state.missed)
        // Phone lock off, or a lock turned off: nothing.
        assertFalse(LockSchedule.wantsClock(null))
        assertFalse(LockSchedule.wantsClock(file.copy(locks = file.locks.map { it.copy(on = false) })))
        assertTrue(LockSchedule.wantsClock(file))
    }

    @Test fun aSpoofedAlertNeverReachesTheWatch() {
        // Clock's own next alarm at 6:00, seen at 5:00: an ALERT at 6:00 counts, one at 4:00 doesn't (AlarmCheck, as routines).
        val seen = listOf(SeenAlarm(ms(at(19, 6)), true, ms(at(19, 5))))
        assertTrue(AlarmCheck.admits(seen, AlarmSignal.Alert(ms(at(19, 6)))))
        assertFalse(AlarmCheck.admits(seen, AlarmSignal.Alert(ms(at(19, 4)))))
        // A DONE with nothing ringing is UNKNOWN, so it starts nothing.
        val step = AlarmWatch.on(app.heyrobot.core.AlarmWatchState(), AlarmSignal.Done(ms(at(19, 6, 1)), null))
        assertEquals(AlarmEnd.UNKNOWN, step.end)
        // ALERT then DONE a minute later with no snoozed instance: dismissed.
        val ring = AlarmWatch.on(app.heyrobot.core.AlarmWatchState(), AlarmSignal.Alert(ms(at(19, 6)))).state
        assertEquals(AlarmEnd.DISMISSED, AlarmWatch.on(ring, AlarmSignal.Done(ms(at(19, 6, 1)), NextAlarm(ms(at(20, 6)), true))).end)
    }

    @Test fun theStateKeepsTheAlarmFacts() {
        val s = LockState(seen = listOf(SeenAlarm(1, true, 0)), missed = MissedAlarm(10, 20), car = 30)
        assertEquals(s, LockCodec.decodeState(LockCodec.encodeState(s)))
        // A state written before phase B (no "seen", no "missed") still reads.
        val old = LockCodec.encodeState(LockState()).replace(",\"seen\":[]", "").replace(",\"missed\":null", "").replace(",\"car\":0", "")
        assertTrue(old, !old.contains("seen") && !old.contains("car"))
        assertEquals(LockState(), LockCodec.decodeState(old))
    }

    @Test fun theBriefsLineInBothLanguages() {
        val en = app.heyrobot.core.BriefComposer.lines(app.heyrobot.core.BriefItem.GREETING,
            app.heyrobot.core.BriefInput(at(19, 6, 5), app.heyrobot.core.i18n.Lang.EN, phoneOpens = LocalTime.of(8, 0)), place = false)
        assertEquals("Your phone opens at 8.", en.last().text)
        val fr = app.heyrobot.core.BriefComposer.lines(app.heyrobot.core.BriefItem.GREETING,
            app.heyrobot.core.BriefInput(at(19, 6, 5), app.heyrobot.core.i18n.Lang.FR, phoneOpens = LocalTime.of(7, 30)), place = false)
        assertEquals("Ton téléphone s’ouvre à 7 heures 30.", fr.last().text)
        assertEquals("Ton téléphone s’ouvre à 7\u00A0h\u00A030.", fr.last().shown)
        assertFalse(fr.last().personal)
        // No lock: the greeting as before.
        assertEquals(2, app.heyrobot.core.BriefComposer.lines(app.heyrobot.core.BriefItem.GREETING,
            app.heyrobot.core.BriefInput(at(19, 6, 5), app.heyrobot.core.i18n.Lang.EN), place = false).size)
    }

    @Test fun theBriefSaysWhenThePhoneOpens() {
        // On the same ALARM_DONE, before :lock wrote anything: Wake up's end; Reading first ends on time in its app, so it doesn't count.
        assertEquals(at(19, 7, 5), LockSchedule.briefOpensAt(file, LockState(), clocks(at(19, 6, 5)), ms(at(19, 6))))
        // And once :lock has written it: the same.
        val st = LockSchedule.onAlarmEnd(file, LockState(), clocks(at(19, 6, 5)), AlarmEnd.DISMISSED, ms(at(19, 6))).state
        assertEquals(at(19, 7, 5), LockSchedule.briefOpensAt(file, st, clocks(at(19, 6, 5)), ms(at(19, 6))))
        // No lock: no line.
        assertNull(LockSchedule.briefOpensAt(file, LockState(), clocks(at(19, 13, 5)), ms(at(19, 13))))
        val bedtime = PhoneLock("lk_bed0", "Bedtime", LockRule.Time(TimeWindow(LocalTime.of(22, 0), LocalTime.of(8, 0)), DayOfWeek.entries.toSet()))
        val f = LockFile(LockSettings(on = true), listOf(bedtime))
        val night = LockSchedule.refresh(f, LockState(), clocks(at(19, 6))).state
        assertEquals(at(19, 8), LockSchedule.briefOpensAt(f, night, clocks(at(19, 6)), null))
    }
}
