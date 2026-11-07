// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.app.Notification
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.sys.HandOffApps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** review-126-final 2: a work profile's Cheogram notification teaches the personal People store nothing; this profile's still does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CheogramProfileTest {
    /** User 10, the usual work profile: the same app's uid there (UserHandle.of is hidden from apps). */
    private val work = UserHandle.getUserHandleForUid(10 * 100_000 + Process.myUid() % 100_000)

    private fun posted(pkg: String, user: UserHandle): StatusBarNotification {
        val n = Notification.Builder(ApplicationProvider.getApplicationContext(), "c").setContentTitle("x").build()
        return StatusBarNotification(pkg, pkg, 7, null, Process.myUid(), 0, 0, n, user, 1_760_191_320_000L)
    }

    @Test fun thisProfilesCheogramIsLearned() {
        assertTrue(CheogramListener.learnsFrom(posted(HandOffApps.CHEOGRAM, Process.myUserHandle())))
        assertTrue(CheogramListener.learnsFrom(posted(HandOffApps.CHEOGRAM_PLAY, Process.myUserHandle())))
    }

    @Test fun aWorkProfilesCheogramIsNot() {
        assertFalse(CheogramListener.learnsFrom(posted(HandOffApps.CHEOGRAM, work)))
        assertFalse(CheogramListener.learnsFrom(posted(HandOffApps.CHEOGRAM_PLAY, work)))
    }

    @Test fun anotherAppIsNot() {
        assertFalse(CheogramListener.learnsFrom(posted("com.example.chat", Process.myUserHandle())))
    }
}
