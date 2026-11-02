// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.core.TimeWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Phone lock's service, run for real as a lock starts: when the cover goes up, and how. The events of a real phone stay in LockCoverWatchTest's source checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockCoverServiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val service by lazy { Robolectric.buildService(LockAccessibilityService::class.java).create().get() }

    private fun lockOn(): LockEngine.Snapshot {
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val l = PhoneLock("lk_cover", "Homework", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true), listOf(l)) })
        return LockEngine.refresh(ctx).also { assertTrue(it.active) }
    }

    @After fun lockOff() {
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
    }

    private fun started(): Intent? {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    }

    /** F94: the app under the cover isn't told you left it, so a video doesn't float over in picture-in-picture. */
    @Test fun aLockStartingBringsUpTheCoverAndTheAppBelowIsNotToldYouLeft() {
        service.onEngine(lockOn(), startedNow = true)
        val cover = started()!!
        assertEquals(LockSignals.COVER, cover.component!!.className)
        assertTrue(cover.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
    }

    @Test fun carModeYourCarStartedDuringYourOwnLockBringsUpNoCover() {
        val s = lockOn()
        LockStore.editState(ctx) { it.copy(car = System.currentTimeMillis() - 1000) }
        service.onEngine(LockEngine.refresh(ctx).also { assertTrue(it.active && s.active) }, startedNow = true)
        assertNull(started())
    }

    @Test fun aLockAlreadyOnBringsUpNoCoverOnARefresh() {
        service.onEngine(lockOn(), startedNow = false)
        assertNull(started())
    }
}
