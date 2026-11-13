// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.CorrectStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import java.text.Normalizer
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * correct-last (idea 11, docs/plans/correct-last-plan.md): "undo that" / « annule ça » and "no, 6:45" / « non, 6 h 45 »
 * about Robot's own last action, with no model call. The app keeps the record ([Done], in memory, [WINDOW_MS]); this
 * reads the words ([parse]) and decides ([plan]): what to touch, or the one line said instead. Only what Robot made and
 * only as Robot left it ([Seen]); locked, nothing private (an event, a note, a card) is touched or named.
 */
object CorrectRouter {
    const val WINDOW_MS = 120_000L

    /** What you asked: put it back, or the one time just set changed to [hour]:[minute] ([pm] null: not said). */
    sealed class Ask {
        abstract val lang: Lang
        data class Undo(override val lang: Lang) : Ask()
        data class Change(val hour: Int, val minute: Int, val pm: Boolean?, override val lang: Lang) : Ask()
    }

    /** Robot's last action, made at [at]. */
    sealed class Done {
        abstract val at: Long
        data class Alarm(val hour: Int, val minute: Int, override val at: Long) : Done()
        data class Timer(val seconds: Long, override val at: Long) : Done()
        /** An event Robot wrote, as read back right after. */
        data class Event(val row: CalendarEvent, override val at: Long) : Done()
        /** A note Robot saved; [actionId], its card (whose Undo deletes it). */
        data class Note(val note: app.heyrobot.core.Note, val actionId: String?, override val at: Long) : Done()
        /** Any other card with an Undo (memory, lists, Robot Files, a deleted note): its own undo, with its own checks. */
        data class Card(val actionId: String, override val at: Long) : Done()
        /** A text sent or a call placed. */
        data class Sent(override val at: Long) : Done()
        /** Handed to another app (an email draft, the calendar's editor). */
        data class Elsewhere(override val at: Long) : Done()
        /** Anything else Robot did, which it can't undo from here; [alarm]: an alarm set at a time one was already set for. */
        data class Kept(override val at: Long, val alarm: Boolean = false, val hour: Int = 0, val minute: Int = 0) : Done()
    }

    /** Whether the thing is still as Robot left it (only events and notes can be checked). */
    enum class Seen { SAME, CHANGED, GONE }

    sealed class Plan {
        /** Nothing touched; [line] said. */
        data class Say(val line: Phrase) : Plan()
        data class DismissAlarm(val hour: Int, val minute: Int) : Plan()
        data class MoveAlarm(val from: Done.Alarm, val hour: Int, val minute: Int) : Plan()
        data class DeleteEvent(val id: Long) : Plan()
        data class MoveEvent(val row: CalendarEvent, val begin: Long, val end: Long) : Plan()
        data class DeleteNote(val id: String) : Plan()
        data class UndoCard(val actionId: String) : Plan()
    }

    /**
     * An alarm turn-off (review High 1 and A): the next alarm moved on, it didn't, or nothing was sent, since the next alarm
     * wasn't this one and Robot couldn't tell when it went.
     */
    enum class Off { GONE, STILL_ON, NOT_SENT, STOPPED } // review-e Low: STOPPED, a stop before the send

    /** Whether [next] (AlarmManager's next alarm clock) is the alarm at [hour]:[minute], so Robot can tell when it goes. */
    fun isNext(hour: Int, minute: Int, next: Long?, zone: ZoneId): Boolean =
        next != null && Instant.ofEpochMilli(next).atZone(zone).let { it.hour == hour && it.minute == minute }

    /** fix-batch-15 M1: what the next alarm shows once a move's new one was asked for. */
    enum class Landed { SEEN, HIDDEN, MISSING }

    /**
     * fix-batch-15 M1 (review-batch-15): SEEN, the next alarm is at [hour]:[minute]'s next time after [now] (within a minute;
     * fix-batch-15-lows a: not that clock time on a later day, a weekly one); HIDDEN, another rings before it, so Robot
     * can't tell; MISSING, neither: your clock didn't keep it.
     */
    fun landed(hour: Int, minute: Int, next: Long?, now: Long, zone: ZoneId): Landed {
        val today = Instant.ofEpochMilli(now).atZone(zone).withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        val due = (if (today.toInstant().toEpochMilli() > now) today else today.plusDays(1)).toInstant().toEpochMilli()
        if (next != null && kotlin.math.abs(next - due) < 60_000L) return Landed.SEEN
        return if (next != null && next < due) Landed.HIDDEN else Landed.MISSING
    }

    /** fix-batch-15 M1: a move once [from] went and [hour]:[minute] was asked for: "changed" unless Robot sees it isn't there. */
    fun setSaid(landed: Landed, from: Done.Alarm, hour: Int, minute: Int): Phrase =
        if (landed == Landed.MISSING) CorrectStrings.moveNotSeen("from" to EventText.clock(from.hour, from.minute), "to" to EventText.clock(hour, minute))
        else CorrectStrings.alarmChanged("time" to EventText.clock(hour, minute))

    /** What Robot says after asking to turn off [hour]:[minute] ([off] null: no clock took it): never more than it can tell. */
    fun offSaid(off: Off?, hour: Int, minute: Int): Phrase {
        val t = EventText.clock(hour, minute)
        return when (off) {
            null -> ToolStrings.noClockAlarms()
            Off.GONE -> ToolStrings.alarmOff("time" to t)
            Off.STILL_ON -> CorrectStrings.offStillOn("time" to t) // fix-alarm-moved: where to finish it
            Off.NOT_SENT -> CorrectStrings.offNotSent("time" to t)
            Off.STOPPED -> CorrectStrings.offStopped("time" to t)
        }
    }

    /** A move from [from] to [hour]:[minute] whose turn-off didn't go ([off] not GONE): nothing new was set (review Low D). */
    fun moveSaid(off: Off?, from: Done.Alarm, hour: Int, minute: Int): Phrase {
        val times = arrayOf("from" to EventText.clock(from.hour, from.minute), "to" to EventText.clock(hour, minute))
        return when (off) {
            null -> ToolStrings.noClockAlarms()
            Off.STILL_ON -> CorrectStrings.moveStillOn(*times)
            Off.STOPPED -> CorrectStrings.moveStopped(*times)
            else -> CorrectStrings.moveLeftOn(*times)
        }
    }

    /** review Low E: [from] went, then the clock didn't set [hour]:[minute]. */
    fun offNotSet(from: Done.Alarm, hour: Int, minute: Int): Phrase =
        CorrectStrings.moveOffOnly("from" to EventText.clock(from.hour, from.minute), "to" to EventText.clock(hour, minute))

    /** review-e Low: [from] went, then a stop came before [hour]:[minute] was set. */
    fun offStoppedNotSet(from: Done.Alarm, hour: Int, minute: Int): Phrase =
        CorrectStrings.moveOffStopped("from" to EventText.clock(from.hour, from.minute), "to" to EventText.clock(hour, minute))

    fun seen(e: Done.Event, now: CalendarEvent?): Seen = when {
        now == null -> Seen.GONE
        now.title != e.row.title || now.begin != e.row.begin || now.end != e.row.end || now.allDay != e.row.allDay || now.location != e.row.location -> Seen.CHANGED
        else -> Seen.SAME
    }

    fun seen(n: Done.Note, now: Note?): Seen = when {
        now == null -> Seen.GONE
        now.text != n.note.text || now.title != n.note.title -> Seen.CHANGED
        else -> Seen.SAME
    }

    fun plan(ask: Ask, last: Done?, now: Long, locked: Boolean, seen: Seen, zone: ZoneId): Plan {
        last ?: return Plan.Say(CorrectStrings.nothing())
        if (now - last.at > WINDOW_MS || now < last.at) return Plan.Say(CorrectStrings.tooOld())
        val change = ask as? Ask.Change
        // review 12 and C2: locked, nothing is touched or named, an alarm included (fail closed: a move or an undo of an
        // alarm sends nothing; unlocking is input Robot can't check). Setting a new alarm by voice isn't this path.
        if (locked) return Plan.Say(if (change != null) CorrectStrings.unlockChange() else CorrectStrings.unlockUndo())
        when (last) {
            is Done.Sent -> return Plan.Say(CorrectStrings.cantSent())
            is Done.Elsewhere -> return Plan.Say(CorrectStrings.cantElsewhere())
            is Done.Timer -> return Plan.Say(CorrectStrings.cantTimer())
            is Done.Kept -> return Plan.Say(when {
                !last.alarm -> CorrectStrings.cantKept()
                // review Low J: "no, 6:45" after a shared alarm says 6:45 wasn't set.
                change != null -> CorrectStrings.moveShared("to" to EventText.clock(hourOf(change, last.hour), change.minute))
                else -> CorrectStrings.cantAlarmShared()
            })
            is Done.Alarm -> return if (change == null) Plan.DismissAlarm(last.hour, last.minute)
                else hourOf(change, last.hour).let { h -> if (h == last.hour && change.minute == last.minute) Plan.Say(CorrectStrings.alarmChanged("time" to EventText.clock(h, change.minute))) else Plan.MoveAlarm(last, h, change.minute) }
            else -> {}
        }
        if (change != null && (last !is Done.Event || last.row.allDay)) return Plan.Say(CorrectStrings.cantChange())
        if (last is Done.Event || last is Done.Note) when (seen) {
            Seen.GONE -> return Plan.Say(CorrectStrings.goneSince())
            Seen.CHANGED -> return Plan.Say(CorrectStrings.changedSince())
            Seen.SAME -> {}
        }
        return when (last) {
            is Done.Event -> if (change == null) Plan.DeleteEvent(last.row.id) else {
                val start = Instant.ofEpochMilli(last.row.begin).atZone(zone)
                val at = start.with(LocalTime.of(hourOf(change, start.hour), change.minute))
                // review 9 and Low I, on the clock's hours (so the spring-forward and fall-back days hold): a morning time
                // 12 h or more before the old start is the next day ("no, 12:30 a.m." on a 23:00 event); an evening time more
                // than 12 h after a start before 06:00, the day before ("no, 11:30 p.m." on a 00:30 event). A tie goes forward.
                // review-d Medium: a daytime start keeps its day ("no, 7:30 p.m." on a 7:00 a.m. event), and a pick before now
                // goes a day later, so an event is never moved into the past. review-e Medium: the morning side has the same
                // limit (a pick before 06:00), so "no, 7 a.m." on a 7:00 p.m. event keeps its day.
                val local = at.toLocalDateTime()
                val from = start.toLocalDateTime()
                val day = when {
                    at.hour < 6 && !local.isAfter(from.minusHours(12)) -> at.plusDays(1)
                    at.hour >= 12 && from.hour < 6 && local.isAfter(from.plusHours(12)) -> at.minusDays(1)
                    else -> at
                }.let { if (it.toInstant().toEpochMilli() < now) it.plusDays(1) else it }
                val begin = day.toInstant().toEpochMilli()
                Plan.MoveEvent(last.row, begin, begin + (last.row.end - last.row.begin))
            }
            is Done.Note -> last.actionId?.let { Plan.UndoCard(it) } ?: Plan.DeleteNote(last.note.id)
            is Done.Card -> Plan.UndoCard(last.actionId)
            else -> Plan.Say(CorrectStrings.failed())
        }
    }

    /** The hour [c] means, next to the one Robot set ([was]): with no a.m. or p.m., the same half of the day. */
    fun hourOf(c: Ask.Change, was: Int): Int = when {
        c.pm == true -> if (c.hour < 12) c.hour + 12 else c.hour
        c.pm == false -> if (c.hour == 12) 0 else c.hour
        c.hour == 0 || c.hour > 12 -> c.hour
        c.hour == 12 -> if (was == 0) 0 else 12 // review 8: "no, 12:45" next to a 00:30 alarm
        was >= 12 -> c.hour + 12
        else -> c.hour
    }

    // l10n: not text (words Robot hears, folded: lower case, no accents or punctuation)
    private val UNDO_EN = setOf("undo", "undo that", "undo it", "undo this", "undo the last one", "take that back", "scratch that")
    private val UNDO_FR = setOf("annule", "annule ca", "annule le", "annule la", "annule cela", "defais ca", "defais", "annule la derniere") // l10n: not text (words Robot hears)
    private val POLITE = listOf(" please", " s il te plait", " stp") // l10n: not text (words Robot hears)
    private val LEAD_EN = listOf("no", "nope", "actually", "oops", "sorry", "wait") // l10n: not text (words Robot hears)
    private val LEAD_FR = listOf("non", "en fait", "plutot", "oups", "pardon", "attends") // l10n: not text (words Robot hears)
    private val FILL_EN = listOf("i meant", "i said", "make it", "make that", "change it to", "set it for", "set it to", "not that", "at", "for", "to") // l10n: not text (words Robot hears)
    private val FILL_FR = listOf("je voulais dire", "j ai dit", "mets la a", "mets le a", "mets a", "change pour", "plutot", "a", "pour") // l10n: not text (words Robot hears)

    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("(\\d)\\s*h\\s*(\\d{2})"), "$1 h $2").replace(Regex("(\\d)h\\b"), "$1 h")
        .replace(Regex("(\\d):(\\d{2})"), "$1h$2").replace(Regex("[^a-z0-9]+"), " ").trim()
        .replace(Regex("(\\d)h(\\d{2})"), "$1:$2")

    fun parse(said: String): Ask? {
        var t = fold(said)
        POLITE.forEach { if (t.endsWith(it)) t = t.removeSuffix(it).trim() }
        if (t in UNDO_EN) return Ask.Undo(Lang.EN)
        if (t in UNDO_FR) return Ask.Undo(Lang.FR)
        return change(t, LEAD_EN, FILL_EN, Lang.EN) ?: change(t, LEAD_FR, FILL_FR, Lang.FR)
    }

    private fun strip(t: String, words: List<String>): String? = words.firstOrNull { t == it || t.startsWith("$it ") }?.let { t.removePrefix(it).trim() }

    private fun change(t0: String, lead: List<String>, fill: List<String>, lang: Lang): Ask.Change? {
        var t = strip(t0, lead) ?: return null
        strip(t, lead)?.let { t = it } // "no, actually 7"
        strip(t, fill)?.let { t = it }
        val en = lang == Lang.EN
        val m = (if (en) Regex("^(\\d{1,2})(?::(\\d{2}))?(?: (am|a m|pm|p m|o clock|in the morning|in the evening|tonight))?$")
            else Regex("^(\\d{1,2})(?::(\\d{2})| h(?: (\\d{2}))?)(?: (du matin|du soir|de l apres midi|ce soir))?$")).find(t)
        val word = mapOf("noon" to 12, "midnight" to 0, "midi" to 12, "minuit" to 0)[t] // l10n: not text (words Robot hears)
        if (m == null && word == null) return spoken(t, lang)
        if (word != null) return Ask.Change(word, 0, pm = null, lang = lang).takeIf { (it.lang == Lang.EN) == (t == "noon" || t == "midnight") }
        val g = m!!.groupValues
        val hour = g[1].toInt()
        val minute = (if (en) g[2] else g[2].ifEmpty { g[3] }).ifEmpty { "0" }.toInt()
        val tail = if (en) g[3] else g[4]
        val pm = when (tail) { "pm", "p m", "in the evening", "tonight", "du soir", "de l apres midi", "ce soir" -> true; "am", "a m", "in the morning", "du matin" -> false; else -> null } // l10n: not text (words Robot hears)
        if (hour > 23 || minute > 59 || (pm != null && hour > 12)) return null
        // A bare "no, 7" in English is a time only with a clock word, or with nothing after the number.
        return Ask.Change(hour, minute, pm, lang)
    }

    private val ANY_DAY = java.time.ZonedDateTime.of(2026, 1, 5, 12, 0, 0, 0, ZoneId.of("UTC"))
    private val AM = Regex("\\b(am|a m|morning|matin)\\b") // l10n: not text (words Robot hears)

    /**
     * review Medium 7: "six forty-five", "quarter to seven", « sept heures »: all that's left read as one clock time (core
     * WhenParser, both languages). Not marked a.m. or p.m., it keeps the alarm's half of the day, as the digits do.
     */
    private fun spoken(t: String, lang: Lang): Ask.Change? {
        val m = WhenParser.reads(t, ANY_DAY, lang)?.takeIf { it.clock && !it.dated && !it.relative } ?: return null
        val at = m.span.start.toLocalTime()
        val h = at.hour
        if (!m.marked) return Ask.Change(if (h % 12 == 0) 12 else h % 12, at.minute, pm = null, lang = lang)
        val am = AM.containsMatchIn(t)
        return Ask.Change(h, at.minute, pm = when { am -> false; h > 12 -> true; else -> null }, lang = lang)
    }
}
