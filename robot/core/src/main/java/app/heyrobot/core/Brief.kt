// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Pure. The morning brief (design §2.4): fixed sentences in English and Québec French (tu, "7 h 02", "cadran" elsewhere),
// assembled from the clock, the weather tool's data, today's calendar, the brief reminders and (0.7) the unread count.
// No model is involved, so a calendar title can only ever be read out, never followed; the same input always gives the
// same words, and every part has a length limit so the voice never reads a novel. Robot's own words through Gemma
// ("Phrasing") can come later on top of this.

/** The brief's language: French or English, following the phone by default (Settings `briefLang` null). */
enum class BriefLang(val id: String) {
    EN("en"), FR("fr");

    companion object {
        /** A language tag ("fr-CA", "en-US"); anything not French is English. */
        fun of(tag: String?): BriefLang = if (tag?.trim()?.lowercase(Locale.ROOT)?.startsWith("fr") == true) FR else EN
    }
}

/** The brief's parts in the order spoken (Settings `briefItems`; reorderable later). */
enum class BriefItem(val id: String) {
    GREETING("greeting"), WEATHER("weather"), CALENDAR("calendar"), REMINDERS("reminders"), MESSAGES("messages"), CLOSING("closing");

    companion object {
        val DEFAULTS: List<BriefItem> = entries.toList()

        fun of(id: String?): BriefItem? = entries.firstOrNull { it.id == id }
    }
}

/** A part of the day, for "30 percent chance of rain this afternoon" (from Open-Meteo's hourly data, when the app has it). */
enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

/** Why the brief has no weather: the lookup failed, or routines may not go online by themselves. */
enum class BriefWeatherMissing { UNAVAILABLE, NOT_ONLINE }

/**
 * The weather tool's data for the brief: now, today, the units of the phone's region, the place's name as the tool found
 * it ("Montreal, Quebec, Canada"; only the town is said, and only when unlocked) and the wettest part of the day.
 */
data class BriefWeather(val now: WeatherNow?, val today: WeatherDay?, val units: WeatherUnits, val place: String? = null,
    val wettest: DayPart? = null)

/**
 * 0.8, from 0.7's messages (core messages MessageUnread): how many messages Robot hasn't read to you, in all and by app
 * ([apps], most first). Counts only: never who wrote or what, so the line is the same on the lock screen.
 */
data class UnreadCount(val total: Int, val apps: List<AppUnread> = emptyList())

/** [count] unread messages in the app called [app] ("Signal"). */
data class AppUnread(val app: String, val count: Int)

data class BriefInput(
    val now: ZonedDateTime,
    val lang: BriefLang,
    val items: List<BriefItem> = BriefItem.DEFAULTS,
    /** Null: none ([weatherMissing] says why; unset means it wasn't available). */
    val weather: BriefWeather? = null,
    val weatherMissing: BriefWeatherMissing? = null,
    /** CalendarContract instances around today; null: Robot can't read the calendar (no permission). */
    val events: List<CalendarEvent>? = null,
    /** Today's brief reminders ([BriefReminders.due]). */
    val reminders: List<String> = emptyList(),
    /** Null until 0.7: nothing is said about messages. */
    val unread: UnreadCount? = null,
)

/** One sentence (one chunk for the voice). [personal]: it says something from your calendar, notes, messages or the town. */
data class BriefLine(val item: BriefItem?, val text: String, val personal: Boolean)

/** What is said now, and what waits for an unlock ("Finish when I unlock"). */
data class Brief(val spoken: List<BriefLine>, val held: List<BriefLine>) {
    val text: String get() = spoken.joinToString(" ") { it.text }
    val heldText: String get() = held.joinToString(" ") { it.text }
}

object BriefComposer {
    /** Each part (spoken now, held) at most this long: about a minute of speech. */
    const val MAX_CHARS = 1_200
    /** Events read out by name; the count covers all of them. */
    const val MAX_EVENTS = 5
    const val TITLE = CalendarFormat.TITLE
    const val MAX_REMINDERS = 3
    const val REMINDER = BriefReminders.MAX_TEXT
    const val PLACE = 40
    const val SENDER = 40
    /** A chance of rain or snow below this isn't mentioned. */
    const val MIN_CHANCE = 20

    /**
     * The brief for [input], with the phone [locked] or not at lock [level] (BriefPrivacy.plan): the public part now,
     * "Unlock to hear your day" when something personal waits, and the rest held (composed as it will be heard, unlocked).
     * At "Nothing until I unlock" nothing is said until then.
     */
    fun compose(input: BriefInput, locked: Boolean = false, level: BriefLock = BriefLock.TIME_WEATHER): Brief {
        val plan = BriefPrivacy.plan(input.items, locked, level)
        val now = plan.spoken.flatMap { lines(it, input, plan.placeName) }
        val spoken = if (plan.unlockLine && now.isNotEmpty()) now + BriefLine(null, unlockLine(input.lang), false) else now
        return Brief(bound(spoken), bound(plan.held.flatMap { lines(it, input, place = true) }))
    }

    /** The whole brief as heard unlocked. */
    fun full(input: BriefInput): String = compose(input).text

    fun unlockLine(lang: BriefLang): String = when (lang) {
        BriefLang.EN -> "Unlock to hear your day."
        BriefLang.FR -> "Déverrouille pour entendre ta journée."
    }

    /** The sentences of [item]; none when it has nothing to say (no reminders, no messages before 0.7). */
    fun lines(item: BriefItem, input: BriefInput, place: Boolean): List<BriefLine> = when (item) {
        BriefItem.GREETING -> listOf(BriefLine(item, greeting(input.now, input.lang), false), BriefLine(item, timeAndDate(input.now, input.lang), false))
        BriefItem.WEATHER -> {
            val town = if (place) input.weather?.place?.substringBefore(',')?.let { BriefText.clean(it, PLACE) }?.takeIf { it.isNotEmpty() } else null
            listOf(BriefLine(item, weather(input.weather, input.weatherMissing, input.lang, town), town != null && input.weather != null && input.weatherMissing == null))
        }
        BriefItem.CALENDAR -> listOf(BriefLine(item, calendar(input.events, input.now, input.lang), true))
        BriefItem.REMINDERS -> reminders(input.reminders, input.lang)?.let { listOf(BriefLine(item, it, true)) } ?: emptyList()
        BriefItem.MESSAGES -> input.unread?.let { u -> listOf(BriefLine(item, messages(u, input.lang), false)) } ?: emptyList()
        BriefItem.CLOSING -> listOf(BriefLine(item, closing(input.now, input.lang), false))
    }

    /** Whole lines while they fit in [MAX_CHARS]; a line that doesn't is left out (the closing still fits). */
    private fun bound(lines: List<BriefLine>): List<BriefLine> {
        var used = 0
        return lines.filter { l ->
            val add = l.text.length + if (used == 0) 0 else 1
            if (used + add > MAX_CHARS) false else { used += add; true }
        }
    }

    // ------------------------------------------------------------------ greeting, time, closing

    fun greeting(now: ZonedDateTime, lang: BriefLang): String = when (lang) {
        BriefLang.EN -> when (now.hour) { in 4..11 -> "Good morning."; in 12..16 -> "Good afternoon."; else -> "Good evening." }
        BriefLang.FR -> when (now.hour) { in 4..11 -> "Bon matin."; in 12..16 -> "Bon après-midi."; else -> "Bonsoir." }
    }

    /** "It's 7:02, Monday October 12." / "Il est 7 h 02, lundi 12 octobre." */
    fun timeAndDate(now: ZonedDateTime, lang: BriefLang): String {
        val t = now.toLocalTime()
        return when (lang) {
            BriefLang.EN -> "It's ${BriefClock.nowEn(t)}, ${BriefClock.dateEn(now.toLocalDate())}."
            BriefLang.FR -> "Il est ${BriefClock.fr(t)}, ${BriefClock.dateFr(now.toLocalDate())}."
        }
    }

    fun closing(now: ZonedDateTime, lang: BriefLang): String {
        val day = now.hour in 4..16
        return when (lang) {
            BriefLang.EN -> if (day) "Have a good day." else "Have a good evening."
            BriefLang.FR -> if (day) "Bonne journée." else "Bonne soirée."
        }
    }

    // ------------------------------------------------------------------ weather

    /**
     * "Right now it's 4 degrees and partly cloudy; high of 12, 30 percent chance of rain this afternoon." /
     * "En ce moment, 4 degrés et partiellement nuageux; maximum de 12, 30 % de risque de pluie cet après-midi."
     * [town]: said only unlocked ("Right now in Montreal it's …"). Degrees in the phone region's unit, as the tool fetched.
     */
    fun weather(w: BriefWeather?, missing: BriefWeatherMissing?, lang: BriefLang, town: String?): String {
        val en = lang == BriefLang.EN
        if (w == null || missing != null) return missingText(missing ?: BriefWeatherMissing.UNAVAILABLE, lang)
        val nowTemp = w.now?.temp?.roundToInt()
        val nowWords = w.now?.code?.let { words(it, lang) }
        val nowPart = listOfNotNull(nowTemp?.let { deg(it, lang) }, nowWords).joinToString(if (en) " and " else " et ").ifEmpty { null }
        val d = w.today
        val dayParts = listOfNotNull(
            d?.high?.roundToInt()?.let { if (en) "high of ${num(it, lang)}" else "maximum de ${num(it, lang)}" },
            d?.let { chance(it, w.units, w.wettest, lang) },
        )
        return when {
            nowPart != null -> {
                val head = if (en) "Right now" + (town?.let { " in $it" } ?: "") + " it's " else "En ce moment" + (town?.let { " à $it" } ?: "") + ", "
                head + nowPart + (if (dayParts.isEmpty()) "" else "; " + dayParts.joinToString(", ")) + "."
            }
            d != null && (dayParts.isNotEmpty() || words(d.code, lang) != null) -> {
                val parts = listOfNotNull(words(d.code, lang)) + dayParts
                (if (en) "Today" + (town?.let { " in $it" } ?: "") + ": " else "Aujourd'hui" + (town?.let { " à $it" } ?: "") + " : ") + parts.joinToString(", ") + "."
            }
            else -> missingText(BriefWeatherMissing.UNAVAILABLE, lang)
        }
    }

    fun missingText(m: BriefWeatherMissing, lang: BriefLang): String = when (m) {
        BriefWeatherMissing.UNAVAILABLE -> if (lang == BriefLang.EN) "The weather isn't available right now." else "La météo n'est pas disponible pour le moment."
        BriefWeatherMissing.NOT_ONLINE -> if (lang == BriefLang.EN) "Weather skipped: routines may not go online." else "Météo sautée : les routines ne peuvent pas aller en ligne."
    }

    /** "30 percent chance of rain this afternoon", or null below [MIN_CHANCE] or without a chance. */
    private fun chance(d: WeatherDay, u: WeatherUnits, part: DayPart?, lang: BriefLang): String? {
        val c = d.chance?.takeIf { it >= MIN_CHANCE } ?: return null
        val rainMin = if (u == WeatherUnits.IMPERIAL) 0.01 else 0.1
        val snowMin = if (u == WeatherUnits.IMPERIAL) 0.05 else 0.1
        val rain = (d.rain ?: 0.0) + (d.showers ?: 0.0)
        val snow = d.snow ?: 0.0
        val en = lang == BriefLang.EN
        val kind = when {
            snow >= snowMin && rain >= rainMin -> if (en) "rain or snow" else "pluie ou neige"
            snow >= snowMin || (rain < rainMin && Wmo.snowy(d.code)) -> if (en) "snow" else "neige"
            else -> if (en) "rain" else "pluie"
        }
        val whenText = part?.let {
            if (en) when (it) { DayPart.MORNING -> " this morning"; DayPart.AFTERNOON -> " this afternoon"; DayPart.EVENING -> " this evening"; DayPart.NIGHT -> " tonight" }
            else when (it) { DayPart.MORNING -> " ce matin"; DayPart.AFTERNOON -> " cet après-midi"; DayPart.EVENING -> " ce soir"; DayPart.NIGHT -> " cette nuit" }
        } ?: ""
        return if (en) "$c percent chance of $kind$whenText" else "$c % de risque de $kind$whenText"
    }

    /** "minus 3" / "moins 3". */
    private fun num(n: Int, lang: BriefLang): String = if (n < 0) (if (lang == BriefLang.EN) "minus ${-n}" else "moins ${-n}") else "$n"

    /** "1 degree", "4 degrees" / "1 degré", "0 degré", "4 degrés" (French counts below 2 as one). */
    private fun deg(n: Int, lang: BriefLang): String = when (lang) {
        BriefLang.EN -> num(n, lang) + if (abs(n) == 1) " degree" else " degrees"
        BriefLang.FR -> num(n, lang) + if (abs(n) < 2) " degré" else " degrés"
    }

    /** WMO conditions: Wmo's English words, or their French. */
    fun words(code: Int?, lang: BriefLang): String? = if (lang == BriefLang.EN) Wmo.words(code) else code?.let { WMO_FR[it] }

    private val WMO_FR = mapOf(
        0 to "dégagé", 1 to "plutôt dégagé", 2 to "partiellement nuageux", 3 to "couvert",
        45 to "brouillard", 48 to "brouillard givrant",
        51 to "faible bruine", 53 to "bruine", 55 to "forte bruine", 56 to "faible bruine verglaçante", 57 to "bruine verglaçante",
        61 to "faible pluie", 63 to "pluie", 65 to "forte pluie", 66 to "faible pluie verglaçante", 67 to "pluie verglaçante",
        71 to "faible neige", 73 to "neige", 75 to "forte neige", 77 to "neige en grains",
        80 to "faibles averses", 81 to "averses", 82 to "fortes averses", 85 to "faibles averses de neige", 86 to "averses de neige",
        95 to "orages", 96 to "orages avec grêle", 99 to "orages avec forte grêle",
    )

    // ------------------------------------------------------------------ calendar

    /**
     * Today's events that aren't over (all-day ones always): "You have 3 events today: standup at 9:30, lunch with Marie at
     * noon, dentist at 4." / "Tu as 3 rendez-vous aujourd'hui : standup à 9 h 30, dîner avec Marie à midi, dentiste à 16 h."
     * The count covers them all; the first [MAX_EVENTS] are named (titles cut at [TITLE] characters).
     */
    fun calendar(events: List<CalendarEvent>?, now: ZonedDateTime, lang: BriefLang): String {
        val en = lang == BriefLang.EN
        if (events == null) return if (en) "Robot can't read your calendar." else "Robot ne peut pas lire ton agenda."
        val list = todays(events, now)
        val n = list.size
        val named = list.take(MAX_EVENTS).joinToString(", ") { event(it, now, lang) }
        return when {
            n == 0 -> if (en) "Nothing on your calendar today." else "Rien à ton agenda aujourd'hui."
            n == 1 -> if (en) "You have 1 event today: $named." else "Tu as 1 rendez-vous aujourd'hui : $named."
            n <= MAX_EVENTS -> if (en) "You have $n events today: $named." else "Tu as $n rendez-vous aujourd'hui : $named."
            else -> if (en) "You have $n events today. The first $MAX_EVENTS: $named." else "Tu as $n rendez-vous aujourd'hui. Les $MAX_EVENTS premiers : $named."
        }
    }

    /** Today's events in [now]'s zone that aren't over yet, all-day ones first, then by start. */
    fun todays(events: List<CalendarEvent>, now: ZonedDateTime): List<CalendarEvent> {
        val day = now.toLocalDate()
        val range = DayRange(day.atStartOfDay(now.zone), day.plusDays(1).atStartOfDay(now.zone))
        val ms = now.toInstant().toEpochMilli()
        return CalendarFormat.inRange(events, range).filter { it.allDay || it.end > ms || (it.begin == it.end && it.begin >= ms) }
    }

    /** "standup at 9:30", "Thanksgiving all day", "meeting until 10:30" (under way), "conference ongoing" (ends another day). */
    private fun event(e: CalendarEvent, now: ZonedDateTime, lang: BriefLang): String {
        val en = lang == BriefLang.EN
        val title = BriefText.clean(e.title, TITLE).ifEmpty { if (en) "an untitled event" else "un rendez-vous sans titre" }
        val start = Instant.ofEpochMilli(e.begin).atZone(now.zone)
        val end = Instant.ofEpochMilli(e.end).atZone(now.zone)
        val ms = now.toInstant().toEpochMilli()
        val whenText = when {
            e.allDay -> if (en) "all day" else "toute la journée"
            e.begin < ms && end.toLocalDate() != now.toLocalDate() -> if (en) "ongoing" else "en cours"
            e.begin < ms -> if (en) "until ${BriefClock.en(end.toLocalTime())}" else "jusqu'à ${BriefClock.fr(end.toLocalTime())}"
            else -> if (en) "at ${BriefClock.en(start.toLocalTime())}" else "à ${BriefClock.fr(start.toLocalTime())}"
        }
        return "$title $whenText"
    }

    // ------------------------------------------------------------------ reminders, messages

    /** "Reminder: bring the passport." / "Rappels : X; Y; Z; et 2 autres." Null when there are none. */
    fun reminders(list: List<String>, lang: BriefLang): String? {
        val items = list.map { BriefText.clean(it, REMINDER) }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        val en = lang == BriefLang.EN
        val shown = items.take(MAX_REMINDERS)
        val more = items.size - shown.size
        val tail = if (more > 0) (if (en) "; and $more more" else "; et $more autre" + if (more > 1) "s" else "") else ""
        val head = when {
            items.size == 1 -> if (en) "Reminder: " else "Rappel : "
            else -> if (en) "Reminders: " else "Rappels : "
        }
        return head + shown.joinToString("; ") + tail + "."
    }

    /**
     * "You have 5 unread messages: 3 in Signal and 2 in Cheogram." / "Tu as 5 messages non lus : 3 dans Signal et 2 dans
     * Cheogram." With one app: "You have 1 unread message in Signal." Counts and app names only, never a sender.
     */
    fun messages(u: UnreadCount, lang: BriefLang): String {
        val en = lang == BriefLang.EN
        val apps = u.apps.filter { it.count > 0 }.map { it.copy(app = BriefText.clean(it.app, SENDER)) }.filter { it.app.isNotEmpty() }
        val head = when {
            u.total <= 0 -> return if (en) "No unread messages." else "Aucun message non lu."
            u.total == 1 -> if (en) "You have 1 unread message" else "Tu as 1 message non lu"
            else -> if (en) "You have ${u.total} unread messages" else "Tu as ${u.total} messages non lus"
        }
        val tail = when {
            apps.isEmpty() -> ""
            apps.size == 1 -> (if (en) " in " else " dans ") + apps[0].app
            else -> {
                val parts = apps.take(MAX_APPS).map { a -> "${a.count} " + (if (en) "in " else "dans ") + a.app }
                (if (en) ": " else " : ") + parts.dropLast(1).joinToString(", ") + (if (en) " and " else " et ") + parts.last()
            }
        }
        return "$head$tail."
    }

    /** Apps named at most in the messages line (Robot reads five). */
    private const val MAX_APPS = 5
}

/** Clock times and dates as the brief says them, without the phone's locale (the same words on every phone). */
object BriefClock {
    private val DAYS_EN = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val DAYS_FR = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val MONTHS_EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val MONTHS_FR = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    /** "9:30", "4", "noon", "midnight": an event's time, on a 12-hour clock without am or pm, as people say it. */
    fun en(t: LocalTime): String = when {
        t.hour == 0 && t.minute == 0 -> "midnight"
        t.hour == 12 && t.minute == 0 -> "noon"
        else -> h12(t).let { h -> if (t.minute == 0) "$h" else String.format(Locale.US, "%d:%02d", h, t.minute) }
    }

    /** "7:02", "7 o'clock", "noon": what time it is. */
    fun nowEn(t: LocalTime): String = if (t.minute == 0 && t.hour != 0 && t.hour != 12) "${h12(t)} o'clock" else en(t)

    /** "7 h 02", "16 h", "midi", "minuit": Québec's 24-hour clock. */
    fun fr(t: LocalTime): String = when {
        t.hour == 0 && t.minute == 0 -> "minuit"
        t.hour == 12 && t.minute == 0 -> "midi"
        t.minute == 0 -> "${t.hour} h"
        else -> String.format(Locale.US, "%d h %02d", t.hour, t.minute)
    }

    /** "Monday October 12". */
    fun dateEn(d: LocalDate): String = "${DAYS_EN[d.dayOfWeek.value - 1]} ${MONTHS_EN[d.monthValue - 1]} ${d.dayOfMonth}"

    /** "lundi 12 octobre", "jeudi 1er octobre". */
    fun dateFr(d: LocalDate): String = "${DAYS_FR[d.dayOfWeek.value - 1]} ${if (d.dayOfMonth == 1) "1er" else d.dayOfMonth} ${MONTHS_FR[d.monthValue - 1]}"

    fun dayEn(d: DayOfWeek): String = DAYS_EN[d.value - 1]
    fun dayFr(d: DayOfWeek): String = DAYS_FR[d.value - 1]

    private fun h12(t: LocalTime): Int = (t.hour % 12).let { if (it == 0) 12 else it }
}

/** Text Robot didn't write (a calendar title, a reminder, a sender's name), made fit to be read out. */
object BriefText {
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]+")

    /**
     * One line of plain words at most [max] characters: no Markdown, links or emoji (SpeechText.clean), no control
     * characters or line breaks, no closing punctuation (the brief adds its own). Read as words only: nothing in it is
     * ever a command.
     */
    fun clean(s: String, max: Int): String {
        val one = CONTROL.replace(Normalizer.normalize(s, Normalizer.Form.NFC), " ")
        return NoteSearch.cut(SpeechText.clean(one), max).trimEnd('.', '!', '?', ',', ';', ':', ' ').trim()
    }
}

// ---------------------------------------------------------------------------------------------- brief reminders

/** One brief reminder (`routines/reminders.json`): said in the brief of [day] and after; [readAt] once it was read. */
data class BriefReminder(val text: String, val day: LocalDate, val readAt: Long? = null)

/**
 * "Remind me in the morning to bring the passport", "add to my brief: …" (design §2.4): a tiny list read in the brief.
 * A read item stays until the end of that day, then goes.
 */
object BriefReminders {
    const val MAX = 20
    const val MAX_TEXT = 100
    const val VERSION = 1

    /**
     * The day a reminder said at [now] is for: "tomorrow morning" ([tomorrow]) is tomorrow; "in the morning" and "add to
     * my brief" are the next morning's brief (today's before 5:00, else tomorrow's).
     */
    fun dayFor(now: ZonedDateTime, tomorrow: Boolean): LocalDate =
        if (!tomorrow && now.toLocalTime() < LocalTime.of(5, 0)) now.toLocalDate() else now.toLocalDate().plusDays(1)

    /** [list] with [text] added for [day] (one line, at most [MAX_TEXT] characters; the same text once; at most [MAX]). */
    fun add(list: List<BriefReminder>, text: String, day: LocalDate): List<BriefReminder> {
        val t = BriefText.clean(text, MAX_TEXT)
        if (t.isEmpty()) return list
        val same = list.filterNot { it.readAt == null && it.text.equals(t, ignoreCase = true) }
        return (same + BriefReminder(t, day)).takeLast(MAX)
    }

    /** What the brief of [today] reads: reminders for today or before that weren't read before today. */
    fun due(list: List<BriefReminder>, today: LocalDate, zone: ZoneId): List<BriefReminder> =
        list.filter { !it.day.isAfter(today) && (it.readAt == null || readDay(it, zone) == today) }

    /** The due ones marked read at [nowMs] (they stay until the end of the day). */
    fun markRead(list: List<BriefReminder>, today: LocalDate, zone: ZoneId, nowMs: Long): List<BriefReminder> {
        val due = due(list, today, zone).toSet()
        return list.map { if (it in due && it.readAt == null) it.copy(readAt = nowMs) else it }
    }

    /** Without the ones read before [today]. */
    fun prune(list: List<BriefReminder>, today: LocalDate, zone: ZoneId): List<BriefReminder> =
        list.filter { it.readAt == null || !readDay(it, zone).isBefore(today) }

    private fun readDay(r: BriefReminder, zone: ZoneId): LocalDate = Instant.ofEpochMilli(r.readAt ?: 0).atZone(zone).toLocalDate()

    fun encode(list: List<BriefReminder>): String = JSONObject().put("v", VERSION).put("reminders", JSONArray().apply {
        for (r in list) put(JSONObject().put("text", r.text).put("day", r.day.toString()).apply { r.readAt?.let { put("read", it) } })
    }).toString()

    /** The reminders that can be read; a damaged or newer file gives none. */
    fun decode(json: String?): List<BriefReminder> = try {
        val o = JSONObject(json ?: return emptyList())
        if ((o.opt("v") as? Number)?.toInt() != VERSION) return emptyList()
        val a = o.getJSONArray("reminders")
        (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val text = RoutineCodec.str(r, "text")?.let { BriefText.clean(it, MAX_TEXT) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            BriefReminder(text, RoutineCodec.date(RoutineCodec.str(r, "day")) ?: return@mapNotNull null, RoutineCodec.long(r, "read"))
        }.takeLast(MAX)
    } catch (_: Exception) { emptyList() }
}

/** While the brief speaks (design §2.3): "next", "skip", "repeat", "stop", and "suivant", "passe", "répète", "stop". */
enum class BriefControl {
    NEXT, SKIP, REPEAT, STOP;

    companion object {
        private val WORDS = mapOf(
            "next" to NEXT, "suivant" to NEXT, "skip" to SKIP, "skip it" to SKIP, "passe" to SKIP, "repeat" to REPEAT, "repeat that" to REPEAT,
            "say that again" to REPEAT, "repete" to REPEAT, "stop" to STOP,
        )
        private val AROUND = setOf("hey robot", "robot", "please", "sil te plait", "ok", "okay")

        /** The control [text] is, as a whole utterance (the wake phrase and "please" around it allowed), or null. */
        fun of(text: String): BriefControl? {
            var t = " " + HelperRouter.plain(text).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
            for (a in AROUND.sortedByDescending { it.length }) t = t.replace(" $a ", " ")
            return WORDS[t.trim()]
        }
    }
}
