// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupCode
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.backup.PlanSkip
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.core.backup.RestoreResult
import app.heyrobot.core.calendar.PrivateEdits
import app.heyrobot.core.calendar.PrivateEvent
import app.heyrobot.core.calendar.PrivateEnvelope
import app.heyrobot.sys.PrivateEventsFiles
import app.heyrobot.sys.PrivateLoad
import app.heyrobot.sys.SoftMemoryKeys
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

/**
 * H3: private events go in Robot's backup (the plain events inside its encryption, never the sealed file or a key) and come
 * back on a new phone sealed under that phone's key; someone else's backup never brings them; a vault that can't be opened
 * stops the backup with its words and is never written over by a restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivateEventsBackupTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"
    private val t0 = 1_760_270_000_000L
    private fun ev(title: String, h: Int, changed: Long = 1) = PrivateEvent(PrivateEdits.newId(), title, t0 + h * 3_600_000L, t0 + (h + 1) * 3_600_000L, false, "12 Main St", changed)

    @Before fun setUp() {
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
        PrivateEventsFiles.testKeys = SoftMemoryKeys()
    }

    @After fun clean() {
        PrivateEventsFiles.testKeys = null
        ctx.filesDir.deleteRecursively()
    }

    private fun save(vararg e: PrivateEvent) = e.forEach { x -> PrivateEventsFiles.vault(ctx).update { f, now -> PrivateEdits.save(f, x, now) } }
    private fun titles() = PrivateEventsFiles.events(ctx).map { it.title }.toSet()

    private fun backup(): File {
        val out = File(ctx.cacheDir, "private.robotbackup")
        val r = BackupJob(ctx, setOf(BackupCategory.CALENDAR), recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        assertEquals(setOf(BackupCategory.CALENDAR), (r as BackupJob.Outcome.Done).categories)
        return out
    }

    private fun restore(file: File, mine: Boolean, replace: Boolean = false): RestoreResult {
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(file)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        assertEquals(2, v.privateEvents?.events?.size)
        val r = s.apply(RestorePlanner.plan(v, s.phone(), mapOf(BackupCategory.CALENDAR to CategoryChoice(replace = replace)), mine)) {}
        s.close()
        return r
    }

    @Test fun theyGoToANewPhoneSealedUnderItsKey() {
        save(ev("Surprise party planning", 2), ev("Counsellor", 5))
        assertEquals(2, BackupSources.survey(ctx, RobotSettings()).getValue(BackupCategory.CALENDAR).count)
        val out = backup()
        assertFalse(String(out.readBytes(), Charsets.ISO_8859_1).contains("Surprise"))

        // A new phone: nothing there, its own keys.
        PrivateEventsFiles.dir(ctx).deleteRecursively()
        val newKeys = SoftMemoryKeys()
        PrivateEventsFiles.testKeys = newKeys
        assertEquals(CategoryOutcome.Done, restore(out, mine = true).outcomes[BackupCategory.CALENDAR])
        assertEquals(setOf("Surprise party planning", "Counsellor"), titles())
        assertTrue(PrivateEnvelope.ALIAS in newKeys.keys)
        assertEquals(PrivateEnvelope.ALIAS, PrivateEnvelope.alias(File(PrivateEventsFiles.dir(ctx), "events.enc").readBytes()))

        // Add again: nothing doubled; one made here since stays.
        save(ev("Made here", 8))
        restore(out, mine = true)
        assertEquals(setOf("Surprise party planning", "Counsellor", "Made here"), titles())
        // Replace: the backup's.
        restore(out, mine = true, replace = true)
        assertEquals(setOf("Surprise party planning", "Counsellor"), titles())
    }

    @Test fun someoneElsesBackupNeverBringsThem() {
        save(ev("Surprise party planning", 2), ev("Counsellor", 5))
        val out = backup()
        PrivateEventsFiles.dir(ctx).deleteRecursively()
        val r = restore(out, mine = false)
        assertEquals(CategoryOutcome.Skipped(PlanSkip.NOT_YOURS), r.outcomes[BackupCategory.CALENDAR])
        assertTrue(PrivateEventsFiles.events(ctx).isEmpty())
    }

    @Test fun aVaultThatCantBeOpenedIsLeftOutOfTheBackupAndIsNeverWrittenOver() {
        save(ev("Surprise party planning", 2), ev("Counsellor", 5))
        val out = backup()
        // Its key gone (a new SoftMemoryKeys without it): unreadable.
        PrivateEventsFiles.testKeys = SoftMemoryKeys()
        assertTrue(PrivateEventsFiles.vault(ctx).load() is PrivateLoad.Unreadable)
        assertEquals(-1, BackupSources.survey(ctx, RobotSettings()).getValue(BackupCategory.CALENDAR).count)
        // Today fix: the backup goes on without them and says so (it stopped with code 13 before).
        val done = BackupJob(ctx, setOf(BackupCategory.CALENDAR, BackupCategory.SETTINGS), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "no.robotbackup")), pass) {}
        assertTrue(done.toString(), done is BackupJob.Outcome.Done && done.privateLeftOut && BackupCategory.CALENDAR !in done.categories)
        val sealed = File(PrivateEventsFiles.dir(ctx), "events.enc").readBytes()
        assertEquals(CategoryOutcome.Skipped(PlanSkip.CALENDAR_UNREADABLE), restore(out, mine = true).outcomes[BackupCategory.CALENDAR])
        assertTrue(sealed.contentEquals(File(PrivateEventsFiles.dir(ctx), "events.enc").readBytes()))
        // Start over keeps the file aside (never deleted); then a restore brings the events back, and saving works again.
        assertTrue(PrivateEventsFiles.vault(ctx).startOver())
        assertTrue(sealed.contentEquals(File(PrivateEventsFiles.dir(ctx), "events.enc.bad").readBytes()))
        assertEquals(setOf<String>(), titles())
        assertFalse("only a file Robot can't read", PrivateEventsFiles.vault(ctx).startOver())
        restore(out, mine = true)
        assertEquals(setOf("Surprise party planning", "Counsellor"), titles())
        save(ev("Physio", 7))
        assertEquals(3, titles().size)
    }

    @Test fun aLockedVaultStillStopsTheBackup() {
        save(ev("Counsellor", 5))
        org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceLocked(true)
        try {
            assertFalse(PrivateEventsFiles.vault(ctx).startOver())
            val failed = BackupJob(ctx, setOf(BackupCategory.CALENDAR), recordings = false, pictures = true).run(Uri.fromFile(File(ctx.cacheDir, "no.robotbackup")), pass) {}
            assertEquals(BackupCode.CALENDAR_UNREADABLE, (failed as BackupJob.Outcome.Failed).code)
        } finally {
            org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceLocked(false)
        }
    }
}
