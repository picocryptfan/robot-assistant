// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Action
import app.heyrobot.core.RobotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** fix-batch-15 L7 (review-batch-15): a move's new alarm goes through your clock's page (no SKIP_UI); any other skips it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmMovedPageTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun onlyAMovedAlarmShowsTheClocksPage() {
        val clock = ApplicationInfo().apply { packageName = "com.example.clock" }
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = clock.packageName; name = "SetAlarm"; applicationInfo = clock } }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(Intent(AlarmClock.ACTION_SET_ALARM), info)
        val sent = ArrayList<Intent>()
        val ex = ToolExecutor(ctx, RobotSettings()) { sent += it; true }
        assertTrue(ex.setMoved(6, 45).status.succeeded)
        assertTrue(ex.run(Action.SetAlarm(7, 0)).status.succeeded)
        assertEquals(listOf(AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_ALARM), sent.map { it.action })
        assertEquals(listOf(false, true), sent.map { it.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false) })
    }
}
