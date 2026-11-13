// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.car.CarInfo
import app.heyrobot.core.car.CarLayout
import app.heyrobot.core.car.CarWidget
import app.heyrobot.core.everyday.MusicApp
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

private const val HALF = "w411dp-h420dp-xhdpi"
private const val HALF_FR = "fr-rCA-w411dp-h420dp-xhdpi"

/**
 * The bottom screen's widget (docs/plans/car-screens-plan.md): a stand-in widget above the buttons, the swap to the media
 * player and back, a turn over it, the media player over the lock screen, and every target big at 200% font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = HALF)
class CarSlotScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = ArrayList<String>()
    private val stand = "Stand-in widget" // l10n: not text (a test's stand-in)
    private val actions = CarScreenActions(swapBottom = { calls += "swap" }, widget = { m, shown ->
        if (shown) Box(m.background(Color(0xFF2E5E4E))) { Text(stand, color = Color.White) } else calls += "hidden"
    })
    private val radio = CarWidget(MusicApp("org.example.radio", "a".repeat(64), "Radio"), "org.example.radio.NowPlaying", 7)
    private val split = CarScreenState(music = CarMusicUi("Radio app", "Harvest Moon", playing = true, artist = "Neil Young"),
        beside = true, layout = CarLayout(info = CarInfo.WIDGET, widget = radio))

    private fun draw(s: CarScreenState) = rule.show { CarModeScreen(s, actions) }
    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    @Test fun theWidgetShowsWithItsSwap() {
        draw(split)
        rule.onNodeWithText(stand).assertExists()
        rule.onNodeWithContentDescription("Media player").performClick()
        assertEquals(listOf("swap"), calls)
        rule.shot("car_slot_widget")
        noProblems()
    }

    @Test @Config(qualifiers = HALF_FR) fun theSwapIsInFrench() {
        draw(split)
        rule.onNodeWithContentDescription("Lecteur multimédia").assertExists()
    }

    // The widget's side has no words of its own (its button is an icon), so the French shot is the swapped side.
    @Test @Config(qualifiers = HALF_FR) fun theSwappedSideIsInFrench() {
        draw(split.copy(swapped = true))
        rule.onNodeWithContentDescription("Précédent").assertExists()
        rule.onNodeWithContentDescription("Radio").assertExists()
        rule.shot("car_slot_widget_fr")
    }

    @Test fun swappedShowsTheMediaPlayerAndTheWidgetsApp() {
        draw(split.copy(swapped = true))
        rule.onNodeWithText(stand).assertDoesNotExist()
        rule.onNodeWithText("Harvest Moon").assertExists()
        rule.onNodeWithContentDescription("Radio").assertExists()
        noProblems()
    }

    @Test fun aTurnTakesTheSlotAndTheWidgetWaits() {
        draw(split.copy(turn = CarTurnUi(words = "What's the weather")))
        rule.onNodeWithText(stand).assertDoesNotExist()
        rule.onNodeWithText("What's the weather", substring = true).assertExists()
        assertEquals("hidden", calls.lastOrNull())
        rule.shot("car_slot_turn")
    }

    @Test fun lockedShowsTheMediaPlayerAndNoSwap() {
        draw(split.copy(widgetOff = true))
        rule.onNodeWithText(stand).assertDoesNotExist()
        rule.onNodeWithText("Harvest Moon").assertExists()
        rule.onNodeWithContentDescription("Radio").assertDoesNotExist()
    }

    @Test @Config(qualifiers = PHONE, fontScale = 2f) fun theLargestFontKeepsEveryTarget() {
        draw(split.copy(beside = false))
        rule.onNodeWithText(stand).assertExists()
        rule.shot("car_slot_large_font")
        noProblems()
    }
}
