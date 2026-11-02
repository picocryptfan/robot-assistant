// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.web.PublicAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 (home or internet, on every resolution mix and on the socket) and D2 (secrets only over https, before any client),
 * and the typed address: numbers in other spellings, IPv4-mapped IPv6, userinfo, dot segments.
 */
class ConnRulesTest {
    private fun ip(s: String): ByteArray = LanRule.literal(s)!!
    private fun lookup(https: Boolean, vararg a: String) = ConnRules.lookup(https, a.map(::ip))

    private val home = listOf("127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.5", "169.254.10.1", "100.64.0.1", "100.127.255.254",
        "::1", "fe80::1", "fd00::5", "fc00::1", "::ffff:192.168.1.5", "::ffff:100.100.1.1")
    private val internet = listOf("8.8.8.8", "1.1.1.1", "203.0.114.1", "100.128.0.1", "172.32.0.1", "2606:4700::1111", "::ffff:8.8.8.8")
    private val neither = listOf("0.0.0.0", "255.255.255.255", "224.0.0.1", "239.1.1.1", "192.0.2.1", "198.51.100.7", "203.0.113.9", "198.18.0.1",
        "192.0.0.1", "192.88.99.1", "240.0.0.1", "::", "ff02::1", "64:ff9b::808:808", "2002:c0a8:105::1", "2001:0:4136:e378::1", "2001:db8::1",
        "::808:808", "3fff::1")

    @Test fun placesAreThreeAndHomeAndInternetNeverOverlap() {
        for (a in home) assertEquals(a, ConnRules.Place.HOME, ConnRules.place(ip(a)))
        for (a in internet) assertEquals(a, ConnRules.Place.INTERNET, ConnRules.place(ip(a)))
        for (a in neither) assertEquals(a, ConnRules.Place.NEITHER, ConnRules.place(ip(a)))
        // Disjoint by construction: no address is both LanRule's cleartext and PublicAddress's public (sampled widely).
        for (x in 0..255) for (y in listOf(0, 1, 16, 31, 64, 127, 128, 168, 254, 255)) {
            val a = byteArrayOf(x.toByte(), y.toByte(), 1, 1)
            assertFalse("$x.$y.1.1", LanRule.classify(a).cleartext && PublicAddress.isPublic(a))
        }
        assertEquals(ConnRules.Place.NEITHER, ConnRules.place(ByteArray(5)))
    }

    @Test fun everyResolutionMix() {
        for (https in listOf(true, false)) {
            assertEquals(ConnWhere.HOME, (lookup(https, "192.168.1.5", "fd00::5") as ConnRules.Lookup.Ok).where)
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.BOTH_KINDS), lookup(https, "192.168.1.5", "8.8.8.8"))
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.BOTH_KINDS), lookup(https, "2606:4700::1111", "fd00::5"))
            // C12's M3: anything that is neither is dropped, never connected to (0.0.0.0 would reach the phone itself), and
            // refused only when nothing else is left.
            assertEquals(listOf("192.168.1.5"), (lookup(https, "192.168.1.5", "0.0.0.0") as ConnRules.Lookup.Ok).addresses.map { java.net.InetAddress.getByAddress(it).hostAddress })
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.NOWHERE), lookup(https, "64:ff9b::808:808"))
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.UNREACHABLE), ConnRules.lookup(https, emptyList()))
        }
        // http is home only; https may be the internet.
        assertEquals(ConnWhere.INTERNET, (lookup(true, "8.8.8.8", "2606:4700::1111") as ConnRules.Lookup.Ok).where)
        assertEquals(ConnRules.Lookup.Refused(ConnFailure.MOVED), lookup(false, "8.8.8.8"))
        // IPv4-mapped IPv6 counts as its IPv4 address.
        assertEquals(ConnRules.Lookup.Refused(ConnFailure.MOVED), lookup(false, "::ffff:8.8.8.8"))
        assertEquals(ConnWhere.HOME, (lookup(false, "::ffff:192.168.1.5") as ConnRules.Lookup.Ok).where)
    }

    /**
     * C12's M3: on an IPv6-only mobile network, DNS64 answers an IPv4-only name with its real address and a NAT64 one
     * (64:ff9b::/96, neither home nor internet): the NAT64 one is dropped and never connected to, the real one kept.
     */
    @Test fun aNat64AddressBesideAPublicOneIsDropped() {
        fun kept(l: ConnRules.Lookup) = (l as ConnRules.Lookup.Ok).addresses.map { java.net.InetAddress.getByAddress(it).hostAddress }
        for (rule in listOf(ConnRules::lookup, ConnRules::lookupHomeWins)) {
            val l = rule(true, listOf(ip("64:ff9b::5db8:d822"), ip("93.184.216.34")))
            assertEquals(ConnWhere.INTERNET, (l as ConnRules.Lookup.Ok).where)
            assertEquals(listOf("93.184.216.34"), kept(l))
            // In either order, and beside home addresses too (whose kind then decides, as before).
            assertEquals(listOf("93.184.216.34"), kept(rule(true, listOf(ip("93.184.216.34"), ip("64:ff9b::5db8:d822")))))
            assertEquals(listOf("192.168.1.5"), kept(rule(false, listOf(ip("192.168.1.5"), ip("0.0.0.0")))))
            // http still reaches home only: the public address left is "moved".
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.MOVED), rule(false, listOf(ip("64:ff9b::5db8:d822"), ip("93.184.216.34"))))
        }
    }

    /** C12's M3: a lookup with nothing but addresses that are neither, and an address typed as one, are still refused. */
    @Test fun onlyNeitherIsStillRefused() {
        for (rule in listOf(ConnRules::lookup, ConnRules::lookupHomeWins)) for (https in listOf(true, false)) {
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.NOWHERE), rule(https, listOf(ip("64:ff9b::5db8:d822"))))
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.NOWHERE), rule(https, neither.map(::ip)))
            assertEquals(ConnRules.Lookup.Refused(ConnFailure.UNREACHABLE), rule(https, emptyList()))
        }
        for (a in listOf("https://[64:ff9b::5db8:d822]", "https://0.0.0.0", "http://[64:ff9b::808:808]:8123"))
            assertEquals(a, ConnRules.Lookup.Refused(ConnFailure.NOWHERE), ConnUrl.parse(a)?.let(ConnRules::literal))
    }

    /** Split DNS: an https name that is home on one call and internet on the next is allowed; each call is one kind. */
    @Test fun httpsMayChangeKindBetweenCalls() {
        assertEquals(ConnWhere.HOME, (lookup(true, "192.168.1.5") as ConnRules.Lookup.Ok).where)
        assertEquals(ConnWhere.INTERNET, (lookup(true, "203.0.114.1") as ConnRules.Lookup.Ok).where)
    }

    @Test fun theSocketIsOfTheLookupsKind() {
        assertTrue(ConnRules.socket(ip("192.168.1.5"), ConnWhere.HOME))
        assertFalse(ConnRules.socket(ip("8.8.8.8"), ConnWhere.HOME))
        assertTrue(ConnRules.socket(ip("8.8.8.8"), ConnWhere.INTERNET))
        assertFalse(ConnRules.socket(ip("100.64.0.1"), ConnWhere.INTERNET))
        assertFalse(ConnRules.socket(ip("0.0.0.0"), ConnWhere.HOME))
        assertFalse(ConnRules.socket(ip("0.0.0.0"), ConnWhere.INTERNET))
        assertFalse(ConnRules.socket(ip("::ffff:8.8.8.8"), ConnWhere.HOME))
    }

    /** D2: every secret kind makes a connection https only, refused before any client. */
    @Test fun secretsOnlyOverHttps() {
        val http = ConnFixtures.url("http://192.168.1.5:8123")
        val cases = listOf(
            ConnFixtures.homeAssistant().copy(address = http),                                            // a token
            ConnFixtures.ntfy().copy(address = http, auth = ConnAuth.None),                               // the topic alone
            ConnFixtures.webhook().copy(address = http, auth = ConnAuth.Bearer),
            ConnFixtures.webhook().copy(address = http, auth = ConnAuth.Basic),
            ConnFixtures.webhook().copy(address = http, auth = ConnAuth.Header("X-Api-Key")),             // a header you marked secret
            ConnFixtures.discord().copy(address = ConnFixtures.url("http://discord.example.com")),        // an address that is the key
            ConnFixtures.routing().copy(auth = ConnAuth.Bearer),
        )
        for (c in cases) {
            assertTrue(c.toString(), ConnRules.secretOverHttp(c))
            assertEquals(c.toString(), ConnFailure.KEY_OVER_HTTP, ConnRules.refusal(c))
            assertTrue(c.toString(), ConnProblem.KEY_OVER_HTTP in ConnCheck.problems(c))
        }
        // No secret: http at home is fine; an address typed as a public number is refused for http.
        assertNull(ConnRules.refusal(ConnFixtures.routing()))
        assertNull(ConnRules.refusal(ConnFixtures.webhook().copy(address = http)))
        assertEquals(ConnFailure.MOVED, ConnRules.refusal(ConnFixtures.routing(address = "http://8.8.8.8:5000")))
        assertTrue(ConnProblem.ADDRESS in ConnCheck.problems(ConnFixtures.routing(address = "http://8.8.8.8:5000")))
        assertEquals(ConnFailure.NOWHERE, ConnRules.refusal(ConnFixtures.routing(address = "https://192.0.2.1")))
        assertNull(ConnRules.refusal(ConnFixtures.routing(address = "https://[fd00::5]:8002")))
        assertEquals(ConnFailure.NOWHERE, ConnRules.refusal(ConnFixtures.handOff()))
    }

    @Test fun typedAddresses() {
        val ok = mapOf(
            "https://home.tail1234.ts.net" to "https://home.tail1234.ts.net",
            "home.tail1234.ts.net" to "https://home.tail1234.ts.net",
            "HTTPS://Home.Example.ORG./" to "https://home.example.org",
            "https://home.example.org:443/ha/" to "https://home.example.org/ha",
            "http://192.168.1.5:8123" to "http://192.168.1.5:8123",
            "http://192.168.1.5:80" to "http://192.168.1.5",
            "https://[FD00::5]:8443" to "https://[fd00::5]:8443",
            "https://[::ffff:192.168.1.5]" to "https://[::ffff:192.168.1.5]",
            "https://ntfy.example.org/a%20b" to "https://ntfy.example.org/a%20b",
        )
        for ((typed, text) in ok) assertEquals(typed, text, ConnUrl.parse(typed)?.text)
        val refused = listOf("", " ", "ftp://x.org", "file:///etc/passwd", "https://user:pw@x.org", "https://x.org@evil.org", "https://x.org/#top",
            "https://x.org/?a=1", "https://x.org/a/../b", "https://x.org/a/%2e%2E/b", "https://x.org/a/./b", "https://x.org/a%2Fb", "https://x.org/a%5cb",
            "https://x.org/a%00", "https://x.org//a", "https://x.org/a b", "https://x.org\\a", "https://exämple.org", "https://x.org:0", "https://x.org:65536",
            "https://x.org:8a", "https://x.org:1:2", "http://3232235777", "http://10.1", "http://010.0.0.1", "http://0x7f000001", "http://0x7f.1",
            "http://127.1", "http://1.2.3.4.5", "https://a.0x10", "https://[fe80::1%25wlan0]", "https://[fe80::1", "https://[::1]x",
            "https://-bad.org", "https://bad-.org", "https://under_score.org", "https://x.org/\u0007", "https://" + "a".repeat(64) + ".org")
        for (r in refused) assertNull(r, ConnUrl.parse(r))
        // A whole address that is a key may end in a query; a fragment never.
        assertEquals("https://discord.example.com/api/webhooks/1/AbC?wait=true",
            ConnUrl.parse("https://discord.example.com/api/webhooks/1/AbC?wait=true", whole = true)?.text)
        assertEquals("https://x.org/?k=v", ConnUrl.parse("https://x.org?k=v", whole = true)?.text)
        assertNull(ConnUrl.parse("https://x.org/a?k=v#f", whole = true))
        assertNull(ConnUrl.parse("https://x.org/a?", whole = true))
        assertNull(ConnUrl.parse("https://x.org/a?k=%0a", whole = true))
    }

    @Test fun originsAndHosts() {
        val u = ConnFixtures.url("https://home.example.org:8443/ha")
        assertEquals("https://home.example.org:8443", u.origin)
        assertEquals("home.example.org:8443", u.shownHost)
        assertEquals("https://home.example.org:8443", u.originOnly().text)
        assertEquals("[fd00::5]", ConnFixtures.url("https://[fd00::5]").shownHost)
        assertNull(u.literal)
        assertEquals(16, ConnFixtures.url("https://[fd00::5]").literal?.size)
        assertEquals("ConnUrl(…)", u.toString())
    }
}
