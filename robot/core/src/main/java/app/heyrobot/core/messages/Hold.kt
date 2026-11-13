// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.MessageStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

// Pure. 1.3, notifications held until a time (docs/plans/notify-hold-plan.md): "hold my shopping and news apps till
// noon" snoozes the picked apps' notifications until then, and at the end Robot gives one line of counts by app. Its
// settings are hold.json's (off, no app). What it holds is RAM only, in the main process: counts by app name, nothing of
// a notification's title or text. Never held: see [HoldRule].

/** hold.json: holding [on] (off until you turn it on) for the packages you picked one by one. */
data class HoldSettings(val on: Boolean = false, val apps: List<String> = emptyList()) {
    /** [pkg]'s notifications may be held: on, and picked. */
    fun picked(pkg: String?): Boolean = on && pkg != null && pkg in apps

    companion object {
        const val FILE = "hold.json"
        const val VERSION = 1

        /** A missing or damaged file: the defaults (off, no app). */
        fun parse(text: String?): HoldSettings = try {
            if (text.isNullOrBlank()) HoldSettings() else JSONObject(text).let { o ->
                val a = o.optJSONArray("apps") ?: JSONArray()
                HoldSettings(o.optBoolean("on", false), (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.distinct())
            }
        } catch (_: Exception) {
            HoldSettings()
        }

        fun write(s: HoldSettings): String =
            JSONObject().put("version", VERSION).put("on", s.on).put("apps", JSONArray(s.apps)).toString(2) + "\n"
    }
}

/** When a hold ends, as said: a clock time ([pm] null: a bare hour, the next of its two) or a number of minutes. */
sealed class HoldUntil {
    data class At(val hour: Int, val minute: Int, val pm: Boolean? = null) : HoldUntil()
    data class For(val minutes: Int) : HoldUntil()
}

/** A hold as asked: [apps] the names said (empty: every picked app), until [until]. */
data class HoldAsk(val apps: List<String>, val until: HoldUntil)

object HoldTime {
    const val MAX_MS = 12 * 3_600_000L

    /** When [u] ends after [now]: a clock time's next occurrence (a bare hour: the sooner of am and pm). */
    fun end(u: HoldUntil, now: ZonedDateTime): ZonedDateTime = when (u) {
        is HoldUntil.For -> now.plusMinutes(u.minutes.toLong())
        is HoldUntil.At -> {
            val h = u.hour % 12
            val hours = when {
                u.hour > 12 || (u.hour == 12 && u.pm == null) || (u.hour == 0) -> listOf(u.hour % 24)
                u.pm == true -> listOf(h + 12)
                u.pm == false -> listOf(h)
                else -> listOf(h, h + 12)
            }
            hours.map { hh ->
                val t = now.withHour(hh).withMinute(u.minute).withSecond(0).withNano(0)
                if (t.isAfter(now)) t else t.plusDays(1)
            }.minByOrNull { it.toInstant() }!!
        }
    }
}

/** What a notification shows of itself for [HoldRule]: its app, Android's category, and how it behaves. */
data class HoldCandidate(
    val pkg: String,
    val category: String?,
    val ongoing: Boolean = false,
    val conversation: Boolean = false,
    val media: Boolean = false,
    val fullScreen: Boolean = false,
    val ownProfile: Boolean = true,
    val title: String? = null,
    /** Android ranks it to make a sound or show a heads-up (IMPORTANCE_DEFAULT or more); with no ranking to tell, true. */
    val alerting: Boolean = true,
)

object HoldRule {
    /** Android's Notification.CATEGORY_* values that are never held: messages, calls, alarms, reminders, timers, media, system. */
    val NEVER = setOf("msg", "email", "call", "missed_call", "alarm", "reminder", "event", "stopwatch", "transport",
        "navigation", "sys", "err", "service", "location_sharing", "car_emergency", "car_warning")

    /** [n] may be snoozed now: a hold runs for its app, it's none of the never-held kinds, nor Robot's own, nor from someone in People. */
    fun mayHold(n: HoldCandidate, s: HoldSettings, holding: Set<String>, robot: String, knows: (String) -> Boolean): Boolean =
        s.picked(n.pkg) && n.pkg in holding && n.pkg != robot && n.ownProfile &&
            n.alerting && !n.ongoing && !n.conversation && !n.media && !n.fullScreen && n.category !in NEVER &&
            n.title?.trim()?.takeIf { it.isNotEmpty() }?.let { !knows(it) } != false
}

/** One app's count at the end of a hold. */
data class HeldCount(val app: String, val n: Int)

/**
 * A hold as kept on the phone (hold_now.json, main process): its apps, its end and the counts so far by app name, never a
 * title or a text. A restart or an app update reads it back and carries the hold on, or ends it with the usual line.
 */
data class HoldSaved(val apps: Set<String>, val end: Long, val counts: List<HeldCount>) {
    fun write(): String = JSONObject().put("v", 1).put("apps", JSONArray(apps.sorted())).put("end", end)
        .put("counts", JSONArray(counts.map { JSONObject().put("app", it.app).put("n", it.n) })).toString()

    companion object {
        const val FILE = "hold_now.json"

        /** A missing, damaged or newer file: null (no hold to carry on). */
        fun parse(t: String?): HoldSaved? = try {
            val o = JSONObject(t ?: return null)
            if (o.optInt("v") != 1) return null
            val a = o.getJSONArray("apps")
            val c = o.getJSONArray("counts")
            HoldSaved((0 until a.length()).map { a.getString(it) }.toSet(), o.getLong("end"),
                (0 until c.length()).map { c.getJSONObject(it).let { j -> HeldCount(j.getString("app"), j.getInt("n")) } })
        } catch (_: Exception) {
            null
        }
    }
}

/** What a saved hold does when Robot starts again: nothing, carry on until its end, or end now with the usual line. */
enum class HoldResume { NONE, CARRY_ON, END }

object HoldResumeRule {
    /** [now] in ms. An end past, or further off than a hold may last (a clock set back), ends the hold now. */
    fun at(s: HoldSaved?, now: Long): HoldResume = when {
        s == null || s.end <= 0L -> HoldResume.NONE
        now >= s.end || s.end - now > HoldTime.MAX_MS + 60_000L -> HoldResume.END
        else -> HoldResume.CARRY_ON
    }
}

/**
 * The hold running and what it held (counts by app name; HoldTap keeps a copy in [HoldSaved]). Thread-safe. [start]
 * replaces a running hold and its counts; [take] at its end gives the counts and clears them.
 */
class HoldBook {
    private var apps: Set<String> = emptySet()
    private var endAt = 0L
    private val counts = LinkedHashMap<String, Int>()

    @Synchronized fun start(pkgs: Set<String>, end: Long) {
        apps = pkgs
        endAt = end
        counts.clear()
    }

    /** What to keep on the phone, or null: no hold. */
    @Synchronized fun saved(): HoldSaved? = if (endAt <= 0L) null else HoldSaved(apps, endAt, counts.map { HeldCount(it.key, it.value) })

    /** A saved hold, after a restart: as it was. */
    @Synchronized fun restore(s: HoldSaved) {
        apps = s.apps
        endAt = s.end
        counts.clear()
        for (c in s.counts) counts[c.app] = c.n
    }

    @Synchronized fun holding(now: Long): Set<String> = if (now < endAt) apps else emptySet()

    @Synchronized fun endsAt(): Long = endAt

    /** The ms to snooze a notification from [pkg] for (and it's counted under [label]), or 0: not held now. */
    @Synchronized fun held(pkg: String, label: String, now: Long): Long {
        if (now >= endAt || pkg !in apps) return 0L
        counts[label] = (counts[label] ?: 0) + 1
        return endAt - now
    }

    /** The counts, newest app last, and the hold is over. */
    @Synchronized fun take(): List<HeldCount> = counts.map { HeldCount(it.key, it.value) }.also {
        counts.clear()
        apps = emptySet()
        endAt = 0L
    }

    @Synchronized fun clear() {
        take()
    }
}

/** How a hold asked for went: [apps] the app names held, until [end]; [unknown] names said that no picked app has. */
data class HoldResult(val code: HoldCode, val apps: List<String> = emptyList(), val end: LocalTime? = null, val unknown: List<String> = emptyList())

enum class HoldCode { HELD, OFF, NO_APPS, NO_ACCESS, UNKNOWN, TOO_LONG, SOMEONE_ELSE }

object HoldPlan {
    /**
     * [ask] against your settings: the packages to hold and when (ms), or why not. [labels]: each picked package's name.
     * A name said matches an app whose name, folded, equals it, starts with it or is its first word ("shopping": Shop).
     */
    fun plan(ask: HoldAsk, s: HoldSettings, access: Boolean, someoneElse: Boolean, now: ZonedDateTime, labels: (String) -> String): Pair<HoldResult, Set<String>> {
        if (someoneElse) return HoldResult(HoldCode.SOMEONE_ELSE) to emptySet()
        if (!s.on) return HoldResult(HoldCode.OFF) to emptySet()
        if (s.apps.isEmpty()) return HoldResult(HoldCode.NO_APPS) to emptySet()
        if (!access) return HoldResult(HoldCode.NO_ACCESS) to emptySet()
        val end = HoldTime.end(ask.until, now)
        if (Duration.between(now, end).toMillis() > HoldTime.MAX_MS) return HoldResult(HoldCode.TOO_LONG) to emptySet()
        val pkgs = if (ask.apps.isEmpty()) s.apps.toSet() else {
            val found = ask.apps.associateWith { name -> s.apps.filter { matches(name, labels(it)) } }
            val unknown = found.filterValues { it.isEmpty() }.keys.toList()
            if (unknown.isNotEmpty()) return HoldResult(HoldCode.UNKNOWN, unknown = unknown) to emptySet()
            found.values.flatten().toSet()
        }
        return HoldResult(HoldCode.HELD, s.apps.filter { it in pkgs }.map(labels), end.toLocalTime().withSecond(0).withNano(0)) to pkgs
    }

    fun matches(said: String, label: String): Boolean {
        val a = fold(said)
        val b = fold(label)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (a.length >= 3 && b.startsWith(a)) || (b.length >= 3 && a.startsWith(b)) || b.split(' ').first() == a
    }

    private fun fold(t: String): String = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun toJson(r: HoldResult): String = JSONObject().put("code", r.code.name).put("apps", JSONArray(r.apps))
        .put("end", r.end?.toString() ?: "").put("unknown", JSONArray(r.unknown)).toString()

    fun fromJson(t: String?): HoldResult? = try {
        val o = JSONObject(t ?: return null)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        HoldResult(HoldCode.valueOf(o.getString("code")), list("apps"), o.optString("end").takeIf { it.isNotEmpty() }?.let(LocalTime::parse), list("unknown"))
    } catch (_: Exception) {
        null
    }

    fun askJson(a: HoldAsk): String = JSONObject().put("apps", JSONArray(a.apps)).put("until", when (val u = a.until) {
        is HoldUntil.For -> JSONObject().put("for", u.minutes)
        is HoldUntil.At -> JSONObject().put("h", u.hour).put("m", u.minute).put("pm", u.pm?.toString() ?: "")
    }).toString()

    fun askOf(t: String?): HoldAsk? = try {
        val o = JSONObject(t ?: return null)
        val a = o.optJSONArray("apps") ?: JSONArray()
        val u = o.getJSONObject("until")
        val until = if (u.has("for")) HoldUntil.For(u.getInt("for")) else HoldUntil.At(u.getInt("h"), u.getInt("m"), u.optString("pm").toBooleanStrictOrNull())
        HoldAsk((0 until a.length()).map { a.getString(it) }, until)
    } catch (_: Exception) {
        null
    }
}

object HoldLine {
    private val page get() = UiStrings.inSettings(UiStrings.messages)
    const val MAX_APPS = 4

    /** What Robot says to a hold asked for. */
    fun reply(r: HoldResult): Phrase = when (r.code) {
        HoldCode.HELD -> MessageStrings.holdStarted("list" to Phrase.ListOf(r.apps.take(MAX_APPS).map(Phrase::Plain)), "time" to r.end!!)
        HoldCode.OFF -> MessageStrings.holdOff("page" to page)
        HoldCode.NO_APPS -> MessageStrings.holdNoApps("page" to page)
        HoldCode.NO_ACCESS -> MessageReader.noAccessText()
        HoldCode.UNKNOWN -> MessageStrings.holdUnknown("names" to Phrase.ListOf(r.unknown.map(Phrase::Plain)), "page" to page)
        HoldCode.TOO_LONG -> MessageStrings.holdTooLong()
        HoldCode.SOMEONE_ELSE -> MessageStrings.holdSomeoneElse()
    }

    /** The one line at a hold's end: "Your hold is over. 8 notifications came in: 3 in Shop and 5 in News." [countsOnly]: no app name. */
    fun over(list: List<HeldCount>, countsOnly: Boolean): Phrase {
        val apps = list.filter { it.n > 0 }
        if (apps.isEmpty()) return MessageStrings.holdOverNone()
        val total = apps.sumOf { it.n }
        if (countsOnly) return MessageStrings.holdOverCount(total)
        val named = apps.take(MAX_APPS).map { MessageStrings.missedIn("n" to it.n, "appName" to it.app) }
        val rest = apps.drop(MAX_APPS).sumOf { it.n }
        return MessageStrings.holdOver(total, "list" to Phrase.ListOf(if (rest > 0) named + MessageStrings.missedElsewhere("n" to rest) else named))
    }

    /** The lock screen's version of the end notification. */
    fun overPublic(): Phrase = MessageStrings.holdOverPublic()
}
