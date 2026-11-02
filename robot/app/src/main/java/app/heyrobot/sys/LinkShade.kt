// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.NotificationManager
import android.content.Context
import app.heyrobot.core.RLog
import java.io.File

/**
 * F116: the two Robot to Robot notifications that hold someone's words, and taking them off the shade as a phone lock starts.
 * Here, not in LinkNotices, because :lock calls it and :lock has no business loading the assistant side. Any of Robot's
 * processes may cancel them: a notification belongs to the package. The next pass after the lock posts what's still waiting:
 * the cards are still waiting by themselves, and an answer is already marked said, so an empty file says one is owed (no words in it).
 */
object LinkShade {
    const val CARDS_ID = 8_780
    const val REPLIED_ID = 8_795

    fun hide(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // Only one that's still up: an answer you've swiped away or opened stays gone.
            try { if (nm.activeNotifications.any { it.id == REPLIED_ID }) oweReply(ctx) } catch (t: Throwable) { RLog.w("link_shade_owe", t) }
            nm.cancel(CARDS_ID)
            nm.cancel(REPLIED_ID)
        } catch (_: Throwable) {}
    }

    private fun owedFile(ctx: Context) = File(ctx.noBackupFilesDir, "link_reply_owed")

    fun oweReply(ctx: Context) { try { owedFile(ctx).createNewFile() } catch (t: Throwable) { RLog.w("link_shade_owe", t) } }

    fun replyOwed(ctx: Context): Boolean = try { owedFile(ctx).exists() } catch (_: Throwable) { false }

    fun replyPaid(ctx: Context) { try { owedFile(ctx).delete() } catch (t: Throwable) { RLog.w("link_shade_owe", t) } }
}
