// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.QrCode
import app.heyrobot.core.link.requests.LinkCaps
import app.heyrobot.core.link.requests.RequestType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * robot-to-robot-plan.md §2.3 and §9.1's PairCodeTest: round trip; expiry; one use; five wrong tries; a mode change; text
 * and QR forms. With the pairing hello (R0 §9 item 25): its round trip and its worst case under one push body.
 */
class PairCodeTest {
    private val now = 1_790_000_000L
    private val identity = ByteArray(33) { (it * 7 + 5).toByte() }.also { it[0] = 5 }
    private val onion = OnionKey(ByteArray(32) { (it + 40).toByte() })
    private val onionAuth = ByteArray(32) { (it + 80).toByte() }
    private val lan = LocalAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 23), 48_123)
    private val lan6 = LocalAddress(ByteArray(16) { if (it == 0) 0xFD.toByte() else (it * 13).toByte() }, 65_535)
    private fun push(len: Int = 120) = PushRoute("https://push.example.org/" + "a".repeat(len - 25), ByteArray(65) { if (it == 0) 4 else it.toByte() }, ByteArray(16) { (it + 1).toByte() })

    private fun ok(text: String, form: PairCodeForm = PairCodeForm.PASTED, at: Long = now) = (PairCode.read(text, form, at) as PairCodeRead.Ok).code
    private fun bad(text: String, form: PairCodeForm = PairCodeForm.PASTED, at: Long = now) = (PairCode.read(text, form, at) as? PairCodeRead.Bad)?.problem

    @Test fun codesRoundTripInEveryShape() {
        val shapes = listOf(
            PairCode.make(LinkMode.PRIVATE, identity, now, onion = onion, local = lan).first to PairCodeForm.SCANNED,
            PairCode.make(LinkMode.PRIVATE, identity, now, onion = onion, local = lan6).first to PairCodeForm.SCANNED,
            PairCode.make(LinkMode.ANONYMOUS, identity, now, onion = onion).first to PairCodeForm.SCANNED,
            PairCode.make(LinkMode.PRIVATE, identity, now, push = push()).first to PairCodeForm.PASTED,
            PairCode.make(LinkMode.ANONYMOUS, identity, now, push = push(), onion = onion).first to PairCodeForm.PASTED,
        )
        for ((code, form) in shapes) {
            val back = ok(code.text(), form)
            assertArrayEquals(code.bytes(), back.bytes())
            assertEquals(code.mode, back.mode)
            assertArrayEquals(identity, back.identity)
            assertArrayEquals(code.token, back.token)
            assertEquals(now + 24 * 3600, back.expires)
            assertEquals(code.push?.endpoint, back.push?.endpoint)
            assertEquals(code.onion, back.onion)
            assertEquals(code.local, back.local)
            assertTrue(code.text().startsWith("ROBOT-PAIR-"))
        }
        // Each code has its own token.
        assertFalse(PairCode.make(LinkMode.PRIVATE, identity, now, onion = onion).first.token.contentEquals(PairCode.make(LinkMode.PRIVATE, identity, now, onion = onion).first.token))
    }

    @Test fun textAndQrForms() {
        // The largest code shown in person: Private, an IPv6 address and port, the onion address. It fits core QrCode.
        val inPerson = PairCode.make(LinkMode.PRIVATE, identity, now, onion = onion, local = lan6).first
        assertEquals(111, inPerson.bytes().size)
        assertEquals(159, inPerson.text().length)
        val qrText = inPerson.qrText()!!
        assertEquals(inPerson.text(), qrText)
        assertEquals(8, QrCode.encode(qrText)!!.version)
        // What QrCode holds at its largest is what PairCode assumes.
        assertNotNull(QrCode.encode("x".repeat(PairCode.QR_MAX_BYTES)))
        assertNull(QrCode.encode("x".repeat(PairCode.QR_MAX_BYTES + 1)))
        // A code with a push address is shared as text only: the largest, with a 1,000-byte endpoint.
        val far = PairCode.make(LinkMode.ANONYMOUS, identity, now, push = push(1_000), onion = onion).first
        assertNull(far.qrText())
        assertEquals(1_175, far.bytes().size)
        assertEquals(PairCode.MAX_BYTES, far.bytes().size)
        assertEquals(1_578, far.text().length)
        assertEquals(PairCode.MAX_TEXT, far.text().length)
        ok(far.text())
    }

    @Test fun aPastedCodeMaySitInsideOtherWords() {
        val code = PairCode.make(LinkMode.PRIVATE, identity, now, push = push()).first
        val back = ok("Here's my Robot code: ${code.text()} see you Friday!")
        assertArrayEquals(code.bytes(), back.bytes())
        ok("${code.text()}\n")
        assertEquals(LinkProblem.NO_CODE, bad("hello there"))
        assertEquals(LinkProblem.TWO_CODES, bad(code.text() + " " + code.text()))
        // A messaging app cut it, changed a letter, or added padding: caught by the checksum or the length, before any try is used.
        val t = code.text()
        assertEquals(LinkProblem.BAD_CHECKSUM, bad(t.dropLast(3)))
        val flipped = t.substring(0, 40) + (if (t[40] == 'A') 'B' else 'A') + t.substring(41)
        assertEquals(LinkProblem.BAD_CHECKSUM, bad(flipped))
        assertTrue(PairCode.read("$t==", PairCodeForm.PASTED, now) is PairCodeRead.Ok) // stops at the first '='
        assertTrue(PairCode.read(t.substring(0, 11) + "", PairCodeForm.PASTED, now) is PairCodeRead.Bad)
    }

    @Test fun expiry() {
        val code = PairCode.make(LinkMode.PRIVATE, identity, now, push = push()).first
        ok(code.text(), at = now + 24 * 3600 - 1)
        assertEquals(PairCodeRead.Expired, PairCode.read(code.text(), PairCodeForm.PASTED, now + 24 * 3600))
        // A code that says it lasts much longer than 24 hours wasn't made by Robot (an hour of clock slack allowed).
        val long = PairCode(LinkMode.PRIVATE, identity, code.token, now + 26 * 3600, push = push())
        assertEquals(LinkProblem.BAD_FIELD, bad(long.text()))
        val slack = PairCode(LinkMode.PRIVATE, identity, code.token, now + 24 * 3600 + 1800, push = push())
        ok(slack.text())
        // The maker's side refuses it too after 24 hours, whatever the other clock said.
        val (_, open) = PairCode.make(LinkMode.PRIVATE, identity, now, push = push())
        assertEquals(CodeAttempt.EXPIRED, open.attempt(code.token, now + 24 * 3600).second)
        assertFalse(open.open(now + 24 * 3600))
        assertTrue(open.open(now))
    }

    @Test fun oneUse() {
        val (code, open) = PairCode.make(LinkMode.PRIVATE, identity, now, push = push())
        val (spent, first) = open.attempt(code.token, now + 60)
        assertEquals(CodeAttempt.ACCEPTED, first)
        assertEquals(OpenCode.State.SPENT, spent.state)
        // The same code again, from anyone: refused, and never answered.
        assertEquals(CodeAttempt.SPENT, spent.attempt(code.token, now + 120).second)
        assertFalse(spent.open(now + 120))
    }

    @Test fun fiveWrongTriesCloseTheCode() {
        val (code, open0) = PairCode.make(LinkMode.PRIVATE, identity, now, push = push())
        var open = open0
        val wrong = ByteArray(16) { 1 }
        repeat(4) { i ->
            val (o, a) = open.attempt(wrong, now + i)
            assertEquals(CodeAttempt.WRONG, a)
            assertEquals(i + 1, o.wrong)
            open = o
        }
        // A wrong token of another length is still one wrong try, and never an exception.
        val (o5, a5) = open.attempt(ByteArray(3), now + 10)
        assertEquals(CodeAttempt.CLOSED, a5)
        assertEquals(OpenCode.State.CLOSED, o5.state)
        // Closed for good: even the right token now.
        assertEquals(CodeAttempt.CLOSED, o5.attempt(code.token, now + 11).second)
        // Four wrong tries, then the right one: still accepted.
        val (code2, first) = PairCode.make(LinkMode.PRIVATE, identity, now, push = push())
        var op = first
        repeat(4) { op = op.attempt(wrong, now).first }
        assertEquals(CodeAttempt.ACCEPTED, op.attempt(code2.token, now).second)
        // L5-fix: in person, a hello before your photo is only checked (the code stays open), and one with the right token
        // from another key than the one you photographed is a wrong try: five of those close it too.
        val (code4, open4) = PairCode.make(LinkMode.PRIVATE, identity, now, onion = OnionKey(ByteArray(32) { 3 }))
        assertTrue(open4.accepts(code4.token, now))
        assertFalse(open4.accepts(wrong, now))
        assertFalse(open4.accepts(code4.token, open4.expires))
        assertEquals(OpenCode.State.OPEN, open4.state)
        var missed = open4
        repeat(4) { missed = missed.miss(now) }
        assertEquals(4, missed.wrong)
        assertTrue(missed.accepts(code4.token, now))
        assertEquals(OpenCode.State.CLOSED, missed.miss(now).state)
        assertFalse(missed.miss(now).accepts(code4.token, now))
        assertEquals(OpenCode.State.SPENT, open4.attempt(code4.token, now).first.miss(now).state)
        // Cancel this code: refused at once.
        val (code3, open3) = PairCode.make(LinkMode.PRIVATE, identity, now, push = push())
        val cancelled = open3.cancel()
        assertEquals(OpenCode.State.CANCELLED, cancelled.state)
        assertEquals(CodeAttempt.CANCELLED, cancelled.attempt(code3.token, now).second)
    }

    @Test fun aModeChange() {
        // At first contact each picks a mode; the pairing uses the stricter, whoever picked it.
        assertEquals(LinkMode.ANONYMOUS, LinkMode.stricter(LinkMode.PRIVATE, LinkMode.ANONYMOUS))
        assertEquals(LinkMode.ANONYMOUS, LinkMode.stricter(LinkMode.ANONYMOUS, LinkMode.PRIVATE))
        assertEquals(LinkMode.ANONYMOUS, LinkMode.stricter(LinkMode.ANONYMOUS, LinkMode.ANONYMOUS))
        assertEquals(LinkMode.PRIVATE, LinkMode.stricter(LinkMode.PRIVATE, LinkMode.PRIVATE))
        // Raising is one-sided and at once; lowering waits for the other side's owner.
        assertEquals(LinkMode.ANONYMOUS, ModeChange.raised(LinkMode.PRIVATE))
        assertEquals(LinkMode.ANONYMOUS, ModeChange.raised(LinkMode.ANONYMOUS))
        assertEquals(LinkMode.ANONYMOUS, ModeChange.lowered(LinkMode.ANONYMOUS, otherAccepted = false))
        assertEquals(LinkMode.PRIVATE, ModeChange.lowered(LinkMode.ANONYMOUS, otherAccepted = true))
        // The code carries its maker's mode; an Anonymous code never carries a local address, a pasted code never does.
        val anon = PairCode.make(LinkMode.ANONYMOUS, identity, now, onion = onion).first
        assertEquals(LinkMode.ANONYMOUS, ok(anon.text(), PairCodeForm.SCANNED).mode)
        assertTrue(runCatching { PairCode(LinkMode.ANONYMOUS, identity, anon.token, now + 60, onion = onion, local = lan) }.isFailure)
        val inPerson = PairCode.make(LinkMode.PRIVATE, identity, now, onion = onion, local = lan).first
        assertEquals(LinkProblem.NOT_HERE, bad(inPerson.text(), PairCodeForm.PASTED))
        ok(inPerson.text(), PairCodeForm.SCANNED)
        // The same bytes with the mode flipped to Anonymous are refused (and the checksum is recomputed to get that far).
        val flipped = inPerson.bytes().copyOf(inPerson.bytes().size - 4).also { it[1] = 2 }
        val reflipped = flipped + LinkBytes.sha256(flipped).copyOf(4)
        assertEquals(LinkProblem.NOT_HERE, (PairCode.read(reflipped, PairCodeForm.SCANNED, now) as PairCodeRead.Bad).problem)
    }

    @Test fun onlyPrivateAddressesAndHttpsPushAddresses() {
        fun v4(a: Int, b: Int) = LocalAddress.valid(byteArrayOf(a.toByte(), b.toByte(), 0, 1), 5000)
        assertTrue(v4(10, 0)); assertTrue(v4(172, 16)); assertTrue(v4(172, 31)); assertTrue(v4(192, 168)); assertTrue(v4(100, 64)); assertTrue(v4(100, 127))
        assertFalse(v4(172, 32)); assertFalse(v4(8, 8)); assertFalse(v4(127, 0)); assertFalse(v4(169, 254)); assertFalse(v4(100, 128)); assertFalse(v4(0, 0))
        assertTrue(LocalAddress.valid(ByteArray(16).also { it[0] = 0xFC.toByte() }, 1024))
        assertFalse(LocalAddress.valid(ByteArray(16).also { it[0] = 0xFE.toByte(); it[1] = 0x80.toByte() }, 1024))
        assertFalse(LocalAddress.valid(ByteArray(16).also { it[0] = 0x20 }, 1024))
        assertFalse(LocalAddress.valid(byteArrayOf(10, 0, 0, 1), 1023))
        assertFalse(LocalAddress.valid(ByteArray(5), 5000))
        assertEquals(lan, LocalAddress.ofHex(lan.hex()))
        assertEquals(lan6, LocalAddress.ofHex(lan6.hex()))
        assertNull(LocalAddress.ofHex(lan.hex().uppercase()))
        assertNull(LocalAddress.ofHex("04080808080050"))
        val k = ByteArray(65).also { it[0] = 4 }
        val a = ByteArray(16) { 1 }
        assertTrue(PushRoute.valid("https://ntfy.example/up123", k, a))
        assertFalse(PushRoute.valid("http://ntfy.example/up123", k, a))
        assertFalse(PushRoute.valid("https:///up123", k, a))
        assertFalse(PushRoute.valid("https://ntfy.example/up 123", k, a))
        assertFalse(PushRoute.valid("https://ntfy.example/é", k, a))
        assertFalse(PushRoute.valid("https://" + "a".repeat(993), k, a))
        assertTrue(PushRoute.valid("https://" + "a".repeat(992), k, a))
        assertFalse(PushRoute.valid("https://ntfy.example/up", ByteArray(65).also { it[0] = 2 }, a))
        assertFalse(PushRoute.valid("https://ntfy.example/up", k, ByteArray(16)))
    }

    @Test fun inPersonOneSideSends() {
        val other = identity.copyOf().also { it[1] = (it[1] + 1).toByte() }
        assertEquals(true, PairCode.firstSends(identity, other))
        assertEquals(false, PairCode.firstSends(other, identity))
        assertNull(PairCode.firstSends(identity, identity.copyOf()))
        // Unsigned: 0x80 sorts after 0x7F.
        val hi = identity.copyOf().also { it[1] = 0x80.toByte() }
        val lo = identity.copyOf().also { it[1] = 0x7F }
        assertEquals(true, PairCode.firstSends(lo, hi))
    }

    private fun bundle() = HelloBundle(
        16_380, identity, 0xFF_FFFF, ByteArray(33) { if (it == 0) 5 else it.toByte() }, ByteArray(64) { 7 },
        0xFF_FFFF, ByteArray(1_569) { if (it == 0) 8 else (it % 251).toByte() }, ByteArray(64) { 9 },
    )

    private fun hello(name: String = "Sam", push: PushRoute? = push(), caps: Set<RequestType> = LinkCaps.SUPPORTED, purpose: HelloPurpose = HelloPurpose.PAIR) =
        PairHello(purpose, LinkMode.PRIVATE, ByteArray(16) { 3 }, bundle(), push, onion, onionAuth, name, LinkCaps.bitmap(caps))

    @Test fun theHelloRoundTripsAndItsWorstCaseFitsOnePushBody() {
        val h = hello()
        val back = (PairHello.read(h.bytes()) as PairHelloRead.Ok).hello
        assertArrayEquals(h.bytes(), back.bytes())
        assertEquals("Sam", back.name)
        assertEquals(LinkCaps.SUPPORTED, back.caps)
        assertArrayEquals(h.token, back.token)
        assertEquals(h.bundle.registrationId, back.bundle.registrationId)
        assertArrayEquals(h.bundle.kyberPreKey, back.bundle.kyberPreKey)
        // The worst case: a 1,000-byte endpoint, the longest name in 4-byte characters, every bit of caps (the registry
        // as it stands, and any bit a newer Robot may set).
        val worst = PairHello(HelloPurpose.MOVED, LinkMode.ANONYMOUS, ByteArray(16) { 3 }, bundle(), push(1_000), onion, onionAuth,
            "🤖".repeat(40), ByteArray(32) { 0xFF.toByte() })
        val bytes = worst.bytes()
        assertEquals(PairHello.MAX_BYTES, bytes.size)
        // 1.5 L3, deliberately: 3,106 bytes in L1, 32 more for the onion's client authorization key; L5-fix, deliberately: 64
        // more for the sender's signature.
        assertEquals(3_202, bytes.size)
        // Sealed to the maker's identity key (libsignal's HPKE: 49 bytes more; the link module's HelloSealTest measures
        // it), it still fits one push body's envelope.
        assertEquals(3_251, PairHello.SEALED_MAX)
        assertTrue(PairHello.SEALED_MAX <= LinkCarrier.PUSH.maxBody)
        assertTrue(PairHello.SEALED_MAX <= 3_968)
        LinkEnvelope(EnvelopeKind.PAIR_HELLO, LinkEnvelope.NO_ROUTE, ByteArray(PairHello.SEALED_MAX)).encode(LinkCarrier.PUSH)
        val wb = (PairHello.read(bytes) as PairHelloRead.Ok).hello
        assertEquals(RequestType.entries.toSet(), wb.caps)
        assertArrayEquals(ByteArray(32) { 0xFF.toByte() }, wb.capsBitmap)
        // Every type in the registry has a bit; no reserved code is used.
        for (t in RequestType.entries) assertTrue(t.code in 1..255 && t.code !in RequestType.RESERVED_CODES)
        assertEquals(RequestType.entries.size, RequestType.entries.map { it.code }.toSet().size)
    }

    /** L5-fix: the hello's last 64 bytes are its signature, over the context string and every byte before it. */
    @Test fun aHelloCarriesItsSignatureOverEverythingBeforeIt() {
        val unsigned = hello()
        assertArrayEquals(ByteArray(PairHello.SIGNATURE), unsigned.signature)
        val sig = ByteArray(PairHello.SIGNATURE) { (it * 3 + 1).toByte() }
        val h = unsigned.signed(sig)
        val bytes = h.bytes()
        assertEquals(unsigned.bytes().size, bytes.size)
        assertArrayEquals(sig, bytes.copyOfRange(bytes.size - PairHello.SIGNATURE, bytes.size))
        assertArrayEquals(unsigned.bytes().copyOf(bytes.size - PairHello.SIGNATURE), bytes.copyOf(bytes.size - PairHello.SIGNATURE))
        // What is signed: the context, then the hello without its signature (the same whatever the signature).
        val context = "robot-link-hello-sig-v1".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(context + bytes.copyOf(bytes.size - PairHello.SIGNATURE), h.signedPart())
        assertArrayEquals(unsigned.signedPart(), h.signedPart())
        val back = (PairHello.read(bytes) as PairHelloRead.Ok).hello
        assertArrayEquals(sig, back.signature)
        assertArrayEquals(h.signedPart(), back.signedPart())
        // Any byte changed changes what is signed; a signature cut short is no hello.
        assertFalse(h.signedPart().contentEquals((PairHello.read(bytes.copyOf().also { it[30] = (it[30] + 1).toByte() }) as PairHelloRead.Ok).hello.signedPart()))
        assertEquals(LinkProblem.TRUNCATED, (PairHello.read(bytes.copyOf(bytes.size - 1)) as PairHelloRead.Bad).problem)
        assertTrue(runCatching { unsigned.signed(ByteArray(63)) }.isFailure)
    }

    @Test fun aHelloIsReadStrictly() {
        val good = hello().bytes()
        assertEquals(LinkProblem.TRAILING, (PairHello.read(good + 0) as PairHelloRead.Bad).problem)
        for (n in 0 until good.size step 7) assertTrue(PairHello.read(good.copyOf(n)) is PairHelloRead.Bad)
        assertEquals(PairHelloRead.Newer(2), PairHello.read(good.copyOf().also { it[3] = 2 }))
        assertEquals(LinkProblem.BAD_MAGIC, (PairHello.read(good.copyOf().also { it[0] = 0 }) as PairHelloRead.Bad).problem)
        // A name that hides or reorders text, or a blank one, isn't a name.
        for (n in listOf("Sam\u202Eevil", "Sam\nBob", " Sam", "", "\u200F", "a".repeat(41)))
            assertTrue(n, runCatching { hello(name = n) }.isFailure)
        // Neither push nor onion: nowhere to answer.
        assertTrue(runCatching { PairHello(HelloPurpose.PAIR, LinkMode.PRIVATE, ByteArray(16) { 3 }, bundle(), null, null, null, "Sam", ByteArray(32)) }.isFailure)
        val at = 4 + 2 + 16 + HelloBundle.SIZE
        assertEquals(1 or 2, good[at].toInt())
        assertEquals(LinkProblem.BAD_FIELD, (PairHello.read(good.copyOf().also { it[at] = 0 }) as PairHelloRead.Bad).problem)
        assertEquals(LinkProblem.BAD_FIELD, (PairHello.read(good.copyOf().also { it[at] = 7 }) as PairHelloRead.Bad).problem)
        // A Kyber key of another type, a registration id of 0.
        assertTrue(PairHello.read(good.copyOf().also { it[4 + 2 + 16 + 4 + 33 + 4 + 33 + 64 + 4] = 7 }) is PairHelloRead.Bad)
        assertTrue(PairHello.read(good.copyOf().also { for (i in 22..25) it[i] = 0 }) is PairHelloRead.Bad)
        // 1.5 L3: an onion comes with its client authorization key, never without; an all-zero key isn't one.
        assertTrue(runCatching { PairHello(HelloPurpose.PAIR, LinkMode.PRIVATE, ByteArray(16) { 3 }, bundle(), null, onion, null, "Sam", ByteArray(32)) }.isFailure)
        assertTrue(runCatching { PairHello(HelloPurpose.PAIR, LinkMode.PRIVATE, ByteArray(16) { 3 }, bundle(), push(), null, onionAuth, "Sam", ByteArray(32)) }.isFailure)
        val authAt = at + 1 + 2 + push().endpoint.length + 65 + 16 + 32
        assertArrayEquals(onionAuth, good.copyOfRange(authAt, authAt + 32))
        assertEquals(LinkProblem.BAD_FIELD, (PairHello.read(good.copyOf().also { it.fill(0, authAt, authAt + 32) }) as PairHelloRead.Bad).problem)
        assertArrayEquals(onionAuth, (PairHello.read(good) as PairHelloRead.Ok).hello.onionAuth)
    }

    /** Random and damaged codes and hellos: never an exception; anything accepted is exactly what it writes again. */
    @Test fun damagedCodesAndHellosNeverThrow() {
        val r = Random(1502)
        val codes = listOf(
            PairCode.make(LinkMode.PRIVATE, identity, now, onion = onion, local = lan).first to PairCodeForm.SCANNED,
            PairCode.make(LinkMode.ANONYMOUS, identity, now, push = push(300), onion = onion).first to PairCodeForm.PASTED,
        )
        for ((code, form) in codes) {
            val good = code.bytes()
            repeat(5_000) {
                val f = good.copyOf(if (r.nextInt(4) == 0) r.nextInt(good.size + 8) else good.size)
                repeat(1 + r.nextInt(3)) { if (f.isNotEmpty()) f[r.nextInt(f.size)] = r.nextInt(256).toByte() }
                // Half the time the checksum is made right again, so the fields themselves are tried.
                val g = if (r.nextBoolean() && f.size > 4) f.copyOf(f.size - 4).let { it + LinkBytes.sha256(it).copyOf(4) } else f
                val back = PairCode.read(g, form, now)
                if (back is PairCodeRead.Ok) assertArrayEquals(g, back.code.bytes())
                PairCode.read(PairCode.PREFIX + LinkBytes.b64(g), form, now)
            }
            val text = code.text()
            repeat(2_000) {
                val chars = text.toCharArray()
                repeat(1 + r.nextInt(3)) { chars[r.nextInt(chars.size)] = (32 + r.nextInt(95)).toChar() }
                PairCode.read(String(chars), form, now)
            }
        }
        val good = hello().bytes()
        var accepted = 0
        repeat(5_000) {
            val f = good.copyOf(if (r.nextInt(4) == 0) r.nextInt(good.size + 8) else good.size)
            repeat(1 + r.nextInt(3)) { if (f.isNotEmpty()) f[r.nextInt(f.size)] = r.nextInt(256).toByte() }
            val back = PairHello.read(f)
            if (back is PairHelloRead.Ok) { accepted++; assertArrayEquals(f, back.hello.bytes()) }
        }
        assertTrue(accepted > 0)
        repeat(2_000) { PairHello.read(ByteArray(r.nextInt(4_000)).also(r::nextBytes)) }
    }

    @Test fun nothingOfACodeOrHelloShowsInALogLine() {
        val code = PairCode.make(LinkMode.PRIVATE, identity, now, push = PushRoute("https://zq9marker.example/x", ByteArray(65).also { it[0] = 4 }, ByteArray(16) { 1 })).first
        val h = hello(name = "zq9marker", push = PushRoute("https://zq9marker.example/x", ByteArray(65).also { it[0] = 4 }, ByteArray(16) { 1 }))
        for (s in listOf(code.toString(), h.toString(), h.bundle.toString(), PairCode.read(code.text(), PairCodeForm.PASTED, now).toString(),
            PairHello.read(h.bytes()).toString(), lan.toString(), onion.toString(), code.push.toString()))
            assertFalse(s, s.contains("zq9marker") || s.contains(LinkBytes.hex(code.token)) || s.contains("192"))
    }
}
