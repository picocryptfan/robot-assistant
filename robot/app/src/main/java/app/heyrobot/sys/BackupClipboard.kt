// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import app.heyrobot.core.RLog
import app.heyrobot.core.backup.ClipMark
import app.heyrobot.core.writeAtomic
import java.io.File

/**
 * F48: Robot's made passphrase on the clipboard, cleared after [CLEAR_MS] if it is still there. The timer isn't the screen's,
 * so closing the screen doesn't stop it, and a mark on disk ([ClipMark], never the passphrase) has the next focus clear it after
 * the process has gone, or while Android gives no clip to a window without the focus.
 */
object BackupClipboard {
    const val CLEAR_MS = 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun file(ctx: Context) = File(Paths.dir(ctx), ClipMark.FILE)

    fun copy(ctx: Context, label: String, text: String) {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        try {
            writeAtomic(file(app), ClipMark.make(text, System.currentTimeMillis() + CLEAR_MS).toJson())
        } catch (t: Throwable) {
            RLog.w("backup_clip", t)
        }
        main.postDelayed({ check(app) }, CLEAR_MS)
    }

    /** The backup has ended: a passphrase copied for it is due now. A typed one is never marked; it is only compared here. */
    fun ended(ctx: Context, text: String) {
        val app = ctx.applicationContext
        try {
            val f = file(app)
            ClipMark.fromJson(if (f.exists()) f.readText() else null)?.let { writeAtomic(f, it.dueNow(System.currentTimeMillis()).toJson()) }
        } catch (t: Throwable) {
            RLog.w("backup_clip", t)
        }
        check(app)
        if (text.isNotEmpty()) clearIfHolds(app, text)
    }

    /** Clears the clipboard if the mark is due and it still holds the passphrase; called on the timer and at each focus. */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        val f = file(app)
        if (!f.exists()) return
        try {
            val mark = ClipMark.fromJson(f.readText())
            if (mark == null) {
                f.delete()
                return
            }
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
            when (ClipMark.decide(mark, clip, System.currentTimeMillis())) {
                ClipMark.Action.WAIT -> {}
                ClipMark.Action.CLEAR -> {
                    cm.clearPrimaryClip()
                    f.delete()
                }
                ClipMark.Action.FORGET -> f.delete()
            }
        } catch (t: Throwable) {
            RLog.w("backup_clip", t)
        }
    }

    private fun clearIfHolds(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0 && clip.getItemAt(0).coerceToText(ctx)?.toString() == text) cm.clearPrimaryClip()
        } catch (t: Throwable) {
            RLog.w("backup_clip", t)
        }
    }
}
