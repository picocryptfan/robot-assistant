// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.RequestRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 1.5, Robot to Robot (job L5): what main and :link share about pairings. The view of a pairing crosses LinkService whole or
 * not at all; levels keep stable codes and never let Answer on its own reach money or contact cards; a block keeps a clean
 * label; net.hint carries the moved token as a 32-hex id only; the safety number is 60 digits in 12 groups of 5.
 */
class LinkPairingTest {
    private fun view(id: String = LinkIds.newId(), phase: PairingPhase = PairingPhase.ACTIVE, verified: Verified = Verified.IN_PERSON,
        person: String? = UUID.randomUUID().toString(), claimed: String = "Sam", preset: LinkPreset? = LinkPreset.FAMILY_OR_FRIEND) =
        PairingView(id, phase, LinkMode.PRIVATE, 1_790_000_000L, claimed, person, verified, if (verified == Verified.NO) 0 else 1_790_000_100L, preset,
            LinkLevels.of(preset ?: LinkPreset.SOMEONE_ELSE), lowerAsked = false, lowerOffered = true, lowerDeclinedAt = 0, movedClaimed = "",
            since = 0)

    @Test fun viewsRoundTripWhole() {
        val vs = listOf(view(), view(phase = PairingPhase.SENT, verified = Verified.NO, person = null, claimed = "", preset = null),
            view(phase = PairingPhase.ENDED, verified = Verified.COMPARED, claimed = "Émilie 👋"))
        val back = PairingView.decodeAll(PairingView.encodeAll(vs))!!
        assertEquals(vs.size, back.size)
        for ((a, b) in vs.zip(back)) {
            assertEquals(a.id, b.id); assertEquals(a.phase, b.phase); assertEquals(a.mode, b.mode); assertEquals(a.claimed, b.claimed)
            assertEquals(a.person, b.person); assertEquals(a.verified, b.verified); assertEquals(a.verifiedAt, b.verifiedAt); assertEquals(a.preset, b.preset)
            assertEquals(a.levels, b.levels); assertEquals(a.lowerOffered, b.lowerOffered); assertEquals(a.created, b.created)
        }
        assertEquals(emptyList<PairingView>(), PairingView.decodeAll(PairingView.encodeAll(emptyList())))
    }

    @Test fun aViewThatDoesNotReadWholeIsNothing() {
        val bytes = PairingView.encodeAll(listOf(view(), view()))
        assertNull(PairingView.decodeAll(bytes.copyOf(bytes.size - 1)))
        assertNull(PairingView.decodeAll(bytes + 0))
        assertNull(PairingView.decodeAll(ByteArray(0)))
        // Every single byte changed: refused, or still a well-formed list (never a crash).
        for (i in bytes.indices) {
            val b = bytes.copyOf()
            b[i] = (b[i] + 1).toByte()
            PairingView.decodeAll(b)
        }
        // The same id twice: refused.
        val v = view()
        assertNull(PairingView.decodeAll(PairingView.encodeAll(listOf(v, v))))
        // A phase or verified code this version doesn't know: refused.
        val one = PairingView.encodeAll(listOf(view()))
        assertNull(PairingView.decodeAll(one.copyOf().also { it[4 + 2 + 16] = 9 }))
    }

    @Test fun aViewHoldsNoClaimItCantShowAndAPersonOnlyAsAPeopleId() {
        assertTrue(runCatching { view(claimed = "Sam‮evil") }.isFailure)
        assertTrue(runCatching { view(claimed = "x".repeat(41)) }.isFailure)
        assertTrue(runCatching { view(person = "not an id") }.isFailure)
        assertTrue(runCatching { view(verified = Verified.NO).let { PairingView(it.id, it.phase, it.mode, it.created, "", null, Verified.NO, 5, null,
            it.levels, false, false, 0, "", 0) } }.isFailure)
    }

    @Test fun levelsKeepStableCodesAndNeverLetMoneyOrCardsAnswerAlone() {
        assertEquals((1..12).toList(), LinkCapability.entries.map(LinkLevels::code).sorted())
        for (c in LinkCapability.entries) assertEquals(c, LinkLevels.capability(LinkLevels.code(c)))
        assertEquals(listOf(0, 1, 2), LinkLevel.entries.map(LinkLevels::levelCode))
        for (p in LinkPreset.entries) assertEquals(LinkLevels.of(p), LinkLevels.decode(LinkLevels.encode(LinkLevels.of(p))))
        val family = LinkLevels.of(LinkPreset.FAMILY_OR_FRIEND)
        assertNull(LinkLevels.set(family, LinkCapability.CONTACT_CARDS, LinkLevel.AUTO))
        assertNull(LinkLevels.set(family, LinkCapability.PAYMENTS, LinkLevel.ASK))
        assertFalse(LinkCapability.PAYMENTS in LinkLevels.SHOWN)
        // L16: splits aren't built, so no row that controls nothing.
        assertFalse(LinkCapability.SPLITS in LinkLevels.SHOWN)
        assertEquals(LinkLevel.AUTO, LinkLevels.set(family, LinkCapability.EVENTS, LinkLevel.AUTO)!![LinkCapability.EVENTS])
        assertEquals(LinkLevel.OFF, LinkLevels.set(family, LinkCapability.EVENTS, LinkLevel.OFF)!![LinkCapability.EVENTS])
        // Read back strictly: a capability missing, twice, out of order, or at a level it can't have.
        val ok = LinkLevels.encode(family)
        assertNull(LinkLevels.decode(ok.copyOf(ok.size - 2)))
        assertNull(LinkLevels.decode(ok.copyOf().also { it[2] = it[0] }))
        assertNull(LinkLevels.decode(ok.copyOf().also { it[2 * 7 + 1] = 2 })) // contact cards (code 8) at Answer on its own
        assertNull(LinkLevels.decode(ok.copyOf().also { it[1] = 3 }))
    }

    @Test fun blockedViewsKeepACleanLabel() {
        assertEquals("Sam from work", BlockedView.label("  Sam from\nwork "))
        assertEquals("", BlockedView.label(null))
        assertEquals(BlockedView.BLOCK_LABEL, BlockedView.label("x".repeat(300)).length)
        val vs = listOf(BlockedView(LinkIds.newId(), "Sam", 1_790_000_000L), BlockedView(LinkIds.newId(), "", 1L))
        val back = BlockedView.decodeAll(BlockedView.encodeAll(vs))!!
        assertEquals(vs.map { it.id to it.label }, back.map { it.id to it.label })
        assertNull(BlockedView.decodeAll(BlockedView.encodeAll(vs).let { it.copyOf(it.size - 1) }))
    }

    @Test fun netHintCarriesTheMovedTokenAsAnIdOnly() {
        val token = LinkIds.newId()
        val r = LinkRequest.of(NetHint(LinkMode.PRIVATE, moved = token), 1_790_000_000L, "en", 86_400L)
        val read = LinkRequests.decode(LinkRequests.encode(r, LinkRequests.LIVE)!!, LinkRequests.LIVE)
        assertEquals(token, ((read as RequestRead.Ok).request.body as NetHint).moved)
        assertTrue(runCatching { NetHint(LinkMode.PRIVATE, moved = "abc") }.isFailure)
        assertTrue(runCatching { NetHint(LinkMode.PRIVATE, moved = "0".repeat(32)) }.isFailure)
        // With the other fields, and without it as before.
        val both = NetHint(LinkMode.PRIVATE, token = LinkIds.newId(), moved = token)
        assertNotNull(LinkRequests.encode(LinkRequest.of(both, 1_790_000_000L, "en", 86_400L), LinkRequests.LIVE))
        assertNull(NetHint(LinkMode.PRIVATE, token = LinkIds.newId()).moved)
    }

    @Test fun theSafetyNumberIsSixtyDigitsInTwelveGroups() {
        val n = (0 until 60).joinToString("") { (it % 10).toString() }
        assertTrue(SafetyNumber.ok(n))
        assertEquals(listOf("01234", "56789"), SafetyNumber.groups(n).take(2))
        assertEquals(12, SafetyNumber.groups(n).size)
        assertFalse(SafetyNumber.ok(n.dropLast(1)))
        assertFalse(SafetyNumber.ok(n.dropLast(1) + "a"))
        // Result codes are stable and distinct.
        assertEquals(UseCodeResult.entries.size, UseCodeResult.entries.map { it.code }.toSet().size)
        assertEquals(MakeCodeResult.entries.size, MakeCodeResult.entries.map { it.code }.toSet().size)
        assertEquals(listOf(1, 2, 3, 4, 5), PairingPhase.entries.map { it.code })
        assertEquals(PairStep.entries.size, PairStep.entries.map { it.code }.toSet().size)
    }
}
