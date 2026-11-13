// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.core.QuickButton
import app.heyrobot.core.QuickItem
import app.heyrobot.core.QuickItems
import app.heyrobot.core.QuickPhone
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** HS3: Quick access's page (2 columns, 1 at large fonts, 96 dp buttons, "Set up") and Display's Home screen row. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class QuickHomeTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    // Robot to Robot on, two skills, the interpreter's files not here but downloadable.
    private val buttons = QuickItems.shown(QuickItem.DEFAULT, QuickPhone(link = true, skills = 2, speech = true, translationToGet = true))
    private var opened: QuickButton? = null
    private val moved = ArrayList<Pair<QuickItem, Int>>()

    private fun page(dark: Boolean = false) = rule.show(dark) {
        QuickHome(buttons, Modifier, onOpen = { opened = it }, onHistory = {}, onGettingStarted = {}, onMove = { i, to -> moved += i to to })
    }
    private fun left(name: String) = rule.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot.left

    @Test fun eachButtonOpensItsFeatureAndSaysItsState() {
        page()
        assertEquals(QuickItem.entries.size, buttons.size)
        rule.onNodeWithContentDescription("Car mode").performClick()
        assertEquals(QuickItem.CAR, opened?.item)
        rule.onNodeWithContentDescription("Interpreter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Set up"))
        rule.onNodeWithContentDescription("Talk to Robot").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        // TalkBack's Move down on the first button: Talk to Robot takes the second place.
        val down = rule.onNodeWithContentDescription("Talk to Robot").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move down"), down.map { it.label })
        down.single().action()
        assertEquals(listOf(QuickItem.TALK to 1), moved)
        assertNotEquals(left("Talk to Robot"), left("Car mode"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("quick_access")
    }

    @Test fun dark() { page(dark = true); rule.shot("quick_access_dark") }

    @Test @Config(fontScale = 2f)
    fun oneColumnAtTwiceTheTextSize() {
        page()
        assertEquals(left("Talk to Robot"), left("Car mode"), 0.5f)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("quick_access_font200")
    }

    @Test @Config(fontScale = 2f)
    fun oneColumnDark() { page(dark = true); rule.shot("quick_access_font200_dark") }

    @Test fun displaysHomeScreenRowHasThreeChoicesAndTodaysSettingsOnlyWithToday() {
        rule.show { TodayHomeSetting() }
        val weather = ctx.getString(R.string.today_weather_setting)
        rule.onNodeWithText("Getting started").assertIsSelected()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText(weather)).fetchSemanticsNodes().size)
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithText("Today").assertIsSelected()
        assertEquals(1, rule.onAllNodes(androidx.compose.ui.test.hasText(weather)).fetchSemanticsNodes().size)
        rule.onNodeWithText("Quick access").performClick()
        rule.onNodeWithText("Quick access").assertIsSelected()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText(weather)).fetchSemanticsNodes().size)
        rule.shot("display_home_screen")
    }
}
