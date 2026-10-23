// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import app.heyrobot.core.RLog

// MAIN PROCESS. FX1 (files-1.4-review.md finding 1): travel mode's confirms (turning it off, unhiding a drawer while it is
// on, Show hidden drawers) take the phone's own PIN, pattern or password, and nothing else. KeyguardManager's
// createConfirmDeviceCredentialIntent may take a fingerprint or a face on Android 10 and later, so someone holding the
// phone up to your face could show your hidden drawers; Android's BiometricPrompt with DEVICE_CREDENTIAL as its only
// authenticator never offers a biometric. Every confirm goes through [ask]; TravelConfirmTest's scan keeps it the only
// place a prompt is built. Android checks USE_BIOMETRIC for every BiometricPrompt, even this one (a normal permission,
// granted at install; no sensor is used).

/** What the phone's PIN screen gave: your PIN, pattern or password; cancelled or wrong; or no screen came. */
enum class PinAnswer { CONFIRMED, NOT_CONFIRMED, NO_SCREEN }

object DevicePin {
    /** The one authenticator asked for: the phone's PIN, pattern or password. */
    const val AUTHENTICATORS = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** The prompt [ask] shows ([ctx]: the activity in front), titled [title] with [text] under it. */
    fun prompt(ctx: Context, title: String, text: String): BiometricPrompt =
        BiometricPrompt.Builder(ctx).setTitle(title).setDescription(text).setAllowedAuthenticators(AUTHENTICATORS).build()

    /**
     * Asks for the phone's PIN, pattern or password; [done] gets the answer once, on the main thread. A wrong try stays on
     * Android's own screen; cancelling it (or too many wrong tries) is [PinAnswer.NOT_CONFIRMED]; a screen that couldn't
     * come (no screen lock after all, or Android refused it) is [PinAnswer.NO_SCREEN]. Nothing changes without CONFIRMED.
     */
    fun ask(ctx: Context, title: String, text: String, done: (PinAnswer) -> Unit) {
        val p = try { prompt(ctx, title, text) } catch (t: Exception) { RLog.w("pin_prompt", t); return done(PinAnswer.NO_SCREEN) }
        try {
            p.authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(PinAnswer.CONFIRMED)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(answer(errorCode))
            })
        } catch (t: Exception) {
            RLog.w("pin_prompt", t)
            done(PinAnswer.NO_SCREEN)
        }
    }

    /** An error [code] from the prompt, as an answer: you (or Android) cancelled it, or it couldn't be shown. */
    fun answer(code: Int): PinAnswer = when (code) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> PinAnswer.NOT_CONFIRMED
        else -> PinAnswer.NO_SCREEN
    }
}
