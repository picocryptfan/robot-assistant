// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A recreation while Android's question or the widget's setup is up keeps the pick, so its answer saves or deletes the id. */
class CarPendingPickTest {
    private val scope = SaverScope { true }

    private fun roundTrip(p: Pair<WidgetChoice, Int>?): Pair<WidgetChoice, Int>? {
        val saved = with(PendingPick) { scope.save(p) } ?: return null
        return PendingPick.restore(saved)
    }

    @Test fun thePickSurvivesARecreation() {
        val p = WidgetChoice("org.example.music", "org.example.music.Widget", "Music", "Now playing") to 42
        assertEquals(p, roundTrip(p))
    }

    @Test fun noPickStaysNone() {
        assertNull(roundTrip(null))
    }
}
