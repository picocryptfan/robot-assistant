// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWidgetNowTest {
    @Test fun phoneLockClosesTheAppOnlyWhileOn() {
        assertTrue(CarWidgetNow.closes(lockOn = true, closed = setOf("org.example.mail"), pkg = "org.example.mail"))
        assertFalse(CarWidgetNow.closes(lockOn = false, closed = setOf("org.example.mail"), pkg = "org.example.mail"))
        assertFalse(CarWidgetNow.closes(lockOn = true, closed = setOf("org.example.other"), pkg = "org.example.mail"))
    }

    @Test fun anIdNoLongerBoundGivesWayToTheMediaPlayer() {
        assertFalse(CarWidgetNow.off(locked = false, closes = false, made = true))
        assertTrue(CarWidgetNow.off(locked = false, closes = false, made = false))
        assertTrue(CarWidgetNow.off(locked = true, closes = false, made = true))
        assertTrue(CarWidgetNow.off(locked = false, closes = true, made = true))
    }
}
