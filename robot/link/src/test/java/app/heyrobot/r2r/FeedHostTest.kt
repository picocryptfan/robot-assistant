// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** review-home-net-bind L3: a feed's non-ASCII host goes to Tor as its punycode, as WebUrl and ConnUrl write it. */
class FeedHostTest {
    @Test fun aNonAsciiHostIsDialledAsPunycode() {
        assertEquals("xn--bcher-kva.example", feedHost("b\u00fccher.example"))
        assertEquals("news.example.org", feedHost("News.Example.ORG"))
        assertEquals("news.example.org", feedHost("news.example.org"))
        assertNull(feedHost("192.168.1.20"))
        assertNull(feedHost("bad host.example"))
        assertNull(feedHost("a".repeat(300) + ".example"))
    }
}
