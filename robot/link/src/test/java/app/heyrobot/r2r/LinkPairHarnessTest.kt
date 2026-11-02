// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.RLog
import app.heyrobot.core.link.LinkEvent
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.LinkWay
import app.heyrobot.core.link.PairCode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairCodeRead
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.Ack
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.Caps
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.ModeLower
import app.heyrobot.core.link.requests.ModeRaise
import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.RemOffer
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.requests.Span
import app.heyrobot.core.link.requests.Unpair
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
 * robot-to-robot-plan.md §8.3 and §9.1 (job L3): LinkPairHarnessTest, two complete Robots in one JVM, each with its own
 * sealed state, libsignal's real sessions (the desktop library), and the carriers: direct over loopback, a fake push server
 * with real RFC 8291 on both sides, a fake Tor network that checks client authorization, an injected clock. Pairing at a
 * distance by push and in person (direct and Tor), typed requests and acks both ways over each carrier, Anonymous never
 * direct and its pushes through Tor, replay and expiry, the rate limits, the Network toggle, the outbox's backoff and giving
 * up, a code's one use and five wrong tries, unpairing, and a marker name that never reaches a log.
 */
class LinkPairHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()
    private val logLines = CopyOnWriteArrayList<String>()

    @After fun tearDown() {
        RLog.sink = null
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

    private fun active(r: TestRobot): String {
        until("an active pairing") { r.n().pairings().get().values.any { it == PairingState.ACTIVE } }
        return r.n().pairings().get().entries.first { it.value == PairingState.ACTIVE }.key
    }

    private fun send(from: TestRobot, pairing: String, body: LinkBody, at: Long = from.env.now() / 1000, ttl: Long = LinkRequests.DEFAULT_TTL): LinkRequest {
        val r = LinkRequest.of(body, at, "en", ttl)
        val bytes = LinkRequests.encode(r, LinkRequests.LIVE)!!
        assertNull(from.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, bytes).get(10, TimeUnit.SECONDS))
        return r
    }

    private fun pairByPush(a: TestRobot, b: TestRobot, mode: LinkMode = LinkMode.PRIVATE): Pair<String, String> {
        val code = a.n().makeCode(mode, false).get(20, TimeUnit.SECONDS).code
        assertNotNull("a code", code)
        assertNotNull("a code shared at a distance carries a push address", code!!.push)
        assertNull(code.local)
        val text = "Here's my code: ${code.text()} (works once, for 24 hours)"
        assertEquals(UseCodeResult.SENT, b.n().useCode(text, PairCodeForm.PASTED, mode).get(30, TimeUnit.SECONDS).result)
        val onA = active(a)
        val onB = active(b)
        return onA to onB
    }

    @Test fun twoRobotsPairByPushAndExchangeRequests() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        assertEquals(listOf(samOnMatt), a.sink.paired)
        assertEquals(listOf(mattOnSam), b.sink.paired)
        // Every push body crossed the push server as RFC 8291 ciphertext: neither name appears in it.
        for (body in push.bodies) for (marker in listOf("Matt", "Sam")) assertFalse(String(body, Charsets.ISO_8859_1).contains(marker))
        // Their caps and routes arrived: Matt asks Sam's Robot about Friday evening, Sam's answers busy blocks only.
        idle(a, b)
        val ask = send(a, samOnMatt, FbAsk(1_790_100_000L, 1_790_100_000L + 6 * 3_600))
        until("Sam's Robot got the question") { b.sink.delivered.any { it.second.id == ask.id } }
        assertEquals(mattOnSam, b.sink.delivered.single { it.second.id == ask.id }.first)
        until("Matt's Robot got the ack") { a.sink.acked.any { it.second == ask.id && it.third == AckState.RECEIVED } }
        until("the ack emptied Matt's outbox of it") { a.n().status().get().outbox == 0 }
        val reply = send(b, mattOnSam, FbReply(ask.id, 1_790_100_000L, 1_790_100_000L + 6 * 3_600, listOf(Span(1_790_100_000L + 3_600, 1_790_100_000L + 5_400))))
        until("Matt's Robot got the answer") { a.sink.delivered.any { it.second.id == reply.id } }
        assertTrue(a.sink.delivered.single { it.second.id == reply.id }.second.body is FbReply)
        // Nothing went any other way.
        assertTrue(push.posts.none { it })
        idle(a, b)
        assertEquals(0, a.n().status().get().outbox)
        assertEquals(0, b.n().status().get().outbox)
    }

    @Test fun inPersonPrivateGoesDirectOnTheSameNetworkAndReconnectsWithItsToken() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val a = robot("Matt", lan, 1, tor, null)
        val b = robot("Sam", lan, 2, tor, null)
        val code = a.n().makeCode(LinkMode.PRIVATE, true).get(20, TimeUnit.SECONDS).code!!
        assertNotNull("in person, a Private code names this network", code.local)
        assertNotNull("and a one-time onion", code.onion)
        assertNull(code.push)
        assertNotNull(code.qrText())
        // Both photograph each other's code (L5-fix: a hello pairs only once its maker photographed the sender's code).
        val samsCode = b.n().makeCode(LinkMode.PRIVATE, true).get(20, TimeUnit.SECONDS).code!!
        assertTrue(b.n().useCode(code.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result in listOf(UseCodeResult.SENT, UseCodeResult.WAITING))
        // (Matt's photo pairs Sam's hello if it came first, else waits for it or sends, as the keys sort.)
        assertTrue(a.n().useCode(samsCode.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result in
            listOf(UseCodeResult.SENT, UseCodeResult.WAITING, UseCodeResult.VERIFIED))
        val samOnMatt = active(a)
        val mattOnSam = active(b)
        // The hello went over this network. (L5-fix, deliberately: the answer on the hello's connection, which hasn't proven
        // itself yet, goes by Tor too, so Tor may be dialled.)
        assertTrue(lan.dials.get() >= 1)
        // The used code's one-time onion is gone once used; the other one when its screen cancels it (PairScreen, once paired).
        until("the used code's onion taken down") { listOf(code, samsCode).any { !tor.has(it.onion!!) } }
        a.n().cancelCodes().get(10, TimeUnit.SECONDS); b.n().cancelCodes().get(10, TimeUnit.SECONDS)
        until("both codes' onions taken down") { listOf(code, samsCode).none { tor.has(it.onion!!) } }
        idle(a, b)
        val ev = send(a, samOnMatt, EvPropose("1".repeat(32), 1, EventFields("Dinner", listOf(1_790_186_400L, 1_790_272_800L), 90)))
        until("Sam's Robot got the proposal") { b.sink.delivered.any { it.second.id == ev.id } }
        until("acked") { a.sink.acked.any { it.second == ev.id } }
        // Every connection closes (both phones' Robots go idle); the next exchange opens a new one, with a connection hello
        // and the token the other Robot gave (an open connection is used both ways, whichever Robot dialled it).
        a.n().sleep(); b.n().sleep()
        idle(a, b)
        val before = lan.dials.get()
        val tokens = listOf(a.state.pairings.getValue(samOnMatt).inToken!!.copyOf(), b.state.pairings.getValue(mattOnSam).inToken!!.copyOf())
        a.n().wake(); b.n().wake()
        val eta = send(b, mattOnSam, EtaTell(10, null))
        until("Matt's Robot got the ETA") { a.sink.delivered.any { it.second.id == eta.id } }
        assertTrue("a new direct connection", lan.dials.get() > before)
        // Accepted with its token, which was then given anew (rotated each time it's used).
        idle(a, b)
        val now = listOf(a.state.pairings.getValue(samOnMatt).inToken!!, b.state.pairings.getValue(mattOnSam).inToken!!)
        assertTrue("a token rotated", !now[0].contentEquals(tokens[0]) || !now[1].contentEquals(tokens[1]))
        // (Tor may have carried something while a listener was coming back: a Private pairing's fallback, §2.2.)
        assertTrue((a.log.snapshot() + b.log.snapshot()).none { it.event == LinkEvent.DROPPED })
    }

    @Test fun anonymousNeverGoesDirectItsPushesGoThroughTorAndTorChecksClientAuthorization() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val push = FakePushServer()
        val a = robot("Matt", lan, 1, tor, push)
        val b = robot("Sam", lan, 2, tor, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b, LinkMode.ANONYMOUS)
        idle(a, b)
        assertTrue("posted through Tor", push.posts.isNotEmpty() && push.posts.all { it })
        assertEquals("never direct", 0, lan.dials.get())
        // Sam's push address goes: Matt's Robot reaches Sam's onion with the key Sam's Robot made for it.
        push.down = true
        val r = send(a, samOnMatt, RemOffer("Bring the charger", 1_790_100_000L))
        until("over Tor") { b.sink.delivered.any { it.second.id == r.id } }
        assertTrue(tor.dials.get() >= 1)
        assertEquals("only authorized clients", 0, tor.refused.get())
        until("acked over the open connection") { a.sink.acked.any { it.second == r.id } }
        // A Robot without the key is refused by the onion service.
        val stranger = tor.carrier().apply { up() }
        val samOnion = b.state.tor!!.onion
        assertTrue(runCatching { stranger.dial(samOnion, "x") }.exceptionOrNull() is SocksException)
        assertEquals(1, tor.refused.get())
        assertEquals(0, lan.dials.get())
        assertTrue(b.state.pairings.getValue(mattOnSam).peerAddrs.isEmpty())
    }

    @Test fun replayExpiryRateLimitsAndTheNetworkToggle() {
        val push = FakePushServer()
        val envA = FakeEnv(t0)
        val envB = FakeEnv(t0)
        val a = robot("Matt", null, 1, null, push, envA)
        val b = robot("Sam", null, 2, null, push, envB)
        val (samOnMatt, _) = pairByPush(a, b)
        idle(a, b)
        // Expired on arrival (a slow push server held it past its lifetime): answered `expired`, never handed on.
        push.hold = true
        val old = send(a, samOnMatt, EtaTell(5, null), ttl = 60)
        idle(a)
        envA.clock.addAndGet(120_000); envB.clock.addAndGet(120_000)
        push.hold = false
        push.release()
        until("answered expired") { a.sink.acked.any { it.second == old.id && it.third == AckState.EXPIRED } }
        assertTrue(b.sink.delivered.none { it.second.id == old.id })
        // The same request again (a copy the push server sent twice): refused as a repeat, never handed on twice.
        val eta = send(a, samOnMatt, EtaTell(6, null))
        until("delivered") { b.sink.delivered.any { it.second.id == eta.id } }
        val samPush = a.state.pairings.getValue(samOnMatt).peerPush!!
        val copy = synchronized(push.sentTo) { push.sentTo.last { it.first == samPush.endpoint }.second }
        push.poster.post(samPush, copy, 60, false, "x")
        idle(a, b)
        assertTrue(b.log.snapshot().any { it.event == LinkEvent.UNDECRYPTED })
        assertEquals(1, b.sink.delivered.count { it.second.id == eta.id })
        // The Network toggle off: nothing goes, and it waits; back on, it goes.
        envA.network.set(false)
        val held = send(a, samOnMatt, EtaTell(7, null))
        idle(a, b)
        assertTrue(b.sink.delivered.none { it.second.id == held.id })
        assertTrue(a.log.snapshot().any { it.event == LinkEvent.NO_NETWORK })
        envA.network.set(true)
        idle(a, b)
        until("sent once the toggle is back") { b.sink.delivered.any { it.second.id == held.id } }
        // 70 in an hour: what's left of the 60 handed on, then busy, one pause noticed. What Matt's Robot sent before (its
        // caps and net.hint, the ETAs above) counts too; acks never do.
        idle(a, b)
        val counted = b.log.snapshot().count { it.event == LinkEvent.REQUEST_IN && it.a != RequestType.ACK.code && it.a != RequestType.UNPAIR.code }
        assertTrue(counted in 3..20)
        val sent = (1..70).map { send(a, samOnMatt, EtaTell(it.toLong(), null)) }
        until("all answered", 30_000) { sent.all { s -> a.sink.acked.any { it.second == s.id } } }
        val handed = sent.count { s -> b.sink.delivered.any { it.second.id == s.id } }
        val busy = sent.count { s -> a.sink.acked.any { it.second == s.id && it.third == AckState.BUSY } }
        assertEquals(60 - counted, handed)
        assertEquals(70 - handed, busy)
        assertEquals(1, b.sink.busy.size)
    }

    /**
     * L5-fix: SEND (queue) is for requests only. An `unpair` queued from outside is refused (it would end the pairing on
     * their side alone), as are a request's bytes queued under another type's code.
     */
    @Test fun sendIsForRequestsOnly() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        fun queue(body: LinkBody, type: RequestType = body.type): QueueProblem? {
            val r = LinkRequest.of(body, a.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
            return a.n().queue(samOnMatt, type.code, r.id, r.at, r.ttl, LinkRequests.encode(r, LinkRequests.LIVE)!!).get(10, TimeUnit.SECONDS)
        }
        for (control in listOf(Unpair(), ModeRaise(), ModeLower(), Caps.of("Matt"), Ack(LinkIds.newId(), AckState.RECEIVED), NetHint(LinkMode.PRIVATE, token = LinkIds.newId())))
            assertEquals(control.type.wire, QueueProblem.BAD_REQUEST, queue(control))
        assertEquals(QueueProblem.BAD_REQUEST, queue(EtaTell(4, null), RequestType.FB_ASK))
        // The same request, queued under its own fields: it goes.
        assertNull(queue(EtaTell(4, null)))
        idle(a, b)
        assertEquals(PairingState.ACTIVE, b.n().pairings().get()[mattOnSam])
        assertEquals(PairingState.ACTIVE, a.n().pairings().get()[samOnMatt])
    }

    @Test fun theOutboxBacksOffAndGivesUpAfterItsLifetime() {
        val push = FakePushServer()
        val envA = FakeEnv(t0)
        val a = robot("Matt", null, 1, null, push, envA)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, _) = pairByPush(a, b)
        idle(a, b)
        push.down = true
        val r = send(a, samOnMatt, EtaTell(3, null), ttl = 3_600)
        idle(a)
        val first = a.state.outbox.getValue(r.id)
        assertEquals(1, first.attempts)
        assertEquals(t0 / 1000 + 60, first.nextAt)
        envA.clock.addAndGet(61_000); idle(a)
        assertEquals(2, a.state.outbox.getValue(r.id).attempts)
        assertEquals(t0 / 1000 + 61 + 120, a.state.outbox.getValue(r.id).nextAt)
        envA.clock.addAndGet(3_700_000); idle(a)
        assertFalse(a.state.outbox.containsKey(r.id))
        assertEquals(listOf(r.id), a.sink.gaveUp)
        assertTrue(a.log.snapshot().any { it.event == LinkEvent.EXPIRED })
    }

    @Test fun aCodeWorksOnceAndClosesAfterFiveWrongTries() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val c = robot("Bob", null, 3, null, push)
        val code = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        active(a)
        // A second Robot with the same code: refused without a reply (the code's one-time push address is gone too).
        c.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
        idle(a, c)
        Thread.sleep(300)
        assertEquals(1, a.n().pairings().get().size)
        assertTrue(c.n().pairings().get().values.none { it == PairingState.ACTIVE })
        // Five wrong tokens close a code: a forged code with this code's routes and identity but another token.
        val code2 = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        // L5: each a different token (the same code pasted again reuses the hello already waiting, and sends none).
        repeat(5) { n ->
            val forged = PairCode(code2.mode, code2.identity, ByteArray(16) { (it + 1 + 16 * n).toByte() }, code2.expires, code2.push, code2.onion, code2.local)
            assertTrue(PairCode.read(forged.text(), PairCodeForm.PASTED, t0 / 1000) is PairCodeRead.Ok)
            c.n().useCode(forged.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
            idle(a, c)
        }
        until("the code closed") { a.state.codes.isEmpty() }
        assertTrue(a.log.snapshot().count { it.event == LinkEvent.HELLO && it.a == 3 } >= 5)
        // The right token now: too late.
        b.n().useCode(code2.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
        idle(a, b)
        Thread.sleep(300)
        assertEquals(1, a.n().pairings().get().size)
    }

    @Test fun unpairEndsThePairingOnBothSides() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        assertTrue(a.n().unpair(samOnMatt).get(10, TimeUnit.SECONDS))
        until("Sam's Robot saw it") { b.sink.unpaired == listOf(mattOnSam) }
        assertTrue(a.n().pairings().get().isEmpty())
        assertFalse(a.stores.hasSession(samOnMatt))
        assertFalse(b.stores.hasSession(mattOnSam))
        // Nothing of it stays on Matt's storage but libsignal's own prekeys and identity.
        assertTrue(a.records.keys().none { it.id == samOnMatt })
        // L5 (deliberately changed from "nothing on either side"): Sam's side keeps a note that Matt's Robot unpaired, ENDED,
        // with no session, pinned key, token, route or traffic: its pairing record alone.
        until("ended on Sam's side") { b.n().pairings().get() == mapOf(mattOnSam to PairingState.ENDED) }
        assertEquals(listOf(LinkPurpose.PAIRING), b.records.keys().filter { it.id == mattOnSam }.map { it.purpose })
        val note = b.state.pairings.getValue(mattOnSam)
        assertTrue(note.peerPush == null && note.peerOnion == null && note.outToken == null && note.inToken == null && note.myInstance == null &&
            note.myAuthPriv == null && note.movedIn == null && note.movedOut == null)
        assertNotNull("their key, for Block", note.peerIdentity)
    }

    /** L5-fix: Start over tells every paired Robot first: each one's pairing ends (ENDED), as after Unpair. */
    @Test fun startOverTellsEveryPairedRobot() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val c = robot("Bob", null, 3, null, push)
        val (_, mattOnSam) = pairByPush(a, b)
        val made = c.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, a.n().useCode(made.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val mattOnBob = active(c)
        until("both of Matt's pairings active") { a.n().pairings().get().values.count { it == PairingState.ACTIVE } == 2 }
        idle(a, b, c)
        a.n().unpairAll().get(20, TimeUnit.SECONDS)
        until("Sam's and Bob's sides ended") { b.n().pairings().get()[mattOnSam] == PairingState.ENDED && c.n().pairings().get()[mattOnBob] == PairingState.ENDED }
        assertEquals(listOf(mattOnSam), b.sink.unpaired)
        assertEquals(listOf(mattOnBob), c.sink.unpaired)
    }

    @Test fun aMarkerNameAndTitleNeverReachALog() {
        RLog.sink = { logLines += it }
        val push = FakePushServer()
        val tor = FakeTorNetwork()
        val a = robot("zq9marker", null, 1, tor, push)
        val b = robot("zq9marker3", null, 2, tor, push)
        val (samOnMatt, _) = pairByPush(a, b)
        idle(a, b)
        val ev = send(a, samOnMatt, EvPropose("2".repeat(32), 1, EventFields("zq9marker2", listOf(1_790_186_400L), 60, "zq9marker4", "zq9marker5")))
        until("delivered") { b.sink.delivered.any { it.second.id == ev.id } }
        idle(a, b)
        val everything = (a.log.snapshot() + b.log.snapshot()).joinToString("\n") + logLines.joinToString("\n") +
            listOf(a.state, b.state).flatMap { it.pairings.values + it.outbox.values + it.codes.values }.joinToString("\n")
        assertFalse(everything.contains("zq9marker"))
        assertFalse(everything.contains("push.example.org"))
        assertTrue(a.log.snapshot().isNotEmpty())
    }
}
