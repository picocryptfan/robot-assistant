// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.headset

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import app.heyrobot.assistant.PanelController
import app.heyrobot.assistant.Phase
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.assistant.routines.RoutineVoice
import app.heyrobot.core.BriefControl
import app.heyrobot.core.Playback
import app.heyrobot.core.RLog
import app.heyrobot.core.TtsStop
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.headset.CaptureSource
import app.heyrobot.core.headset.EndWhy
import app.heyrobot.core.headset.EntryKind
import app.heyrobot.core.headset.HeadsetAuto
import app.heyrobot.core.headset.HeadsetSessionMachine
import app.heyrobot.core.headset.HeadsetSessionState
import app.heyrobot.core.headset.ListenWith
import app.heyrobot.core.headset.RouteDecision
import app.heyrobot.core.headset.RoutePlanner
import app.heyrobot.core.headset.SessionEffect
import app.heyrobot.core.headset.SessionEvent
import app.heyrobot.core.headset.SessionPhase
import app.heyrobot.core.headset.TimerKind
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SessionKind
import app.heyrobot.sys.AudioDevices
import app.heyrobot.voice.CaptureRoute
import app.heyrobot.voice.Cues
import app.heyrobot.voice.OutputPin
import app.heyrobot.voice.SessionRoute

// VOICE PROCESS (0.8). One headset or car session (design §3.3 "Flow", §3.6 HeadsetSession, §4.3): core's
// HeadsetSessionMachine decides, this carries out its effects with Android's audio. The headset's call route
// (setCommunicationDevice, MODE_IN_COMMUNICATION through call audio's owners: never during another app's call), the
// capture with VOICE_COMMUNICATION pinned to the headset's microphone (Listener, between questions), Robot's voice pinned
// to the headset and watched while it plays, the chime, the follow-up window, and at the end the route released so your
// music comes back. The headset going (the device list, ACTION_AUDIO_BECOMING_NOISY, the track's route) or another call
// stops speech at once; nothing moves to the speaker. Main thread.

class HeadsetSession(private val entry: EntryKind, private val locked: Boolean, private val wake: Boolean, private val car: SeenDevice?) {
    private val app get() = VoiceHub.app
    private val main get() = VoiceHub.main
    private val owner = Any()
    private var state = HeadsetSessionState()
    private val started = SystemClock.elapsedRealtime()
    private var routeAt = 0L
    private var ended = false
    /** The device the session is for (its output), and its number in devices.json (0: not remembered). */
    var device: SeenDevice? = null; private set
    var number: Int = 0; private set

    val kind: SessionKind get() = if (car != null) SessionKind.CAR else SessionKind.HEADSET

    /** The session holds the call route now (Robot's voice is call audio). */
    val commRoute: Boolean get() = state.routeHeld

    val phase: SessionPhase get() = state.phase

    fun begin() {
        val hs = HandsFreeHub.headset.settings
        val cs = HandsFreeHub.car.settings
        val decision = RoutePlanner.plan(entry, HandsFreeHub.devices(), HandsFreeHub.memory, hs, HandsFreeHub.callActive(), car = car,
            wakeInCar = car != null && (wake && cs.wakeListenWith == ListenWith.PHONE_MIC), carAsCall = cs.speakAsCall)
        if (decision is RouteDecision.Plan) {
            device = decision.plan.output
            // Settings › Headset › Your headphones fills with a headset's first use (a car is car mode's).
            if (car == null) decision.plan.output?.let { d -> HandsFreeHub.editDevices { HeadsetAuto.firstUse(it, d, System.currentTimeMillis()) } }
            number = device?.let { HandsFreeHub.memory.find(it) }?.number ?: 0
        }
        RLog.i("headset_session", "kind" to kind, "plan" to RLog.Code(decision.javaClass.simpleName.lowercase()))
        on(SessionEvent.Begin(decision))
    }

    /** [d] is (part of) the session's headset or car. */
    fun isSessionDevice(d: SeenDevice): Boolean = device?.let { HeadsetAuto.sessionDeviceGone(d, it) } == true

    fun deviceGone() {
        // Speech stops first, whatever the machine's phase: nothing may move to the speaker.
        VoiceHub.stopVoice(TtsStop.OTHER)
        if (!ended) on(SessionEvent.DeviceGone)
    }

    fun callStarted() = on(SessionEvent.CallStarted)

    /** The panel heard the question ([empty]: nothing came). */
    fun heard(empty: Boolean) {
        if (state.phase == SessionPhase.LISTENING || state.phase == SessionPhase.FOLLOW_UP) on(SessionEvent.Heard(empty))
        if (state.phase == SessionPhase.BUSY) watchReply()
    }

    /** The panel closed or a new session replaces this one: everything stops and is released. */
    fun cancel() {
        if (ended) return
        apply(listOf(SessionEffect.CancelTimers, SessionEffect.StopListening, SessionEffect.ClearCommunication, SessionEffect.PinOutput(null),
            SessionEffect.End(EndWhy.BY_YOU)))
    }

    private fun on(e: SessionEvent) {
        if (ended && e !is SessionEvent.Begin) return
        val (next, fx) = HeadsetSessionMachine.on(state, e, HandsFreeHub.headset.settings, HandsFreeHub.devices())
        state = next
        apply(fx)
    }

    private fun apply(effects: List<SessionEffect>) {
        for (fx in effects) try {
            when (fx) {
                is SessionEffect.SetCommunication -> setCommunication(fx.device)
                SessionEffect.ClearCommunication -> clearCommunication()
                is SessionEffect.PinOutput -> pin(fx.device)
                SessionEffect.Chime -> Cues.listening(call = commRoute)
                is SessionEffect.Listen -> listen(fx.capture)
                SessionEffect.StopListening -> PanelController.stopListeningQuietly()
                SessionEffect.StopSpeech -> VoiceHub.stopVoice(TtsStop.OTHER)
                SessionEffect.CancelTurn -> PanelController.stopGenerating(TtsStop.OTHER)
                is SessionEffect.Say -> VoiceHub.tts.speak(fx.line.get(HandsFreeHub.lang))
                SessionEffect.Repeat -> PanelHandsFree.repeat()
                SessionEffect.Next -> RoutineVoice.control(BriefControl.NEXT)
                is SessionEffect.StartTimer -> main.postDelayed(if (fx.kind == TimerKind.ROUTE) routeTimeout else followUpExpired, fx.ms)
                SessionEffect.CancelTimers -> {
                    main.removeCallbacks(routeTimeout)
                    main.removeCallbacks(followUpExpired)
                    main.removeCallbacks(routeCheck)
                }
                is SessionEffect.RouteLogged -> VoiceLog.add(VoiceEvent.HEADSET_ROUTE, fx.mic, fx.ms.toInt())
                is SessionEffect.RouteFailed -> VoiceLog.add(VoiceEvent.HEADSET_ROUTE_FAILED, fx.why, if (fx.fellBack) 1 else 0)
                is SessionEffect.End -> end(fx.why)
            }
        } catch (t: Throwable) {
            RLog.w("headset_effect", t, "effect" to RLog.Code(fx.javaClass.simpleName.lowercase()))
        }
    }

    // ------------------------------------------------------------------ the route

    private fun setCommunication(d: SeenDevice) {
        val info = AudioDevices.info(app, d, outputs = true)
        if (info == null || !SessionRoute.take(app, owner, info)) {
            // Refused (or the headset went just now): the fallback, as for a route that never came.
            main.post { if (state.phase == SessionPhase.ROUTING) on(SessionEvent.RouteTimeout) }
            return
        }
        // Robot's voice as call audio on the headset; the capture with VOICE_COMMUNICATION from its microphone.
        VoiceHub.tts.setPlayback(Playback.CALL)
        CaptureRoute.set(true, input(d))
        routeAt = SystemClock.elapsedRealtime()
        if (state.phase == SessionPhase.ROUTING) main.post(routeCheck)
    }

    /** The headset's microphone (its input entry), for the capture to pin. */
    private fun input(d: SeenDevice): AudioDeviceInfo? = try {
        app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) && AudioDevices.seen(app, it).samePhysical(d) }
    } catch (_: Throwable) { null }

    /** Waits for the communication device and the capture's routed input (RouteUp), checked every 100 ms up to the timeout. */
    private val routeCheck: Runnable = object : Runnable {
        override fun run() {
            if (state.phase != SessionPhase.ROUTING || ended) return
            val am = app.getSystemService(AudioManager::class.java)
            val want = state.plan?.communication ?: return
            val comm = try { am.communicationDevice?.let { AudioDevices.seen(app, it) } } catch (_: Throwable) { null }
            if (comm != null && comm.samePhysical(want)) {
                // The listener's routed input once it reopened on the route; without a running listener (the wake word
                // off), the question's own capture opens on it when it starts.
                val listener = VoiceHub.listener
                val routed = listener?.routedInput?.let { AudioDevices.seen(app, it) }
                val input = if (listener == null) want.copy(type = state.plan?.input ?: RouteType.BT_SCO, sink = false) else routed
                if (listener == null || (routed != null && routed.type != RouteType.BUILTIN_MIC)) {
                    return on(SessionEvent.RouteUp(input, SystemClock.elapsedRealtime() - routeAt))
                }
            }
            main.postDelayed(this, 100)
        }
    }

    private val routeTimeout = Runnable { on(SessionEvent.RouteTimeout) }
    private val followUpExpired = Runnable { on(SessionEvent.FollowUpExpired) }

    private fun clearCommunication() {
        SessionRoute.release(app, owner)
        CaptureRoute.clear()
        VoiceHub.tts.setPlayback(VoiceHub.settings.playback)
    }

    /** Robot's voice pinned to [d] (null: let go), and a route change onto anything else stops it (OutputGuard rule 3). */
    private fun pin(d: SeenDevice?) {
        val info = d?.let { AudioDevices.info(app, it, outputs = true) }
        VoiceHub.tts.setPin(if (d == null) null else OutputPin(info) { routed ->
            val r = routed?.let { AudioDevices.seen(app, it) }
            if (!ended && (r == null || !r.samePhysical(d))) {
                RLog.i("headset_rerouted")
                deviceGone()
            }
        })
    }

    private fun listen(capture: CaptureSource) {
        // The capture's mode for the question (the Listener switches between questions; the question waits for it).
        if (!commRoute) CaptureRoute.set(capture == CaptureSource.VOICE_COMMUNICATION, null)
        // The car screen's Call asks who first; then, after the chime, the question (so it isn't the start of it).
        PanelHandsFree.takeIntro()?.let { VoiceHub.tts.speak(it) }
        main.postDelayed(object : Runnable {
            override fun run() {
                if (ended || (state.phase != SessionPhase.LISTENING && state.phase != SessionPhase.FOLLOW_UP)) return
                if (VoiceHub.tts.isSpeaking) return run { main.postDelayed(this, REPLY_CHECK_MS) }
                PanelController.micPressed()
            }
        }, CHIME_MS)
    }

    // ------------------------------------------------------------------ the answer

    private var quiet = 0

    /** While Robot answers: the reply is done once the panel is idle and her voice quiet (twice in a row), then any line PanelHandsFree kept. */
    private fun watchReply() {
        main.removeCallbacks(replyCheck)
        quiet = 0
        main.postDelayed(replyCheck, REPLY_CHECK_MS)
    }

    private val replyCheck: Runnable = object : Runnable {
        override fun run() {
            if (ended || state.phase != SessionPhase.BUSY) return
            val st = PanelController.state.value
            val idle = !st.busy && st.phase != Phase.LISTENING && st.phase != Phase.TRANSCRIBING && !VoiceHub.tts.isSpeaking && !RoutineVoice.active
            quiet = if (idle) quiet + 1 else 0
            if (quiet >= 2) {
                // "Want more?", or "Is it headphones only you can hear?": said first, then the follow-up listens for the answer.
                if (PanelHandsFree.atReplyEnd(this@HeadsetSession)) {
                    quiet = 0
                } else {
                    return on(SessionEvent.ReplyDone)
                }
            }
            main.postDelayed(this, REPLY_CHECK_MS)
        }
    }

    private fun end(why: EndWhy) {
        if (ended) return
        ended = true
        main.removeCallbacks(replyCheck)
        main.removeCallbacks(routeCheck)
        main.removeCallbacks(routeTimeout)
        main.removeCallbacks(followUpExpired)
        // Whatever the machine said, nothing of the session's route is left behind.
        SessionRoute.release(app, owner)
        CaptureRoute.clear()
        VoiceHub.tts.setPin(null)
        VoiceHub.tts.setPlayback(VoiceHub.settings.playback)
        VoiceLog.add(VoiceEvent.HEADSET_END, why, ((SystemClock.elapsedRealtime() - started) / 1000).toInt(), kind.id)
        RLog.i("headset_end", "why" to why)
        PanelHandsFree.sessionEnded(this)
        HandsFreeHub.ended(this)
    }

    /** The guard's view of where the session's sound goes now: its device while Android still lists it, else the speaker. */
    fun routed(): SeenDevice? {
        val d = device ?: return null
        return if (HandsFreeHub.devices().any { it.sink && it.samePhysical(d) }) d else OutputGuard.PHONE_SPEAKER
    }

    /** Remembered as a headset (for the private question); 0 for a car or nothing. */
    fun headsetNumber(): Int = device?.let { HandsFreeHub.memory.find(it, DeviceRole.HEADSET) }?.number ?: 0

    val startedLocked: Boolean get() = locked

    companion object {
        private const val CHIME_MS = 250L
        private const val REPLY_CHECK_MS = 300L
    }
}
