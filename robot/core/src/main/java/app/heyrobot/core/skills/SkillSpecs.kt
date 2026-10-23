// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONArray
import org.json.JSONObject

// Pure. Phase B (plan §8, D20): how a skill is described to Robot's model. Each skill found for your words is a tool
// `skill_<slug>`: its name folded to [a-z0-9_], at most 24 characters, the same for a skill from one turn to the next.
// Its description is an English line computed from its steps' kinds ("Texts someone, then opens a route."), at most 90
// characters, and its parameters are its questions, each described by its English ask, at most 30 characters. The model
// is never told the steps or their fixed values (D20: "Sam", "On my way home." stay on the phone), and the line isn't
// stored. There is no ToolName for a skill: :brain gets these specs beside the static tools (BrainMsg.Generate.skills).
// A slug comes from your skill's name: never logged.

/** One skill as a tool for the model. [name] is `skill_<slug>`; [params]: each question's name and English ask. */
data class SkillToolSpec(val name: String, val description: String, val params: List<Pair<String, String>>) {
    /** {"name", "description", "parameters": {...}} as LiteRT-LM's OpenApiTool wants it, the static tools' own shape (ToolSpec). */
    fun openApiJson(): String = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object") // l10n: not text (OpenAPI)
            put("properties", JSONObject().apply { for ((n, d) in params) put(n, JSONObject().put("type", "string").put("description", d)) }) // l10n: not text (OpenAPI)
            // Every question has a default (D3): none is required.
            put("required", JSONArray())
        })
    }.toString()

    fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description)
        .put("params", JSONArray().apply { params.forEach { (n, d) -> put(JSONObject().put("name", n).put("ask", d)) } })

    companion object {
        private val NAME = Regex("^skill_[a-z0-9_]{1,${SkillSpecs.MAX_SLUG}}$")

        /**
         * A spec as the wire carries it (BrainMsg.Generate), or null when it is anything but one the app could have made: a
         * name that isn't a skill's, a line or an ask too long, more than 3 questions, a question name a skill can't have.
         */
        fun fromJson(o: JSONObject?): SkillToolSpec? = try {
            o ?: return null
            val name = o.getString("name")
            val description = o.getString("description")
            val a = o.getJSONArray("params")
            val params = (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("name") to it.getString("ask") } }
            val ok = NAME.matches(name) && description.length <= SkillSpecs.MAX_LINE && params.size <= SkillCheck.MAX_PARAMS &&
                params.all { (n, d) -> SkillCheck.validParamName(n) && d.length <= SkillSpecs.MAX_ASK } && params.map { it.first }.distinct().size == params.size
            if (ok) SkillToolSpec(name, description, params) else null
        } catch (_: Exception) {
            null
        }
    }
}

/** A skill as described this turn: its tool, and which skill and revision it was made from (a call is checked against both). */
data class SkillTool(val spec: SkillToolSpec, val skillId: String, val rev: Int) {
    val name: String get() = spec.name
}

object SkillSpecs {
    const val PREFIX = "skill_" // l10n: not text (the skill tools' name prefix the model sees)
    const val MAX_SLUG = 24
    const val MAX_LINE = 90
    const val MAX_ASK = 30

    /**
     * The one system line when skills are described (about 25 tokens). Gemma reads "the person" as the other lines do
     * (Prompt.system).
     */
    const val SYSTEM_LINE = "Your own skills are tools named skill_…; call one only when the person asks for what it does." // l10n: not text (the model's prompt)

    /**
     * Each of [all] (your skills) by its slug, unique and the same from turn to turn: the name folded, made unique in the
     * order of the skills' ids ("skill_pasta", "skill_pasta_2").
     */
    fun slugs(all: List<Skill>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val taken = HashSet<String>()
        for (s in all.sortedBy { it.id }) {
            val base = slug(s.name)
            var n = 1
            var x = base
            while (x in taken) {
                n++
                val tail = "_$n"
                x = base.take(MAX_SLUG - tail.length).trimEnd('_') + tail
            }
            taken += x
            out[s.id] = x
        }
        return out
    }

    /** A name folded to [a-z0-9_], at most [MAX_SLUG] characters; "skill" when nothing of it is left (a name in another script). */
    fun slug(name: String): String {
        val folded = SkillKey.words(name).joinToString("_").filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            .replace(Regex("_+"), "_").trim('_')
        return folded.take(MAX_SLUG).trimEnd('_').ifEmpty { "skill" } // l10n: not text (a tool name)
    }

    /** [skills] as tools, their slugs computed among [all] (your skills). */
    fun describe(skills: List<Skill>, all: List<Skill>): List<SkillTool> {
        val slugs = slugs((all + skills).distinctBy { it.id })
        return skills.map { s -> SkillTool(SkillToolSpec(PREFIX + slugs.getValue(s.id), line(s), s.params.map { it.name to ask(it.askEn) }), s.id, s.rev) }
    }

    /** An ask cut to [MAX_ASK] characters on a word. */
    fun ask(en: String): String {
        val t = en.trim()
        if (t.length <= MAX_ASK) return t
        val cut = t.take(MAX_ASK)
        val space = cut.lastIndexOf(' ')
        return (if (space > MAX_ASK / 2) cut.substring(0, space) else cut).trimEnd(',', ';', ':', ' ')
    }

    /** What a step kind does, in the model's English, without its values (D20). */
    private fun does(k: StepKind): String = when (k) { // l10n: not text (the model's prompt)
        StepKind.SET_TIMER -> "sets a timer" // l10n: not text (the model's prompt)
        StepKind.SET_ALARM -> "sets an alarm" // l10n: not text (the model's prompt)
        StepKind.FLASHLIGHT -> "switches the flashlight" // l10n: not text (the model's prompt)
        StepKind.OPEN_APP -> "opens an app" // l10n: not text (the model's prompt)
        StepKind.MAPS_SEARCH -> "searches the map" // l10n: not text (the model's prompt)
        StepKind.MAPS_NAVIGATE -> "opens a route" // l10n: not text (the model's prompt)
        StepKind.SEND_TEXT -> "texts someone" // l10n: not text (the model's prompt)
        StepKind.DRAFT_EMAIL -> "drafts an email" // l10n: not text (the model's prompt)
        StepKind.ADD_NOTE -> "adds a note" // l10n: not text (the model's prompt)
        StepKind.ADD_EVENT -> "adds an event" // l10n: not text (the model's prompt)
        StepKind.WEATHER -> "gives the weather" // l10n: not text (the model's prompt)
        StepKind.READ_CALENDAR -> "reads the calendar" // l10n: not text (the model's prompt)
        StepKind.FIND_NOTES -> "finds notes" // l10n: not text (the model's prompt)
        StepKind.PHONE_STATUS -> "checks the phone" // l10n: not text (the model's prompt)
        StepKind.LOOKUP_CONTACT -> "looks up a contact" // l10n: not text (the model's prompt)
        StepKind.SAY -> "says a sentence" // l10n: not text (the model's prompt)
    }

    /**
     * The line: what the steps do, in order, the same kind twice in a row said once ("Sets a timer, then texts someone."),
     * at most [MAX_LINE] characters ("…, and more." when the steps don't fit).
     */
    fun line(s: Skill): String {
        val parts = s.steps.map { does(it.kind) }.fold(mutableListOf<String>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
        fun join(ps: List<String>, more: Boolean): String {
            val body = when {
                more -> ps.joinToString(", ") + ", and more" // l10n: not text (the model's prompt)
                ps.size == 1 -> ps[0]
                else -> ps.dropLast(1).joinToString(", ") + ", then " + ps.last() // l10n: not text (the model's prompt)
            }
            return body.replaceFirstChar { it.uppercaseChar() } + "."
        }
        val whole = join(parts, more = false)
        if (whole.length <= MAX_LINE) return whole
        for (n in parts.size - 1 downTo 1) join(parts.take(n), more = true).let { if (it.length <= MAX_LINE) return it }
        return join(parts.take(1), more = false).take(MAX_LINE)
    }

    /** The call example the text-call hint shows for [spec] (Prompt.textCallHint): its questions with no value. */
    fun callExample(spec: SkillToolSpec): String = "call:${spec.name}{" + spec.params.joinToString(",") { "${it.first}:<escape>…<escape>" } + "}" // l10n: not text (the model's prompt)
}
