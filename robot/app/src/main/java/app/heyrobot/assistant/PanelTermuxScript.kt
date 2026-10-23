// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.DoneReason
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnResult
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.termux.ScriptMatch

// VOICE PROCESS. "Run backup" (0.9, TermuxTool.direct) in the panel: your own Termux script named in your own words, as
// termux_script's call without the model; what it printed is said in a sentence. What may run, and when, is TermuxTool's
// and TermuxPolicy's, checked as the call is prepared and again as it runs. The turn runner (the panel's actions, its
// cards and the quarantine), the reply's end and leaving the panel stay in PanelController, through
// [PanelTermuxScript.Port]; what the script printed goes out through PanelStream. Its caller runs on the main thread, as
// before. The Termux cards' fronts (PanelController.cardById, updateCard, termuxChanged) stay in PanelController.

/** Your script named in your own words, on PanelController's [replies] and [stream]; [port]: what it needs of PanelController. */
internal class PanelTermuxScript(private val replies: PanelReplies, private val stream: PanelStream, private val port: Port) {
    /** What a script named in your words needs of PanelController. */
    interface Port {
        /** The turn's languages, read live. */
        val turnLangs: Langs
        /** [model]'s one termux_script call as a turn on reply [replyId]: prepared, gated and carded as Gemma's call would be. */
        suspend fun termuxTurn(replyId: String, model: TurnModel, untrusted: Boolean): TurnResult
        fun finishReply(replyId: String, reason: DoneReason)
        fun failReply(replyId: String, message: String)
        /** The turn opened an app: the panel goes. */
        suspend fun leaveForApp()
    }

    private val turnLangs: Langs get() = port.turnLangs

    /**
     * 0.9: your script named in your own words ("run backup"): termux_script prepared, gated and carded as Gemma's call
     * would be, without the model; what it printed is said in a sentence (two short lines at most).
     */
    suspend fun run(replyId: String, m: ScriptMatch, untrusted: Boolean) {
        replies.setText(replyId, PanelStrings.yourScript("name" to m.name).render(turnLangs))
        val call = RawToolCall(ToolName.TERMUX_SCRIPT.id, mapOf("name" to m.name, "args" to m.args))
        val said = object : TurnModel {
            override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(call))
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
                results.firstOrNull()?.second?.let(TermuxTool::spokenResult)?.let { stream.streamText(replyId, it) }
                return ModelOutcome.Done(DoneReason.END)
            }
            override fun close() {}
        }
        // 0.7's quarantine: after a message, even your own script is a card only a tap answers (core MessageQuarantine).
        val result = port.termuxTurn(replyId, said, untrusted)
        result.failure?.let { return port.failReply(replyId, it) }
        port.finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) port.leaveForApp()
    }
}
