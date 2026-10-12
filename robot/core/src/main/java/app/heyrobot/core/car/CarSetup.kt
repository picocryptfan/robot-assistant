package app.heyrobot.core.car

import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice

// Pure. Settings › Car mode › Add your car (design §4.3 "Setup", §4.4): the Bluetooth devices Android lists now (paired
// and connected: AudioManager's outputs, A2DP and SCO of one car counting once), cars Bluetooth says are cars first, and
// what car mode's switch and status rows say.

/** One device "Add your car" offers: [seen] as Android listed it, [suggested] when Bluetooth says car audio or hands-free. */
data class CarCandidate(val seen: SeenDevice, val suggested: Boolean) {
    override fun toString(): String = "CarCandidate(suggested=$suggested)"
}

object CarSetup {
    /**
     * The connected Bluetooth audio devices that aren't remembered as a car yet, one per physical device (its call entry,
     * SCO, before its media entry: a car's microphone is its call route), cars first. A device remembered as a headset can
     * still be added (it becomes a car, never private again).
     */
    fun candidates(devices: List<SeenDevice>, memory: DeviceMemory): List<CarCandidate> {
        val bt = devices.filter { it.sink && it.type.bluetooth && it.type != RouteType.BLE_BROADCAST && it.type != RouteType.HEARING_AID && it.tail != null }
        val one = mutableListOf<SeenDevice>()
        for (d in bt.sortedBy { if (it.type == RouteType.BT_SCO) 0 else 1 }) if (one.none { it.samePhysical(d) }) one += d
        return one.filter { memory.find(it, DeviceRole.CAR) == null }
            .map { CarCandidate(it, it.btClass.carLike) }
            .sortedByDescending { it.suggested }
    }

    /** Settings › Car mode's summary row: "Off", "On · no car added", "On · 1 car", "On · 2 cars". */
    fun summary(on: Boolean, memory: DeviceMemory): String {
        if (!on) return "Off"
        val n = memory.cars.count { it.enabled }
        return "On · " + when (n) { 0 -> "no car added"; 1 -> "1 car"; else -> "$n cars" }
    }

    /**
     * A place typed or pasted as coordinates ("45.5017, -73.5673", "geo:45.5017,-73.5673", Organic Maps' "Copy
     * coordinates"), or null when it isn't one on the globe. Addresses aren't looked up here: Settings › Car mode › Places
     * takes coordinates or where the phone is now.
     */
    fun coordinates(text: String): Pair<Double, Double>? {
        val m = Regex("""^\s*(?:geo:)?\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\?.*)?$""").find(text.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    /** Car mode can start by itself: on, with a car added (by voice or the screen's button it can start without one). */
    fun startsByItself(on: Boolean, s: CarSettings, memory: DeviceMemory): Boolean = on && s.startOnConnect && CarSettings.hasCar(memory)
}

/** "What's next" in the car (design §4.5 flow 4): today's next event only, in one short sentence. */
object WhatsNext {
    /** The event in [events] (CalendarEvent) still to come or under way at [now], said as one line; a line saying so when none is left. */
    fun line(events: List<app.heyrobot.core.CalendarEvent>, now: java.time.ZonedDateTime, lang: app.heyrobot.core.output.SpokenLang): String {
        val nowMs = now.toInstant().toEpochMilli()
        val e = events.filter { !it.allDay && it.end > nowMs }.minByOrNull { it.begin }
        val fr = lang == app.heyrobot.core.output.SpokenLang.FR
        if (e == null) return if (fr) "Plus rien à ton agenda aujourd'hui." else "Nothing else on your calendar today."
        val t = java.time.Instant.ofEpochMilli(e.begin).atZone(now.zone).toLocalTime()
        val title = e.title.replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { if (fr) "un rendez-vous" else "an event" }
        return when {
            e.begin <= nowMs -> if (fr) "En ce moment : $title." else "Now: $title."
            fr -> "Prochain : $title à ${t.hour} h ${"%02d".format(t.minute)}."
            else -> "Next: $title at ${java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(t)}."
        }
    }
}
