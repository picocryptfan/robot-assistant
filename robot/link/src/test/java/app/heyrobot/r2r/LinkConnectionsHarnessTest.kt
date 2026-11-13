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

    /** fix-1.2.1-b: [r]'s listener now, not the port its peer last heard: after a sleep it may be another. */
    private fun listening(lan: LoopbackLan, r: TestRobot): Int = lan.port(if (r.name == "Matt") 1 else 2)

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
        // Matt's Robot goes quiet and the shared network goes; someone who saw both tokens opens two connections to Sam's.
        first.n().sleep(); second.n().sleep(); idle(first, second)
        second.n().wake(); idle(second)
        val port = listening(lan, second)
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
        // link-flake-1.2.1: Matt's onion comes up a while after he wakes (Tor's own thread); a try before then fails and
        // waits out its backoff, which this test's clock never reaches. Time moves on only once it answers.
        tor.publishDelayMs = 500
        first.n().wake()
        until("Matt's onion answers") { first.state.tor?.let { tor.serving(it.onion) } == true }
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
        first.n().sleep(); second.n().sleep(); idle(first, second)
        second.n().wake(); idle(second)
        val port = listening(lan, second)
        // Eight connections to Sam's listener that never say hello: four wait (their own cap), four are closed at once.
        // fix-1.2.1-b: wait until Sam's Robot has taken in all eight, not a fixed half second: under a full run's load the
        // listener's thread and :link's own lag behind, and fewer than eight in by then failed the counts below.
        val mark = second.log.snapshot().lastOrNull()
        val socks = List(8) { Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000) } }
        until("all eight taken in") { second.log.snapshot().let { l -> l.drop(l.indexOfLast { it === mark } + 1) }.count { it.event == LinkEvent.CONNECT } >= 8 }
        idle(second)
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
        until("Matt's onion answers") { a.state.tor?.let { tor.serving(it.onion) } == true } // link-flake-1.2.1, as above
        later(5 * 60_000L, a, b)
        until("delivered once awake") { idle(a, b); b.sink.delivered.any { it.second.id == e.id } }
    }

    /**
     * fix-tor-lows-141-c (review-fix-tor-lows-141-b L): close() while Tor's thread is busy with a start that ignores the
     * interrupt (its last poll reaching 100%, then the code's publish) still leaves Tor stopped once that start returns.
     */
    @Test fun closeWhileTorStartsLeavesItStopped() {
        val tor = FakeTorNetwork()
        val a = robot("Robin", null, 4, tor, null)
        idle(a)
        val at = a.tor!!
        val gate = java.util.concurrent.CountDownLatch(1)
        tor.deaf = true
        tor.holds[at] = gate
        val ups = tor.ups(at)
        val downs = tor.downs(at)
        a.n().makeCode(LinkMode.PRIVATE, false)
        until("Robin's start under way") { at in tor.entered }
        val closer = Thread { a.n().close() }
        // fix-link-unbind-141-b (review-fix-link-unbind-141 L2): only a stop thread this close() starts counts, not one an
        // earlier test left behind.
        fun stops() = Thread.getAllStackTraces().keys.filter { it.name == "robot-link-tor-stop" && it.isAlive }.toSet()
        val earlier = stops()
        closer.start()
        // fix-tor-lows-141-d (review-c L1, L2): close() waits for its Tor stop, so the start goes on while close() runs,
        // Tor comes up, and it is stopped by the time close() returns.
        until("close()'s Tor stop waiting") { (stops() - earlier).isNotEmpty() }
        // fix-link-unbind-141 (review-fix-tor-lows-141-d F2): close() still runs while the start is held, so a close() that
        // does not wait for its stop fails here every time, not only when the start loses the race.
        closer.join(500)
        assertTrue("close() waits for its Tor stop", closer.isAlive)
        gate.countDown()
        closer.join(15_000)
        assertFalse("close() returned", closer.isAlive)
        robots -= a
        assertTrue("Tor came up before the stop", tor.ups(at) > ups)
        assertTrue("Tor stopped by close()", tor.downs(at) > downs)
        assertFalse("Tor stopped after close", at.running)
    }

    /**
     * fix-bridges-and-wording-c L1, L2: a stale Tor (your own bridge lines changed) is dealt with on the pump while :link is
     * awake: started again once when your onion is published and Robot's network access is allowed, otherwise stopped
     * (never plain Tor with nothing to carry); asleep (the screens closed), the pump leaves it to the next use.
     */
    @Test fun aStaleTorIsStartedAgainOnThePumpOnlyWhenItHasSomethingToCarry() {
        val tor = FakeTorNetwork()
        // No onion published, nothing queued: stopped, not started again.
        val lone = robot("Alex", null, 3, tor, null)
        idle(lone)
        val lt = lone.tor!!
        val lu = tor.ups(lt)
        val ld = tor.downs(lt)
        tor.staleOnes += lt
        lone.n().pump().get(10, TimeUnit.SECONDS)
        until("Alex's stale Tor stopped") { tor.downs(lt) > ld }
        assertEquals("nothing to carry: not started", lu, tor.ups(lt))
        // Your onion published (a pairing Tor alone carries): started again once on the pump.
        val a = robot("Matt", null, 1, tor, null)
        val b = robot("Sam", null, 2, tor, null)
        val made = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, b.n().useCode(made.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        active(a)
        until("Sam's side active") { views(b).any { it.phase == PairingPhase.ACTIVE } }
        idle(a, b)
        val at = a.tor!!
        val u = tor.ups(at)
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("Matt's stale Tor started again") { at !in tor.staleOnes }
        idle(a)
        assertEquals("one start", u + 1, tor.ups(at))
        // Robot's network access off: stopped, never started.
        a.env.network.set(false)
        val u2 = tor.ups(at)
        val d2 = tor.downs(at)
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("Matt's stale Tor stopped") { tor.downs(at) > d2 }
        assertEquals("network off: not started", u2, tor.ups(at))
        a.env.network.set(true)
        // Asleep: the pump leaves it alone. Tor is already stopped (network access was off), so `running` is false before
        // the sleep's own stop runs on Tor's thread: wait for that stop's count, or it lands after d3 is read.
        val d = tor.downs(at)
        a.n().sleep()
        until("Matt's Tor stopped") { tor.downs(at) > d && !at.running }
        val u3 = tor.ups(at)
        val d3 = tor.downs(at)
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        Thread.sleep(300)
        assertEquals("asleep: not started", u3, tor.ups(at))
        assertEquals("asleep: nothing on the pump", d3, tor.downs(at))
    }

    /** fix-bridges-and-wording-d L2: an open code's one-time onion is something to carry: a stale Tor starts again for it. */
    @Test fun aStaleTorStartsAgainForAnOpenCode() {
        val tor = FakeTorNetwork()
        val lone = robot("Alex", null, 3, tor, null)
        val code = lone.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code
        assertNotNull(code)
        idle(lone)
        val lt = lone.tor!!
        val u = tor.ups(lt)
        val d = tor.downs(lt)
        tor.staleOnes += lt
        lone.n().pump().get(10, TimeUnit.SECONDS)
        until("Alex's stale Tor started again") { tor.ups(lt) > u }
        assertEquals("not stopped", d, tor.downs(lt))
        // fix-bridges-lows (review-d L6): the new start dropped the code's onion; it's published again over it.
        until("the code's onion answers again") { tor.serving(code!!.onion!!) }
    }

    /** fix-bridges-and-wording-d L3: a stale Tor stopped while network access was off comes back with your onion once it's on. */
    @Test fun networkAccessBackOnPublishesYourOnionAgain() {
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, null)
        val b = robot("Sam", null, 2, tor, null)
        val made = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, b.n().useCode(made.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        active(a)
        until("Sam's side active") { views(b).any { it.phase == PairingPhase.ACTIVE } }
        idle(a, b)
        val at = a.tor!!
        a.env.network.set(false)
        val d = tor.downs(at)
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("Matt's stale Tor stopped") { tor.downs(at) > d && !at.running }
        a.env.network.set(true)
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("Matt's Tor back with his onion") { at.running }
        until("Matt's onion answers") { a.state.tor?.let { tor.serving(it.onion) } == true } // fix-bridges-lows (review-d L6)
    }

    /**
     * review-fix-bridges-and-wording-c L1: a code's start on Tor's thread is still connecting over your old lines when the
     * pump finds them stale: the pump's task, queued behind it, finds Tor up over the new lines and leaves it, onion and all.
     */
    @Test fun aCodesStartThatMovedToYourNewLinesKeepsItsOnion() {
        val tor = FakeTorNetwork()
        val a = robot("Alex", null, 3, tor, null)
        idle(a)
        val at = a.tor!!
        val d = tor.downs(at)
        val gate = java.util.concurrent.CountDownLatch(1)
        tor.holds[at] = gate
        val made = a.n().makeCode(LinkMode.PRIVATE, false)
        until("Alex's start under way") { at in tor.entered }
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        gate.countDown()
        val code = made.get(20, TimeUnit.SECONDS).code!!
        idle(a)
        assertEquals("not stopped", d, tor.downs(at))
        assertTrue("the code's onion answers", tor.serving(code.onion!!))
    }

    /**
     * review-fix-bridges-and-wording-d L5: a start off Tor's thread (a feed's) is still connecting over your old lines when
     * the pump finds them stale, with nothing to carry (no pairing, no code, nothing queued): it moves to the new lines and
     * comes up, and the pump's stop, checked again under Tor's lock (letGo), leaves it running for the feed.
     */
    @Test fun aStartOffTorsThreadThatMovedToYourNewLinesKeepsRunning() {
        val tor = FakeTorNetwork()
        val a = robot("Alex", null, 3, tor, null)
        idle(a)
        val at = a.tor!!
        val d = tor.downs(at)
        val gate = java.util.concurrent.CountDownLatch(1)
        tor.holds[at] = gate
        Thread { a.n().feedTor() }.apply { isDaemon = true }.start() // a feed waits on that start: off the test's thread
        until("Alex's feed start under way") { at in tor.entered }
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("the pump's stop waiting on that start") { tor.asks(at) > 0 }
        gate.countDown()
        until("Tor up for the feed") { a.n().feedTor() == FeedTorGate.State.UP }
        idle(a)
        assertEquals("not stopped", d, tor.downs(at))
        assertTrue("Alex's Tor still up", at.running)
    }

    /**
     * review-fix-bridges-lows L1: as above, but you turn off Robot's network access before the pump: the start that moved
     * to your new lines is stopped once it ends, never kept with network access off.
     */
    @Test fun aStartOffTorsThreadThatMovedToYourNewLinesStopsWithNetworkOff() {
        val tor = FakeTorNetwork()
        val a = robot("Alex", null, 3, tor, null)
        idle(a)
        val at = a.tor!!
        val d = tor.downs(at)
        val gate = java.util.concurrent.CountDownLatch(1)
        tor.holds[at] = gate
        Thread { a.n().feedTor() }.apply { isDaemon = true }.start()
        until("Alex's feed start under way") { at in tor.entered }
        tor.staleOnes += at
        a.env.network.set(false)
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("the pump's stop waiting on that start") { tor.asks(at) > 0 }
        gate.countDown()
        until("stopped with network access off") { tor.downs(at) > d }
        assertFalse("Alex's Tor not running", at.running)
    }

    /** review-fix-bridges-lows-b L1, Tor fails closed: network access off stops a running Tor that isn't stale too. */
    @Test fun networkAccessOffStopsATorThatIsNotStale() {
        val tor = FakeTorNetwork()
        val a = robot("Alex", null, 3, tor, null)
        idle(a)
        val at = a.tor!!
        assertTrue(at.up())
        val d = tor.downs(at)
        a.env.network.set(false)
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("stopped with network access off") { tor.downs(at) > d && !at.running }
    }

    /**
     * review-fix-bridges-lows-b L2: the pump's start over your new lines is stopped by network access going off, back on
     * before any pump saw it off: your onion is published again on the next pumps, not only at the next send or wake.
     */
    @Test fun aStartStoppedByNetworkAccessOffPublishesYourOnionOnceItsBack() {
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, null)
        val b = robot("Sam", null, 2, tor, null)
        val made = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, b.n().useCode(made.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        active(a)
        until("Sam's side active") { views(b).any { it.phase == PairingPhase.ACTIVE } }
        idle(a, b)
        until("Matt's onion answers") { a.state.tor?.let { tor.serving(it.onion) } == true }
        val at = a.tor!!
        val gate = java.util.concurrent.CountDownLatch(1)
        tor.holds[at] = gate
        val k = a.env.torChecks.get()
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("the pump's start under way") { at in tor.entered }
        a.env.network.set(false)
        gate.countDown()
        // One check before that start, one after it (read while network access was still off).
        until("the stopped start's check") { a.env.torChecks.get() > k + 1 }
        assertFalse("Matt's onion went with that start", a.state.tor?.let { tor.serving(it.onion) } == true)
        tor.holds.remove(at)
        a.env.network.set(true)
        until("Matt's onion answers again") { a.n().pump().get(10, TimeUnit.SECONDS); a.state.tor?.let { tor.serving(it.onion) } == true }
    }

    /** review-fix-bridges-lows-c M1: network access off stops a Tor still connecting (a start that gave up), not only a ready one. */
    @Test fun networkAccessOffStopsATorStillConnecting() {
        val tor = FakeTorNetwork()
        val a = robot("Alex", null, 3, tor, null)
        idle(a)
        val at = a.tor!!
        tor.connecting += at
        val d = tor.downs(at)
        a.env.network.set(false)
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("stopped with network access off") { tor.downs(at) > d && !at.live }
    }

    /**
     * review-fix-bridges-lows-c L2: the start network access stopped, with it back on before that start returned: your onion
     * is still published again on the next pumps (the refusal itself marks it).
     */
    @Test fun aStartStoppedByAQuickOffAndOnPublishesYourOnionAgain() {
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, null)
        val b = robot("Sam", null, 2, tor, null)
        val made = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, b.n().useCode(made.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        active(a)
        until("Sam's side active") { views(b).any { it.phase == PairingPhase.ACTIVE } }
        idle(a, b)
        until("Matt's onion answers") { a.state.tor?.let { tor.serving(it.onion) } == true }
        val at = a.tor!!
        val node = tor.mayStart.getValue(at)
        // Network access back on as soon as that start is refused, before its up() returns.
        tor.mayStart[at] = { node().also { if (!it) a.env.network.set(true) } }
        val gate = java.util.concurrent.CountDownLatch(1)
        tor.holds[at] = gate
        tor.staleOnes += at
        a.n().pump().get(10, TimeUnit.SECONDS)
        until("the pump's start under way") { at in tor.entered }
        a.env.network.set(false)
        gate.countDown()
        until("refused, and network access back on") { at !in tor.staleOnes && a.env.network.get() }
        assertFalse("Matt's onion went with that start", a.state.tor?.let { tor.serving(it.onion) } == true)
        tor.holds.remove(at)
        until("Matt's onion answers again") { a.n().pump().get(10, TimeUnit.SECONDS); a.state.tor?.let { tor.serving(it.onion) } == true }
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
