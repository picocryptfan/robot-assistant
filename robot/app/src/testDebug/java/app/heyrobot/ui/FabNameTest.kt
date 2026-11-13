// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.heyrobot.ui.testing.ActionLog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An extended floating button keeps its name for TalkBack and the runners' dumps. Material clears the semantics of the
 * button's text (the button can collapse to its icon), so the icon carries the name; with a null icon description the
 * button had none (phone-seal-5: Notes' New note was a nameless "NAF" button).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FabNameTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun button(name: String): SemanticsMatcher = hasClickAction() and (hasText(name) or hasContentDescription(name))

    private fun notesNewNote(name: String) {
        val a = ActionLog().actions
        rule.setContent { NotesScreen(emptyList(), a, Modifier) }
        rule.onNode(button(name)).assertExists()
    }

    private fun transcriptsTranscribe(name: String) {
        rule.setContent { TranscriptsScreen(SnackbarHostState(), { _, _ -> }, {}) }
        rule.onNode(button(name)).assertExists()
    }

    @Test fun notesNewNoteHasItsName() = notesNewNote("New note")

    @Config(qualifiers = "fr-rCA")
    @Test fun notesNewNoteHasItsNameInFrench() = notesNewNote("Nouvelle note")

    @Test fun transcriptsTranscribeHasItsName() = transcriptsTranscribe("Transcribe")

    @Config(qualifiers = "fr-rCA")
    @Test fun transcriptsTranscribeHasItsNameInFrench() = transcriptsTranscribe("Transcrire")
}
