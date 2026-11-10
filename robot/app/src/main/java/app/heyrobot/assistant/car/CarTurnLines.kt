// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import app.heyrobot.core.ActionStatus
import app.heyrobot.assistant.PanelState
import app.heyrobot.assistant.UiAction
import app.heyrobot.assistant.Phase
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.car.CarAskUi
import app.heyrobot.ui.car.CarTurnUi
import app.heyrobot.ui.common.ReplyCardState
import app.heyrobot.ui.common.ReplyCardView

// Pure. A turn with no panel, as the car screen's information panel shows it (docs/pm/handoff-car-mode-drive-fixes.md, item 3):
// what Robot hears, then its reply, or the card waiting on a yes. Shown only; the whole answer is in History.

/** What the panel shows now, and what its Yes and Cancel answer: the card [actionId] or the reply in the turn [replyId]. */
data class CarTurnNow(val ui: CarTurnUi, val actionId: String? = null, val replyId: String? = null, val tapOnly: Boolean = false)

object CarTurnLines {
    /** About what two lines of the panel hold on a phone; more would be cut off under a Yes. EST. */
    private const val ASK_MAX = 60
    /** A button's own word that fits the panel's Yes. EST. */
    private const val WORD_MAX = 12

    private fun fits(text: String) = text.length <= ASK_MAX && text.lines().size <= 2

    /** A button's own word, when it fits the panel's button; else the car screen's own (Yes or Approve, Cancel). */
    private fun word(w: String?) = w?.takeIf { it.length <= WORD_MAX }

    /** L16: who it goes to and the words a Yes sends, as sent: the translation when it's ticked. */
    private fun replyAsk(r: ReplyCardView): String {
        val who = r.headline.replace("**", "")
        val words = if (r.translate) r.translation ?: r.text else r.text
        return if (r.state == ReplyCardState.WAITING && words.isNotBlank()) "$who\n$words" else who
    }

    fun of(s: PanelState): CarTurnNow? {
        val turn = s.turns.lastOrNull()
        val action = turn?.actions?.lastOrNull { it.status == ActionStatus.PENDING }
        if (action != null) {
            // fix-car-long-card: the whole card in the car screen's pane (it scrolls), never the panel over it.
            val text = CarCardVoice.text(action)
            val tap = CarCardVoice.tapOnly(action)
            return CarTurnNow(CarTurnUi(ask = CarAskUi(text, canYes = action.choices.isEmpty(), yes = word(action.confirm), no = word(action.cancel),
                whole = fits(text), tapOnly = tap, picture = action.picture)), actionId = action.id, tapOnly = tap)
        }
        val reply = turn?.reply
        if (reply != null && (reply.state == ReplyCardState.WAITING || reply.state == ReplyCardState.PICK)) {
            val text = replyAsk(reply)
            return CarTurnNow(CarTurnUi(ask = CarAskUi(text, canYes = reply.state == ReplyCardState.WAITING, whole = fits(text))), replyId = turn.id)
        }
        val partial = s.partial.takeIf { it.isNotBlank() }
        val answer = s.turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.takeIf { it === turn }?.text?.takeIf { it.isNotBlank() }
        val notice = s.notice?.text?.takeIf { it.isNotBlank() }
        return when (s.phase) {
            Phase.LISTENING -> CarTurnNow(CarTurnUi(listening = true, words = partial))
            Phase.TRANSCRIBING, Phase.THINKING -> CarTurnNow(CarTurnUi(words = partial ?: turn?.takeIf { it.role == TurnRole.USER }?.text ?: answer ?: notice))
            Phase.ANSWERING, Phase.SPEAKING, Phase.CONFIRMING -> (answer ?: notice ?: partial)?.let { CarTurnNow(CarTurnUi(words = it)) }
            Phase.IDLE -> (notice ?: answer)?.let { CarTurnNow(CarTurnUi(words = it)) }
        }
    }

    /** fix-car-stay-c: a reply in its 3 seconds to stop it, or on its way: the turn waits for it to go (or not) and say so. */
    fun sending(s: PanelState): Boolean = s.turns.any { it.reply?.state == ReplyCardState.STOP_WINDOW || it.reply?.state == ReplyCardState.SENDING }

    /** Whether the line is Robot's own and stays a few seconds after the turn. */
    fun kept(n: CarTurnNow?): Boolean = n != null && !n.ui.listening && n.ui.ask == null && n.ui.words != null
}
