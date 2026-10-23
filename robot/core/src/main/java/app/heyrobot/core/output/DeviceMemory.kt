// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import org.json.JSONArray
import org.json.JSONObject

// Pure (org.json). The headsets and cars Robot remembers, kept in files/routines/devices.json next to routines.json (the store, an
// atomic write under the lock both processes take, comes with the wiring). A remembered device is its family, its
// product name and the last two bytes of its address; with BLUETOOTH_CONNECT also its whole address, which then wins
// (a renamed headset stays itself). The voice log names a device only by [RememberedDevice.number] ("device #2").
//
// Routines wait for "a headset or car connecting or disconnecting" with
// RoutineTrigger.Device(type, deviceId, connect): [DeviceRole.id] is the same string as its DeviceKind.id ("headset",
// "car"), [RememberedDevice.id] is the deviceId it stores, and DeviceTriggers.matches (DevicePresence.kt) answers whether
// an event starts such a routine, a null deviceId meaning any remembered device of that role.

/** What a remembered device is to Robot. The ids are the routines' DeviceKind ids. */
enum class DeviceRole(val id: String) {
    HEADSET("headset"), CAR("car");

    companion object {
        fun of(id: String?): DeviceRole? = entries.firstOrNull { it.id == id }
    }
}

/** Whether personal sentences may play on a device. */
enum class Trust {
    /** Worn by you: wired, USB, earpiece, hearing aids, or a Bluetooth headset you confirmed. */
    PRIVATE,
    /** Heard by others: the speaker, a Bluetooth speaker, a car, a device you said No to. */
    NOT_PRIVATE,
    /** A Bluetooth headset never asked about (or renamed): Robot asks once before personal speech plays there. */
    ASK,
}

/**
 * One remembered device. [number]: stable, never reused (the voice log's "device #n"). [name]: the product name as last
 * seen. [tail]: "EE:FF". [address]: the whole address, only when Robot held BLUETOOTH_CONNECT. [private]: your answer to
 * "Is it headphones only you can hear?" (null: never asked; a car is always false). [enabled]: headset mode, or car mode,
 * for it (a routine may still wait for it when off). Times are wall clock ms. [toString] leaves the name and address out.
 */
data class RememberedDevice(
    val number: Int,
    val role: DeviceRole,
    val family: DeviceFamily,
    val name: String,
    val tail: String? = null,
    val address: String? = null,
    val private: Boolean? = null,
    val enabled: Boolean = true,
    val added: Long = 0,
    val seen: Long = added,
) {
    /** The routines' deviceId (RoutineTrigger.Device), stable for as long as the device is remembered. */
    val id: String get() = "dev-$number" // l10n: not text (an id, never shown)

    override fun toString(): String = "RememberedDevice(#$number, role=$role, family=$family, private=$private, enabled=$enabled)" // l10n: not text (toString, for the logs)
}

/** How a device Android lists matched the remembered ones. */
sealed class DeviceMatch {
    data object None : DeviceMatch()

    /** [how]: by the whole address, by name and tail, or by tail only (a rename, or another device with the same tail). */
    data class One(val device: RememberedDevice, val how: MatchHow) : DeviceMatch()

    /** Two remembered devices with the same name and tail: Robot can't tell which. */
    data class Several(val list: List<RememberedDevice>) : DeviceMatch()

    val devices: List<RememberedDevice> get() = when (this) {
        None -> emptyList()
        is One -> listOf(device)
        is Several -> list
    }
}

enum class MatchHow {
    /** The whole address (BLUETOOTH_CONNECT): the same device even after a rename. */
    ADDRESS,
    /** The same name and address tail (or, wired and USB, the same name). */
    NAME_AND_TAIL,
    /**
     * Only the tail and the family agree, the name doesn't: most likely you renamed it, but it could be another device
     * whose address ends the same. Its role carries over; its "private" answer doesn't (Robot asks again).
     */
    RENAMED,
}

/** What devices.json held. */
sealed class DeviceFile {
    /** [dropped]: entries that couldn't be read (left out, never guessed). */
    data class Ok(val memory: DeviceMemory, val dropped: Int = 0) : DeviceFile()
    data class TooNew(val version: Int) : DeviceFile()
    data object Damaged : DeviceFile()
}

/** The remembered devices, oldest first. Immutable: every change returns a new memory. */
data class DeviceMemory(val devices: List<RememberedDevice> = emptyList(), val nextNumber: Int = 1) {
    val headsets: List<RememberedDevice> get() = devices.filter { it.role == DeviceRole.HEADSET }
    val cars: List<RememberedDevice> get() = devices.filter { it.role == DeviceRole.CAR }

    fun byNumber(n: Int): RememberedDevice? = devices.firstOrNull { it.number == n }
    fun byId(id: String?): RememberedDevice? = devices.firstOrNull { it.id == id }

    /** Which remembered device [seen] is. */
    fun match(seen: SeenDevice): DeviceMatch {
        val same = devices.filter { it.family == seen.family }
        if (same.isEmpty() || seen.family == DeviceFamily.BUILTIN) return DeviceMatch.None
        if (seen.family != DeviceFamily.BLUETOOTH) {
            return one(same.filter { Addresses.sameName(it.name, seen.name) }, MatchHow.NAME_AND_TAIL)
        }
        val tail = seen.tail ?: return DeviceMatch.None
        val full = seen.fullAddress
        if (full != null) same.firstOrNull { it.address == full }?.let { return DeviceMatch.One(it, MatchHow.ADDRESS) }
        // A remembered whole address that differs is another device, whatever its name.
        val candidates = same.filter { it.tail == tail && (full == null || it.address == null) }
        val named = candidates.filter { Addresses.sameName(it.name, seen.name) }
        if (named.isNotEmpty()) return one(named, MatchHow.NAME_AND_TAIL)
        return if (candidates.size == 1) DeviceMatch.One(candidates[0], MatchHow.RENAMED) else DeviceMatch.None
    }

    private fun one(list: List<RememberedDevice>, how: MatchHow): DeviceMatch = when (list.size) {
        0 -> DeviceMatch.None
        1 -> DeviceMatch.One(list[0], how)
        else -> DeviceMatch.Several(list)
    }

    /**
     * Whether personal speech may play on [seen]. Heard by the room by its type (the speaker, a
     * Bluetooth or LE speaker, line out) or by its Bluetooth class (a loudspeaker, a car): never. A remembered car: never.
     * Your "No": never. Worn by its type (wired, USB headset, earpiece, hearing aid): private. A Bluetooth headset you
     * confirmed: private, unless it only matched by its tail (a rename): asked again. Two remembered devices that look
     * the same: private only if both are. Anything else: asked.
     */
    fun trust(seen: SeenDevice): Trust {
        if (seen.type.privateByType == false || seen.btClass.neverPrivate) return Trust.NOT_PRIVATE
        val m = match(seen)
        val found = m.devices
        if (found.any { it.role == DeviceRole.CAR || it.private == false }) return Trust.NOT_PRIVATE
        if (seen.type.privateByType == true) return Trust.PRIVATE
        return when (m) {
            DeviceMatch.None -> Trust.ASK
            is DeviceMatch.One -> if (m.device.private == true && m.how != MatchHow.RENAMED) Trust.PRIVATE else Trust.ASK
            is DeviceMatch.Several -> if (m.devices.all { it.private == true }) Trust.PRIVATE else Trust.ASK
        }
    }

    /** The remembered device [seen] is with [role], or null. A collision gives none. */
    fun find(seen: SeenDevice, role: DeviceRole? = null): RememberedDevice? =
        (match(seen) as? DeviceMatch.One)?.device?.takeIf { role == null || it.role == role }

    /**
     * [seen] remembered as [role] at [now] (from "Add your car", the first use of a headset, or your answer to the private
     * question, [private]). An existing match (not a collision) is updated: its name and whole address are learned, a
     * role change to car makes it not private. A new device gets the next number; null when [MAX] are already kept or
     * [seen] can't be remembered (the phone itself, a Bluetooth device without an address).
     */
    fun remember(seen: SeenDevice, role: DeviceRole, now: Long, private: Boolean? = null): Pair<DeviceMemory, RememberedDevice>? {
        if (seen.family == DeviceFamily.BUILTIN || seen.family == DeviceFamily.OTHER) return null
        if (seen.family == DeviceFamily.BLUETOOTH && seen.tail == null) return null
        val name = clean(seen.name)
        val m = match(seen)
        val newPrivate = if (role == DeviceRole.CAR) false else private
        if (m is DeviceMatch.One) {
            val d = m.device
            val keepPrivate = if (m.how == MatchHow.RENAMED) null else d.private
            val updated = d.copy(
                role = role, name = name.ifEmpty { d.name }, address = seen.fullAddress ?: d.address,
                private = if (role == DeviceRole.CAR) false else newPrivate ?: keepPrivate, seen = now,
            )
            return replace(updated) to updated
        }
        if (devices.size >= MAX) return null
        val d = RememberedDevice(nextNumber, role, seen.family, name, seen.tail, seen.fullAddress, newPrivate, enabled = true, added = now, seen = now)
        return copy(devices = devices + d, nextNumber = nextNumber + 1) to d
    }

    /** [seen] connected at [now]: its last-seen time, and its name and whole address when they can be learned safely. */
    fun saw(seen: SeenDevice, now: Long): DeviceMemory {
        val m = match(seen) as? DeviceMatch.One ?: return this
        val d = m.device
        val name = clean(seen.name)
        // A tail-only match keeps the name you know until you confirm it again; an address match learns the new name.
        val newName = if (m.how == MatchHow.ADDRESS && name.isNotEmpty()) name else d.name
        return replace(d.copy(name = newName, address = seen.fullAddress ?: d.address, seen = maxOf(d.seen, now)))
    }

    /** Your answer to "Is it headphones only you can hear?". A car stays not private. */
    fun answerPrivate(number: Int, private: Boolean): DeviceMemory =
        byNumber(number)?.let { replace(it.copy(private = if (it.role == DeviceRole.CAR) false else private)) } ?: this

    fun setEnabled(number: Int, enabled: Boolean): DeviceMemory = byNumber(number)?.let { replace(it.copy(enabled = enabled)) } ?: this

    /** Forget: gone from the list; its number is never given again. */
    fun forget(number: Int): DeviceMemory = copy(devices = devices.filterNot { it.number == number })

    private fun replace(d: RememberedDevice): DeviceMemory = copy(devices = devices.map { if (it.number == d.number) d else it })

    fun encode(): String = JSONObject().put("v", VERSION).put("next", nextNumber).put("devices", JSONArray().apply {
        for (d in devices) put(JSONObject().apply {
            put("n", d.number)
            put("role", d.role.id)
            put("family", d.family.id)
            put("name", d.name)
            d.tail?.let { put("tail", it) }
            d.address?.let { put("address", it) }
            d.private?.let { put("private", it) }
            put("enabled", d.enabled)
            put("added", d.added)
            put("seen", d.seen)
        })
    }).toString()

    companion object {
        const val VERSION = 1
        /** Remembered devices at most: Settings says "Forget one first" before the next. */
        const val MAX = 20
        const val MAX_NAME = 64

        private val TAIL = Regex("^[0-9A-F]{2}:[0-9A-F]{2}$")

        /** A product name kept as one short line. */
        fun clean(name: String): String = name.replace(Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]"), " ").trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

        fun decode(json: String?): DeviceFile {
            val o = try { JSONObject(json ?: return DeviceFile.Damaged) } catch (_: Exception) { return DeviceFile.Damaged }
            val v = (o.opt("v") as? Number)?.toInt() ?: return DeviceFile.Damaged
            if (v > VERSION) return DeviceFile.TooNew(v)
            if (v < 1) return DeviceFile.Damaged
            val a = o.optJSONArray("devices") ?: return DeviceFile.Damaged
            val out = mutableListOf<RememberedDevice>()
            var dropped = 0
            for (i in 0 until a.length()) {
                val d = a.optJSONObject(i)?.let(::device)
                if (d == null || out.any { it.number == d.number } || out.size >= MAX) dropped++ else out += d
            }
            val next = maxOf((o.opt("next") as? Number)?.toInt() ?: 1, (out.maxOfOrNull { it.number } ?: 0) + 1)
            return DeviceFile.Ok(DeviceMemory(out, next), dropped)
        }

        private fun device(x: JSONObject): RememberedDevice? = try {
            val family = DeviceFamily.of(x.opt("family") as? String)!!
            val role = DeviceRole.of(x.opt("role") as? String)!!
            val tail = (x.opt("tail") as? String)?.also { require(TAIL.matches(it)) }
            val address = (x.opt("address") as? String)?.also { require(Addresses.full(it) == it) }
            require(family != DeviceFamily.BLUETOOTH || tail != null)
            require(family != DeviceFamily.BUILTIN && family != DeviceFamily.OTHER)
            require(address == null || Addresses.tail(address) == tail)
            val n = (x.opt("n") as Number).toInt().also { require(it > 0) }
            val private = if (x.has("private")) x.get("private") as Boolean else null
            RememberedDevice(
                number = n, role = role, family = family, name = clean(x.opt("name") as? String ?: ""), tail = tail, address = address,
                private = if (role == DeviceRole.CAR) false else private,
                enabled = x.opt("enabled") as? Boolean ?: true,
                added = (x.opt("added") as? Number)?.toLong() ?: 0, seen = (x.opt("seen") as? Number)?.toLong() ?: 0,
            )
        } catch (_: Exception) { null }
    }
}
