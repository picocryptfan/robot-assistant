// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.os.Handler
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnResult
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.web.Citations
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.core.web.WebQueryContext
import app.heyrobot.core.web.WebRoute
import app.heyrobot.core.web.WebStatus
import app.heyrobot.helpers.web.WebSearch
import app.heyrobot.helpers.web.WebStep
import app.heyrobot.sys.WebStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

// VOICE PROCESS. Web search in the panel (0.9, core web; design Part A): "search the web for …" in your own words, the
// web_search tool's run on its own thread with one deadline, what the search is doing on the panel and aloud, where the
// conversation's words came from for QueryCheck (with everything read tools returned in it), and the answer from what was
// found. What is sent, when and to which service is core web's and WebTool's; this is the panel's side of it. The turn
// runner, the cards, the tools, the reply's end and the voice's guard stay in PanelController, through
// [PanelWebSearch.Port]; the screen's text comes from PanelScreenShare. Its callers run on the main thread, as before;
// [PanelWebSearch.context] runs on the search's own thread, and [PanelWebSearch.step] is called from it.

/**
 * The panel's web search over PanelController's [state] and [chat] (the same list, read live); [voice]: what of the reply
 * is read aloud; [port]: what it needs of PanelController.
 */
internal class PanelWebSearch(
    private val state: MutableStateFlow<PanelState>,
    private val replies: PanelReplies,
    private val screenShare: PanelScreenShare,
    private val chat: List<ChatTurn>,
    private val voice: ReplyVoice,
    private val port: Port,
) {
    /** What web search needs of PanelController. */
    interface Port {
        /** The voice process's context (VoiceHub.app). */
        val ctx: Context
        /** The main thread's handler: a search's steps come from its own thread. */
        val main: Handler
        /** The settings as the turns use them now (VoiceHub.settings: whether an answer shows its cost). */
        val settings: RobotSettings
        /** What you typed or said this turn (never screen text), read live. */
        val turnSaid: String?
        /** The turn's languages, read live. */
        val turnLangs: Langs
        /** web_search run by the phone side of a turn (PanelController's ToolExecutor) with [abort], on OnlineRun's thread. */
        fun search(abort: Abort, action: Action.WebSearch): Executed
        /** Your own search's [call] as a turn on reply [replyId]: the panel's direct call, its cards and the quarantine. */
        suspend fun runDirect(replyId: String, call: RawToolCall, untrusted: Boolean): TurnResult
        /** What Robot says of [p] in the turn's reply language. */
        fun said(p: Phrase): String
        /** Robot's own sentence into its empty reply, shown and said in its voice. */
        fun sayInto(replyId: String, text: Phrase)
        fun finishReply(replyId: String, reason: DoneReason)
        fun failReply(replyId: String, message: String)
        /** The turn opened an app: the panel goes. */
        suspend fun leaveForApp()
        /** One sentence aloud in Robot's voice, through the panel's guard for a headset or car. */
        fun speakSentence(s: String)
    }

    private val turnSaid: String? get() = port.turnSaid
    private val turnLangs: Langs get() = port.turnLangs

    /**
     * "Search the web for …": web_search with your words as they are, gated and carded as Gemma's call would be. 1.0: a
     * search in your browser instead when your words name one, or while Robot's own web search is off (BrowserSearchTool).
     */
    suspend fun direct(replyId: String, route: WebRoute, question: String, history: List<ChatTurn>, untrusted: Boolean) {
        RLog.i("web_direct")
        val call = withContext(Dispatchers.IO) { BrowserSearchTool.directCall(port.ctx, route) } ?: RawToolCall(ToolName.WEB_SEARCH.id, mapOf("query" to route.query))
        val result = port.runDirect(replyId, call, untrusted)
        result.failure?.let { return port.failReply(replyId, it) }
        result.web?.let { return answer(replyId, question, history, it) }
        port.finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) port.leaveForApp()
    }

    /** web_search's run: on its own thread with one deadline (the request, then the pages); Stop disconnects it. */
    suspend fun run(action: Action.WebSearch): Executed {
        val abort = Abort(WebSearch.DEADLINE_MS)
        return try {
            OnlineRun.run(abort, WebSearch.DEADLINE_MS) { a -> port.search(a, action) } ?: WebOutcome.Failed(null, WebFail.TIMEOUT).let { o ->
                VoiceLog.add(VoiceEvent.WEB_FAILED, WebFail.TIMEOUT.id)
                Executed(ActionStatus.FAILED, o.sentence, web = o)
            }
        } finally {
            state.update { it.copy(helperStatus = null) }
        }
    }

    /** What the search is doing ("Searching the web…", "Reading 3 pages…"): the panel's status, and said once each. */
    fun step(step: WebStep, lang: String) {
        port.main.post {
            // 1.1: in the turn's reply language (the search's own [lang] is the question's, which ReplyLang already heard).
            val shown = when (step) {
                WebStep.Searching -> WebStatus.searchingText
                is WebStep.Reading -> WebStatus.readingText(step.pages, step.done.takeIf { it > 0 })
            }.render(turnLangs)
            state.update { if (it.busy) it.copy(helperStatus = shown) else it }
            val say = when (step) {
                WebStep.Searching -> WebStatus.searchingText
                is WebStep.Reading -> if (step.done == 0) WebStatus.readingText(step.pages) else null
            }?.let(port::said)
            val id = state.value.turns.lastOrNull { it.streaming }?.id ?: return@post
            if (say != null) voice.say(id, say, state.value.speak)?.let(port::speakSentence)
        }
    }

    /**
     * Where this conversation's words came from, for QueryCheck (core web WebQueryContext): your words, content Robot
     * didn't write, and personal content (with what read tools' cards showed).
     */
    fun context(): QueryContext {
        val bodies = state.value.turns.flatMap { t -> t.actions.filter { it.content && it.tool != ToolName.WEB_SEARCH }.mapNotNull { it.body } }
        val screen = screenShare.text?.takeIf { chat.any { it.attachment == Attachment.SCREEN } }
        // 0.7's memory: what the model was given from it never goes to a search service (QueryCheck refuses it).
        val memory = MemoryPanel.forWebCheck(chat.flatMap { it.memoryIds })
        val reads = synchronized(readTexts) { readTexts.toList() }
        return WebQueryContext.of(chat.toList(), turnSaid.orEmpty(), screen, bodies + reads, memory, fresh = readsAllKnown)
    }

    /** The search ran: the answer from what it found (WebReply), streamed and spoken without its [n] marks, then its card. */
    suspend fun answer(replyId: String, question: String, history: List<ChatTurn>, outcome: WebOutcome) {
        state.update { it.copy(helperStatus = null) }
        val found = when (outcome) {
            is WebOutcome.Failed -> {
                if (outcome.why == WebFail.STOPPED) return port.finishReply(replyId, DoneReason.CANCELLED)
                port.sayInto(replyId, outcome.sentence)
                return port.finishReply(replyId, DoneReason.END)
            }
            is WebOutcome.Found -> outcome
        }
        val r = WebReply.answer(port.ctx, question, history, found, state.value.speak, port.settings.helperShowCost, onRestart = { replies.setText(replyId, "") }) { delta ->
            replies.appendText(replyId, delta)
            voice.feed(replyId, delta, state.value.speak).forEach { port.speakSentence(Citations.forSpeech(it)) }
        }
        val a = when (r) {
            is WebReply.Result.Answered -> r
            WebReply.Result.Cancelled -> return port.finishReply(replyId, DoneReason.CANCELLED)
            WebReply.Result.Failed -> return port.failReply(replyId, WebReply.noAnswer.render(turnLangs))
        }
        // The last words, without their marks; the search's card becomes its saved line (1.1: in the turn's reply language).
        val keep = withContext(Dispatchers.IO) { WebStore.load(port.ctx).keepSources }
        voice.finish(replyId, cancelled = !a.complete, speak = state.value.speak)?.let { port.speakSentence(Citations.forSpeech(it)) }
        state.update { st ->
            st.copy(turns = st.turns.map { t ->
                if (t.id != replyId) t else t.copy(text = a.text, web = a.card, actions = t.actions.map { x ->
                    if (x.tool == ToolName.WEB_SEARCH && x.status == ActionStatus.DONE) x.copy(summary = a.card.savedText(keep).render(turnLangs), detail = a.card.readText?.render(turnLangs), body = null,
                        content = true) else x
                })
            })
        }
        port.finishReply(replyId, if (a.complete) DoneReason.END else DoneReason.LENGTH)
    }

    /** Everything read tools returned to the model in this conversation (QueryCheck), and whether that is all of it. */
    private val readTexts = ArrayList<String>()
    @Volatile private var readsAllKnown = true

    /** 1.0 (injection-querycheck-notes-personal): what a read tool returned to the model, kept while this conversation lives. */
    fun noteRead(r: Executed) {
        if (r.results.isNullOrEmpty() || r.web != null) return
        synchronized(readTexts) {
            readTexts += app.heyrobot.core.web.readTexts(r.results)
            r.body?.let { readTexts += it }
        }
    }

    /** A new conversation ([allKnown]) or one loaded from History, whose earlier reads aren't saved (not [allKnown]). */
    fun forgetReads(allKnown: Boolean) {
        synchronized(readTexts) { readTexts.clear() }
        readsAllKnown = allKnown
    }
}
