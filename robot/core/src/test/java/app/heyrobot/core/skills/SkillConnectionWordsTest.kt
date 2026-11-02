// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.Quoted
import app.heyrobot.core.StepCode
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.connect.ConnFixtures
import app.heyrobot.core.connect.ConnLinks
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.toPromptTurns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C4: what a skill's connection step is called, by whom. The model is told what it does by its kind (D19), never a host,
 * a path, an entity, a topic or your operation's title; the skill index finds it by fixed words (§5.3); your skill's card
 * names it by your own title, your connection's name and its host (§5.4); History keeps your title and name (D20), the
 * model's replay only its kind; an older Robot keeps the taint of a connection's answer (FX2's record); its voice log codes.
 */
class SkillConnectionWordsTest {
    private val ha = ConnFixtures.homeAssistant()
    private val ntfy = ConnFixtures.ntfy()
    private val n8n = ConnFixtures.webhook()
    private val links = ConnLinks(listOf(ha, ntfy, n8n))
    private val secretWords = listOf("tail1234", "home.", "light.downstairs", "Turn off a light", "Is the light on", "Tell the family", "ntfy.example",
        "n8n", "Log a trip", "webhook/")

    private fun step(c: String, op: String, args: Map<String, Any?> = emptyMap()) = SkillStep.of(StepKind.CONN, args, conn = c, op = op)
    private fun skill(vararg steps: SkillStep) = Skill("s-words1", 1, "Good night", listOf("good night"), emptyList(), emptyList(), steps.toList(),
        SkillLevel.ASK_FIRST, SkillProvenance(SkillMade.SETTINGS, false, 1L, 1L))

    private val all = skill(step(ha.id, "light_off", mapOf("entity" to "light.downstairs")), step(ha.id, "light_state"), step(ntfy.id, "tell_family", mapOf("message" to "Good night")),
        step(n8n.id, "log_trip", mapOf("kind" to "work", "minutes" to 20L)))

    @Test fun theModelIsToldWhatItDoesNeverWhatItReaches() {
        val line = SkillSpecs.line(all, links)
        // Four kinds don't fit the line's 90 characters: the first three, "and more".
        assertEquals("Changes something at home, reads something at home, sends a notification, and more.", line)
        assertEquals("Calls a web address.", SkillSpecs.line(skill(all.steps[3]), links))
        for (w in secretWords) assertFalse(w, line.contains(w))
        // Its link gone or not passed: only that it uses a connection.
        assertEquals("Uses one of your connections.", SkillSpecs.line(skill(step(ha.id, "light_off"))))
        val spec = SkillSpecs.describe(listOf(all), listOf(all), links).single().spec
        assertEquals(line, spec.description)
        for (w in secretWords) assertFalse(w, spec.openApiJson().contains(w))
    }

    @Test fun theIndexFindsItByFixedWords() {
        val doc = SkillSelect.document(all, links)
        assertTrue(doc, doc.contains("lights") && doc.contains("notification") && doc.contains("home"))
        for (w in secretWords) assertFalse(w, doc.contains(w))
        assertTrue(SkillSelect.document(skill(SkillStep.of(StepKind.TRAVEL_TIME, mapOf("to" to "home"))), links).contains("travel time"))
        // A skill whose connection is gone isn't found at all.
        assertEquals(emptyList<Pair<Skill, Double>>(), SkillSelect.Index(listOf(all), ConnLinks(listOf(ha))).scores("good night"))
        assertEquals(listOf(all), SkillSelect.Index(listOf(all), links).scores("good night").map { it.first })
    }

    @Test fun yourCardNamesItYourWay() {
        assertEquals("Turn off a light · your Home Assistant (home.tail1234.ts.net) · changes your home",
            SkillCards.stepText(all, all.steps[0], links = links).render(Langs.EN))
        assertEquals("Tell the family · your Jarvis's ntfy (ntfy.example.org) · tells people", SkillCards.stepText(all, all.steps[2], links = links).render(Langs.EN))
        assertEquals("Log a trip · your n8n (n8n.example.org) · sends data to n8n.example.org", SkillCards.stepText(all, all.steps[3], links = links).render(Langs.EN))
        // Without its link: the kind only.
        assertEquals("Use one of your connections", SkillCards.stepText(all, all.steps[0]).render(Langs.EN))
        assertEquals(4, SkillCards.run(all, emptyMap(), links).size)
    }

    @Test fun historyKeepsYourWordsAndTheReplayOnlyTheKind() {
        val asked = ChatTurn("q", TurnRole.USER, "good night", 1)
        val read = ChatTurn("a", TurnRole.ASSISTANT, "Good night: 2 of 2 done.", 2, actions = listOf(
            ActionRecord(ToolName.CONNECTION, "Turn off a light · your Home Assistant", ActionStatus.DONE, "Your Home Assistant said yes."),
            ActionRecord(ToolName.CONNECTION, "Is the light on · your Home Assistant", ActionStatus.DONE, "Read from your Home Assistant.", content = true)))
        val c = StoredConversation("c", 1, 2, listOf(asked, read))
        val json = ConversationCodec.toJson(c)
        // History keeps your title and your connection's name (D20); this Robot reads back exactly what it saved.
        assertTrue(json.contains("Turn off a light · your Home Assistant"))
        assertEquals(c, ConversationCodec.fromJson(json))
        // An older Robot drops the connection's records, but keeps the content record FX2 writes beside them.
        assertTrue(json.contains(ConversationCodec.COMPAT))
        assertTrue(json.contains(Quoted.record().summary))
        // Replayed to the model by its kind only.
        val replay = listOf(asked, read).toPromptTurns().last().text
        assertTrue(replay, replay.startsWith("(Your connections: done.) (Your connections: done.)"))
        for (w in secretWords) assertFalse(w, replay.contains(w))
        // A write whose answer kept nothing writes no extra record.
        val write = read.copy(actions = read.actions.take(1))
        assertFalse(ConversationCodec.toJson(StoredConversation("c", 1, 2, listOf(asked, write))).contains(ConversationCodec.COMPAT))
    }

    @Test fun theVoiceLogsCodes() {
        assertEquals(listOf(StepCode.CONNECTION, StepCode.HAND_OFF, StepCode.TRAVEL_TIME, StepCode.TYPED_SCRIPT),
            listOf(StepKind.CONN, StepKind.APP, StepKind.TRAVEL_TIME, StepKind.TERMUX_SCRIPT).map { SkillRun.code(it) })
        assertEquals(listOf(40, 41, 42, 43), listOf(StepCode.CONNECTION, StepCode.HAND_OFF, StepCode.TRAVEL_TIME, StepCode.TYPED_SCRIPT).map { it.id })
        assertEquals(StepCode.entries.size, StepCode.entries.map { it.id }.toSet().size)
        assertEquals(StepCode.CONNECTION, StepCode.of(ToolName.CONNECTION))
        assertEquals(ToolName.CONNECTION, StepKind.CONN.runsAs)
        assertEquals(ToolName.CONNECTION, StepKind.TRAVEL_TIME.runsAs)
        assertEquals(ToolName.HAND_OFF, StepKind.APP.runsAs)
        assertEquals(ToolName.TERMUX_SCRIPT, StepKind.TERMUX_SCRIPT.runsAs)
        // Never captured from a card by "save that" or teaching.
        assertEquals(null, StepKind.of(ToolName.CONNECTION))
        assertEquals(null, StepKind.of(ToolName.HAND_OFF))
    }
}
