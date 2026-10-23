// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.lock.ScreenTime.KEYGUARD_HIDDEN
import app.heyrobot.core.lock.ScreenTime.KEYGUARD_SHOWN
import app.heyrobot.core.lock.ScreenTime.PAUSED
import app.heyrobot.core.lock.ScreenTime.RESUMED
import app.heyrobot.core.lock.ScreenTime.SCREEN_OFF
import app.heyrobot.core.lock.ScreenTime.SCREEN_ON
import app.heyrobot.core.lock.ScreenTime.SHUTDOWN
import app.heyrobot.core.lock.ScreenTime.STARTUP
import app.heyrobot.core.lock.ScreenTime.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Phase B: One app first's count and Daily limit's.
class ScreenTimeTest {
    private val app = "org.example.reader"
    private val min = 60_000L
    private fun e(m: Long, type: Int, pkg: String? = null, cls: String? = null) = UsageEvent(m * min, type, pkg, cls)

    @Test fun resumedPausedAndStopped() {
        val ev = listOf(e(0, SCREEN_ON), e(0, KEYGUARD_HIDDEN), e(1, RESUMED, app, "A"), e(6, PAUSED, app, "A"), e(6, RESUMED, "other", "X"),
            e(7, STOPPED, app, "A"), e(10, RESUMED, app, "A"))
        assertEquals(5 * min + 2 * min, ScreenTime.count(ev, 0, 12 * min, app))
        // Screen time with no app: all of it.
        assertEquals(12 * min, ScreenTime.count(ev, 0, 12 * min))
    }

    @Test fun twoActivitiesOfOneApp() {
        // B resumes before A pauses: one stretch, never counted twice.
        val ev = listOf(e(0, SCREEN_ON), e(0, KEYGUARD_HIDDEN), e(1, RESUMED, app, "A"), e(3, RESUMED, app, "B"), e(3, PAUSED, app, "A"), e(5, PAUSED, app, "B"))
        assertEquals(4 * min, ScreenTime.count(ev, 0, 10 * min, app))
    }

    @Test fun screenOffAndTheLockScreenDontCount() {
        val ev = listOf(e(0, SCREEN_ON), e(0, KEYGUARD_HIDDEN), e(0, RESUMED, app, "A"), e(4, SCREEN_OFF), e(4, KEYGUARD_SHOWN), e(9, SCREEN_ON),
            e(10, KEYGUARD_HIDDEN), e(12, PAUSED, app, "A"))
        assertEquals(6 * min, ScreenTime.count(ev, 0, 15 * min, app))
        assertEquals(4 * min + 5 * min, ScreenTime.count(ev, 0, 15 * min))
    }

    @Test fun whatWasTrueBeforeTheFirstEvent() {
        // The app was in front and the screen on at the start: its pause and the screen going off tell.
        val ev = listOf(e(3, PAUSED, app, "A"), e(5, SCREEN_OFF))
        val unlocked = ScreenNow(unlocked = true)
        assertEquals(3 * min, ScreenTime.count(ev, 0, 8 * min, app, unlocked))
        assertEquals(5 * min, ScreenTime.count(ev, 0, 8 * min, now = unlocked))
        // The screen came on at 2 (it was off before).
        assertEquals(6 * min, ScreenTime.count(listOf(e(2, SCREEN_ON), e(2, KEYGUARD_HIDDEN)), 0, 8 * min))
    }

    @Test fun noEventThatTellsMeansItWasAsItIsNow() {
        // The lock came on at 0 with the screen on and unlocked; the app opened at 1 and is still in front at 21.
        val ev = listOf(e(1, RESUMED, app, "A"))
        assertEquals(20 * min, ScreenTime.count(ev, 0, 21 * min, app, ScreenNow(screenOn = true, unlocked = true, appInFront = true)))
        // No event at all: in the app the whole time, or not at all.
        assertEquals(21 * min, ScreenTime.count(emptyList(), 0, 21 * min, app, ScreenNow(true, true, true)))
        assertEquals(0, ScreenTime.count(emptyList(), 0, 21 * min, app, ScreenNow(true, true, false)))
        assertEquals(0, ScreenTime.count(emptyList(), 0, 21 * min, app, ScreenNow(false, false, true)))
    }

    @Test fun aShutdownEndsEverything() {
        val ev = listOf(e(0, SCREEN_ON), e(0, KEYGUARD_HIDDEN), e(0, RESUMED, app, "A"), e(2, SHUTDOWN), e(5, STARTUP), e(6, SCREEN_ON), e(7, KEYGUARD_HIDDEN))
        assertEquals(2 * min, ScreenTime.count(ev, 0, 10 * min, app))
        assertEquals(2 * min + 3 * min, ScreenTime.count(ev, 0, 10 * min))
    }

    @Test fun outOfOrderEventsAreSorted() {
        val ev = listOf(e(6, PAUSED, app, "A"), e(0, SCREEN_ON), e(1, RESUMED, app, "A"), e(0, KEYGUARD_HIDDEN))
        assertEquals(5 * min, ScreenTime.count(ev, 0, 10 * min, app))
        assertEquals(0, ScreenTime.count(ev, 10 * min, 5 * min, app))
    }

    // ------------------------------------------------------------------------------------------------ the locks

    private val zone = ZoneId.of("America/Toronto")
    private fun at(d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(LocalDateTime.of(2026, 10, d, h, mi), zone)
    private fun c(z: ZonedDateTime) = Clocks(z, 1_000, 3)
    private val first = PhoneLock("lk_read", "Reading first", LockRule.App(AppMode.FIRST, app, AppStart.At(LocalTime.of(16, 0), LockRule.WEEKDAYS), minutes = 20, latestMin = 180))
    private val limit = PhoneLock("lk_lim0", "Daily limit", LockRule.App(AppMode.LIMIT, app, limitMin = 120, until = LocalTime.of(5, 0)))
    private val file = LockFile(LockSettings(on = true), listOf(first, limit))

    @Test fun oneAppFirstEndsWhenItsTimeIsMet() {
        // Monday at 16:05: on, until 19:00 at the latest.
        val on = LockSchedule.refresh(file, LockState(), c(at(19, 16, 5)))
        assertEquals(listOf("lk_read"), on.started.map { it.lock })
        assertEquals(at(19, 19).toInstant().toEpochMilli(), on.state.active.single().end)
        val part = LockSchedule.countApps(file, on.state, mapOf("lk_read" to 8 * min), c(at(19, 16, 20)))
        assertEquals(8 * min, part.state.active.single().usedMs)
        assertEquals(12 * min, LockSchedule.appLeftMs(part.state.active.single(), first.rule as LockRule.App))
        // A smaller count later (events gone) never takes time back.
        assertEquals(8 * min, LockSchedule.countApps(file, part.state, mapOf("lk_read" to 2 * min), c(at(19, 16, 21))).state.active.single().usedMs)
        val done = LockSchedule.countApps(file, part.state, mapOf("lk_read" to 20 * min), c(at(19, 16, 40)))
        assertEquals(listOf("lk_read"), done.ended.map { it.lock })
        assertTrue(done.state.active.isEmpty())
        // The next refresh doesn't put it back on today.
        assertTrue(LockSchedule.refresh(file, done.state, c(at(19, 16, 41))).state.active.isEmpty())
        // At its latest end it is over anyway.
        assertTrue(LockSchedule.refresh(file, part.state, c(at(19, 19, 0))).state.active.isEmpty())
        // Saturday isn't one of its days.
        assertTrue(LockSchedule.refresh(file, LockState(), c(at(17, 16, 5))).started.isEmpty())
        // It warns and is the next lock like a Time of day lock.
        assertEquals("lk_read", LockSchedule.nextLock(file, LockState(), at(19, 15))!!.lock.id)
    }

    @Test fun dailyLimitComesOnOnceADay() {
        val st = LockState()
        assertEquals(30 * min, LockSchedule.limitLeftMs(file, st, at(19, 20), 90 * min))
        assertTrue(LockSchedule.startLimits(file, st, c(at(19, 20)), 90 * min).started.isEmpty())
        val on = LockSchedule.startLimits(file, st, c(at(19, 20)), 120 * min)
        assertEquals(listOf("lk_lim0"), on.started.map { it.lock })
        // Until 5:00 the next morning, across midnight.
        assertEquals(at(20, 5).toInstant().toEpochMilli(), on.state.active.single().end)
        assertTrue(LockSchedule.refresh(file, on.state, c(at(20, 1))).state.active.isNotEmpty())
        val after = LockSchedule.refresh(file, on.state, c(at(20, 5))).state
        assertTrue(after.active.isEmpty())
        // Not again on the day it came on; armed again the next day.
        assertNull(LockSchedule.limitLeftMs(file, on.state.copy(active = emptyList()), at(19, 23), 200 * min))
        assertEquals(0L, LockSchedule.limitLeftMs(file, after, at(20, 20), 200 * min))
        // Only the chosen app stays open.
        assertEquals(setOf(app), LockSchedule.allowedPackages(file, on.state))
    }
}
