// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.NotificationManager
import android.content.Context
import app.heyrobot.core.RLog

/**
 * review-lock 10: Robot's own words in the shade, from before a lock for someone else: a routine's results and a skill's next
 * step. VISIBILITY_SECRET helps only on the lock screen, and the shade stays open during a lock, so they go as the lock starts,
 * from :lock so they go even with :voice down, and none is posted during one (RoutineNotices, SkillContinue). Your own lock
 * keeps them: they may be all a routine had to say. What keeps its own place stays there (a routine's cards, in Routines).
 */
object LockShade {
    const val READY = 8_411
    const val SUMMARY = 8_412
    const val PLAY = 8_413
    const val CONTINUE = 8_414
    const val SILENT = 8_416
    const val SKILL_BASE = 8_700
    const val SKILL_SLOTS = 32

    fun hide(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            for (id in listOf(READY, SUMMARY, PLAY, CONTINUE, SILENT)) nm.cancel(id)
            for (slot in 0 until SKILL_SLOTS) nm.cancel(SKILL_BASE + slot)
        } catch (t: Throwable) {
            RLog.w("lock_shade", t)
        }
    }
}
