// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calls

import java.util.Locale

// Pure. Caller name (docs/plans/caller-name-plan.md, idea 13): a call from a number that isn't in your contacts nor
// Robot's People, matched offline against your texts, your calendar and Robot Files, gives only the likely name
// ("Probably Coastal Plumbing"), never where it was found. Nothing here is logged: no number, name or match.

/** Where a clue came from: only to choose what may be searched (the lock rule), never shown. */
enum class CallerSource { TEXTS, CALENDAR, FILES }

/**
 * One thing that may name a caller. [about]: the number it is from (a text's sender), with [name] the name its app gave;
 * [title]: a calendar event's title or a file's name, the fallback when [text] holds the number but no name on its line.
 */
data class CallerClue(val source: CallerSource, val text: String, val title: String? = null, val about: String? = null, val name: String? = null) {
    override fun toString(): String = "CallerClue($source)" // l10n: not text (never a name or text in a log)
}

/** What the lock allows: which sources are searched and whether the name may show on the lock screen. */
data class CallerScope(val sources: Set<CallerSource>, val onLockScreen: Boolean)

object CallerName {
    /** Robot's own limit for the whole search; Android's Telecom waits about 5 s for a screening app's answer. */
    const val BUDGET_MS = 1_500L
    const val MIN_DIGITS = 7
    const val MAX_NAME = 60
    private const val MAX_WORDS = 6
    private val NUMBER = Regex("""\+?\(?\d[\d\s().\- ]{5,18}\d""")
    private val CUT = Regex("""[.!?;|•]\s""")
    private val TRAIL = Regex("""(?i)[\s:,\-–—=/(]+$|\s+(?:at|on|tel\.?|tél\.?|phone|ph\.?|number|no\.?|au|à|le|#)$""")
    // Inside a name only: "Johnson & Sons", "Bank of Nova Scotia", "Café de la Gare". Never its first or last word.
    private val JOINS = setOf("&", "and", "of", "the", "de", "du", "des", "la", "le", "les", "et", "d'", "l'") // l10n: not text (words matched, never shown)
    // Capitalised words that start a sentence but never a name (pronouns, verbs, greetings, phone words), English and French.
    private val NOT_NAME = setOf(
        "a", "an", "the", "i", "i'm", "it", "it's", "its", "me", "my", "mine", "you", "your", "he", "she", "his", "her", "we", // l10n: not text (words matched, never shown)
        "our", "they", "their", "this", "that", "here", "here's", "there", "please", "pls", "call", "text", "contact", "reach", // l10n: not text (words matched, never shown)
        "ring", "try", "dial", "phone", "tel", "cell", "mobile", "home", "work", "office", "fax", "number", "new", "is", "hi", // l10n: not text (words matched, never shown)
        "hey", "hello", "thanks", "thank", "ok", "okay", "yes", "no", "and", "or", "but", "at", "on", "re", "fyi", "urgent", // l10n: not text (words matched, never shown)
        "appointment", "meeting", "lunch", "dinner", "reminder", "rendez-vous", "rdv", "réunion", "rappel", "dîner", // l10n: not text (words matched, never shown)
        "je", "j'", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "moi", "toi", "mon", "ma", "mes", "ton", "ta", // l10n: not text (words matched, never shown)
        "tes", "son", "sa", "ses", "notre", "votre", "leur", "un", "une", "le", "la", "les", "au", "aux", "c'est", "voici", // l10n: not text (words matched, never shown)
        "voilà", "svp", "appelle", "appelle-moi", "appeler", "rappelle", "texte", "texte-moi", "contacte", "écris", "téléphone", // l10n: not text (words matched, never shown)
        "cellulaire", "cell.", "bureau", "maison", "numéro", "nouveau", "nouvelle", "est", "bonjour", "salut", "allo", "allô", // l10n: not text (words matched, never shown)
        "merci", "oui", "non", "et", "ou", "mais", "réserve", // l10n: not text (words matched, never shown)
        // review-caller-name-b, Medium 1: object pronouns, vous imperatives and labels.
        "us", "him", "them", "lui", "eux", "ph", "telephone", "toll-free", "tollfree", "tél", "sans", "frais", "appelez", // l10n: not text (words matched, never shown)
        "rappelez", "contactez", "écrivez", "textez", "joignez", "composez", "rejoins", "compose", // l10n: not text (words matched, never shown)
    )
    // Greetings that may come before a comma and a name: "Hi Sam, Bay Heating …".
    private val OPENERS = setOf("hi", "hey", "hello", "salut", "bonjour", "allo", "allô", "bonsoir", "coucou") // l10n: not text (words matched, never shown)
    // Capitalised nouns in NOT_NAME that may start a business name ("Home Hardware", "New Horizons", "Bureau Gagnon").
    private val NOUN_BEFORE = setOf("home", "work", "office", "new", "fax", "cell", "mobile", "maison", "bureau", "nouveau", "nouvelle", "cellulaire") // l10n: not text (words matched, never shown)
    // A span ending in one of these is a label or an event's title, not a name; precision first (a wrong name costs more than none).
    private val LABEL_LAST = setOf(
        "service", "services", "support", "sync", "meeting", "meetings", "call", "calls", "desk", "line", "lines", "free", "results", // l10n: not text (words matched, never shown)
        "result", "session", "sessions", "sales", "help", "helpline", "hotline", "info", "information", "inquiries", "enquiries", // l10n: not text (words matched, never shown)
        "care", "billing", "dispatch", "reception", "team", "review", "interview", "checkup", "check-up", "consult", "consultation", // l10n: not text (words matched, never shown)
        "follow-up", "followup", "standup", "stand-up", "webinar", "lesson", "lessons", "training", "appointment", "appointments", // l10n: not text (words matched, never shown)
        "booking", "bookings", "reservations", "reminder", "update", "notice", "alert", "hours", "voicemail", "number", // l10n: not text (words matched, never shown)
        "ventes", "ligne", "soutien", "client", "clients", "clientèle", "rencontre", "séance", "appel", "réunion", "suivi", "cours", // l10n: not text (words matched, never shown)
        "atelier", "formation", "résultats", "technique", "infos", "assistance", "aide", "réception", "facturation", "rendez-vous", // l10n: not text (words matched, never shown)
    )
    // French puts the label or the event first: "Service Client", "Ligne Info", "Séance Photo", "Appel Équipe".
    private val LABEL_FIRST = setOf("service", "ligne", "soutien", "séance", "appel", "rencontre", "réunion", "suivi", "cours", "atelier", "formation", "résultats", "info", "infos") // l10n: not text (words matched, never shown)
    private val LABEL = Regex("""(\S+)\s*:\s*$""")
    private val HYPHEN = Regex("[-\u2010\u2011]")

    /** The number's digits, or null when it has fewer than [MIN_DIGITS] (hidden, short codes). */
    fun digits(number: String?): String? = number?.filter(Char::isDigit)?.takeIf { it.length >= MIN_DIGITS }

    /** The same number: the last 10 digits when both have 10 or more (country code aside), else one ends with the other. */
    fun same(a: String?, b: String?): Boolean {
        val x = digits(a) ?: return false
        val y = digits(b) ?: return false
        if (x.length >= 10 && y.length >= 10) return x.takeLast(10) == y.takeLast(10)
        return if (x.length >= y.length) x.endsWith(y) else y.endsWith(x)
    }

    /**
     * The same number in a clue: both with 10 digits or more and the last 10 equal. Stricter than [same], which the
     * contacts check keeps loose so that unsure means known (old Low 5: "555-0148" in a text names no one in 902).
     */
    fun sameInClue(a: String?, b: String?): Boolean {
        val x = digits(a)?.takeIf { it.length >= 10 } ?: return false
        val y = digits(b)?.takeIf { it.length >= 10 } ?: return false
        return x.takeLast(10) == y.takeLast(10)
    }

    /** A name as shown: trimmed, one line, not a number, at most [MAX_NAME] characters; else null. */
    fun clean(s: String?): String? {
        val t = s?.replace(Regex("[\\p{Cc}\\p{Cf}\\u00A0\\u202F\\u2028\\u2029]"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.trim('"', '\'', '“', '”', '«', '»', ' ', ':', '-', ',', '.')
            ?: return null
        if (t.length < 2 || t.length > MAX_NAME || t.count(Char::isLetter) < 2) return null
        if (t.count(Char::isDigit) * 2 >= t.length) return null
        return t
    }

    /**
     * The name just before [number] on the line of [text] that holds it, or null; "" when the number is there with no
     * name before it. Only a span shaped like a name counts (capitalised words, joined by "&", "of", "de" and the like),
     * never the words as they come: "Please call …", "call me at …" and "my new number is …" give "".
     */
    fun onLine(text: String, number: String): String? = wordsBefore(text, number)?.let(::nameSpan)

    /** The words before a number: [label] when the last of them stood just before a ":" ("Dispatch:", "Tél :"). */
    private class Before(val words: List<String>, val label: Boolean)

    /** The words before [number] on its line, from the last sentence break, connectors ("at", "Tel:", "au") trimmed. */
    private fun wordsBefore(text: String, number: String): Before? {
        for (raw in text.lines()) {
            // fr-CA puts a no-break space before ":"; the same length, so the number's place holds.
            val line = raw.replace('\u00A0', ' ').replace('\u202F', ' ')
            for (m in NUMBER.findAll(line)) {
                if (!sameInClue(m.value, number)) continue
                var before = line.substring(0, m.range.first)
                val labelWord = LABEL.find(before)?.groupValues?.get(1)
                repeat(3) { before = before.replace(TRAIL, "") }
                CUT.findAll(before).lastOrNull()?.let { before = before.substring(it.range.last + 1) }
                val words = before.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                return Before(words, labelWord != null && words.lastOrNull() == labelWord)
            }
        }
        return null
    }

    /** A word as matched: lower case, typographic apostrophes folded (as phones send "It’s"), end punctuation off. */
    private fun key(w: String): String = w.lowercase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'').trimEnd(',', '.', ':')

    private fun isNameWord(w: String): Boolean {
        val c = w.firstOrNull { it.isLetter() } ?: return false
        if (w.first() != c || !(c.isUpperCase() || c.isTitleCase())) return false
        // "Hi!", "Questions?", "Allô?": an exclamation or a question, never a name (review-caller-name-c, Medium 1).
        if (w.trimEnd('"', '\'', '\u201D', '\u2019', ')').lastOrNull().let { it == '!' || it == '?' }) return false
        val k = key(w)
        // Each part of a hyphenated word: "Rappelle-moi", "Appelez-nous" are verbs; "Jean-Marc" is a name.
        return k !in NOT_NAME && k.split(HYPHEN).none { it in NOT_NAME }
    }

    /** The name-shaped words at the end of [before] (at most [MAX_WORDS]), or "". A lone word before ":" is a label. */
    private fun nameSpan(before: Before): String {
        val span = nameSpan(before.words)
        return if (before.label && !span.contains(' ')) "" else span
    }

    private fun nameSpan(words: List<String>): String {
        val span = ArrayDeque<String>()
        var stop = -1
        for (i in words.indices.reversed()) {
            val w = words[i]
            // A comma ends the name: "Hi Sam, Bay Heating" gives "Bay Heating" (review-caller-name-b, Low 2), but only after
            // an opener: "Jane Doe, Realtor" or "Halifax, NS" is a title or a place, so nothing (review-caller-name-c, Low 1).
            // The span then goes through the same trim and label check (review-last-fixes-14: "Hi, Customer Service").
            if (span.isNotEmpty() && w.endsWith(',')) { if (!openerBefore(words, i)) return ""; break }
            if (span.size >= MAX_WORDS) { stop = i; break }
            if (!isNameWord(w) && !(span.isNotEmpty() && key(w) in JOINS)) {
                // "Home Hardware", "Office Depot": a capitalised noun just before is part of the name, so the rest of it
                // alone would be wrong; show nothing (review-caller-name-c, Low 2).
                if (span.isNotEmpty() && w.first().isUpperCase() && key(w) in NOUN_BEFORE) return ""
                stop = i; break
            }
            span.addFirst(w)
        }
        while (span.isNotEmpty() && !isNameWord(span.first())) span.removeFirst()
        // "Customer Service", "Team Sync", "Ligne Info", "Séance Photo": a label or an event, not a name (review-caller-name-c, Low 3).
        if (span.isNotEmpty() && (key(span.last()) in LABEL_LAST || key(span.first()) in LABEL_FIRST)) return ""
        // A street number just before, or in the part before its comma, makes it an address ("12 Water St, Halifax").
        if (stop >= 0) {
            var j = stop
            while (j >= 0 && (j == stop || (words[stop].endsWith(',') && !words[j].endsWith(',')))) {
                if (words[j].any(Char::isDigit)) return ""
                j--
            }
        }
        return span.joinToString(" ")
    }

    /** The part of [words] ending at [comma] (back to the comma before it) is an opener and at most a name: "Hi Sam,", "Salut,". */
    private fun openerBefore(words: List<String>, comma: Int): Boolean {
        var j = comma
        while (j > 0 && !words[j - 1].endsWith(',')) j--
        return key(words[j]) in OPENERS && (j + 1..comma).all { isNameWord(words[it]) }
    }

    /** [s] as a whole when every word of it is shaped like a name (an event's title), else null. */
    fun nameShaped(s: String?): String? {
        val t = clean(s) ?: return null
        val words = t.split(' ')
        return t.takeIf { words.size <= MAX_WORDS && nameSpan(words) == t }
    }

    /**
     * The name [clue] gives for [number], or null: the sender's name its app gave, else a name just before the number,
     * else an event's title shaped like a name when the number stands with nothing before it. Never a file's name.
     */
    fun nameFrom(clue: CallerClue, number: String): String? {
        if (clue.about != null && sameInClue(clue.about, number)) clean(clue.name)?.let { return it }
        val before = wordsBefore(clue.text, number) ?: return null
        // Words that aren't a name ("Please call") give nothing, not the title.
        if (before.words.isNotEmpty()) return clean(nameSpan(before))
        return if (clue.source == CallerSource.CALENDAR) nameShaped(clue.title) else null
    }

    /** The name found in the most clues (case and spaces aside); a tie, or none, gives null. */
    fun choose(names: List<String>): String? {
        if (names.isEmpty()) return null
        val groups = names.groupBy { it.lowercase(Locale.ROOT) }.values.sortedByDescending { it.size }
        if (groups.size > 1 && groups[0].size == groups[1].size) return null
        return groups[0].groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    }

    /**
     * The likely name for [number], reading each of [sources] in turn, or null: when none names it, it's a tie, or the
     * number is [known] (contacts, People), or the search ran past [budgetMs] by [now] (a late name is never shown: the call is already ringing).
     * [start]: when the call came in, by [now]'s clock, so the contacts check counts too (review-caller-name-b, Low 3).
     */
    fun guess(number: String?, sources: List<Pair<CallerSource, () -> List<CallerClue>>>, scope: CallerScope, now: () -> Long,
              budgetMs: Long = BUDGET_MS, known: () -> Boolean = { false }, start: Long = now()): String? {
        // Hidden or short, or in your contacts or Robot's People: untouched, nothing read.
        if (digits(number) == null || known()) return null
        val names = mutableListOf<String>()
        for ((source, read) in sources) {
            if (source !in scope.sources) continue
            if (now() - start > budgetMs) return null
            val clues = try { read() } catch (_: Exception) { emptyList() }
            clues.mapNotNullTo(names) { nameFrom(it, number!!) }
        }
        if (now() - start > budgetMs) return null
        return choose(names)
    }

    /**
     * The lock rule: unlocked, every source; locked, no Files (its key needs an unlocked phone) and texts only when Read my
     * messages may read them on the lock screen ([textsLocked]); someone else's Phone lock, nothing. The name never
     * shows on the lock screen (question 1 of the plan: default no).
     */
    fun scope(locked: Boolean, someoneElse: Boolean, textsOn: Boolean, textsLocked: Boolean): CallerScope = when {
        someoneElse -> CallerScope(emptySet(), false)
        !locked -> CallerScope(setOfNotNull(CallerSource.TEXTS.takeIf { textsOn }, CallerSource.CALENDAR, CallerSource.FILES), false)
        else -> CallerScope(setOfNotNull(CallerSource.TEXTS.takeIf { textsOn && textsLocked }, CallerSource.CALENDAR), false)
    }
}

/** caller_name.json (not settings.json, which stays at schema 13): off by default; a damaged file is off. */
data class CallerNameSettings(val on: Boolean = false) {
    fun toJson(): String = org.json.JSONObject().put("v", 1).put("on", on).toString()

    companion object {
        const val FILE = "caller_name.json"

        /**
         * Caller name's one switch (hide-caller-name-1.4). False in 1.4: review-caller-name-e found the cue-only name still
         * wrong, so the feature waits for 1.5. While false there is no Settings row (so no role request), a saved
         * caller_name.json with on = true reads as off ([read]), and the manifest disables CallerNameService
         * (android:enabled, which CallerNameHiddenTest keeps in step with this). 1.5 sets it true and the manifest's
         * android:enabled with it.
         */
        const val AVAILABLE = false

        /** The saved file as the app sees it: [fromJson], but always off while [AVAILABLE] is false. */
        fun read(s: String?): CallerNameSettings = if (AVAILABLE) fromJson(s) else CallerNameSettings()
        fun fromJson(s: String?): CallerNameSettings = try {
            if (s.isNullOrBlank()) CallerNameSettings() else CallerNameSettings(org.json.JSONObject(s).optBoolean("on", false))
        } catch (_: Exception) { CallerNameSettings() }
    }
}
