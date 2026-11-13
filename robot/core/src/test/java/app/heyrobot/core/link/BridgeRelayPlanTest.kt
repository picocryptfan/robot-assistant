// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** tor-bridge-relay: when a bridge leads to a home address, and where the relay may connect. */
class BridgeRelayPlanTest {
    private val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
    private fun l(s: String) = BridgeLine.parse(s)!!

    @Test fun aBridgeAtAHomeAddressNeedsTheRelayAndNoOtherDoes() {
        for (b in TorBuiltInBridges.snowflake + TorBuiltInBridges.obfs4 + TorBuiltInBridges.meek) assertFalse(b.toString(), BridgeRelayPlan.needed(listOf(b)))
        assertFalse(BridgeRelayPlan.needed(listOf(l("192.0.2.9:443 $fp"), l("obfs4 198.51.100.7:9001 $fp cert=x iat-mode=0"))))
        for (home in listOf("10.0.0.5:443 $fp", "obfs4 192.168.1.20:9001 $fp cert=x iat-mode=0", "[fd00::5]:443 $fp", "100.64.1.2:443 $fp",
            "webtunnel [2001:db8::1]:443 $fp url=https://10.0.0.7/x", "webtunnel [2001:db8::1]:443 $fp url=https://example.org/x addr=192.168.1.3:8443",
            "meek_lite 192.0.2.20:80 url=https://example.org front=10.1.1.1"))
            assertTrue(home, BridgeRelayPlan.needed(listOf(l("192.0.2.9:443 $fp"), l(home))))
        // Snowflake's address is never dialled, and its traffic doesn't go through the relay: never a reason for it.
        assertFalse(BridgeRelayPlan.needed(listOf(l("snowflake 10.0.0.9:80 $fp url=https://broker.example/"))))
        assertEquals(listOf(l("10.0.0.5:443 $fp")), BridgeRelayPlan.relayed(listOf(l("10.0.0.5:443 $fp")) + TorBuiltInBridges.snowflake))
    }

    @Test fun theRelayLetsThroughWhereTheLinesLeadAndNothingElse() {
        val t = BridgeRelayPlan.targets(listOf(l("obfs4 10.0.0.5:443 $fp cert=x iat-mode=0"), l("webtunnel [2001:db8::1]:443 $fp url=https://Bridge.Example.org:8443/p"),
            l("meek_lite 192.0.2.20:80 url=https://cdn.example.net front=www.example.com"), l("webtunnel [2001:db8::2]:443 $fp url=http://plain.example.org/p"),
            l("[fd00::5]:9001 $fp"), l("snowflake 192.0.2.3:80 $fp url=https://broker.example/")))
        for ((h, p) in listOf("10.0.0.5" to 443, "bridge.example.org" to 8443, "BRIDGE.example.org" to 8443, "www.example.com" to 443,
            "plain.example.org" to 80, "fd00::5" to 9001, "[fd00:0:0::5]" to 9001)) assertTrue("$h $p", BridgeRelayPlan.allows(t, h, p))
        for ((h, p) in listOf("10.0.0.5" to 444, "10.0.0.6" to 443, "2001:db8::1" to 443, "cdn.example.net" to 443, "broker.example" to 443,
            "192.0.2.3" to 80, "192.0.2.20" to 80, "example.org" to 8443, "10.0.0.5.example" to 443, "fd00::5" to 443)) assertFalse("$h $p", BridgeRelayPlan.allows(t, h, p))
    }

    /**
     * review-tor-bridge-relay-b L1: lyrebird 0.9.0 reads meek's targets before its url and front, and dials a webtunnel addr
     * as given, port names included. A line that leads home that way needs the relay; one that can't be read for sure
     * needs it too, and the relay lets nothing through for it.
     */
    @Test fun everyLineLyrebirdCanDialHomeFromNeedsTheRelay() {
        val meek = l("meek_lite 192.0.2.20:80 url=https://cdn.example.com targets=https://cdn.example.com|192.168.1.5")
        assertTrue(BridgeRelayPlan.needed(listOf(meek)))
        val t = BridgeRelayPlan.targets(listOf(meek))
        assertTrue(BridgeRelayPlan.allows(t, "192.168.1.5", 443))
        assertFalse(BridgeRelayPlan.allows(t, "cdn.example.com", 443))
        val many = BridgeRelayPlan.targets(listOf(l("meek_lite 192.0.2.20:80 url=https://x.example targets=https://a.example|www.example.com+,http://b.example:8080|10.0.0.9:81")))
        for ((h, p) in listOf("www.example.com" to 443, "a.example" to 443, "10.0.0.9" to 81)) assertTrue("$h $p", BridgeRelayPlan.allows(many, h, p))
        for ((h, p) in listOf("x.example" to 443, "b.example" to 8080, "10.0.0.9" to 80)) assertFalse("$h $p", BridgeRelayPlan.allows(many, h, p))
        assertFalse(BridgeRelayPlan.needed(listOf(l("meek_lite 192.0.2.20:80 url=https://x.example targets=https://a.example|www.example.com+b.example"))))
        // Not read for sure: a port name, a host with %XX (Go decodes it), meek targets without their bar.
        for (unsure in listOf("webtunnel [2001:db8::1]:443 $fp url=https://wt.example.com/p addr=192.168.1.5:https",
            "webtunnel [2001:db8::1]:443 $fp url=https://wt.example.com/p addr=wt.example.com:https",
            "webtunnel [2001:db8::1]:443 $fp url=https://%31%39%32.168.1.5/p",
            "meek_lite 192.0.2.20:80 url=https://x.example targets=https://a.example",
            "meek_lite 192.0.2.20:80 url=https://x.example front=192.168.1.5:https")) {
            assertTrue(unsure, BridgeRelayPlan.needed(listOf(l(unsure))))
            assertEquals(unsure, emptyList<BridgeTarget>(), BridgeRelayPlan.targets(l(unsure)))
        }
    }

    /** tor-bridge-relay-c L4: the screen's line on skipped Snowflake lines shows only with a home bridge beside them. */
    @Test fun yourSnowflakeLinesAreSkippedOnlyBesideAHomeBridge() {
        val snow = l("snowflake 192.0.2.9:80 $fp url=https://broker.example/")
        assertTrue(BridgeRelayPlan.skipsSnowflake(listOf(l("10.0.0.5:443 $fp"), snow)))
        assertFalse(BridgeRelayPlan.skipsSnowflake(listOf(l("192.0.2.5:443 $fp"), snow)))
        assertFalse(BridgeRelayPlan.skipsSnowflake(listOf(l("10.0.0.5:443 $fp"))))
    }
}
