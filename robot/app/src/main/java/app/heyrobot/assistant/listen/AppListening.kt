// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.listen

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.heyrobot.assistant.PanelController
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.core.LogCode
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.everyday.ListenForAppCode
import app.heyrobot.core.everyday.ListenForApps
import app.heyrobot.core.everyday.ListenSheetCode
import app.heyrobot.voice.AppListen
import app.heyrobot.voice.SpeechToText

// VOICE PROCESS. Listening for other apps (docs/plans/everyday-set-plan.md §6.2, D13 to D15, E5): one request at a time,
// from the recognizer (RobotRecognitionService: an app's mic button) or the listening sheet (ListenSheetActivity: an app's
// RECOGNIZE_SPEECH). Its owner has checked it (core ListenForApps.decide) before it gets here. It listens on Robot's own
// capture through speech to text's app path (SpeechToText.startForApp: no audio focus, no audio from before it asked), with
// the wake word paused so "hey robot" said while dictating doesn't open the panel. Robot's own listening wins: it ends a
// request, which is told (Robot was busy). A request whose owner gave a check on its caller (the recognizer) ends when that
// check fails: about once a second and before any words go out, since Android stops only a background caller's own recording,
// never Robot's (E10). Nothing is kept: the words go to the request's owner and nowhere else, and the
// voice log gets a code and how long it listened. Main thread only.

class AppListening(private val port: Port) {
    interface Port {
        val main: Handler
        val stt: SpeechToText
        /** Robot is listening or talking itself, in a conversation or a call: busy for another app. */
        fun robotBusy(): Boolean
        /**
         * The wake word pauses for a request ([on]) or may listen again. Letting it go must never undo a pause the panel set
         * for its own question (Robot's own listening takes over a request after setting it).
         */
        fun pauseWake(on: Boolean)
        /** elapsedRealtime. */
        fun now(): Long
        fun log(event: VoiceEvent, a: Int, b: Int)
    }

    /** Who asked, for the voice log: an app's mic button through the recognizer, or the sheet. */
    enum class From { RECOGNIZER, SHEET }

    /** How a request ended, for its owner. */
    enum class End {
        /** Words came back ([Sink.words]). */
        DONE,
        /** Nothing was said before the silence timeout. */
        NO_SPEECH,
        /** Speech, but no words came of it. */
        NO_MATCH,
        /** Robot's own listening took over. */
        TAKEN,
        /** Robot was busy when it would have started. */
        BUSY,
        /** The caller may no longer use the microphone (it left the front): the words so far are dropped. */
        CALLER,
        /** The microphone was busy or stopped. */
        MIC,
        /** Robot's speech model isn't usable. */
        NO_MODEL,
        ERROR,
    }

    /** What a request's owner hears, on the main thread. Nothing after [words] or [ended]. */
    interface Sink {
        fun ready() {}
        fun speechStart() {}
        /** The input level in dBFS. */
        fun level(dbfs: Float) {}
        /** The words so far, when the request asked for them. */
        fun partial(text: String) {}
        fun endOfSpeech() {}
        fun words(text: String)
        /** [message]: speech to text's own words for it, in the menus' language, when it gave some. */
        fun ended(end: End, message: String?)
    }

    class Request internal constructor(val from: From, val app: AppListen, val sink: Sink, internal val callerMay: (() -> Boolean)?) {
        internal var handle: Int? = null
        /** It began listening (the wake word was paused for it). */
        internal var started = false
        /** Over: nothing more reaches [sink]. */
        internal var done = false
        /** Its owner asked it to stop before it began. */
        internal var stopAsked = false
        internal var at = 0L
    }

    private var current: Request? = null

    /** Busy for another request: one is open, or Robot is listening or talking itself. */
    fun busy(): Boolean = current != null || port.robotBusy()

    /**
     * [sink]'s request, checked by its owner, listens once this main-thread message is over: it is posted to the front of
     * the queue, so for the recognizer Android's own check of the caller (which RecognitionService runs after onStartListening
     * returns, in the same message) has passed or cancelled it first, and no audio flows before that. Null when another
     * request is open. [callerMay], when given, is asked again as the request runs: once it says no, the request ends with
     * [End.CALLER] and the words go nowhere.
     */
    fun open(from: From, app: AppListen, sink: Sink, callerMay: (() -> Boolean)? = null): Request? {
        if (current != null) return null
        val r = Request(from, app, sink, callerMay)
        current = r
        port.main.postAtFrontOfQueue { begin(r) }
        return r
    }

    private fun begin(r: Request) {
        // Cancelled first: Android refused the caller, or its owner went.
        if (r.done) return
        // Robot started listening or talking meanwhile.
        if (port.robotBusy()) return finish(r, End.BUSY)
        r.started = true
        r.at = port.now()
        port.pauseWake(true)
        val h = port.stt.startForApp(listener(r), r.app) ?: return finish(r, End.BUSY)
        r.handle = h
        if (r.stopAsked) port.stt.stopForApp(h)
        watch(r)
    }

    /** About once a second, whether audio is coming or not (writing the words down can take seconds with no level). */
    private fun watch(r: Request) {
        port.main.postDelayed({
            if (!r.done && callerStillMay(r)) watch(r)
        }, WATCH_MS)
    }

    /**
     * False when [r] has just ended: its caller has lost the microphone, or Robot started talking, listening or a call
     * (what the microphone hears then is Robot, or someone who didn't ask this app anything).
     */
    private fun callerStillMay(r: Request): Boolean {
        val end = when {
            port.robotBusy() -> End.BUSY
            r.callerMay?.invoke() == false -> End.CALLER
            else -> return true
        }
        r.handle?.let { port.stt.cancelForApp(it) }
        finish(r, end)
        return false
    }

    /** Its owner wants the words heard so far (the app's stopListening, the sheet's Stop). */
    fun stop(r: Request) {
        if (r.done) return
        val h = r.handle
        if (h == null) r.stopAsked = true else port.stt.stopForApp(h)
    }

    /**
     * Its owner ends [r] without a result (the app's cancel, the sheet's Cancel, Android refusing the caller): nothing more
     * reaches its sink.
     */
    fun cancel(r: Request) {
        if (r.done) return
        r.done = true
        if (current === r) current = null
        r.handle?.let { port.stt.cancelForApp(it) }
        if (r.started) port.pauseWake(false)
        log(r, when {
            r.from == From.SHEET -> ListenSheetCode.CANCELLED
            // Cancelled before it began: Android's check of the caller failed, in the message that opened it.
            !r.started -> ListenForAppCode.ANDROID
            else -> ListenForAppCode.CANCELLED
        })
    }

    /** Its owner refused a request before it opened one: the voice log's line. */
    fun refused(from: From, code: LogCode) = port.log(if (from == From.SHEET) VoiceEvent.LISTEN_SHEET else VoiceEvent.LISTEN_FOR_APP, code.id, 0)

    /** Speech to text's callbacks, on its threads: each goes to the main thread, and none after [r] is over. */
    private fun listener(r: Request) = object : SpeechToText.Listener {
        override fun onListening() = post { r.sink.ready() }
        override fun onLevel(db: Float) = post { r.sink.level(db) }
        override fun onSpeechStart() = post { r.sink.speechStart() }
        override fun onPartial(text: String) = post { if (r.app.partials && callerStillMay(r)) r.sink.partial(text) }
        override fun onEndOfSpeech() = post { r.sink.endOfSpeech() }
        override fun onFinal(text: String) = post { if (callerStillMay(r)) { if (text.isBlank()) finish(r, End.NO_MATCH) else finish(r, End.DONE, text) } }
        override fun onError(reason: SpeechToText.Reason, message: String) = post {
            finish(r, when (reason) {
                SpeechToText.Reason.NO_SPEECH -> End.NO_SPEECH
                SpeechToText.Reason.MIC -> End.MIC
                SpeechToText.Reason.UNAVAILABLE -> End.NO_MODEL
                SpeechToText.Reason.ERROR -> End.ERROR
            }, message = message)
        }
        override fun onTakenOver() = post { finish(r, End.TAKEN) }

        private fun post(f: () -> Unit) {
            port.main.post { if (!r.done) f() }
        }
    }

    private fun finish(r: Request, end: End, text: String? = null, message: String? = null) {
        if (r.done) return
        r.done = true
        if (current === r) current = null
        // Taken over: Robot's own question paused the wake word itself and keeps it paused.
        if (r.started && end != End.TAKEN) port.pauseWake(false)
        log(r, code(r.from, end))
        // 1.3: fillers out only; corrections stay as said, since another app may act on the words before you see them.
        if (end == End.DONE && text != null) r.sink.words(app.heyrobot.core.messages.DraftTidy.fillersOnly(text).ifEmpty { text }) else r.sink.ended(end, message)
    }

    private fun log(r: Request, code: LogCode) {
        val tenths = if (r.started) ListenForApps.tenths(port.now() - r.at) else 0
        if (r.from == From.SHEET) port.log(VoiceEvent.LISTEN_SHEET, code.id, 0) else port.log(VoiceEvent.LISTEN_FOR_APP, code.id, tenths)
    }

    private fun code(from: From, end: End): LogCode = if (from == From.SHEET) when (end) {
        End.DONE -> ListenSheetCode.DONE
        End.NO_SPEECH, End.NO_MATCH -> ListenSheetCode.NOTHING
        End.TAKEN -> ListenSheetCode.TAKEN
        End.BUSY -> ListenSheetCode.BUSY
        // The sheet gives no check on its caller: it ends when it isn't in front.
        End.CALLER -> ListenSheetCode.CANCELLED
        End.MIC -> ListenSheetCode.MIC
        End.NO_MODEL -> ListenSheetCode.NO_MODEL
        End.ERROR -> ListenSheetCode.ERROR
    } else when (end) {
        End.DONE -> ListenForAppCode.DONE
        End.NO_SPEECH, End.NO_MATCH -> ListenForAppCode.NOTHING
        End.TAKEN -> ListenForAppCode.TAKEN
        End.BUSY -> ListenForAppCode.BUSY
        End.CALLER -> ListenForAppCode.CALLER
        End.MIC -> ListenForAppCode.MIC
        End.NO_MODEL -> ListenForAppCode.NO_MODEL
        End.ERROR -> ListenForAppCode.ERROR
    }

    companion object {
        /** How often a request's caller is checked again. */
        const val WATCH_MS = 1_000L

        /** :voice's one coordinator, shared by the recognizer and the sheet. */
        val live: AppListening by lazy { AppListening(LivePort) }
    }
}

/** The :voice process's side of [AppListening]: VoiceHub's speech to text and wake word, the panel, conversations, calls. */
private object LivePort : AppListening.Port {
    override val main = Handler(Looper.getMainLooper())
    override val stt: SpeechToText get() = VoiceHub.stt

    override fun robotBusy(): Boolean = PanelController.shown || VoiceHub.robotSpeaking || ConversationSession.running || HandsFreeHub.callActive()

    override fun pauseWake(on: Boolean) {
        val l = VoiceHub.listener ?: return
        // The panel pauses the wake word for its own question and lets it go itself: never let it go while it shows.
        if (on) l.wakePaused = true else if (!PanelController.shown) l.wakePaused = false
    }

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun log(event: VoiceEvent, a: Int, b: Int) = VoiceLog.add(event, a, b)
}
