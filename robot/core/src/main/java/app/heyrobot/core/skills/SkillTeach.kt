// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.EventText
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.StepPhrases
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import java.util.Locale

// Pure. A skill being made (plan §6): taught by voice ("teach a skill called heading home: text Sam I'm on my way, then
// open the route home") or saved from what Robot just did (SkillFromActions), waiting on its save card. Every value that
// can become a question is a slot, fixed at first; a tap on the card makes it asked each time, with this value as its
// default and a ready-made question (at most 3). A part not understood is named on the card and left out, never guessed.

/** A value of step [step]'s argument [arg] that can become a question of [type]. [value]: as the skills file keeps it. */
data class Slot(val step: Int, val arg: String, val type: ParamType, val value: String) {
    /** The value as the card shows it: "12 minutes", "07:30", "Sam". */
    val shown: Arg get() = valueArg(type, value)

    /** Its chip on the save card: "12 minutes · fixed", "12 minutes · asked each time". */
    fun chip(asked: Boolean): Phrase = SkillStrings.valueChip("value" to shown, "state" to if (asked) SkillStrings.asked() else SkillStrings.fixed())

    companion object {
        /** A value of [type] as words: a duration's seconds read out, a time on the clock, the rest as said. */
        fun valueArg(type: ParamType, value: String): Arg = when (type) {
            ParamType.DURATION -> value.toLongOrNull()?.let { Arg.Seconds(it) } ?: Arg.Plain(value)
            ParamType.TIME -> value.split(':').takeIf { it.size == 2 }?.let { (h, m) -> Arg.Sub(EventText.clock(h.toInt(), m.toInt())) } ?: Arg.Plain(value)
            else -> Arg.Plain(value)
        }
    }
}

/**
 * A skill on its save card. [said]: its name as you said it; [steps]: every value fixed; [asked]: the slots that are asked
 * each time. [unparsed], [cantDo], [leftOut], [notes]: the card's lines. [fromContent]: made while the conversation held
 * content Robot didn't write (D13): only a tap saves it, and it stays at Ask first or Off.
 */
data class SkillDraft(
    val id: String,
    val name: String,
    val lang: Lang,
    val steps: List<SkillStep>,
    val slots: List<Slot>,
    val made: SkillMade,
    val fromContent: Boolean,
    val now: Long,
    val asked: List<Slot> = emptyList(),
    val level: SkillLevel = SkillLevel.ASK_FIRST,
    val unparsed: List<String> = emptyList(),
    val cantDo: List<Phrase> = emptyList(),
    val leftOut: List<Phrase> = emptyList(),
    val notes: List<Phrase> = emptyList(),
    /** D8: the steps came after a pause. */
    val paused: Boolean = false,
) {
    /** The skill this card saves. */
    fun skill(): Skill {
        val params = mutableListOf<SkillParam>()
        val steps = steps.toMutableList()
        for (slot in asked) {
            val base = PARAM_NAMES.getValue(slot.type)
            val pname = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { n -> params.none { it.name == n } }
            val ask = ASK.getValue(slot.type)
            params += SkillParam(pname, slot.type, slot.value, ask().render(Langs.EN), ask().render(Langs.of(Lang.FR)))
            val st = steps[slot.step]
            val args = st.args.toMutableMap()
            args[slot.arg] = SkillTemplate.of(pname)
            if (slot.type == ParamType.TIME) args.remove("minute")
            steps[slot.step] = SkillStep.of(st.kind, args)
        }
        val key = otherName(name)
        return Skill(id, 1, displayName(name), if (lang == Lang.EN) listOfNotNull(key) else emptyList(), if (lang == Lang.FR) listOfNotNull(key) else emptyList(),
            params, steps, if (fromContent && level == SkillLevel.JUST_DO_IT) SkillLevel.ASK_FIRST else level,
            SkillProvenance(made, fromContent, now, now))
    }

    /** What can't be saved as it is (SkillCheck's lines, before any name check). */
    val problems: List<Phrase> get() = if (steps.isEmpty()) listOf(SkillStrings.noSteps()) else SkillCheck.problems(skill())

    /** A spoken yes may save it: it can be saved and it didn't come from content (D13). */
    val yesSaves: Boolean get() = !fromContent && problems.isEmpty()

    /** [slot] made asked each time, or fixed again; at most [SkillCheck.MAX_PARAMS] asked. */
    fun toggle(slot: Slot): SkillDraft = when {
        slot in asked -> copy(asked = asked - slot)
        asked.size >= SkillCheck.MAX_PARAMS || slot !in slots -> this
        else -> copy(asked = asked + slot)
    }

    fun renamed(name: String): SkillDraft = copy(name = name.trim())

    companion object {
        val PARAM_NAMES = mapOf(ParamType.DURATION to "length", ParamType.TIME to "time", ParamType.PERSON to "who", ParamType.TEXT to "message",
            ParamType.APP to "app", ParamType.PLACE to "place")
        val ASK = mapOf(ParamType.DURATION to SkillStrings.askDuration, ParamType.TIME to SkillStrings.askTime, ParamType.PERSON to SkillStrings.askPerson,
            ParamType.TEXT to SkillStrings.askText, ParamType.APP to SkillStrings.askApp, ParamType.PLACE to SkillStrings.askPlace)

        /** "heading home" → "Heading home", at most 40 characters on one line. */
        fun displayName(said: String): String {
            val t = said.replace(Regex("\\s+"), " ").trim().trim('.', ',', '!', '?', ':', ';').trim()
            return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        /** The name as you say it, for its language's list: letters, digits, apostrophes and hyphens; null when it isn't 1 to 5 words. */
        fun otherName(said: String): String? {
            val t = said.replace(Regex("[^\\p{L}\\p{N} '’-]+"), " ").replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
            return t.takeIf { SkillCheck.validOtherName(it) }
        }

        /** The values of [steps] that can become questions (plan §6's table). */
        fun slots(steps: List<SkillStep>): List<Slot> = steps.flatMapIndexed { i, st ->
            fun str(k: String) = (st.args[k] as? String)?.takeIf { it.isNotBlank() }
            when (st.kind) {
                StepKind.SET_TIMER -> listOfNotNull((st.args["seconds"] as? Number)?.let { Slot(i, "seconds", ParamType.DURATION, it.toLong().toString()) })
                StepKind.SET_ALARM -> {
                    val h = (st.args["hour"] as? Number)?.toInt()
                    val m = (st.args["minute"] as? Number)?.toInt() ?: 0
                    listOfNotNull(h?.let { Slot(i, "hour", ParamType.TIME, "%02d:%02d".format(it, m)) }) // l10n: not text (a value in the skills file)
                }
                StepKind.OPEN_APP -> listOfNotNull(str("name")?.let { Slot(i, "name", ParamType.APP, it) })
                StepKind.MAPS_SEARCH -> listOfNotNull(str("query")?.let { Slot(i, "query", ParamType.PLACE, it) })
                StepKind.MAPS_NAVIGATE -> listOfNotNull(str("destination")?.let { Slot(i, "destination", ParamType.PLACE, it) })
                StepKind.SEND_TEXT -> listOfNotNull(str("to")?.let { Slot(i, "to", ParamType.PERSON, it) }, str("message")?.let { Slot(i, "message", ParamType.TEXT, it) })
                StepKind.DRAFT_EMAIL -> listOfNotNull(str("to")?.let { Slot(i, "to", ParamType.PERSON, it) }, str("body")?.let { Slot(i, "body", ParamType.TEXT, it) })
                StepKind.ADD_NOTE -> listOfNotNull(str("text")?.let { Slot(i, "text", ParamType.TEXT, it) })
                StepKind.ADD_EVENT -> listOfNotNull(str("title")?.let { Slot(i, "title", ParamType.TEXT, it) })
                else -> emptyList()
            }
        }

        /** One of Robot's actions as a skill's step: its arguments as said (never a resolved number or contact); null when skills never do it. */
        fun step(a: Action): SkillStep? {
            val kind = StepKind.of(a.tool) ?: return null
            return SkillStep.of(kind, RoutineCodec.args(a).filterValues { it != null })
        }
    }
}

object SkillTeach {
    /**
     * The draft for "teach a skill called [name]: [steps]" ([lang]: the words it was said in). The steps are read by the
     * routines' step parser (StepPhrases); a part not understood, or a step skills never do, is named on the card.
     */
    fun teach(name: String, steps: String, lang: Lang, id: String, now: Long, fromContent: Boolean, paused: Boolean = false): SkillDraft {
        val acts = StepPhrases.steps(steps, UiLabel(UiStrings.skills))
        val out = mutableListOf<SkillStep>()
        val cantDo = mutableListOf<Phrase>()
        for (s in acts.steps) {
            val st = when (s) {
                is RoutineStep.Tool -> SkillDraft.step(s.action)?.takeIf { it.kind !in StepKind.SAVE_THAT_ONLY }
                is RoutineStep.Say -> SkillStep.of(StepKind.SAY, mapOf("text" to s.text))
                else -> null
            }
            if (st == null) cantDo += SkillStrings.cantDo("step" to s.summary()) else out += st
        }
        for (r in acts.refused) cantDo += SkillStrings.cantDo("step" to r)
        val notes = acts.notes.toMutableList()
        if (out.size > SkillCheck.MAX_STEPS) notes += SkillStrings.tooManySteps()
        val kept = out.take(SkillCheck.MAX_STEPS)
        return SkillDraft(id, SkillDraft.displayName(name), lang, kept, SkillDraft.slots(kept), SkillMade.VOICE, fromContent, now,
            unparsed = acts.unparsed, cantDo = cantDo, notes = notes, paused = paused)
    }

    /** Some step in [text] parses (D8: the words after "teach a skill called …" and a pause are the steps only then). */
    fun anyStep(text: String): Boolean = StepPhrases.steps(text, UiLabel(UiStrings.skills)).steps.isNotEmpty()
}
