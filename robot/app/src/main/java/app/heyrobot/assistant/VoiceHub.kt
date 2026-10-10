package app.heyrobot.assistant

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.ClaudeSpendStore
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.Paths
import app.heyrobot.sys.SettingsStore
import app.heyrobot.voice.Listener
import app.heyrobot.voice.ListenerConfig
import app.heyrobot.voice.SpeechToText
import app.heyrobot.voice.TextToSpeech
import app.heyrobot.voice.VoiceEngines
import app.heyrobot.voice.WakeWord
import org.json.JSONObject

// VOICE PROCESS. The controller shared by the assistant service, the session and the panel: settings,
// the "hey robot" listener (mode A: in the assistant's process, as phase 0 proved), the speech engines
// (VoiceEngines: sherpa-onnx once the voice models are downloaded, the placeholders until then).

object VoiceHub {
    private const val DEBOUNCE_MS = 2_000L
    private const val RETRY_MS = 5 * 60_000L

    lateinit var app: Context; private set
    val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler

    @Volatile var settings = RobotSettings(); private set
    @Volatile var listener: Listener? = null; private set
    lateinit var tts: TextToSpeech; private set
    lateinit var stt: SpeechToText; private set
    @Volatile private var wakeDisabledReason: String? = null
    private var lastWakeE = 0L
    /** Not VoiceHub's monitor: stopListening() holds that while it joins the audio thread that calls onWake. */
    private val wakeLock = Any()
    private var lastStartE = 0L
    private var receivers = mutableListOf<BroadcastReceiver>()
    /** Between onReady and onShutdown of the assistant service: only then may the microphone be opened. */
    @Volatile private var active = false

    /** Retries a listener that stopped (another app held the mic, a permission came later); cancelled at shutdown. */
    private val retry = object : Runnable {
        override fun run() {
            if (!active) return
            try { if (wantListening() && listener?.running != true && SystemClock.elapsedRealtime() - lastStartE > RETRY_MS) applyListening("retry") }
            catch (t: Throwable) { RLog.w("voice_retry", t) }
            if (active) worker.postDelayed(this, 60_000)
        }
    }

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        worker = Handler(HandlerThread("robot-voice").apply { start() }.looper)
        // Whether a Claude key is saved and today's Claude spend, read off the main thread before the panel needs them.
        Thread({ ClaudeLink.refresh(app); ClaudeSpendStore.current(app) }, "robot-claude-init").apply { isDaemon = true; start() }
        settings = SettingsStore.load(app)
        VoiceEngines.init(app, Paths.dir(app))
        tts = VoiceEngines.textToSpeech(app).also { it.configure(settings.speechRate, settings.voice) }
        stt = VoiceEngines.speechToText(app) { listener }.also { it.configure(settings.livePartials) }
    }

    /** From the panel: while Robot speaks, "stop" said aloud ends the reply (when the wake word listens). */
    fun onSpeakingChanged(speaking: Boolean) {
        listener?.stopWord = speaking
    }

    /**
     * Memory is short (RobotVoiceService.onTrimMemory): free the speech models unless they are in use. The wake
     * word stays. Not on UI_HIDDEN: as the panel comes and goes, the models follow their own idle rule (VoiceEngines:
     * loaded on first use, freed after 5 minutes unused). This rarely runs: Android 14+ no longer sends the
     * RUNNING_* levels, and BACKGROUND comes only once the process is cached, which the bound assistant process
     * does not become. The 5-minute rule is what frees the models in practice (README).
     */
    @Suppress("DEPRECATION") // RUNNING_LOW / RUNNING_CRITICAL: still honoured if a platform sends them.
    fun onTrimMemory(level: Int) {
        if (!::app.isInitialized) return
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
        val s = stt.trim()
        val t = tts.trim()
        RLog.i("voice_trim", "level" to level, "stt" to s, "tts" to t)
    }

    /** The assistant service is ready (from boot, after the first unlock, or after being chosen). */
    fun onReady(ctx: Context) {
        init(ctx)
        active = true
        crashGuard()
        // A second onReady without a shutdown must not double the receivers or the retry loop.
        unregisterAll()
        worker.removeCallbacks(retry)
        register(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = command(i)
        }, IntentFilter(Cmd.ACTION), Cmd.PERMISSION)
        // "Listen only while charging".
        register(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = applyListening("power")
        }, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }, null)
        applyListening("ready")
        worker.postDelayed(retry, 60_000)
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter, permission: String?) {
        try {
            app.registerReceiver(r, f, permission, null, Context.RECEIVER_NOT_EXPORTED)
            receivers += r
        } catch (t: Throwable) { RLog.w("voice_register", t) }
    }

    fun onShutdown() {
        // First, so nothing (the retry loop, a power or settings broadcast in flight) opens the microphone again.
        active = false
        if (::worker.isInitialized) worker.removeCallbacks(retry)
        stopListening("shutdown")
        if (::app.isInitialized) writeStatus(false, "Robot isn't the active assistant right now.")
        unregisterAll()
        BrainKeeper.release("shutdown")
    }

    private fun unregisterAll() {
        receivers.forEach { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        receivers.clear()
    }

    // ------------------------------------------------------------------ listening

    private fun charging(): Boolean = try {
        (app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (_: Exception) { false }

    /** Only while Robot is the active assistant (its service between onReady and onShutdown). */
    private fun wantListening(): Boolean = active && RobotVoiceService.instance != null && settings.wakeWord && wakeDisabledReason == null &&
        Assistant.granted(app, Manifest.permission.RECORD_AUDIO) && (!settings.wakeOnlyWhileCharging || charging())

    @Synchronized
    fun applyListening(reason: String) {
        val want = wantListening()
        val cur = listener
        if (want && cur != null && cur.running) return writeStatus(true, null)
        if (!want) {
            stopListening(reason)
            writeStatus(false, when {
                !active || RobotVoiceService.instance == null -> "Robot isn't the active assistant right now."
                !settings.wakeWord -> "The wake word is off in settings."
                wakeDisabledReason != null -> wakeDisabledReason
                !Assistant.granted(app, Manifest.permission.RECORD_AUDIO) -> "Robot needs the microphone permission."
                else -> "Listening only while charging."
            })
            return
        }
        stopListening(reason)
        lastStartE = SystemClock.elapsedRealtime()
        val l = Listener(app, ListenerConfig(), Paths.marker(app, "kws_init"), ::onWake)
        val ok = l.start()
        listener = if (ok) l else null
        writeStatus(ok, if (ok) null else "The microphone couldn't be opened (another app may be using it).")
        RLog.i("listen_apply", "ok" to ok)
    }

    @Synchronized
    fun stopListening(reason: String) {
        listener?.stop()
        listener = null
    }

    private fun writeStatus(listening: Boolean, note: String?) {
        try {
            writeAtomic(Paths.voiceStatus(app), JSONObject().put("listening", listening).put("note", note ?: "").put("t", System.currentTimeMillis()).toString())
        } catch (_: Exception) {}
    }

    /**
     * Native crashes in sherpa-onnx must not loop: a crash while the wake-word model loads, or three native
     * crashes of this process in 30 minutes, turn the wake word off until the next start of the process.
     */
    private fun crashGuard() {
        val marker = Paths.marker(app, "kws_init")
        if (marker.isFile) {
            marker.delete()
            RLog.i("wake_init_crash_suspected")
        }
        val since = System.currentTimeMillis() - 30 * 60_000L
        val crashes = try {
            app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 20)
                .count { it.processName.endsWith(":voice") && it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp >= since }
        } catch (_: Exception) { 0 }
        if (crashes >= 3) {
            wakeDisabledReason = "The wake word stopped after repeated crashes; it comes back after a restart."
            RLog.i("wake_disabled", "crashes" to crashes)
        }
    }

    /** From the audio thread: debounce, then on the main thread. */
    private fun onWake(keyword: String) {
        if (keyword == WakeWord.STOP) {
            main.post {
                // Ignored when the reply itself says "stop" (or a word like it): that may be Robot's own voice.
                val act = tts.isSpeaking && tts.stopWordSafe
                RLog.i("stop_word", "acted" to act)
                if (act) PanelController.stopSpeaking()
            }
            return
        }
        val e = SystemClock.elapsedRealtime()
        synchronized(wakeLock) {
            if (e - lastWakeE < DEBOUNCE_MS) return
            lastWakeE = e
        }
        main.post {
            RLog.i("wake", "panelShown" to PanelController.shown)
            if (PanelController.shown) PanelController.onWakeWhileShown()
            else RobotVoiceService.instance?.show(RobotVoiceService.SRC_WAKE)
        }
    }

    // ------------------------------------------------------------------ commands from the main app

    private fun command(i: Intent) {
        when (i.getStringExtra("op")) {
            Cmd.SETTINGS -> {
                settings = SettingsStore.load(app)
                tts.configure(settings.speechRate, settings.voice)
                stt.configure(settings.livePartials)
                applyListening("settings")
                PanelController.settingsChanged()
            }
            Cmd.CLAUDE_KEY -> Thread({
                ClaudeLink.keyChanged(app)
                main.post { PanelController.claudeKeyChanged() }
            }, "robot-claude-key").apply { isDaemon = true; start() }
            Cmd.SPEAK_TEST -> {
                tts.stop()
                tts.speak("Hi, I'm Robot. This is how I sound.")
            }
            Cmd.VOICE_MODELS -> VoiceEngines.modelsChanged()
            Cmd.VOICE_RESET -> VoiceEngines.reset()
            Cmd.VOICE_BENCH -> VoiceEngines.benchmark()
        }
    }
}
