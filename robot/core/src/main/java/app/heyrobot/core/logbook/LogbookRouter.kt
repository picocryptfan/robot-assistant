// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.logbook

import app.heyrobot.core.LogCode
import app.heyrobot.core.WhenParser
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.sums.SumDates
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

// Pure. Your words to the logbook (docs/plans/spoken-log-plan.md §2 to §5), ahead of memory and the model: "log that …"
// always; a question only when it names something the log holds ([LogbookHeard]), so "when did I last see the dentist"
// stays memory's and the model's when the log has no dentist in it. Misfires left alone: "log in", "log me out", "the log
// file", "voice log", "logarithm".

/** What a turn did, for the voice log (VoiceEvent.LOGBOOK's a). */
enum class LogbookKind(override val id: Int, override val title: String) : LogCode {
    ADD(1, "add"),
    LAST(2, "last time"),
    COUNT(3, "count"),
    TOTAL(4, "total"),
    RECENT(5, "recent"),
    DELETE_LAST(6, "delete the last"),
}

/** The periods a count reads: [from] inclusive, [to] exclusive; both null for everything. [month] names "in October". */
data class LogPeriod(val kind: Kind, val from: ZonedDateTime?, val to: ZonedDateTime?, val month: YearMonth? = null) {
    enum class Kind { ALL, TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR, MONTH }

    fun holds(at: Long): Boolean = (from == null || at >= from.toInstant().toEpochMilli()) && (to == null || at < to.toInstant().toEpochMilli())

    companion object {
        val ALL = LogPeriod(Kind.ALL, null, null)
    }
}

sealed class LogbookCommand {
    abstract val lang: Lang
    abstract val kind: LogbookKind

    /** [timed]: you named a time, or it's now; else only a day ("yesterday"), shown without a clock time. */
    data class Add(val what: String, val at: Long, val timed: Boolean, val number: BigDecimal?, val unit: String?, override val lang: Lang) : LogbookCommand() {
        override val kind get() = LogbookKind.ADD
        override fun toString() = "Add($lang)" // l10n: not text (log only: never an entry's words)
    }
    data class Last(val subject: String, override val lang: Lang) : LogbookCommand() {
        override val kind get() = LogbookKind.LAST
        override fun toString() = "Last($lang)" // l10n: not text (log only)
    }
    /** A count of the matches in [period]; with [unit], the total of their numbers in it. */
    data class Count(val subject: String, val period: LogPeriod, val unit: String?, override val lang: Lang) : LogbookCommand() {
        override val kind get() = if (unit != null) LogbookKind.TOTAL else LogbookKind.COUNT
        override fun toString() = "Count(${period.kind}, ${if (unit != null) "total" else "count"}, $lang)" // l10n: not text (log only)
    }
    data class Recent(override val lang: Lang) : LogbookCommand() {
        override val kind get() = LogbookKind.RECENT
    }
    data class DeleteLast(override val lang: Lang) : LogbookCommand() {
        override val kind get() = LogbookKind.DELETE_LAST
    }
}

/** What the router knows of the log, read off the main thread: [on], [empty], its entries' words ([stems]) and [units]. */
data class LogbookHeard(val on: Boolean, val empty: Boolean, val stems: Set<String>, val units: Set<String>, val locked: Boolean = false) {
    override fun toString() = "LogbookHeard(on=$on, empty=$empty)" // l10n: not text (log only)

    companion object {
        val OFF = LogbookHeard(on = false, empty = true, stems = emptySet(), units = emptySet())
        fun of(on: Boolean, entries: List<LogEntry>) = LogbookHeard(on, entries.isEmpty(), entries.flatMapTo(HashSet()) { LogbookMatch.stems(it.what) },
            entries.mapNotNullTo(HashSet()) { e -> e.unit?.let(LogbookMatch::unitKey) })
    }
}

object LogbookRouter {
    private val I = RegexOption.IGNORE_CASE
    private const val LOGW = "log(?:book)?"

    private val ADD_EN = listOf(
        Regex("^(?:please )?(?:log|add to (?:my|the) $LOGW|put (?:in|into|on) (?:my|the) $LOGW|note in (?:my|the) $LOGW|$LOGW:)(?: that)?[:,]? (.+)$", I),
        Regex("^(?:please )?(?:add|put) (.+) (?:to|in|into) (?:my|the) $LOGW$", I))
    private val ADD_FR = listOf(
        Regex("^(?:inscris|écris|ecris|note|ajoute|mets)(?: ça| ca)? (?:dans|à|a|au) (?:mon|le) carnet(?: que | qu'|[:,] | )(.+)$", I),
        Regex("^(?:inscris|écris|ecris)(?: dans mon carnet)? (?:que |qu')(.+)$", I),
        Regex("^(?:carnet|inscris)[:,] (.+)$", I))

    /** After a bare "log": what makes it an account's or a file's ("log in", "log me out", "log file"). */
    private val NOT_AFTER_LOG = Regex("^(?:in|into|on|onto|out|off|me|us|him|her|them|you|it|everyone|file|files|level|levels|entry|entries|viewer|window|cat|of|for|from|data|book|is|was|says|shows)\\b", I)
    private val ENDS_AS_ACCOUNT = Regex(" (?:out|in|off|on)$", I)

    private const val MONTHS_EN = "january|february|march|april|may|june|july|august|september|october|november|december"
    private const val MONTHS_FR = "janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre"
    private const val PERIOD_EN = "today|yesterday|this week|last week|this month|last month|this year|last year|in all|ever|so far|altogether|in total|in (?:$MONTHS_EN)"
    private const val PERIOD_FR = "aujourd'hui|hier|cette semaine|la semaine (?:passée|passee|dernière|derniere)|ce mois-ci|ce mois ci|ce mois|le mois (?:passé|passe|dernier)|" +
        "cette année|cette annee|l'année (?:passée|passee|dernière|derniere)|l'annee (?:passee|derniere)|en tout|au total|depuis le début|depuis le debut|en (?:$MONTHS_FR)|au mois (?:de |d')(?:$MONTHS_FR)"

    private val LAST_EN = listOf(Regex("^when (?:did|was|were|have) i last (.+)$", I), Regex("^when(?: was|'s| is) the last time (?:that )?i (.+)$", I),
        Regex("^when did i (.+?) last$", I))
    private val LAST_FR = listOf(Regex("^(?:c'est )?quand (?:est-ce que |est ce que )?j'ai (.+?) (?:pour )?la derni[eè]re fois$", I),
        Regex("^c'est quand la derni[eè]re fois (?:que |qu')j'ai (.+)$", I), Regex("^quand ai-je (.+?) pour la derni[eè]re fois$", I),
        Regex("^la derni[eè]re fois (?:que |qu')j'ai (.+?),? c'est quand$", I))

    private val TIMES_EN = Regex("^how many times (?:did|have) i (.+?)(?: ($PERIOD_EN))?$", I)
    private val UNIT_EN = Regex("^how (?:many|much) (\\S+) (?:did|have) i (.+?)(?: ($PERIOD_EN))?$", I)
    private val MANY_EN = Regex("^how many (.+?)(?: (?:did i (?:do|have|log|go for|get)|have i (?:done|had|logged)))?(?: ($PERIOD_EN))?$", I)
    private val TIMES_FR = Regex("^combien de fois (?:est-ce que |est ce que )?j'ai (.+?)(?: ($PERIOD_FR))?$", I)
    private val UNIT_FR = Regex("^combien (?:de |d')(\\S+) (?:est-ce que |est ce que )?j'ai (.+?)(?: ($PERIOD_FR))?$", I)
    private val MANY_FR = Regex("^combien (?:de |d')(.+?)(?: (?:est-ce que |est ce que )?j'ai (?:fait|eu|inscrit))?(?: ($PERIOD_FR))?$", I)

    private val RECENT = listOf(Regex("^(?:what's|what is|whats) in (?:my|the) $LOGW$", I), Regex("^(?:show|read|tell)(?: me)? (?:my|the) $LOGW$", I),
        Regex("^what (?:did|have) i (?:log|logged)(?: lately| recently| today)?$", I), Regex("^(?:show|read)(?: me)? (?:my|the) (?:last|latest|newest) $LOGW entries$", I),
        Regex("^qu'est-ce qu'il y a dans (?:mon|le) carnet$", I), Regex("^(?:montre|lis)(?:-moi| moi)? (?:mon|le) carnet$", I),
        Regex("^qu'est-ce que j'ai inscrit(?: derni[eè]rement| r[ée]cemment)?$", I))
    private val DELETE_LAST = listOf(
        Regex("^(?:delete|remove|erase|undo) (?:the |my )?(?:last|latest) (?:$LOGW entry|entry (?:from|in) (?:my|the) $LOGW)$", I),
        Regex("^(?:efface|supprime|enl[eè]ve) la derni[eè]re (?:entr[ée]e|inscription) (?:du|de mon|dans mon) carnet$", I))

    /** [text] as the logbook's, or null (the routers after it, memory and the model have it). [now]: what times count from. */
    fun route(text0: String, heard: LogbookHeard, now: ZonedDateTime): LogbookCommand? {
        if (!heard.on) return null
        val text = text0.trim().replace('’', '\'').replace(Regex("\\s+"), " ").trimEnd('?', '.', '!', ' ')
        if (text.isEmpty() || text.length > 400) return null
        val lang = if (ADD_FR.any { it.matches(text) } || Regex("\\b(?:carnet|j'ai|combien|quand)\\b", I).containsMatchIn(text)) Lang.FR else Lang.EN
        add(text, now, lang)?.let { return it }
        if (DELETE_LAST.any { it.matches(text) }) return LogbookCommand.DeleteLast(lang)
        if (RECENT.any { it.matches(text) }) return LogbookCommand.Recent(lang)
        (LAST_EN + LAST_FR).firstNotNullOfOrNull { it.find(text) }?.let { m ->
            val subject = m.groupValues[1].trim()
            // Locked: the log can't be read, so a "when did I last" is answered with "unlock first", never guessed by the model.
            // spoken-log-4: no entry matches (an empty log too): memory and the model keep it, as before the log.
            return if (heard.locked || knows(subject, heard)) LogbookCommand.Last(subject, lang) else null
        }
        if (heard.empty || heard.locked) return null
        return count(text, heard, now, lang)
    }

    private fun knows(subject: String, heard: LogbookHeard): Boolean {
        val s = LogbookMatch.stems(subject)
        return s.isNotEmpty() && heard.stems.containsAll(s)
    }

    private fun count(text: String, heard: LogbookHeard, now: ZonedDateTime, lang: Lang): LogbookCommand.Count? {
        (TIMES_EN.find(text) ?: TIMES_FR.find(text))?.let { m ->
            val subject = m.groupValues[1]
            return if (knows(subject, heard)) LogbookCommand.Count(subject, period(m.groupValues[2], now), null, lang) else null
        }
        (UNIT_EN.find(text) ?: UNIT_FR.find(text))?.let { m ->
            val w = m.groupValues[1]
            if (!w.equals("times", true) && !w.equals("fois", true)) {
                val unit = LogbookMatch.unitKey(w)
                if (unit in heard.units && knows(m.groupValues[2], heard)) return LogbookCommand.Count(m.groupValues[2], period(m.groupValues[3], now), unit, lang)
                val subject = w + " " + m.groupValues[2]
                if (knows(subject, heard)) return LogbookCommand.Count(subject, period(m.groupValues[3], now), null, lang)
            }
        }
        (MANY_EN.find(text) ?: MANY_FR.find(text))?.let { m ->
            val subject = m.groupValues[1]
            return if (knows(subject, heard)) LogbookCommand.Count(subject, period(m.groupValues[2], now), null, lang) else null
        }
        return null
    }

    /** A period's words as a range of [now]'s calendar (weeks from Monday); none or "in all" is everything. */
    fun period(words0: String, now: ZonedDateTime): LogPeriod {
        val w = words0.lowercase().trim()
        val day = now.toLocalDate().atStartOfDay(now.zone)
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val month1 = day.withDayOfMonth(1)
        val year1 = day.withDayOfYear(1)
        fun p(k: LogPeriod.Kind, from: ZonedDateTime, to: ZonedDateTime) = LogPeriod(k, from, to)
        return when {
            w.isEmpty() || w in setOf("in all", "ever", "so far", "altogether", "in total", "en tout", "au total") || w.startsWith("depuis le d") -> LogPeriod.ALL
            w == "today" || w == "aujourd'hui" -> p(LogPeriod.Kind.TODAY, day, day.plusDays(1))
            w == "yesterday" || w == "hier" -> p(LogPeriod.Kind.YESTERDAY, day.minusDays(1), day)
            w == "this week" || w == "cette semaine" -> p(LogPeriod.Kind.THIS_WEEK, monday, monday.plusWeeks(1))
            w == "last week" || w.startsWith("la semaine") -> p(LogPeriod.Kind.LAST_WEEK, monday.minusWeeks(1), monday)
            w == "this month" || w.startsWith("ce mois") -> p(LogPeriod.Kind.THIS_MONTH, month1, month1.plusMonths(1))
            w == "last month" || w.startsWith("le mois") -> p(LogPeriod.Kind.LAST_MONTH, month1.minusMonths(1), month1)
            w == "this year" || w.startsWith("cette ann") -> p(LogPeriod.Kind.THIS_YEAR, year1, year1.plusYears(1))
            w == "last year" || w.startsWith("l'ann") -> p(LogPeriod.Kind.LAST_YEAR, year1.minusYears(1), year1)
            else -> {
                val name = w.removePrefix("in ").removePrefix("en ").removePrefix("au mois de ").removePrefix("au mois d'")
                val m = monthOf(name) ?: return LogPeriod.ALL
                val year = if (m > now.monthValue) now.year - 1 else now.year
                val from = YearMonth.of(year, m).atDay(1).atStartOfDay(now.zone)
                LogPeriod(LogPeriod.Kind.MONTH, from, from.plusMonths(1), YearMonth.of(year, m))
            }
        }
    }

    private fun monthOf(name: String): Int? {
        val en = MONTHS_EN.split('|').indexOf(name)
        if (en >= 0) return en + 1
        return when (name) {
            "janvier" -> 1; "février", "fevrier" -> 2; "mars" -> 3; "avril" -> 4; "mai" -> 5; "juin" -> 6; "juillet" -> 7
            "août", "aout" -> 8; "septembre" -> 9; "octobre" -> 10; "novembre" -> 11; "décembre", "decembre" -> 12
            else -> null
        }
    }

    private fun add(text: String, now: ZonedDateTime, lang0: Lang): LogbookCommand.Add? {
        val en = ADD_EN.firstNotNullOfOrNull { it.find(text) }
        val fr = if (en == null) ADD_FR.firstNotNullOfOrNull { it.find(text) } else null
        val m = en ?: fr ?: return null
        val lang = if (fr != null) Lang.FR else lang0.takeIf { en == null } ?: Lang.EN
        var rest = m.groupValues[1].trim()
        if (en != null && Regex("^(?:please )?log ", I).containsMatchIn(text) && !Regex("^(?:please )?log that\\b", I).containsMatchIn(text)) {
            if (NOT_AFTER_LOG.containsMatchIn(rest) || ENDS_AS_ACCOUNT.containsMatchIn(" $rest")) return null
        }
        // When: the last day or clock time named, always in the past ("yesterday at 8"; "Monday" is the one gone by).
        var at = now
        var timed = true
        WhenParser.find(rest, now, lang).lastOrNull { (it.dated || it.clock) && !it.relative }?.let { w ->
            past(w, now, lang)?.let { (t, clock) ->
                at = t
                timed = clock
                val i = rest.lowercase().lastIndexOf(w.said.lowercase())
                if (i >= 0) rest = (rest.substring(0, i) + rest.substring(i + w.said.length)).trim()
            }
        }
        rest = rest.replace(Regex("(?:,| at| on| à| a| le| vers)+$", I), "").replace(Regex("\\s+"), " ").trim()
        val what = rest.replace(Regex("^(?:i've |i have |i |j'ai |je |j')", I), "").trim().trimEnd(',', ';', ':')
        if (what.isEmpty()) return null
        val cut = if (what.length <= LogbookLimits.MAX_WHAT) what else what.take(LogbookLimits.MAX_WHAT).substringBeforeLast(' ').ifEmpty { what.take(LogbookLimits.MAX_WHAT) }
        val (number, unit) = number(cut, lang)
        return LogbookCommand.Add(cut, at.toInstant().toEpochMilli(), timed, number, unit, lang)
    }

    /** [w]'s moment, moved into the past, and whether it named a clock time; null when it can't be put before [now]. */
    private fun past(w: app.heyrobot.core.Mention, now: ZonedDateTime, lang: Lang): Pair<ZonedDateTime, Boolean>? {
        var t = w.span.start
        if (!w.clock) {
            val d = if (t.isAfter(now)) SumDates.read(w.said.lowercase(), now, lang, past = true) ?: return null else t.toLocalDate()
            if (d == now.toLocalDate()) return now to true
            // spoken-log-4: a day before 1970 can't be stored (the reader needs at > 0); it stays in the words, logged now.
            return d.atTime(12, 0).atZone(now.zone).takeIf { it.toInstant().toEpochMilli() > 0 }?.let { it to false }
        }
        if (t.isAfter(now) && !w.marked && !t.minusHours(12).isAfter(now) && t.minusHours(12).toLocalDate() == t.toLocalDate()) t = t.minusHours(12)
        if (t.isAfter(now) && !w.dated) t = t.minusDays(1)
        if (t.isAfter(now) && w.dated) t = if (t.minusDays(7).isAfter(now)) t.minusYears(1) else t.minusWeeks(1)
        if (t.isAfter(now) && !w.marked) t = t.minusHours(12)
        return if (t.isAfter(now) || t.toInstant().toEpochMilli() <= 0) null else t to true
    }

    private val NUM_EN = Regex("(?<![\\p{L}\\d.,])(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?(?: ?(\\p{L}{1,24}))?")
    private val NUM_FR = Regex("(?<![\\p{L}\\d.,])(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d+)(?:,(\\d+))?(?: ?(\\p{L}{1,24}))?")

    /** The first number in [what] and the word after it ("84,000 km": 84000, "km"; « 5,5 km »: 5.5). */
    fun number(what: String, lang: Lang): Pair<BigDecimal?, String?> {
        val m = (if (lang == Lang.FR) NUM_FR else NUM_EN).find(what) ?: return null to null
        val whole = m.groupValues[1].replace(Regex("[,\\s\\u00A0\\u202F]"), "")
        val n = (if (m.groupValues[2].isEmpty()) whole else whole + "." + m.groupValues[2]).toBigDecimalOrNull() ?: return null to null
        return n to m.groupValues[3].ifEmpty { null }?.lowercase()
    }
}
