// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupCode
import app.heyrobot.core.backup.JournalState
import app.heyrobot.core.backup.OpenProblem
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.Paths
import app.heyrobot.sys.RestoreFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/** Back up now and a restore's run, where they go wrong: F4, F23, F24, F91, F132, U1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRunTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"
    private val settings = setOf(BackupCategory.SETTINGS)

    @Before fun up() {
        VoiceLog.resetForTests()
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
        File(Paths.dir(ctx), "settings.json").writeText(app.heyrobot.core.RobotSettings().toJson().toString())
        Documents.deleted = 0
    }

    @After fun down() {
        ctx.filesDir.deleteRecursively()
    }

    /** A document as the file picker hands it over: its size, and a delete that's counted. */
    class Documents : ContentProvider() {
        companion object { var size = 0L; var deleted = 0 }
        override fun onCreate() = true
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(size)) }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle { deleted++; return Bundle() }
        override fun getType(u: Uri): String? = null
        override fun insert(u: Uri, v: ContentValues?): Uri? = null
        override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private fun document(size: Long): Uri {
        Documents.size = size
        Robolectric.setupContentProvider(Documents::class.java, "test.documents")
        return DocumentsContract.buildDocumentUri("test.documents", "d")
    }

    private fun job() = BackupJob(ctx, settings, recordings = false, pictures = true)

    /** F4: never a file sealed with no passphrase; the new empty document goes. */
    @Test fun anEmptyPassphraseIsRefused() {
        val r = job().run(document(0), "") {}
        assertEquals(BackupCode.FAILED, (r as BackupJob.Outcome.Failed).code)
        assertEquals(1, Documents.deleted)
    }

    /** F24: a document with something in it, or whose size can't be told, stays when nothing was written. */
    @Test fun aRefusalLeavesAnExistingDocument() {
        assertTrue(job().run(document(9), "") {} is BackupJob.Outcome.Failed)
        assertEquals(0, Documents.deleted)
        val f = File(ctx.cacheDir, "old.robotbackup").apply { writeText("my old one") }
        assertTrue(job().run(Uri.fromFile(f), "") {} is BackupJob.Outcome.Failed)
        assertEquals("my old one", f.readText())
    }

    /** F24: the same for Cancel before the first write. */
    @Test fun aCancelBeforeTheFirstWriteLeavesAnExistingDocument() {
        val f = File(ctx.cacheDir, "old.robotbackup").apply { writeText("my old one") }
        val j = job().apply { cancelled = true }
        assertEquals(BackupJob.Outcome.Cancelled, j.run(Uri.fromFile(f), pass) {})
        assertEquals("my old one", f.readText())
    }

    /** F23: staged lines stay while a restore is being written here; afterwards a leftover is recovered. */
    @Test fun recoveryAtStartWaitsForARunningRestore() {
        val out = File(ctx.cacheDir, "s.robotbackup")
        assertTrue(job().run(Uri.fromFile(out), pass) {} is BackupJob.Outcome.Done)
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(out)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val j = RestoreFiles.journal(ctx)
        var during: JournalState? = null
        s.apply(RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v), mine = true)) {
            if (during == null) {
                j.begin(BackupCategory.NOTES, app.heyrobot.core.backup.RestoreProcess.VOICE)
                RestoreSession.recoverAtStart(ctx)
                during = j.state(BackupCategory.NOTES)
            }
        }
        s.close()
        assertEquals(JournalState.STAGED, during)
        j.begin(BackupCategory.NOTES, app.heyrobot.core.backup.RestoreProcess.VOICE)
        RestoreSession.recoverAtStart(ctx)
        assertEquals(JournalState.ROLLED_BACK, j.state(BackupCategory.NOTES))
    }

    /** F91: the copy a cut restore left doesn't outlive the next try. */
    @Test fun aLeftoverCopyGoesFirst() {
        val stale = RestoreFiles.incoming(ctx).apply { parentFile!!.mkdirs(); writeText("left by a restore cut short") }
        val r = RestoreSession(ctx).copyIn(Uri.fromFile(File(ctx.cacheDir, "n.txt").apply { writeText("x".repeat(200)) })) {}
        assertTrue(r is RestoreSession.Refused.File && r.problem == OpenProblem.NOT_A_BACKUP)
        assertFalse(stale.exists())
    }

    /** F132: off, the row says the pairings can't go, rather than that there are none. */
    @Test fun robotToRobotOffIsNotNone() {
        assertFalse(LinkSettingsStore.current(ctx).on)
        val sum = BackupSources.survey(ctx, app.heyrobot.core.RobotSettings()).getValue(BackupCategory.LINKS)
        assertEquals(BackupSources.Summary(-1, 0, extra = BackupSources.LINKS_OFF), sum)
        assertEquals(emptySet<BackupCategory>(), BackupSources.going(setOf(BackupCategory.LINKS), mapOf(BackupCategory.LINKS to sum)))
    }

    /** U1: on, but the pairings' export is refused: the category isn't counted as written, and Done says so. */
    @Test fun aRefusedPairingsExportIsReported() {
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val out = File(ctx.cacheDir, "l.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.LINKS) + settings, recordings = false, pictures = true).run(Uri.fromFile(out), pass) {} as BackupJob.Outcome.Done
        assertTrue(r.linksLeftOut)
        assertEquals(settings, r.categories)
    }
}
