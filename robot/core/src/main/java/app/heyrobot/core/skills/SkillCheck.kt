// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.Durations
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RoutineCheck
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.core.termux.TermuxScripts

// Pure. What a skill may hold (1.1 plan §3, "SkillCheck"; connections plan §5.1, C1), used when it is saved and when
// skills.json is read: the limits of each field, every template naming a question or an earlier step's output of a type
// its place takes, templates inside words only in a text argument, a condition only on a typed value (never free text), a
// check only where its step has one, "go on" only after a read, every fixed value and default read back through the
// tool's own validation (ToolCalls.parse, RoutineCodec.readBack's rule), nothing that looks like a password or key, and
// nothing a reply or the phone read ever becomes a Termux script's argument (D15). Names colliding with Robot's own
// phrases are SkillNames' (they depend on the phone).
//
// What a step links to (a connection's operation, a hand-off, a typed script, a routing server) lives outside the skill,
// so [problems] never needs it: a skill whose link is gone or no longer fits is kept, and [attention] says why it can't
// run until it's fixed (design §3.8).

/** Why a skill can't run until it's fixed: the step's link is gone, or no longer fits the step (plan §5.2). */
enum class AttentionWhy { CONNECTION_GONE, APP_GONE, SCRIPT_GONE, NO_ROUTING, DOESNT_FIT }

/** Step [step] (from 0) needs attention: [why]. */
data class SkillAttention(val step: Int, val why: AttentionWhy)

object SkillCheck {
    const val MAX_NAME = 40
    const val MAX_OTHER_NAMES = 6
    const val MAX_NAME_WORDS = 5
    const val MAX_PARAMS = 3
    const val MAX_STEPS = 8
    const val MAX_ASK = 60
    const val MAX_SKILLS = 100
    const val MAX_BYTES = 512 * 1024
    const val MAX_PLACE = 200
    const val MAX_APP = 60
    const val MAX_PERSON = 120
    /** Languages beyond English and French a skill may carry from a later Robot (names or a question), each kept as read. */
    const val MAX_MORE_LANGS = 16
    // Connections plan §5.1 (EST where not said there).
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 12
    /** A choice's value, and its label in each language. */
    const val MAX_OPTION = 60
    const val MAX_LABEL = 40
    /** An integer's or a number's limits stay within ± this (EST). */
    const val MAX_NUMBER = 1e9
    const val MAX_PER_HOUR = 60
    const val MAX_POINTER = 200
    const val MAX_EQUALS = 200
    const val MAX_REGEX = 200
    /** A step argument's name in a connection's operation, a hand-off or a script (EST). */
    const val MAX_ARG = 32

    /** An integer's or a number's `unit` (EST): what the editor and the card show beside it. */
    val UNITS: Set<String> = setOf("minutes", "hours", "days", "degrees", "percent", "km")

    private val ID = Regex("^s-[a-z0-9]{6}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val PARAM = Regex("^[a-z][a-z0-9_]{0,15}$")
    private val ARG = Regex("^[a-z][a-z0-9_]{0,${MAX_ARG - 1}}$")
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")
    private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    /** An other name: letters, digits, spaces, apostrophes and hyphens ("je m'en viens", "drop-off"). */
    private val OTHER_NAME = Regex("^[\\p{L}\\p{N} '’-]+$")
    /** A language key as a later Robot writes one ("es", "de", "fr-FR"); "en" and "fr" are this Robot's own fields. */
    private val LANG_KEY = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    private val OWN_LANGS = setOf("en", "fr")
    /** `travel_time`'s places: Settings › Car mode's Home and Work (plan §3.4). */
    private val PLACES = setOf("home", "work")
    /** A choice's value can't read as a condition's own words. */
    private val NOT_CHOICES = setOf("set", "unset")

    fun validId(id: String?): Boolean = id != null && ID.matches(id)

    fun validParamName(n: String?): Boolean = n != null && PARAM.matches(n)

    /** A step's `id` (plan §5.1): a question name's shape. */
    fun validStepId(n: String?): Boolean = validParamName(n)

    /** A skill's name: 1 to 40 characters on one line, with at least a letter or digit. */
    fun validName(name: String): Boolean = name.isNotBlank() && name == name.trim() && name.length <= MAX_NAME && !CONTROL.containsMatchIn(name) &&
        SkillKey.of(name).isNotEmpty()

    /** Another name: 1 to 5 words of letters and digits. */
    fun validOtherName(n: String): Boolean = n == n.trim() && OTHER_NAME.matches(n) && SkillKey.words(n).size in 1..MAX_NAME_WORDS

    /** A language key for [Skill.namesMore] or [SkillParam.askMore]: a language tag's shape, and not one 1.1 keeps itself. */
    fun validMoreLang(k: String): Boolean = LANG_KEY.matches(k) && k !in OWN_LANGS

    /** A question as asked in one language: one line of 1 to 60 characters. */
    private fun validAsk(a: String): Boolean = a.isNotBlank() && a.length <= MAX_ASK && !CONTROL.containsMatchIn(a)

    /** One line of 1 to [max] characters, as written (no spaces around it), with no template in it. */
    private fun line(v: String, max: Int): Boolean = v.isNotBlank() && v == v.trim() && v.length <= max && !CONTROL.containsMatchIn(v) && !SkillTemplate.looksLike(v)

    /**
     * A value of [type], whatever its question's options and limits ([validValue] adds them): a duration in whole seconds
     * within a timer's limit, a time as "HH:MM", a whole number or a number as the file writes it ("12", "2.5"), "true" or
     * "false", a choice's value on one line, the rest one line of text.
     */
    fun validDefault(type: ParamType, v: String): Boolean = when (type) {
        ParamType.DURATION -> v.toLongOrNull()?.let { it in 1..Durations.MAX_TIMER_S } == true && v == v.trim()
        ParamType.TIME -> TIME.matches(v)
        ParamType.TEXT -> v.isNotBlank() && v.length <= ToolCalls.MAX_MESSAGE
        ParamType.PERSON -> v.isNotBlank() && v.length <= MAX_PERSON && !CONTROL.containsMatchIn(v) && !ToolCalls.placeholder(v)
        ParamType.APP -> v.isNotBlank() && v.length <= MAX_APP && !CONTROL.containsMatchIn(v)
        ParamType.PLACE -> v.isNotBlank() && v.length <= MAX_PLACE && !CONTROL.containsMatchIn(v)
        ParamType.CHOICE -> line(v, MAX_OPTION) && v !in NOT_CHOICES
        ParamType.INTEGER -> v.toLongOrNull()?.let { Math.abs(it.toDouble()) <= MAX_NUMBER && it.toString() == v } == true
        ParamType.NUMBER -> v.toDoubleOrNull()?.let { it.isFinite() && Math.abs(it) <= MAX_NUMBER && SkillNumbers.text(it) == v } == true
        ParamType.YES_NO -> v == "true" || v == "false"
    }

    /** A value [p] may take: of its type, one of its choices, within its limits. */
    fun validValue(p: SkillParam, v: String): Boolean = validDefault(p.type, v) && when (p.type) {
        ParamType.CHOICE -> p.option(v) != null
        ParamType.INTEGER, ParamType.NUMBER -> v.toDouble().let { x -> p.min != null && p.max != null && x >= p.min && x <= p.max }
        else -> true
    }

    /** Looks like a password or key (SecretDetector): a skill never keeps one, fixed or as a default. */
    fun secret(v: String): Boolean = SecretDetector.find(v).isNotEmpty()

    fun valid(s: Skill): Boolean = problems(s).isEmpty()

    /** What is wrong with [s], in words without a language yet; empty when it may be saved (and, with nothing in [attention], run). */
    fun problems(s: Skill): List<Phrase> = buildList {
        if (!validId(s.id) || s.rev < 1 || s.provenance.created < 0 || s.provenance.edited < 0) add(SkillStrings.couldntSave())
        if (s.perHour != null && s.perHour !in 1..MAX_PER_HOUR) add(SkillStrings.couldntSave())
        // Design §3.7: an import, and only an import, says which file it came from and who signed it.
        val imp = s.provenance.imported
        if ((s.provenance.made == SkillMade.IMPORT) != (imp != null) || imp != null && (!HEX64.matches(imp.fileSha256) || imp.signer?.let(HEX64::matches) == false))
            add(SkillStrings.couldntSave())
        if (!validName(s.name)) add(SkillStrings.nameRules())
        if (s.namesEn.size > MAX_OTHER_NAMES || s.namesFr.size > MAX_OTHER_NAMES || s.namesMore.values.any { it.size > MAX_OTHER_NAMES })
            add(SkillStrings.tooManyNames())
        if ((s.namesEn + s.namesFr + s.namesMore.values.flatten()).any { !validOtherName(it) }) add(SkillStrings.otherNameRules())
        // Names in a later Robot's languages are kept only in a language key's shape, and not too many.
        if (s.namesMore.size > MAX_MORE_LANGS || s.namesMore.keys.any { !validMoreLang(it) }) add(SkillStrings.otherNameRules())
        if (s.params.size > MAX_PARAMS) add(SkillStrings.tooManyQuestions())
        if (s.steps.isEmpty()) add(SkillStrings.noSteps())
        if (s.steps.size > MAX_STEPS) add(SkillStrings.tooManySteps())
        // D13: made from content, a skill never runs without its card until you say its steps are yours.
        if (s.provenance.fromContent && s.level == SkillLevel.JUST_DO_IT) add(SkillStrings.contentCheck())
        val named = s.steps.flatMap { it.templates }.toSet()
        for (p in s.params) {
            val ok = validParamName(p.name) && s.params.count { it.name == p.name } == 1 && validValue(p, p.default) && p.name in named &&
                listOf(p.askEn, p.askFr).all(::validAsk) && p.askMore.size <= MAX_MORE_LANGS &&
                p.askMore.all { (k, a) -> validMoreLang(k) && validAsk(a) } && shaped(p)
            if (!ok) add(SkillStrings.questionUnusable("ask" to p.askEn))
            if (secret(p.default) || p.options.any { o -> secret(o.value) || (listOf(o.en, o.fr) + o.more.values).any(::secret) }) add(SkillStrings.secret())
        }
        val ids = s.steps.mapNotNull { it.id }
        s.steps.forEachIndexed { i, st ->
            val dup = st.id != null && ids.count { it == st.id } > 1
            addAll(if (dup || (st.id != null && !validStepId(st.id))) listOf(unusable(st)) else stepProblems(s, st, i))
        }
    }.distinct()

    /** A question's own fields fit its type: a choice's 2 to 12 values, an integer's or a number's limits and unit, nothing else. */
    private fun shaped(p: SkillParam): Boolean {
        val numeric = p.type == ParamType.INTEGER || p.type == ParamType.NUMBER
        if (p.type == ParamType.CHOICE) {
            if (p.options.size !in MIN_OPTIONS..MAX_OPTIONS || p.options.map { it.value }.distinct().size != p.options.size) return false
            if (!p.options.all { o -> validDefault(ParamType.CHOICE, o.value) && line(o.en, MAX_LABEL) && line(o.fr, MAX_LABEL) && o.more.size <= MAX_MORE_LANGS &&
                    o.more.all { (k, l) -> validMoreLang(k) && line(l, MAX_LABEL) } }) return false
        } else if (p.options.isNotEmpty()) return false
        if (numeric) {
            val (lo, hi) = (p.min ?: return false) to (p.max ?: return false)
            if (!lo.isFinite() || !hi.isFinite() || lo > hi || Math.abs(lo) > MAX_NUMBER || Math.abs(hi) > MAX_NUMBER) return false
            if (p.type == ParamType.INTEGER && (lo != Math.floor(lo) || hi != Math.floor(hi))) return false
            if (p.unit != null && p.unit !in UNITS) return false
        } else if (p.min != null || p.max != null || p.unit != null) return false
        return true
    }

    private fun unusable(step: SkillStep): Phrase = SkillStrings.stepUnusable("step" to SkillCards.kindTitle(step))

    /** What is wrong with [step], step [index] (from 0) of [s]. */
    fun stepProblems(s: Skill, step: SkillStep, index: Int = s.steps.indexOf(step)): List<Phrase> {
        val out = mutableListOf<Phrase>()
        val unusable = unusable(step)
        val earlier = s.steps.take(maxOf(index, 0))
        // Templates: each names a question, or an output of an earlier step with an id (one of its kind's own outputs when it always has them).
        for (r in step.refs + listOfNotNull(step.cond?.value)) {
            val ok = if (r.output == null) s.param(r.name) != null else earlier.any { e ->
                e.id == r.name && (e.kind == StepKind.CONN || r.output in e.kind.outputs)
            }
            if (!ok) return listOf(unusable)
        }
        if ((step.kind == StepKind.CONN || step.kind == StepKind.APP) != (step.conn != null) || (step.kind == StepKind.CONN) != (step.op != null)) return listOf(unusable)
        if (step.conn != null && !SkillStep.validConnId(step.conn) || step.op != null && !SkillStep.validOpId(step.op)) return listOf(unusable)
        step.cond?.let { if (!condFits(s, it, earlier)) return listOf(unusable) }
        when (val c = step.check) {
            null -> {}
            is StepCheck.Equals -> if (step.kind != StepKind.CONN || !pointer(c.pointer) || !equalsValue(c.equals)) return listOf(unusable)
                else if (c.equals is String && secret(c.equals)) out += SkillStrings.secret()
            is StepCheck.Matches -> if (step.kind != StepKind.TERMUX_SCRIPT || !regex(c.regex)) return listOf(unusable)
        }
        // "Go on" only after a read: a step that changes something stops the skill when it fails (a connection's operation says for itself, attention).
        if (step.onFail == OnFail.GO_ON && !step.kind.reads && step.kind != StepKind.CONN) return listOf(unusable)
        if (step.kind == StepKind.TRAVEL_TIME) {
            val to = step.args["to"]
            if (step.args.keys.any { it !in step.kind.args } || to !in PLACES || step.args["conn"]?.let { it !is String || !SkillStep.validConnId(it) } == true)
                return listOf(unusable)
            return out
        }
        if (step.kind.linked) return out + linkedProblems(s, step, unusable)
        if (step.args.keys.any { it !in step.kind.args }) return listOf(unusable)
        for ((k, v) in step.args) {
            val whole = SkillTemplate.ref(v)
            val text = k in step.kind.textArgs
            when {
                // 1.1's form, kept as 1.1 had it: a question of the argument's own type (a person isn't a whole message; inside words it may be).
                whole != null && whole.output == null -> if (step.kind.args[k] != s.param(whole.name)!!.type) return listOf(unusable)
                // A step's output, or templates inside words: only in a text argument (an output never fills a typed argument of Robot's own tools).
                SkillTemplate.looksLike(v) -> {
                    if (!text || SkillTemplate.parts(v as String) == null) return listOf(unusable)
                    for (r in SkillTemplate.refs(v)) if (r.output == null && !SkillEncode.hasWords(s.param(r.name)!!.type)) return listOf(unusable)
                    if (secret(SkillTemplate.render(v) { "" })) out += SkillStrings.secret()
                }
                v !is String && v !is Number && v !is Boolean -> return listOf(unusable)
                v is String && secret(v) -> out += SkillStrings.secret()
            }
        }
        // A routine's own step (1.3) takes no arguments (the check above refuses any).
        if (step.kind.routineOnly) return out
        if (step.kind == StepKind.SAY) {
            val text = step.args["text"] as? String
            if (text == null || text.isBlank() || text.length > RoutineCheck.MAX_SAY || CONTROL.containsMatchIn(text)) out += unusable
            return out
        }
        // A time question fills "hour" with "HH:MM": the minute comes with it.
        if (SkillTemplate.name(step.args["hour"]) != null && "minute" in step.args) return listOf(unusable)
        val a = action(s, step, outputs = ::sample) ?: return out + unusable
        val canonical = RoutineCodec.args(a)
        val filled = s.withValues(step, outputs = ::sample)
        for ((k, v) in step.args) {
            val time = SkillTemplate.name(v)?.let { s.param(it)?.type } == ParamType.TIME
            if (!time && !same(filled[k], canonical[k])) return out + unusable
        }
        if (step.refs.isEmpty() && canonical.filterValues { it != null }.keys != step.args.keys) return out + unusable
        return out
    }

    /**
     * A `conn:`, `app:` or `termux_script` step: arguments named as a link's are (their types are [attention]'s), each a
     * constant, a template, or words with templates inside. A script's `script` is fixed and one of Termux's names; nothing
     * a step read becomes its argument (D15).
     */
    private fun linkedProblems(s: Skill, step: SkillStep, unusable: Phrase): List<Phrase> {
        val out = mutableListOf<Phrase>()
        for ((k, v) in step.args) {
            if (!ARG.matches(k)) return listOf(unusable)
            when {
                SkillTemplate.looksLike(v) -> if (SkillTemplate.parts(v as String) == null) return listOf(unusable)
                    else if (secret(SkillTemplate.render(v) { "" })) out += SkillStrings.secret()
                v is String -> if (CONTROL.containsMatchIn(v)) return listOf(unusable) else if (secret(v)) out += SkillStrings.secret()
                v !is Number && v !is Boolean -> return listOf(unusable)
            }
        }
        if (step.kind == StepKind.TERMUX_SCRIPT) {
            val name = step.args["script"]
            if (name !is String || SkillTemplate.looksLike(name) || !TermuxScripts.validName(name)) return listOf(unusable)
            if (step.refs.any { it.output != null }) return listOf(unusable)
        }
        return out
    }

    /** A condition on a typed value that fits its test: never free text (design §3.4), a choice only one of its values. */
    private fun condFits(s: Skill, c: SkillIf, earlier: List<SkillStep>): Boolean {
        val type: ParamType? = if (c.value.output == null) s.param(c.value.name)?.type else
            earlier.lastOrNull { it.id == c.value.name }?.kind?.outputs?.get(c.value.output)?.type
        if (type == null) return c.value.output != null && earlier.any { it.id == c.value.name && it.kind == StepKind.CONN } && testShaped(c.test)
        if (type.free) return false
        return testFits(c.test, type, if (c.value.output == null) s.param(c.value.name)!!.options.map { it.value } else emptyList()) && testShaped(c.test)
    }

    /** [t] fits a value of [type] ([options]: a choice's values, empty when any may come). */
    internal fun testFits(t: IfTest, type: ParamType, options: List<String>): Boolean = when (t) {
        IfTest.Set, IfTest.Unset -> true
        is IfTest.Is -> type == ParamType.CHOICE && (options.isEmpty() || t.choice in options)
        is IfTest.Yes -> type == ParamType.YES_NO
        is IfTest.Below, is IfTest.Above -> type == ParamType.INTEGER || type == ParamType.NUMBER || type == ParamType.DURATION
    }

    private fun testShaped(t: IfTest): Boolean = when (t) {
        is IfTest.Is -> validDefault(ParamType.CHOICE, t.choice) && !secret(t.choice)
        is IfTest.Below -> t.n.isFinite() && Math.abs(t.n) <= MAX_NUMBER
        is IfTest.Above -> t.n.isFinite() && Math.abs(t.n) <= MAX_NUMBER
        else -> true
    }

    /** RFC 6901: empty, or "/"-separated tokens where "~" is only "~0" or "~1". */
    private fun pointer(p: String): Boolean = p.length <= MAX_POINTER && !CONTROL.containsMatchIn(p) && (p.isEmpty() || p.startsWith("/")) &&
        !Regex("~(?![01])").containsMatchIn(p)

    private fun equalsValue(v: Any): Boolean = when (v) {
        is String -> v.length <= MAX_EQUALS && !CONTROL.containsMatchIn(v)
        is Boolean, is Long -> true
        is Double -> v.isFinite()
        else -> false
    }

    /** A script check's regular expression: short, on one line, and one Java reads. */
    private fun regex(r: String): Boolean = r.isNotEmpty() && r.length <= MAX_REGEX && !CONTROL.containsMatchIn(r) &&
        try { java.util.regex.Pattern.compile(r); true } catch (_: Exception) { false }

    /** A stand-in for any output when a step is read back: outputs only ever sit in words there, and "1" is words. */
    @Suppress("UNUSED_PARAMETER")
    private fun sample(r: SkillRef): Any = "1"

    /**
     * [step] as the tool's action, with [values] for its questions (the defaults for the rest) and [outputs] for earlier
     * steps' outputs; null when the tool refuses it or the step isn't one of Robot's tools.
     */
    fun action(s: Skill, step: SkillStep, values: Map<String, String> = emptyMap(), outputs: (SkillRef) -> Any? = { null }): Action? {
        val tool = step.kind.tool ?: return null
        val p = ToolCalls.parse(RawToolCall(tool.id, s.withValues(step, values, outputs)))
        return (p as? ParsedCall.Ok)?.action?.takeIf { it.tool == tool }
    }

    /** Two argument values the same once numbers are compared as numbers. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    // ------------------------------------------------------------------ what the links say (plan §5.2)

    /**
     * Why [s] can't run with [links] (empty: it can): a step's connection, operation, hand-off, script or routing server is
     * gone, or its arguments, condition, outputs or "go on" no longer fit what it links to. Never in [problems]: the skill
     * is kept, marked, and runs again once it's fixed (design §3.8).
     */
    fun attention(s: Skill, links: SkillLinks = SkillLinks.NONE): List<SkillAttention> = buildList {
        s.steps.forEachIndexed { i, st ->
            val why = stepAttention(s, st, i, links)
            if (why != null) add(SkillAttention(i, why))
        }
    }

    private fun stepAttention(s: Skill, st: SkillStep, i: Int, links: SkillLinks): AttentionWhy? {
        val earlier = s.steps.take(i)
        val slots: Map<String, SlotType> = when (st.kind) {
            StepKind.CONN -> {
                val op = links.op(st.conn!!, st.op!!) ?: return AttentionWhy.CONNECTION_GONE
                if (!op.params.keys.containsAll(st.args.keys) || !st.args.keys.containsAll(op.required)) return AttentionWhy.DOESNT_FIT
                if (st.onFail == OnFail.GO_ON && !op.read) return AttentionWhy.DOESNT_FIT
                // Home Assistant: every entity it may send is known, and "all" only where it's allowed (plan §3.1).
                op.ha?.let { ha ->
                    val e = SkillEffects.entities(s, st, op, ha) ?: return AttentionWhy.DOESNT_FIT
                    if ("all" in e && !SkillEffects.haAll(ha.domain)) return AttentionWhy.DOESNT_FIT // l10n: not text (Home Assistant's entity_id)
                }
                op.params
            }
            StepKind.APP -> {
                val h = links.handOff(st.conn!!) ?: return AttentionWhy.APP_GONE
                if (!h.slots.keys.containsAll(st.args.keys) || !st.args.keys.containsAll(h.required)) return AttentionWhy.DOESNT_FIT
                h.slots
            }
            StepKind.TERMUX_SCRIPT -> {
                val sc = (st.args["script"] as? String)?.let(links::script) ?: return AttentionWhy.SCRIPT_GONE
                val p = sc.params.toMap()
                if ((st.args.keys - "script") != p.keys) return AttentionWhy.DOESNT_FIT
                p
            }
            StepKind.TRAVEL_TIME -> if (links.routing(st.args["conn"] as? String)) emptyMap() else return AttentionWhy.NO_ROUTING
            else -> emptyMap()
        }
        if (st.kind.linked) for ((k, v) in st.args) {
            if (st.kind == StepKind.TERMUX_SCRIPT && k == "script") continue
            if (!argFits(s, v, slots.getValue(k), earlier, links)) return AttentionWhy.DOESNT_FIT
        }
        // Outputs named in words or a condition are ones the step's link declares; inside words, one that has words; a condition's, typed.
        for (r in st.refs) if (r.output != null) {
            val t = outputType(r, earlier, links) ?: return AttentionWhy.DOESNT_FIT
            if (!st.kind.linked && !SkillEncode.hasWords(t.type)) return AttentionWhy.DOESNT_FIT
        }
        st.cond?.let { c ->
            if (c.value.output != null) {
                val t = outputType(c.value, earlier, links) ?: return AttentionWhy.DOESNT_FIT
                if (t.type.free || !testFits(c.test, t.type, t.options)) return AttentionWhy.DOESNT_FIT
            }
        }
        return null
    }

    /** The type of output [r] of an earlier step, as its kind or its link declares it; null when there is none. */
    fun outputType(r: SkillRef, earlier: List<SkillStep>, links: SkillLinks): SlotType? {
        val e = earlier.lastOrNull { it.id == r.name } ?: return null
        val out = r.output ?: return null
        return if (e.kind == StepKind.CONN) e.conn?.let { c -> e.op?.let { links.op(c, it) } }?.outputs?.get(out) else e.kind.outputs[out]
    }

    /** A step argument [v] fits [slot]: a constant of its type, a template of a type the slot takes, or words with templates only in a text slot. */
    private fun argFits(s: Skill, v: Any?, slot: SlotType, earlier: List<SkillStep>, links: SkillLinks): Boolean {
        val whole = SkillTemplate.ref(v)
        if (whole != null) {
            val t = if (whole.output == null) s.param(whole.name)?.let { p -> SlotType(p.type, p.options.map { it.value }) } else outputType(whole, earlier, links)
            t ?: return false
            return when (slot.type) {
                ParamType.TEXT -> SkillEncode.hasWords(t.type)
                ParamType.NUMBER -> t.type == ParamType.NUMBER || t.type == ParamType.INTEGER
                ParamType.CHOICE -> t.type == ParamType.CHOICE && t.options.isNotEmpty() && (slot.options.isEmpty() || slot.options.containsAll(t.options))
                else -> t.type == slot.type
            }
        }
        if (SkillTemplate.looksLike(v)) return slot.type == ParamType.TEXT && SkillTemplate.refs(v).all { r ->
            val t = if (r.output == null) s.param(r.name)?.type else outputType(r, earlier, links)?.type
            t != null && SkillEncode.hasWords(t)
        }
        return when (slot.type) {
            ParamType.TEXT -> v is String
            ParamType.INTEGER -> v is Long
            ParamType.NUMBER -> v is Number
            ParamType.YES_NO -> v is Boolean
            ParamType.CHOICE -> v is String && (slot.options.isEmpty() || v in slot.options)
            else -> v is String
        }
    }
}
