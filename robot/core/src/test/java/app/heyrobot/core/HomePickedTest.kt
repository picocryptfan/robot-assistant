// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Review L3: picking any home in Settings › Display answers Today's offer, so it can't show up later. */
class HomePickedTest {
    @Test fun anyChoiceAnswersTheOffer() {
        for (c in HomeChoice.entries) {
            val h = HomeSettings().picked(c)
            assertEquals(c, h.home)
            // Back on Getting started later (Quick access, then Getting started): no offer.
            assertFalse(c.name, h.picked(HomeChoice.START).offerShows(ready = true, guideUnderWay = false))
        }
        assertEquals(HomeSettings().turnedOn(), HomeSettings().picked(HomeChoice.TODAY))
        assertEquals(TodayOffer.TAKEN, HomeSettings(offer = TodayOffer.TAKEN).picked(HomeChoice.QUICK).offer)
    }
}
