// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.EventFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * robot-to-robot-plan.md §3.4, D18 (job L7): a plan's record as :link keeps it (strict, capped, round trip), keyed by pairing
 * and plan id so one Robot's request never reaches another pairing's plan, and the plans' rules (3 a day on their own, kept 30
 * days, the plans ahead, an older change ignored).
 */
class LinkPlansTest {
    private val sam = LinkIds.newId()
    private val mom = LinkIds.newId()
    private val t = 1_790_000_000L - 1_790_000_000L % 60

    private fun plan(pairing: String = sam, id: String = LinkIds.newId(), mine: Boolean = true, state: PlanState = PlanState.PROPOSED,
        times: List<Long> = listOf(t + 86_400, t + 90_000), at: Long = t, aloneAt: Long = 0) =
        LinkPlan(id, pairing, mine, state, 1, LinkIds.newId(), EventFields("Dinner at Joe's", times, 90, "Joe's", "Bring the wine\nand a corkscrew", null), at, at,
            sent = if (mine) LinkIds.newId() else null, aloneAt = aloneAt)

    /** L16: "cancel the dentist with Sam" with no such plan does nothing; only words that name no plan mean the next one. */
    @Test fun aPlanYourWordsDontNameIsNeverCancelledInstead() {
        val next = plan()
        fun titled(title: String, at: Long) = LinkPlan(LinkIds.newId(), sam, true, PlanState.PROPOSED, 1, LinkIds.newId(), EventFields(title, listOf(t + at), 90), t, t, sent = LinkIds.newId())
        val party = titled("Party", 100_000)
        val later = titled("Dentist appointment", 200_000)
        val ahead = listOf(next, party, later)
        // The next plan, when your words name none.
        for (none in listOf("", "  ", "the plan", "our plans", "my next plan", "that one", "the next one", "the plan with Sam", "Sam's plan", "notre prochain plan", "le plan avec Sam", "le rendez-vous"))
            assertEquals(none, next, PlanRules.named(ahead, none, "Sam"))
        // The one your words name, whatever the case, accents and small words.
        assertEquals(later, PlanRules.named(ahead, "the dentist", "Sam"))
        assertEquals(later, PlanRules.named(ahead, "DENTIST with Sam.", "Sam"))
        assertNull(PlanRules.named(ahead, "le dîner", "Sam"))
        assertEquals(next, PlanRules.named(ahead, "dinner at Joe's", "Sam"))
        // Named but not there: nothing, never the next one.
        assertNull(PlanRules.named(ahead, "the gym", "Sam"))
        assertNull(PlanRules.named(ahead, "the dentist on the moon", "Sam"))
        assertNull(PlanRules.named(ahead, "TV", "Sam"))
        // A word inside a title's word isn't its name ("art" in Party).
        assertNull(PlanRules.named(ahead, "art", "Sam"))
        assertNull(PlanRules.named(emptyList(), "", "Sam"))
    }

    @Test fun recordsRoundTripStrictly() {
        val p = plan().also { it.time = it.event.times[1]; it.cal = 42; it.state = PlanState.ACCEPTED }
        val b = LinkPlan.encode(p)
        val q = LinkPlan.decode(b)!!
        assertEquals(p.id, q.id); assertEquals(p.pairing, q.pairing); assertEquals(p.mine, q.mine); assertEquals(p.state, q.state)
        assertEquals(p.rev, q.rev); assertEquals(p.req, q.req); assertEquals(p.event, q.event); assertEquals(p.sent, q.sent)
        assertEquals(p.time, q.time); assertEquals(42L, q.cal); assertTrue(LinkPlan.encode(q).contentEquals(b))
        // Anything left over, cut short or out of its caps is no record.
        assertNull(LinkPlan.decode(b + 0))
        assertNull(LinkPlan.decode(b.copyOf(b.size - 1)))
        val badTime = b.copyOf().also { it[4 + 16 + 16 + 1 + 1 + 8 + 16 + 16 + 8 + 8 + 7] = 1 }
        assertNull(LinkPlan.decode(badTime))
        // A list for :voice.
        val all = LinkPlan.encodeAll(listOf(p, plan(mom, mine = false)))
        assertEquals(2, LinkPlan.decodeAll(all)!!.size)
        assertNull(LinkPlan.decodeAll(all + 0))
    }

    @Test fun aPlanIsReachedOnlyThroughItsOwnPairing() {
        val id = LinkIds.newId()
        val withSam = plan(sam, id)
        assertNotEquals(LinkPlan.key(sam, id), LinkPlan.key(mom, id))
        val plans = mapOf(LinkPlan.key(sam, id) to withSam)
        assertEquals(withSam, PlanRules.of(plans, sam, id))
        assertNull(PlanRules.of(plans, mom, id))
        assertNull(PlanRules.of(plans, sam, LinkIds.newId()))
        assertNull(PlanRules.of(plans, "not an id", id))
    }

    @Test fun threeADayOnTheirOwnFromOnePerson() {
        val now = t + 10_000
        val plans = listOf(plan(mine = false, aloneAt = now - 100), plan(mine = false, aloneAt = now - 86_399), plan(mine = false, aloneAt = now - 86_400),
            plan(mom, mine = false, aloneAt = now - 10), plan(mine = true), plan(mine = false))
        assertEquals(2, PlanRules.aloneLastDay(plans, sam, now))
        assertEquals(1, PlanRules.aloneLastDay(plans, mom, now))
    }

    @Test fun keptThirtyDaysAfterItEndsAndTheOnesAhead() {
        val p = plan(state = PlanState.ACCEPTED).also { it.time = it.event.times[0] }
        val end = p.event.times[0] + 90 * 60
        assertFalse(PlanRules.drop(p, end + 29 * 86_400))
        assertTrue(PlanRules.drop(p, end + 30 * 86_400))
        val c = plan(state = PlanState.CANCELLED).also { it.done = t }
        assertTrue(PlanRules.drop(c, t + 30 * 86_400))
        val later = plan(times = listOf(t + 200_000))
        val sooner = plan(times = listOf(t + 100_000))
        val gone = plan(times = listOf(t + 1_000))
        val other = plan(mom)
        assertEquals(listOf(sooner, later), PlanRules.ahead(listOf(later, gone, sooner, other, c), sam, t + 50_000))
    }

    @Test fun anOlderChangeArrivingLateIsIgnored() {
        val p = plan().also { it.rev = 3 }
        assertTrue(PlanRules.wins(p, 4, LinkIds.newId()))
        assertFalse(PlanRules.wins(p, 2, LinkIds.newId()))
    }

    @Test fun aRecordSaysNothingOfThePlan() {
        assertFalse(plan().toString().contains("Dinner"))
        assertFalse(plan().toString().contains("Joe"))
    }

    @Test fun desksRepliesAreListedAndTrimmedLikeIncomingOnes() {
        val now = t + 1_000_000
        val e = DeskEntry(LinkIds.newId(), sam, DeskWay.REPLY, 11, DeskState.REPLIED, now, now, plain = ByteArray(512))
        assertTrue(DeskRules.listed(e, now + 6 * 86_400))
        assertFalse(DeskRules.listed(e, now + 7 * 86_400))
        assertFalse(DeskRules.trim(e, now + 6 * 86_400))
        assertTrue(DeskRules.trim(e, now + 7 * 86_400))
        assertTrue(DeskEntry.decode(DeskEntry.encode(e))!!.let { it.way == DeskWay.REPLY && it.state == DeskState.REPLIED })
        assertEquals(listOf(0, 1, 2, 3, -1), listOf(DeskChoice.YES, DeskChoice.FIRST, DeskChoice.SECOND, DeskChoice.THIRD, DeskChoice.DECLINE).map { it.option })
    }
}
