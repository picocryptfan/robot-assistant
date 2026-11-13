// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.RLog
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.TtsStop
import app.heyrobot.core.toPromptTurns
import app.heyrobot.core.web.WebPrompt
import app.heyrobot.core.web.request
import app.heyrobot.core.wiki.WikiCopy
import app.heyrobot.core.wiki.WikiDownload
import app.heyrobot.core.wiki.WikiSpeech
import app.heyrobot.core.wiki.WikiTurn
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.WikiStrings
import app.heyrobot.core.wiki.WikiBroken
import app.heyrobot.core.wiki.WikiGuard
import app.heyrobot.core.wiki.WikiRoute
import kotlin.coroutines.cancellation.CancellationException
import app.heyrobot.sys.WikiStore
import app.heyrobot.wiki.WikiLink
import java.io.File
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * review-wikipedia-p4-c: a turn the copy wrote into is content however it ends: its card, or the streamed words a stop,
 * ANSWER_MS or a failure left (kept in the chat and History as content, so the next turn stays untrusted).
 */
internal fun wikiContent(ui: UiTurn?): Boolean = ui?.wiki != null || ui?.content == true

// PANEL. Wikipedia P4 (wikipedia-offline-plan.md §5 and §6): the copy on this phone answers a question of fact after
// every command and memory, before the model alone (PanelController.send's last branch). core WikiTurn decides and makes
// the reply (WikiAnswer.reply; WikiCard, P4b: the articles with their links, CC BY-SA 4.0, the copy's month); this file opens the copy in the
// :wiki process (WikiLink, never online) and runs the one-shot. No copy, not the copy's question, nothing found: false,
// and the model answers alone. Logs numbers only: never the question, a title or the text.
internal class PanelWiki(
    private val replies: PanelReplies,
    private val voice: ReplyVoice,
    private val speak: () -> Boolean,
    private val say: (String) -> Unit,
    private val finish: (String, DoneReason) -> Unit,
) {
    /** True when the copy answered (or its answer was stopped): the turn is done. */
    suspend fun run(replyId: String, question: String, history: List<ChatTurn>, attached: Boolean, helperMode: Boolean, untrusted: Boolean): Boolean {
        // L5 (review-wikipedia-p4): the route first; the copies' JSON, the contacts and memory only for the copy's question.
        // fix-wiki-rc1-b: each check that keeps the copy out logs its code and counts (never the question), for a phone run.
        if (!WikiTurn.routes(question, attached, helperMode, untrusted)) return false.also { skip(WikiTurn.Gate.ROUTE) }
        val ctx = VoiceHub.app
        val stored = withContext(Dispatchers.IO) { WikiStore.load(ctx) }
        val files = WikiDownload.copies(ctx.filesDir, stored)
        val sets = files.filterNot { broken.skips(key(it.second)) }
        // H1 (review-wikipedia-p4): People, the contacts and memory answer their own questions; one that can't be read keeps the copy out.
        val own = object : WikiTurn.Own {
            override fun names() = app.heyrobot.sys.PeopleFiles.book(ctx).names
            override fun recalls(question: String) = MemoryPanel.recalls(question, app.heyrobot.sys.Assistant.locked(ctx))
        }
        val gate = try {
            withContext(Dispatchers.IO) { WikiTurn.refusal(question, sets.size, attached, helperMode, untrusted, own) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            RLog.w("wiki_own", t)
            return false
        }
        if (gate != null) return false.also { skip(gate, "stored" to stored.sets.size, "files" to files.size, "usable" to sets.size) } // l10n: not text (log keys)
        val spoken = speak()
        // L4 (review-wikipedia-p4): in a car or a headset each sentence is said once it is whole and the passages hold it
        // (WikiSpeech); the phone's speaker alone still says the reply at its end.
        val bySentence = spoken && PanelHandsFree.active
        var talk: WikiSpeech? = null
        val context = BrainKeeper.state.value.contextTokens.takeIf { it > 0 } ?: ContextWindow.MAX_TOKENS
        var link: WikiLink? = null
        var opened: File? = null
        // review-wikipedia-p4-b: what the copy found and the words streamed so far, for a stopped turn's checked words.
        var found: app.heyrobot.core.wiki.WikiAnswer.Found? = null
        var heard = ""
        val r = try {
            withContext(Dispatchers.IO) {
                WikiTurn.run(question, sets.size, { lang ->
                    // One copy open at a time: the question's language, else English (WikiAnswer.pick's order).
                    val (s, f) = sets.firstOrNull { it.first.lang == lang } ?: sets.firstOrNull { it.first.lang == "en" } ?: sets.first()
                    link = WikiLink.connect(ctx)
                    opened = f
                    listOfNotNull(link?.let { l -> l.open(f)?.let { info -> LinkCopy(l, s.lang, info.date) } })
                }, ZonedDateTime.now(), history.toPromptTurns(), spoken, WebPrompt.budget(context, spoken), attached, helperMode, untrusted,
                    find = { f -> WikiGuard.within(WikiGuard.DEADLINE_MS, { link?.close() }, f).also { found = it } }) { shot ->
                    var streamed = 0
                    replies.setContent(replyId, true)
                    talk = found?.takeIf { bySentence }?.let(::WikiSpeech)
                    WikiTurn.said(withContext(Dispatchers.Main) { BrainOneShots.run(shot.request(), OneShotBudget(startWithinMs = null, totalMs = ANSWER_MS)) { soFar ->
                        heard = soFar
                        if (soFar.length < streamed) { replies.setText(replyId, ""); streamed = 0 }
                        if (soFar.length > streamed) { replies.appendText(replyId, soFar.substring(streamed)); streamed = soFar.length }
                        talk?.feed(soFar)?.forEach { s -> voice.feed(replyId, "$s ", speak()).forEach(say) }
                    } })
                }
            }
        } catch (e: CancellationException) {
            // Stopped: the turn keeps only the words the passages hold (startTurn's handler then finishes it).
            if (found != null) replies.setText(replyId, WikiTurn.kept(found, heard))
            throw e
        } catch (t: Throwable) {
            RLog.w("wiki_answer", t)
            WikiTurn.Result.NotAsked
        } finally {
            link?.close()
        }
        // M4: a file that crashed :wiki (twice: WikiLink.gone) is noted once and said, then left out until it's downloaded again.
        val bad = opened?.takeIf { link?.gone == true }
        if (bad != null && broken.note(key(bad)) && r == WikiTurn.Result.NotAsked) {
            RLog.i("wiki_broken", "n" to 1)
            val line = WikiStrings.broken().render(Langs.of(Lang.of(WikiRoute.lang(question))))
            replies.setText(replyId, line)
            replies.setContent(replyId, false)
            voice.feed(replyId, line, speak()).forEach(say)
            finish(replyId, DoneReason.END)
            return true
        }
        return when (r) {
            WikiTurn.Result.NotAsked -> false.also {
                // connect: :wiki didn't bind; found: bound, but the copy didn't open or had nothing; unopened: never got that far.
                // L1 (review-fix-wiki-rc1-b): found carries the last call's WikiIpc.REPLY_* (a number), to tell "didn't open" from "nothing".
                val l = link
                if (opened == null) skip("unopened") else if (l == null) skip("connect") else skip("found", "status" to l.lastStatus) // l10n: not text (log codes)
                hush(replyId, talk, again = true); replies.setText(replyId, ""); replies.setContent(replyId, false) }
            WikiTurn.Result.Stopped -> true.also {
                // L4 (review-wikipedia-l4): stopped after speech (ANSWER_MS, a :brain cancel): what was heard stays and its month line is said.
                val end = if (talk?.spoke == true) talk?.end(heard, null) else null
                replies.setText(replyId, end?.shown ?: WikiTurn.kept(found, heard))
                end?.rest?.takeIf { it.isNotEmpty() }?.let { rest -> end.line?.let(PanelHandsFree::credit); voice.say(replyId, rest, speak())?.let(say) }
                finish(replyId, DoneReason.CANCELLED)
            }
            is WikiTurn.Result.Answered -> {
                RLog.i("wiki_answer", "n" to r.reply.articles.size)
                // M1 (review-wikipedia-l4): after a said sentence the bubble, History and the voice keep the same words.
                val end = talk?.end(heard, r.reply.spoken)
                replies.setText(replyId, end?.shown ?: r.reply.shown)
                replies.setWiki(replyId, r.reply.card)
                if (talk?.spoke == true) end?.line?.let(PanelHandsFree::credit)
                voice.feed(replyId, end?.rest ?: r.reply.spoken, speak()).forEach(say)
                finish(replyId, DoneReason.END)
                true
            }
        }
    }

    /**
     * L4: the copy's one-shot failed and the model answers alone ([again]): what a car or a headset is hearing of it stops at
     * once (a stopped one-shot ends what it said: WikiSpeech.end). Stop itself: PanelController.stopGenerating.
     */
    private fun hush(replyId: String, talk: WikiSpeech?, again: Boolean = false) {
        if (talk?.spoke != true || !voice.live(replyId)) return
        if (again) voice.start(replyId) else voice.mute()
        VoiceHub.stopVoice(TtsStop.COPY)
    }

    /** The copy as WikiAnswer reads it, through the :wiki process (blocking calls: IO only). */
    private class LinkCopy(private val link: WikiLink, override val lang: String, override val date: String?) : WikiCopy {
        override fun titles(query: String, max: Int) = link.titles(query, max).map { WikiCopy.Hit(it.path, it.title) }
        override fun search(query: String, max: Int) = link.search(query, max).map { WikiCopy.Hit(it.path, it.title) }
        override fun article(path: String) = link.article(path)?.let { WikiCopy.Page(it.path, it.title, it.html) }
    }

    private companion object {
        /** M4: the files that crashed :wiki in this process. */
        val broken = WikiBroken()

        fun key(f: File) = "${f.path}:${f.length()}:${f.lastModified()}"

        /**
         * fix-wiki-rc1-b: the check that kept the copy out, as a code, and numbers only (the copies recorded, on disk and not
         * skipped as broken; a status). L2 (review-fix-wiki-rc1-b): the route, on most turns, logs its code alone.
         */
        fun skip(gate: WikiTurn.Gate, vararg numbers: Pair<String, Int>) = skip(gate.name.lowercase(), *numbers)

        fun skip(gate: String, vararg numbers: Pair<String, Int>) = RLog.i("wiki_skip", "gate" to RLog.Code(gate), *numbers) // l10n: not text (log keys)

        /** As a web answer's: the whole one-shot once it starts. */
        const val ANSWER_MS = 180_000L
    }
}
