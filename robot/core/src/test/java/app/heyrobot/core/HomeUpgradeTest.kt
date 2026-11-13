// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HS2, the plan's "Upgrading": nobody moves, no new offer, and backups cross between 1.3 and this build both ways. */
class HomeUpgradeTest {
    private fun file13(today: Boolean, offer: String) =
        """{"v":1,"today":$today,"offer":"$offer","order":["calendar","lists","note"],"lock_summary":false,"weather":false,"news":{}}"""

    /** release/1.3's fromJson and restored(), as far as the home page goes. */
    private fun today13(text: String) = JSONObject(text).let { o -> o.optInt("v", 0) == 1 && o.optBoolean("today", false) }
    private fun restored13(here: Boolean, text: String) = here || today13(text)

    @Test fun todayOnStaysTodayAndOffStaysGettingStarted() {
        assertEquals(HomeChoice.TODAY, HomeSettings.fromJson(file13(true, "taken")).home)
        for (offer in listOf("open", "declined", "taken")) assertEquals(HomeChoice.START, HomeSettings.fromJson(file13(false, offer)).home)
        assertEquals(QuickItem.DEFAULT, HomeSettings.fromJson(file13(true, "taken")).quick)
    }

    @Test fun noNewOfferAppears() {
        assertFalse(HomeSettings.fromJson(file13(false, "declined")).offerShows(ready = true, guideUnderWay = false))
        assertFalse(HomeSettings.fromJson(file13(true, "taken")).offerShows(ready = true, guideUnderWay = false))
        // An offer still open in 1.3 is the same offer, not a new one.
        assertTrue(HomeSettings.fromJson(file13(false, "open")).offerShows(ready = true, guideUnderWay = false))
    }

    @Test fun aRestoreAppliesTheBackupsChoiceOnlyOnGettingStarted() {
        val today = HomeSettings(home = HomeChoice.TODAY)
        val quick = HomeSettings(home = HomeChoice.QUICK)
        assertEquals(HomeChoice.TODAY, HomeSettings().restored(HomeSettings.fromJson(file13(true, "taken"))).home)
        assertEquals(HomeChoice.QUICK, HomeSettings().restored(quick).home)
        assertEquals(HomeChoice.QUICK, quick.restored(today).home)
        assertEquals(HomeChoice.TODAY, today.restored(quick).home)
        assertEquals(HomeChoice.TODAY, today.restored(HomeSettings()).home)
        assertEquals(HomeChoice.START, HomeSettings().restored(HomeSettings()).home)
    }

    @Test fun theQuickOrderComesBackButA13BackupKeepsThisPhones() {
        val mine = HomeSettings().with(QuickItem.SKILLS, TodayMove.FIRST)
        assertEquals(QuickItem.SKILLS, HomeSettings().restored(HomeSettings.fromJson(mine.toJson())).quick.first())
        assertEquals(mine.quick, mine.restored(HomeSettings.fromJson(file13(true, "taken"))).quick)
        assertEquals(mine, HomeSettings.fromJson(mine.toJson()))
    }

    @Test fun thisBuildsBackupRestoresOn13() {
        assertTrue(restored13(false, HomeSettings(home = HomeChoice.TODAY).toJson()))
        assertFalse(restored13(false, HomeSettings(home = HomeChoice.QUICK).toJson()))
        assertTrue(restored13(true, HomeSettings(home = HomeChoice.QUICK).toJson()))
        // 1.3 refuses a file of another version; this build keeps 1.
        assertEquals(HomeSettings.VERSION, HomeSettings.version(HomeSettings(home = HomeChoice.QUICK).toJson()))
        assertEquals(1, HomeSettings.VERSION)
    }
}
