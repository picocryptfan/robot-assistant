// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.assistant.conversation.ConversationService
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConvStop
import app.heyrobot.core.ConversationCommand
import app.heyrobot.core.ConversationLock
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.Transcripts

// VOICE PROCESS. Conversation mode's commands by voice in the panel (0.5.0, core ConversationRouter,
// docs/conversation-mode.md 5.4): start transcribing or the interpreter, switch, stop, pause, resume or summarize. The
// session is ConversationSession's; this is the panel's side: what Robot says, and which transcript screen opens. Robot's
// own reply, the lock, the settings, the host and leaving the panel stay in PanelController, through
// [PanelConversationMode.Port]. Its caller runs on the main thread, as before.

/** Conversation mode's voice commands; [port]: what they need of PanelController. */
internal class PanelConversationMode(private val port: Port) {
    /** What conversation mode's commands need of PanelController. */
    interface Port {
        /** The voice process's context (VoiceHub.app). */
        val ctx: Context
        /** The settings as the turns use them now (VoiceHub.settings). */
        val settings: RobotSettings
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

    private fun unlockFirst(p: Phrase): Phrase = port.unlockFirst(p)

    /**
     * Conversation mode by voice (0.5.0): start transcribing or the interpreter (the session starts from here, while the
     * panel is on screen, then its screen opens and the panel goes), switch the running one between them, stop, pause or
     * resume it, or summarize it (it stops, and the summary screen opens: unlocked only).
     */
    suspend fun run(replyId: String, c: ConversationCommand) {
        val ctx = port.ctx
        when (c) {
            is ConversationCommand.Start -> when {
                ConversationSession.running -> {
                    val now = ConversationSession.state.value.mode
                    if (now != c.mode) ConversationSession.setMode(c.mode)
                    localReply(replyId, when {
                        now == c.mode && c.mode == ConvMode.INTERPRET -> PanelStrings.alreadyTranslating()
                        now == c.mode -> PanelStrings.alreadyTranscribing()
                        c.mode == ConvMode.INTERPRET -> PanelStrings.translatingNow()
                        else -> PanelStrings.backToTranscript()
                    })
                    openConversation(ConversationEntry.OPEN, c.mode)
                }
                !ConversationLock.mayStart(port.locked(), port.settings) ->
                    localReply(replyId, unlockFirst(PanelStrings.unlockToStart("page" to UiStrings.inSettings(UiStrings.conversation))), unlockFirst(PanelStrings.unlockToStartSpoken()))
                else -> {
                    // The first time, the transcript screen asks you to tell people first; after that it starts from here
                    // (the panel is on screen), or from the transcript screen if Android won't let it start here.
                    val consented = !ConsentRecord.needed(Transcripts.consent(ctx), port.settings)
                    val started = consented && ConversationService.start(ctx, ConvEntry.VOICE, mode = c.mode)
                    val what = if (c.mode == ConvMode.INTERPRET) PanelStrings.startedTranslating else PanelStrings.startedTranscribing
                    localReply(replyId, when {
                        started -> what("consent" to ConsentRecord.line)
                        consented -> PanelStrings.openingToStart()
                        else -> PanelStrings.tellPeopleFirst()
                    })
                    // On the lock screen the screen opens as a start, so it may show there (it doesn't start a second one).
                    openConversation(if (started && !port.locked()) ConversationEntry.OPEN else ConversationEntry.START, c.mode)
                }
            }
            ConversationCommand.Stop -> {
                ConversationSession.stop(ConvStop.VOICE)
                localReply(replyId, PanelStrings.stoppedSaved("page" to UiLabel(UiStrings.transcripts)), PanelStrings.stoppedSavedSpoken())
            }
            ConversationCommand.Pause -> {
                ConversationSession.userPause()
                localReply(replyId, PanelStrings.paused("button" to UiLabel(UiStrings.resume)))
            }
            // Resuming needs what starting needs (ConversationSession checks it again).
            ConversationCommand.Resume -> if (!ConversationLock.mayStart(port.locked(), port.settings)) {
                localReply(replyId, unlockFirst(PanelStrings.unlockToStart("page" to UiStrings.inSettings(UiStrings.conversation))), unlockFirst(PanelStrings.unlockToStartSpoken()))
            } else {
                ConversationSession.userResume()
                localReply(replyId, PanelStrings.goingOn())
            }
            ConversationCommand.Summarize -> {
                // A summary needs the phone unlocked (docs/conversation-mode.md 1.7): the session stops, the screen opens.
                val id = ConversationSession.state.value.id
                ConversationSession.stop(ConvStop.VOICE)
                if (port.locked() || id == null) {
                    localReply(replyId, unlockFirst(PanelStrings.stoppedUnlockToSummarize("page" to UiLabel(UiStrings.transcripts))), unlockFirst(PanelStrings.stoppedUnlockToSummarizeSpoken()))
                } else {
                    localReply(replyId, PanelStrings.stoppedSummaryOpening())
                    if (host?.launch(Transcripts.summaryIntent(ctx, id)) == true) port.leaveForApp()
                }
            }
        }
    }

    private enum class ConversationEntry { START, OPEN }

    /** The transcript screen (voice process), then the panel goes so nothing more is said to it. */
    private suspend fun openConversation(how: ConversationEntry, mode: ConvMode = ConvMode.TRANSCRIBE) {
        val ctx = port.ctx
        val i = if (how == ConversationEntry.START) Transcripts.startIntent(ctx, ConvEntry.VOICE.name, mode.name) else Transcripts.openIntent(ctx)
        if (host?.launch(i) == true) port.leaveForApp()
    }
}
