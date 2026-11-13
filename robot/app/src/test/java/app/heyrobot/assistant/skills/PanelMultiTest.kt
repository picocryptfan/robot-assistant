// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Executed
import app.heyrobot.core.OneShotTransport
import app.heyrobot.core.Prepared
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnView
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.skills.DraftSource
import app.heyrobot.core.skills.MultiAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** PanelMulti: each request in one sentence goes through its own Settings › Actions level, the lock and its own card. */
class PanelMultiTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))

    private class Actions(override val settings: RobotSettings, override val locked: Boolean, val why: Map<ToolName, Set<AskWhy>>) : TurnActions {
        val ran = mutableListOf<ToolName>()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action, why = why[action.tool] ?: emptySet())
        override suspend fun run(action: Action): Executed { ran += action.tool; return Executed(ActionStatus.DONE, "done") }
    }

    /** The step cards shown in the panel; each PENDING one is answered [stepYes]. */
    private class View(val scope: CoroutineScope, val conf: Confirmations, val stepYes: Boolean) : TurnView {
        val asked = mutableListOf<Card>()
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) {
            if (c.status != ActionStatus.PENDING) return
            asked += c
            scope.launch { var n = 0; while (!conf.answer(c.id, stepYes) && n++ < 1000) yield() }
        }
        override var text = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private class Host(override val locked: Boolean, val multiPick: Int, val p: PanelSkills.SkillPorts) : PanelSkills.Host {
        val multiCards = mutableListOf<Card>()
        val lines = mutableListOf<String>()
        override val speak = false
        override fun text(replyId: String, text: String) {}
        override fun append(replyId: String, text: String) { lines += text }
        override fun say(sentence: String, personal: Boolean) {}
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean = multiPick == Confirmations.YES
        override suspend fun pick(replyId: String, card: Card, tapOnly: String?): Int { multiCards += card; return multiPick }
        override fun show(replyId: String, card: Card) {}
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) {}
        override fun quoted(replyId: String) {}
        override suspend fun leave() {}
        override fun ports(replyId: String) = p
        override val untrusted = false
        override val phoneLock = false
        override val messages = MessageTaint.NONE
        override val oneShots: OneShotTransport get() = error("no model in this test")
        override val source: DraftSource? = null
    }

    private class Run(val ran: List<ToolName>, val asked: List<Card>, val multiCards: List<Card>, val lines: List<String>)

    private fun run(sentence: String, levels: Map<ToolName, Approval>, locked: Boolean = false, hostLocked: Boolean = false,
        multiPick: Int = Confirmations.YES, stepYes: Boolean = true, why: Map<ToolName, Set<AskWhy>> = emptyMap()): Run = runBlocking {
        val parts = MultiAsk.split(sentence, now)!!
        assertEquals(2, parts.size)
        val settings = RobotSettings().let { it.copy(approvals = it.approvals + levels) }
        val actions = Actions(settings, locked, why)
        val conf = Confirmations()
        val view = View(this, conf, stepYes)
        var n = 0
        val host = Host(hostLocked, multiPick, PanelSkills.SkillPorts(actions, view, conf, MessageTaint.NONE, Langs.EN) { "c" + n++ })
        PanelMulti.handle("r", parts, host, Langs.EN)
        Run(actions.ran, view.asked, host.multiCards, host.lines)
    }

    private val twoActions = "Add milk to the shopping list and set a 20-minute timer"
    private fun levels(list: Approval, timer: Approval) = mapOf(ToolName.ADD_TO_LIST to list, ToolName.SET_TIMER to timer)

    @Test fun justDoItRunsBothAfterTheOneYes() {
        val r = run(twoActions, levels(Approval.AUTO, Approval.AUTO))
        assertEquals(1, r.multiCards.size)
        assertEquals(listOf<Card>(), r.asked)
        assertEquals(setOf(ToolName.ADD_TO_LIST, ToolName.SET_TIMER), r.ran.toSet())
    }

    @Test fun askFirstShowsThatStepsOwnCard() {
        val r = run(twoActions, levels(Approval.AUTO, Approval.ASK))
        assertEquals(listOf(ToolName.SET_TIMER), r.asked.map { it.tool })
        assertEquals(setOf(ToolName.ADD_TO_LIST, ToolName.SET_TIMER), r.ran.toSet())
    }

    @Test fun aNoOnAnAskFirstCardDropsOnlyThatStep() {
        val r = run(twoActions, levels(Approval.ASK, Approval.AUTO), stepYes = false)
        assertEquals(listOf(ToolName.ADD_TO_LIST), r.asked.map { it.tool })
        assertEquals(listOf(ToolName.SET_TIMER), r.ran)
    }

    @Test fun offRunsNothingForThatStepAndTheOtherStillRuns() {
        val r = run(twoActions, levels(Approval.AUTO, Approval.OFF))
        assertEquals(listOf<Card>(), r.asked)
        assertEquals(listOf(ToolName.ADD_TO_LIST), r.ran)
    }

    @Test fun lockedAfterTheYesRunsOnlyWhatWorksLockedAlone() {
        // Timers work on the lock screen by default; a list never does.
        val r = run(twoActions, levels(Approval.AUTO, Approval.AUTO), locked = true)
        assertEquals(listOf(ToolName.SET_TIMER), r.ran)
    }

    @Test fun lockedPanelShowsNoCardAndRunsNothing() {
        val r = run(twoActions, levels(Approval.AUTO, Approval.AUTO), locked = true, hostLocked = true)
        assertEquals(0, r.multiCards.size)
        assertEquals(listOf<ToolName>(), r.ran)
        assertEquals(1, r.lines.size)
    }

    @Test fun cancelRunsNothing() {
        val r = run(twoActions, levels(Approval.AUTO, Approval.AUTO), multiPick = Confirmations.NO)
        assertEquals(listOf<Card>(), r.asked)
        assertEquals(listOf<ToolName>(), r.ran)
    }

    @Test fun aReminderReasonStillShowsItsCard() {
        val r = run("Set a 20-minute timer and remind me at 6 to call Mom", mapOf(ToolName.SET_TIMER to Approval.AUTO, ToolName.ADD_EVENT to Approval.AUTO),
            why = mapOf(ToolName.ADD_EVENT to setOf(AskWhy.REMINDER)))
        assertEquals(listOf(ToolName.ADD_EVENT), r.asked.map { it.tool })
        assertTrue(ToolName.SET_TIMER in r.ran && ToolName.ADD_EVENT in r.ran)
    }
}
