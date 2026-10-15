// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ShowTrust
import app.heyrobot.sys.ShowNonces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0 audit ipc-1 and ipc-2, on Android's own Bundle: another app's showAssist arguments are dropped
 * (the brief, Ready cards, the parked list, a headset or call session, the camera's return), Robot's own nonce lets Talk to
 * Robot through once, and the assistant service's own show keeps everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShowGateTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app = VoiceInteractionSession.SHOW_SOURCE_APPLICATION

    private fun forged() = Bundle().apply {
        putString("robot_source", "wake")
        putString("robot_routine", "morning-brief")
        putString("robot_ready", "*")
        putString("robot_parked", "1")
        putBoolean("robot_handsfree_call", true)
        putString("robot_conversation", "c1")
    }

    @Test fun anAppsShowassistOpensThePlainPanel() {
        val out = ShowGate.args(ctx, forged(), app)!!
        assertTrue(out.keySet().toString(), out.isEmpty)
        // Even a capture "return" or the service's token copied in.
        val capture = Bundle().apply { putString("robot_source", "capture"); putString(ShowTrust.ARG_VIS, RobotVoiceService.TOKEN) }
        assertTrue(ShowGate.args(ctx, capture, app)!!.isEmpty)
    }

    @Test fun robotsNonceLetsTalkToRobotThroughOnce() {
        val nonce = ShowNonces.mint(ctx)
        val b = forged().apply { putString(ShowTrust.ARG_NONCE, nonce) }
        val out = ShowGate.args(ctx, b, app)!!
        assertEquals(setOf("robot_source", "robot_conversation"), out.keySet())
        assertEquals("app", out.getString("robot_source"))
        // Single use.
        assertTrue(ShowGate.args(ctx, Bundle(b), app)!!.isEmpty)
        // A guessed one fails, and uses up the real one.
        val real = ShowNonces.mint(ctx)
        assertFalse(ShowNonces.consume(ctx, real.reversed()))
        assertFalse(ShowNonces.consume(ctx, real))
    }

    @Test fun theAssistantsOwnShowKeepsItsArguments() {
        val b = forged().apply { putString(ShowTrust.ARG_VIS, RobotVoiceService.TOKEN) }
        val out = ShowGate.args(ctx, b, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)!!
        assertEquals("wake", out.getString("robot_source"))
        assertEquals("morning-brief", out.getString("robot_routine"))
        assertTrue(out.getBoolean("robot_handsfree_call"))
        assertNull(out.getString(ShowTrust.ARG_VIS))
        // The corner swipe: no token, nothing of Robot's.
        assertTrue(ShowGate.args(ctx, forged(), VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE)!!.isEmpty)
    }
}
