// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Ask about this screen": shown on every corner-swipe and wake-word panel, and a tap without the screen says why. */
class ScreenAccessTest {
    private fun chip(source: ShowSource, text: Boolean? = null, shot: Boolean? = null, waited: Long = 0, textOn: Boolean? = true, locked: Boolean = false) =
        ScreenAccess.chip(source, locked, text, shot, waited, textOn)

    @Test fun robotsOwnAppAndTheLockScreenNeverOfferIt() {
        assertEquals(ScreenChip.HIDDEN, chip(ShowSource.APP))
        assertEquals(ScreenChip.HIDDEN, chip(ShowSource.APP, text = true))
        assertEquals(ScreenChip.HIDDEN, chip(ShowSource.CAPTURE))
        assertEquals(ScreenChip.HIDDEN, chip(ShowSource.TEST))
        for (s in ShowSource.entries) assertEquals(s.name, ScreenChip.HIDDEN, chip(s, text = true, locked = true))
    }

    @Test fun theScreenIsReadyOnceTextOrAPictureCame() {
        assertEquals(ScreenChip.READY, chip(ShowSource.SWIPE, text = true))
        assertEquals(ScreenChip.READY, chip(ShowSource.SWIPE, text = false, shot = true))
        assertEquals(ScreenChip.READY, chip(ShowSource.SWIPE, shot = true, waited = 10_000))
        // A later Android that shares the screen with a wake-word panel too.
        assertEquals(ScreenChip.READY, chip(ShowSource.WAKE, text = true))
    }

    @Test fun aCornerSwipeWithoutTheScreenSaysWhy() {
        // Android called back with nothing for both.
        assertEquals(ScreenChip.NOT_SHARED, chip(ShowSource.SWIPE, text = false, shot = false))
        // "Use text from screen" is off: no need to wait.
        assertEquals(ScreenChip.NOT_SHARED, chip(ShowSource.SWIPE, textOn = false))
        // Nothing yet: wait a moment, then explain.
        assertEquals(ScreenChip.WAITING, chip(ShowSource.SWIPE, waited = 1_999))
        assertEquals(ScreenChip.WAITING, chip(ShowSource.SWIPE, text = false, waited = 500))
        assertEquals(ScreenChip.WAITING, chip(ShowSource.SWIPE, textOn = null, waited = 500))
        assertEquals(ScreenChip.NOT_SHARED, chip(ShowSource.SWIPE, waited = ScreenAccess.WAIT_MS))
        assertEquals(ScreenChip.NOT_SHARED, chip(ShowSource.SWIPE, text = false, waited = 5_000))
    }

    @Test fun aWakeWordPanelPointsToTheCornerSwipe() {
        assertEquals(ScreenChip.SWIPE_ONLY, chip(ShowSource.WAKE))
        assertEquals(ScreenChip.SWIPE_ONLY, chip(ShowSource.WAKE, waited = 10_000))
        assertEquals(ScreenChip.SWIPE_ONLY, chip(ShowSource.WAKE, textOn = false))
        assertEquals(ScreenChip.SWIPE_ONLY, chip(ShowSource.KEYGUARD))
        val h = ScreenAccess.swipeHint(textOn = true)
        assertTrue(h.text, h.text.contains("swiping up from a bottom corner"))
        assertFalse(h.openSettings)
        // "Use text from screen" off: the swipe alone won't do, and the notice says so and opens Settings.
        val off = ScreenAccess.swipeHint(textOn = false)
        assertTrue(off.text, off.text.contains("“Use text from screen” is off"))
        assertTrue(off.openSettings)
    }

    @Test fun theHintNamesOnlyTheSettingsThatAreOff() {
        val where = "Settings › Apps › Default apps › Digital assistant app"
        fun h(t: Boolean?, s: Boolean?) = ScreenAccess.notSharedHint(t, s)
        for ((t, s) in listOf(false to false, false to true, true to false, null to null, true to true, true to null)) {
            assertTrue(h(t, s).text, h(t, s).text.startsWith("Android didn't share this screen with Robot."))
        }
        // Both off: both named, with where they are.
        val both = h(false, false)
        assertTrue(both.text, both.text.contains("Turn on “Use text from screen” and “Use screenshot” in $where"))
        assertTrue(both.openSettings)
        // Only text off: only it is named (and why the screenshot didn't come either).
        val text = h(false, true)
        assertTrue(text.text, text.text.contains("Turn on “Use text from screen” in $where"))
        assertFalse(text.text, text.text.contains("Turn on “Use screenshot”"))
        assertTrue(text.text, text.text.contains("no screenshot either"))
        // Text on, screenshot off: the app keeps its screen private; the screenshot setting is offered for a picture.
        val shot = h(true, false)
        assertTrue(shot.text, shot.text.contains("private") && shot.text.contains("“Use screenshot” is off"))
        assertFalse(shot.text, shot.text.contains("Turn on “Use text from screen”"))
        assertTrue(shot.openSettings)
        // Both on: nothing to turn on, nothing to open.
        val on = h(true, true)
        assertTrue(on.text, on.text.contains("are on") && on.text.contains("private"))
        assertFalse(on.text, on.text.contains("Turn on"))
        assertFalse(on.openSettings)
        assertFalse(h(true, null).openSettings)
        // Android wouldn't say: check both.
        val unknown = h(null, null)
        assertTrue(unknown.text, unknown.text.contains("Check that “Use text from screen” and “Use screenshot” are on in $where"))
        assertTrue(unknown.openSettings)
    }

    @Test fun setupWarnsOnlyWhenTextIsOff() {
        val ok = ScreenAccess.setup(true, true)
        assertEquals(ScreenAccess.Level.OK, ok.level)
        assertTrue(ok.text.startsWith("Use text from screen ✓ · Use screenshot ✓"))
        // The text alone works: information, not a warning.
        val noShot = ScreenAccess.setup(true, false)
        assertEquals(ScreenAccess.Level.INFO, noShot.level)
        assertTrue(noShot.text, noShot.text.startsWith("Use text from screen ✓ · Use screenshot ✗"))
        for (shot in listOf(true, false, null)) {
            val noText = ScreenAccess.setup(false, shot)
            assertEquals(ScreenAccess.Level.WARN, noText.level)
            assertTrue(noText.text, noText.text.contains("no screenshot either"))
        }
        assertEquals(ScreenAccess.Level.INFO, ScreenAccess.setup(null, true).level)
        assertEquals(ScreenAccess.Level.INFO, ScreenAccess.setup(true, null).level)
    }

    @Test fun theChipsIconSaysWhatATapDoes() {
        assertNull(ScreenAccess.iconDescription(ScreenChip.HIDDEN))
        for (c in ScreenChip.entries - ScreenChip.HIDDEN) assertFalse(c.name, ScreenAccess.iconDescription(c).isNullOrBlank())
        assertEquals(ScreenChip.entries.size - 1, (ScreenChip.entries - ScreenChip.HIDDEN).map { ScreenAccess.iconDescription(it) }.toSet().size)
    }

    @Test fun showFlagsAndArrivalsAreCodes() {
        assertEquals("text, screenshot, assist gesture", ScreenAccess.flagWords(7))
        assertEquals("none", ScreenAccess.flagWords(0))
        assertEquals(ScreenData.NOT_SENT, ScreenData.of(null))
        assertEquals(ScreenData.EMPTY, ScreenData.of(false))
        assertEquals(ScreenData.SHARED, ScreenData.of(true))
    }
}
