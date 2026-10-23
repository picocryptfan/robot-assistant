// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ToolName
import java.text.Normalizer
import java.util.Locale

// Pure. Skills (docs/plans/skills-1.1-plan.md §3): a name you say, up to 3 questions with a default each, and 1 to 8 of
// Robot's own actions, fixed when the skill is saved. A skill runs on the phone, each step through the same approval gate
// and card as a typed command, so a skill can only add cards, never take one away. Stored in files/robot/skills.json
// (SkillCodec, v1) through SkillStore. Skills are personal: names, values and steps are never logged.

/** How a skill runs when its name is said (plan §7.3). New skills start at [ASK_FIRST] (D14). */
enum class SkillLevel(val id: String) {
    JUST_DO_IT("just_do_it"), ASK_FIRST("ask_first"), OFF("off");

    companion object {
        fun of(id: String?): SkillLevel? = entries.firstOrNull { it.id == id }
    }
}

/** What a question asks for. A value of each type has its own rule ([SkillCheck.validDefault]). */
enum class ParamType(val id: String) {
    TEXT("text"), PERSON("person"), DURATION("duration"), TIME("time"), APP("app"), PLACE("place");

    companion object {
        fun of(id: String?): ParamType? = entries.firstOrNull { it.id == id }
    }
}

/** How the skill was made: taught by voice, "save that" (`demo`), or in Settings. */
enum class SkillMade(val id: String) {
    VOICE("voice"), DEMO("demo"), SETTINGS("settings");

    companion object {
        fun of(id: String?): SkillMade? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A step kind skills may use in 1.1 (plan §6): one of Robot's tools, or [SAY]. [args]: the arguments it may have (the
 * tool's own names, as RoutineCodec.args writes them) and which question type may fill each (null: always fixed).
 */
enum class StepKind(val id: String, val tool: ToolName?, val args: Map<String, ParamType?>) {
    SET_TIMER("set_timer", ToolName.SET_TIMER, mapOf("seconds" to ParamType.DURATION)),
    // A time question fills "hour" with "HH:MM" (ToolCalls reads a clock there); "minute" is then left out.
    SET_ALARM("set_alarm", ToolName.SET_ALARM, mapOf("hour" to ParamType.TIME, "minute" to null)),
    FLASHLIGHT("flashlight", ToolName.FLASHLIGHT, mapOf("on" to null)),
    OPEN_APP("open_app", ToolName.OPEN_APP, mapOf("name" to ParamType.APP)),
    MAPS_SEARCH("maps_search", ToolName.MAPS_SEARCH, mapOf("query" to ParamType.PLACE)),
    MAPS_NAVIGATE("maps_navigate", ToolName.MAPS_NAVIGATE, mapOf("destination" to ParamType.PLACE)),
    SEND_TEXT("send_text", ToolName.SEND_TEXT, mapOf("to" to ParamType.PERSON, "message" to ParamType.TEXT)),
    DRAFT_EMAIL("draft_email", ToolName.DRAFT_EMAIL, mapOf("to" to ParamType.PERSON, "subject" to ParamType.TEXT, "body" to ParamType.TEXT)),
    ADD_NOTE("add_note", ToolName.ADD_NOTE, mapOf("text" to ParamType.TEXT, "title" to ParamType.TEXT)),
    ADD_EVENT("add_event", ToolName.ADD_EVENT, mapOf("title" to ParamType.TEXT, "when" to null, "duration_minutes" to null)),
    WEATHER("weather", ToolName.WEATHER, mapOf("place" to null, "day" to null)),
    READ_CALENDAR("read_calendar", ToolName.READ_CALENDAR, mapOf("day" to null)),
    FIND_NOTES("find_notes", ToolName.FIND_NOTES, mapOf("query" to null)),
    PHONE_STATUS("phone_status", ToolName.PHONE_STATUS, mapOf("about" to null)),
    LOOKUP_CONTACT("lookup_contact", ToolName.LOOKUP_CONTACT, mapOf("name" to null)),
    /** Robot says a sentence of yours. It changes nothing on the phone. */
    SAY("say", null, mapOf("text" to null));

    companion object {
        fun of(id: String?): StepKind? = entries.firstOrNull { it.id == id }
        fun of(t: ToolName): StepKind? = entries.firstOrNull { it.tool == t }

        /** Robot's tools a skill never uses (plan §6): the card names such a step under "Skills can't do this". */
        val NEVER: Set<ToolName> = setOf(ToolName.CALL, ToolName.REMEMBER, ToolName.FORGET, ToolName.ASK_HELPER, ToolName.FREE_MEMORY,
            ToolName.FREE_STORAGE, ToolName.TERMUX_COMMAND, ToolName.TERMUX_SCRIPT, ToolName.WEB_SEARCH, ToolName.BROWSER_SEARCH,
            // 1.4: Robot Files' reads are the model's alone, in a conversation you're in (a skill may run unattended).
            ToolName.FIND_FILES, ToolName.READ_FILE)

        /** Kinds only "save that" makes: teaching never parses them (plan §6). */
        val SAVE_THAT_ONLY: Set<StepKind> = setOf(ADD_NOTE, ADD_EVENT)
    }
}

/**
 * A question: its value fills the arguments that name it, `{{name}}`. Every question has a default (D3). [askMore]: the
 * question in languages this Robot doesn't speak ("es", "de", from a later Robot's skills.json), kept as read and written
 * back unchanged; 1.1 never asks it.
 */
data class SkillParam(val name: String, val type: ParamType, val default: String, val askEn: String, val askFr: String,
    val askMore: Map<String, String> = emptyMap())

/**
 * One step. [args]: each value a constant (a String, a whole number or a Boolean, as the tool takes it) or exactly
 * `{{param}}`. A duration's value is its seconds, a time's "HH:MM".
 */
data class SkillStep(val kind: StepKind, val args: Map<String, Any?>) {
    /** The questions it names, in argument order. */
    val templates: List<String> get() = args.values.mapNotNull { SkillTemplate.name(it) }

    companion object {
        /** A step with its whole numbers as Long (as the file reads them back), so two equal steps compare equal. */
        fun of(kind: StepKind, args: Map<String, Any?>): SkillStep = SkillStep(kind, args.mapValues { (_, v) -> if (v is Int) v.toLong() else v })
    }
}

data class SkillProvenance(val made: SkillMade, val fromContent: Boolean, val created: Long, val edited: Long)

/**
 * A skill (plan §3). [namesEn] and [namesFr]: what you say to run it, the name you said included in its language's list.
 * [rev] goes up when the name, the other names, a question or a step changes; a level change leaves it. [namesMore]: its
 * names in languages this Robot doesn't speak, by language key ("es", "de", from a later Robot's skills.json), kept as
 * read and written back unchanged. They never run it here and are left out of [allNames] and [keys].
 */
data class Skill(
    val id: String,
    val rev: Int,
    val name: String,
    val namesEn: List<String>,
    val namesFr: List<String>,
    val params: List<SkillParam>,
    val steps: List<SkillStep>,
    val level: SkillLevel,
    val provenance: SkillProvenance,
    val namesMore: Map<String, List<String>> = emptyMap(),
) {
    /** Every name it answers to, the name included, as said. */
    val allNames: List<String> get() = (listOf(name) + namesEn + namesFr).distinctBy { SkillKey.of(it) }

    /** The folded keys of [allNames]. */
    val keys: Set<String> get() = allNames.map { SkillKey.of(it) }.filter { it.isNotEmpty() }.toSet()

    fun param(name: String): SkillParam? = params.firstOrNull { it.name == name }

    /** Same skill apart from what Settings' level row and **These steps are mine** change. */
    fun sameContent(o: Skill): Boolean = name == o.name && namesEn == o.namesEn && namesFr == o.namesFr && namesMore == o.namesMore &&
        params == o.params && steps == o.steps

    /** The `{{param}}`'s defaults filled in: the arguments a step runs with when nothing else was said. */
    fun withValues(step: SkillStep, values: Map<String, String> = emptyMap()): Map<String, Any?> = step.args.mapValues { (_, v) ->
        val p = SkillTemplate.name(v)?.let(::param) ?: return@mapValues v
        SkillTemplate.value(p.type, values[p.name] ?: p.default)
    }
}

/** `{{param}}` in an argument. */
object SkillTemplate {
    private val RX = Regex("^\\{\\{([a-z][a-z0-9_]{0,15})\\}\\}$")

    fun of(name: String): String = "{{$name}}" // l10n: not text (a template in the skills file)

    /** The question [v] names when it is a template, else null. */
    fun name(v: Any?): String? = (v as? String)?.let { RX.matchEntire(it)?.groupValues?.get(1) }

    /** Something that looks like a template but isn't a valid one (a typo in the file): refused. */
    fun looksLike(v: Any?): Boolean = (v as? String)?.let { "{{" in it || "}}" in it } == true

    /** A value of [type] as the tool's argument takes it: a duration's seconds as a number, the rest as text. */
    fun value(type: ParamType, v: String): Any? = if (type == ParamType.DURATION) v.toLongOrNull() ?: v else v
}

/** How names are compared: folded (no accents or case), letters and digits only, single spaces. */
object SkillKey {
    fun of(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace("œ", "oe").replace("æ", "ae").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun words(s: String): List<String> = of(s).split(' ').filter { it.isNotEmpty() }
}

/** A new skill's id: "s-" and 6 lowercase letters or digits, random. */
object SkillIds {
    private const val ABC = "abcdefghijklmnopqrstuvwxyz0123456789" // l10n: not text (an id's alphabet)

    fun make(r: java.util.Random = java.security.SecureRandom()): String = "s-" + (1..6).map { ABC[r.nextInt(ABC.length)] }.joinToString("") // l10n: not text (an id)
}
