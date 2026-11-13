// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.GateFacts
import app.heyrobot.core.pause.NeverPick
import app.heyrobot.core.pause.PauseGate
import app.heyrobot.core.pause.PauseSettings
import app.heyrobot.sys.Paths
import java.io.File

// App pause (docs/plans/app-pause-plan.md). Its file, files/robot/app_pause.json, is written by Settings › App pause (main)
// and read by :lock's accessibility service, which shows the pause cover ([PauseCoverActivity]) when a picked app comes
// to the front from another app (core PauseGate). Only the package in front is used, as Phone lock's gate uses it.
object AppPauseWatch {
    private const val FILE = "app_pause.json"
    const val COVER = "app.heyrobot.lock.PauseCoverActivity"
    const val EXTRA_APP = "app"

    /** :lock's main thread only. */
    private val gate = PauseGate()
    @Volatile private var cached: Pair<Long, PauseSettings>? = null
    @Volatile private var homes: Pair<Long, Set<String>>? = null

    private fun file(ctx: Context) = File(Paths.dir(ctx), FILE)

    /** The settings as saved; anything unreadable is off. */
    fun settings(ctx: Context): PauseSettings {
        val f = file(ctx)
        val m = f.lastModified()
        cached?.takeIf { it.first == m && m != 0L }?.let { return it.second }
        val s = PauseSettings.decode(try { if (f.isFile) f.readText() else null } catch (t: Throwable) { RLog.w("pause_read", t); null })
        cached = m to s
        return s
    }

    /** [MAIN or :lock] Saves [s] (whole, through a temp file) and tells :lock, which turns window events on or off. */
    fun save(ctx: Context, s: PauseSettings): Boolean = try {
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(s.encode())
        if (!tmp.renameTo(f)) error("rename")
        cached = null
        LockSignals.locksChanged(ctx)
        true
    } catch (t: Throwable) {
        RLog.w("pause_save", t)
        false
    }

    /** Window events are wanted for the pause: on, with an app picked (the gate checks the never-pick list again). */
    fun wants(ctx: Context): Boolean = settings(ctx).let { it.on && it.apps.isNotEmpty() }

    /** Every app that answers Home (the <queries> entry for it), read at most once a minute. */
    fun homes(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        homes?.takeIf { now - it.first < 60_000 }?.let { return it.second }
        val h = try {
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }.toSet()
        } catch (t: Throwable) { RLog.w("pause_homes", t); emptySet() }
        homes = now to h
        return h
    }

    fun never(ctx: Context, f: GateFacts) = NeverPick.of(f, homes(ctx))

    /** :lock's main thread: [pkg] came to the front at [at] (uptime ms); [busy]: a lock, a call, the lock screen, car mode. */
    fun onFront(ctx: Context, pkg: String?, at: Long, busy: Boolean, f: GateFacts) {
        val s = settings(ctx)
        if (!s.on) return
        if (gate.onFront(pkg, at, s, never(ctx, f), busy)) show(ctx, pkg ?: return)
    }

    /** :lock's main thread: Open on the cover. */
    fun grant(pkg: String) = gate.grant(pkg)

    /** :lock's main thread: the screen went off. */
    fun screenOff() = gate.screenOff()

    private fun show(ctx: Context, pkg: String) {
        try {
            // As the lock's cover: no animation, and the app under it isn't told you left it (no picture-in-picture).
            ctx.startActivity(Intent().setClassName(ctx.packageName, COVER).putExtra(EXTRA_APP, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        } catch (t: Throwable) {
            RLog.w("pause_cover", t)
        }
    }
}
