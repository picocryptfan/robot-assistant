// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A widget's play button works in place; a tap that opens a screen goes to the top screen, never over the car screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarWidgetTapsTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val flags = PendingIntent.FLAG_IMMUTABLE

    // Robolectric can't tell a button from a screen once the sender is read back (the phone can: row cm-slot-widget), so
    // here only the side that matters: a screen is never taken for a button.
    @Test fun aScreenIsNeverTakenForAButton() {
        assertTrue(CarWidgetTaps.opensScreen(PendingIntent.getActivity(ctx, 1, Intent("org.example.OPEN"), flags).intentSender))
    }

    // No mode in the options: Android lends services and foreground services (play on a stopped player), never a screen.
    @Test fun theOptionsSetNoBackgroundStartMode() {
        val b = CarWidgetTaps.noStarts()
        assertFalse(b.containsKey("android.pendingIntent.backgroundActivityAllowed"))
        assertFalse(b.containsKey("android.pendingIntent.backgroundActivityAllowedByPermission"))
    }

    @Test fun aScreenOpensOnTheTopScreen() {
        var opened = 0
        val play = PendingIntent.getBroadcast(ctx, 5, Intent("org.example.PLAY"), flags).intentSender
        val taps = CarWidgetTaps(ctx, { opened++ }) { it != play }
        taps.startIntentSender(PendingIntent.getActivity(ctx, 4, Intent("org.example.OPEN"), flags).intentSender, null, 0, 0, 0)
        taps.startActivity(Intent("org.example.OPEN"))
        taps.startActivities(arrayOf(Intent("org.example.OPEN")))
        assertEquals(3, opened)
        taps.startIntentSender(play, null, 0, 0, 0)
        assertEquals(3, opened)
    }
}
