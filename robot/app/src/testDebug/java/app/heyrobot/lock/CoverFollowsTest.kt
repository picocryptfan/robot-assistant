// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.lock.LockExtras
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * A cover that stays up shows the engine's state as it changes. On a Pixel 8 (1.1.2 test build 1) one cover said "123
 * minutes left" for 19 minutes, and an app added during an edit session got no Open button until a new cover came.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverFollowsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val reader = "org.example.reader"
    private val calculator = "org.example.calculator"
    private lateinit var cover: ActivityController<LockCoverActivity>
    private val start = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
    /** The cover's wall clock, moved on only by a test. */
    private var t = System.currentTimeMillis()

    private fun homework(end: LocalTime, apps: List<String> = listOf(reader)) =
        PhoneLock("lk_hw00", "Homework", LockRule.Time(TimeWindow(start.minusHours(1), end), DayOfWeek.entries.toSet()), extras = LockExtras(apps = apps))

    private fun install(pkg: String, label: String) = shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
        packageName = pkg
        applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
    })

    /** What Settings › Phone lock saves, and :lock's refresh as its signal arrives. */
    private fun save(lock: PhoneLock, forWho: LockFor = LockFor.SOMEONE_ELSE) {
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true, forWho = forWho), listOf(lock)) })
        LockEngine.refresh(ctx)
        settle()
    }

    @Before fun setUp() {
        install(reader, "Reader")
        install(calculator, "Calculator")
        save(homework(start.plusHours(2)))
        assertTrue(LockEngine.snapshot!!.active)
        LockCoverActivity.wallClock = { t }
        cover = Robolectric.buildActivity(LockCoverActivity::class.java).setup()
        settle()
    }

    @After fun tearDown() {
        cover.pause().stop().destroy()
        LockCoverActivity.wallClock = System::currentTimeMillis
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
        settle()
    }

    /** The cover's own thread done (onResume's refresh), and what it and the engine posted run. */
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            val until = System.currentTimeMillis() + 5_000
            while (Thread.getAllStackTraces().keys.any { it.name == "robot-lock" && it.isAlive } && System.currentTimeMillis() < until) Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()
    private fun showsNot(text: String) = compose.onNodeWithText(text).assertDoesNotExist()

    /** The minutes left as the cover works them out from the snapshot it shows. */
    private fun left(): Long {
        val opens = LockEngine.snapshot!!.opensAt!!.toInstant().toEpochMilli()
        return (opens - t + 59_999) / 60_000
    }

    /** The time the cover last took, while it was resumed (its tick, or the engine). */
    private fun coverNow(): Long =
        (LockCoverActivity::class.java.getDeclaredField("now\$delegate").apply { isAccessible = true }.get(cover.get()) as androidx.compose.runtime.MutableLongState).longValue

    @Test fun anAppAddedWhileTheCoverIsUpGetsItsButton() {
        shows("Open Reader")
        showsNot("Open Calculator")
        save(homework(start.plusHours(2), listOf(reader, calculator)))
        shows("Open Calculator")
        shows("Open Reader")
        // And taken away again.
        save(homework(start.plusHours(2), listOf(calculator)))
        showsNot("Open Reader")
    }

    @Test fun theMinutesLeftFollowAChangedEnd() {
        shows("${left()} minutes left")
        save(homework(start.plusMinutes(90)))
        shows("${left()} minutes left")
        assertTrue(left() in 85..90)
    }

    @Test fun theMinutesLeftDropAsTimePasses() {
        val first = left()
        shows("$first minutes left")
        // A minute on, by the wall clock and the main thread's: the tick redraws the cover, nothing else does.
        t += 60_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        settle()
        shows("${first - 1} minutes left")
        showsNot("$first minutes left")
    }

    @Test fun whoItsForFollowsTheLock() {
        shows("Set with a lock code.")
        save(homework(start.plusHours(2)), LockFor.ME)
        showsNot("Set with a lock code.")
    }

    @Test fun theCoverClosesWhenTheLockEnds() {
        assertFalse(cover.get().isFinishing)
        save(homework(start.plusHours(2)).copy(on = false))
        assertFalse(LockEngine.snapshot!!.active)
        assertTrue(cover.get().isFinishing)
    }

    @Test fun theTickRunsOnlyWhileTheCoverIsResumed() {
        cover.pause()
        settle()
        val paused = coverNow()
        t += 3 * 60_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        settle()
        assertEquals(paused, coverNow())
        cover.resume()
        settle()
        assertEquals(t, coverNow())
    }

    @Test fun theNextTickComesAsTheMinutesLeftChange() {
        val s = LockEngine.snapshot!!
        val opens = s.opensAt!!.toInstant().toEpochMilli()
        assertEquals(250L, LockCoverActivity.nextTick(s, opens - 60_001))
        assertEquals(60_000L, LockCoverActivity.nextTick(s, opens - 60_000))
        assertEquals(30_000L, LockCoverActivity.nextTick(s, opens - 90_000))
        assertEquals(60_000L, LockCoverActivity.nextTick(null, opens))
        assertEquals(60_000L, LockCoverActivity.nextTick(s, opens + 1))
    }
}
