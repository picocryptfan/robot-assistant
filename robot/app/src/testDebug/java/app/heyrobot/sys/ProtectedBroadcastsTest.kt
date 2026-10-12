package app.heyrobot.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.0 audit ipc-3: the unlock (USER_PRESENT, sent by SystemUI) reaches the receivers registered for it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtectedBroadcastsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun theUnlockReachesItsReceivers() {
        var seen = 0
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { seen++ } }
        ProtectedBroadcasts.register(ctx, r, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT))
        ctx.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, seen)
        // Only protected system broadcasts may be exported this way.
        assertTrue(runCatching { ProtectedBroadcasts.filter("com.android.deskclock.ALARM_ALERT") }.isFailure)
        assertTrue(runCatching { ProtectedBroadcasts.register(ctx, r, IntentFilter("app.heyrobot.X")) }.isFailure)
    }
}
