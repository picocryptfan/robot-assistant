package app.heyrobot.core.helpers

import app.heyrobot.core.helpers.LanRule.Range
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRuleTest {
    private fun ip(s: String): ByteArray = LanRule.literal(s) ?: throw AssertionError("not an address: $s")
    private fun range(s: String) = LanRule.classify(ip(s))

    @Test fun everyRange() {
        assertEquals(Range.LOOPBACK, range("127.0.0.1"))
        assertEquals(Range.LOOPBACK, range("127.255.0.9"))
        assertEquals(Range.LOOPBACK, range("::1"))
        for (a in listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.0.1", "192.168.255.255")) assertEquals(a, Range.PRIVATE, range(a))
        for (a in listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1"))
            assertEquals(a, Range.PUBLIC, range(a))
        assertEquals(Range.LINK_LOCAL, range("169.254.10.20"))
        assertEquals(Range.LINK_LOCAL, range("fe80::1"))
        assertEquals(Range.LINK_LOCAL, range("febf::1"))
        // Tailscale's 100.64.0.0 through 100.127.255.255.
        assertEquals(Range.CGNAT, range("100.64.0.1"))
        assertEquals(Range.CGNAT, range("100.101.102.103"))
        assertEquals(Range.CGNAT, range("100.127.255.255"))
        // IPv6 unique local (Tailscale's IPv6 too).
        assertEquals(Range.ULA, range("fd7a:115c:a1e0::1"))
        assertEquals(Range.ULA, range("fc00::1"))
        assertEquals(Range.PUBLIC, range("2001:4860:4860::8888"))
        // IPv4-mapped: the IPv4 address decides.
        assertEquals(Range.PRIVATE, range("::ffff:192.168.1.5"))
        assertEquals(Range.PUBLIC, range("::ffff:8.8.8.8"))
        // NAT64 maps to public IPv4: never cleartext.
        assertEquals(Range.PUBLIC, range("64:ff9b::192.168.1.5"))
        // Never a server.
        for (a in listOf("0.0.0.0", "255.255.255.255", "224.0.0.251", "239.1.1.1", "0.1.2.3", "::", "ff02::fb")) assertEquals(a, Range.BLOCKED, range(a))
    }

    @Test fun cleartextOnlyToYourOwnNetwork() {
        val home = listOf(ip("192.168.1.20"))
        assertTrue(LanRule.decide(https = false, home) is LanRule.Decision.Allow)
        assertEquals(LanRule.Decision.Block(LanRule.NOT_LOCAL), LanRule.decide(https = false, listOf(ip("93.184.216.34"))))
        // A name that resolves to both: only the private one is used.
        val mixed = LanRule.decide(https = false, listOf(ip("93.184.216.34"), ip("10.0.0.5"))) as LanRule.Decision.Allow
        assertEquals(1, mixed.use.size)
        assertArrayEquals(ip("10.0.0.5"), mixed.use[0])
        assertEquals(1, LanRule.cleartextOnly(listOf(ip("93.184.216.34"), ip("10.0.0.5"))).size)
        // Nothing resolved: refused.
        assertTrue(LanRule.decide(https = false, emptyList()) is LanRule.Decision.Block)
        // CGNAT and link-local work, with a warning.
        assertTrue((LanRule.decide(false, listOf(ip("100.100.1.2"))) as LanRule.Decision.Allow).warning!!.contains("Tailscale"))
        assertTrue((LanRule.decide(false, listOf(ip("169.254.1.2"))) as LanRule.Decision.Allow).warning!!.contains("link-local"))
        assertNull((LanRule.decide(false, home) as LanRule.Decision.Allow).warning)
        // https may go anywhere (TLS protects it), never to a multicast or unspecified address.
        assertTrue(LanRule.decide(https = true, listOf(ip("93.184.216.34"))) is LanRule.Decision.Allow)
        assertTrue(LanRule.decide(https = true, listOf(ip("0.0.0.0"))) is LanRule.Decision.Block)
    }

    @Test fun typedAddresses() {
        val bare = LanRule.normalize("192.168.1.20")!!
        assertTrue(bare.probe)
        assertEquals(listOf("http://192.168.1.20:11434/v1", "http://192.168.1.20:1234/v1", "http://192.168.1.20:9931/v1", "http://192.168.1.20:8080/v1",
            "http://192.168.1.20:8000/v1", "http://192.168.1.20:3000/api"), bare.candidates)
        assertEquals(listOf("http://desktop.lan:11434/v1"), LanRule.normalize("desktop.lan:11434")!!.candidates)
        assertEquals(listOf("https://box.tail1234.ts.net/v1"), LanRule.normalize("https://box.tail1234.ts.net")!!.candidates)
        assertEquals(listOf("https://box.tail1234.ts.net/v1"), LanRule.normalize("HTTPS://Box.Tail1234.ts.net/")!!.candidates)
        assertEquals(listOf("http://10.0.0.2:3000/api"), LanRule.normalize("10.0.0.2:3000")!!.candidates)
        assertEquals(listOf("http://10.0.0.2:8080/custom/v1"), LanRule.normalize("http://10.0.0.2:8080/custom/v1")!!.candidates)
        assertEquals("http://[fd7a:115c::5]:11434/v1", LanRule.normalize("[fd7a:115c::5]:11434")!!.candidates.single())
        assertEquals("http://192.168.1.20/v1", LanRule.normalize("http://192.168.1.20")!!.candidates.first())
        // Refused: userinfo, a query, a fragment, another scheme, spaces, a bad port, nothing.
        for (bad in listOf("http://user:pass@10.0.0.1", "10.0.0.1/?x=1", "10.0.0.1#top", "ftp://10.0.0.1", "file:///etc/hosts", "10.0.0.1:99999",
            "10.0.0 .1", "", "   ", "http://", "fe80::1", "javascript:alert(1)", "10.0.0.1:abc", "[fe80::1"))
            assertNull(bad, LanRule.normalize(bad))
        assertTrue(LanRule.normalize("box.tail1234.ts.net")!!.hint!!.contains("Tailscale"))
        assertTrue(LanRule.normalize("nas.local")!!.hint!!.contains(".local"))
        assertNull(LanRule.normalize("192.168.1.20")!!.hint)
    }

    @Test fun literalsOnly() {
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), LanRule.literal("10.0.0.1"))
        assertNull(LanRule.literal("desktop.lan"))
        assertNull(LanRule.literal("256.1.1.1"))
        assertNull(LanRule.literal("01.2.3.4"))
        assertNull(LanRule.literal("1.2.3"))
        assertNull(LanRule.literal("1::2::3"))
        assertEquals(16, LanRule.literal("::ffff:10.0.0.1")!!.size)
        assertEquals(16, LanRule.literal("[2001:db8::1]")!!.size)
    }
}
