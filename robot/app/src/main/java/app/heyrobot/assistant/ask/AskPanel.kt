// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import android.os.Bundle
import android.os.SystemClock
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.ask.AskRefusal

// VOICE PROCESS (the panel). Ask Robot's hand-over (docs/plans/everyday-set-plan.md §7.2, §7.6; E7): a show from
// PanelLaunchActivity with RobotVoiceService.ARG_SHARED (kept only on the assistant service's own show, core ShowTrust)
// takes what AskInbox holds under that id, once. Never over the lock screen and never during a phone lock: what was
// shared goes then, unread by the panel.

object AskPanel {
    /** What the show [source] with [args] brought from another app, or null; [locked]: the panel opens on the lock screen. */
    fun take(source: String?, args: Bundle?, locked: Boolean, phoneLock: () -> Boolean = { app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app) },
        now: Long = SystemClock.elapsedRealtime()): SharedItem? {
        if (source != RobotVoiceService.SRC_SHARE) return null
        val item = AskInbox.take(args?.getString(RobotVoiceService.ARG_SHARED), now) ?: return null
        val why = when {
            phoneLock() -> AskRefusal.PHONE_LOCK
            locked -> AskRefusal.LOCKED
            else -> return item
        }
        VoiceLog.add(VoiceEvent.ASK_REFUSED, why.id, item.kind.id)
        return null
    }
}
