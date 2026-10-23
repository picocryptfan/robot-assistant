// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.RestoreProcess
import app.heyrobot.core.backup.RestoreSafety
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockFileRead
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import app.heyrobot.lock.LockStore
import java.io.File

// MAIN PROCESS (docs/plans/backup-1.1-design.md §6.1, §7.10, §7.12). The main app's part of a restore, at its start
// (RobotApp): the settings files and Phone lock's locks.json that :voice staged are written here, by their one writer,
// then the journal goes. A restore ends by restarting Robot's two app processes (:voice ends the main process, opens the
// app again and ends itself), so every cache reads the restored files; :lock keeps running and re-reads locks.json
// (LockStore.editLocks tells it). Running it again changes nothing.

object RestoreApply {
    /** The main app's COMMIT categories finished and DONE; a STAGED one (its staging cut short) undone. */
    fun recover(ctx: Context) {
        try {
            val j = RestoreFiles.journal(ctx)
            if (j.read().isEmpty()) return
            j.recover(RestoreProcess.MAIN, { c, staging -> finish(ctx, c, staging) }, {})
        } catch (t: Throwable) {
            RLog.w("restore_apply", t)
        }
    }

    private fun finish(ctx: Context, c: BackupCategory, staging: File) {
        when (c) {
            BackupCategory.SETTINGS -> {
                for (name in BackupCategory.SETTINGS_FILES.keys) {
                    val text = readOrNull(File(staging, name)) ?: continue
                    writeAtomic(File(Paths.dir(ctx), name), text)
                }
                // Your voiceprint came back (:voice saved it): it is enrolled here, with or without Settings' files.
                if (File(staging, BackupCategory.VOICEPRINT_STAGED).isFile) {
                    val f = File(Paths.dir(ctx), "settings.json") // l10n: not text (a file name)
                    val s = RobotSettings.fromJson(readOrNull(f))
                    if (!s.meVoiceprint) writeAtomic(f, s.copy(meVoiceprint = true).toJson().toString())
                }
                // Memory sealed with the key the restored "Use memory on the lock screen" names; a no-op when
                // it already is, and nothing changes if the phone locked meanwhile (Settings › Memory's switch does it again).
                readOrNull(File(staging, MemorySettings.FILE))?.let { text ->
                    try {
                        val load = MemoryFiles.vault(ctx).rekey(MemorySettings.fromJson(text).onLockScreen)
                        if (load !is MemoryLoad.Loaded && load != MemoryLoad.Empty) RLog.i("restore_memory_key", "outcome" to RLog.Code("not_now"))
                    } catch (t: Throwable) {
                        RLog.w("restore_memory_key", t)
                    }
                }
                // The updater's settings, merged here (its process): your fork, test builds and installing without asking
                // keep this phone's safer values unless the backup is yours.
                readOrNull(File(staging, BackupCategory.UPDATES_STAGED))?.let { text ->
                    val f = File(Paths.dir(ctx), BackupCategory.UPDATES_FILE)
                    val mine = File(staging, BackupCategory.MINE_STAGED).isFile
                    writeAtomic(f, RestoreSafety.updates(UpdateSettings.fromJson(readOrNull(f)), UpdateSettings.fromJson(text), mine).value.toJson())
                }
            }
            BackupCategory.PHONE_LOCK -> {
                val f = (LockCodec.decode(readOrNull(File(staging, LockStore.LOCKS)) ?: return) as? LockFileRead.Ok)?.file ?: return
                // Through Phone lock's own writer, which tells :lock to read it again (Cmd.LOCKS, LockSignals.locksChanged).
                LockStore.editLocks(ctx) { f }
            }
            else -> {}
        }
    }

    /** Settings › Back up and restore closed the last restore's result. */
    fun dismiss(ctx: Context) {
        RestoreFiles.resultFile(ctx).delete()
    }
}
