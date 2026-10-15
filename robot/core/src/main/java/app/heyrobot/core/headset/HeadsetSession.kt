// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.LogCode
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.PresenceEvent
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.output.SeenDevice

// Pure. A headset session as a state machine (design §3.3 "Flow", §3.6 HeadsetSession, §3.7): the :voice HeadsetSession
// feeds it what AudioManager, the Listener and the voice report, and carries out the effects it returns. Its seam is
// the effects, so the design's "fake AudioManager" tests (§3.9: a route timeout leads to the fallback, a route change
// mid-sentence to a stop, no route during a call) run here on the JVM.
//
// 1. Begin: the route (RoutePlanner). With a communication device: set it, pin Robot's voice to the headset, wait up to
//    3 s for it and for the headset's input. Without (wired, USB, the phone microphone): straight to listening.
// 2. The route is up and the capture really comes from the headset: a chime in the headset, then listen. Not within 3 s,
//    or the wrong input: "I'll listen on the phone" (Fall back to the phone mic) or "The headset microphone didn't
//    connect", and the session ends.
// 3. Heard: the turn runs (the answer goes through OutputGuard sentence by sentence).
// 4. Answered: listen for a follow-up (6 s by default, with the chime), then release the route so music resumes.
// 5. The headset goes, or a call starts: stop speaking and listening at once, release, end.
// The communication route, and with it the headset's microphone, is held only during a question, its follow-up window
// or a spoken card (§3.7 rule 4).

enum class SessionPhase { IDLE, ROUTING, LISTENING, BUSY, FOLLOW_UP, ENDED }

/** Why a session ended. Also a voice log code. */
enum class EndWhy(override val id: Int, override val title: String) : LogCode {
    DONE(1, "done"), BY_YOU(2, "you stopped it"), DEVICE_GONE(3, "the headset went away"), CALL(4, "a call started"),
    ROUTE_FAILED(5, "the headset microphone didn't connect"), NO_HEADSET(6, "no headset"), NOTHING_HEARD(7, "nothing heard"),
}

enum class TimerKind { ROUTE, FOLLOW_UP }

sealed class SessionEvent {
    /** Start, with RoutePlanner's decision. */
    data class Begin(val decision: RouteDecision) : SessionEvent()

    /** The communication device changed to the plan's and the capture's routed input is [routedInput], [ms] after Begin. */
    data class RouteUp(val routedInput: SeenDevice?, val ms: Long) : SessionEvent()

    data object RouteTimeout : SessionEvent()

    /** The question ended: [empty] when nothing was heard. */
    data class Heard(val empty: Boolean) : SessionEvent()

    /** Robot finished answering (or was stopped). */
    data object ReplyDone : SessionEvent()

    data object FollowUpExpired : SessionEvent()

    /** The session's headset went away (AudioDeviceCallback, ACTION_AUDIO_BECOMING_NOISY). */
    data object DeviceGone : SessionEvent()

    /** Another app's call started (AudioManager mode changed). */
    data object CallStarted : SessionEvent()

    /** A button, as ButtonGestures read it. */
    data class Button(val action: ButtonAction) : SessionEvent()
}

sealed class SessionEffect {
    data class SetCommunication(val device: SeenDevice) : SessionEffect()
    data object ClearCommunication : SessionEffect()
    /** AudioTrack.setPreferredDevice for Robot's voice (null: no preference). */
    data class PinOutput(val device: SeenDevice?) : SessionEffect()
    data object Chime : SessionEffect()
    data class Listen(val capture: CaptureSource, val mic: MicRoute) : SessionEffect()
    data object StopListening : SessionEffect()
    data object StopSpeech : SessionEffect()
    /** Cancel the turn being thought about. */
    data object CancelTurn : SessionEffect()
    data class Say(val line: Line) : SessionEffect()
    /** Say the last answer again. */
    data object Repeat : SessionEffect()
    /** The brief's next item (routines' BriefControl.NEXT). */
    data object Next : SessionEffect()
    data class StartTimer(val kind: TimerKind, val ms: Long) : SessionEffect()
    data object CancelTimers : SessionEffect()
    /** Voice log: HEADSET_ROUTE (a = [mic], b = [ms]). */
    data class RouteLogged(val mic: MicRoute, val ms: Long) : SessionEffect()
    /** Voice log: HEADSET_ROUTE_FAILED (a = [why], b = [fellBack]). */
    data class RouteFailed(val why: RouteFail, val fellBack: Boolean) : SessionEffect()
    data class End(val why: EndWhy) : SessionEffect()
}

data class HeadsetSessionState(val phase: SessionPhase = SessionPhase.IDLE, val plan: RoutePlan? = null, val fellBack: Boolean = false) {
    /** The communication device is set now. */
    val routeHeld: Boolean get() = plan?.communication != null && phase in setOf(SessionPhase.ROUTING, SessionPhase.LISTENING, SessionPhase.FOLLOW_UP) ||
        plan?.answerCommunication != null && phase == SessionPhase.BUSY
}

object HeadsetSessionMachine {
    fun on(s: HeadsetSessionState, e: SessionEvent, settings: HeadsetSettings, devices: List<SeenDevice> = emptyList()): Pair<HeadsetSessionState, List<SessionEffect>> {
        val plan = s.plan
        // Whatever the phase: the headset going, or a call, ends it at once.
        if (s.phase != SessionPhase.IDLE && s.phase != SessionPhase.ENDED) when (e) {
            SessionEvent.DeviceGone -> return end(s, EndWhy.DEVICE_GONE, stop = true)
            SessionEvent.CallStarted -> return end(s, EndWhy.CALL, stop = true)
            else -> Unit
        }
        return when (s.phase) {
            SessionPhase.IDLE, SessionPhase.ENDED -> begin(e)
            SessionPhase.ROUTING -> when (e) {
                is SessionEvent.RouteUp -> if (RoutePlanner.inputOk(plan!!, e.routedInput)) {
                    s.copy(phase = SessionPhase.LISTENING) to listOf(SessionEffect.CancelTimers, SessionEffect.RouteLogged(plan.mic, e.ms)) + listen(plan)
                } else fallback(s, RouteFail.WRONG_INPUT, settings, devices)
                SessionEvent.RouteTimeout -> fallback(s, RouteFail.TIMEOUT, settings, devices)
                is SessionEvent.Button -> if (e.action == ButtonAction.STOP_LISTENING || e.action == ButtonAction.CANCEL) end(s, EndWhy.BY_YOU) else s to emptyList()
                else -> s to emptyList()
            }
            SessionPhase.LISTENING -> when (e) {
                is SessionEvent.Heard -> if (e.empty) end(s, EndWhy.NOTHING_HEARD) else busy(s)
                is SessionEvent.Button -> when (e.action) {
                    ButtonAction.STOP_LISTENING, ButtonAction.CANCEL -> end(s, EndWhy.BY_YOU, listening = true)
                    ButtonAction.REPEAT -> busy(s, listOf(SessionEffect.StopListening, SessionEffect.Repeat))
                    else -> s to emptyList()
                }
                else -> s to emptyList()
            }
            SessionPhase.BUSY -> when (e) {
                SessionEvent.ReplyDone -> afterReply(s, settings)
                is SessionEvent.Button -> when (e.action) {
                    ButtonAction.STOP_SPEAKING -> s to listOf(SessionEffect.StopSpeech)
                    ButtonAction.CANCEL -> end(s, EndWhy.BY_YOU, stop = true)
                    ButtonAction.STOP_AND_LISTEN -> s.copy(phase = SessionPhase.LISTENING) to listOf(SessionEffect.StopSpeech) + toListening(plan!!) + listen(plan)
                    ButtonAction.REPEAT -> s to listOf(SessionEffect.StopSpeech, SessionEffect.Repeat)
                    ButtonAction.NEXT -> s to listOf(SessionEffect.Next)
                    else -> s to emptyList()
                }
                else -> s to emptyList()
            }
            SessionPhase.FOLLOW_UP -> when (e) {
                is SessionEvent.Heard -> if (e.empty) end(s, EndWhy.DONE, listening = true) else busy(s, listOf(SessionEffect.CancelTimers))
                SessionEvent.FollowUpExpired -> end(s, EndWhy.DONE, listening = true)
                is SessionEvent.Button -> when (e.action) {
                    ButtonAction.STOP_LISTENING, ButtonAction.CANCEL -> end(s, EndWhy.BY_YOU, listening = true)
                    ButtonAction.REPEAT -> busy(s, listOf(SessionEffect.CancelTimers, SessionEffect.StopListening, SessionEffect.Repeat))
                    else -> s to emptyList()
                }
                else -> s to emptyList()
            }
        }
    }

    private fun begin(e: SessionEvent): Pair<HeadsetSessionState, List<SessionEffect>> {
        if (e !is SessionEvent.Begin) return HeadsetSessionState(SessionPhase.IDLE) to emptyList()
        return when (val d = e.decision) {
            RouteDecision.DuringCall -> HeadsetSessionState(SessionPhase.ENDED) to listOf(SessionEffect.End(EndWhy.CALL))
            RouteDecision.NoHeadset -> HeadsetSessionState(SessionPhase.ENDED) to listOf(SessionEffect.End(EndWhy.NO_HEADSET))
            is RouteDecision.Plan -> {
                val p = d.plan
                val comm = p.communication
                if (comm != null) HeadsetSessionState(SessionPhase.ROUTING, p) to
                    listOf(SessionEffect.SetCommunication(comm), SessionEffect.PinOutput(p.output), SessionEffect.StartTimer(TimerKind.ROUTE, RoutePlanner.ROUTE_TIMEOUT_MS))
                else HeadsetSessionState(SessionPhase.LISTENING, p) to listOf(SessionEffect.PinOutput(p.output), SessionEffect.RouteLogged(p.mic, 0)) + listen(p)
            }
        }
    }

    private fun listen(p: RoutePlan): List<SessionEffect> = listOf(SessionEffect.Chime, SessionEffect.Listen(p.capture, p.mic))

    /** The question was heard: the answer's route (the car's call route after "hey robot"), then the turn runs. */
    private fun busy(s: HeadsetSessionState, first: List<SessionEffect> = emptyList()): Pair<HeadsetSessionState, List<SessionEffect>> {
        val p = s.plan!!
        val open = p.answerCommunication?.takeIf { it != p.communication }?.let { listOf(SessionEffect.SetCommunication(it)) } ?: emptyList()
        return s.copy(phase = SessionPhase.BUSY) to first + open
    }

    /** Back from answering to listening: the listening route again when the answer had its own. */
    private fun toListening(p: RoutePlan): List<SessionEffect> = when {
        p.answerCommunication == p.communication -> emptyList()
        p.communication != null -> listOf(SessionEffect.SetCommunication(p.communication))
        else -> listOf(SessionEffect.ClearCommunication)
    }

    private fun afterReply(s: HeadsetSessionState, settings: HeadsetSettings): Pair<HeadsetSessionState, List<SessionEffect>> {
        val p = s.plan!!
        if (settings.followUpMs <= 0) return end(s, EndWhy.DONE)
        return s.copy(phase = SessionPhase.FOLLOW_UP) to toListening(p) + listen(p) + SessionEffect.StartTimer(TimerKind.FOLLOW_UP, settings.followUpMs)
    }

    private fun fallback(s: HeadsetSessionState, why: RouteFail, settings: HeadsetSettings, devices: List<SeenDevice>): Pair<HeadsetSessionState, List<SessionEffect>> {
        val p = s.plan!!
        return when (val f = RoutePlanner.onFailed(p, why, settings, devices)) {
            is RouteFallback.PhoneMic -> HeadsetSessionState(SessionPhase.LISTENING, f.plan, fellBack = true) to listOf(
                SessionEffect.CancelTimers, SessionEffect.RouteFailed(why, true), SessionEffect.ClearCommunication, SessionEffect.PinOutput(f.plan.output),
                SessionEffect.Say(f.say),
            ) + listen(f.plan)
            is RouteFallback.End -> HeadsetSessionState(SessionPhase.ENDED, p) to listOf(
                SessionEffect.CancelTimers, SessionEffect.RouteFailed(why, false), SessionEffect.ClearCommunication, SessionEffect.Say(f.say),
                SessionEffect.PinOutput(null), SessionEffect.End(EndWhy.ROUTE_FAILED),
            )
        }
    }

    /** End: stop what runs, release the route (the last session out sets the normal audio mode again, as CallAudio.end), so music resumes. */
    private fun end(s: HeadsetSessionState, why: EndWhy, stop: Boolean = false, listening: Boolean = false): Pair<HeadsetSessionState, List<SessionEffect>> {
        val effects = buildList {
            add(SessionEffect.CancelTimers)
            if (stop) { add(SessionEffect.StopSpeech); add(SessionEffect.StopListening); add(SessionEffect.CancelTurn) }
            else if (listening) add(SessionEffect.StopListening)
            if (s.plan?.communication != null || s.plan?.answerCommunication != null) add(SessionEffect.ClearCommunication)
            add(SessionEffect.PinOutput(null))
            add(SessionEffect.End(why))
        }
        return HeadsetSessionState(SessionPhase.ENDED, s.plan, s.fellBack) to effects
    }
}

/** What headset mode does when a remembered headset comes or goes (DevicePresence), apart from the session machine. */
sealed class HeadsetReaction {
    /** "Hey robot through the headset microphone": hold the headset's communication route for the wake word. */
    data class HoldWakeRoute(val device: RememberedDevice) : HeadsetReaction()
    data object ReleaseWakeRoute : HeadsetReaction()
    /** The session's headset is gone for good: end it (the machine already stopped speech when Android removed it). */
    data object EndSession : HeadsetReaction()
}

object HeadsetAuto {
    /**
     * [e] for headset mode. [sessionDevice] and [wakeDevice]: the remembered numbers of the session's headset and of the
     * one holding the wake route, if any. Only enabled headsets count; cars are car mode's.
     */
    fun onPresence(e: PresenceEvent, s: HeadsetSettings, sessionDevice: Int?, wakeDevice: Int?): List<HeadsetReaction> {
        val d = e.device
        if (d.role != DeviceRole.HEADSET) return emptyList()
        return if (e.connected) {
            if (d.enabled && s.wakeThroughHeadset && wakeDevice == null) listOf(HeadsetReaction.HoldWakeRoute(d)) else emptyList()
        } else buildList {
            if (wakeDevice == d.number) add(HeadsetReaction.ReleaseWakeRoute)
            if (sessionDevice == d.number) add(HeadsetReaction.EndSession)
        }
    }

    /** Android removed [removed]: true when it is the session's headset (send [SessionEvent.DeviceGone] at once). */
    fun sessionDeviceGone(removed: SeenDevice, session: SeenDevice?): Boolean = session != null && removed.samePhysical(session)

    /**
     * Settings › Headset › "Your headphones" is filled when a headset is first used (§3.4): a Bluetooth or USB headset a
     * session started on, remembered as a headset with "private" not asked yet. A car, a speaker or the phone: unchanged.
     */
    fun firstUse(memory: DeviceMemory, device: SeenDevice, now: Long): DeviceMemory {
        if (device.type.privateByType == false || device.btClass.neverPrivate) return memory
        val known = memory.find(device)
        if (known != null) return memory.saw(device, now)
        return memory.remember(device, DeviceRole.HEADSET, now)?.first ?: memory
    }
}
