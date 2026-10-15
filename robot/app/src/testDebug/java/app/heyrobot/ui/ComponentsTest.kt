// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The older shared blocks (Components.kt), restyled in place: what every screen not yet rebuilt now looks like. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    private fun content() = rule.show {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenTitle("Settings")
            SectionCard("Get started") {
                StatusRow(Mark.OK, "Download the model", "Ready on this phone")
                StatusRow(Mark.TODO, "Allow the microphone and notifications", "For “hey robot”") { FilledTonalButton(onClick = {}) { Text("Allow") } }
                StatusRow(Mark.WARN, "Voice models", "The file is damaged")
            }
            SectionCard("Advanced") {
                NavRow(RIcons.Terminal, "ADB fixes", "One-time fixes with a computer") {}
                NavRow(RIcons.Palette, "Display", "Wallpaper colours") {}
            }
        }
    }

    @Test fun light() {
        content()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Get started").assert(isHeading())
        rule.onNodeWithText("ADB fixes").assertHeightIsAtLeast(72.dp)
        // The status symbols say what they mean.
        for (label in listOf("Done", "To do", "Problem")) rule.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
        rule.shot("components_light")
    }
}
