// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.window.DialogProperties
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
