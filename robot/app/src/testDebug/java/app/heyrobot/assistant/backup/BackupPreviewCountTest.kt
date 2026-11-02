// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.sys.RoutineFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/** The preview's "Kept safer" says what the restore then says, hands-free and routines included (FB1's note). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPreviewCountTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"

    @Before fun setUp() {
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
    }

    @After fun clean() {
        ctx.filesDir.deleteRecursively()
    }

    @Test fun thePreviewCountsHandsFreeAndRoutinesLikeTheRestore() {
        val hf = HandsFreeFiles.store(ctx)
        val rs = RoutineFiles.store(ctx)
        // Off, then on: a store writes only a change, and a default alone leaves no file to back up.
        hf.editHeadset { it.copy(safety = it.safety.copy(replyAfterSpokenYes = false)) }
        hf.editHeadset { it.copy(safety = it.safety.copy(replyAfterSpokenYes = true)) }
        rs.editSettings { it.copy(online = false) }
        rs.editSettings { it.copy(online = true) }
        val out = File(ctx.cacheDir, "Robot backup.robotbackup")
        val chosen = setOf(BackupCategory.HANDSFREE, BackupCategory.ROUTINES)
        val done = BackupJob(ctx, chosen, recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(done.toString(), done is BackupJob.Outcome.Done)
        // This phone is safer than the file on both.
        hf.editHeadset { it.copy(safety = it.safety.copy(replyAfterSpokenYes = false)) }
        rs.editSettings { it.copy(online = false) }

        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(out)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val preview = s.keptSafer(RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v), false))
        val r = s.apply(RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v), false)) {}
        s.close()
        assertEquals("$v ${r.outcomes}", CategoryOutcome.Done, r.outcomes[BackupCategory.HANDSFREE])
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.ROUTINES])
        assertTrue("both kept: ${r.keptSafer}", r.keptSafer >= 2)
        assertEquals(r.keptSafer, preview)
    }

    /** Made here with hands-free on, then this phone holds a saved place, so Add keeps nothing of the file's and Replace keeps this phone's safer switch. */
    private fun opened(on: Context = ctx): RestoreSession {
        val hf = HandsFreeFiles.store(ctx)
        hf.editHeadset { it.copy(safety = it.safety.copy(replyAfterSpokenYes = false)) }
        hf.editHeadset { it.copy(safety = it.safety.copy(replyAfterSpokenYes = true)) }
        val out = File(ctx.cacheDir, "Robot backup.robotbackup")
        val done = BackupJob(ctx, setOf(BackupCategory.HANDSFREE), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(done.toString(), done is BackupJob.Outcome.Done)
        hf.editHeadset { it.copy(safety = it.safety.copy(replyAfterSpokenYes = false)) }
        hf.editPlaces { it.copy(home = SavedPlace("Home", 44.6, -63.6)) }
        val s = RestoreSession(on)
        assertNull(s.copyIn(Uri.fromFile(out)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        return s
    }

    @Test fun thePreviewCountsForTheModeYouChose() {
        val s = opened()
        val v = s.verified!!
        fun plan(c: CategoryChoice) = RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v) + (BackupCategory.HANDSFREE to c), false)
        val add = s.keptSafer(plan(CategoryChoice()))
        val replace = s.keptSafer(plan(CategoryChoice(replace = true)))
        assertEquals(0, add)
        assertTrue("replace: $replace", replace!! >= 1)
        // Left out, it keeps nothing.
        assertEquals(0, s.keptSafer(plan(CategoryChoice(restore = false))))
        // What the restore then says.
        val r = s.apply(plan(CategoryChoice(replace = true))) {}
        s.close()
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.HANDSFREE])
        assertEquals(replace, r.keptSafer)
    }

    @Test fun aCountThatFailsIsNotReadAsNothingKept() {
        var fail = false
        val failing = object : ContextWrapper(ctx) {
            override fun getFilesDir(): File = if (fail) throw java.io.IOException("no storage") else super.getFilesDir()
        }
        val s = opened(failing)
        val v = s.verified!!
        val plan = RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v) + (BackupCategory.HANDSFREE to CategoryChoice(replace = true)), false)
        fail = true
        assertNull(s.keptSafer(plan))
        fail = false
        assertTrue(s.keptSafer(plan)!! >= 1)
        s.close()
    }
}
