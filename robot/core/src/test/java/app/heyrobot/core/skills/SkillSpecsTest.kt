// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainPolicy
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.Prompt
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B (plan §8, D20): what the model is told about a skill, and how it reaches :brain. */
class SkillSpecsTest {
    companion object {
        /** [n] skill specs at their longest: a 24-character slug, a 90-character line, 3 questions with 30-character asks. */
        fun longest(n: Int): List<SkillToolSpec> = (1..n).map { i ->
            SkillToolSpec(SkillSpecs.PREFIX + "abcdefghijklmnopqrstuvw$i", ("Sets a timer, texts someone, opens a route, drafts an email, " + "x".repeat(90)).take(89) + ".",
                listOf("first_question_$i", "second_question$i", "third_question_$i").map { it.take(16) to "Who should I tell about it now" })
        }
    }

    private val home = SkillFixtures.headingHome()
    private val pasta = SkillFixtures.pasta()

    @Test fun theLineSaysWhatTheStepsDoWithoutTheirValues() {
        assertEquals("Texts someone, then opens a route.", SkillSpecs.line(home))
        assertEquals("Sets a timer, then texts someone.", SkillSpecs.line(pasta))
        val tool = SkillSpecs.describe(listOf(home), listOf(home)).single()
        val json = tool.spec.openApiJson()
        // D20: never a step's fixed value, nor a default.
        for (secret in listOf("Sam", "On my way home", "\"destination\"", "maps_navigate", "send_text")) assertFalse(secret, json.contains(secret))
        assertEquals("skill_heading_home", tool.name)
        assertEquals(listOf("who" to "Who should I tell?"), tool.spec.params)
        assertEquals(home.id, tool.skillId)
        assertEquals(home.rev, tool.rev)
    }

    @Test fun longLinesAndAsksAreCut() {
        val steps = listOf(StepKind.SET_TIMER, StepKind.SEND_TEXT, StepKind.MAPS_NAVIGATE, StepKind.DRAFT_EMAIL, StepKind.OPEN_APP, StepKind.WEATHER,
            StepKind.READ_CALENDAR, StepKind.PHONE_STATUS).map { SkillStep(it, emptyMap()) }
        val many = home.copy(steps = steps)
        val line = SkillSpecs.line(many)
        assertTrue(line, line.length <= SkillSpecs.MAX_LINE && line.endsWith("and more."))
        // The same kind twice in a row is said once.
        assertEquals("Sets a timer.", SkillSpecs.line(home.copy(steps = List(3) { SkillStep(StepKind.SET_TIMER, emptyMap()) })))
        assertEquals("Who should I tell about the", SkillSpecs.ask("Who should I tell about the move this morning?"))
        assertTrue(SkillSpecs.ask("x".repeat(60)).length <= SkillSpecs.MAX_ASK)
    }

    @Test fun slugsAreFoldedUniqueAndStable() {
        assertEquals("heading_home", SkillSpecs.slug("Heading home"))
        assertEquals("je_rentre_a_la_maison", SkillSpecs.slug("Je rentre à la maison"))
        assertEquals("dinner_s_ready", SkillSpecs.slug("Dinner's ready"))
        assertEquals("skill", SkillSpecs.slug("ура"))
        assertEquals(24, SkillSpecs.slug("a very long name for a skill that goes on").length)
        val a = SkillFixtures.simple("s-aaaaaa", "The morning routine for weekdays")
        val b = SkillFixtures.simple("s-bbbbbb", "The morning routine for weekends")
        val slugs = SkillSpecs.slugs(listOf(b, a))
        assertEquals(2, slugs.values.toSet().size)
        assertTrue(slugs.values.all { it.length <= SkillSpecs.MAX_SLUG && Regex("^[a-z0-9_]+$").matches(it) })
        // The same whatever was described with it.
        assertEquals(SkillSpecs.describe(listOf(b), listOf(a, b)).single().name, SkillSpecs.describe(listOf(b, a), listOf(a, b)).first().name)
        assertEquals(slugs.getValue(b.id), SkillSpecs.describe(listOf(b), listOf(a, b)).single().name.removePrefix(SkillSpecs.PREFIX))
    }

    @Test fun skillsTravelInGenerateBothWays() {
        val specs = SkillSpecs.describe(listOf(home, pasta), listOf(home, pasta)).map { it.spec }
        val gen = BrainMsg.Generate("c", "sys", emptyList(), 0, "hi", false, skills = specs)
        val back = BrainMsg.decode(BrainProtocol.REQ_GENERATE, gen.toJson().toString()) as BrainMsg.Generate
        assertEquals(specs, back.skills)
        assertEquals(gen, back)
        // None: no key, and an older request reads as none.
        val none = BrainMsg.Generate("c", "sys", emptyList(), 0, "hi", false)
        assertFalse(none.toJson().has("skills"))
        assertEquals(emptyList<SkillToolSpec>(), (BrainMsg.decode(BrainProtocol.REQ_GENERATE, none.toJson().toString()) as BrainMsg.Generate).skills)
    }

    @Test fun brainLeavesOutASpecTheAppCouldntHaveMade() {
        val ok = SkillSpecs.describe(listOf(home), listOf(home)).single().spec
        fun with(vararg o: JSONObject): List<SkillToolSpec> {
            val j = BrainMsg.Generate("c", "s", emptyList(), 0, "t", false).toJson().put("skills", org.json.JSONArray(o.toList()))
            return (BrainMsg.decode(BrainProtocol.REQ_GENERATE, j.toString()) as BrainMsg.Generate).skills
        }
        assertEquals(listOf(ok), with(ok.toJson()))
        assertEquals(emptyList<SkillToolSpec>(), with(ok.toJson().put("name", "set_timer")))
        assertEquals(emptyList<SkillToolSpec>(), with(ok.toJson().put("name", "skill_Bad Name")))
        assertEquals(emptyList<SkillToolSpec>(), with(ok.toJson().put("description", "x".repeat(91))))
        assertEquals(emptyList<SkillToolSpec>(), with(ok.toJson().put("params", org.json.JSONArray().put(JSONObject().put("name", "who").put("ask", "x".repeat(31))))))
        assertNull(SkillToolSpec.fromJson(JSONObject().put("name", "skill_x")))
        // At most 3, each name once.
        val four = longest(4).map { it.toJson() }.toTypedArray()
        assertEquals(3, with(*four).size)
        assertEquals(1, with(ok.toJson(), ok.toJson()).size)
    }

    @Test fun chatOnlyGetsNoSkillsAndAChangedSkillRebuilds() {
        val specs = longest(2)
        assertEquals(emptyList<SkillToolSpec>(), BrainPolicy.skillsFor(ToolMode.CHAT_ONLY, specs))
        assertEquals(specs, BrainPolicy.skillsFor(ToolMode.TEXT_CALLS, specs))
        val keys = BrainPolicy.toolKeys(Prompt.CORE_TOOLS, specs)
        assertEquals(Prompt.CORE_TOOLS.map { it.id }, BrainPolicy.toolKeys(Prompt.CORE_TOOLS, emptyList()))
        assertNotEquals(keys, BrainPolicy.toolKeys(Prompt.CORE_TOOLS, listOf(specs[0], specs[1].copy(description = "Opens an app."))))
    }

    @Test fun textCallsReadASkillsCall() {
        val calls = ToolCalls.wholeReplyCalls("call:skill_heading_home{who:<escape>Alex<escape>}")
        assertEquals("skill_heading_home", calls.single().call.name)
        assertEquals("Alex", calls.single().call.args["who"])
        assertEquals("skill_pasta", ToolCalls.wholeReplyCalls("""{"name": "skill_pasta", "arguments": {"length": "8 minutes"}}""").single().call.name)
        // Prose that mentions one never runs.
        assertEquals(emptyList<Any>(), ToolCalls.wholeReplyCalls("I could call:skill_pasta{} for you."))
    }
}
