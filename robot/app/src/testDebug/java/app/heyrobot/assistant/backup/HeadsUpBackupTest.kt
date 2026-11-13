// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.core.headsup.HeadsUpSaid
import app.heyrobot.core.headsup.HeadsUpSettings
import app.heyrobot.sys.HeadsUpStore
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

/** earbud-headsup-c: the heads-up's settings go in Robot's backup (HEADSUP) and come back; the events already handled don't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeadsUpBackupTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"
    private val mine = HeadsUpSettings(on = true, minutes = 10)

    @Before fun setUp() { ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000) }

    @After fun clean() { ctx.filesDir.deleteRecursively() }

    private fun backup(): File {
        HeadsUpStore.save(ctx, mine)
        HeadsUpStore.saveSaid(ctx, HeadsUpSaid(mapOf("e:1" to 1L)))
        val out = File(ctx.cacheDir, "headsup.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.HEADSUP), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        assertEquals(setOf(BackupCategory.HEADSUP), (r as BackupJob.Outcome.Done).categories)
        ctx.filesDir.deleteRecursively()
        return out
    }

    private fun restore(file: File, yours: Boolean) {
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(file)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        assertEquals(mine, v.headsUp)
        val r = s.apply(RestorePlanner.plan(v, s.phone(), mapOf(BackupCategory.HEADSUP to CategoryChoice()), yours)) {}
        s.close()
        assertEquals(r.toString(), CategoryOutcome.Done, r.outcomes[BackupCategory.HEADSUP])
    }

    @Test fun theHeadsUpComesBackOnANewPhone() {
        val file = backup()
        assertFalse(HeadsUpStore.file(ctx).isFile)
        restore(file, yours = true)
        assertEquals(mine, HeadsUpStore.settings(ctx))
        assertEquals(HeadsUpSaid(), HeadsUpStore.said(ctx))
    }

    @Test fun someoneElsesBackupComesBackWithTheHeadsUpOff() {
        restore(backup(), yours = false)
        val s = HeadsUpStore.settings(ctx)
        assertFalse(s.on)
        assertEquals(10, s.minutes)
    }
}
