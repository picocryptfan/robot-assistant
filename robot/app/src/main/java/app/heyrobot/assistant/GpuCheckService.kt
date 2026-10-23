// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * VOICE PROCESS. 1.2 (docs/plans/every-android-phone-1.2-design.md, Step 2): the setup guide's way to Robot's self-test of
 * the GPU (or a beta TPU build). The guide's phone step comes before Robot is your assistant, so the voice process may not
 * run yet and its command receiver isn't there: the main app binds this service by class name (sys GpuCheckLink) while it
 * waits for the check. The binding starts the voice process if it has to, and keeps it, and :brain under it, at the guide's
 * priority while the check runs (a background process is throttled, which would fail a healthy GPU on speed). Binding asks
 * BrainKeeper for the check, which runs only when one is due; the last unbind stops one that hasn't ended, with no verdict.
 * Nothing crosses the binding: the result is the check's record (core CheckLog), which the guide reads. Not exported, no
 * intent filter, Robot's signature permission.
 */
class GpuCheckService : Service() {
    private val binder = Binder()

    override fun onCreate() {
        super.onCreate()
        VoiceHub.init(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        BrainKeeper.guideCheck(this)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        BrainKeeper.guideLeft()
        return false
    }
}
