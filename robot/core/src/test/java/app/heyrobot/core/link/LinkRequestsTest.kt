// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.StrictJson
import app.heyrobot.core.link.requests.Ack
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.Bodies
import app.heyrobot.core.link.requests.Caps
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.EvAccept
import app.heyrobot.core.link.requests.EvCancel
import app.heyrobot.core.link.requests.EvChange
import app.heyrobot.core.link.requests.EvDecline
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.FileAccept
import app.heyrobot.core.link.requests.FileAsk
import app.heyrobot.core.link.requests.FileDecline
import app.heyrobot.core.link.requests.FileDone
import app.heyrobot.core.link.requests.FileKey
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.FoldJoin
import app.heyrobot.core.link.requests.FoldLeave
import app.heyrobot.core.link.requests.FoldOp
import app.heyrobot.core.link.requests.FoldOpKind
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkCaps
import app.heyrobot.core.link.requests.LinkJson
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.ListItem
import app.heyrobot.core.link.requests.ListOp
import app.heyrobot.core.link.requests.LsLeave
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.ModeLower
import app.heyrobot.core.link.requests.ModeRaise
import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.NumS
import app.heyrobot.core.link.requests.OfferFields
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.QAsk
import app.heyrobot.core.link.requests.QReply
import app.heyrobot.core.link.requests.RemOffer
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.requests.SettleHow
import app.heyrobot.core.link.requests.SpReply
import app.heyrobot.core.link.requests.SpRequest
import app.heyrobot.core.link.requests.SpSettle
import app.heyrobot.core.link.requests.Span
import app.heyrobot.core.link.requests.SplitItem
import app.heyrobot.core.link.requests.TextS
import app.heyrobot.core.link.requests.Unpair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * robot-to-robot-plan.md §3.3, §4.1 and §9.1's LinkRequestsTest: each type's exact keys, types and caps; unknown keys and
 * types; padding; `caps`. Plus canonical spelling (one way to write each request), every type's worst case under its cap,
 * and random and damaged input.
 */
class LinkRequestsTest {
    companion object {
        const val T = 1_790_000_040L
        const val A = "0123456789abcdef0123456789abcdef"
        const val B = "fedcba9876543210fedcba9876543210"
        const val C = "0000000000000000000000000000000c"
        val SHA = "ab".repeat(32)
        val lan = LocalAddress(byteArrayOf(10, 0, 0, 7), 40_000)
        val push = PushRoute("https://ntfy.example.org/upAbC123?up=1", ByteArray(65) { if (it == 0) 4 else it.toByte() }, ByteArray(16) { 9 })
        val dinner = EventFields("Dinner at Sam's", listOf(T + 3_600, T + 7_200), 120, place = "Chez Sam", note = "Bring wine\nand bread")

        /** One body of every type, as the code that sends it would make it. */
        fun samples(): List<LinkBody> = listOf(
            Caps.of("Sam"), NetHint(LinkMode.PRIVATE, push, OnionKey(ByteArray(32) { 5 }), listOf(lan), A, ByteArray(32) { 6 }), ModeRaise(), ModeLower(), Unpair(), Ack(A, AckState.HANDLED),
            FbAsk(T, T + 21_600), FbReply(A, T, T + 21_600, listOf(Span(T, T + 1_800), Span(T + 3_600, T + 21_600))),
            EvPropose(A, 1, dinner), EvAccept(A, 1, T + 3_600), EvDecline(A, 1, "Busy that night"), EvChange(A, 2, dinner.copy(times = listOf(T + 90_000))),
            EvCancel(A, 3),
            SpRequest(A, 1, "CAD", 10_000, 3_450, listOf(SplitItem("Salad", 1_500, 1_500), SplitItem("Wine", 3_000, 1_000)), 450, 500, 3, file = B),
            SpReply(A, 1, true), SpSettle(A, 2, SettleHow.CASH, 3_450, "CAD"),
            FileOffer(A, "Costco receipt.pdf", OfferKind.RECEIPT, 120_000, SHA, OfferWhy.SPLIT, fields = OfferFields(merchant = "Costco", date = "2026-10-21", total = 8_412, currency = "CAD")),
            FileAccept(A), FileKey(A, ByteArray(32) { 3 }), FileDecline(A), FileDone(A, true, SHA), FileAsk("The lasagna recipe"),
            app.heyrobot.core.link.requests.GrpStatus(A, 1, listOf(app.heyrobot.core.link.requests.GrpPerson("Sam", app.heyrobot.core.link.requests.GrpWho.IN))), FoldJoin(A, "Cottage"), FoldOp(A, B, 1, FoldOpKind.ADD, C), FoldLeave(A),
            // L4: as a sender makes one, the canonical subset (LinkVCard; LinkVCardTest reads it in :linkparse's place).
            CardOffer.of(ContactCard("Sam Tremblay", phones = listOf("+15145550101"))),
            EtaTell(10, "the station"), RemOffer("Bring the charger", T + 86_400),
            QAsk("What's the wifi password at the cottage?"), QReply(A, text = "It's on the fridge."),
            LsOffer(A, "Shopping", 1, 1, listOf(ListItem(B, 3, "Oat milk", false, 1_000, "2 L"))),
            LsOp(A, listOf(ListOp(B, 4, ListItem(B, 4, "Oat milk", true, 1_000, "2 L")), ListOp(C, 2))),
            LsLeave(A),
        )

        fun req(body: LinkBody, lang: String = "en") = LinkRequest(A, T, LinkRequests.DEFAULT_TTL, lang, body)

        fun pad(json: String): ByteArray {
            val b = json.toByteArray(Charsets.UTF_8)
            val size = (b.size + 511) / 512 * 512
            return ByteArray(size) { if (it < b.size) b[it] else ' '.code.toByte() }
        }

        @Suppress("UNCHECKED_CAST")
        fun tree(r: LinkRequest): LinkedHashMap<String, Any?> = StrictJson.parse(LinkRequests.json(r))!!.value as LinkedHashMap<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun body(m: Map<String, Any?>) = m["body"] as LinkedHashMap<String, Any?>
    }

    private fun read(bytes: ByteArray, cap: Int = LinkRequests.LIVE): RequestRead = LinkRequests.decodeOrThrow(bytes, cap)
    private fun bad(bytes: ByteArray, cap: Int = LinkRequests.LIVE) = (read(bytes, cap) as? RequestRead.Bad)?.problem

    @Test fun everyTypeHasACodecAndASampleThatRoundTrips() {
        assertEquals(RequestType.entries.toSet(), Bodies.codecs.keys)
        assertEquals(RequestType.entries.toSet(), samples().map { it.type }.toSet())
        for (b in samples()) {
            val r = req(b)
            val bytes = LinkRequests.encode(r)!!
            assertEquals(0, bytes.size % 512)
            val back = (read(bytes) as RequestRead.Ok).request
            assertEquals(b.type.wire, r, back)
            assertEquals(b, back.body)
            assertArrayEquals(bytes, LinkRequests.encode(back))
        }
        // The plan's example, exactly.
        assertEquals("{\"v\":1,\"id\":\"$A\",\"at\":$T,\"ttl\":604800,\"lang\":\"en\",\"type\":\"fb.ask\",\"body\":{\"from\":$T,\"to\":${T + 21_600}}}",
            LinkRequests.json(req(FbAsk(T, T + 21_600))))
    }

    @Test fun eachTypesKeysAreExactlyItsOwn() {
        for (b in samples()) {
            val m = tree(req(b))
            // An unknown key: dropped.
            val extra = LinkedHashMap(m).also { it["body"] = LinkedHashMap(body(m)).apply { put("zz", 1L) } }
            assertEquals(b.type.wire, LinkProblem.BAD_FIELD, bad(pad(LinkJson.write(extra))))
            // Each required key missing: dropped. (Optional ones may go.)
            val spec = Bodies.codecs.getValue(b.type).spec
            for (k in body(m).keys) {
                val less = LinkedHashMap(m).also { it["body"] = LinkedHashMap(body(m)).apply { remove(k) } }
                val r = read(pad(LinkJson.write(less)))
                if (k in spec.required) assertTrue("${b.type.wire} without $k", r is RequestRead.Bad)
            }
            // A null where a value goes: dropped.
            for (k in body(m).keys) {
                val nul = LinkedHashMap(m).also { it["body"] = LinkedHashMap(body(m)).apply { put(k, null) } }
                assertTrue("${b.type.wire} $k=null", read(pad(LinkJson.write(nul))) is RequestRead.Bad)
            }
            // A wrong type for each key: a number for a string, a string for anything else.
            for ((k, v) in body(m)) {
                val wrong: Any = if (v is String) 7L else "7"
                val w = LinkedHashMap(m).also { it["body"] = LinkedHashMap(body(m)).apply { put(k, wrong) } }
                assertTrue("${b.type.wire} $k wrong type", read(pad(LinkJson.write(w))) is RequestRead.Bad)
            }
        }
        // The top level too: a key more or less.
        val m = tree(req(FbAsk(T, T + 600)))
        assertEquals(LinkProblem.BAD_KEYS, bad(pad(LinkJson.write(LinkedHashMap(m).apply { put("x", 1L) }))))
        assertEquals(LinkProblem.BAD_KEYS, bad(pad(LinkJson.write(LinkedHashMap(m).apply { remove("lang") }))))
    }

    @Test fun everyTextAndNumberIsCapped() {
        for (b in samples()) {
            val m = tree(req(b))
            val spec = Bodies.codecs.getValue(b.type).spec
            for ((k, v) in body(m)) {
                val s = spec.required[k] ?: spec.optional[k]!!
                fun tryValue(x: Any) = read(pad(LinkJson.write(LinkedHashMap(m).also { it["body"] = LinkedHashMap(body(m)).apply { put(k, x) } })))
                if (s is TextS) {
                    assertTrue("${b.type.wire}.$k at its cap", tryValue("é".repeat(s.max)) is RequestRead.Ok)
                    assertTrue("${b.type.wire}.$k past its cap", tryValue("é".repeat(s.max + 1)) is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k blank", tryValue("  ") is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k untrimmed", tryValue(" x") is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k bidi", tryValue("a\u202Eb") is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k tag", tryValue("a\uDB40\uDC41b") is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k NUL", tryValue("a\u0000b") is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k not NFC", tryValue("e\u0301") is RequestRead.Bad)
                    assertEquals("${b.type.wire}.$k a line", s.lines, tryValue("a\nb") is RequestRead.Ok)
                    assertTrue("${b.type.wire}.$k CR", tryValue("a\r\nb") is RequestRead.Bad)
                }
                if (s is NumS) {
                    if (s.max < Long.MAX_VALUE) assertTrue("${b.type.wire}.$k past max", tryValue(s.max + s.step) is RequestRead.Bad)
                    if (s.min > Long.MIN_VALUE) assertTrue("${b.type.wire}.$k under min", tryValue(s.min - s.step) is RequestRead.Bad)
                    if (s.step > 1) assertTrue("${b.type.wire}.$k off its step", tryValue(s.min + 1) is RequestRead.Bad)
                }
                if (v is String && (k == "ev" || k == "sp" || k == "file" || k == "fold" || k == "item" || k == "list" || k == "re" || k == "tok")) {
                    assertTrue("${b.type.wire}.$k upper case", tryValue(v.uppercase()) is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k short", tryValue(v.drop(1)) is RequestRead.Bad)
                    assertTrue("${b.type.wire}.$k zeros", tryValue("0".repeat(32)) is RequestRead.Bad)
                }
            }
        }
    }

    @Test fun crossFieldRulesHold() {
        fun ok(b: LinkBody) = read(pad(LinkRequests.json(req(b)))) is RequestRead.Ok
        // Free/busy: a window of at most 14 days; blocks on 30-minute steps from its start, inside it, sorted, apart.
        assertTrue(ok(FbAsk(T, T + 14 * 86_400)))
        assertFalse(ok(FbAsk(T, T + 14 * 86_400 + 60)))
        assertFalse(ok(FbAsk(T, T)))
        assertFalse(ok(FbAsk(T + 600, T)))
        assertTrue(ok(FbReply(A, T, T + 7_200, emptyList())))
        assertTrue(ok(FbReply(A, T, T + 6_000, listOf(Span(T + 3_600, T + 6_000)))))
        assertFalse(ok(FbReply(A, T, T + 7_200, listOf(Span(T + 60, T + 1_800)))))
        assertFalse(ok(FbReply(A, T, T + 7_200, listOf(Span(T, T + 1_800), Span(T + 1_800, T + 3_600)))))
        assertFalse(ok(FbReply(A, T, T + 7_200, listOf(Span(T + 3_600, T + 5_400), Span(T, T + 1_800)))))
        assertFalse(ok(FbReply(A, T, T + 7_200, listOf(Span(T, T + 9_000)))))
        assertFalse(ok(FbReply(A, T, T + 7_200, listOf(Span(T, T)))))
        // Events: times strictly increasing, 1 to 3.
        assertFalse(ok(EvPropose(A, 1, dinner.copy(times = listOf(T + 7_200, T + 3_600)))))
        assertFalse(ok(EvPropose(A, 1, dinner.copy(times = listOf(T, T)))))
        assertFalse(ok(EvPropose(A, 1, dinner.copy(times = listOf(T, T + 60, T + 120, T + 180)))))
        assertFalse(ok(EvPropose(A, 1, dinner.copy(times = emptyList()))))
        // Splits: the share is its parts, tax and tip; a part never more than its line.
        val sp = samples().filterIsInstance<SpRequest>().single()
        assertFalse(ok(sp.copy(share = sp.share + 1)))
        assertFalse(ok(sp.copy(items = listOf(SplitItem("Salad", 100, 1_500), SplitItem("Wine", 3_000, 1_000)))))
        assertFalse(ok(sp.copy(total = 100)))
        assertFalse(ok(sp.copy(people = 1)))
        // Files: a name is no path; done carries the hash only when it worked; fields' total needs its currency.
        val offer = samples().filterIsInstance<FileOffer>().single()
        assertFalse(ok(offer.copy(name = "../etc/passwd")))
        assertFalse(ok(offer.copy(name = "a\\b")))
        assertFalse(ok(offer.copy(name = "..")))
        assertFalse(ok(offer.copy(size = 200L * 1024 * 1024 + 1)))
        assertTrue(ok(offer.copy(size = 200L * 1024 * 1024)))
        assertFalse(ok(FileDone(A, true)))
        assertFalse(ok(FileDone(A, false, SHA)))
        assertTrue(ok(FileDone(A, false)))
        assertTrue(runCatching { OfferFields(total = 5) }.isFailure)
        val m = tree(req(offer))
        val fields = body(m)["fields"] as MutableMap<String, Any?>
        fields["date"] = "2026-02-30"
        assertTrue(read(pad(LinkJson.write(m))) is RequestRead.Bad)
        // Folders: an add names its file, a removal doesn't. Questions: words or a file, one of the two.
        assertFalse(ok(FoldOp(A, B, 1, FoldOpKind.ADD)))
        assertFalse(ok(FoldOp(A, B, 1, FoldOpKind.REMOVE, C)))
        assertTrue(ok(FoldOp(A, B, 1, FoldOpKind.REMOVE)))
        assertFalse(ok(QReply(A)))
        assertFalse(ok(QReply(A, "x", B)))
        assertTrue(ok(QReply(A, file = B)))
        // Lists: parts in range, items once each; an op is a whole item or a tombstone.
        assertFalse(ok(LsOffer(A, "Shopping", 2, 1, emptyList())))
        assertFalse(ok(LsOffer(A, "Shopping", 1, 1, listOf(ListItem(B, 1, "a", false, 0), ListItem(B, 2, "b", false, 1)))))
        assertFalse(ok(LsOp(A, emptyList())))
        assertFalse(ok(LsOp(A, listOf(ListOp(B, 1), ListOp(B, 2)))))
        val tomb = tree(req(LsOp(A, listOf(ListOp(B, 1)))))
        val tombOp = (body(tomb)["ops"] as List<MutableMap<String, Any?>>)[0]
        tombOp["text"] = "x"
        assertTrue(read(pad(LinkJson.write(tomb))) is RequestRead.Bad)
        val notGone = tree(req(LsOp(A, listOf(ListOp(B, 1)))))
        val notGoneOp = (body(notGone)["ops"] as List<MutableMap<String, Any?>>)[0]
        notGoneOp["gone"] = false
        assertTrue(read(pad(LinkJson.write(notGone))) is RequestRead.Bad)
        // A contact card is a vCard's shape and at most 2 KiB.
        assertFalse(ok(CardOffer("FN:Sam\r\n")))
        assertFalse(ok(CardOffer("BEGIN:VCARD\r\nFN:Sam\u0000\r\nEND:VCARD")))
        assertFalse(ok(CardOffer("BEGIN:VCARD\r\nNOTE:" + "x".repeat(2_048) + "\r\nEND:VCARD")))
        // Net hints: private addresses only, at most 4, once each; something in it.
        assertTrue(runCatching { NetHint(LinkMode.PRIVATE) }.isFailure)
        val hint = tree(req(NetHint(LinkMode.PRIVATE, addrs = listOf(lan))))
        body(hint)["addrs"] = listOf("0408080808" + "9c40")
        assertTrue(read(pad(LinkJson.write(hint))) is RequestRead.Bad)
        body(hint)["addrs"] = listOf(lan.hex(), lan.hex())
        assertTrue(read(pad(LinkJson.write(hint))) is RequestRead.Bad)
        // 1.5 L3: an onion travels with its client authorization key, never one without the other; a zero key isn't one.
        val onionHint = tree(req(NetHint(LinkMode.PRIVATE, onion = OnionKey(ByteArray(32) { 5 }), onionAuth = ByteArray(32) { 6 })))
        assertTrue(read(pad(LinkJson.write(onionHint))) is RequestRead.Ok)
        assertTrue(runCatching { NetHint(LinkMode.PRIVATE, onion = OnionKey(ByteArray(32) { 5 })) }.isFailure)
        assertTrue(runCatching { NetHint(LinkMode.PRIVATE, token = A, onionAuth = ByteArray(32) { 6 }) }.isFailure)
        body(onionHint).remove("oauth")
        assertTrue(read(pad(LinkJson.write(onionHint))) is RequestRead.Bad)
        body(onionHint)["oauth"] = "00".repeat(32)
        assertTrue(read(pad(LinkJson.write(onionHint))) is RequestRead.Bad)
        body(onionHint).remove("onion")
        body(onionHint)["oauth"] = "06".repeat(32)
        assertTrue(read(pad(LinkJson.write(onionHint))) is RequestRead.Bad)
        // The largest hint (a 1,000-byte push address, the onion and its key, four addresses, a token, a moved token, and the
        // longer mode) fits a first message by push: the maker's answer to a pairing hello may be one. L5-fix: `mode` adds 19
        // bytes to its body (1,608 to 1,627; the request 1,741 bytes before padding, 2,048 padded, as before).
        val bigHint = NetHint(LinkMode.ANONYMOUS, PushRoute("https://" + "a".repeat(992), ByteArray(65) { 4 }, ByteArray(16) { 1 }), OnionKey(ByteArray(32) { 1 }),
            (1..4).map { LocalAddress(ByteArray(16).also { a -> a[0] = 0xFD.toByte(); a[15] = it.toByte() }, 65_535) }, A, ByteArray(32) { 2 }, B)
        assertEquals(1_627, LinkJson.write(bigHint.json()).length)
        assertEquals(1_741, LinkRequests.json(req(bigHint)).length)
        assertEquals(LinkRequests.PUSH_FIRST, LinkRequests.encode(req(bigHint), LinkRequests.PUSH_FIRST)!!.size)
        // L5-fix: the mode is required, and one of the two.
        val modeHint = tree(req(NetHint(LinkMode.ANONYMOUS, token = A)))
        assertTrue(read(pad(LinkJson.write(modeHint))) is RequestRead.Ok)
        assertEquals(LinkMode.ANONYMOUS, ((read(pad(LinkJson.write(modeHint))) as RequestRead.Ok).request.body as NetHint).mode)
        body(modeHint)["mode"] = "public"
        assertTrue(read(pad(LinkJson.write(modeHint))) is RequestRead.Bad)
        body(modeHint).remove("mode")
        assertTrue(read(pad(LinkJson.write(modeHint))) is RequestRead.Bad)
        // Mode changes say which way, and only the right way.
        val raise = tree(req(ModeRaise()))
        body(raise)["to"] = "private"
        assertTrue(read(pad(LinkJson.write(raise))) is RequestRead.Bad)
    }

    @Test fun unknownAndReservedTypesAreAnsweredUnsupported() {
        val m = tree(req(FbAsk(T, T + 600)))
        for (t in listOf("pay.request", "pay.done", "act.call", "grp.propose", "skill.offer", "zz.new", "later")) {
            m["type"] = t
            m["body"] = linkedMapOf<String, Any?>("anything" to 1L)
            val r = read(pad(LinkJson.write(m)))
            assertTrue(t, r is RequestRead.Unsupported)
            assertEquals(A, (r as RequestRead.Unsupported).id)
            assertFalse(r.newer)
            assertNull(RequestType.of(t))
        }
        // A malformed type name, or a body that isn't an object: dropped.
        for (t in listOf("FB.ASK", "fb..ask", "f", "fb.ask.x", "fb ask")) { m["type"] = t; assertTrue(t, read(pad(LinkJson.write(m))) is RequestRead.Bad) }
        m["type"] = "zz.new"; m["body"] = 3L
        assertTrue(read(pad(LinkJson.write(m))) is RequestRead.Bad)
        // A newer protocol version with an id: answered; without one: dropped.
        val v2 = tree(req(FbAsk(T, T + 600))).apply { put("v", 2L); put("extra", "x") }
        assertTrue((read(pad(LinkJson.write(v2))) as RequestRead.Unsupported).newer)
        v2.remove("id")
        assertTrue(read(pad(LinkJson.write(v2))) is RequestRead.Bad)
        assertTrue(read(pad(LinkJson.write(tree(req(FbAsk(T, T + 600))).apply { put("v", 0L) }))) is RequestRead.Bad)
        // A known type with a wrong body is never "unsupported": it is dropped.
        val known = tree(req(FbAsk(T, T + 600)))
        body(known)["zz"] = 1L
        assertTrue(read(pad(LinkJson.write(known))) is RequestRead.Bad)
        // L16: splits wait for L9, so a hello and caps don't claim them; their codes stay theirs.
        assertTrue(LinkCaps.SUPPORTED.none { it.wire.startsWith("sp.") })
        assertTrue(Caps.of("Sam").types.none { it.startsWith("sp.") })
        assertEquals(30, RequestType.of("sp.request")?.code)
        // Payment types are not in this build: not in caps, not in the registry.
        assertTrue(LinkCaps.SUPPORTED.none { it.wire.startsWith("pay.") })
        assertTrue(Caps.of("Sam").types.none { it.startsWith("pay.") })
    }

    /** L5-fix: what :voice asks :link to send starts with exactly the header of the fields it gave beside it (a byte compare). */
    @Test fun theHeaderMatchesTheFieldsGivenBesideIt() {
        val r = LinkRequest(A, T, LinkRequests.DEFAULT_TTL, "en", EtaTell(4, null))
        val bytes = LinkRequests.encode(r, LinkRequests.LIVE)!!
        assertTrue(LinkRequests.headerOk(bytes, A, T, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
        assertTrue(LinkRequests.headerOk(LinkRequests.encode(LinkRequest(A, T, 60, "fra", EtaTell(4, null)))!!, A, T, 60, RequestType.ETA_TELL))
        // Another type, id, time or lifetime than the bytes say: refused.
        assertFalse(LinkRequests.headerOk(bytes, A, T, LinkRequests.DEFAULT_TTL, RequestType.FB_ASK))
        assertFalse(LinkRequests.headerOk(bytes, B, T, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
        assertFalse(LinkRequests.headerOk(bytes, A, T + 1, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
        assertFalse(LinkRequests.headerOk(bytes, A, T, LinkRequests.DEFAULT_TTL + 1, RequestType.ETA_TELL))
        // A control type's bytes never pass for a request of another type; nothing short, and no id that isn't one.
        val unpair = LinkRequests.encode(LinkRequest(A, T, LinkRequests.DEFAULT_TTL, "en", Unpair()))!!
        assertFalse(LinkRequests.headerOk(unpair, A, T, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
        assertTrue(LinkRequests.headerOk(unpair, A, T, LinkRequests.DEFAULT_TTL, RequestType.UNPAIR))
        assertFalse(LinkRequests.headerOk(bytes.copyOf(40), A, T, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
        assertFalse(LinkRequests.headerOk(ByteArray(0), A, T, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
        assertFalse(LinkRequests.headerOk(bytes, "x", T, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
        // White space or another key order: not the canonical header.
        val spaced = String(bytes, Charsets.UTF_8).replaceFirst("{\"v\":1,", "{ \"v\":1,").toByteArray(Charsets.UTF_8)
        assertFalse(LinkRequests.headerOk(spaced, A, T, LinkRequests.DEFAULT_TTL, RequestType.ETA_TELL))
    }

    @Test fun paddingIsSpacesToAMultipleOf512() {
        val json = LinkRequests.json(req(FbAsk(T, T + 600)))
        val padded = pad(json)
        assertEquals(512, padded.size)
        assertTrue(read(padded) is RequestRead.Ok)
        // Unpadded, short, too much padding, padding that isn't spaces, junk after the JSON.
        assertEquals(LinkProblem.BAD_PADDING, bad(json.toByteArray()))
        assertEquals(LinkProblem.BAD_PADDING, bad(padded.copyOf(511)))
        assertEquals(LinkProblem.BAD_PADDING, bad(padded + ByteArray(512) { ' '.code.toByte() }))
        assertTrue(read(padded.copyOf().also { it[511] = '\t'.code.toByte() }) is RequestRead.Bad)
        assertTrue(read(padded.copyOf().also { it[511] = '\n'.code.toByte() }) is RequestRead.Bad)
        assertTrue(read(padded.copyOf().also { it[511] = 0 }) is RequestRead.Bad)
        assertTrue(read(padded.copyOf().also { it[json.length] = 'x'.code.toByte() }) is RequestRead.Bad)
        assertEquals(LinkProblem.BAD_PADDING, bad(ByteArray(0)))
        assertEquals(LinkProblem.BAD_PADDING, bad(ByteArray(512) { ' '.code.toByte() }))
        // A request exactly 512 bytes long needs no padding at all.
        var words = "x"
        var exact: ByteArray? = null
        while (exact == null) {
            val j = LinkRequests.json(req(QReply(A, text = words)))
            if (j.length == 512) exact = j.toByteArray() else if (j.length > 512) break else words += "x"
        }
        assertNotNull(exact)
        assertTrue(read(exact!!) is RequestRead.Ok)
        assertEquals(512, LinkRequests.encode(req(QReply(A, text = words)))!!.size)
    }

    @Test fun oneSpellingOnly() {
        val r = req(EvPropose(A, 1, dinner))
        val json = LinkRequests.json(r)
        assertTrue(read(pad(json)) is RequestRead.Ok)
        // White space, a byte-order mark, keys in another order, an escape where a character goes, a number's other form.
        for (variant in listOf(
            " $json",
            json.replace(",\"at\":", ", \"at\":"),
            "\uFEFF$json",
            json.replace("{\"v\":1,\"id\":\"$A\",", "{\"id\":\"$A\",\"v\":1,"),
            json.replace("Dinner", "\\u0044inner"),
            json.replace("Bring wine\\nand", "Bring wine\\u000aand"),
            json.replace("\"rev\":1", "\"rev\":1.0"),
            json.replace("\"rev\":1", "\"rev\":1e0"),
            json.replace("\"rev\":1", "\"rev\":01"),
            json.replace("\"len\":120", "\"len\":120,\"len\":120"),
            json.replace("\"Chez Sam\"", "\"Chez Sam\\/\""),
        )) {
            assertFalse(variant, variant == json)
            assertTrue(variant, read(pad(variant)) is RequestRead.Bad)
        }
        // An escape the writer uses is the one way to write that character.
        val q = req(EvDecline(A, 1, "Say \"no\" \\ maybe\nlater"))
        assertTrue(LinkRequests.json(q).contains("Say \\\"no\\\" \\\\ maybe\\nlater"))
        assertTrue(read(pad(LinkRequests.json(q))) is RequestRead.Ok)
        // Not UTF-8, not JSON.
        assertEquals(LinkProblem.BAD_TEXT, bad(pad(json).also { it[40] = 0xC3.toByte(); it[41] = 0x28 }))
        assertEquals(LinkProblem.NOT_JSON, bad(pad("{\"v\":1,}")))
    }

    @Test fun theEnvelopesFieldsAreChecked() {
        fun with(k: String, v: Any) = read(pad(LinkJson.write(tree(req(FbAsk(T, T + 600))).apply { put(k, v) })))
        assertTrue(with("at", RequestCaps.MIN_TIME - 1) is RequestRead.Bad)
        assertTrue(with("at", RequestCaps.MAX_TIME + 1) is RequestRead.Bad)
        assertTrue(with("ttl", 59L) is RequestRead.Bad)
        assertTrue(with("ttl", 30 * 86_400L + 1) is RequestRead.Bad)
        assertTrue(with("ttl", 30 * 86_400L) is RequestRead.Ok)
        assertTrue(with("lang", "fr") is RequestRead.Ok)
        assertTrue(with("lang", "es") is RequestRead.Ok) // a 1.3 language is still read
        for (l in listOf("EN", "e", "en-CA", "fran", "")) assertTrue(l, with("lang", l) is RequestRead.Bad)
        assertTrue(with("id", A.uppercase()) is RequestRead.Bad)
        assertTrue(with("id", "0".repeat(32)) is RequestRead.Bad)
        assertTrue(with("v", "1") is RequestRead.Bad)
    }

    @Test fun capsListsWhatThisBuildReads() {
        val caps = Caps.of("Sam")
        assertEquals(LinkCaps.SUPPORTED, caps.known)
        assertEquals(caps.types.sorted(), caps.types)
        assertTrue(caps.types.size <= RequestCaps.CAPS_TYPES)
        // A newer Robot's caps name types this one doesn't know: read, kept out of `known`.
        val newer = Caps((caps.types + "zz.new").sorted(), "Sam")
        val back = (read(LinkRequests.encode(req(newer))!!) as RequestRead.Ok).request.body as Caps
        assertEquals(LinkCaps.SUPPORTED, back.known)
        assertTrue("zz.new" in back.types)
        // Unsorted, twice, malformed: dropped.
        assertTrue(read(pad(LinkRequests.json(req(Caps(listOf("fb.ask", "caps"), "Sam"))))) is RequestRead.Bad)
        assertTrue(read(pad(LinkRequests.json(req(Caps(listOf("caps", "caps"), "Sam"))))) is RequestRead.Bad)
        assertTrue(read(pad(LinkRequests.json(req(Caps(listOf("Caps"), "Sam"))))) is RequestRead.Bad)
        assertTrue(read(pad(LinkRequests.json(req(Caps((1..65).map { "a" + ('a' + it / 26) + ('a' + it % 26) }.sorted(), "Sam"))))) is RequestRead.Bad)
        // The hello's bitmap says the same as the names.
        assertEquals(LinkCaps.SUPPORTED, LinkCaps.fromBitmap(LinkCaps.bitmap(LinkCaps.SUPPORTED)))
        assertEquals(setOf(RequestType.FB_ASK), LinkCaps.fromBitmap(LinkCaps.bitmap(setOf(RequestType.FB_ASK))))
    }

    @Test fun sizesFitTheirCarriers() {
        assertEquals(listOf(2_048, 3_584, 32_768), listOf(LinkRequests.PUSH_FIRST, LinkRequests.PUSH, LinkRequests.LIVE))
        // Every sample fits a first push message.
        for (b in samples()) assertNotNull(b.type.wire, LinkRequests.encode(req(b), LinkRequests.PUSH_FIRST))
        // Past a cap: the encoder says so (null) and the decoder refuses it.
        val big = req(QReply(A, text = "x".repeat(2_000)))
        assertNull(LinkRequests.encode(big, LinkRequests.PUSH_FIRST))
        val bytes = LinkRequests.encode(big, LinkRequests.LIVE)!!
        assertEquals(LinkProblem.TOO_LONG, bad(bytes, LinkRequests.PUSH_FIRST))
        // The worst case of every type that can grow fits a live connection: 4-byte characters at every cap.
        val e4 = "🤖"
        fun t(n: Int) = e4.repeat(n)
        val worst = listOf(
            LsOffer(A, t(40), 13, 13, (1..RequestCaps.LIST_PART_ITEMS).map { ListItem(LinkIds.newId(), RequestCaps.MAX_REV, t(120), true, -RequestCaps.LIST_POS, t(16)) }),
            LsOp(A, (1..RequestCaps.LIST_OPS).map { val id = LinkIds.newId(); ListOp(id, RequestCaps.MAX_REV, ListItem(id, RequestCaps.MAX_REV, t(120), true, -RequestCaps.LIST_POS, t(16))) }),
            SpRequest(A, RequestCaps.MAX_REV, "CAD", RequestCaps.MAX_MONEY, 40 * 1_000L, (1..40).map { SplitItem(t(60), 1_000, 1_000) }, 0, 0, 20, B, t(100)),
            FbReply(A, T, T + 14 * 86_400, (0 until 336).map { Span(T + it * 3_600L, T + it * 3_600L + 1_800) }),
            QReply(A, text = (1..1_000).joinToString("") { "\"\n" }.trim().let { it.substring(0, minOf(it.length, 1_999)) } + "x"),
            CardOffer("BEGIN:VCARD\r\n" + "\r\n".repeat(1_011) + "END:VCARD"),
            Caps((1..64).map { "abcdefgh.ab" + ('a' + it / 26) + ('a' + it % 26) }.sorted(), t(40)),
            EvPropose(A, RequestCaps.MAX_REV, EventFields(t(60), listOf(T, T + 60, T + 120), 1_440, t(80), t(300), B)),
            FileOffer(A, t(80), OfferKind.DANGEROUS, RequestCaps.FILE_MAX_BYTES, SHA, OfferWhy.REQUEST, B, OfferFields(t(40), t(40), "2026-10-21", RequestCaps.MAX_MONEY, "CAD", "2026-11-01")),
            NetHint(LinkMode.ANONYMOUS, PushRoute("https://" + "a".repeat(992), ByteArray(65) { 4 }, ByteArray(16) { 1 }), OnionKey(ByteArray(32) { 1 }),
                (1..4).map { LocalAddress(ByteArray(16).also { a -> a[0] = 0xFD.toByte(); a[15] = it.toByte() }, 65_535) }, A, ByteArray(32) { 2 }, B),
        )
        for (b in worst) {
            val bytes = LinkRequests.encode(req(b), LinkRequests.LIVE)
            assertNotNull(b.type.wire, bytes)
            assertTrue(read(bytes!!) is RequestRead.Ok)
        }
        // One more 4-byte list item per part would not have fitted: the cap of 40 is what keeps lists under 32 KiB.
        val over = LsOffer(A, t(40), 13, 13, (1..50).map { ListItem(LinkIds.newId(), RequestCaps.MAX_REV, t(120), true, -RequestCaps.LIST_POS, t(16)) })
        assertTrue(LinkRequests.json(req(over)).toByteArray().size > LinkRequests.LIVE)
    }

    @Test fun nothingOfARequestShowsInALogLine() {
        for (b in samples() + listOf(EvPropose(A, 1, EventFields("zq9marker2", listOf(T), 60)), FileAsk("zq9marker"))) {
            val r = req(b)
            val s = listOf(b.toString(), r.toString(), RequestRead.Ok(r).toString(), RequestRead.Unsupported(A, false).toString())
            for (x in s) assertFalse(x, x.contains("zq9marker") || x.contains(A) || x.contains("Sam") || x.contains("Dinner") || x.contains("Costco"))
        }
        assertEquals("LinkBody(fb.ask)", FbAsk(T, T + 600).toString())
    }

    /** Random and damaged requests: never an exception (decodeOrThrow would throw it); anything accepted is exactly what it writes again. */
    @Test fun damagedRequestsNeverThrow() {
        val r = Random(1503)
        var accepted = 0
        for (b in samples()) {
            val good = LinkRequests.encode(req(b))!!
            val jsonLen = LinkRequests.json(req(b)).toByteArray().size
            repeat(800) {
                val f = good.copyOf()
                repeat(1 + r.nextInt(3)) {
                    val i = r.nextInt(jsonLen)
                    f[i] = when (r.nextInt(4)) {
                        0 -> r.nextInt(256).toByte()
                        1 -> "{}[]\",:0123456789.-eE \\u".random(kotlin.random.Random(r.nextLong())).code.toByte()
                        else -> (f[i] + if (r.nextBoolean()) 1 else -1).toByte()
                    }
                }
                val back = read(f)
                if (back is RequestRead.Ok) { accepted++; assertArrayEquals(f, LinkRequests.encode(back.request)) }
            }
            // Cut anywhere, then padded again.
            repeat(100) {
                val cut = LinkRequests.json(req(b)).let { it.substring(0, r.nextInt(it.length)) }
                assertFalse(read(pad(cut)) is RequestRead.Ok)
            }
        }
        repeat(3_000) {
            val f = ByteArray(512 * (1 + r.nextInt(3))).also(r::nextBytes)
            read(f)
            LinkRequests.decode(f, LinkRequests.LIVE)
        }
        assertTrue(accepted > 0)
    }
}
