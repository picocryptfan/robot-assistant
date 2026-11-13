// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.ReportPart
import app.heyrobot.core.feedback.SentReport
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.internal.TimeLimitedStatement
import java.time.ZoneId

/** RP5, Reports you sent: each row's reference, when, kind and parts, the empty page, Delete all asking first, the ways in. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SentReportsScreenTest {
    @get:Rule(order = -2) val timeout = TestRule { base, _ -> TimeLimitedStatement(60_000, base) }
    @get:Rule val rule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val two = listOf(
        SentReport("RFB-ABCD2345", 1_794_225_900_000L, FeedbackKind.BUG, setOf(ReportPart.SAID, ReportPart.SCREENSHOT, ReportPart.VOICE_LOG)),
        SentReport("RFB-WXYZ7777", 1_794_139_500_000L, FeedbackKind.IDEA, emptySet()),
    )
    private var deletes = 0

    private fun page(list: List<SentReport>?) = rule.show { SentReportsScreen(list, onDeleteAll = { deletes++ }, onBack = {}, zone = utc) }

    @Test fun eachRowShowsItsReferenceAndWhatWent() {
        page(two)
        rule.onNodeWithText("RFB-ABCD2345").assertExists()
        rule.onNodeWithText("RFB-WXYZ7777").assertExists()
        rule.onNodeWithText("What you said", substring = true).assertExists()
        rule.onNodeWithText("Screenshot", substring = true).assertExists()
        rule.onNodeWithText("Your words only", substring = true).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun anEmptyListSaysSoAndHasNoDeleteAll() {
        page(emptyList())
        rule.onNodeWithText("No reports sent yet", substring = true).assertExists()
        rule.onNodeWithText("Delete all").assertDoesNotExist()
    }

    @Test fun deleteAllAsksFirst() {
        page(two)
        rule.onNodeWithText("Delete all").performScrollTo().performClick()
        rule.onNodeWithText("Delete the list?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, deletes)
        rule.onNodeWithText("Delete all").performClick()
        rule.onNode(hasText("Delete all") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, deletes)
    }

    @Test fun aboutOpensIt() {
        val log = ActionLog()
        rule.show { AboutSettingsScreen(log.actions, onBack = {}) }
        rule.onRow("Reports you sent").performScrollTo().performClick()
        assertEquals(listOf("openPage ${Page.Settings(SettingsRoutes.REPORTS_SENT)}"), log)
    }

    @Test fun theCardsMenuOpensIt() {
        var opened = 0
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.ABOUT, FeedbackKind.BUG, null), null, FeedbackActions(sentList = { opened++ }), onBack = {}) }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Reports you sent").performClick()
        assertEquals(1, opened)
    }

    @Test fun screenshot() {
        page(two)
        rule.shot("reports_sent_light")
    }
}
