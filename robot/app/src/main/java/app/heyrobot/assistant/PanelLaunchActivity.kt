package app.heyrobot.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import app.heyrobot.sys.RoutineIntents

/**
 * VOICE PROCESS, not exported. 1.0 audit (ipc-1): Robot's own app opens the panel for a routine's Run now, a Ready card or
 * a conversation's Continue through here, by class name: this asks the assistant service itself to show the panel, so the
 * show carries the service's token (core ShowTrust) and never SHOW_SOURCE_APPLICATION. Another app can't start this
 * window, and its own showAssist arguments are dropped.
 */
class PanelLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val extras = Bundle()
            for (k in KEYS) intent?.getStringExtra(k)?.let { extras.putString(k, it) }
            VoiceHub.init(this)
            val shown = RobotVoiceService.instance?.show(RobotVoiceService.SRC_APP, extras) == true
            if (!shown) Toast.makeText(this, "Couldn't open the assistant.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        /** The only extras taken (strings): which routine to run, which Ready card, which conversation to continue. */
        val KEYS = listOf(RoutineIntents.ARG_ROUTINE, RoutineIntents.ARG_READY, RobotVoiceService.ARG_CONVERSATION)
    }
}
