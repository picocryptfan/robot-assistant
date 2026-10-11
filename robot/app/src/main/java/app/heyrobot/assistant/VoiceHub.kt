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
import app.heyrobot.core.BargeIn
import app.heyrobot.core.BargeRun
import app.heyrobot.core.EchoVoice
import app.heyrobot.core.KeywordBuffer
import app.heyrobot.core.KwsThresholds
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.InterruptSensitivity
import app.heyrobot.core.ListenWhy
import app.heyrobot.core.ModelCode
import app.heyrobot.core.MicKind
import app.heyrobot.core.PlaybackCode
import app.heyrobot.core.Playback
import app.heyrobot.core.RLog
import app.heyrobot.core.RepairWhen
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TtsStop
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.WakeAction
import app.heyrobot.core.WakeDrop
import app.heyrobot.core.WakePhrases
import app.heyrobot.core.enumOr
import app.heyrobot.core.writeAtomic
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.sys.HelperSpendStore
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.KwsAssets
import app.heyrobot.sys.RecognizerKeeper
import app.heyrobot.sys.Paths
import app.heyrobot.sys.SettingsStore
import app.heyrobot.voice.CapturePolicy
import app.heyrobot.voice.KeywordInputs
import app.heyrobot.voice.Listener
import app.heyrobot.voice.ListenerConfig
import app.heyrobot.voice.SpeechToText
import app.heyrobot.voice.TextToSpeech
import app.heyrobot.voice.VoiceEngines
import app.heyrobot.voice.VoiceOutput
import app.heyrobot.voice.WakeWord
import org.json.JSONObject

// VOICE PROCESS. The controller shared by the assistant service, the session and the panel: settings,
// the wake-word listener (mode A: in the assistant's process, as phase 0 proved), the speech engines
// (VoiceEngines: sherpa-onnx once the voice models are downloaded, the placeholders until then).
//
// 0.2.3: it owns Robot's speaking state (the TTS listener), so "stop", the capture mode for barge-in and the voice
// log follow the voice wherever it speaks (the panel, the voice test, the barge-in test); it builds the wake phrases'
// keyword lines from the settings and hands them to the running listener (no restart); and it writes the voice log.

object VoiceHub {
    private const val DEBOUNCE_MS = 2_000L
    private const val RETRY_MS = 5 * 60_000L
    /** A listener that died this soon after starting counts as a failed start (the retry backs off after three). */
    private const val SHORT_RUN_MS = 60_000L
    /** No frame for this long: the capture stalled without an error, so it is restarted. */
    private const val STALL_MS = 15_000L
    /** "Test wake words" ends by itself if the main app stops renewing it. */
    private const val WAKE_TEST_MS = 15_000L

    lateinit var app: Context; private set
    val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler

    @Volatile var settings = RobotSettings(); private set

    /** Settings › Voice › "Interrupt sensitivity" as the listener uses it now (the barge-in test's result says it). */
    val interruptInUse: InterruptSensitivity get() = inputs.interrupt
    @Volatile var listener: Listener? = null; private set
    lateinit var tts: TextToSpeech; private set
    lateinit var stt: SpeechToText; private set
    @Volatile private var wakeDisabledReason: String? = null
    private var lastWakeE = 0L
    /** Not VoiceHub's monitor: stopListening() holds that while it joins the audio thread that calls onWake. */
    private val wakeLock = Any()
    private var lastStartE = 0L
    /** Listeners in a row that died within [SHORT_RUN_MS]: the retry waits longer after three. */
    private var shortRuns = 0
    private var receivers = mutableListOf<BroadcastReceiver>()
    /** Between onReady and onShutdown of the assistant service: only then may the microphone be opened. */
    @Volatile private var active = false
    /**
     * The wake phrases' keyword lines (core KeywordBuffer, rebuilt when the settings change) and whether Robot speaks,
     * shared with every listener, which reads them on each frame.
     */
    private val inputs = KeywordInputs()
    /** Robot's voice (main thread): when it started and stopped, and whether its stop was already logged. */
    @Volatile private var speaking = false
    private var speakStartE = 0L
    private var spokeE = Long.MIN_VALUE / 2
    private var stopLogged = false
    /** Until when (elapsedRealtime) "Test wake words" runs. */
    @Volatile private var wakeTestUntil = 0L

    private val wakeTesting: Boolean get() = SystemClock.elapsedRealtime() < wakeTestUntil

    /** Retries a listener that stopped (another app held the mic, a permission came later); cancelled at shutdown. */
    private val retry = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                val l = listener
                val now = SystemClock.elapsedRealtime()
                if (l != null && l.running && now - l.lastFrameAt > STALL_MS) {
                    // Frames stopped without an error: no wake word can fire. Start again.
                    RLog.i("listen_stalled")
                    VoiceLog.add(VoiceEvent.LISTEN_STOP, ListenWhy.STALLED)
                    stopListening(ListenWhy.STALLED)
                    applyListening(ListenWhy.RESTART)
                } else if (wantListening() && l?.running != true && now - lastStartE > (if (shortRuns < 3) 0 else RETRY_MS)) {
                    applyListening(ListenWhy.RETRY)
                }
            } catch (t: Throwable) { RLog.w("voice_retry", t) }
            if (active) worker.postDelayed(this, 60_000)
        }
    }

    /** Re-evaluates the capture mode once the tail after Robot's voice is over. */
    private val captureCheck = Runnable { updateCapture() }

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        VoiceLog.init(Paths.voiceLog(app))
        worker = Handler(HandlerThread("robot-voice").apply { start() }.looper)
        // Whether each helper's key is saved and today's spend, read off the main thread before the panel needs them.
        Thread({ HelperLink.refresh(app, settings); HelperSpendStore.current(app) }, "robot-helper-init").apply { isDaemon = true; start() }
        settings = SettingsStore.load(app)
        VoiceEngines.init(app, Paths.dir(app))
        tts = VoiceEngines.textToSpeech(app).also {
            it.configure(settings.speechRate, settings.voice)
            it.setPlayback(settings.playback)
            it.setWakeWords(WakePhrases.echoWords(settings.wakePhrases))
            it.setListener(::onSpeakingChanged)
        }
        // With no listener of its own running, a question during a conversation takes the session's capture.
        stt = VoiceEngines.speechToText(app) { listener ?: ConversationSession.frames() }.also { it.configure(settings.livePartials) }
        worker.post { buildWakeLines() }
        // 0.5.0: a conversation this process died under is closed, and Resume offered (never resumed by itself).
        ConversationSession.recoverOnce(app)
    }

    /** Robot's voice is playing: conversation mode counts it as silence (and 300 ms after). */
    val robotSpeaking: Boolean get() = speaking

    /**
     * A conversation runs ([off] true) without "Hey robot works during a conversation": the wake phrases sleep, "stop"
     * while Robot talks still works. False when it ends: the wake word is back.
     */
    fun conversationWake(off: Boolean) {
        inputs.wakeOff = off
        RLog.i("conv_wake", "off" to off)
    }

    // ------------------------------------------------------------------ Robot's voice

    /** Main thread, from the voice: speaking started or the queue ran empty (or was stopped). */
    private fun onSpeakingChanged(on: Boolean) {
        if (on == speaking) return
        speaking = on
        val now = SystemClock.elapsedRealtime()
        // While Robot speaks, "stop" said aloud ends the reply (when the wake word listens).
        inputs.speaking = on
        if (on) {
            speakStartE = now
            stopLogged = false
            val phone = (tts as? VoiceOutput)?.usesPhoneVoice == true
            val pb = if (phone) PlaybackCode.PHONE_VOICE else if (settings.playback == Playback.CALL) PlaybackCode.CALL else PlaybackCode.ASSISTANT
            // The capture mode changes a moment later on the audio thread: log the one wanted.
            val l = listener
            val mic = when {
                l == null -> MicKind.RECOGNITION
                settings.bargeIn.wantsCommunication() -> MicKind.COMMUNICATION
                else -> l.micKind
            }
            VoiceLog.add(VoiceEvent.TTS_START, pb, mic)
        } else {
            spokeE = now
            if (!stopLogged) VoiceLog.add(VoiceEvent.TTS_STOP, TtsStop.DONE, (now - speakStartE).toInt())
            main.removeCallbacks(captureCheck)
            main.postDelayed(captureCheck, CapturePolicy.TAIL_MS + 50)
        }
        updateCapture()
        PanelController.onSpeakingChanged(on)
        BargeTest.onSpeaking(on)
    }

    private fun BargeIn.wantsCommunication() = this != BargeIn.OFF

    /** Stops Robot's voice and logs why (only if she was speaking). Every stop of the voice goes through here. */
    fun stopVoice(why: TtsStop) {
        if (tts.isSpeaking && !stopLogged) {
            stopLogged = true
            VoiceLog.add(VoiceEvent.TTS_STOP, why, (SystemClock.elapsedRealtime() - speakStartE).toInt())
        }
        tts.stop()
    }

    /** The capture mode for "hear me while Robot talks" (CapturePolicy); the listener applies it between questions. */
    fun updateCapture() {
        val l = listener ?: return
        val since = if (speaking) 0L else SystemClock.elapsedRealtime() - spokeE
        val want = CapturePolicy.desired(settings.bargeIn, speaking, since, PanelController.shown, BargeTest.active)
        l.wantCommunication = want == CapturePolicy.Capture.COMMUNICATION
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
        val k = VoiceEngines.trimSpeakers()
        RLog.i("voice_trim", "level" to level, "stt" to s, "tts" to t, "spk" to k)
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
            override fun onReceive(c: Context, i: Intent) = applyListening(ListenWhy.POWER)
        }, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }, null)
        // For the voice log only: "she didn't answer" may go with the screen being off.
        register(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val on = i.action == Intent.ACTION_SCREEN_ON
                VoiceLog.add(VoiceEvent.SCREEN, if (on) 1 else 0)
                // Nobody looks at "Test wake words" with the screen off: wake words open Robot again.
                if (!on) wakeTestUntil = 0L
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, null)
        applyListening(ListenWhy.READY)
        worker.postDelayed(retry, 60_000)
        // Robot is the running assistant: keep the speech recognizer setting that keeps it so after a restart.
        worker.post { try { RecognizerKeeper.check(app, RepairWhen.ASSISTANT_READY) } catch (t: Throwable) { RLog.w("recognizer", t) } }
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
        stopListening(ListenWhy.SHUTDOWN)
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
    fun applyListening(why: ListenWhy) {
        val want = wantListening()
        val cur = listener
        if (want && cur != null && cur.running) return writeStatus(true, null)
        if (!want) {
            val reason = when {
                !active || RobotVoiceService.instance == null -> ListenWhy.NOT_ASSISTANT
                !settings.wakeWord -> ListenWhy.OFF
                wakeDisabledReason != null -> ListenWhy.CRASHES
                !Assistant.granted(app, Manifest.permission.RECORD_AUDIO) -> ListenWhy.NO_PERMISSION
                else -> ListenWhy.NOT_CHARGING
            }
            stopListening(if (why == ListenWhy.SHUTDOWN) why else reason)
            writeStatus(false, when (reason) {
                ListenWhy.NOT_ASSISTANT -> "Robot isn't the active assistant right now."
                ListenWhy.OFF -> "The wake word is off in settings."
                ListenWhy.CRASHES -> wakeDisabledReason
                ListenWhy.NO_PERMISSION -> "Robot needs the microphone permission."
                else -> "Listening only while charging."
            })
            return
        }
        // A listener that ended by itself soon after it started counts against the quick retries.
        if (cur != null && !cur.running) shortRuns = if (SystemClock.elapsedRealtime() - lastStartE < SHORT_RUN_MS) shortRuns + 1 else 0
        stopListening(why)
        lastStartE = SystemClock.elapsedRealtime()
        val l = Listener(app, ListenerConfig(), Paths.marker(app, "kws_init"), inputs, ::onWake)
        val ok = l.start(why)
        if (!ok) shortRuns++
        listener = if (ok) l else null
        // A conversation takes its audio from this listener now.
        if (ok) ConversationSession.audioChanged()
        updateCapture()
        writeStatus(ok, if (ok) null else "The microphone couldn't be opened (another app may be using it).")
        RLog.i("listen_apply", "ok" to ok)
    }

    @Synchronized
    fun stopListening(why: ListenWhy) {
        val had = listener != null
        listener?.stop(why)
        listener = null
        // A conversation that used it opens a capture of its own.
        if (had) ConversationSession.audioChanged()
    }

    /**
     * Worker thread: the wake phrases' keyword lines from the settings (pure Kotlin spelling, core KeywordBuffer). If
     * the spelling files can't be read, "hey robot" keeps its known spelling.
     */
    private fun buildWakeLines() {
        val phrases = settings.wakePhrases
        val interrupt = settings.interrupt
        // "Stop" takes the new level at once, even if the wake lines below can't be built.
        inputs.interrupt = interrupt
        val sp = KwsAssets.speller(app)
        val talk: (WakePhrase) -> KwsThresholds.Preset = { KwsThresholds.talkWake(it.sensitivity, interrupt) }
        val heyRobot = listOf("▁HE", "Y", "▁RO", "B", "O", "T")
        val fallback = { preset: (WakePhrase) -> KwsThresholds.Preset ->
            phrases.filter { it.enabled && WakePhrases.clean(it.text).equals("hey robot", ignoreCase = true) }
                .joinToString("\n") { KeywordBuffer.line(heyRobot, preset(it), KeywordBuffer.label(it.id)) }
        }
        val lines = if (sp != null) {
            val b = KeywordBuffer.wake(phrases, sp)
            if (b.refused.isNotEmpty()) RLog.i("wake_phrases_refused", "count" to b.refused.size)
            VoiceLog.add(VoiceEvent.KWS_SET, b.ids.size, b.refused.size)
            b.text
        } else {
            fallback { KwsThresholds.wake(it.sensitivity) }.also { VoiceLog.add(VoiceEvent.KWS_SET, it.lines().count { l -> l.isNotBlank() }, 0, 1) }
        }
        // While Robot talks: the same phrases at Settings › Voice › "Interrupt sensitivity" (Medium: these very lines).
        inputs.talkWakeLines = if (interrupt == InterruptSensitivity.MEDIUM) null else if (sp != null) KeywordBuffer.wake(phrases, sp, talk).text else fallback(talk)
        inputs.wakeLines = lines
        RLog.i("wake_phrases", "enabled" to phrases.count { it.enabled }, "lines" to lines.lines().count { it.isNotBlank() })
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

    /** Which voice speaks now, for the voice log's EchoGuard lines (main thread). */
    private fun echoVoice(): EchoVoice = if ((tts as? VoiceOutput)?.usesPhoneVoice == true) EchoVoice.PHONE else EchoVoice.MODEL

    /**
     * From the audio thread: "stop", or a wake phrase's label ("W<id>"), and when the frame it fired on was read
     * ([heardAt], elapsedRealtime). The listener has logged the hit (KWS_HIT); every way out of here logs what came of
     * it. EchoGuard is asked here, for [heardAt]: by the time the main thread runs, Robot's voice has moved on.
     */
    private fun onWake(label: String, heardAt: Long) {
        if (label == WakeWord.STOP) {
            // Ignored when the reply itself says "stop" (or a word like it): that may be Robot's own voice.
            val safe = tts.stopWordSafe(heardAt)
            // For the log: she said such a word lately, even outside the guard (did she stop herself?).
            val near = if (tts.saidStopLike(heardAt)) 1 else 0
            main.post {
                val playing = tts.isSpeaking
                if (BargeTest.active) return@post BargeTest.onKeyword(0, safe)
                if (!playing) return@post VoiceLog.add(VoiceEvent.STOP_HEARD, 0, near)
                RLog.i("stop_word", "acted" to safe)
                if (!safe) return@post VoiceLog.add(VoiceEvent.STOP_SUPPRESSED, echoVoice(), (SystemClock.elapsedRealtime() - speakStartE).toInt())
                VoiceLog.add(VoiceEvent.STOP_HEARD, 1, near)
                PanelController.stopSpeaking(TtsStop.STOP_WORD)
            }
            return
        }
        val id = KeywordBuffer.idOf(label) ?: -1
        val e = SystemClock.elapsedRealtime()
        synchronized(wakeLock) {
            if (e - lastWakeE < DEBOUNCE_MS) {
                VoiceLog.add(VoiceEvent.WAKE_IGNORED, WakeDrop.DEBOUNCE, id)
                return
            }
            lastWakeE = e
        }
        val safe = tts.wakeWordSafe(heardAt)
        main.post {
            if (wakeTesting) return@post VoiceLog.add(VoiceEvent.WAKE, id, -1, WakeAction.TEST.id)
            if (BargeTest.active) return@post BargeTest.onKeyword(id, safe)
            if (tts.isSpeaking && !safe) return@post VoiceLog.add(VoiceEvent.WAKE_IGNORED, WakeDrop.ECHO, id, echoVoice().id)
            RLog.i("wake", "panelShown" to PanelController.shown)
            if (PanelController.shown) {
                VoiceLog.add(VoiceEvent.WAKE, id, -1, WakeAction.NEW_QUESTION.id)
                PanelController.onWakeWhileShown()
                return@post
            }
            // A voice still speaking without the panel (the voice test) stops first.
            stopVoice(TtsStop.WAKE)
            val vis = RobotVoiceService.instance
            when {
                vis == null -> VoiceLog.add(VoiceEvent.WAKE_IGNORED, WakeDrop.NO_ASSISTANT, id)
                !vis.show(RobotVoiceService.SRC_WAKE) -> VoiceLog.add(VoiceEvent.WAKE_IGNORED, WakeDrop.SHOW_FAILED, id)
                else -> VoiceLog.add(VoiceEvent.WAKE, id, -1, WakeAction.OPENED.id)
            }
        }
    }

    // ------------------------------------------------------------------ commands from the main app

    private fun command(i: Intent) {
        when (i.getStringExtra("op")) {
            Cmd.SETTINGS -> {
                val old = settings
                settings = SettingsStore.load(app)
                tts.configure(settings.speechRate, settings.voice)
                tts.setPlayback(settings.playback)
                tts.setWakeWords(WakePhrases.echoWords(settings.wakePhrases))
                stt.configure(settings.livePartials)
                // New wake phrases (or interrupt sensitivity) go to the running listener: no restart.
                if (old.wakePhrases != settings.wakePhrases || old.interrupt != settings.interrupt) worker.post { buildWakeLines() }
                // "Hey robot works during a conversation" changed while one runs.
                if (ConversationSession.running) conversationWake(!settings.convWakeDuring)
                applyListening(ListenWhy.SETTINGS)
                updateCapture()
                // Settings › Model (0.4.1): a new choice lets go of the model in memory now; any save retries a failed one.
                val modelChanged = old.brainModelChoice != settings.brainModelChoice
                if (modelChanged) VoiceLog.add(VoiceEvent.MODEL_CHOSEN, ModelCode.of(settings.brainModelChoice), ModelCode.of(old.brainModelChoice))
                // 0.4.7: "Use the GPU" switched moves the model in memory to the CPU or the GPU now.
                BrainKeeper.settingsChanged(app, modelChanged || old.modelGpu != settings.modelGpu)
                PanelController.settingsChanged()
            }
            Cmd.HELPER_KEY -> {
                val id = i.getStringExtra(Cmd.EXTRA_ID) ?: return
                Thread({
                    HelperLink.keyChanged(app, id)
                    main.post { PanelController.helperKeyChanged(id) }
                }, "robot-helper-key").apply { isDaemon = true; start() }
            }
            Cmd.MEDIA_REMOVED -> PanelController.forgetKeptMedia()
            Cmd.CONVERSATIONS_DELETED -> PanelController.forgetDeleted(i.getStringExtra(Cmd.EXTRA_ID))
            // 0.5.0: a session recording a deleted transcript stops without writing it back; a Resume offer for one goes.
            Cmd.TRANSCRIPTS_DELETED -> ConversationSession.transcriptsDeleted(app, i.getStringExtra(Cmd.EXTRA_ID))
            Cmd.VOICEPRINT_DELETED -> ConversationSession.voiceprintDeleted()
            Cmd.SPEAK_TEST -> {
                stopVoice(TtsStop.TEST)
                tts.speak("Hi, I'm Robot. This is how I sound.")
            }
            Cmd.VOICE_MODELS -> VoiceEngines.modelsChanged()
            Cmd.VOICE_RESET -> VoiceEngines.reset()
            Cmd.VOICE_BENCH -> VoiceEngines.benchmark()
            Cmd.BARGE_TEST -> BargeTest.start(enumOr(i.getStringExtra(Cmd.EXTRA_RUN), BargeRun.FIRST))
            Cmd.WAKE_TEST -> wakeTestUntil = if (i.getBooleanExtra(Cmd.EXTRA_ON, false)) SystemClock.elapsedRealtime() + WAKE_TEST_MS else 0L
        }
    }
}
