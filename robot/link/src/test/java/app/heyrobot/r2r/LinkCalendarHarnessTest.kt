// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskState
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPlan
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PlanState
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.EvCancel
import app.heyrobot.core.link.requests.EvChange
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.Span
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * robot-to-robot-plan.md §4.1, §4.2, D18, RR4, RR5 (job L7): free/busy and plans between two Robots in one JVM, each over a
 * calendar of its own (a fake one that holds only times: the interface has no title to give). Free/busy answers carry busy
 * blocks on 30-minute steps only, and an answer is kept only when it answers something you asked. A plan accepted on its own
 * stays inside the working hours, when free, 3 a day from one person; Undo takes it off and tells them; a cancel on either side
 * takes it off both calendars; another Robot never reaches a plan you have with someone else.
 */
class LinkCalendarHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L // 2026-09-21 21:33 UTC
    private val robots = ArrayList<TestRobot>()
    private val zone: ZoneId = ZoneOffset.UTC

    @After fun tearDown() { for (r in robots) r.node?.close() }

    /** A calendar of times only: busy spans set by the test, plus the events Robot added. */
    class FakeCalendar(private val zone: ZoneId) : DeskCalendar {
        class Event(val title: String, val place: String?, var start: Long, var minutes: Long)
        val busy = java.util.concurrent.CopyOnWriteArrayList<Span>()
        val events = java.util.concurrent.ConcurrentHashMap<Long, Event>()
        @Volatile var readable = true
        private var next = 100L
        override fun zone() = zone
        val asked = java.util.concurrent.CopyOnWriteArrayList<Span>()
        override fun busy(from: Long, to: Long): List<Span>? {
            asked += Span(from, to)
            return if (!readable) null else (busy + events.values.map { Span(it.start, it.start + it.minutes * 60) }).filter { it.end > from && it.start < to }
        }
        @Synchronized override fun add(title: String, place: String?, start: Long, minutes: Long): Long? { val id = next++; events[id] = Event(title, place, start, minutes); return id }
        override fun move(id: Long, start: Long, minutes: Long): Boolean = events[id]?.let { it.start = start; it.minutes = minutes; true } ?: false
        override fun remove(id: Long): Boolean = events.remove(id) != null
    }

    private class Bot(val r: TestRobot, val env: TestDeskEnv, val cal: FakeCalendar)

    private fun robot(name: String, push: FakePushServer): Bot {
        val env = TestDeskEnv()
        val cal = FakeCalendar(zone)
        val r = TestRobot(tmp.newFolder(), FakeEnv(t0), null, 0, null, push, name, deskEnv = env,
            more = { st -> listOf(FreeBusyHandler(st, cal), EventsHandler(st, cal) { env.settings }) })
        robots += r; r.n().start(); r.n().wake()
        return Bot(r, env, cal)
    }

    private fun until(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun idle(vararg bs: Bot) {
        repeat(3) { for (b in bs) { b.r.n().pump().get(10, TimeUnit.SECONDS); b.r.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun active(r: TestRobot, not: Set<String> = emptySet()): String {
        until("an active pairing") { r.n().pairings().get().any { it.value == PairingState.ACTIVE && it.key !in not } }
        return r.n().pairings().get().entries.first { it.value == PairingState.ACTIVE && it.key !in not }.key
    }

    private fun pair(a: Bot, b: Bot): Pair<String, String> {
        val beforeA = a.r.n().pairings().get().keys; val beforeB = b.r.n().pairings().get().keys
        val code = a.r.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.r.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        return active(a.r, beforeA) to active(b.r, beforeB)
    }

    private fun send(from: Bot, pairing: String, body: LinkBody): LinkRequest {
        val r = LinkRequest.of(body, from.r.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.r.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
        return r
    }

    private fun <T> on(b: Bot, block: (LinkDesk) -> T): T = b.r.n().onLinkThread { block(b.r.desk!!) }.get(10, TimeUnit.SECONDS)

    private fun sent(b: Bot, id: String): DeskState? = on(b) { it.view()!!.lines.firstOrNull { l -> l.entry.id == id && l.entry.way == DeskWay.OUT }?.entry?.state }

    private fun level(b: Bot, pairing: String, cap: LinkCapability, level: LinkLevel) {
        if (level == LinkLevel.AUTO) assertTrue(b.r.n().setLevel(pairing, cap, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        assertTrue(b.r.n().setLevel(pairing, cap, level).get(10, TimeUnit.SECONDS))
    }

    private fun plan(b: Bot, pairing: String, id: String): LinkPlan? = on(b) { it.plans().firstOrNull { p -> p.pairing == pairing && p.id == id } }

    private fun replies(b: Bot) = on(b) { it.view()!!.lines.filter { l -> l.entry.way == DeskWay.REPLY } }

    /** Tomorrow (22 Sep 2026, UTC) at [h]:[m]. */
    private fun at(h: Int, m: Int = 0, days: Long = 1): Long = ZonedDateTime.of(2026, 9, 21, h, m, 0, 0, zone).plusDays(days).toEpochSecond()

    @Test fun freeBusyOnItsOwnSendsBusyBlocksOnlyAndTheAnswerIsKeptForYou() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.FREE_BUSY, LinkLevel.AUTO)
        // Sam's evening: 18:10 to 18:40 and 20:00 to 21:00 busy; something outside the window too.
        sam.cal.busy += Span(at(18, 10), at(18, 40)); sam.cal.busy += Span(at(20), at(21)); sam.cal.busy += Span(at(9), at(10))
        val ask = send(matt, samOnMatt, FbAsk(at(17), at(23)))
        until("Matt's Sent says Answered") { sent(matt, ask.id) == DeskState.ANSWERED_BY_THEM }
        until("Matt keeps Sam's answer") { replies(matt).isNotEmpty() }
        val line = replies(matt).single()
        assertEquals(DeskState.REPLIED, line.entry.state)
        assertFalse(line.entry.seen)
        val reply = line.request!!.body as FbReply
        assertEquals(ask.id, reply.re)
        // Widened to 30-minute steps from the window's start, never outside it, never a title (FbReply has no field for one).
        assertEquals(listOf(Span(at(18), at(19)), Span(at(20), at(21))), reply.busy)
        assertTrue(on(matt) { it.notices() }.notices.any { it.kind == DeskNoticeKind.REPLIED && it.pairing == samOnMatt })
        // Said once: marked seen.
        assertTrue(on(matt) { it.seen(line.entry.id) })
        assertTrue(replies(matt).single().entry.seen)
        // On Sam's side: handled on its own, no Undo (an answer sent can't be taken back).
        val handled = on(sam) { it.view()!!.lines.single { l -> l.entry.id == ask.id } }
        assertEquals(DeskState.HANDLED, handled.entry.state)
        assertNull(handled.entry.undo)
    }

    @Test fun freeBusyAtAskMeFirstIsACardWhoseYesSendsTheBlocks() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.FREE_BUSY, LinkLevel.ASK)
        sam.cal.busy += Span(at(12), at(13))
        val ask = send(matt, samOnMatt, FbAsk(at(9), at(17)))
        until("a card on Sam's side") { sam.r.box.count() == 1 }
        val card = on(sam) { it.view()!!.cards.single() }
        assertEquals(DeskYes.SEND_BUSY, card.yes)
        // Without calendar access the yes can't be done: the card stays.
        sam.cal.readable = false
        assertFalse(on(sam) { it.answer(card.id, DeskChoice.YES) })
        sam.cal.readable = true
        assertTrue(on(sam) { it.answer(card.id, DeskChoice.YES) })
        until("Matt keeps Sam's answer") { replies(matt).isNotEmpty() }
        assertEquals(listOf(Span(at(12), at(13))), (replies(matt).single().request!!.body as FbReply).busy)
        assertEquals(DeskState.ANSWERED_BY_THEM, sent(matt, ask.id))
    }

    @Test fun anAnswerToNothingYouAskedIsNeverKept() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        // Sam's Robot "answers" an ask Matt never sent (and one that names Sam's own request id).
        send(sam, mattOnSam, FbReply(app.heyrobot.core.link.LinkIds.newId(), at(9), at(10), emptyList()))
        idle(matt, sam)
        assertTrue(replies(matt).isEmpty())
        assertTrue(on(matt) { it.notices() }.notices.none { it.kind == DeskNoticeKind.REPLIED })
        assertNotNull(samOnMatt)
    }

    @Test fun aPlanOnItsOwnStaysInsideTheLimitsAndUndoTakesItOffBothCalendars() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.AUTO)
        sam.cal.busy += Span(at(18, 30), at(19, 30))
        // 22:00 is outside Sam's working hours (9:00 to 21:00), 19:00 is busy, 20:00 is free: 20:00 is taken.
        val id = on(matt) { it.propose(samOnMatt, EventFields("Dinner", listOf(at(19), at(20), at(22)), 60, "Joe's")) }!!
        assertEquals(PlanState.PROPOSED, plan(matt, samOnMatt, id)!!.state)
        until("Matt's plan accepted") { plan(matt, samOnMatt, id)?.state == PlanState.ACCEPTED }
        assertEquals(at(20), plan(matt, samOnMatt, id)!!.time)
        // Both calendars hold it, at 20:00, with Matt's title and place.
        val samEvent = sam.cal.events.values.single()
        assertEquals("Dinner", samEvent.title); assertEquals("Joe's", samEvent.place); assertEquals(at(20), samEvent.start); assertEquals(60L, samEvent.minutes)
        until("in Matt's calendar") { matt.cal.events.size == 1 }
        assertEquals(at(20), matt.cal.events.values.single().start)
        assertEquals(DeskState.REPLIED, replies(matt).single().entry.state)
        // Sam's side lists it as handled, with Undo: off Sam's calendar, cancelled, and Matt's Robot told.
        val handled = on(sam) { it.view()!!.lines.single { l -> l.entry.way == DeskWay.IN && l.entry.state == DeskState.HANDLED } }
        assertNotNull(handled.entry.undo)
        assertTrue(on(sam) { it.undo(handled.entry.id) })
        assertTrue(sam.cal.events.isEmpty())
        assertEquals(PlanState.CANCELLED, plan(sam, mattOnSam, id)!!.state)
        until("off Matt's calendar too") { matt.cal.events.isEmpty() }
        assertEquals(PlanState.CANCELLED, plan(matt, samOnMatt, id)!!.state)
        assertEquals(2, replies(matt).size)
    }

    @Test fun onItsOwnAtMostThreeADayAndNeverOutsideTheHoursOrWhenBusy() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.AUTO)
        val ids = (0 until 3).map { i -> on(matt) { it.propose(samOnMatt, EventFields("Plan $i", listOf(at(10 + 2 * i)), 30)) }!! }
        until("three accepted on their own") { ids.all { plan(sam, mattOnSam, it)?.state == PlanState.ACCEPTED } }
        assertEquals(3, sam.cal.events.size)
        // The fourth from Matt the same day is a card, never on its own.
        on(matt) { it.propose(samOnMatt, EventFields("Plan 3", listOf(at(17)), 30)) }!!
        until("a card") { sam.r.box.count() == 1 }
        assertEquals(DeskYes.ADD_EVENT, on(sam) { it.view()!!.cards.single().yes })
        // A plan only outside the hours, or only when busy: a card too.
        sam.env.settings = sam.env.settings.copy(hours = app.heyrobot.core.link.EventAlone.Hours(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(21, 0)))
        sam.r.env.clock.addAndGet(86_400_000L)
        on(matt) { it.propose(samOnMatt, EventFields("Late", listOf(at(22, days = 2)), 30)) }!!
        until("a second card") { sam.r.box.count() == 2 }
        assertEquals(3, sam.cal.events.size)
    }

    @Test fun aCardsYesTakesTheTimeYouPickAndDeclineTellsThem() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.ASK)
        val a = on(matt) { it.propose(samOnMatt, EventFields("Lunch", listOf(at(12), at(13)), 45)) }!!
        until("a card") { sam.r.box.count() == 1 }
        val card = on(sam) { it.view()!!.cards.single() }
        assertTrue(on(sam) { it.answer(card.id, DeskChoice.SECOND) })
        assertEquals(at(13), sam.cal.events.values.single().start)
        until("Matt's plan accepted at 13:00") { plan(matt, samOnMatt, a)?.time == at(13) }
        assertEquals(PlanState.ACCEPTED, plan(matt, samOnMatt, a)!!.state)
        // A third time that isn't there: not answered, the card would stay (here it's gone already).
        val b = on(matt) { it.propose(samOnMatt, EventFields("Coffee", listOf(at(15)), 30)) }!!
        until("a second card") { sam.r.box.count() == 1 }
        val c2 = on(sam) { it.view()!!.cards.single() }
        assertFalse(on(sam) { it.answer(c2.id, DeskChoice.THIRD) })
        assertTrue(on(sam) { it.answer(c2.id, DeskChoice.DECLINE) })
        until("Matt's plan declined") { plan(matt, samOnMatt, b)?.state == PlanState.DECLINED }
        assertEquals(1, sam.cal.events.size)
        assertEquals(PlanState.DECLINED, plan(sam, mattOnSam, b)!!.state)
    }

    @Test fun aCancelOnOneSideTakesItOffTheOtherAndAWaitingCardGoes() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.ASK)
        val a = on(matt) { it.propose(samOnMatt, EventFields("Dinner", listOf(at(19)), 90)) }!!
        until("a card") { sam.r.box.count() == 1 }
        assertTrue(on(sam) { it.answer(it.view()!!.cards.single().id, DeskChoice.YES) })
        until("accepted") { plan(matt, samOnMatt, a)?.state == PlanState.ACCEPTED }
        until("in Matt's calendar") { matt.cal.events.size == 1 }
        // Matt cancels: off both calendars, and Sam keeps a line saying so.
        assertTrue(on(matt) { it.cancelPlan(samOnMatt, a) })
        assertTrue(matt.cal.events.isEmpty())
        until("off Sam's calendar") { sam.cal.events.isEmpty() }
        assertEquals(PlanState.CANCELLED, plan(sam, mattOnSam, a)!!.state)
        assertTrue(replies(sam).any { it.request?.body is EvCancel })
        // A plan cancelled while its card waits: the card goes (nothing left to answer).
        val b = on(matt) { it.propose(samOnMatt, EventFields("Movie", listOf(at(21)), 120)) }!!
        until("a card") { sam.r.box.count() == 1 }
        assertTrue(on(matt) { it.cancelPlan(samOnMatt, b) })
        until("Sam knows it's cancelled") { plan(sam, mattOnSam, b)?.state == PlanState.CANCELLED }
        val v = on(sam) { it.view()!! }
        assertTrue(v.cards.isEmpty())
        assertEquals(0, sam.r.box.count())
        assertTrue(sam.cal.events.isEmpty())
    }

    @Test fun aYesNeverTakesATimeThatHasPassed() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.ASK)
        send(matt, samOnMatt, EvPropose("a".repeat(32), 1, EventFields("Lunch", listOf(at(9, days = 0), at(23, days = 0)), 30)))
        until("a card") { sam.r.box.count() == 1 }
        val card = on(sam) { it.view()!!.cards.single() }
        assertFalse("the time that has passed, picked", on(sam) { it.answer(card.id, DeskChoice.FIRST) })
        assertTrue(sam.cal.events.isEmpty())
        assertTrue(on(sam) { it.answer(card.id, DeskChoice.YES) })
        assertEquals("the one still to come", at(23, days = 0), sam.cal.events.values.single().start)
    }

    @Test fun aPlanWhoseTimesHaveAllPassedGoesAsExpired() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.ASK)
        val r = send(matt, samOnMatt, EvPropose("b".repeat(32), 1, EventFields("Breakfast", listOf(at(9, days = 0), at(10, days = 0)), 30)))
        until("listed as expired") { on(sam) { it.view()!!.lines.any { l -> l.entry.id == r.id && l.entry.state == DeskState.EXPIRED } } }
        assertTrue(on(sam) { it.view()!!.cards.isEmpty() })
        assertTrue(sam.cal.events.isEmpty())
    }

    @Test fun freeBusyOnItsOwnLooksBackAnHourAtMost() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.FREE_BUSY, LinkLevel.AUTO)
        val near = send(matt, samOnMatt, FbAsk(at(21, days = 0), at(23, days = 0))) // 33 minutes ago: a clock that's off, or a slow road.
        until("answered on its own") { on(matt) { it.view()!!.lines.any { l -> l.entry.way == DeskWay.REPLY && (l.request?.body as? FbReply)?.re == near.id } } }
        assertTrue(on(sam) { it.view()!!.cards.isEmpty() })
        val past = send(matt, samOnMatt, FbAsk(at(9, days = -30), at(9, days = -29)))
        until("a card for the look back") { on(sam) { it.view()!!.cards.any { c -> c.request.id == past.id } } }
        assertEquals("only the first was answered", 1, on(matt) { it.view()!!.lines.count { l -> l.entry.way == DeskWay.REPLY } })
    }

    @Test fun theirChangeIsAlwaysACardEvenOnItsOwn() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.AUTO)
        val a = on(matt) { it.propose(samOnMatt, EventFields("Dinner", listOf(at(18)), 60)) }!!
        until("accepted on its own") { plan(matt, samOnMatt, a)?.state == PlanState.ACCEPTED }
        send(matt, samOnMatt, EvChange(a, 3, EventFields("Bank visit", listOf(at(19)), 60)))
        until("a change card") { on(sam) { it.view()!!.cards.size == 1 } }
        assertEquals("not moved", at(18), sam.cal.events.values.single().start)
        assertEquals("not retitled", "Dinner", sam.cal.events.values.single().title)
        assertTrue(on(sam) { it.answer(it.view()!!.cards.single().id, DeskChoice.YES) })
        assertEquals(at(19), sam.cal.events.values.single().start)
    }

    @Test fun timesFarApartAskTheCalendarOnlyAboutThemselves() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.ASK)
        sam.cal.busy += Span(at(12), at(13))
        send(matt, samOnMatt, EvPropose("c".repeat(32), 1, EventFields("Lunch", listOf(at(12), at(12, days = 20_000)), 30)))
        until("a card") { sam.r.box.count() == 1 }
        sam.cal.asked.clear()
        assertTrue(on(sam) { it.answer(it.view()!!.cards.single().id, DeskChoice.YES) })
        assertEquals("the first time is busy", at(12, days = 20_000), sam.cal.events.values.single().start)
        assertEquals(listOf(Span(at(12), at(12, 30)), Span(at(12, days = 20_000), at(12, 30, days = 20_000))), sam.cal.asked.toList())
    }

    @Test fun theirChangeMovesThePlanYouHaveAndAnOlderOneIsIgnored() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.ASK)
        val a = on(matt) { it.propose(samOnMatt, EventFields("Dinner", listOf(at(19)), 60)) }!!
        until("a card") { sam.r.box.count() == 1 }
        assertTrue(on(sam) { it.answer(it.view()!!.cards.single().id, DeskChoice.YES) })
        until("accepted") { plan(matt, samOnMatt, a)?.state == PlanState.ACCEPTED }
        send(matt, samOnMatt, EvChange(a, 3, EventFields("Dinner", listOf(at(20)), 60)))
        until("a change card") { sam.r.box.count() == 1 }
        val card = on(sam) { it.view()!!.cards.single() }
        assertEquals(DeskYes.MOVE_EVENT, card.yes)
        assertTrue(on(sam) { it.answer(card.id, DeskChoice.YES) })
        assertEquals(at(20), sam.cal.events.values.single().start)
        assertEquals(3L, plan(sam, mattOnSam, a)!!.rev)
        // An older change arriving late (rev 2) is stale: its card goes.
        send(matt, samOnMatt, EvChange(a, 2, EventFields("Dinner", listOf(at(18)), 60)))
        idle(matt, sam)
        assertTrue(on(sam) { it.view()!!.cards.isEmpty() })
        assertEquals(at(20), sam.cal.events.values.single().start)
    }

    /** L16, finding 11: a change that retitles a plan retitles your calendar too, so the two never disagree. */
    @Test fun aChangedTitleOrPlaceReachesYourCalendar() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.ASK)
        val a = on(matt) { it.propose(samOnMatt, EventFields("Dinner", listOf(at(19)), 60)) }!!
        until("a card") { sam.r.box.count() == 1 }
        assertTrue(on(sam) { it.answer(it.view()!!.cards.single().id, DeskChoice.YES) })
        until("accepted") { plan(matt, samOnMatt, a)?.state == PlanState.ACCEPTED }
        send(matt, samOnMatt, EvChange(a, 3, EventFields("Bank visit", listOf(at(20)), 60, "Main St")))
        until("a change card") { sam.r.box.count() == 1 }
        assertTrue(on(sam) { it.answer(it.view()!!.cards.single().id, DeskChoice.YES) })
        val e = sam.cal.events.values.single()
        assertEquals("Bank visit", e.title)
        assertEquals("Main St", e.place)
        assertEquals(at(20), e.start)
        assertEquals("Bank visit", plan(sam, mattOnSam, a)!!.event.title)
    }

    @Test fun anotherRobotNeverReachesAPlanYouHaveWithSomeoneElse() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push); val mom = robot("Mom", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        val (momOnMatt, mattOnMom) = pair(matt, mom)
        idle(matt, sam, mom)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.AUTO)
        val a = on(matt) { it.propose(samOnMatt, EventFields("Dinner", listOf(at(19)), 60)) }!!
        until("accepted") { plan(matt, samOnMatt, a)?.state == PlanState.ACCEPTED }
        until("in Matt's calendar") { matt.cal.events.size == 1 }
        // Mom's Robot names the same plan id: Matt's plan with Sam, and his calendar, stay as they are.
        send(mom, mattOnMom, EvCancel(a, 9))
        idle(matt, sam, mom)
        assertEquals(PlanState.ACCEPTED, plan(matt, samOnMatt, a)!!.state)
        assertEquals(1, matt.cal.events.size)
        assertNull(plan(matt, momOnMatt, a)?.takeIf { it.state != PlanState.CANCELLED })
    }

    @Test fun plansSurviveAReadOfTheStateAndKeepTheirCalendarEvent() {
        val push = FakePushServer()
        val matt = robot("Matt", push); val sam = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(matt, sam)
        idle(matt, sam)
        level(sam, mattOnSam, LinkCapability.EVENTS, LinkLevel.AUTO)
        val a = on(matt) { it.propose(samOnMatt, EventFields("Dinner", listOf(at(19)), 60)) }!!
        until("accepted") { plan(sam, mattOnSam, a)?.state == PlanState.ACCEPTED }
        // The records as :link reads them at its next start.
        val again = LinkStateStore(sam.r.records).also { it.load() }
        val p = again.plans.values.single()
        assertEquals(a, p.id); assertEquals(mattOnSam, p.pairing); assertEquals(sam.cal.events.keys.single(), p.cal); assertTrue(p.aloneAt > 0)
    }
}
