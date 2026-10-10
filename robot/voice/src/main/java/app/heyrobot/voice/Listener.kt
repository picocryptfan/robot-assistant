package app.heyrobot.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Process
import android.os.SystemClock
import app.heyrobot.core.RLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max

// VOICE PROCESS. The always-on microphone loop from the probe (mode A: AudioRecord in the assistant's
// process, no foreground service), with the wake word as its main consumer. Audio is 16 kHz mono PCM
// read in 32 ms frames, kept in memory only and dropped as it is consumed: nothing is written to disk.

/** MediaRecorder.AudioSource ids. */
enum class MicSource(val id: Int) { VOICE_RECOGNITION(6), MIC(1) }

data class ListenerConfig(
    /** Run the wake word (false = capture only, e.g. for speech to text with the wake word off). */
    val wakeWord: Boolean = true,
    /** sherpa-onnx KeywordSpotterConfig defaults, proven on the phone in phase 0. */
    val threshold: Float = 0.25f,
    val boost: Float = 1.5f,
    /** Silero VAD gate in front of the keyword model. */
    val vadGate: Boolean = true,
    val micSource: MicSource = MicSource.VOICE_RECOGNITION,
)

/** A source of microphone frames (16 kHz mono floats, [Listener.FRAME] samples) other engines can tap. */
interface AudioFrames {
    fun addSink(sink: (FloatArray) -> Unit)
    fun removeSink(sink: (FloatArray) -> Unit)

    /** The microphone is open and frames flow. */
    val capturing: Boolean get() = true

    /** The system silenced this capture (another app is recording), or null if unknown. */
    val silenced: Boolean? get() = null

    /**
     * Adds [sink] and returns the audio heard just before it, with no frame lost or repeated in between: what came
     * after the last wake word if it fired less than [sinceWakeMs] ago (the question right after "hey robot"),
     * else the last [otherwiseMs] (someone who starts talking as they press the button). In memory only.
     * [onEnded] runs once if the capture stops while [sink] is still attached (no more frames will come).
     */
    fun attach(sink: (FloatArray) -> Unit, sinceWakeMs: Int, otherwiseMs: Int, onEnded: () -> Unit = {}): FloatArray {
        addSink(sink)
        return FloatArray(0)
    }
}

class Listener(
    private val ctx: Context,
    val config: ListenerConfig,
    /** Present while the wake-word model loads; left behind if a native crash kills the process in it. */
    private val initMarker: File?,
    private val onWake: (String) -> Unit,
) : AudioFrames {
    @Volatile var running = false; private set
    @Volatile var levelDb = -120.0; private set
    /** The system silenced this capture (another app is recording), or null if unknown. */
    @Volatile override var silenced: Boolean? = null; private set
    @Volatile var wakeWordLoaded = false; private set
    /** Wake detection paused (the panel is listening for the question); capture goes on for the sinks. */
    @Volatile var wakePaused = false
    /** Robot is speaking: also listen for "STOP" (WakeWord), so a spoken "stop" can end the reply. */
    @Volatile var stopWord = false

    private val sinks = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    /** What to tell attached sinks when the capture ends ([attach]). */
    private val ends = ConcurrentHashMap<(FloatArray) -> Unit, () -> Unit>()
    /** The last [PreRoll.RING_MS] of audio and where the last wake word fired, for [attach] (guarded by [ringLock]). */
    private val ringLock = Any()
    private val recent = FloatRing(SAMPLE_RATE * PreRoll.RING_MS / 1000)
    private var samplesSeen = 0L
    private var wakeAt = Long.MIN_VALUE / 2
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val callbackExec = Executors.newSingleThreadExecutor()

    override fun addSink(sink: (FloatArray) -> Unit) { sinks.addIfAbsent(sink) }
    override fun removeSink(sink: (FloatArray) -> Unit) {
        sinks.remove(sink)
        ends.remove(sink)
    }

    override val capturing: Boolean get() = running

    override fun attach(sink: (FloatArray) -> Unit, sinceWakeMs: Int, otherwiseMs: Int, onEnded: () -> Unit): FloatArray {
        val pre = synchronized(ringLock) {
            sinks.addIfAbsent(sink)
            ends[sink] = onEnded
            recent.last(PreRoll.samples(samplesSeen - wakeAt, sinceWakeMs, otherwiseMs))
        }
        // Stopped just before: no frame will come, so say so now.
        if (!running) endSinks()
        return pre
    }

    /** The capture ended (stopped, or the microphone died): attached sinks get nothing more, and are told once. */
    private fun endSinks() {
        val gone = ends.keys.toList()
        for (k in gone) {
            val cb = ends.remove(k) ?: continue
            sinks.remove(k)
            try { cb() } catch (t: Throwable) { RLog.w("audio_sink_end", t) }
        }
    }

    /** Opens the microphone (the configured source, MIC if that fails) and starts the loop. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return fail("no_permission")
        var r: AudioRecord? = null
        for (src in listOf(config.micSource, MicSource.MIC).distinct()) {
            r = open(src)
            if (r != null) break
        }
        val record = r ?: return fail("open")
        try {
            record.startRecording()
        } catch (t: Throwable) {
            record.release()
            return fail("start")
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return fail("state")
        }
        record.registerAudioRecordingCallback(callbackExec, object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val now = configs?.firstOrNull { it.clientAudioSessionId == record.audioSessionId }?.isClientSilenced ?: return
                if (now != silenced) {
                    silenced = now
                    RLog.i("mic_silenced", "value" to now)
                }
            }
        })
        silenced = record.activeRecordingConfiguration?.isClientSilenced
        rec = record
        running = true
        thread = Thread({ loop(record) }, "robot-listen").also { it.start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun open(src: MicSource): AudioRecord? = try {
        val fmt = AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 2 s of buffer: the keyword decode runs on the read thread and must not lose audio while it does.
        val r = AudioRecord.Builder().setContext(ctx).setAudioSource(src.id).setAudioFormat(fmt)
            .setBufferSizeInBytes(max(min * 2, SAMPLE_RATE * 2 * 2)).build()
        if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    } catch (_: Throwable) {
        null
    }

    private fun fail(stage: String): Boolean {
        RLog.i("listen_start", "ok" to false, "stage" to RLog.Code(stage))
        return false
    }

    private fun loop(r: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var ww: WakeWord? = null
        if (config.wakeWord) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                // Not beside a speech model's load, so a crash here is not blamed on that model (and the reverse).
                ww = NativeLoads.one("kws") {
                    initMarker?.writeText("${Process.myPid()}")
                    try { WakeWord(ctx.assets, config) } finally { initMarker?.delete() }
                }
            } catch (t: Throwable) {
                RLog.w("wake_word_init", t)
            }
            RLog.i("listen_start", "ok" to true, "wakeWord" to (ww != null), "initMs" to SystemClock.elapsedRealtime() - t0)
        }
        wakeWordLoaded = ww != null
        val buf = ShortArray(FRAME)
        while (running) {
            val n = try { r.read(buf, 0, FRAME) } catch (_: Throwable) { AudioRecord.ERROR }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                    RLog.i("listen_error", "dead" to true)
                    running = false
                    break
                }
                Thread.sleep(20)
                continue
            }
            val x = FloatArray(n)
            var ss = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768f
                x[i] = v
                ss += v * v
            }
            levelDb = 0.8 * levelDb + 0.2 * db(ss / n)
            // A sink attached after this block gets this frame in its pre-roll, one attached before gets it here.
            val targets = synchronized(ringLock) {
                recent.push(x)
                samplesSeen += n
                sinks.toTypedArray()
            }
            for (s in targets) try { s(x) } catch (t: Throwable) { RLog.w("audio_sink", t) }
            val w = ww ?: continue
            if (wakePaused) continue
            val hit = try { w.accept(x, stopWord) } catch (t: Throwable) {
                // Java-side errors only; a native crash takes the whole voice process.
                RLog.w("wake_word", t)
                null
            }
            if (hit != null) {
                // Only "hey robot" marks where a question starts; "stop" is followed by Robot's own voice.
                if (PreRoll.marksQuestion(hit)) synchronized(ringLock) { wakeAt = samplesSeen }
                onWake(hit)
            }
        }
        ww?.release()
        wakeWordLoaded = false
        endSinks()
    }

    fun stop() {
        if (!running && rec == null) return
        running = false
        rec?.let { r ->
            try { r.stop() } catch (_: Throwable) {}
            thread?.join(2_000)
            try { r.release() } catch (_: Throwable) {}
        }
        rec = null
        // The loop tells them as it exits; also here in case it is still stuck in a read.
        endSinks()
        callbackExec.shutdown()
        RLog.i("listen_stop")
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 32 ms at 16 kHz: also the Silero VAD window. */
        const val FRAME = 512

        fun db(meanSquare: Double): Double = if (meanSquare <= 1e-12) -120.0 else 10 * log10(meanSquare)
    }
}
