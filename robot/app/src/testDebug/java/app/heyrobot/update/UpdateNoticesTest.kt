// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.sys.AppPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Updates notice: "available" then "ready" is one notification, its tap, and whether Robot can post it at all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateNoticesTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    @Test fun availableTurnsIntoReadyInTheSameNotificationAndOpensUpdates() {
        shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(UpdateNotices.canPost(ctx))
        assertTrue(UpdateNotices.available(ctx, "0.4.6"))
        assertEquals("Robot 0.4.6 is available", shadowOf(nm).allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
        assertTrue(UpdateNotices.ready(ctx, "0.4.6"))
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("Robot 0.4.6 is ready to install", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(AppPages.UPDATES, shadowOf(n.contentIntent).savedIntent.getStringExtra(AppPages.EXTRA))
    }

    @Test fun refusedPermissionCantPostAndOpensRobotsNotifications() {
        shadowOf(ctx).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(UpdateNotices.canPost(ctx))
        assertFalse(UpdateNotices.available(ctx, "0.4.6"))
        val i = UpdateNotices.settings(ctx)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, i.action)
        assertEquals(ctx.packageName, i.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun theUpdatesChannelOffCantPostAndOpensItsPage() {
        shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm.createNotificationChannel(NotificationChannel(UpdateNotices.CHANNEL, "Updates", NotificationManager.IMPORTANCE_NONE))
        assertFalse(UpdateNotices.canPost(ctx))
        val i = UpdateNotices.settings(ctx)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, i.action)
        assertEquals(UpdateNotices.CHANNEL, i.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
}
