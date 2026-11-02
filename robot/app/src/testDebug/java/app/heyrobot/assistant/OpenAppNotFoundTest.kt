// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Prepared
import app.heyrobot.core.RobotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An app that isn't installed: "Not found", never "Didn't work", which says Robot tried. Nothing starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAppNotFoundTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun noSuchAppSaysSoAndStartsNothing() {
        val started = mutableListOf<android.content.Intent>()
        val exec = ToolExecutor(ctx, RobotSettings()) { started += it; true }
        val p = exec.prepare(Action.OpenApp("Home"))
        assertTrue(p is Prepared.Refused)
        assertEquals(ActionStatus.NOT_FOUND, (p as Prepared.Refused).status)
        assertTrue(started.isEmpty())
        assertFalse(ActionStatus.NOT_FOUND.text.en().contains("work"))
    }
}
