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
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.LinkEvent
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkCaps
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RemOffer
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.requests.SettleHow
import app.heyrobot.core.link.requests.SplitItem
import app.heyrobot.core.link.requests.SpReply
import app.heyrobot.core.link.requests.SpRequest
import app.heyrobot.core.link.requests.SpSettle
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * robot-to-robot-plan.md §4.2, §4.4, §6.4 (job L6): two Robots in one JVM (the harness of L3, over a fake push server), each
 * with a real desk as its node's sink. What Matt's Robot sends becomes, on Sam's: a card at Ask me first (acked `waiting`,
 * sealed in the inbox, shown only unlocked; Yes and Decline answered back), declined at Off, answered on its own with its
 * handler (and Undo, where it can be undone), a card when no handler exists yet, waiting while locked when Q3 says so and
 * answered at the unlock; "Robot handled" notices; Matt's Sent list following each ack; Robot's proposal after three yeses.
 */
class LinkDeskHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()

    @After fun tearDown() { for (r in robots) r.node?.close() }

    /** A reminder handler with Undo, standing in for L10's. */
    private class Reminders : DeskHandler {
        val undone = CopyOnWriteArrayList<String>()
        override val types = setOf(RequestType.REM_OFFER)
        override fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut) = DeskDone(undo = "rem:${r.id.take(8)}")
        override fun undo(pairing: String, token: String, out: DeskOut): Boolean { undone += token; return true }
    }

    private fun robot(name: String, push: FakePushServer, env: TestDeskEnv, handlers: List<DeskHandler> = listOf(EtaHandler)): TestRobot =
        TestRobot(tmp.newFolder(), FakeEnv(t0), null, 0, null, push, name, deskEnv = env, handlers = handlers).also { robots += it; it.n().start(); it.n().wake() }

    private fun until(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun idle(vararg rs: TestRobot) {
        repeat(3) { for (r in rs) { r.n().pump().get(10, TimeUnit.SECONDS); r.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun active(r: TestRobot): String {
        until("an active pairing") { r.n().pairings().get().values.any { it == PairingState.ACTIVE } }
        return r.n().pairings().get().entries.first { it.value == PairingState.ACTIVE }.key
    }

    private fun pair(a: TestRobot, b: TestRobot): Pair<String, String> {
        val code = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        return active(a) to active(b)
    }

    private fun send(from: TestRobot, pairing: String, body: LinkBody): LinkRequest {
        val r = LinkRequest.of(body, from.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
        return r
    }

    private fun <T> on(r: TestRobot, block: (LinkDesk) -> T): T = r.n().onLinkThread { block(r.desk!!) }.get(10, TimeUnit.SECONDS)

    private fun sent(r: TestRobot, id: String): DeskState? = on(r) { it.view()!!.lines.firstOrNull { l -> l.entry.id == id && l.entry.way == DeskWay.OUT }?.entry?.state }

    private fun level(r: TestRobot, pairing: String, cap: LinkCapability, level: LinkLevel) =
        assertTrue(r.n().setLevel(pairing, cap, level).get(10, TimeUnit.SECONDS))

    /** L16: splits wait for L9. A peer that sends them anyway (a later build, or one that ignores `caps`) gets `unsupported`, no card, no entry. */
    @Test fun splitsAreAnsweredUnsupported_noCard() {
        val push = FakePushServer()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, TestDeskEnv())
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        // Not a row, so not a level to set.
        assertFalse(b.n().setLevel(mattOnSam, LinkCapability.SPLITS, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        // Neither side says it reads them.
        for (t in listOf(RequestType.SP_REQUEST, RequestType.SP_REPLY, RequestType.SP_SETTLE)) {
            assertTrue(t !in LinkCaps.fromBitmap(LinkCaps.bitmap(LinkCaps.SUPPORTED)))
        }
        val request = LinkRequest.of(SpRequest(LinkIds.newId(), 1, "CAD", 10_000, 3_450, listOf(SplitItem("Salad", 1_500, 1_500), SplitItem("Wine", 3_000, 1_000)), 450, 500, 3), a.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertEquals(QueueProblem.NOT_READ, a.n().queue(samOnMatt, RequestType.SP_REQUEST.code, request.id, request.at, request.ttl, LinkRequests.encode(request)!!).get(10, TimeUnit.SECONDS))
        // A Robot that sends them anyway.
        a.n().onLinkThread { a.state.pairings[samOnMatt]!!.caps = LinkCaps.bitmap(RequestType.entries.toSet()) }.get(10, TimeUnit.SECONDS)
        val all = listOf(send(a, samOnMatt, request.body), send(a, samOnMatt, SpReply(LinkIds.newId(), 1, true)), send(a, samOnMatt, SpSettle(LinkIds.newId(), 2, SettleHow.CASH, 3_450, "CAD")))
        for (r in all) until("Matt's Sent says Sam's Robot can't read it") { sent(a, r.id) == DeskState.COULDNT_REACH }
        idle(a, b)
        assertEquals(0, b.box.count())
        assertTrue(on(b) { d -> d.view()!!.cards.isEmpty() && d.view()!!.lines.none { it.entry.way == DeskWay.IN } })
    }

    /** L16, finding 5: answers to requests that are refused or repeated have a budget of their own, so a peer can't fill your outbox with them. */
    @Test fun aPeerCantFillYourOutboxWithRefusals() {
        val push = FakePushServer()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, TestDeskEnv())
        val (samOnMatt, _) = pair(a, b)
        idle(a, b)
        a.n().onLinkThread { a.state.pairings[samOnMatt]!!.caps = LinkCaps.bitmap(RequestType.entries.toSet()) }.get(10, TimeUnit.SECONDS)
        // Sam's Robot can't send: its answers stay queued.
        b.env.network.set(false)
        val before = b.n().status().get(10, TimeUnit.SECONDS).outbox
        repeat(LinkLimits.REFUSALS_QUEUED + 15) { send(a, samOnMatt, SpReply(LinkIds.newId(), 1, true)) }
        until("every request read") { b.log.snapshot().count { it.event == LinkEvent.REQUEST_REFUSED } >= LinkLimits.REFUSALS_QUEUED + 15 }
        idle(b)
        assertEquals(before + LinkLimits.REFUSALS_QUEUED, b.n().status().get(10, TimeUnit.SECONDS).outbox)
    }

    /** L16, finding 12: a crash after a card was saved but before the replay window was brings the same request again: one card. */
    @Test fun theSameRequestTwiceIsOneCard() {
        val push = FakePushServer()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, TestDeskEnv(), handlers = listOf(EtaHandler, Reminders()))
        val (_, mattOnSam) = pair(a, b)
        idle(a, b)
        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.ASK)
        val r = LinkRequest.of(EtaTell(10, "Atwater"), System.currentTimeMillis() / 1000, "en", 3600) // l10n: not text
        on(b) { it.delivered(mattOnSam, r); it.delivered(mattOnSam, r); Unit }
        assertEquals(1, on(b) { it.view()!!.cards.size })
        assertEquals(1, b.box.count())
    }

    /** L16, finding 8: an `unpair` isn't sent twice, so a parser that can't be reached for a moment mustn't lose it. */
    @Test fun anUnpairReadWhileTheParserIsAwayIsReadWhenItIsBack() {
        val push = FakePushServer()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, TestDeskEnv())
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        b.parseAway.set(1)
        assertTrue(a.n().unpair(samOnMatt).get(10, TimeUnit.SECONDS))
        until("the parser was asked and away") { b.parseAway.get() == 0 }
        until("Sam's Robot read the unpair on a later try") { b.n().pairings().get(10, TimeUnit.SECONDS)[mattOnSam] == PairingState.ENDED }
    }

    private fun waitingCard(): Pair<TestRobot, String> {
        val push = FakePushServer()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, TestDeskEnv(), handlers = listOf(EtaHandler, Reminders()))
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.ASK)
        send(a, samOnMatt, EtaTell(10, "Atwater"))
        until("a card waits") { b.box.count() == 1 }
        return b to mattOnSam
    }

    /** F3: the sender dropped its copy when told it waits here, so a reader that couldn't start costs nothing. */
    @Test fun aReaderThatIsAwayKeepsTheWaitingCard() {
        val (b, _) = waitingCard()
        b.parseAway.set(1)
        assertTrue(on(b) { it.view()!!.cards.isEmpty() })
        assertEquals(1, b.box.count())
        assertEquals(1, on(b) { it.view()!!.cards.size })
    }

    /** F3: a reader that fails on it gets one more try, on a fresh process; failing twice is about the item, which goes. */
    @Test fun aReaderThatFailsGetsOneMoreTryThenTheItemGoes() {
        val (b, _) = waitingCard()
        b.parseBad.set(1)
        assertEquals(1, on(b) { it.view()!!.cards.size })
        val (c, _) = waitingCard()
        c.parseBad.set(2)
        assertTrue(on(c) { it.view()!!.cards.isEmpty() })
        assertEquals(0, c.box.count())
    }

    /** F161: a move pauses the pairing; what waits from that person stays, off the page, and goes only when it ends. */
    @Test fun aPausedPairingKeepsItsCardsAndAnEndedOneDropsThem() {
        val (b, id) = waitingCard()
        assertEquals(1, on(b) { it.view()!!.cards.size })
        fun set(s: PairingState) = on(b) { b.state.pairings[id]!!.let { p -> p.state = s; b.state.put(p) } }
        set(PairingState.MOVED)
        assertTrue(on(b) { it.view()!!.cards.isEmpty() })
        assertEquals(1, b.box.count())
        set(PairingState.ACTIVE)
        assertEquals(1, on(b) { it.view()!!.cards.size })
        set(PairingState.MOVED)
        assertTrue(on(b) { it.view()!!.cards.isEmpty() })
        set(PairingState.ENDED)
        on(b) { it.view() }
        assertEquals(0, b.box.count())
    }

    /** F159: with the outbox full nothing can tell them, so "You declined" isn't said and the card stays. */
    @Test fun aDeclineThatCantBeQueuedKeepsTheCard() {
        val (b, id) = waitingCard()
        val card = on(b) { it.view()!!.cards.single().id }
        val answered = on(b) { d ->
            // Bodies, not acks: acks have a cap of their own (F74), so they can't fill the outbox.
            repeat(LinkLimits.OUTBOX_PER_PERSON) { b.n().deskOut.send(id, EtaTell(1, null)) }
            assertFalse(b.n().deskOut.room(id))
            d.answer(card, DeskChoice.DECLINE)
        }
        assertFalse(answered)
        assertEquals(1, b.box.count())
        assertEquals(card, on(b) { it.view()!!.cards.single().id })
    }

    /** F160: a caller that stopped waiting said it failed: what it queued doesn't happen later. */
    @Test fun workItsCallerGaveUpOnNeverRuns() {
        val r = robot("Matt", FakePushServer(), TestDeskEnv())
        val gate = java.util.concurrent.CountDownLatch(1)
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        r.n().onLinkThread { gate.await(10, TimeUnit.SECONDS) }
        val late = r.n().onLinkThread { ran.set(true) }
        org.junit.Assert.assertThrows(java.util.concurrent.TimeoutException::class.java) { late.within(0) }
        gate.countDown()
        r.n().onLinkThread { }.get(10, TimeUnit.SECONDS)
        assertFalse(ran.get())
    }

    /** L16, finding 10: nothing is answered or undone during a phone lock, whichever card or way. */
    @Test fun aPhoneLockStopsAnswersAndUndos() {
        val push = FakePushServer()
        val samEnv = TestDeskEnv()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, samEnv, handlers = listOf(EtaHandler, Reminders()))
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.ASK)
        val eta = send(a, samOnMatt, EtaTell(10, "Atwater"))
        until("a card waits") { b.box.count() == 1 }
        val card = on(b) { it.view()!!.cards.single().id }
        samEnv.phoneLocked = true
        assertFalse(on(b) { it.answer(card, DeskChoice.YES) })
        assertFalse(on(b) { it.answerWith(card, "ok") })
        assertFalse(on(b) { it.answerWithFile(card, LinkIds.newId()) })
        assertFalse(on(b) { it.undo(card) })
        assertEquals(1, b.box.count())
        samEnv.phoneLocked = false
        assertTrue(on(b) { it.answer(card, DeskChoice.YES) })
        until("Matt sees it answered") { sent(a, eta.id) != DeskState.WAITING_FOR_THEM }
    }

    @Test fun askMeFirstIsACardThatWaitsSealedAndIsAnsweredBack() {
        val push = FakePushServer()
        val samEnv = TestDeskEnv()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, samEnv)
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        // "Someone else" until a preset: ETA is Off, so Sam's Robot declines it and keeps nothing.
        val first = send(a, samOnMatt, EtaTell(10, "Atwater"))
        until("Matt's Sent says Declined") { sent(a, first.id) == DeskState.DECLINED_BY_THEM }
        assertEquals(0, b.box.count())
        assertTrue(on(b) { it.view()!!.cards.isEmpty() })

        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.ASK)
        val eta = send(a, samOnMatt, EtaTell(10, "Atwater"))
        until("a card waits on Sam's side") { b.box.count() == 1 }
        until("Matt's Sent says Waiting for Sam") { sent(a, eta.id) == DeskState.WAITING_FOR_THEM }
        // :voice was pinged and is told a card waits, from whom; nothing of the request.
        assertTrue(samEnv.pings.get() > 0)
        val n = on(b) { it.notices() }
        assertEquals(1, n.waiting)
        assertTrue(n.notices.any { it.kind == DeskNoticeKind.CARDS && it.pairing == mattOnSam && it.value == 1L })
        // Locked: the card isn't read (robot_inbox_v1 needs the phone unlocked), and no view is given.
        samEnv.locked = true
        assertNull(on(b) { it.view() })
        assertFalse(on(b) { it.answer(b.box.ids().single(), DeskChoice.YES) })
        samEnv.locked = false
        val v = on(b) { it.view()!! }
        val card = v.cards.single()
        assertEquals(mattOnSam, card.pairing)
        assertEquals(DeskYes.GOT_IT, card.yes)
        assertEquals(10L, (card.request.body as EtaTell).minutes)
        assertEquals("Atwater", (card.request.body as EtaTell).place)
        // Your yes: the card goes, Matt's Robot hears it was handled, and Sam's list says you answered it.
        assertTrue(on(b) { it.answer(card.id, DeskChoice.YES) })
        assertEquals(0, b.box.count())
        until("Matt's Sent says Answered") { sent(a, eta.id) == DeskState.ANSWERED_BY_THEM }
        val line = on(b) { it.view()!!.lines.single { l -> l.entry.id == eta.id } }
        assertEquals(DeskState.ANSWERED, line.entry.state)
        assertEquals(10L, (line.request!!.body as EtaTell).minutes)
        // Decline on the next one.
        val eta2 = send(a, samOnMatt, EtaTell(20))
        until("a second card") { b.box.count() == 1 }
        val c2 = on(b) { it.view()!!.cards.single() }
        assertTrue(on(b) { it.answer(c2.id, DeskChoice.DECLINE) })
        until("Matt's Sent says Declined") { sent(a, eta2.id) == DeskState.DECLINED_BY_THEM }
        assertEquals(DeskState.DECLINED, on(b) { it.view()!!.lines.single { l -> l.entry.id == eta2.id }.entry.state })
        // A card gone can't be answered twice.
        assertFalse(on(b) { it.answer(c2.id, DeskChoice.YES) })
    }

    @Test fun answerOnItsOwnUsesTheHandlerListsItAndCanBeUndone() {
        val push = FakePushServer()
        val samEnv = TestDeskEnv()
        val reminders = Reminders()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, samEnv, listOf(EtaHandler, reminders))
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        for (cap in listOf(LinkCapability.ETA, LinkCapability.REMINDERS, LinkCapability.FREE_BUSY)) { level(b, mattOnSam, cap, LinkLevel.ASK); level(b, mattOnSam, cap, LinkLevel.AUTO) }
        val eta = send(a, samOnMatt, EtaTell(5))
        until("Matt hears it was handled") { sent(a, eta.id) == DeskState.ANSWERED_BY_THEM }
        assertEquals(0, b.box.count())
        val rem = send(a, samOnMatt, RemOffer("Bring the charger", 1_790_100_000L))
        until("the reminder handled") { sent(a, rem.id) == DeskState.ANSWERED_BY_THEM }
        // "Robot handled 2 things", once.
        val n = on(b) { it.notices() }
        assertEquals(2L, n.notices.single { it.kind == DeskNoticeKind.HANDLED }.value)
        assertTrue(on(b) { it.notices() }.notices.none { it.kind == DeskNoticeKind.HANDLED })
        val v = on(b) { it.view()!! }
        assertEquals(setOf(eta.id, rem.id), v.lines.filter { it.entry.way == DeskWay.IN && it.entry.state == DeskState.HANDLED }.map { it.entry.id }.toSet())
        // Undo: only what can be undone (the reminder, not the ETA), once.
        assertFalse(on(b) { it.undo(eta.id) })
        assertTrue(on(b) { it.undo(rem.id) })
        assertEquals(1, reminders.undone.size)
        assertFalse(on(b) { it.undo(rem.id) })
        assertEquals(DeskState.UNDONE, on(b) { it.view()!!.lines.single { l -> l.entry.id == rem.id }.entry.state })
        // A type this desk has no handler for (here free/busy): on its own falls back to a card, never a guess.
        val fb = send(a, samOnMatt, FbAsk(1_790_100_000L, 1_790_100_000L + 6 * 3_600))
        until("a card for free/busy") { b.box.count() == 1 }
        until("Matt's Sent says Waiting for Sam") { sent(a, fb.id) == DeskState.WAITING_FOR_THEM }
        assertNull(on(b) { it.view()!!.cards.single().yes })
    }

    @Test fun lockedWithQ3OffEverythingWaitsAndIsAnsweredAtTheUnlock() {
        val push = FakePushServer()
        val samEnv = TestDeskEnv()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, samEnv)
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.ASK); level(b, mattOnSam, LinkCapability.ETA, LinkLevel.AUTO)
        // Q3's default: answered on its own while locked.
        samEnv.locked = true
        val e1 = send(a, samOnMatt, EtaTell(5))
        until("answered while locked") { sent(a, e1.id) == DeskState.ANSWERED_BY_THEM }
        // Q3's other answer: it waits sealed, answered `waiting`...
        samEnv.settings = samEnv.settings.copy(whileLocked = false)
        val e2 = send(a, samOnMatt, EtaTell(6))
        until("it waits") { b.box.count() == 1 }
        until("Matt's Sent says Waiting for Sam") { sent(a, e2.id) == DeskState.WAITING_FOR_THEM }
        // ...and at the unlock it is answered on its own, with `handled` sent then.
        samEnv.locked = false
        assertTrue(on(b) { it.view()!!.cards.isEmpty() })
        assertEquals(0, b.box.count())
        until("Matt hears it was handled") { sent(a, e2.id) == DeskState.ANSWERED_BY_THEM }
    }

    @Test fun robotProposesTheSwitchAfterThreeYesesAndNotNowHoldsItBack() {
        val push = FakePushServer()
        val samEnv = TestDeskEnv()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, samEnv)
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.ASK)
        repeat(3) { i ->
            send(a, samOnMatt, EtaTell(5L + i))
            until("card ${i + 1}") { b.box.count() == 1 }
            val c = on(b) { it.view()!! }.also { v -> assertTrue(v.proposals.isEmpty()) }.cards.single()
            assertTrue(on(b) { it.answer(c.id, DeskChoice.YES) })
        }
        assertEquals(listOf(LinkCapability.ETA), on(b) { it.view()!!.proposals.map { p -> p.capability } })
        assertTrue(on(b) { it.notNow(mattOnSam, LinkCapability.ETA) })
        assertTrue(on(b) { it.view()!!.proposals.isEmpty() })
        // Turned on by your tap (SET_LEVEL), it's no longer proposed either.
        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.AUTO)
        assertTrue(on(b) { it.view()!!.proposals.isEmpty() })
    }

    @Test fun aCardPastItsLifetimeGoes() {
        val push = FakePushServer()
        val samEnv = TestDeskEnv()
        val a = robot("Matt", push, TestDeskEnv())
        val b = robot("Sam", push, samEnv)
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        level(b, mattOnSam, LinkCapability.ETA, LinkLevel.ASK)
        val r = LinkRequest.of(EtaTell(9), a.env.now() / 1000, "en", 3_600)
        assertNull(a.n().queue(samOnMatt, RequestType.ETA_TELL.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
        until("its card") { b.box.count() == 1 }
        b.env.clock.addAndGet(3_600_000)
        val v = on(b) { it.view()!! }
        assertTrue(v.cards.isEmpty())
        assertEquals(0, b.box.count())
        assertEquals(DeskState.EXPIRED, v.lines.single { it.entry.id == r.id }.entry.state)
        assertNotNull(v)
    }
}
