// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.TodayOffer
import app.heyrobot.core.TodayPart
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.sys.HomeSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/** H5: the Today page's order, pinned note and Today on go in Robot's backup and come back on a new phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeBackupTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"
    private val mine = listOf(TodayPart.NOTE, TodayPart.CALENDAR, TodayPart.LISTS)

    @Before fun setUp() { ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000) }

    @After fun clean() { ctx.filesDir.deleteRecursively() }

    private fun backup(): File {
        val out = File(ctx.cacheDir, "home.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.HOME), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        assertEquals(setOf(BackupCategory.HOME), (r as BackupJob.Outcome.Done).categories)
        return out
    }

    private fun restore(file: File, yours: Boolean = true, outcome: CategoryOutcome = CategoryOutcome.Done) {
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(file)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        assertEquals(mine, v.home?.order)
        val r = s.apply(RestorePlanner.plan(v, s.phone(), mapOf(BackupCategory.HOME to CategoryChoice()), yours)) {}
        s.close()
        assertEquals(r.toString(), outcome, r.outcomes[BackupCategory.HOME])
    }

    @Test fun theOrderAndTodayComeBackOnANewPhone() {
        HomeSettingsStore.update(ctx) { HomeSettings(order = mine, note = "n1").turnedOn() }
        val file = backup()
        HomeSettingsStore.file(ctx).delete()
        assertEquals(TodayPart.DEFAULT, HomeSettingsStore.current(ctx).order)
        restore(file)
        val h = HomeSettingsStore.current(ctx)
        assertEquals(mine, h.order)
        assertTrue(h.today)
        assertEquals("n1", h.note)
        assertEquals(TodayOffer.TAKEN, h.offer)
    }

    @Test fun theWeatherLineComesBackFromYourOwnBackupOnly() {
        HomeSettingsStore.update(ctx) { HomeSettings(order = mine, weather = true).turnedOn() }
        val file = backup()
        HomeSettingsStore.file(ctx).delete()
        // Someone else's backup: today-fix-b restores none of Today from it (ONLY_YOURS), so the weather line stays off;
        // RestoreSafety.home still guards the line (GUARDED) should HOME ever come from a backup that isn't yours.
        restore(file, yours = false, outcome = CategoryOutcome.Skipped(app.heyrobot.core.backup.PlanSkip.NOT_YOURS))
        assertFalse(HomeSettingsStore.current(ctx).today)
        assertFalse(HomeSettingsStore.current(ctx).weather)
        HomeSettingsStore.file(ctx).delete()
        restore(file)
        assertTrue(HomeSettingsStore.current(ctx).weather)
    }

    @Test fun theFetchedForecastIsNeverInTheBackup() {
        HomeSettingsStore.update(ctx) { HomeSettings(order = mine, weather = true).turnedOn() }
        val stored = app.heyrobot.sys.TodayWeatherStore.file(ctx).apply { parentFile?.mkdirs(); writeText("{\"v\":1}") }
        val file = backup()
        stored.delete()
        HomeSettingsStore.file(ctx).delete()
        restore(file)
        assertTrue(HomeSettingsStore.current(ctx).weather)
        assertFalse(stored.exists())
    }

    @Test fun todayOffInTheBackupKeepsThisPhonesDeclinedOffer() {
        HomeSettingsStore.update(ctx) { HomeSettings(order = mine) }
        val file = backup()
        HomeSettingsStore.update(ctx) { HomeSettings().declined() }
        restore(file)
        val h = HomeSettingsStore.current(ctx)
        assertEquals(mine, h.order)
        assertFalse(h.today)
        assertEquals(TodayOffer.DECLINED, h.offer)
    }
}
