// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L16, finding 9: your phone only POSTs a peer's ciphertext to a host on the internet, never to your own network. */
class PushEndpointTest {
    private val ok = { e: String -> PushRoute.peerEndpointOk(e) }

    /** Your own distributor's address isn't judged: it's yours to choose, even on your own network. */
    @Test fun yourOwnRouteIsYours() = assertTrue(PushRoute.endpointOk("https://192.168.1.5/up"))

    @Test fun aRouteInACodeOrHelloIsJudged() {
        val k = ByteArray(65) { if (it == 0) 4 else 7 }
        val a = ByteArray(16) { 2 }
        fun read(url: String): Boolean {
            val w = BinWriter()
            PushRoute(url, k, a).write(w)
            return try { PushRoute.read(BinReader(w.toByteArray())); true } catch (_: LinkBad) { false } // l10n: not text
        }
        assertTrue(read("https://push.example.org/up"))
        assertFalse(read("https://192.168.1.5/up"))
    }

    @Test fun realPushServersPass() {
        for (e in listOf("https://push.example.org/up/abc", "https://ntfy.sh/UP123?up=1", "https://push.example.org:8443/x", "https://a.b-c.example", "https://8.8.8.8/x",
            "https://[2606:4700:4700::1111]/x", "https://172.32.0.1/x", "https://192.169.0.1/", "https://11.0.0.1", "https://example.org.")) assertTrue(e, ok(e))
    }

    @Test fun yourOwnNetworkIsRefused() {
        for (e in listOf("https://localhost/x", "https://LOCALHOST:8443/x", "https://localhost./x", "https://foo.localhost/x",
            "https://127.0.0.1/x", "https://127.8.8.8:443/", "https://0.0.0.0/", "https://10.1.2.3/x", "https://172.16.0.1/x", "https://172.31.255.255/x",
            "https://192.168.1.1/x", "https://169.254.169.254/latest", "https://100.64.0.1/", "https://224.0.0.1/", "https://255.255.255.255/",
            "https://[::1]/x", "https://[::]/x", "https://[fe80::1]/x", "https://[FC00::1]:8443/x", "https://[fd12:3456::1]/", "https://[ff02::1]/", "https://[::ffff:127.0.0.1]/", "https://[::ffff:7f00:1]/",
            )) assertFalse(e, ok(e))
    }

    @Test fun otherSpellingsOfAnAddressAreRefused() {
        for (e in listOf("https://2130706433/", "https://127.1/", "https://0x7f.0.0.1/", "https://0177.0.0.1/", "https://1.2.3.4.5/", "https://256.1.1.1/", "https://12/",
            "https://user@example.org/", "https://example.org@127.0.0.1/", "https://127.0.0.1\\@example.org/", "https://%31%32%37.0.0.1/", "https://[::1/", "https://[zz::1]/", "https://[1:2:3]/",
            "https://example.org:0/", "https://example.org:99999/", "https://example.org:ab/", "https://exa mple.org/", "http://example.org/", "https:///x", "https://")) assertFalse(e, ok(e))
    }
}
