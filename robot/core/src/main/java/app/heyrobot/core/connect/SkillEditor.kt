// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StepArg
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.IfTest
import app.heyrobot.core.skills.OnFail
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCards
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillDraft
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillEffects
import app.heyrobot.core.skills.SkillEncode
import app.heyrobot.core.skills.SkillIf
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillLinks
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillNumbers
import app.heyrobot.core.skills.SkillOption
import app.heyrobot.core.skills.SkillParam
import app.heyrobot.core.skills.SkillProvenance
import app.heyrobot.core.skills.SkillRef
import app.heyrobot.core.skills.SkillRisk
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.SkillTemplate
import app.heyrobot.core.skills.SkillTier
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.skills.ValueSource
import app.heyrobot.core.termux.TermuxScriptInfo

// Pure. A skill's page in Settings (docs/plans/skills-connections-plan.md §6.1, §6.4; C9): Add a skill, Add a step's sheet
// (your connections' operations, travel time, app hand-offs, your typed Termux scripts, Say something), the argument
// editor (each argument a constant, a question, or an earlier step's value, picked from lists; a text argument's words may
// hold a question or a value as a chip), Only if…, If it doesn't work, and the computed lines: Effects, Risk, "Needs a
// model" and Test (a dry run: nothing is sent, the key is never shown). Robot's own actions stay taught by voice (D10).
//
// Every change returns the skill to save, or null when it can't be made; the page then saves it through SkillCheck (the one
// gate). What a step links to is read from your connections and scripts here (their limits, which SkillLinks doesn't
// carry), and a step that no longer fits is SkillCheck.attention's. Nothing here is logged.

/** A section of Add a step's sheet (plan §6.4), in its order. */
enum class OfferSection { CONNECTIONS, TRAVEL, HAND_OFFS, SCRIPTS, SAY }

/** One thing Add a step offers. */
sealed class StepOffer(val section: OfferSection) {
    /** An operation of an HTTP connection, by your title. */
    class Operation(val conn: Connection, val op: ConnOp) : StepOffer(OfferSection.CONNECTIONS) {
        override fun toString(): String = "Operation(…)" // l10n: not text (toString, for the logs)
    }

    /** Travel time from a routing server: [conn] when you have more than one, else null (your only one). */
    class Travel(val conn: Connection?, val name: String) : StepOffer(OfferSection.TRAVEL) {
        override fun toString(): String = "Travel(…)" // l10n: not text (toString, for the logs)
    }

    class HandOff(val conn: Connection) : StepOffer(OfferSection.HAND_OFFS) {
        override fun toString(): String = "HandOff(…)" // l10n: not text (toString, for the logs)
    }

    /** One of your typed scripts (its declarations read whole), with how many questions it takes. */
    class Script(val info: TermuxScriptInfo) : StepOffer(OfferSection.SCRIPTS) {
        override fun toString(): String = "Script(…)" // l10n: not text (toString, for the logs)
    }

    /** Robot says a sentence of yours. */
    data object Say : StepOffer(OfferSection.SAY)
}

/**
 * One argument of a step as the editor fills it: [name], its type and limits ([options] a choice's values, [min] and
 * [max] a number's, [maxLen] a text's), [required] (no default: a step must give it), and your [label] when it has one
 * (a script's `# param:` label).
 */
data class ArgSlot(val name: String, val type: ParamType, val options: List<String> = emptyList(), val min: Double? = null, val max: Double? = null,
    val maxLen: Int? = null, val required: Boolean = true, val label: String? = null) {
    /** Words may hold chips (a question or a value inside the sentence). */
    val text: Boolean get() = type == ParamType.TEXT
}

/** What an argument holds now. */
sealed interface ArgFill {
    /** Not given: the operation's default, or nothing (a required one: the step needs attention). */
    data object Empty : ArgFill
    data class Fixed(val value: Any) : ArgFill
    /** A question's value, or an earlier step's output. */
    data class Value(val ref: SkillRef) : ArgFill
    /** Words with chips inside. */
    data class Words(val parts: List<SkillTemplate.Part>) : ArgFill
}

/** A value a condition can test (Only if…): a question or an earlier step's output, of a type that isn't free text. */
data class CondSubject(val ref: SkillRef, val type: ParamType, val options: List<String>)

object SkillEditor {
    /** Names a step gets for its outputs ("eta.minutes"): travel time's, a script's, an operation's own id. */
    private const val TRAVEL_ID = "eta" // l10n: not text (a step id in the skills file)
    private const val SCRIPT_ID = "script" // l10n: not text (a step id in the skills file)
    private const val STEP_ID = "step" // l10n: not text (a step id in the skills file)
    private const val TEXT = "text" // l10n: not text (SAY's argument)
    private const val TO = "to" // l10n: not text (travel time's argument)
    private const val CONN = "conn" // l10n: not text (travel time's argument)
    private const val SCRIPT = "script" // l10n: not text (a script step's argument)
    private val PLACES = listOf("home", "work") // l10n: not text (travel time's values)

    // ------------------------------------------------------------------ Add a step

    /**
     * What Add a step offers, by section: every operation of your Home Assistant, ntfy and webhook connections, travel
     * time (with a routing server), each hand-off, each typed script Robot could read, and Say something.
     */
    fun offers(connections: List<Connection>, scripts: List<TermuxScriptInfo>): List<StepOffer> = buildList {
        for (c in connections) if (c.kind == ConnKind.HOME_ASSISTANT || c.kind == ConnKind.NTFY || c.kind == ConnKind.WEBHOOK)
            for (op in c.ops) add(StepOffer.Operation(c, op))
        val routing = connections.filter { it.kind == ConnKind.ROUTING }
        if (routing.size == 1) add(StepOffer.Travel(null, routing.single().name)) else for (r in routing) add(StepOffer.Travel(r, r.name))
        for (c in connections) if (c.kind == ConnKind.HAND_OFF) add(StepOffer.HandOff(c))
        for (s in scripts) if (s.decl.ok && s.decl.params.isNotEmpty()) add(StepOffer.Script(s))
        add(StepOffer.Say)
    }

    /**
     * The step [o] makes, each argument a value it can't be wrong about given (a choice's only value, or its first; a
     * number's least; "no"); a text with no default is left for you (the step needs attention until it's filled). Say's
     * words are [say]'s, asked first.
     */
    fun step(o: StepOffer, say: String = ""): SkillStep = when (o) {
        is StepOffer.Operation -> SkillStep.of(StepKind.CONN, starting(slots(o.conn, o.op)), conn = o.conn.id, op = o.op.id)
        is StepOffer.Travel -> SkillStep.of(StepKind.TRAVEL_TIME, listOfNotNull(TO to PLACES[0], o.conn?.let { CONN to it.id }).toMap())
        is StepOffer.HandOff -> SkillStep.of(StepKind.APP, starting(slots(o.conn.handOff?.intent?.params.orEmpty())), conn = o.conn.id)
        is StepOffer.Script -> SkillStep.of(StepKind.TERMUX_SCRIPT, mapOf<String, Any?>(SCRIPT to o.info.name) + starting(scriptSlots(o.info)))
        StepOffer.Say -> SkillStep.of(StepKind.SAY, mapOf(TEXT to say.trim()))
    }

    private fun starting(slots: List<ArgSlot>): Map<String, Any?> = slots.filter { it.required }.mapNotNull { a ->
        val v: Any? = when (a.type) {
            ParamType.CHOICE -> a.options.firstOrNull()
            ParamType.YES_NO -> false
            ParamType.INTEGER -> a.min?.toLong()
            ParamType.NUMBER -> a.min?.let(SkillNumbers::json)
            else -> null
        }
        v?.let { a.name to it }
    }.toMap()

    /** [s] with [step] added last (at most 8 steps), or null. */
    fun add(s: Skill, step: SkillStep): Skill? = if (s.steps.size >= SkillCheck.MAX_STEPS) null else s.copy(steps = s.steps + step)

    /**
     * Add a skill (plan §6.1): [name] as typed, its first [step], made in Settings at Ask first. The name, as you'd say it, is
     * its first name in the menus' language [lang].
     */
    fun newSkill(id: String, name: String, step: SkillStep, now: Long, lang: Lang): Skill {
        val n = name.trim()
        val said = listOfNotNull(SkillDraft.otherName(n))
        return Skill(id, 1, n, if (lang == Lang.FR) emptyList() else said, if (lang == Lang.FR) said else emptyList(), emptyList(), listOf(step),
            SkillLevel.ASK_FIRST, SkillProvenance(SkillMade.SETTINGS, false, now, now))
    }

    // ------------------------------------------------------------------ arguments

    /** An operation's arguments (or a hand-off's slots) as the editor fills them. */
    fun slots(params: Map<String, OpParam>): List<ArgSlot> = params.map { (n, p) ->
        ArgSlot(n, p.type, p.options, p.min, p.max, p.maxLen, required = p.default == null)
    }

    fun slots(c: Connection, op: ConnOp): List<ArgSlot> = slots(op.params)

    /** A typed script's parameters, every one required (C8). */
    fun scriptSlots(info: TermuxScriptInfo): List<ArgSlot> = info.decl.takeIf { it.ok }?.params.orEmpty().map { p ->
        ArgSlot(p.name, p.type, p.options, p.min?.toDouble(), p.max?.toDouble(), p.maxLen, required = true, label = p.label)
    }

    /**
     * Step [i]'s arguments as the editor fills them, from what it links to now; empty when it links to nothing (gone) or is
     * one of Robot's own actions (taught by voice; only moved or removed here).
     */
    fun slots(s: Skill, i: Int, connections: List<Connection>, scripts: List<TermuxScriptInfo>): List<ArgSlot> {
        val st = s.steps.getOrNull(i) ?: return emptyList()
        return when (st.kind) {
            StepKind.CONN -> connections.firstOrNull { it.id == st.conn }?.let { c -> c.op(st.op!!)?.let { slots(c, it) } }.orEmpty()
            StepKind.APP -> connections.firstOrNull { it.id == st.conn }?.handOff?.let { slots(it.intent.params) }.orEmpty()
            StepKind.TERMUX_SCRIPT -> scripts.firstOrNull { it.name == st.args[SCRIPT] }?.let(::scriptSlots).orEmpty()
            StepKind.TRAVEL_TIME -> listOf(ArgSlot(TO, ParamType.CHOICE, PLACES))
            StepKind.SAY -> listOf(ArgSlot(TEXT, ParamType.TEXT, maxLen = app.heyrobot.core.RoutineCheck.MAX_SAY))
            else -> emptyList()
        }
    }

    /** What argument [slot] of step [i] holds now. */
    fun fill(s: Skill, i: Int, slot: ArgSlot): ArgFill {
        val v = s.steps.getOrNull(i)?.args?.get(slot.name) ?: return ArgFill.Empty
        SkillTemplate.ref(v)?.let { return ArgFill.Value(it) }
        if (SkillTemplate.looksLike(v)) return SkillTemplate.parts(v as String)?.let { ArgFill.Words(it) } ?: ArgFill.Empty
        return ArgFill.Fixed(v)
    }

    /** [typed] as a constant of [slot]'s type, as the skills file holds it; null when it doesn't fit. */
    fun constant(slot: ArgSlot, typed: String): Any? {
        val t = typed.trim()
        return when (slot.type) {
            ParamType.TEXT -> t.takeIf { it.isNotEmpty() && it.length <= (slot.maxLen ?: ConnCheck.DEFAULT_TEXT) && !SkillTemplate.looksLike(it) && !SkillCheck.secret(it) }
            ParamType.CHOICE -> t.takeIf { it in slot.options }
            ParamType.INTEGER -> t.toLongOrNull()?.takeIf { within(slot, it.toDouble()) }
            ParamType.NUMBER -> t.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && within(slot, it) }?.let(SkillNumbers::json)
            ParamType.YES_NO -> when (t) { "true" -> true; "false" -> false; else -> null }
            else -> null
        }
    }

    private fun within(slot: ArgSlot, x: Double): Boolean = (slot.min == null || x >= slot.min) && (slot.max == null || x <= slot.max)

    /** Step [i]'s argument [slot] set to [value] (a constant, a template, or words with templates); null: unset (optional ones only). */
    fun set(s: Skill, i: Int, slot: ArgSlot, value: Any?): Skill? {
        val st = s.steps.getOrNull(i) ?: return null
        if (value == null && slot.required) return null
        val args = LinkedHashMap(st.args)
        if (value == null) args.remove(slot.name) else args[slot.name] = value
        return pruned(replace(s, i, SkillStep.of(st.kind, args, st.id, st.conn, st.op, st.cond, st.check, st.onFail)))
    }

    /** Step [i]'s argument set to the constant [typed]; null when it doesn't fit. */
    fun setConstant(s: Skill, i: Int, slot: ArgSlot, typed: String): Skill? = constant(slot, typed)?.let { set(s, i, slot, it) }

    /**
     * A text argument's words, with chips: [text] holds `{{question}}` and `{{step.output}}` where you put them; each must be
     * a question or an earlier step's output that has words. Null when they don't read or name something else.
     */
    fun setWords(s: Skill, i: Int, slot: ArgSlot, text: String, links: SkillLinks): Skill? {
        if (!slot.text) return null
        val t = text.trim()
        val parts = SkillTemplate.parts(t) ?: return null
        if (parts.isEmpty() || t.length > (slot.maxLen ?: ConnCheck.MAX_TEXT)) return null
        val ok = chips(s, i, links).map { it.first }.toSet()
        if (parts.filterIsInstance<SkillTemplate.Part.Ref>().any { it.ref !in ok }) return null
        val words = parts.filterIsInstance<SkillTemplate.Part.Words>().joinToString(" ") { it.text }
        if (SkillCheck.secret(words)) return null
        return set(s, i, slot, t)
    }

    /** The questions and earlier outputs that may go inside step [i]'s words as chips, with their words ("who", "eta.minutes"). */
    fun chips(s: Skill, i: Int, links: SkillLinks): List<Pair<SkillRef, ParamType>> = buildList {
        for (p in s.params) if (SkillEncode.hasWords(p.type)) add(SkillRef(p.name) to p.type)
        for ((r, t) in outputs(s, i, links)) if (SkillEncode.hasWords(t.type)) add(r to t.type)
    }

    /**
     * The values that may fill [slot] of step [i] whole: questions of a type it takes, and earlier steps' outputs. An
     * earlier step without an id gets one when you pick its value ([pick]).
     */
    fun values(s: Skill, i: Int, slot: ArgSlot, links: SkillLinks): List<SkillRef> = buildList {
        for (p in s.params) if (fits(p.type, p.options.map { it.value }, slot)) add(SkillRef(p.name))
        for ((r, t) in outputs(s, i, links)) if (fits(t.type, t.options, slot)) add(r)
    }

    private fun fits(type: ParamType, options: List<String>, slot: ArgSlot): Boolean = when (slot.type) {
        ParamType.TEXT -> SkillEncode.hasWords(type)
        ParamType.NUMBER -> type == ParamType.NUMBER || type == ParamType.INTEGER
        ParamType.CHOICE -> type == ParamType.CHOICE && options.isNotEmpty() && (slot.options.isEmpty() || slot.options.containsAll(options))
        else -> type == slot.type
    }

    /**
     * The outputs of the steps before [i], by the name a template uses: a step that has no id yet is named as [pick] would
     * name it. Travel time's minutes and km, a script's exit and out, an operation's declared outputs ([links]).
     */
    fun outputs(s: Skill, i: Int, links: SkillLinks = SkillLinks.NONE): List<Pair<SkillRef, app.heyrobot.core.skills.SlotType>> = buildList {
        for ((j, id) in ids(s, i, links)) {
            val st = s.steps[j]
            for ((o, t) in outs(st, links)) add(SkillRef(id, o) to t)
        }
    }

    private fun outs(st: SkillStep, links: SkillLinks): Map<String, app.heyrobot.core.skills.SlotType> =
        if (st.kind == StepKind.CONN) st.conn?.let { c -> st.op?.let { links.op(c, it) } }?.outputs.orEmpty() else st.kind.outputs

    /** The steps before [i] that have outputs, each with its id, or the one [withId] gives it after the ones before it. */
    private fun ids(s: Skill, i: Int, links: SkillLinks): List<Pair<Int, String>> = buildList {
        var named = s
        for (j in 0 until minOf(i, s.steps.size)) {
            if (outs(s.steps[j], links).isEmpty()) continue
            val (k, id) = withId(named, j)
            named = k
            add(j to id)
        }
    }

    /**
     * Picks [ref] for [slot] of step [i]: the template, and the earlier step it names gets an id if it has none (its kind's:
     * "eta" for travel time, "script", the operation's own id, else "step2").
     */
    fun pick(s: Skill, i: Int, slot: ArgSlot, ref: SkillRef, links: SkillLinks): Skill? {
        val named = name(s, ref, i, links) ?: return null
        return set(named, i, slot, SkillTemplate.of(ref))
    }

    /** [s] with the step [ref] names (an earlier one, by the id [outputs] gave it) holding that id. */
    private fun name(s: Skill, ref: SkillRef, i: Int, links: SkillLinks): Skill? {
        if (ref.output == null) return s.takeIf { it.param(ref.name) != null }
        val (j, id) = ids(s, i, links).firstOrNull { it.second == ref.name } ?: return null
        if (ref.output !in outs(s.steps[j], links)) return null
        return replace(s, j, s.steps[j].copy(id = id))
    }

    /** Step [j] with an id, and that id (unique in the skill). */
    fun withId(s: Skill, j: Int): Pair<Skill, String> {
        val st = s.steps[j]
        st.id?.let { return s to it }
        val taken = s.steps.mapNotNull { it.id }.toSet()
        val base = when (st.kind) {
            StepKind.TRAVEL_TIME -> TRAVEL_ID
            StepKind.TERMUX_SCRIPT -> SCRIPT_ID
            StepKind.CONN -> (st.op ?: STEP_ID).take(14).trimEnd('_')
            else -> STEP_ID + (j + 1)
        }
        val id = generateSequence(1) { it + 1 }.map { n -> if (n == 1) base else (base.take(14) + n) }.first { it !in taken && SkillCheck.validStepId(it) }
        return replace(s, j, st.copy(id = id)) to id
    }

    /**
     * Step [i]'s argument becomes a new question you answer each time: [ask] is the question as you typed it (both
     * languages start with it; the page edits each), [default] its value when your words don't give one. Its type and
     * limits follow the argument: a choice of its values (2 to 12), a number within its limits, yes or no, or words.
     * Null when a skill already has 3 questions or the argument can't be a question.
     */
    fun ask(s: Skill, i: Int, slot: ArgSlot, ask: String, default: String): Skill? {
        if (s.params.size >= SkillCheck.MAX_PARAMS) return null
        val name = generateSequence(1) { it + 1 }.map { n ->
            val b = slot.name.filter { it.isLetterOrDigit() || it == '_' }.lowercase().ifEmpty { "value" }.take(14) // l10n: not text (a question's name)
            if (n == 1) b else b + n
        }.first { n -> s.param(n) == null && SkillCheck.validParamName(n) }
        val a = ask.trim()
        val p = when (slot.type) {
            ParamType.TEXT -> SkillParam(name, ParamType.TEXT, default.trim(), a, a)
            ParamType.CHOICE -> {
                if (slot.options.size !in SkillCheck.MIN_OPTIONS..SkillCheck.MAX_OPTIONS) return null
                SkillParam(name, ParamType.CHOICE, default.takeIf { it in slot.options } ?: slot.options.first(), a, a,
                    options = slot.options.map { SkillOption(it, it.take(SkillCheck.MAX_LABEL), it.take(SkillCheck.MAX_LABEL)) })
            }
            ParamType.INTEGER, ParamType.NUMBER -> {
                val lo = slot.min ?: return null
                val hi = slot.max ?: return null
                val d = default.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in lo..hi } ?: lo
                SkillParam(name, slot.type, if (slot.type == ParamType.INTEGER) d.toLong().toString() else SkillNumbers.text(d), a, a, min = lo, max = hi)
            }
            ParamType.YES_NO -> SkillParam(name, ParamType.YES_NO, if (default == "true") "true" else "false", a, a)
            else -> return null
        }
        if (!SkillCheck.validValue(p, p.default) || SkillCheck.secret(p.default)) return null
        return set(s.copy(params = s.params + p), i, slot, SkillTemplate.of(name))
    }

    // ------------------------------------------------------------------ Only if… and If it doesn't work

    /** What step [i]'s condition may test: questions and earlier outputs with a shape (never free words, design §3.4). */
    fun subjects(s: Skill, i: Int, links: SkillLinks): List<CondSubject> = buildList {
        for (p in s.params) if (!p.type.free && p.type != ParamType.TIME) add(CondSubject(SkillRef(p.name), p.type, p.options.map { it.value }))
        for ((r, t) in outputs(s, i, links)) if (!t.type.free) add(CondSubject(r, t.type, t.options))
    }

    /** The tests a condition on [c] offers without a number: set, not set (an output only), each choice, yes, no. */
    fun tests(c: CondSubject): List<IfTest> = buildList {
        if (c.ref.output != null) { add(IfTest.Set); add(IfTest.Unset) }
        when (c.type) {
            ParamType.CHOICE -> for (o in c.options) add(IfTest.Is(o))
            ParamType.YES_NO -> { add(IfTest.Yes(true)); add(IfTest.Yes(false)) }
            else -> {}
        }
    }

    /** A number's tests (less than, more than) take a typed number. */
    fun numeric(c: CondSubject): Boolean = c.type == ParamType.INTEGER || c.type == ParamType.NUMBER || c.type == ParamType.DURATION

    /** Step [i]'s condition set ([cond] null: always runs). */
    fun setCondition(s: Skill, i: Int, cond: SkillIf?, links: SkillLinks): Skill? {
        val named = if (cond == null) s else name(s, cond.value, i, links) ?: return null
        val st = named.steps.getOrNull(i) ?: return null
        return pruned(replace(named, i, st.copy(cond = cond)))
    }

    /** Step [i] only reads, so "Go on" may follow its failure (plan §5.1). */
    fun reads(s: Skill, i: Int, links: SkillLinks): Boolean {
        val st = s.steps.getOrNull(i) ?: return false
        return if (st.kind == StepKind.CONN) st.conn?.let { c -> st.op?.let { links.op(c, it) } }?.read == true else st.kind.reads
    }

    /** What If it doesn't work offers for step [i]: Go on after a read, and while it's set, so a step that stopped being a read can go back to Stop. */
    fun onFailWays(s: Skill, i: Int, links: SkillLinks): List<OnFail> =
        if (reads(s, i, links) || s.steps.getOrNull(i)?.onFail == OnFail.GO_ON) listOf(OnFail.STOP, OnFail.GO_ON) else listOf(OnFail.STOP)

    /** If it doesn't work: Stop, or Go on (after a read only). */
    fun setOnFail(s: Skill, i: Int, f: OnFail, links: SkillLinks): Skill? {
        if (f == OnFail.GO_ON && !reads(s, i, links)) return null
        val st = s.steps.getOrNull(i) ?: return null
        return replace(s, i, st.copy(onFail = f))
    }

    /** The condition in words: "Only if eta.minutes is set", "Only if room is kitchen", "Only if days is less than 7". */
    fun condText(c: SkillIf): Phrase {
        val v = "value" to c.value.toString()
        return when (val t = c.test) {
            IfTest.Set -> ConnStrings.ifSet(v)
            IfTest.Unset -> ConnStrings.ifUnset(v)
            is IfTest.Is -> ConnStrings.ifIs(v, "choice" to t.choice)
            is IfTest.Yes -> if (t.yes) ConnStrings.ifYes(v) else ConnStrings.ifNo(v)
            is IfTest.Below -> ConnStrings.ifBelow(v, "n" to SkillNumbers.text(t.n))
            is IfTest.Above -> ConnStrings.ifAbove(v, "n" to SkillNumbers.text(t.n))
        }
    }

    /** [s] without the questions no step names any more (SkillCheck refuses an unused one; as SkillEdit.removeStep). */
    private fun pruned(s: Skill): Skill {
        val named = s.steps.flatMap { it.templates }.toSet()
        return s.copy(params = s.params.filter { it.name in named })
    }

    private fun replace(s: Skill, i: Int, st: SkillStep): Skill = s.copy(steps = s.steps.mapIndexed { j, x -> if (j == i) st else x })

    // ------------------------------------------------------------------ the computed lines (plan §6.4)

    /** What [s] may do, every question as the model might fill it. */
    fun effects(s: Skill, links: SkillLinks): Set<SkillEffect> = SkillEffects.of(s, links, s.params.map { it.name }.toSet())

    /** The effects as chips, in a fixed order. */
    fun chips(effects: Set<SkillEffect>): List<Phrase> = buildList {
        if (SkillEffect.SECURITY in effects) add(ConnStrings.effectSecurity())
        if (SkillEffect.MONEY in effects) add(ConnStrings.effectMoney())
        if (SkillEffect.DELETES in effects) add(ConnStrings.effectDeletesOnline())
        if (SkillEffect.RUNS_CODE in effects) add(ConnStrings.effectCode())
        if (SkillEffect.MESSAGES_SOMEONE in effects) add(ConnStrings.effectTells())
        if (SkillEffect.WRITES_ONLINE in effects) add(ConnStrings.effectWritesOnline())
        if (SkillEffect.CHANGES_HOME in effects) add(ConnStrings.effectHome())
        if (SkillEffect.CHANGES_PHONE in effects) add(ConnStrings.effectPhone())
        if (SkillEffect.OPENS_APP in effects) add(ConnStrings.effectApp())
        if (SkillEffect.READS_ONLINE in effects) add(ConnStrings.effectReadsOnline())
        if (SkillEffect.READS_PHONE in effects) add(ConnStrings.effectReadsPhone())
        if (SkillEffect.MODEL_TEXT in effects) add(ConnStrings.effectModelText())
    }

    /**
     * The Risk line (plan §6.4): "Careful: 3 steps, tells people", the tier when Robot's model fills every question (the
     * most it can be; with no question, a model fills nothing and it is Simple unless a step is Risky by itself).
     */
    fun risk(s: Skill, links: SkillLinks): Phrase {
        val tier = SkillRisk.tier(s, s.params.map { it.name }.toSet(), links)
        val word = when (tier) {
            SkillTier.SIMPLE -> ConnStrings.tierSimple()
            SkillTier.CAREFUL -> ConnStrings.tierCareful()
            SkillTier.RISKY -> ConnStrings.tierRisky()
        }
        val chips = chips(effects(s, links))
        val steps = ConnStrings.stepsCount(s.steps.size)
        return if (chips.isEmpty()) ConnStrings.riskLineQuiet("tier" to word, "steps" to steps)
        else ConnStrings.riskLine("tier" to word, "steps" to steps, "effects" to Phrase.ListOf(chips))
    }

    /** "Needs a model: yes, 1 question" (a model fills a question your words don't), or "no". */
    fun model(s: Skill): Phrase = if (s.params.isEmpty()) ConnStrings.modelNo() else ConnStrings.modelYes(s.params.size)

    /**
     * Test (plan §5.4, §6.4): what each step would do with every question at its default, nothing sent. A connection's
     * request as its card's Preview shows it ([masks]: its key's masked form, never the key); a hand-off's app, action
     * and address; a script's name and arguments; a value an earlier step would bring back can't be shown before it runs.
     */
    fun dryRun(s: Skill, connections: List<Connection>, links: SkillLinks, masks: (String) -> Map<SecretPart, String> = { emptyMap() }): List<Phrase> =
        s.steps.mapIndexed { i, st ->
            val n = "n" to (i + 1).toString()
            val line: Phrase = when (st.kind) {
                StepKind.CONN -> {
                    val c = connections.firstOrNull { it.id == st.conn }
                    val args = args(s, st.args)
                    when {
                        c == null || c.op(st.op!!) == null -> ConnStrings.dryGone(n)
                        args == null -> ConnStrings.dryEarlier(n)
                        else -> when (val v = ConnStep.values(c.op(st.op)!!, args)) {
                            is ConnStep.Values.Refused -> ConnStrings.dryMissing(n)
                            is ConnStep.Values.Ok -> when (val b = ConnOps.build(c, st.op, v.values)) {
                                is Built.Refused -> ConnStrings.dryMissing(n)
                                is Built.Ok -> preview(n, ConnPreview.of(b.request, c, masks(c.id)))
                            }
                        }
                    }
                }
                StepKind.APP -> {
                    val c = connections.firstOrNull { it.id == st.conn && it.kind == ConnKind.HAND_OFF }
                    val app = c?.handOff
                    val args = args(s, st.args)
                    when {
                        c == null || app == null -> ConnStrings.dryGone(n)
                        args == null -> ConnStrings.dryEarlier(n)
                        else -> {
                            val v = ConnStep.values(app.intent.params, args) as? ConnStep.Values.Ok
                            val i2 = v?.let { IntentSpecs.build(app, it.values) }
                            if (i2 == null) ConnStrings.dryMissing(n)
                            else ConnStrings.dryLine(n, "what" to Phrase.Join(listOfNotNull(HandOffStep.summary(c, i2),
                                i2.data?.let { ConnStrings.handOffData("data" to it) }), " · "))
                        }
                    }
                }
                StepKind.TRAVEL_TIME -> {
                    val c = (links as? ConnLinks)?.routingFor(st.args[CONN] as? String)
                        ?: connections.filter { it.kind == ConnKind.ROUTING }.let { r -> (st.args[CONN] as? String)?.let { id -> r.firstOrNull { it.id == id } } ?: r.singleOrNull() }
                    if (c == null) ConnStrings.dryGone(n)
                    else ConnStrings.dryLine(n, "what" to Phrase.Join(listOf(ConnStrings.travelCard("place" to TravelStep.placeWord(st.args[TO] as? String ?: PLACES[0]),
                        "name" to c.name), ConnStrings.travelSends("place" to TravelStep.placeWord(st.args[TO] as? String ?: PLACES[0]))), " · "))
                }
                StepKind.TERMUX_SCRIPT -> {
                    val name = st.args[SCRIPT] as? String ?: ""
                    val args = args(s, st.args - SCRIPT)
                    if (args == null) ConnStrings.dryEarlier(n)
                    else ConnStrings.dryScript(n, "name" to name, "args" to Phrase.ListOf(args.values.map { a ->
                        Phrase.plain(when (a) {
                            is StepArg.Fixed -> SkillTemplate.text(a.value)
                            is StepArg.Value -> SkillTemplate.text(a.value.value)
                            StepArg.Missing -> "…"
                        })
                    }))
                }
                StepKind.SAY -> {
                    val t = st.args[TEXT] as? String
                    val said = t?.let { if (SkillTemplate.looksLike(it)) (args(s, mapOf(TEXT to it))?.get(TEXT) as? StepArg.Value)?.value?.value?.toString() else it }
                    if (said == null) ConnStrings.dryEarlier(n) else ConnStrings.drySay(n, "text" to said)
                }
                else -> ConnStrings.dryLine(n, "what" to SkillCards.stepText(s, st, links = links))
            }
            line
        }

    private fun preview(n: Pair<String, String>, p: Preview): Phrase = ConnStrings.dryLine(n, "what" to Phrase.Join(listOfNotNull(
        ConnStrings.previewRequest("method" to p.method, "url" to p.url),
        *p.headers.map { ConnStrings.previewHeader("line" to it) }.toTypedArray(),
        p.auth?.let { ConnStrings.previewHeader("line" to it) },
        p.body?.let { b -> ConnStrings.previewBody("body" to if (b.length > ConnStep.PREVIEW_BODY) b.take(ConnStep.PREVIEW_BODY) + "…" else b) },
    ), "\n"))

    /**
     * A step's arguments with every question at its default (as you'd hear the card with no words given), or null when one
     * names an earlier step's output (it isn't there before a run).
     */
    private fun args(s: Skill, a: Map<String, Any?>): Map<String, StepArg>? {
        fun question(r: SkillRef): SkillValue? = if (r.output != null) null else s.param(r.name)?.let { SkillValue.of(it, it.default, ValueSource.FIXED) }
        val out = LinkedHashMap<String, StepArg>()
        for ((k, v) in a) {
            val whole = SkillTemplate.ref(v)
            out[k] = when {
                whole != null -> StepArg.Value(question(whole) ?: return null)
                SkillTemplate.looksLike(v) -> StepArg.Value(SkillValue.inText(v as String, ::question) ?: return null)
                v == null -> continue
                else -> StepArg.Fixed(v)
            }
        }
        return out
    }
}
