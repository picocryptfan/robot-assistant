// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.requests.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * robot-to-robot-plan.md §4 and §9.1's LinkPolicyTest: levels, presets, limits that stay (free/busy never titles, events
 * only in hours and when free, 3 a day), money never on its own, locked behaviour by Q3.
 */
class LinkPolicyTest {
    private val unlocked = LinkContext(locked = false)
    private val locked = LinkContext(locked = true)
    private val lockedNoQ3 = LinkContext(locked = true, answerWhileLocked = false)
    private val phoneLock = LinkContext(locked = false, phoneLock = true)
    private val halifax = ZoneId.of("America/Halifax")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, halifax).toEpochSecond()

    @Test fun presetsStartNothingOnItsOwn() {
        for (p in LinkPreset.entries) {
            val levels = LinkPolicy.preset(p)
            assertEquals(LinkCapability.entries.toSet(), levels.keys)
            assertTrue(p.name, levels.values.none { it == LinkLevel.AUTO })
        }
        // Family or friend: everything asks first. Someone else: events and files ask; the rest is Off.
        assertTrue(LinkPolicy.preset(LinkPreset.FAMILY_OR_FRIEND).values.all { it == LinkLevel.ASK })
        val other = LinkPolicy.preset(LinkPreset.SOMEONE_ELSE)
        assertEquals(setOf(LinkCapability.EVENTS, LinkCapability.FILES, LinkCapability.PAYMENTS), other.filterValues { it == LinkLevel.ASK }.keys)
        for (c in listOf(LinkCapability.FREE_BUSY, LinkCapability.REMINDERS, LinkCapability.QUESTIONS)) assertEquals(LinkLevel.OFF, other[c])
    }

    @Test fun moneyAndContactCardsAreNeverAnsweredOnTheirOwn() {
        for (cap in listOf(LinkCapability.PAYMENTS, LinkCapability.CONTACT_CARDS)) {
            assertFalse(cap.autoAllowed)
            assertFalse(cap.autoWhileLocked)
            assertFalse(cap.spokenYes)
            assertEquals(listOf(LinkLevel.OFF, LinkLevel.ASK), LinkPolicy.levels(cap))
            for (from in LinkLevel.entries) assertFalse(LinkPolicy.change(cap, from, LinkLevel.AUTO, tap = true))
        }
        // Even a stored level that says on its own (a damaged file) gives a card, or a wait while locked.
        assertEquals(Handling.CARD, LinkPolicy.decide(RequestType.CARD_OFFER, LinkLevel.AUTO, unlocked))
        assertEquals(Handling.WAIT, LinkPolicy.decide(RequestType.CARD_OFFER, LinkLevel.AUTO, locked))
        // No payment type is in this build at all; when one comes, PAYMENTS is its capability and these rules hold it.
        assertTrue(RequestType.entries.none { it.capability == LinkCapability.PAYMENTS })
        assertTrue(RequestType.reserved("pay.request"))
        // L16, finding 24: a family with a real type in it isn't reserved.
        assertEquals(emptyList<RequestType>(), RequestType.entries.filter { RequestType.reserved(it.wire) })
        assertEquals(3, LinkLimits.PAYMENT_HANDOFFS_PER_DAY)
    }

    @Test fun levelsLoosenOnlyByATapAndTightenAtOnce() {
        val cap = LinkCapability.EVENTS
        assertTrue(LinkPolicy.change(cap, LinkLevel.ASK, LinkLevel.AUTO, tap = true))
        assertFalse(LinkPolicy.change(cap, LinkLevel.ASK, LinkLevel.AUTO, tap = false))
        assertFalse(LinkPolicy.change(cap, LinkLevel.OFF, LinkLevel.ASK, tap = false))
        assertTrue(LinkPolicy.change(cap, LinkLevel.AUTO, LinkLevel.OFF, tap = false))
        assertTrue(LinkPolicy.change(cap, LinkLevel.AUTO, LinkLevel.ASK, tap = false))
        assertTrue(LinkPolicy.change(cap, LinkLevel.ASK, LinkLevel.ASK, tap = false))
        // D14: restored levels drop to Ask me first.
        assertEquals(LinkLevel.ASK, LinkPolicy.restored(LinkLevel.AUTO))
        assertEquals(LinkLevel.OFF, LinkPolicy.restored(LinkLevel.OFF))
        assertEquals(LinkLevel.ASK, LinkPolicy.restored(LinkLevel.ASK))
    }

    @Test fun everyTypeIsDecidedOneWay() {
        for (t in RequestType.entries) {
            val f = LinkPolicy.flow(t)
            if (f == Flow.ASK || f == Flow.SYNC) assertTrue(t.wire, t.capability != null)
            if (t.control) assertTrue(t.wire, f == Flow.CONTROL || f == Flow.ANSWER || f == Flow.ALWAYS_CARD)
            for (level in LinkLevel.entries) for (ctx in listOf(unlocked, locked, lockedNoQ3, phoneLock)) LinkPolicy.decide(t, level, ctx)
        }
        // Control and answers to your own requests are code, whatever the level.
        for (t in listOf(RequestType.CAPS, RequestType.NET_HINT, RequestType.MODE_RAISE, RequestType.UNPAIR, RequestType.ACK, RequestType.FB_REPLY, RequestType.FILE_KEY, RequestType.EV_CANCEL))
            for (level in LinkLevel.entries) assertEquals(t.wire, Handling.CODE, LinkPolicy.decide(t, level, locked))
        // Lowering the mode, joining a folder or a list: always your tap, whatever the level.
        for (t in listOf(RequestType.MODE_LOWER, RequestType.FOLD_JOIN, RequestType.LS_OFFER)) {
            assertEquals(t.wire, Handling.CARD, LinkPolicy.decide(t, LinkLevel.AUTO, unlocked))
            assertEquals(t.wire, Handling.WAIT, LinkPolicy.decide(t, LinkLevel.AUTO, locked))
        }
        assertEquals(Handling.DECLINE, LinkPolicy.decide(RequestType.LS_OFFER, LinkLevel.OFF, unlocked))
        // After joining, a list's changes sync on their own, and wait while locked (Lists' journal applies them at unlock).
        assertEquals(Handling.ANSWER_ALONE, LinkPolicy.decide(RequestType.LS_OP, LinkLevel.ASK, unlocked))
        assertEquals(Handling.WAIT, LinkPolicy.decide(RequestType.LS_OP, LinkLevel.AUTO, locked))
        assertEquals(Handling.DECLINE, LinkPolicy.decide(RequestType.FOLD_OP, LinkLevel.OFF, unlocked))
    }

    @Test fun levelsDecideRequests() {
        val t = RequestType.FB_ASK
        assertEquals(Handling.DECLINE, LinkPolicy.decide(t, LinkLevel.OFF, unlocked))
        assertEquals(Handling.CARD, LinkPolicy.decide(t, LinkLevel.ASK, unlocked))
        assertEquals(Handling.ANSWER_ALONE, LinkPolicy.decide(t, LinkLevel.AUTO, unlocked))
        // Outside a limit that stays: a card even under Answer on its own.
        assertEquals(Handling.CARD, LinkPolicy.decide(RequestType.EV_PROPOSE, LinkLevel.AUTO, unlocked.copy(withinLimits = false)))
    }

    @Test fun lockedBehaviourByQ3() {
        // Q3's default: these still answer on their own while locked.
        for (t in listOf(RequestType.FB_ASK, RequestType.EV_PROPOSE, RequestType.EV_CHANGE, RequestType.REM_OFFER, RequestType.ETA_TELL, RequestType.SP_REQUEST))
            assertEquals(t.wire, Handling.ANSWER_ALONE, LinkPolicy.decide(t, LinkLevel.AUTO, locked))
        // Files are received and sealed, filed at unlock.
        assertEquals(Handling.RECEIVE_AND_SEAL, LinkPolicy.decide(RequestType.FILE_OFFER, LinkLevel.AUTO, locked))
        // Everything else waits sealed until you unlock: questions and file requests need Files' search.
        for (t in listOf(RequestType.Q_ASK, RequestType.FILE_ASK, RequestType.CARD_OFFER)) assertEquals(t.wire, Handling.WAIT, LinkPolicy.decide(t, LinkLevel.AUTO, locked))
        // Cards never open while locked.
        for (t in RequestType.entries.filter { LinkPolicy.flow(it) == Flow.ASK }) assertEquals(t.wire, Handling.WAIT, LinkPolicy.decide(t, LinkLevel.ASK, locked))
        // Q3's other answer: everything waits.
        for (t in RequestType.entries.filter { LinkPolicy.flow(it) == Flow.ASK }) assertEquals(t.wire, Handling.WAIT, LinkPolicy.decide(t, LinkLevel.AUTO, lockedNoQ3))
        // A phone lock: no card opens, answers on their own continue.
        assertEquals(Handling.WAIT, LinkPolicy.decide(RequestType.FB_ASK, LinkLevel.ASK, phoneLock))
        assertEquals(Handling.ANSWER_ALONE, LinkPolicy.decide(RequestType.FB_ASK, LinkLevel.AUTO, phoneLock))
        // You never send, pair or change levels while locked; nothing goes out during a phone lock.
        assertFalse(LinkPolicy.canSend(locked = true, phoneLock = false))
        assertFalse(LinkPolicy.canSend(locked = false, phoneLock = true))
        assertTrue(LinkPolicy.canSend(locked = false, phoneLock = false))
        assertFalse(LinkPolicy.canPair(locked = true))
        assertTrue(LinkPolicy.canPair(locked = false))
    }

    @Test fun aSpokenYesOnlyForTheEverydayCards() {
        for (t in listOf(RequestType.FB_ASK, RequestType.EV_PROPOSE, RequestType.REM_OFFER, RequestType.ETA_TELL))
            assertTrue(t.wire, LinkPolicy.spokenYes(t, panelOpen = true, locked = false))
        for (t in listOf(RequestType.FILE_OFFER, RequestType.CARD_OFFER, RequestType.SP_REQUEST, RequestType.Q_ASK, RequestType.FILE_ASK, RequestType.MODE_LOWER,
            RequestType.FOLD_JOIN, RequestType.LS_OFFER, RequestType.CAPS))
            assertFalse(t.wire, LinkPolicy.spokenYes(t, panelOpen = true, locked = false))
        assertFalse(LinkPolicy.spokenYes(RequestType.FB_ASK, panelOpen = false, locked = false))
        assertFalse(LinkPolicy.spokenYes(RequestType.FB_ASK, panelOpen = true, locked = true))
    }

    @Test fun aContactCardsAddIsNeverASpokenYes() {
        // Whatever a type's capability says, Add to People is a tap; a card with no yes takes no spoken yes either.
        for (t in RequestType.entries) assertFalse(t.wire, LinkPolicy.spokenYes(t, DeskYes.ADD_CONTACT, panelOpen = true, locked = false))
        assertFalse(LinkPolicy.spokenYes(RequestType.ETA_TELL, null, panelOpen = true, locked = false))
        assertTrue(LinkPolicy.spokenYes(RequestType.ETA_TELL, DeskYes.GOT_IT, panelOpen = true, locked = false))
        assertTrue(LinkPolicy.spokenYes(RequestType.REM_OFFER, DeskYes.ADD_REMINDER, panelOpen = true, locked = false))
        assertFalse(LinkPolicy.spokenYes(RequestType.REM_OFFER, DeskYes.ADD_REMINDER, panelOpen = true, locked = true))
    }

    @Test fun freeBusyIsBusyBlocksOnlyNeverTitlesNeverOutsideTheWindow() {
        // The reply's keys are times only: no title, no place, no who.
        val reply = FbReply(LinkRequestsTest.A, 1_790_000_040, 1_790_000_040 + 7_200, emptyList())
        assertEquals(setOf("re", "from", "to", "busy"), (app.heyrobot.core.StrictJson.parse(
            LinkRequests.json(LinkRequestsTest.req(reply)))!!.value as Map<*, *>).let { (it["body"] as Map<*, *>).keys })
        val from = at(2026, 10, 24, 17)
        val to = at(2026, 10, 24, 23)
        val busy = listOf(
            Span(at(2026, 10, 24, 15), at(2026, 10, 24, 17, 10)), // starts before the window: clipped, then a half hour
            Span(at(2026, 10, 24, 18, 5), at(2026, 10, 24, 18, 15)), // 10 minutes: a half hour
            Span(at(2026, 10, 24, 18, 20), at(2026, 10, 24, 19, 0)), // touches the one before once widened: merged
            Span(at(2026, 10, 24, 22, 50), at(2026, 10, 25, 2)), // runs past the window: clipped at its end
            Span(at(2026, 10, 25, 9), at(2026, 10, 25, 10)), // outside: never sent
        )
        val blocks = FreeBusy.blocks(from, to, busy)
        assertEquals(listOf(
            Span(from, at(2026, 10, 24, 17, 30)),
            Span(at(2026, 10, 24, 18), at(2026, 10, 24, 19)),
            Span(at(2026, 10, 24, 22, 30), to),
        ), blocks)
        assertTrue(FbReply.blocksOk(from, to, blocks))
        assertTrue(blocks.all { it.start >= from && it.end <= to })
        assertEquals(emptyList<Span>(), FreeBusy.blocks(from, to, emptyList()))
        // A window that doesn't end on a half hour from its start: the last block ends at the window's end.
        val oddTo = from + 3_000
        val odd = FreeBusy.blocks(from, oddTo, listOf(Span(from + 2_000, from + 2_100)))
        assertEquals(listOf(Span(from + 1_800, oddTo)), odd)
        assertTrue(FbReply.blocksOk(from, oddTo, odd))
    }

    @Test fun eventsOnTheirOwnOnlyInHoursWhenFreeThreeADay() {
        val hours = EventAlone.Hours()
        assertEquals(LocalTime.of(9, 0), hours.start)
        assertEquals(LocalTime.of(21, 0), hours.end)
        val sat7pm = at(2026, 10, 24, 19)
        val sat8pm = at(2026, 10, 24, 20)
        val sat10pm = at(2026, 10, 24, 22)
        // The first proposed time that fits.
        assertEquals(sat7pm, EventAlone.pick(listOf(sat7pm, sat8pm), 120, halifax, hours, emptyList(), 0))
        // Ending past 21:00 is outside the hours; starting before 9:00 too.
        assertNull(EventAlone.pick(listOf(sat8pm), 120, halifax, hours, emptyList(), 0))
        assertEquals(sat8pm, EventAlone.pick(listOf(sat8pm), 60, halifax, hours, emptyList(), 0))
        assertNull(EventAlone.pick(listOf(sat10pm), 30, halifax, hours, emptyList(), 0))
        assertNull(EventAlone.pick(listOf(at(2026, 10, 24, 8, 30)), 60, halifax, hours, emptyList(), 0))
        // Only when free: a busy time overlapping the first choice moves to the second.
        val busy = listOf(Span(at(2026, 10, 24, 18), at(2026, 10, 24, 19, 30)))
        assertEquals(at(2026, 10, 24, 19, 30), EventAlone.pick(listOf(sat7pm, at(2026, 10, 24, 19, 30)), 60, halifax, hours, busy, 0))
        assertNull(EventAlone.pick(listOf(sat7pm), 60, halifax, hours, busy, 0))
        // Three a day from one person, then cards.
        assertEquals(sat7pm, EventAlone.pick(listOf(sat7pm), 60, halifax, hours, emptyList(), 2))
        assertNull(EventAlone.pick(listOf(sat7pm), 60, halifax, hours, emptyList(), 3))
        // Your own hours: 7:00 to 23:00.
        assertEquals(sat10pm, EventAlone.pick(listOf(sat10pm), 30, halifax, EventAlone.Hours(LocalTime.of(7, 0), LocalTime.of(23, 0)), emptyList(), 0))
    }

    @Test fun theOtherLimitsThatStay() {
        val now = 1_790_000_000L
        val day = SlidingSum(LinkLimits.DAY, LinkLimits.FILE_BYTES_PER_DAY)
        val mib = 1L shl 20
        assertTrue(LinkPolicy.fileAlone(50 * mib, OfferKind.RECEIPT, day, now))
        assertFalse(LinkPolicy.fileAlone(50 * mib + 1, OfferKind.RECEIPT, day, now))
        assertFalse(LinkPolicy.fileAlone(1_000, OfferKind.DANGEROUS, day, now))
        day.add(now, 2L * 1024 * mib - 10)
        assertFalse(LinkPolicy.fileAlone(11, OfferKind.PHOTO, day, now))
        assertTrue(LinkPolicy.fileAlone(10, OfferKind.PHOTO, day, now))
        // Splits on their own only under the amount you set, in its currency.
        assertTrue(LinkPolicy.splitAlone(2_000, "CAD", 2_500, "CAD"))
        assertFalse(LinkPolicy.splitAlone(3_000, "CAD", 2_500, "CAD"))
        assertFalse(LinkPolicy.splitAlone(2_000, "USD", 2_500, "CAD"))
        assertFalse(LinkPolicy.splitAlone(1, "CAD", null, null))
        // Reminders: 3 a day.
        val rem = SlidingSum(LinkLimits.DAY, LinkLimits.REMINDERS_PER_DAY.toLong())
        repeat(3) { assertTrue(LinkPolicy.reminderAlone(rem, now)); rem.add(now) }
        assertFalse(LinkPolicy.reminderAlone(rem, now + 60))
        // D17: questions and file requests on their own only from what you shared.
        assertTrue(LinkPolicy.fromSharedAlone(true))
        assertFalse(LinkPolicy.fromSharedAlone(false))
        // An Anonymous pairing never takes addresses on a shared network (L5-fix: the rest of the hint it still takes).
        val lan = LocalAddress(byteArrayOf(10, 0, 0, 2), 4_000)
        val hint = NetHint(LinkMode.PRIVATE, addrs = listOf(lan), token = "1".repeat(32))
        assertEquals(listOf(lan), LinkPolicy.hintAddrs(hint, LinkMode.PRIVATE))
        assertEquals(emptyList<LocalAddress>(), LinkPolicy.hintAddrs(hint, LinkMode.ANONYMOUS))
    }
}
