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
}

object ToolRouter {
    /** English plus the obvious French words. Single words also match their plain endings (texts, texting, notes). */
    private val PEOPLE = setOf("text", "message", "tell", "reply", "email", "mail", "write to", "number", "contact", "phone",
        "sms", "e mail", "texto", "courriel", "telephone")
    private val NOTES = setOf("note", "remember", "write down", "list", "jot", "liste")
    private val CALENDAR = setOf("calendar", "event", "appointment", "meeting", "schedule", "agenda", "busy", "free", "today",
        "tomorrow", "tonight", "weekend", "rendez vous", "rdv", "calendrier", "reunion", "demain", "aujourdhui") +
        WEEKDAYS + MONTHS + JOURS
    private val WEEKDAYS get() = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val JOURS get() = setOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    /** Not "march" or "may": they are everyday verbs as well. */
    private val MONTHS get() = setOf("january", "february", "april", "june", "july", "august", "september", "october", "november", "december")

    /** "tell me a joke" is not about telling someone: "tell" counts only when it isn't followed by me or us. */
    private val NOT_PEOPLE = setOf("tell me", "tell us")

    /** The groups this question needs: always CORE, plus each group one of its words appears in. */
    fun route(question: String): Set<ToolGroup> {
        val words = words(question)
        val padded = " " + words.joinToString(" ") + " "
        fun has(keys: Set<String>) = keys.any { k ->
            if (' ' in k) padded.contains(" $k ")
            else words.withIndex().any { (i, w) -> inflects(w, k) && !(k == "tell" && i + 1 < words.size && "tell ${words[i + 1]}" in NOT_PEOPLE) }
        }
        return buildSet {
            add(ToolGroup.CORE)
            if (has(PEOPLE)) add(ToolGroup.PEOPLE)
            if (has(NOTES)) add(ToolGroup.NOTES)
            if (has(CALENDAR)) add(ToolGroup.CALENDAR)
        }
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
     * send_text when the texting app is Off.
     */
    fun tools(groups: Set<ToolGroup>, settings: RobotSettings): List<ToolName> = ToolName.entries.filter { t ->
        t.group in groups && settings.approval(t) != Approval.OFF && !(t == ToolName.SEND_TEXT && settings.textApp == TextApp.OFF)
    }

    /** "core.people" for the logs (a fixed identifier, never the question). */
    fun label(tools: Collection<ToolName>): String =
        ToolGroup.entries.filter { g -> tools.any { it.group == g } }.joinToString(".") { it.name.lowercase(Locale.ROOT) }.ifEmpty { "none" }

    private fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            .replace("'", "").replace("’", "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** text → texts, texted, texting; note → notes, noted; schedule → scheduled, scheduling. */
    private fun inflects(w: String, k: String): Boolean =
        w == k || w == k + "s" || w == k + "es" || w == k + "ed" || w == k + "d" || w == k + "ing" ||
            (k.endsWith("e") && w == k.dropLast(1) + "ing")
}
