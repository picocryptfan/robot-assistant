// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.helpers.HelperConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// VOICE PROCESS. The turn surface: what every part of the panel does to a reply bubble (its text, its cards, whose it
// is), over PanelController's state. It keeps nothing of its own, and its callers write on the main thread, as before.

/** The writes and lookups on the panel's replies, by reply id, over [state] (PanelController's). */
internal class PanelReplies(private val state: MutableStateFlow<PanelState>) {
    /** Reply [replyId]'s text, or null when there is no such reply. */
    fun text(replyId: String): String? = state.value.turns.firstOrNull { it.id == replyId }?.text

    fun setText(replyId: String, text: String) = state.update { st ->
        st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(text = text) else it })
    }

    /** Streamed text onto reply [replyId]: the panel is answering, and a helper's status line goes. */
    fun appendText(replyId: String, text: String) = state.update { st ->
        if (st.turns.none { it.id == replyId }) st
        else st.copy(phase = Phase.ANSWERING, helperStatus = null, turns = st.turns.map { if (it.id == replyId) it.copy(text = it.text + text) else it })
    }

    /**
     * The model's text after a tool round starts a new paragraph: true when one was started (the reply has text that
     * doesn't already end with one), so the caller tells the voice too.
     */
    fun paragraphBreak(replyId: String): Boolean {
        val t = text(replyId) ?: return false
        if (t.isBlank() || t.endsWith("\n\n")) return false
        setText(replyId, t.trimEnd() + "\n\n")
        return true
    }

    /** Card [a] on reply [replyId]: replaced in place when it is there already (by id), added at the end otherwise. */
    fun upsertAction(replyId: String, a: UiAction) = state.update { st ->
        st.copy(turns = st.turns.map { t ->
            if (t.id != replyId) t
            else if (t.actions.any { it.id == a.id }) t.copy(actions = t.actions.map { if (it.id == a.id) a else it })
            else t.copy(actions = t.actions + a)
        })
    }

    /** Reply [replyId] is [h]'s (its label and accent), or Robot's again ([h] null). */
    fun markHelper(replyId: String, h: HelperConfig?) = state.update { st ->
        st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(fromHelper = h != null, helper = h?.id, by = h?.name, home = h?.home == true) else it })
    }

    /** The card with id [id], on whichever reply holds it. */
    fun cardById(id: String): UiAction? = state.value.turns.firstNotNullOfOrNull { t -> t.actions.firstOrNull { it.id == id } }

    /** The card waiting for an answer (the newest one still pending), or null. */
    fun pendingCard(): UiAction? = state.value.turns.asReversed().firstNotNullOfOrNull { t -> t.actions.lastOrNull { it.status == ActionStatus.PENDING } }

    /** Card [id] changed in place by [change], wherever it is. */
    fun updateCard(id: String, change: (UiAction) -> UiAction) = updateCards({ it.id == id }, change)

    /** Every card [match] picks, on every reply, changed in place by [change]. */
    fun updateCards(match: (UiAction) -> Boolean, change: (UiAction) -> UiAction) =
        state.update { st -> st.copy(turns = st.turns.map { t -> t.copy(actions = t.actions.map { if (match(it)) change(it) else it }) }) }
}
