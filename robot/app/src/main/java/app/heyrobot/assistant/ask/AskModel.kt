// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.ask.AskCaps
import app.heyrobot.core.ask.AskHeard
import app.heyrobot.core.ask.AskRefusal
import app.heyrobot.core.ask.AskShare
import app.heyrobot.core.ask.AskShown
import app.heyrobot.core.ask.AudioSniff
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.parse.FileSniff
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// VOICE PROCESS. Ask Robot's door (docs/plans/everyday-set-plan.md §7.2, D19, D20; job E7), apart from its window so the
// rules are tested with a fake port (AskRobotTest). In order, before anything of the share is read:
//   1. only the text menu (PROCESS_TEXT) and one thing shared (SEND: text, one image, one audio file): core AskShare.kind;
//   2. never during a phone lock or while the phone is locked, never with "Ask Robot in other apps" off;
//   3. only while Robot is the assistant (else "Make Robot your assistant to use Ask Robot." and its setup);
//   4. another app's content:// only (core ShareRules, as Robot Files' share target), read once with a cap and a
//      deadline, no persistable permission, nothing written;
//   5. a photo decoded only in :files (SharedPhoto); a voice message sniffed (core AudioSniff), decoded through Android's
//      media sandboxes (SharedAudio, D20) and written down by Parakeet, at most 3 minutes;
//   6. handed to the panel as a one-time id (AskInbox), never the content in an intent.
// Robot never answers the sending app: no result is ever set (Q5), and the app is never named (§7.7).

/** What a start of Ask Robot brought, read from its intent: every value the sender's. */
class AskRequest(val kind: SharedKind?, val text: CharSequence?, val uri: Uri?) {
    companion object {
        /** The action, the claimed type, the text and one URI (EXTRA_STREAM, else the clip's first). A bad extra is nothing. */
        fun of(i: Intent?): AskRequest {
            if (i == null) return AskRequest(null, null, null)
            val uri = try {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            } catch (t: Exception) {
                RLog.w("ask_extra", t)
                null
            }
            val text = try {
                when (i.action) {
                    AskShare.PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    AskShare.SEND -> i.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            return AskRequest(AskShare.kind(i.action, i.type, uri != null), text, uri)
        }
    }
}

/** What the window shows. */
sealed class AskUi {
    /** Nothing yet (text goes straight to the panel). */
    data object Quiet : AskUi()
    /** Reading a photo, or writing down a voice message. */
    class Working(val kind: SharedKind) : AskUi()
    class Refused(val why: Phrase) : AskUi()
    /** Robot isn't the assistant: the line and the way to its setup. */
    data object NotAssistant : AskUi()
    /** Handed to the panel (or given up): the window goes. */
    data object Done : AskUi()
}

class AskModel(private val port: Port, private val req: AskRequest, private val worker: Executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "robot-ask").apply { isDaemon = true }  // l10n: not text (a thread's name)
}) {
    interface Port {
        /** Why nothing may come in now (a phone lock, the phone locked, the switch off), or null. */
        fun gate(): AskRefusal?
        /** true: Robot's assistant runs; false: Robot isn't the assistant; null: it is, but its service isn't running yet. */
        fun assistant(): Boolean?
        /** [uri] is another app's content (core ShareRules). */
        fun allowed(uri: Uri): Boolean
        /** [uri]'s bytes, read once, at most [cap], within [AskCaps.READ_MS]. */
        fun read(uri: Uri, cap: Int): Read
        fun photo(bytes: ByteArray): SharedPhoto.Read
        /** Why a voice message can't be written down now (no speech model, a transcription running), or null. */
        fun speech(): AskRefusal?
        /** [bytes] decoded and written down, or why not. */
        fun transcribe(bytes: ByteArray, cancelled: () -> Boolean): Heard
        /** Holds [item] and opens the panel with its id; false when it couldn't. */
        fun open(item: SharedItem): Boolean
        fun log(event: VoiceEvent, a: Int, b: Int = 0)
    }

    sealed class Read {
        class Bytes(val b: ByteArray) : Read()
        data object TooLarge : Read()
        data object TimedOut : Read()
        data object Unreadable : Read()
    }

    sealed class Heard {
        class Words(val text: String, val seconds: Int, val cut: Boolean) : Heard()
        class Not(val why: AskRefusal, val heard: AskHeard?, val seconds: Int = 0) : Heard()
    }

    var ui: AskUi by mutableStateOf(AskUi.Quiet)
        private set

    @Volatile private var closed = false

    /** Everything up to the reading, here; the reading and decoding on the worker. */
    fun start() {
        if (closed) return
        val kind = req.kind ?: return refuse(AskRefusal.NOTHING, null)
        port.gate()?.let { return refuse(it, kind) }
        when (port.assistant()) {
            false -> {
                port.log(VoiceEvent.ASK_REFUSED, AskRefusal.NOT_ASSISTANT.id, kind.id)
                ui = AskUi.NotAssistant
                return
            }
            null -> return refuse(AskRefusal.NOT_READY, kind)
            true -> {}
        }
        if (kind == SharedKind.TEXT) {
            val t = AskShare.text(req.text) ?: return refuse(AskRefusal.NOTHING, kind)
            return open(SharedItem(SharedKind.TEXT, t.text, cut = t.cut))
        }
        val uri = req.uri ?: return refuse(AskRefusal.NOTHING, kind)
        if (!port.allowed(uri)) return refuse(AskRefusal.NOT_ALLOWED, kind)
        if (kind == SharedKind.VOICE) port.speech()?.let { return refuse(it, kind) }
        ui = AskUi.Working(kind)
        worker.execute {
            try {
                work(kind, uri)
            } catch (t: Throwable) {
                RLog.w("ask_work", t)
                refuse(AskRefusal.UNREADABLE, kind)
            }
        }
    }

    /** Cancel, Back, or the window going: nothing more is read, and nothing reaches the panel. */
    fun close() {
        if (closed) return
        closed = true
        if (ui !is AskUi.Done) ui = AskUi.Done
        // Its one thread goes when any read under way is over: one share, one thread, no more.
        (worker as? ExecutorService)?.shutdown()
    }

    private fun work(kind: SharedKind, uri: Uri) {
        val cap = if (kind == SharedKind.PHOTO) AskCaps.MAX_IMAGE_BYTES else AskCaps.MAX_AUDIO_BYTES
        val bytes = when (val r = port.read(uri, cap)) {
            is Read.Bytes -> r.b
            Read.TooLarge -> return refuse(AskRefusal.TOO_LARGE, kind)
            Read.TimedOut -> return refuse(AskRefusal.TIMED_OUT, kind)
            Read.Unreadable -> return refuse(AskRefusal.UNREADABLE, kind)
        }
        if (closed) return
        // The gate again: a phone lock may have started while the sending app took its time.
        port.gate()?.let { return refuse(it, kind) }
        when (kind) {
            SharedKind.PHOTO -> when (val p = port.photo(bytes)) {
                is SharedPhoto.Read.Ok -> open(SharedItem(SharedKind.PHOTO, jpeg = p.jpeg, thumb = p.thumb))
                is SharedPhoto.Read.Refused -> refuse(p.why, kind)
            }
            SharedKind.VOICE -> {
                if (AudioSniff.container(bytes.copyOf(minOf(bytes.size, FileSniff.PREFIX)), bytes.size.toLong()) == null) return refuse(AskRefusal.WRONG_TYPE, kind)
                when (val h = port.transcribe(bytes) { closed }) {
                    is Heard.Words -> {
                        port.log(VoiceEvent.ASK_TRANSCRIBE, AskShare.secondsBucket(h.seconds), (if (h.cut) AskHeard.CUT else AskHeard.WORDS).id)
                        if (closed) return
                        port.gate()?.let { return refuse(it, kind) }
                        open(SharedItem(SharedKind.VOICE, h.text, seconds = h.seconds, cut = h.cut))
                    }
                    is Heard.Not -> {
                        h.heard?.let { port.log(VoiceEvent.ASK_TRANSCRIBE, AskShare.secondsBucket(h.seconds), it.id) }
                        if (h.heard != AskHeard.CANCELLED) refuse(h.why, kind)
                    }
                }
            }
            SharedKind.TEXT -> {}
        }
    }

    private fun open(item: SharedItem) {
        if (closed) return
        val ok = try { port.open(item) } catch (t: Throwable) { RLog.w("ask_open", t); false }
        port.log(VoiceEvent.ASK_SHARED, item.kind.id, (if (!ok) AskShown.NOT_SHOWN else if (item.cut) AskShown.CUT else AskShown.SHOWN).id)
        ui = if (ok) AskUi.Done else AskUi.Refused(EverydayStrings.askNotOpened())
    }

    private fun refuse(why: AskRefusal, kind: SharedKind?) {
        port.log(VoiceEvent.ASK_REFUSED, why.id, kind?.id ?: 0)
        if (!closed) ui = AskUi.Refused(words(why, kind))
    }

    companion object {
        /** What the window says for [why] ([kind]: the photo's or the voice message's own words where they differ). */
        fun words(why: AskRefusal, kind: SharedKind?): Phrase = when (why) {
            AskRefusal.PHONE_LOCK -> EverydayStrings.askPhoneLock()
            AskRefusal.LOCKED -> EverydayStrings.askLocked()
            AskRefusal.OFF -> EverydayStrings.askOff("page" to Arg.Ui(UiStrings.inSettings(UiStrings.actions)))
            AskRefusal.NOT_ASSISTANT -> EverydayStrings.askNotAssistant()
            AskRefusal.NOT_READY -> EverydayStrings.askNotReady()
            AskRefusal.NOTHING -> EverydayStrings.askNothing()
            AskRefusal.NOT_ALLOWED -> EverydayStrings.askNotAllowed()
            AskRefusal.TOO_LARGE -> if (kind == SharedKind.VOICE) EverydayStrings.askVoiceTooLarge("size" to Arg.Bytes(AskCaps.MAX_AUDIO_BYTES.toLong()))
                else EverydayStrings.askPhotoTooLarge("size" to Arg.Bytes(AskCaps.MAX_IMAGE_BYTES.toLong()))
            AskRefusal.WRONG_TYPE -> EverydayStrings.askWrongType()
            AskRefusal.UNREADABLE -> if (kind == SharedKind.VOICE) EverydayStrings.askVoiceUnreadable() else EverydayStrings.askPhotoUnreadable()
            AskRefusal.NO_SPEECH_MODEL -> EverydayStrings.askNoSpeechModel("page" to Arg.Ui(UiStrings.inSettings(UiStrings.model)))
            AskRefusal.BUSY -> EverydayStrings.askBusy()
            AskRefusal.TIMED_OUT -> EverydayStrings.askTimedOut()
            AskRefusal.NO_WORDS -> EverydayStrings.askNoWords()
        }
    }
}
