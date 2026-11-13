// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WikiHopsTest {
    private val start = WikiSet.entries.first().url
    private fun chain(vararg hops: Pair<String, Pair<Int, String?>>): (String) -> Pair<Int, String?> {
        val m = hops.toMap()
        return { u -> m[u] ?: (-1 to null) }
    }

    @Test fun aMirrorOverHttpsIsWhatTheDownloadManagerGets() {
        val mirror = "https://mirror.example.org/kiwix/zim/a.zim"
        assertEquals(mirror, WikiHops.resolve(start, chain(start to (302 to "https://lb.download.kiwix.org/a.zim"),
            "https://lb.download.kiwix.org/a.zim" to (302 to mirror), mirror to (200 to null))))
        // A path on the same host stays https.
        assertEquals("https://mirror.example.org/b.zim", WikiHops.resolve(start, chain(start to (302 to "https://mirror.example.org/x"),
            "https://mirror.example.org/x" to (301 to "/b.zim"), "https://mirror.example.org/b.zim" to (200 to null))))
    }

    @Test fun aMirrorHopToPlainHttpIsRefusedAtAnyHost() {
        val mirror = "https://mirror.example.org/kiwix/a.zim"
        assertNull(WikiHops.resolve(start, chain(start to (302 to mirror), mirror to (302 to "http://other-mirror.example.net/a.zim"),
            "http://other-mirror.example.net/a.zim" to (200 to null))))
        assertNull(WikiHops.resolve(start, chain(start to (302 to "HTTP://mirror.example.org/a.zim"))))
        assertNull(WikiHops.resolve(start, chain(start to (302 to "ftp://mirror.example.org/a.zim"))))
        assertNull(WikiHops.resolve(start, chain(start to (302 to null))))
        assertNull(WikiHops.resolve("http://download.kiwix.org/a.zim") { 200 to null })
    }

    @Test fun tooManyHopsOrAFailureStopIt() {
        assertNull(WikiHops.resolve(start) { u -> 302 to "$u/x" })
        assertNull(WikiHops.resolve(start) { -1 to null })
        assertNull(WikiHops.resolve(start) { 404 to null })
        assertEquals(start, WikiHops.resolve(start) { 200 to null })
    }
}
