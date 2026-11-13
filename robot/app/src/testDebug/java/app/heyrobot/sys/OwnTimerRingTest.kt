// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.app.KeyguardManager
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.routines.OwnTimerPanel
import app.heyrobot.core.OwnTimers
import app.heyrobot.core.TimerCommand
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** named-timers: the ring path on Android's own services: the alarm, the notification, boot, a killed process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnTimerRingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val am get() = ctx.getSystemService(AlarmManager::class.java)
    private fun say(c: TimerCommand) = OwnTimerPanel.answer(ctx, c) { true }.render(Langs.EN)
    private fun alarmAt(): Long? = shadowOf(am).scheduledAlarms.lastOrNull { it.operation == OwnTimerAlarms.intent(ctx) }?.triggerAtTime
    private fun ringNote(id: Int): Notification? = shadowOf(nm).getNotification(OwnTimerIntents.NOTE_BASE + id)

    @Before fun on() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OwnTimerStore.setOn(ctx, true)
        // The ring's screen hook lives in the object for the process, as on a phone, but each test gets a new Application:
        // one left from an earlier test's named ring would be registered on that test's app, so this one's unlock never reached it.
        OwnTimerRing::class.java.getDeclaredField("screenHook").apply { isAccessible = true }.set(null, null)
    }

    @Test fun aNamedTimerIsKeptOnDiskAndArmsTheExactAlarm() {
        assertTrue(say(TimerCommand.Set("pasta", 540, false)).startsWith("The pasta timer is set for 9"))
        val t = OwnTimerStore.load(ctx).single()
        assertEquals(t.ends, alarmAt())
    }

    @Test fun bootSetsTheAlarmAgainAndAKilledProcessLosesNothing() {
        say(TimerCommand.Set("eggs", 120, false))
        am.cancel(OwnTimerAlarms.intent(ctx))
        assertNull(alarmAt())
        // Nothing is held in memory: the receiver reads the file, as a new process would.
        assertFalse(OwnTimerRing.receive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED)))
        assertEquals(OwnTimerStore.load(ctx).single().ends, alarmAt())
    }

    @Test fun itRingsInsistentlyAsAnAlarmAndStopStopsIt() {
        say(TimerCommand.Set("pasta", 60, false))
        val t = OwnTimerStore.load(ctx).single()
        OwnTimerRing.onFire(ctx, t.ends + 500)
        val n = ringNote(t.id)!!
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(AudioAttributes.USAGE_ALARM, nm.getNotificationChannel(OwnTimerIntents.CHANNEL).audioAttributes.usage)
        assertEquals(1, OwnTimers.ringing(OwnTimerStore.load(ctx, t.ends + 600), t.ends + 600).size)
        assertTrue(OwnTimerRing.receive(ctx, Intent(OwnTimerIntents.ACTION_STOP).putExtra(OwnTimerIntents.EXTRA_ID, t.id)))
        assertNull(ringNote(t.id))
        assertTrue(OwnTimerStore.load(ctx).isEmpty())
    }

    @Test fun aTimerThatEndedWhileThePhoneWasOffRingsLateOrIsToldMissed() {
        val now = System.currentTimeMillis()
        OwnTimerStore.update(ctx, now) { (OwnTimers.add(it, "bread", 60, now - 20 * 60_000) as OwnTimers.Added.Ok).timers }
        OwnTimerStore.update(ctx, now) { (OwnTimers.add(it, "rice", 60, now - 3 * 3_600_000) as OwnTimers.Added.Ok).timers }
        OwnTimerRing.receive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        val late = ringNote(1)!!
        assertTrue(late.flags and Notification.FLAG_INSISTENT != 0)
        assertTrue(late.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("Late"))
        val missed = ringNote(2)!!
        assertEquals(0, missed.flags and Notification.FLAG_INSISTENT)
        assertEquals("Timer missed", missed.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("bread"), OwnTimerStore.load(ctx).map { it.name })
        assertNull(alarmAt())
    }

    @Test fun howLongCancelAndStopByVoice() {
        say(TimerCommand.Set("pasta", 540, false))
        say(TimerCommand.Set("eggs", 120, false))
        assertTrue(say(TimerCommand.Left("pasta", false)).startsWith("The pasta timer has 9 minutes left"))
        assertTrue(say(TimerCommand.Cancel("", false)).startsWith("You have 2 timers. Say which one to cancel."))
        assertEquals("The eggs timer is cancelled.", say(TimerCommand.Cancel("eggs", false)))
        val t = OwnTimerStore.load(ctx).single()
        assertEquals(t.ends, alarmAt())
        OwnTimerRing.onFire(ctx, t.ends + 1)
        // The ring ended at t.ends + 1; the list is read at the real time here, so it is marked rung as below.
        OwnTimerStore.update(ctx) { OwnTimers.rang(it, setOf(t.id), System.currentTimeMillis()) }
        assertEquals("Timer stopped.", say(TimerCommand.Stop("", false)))
        assertNull(ringNote(t.id))
    }

    @Test fun withNotificationsOffTheTimerGoesToTheClockAndRobotSaysWhy() {
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNull(OwnTimerPanel.forTool(ctx, 300))
        assertTrue(say(TimerCommand.Set("pasta", 300, false)).startsWith("Robot can't ring because its notifications are off"))
        assertTrue(OwnTimerStore.load(ctx).isEmpty())
    }

    @Test fun offTheModelsTimerStaysTheClocks() {
        OwnTimerStore.setOn(ctx, false)
        assertNull(OwnTimerPanel.forTool(ctx, 300))
        assertNull(OwnTimerPanel.route(ctx, "pasta timer nine minutes"))
    }

    @Test fun anHourOnTheClockLeavesTheTimerEndingOnTime() {
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 4)
        val wall = System.currentTimeMillis()
        val el = 50_000L
        OwnTimerStore.update(ctx, wall, el) { (OwnTimers.add(it, "pasta", 600, wall) as OwnTimers.Added.Ok).timers }
        val moved = wall + 3_600_000 + 10_000
        val l = OwnTimerStore.load(ctx, moved, el + 10_000)
        assertEquals(moved + 590_000, l.single().ends)
        assertTrue(OwnTimers.due(l, moved).isEmpty())
    }

    @Test fun aCancelLandingAsTheRingReadsTheListNeverLeavesARingWithoutItsTimer() {
        say(TimerCommand.Set("pasta", 60, false))
        val t = OwnTimerStore.load(ctx).single()
        OwnTimerStore.afterRead = { OwnTimerStore.afterRead = null; OwnTimerRing.cancel(ctx, setOf(t.id)) }
        try { OwnTimerRing.onFire(ctx, t.ends + 1_000) } finally { OwnTimerStore.afterRead = null }
        val kept = OwnTimerStore.load(ctx, t.ends + 1_000).singleOrNull { it.id == t.id }
        assertEquals(kept != null && kept.rang > 0, ringNote(t.id) != null)
    }

    @Test fun theNameStaysOffALockedScreenAndComesBackAtTheUnlock() {
        val km = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        say(TimerCommand.Set("pasta", 60, false))
        val t = OwnTimerStore.load(ctx).single()
        fun title() = ringNote(t.id)!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        km.setKeyguardLocked(true)
        OwnTimerRing.onFire(ctx, t.ends + 1_000)
        assertEquals("Timer", title())
        assertEquals("Timer", ringNote(t.id)!!.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        km.setKeyguardLocked(false)
        ctx.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("pasta timer", title())
        assertTrue(ringNote(t.id)!!.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // The screen going off takes the name down again, before the lock screen shows.
        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Timer", title())
    }
}
