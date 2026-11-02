// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** fb13: a dialog has a window of its own, which MainActivity's filter doesn't reach; Robot's AlertDialog filters that window. Only a phone can show a real tap being refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialogRefusesCoveredTapsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theDialogsOwnWindowRefusesCoveredTaps() {
        rule.setContent { AlertDialog(onDismissRequest = {}, confirmButton = { Text("Yes") }) }
        rule.waitForIdle()
        val decor = ShadowDialog.getLatestDialog().window!!.decorView
        assertTrue(decor.filterTouchesWhenObscured)
    }
}
