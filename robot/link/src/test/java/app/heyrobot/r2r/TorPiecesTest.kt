// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.BridgeLine
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.TorBridges
import app.heyrobot.core.link.TorBuiltInBridges
import app.heyrobot.core.link.TorRoute
import app.heyrobot.core.link.OnionKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * Job L3: Tor's pieces that run without Tor. The onion address's checksum (Robot's own SHA3-256 against the JDK's and FIPS
 * 202's examples, and real v3 addresses), the torrc (unix: sockets only, never a TCP port), the control commands Robot builds,
 * the control protocol read from a fake Tor (multi-line and data replies, a refusal, a reply that breaks the rules), and
 * SOCKS 5 against a fake SOCKS socket (host names only, isolation, Tor's onion errors).
 */
class TorPiecesTest {
    @Test fun sha3MatchesTheJdkAndFips202() {
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", LinkBytes.hex(Sha3.sha256(ByteArray(0))))
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", LinkBytes.hex(Sha3.sha256("abc".toByteArray())))
        val jdk = MessageDigest.getInstance("SHA3-256")
        val r = Random(1502)
        // Every length around the 136-byte rate and its multiples, and random ones.
        for (n in (0..300) + listOf(407, 408, 409, 1_000, 4_096)) {
            val b = r.nextBytes(n)
            assertArrayEquals("length $n", jdk.digest(b), Sha3.sha256(b))
        }
    }

    @Test fun onionAddressesSpellAndReadBack() {
        // Real v3 addresses: the checksum Robot computes is the one Tor wrote.
        for (a in listOf("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad", "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid")) {
            val k = OnionAddress.keyOf(a)
            assertTrue(a, k != null)
            assertEquals(a, OnionAddress.of(k!!))
        }
        val r = Random(7)
        repeat(200) {
            val k = OnionKey(r.nextBytes(32).also { it[0] = 1 })
            val a = OnionAddress.of(k)
            assertEquals(56, a.length)
            assertTrue(a.all { it in 'a'..'z' || it in '2'..'7' })
            assertEquals(k, OnionAddress.keyOf(a))
            // One character changed: the checksum fails (or the version).
            val i = r.nextInt(56)
            val c = if (a[i] == 'a') 'b' else 'a'
            assertNull(OnionAddress.keyOf(a.substring(0, i) + c + a.substring(i + 1)))
        }
        for (bad in listOf("", "x".repeat(56), "DUCKDUCKGOGG42XJOC72X3SJASOWOARFBGCMVFIMAFTT6TWAGSWZCZAD", "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzcza"))
            assertNull(bad, OnionAddress.keyOf(bad))
        // Client authorization: base32 without padding, 52 characters, as ADD_ONION's ClientAuthV3 reads it.
        val pub = r.nextBytes(32)
        val b32 = OnionAddress.authorizedClient(pub)
        assertEquals(52, b32.length)
        assertArrayEquals(pub, OnionAddress.unbase32(b32))
        assertNull(OnionAddress.unbase32("ab")) // a leftover bit
    }

    private val paths = TorPaths(File("/data/user/0/ai.robot_app/files/link/tor"))

    @Test fun theTorrcHasUnixSocketsOnlyAndNeverATcpPort() {
        val torrc = TorConfig.torrc(paths)
        assertEquals("""
            DataDirectory /data/user/0/ai.robot_app/files/link/tor/data
            SocksPort unix:/data/user/0/ai.robot_app/files/link/tor/socks.sock ExtendedErrors IsolateSOCKSAuth
            ControlPort unix:/data/user/0/ai.robot_app/files/link/tor/control.sock
            CookieAuthentication 1
            DisableNetwork 1
            SafeSocks 1
            AvoidDiskWrites 1
            RunAsDaemon 0
            Log notice stdout
            GeoIPFile ""
            GeoIPv6File ""
        """.trimIndent() + "\n", torrc)
        // No port line names a number: every port Tor opens is a unix socket in :link's private folder.
        for (line in torrc.lines()) if (Regex("^\\w*Port\\b").containsMatchIn(line)) assertTrue(line, line.split(' ')[1].startsWith("unix:/"))
        assertFalse(Regex("(?i)\\b(ORPort|DirPort|DNSPort|TransPort|HTTPTunnelPort|MetricsPort|NATDPort)\\b").containsMatchIn(torrc))
        // A path Tor would read as more than one word is refused.
        assertTrue(runCatching { TorConfig.torrc(TorPaths(File("/data/user/0/a b/tor"))) }.isFailure)
        assertTrue(runCatching { TorConfig.torrc(TorPaths(File("relative/tor"))) }.isFailure)
    }

    /**
     * L-bridges: with a bridge on, the same torrc plus UseBridges, ClientTransportPlugin for lyrebird (only when a line
     * names a transport) and one Bridge line each; still no TCP port of Tor's own (lyrebird's loopback port is Tor's to pick).
     */
    @Test fun theTorrcWithBridges() {
        val lyrebird = File("/data/app/~~kQ3x0Zp-9yT2bVw1Lm4n7A==/ai.robot_app-Rb5vX2Ytq8NnKc0Ue9WjHg==/lib/arm64/liblyrebird.so")
        val plain = TorConfig.torrc(paths)
        assertEquals(plain, TorConfig.torrc(paths, emptyList(), lyrebird))
        val snow = TorConfig.torrc(paths, TorBuiltInBridges.snowflake, lyrebird)
        assertEquals(plain + "UseBridges 1\n" +
            "ClientTransportPlugin meek_lite,obfs4,snowflake,webtunnel exec ${lyrebird.path}\n" +
            TorBuiltInBridges.texts.getValue(TorRoute.SNOWFLAKE).joinToString("") { "Bridge $it\n" }, snow)
        for (line in snow.lines()) if (Regex("^\\w*Port\\b").containsMatchIn(line)) assertTrue(line, line.split(' ')[1].startsWith("unix:/"))
        // Each route's lines; obfs4 and meek the same way.
        for (route in listOf(TorRoute.OBFS4, TorRoute.MEEK)) {
            val t = TorConfig.torrc(paths, TorBridges.lines(route, emptyList()), lyrebird)
            assertEquals(TorBridges.lines(route, emptyList()).size, t.lines().count { it.startsWith("Bridge ") })
            assertEquals(1, t.lines().count { it.startsWith("ClientTransportPlugin ") })
        }
        // A plain bridge of your own: no lyrebird at all, so nothing listens on a port.
        val own = listOf(BridgeLine.parse("192.0.2.9:443 0123456789ABCDEF0123456789ABCDEF01234567")!!)
        assertEquals(plain + "UseBridges 1\nBridge 192.0.2.9:443 0123456789ABCDEF0123456789ABCDEF01234567\n", TorConfig.torrc(paths, own, null))
        // A transport without lyrebird, or a lyrebird path Tor would read as more than one word, is refused.
        assertTrue(runCatching { TorConfig.torrc(paths, TorBuiltInBridges.obfs4, null) }.isFailure)
        for (bad in listOf("relative/liblyrebird.so", "/data/app/a b/liblyrebird.so", "/data/app/a#b/liblyrebird.so", "/data/app/\"x\"/liblyrebird.so", "/" + "x".repeat(255)))
            assertTrue(bad, runCatching { TorConfig.torrc(paths, TorBuiltInBridges.obfs4, File(bad)) }.isFailure)
    }

    /** tor-bridge-relay: a bridge at a home address; Tor and lyrebird through the relay, Snowflake's lines left out. */
    @Test fun theTorrcThroughTheRelay() {
        val lyrebird = File("/data/app/x/lib/arm64/liblyrebird.so")
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        val home = listOf(BridgeLine.parse("obfs4 10.0.0.5:443 $fp cert=x iat-mode=0")!!, BridgeLine.parse("[fd00::5]:9001 $fp")!!) + TorBuiltInBridges.snowflake
        val lo = java.net.InetAddress.getLoopbackAddress().hostAddress!!
        val auth = RelayAuth(lo, 40_001, "0a".repeat(16), "b1".repeat(16))
        assertEquals(TorConfig.torrc(paths) + "Socks5Proxy $lo:40001\nSocks5ProxyUsername ${"0a".repeat(16)}\nSocks5ProxyPassword ${"b1".repeat(16)}\n" +
            "UseBridges 1\nClientTransportPlugin obfs4 exec ${lyrebird.path}\nBridge obfs4 10.0.0.5:443 $fp cert=x iat-mode=0\nBridge [fd00::5]:9001 $fp\n",
            TorConfig.torrc(paths, home, lyrebird, auth))
        // Without the relay, the same lines as before it.
        assertEquals(TorConfig.torrc(paths, home, lyrebird, null).replace("ClientTransportPlugin meek_lite,obfs4,snowflake,webtunnel", "x"),
            TorConfig.torrc(paths) + "UseBridges 1\nx exec ${lyrebird.path}\n" + home.joinToString("") { "Bridge ${it.text}\n" })
        // What Tor would read as something else is refused.
        for (bad in listOf(RelayAuth(lo, 0, auth.user, auth.pass), RelayAuth("10.0.0.1", 40_001, auth.user, auth.pass), RelayAuth(lo, 40_001, "a b", auth.pass),
            RelayAuth(lo, 40_001, auth.user, "x\nControlPort 9051"), RelayAuth("localhost", 40_001, auth.user, auth.pass)))
            assertTrue(runCatching { TorConfig.torrc(paths, home, lyrebird, bad) }.isFailure)
    }

    @Test fun theControlCommandsAreTheOnesTorReads() {
        val target = paths.service(0)
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        assertEquals("ADD_ONION NEW:ED25519-V3 Port=7710,unix:/data/user/0/ai.robot_app/files/link/tor/s0.sock\r\n", TorCommands.addOnion(null, target, emptyList()))
        val blob = "ED25519-V3:" + Base64.getEncoder().encodeToString(ByteArray(64) { 3 })
        assertEquals("ADD_ONION $blob Flags=V3Auth Port=7710,unix:/data/user/0/ai.robot_app/files/link/tor/s0.sock " +
            "ClientAuthV3=${OnionAddress.authorizedClient(k1)} ClientAuthV3=${OnionAddress.authorizedClient(k2)}\r\n", TorCommands.addOnion(blob, target, listOf(k1, k2)))
        assertTrue(runCatching { TorCommands.addOnion("RSA1024:xyz", target, emptyList()) }.isFailure)
        assertTrue(runCatching { TorCommands.addOnion(null, File("/a b"), emptyList()) }.isFailure)
        val onion = OnionAddress.of(OnionKey(ByteArray(32) { 9 }))
        assertEquals("ONION_CLIENT_AUTH_ADD $onion x25519:${Base64.getEncoder().encodeToString(k1)}\r\n", TorCommands.clientAuthAdd(onion, k1))
        assertTrue(runCatching { TorCommands.clientAuthAdd("example", k1) }.isFailure)
    }

    /** A fake Tor on the other end of the control connection: [replies] answer the commands in turn. */
    private fun control(vararg replies: String): Pair<TorControl, ByteArrayOutputStream> {
        val sent = ByteArrayOutputStream()
        return TorControl(ByteArrayInputStream(replies.joinToString("").toByteArray()), sent) to sent
    }

    @Test fun controlRepliesAreReadStrictly() {
        val id = OnionAddress.of(OnionKey(ByteArray(32) { 4 }))
        val blob = "ED25519-V3:" + Base64.getEncoder().encodeToString(ByteArray(64) { 5 })
        val (c, sent) = control("250-ServiceID=$id\r\n250-PrivateKey=$blob\r\n250 OK\r\n")
        val pub = c.addOnion(null, paths.service(1), emptyList())
        assertEquals(id, pub.serviceId)
        assertEquals(blob, pub.keyBlob)
        assertTrue(sent.toString().startsWith("ADD_ONION NEW:ED25519-V3 Port=7710,unix:"))
        // Authentication: the cookie in hex, then ownership.
        val (c2, sent2) = control("250 OK\r\n", "250 OK\r\n", "250 OK\r\n")
        c2.authenticate(ByteArray(32) { 0xAB.toByte() })
        assertEquals("AUTHENTICATE ${"AB".repeat(32)}\r\nTAKEOWNERSHIP\r\nRESETCONF __OwningControllerProcess\r\n", sent2.toString())
        // Bootstrap, from GETINFO's line; a data block is skipped.
        assertEquals(100, control("250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"\r\n250 OK\r\n").first.bootstrap())
        assertEquals(42, control("250+info/names=\r\na b\r\n.\r\n250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=42 TAG=x\r\n250 OK\r\n").first.bootstrap())
        // A refusal is a code; the reply's text (it can name an onion) never leaves.
        val refused = runCatching { control("552 Unrecognized key \"$id\"\r\n").first.networkOn(true) }.exceptionOrNull()
        assertEquals(TorProblem.REFUSED, (refused as TorException).problem)
        assertFalse(refused.toString().contains(id))
        // Broken replies: too short, a bad separator, the stream ending, a ServiceID that isn't an onion.
        for (bad in listOf("25\r\n", "250*OK\r\n", "", "250-ServiceID=notanonion\r\n250 OK\r\n"))
            assertTrue(bad, runCatching { control(bad).first.addOnion(blob, paths.service(1), emptyList()) }.exceptionOrNull() is TorException)
        // A command can't carry a second line.
        assertTrue(runCatching { control("250 OK\r\n").first.send("GETINFO x\r\nSIGNAL SHUTDOWN") }.isFailure)
    }

    @Test fun socks5AsksTorByNameWithIsolationAndReadsItsOnionErrors() {
        // Tor's answers: user/password chosen, accepted, then the stream's reply.
        fun tor(rep: Int, method: Int = 2): Pair<ByteArrayInputStream, ByteArrayOutputStream> =
            ByteArrayInputStream(byteArrayOf(5, method.toByte(), 1, 0, 5, rep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)) to ByteArrayOutputStream()
        val onion = OnionAddress.of(OnionKey(ByteArray(32) { 6 })) + ".onion"
        val (i, o) = tor(0)
        Socks5.connect(i, o, onion, 7_710, "pairing")
        val w = o.toByteArray()
        assertArrayEquals(byteArrayOf(5, 1, 2), w.copyOf(3))
        assertArrayEquals(byteArrayOf(1, 7) + "pairing".toByteArray() + byteArrayOf(1, 'x'.code.toByte()), w.copyOfRange(3, 3 + 11))
        val req = w.copyOfRange(14, w.size)
        assertArrayEquals(byteArrayOf(5, 1, 0, 3, onion.length.toByte()) + onion.toByteArray() + byteArrayOf((7_710 ushr 8).toByte(), 7_710.toByte()), req)
        for ((rep, want) in listOf(0xF0 to SocksProblem.ONION_DOWN, 0xF4 to SocksProblem.ONION_AUTH, 0xF5 to SocksProblem.ONION_AUTH, 0xF6 to SocksProblem.ONION_ADDRESS, 1 to SocksProblem.REFUSED)) {
            val (ii, oo) = tor(rep)
            assertEquals(want, (runCatching { Socks5.connect(ii, oo, onion, 7_710, "p") }.exceptionOrNull() as SocksException).problem)
        }
        // No authentication offered while isolation was asked for: refused (Tor always takes user/password).
        val (i3, o3) = tor(0, method = 0)
        assertEquals(SocksProblem.PROTOCOL, (runCatching { Socks5.connect(i3, o3, onion, 7_710, "p") }.exceptionOrNull() as SocksException).problem)
        // Only host names (Tor resolves them): an address with a space or a port of 0 isn't asked for.
        assertTrue(runCatching { Socks5.connect(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), "a b", 80) }.isFailure)
        assertTrue(runCatching { Socks5.connect(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), "x.onion", 0) }.isFailure)
        // A SOCKS socket that closes mid-answer.
        val cut = PipedOutputStream()
        val cutIn = PipedInputStream(cut)
        cut.write(byteArrayOf(5)); cut.close()
        assertEquals(SocksProblem.PROTOCOL, (runCatching { Socks5.connect(cutIn, ByteArrayOutputStream(), onion, 7_710) }.exceptionOrNull() as SocksException).problem)
    }
}
