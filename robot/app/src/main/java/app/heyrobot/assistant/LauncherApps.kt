// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.heyrobot.core.AppEntry

/** The apps on your home screen, as ToolExecutor finds them: for the save cards' boxes (auto-open-plan §1). */
internal object LauncherApps {
    fun of(ctx: Context): List<AppEntry> = try {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL).map { ri ->
            AppEntry(ri.loadLabel(pm).toString(), "${ri.activityInfo.packageName}/${ri.activityInfo.name}")
        }
    } catch (_: RuntimeException) { emptyList() }
}
