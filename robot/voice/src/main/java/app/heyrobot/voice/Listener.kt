package app.heyrobot.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import app.heyrobot.core.AecState
import app.heyrobot.core.CaptureFx
import app.heyrobot.core.KeywordBuffer
import app.heyrobot.core.KwsThresholds
import app.heyrobot.core.ListenWhy
import app.heyrobot.core.MicKind
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max

// VOICE PROCESS. The always-on microphone loop from the probe (mode A: AudioRecord in the assistant's
// process, no foreground service), with the wake word as its main consumer. Audio is 16 kHz mono PCM
// read in 32 ms frames, kept in memory only and dropped as it is consumed: nothing is written to disk.
//
// 0.2.3: while Robot speaks the capture can move to the call mode (VOICE_COMMUNICATION) with the platform's echo
// canceller and noise suppressor on its session, so "stop" and the wake word are heard over her voice; it comes back
// to speech recognition after (CapturePolicy decides, never mid-question). The new AudioRecord has the same format,
// the ring and sample counts simply go on, and the keyword stream starts afresh after the few-ms gap. The wake-word
// model loads on a thread of its own, so audio flows to a question even while another model's load holds it up.
//
// 0.3.1: the voice log gets every keyword the spotter fires, before any check (KWS_HIT), what each capture really
// got (CAPTURE_INFO) and, for each stretch of Robot talking, what the microphone heard of her (SPEAK_RUN).

/** MediaRecorder.AudioSource ids. */
enum class MicSource(val id: Int) { VOICE_RECOGNITION(6), MIC(1), VOICE_COMMUNICATION(7) }

data class ListenerConfig(
    /** Run the wake word (false = capture only, e.g. for speech to text with the wake word off). */
    val wakeWord: Boolean = true,
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
    /** The wake lines and whether Robot speaks, read on every frame. */
    private val inputs: KeywordInputs = KeywordInputs(),
    private val onWake: (String) -> Unit,
) : AudioFrames {
    @Volatile var running = false; private set
    @Volatile var levelDb = -120.0; private set
    /** The system silenced this capture (another app is recording), or null if unknown. */
    @Volatile override var silenced: Boolean? = null; private set
    @Volatile var wakeWordLoaded = false; private set
    /** Wake detection paused (the panel is listening for the question); capture goes on for the sinks. */
    @Volatile var wakePaused = false
    /** Wanted: the call mode with echo cancellation (CapturePolicy); applied between questions. */
    @Volatile var wantCommunication = false
    /** What the capture uses now. */
    @Volatile var communication = false; private set
    @Volatile var aec: AecState = AecState.OFF; private set
    /** When the last frame was read (elapsedRealtime): a capture that stalls without an error is restarted. */
    @Volatile var lastFrameAt = 0L; private set

    private val sinks = CopyOnWriteArrayList<(FloatArray) -> Unit>()
    /** What to tell attached sinks when the capture ends ([attach]). */
    private val ends = ConcurrentHashMap<(FloatArray) -> Unit, () -> Unit>()
    /** The last [PreRoll.RING_MS] of audio and where the last wake word fired, for [attach] (guarded by [ringLock]). */
    private val ringLock = Any()
    private val recent = FloatRing(SAMPLE_RATE * PreRoll.RING_MS / 1000)
    private var samplesSeen = 0L
    private var wakeAt = Long.MIN_VALUE / 2
    /** Guards [rec], its effects and its callback: the loop swaps them, [stop] closes them. */
    private val recLock = Any()
    private var rec: AudioRecord? = null
    private var recCallback: AudioManager.AudioRecordingCallback? = null
    /** Set (under [ringLock]) while the capture switches; a question attaching waits for it. */
    private var switchGate: CountDownLatch? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var thread: Thread? = null
    private val callbackExec = Executors.newSingleThreadExecutor()
    private val audio = ctx.getSystemService(AudioManager::class.java)
    /** The wake-word model, once its loader thread is done (guarded by [wwLock] until the loop takes it). */
    private val wwLock = Any()
    private var loadedWw: WakeWord? = null
    private var closed = false
    /** The call mode failed to open: not tried again before this (elapsedRealtime). */
    private var commRefusedUntil = 0L
    private val gateLog = GateSpeechLog()
    /** The current stretch of Robot talking, for SPEAK_RUN (audio thread). */
    private val speakRun = SpeakRun()
    /** The last CAPTURE_INFO per [MicKind] id (callbackExec's thread). */
    private val lastInfo = HashMap<Int, Triple<Int, Int, Int>>()

    override fun addSink(sink: (FloatArray) -> Unit) { sinks.addIfAbsent(sink) }
    override fun removeSink(sink: (FloatArray) -> Unit) {
        sinks.remove(sink)
        ends.remove(sink)
    }

    override val capturing: Boolean get() = running

    /** A question is being captured from this listener (its sink is attached): the capture mode doesn't change. */
    val questionOpen: Boolean get() = ends.isNotEmpty()

    /** The source in use, for the voice log. */
    val micKind: MicKind get() = when {
        communication -> MicKind.COMMUNICATION
        config.micSource == MicSource.MIC || openedPlain -> MicKind.MIC
        else -> MicKind.RECOGNITION
    }
    @Volatile private var openedPlain = false

    override fun attach(sink: (FloatArray) -> Unit, sinceWakeMs: Int, otherwiseMs: Int, onEnded: () -> Unit): FloatArray {
        // The loop decides to switch the capture under ringLock, so a question attaching meanwhile either sees no switch
        // and stops one from starting, or waits for it to finish (at most about a second): never a gap mid-question.
        var pre: FloatArray? = null
        for (attempt in 0 until 3) {
            val gate = synchronized(ringLock) {
                val g = switchGate
                if (g == null || attempt == 2) {
                    sinks.addIfAbsent(sink)
                    ends[sink] = onEnded
                    pre = recent.last(PreRoll.samples(samplesSeen - wakeAt, sinceWakeMs, otherwiseMs))
                }
                g
            }
            if (pre != null) break
            gate?.await(500, TimeUnit.MILLISECONDS)
        }
        // Stopped just before: no frame will come, so say so now.
        if (!running) endSinks()
        return pre ?: FloatArray(0)
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

    /** Opens the microphone (the configured source, MIC if that fails) and starts the loop. [why] goes to the voice log. */
    fun start(why: ListenWhy = ListenWhy.READY): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return fail("no_permission", ListenWhy.NO_PERMISSION)
        val record = synchronized(recLock) {
            var r: AudioRecord? = null
            for (src in listOf(config.micSource, MicSource.MIC).distinct()) {
                r = openStarted(src)
                if (r != null) break
            }
            r?.also { rec = it }
        } ?: return fail("open", ListenWhy.MIC_BUSY)
        silenced = record.activeRecordingConfiguration?.isClientSilenced
        running = true
        lastFrameAt = SystemClock.elapsedRealtime()
        if (config.wakeWord) loadWakeWord()
        thread = Thread({ loop(record) }, "robot-listen").also { it.start() }
        if (config.wakeWord) VoiceLog.add(VoiceEvent.LISTEN_START, why, micKind, aec.id)
        captureInfo(record)
        return true
    }

    /** Opens [src] at 16 kHz mono and starts it; with the call mode, the echo canceller and noise suppressor too. */
    @SuppressLint("MissingPermission")
    private fun openStarted(src: MicSource): AudioRecord? {
        val r = try {
            val fmt = AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // 2 s of buffer: the keyword decode runs on the read thread and must not lose audio while it does.
            AudioRecord.Builder().setContext(ctx).setAudioSource(src.id).setAudioFormat(fmt)
                .setBufferSizeInBytes(max(min * 2, SAMPLE_RATE * 2 * 2)).build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
        } catch (_: Throwable) { null } ?: return null
        val comm = src == MicSource.VOICE_COMMUNICATION
        // The effects go on the record's session before it starts, so the first frames are already cleaned.
        aec = if (comm) attachEffects(r) else AecState.OFF
        try {
            r.startRecording()
        } catch (_: Throwable) {
            releaseEffects()
            r.release()
            return null
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseEffects()
            r.release()
            return null
        }
        communication = comm
        openedPlain = src == MicSource.MIC
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val now = configs?.firstOrNull { it.clientAudioSessionId == r.audioSessionId }?.isClientSilenced ?: return
                if (now != silenced) {
                    silenced = now
                    RLog.i("mic_silenced", "value" to now)
                    VoiceLog.add(VoiceEvent.MIC_SILENCED, if (now) 1 else 0)
                }
            }
        }
        r.registerAudioRecordingCallback(callbackExec, cb)
        recCallback = cb
        return r
    }

    /**
     * Stops and releases [r] and its effects. Its recording callback is unregistered first: release() alone leaves the
     * callback's thread running and its registration in the audio service, one more of each per capture switch.
     */
    private fun close(r: AudioRecord) {
        recCallback?.let { try { r.unregisterAudioRecordingCallback(it) } catch (_: Throwable) {} }
        recCallback = null
        try { r.stop() } catch (_: Throwable) {}
        releaseEffects()
        try { r.release() } catch (_: Throwable) {}
    }

    /** The platform's echo canceller and noise suppressor on [r]'s session, where the phone has them. */
    private fun attachEffects(r: AudioRecord): AecState {
        val id = r.audioSessionId
        val state = if (!AcousticEchoCanceler.isAvailable()) AecState.UNAVAILABLE else try {
            echo = AcousticEchoCanceler.create(id)?.also { it.enabled = true }
            if (echo?.enabled == true) AecState.ON else AecState.FAILED
        } catch (t: Throwable) {
            RLog.w("aec", t)
            AecState.FAILED
        }
        noise = if (NoiseSuppressor.isAvailable()) try { NoiseSuppressor.create(id)?.also { it.enabled = true } } catch (_: Throwable) { null } else null
        return state
    }

    /**
     * The voice log's CAPTURE_INFO for [r], just opened: whether Android silences it, the microphone's own sample rate,
     * and the effects on it; only when that differs from the last time this mode was opened, so each switch doesn't
     * add a line. Robot's own effects are read here; the recording configuration is a binder call, so it is asked for
     * off the audio thread.
     */
    private fun captureInfo(r: AudioRecord) {
        if (!config.wakeWord) return
        val kind = micKind.id
        var fx = 0
        try {
            if (echo?.enabled == true) fx = fx or CaptureFx.AEC
            if (noise?.enabled == true) fx = fx or CaptureFx.NS
        } catch (_: Throwable) {}
        try {
            callbackExec.execute {
                val cfg = try { r.activeRecordingConfiguration } catch (_: Throwable) { null }
                var f = fx
                if (cfg == null) f = f or CaptureFx.NO_CONFIG else {
                    val types = try { cfg.effects.map { it.type } } catch (_: Throwable) { emptyList() }
                    if (AudioEffect.EFFECT_TYPE_AEC in types) f = f or CaptureFx.SYSTEM_AEC
                    if (AudioEffect.EFFECT_TYPE_NS in types) f = f or CaptureFx.SYSTEM_NS
                }
                val rate = cfg?.format?.sampleRate?.takeIf { it > 0 }
                val info = Triple(when (cfg?.isClientSilenced) { true -> 1; false -> 0; null -> -1 }, rate?.let { it / 100 } ?: -1, f)
                if (lastInfo.put(kind, info) != info) VoiceLog.add(VoiceEvent.CAPTURE_INFO, info.first, info.second, info.third)
            }
        } catch (_: Throwable) {}
    }

    private fun releaseEffects() {
        try { echo?.release() } catch (_: Throwable) {}
        try { noise?.release() } catch (_: Throwable) {}
        echo = null
        noise = null
    }

    private fun fail(stage: String, why: ListenWhy): Boolean {
        RLog.i("listen_start", "ok" to false, "stage" to RLog.Code(stage))
        if (config.wakeWord) VoiceLog.add(VoiceEvent.LISTEN_FAILED, why)
        return false
    }

    /** Loads the keyword model on its own thread: it may wait for another model's load, and the audio must not. */
    private fun loadWakeWord() {
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            var waited = 0L
            val ww = try {
                // Not beside a speech model's load, so a crash here is not blamed on that model (and the reverse).
                NativeLoads.one("kws") {
                    waited = SystemClock.elapsedRealtime() - t0
                    initMarker?.writeText("${Process.myPid()}")
                    try { WakeWord(ctx.assets, config.vadGate) } finally { initMarker?.delete() }
                }
            } catch (t: Throwable) {
                RLog.w("wake_word_init", t)
                null
            }
            val ms = SystemClock.elapsedRealtime() - t0
            RLog.i("listen_start", "ok" to true, "wakeWord" to (ww != null), "initMs" to ms, "waitedMs" to waited)
            if (ww == null) {
                VoiceLog.add(VoiceEvent.LISTEN_FAILED, ListenWhy.MODEL)
                return@Thread
            }
            ww.onGateSpeech = { len ->
                gateLog.onSpeech(len, SystemClock.elapsedRealtime())?.let { (longest, n) -> VoiceLog.add(VoiceEvent.GATE_SPEECH, longest, n) }
            }
            val keep = synchronized(wwLock) { if (closed) false else { loadedWw = ww; true } }
            if (!keep) ww.release() else VoiceLog.add(VoiceEvent.KWS_READY, (ms - waited).toInt(), waited.toInt())
        }, "robot-kws-load").start()
    }

    private fun loop(first: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var r = first
        var ww: WakeWord? = null
        val buf = ShortArray(FRAME)
        var errors = 0
        var wantSince = 0L
        var wasSpeaking = false
        while (running) {
            if (ww == null) ww = synchronized(wwLock) { loadedWw }?.also { wakeWordLoaded = true }
            // The capture mode: decided under ringLock, so a question can't attach between the decision and the switch.
            val now = SystemClock.elapsedRealtime()
            val cur = if (communication) CapturePolicy.Capture.COMMUNICATION else CapturePolicy.Capture.RECOGNITION
            val want = if (wantCommunication && now >= commRefusedUntil) CapturePolicy.Capture.COMMUNICATION else CapturePolicy.Capture.RECOGNITION
            if (want == cur) wantSince = 0L else if (wantSince == 0L) wantSince = now
            val gate = synchronized(ringLock) {
                val next = CapturePolicy.next(cur, want, questionOpen, ww?.hearing == true, if (wantSince == 0L) 0L else now - wantSince)
                if (next != cur) CountDownLatch(1).also { switchGate = it } else null
            }
            if (gate != null) {
                val next = try {
                    reopen(want == CapturePolicy.Capture.COMMUNICATION)
                } finally {
                    synchronized(ringLock) { switchGate = null }
                    gate.countDown()
                }
                r = next ?: break
                ww?.reset()
                wantSince = 0L
            }
            val n = try { r.read(buf, 0, FRAME) } catch (_: Throwable) { AudioRecord.ERROR }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT || ++errors > MAX_READ_ERRORS) {
                    RLog.i("listen_error", "dead" to (n == AudioRecord.ERROR_DEAD_OBJECT), "errors" to errors)
                    if (running && config.wakeWord) VoiceLog.add(VoiceEvent.LISTEN_STOP, ListenWhy.DEAD)
                    running = false
                    break
                }
                Thread.sleep(20)
                continue
            }
            errors = 0
            lastFrameAt = SystemClock.elapsedRealtime()
            val x = FloatArray(n)
            var ss = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768f
                x[i] = v
                ss += v * v
            }
            val frameDb = db(ss / n)
            levelDb = 0.8 * levelDb + 0.2 * frameDb
            // A sink attached after this block gets this frame in its pre-roll, one attached before gets it here.
            val targets = synchronized(ringLock) {
                recent.push(x)
                samplesSeen += n
                sinks.toTypedArray()
            }
            for (s in targets) try { s(x) } catch (t: Throwable) { RLog.w("audio_sink", t) }
            val speaking = inputs.speaking
            val w = ww?.takeIf { !wakePaused }
            val stop = if (speaking) KwsThresholds.stop(echoCancelled = communication && aec == AecState.ON) else null
            val hit = if (w == null) null else try { w.accept(x, inputs.wakeLines, stop) } catch (t: Throwable) {
                // Java-side errors only; a native crash takes the whole voice process.
                RLog.w("wake_word", t)
                null
            }
            if (speaking) speakRun.frame(n, frameDb, gate = w?.hearing == true, stop = w?.listeningForStop == true)
            else if (wasSpeaking) speakRun.end()?.let { (gateMs, minusDb, stopPct) -> if (config.wakeWord) VoiceLog.add(VoiceEvent.SPEAK_RUN, gateMs, minusDb, stopPct) }
            wasSpeaking = speaking
            if (hit != null) {
                // Before any check (EchoGuard, the debounce, the tests): the line after it in the log says what came of it.
                VoiceLog.add(VoiceEvent.KWS_HIT, KeywordBuffer.idOf(hit) ?: 0, if (speaking) 1 else 0, micKind.id * 10 + aec.id)
                // Only a wake phrase marks where a question starts; "stop" is followed by Robot's own voice.
                if (PreRoll.marksQuestion(hit)) synchronized(ringLock) { wakeAt = samplesSeen }
                onWake(hit)
            }
        }
        synchronized(wwLock) {
            closed = true
            (ww ?: loadedWw)?.release()
            loadedWw = null
        }
        wakeWordLoaded = false
        endSinks()
    }

    /**
     * Audio thread: the capture in the call mode ([comm]) or back in the configured one. The old record stops first
     * (two captures at once may get one silenced). If the call mode can't open, the old mode comes back and the call
     * mode isn't tried again for a minute. Null if no capture could be opened at all (the loop ends).
     */
    private fun reopen(comm: Boolean): AudioRecord? = synchronized(recLock) {
        if (!running) return null
        rec?.let(::close)
        rec = null
        val wanted = if (comm) MicSource.VOICE_COMMUNICATION else config.micSource
        var r = openStarted(wanted)
        if (r == null && comm) {
            commRefusedUntil = SystemClock.elapsedRealtime() + 60_000
            RLog.i("capture_comm_refused")
            r = openStarted(config.micSource)
        }
        if (r == null) r = openStarted(MicSource.MIC)
        if (r == null) {
            running = false
            VoiceLog.add(VoiceEvent.LISTEN_STOP, ListenWhy.DEAD)
            return null
        }
        rec = r
        RLog.i("capture", "communication" to communication, "aec" to aec, "ns" to (noise != null))
        if (config.wakeWord) VoiceLog.add(VoiceEvent.CAPTURE, micKind, aec, if (noise?.enabled == true) 1 else 0)
        captureInfo(r)
        r
    }

    fun stop(why: ListenWhy = ListenWhy.SHUTDOWN) {
        if (!running && rec == null && thread == null) return
        val wasRunning = running
        running = false
        // Unblocks a read in progress.
        synchronized(recLock) { rec?.let { try { it.stop() } catch (_: Throwable) {} } }
        thread?.join(2_000)
        synchronized(recLock) {
            rec?.let(::close)
            releaseEffects()
            rec = null
        }
        thread = null
        // The loop tells them as it exits; also here in case it is still stuck in a read.
        endSinks()
        callbackExec.shutdown()
        RLog.i("listen_stop")
        if (wasRunning && config.wakeWord) VoiceLog.add(VoiceEvent.LISTEN_STOP, why)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 32 ms at 16 kHz: also the Silero VAD window. */
        const val FRAME = 512
        /** Failed reads in a row (20 ms apart) before the capture counts as dead: 3 s. */
        private const val MAX_READ_ERRORS = 150

        fun db(meanSquare: Double): Double = if (meanSquare <= 1e-12) -120.0 else 10 * log10(meanSquare)
    }
}

/**
 * One stretch of Robot talking as the microphone heard it, for the voice log's SPEAK_RUN (pure): how long the
 * speech gate heard speech, the mean level (in dB, so her loud words don't hide how quiet the rest was: with echo
 * cancellation working it is low), and how much of the time the spotter listened for "stop".
 */
class SpeakRun(private val rate: Int = Listener.SAMPLE_RATE) {
    private var samples = 0L
    private var gateSamples = 0L
    private var stopSamples = 0L
    private var dbSum = 0.0
    private var frames = 0

    /** One frame of [n] samples at [db] dBFS while Robot talks; [gate]: the speech gate heard speech; [stop]: the spotter listened for "stop". */
    fun frame(n: Int, db: Double, gate: Boolean, stop: Boolean) {
        samples += n
        if (gate) gateSamples += n
        if (stop) stopSamples += n
        dbSum += db.coerceIn(-120.0, 0.0)
        frames++
    }

    /** (ms the gate heard speech, mean level as -dBFS, % listened for "stop"), or null if no frame came; starts over. */
    fun end(): Triple<Int, Int, Int>? {
        if (frames == 0 || samples == 0L) return null
        val out = Triple((gateSamples * 1000 / rate).toInt(), Math.round(-dbSum / frames).toInt(), (stopSamples * 100 / samples).toInt())
        samples = 0
        gateSamples = 0
        stopSamples = 0
        dbSum = 0.0
        frames = 0
        return out
    }
}
