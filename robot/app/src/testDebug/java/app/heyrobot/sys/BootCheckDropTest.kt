// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.boot.BootCheckReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.2.1: the voice service stopping between restarts doesn't leave you thinking Robot is listening. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootCheckDropTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    @Before fun itWasTheAssistantInThisBoot() = BootCheck.onVisReady(ctx)

    private fun setActive(on: Boolean) = Settings.Secure.putString(ctx.contentResolver, "voice_interaction_service",
        if (on) ComponentName(ctx, RobotVoiceService::class.java).flattenToString() else "")

    /** What the service's onShutdown left armed: the alarm's own intent, so the check runs as the receiver runs it. */
    private fun shutDownAndGetTheAlarm(): Intent {
        val service = Robolectric.buildService(RobotVoiceService::class.java).get()
        service.onShutdown()
        val alarm = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.lastOrNull()
        assertNotNull("onShutdown armed no check", alarm)
        val intent = shadowOf(alarm!!.operation).savedIntent
        assertEquals(BootCheckReceiver::class.java.name, intent.component?.className)
        return intent
    }

    @Test fun droppedAndNotTheAssistantSaysSo() {
        val alarm = shutDownAndGetTheAlarm()
        setActive(false)
        BootCheck.run(ctx, alarm)
        val notice = shadowOf(nm).allNotifications.singleOrNull()
        assertNotNull("no notification", notice)
        assertEquals(ctx.getString(R.string.settings_dropped_text), shadowOf(notice).contentText)
        assertTrue(ctx.getString(R.string.settings_dropped_text).contains("wake word"))
    }

    @Test fun androidOnlyRestartedItSaysNothing() {
        val alarm = shutDownAndGetTheAlarm()
        setActive(true)
        BootCheck.run(ctx, alarm)
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    /** The restart check keeps its meaning: started in this boot is enough, without the drop's extra. */
    @Test fun theRestartCheckIsUnchanged() {
        setActive(false)
        BootCheck.run(ctx, Intent(ctx, BootCheckReceiver::class.java))
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
