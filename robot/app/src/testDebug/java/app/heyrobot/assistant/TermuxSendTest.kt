// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.termux.TermuxIntents
import app.heyrobot.core.termux.TermuxRun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The RUN_COMMAND intent as Termux expects it (B1, B8.2): explicit to RunCommandService, every extra of the plan, the label
 * "Robot"; the result PendingIntent explicit to Robot's receiver, one-shot, mutable and unique per run; the result bundle
 * read back; and a result for no known run (or a forged address) dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermuxSendTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun theIntentCarriesThePlanExactly() {
        val plan = TermuxRun.command(41, "du -sh ~", 60)
        val i = TermuxSend.intent(ctx, plan)
        assertEquals(TermuxIntents.ACTION_RUN_COMMAND, i.action)
        assertEquals("com.termux", i.component!!.packageName)
        assertEquals("com.termux.app.RunCommandService", i.component!!.className)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", i.getStringExtra(TermuxIntents.EXTRA_COMMAND_PATH))
        assertArrayEquals(plan.arguments.toTypedArray(), i.getStringArrayExtra(TermuxIntents.EXTRA_ARGUMENTS))
        assertEquals("/data/data/com.termux/files/home", i.getStringExtra(TermuxIntents.EXTRA_WORKDIR))
        assertTrue(i.getBooleanExtra(TermuxIntents.EXTRA_BACKGROUND, false))
        assertEquals("app-shell", i.getStringExtra(TermuxIntents.EXTRA_RUNNER))
        assertEquals("Robot", i.getStringExtra(TermuxIntents.EXTRA_COMMAND_LABEL))
        assertFalse(i.hasExtra(TermuxIntents.EXTRA_STDIN))
        // The command is an argument of the fixed wrapper, never the label.
        assertEquals("du -sh ~", plan.arguments.last())
        @Suppress("DEPRECATION") val pi = i.getParcelableExtra<PendingIntent>(TermuxIntents.EXTRA_PENDING_INTENT)!!
        val s = shadowOf(pi)
        assertTrue(s.isBroadcast)
        assertEquals(41, s.requestCode)
        assertEquals(PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE, s.flags and (PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE))
        assertEquals(TermuxResultReceiver::class.java.name, s.savedIntent.component!!.className)
        assertEquals("robot-termux://run/41", s.savedIntent.dataString)
        // Another run, another PendingIntent.
        val other = shadowOf(TermuxSend.resultIntent(ctx, 42))
        assertEquals(42, other.requestCode)
        assertEquals("robot-termux://run/42", other.savedIntent.dataString)
        // Open in Termux: a terminal session, switched to without its activity.
        val t = TermuxSend.intent(ctx, TermuxRun.terminal(43, "htop"))
        assertEquals("terminal-session", t.getStringExtra(TermuxIntents.EXTRA_RUNNER))
        assertEquals("2", t.getStringExtra(TermuxIntents.EXTRA_SESSION_ACTION))
        assertFalse(t.getBooleanExtra(TermuxIntents.EXTRA_BACKGROUND, true))
    }

    @Test fun theResultBundleIsReadBack() {
        val b = Bundle().apply {
            putString("stdout", "robot-ok\n"); putString("stderr", ""); putInt("exitCode", 0); putInt("err", -1); putString("errmsg", "")
            putString("stdout_original_length", "9")
        }
        val r = TermuxSend.result(b)
        assertEquals("robot-ok\n", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals(-1, r.err)
        assertEquals("9", r.stdoutOriginalLength)
        val none = TermuxSend.result(null)
        assertNull(none.exitCode)
        assertTrue(none.err != TermuxIntents.ERR_NONE)
    }

    @Test fun aResultForNoKnownRunIsDropped() {
        val receiver = TermuxResultReceiver()
        // A forged address, and an id Robot never sent: nothing happens, nothing throws.
        receiver.onReceive(ctx, Intent().setData(android.net.Uri.parse("robot-termux://run/abc")))
        receiver.onReceive(ctx, Intent().setData(android.net.Uri.parse("robot-termux://run/77")).putExtra("result", Bundle()))
        assertEquals(0, TermuxTool.runner(ctx).waiting)
    }
}
