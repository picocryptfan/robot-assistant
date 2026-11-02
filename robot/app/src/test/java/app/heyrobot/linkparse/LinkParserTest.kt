// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.linkparse

import app.heyrobot.core.link.ContactCard
import app.heyrobot.core.link.LinkParseWire
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.linkparse.LinkSamples.A
import app.heyrobot.linkparse.LinkSamples.bodies
import app.heyrobot.linkparse.LinkSamples.req
import app.heyrobot.linkparse.LinkSamples.unread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * What :linkparse answers (robot-to-robot-plan.md §5.1, §4.5; job L4), and that :link keeps it: every type read, every
 * answer one :link's check (core LinkParseWire.check) accepts as said; contact cards only as their strict subset; payment
 * requests and the other reserved families answered unsupported, never read (payments wait: the user, 21 Oct). Random and
 * damaged plaintexts never throw, always get one answer, and end where L1's decoder alone would.
 */
class LinkParserTest {
    private val cap = LinkRequests.LIVE

    private fun kept(plain: ByteArray, cap: Int = this.cap): RequestRead {
        val got = LinkParseWire.check(LinkParser.answer(plain, cap), plain, cap)
        assertNotNull("an honest answer :link refused", got)
        return got!!
    }

    @Test fun everyTypeIsReadAndKeptExactly() {
        assertEquals(RequestType.entries.toSet(), bodies().map { it.type }.toSet())
        for (b in bodies()) for (c in LinkParseWire.CAPS) {
            val plain = LinkRequests.encode(req(b), c) ?: continue
            val r = (kept(plain, c) as RequestRead.Ok).request
            assertEquals(b.type.wire, req(b), r)
            assertArrayEquals(plain, LinkRequests.wire(r))
            if (b is CardOffer) assertEquals(LinkSamples.sam, (r.body as CardOffer).card)
        }
    }

    @Test fun paymentsAndTheOtherReservedFamiliesStayUnsupported() {
        for (t in listOf("pay.request", "pay.done", "act.call", "grp.plan", "skill.offer", "zz.new")) {
            val u = kept(unread(t, "{\"coin\":\"xmr\",\"uri\":\"monero:4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx\"}")) as RequestRead.Unsupported
            assertEquals(t, A to false, u.id to u.newer)
        }
        // Unsupported only about this request: spelled any other way, refused unanswered.
        val spaced = LinkSamples.pad("{ \"v\":1,\"id\":\"$A\",\"at\":1790000040,\"ttl\":604800,\"lang\":\"en\",\"type\":\"pay.request\",\"body\":{}}")
        assertEquals(LinkProblem.NOT_CANONICAL, (kept(spaced) as RequestRead.Bad).problem)
        // Its id escaped, so :link couldn't see it's this request's: refused too. Spaced where :link can still see both: unsupported.
        val escaped = LinkSamples.pad("{\"v\":1,\"id\":\"\\u0030${A.drop(1)}\",\"at\":1790000040,\"ttl\":604800,\"lang\":\"en\",\"type\":\"pay.request\",\"body\":{}}")
        assertEquals(LinkProblem.NOT_CANONICAL, (kept(escaped) as RequestRead.Bad).problem)
        val later = LinkSamples.pad("{\"v\":1, \"id\":\"$A\",\"at\":1790000040,\"ttl\":604800,\"lang\":\"en\",\"type\":\"pay.request\",\"body\":{}}")
        assertEquals(A, (kept(later) as RequestRead.Unsupported).id)
        // A newer protocol version: unsupported, "newer".
        assertTrue((kept(LinkSamples.pad("{\"v\":2,\"id\":\"$A\"}")) as RequestRead.Unsupported).newer)
    }

    @Test fun contactCardsAreTheirStrictSubsetOrNothing() {
        val good = kept(LinkRequests.encode(req(CardOffer.of(ContactCard("Zoë Côté", emails = listOf("zoe@example.ca")))))!!)
        assertEquals(listOf("zoe@example.ca"), ((good as RequestRead.Ok).request.body as CardOffer).card!!.emails)
        // A card L1's codec reads (its shape) but outside the subset: a photo, an address to fetch, another version.
        val base = CardOffer.of(LinkSamples.sam).vcard
        for (v in listOf(base.replace("END:VCARD", "PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQ\r\nEND:VCARD"), base.replace("END:VCARD", "URL:https://example.org\r\nEND:VCARD"),
            base.replace("VERSION:4.0", "VERSION:3.0"), base.replace("\r\n", "\n"), base.replace("FN:Sam", "FN:Sаm"))) {
            val plain = LinkRequests.encode(req(CardOffer(v)))!!
            assertTrue(LinkRequests.decode(plain, cap) is RequestRead.Ok)
            assertEquals(LinkProblem.BAD_FIELD, (kept(plain) as RequestRead.Bad).problem)
        }
    }

    /** Random and damaged plaintexts: one answer each, never an exception, and :link keeps only what the decoder read. */
    @Test fun damagedAndRandomPlaintextsGetOneAnswer() {
        val r = Random(1506)
        for (b in bodies()) {
            val good = LinkRequests.encode(req(b))!!
            val len = LinkRequests.json(req(b)).toByteArray().size
            repeat(500) {
                val p = good.copyOf()
                repeat(1 + r.nextInt(3)) {
                    val i = r.nextInt(len)
                    p[i] = when (r.nextInt(3)) {
                        0 -> r.nextInt(256).toByte()
                        1 -> "{}[]\",:0123456789.-eE \\u".random(kotlin.random.Random(r.nextLong())).code.toByte()
                        else -> (p[i] + if (r.nextBoolean()) 1 else -1).toByte()
                    }
                }
                val alone = LinkRequests.decode(p, cap)
                when (val got = kept(p)) {
                    is RequestRead.Ok -> assertArrayEquals(p, LinkRequests.wire(got.request))
                    is RequestRead.Unsupported -> assertEquals((alone as RequestRead.Unsupported).id, got.id)
                    is RequestRead.Bad -> assertTrue(alone is RequestRead.Bad || got.problem == LinkProblem.NOT_CANONICAL || (alone as RequestRead.Ok).request.body is CardOffer)
                }
            }
        }
        repeat(3_000) {
            val p = ByteArray(512 * (1 + r.nextInt(4))).also(r::nextBytes)
            assertTrue(kept(p) is RequestRead.Bad)
            // A cap the link never asks under, sizes the link never sends: still one answer, a refusal.
            LinkParser.answer(p, 1_000)
            LinkParser.answer(p.copyOf(100), cap)
        }
    }
}
