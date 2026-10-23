// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.FrTypo
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

// Pure. The morning brief: fixed sentences in English and Québec French (tu, "7 h 02", "cadran" elsewhere),
// assembled from the clock, the weather tool's data, today's calendar, the brief reminders and (0.7) the unread count.
// No model is involved, so a calendar title can only ever be read out, never followed; the same input always gives the
// same words, and every part has a length limit so the voice never reads a novel. Robot's own words through Gemma
// ("Phrasing") can come later on top of this.

/**
 * The brief's language: French or English, following the phone by default (Settings `briefLang` null). 1.1: core i18n's
 * [app.heyrobot.core.i18n.Lang], with the same constants, ids ("en", "fr", saved in routine_settings.json) and `of(tag)`.
 */

/**
 * The brief's parts (Settings `briefItems`; reorderable later). 1.4: [FILES], appended (an id an older Robot reads as none):
 * what Robot filed yesterday and the reminders you set from your files. It isn't one of Settings › Routines' switches:
 * Settings › Files' "Show in morning brief" adds it, and it is said only while the phone is unlocked ([BriefPrivacy]).
 */
enum class BriefItem(val id: String) {
    GREETING("greeting"), WEATHER("weather"), CALENDAR("calendar"), REMINDERS("reminders"), MESSAGES("messages"), CLOSING("closing"), FILES("files");

    companion object {
        /** Settings › Routines' parts, all on by default (Files' part is Settings › Files'). */
        val DEFAULTS: List<BriefItem> = entries.filter { it != FILES }

        /** The order they are said in: Files' part after your reminders, before the messages and the closing. */
        val ORDER: List<BriefItem> = listOf(GREETING, WEATHER, CALENDAR, REMINDERS, FILES, MESSAGES, CLOSING)

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
    val lang: Lang,
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
    /** 1.1, Phone lock (§9.2): when the phone opens, within the next 12 hours (LockSchedule.briefOpensAt); null: no line. */
    val phoneOpens: java.time.LocalTime? = null,
    /** 1.4, Robot Files' part (core files FileBrief), read only while the phone is unlocked; null: nothing about files. */
    val files: app.heyrobot.core.files.FilesBrief? = null,
)

/** One sentence (one chunk for the voice). [personal]: it says something from your calendar, notes, messages or the town. */
data class BriefLine(val item: BriefItem?, val text: String, val personal: Boolean, val shown: String = text)

/**
 * What is said now, and what waits for an unlock ("Finish when I unlock"). [text] is what the voice says; 1.1: [shownText]
 * is the same in the screen's form (French with its no-break spaces and « », which the voice never gets).
 */
data class Brief(val spoken: List<BriefLine>, val held: List<BriefLine>) {
    val text: String get() = spoken.joinToString(" ") { it.text }
    val heldText: String get() = held.joinToString(" ") { it.text }
    val shownText: String get() = spoken.joinToString(" ") { it.shown }
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
     * 1.1: [p] as the voice says it in [lang]: English as written, French without its typography (core VoiceForm). The
     * brief's sentences are phrases (core i18n BriefStrings); a time inside one is already in [lang]'s spoken words.
     */
    fun said(p: Phrase, lang: Lang): String = if (lang == Lang.EN) p.render(Langs.of(lang)) else p.render(Langs.of(lang).voice)

    /** [p] as the screen shows it in [lang] (« Rappel : … » with its no-break spaces). */
    fun shown(p: Phrase, lang: Lang): String = p.render(Langs.of(lang))

    private fun line(item: BriefItem?, p: Phrase, lang: Lang, personal: Boolean) = BriefLine(item, said(p, lang), personal, shown(p, lang))

    /**
     * The brief for [input], with the phone [locked] or not at lock [level] (BriefPrivacy.plan): the public part now,
     * "Unlock to hear your day" when something personal waits, and the rest held (composed as it will be heard, unlocked).
     * At "Nothing until I unlock" nothing is said until then.
     */
    fun compose(input: BriefInput, locked: Boolean = false, level: BriefLock = BriefLock.TIME_WEATHER): Brief {
        val plan = BriefPrivacy.plan(input.items.sortedBy { BriefItem.ORDER.indexOf(it) }, locked, level)
        val now = plan.spoken.flatMap { lines(it, input, plan.placeName) }
        val spoken = if (plan.unlockLine && now.isNotEmpty()) now + line(null, BriefStrings.unlockToHear(), input.lang, false) else now
        return Brief(bound(spoken), bound(plan.held.flatMap { lines(it, input, place = true) }))
    }

    /** The whole brief as heard unlocked. */
    fun full(input: BriefInput): String = compose(input).text

    fun unlockLine(lang: Lang): String = shown(BriefStrings.unlockToHear(), lang)

    /** The sentences of [item]; none when it has nothing to say (no reminders, no messages before 0.7). */
    fun lines(item: BriefItem, input: BriefInput, place: Boolean): List<BriefLine> {
        val lang = input.lang
        return when (item) {
            BriefItem.GREETING -> listOf(line(item, greetingText(input.now), lang, false), line(item, timeAndDateText(input.now, lang), lang, false)) +
                // 1.1: "Your phone opens at 8:00." Not personal: the lock's own screen says as much.
                listOfNotNull(input.phoneOpens?.let { t -> line(item, BriefStrings.phoneOpens("time" to if (lang == Lang.EN) BriefClock.en(t) else BriefClock.fr(t)), lang, false) })
            BriefItem.WEATHER -> {
                val town = if (place) input.weather?.place?.substringBefore(',')?.let { BriefText.clean(it, PLACE) }?.takeIf { it.isNotEmpty() } else null
                listOf(line(item, weatherText(input.weather, input.weatherMissing, lang, town), lang, town != null && input.weather != null && input.weatherMissing == null))
            }
            BriefItem.CALENDAR -> listOf(line(item, calendarText(input.events, input.now, lang), lang, true))
            BriefItem.REMINDERS -> remindersText(input.reminders, lang)?.let { listOf(line(item, it, lang, true)) } ?: emptyList()
            BriefItem.MESSAGES -> input.unread?.let { u -> listOf(line(item, messagesText(u), lang, false)) } ?: emptyList()
            // 1.4: shown without a merchant (the screen, a notification, History), said with one unless the file is private.
            BriefItem.FILES -> input.files?.let { f -> app.heyrobot.core.files.FileMoneyText.brief(f, input.now.toLocalDate())
                .map { BriefLine(item, said(it.said, lang), true, shown(it.shown, lang)) } } ?: emptyList()
            BriefItem.CLOSING -> listOf(line(item, closingText(input.now), lang, false))
        }
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

    fun greeting(now: ZonedDateTime, lang: Lang): String = shown(greetingText(now), lang)

    /** "Good morning.", « Bon matin. ». */
    fun greetingText(now: ZonedDateTime): Phrase = when (now.hour) {
        in 4..11 -> BriefStrings.goodMorning()
        in 12..16 -> BriefStrings.goodAfternoon()
        else -> BriefStrings.goodEvening()
    }

    /** "It's 7:02, Monday October 12." / "Il est 7 h 02, lundi 12 octobre." */
    fun timeAndDate(now: ZonedDateTime, lang: Lang): String = shown(timeAndDateText(now, lang), lang)

    /** [timeAndDate] as a phrase; the time in [lang]'s spoken words ("7 o'clock", « 7 h »). */
    fun timeAndDateText(now: ZonedDateTime, lang: Lang): Phrase {
        val t = now.toLocalTime()
        return BriefStrings.timeAndDate("time" to if (lang == Lang.EN) BriefClock.nowEn(t) else BriefClock.fr(t), "date" to now.toLocalDate())
    }

    fun closing(now: ZonedDateTime, lang: Lang): String = shown(closingText(now), lang)

    fun closingText(now: ZonedDateTime): Phrase = if (now.hour in 4..16) BriefStrings.goodDay() else BriefStrings.goodEveningEnd()

    // ------------------------------------------------------------------ weather

    /**
     * "Right now it's 4 degrees and partly cloudy; high of 12, 30 percent chance of rain this afternoon." /
     * "En ce moment, 4 degrés et partiellement nuageux; maximum de 12, 30 % de risque de pluie cet après-midi."
     * [town]: said only unlocked ("Right now in Montreal it's …"). Degrees in the phone region's unit, as the tool fetched.
     */
    fun weather(w: BriefWeather?, missing: BriefWeatherMissing?, lang: Lang, town: String?): String = shown(weatherText(w, missing, lang, town), lang)

    /** [weather] as a phrase (the conditions' words are core Wmo's). */
    fun weatherText(w: BriefWeather?, missing: BriefWeatherMissing?, lang: Lang, town: String?): Phrase {
        if (w == null || missing != null) return missingPhrase(missing ?: BriefWeatherMissing.UNAVAILABLE)
        val nowTemp = w.now?.temp?.roundToInt()
        val nowWords = w.now?.code?.let { Wmo.text(it) }
        val nowParts = listOfNotNull(nowTemp?.let { deg(it) }, nowWords)
        val d = w.today
        val dayParts = listOfNotNull(
            d?.high?.roundToInt()?.let { if (it < 0) BriefStrings.highOfMinus("n" to -it) else BriefStrings.highOf("n" to it) },
            d?.let { chance(it, w.units, w.wettest) },
        )
        val day = Phrase.Join(dayParts, ", ")
        return when {
            nowParts.isNotEmpty() -> {
                val now = Phrase.ListOf(nowParts)
                when {
                    town == null && dayParts.isEmpty() -> BriefStrings.now("now" to now)
                    town == null -> BriefStrings.nowAndDay("now" to now, "day" to day)
                    dayParts.isEmpty() -> BriefStrings.nowIn("town" to town, "now" to now)
                    else -> BriefStrings.nowInAndDay("town" to town, "now" to now, "day" to day)
                }
            }
            d != null && (dayParts.isNotEmpty() || Wmo.text(d.code) != null) -> {
                val parts = Phrase.Join(listOfNotNull(Wmo.text(d.code)) + dayParts, ", ")
                if (town == null) BriefStrings.today("day" to parts) else BriefStrings.todayIn("town" to town, "day" to parts)
            }
            else -> missingPhrase(BriefWeatherMissing.UNAVAILABLE)
        }
    }

    fun missingText(m: BriefWeatherMissing, lang: Lang): String = shown(missingPhrase(m), lang)

    /** "The weather isn't available right now.", "Weather skipped: routines may not go online." */
    fun missingPhrase(m: BriefWeatherMissing): Phrase = when (m) {
        BriefWeatherMissing.UNAVAILABLE -> BriefStrings.weatherUnavailable()
        BriefWeatherMissing.NOT_ONLINE -> BriefStrings.weatherOffline()
    }

    /** "30 percent chance of rain this afternoon", or null below [MIN_CHANCE] or without a chance. */
    private fun chance(d: WeatherDay, u: WeatherUnits, part: DayPart?): Phrase? {
        val c = d.chance?.takeIf { it >= MIN_CHANCE } ?: return null
        val rainMin = if (u == WeatherUnits.IMPERIAL) 0.01 else 0.1
        val snowMin = if (u == WeatherUnits.IMPERIAL) 0.05 else 0.1
        val rain = (d.rain ?: 0.0) + (d.showers ?: 0.0)
        val snow = d.snow ?: 0.0
        val kind = when {
            snow >= snowMin && rain >= rainMin -> BriefStrings.rainOrSnow()
            snow >= snowMin || (rain < rainMin && Wmo.snowy(d.code)) -> BriefStrings.snow()
            else -> BriefStrings.rain()
        }
        val whenText = when (part) {
            DayPart.MORNING -> BriefStrings.thisMorning()
            DayPart.AFTERNOON -> BriefStrings.thisAfternoon()
            DayPart.EVENING -> BriefStrings.thisEvening()
            DayPart.NIGHT -> BriefStrings.tonight()
            null -> return BriefStrings.chance("n" to c, "kind" to kind)
        }
        return BriefStrings.chanceWhen("n" to c, "kind" to kind, "when" to whenText)
    }

    /** "1 degree", "4 degrees", "minus 3 degrees" / "1 degré", "0 degré", "4 degrés", "moins 3 degrés" (French counts below 2 as one). */
    private fun deg(n: Int): Phrase = if (n < 0) BriefStrings.minusDegrees(-n) else BriefStrings.degrees(n)

    /** WMO conditions: Wmo's words (core Weather.kt), in [lang]. */
    fun words(code: Int?, lang: Lang): String? = Wmo.text(code)?.let { shown(it, lang) }

    // ------------------------------------------------------------------ calendar

    /**
     * Today's events that aren't over (all-day ones always): "You have 3 events today: standup at 9:30, lunch with Marie at
     * noon, dentist at 4." / "Tu as 3 événements aujourd'hui : standup à 9 h 30, dîner avec Marie à midi, dentiste à 16 h."
     * The count covers them all; the first [MAX_EVENTS] are named (titles cut at [TITLE] characters).
     */
    fun calendar(events: List<CalendarEvent>?, now: ZonedDateTime, lang: Lang): String = shown(calendarText(events, now, lang), lang)

    /** [calendar] as a phrase; the events' times in [lang]'s spoken words. */
    fun calendarText(events: List<CalendarEvent>?, now: ZonedDateTime, lang: Lang): Phrase {
        if (events == null) return BriefStrings.calendarUnreadable()
        val list = todays(events, now)
        val n = list.size
        val named = Phrase.Join(list.take(MAX_EVENTS).map { event(it, now, lang) }, ", ")
        return when {
            n == 0 -> BriefStrings.calendarEmpty()
            n <= MAX_EVENTS -> BriefStrings.events(n, "list" to named)
            else -> BriefStrings.eventsFirst("n" to n, "shown" to MAX_EVENTS, "list" to named)
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
    private fun event(e: CalendarEvent, now: ZonedDateTime, lang: Lang): Phrase {
        val title: Any = BriefText.clean(e.title, TITLE).ifEmpty { null } ?: BriefStrings.untitled()
        val start = Instant.ofEpochMilli(e.begin).atZone(now.zone)
        val end = Instant.ofEpochMilli(e.end).atZone(now.zone)
        val ms = now.toInstant().toEpochMilli()
        fun clock(t: LocalTime) = if (lang == Lang.EN) BriefClock.en(t) else BriefClock.fr(t)
        return when {
            e.allDay -> BriefStrings.eventAllDay("title" to title)
            e.begin < ms && end.toLocalDate() != now.toLocalDate() -> BriefStrings.eventOngoing("title" to title)
            e.begin < ms -> BriefStrings.eventUntil("title" to title, "time" to clock(end.toLocalTime()))
            else -> BriefStrings.eventAt("title" to title, "time" to clock(start.toLocalTime()))
        }
    }

    // ------------------------------------------------------------------ reminders, messages

    /** "Reminder: bring the passport." / "Rappels : X; Y; Z; et 2 autres." Null when there are none. */
    fun reminders(list: List<String>, lang: Lang): String? = remindersText(list, lang)?.let { shown(it, lang) }

    /** [reminders] as a phrase (the reminders are your words; in French the "; " between them takes its narrow space). */
    fun remindersText(list: List<String>, lang: Lang): Phrase? {
        val items = list.map { BriefText.clean(it, REMINDER) }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        if (items.size == 1) return BriefStrings.reminder("text" to items[0])
        val shown = items.take(MAX_REMINDERS)
        val more = items.size - shown.size
        val joined = shown.joinToString(if (lang == Lang.FR) FrTypo.FINE + "; " else "; ")
        return if (more > 0) BriefStrings.remindersMore(more, "list" to joined) else BriefStrings.reminders("list" to joined)
    }

    /**
     * "You have 5 unread messages: 3 in Signal and 2 in Cheogram." / "Tu as 5 messages non lus : 3 dans Signal et 2 dans
     * Cheogram." With one app: "You have 1 unread message in Signal." Counts and app names only, never a sender.
     */
    fun messages(u: UnreadCount, lang: Lang): String = shown(messagesText(u), lang)

    /** [messages] as a phrase. */
    fun messagesText(u: UnreadCount): Phrase {
        val apps = u.apps.filter { it.count > 0 }.map { it.copy(app = BriefText.clean(it.app, SENDER)) }.filter { it.app.isNotEmpty() }
        return when {
            u.total <= 0 -> BriefStrings.noUnread()
            apps.isEmpty() -> BriefStrings.unread(u.total)
            apps.size == 1 -> BriefStrings.unreadIn(u.total, "appName" to apps[0].app)
            else -> BriefStrings.unreadApps(u.total, "list" to Phrase.ListOf(apps.take(MAX_APPS).map { a -> BriefStrings.inApp("n" to a.count, "appName" to a.app) }))
        }
    }

    /** Apps named at most in the messages line (Robot reads five). */
    private const val MAX_APPS = 5
}

/** Clock times and dates as the brief says them, without the phone's locale (the same words on every phone). */
object BriefClock {
    /** "9:30", "4", "noon", "midnight": an event's time, on a 12-hour clock without am or pm, as people say it. */
    fun en(t: LocalTime): String = when {
        t.hour == 0 && t.minute == 0 -> "midnight"
        t.hour == 12 && t.minute == 0 -> "noon"
        else -> h12(t).let { h -> if (t.minute == 0) "$h" else String.format(Locale.US, "%d:%02d", h, t.minute) }
    }

    /** "7:02", "7 o'clock", "noon": what time it is. */
    fun nowEn(t: LocalTime): String =
        if (t.minute == 0 && t.hour != 0 && t.hour != 12) BriefStrings.oclock("h" to h12(t).toString()).render(Langs.EN) else en(t)

    /**
     * « 7 h 02 », « 16 h », « midi », « minuit »: Québec's 24-hour clock, with no-break spaces (core Fmt.clock's); the voice
     * is given « 7 heures 2 » (core VoiceForm).
     */
    fun fr(t: LocalTime): String = when {
        t.hour == 0 && t.minute == 0 -> "minuit"
        t.hour == 12 && t.minute == 0 -> "midi"
        else -> Fmt.clock(t, Lang.FR)
    }

    /** "Monday October 12" (core Fmt.day). */
    fun dateEn(d: LocalDate): String = Fmt.day(d, Lang.EN)

    /** "lundi 12 octobre", "jeudi 1er octobre". */
    fun dateFr(d: LocalDate): String = Fmt.day(d, Lang.FR)

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
 * "Remind me in the morning to bring the passport", "add to my brief: …": a tiny list read in the brief.
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

/** While the brief speaks: "next", "skip", "repeat", "stop", and "suivant", "passe", "répète", "stop". */
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
