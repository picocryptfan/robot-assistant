package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RoutineScheduleTest {
    private val zone = ZoneId.of("America/Toronto")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val everyDay = DayOfWeek.entries.toSet()

    private fun at(y: Int, mo: Int, d: Int, h: Int, m: Int = 0, z: ZoneId = zone) = ZonedDateTime.of(y, mo, d, h, m, 0, 0, z)
    private fun time(h: Int, m: Int = 0, days: Set<DayOfWeek> = weekdays) = RoutineTrigger.At(LocalTime.of(h, m), days)
    private fun routine(id: String, trigger: RoutineTrigger, enabled: Boolean = true, conditions: RoutineConditions = RoutineConditions()) =
        Routine(id, id, trigger, listOf(RoutineStep.Brief), enabled = enabled, created = 0, conditions = conditions)

    @Test fun nextAcrossDaysOfTheWeek() {
        val t = time(7)
        // Saturday morning: Monday.
        assertEquals(at(2026, 10, 12, 7), RoutineSchedule.next(t, at(2026, 10, 10, 9)))
        assertEquals(at(2026, 10, 12, 7), RoutineSchedule.next(t, at(2026, 10, 12, 6, 59)))
        // Strictly after: at 7:00 itself, the next is Tuesday's.
        assertEquals(at(2026, 10, 13, 7), RoutineSchedule.next(t, at(2026, 10, 12, 7)))
        // Friday after 7: Monday.
        assertEquals(at(2026, 10, 19, 7), RoutineSchedule.next(t, at(2026, 10, 16, 7, 1)))
        assertEquals(at(2026, 10, 17, 9, 30), RoutineSchedule.next(time(9, 30, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)), at(2026, 10, 12, 7)))
        assertEquals(at(2026, 10, 16, 17), RoutineSchedule.next(time(17, 0, setOf(DayOfWeek.FRIDAY)), at(2026, 10, 16, 16, 59)))
    }

    @Test fun oneOffs() {
        val once = RoutineTrigger.At(LocalTime.of(9, 0), emptySet(), LocalDate.of(2026, 10, 17))
        assertEquals(at(2026, 10, 17, 9), RoutineSchedule.next(once, at(2026, 10, 12, 7)))
        assertNull(RoutineSchedule.next(once, at(2026, 10, 17, 9)))
        assertNull(RoutineSchedule.next(RoutineTrigger.At(LocalTime.of(9, 0), emptySet()), at(2026, 10, 12, 7)))
    }

    /** 2026-11-01 in Toronto: 1:30 happens twice; the routine fires once, at the first. */
    @Test fun fallBackFiresOnceAtTheFirst130() {
        val t = time(1, 30, everyDay)
        val first = RoutineSchedule.next(t, at(2026, 10, 31, 12))!!
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30), first.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(-4), first.offset)
        val after = RoutineSchedule.next(t, first)!!
        assertEquals(at(2026, 11, 2, 1, 30), after)
        assertEquals(ZoneOffset.ofHours(-5), after.offset)
        // Even computed from inside the repeated hour, the next is the next day.
        val second130 = first.withLaterOffsetAtOverlap()
        assertEquals(first.plusHours(1).toInstant(), second130.toInstant())
        assertEquals(at(2026, 11, 2, 1, 30), RoutineSchedule.next(t, first.plusMinutes(10)))
        assertEquals(at(2026, 11, 2, 1, 30), RoutineSchedule.next(t, second130))
        // Both 1:30s are the same occurrence for the ledger.
        val r = routine("night", t)
        val slot = RoutineSchedule.slot(t, first)
        val fired = RoutineTriggers.fired(listOf(r), TriggerEvent.Time(slot), first, RunLedger())
        assertEquals(listOf("t:2026-11-01T01:30"), fired.fire.map { it.key })
        val ledger = RunLedger().record(r.id, fired.fire.single().key, first)
        val again = RoutineTriggers.fired(listOf(r), TriggerEvent.Time(RoutineSchedule.slot(t, second130)), second130, ledger)
        assertEquals(emptyList<Fire>(), again.fire)
        assertEquals(listOf(SkipWhy.DUPLICATE), again.skipped.map { it.why })
    }

    /** 2027-03-14 in Toronto: 2:30 doesn't exist; the routine fires when the clocks jump, at 3:00. */
    @Test fun springForwardFiresAtThree() {
        val t = time(2, 30, everyDay)
        val fire = RoutineSchedule.next(t, at(2027, 3, 13, 12))!!
        assertEquals(LocalDateTime.of(2027, 3, 14, 3, 0), fire.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(-4), fire.offset)
        assertEquals(LocalDateTime.of(2027, 3, 14, 2, 30), RoutineSchedule.slot(t, fire))
        assertEquals(at(2027, 3, 15, 2, 30), RoutineSchedule.next(t, fire))
        // A 3:00 routine fires at the same moment; both are due, each with its own slot.
        val next = RoutineSchedule.soonest(listOf(routine("a", t), routine("b", time(3, 0, everyDay))), at(2027, 3, 13, 12))!!
        assertEquals(fire, next.at)
        assertEquals(listOf(LocalDateTime.of(2027, 3, 14, 2, 30), LocalDateTime.of(2027, 3, 14, 3, 0)), next.due.map { it.slot })
    }

    /** A time zone change while waiting: 7:00 is the new place's 7:00 (rescheduled at TIMEZONE_CHANGED). */
    @Test fun timeZoneChangeWhileWaiting() {
        val t = time(7)
        val before = at(2026, 10, 12, 6)
        assertEquals(at(2026, 10, 12, 7), RoutineSchedule.next(t, before))
        val vancouver = ZoneId.of("America/Vancouver")
        val there = before.withZoneSameInstant(vancouver)
        assertEquals(at(2026, 10, 12, 7, z = vancouver), RoutineSchedule.next(t, there))
        // Flying east past 7:00 Paris time: the next is Tuesday's in Paris.
        val paris = before.withZoneSameInstant(ZoneId.of("Europe/Paris"))
        assertEquals(at(2026, 10, 13, 7, z = ZoneId.of("Europe/Paris")), RoutineSchedule.next(t, paris))
    }

    @Test fun theSoonestOfMany() {
        val routines = listOf(
            routine("a", time(7, 30)),
            routine("b", time(7, 0)),
            routine("c", time(6, 0, everyDay), enabled = false),
            routine("d", RoutineTrigger.Unlock()),
            routine("e", time(7, 0, everyDay)),
        )
        val next = RoutineSchedule.soonest(routines, at(2026, 10, 12, 6))!!
        assertEquals(at(2026, 10, 12, 7), next.at)
        assertEquals(listOf("b", "e"), next.routines.map { it.id })
        // On Saturday only the daily one.
        assertEquals(listOf("e"), RoutineSchedule.soonest(routines, at(2026, 10, 17, 6))!!.routines.map { it.id })
        assertNull(RoutineSchedule.soonest(listOf(routines[3]), at(2026, 10, 12, 6)))
    }

    /** After a reboot or a long Doze: only the last missed time counts, and a late run speaks only if barely late. */
    @Test fun missedRuns() {
        val t = time(7)
        assertEquals(at(2026, 10, 12, 7), RoutineSchedule.lastMissed(t, at(2026, 10, 9, 6), at(2026, 10, 12, 7, 5)))
        assertEquals(at(2026, 10, 9, 7), RoutineSchedule.lastMissed(t, at(2026, 10, 9, 6), at(2026, 10, 10, 9)))
        assertNull(RoutineSchedule.lastMissed(t, at(2026, 10, 12, 7, 1), at(2026, 10, 12, 8)))
        assertNull(RoutineSchedule.lastMissed(t, at(2026, 10, 10, 6), at(2026, 10, 11, 23)))
        val due = at(2026, 10, 12, 7)
        assertEquals(MissedRun.RUN, RoutineSchedule.missed(due, due.plusMinutes(9)))
        assertEquals(MissedRun.RUN, RoutineSchedule.missed(due, due.plusMinutes(10)))
        assertEquals(MissedRun.OFFER, RoutineSchedule.missed(due, due.plusMinutes(11)))
        assertEquals(MissedRun.OFFER, RoutineSchedule.missed(due, due.plusHours(3)))
        assertEquals(MissedRun.DROP, RoutineSchedule.missed(due, due.plusHours(3).plusMinutes(1)))
        assertEquals(MissedRun.DROP, RoutineSchedule.missed(at(2026, 10, 12, 23, 30), at(2026, 10, 13, 0, 30)))
        // A catch-up for a time that already ran is the same occurrence.
        val r = routine("m", t)
        val ledger = RunLedger().record("m", RoutineTriggers.timeKey(LocalDateTime.of(2026, 10, 12, 7, 0)), due)
        assertEquals(listOf(SkipWhy.DUPLICATE), RoutineTriggers.fired(listOf(r), TriggerEvent.Time(LocalDateTime.of(2026, 10, 12, 7, 0)), due.plusMinutes(3), ledger).skipped.map { it.why })
    }

    @Test fun alarmTriggers() {
        val r = routine("brief", RoutineTrigger.AlarmDismissed())
        val now = at(2026, 10, 12, 6, 46)
        val alarmAt = at(2026, 10, 12, 6, 45).toInstant().toEpochMilli()
        val dismissed = RoutineTriggers.fired(listOf(r), TriggerEvent.Alarm(AlarmEnd.DISMISSED, alarmAt), now, RunLedger())
        assertEquals(listOf(Fire(r, "a:$alarmAt", waitUnlock = false)), dismissed.fire)
        // Missed: it waits for the first unlock.
        assertEquals(listOf(Fire(r, "a:$alarmAt", waitUnlock = true)), RoutineTriggers.fired(listOf(r), TriggerEvent.Alarm(AlarmEnd.MISSED, alarmAt), now, RunLedger()).fire)
        for (end in listOf(AlarmEnd.SNOOZED, AlarmEnd.UNKNOWN)) assertEquals(Fired(emptyList(), emptyList()), RoutineTriggers.fired(listOf(r), TriggerEvent.Alarm(end, alarmAt), now, RunLedger()))
        // An afternoon nap's alarm is outside 4:00–12:00.
        val nap = at(2026, 10, 12, 14, 0).toInstant().toEpochMilli()
        assertEquals(listOf(SkipWhy.OUTSIDE_ALARM_WINDOW), RoutineTriggers.fired(listOf(r), TriggerEvent.Alarm(AlarmEnd.DISMISSED, nap), now.withHour(14), RunLedger()).skipped.map { it.why })
        // At most once a day: a second alarm that morning doesn't repeat it; the next day it runs.
        val ledger = RunLedger().record(r.id, dismissed.fire.single().key, now)
        val second = at(2026, 10, 12, 7, 30).toInstant().toEpochMilli()
        assertEquals(listOf(SkipWhy.ALREADY_RAN), RoutineTriggers.fired(listOf(r), TriggerEvent.Alarm(AlarmEnd.DISMISSED, second), now.withHour(7).withMinute(31), ledger).skipped.map { it.why })
        val tomorrow = at(2026, 10, 13, 6, 45).toInstant().toEpochMilli()
        assertEquals(1, RoutineTriggers.fired(listOf(r), TriggerEvent.Alarm(AlarmEnd.DISMISSED, tomorrow), now.plusDays(1), ledger).fire.size)
        // Switched off: nothing.
        assertEquals(listOf(SkipWhy.OFF), RoutineTriggers.fired(listOf(r.copy(enabled = false)), TriggerEvent.Alarm(AlarmEnd.DISMISSED, alarmAt), now, RunLedger()).skipped.map { it.why })
    }

    @Test fun unlockDeviceAndChargingTriggers() {
        val unlock = routine("u", RoutineTrigger.Unlock(LocalTime.of(5, 0)))
        assertEquals(Fired(emptyList(), emptyList()), RoutineTriggers.fired(listOf(unlock), TriggerEvent.Unlocked, at(2026, 10, 12, 4, 30), RunLedger()))
        val first = RoutineTriggers.fired(listOf(unlock), TriggerEvent.Unlocked, at(2026, 10, 12, 6, 50), RunLedger())
        assertEquals(listOf("u:2026-10-12"), first.fire.map { it.key })
        val ledger = RunLedger().record("u", "u:2026-10-12", at(2026, 10, 12, 6, 50))
        assertEquals(listOf(SkipWhy.DUPLICATE), RoutineTriggers.fired(listOf(unlock), TriggerEvent.Unlocked, at(2026, 10, 12, 9), ledger).skipped.map { it.why })

        val anyCar = routine("car", RoutineTrigger.Device(DeviceKind.CAR))
        val myCar = routine("mine", RoutineTrigger.Device(DeviceKind.CAR, "dev-2"))
        val buds = routine("buds", RoutineTrigger.Device(DeviceKind.HEADSET, connect = false))
        val all = listOf(anyCar, myCar, buds)
        val n = at(2026, 10, 12, 17, 5)
        assertEquals(listOf("car", "mine"), RoutineTriggers.fired(all, TriggerEvent.Device(DeviceKind.CAR, "dev-2", true), n, RunLedger()).fire.map { it.routine.id })
        assertEquals(listOf("car"), RoutineTriggers.fired(all, TriggerEvent.Device(DeviceKind.CAR, "dev-3", true), n, RunLedger()).fire.map { it.routine.id })
        assertEquals(emptyList<String>(), RoutineTriggers.fired(all, TriggerEvent.Device(DeviceKind.CAR, "dev-2", false), n, RunLedger()).fire.map { it.routine.id })
        assertEquals(listOf("buds"), RoutineTriggers.fired(all, TriggerEvent.Device(DeviceKind.HEADSET, "dev-1", false), n, RunLedger()).fire.map { it.routine.id })

        val night = routine("plug", RoutineTrigger.Charging(), conditions = RoutineConditions(between = TimeWindow.NIGHT, days = weekdays))
        assertEquals(1, RoutineTriggers.fired(listOf(night), TriggerEvent.Power(true), at(2026, 10, 12, 22), RunLedger()).fire.size)
        assertEquals(listOf(SkipWhy.QUIET_HOURS), RoutineTriggers.fired(listOf(night), TriggerEvent.Power(true), at(2026, 10, 12, 14), RunLedger()).skipped.map { it.why })
        assertEquals(listOf(SkipWhy.QUIET_HOURS), RoutineTriggers.fired(listOf(night), TriggerEvent.Power(true), at(2026, 10, 17, 22), RunLedger()).skipped.map { it.why })
        assertEquals(emptyList<Fire>(), RoutineTriggers.fired(listOf(night), TriggerEvent.Power(false), at(2026, 10, 12, 22), RunLedger()).fire)
        // A routine you only ask for never fires by itself.
        val asked = routine("asked", RoutineTrigger.Asked)
        for (e in listOf(TriggerEvent.Unlocked, TriggerEvent.Power(true), TriggerEvent.Time(LocalDateTime.of(2026, 10, 12, 7, 0))))
            assertEquals(Fired(emptyList(), emptyList()), RoutineTriggers.fired(listOf(asked), e, at(2026, 10, 12, 7), RunLedger()))
    }

    @Test fun timeEventsMatchTheirSlotOnly() {
        val r = routine("w", time(7))
        assertEquals(1, RoutineTriggers.fired(listOf(r), TriggerEvent.Time(LocalDateTime.of(2026, 10, 12, 7, 0)), at(2026, 10, 12, 7), RunLedger()).fire.size)
        assertEquals(0, RoutineTriggers.fired(listOf(r), TriggerEvent.Time(LocalDateTime.of(2026, 10, 12, 7, 30)), at(2026, 10, 12, 7, 30), RunLedger()).fire.size)
        // Saturday isn't a weekday.
        assertEquals(0, RoutineTriggers.fired(listOf(r), TriggerEvent.Time(LocalDateTime.of(2026, 10, 17, 7, 0)), at(2026, 10, 17, 7), RunLedger()).fire.size)
    }

    @Test fun theStateFileRoundTrips() {
        val ledger = RunLedger().record("r-1", "t:2026-10-12T07:00", at(2026, 10, 12, 7)).record("r-2", "u:2026-10-12", at(2026, 10, 12, 8))
        val alarm = AlarmWatchState(AlarmPhase.SNOOZED, 10L, 5L, 12L)
        assertEquals(ledger to alarm, RoutineStateCodec.decode(RoutineStateCodec.encode(ledger, alarm)))
        assertEquals(RunLedger() to AlarmWatchState(), RoutineStateCodec.decode("{oops"))
        assertEquals(RunLedger() to AlarmWatchState(), RoutineStateCodec.decode("""{"v":2}"""))
        assertEquals(setOf("r-2"), ledger.keep(setOf("r-2", "r-9")).last.keys)
        assertTrue(ledger.last.getValue("r-1").day == LocalDate.of(2026, 10, 12))
    }
}
