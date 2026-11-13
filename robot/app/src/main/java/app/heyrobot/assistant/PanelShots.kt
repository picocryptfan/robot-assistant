// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.KeyguardManager
import app.heyrobot.assistant.shots.ShotsWork
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ShotStrings
import app.heyrobot.core.shots.ShotAsk
import app.heyrobot.core.shots.ShotIndex
import app.heyrobot.core.shots.ShotStore
import app.heyrobot.sys.ShotsVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

// PANEL. Ask about your screenshots (screenshots-plan.md, "What's new" 4; Privacy): a question that names a screenshot,
// after every command and memory and before Wikipedia and the model alone. Off: false, and the model answers as before.
// Locked: "unlock first", nothing shown or spoken (the index's key needs the phone unlocked: fail closed). Each hit is
// checked against MediaStore first, so a deleted screenshot is never answered from. The answer is kept in History and on
// the bug card only as its placeholder (ShotAsk.forHistory), and never goes to a helper. Logs counts only.
internal class PanelShots(
    private val replies: PanelReplies,
    private val voice: ReplyVoice,
    private val speak: () -> Boolean,
    private val say: (String) -> Unit,
    private val finish: (String, DoneReason) -> Unit,
) {
    /** True when the index answered (or said to unlock): the turn is done. */
    suspend fun run(replyId: String, question: String, langs: Langs, attached: Boolean, helperMode: Boolean, untrusted: Boolean): Boolean {
        if (attached || helperMode || untrusted || !ShotAsk.asks(question)) return false
        val ctx = VoiceHub.app
        val locked = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
        val load = withContext(Dispatchers.IO) {
            if (locked) ShotStore.Load.Locked else try { ShotsVault.store(ctx).load() } catch (t: Throwable) { RLog.w("shots_load", t); ShotStore.Load.Locked }
        }
        if (load is ShotStore.Load.Off) return false
        mark(replyId)
        var hits = 0
        val answer = when (load) {
            is ShotStore.Load.Ready -> {
                val found = ShotIndex.search(load.entries.values, question, 6)
                val still = withContext(Dispatchers.IO) {
                    try { ShotsWork.rows(ctx, found.map { it.id }).map { it.id }.toSet() } catch (t: Throwable) { RLog.w("shots_check", t); emptySet() }
                }
                val use = found.filter { it.id in still }.take(3)
                hits = use.size
                if (use.isEmpty()) ShotAsk.none(load.entries.values, load.unreadable).render(langs) else {
                    val stream = ShotStream(replies, replyId, ctx.getSystemService(KeyguardManager::class.java))
                    val o = ShotAsk.request(question, use, ZoneId.systemDefault(), speak())?.let { req -> BrainOneShots.run(req, OneShotBudget(startWithinMs = null, totalMs = ANSWER_MS)) { soFar -> stream.onText(soFar) } }
                    when (o) {
                        is OneShotResult.Done -> o.text
                        is OneShotResult.TimedOut -> o.partial
                        else -> ""
                    }.trim().ifBlank { ShotStrings.nothing().render(langs) }
                }
            }
            else -> ShotStrings.unlockFirst().render(langs)
        }
        // Locked while it answered (up to two minutes): nothing from the index is shown or spoken.
        val text = if (load is ShotStore.Load.Ready && ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) ShotStrings.unlockFirst().render(langs) else answer
        RLog.i("shots_answer", "hits" to hits, "locked" to (load !is ShotStore.Load.Ready))
        replies.setText(replyId, text)
        voice.feed(replyId, text, speak()).forEach(say)
        finish(replyId, DoneReason.END)
        return true
    }

    companion object {
        private const val ANSWER_MS = 120_000L
        private val answered = HashSet<String>()

        private fun mark(id: String) = synchronized(answered) { answered += id }

        /** [t] as History, the bug card and the live chat keep it: an answer from the index only as its placeholder. */
        fun forHistory(t: ChatTurn, langs: Langs): ChatTurn = if (synchronized(answered) { answered.remove(t.id) }) ShotAsk.forHistory(t, langs) else t
    }
}

/** An answer from the index streaming into reply [replyId]. Locked mid-answer (the panel can show over the lock screen):
 *  what streamed goes, and nothing more shows, even once it unlocks (PanelShotsLockTest). */
internal class ShotStream(private val replies: PanelReplies, private val replyId: String, private val keyguard: KeyguardManager?) {
    private var streamed = 0
    private var hidden = false

    fun onText(soFar: String) {
        if (hidden || keyguard?.isDeviceLocked != false) {
            if (!hidden) replies.setText(replyId, "")
            hidden = true
            return
        }
        if (soFar.length < streamed) { replies.setText(replyId, ""); streamed = 0 }
        if (soFar.length > streamed) { replies.appendText(replyId, soFar.substring(streamed)); streamed = soFar.length }
    }
}
