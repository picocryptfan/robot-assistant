package app.heyrobot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
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

    fun interface Listener {
        /** Called on the main thread when speaking starts or the queue runs empty. */
        fun onSpeakingChanged(speaking: Boolean)
    }
}

class AndroidTextToSpeech(ctx: Context) : TextToSpeech {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
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

    private val tts: android.speech.tts.TextToSpeech = android.speech.tts.TextToSpeech(app) { status ->
        main.post { onInit(status == android.speech.tts.TextToSpeech.SUCCESS) }
    }

    private fun onInit(ok: Boolean) {
        ready = ok
        failed = !ok
        RLog.i("tts_init", "ok" to ok)
        if (!ok) {
            pending.clear()
            setOutstanding(0)
            return
        }
        tts.setAudioAttributes(attrs)
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
                audio.requestAudioFocus(focus)
                listener?.onSpeakingChanged(true)
            }
            if (EchoGuard.soundsLikeStop(sentence)) risky = true
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
        risky = false
        audio.abandonAudioFocusRequest(focus)
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

    override fun setListener(listener: TextToSpeech.Listener?) {
        this.listener = listener
    }

    override fun release() {
        main.post {
            generation++
            try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
            ready = false
            failed = true
        }
    }
}
