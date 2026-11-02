// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.helpers.LanRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C3's decision on C2's open risk, a dual-stack server at home (ConnRules.lookupHomeWins, the rule the client applies on
 * every lookup): a lookup that gives home and internet addresses keeps the home ones, over http and https alike, in their
 * order; anything else is decided exactly as ConnRules.lookup decides it (ConnRulesTest), so an http name that resolves
 * only to the internet is still "moved". An address that is neither is dropped from either (C12's M3).
 */
class ConnRulesHomeWinsTest {
    private fun ip(s: String): ByteArray = LanRule.literal(s)!!
    private fun homeWins(https: Boolean, vararg a: String) = ConnRules.lookupHomeWins(https, a.map(::ip))
    private fun kept(l: ConnRules.Lookup): List<String> = (l as ConnRules.Lookup.Ok).addresses.map { java.net.InetAddress.getByAddress(it).hostAddress }

    @Test fun aDualStackServerAtHomeKeepsItsHomeAddresses() {
        for (https in listOf(true, false)) {
            // A home router's name: a private IPv4 address and a global IPv6 one (and its unique-local one).
            val l = homeWins(https, "2a02:8070:1:2::5", "192.168.178.20", "fd00::5")
            assertEquals(ConnWhere.HOME, (l as ConnRules.Lookup.Ok).where)
            assertEquals(listOf("192.168.178.20", "fd00:0:0:0:0:0:0:5"), kept(l))
            // Tailscale's: 100.64/10 beside a global address.
            assertEquals(listOf("100.101.102.103"), kept(homeWins(https, "100.101.102.103", "8.8.8.8")))
            // IPv4-mapped counts as its IPv4 address.
            assertEquals(ConnWhere.HOME, (homeWins(https, "::ffff:192.168.1.5", "2606:4700::1111") as ConnRules.Lookup.Ok).where)
            // C12's M3: an address that is neither is dropped first.
            assertEquals(listOf("192.168.1.5"), kept(homeWins(https, "8.8.8.8", "192.168.1.5", "224.0.0.1")))
            assertEquals(listOf("192.168.1.5"), kept(homeWins(https, "192.168.1.5", "8.8.8.8", "0.0.0.0")))
        }
    }

    @Test fun everythingElseIsDecidedAsLookupDecidesIt() {
        val cases = listOf(listOf("192.168.1.5"), listOf("8.8.8.8"), listOf("8.8.8.8", "2606:4700::1111"), listOf("192.168.1.5", "0.0.0.0"),
            listOf("64:ff9b::808:808"), listOf("64:ff9b::808:808", "8.8.8.8"), listOf("::ffff:8.8.8.8"), emptyList())
        for (https in listOf(true, false)) for (c in cases) {
            val a = c.map(::ip)
            val want = ConnRules.lookup(https, a)
            val got = ConnRules.lookupHomeWins(https, a)
            when (want) {
                is ConnRules.Lookup.Refused -> assertEquals("$c https=$https", want, got)
                is ConnRules.Lookup.Ok -> {
                    assertEquals("$c https=$https", want.where, (got as ConnRules.Lookup.Ok).where)
                    assertEquals(want.addresses.map { it.toList() }, got.addresses.map { it.toList() })
                }
            }
        }
        // http is still home only: a name that now resolves only to the internet has moved, and nothing is kept.
        assertEquals(ConnRules.Lookup.Refused(ConnFailure.MOVED), homeWins(false, "8.8.8.8", "2606:4700::1111"))
        // Only addresses that are neither: refused.
        assertEquals(ConnRules.Lookup.Refused(ConnFailure.NOWHERE), homeWins(true, "0.0.0.0", "64:ff9b::808:808"))
        assertEquals(ConnRules.Lookup.Refused(ConnFailure.UNREACHABLE), ConnRules.lookupHomeWins(true, emptyList()))
    }

    /** What the client keeps is a copy, never the resolver's own arrays. */
    @Test fun theKeptAddressesAreCopies() {
        val a = ip("192.168.1.5")
        val l = ConnRules.lookupHomeWins(false, listOf(a, ip("8.8.8.8"))) as ConnRules.Lookup.Ok
        a[3] = 9
        assertTrue(l.addresses.single().contentEquals(ip("192.168.1.5")))
    }
}
