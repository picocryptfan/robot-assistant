// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.FileAsk
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileFindCode
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FilePrompt
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.sys.FilesIntake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

// VOICE PROCESS. 1.4, read_file's answer (files-1.4-plan.md §5.1, §5.4, D8; job F9). The model called read_file and its
// turn ended there (core TurnRunner, Executed.file), as at web_search: here a tool-free FILE_ANSWER one-shot (core
// FilePrompt.answer: a fixed system prompt, the file's text framed by FileQuote as information only, the question last)
// answers from the file's text as the sealed index keeps it, read again now behind the lock gate. Its words stream into
// the reply like a web answer's; History keeps them as a content record (the READ_FILE card), and a rebuilt conversation
// frames them as information (core Conversation). Nothing it writes can call a tool: one-shots describe none.
//
// A private file (a medical file, an ID, or words SecretDetector flags: core FilesForHelper) is answered on the screen
// only: never read aloud, and History keeps a placeholder in its place, so the live conversation (which closes after the
// answer) is the only place its words were, and no later "include the conversation" can send them to a helper.

internal class PanelFileAnswer(
    private val state: MutableStateFlow<PanelState>,
    private val replies: PanelReplies,
    private val voice: ReplyVoice,
    private val port: Port,
) {
    /** What the answer needs of PanelController. */
    interface Port {
        val ctx: Context
        val turnLangs: Langs
        fun sayInto(replyId: String, text: Phrase)
        fun finishReply(replyId: String, reason: DoneReason)
        fun failReply(replyId: String, message: String)
        fun speakSentence(s: String)
        /** The answer to [replyId] comes from a private file: not read aloud, a placeholder in History, the conversation closed after. */
        fun privateAnswer(replyId: String)
        /** What the answer said, for QueryCheck (a later web search never carries a file's words online). */
        fun noteRead(text: String)
        /** The vault's lock gate and index (tests give their own). */
        fun gate(): FilesIntake.Gate = FilesIntake.gate(ctx)
        fun index(): FileIndex? = app.heyrobot.sys.RobotFiles.shown(ctx)
        /** The FILE_ANSWER one-shot (BrainOneShots.run; tests give their own). [onText]: the reply so far. */
        suspend fun ask(req: app.heyrobot.core.BrainMsg.OneShot, onText: (String) -> Unit): OneShotResult =
            BrainOneShots.run(req, OneShotBudget(startWithinMs = null, totalMs = ANSWER_MS), onText)
        /** The loaded model's context, for how much of the file fits. */
        val contextTokens: Int get() = BrainKeeper.state.value.contextTokens.takeIf { it > 0 } ?: ContextWindow.MAX_TOKENS
    }

    /** read_file's answer to [ask], into the reply [replyId]. */
    suspend fun answer(replyId: String, ask: FileAsk) {
        val langs = port.turnLangs
        // The gate again: the phone may have locked since the call (the key closes with it).
        val e: FileEntry = withContext(Dispatchers.IO) {
            PanelFileTools.refusal(port.gate())?.let { return@withContext null to (it.text ?: FilesStrings.locked()) }
            val idx = port.index() ?: return@withContext null to FilesStrings.locked()
            val e = idx.entry(ask.id)?.takeIf { !it.damaged && it.kind != FileKind.DANGEROUS && it.text.isNotBlank() }
                ?: return@withContext null to ToolStrings.fileNoText()
            e to null
        }.let { (e, why) -> e ?: return said(replyId, why!!) }
        val speak = state.value.speak && !ask.private
        val req = FilePrompt.answer(ask.question, e.text, e.kind, e.from.source, langs.say, ZonedDateTime.now(), spoken = state.value.speak,
            maxTokens = port.contextTokens) ?: return said(replyId, ToolStrings.fileNoText())
        if (ask.private) port.privateAnswer(replyId)
        var streamed = 0
        val r = port.ask(req.request) { soFar ->
            // A request that gave way starts over: what was shown goes.
            if (soFar.length < streamed) {
                replies.setText(replyId, "")
                streamed = 0
            }
            if (soFar.length > streamed) {
                val delta = soFar.substring(streamed)
                streamed = soFar.length
                replies.appendText(replyId, delta)
                voice.feed(replyId, delta, speak).forEach(port::speakSentence)
            }
        }
        if (r is OneShotResult.Cancelled) return port.finishReply(replyId, DoneReason.CANCELLED)
        val text = FilePrompt.answerText(r)
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.ANSWER.id, if (text != null) 1 else 0)
        if (text == null) return port.failReply(replyId, ToolStrings.fileNotAnswered().render(langs))
        replies.setText(replyId, text)
        voice.finish(replyId, cancelled = false, speak = speak)?.let(port::speakSentence)
        port.noteRead(text)
        port.finishReply(replyId, if ((r as? OneShotResult.Done)?.reason == DoneReason.LENGTH) DoneReason.LENGTH else DoneReason.END)
    }

    /** Robot's own line in place of an answer (the file's gone, locked meanwhile, no words to read). */
    private fun said(replyId: String, p: Phrase) {
        port.sayInto(replyId, p)
        port.finishReply(replyId, DoneReason.END)
    }

    companion object {
        /** The answer's whole time on the model once it starts (as a web answer's). */
        const val ANSWER_MS = 180_000L

        /** Replies that answered from a private file: History keeps [FilesStrings.privateAnswer] in their place. */
        private val privateReplies = HashSet<String>()

        fun markPrivate(replyId: String) = synchronized(privateReplies) { privateReplies += replyId }

        /** FX1: replies Robot Files' router gave: History marks them (ChatTurn.files), for travel mode's placeholder. */
        private val filesReplies = HashSet<String>()

        fun markFiles(replyId: String) = synchronized(privateReplies) { filesReplies += replyId }

        /** Whether [id] is a reply Robot Files' router gave and History hasn't taken yet: personal while it's spoken. */
        fun isFiles(id: String) = synchronized(privateReplies) { id in filesReplies }

        /** [t] as History and the live list keep it: a private file's answer only as its placeholder, in [langs]. */
        fun forHistory(t: ChatTurn, langs: Langs): ChatTurn {
            val (private, files) = synchronized(privateReplies) { privateReplies.remove(t.id) to filesReplies.remove(t.id) }
            val marked = if (files) t.copy(files = true) else t
            return if (private) marked.copy(text = FilesStrings.privateAnswer().render(langs)) else marked
        }

        /** A new conversation: nothing waits to be kept as a placeholder. */
        fun forget() = synchronized(privateReplies) {
            privateReplies.clear()
            filesReplies.clear()
        }
    }
}
