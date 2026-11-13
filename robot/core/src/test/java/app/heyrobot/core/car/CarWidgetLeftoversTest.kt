// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarWidgetLeftoversTest {
    @Test fun allButTheSavedAndTheWaitingIdGo() {
        assertEquals(listOf(3, 9), CarWidget.leftovers(intArrayOf(3, 5, 7, 9), setOf(5, 7)))
        assertEquals(listOf(4), CarWidget.leftovers(intArrayOf(4), emptySet()))
        assertEquals(emptyList<Int>(), CarWidget.leftovers(intArrayOf(), setOf(1)))
    }
}
