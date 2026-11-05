// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.util.AtomicFile
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotTimer
import app.heyrobot.core.TimerAsk
import java.io.File

/**
 * fix-everyday-e: the timers Robot asked your clock app to set (when, for how long), in their own small file. Android lets
 * an app set a clock's timer but never read it back, so "how much time is left on my timer" is answered from this record
 * (core TimerAsk). Only Robot's own timers are here; nothing is read from the clock app.
 */
object RobotTimers {
    private const val FILE = "robot_timers.json"
    private val lock = Any()

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, FILE))

    /**
     * fix-timers-store: a record that holds nothing still worth knowing (TimerAsk.kept empty: every timer ran out more than
     * KEEP_MS ago) is deleted as it's read, so the last timers don't stay on disk for good.
     */
    fun load(ctx: Context, now: Long = System.currentTimeMillis()): List<RobotTimer> = synchronized(lock) {
        try {
            val f = file(ctx)
            if (!f.baseFile.exists()) return emptyList()
            val all = TimerAsk.decode(String(f.readFully(), Charsets.UTF_8))
            if (TimerAsk.kept(all, now).isEmpty()) { f.delete(); emptyList() } else all
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** A timer of [seconds] set now. */
    fun set(ctx: Context, seconds: Long, now: Long = System.currentTimeMillis()) = update(ctx) { TimerAsk.added(it, now, seconds) }

    /** "Cancel the timer" now: the running ones are marked (whether you cancelled them in the clock, Robot can't see). */
    fun cancelAsked(ctx: Context, now: Long = System.currentTimeMillis()) = update(ctx) { TimerAsk.cancelAsked(it, now) }

    /** With your data: History's Delete all, Memory's Delete all and Start over, a restore's Replace of History. */
    fun clear(ctx: Context) = synchronized(lock) { try { file(ctx).delete() } catch (_: Exception) {} }

    private fun update(ctx: Context, change: (List<RobotTimer>) -> List<RobotTimer>) = synchronized(lock) {
        val f = file(ctx)
        try {
            val next = change(load(ctx))
            if (next.isEmpty()) { f.delete(); return@synchronized }
            val out = f.startWrite()
            try {
                out.write(TimerAsk.encode(next).toByteArray(Charsets.UTF_8))
                f.finishWrite(out)
            } catch (e: Exception) {
                f.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            RLog.w("robot_timers", e)
        }
    }
}
