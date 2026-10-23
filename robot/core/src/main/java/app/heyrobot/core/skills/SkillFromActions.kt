// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings

// Pure. "Save that as a skill" (plan D12): the actions of this conversation that happened (Done or Draft opened) since
// the last "save that", at most 8, in order, each kept as you said it (RoutineCodec.args: the contact or number a card
// resolved is found again when the skill runs, never saved). What a helper asked for is left out, and so is what didn't
// happen and what skills never do; the card says so.

/** One action card of the conversation, as it ended. [helper]: a helper asked for it. */
data class RecentAction(val action: Action, val status: ActionStatus, val helper: Boolean = false)

object SkillFromActions {
    /**
     * The draft for "save that" over [actions] (oldest first), named [name] or after its first steps' kinds in [lang];
     * null when nothing can be saved. [taken]: names already in use, so the default one gets a number.
     */
    fun draft(actions: List<RecentAction>, name: String?, lang: Lang, id: String, now: Long, fromContent: Boolean, taken: Set<String> = emptySet()): SkillDraft? {
        val leftOut = mutableListOf<Phrase>()
        val cantDo = mutableListOf<Phrase>()
        val steps = mutableListOf<SkillStep>()
        if (actions.any { it.helper }) leftOut += SkillStrings.leftOutHelper()
        if (actions.any { !it.helper && !it.status.succeeded }) leftOut += SkillStrings.leftOutFailed()
        for (a in actions.filter { !it.helper && it.status.succeeded }) {
            val st = SkillDraft.step(a.action)
            if (st == null) cantDo += SkillStrings.cantDo("step" to a.action.summary()) else steps += st
        }
        if (steps.isEmpty()) return null
        val kept = steps.takeLast(SkillCheck.MAX_STEPS)
        val named = name?.takeIf { it.isNotBlank() } ?: defaultName(kept, lang, taken)
        return SkillDraft(id, SkillDraft.displayName(named), lang, kept, SkillDraft.slots(kept), SkillMade.DEMO, fromContent, now,
            cantDo = cantDo, leftOut = leftOut)
    }

    /** "Timer and text": the first two kinds of [steps], in [lang]; "Timer and text 2" when that is taken. */
    fun defaultName(steps: List<SkillStep>, lang: Lang, taken: Set<String> = emptySet()): String {
        val kinds = steps.map { KIND.getValue(it.kind) }.distinct()
        val l = Langs.of(lang)
        val base = (if (kinds.size == 1) kinds[0]() else SkillStrings.defaultName("first" to kinds[0](), "second" to Phrase.M(kinds[1]).render(l).lowercase()))
            .render(l)
        val keys = taken.map { SkillKey.of(it) }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { SkillKey.of(it) !in keys }
    }

    private val KIND: Map<StepKind, Msg> = mapOf(
        StepKind.SET_TIMER to SkillStrings.kindTimer, StepKind.SET_ALARM to SkillStrings.kindAlarm, StepKind.FLASHLIGHT to SkillStrings.kindFlashlight,
        StepKind.OPEN_APP to SkillStrings.kindApp, StepKind.MAPS_SEARCH to SkillStrings.kindMaps, StepKind.MAPS_NAVIGATE to SkillStrings.kindMaps,
        StepKind.SEND_TEXT to SkillStrings.kindText, StepKind.DRAFT_EMAIL to SkillStrings.kindEmail, StepKind.ADD_NOTE to SkillStrings.kindNote,
        StepKind.ADD_EVENT to SkillStrings.kindEvent, StepKind.WEATHER to SkillStrings.kindWeather, StepKind.READ_CALENDAR to SkillStrings.kindCalendar,
        StepKind.FIND_NOTES to SkillStrings.kindNotes, StepKind.PHONE_STATUS to SkillStrings.kindPhone, StepKind.LOOKUP_CONTACT to SkillStrings.kindContact,
        StepKind.SAY to SkillStrings.kindSay,
    )
}
