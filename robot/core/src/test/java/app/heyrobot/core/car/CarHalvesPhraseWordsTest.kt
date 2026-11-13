// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHalvesPhraseWordsTest {
    private fun of(s: String) = CarHalvesPhrase.of(s.split(" "))

    @Test fun theWidgetMeansTheOneRobotHas() {
        val p = of("show the widget on the bottom") as CarHalvesPhrase.WidgetOnBottom
        assertEquals("", p.app)
        assertTrue(p.means("Spotify"))
        assertEquals(CarHalvesPhrase.WidgetOnBottom(""), of("put my widget on the bottom"))
        assertEquals(CarHalvesPhrase.WidgetOnBottom(""), of("mets le widget en bas"))
        val named = of("show the spotify widget on the bottom") as CarHalvesPhrase.WidgetOnBottom
        assertTrue(named.means("Spotify"))
        assertFalse(named.means("Pocket Casts"))
    }

    @Test fun frenchWithOrWithoutDe() {
        assertEquals(CarHalvesPhrase.WidgetOnBottom("spotify"), of("mets le widget spotify en bas"))
        assertEquals(CarHalvesPhrase.WidgetOnBottom("spotify"), of("mets le widget de spotify en bas"))
        assertEquals(CarHalvesPhrase.WidgetOnBottom("deezer"), of("affiche le widget deezer sur lecran du bas"))
    }

    @Test fun frenchDeBeforeAVowel() {
        val p = of("mets le widget dapple music en bas") as CarHalvesPhrase.WidgetOnBottom
        assertTrue(p.means("Apple Music"))
        assertFalse(p.means("Spotify"))
        assertTrue((of("mets le widget deezer en bas") as CarHalvesPhrase.WidgetOnBottom).means("Deezer"))
    }

    @Test fun permuteSwaps() {
        assertEquals(CarHalvesPhrase.SwapBottom, of("permute lecran du bas"))
        assertEquals(CarHalvesPhrase.SwapBottom, of("permute le bas"))
    }
}
