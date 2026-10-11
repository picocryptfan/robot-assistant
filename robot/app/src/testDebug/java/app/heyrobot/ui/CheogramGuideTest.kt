package app.heyrobot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.testing.PHONE
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
 * Settings › People › "Learn names from Cheogram notifications" (0.4.6): why Android calls notification access a restricted
 * setting, the three steps with a button for each page, what Robot reads and keeps, that it never goes to Claude or any
 * online model by itself, and how to take it back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class CheogramGuideTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theStepsSayWhyAndOpenEachPage() {
        rule.show { Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) { NotificationAccessSteps() } }
        rule.onNodeWithText("restricted setting for apps installed from a file", substring = true).assertExists()
        rule.onNodeWithText("Allow restricted settings", substring = true).assertExists()
        // A button for each page: Notification access (steps 1 and 3) and App info (step 2).
        rule.onNodeWithText("Notification access").assert(hasClickAction())
        rule.onNodeWithText("App info").assert(hasClickAction())
        rule.onNodeWithText("Notification access again").assert(hasClickAction())
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // Each opens Android's page (Robolectric has none to show; nothing breaks).
        rule.onNodeWithText("App info").performClick()
        rule.onNodeWithText("Notification access").performClick()
    }

    @Test fun thePrivacyNoteSaysWhatIsReadAndWhereItGoes() {
        var opened = 0
        rule.show { Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) { CheogramPrivacy(canTakeBack = true) { opened++ } } }
        for (t in listOf("drops all but Cheogram's", "only the sender's name and address", "never looks at, keeps or logs what the message says",
            "group chats teach it nothing", "Names stay on this phone", "not to Claude, not to any other helper or online AI model",
            "To take it back")) {
            rule.onNodeWithText(t, substring = true).assertExists()
        }
        rule.onNodeWithText("Notification access").performClick()
        assertEquals(1, opened)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /**
     * 0.6.0: Claude is one helper among others. A conversation in which Robot looked someone up reaches any of them only
     * the ways it reaches Claude (Include, the personal-content switch) or through a helper you trust with personal content.
     */
    @Test fun thePrivacyNoteNamesEveryWayToAHelper() {
        rule.show { Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) { CheogramPrivacy(canTakeBack = false) {} } }
        for (t in listOf("share with Claude or another helper", "Include", "a helper you trust with personal content",
            "Send personal content to helpers automatically")) {
            rule.onNodeWithText(t, substring = true).assertExists()
        }
    }

    @Test fun noSecondButtonWhenTheRowAboveHasOne() {
        rule.show { CheogramPrivacy(canTakeBack = false) {} }
        rule.onAllNodesWithText("Notification access").assertCountEquals(0)
        rule.onNode(hasText("To take it back", substring = true)).assertExists()
    }
}
