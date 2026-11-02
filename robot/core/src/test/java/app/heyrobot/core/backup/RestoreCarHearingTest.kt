// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.ListenPause
import app.heyrobot.core.car.CarHearMe
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import org.junit.Assert.assertEquals
import org.junit.Test

/** merge-all-10: listen-wait's car rows and car-drive's podcast app, each as its side meant, through a restore. */
class RestoreCarHearingTest {
    private val backup = CarFile(on = true, settings = CarSettings(hearMe = CarHearMe.NORMAL, waitPause = ListenPause.VERY_LONG))

    private fun restored(mine: Boolean) =
        RestoreMerge.handsfree(DeviceMemory(), HeadsetFile(), CarFile(), Places(), mapOf(HandsFreeStore.CAR to backup.encode()), RestoreMode.REPLACE, mine).car.settings

    @Test fun aBackupBringsBackHowTheCarHearsYou() {
        for (mine in listOf(true, false)) {
            assertEquals(CarHearMe.NORMAL, restored(mine).hearMe)
            assertEquals(ListenPause.VERY_LONG, restored(mine).waitPause)
        }
    }

    @Test fun aBackupLeavesThisPhonesPodcastApp() {
        val here = MusicApp("com.example.here", "a".repeat(64), "Here")
        val there = MusicApp("com.example.there", "b".repeat(64), "There")
        val kept = RestoreSafety.everyday(EverydaySettings(podcastApp = here), EverydaySettings(podcastApp = there), mine = true).value
        assertEquals(here, kept.podcastApp)
    }
}
