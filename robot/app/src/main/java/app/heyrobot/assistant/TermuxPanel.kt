package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Card
import app.heyrobot.core.TermuxScriptAction
import app.heyrobot.core.termux.TermuxCardInfo
import app.heyrobot.core.termux.TermuxCards
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.ui.common.TermuxCardChange
import app.heyrobot.ui.common.TermuxCardView

// VOICE PROCESS. A Termux action's card in the panel (0.9): what TermuxTool
// prepared (the command, Robot's warnings, Gemma's reason) and its live state (running, the outcome), drawn by ui/common
// TermuxCard in the panel's bubbles; and what the card's buttons ask for besides Run and Cancel (an edited command,
// another limit, Open in Termux). Once it ran, the card's summary is History's saved line ("Ran a command in Termux (exit 0)"): the
// command and its output are shown, never saved.

object TermuxPanel {
    /** [ui] with its Termux card when [c] is a Termux action's (unchanged otherwise). */
    fun dress(ui: UiAction, c: Card): UiAction {
        val key = TermuxTool.keyOf(c.action) ?: return ui
        val info = TermuxTool.cardOf(c.action) ?: return ui
        return view(ui.copy(termux = TermuxCardView(key = key, command = info.command)), info, (c.action as? TermuxScriptAction)?.name)
    }

    /** The card drawn again from its live state (TermuxTool.setLive). */
    fun redress(ui: UiAction): UiAction {
        val t = ui.termux ?: return ui
        return ui.copy(termux = t.copy(running = TermuxTool.live(t.key)?.running == true)).let { live(it) }
    }

    private fun view(ui: UiAction, info: TermuxCardInfo, script: String?): UiAction {
        val base = ui.termux!!.copy(
            command = info.command, script = script, chips = info.chips(), red = info.redLine(), why = info.why, readsAs = info.readsAs,
            fromContent = info.fromContent, terminal = info.terminal, tapOnly = info.tapOnly, where = info.workdir, limitSeconds = info.limitSeconds,
            editable = script == null, personal = info.personal,
        )
        return live(ui.copy(termux = base))
    }

    /** The outcome, the running state and the saved line from TermuxTool's live state. */
    private fun live(ui: UiAction): UiAction {
        val t = ui.termux ?: return ui
        val l = TermuxTool.live(t.key)
        val o = l?.outcome
        val edit = l?.edit
        val withEdit = t.copy(running = l?.running == true, limitSeconds = TermuxSettings.limit(edit?.limitSeconds ?: t.limitSeconds),
            edited = edit?.command?.takeIf { it != t.command })
        if (o == null) {
            val stopped = ui.status == ActionStatus.DECLINED && ui.detail == "Stopped."
            return ui.copy(termux = withEdit, summary = if (stopped) TermuxCards.savedLine(t.script, null, stopped = true) else ui.summary)
        }
        return ui.copy(
            summary = if (ui.status == ActionStatus.PENDING) ui.summary else TermuxCards.savedLine(t.script, o.exit),
            termux = withEdit.copy(exitLine = TermuxCards.exitLine(o), output = o.output?.card?.takeIf { it.isNotEmpty() },
                size = o.output?.let { TermuxCards.size(it.length) }, cut = o.output?.truncated == true, masked = o.output?.masked ?: 0),
        )
    }

    /** A Termux card's button besides Run and Cancel. */
    fun change(actionId: String, change: TermuxCardChange) {
        val a = PanelController.cardById(actionId) ?: return
        val t = a.termux ?: return
        when (change) {
            is TermuxCardChange.Run -> {
                val edited = change.command?.trim()?.takeIf { it.isNotEmpty() && it != t.command }
                if (edited != null || (change.limitSeconds != null && change.limitSeconds != t.limitSeconds)) {
                    TermuxTool.edit(t.key, TermuxTool.Edit(command = edited, limitSeconds = change.limitSeconds))
                }
                PanelController.confirm(actionId, true)
            }
            // Editing: a spoken or typed yes would run what the card no longer shows, so only a tap on Run answers it now.
            TermuxCardChange.Editing -> PanelController.updateCard(actionId) {
                it.copy(tapFor = AskWhy.COMMAND, detail = "You're editing the command: tap Run when it's ready.", termux = t.copy(editing = true))
            }
            TermuxCardChange.OpenInTermux -> {
                TermuxTool.edit(t.key, TermuxTool.Edit(terminal = true))
                PanelController.confirm(actionId, true)
            }
            TermuxCardChange.Stop -> PanelController.stopGenerating()
        }
    }
}
