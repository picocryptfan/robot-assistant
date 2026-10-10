package app.heyrobot.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.heyrobot.core.EngineState
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceEngineStatus
import app.heyrobot.core.VoiceFiles
import app.heyrobot.core.VoiceMark
import app.heyrobot.core.writeAtomic
import java.io.File

// VOICE PROCESS. Where the :voice process gets its speech engines (the single swap point): the sherpa-onnx
// ones once their models are downloaded and verified, the placeholders otherwise. Also the commands from the
// main app (models changed, try again, test voice speed) and the status file it reads.

internal class VoiceEnv(val ctx: Context, val stateDir: File, private val onChange: () -> Unit, private val onUse: () -> Unit = {}) {
    fun changed() = onChange()

    /** A model was just used (or loaded): the idle timer starts again. */
    fun used() = onUse()

    /** How the previous :voice process ended (ApplicationExitInfo reason), or null if Android does not say. */
    fun lastVoiceExit(): Int? = ctx.getSystemService(ActivityManager::class.java)
        .getHistoricalProcessExitReasons(ctx.packageName, 0, 16)
        .firstOrNull { it.processName.endsWith(":voice") }?.reason
}

/**
 * The voice models' memory rule (pure): the wake word stays loaded; speech to text and the voice load on first use
 * and are freed after [IDLE_MS] without use (and on memory pressure), never while in use.
 */
object IdlePolicy {
    const val IDLE_MS = 5 * 60_000L

    fun shouldUnload(loaded: Boolean, inUse: Boolean, lastUsedMs: Long, nowMs: Long, idleMs: Long = IDLE_MS): Boolean =
        loaded && !inUse && nowMs - lastUsedMs >= idleMs
}

/**
 * One downloaded model bundle and the native engine made from it. Loaded on first use (on the engine's worker
 * thread, which is the only thread that calls [get] and [release]), freed after [IdlePolicy.IDLE_MS] unused and on
 * memory pressure. Never created unless the verified mark vouches for every file, and always behind the crash guard.
 */
internal class ModelSlot<T : Any>(val bundle: VoiceBundle, private val env: VoiceEnv, private val free: (T) -> Unit) {
    private val guard by lazy { EngineGuard(bundle.id, File(env.stateDir, "guard-${bundle.id}.txt"), env::lastVoiceExit) }
    @Volatile private var verified = false
    @Volatile private var loading = false
    @Volatile private var failed = false
    @Volatile var value: T? = null; private set
    /** The last load's duration (ms), or -1. */
    @Volatile var loadMs = -1L; private set
    /** When the engine was last handed out (elapsedRealtime). */
    @Volatile var lastUsed = 0L; private set

    val dir: File get() = VoiceBundle.dir(env.ctx.filesDir, bundle)

    /** Re-reads the verified mark (a small file and a few stats); true if the bundle is complete. */
    fun refresh(): Boolean {
        verified = try { VoiceMark.complete(bundle, dir) } catch (_: Exception) { false }
        return verified
    }

    val state: EngineState get() = EngineFallback.state(verified, guard.broken, failed, loading, value != null)

    /** The engine, loading it now if needed; null if it may not or could not be loaded. Worker thread only. */
    fun get(create: (File) -> T): T? {
        value?.let {
            touch()
            return it
        }
        if (!refresh() || guard.broken || failed) return null
        loading = true
        env.changed()
        val t0 = SystemClock.elapsedRealtime()
        guard.beforeLoad()
        try {
            val v = create(dir)
            guard.afterLoad(true)
            value = v
            loadMs = SystemClock.elapsedRealtime() - t0
            touch()
            RLog.i("voice_load", "engine" to RLog.Code(bundle.id), "ok" to true, "ms" to loadMs)
            return v
        } catch (t: Throwable) {
            // A Java-side failure (a refused config, a missing file): the process lives; use the stand-in.
            guard.afterLoad(false)
            failed = true
            RLog.w("voice_load", t, "engine" to RLog.Code(bundle.id))
            return null
        } finally {
            loading = false
            env.changed()
        }
    }

    private fun touch() {
        lastUsed = SystemClock.elapsedRealtime()
        env.used()
    }

    /** Frees the engine if it has not been used for [IdlePolicy.IDLE_MS] and is not in use. Worker thread only. */
    fun releaseIfIdle(inUse: Boolean): Boolean =
        IdlePolicy.shouldUnload(value != null, inUse, lastUsed, SystemClock.elapsedRealtime()) && release()

    /** Frees the native engine. Worker thread only. */
    fun release(): Boolean {
        val v = value ?: return false
        value = null
        try { free(v) } catch (t: Throwable) { RLog.w("voice_unload", t, "engine" to RLog.Code(bundle.id)) }
        RLog.i("voice_unload", "engine" to RLog.Code(bundle.id))
        env.changed()
        return true
    }

    /** "Try again": forget crashes and failures. */
    fun reset() {
        guard.reset()
        failed = false
        refresh()
        env.changed()
    }
}

object VoiceEngines {
    private lateinit var env: VoiceEnv
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Frees speech to text and the voice once unused for [IdlePolicy.IDLE_MS]; re-armed by every use. */
    private val idleCheck = Runnable { checkIdle() }
    private lateinit var sttSlot: ModelSlot<Recognizer>
    private lateinit var kokoroSlot: ModelSlot<com.k2fsa.sherpa.onnx.OfflineTts>
    private lateinit var kittenSlot: ModelSlot<com.k2fsa.sherpa.onnx.OfflineTts>
    private var stt: SherpaSpeechToText? = null
    private var tts: SherpaTextToSpeech? = null
    @Volatile private var benchRunning = false

    /** Once per process, before the engines: [stateDir] holds the crash guards and the status files (filesDir/robot). */
    @Synchronized
    fun init(ctx: Context, stateDir: File) {
        if (::env.isInitialized) return
        env = VoiceEnv(ctx.applicationContext, stateDir, ::writeStatus, ::armIdle)
        sttSlot = ModelSlot(VoiceBundle.STT, env) { it.release() }
        kokoroSlot = ModelSlot(VoiceBundle.KOKORO, env) { it.release() }
        kittenSlot = ModelSlot(VoiceBundle.KITTEN, env) { it.release() }
        slots().forEach { it.refresh() }
        writeStatus()
    }

    private fun slots() = listOf(sttSlot, kokoroSlot, kittenSlot)

    private fun armIdle() {
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IdlePolicy.IDLE_MS + 1_000)
    }

    /** Each engine checks on its own worker (in order with its decodes and syntheses), so nothing in use is freed. */
    private fun checkIdle() {
        stt?.unloadIfIdle()
        tts?.unloadIfIdle()
        // Something used meanwhile re-arms through its slot; check again while anything is still loaded.
        if (slots().any { it.value != null }) main.postDelayed(idleCheck, 60_000)
    }

    fun speechToText(ctx: Context, frames: () -> AudioFrames?): SpeechToText =
        SherpaSpeechToText(ctx.applicationContext, sttSlot, frames).also { stt = it }

    fun textToSpeech(ctx: Context): TextToSpeech {
        val s = SherpaTextToSpeech(ctx.applicationContext, kokoroSlot, kittenSlot).also { tts = it }
        return VoiceOutput(ctx.applicationContext, s)
    }

    /** The main app installed or deleted voice models: re-check them; free an engine whose files are gone. */
    fun modelsChanged() {
        if (!::env.isInitialized) return
        val gone = slots().filter { !it.refresh() && it.value != null }.map { it.bundle }
        if (VoiceBundle.STT in gone) stt?.unload()
        if (VoiceBundle.KOKORO in gone || VoiceBundle.KITTEN in gone) tts?.unload()
        writeStatus()
    }

    /** "Try again" in Settings › Voice: clear the crash guards and failures. */
    fun reset() {
        if (!::env.isInitialized) return
        slots().forEach { it.reset() }
        RLog.i("voice_reset")
    }

    /** "Test voice speed": runs on its own thread and reports through voice_bench.json. */
    fun benchmark() {
        if (!::env.isInitialized || benchRunning) return
        val s = stt ?: return
        val t = tts ?: return
        benchRunning = true
        Thread({
            try { VoiceBenchmark.run(env.ctx, s, t, File(env.stateDir, VoiceFiles.BENCH)) } finally { benchRunning = false }
        }, "robot-bench").start()
    }

    private fun writeStatus() {
        if (!::env.isInitialized || !::kittenSlot.isInitialized) return
        try {
            val st = VoiceEngineStatus(slots().associate { it.bundle to it.state }, System.currentTimeMillis())
            writeAtomic(File(env.stateDir, VoiceFiles.ENGINES), st.toJson().toString())
        } catch (t: Throwable) {
            RLog.w("voice_status", t)
        }
    }

    internal fun state(b: VoiceBundle): EngineState = when (b) {
        VoiceBundle.STT -> sttSlot.state
        VoiceBundle.KOKORO -> kokoroSlot.state
        VoiceBundle.KITTEN -> kittenSlot.state
    }
}
