package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure. The tool schemas are part of every prompt (about 4,200 tokens of context in all), so a conversation is told
// only about the tool groups its questions need (docs/phase-2.md "Fitting the tools"). The router reads the words of
// a question; a conversation keeps the union of every group it has used, so groups are only ever added, and adding
// one rebuilds the conversation once through the existing rebuild path (:brain sees a different tool list).

enum class ToolGroup {
    /** Timer, alarm, open app, the two maps tools, flashlight: always described. */
    CORE,
    PEOPLE,
    NOTES,
    CALENDAR,
    /** The weather (0.4.0): described only when a question speaks of it. */
    WEATHER,
    /** The phone's storage, battery and memory (0.4.0). */
    PHONE,
}

object ToolRouter {
    /**
     * English plus the obvious French words. Single words also match their plain endings (texts, texting, notes).
     * A word too many only costs tokens; a word too few costs a turn in which the model can't do what was asked.
     */
    private val PEOPLE = setOf("text", "message", "tell", "reply", "email", "mail", "write to", "number", "contact", "phone",
        "sms", "e mail", "texto", "courriel", "telephone", "write", "cell", "mobile", "msg", "dm", "ping", "reach", "address",
        "ecris", "ecrire", "dis a", "dire a")
    private val NOTES = setOf("note", "remember", "write down", "list", "jot", "liste", "save", "remind", "reminder", "rappelle")
    private val CALENDAR = setOf("calendar", "event", "appointment", "meeting", "schedule", "agenda", "busy", "free", "today",
        "tomorrow", "tonight", "weekend", "week", "morning", "afternoon", "evening", "coming up", "remind", "reminder",
        "rendez vous", "rdv", "calendrier", "reunion", "demain", "aujourdhui", "rappelle") + WEEKDAYS + MONTHS + JOURS
    private val WEEKDAYS get() = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val JOURS get() = setOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    /** Not "march" or "may": they are everyday verbs as well. */
    private val MONTHS get() = setOf("january", "february", "april", "june", "july", "august", "september", "october", "november", "december")

    /** The weather, English and French ("will it rain", "cloud cover tomorrow", "quel temps", "météo"). */
    private val WEATHER = setOf("weather", "forecast", "rain", "rainy", "snow", "snowy", "temperature", "cloud", "cloudy", "cloud cover",
        "wind", "windy", "umbrella", "sunny", "storm", "stormy", "thunder", "thunderstorm", "hail", "fog", "foggy", "drizzle", "sleet",
        "frost", "freezing", "humid", "humidity", "degree", "degrees", "hot", "cold", "warm", "chilly", "heat", "heatwave", "uv",
        "sunglasses", "outside", "nice out", "the high", "the low", "jacket", "coat", "sunscreen", "chaud", "froid", "chaleur",
        "canicule", "dehors", "frais",
        "meteo", "pluie", "pleuvoir", "pleut", "neige", "neiger", "nuage", "nuageux", "vent", "venteux",
        "parapluie", "soleil", "ensoleille", "orage", "brouillard", "grele", "gel", "degres", "quel temps", "previsions", "manteau")

    /** The phone itself: storage, battery, memory, and freeing them ("free up space", "libérer de la place"). */
    private val PHONE = setOf("storage", "space", "free up", "clean up", "memory", "ram", "battery", "charge", "disk", "gigabytes", "gb",
        "megabytes", "cache", "phone status", "running out", "taking up", "stockage", "espace", "memoire", "batterie", "liberer", "libere",
        "de la place", "nettoyer", "nettoie", "delete", "biggest", "largest", "force stop", "kill", "plein", "pleine", "supprime",
        "supprimer", "efface", "effacer", "plus grosses", "plus gros")
    private val ELISION = Regex("""\b([dljnsmtc]|qu)['’](?=\p{L})""")

    /** "Stop YouTube", "close Chrome", "arrête YouTube": a verb that may stop an app, then (not) one of these (0.4.0). */
    private val STOP_VERBS = setOf("stop", "close", "quit", "kill", "arrete", "arreter", "ferme", "fermer", "quitte")
    private val NOT_AN_APP = setOf("it", "that", "this", "talking", "listening", "reading", "speaking", "playing", "the", "a", "an", "me",
        "now", "please", "there", "here", "timer", "timers", "alarm", "alarms", "recording", "music", "le", "la", "les", "l", "ca", "cela",
        "de", "du", "des", "un", "une", "moi", "tout", "being", "doing", "worrying", "your", "my", "ton", "ta", "mon", "ma")

    /** "tell me a joke" is not about telling someone: "tell" counts only when it isn't followed by me or us. */
    private val NOT_PEOPLE = setOf("tell me", "tell us")
    /** "free up space" is about the phone, not about being free (0.4.0): "free" is a calendar word only otherwise. */
    private val NOT_CALENDAR = setOf("free up", "free space", "free memory", "free storage", "free ram")

    /** "let Sarah know", "let my sister know". */
    private val LET_KNOW = Regex("""\blet (?:\w+ ){1,3}know\b""")
    /** A clock time ("3pm", "3 p.m.", "15:30", "15h", "15h30"), an ordinal day ("the 14th"), March or May with a day. */
    private val WHEN = Regex("""\d\s?[ap]\.?m\b|\d:\d\d|\b\d{1,2}h\d{0,2}\b|\b\d{1,2}(?:st|nd|rd|th)\b|\b(?:march|may) \d{1,2}\b|\b\d{1,2} (?:of )?(?:march|may)\b""")

    /** The groups this question needs: always CORE, plus each group one of its words appears in. */
    fun route(question: String): Set<ToolGroup> {
        val words = words(question)
        val padded = " " + words.joinToString(" ") + " "
        val plain = Normalizer.normalize(question, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        fun has(keys: Set<String>) = keys.any { k ->
            if (' ' in k) padded.contains(" $k ")
            else words.withIndex().any { (i, w) ->
                inflects(w, k) && !(k == "tell" && i + 1 < words.size && "tell ${words[i + 1]}" in NOT_PEOPLE) &&
                    !(k == "free" && i + 1 < words.size && "free ${words[i + 1]}" in NOT_CALENDAR)
            }
        }
        return buildSet {
            add(ToolGroup.CORE)
            if (has(PEOPLE) || LET_KNOW.containsMatchIn(padded)) add(ToolGroup.PEOPLE)
            if (has(NOTES)) add(ToolGroup.NOTES)
            if (has(CALENDAR) || WHEN.containsMatchIn(plain)) add(ToolGroup.CALENDAR)
            if (has(WEATHER)) add(ToolGroup.WEATHER)
            if (has(PHONE) || stopsAnApp(words)) add(ToolGroup.PHONE)
        }
    }

    /** "Stop YouTube", "close the Signal app", "arrête Chrome"; not "stop the timer", "stop talking" or "close the door". */
    private fun stopsAnApp(words: List<String>): Boolean = words.withIndex().any { (i, w) ->
        w in STOP_VERBS && i + 1 < words.size && (words[i + 1] !in NOT_AN_APP || words.drop(i + 1).any { it == "app" || it == "appli" || it == "application" })
    }

    /** The groups a saved conversation already used: its questions' words and the tools its answers ran. */
    fun used(turns: List<ChatTurn>): Set<ToolGroup> = buildSet {
        add(ToolGroup.CORE)
        for (t in turns) {
            if (t.role == TurnRole.USER) addAll(route(t.text))
            t.actions.forEach { add(it.tool.group) }
        }
    }

    /**
     * The tools described for [groups], in a fixed order. Tools turned Off in settings are never described, nor
     * send_text when the texting app is Off. ask_claude only with [askClaude] (Ask Claude on, escalation not Off:
     * ClaudeSettings.localTool).
     */
    fun tools(groups: Set<ToolGroup>, settings: RobotSettings, askClaude: Boolean = false): List<ToolName> = ToolName.entries.filter { t ->
        t.group in groups && settings.approval(t) != Approval.OFF && !(t == ToolName.SEND_TEXT && settings.textApp == TextApp.OFF) &&
            (t != ToolName.ASK_CLAUDE || askClaude)
    }

    /** "core.people" for the logs (a fixed identifier, never the question). */
    fun label(tools: Collection<ToolName>): String =
        ToolGroup.entries.filter { g -> tools.any { it.group == g } }.joinToString(".") { it.name.lowercase(Locale.ROOT) }.ifEmpty { "none" }

    private fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            // French elision is two words ("d'espace", "l'appli"); "what's" and "aujourd'hui" stay one.
            .replace(ELISION, "$1 ")
            .replace("'", "").replace("’", "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** text → texts, texted, texting; note → notes, noted; schedule → scheduled, scheduling. */
    private fun inflects(w: String, k: String): Boolean =
        w == k || w == k + "s" || w == k + "es" || w == k + "ed" || w == k + "d" || w == k + "ing" ||
            (k.endsWith("e") && w == k.dropLast(1) + "ing")
}
