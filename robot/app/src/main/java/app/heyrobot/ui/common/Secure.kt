// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.heyrobot.core.everyday.SheetMic
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog as Material3AlertDialog
import androidx.compose.material3.DropdownMenu as Material3DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy

// 1.0 audit (secrets-memory-private-items-not-secure): a screen that shows private memory items (passwords, PINs, card
// numbers) is kept out of screenshots, screen recordings, casting and Recents' snapshot, and its text fields ask the
// keyboard not to learn from what is typed.

/** FLAG_SECURE on the activity's window while this is in the composition (counted, so nested screens don't clear it early). */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity) {
        val w = activity?.window
        if (w != null) {
            if (secureCount++ == 0) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (w != null && --secureCount == 0) w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private var secureCount = 0

/**
 * LS13: this window refuses a tap while another app's window covers it (a tap to add or to pick must be yours), and Phone
 * lock still hears it come to the front. Since Android 14 a view that filters obscured touches counts as accessibility
 * data-sensitive, and Android then sends its window's events only to accessibility tools; Phone lock's service isn't one
 * (on purpose), so the widget's picker opened during a lock was never covered (LT8 step 4 on the Pixel 8). The window says
 * it isn't sensitive (its event carries its class, nothing it shows), and what's in it still is.
 */
fun Window.filterObscuredTouches() {
    decorView.filterTouchesWhenObscured = true
    // The content first (LS13 low 3): without it the decor stays sensitive, Android 14's default for a view that filters
    // touches, so nothing the window shows ever reaches an accessibility service unmarked. Fail closed.
    val content = decorView.findViewById<View>(android.R.id.content) ?: return
    content.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_NO)
}

/**
 * M1 (review-panel-covered): [filterObscuredTouches] refuses only a touch on a spot another app's window covers, so a window
 * that hides a card's words but leaves its button clear still gets the tap through, marked FLAG_WINDOW_IS_PARTIALLY_OBSCURED.
 * The activity feeds each touch here before its views see it ([see], from dispatchTouchEvent); a button that sends or says
 * yes asks [now] and refuses while this gesture was so marked, with a line in the screen's own words (never a Toast: an
 * app's toast is another app's window and may set the flag). Cleared on the next touch down.
 */
class CoveredTouches {
    var now = false
        private set

    fun see(action: Int, flags: Int) {
        if (action == MotionEvent.ACTION_DOWN) now = false
        if (SheetMic.covered(flags)) now = true
    }
}

/**
 * fb13: Robot's every AlertDialog: as material3's, and it refuses a tap while another app's window covers it. A dialog has a
 * window of its own, so its activity's [filterObscuredTouches] doesn't reach it: "Delete all conversations?" could be tapped
 * through an overlay. Here, in one place; DialogTouchesTest refuses material3's own anywhere else. The slot that's always
 * there carries the effect, since only what's inside the dialog sees its window.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) = Material3AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = { RefuseCoveredTaps(); confirmButton() },
    dismissButton = dismissButton,
    icon = icon,
    title = title,
    text = text,
    properties = properties,
)

/** The dialog this runs in refuses covered taps. Should its window not be found: the root view still refuses them. Fails closed. */
@Composable
private fun RefuseCoveredTaps() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        if (window != null) window.filterObscuredTouches() else view.rootView.filterTouchesWhenObscured = true
        onDispose {}
    }
}

/**
 * F115: Robot's every menu: as material3's, and it refuses a tap while another app's window covers it. A menu is a popup in a
 * window of its own, so the activity's [filterObscuredTouches] doesn't reach it, and some of its rows act at once (a list
 * item's Delete). Here, in one place; DialogTouchesTest refuses material3's own anywhere else.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) = Material3DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
    RefuseCoveredPopupTaps(); content()
}

/** The popup this runs in refuses covered taps: its root view is the top of its window (a popup has no Window to ask). */
@Composable
private fun RefuseCoveredPopupTaps() {
    val view = LocalView.current
    DisposableEffect(view) {
        val top = view.rootView
        top.filterTouchesWhenObscured = true
        onDispose {}
    }
}

/** A dialog over a secure screen: secure itself, whatever its parent says. */
val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

/**
 * The text fields in [content] (dialogs included) tell the keyboard not to learn what is typed
 * (EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), as a password field would, while still showing the words.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoImeLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val ic = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = noLearning(outAttributes.imeOptions)
                    return ic
                }
            })
        },
        content = content,
    )
}

/** The IME options with no personalized learning. */
fun noLearning(imeOptions: Int): Int = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
