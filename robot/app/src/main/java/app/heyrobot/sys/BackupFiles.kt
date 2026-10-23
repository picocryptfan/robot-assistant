// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.content.Intent
import app.heyrobot.core.RLog
import app.heyrobot.core.backup.BackupPrefs
import app.heyrobot.core.backup.BackupState
import app.heyrobot.core.backup.RestoreJournal
import app.heyrobot.core.backup.RestoreResult
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// BOTH PROCESSES. Back up and restore's two small files (docs/plans/backup-1.1-design.md §9.5), one writer each, and the
// backup window's class name. Neither file holds where a backup went, its passphrase or anything of its content.
//
//   backup.json        the main app's (Settings › Back up and restore): the reminder's switch
//   backup_state.json  :voice's (the backup window): the last backup and the switches chosen for the next one

/** The backup window (assistant/backup/BackupActivity, in :voice where keys are decrypted), opened by class name. */
object BackupWindow {
    const val CLASS = "app.heyrobot.assistant.backup.BackupActivity"
    const val EXTRA_MODE = "mode"
    /** Back up now: what goes in, the passphrase, where to save it. */
    const val MODE_BACKUP = "backup"
    /** Restore from a backup: the file, its passphrase, the preview, then the restore and a restart. */
    const val MODE_RESTORE = "restore"

    fun backup(ctx: Context): Intent = Intent().setClassName(ctx.packageName, CLASS).putExtra(EXTRA_MODE, MODE_BACKUP)
    fun restore(ctx: Context): Intent = Intent().setClassName(ctx.packageName, CLASS).putExtra(EXTRA_MODE, MODE_RESTORE)
}

/**
 * A restore's files, in files/robot/restore: the encrypted copy of the picked file (incoming, deleted
 * once the restore ends), the journal and its staging folders (core RestoreJournal), and result.json (core RestoreResult:
 * codes only), written by :voice and closed (deleted) by Settings › Back up and restore.
 */
object RestoreFiles {
    fun dir(ctx: Context): File = File(Paths.dir(ctx), "restore")
    fun incoming(ctx: Context): File = File(dir(ctx), "incoming")
    fun journal(ctx: Context): RestoreJournal = RestoreJournal(dir(ctx))
    fun resultFile(ctx: Context): File = File(dir(ctx), RestoreResult.FILE)
    fun result(ctx: Context): RestoreResult? = RestoreResult.fromJson(readOrNull(resultFile(ctx)))
}

object BackupFiles {
    fun prefsFile(ctx: Context): File = File(Paths.dir(ctx), BackupPrefs.FILE)
    fun stateFile(ctx: Context): File = File(Paths.dir(ctx), BackupState.FILE)

    fun prefs(ctx: Context): BackupPrefs = BackupPrefs.fromJson(readOrNull(prefsFile(ctx)))

    /** [MAIN] backup.json. */
    fun savePrefs(ctx: Context, p: BackupPrefs) {
        try { writeAtomic(prefsFile(ctx), p.toJson()) } catch (t: Throwable) { RLog.w("backup_prefs", t) }
    }

    fun state(ctx: Context): BackupState = BackupState.fromJson(readOrNull(stateFile(ctx)))

    /** [VOICE] backup_state.json. */
    fun saveState(ctx: Context, s: BackupState) {
        try { writeAtomic(stateFile(ctx), s.toJson()) } catch (t: Throwable) { RLog.w("backup_state", t) }
    }
}
