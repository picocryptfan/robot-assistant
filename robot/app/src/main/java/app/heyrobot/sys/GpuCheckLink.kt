// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import app.heyrobot.core.RLog

/**
 * MAIN PROCESS. 1.2 (docs/plans/every-android-phone-1.2-design.md, Step 2): the setup guide's wait for Robot's self-test of
 * the GPU (or a beta TPU build). It binds the voice process's GpuCheckService by class name (the main app never refers to
 * the assistant's classes), which asks BrainKeeper for the check; it stays bound, so the voice process and :brain keep the
 * app's priority, until the check's record is there, the app goes to the background, or [MAX_MS] passes. Nothing crosses
 * the binding: the result is the check's record (sys Problems.checks), which the guide reads.
 */
class GpuCheckLink(private val ctx: Context) {
    private var conn: ServiceConnection? = null
    private var since = 0L
    /** A wait ran past [MAX_MS] (the voice process didn't run the check): not asked again until the app starts afresh. */
    private var gaveUp = false

    /** Bound now: a check was asked for and isn't over. */
    val bound: Boolean get() = conn != null

    /** Asks for the check (once: a second call while bound does nothing). False when the voice process can't be bound. */
    fun start(): Boolean {
        if (conn != null) return true
        if (gaveUp) return false
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}
            // The voice process stopped: Android binds it again by itself, and the check's record (or its absence) says the rest.
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val ok = try {
            ctx.bindService(Intent().setClassName(ctx.packageName, CLASS), c, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (t: Throwable) { RLog.w("check_bind", t); false }
        if (!ok) {
            try { ctx.unbindService(c) } catch (_: Exception) {}
            return false
        }
        conn = c
        since = SystemClock.elapsedRealtime()
        RLog.i("check_bound")
        return true
    }

    /** Stops waiting: a check still running stops with no verdict, and runs again at the next load. */
    fun stop() {
        val c = conn ?: return
        conn = null
        try { ctx.unbindService(c) } catch (_: Exception) {}
        RLog.i("check_unbound")
    }

    /** Bound longer than any load and three turns take: stops waiting, for good in this app session. */
    fun overdue(): Boolean {
        if (conn == null || SystemClock.elapsedRealtime() - since <= MAX_MS) return false
        gaveUp = true
        RLog.i("check_gave_up")
        return true
    }

    companion object {
        const val CLASS = "app.heyrobot.assistant.GpuCheckService"
        /** A load (up to 5 minutes, BrainKeeper) and three turns of 30 s, with room. */
        const val MAX_MS = 8 * 60_000L
    }
}
