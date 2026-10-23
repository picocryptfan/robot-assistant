// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

// Phase B: the extras and car mode (§9.3, §5.2 rule 1).
class LockExtrasTest {
    private val maps = "org.example.maps"
    private fun lock(id: String, e: LockExtras) = PhoneLock(id, id, LockRule.Time(TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0))), extras = e)
    private val a = lock("lk_aaaa", LockExtras(people = listOf("p_mom", "p_dad"), maps = true, music = "org.example.music", apps = listOf("org.example.read")))
    private val b = lock("lk_bbbb", LockExtras(people = listOf("p_mom"), maps = false, apps = listOf("org.example.read")))
    private val file = LockFile(LockSettings(on = true), listOf(a, b))
    private fun on(vararg ids: String) = LockState(active = ids.map { ActiveLock(it, 0, 1) })

    @Test fun oneLocksExtrasStayOpen() {
        val st = on("lk_aaaa")
        assertEquals(setOf("org.example.music", "org.example.read", maps), LockSchedule.allowedPackages(file, st, maps))
        assertEquals(setOf("p_mom", "p_dad"), LockSchedule.allowedPeople(file, st))
        assertTrue(LockSchedule.mapsAllowed(file, st))
        // Without a maps app on the phone, nothing more.
        assertEquals(setOf("org.example.music", "org.example.read"), LockSchedule.allowedPackages(file, st))
    }

    @Test fun overlapsLeaveOnlyWhatBothAllow() {
        val st = on("lk_aaaa", "lk_bbbb")
        assertEquals(setOf("org.example.read"), LockSchedule.allowedPackages(file, st, maps))
        assertEquals(setOf("p_mom"), LockSchedule.allowedPeople(file, st))
        assertFalse(LockSchedule.mapsAllowed(file, st))
    }

    /** 1.2 (job 6b): a never-open app in a lock (saved before the list grew, or restored) isn't let through, nor offered to the panel. */
    @Test fun neverOpenAppsAreNeverAllowed() {
        val store = "com.sec.android.app.samsungapps"
        val old = lock("lk_cccc", LockExtras(maps = true, music = "com.google.android.apps.nbu.files", apps = listOf(store, "com.android.settings", "org.example.read")))
        val app = PhoneLock("lk_dddd", "lk_dddd", LockRule.App(AppMode.FIRST, "com.samsung.android.lool"))
        val f = LockFile(LockSettings(on = true), listOf(old, app))
        assertEquals(setOf("org.example.read", maps), LockSchedule.allowedPackages(f, on("lk_cccc"), maps))
        assertTrue(LockSchedule.allowedPackages(f, on("lk_dddd")).isEmpty())
        // Not even as the phone's maps app.
        assertEquals(setOf("org.example.read"), LockSchedule.allowedPackages(f, on("lk_cccc"), "com.android.vending"))
    }

    @Test fun aLockByVoiceHasNoExtras() {
        val st = on("lk_aaaa", ActiveLock.VOICE)
        assertTrue(LockSchedule.allowedPackages(file, st, maps).isEmpty())
        assertTrue(LockSchedule.allowedPeople(file, st).isEmpty())
        assertFalse(LockSchedule.mapsAllowed(file, st))
        assertFalse(LockSchedule.mapsAllowed(file, LockState()))
    }

    @Test fun carModeByTheCar() {
        val f = GateFacts(active = true, ownPackage = "ai.robot_app")
        // Your own lock: nothing covered while the car's car mode is on.
        assertEquals(GateDecision.IGNORE, LockGate.decide("org.example.browser", "B", f.copy(carOwnLock = true)))
        // Someone else's: the maps app stays open, the rest is covered.
        assertEquals(GateDecision.ALLOW, LockGate.decide(maps, "M", f.copy(carApps = setOf(maps))))
        assertEquals(GateDecision.COVER, LockGate.decide("org.example.browser", "B", f.copy(carApps = setOf(maps))))
        // The state keeps it, for 12 hours at most.
        val st = LockState(car = 1_000)
        assertTrue(st.carOn(2_000))
        assertFalse(st.carOn(1_000 + LockState.CAR_MAX_MS))
        assertFalse(LockState().carOn(2_000))
    }
}
