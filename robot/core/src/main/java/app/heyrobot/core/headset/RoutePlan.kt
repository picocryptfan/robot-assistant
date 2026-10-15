// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.LogCode
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice

// Pure. How a headset session routes the microphone and Robot's voice (design §3.1 "Microphone and output routing",
// §3.6 HeadsetSession, §3.9 RoutePlan). VERIFIED in AOSP's audio policy (Engine.cpp, android-17.0.0_r1):
// - Bluetooth classic (HFP): the microphone is SCO only (A2DP has none). setCommunicationDevice(SCO) makes the engine pick
//   the SCO headset microphone for both VOICE_COMMUNICATION and VOICE_RECOGNITION.
// - LE Audio (TYPE_BLE_HEADSET): its microphone only for VOICE_COMMUNICATION with the LE device as communication device;
//   VOICE_RECOGNITION doesn't list it.
// - Wired or USB-C headsets: picked for VOICE_RECOGNITION by themselves, no communication device needed, and
//   USAGE_ASSISTANT plays to them by default.
// setCommunicationDevice needs only MODIFY_AUDIO_SETTINGS (held) and lasts until clearCommunicationDevice, the device
// disconnecting, or :voice dying. Robot never takes the route during another app's call (CallAudio.mayTake).

/** Which microphone a session listens with. Also the voice log's HEADSET_ROUTE a. */
enum class MicRoute(override val id: Int, override val title: String) : LogCode {
    SCO(1, "Bluetooth headset microphone (SCO)"), LE_AUDIO(2, "LE Audio headset microphone"), WIRED(3, "wired headset microphone"),
    USB(4, "USB headset microphone"), PHONE(5, "phone microphone"),
}

/** Why the headset's route didn't come up. Also the voice log's HEADSET_ROUTE_FAILED a. */
enum class RouteFail(override val id: Int, override val title: String) : LogCode {
    TIMEOUT(1, "no route within 3 s"), REFUSED(2, "setCommunicationDevice refused"), WRONG_INPUT(3, "the capture wasn't from the headset"),
    GONE(4, "the headset disconnected"),
}

/** The capture's audio source (MediaRecorder.AudioSource). */
enum class CaptureSource { VOICE_COMMUNICATION, VOICE_RECOGNITION }

/** How Robot's voice plays (AudioAttributes usage). */
enum class PlaybackUsage { VOICE_COMMUNICATION, ASSISTANT }

/**
 * A session's route. [communication]: the device to setCommunicationDevice (null: none). [mic]: where it listens.
 * [capture]: the Listener's source (VOICE_COMMUNICATION is its existing call-mode switch, with echo cancellation).
 * [input]: the routed input type to wait for (AudioRecord.getRoutedDevice), null for the phone's own microphone.
 * [output]: the device to pin Robot's voice to (setPreferredDevice). [usage]: how it plays. [answerCommunication]: the
 * communication device while Robot answers (the same as [communication], except in the car after "hey robot", where
 * the phone listens and the car's call route opens only for the answer).
 */
data class RoutePlan(
    val communication: SeenDevice?,
    val mic: MicRoute,
    val capture: CaptureSource,
    val input: RouteType?,
    val output: SeenDevice?,
    val usage: PlaybackUsage,
    val answerCommunication: SeenDevice? = communication,
) {
    /** It takes the call route: music pauses or drops to call quality (most headsets, EST). */
    val takesCallRoute: Boolean get() = listOfNotNull(communication, answerCommunication).any { it.type.bluetooth }
}

sealed class RouteDecision {
    data class Plan(val plan: RoutePlan) : RouteDecision()

    /** Another call is on: Robot doesn't touch its audio, and doesn't speak ("During a phone call the button does nothing to the call"). */
    data object DuringCall : RouteDecision()

    /** No headset connected after all: the panel on the phone, as any other way in. */
    data object NoHeadset : RouteDecision()
}

/** After the route failed: listen on the phone ([plan], saying [say] in the headset), or end the session ([say]). */
sealed class RouteFallback {
    data class PhoneMic(val plan: RoutePlan, val say: Line) : RouteFallback()
    data class End(val say: Line) : RouteFallback()
}

/** What a route costs, for Settings › Headset's explanation (EST, design §3.1 "Latency"; measured in H0.5). */
data class RouteCost(val routeMs: IntRange, val musicPauses: Boolean, val note: String)

object RoutePlanner {
    /** How long a session waits for the communication device and the headset input (§3.6). */
    const val ROUTE_TIMEOUT_MS = 3_000L

    val ON_PHONE = Line("I'll listen on the phone.", "Je vais écouter sur le téléphone.")
    val NO_HEADSET_MIC = Line("The headset microphone didn't connect.", "Le micro des écouteurs ne s'est pas connecté.")

    /**
     * The route for a session started by [entry] with [devices] connected (outputs and inputs, as AudioManager lists
     * them). The headset: for a voice button (BVRA), the Bluetooth one that has a microphone (SCO or LE), a remembered
     * enabled one first, [car] when car mode is on; for a hook or assist key, a wired or USB headset first, else a
     * Bluetooth one. "Phone microphone", a headset without a microphone, or "hey robot" in the car with the phone
     * microphone ([wakeInCar]): the phone listens and Robot is heard in the headset (or the car) without the call route,
     * except in the car, where Robot speaks through the car as a call ("Speak through the car as a call", [carAsCall]).
     */
    fun plan(entry: EntryKind, devices: List<SeenDevice>, memory: DeviceMemory, settings: HeadsetSettings, callActive: Boolean,
        car: SeenDevice? = null, wakeInCar: Boolean = false, carAsCall: Boolean = true): RouteDecision {
        if (callActive) return RouteDecision.DuringCall
        val outs = devices.filter { it.sink }
        if (car != null) return RouteDecision.Plan(carPlan(car, outs, wakeInCar, carAsCall))
        val bt = outs.filter { it.type == RouteType.BT_SCO || it.type == RouteType.BLE_HEADSET }
            .sortedByDescending { d -> memory.find(d, DeviceRole.HEADSET)?.let { if (it.enabled) 2 else 1 } ?: 0 }
        val wired = outs.filter { it.type == RouteType.WIRED_HEADSET || it.type == RouteType.USB_HEADSET || it.type == RouteType.WIRED_HEADPHONES }
        val media = outs.filter { it.type == RouteType.BT_A2DP || it.type == RouteType.BLE_HEADSET || it.type == RouteType.HEARING_AID }
        val headset = when (entry) {
            EntryKind.BVRA -> bt.firstOrNull() ?: media.firstOrNull()
            EntryKind.HOOK, EntryKind.ASSIST -> wired.firstOrNull() ?: bt.firstOrNull() ?: media.firstOrNull()
        } ?: return RouteDecision.NoHeadset
        if (settings.listenWith == ListenWith.PHONE_MIC || !headset.type.hasMic) return RouteDecision.Plan(phoneMic(headset, outs))
        return RouteDecision.Plan(when (headset.type) {
            RouteType.BT_SCO -> RoutePlan(headset, MicRoute.SCO, CaptureSource.VOICE_COMMUNICATION, RouteType.BT_SCO, headset, PlaybackUsage.VOICE_COMMUNICATION)
            // LE Audio: only VOICE_COMMUNICATION lists its microphone.
            RouteType.BLE_HEADSET -> RoutePlan(headset, MicRoute.LE_AUDIO, CaptureSource.VOICE_COMMUNICATION, RouteType.BLE_HEADSET, headset, PlaybackUsage.VOICE_COMMUNICATION)
            // Wired and USB: picked by themselves; no communication device, no call route.
            RouteType.USB_HEADSET -> RoutePlan(null, MicRoute.USB, CaptureSource.VOICE_RECOGNITION, RouteType.USB_HEADSET, headset, PlaybackUsage.ASSISTANT)
            else -> RoutePlan(null, MicRoute.WIRED, CaptureSource.VOICE_RECOGNITION, RouteType.WIRED_HEADSET, headset, PlaybackUsage.ASSISTANT)
        })
    }

    /** The phone listens; Robot is heard on [headset]'s media output (A2DP, LE, wired). */
    private fun phoneMic(headset: SeenDevice, outs: List<SeenDevice>): RoutePlan {
        val out = outs.firstOrNull { it.samePhysical(headset) && it.type != RouteType.BT_SCO } ?: headset
        return RoutePlan(null, MicRoute.PHONE, CaptureSource.VOICE_RECOGNITION, null, out, PlaybackUsage.ASSISTANT)
    }

    /**
     * Car mode: the car's call route (SCO) for its microphone and Robot's voice. After "hey robot" ([wakeInCar]) the
     * phone microphone keeps the question (the pre-roll would be lost in a 1 s route switch), and Robot still answers
     * through the car as a call. Without "Speak through the car as a call", the car's media output (A2DP), which plays
     * only if the car's source is Bluetooth.
     */
    private fun carPlan(car: SeenDevice, outs: List<SeenDevice>, wakeInCar: Boolean, asCall: Boolean): RoutePlan {
        val sco = outs.firstOrNull { it.samePhysical(car) && it.type == RouteType.BT_SCO }
        val a2dp = outs.firstOrNull { it.samePhysical(car) && it.type == RouteType.BT_A2DP }
        // The car's microphone is its call route; after "hey robot" the phone keeps the question (a communication device
        // set while listening would move VOICE_RECOGNITION to the car's microphone too).
        val listen = if (wakeInCar) null else sco
        val answer = if (asCall) sco else null
        val output = if (asCall) sco ?: a2dp ?: car else a2dp ?: sco ?: car
        return RoutePlan(
            communication = listen,
            mic = if (listen != null) MicRoute.SCO else MicRoute.PHONE,
            capture = if (listen != null) CaptureSource.VOICE_COMMUNICATION else CaptureSource.VOICE_RECOGNITION,
            input = if (listen != null) RouteType.BT_SCO else null,
            output = output,
            usage = if (answer != null) PlaybackUsage.VOICE_COMMUNICATION else PlaybackUsage.ASSISTANT,
            answerCommunication = answer,
        )
    }

    /**
     * The route didn't come up ([why]). With "Fall back to the phone mic": "I'll listen on the phone", said in the
     * headset, the capture back on the phone and Robot still heard in the headset over its media output. Otherwise:
     * "The headset microphone didn't connect", and the session ends. A headset that went away ends it either way.
     */
    fun onFailed(plan: RoutePlan, why: RouteFail, settings: HeadsetSettings, devices: List<SeenDevice>): RouteFallback {
        val headset = plan.output ?: plan.communication
        if (why == RouteFail.GONE || headset == null || !settings.fallbackToPhoneMic) return RouteFallback.End(NO_HEADSET_MIC)
        val media = devices.firstOrNull { it.sink && it.samePhysical(headset) && it.type != RouteType.BT_SCO } ?: return RouteFallback.End(NO_HEADSET_MIC)
        return RouteFallback.PhoneMic(RoutePlan(null, MicRoute.PHONE, CaptureSource.VOICE_RECOGNITION, null, media, PlaybackUsage.ASSISTANT), ON_PHONE)
    }

    /** The routed input matches the plan: the capture really comes from the headset (else [RouteFail.WRONG_INPUT]). */
    fun inputOk(plan: RoutePlan, routedInput: SeenDevice?): Boolean = when (val want = plan.input) {
        null -> routedInput == null || routedInput.type == RouteType.BUILTIN_MIC
        else -> routedInput != null && routedInput.type == want && (plan.communication == null || routedInput.samePhysical(plan.communication))
    }

    fun cost(plan: RoutePlan): RouteCost = when (plan.mic) {
        MicRoute.SCO -> RouteCost(300..1_500, true, "Music pauses while Robot listens and answers; call-quality sound (8 to 32 kHz, by headset).")
        MicRoute.LE_AUDIO -> RouteCost(300..1_500, true, "Music pauses while Robot listens and answers.")
        MicRoute.WIRED, MicRoute.USB -> RouteCost(0..100, false, "No switch needed; music lowers while Robot talks.")
        MicRoute.PHONE -> RouteCost(0..100, false, "The phone listens: keep it near you. Music keeps its quality.")
    }
}
