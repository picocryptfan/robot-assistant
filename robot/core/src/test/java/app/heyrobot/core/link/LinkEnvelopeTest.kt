// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** robot-to-robot-plan.md §3.2 and §3.7: every field, every cap, a newer version, truncation, a wrong magic, a length past the carrier's cap. */
class LinkEnvelopeTest {
    private val token = ByteArray(16) { (it + 1).toByte() }
    private val zeros = ByteArray(16)

    private fun ok(bytes: ByteArray, carrier: LinkCarrier): LinkEnvelope = (LinkEnvelope.decode(bytes, carrier) as EnvelopeRead.Ok).envelope
    private fun bad(bytes: ByteArray, carrier: LinkCarrier): LinkProblem? = (LinkEnvelope.decode(bytes, carrier) as? EnvelopeRead.Bad)?.problem

    @Test fun everyFieldIsWhereThePlanPutsIt() {
        val body = byteArrayOf(9, 8, 7)
        val wire = LinkEnvelope(EnvelopeKind.SESSION, token, body).encode(LinkCarrier.LIVE)
        assertEquals(25 + 3, wire.size)
        assertArrayEquals(byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), 1), wire.copyOfRange(0, 4))
        assertEquals(1, wire[4].toInt())
        assertArrayEquals(token, wire.copyOfRange(5, 21))
        assertArrayEquals(byteArrayOf(0, 0, 0, 3), wire.copyOfRange(21, 25))
        assertArrayEquals(body, wire.copyOfRange(25, 28))
        val back = ok(wire, LinkCarrier.LIVE)
        assertEquals(EnvelopeKind.SESSION, back.kind)
        assertArrayEquals(token, back.route)
        assertArrayEquals(body, back.body)
        // Each kind has its code.
        assertEquals(listOf(1, 2, 3, 4, 5), EnvelopeKind.entries.map { it.code })
    }

    @Test fun eachKindTravelsOnlyWhereThePlanSays() {
        fun can(kind: EnvelopeKind, route: ByteArray, carrier: LinkCarrier, len: Int = 10) =
            runCatching { LinkEnvelope(kind, route, ByteArray(len)).encode(carrier) }.isSuccess
        // Push: session, first and pairing hello only, route all zeros.
        assertTrue(can(EnvelopeKind.SESSION, zeros, LinkCarrier.PUSH))
        assertTrue(can(EnvelopeKind.FIRST, zeros, LinkCarrier.PUSH))
        assertTrue(can(EnvelopeKind.PAIR_HELLO, zeros, LinkCarrier.PUSH))
        assertFalse(can(EnvelopeKind.SESSION, token, LinkCarrier.PUSH))
        assertFalse(can(EnvelopeKind.PAIR_HELLO, token, LinkCarrier.PUSH))
        assertFalse(can(EnvelopeKind.CHUNK, zeros, LinkCarrier.PUSH))
        assertFalse(can(EnvelopeKind.CONNECT, zeros, LinkCarrier.PUSH, 0))
        // Live: a connection hello is empty and carries a token; a chunk carries a token; a pairing hello zeros.
        assertTrue(can(EnvelopeKind.CONNECT, token, LinkCarrier.LIVE, 0))
        assertFalse(can(EnvelopeKind.CONNECT, token, LinkCarrier.LIVE, 1))
        assertFalse(can(EnvelopeKind.CONNECT, zeros, LinkCarrier.LIVE, 0))
        assertTrue(can(EnvelopeKind.CHUNK, token, LinkCarrier.LIVE))
        assertFalse(can(EnvelopeKind.CHUNK, zeros, LinkCarrier.LIVE))
        assertTrue(can(EnvelopeKind.PAIR_HELLO, zeros, LinkCarrier.LIVE))
        assertFalse(can(EnvelopeKind.PAIR_HELLO, token, LinkCarrier.LIVE))
        assertTrue(can(EnvelopeKind.SESSION, token, LinkCarrier.LIVE))
        assertTrue(can(EnvelopeKind.FIRST, zeros, LinkCarrier.LIVE))
        // No kind but a connection hello is empty.
        for (k in EnvelopeKind.entries - EnvelopeKind.CONNECT) assertFalse(can(k, if (k == EnvelopeKind.PAIR_HELLO) zeros else token, LinkCarrier.LIVE, 0))
        // The decoder says the same about the bytes: a token on push, a chunk on push.
        val live = LinkEnvelope(EnvelopeKind.SESSION, token, ByteArray(5)).encode(LinkCarrier.LIVE)
        assertEquals(LinkProblem.BAD_ROUTE, bad(live, LinkCarrier.PUSH))
        val chunk = LinkEnvelope(EnvelopeKind.CHUNK, token, ByteArray(5)).encode(LinkCarrier.LIVE)
        assertEquals(LinkProblem.BAD_KIND, bad(chunk, LinkCarrier.PUSH))
    }

    @Test fun theCarriersCapsHold() {
        // Push: 3,993 bytes in all (UnifiedPush AND_3.1.0), so 3,968 of body.
        assertEquals(3_993, LinkCarrier.PUSH.maxEnvelope)
        assertEquals(3_968, LinkCarrier.PUSH.maxBody)
        val fullPush = LinkEnvelope(EnvelopeKind.FIRST, zeros, ByteArray(3_968)).encode(LinkCarrier.PUSH)
        assertEquals(3_993, fullPush.size)
        ok(fullPush, LinkCarrier.PUSH)
        assertTrue(runCatching { LinkEnvelope(EnvelopeKind.FIRST, zeros, ByteArray(3_969)).encode(LinkCarrier.PUSH) }.isFailure)
        // Live: 65,577, a 64 KiB chunk with its tag and the header.
        assertEquals(65_577, LinkCarrier.LIVE.maxEnvelope)
        val fullChunk = LinkEnvelope(EnvelopeKind.CHUNK, token, ByteArray(65_552)).encode(LinkCarrier.LIVE)
        assertEquals(65_577, fullChunk.size)
        ok(fullChunk, LinkCarrier.LIVE)
        // A length past the carrier's cap, written by hand: refused from the header alone.
        val h = fullPush.copyOf(25)
        h[21] = 0; h[22] = 0; h[23] = 0x0F; h[24] = 0x81.toByte() // 3,969
        assertEquals(LinkProblem.BAD_LENGTH, (LinkEnvelope.header(h, LinkCarrier.PUSH) as HeaderRead.Bad).problem)
        // 0xFFFFFFFF is a length, not -1: refused before anything is allocated.
        val huge = fullChunk.copyOf(25)
        for (i in 21..24) huge[i] = 0xFF.toByte()
        assertEquals(LinkProblem.BAD_LENGTH, (LinkEnvelope.header(huge, LinkCarrier.LIVE) as HeaderRead.Bad).problem)
        assertEquals(LinkProblem.BAD_LENGTH, bad(huge, LinkCarrier.LIVE))
        // More bytes than the carrier holds at all.
        assertEquals(LinkProblem.TOO_LONG, bad(ByteArray(3_994), LinkCarrier.PUSH))
    }

    @Test fun aNewerVersionIsAnsweredAndAnythingElseDropped() {
        val wire = LinkEnvelope(EnvelopeKind.SESSION, zeros, ByteArray(4)).encode(LinkCarrier.PUSH)
        val newer = wire.copyOf().also { it[3] = 2 }
        assertEquals(EnvelopeRead.Newer(2), LinkEnvelope.decode(newer, LinkCarrier.PUSH))
        assertEquals(HeaderRead.Newer(2), LinkEnvelope.header(newer.copyOf(25), LinkCarrier.PUSH))
        // A newer envelope larger than this version's cap is still a newer one, not noise.
        val bigNewer = ByteArray(5_000).also { it[0] = 'R'.code.toByte(); it[1] = 'L'.code.toByte(); it[2] = 'K'.code.toByte(); it[3] = 7 }
        assertEquals(EnvelopeRead.Newer(7), LinkEnvelope.decode(bigNewer, LinkCarrier.PUSH))
        assertEquals(LinkProblem.BAD_VERSION, bad(wire.copyOf().also { it[3] = 0 }, LinkCarrier.PUSH))
        // A wrong magic, an unknown kind.
        assertEquals(LinkProblem.BAD_MAGIC, bad(wire.copyOf().also { it[0] = 'X'.code.toByte() }, LinkCarrier.PUSH))
        assertEquals(LinkProblem.BAD_MAGIC, bad(wire.copyOf().also { it[2] = 'k'.code.toByte() }, LinkCarrier.PUSH))
        assertEquals(LinkProblem.BAD_KIND, bad(wire.copyOf().also { it[4] = 0 }, LinkCarrier.PUSH))
        assertEquals(LinkProblem.BAD_KIND, bad(wire.copyOf().also { it[4] = 6 }, LinkCarrier.PUSH))
    }

    @Test fun truncatedOrTrailingInputIsRefused() {
        val wire = LinkEnvelope(EnvelopeKind.FIRST, zeros, ByteArray(40) { it.toByte() }).encode(LinkCarrier.PUSH)
        for (n in 0 until wire.size) assertTrue("cut at $n", LinkEnvelope.decode(wire.copyOf(n), LinkCarrier.PUSH) is EnvelopeRead.Bad)
        assertEquals(LinkProblem.TRUNCATED, bad(wire.copyOf(wire.size - 1), LinkCarrier.PUSH))
        assertEquals(LinkProblem.TRAILING, bad(wire + 0, LinkCarrier.PUSH))
        assertEquals(LinkProblem.TRUNCATED, (LinkEnvelope.header(wire.copyOf(24), LinkCarrier.PUSH) as HeaderRead.Bad).problem)
        assertEquals(LinkProblem.TRAILING, (LinkEnvelope.header(wire.copyOf(26), LinkCarrier.PUSH) as HeaderRead.Bad).problem)
        val h = LinkEnvelope.header(wire.copyOf(25), LinkCarrier.PUSH) as HeaderRead.Ok
        assertEquals(40, h.length)
        assertEquals(EnvelopeKind.FIRST, h.kind)
    }

    @Test fun nothingOfTheEnvelopeShowsInALogLine() {
        val e = LinkEnvelope(EnvelopeKind.SESSION, token, "zq9marker".toByteArray())
        assertFalse(e.toString().contains("zq9marker"))
        assertEquals("LinkEnvelope(kind=SESSION, body=9 bytes)", e.toString())
        // The arrays given in and taken out are copies.
        val r = e.route
        r[0] = 99
        assertArrayEquals(token, e.route)
    }

    /** Random and damaged input: never an exception; anything accepted is exactly the bytes it would write again. */
    @Test fun damagedEnvelopesNeverThrow() {
        val r = Random(1501)
        val goods = listOf(
            LinkEnvelope(EnvelopeKind.SESSION, zeros, ByteArray(100).also(r::nextBytes)) to LinkCarrier.PUSH,
            LinkEnvelope(EnvelopeKind.PAIR_HELLO, zeros, ByteArray(3_000).also(r::nextBytes)) to LinkCarrier.PUSH,
            LinkEnvelope(EnvelopeKind.CHUNK, token, ByteArray(500).also(r::nextBytes)) to LinkCarrier.LIVE,
            LinkEnvelope(EnvelopeKind.CONNECT, token, ByteArray(0)) to LinkCarrier.LIVE,
        )
        var accepted = 0
        for ((e, c) in goods) {
            val good = e.encode(c)
            repeat(5_000) {
                val f = good.copyOf(if (r.nextInt(4) == 0) r.nextInt(good.size + 8) else good.size)
                repeat(1 + r.nextInt(3)) { if (f.isNotEmpty()) f[r.nextInt(minOf(f.size, 40))] = r.nextInt(256).toByte() }
                for (carrier in LinkCarrier.entries) {
                    val back = LinkEnvelope.decode(f, carrier)
                    if (back is EnvelopeRead.Ok) { accepted++; assertArrayEquals(f, back.envelope.encode(carrier)) }
                    if (f.size >= 25) LinkEnvelope.header(f.copyOf(25), carrier)
                }
            }
        }
        repeat(5_000) {
            val f = ByteArray(r.nextInt(80)).also(r::nextBytes)
            if (r.nextBoolean() && f.size >= 4) { f[0] = 'R'.code.toByte(); f[1] = 'L'.code.toByte(); f[2] = 'K'.code.toByte(); f[3] = 1 }
            for (carrier in LinkCarrier.entries) LinkEnvelope.decode(f, carrier)
        }
        assertTrue(accepted > 0)
    }
}
