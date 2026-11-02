// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.lock.ActiveLock
import app.heyrobot.core.lock.AppMode
import app.heyrobot.core.lock.AppStart
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.ui.settings.phoneLockSummaryText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalTime

/** Settings › Phone lock's row during a One app first lock gives its count, as the notice does (the user, 28 Oct). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLockRowTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app = "org.example.reader"
    private val en = Langs(Lang.EN, Lang.EN, Lang.EN)
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)

    @After fun tearDown() {
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
    }

    @Test fun oneAppFirstShowsItsCount() {
        val first = PhoneLock("lk_read", "Reading first", LockRule.App(AppMode.FIRST, app, AppStart.At(LocalTime.of(16, 0), LockRule.WEEKDAYS), minutes = 20, latestMin = 180))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true), listOf(first)) })
        val now = System.currentTimeMillis()
        LockStore.editState(ctx) { it.copy(active = listOf(ActiveLock("lk_read", now - 600_000, now + 3_600_000, usedMs = 8 * 60_000L + 59_000))) }
        // The app isn't installed here, so its package stands for its name.
        assertEquals("On now, 8 of 20 minutes in $app done", phoneLockSummaryText(ctx).render(en))
        assertEquals("En cours, 8 minutes sur 20 passées dans $app", phoneLockSummaryText(ctx).render(fr))
    }

    @Test fun withNoLockOnItCountsTheLocks() {
        val first = PhoneLock("lk_read", "Reading first", LockRule.App(AppMode.FIRST, app, AppStart.At(LocalTime.of(16, 0), LockRule.WEEKDAYS), minutes = 20, latestMin = 180))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true), listOf(first)) })
        assertEquals("On, 1 lock", phoneLockSummaryText(ctx).render(en))
    }
}
