package app.heyrobot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.heyrobot.core.RLog
import app.heyrobot.core.TtsBench
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// VOICE PROCESS ONLY, :voice module only (sherpa-onnx). Replies read aloud by Kokoro v1.0 fp32 (af_heart, or
// ff_siwis for French later) or Kitten nano 0.8 fp32 (docs/voice-plan.md 4.8-4.9). Pieces of text are queued as
// the reply streams (the panel's SentenceSplitter makes the first one short); one "robot-tts" thread synthesises
// them in order, sentence by sentence (sherpa calls back once per sentence), and writes the audio into a
// streaming AudioTrack (assistant usage, speech content) while the previous piece plays. stop() pauses and
// flushes the track at once and drops the queue; isSpeaking stays true until the last sample has played.

class SherpaTextToSpeech internal constructor(
    private val ctx: Context,
    private val kokoro: ModelSlot<OfflineTts>,
    private val kitten: ModelSlot<OfflineTts>,
) : TextToSpeech {
    private class Item(val text: String, val gen: Int)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-tts") }
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    /** Guards the queue, [speaking], [pumping] and the run statistics. Taken before [trackLock], never after. */
    private val stateLock = Any()
    private val queue = ArrayDeque<Item>()
    private var speaking = false
    private var pumping = false
    @Volatile private var gen = 0

    /** Guards [track] and [written]; held only for short, non-blocking calls. */
    private val trackLock = Any()
    private var track: AudioTrack? = null
    /** Frames written since the last flush. */
    private var written = 0L
    private val echo = EchoGuard(SAMPLE_RATE)

    @Volatile private var rate = 1.0f
    @Volatile private var choice = VoiceChoice.DEFAULT
    private var listener: TextToSpeech.Listener? = null

    /** The voice could not be loaded: these pieces should be spoken another way (VoiceOutput uses the phone's voice). */
    internal var onUnavailable: ((List<String>) -> Unit)? = null

    // One speaking run, for the log (numbers only).
    private var runStart = 0L
    private var firstAudioMs = -1L
    private var runPieces = 0
    private var runFrames = 0L
    private var runSynthMs = 0L

    private fun slot(b: VoiceBundle?): ModelSlot<OfflineTts>? = when (b) {
        VoiceBundle.KOKORO -> kokoro
        VoiceBundle.KITTEN -> kitten
        else -> null
    }

    /** Can [c] be spoken by this engine now (its model downloaded, verified, not broken)? */
    internal fun usable(c: VoiceChoice): Boolean = slot(c.bundle)?.let { it.refresh(); it.state.usable } == true

    override fun configure(rate: Float, voice: String) {
        this.rate = rate.coerceIn(0.5f, 2.0f)
        choice = VoiceChoice.of(voice)
    }

    override fun setListener(listener: TextToSpeech.Listener?) {
        this.listener = listener
    }

    override val isSpeaking: Boolean get() = synchronized(stateLock) { speaking }

    override fun speak(sentence: String) {
        val text = sentence.trim()
        if (text.isEmpty()) return
        synchronized(stateLock) {
            queue.addLast(Item(text, gen))
            if (!speaking) {
                speaking = true
                runStart = SystemClock.elapsedRealtime()
                firstAudioMs = -1
                runPieces = 0
                runFrames = 0
                runSynthMs = 0
                changed(true)
            }
            if (!pumping) {
                pumping = true
                worker.execute(::pump)
            }
        }
    }

    override fun stop() {
        synchronized(stateLock) {
            gen++
            queue.clear()
            synchronized(trackLock) {
                track?.let {
                    try {
                        it.pause()
                        it.flush()
                    } catch (_: Throwable) {}
                }
                written = 0
                echo.reset()
            }
            if (speaking) {
                speaking = false
                logRun(stopped = true)
                changed(false)
            }
        }
    }

    /** Called with [stateLock] held. */
    private fun changed(on: Boolean) {
        try {
            if (on) audio.requestAudioFocus(focus) else audio.abandonAudioFocusRequest(focus)
        } catch (_: Throwable) {}
        main.post { listener?.onSpeakingChanged(on) }
    }

    /** Worker: speak queued pieces, then wait for the audio to play out (or for more text, or stop()). */
    private fun pump() {
        var lastHead = -1L
        var stuckSince = 0L
        while (true) {
            val item = synchronized(stateLock) { queue.removeFirstOrNull() }
            if (item != null) {
                if (item.gen == gen) synth(item)
                lastHead = -1
                continue
            }
            val done = synchronized(stateLock) {
                when {
                    queue.isNotEmpty() -> false
                    !speaking -> { pumping = false; true }
                    else -> {
                        val head = synchronized(trackLock) { headLocked() }
                        // A track that stops moving (a routing change, a tiny tail under its start threshold) must
                        // not leave Robot "speaking" for ever.
                        val now = SystemClock.elapsedRealtime()
                        if (head != lastHead) { lastHead = head; stuckSince = now }
                        if (playedOutLocked() || now - stuckSince > STUCK_MS) {
                            synchronized(trackLock) { resetTrackLocked() }
                            speaking = false
                            pumping = false
                            logRun(stopped = false)
                            changed(false)
                            true
                        } else false
                    }
                }
            }
            if (done) return
            Thread.sleep(25)
        }
    }

    private fun headLocked(): Long = track?.let { it.playbackHeadPosition.toLong() and 0xFFFFFFFFL } ?: 0L

    private fun playedOutLocked(): Boolean = synchronized(trackLock) { written == 0L || headLocked() >= written }

    private fun resetTrackLocked() {
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Throwable) {}
        }
        written = 0
        echo.reset()
    }

    /** Worker: one queued piece. */
    private fun synth(item: Item) {
        val c = choice
        val s = slot(c.bundle)
        val tts = s?.get { create(c.bundle!!, it) }
        if (tts == null) {
            // Missing, broken or failed to load: hand this run to the phone's voice.
            val rest = synchronized(stateLock) {
                val r = listOf(item.text) + queue.filter { it.gen == item.gen }.map { it.text }
                queue.clear()
                // Quietly: VoiceOutput carries on with the phone's voice, so the panel stays "speaking".
                speaking = false
                try { audio.abandonAudioFocusRequest(focus) } catch (_: Throwable) {}
                r
            }
            RLog.i("tts_fallback", "voice" to RLog.Code(c.id), "state" to s?.state)
            onUnavailable?.invoke(rest)
            return
        }
        // Only the chosen voice stays loaded.
        (if (c.bundle == VoiceBundle.KOKORO) kitten else kokoro).release()
        val t = ensureTrack(tts.sampleRate()) ?: return
        val t0 = SystemClock.elapsedRealtime()
        val start = synchronized(trackLock) { written }
        var frames = 0L
        try {
            tts.generateWithConfigAndCallback(item.text, generation(c)) { samples ->
                if (frames == 0L) synchronized(stateLock) {
                    if (firstAudioMs < 0 && item.gen == gen) firstAudioMs = SystemClock.elapsedRealtime() - runStart
                }
                frames += samples.size
                if (write(t, samples, item.gen)) 1 else 0
            }
        } catch (t2: Throwable) {
            RLog.w("tts_generate", t2)
        }
        val end = synchronized(trackLock) { if (gen == item.gen) written else -1L }
        if (end > start) echo.onPiece(start, end, item.text)
        synchronized(stateLock) {
            runPieces++
            runFrames += frames
            runSynthMs += SystemClock.elapsedRealtime() - t0
        }
    }

    private fun generation(c: VoiceChoice) = GenerationConfig(
        sid = c.speaker,
        speed = rate,
        // Kokoro reads French with espeak's "fr" when asked per call; English uses the model's "en-us".
        extra = c.lang?.let { mapOf("lang" to it) },
    )

    /** Writes [samples] without blocking under the lock; false once a stop() made them stale. */
    private fun write(t: AudioTrack, samples: FloatArray, itemGen: Int): Boolean {
        var off = 0
        while (off < samples.size) {
            val n = synchronized(trackLock) {
                if (gen != itemGen) return false
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                val w = t.write(samples, off, samples.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (w > 0) written += w
                w
            }
            if (n < 0) return false
            off += n
            // The buffer holds several seconds: synthesis this far ahead of playback can wait.
            if (n == 0) Thread.sleep(20)
        }
        return true
    }

    private fun ensureTrack(rate: Int): AudioTrack? = synchronized(trackLock) {
        track?.let { if (it.sampleRate == rate) return it }
        track?.release()
        track = null
        written = 0
        try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, rate * 4 * BUFFER_S))
                .build()
            // Start playing after 0.1 s of audio, not once the whole multi-second buffer is full.
            try { t.setStartThresholdInFrames(rate / 10) } catch (_: Throwable) {}
            track = t
            t
        } catch (e: Throwable) {
            RLog.w("tts_track", e)
            null
        }
    }

    /** sherpa-onnx OfflineTts for [b] from its verified directory, with the espeak-ng data copied first. */
    private fun create(b: VoiceBundle, dir: File): OfflineTts {
        val data = EspeakData.ensure(ctx) ?: throw IllegalStateException("espeak-ng data")
        // If espeak cannot initialise, sherpa exits the process: never get there.
        check(File(data, "phontab").isFile && File(data, "phondata").isFile)
        for (f in b.files) require(File(dir, f.name).length() == f.bytes) { "size" }
        val p = dir.absolutePath
        val model = when (b) {
            // fp32, not int8 (3x slower on the CPU path); lang is required for v1.0 (or the process exits), and
            // no lexicon: English always goes through espeak in 1.13.8.
            VoiceBundle.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(model = "$p/model.onnx", voices = "$p/voices.bin", tokens = "$p/tokens.txt", dataDir = data, lang = "en-us"),
                numThreads = THREADS, debug = false, provider = "cpu",
            )
            VoiceBundle.KITTEN -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(model = "$p/model.fp32.onnx", voices = "$p/voices.bin", tokens = "$p/tokens.txt", dataDir = data),
                numThreads = THREADS, debug = false, provider = "cpu",
            )
            VoiceBundle.STT -> throw IllegalArgumentException("not a voice")
        }
        // debug = false above: with it on, sherpa logs the text being spoken.
        return OfflineTts(null, OfflineTtsConfig(model = model, maxNumSentences = 1, silenceScale = 0.2f))
    }

    /** Called with [stateLock] held. */
    private fun logRun(stopped: Boolean) {
        RLog.i("tts_run", "voice" to RLog.Code(choice.id), "pieces" to runPieces, "firstAudioMs" to firstAudioMs,
            "audioMs" to runFrames * 1000 / SAMPLE_RATE, "synthMs" to runSynthMs, "stopped" to stopped)
    }

    override val stopWordSafe: Boolean
        get() = synchronized(trackLock) { !echo.mayBeEcho(headLocked()) }

    override fun prewarm() {
        val c = choice
        val s = slot(c.bundle) ?: return
        if (s.value != null || !usable(c)) return
        worker.execute { s.get { create(c.bundle!!, it) } }
    }

    /** Frees the voices (they load again on next use). */
    internal fun unload() {
        worker.execute {
            kokoro.release()
            kitten.release()
        }
    }

    override fun trim(): Boolean {
        if (isSpeaking || (kokoro.value == null && kitten.value == null)) return false
        unload()
        return true
    }

    override fun release() {
        stop()
        worker.execute {
            kokoro.release()
            kitten.release()
            synchronized(trackLock) {
                track?.release()
                track = null
            }
        }
    }

    /**
     * "Test voice speed" (benchmark thread): load [c] afresh, then time to first audio of the 5-word first chunk
     * (after one warm-up) and of the 16-word sentence, and that sentence's real-time factor. Nothing is played.
     */
    internal fun bench(c: VoiceChoice, firstChunk: String, sentence: String): TtsBench? = worker.submit(Callable {
        if (isSpeaking) return@Callable null
        val s = slot(c.bundle) ?: return@Callable null
        s.release()
        val t0 = SystemClock.elapsedRealtime()
        val tts = s.get { create(c.bundle!!, it) } ?: return@Callable null
        val loadMs = SystemClock.elapsedRealtime() - t0
        fun run(text: String): Triple<Long, Long, Long> {
            val start = SystemClock.elapsedRealtime()
            var first = -1L
            var frames = 0L
            tts.generateWithConfigAndCallback(text, GenerationConfig(sid = c.speaker, speed = 1.0f, extra = c.lang?.let { mapOf("lang" to it) })) { x ->
                if (first < 0) first = SystemClock.elapsedRealtime() - start
                frames += x.size
                1
            }
            return Triple(first, SystemClock.elapsedRealtime() - start, frames * 1000 / tts.sampleRate())
        }
        run(firstChunk)
        val (firstChunkMs, _, _) = run(firstChunk)
        val (sentenceFirst, genMs, audioMs) = run(sentence)
        // Keep only the chosen voice loaded.
        if (c.bundle != choice.bundle) s.release()
        TtsBench(c, loadMs, firstChunkMs, sentenceFirst, audioMs, genMs)
    }).get()

    companion object {
        /** Kokoro and Kitten both produce 24 kHz. */
        const val SAMPLE_RATE = 24_000
        const val THREADS = 4
        /** Seconds of audio the track buffers ahead of playback. */
        private const val BUFFER_S = 6
        private const val STUCK_MS = 2_500L
    }
}

/**
 * The TextToSpeech the assistant uses: the chosen sherpa-onnx voice when its model is ready, the phone's own
 * voice (AndroidTextToSpeech, made only when needed) when the model is missing, broken or failed, or when the
 * person picked it. The engine is chosen when a speaking run starts, so a reply never switches voices midway
 * unless the model fails to load.
 */
class VoiceOutput internal constructor(private val ctx: Context, private val sherpa: SherpaTextToSpeech) : TextToSpeech {
    private val main = Handler(Looper.getMainLooper())
    private var system: AndroidTextToSpeech? = null
    @Volatile private var active: TextToSpeech? = null
    @Volatile private var choice = VoiceChoice.DEFAULT
    @Volatile private var rate = 1.0f
    private var listener: TextToSpeech.Listener? = null
    /** What the panel was last told (main thread). */
    private var reported = false

    init {
        sherpa.setListener { changed() }
        sherpa.onUnavailable = { texts ->
            main.post {
                val s = systemTts()
                active = s
                texts.forEach(s::speak)
                changed()
            }
        }
    }

    private fun systemTts(): AndroidTextToSpeech = system ?: AndroidTextToSpeech(ctx).also {
        it.configure(rate, "")
        it.setListener { changed() }
        system = it
    }

    /** Main thread: tell the panel when the combined state changes. */
    private fun changed() {
        val now = isSpeaking
        if (now == reported) return
        reported = now
        if (!now) active = null
        listener?.onSpeakingChanged(now)
    }

    private fun pick(): TextToSpeech = if (choice != VoiceChoice.SYSTEM && sherpa.usable(choice)) sherpa else systemTts()

    override fun speak(sentence: String) {
        if (sentence.isBlank()) return
        val t = active ?: pick().also { active = it }
        t.speak(sentence)
    }

    override fun stop() {
        sherpa.stop()
        system?.stop()
        active = null
    }

    override val isSpeaking: Boolean get() = sherpa.isSpeaking || system?.isSpeaking == true

    override fun configure(rate: Float, voice: String) {
        this.rate = rate
        choice = VoiceChoice.of(voice)
        sherpa.configure(rate, voice)
        system?.configure(rate, "")
    }

    override fun setListener(listener: TextToSpeech.Listener?) {
        this.listener = listener
    }

    override fun release() {
        sherpa.release()
        system?.release()
    }

    override fun prewarm() {
        if (choice != VoiceChoice.SYSTEM) sherpa.prewarm()
    }

    override fun trim(): Boolean = !isSpeaking && sherpa.trim()

    override val stopWordSafe: Boolean
        get() = when (val a = active) {
            null -> false
            else -> a.stopWordSafe
        }
}
