// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnView
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.MessageTaint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Test Bench SK11 on the JVM: a skill named zq9marker with a value zq9marker2, taught, saved, run (one step done, one
 * stopped) and deleted. Neither word reaches RLog or the voice log: codes and counts only.
 */
class SkillLogTest {
    private val lines = mutableListOf<String>()
    private val dir = Files.createTempDirectory("skill-log").toFile()

    @After fun clean() {
        RLog.sink = null
        dir.deleteRecursively()
    }

    @Test fun theMarkerNeverReachesTheLogs() = runBlocking {
        RLog.sink = { synchronized(lines) { lines += it } }
        val before = VoiceLog.entries().size
        val cmd = SkillRouter.route("teach a skill called zq9marker: say zq9marker2, then text Sam zq9marker2", emptyList()) as SkillCommand.Teach
        val draft = SkillTeach.teach(cmd.name, cmd.steps!!, Lang.EN, "s-zq9mrk", 1L, false)
        val skill = draft.toggle(draft.slots.first { it.type == ParamType.TEXT }).skill()
        assertTrue(SkillCheck.problems(skill).toString(), SkillCheck.valid(skill))
        val store = SkillStore(dir)
        assertTrue(store.save(skill))
        val run = SkillRouter.route("zq9marker saying zq9marker2", store.skills()) as SkillCommand.Run
        val phone = object : TurnActions {
            override val locked = false
            override val settings = RobotSettings(approvals = ToolName.entries.associateWith { Approval.AUTO })
            override suspend fun prepare(action: Action): Prepared = Prepared.Refused(ActionStatus.NOT_FOUND, "zq9marker2 isn't in your contacts.")
            override suspend fun run(action: Action): Executed = Executed(ActionStatus.DONE, "Done.")
        }
        val view = object : TurnView {
            override fun phase(p: TurnPhase) {}
            override fun card(c: Card) {}
            override var text = ""
            override fun replaceText(text: String) { this.text = text }
            override fun paragraphBreak() {}
            override fun say(sentence: String) {}
        }
        val out = SkillRun(run.skill, run.values, true, phone, view, Confirmations(), false, MessageTaint.NONE, Langs.EN, { "x" }, true, {}).run()
        assertEquals(1, out.done)
        store.editState { SkillStateFile.record(it, skill.id, SkillRunRecord(2L, out.done, out.total), setOf(skill.id)) }
        assertTrue(store.delete(skill.id))
        // Newest first: this test's entries are the first ones.
        val mine = VoiceLog.entries().let { it.take(it.size - before) }
        val voice = mine.map { VoiceLogText.describe(it) }
        assertTrue(voice.toString(), voice.any { it.startsWith("Skill") })
        assertTrue(mine.any { it.event == VoiceEvent.SKILL_STEP })
        for (l in lines + voice) assertTrue(l, !l.contains("zq9marker"))
        // Nothing in skills_state.json names it either: an id, a time and counts.
        assertTrue(!dir.listFiles()!!.filter { it.name == SkillStore.STATE }.any { it.readText().contains("zq9marker") })
    }

    /**
     * Phase B: the same marker picked by the model (skill_zq9marker), filled with the value, refused once for a changed rev,
     * its sample kept: still nothing in RLog, the voice log or skills_state.json.
     */
    @Test fun theModelsPathNeverLogsItEither() = runBlocking {
        RLog.sink = { synchronized(lines) { lines += it } }
        val before = VoiceLog.entries().size
        val skill = Skill("s-zq9mrk", 1, "zq9marker", listOf("zq9marker"), emptyList(),
            listOf(SkillParam("message", ParamType.TEXT, "zq9marker2", "What should it say?", "Ça dit quoi?")),
            listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "{{message}}"))), SkillLevel.ASK_FIRST,
            SkillProvenance(SkillMade.VOICE, false, 1L, 1L))
        assertTrue(SkillCheck.valid(skill))
        val tools = SkillSpecs.describe(SkillSelect.Index(listOf(skill)).pick("zq9marker please"), listOf(skill))
        assertEquals("skill_zq9marker", tools.single().name)
        val store = SkillStore(dir)
        assertTrue(store.save(skill))
        val menu = SkillMenu(tools) { id -> store.get(id) }
        val call = SkillCalls.parse(app.heyrobot.core.RawToolCall("skill_zq9marker", mapOf("message" to "zq9marker2")), menu) as SkillCall.Ok
        val fill = SkillFills.of(call.skill, call.values, "zq9marker zq9marker2") as SkillFill.Ok
        store.editState { SkillStats.record(it, skill, FillModel.FAST, right = true) }
        // A rev that changed since it was described: refused, and logged as a code.
        val changed = SkillMenu(tools) { skill.copy(rev = 2) }
        val r = app.heyrobot.core.TurnRunner(object : app.heyrobot.core.TurnModel {
            var n = 0
            override suspend fun ask() = app.heyrobot.core.ModelOutcome.Calls(listOf(app.heyrobot.core.RawToolCall("skill_zq9marker", fill.values)))
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>) = app.heyrobot.core.ModelOutcome.Done(app.heyrobot.core.DoneReason.END)
            override fun close() {}
        }, object : TurnActions {
            override val locked = false
            override val settings = RobotSettings()
            override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
            override suspend fun run(action: Action): Executed = Executed(ActionStatus.DONE, "Done.")
        }, object : TurnView {
            override fun phase(p: TurnPhase) {}
            override fun card(c: Card) {}
            override var text = ""
            override fun replaceText(text: String) { this.text = text }
            override fun paragraphBreak() {}
            override fun say(sentence: String) {}
        }, Confirmations(), false, langs = Langs.EN, skills = changed, newId = { "y" }).run()
        assertEquals(null, r.failure)
        val mine = VoiceLog.entries().let { it.take(it.size - before) }
        assertTrue(mine.any { it.event == VoiceEvent.SKILL_WARN })
        val voice = mine.map { VoiceLogText.describe(it) }
        for (l in lines + voice) assertTrue(l, !l.contains("zq9marker"))
        assertTrue(!dir.listFiles()!!.filter { it.name == SkillStore.STATE }.any { it.readText().contains("zq9marker") })
    }
}
