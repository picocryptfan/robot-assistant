// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.assistant.Draft
import app.heyrobot.assistant.PanelActions
import app.heyrobot.assistant.PanelState
import app.heyrobot.assistant.Phase
import app.heyrobot.assistant.RecordingActions
import app.heyrobot.assistant.UiTurn
import app.heyrobot.assistant.showPanel
import app.heyrobot.core.Attachment
import app.heyrobot.core.TurnRole
import app.heyrobot.core.ask.AskShare
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ask Robot in the panel (everyday-set-plan.md §7.3, E7): the chip above the question names what was shared (never the
 * app), in one clean line; its buttons are your request in words; the question it went with is tagged "Shared", never
 * "Screen".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class AskChipTest {
    @get:Rule val rule = createComposeRule()

    private val passage = "The meeting‮ moved to 3 pm on Thursday, and the room is now B-204 on the second floor, past the lifts."

    /** The panel's actions, with Ask Robot's buttons recorded as the words they send. */
    private class Asked(val rec: RecordingActions = RecordingActions()) : PanelActions by rec {
        val asked = ArrayList<String>()
        override fun askShared(request: Phrase) { asked += request.render(Langs.EN) }
    }

    private fun draft(item: SharedItem) = PanelState("c1", draft = Draft(if (item.kind == SharedKind.PHOTO) Attachment.PHOTO else Attachment.SCREEN, item.thumb, shared = item),
        phase = Phase.LISTENING, sttAvailable = true)

    @Test fun theChipNamesWhatWasSharedAndItsButtonsAsk() {
        val a = Asked()
        rule.showPanel(draft(SharedItem(SharedKind.TEXT, passage)), a)
        val line = "Shared text: “" + AskShare.preview(passage) + "”"
        assertTrue(line, !line.contains('‮') && line.endsWith("…”"))
        rule.onNodeWithText(line).assertExists()
        rule.onNodeWithText("Goes with your next question").assertExists()
        for ((label, words) in listOf("Explain" to "Explain this.", "Translate" to "Translate this.", "Summarize" to "Summarize this.",
                "Draft a reply" to "Draft a reply to this.", "Remember" to "Remember this.")) {
            rule.onNodeWithText(label).performClick()
            assertEquals(words, a.asked.last())
        }
        rule.onNodeWithContentDescription("Remove what you shared").performClick()
        assertEquals("removeDraft", a.rec.calls.last())
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aVoiceMessageShowsItsLengthAndWhatWasLeftOut() {
        rule.showPanel(draft(SharedItem(SharedKind.VOICE, "I'm on my way.", seconds = 300, cut = true)), Asked())
        rule.onNodeWithText("Shared voice message (5:00)").assertExists()
        rule.onNodeWithText("Robot wrote down the first 3 minutes.").assertExists()
    }

    @Test fun noButtonsWhileATurnRuns() {
        rule.showPanel(draft(SharedItem(SharedKind.TEXT, "hi")).copy(phase = Phase.THINKING), Asked())
        rule.onNodeWithText("Shared text: “hi”").assertExists()
        rule.onNodeWithText("Explain").assertDoesNotExist()
    }

    @Test fun theQuestionIsTaggedShared() {
        rule.showPanel(PanelState("c1", turns = listOf(UiTurn("u1", TurnRole.USER, "explain this", Attachment.SCREEN, shared = true),
            UiTurn("u2", TurnRole.USER, "and this screen?", Attachment.SCREEN))), Asked())
        rule.onNodeWithText("Shared").assertExists()
        rule.onNodeWithText("Screen").assertExists()
    }

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun inFrench() {
        val a = Asked()
        rule.showPanel(draft(SharedItem(SharedKind.TEXT, "Le rendez-vous est déplacé.")), a)
        rule.onNodeWithText("Expliquer").assertExists()
        rule.onNodeWithText("Rédiger une réponse").assertExists()
        rule.onNodeWithText("Texte partagé : « Le rendez-vous est déplacé. »", substring = false).assertExists()
    }
}
