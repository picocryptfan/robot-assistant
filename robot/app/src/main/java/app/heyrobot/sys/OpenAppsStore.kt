// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.OpenAppsFile
import java.io.File

/**
 * auto-open-plan §1: files/robot/open_apps.json, "Open apps you name without a card" (Settings › Actions). The main app writes
 * it; the panel reads it at each open_app. No file: on (the default, new and existing installs). Unreadable: off.
 */
object OpenAppsStore {
    fun file(ctx: Context) = File(Paths.dir(ctx), OpenAppsFile.NAME)

    fun named(ctx: Context): Boolean = OpenAppsFile.read(try { file(ctx).takeIf { it.exists() }?.readText() } catch (_: Exception) { "" }).named

    fun current(ctx: Context): OpenAppsFile = OpenAppsFile(named(ctx))

    /** review-auto-open 6: true once, the first time an app opens with no card (an empty marker file, not backed up); false after or on an error. */
    fun firstOpen(ctx: Context): Boolean = try { File(Paths.dir(ctx), TOLD).createNewFile() } catch (_: Exception) { false }

    private const val TOLD = "open_apps_told" // l10n: not text (a file name)

    /** auto-open-plan §4: a backup's switch, staged by :voice, merged over this phone's (RestoreSafety.openApps). */
    fun restore(ctx: Context, staged: String, mine: Boolean): Boolean = try {
        set(ctx, app.heyrobot.core.backup.RestoreSafety.openApps(current(ctx), OpenAppsFile.read(staged), mine).value.named)
        true
    } catch (_: Exception) { false }

    fun set(ctx: Context, on: Boolean) {
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(OpenAppsFile.write(OpenAppsFile(named = on)))
        if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException(f.name) }
    }
}
