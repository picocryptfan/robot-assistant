// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.TurnRole
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// fix-screenshots-d item 5, review-screenshots-c: once the phone locks mid-answer, what streamed goes and nothing
// more shows, even after it unlocks again.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelShotsLockTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test fun nothingStreamsOnceThePhoneLocks() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val kg = shadowOf(km)
        kg.setIsDeviceLocked(false)
        val replies = PanelReplies(MutableStateFlow(PanelState(convId = "c", turns = listOf(UiTurn("r", TurnRole.ASSISTANT, "")))))
        val s = ShotStream(replies, "r", km)
        s.onText("Your Wi-Fi")
        assertEquals("Your Wi-Fi", replies.text("r"))
        kg.setIsDeviceLocked(true)
        s.onText("Your Wi-Fi screenshot")
        assertEquals("", replies.text("r"))
        kg.setIsDeviceLocked(false)
        s.onText("Your Wi-Fi screenshot is from 3 November")
        assertEquals("", replies.text("r"))
    }
}
