// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.view.KeyEvent

/**
 * VOICE PROCESS. 1.1 (phone, 22 Oct, Android 17): the panel's window is a Dialog, and since Android 14 a Dialog cancels
 * itself on Escape's press (a hardware keyboard's Esc). That removed the panel's window while the system still counted the
 * session shown, and every later show (the assistant key, Talk to Robot, the corner swipe) was ignored until Robot was
 * force-stopped. RobotSession's window sees keys first (its key listener), and Escape now does what Back does, on its
 * release: the keyboard first, then the panel through the session's own hide.
 */
internal object PanelEscape {
    /**
     * Escape's part of the window's key listener: true when [e] was Escape's (always used: never the Dialog's cancel). On its
     * release, [hideKeyboard] while [keyboardShown], else [back].
     */
    fun onKey(keyCode: Int, e: KeyEvent, keyboardShown: () -> Boolean, hideKeyboard: () -> Unit, back: () -> Unit): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE) return false
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (keyboardShown()) hideKeyboard() else back()
        }
        return true
    }
}
