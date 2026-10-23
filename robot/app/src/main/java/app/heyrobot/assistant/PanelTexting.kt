// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.DoneReason
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TextCommand
import app.heyrobot.core.TextRequest
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnResult
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.PeopleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

// VOICE PROCESS. Texting without the model in the panel (core TextCommand): "text mom I'm late" becomes send_text,
// prepared, gated and carded as the model's call would be; "text mom" asks what it should say and takes your next words
// as the message. Who may be texted, and when, is the approval gate's and the send_text tool's; this is the panel's side:
// the lock first, then the names Robot may match, then the question or the call. The turn runner (the panel's own call,
// its card and the quarantine), the name Robot waits on and the listen after speaking (PanelController's fields, read by
// its show, hide and voice), the voice, the microphone, the reply's end and the lock stay in PanelController, through
// [PanelTexting.Port]. Its caller runs on the main thread, as before.

/** Texting in your own words, over PanelController's [state]; [port]: what it needs of PanelController. */
internal class PanelTexting(
    private val state: MutableStateFlow<PanelState>,
    private val replies: PanelReplies,
    private val port: Port,
) {
    /** What texting needs of PanelController. */
    interface Port {
        /** The voice process's context (VoiceHub.app). */
        val ctx: Context
        /** The settings as the turns use them now (VoiceHub.settings: the wake phrases, the lock screen's tools). */
        val settings: RobotSettings
        /** The turn's languages, read live. */
        val turnLangs: Langs
        /** The phone is locked now (Assistant.locked), read at each call. */
        fun locked(): Boolean
        /** The panel is on screen now. */
        val shown: Boolean
        /** Robot asked what a text to this name should say: the next question answers it (PanelController's own field). */
        var askingTextFor: String?
        /** Listen once Robot has stopped talking (PanelController's own field, shared with the skills' questions). */
        var listenAfterSpeech: Boolean
        /** What Robot says of [p] in the turn's reply language. */
        fun said(p: Phrase): String
        /** One sentence aloud in Robot's voice, through the panel's guard for a headset or car. */
        fun speakSentence(s: String)
        /** Listens for your next words now. */
        fun startListening()
        /** Robot's own words as the reply, in its bubble and voice ([spoken]: as the voice says it), and the reply ends. */
        fun localReply(replyId: String, text: Phrase, spoken: Phrase)
        /** send_text's [call], your own words', as a turn on reply [replyId]: prepared, gated and carded as the model's would be. */
        suspend fun textTurn(replyId: String, call: RawToolCall, untrusted: Boolean): TurnResult
        fun finishReply(replyId: String, reason: DoneReason)
        fun failReply(replyId: String, message: String)
        /** The turn opened an app: the panel goes. */
        suspend fun leaveForApp()
    }

    private val turnLangs: Langs get() = port.turnLangs

    private fun localReply(replyId: String, text: Phrase, spoken: Phrase = text) = port.localReply(replyId, text, spoken)

    /**
     * Texting without the model (core TextCommand): "text mom I'm late" becomes send_text, prepared, gated and carded as
     * the model's call would be; "text mom" asks what it should say and takes the next question as the message
     * ("cancel" drops it). False: not a text, the model answers.
     */
    suspend fun direct(replyId: String, q: String, textingFor: String?, fromVoice: Boolean, untrusted: Boolean): Boolean {
        val typed = !fromVoice
        // The wake word is paused while Robot waits for the message, so a spoken "hey robot, …" comes back whole.
        val wakes = port.settings.wakePhrases.filter { it.enabled }.map { it.text }
        val answer = if (fromVoice && textingFor != null) TextCommand.stripWake(q, wakes) else q
        // Decided before any contact is read or anything asked: the lock first.
        val locked = port.locked() && ToolName.SEND_TEXT !in port.settings.lockedTools
        val req: TextRequest = when (TextCommand.route(answer, textingFor, locked, typed, wakes)) {
            TextCommand.Route.MODEL -> return false
            // Said aloud as well: the question may have come by voice from across the room.
            TextCommand.Route.UNLOCK -> {
                localReply(replyId, PanelStrings.unlockToText())
                return true
            }
            TextCommand.Route.CANCEL -> {
                val kind = TextCommand.cancelKind(answer, wakes) ?: TextCommand.Cancel.EXPLICIT
                localReply(replyId, TextCommand.cancelReplyText(textingFor!!, answer, kind, wakes))
                return true
            }
            // The message itself; the approval gate still checks the lock. Only the wake phrase: Robot asks again.
            TextCommand.Route.ANSWER -> TextRequest(textingFor!!, answer.trim().ifEmpty { null })
            TextCommand.Route.PARSE -> {
                // People's names and what you call them, with the phone's contacts when Robot may read them. Not names learned
                // from notifications: whoever sent the message chose those, so they never decide where the message starts.
                val names = withContext(Dispatchers.IO) {
                    PeopleFiles.book(port.ctx).names.filter { !it.learned }.flatMap { listOf(it.name) + it.nicknames + it.aliases }
                }
                TextCommand.parse(answer, names, typed) ?: return false
            }
        }
        val name = TextCommand.display(req.name)
        if (req.message == null) {
            val ask = TextCommand.askMessageText(req.name)
            replies.setText(replyId, ask.render(turnLangs))
            port.askingTextFor = req.name
            if (state.value.speak) {
                port.speakSentence(port.said(ask))
                port.listenAfterSpeech = fromVoice
            }
            port.finishReply(replyId, DoneReason.END)
            // Asked by voice with replies silent: listen for the answer now.
            if (fromVoice && !state.value.speak && port.shown) port.startListening()
            return true
        }
        RLog.i("text_direct", "typed" to typed)
        // A short line for the conversation, so later questions know what happened (the card has the rest).
        replies.setText(replyId, PanelStrings.texting("name" to name).render(turnLangs))
        val call = RawToolCall(ToolName.SEND_TEXT.id, mapOf("to" to req.name, "message" to req.message))
        // Your own words asked for this text (or answered "what should it say?").
        val result = port.textTurn(replyId, call, untrusted)
        result.failure?.let { port.failReply(replyId, it); return true }
        port.finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) port.leaveForApp()
        return true
    }
}
