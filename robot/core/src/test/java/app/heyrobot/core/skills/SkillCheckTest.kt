// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.SkillStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SkillCheck (plan §3): every limit, only 1.1's step kinds, templates of the argument's type, no secrets. */
class SkillCheckTest {
    private val home = SkillFixtures.headingHome()

    private fun bad(s: Skill) = assertFalse(s.toString(), SkillCheck.valid(s))

    @Test fun theFixturesAreValid() {
        assertEquals(emptyList<Any>(), SkillCheck.problems(home))
        assertEquals(emptyList<Any>(), SkillCheck.problems(SkillFixtures.pasta()))
    }

    @Test fun everyLimit() {
        bad(home.copy(id = "k3f9q2"))
        bad(home.copy(id = "s-K3F9Q2"))
        bad(home.copy(rev = 0))
        bad(home.copy(name = ""))
        bad(home.copy(name = "x".repeat(41)))
        assertTrue(SkillCheck.valid(home.copy(name = "x".repeat(40))))
        bad(home.copy(name = "Heading\nhome"))
        bad(home.copy(name = "Heading​home"))
        bad(home.copy(name = " Heading home"))
        bad(home.copy(name = "!!!"))
        bad(home.copy(namesEn = (1..7).map { "name $it" }))
        bad(home.copy(namesFr = listOf("un deux trois quatre cinq six")))
        bad(home.copy(namesEn = listOf("heading, home")))
        assertTrue(SkillCheck.valid(home.copy(namesFr = listOf("je m'en viens"))))
        // Questions: at most 3, a name of the right shape, unique, used by a step, an ask of at most 60 characters.
        val p = home.params[0]
        bad(home.copy(params = listOf(p, p.copy(name = "a"), p.copy(name = "b"), p.copy(name = "c"))))
        bad(home.copy(params = listOf(p.copy(name = "Who"))))
        bad(home.copy(params = listOf(p, p)))
        bad(home.copy(params = listOf(p, p.copy(name = "unused"))))
        bad(home.copy(params = listOf(p.copy(askEn = "x".repeat(61)))))
        bad(home.copy(params = listOf(p.copy(askFr = ""))))
        // Steps: 1 to 8.
        bad(home.copy(steps = emptyList()))
        val timer = SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to 60L))
        val nine = SkillFixtures.simple("s-nine99", "Nine").copy(steps = List(9) { timer })
        bad(nine)
        assertTrue(SkillCheck.valid(nine.copy(steps = List(8) { timer })))
        // A sentence to say: one line, at most 200 characters.
        val say = SkillFixtures.simple("s-say123", "Good night")
        assertTrue(SkillCheck.valid(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "good night"))))))
        bad(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "x".repeat(201))))))
        bad(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "a\nb")))))
        bad(say.copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("words" to "hi")))))
    }

    @Test fun everyStepKindSkillsNeverUseIsRefused() {
        for (t in StepKind.NEVER) assertNull(t.id, StepKind.of(t))
        for (t in listOf(ToolName.CALL, ToolName.REMEMBER, ToolName.FORGET, ToolName.ASK_HELPER, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE,
                ToolName.TERMUX_COMMAND, ToolName.TERMUX_SCRIPT, ToolName.WEB_SEARCH, ToolName.BROWSER_SEARCH)) assertTrue(t in StepKind.NEVER)
        // Every other tool is a step kind.
        for (t in ToolName.entries - StepKind.NEVER) assertEquals(t, StepKind.of(t)?.tool)
    }

    @Test fun templatesNameAQuestionOfTheArgumentsType() {
        // A person question can't fill the message.
        bad(home.copy(steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "{{who}}")))))
        // A template naming no question.
        bad(home.copy(steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{whom}}", "message" to "hi")))))
        // Something that only looks like a template.
        bad(home.copy(params = emptyList(), steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "{{ who }}", "message" to "hi")))))
        // An argument that is never a question (the flashlight's switch).
        val on = SkillParam("on", ParamType.TEXT, "true", "On?", "Allumée?")
        bad(home.copy(params = listOf(on), steps = listOf(SkillStep.of(StepKind.FLASHLIGHT, mapOf("on" to "{{on}}")))))
        // An argument the tool doesn't have.
        bad(home.copy(steps = listOf(SkillStep.of(StepKind.MAPS_NAVIGATE, mapOf("destination" to "home", "mode" to "walk")))))
    }

    @Test fun aQuestionAlwaysHasAValidDefault() {
        val p = home.params[0]
        bad(home.copy(params = listOf(p.copy(default = ""))))
        bad(home.copy(params = listOf(p.copy(default = "[Recipient Name]"))))
        assertTrue(SkillCheck.validDefault(ParamType.DURATION, "720"))
        assertFalse(SkillCheck.validDefault(ParamType.DURATION, "0"))
        assertFalse(SkillCheck.validDefault(ParamType.DURATION, "86401"))
        assertFalse(SkillCheck.validDefault(ParamType.DURATION, "12 minutes"))
        assertTrue(SkillCheck.validDefault(ParamType.TIME, "07:30"))
        assertFalse(SkillCheck.validDefault(ParamType.TIME, "7:30"))
        assertFalse(SkillCheck.validDefault(ParamType.TIME, "24:00"))
    }

    @Test fun aTimeQuestionFillsTheAlarmsHour() {
        val wake = SkillFixtures.simple("s-wake12", "Wake").copy(params = listOf(SkillParam("t", ParamType.TIME, "07:30", "What time?", "À quelle heure?")),
            steps = listOf(SkillStep.of(StepKind.SET_ALARM, mapOf("hour" to "{{t}}"))))
        assertTrue(SkillCheck.problems(wake).toString(), SkillCheck.valid(wake))
        assertEquals(Action.SetAlarm(7, 30), SkillCheck.action(wake, wake.steps[0]))
        assertEquals(Action.SetAlarm(6, 5), SkillCheck.action(wake, wake.steps[0], mapOf("t" to "06:05")))
        bad(wake.copy(steps = listOf(SkillStep.of(StepKind.SET_ALARM, mapOf("hour" to "{{t}}", "minute" to 0L)))))
    }

    @Test fun nothingThatLooksLikeAPasswordOrKey() {
        val secret = SkillStrings.secret()
        val text = home.copy(steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "the door code is 4512")), home.steps[1]),
            params = emptyList())
        assertTrue(secret in SkillCheck.problems(text))
        assertTrue(secret in SkillCheck.problems(home.copy(params = listOf(home.params[0].copy(default = "sk-ant-api03-abcdef1234567890ABCDEF")))))
        val say = SkillFixtures.simple("s-say123", "Wifi").copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "my password is Tr0ub4dor"))))
        assertTrue(secret in SkillCheck.problems(say))
        // A phone number isn't a secret.
        assertTrue(SkillCheck.valid(home.copy(params = emptyList(), steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "+1 514 555 0101", "message" to "hi"))))))
    }

    @Test fun madeFromContentStaysAtAskFirstOrOff() {
        bad(SkillFixtures.headingHome(fromContent = true, level = SkillLevel.JUST_DO_IT))
        assertTrue(SkillCheck.valid(SkillFixtures.headingHome(fromContent = true, level = SkillLevel.OFF)))
        assertTrue(SkillCheck.valid(SkillFixtures.headingHome(level = SkillLevel.JUST_DO_IT)))
    }
}
