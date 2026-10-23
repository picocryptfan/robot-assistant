// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Confirmations
import app.heyrobot.core.DoneReason
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnResult
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.MemoryReplies
import app.heyrobot.core.memory.MemoryRoute
import app.heyrobot.core.messages.MessageQuarantine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

// VOICE PROCESS. Memory's commands in the panel (0.7, core MemoryRouting): "remember that …", "forget that", "what do you
// know about me?", before the model and never a helper's. What is kept, found and forgotten is MemoryPanel's; this is the
// panel's side of it: the quarantine's card after a message, the pick card when several items fit "forget …", the card
// under the reply and Robot's words. The turn runner (the quarantine's card as your own words' call), the confirmations,
// Robot's own reply, the reply's end and the lock stay in PanelController, through [PanelMemoryCommand.Port]; a card
// waiting is answered by voice through PanelCardVoice. Its caller runs on the main thread, as before.

/**
 * A memory command over PanelController's [state] and [chat] (the same list, read live); [cardVoice]: answering the pick
 * card by voice; [port]: what it needs of PanelController.
 */
internal class PanelMemoryCommand(
    private val state: MutableStateFlow<PanelState>,
    private val replies: PanelReplies,
    private val cardVoice: PanelCardVoice,
    private val chat: List<ChatTurn>,
    private val port: Port,
) {
    /** What a memory command needs of PanelController. */
    interface Port {
        /** The turn's languages, read live. */
        val turnLangs: Langs
        /** The phone is locked now (Assistant.locked), read at each call. */
        fun locked(): Boolean
        /** The turn slot's confirmation cards: the pick card waits here for its tap or spoken answer. */
        val confirmations: Confirmations
        fun newId(): String
        /** Robot's own words as the reply, in its bubble and voice ([spoken]: as the voice says it), and the reply ends. */
        fun localReply(replyId: String, text: Phrase, spoken: Phrase)
        /** The same with words already in the turn's language. */
        fun localReply(replyId: String, text: String, spoken: String)
        /** Robot's own words into its empty reply, shown and said in its voice. */
        fun sayInto(replyId: String, text: String)
        /** The quarantine's remember [call] as your own words' turn on reply [replyId]: a card only a tap answers. */
        suspend fun memoryCardTurn(replyId: String, call: RawToolCall): TurnResult
        fun finishReply(replyId: String, reason: DoneReason)
        fun failReply(replyId: String, message: String)
    }

    private val turnLangs: Langs get() = port.turnLangs

    private fun localReply(replyId: String, text: Phrase, spoken: Phrase = text) = port.localReply(replyId, text, spoken)

    private fun localReply(replyId: String, text: String, spoken: String = text) = port.localReply(replyId, text, spoken)

    /**
     * 0.7, a memory command (core MemoryRouting), before the model and never a helper's: the vault changes, the card under
     * the reply (Remembered or Forgotten with Undo, Open Memory), and Robot's own words, said back; several items that fit
     * "forget …" make a pick card first. On the lock screen it only says to unlock, unless memory is allowed there.
     */
    suspend fun run(replyId: String, route: MemoryRoute, spoken: Boolean) {
        // 0.7, memory and messages: your "remember that …" in a conversation that holds a message is never saved at once.
        // It goes through the turn runner as one remember call of your own words: a card that shows exactly the words kept,
        // answered only by a tap, kept as content (core MessageQuarantine.rememberAfterMessages); a code is refused there.
        when (val after = MessageQuarantine.rememberAfterMessages(route, PanelMessages.taint(chat))) {
            null -> {}
            is MessageQuarantine.AfterMessages.Refused -> return localReply(replyId, after.phrase ?: Phrase.plain(after.text))
            is MessageQuarantine.AfterMessages.Card -> {
                replies.setText(replyId, MessageQuarantine.checkAndTapText().render(turnLangs))
                val result = port.memoryCardTurn(replyId, after.call)
                result.failure?.let { return port.failReply(replyId, it) }
                return port.finishReply(replyId, result.reason ?: DoneReason.END)
            }
        }
        var a = withContext(Dispatchers.IO) { MemoryPanel.command(route, port.locked(), spoken, langs = turnLangs) }
        if (a.pick.isNotEmpty()) {
            val id = port.newId()
            replies.upsertAction(replyId, UiAction(id, ToolName.FORGET, MemoryReplies.cardWhichText().render(turnLangs), ActionStatus.PENDING, null,
                choices = MemoryPanel.pickLabels(a.pick)))
            port.sayInto(replyId, a.text)
            state.update { it.copy(phase = Phase.CONFIRMING) }
            cardVoice.waiting()
            val pick = try {
                port.confirmations.ask(id)
            } finally {
                cardVoice.stop()
                state.value.turns.firstOrNull { it.id == replyId }?.actions?.firstOrNull { it.id == id }?.let { replies.upsertAction(replyId, it.copy(status = ActionStatus.DECLINED)) }
            }
            state.update { it.copy(phase = Phase.THINKING) }
            val chosen = a.pick.getOrNull(if (pick >= Confirmations.REMEMBER) pick - Confirmations.REMEMBER else pick)
            state.update { st -> st.copy(turns = st.turns.map { t -> if (t.id != replyId) t else t.copy(text = "", actions = t.actions.filter { it.id != id }) }) }
            a = withContext(Dispatchers.IO) { MemoryPanel.forgetPicked(chosen, a.fr) }
        }
        a.card?.let { replies.upsertAction(replyId, it.copy(id = port.newId())) }
        MemoryPanel.mark(replyId, a.ids)
        localReply(replyId, a.text)
    }
}
