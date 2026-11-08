// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContactName
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.Priority
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolMode
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import app.heyrobot.core.connect.ConnFixtures
import app.heyrobot.core.connect.ConnLinks
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.messages.MessageTaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Skills 1.3, drafts (design §10; skills-1.3-plan.md S3.8, M5 on the PC): the router, the read, the card, the quarantine. */
class SkillDrafterTest {
    private val on = SkillPlans.PLANNABLE.toSet()
    private val ha = ConnFixtures.homeAssistant("c-ha0001")
    private val links = ConnLinks(listOf(ha))
    private val ops = SkillDrafter.ops(listOf(ha))
    private val contacts = listOf(ContactName("1", "Sam Tremblay"), ContactName("2", "Alexandra Roy"))
    private fun step(use: String, vararg a: Pair<String, Any?>) = mapOf("use" to use, "args" to mapOf(*a))
    private fun call(name: String, vararg steps: Map<String, Any?>) = RawToolCall(SkillDrafter.TOOL, mapOf("name" to name, "steps" to steps.toList()))
    private val ask = SkillDrafter.Ask("sets a ten minute timer", null, Lang.EN)
    private fun read(vararg steps: Map<String, Any?>, source: DraftSource? = null, taint: MessageTaint = MessageTaint.NONE, tools: Set<ToolName> = on) =
        (SkillDrafter.read(listOf(call("tea time", *steps)), "", ask, "s-abc123", 1L, tools, ops, contacts, source, taint, links) as DraftRead.Ok).draft
    private fun en(p: Phrase?) = p?.render(Langs.EN)
    private fun lines(d: SkillDraft) = SkillCards.save(d, null).lines.map { en(it) }
    private val timer = step("set_timer", "minutes" to 10)

    // ---------------------------------------------------------------- the router

    @Test fun makeASkillThatIsADraftAndTeachingIsNot() {
        assertEquals(SkillDrafter.Ask("sets a ten minute timer", null, Lang.EN), SkillDrafter.route("make a skill that sets a ten minute timer"))
        assertEquals("good night", SkillDrafter.route("create a skill called good night that turns off the lights")?.name)
        assertEquals("turns off the lights", SkillDrafter.route("Create a skill called good night that turns off the lights.")?.words)
        assertEquals(Lang.FR, SkillDrafter.route("crée une compétence qui met un minuteur de 5 minutes")?.lang)
        assertEquals("bonne nuit", SkillDrafter.route("crée une compétence qui s'appelle bonne nuit qui éteint les lumières")?.name)
        assertEquals(Lang.FR, SkillDrafter.route("fais-moi une compétence pour la météo du matin")?.lang)
        assertNull(SkillDrafter.route("create a skill called heading home: text Sam I'm on my way"))
        assertNull(SkillDrafter.route("crée une compétence qui s'appelle bonne nuit"))
        assertNull(SkillDrafter.route("make that a skill"))
        assertNull(SkillDrafter.route("set a timer for 10 minutes"))
    }

    // ---------------------------------------------------------------- the read

    @Test fun stepsThatFailAreLeftOutAndNamed() {
        val d = read(timer, step("unlock_front_door"), step("call", "who" to "Sam"), step("conn:c-zz9999:lock", "x" to 1), step("set_alarm", "hour" to 99))
        assertEquals(listOf(StepKind.SET_TIMER), d.steps.map { it.kind })
        assertEquals(SkillMade.DRAFT_DEVICE, d.made)
        assertEquals("Tea time", d.name)
        val l = lines(d)
        assertTrue(l.toString(), l.contains("Robot left out step 2, unlock_front_door: Robot has no such action."))
        assertTrue(l.toString(), l.any { it!!.startsWith("Robot left out step 3,") && it.endsWith("skills never do that.") })
        assertTrue(l.toString(), l.contains("Robot left out step 4, conn:c-zz9999:lock: it isn't one of your connections."))
        assertTrue(l.toString(), l.any { it!!.startsWith("Robot left out step 5,") && it.endsWith("its values didn't check out.") })
    }

    @Test fun aToolThatIsOffAndAConnectionOfYours() {
        val d = read(timer, step(ops.first { it.title == "Turn off a light" }.use, "entity" to "light.kitchen"), tools = on - ToolName.SET_TIMER)
        assertEquals(listOf(StepKind.CONN), d.steps.map { it.kind })
        assertTrue(lines(d).any { it!!.startsWith("Robot left out step 1,") && it.endsWith("it's turned off.") })
        assertTrue(SkillDrafter.system(on.toList(), ops).contains("conn:c-ha0001:light_off"))
        assertFalse("never your contacts", SkillDrafter.system(on.toList(), ops).contains("Tremblay"))
    }

    @Test fun peopleAreFoundOrMissing() {
        val d = read(step("send_text", "to" to "Sam", "message" to "home soon"), step("send_text", "to" to "Zed", "message" to "hi"))
        val l = lines(d)
        assertTrue(l.toString(), l.contains("Sam: Sam Tremblay in your contacts"))
        assertTrue(l.toString(), l.contains("Zed: not in your contacts"))
    }

    @Test fun aModelWithoutToolsWritesTheJson() {
        val r = SkillDrafter.read(emptyList(), "Here: {\"name\": \"Tea\", \"steps\": [{\"use\": \"set_timer\", \"args\": {\"minutes\": 4}}]}", ask, "s-abc123", 1L,
            on, ops, contacts, null)
        assertEquals(listOf(StepKind.SET_TIMER), (r as DraftRead.Ok).draft.steps.map { it.kind })
        assertEquals(DraftRead.Refused(DraftRefusal.NO_DRAFT), SkillDrafter.read(emptyList(), "no", ask, "s-abc123", 1L, on, ops, contacts, null))
        assertEquals(DraftRead.Refused(DraftRefusal.BAD_SHAPE),
            SkillDrafter.read(listOf(RawToolCall("set_timer", mapOf("minutes" to 4))), "", ask, "s-abc123", 1L, on, ops, contacts, null))
    }

    // ---------------------------------------------------------------- M5: the card

    @Test fun aSpokenYesSaysToTap() {
        val d = read(timer)
        assertFalse(d.yesSaves)
        val c = SkillCards.save(d, null)
        assertEquals("Tap Save: Robot's model wrote this skill.", en(d.tapLine))
        assertEquals(en(d.tapLine), en(c.footer))
        assertEquals("New skill (draft): Tea time", en(c.title))
        assertFalse("no Just do it on a draft's card", c.levelChip)
        assertEquals(SkillLevel.ASK_FIRST, d.copy(level = SkillLevel.JUST_DO_IT).skill().level)
        assertEquals(SkillStrings.draftCheck().render(Langs.EN), lines(d).first())
        // Taught by voice with no content, a yes still saves.
        assertTrue(SkillTeach.teach("tea", "set a timer for 4 minutes", Lang.EN, "s-abc124", 1L, fromContent = false).yesSaves)
    }

    @Test fun aDraftAfterAWebPageIsQuarantined() {
        val d = read(timer, source = DraftSource.WEB)
        assertTrue(d.fromContent)
        val l = lines(d)
        assertTrue(l.toString(), l.contains(en(SkillStrings.draftFromWeb())))
        assertFalse(d.yesSaves)
        assertFalse("no Just do it", SkillCards.save(d, null).levelChip)
        val s = d.copy(level = SkillLevel.JUST_DO_IT).skill()
        assertEquals(SkillLevel.ASK_FIRST, s.level)
        assertTrue(s.provenance.fromContent)
        assertFalse("Just do it only after you edit and save it", SkillLevel.JUST_DO_IT in SkillEdit.levels(s))
        assertEquals(SkillGo.Card(tapOnly = true), SkillPolicy.decide(s, locked = false, phoneLock = false, messages = false))
        // No trigger: a skill gets one only as a routine's, and RoutineSkills never links a skill a routine didn't make.
        assertEquals(SkillMade.DRAFT_DEVICE, s.provenance.made)
        assertEquals(3, SkillCodec.needs(s))
        assertTrue(SkillEdit.levels(SkillEdit.mine(s)).contains(SkillLevel.JUST_DO_IT))
    }

    @Test fun afterAMessageOnlyItsSenderIsSentTo() {
        val taint = MessageTaint(conversations = setOf("t1"), senderKeys = setOf("+15145550101"))
        val d = read(step("send_text", "to" to "Sam", "message" to "ok"), step("send_text", "to" to "+15145550101", "message" to "ok"), timer,
            source = DraftSource.MESSAGE, taint = taint)
        assertEquals(listOf(StepKind.SEND_TEXT, StepKind.SET_TIMER), d.steps.map { it.kind })
        val l = lines(d)
        assertTrue(l.toString(), l.contains(en(SkillStrings.draftFromMessage())))
        assertTrue(l.toString(), l.any { it!!.startsWith("Robot left out step 1,") && it.endsWith("after a message, a skill sends only to who wrote it.") })
    }

    @Test fun theBannerSaysWhereTheContentCameFrom() {
        fun turn(t: ChatTurn.() -> ChatTurn) = ChatTurn("u", TurnRole.USER, "q", 1L).t()
        assertNull(SkillDrafter.source(listOf(turn { this })))
        assertEquals(DraftSource.MESSAGE, SkillDrafter.source(listOf(turn { copy(messages = true) }, turn { copy(attachment = Attachment.SCREEN) })))
        assertEquals(DraftSource.WEB, SkillDrafter.source(listOf(turn { copy(actions = listOf(ActionRecord(ToolName.WEB_SEARCH, "w", ActionStatus.DONE, content = true))) })))
        assertEquals(DraftSource.SCREEN, SkillDrafter.source(listOf(turn { copy(attachment = Attachment.SCREEN) })))
        assertEquals(DraftSource.CONTENT, SkillDrafter.source(listOf(turn { copy(fromHelper = true) })))
        // Every conversation TurnRules calls untrusted has a banner.
        for (t in listOf(turn { copy(readBack = true) }, turn { copy(locked = true) }, turn { copy(actions = listOf(ActionRecord(ToolName.FIND_NOTES, "n", ActionStatus.DONE, content = true))) }))
            assertEquals(TurnRules.untrusted(listOf(t)), SkillDrafter.source(listOf(t)) != null)
    }

    // ---------------------------------------------------------------- the one-shot

    @Test fun theDraftOneShotGetsMakeSkillAlone() {
        val req = BrainMsg.OneShot(OneShotKind.DRAFT, Priority.PANEL, SkillDrafter.system(listOf(ToolName.SET_TIMER), emptyList()), "w", 64)
        val spec = OneShotRules.withPlanTool(OneShotRules.conversation(req), req, ToolMode.TOOLS_CONSTRAINED)
        assertEquals(SkillDrafter.TOOL_JSON, spec.forced)
        assertTrue(spec.constrained)
        assertEquals(emptyList<ToolName>(), spec.tools)
        assertNull(OneShotRules.withPlanTool(OneShotRules.conversation(req), req, ToolMode.TEXT_CALLS).forced)
        assertTrue(OneShotRules.tokens(req) > OneShotRules.tokens(req.copy(kind = OneShotKind.TRANSLATE)))
        assertNotNull(org.json.JSONObject(SkillDrafter.TOOL_JSON).optJSONObject("parameters"))
    }

    @Test fun tapToSaveStaysForContentThatIsntADraft() {
        val d = SkillTeach.teach("tea", "set a timer for 4 minutes", Lang.EN, "s-abc124", 1L, fromContent = true)
        assertEquals(en(SkillStrings.tapToSave("save" to UiLabel(UiStrings.save))), en(d.tapLine))
    }
}
