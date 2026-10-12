package app.heyrobot.core.output

import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.ParkCode
import app.heyrobot.core.car.ParkItem
import app.heyrobot.core.car.ParkList
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.writeAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// Pure (java.io, org.json). Where headset mode and car mode keep what they remember (0.8): files/robot/ next to
// settings.json, which stays schema 13 (their settings have files of their own, as routines' do), each change written
// whole under one lock both app processes take (the PeopleStore pattern): the main app's Settings › Headset and Settings ›
// Car mode, and the :voice process (a headset's first use, your answer to "Is it headphones only you can hear?", what car
// mode keeps for when you park).
//
//   devices.json   your headsets and cars (DeviceMemory, schema 1); routines' device triggers read it too
//   headset.json   Settings › Headset (HeadsetSettings) and the hands-free Safety & privacy rows (HandsFreeSafety)
//   car.json       Settings › Car mode (CarSettings without the places), and whether car mode is on at all (off)
//   places.json    Home and Work for "navigate home" (SavedPlace): never logged, never sent anywhere
//   park.json      what car mode kept for when you park, with what to ask again then (the panel offers it as a tip)
//
// A file that can't be read reads as its defaults (devices.json: no device; a damaged one is never written over until you
// change something, and then it is kept aside as devices.json.bad). Nothing here is logged: a device is its number.

/** Settings › Headset's file: the switches and the hands-free Safety & privacy rows. [noticeSeen]: the 0.8 line was closed. */
data class HeadsetFile(val settings: HeadsetSettings = HeadsetSettings(), val safety: HandsFreeSafety = HandsFreeSafety(), val noticeSeen: Boolean = false) {
    fun encode(): String = JSONObject().put("v", VERSION).put("headset", settings.toJson()).put("safety", safety.toJson()).put("notice_seen", noticeSeen).toString()

    companion object {
        const val VERSION = 1

        /** An absent or unreadable file is the defaults: the headset button on, everything else as the design says. */
        fun decode(json: String?): HeadsetFile {
            val o = try { json?.let(::JSONObject) } catch (_: Exception) { null } ?: return HeadsetFile()
            return HeadsetFile(HeadsetSettings.fromJson(o.optJSONObject("headset")), HandsFreeSafety.fromJson(o.optJSONObject("safety")),
                o.optBoolean("notice_seen", false))
        }
    }
}

/**
 * Settings › Car mode's file. [on]: "Car mode" itself, off until you turn it on (and it needs a car to start by itself);
 * [settings]: its rows, the places aside (places.json).
 */
data class CarFile(val on: Boolean = false, val settings: CarSettings = CarSettings()) {
    fun encode(): String = JSONObject().put("v", VERSION).put("on", on).put("car", settings.copy(home = null, work = null).toJson()).toString()

    companion object {
        const val VERSION = 1

        fun decode(json: String?): CarFile {
            val o = try { json?.let(::JSONObject) } catch (_: Exception) { null } ?: return CarFile()
            return CarFile(o.optBoolean("on", false), CarSettings.fromJson(o.optJSONObject("car")).copy(home = null, work = null))
        }
    }
}

/** Home and Work, for "navigate home" and "take me to work". */
data class Places(val home: SavedPlace? = null, val work: SavedPlace? = null) {
    override fun toString(): String = "Places(home=${home != null}, work=${work != null})"

    fun encode(): String = JSONObject().put("v", VERSION).apply {
        home?.let { put("home", place(it)) }
        work?.let { put("work", place(it)) }
    }.toString()

    companion object {
        const val VERSION = 1

        private fun place(p: SavedPlace) = JSONObject().put("label", p.label).put("lat", p.lat).put("lon", p.lon)

        private fun place(o: JSONObject?): SavedPlace? = o?.let { SavedPlace.of(it.optString("label"), it.optDouble("lat"), it.optDouble("lon")) }

        fun decode(json: String?): Places {
            val o = try { json?.let(::JSONObject) } catch (_: Exception) { null } ?: return Places()
            return Places(place(o.optJSONObject("home")), place(o.optJSONObject("work")))
        }
    }
}

/**
 * One thing kept for when you park: [item] (why, its card's words, when) and [ask], your words to ask again once parked
 * ("Text Bob I'm late"): the panel offers them, and a tap asks as if you typed them, through every gate again.
 */
data class Parked(val item: ParkItem, val ask: String? = null) {
    override fun toString(): String = "Parked(${item.code}, ask=${ask != null})"
}

/** park.json: what car mode kept, oldest first (at most [ParkList.MAX]). */
object ParkFile {
    const val VERSION = 1

    fun encode(list: List<Parked>): String = JSONObject().put("v", VERSION).put("items", JSONArray().apply {
        for (p in list) put(JSONObject().put("code", p.item.code.id).put("summary", p.item.summary).put("at", p.item.at).apply {
            p.ask?.let { put("ask", it) }
        })
    }).toString()

    fun decode(json: String?): List<Parked> {
        val a = try { json?.let(::JSONObject)?.optJSONArray("items") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val code = ParkCode.entries.firstOrNull { it.id == o.optInt("code") } ?: return@mapNotNull null
            val summary = o.optString("summary").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Parked(ParkItem(code, summary.take(MAX_SUMMARY), o.optLong("at")), o.optString("ask").takeIf { it.isNotBlank() }?.take(MAX_SUMMARY))
        }.takeLast(ParkList.MAX)
    }

    /** [list] with [p] (the same thing once, at most [ParkList.MAX], the oldest dropped). */
    fun add(list: List<Parked>, p: Parked): List<Parked> =
        (list.filterNot { it.item.code == p.item.code && it.item.summary == p.item.summary } + p).takeLast(ParkList.MAX)

    const val MAX_SUMMARY = 200
}

class HandsFreeStore(private val dir: File) {
    private fun f(name: String) = File(dir, name)

    /** Your headsets and cars; none while devices.json is missing, damaged or from a newer Robot. */
    fun devices(): DeviceMemory = (deviceFile() as? DeviceFile.Ok)?.memory ?: DeviceMemory()

    fun deviceFile(): DeviceFile {
        val text = read(f(DEVICES)) ?: return DeviceFile.Ok(DeviceMemory())
        return DeviceMemory.decode(text)
    }

    /**
     * Reads, changes and writes devices.json under the lock; null when it couldn't (a newer Robot's file, which is left
     * alone, or a failed write). A damaged file is kept aside as devices.json.bad before it is replaced.
     */
    fun editDevices(change: (DeviceMemory) -> DeviceMemory): DeviceMemory? = try {
        locked {
            val now = deviceFile()
            val memory = when (now) {
                is DeviceFile.Ok -> now.memory
                is DeviceFile.TooNew -> return@locked null
                DeviceFile.Damaged -> DeviceMemory()
            }
            val next = change(memory)
            if (next != memory || now !is DeviceFile.Ok) {
                if (now is DeviceFile.Damaged || (now as? DeviceFile.Ok)?.dropped?.let { it > 0 } == true) f(DEVICES).copyTo(f("$DEVICES.bad"), overwrite = true)
                writeAtomic(f(DEVICES), next.encode())
            }
            next
        }
    } catch (_: Exception) {
        null
    }

    fun headset(): HeadsetFile = HeadsetFile.decode(read(f(HEADSET)))

    fun editHeadset(change: (HeadsetFile) -> HeadsetFile): HeadsetFile? = update(::headset, change) { writeAtomic(f(HEADSET), it.encode()) }

    fun car(): CarFile = CarFile.decode(read(f(CAR)))

    fun editCar(change: (CarFile) -> CarFile): CarFile? = update(::car, change) { writeAtomic(f(CAR), it.encode()) }

    fun places(): Places = Places.decode(read(f(PLACES)))

    fun editPlaces(change: (Places) -> Places): Places? = update(::places, change) { writeAtomic(f(PLACES), it.encode()) }

    /** Car mode's settings with its places: what CarModeMachine, CarNavigate and the call flow read. */
    fun carSettings(): CarSettings = places().let { p -> car().settings.copy(home = p.home, work = p.work) }

    fun parked(): List<Parked> = ParkFile.decode(read(f(PARK)))

    fun editParked(change: (List<Parked>) -> List<Parked>): List<Parked>? = update(::parked, change) { writeAtomic(f(PARK), ParkFile.encode(it)) }

    private fun <T> update(read: () -> T, change: (T) -> T, write: (T) -> Unit): T? = try {
        locked {
            val now = read()
            val next = change(now)
            if (next != now) write(next)
            next
        }
    } catch (_: Exception) {
        null
    }

    private fun read(file: File): String? = try { if (file.isFile) file.readText(Charsets.UTF_8) else null } catch (_: Exception) { null }

    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        dir.mkdirs()
        FileChannel.open(f(LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val l = ch.lock()
            try { block() } finally { l.release() }
        }
    }

    companion object {
        const val DEVICES = "devices.json"
        const val HEADSET = "headset.json"
        const val CAR = "car.json"
        const val PLACES = "places.json"
        const val PARK = "park.json"
        const val LOCK_FILE = "handsfree.lock"
        private val LOCK = Any()

        /** Every file it writes (backups and "Delete everything" use the list). */
        val FILES = listOf(DEVICES, HEADSET, CAR, PLACES, PARK)
    }
}
