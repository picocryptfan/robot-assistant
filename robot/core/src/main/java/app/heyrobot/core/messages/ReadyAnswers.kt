// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.DayRange
import app.heyrobot.core.EventKind
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ReadyStrings
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// Pure. Ready answers (idea 18): someone in People asks "what time do you land?" and Robot offers a draft from your calendar
// or Files, which you send with one tap. The draft quotes the event's title or the file's name as written (never the model's
// words); no match, three or more matches, or a question about money, a code or a password: nothing.

/** What the question asks. LANDS and LEAVES: a flight; APPOINTMENT; BOOKING (a reservation). */
enum class ReadyKind { LANDS, LEAVES, APPOINTMENT, BOOKING }

sealed class ReadyQuestion {
    /** A question Robot may answer, in [lang] (the language it was asked in). */
    data class Ask(val kind: ReadyKind, val lang: Lang) : ReadyQuestion()
    /** Money, a code, a PIN or a password: never answered. */
    data object Refused : ReadyQuestion()
}

/** Where the answer came from. */
enum class ReadySource { CALENDAR, FILES }

/** Why no draft is offered for a message. */
enum class ReadySkip { OFF, LOCKED, GROUP, HIDDEN, NOT_IN_PEOPLE, NO_REPLY }

/** The draft ([draft], the words sent) and the notice's line naming its source and when it was read ([note]). */
data class ReadyAnswer(val source: ReadySource, val draft: String, val note: String, val lang: Lang) {
    override fun toString(): String = "ReadyAnswer($source, chars=${draft.length})" // l10n: not text (toString for logs and tests)
}

/** Settings › Messages › Ready answers, in its own file (settings.json stays at schema 13). Off by default. */
data class ReadyAnswerSettings(val on: Boolean = false) {
    fun encode(): String = JSONObject().put("v", VERSION).put("on", on).toString()

    companion object {
        const val VERSION = 1
        const val FILE = "ready-answers.json" // l10n: not text (a file name)

        fun decode(text: String?): ReadyAnswerSettings = try {
            val o = JSONObject(text ?: return ReadyAnswerSettings())
            if (o.optInt("v", 0) != VERSION) ReadyAnswerSettings() else ReadyAnswerSettings(o.optBoolean("on", false))
        } catch (_: Exception) {
            ReadyAnswerSettings()
        }
    }
}

object ReadyQuestions {
    // l10n: not text (the patterns below are words Robot reads, never shows)
    private val REFUSE = Regex("(\\$|€|£|\\b(money|cash|pay|paid|payment|owe|bank|card|credit|debit|e-?transfer|code|codes|pin|password|passcode|otp|account|argent|payer|payé|paiement|dois|banque|carte|virement|mot de passe|nip|compte|dollars?|fees?|frais|prix|facture|mdp|paie)\\b)") // l10n: not text (words Robot reads)
    private const val EN_ASK = "\\b(what time|when|what's the time|whens?)\\b" // l10n: not text (words Robot reads)
    private const val FR_ASK = "(quelle heure|\\bquand\\b)" // l10n: not text (words Robot reads)
    /** [ask] before or after [topic]: "what time do you land", "you land at what time". */
    private fun q(ask: String, topic: String) = Regex("$ask.*$topic|$topic.*$ask")

    private val EN = listOf(
        ReadyKind.LANDS to q(EN_ASK, "\\b(land|lands|landing|flight (arrive|arrives|get in|gets in)|plane (arrive|arrives|get in|gets in))\\b"), // l10n: not text (words Robot reads)
        ReadyKind.LEAVES to q(EN_ASK, "\\b(take ?off|takes ?off|depart|departs|departure|boarding|board|flight (leave|leaves)|plane (leave|leaves))\\b"), // l10n: not text (words Robot reads)
        ReadyKind.APPOINTMENT to q(EN_ASK, "\\b(appointment|appt|doctor|dentist)\\b"), // l10n: not text (words Robot reads)
        ReadyKind.BOOKING to q(EN_ASK, "\\b(reservation|booking)\\b"), // l10n: not text (words Robot reads)
    )
    private val FR = listOf(
        ReadyKind.LANDS to q(FR_ASK, "(atterri|atterrir|atterris|atterrit|atterrissage|vol arrive|avion arrive)"), // l10n: not text (words Robot reads)
        ReadyKind.LEAVES to q(FR_ASK, "(décoll|decoll|vol part|avion part|embarquement)"), // l10n: not text (words Robot reads)
        ReadyKind.APPOINTMENT to q(FR_ASK, "(rendez-vous|\\brdv\\b|médecin|medecin|dentiste)"), // l10n: not text (words Robot reads)
        ReadyKind.BOOKING to q(FR_ASK, "(réservation|reservation)"), // l10n: not text (words Robot reads)
    )

    /** [text]'s question, or null when it isn't one Robot answers. Money, codes and passwords are refused before anything. */
    fun read(text: String): ReadyQuestion? {
        val t = text.lowercase(Locale.ROOT).replace('’', '\'').replace('\n', ' ')
        if (t.isBlank() || t.length > MAX_CHARS) return null
        if (REFUSE.containsMatchIn(t)) return ReadyQuestion.Refused
        FR.firstOrNull { it.second.containsMatchIn(t) }?.let { return ReadyQuestion.Ask(it.first, Lang.FR) }
        EN.firstOrNull { it.second.containsMatchIn(t) }?.let { return ReadyQuestion.Ask(it.first, Lang.EN) }
        return null
    }

    /** Money, a code, a PIN or a password named in [text] (a question, or a title Robot would quote). */
    fun refuses(text: String): Boolean = REFUSE.containsMatchIn(text.lowercase(Locale.ROOT).replace('’', '\''))

    /** A longer message is a conversation, not a quick question. */
    const val MAX_CHARS = 200
}

object ReadyGate {
    /**
     * Medium 7: the confirmed person who wrote [t]'s newest message, by address only (a phone number or a JID, as "Send my
     * replies without a card" asks): a display name is the sender's choice, so a stranger named Sam never gets Sam's draft.
     */
    fun person(t: MessageThread, people: List<Person>, gateway: String = PhoneNumbers.DEFAULT_GATEWAY): Person? {
        if (t.isGroup) return null
        val key = t.incoming.lastOrNull()?.senderKey
        return people.firstOrNull { it.confirmed && MessagePeople.matchesKey(it, key, gateway) }
    }

    /** Why [t] gets no draft, or null when it may: on, unlocked, one to one, readable, from someone in People ([person]), with a reply action. */
    fun skip(on: Boolean, locked: Boolean, t: MessageThread, person: Person?): ReadySkip? = when {
        !on -> ReadySkip.OFF
        locked -> ReadySkip.LOCKED
        t.isGroup -> ReadySkip.GROUP
        t.hidden != null -> ReadySkip.HIDDEN
        person == null -> ReadySkip.NOT_IN_PEOPLE
        t.reply == null -> ReadySkip.NO_REPLY
        else -> null
    }
}

object ReadyAnswers {
    /** At most this many matches are named; more and none is. */
    const val MAX_MATCHES = 2

    // l10n: not text (words Robot reads in titles)
    // Medium 4: a flight word in any case, or a code as airlines write it (upper case; never a quarter such as Q3 2026).
    private val FLIGHT = Regex("(?i:\\b(flight|vol|avion|plane)\\b)|(?<![A-Za-z0-9])(?!Q[1-4] )[A-Z][A-Z0-9] ?\\d{2,4}(?![A-Za-z0-9])")
    /**
     * Medium 5: a booking number or code (letters and digits, 5 or more, not a flight's code) or a long number; Low A: an
     * all-letter booking reference (6 capitals), 4 digits or more (not a flight's own number), a number split by - . / or a space.
     */
    private val CODE = Regex("(?<![\\p{L}\\p{N}])(?![A-Z][A-Z0-9]\\d{1,4}(?![\\p{L}\\p{N}]))(?=[\\p{L}\\p{N}]*\\d)(?=[\\p{L}\\p{N}]*\\p{L})[\\p{L}\\p{N}]{5,}(?![\\p{L}\\p{N}])|\\d{5,}|(?<![\\p{L}\\p{N}])[A-Z]{6}(?![\\p{L}\\p{N}])|(?<![\\p{L}\\p{N}])(?<![A-Z][A-Z0-9] )\\d{4,}|(?<![\\p{L}\\p{N}])\\d{2,}(?:[-./ ]\\d{2,})+(?![\\p{L}\\p{N}])") // l10n: not text (words Robot reads)
    private val APPOINTMENT = Regex("(?i)\\b(appointment|appt|rendez-vous|rdv|doctor|dentist|médecin|dentiste|clinic|clinique)\\b") // l10n: not text (words Robot reads)
    private val BOOKING = Regex("(?i)\\b(reservation|réservation|booking)\\b") // l10n: not text (words Robot reads)

    /** From [now] to the end of tomorrow, where answers are looked for. */
    fun window(now: Long, zone: ZoneId): DayRange {
        val from = Instant.ofEpochMilli(now).atZone(zone)
        return DayRange(from, from.toLocalDate().plusDays(2).atStartOfDay(zone))
    }

    /** Medium 5: a title or name is quoted whole, so one holding a code, a number or money words is never offered. */
    private fun quotable(s: String): Boolean = !ReadyQuestions.refuses(s) && !CODE.containsMatchIn(s)

    private fun matches(kind: ReadyKind, title: String): Boolean = when (kind) {
        ReadyKind.LANDS, ReadyKind.LEAVES -> FLIGHT.containsMatchIn(title)
        ReadyKind.APPOINTMENT -> APPOINTMENT.containsMatchIn(title)
        ReadyKind.BOOKING -> BOOKING.containsMatchIn(title)
    }

    private fun fileKinds(kind: ReadyKind): Set<FileKind> = when (kind) {
        ReadyKind.LANDS, ReadyKind.LEAVES, ReadyKind.BOOKING -> setOf(FileKind.TICKET)
        // Medium 6 (STATUS question 97): a medical file's date is the document's, not a visit's; appointments come from the calendar only.
        ReadyKind.APPOINTMENT -> emptySet()
    }

    /**
     * The draft for [q] from [events] (the phone's calendar; Robot's private events never), else from [files] (read only
     * when the calendar has no match), or null. Two matches are both named; none or more than [MAX_MATCHES], nothing.
     */
    fun answer(q: ReadyQuestion.Ask, events: List<CalendarEvent>, files: () -> List<FileEntry>, now: Long, zone: ZoneId): ReadyAnswer? {
        val l = Langs.of(q.lang)
        val sep = "; " // l10n: not text (a list's separator; render() spaces it in French)
        val window = window(now, zone)
        val end = window.to.toInstant().toEpochMilli()
        val today = window.from.toLocalDate()
        val near = events.filter { e ->
            e.kind == EventKind.PHONE && !e.allDay && e.title.isNotBlank() && matches(q.kind, e.title) &&
                (if (q.kind == ReadyKind.LANDS) e.end else e.begin).let { it in now until end }
        }
        // Low B: a match that can't be quoted isn't dropped for the other (the wrong appointment): none is named.
        if (near.any { !quotable(it.title) }) return null
        val hits = near.sortedBy { if (q.kind == ReadyKind.LANDS) it.end else it.begin }
        val read = time(now, zone, q.lang)
        if (hits.isNotEmpty()) {
            if (hits.size > MAX_MATCHES) return null
            val facts = hits.joinToString(sep) { e ->
                val at = if (q.kind == ReadyKind.LANDS) e.end else e.begin
                val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                val whenText = (if (day == today) ReadyStrings.todayAt else ReadyStrings.tomorrowAt)("time" to time(at, zone, q.lang)).render(l)
                when (q.kind) {
                    ReadyKind.LANDS -> ReadyStrings.lands("what" to e.title.trim(), "when" to whenText)
                    ReadyKind.LEAVES -> ReadyStrings.leaves("what" to e.title.trim(), "when" to whenText)
                    else -> ReadyStrings.at("what" to e.title.trim(), "when" to whenText)
                }.render(l)
            }
            return ReadyAnswer(ReadySource.CALENDAR, ReadyStrings.fromCalendar("facts" to facts).render(l), ReadyStrings.readCalendar("time" to read).render(l), q.lang)
        }
        val kinds = fileKinds(q.kind).ifEmpty { return null }
        val found = files().filter { f ->
            f.kind in kinds && f.name.isNotBlank() && !f.damaged &&
                f.fields["date"]?.value?.let { d -> parse(d)?.let { it == today || it == today.plusDays(1) } } == true // l10n: not text (a field's name)
        }
        if (found.any { !quotable(it.name) }) return null
        if (found.isEmpty() || found.size > MAX_MATCHES) return null
        val facts = found.joinToString(sep) { f -> ReadyStrings.dated("what" to f.name.trim(), "date" to f.fields.getValue("date").value).render(l) } // l10n: not text (a field's name)
        return ReadyAnswer(ReadySource.FILES, ReadyStrings.fromFiles("facts" to facts).render(l), ReadyStrings.readFiles("time" to read).render(l), q.lang)
    }

    private fun parse(d: String): LocalDate? = try { LocalDate.parse(d.trim()) } catch (_: Exception) { null }

    /** 14:35 in English, 14 h 35 in French (Canadian usage). */
    fun time(ms: Long, zone: ZoneId, lang: Lang): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        return if (lang == Lang.FR) "%d h %02d".format(Locale.ROOT, t.hour, t.minute) else "%02d:%02d".format(Locale.ROOT, t.hour, t.minute) // l10n: not text (a time's format)
    }
}
