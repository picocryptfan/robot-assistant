// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * L-bridges (robot-to-robot-carriers-research.md, "The build job"): Settings › Robot to Robot › Tor bridges. Off by default;
 * each choice's routes in order; bridge lines Tor's Bridge option takes and nothing else (a pasted line can't add a torrc
 * option); Tor Browser's built-in lines pinned as taken; the bootstrap watch that moves Automatic on to the next route;
 * link_settings.json's two new keys, failing closed.
 */
class TorBridgesTest {
    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun offByDefaultAndPlainTorExactlyAsBefore() {
        val d = LinkSettings()
        assertEquals(BridgeChoice.OFF, d.bridges)
        assertEquals(emptyList<BridgeLine>(), d.ownBridges)
        assertEquals(listOf(TorRoute.PLAIN), d.torRoutes)
        assertEquals(emptyList<BridgeLine>(), TorPlan.of(d).lines(TorRoute.PLAIN))
        assertFalse(TorPlan.of(d).needsLyrebird(TorRoute.PLAIN))
        // The file a Robot without bridges writes is the same as before: no new key until you choose one.
        val text = LinkSettingsCodec.encode(d)
        assertFalse(text, text.contains("bridges") || text.contains("ownBridges"))
    }

    @Test fun eachChoiceItsRoutesInOrder() {
        val own = listOf(BridgeLine.parse("192.0.2.9:443 0123456789ABCDEF0123456789ABCDEF01234567")!!)
        assertEquals(listOf(TorRoute.PLAIN, TorRoute.SNOWFLAKE, TorRoute.OBFS4, TorRoute.MEEK), TorBridges.routes(BridgeChoice.AUTO, own))
        assertEquals(listOf(TorRoute.SNOWFLAKE), TorBridges.routes(BridgeChoice.SNOWFLAKE, own))
        assertEquals(listOf(TorRoute.OBFS4), TorBridges.routes(BridgeChoice.OBFS4, own))
        assertEquals(listOf(TorRoute.OWN), TorBridges.routes(BridgeChoice.OWN, own))
        // Your own bridge with no line: no route, so Tor stays off rather than connect without a bridge.
        assertEquals(emptyList<TorRoute>(), TorBridges.routes(BridgeChoice.OWN, emptyList()))
        assertEquals(own, TorBridges.lines(TorRoute.OWN, own))
        // A plain bridge of your own needs no lyrebird; every built-in route does.
        val plan = TorPlan(TorBridges.routes(BridgeChoice.AUTO, own), own)
        assertFalse(plan.needsLyrebird(TorRoute.OWN))
        for (r in listOf(TorRoute.SNOWFLAKE, TorRoute.OBFS4, TorRoute.MEEK)) assertTrue(r.name, plan.needsLyrebird(r) && r.bridged)
        assertFalse(TorRoute.PLAIN.bridged)
    }

    /** Automatic starts from the route Tor is still connecting over, else the one that last worked, and tries each once. */
    @Test fun theOrderOfAStart() {
        val p = TorPlan(TorBridges.routes(BridgeChoice.AUTO, emptyList()))
        assertEquals(listOf(TorRoute.PLAIN, TorRoute.SNOWFLAKE, TorRoute.OBFS4, TorRoute.MEEK), p.order(null, null))
        assertEquals(listOf(TorRoute.OBFS4, TorRoute.MEEK, TorRoute.PLAIN, TorRoute.SNOWFLAKE), p.order(null, TorRoute.OBFS4))
        assertEquals(listOf(TorRoute.MEEK, TorRoute.PLAIN, TorRoute.SNOWFLAKE, TorRoute.OBFS4), p.order(TorRoute.MEEK, TorRoute.OBFS4))
        // A route no longer chosen (you changed Settings) is no starting point.
        assertEquals(listOf(TorRoute.SNOWFLAKE), TorPlan(listOf(TorRoute.SNOWFLAKE)).order(TorRoute.PLAIN, TorRoute.OBFS4))
        assertEquals(emptyList<TorRoute>(), TorPlan(emptyList()).order(null, null))
    }

    @Test fun theBootstrapWatch() {
        // With another route to try: stalled after the stall time without progress, never while progress moves.
        val w = BootstrapWatch(0, 30_000, 120_000, fallback = true)
        assertEquals(BootstrapWatch.Verdict.WAIT, w.see(5, 0))
        assertEquals(BootstrapWatch.Verdict.WAIT, w.see(5, 29_999))
        assertEquals(BootstrapWatch.Verdict.WAIT, w.see(10, 29_999))
        assertEquals(BootstrapWatch.Verdict.WAIT, w.see(10, 59_000))
        assertEquals(BootstrapWatch.Verdict.STALLED, w.see(10, 59_999))
        // Progress that goes back doesn't count as progress.
        val back = BootstrapWatch(0, 30_000, 120_000, fallback = true)
        back.see(50, 0)
        assertEquals(BootstrapWatch.Verdict.STALLED, back.see(40, 30_000))
        // Done at 100%, whatever the time.
        assertEquals(BootstrapWatch.Verdict.DONE, BootstrapWatch(0, 30_000, 120_000, fallback = true).see(100, 500_000))
        // The only route: no stall, only the timeout (as plain Tor always waited).
        val only = BootstrapWatch(0, 30_000, 120_000, fallback = false)
        assertEquals(BootstrapWatch.Verdict.WAIT, only.see(5, 0))
        assertEquals(BootstrapWatch.Verdict.WAIT, only.see(5, 119_999))
        assertEquals(BootstrapWatch.Verdict.TIMED_OUT, only.see(5, 120_000))
        // Moving but slow still ends at the timeout.
        val slow = BootstrapWatch(0, 30_000, 120_000, fallback = true)
        for (t in 0 until 120_000 step 10_000) assertEquals(BootstrapWatch.Verdict.WAIT, slow.see(t / 10_000, t.toLong()))
        assertEquals(BootstrapWatch.Verdict.TIMED_OUT, slow.see(20, 120_000))
        assertEquals(30_000L, BootstrapWatch.STALL_PLAIN_MS)
        assertEquals(60_000L, BootstrapWatch.STALL_BRIDGE_MS)
    }

    /** Tor Browser's built-in lines, as taken on 28 Oct 2026: a change to them is deliberate (refreshed at each release). */
    @Test fun theBuiltInLinesArePinnedAsTaken() {
        assertEquals("2026-11-02", TorBuiltInBridges.TAKEN)
        val t = TorBuiltInBridges.texts
        assertEquals(listOf(2, 7, 1), listOf(TorRoute.SNOWFLAKE, TorRoute.OBFS4, TorRoute.MEEK).map { t.getValue(it).size })
        for ((route, lines) in t) {
            val parsed = TorBridges.lines(route, emptyList())
            // Each parses, and Robot writes it exactly as Tor Browser has it.
            assertEquals(route.name, lines, parsed.map { it.text })
            assertTrue(route.name, parsed.all { it.transport == mapOf(TorRoute.SNOWFLAKE to "snowflake", TorRoute.OBFS4 to "obfs4", TorRoute.MEEK to "meek_lite")[route] })
        }
        assertEquals(PINNED, sha256(listOf(TorRoute.SNOWFLAKE, TorRoute.OBFS4, TorRoute.MEEK).flatMap { t.getValue(it) }.joinToString("\n")))
    }

    @Test fun bridgeLinesAsPeopleGiveThem() {
        val obfs4 = "obfs4 203.0.113.7:9443 0123456789ABCDEF0123456789ABCDEF01234567 cert=AbCd+/efGH0123 iat-mode=0"
        assertEquals(obfs4, BridgeLine.parse(obfs4)!!.text)
        assertEquals("obfs4", BridgeLine.parse(obfs4)!!.transport)
        // "Bridge " in front (a torrc line), extra spaces and tabs, a lower-case fingerprint: written the one way Tor reads.
        assertEquals(obfs4, BridgeLine.parse("Bridge  obfs4\t203.0.113.7:9443 0123456789abcdef0123456789abcdef01234567  cert=AbCd+/efGH0123 iat-mode=0 ")!!.text)
        assertEquals(obfs4, BridgeLine.parse("bridge $obfs4")!!.text)
        // WebTunnel (given out by bridges.torproject.org, never built in) over IPv6, a plain bridge, meek without a fingerprint.
        val wt = "webtunnel [2001:db8:3c5d:9e1a::1]:443 0123456789ABCDEF0123456789ABCDEF01234567 url=https://example.org/aB3dEf ver=0.0.1"
        assertEquals(wt, BridgeLine.parse(wt)!!.text)
        assertEquals("webtunnel", BridgeLine.parse(wt)!!.transport)
        val plain = BridgeLine.parse("192.0.2.9:443 0123456789ABCDEF0123456789ABCDEF01234567")!!
        assertNull(plain.transport)
        assertNotNull(BridgeLine.parse("meek_lite 192.0.2.20:80 url=https://example.org front=www.example.com"))
        // Never the bridge in a log line.
        assertFalse(BridgeLine.parse(obfs4).toString().contains("203.0.113.7"))
    }

    /** A pasted line can't carry anything Tor would read as another option, a comment or a second line. */
    @Test fun nothingButABridgeLine() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        for (bad in listOf(
            "", " ", "Bridge", "Bridge ",
            "obfs4 203.0.113.7:9443 $fp cert=x iat-mode=0\nControlPort 9051",
            "obfs4 203.0.113.7:9443 $fp cert=x iat-mode=0\rControlPort 9051",
            "obfs4 203.0.113.7:9443 $fp cert=x iat-mode=0 # ControlPort 9051",
            "obfs4 203.0.113.7:9443 $fp cert=\"x\" iat-mode=0",
            "obfs4 203.0.113.7:9443 $fp cert=x\\ iat-mode=0",
            "obfs4 203.0.113.7:9443 $fp cert=x iat-mode=0\u0000",
            "obfs4 203.0.113.7:9443 $fp cert=x iat-mode=0 café=1",
            // Unknown or retired transports, and a transport with no address.
            "obfs3 203.0.113.7:9443 $fp", "scramblesuit 203.0.113.7:9443 $fp password=x", "conjure 203.0.113.7:9443 $fp", "obfs4",
            // Addresses Tor wouldn't read as one.
            "obfs4 203.0.113.7 $fp cert=x iat-mode=0", "obfs4 203.0.113.256:443 $fp cert=x iat-mode=0", "obfs4 203.0.113.07:443 $fp cert=x iat-mode=0",
            "obfs4 203.0.113.7:0 $fp cert=x iat-mode=0", "obfs4 203.0.113.7:65536 $fp cert=x iat-mode=0", "obfs4 203.0.113.7:0443 $fp cert=x iat-mode=0",
            "obfs4 example.org:443 $fp cert=x iat-mode=0", "obfs4 [::1:443 $fp cert=x iat-mode=0", "obfs4 [zz::1]:443 $fp cert=x iat-mode=0",
            // What a transport can't do without, or a key twice, or an argument that isn't key=value.
            "obfs4 203.0.113.7:9443 cert=x iat-mode=0", "obfs4 203.0.113.7:9443 $fp iat-mode=0", "obfs4 203.0.113.7:9443 $fp cert=x",
            "webtunnel [2001:db8::1]:443 $fp ver=0.0.1", "meek_lite 192.0.2.20:80 front=x", "snowflake 192.0.2.3:80 $fp fronts=x",
            "obfs4 203.0.113.7:9443 $fp cert=x cert=y iat-mode=0", "obfs4 203.0.113.7:9443 $fp cert=x iat-mode=0 extra",
            "obfs4 203.0.113.7:9443 $fp cert= iat-mode=0", "obfs4 203.0.113.7:9443 $fp =x iat-mode=0 cert=y",
            // A plain bridge takes no arguments and needs its fingerprint.
            "192.0.2.9:443 $fp cert=x", "192.0.2.9:443",
            "obfs4 203.0.113.7:9443 $fp cert=${"x".repeat(BridgeLine.MAX_LENGTH)} iat-mode=0",
        )) assertNull(bad, BridgeLine.parse(bad))
        assertNull(BridgeLine.parse("obfs4 203.0.113.7:9443 $fp cert=x iat-mode=0 " + (1..16).joinToString(" ") { "k$it=v" }))
    }

    @Test fun yourOwnBridgeAsTyped() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        val a = "obfs4 203.0.113.7:9443 $fp cert=AbCd iat-mode=0"
        val b = "webtunnel [2001:db8::1]:443 $fp url=https://example.org/x ver=0.0.1"
        assertEquals(listOf(a, b), TorBridges.parseOwn("\n$a\r\n\n  $b  \n")!!.map { it.text })
        assertNull(TorBridges.parseOwn(""))
        assertNull(TorBridges.parseOwn("\n \n"))
        assertNull(TorBridges.parseOwn("$a\nnot a bridge"))
        assertNull(TorBridges.parseOwn("$a\n$a"))
        val nine = (1..9).joinToString("\n") { "192.0.2.$it:443 $fp" }
        assertNull(TorBridges.parseOwn(nine))
        assertEquals(8, TorBridges.parseOwn(nine.lines().take(8).joinToString("\n"))!!.size)
    }

    /** link_settings.json's "bridges" and "ownBridges": round trip, and anything else fails closed (Robot to Robot off). */
    @Test fun theSettingsFile() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        val own = TorBridges.parseOwn("obfs4 203.0.113.7:9443 $fp cert=AbCd iat-mode=0\n192.0.2.9:443 $fp")!!
        for (s in listOf(LinkSettings(on = true, bridges = BridgeChoice.AUTO), LinkSettings(on = true, bridges = BridgeChoice.SNOWFLAKE),
            LinkSettings(on = true, bridges = BridgeChoice.OBFS4), LinkSettings(on = true, bridges = BridgeChoice.OWN, ownBridges = own),
            // Your lines are kept while another choice is on, so going back to them needs no pasting again.
            LinkSettings(on = true, bridges = BridgeChoice.OFF, ownBridges = own))) {
            val text = LinkSettingsCodec.encode(s)
            assertEquals(s.toString(), LinkSettingsFile.Ok(s), LinkSettingsCodec.decode(text))
        }
        val o = JSONObject(LinkSettingsCodec.encode(LinkSettings(on = true, bridges = BridgeChoice.OWN, ownBridges = own)))
        assertEquals("OWN", o.getString("bridges"))
        assertEquals(2, o.getJSONArray("ownBridges").length())
        // An older Robot keeps both as keys it doesn't know (LinkSettingsCodec's rule); the bridge never reaches a log.
        assertFalse(LinkSettings(on = true, bridges = BridgeChoice.OWN, ownBridges = own).toString().contains("203.0.113.7"))
        val ok = """{"v":1,"on":true}"""
        for (bad in listOf(
            """{"v":1,"on":true,"bridges":"WEBTUNNEL"}""", """{"v":1,"on":true,"bridges":1}""", """{"v":1,"on":true,"bridges":null}""",
            """{"v":1,"on":true,"bridges":"OWN"}""", """{"v":1,"on":true,"bridges":"OWN","ownBridges":[]}""",
            """{"v":1,"on":true,"ownBridges":"192.0.2.9:443 $fp"}""", """{"v":1,"on":true,"ownBridges":[1]}""",
            """{"v":1,"on":true,"ownBridges":["192.0.2.9:443"]}""",
            // Not as Robot writes it (a lower-case fingerprint, "Bridge " in front): changed by hand, so damaged.
            """{"v":1,"on":true,"ownBridges":["192.0.2.9:443 ${fp.lowercase()}"]}""", """{"v":1,"on":true,"ownBridges":["Bridge 192.0.2.9:443 $fp"]}""",
            """{"v":1,"on":true,"ownBridges":["192.0.2.9:443 $fp","192.0.2.9:443 $fp"]}""",
            """{"v":1,"on":true,"ownBridges":[${(1..9).joinToString(",") { "\"192.0.2.$it:443 $fp\"" }}]}""",
            """{"v":1,"on":true,"ownBridges":["192.0.2.9:443 $fp\nControlPort 9051"]}""",
        )) {
            assertTrue(LinkSettingsCodec.decode(ok).settings.on)
            assertEquals(bad, LinkSettingsFile.Damaged, LinkSettingsCodec.decode(bad))
        }
        assertTrue(runCatching { LinkSettings(bridges = BridgeChoice.OWN) }.isFailure)
        assertTrue(runCatching { LinkSettings(ownBridges = own + own) }.isFailure)
    }

    private companion object {
        /** SHA-256 of the built-in lines, Snowflake, obfs4 then meek, one a line, as taken on 28 Oct 2026. */
        const val PINNED = "c23e8c64b382c551f3860c9c146a3b92af8a8cc9785989d6de997286419b903e"
    }
}
