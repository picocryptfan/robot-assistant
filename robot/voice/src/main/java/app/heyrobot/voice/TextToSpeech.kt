package app.heyrobot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import app.heyrobot.core.Playback
import app.heyrobot.core.RLog
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// VOICE PROCESS. Text to speech for replies, one sentence at a time while the reply streams (spec item 4):
// the sherpa-onnx voices (Kokoro, Kitten: SherpaTextToSpeech) once downloaded, and [AndroidTextToSpeech]
// (Android's TextToSpeech API, which on GrapheneOS is GrapheneOS Speech Services) until then or when chosen.
// VoiceOutput switches between them; VoiceEngines builds it.

interface TextToSpeech {
    /** Queues one sentence (plain text, no Markdown) after those already queued. */
    fun speak(sentence: String)

    /** Stops speaking and drops the queue. */
    fun stop()

    /** True from the first queued sentence until the last one finished (or [stop]). */
    val isSpeaking: Boolean

    /** Rate 0.5 to 2.0 (1.0 normal) and the voice id ("" = default); applied to the next sentence. */
    fun configure(rate: Float, voice: String)

    fun setListener(listener: Listener?)

    fun release()

    /** Start loading the voice in the background (a spoken reply is likely soon). */
    fun prewarm() {}

    /** Memory is short: free the voice model unless it is speaking. True if something was freed. */
    fun trim(): Boolean = false

    /**
     * A "stop" heard now cannot be Robot's own voice coming back through the microphone (nothing playing says
     * "stop" or a word like it). False when unsure.
     */
    val stopWordSafe: Boolean get() = false

    /**
     * A wake phrase heard now cannot be Robot's own voice (nothing playing says one of its words). True when there is
     * no way to tell, so a wake word during speech still acts.
     */
    val wakeWordSafe: Boolean get() = true

    /** The last words of the enabled wake phrases, which Robot saying would be heard as a wake ([EchoGuard]). */
    fun setWakeWords(words: List<String>) {}

    /** How the voice is played (Settings › Voice); takes effect from the next piece of speech. */
    fun setPlayback(p: Playback) {}

    fun interface Listener {
        /** Called on the main thread when speaking starts or the queue runs empty. */
        fun onSpeakingChanged(speaking: Boolean)
    }
}

class AndroidTextToSpeech(ctx: Context) : TextToSpeech {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    @Volatile private var playback = Playback.ASSISTANT
    /** The playback of the current speaking run (call audio only if the mode could be taken). */
    private var runPlayback = Playback.ASSISTANT
    private var focus = CallAudio.focus(playback)
    /** Checks in a row the engine was quiet although sentences are outstanding ([SpeechWatchdog]). */
    private var quiet = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (outstanding.get() == 0) return
            val busy = ready && try { tts.isSpeaking } catch (_: Throwable) { false }
            quiet = SpeechWatchdog.next(quiet, busy)
            if (SpeechWatchdog.stuck(quiet, ready)) {
                // The engine won't report the rest: end the run, so the voice, the call mode and the capture come back.
                RLog.i("tts_stuck", "ready" to ready)
                generation++
                pending.clear()
                try { if (ready) tts.stop() } catch (_: Throwable) {}
                if (outstanding.getAndSet(0) > 0) idle()
                return
            }
            main.postDelayed(this, SpeechWatchdog.CHECK_MS)
        }
    }
    private val outstanding = AtomicInteger(0)
    private val ids = AtomicLong(0)
    /** Utterances started before the last stop() are ignored by the progress callbacks. */
    @Volatile private var generation = 0L
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var failed = false
    private var listener: TextToSpeech.Listener? = null
    private var rate = 1.0f
    private var voice = ""
    /** A sentence of this speaking run says "stop" or a word like it (no audio positions here, so the whole run counts). */
    @Volatile private var risky = false
    /** ... or a wake phrase's word. */
    @Volatile private var riskyWake = false
    @Volatile private var wakeWords: Regex? = null

    private val tts: android.speech.tts.TextToSpeech = android.speech.tts.TextToSpeech(app) { status ->
        main.post { onInit(status == android.speech.tts.TextToSpeech.SUCCESS) }
    }

    private fun onInit(ok: Boolean) {
        ready = ok
        failed = !ok
        RLog.i("tts_init", "ok" to ok)
        if (!ok) {
            pending.clear()
            // Sentences may be waiting (and the call mode taken): end the run properly.
            if (outstanding.getAndSet(0) > 0) idle()
            return
        }
        tts.setAudioAttributes(CallAudio.attributes(runPlayback))
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        applyConfig()
        while (pending.isNotEmpty()) enqueue(pending.removeFirst())
    }

    private fun finished(id: String?) {
        if (id?.substringBefore(':')?.toLongOrNull() != generation) return
        main.post {
            if (outstanding.get() > 0 && outstanding.decrementAndGet() == 0) idle()
        }
    }

    private fun applyConfig() {
        tts.setSpeechRate(rate)
        if (voice.isNotEmpty()) tts.voices?.firstOrNull { it.name == voice }?.let { tts.voice = it }
    }

    override fun configure(rate: Float, voice: String) {
        main.post {
            this.rate = rate.coerceIn(0.5f, 2.0f)
            this.voice = voice
            if (ready) applyConfig()
        }
    }

    override fun speak(sentence: String) {
        if (sentence.isBlank()) return
        main.post {
            if (failed) return@post
            if (setOutstanding(outstanding.get() + 1) == 1) {
                runPlayback = if (playback == Playback.CALL && CallAudio.begin(app, this)) Playback.CALL else Playback.ASSISTANT
                focus = CallAudio.focus(runPlayback)
                if (ready) tts.setAudioAttributes(CallAudio.attributes(runPlayback))
                audio.requestAudioFocus(focus)
                quiet = 0
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, SpeechWatchdog.CHECK_MS)
                listener?.onSpeakingChanged(true)
            }
            if (EchoGuard.soundsLikeStop(sentence)) risky = true
            if (wakeWords?.containsMatchIn(sentence) == true) riskyWake = true
            if (ready) enqueue(sentence) else pending.addLast(sentence)
        }
    }

    private fun enqueue(sentence: String) {
        val id = "$generation:${ids.incrementAndGet()}"
        val r = tts.speak(sentence, android.speech.tts.TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (r != android.speech.tts.TextToSpeech.SUCCESS) finished(id)
    }

    private fun setOutstanding(n: Int): Int { outstanding.set(n); return n }

    private fun idle() {
        main.removeCallbacks(watchdog)
        risky = false
        riskyWake = false
        audio.abandonAudioFocusRequest(focus)
        CallAudio.end(app, this)
        listener?.onSpeakingChanged(false)
    }

    override fun stop() {
        main.post {
            generation++
            pending.clear()
            if (ready) tts.stop()
            if (outstanding.getAndSet(0) > 0) idle()
        }
    }

    override val isSpeaking: Boolean get() = outstanding.get() > 0

    override val stopWordSafe: Boolean get() = !risky

    override val wakeWordSafe: Boolean get() = !riskyWake

    override fun setWakeWords(words: List<String>) {
        wakeWords = EchoGuard.wakeRegex(words)
    }

    override fun setPlayback(p: Playback) {
        // Used from the next speaking run.
        main.post { playback = p }
    }

    override fun setListener(listener: TextToSpeech.Listener?) {
        this.listener = listener
    }

    override fun release() {
        main.post {
            generation++
            pending.clear()
            if (outstanding.getAndSet(0) > 0) idle()
            try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
            ready = false
            failed = true
        }
    }
}

/**
 * Pure. When the phone's text to speech counts as stuck: sentences are outstanding but the engine has been quiet for
 * [CHECK_MS] × [QUIET_CHECKS] (or longer while it still starts), and it never reported them done.
 */
object SpeechWatchdog {
    const val CHECK_MS = 2_000L
    const val QUIET_CHECKS = 3
    /** The engine may take a while to start the first time. */
    const val STARTING_CHECKS = 8

    fun next(quiet: Int, busy: Boolean): Int = if (busy) 0 else quiet + 1

    fun stuck(quiet: Int, ready: Boolean): Boolean = quiet >= if (ready) QUIET_CHECKS else STARTING_CHECKS
}
