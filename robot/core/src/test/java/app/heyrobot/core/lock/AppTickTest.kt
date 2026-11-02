// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// One app first's minute tick (§8.1): the count shown moves each whole minute while its app is in front.
class AppTickTest {
    private val app = "org.example.reader"
    private val min = 60_000L
    private val first = PhoneLock("lk_read", "Reading first", LockRule.App(AppMode.FIRST, app, AppStart.At(LocalTime.of(16, 0), LockRule.WEEKDAYS), minutes = 20, latestMin = 180))
    private val file = LockFile(LockSettings(on = true), listOf(first))
    private val zone = ZoneId.of("America/Halifax")
    private fun c(mi: Int) = Clocks(ZonedDateTime.of(LocalDateTime.of(2026, 10, 19, 16, mi), zone), 1_000, 3)

    /** One app first on since 16:05, with [used] counted in its app. */
    private fun counted(used: Long): LockState {
        val on = LockSchedule.refresh(file, LockState(), c(5)).state
        return LockSchedule.countApps(file, on, mapOf("lk_read" to used), c(6)).state.also { assertEquals(used, it.active.single().usedMs) }
    }

    private fun tick(st: LockState, front: String? = app, on: Boolean = true, unlocked: Boolean = true) = LockSchedule.appTickMs(file, st, front, on, unlocked)

    @Test fun onAWholeMinuteTheNextIsAMinuteAway() = assertEquals(min, tick(counted(3 * min)))

    @Test fun halfwayThroughAMinute() = assertEquals(30_000L, tick(counted(3 * min + 30_000)))

    @Test fun justBeforeAMinute() = assertEquals(100L, tick(counted(3 * min + 59_900)))

    @Test fun theTimeLeftWinsUnderAMinute() {
        // 19 min 40 s of 20: 20 s left, sooner than the next minute.
        assertEquals(20_000L, tick(counted(19 * min + 40_000)))
    }

    @Test fun noTickWithTheAppNotInFront() {
        assertNull(tick(counted(3 * min), front = "org.example.other"))
        assertNull(tick(counted(3 * min), front = null))
    }

    @Test fun noTickWithTheScreenOff() = assertNull(tick(counted(3 * min), on = false))

    @Test fun noTickWhileLocked() = assertNull(tick(counted(3 * min), unlocked = false))

    @Test fun noTickWithoutOneAppFirst() = assertNull(LockSchedule.appTickMs(file, LockState(), app, true, true))
}
