// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.i18n.Lang
import org.json.JSONArray
import org.json.JSONObject

// Pure. Skills 1.3, plan-then-act (design §9; skills-1.3-plan.md S3.7). A plan is what the planning model wrote as its
// one call to make_plan (the PLAN one-shot, OneShotRules.withPlanTool), or the calls a turn couldn't run past
// TurnRules.MAX_CALLS ("Make that a plan?"). Read strictly here before its card shows: at most the cap of steps (5 by
// default, Settings › Skills, 3 to 8), each one of Robot's own tools that is on and that skills may use (never a plan, a
// call, Termux, a helper or the web), its arguments read by the tool's own parser (ToolCalls.parse) and kept as you'd
// have said them (SkillDraft.step), no template anywhere (Robot's own tools declare no outputs for a later step), and
// each step's own checks (SkillCheck.stepProblems). Its risk is scored with plan points (SkillRisk.planPoints). A plan
// that fails any of this is refused whole: nothing of it runs and no card shows.

/** A checked plan: its steps as a skill that is never saved ([skill]), the actions they are, its tier and points. */
data class SkillPlan(val skill: Skill, val actions: List<Action>, val tier: SkillTier, val points: Int) {
    val steps: List<SkillStep> get() = skill.steps

    /** The steps that read, before any write: they run at once (design §9, "Reads run first"). */
    val readsFirst: Int get() = steps.indexOfFirst { !it.kind.reads }.let { if (it < 0) steps.size else it }
}

/** Why a plan was refused (the voice log's PLAN code b). Stable numbers. */
enum class PlanRefusal(val id: Int) {
    NO_PLAN(1), BAD_SHAPE(2), TOO_MANY(3), NOT_ON(4), BAD_ARGS(5), CANT_SAVE(6), TEMPLATE(7), APP_NOT_LAST(8)
}

sealed class PlanRead {
    data class Ok(val plan: SkillPlan) : PlanRead()
    data class Refused(val why: PlanRefusal) : PlanRead()
}

object SkillPlans {
    /** Settings › Skills › Most steps in a plan: the default (EST) and its range. */
    const val CAP = 5
    val CAPS = 3..8

    const val TOOL = "make_plan" // l10n: not text (a tool's name)

    /** The plan's id as a skill: never saved, never in skills.json. */
    const val PLAN_ID = "plan" // l10n: not text (an id)

    /** Robot's own tools a plan's step may be: a skill's steps, without Robot's own `say` and the routines' steps. */
    val PLANNABLE: List<ToolName> = StepKind.entries.mapNotNull { k -> k.tool?.takeIf { k == StepKind.of(it) && it !in StepKind.NEVER } }

    /** make_plan, as LiteRT-LM's OpenApiTool takes it: one argument, the steps in order, each a tool's name and its arguments. */
    val TOOL_JSON: String = JSONObject().put("name", TOOL)
        .put("description", "Write the plan: the actions the person asked for, in order.") // l10n: not text (a tool for the model)
        .put("parameters", JSONObject().put("type", "object") // l10n: not text (a JSON schema)
            .put("properties", JSONObject().put("steps", JSONObject().put("type", "array") // l10n: not text (a JSON schema)
                .put("description", "The steps, in the order to do them") // l10n: not text (a tool for the model)
                .put("items", JSONObject().put("type", "object") // l10n: not text (a JSON schema)
                    .put("properties", JSONObject()
                        .put("use", JSONObject().put("type", "string").put("description", "A tool's name")) // l10n: not text (a tool for the model)
                        .put("args", JSONObject().put("type", "object").put("description", "That tool's arguments"))) // l10n: not text (a tool for the model)
                    .put("required", JSONArray(listOf("use", "args")))))) // l10n: not text (a JSON schema)
            .put("required", JSONArray(listOf("steps")))).toString() // l10n: not text (a JSON schema)

    /** The PLAN one-shot's system prompt: the tools that are on ([tools]), each as its name, arguments and one line, and the cap. */
    fun system(tools: List<ToolName>, cap: Int): String = buildString {
        appendLine("You write plans for Robot, an assistant on a phone. Call $TOOL once, with nothing else.") // l10n: not text (a prompt for the model)
        appendLine("Its steps are the actions the person asked for, in the order they asked, at most $cap.") // l10n: not text (a prompt for the model)
        appendLine("Each step is {\"use\": a tool's name, \"args\": that tool's arguments}. Only these tools:") // l10n: not text (a prompt for the model)
        for (t in tools.filter { it in PLANNABLE }) {
            val spec = ToolSpecs.of(t)
            appendLine("- ${t.id}(${spec.params.joinToString(", ") { "${it.name}: ${it.type}" }}): ${spec.description}") // l10n: not text (a prompt for the model)
        }
        appendLine("Take every value from the person's words. Add no step they didn't ask for. A step is never a plan.") // l10n: not text (a prompt for the model)
        append("If you can't call $TOOL, write only its arguments as JSON: {\"steps\": [...]}.") // l10n: not text (a prompt for the model)
    }

    /**
     * The plan in the model's reply: its one call to make_plan ([calls]), or, from an engine without the tool API, the JSON
     * in its [text] (read leniently: the first `{` to the last `}`), then checked strictly. [on]: the tools that are on;
     * [cap]: the most steps. [fromContent]: the conversation holds content Robot didn't write (its card is then tap-only).
     */
    fun read(calls: List<RawToolCall>, text: String, on: Set<ToolName>, cap: Int, lang: Lang, now: Long, fromContent: Boolean,
        links: SkillLinks = SkillLinks.NONE): PlanRead {
        val args: Any? = when {
            calls.size == 1 && calls[0].name == TOOL -> calls[0].args
            calls.isNotEmpty() -> return PlanRead.Refused(PlanRefusal.BAD_SHAPE)
            else -> lenient(text) ?: return PlanRead.Refused(PlanRefusal.NO_PLAN)
        }
        val steps = (plain(args) as? Map<*, *>)?.takeIf { it.keys == setOf("steps") }?.get("steps") as? List<*>
            ?: return PlanRead.Refused(PlanRefusal.BAD_SHAPE)
        val asCalls = steps.map { st ->
            val m = st as? Map<*, *> ?: return PlanRead.Refused(PlanRefusal.BAD_SHAPE)
            if (!m.keys.all { it == "use" || it == "args" }) return PlanRead.Refused(PlanRefusal.BAD_SHAPE)
            val use = m["use"] as? String ?: return PlanRead.Refused(PlanRefusal.BAD_SHAPE)
            val a = (m["args"] ?: emptyMap<String, Any?>()) as? Map<*, *> ?: return PlanRead.Refused(PlanRefusal.BAD_SHAPE)
            if (!a.keys.all { it is String }) return PlanRead.Refused(PlanRefusal.BAD_SHAPE)
            @Suppress("UNCHECKED_CAST")
            RawToolCall(use, a as Map<String, Any?>)
        }
        return check(asCalls, on, cap, lang, now, fromContent, links)
    }

    /**
     * [calls] as a plan, each read by its tool's own parser: a PLAN's steps, or what a turn couldn't run past its limit.
     * Refused whole when one step isn't one a plan may hold, so nothing of it runs.
     */
    fun check(calls: List<RawToolCall>, on: Set<ToolName>, cap: Int, lang: Lang, now: Long, fromContent: Boolean,
        links: SkillLinks = SkillLinks.NONE): PlanRead {
        if (calls.isEmpty()) return PlanRead.Refused(PlanRefusal.NO_PLAN)
        if (calls.size > cap.coerceIn(CAPS)) return PlanRead.Refused(PlanRefusal.TOO_MANY)
        val actions = mutableListOf<Action>()
        val steps = mutableListOf<SkillStep>()
        for (c in calls) {
            val tool = ToolName.of(c.name)?.takeIf { it in PLANNABLE && it in on } ?: return PlanRead.Refused(PlanRefusal.NOT_ON)
            // A plan's values are its own: no template, and no step reads what an earlier one brought back (none declares it).
            if (c.args.values.any { SkillTemplate.looksLike(it) }) return PlanRead.Refused(PlanRefusal.TEMPLATE)
            val a = (ToolCalls.parse(RawToolCall(tool.id, c.args)) as? ParsedCall.Ok)?.action ?: return PlanRead.Refused(PlanRefusal.BAD_ARGS)
            actions += a
            steps += SkillDraft.step(a) ?: return PlanRead.Refused(PlanRefusal.CANT_SAVE)
        }
        // An app that opens leaves the panel: what comes after it would wait for a notification, which a plan doesn't have.
        if (steps.dropLast(1).any { SkillRun.mayOpenApp(it.kind) }) return PlanRead.Refused(PlanRefusal.APP_NOT_LAST)
        val skill = Skill(PLAN_ID, 1, SkillFromActions.defaultName(steps, lang), emptyList(), emptyList(), emptyList(), steps, SkillLevel.ASK_FIRST,
            SkillProvenance(SkillMade.DRAFT_DEVICE, fromContent, now, now))
        if (steps.indices.any { SkillCheck.stepProblems(skill, steps[it], it).isNotEmpty() }) return PlanRead.Refused(PlanRefusal.BAD_ARGS)
        return PlanRead.Ok(SkillPlan(skill, actions, SkillRisk.planTier(skill, links), SkillRisk.planPoints(skill, links)))
    }

    /** "Save as a skill" (design §9): a done plan's steps with their values fixed, through the save card as "save that" does. */
    fun draft(p: SkillPlan, id: String, lang: Lang, now: Long, taken: Set<String> = emptySet()): SkillDraft =
        SkillDraft(id, SkillDraft.displayName(SkillFromActions.defaultName(p.steps, lang, taken)), lang, p.steps, SkillDraft.slots(p.steps),
            SkillMade.DRAFT_DEVICE, p.skill.provenance.fromContent, now)

    /** The JSON object in [text] (a model without the tool API writes it as text), or null. */
    private fun lenient(text: String): Any? {
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(text.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    /** JSON (org.json's or LiteRT-LM's maps and lists) as plain maps and lists. */
    private fun plain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        is Map<*, *> -> v.entries.associate { (k, x) -> k to plain(x) }
        is List<*> -> v.map(::plain)
        else -> v
    }
}
