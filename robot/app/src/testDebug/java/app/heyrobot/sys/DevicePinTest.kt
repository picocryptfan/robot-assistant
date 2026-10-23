// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FX1 (files-1.4-review.md finding 1): the prompt travel mode's confirms show allows the phone's PIN, pattern or password
 * alone (never a fingerprint or a face), and its answers: cancelled or locked out is "not confirmed", anything else that
 * stops it is "couldn't open the screen". Whether a phone offers a biometric anyway is checked on the phone (F14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevicePinTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun thePromptAllowsTheDeviceCredentialAlone() {
        assertEquals(BiometricManager.Authenticators.DEVICE_CREDENTIAL, DevicePin.AUTHENTICATORS)
        val p = DevicePin.prompt(ctx, "Confirm it's you", "Turn travel mode off")
        assertEquals(BiometricManager.Authenticators.DEVICE_CREDENTIAL, p.allowedAuthenticators)
        assertEquals(0, p.allowedAuthenticators and (BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK))
        assertEquals("Confirm it's you", p.title.toString())
        assertEquals("Turn travel mode off", p.description.toString())
    }

    @Test fun answers() {
        for (c in listOf(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT)) assertEquals("$c", PinAnswer.NOT_CONFIRMED, DevicePin.answer(c))
        for (c in listOf(BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
            BiometricPrompt.BIOMETRIC_ERROR_VENDOR, BiometricPrompt.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)) assertEquals("$c", PinAnswer.NO_SCREEN, DevicePin.answer(c))
    }
}
