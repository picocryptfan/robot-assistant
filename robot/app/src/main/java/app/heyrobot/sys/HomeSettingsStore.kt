// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.RLog
import app.heyrobot.core.writeAtomic
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// Main process. Today's settings (home-design §3): files/robot/home_settings.json, never settings.json (schema 13 stays).
// Written by the main app's screens and, for "put my calendar first" (H4), by :voice: every write is a read, change and
// write under a file lock, so neither process writes over the other's change. H5: the backup's HOME category, whose restore
// writes it from :voice through the same lock.

object HomeSettingsStore {
    fun file(ctx: Context): File = File(Paths.dir(ctx), HomeSettings.FILE)

    fun current(ctx: Context): HomeSettings = try {
        val f = file(ctx)
        HomeSettings.fromJson(if (f.isFile) f.readText() else null)
    } catch (t: Throwable) {
        RLog.w("home_settings_read", t)
        HomeSettings()
    }

    /** today-fix-d: changes with each write by either process (a new file renamed over the old one). */
    fun stamp(ctx: Context): Long = file(ctx).let { if (it.isFile) it.lastModified() * 31 + it.length() else 0L }

    private const val LOCK = "home_settings.lock" // l10n: not text (a file name)

    /**
     * The file as [change] leaves it, read and written under the lock; null when it couldn't be written. today-fix-c 12:
     * with the weather line or Today off, the stored forecast (its town and coordinates) is deleted. today-h7-fix: and the
     * news follows (NewsStore.settled: a removed feed's headlines go, Today off stops the daily job).
     */
    fun update(ctx: Context, change: (HomeSettings) -> HomeSettings): HomeSettings? =
        write(ctx, change)?.also { if (!it.today || !it.weather) TodayWeatherStore.forget(ctx); NewsStore.settled(ctx, it) }

    @Synchronized
    private fun write(ctx: Context, change: (HomeSettings) -> HomeSettings): HomeSettings? = try {
        val f = file(ctx).apply { parentFile?.mkdirs() }
        FileChannel.open(File(f.parentFile, LOCK).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use { change(current(ctx)).also { writeAtomic(f, it.toJson()) } }
        }
    } catch (t: Throwable) {
        RLog.w("home_settings_write", t)
        null
    }
}
