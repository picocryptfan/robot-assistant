// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler

/**
 * 1.0 audit (ipc-3): the unlock (USER_PRESENT) comes from SystemUI, which runs under an ordinary app uid, not the system's,
 * so a receiver registered RECEIVER_NOT_EXPORTED never gets it ("Exported Denial"): the unlock trigger, the held brief, car
 * mode's trusted session and the transcript screen's lock watch never saw an unlock. These actions are protected broadcasts:
 * only the system may send them, so exporting the receiver lets nothing else in. Only a filter made of [ACTIONS] goes
 * through here.
 */
object ProtectedBroadcasts {
    /** System-only (protected) broadcasts, as Android's own manifest declares them. */
    val ACTIONS = setOf(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF)

    fun filter(vararg actions: String): IntentFilter {
        require(actions.isNotEmpty() && actions.all { it in ACTIONS }) { "not a protected system broadcast" } // l10n: not text (a developer's error)
        return IntentFilter().apply { actions.forEach(::addAction) }
    }

    fun register(ctx: Context, r: BroadcastReceiver, filter: IntentFilter, handler: Handler? = null) {
        for (i in 0 until filter.countActions()) require(filter.getAction(i) in ACTIONS) { "not a protected system broadcast" } // l10n: not text (a developer's error)
        ctx.registerReceiver(r, filter, null, handler, Context.RECEIVER_EXPORTED)
    }
}
