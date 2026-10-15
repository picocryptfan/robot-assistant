// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import app.heyrobot.core.RLog
import app.heyrobot.core.ShowTrust
import app.heyrobot.sys.ShowNonces

// VOICE PROCESS. 1.0 audit (ipc-1, ipc-2): what the panel may read of a show's arguments (core ShowTrust).

object ShowGate {
    /**
     * The show's arguments the panel may act on, as a new Bundle (null for none). Any app on top can open the panel with
     * Activity.showAssist and arguments of its choosing, which come with SHOW_SOURCE_APPLICATION: such a show keeps at most
     * "from Robot's app" and a conversation to continue, and only with Robot's own single-use nonce (MainActivity's Talk to
     * Robot). Every robot_* argument of a show is kept only when RobotVoiceService made it (its token).
     */
    fun args(ctx: Context, args: Bundle?, showFlags: Int): Bundle? {
        if (args == null) return null
        val fromApp = showFlags and VoiceInteractionSession.SHOW_SOURCE_APPLICATION != 0
        val from = ShowTrust.from(fromApp, args.getString(ShowTrust.ARG_VIS), RobotVoiceService.TOKEN, args.getString(ShowTrust.ARG_NONCE)) {
            ShowNonces.consume(ctx, it)
        }
        val keys = try { args.keySet().toSet() } catch (_: Throwable) { emptySet() }
        val keep = ShowTrust.keep(keys, from)
        if (from == ShowTrust.From.OTHER && keys.any { it.startsWith(ShowTrust.PREFIX) }) RLog.i("show_args_dropped", "app" to fromApp)
        val out = Bundle(args)
        for (k in keys) if (k !in keep) out.remove(k)
        ShowTrust.source(from, args.getString(ShowTrust.ARG_SOURCE))?.let { out.putString(ShowTrust.ARG_SOURCE, it) } ?: out.remove(ShowTrust.ARG_SOURCE)
        return out
    }
}
