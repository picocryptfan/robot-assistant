package app.heyrobot.assistant.routines

import app.heyrobot.core.output.VolumePolicy
import app.heyrobot.core.BriefItem
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.core.BriefControl
import app.heyrobot.core.PlayState
import app.heyrobot.core.RLog
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineOutputs
import app.heyrobot.core.SpeechLang
import app.heyrobot.core.SpeechText
import app.heyrobot.core.SpeechWay
import app.heyrobot.core.SpokenPart
import app.heyrobot.core.TtsStop
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.Trust
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Paths
import java.io.File

// VOICE PROCESS (0.8). Says a routine's parts one at a time with Robot's voice (VoiceHub.tts), so "next", "skip",
// "repeat" and "stop" move by them (core PlayState): said to the panel after "hey robot", the notification's buttons, or
// the "stop" spotter. A run nobody asked for speaks from RoutineSpeakingService, a mediaPlayback foreground service (Android
// 17 lets background audio play only from a visible activity or such a service, design §1.2); each part goes through
// OutputGuard first (core RoutineOutputs): on the lock screen a personal part waits for the unlock, nothing talks over a
// call, "Headset only" says nothing on the speaker. Asked in the panel, the panel is the surface. One run speaks at a time;
// the next waits its turn. Main thread only. Nothing here logs words: counts and codes.

object RoutineVoice {
    enum class Surface { SERVICE, PANEL }

    /**
     * One run's speech. [everything]: the brief's lock level is "Everything". [onEnd] gets it once it is over: what was
     * said, and what OutputGuard kept for the unlock ([held]).
     */
    class Session internal constructor(
        val routine: Routine,
        val surface: Surface,
        val everything: Boolean,
        val locked: Boolean,
        internal val onEnd: (Session) -> Unit,
    ) {
        internal var play = PlayState()
        internal var closed = false
        internal var ready = false
        internal var refused = false
        /** The rest of a brief after the unlock ("Continue your brief" if Android won't let it speak). */
        var resumed = false
        val held = mutableListOf<SpokenPart>()
        /** The brief's items said aloud (its reminders are then read). */
        val saidItems = mutableSetOf<BriefItem>()
        var said = 0; internal set
        var chars = 0; internal set
        var notSaid = 0; internal set
        val stopped: Boolean get() = play.stopped
    }

    /** "Hey robot" (or a new question) stopped it: it waits this long for "next", "repeat" or "stop" before it is over. */
    private const val PAUSE_MS = 60_000L
    /** A part that never reports its end (the voice failed) is given up after this, plus 100 ms a character. */
    private const val PART_MS = 20_000L

    private var current: Session? = null
    private val queue = ArrayDeque<Session>()
    private var paused = false
    private var gen = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val main get() = VoiceHub.main
    private val app: Context get() = VoiceHub.app

    /** A run speaks now, or is paused for a control. */
    val active: Boolean get() = current != null

    /** The run speaking now, to show on the notification. */
    val now: Session? get() = current

    // ------------------------------------------------------------------ sessions

    /** A new run's speech; it starts when the one before it is over. The service surface starts the foreground service. */
    fun open(routine: Routine, surface: Surface, everything: Boolean, locked: Boolean, onEnd: (Session) -> Unit): Session {
        val s = Session(routine, surface, everything, locked, onEnd)
        main.post {
            if (current == null) begin(s) else queue.addLast(s)
        }
        return s
    }

    /** More parts for [s] (any thread). */
    fun add(s: Session, parts: List<SpokenPart>) {
        if (parts.isEmpty()) return
        main.post {
            s.play = s.play.plus(parts)
            if (current === s) playCurrent()
        }
    }

    /** The run added its last part (any thread). */
    fun close(s: Session) {
        main.post {
            s.closed = true
            if (current === s) playCurrent()
        }
    }

    private fun begin(s: Session) {
        current = s
        paused = false
        hold()
        when (s.surface) {
            Surface.PANEL -> { s.ready = true; playCurrent() }
            Surface.SERVICE -> if (!RoutineSpeakingService.start(app, s.routine)) refused(s)
        }
    }

    /** [RoutineSpeakingService]: in the foreground, so the voice may play. */
    fun serviceReady() {
        val s = current ?: return
        if (s.surface != Surface.SERVICE || s.ready) return
        s.ready = true
        VoiceLog.add(VoiceEvent.ROUTINE_SPEECH, SpeechWay.FOREGROUND, if (s.locked) 1 else 0)
        playCurrent()
    }

    /**
     * Android didn't let the service start by itself (design §1.2 Path 2's fallback): the run's parts are kept and a
     * notification's Play starts it, a tap being something you did.
     */
    fun refused(s: Session) {
        if (s.refused) return
        s.refused = true
        RLog.i("routine_speech", "refused" to true)
        VoiceLog.add(VoiceEvent.ROUTINE_SPEECH, SpeechWay.NOTIFICATION, if (s.locked) 1 else 0)
        waiting = s
        if (s.resumed) RoutineNotices.continueBrief(app) else RoutineNotices.playLater(app, s.routine)
        current = null
        release()
        next()
    }

    /** The run Android didn't let speak, waiting for a tap on Play. */
    private var waiting: Session? = null

    /** Play on that notification: the service is in the foreground now, by your tap. */
    fun playWaiting() {
        val s = waiting ?: return RoutineSpeakingService.instance?.done() ?: Unit
        waiting = null
        RoutineNotices.cancelPlayLater(app)
        s.refused = false
        s.ready = true
        s.play = s.play.copy(at = 0, inPart = false)
        if (current == null) {
            current = s
            hold()
            playCurrent()
        } else {
            queue.addFirst(s)
        }
    }

    private fun end(s: Session) {
        if (current !== s) return
        current = null
        paused = false
        gen++
        main.removeCallbacks(watchdog)
        try { s.onEnd(s) } catch (t: Throwable) { RLog.w("routine_end", t) }
        next()
    }

    private fun next() {
        val n = queue.removeFirstOrNull()
        if (n != null) return begin(n)
        release()
        RoutineSpeakingService.instance?.done()
    }

    // ------------------------------------------------------------------ playing

    private fun playCurrent() {
        val s = current ?: return
        if (!s.ready || paused || s.play.inPart) return
        val part = s.play.current
        if (part == null) {
            if (s.closed || s.play.stopped) end(s)
            return
        }
        // The panel's own reply is still being said: this part comes after it.
        if (VoiceHub.tts.isSpeaking) { main.postDelayed({ playCurrent() }, 400); return }
        val d: OutputDecision? = if (s.surface == Surface.PANEL) OutputDecision.Speak(VolumePolicy.NORMAL)
            else RoutineOutputs.decide(part, s.routine.output, phone(), headset(), trust(), s.everything)
        if (d !is OutputDecision.Speak) {
            if (RoutineOutputs.waitsForUnlock(d)) s.held += part else s.notSaid++
            s.play = s.play.played()
            return playCurrent()
        }
        // Asked in the panel during a headset or car session: each line through OutputGuard as the panel's own sentences are
        // (a personal part only in private headphones, the car's two sentences); outside a session every line plays.
        val lines = if (s.surface == Surface.PANEL) part.lines.filter { l -> PanelHandsFree.allow(SpeechText.clean(l)) { part.personal } } else part.lines
        if (lines.isEmpty()) {
            s.notSaid++
            s.play = s.play.played()
            return playCurrent()
        }
        s.play = s.play.started()
        s.said += lines.size
        part.item?.let { s.saidItems += it }
        s.chars += part.chars
        lines.forEach(::say)
        val g = ++gen
        main.removeCallbacks(watchdog)
        watchFor = g
        main.postDelayed(watchdog, PART_MS + 100L * part.chars)
    }

    private var watchFor = 0
    private val watchdog = Runnable {
        val s = current ?: return@Runnable
        if (watchFor == gen && s.play.inPart && !paused) {
            RLog.i("routine_part_watchdog")
            s.play = s.play.played()
            playCurrent()
        }
    }

    /** One line in Robot's voice; a French line in Kokoro's French voice, as Claude's French sentences are. */
    private fun say(line: String) {
        val clean = SpeechText.clean(line)
        if (clean.isEmpty()) return
        val v = VoiceHub.settings.voiceChoice
        if (v.bundle == VoiceBundle.KOKORO && SpeechLang.french(clean)) {
            VoiceHub.tts.speakOther(clean, if (v != VoiceChoice.SIWIS) VoiceChoice.SIWIS.speaker else v.speaker, "fr")
        } else {
            VoiceHub.tts.speak(clean)
        }
    }

    /** From VoiceHub: Robot's voice went quiet; once it really has (a sentence ends between two), the next part. */
    fun onSpeakingChanged(speaking: Boolean) {
        if (speaking || current == null) return
        val g = gen
        main.postDelayed({
            val s = current ?: return@postDelayed
            if (g == gen && s.play.inPart && !paused && !VoiceHub.tts.isSpeaking) {
                s.play = s.play.played()
                playCurrent()
            }
        }, 350)
    }

    /**
     * From VoiceHub.stopVoice: something stopped Robot's voice. "Hey robot" or a new question pauses the run for a
     * minute, waiting for "next", "repeat" or "stop"; anything else ("stop", a tap, the panel closed, a volume key) ends it.
     */
    fun onVoiceStopped(why: TtsStop) {
        val s = current ?: return
        if (why == TtsStop.WAKE || why == TtsStop.NEW_QUESTION) {
            if (paused) return
            paused = true
            gen++
            main.removeCallbacks(expire)
            main.postDelayed(expire, PAUSE_MS)
        } else {
            stopRun(s)
        }
    }

    private val expire = Runnable { current?.let { if (paused) stopRun(it) } }

    private fun stopRun(s: Session) {
        s.play = s.play.control(BriefControl.STOP)
        s.held.clear()
        end(s)
    }

    /**
     * [text] said or typed to the panel is "next", "skip", "repeat" or "stop" while a run speaks (or is paused for it):
     * done, true. Anything else is a new question: the run ends (you moved on) and the panel answers it: false.
     */
    fun control(text: String): Boolean {
        val s = current ?: return false
        val c = BriefControl.of(text)
        if (c == null) {
            stopRun(s)
            return false
        }
        control(c)
        return true
    }

    /** The notification's buttons, and [control]'s words. */
    fun control(c: BriefControl) {
        val s = current ?: return
        VoiceLog.add(VoiceEvent.BRIEF_CONTROL, c.ordinal + 1)
        main.removeCallbacks(expire)
        paused = false
        gen++
        if (c == BriefControl.STOP) {
            VoiceHub.tts.stop()
            return stopRun(s)
        }
        s.play = s.play.control(c)
        VoiceHub.tts.stop()
        playCurrent()
    }

    // ------------------------------------------------------------------ what OutputGuard needs

    private fun phone(): PhoneState {
        val am = app.getSystemService(AudioManager::class.java)
        val mode = try { am.mode } catch (_: Throwable) { AudioManager.MODE_NORMAL }
        return PhoneState(
            locked = Assistant.locked(app),
            screenOn = try { app.getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { true },
            callActive = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION,
            mediaPlaying = try { am.isMusicActive } catch (_: Throwable) { false },
        )
    }

    private val HEADSETS = setOf(RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET, RouteType.BT_A2DP, RouteType.BLE_HEADSET,
        RouteType.HEARING_AID)

    /** A headset Android lists as an output now ("Headset only"), or null. */
    private fun headset(): SeenDevice? = try {
        app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d: AudioDeviceInfo ->
            SeenDevice(RouteType.of(d.type), d.productName?.toString().orEmpty(), d.address.orEmpty(), sink = true, id = d.id)
        }.firstOrNull { it.type in HEADSETS }
    } catch (_: Throwable) { null }

    /**
     * Whether the headset is private: what headset mode remembers in devices.json once it exists, else by its type (wired,
     * USB and hearing aids are; a Bluetooth headset is asked about, so a personal part waits).
     */
    private fun trust(): Trust {
        val h = headset() ?: return Trust.NOT_PRIVATE
        val memory = (DeviceMemory.decode(readOrNull(File(Paths.dir(app), "devices.json"))) as? DeviceFile.Ok)?.memory ?: DeviceMemory()
        return memory.trust(h)
    }

    // ------------------------------------------------------------------ the CPU while it speaks

    private fun hold() {
        if (wakeLock?.isHeld == true) return
        wakeLock = try {
            app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "robot:routine").apply {
                setReferenceCounted(false)
                acquire(3 * 60_000L)
            }
        } catch (t: Throwable) { RLog.w("routine_wakelock", t); null }
    }

    private fun release() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    /** For a test or a shutdown: everything stops. */
    fun reset() {
        main.post {
            current?.let { stopRun(it) }
            queue.clear()
            waiting = null
            release()
        }
    }
}
