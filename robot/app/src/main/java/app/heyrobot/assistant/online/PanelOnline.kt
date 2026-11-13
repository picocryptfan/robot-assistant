// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.online

import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.online.OnlineAsk
import app.heyrobot.core.online.OnlineCommand
import app.heyrobot.core.online.OnlineRouter
import app.heyrobot.core.online.OnlineSummary
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.sys.NetLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * VOICE PROCESS. The panel's What went online slot (docs/plans/everyday-set-plan.md §5.6, D1): after sums and before
 * hands-free (PanelController.send). "What went online today?" is answered by code from the record (core OnlineSummary),
 * on screen and aloud; locked, only the unlock line, and the record isn't read at all. The voice log keeps that it was
 * asked and whether the phone was locked, never what the answer named.
 */
object PanelOnline {
    fun route(q: String): OnlineCommand? = app.heyrobot.assistant.later.PanelLater.route(q) ?: OnlineRouter.route(q)

    suspend fun handle(replyId: String, cmd: OnlineCommand, host: PanelRoutines.Host) {
        try {
            // Signal later (signal-later-plan.md): its phrases share this slot.
            if (cmd is app.heyrobot.core.online.LaterCommand) return app.heyrobot.assistant.later.PanelLater.handle(replyId, cmd, host)
            val ask = cmd as? OnlineAsk ?: return
            val locked = host.locked
            VoiceLog.add(VoiceEvent.ONLINE_ASKED, if (locked) 1 else 0)
            val app = VoiceHub.app
            val (on, rows) = withContext(Dispatchers.IO) {
                val s = EverydaySettingsStore.current(app)
                s.online to if (locked || !s.online) emptyList() else NetLog.read(app)
            }
            val p = OnlineSummary.spoken(rows, ask.day, LocalDate.now(), recording = on, locked = locked)
            val l = host.langs(null, ask.lang)
            host.text(replyId, p.render(l))
            host.say(p.render(l.voice))
        } finally {
            host.finish(replyId)
        }
    }
}
