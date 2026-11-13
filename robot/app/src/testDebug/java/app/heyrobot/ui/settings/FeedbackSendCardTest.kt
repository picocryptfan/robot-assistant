// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackFacts
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.ReportShot
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.internal.TimeLimitedStatement

/**
 * RP5, the report card's Send (review-report-shot M1): with no report key, no Send and no screenshot part, only Share;
 * with a key, Send, the fingerprint note, and "Send as plain text" under the menu, which takes the words only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class FeedbackSendCardTest {
    @get:Rule(order = -2) val timeout = TestRule { base, _ -> TimeLimitedStatement(60_000, base) }
    @get:Rule val rule = createComposeRule()

    private val facts = FeedbackFacts("0.7.0 (70) · release build", "Pixel 9a · Tensor G4 · Android 16 (BP2A.250805.005) · GrapheneOS",
        "Fast · Gemma 4 E2B · GPU", "stock", "en-CA", "Build.SOC_MANUFACTURER “Google” · Build.SOC_MODEL “Tensor G4”")
    private val ui = FeedbackUi(facts, emptyList())
    private val taps = ArrayList<String>()
    private val actions = FeedbackActions(
        share = { taps += "share ${it.kind}"; true },
        copy = { taps += "copy ${it.kind}" },
        textIt = { true },
        github = { _, _ -> true },
        saveNumber = {},
        githubCard = { _, _ -> true },
        send = { o, done -> taps += "send ${o.kind} ${o.ref}"; done(true) },
    )

    private fun page(sealed: String?) {
        val shot = ReportShotHold().apply { offer(ReportShot(4, 4, IntArray(16) { -1 }), "Notes") }
        rule.show { FeedbackScreen(FeedbackArgs(FeedbackEntry.ABOUT, FeedbackKind.IDEA, null), ui, actions, onBack = {}, newRef = { "RFB-ABCD2345" }, shot = shot, sealed = sealed) }
    }

    private fun scrollTo(text: String) {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    @Test fun noKeyNoSendAndNoScreenshot() {
        page(sealed = null)
        scrollTo("Share")
        rule.onNodeWithText("Share").assertExists()
        rule.onNode(hasText("Send") and hasClickAction()).assertDoesNotExist()
        rule.onNodeWithText("Sealed so only Robot", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Add a screenshot of Notes").assertDoesNotExist()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Send as plain text").assertDoesNotExist()
    }

    @Test fun aKeyGivesSendItsNoteAndPlainTextInTheMenu() {
        page(sealed = "554c 3c71 13b0 83ca fdec")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("A dark mode, please.")
        scrollTo("Add a screenshot of Notes")
        rule.onNodeWithText("Add a screenshot of Notes").assertExists()
        scrollTo("Sealed so only Robot")
        rule.onNodeWithText("Sealed so only Robot's developer can open it (key 554c 3c71 13b0 83ca fdec)", substring = true).assertExists()
        rule.onNodeWithText("Only Send takes the screenshot.", substring = true).assertExists()
        rule.onNodeWithText("Share").assertDoesNotExist()
        rule.onNode(hasText("Send") and hasClickAction()).performClick()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("The screenshot doesn't go this way.").assertExists()
        rule.onNodeWithText("Send as plain text").performClick()
        assertEquals(listOf("send IDEA RFB-ABCD2345", "share IDEA"), taps)
    }
}
