// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Signal later's notices (review-signal-later 4): silent on any phone setting, hidden on the lock screen, its public
// line naming no question, and gone when cancelled.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaterNoticesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val item = LaterItem("a", "Look up the ferry times to Digby", "ferry times to Digby", Lang.EN, System.currentTimeMillis(), asks = false)

    @Before fun grant() = shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun only(): Notification = shadowOf(nm).allNotifications.single()

    @Test fun theAnswerIsASilentNoticeHiddenOnTheLockScreen() {
        LaterNotices.answer(ctx, item, "Sailings at 9")
        val ch = nm.getNotificationChannel("later")
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        assertNull(ch.sound)
        assertTrue(!ch.shouldVibrate())
        assertEquals(Notification.VISIBILITY_SECRET, ch.lockscreenVisibility)
        val n = only()
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
        val pub = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertEquals("Robot has news about your question", pub)
        assertTrue(!pub.contains("ferry") && n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT) == null)
        assertEquals("Sailings at 9", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun theAskIsHiddenTooAndGoesWhenCancelled() {
        LaterNotices.ask(ctx, item)
        val n = only()
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().endsWith("Look up the ferry times to Digby"))
        LaterNotices.cancel(ctx, listOf(item))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun theAskGoesWhenItsQuestionExpires() {
        val now = System.currentTimeMillis()
        LaterNotices.ask(ctx, item.copy(askedAt = now - (Later.WAIT_MS - 60_000)))
        val t = only().timeoutAfter
        assertTrue("timeout $t", t in 1..60_000)
        // Already past its day: no notice at all.
        LaterNotices.ask(ctx, item.copy(id = "b", askedAt = now - Later.WAIT_MS - 1))
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test fun nothingPostedWithoutThePermission() {
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        LaterNotices.answer(ctx, item, "Sailings at 9")
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
}
