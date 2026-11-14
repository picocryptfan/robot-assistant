// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import java.time.ZonedDateTime

// Pure. fix-calendar-row7-c (rc3, Pixel 8, 14 Nov 2026): "every second Friday starting the 20th timesheet" and « un vendredi
// sur deux à partir du 20 Robot test » reached Gemma with add_event described (a weekday routes to the calendar) and came back
// as words, no call. A typed or said ask that starts with repeat words (RepeatWords) and ends with a short title is add_event's
// call in your words, never left to the model: the card shows what Robot read, and nothing is added without your yes.
object EventDictation {
    /** A title that starts like a sentence or a question is talk, not an event's name ("every Friday I go to the gym"). */
    private val NOT_TITLE = setOf("i", "im", "ive", "we", "you", "he", "she", "it", "my", "our", "mon", "ma", "mes", "notre", "its", "they", "there", "theres", "what", "whats",
        "when", "where", "who", "how", "why", "which", "do", "does", "did", "dont", "is", "are", "was", "were", "can", "could", "should",
        "will", "would", "has", "have", "had", "if", "and", "or", "but", "so", "then", "je", "j", "tu", "il", "elle", "on", "nous", "vous", // l10n: not text (words read)
        "ils", "elles", "qu", "que", "quoi", "quand", "ou", "qui", "comment", "pourquoi", "est", "c", "ca", "y", "et", "mais", "si", "puis",
        "pis", "alors", "anything", "any", "something", "quelque", "rien") // l10n: not text (words read); review-fix-calendar-row7-g nit: a question said with no '?'
    /**
     * fix-calendar-row7-h (review-fix-calendar-row7-g, Low): a title that starts with another tool's verb ("every weekday at 6
     * wake me up", « chaque vendredi à 9 h rappelle-moi … ») is that tool's ask, left to the model, never an event card.
     */
    private val TOOL_VERB = setOf("wake", "remind", "text", "call", "message", "set", "start", "stop", "turn", "open", "play", "note",
        "add", "send", "email", "reveille", "reveiller", "rappelle", "rappeler", "texte", "texter", "appelle", "appeler", "ecris", "ecrire", // l10n: not text (words read)
        "envoie", "envoyer", "regle", "regler", "demarre", "demarrer", "lance", "lancer", "arrete", "arreter", "allume", "allumer",
        "eteins", "eteindre", "ouvre", "ouvrir", "joue", "jouer", "noter", "ajoute", "ajouter", "mets", "mettre")
    private const val MAX_TITLE_WORDS = 6

    /** fix-calendar-fr-daily-142 (rc7): a title that starts as a calendar question ("anything on", « rien de prévu »), never an event. */
    private val QUESTION = setOf("anything", "any", "something", "what", "whats", "is", "are", "do", "does", "quelque", "rien", "quoi", "qu", "que", "est", "y") // l10n: not text (words read)

    /** True when [title] (a model's add_event) reads as a question about the calendar, not an event's name. */
    fun questionTitle(title: String): Boolean = words(title).firstOrNull() in QUESTION
    /** A "when" that stops on one of these left its date in the title ("every Friday starting the" + "20th"). */
    private val OPEN_END = setOf("on", "at", "the", "from", "starting", "beginning", "le", "la", "a", "de", "du", "des", "partir", "au", "aux") // l10n: not text (words read)

    /** Words that start a series ("starting the 20th", « à partir du 20 »): with no colon, a "when" names one or a time. */
    private val START = setOf("starting", "from", "beginning", "partir") // l10n: not text (words read)
    private val CLOCK = Regex("\\d{1,2}(am|pm|h\\d{0,2})")

    /** fix-calendar-row7-f (rc4 item 8): "Add every Friday at 9 team meeting", « Ajoute un vendredi sur deux … »: the verb (and "an event") goes, the rest reads as without it. */
    private val ADD_VERB = Regex("^(add|put|schedule|create|ajoute|ajouter|mets|planifie|crée|cree)\\s+((an?\\s+)?(recurring\\s+|repeating\\s+)?event\\s+|un\\s+[ée]v[éè]nement\\s+)?", RegexOption.IGNORE_CASE) // l10n: not text (words read)
    /** review-fix-calendar-row7 -f: "… to my calendar", « … dans mon agenda » at the end says where, not the title. */
    private val CALENDAR_TAIL = Regex("\\s+((to|on|in|into|onto) (my|the|our) calendar|(dans|à|a|sur|au) (mon |ma |notre |le |l')?(agenda|calendrier))$", RegexOption.IGNORE_CASE) // l10n: not text (words read)
    /** After a colon the title is yours as said: "every Friday: my timesheet". */
    private val YOURS = setOf("my", "our", "mon", "ma", "mes", "notre") // l10n: not text (words read)
    /** An ask for a list or your notes ("… to my notes", « … dans ma liste ») is the list's or the note's, never an event. */
    private val LIST_OR_NOTE = Regex("\\b(to|in|on|onto|into|a|dans|sur) (my|the|our|ma|mes|mon|notre|nos|la|le|les) ([a-z]+ )?(list|lists|note|notes|liste|listes)\\b") // l10n: not text (words read)

    /** The add_event call [heard] asks for at [now], or null when it isn't a repeating event with a title. */
    fun direct(heard: String, now: ZonedDateTime, lang: Lang): RawToolCall? {
        val said = heard.trim().trimEnd('.', '!').trim()
        val text = said.replaceFirst(ADD_VERB, "").trim().replace(CALENDAR_TAIL, "").trim()
        if (text.isEmpty() || text.endsWith('?')) return null
        if (LIST_OR_NOTE.containsMatchIn(words(text).joinToString(" "))) return null
        val found = RepeatWords.read(text) as? RepeatWords.Found.Ok ?: return null
        if (!RepeatWords.normalize(text).startsWith(found.said)) return null
        val colon = text.indexOf(':')
        val split = if (colon > 0) listOf(text.substring(0, colon).trim() to text.substring(colon + 1).trim())
        else {
            val ws = text.split(Regex("\\s+"))
            (1..minOf(MAX_TITLE_WORDS, ws.size - 1)).map { k -> ws.dropLast(k).joinToString(" ") to ws.takeLast(k).joinToString(" ") }
        }
        val (whenText, title) = split.firstOrNull { (w, t) -> t.isNotEmpty() && words(w).lastOrNull() !in OPEN_END && reads(w, now, lang) } ?: return null
        val first = words(title)
        if (first.isEmpty() || first.size > MAX_TITLE_WORDS || (first[0] in NOT_TITLE && !(colon > 0 && first[0] in YOURS)) || first[0] in OPEN_END || first[0] in TOOL_VERB || first[0][0].isDigit()) return null
        // review-fix-calendar-row7 -c item 3: "every Monday sucks" is talk; with no colon, an ask is an event only when it says when it starts or its time.
        if (colon <= 0 && !startsOrTimed(whenText)) return null
        return RawToolCall(ToolName.ADD_EVENT.id, mapOf("title" to title, "when" to whenText))
    }

    private fun startsOrTimed(whenText: String): Boolean {
        val w = words(whenText)
        return w.any { it in START || CLOCK.matches(it) } || w.zipWithNext().any { (a, b) -> (a == "at" || a == "a") && b[0].isDigit() }
    }

    private fun reads(whenText: String, now: ZonedDateTime, lang: Lang): Boolean =
        RepeatWords.plan(whenText, null, now, 60, lang) is Read.Ok

    private fun words(s: String): List<String> = RepeatWords.normalize(s).replace("'", " ").replace("’", " ")
        .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * [direct] for a turn: only your own words, typed or said, with nothing else in play (as NoteDictation.forTurn).
     * fix-calendar-row7-g (rc6 item 2): a plain turn's way is TEXT (FastPath.of with no command), never MODEL, so "only the
     * model's way" left every typed or said row to the model; texting's own claim still runs first in the panel.
     */
    fun forTurn(heard: String, way: FastPath.Way, cmd: HelperCommand?, attachment: Attachment, untrusted: Boolean, inHelperMode: Boolean,
        now: ZonedDateTime, lang: Lang): RawToolCall? =
        if (way != FastPath.Way.HELPER && cmd == null && attachment == Attachment.NONE && !untrusted && !inHelperMode) direct(heard, now, lang) else null
}
