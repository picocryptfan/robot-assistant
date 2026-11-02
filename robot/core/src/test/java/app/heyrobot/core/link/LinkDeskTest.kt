// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * robot-to-robot-plan.md §4.2, §4.6, §6.4, D14 (job L6): the desk's records and rules. An entry and a view read back
 * exactly, strictly, and a request crosses only as a frame that rebuilds its plaintext; Undo for 7 days on what Robot did on
 * its own; entries kept 30 days, their plaintext 7; 10 cards per person, then "12 more"; and when Robot proposes the switch.
 */
class LinkDeskTest {
    private val day = LinkLimits.DAY
    // On the 30-minute steps times travel on.
    private val now = 1_789_999_200L
    private val sam = LinkIds.newId()
    private val mom = LinkIds.newId()

    private fun req(body: app.heyrobot.core.link.requests.LinkBody, at: Long = now) = LinkRequest.of(body, at, "en", LinkRequests.DEFAULT_TTL)

    private fun entry(pairing: String, type: RequestType, state: DeskState, done: Long, way: DeskWay = state.way) =
        DeskEntry(LinkIds.newId(), pairing, way, if (way == DeskWay.PROPOSAL) LinkLevels.code(LinkCapability.EVENTS) else type.code, state, done, done)

    @Test fun anEntryReadsBackExactlyAndAnythingElseIsRefused() {
        val r = req(EtaTell(10, "Atwater"))
        val e = DeskEntry(r.id, sam, DeskWay.IN, r.type.code, DeskState.HANDLED, now, now + 5, "u:12ab", false, LinkRequests.wire(r))
        val b = DeskEntry.encode(e)
        val back = DeskEntry.decode(b)!!
        assertEquals(e.id, back.id); assertEquals(sam, back.pairing); assertEquals(DeskWay.IN, back.way); assertEquals(DeskState.HANDLED, back.state)
        assertEquals(now + 5, back.done); assertEquals("u:12ab", back.undo); assertFalse(back.seen)
        assertTrue(LinkRequests.wire(r).contentEquals(back.plain))
        // Truncated, extended, a state of the wrong way, a bad magic: none reads.
        assertNull(DeskEntry.decode(b.copyOf(b.size - 1)))
        assertNull(DeskEntry.decode(b + 0))
        assertNull(DeskEntry.decode(b.copyOf().also { it[0] = 'X'.code.toByte() }))
        val wrongWay = b.copyOf().also { it[4 + 32] = DeskWay.OUT.code.toByte() }
        assertNull(DeskEntry.decode(wrongWay))
        // An undo token is printable ASCII, at most 64 characters.
        for (bad in listOf("", "a b", "x".repeat(65), "é")) try {
            DeskEntry(r.id, sam, DeskWay.IN, r.type.code, DeskState.HANDLED, now, now, bad); throw AssertionError(bad)
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun aViewCarriesRequestsOnlyAsFramesThatRebuildThePlaintext() {
        val eta = req(EtaTell(10, "Atwater"))
        val ev = req(EvPropose(LinkIds.newId(), 1, EventFields("Dinner", listOf(now + 3 * day), 90, "Chez Ma Tante", "Bring the charger")))
        val handled = DeskEntry(eta.id, sam, DeskWay.IN, eta.type.code, DeskState.HANDLED, now, now, "t1", false, LinkRequests.wire(eta))
        val sent = DeskEntry(LinkIds.newId(), mom, DeskWay.OUT, RequestType.FB_ASK.code, DeskState.WAITING_FOR_THEM, now, now)
        val bytes = DeskView.encode(listOf(DeskCardOut(LinkIds.newId(), sam, now, DeskYes.ACCEPT) to DeskRequest.of(ev)),
            listOf(handled to DeskRequest.of(eta), sent to null), listOf(DeskProposal(sam, LinkCapability.ETA)), mapOf(sam to 2))
        val v = DeskView.decode(bytes)!!
        assertEquals(1, v.cards.size)
        val card = v.cards.single()
        assertEquals(ev.id, card.request.id); assertEquals(DeskYes.ACCEPT, card.yes)
        assertEquals("Dinner", (card.request.body as EvPropose).event.title)
        assertEquals(2, v.lines.size)
        assertEquals(10L, (v.lines[0].request!!.body as EtaTell).minutes)
        assertNull(v.lines[1].request)
        assertEquals(listOf(DeskProposal(sam, LinkCapability.ETA)), v.proposals)
        assertEquals(mapOf(sam to 2), v.more)
        // A frame that says something else than the plaintext: the whole view is refused.
        val other = req(EtaTell(11, "Atwater"), eta.at)
        val forged = DeskView.encode(emptyList(), listOf(handled to DeskRequest(DeskRequest.of(other).frame, LinkRequests.wire(eta))), emptyList(), emptyMap())
        assertNull(DeskView.decode(forged))
        // Every truncation, and anything after the end, is refused.
        for (n in listOf(1, 10, bytes.size / 2, bytes.size - 1)) assertNull(DeskView.decode(bytes.copyOf(n)))
        assertNull(DeskView.decode(bytes + 0))
    }

    @Test fun aViewLeavesOutWhatDoesNotFitCardsFirst() {
        val big = (1..60).map { i ->
            val r = req(EvPropose(LinkIds.newId(), 1, EventFields("x".repeat(60), listOf(now + i * 1_800L), 30, null, "n".repeat(300))))
            DeskCardOut(LinkIds.newId(), sam, now, null) to DeskRequest.of(r)
        }
        val v = DeskView.decode(DeskView.encode(big, emptyList(), emptyList(), emptyMap()))!!
        assertEquals(60, v.cards.size)
        assertTrue(DeskView.encode(big, emptyList(), emptyList(), emptyMap()).size <= DeskView.MAX_BYTES)
    }

    @Test fun undoForSevenDaysOnlyOnWhatRobotDidOnItsOwn() {
        val e = DeskEntry(LinkIds.newId(), sam, DeskWay.IN, RequestType.EV_PROPOSE.code, DeskState.HANDLED, now, now, "ev:1")
        assertTrue(DeskRules.undoable(e, now))
        assertTrue(DeskRules.undoable(e, now + 7 * day - 1))
        assertFalse(DeskRules.undoable(e, now + 7 * day))
        assertFalse(DeskRules.undoable(DeskEntry(e.id, sam, DeskWay.IN, e.type, DeskState.HANDLED, now, now), now))
        assertFalse(DeskRules.undoable(DeskEntry(e.id, sam, DeskWay.IN, e.type, DeskState.ANSWERED, now, now), now))
    }

    @Test fun entriesGoAfterThirtyDaysTheirPlaintextAfterSeven() {
        val r = req(EtaTell(5))
        val e = DeskEntry(r.id, sam, DeskWay.IN, r.type.code, DeskState.HANDLED, now, now, null, false, LinkRequests.wire(r))
        assertTrue(DeskRules.listed(e, now + 6 * day)); assertFalse(DeskRules.listed(e, now + 7 * day))
        assertFalse(DeskRules.trim(e, now + 6 * day)); assertTrue(DeskRules.trim(e, now + 7 * day))
        assertTrue(DeskRules.keep(e, now + 29 * day)); assertFalse(DeskRules.keep(e, now + 30 * day))
        val out = DeskEntry(LinkIds.newId(), sam, DeskWay.OUT, RequestType.FB_ASK.code, DeskState.SENT, now, now)
        assertTrue(DeskRules.listed(out, now + 29 * day))
        assertFalse(DeskRules.listed(entry(sam, RequestType.EV_PROPOSE, DeskState.NOT_NOW, now), now))
    }

    @Test fun tenCardsPerPersonThenHowManyMore() {
        val cards = (1..12).map { sam to it } + (1..3).map { mom to it }
        val (shown, more) = DeskRules.shown(cards) { it.first }
        assertEquals(13, shown.size)
        assertEquals(10, shown.count { it.first == sam })
        assertEquals(mapOf(sam to 2), more)
    }

    @Test fun robotProposesTheSwitchAfterThreeYesesWithNoDecline() {
        val ev = RequestType.EV_PROPOSE
        val yes = (1..3).map { entry(sam, ev, DeskState.ANSWERED, now - it * day) }
        assertTrue(DeskRules.proposes(yes, sam, LinkCapability.EVENTS, LinkLevel.ASK, now))
        // Two aren't enough; another person's don't count; nor another capability's.
        assertFalse(DeskRules.proposes(yes.take(2), sam, LinkCapability.EVENTS, LinkLevel.ASK, now))
        assertFalse(DeskRules.proposes(yes, mom, LinkCapability.EVENTS, LinkLevel.ASK, now))
        assertFalse(DeskRules.proposes(yes, sam, LinkCapability.FREE_BUSY, LinkLevel.ASK, now))
        // Only at Ask me first: not when it's already on its own, nor Off.
        assertFalse(DeskRules.proposes(yes, sam, LinkCapability.EVENTS, LinkLevel.AUTO, now))
        assertFalse(DeskRules.proposes(yes, sam, LinkCapability.EVENTS, LinkLevel.OFF, now))
        // A Decline since the first of them: not now.
        assertFalse(DeskRules.proposes(yes + entry(sam, ev, DeskState.DECLINED, now - day + 10), sam, LinkCapability.EVENTS, LinkLevel.ASK, now))
        // A Decline before them doesn't count.
        assertTrue(DeskRules.proposes(yes + entry(sam, ev, DeskState.DECLINED, now - 10 * day), sam, LinkCapability.EVENTS, LinkLevel.ASK, now))
        // Older than 30 days: not counted.
        assertFalse(DeskRules.proposes(yes, sam, LinkCapability.EVENTS, LinkLevel.ASK, now + 29 * day))
        // "Not now" holds it back 30 days.
        val notNow = entry(sam, ev, DeskState.NOT_NOW, now - day)
        assertFalse(DeskRules.proposes(yes + notNow, sam, LinkCapability.EVENTS, LinkLevel.ASK, now))
        // Never for what can't be answered on its own: contact cards, money.
        val cards = (1..3).map { entry(sam, RequestType.CARD_OFFER, DeskState.ANSWERED, now - it * day) }
        assertFalse(DeskRules.proposes(cards, sam, LinkCapability.CONTACT_CARDS, LinkLevel.ASK, now))
        assertFalse(DeskRules.proposes(yes, sam, LinkCapability.PAYMENTS, LinkLevel.ASK, now))
        assertEquals(listOf(LinkCapability.EVENTS), DeskRules.proposals(yes, sam, mapOf(LinkCapability.EVENTS to LinkLevel.ASK, LinkCapability.ETA to LinkLevel.ASK), now))
    }

    @Test fun whatTheirAcksDoToWhatYouSent() {
        assertEquals(DeskState.DELIVERED, DeskRules.afterAck(DeskState.SENT, AckState.RECEIVED))
        assertNull(DeskRules.afterAck(DeskState.WAITING_FOR_THEM, AckState.RECEIVED))
        assertEquals(DeskState.WAITING_FOR_THEM, DeskRules.afterAck(DeskState.DELIVERED, AckState.WAITING))
        assertEquals(DeskState.ANSWERED_BY_THEM, DeskRules.afterAck(DeskState.WAITING_FOR_THEM, AckState.HANDLED))
        assertEquals(DeskState.DECLINED_BY_THEM, DeskRules.afterAck(DeskState.SENT, AckState.DECLINED))
        assertEquals(DeskState.COULDNT_REACH, DeskRules.afterAck(DeskState.SENT, AckState.UNSUPPORTED))
        assertNull(DeskRules.afterAck(DeskState.SENT, AckState.BUSY))
        assertNull(DeskRules.afterAck(DeskState.SENT, AckState.EXPIRED))
    }

    @Test fun noticesReadBackStrictly() {
        val n = DeskNotices(3, listOf(DeskNotice(DeskNoticeKind.CARDS, sam, 2), DeskNotice(DeskNoticeKind.BUSY, mom, now)))
        val b = DeskNotices.encode(n)
        val back = DeskNotices.decode(b)!!
        assertEquals(3, back.waiting)
        assertEquals(n.notices, back.notices)
        assertNull(DeskNotices.decode(b + 0))
        assertNull(DeskNotices.decode(b.copyOf(b.size - 1)))
        assertNull(DeskNotices.decode(b.copyOf().also { it[9] = 99 }))
    }

    @Test fun aCardsRequestExpiresWithItsLifetime() {
        val r = LinkRequest.of(FbAsk(now, now + 3_600), now, "en", 3_600)
        assertFalse(DeskRules.expired(r, now + 3_599))
        assertTrue(DeskRules.expired(r, now + 3_600))
        assertNotNull(DeskRequest.of(r).read())
    }
}
