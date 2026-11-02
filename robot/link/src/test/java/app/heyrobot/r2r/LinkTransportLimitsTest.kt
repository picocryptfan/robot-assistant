// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkWay
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Audit findings F69, F70, F73, F74, F75: what one paired Robot, or a stranger on the network, can't hold on to. */
class LinkTransportLimitsTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()

    @After fun tearDown() { for (r in robots) r.node?.close() }

    private fun robot(name: String, push: FakePushServer): TestRobot =
        TestRobot(tmp.newFolder(), FakeEnv(t0), null, 0, null, push, name, deskEnv = TestDeskEnv()).also { robots += it; it.n().start(); it.n().settle().get(10, TimeUnit.SECONDS) }

    private fun until(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun idle(vararg rs: TestRobot) {
        repeat(3) { for (r in rs) { r.n().pump().get(10, TimeUnit.SECONDS); r.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun active(r: TestRobot, not: Set<String> = emptySet()): String {
        until("an active pairing") { r.n().pairings().get().any { it.value == PairingState.ACTIVE && it.key !in not } }
        return r.n().pairings().get().entries.first { it.value == PairingState.ACTIVE && it.key !in not }.key
    }

    private fun pair(a: TestRobot, b: TestRobot, notOnA: Set<String> = emptySet()): Pair<String, String> {
        val code = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        return active(a, notOnA) to active(b)
    }

    private fun connections(r: TestRobot) = r.n().status().get(10, TimeUnit.SECONDS).connections

    /** The test's end of a connection accepted by [r]. */
    private fun comeIn(r: TestRobot): PipeDuplex {
        val (mine, theirs) = PipeDuplex.pair()
        r.n().accepted(mine, LinkWay.TOR)
        return theirs
    }

    private fun closed(d: PipeDuplex): Boolean {
        val done = CountDownLatch(1)
        Thread { try { while (d.input.read() != -1) { /* what it sent */ } } catch (_: java.io.IOException) {}; done.countDown() }.apply { isDaemon = true }.start()
        return done.await(10, TimeUnit.SECONDS)
    }

    private fun card() = LinkRequest.of(EtaTell(10, "Atwater"), t0 / 1000, "en", 3600) // l10n: not text

    private fun asks(r: TestRobot, pairing: String) = assertTrue(r.n().setLevel(pairing, LinkCapability.ETA, LinkLevel.ASK).get(10, TimeUnit.SECONDS))

    @Test fun oneRobotHoldsTwoConnectionsAndTheOldestGoes() {
        val push = FakePushServer()
        val a = robot("Matt", push); val b = robot("Sam", push)
        val (_, mattOnSam) = pair(a, b)
        idle(a, b)
        val token = b.n().onLinkThread { b.state.pairings[mattOnSam]!!.inToken!! }.get(10, TimeUnit.SECONDS)
        val base = connections(b)
        val ends = List(3) { comeIn(b).also { e -> LiveFrames.write(e.output, LiveFrames.connectHello(token)); idle(b) } }
        until("two left") { connections(b) == base + LinkLimits.LIVE_PER_PAIRING }
        assertTrue("the oldest went", closed(ends[0]))
    }

    @Test fun silentConnectionsMakeWayForTheNext() {
        val push = FakePushServer()
        val b = robot("Sam", push)
        val base = connections(b)
        val ends = List(LinkLimits.UNBOUND_CONNECTIONS + 1) { comeIn(b).also { idle(b) } }
        until("the cap holds") { connections(b) == base + LinkLimits.UNBOUND_CONNECTIONS }
        assertTrue("the oldest went", closed(ends[0]))
    }

    @Test fun framesWaitInTheSendersPipeWhileTheLinkThreadIsBusy() {
        val push = FakePushServer()
        val b = robot("Sam", push)
        val (mine, theirs) = PipeDuplex.pair()
        b.n().accepted(mine, LinkWay.TOR)
        idle(b)
        val hold = CountDownLatch(1)
        val blocked = b.n().onLinkThread { hold.await(30, TimeUnit.SECONDS) }
        repeat(LiveConn.FRAMES_WAITING * 4) { LiveFrames.write(theirs.output, LiveFrames.connectHello(ByteArray(app.heyrobot.core.link.LinkEnvelope.NO_ROUTE.size) { 1 })) }
        Thread.sleep(500)
        val unread = mine.input.available()
        hold.countDown()
        blocked.get(10, TimeUnit.SECONDS)
        assertTrue("the reader stopped with $unread bytes left", unread > 0)
    }

    @Test fun onePersonHasAShareOfTheInbox() {
        val push = FakePushServer()
        val a = robot("Matt", push); val b = robot("Sam", push); val c = robot("Kim", push)
        val (_, mattOnSam) = pair(a, b)
        val (kimOnSam, _) = pair(b, c, setOf(mattOnSam))
        idle(a, b, c)
        asks(b, mattOnSam); asks(b, kimOnSam)
        b.deskEnv!!.locked = true
        fun deliver(p: String) = b.n().onLinkThread { b.desk!!.delivered(p, card()) }.get(10, TimeUnit.SECONDS)
        repeat(LinkLimits.INBOX_PER_PERSON) { assertEquals(AckState.WAITING, deliver(mattOnSam)) }
        assertEquals("past the share", AckState.BUSY, deliver(mattOnSam))
        assertEquals("someone else still gets in", AckState.WAITING, deliver(kimOnSam))
        // Opened at the unlock, the same count holds.
        b.deskEnv.locked = false
        b.n().onLinkThread { b.desk!!.drain() }.get(30, TimeUnit.SECONDS)
        assertEquals(AckState.BUSY, deliver(mattOnSam))
    }

    /** F73: a restart while locked keeps the count, for cards opened before the lock and what came during it; the unlock sets it right. */
    @Test fun aRestartWhileLockedKeepsThePersonsShare() {
        val push = FakePushServer()
        val a = robot("Matt", push); val b = robot("Sam", push); val c = robot("Kim", push)
        val (_, mattOnSam) = pair(a, b)
        val (kimOnSam, _) = pair(b, c, setOf(mattOnSam))
        idle(a, b, c)
        asks(b, mattOnSam); asks(b, kimOnSam)
        fun delivered(d: LinkDesk, p: String) = b.n().onLinkThread { d.delivered(p, card()) }.get(10, TimeUnit.SECONDS)
        // Half of it opened into cards while unlocked, the rest while locked.
        repeat(LinkLimits.INBOX_PER_PERSON / 2) { assertEquals(AckState.WAITING, delivered(b.desk!!, mattOnSam)) }
        b.n().onLinkThread { b.desk!!.drain() }.get(30, TimeUnit.SECONDS)
        b.deskEnv!!.locked = true
        repeat(LinkLimits.INBOX_PER_PERSON / 2 - 1) { assertEquals(AckState.WAITING, delivered(b.desk!!, mattOnSam)) }
        assertEquals(AckState.WAITING, delivered(b.desk!!, kimOnSam))
        // The process ends: a new state read from the records, a new desk over the same inbox.
        val saved = LinkStateStore(b.records).also { it.load() }
        val later = LinkDesk(saved, b.box, b.deskEnv, b.decoder, emptyList()).also { it.attach(b.n().deskOut) }
        assertEquals("one left in the share", AckState.WAITING, b.n().onLinkThread { later.delivered(mattOnSam, card()) }.get(10, TimeUnit.SECONDS))
        assertEquals("past the share", AckState.BUSY, b.n().onLinkThread { later.delivered(mattOnSam, card()) }.get(10, TimeUnit.SECONDS))
        assertEquals("someone else still gets in", AckState.WAITING, b.n().onLinkThread { later.delivered(kimOnSam, card()) }.get(10, TimeUnit.SECONDS))
    }

    @Test fun acksToADeadRouteStopAtACapAndTheNewestStays() {
        val push = FakePushServer()
        val a = robot("Matt", push); val b = robot("Sam", push)
        val (samOnMatt, mattOnSam) = pair(a, b)
        idle(a, b)
        asks(b, mattOnSam)
        a.n().onLinkThread { a.state.pairings[samOnMatt]!!.caps = app.heyrobot.core.link.requests.LinkCaps.bitmap(RequestType.entries.toSet()) }.get(10, TimeUnit.SECONDS)
        b.env.network.set(false)
        repeat(LinkLimits.ACKS_QUEUED + 8) {
            val r = LinkRequest.of(EtaTell(10, "Atwater"), a.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL) // l10n: not text
            a.n().queue(samOnMatt, r.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS)
        }
        until("all read") { b.box.count() >= LinkLimits.ACKS_QUEUED + 8 }
        idle(b)
        val acks = b.n().onLinkThread { b.state.outbox.values.count { it.type == RequestType.ACK.code } }.get(10, TimeUnit.SECONDS)
        assertEquals(LinkLimits.ACKS_QUEUED, acks)
    }
}
