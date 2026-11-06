// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.heyrobot.ui.testing.ActionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notes hold what you tell Robot: the list, a note and its editor keep the window out of screenshots, screen recordings,
 * casting and Recents' snapshot while they show (SecureWindow, as Settings › Memory does), and only then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotesSecureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test fun theNotesListIsSecureWhileItShows() {
        var shown by mutableStateOf(true)
        val a = ActionLog().actions
        rule.setContent { if (shown) NotesScreen(emptyList(), a, Modifier) }
        rule.waitForIdle()
        assertNotEquals(0, secure())
        shown = false
        rule.waitForIdle()
        assertEquals(0, secure())
    }

    @Test fun aNoteAndItsEditorAreSecureWhileTheyShow() {
        var shown by mutableStateOf(true)
        val a = ActionLog().actions
        rule.setContent { if (shown) NoteScreen(null, 0, a, SnackbarHostState(), {}, {}) }
        rule.waitForIdle()
        assertNotEquals(0, secure())
        shown = false
        rule.waitForIdle()
        assertEquals(0, secure())
    }
}
