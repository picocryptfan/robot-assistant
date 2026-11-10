// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.net.HomeNets
import app.heyrobot.core.net.HomeRoute
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetEntry
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket

/** review-home-net-bind M1: a feed at a home address refused off your network sent nothing, so What went online has no row. */
class FeedOffNetworkTest {
    @After fun tearDown() {
        HomeNets.use(null)
        NetRecord.sink = null
    }

    @Test fun aRefusedHomeFeedIsNotRecorded() {
        val rows = java.util.Collections.synchronizedList(mutableListOf<NetEntry>())
        NetRecord.sink = NetSink { rows += it }
        HomeNets.use(object : HomeNets.Source {
            override fun nets() = listOf(HomeRoute.Net(1, false, false, false, true, listOf(HomeRoute.Prefix(LanRule.literal("0.0.0.0")!!, 0))))
            override fun bind(s: Socket, id: Long) = fail("bound")
        })
        try {
            FeedClient.homeGet("http://192.168.1.20:8080/feed.xml", listOf(LanRule.literal("192.168.1.20")!!), NetCause.ASKED, null, null,
                System.currentTimeMillis() + 5_000, false)
            fail("sent")
        } catch (f: FeedClient.Failure) {
            assertEquals(NewsFail.OFF_NETWORK, f.why)
        }
        assertEquals(emptyList<NetEntry>(), rows.toList())
    }
}
