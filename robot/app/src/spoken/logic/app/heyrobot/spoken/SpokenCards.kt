// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.assistant.PanelState
import app.heyrobot.assistant.Phase
import app.heyrobot.assistant.UiTurn
import app.heyrobot.core.ActionStatus
import app.heyrobot.ui.common.ReplyCardState

/**
 * The card guard. Which cards wait for an answer now, by kind: a tool's id, or `other` for anything else a spoken yes could
 * answer. It fails closed: what it can't name is `other`, and `other` isn't harmless.
 */
object SpokenCards {
    const val OTHER = "other"

    /** The kinds of what waits now. */
    fun showing(st: PanelState): List<String> {
        val kinds = st.turns.flatMap { waitingIn(it) }.toMutableList()
        // An offer under the last reply: a yes takes it to a helper.
        if (st.turns.lastOrNull()?.offer != null) kinds += OTHER
        // The panel says a card waits and none was found: not one this code knows, so not harmless.
        if (kinds.isEmpty() && st.phase == Phase.CONFIRMING) kinds += OTHER
        return kinds
    }

    /** A turn's own waiting cards: a message's reply card is `other` until it's over, whichever way it ended. */
    private fun waitingIn(t: UiTurn): List<String> {
        val cards = t.actions.filter { it.status == ActionStatus.PENDING }.map { it.tool?.id ?: OTHER }
        val reply = t.reply?.state
        val open = reply != null && reply in setOf(ReplyCardState.PICK, ReplyCardState.WAITING, ReplyCardState.STOP_WINDOW, ReplyCardState.SENDING)
        return if (open) cards + OTHER else cards
    }

    /** Whether [clip] may reach the listener with [kinds] showing; null means they can't be read, which only a no gets past. */
    fun allows(clip: String, kinds: List<String>?): Boolean =
        SpokenProtocol.isNo(clip) || (kinds != null && kinds.all { it in SpokenProtocol.HARMLESS_CARDS })
}
