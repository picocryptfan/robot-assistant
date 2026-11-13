// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.HomeChoice
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.QuickItem
import app.heyrobot.core.TodayOffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review L4: RestoreSafety.home itself keeps this phone's home page, Quick access order and offer unless the backup is your
 * own, so someone else's backup can't change them even if the category gate (HOME is ONLY_YOURS) ever let it through.
 */
class HomeRestoreOthersTest {
    private val order = listOf(QuickItem.ROUTINES) + QuickItem.DEFAULT.filter { it != QuickItem.ROUTINES }

    @Test fun anotherPersonsBackupKeepsThisPhonesHome() {
        val file = HomeSettings(home = HomeChoice.QUICK, quick = order)
        val other = RestoreSafety.home(HomeSettings(), file, mine = false).value
        assertEquals(HomeChoice.START, other.home)
        assertEquals(QuickItem.DEFAULT, other.quick)
        assertEquals(TodayOffer.OPEN, other.offer)
        val today = RestoreSafety.home(HomeSettings(), HomeSettings(home = HomeChoice.TODAY, offer = TodayOffer.TAKEN), mine = false).value
        assertEquals(HomeChoice.START, today.home)
        assertEquals(TodayOffer.OPEN, today.offer)
    }

    @Test fun yourOwnBackupBringsThemBack() {
        val own = RestoreSafety.home(HomeSettings(), HomeSettings(home = HomeChoice.QUICK, quick = order), mine = true).value
        assertEquals(HomeChoice.QUICK, own.home)
        assertEquals(order, own.quick)
    }
}
