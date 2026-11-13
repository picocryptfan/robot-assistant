// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.skills.MultiAsk
import app.heyrobot.sys.OwnTimerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** Several timers in one sentence (MultiAsk, then each part's set_timer): Robot's own timers while they're on, else the clock app's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiTimerOwnTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sentences = listOf("set a timer for 5 minutes and a timer for 10 minutes", "mets une minuterie de 5 minutes et une minuterie de 10 minutes")

    @Before fun clock() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val app = ApplicationInfo().apply { packageName = "com.example.clock" }
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = app.packageName; name = "SetTimer"; applicationInfo = app } }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(Intent(AlarmClock.ACTION_SET_TIMER), info)
    }

    /** Each part of [s] run as the panel runs it: parsed, then the executor; the intents it sent. */
    private fun runAll(s: String): List<Intent> {
        val parts = requireNotNull(MultiAsk.split(s, ZonedDateTime.now())) { s }
        assertEquals(s, listOf(MultiAsk.Kind.TIMER, MultiAsk.Kind.TIMER), parts.map { it.kind })
        val sent = ArrayList<Intent>()
        val ex = ToolExecutor(ctx, RobotSettings()) { sent += it; true }
        for (p in parts) ex.run((ToolCalls.parse(p.call) as ParsedCall.Ok).action)
        return sent
    }

    @Test fun onBothGoToRobotsOwnTimers() {
        OwnTimerStore.setOn(ctx, true)
        sentences.forEachIndexed { i, s ->
            val sent = runAll(s)
            assertTrue("$s: ${sent.map { it.action }}", sent.none { it.action == AlarmClock.ACTION_SET_TIMER })
            // Two more of Robot's own each time: 5 and 10 minutes.
            assertEquals(s, List(i + 1) { listOf(300L, 600L) }.flatten().sorted(), OwnTimerStore.load(ctx).map { it.seconds }.sorted())
        }
    }

    @Test fun offBothGoToTheClockApp() {
        OwnTimerStore.setOn(ctx, false)
        for (s in sentences) {
            val sent = runAll(s).filter { it.action == AlarmClock.ACTION_SET_TIMER }
            assertEquals(s, listOf(300, 600), sent.map { it.getIntExtra(AlarmClock.EXTRA_LENGTH, 0) })
        }
        assertTrue(OwnTimerStore.load(ctx).isEmpty())
    }

    @Test fun aTimerAndTheFlashlightIsNotSplit() {
        assertNull(MultiAsk.split("set a timer for 5 minutes and turn on the flashlight", ZonedDateTime.now()))
    }
}
