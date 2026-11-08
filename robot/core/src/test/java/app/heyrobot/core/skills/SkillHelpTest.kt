// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperFormat
import app.heyrobot.core.helpers.HelperSpend
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.messages.MessageTaint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Skills 1.3, planning and drafting with a helper (design §9, §10.1; skills-1.3-plan.md S3.9, M4 on the PC). */
class SkillHelpTest {
    private val now = ZonedDateTime.of(2026, 11, 4, 9, 30, 0, 0, ZoneOffset.UTC)
    private val today: LocalDate = now.toLocalDate()
    private val claude = HelperConfig.claude()
    private val s = RobotSettings(helpers = listOf(claude))
    private val words = "tidy up my evening: timer, then lights"
    private val r = SkillHelp.plan(words, SkillPlans.PLANNABLE.sorted(), SkillPlans.CAP, now, Lang.EN)
    private fun en(p: Phrase?) = p?.render(Langs.EN)

    private class Port(override var locked: Boolean = false, override var driving: Boolean = false, var spent: HelperSpend = HelperSpend(""),
        override val parkWaitMs: Long = SkillHelp.PARK_WAIT_MS) : HelpPort {
        val picks = ArrayDeque<suspend (Card) -> Int>()
        val cards = mutableListOf<Card>()
        val taps = mutableListOf<Phrase?>()
        val shown = mutableListOf<Card>()
        val replies = mutableListOf<Phrase>()
        val asked = mutableListOf<HelpRequest>()
        val gate = CompletableDeferred<Unit>()
        private var n = 0
        override suspend fun untilParked() { gate.await(); driving = false }
        override fun newId() = "c" + n++
        override suspend fun pick(card: Card, tapOnly: Phrase?): Int { cards += card; taps += tapOnly; return picks.removeFirst()(card) }
        override fun show(card: Card) { shown += card }
        override fun reply(p: Phrase) { replies += p }
        override fun say(p: Phrase) {}
        override fun status(p: Phrase?) {}
        override suspend fun spend() = spent
        override suspend fun ask(h: HelperConfig, r: HelpRequest): HelpReply { asked += r; return HelpReply.Text("{\"steps\": []}") }
    }

    private fun send(p: Port, settings: RobotSettings = s) = runBlocking {
        SkillHelp.send(p, claude, r, settings, Langs.EN, Langs.EN, { now.toInstant() }, { today })
    }

    @Test fun theCardShowsExactlyWhatWouldGoBeforeAnythingIsSent() {
        val p = Port()
        p.picks += { c -> assertTrue("nothing sent before the tap", p.asked.isEmpty()); assertEquals(r.text(), c.body); Confirmations.YES }
        assertEquals("{\"steps\": []}", send(p))
        val card = p.cards.single()
        assertEquals("What would go to Claude", card.summary)
        assertEquals("Send", card.confirm)
        assertTrue(card.detail!!.startsWith("Nothing goes until you tap Send."))
        // Your words this turn and the fixed instruction with the date and time; nothing else.
        assertEquals(r.system + "\n\n" + words, card.body)
        assertTrue(card.body!!.contains("Now: 2026-11-04 09:30"))
        assertEquals(words, r.words)
        assertEquals(listOf(r), p.asked)
        assertEquals(SkillStrings.helpTap("send" to "Send"), p.taps.single()) // tap only: a spoken yes is told to tap
        assertEquals(ActionStatus.DONE, p.shown.last().status)
    }

    @Test fun cancelSendsNothing() {
        val p = Port()
        p.picks += { -1 }
        assertNull(send(p))
        assertTrue(p.asked.isEmpty())
        assertEquals(ActionStatus.DECLINED, p.shown.last().status)
        assertEquals("Nothing went to Claude.", en(p.replies.single()))
    }

    @Test fun theCostIsShownAgainstTodaysBudget() {
        val spent = HelperSpend("").add(today, "claude", 0.12)
        val line = en(SkillHelp.costLine(claude, r, now.toInstant(), spent, today, 100, Lang.EN))!!
        assertTrue(line, line.endsWith("today: " + HelperFormat.dollars(120_000, Lang.EN) + " of " + HelperFormat.budget(1_000_000, Lang.EN) + " on helpers"))
        assertTrue(line, line.endsWith("no daily budget for helpers") == false)
        assertTrue(en(SkillHelp.costLine(claude, r, now.toInstant(), spent, today, -1, Lang.EN))!!.endsWith("no daily budget for helpers"))
        assertTrue(SkillHelp.estimate(claude, r, now.toInstant())!! > 0.0)
    }

    @Test fun aUsedUpBudgetSendsNothing() {
        val p = Port(spent = HelperSpend("").add(today, "claude", 1.0))
        assertNull(send(p))
        assertTrue(p.cards.isEmpty() && p.asked.isEmpty())
        assertEquals(SkillStrings.helpBudget("name" to "Claude"), p.replies.single())
    }

    @Test fun theLockAndBudgetAreCheckedAgainAfterTheTap() {
        val locked = Port()
        locked.picks += { locked.locked = true; Confirmations.YES }
        assertNull(send(locked))
        assertTrue(locked.asked.isEmpty())
        assertEquals(ActionStatus.NEEDS_UNLOCK, locked.shown.last().status)
        val spent = Port()
        spent.picks += { spent.spent = HelperSpend("").add(today, "claude", 1.0); Confirmations.YES }
        assertNull(send(spent))
        assertTrue(spent.asked.isEmpty())
        assertNull(send(Port(locked = true)))
    }

    @Test fun inCarModeItWaitsUntilParked() = runBlocking {
        val p = Port(driving = true)
        p.picks += { awaitCancellation() }
        p.picks += { c -> assertFalse(p.driving); assertEquals(r.text(), c.body); Confirmations.YES }
        launch { yield(); assertTrue("nothing before parking", p.asked.isEmpty()); p.gate.complete(Unit) }
        assertEquals("{\"steps\": []}", SkillHelp.send(p, claude, r, s, Langs.EN, Langs.EN, { now.toInstant() }, { today }))
        assertEquals(SkillStrings.helpPark("name" to "Claude"), p.taps.first())
        assertEquals(2, p.cards.size)
        assertEquals(1, p.asked.size)
    }

    /** A drive longer than the wait: the card gives up and nothing is sent, rather than come up when you park hours later. */
    @Test fun theParkingWaitEndsAndSendsNothing() = runBlocking {
        val p = Port(driving = true, parkWaitMs = 50)
        p.picks += { awaitCancellation() }
        assertNull(withTimeout(5_000) { SkillHelp.send(p, claude, r, s, Langs.EN, Langs.EN, { now.toInstant() }, { today }) })
        assertEquals(1, p.cards.size)
        assertTrue(p.asked.isEmpty())
        assertEquals(ActionStatus.DECLINED, p.shown.last().status)
        assertEquals(SkillStrings.helpNotSent("name" to "Claude").render(Langs.EN), p.shown.last().detail)
        assertEquals(SkillStrings.helpNotSent("name" to "Claude"), p.replies.last())
    }

    @Test fun cancellingWhileDrivingSendsNothing() {
        val p = Port(driving = true)
        p.picks += { -1 }
        assertNull(send(p))
        assertEquals(1, p.cards.size)
        assertTrue(p.asked.isEmpty())
        assertEquals(ActionStatus.DECLINED, p.shown.last().status)
    }

    @Test fun theHelperIsOneRobotCanAskWithBudgetLeft() {
        val none = HelperSpend("")
        assertEquals(claude, SkillHelp.helper(s, { true }, none, today))
        assertNull("no key: only the app", SkillHelp.helper(s, { false }, none, today))
        assertNull(SkillHelp.helper(s.copy(helpers = listOf(claude.copy(enabled = false))), { true }, none, today))
        assertNull(SkillHelp.helper(s, { true }, none.add(today, "claude", 1.0), today))
        assertNull(SkillHelp.helper(s.copy(helpers = listOf(claude.copy(budgetCents = 10))), { true }, none.add(today, "claude", 0.1), today))
        assertNull(SkillHelp.helper(RobotSettings(helpers = emptyList()), { true }, none, today))
    }

    @Test fun yourConnectionsGoOnlyAsTrustAllowsAndNeverAfterContent() {
        val ops = listOf(DraftOp("c_lights", "Turn on the porch light", "c-ha0001", emptyList()))
        assertTrue(SkillHelp.opsFor(s, claude, ops, quarantined = false).isEmpty())
        assertEquals(ops, SkillHelp.opsFor(s, claude.copy(trust = true), ops, quarantined = false))
        assertEquals(ops, SkillHelp.opsFor(s.copy(personalToHelpers = true), claude, ops, quarantined = false))
        assertTrue(SkillHelp.opsFor(s.copy(personalToHelpers = true), claude.copy(trust = true), ops, quarantined = true).isEmpty())
        val d = SkillHelp.draft("sets a ten minute timer", SkillPlans.PLANNABLE.sorted(), emptyList(), now, Lang.FR)
        assertFalse(d.system.contains("c_lights"))
        assertTrue(d.system.contains("The person speaks French."))
    }

    @Test fun aHelpersDraftSaysSoAndKeepsItsQuarantine() {
        val ask = SkillDrafter.Ask("sets a ten minute timer", null, Lang.EN)
        val text = "{\"name\": \"Tea\", \"steps\": [{\"use\": \"set_timer\", \"args\": {\"minutes\": 10}}]}"
        fun read(source: DraftSource?) = (SkillDrafter.read(emptyList(), text, ask, "s-abc123", 1L, SkillPlans.PLANNABLE.toSet(), emptyList(),
            emptyList(), source, MessageTaint.NONE, made = SkillMade.DRAFT_HELPER) as DraftRead.Ok).draft
        val d = read(null)
        assertEquals(SkillMade.DRAFT_HELPER, d.made)
        val lines = SkillCards.save(d, null).lines.map { en(it) }
        assertEquals("A helper drafted this: check every line.", lines.first())
        val q = SkillCards.save(read(DraftSource.MESSAGE), null).lines.map { en(it) }
        assertTrue(q.toString(), q.contains(en(SkillStrings.draftFromMessage())))
    }

    @Test fun whenAHelperIsOffered() {
        assertFalse(SkillHelp.offersPlan(SkillTier.SIMPLE))
        assertTrue(SkillHelp.offersPlan(SkillTier.CAREFUL) && SkillHelp.offersPlan(SkillTier.RISKY))
        assertFalse(SkillHelp.offersAfter(PlanRefusal.APP_NOT_LAST))
        assertFalse(SkillHelp.offersAfter(PlanRefusal.TOO_MANY))
        assertTrue(PlanRefusal.entries.filter { SkillHelp.offersAfter(it) }.isNotEmpty())
    }
}
