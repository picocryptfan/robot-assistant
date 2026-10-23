// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.GateDecision
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.TurnStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §7.3, D6: each level × locked × phone lock × content × messages × named; a skill only adds cards, never removes one. */
class SkillPolicyTest {
    private val base = SkillFixtures.headingHome()

    @Test fun everyLevel() {
        for (level in SkillLevel.entries) for (content in listOf(false, true)) for (locked in listOf(false, true)) for (phone in listOf(false, true))
            for (messages in listOf(false, true)) {
                if (content && level == SkillLevel.JUST_DO_IT) continue
                val s = SkillFixtures.headingHome(level = level, fromContent = content)
                val go = SkillPolicy.decide(s, locked = locked || phone, phoneLock = phone, messages = messages)
                val what = "$level content=$content locked=$locked phone=$phone messages=$messages"
                when {
                    // Off never runs, locked or not.
                    level == SkillLevel.OFF -> assertEquals(what, SkillGo.Refused(SkillStrings.isOff("name" to s.name,
                        "page" to app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.skills))), go)
                    phone -> assertEquals(what, SkillGo.Refused(LockStrings.closedNow()), go)
                    locked -> assertEquals(what, SkillGo.Refused(TurnStrings.unlockThenAsk()), go)
                    level == SkillLevel.ASK_FIRST -> assertEquals(what, SkillGo.Card(tapOnly = content || messages), go)
                    else -> assertEquals(what, SkillGo.Run, go)
                }
            }
    }

    @Test fun lockedLines() {
        assertEquals(SkillStrings.unlockChange(), SkillPolicy.lockedLine(change = true, phoneLock = false))
        assertEquals(SkillStrings.unlockSee(), SkillPolicy.lockedLine(change = false, phoneLock = false))
        assertEquals(LockStrings.closedNow(), SkillPolicy.lockedLine(change = true, phoneLock = true))
    }

    /**
     * Never weaker than a step: whatever the level, each step goes to its own gate with the conversation's content (plus the
     * skill's own, when it was made from content) and "asked" only when your words named the skill. So the gate's decision
     * for a step inside a skill is never RUN where the same step typed in the same conversation would ask.
     */
    @Test fun neverWeakerThanAStep() {
        for (safety in SafetyCards.entries) for (content in listOf(false, true)) for (fromContent in listOf(false, true)) for (named in listOf(false, true)) {
            val s = SkillFixtures.headingHome(fromContent = fromContent)
            val settings = RobotSettings(safetyCards = safety)
            for (st in s.steps) {
                val tool = st.kind.tool!!
                val inSkill = ApprovalGate.decide(tool, false, settings, content = SkillPolicy.untrusted(s, content),
                    asked = tool in SkillPolicy.asked(tool, named))
                // The same step typed in your own words in this conversation.
                val typed = ApprovalGate.decide(tool, false, settings, content = content, asked = true)
                if (typed == GateDecision.CONFIRM) assertEquals("$safety $content $fromContent $named $tool", GateDecision.CONFIRM, inSkill)
            }
        }
        // A skill made from content brings content into its steps; its name alone isn't asking.
        assertTrue(SkillPolicy.untrusted(SkillFixtures.headingHome(fromContent = true), false))
        assertFalse(SkillPolicy.untrusted(base, false))
        assertEquals(emptySet<ToolName>(), SkillPolicy.asked(ToolName.SEND_TEXT, named = false))
        assertEquals(setOf(ToolName.SEND_TEXT), SkillPolicy.asked(ToolName.SEND_TEXT, named = true))
    }
}
