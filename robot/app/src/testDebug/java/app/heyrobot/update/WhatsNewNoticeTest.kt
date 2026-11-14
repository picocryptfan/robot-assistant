// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.guide.SetupGuide
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.SetupGuideStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.5: after an update with something new, one silent notice that opens What's new, once, only if notifications are allowed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsNewNoticeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    private fun kept(s: GuideState) {
        SetupGuideStore.save(app, s)
        SetupGuideStore.awaitWrites()
    }

    @Test fun oneSilentNoticeThatOpensWhatsNewOnce() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        kept(GuideState(seen = SetupGuide.VERSION, finished = SetupGuide.VERSION, app = "1.2.7"))
        assertTrue(WhatsNewNotice.post(app))
        val posted = nm.activeNotifications
        assertEquals(1, posted.size)
        val n = posted[0].notification
        assertEquals(UpdateNotices.CHANNEL, n.channelId)
        // setSilent: no sound or vibration, whatever the channel's.
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        assertEquals(AppPages.WHATS_NEW, shadowOf(n.contentIntent).savedIntent.getStringExtra(AppPages.EXTRA))
        // Once: the same version's update broadcast again posts nothing more.
        nm.cancelAll()
        assertFalse(WhatsNewNotice.post(app))
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun aWhatsNewLaunchClearsOnlyItsOwnNotice() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(UpdateNotices.whatsNew(app, "1.5.0"))
        UpdateNotices.cancelWhatsNew(app)
        assertEquals(0, nm.activeNotifications.size)
        // "Update available" posted after the update and before the launch takes the notice's place: it stays.
        assertTrue(UpdateNotices.whatsNew(app, "1.5.0"))
        assertTrue(UpdateNotices.available(app, "1.5.1"))
        UpdateNotices.cancelWhatsNew(app)
        assertEquals(1, nm.activeNotifications.size)
        assertEquals(null, nm.activeNotifications[0].notification.group)
    }

    @Test fun nothingWhenNotificationsAreNotAllowedOrNothingIsNew() {
        kept(GuideState(seen = SetupGuide.VERSION, finished = SetupGuide.VERSION, app = "1.2.7"))
        assertFalse(WhatsNewNotice.post(app)) // no permission, and none asked for
        assertEquals(null, SetupGuideStore.reload(app).told)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        kept(GuideState()) // a new install: the guide, not What's new
        assertFalse(WhatsNewNotice.post(app))
        assertEquals(0, nm.activeNotifications.size)
    }
}
