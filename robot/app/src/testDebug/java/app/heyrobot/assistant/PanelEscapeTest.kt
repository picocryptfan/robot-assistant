// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.Activity
import android.app.Dialog
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.1 (phone, 22 Oct, Android 17): Escape on the panel removed its window without hiding the session, and Robot's panel
 * never came back until a force stop. With RobotSession's key listener (PanelEscape) on a Dialog, as the session's window
 * is one, Escape is Back: the keyboard first, then the session's hide; the Dialog never cancels itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelEscapeTest {
    private fun press(d: Dialog, code: Int) {
        d.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        d.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Test fun escapeIsBackNeverACancel() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val d = Dialog(activity)
        var keyboard = true
        var keyboardHidden = 0
        var back = 0
        var cancelled = 0
        d.setOnKeyListener { _, k, e -> PanelEscape.onKey(k, e, { keyboard }, { keyboardHidden++; keyboard = false }, { back++ }) }
        d.setOnCancelListener { cancelled++ }
        d.show()
        // The keyboard first, as Back's first press.
        press(d, KeyEvent.KEYCODE_ESCAPE)
        assertEquals(1, keyboardHidden)
        assertEquals(0, back)
        assertTrue(d.isShowing)
        // Then the panel, through the session's own Back (its hide), never the Dialog's cancel.
        press(d, KeyEvent.KEYCODE_ESCAPE)
        assertEquals(1, back)
        assertEquals(0, cancelled)
        assertTrue(d.isShowing)
        // Only on the release, and not for a press the system cancelled.
        d.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        assertEquals(1, back)
        d.dispatchKeyEvent(KeyEvent.changeFlags(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE), KeyEvent.FLAG_CANCELED))
        assertEquals(1, back)
        assertEquals(0, cancelled)
    }

    @Test fun otherKeysAreNotEscapes() {
        for (k in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            assertFalse(PanelEscape.onKey(k, KeyEvent(KeyEvent.ACTION_UP, k), { false }, { error("keyboard") }, { error("back") }))
        }
    }
}
