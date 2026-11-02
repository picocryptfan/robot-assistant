// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.LinkRequestsTest.Companion.A
import app.heyrobot.core.link.LinkRequestsTest.Companion.B
import app.heyrobot.core.link.LinkRequestsTest.Companion.SHA
import app.heyrobot.core.link.LinkRequestsTest.Companion.T
import app.heyrobot.core.link.LinkRequestsTest.Companion.req
import app.heyrobot.core.link.LinkRequestsTest.Companion.samples
import app.heyrobot.core.link.requests.Caps
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.ListItem
import app.heyrobot.core.link.requests.ListOp
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.OfferFields
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.QReply
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.requests.Span
import app.heyrobot.core.link.requests.SpRequest
import app.heyrobot.core.link.requests.SplitItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * robot-to-robot-plan.md §5.1, §5.4 (job L4): the frames between :link and :linkparse, and :link's check of every answer.
 * An honest :linkparse ([parse]: L1's decoder and the vCard subset, as app/linkparse's LinkParser does) answers every
 * request so that :link keeps exactly what the decoder read; a lying one (any other request, any other field, another
 * card, an unsupported answer about another id) is caught, and only a refusal gets through, which only drops a request.
 * Every rule of the value tree, every cap, cut and damaged frames, and the worst cases of every type.
 */
class LinkParseWireTest {
    private val cap = LinkRequests.LIVE

    /** What :linkparse answers (app/linkparse LinkParser's composition, here in the test's hands). */
    private fun parse(plain: ByteArray, cap: Int = this.cap): ByteArray {
        var read = LinkRequests.decode(plain, cap)
        if (read is RequestRead.Unsupported && !LinkParseWire.unsupportedOk(plain, read.id, read.newer)) read = RequestRead.Bad(LinkProblem.NOT_CANONICAL)
        val ok = read as? RequestRead.Ok
        val offer = ok?.request?.body as? CardOffer
        if (ok != null && offer != null) {
            val card = LinkVCard.read(offer.vcard)
            read = if (card == null) RequestRead.Bad(LinkProblem.BAD_FIELD) else RequestRead.Ok(with(ok.request) { LinkRequest(id, at, ttl, lang, CardOffer(offer.vcard, card)) })
        }
        return LinkParseWire.encodeReply(read)
    }

    private fun wire(b: LinkBody) = LinkRequests.encode(req(b))!!

    private fun okOf(read: RequestRead?): LinkRequest = (read as RequestRead.Ok).request

    @Test fun requestFramesAreExact() {
        for (c in LinkParseWire.CAPS) for (size in listOf(512, c)) {
            val f = LinkParseWire.encodeRequest(c, size)
            assertEquals(LinkParseIpc.REQUEST_FRAME, f.size)
            val ask = LinkParseWire.decodeRequest(f, size)!!
            assertEquals(c to size, ask.cap to ask.size)
            // The region must be the size the frame says.
            assertNull(LinkParseWire.decodeRequest(f, size + 512))
            assertNull(LinkParseWire.decodeRequest(f, size - 1))
        }
        val good = LinkParseWire.encodeRequest(cap, 1_024)
        assertNull(LinkParseWire.decodeRequest(good + 0, 1_024))
        assertNull(LinkParseWire.decodeRequest(good.copyOf(11), 1_024))
        for (i in 0 until 4) assertNull(LinkParseWire.decodeRequest(good.copyOf().also { it[i] = (it[i] + 1).toByte() }, 1_024))
        fun frame(c: Long, s: Long) = BinWriter().bytes(good.copyOf(4)).u32(c).u32(s).toByteArray()
        for ((c, s) in listOf(4_096L to 512L, 0L to 0L, cap.toLong() to 0L, cap.toLong() to 511L, cap.toLong() to 1_000L, LinkRequests.PUSH_FIRST.toLong() to 2_560L,
            cap.toLong() to cap + 512L, 0xFFFF_FFFFL to 512L, cap.toLong() to 0xFFFF_FFFFL))
            assertNull("$c $s", LinkParseWire.decodeRequest(frame(c, s), s.toInt()))
        for ((c, s) in listOf(4_096 to 512, cap to 1_000, LinkRequests.PUSH_FIRST to 2_560, cap to 0))
            assertTrue(runCatching { LinkParseWire.encodeRequest(c, s) }.isFailure)
    }

    @Test fun everyTypeTravelsAndIsKeptExactly() {
        val card = ContactCard("Sam Tremblay", phones = listOf("+15145550101"))
        for (b in samples()) {
            for (c in LinkParseWire.CAPS) {
                val plain = LinkRequests.encode(req(b), c) ?: continue
                val back = okOf(LinkParseWire.check(parse(plain, c), plain, c))
                assertEquals(b.type.wire, req(b), back)
                assertArrayEquals(plain, LinkRequests.wire(back))
                if (b is CardOffer) assertEquals(card, (back.body as CardOffer).card)
            }
        }
        // A newer Robot's caps keep their unknown names through the tree.
        val newer = Caps((Caps.of("Sam").types + "zz.new").sorted(), "Sam")
        val plain = wire(newer)
        assertEquals(newer, okOf(LinkParseWire.check(parse(plain), plain, cap)).body)
    }

    @Test fun unsupportedAndRefusedTravelAsSaid() {
        // A type this Robot doesn't read, a reserved family (payments wait: `pay.` is answered unsupported), a newer version.
        val unknown = LinkRequestsTest.pad("{\"v\":1,\"id\":\"$A\",\"at\":$T,\"ttl\":604800,\"lang\":\"en\",\"type\":\"zz.new\",\"body\":{}}")
        val pay = LinkRequestsTest.pad("{\"v\":1,\"id\":\"$A\",\"at\":$T,\"ttl\":604800,\"lang\":\"en\",\"type\":\"pay.request\",\"body\":{\"coin\":\"xmr\"}}")
        val v2 = LinkRequestsTest.pad("{\"v\":2,\"id\":\"$B\",\"what\":\"next\"}")
        for ((plain, id, newer) in listOf(Triple(unknown, A, false), Triple(pay, A, false), Triple(v2, B, true))) {
            val u = LinkParseWire.check(parse(plain), plain, cap) as RequestRead.Unsupported
            assertEquals(id to newer, u.id to u.newer)
        }
        // Spelled another way (a space, an escaped id): refused as not canonical, never answered.
        val spaced = LinkRequestsTest.pad("{ \"v\":1,\"id\":\"$A\",\"at\":$T,\"ttl\":604800,\"lang\":\"en\",\"type\":\"zz.new\",\"body\":{}}")
        assertEquals(LinkProblem.NOT_CANONICAL, (LinkParseWire.check(parse(spaced), spaced, cap) as RequestRead.Bad).problem)
        // Anything the decoder refuses: its code, as it said it.
        for (junk in listOf(ByteArray(512) { ' '.code.toByte() }, LinkRequestsTest.pad("{\"v\":1}"), LinkRequestsTest.pad("[1,2]"), wire(FbAsk(T, T + 600)).also { it[30] = 'X'.code.toByte() })) {
            val read = LinkRequests.decode(junk, cap) as RequestRead.Bad
            assertEquals(read.problem, (LinkParseWire.check(parse(junk), junk, cap) as RequestRead.Bad).problem)
        }
        // A contact card outside the subset: refused, though L1's codec reads its shape.
        val loose = wire(CardOffer("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Sam Tremblay\r\nTEL:+15145550101\r\nEND:VCARD\r\n"))
        assertTrue(LinkRequests.decode(loose, cap) is RequestRead.Ok)
        assertEquals(LinkProblem.BAD_FIELD, (LinkParseWire.check(parse(loose), loose, cap) as RequestRead.Bad).problem)
    }

    @Test fun aLyingParserCanRefuseButNeverChangeARequest() {
        val all = samples()
        for (b in all) {
            val plain = wire(b)
            // Any other request, read: refused (another type, another id, another time, another language).
            for (other in all.filter { it != b }) assertNull(other.type.wire, LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Ok(read(other))), plain, cap))
            val r = req(b)
            for (x in listOf(LinkRequest(B, r.at, r.ttl, r.lang, r.body), LinkRequest(r.id, r.at + 60, r.ttl, r.lang, r.body), LinkRequest(r.id, r.at, r.ttl + 60, r.lang, r.body),
                LinkRequest(r.id, r.at, r.ttl, "fr", r.body)))
                assertNull(LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Ok(withCard(x))), plain, cap))
            // About another id, or the wrong version: refused.
            assertNull(LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Unsupported(B, false)), plain, cap))
            assertNull(LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Unsupported(A, true)), plain, cap))
            // A refusal of its own making (or of :link's codes): only the decoder's codes pass.
            for (p in LinkProblem.entries) {
                val got = LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Bad(p)), plain, cap)
                if (p in LinkParseWire.DECODER_PROBLEMS) assertEquals(p, (got as RequestRead.Bad).problem) else assertNull(p.name, got)
            }
            // What it may do: refuse, or call a request it reads unsupported (an ack "unsupported", never content).
            assertTrue(LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Unsupported(A, false)), plain, cap) is RequestRead.Unsupported)
        }
        // A field changed inside the body: the same request otherwise.
        val ev = EvPropose(A, 1, EventFields("Dinner", listOf(T + 3_600), 60))
        val plain = wire(ev)
        for (x in listOf(EvPropose(A, 1, EventFields("Dinnor", listOf(T + 3_600), 60)), EvPropose(A, 2, EventFields("Dinner", listOf(T + 3_600), 60)),
            EvPropose(A, 1, EventFields("Dinner", listOf(T + 3_600), 60, place = "Here")), EvPropose(B, 1, EventFields("Dinner", listOf(T + 3_600), 60))))
            assertNull(LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Ok(req(x))), plain, cap))
        // Another card for the same vCard, a card on a request that isn't one, none on one that is.
        val sam = ContactCard("Sam Tremblay", phones = listOf("+15145550101"))
        val cardPlain = wire(CardOffer.of(sam))
        val honest = parse(cardPlain)
        assertEquals(sam, (okOf(LinkParseWire.check(honest, cardPlain, cap)).body as CardOffer).card)
        val other = ContactCard("Sam Tremblay", phones = listOf("+15145550102"))
        assertNull(LinkParseWire.check(swapCard(honest, other), cardPlain, cap))
        assertNull(LinkParseWire.check(withCardFlag(parse(plain), 1), plain, cap))
        assertNull(LinkParseWire.check(withoutCard(honest), cardPlain, cap))
        // The encoder never answers a card offer as read without its card.
        assertTrue(LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Ok(req(CardOffer(CardOffer.of(sam).vcard)))), cardPlain, cap) is RequestRead.Bad)
        // A plaintext the link would never send: no answer counts.
        assertNull(LinkParseWire.check(honest, cardPlain.copyOf(cardPlain.size + 1), cap))
        val long = wire(QReply(A, text = "x".repeat(2_000)))
        assertTrue(long.size > LinkRequests.PUSH_FIRST)
        assertNull(LinkParseWire.check(parse(long), long, LinkRequests.PUSH_FIRST))
    }

    private fun read(b: LinkBody): LinkRequest = withCard(req(b))

    private fun withCard(r: LinkRequest): LinkRequest {
        val o = r.body as? CardOffer ?: return r
        return LinkRequest(r.id, r.at, r.ttl, r.lang, CardOffer(o.vcard, LinkVCard.read(o.vcard)))
    }

    /** [frame] (a read card offer) with its card's value replaced by [card]'s. */
    private fun swapCard(frame: ByteArray, card: ContactCard): ByteArray {
        val r = BinReader(frame)
        r.bytes(5 + 2 + 16 + 16)
        r.bytes(r.u8())
        LinkTree.read(r)
        val at = r.at
        val w = BinWriter().bytes(frame.copyOf(at)).u8(1)
        LinkTree.write(linkedMapOf("fn" to card.name, "tel" to card.phones, "email" to card.emails, "xmpp" to card.xmpp), w)
        return w.toByteArray()
    }

    private fun withCardFlag(frame: ByteArray, flag: Int): ByteArray = frame.copyOf().also { it[it.size - 1] = flag.toByte() }

    private fun withoutCard(frame: ByteArray): ByteArray {
        val r = BinReader(frame)
        r.bytes(5 + 2 + 16 + 16)
        r.bytes(r.u8())
        LinkTree.read(r)
        return frame.copyOf(r.at) + 0
    }

    @Test fun theValueTreeKeepsItsRules() {
        val plain = wire(FbAsk(T, T + 600))
        val honest = parse(plain)
        assertNotNull(LinkParseWire.check(honest, plain, cap))
        // Cut anywhere, or one byte more: refused.
        for (n in 0 until honest.size) assertNull(LinkParseWire.check(honest.copyOf(n), plain, cap))
        assertNull(LinkParseWire.check(honest + 0, plain, cap))
        // Values past their rules, each in a frame otherwise right.
        fun frame(body: (BinWriter) -> Unit): ByteArray {
            val w = BinWriter().bytes(honest.copyOf(4)).u8(1).u16(RequestType.FB_ASK.code).bytes(LinkBytes.unhex(A, 16)!!).u64(T).u64(604_800)
            w.u8(2).bytes("en".toByteArray())
            body(w)
            return w.u8(0).toByteArray()
        }
        // The right body written by hand: accepted (so each refusal below is its value's).
        assertNotNull(LinkParseWire.check(frame { LinkTree.write(linkedMapOf("from" to T, "to" to T + 600), it) }, plain, cap))
        fun text(w: BinWriter, b: ByteArray) = w.u8(1).u16(b.size).bytes(b)
        val bad = listOf<(BinWriter) -> Unit>(
            // Unknown tag; a null; a key twice; an upper-case key; an empty or long key; too many keys.
            { it.u8(6).u8(2).u8(4).bytes("from".toByteArray()).u8(7) },
            { it.u8(6).u8(2).u8(4).bytes("from".toByteArray()).u8(0) },
            { it.u8(6).u8(2).u8(4).bytes("from".toByteArray()).u8(2).u64(T).u8(4).bytes("from".toByteArray()).u8(2).u64(T + 600) },
            { it.u8(6).u8(2).u8(4).bytes("From".toByteArray()).u8(2).u64(T).u8(2).bytes("to".toByteArray()).u8(2).u64(T + 600) },
            { it.u8(6).u8(1).u8(0) },
            { it.u8(6).u8(1).u8(17).bytes(ByteArray(17) { 'a'.code.toByte() }).u8(3) },
            { w -> w.u8(6).u8(17); repeat(17) { i -> w.u8(2).bytes("k${'a' + i}".toByteArray()).u8(3) } },
            // Text that isn't UTF-8, text past its cap, a list past its cap, a list deeper than five.
            { w -> w.u8(6).u8(1).u8(4).bytes("from".toByteArray()); text(w, byteArrayOf(0xC3.toByte(), 0x28)) },
            { w -> w.u8(6).u8(1).u8(4).bytes("from".toByteArray()); text(w, ByteArray(LinkTree.MAX_TEXT + 1) { 'a'.code.toByte() }) },
            { w -> w.u8(6).u8(1).u8(4).bytes("from".toByteArray()).u8(5).u16(LinkTree.MAX_LIST + 1); repeat(LinkTree.MAX_LIST + 1) { w.u8(3) } },
            { w -> w.u8(6).u8(1).u8(4).bytes("from".toByteArray()); repeat(5) { w.u8(5).u16(1) }; w.u8(3) },
            // A list that says more values than the frame holds.
            { w -> w.u8(6).u8(1).u8(4).bytes("from".toByteArray()).u8(5).u16(500) },
            // A number where text belongs (the body's own check), the body not an object.
            { it.u8(6).u8(2).u8(4).bytes("from".toByteArray()).u8(1).u16(1).bytes("1".toByteArray()).u8(2).bytes("to".toByteArray()).u8(2).u64(T + 600) },
            { it.u8(5).u16(0) },
        )
        for ((i, b) in bad.withIndex()) assertNull("value $i", LinkParseWire.check(frame(b), plain, cap))
        // The header's own fields: a language of upper case or 4 letters, an unknown type, a zero id, another kind or magic.
        assertNull(LinkParseWire.check(honest.copyOf().also { it[4] = 9 }, plain, cap))
        assertNull(LinkParseWire.check(honest.copyOf().also { it[0] = 'X'.code.toByte() }, plain, cap))
        assertNull(LinkParseWire.check(honest.copyOf().also { it[5] = 0; it[6] = 99 }, plain, cap))
        assertNull(LinkParseWire.check(honest.copyOf().also { it[40] = 'E'.code.toByte() }, plain, cap))
        assertNull(LinkParseWire.check(LinkParseWire.encodeReply(RequestRead.Unsupported(A, false)).also { b -> for (i in 5 until 21) b[i] = 0 }, plain, cap))
        // Larger than a reply may be: refused before it is read.
        assertNull(LinkParseWire.check(honest + ByteArray(LinkParseIpc.MAX_REPLY), plain, cap))
        // The tree's writer refuses what its reader would.
        assertTrue(runCatching { LinkTree.write(linkedMapOf("A" to 1L), BinWriter()) }.isFailure)
        assertTrue(runCatching { LinkTree.write(null, BinWriter()) }.isFailure)
        assertTrue(runCatching { LinkTree.write(listOf(listOf(listOf(listOf(listOf(listOf(1L)))))), BinWriter()) }.isFailure)
    }

    /** L1's worst case of every type that can grow (4-byte characters at every cap): each answer fits a reply and is kept. */
    @Test fun theWorstCasesFitAReply() {
        val e4 = "🤖"
        fun t(n: Int) = e4.repeat(n)
        val worst = listOf(
            LsOffer(A, t(40), 13, 13, (1..RequestCaps.LIST_PART_ITEMS).map { ListItem(LinkIds.newId(), RequestCaps.MAX_REV, t(120), true, -RequestCaps.LIST_POS, t(16)) }),
            LsOp(A, (1..RequestCaps.LIST_OPS).map { val id = LinkIds.newId(); ListOp(id, RequestCaps.MAX_REV, ListItem(id, RequestCaps.MAX_REV, t(120), true, -RequestCaps.LIST_POS, t(16))) }),
            // Many small numbers: the tree's numbers are 9 bytes where JSON's may be 1.
            LsOp(A, (1..RequestCaps.LIST_OPS).map { val id = LinkIds.newId(); ListOp(id, 1, ListItem(id, 1, "x", false, 0)) }),
            SpRequest(A, RequestCaps.MAX_REV, "CAD", RequestCaps.MAX_MONEY, 40 * 1_000L, (1..40).map { SplitItem(t(60), 1_000, 1_000) }, 0, 0, 20, B, t(100)),
            SpRequest(A, 1, "CAD", 100, 40, (1..40).map { SplitItem("x", 1, 1) }, 0, 0, 2),
            FbReply(A, T, T + 14 * 86_400, (0 until 336).map { Span(T + it * 3_600L, T + it * 3_600L + 1_800) }),
            QReply(A, text = (1..1_000).joinToString("") { "\"\n" }.trim().let { it.substring(0, minOf(it.length, 1_999)) } + "x"),
            Caps((1..64).map { "abcdefgh.ab" + ('a' + it / 26) + ('a' + it % 26) }.sorted(), t(40)),
            Caps((1..64).map { "a" + ('a' + it / 26) + ('a' + it % 26) }.sorted(), "x"),
            EvPropose(A, RequestCaps.MAX_REV, EventFields(t(60), listOf(T, T + 60, T + 120), 1_440, t(80), t(300), B)),
            FileOffer(A, t(80), OfferKind.DANGEROUS, RequestCaps.FILE_MAX_BYTES, SHA, OfferWhy.REQUEST, B, OfferFields(t(40), t(40), "2026-10-21", RequestCaps.MAX_MONEY, "CAD", "2026-11-01")),
            NetHint(LinkMode.ANONYMOUS, PushRoute("https://" + "a".repeat(992), ByteArray(65) { 4 }, ByteArray(16) { 1 }), OnionKey(ByteArray(32) { 1 }),
                (1..4).map { LocalAddress(ByteArray(16).also { a -> a[0] = 0xFD.toByte(); a[15] = it.toByte() }, 65_535) }, A, ByteArray(32) { 2 }, B),
            CardOffer.of(ContactCard(e4.repeat(ContactCard.NAME), phones = (1..5).map { "+1 514 555 010$it" }, emails = (1..5).map { "a$it." + "b".repeat(50) + "@" + "c".repeat(60) + ".org" },
                xmpp = (1..3).map { "x$it." + "y".repeat(50) + "@" + "z".repeat(60) + ".org" })),
        )
        var largest = 0
        for (b in worst) {
            val plain = LinkRequests.encode(req(b), cap)!!
            val frame = parse(plain)
            largest = maxOf(largest, frame.size)
            assertTrue("${b.type.wire}: ${frame.size}", frame.size <= LinkParseIpc.MAX_REPLY)
            assertEquals(b.type.wire, req(b), okOf(LinkParseWire.check(frame, plain, cap)))
        }
        // The cap has room: twice a live request at most, and every worst case well under it.
        assertTrue(largest.toString(), largest <= LinkParseIpc.MAX_REPLY * 3 / 4)
    }

    /**
     * Random and damaged answers and plaintexts: never an exception. A damaged answer :link keeps as read is exactly the
     * request; whatever plaintext comes in, an honest :linkparse and :link's check end where the decoder alone does.
     */
    @Test fun damagedAnswersAndPlaintextsNeverThrow() {
        val r = Random(1505)
        for (b in samples()) {
            val plain = wire(b)
            val honest = parse(plain)
            val want = okOf(LinkParseWire.check(honest, plain, cap))
            repeat(400) {
                val f = honest.copyOf()
                repeat(1 + r.nextInt(3)) {
                    val i = r.nextInt(f.size)
                    f[i] = if (r.nextBoolean()) r.nextInt(256).toByte() else (f[i] + if (r.nextBoolean()) 1 else -1).toByte()
                }
                when (val got = LinkParseWire.check(f, plain, cap)) {
                    is RequestRead.Ok -> assertEquals(want, got.request)
                    else -> {}
                }
            }
            // Damaged plaintexts through the honest parser: what the decoder says, never more.
            val jsonLen = LinkRequests.json(req(b)).toByteArray().size
            repeat(300) {
                val p = plain.copyOf()
                repeat(1 + r.nextInt(2)) { val i = r.nextInt(jsonLen); p[i] = "{}[]\",:0123456789aez\\ ".random(kotlin.random.Random(r.nextLong())).code.toByte() }
                val alone = LinkRequests.decode(p, cap)
                when (val got = LinkParseWire.check(parse(p), p, cap)) {
                    is RequestRead.Ok -> assertEquals((alone as RequestRead.Ok).request, got.request)
                    is RequestRead.Unsupported -> assertEquals((alone as RequestRead.Unsupported).id, got.id)
                    is RequestRead.Bad -> when (alone) {
                        is RequestRead.Bad -> assertEquals(alone.problem, got.problem)
                        // A card offer whose vCard isn't the subset, or an unsupported type spelled another way.
                        is RequestRead.Ok -> assertTrue(alone.request.body is CardOffer && got.problem == LinkProblem.BAD_FIELD)
                        is RequestRead.Unsupported -> assertEquals(LinkProblem.NOT_CANONICAL, got.problem)
                    }
                    null -> throw AssertionError("an honest answer refused")
                }
            }
        }
        repeat(3_000) {
            val f = ByteArray(r.nextInt(200)).also(r::nextBytes)
            if (f.size > 4 && r.nextBoolean()) { f[0] = 'R'.code.toByte(); f[1] = 'L'.code.toByte(); f[2] = 'P'.code.toByte(); f[3] = 1; f[4] = (1 + r.nextInt(3)).toByte() }
            LinkParseWire.check(f, wire(FbAsk(T, T + 600)), cap)
            LinkParseWire.decodeRequest(f, r.nextInt(40_000))
        }
    }
}
