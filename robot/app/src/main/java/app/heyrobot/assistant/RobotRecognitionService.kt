// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.Manifest
import android.app.AppOpsManager
import android.content.AttributionSource
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.assistant.listen.AppListening
import app.heyrobot.core.RLog
import app.heyrobot.core.everyday.ListenFacts
import app.heyrobot.core.everyday.ListenForAppCode
import app.heyrobot.core.everyday.ListenForApps
import app.heyrobot.core.everyday.ListenRefusal
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.voice.AppListen

// VOICE PROCESS. Named by the voice-interaction metadata (android:recognitionService) and discoverable (see the manifest),
// so the system keeps Robot as the assistant across restarts: its name never changes, and its component is never disabled
// (with no selectable recognizer Android clears a third-party assistant at the next boot). While Robot is the assistant it
// is the phone's speech recognizer.
//
// The everyday set (docs/plans/everyday-set-plan.md §6.2, D13 and D14, E5): it listens for other apps' mic buttons with
// Robot's offline speech model, for an app in front that holds the microphone permission, and gives back only the text of
// what was said after it asked. Android's RecognitionService (AOSP, read for E5) checks the caller twice: a preflight
// before onStartListening that passes a background app with "while in use" access (it reads the app op's raw mode), and,
// after onStartListening returns, a proxied start of the caller's microphone use (PermissionChecker's
// checkPermissionAndStartDataDelivery: Robot, then the caller) that does look at whether the caller is in front, charges
// the microphone to it until the request ends, and cancels the request if it fails. Robot adds its own checks before any
// of that matters, in core ListenForApps' order: the switch (read here, never by disabling the component), Robot being the
// running assistant, no audio brought by the caller, English or French, Robot's own microphone op, then the caller (it
// holds the permission and its app op, noted through Robot, allows it now), the model, and busy. A request that passes
// listens on Robot's own capture (AppListening), from the next main-thread message, so Android's own check has run first.
// Nothing is kept: no audio, no transcript, no History; the logs get codes.

class RobotRecognitionService : RecognitionService() {
    private val listening: AppListening get() = AppListening.live
    /** Each of Android's callbacks and its request (Android keeps one session at a time). Main thread. */
    private val requests = HashMap<Callback, AppListening.Request>()

    override fun onStartListening(intent: Intent?, listener: Callback) {
        val ctx = applicationContext
        val asked = Asked.of(intent)
        val caller = listener.callingAttributionSource
        val why = ListenForApps.decide(ListenFacts(
            on = EverydaySettingsStore.current(ctx).listen,
            assistant = RobotVoiceService.instance != null,
            ownAudio = asked.ownAudio,
            language = asked.language,
            robotMic = { Mic.robotMay(ctx) },
            caller = { Mic.callerMay(ctx, caller) },
            modelReady = { VoiceHub.stt.available },
            busy = { listening.busy() },
        ))
        if (why != null) return refuse(listener, why)
        val s = VoiceHub.settings
        val app = AppListen(ListenForApps.pauseMs(asked.pauseMs, s.listenPause.ms), partials = asked.partials && s.livePartials)
        val r = listening.open(AppListening.From.RECOGNIZER, app, sink(listener), callerMay = { Mic.callerStillMay(ctx, caller) }) ?: return refuse(listener, ListenRefusal.BUSY)
        requests[listener] = r
    }

    override fun onStopListening(listener: Callback) {
        requests[listener]?.let(listening::stop)
    }

    /** The app cancelled, or Android's own check of the caller failed (then before the request began). */
    override fun onCancel(listener: Callback) {
        requests.remove(listener)?.let(listening::cancel)
    }

    override fun onDestroy() {
        requests.values.forEach(listening::cancel)
        requests.clear()
        super.onDestroy()
    }

    /**
     * Refuses [why] from inside onStartListening, before it returns (only there). First the caller's attribution is marked as
     * handled (createContext with the caller as next: AOSP's RecognitionService then skips its own start of the caller's
     * microphone use), so Android neither starts and at once finishes the caller's microphone use for a request Robot never
     * listened for (the microphone indicator would name the app) nor refuses it a second time. INFERRED from AOSP's source
     * (RecognitionService.createContext and checkPermissionAndStartDataDelivery); SE2 checks it on the phone. Never on the way
     * to listening: there, Android's own start is what charges the microphone to the app.
     */
    private fun refuse(listener: Callback, why: ListenRefusal) {
        try {
            createContext(ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build())
        } catch (t: Exception) {
            RLog.w("recognizer_refuse", t)
        }
        listening.refused(AppListening.From.RECOGNIZER, ListenForAppCode.of(why))
        RLog.i("recognizer", "op" to RLog.Code("refused"), "why" to RLog.Code(why.name.lowercase()))
        try { listener.error(codeFor(why)) } catch (_: Exception) {}
    }

    /** What the app hears, through Android's callback: never audio (no bufferReceived), only the text at the end. */
    private fun sink(listener: Callback) = object : AppListening.Sink {
        override fun ready() = send(listener) { readyForSpeech(Bundle()) }
        override fun speechStart() = send(listener) { beginningOfSpeech() }
        override fun level(dbfs: Float) = send(listener) { rmsChanged(ListenForApps.rmsDb(dbfs)) }
        override fun partial(text: String) = send(listener) { partialResults(textBundle(text)) }
        override fun endOfSpeech() = send(listener) { endOfSpeech() }
        override fun words(text: String) {
            requests.remove(listener)
            send(listener) { results(textBundle(text)) }
        }
        override fun ended(end: AppListening.End, message: String?) {
            requests.remove(listener)
            send(listener) { error(codeFor(end)) }
        }
    }

    /** One call to the app. If it has gone (its process died), its request ends: Robot never listens for nobody. */
    private fun send(listener: Callback, call: Callback.() -> Unit) {
        try {
            listener.call()
        } catch (_: Exception) {
            requests.remove(listener)?.let(listening::cancel)
            // Android forgets the session only when told an end, and resets it before the call that can fail: without this,
            // every later request would hear "busy" for as long as this service lives.
            try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
        }
    }

    private fun textBundle(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

    /** What a request brought, read once; a request whose extras can't be read is treated as bringing its own audio (refused). */
    private class Asked(val ownAudio: Boolean, val language: String?, val pauseMs: Long?, val partials: Boolean) {
        companion object {
            fun of(i: Intent?): Asked = try {
                val x = i?.extras
                val own = x?.containsKey(RecognizerIntent.EXTRA_AUDIO_SOURCE) == true
                // Robot doesn't read audio a caller brings (D14); its descriptor is closed here, not left open in this process.
                if (own) try { i?.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor::class.java)?.close() } catch (_: Exception) {}
                @Suppress("DEPRECATION")
                val pause = (x?.get(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS) as? Number)?.toLong()
                Asked(own, x?.getString(RecognizerIntent.EXTRA_LANGUAGE), pause, x?.getBoolean(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) == true)
            } catch (_: Exception) {
                Asked(ownAudio = true, language = null, pauseMs = null, partials = false)
            }
        }
    }

    /** Robot's own check of the microphone (D14), beside Android's. */
    private object Mic {
        /** Robot itself may use the microphone now: its own app op, which Android's proxied start for the caller needs too. Noted nowhere. */
        fun robotMay(ctx: Context): Boolean = try {
            ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), ctx.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }

        /** F60: during someone else's lock, only an app that lock allows may have Robot listen, as ListenSheetActivity's refusal. */
        private fun lockRefuses(ctx: Context, pkg: String): Boolean = PhoneLockNow.someoneElse(ctx) && pkg !in PhoneLockNow.apps(ctx)

        /**
         * The caller holds the microphone permission, and its app op lets it use the microphone now: for the microphone, it's
         * in front (an app on screen, or a foreground service that may record itself). The use is noted for the caller
         * through Robot (noteProxyOpNoThrow), which also refuses a package that isn't the caller's uid's. Android's own
         * proxied start follows if Robot listens, so the access may show twice in the privacy history.
         */
        fun callerMay(ctx: Context, c: AttributionSource): Boolean {
            val pkg = c.packageName ?: return false
            if (lockRefuses(ctx, pkg)) return false
            if (ctx.checkPermission(Manifest.permission.RECORD_AUDIO, -1, c.uid) != PackageManager.PERMISSION_GRANTED) return false
            return try {
                ctx.getSystemService(AppOpsManager::class.java).noteProxyOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, pkg, c.uid, c.attributionTag, null) ==
                    AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }

        /**
         * The same question as the request runs (E10): Android stops a background caller's own recording, not Robot's, so
         * nothing else ends a request whose app has left the front. Reads the op without noting it, or each check would
         * show in the privacy history. It evaluates the mode as noteProxyOp does (not the raw one), so a caller that passed
         * passes until it changes. Android holds an app's state for a few seconds after it leaves the front (AppOps' settle
         * time, 5 s out of top), so a request can run that long past it. Fail closed: an error ends the request.
         */
        fun callerStillMay(ctx: Context, c: AttributionSource): Boolean {
            val pkg = c.packageName ?: return false
            return try {
                !lockRefuses(ctx, pkg) && ctx.checkPermission(Manifest.permission.RECORD_AUDIO, -1, c.uid) == PackageManager.PERMISSION_GRANTED &&
                    ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, c.uid, pkg) == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        /**
         * What the app hears for a refusal. Off and not the assistant: unavailable, as before the everyday set; a caller not
         * in front or without the permission: insufficient permissions, as Android's own check says.
         */
        fun codeFor(why: ListenRefusal): Int = when (why) {
            ListenRefusal.OFF, ListenRefusal.NOT_ASSISTANT, ListenRefusal.NO_MODEL -> SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            ListenRefusal.OWN_AUDIO -> SpeechRecognizer.ERROR_CLIENT
            ListenRefusal.LANGUAGE -> SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            ListenRefusal.ROBOT_MIC -> SpeechRecognizer.ERROR_AUDIO
            ListenRefusal.CALLER -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            ListenRefusal.BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        }

        /** What the app hears for a request that ended without words. */
        fun codeFor(end: AppListening.End): Int = when (end) {
            AppListening.End.NO_SPEECH -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            AppListening.End.NO_MATCH, AppListening.End.DONE -> SpeechRecognizer.ERROR_NO_MATCH
            AppListening.End.TAKEN, AppListening.End.BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            AppListening.End.MIC -> SpeechRecognizer.ERROR_AUDIO
            AppListening.End.CALLER -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            AppListening.End.NO_MODEL -> SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            AppListening.End.ERROR -> SpeechRecognizer.ERROR_SERVER
        }
    }
}
