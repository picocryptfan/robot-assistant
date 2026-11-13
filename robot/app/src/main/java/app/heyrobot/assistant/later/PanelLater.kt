// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterCancel
import app.heyrobot.core.online.LaterCant
import app.heyrobot.core.online.LaterCommand
import app.heyrobot.core.online.LaterGate
import app.heyrobot.core.online.LaterItem
import app.heyrobot.core.online.LaterKeep
import app.heyrobot.core.online.LaterList
import app.heyrobot.core.online.LaterRouter
import app.heyrobot.core.online.LaterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// VOICE PROCESS. Signal later in the panel (docs/plans/signal-later-plan.md): "… when we're back in range", "what's
// waiting?" and "cancel the ferry one" in What went online's slot (PanelOnline.route); a direct web search with no
// validated connection kept (PanelWebSearch.direct, [keepOffline]); and on an unlocked show, what came back while you
// were out of range as a turn of its own, or the oldest question back online that needs your OK sent as you asked it,
// so its card comes up (never run silently). Locked, nothing is kept, listed or shown (its key needs the phone unlocked).
object PanelLater {
    /** How many wait, as last read: "cancel the … one" is Later's only while something waits. */
    @Volatile private var waiting = 0

    fun route(q: String): LaterCommand? = LaterRouter.route(q)?.takeIf { it !is LaterCancel || waiting > 0 }

    suspend fun handle(replyId: String, cmd: LaterCommand, host: PanelRoutines.Host) {
        val l = host.langs(null, cmd.lang)
        val p = withContext(Dispatchers.IO) { if (host.locked) lockedLine(cmd) else answer(cmd) }
        host.text(replyId, p.render(l))
        host.say(p.render(l.voice))
    }

    /** Locked: web search being off is said first, as [keepOffline] does; anything else asks for the unlock. Keeps nothing. */
    private fun lockedLine(cmd: LaterCommand): Phrase =
        (cmd as? LaterKeep)?.let { (gate(it.said, it.query) as? LaterGate.Off)?.why } ?: EverydayStrings.laterUnlock()

    private fun answer(cmd: LaterCommand): Phrase {
        val app = VoiceHub.app
        return when (cmd) {
            is LaterCant -> EverydayStrings.laterCant()
            is LaterKeep -> keepNow(cmd.said, cmd.query, cmd.lang, untrusted = false)
            is LaterList -> {
                val items = SignalLater.vault(app).update { Later.expire(it, System.currentTimeMillis()) } ?: return EverydayStrings.laterNotKept()
                val w = Later.waiting(items).sortedBy { it.askedAt }.also { waiting = it.size }
                if (w.isEmpty()) EverydayStrings.laterNone() else Phrase.Join(listOf(EverydayStrings.laterHead("n" to Arg.Count(w.size.toLong()))) + w.map(::line), "\n")
            }
            is LaterCancel -> {
                var gone = emptyList<LaterItem>()
                val items = SignalLater.vault(app).update { all -> gone = Later.matching(all, cmd.words, cmd.all); Later.drop(all, gone.map { it.id }.toSet()) }
                    ?: return EverydayStrings.laterNotKept()
                waiting = Later.waiting(items).size
                LaterNotices.cancel(app, gone)
                if (gone.isEmpty()) EverydayStrings.laterNoMatch() else EverydayStrings.laterCancelled("n" to Arg.Count(gone.size.toLong()))
            }
        }
    }

    private fun line(i: LaterItem): Phrase = (if (i.state == LaterState.READY) EverydayStrings.laterItemReady else EverydayStrings.laterItem)(
        "q" to Arg.Plain(i.said), "t" to LaterNotices.clock(i.askedAt))

    private fun kept(k: SignalLater.Kept): Phrase = when (k) {
        SignalLater.Kept.KEPT -> EverydayStrings.laterKept().also { waiting++ }
        SignalLater.Kept.FULL -> EverydayStrings.laterFull("n" to Arg.Plain(Later.CAP.toString()))
        SignalLater.Kept.FAILED -> EverydayStrings.laterNotKept()
    }

    /** Whether the phone has a validated connection now; a seam for the JVM tests, which have none. */
    internal var onlineNow: () -> Boolean = { SignalLater.validated(VoiceHub.app) }

    /** A direct web search with no validated connection: kept (its card's rule with it), and the one line to say; null: go on as now. */
    fun keepOffline(said: String, query: String, lang: Lang, untrusted: Boolean, locked: Boolean): Phrase? {
        if (onlineNow()) return null
        // Locked: web search being off is said first; only a question that could go asks for the unlock.
        if (locked) return (gate(said, query) as? LaterGate.Off)?.why ?: EverydayStrings.laterUnlock()
        return keepNow(said, query, lang, untrusted)
    }

    /** Web search's gate now, and the queue's keep: seams for the JVM tests. */
    internal var gate: (String, String) -> LaterGate = { said, query -> SignalLater.gate(VoiceHub.app, VoiceHub.settings, said, query) }
    internal var keeper: (String, String, Lang, Boolean) -> SignalLater.Kept = { said, query, lang, asks -> SignalLater.keep(VoiceHub.app, said, query, lang, asks) }

    /** Kept with its ask-first rule; with web search off, nothing is kept and the line says how to turn it on. */
    internal fun keepNow(said: String, query: String, lang: Lang, untrusted: Boolean): Phrase = when (val g = gate(said, query)) {
        is LaterGate.Off -> g.why
        else -> kept(keeper(said, query, lang, untrusted || g != LaterGate.Goes))
    }

    /**
     * On an unlocked show: what came back as one turn ([turn]: PanelController.routineTurn), deleted once shown; else the
     * oldest question back online that needs your OK, sent as you asked it ([send]), so the usual card asks.
     */
    fun onShow(scope: CoroutineScope, turn: (String, suspend (String, PanelRoutines.Host) -> Unit) -> Unit, send: (String) -> Unit) {
        scope.launch {
            val app = VoiceHub.app
            val items = try { withContext(Dispatchers.IO) { SignalLater.vault(app).update { Later.expire(it, System.currentTimeMillis()) } } } catch (t: Throwable) {
                RLog.w("later_show", t)
                null
            } ?: return@launch
            waiting = Later.waiting(items).size
            val show = Later.toShow(items)
            if (show.isNotEmpty()) {
                val first = Langs.of(show.first().lang)
                turn(EverydayStrings.laterLabel().render(first)) { replyId, host ->
                    val l = host.langs(null, show.first().lang)
                    withContext(Dispatchers.IO) { SignalLater.vault(app).update { Later.drop(it, show.map { i -> i.id }.toSet()) } }
                    LaterNotices.cancel(app, show)
                    host.text(replyId, show.joinToString("\n\n") { shown(it).render(l) })
                    host.finish(replyId)
                }
                return@launch
            }
            // Web search off now: each READY one is dropped and nothing is sent.
            val next = withContext(Dispatchers.IO) { Later.toSend(items) { gate(it.said, it.query) } }
            if (next.drop.isEmpty()) return@launch
            var sent: LaterItem? = null
            val left = withContext(Dispatchers.IO) { SignalLater.vault(app).update { all -> takeOff(all, next).also { sent = it.second }.first } } ?: return@launch
            LaterNotices.cancel(app, items.filter { it.id in next.drop })
            waiting = Later.waiting(left).size
            sent?.let { send(it.said) }
        }
    }

    /**
     * The take-off in one vault change: [next]'s drops, and the one to send only if it is still there and READY, so two
     * overlapping shows, or a cancel between the read and the take-off, never send it twice or after the cancel.
     */
    internal fun takeOff(all: List<LaterItem>, next: Later.Send): Pair<List<LaterItem>, LaterItem?> =
        Later.drop(all, next.drop) to next.item?.takeIf { s -> all.any { it.id == s.id && it.state == LaterState.READY } }

    /** An expired one: its time only. An answered one with no answer yet: its search never finished (a crash mid-search). */
    internal fun shown(i: LaterItem): Phrase {
        val t = LaterNotices.clock(i.askedAt)
        if (i.state == LaterState.EXPIRED) return EverydayStrings.laterExpired("t" to t)
        val answer = i.answer
        val a = when {
            answer == null -> EverydayStrings.laterUnfinished()
            answer.isBlank() -> EverydayStrings.laterNothingFound()
            else -> Phrase.Plain(answer)
        }
        return Phrase.Join(listOf(EverydayStrings.laterAsked("t" to t, "q" to Arg.Plain(i.said)), a), "\n")
    }
}
