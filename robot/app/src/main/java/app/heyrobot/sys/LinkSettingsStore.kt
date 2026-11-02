// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.LinkSettingsCodec
import app.heyrobot.core.link.LinkSettingsFile
import app.heyrobot.core.readOrNull
import app.heyrobot.core.readTextForUpdate
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import java.io.File

/**
 * 1.5, Settings › Robot to Robot's values (core LinkSettings, D12) in files/robot/link_settings.json, never in
 * settings.json. The main app writes it (Settings › Robot to Robot, L5); :link and :voice read it, again whenever the file
 * changed (its time and size, as files_settings.json's readers do). A newer Robot's file is never written over, and one
 * that can't be read keeps Robot to Robot off. Nothing of it is logged (it holds the name other Robots see).
 */
object LinkSettingsStore {
    @Volatile private var cached: Pair<Long, LinkSettingsFile>? = null

    fun file(ctx: Context): File = File(Paths.dir(ctx), LinkSettingsCodec.FILE)

    /** Every write takes it, across processes, so each reads the file as it is and writes over it in one step. */
    fun lock(ctx: Context): File = File(Paths.dir(ctx), "link_settings.lock") // l10n: not text (a file name)

    private fun stamp(f: File): Long = if (f.isFile) f.lastModified() * 31 + f.length() else 0L

    /** The file as read (the defaults, off, when there is none). */
    fun read(ctx: Context): LinkSettingsFile {
        val f = file(ctx)
        val s = stamp(f)
        cached?.let { (at, read) -> if (at == s) return read }
        return LinkSettingsCodec.decode(readOrNull(f)).also { cached = s to it }
    }

    fun current(ctx: Context): LinkSettings = read(ctx).settings

    /**
     * [MAIN] [change] applied to the file as it is now, under [lock] (core LinkSettingsCodec.update): only what it changes
     * is written; keys a later Robot added stay. False when nothing was written (a newer Robot's file, or a failed write).
     */
    /** L12: a restore's file merged over this phone's as it is now (RestoreSafety.link); false: nothing written. */
    fun restore(ctx: Context, staged: String?): Boolean = withFileLock(lock(ctx)) {
        try {
            val text = app.heyrobot.core.backup.RestoreSafety.linkFile(readTextForUpdate(file(ctx)), staged) ?: return@withFileLock false
            writeAtomic(file(ctx), text)
            true
        } catch (_: java.io.IOException) { false }
    }

    fun update(ctx: Context, change: (LinkSettings) -> LinkSettings): Boolean {
        val app = ctx.applicationContext
        val f = file(app)
        return withFileLock(lock(app)) {
            try {
                val text = LinkSettingsCodec.update(readTextForUpdate(f), change) ?: return@withFileLock false
                writeAtomic(f, text)
                true
            } catch (t: Exception) {
                RLog.w("link_settings_save", t)
                false
            } finally {
                cached = null
            }
        }
    }

    /** Read the file again next time. */
    fun forget() {
        cached = null
    }
}
