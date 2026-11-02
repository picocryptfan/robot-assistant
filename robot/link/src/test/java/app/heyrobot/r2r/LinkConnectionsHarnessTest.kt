// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.EnvelopeKind
import app.heyrobot.core.link.LinkCarrier
import app.heyrobot.core.link.LinkEnvelope
import app.heyrobot.core.link.LinkEvent
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.LinkWay
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.PairCode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 1.5, Robot to Robot (robot-to-robot-plan.md §2.2, §3.2, §4.6; job L5-fix): live connections and when the carriers run,
 * two complete link stacks in one JVM (§8.3). A connection bound by a token someone saw proves itself before anything sent
 * on it counts, and one that carries what doesn't decrypt is closed.
 */
class LinkConnectionsHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()

    @After fun tearDown() {
        for (r in robots) r.node?.close()
    }

    private fun robot(name: String, lan: LoopbackLan?, n: Int, tor: FakeTorNetwork?, push: FakePushServer?, env: FakeEnv = FakeEnv(t0)): TestRobot =
        TestRobot(tmp.newFolder(), env, lan, n, tor, push, name).also { robots += it; it.n().start(); it.n().wake() }

    private fun until(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $what")
    }

    private fun idle(vararg rs: TestRobot) {
        repeat(3) { for (r in rs) { r.n().pump().get(10, TimeUnit.SECONDS); r.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    /** [ms] later on every clock of [rs]. */
    private fun later(ms: Long, vararg rs: TestRobot) { for (e in rs.map { it.env }.distinct()) e.clock.addAndGet(ms) }

    private fun views(r: TestRobot): List<PairingView> = r.n().views().get(10, TimeUnit.SECONDS)
    private fun active(r: TestRobot): String {
        until("an active pairing") { views(r).any { it.phase == PairingPhase.ACTIVE } }
        return views(r).first { it.phase == PairingPhase.ACTIVE }.id
    }

    private fun send(from: TestRobot, pairing: String, body: LinkBody): LinkRequest {
        val r = LinkRequest.of(body, from.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r, LinkRequests.LIVE)!!).get(10, TimeUnit.SECONDS))
        return r
    }

    private fun sorted(a: TestRobot, b: TestRobot): Pair<TestRobot, TestRobot> = if (PairCode.firstSends(a.stores.identityKey, b.stores.identityKey) == true) a to b else b to a

    private fun inPersonCode(r: TestRobot): MadeCode = r.n().makeCode(LinkMode.PRIVATE, true).get(20, TimeUnit.SECONDS).also {
        assertEquals(MakeCodeResult.OK, it.result)
        assertNotNull(it.code!!.qrText())
    }

    /** [first] and [second] photograph each other's codes (§2.3): (the pairing on first, the pairing on second). */
    private fun pairInPerson(first: TestRobot, second: TestRobot): Pair<String, String> {
        val fCode = inPersonCode(first)
        val sCode = inPersonCode(second)
        assertEquals(UseCodeResult.WAITING, second.n().useCode(fCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val used = first.n().useCode(sCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, used.result)
        val onSecond = active(second)
        until("first answered") { views(first).any { it.phase == PairingPhase.ACTIVE } }
        idle(first, second)
        return used.pairing!! to onSecond
    }

    /** A stranger's socket on [port] that sends a connection hello with [token]. */
    private fun bind(port: Int, token: ByteArray): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
        s.getOutputStream().write(LiveFrames.connectHello(token).encode(LinkCarrier.LIVE))
        s.getOutputStream().flush()
        return s
    }

    /** True when the other side closed [s] within [ms] (what it sent before is read and dropped). */
    private fun closedByPeer(s: Socket, ms: Int): Boolean {
        s.soTimeout = ms
        val buf = ByteArray(4_096)
        return try {
            var closed = false
            while (!closed) closed = s.getInputStream().read(buf) < 0
            true
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
    }

    @Test fun aConnectionBoundByATokenSomeoneSawNeverCountsAsDeliveryAndJunkClosesIt() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val (first, second) = sorted(robot("Matt", lan, 1, tor, null), robot("Sam", lan, 2, tor, null))
        val (onFirst, onSecond) = pairInPerson(first, second)
        // One reconnection, so the second Robot has given a current token and the one before (both still let in).
        first.n().sleep(); second.n().sleep(); idle(first, second)
        first.n().wake(); second.n().wake()
        val e0 = send(first, onFirst, EtaTell(1, null))
        until("reconnected") { second.sink.delivered.any { it.second.id == e0.id } }
        idle(first, second)
        val rec = second.state.pairings.getValue(onSecond)
        val current = rec.inToken!!.copyOf()
        val before = rec.inPrev!!.copyOf()
        val port = first.state.pairings.getValue(onFirst).peerAddrs.first().port
        // Matt's Robot goes quiet and the shared network goes; someone who saw both tokens opens two connections to Sam's.
        first.n().sleep(); second.n().sleep(); idle(first, second)
        second.n().wake(); idle(second)
        lan.down = true
        val raw1 = bind(port, current)
        val raw2 = bind(port, before)
        Thread.sleep(300)
        assertFalse("bound by the current token", closedByPeer(raw1, 200))
        assertFalse("bound by the one before", closedByPeer(raw2, 200))
        // Sam's request goes on one of them, and since neither proved itself, by Tor too: Matt's Robot gets it.
        val e = send(second, onSecond, EtaTell(7, null))
        idle(second)
        // The stranger's token never made Sam's Robot give a new one.
        assertTrue(second.state.pairings.getValue(onSecond).inToken!!.contentEquals(current))
        first.n().wake()
        later(2 * 60_000L, first, second)
        until("Matt's Robot got it by Tor") { idle(first, second); first.sink.delivered.any { it.second.id == e.id } }
        assertTrue(second.log.snapshot().any { it.event == LinkEvent.REQUEST_OUT && it.b == LinkWay.TOR.code })
        // A session message on it that doesn't decrypt: closed.
        raw1.getOutputStream().write(LinkEnvelope(EnvelopeKind.SESSION, current, ByteArray(200) { 7 }).encode(LinkCarrier.LIVE))
        raw1.getOutputStream().flush()
        assertTrue("a session message that doesn't decrypt closes it", closedByPeer(raw1, 3_000))
        raw2.getOutputStream().write(LinkEnvelope(EnvelopeKind.SESSION, before, ByteArray(200) { 9 }).encode(LinkCarrier.LIVE))
        raw2.getOutputStream().flush()
        assertTrue(closedByPeer(raw2, 3_000))
        raw1.close(); raw2.close()
    }

    @Test fun silentConnectionsOnTheListenerNeverBlockBoundOrDialledOnes() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val (first, second) = sorted(robot("Matt", lan, 1, tor, null), robot("Sam", lan, 2, tor, null))
        val (onFirst, onSecond) = pairInPerson(first, second)
        val port = first.state.pairings.getValue(onFirst).peerAddrs.first().port
        first.n().sleep(); second.n().sleep(); idle(first, second)
        second.n().wake(); idle(second)
        // Eight connections to Sam's listener that never say hello: four wait (their own cap), four are closed at once.
        val socks = List(8) { Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000) } }
        Thread.sleep(500)
        assertEquals(4, second.n().status().get().connections)
        assertEquals(4, socks.count { closedByPeer(it, 300) })
        // Sam's request still reaches Matt's Robot, long before the silent ones time out (30 seconds).
        first.n().wake()
        val e = send(second, onSecond, EtaTell(8, null))
        until("delivered while four silent connections wait", 10_000) { idle(first, second); first.sink.delivered.any { it.second.id == e.id } }
        socks.forEach { it.close() }
    }

    /** L5-fix: while :link sleeps, a pump neither starts Tor nor dials through it; once awake, the request goes. */
    @Test fun noTorWhileAsleep() {
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, null)
        val b = robot("Sam", null, 2, tor, null)
        // A pairing Tor alone carries (no push app, no shared network).
        val made = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        assertNull(made.code!!.push)
        assertEquals(UseCodeResult.SENT, b.n().useCode(made.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val onA = active(a)
        until("Sam's side active") { views(b).any { it.phase == PairingPhase.ACTIVE } }
        idle(a, b)
        a.n().sleep()
        until("Matt's Tor stopped") { !a.tor!!.running }
        val dials = tor.dials.get()
        val e = send(a, onA, EtaTell(3, null))
        idle(a)
        later(2 * 60_000L, a, b)
        idle(a)
        Thread.sleep(300)
        assertFalse("Tor not started while asleep", a.tor!!.running)
        assertEquals("nothing dialled while asleep", dials, tor.dials.get())
        assertTrue(b.sink.delivered.none { it.second.id == e.id })
        // Awake (a client bound, or the push job): it goes.
        a.n().wake()
        later(5 * 60_000L, a, b)
        until("delivered once awake") { idle(a, b); b.sink.delivered.any { it.second.id == e.id } }
    }

    @Test fun aCodesOneTimeOnionTakesOnlyAPairingHello() {
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, null)
        val b = robot("Sam", null, 2, tor, null)
        val first = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, b.n().useCode(first.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val onA = active(a)
        idle(a, b)
        // Anyone holding a new code reaches its one-time onion (no client authorization): a connection hello there, even
        // with a token Matt's Robot gave, is dropped.
        val made = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        val stream = tor.carrier().apply { up() }.dial(made.code!!.onion!!, "x")
        stream.output.write(LiveFrames.connectHello(a.state.pairings.getValue(onA).inToken!!).encode(LinkCarrier.LIVE))
        stream.output.flush()
        until("dropped") { a.log.snapshot().any { it.event == LinkEvent.DROPPED && it.a == LinkWay.TOR.code && it.b == LinkProblem.BAD_KIND.code } }
        assertEquals(-1, stream.input.read())
    }
}
