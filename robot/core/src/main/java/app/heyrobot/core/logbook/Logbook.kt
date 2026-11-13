// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.logbook

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.LogbookStrings
import app.heyrobot.core.i18n.Phrase
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.time.ZonedDateTime

// Pure. What Robot says to the logbook's questions, all by code (docs/plans/spoken-log-plan.md §3): which entries match is
// worked out here, and "how many" is a count of them, never the model's guess; a total adds their numbers exactly
// (BigDecimal). The words come from the catalog (LogbookStrings), in English and Québec French.

/** How a logbook turn ended, for the voice log (VoiceEvent.LOGBOOK's b). */
enum class LogbookResult(override val id: Int, override val title: String) : app.heyrobot.core.LogCode {
    ANSWERED(1, "answered"),
    EMPTY(2, "nothing logged yet"),
    NONE_FOUND(3, "nothing matched"),
    LOCKED(4, "locked: unlock first"),
    FULL(5, "the logbook is full"),
    ADDED(6, "added"),
    ADDED_LOCKED(7, "added to the lock-screen journal"),
    DELETED(8, "deleted"),
    FAILED(9, "not written"),
    KEPT(10, "kept: you said no"),
}

data class LogbookAnswer(val text: Phrase, val result: LogbookResult) {
    override fun toString() = "LogbookAnswer($result)" // l10n: not text (log only: never an entry's words)
}

/** Your words and an entry's, compared by their stems: case and accents folded, small words dropped, endings taken off. */
object LogbookMatch {
    private val STOP = setOf("i", "me", "my", "mine", "the", "a", "an", "to", "of", "for", "at", "on", "in", "and", "did", "do", "does", "done", "have", "has",
        "had", "was", "were", "is", "it", "go", "went", "gone", "going", "some", "any", "with", "time", "times", "last",
        "je", "j", "ai", "mes", "mon", "ma", "le", "la", "les", "l", "un", "une", "des", "de", "d", "du", "a", "au", "aux", "et", "pour", "fois", "fait")
    private val IRREGULAR = mapOf("ran" to "run", "running" to "run", "took" to "take", "taken" to "take", "taking" to "take", "ate" to "eat", "eaten" to "eat",
        "slept" to "sleep", "drank" to "drink", "drunk" to "drink", "fed" to "feed", "paid" to "pay", "bought" to "buy", "swam" to "swim", "rode" to "ride",
        "ridden" to "ride", "wrote" to "write", "spent" to "spend", "made" to "make", "got" to "get", "gave" to "give", "saw" to "see", "seen" to "see",
        "pris" to "prendre", "prise" to "prendre", "couru" to "courir", "mange" to "manger", "bu" to "boire", "dormi" to "dormir")

    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun stems(s: String): Set<String> = fold(s).split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() && it !in STOP }.mapTo(LinkedHashSet(), ::stem)

    fun stem(w: String): String {
        var s = IRREGULAR[w] ?: w
        if (s.length > 4 && s.endsWith("ies")) s = s.dropLast(3) + "y"
        else if (s.length > 3 && (s.endsWith("s") || s.endsWith("x")) && !s.endsWith("ss")) s = s.dropLast(1)
        s = IRREGULAR[s] ?: s
        if (s.length > 5 && s.endsWith("ing")) s = undouble(s.dropLast(3))
        else if (s.length > 4 && s.endsWith("ed")) s = undouble(s.dropLast(2))
        if (s.length > 3 && s.endsWith("e")) s = s.dropLast(1)
        return s
    }

    private fun undouble(s: String) = if (s.length > 2 && s[s.length - 1] == s[s.length - 2] && s.last() !in "lsz") s.dropLast(1) else s

    /** A unit as one key: "km", "kilometres" and « kilomètres » alike. */
    fun unitKey(u: String): String = when (val f = stem(fold(u.trim()))) {
        "kilometr", "kilometer", "kilometre" -> "km"
        "mile", "mi" -> "mi"
        "hour", "hr", "h", "heur" -> "h"
        "minut", "min" -> "min"
        "kilo", "kilogram", "kilogramm" -> "kg"
        "pound", "lb" -> "lb"
        "litr", "liter", "l" -> "l"
        "step", "pa" -> "step"
        else -> f
    }

    /** [e] holds every stem of [subject] (none: nothing matches). */
    fun matches(subject: Set<String>, e: LogEntry): Boolean = subject.isNotEmpty() && stems(e.what).containsAll(subject)
}

object Logbook {
    private val S = LogbookStrings

    fun entryOf(cmd: LogbookCommand.Add, id: String, now: Long) = LogEntry(id, cmd.at, cmd.what, cmd.number, cmd.unit, now)

    /** [cmd]'s answer over [entries] (any order), counted from [now]. */
    fun answer(cmd: LogbookCommand, entries: List<LogEntry>, now: ZonedDateTime): LogbookAnswer = when (cmd) {
        is LogbookCommand.Recent -> if (entries.isEmpty()) empty() else {
            val items = entries.sortedByDescending { it.at }.take(LogbookLimits.RECENT).map { S.item("what" to it.what, "when" to whenOf(it.at, it.added, now)) }
            LogbookAnswer(S.recent("list" to Phrase.Join(items, "; ")), LogbookResult.ANSWERED)
        }
        is LogbookCommand.Last -> if (entries.isEmpty()) empty() else {
            val s = LogbookMatch.stems(cmd.subject)
            entries.filter { LogbookMatch.matches(s, it) }.maxByOrNull { it.at }?.let {
                LogbookAnswer(S.last("what" to it.what, "when" to whenOf(it.at, it.added, now)), LogbookResult.ANSWERED)
            } ?: LogbookAnswer(S.noneFound("what" to cmd.subject), LogbookResult.NONE_FOUND)
        }
        is LogbookCommand.Count -> if (entries.isEmpty()) empty() else count(cmd, entries, now)
        is LogbookCommand.Add, is LogbookCommand.DeleteLast -> error("not a question") // l10n: not text (a programming error)
    }

    private fun count(cmd: LogbookCommand.Count, entries: List<LogEntry>, now: ZonedDateTime): LogbookAnswer {
        val s = LogbookMatch.stems(cmd.subject)
        val all = entries.filter { LogbookMatch.matches(s, it) }
        if (all.isEmpty()) return LogbookAnswer(S.noneFound("what" to cmd.subject), LogbookResult.NONE_FOUND)
        val inPeriod = all.filter { cmd.period.holds(it.at) }
        val p = period(cmd.period)
        val unit = cmd.unit
        if (unit != null) {
            val withUnit = inPeriod.filter { it.number != null && it.unit != null && LogbookMatch.unitKey(it.unit) == unit }
            if (withUnit.isNotEmpty()) {
                val total = withUnit.fold(BigDecimal.ZERO) { a, e -> a + e.number!! }
                val said = withUnit.maxBy { it.at }.unit!!
                return LogbookAnswer(S.total(withUnit.size.toLong(), "x" to Arg.Dec(total, 6), "unit" to said, "period" to p), LogbookResult.ANSWERED)
            }
        }
        return LogbookAnswer(S.count(inPeriod.size.toLong(), "period" to p), LogbookResult.ANSWERED)
    }

    fun period(p: LogPeriod): Phrase = when (p.kind) {
        LogPeriod.Kind.ALL -> S.inAll()
        LogPeriod.Kind.TODAY -> S.periodToday()
        LogPeriod.Kind.YESTERDAY -> S.periodYesterday()
        LogPeriod.Kind.THIS_WEEK -> S.thisWeek()
        LogPeriod.Kind.LAST_WEEK -> S.lastWeek()
        LogPeriod.Kind.THIS_MONTH -> S.thisMonth()
        LogPeriod.Kind.LAST_MONTH -> S.lastMonth()
        LogPeriod.Kind.THIS_YEAR -> S.thisYear()
        LogPeriod.Kind.LAST_YEAR -> S.lastYear()
        LogPeriod.Kind.MONTH -> S.inMonth("month" to Arg.Month(p.month!!.year, p.month.monthValue))
    }

    /** When [at] was, from [now]: "today at 8:05", "yesterday", "Friday November 20 at 7:30". [added] equal to [at] or later. */
    fun whenOf(at: Long, added: Long, now: ZonedDateTime, timed: Boolean = true): Phrase {
        val t = Instant.ofEpochMilli(at).atZone(now.zone)
        val d = t.toLocalDate()
        val today = now.toLocalDate()
        val day = when {
            d == today -> S.today()
            d == today.minusDays(1) -> S.yesterday()
            d.year == today.year -> S.day("day" to Arg.Day(d))
            else -> S.dayYear("day" to Arg.Day(d), "year" to d.year.toString())
        }
        // A day said without a time is kept at noon ([LogbookRouter]): shown as the day alone.
        val noon = t.hour == 12 && t.minute == 0 && t.second == 0 && added != at
        return if (!timed || noon) day else S.at("day" to day, "time" to Arg.Clock(t.toLocalTime().withSecond(0).withNano(0)))
    }

    fun logged(e: LogEntry, timed: Boolean, now: ZonedDateTime, locked: Boolean): LogbookAnswer =
        LogbookAnswer((if (locked) S.loggedLocked else S.logged)("what" to e.what, "when" to whenOf(e.at, e.added, now, timed)),
            if (locked) LogbookResult.ADDED_LOCKED else LogbookResult.ADDED)

    fun deleted(e: LogEntry): LogbookAnswer = LogbookAnswer(S.deleted("what" to e.what), LogbookResult.DELETED)
    /** spoken-log-3: the card that asks before "delete the last log entry" deletes, naming the entry and its time. */
    fun deleteAsk(e: LogEntry, now: ZonedDateTime): Phrase = S.deleteAsk("what" to e.what, "when" to whenOf(e.at, e.added, now))
    fun deleteTitle(e: LogEntry, now: ZonedDateTime): Phrase = S.deleteTitle("what" to e.what, "when" to whenOf(e.at, e.added, now))
    fun deleteFailed(): LogbookAnswer = LogbookAnswer(S.deleteFailed(), LogbookResult.FAILED)
    fun empty(): LogbookAnswer = LogbookAnswer(S.empty(), LogbookResult.EMPTY)
    fun unlockFirst(): LogbookAnswer = LogbookAnswer(S.unlockFirst(), LogbookResult.LOCKED)
    fun full(): LogbookAnswer = LogbookAnswer(S.full(), LogbookResult.FULL)
    fun failed(): LogbookAnswer = LogbookAnswer(S.failed(), LogbookResult.FAILED)
    /** A question while the log can't be opened (a newer Robot's, a key not usable now): never "That wasn't logged". */
    fun notNow(): LogbookAnswer = LogbookAnswer(S.notNow(), LogbookResult.FAILED)
}
