// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.CodeState
import app.heyrobot.core.link.EnvelopeKind
import app.heyrobot.core.link.HelloPurpose
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkCarrier
import app.heyrobot.core.link.LinkEnvelope
import app.heyrobot.core.link.LinkEvent
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.LinkWay
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.PairCode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairHello
import app.heyrobot.core.link.PairStep
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.PushRoute
import app.heyrobot.core.link.SafetyNumber
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.Verified
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkCaps
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 1.5, Robot to Robot (robot-to-robot-plan.md §2.3, §2.6, §2.7, §6.2, §6.3; job L5): pairing as people use it, two or three
 * complete link stacks in one JVM (§8.3). In person, both photograph each other's code and only the key that sorts first
 * sends, in either order, and a hello from another key than the one photographed is refused; at a distance, the safety
 * number is the same on both phones and "They match" verifies; who it is in People, presets and levels; raising at once and
 * lowering only when both agree; Block drops a key's hellos before they touch a code; their Unpair leaves an ENDED note for
 * 30 days; a moved hello carries the token given inside the session, pauses the pairing until accepted or kept, and an
 * accepted move gives a new session the old phone can't use.
 */
class LinkPairingHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()

    @After fun tearDown() {
        for (r in robots) r.node?.close()
    }

    private fun robot(name: String, lan: LoopbackLan?, n: Int, tor: FakeTorNetwork?, push: FakePushServer?, env: FakeEnv = FakeEnv(t0),
        before: (TestRobot) -> Unit = {}): TestRobot =
        TestRobot(tmp.newFolder(), env, lan, n, tor, push, name).also { robots += it; before(it); it.n().start(); it.n().wake() }

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
    private fun view(r: TestRobot, id: String): PairingView = views(r).single { it.id == id }
    private fun active(r: TestRobot): String {
        until("an active pairing") { views(r).any { it.phase == PairingPhase.ACTIVE } }
        return views(r).first { it.phase == PairingPhase.ACTIVE }.id
    }

    private fun send(from: TestRobot, pairing: String, body: LinkBody): LinkRequest {
        val r = LinkRequest.of(body, from.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r, LinkRequests.LIVE)!!).get(10, TimeUnit.SECONDS))
        return r
    }

    private fun pairByPush(a: TestRobot, b: TestRobot, mode: LinkMode = LinkMode.PRIVATE): Pair<String, String> {
        val made = a.n().makeCode(mode, false).get(20, TimeUnit.SECONDS)
        assertEquals(MakeCodeResult.OK, made.result)
        val used = b.n().useCode("Here's my code: ${made.code!!.text()}", PairCodeForm.PASTED, mode).get(30, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, used.result)
        val onA = active(a)
        until("Sam's side answered") { views(b).any { it.id == used.pairing && it.phase == PairingPhase.ACTIVE } }
        // Both sides' net.hints carried their moved tokens.
        until("moved tokens given both ways") { a.state.pairings.getValue(onA).movedOut != null && b.state.pairings.getValue(used.pairing!!).movedOut != null }
        return onA to used.pairing!!
    }

    /** The two Robots in the order their identity keys sort: the first one sends in person (§2.3). */
    private fun sorted(a: TestRobot, b: TestRobot): Pair<TestRobot, TestRobot> = if (PairCode.firstSends(a.stores.identityKey, b.stores.identityKey) == true) a to b else b to a

    private fun inPersonCode(r: TestRobot): MadeCode = r.n().makeCode(LinkMode.PRIVATE, true).get(20, TimeUnit.SECONDS).also {
        assertEquals(MakeCodeResult.OK, it.result)
        assertNotNull(it.code!!.qrText())
    }

    @Test fun inPersonTheSecondPhotoWaitsAndOnePairingIsVerifiedOnBoth() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val (first, second) = sorted(robot("Matt", lan, 1, tor, null), robot("Sam", lan, 2, tor, null))
        val fCode = inPersonCode(first)
        val sCode = inPersonCode(second)
        // The Robot whose key sorts second photographs first: it sends nothing, and keeps the key it saw on its own code.
        assertEquals(UseCodeResult.WAITING, second.n().useCode(fCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        assertTrue(views(second).isEmpty())
        assertTrue(second.state.codes.getValue(sCode.id!!).expect!!.contentEquals(first.stores.identityKey))
        // Then the other photo: that one sends, to the code it photographed.
        val used = first.n().useCode(sCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, used.result)
        val onSecond = active(second)
        until("the first one's answered") { views(first).any { it.phase == PairingPhase.ACTIVE } }
        idle(first, second)
        // One pairing each, Verified in person on both, since when.
        assertEquals(1, views(first).size)
        assertEquals(1, views(second).size)
        for (v in views(first) + views(second)) {
            assertEquals(Verified.IN_PERSON, v.verified)
            assertEquals(t0 / 1000, v.verifiedAt)
            assertEquals(LinkMode.PRIVATE, v.mode)
        }
        assertEquals(CodeState.USED to onSecond, second.n().codeState(sCode.id!!).get())
        // Both safety numbers agree (in person nobody needs to compare them, but they're there).
        val n = first.n().safety(used.pairing!!).get()!!
        assertTrue(SafetyNumber.ok(n))
        assertEquals(n, second.n().safety(onSecond).get())
        assertTrue((first.log.snapshot() + second.log.snapshot()).any { it.event == LinkEvent.PAIR && it.a == PairStep.SCAN_KEPT.code })
    }

    /**
     * L5-fix (deliberately changed from "the second pairs at once and its photo verifies it later"): the first photo sends,
     * and the hello waits on the second Robot, unanswered, until its own photo says whose it is; then one pairing, Verified
     * in person on both, with no second hello.
     */
    @Test fun inPersonTheFirstPhotoSendsAndTheSecondsPhotoPairsTheHeldHello() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val (first, second) = sorted(robot("Matt", lan, 1, tor, null), robot("Sam", lan, 2, tor, null))
        val fCode = inPersonCode(first)
        val sCode = inPersonCode(second)
        assertEquals(UseCodeResult.SENT, first.n().useCode(sCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        until("held on the second") { second.n().codeState(sCode.id!!).get().first == CodeState.HELD }
        idle(first, second)
        // Held: no pairing on the second, the code not spent, nothing answered.
        assertTrue(views(second).isEmpty())
        assertEquals(CodeState.HELD, second.n().codeState(sCode.id!!).get().first)
        assertEquals(0, second.state.codes.getValue(sCode.id!!).open.wrong)
        assertTrue(views(first).none { it.phase == PairingPhase.ACTIVE })
        assertTrue(second.log.snapshot().any { it.event == LinkEvent.PAIR && it.a == PairStep.HELD.code })
        val hellosBefore = first.log.snapshot().count { it.event == LinkEvent.HELLO && it.a == 1 }
        // The second's photo: the held hello pairs, Verified in person.
        val used = second.n().useCode(fCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.VERIFIED, used.result)
        val onSecond = used.pairing!!
        assertEquals(Verified.IN_PERSON, view(second, onSecond).verified)
        assertEquals(CodeState.USED to onSecond, second.n().codeState(sCode.id!!).get())
        until("the first answered") { views(first).any { it.phase == PairingPhase.ACTIVE } }
        idle(first, second)
        assertEquals(1, views(first).size)
        assertEquals(1, views(second).size)
        assertEquals(Verified.IN_PERSON, views(first).single().verified)
        assertEquals(hellosBefore, first.log.snapshot().count { it.event == LinkEvent.HELLO && it.a == 1 })
        // A code shown in person names this network: pasted, it is refused (L1's rule), and nothing changes.
        assertEquals(UseCodeResult.BAD, second.n().useCode(fCode.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        assertEquals(1, views(second).size)
    }

    /**
     * L5-fix (deliberately changed from "the code closes"): after your photo, a hello from another key than the one you
     * photographed is a wrong try; the code stays open and the real Robot's hello then pairs.
     */
    @Test fun aHelloFromAnotherKeyThanThePhotographedOneIsAWrongTryAndTheRealOneThenPairs() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val (first, second) = sorted(robot("Matt", lan, 1, tor, null), robot("Sam", lan, 2, tor, null))
        val eve = robot("Eve", lan, 3, tor, null)
        val fCode = inPersonCode(first)
        val sCode = inPersonCode(second)
        assertEquals(UseCodeResult.WAITING, second.n().useCode(fCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        // Someone else photographed the second one's code over a shoulder, and has no code of its own showing: it sends.
        assertEquals(UseCodeResult.SENT, eve.n().useCode(sCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        until("a wrong try") { second.state.codes.getValue(sCode.id!!).open.wrong == 1 }
        idle(second, eve)
        assertTrue(views(second).isEmpty())
        assertTrue(views(eve).none { it.phase == PairingPhase.ACTIVE })
        assertTrue(second.log.snapshot().any { it.event == LinkEvent.PAIR && it.a == PairStep.MISMATCH.code })
        assertEquals(CodeState.OPEN, second.n().codeState(sCode.id!!).get().first)
        // The real one's photo: its hello pairs, Verified in person on both.
        assertEquals(UseCodeResult.SENT, first.n().useCode(sCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val onSecond = active(second)
        assertEquals(CodeState.USED to onSecond, second.n().codeState(sCode.id!!).get())
        assertTrue(second.stores.identityOf(onSecond)!!.contentEquals(first.stores.identityKey))
        until("verified on the first") { views(first).any { it.phase == PairingPhase.ACTIVE && it.verified == Verified.IN_PERSON } }
        assertEquals(Verified.IN_PERSON, view(second, onSecond).verified)
    }

    /** L5-fix: someone photographs your code before you photograph theirs; only the Robot whose code you photograph pairs. */
    @Test fun aHelloBeforeYourPhotoNeverPairsAnotherKey() {
        val lan = LoopbackLan()
        val tor = FakeTorNetwork()
        val (first, second) = sorted(robot("Matt", lan, 1, tor, null), robot("Sam", lan, 2, tor, null))
        val eve = robot("Eve", lan, 3, tor, null)
        val fCode = inPersonCode(first)
        val sCode = inPersonCode(second)
        // Eve photographed Sam's code over a shoulder before Sam took his own photo: held, unanswered.
        assertEquals(UseCodeResult.SENT, eve.n().useCode(sCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        until("held") { second.n().codeState(sCode.id!!).get().first == CodeState.HELD }
        assertTrue(views(second).isEmpty())
        // Sam photographs Matt's code: Eve's held hello is refused, and Sam waits for Matt's (Matt's key sorts first).
        assertEquals(UseCodeResult.WAITING, second.n().useCode(fCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        assertEquals(1, second.state.codes.getValue(sCode.id!!).open.wrong)
        assertEquals(UseCodeResult.SENT, first.n().useCode(sCode.code!!.qrText()!!, PairCodeForm.SCANNED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        until("Matt's Robot paired") { views(first).any { it.phase == PairingPhase.ACTIVE } }
        // Eve's hello, sent again after its backoff, is refused too.
        later(3 * 60_000L, eve)
        idle(first, second, eve)
        val mine = views(second)
        assertEquals(1, mine.size)
        assertEquals(Verified.IN_PERSON, mine.single().verified)
        assertTrue(second.stores.identityOf(mine.single().id)!!.contentEquals(first.stores.identityKey))
        assertTrue(views(eve).none { it.phase == PairingPhase.ACTIVE })
    }

    /**
     * L5-fix: both paste each other's codes at the same moment (their hellos held by the push server, then let go together):
     * the Robot whose key sorts first keeps its own hello, the other answers it as the maker; one pairing each, both ways.
     */
    /**
     * L16, finding 6: the other Robot's real signed hello, re-sealed by a third party who holds your code's route and key but not
     * its token, is a wrong try. It doesn't count as crossing: your sent pairing stays and your code isn't tied to it.
     */
    @Test fun aHelloWithAWrongTokenIsNoCrossing_theKeyThatSendsFirst() = wrongTokenIsNoCrossing(true)
    @Test fun aHelloWithAWrongTokenIsNoCrossing_theKeyThatAnswers() = wrongTokenIsNoCrossing(false)

    private fun wrongTokenIsNoCrossing(first: Boolean) {
        val push = FakePushServer()
        val (x, y) = sorted(robot("Matt", null, 1, null, push), robot("Sam", null, 2, null, push))
        run {
            val (me, them) = if (first) x to y else y to x
            val theirs = them.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
            val mine = me.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
            push.hold = true
            assertEquals(UseCodeResult.SENT, me.n().useCode(theirs.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
            val sentPairing = views(me).single().id
            val code = mine.code!!
            val route = code.push!!
            val bundle = them.stores.bundle(them.stores.newKyberPreKey(t0), t0)
            val wrong = code.token.copyOf().also { it[0] = (it[0] + 1).toByte() }
            val hello = PairHello(HelloPurpose.PAIR, LinkMode.PRIVATE, wrong, bundle, PushRoute("https://push.example.org/up/x", ByteArray(65) { if (it == 0) 4 else 7 }, ByteArray(16) { 2 }), null, null, "Sam", LinkCaps.bitmap(LinkCaps.SUPPORTED))
            val signed = hello.signed(them.stores.signHello(hello.signedPart()))
            val crossings = me.log.snapshot().count { it.event == LinkEvent.PAIR && it.a == PairStep.CROSSED.code }
            push.hold = false
            push.poster.post(route, WebPush.seal(route, LinkEnvelope(EnvelopeKind.PAIR_HELLO, LinkEnvelope.NO_ROUTE, HelloSeal.seal(code.identity, signed.bytes())).encode(LinkCarrier.PUSH)), 60, false, "x")
            until("a wrong try on the code") { me.state.codes.getValue(mine.id!!).open.wrong == 1 }
            assertEquals(crossings, me.log.snapshot().count { it.event == LinkEvent.PAIR && it.a == PairStep.CROSSED.code })
            assertTrue(views(me).any { it.id == sentPairing })
            assertTrue(me.n().onLinkThread { me.state.pairings.containsKey(sentPairing) }.get(10, TimeUnit.SECONDS))
        }
    }

    @Test fun aCrossPasteEndsWithOnePairingThatWorksBothWays() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val ca = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        val cb = b.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        push.hold = true
        assertEquals(UseCodeResult.SENT, a.n().useCode(cb.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        assertEquals(UseCodeResult.SENT, b.n().useCode(ca.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        until("both hellos held by the push server") { push.posts.size >= 2 }
        push.hold = false
        push.release()
        until("one active pairing each") { idle(a, b); views(a).count { it.phase == PairingPhase.ACTIVE } == 1 && views(b).count { it.phase == PairingPhase.ACTIVE } == 1 }
        assertEquals(1, views(a).size)
        assertEquals(1, views(b).size)
        val onA = views(a).single().id
        val onB = views(b).single().id
        val e1 = send(a, onA, EtaTell(5, null))
        val e2 = send(b, onB, EtaTell(6, null))
        until("a request each way") { idle(a, b); b.sink.delivered.any { it.second.id == e1.id } && a.sink.delivered.any { it.second.id == e2.id } }
        // Both codes are done: the winner's was used by this pairing too, the other's by the winner's hello.
        val (first, second) = sorted(a, b)
        val firstsCode = if (first === a) ca else cb
        val secondsCode = if (first === a) cb else ca
        assertEquals(CodeState.USED, first.n().codeState(firstsCode.id!!).get().first)
        assertEquals(CodeState.USED, second.n().codeState(secondsCode.id!!).get().first)
        assertTrue(first.log.snapshot().any { it.event == LinkEvent.PAIR && it.a == PairStep.CROSSED.code && it.b == 1 })
        assertTrue(second.log.snapshot().any { it.event == LinkEvent.PAIR && it.a == PairStep.CROSSED.code && it.b == 0 })
        assertEquals(0, first.state.codes.values.sumOf { it.open.wrong })
    }

    /**
     * L5-fix: a hello carrying another Robot's genuine bundle (as copied out of an earlier hello) with the sender's own routes
     * is refused before it touches the code: no pairing, the code open, no try spent. The real Robot's hello then pairs.
     */
    @Test fun aHelloWithAnotherRobotsBundleIsRefusedBeforeItTouchesTheCode() {
        val push = FakePushServer()
        val sam = robot("Sam", null, 1, null, push)
        val matt = robot("Matt", null, 2, null, push)
        val eve = robot("Eve", null, 3, null, push)
        val made = sam.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        val code = made.code!!
        val route = code.push!!
        val mattsBundle = matt.stores.bundle(matt.stores.newKyberPreKey(t0), t0)
        val evesRoute = PushRoute("https://push.example.org/up/eve", ByteArray(65) { if (it == 0) 4 else 7 }, ByteArray(16) { 2 })
        val forged = PairHello(HelloPurpose.PAIR, LinkMode.PRIVATE, code.token, mattsBundle, evesRoute, null, null, "Matt", LinkCaps.bitmap(LinkCaps.SUPPORTED))
        fun deliver(h: PairHello) = push.poster.post(route, WebPush.seal(route, LinkEnvelope(EnvelopeKind.PAIR_HELLO, LinkEnvelope.NO_ROUTE,
            HelloSeal.seal(code.identity, h.bytes())).encode(LinkCarrier.PUSH)), 60, false, "x")
        // Signed by Eve (she can't sign as Matt), and not signed at all: both refused, nothing spent.
        deliver(forged.signed(eve.stores.signHello(forged.signedPart())))
        deliver(forged)
        until("both refused") { sam.log.snapshot().count { it.event == LinkEvent.HELLO && it.a == 3 && it.b == LinkProblem.BAD_SIGNATURE.code } == 2 }
        idle(sam)
        assertTrue(views(sam).isEmpty())
        assertEquals(CodeState.OPEN, sam.n().codeState(made.id!!).get().first)
        assertEquals(0, sam.state.codes.getValue(made.id!!).open.wrong)
        // Matt's own hello, signed by Matt: pairs, with Matt's key.
        assertEquals(UseCodeResult.SENT, matt.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val onSam = active(sam)
        assertTrue(sam.stores.identityOf(onSam)!!.contentEquals(matt.stores.identityKey))
    }

    @Test fun atADistanceTheSafetyNumberMatchesAndTheyMatchVerifies() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        val va = view(a, samOnMatt)
        assertEquals(Verified.NO, va.verified)
        assertEquals(0L, va.verifiedAt)
        assertEquals("Sam", va.claimed)
        assertEquals("Matt", view(b, mattOnSam).claimed)
        val n = a.n().safety(samOnMatt).get()!!
        assertEquals(60, n.length)
        assertEquals(12, SafetyNumber.groups(n).size)
        assertEquals(n, b.n().safety(mattOnSam).get())
        // A third Robot's number with Sam differs.
        val c = robot("Bob", null, 3, null, push)
        assertNotEquals(n, c.stores.safetyNumber(b.stores.identityKey))
        assertTrue(a.n().verify(samOnMatt).get())
        assertEquals(Verified.COMPARED, view(a, samOnMatt).verified)
        assertEquals(Verified.NO, view(b, mattOnSam).verified)
        assertFalse(a.n().verify(LinkIds.newId()).get())
        // The moved tokens: fresh, one each way, and never the code's.
        val pa = a.state.pairings.getValue(samOnMatt)
        val pb = b.state.pairings.getValue(mattOnSam)
        assertTrue(pa.movedIn!!.contentEquals(pb.movedOut!!))
        assertTrue(pb.movedIn!!.contentEquals(pa.movedOut!!))
        assertFalse(pa.movedIn!!.contentEquals(pb.movedIn!!))
    }

    @Test fun whoItIsPresetsAndLevelsStayInTheSealedStateOnly() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, _) = pairByPush(a, b)
        // Until a preset is picked, "Someone else"'s levels apply (D8, §4.6).
        val before = view(a, samOnMatt)
        assertNull(before.preset)
        assertEquals(LinkLevels.of(LinkPreset.SOMEONE_ELSE), before.levels)
        assertNull(before.person)
        assertFalse(a.n().setPerson(samOnMatt, "Sam Tremblay").get())
        val person = UUID.randomUUID().toString()
        assertTrue(a.n().setPerson(samOnMatt, person).get())
        assertTrue(a.n().setPreset(samOnMatt, LinkPreset.FAMILY_OR_FRIEND).get())
        assertEquals(LinkLevels.of(LinkPreset.FAMILY_OR_FRIEND), view(a, samOnMatt).levels)
        assertTrue(LinkLevels.of(LinkPreset.FAMILY_OR_FRIEND).values.none { it == LinkLevel.AUTO })
        // A tap may loosen to Answer on its own where it is allowed; never contact cards, never money.
        assertTrue(a.n().setLevel(samOnMatt, LinkCapability.FREE_BUSY, LinkLevel.AUTO).get())
        assertFalse(a.n().setLevel(samOnMatt, LinkCapability.CONTACT_CARDS, LinkLevel.AUTO).get())
        assertFalse(a.n().setLevel(samOnMatt, LinkCapability.PAYMENTS, LinkLevel.ASK).get())
        assertTrue(a.n().setLevel(samOnMatt, LinkCapability.FILES, LinkLevel.OFF).get())
        val after = view(a, samOnMatt)
        assertEquals(person, after.person)
        assertEquals(LinkPreset.FAMILY_OR_FRIEND, after.preset)
        assertEquals(LinkLevel.AUTO, after.levels[LinkCapability.FREE_BUSY])
        assertEquals(LinkLevel.OFF, after.levels[LinkCapability.FILES])
        // Written through: read back from the sealed records as the next :link start would.
        val again = LinkStateStore(a.records).also { it.load() }.pairings.getValue(samOnMatt)
        assertEquals(person, again.person)
        assertEquals(after.levels, again.levels)
        assertEquals(LinkPreset.FAMILY_OR_FRIEND, again.preset)
        // Nothing of it goes to the other Robot.
        assertNull(view(b, views(b).single().id).person)
    }

    @Test fun raisingIsAtOnceAndLoweringNeedsBoth() {
        val push = FakePushServer()
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, push)
        val b = robot("Sam", null, 2, tor, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        assertTrue(a.n().raise(samOnMatt).get())
        assertEquals(LinkMode.ANONYMOUS, view(a, samOnMatt).mode)
        until("raised on Sam's side") { view(b, mattOnSam).mode == LinkMode.ANONYMOUS }
        assertEquals(listOf(mattOnSam), b.sink.raised)
        // Matt asks to lower; Sam keeps Anonymous.
        assertTrue(a.n().lower(samOnMatt).get())
        assertTrue(view(a, samOnMatt).lowerAsked)
        until("Sam's card") { view(b, mattOnSam).lowerOffered }
        assertEquals(LinkMode.ANONYMOUS, view(a, samOnMatt).mode)
        assertTrue(b.n().keepMode(mattOnSam).get())
        until("Matt heard Sam kept it") { !view(a, samOnMatt).lowerAsked && view(a, samOnMatt).lowerDeclinedAt > 0 }
        assertEquals(LinkMode.ANONYMOUS, view(a, samOnMatt).mode)
        assertEquals(LinkMode.ANONYMOUS, view(b, mattOnSam).mode)
        // Asked again, and this time Sam agrees: Private on Sam's side at once, on Matt's when Sam's mode.lower arrives.
        assertTrue(a.n().lower(samOnMatt).get())
        until("Sam's card again") { view(b, mattOnSam).lowerOffered }
        assertTrue(b.n().lower(mattOnSam).get())
        assertEquals(LinkMode.PRIVATE, view(b, mattOnSam).mode)
        until("lowered on Matt's side") { view(a, samOnMatt).mode == LinkMode.PRIVATE }
        assertFalse(view(a, samOnMatt).lowerAsked)
        assertFalse(view(b, mattOnSam).lowerOffered)
        assertEquals(listOf(samOnMatt), a.sink.lowered)
    }

    /** L5-fix: "Raise to Anonymous" waits 30 days for a Robot that can't be reached, not one, so the two modes meet again. */
    @Test fun aRaiseWaitsThirtyDaysForARobotThatCantBeReached() {
        val push = FakePushServer()
        val tor = FakeTorNetwork()
        val env = FakeEnv(t0)
        val a = robot("Matt", null, 1, tor, push, env)
        val b = robot("Sam", null, 2, tor, push, env)
        val (onA, onB) = pairByPush(a, b)
        idle(a, b)
        // Sam's push server is down and Sam's Robot asleep (no onion) for 30 hours: Matt's raise can't reach it.
        push.down = true
        b.n().sleep()
        assertTrue(a.n().raise(onA).get())
        for (i in 1..30) { later(3_600_000L, a); a.n().pump().get(10, TimeUnit.SECONDS) }
        idle(a)
        assertEquals(LinkMode.PRIVATE, view(b, onB).mode)
        push.down = false
        b.n().wake()
        later(7 * 3_600_000L, a)
        until("raised on Sam's side") { idle(a, b); view(b, onB).mode == LinkMode.ANONYMOUS }
        assertEquals(listOf(onB), b.sink.raised)
        // Sam's next request to Matt goes to the push server through Tor.
        val before = push.posts.size
        val e = send(b, onB, EtaTell(9, null))
        until("Matt's Robot got it") { idle(a, b); a.sink.delivered.any { it.second.id == e.id } }
        val since = push.posts.drop(before)
        assertTrue(since.isNotEmpty() && since.all { it })
    }

    /** L5-fix: a raise answered `busy` (Matt passed 60 requests in the hour) is kept and goes again after the pause. */
    @Test fun aRaiseAnsweredBusyGoesAgainAfterThePause() {
        val push = FakePushServer()
        val tor = FakeTorNetwork()
        val env = FakeEnv(t0)
        val a = robot("Matt", null, 1, tor, push, env)
        val b = robot("Sam", null, 2, tor, push, env)
        val (onA, onB) = pairByPush(a, b)
        repeat(62) { send(a, onA, EtaTell((1 + it).toLong(), null)) }
        until("Sam's Robot paused Matt's") { idle(a, b); b.sink.busy.isNotEmpty() }
        assertTrue(a.n().raise(onA).get())
        idle(a, b); Thread.sleep(300); idle(a, b)
        assertEquals(LinkMode.PRIVATE, view(b, onB).mode)
        assertTrue("kept, to go again", a.state.outbox.values.any { it.type == RequestType.MODE_RAISE.code })
        later(2 * 3_600_000L, a)
        until("raised on Sam's side after the pause") { idle(a, b); view(b, onB).mode == LinkMode.ANONYMOUS }
        until("the raise answered") { idle(a, b); a.state.outbox.values.none { it.type == RequestType.MODE_RAISE.code } }
    }

    /** L5-fix: a pasted code whose mode byte was changed on the way (and its checksum made again): both end Anonymous. */
    @Test fun aCodesChangedModeEndsAnonymousOnBothSidesAfterTheFirstHints() {
        val push = FakePushServer()
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, push)
        val b = robot("Sam", null, 2, tor, push)
        val made = a.n().makeCode(LinkMode.ANONYMOUS, false).get(20, TimeUnit.SECONDS)
        val bytes = made.code!!.bytes()
        val body = bytes.copyOf(bytes.size - PairCode.CHECKSUM)
        assertEquals(LinkMode.ANONYMOUS.code, body[1].toInt())
        body[1] = LinkMode.PRIVATE.code.toByte()
        val changed = PairCode.PREFIX + LinkBytes.b64(body + LinkBytes.sha256(body).copyOf(PairCode.CHECKSUM))
        val used = b.n().useCode(changed, PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, used.result)
        val onA = active(a)
        assertEquals(LinkMode.ANONYMOUS, view(a, onA).mode)
        until("both Anonymous") { idle(a, b); view(b, used.pairing!!).phase == PairingPhase.ACTIVE && view(b, used.pairing!!).mode == LinkMode.ANONYMOUS }
        assertEquals(LinkMode.ANONYMOUS, view(a, onA).mode)
        assertEquals(listOf(used.pairing!!), b.sink.raised)
    }

    /** L5-fix: a hint with addresses (from a side still Private) on an Anonymous pairing: its token taken, never its addresses. */
    @Test fun aHintWithAddressesOnAnAnonymousPairingIsTakenWithoutThem() {
        val lan = LoopbackLan()
        val push = FakePushServer()
        val tor = FakeTorNetwork()
        val a = robot("Matt", lan, 1, tor, push)
        val b = robot("Sam", lan, 2, tor, push)
        val (onA, onB) = pairByPush(a, b, LinkMode.ANONYMOUS)
        idle(a, b)
        // Sam's side drifted to Private with no token given out; listening again, it gives one, with its addresses.
        b.n().settle().get()
        b.state.pairings.getValue(onB).apply { mode = LinkMode.PRIVATE; inToken = null; inPrev = null }
        b.n().sleep(); idle(b)
        b.n().wake()
        until("Sam's new token reached Matt") { idle(a, b); b.state.pairings.getValue(onB).inToken?.let { t -> a.state.pairings.getValue(onA).outToken?.contentEquals(t) } == true }
        assertTrue(a.state.pairings.getValue(onA).peerAddrs.isEmpty())
        assertTrue(b.sink.acked.none { it.third == AckState.DECLINED })
        assertEquals(LinkMode.ANONYMOUS, view(a, onA).mode)
        // And Matt's hints, Anonymous, bring Sam's side back.
        until("Sam's side Anonymous again") { idle(a, b); view(b, onB).mode == LinkMode.ANONYMOUS }
        assertEquals(0, lan.dials.get())
    }

    @Test fun blockDropsTheirHellosBeforeTheyTouchACodeAndUnblockLetsThemIn() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        assertTrue(a.n().block(samOnMatt, "Sam from work").get())
        assertTrue(views(a).isEmpty())
        assertEquals(listOf("Sam from work"), a.n().blockedList().get().map { it.label })
        until("Sam's side ended") { view(b, mattOnSam).phase == PairingPhase.ENDED }
        // A new code from Matt, pasted by Sam: Sam's hello is dropped unread; the code stays open with no wrong try spent.
        val made = a.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS)
        val used = b.n().useCode(made.code!!.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS)
        assertEquals(UseCodeResult.SENT, used.result)
        until("dropped") { a.log.snapshot().any { it.event == LinkEvent.PAIR && it.a == PairStep.BLOCKED_HELLO.code } }
        idle(a, b)
        assertTrue(views(a).isEmpty())
        assertEquals(CodeState.OPEN, a.n().codeState(made.id!!).get().first)
        assertEquals(0, a.state.codes.getValue(made.id!!).open.wrong)
        // Matt can't pair with a code of Sam's either while Sam is blocked.
        val samsCode = b.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.BLOCKED, a.n().useCode(samsCode.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        // Unblocked: Sam's hello, sent again after its backoff, now pairs.
        assertTrue(a.n().unblock(a.n().blockedList().get().single().id).get())
        assertTrue(a.n().blockedList().get().isEmpty())
        a.env.clock.addAndGet(2 * 60_000L); b.env.clock.addAndGet(2 * 60_000L)
        b.n().pump().get(10, TimeUnit.SECONDS)
        active(a)
        until("Sam paired again") { views(b).any { it.id == used.pairing && it.phase == PairingPhase.ACTIVE } }
    }

    @Test fun theirUnpairLeavesAnEndedNoteForThirtyDaysThatCanStillBeBlocked() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        assertTrue(b.n().setPerson(mattOnSam, UUID.randomUUID().toString()).get())
        assertTrue(a.n().unpair(samOnMatt).get())
        until("ended") { view(b, mattOnSam).phase == PairingPhase.ENDED }
        val note = view(b, mattOnSam)
        assertNotNull(note.person)
        assertEquals(t0 / 1000, note.since)
        assertNull(b.n().safety(mattOnSam).get())
        assertFalse(b.n().raise(mattOnSam).get())
        // A note counts toward nothing: Sam can pair again at once.
        // Block works on it (their key was kept), and Remove (Unpair) deletes it.
        assertTrue(b.n().block(mattOnSam, "Matt").get())
        assertTrue(views(b).isEmpty())
        assertEquals(1, b.n().blockedList().get().size)
        // Another note, gone by itself after 30 days.
        val c = robot("Bob", null, 3, null, push, env = b.env)
        val (bobOnSam, _) = pairByPush(b, c)
        assertTrue(c.n().unpair(views(c).single().id).get())
        until("ended") { view(b, bobOnSam).phase == PairingPhase.ENDED }
        b.env.clock.addAndGet(31L * 86_400_000L)
        b.n().pump().get(10, TimeUnit.SECONDS)
        assertTrue(views(b).none { it.id == bobOnSam })
    }

    /** A Robot restored on a new phone (L12 makes this record from the backup): a MOVING copy of [old], no session. */
    private fun moved(from: TestRobot, old: String, peer: TestRobot, push: FakePushServer, tor: FakeTorNetwork?, token: ByteArray? = null): Pair<TestRobot, String> {
        val o = from.state.pairings.getValue(old)
        val id = LinkIds.newId()
        val r = robot(from.name, null, 9, tor, push, env = from.env) { n ->
            val q = PairingRecord(id, PairingState.MOVING, o.mode, o.created, o.name, o.caps)
            q.peerPush = o.peerPush; q.peerOnion = o.peerOnion; q.peerOnionAuth = o.peerOnionAuth
            q.movedOut = (token ?: o.movedOut)!!.copyOf()
            q.person = o.person; q.levels = o.levels; q.preset = o.preset
            n.stores.pin(id, peer.stores.identityKey)
            n.state.put(q)
        }
        return r to id
    }

    /**
     * L12, §2.7, D14: the whole way, through the file itself. Export, read back, restore on a new Robot: paused, no session, Ask me
     * first, not verified, and nothing sent. "Tell my Robots I moved" reaches Sam; once Sam accepts, it is live again.
     */
    @Test fun aRestoredBackupWaitsForYourTapThenMovesWithLevelsDown() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        assertTrue(a.n().setLevel(samOnMatt, LinkCapability.FREE_BUSY, LinkLevel.AUTO).get())
        val text = app.heyrobot.core.link.LinkBackupCodec.encode(a.n().backupExport().get()!!)
        val file = (app.heyrobot.core.link.LinkBackupCodec.decode(text) as app.heyrobot.core.link.LinkBackupRead.Ok).file
        assertEquals(LinkLevel.AUTO, file.pairings.single().levels[LinkCapability.FREE_BUSY])

        val a2 = robot("Matt", null, 9, null, push, env = a.env)
        assertTrue(a2.n().restoreBackup(file).get())
        val restored = view(a2, samOnMatt)
        assertEquals(PairingPhase.MOVING, restored.phase)
        assertEquals(LinkLevel.ASK, restored.levels[LinkCapability.FREE_BUSY])
        assertEquals(Verified.NO, restored.verified)
        assertEquals(a.state.pairings.getValue(samOnMatt).name, a2.state.pairings.getValue(samOnMatt).name)
        // Nothing has gone: Sam's Robot hears of it only from your tap, and a second run changes nothing.
        idle(a2, b)
        assertEquals(emptyList<String>(), b.sink.moved)
        assertEquals(PairingPhase.ACTIVE, view(b, mattOnSam).phase)
        assertTrue(a2.n().restoreBackup(file).get())
        assertEquals(1, views(a2).size)

        assertTrue(a2.n().sendMoved(samOnMatt).get(30, TimeUnit.SECONDS))
        until("Sam's card") { b.sink.moved == listOf(mattOnSam) }
        assertEquals(PairingPhase.MOVED, view(b, mattOnSam).phase)
        assertTrue(b.n().answerMoved(mattOnSam, true).get())
        until("the new phone heard") { view(a2, samOnMatt).phase == PairingPhase.ACTIVE }
        // Live again, and Sam's trust wasn't brought along: free/busy still asks here.
        assertEquals(LinkLevel.ASK, view(a2, samOnMatt).levels[LinkCapability.FREE_BUSY])
    }

    /** D14: none of what the pairing's record holds as a secret is in the file: tokens, client authorization, the onion. */
    @Test fun aBackupHoldsNoneOfThePairingsSecrets() {
        val tor = FakeTorNetwork()
        val push = FakePushServer()
        val a = robot("Matt", null, 1, tor, push)
        val b = robot("Sam", null, 2, tor, push)
        val (samOnMatt, _) = pairByPush(a, b)
        idle(a, b)
        val p = a.state.pairings.getValue(samOnMatt)
        val secrets = listOfNotNull(p.outToken, p.inToken, p.inPrev, p.myAuthPriv, p.myAuthPub, p.peerOnionAuth, p.code, p.hello, p.movedIn, a.state.tor?.keyBlob?.toByteArray())
        assertTrue("the pairing holds some", secrets.isNotEmpty())
        val text = app.heyrobot.core.link.LinkBackupCodec.encode(a.n().backupExport().get()!!)
        for (k in secrets) assertFalse(text.contains(app.heyrobot.core.link.LinkBytes.hex(k)))
        a.state.tor?.let { assertFalse(text.contains(it.keyBlob)) }
        // A pairing with only an onion can't be told: its client authorization stays behind, so it pairs again.
        val f = (app.heyrobot.core.link.LinkBackupCodec.decode(text) as app.heyrobot.core.link.LinkBackupRead.Ok).file
        val one = f.pairings.single()
        val noPush = app.heyrobot.core.link.LinkBackupFile(listOf(app.heyrobot.core.link.LinkPairingBackup(one.id, one.mode, one.created, one.name, one.caps, one.identity, null, null, null, one.levels, one.movedOut)))
        val a2 = robot("Matt", null, 9, tor, push, env = a.env)
        assertTrue(a2.n().restoreBackup(noPush).get())
        assertEquals(false, a2.n().sendMoved(one.id).get(30, TimeUnit.SECONDS))
    }

    /** F131: "Tell my Robots I moved" a week later really sends again: it gets a new week of tries. */
    @Test fun tellingThemAgainAfterAWeekSends() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        val (a2, onA2) = moved(a, samOnMatt, b, push, null)
        assertTrue(a2.n().sendMoved(onA2).get(30, TimeUnit.SECONDS))
        until("Sam's card") { b.sink.moved == listOf(mattOnSam) }
        idle(a2, b)
        later((LinkNode.MOVE_DAYS + 1) * 86_400_000L, a2)
        val before = push.posts.size
        assertTrue(a2.n().sendMoved(onA2).get(30, TimeUnit.SECONDS))
        until("the hello posted again") { push.posts.size > before }
        idle(a2)
        assertTrue(a2.state.pairings.getValue(onA2).hello != null)
    }

    /** Restoring never reopens a door: someone you blocked here stays out, and your own key isn't a person. */
    @Test fun aRestoreSkipsWhoIsBlockedHereAndWhatIsAlreadyHere() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, _) = pairByPush(a, b)
        idle(a, b)
        val file = a.n().backupExport().get()!!
        // Already here: untouched, and still live.
        assertTrue(a.n().restoreBackup(file).get())
        assertEquals(PairingPhase.ACTIVE, view(a, samOnMatt).phase)
        // Nor can a file's other key take over a pairing that is here by its id.
        val p = file.pairings.single()
        val other = app.heyrobot.core.link.LinkBackupFile(listOf(app.heyrobot.core.link.LinkPairingBackup(p.id, p.mode, p.created, "Other", p.caps, ByteArray(33) { 5 }, p.push, null, null, p.levels, p.movedOut)))
        val theirs = a.n().safety(samOnMatt).get()
        assertTrue(a.n().restoreBackup(other).get())
        assertEquals(PairingPhase.ACTIVE, view(a, samOnMatt).phase)
        assertEquals(theirs, a.n().safety(samOnMatt).get())
        // Blocked on the new phone: skipped.
        val a2 = robot("Matt", null, 9, null, push, env = a.env) { n -> n.state.put(BlockRecord(LinkIds.newId(), b.stores.identityKey, "", 1)) }
        assertTrue(a2.n().restoreBackup(file).get())
        assertEquals(0, views(a2).size)
        // This Robot's own key: not a person.
        val b2 = robot("Sam", null, 8, null, push, env = a.env)
        val own = file.pairings.single()
        val mine = app.heyrobot.core.link.LinkBackupFile(listOf(app.heyrobot.core.link.LinkPairingBackup(own.id, own.mode, own.created, own.name, own.caps, b2.stores.identityKey, own.push, null, null, own.levels, own.movedOut)))
        assertTrue(b2.n().restoreBackup(mine).get())
        assertEquals(0, views(b2).size)
    }

    @Test fun aMovedHelloPausesThePairingUntilAcceptedThenTheOldPhoneIsOut() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        val oldNumber = b.n().safety(mattOnSam).get()!!
        val samsToken = b.state.pairings.getValue(mattOnSam).movedIn!!.copyOf()
        val (a2, onA2) = moved(a, samOnMatt, b, push, null)
        assertTrue(a2.n().sendMoved(onA2).get(30, TimeUnit.SECONDS))
        until("Sam's card") { b.sink.moved == listOf(mattOnSam) }
        val paused = view(b, mattOnSam)
        assertEquals(PairingPhase.MOVED, paused.phase)
        assertEquals("Matt", paused.movedClaimed)
        assertEquals(PairingPhase.MOVING, view(a2, onA2).phase)
        // Paused: nothing can be sent to Matt, and the number to compare is the new phone's.
        val r = LinkRequest.of(EtaTell(5, null), b.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertEquals(QueueProblem.NO_PAIRING, b.n().queue(mattOnSam, r.body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r, LinkRequests.LIVE)!!).get())
        val newNumber = b.n().safety(mattOnSam).get()!!
        assertNotEquals(oldNumber, newNumber)
        assertEquals(newNumber, a2.n().safety(onA2).get())
        // Accepted after "They match": a new session with the new phone, verified, new moved tokens both ways.
        assertTrue(b.n().answerMoved(mattOnSam, true).get())
        assertEquals(Verified.COMPARED, view(b, mattOnSam).verified)
        until("the new phone heard") { view(a2, onA2).phase == PairingPhase.ACTIVE }
        idle(a2, b)
        until("new tokens") { a2.state.pairings.getValue(onA2).movedOut?.contentEquals(b.state.pairings.getValue(mattOnSam).movedIn!!) == true }
        assertFalse(b.state.pairings.getValue(mattOnSam).movedIn!!.contentEquals(samsToken))
        val ask = send(a2, onA2, FbAsk(1_790_100_000L, 1_790_100_000L + 3_600))
        until("Sam got the new phone's question") { b.sink.delivered.any { it.second.id == ask.id } }
        val eta = send(b, mattOnSam, EtaTell(10, null))
        until("the new phone got Sam's") { a2.sink.delivered.any { it.second.id == eta.id } }
        // The old phone is out: L5-fix, its push address for Sam is gone (deliberately changed from "what it sends doesn't
        // decrypt": it no longer reaches Sam's Robot at all), and nothing of it is handed on.
        val stale = send(a, samOnMatt, EtaTell(3, null))
        until("the old push address gone") { idle(a); a.log.snapshot().any { it.event == LinkEvent.SEND_FAILED && it.a == LinkWay.PUSH.code && it.b == 10 + PushProblem.GONE.code } }
        idle(a, b)
        assertTrue(b.sink.delivered.none { it.second.id == stale.id })
        assertEquals(PairingPhase.ACTIVE, view(b, mattOnSam).phase)
    }

    /**
     * L5-fix: an accepted move cuts every route the old phone had to Sam: a new push address and a new client key for Sam's
     * onion go to the new phone; the old one's post gets GONE, its onion stream is refused, and nothing it sends is read.
     */
    @Test fun anAcceptedMoveCutsTheOldPhonesRoutes() {
        val push = FakePushServer()
        val tor = FakeTorNetwork()
        val a = robot("Matt", null, 1, tor, push)
        val b = robot("Sam", null, 2, tor, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        until("Matt holds Sam's onion") { a.state.pairings.getValue(samOnMatt).peerOnion != null }
        val oldPush = b.state.pairings.getValue(mattOnSam).myPush!!
        val oldAuth = b.state.pairings.getValue(mattOnSam).myAuthPub!!.copyOf()
        val (a2, onA2) = moved(a, samOnMatt, b, push, tor)
        assertTrue(a2.n().sendMoved(onA2).get(30, TimeUnit.SECONDS))
        until("Sam's card") { b.sink.moved == listOf(mattOnSam) }
        assertTrue(b.n().answerMoved(mattOnSam, true).get())
        until("the new phone heard") { view(a2, onA2).phase == PairingPhase.ACTIVE }
        until("the new phone has Sam's new push address and onion key") {
            idle(a2, b)
            val q = a2.state.pairings.getValue(onA2); val p = b.state.pairings.getValue(mattOnSam)
            q.peerPush != null && q.peerPush!!.endpoint != oldPush.endpoint && q.peerOnionAuth != null && p.myAuthPub != null && !p.myAuthPub!!.contentEquals(oldAuth)
        }
        // The old phone tries both: Sam's old push address is gone, and Sam's onion refuses its old client key.
        val refusedBefore = tor.refused.get()
        val stale = send(a, samOnMatt, EtaTell(3, null))
        until("the old phone's routes are cut") {
            idle(a)
            a.log.snapshot().any { it.event == LinkEvent.SEND_FAILED && it.a == LinkWay.PUSH.code && it.b == 10 + PushProblem.GONE.code } && tor.refused.get() > refusedBefore
        }
        idle(a, b)
        assertTrue(b.sink.delivered.none { it.second.id == stale.id })
        // The new phone's requests still come through, both ways.
        val ask = send(a2, onA2, EtaTell(4, null))
        until("Sam got the new phone's") { idle(a2, b); b.sink.delivered.any { it.second.id == ask.id } }
        val back = send(b, mattOnSam, EtaTell(5, null))
        until("the new phone got Sam's") { idle(a2, b); a2.sink.delivered.any { it.second.id == back.id } }
    }

    /**
     * L5-fix: "Keep the old phone" gives a new moved token (in a hint), so the same backup can't pause the pairing again
     * from another new identity: refused, no second card.
     */
    @Test fun keepingTheOldPhoneGivesANewMovedTokenSoTheBackupCantPauseItAgain() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        val given = b.state.pairings.getValue(mattOnSam).movedIn!!.copyOf()
        val (x1, on1) = moved(a, samOnMatt, b, push, null)
        assertTrue(x1.n().sendMoved(on1).get(30, TimeUnit.SECONDS))
        until("the first card") { b.sink.moved.size == 1 }
        assertTrue(b.n().answerMoved(mattOnSam, false).get())
        until("a new token reached the old phone") { idle(a, b); a.state.pairings.getValue(samOnMatt).movedOut?.contentEquals(b.state.pairings.getValue(mattOnSam).movedIn!!) == true }
        assertFalse(b.state.pairings.getValue(mattOnSam).movedIn!!.contentEquals(given))
        // Another new identity with the token from the same backup: refused, no second card, still active.
        val refused = b.log.snapshot().count { it.event == LinkEvent.PAIR && it.a == PairStep.MOVED_REFUSED.code }
        val (x2, on2) = moved(a, samOnMatt, b, push, null, token = given)
        assertTrue(x2.n().sendMoved(on2).get(30, TimeUnit.SECONDS))
        until("refused") { b.log.snapshot().count { it.event == LinkEvent.PAIR && it.a == PairStep.MOVED_REFUSED.code } > refused }
        assertEquals(1, b.sink.moved.size)
        assertEquals(PairingPhase.ACTIVE, view(b, mattOnSam).phase)
    }

    @Test fun aMovedHelloWithoutTheTokenIsRefusedAndKeepingTheOldPhoneDropsThatMove() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (samOnMatt, mattOnSam) = pairByPush(a, b)
        idle(a, b)
        // A token Sam never gave: refused, no card, nothing paused.
        val (forged, onForged) = moved(a, samOnMatt, b, push, null, token = LinkBytes.random(16))
        assertTrue(forged.n().sendMoved(onForged).get(30, TimeUnit.SECONDS))
        until("refused") { b.log.snapshot().any { it.event == LinkEvent.PAIR && it.a == PairStep.MOVED_REFUSED.code } }
        assertTrue(b.sink.moved.isEmpty())
        assertEquals(PairingPhase.ACTIVE, view(b, mattOnSam).phase)
        // The right token: paused; Sam keeps the old phone.
        val (a3, onA3) = moved(a, samOnMatt, b, push, null)
        assertTrue(a3.n().sendMoved(onA3).get(30, TimeUnit.SECONDS))
        until("Sam's card") { b.sink.moved == listOf(mattOnSam) }
        assertTrue(b.n().answerMoved(mattOnSam, false).get())
        assertEquals(PairingPhase.ACTIVE, view(b, mattOnSam).phase)
        // The old phone still works both ways.
        val eta = send(a, samOnMatt, EtaTell(4, null))
        until("delivered from the old phone") { b.sink.delivered.any { it.second.id == eta.id } }
        // That new phone asking again: dropped, no second card.
        val refusedBefore = b.log.snapshot().count { it.event == LinkEvent.PAIR && it.a == PairStep.MOVED_REFUSED.code }
        a3.env.clock.addAndGet(2 * 60_000L)
        a3.n().pump().get(10, TimeUnit.SECONDS)
        until("refused again") { b.log.snapshot().count { it.event == LinkEvent.PAIR && it.a == PairStep.MOVED_REFUSED.code } > refusedBefore }
        assertEquals(listOf(mattOnSam), b.sink.moved)
        assertEquals(PairingPhase.ACTIVE, view(b, mattOnSam).phase)
    }

    private fun addressOf(r: TestRobot, id: String) = r.state.pairings.getValue(id).myPush

    /** F27: your choice registers every pairing, the address reaches the other Robot, and None takes it back. */
    @Test fun choosingAPushAppRegistersEachPairingAndNoneTakesItBack() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (onA, onB) = pairByPush(a, b)
        until("Matt's address") { addressOf(a, onA) != null && b.state.pairings.getValue(onB).peerPush?.endpoint == addressOf(a, onA)!!.endpoint }
        val first = a.state.pairings.getValue(onA).myInstance

        var saved = 0
        assertEquals(true, a.n().choosePush(true) { saved++; true }.get(20, TimeUnit.SECONDS))
        assertEquals(1, saved)
        until("a new address, told to Sam") {
            val now = addressOf(a, onA)
            now != null && a.state.pairings.getValue(onA).myInstance != first && b.state.pairings.getValue(onB).peerPush?.endpoint == now.endpoint
        }

        assertEquals(true, a.n().choosePush(false) { push.chosen = false; true }.get(20, TimeUnit.SECONDS))
        assertEquals(null, addressOf(a, onA))
        assertEquals(null, a.state.pairings.getValue(onA).myInstance)
        assertEquals(1, a.n().pushWaiting().get(10, TimeUnit.SECONDS))
        val before = push.registered.get()
        a.n().wake()
        a.n().status().get(10, TimeUnit.SECONDS)
        assertEquals(before, push.registered.get())

        // A save that fails registers nothing.
        push.chosen = true
        assertEquals(false, a.n().choosePush(true) { false }.get(20, TimeUnit.SECONDS))
        assertEquals(null, a.state.pairings.getValue(onA).myInstance)
    }

    /** The dormant bug under Refuted: UNREGISTERED and a failed registration come back at a client's wake, never the job's. */
    @Test fun aLostPushAddressRegistersAgainAtAClientsWakeOnly() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (onA, onB) = pairByPush(a, b)
        until("Matt's address") { addressOf(a, onA) != null }
        for (kind in listOf(PushEvent.Kind.UNREGISTERED, PushEvent.Kind.FAILED)) {
            val gone = a.state.pairings.getValue(onA).myInstance!!
            a.n().onPush(PushEvent(kind, gone)).get(10, TimeUnit.SECONDS)
            assertEquals(null, addressOf(a, onA))
            assertEquals(1, a.n().pushWaiting().get(10, TimeUnit.SECONDS))
            val before = push.registered.get()
            a.n().wake(quiet = true)
            a.n().status().get(10, TimeUnit.SECONDS)
            assertEquals(before, push.registered.get())
            a.n().wake()
            until("a new address, told to Sam") {
                val now = addressOf(a, onA)
                now != null && b.state.pairings.getValue(onB).peerPush?.endpoint == now.endpoint
            }
            assertEquals(0, a.n().pushWaiting().get(10, TimeUnit.SECONDS))
        }
    }

    /** The dormant bug under Refuted: an address only your own network can reach counts as none, and hints still go. */
    @Test fun aPrivatePushAddressIsNeverWrittenInAHint() {
        val push = FakePushServer()
        val a = robot("Matt", null, 1, null, push)
        val b = robot("Sam", null, 2, null, push)
        val (onA, onB) = pairByPush(a, b)
        until("Matt's address") { addressOf(a, onA) != null }
        val instance = a.state.pairings.getValue(onA).myInstance!!
        val priv = PushRoute("https://192.168.1.5/up/x", ByteArray(65) { if (it == 0) 4 else 7 }, ByteArray(16) { 2 })
        a.n().onPush(PushEvent(PushEvent.Kind.ENDPOINT, instance, route = priv)).get(10, TimeUnit.SECONDS)
        assertEquals(null, addressOf(a, onA))
        assertEquals(null, a.state.pairings.getValue(onA).myInstance)
        a.n().wake()
        until("a public address again, told to Sam") {
            val now = addressOf(a, onA)
            now != null && b.state.pairings.getValue(onB).peerPush?.endpoint == now.endpoint
        }
    }

    /** The dormant bug under Refuted: a push job's wake sends no hint, so two phones can't keep waking each other. */
    @Test fun aPushJobsWakeSendsNoHintUntilAClientComes() {
        val push = FakePushServer()
        val lan = LoopbackLan()
        val a = robot("Matt", lan, 1, null, push)
        val b = robot("Sam", lan, 2, null, push)
        val (onA, _) = pairByPush(a, b)
        until("Matt's address") { addressOf(a, onA) != null }
        a.n().status().get(10, TimeUnit.SECONDS)
        a.n().sleep()
        a.n().status().get(10, TimeUnit.SECONDS)
        a.state.pairings.getValue(onA).hintAt = 0
        a.n().wake(quiet = true)
        val s = a.n().status().get(10, TimeUnit.SECONDS)
        assertTrue(s.listening)
        assertEquals(0L, a.state.pairings.getValue(onA).hintAt)
        a.n().wake()
        until("the hint it held back") { a.state.pairings.getValue(onA).hintAt != 0L }
    }
}
