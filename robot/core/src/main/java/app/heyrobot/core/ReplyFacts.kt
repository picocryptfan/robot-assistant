// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

// Pure. 1.1 (docs/pm/handoff-dated-reminders.md point 1): Robot never says what its card contradicts. On a 1.1 phone the
// card read "Alarm set for 18:00" and the reply "I've set an alarm for 6:00 PM on November 20th": an alarm can't ring on a
// date at all. After an action that happened, a sentence of the reply that speaks of it (a claim, or one about an alarm, a
// timer, an event or a reminder) may name only the days, clock times and lengths its card shows; any other such sentence
// goes, and Robot's own line from the card takes its place (TurnRunner). For a timer, an alarm, an event and a note Robot
// writes the line itself in the first place (ActionOutcome.confirms).

/** The days, clock times and lengths (in seconds) one card shows. */
data class Shown(val dates: Set<LocalDate> = emptySet(), val times: Set<LocalTime> = emptySet(), val seconds: Set<Long> = emptySet())

object ReplyFacts {
    /**
     * What the card of [a] shows, at [now]: an alarm its time and the day it rings, a timer its length, an event its days,
     * times, length and alert; anything else the days, times and lengths in its words ([text]: its summary, body and line).
     */
    fun shown(a: Action, now: ZonedDateTime, text: String, lang: Lang): Shown = when {
        a is Action.SetAlarm -> Shown(setOf(AlarmWords.rings(a.hour, a.minute, now)), setOf(LocalTime.of(a.hour, a.minute)))
        a is Action.SetTimer -> Shown(seconds = setOf(a.seconds))
        a is Action.AddEvent && a.plan != null -> a.plan.let { p ->
            val last = if (p.allDay) p.end.toLocalDate().minusDays(1) else p.end.toLocalDate()
            val days = generateSequence(p.start.toLocalDate()) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(31).toSet()
            val times = if (p.allDay) setOfNotNull(p.alertAt?.toLocalTime()) else setOfNotNull(p.start.toLocalTime(), p.end.toLocalTime(), p.alertAt?.toLocalTime())
            Shown(days + setOfNotNull(p.alertAt?.toLocalDate()), times, if (p.allDay) emptySet() else setOf(ChronoUnit.SECONDS.between(p.start, p.end)))
        }
        else -> {
            val found = WhenParser.find(text, now, lang).filter { !it.relative }
            Shown(found.filter { it.dated }.map { it.span.start.toLocalDate() }.toSet(), found.mapNotNull { it.time }.toSet(), lengths(text).toSet())
        }
    }

    /**
     * [text] without the sentences that speak of an action and name a day, a clock time or a length that none of [shown]
     * shows (a weekday or a month and day may be any year's or week's that one does; "7" may be 07:00 or 19:00); null when
     * every sentence fits. A length counts only beside a card that shows one (a timer, an event).
     */
    fun scrub(text: String, shown: List<Shown>, now: ZonedDateTime, lang: Lang): String? {
        if (shown.isEmpty()) return null
        var dropped = false
        val out = text.split("\n").joinToString("\n") { line ->
            sentences(line).filterNot { s -> contradicts(s, shown, now, lang).also { if (it) dropped = true } }.joinToString(" ")
        }.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (dropped) out else null
    }

    /** A sentence of [text] speaks of an action (a claim, or an alarm, a timer, an event or a reminder, or adding or setting one). */
    fun speaksOfAction(text: String): Boolean = text.split("\n").any { line -> sentences(line).any(::about) }

    private fun about(sentence: String): Boolean {
        val p = plain(sentence)
        return ActionOutcome.claimsSentence(sentence) || ACTION_WORDS.containsMatchIn(p) || ACTION_VERBS.containsMatchIn(p)
    }

    /** [sentence] speaks of an action and names a day, time or length no card of [shown] shows. */
    fun contradicts(sentence: String, shown: List<Shown>, now: ZonedDateTime, lang: Lang): Boolean {
        if (!about(sentence)) return false
        val dates = shown.flatMap { it.dates }
        val times = shown.flatMap { it.times }
        for (m in WhenParser.find(sentence, now, lang)) {
            if (m.relative) continue
            if (m.dated && dates.none { sameDay(m, it) }) return true
            if (m.clock && times.none { m.sameTime(it.hour, it.minute) }) return true
        }
        val seconds = shown.flatMap { it.seconds }
        return seconds.isNotEmpty() && lengths(sentence).any { it !in seconds }
    }

    /** The day [m] names is [d]: the same date, or the same month and day, or (a weekday said) the same weekday. */
    private fun sameDay(m: Mention, d: LocalDate): Boolean {
        val said = m.span.start.toLocalDate()
        return said == d || (said.monthValue == d.monthValue && said.dayOfMonth == d.dayOfMonth) ||
            (said.dayOfWeek == d.dayOfWeek && WEEKDAY.containsMatchIn(plain(m.said)))
    }

    private val WEEKDAY = Regex("""\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thurs|fri|sat|sun|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\b""") // l10n: not text (the words a day is found by)

    /** Words that make a sentence about an alarm, a timer, an event or a reminder (English and French, accents stripped). */
    private val ACTION_WORDS = Regex("""\b(?:alarms?|timers?|reminders?|events?|appointments?|calendar|alarmes?|minuteries?|rappels?|evenements?|agenda|cadran|rendez vous|rendez-vous)\b""") // l10n: not text (the words an action is found by)

    /** Verbs that make a sentence about doing an action, done or to come ("I'll add it for Friday", « je règle »). */
    private val ACTION_VERBS = Regex("""\b(?:add|adding|set|setting|schedule|scheduling|remind|reminding|create|creating|save|saving|start|starting|ajoute|ajouter|regle|regler|programme|programmer|mets|cree|creer)\b""") // l10n: not text (the words an action is found by)

    // ------------------------------------------------------------------ lengths

    private val NUMBER_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, // l10n: not text (number words)
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, // l10n: not text (number words)
        "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, // l10n: not text (number words)
        "cinq" to 5, "dix" to 10, "quinze" to 15, "vingt" to 20, "trente" to 30, "quarante" to 40, "quarante cinq" to 45, "cinquante" to 50, // l10n: not text (number words)
        "soixante" to 60, "quatre vingt dix" to 90) // l10n: not text (number words)

    private val NUM = """\d+|${NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|")}""" // l10n: not text (a pattern)
    private val PART = Regex("""\b($NUM) (seconds?|secs?|secondes?|minutes?|mins?|hours?|hrs?|heures?)\b""") // l10n: not text (a pattern)
    private val JOIN = Regex("""^\s*(?:,|and|et)?\s*$""") // l10n: not text (a pattern)

    /** The lengths [text] names, in seconds ("5 minutes", "an hour and a half", « une demi-heure »); parts said together add up. */
    fun lengths(text: String): List<Long> {
        var s = plain(text).replace('-', ' ')
        s = s.replace(Regex("""\b(?:half an hour|une demi heure|demi heure)\b"""), "30 minutes") // l10n: not text (a pattern)
        s = s.replace(Regex("""\b($NUM) (hours?|heures?) (?:and a half|et demie)\b"""), "$1 $2 30 minutes") // l10n: not text (a pattern)
        val parts = PART.findAll(s).toList()
        val out = mutableListOf<Long>()
        var total = 0L
        var end = -1
        for (m in parts) {
            val n = m.groupValues[1].toLongOrNull() ?: NUMBER_WORDS.getValue(m.groupValues[1]).toLong()
            val unit = when (m.groupValues[2].first()) { 's' -> 1L; 'm' -> 60L; else -> 3600L }
            if (end >= 0 && JOIN.matches(s.substring(end, m.range.first))) total += n * unit
            else {
                if (end >= 0) out += total
                total = n * unit
            }
            end = m.range.last + 1
        }
        if (end >= 0) out += total
        return out
    }

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).replace('’', '\'')

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+""")).filter { it.isNotBlank() }
}
