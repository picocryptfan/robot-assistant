// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiStrings

// Pure. Whether a skill may run, and with what card (plan §7.3, D6). The skill's card only ever adds to the steps' own:
// each step still goes through the approval gate with its own card (SkillRun), so no level here is weaker than a step.
// Off never runs; nothing runs on a locked phone (a phone lock says it closes it); Ask first shows the skill's card, which
// only a tap answers when the skill came from content or a message is in the conversation; Just do it shows none.

sealed class SkillGo {
    /** Not run: what Robot says. */
    data class Refused(val why: Phrase) : SkillGo()

    /** The skill's card first ([tapOnly]: a spoken yes doesn't answer it), then the steps. */
    data class Card(val tapOnly: Boolean) : SkillGo()

    /** The steps at once, each with its own gate and card. */
    data object Run : SkillGo()
}

object SkillPolicy {
    /**
     * [locked]: Assistant.locked (a phone lock counts); [phoneLock]: a phone lock is on. [messages]: a message someone
     * sent is in the conversation (MessageTaint.active).
     */
    fun decide(s: Skill, locked: Boolean, phoneLock: Boolean, messages: Boolean): SkillGo = when {
        s.level == SkillLevel.OFF -> SkillGo.Refused(SkillStrings.isOff("name" to s.name, "page" to UiStrings.inSettings(UiStrings.skills)))
        locked -> SkillGo.Refused(if (phoneLock) LockStrings.closedNow() else TurnStrings.unlockThenAsk())
        s.level == SkillLevel.ASK_FIRST || s.provenance.fromContent -> SkillGo.Card(tapOnly = s.provenance.fromContent || messages)
        else -> SkillGo.Run
    }

    /**
     * What a step's gate is told (D5): the conversation's content ([untrusted]), and that you asked for the step only when
     * your words named the skill ([named]). A skill made from content brings content of its own: its steps' details came
     * from text Robot didn't write.
     */
    fun untrusted(s: Skill, untrusted: Boolean): Boolean = untrusted || s.provenance.fromContent

    fun asked(tool: ToolName?, named: Boolean): Set<ToolName> = if (named && tool != null) setOf(tool) else emptySet()

    /** What a locked phone says to teaching, saving or deleting ([change]), or to listing and asking about skills. */
    fun lockedLine(change: Boolean, phoneLock: Boolean): Phrase = when {
        phoneLock -> LockStrings.closedNow()
        change -> SkillStrings.unlockChange()
        else -> SkillStrings.unlockSee()
    }
}
