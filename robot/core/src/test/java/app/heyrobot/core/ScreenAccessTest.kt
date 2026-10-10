package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(ScreenAccess.SWIPE_HINT.contains("swiping up from a bottom corner"))
    }

    @Test fun theHintNamesBothSettingsAndWhereTheyAre() {
        for ((t, s) in listOf(false to false, false to true, true to false, null to null)) {
            val h = ScreenAccess.notSharedHint(t, s)
            assertTrue(h, h.startsWith("Android didn't share this screen"))
            assertTrue(h, h.contains("“Use text from screen”") && h.contains("“Use screenshot”"))
            assertTrue(h, h.contains("Settings › Apps › Default apps › Digital assistant app"))
            assertTrue(h, h.contains("Turn on"))
        }
        // Both on: the app keeps its screen to itself.
        val on = ScreenAccess.notSharedHint(true, true)
        assertTrue(on, on.contains("are on") && on.contains("private"))
        assertFalse(on, on.contains("Turn on"))
    }

    @Test fun showFlagsAndArrivalsAreCodes() {
        assertEquals("text, screenshot, assist gesture", ScreenAccess.flagWords(7))
        assertEquals("none", ScreenAccess.flagWords(0))
        assertEquals(ScreenData.NOT_SENT, ScreenData.of(null))
        assertEquals(ScreenData.EMPTY, ScreenData.of(false))
        assertEquals(ScreenData.SHARED, ScreenData.of(true))
    }
}
