// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import app.heyrobot.Source
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RestoreSession, BackupActivity and the main app need Android to run, so their wiring of the core rules is pinned by reading
 * the code (as ProcessIsolationTest does). The rules themselves are tested in core.
 */
class RestoreWritingPinsTest {

    /** seal-transcripts: Robolectric has no Android Keystore; the transcripts' store takes soft keys. */
    @org.junit.Before fun transcriptKeys() { app.heyrobot.sys.Transcripts.testKeys(app.heyrobot.sys.SoftListsKeys(setOf(app.heyrobot.core.TranscriptKeyAliases.UNLOCKED))) }
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun code(path: String) = Source.strip(File(root, "app/src/main/java/app/heyrobot/$path").readText(), keepStrings = true, kotlin = true)
    private val writer by lazy { code("assistant/backup/RestoreWriter.kt") }

    private fun assertBefore(first: String, then: String, text: String = writer) {
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertTrue("$first is missing", a >= 0)
        assertTrue("$then is missing", b >= 0)
        assertTrue("$first should come before $then", a < b)
    }

    /** seal-people-fix-b 2: a restored lock's chosen callers can be called before the next unlock; the editor's refresh outlives its page. */
    @Test fun aRestoredLockRebuildsTheCallList() {
        val apply = code("sys/RestoreApply.kt")
        val lock = apply.substring(apply.indexOf("BackupCategory.PHONE_LOCK ->"), apply.indexOf("BackupCategory.EVERYDAY ->"))
        assertBefore("LockStore.editLocks(ctx) { f }", "PeopleFiles.refreshCalls(ctx)", lock)
        assertTrue(!code("ui/settings/PhoneLockEditor.kt").contains("PeopleFiles.refreshCalls(ctx)"))
        // seal-people-fix-b 6: every lock edit, and Start over, rewrite it (Phone lock off: no file).
        val settings = code("ui/settings/PhoneLockSettings.kt")
        assertTrue(settings.contains("LockStore.editLocks(ctx, change).also { tick++; if (it != null) PeopleFiles.refreshCallsLater(ctx) }"))
        assertTrue(settings.contains("LockStore.startOver(ctx); PeopleFiles.refreshCallsLater(ctx)"))
    }

    /** F23: :voice's start never recovers over a restore being written in this process. */
    @Test fun voiceStartLeavesARunningRestoreAlone() {
        val hub = code("assistant/VoiceHub.kt")
        assertTrue(hub.contains("RestoreSession.recoverAtStart(app)"))
        assertTrue(!hub.contains("RestoreSession.recover(app)"))
    }

    /** F45: Transcripts are checked for a recording before they're planned, and again right before the move in. */
    @Test fun transcriptsCheckForARecordingBeforeThePlanAndTheMove() {
        assertBefore("if (transcriptsWait()) out[BackupCategory.TRANSCRIPTS]", "RestoreMerge.transcripts(haveTranscripts()")
        assertBefore("if (transcriptsWait()) CategoryOutcome.Skipped(PlanSkip.RECORDING) else files(j, BackupCategory.TRANSCRIPTS", "step(BackupCategory.KEYS)")
    }

    @Test fun aCopiedPassphraseOutlivesItsScreen() {
        // F48: not the screen's timer, and the next focus clears what a process death left.
        val backup = code("assistant/backup/BackupActivity.kt")
        assertTrue(backup.contains("BackupClipboard.copy(this"))
        assertTrue(backup.contains("BackupClipboard.ended("))
        assertTrue(backup.contains("BackupClipboard.check(this)"))
        assertTrue(!backup.contains("postDelayed({ clearClipboard"))
        assertTrue(code("ui/MainActivity.kt").contains("BackupClipboard.check(this)"))
        val clip = code("sys/BackupClipboard.kt")
        assertBefore("cm.setPrimaryClip(clip)", "ClipMark.make(text", clip)
    }

    @Test fun aFinishTheStoreRefusedEndsTheCategoryWithoutARepeat() {
        // F46: the run's Failed line and the journal settled together; Keys count what didn't save.
        assertBefore("if (t is CantFinish) try { j.giveUp(c) }", "CategoryOutcome.Failed")
        assertTrue(writer.contains("RestoreFinish.handsfree(HandsFreeFiles.store(ctx), staging)"))
        assertTrue(writer.contains("RestoreFinish.routines(RoutineFiles.store(ctx), staging)"))
        assertTrue(writer.contains("RestoreFinish.skills(SkillFiles.store(ctx), staging)"))
        assertTrue(writer.contains("CategoryOutcome.keys(missed, keyBytes.size"))
    }

    /** R1, R12: the marker is written before anything else, each ended category noted, the result ends it, recovery at :voice's start settles it. */
    @Test fun aCutRestoreIsTold() {
        assertBefore("told.begin(sofar())", "val peopleStore = PeopleFiles.store(ctx)")
        assertTrue(writer.contains("RestoreFiles.progress(ctx).end(r)"))
        assertTrue(writer.contains(".settle(lines,"))
        assertTrue(!writer.contains("writeAtomic(RestoreFiles.resultFile(ctx)"))
        // One note per step and one for Memory, outside the catch's value so a thrown category is noted too.
        assertTrue(writer.split("told.note(sofar())").size == 3)
        val settings = code("ui/settings/BackupSettings.kt")
        assertTrue(settings.contains("RestoreFiles.result(ctx)"))
        assertTrue(settings.contains("backup_restore_stopped"))
        assertTrue(code("sys/RestoreApply.kt").contains("progress(ctx).dismiss()"))
    }

    /** R2: both key steps count what had no place. */
    @Test fun keysSayWhatHadNoPlace() {
        assertTrue(writer.contains("CategoryOutcome.keys(missed, keyBytes.size, keys!!.noPlace, alsoCame = codes.isNotEmpty())"))
        assertTrue(writer.contains("CategoryOutcome.keys(missed, connKeyBytes.size, connKeys!!.noPlace)"))
    }

    /** R3: the main app's refusals go through CantFinish into applied.json. */
    @Test fun theMainAppsRefusalReachesTheResult() {
        val apply = code("sys/RestoreApply.kt")
        assertTrue(apply.contains(".applied(j.recover(RestoreProcess.MAIN"))
        assertTrue(apply.contains(".applied(waiting.map { Recovered(it.category, it.state) })"))
        assertTrue(apply.contains("LockStore.editLocks(ctx) { f } ?: throw CantFinish"))
        assertTrue(apply.contains("if (!EverydaySettingsStore.restore("))
        assertTrue(apply.contains("if (!app.heyrobot.sys.LinkSettingsStore.restore(ctx, it)) throw CantFinish"))
        assertTrue(apply.contains("pendingOver("))
    }

    /** R4: Done and the result both carry what the backup itself lacks. */
    @Test fun whatsLeftOutOfABackupIsSaid() {
        val sources = code("assistant/backup/BackupSources.kt")
        assertTrue(sources.contains("FilesBackup.exported(index, synchronized(whole) { LinkedHashMap(whole) }, left())"))
        assertTrue(code("assistant/backup/BackupJob.kt").contains("gathered.filesLeftOut(), gathered.codesLeftOut"))
        val window = code("assistant/backup/BackupActivity.kt")
        assertTrue(window.contains("backup_files_left_out") && window.contains("backup_codes_left_out"))
        assertTrue(code("ui/settings/BackupSettings.kt").contains("backup_restore_files_left_out"))
        assertTrue(writer.contains("FilesBackup.RETHUMB - FilesBackup.LEFT_OUT"))
    }

    /** R10: a cut restore's copy goes at :voice's start, never under an open session. R11: a window made again joins the run. */
    @Test fun theCopyGoesAndAWindowMadeAgainJoins() {
        assertTrue(writer.contains("if (sessions.get() == 0 && !running) RestoreFiles.incoming(ctx).delete()"))
        val flow = code("assistant/backup/RestoreFlow.kt")
        assertTrue(flow.contains("mutableStateOf(if (RestoreSession.running) RStep.WORKING else RStep.PICK)"))
        assertBefore("step == RStep.WORKING -> {}", "else -> picker.launch", flow)
        assertBefore("RestoreSession.willRun()", "\"robot-restore\"", flow)
        assertTrue(code("assistant/backup/BackupActivity.kt").contains("internal var restoring = RestoreSession.running"))
    }

    /** R14: notes from someone else's backup never write over yours in Add. */
    @Test fun notesFromSomeoneElseKeepYours() {
        assertTrue(writer.contains("mode(BackupCategory.NOTES), replaceYours = mine)"))
    }

    /** R7, the part FB5's order doesn't reach: waiting pairings go first; a key that stays says so. */
    @Test fun startOverCutOrRefusedIsNotSaidToHaveWorked() {
        val runtime = code("link/LinkRuntime.kt")
        val body = runtime.substring(runtime.indexOf("fun startOver(ctx: Context)"))
        assertBefore("LinkBackupCodec.PENDING).delete()", "v.startOver()", body)
        assertTrue(code("link/LinkVault.kt").contains("return link && inboxKey"))
        assertTrue(code("link/LinkService.kt").contains("out.writeInt(if (gone) 1 else 0)"))
    }
}
