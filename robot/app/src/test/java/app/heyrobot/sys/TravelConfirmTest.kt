// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FX1 (files-1.4-review.md finding 1): travel mode's confirms take the phone's PIN, pattern or password only. In the source
 * (comments out): KeyguardManager's confirm intent, which may take a fingerprint or a face, is used nowhere again; a
 * BiometricPrompt is built only in sys/DevicePin.kt, allowing DevicePin.AUTHENTICATORS (DEVICE_CREDENTIAL) alone; and
 * Settings › Files asks only through its store's one confirm, for Off, Unhide and Show alike.
 */
class TravelConfirmTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val modules = listOf("core", "brain", "voice", "helpers", "mt", "app")

    /** Every source of ours, comments out, by "module/path below app/heyrobot". */
    private val code: Map<String, String> by lazy {
        modules.flatMap { m ->
            val base = File(root, "$m/src/main/java/app/heyrobot")
            base.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .map { "$m/${it.relativeTo(base).path}" to Source.strip(it.readText(), keepStrings = true, kotlin = it.name.endsWith(".kt")) }.toList()
        }.toMap()
    }

    private fun users(pattern: Regex) = code.filterValues { pattern.containsMatchIn(it) }.keys.sorted()

    @Test fun theOldConfirmScreenIsUsedNowhere() {
        assertTrue(code.size > 300)
        assertEquals(emptyList<String>(), users(Regex("""createConfirmDeviceCredentialIntent|ACTION_CONFIRM_DEVICE_CREDENTIAL""")))
    }

    @Test fun onlyDevicePinBuildsAPromptAndItAllowsTheDeviceCredentialAlone() {
        assertEquals(listOf("app/sys/DevicePin.kt"), users(Regex("""\bBiometricPrompt\b|androidx\.biometric""")))
        val pin = code.getValue("app/sys/DevicePin.kt")
        assertEquals(1, Regex("""setAllowedAuthenticators\(""").findAll(pin).count())
        assertTrue(pin.contains("setAllowedAuthenticators(AUTHENTICATORS)"))
        assertTrue(Regex("""AUTHENTICATORS\s*=\s*BiometricManager\.Authenticators\.DEVICE_CREDENTIAL\s""").containsMatchIn(pin))
        // No biometric authenticator named anywhere, and no negative button (a credential prompt has none).
        assertEquals(emptyList<String>(), users(Regex("""BIOMETRIC_(STRONG|WEAK)\b|setNegativeButton|setDeviceCredentialAllowed""")))
    }

    @Test fun everyTravelConfirmGoesThroughTheStoresOneConfirm() {
        val page = code.getValue("app/ui/settings/FilesSettings.kt")
        // The phone's store hands the confirm to DevicePin; the page calls the store's confirm once, from confirm(ask).
        assertEquals(listOf("app/ui/settings/FilesSettings.kt"), users(Regex("""\bDevicePin\.ask\(""")))
        assertEquals(1, Regex("""\bstore\.confirm\(""").findAll(page).count())
        // Off, Unhide and Show all ask through confirm(TravelAsk.…); the PIN's yes is the only way to their saves.
        for (ask in listOf("Off", "Unhide(d.id)", "Show")) assertTrue(ask, page.contains("confirm(TravelAsk.$ask)"))
        assertEquals(2, Regex("""pin = true""").findAll(page.substringAfter("fun confirmed(ask: TravelAsk)").substringBefore("fun confirm(ask: TravelAsk)")).count())
    }
}
