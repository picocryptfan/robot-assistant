// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headsup

import app.heyrobot.core.BriefClock
import app.heyrobot.core.BriefText
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.CalendarFormat
import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.output.DeviceFamily
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.Dnd
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.Trust
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

// Pure. Idea 5's heads-up in your earbuds: "Dentist at 2:30, in 15 minutes", said once per event, only in headphones only
// you can hear. The app (assistant/headsup HeadsUpHub) reads the calendar, where Robot's voice would play and Do Not
// Disturb, and asks here whether to speak and what. Anything unsure is not private: nothing is said.

/** Quiet hours: nothing is said from [from] to [to] (across midnight). */
enum class HeadsUpQuiet(val id: String, val from: LocalTime?, val to: LocalTime?) {
    OFF("off", null, null),
    FROM_21("21-7", LocalTime.of(21, 0), LocalTime.of(7, 0)),
    FROM_22("22-7", LocalTime.of(22, 0), LocalTime.of(7, 0)),
    FROM_23("23-8", LocalTime.of(23, 0), LocalTime.of(8, 0));

    fun contains(t: LocalTime): Boolean {
        val f = from ?: return false
        val e = to ?: return false
        return if (f <= e) t >= f && t < e else t >= f || t < e
    }

    companion object {
        fun of(id: String?): HeadsUpQuiet? = entries.firstOrNull { it.id == id }
    }
}

/** Settings › Headset › Heads-up, in headsup.json (its own file; settings.json stays at schema 13). Off by default. */
data class HeadsUpSettings(val on: Boolean = false, val minutes: Int = DEFAULT_MINUTES, val quiet: HeadsUpQuiet = HeadsUpQuiet.FROM_22) {
    fun encode(): String = JSONObject().put("v", VERSION).put("on", on).put("minutes", minutes).put("quiet", quiet.id).toString()

    companion object {
        const val VERSION = 1
        const val FILE = "headsup.json"
        const val DEFAULT_MINUTES = 15

        /** The file's schema, or null when it isn't JSON or has none (a backup's entry is then refused). */
        fun version(text: String): Int? = try {
            JSONObject(text).let { if (it.has("v")) it.optInt("v", -1) else null }
        } catch (_: Exception) {
            null
        }
        val MINUTES = listOf(5, 10, 15, 30)

        /** A file it can't read, or one from a newer Robot, is off: never a heads-up you didn't turn on. */
        fun decode(text: String?): HeadsUpSettings = try {
            val o = JSONObject(text ?: return HeadsUpSettings())
            if (o.optInt("v", 0) != VERSION) HeadsUpSettings()
            else HeadsUpSettings(o.optBoolean("on", false), o.optInt("minutes", DEFAULT_MINUTES).takeIf { it in MINUTES } ?: DEFAULT_MINUTES,
                HeadsUpQuiet.of(o.optString("quiet")) ?: HeadsUpQuiet.FROM_22)
        } catch (_: Exception) {
            HeadsUpSettings()
        }
    }
}

/** The events already handled (spoken or held), by [HeadsUp.key], with their start: no titles. headsup-said.json. */
data class HeadsUpSaid(val keys: Map<String, Long> = emptyMap()) {
    operator fun contains(key: String): Boolean = key in keys
    fun plus(more: List<CalendarEvent>): HeadsUpSaid = HeadsUpSaid(keys + more.associate { HeadsUp.key(it) to it.begin })
    /** Drops events that started more than [HeadsUp.KEEP_MS] ago. */
    fun prune(nowMs: Long): HeadsUpSaid = HeadsUpSaid(keys.filterValues { it >= nowMs - HeadsUp.KEEP_MS })
    fun encode(): String = JSONObject().put("v", 1).put("said", JSONArray().apply { keys.forEach { (k, b) -> put(JSONObject().put("k", k).put("b", b)) } }).toString()

    companion object {
        fun decode(text: String?): HeadsUpSaid = try {
            val a = JSONObject(text ?: return HeadsUpSaid()).getJSONArray("said")
            HeadsUpSaid((0 until a.length()).associate { a.getJSONObject(it).let { o -> o.getString("k") to o.getLong("b") } })
        } catch (_: Exception) {
            HeadsUpSaid()
        }
    }
}

/** Why a heads-up wasn't said (the voice log's code; never the event). */
enum class HeadsUpHold(val code: String) { OFF("off"), CALL("call"), DND("dnd"), QUIET("quiet"), NOT_PRIVATE("not_private") }

/** The phone when an event's moment comes: where Robot's voice would play ([routed]; null when it can't say), car mode's car. */
data class HeadsUpFacts(val routed: SeenDevice?, val carConnected: Boolean, val dnd: Dnd, val callActive: Boolean, val locked: Boolean)

object HeadsUp {
    /** A check at least this often while it's on, so a new or moved event is caught. */
    const val RECHECK_MS = 60 * 60_000L
    /** An event this close to its start (or past it) gets no heads-up. */
    const val TOO_LATE_MS = 60_000L
    const val KEEP_MS = 2 * 24 * 60 * 60_000L

    /** Worn devices: the only kinds a heads-up may play on, and only when [private] says so. */
    private val WORN = setOf(RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET, RouteType.BT_A2DP,
        RouteType.BT_SCO, RouteType.BLE_HEADSET, RouteType.HEARING_AID)

    fun key(e: CalendarEvent): String = e.key ?: "${e.kind.name.lowercase()}:${e.id}:${e.begin}"

    /**
     * Private headphones only: a wired or USB headset, hearing aids, or a Bluetooth headset you confirmed (DeviceMemory).
     * Never the phone's speaker or earpiece, a Bluetooth or LE speaker, a car (its class, remembered as one, or car mode
     * with its car), line out, an unknown device, or a Bluetooth headset never asked about.
     */
    fun private(routed: SeenDevice?, memory: DeviceMemory, carConnected: Boolean): Boolean {
        if (routed == null || carConnected) return false
        if (routed.type !in WORN || routed.btClass.neverPrivate || routed.btClass.carLike) return false
        return memory.trust(routed) == Trust.PRIVATE
    }

    /** The event's name may be said on the lock screen: only on a Bluetooth headset you confirmed (not one private by type). */
    fun namedWhileLocked(routed: SeenDevice?, memory: DeviceMemory): Boolean =
        routed != null && routed.family == DeviceFamily.BLUETOOTH && routed.type.privateByType == null && memory.trust(routed) == Trust.PRIVATE

    /** Null: speak. Otherwise why not, in this order: off, a call, Do Not Disturb, quiet hours, not private. */
    fun hold(s: HeadsUpSettings, f: HeadsUpFacts, memory: DeviceMemory, now: ZonedDateTime): HeadsUpHold? = when {
        !s.on -> HeadsUpHold.OFF
        f.callActive -> HeadsUpHold.CALL
        f.dnd.on -> HeadsUpHold.DND
        s.quiet.contains(now.toLocalTime()) -> HeadsUpHold.QUIET
        !private(f.routed, memory, f.carConnected) -> HeadsUpHold.NOT_PRIVATE
        else -> null
    }

    /** Timed events whose moment ([minutes] before) has come, not yet handled, at least a minute before they start; soonest first. */
    fun due(events: List<CalendarEvent>, nowMs: Long, minutes: Int, said: HeadsUpSaid): List<CalendarEvent> =
        events.filter { !it.allDay && it.begin - minutes * 60_000L <= nowMs && it.begin - nowMs >= TOO_LATE_MS && key(it) !in said }
            .sortedBy { it.begin }

    /** When to look next: the next event's moment, or [RECHECK_MS] from now, whichever is sooner. */
    fun nextAt(events: List<CalendarEvent>, nowMs: Long, minutes: Int, said: HeadsUpSaid): Long {
        val next = events.filter { !it.allDay && key(it) !in said }.map { it.begin - minutes * 60_000L }.filter { it > nowMs }.minOrNull()
        return minOf(next ?: Long.MAX_VALUE, nowMs + RECHECK_MS)
    }

    /** Minutes left, rounded, never more than the setting nor less than 1. */
    fun minutesLeft(e: CalendarEvent, nowMs: Long, minutes: Int): Int = ((e.begin - nowMs + 30_000L) / 60_000L).toInt().coerceIn(1, minutes)

    /** "Dentist at 2:30, in 15 minutes." Unnamed (locked, not a confirmed headset): "Something on your calendar in 15 minutes." */
    fun line(e: CalendarEvent, nowMs: Long, minutes: Int, now: ZonedDateTime, lang: Lang, named: Boolean): Phrase {
        val n = minutesLeft(e, nowMs, minutes)
        if (!named) return HandsFreeStrings.headsUpUnnamed(n)
        val title: Any = BriefText.clean(e.title, CalendarFormat.TITLE).ifEmpty { null } ?: BriefStrings.untitled()
        val t = Instant.ofEpochMilli(e.begin).atZone(now.zone).toLocalTime()
        return HandsFreeStrings.headsUpAt(n, "title" to title, "time" to if (lang == Lang.EN) BriefClock.en(t) else BriefClock.fr(t))
    }
}
