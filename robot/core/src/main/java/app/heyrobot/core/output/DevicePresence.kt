// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.DeviceKind
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.TriggerEvent

// Pure. Which remembered headsets and cars are connected now, from AudioManager's device list after every
// AudioDeviceCallback. One physical
// device comes and goes as several entries (A2DP, SCO, LE), and Bluetooth links flap (a case opened and closed, a car
// restarting its radio), so a device counts as gone only after a grace period ([graceMs]: 30 s for a car by default, as
// car mode ends, 5 s for a headset). Coming back within it is no event at all. This is for routines, car mode and
// headset mode starting and ending; stopping personal speech when a device goes is immediate and is OutputGuard's
// (it doesn't wait for the grace).

/** A remembered device connected, or went away for longer than its grace. */
sealed class PresenceEvent {
    abstract val device: RememberedDevice
    abstract val at: Long
    abstract val connected: Boolean

    data class Connected(override val device: RememberedDevice, override val at: Long) : PresenceEvent() {
        override val connected get() = true
    }

    data class Disconnected(override val device: RememberedDevice, override val at: Long) : PresenceEvent() {
        override val connected get() = false
    }
}

/**
 * [present]: remembered devices (by number) connected now, with when they came. [leaving]: devices that went, with
 * when, still within their grace.
 */
data class Presence(val present: Map<Int, Long> = emptyMap(), val leaving: Map<Int, Long> = emptyMap()) {
    /** Connected, or gone for less than the grace (car mode keeps going through a flap). */
    fun counts(number: Int): Boolean = number in present || number in leaving
}

object DevicePresence {
    const val HEADSET_GRACE_MS = 5_000L
    const val CAR_GRACE_MS = 30_000L

    fun defaultGrace(role: DeviceRole): Long = if (role == DeviceRole.CAR) CAR_GRACE_MS else HEADSET_GRACE_MS

    /**
     * The device list changed ([devices]: every output Android lists now, at [now]). A remembered device seen and not
     * already present (or leaving) connects; one present and no longer seen starts leaving. Two remembered devices that
     * look the same (a collision) give no event: Robot can't tell which one it is.
     */
    fun onDevices(state: Presence, memory: DeviceMemory, devices: List<SeenDevice>, now: Long,
        graceMs: (DeviceRole) -> Long = ::defaultGrace): Pair<Presence, List<PresenceEvent>> {
        val seen = devices.mapNotNull { (memory.match(it) as? DeviceMatch.One)?.device }.associateBy { it.number }
        val events = mutableListOf<PresenceEvent>()
        val present = state.present.toMutableMap()
        val leaving = state.leaving.toMutableMap()
        for ((n, d) in seen) {
            when {
                n in present -> Unit
                n in leaving -> { leaving.remove(n); present[n] = now }
                else -> { present[n] = now; events += PresenceEvent.Connected(d, now) }
            }
        }
        for (n in present.keys.toList()) if (n !in seen) { present.remove(n); leaving[n] = now }
        // A device forgotten while leaving is simply gone.
        leaving.keys.retainAll { memory.byNumber(it) != null }
        val (after, ended) = tick(Presence(present, leaving), memory, now, graceMs)
        return after to (events + ended)
    }

    /** Time passed: devices gone for at least their grace disconnect. */
    fun tick(state: Presence, memory: DeviceMemory, now: Long, graceMs: (DeviceRole) -> Long = ::defaultGrace): Pair<Presence, List<PresenceEvent>> {
        val events = mutableListOf<PresenceEvent>()
        val leaving = state.leaving.toMutableMap()
        for ((n, at) in state.leaving) {
            val d = memory.byNumber(n)
            if (d == null) { leaving.remove(n); continue }
            if (now - at >= graceMs(d.role)) { leaving.remove(n); events += PresenceEvent.Disconnected(d, now) }
        }
        return state.copy(leaving = leaving) to events
    }

    /** When [tick] should next run (the earliest grace to end), or null when nothing is leaving. */
    fun nextTick(state: Presence, memory: DeviceMemory, graceMs: (DeviceRole) -> Long = ::defaultGrace): Long? =
        state.leaving.mapNotNull { (n, at) -> memory.byNumber(n)?.let { at + graceMs(it.role) } }.minOrNull()
}

/**
 * For routines: whether [event] starts a RoutineTrigger.Device(type, deviceId, connect), passed
 * here as [kind] (its DeviceKind.id, "headset" or "car"), [deviceId] (RememberedDevice.id; null, a routine made by voice
 * before you picked one: any remembered device of that kind) and [connect]. Only remembered devices make events, so an
 * unknown device never starts a routine.
 */
object DeviceTriggers {
    fun matches(event: PresenceEvent, kind: String, deviceId: String?, connect: Boolean): Boolean =
        event.connected == connect && event.device.role.id == kind && (deviceId == null || deviceId == event.device.id)

    /** [matches] for the routine's own trigger: [DeviceKind] and [DeviceRole] are one by their ids. */
    fun matches(event: PresenceEvent, trigger: RoutineTrigger.Device): Boolean = matches(event, trigger.type.id, trigger.deviceId, trigger.connect)

    /** [event] as the routines' TriggerEvent (RoutineTriggers.fired): the device's role as a [DeviceKind], its id, connected or not. */
    fun event(event: PresenceEvent): TriggerEvent.Device? =
        DeviceKind.of(event.device.role.id)?.let { TriggerEvent.Device(it, event.device.id, event.connected) }

    /** The routine editor's device list for [kind]: remembered devices of that role, by number. */
    fun choices(memory: DeviceMemory, kind: String): List<RememberedDevice> = memory.devices.filter { it.role.id == kind }
}
