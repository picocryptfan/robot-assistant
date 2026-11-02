// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.backup.RestoreSafety
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.EverydaySettingsCodec
import app.heyrobot.core.everyday.EverydaySettingsFile
import app.heyrobot.core.everyday.EverydaySettingsWrite
import app.heyrobot.core.readOrNull
import app.heyrobot.core.readTextForUpdate
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import java.io.File

/**
 * The everyday set's switches (core EverydaySettings, docs/plans/everyday-set-plan.md §2.4) in
 * files/robot/everyday_settings.json, never in settings.json. The main app writes it (Settings › Actions, Voice and Safety
 * & privacy, and a restore's finish); both app processes read it, again whenever the file changed, and :voice is told by
 * Cmd.EVERYDAY. A file from a newer Robot is never written over.
 */
object EverydaySettingsStore {
    @Volatile private var cached: Pair<Long, EverydaySettingsFile>? = null

    fun file(ctx: Context): File = File(Paths.dir(ctx), EverydaySettingsCodec.FILE)

    private fun stamp(f: File): Long = if (f.isFile) f.lastModified() * 31 + f.length() else 0L

    /** The file as read (the defaults when there is none; a damaged or newer one's defaults apply). */
    fun read(ctx: Context): EverydaySettingsFile {
        val f = file(ctx)
        val s = stamp(f)
        cached?.let { (at, read) -> if (at == s) return read }
        return EverydaySettingsCodec.decode(readOrNull(f)).also { cached = s to it }
    }

    fun current(ctx: Context): EverydaySettings = read(ctx).settings

    /** The lock every write of the file takes, so each reads the file as it is and writes over it in one step. */
    fun lock(ctx: Context): File = File(Paths.dir(ctx), "everyday_settings.lock") // l10n: not text (a file name)

    /**
     * [MAIN] [change] applied to the file as it is now, under [lock] (core EverydaySettingsWrite.update): only the fields it
     * changes are written, the keys this version doesn't know kept. Then :voice is told. False when nothing was written (a
     * newer Robot's file, or a failed write).
     */
    fun update(ctx: Context, change: (EverydaySettings) -> EverydaySettings): Boolean =
        write(ctx) { raw -> EverydaySettingsWrite.update(raw, change) }

    /**
     * [MAIN] A restore's finish (sys RestoreApply): the backup's [staged] file merged over this phone's as it is now, under
     * [lock] (core RestoreSafety.everydayFile). False when nothing was written.
     */
    fun restore(ctx: Context, staged: String?, mine: Boolean): Boolean = write(ctx) { raw -> RestoreSafety.everydayFile(raw, staged, mine) }

    /** The text [next] makes of the file as it is (null: nothing written), written under [lock]; then :voice is told. */
    private fun write(ctx: Context, next: (String?) -> String?): Boolean {
        val app = ctx.applicationContext
        val f = file(app)
        val ok = withFileLock(lock(app)) {
            try {
                val text = next(readTextForUpdate(f)) ?: return@withFileLock false
                writeAtomic(f, text)
                true
            } catch (t: Exception) {
                RLog.w("everyday_settings_save", t)
                false
            } finally {
                cached = null
            }
        }
        if (ok) {
            Cmd.send(app, Cmd.EVERYDAY)
            // E6: Robot's voices for other apps on or off is the engine in Android's list or not.
            VoicesForApps.sync(app, current(app).voices)
            // E7: Ask Robot's menu item and share entry follow their switch, from Settings and from a restore alike.
            AskRobotDoor.apply(app, current(app).askRobot)
        }
        return ok
    }

    /** Read the file again next time (Cmd.EVERYDAY). */
    fun forget() {
        cached = null
    }
}
