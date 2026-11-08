package app.heyrobot.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** fix-notice-ids-b (review Low 2): after an update, a backup reminder still up under 8,701 is posted again at 8,800. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupReminderMoveTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    private fun up(id: Int, channel: String) {
        nm.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_LOW))
        nm.notify(id, Notification.Builder(ctx, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("old").build())
    }

    private fun shown(id: Int) = nm.activeNotifications.firstOrNull { it.id == id }

    @Test fun aReminderThatWasUpMovesToItsNewId() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        up(8_701, BackupReminders.CHANNEL)
        BackupReminders.dropOld(ctx)
        assertNull(shown(8_701))
        assertEquals(BackupReminders.CHANNEL, shown(8_800)?.notification?.channelId)
        BackupReminders.dropOld(ctx) // the next update: nothing at 8,701, nothing changes
        assertNotNull(shown(8_800))
    }

    @Test fun aSkillsNoticeThereStaysAndNoReminderComes() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        up(8_701, "skills")
        BackupReminders.dropOld(ctx)
        assertNotNull(shown(8_701))
        assertNull(shown(8_800))
    }
}
