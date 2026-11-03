// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.classic

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import app.heyrobot.core.LookSettings
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.setShotContent
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.2.1, from the phone: pressing the card's camera opened Camera, Photos and File with their words broken over two
 * lines, so "Camera" and "Photos" didn't line up. Each icon and its word stay on one line, at every text size, in French too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class AttachRowLineTest {
    @get:Rule val rule = createComposeRule()

    private fun check(labels: List<String>, shot: String) {
        rule.setShotContent {
            RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { ClassicAttachRow(false, {}, {}, {}, {}) }
        }
        for (w in labels) {
            val out = ArrayList<TextLayoutResult>()
            rule.onNodeWithText(w).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
            val l = out.first()
            assertEquals("'$w' is on one line", 1, l.lineCount)
            // Not hasVisualOverflow: it compares against the width the text was offered, not the width of its line.
            assertFalse("'$w' is cut", l.isLineEllipsized(0) || l.multiParagraph.getLineWidth(0) > l.size.width)
        }
        // Across, every button sits on one line; stacked, each has the row's whole width.
        val boxes: List<Rect> = labels.map {
            val b = rule.onNodeWithText(it).getUnclippedBoundsInRoot()
            Rect(b.left.value, b.top.value, b.right.value, b.bottom.value)
        }
        val across = boxes.all { it.top == boxes[0].top }
        val stacked = boxes.all { it.left == boxes[0].left && it.right == boxes[0].right }
        assertFalse("the buttons are neither in a line nor stacked: $boxes", !across && !stacked)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot(shot)
    }

    private val english = listOf("Camera", "Photos", "File")
    private val french = listOf("Appareil photo", "Photos", "Fichier")

    @Test fun english() = check(english, "panel_attach_row")

    @Test @Config(fontScale = 2f)
    fun englishAtTwiceTheTextSize() = check(english, "panel_attach_row_font200")

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun englishOnANarrowPhone() = check(english, "panel_attach_row_narrow")

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun french() = check(french, "panel_attach_row_fr")

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi", fontScale = 2f)
    fun frenchAtTwiceTheTextSize() = check(french, "panel_attach_row_fr_font200")
}
