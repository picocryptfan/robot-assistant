// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lasttime

import app.heyrobot.core.SearchModel
import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.LastTimeStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.Bm25Index
import app.heyrobot.core.memory.Bm25Retriever
import app.heyrobot.core.memory.HybridRetriever
import app.heyrobot.core.memory.Vectors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Pure. "When did I last …" (docs/plans/last-time-plan.md): your question, matched against Files' receipts, past
// calendar events and notes (the Logbook joins as one more source), by meaning when the search model's vectors are
// there and by words always, as MeaningSearch does. The newest of the best matches wins (a weak newer one never beats a
// strong older one); a second match within CLOSE_DAYS of it is named too.
// No model call: the match is vectors and word counts on the phone.

enum class LastSource { RECEIPT, CALENDAR, NOTE, LOGBOOK }

/** One dated thing that may answer: [title] is what the answer names, [text] more words to match on. */
data class LastCandidate(val source: LastSource, val id: String, val title: String, val text: String, val day: LocalDate) {
    val key: String get() = "${source.name.lowercase()}:$id"
    val searchable: String get() = if (text.isBlank()) title else "$title $text"
}

data class LastHit(val candidate: LastCandidate, val score: Double)

/**
 * "When did I last get the oil changed?": [what] is "get the oil changed" (lowercased), [lang] the question's language,
 * [said] the same words as said (their capitals, so a name or a number counts as one).
 */
data class LastAsk(val what: String, val lang: Lang, val said: String = what)

/**
 * Which sources were read: [files] false when Files isn't set up, [calendar] false without the calendar permission;
 * [unread] the sources whose reading failed (said as such, never counted as searched).
 */
data class LastRead(val files: Boolean = true, val calendar: Boolean = true, val unread: Set<LastSource> = emptySet())

/** What [LastTime.respondRead]'s reading found, and what it could read. */
data class LastFound(val hits: List<LastHit>, val read: LastRead = LastRead())

object LastTimeRouter {
    const val MAX_WHAT = 120

    private val EN = listOf(
        Regex("""^when did i last (.+)$"""),
        Regex("""^when was the last time (?:that )?i (.+)$"""),
        Regex("""^when was my last (.+)$"""),
        Regex("""^how long (?:has it been |is it )?since (?:i last |my last )(.+)$"""),
        // A statement ("the last time I checked, it was fine") is never a question: "what was" or "when was" first.
        Regex("""^what was the last time i (.+)$"""),
    )
    private val FR = listOf(
        Regex("""^quand est-ce que (?:j'ai|je suis|je me suis) (.+?) pour la derni[eè]re fois$"""),
        Regex("""^quand (?:ai-je|suis-je) (.+?) pour la derni[eè]re fois$"""),
        Regex("""^(?:c'est |c'était )?quand la derni[eè]re fois (?:que |qu')(?:j'ai|je suis|je me suis) (.+)$"""),
        Regex("""^(?:c'est |c'était )?quand (?:mon|ma|mes) derni(?:er|ère|ers|ères) (.+)$"""),
        Regex("""^(?:ça fait )?combien de temps depuis (?:mon|ma|mes) derni(?:er|ère|ers|ères) (.+)$"""),
        // As in English: "la dernière fois que j'ai vérifié, …" is a statement; only "…, c'était quand" asks.
        Regex("""^la derni[eè]re fois que (?:j'ai|je suis|je me suis) (.+?),? (?:c'était|c'est) quand$"""),
    )

    fun parse(q: String): LastAsk? {
        val raw = q.trim().replace('’', '\'').replace(Regex("""\s+"""), " ").trimEnd('?', '.', '!', ' ')
        val s = raw.lowercase()
        if (s.length > 200) return null
        for ((lang, set) in listOf(Lang.EN to EN, Lang.FR to FR)) for (r in set) {
            val g = r.find(s)?.groups?.get(1) ?: continue
            val what = g.value.trim().trimEnd('?', '.', '!', ' ')
            if (what.isEmpty() || what.length > MAX_WHAT) return null
            val said = if (raw.length == s.length) raw.substring(g.range).trim().trimEnd('?', '.', '!', ' ') else what
            return LastAsk(what, lang, said)
        }
        return null
    }
}

object LastTime {
    /** A second match this many days or fewer before the newest is named too (a plan and its receipt, say). */
    const val CLOSE_DAYS = 3L
    /** A vector match this close to the best one is as good: the newest of them answers. */
    const val NEAR = 0.05
    /** A word match with at least this share of the best word score is as good. */
    const val WORD_SHARE = 0.5

    /**
     * The candidates that match [what], newest first: by [query]'s vector against [vectors] (by [LastCandidate.key]) when
     * there is one, those within [NEAR] of the best; plus word matches with at least [WORD_SHARE] of the best word score
     * (without a query vector all of them; with one, those on a candidate that has no vector yet, and on others those
     * with a name or a number from [said], as MeaningSearch). Anything after [today] (a plan still to come) never answers.
     */
    fun match(what: String, query: FloatArray?, candidates: List<LastCandidate>, vectors: Map<String, FloatArray>, today: LocalDate,
              minScore: Double = SearchModel.MIN_SCORE, said: String = what): List<LastHit> {
        val past = candidates.filter { !it.day.isAfter(today) }.distinctBy { it.key }
        if (past.isEmpty() || what.isBlank()) return emptyList()
        val byVector = HashMap<String, Double>()
        if (query != null) for (c in past) {
            val v = vectors[c.key] ?: continue
            val n = minOf(v.size, query.size, SearchModel.DIM)
            val q = Vectors.matryoshka(query, n) ?: continue
            val s = Vectors.dot(q, Vectors.matryoshka(v, n) ?: continue)
            if (s >= minScore) byVector[c.key] = s
        }
        val strong = HybridRetriever.strongTokens(said)
        val byWords = HashMap<String, Double>()
        for (h in Bm25Index.build(past.map { it.key to it.searchable }, minDocs = Bm25Retriever.MIN_DOCS).search(what, past.size, Bm25Retriever.MIN_SCORE)) {
            if (query != null && h.id in vectors && h.matched.none { it in strong }) continue
            byWords[h.id] = h.score
        }
        val byKey = past.associateBy { it.key }
        val found = LinkedHashMap<String, LastHit>()
        byVector.values.maxOrNull()?.let { best -> byVector.forEach { (k, s) -> if (s >= best - NEAR) found[k] = LastHit(byKey.getValue(k), s) } }
        byWords.values.maxOrNull()?.let { best -> byWords.forEach { (k, s) -> if (k !in found && s >= best * WORD_SHARE) found[k] = LastHit(byKey.getValue(k), s) } }
        return found.values.sortedWith(compareByDescending<LastHit> { it.candidate.day }.thenByDescending { it.score })
    }

    /** Locked: nothing is read ([find] isn't called) and nothing named. */
    fun respond(locked: Boolean, today: LocalDate, find: () -> List<LastHit>): Phrase = respondRead(locked, today) { LastFound(find()) }

    /** As [respond], with what could be read: a source that couldn't be is said, and the none line names only those read. */
    fun respondRead(locked: Boolean, today: LocalDate, find: () -> LastFound): Phrase =
        if (locked) LastTimeStrings.locked() else find().let { answer(it.hits, today, it.read) }

    /**
     * The newest of [hits] with its date, how long ago and its source, and a close second; or the one-line none, naming
     * only the sources [read]. A calendar Robot may not read is said in one more line either way; Files not set up, with
     * the none line only (there's nothing there to miss).
     */
    fun answer(hits: List<LastHit>, today: LocalDate, read: LastRead = LastRead()): Phrase {
        val calendarOff = if (read.calendar) null else LastTimeStrings.calendarOff("path" to AndroidPath.APP_PERMISSIONS)
        val unread = listOfNotNull(
            LastTimeStrings.unreadReceipts.takeIf { LastSource.RECEIPT in read.unread }?.invoke(),
            LastTimeStrings.unreadCalendar.takeIf { LastSource.CALENDAR in read.unread }?.invoke(),
            LastTimeStrings.unreadNotes.takeIf { LastSource.NOTE in read.unread }?.invoke(),
        )
        val first = hits.firstOrNull() ?: return (listOfNotNull(none(read), calendarOff, if (read.files) null else LastTimeStrings.filesOff()) + unread)
            .let { if (it.size == 1) it[0] else Phrase.join(*it.toTypedArray()) }
        val second = hits.drop(1).firstOrNull { ChronoUnit.DAYS.between(it.candidate.day, first.candidate.day) in 0..CLOSE_DAYS }
        val one = said(first.candidate, today)
        val both = if (second == null) one else Phrase.join(one, LastTimeStrings.also("rest" to said(second.candidate, today)))
        val tail = listOfNotNull(calendarOff) + unread
        return if (tail.isEmpty()) both else Phrase.join(both, *tail.toTypedArray())
    }

    /** The none line for the sources searched; null when none was. */
    private fun none(read: LastRead): Phrase? {
        val r = read.files && LastSource.RECEIPT !in read.unread
        val c = read.calendar && LastSource.CALENDAR !in read.unread
        val n = LastSource.NOTE !in read.unread
        return when {
            r && c && n -> LastTimeStrings.none()
            c && n -> LastTimeStrings.noneCalendarNotes()
            r && n -> LastTimeStrings.noneReceiptsNotes()
            n -> LastTimeStrings.noneNotes()
            r && c -> LastTimeStrings.noneReceiptsCalendar()
            r -> LastTimeStrings.noneReceipts()
            c -> LastTimeStrings.noneCalendar()
            else -> null
        }
    }

    private fun said(c: LastCandidate, today: LocalDate): Phrase {
        val args = arrayOf<Pair<String, Any?>>("date" to Arg.Day(c.day), "ago" to ago(c.day, today), "what" to c.title)
        return when (c.source) {
            LastSource.RECEIPT -> LastTimeStrings.receipt(*args)
            LastSource.CALENDAR -> LastTimeStrings.calendar(*args)
            LastSource.NOTE -> LastTimeStrings.note(*args)
            LastSource.LOGBOOK -> LastTimeStrings.logbook(*args)
        }
    }

    fun ago(day: LocalDate, today: LocalDate): Phrase {
        val d = ChronoUnit.DAYS.between(day, today)
        return when {
            d <= 0L -> LastTimeStrings.today()
            d == 1L -> LastTimeStrings.yesterday()
            d < 14L -> LastTimeStrings.days(d)
            d < 60L -> LastTimeStrings.weeks(d / 7)
            d < 365L -> LastTimeStrings.months(ChronoUnit.MONTHS.between(day, today).coerceAtLeast(2))
            else -> LastTimeStrings.years(ChronoUnit.YEARS.between(day, today).coerceAtLeast(1))
        }
    }
}
