// SPDX-License-Identifier: GPL-3.0-only
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
    /** 0.7: remember and forget, described only with Memory on (Settings › Memory) and words that call for them. */
    MEMORY,
    /** Web search (0.9): described only when the words call for it (core web WebRouter) and web search is on. */
    WEB,
    /** 0.9: Termux (TermuxRouter's words), described only while Settings › Termux is on. */
    TERMUX,
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
    /** 0.7, what you ask Robot to keep about you or drop ("remember that …", "souviens-toi", "oublie ça"). */
    private val MEMORY = setOf("remember", "forget", "know about me", "keep in mind", "memorize", "memorise", "souviens", "rappelle toi",
        "retiens", "oublie", "garde en memoire", "sais sur moi")
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
            // "Remember to buy milk" stays a note (MemoryCommands.staysNote): memory's tools aren't described for it.
            if (has(MEMORY) && !app.heyrobot.core.memory.MemoryCommands.staysNote(question)) add(ToolGroup.MEMORY)
            if (app.heyrobot.core.web.WebRouter.wants(question)) add(ToolGroup.WEB)
            if (app.heyrobot.core.termux.TermuxRouter.calls(question)) add(ToolGroup.TERMUX)
        }
    }

    /**
     * The always-described tools, each by its own words (0.4.0, "Safety cards: When you didn't ask for it"): CORE is in
     * every route, so it can't say which of them the person asked for.
     */
    private val TIMER = setOf("timer", "countdown", "minuteur", "minuterie", "chrono", "chronometre")
    private val ALARM = setOf("alarm", "wake me", "wake up", "alarme", "reveil", "reveille", "reveiller")
    private val LIGHT = setOf("flashlight", "torch", "light", "lamp", "lampe", "lumiere", "flash")
    private val OPEN = setOf("open", "launch", "start", "ouvre", "ouvrir", "lance", "lancer", "demarre")
    private val MAP = setOf("map", "maps", "directions", "direction", "navigate", "navigation", "route", "take me", "get to", "how do i get",
        "where is", "wheres", "find", "nearest", "closest", "near me", "nearby", "carte", "itineraire", "aller a", "emmene", "trouve",
        "plus proche", "pres de", "ou est")

    /**
     * The actions that write or leave the phone, each asked for only by a word that asks for it (the router's topic words
     * are too wide for this: "what does this message say?" routes to texting, "is it open today?" to the calendar).
     * Exact words, not their endings ("messages", "texts" and "emails" are things, not requests).
     */
    private val TEXT_ASK = setOf("text", "texto", "sms", "message", "msg", "dm", "tell", "reply", "ping", "send", "write to", "ecris", "ecrire",
        "envoie", "envoyer", "dis a", "dire a", "reponds", "repondre")
    private val EMAIL_ASK = setOf("email", "e mail", "mail", "courriel", "send", "reply", "write to", "ecris", "ecrire", "envoie", "envoyer",
        "reponds", "repondre")
    private val NOTE_ASK = setOf("note", "remember", "remind", "write down", "jot", "save", "take a note", "make a note", "retiens", "souviens",
        "rappelle", "enregistre", "sauvegarde", "prends note")
    /** A new event: one of these, with the calendar's words ([route]'s CALENDAR: a day, a time, "calendar"). */
    private val EVENT_ASK = setOf("schedule", "book", "remind", "rappelle", "planifie", "reserve", "programme")
    /** "Add milk to my list", "put lunch on Friday": a note or an event, by the group the rest of the words name. */
    private val ADD = setOf("add", "put", "create", "make", "ajoute", "ajouter", "mets", "mettre", "cree", "creer")
    /** Freeing memory or storage: with the phone's words ([route]'s PHONE). */
    private val FREE_ASK = setOf("free up", "clean up", "clear", "delete", "remove", "kill", "force stop", "stop", "close", "quit", "unload",
        "liberer", "libere", "nettoie", "nettoyer", "supprime", "supprimer", "efface", "effacer", "vide", "vider", "ferme", "fermer", "arrete",
        "arreter", "quitte")
    /** 0.7: keeping something about you ([route]'s MEMORY): a memory the model proposes without these words is a card. */
    private val REMEMBER_ASK = setOf("remember", "keep in mind", "memorize", "memorise", "souviens", "rappelle toi", "retiens", "garde en memoire",
        "n oublie pas", "call me", "appelle moi", "i prefer", "je prefere")
    private val FORGET_ASK = setOf("forget", "oublie", "oublier")
    /** "Tell me a joke", "can you tell what this is": not about telling someone. */
    private val NOT_TOLD = setOf("me", "us", "what", "whether", "if", "how", "why", "who", "when", "where", "which", "the", "a", "apart")
    /**
     * Right before a word, these make it a thing named, not an action asked for: "this message", "the email", "my
     * calendar", "cette note". An action's word never follows one.
     */
    private val NAMING = setOf("this", "that", "these", "those", "the", "a", "an", "my", "your", "his", "her", "their", "our", "its", "any",
        "new", "last", "latest", "ce", "cet", "cette", "ces", "le", "la", "les", "l", "un", "une", "mon", "ma", "mes", "ton", "ta", "tes", "son",
        "sa", "ses", "notre", "nos", "votre", "vos", "leur", "leurs", "du", "des", "de", "d")

    /**
     * The actions the person asked for in their own words ([question]: what they typed or said this turn, never screen
     * text, a photo or what a tool found), for "Safety cards: When you didn't ask for it". Each always-described tool by
     * its own words; each action that writes or leaves the phone by a word that asks for it, never one that only names
     * a thing ([NAMING]); the read tools by their group's words ([route]): they only matter for the weather, which goes
     * online. A word missed here only shows a card; a word too many skips one, so the lists ask rather than guess.
     * Robot's own ask_claude never counts (it always shows its card).
     */
    fun asked(question: String): Set<ToolName> {
        val words = words(question)
        val padded = " " + words.joinToString(" ") + " "
        fun has(keys: Set<String>) = keys.any { k -> if (' ' in k) padded.contains(" $k ") else words.any { inflects(it, k) } }
        /** One of [keys] as a request: the word itself (no ending), not right after a word that names a thing. */
        fun asks(keys: Set<String>): Boolean = keys.any { k ->
            val n = k.split(' ')
            (0..words.size - n.size).any { i ->
                (0 until n.size).all { j -> words[i + j] == n[j] } && (i == 0 || words[i - 1] !in NAMING) &&
                    !(k == "tell" && i + 1 < words.size && words[i + 1] in NOT_TOLD)
            }
        }
        val groups = route(question)
        return buildSet {
            if (has(TIMER)) add(ToolName.SET_TIMER)
            if (has(ALARM)) add(ToolName.SET_ALARM)
            if (has(LIGHT)) add(ToolName.FLASHLIGHT)
            if (has(OPEN)) add(ToolName.OPEN_APP)
            if (has(MAP)) { add(ToolName.MAPS_SEARCH); add(ToolName.MAPS_NAVIGATE) }
            if (asks(TEXT_ASK) || LET_KNOW.containsMatchIn(padded)) add(ToolName.SEND_TEXT)
            if (asks(EMAIL_ASK)) add(ToolName.DRAFT_EMAIL)
            if (asks(NOTE_ASK) || (asks(ADD) && ToolGroup.NOTES in groups)) add(ToolName.ADD_NOTE)
            if ((asks(EVENT_ASK) || asks(ADD)) && ToolGroup.CALENDAR in groups) add(ToolName.ADD_EVENT)
            if (asks(FREE_ASK) && ToolGroup.PHONE in groups) { add(ToolName.FREE_MEMORY); add(ToolName.FREE_STORAGE) }
            if (ToolGroup.MEMORY in groups && has(REMEMBER_ASK)) add(ToolName.REMEMBER)
            if (ToolGroup.MEMORY in groups && has(FORGET_ASK)) add(ToolName.FORGET)
            for (t in ToolName.entries) if (t.reads && t.group != ToolGroup.CORE && t.group in groups) add(t)
            remove(ToolName.ASK_HELPER)
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
     * send_text when the texting app is Off. ask_claude only with [askHelper] (a helper on, escalation not Off:
     * HelperSettings.localTool). remember and forget only with [memory] (0.7: Settings › Memory's main switch); web_search only
     * with [web] (0.9: Settings › Web search on and set up); a Termux tool only when in [termux] (0.9: Settings › Termux on,
     * set up, and that tool on); browser_search only with [browser] (1.0: the question names a search engine or the browser).
     */
    fun tools(groups: Set<ToolGroup>, settings: RobotSettings, askHelper: Boolean = false, memory: Boolean = false, web: Boolean = false,
        termux: Set<ToolName> = emptySet(), browser: Boolean = false): List<ToolName> = ToolName.entries.filter { t ->
        t.group in groups && t.forModel && settings.approval(t) != Approval.OFF && !(t == ToolName.SEND_TEXT && settings.textApp == TextApp.OFF) &&
            (t != ToolName.ASK_HELPER || askHelper) && (t.group != ToolGroup.MEMORY || memory) && (t != ToolName.WEB_SEARCH || web) && (!t.termux || t in termux) &&
            (t != ToolName.BROWSER_SEARCH || browser)
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
