// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenFrameFeedTest {
    private var clock = 1_000L
    private var allow = true
    private val cuts = ArrayList<String>()
    private val feed = SpokenFrameFeed(nanos = { clock }, sleep = { ns -> if (ns > 0) clock += ns }, allowed = { allow || SpokenProtocol.isNo(it) }, onCut = { cuts += it })

    private fun frame(n: Int = 4): ShortArray = ShortArray(n) { 99 }.also { assertEquals(n, feed.read(it, n)) }
    private fun clip(vararg s: Int) = ShortArray(s.size) { s[it].toShort() }

    @Test fun nothingToPlayIsSilenceNeverTheRoom() = assertArrayEquals(ShortArray(4), frame())

    @Test fun clipsPlayInTurnWithSilenceAfterEach() {
        feed.enqueue("say-a", clip(1, 2, 3, 4, 5))
        feed.enqueue("say-b", clip(6, 7))
        assertArrayEquals(clip(1, 2, 3, 4), frame())
        assertArrayEquals(clip(5, 0, 0, 0), frame())
        assertArrayEquals(clip(6, 7, 0, 0), frame())
        assertArrayEquals(ShortArray(4), frame())
        assertEquals(0, feed.pending())
    }

    @Test fun framesComeAtRealTimeAndNeverInABurst() {
        feed.enqueue("say-a", ShortArray(16_000))
        val start = clock
        repeat(100) { frame(512) }
        assertEquals(100 * 512 * 1_000_000_000L / 16_000, clock - start)
        // The listener stalled for a second: the next frames keep their pace, they don't rush to catch up.
        clock += 1_000_000_000L
        val after = clock
        repeat(10) { frame(512) }
        assertTrue("caught up in a burst: ${clock - after}", clock - after >= 9 * 512 * 1_000_000_000L / 16_000)
    }

    @Test fun aCardThatComesUpMidClipCutsIt() {
        feed.enqueue("say-yes", clip(1, 2, 3, 4, 5, 6, 7, 8))
        assertArrayEquals(clip(1, 2, 3, 4), frame())
        allow = false
        assertArrayEquals(ShortArray(4), frame())
        assertEquals(listOf("say-yes"), cuts)
        allow = true
        assertArrayEquals("the rest of it doesn't come back", ShortArray(4), frame())
    }

    @Test fun aNoStillPlaysOverACard() {
        allow = false
        feed.enqueue("say-yes", clip(1, 1, 1, 1))
        feed.enqueue("no-thanks", clip(2, 2, 2, 2))
        assertArrayEquals(clip(2, 2, 2, 2), frame())
        assertEquals(listOf("say-yes"), cuts)
    }

    @Test fun clearDropsWhatsWaiting() {
        feed.enqueue("say-a", clip(1, 2, 3, 4, 5))
        feed.clear()
        assertArrayEquals(ShortArray(4), frame())
    }
}
