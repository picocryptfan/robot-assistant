// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.provider.Settings
import android.speech.RecognitionService
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ShizukuOutcome
import app.heyrobot.core.ShizukuRefusal
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.TermuxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.0, Settings › Advanced › ADB fixes' "Run with Shizuku through Termux", in the :voice process (TermuxTool.setup, as
 * Cmd.TERMUX "shizuku" asks it): nothing is sent for anything but an AdbFixes id, nothing on the lock screen, nothing to a
 * Termux that isn't set up and trusted; the page gets the refusal's code, and nothing reaches Termux (no service started).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShizukuRunTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var mark = 0L

    @Before fun setUp() {
        mark = VoiceLog.total
        TermuxStore.saveStatus(ctx, app.heyrobot.core.termux.TermuxSetupStatus())
    }

    /** Runs it and waits for the :voice side to write its end (it runs on its own coroutine). */
    private fun runFix(id: String?): app.heyrobot.core.termux.TermuxSetupStatus {
        TermuxTool.setup(ctx, TermuxTool.SHIZUKU, id)
        val until = System.currentTimeMillis() + 10_000
        while (TermuxStore.status(ctx).running != null && System.currentTimeMillis() < until) Thread.sleep(20)
        return TermuxStore.status(ctx)
    }

    private fun events() = VoiceLog.entries().take((VoiceLog.total - mark).toInt()).reversed().filter { it.event == VoiceEvent.TERMUX_ADB_FIX }

    private fun nothingSent() = assertNull(shadowOf(ctx as android.app.Application).nextStartedService)

    @Test fun onlyAnAdbFixIsEverSent() {
        for (bad in listOf("reboot", "recognizer; reboot", null)) {
            val st = runFix(bad)
            assertNull(st.shizuku)
            assertNull(st.running)
        }
        assertEquals(listOf(ShizukuRefusal.NOT_A_FIX.id), events().map { it.c }.distinct())
        nothingSent()
    }

    @Test fun neverOnTheLockScreen() {
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val r = runFix("repair").shizuku!!
        assertEquals(ShizukuOutcome.REFUSED, r.outcome)
        assertEquals(ShizukuRefusal.LOCKED, r.refusal)
        assertEquals(listOf(Triple(2, ShizukuOutcome.REFUSED.id, ShizukuRefusal.LOCKED.id)), events().map { Triple(it.a, it.b, it.c) })
        nothingSent()
    }

    /**
     * 1.2 (every-android-phone, Step 6): another installed speech recognizer is set (a stock phone's own): the recognizer
     * fix would replace it for every app, so :voice never sends it, whatever the page showed; the repair fix goes on to its
     * usual checks.
     */
    @Test fun neverTheRecognizerFixOverAnotherInstalledRecognizer() {
        val other = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        val pm = shadowOf(ctx.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = other.packageName; applicationInfo = ApplicationInfo().apply { packageName = other.packageName } })
        pm.addServiceIfNotPresent(other)
        pm.addIntentFilterForService(other, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        Settings.Secure.putString(ctx.contentResolver, "voice_recognition_service", other.flattenToString())
        assertEquals(app.heyrobot.core.RecognizerRepair.Kept.OTHER, app.heyrobot.sys.RecognizerKeeper.kept(ctx))
        val r = runFix("recognizer").shizuku!!
        assertEquals(ShizukuOutcome.REFUSED, r.outcome)
        assertEquals(ShizukuRefusal.NOT_NEEDED, r.refusal)
        assertEquals(listOf(Triple(1, ShizukuOutcome.REFUSED.id, ShizukuRefusal.NOT_NEEDED.id)), events().map { Triple(it.a, it.b, it.c) })
        // The grant replaces nothing: it goes on to Termux's checks (none here).
        assertEquals(ShizukuRefusal.TERMUX_NOT_READY, runFix("repair").shizuku!!.refusal)
        nothingSent()
        // Still set as it was.
        assertEquals(other.flattenToString(), Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service"))
    }

    @Test fun onlyToATermuxSetUpAndTrusted() {
        // No Termux here: not set up, so not sent.
        val r = runFix("recognizer").shizuku!!
        assertEquals("recognizer", r.fixId)
        assertEquals(ShizukuRefusal.TERMUX_NOT_READY, r.refusal)
        nothingSent()
    }
}
