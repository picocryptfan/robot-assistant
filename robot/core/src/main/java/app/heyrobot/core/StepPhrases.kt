// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import java.text.Normalizer
import java.time.LocalTime
import java.util.Locale

// Pure. The steps Robot hears in a routine or a skill (1.1, moved out of RoutineRouter for Skills: docs/plans/
// skills-1.1-plan.md §6), English and Québec French: "set a timer for 12 minutes", "text Sam I'm on my way", "open the
// camera", "tell me the weather", "say good night", joined by "then", "and", ",", « puis », « ensuite », « et », « pis ».
// Each step is one of Robot's tools with its arguments read through the tool's own validation (ToolCalls.parse), or a
// routine-only one (the brief, a sentence). A part not understood is never guessed: the card names it. Also the words and
// clock times the routine router reads its triggers with.

object StepPhrases {
    internal enum class Period { AM, PM }

    /** A word of the utterance: plain (no accents, apostrophes or case) and where it is in what was said. */
    internal class Tok(val w: String, val start: Int, val end: Int)

    /** French elisions split ("j'éteins" → j, eteins; "d'apporter"); English contractions stay one word ("what's" → whats). */
    private val ELISION = setOf("j", "l", "d", "qu", "c", "m", "t", "s", "n", "jusqu", "lorsqu", "puisqu")

    internal fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace("'", "").replace("’", "")

    internal class Words(val text: String) {
        val t: List<Tok> = tokens(text)
        val size: Int get() = t.size
        fun w(i: Int): String? = t.getOrNull(i)?.w

        /** The length of the longest of [phrases] (words separated by spaces) at [i], or 0. */
        fun at(i: Int, phrases: Collection<String>): Int = phrases.maxOfOrNull { ph ->
            val ws = ph.split(' ')
            if (ws.indices.all { w(i + it) == ws[it] }) ws.size else 0
        } ?: 0

        fun at(i: Int, vararg phrases: String): Int = at(i, phrases.asList())

        /** What was said from word [from] to before [to], as said (with closing brackets right after the last word). */
        fun span(from: Int, to: Int): String {
            if (from >= to || from >= size) return ""
            var end = t[minOf(to, size) - 1].end
            while (end < text.length && text[end] in ")]}") end++
            return text.substring(t[from].start, end)
        }

        private fun tokens(text: String): List<Tok> {
            val out = mutableListOf<Tok>()
            var i = 0
            fun digitAt(k: Int) = k in text.indices && text[k].isDigit()
            while (i < text.length) {
                val c = text[i]
                if (c == ',' || c == ';' || (c == ':' && !(digitAt(i - 1) && digitAt(i + 1)))) { out += Tok(",", i, i + 1); i++; continue }
                if (!c.isLetterOrDigit()) { i++; continue }
                val start = i
                while (i < text.length) {
                    val ch = text[i]
                    if (ch.isLetterOrDigit()) { i++; continue }
                    if ((ch == ':' || ch == '.') && digitAt(i - 1) && digitAt(i + 1)) { i++; continue }
                    if ((ch == '\'' || ch == '’') && i + 1 < text.length && text[i + 1].isLetter() && plain(text.substring(start, i)) !in ELISION) { i++; continue }
                    break
                }
                out += Tok(plain(text.substring(start, i)), start, i)
            }
            return out
        }
    }

    private val HOUR_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

    internal enum class Kind { EXPLICIT, H24, BARE }

    /** A clock time as said: [h] already on 24 hours when [kind] is EXPLICIT (am/pm, noon). */
    internal class Clock(val h: Int, val m: Int, val kind: Kind, val end: Int, val said: String)

    private val CLOCK_TOKEN = Regex("""^(\d{1,2})(?:[:.](\d{2}))?(am|pm|h)?(\d{2})?$""")

    internal fun clock(p: Words, i: Int): Clock? {
        val w = p.w(i) ?: return null
        when (w) {
            "noon", "midi" -> return Clock(12, 0, Kind.EXPLICIT, i + 1, p.span(i, i + 1))
            "midnight", "minuit" -> return Clock(0, 0, Kind.EXPLICIT, i + 1, p.span(i, i + 1))
        }
        val word = HOUR_WORDS.indexOf(w)
        if (word >= 0) {
            var j = i + 1
            if (p.w(j) == "oclock") j++
            return finishAmPm(p, word + 1, 0, j, i, Kind.BARE)
        }
        val m = CLOCK_TOKEN.matchEntire(w) ?: return null
        val h = m.groupValues[1].toInt()
        var min = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: 0
        var j = i + 1
        when (m.groupValues[3]) {
            "am", "pm" -> return amPm(h, min, m.groupValues[3], j, p.span(i, j))
            "h" -> {
                if (m.groupValues[4].isEmpty() && p.w(j)?.matches(Regex("""\d{2}""")) == true) { min = p.w(j)!!.toInt(); j++ }
                return valid(h, min, Kind.H24, j, p.span(i, j))
            }
        }
        if (p.at(j, "h", "heure", "heures") > 0) {
            j++
            if (p.w(j)?.matches(Regex("""\d{1,2}""")) == true) { min = p.w(j)!!.toInt(); j++ }
            return valid(h, min, Kind.H24, j, p.span(i, j))
        }
        if (p.w(j) == "oclock") j++
        val h24 = h >= 13 || h == 0 || (m.groupValues[2].isNotEmpty() && m.groupValues[1].length == 2 && m.groupValues[1].startsWith("0"))
        return finishAmPm(p, h, min, j, i, if (h24) Kind.H24 else Kind.BARE)
    }

    private fun finishAmPm(p: Words, h: Int, min: Int, j: Int, i: Int, kind: Kind): Clock? {
        val ap = p.at(j, "am", "pm", "a m", "p m")
        if (ap > 0) return amPm(h, min, if (p.w(j)!!.startsWith("a")) "am" else "pm", j + ap, p.span(i, j + ap))
        return valid(h, min, kind, j, p.span(i, j))
    }

    private fun amPm(h: Int, min: Int, ap: String, end: Int, said: String): Clock? {
        if (h !in 1..12 || min !in 0..59) return null
        val h24 = if (ap == "am") (if (h == 12) 0 else h) else (if (h == 12) 12 else h + 12)
        return Clock(h24, min, Kind.EXPLICIT, end, said)
    }

    private fun valid(h: Int, min: Int, kind: Kind, end: Int, said: String): Clock? =
        if (h in 0..23 && min in 0..59) Clock(h, min, kind, end, said) else null

    /** "in the morning", "at night", "du matin", "du soir", "de l'après-midi", "le soir" after a time. */
    private val CLOCK_PERIOD = mapOf("in the morning" to Period.AM, "in the evening" to Period.PM, "at night" to Period.PM, "in the afternoon" to Period.PM,
        "tonight" to Period.PM, "du matin" to Period.AM, "du soir" to Period.PM, "de l apres midi" to Period.PM, "le matin" to Period.AM,
        "le soir" to Period.PM)

    /**
     * The time [c] means. A bare 12-hour time ("at 7") takes the period said ("every morning", "in the evening"), else
     * 6 to 11 are morning hours, 12 is noon and 1 to 5 afternoon ones ("every Friday at 5" is 17:00); the card then says
     * how it was read ([note]).
     */
    internal fun resolve(c: Clock, per: Period?, notes: MutableList<Phrase>, page: UiLabel = UiLabel(UiStrings.routines)): LocalTime {
        var h = c.h
        when (c.kind) {
            Kind.EXPLICIT -> Unit
            Kind.H24 -> if (per == Period.PM && h in 1..11) h += 12
            Kind.BARE -> when (per) {
                Period.AM -> if (h == 12) h = 0
                Period.PM -> if (h < 12) h += 12
                null -> {
                    if (h in 1..5) h += 12
                    notes += RoutineStrings.readAs("said" to c.said, "time" to EventText.clock(h, c.m), "page" to page)
                }
            }
        }
        return LocalTime.of(h, c.m)
    }

    internal fun clockPeriod(p: Words, j: Int): Pair<Period?, Int> {
        val n = p.at(j, CLOCK_PERIOD.keys)
        return if (n == 0) null to 0 else CLOCK_PERIOD.getValue((j until j + n).joinToString(" ") { p.w(it)!! }) to n
    }

    // ------------------------------------------------------------------ steps

    internal class Acts(val steps: List<RoutineStep>, val unparsed: List<String>, val refused: List<String>, val notes: List<Phrase>, val commandLike: Boolean)

    /** Between steps: ",", "and", "then", "et", "pis", "puis", "ensuite". */
    private val SEP = setOf(",", "and", "then", "also", "et", "pis", "puis", "ensuite", "aussi")
    private val COMMAND = setOf("tell", "give", "read", "say", "set", "start", "open", "turn", "launch", "play", "text", "send", "show", "check",
        "call", "remind", "put", "switch", "wake", "search", "find", "navigate", "let", "whats", "what", "dis", "donne", "lis", "regle", "mets", "met",
        "ouvre", "allume", "eteins", "lance", "joue", "envoie", "appelle", "montre", "rappelle", "demarre", "programme", "annonce", "verifie",
        "cherche", "texte", "ecris", "fais")

    /**
     * 1.1 (Skills, D9): a step phrase starts at word [from] of [p] (its piece, up to the next "then" or comma, is a step or
     * "say …"): where a skill's name ends when speech to text gave no colon.
     */
    internal fun stepAt(p: Words, from: Int): Boolean {
        var to = from
        while (to < p.size && p.w(to) !in SEP) to++
        if (to == from) return false
        val r = piece(p, from, to, mutableListOf())
        return r is Piece.Step || r is Piece.Say
    }

    /** The steps in [text] as a whole (a skill's steps, said after its name; a test's). */
    internal fun steps(text: String, page: UiLabel = UiLabel(UiStrings.routines)): Acts = actions(Words(text), 0, page)

    internal fun actions(p: Words, from: Int, page: UiLabel = UiLabel(UiStrings.routines)): Acts {
        // Pieces between separators.
        val pieces = mutableListOf<Pair<Int, Int>>()
        var start = from
        for (j in from..p.size) {
            if (j == p.size || p.w(j) in SEP) {
                if (j > start) pieces += start to j
                start = j + 1
            }
        }
        val steps = mutableListOf<RoutineStep>()
        val unparsed = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val notes = mutableListOf<Phrase>()
        var k = 0
        while (k < pieces.size) {
            val (a, b) = pieces[k]
            when (val r = piece(p, a, b, notes, page)) {
                is Piece.Step -> steps += r.step
                Piece.Refused -> refused += p.span(a, b)
                Piece.Unknown -> unparsed += p.span(a, b)
                is Piece.Say -> {
                    // A sentence runs on while what follows isn't a step of its own (or refused): "say good night and sweet
                    // dreams, then turn off the flashlight" says "good night and sweet dreams", exactly as on the card.
                    var end = b
                    while (k + 1 < pieces.size && piece(p, pieces[k + 1].first, pieces[k + 1].second, mutableListOf()) == Piece.Unknown) {
                        k++
                        end = pieces[k].second
                    }
                    val text = p.span(r.from, end).trim()
                    if (text.length > RoutineCheck.MAX_SAY) unparsed += p.span(a, end) else steps += RoutineStep.Say(text)
                }
            }
            k++
        }
        val first = pieces.firstOrNull()?.let { p.w(it.first) }
        return Acts(steps, unparsed, refused, notes, first in COMMAND)
    }

    private sealed class Piece {
        data class Step(val step: RoutineStep) : Piece()
        /** "say …": the sentence starts at word [from]. */
        data class Say(val from: Int) : Piece()
        data object Refused : Piece()
        data object Unknown : Piece()
    }

    private val LEAD = listOf("please", "also", "and", "then", "s il te plait", "aussi")
    private val NEVER = listOf("free up", "free memory", "free storage", "free space", "free some space", "clear the cache", "clear cache", "clean up",
        "delete", "erase", "wipe", "uninstall", "remove", "ask claude", "ask cloud", "ask clyde", "ask claud", "ask clod", "get claude",
        "libere", "liberer", "vide le cache", "supprime", "supprimer", "efface", "effacer", "nettoie", "desinstalle", "demande a claude",
        "demande a cloud", "va chercher claude",
        // 1.0.0: "Ask a helper"'s own phrases.
        "ask the helper", "ask a helper", "ask my helper", "demande a l assistant", "demande a lassistant", "demande l assistant",
        "demande a un assistant", "demande un assistant")
    private val READ_VERBS = listOf("tell me about", "tell me", "give me", "read me", "read out", "read", "let me know", "show me", "whats on", "what is on",
        "whats", "what is", "check", "how is", "hows", "dis moi", "donne moi", "lis moi", "lis", "annonce moi", "c est quoi", "quelle est", "quel est",
        "verifie", "montre moi")

    private fun piece(p: Words, from: Int, to: Int, notes: MutableList<Phrase>, page: UiLabel = UiLabel(UiStrings.routines)): Piece {
        var a = from
        while (a < to) { val n = p.at(a, LEAD); if (n == 0) break; a += n }
        if (a >= to) return Piece.Unknown
        if ((a until to).any { p.at(it, NEVER) in 1..(to - it) }) return Piece.Refused
        action(p, a, to, notes, page)?.let { return Piece.Step(it) }
        val v = p.at(a, READ_VERBS)
        read(p, a + v, to)?.let { return Piece.Step(it) }
        if (v == 0 && p.at(a, "say", "dis") == 1 && a + 1 < to) return Piece.Say(a + 1)
        return Piece.Unknown
    }

    private fun tool(name: String, vararg args: Pair<String, Any?>): RoutineStep? =
        (ToolCalls.parse(RawToolCall(name, args.filter { it.second != null }.toMap())) as? ParsedCall.Ok)?.let { RoutineStep.Tool(it.action) }

    private val ALARM_STEP = listOf("set an alarm for", "set an alarm at", "set the alarm for", "set the alarm at", "set my alarm for", "set my alarm at",
        "set alarm for", "set alarm at", "wake me up at", "wake me at", "regle un reveil a", "regle un reveil pour", "regle le reveil a", "regle mon reveil a",
        "mets un reveil a", "mets un reveil pour", "mets le reveil a", "mets un cadran a", "regle un cadran a", "regle mon cadran a", "regle le cadran a",
        "mets le cadran a", "mets mon cadran a", "regle une alarme a", "mets une alarme a", "programme un reveil a", "reveille moi a")
    private val TIMER_EN = listOf("set a timer for", "set the timer for", "start a timer for", "set timer for", "timer for", "a timer for")
    private val TIMER_FR = listOf("mets une minuterie de", "regle une minuterie de", "part une minuterie de", "lance une minuterie de",
        "demarre une minuterie de", "programme une minuterie de", "mets un minuteur de", "regle un minuteur de", "lance un minuteur de",
        "mets une minuterie pour", "minuterie de", "une minuterie de")
    private val UNITS = mapOf("minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60, "hour" to 3600, "hours" to 3600, "heure" to 3600,
        "heures" to 3600, "second" to 1, "seconds" to 1, "seconde" to 1, "secondes" to 1)
    private val NUMBER_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "une" to 1, "un" to 1, "deux" to 2, "trois" to 3, "cinq" to 5, "dix" to 10, "quinze" to 15, "vingt" to 20, "trente" to 30)
    private val LIGHT_ON = listOf("turn on the flashlight", "turn on the torch", "turn on the light", "turn the flashlight on", "turn the torch on",
        "switch on the flashlight", "flashlight on", "torch on", "allume la lampe de poche", "allume la lampe", "allume le flash", "ouvre la lampe de poche",
        "ouvre la lampe")
    private val LIGHT_OFF = listOf("turn off the flashlight", "turn off the torch", "turn off the light", "turn the flashlight off", "turn the torch off",
        "switch off the flashlight", "flashlight off", "torch off", "eteins la lampe de poche", "eteins la lampe", "eteins le flash", "ferme la lampe de poche",
        "ferme la lampe")
    private val NAVIGATE = listOf("directions to", "give me directions to", "get directions to", "navigate to", "the route to", "route to", "take me to",
        "itineraire vers", "itineraire pour", "itineraire jusqu a", "amene moi a", "emmene moi a", "amene moi au", "emmene moi au")
    private val SEARCH = listOf("search maps for", "search organic maps for", "search the map for", "cherche sur la carte")
    private val OPEN = listOf("open up", "open", "launch", "start", "ouvre", "ouvrir", "lance", "demarre")

    // 1.1 (Skills, D4): the route to your saved Home or Work, ahead of the search and of opening an app, so "open the route
    // home" is never open_app("route home").
    private val ROUTE_VERB = listOf("open up", "open", "show me", "show", "pull up", "start", "get", "give me", "ouvre", "ouvrir", "montre moi",
        "montre", "affiche", "lance", "demarre", "donne moi")
    private val ROUTE_DET = setOf("the", "my", "l", "la", "le", "mon")
    private val ROUTE_HOME = listOf("route home", "directions home", "way home", "route to home", "itineraire vers la maison",
        "itineraire vers chez nous", "itineraire vers chez moi", "itineraire pour la maison", "itineraire a la maison", "chemin de la maison")
    private val ROUTE_WORK = listOf("route to work", "directions to work", "way to work", "route to the office", "itineraire vers le travail",
        "itineraire vers le bureau", "itineraire pour le travail", "itineraire au travail", "chemin du travail")
    private val GO_HOME = listOf("navigate home", "take me home", "drive me home", "get me home", "ramene moi a la maison", "ramene moi chez nous",
        "ramene moi chez moi", "amene moi a la maison", "emmene moi a la maison", "amene moi chez nous")
    private val GO_WORK = listOf("navigate to work", "take me to work", "drive me to work", "get me to work", "amene moi au travail",
        "emmene moi au travail", "amene moi au bureau", "emmene moi au bureau", "ramene moi au travail")

    /** "Open the route home", "take me to work", « ramène-moi à la maison », « l'itinéraire vers le travail ». */
    private fun route(p: Words, a: Int, to: Int): RoutineStep? {
        for ((list, dest) in listOf(GO_HOME to "home", GO_WORK to "work")) if (p.at(a, list) == to - a) return tool("maps_navigate", "destination" to dest)
        var s = a + p.at(a, ROUTE_VERB)
        if (p.w(s) in ROUTE_DET) s++
        for ((list, dest) in listOf(ROUTE_HOME to "home", ROUTE_WORK to "work")) if (s < to && p.at(s, list) == to - s) return tool("maps_navigate", "destination" to dest)
        return null
    }

    // 1.1 (Skills): a text in French, « texte à Sam que je m'en viens »: the name, then « que », « pour dire » or « qui dit ».
    private val TEXT_FR = listOf("envoie un texto a", "envoie un texte a", "envoie un message a", "envoie un sms a", "ecris a", "texte a", "textes a",
        "texto a", "texte")
    private val SAYS_FR = listOf("pour lui dire que", "pour leur dire que", "pour lui dire", "pour leur dire", "pour dire que", "pour dire", "qui dit que",
        "qui dit", "que", "qu")

    private fun textFr(p: Words, a: Int, to: Int): RoutineStep? {
        val lead = p.at(a, TEXT_FR)
        if (lead == 0) return null
        val from = a + lead
        // The name is one to three words before the first « que » (« dis à Sam que … »).
        for (k in from + 1..minOf(from + 3, to - 1)) {
            val n = p.at(k, SAYS_FR)
            if (n > 0 && k + n < to) return tool("send_text", "to" to p.span(from, k), "message" to p.span(k + n, to))
        }
        return null
    }

    /** A step that does something: an alarm, a timer, the flashlight, maps, a text, an app. */
    private fun action(p: Words, a: Int, to: Int, notes: MutableList<Phrase>, page: UiLabel): RoutineStep? {
        p.at(a, ALARM_STEP).takeIf { it > 0 }?.let { n ->
            val c = clock(p, a + n) ?: return null
            val (per, pn) = clockPeriod(p, c.end)
            if (c.end + pn != to) return null
            val t = resolve(c, per, notes, page)
            return tool("set_alarm", "hour" to t.hour, "minute" to t.minute)
        }
        timer(p, a, to)?.let { return it }
        if (p.at(a, LIGHT_ON) == to - a) return tool("flashlight", "on" to true)
        if (p.at(a, LIGHT_OFF) == to - a) return tool("flashlight", "on" to false)
        route(p, a, to)?.let { return it }
        p.at(a, NAVIGATE).takeIf { it > 0 && a + it < to }?.let { return tool("maps_navigate", "destination" to p.span(a + it, to)) }
        p.at(a, SEARCH).takeIf { it > 0 && a + it < to }?.let { return tool("maps_search", "query" to p.span(a + it, to)) }
        if (p.at(a, "text", "send a text to", "send a message to", "message", "let") > 0) {
            val req = TextCommand.parse(p.span(a, to), typed = false) ?: return null
            return tool("send_text", "to" to req.name, "message" to (req.message ?: return null))
        }
        textFr(p, a, to)?.let { return it }
        p.at(a, OPEN).takeIf { it > 0 && a + it < to }?.let { n ->
            if (p.at(a + n, "a timer", "the timer", "une minuterie", "un minuteur") > 0) return null
            var s = a + n
            while (p.w(s) in setOf("the", "my", "l", "la", "le", "mon", "ma") && s + 1 < to) s++
            var e = to
            if (p.w(e - 1) in setOf("app", "application", "appli") && e - 1 > s) e--
            return tool("open_app", "name" to p.span(s, e))
        }
        return null
    }

    private fun timer(p: Words, a: Int, to: Int): RoutineStep? {
        fun seconds(at: Int): Pair<Long, Int>? {
            val n = p.w(at)?.toIntOrNull() ?: NUMBER_WORDS[p.w(at)] ?: return null
            val unit = UNITS[p.w(at + 1)] ?: return null
            return n.toLong() * unit to at + 2
        }
        val lead = p.at(a, TIMER_EN + TIMER_FR)
        if (lead > 0) {
            val (s, end) = seconds(a + lead) ?: return null
            if (end != to) return null
            return tool("set_timer", "seconds" to s)
        }
        // "start a 10 minute timer".
        val pre = p.at(a, "set a", "start a", "set an", "start an")
        if (pre > 0) {
            val (s, end) = seconds(a + pre) ?: return null
            if (p.w(end) != "timer" || end + 1 != to) return null
            return tool("set_timer", "seconds" to s)
        }
        return null
    }

    private val DET = setOf("my", "the", "a", "an", "mon", "ma", "mes", "la", "le", "les", "l", "un", "une", "about")
    private val WEATHER_WORDS = listOf("weather", "forecast", "weather forecast", "weather report", "temperature", "meteo", "previsions",
        "previsions meteo", "temps qu il fait", "temps quil fait")
    private val DAY_TAIL = mapOf(
        "today" to "today", "for today" to "today", "for the day" to "today", "tomorrow" to "tomorrow", "for tomorrow" to "tomorrow",
        "this weekend" to "this weekend", "for the weekend" to "this weekend", "for this weekend" to "this weekend", "the weekend" to "this weekend", // l10n: not text (words heard, and the day the tool reads)
        "on the weekend" to "this weekend", "this week" to "this week", "for the week" to "this week", "for this week" to "this week", // l10n: not text (words heard, and the day the tool reads)
        "aujourdhui" to "today", "d aujourdhui" to "today", "pour aujourdhui" to "today", "de la journee" to "today", "pour la journee" to "today",
        "demain" to "tomorrow", "de demain" to "tomorrow", "pour demain" to "tomorrow", "de la fin de semaine" to "this weekend", // l10n: not text (words heard, and the day the tool reads)
        "pour la fin de semaine" to "this weekend", "la fin de semaine" to "this weekend", "en fin de semaine" to "this weekend", // l10n: not text (words heard, and the day the tool reads)
        "cette fin de semaine" to "this weekend", "du week end" to "this weekend", "pour le week end" to "this weekend", "ce week end" to "this weekend", // l10n: not text (words heard, and the day the tool reads)
        "de la semaine" to "this week", "pour la semaine" to "this week", "cette semaine" to "this week") // l10n: not text (words heard, and the day the tool reads)
    private val HOME = listOf("at home", "for home", "at my place", "at my house", "at the house", "home", "a la maison", "chez moi", "chez nous",
        "pour la maison", "de la maison")
    private val HERE = listOf("here", "outside", "around here", "for here", "out", "ici", "dehors", "d ici")
    private val PLACE_LEAD = setOf("in", "for", "at", "a", "au", "en", "pour", "de", "dans", "near")
    private val CALENDAR = listOf("calendar", "agenda", "schedule", "day", "events", "appointments", "meetings", "next meeting", "next appointment",
        "next event", "first meeting", "calendrier", "journee", "horaire", "rendez vous", "prochain rendez vous", "prochaine reunion", "reunions",
        "evenements", "rdv", "prochain rdv")
    private val BRIEF = listOf("morning brief", "brief", "briefing", "morning briefing", "daily brief", "resume", "resume du matin", "resume de la journee",
        "bilan du matin", "resume quotidien")
    private val REMINDERS = listOf("reminders", "brief reminders", "morning reminders", "rappels", "rappels du matin")
    private val MESSAGES = listOf("messages", "unread messages", "new messages", "messages non lus", "nouveaux messages")
    private val PHONE = listOf("battery", "battery level", "phone status", "storage", "batterie", "niveau de batterie", "stockage", "etat du telephone")

    /** Something to read or say: the weather, the calendar, the brief, reminders, messages (0.7), the phone, notes. */
    private fun read(p: Words, a0: Int, to: Int): RoutineStep? {
        var a = a0
        while (a < to && p.w(a) in DET) a++
        if (a >= to) return null
        p.at(a, WEATHER_WORDS).takeIf { it > 0 }?.let { return weather(p, a + it, to) }
        p.at(a, BRIEF).takeIf { it == to - a }?.let { return RoutineStep.Brief }
        p.at(a, REMINDERS).takeIf { it == to - a }?.let { return RoutineStep.Reminders }
        p.at(a, MESSAGES).takeIf { it == to - a }?.let { return RoutineStep.Messages }
        p.at(a, PHONE).takeIf { it == to - a }?.let { return tool("phone_status", "about" to p.span(a, to)) }
        p.at(a, CALENDAR).takeIf { it > 0 }?.let { n ->
            val rest = a + n
            if (rest == to) return tool("read_calendar", "day" to "today")
            val d = p.at(rest, DAY_TAIL.keys)
            if (d > 0 && rest + d == to) return tool("read_calendar", "day" to DAY_TAIL.getValue(key(p, rest, d)))
            return null
        }
        if (p.at(a, "notes about", "notes on", "notes sur", "notes a propos de") > 0 && a + 2 < to) {
            val n = p.at(a, "notes about", "notes on", "notes sur", "notes a propos de")
            return tool("find_notes", "query" to p.span(a + n, to))
        }
        return null
    }

    private fun key(p: Words, at: Int, n: Int): String = (at until at + n).joinToString(" ") { p.w(it)!! }

    /** The weather's place and day after "the weather": here, home, a named town, today, tomorrow, the weekend. */
    private fun weather(p: Words, from: Int, to: Int): RoutineStep? {
        var j = from
        var place: String? = null
        var day: String? = null
        while (j < to) {
            val d = p.at(j, DAY_TAIL.keys)
            if (d > 0) { day = DAY_TAIL.getValue(key(p, j, d)); j += d; continue }
            val h = p.at(j, HOME)
            if (h > 0) { place = "home"; j += h; continue }
            val here = p.at(j, HERE)
            if (here > 0) { place = null; j += here; continue }
            if (p.w(j) in PLACE_LEAD && j + 1 < to) {
                // The town runs to the end of the piece, or to a day said after it ("in Quebec City tomorrow").
                var e = to
                for (k in j + 2 until to) if (p.at(k, DAY_TAIL.keys) == to - k) { e = k; break }
                place = p.span(j + 1, e)
                j = e
                continue
            }
            return null
        }
        return tool("weather", "place" to place, "day" to day)
    }
}
