// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.feedback.FeedbackCommand
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.FeedbackFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// VOICE PROCESS. "Report a bug", "I have an idea" (0.7, core FeedbackRouter) in the panel: Settings › About › Send feedback
// opens in the app with its kind, and what you said after the phrase starts your text. The panel never sends anything.
// Robot's own reply, the lock, the host and leaving the panel stay in PanelController, through [PanelFeedback.Port]. Its
// caller runs on the main thread, as before.

/** A feedback phrase's turn; [port]: what it needs of PanelController. */
internal class PanelFeedback(private val port: Port) {
    /** What feedback needs of PanelController. */
    interface Port {
        /** The voice process's context (VoiceHub.app). */
        val ctx: Context
        /** The panel's window, or null when none is attached. */
        val host: PanelHost?
        /** The phone is locked now (Assistant.locked), read at each call. */
        fun locked(): Boolean
        /** [p], or Phone lock's own sentence while Phone lock is on. */
        fun unlockFirst(p: Phrase): Phrase
        /** Robot's own words as the reply, in its bubble and voice ([spoken]: as the voice says it), and the reply ends. */
        fun localReply(replyId: String, text: Phrase, spoken: Phrase)
        /** The turn opened an app: the panel goes. */
        suspend fun leaveForApp()
    }

    private val host: PanelHost? get() = port.host

    private fun localReply(replyId: String, text: Phrase, spoken: Phrase = text) = port.localReply(replyId, text, spoken)

    /**
     * 0.7: a feedback phrase opens Settings › About › Send feedback in the app with its kind, and what you said after it
     * starts your text (handed over in a file of Robot's own). The panel never sends anything: the page opens, you choose.
     */
    suspend fun run(replyId: String, c: FeedbackCommand) {
        val ctx = port.ctx
        val locked = port.locked()
        VoiceLog.add(VoiceEvent.FEEDBACK_ASKED, c.kind, if (c.text != null) 1 else 0, if (locked) 1 else 0)
        if (locked) return localReply(replyId, port.unlockFirst(PanelStrings.feedbackUnlock("page" to UiStrings.inSettings(UiStrings.about, UiStrings.sendFeedback))),
            port.unlockFirst(PanelStrings.feedbackUnlockSpoken()))
        withContext(Dispatchers.IO) { FeedbackFiles.prefill(ctx, c.kind, c.text) }
        localReply(replyId, PanelStrings.openingFeedback("page" to UiLabel(UiStrings.sendFeedback)))
        if (host?.launch(FeedbackFiles.openIntent(ctx, c.kind)) == true) port.leaveForApp()
    }
}
