// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ConvMode
import app.heyrobot.core.LangPair
import app.heyrobot.core.TranscriptEvent
import app.heyrobot.core.TranscriptFiles
import app.heyrobot.core.TranscriptKeyAliases
import app.heyrobot.core.TranscriptSeal
import app.heyrobot.core.TranscriptWriter
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupCode
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.sys.SoftListsKeys
import app.heyrobot.sys.Transcripts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/** fix-seal-transcripts: sealed transcripts in a backup and back (item 7), and a locked phone's own message (item 6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptsBackupTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(120)
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"
    private val id = "20261111-0900-a1b2"
    private val keys = SoftListsKeys(setOf(TranscriptKeyAliases.UNLOCKED))

    @Before fun setUp() {
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
        Transcripts.testKeys(keys)
    }

    @After fun clean() {
        keyguard(false)
        ctx.filesDir.deleteRecursively()
    }

    private fun keyguard(on: Boolean) = Shadows.shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(on)

    private fun record() {
        val w = TranscriptWriter.start(Transcripts.store(ctx), id, 1_000L, ConvMode.TRANSCRIBE, LangPair.DEFAULT)!!
        w.line(2_000L) { i -> TranscriptEvent.Line(i, 0.0, 0.5, "S1", "en", "Gate code 4411") }
        w.close(3_000L)
        Transcripts.store(ctx).list()
    }

    private fun log() = File(ctx.filesDir, "${TranscriptFiles.DIR}/$id/${TranscriptFiles.LOG}")

    @Test fun aLockedPhoneSaysUnlockForTranscripts() {
        record()
        keyguard(true)
        keys.locked = true
        val failed = BackupJob(ctx, setOf(BackupCategory.TRANSCRIPTS), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "t.robotbackup")), pass) {}
        assertEquals(BackupCode.TRANSCRIPTS_LOCKED, (failed as BackupJob.Outcome.Failed).code)
    }

    /** fix-seal-transcripts-b, Medium 1: the Keystore failing on an unlocked phone has its own words, not "unlock". */
    @Test fun aKeystoreFaultOnAnUnlockedPhoneHasItsOwnWords() {
        record()
        Transcripts.drop() // the screen went off: the data key left RAM
        keys.busy = true
        val failed = BackupJob(ctx, setOf(BackupCategory.TRANSCRIPTS), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "t.robotbackup")), pass) {}
        assertEquals(BackupCode.TRANSCRIPTS_UNREADABLE, (failed as BackupJob.Outcome.Failed).code)
    }

    /** fix-seal-transcripts-b, Medium 2: a damaged sealed snapshot is kept aside, and the backup goes on without it. */
    @Test fun aDamagedTranscriptIsKeptAsideNotFailingEveryBackup() {
        record()
        val snap = File(ctx.filesDir, "${TranscriptFiles.DIR}/$id/${TranscriptFiles.SNAPSHOT}")
        val t = snap.readText().trimEnd()
        val i = t.length * 3 / 4
        snap.writeText(t.substring(0, i) + (if (t[i] == 'A') 'B' else 'A') + t.substring(i + 1))
        val r = BackupJob(ctx, setOf(BackupCategory.TRANSCRIPTS), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "t.robotbackup")), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        assertEquals(1, Transcripts.store(ctx).asideCount())
    }

    /** fix-seal-transcripts-c, Medium 1: a folder set aside during the backup is counted in Done, and Transcripts' line says how many are kept aside. */
    @Test fun aTranscriptLeftOutIsSaidInDoneAndInItsLine() {
        record()
        val snap = File(ctx.filesDir, "${TranscriptFiles.DIR}/$id/${TranscriptFiles.SNAPSHOT}")
        val t = snap.readText().trimEnd()
        val i = t.length * 3 / 4
        snap.writeText(t.substring(0, i) + (if (t[i] == 'A') 'B' else 'A') + t.substring(i + 1))
        val r = BackupJob(ctx, setOf(BackupCategory.TRANSCRIPTS), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "t.robotbackup")), pass) {}
        assertEquals(1, (r as BackupJob.Outcome.Done).transcriptsLeftOut)
        assertEquals(1, BackupSources.survey(ctx, app.heyrobot.core.RobotSettings()).getValue(BackupCategory.TRANSCRIPTS).aside)
    }

    /** The first review's 9: a transcript whose key Android dropped, not yet set aside, fails no backup (settle runs first). */
    @Test fun aLostKeysTranscriptFailsNoBackup() {
        record()
        Transcripts.drop()
        keys.invalid += TranscriptKeyAliases.UNLOCKED
        val r = BackupJob(ctx, setOf(BackupCategory.TRANSCRIPTS), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "t.robotbackup")), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        assertEquals(1, Transcripts.store(ctx).asideCount())
    }

    @Test fun sealedTranscriptsGoThroughABackupAndBack() {
        record()
        assertTrue(log().readText().split('\n').filter { it.isNotEmpty() }.all { it.startsWith(TranscriptSeal.MARK) })
        val out = File(ctx.cacheDir, "t.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.TRANSCRIPTS), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        assertFalse(String(out.readBytes(), Charsets.ISO_8859_1).contains("Gate code"))
        File(ctx.filesDir, "${TranscriptFiles.DIR}/$id").deleteRecursively()
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(out)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val res = s.apply(RestorePlanner.plan(s.verified!!, s.phone(), mapOf(BackupCategory.TRANSCRIPTS to CategoryChoice(replace = false)), true)) {}
        s.close()
        assertTrue(res.toString(), log().isFile)
        assertFalse("restored sealed", log().readText().contains("Gate code"))
        assertEquals(listOf("Gate code 4411"), Transcripts.store(ctx).load(id)!!.lines.map { it.text })
    }
}
