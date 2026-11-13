// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortCacheTest {
    private var now = 1_000L
    private var loads = 0
    private val cache = ShortCache<Int>(3_000L) { now }
    private fun get() = cache.get { ++loads }

    @Test fun keptUntilItExpires() {
        assertEquals(1, get())
        now += 2_999; assertEquals(1, get())
        now += 1; assertEquals(2, get())
        assertEquals(2, loads)
    }

    @Test fun clearedWorksItOutAgain() {
        assertEquals(1, get())
        cache.clear()
        assertEquals(2, get())
        now += 1_000; assertEquals(2, get())
    }

    @Test fun aClockGoingBackWorksItOutAgain() {
        assertEquals(1, get())
        now -= 1; assertEquals(2, get())
    }
}
