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

// Pure. What a skill may hold (plan §3, "SkillCheck"), used when it is saved and when skills.json is read: the limits of
// each field, every template naming a question of the argument's type, only 1.1's step kinds, every fixed value and
// default read back through the tool's own validation (ToolCalls.parse, RoutineCodec.readBack's rule), and nothing that
// looks like a password or key. Names colliding with Robot's own phrases are SkillNames' (they depend on the phone).

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

    private val ID = Regex("^s-[a-z0-9]{6}$")
    private val PARAM = Regex("^[a-z][a-z0-9_]{0,15}$")
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")
    private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    /** An other name: letters, digits, spaces, apostrophes and hyphens ("je m'en viens", "drop-off"). */
    private val OTHER_NAME = Regex("^[\\p{L}\\p{N} '’-]+$")
    /** A language key as a later Robot writes one ("es", "de", "fr-FR"); "en" and "fr" are this Robot's own fields. */
    private val LANG_KEY = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    private val OWN_LANGS = setOf("en", "fr")

    fun validId(id: String?): Boolean = id != null && ID.matches(id)

    fun validParamName(n: String?): Boolean = n != null && PARAM.matches(n)

    /** A skill's name: 1 to 40 characters on one line, with at least a letter or digit. */
    fun validName(name: String): Boolean = name.isNotBlank() && name == name.trim() && name.length <= MAX_NAME && !CONTROL.containsMatchIn(name) &&
        SkillKey.of(name).isNotEmpty()

    /** Another name: 1 to 5 words of letters and digits. */
    fun validOtherName(n: String): Boolean = n == n.trim() && OTHER_NAME.matches(n) && SkillKey.words(n).size in 1..MAX_NAME_WORDS

    /** A language key for [Skill.namesMore] or [SkillParam.askMore]: a language tag's shape, and not one 1.1 keeps itself. */
    fun validMoreLang(k: String): Boolean = LANG_KEY.matches(k) && k !in OWN_LANGS

    /** A question as asked in one language: one line of 1 to 60 characters. */
    private fun validAsk(a: String): Boolean = a.isNotBlank() && a.length <= MAX_ASK && !CONTROL.containsMatchIn(a)

    /** A value of [type]: a duration in whole seconds within a timer's limit, a time as "HH:MM", the rest one line of text. */
    fun validDefault(type: ParamType, v: String): Boolean = when (type) {
        ParamType.DURATION -> v.toLongOrNull()?.let { it in 1..Durations.MAX_TIMER_S } == true && v == v.trim()
        ParamType.TIME -> TIME.matches(v)
        ParamType.TEXT -> v.isNotBlank() && v.length <= ToolCalls.MAX_MESSAGE
        ParamType.PERSON -> v.isNotBlank() && v.length <= MAX_PERSON && !CONTROL.containsMatchIn(v) && !ToolCalls.placeholder(v)
        ParamType.APP -> v.isNotBlank() && v.length <= MAX_APP && !CONTROL.containsMatchIn(v)
        ParamType.PLACE -> v.isNotBlank() && v.length <= MAX_PLACE && !CONTROL.containsMatchIn(v)
    }

    /** Looks like a password or key (SecretDetector): a skill never keeps one, fixed or as a default. */
    fun secret(v: String): Boolean = SecretDetector.find(v).isNotEmpty()

    fun valid(s: Skill): Boolean = problems(s).isEmpty()

    /** What is wrong with [s], in words without a language yet; empty when it may be saved and run. */
    fun problems(s: Skill): List<Phrase> = buildList {
        if (!validId(s.id) || s.rev < 1 || s.provenance.created < 0 || s.provenance.edited < 0) add(SkillStrings.couldntSave())
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
            val ok = validParamName(p.name) && s.params.count { it.name == p.name } == 1 && validDefault(p.type, p.default) && p.name in named &&
                listOf(p.askEn, p.askFr).all(::validAsk) && p.askMore.size <= MAX_MORE_LANGS &&
                p.askMore.all { (k, a) -> validMoreLang(k) && validAsk(a) }
            if (!ok) add(SkillStrings.questionUnusable("ask" to p.askEn))
            if (secret(p.default)) add(SkillStrings.secret())
        }
        for (st in s.steps) addAll(stepProblems(s, st))
    }.distinct()

    /** What is wrong with [step] of [s]. */
    fun stepProblems(s: Skill, step: SkillStep): List<Phrase> {
        val out = mutableListOf<Phrase>()
        val unusable = SkillStrings.stepUnusable("step" to (step.kind.tool?.titleText ?: SkillStrings.stepSay("text" to (step.args["text"] as? String).orEmpty())))
        if (step.args.keys.any { it !in step.kind.args }) return listOf(unusable)
        for ((k, v) in step.args) {
            val t = SkillTemplate.name(v)
            if (t != null) {
                val p = s.param(t)
                if (p == null || step.kind.args[k] != p.type) return listOf(unusable)
            } else {
                if (SkillTemplate.looksLike(v)) return listOf(unusable)
                if (v !is String && v !is Number && v !is Boolean) return listOf(unusable)
                if (v is String && secret(v)) out += SkillStrings.secret()
            }
        }
        if (step.kind == StepKind.SAY) {
            val text = step.args["text"] as? String
            if (text == null || text.isBlank() || text.length > RoutineCheck.MAX_SAY || CONTROL.containsMatchIn(text)) out += unusable
            return out
        }
        // A time question fills "hour" with "HH:MM": the minute comes with it.
        if (SkillTemplate.name(step.args["hour"]) != null && "minute" in step.args) return listOf(unusable)
        val a = action(s, step) ?: return out + unusable
        val canonical = RoutineCodec.args(a)
        val filled = s.withValues(step)
        for ((k, v) in step.args) {
            val time = SkillTemplate.name(v)?.let { s.param(it)?.type } == ParamType.TIME
            if (!time && !same(filled[k], canonical[k])) return out + unusable
        }
        if (step.templates.isEmpty() && canonical.filterValues { it != null }.keys != step.args.keys) return out + unusable
        return out
    }

    /** [step] as the tool's action, with [values] for its questions (the defaults for the rest); null when the tool refuses it. */
    fun action(s: Skill, step: SkillStep, values: Map<String, String> = emptyMap()): Action? {
        val tool = step.kind.tool ?: return null
        val p = ToolCalls.parse(RawToolCall(tool.id, s.withValues(step, values)))
        return (p as? ParsedCall.Ok)?.action?.takeIf { it.tool == tool }
    }

    /** Two argument values the same once numbers are compared as numbers. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}
