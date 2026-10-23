// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LockScheduleTest {
    private val toronto = ZoneId.of("America/Toronto")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = toronto) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)
    private fun clocks(now: ZonedDateTime, elapsed: Long = 1_000_000, boot: Int? = 7) = Clocks(now, elapsed, boot)
    private fun time(id: String, from: Int, to: Int, days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), apps: List<String> = emptyList()) =
        PhoneLock(id, id, LockRule.Time(TimeWindow(LocalTime.of(from, 0), LocalTime.of(to, 0)), days), extras = LockExtras(apps = apps))

    private val bedtime = time("lk_bed0", 22, 7, setOf(DayOfWeek.SUNDAY))
    private val on = LockSettings(on = true)
    private fun file(vararg l: PhoneLock) = LockFile(on, l.toList())

    // 2026-10-18 is a Sunday.
    @Test fun aWindowAcrossMidnightBelongsToItsStartDay() {
        assertEquals(LocalDate.of(2026, 10, 18), LockSchedule.occurrenceAt(bedtime, at(2026, 10, 19, 2))!!.date)
        assertEquals(at(2026, 10, 19, 7), LockSchedule.occurrenceAt(bedtime, at(2026, 10, 18, 23))!!.end)
        // Monday night isn't one of its days.
        assertNull(LockSchedule.occurrenceAt(bedtime, at(2026, 10, 20, 2)))
        assertNull(LockSchedule.occurrenceAt(bedtime, at(2026, 10, 19, 7)))
        assertNull(LockSchedule.occurrenceAt(bedtime, at(2026, 10, 18, 21, 59)))
    }

    @Test fun daylightSavingInToronto() {
        // Fall back, 2026-11-01 (a Sunday) at 2:00: a 1:00 to 3:00 lock lasts three hours.
        val fall = time("lk_fall", 1, 3)
        val o = LockSchedule.occurrenceAt(fall, at(2026, 11, 1, 2))!!
        assertEquals(3, java.time.Duration.between(o.start, o.end).toHours())
        // Spring forward, 2027-03-14 at 2:00: a lock from 2:30 starts when the clocks jump (3:00).
        val spring = PhoneLock("lk_spr0", "s", LockRule.Time(TimeWindow(LocalTime.of(2, 30), LocalTime.of(4, 0))))
        assertEquals(at(2027, 3, 14, 3), LockSchedule.occurrenceOn(spring, LocalDate.of(2027, 3, 14), at(2027, 3, 14, 1))!!.start)
    }

    @Test fun aZoneChangeFollowsTheNewPlacesClock() {
        val l = time("lk_zone", 22, 23)
        assertTrue(LockSchedule.occurrenceAt(l, at(2026, 10, 18, 22, 30, ZoneId.of("Europe/Paris"))) != null)
        assertNull(LockSchedule.occurrenceAt(l, at(2026, 10, 18, 22, 30).withZoneSameInstant(ZoneId.of("Europe/Paris"))))
    }

    @Test fun refreshStartsAndEndsTimeLocks() {
        val f = file(bedtime)
        val r = LockSchedule.refresh(f, LockState(), clocks(at(2026, 10, 18, 22, 5)))
        assertEquals(listOf("lk_bed0"), r.started.map { it.lock })
        assertEquals(at(2026, 10, 19, 7).toInstant().toEpochMilli(), r.state.active.single().end)
        val again = LockSchedule.refresh(f, r.state, clocks(at(2026, 10, 19, 1)))
        assertTrue(again.started.isEmpty() && again.ended.isEmpty())
        val over = LockSchedule.refresh(f, again.state, clocks(at(2026, 10, 19, 7)))
        assertEquals(listOf("lk_bed0"), over.ended.map { it.lock })
        assertTrue(over.state.active.isEmpty())
        // Phone lock off, the lock off, the lock deleted, the file unreadable: off.
        for (g in listOf(f.copy(settings = LockSettings(on = false)), file(bedtime.copy(on = false)), file(), null)) {
            val off = LockSchedule.refresh(LockSchedule.usable(g?.let { LockFileRead.Ok(it) }), r.state, clocks(at(2026, 10, 19, 1)))
            assertTrue(off.state.active.isEmpty() && off.ended.size == 1)
        }
    }

    @Test fun anEarlyEndOrASkipIsntUndoneByTheNextRefresh() {
        val f = file(bedtime)
        val c = clocks(at(2026, 10, 18, 23))
        val on = LockSchedule.refresh(f, LockState(), c).state
        val ended = LockSchedule.endAll(on, c)
        assertTrue(LockSchedule.refresh(f, ended, clocks(at(2026, 10, 19, 1))).state.active.isEmpty())
        // Next Sunday it comes on again.
        assertEquals(1, LockSchedule.refresh(f, ended, clocks(at(2026, 10, 25, 23))).state.active.size)
        // Skipped before it starts.
        val skipped = LockSchedule.skipNext(bedtime, LockState(), at(2026, 10, 18, 21, 55))
        assertEquals(LocalDate.of(2026, 10, 18), skipped.skip["lk_bed0"])
        assertTrue(LockSchedule.refresh(f, skipped, clocks(at(2026, 10, 18, 22, 1))).state.active.isEmpty())
        // Old skips are dropped.
        assertTrue(LockSchedule.refresh(f, skipped, clocks(at(2026, 10, 25, 12))).state.skip.isEmpty())
    }

    @Test fun voiceLocksUseTheElapsedClockAndAreCapped() {
        val f = file()
        val c = clocks(at(2026, 10, 18, 12), elapsed = 5_000_000, boot = 3)
        assertNull(LockSchedule.startVoice(f, LockState(), c, at(2026, 10, 19, 0, 1)))
        assertNull(LockSchedule.startVoice(f, LockState(), c, at(2026, 10, 18, 11)))
        assertNull(LockSchedule.startVoice(null, LockState(), c, at(2026, 10, 18, 13)))
        val s = LockSchedule.startVoice(f, LockState(), c, at(2026, 10, 18, 13))!!
        val v = s.active.single()
        assertTrue(v.voice)
        assertEquals(5_000_000 + 3_600_000L, v.endElapsed)
        // The wall clock moved forward an hour: the elapsed clock says 10 minutes passed, so it is still on.
        assertEquals(1, LockSchedule.refresh(f, s, Clocks(at(2026, 10, 18, 13, 10), 5_600_000, 3)).state.active.size)
        assertFalse(LockSchedule.over(v, Clocks(at(2026, 10, 18, 13, 10), 5_600_000, 3)))
        assertTrue(LockSchedule.over(v, Clocks(at(2026, 10, 18, 12, 30), 8_600_001, 3)))
        // After a restart (another boot count) the wall clock decides.
        assertFalse(LockSchedule.over(v, Clocks(at(2026, 10, 18, 12, 59), 10, 4)))
        assertTrue(LockSchedule.over(v, Clocks(at(2026, 10, 18, 13, 0), 10, 4)))
        // Removed when it ends.
        assertTrue(LockSchedule.refresh(f, s, Clocks(at(2026, 10, 18, 13, 1), 10, 4)).state.active.isEmpty())
    }

    @Test fun aSecondLockByVoiceNeverEndsTheFirstSooner() {
        val f = file()
        val first = LockSchedule.startVoice(f, LockState(), clocks(at(2026, 10, 18, 19), elapsed = 5_000_000, boot = 3), at(2026, 10, 19, 7))!!
        val v = first.active.single()
        // Ten minutes later, "lock the phone for one minute": the lock until 7:00 stays exactly as it was.
        val later = Clocks(at(2026, 10, 18, 19, 10), 5_600_000, 3)
        assertEquals(at(2026, 10, 19, 7), LockSchedule.voiceEnd(first, later))
        assertEquals(first, LockSchedule.startVoice(f, first, later, at(2026, 10, 18, 19, 11)))
        assertEquals(first, LockSchedule.startVoice(f, first, later, at(2026, 10, 19, 7)))
        // A minute after that, it is still on (the phone doesn't open at 19:11).
        val shorter = LockSchedule.startVoice(f, first, later, at(2026, 10, 18, 19, 11))!!
        assertEquals(1, LockSchedule.refresh(f, shorter, Clocks(at(2026, 10, 18, 19, 12), 5_720_000, 3)).state.active.size)
        // A later end makes it longer: the same start (so the 24-hour cap still counts from 19:00) and its wait.
        val waiting = first.copy(active = listOf(v.copy(waitFrom = 123L)))
        val at6 = Clocks(at(2026, 10, 19, 6), 44_600_000, 3)
        val longer = LockSchedule.startVoice(f, waiting, at6, at(2026, 10, 19, 9))!!.active.single()
        assertEquals(v.start, longer.start)
        assertEquals(123L, longer.waitFrom)
        assertEquals(at(2026, 10, 19, 9).toInstant().toEpochMilli(), longer.end)
        assertEquals(44_600_000 + 3 * 3_600_000L, longer.endElapsed)
        // A lock by voice that is already over doesn't count: a new one starts now.
        val over = Clocks(at(2026, 10, 19, 8), 51_800_000, 3)
        assertNull(LockSchedule.voiceEnd(first, over))
        val fresh = LockSchedule.startVoice(f, first, over, at(2026, 10, 19, 9))!!.active.single()
        assertEquals(at(2026, 10, 19, 8).toInstant().toEpochMilli(), fresh.start)
    }

    @Test fun noLockByVoiceDuringALockForSomeoneElse() {
        val someone = LockFile(LockSettings(on = true, forWho = LockFor.SOMEONE_ELSE), listOf(bedtime))
        val c = clocks(at(2026, 10, 18, 19))
        assertFalse(LockSchedule.someoneElseOn(someone, LockState(), c))
        // With none on, one starts (and only the code ends it early).
        val on = LockSchedule.startVoice(someone, LockState(), c, at(2026, 10, 19, 7))!!
        val later = clocks(at(2026, 10, 18, 19, 10))
        assertTrue(LockSchedule.someoneElseOn(someone, on, later))
        // During it, no second one: not a shorter one, and not a longer one either.
        assertNull(LockSchedule.startVoice(someone, on, later, at(2026, 10, 18, 19, 11)))
        assertNull(LockSchedule.startVoice(someone, on, later, at(2026, 10, 19, 7, 30)))
        // The same during a Time of day lock for someone else.
        val night = clocks(at(2026, 10, 18, 23))
        val bed = LockSchedule.refresh(someone, LockState(), night).state
        assertTrue(LockSchedule.someoneElseOn(someone, bed, night))
        assertNull(LockSchedule.startVoice(someone, bed, night, at(2026, 10, 18, 23, 1)))
        // Your own lock isn't refused, and once the lock for someone else is over, another may start.
        assertFalse(LockSchedule.someoneElseOn(file(bedtime), bed, night))
        assertTrue(LockSchedule.startVoice(file(bedtime), bed, night, at(2026, 10, 18, 23, 30)) != null)
        assertFalse(LockSchedule.someoneElseOn(someone, on, clocks(at(2026, 10, 19, 7, 1), elapsed = 50_000_000)))
    }

    @Test fun noLockLastsMoreThanADay() {
        val stale = ActiveLock(ActiveLock.VOICE, at(2026, 10, 18, 12).toInstant().toEpochMilli(), Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 3)
        assertTrue(LockSchedule.over(stale, Clocks(at(2026, 10, 19, 12), 0, 3)))
        assertEquals(at(2026, 10, 19, 12), LockSchedule.endOf(stale, Clocks(at(2026, 10, 18, 13), 0, 3)))
        val long = time("lk_long", 7, 6)
        val o = LockSchedule.occurrenceOn(long, LocalDate.of(2026, 10, 18), at(2026, 10, 18, 6))!!
        assertTrue(java.time.Duration.between(o.start, o.end).toHours() <= 24)
    }

    @Test fun overlapsLeaveOpenOnlyWhatBothAllow() {
        val a = time("lk_aaaa", 20, 23, apps = listOf("org.example.a", "org.example.b"))
        val b = time("lk_bbbb", 21, 22, apps = listOf("org.example.b"))
        val f = file(a, b)
        val s = LockSchedule.refresh(f, LockState(), clocks(at(2026, 10, 18, 21, 30))).state
        assertEquals(setOf("org.example.b"), LockSchedule.allowedPackages(f, s))
        val one = LockSchedule.refresh(f, LockState(), clocks(at(2026, 10, 18, 20, 30))).state
        assertEquals(setOf("org.example.a", "org.example.b"), LockSchedule.allowedPackages(f, one))
        // With a voice lock on too, no extras.
        val v = LockSchedule.startVoice(f, s, clocks(at(2026, 10, 18, 21, 30)), at(2026, 10, 18, 22))!!
        assertEquals(emptySet<String>(), LockSchedule.allowedPackages(f, v))
    }

    @Test fun opensAtChainsOverlappingLocks() {
        val a = time("lk_aaaa", 20, 22)
        val b = time("lk_bbbb", 21, 23)
        val f = file(a)
        val c = clocks(at(2026, 10, 18, 20, 30))
        val s = LockSchedule.refresh(f, LockState(), c).state
        assertEquals(at(2026, 10, 18, 22), LockSchedule.opensAt(f, s, c))
        val f2 = file(a, b)
        assertEquals(at(2026, 10, 18, 23), LockSchedule.opensAt(f2, LockSchedule.refresh(f2, LockState(), c).state, c))
        assertNull(LockSchedule.opensAt(f, LockState(), c))
    }

    @Test fun theNextExactAlarmWarningsAndEvents() {
        val f = file(bedtime)
        val c = clocks(at(2026, 10, 18, 20))
        // Wake-up for the service (21:50), the warning (21:55), the start (22:00): the soonest first.
        assertEquals(at(2026, 10, 18, 21, 50), LockSchedule.nextAlarm(f, LockState(), c))
        assertEquals(at(2026, 10, 18, 21, 55), LockSchedule.nextAlarm(f, LockState(), clocks(at(2026, 10, 18, 21, 50))))
        assertEquals(at(2026, 10, 18, 22), LockSchedule.nextAlarm(f, LockState(), clocks(at(2026, 10, 18, 21, 55))))
        assertNull(LockSchedule.warning(f, LockState(), at(2026, 10, 18, 21, 54)))
        assertEquals("lk_bed0", LockSchedule.warning(f, LockState(), at(2026, 10, 18, 21, 55))!!.lock.id)
        assertNull(LockSchedule.warning(f.copy(settings = on.copy(warnMin = 0)), LockState(), at(2026, 10, 18, 21, 58)))
        assertFalse(LockSchedule.wantsEvents(f, LockState(), clocks(at(2026, 10, 18, 21, 49))))
        assertTrue(LockSchedule.wantsEvents(f, LockState(), clocks(at(2026, 10, 18, 21, 50))))
        // During it: its end.
        val during = clocks(at(2026, 10, 18, 23))
        val s = LockSchedule.refresh(f, LockState(), during).state
        assertEquals(at(2026, 10, 19, 7), LockSchedule.nextAlarm(f, s, during))
        assertTrue(LockSchedule.wantsEvents(f, s, during))
        assertEquals("lk_bed0", LockSchedule.nextLock(f, LockState(), at(2026, 10, 19, 8))!!.lock.id)
        assertNull(LockSchedule.nextAlarm(file(), LockState(), c))
    }
}
