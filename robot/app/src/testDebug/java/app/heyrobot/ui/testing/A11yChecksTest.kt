// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.testing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.heyrobot.core.LookSettings
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The checker itself: it passes proper icon buttons and catches what it is for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11yChecksTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { Surface { content() } } }

    @Test fun labelledIconButtonsInARowPass() {
        show {
            Row {
                IconButton(onClick = {}) { Icon(RIcons.VolumeUp, "Speak replies") }
                IconButton(onClick = {}) { Icon(RIcons.Add, "New conversation") }
                IconButton(onClick = {}) { Icon(RIcons.Close, "Close") }
            }
        }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun anUnlabelledTargetIsCaught() {
        show { Box(Modifier.size(48.dp).clickable {}) { Icon(RIcons.Mic, null) } }
        val p = rule.touchTargetProblems()
        assertTrue(p.toString(), p.single().startsWith("no label"))
    }

    @Test fun crowdedSmallTargetsAreCaught() {
        show {
            Row {
                Box(Modifier.size(24.dp).clickable {}) { Icon(RIcons.Mic, "Talk") }
                Box(Modifier.size(24.dp).clickable {}) { Icon(RIcons.Stop, "Stop") }
            }
        }
        val p = rule.touchTargetProblems()
        assertTrue(p.toString(), p.any { it.startsWith("overlapping targets: 'Talk' and 'Stop'") })
    }
}
