// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.RLog
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.files.ChatFiles
import app.heyrobot.core.files.FilePrompt
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseReply
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.FilePixels
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZonedDateTime

// VOICE PROCESS. chat-files: a file attached to a question in the panel (the File button beside Camera and Photos, or E7
// with a URI). Its bytes are read here, never parsed here: :files reads them (FileParseLink: a text file's or a PDF's text,
// a picture's or a scanned PDF's first page as pixels), and only what :files gives back is kept. The file belongs to this
// conversation: a picture goes with its question as a photo does; a text or a PDF is answered by a tool-free FILE_ANSWER
// one-shot (core FilePrompt.attached: the file's text framed and quoted as information, never instructions), read from its
// start, and the reply says when only the first part was read. History keeps its name, never its contents
// (ChatTurn.fileName); a new conversation, closing the panel or removing the chip forgets it; "file this" (or the chip's
// Save to Robot Files) files its own bytes (PanelFiles.picked). Nothing is granted for later: no persistable permission.

internal class PanelAttach(
    private val state: MutableStateFlow<PanelState>,
    private val replies: PanelReplies,
    private val voice: ReplyVoice,
    private val port: PanelFileAnswer.Port,
) {
    /** A file :files read: its [name]; [text] for a text file or a PDF with words ([textCut]: :files read only its start); else [jpeg], a picture. */
    class Held(val name: String, val text: String?, val textCut: Boolean, val jpeg: ByteArray?)

    /** The panel's view of a picked file: [held] and its chip's [thumb], or why it isn't read. */
    class Picked(val held: Held?, val thumb: Bitmap?, val original: ByteArray?, val tooBig: Boolean, val notice: Phrase?)

    /** The answer to [question] about [file]'s text, into the reply [replyId]. */
    suspend fun answer(replyId: String, question: String, file: Held) {
        val langs = port.turnLangs
        val text = file.text ?: return said(replyId, PanelStrings.fileUnreadable())
        // F50: as read_file's private answers: shown only, a placeholder in History.
        val private = app.heyrobot.core.files.FilesForHelper.attachedPrivate(file.name, text)
        val speak = state.value.speak && !private
        val req = FilePrompt.attached(question, text, langs.say, ZonedDateTime.now(), spoken = state.value.speak, maxTokens = port.contextTokens)
            ?: return said(replyId, PanelStrings.fileUnreadable())
        if (private) port.privateAnswer(replyId)
        // Personal from the first sentence (a car's or a headset's session reads nothing of it aloud without asking).
        PanelFileAnswer.markFiles(replyId)
        val part = ChatFiles.partLine(file.name, file.textCut, req.cut)
        val head = part?.let { buildString { append(it.render(langs)); append("\n\n") } } ?: ""
        if (part != null) port.sayInto(replyId, part) else replies.setText(replyId, "")
        var streamed = 0
        val r = port.ask(req.request) { soFar ->
            if (soFar.length < streamed) {
                replies.setText(replyId, head)
                streamed = 0
            }
            if (soFar.length > streamed) {
                val delta = soFar.substring(streamed)
                if (streamed == 0 && head.isNotEmpty()) replies.appendText(replyId, "\n\n")
                streamed = soFar.length
                replies.appendText(replyId, delta)
                voice.feed(replyId, delta, speak).forEach(port::speakSentence)
            }
        }
        if (r is OneShotResult.Cancelled) return port.finishReply(replyId, DoneReason.CANCELLED)
        val answer = FilePrompt.answerText(r) ?: return port.failReply(replyId, ToolStrings.fileNotAnswered().render(langs))
        replies.setText(replyId, head + answer)
        // Lengths only: enough to tell a reply that reached the bubble from one that didn't (cf-fix).
        RLog.i("file_attached_answer", "chars" to answer.length, "part" to (part != null))
        voice.finish(replyId, cancelled = false, speak = speak)?.let(port::speakSentence)
        port.noteRead(answer)
        port.finishReply(replyId, if ((r as? OneShotResult.Done)?.reason == DoneReason.LENGTH) DoneReason.LENGTH else DoneReason.END)
    }

    private fun said(replyId: String, p: Phrase) {
        port.sayInto(replyId, p)
        port.finishReply(replyId, DoneReason.END)
    }

    companion object {
        /** The file attached to the next question; null once it's gone with it, removed, or forgotten. */
        @Volatile private var held: Held? = null

        /** What the File button's picker offers (the panel names the files code only through here and PanelFiles). */
        val PICKER_TYPES: Array<String> get() = ChatFiles.PICKER_TYPES

        /** The attached file, taken for the question it goes with (a follow-up doesn't read it again). */
        fun take(): Held? = held.also { held = null }

        fun hold(h: Held?) { held = h }

        /** A new conversation, the panel closed or the chip removed: the file is gone. */
        fun forget() { held = null }

        /**
         * E7's entry point and the File button's: [uri] (a document you picked or shared) attached to the panel's next
         * question. Blocks (call it off the main thread); the panel hears on its own thread. On the lock screen it
         * only says to unlock.
         */
        fun attach(ctx: Context, uri: Uri) {
            val picked = if (Assistant.locked(ctx)) null else read(ctx, uri)
            Handler(Looper.getMainLooper()).post { if (picked == null) PanelController.fileLocked() else PanelController.onFilePicked(picked) }
        }

        /** [uri]'s bytes and name, read by :files: what the panel holds of it, or the one line why not. */
        private fun read(ctx: Context, uri: Uri): Picked {
            val name = try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (t: Exception) {
                RLog.w("attach_name", t)
                null
            }.let(ChatFiles::cleanName) ?: TurnStrings.titleFile().render(AppLanguage.langs(ctx))
            val bytes = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readNBytes(ParseLimits.MAX_INPUT + 1) }
            } catch (t: Exception) {
                RLog.w("attach_read", t)
                null
            }
            val tooBig = bytes != null && bytes.size > ParseLimits.MAX_INPUT
            val way = when (val c = ChatFiles.check(bytes, tooBig)) {
                is ChatFiles.Check.Refused -> return refused(c.why)
                is ChatFiles.Check.Read -> c.way
            }
            val b = bytes!!
            val link = FileParseLink(ctx)
            try {
                if (!link.start()) return refused(PanelStrings.fileUnreadable())
                if (way != ChatFiles.Way.PICTURE) {
                    when (val r = link.read(b, ParseRequest.TEXT)) {
                        is ParseResult.Refused -> return refused(ChatFiles.refusal(r.status, r.type))
                        is ParseResult.Failed -> return refused(PanelStrings.fileUnreadable())
                        is ParseResult.Ok -> {
                            val text = r.out.text?.takeIf { it.isNotBlank() }
                            if (text != null) return Picked(Held(name, text, r.out.has(ParseReply.TEXT_CUT), null), null, b, false, null)
                            if (way == ChatFiles.Way.TEXT) return refused(PanelStrings.fileUnreadable())
                            // A PDF without words (a scan): its first page, as a picture.
                            val first = picture(link, b, name) ?: return refused(PanelStrings.fileUnreadable())
                            return if (r.out.pages > 1) Picked(first.held, first.thumb, b, false, PanelStrings.fileFirstPage("name" to name)) else first
                        }
                    }
                }
                return picture(link, b, name) ?: refused(PanelStrings.fileUnreadable())
            } finally {
                link.end("attach_done") // l10n: not text (a log code)
            }
        }

        /** [b]'s first page (or the picture itself) as :files draws it: the JPEG the model sees and the chip's thumbnail. */
        private fun picture(link: FileParseLink, b: ByteArray, name: String): Picked? {
            val r = link.pixels(b, thumb = false) as? ParseResult.Ok ?: return null
            val bmp = r.out.pixels?.let { FilePixels.bitmap(it, thumb = false) } ?: return null
            return Picked(Held(name, null, false, Images.jpeg(bmp)), Images.scaled(bmp, Images.THUMB_EDGE), b, false, null)
        }

        private fun refused(why: Phrase) = Picked(null, null, null, false, why)
    }
}
