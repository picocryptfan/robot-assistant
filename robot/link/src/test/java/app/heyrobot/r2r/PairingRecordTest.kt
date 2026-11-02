// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.OpenCode
import app.heyrobot.core.link.Verified
import app.heyrobot.core.link.requests.LinkCaps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 1.5, Robot to Robot (job L5): the pairing record's layout 2 (who it is in People, the preset and levels, how it was
 * verified and when, the mode change asked either way, the moved tokens, a waiting move, an ENDED note's key) reads back
 * whole; L3's layout 1 still reads, with L5's fields at their starts ("Someone else", not verified); a code's photographed
 * key and a block read back; anything else in their place is refused (DAMAGED, kept).
 */
class PairingRecordTest {
    private fun full(): PairingRecord = PairingRecord(LinkIds.newId(), PairingState.MOVED, LinkMode.ANONYMOUS, 1_790_000_000L, "Sam", ByteArray(LinkCaps.BITMAP) { 3 }).apply {
        verified = Verified.COMPARED; verifiedAt = 1_790_000_100L
        person = UUID.randomUUID().toString(); preset = LinkPreset.FAMILY_OR_FRIEND
        levels = LinkLevels.set(LinkLevels.of(LinkPreset.FAMILY_OR_FRIEND), LinkCapability.EVENTS, LinkLevel.AUTO)!!
        scanned = true; movedIn = ByteArray(16) { 1 }; movedOut = ByteArray(16) { 2 }
        lowerAsked = LinkIds.newId(); lowerOffer = LinkIds.newId(); lowerDeclinedAt = 7
        moved = ByteArray(300) { 4 }; movedDeclined = ByteArray(33) { 5 }; since = 9; peerIdentity = ByteArray(33) { 6 }
    }

    @Test fun layoutTwoReadsBackWhole() {
        val p = full()
        val q = PairingRecord.decode(PairingRecord.encode(p))
        assertEquals(p.id, q.id); assertEquals(p.state, q.state); assertEquals(p.mode, q.mode); assertEquals(p.name, q.name)
        assertEquals(p.verified, q.verified); assertEquals(p.verifiedAt, q.verifiedAt); assertEquals(p.person, q.person); assertEquals(p.preset, q.preset)
        assertEquals(p.levels, q.levels); assertEquals(p.scanned, q.scanned); assertArrayEquals(p.movedIn, q.movedIn); assertArrayEquals(p.movedOut, q.movedOut)
        assertEquals(p.lowerAsked, q.lowerAsked); assertEquals(p.lowerOffer, q.lowerOffer); assertEquals(p.lowerDeclinedAt, q.lowerDeclinedAt)
        assertArrayEquals(p.moved, q.moved); assertArrayEquals(p.movedDeclined, q.movedDeclined); assertEquals(p.since, q.since); assertArrayEquals(p.peerIdentity, q.peerIdentity)
    }

    @Test fun layoutOneStillReadsWithLFiveFieldsAtTheirStarts() {
        // L3's layout: the same bytes up to hintAt, version 1, nothing after.
        val p = PairingRecord(LinkIds.newId(), PairingState.ACTIVE, LinkMode.PRIVATE, 1_790_000_000L, "Sam", ByteArray(LinkCaps.BITMAP))
        val two = PairingRecord.encode(p)
        // Layout 2 adds, with L5's fields at their starts: verified 1, verifiedAt 8, person flag 1, preset 1, levels 4 + 24,
        // scanned 1, two token flags 2, two id flags 2, declinedAt 8, moved flag 1, declined flag 1, since 8, peer flag 1.
        val tail = 1 + 8 + 1 + 1 + 4 + 2 * LinkCapability.entries.size + 1 + 2 + 2 + 8 + 1 + 1 + 8 + 1
        val one = two.copyOf(two.size - tail).also { it[3] = 1 }
        val q = PairingRecord.decode(one)
        assertEquals(p.id, q.id)
        assertEquals(Verified.NO, q.verified)
        assertNull(q.person); assertNull(q.preset); assertNull(q.movedIn)
        assertEquals(LinkLevels.of(LinkPreset.SOMEONE_ELSE), q.levels)
        // Layout 1 with layout 2's tail, a layout this version doesn't know, or verified without a time: refused.
        assertTrue(runCatching { PairingRecord.decode(two.copyOf().also { it[3] = 1 }) }.isFailure)
        assertTrue(runCatching { PairingRecord.decode(two.copyOf().also { it[3] = 3 }) }.isFailure)
        val bad = PairingRecord.encode(p.apply { verified = Verified.IN_PERSON; verifiedAt = 5 }).also { it[it.size - tail] = 0 }
        assertTrue(runCatching { PairingRecord.decode(bad) }.isFailure)
    }

    @Test fun aCodesPhotographedKeyAndABlockReadBack() {
        val c = CodeRecord(LinkIds.newId(), OpenCode.of(ByteArray(16) { 9 }, 1_790_086_400L), LinkMode.PRIVATE, true, 1_790_000_000L)
        c.expect = ByteArray(33) { 5 }
        assertArrayEquals(c.expect, CodeRecord.decode(CodeRecord.encode(c)).expect)
        val b = BlockRecord(LinkIds.newId(), ByteArray(33) { 5 }, "Sam from work", 1_790_000_000L)
        val back = BlockRecord.decode(BlockRecord.encode(b))
        assertEquals(b.label, back.label)
        assertTrue(back.blocks(ByteArray(33) { 5 }))
        assertTrue(!back.blocks(ByteArray(33) { 6 }))
        assertTrue(runCatching { BlockRecord(LinkIds.newId(), ByteArray(32), "", 0) }.isFailure)
    }
}
