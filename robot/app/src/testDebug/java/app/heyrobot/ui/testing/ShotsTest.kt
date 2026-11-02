// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A golden can't depend on how long a real thread took: the cursor's blink, which once made lists_page_done flaky, stays out of every shot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ShotsTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var view: View

    @Composable private fun field() { view = LocalView.current; BasicTextField("", {}) }

    private fun pixels(): Bitmap {
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(b)) }
        return b
    }

    /** The screen right after the field takes focus, and again in the part of the blink where a blinking cursor is off. */
    private fun twoMoments(): Pair<Bitmap, Bitmap> {
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitForIdle()
        val on = pixels()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        return on to pixels()
    }

    /** Shows that the next test can see a blink: without setShotContent the two moments differ. */
    @Test fun aPlainFieldsCursorBlinks() {
        rule.setContent { field() }
        val (a, b) = twoMoments()
        assertFalse(a.sameAs(b))
    }

    @Test fun aShotsCursorStaysOn() {
        rule.setShotContent { field() }
        val (a, b) = twoMoments()
        assertTrue(a.sameAs(b))
    }

    /** A test with goldens that set its content another way would bring the flake back. */
    @Test fun everyTestWithGoldensUsesShotContent() {
        val root = File("src/testDebug/java")
        assertTrue(root.isDirectory)
        val plain = root.walkTopDown().filter { it.extension == "kt" && it.name != "ShotsTest.kt" }
            .filter { val s = it.readText(); s.contains(".shot(") && s.contains("rule.setContent") }.map { it.name }.toList()
        assertEquals(emptyList<String>(), plain)
    }
}
