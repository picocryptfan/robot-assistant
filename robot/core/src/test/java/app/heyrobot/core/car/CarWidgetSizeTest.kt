// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarWidgetSizeTest {
    @Test fun theSlotsPixelsInDpAndNoneBeforeLayout() {
        assertEquals(400f to 150f, CarWidgetSize.dp(1000, 375, 2.5f))
        assertNull(CarWidgetSize.dp(0, 375, 2.5f))
        assertNull(CarWidgetSize.dp(1000, 0, 2.5f))
    }
}
