// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.core.HomeSettings
import app.heyrobot.sys.HomeSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Today H9 in the main process: the home-screen widget draws the day only with the phone unlocked and Today on; locked,
 * it says to unlock, or with "Day summary on the lock screen" on, the counts and times; the summary is a silent, public
 * notice that goes when the switch is off, and the redraw after it is an inexact alarm that never wakes the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayWidgetTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val m = AppWidgetManager.getInstance(ctx)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private fun line(id: Int) = shadowOf(m).getViewFor(id).findViewById<TextView>(R.id.today_widget_line).text.toString()
    private fun notice(): Notification? = shadowOf(nm).allNotifications.singleOrNull { it.channelId == TodayGlances.CHANNEL }
    private fun home(f: (HomeSettings) -> HomeSettings) { HomeSettingsStore.update(ctx, f) }
    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ').replace('’', '\'')

    @Test fun theWidgetAndTheSummaryFollowTheLock() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(m).setAlwaysRecreateViewsDuringUpdate(true)
        shadowOf(m).bindAppWidgetId(5, ComponentName(ctx, TodayWidgetProvider::class.java))
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val keyguard = shadowOf(ctx.getSystemService(KeyguardManager::class.java))

        // Today off: how to turn it on, and no notice.
        TodayGlances.redrawNow(ctx)
        assertEquals("Turn on Today in Settings › Display to see your day here.", line(5))
        assertNull(notice())

        // On and unlocked: the day line (no calendar permission here, so it says so).
        home { it.turnedOn() }
        TodayGlances.redrawNow(ctx)
        assertEquals("Robot can't read your calendar.", plain(line(5)))
        assertNull("the summary is off until you turn it on", notice())

        // Locked, the summary off: unlock to see it.
        keyguard.setKeyguardLocked(true)
        TodayGlances.redrawNow(ctx)
        assertEquals("Unlock to see your day.", line(5))
        assertNull(notice())

        // The summary on: counts and times on the widget and in a silent, public notice; an inexact alarm, not waking.
        home { it.copy(lockSummary = true) }
        TodayGlances.redrawNow(ctx)
        assertEquals("Robot can't read your calendar.", plain(line(5)))
        val n = notice()!!
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals("Robot can't read your calendar.", plain(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()))
        assertEquals("Robot can't read your calendar.", plain(n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()))
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(TodayGlances.CHANNEL).importance)
        assertNull(nm.getNotificationChannel(TodayGlances.CHANNEL).sound)
        val alarms = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertTrue(alarms.isNotEmpty())
        assertTrue(alarms.all { it.type == AlarmManager.RTC })

        // Off again: the notice goes.
        home { it.copy(lockSummary = false) }
        TodayGlances.redrawNow(ctx)
        assertNull(notice())
        assertEquals("Unlock to see your day.", line(5))
        keyguard.setKeyguardLocked(false)

        // Today off with the summary on: nothing on the lock screen either.
        home { it.copy(today = false, lockSummary = true) }
        TodayGlances.redrawNow(ctx)
        assertNull(notice())
    }

    /** today-fix-c 7 (review finding 7): with Today off and drawn so, a screen event does nothing until the settings change. */
    @Test fun todayOffScreenEventsDoNothing() {
        home { it.copy(today = false) }
        TodayGlances.redrawNow(ctx)
        assertFalse(TodayGlances.screen(ctx))
        home { it.turnedOn() }
        assertTrue(TodayGlances.screen(ctx))
    }

    /** today-fix-c 10 (review finding 10): at font 2.0 in its smallest size the day is one line ending "…", never cut mid-line. */
    @Test fun theLineFitsAtTheLargestFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shadowOf(m).bindAppWidgetId(7, ComponentName(ctx, TodayWidgetProvider::class.java))
        home { it.turnedOn() }
        TodayGlances.redrawNow(ctx)
        val v = shadowOf(m).getViewFor(7).findViewById<TextView>(R.id.today_widget_line)
        assertEquals(1, v.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, v.ellipsize)
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
    }

    /** today-fix-c 3 (review finding 3): without the permission Robot can't post the summary, so the switch asks first. */
    @Test fun noPermissionNoNotice() {
        assertFalse(TodayGlances.canNotify(ctx))
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(TodayGlances.canNotify(ctx))
    }

    /** today-fix-c 12 (review finding 12): the stored forecast goes when the weather line or Today is turned off. */
    @Test fun theForecastGoesWithTheWeatherLine() {
        val f = app.heyrobot.sys.TodayWeatherStore.file(ctx)
        for (off in listOf<(HomeSettings) -> HomeSettings>({ it.copy(weather = false) }, { it.copy(today = false) })) {
            home { it.turnedOn().copy(weather = true) }
            f.parentFile?.mkdirs(); f.writeText("{}")
            home { it.copy(weather = true) }
            assertTrue(f.isFile)
            home(off)
            assertFalse(f.exists())
        }
    }
}
