// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.output.HandsFreeCommand
import app.heyrobot.core.output.HandsFreeRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHalvesPhraseTest {
    private fun heard(s: String) = (HandsFreeRouter.route(s, carMode = true) as? HandsFreeCommand.Bottom)?.phrase

    @Test fun swapTheBottomInBothLanguages() {
        for (s in listOf("swap the bottom", "Swap the bottom screen.", "switch the bottom", "change l'écran du bas", "Change le bas"))
            assertEquals(s, CarHalvesPhrase.SwapBottom, heard(s))
    }

    @Test fun aWidgetOnTheBottomNamesItsApp() {
        val en = heard("put Spotify's widget on the bottom") as CarHalvesPhrase.WidgetOnBottom
        assertTrue(CarHalvesPhrase.names(en.app, "Spotify"))
        val fr = heard("mets le widget de Spotify en bas") as CarHalvesPhrase.WidgetOnBottom
        assertTrue(CarHalvesPhrase.names(fr.app, "Spotify"))
        assertTrue(CarHalvesPhrase.names("organic maps", "Organic Maps"))
        assertFalse(CarHalvesPhrase.names("spotify", "Pocket Casts"))
        assertFalse(CarHalvesPhrase.names("spotify", ""))
    }

    @Test fun onlyInCarModeAndNotOtherWords() {
        assertNull(HandsFreeRouter.route("swap the bottom", carMode = false))
        assertNull(heard("navigate home"))
        assertNull(heard("the bottom of the sea"))
    }
}
