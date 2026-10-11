package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPadTest {
    @Test fun noKeyboardClearsTheNavigationBar() = assertEquals(105, KeyboardPad.bottom(navPx = 105, imePx = 0, slidPx = 0))

    @Test fun theWindowStaysAndThePanelPadsForTheKeyboard() = assertEquals(747, KeyboardPad.bottom(navPx = 105, imePx = 747, slidPx = 0))

    @Test fun aSlideAndThePaddingNeverAddUp() {
        // The 0.4.0 screenshot: the window slid up 697 px and the panel padded 747 px more, so the box sat 697 px too high.
        assertEquals(50, KeyboardPad.bottom(navPx = 0, imePx = 747, slidPx = 697))
        assertEquals(105, KeyboardPad.bottom(navPx = 105, imePx = 747, slidPx = 697))
        // Slid all the way: nothing more than the bar.
        assertEquals(0, KeyboardPad.bottom(navPx = 0, imePx = 747, slidPx = 900))
    }

    @Test fun aWindowLowerThanTheScreenTopIsNotASlide() = assertEquals(747, KeyboardPad.bottom(navPx = 0, imePx = 747, slidPx = -40))
}
