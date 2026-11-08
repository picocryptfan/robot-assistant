// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ToolName
import java.text.Normalizer
import java.util.Locale

// Pure. Skills (docs/plans/skills-1.1-plan.md §3): a name you say, up to 3 questions with a default each, and 1 to 8 of
// Robot's own actions, fixed when the skill is saved. A skill runs on the phone, each step through the same approval gate
// and card as a typed command, so a skill can only add cards, never take one away. Stored in files/robot/skills.json
// (SkillCodec, v1 or v2) through SkillStore. Skills are personal: names, values and steps are never logged.
//
// Skills connections (docs/plans/skills-connections-plan.md §5.1, C1) adds, all in skills.json v2 (D6): a step's `id`,
// its condition (`if`), its `check` and `on_fail`; the skill's `limits`; the question types choice, integer, number and
// yes_no; templates naming an earlier step's output (`{{eta.minutes}}`) and templates inside a text argument ("On my way,
// about {{eta.minutes}} min."); and the steps `conn:<connection>:<operation>`, `app:<connection>`, `travel_time` and
// `termux_script`, whose details live in connections.json and Termux (SkillLinks), never in this file.

/** How a skill runs when its name is said (plan §7.3). New skills start at [ASK_FIRST] (D14). */
enum class SkillLevel(val id: String) {
    JUST_DO_IT("just_do_it"), ASK_FIRST("ask_first"), OFF("off");

    companion object {
        fun of(id: String?): SkillLevel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a question asks for. A value of each type has its own rule ([SkillCheck.validDefault], [SkillCheck.validValue]).
 * [since]: the skills file version that holds it (connections plan D6). Choice, integer, number and yes_no also type a
 * connection's slots and a step's outputs (SlotType).
 */
enum class ParamType(val id: String, val since: Int = 1) {
    TEXT("text"), PERSON("person"), DURATION("duration"), TIME("time"), APP("app"), PLACE("place"),
    /** One of the values you listed (`options`), never anything else. */
    CHOICE("choice", 2),
    /** A whole number within `min` and `max`. */
    INTEGER("integer", 2),
    /** A number within `min` and `max`. */
    NUMBER("number", 2),
    YES_NO("yes_no", 2);

    /**
     * Words someone wrote, of any shape: never a condition's value (design §3.4), so text a page or a message wrote can't
     * steer which steps run.
     */
    val free: Boolean get() = this == TEXT || this == PERSON || this == APP || this == PLACE

    companion object {
        fun of(id: String?): ParamType? = entries.firstOrNull { it.id == id }
    }
}

/** A choice question's value: [value] as the file and the steps hold it, [en] and [fr] as you hear and see it. [more]: a later Robot's languages, kept. */
data class SkillOption(val value: String, val en: String, val fr: String, val more: Map<String, String> = emptyMap())

/**
 * How the skill was made: taught by voice, "save that" (`demo`), or in Settings; 1.3 (format v3, [since] 3): a routine you
 * had ([ROUTINE], RoutineMigration), a draft made on the phone or by a helper ([DRAFT_DEVICE], [DRAFT_HELPER]), or a file
 * you imported ([IMPORT], with [SkillProvenance.imported]). [since]: the skills file version that holds it (D6).
 */
enum class SkillMade(val id: String, val since: Int = 1) {
    VOICE("voice"), DEMO("demo"), SETTINGS("settings"),
    ROUTINE("routine", since = 3), DRAFT_DEVICE("draft_device", since = 3), DRAFT_HELPER("draft_helper", since = 3), IMPORT("import", since = 3);

    companion object {
        fun of(id: String?): SkillMade? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A step kind (1.1 plan §6, connections plan §5.1): one of Robot's tools, [SAY], or a step that reaches one of your
 * connections, apps or scripts. [args]: the arguments it may have (the tool's own names, as RoutineCodec.args writes them)
 * and which question type may fill each (null: always fixed); empty for [linked] kinds, whose arguments are the
 * operation's, the hand-off's or the script's own (SkillLinks). [since]: the skills file version that holds it (D6).
 */
enum class StepKind(val id: String, val tool: ToolName?, val args: Map<String, ParamType?>, val since: Int = 1) {
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
    SAY("say", null, mapOf("text" to null)),
    // Skills connections (plan §5.1). [tool] stays null: they aren't Robot's own actions. They run as ToolName.CONNECTION and
    // HAND_OFF ([runsAs], C4), a typed script as TERMUX_SCRIPT with a typed argument list (C8). Until a step's link exists, SkillCheck.attention says so and the skill
    // doesn't run (SkillPolicy). Never captured by "save that" or teaching: StepKind.of(ToolName) never gives one, so an
    // untyped Termux script never becomes a step (D18), and a connection's card never does either.
    /** An operation of an HTTP connection: `conn:<connection id>:<operation id>`. */
    CONN("conn", null, emptyMap(), since = 2),
    /** A hand-off to an app you picked: `app:<connection id>`. */
    APP("app", null, emptyMap(), since = 2),
    /** The travel time home or to work, from your routing server: `to` is "home" or "work", `conn` names the server when you have two. */
    TRAVEL_TIME("travel_time", null, mapOf("to" to null, "conn" to null), since = 2),
    /** One of your typed Termux scripts: `script` (its name) and its declared parameters, one argument each (D18). */
    TERMUX_SCRIPT("termux_script", null, emptyMap(), since = 2),
    // 1.3, routines as skills (design §14.4): a routine's own steps, as RoutineStep has them. No arguments, no tool.
    /** The morning brief (RoutineStep.Brief): time, weather, today's calendar, brief reminders. */
    BRIEF("brief", null, emptyMap(), since = 3),
    /** Today's brief reminders (RoutineStep.Reminders). */
    REMINDERS("reminders", null, emptyMap(), since = 3),
    /** How many unread messages you have (RoutineStep.Messages). */
    MESSAGES("messages", null, emptyMap(), since = 3);

    /** A routine's own step (1.3): it runs as the routine's step does, never as a tool. */
    val routineOnly: Boolean get() = this == BRIEF || this == REMINDERS || this == MESSAGES

    /** Its arguments come from what it links to (SkillLinks), not from [args]. */
    val linked: Boolean get() = this == CONN || this == APP || this == TERMUX_SCRIPT

    /** A step that only reads: `on_fail: go_on` is allowed after it (plan §5.1). A connection's operation says for itself (LinkedOp.read). */
    val reads: Boolean get() = this in READS

    /**
     * The tool a step of this kind runs as, through TurnRunner (C4): Robot's own for 1.1's kinds; CONNECTION for an operation
     * of yours and the travel time, HAND_OFF for an app you picked, TERMUX_SCRIPT for a typed script (C8: its typed carrier,
     * never the model's string of arguments). Null: it doesn't run as a tool (SAY).
     */
    val runsAs: ToolName? get() = tool ?: when (this) {
        CONN, TRAVEL_TIME -> ToolName.CONNECTION
        APP -> ToolName.HAND_OFF
        TERMUX_SCRIPT -> ToolName.TERMUX_SCRIPT
        else -> null
    }

    /** The arguments that take text, where templates may sit inside the words (plan §5.1): SAY's sentence too. */
    val textArgs: Set<String> get() = if (this == SAY) setOf("text") else args.filterValues { it == ParamType.TEXT }.keys

    /** The outputs it always has (design §3.6); a connection's operation declares its own (LinkedOp.outputs). */
    val outputs: Map<String, SlotType> get() = when (this) {
        TRAVEL_TIME -> mapOf("minutes" to SlotType(ParamType.INTEGER), "km" to SlotType(ParamType.NUMBER))
        // `out`: the script's cleaned output, text from outside Robot (ONLINE-like, plan §3.6).
        TERMUX_SCRIPT -> mapOf("exit" to SlotType(ParamType.INTEGER), "out" to SlotType(ParamType.TEXT))
        else -> emptyMap()
    }

    companion object {
        /** The kind a `use` names on its own ("send_text", "travel_time"); `conn:` and `app:` steps name a connection too (StepUse). */
        fun of(id: String?): StepKind? = entries.firstOrNull { it.id == id && it != CONN && it != APP }
        fun of(t: ToolName): StepKind? = entries.firstOrNull { it.tool == t }

        private val READS = setOf(WEATHER, READ_CALENDAR, FIND_NOTES, PHONE_STATUS, LOOKUP_CONTACT, TRAVEL_TIME)

        /** Robot's tools a skill never uses (plan §6): the card names such a step under "Skills can't do this". */
        val NEVER: Set<ToolName> = setOf(ToolName.CALL, ToolName.REMEMBER, ToolName.FORGET, ToolName.ASK_HELPER, ToolName.FREE_MEMORY,
            ToolName.FREE_STORAGE, ToolName.TERMUX_COMMAND, ToolName.TERMUX_SCRIPT, ToolName.WEB_SEARCH, ToolName.BROWSER_SEARCH,
            // 1.4: Robot Files' reads are the model's alone, in a conversation you're in (a skill may run unattended).
            ToolName.FIND_FILES, ToolName.READ_FILE,
            // 1.4: Lists' model tool is the model's alone too (lists-plan.md §10: lists as skill steps come later).
            ToolName.ADD_TO_LIST,
            // The everyday set: a sum is the model's, worked out by code in your turn; a skill's steps are fixed.
            ToolName.CALCULATE,
            // Connections (C4): a step reaches them only through a link (conn:, app:, travel_time), never as one of Robot's own.
            ToolName.CONNECTION, ToolName.HAND_OFF,
            // 1.5 L7: asking another Robot is yours, in a conversation you're in.
            ToolName.ASK_ROBOT)

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
    val askMore: Map<String, String> = emptyMap(),
    /** A choice's values, 2 to 12 (connections plan §5.1). */
    val options: List<SkillOption> = emptyList(),
    /** An integer's or a number's limits, both required; [unit] optional ([SkillCheck.UNITS]). */
    val min: Double? = null, val max: Double? = null, val unit: String? = null) {
    fun option(value: String): SkillOption? = options.firstOrNull { it.value == value }
}

/** A step's condition (plan §5.1): one test on one typed value, a question or an earlier step's output. Never free text. */
data class SkillIf(val value: SkillRef, val test: IfTest) {
    /** Whether it holds, with [lookup] giving each value as typed ([SkillTemplate.typed]): null when it isn't set. */
    fun holds(lookup: (SkillRef) -> Any?): Boolean {
        val v = lookup(value)
        return when (test) {
            IfTest.Set -> v != null
            IfTest.Unset -> v == null
            is IfTest.Is -> v is String && v == test.choice
            is IfTest.Yes -> v is Boolean && v == test.yes
            is IfTest.Below -> v is Number && v.toDouble() < test.n
            is IfTest.Above -> v is Number && v.toDouble() > test.n
        }
    }
}

/** What a condition tests. */
sealed interface IfTest {
    /** `"is": "set"`: the value is there (a step's output that came back). */
    data object Set : IfTest
    data object Unset : IfTest
    /** `"is": "<choice value>"`. */
    data class Is(val choice: String) : IfTest
    /** `"is": true` or `false`, for a yes_no value. */
    data class Yes(val yes: Boolean) : IfTest
    /** `"less_than": n`. */
    data class Below(val n: Double) : IfTest
    /** `"more_than": n`. */
    data class Above(val n: Double) : IfTest
}

/** A step's own post-run check (plan §5.1): a JSON pointer's value in an HTTP step's reply, or a regular expression on a script's output. */
sealed interface StepCheck {
    /** RFC 6901 [pointer]; [equals] a String, a whole number (Long), a number (Double) or a Boolean. */
    data class Equals(val pointer: String, val equals: Any) : StepCheck
    data class Matches(val regex: String) : StepCheck
}

/** What a failed step does to the rest (plan §5.1): stop (the default), or go on, allowed only after a read. */
enum class OnFail(val id: String) {
    STOP("stop"), GO_ON("go_on");

    companion object {
        fun of(id: String?): OnFail? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One step. [args]: each value a constant (a String, a whole number or a Boolean, as the tool takes it), exactly
 * `{{param}}` or `{{step.output}}`, or, in a text argument, words with templates inside. A duration's value is its seconds,
 * a time's "HH:MM". [id]: names it for later steps' templates and conditions. [conn] and [op]: what a `conn:` or `app:`
 * step reaches (connections plan §5.1). [cond]: its `if`; [check]: its own post-run check; [onFail]: stop or go on.
 */
data class SkillStep(val kind: StepKind, val args: Map<String, Any?>, val id: String? = null, val conn: String? = null, val op: String? = null,
    val cond: SkillIf? = null, val check: StepCheck? = null, val onFail: OnFail = OnFail.STOP) {
    /** The questions it names, in argument order: whole values, inside text, then its condition's. */
    val templates: List<String> get() = (refs + listOfNotNull(cond?.value)).filter { it.output == null }.map { it.name }.distinct()

    /** Every template in its arguments, whole or inside text, in order. */
    val refs: List<SkillRef> get() = args.values.flatMap { SkillTemplate.refs(it) }

    /** What the file's "use" says: "send_text", "travel_time", "conn:c-ab12cd:light_off", "app:c-ab12cd". */
    val use: String get() = when (kind) {
        StepKind.CONN -> "conn:$conn:$op" // l10n: not text (a step kind in the skills file)
        StepKind.APP -> "app:$conn" // l10n: not text (a step kind in the skills file)
        else -> kind.id
    }

    companion object {
        /** A step with its whole numbers as Long (as the file reads them back), so two equal steps compare equal. */
        fun of(kind: StepKind, args: Map<String, Any?>, id: String? = null, conn: String? = null, op: String? = null, cond: SkillIf? = null,
            check: StepCheck? = null, onFail: OnFail = OnFail.STOP): SkillStep =
            SkillStep(kind, args.mapValues { (_, v) -> if (v is Int) v.toLong() else v }, id, conn, op, cond, check, onFail)

        private val CONN_ID = Regex("^c-[a-z0-9]{6}$")
        private val OP_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
        private val USE_CONN = Regex("^conn:(c-[a-z0-9]{6}):([a-z][a-z0-9_]{0,31})$")
        private val USE_APP = Regex("^app:(c-[a-z0-9]{6})$")

        fun validConnId(id: String?): Boolean = id != null && CONN_ID.matches(id)
        fun validOpId(id: String?): Boolean = id != null && OP_ID.matches(id)

        /** A "use" read back: the kind, and the connection and operation it names; null when it names no kind. */
        fun use(use: String): Triple<StepKind, String?, String?>? {
            USE_CONN.matchEntire(use)?.let { return Triple(StepKind.CONN, it.groupValues[1], it.groupValues[2]) }
            USE_APP.matchEntire(use)?.let { return Triple(StepKind.APP, it.groupValues[1], null) }
            return StepKind.of(use)?.let { Triple(it, null, null) }
        }
    }
}

/** [imported]: what an imported skill came from (design §3.7, format v3); only with [SkillMade.IMPORT] (SkillCheck). Editing never changes it. */
data class SkillProvenance(val made: SkillMade, val fromContent: Boolean, val created: Long, val edited: Long, val imported: SkillImport? = null)

/** An import's file: [fileSha256], its SHA-256 in lowercase hex; [signer], its signing key's fingerprint (64 lowercase hex), or null when unsigned. */
data class SkillImport(val fileSha256: String, val signer: String?)

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
    /** `limits.per_hour` (connections plan D16, 1 to 60); null: not in the file, [DEFAULT_PER_HOUR] applies. */
    val perHour: Int? = null,
) {
    /** Every name it answers to, the name included, as said. */
    val allNames: List<String> get() = (listOf(name) + namesEn + namesFr).distinctBy { SkillKey.of(it) }

    /** The folded keys of [allNames]. */
    val keys: Set<String> get() = allNames.map { SkillKey.of(it) }.filter { it.isNotEmpty() }.toSet()

    fun param(name: String): SkillParam? = params.firstOrNull { it.name == name }

    /** Same skill apart from what Settings' level row and **These steps are mine** change. */
    fun sameContent(o: Skill): Boolean = name == o.name && namesEn == o.namesEn && namesFr == o.namesFr && namesMore == o.namesMore &&
        params == o.params && steps == o.steps

    /** Runs an hour at most (D16). */
    val runsPerHour: Int get() = perHour ?: DEFAULT_PER_HOUR

    /** Question [name]'s value as typed ([SkillTemplate.typed]): [values]' or its default; null when it has no such question. */
    fun typed(name: String, values: Map<String, String> = emptyMap()): Any? = param(name)?.let { SkillTemplate.typed(it.type, values[it.name] ?: it.default) }

    /**
     * The arguments [step] runs with: each `{{param}}` filled with [values]' value or its default, typed as the tool takes it;
     * in a text argument, every template inside the words filled as text, in one pass (a value holding `{{x}}` stays as
     * written). A step's output comes from [outputs] (null: it didn't come back, and it is left out, or empty inside words).
     */
    fun withValues(step: SkillStep, values: Map<String, String> = emptyMap(), outputs: (SkillRef) -> Any? = { null }): Map<String, Any?> {
        fun lookup(r: SkillRef): Any? = if (r.output == null) typed(r.name, values) else outputs(r)
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in step.args) {
            val whole = SkillTemplate.ref(v)
            when {
                // 1.1's form, and a whole value in a typed slot: the value itself, typed.
                whole != null && (k !in step.kind.textArgs || (whole.output == null && param(whole.name)?.type == ParamType.TEXT)) ->
                    lookup(whole)?.let { out[k] = it }
                SkillTemplate.refs(v).isNotEmpty() -> out[k] = SkillTemplate.render(v as String) { r -> lookup(r)?.let(SkillTemplate::text) ?: "" }
                else -> out[k] = v
            }
        }
        return out
    }

    companion object {
        /** D16: a skill runs at most 12 times an hour unless its `limits` say otherwise (EST). */
        const val DEFAULT_PER_HOUR = 12
    }
}

/** `{{name}}` (a question) or `{{step.output}}` (an earlier step's output, [output] not null). */
data class SkillRef(val name: String, val output: String? = null) {
    override fun toString(): String = if (output == null) name else "$name.$output" // l10n: not text (a template's name in the skills file)

    companion object {
        private val RX = Regex("^([a-z][a-z0-9_]{0,15})(?:\\.([a-z][a-z0-9_]{0,15}))?$")

        /** "who" or "eta.minutes", as a condition's `value` holds it. */
        fun of(s: String?): SkillRef? = s?.let { RX.matchEntire(it) }?.let { m -> SkillRef(m.groupValues[1], m.groupValues[2].ifEmpty { null }) }
    }
}

/**
 * Templates in an argument (design §3.5, connections plan §5.1): `{{param}}` or `{{step.output}}` as the whole value, or,
 * in a text argument, inside the words. Nothing else: no expressions, no filters, no nesting. Filled in one pass, so a
 * value that holds `{{x}}` is never read as a template.
 */
object SkillTemplate {
    private val RX = Regex("^\\{\\{([a-z][a-z0-9_]{0,15})\\}\\}$")
    private val WHOLE = Regex("^\\{\\{([a-z][a-z0-9_]{0,15})(?:\\.([a-z][a-z0-9_]{0,15}))?\\}\\}$")
    private val INSIDE = Regex("^([a-z][a-z0-9_]{0,15})(?:\\.([a-z][a-z0-9_]{0,15}))?$")

    fun of(name: String): String = "{{$name}}" // l10n: not text (a template in the skills file)

    fun of(r: SkillRef): String = "{{$r}}" // l10n: not text (a template in the skills file)

    /** The question [v] names when it is exactly `{{param}}`, else null. */
    fun name(v: Any?): String? = (v as? String)?.let { RX.matchEntire(it)?.groupValues?.get(1) }

    /** What [v] names when it is exactly one template, else null. */
    fun ref(v: Any?): SkillRef? = (v as? String)?.let { WHOLE.matchEntire(it) }?.let { m -> SkillRef(m.groupValues[1], m.groupValues[2].ifEmpty { null }) }

    /** Something that looks like a template ("{{" or "}}" anywhere): refused unless [parts] reads it. */
    fun looksLike(v: Any?): Boolean = (v as? String)?.let { "{{" in it || "}}" in it } == true

    /** A piece of a text argument: words as written, or a template. */
    sealed interface Part {
        data class Words(val text: String) : Part
        data class Ref(val ref: SkillRef) : Part
    }

    /** [text] in pieces, or null when a "{{" or "}}" in it isn't a template (a typo in the file: refused). */
    fun parts(text: String): List<Part>? {
        val out = mutableListOf<Part>()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf("{{", i)
            val words = if (open < 0) text.substring(i) else text.substring(i, open)
            if ("}}" in words) return null
            if (words.isNotEmpty()) out += Part.Words(words)
            if (open < 0) break
            val close = text.indexOf("}}", open + 2)
            if (close < 0) return null
            val m = INSIDE.matchEntire(text.substring(open + 2, close)) ?: return null
            out += Part.Ref(SkillRef(m.groupValues[1], m.groupValues[2].ifEmpty { null }))
            i = close + 2
        }
        return out
    }

    /** The templates in [v], whole or inside words; empty for a constant or a value [parts] can't read. */
    fun refs(v: Any?): List<SkillRef> = (v as? String)?.takeIf { looksLike(it) }?.let(::parts)?.filterIsInstance<Part.Ref>()?.map { it.ref }.orEmpty()

    /** [text] with each template replaced by [fill]'s text, in one pass. [text] must read ([parts]). */
    fun render(text: String, fill: (SkillRef) -> String): String =
        parts(text)!!.joinToString("") { p -> when (p) { is Part.Words -> p.text; is Part.Ref -> fill(p.ref) } }

    /** A value of [type] as the tool's argument takes it: a duration's seconds as a number, the rest as text. */
    fun value(type: ParamType, v: String): Any? = if (type == ParamType.DURATION) v.toLongOrNull() ?: v else typed(type, v)

    /**
     * A value of [type] typed (connections plan §5.1): a whole number or a duration's seconds as Long, a number as Double, a
     * yes_no as Boolean, the rest (a choice's value, text) as String. A value that isn't of its type stays text.
     */
    fun typed(type: ParamType, v: String): Any = when (type) {
        ParamType.DURATION, ParamType.INTEGER -> v.toLongOrNull() ?: v
        ParamType.NUMBER -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: v
        ParamType.YES_NO -> when (v) { "true" -> true; "false" -> false; else -> v }
        else -> v
    }

    /** A typed value as words inside a text argument: a whole number as digits, a number without trailing zeros ("2.5"). */
    fun text(v: Any): String = when (v) {
        is Double -> SkillNumbers.text(v)
        is Number -> v.toLong().toString()
        else -> v.toString()
    }
}

/** Numbers in the skills file: written whole when they are, read back the same. */
object SkillNumbers {
    /** "2.5", "20" (never "20.0"). */
    fun text(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()

    /** As the file holds it: a Long when whole (org.json writes 20.0 as 20 and reads it back as a whole number). */
    fun json(d: Double): Number = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d

    /** A number read from the file, or null when it isn't one or isn't finite. */
    fun read(v: Any?): Double? = when (v) {
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Double -> v.takeIf { it.isFinite() }
        else -> null
    }
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
