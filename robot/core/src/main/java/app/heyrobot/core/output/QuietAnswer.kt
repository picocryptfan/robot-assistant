// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.RoutineOutput
import app.heyrobot.core.RoutineRoute
import app.heyrobot.core.TriggerKind

// Pure. Quiet when the phone is silent: a question you typed while the phone is on silent or in Do Not Disturb gets its
// answer on screen only, so the phone stays quiet (a meeting, a bedside table). Vibrate isn't silent: many people keep
// the phone on vibrate all day (review-quiet-when-silent, 1). A question you said ("hey robot", the microphone) is still
// answered aloud, and so is a typed one while headphones or the car are where Robot's voice goes. Speak replies set to
// "Always", or a tap on it in the panel, keeps the voice: your own choice wins. PanelQuiet applies it.

/** The ringer as AudioManager.getRingerMode reports it: 0 silent, 1 vibrate, 2 normal (anything else reads as normal). */
enum class Ringer(val mode: Int) {
    SILENT(0), VIBRATE(1), NORMAL(2);

    companion object {
        fun of(mode: Int): Ringer = entries.firstOrNull { it.mode == mode } ?: NORMAL
    }
}

/** How a question came: typed (the text box, shared text) or said (the microphone, "hey robot"). */
enum class AskedBy { TYPED, SPOKEN }

object QuietAnswer {
    /**
     * Outputs worn or in your ear: an answer that goes there doesn't break the quiet of a silenced phone. A Bluetooth
     * output can also be a speaker: one DeviceMemory knows as not private (or its class says speaker) is the room.
     */
    val EARS = setOf(RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET, RouteType.BT_A2DP, RouteType.BT_SCO,
        RouteType.BLE_HEADSET, RouteType.HEARING_AID)

    /** The ringer on silent or Do Not Disturb on: the only states that quiet a typed answer (vibrate doesn't). */
    fun silenced(ringer: Ringer, dnd: Dnd): Boolean = ringer == Ringer.SILENT || dnd != Dnd.OFF

    /** An answer to [routed] reaches only your ears: a worn output that DeviceMemory ([trust]) doesn't know as not private. */
    fun toEars(routed: RouteType?, trust: Trust): Boolean = routed != null && routed in EARS && trust != Trust.NOT_PRIVATE

    /**
     * True when this turn's answer is shown only. [routed]: where Robot's voice would play now (null: unknown, as the
     * phone), with DeviceMemory's [trust] in it (as RoutineVoice.route; an unknown device stays as headphones). [car]:
     * car mode's car is connected. [speakTapped]: you turned Speak replies on in this panel yourself. [always]: Speak
     * replies is set to "Always".
     */
    fun quiet(asked: AskedBy, ringer: Ringer, dnd: Dnd, routed: RouteType?, trust: Trust, car: Boolean, speakTapped: Boolean,
        always: Boolean): Boolean {
        if (asked == AskedBy.SPOKEN || speakTapped || always || car) return false
        if (toEars(routed, trust)) return false
        return silenced(ringer, dnd)
    }

    /**
     * An unattended routine or the morning brief (review-quiet-when-silent, 4): true when it stays quiet, its words shown
     * in a notification instead. Quiet on silent or Do Not Disturb (never vibrate) when its voice would go to the room:
     * the phone itself, a Bluetooth speaker, anything not worn ([route], as RoutineVoice reads it). Headphones and car
     * mode's car keep the voice; "Don't speak" is quiet already. A run started by turning off your alarm still speaks: you
     * just woke the phone ([atAlarm]). A scheduled one ("At a time", TIME) doesn't, and neither does a missed alarm's run
     * at your first unlock, hours later maybe, nor the rest of a brief held for the unlock (fix-126-review 2).
     */
    fun routine(kind: TriggerKind, output: RoutineOutput, ringer: Ringer, dnd: Dnd, route: RoutineRoute, key: String?): Boolean {
        if (atAlarm(kind, key) || output == RoutineOutput.SILENT || route.car != null) return false
        if (toEars(route.routed?.type, route.trust)) return false
        return silenced(ringer, dnd)
    }

    /**
     * fix-126-review 2: a run of [kind] is the one your alarm's end started just now: ALARM with the occurrence's [key]
     * ("a:<ms>"). A missed alarm's run waits for the unlock and, like the rest of a brief held for it, runs without one.
     */
    fun atAlarm(kind: TriggerKind, key: String?): Boolean = kind == TriggerKind.ALARM && key != null && key.startsWith("a:")
}
