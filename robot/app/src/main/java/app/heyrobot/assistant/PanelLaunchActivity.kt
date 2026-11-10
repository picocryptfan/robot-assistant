// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.Activity
import android.os.Bundle
import app.heyrobot.sys.RoutineIntents

/**
 * VOICE PROCESS, not exported. 1.0 audit (ipc-1): Robot's own app opens the panel for a routine's Run now, a Ready card or
 * a conversation's Continue through here, by class name: this asks the assistant service itself to show the panel, so the
 * show carries the service's token (core ShowTrust) and never SHOW_SOURCE_APPLICATION. Another app can't start this
 * window, and its own showAssist arguments are dropped. The everyday set (everyday-set-plan.md §7.2, E7): Ask Robot hands the
 * panel what another app shared through here too, as a one-time id ([RobotVoiceService.ARG_SHARED], never the content),
 * shown as SRC_SHARE.
 */
class PanelLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val extras = Bundle()
            for (k in KEYS) intent?.getStringExtra(k)?.let { if (k != RobotVoiceService.ARG_SHARED || sharedId(it)) extras.putString(k, it) }
            VoiceHub.init(this)
            val source = if (extras.containsKey(RobotVoiceService.ARG_SHARED)) RobotVoiceService.SRC_SHARE else RobotVoiceService.SRC_APP
            val shown = RobotVoiceService.instance?.show(source, extras) == true
            if (!shown) app.heyrobot.ui.common.RobotToast.show(this, getString(app.heyrobot.R.string.panel_couldnt_open_assistant))
        }
        finish()
    }

    companion object {
        /** The only extras taken (strings): which routine to run, which Ready card, which conversation to continue. */
        val KEYS = listOf(RoutineIntents.ARG_ROUTINE, RoutineIntents.ARG_READY, RobotVoiceService.ARG_CONVERSATION, RobotVoiceService.ARG_SHARED)

        /** The everyday set's shared id: letters, digits and "-", 8 to 40 of them; anything else is dropped. */
        private val SHARED_ID = Regex("[A-Za-z0-9-]{8,40}")

        fun sharedId(v: String): Boolean = SHARED_ID.matches(v)
    }
}
