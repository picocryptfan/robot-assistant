package app.heyrobot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.AdbFixes
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Advanced › ADB fixes: a status row per fix, and a fix's page with its commands and Copy. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class AdbFixesTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    @Test fun eachFixIsAStatusRowThatOpensItsPage() {
        rule.show { AdbFixesScreen(log.actions, SnackbarHostState(), onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        for (fix in AdbFixes.ALL) rule.onNodeWithText(fix.title).assert(hasText("Not done")).performClick()
        assertEquals(AdbFixes.ALL.map { "openPage ${Page.Settings(SettingsRoutes.adbFix(it.id))}" }, log)
    }

    @Test fun aFixPageShowsWhyAndTheCommandsToCopy() {
        val fix = AdbFixes.ALL.first()
        rule.show { AdbFixScreen(fix.id, SnackbarHostState(), onBack = {}) }
        rule.onNodeWithText(fix.why).assertExists()
        rule.scrollToText("Copy")
        rule.onNodeWithText(fix.commands.joinToString("\n")).assertExists()
        rule.onNodeWithText("Copy").performClick()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun anUnknownFixGoesBack() {
        var back = 0
        rule.show { AdbFixScreen("gone", SnackbarHostState(), onBack = { back++ }) }
        rule.waitForIdle()
        assertEquals(1, back)
    }
}
