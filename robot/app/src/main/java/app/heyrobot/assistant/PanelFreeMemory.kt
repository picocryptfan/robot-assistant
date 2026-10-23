// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.DoneReason
import app.heyrobot.core.FreeUp
import app.heyrobot.core.MemoryFacts
import app.heyrobot.core.PhoneStatus
import app.heyrobot.core.RecentApp
import app.heyrobot.core.TurnResult
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.PhoneInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// VOICE PROCESS. "Free up memory", "close apps", « libère de la mémoire » (0.7.0, core FreeUp) in the panel: Robot's own
// answer, then free_memory's card to pick from. Robot never stops an app itself. The turn runner (free_memory as the
// panel's own call, its card and the quarantine), Robot's own words, the reply's end and the lock stay in PanelController,
// through [PanelFreeMemory.Port]. Its caller runs on the main thread, as before.

/** "Free up memory"'s turn; [port]: what it needs of PanelController. */
internal class PanelFreeMemory(private val port: Port) {
    /** What "free up memory" needs of PanelController. */
    interface Port {
        /** The voice process's context (VoiceHub.app). */
        val ctx: Context
        /** The phone is locked now (Assistant.locked), read at each call. */
        fun locked(): Boolean
        /** Robot's model as BrainKeeper has it now. */
        val brain: BrainKeeper.State
        /** Robot's own words as the reply, in its bubble and voice ([spoken]: as the voice says it), and the reply ends. */
        fun localReply(replyId: String, text: Phrase, spoken: Phrase)
        /** Robot's own sentence into its empty reply, shown and said in its voice. */
        fun sayInto(replyId: String, text: Phrase)
        /** free_memory as the panel's own call on reply [replyId]: its card, gated as the model's call would be. */
        suspend fun freeMemoryTurn(replyId: String): TurnResult
        fun finishReply(replyId: String, reason: DoneReason)
        fun failReply(replyId: String, message: String)
    }

    private fun localReply(replyId: String, text: Phrase, spoken: Phrase = text) = port.localReply(replyId, text, spoken)

    /**
     * 0.7.0, "free up memory" (1.1: in the turn's reply language; asked in French, French): what Android reports free, what unloading Robot's model gives, how
     * many apps used lately are likely still in memory (Usage access), said; then free_memory's card to pick from, as the
     * model's call would make it (ToolExecutor): unload the model, or open an app's info where you tap Force stop, or turn
     * on Usage access. Robot never stops an app itself. On the lock screen only what is free, nothing about your apps.
     */
    suspend fun run(replyId: String) {
        val ctx = port.ctx
        val locked = port.locked()
        val brain = port.brain
        val loaded = brain.readiness == BrainKeeper.Readiness.READY || brain.readiness == BrainKeeper.Readiness.LOADING
        val modelBytes = brain.model?.memoryBytes?.takeIf { it > 0 } ?: app.heyrobot.core.PhoneStatus.ROBOT_MODEL_BYTES
        val (mem, recent) = withContext(Dispatchers.IO) { PhoneInfo.memory(ctx) to if (locked) emptyList() else PhoneInfo.recentInMemory(ctx) }
        if (locked) return localReply(replyId, Phrase.join(FreeUp.memoryNowText(mem), FreeUp.unlockToFreeText()))
        port.sayInto(replyId, advice(mem, if (loaded) modelBytes else null, recent, System.currentTimeMillis()))
        val result = port.freeMemoryTurn(replyId)
        result.failure?.let { return port.failReply(replyId, it) }
        port.finishReply(replyId, result.reason ?: DoneReason.END)
    }

    companion object {
        /**
         * What Robot says to "free up memory" (core FreeUp.adviceText), except that the apps you used lately are named only on
         * free_memory's card, each on its button: the reply is said aloud, kept in the conversation and may go to a helper or
         * a search, and which apps you used is yours. It says how many there are.
         */
        internal fun advice(mem: MemoryFacts, modelBytes: Long?, recent: List<RecentApp>?, now: Long): Phrase =
            if (recent.isNullOrEmpty()) FreeUp.adviceText(mem, modelBytes, recent, now)
            else Phrase.Join(listOf(
                FreeUp.memoryNowText(mem),
                if (modelBytes == null) InfoStrings.modelNotLoaded() else InfoStrings.unloadingFrees("size" to PhoneStatus.sizeText(modelBytes)),
                InfoStrings.recentAppsOnCard(recent.size),
                InfoStrings.neverStops(),
            ))
    }
}
