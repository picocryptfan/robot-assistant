// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.ContactMatcher
import app.heyrobot.core.Person
import app.heyrobot.core.Read
import app.heyrobot.core.WhenParser
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.QAsk
import app.heyrobot.core.link.requests.RemOffer
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.RequestCaps
import java.text.Normalizer
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.3; job L7): what you ask another Robot, read from words by code. The
// same reading serves your own words (LinkRouter) and ask_robot's details (the model's words), so what goes is always what
// the card shows:
//
//   Free/busy  a day ("Friday", "tomorrow", « vendredi »), a part of it ("Friday evening": 17:00 to 23:00; morning 6:00 to
//              12:00, afternoon 12:00 to 17:00, night 20:00 to midnight; « vendredi soir »), a week or a weekend ("next
//              week"), or one time ("Friday at 7": that hour). Never more than 14 days, never before now.
//   A plan     1 to 3 clock times ("Friday at 7 or Saturday at 6"; « vendredi à 19 h ou samedi à 18 h »), each still ahead,
//              its length from a range said ("7 to 9") or your usual event length; the other words are its title
//              ("dinner"), at most 60 characters.
//
// Nothing is guessed: words it can't place give a question back ("When? Say a day, like Friday evening.").

/** What ask_robot's `request` names (its wire words, in English whatever the turn's language). */
enum class LinkAskKind(val wire: String) {
    FREE_BUSY("free_busy"), PLAN("plan"), CANCEL_PLAN("cancel_plan"), // l10n: not text (a tool's argument values)
    ETA("eta"), REMINDER("reminder"), QUESTION("question"), CONTACT("contact"); // l10n: not text (a tool's argument values)

    companion object {
        /** The kind [s] names, leniently (the model may say "availability", "event" or "cancel"); null for anything else. */
        fun of(s: String?): LinkAskKind? = when (s?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')?.replace(' ', '_')) {
            "free_busy", "freebusy", "free", "busy", "availability", "available", "when_free", "free_time" -> FREE_BUSY // l10n: not text (a tool's argument values)
            "plan", "event", "propose", "proposal", "invite", "invitation", "meeting", "meet" -> PLAN // l10n: not text (a tool's argument values)
            "cancel_plan", "cancel", "cancel_event", "cancellation" -> CANCEL_PLAN // l10n: not text (a tool's argument values)
            "eta", "arrival", "on_my_way", "minutes_away", "away" -> ETA // l10n: not text (a tool's argument values)
            "reminder", "remind", "rem" -> REMINDER // l10n: not text (a tool's argument values)
            "question", "ask", "q" -> QUESTION // l10n: not text (a tool's argument values)
            "contact", "contact_card", "card", "vcard" -> CONTACT // l10n: not text (a tool's argument values)
            else -> null
        }
    }
}

object LinkAsk {
    /** What "evening" and its kin mean for a free/busy window. */
    private enum class Part(val from: LocalTime, val to: LocalTime?) {
        MORNING(LocalTime.of(6, 0), LocalTime.of(12, 0)), AFTERNOON(LocalTime.of(12, 0), LocalTime.of(17, 0)),
        EVENING(LocalTime.of(17, 0), LocalTime.of(23, 0)), NIGHT(LocalTime.of(20, 0), null)
    }

    // Folded words (lower case, no accents) Robot hears, never shows.
    private val PARTS = listOf(
        Regex("""\b(?:this |ce |in the |le |du |dans la )?(?:morning|matin|matinee)\b""") to Part.MORNING, // l10n: not text (words Robot hears)
        Regex("""\b(?:this |cet |in the |l'|dans l')?(?:afternoon|apres-midi|apres midi|aprem)\b""") to Part.AFTERNOON, // l10n: not text (words Robot hears)
        Regex("""\b(?:this |ce |in the |le |du |dans la |en )?(?:evening|soir|soiree)\b""") to Part.EVENING, // l10n: not text (words Robot hears)
        Regex("""\b(?:at |la |cette |dans la )?(?:night|nuit)\b""") to Part.NIGHT, // l10n: not text (words Robot hears)
    )
    private val TONIGHT = Regex("""\b(?:tonight|ce soir|cette nuit)\b""") // l10n: not text (words Robot hears)
    private val LEFT_OVER = Regex("""^(?:on |le |for |pour |this |ce |cette |la |at )+|(?: (?:at|on|for|a|le))+$""") // l10n: not text (words Robot hears)

    /** Lower case without accents, one space between words, ’ as '. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).replace('’', '\'')
            .replace(Regex("""[?!.,;:]+$"""), "").replace(Regex("\\s+"), " ").trim()

    private fun langs(lang: Lang) = Langs.of(lang)

    /** The free/busy window [words] name at [now] (whose zone it is in). */
    fun window(words: String, now: ZonedDateTime, lang: Lang): Read<FbAsk> {
        var w = fold(words)
        if (w.isEmpty()) return Read.Unreadable(LinkStrings.askWhen())
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        var part: Part? = null
        var today = false
        if (TONIGHT.containsMatchIn(w)) { part = Part.EVENING; today = true; w = TONIGHT.replace(w, " ") }
        else for ((re, p) in PARTS) if (re.containsMatchIn(w)) { part = p; w = re.replace(w, " "); break }
        w = w.replace(Regex("\\s+"), " ").trim()
        // What a part of the day left behind ("on Friday" of "on Friday evening").
        if (part != null) w = LEFT_OVER.replace(w, "").trim()
        val zone = now.zone
        // One time said ("Friday at 7"): that hour.
        if (part == null && w.isNotEmpty()) {
            WhenParser.reads(w, now, lang)?.takeIf { it.clock }?.let { m ->
                val s = m.span.start.truncatedTo(ChronoUnit.MINUTES)
                val e = m.span.end?.takeIf { it.isAfter(s) } ?: s.plusHours(1)
                return clip(s, e, minute)
            }
        }
        val days = if (w.isEmpty() || today) app.heyrobot.core.DayRange(minute.toLocalDate().atStartOfDay(zone), minute.toLocalDate().plusDays(1).atStartOfDay(zone))
        else when (val r = WhenParser.range(w, now, lang)) {
            is Read.Ok -> r.value
            is Read.Unreadable -> return Read.Unreadable(LinkStrings.askWhen())
        }
        if (part == null) return clip(days.from, days.to, minute)
        if (days.days > 1) return Read.Unreadable(LinkStrings.askWhen())
        val d = days.from.toLocalDate()
        val s = d.atTime(part.from).atZone(zone)
        val e = part.to?.let { d.atTime(it).atZone(zone) } ?: d.plusDays(1).atStartOfDay(zone)
        return clip(s, e, minute)
    }

    private fun clip(s: ZonedDateTime, e: ZonedDateTime, now: ZonedDateTime): Read<FbAsk> {
        val from = maxOf(s, now)
        if (!e.isAfter(from)) return Read.Unreadable(LinkStrings.whenPassed())
        val to = minOf(e, from.plusSeconds(RequestCaps.FB_WINDOW_SECONDS))
        val a = from.toEpochSecond(); val b = to.toEpochSecond()
        if (a < RequestCaps.MIN_TIME || b > RequestCaps.MAX_TIME || a % 60 != 0L || b % 60 != 0L) return Read.Unreadable(LinkStrings.askWhen())
        return Read.Ok(FbAsk(a, b))
    }

    /** Words left around a plan's times that aren't its title. */
    private val GLUE = setOf("or", "ou", "and", "et", "at", "on", "a", "à", "le", "la", "for", "pour", "an", "un", "une", "the", "with", "avec", "then", "sinon", "maybe", "peut-etre", "peut-être") // l10n: not text (words Robot hears)

    /** The plan [words] name at [now]: its times and title. [minutes]: your usual event length when no range is said. */
    fun plan(words: String, now: ZonedDateTime, lang: Lang, minutes: Long): Read<EventFields> {
        val text = words.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return Read.Unreadable(LinkStrings.planTimes())
        val mentions = WhenParser.find(text, now, lang).filter { it.clock }
        val times = mentions.map { it.span.start.truncatedTo(ChronoUnit.MINUTES).toEpochSecond() }.distinct().sorted()
        if (times.isEmpty()) return Read.Unreadable(LinkStrings.planTimes())
        if (times.size > RequestCaps.TIMES) return Read.Unreadable(LinkStrings.planTooMany())
        if (times.any { it <= now.toEpochSecond() }) return Read.Unreadable(LinkStrings.whenPassed())
        if (times.any { it < RequestCaps.MIN_TIME || it > RequestCaps.MAX_TIME }) return Read.Unreadable(LinkStrings.planTimes())
        val said = mentions.firstNotNullOfOrNull { m -> m.span.end?.let { Duration.between(m.span.start, it).toMinutes() }?.takeIf { it > 0 } }
        val length = (said ?: minutes).coerceIn(RequestCaps.EVENT_MIN_MINUTES, RequestCaps.EVENT_MAX_MINUTES)
        var rest = text
        for (m in mentions) rest = rest.replaceFirst(m.said, " ")
        val kept = rest.split(' ').map { it.trim(',', ';', '.', '!', '?', ':', '…', '"', '“', '”', '(', ')') }.filter { it.isNotEmpty() }
        val title = kept.dropWhile { fold(it) in GLUE }.dropLastWhile { fold(it) in GLUE }.joinToString(" ")
        val t = LinkText.clean(title, RequestCaps.TITLE)?.takeIf { LinkText.ok(it, RequestCaps.TITLE) } ?: LinkStrings.planTitle().render(langs(lang))
        return Read.Ok(EventFields(t, times, length))
    }

    // L10 --------------------------------------------------------------------------------------------------------------------
    // Folded words (LinkRouter.foldSame: one character for one, so a match cuts your words as said) Robot hears, never shows.
    private val NUMBERS = mapOf("one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, // l10n: not text (words Robot hears)
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "twenty-five" to 25, // l10n: not text (words Robot hears)
        "twenty five" to 25, "thirty" to 30, "forty" to 40, "forty-five" to 45, "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90, // l10n: not text (words Robot hears)
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, // l10n: not text (words Robot hears)
        "douze" to 12, "quinze" to 15, "vingt" to 20, "vingt-cinq" to 25, "vingt cinq" to 25, "trente" to 30, "quarante" to 40, // l10n: not text (words Robot hears)
        "quarante-cinq" to 45, "quarante cinq" to 45, "cinquante" to 50, "soixante" to 60) // l10n: not text (words Robot hears)
    private val WORDS_N = NUMBERS.keys.sortedByDescending { it.length }.joinToString("|")
    private val N = "(\\d{1,3}|$WORDS_N)" // l10n: not text (a parser's pattern)
    private val HALF_HOUR = Regex("""\b(?:half an hour|a half hour|une demi-heure|une demi heure)\b""") // l10n: not text (words Robot hears)
    private val HOURS = Regex("""\b$N (?:(and a half) )?(?:hours?|hrs?|heures?|h)(?: (et demie|and a half)| (?:and )?(\d{1,2}|$WORDS_N)(?: minutes?| mins?| min)?)?\b""") // l10n: not text (a parser's pattern)
    private val MINUTES = Regex("""\b$N (?:minutes?|mins?|min)\b""") // l10n: not text (a parser's pattern)
    /** An ETA says how far away, not just a number: "away", "from", "there in", « je suis à », « j'arrive dans ». */
    private val ETA_CUE = Regex("""\b(?:away|from|(?:there|here|home|back|over) (?:in|within)|arriv(?:e|ing) in|on my way|on the way|je suis a|on est a|nous sommes a|j'arrive|on arrive|je serai (?:la|rendue?|chez)|en route)\b""") // l10n: not text (words Robot hears)
    private val PLACE_AFTER = Regex("""^(?: away)? (?:from|de la|de l'|du|des|de|d') ?(.+)$""") // l10n: not text (a parser's pattern)
    private val PLACE_WORDS = Regex("""^\p{L}(?:[\p{L}' -]*\p{L})?$""") // l10n: not text (a parser's pattern)

    private fun number(s: String): Long? = s.toLongOrNull() ?: NUMBERS[s]?.toLong()

    /** The minutes an ETA in folded words [f] says, and where the amount ends; null when it says no amount or isn't an ETA. */
    private fun amount(f: String): Pair<Long, Int>? {
        if (!ETA_CUE.containsMatchIn(f)) return null
        HALF_HOUR.find(f)?.let { return 30L to it.range.last + 1 }
        HOURS.find(f)?.let { m ->
            val h = number(m.groupValues[1]) ?: return null
            val extra = when { m.groups[2] != null || m.groups[3] != null -> 30L; m.groups[4] != null -> number(m.groupValues[4]) ?: 0L; else -> 0L }
            return h * 60 + extra to m.range.last + 1
        }
        MINUTES.find(f)?.let { m -> return (number(m.groupValues[1]) ?: return null) to m.range.last + 1 }
        return null
    }

    /** An ETA's place word ("home", "the station"): letters only, up to 3 words; anything else (an address, digits) is dropped. */
    fun place(s: String?): String? {
        val t = s?.replace(Regex("\\s+"), " ")?.trim()?.trimEnd('.', ',', '!', '?') ?: return null
        if (!PLACE_WORDS.matches(t) || t.split(' ').size > 3) return null
        return t.takeIf { LinkText.ok(it, RequestCaps.ETA_PLACE) }
    }

    /** L10: an ETA from [words] ("I'm 10 minutes away from home", « je suis à une demi-heure »): 1 to 600 minutes, a place word. */
    fun eta(words: String): Read<EtaTell> {
        val o = words.replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', ',', '?')
        val f = LinkRouter.foldSame(o)
        val (minutes, end) = amount(f) ?: return Read.Unreadable(LinkStrings.etaHow())
        if (minutes !in 1..RequestCaps.ETA_MAX_MINUTES) return Read.Unreadable(LinkStrings.etaHow())
        val at = PLACE_AFTER.find(f.substring(end))?.groups?.get(1)?.range
        val place = at?.let { place(o.substring(end + it.first, end + it.last + 1)) }
        return Read.Ok(EtaTell(minutes, place))
    }

    /**
     * L10: a reminder for someone from [words] ("bring the charger tomorrow"): a day or time said at the end is when (taken
     * off the words), else now; your words as said, at most 120 characters.
     */
    fun reminder(words: String, now: ZonedDateTime, lang: Lang): Read<RemOffer> {
        var text = words.replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', ',')
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        var at = minute
        WhenParser.find(text, now, lang).lastOrNull { it.dated || it.clock }?.let { m ->
            val i = text.lastIndexOf(m.said)
            if (i > 0 && text.substring(i + m.said.length).isBlank()) {
                at = maxOf(minute, m.span.start.truncatedTo(ChronoUnit.MINUTES))
                text = text.substring(0, i).trim().split(' ').dropLastWhile { fold(it) in GLUE }.joinToString(" ")
            }
        }
        if (text.isBlank()) return Read.Unreadable(LinkStrings.remWhat())
        val t = LinkText.clean(text, RequestCaps.REMINDER + 1) ?: return Read.Unreadable(LinkStrings.remWhat())
        if (!LinkText.ok(t, RequestCaps.REMINDER)) return Read.Unreadable(LinkStrings.remTooLong())
        val s = at.toEpochSecond()
        if (s < RequestCaps.MIN_TIME || s > RequestCaps.MAX_TIME) return Read.Unreadable(LinkStrings.remWhat())
        return Read.Ok(RemOffer(t, s))
    }

    /** L10: a question for someone's Robot ("where the party is"): your words as said, at most 200 characters. */
    fun question(words: String): Read<QAsk> {
        val o = words.replace(Regex("\\s+"), " ").trim()
        val t = LinkText.clean(o, RequestCaps.QUESTION + 1)?.let { if (it.last() in "?.!") it else "$it?" } ?: return Read.Unreadable(LinkStrings.qWhat())
        if (!LinkText.ok(t, RequestCaps.QUESTION)) return Read.Unreadable(LinkStrings.qTooLong())
        return Read.Ok(QAsk(t))
    }
}

/** Who you mean among the people you're paired with (your People names, aliases and nicknames). */
object LinkPeople {
    /** A paired person: their pairing and their People entry. */
    class Paired(val pairing: PairingView, val person: Person) {
        override fun toString(): String = "Paired" // l10n: not text (toString for logs and tests)
    }

    /** Everyone paired (an active pairing you said who it is). */
    fun all(views: List<PairingView>, people: List<Person>): List<Paired> = views.filter { it.phase == PairingPhase.ACTIVE }
        .mapNotNull { v -> v.person?.let { id -> people.firstOrNull { it.id == id } }?.let { Paired(v, it) } }

    /** The paired people [words] name, best match first: by name, alias or nickname (ContactMatcher's rules). */
    fun find(words: String, views: List<PairingView>, people: List<Person>): List<Paired> {
        val q = words.trim()
        if (q.isEmpty()) return emptyList()
        return all(views, people).mapNotNull { p ->
            val names = listOf(p.person.name) + p.person.aliases + p.person.nicknames
            val best = names.mapNotNull { n -> if (ContactMatcher.sameAlias(q, n)) app.heyrobot.core.NameMatch.ALIAS else ContactMatcher.kind(q, n) }.minOrNull()
            best?.let { it to p }
        }.sortedBy { it.first }.let { list ->
            // An exact name or alias wins over looser matches ("Sam" over "Samantha" when both are paired).
            val top = list.firstOrNull()?.first ?: return emptyList()
            list.filter { it.first == top || top > app.heyrobot.core.NameMatch.EXACT }.map { it.second }
        }
    }

    /** Whether [words] name someone you're paired with. */
    fun isPaired(words: String, views: List<PairingView>, people: List<Person>): Boolean = find(words, views, people).isNotEmpty()
}

/**
 * What ask_robot resolved before its card (the app, unlocked): the [pairing] of the person meant, your [name] for them, and
 * exactly what goes ([fb], [plan], or the plan to [cancel]), as its card says it ([summary]).
 */
class LinkReady(
    val pairing: String,
    val name: app.heyrobot.core.i18n.Phrase,
    val kind: LinkAskKind,
    val summary: app.heyrobot.core.i18n.Phrase,
    val fb: FbAsk? = null,
    val plan: EventFields? = null,
    val cancel: LinkPlan? = null,
    /** L10: what goes for an ETA, a reminder, a question or a contact card (built by code from your words). */
    val body: app.heyrobot.core.link.requests.LinkBody? = null,
) {
    init {
        require(LinkIds.isId(pairing))
        require(when (kind) {
            LinkAskKind.FREE_BUSY -> fb != null; LinkAskKind.PLAN -> plan != null; LinkAskKind.CANCEL_PLAN -> cancel != null
            LinkAskKind.ETA -> body is EtaTell; LinkAskKind.REMINDER -> body is RemOffer; LinkAskKind.QUESTION -> body is QAsk
            LinkAskKind.CONTACT -> body is app.heyrobot.core.link.requests.CardOffer
        })
    }

    override fun toString(): String = "LinkReady($kind)" // l10n: not text (toString for logs and tests)
}
