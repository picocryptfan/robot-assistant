package app.heyrobot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.AdbCheck
import app.heyrobot.core.AdbFixes
import app.heyrobot.core.ShizukuOutcome
import app.heyrobot.core.ShizukuPath
import app.heyrobot.core.ShizukuRefusal
import app.heyrobot.core.ShizukuResult
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
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

/**
 * Settings › Advanced › ADB fixes: a status row per fix, and a fix's page with its commands and Copy. 1.0: each fix also
 * without a computer (rish in Termux, with Copy), the list's Shizuku steps, and "Run with Shizuku through Termux" only
 * when Termux is set up and trusted, sent only by its card's Run.
 */
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
        rule.scrollToText("With a computer (adb)")
        rule.onNodeWithText(fix.commands.joinToString("\n")).assertExists()
        rule.onNodeWithContentDescription("Copy the adb commands").performClick()
        // The same command without a computer: rish -c '…' in Termux, with its own Copy.
        rule.scrollToText("Without a computer (Shizuku)")
        rule.scrollToText(ShizukuPath.rishLines(fix)!!.joinToString("\n"))
        rule.onNodeWithContentDescription("Copy the commands for rish").performClick()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun theListSaysHowToSetUpShizukuAndRecommendsTheRepairFixFirst() {
        rule.show { AdbFixesScreen(log.actions, SnackbarHostState(), onBack = {}) }
        rule.scrollToText("Without a computer: Shizuku")
        rule.scrollToText("Do “Let Robot repair that itself” first", substring = true)
        for (step in listOf("1. Install Shizuku", "2. Start it with Wireless debugging", "3. Give rish to Termux", "4. Allow Termux, and only Termux", "5. Run each fix")) {
            rule.scrollToText(step)
        }
        rule.scrollToText(ShizukuPath.TERMUX_LINE)
        rule.scrollToText("authorize only Termux, and stop Shizuku when you're done", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    private val repair = AdbFixes.ALL.single { it.id == "repair" }

    @Test fun runWithShizukuIsSentOnlyByItsCardsRun() {
        val sent = ArrayList<String>()
        rule.show { AdbFixPage(repair, emptyMap(), ShizukuUi(offered = true), SnackbarHostState(), onBack = {}) { sent += it } }
        rule.scrollToText("Run with Shizuku through Termux")
        rule.onNodeWithContentDescription("Run this fix with Shizuku through Termux").performClick()
        // The card shows exactly the fixed command; Cancel sends nothing.
        rule.inDialog("Robot sends this command to Termux", substring = true).assertExists()
        rule.inDialog(ShizukuPath.command("repair")!!, substring = true).assertExists()
        rule.inDialog("Cancel").performClick()
        assertEquals(emptyList<String>(), sent)
        rule.onNodeWithContentDescription("Run this fix with Shizuku through Termux").performClick()
        rule.inDialog("Run").performClick()
        assertEquals(listOf("repair"), sent)
    }

    @Test fun notOfferedWithoutATrustedTermuxAndTheResultIsShown() {
        rule.show {
            AdbFixPage(repair, mapOf(AdbCheck.SECURE_SETTINGS_GRANTED to false), ShizukuUi(offered = false,
                result = ShizukuResult("repair", 1, ShizukuOutcome.REFUSED, refusal = ShizukuRefusal.LOCKED)), SnackbarHostState(), onBack = {}) {}
        }
        rule.scrollToText("Set up Termux in Settings › Termux first", substring = true)
        rule.onNodeWithContentDescription("Run this fix with Shizuku through Termux").assertDoesNotExist()
        rule.scrollToText("Not sent: unlock your phone first.")
    }

    @Test fun anUnknownFixGoesBack() {
        var back = 0
        rule.show { AdbFixScreen("gone", SnackbarHostState(), onBack = { back++ }) }
        rule.waitForIdle()
        assertEquals(1, back)
    }
}
