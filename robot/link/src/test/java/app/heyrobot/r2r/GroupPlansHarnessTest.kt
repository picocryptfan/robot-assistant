// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.GroupAnswer
import app.heyrobot.core.link.GroupMember
import app.heyrobot.core.link.GroupSeen
import app.heyrobot.core.link.GroupState
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkGroup
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PlanState

import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.EvAccept
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.GrpPerson
import app.heyrobot.core.link.requests.GrpStatus
import app.heyrobot.core.link.requests.GrpWho
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
import java.util.concurrent.TimeUnit

/**
 * L11: a group plan between real Robots in one JVM: the organizer's Robot is the hub, each person stands only by what their own Robot
 * said, and what the organizer says of who's in is its word and nothing else.
 */
class GroupPlansHarnessTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val t0 = 1_790_000_000_000L
    private val robots = ArrayList<TestRobot>()
    private val zone = ZoneId.of("UTC")
    private val push = FakePushServer()
    private val day = 86_400L
    private val from = (t0 / 1000 / day + 1) * day
    private val to = from + 7 * day

    @After fun tearDown() { for (r in robots) r.node?.close() }

    private class Bot(val r: TestRobot, val env: TestDeskEnv, val cal: LinkCalendarHarnessTest.FakeCalendar) {
        val st get() = r.state
        val g get() = r.desk!!.groups!!
    }

    private fun robot(name: String): Bot {
        val env = TestDeskEnv()
        val cal = LinkCalendarHarnessTest.FakeCalendar(zone)
        val r = TestRobot(tmp.newFolder(), FakeEnv(t0), null, 0, null, push, name, deskEnv = env, more = { st ->
            val fb = FreeBusyHandler(st, cal); val ev = EventsHandler(st, cal) { env.settings }
            listOf(fb, ev, GroupPlans(st, cal, ev, fb))
        })
        robots += r; r.n().start(); r.n().wake()
        return Bot(r, env, cal)
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun idle() {
        repeat(4) { for (b in robots) { b.n().pump().get(10, TimeUnit.SECONDS); b.n().settle().get(10, TimeUnit.SECONDS) }; Thread.sleep(50) }
    }

    private fun active(r: TestRobot, not: Set<String>): String {
        until("an active pairing") { r.n().pairings().get().any { it.value == PairingState.ACTIVE && it.key !in not } }
        return r.n().pairings().get().entries.first { it.value == PairingState.ACTIVE && it.key !in not }.key
    }

    /** Their pairing ids: on the organizer's side, then on theirs. */
    private fun pair(a: Bot, b: Bot): Pair<String, String> {
        val beforeA = a.r.n().pairings().get().keys; val beforeB = b.r.n().pairings().get().keys
        val code = a.r.n().makeCode(LinkMode.PRIVATE, false).get(20, TimeUnit.SECONDS).code!!
        assertEquals(UseCodeResult.SENT, b.r.n().useCode(code.text(), PairCodeForm.PASTED, LinkMode.PRIVATE).get(30, TimeUnit.SECONDS).result)
        val pa = active(a.r, beforeA); val pb = active(b.r, beforeB)
        for (c in listOf(LinkCapability.EVENTS, LinkCapability.FREE_BUSY)) for (x in listOf(a to pa, b to pb)) {
            assertTrue(x.first.r.n().setLevel(x.second, c, LinkLevel.ASK).get(10, TimeUnit.SECONDS))
        }
        return pa to pb
    }

    private fun <T> on(b: Bot, block: () -> T): T = b.r.n().onLinkThread(block).get(10, TimeUnit.SECONDS)
    private fun out(b: Bot) = b.r.desk!!.deskOut!!
    private fun send(from: Bot, pairing: String, body: LinkBody) {
        val r = LinkRequest.of(body, from.r.env.now() / 1000, "en", LinkRequests.DEFAULT_TTL)
        assertNull(from.r.n().queue(pairing, body.type.code, r.id, r.at, r.ttl, LinkRequests.encode(r)!!).get(10, TimeUnit.SECONDS))
    }
    private fun cards(b: Bot) = on(b) { b.r.desk!!.view()!!.cards }
    private fun yes(b: Bot) { val c = cards(b).first(); assertTrue(on(b) { b.r.desk!!.answer(c.id, DeskChoice.YES) }) }
    private fun no(b: Bot) { val c = cards(b).first(); assertTrue(on(b) { b.r.desk!!.answer(c.id, DeskChoice.DECLINE) }) }

    private class Three(val org: Bot, val sam: Bot, val kim: Bot, val toSam: String, val toKim: String, val samP: String, val kimP: String)

    private fun three(): Three {
        val org = robot("org"); val sam = robot("sam"); val kim = robot("kim")
        val (a, b) = pair(org, sam); val (c, d) = pair(org, kim)
        return Three(org, sam, kim, a, c, b, d)
    }

    private fun group(x: Three, title: String = "Dinner") = LinkGroup(LinkIds.newId(), GroupState.ASKING, title, null, null, 60, from, to, x.org.r.env.now() / 1000, 0,
        listOf(GroupMember(x.toSam, "Sam"), GroupMember(x.toKim, "Kim")))

    /** L16, finding 16: the others hear the name each person asked to be shown, not what you call them; Anonymous, nothing. */
    @Test fun theOthersNeverHearYourNamesForPeople() {
        val x = three()
        val g = proposed(x) // "Kim" in your People; the Robot asked to be shown as "kim".
        yes(x.sam); yes(x.kim); idle()
        assertEquals(listOf("kim"), x.sam.st.groupSeen.values.single().who.map { it.name })
        on(x.org) { x.org.st.pairings[x.toKim]!!.mode = LinkMode.ANONYMOUS; x.org.g.tellForTest(g.id, out(x.org)) }; idle()
        assertEquals(emptyList<String>(), x.sam.st.groupSeen.values.single().who.map { it.name })
    }

    private fun start(x: Three): LinkGroup {
        val g = group(x)
        assertEquals(g.id, on(x.org) { x.org.g.start(g, out(x.org)) })
        idle()
        return x.org.st.groups[g.id]!!
    }

    /** Both answer their `fb.ask` cards, then the group is proposed at [time]. */
    private fun proposed(x: Three, time: Long = from + 10 * 3600): LinkGroup {
        val g = start(x)
        yes(x.sam); yes(x.kim); idle()
        assertTrue(on(x.org) { x.org.r.desk!!.proposeGroup(g.id, time) })
        idle()
        return g
    }

    private fun who(x: Three, g: LinkGroup) = on(x.org) { x.org.g.view(x.org.r.env.now() / 1000) }.mine.first { it.first.id == g.id }.second.who
    private fun plan(b: Bot, grp: String) = b.st.plans.values.first { it.event.grp == grp }

    private fun readable(b: LinkBody) = LinkRequests.decode(LinkRequests.wire(LinkRequest.of(b, t0 / 1000, "en", LinkRequests.DEFAULT_TTL)), LinkRequests.LIVE) is app.heyrobot.core.link.requests.RequestRead.Ok

    @Test fun everyoneIsAskedCommonTimesLeaveOutWhatAnyoneIsBusyAtAndAnswersOnlyCountForTheirOwn() {
        val x = three()
        x.sam.cal.busy += Span(from + 9 * 3600, from + 12 * 3600)
        x.kim.cal.busy += Span(from + day, from + 2 * day)
        x.org.cal.busy += Span(from + 2 * day, from + 3 * day)
        val g = start(x)
        assertEquals("each is asked", 1, cards(x.sam).size); assertEquals(1, cards(x.kim).size)
        yes(x.sam); yes(x.kim); idle()
        assertEquals(listOf(GroupAnswer.KNOWN, GroupAnswer.KNOWN), g.members.map { it.answer })
        val times = on(x.org) { x.org.g.times(g, x.org.r.env.now() / 1000) }
        assertEquals("the first morning goes to Sam's calendar, then two days are taken", listOf(from + 12 * 3600, from + 3 * day + 9 * 3600, from + 4 * day + 9 * 3600), times)
        val v = on(x.org) { x.org.g.view(x.org.r.env.now() / 1000) }
        assertTrue("no one's busy times leave :link", v.mine.single().first.members.all { it.busy == null })
    }

    @Test fun aReplyToSomeoneElsesAskAWrongWindowOrASecondOneCountsForNothing() {
        val x = three()
        val g = start(x)
        val samAsk = g.members[0].ask!!; val kimAsk = g.members[1].ask!!
        send(x.kim, x.kimP, FbReply(samAsk, from, to, listOf(Span(from, to)))); idle()
        assertEquals("Kim answers for Sam", GroupAnswer.ASKED, g.members[0].answer)
        assertEquals("nor does that count for Kim", GroupAnswer.ASKED, g.members[1].answer)
        send(x.kim, x.kimP, FbReply(kimAsk, from, from + 3600, emptyList())); idle()
        assertEquals("another window", GroupAnswer.ASKED, g.members[1].answer)
        send(x.kim, x.kimP, FbReply(kimAsk, from, to, emptyList())); idle()
        assertEquals(GroupAnswer.KNOWN, g.members[1].answer)
        send(x.kim, x.kimP, FbReply(kimAsk, from, to, listOf(Span(from, to)))); idle()
        assertTrue("the first answer stands", on(x.org) { x.org.g.times(g, x.org.r.env.now() / 1000) }.isNotEmpty())
    }

    @Test fun someoneWhoDeclinesToSayIsLeftOutOfTheTimesAndStillAsked() {
        val x = three()
        val g = start(x)
        no(x.sam); yes(x.kim); idle()
        assertEquals(GroupAnswer.WONT_SAY, g.members[0].answer)
        assertTrue(on(x.org) { x.org.g.times(g, x.org.r.env.now() / 1000) }.isNotEmpty())
        assertTrue(on(x.org) { x.org.r.desk!!.proposeGroup(g.id, from + 10 * 3600) })
        idle()
        assertTrue("Sam still gets the plan", cards(x.sam).isNotEmpty())
    }

    @Test fun aProposalGoesToEachWithOneTimeAndTheCalendarGetsOneEvent() {
        val x = three()
        val time = from + 10 * 3600
        val g = proposed(x, time)
        assertEquals(GroupState.PROPOSED, g.state)
        assertTrue("busy times are gone once used", g.members.all { it.busy == null })
        assertEquals(1, cards(x.sam).size)
        assertTrue("nothing's on the calendar until someone says yes", x.org.cal.events.isEmpty())
        yes(x.sam); idle()
        assertEquals(1, x.org.cal.events.size)
        yes(x.kim); idle()
        assertEquals("one event, however many say yes", 1, x.org.cal.events.size)
        assertEquals(listOf(GrpWho.IN, GrpWho.IN), who(x, g))
        assertEquals(1, x.sam.cal.events.size)
        assertEquals(time, plan(x.sam, g.id).time)
    }

    @Test fun nobodyIsToldByNameWhoHasntSaidYes() {
        val x = three()
        proposed(x)
        idle()
        yes(x.sam); idle()
        val seen = x.sam.st.groupSeen.values.single()
        assertTrue("Kim hasn't answered: not named to Sam", seen.who.isEmpty())
        assertEquals(1, seen.waiting); assertEquals(0, seen.out)
        yes(x.kim); idle()
        val after = x.sam.st.groupSeen.values.single()
        assertEquals(listOf(GrpWho.IN), after.who.map { it.who })
        assertEquals(0, after.waiting)
    }

    @Test fun theTimesSayWhenYourCalendarCouldNotBeRead() {
        val x = three()
        val g = start(x)
        fun standing() = on(x.org) { x.org.g.view(x.org.r.env.now() / 1000) }.mine.first { it.first.id == g.id }.second
        assertTrue(standing().calendar)
        x.org.cal.readable = false
        assertFalse(standing().calendar)
    }

    @Test fun whosInMovesOnlyByThatPersonsOwnRobot() {
        val x = three()
        val g = proposed(x)
        yes(x.sam); no(x.kim); idle()
        assertEquals(listOf(GrpWho.IN, GrpWho.OUT), who(x, g))
        // Kim said no, then lies with a yes; and with a yes for Sam's plan.
        send(x.kim, x.kimP, EvAccept(plan(x.org, g.id).let { x.org.st.plans.values.first { p -> p.pairing == x.toKim }.id }, 1, g.time)); idle()
        assertEquals("a no stands", listOf(GrpWho.IN, GrpWho.OUT), who(x, g))
        val samPlan = x.org.st.plans.values.first { it.pairing == x.toSam }
        assertTrue(on(x.org) { x.org.r.desk!!.cancelPlan(x.toSam, samPlan.id) }); idle()
        send(x.kim, x.kimP, EvAccept(samPlan.id, 1, g.time)); idle()
        assertEquals("Kim can't speak for Sam", GrpWho.OUT, who(x, g)[0])
        assertTrue("with no one left, it's off your calendar", x.org.cal.events.isEmpty())
    }

    @Test fun someoneWhoLeftIsOutAndHearsNoMore() {
        val x = three()
        val g = proposed(x)
        yes(x.sam); yes(x.kim); idle()
        val before = x.sam.st.groupSeen.values.single().rev
        val kimPlan = plan(x.kim, g.id)
        assertTrue(on(x.kim) { x.kim.r.desk!!.cancelPlan(x.kimP, kimPlan.id) }); idle()
        assertEquals(listOf(GrpWho.IN, GrpWho.OUT), who(x, g))
        assertTrue("the one who's left isn't told who left", x.sam.st.groupSeen.values.single().who.isEmpty())
        assertEquals("only that one is out", 1, x.sam.st.groupSeen.values.single().out)
        assertTrue(x.sam.st.groupSeen.values.single().rev > before)
        val heard = x.kim.st.groupSeen.values.singleOrNull()?.rev
        assertTrue(on(x.org) { x.org.r.desk!!.cancelPlan(x.toSam, x.org.st.plans.values.first { it.pairing == x.toSam }.id) }); idle()
        assertEquals("Kim isn't told", heard, x.kim.st.groupSeen.values.singleOrNull()?.rev)
        send(x.org, x.toKim, GrpStatus(g.id, 90, listOf(GrpPerson("Sam", GrpWho.IN)))); idle()
        assertEquals("and doesn't take it either", heard, x.kim.st.groupSeen.values.singleOrNull()?.rev)
    }

    /** Who's in goes only to those still in: someone who's out is sent nothing, not sent something they'd decline. */
    @Test fun aStatusGoesOnlyToThoseStillIn() {
        val x = three()
        val g = start(x)
        yes(x.sam); yes(x.kim); idle()
        val told = java.util.concurrent.CopyOnWriteArrayList<String>()
        assertTrue(on(x.org) {
            val real = out(x.org)
            val rec = object : DeskOut by real {
                override fun send(pairing: String, body: LinkBody, ttl: Long): String? { if (body is GrpStatus) told += pairing; return real.send(pairing, body, ttl) }
            }
            x.org.g.propose(g.id, from + 10 * 3600, { p, e -> if (p == x.toSam) x.org.r.desk!!.propose(p, e) else null }, rec)
        })
        assertEquals(listOf(x.toSam), told.toList())
    }

    @Test fun anOrganizerLyingAboutWhosInIsOnlyEverItsOwnWord() {
        val x = three()
        val g = proposed(x)
        yes(x.sam); idle()
        val rev = x.sam.st.groupSeen.values.single().rev
        // Who's named is free text from one Robot: it's kept to show, and no one is reached, no contact or calendar touched.
        val pairings = x.sam.st.pairings.size
        send(x.org, x.toSam, GrpStatus(g.id, rev + 1, listOf(GrpPerson("The Queen", GrpWho.IN), GrpPerson("Kim", GrpWho.IN)))); idle()
        val seen = x.sam.st.groupSeen.values.single()
        assertEquals(listOf("The Queen", "Kim"), seen.who.map { it.name })
        assertEquals(pairings, x.sam.st.pairings.size)
        assertEquals(1, x.sam.cal.events.size)
        // An old count doesn't undo it; another Robot, or a group you aren't in, gets nowhere.
        send(x.org, x.toSam, GrpStatus(g.id, rev, emptyList())); idle()
        assertEquals(2, x.sam.st.groupSeen.values.single().who.size)
        send(x.org, x.toSam, GrpStatus(LinkIds.newId(), 1, emptyList())); idle()
        assertEquals("a group you have no plan in", 1, x.sam.st.groupSeen.size)
        val other = robot("other"); val (o, s) = pair(other, x.sam)
        send(other, o, GrpStatus(g.id, rev + 5, emptyList())); idle()
        assertEquals("the plan's own organizer only", 1, x.sam.st.groupSeen.size)
        assertEquals(2, x.sam.st.groupSeen.values.single().who.size)
        assertNotNull(s)
    }

    @Test fun overSevenOthersOrABadNameIsRefusedAtTheDoor() {
        val big = GrpStatus("a".repeat(32), 1, List(8) { GrpPerson("P$it", GrpWho.IN) })
        assertFalse("eight others", readable(big))
        assertFalse("a line break in a name", readable(GrpStatus("a".repeat(32), 1, listOf(GrpPerson("A\nB", GrpWho.IN)))))
        assertTrue(readable(GrpStatus("a".repeat(32), 1, listOf(GrpPerson("Sam", GrpWho.IN)))))
        val x = three()
        val p = x.toSam
        val tooMany = LinkGroup(LinkIds.newId(), GroupState.ASKING, "Dinner", null, null, 60, from, to, 1, 0, List(8) { GroupMember(LinkIds.newId(), "P$it") })
        assertNull(on(x.org) { x.org.g.start(tooMany, out(x.org)) })
        val notPaired = LinkGroup(LinkIds.newId(), GroupState.ASKING, "Dinner", null, null, 60, from, to, 1, 0, listOf(GroupMember(LinkIds.newId(), "Who")))
        assertNull(on(x.org) { x.org.g.start(notPaired, out(x.org)) })
        val twice = LinkGroup(LinkIds.newId(), GroupState.ASKING, "Dinner", null, null, 60, from, to, 1, 0, listOf(GroupMember(p, "A"), GroupMember(p, "B")))
        assertNull(on(x.org) { x.org.g.start(twice, out(x.org)) })
    }

    @Test fun cancellingTellsEachAndClearsTheOneEvent() {
        val x = three()
        val g = proposed(x)
        yes(x.sam); idle()
        assertEquals(1, x.org.cal.events.size)
        assertTrue(on(x.org) { x.org.r.desk!!.cancelGroup(g.id) }); idle()
        assertEquals(GroupState.CANCELLED, g.state)
        assertTrue(x.org.cal.events.isEmpty())
        assertEquals(PlanState.CANCELLED, plan(x.sam, g.id).state)
        assertTrue(x.sam.cal.events.isEmpty())
        assertFalse("not again", on(x.org) { x.org.r.desk!!.cancelGroup(g.id) })
    }

    @Test fun aLockedPhoneCantProposeOrCancelAndAStatusStillOnlyWaits() {
        val x = three()
        val g = start(x)
        x.org.env.locked = true
        assertFalse(on(x.org) { x.org.r.desk!!.proposeGroup(g.id, from + 10 * 3600) })
        assertFalse(on(x.org) { x.org.r.desk!!.cancelGroup(g.id) })
    }

    @Test fun recordsSurviveARestartAndKeepTheirKey() {
        val x = three()
        val g = proposed(x)
        yes(x.sam); idle()
        val s = x.sam.st.groupSeen.values.single()
        assertEquals(GroupSeen.key(x.samP, g.id), x.sam.st.groupSeen.keys.single())
        assertEquals(s.rev, GroupSeen.decode(GroupSeen.encode(s))!!.rev)
        assertEquals(g.members.size, LinkGroup.decode(LinkGroup.encode(g))!!.members.size)
    }
}
