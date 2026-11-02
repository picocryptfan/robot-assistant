// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** F116: as a phone lock starts, the card and the answer leave the shade, and nothing else of Robot's does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkShadeTest {
    @Test fun aCardAndAnAnswerLeaveTheShadeAndNothingElse() {
        val ctx = RuntimeEnvironment.getApplication()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val other = 42
        for (id in listOf(LinkShade.CARDS_ID, LinkShade.REPLIED_ID, other))
            nm.notify(id, Notification.Builder(ctx, "c").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        LinkShade.hide(ctx)
        assertEquals(1, shadowOf(nm).size())
        assertEquals(true, shadowOf(nm).getNotification(other) != null)
    }
}
