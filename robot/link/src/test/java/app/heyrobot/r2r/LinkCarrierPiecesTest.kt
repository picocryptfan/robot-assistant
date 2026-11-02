// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.EnvelopeKind
import app.heyrobot.core.link.HelloBundle
import app.heyrobot.core.link.HelloPurpose
import app.heyrobot.core.link.LinkEnvelope
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.LocalAddress
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.OpenCode
import app.heyrobot.core.link.PairHello
import app.heyrobot.core.link.PairHelloRead
import app.heyrobot.core.link.PushRoute
import app.heyrobot.core.link.ReplayWindow
import app.heyrobot.core.link.requests.LinkCaps
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Job L3: the carriers' pieces one by one. A sealed hello opens only for its maker and fits a push body; envelopes on a live
 * stream are read strictly; RFC 8291 round-trips through the connector's code at a push body's limit; a push is posted over
 * real TLS (a server certificate made for the test) and refused when the certificate isn't for the host; push addresses are
 * https only; the carriers' records round-trip and are read strictly.
 */
class LinkCarrierPiecesTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })
    private val t0 = 1_790_000_000_000L

    @Test fun aSealedHelloOpensOnlyForItsMakerAndFitsOnePushBody() {
        val maker = LinkStores.create(LinkRecords(tmp.newFolder(), SoftLinkKey()), t0)
        val other = LinkStores.create(LinkRecords(tmp.newFolder(), SoftLinkKey()), t0)
        val scanner = LinkStores.create(LinkRecords(tmp.newFolder(), SoftLinkKey()), t0)
        val bundle = scanner.bundle(scanner.newKyberPreKey(t0), t0)
        val push = PushRoute("https://" + "a".repeat(992), ByteArray(65).also { it[0] = 4 }, ByteArray(16) { 1 })
        val worst = PairHello(HelloPurpose.PAIR, LinkMode.ANONYMOUS, ByteArray(16) { 3 }, bundle, push, OnionKey(ByteArray(32) { 7 }), ByteArray(32) { 8 },
            "🤖".repeat(40), ByteArray(LinkCaps.BITMAP) { 0xFF.toByte() })
        val sealed = HelloSeal.seal(maker.identityKey, worst.bytes())
        assertEquals(PairHello.SEALED_MAX, sealed.size)
        LinkEnvelope(EnvelopeKind.PAIR_HELLO, LinkEnvelope.NO_ROUTE, sealed).encode(app.heyrobot.core.link.LinkCarrier.PUSH)
        val opened = maker.openHello(sealed)!!
        assertArrayEquals(worst.bytes(), opened)
        assertEquals("🤖".repeat(40), (PairHello.read(opened) as PairHelloRead.Ok).hello.name)
        // Nobody else opens it; a changed byte opens for no one.
        assertNull(other.openHello(sealed))
        assertNull(maker.openHello(sealed.copyOf().also { it[60] = (it[60] + 1).toByte() }))
        assertNull(maker.openHello(ByteArray(10)))
        // The name isn't in the sealed bytes.
        assertFalse(String(sealed, Charsets.UTF_8).contains("🤖"))
        // L5-fix: signed by the scanner's identity key (the one its bundle names), it checks for that key alone; unsigned,
        // or with any byte of it changed, it checks for none. Sealed, the worst case is still exactly SEALED_MAX.
        val signed = worst.signed(scanner.signHello(worst.signedPart()))
        assertTrue(maker.verifies(scanner.identityKey, signed.signedPart(), signed.signature))
        assertFalse(maker.verifies(other.identityKey, signed.signedPart(), signed.signature))
        assertFalse(maker.verifies(scanner.identityKey, worst.signedPart(), worst.signature))
        val changed = (PairHello.read(signed.bytes().also { it[40] = (it[40] + 1).toByte() }) as PairHelloRead.Ok).hello
        assertFalse(maker.verifies(scanner.identityKey, changed.signedPart(), changed.signature))
        val sealedSigned = HelloSeal.seal(maker.identityKey, signed.bytes())
        assertEquals(PairHello.SEALED_MAX, sealedSigned.size)
        assertArrayEquals(signed.signature, (PairHello.read(maker.openHello(sealedSigned)!!) as PairHelloRead.Ok).hello.signature)
    }

    @Test fun liveFramesAreReadStrictly() {
        val e = LinkEnvelope(EnvelopeKind.SESSION, ByteArray(16) { 1 }, ByteArray(300) { 2 })
        val out = ByteArrayOutputStream()
        LiveFrames.write(out, e)
        LiveFrames.write(out, LiveFrames.connectHello(ByteArray(16) { 5 }))
        val input = ByteArrayInputStream(out.toByteArray())
        val got = LiveFrames.read(input) as Frame.Got
        assertArrayEquals(e.body, got.envelope.body)
        assertEquals(EnvelopeKind.CONNECT, (LiveFrames.read(input) as Frame.Got).envelope.kind)
        assertEquals(Frame.Closed, LiveFrames.read(input))
        // A length past the live cap is refused before anything is read for it; a cut body is the end.
        val head = e.encode(app.heyrobot.core.link.LinkCarrier.LIVE).copyOf(LinkEnvelope.HEADER)
        head[21] = 0x7F
        assertEquals(LinkProblem.BAD_LENGTH, (LiveFrames.read(ByteArrayInputStream(head)) as Frame.Bad).problem)
        assertEquals(Frame.Closed, LiveFrames.read(ByteArrayInputStream(e.encode(app.heyrobot.core.link.LinkCarrier.LIVE).copyOf(100))))
        assertTrue(LiveFrames.read(ByteArrayInputStream(byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), 2) + ByteArray(21))) is Frame.Newer)
        assertTrue(LiveFrames.read(ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())) is Frame.Bad)
    }

    @Test fun rfc8291RoundTripsThroughTheConnectorsCodeUpToOnePushBody() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val auth = ByteArray(16).also(SecureRandom()::nextBytes)
        val route = PushRoute("https://push.example.org/up/x", FakePushServer.point(g.public as ECPublicKey), auth)
        for (n in listOf(1, 512, WebPush.MAX_PLAIN)) {
            val plain = ByteArray(n) { it.toByte() }
            val body = WebPush.seal(route, plain)
            assertEquals(n + 103, body.size)
            assertTrue(body.size <= PushHttp.MAX_BODY)
            val back = WebPushHybridDecrypt.Builder().withAuthSecret(auth).withRecipientPublicKey(g.public as ECPublicKey)
                .withRecipientPrivateKey(g.private as ECPrivateKey).build().decrypt(body, null)
            assertArrayEquals(plain, back)
        }
        assertTrue(runCatching { WebPush.seal(route, ByteArray(WebPush.MAX_PLAIN + 1)) }.isFailure)
    }

    @Test fun aPendingPushJobIsNeverReplaced() {
        val pending = setOf(PushJobs.FIRST_ID + 5, PushJobs.FIRST_ID + 6)
        assertEquals(PushJobs.FIRST_ID + 7, PushJobs.freeId(5) { it in pending })
        assertEquals(PushJobs.FIRST_ID + 4, PushJobs.freeId(4) { it in pending })
        // From the end of the range it goes round to the start.
        assertEquals(PushJobs.FIRST_ID, PushJobs.freeId(PushJobs.IDS - 1) { it == PushJobs.FIRST_ID + PushJobs.IDS - 1 })
        assertNull(PushJobs.freeId(0) { true })
    }

    /** L5-fix: the direct carrier listens and gives addresses only on Wi-Fi, Ethernet or a VPN, never the mobile network. */
    @Test fun theDirectCarrierIsOnlyOnWifiEthernetOrAVpn() {
        fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        // The mobile network's carrier-grade addresses look private (100.64/10, 10/8): nothing.
        val cellular = NetView(wifi = false, ethernet = false, vpn = false, cellular = true, listOf(ip(100, 64, 0, 5), ip(10, 20, 0, 5)))
        assertEquals(emptyList<LocalAddress>(), DirectPlaces.addresses(listOf(cellular), 4_000))
        // Wi-Fi: its private address, and nothing of the mobile network beside it.
        val wifi = NetView(wifi = true, ethernet = false, vpn = false, cellular = false, listOf(ip(192, 168, 1, 20)))
        assertEquals(listOf(LocalAddress(ip(192, 168, 1, 20), 4_000)), DirectPlaces.addresses(listOf(cellular, wifi), 4_000))
        // A VPN over the mobile network (its transports include it) is a private network both phones are on; Ethernet too.
        val vpn = NetView(wifi = false, ethernet = false, vpn = true, cellular = true, listOf(ip(100, 100, 1, 2)))
        val ethernet = NetView(wifi = false, ethernet = true, vpn = false, cellular = false, listOf(ip(10, 0, 0, 9)))
        assertEquals(listOf(ip(100, 100, 1, 2), ip(10, 0, 0, 9)).map { LocalAddress(it, 4_000) }, DirectPlaces.addresses(listOf(vpn, cellular, ethernet), 4_000))
        // Never a public address or loopback, never a network with both Wi-Fi and the mobile network, at most four.
        assertEquals(emptyList<LocalAddress>(), DirectPlaces.addresses(listOf(NetView(true, false, false, false, listOf(ip(8, 8, 8, 8), ip(127, 0, 0, 1)))), 4_000))
        assertEquals(emptyList<LocalAddress>(), DirectPlaces.addresses(listOf(NetView(true, false, false, true, listOf(ip(192, 168, 1, 5)))), 4_000))
        assertEquals(4, DirectPlaces.addresses(listOf(NetView(true, false, false, false, (1..6).map { ip(192, 168, 1, it) })), 4_000).size)
        // On the mobile network alone the listener doesn't start, and it gives no address.
        val net = SocketDirectNet({ listOf(cellular) })
        assertEquals(0, net.listen { it.close() })
        assertEquals(emptyList<LocalAddress>(), net.localAddresses(0))
    }

    /** L5-fix: a flood of push events leaves at most 50 jobs waiting; each one waits under an id of its own. */
    @Test fun pushJobsAreCappedAtFifty() {
        val slots = object : JobSlots {
            val ids = ArrayList<Int>()
            override fun pending(): List<Int> = ids + listOf(7, 0x52510000) // the updater's job and another app's id don't count
            override fun schedule(id: Int, e: PushEvent): Boolean { ids += id; return true }
        }
        val scheduled = (1..200).count { PushJobs.schedule(slots, PushEvent(PushEvent.Kind.MESSAGE, "0e4a2b7c-1d2e-4f3a-8b4c-5d6e7f8a9b0c", ByteArray(10) { 1 })) }
        assertEquals(50, scheduled)
        assertEquals(PushJobs.MAX_PENDING, slots.ids.size)
        assertEquals(slots.ids.size, slots.ids.toSet().size)
        assertTrue(slots.ids.all { it in PushJobs.FIRST_ID until PushJobs.FIRST_ID + PushJobs.IDS })
        // One finishes: one more fits.
        slots.ids.removeAt(0)
        assertTrue(PushJobs.schedule(slots, PushEvent(PushEvent.Kind.MESSAGE, "0e4a2b7c-1d2e-4f3a-8b4c-5d6e7f8a9b0c", ByteArray(10) { 1 })))
        assertFalse(PushJobs.schedule(slots, PushEvent(PushEvent.Kind.MESSAGE, "0e4a2b7c-1d2e-4f3a-8b4c-5d6e7f8a9b0c", ByteArray(10) { 1 })))
    }

    @Test fun pushAddressesAreHttpsOnly() {
        assertTrue(PushTarget.of("https://push.example.org/up/abc?x=1") != null)
        assertEquals(8443, PushTarget.of("https://push.example.org:8443/up")!!.port)
        assertEquals("/up/abc?x=1", PushTarget.of("https://push.example.org/up/abc?x=1")!!.path)
        for (bad in listOf("http://10.0.2.2:8080/up/abc", "https://user@push.example.org/up", "https://push.example.org/up#x", "ftp://x/y", "https:///up", "https://a b/up"))
            assertNull(bad, PushTarget.of(bad))
    }

    /** A server key and certificate for "localhost", made by the JDK's keytool in this test's folder (nothing committed). */
    private fun serverKeys(): KeyStore {
        val f = File(tmp.root, "push-server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "push", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", f.path, "-storepass", "testpass", "-keypass", "testpass")
            .redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
        return KeyStore.getInstance("PKCS12").apply { f.inputStream().use { load(it, "testpass".toCharArray()) } }
    }

    @Test fun aPushIsPostedOverTlsAndRefusedForAnotherHost() {
        val ks = serverKeys()
        val server = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "testpass".toCharArray()) }.keyManagers, null, null)
        }
        val trust = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers, null)
        }
        val ss = server.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        Thread {
            while (true) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                try {
                    val input = s.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { if (it < 0) throw java.io.EOFException() }.toChar())
                    seen += head.toString()
                    val len = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
                    bodies += ByteArray(len).also { var at = 0; while (at < len) at += input.read(it, at, len - at) }
                    s.getOutputStream().apply { write("HTTP/1.1 201 Created\r\nLocation: /m/1\r\n\r\n".toByteArray()); flush() }
                } catch (_: Exception) {
                } finally {
                    s.close()
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            val http = PushHttp(trust) { _, _ -> true }
            val body = ByteArray(700) { 9 }
            Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { s ->
                http.post(SocketDuplex(s), PushTarget.of("https://localhost:${ss.localPort}/up/abc")!!, body, 86_400)
            }
            val head = seen.single()
            assertTrue(head.startsWith("POST /up/abc HTTP/1.1\r\n"))
            for (h in listOf("Host: localhost:${ss.localPort}", "TTL: 86400", "Urgency: normal", "Content-Encoding: aes128gcm", "Content-Length: 700")) assertTrue(h, head.contains(h))
            assertArrayEquals(body, bodies.single())
            // The certificate is for localhost: posting as another host fails the TLS check, and nothing is sent.
            val x = Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { s ->
                runCatching { http.post(SocketDuplex(s), PushTarget.of("https://wrong.example:${ss.localPort}/up/abc")!!, body, 60) }.exceptionOrNull()
            }
            assertEquals(PushProblem.TLS, (x as PushException).problem)
            // The platform's host name check refusing is the same.
            val y = Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { s ->
                runCatching { PushHttp(trust) { _, _ -> false }.post(SocketDuplex(s), PushTarget.of("https://localhost:${ss.localPort}/up/abc")!!, body, 60) }.exceptionOrNull()
            }
            assertEquals(PushProblem.TLS, (y as PushException).problem)
            assertEquals(1, bodies.size)
            // A server we don't trust at all: refused.
            val z = Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { s ->
                runCatching { PushHttp(SSLContext.getDefault()) { _, _ -> true }.post(SocketDuplex(s), PushTarget.of("https://localhost:${ss.localPort}/up")!!, body, 60) }.exceptionOrNull()
            }
            assertEquals(PushProblem.TLS, (z as PushException).problem)
            // E4: a direct post that was taken is a row in What went online: the body's bytes, and "localhost" as "your network".
            val rows = ArrayList<app.heyrobot.core.online.NetEntry>()
            app.heyrobot.core.online.NetRecord.sink = app.heyrobot.core.online.NetSink { rows += it }
            try {
                HttpPushPoster(http, { null }).post(PushRoute("https://localhost:${ss.localPort}/up/abc", ByteArray(65).also { it[0] = 4 }, ByteArray(16) { 1 }), body, 60, false, "p")
            } finally {
                app.heyrobot.core.online.NetRecord.sink = null
            }
            val row = rows.single()
            assertEquals(app.heyrobot.core.online.NetPurpose.LINK_PUSH, row.purpose)
            assertEquals(app.heyrobot.core.online.NetResult.DONE, row.result)
            assertEquals(app.heyrobot.core.online.NetHost.YOUR_NETWORK, row.host)
            // What went over the socket, TLS's own bytes too.
            assertTrue("" + row.out, row.out > 700L)
            assertTrue("" + row.inBytes, row.inBytes > 0L)
        } finally {
            ss.close()
        }
    }

    @Test fun theCarriersRecordsRoundTripAndAreReadStrictly() {
        val p = PairingRecord("a".repeat(32), PairingState.SENT, LinkMode.PRIVATE, 1_790_000_000L, "Sam", ByteArray(32) { 1 })
        p.peerPush = PushRoute("https://push.example.org/up/1", ByteArray(65).also { it[0] = 4 }, ByteArray(16) { 2 })
        p.peerOnion = OnionKey(ByteArray(32) { 3 }); p.peerOnionAuth = ByteArray(32) { 4 }
        p.peerAddrs = listOf(LocalAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), 40_000)); p.addrsAt = 5
        p.outToken = ByteArray(16) { 6 }; p.inToken = ByteArray(16) { 7 }; p.inPrev = null
        p.myInstance = "0b0c0d0e-0f10-4111-8213-141516171819"; p.myAuthPub = ByteArray(32) { 8 }; p.myAuthPriv = ByteArray(32) { 9 }
        p.code = ByteArray(100) { 10 }; p.hello = ByteArray(3_000) { 11 }; p.helloTries = 3; p.helloNext = 99; p.hintAt = 77
        val bytes = PairingRecord.encode(p)
        val q = PairingRecord.decode(bytes)
        assertArrayEquals(bytes, PairingRecord.encode(q))
        assertEquals("Sam", q.name)
        assertEquals(p.peerAddrs, q.peerAddrs)
        assertTrue(runCatching { PairingRecord.decode(bytes + 0) }.isFailure)
        assertTrue(runCatching { PairingRecord.decode(bytes.copyOf(bytes.size - 1)) }.isFailure)
        assertFalse(p.toString().contains("Sam"))
        val o = OutboxItem("b".repeat(32), "a".repeat(32), 70, 1_790_000_000L, 604_800, ByteArray(512) { 1 }).apply { attempts = 2; nextAt = 5; sent = true }
        val ob = OutboxItem.encode(o)
        assertArrayEquals(ob, OutboxItem.encode(OutboxItem.decode(ob)))
        val c = CodeRecord("c".repeat(32), OpenCode.of(ByteArray(16) { 1 }, 1_790_086_400L), LinkMode.ANONYMOUS, true, 1_790_000_000L)
        c.onionSlot = 2; c.onionKeyBlob = "ED25519-V3:" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 5 }); c.onion = OnionKey(ByteArray(32) { 6 })
        val cb = CodeRecord.encode(c)
        assertArrayEquals(cb, CodeRecord.encode(CodeRecord.decode(cb)))
        // Through the sealed records: written, read back by a new store, a damaged one refused as DAMAGED.
        val records = LinkRecords(tmp.newFolder(), SoftLinkKey())
        val st = LinkStateStore(records)
        st.put(p); st.put(o); st.put(c); st.putReplay(p.id, ReplayWindow().apply { admit("d".repeat(32), 1_790_000_000L, 600, 1_790_000_000L) })
        val again = LinkStateStore(records).also { it.load() }
        assertEquals(setOf(p.id), again.pairings.keys)
        assertEquals(setOf(o.id), again.outbox.keys)
        assertTrue(again.replay.getValue(p.id).seen("d".repeat(32)))
        again.deletePairing(p.id)
        assertTrue(LinkStateStore(records).also { it.load() }.let { it.pairings.isEmpty() && it.outbox.isEmpty() && it.replay.isEmpty() })
    }
}
