// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import app.heyrobot.core.calls.CallerNameSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// hide-caller-name-1.4: caller name waits for 1.5 (review-caller-name-e). Its one switch is CallerNameSettings.AVAILABLE;
// while it is false nobody can turn caller name on or reach it. 1.5 flips the switch and these tests with it.
class CallerNameHiddenTest {
    private fun code(f: File) = Source.strip(f.readText(), keepStrings = false)
    private val main = File("src/main/java").walk().filter { it.isFile && it.extension == "kt" }.toList()

    /** No Settings row: the switch is off, every place that shows the row is behind it, and the row itself returns early. */
    @Test fun settingsRowStaysHidden() {
        assertFalse("caller name is hidden in 1.4", CallerNameSettings.AVAILABLE)
        val calls = main.filter { it.name != "CallerNameSettings.kt" }.flatMap { f ->
            code(f).lines().filter { "CallerNamePreference(" in it }.map { f.name to it }
        }
        assertTrue("the row is still placed in Settings", calls.isNotEmpty())
        calls.forEach { (f, line) -> assertTrue("$f shows the row without the switch: ${line.trim()}", "if (CallerNameSettings.AVAILABLE)" in line) }
        val row = code(File("src/main/java/app/heyrobot/ui/settings/CallerNameSettings.kt"))
        val body = row.substringAfter("fun CallerNamePreference() {").trimStart()
        assertTrue("CallerNamePreference must return first while hidden", body.startsWith("if (!CallerNameSettings.AVAILABLE) return"))
    }

    /** No role request anywhere but the hidden row. */
    @Test fun noOtherRoleRequest() {
        val askers = main.filter { "ROLE_CALL_SCREENING" in code(it) }.map { it.name }
        assertEquals(listOf("CallerNameSettings.kt"), askers)
    }

    /** The screening service is off in the manifest exactly while the switch is. */
    @Test fun serviceDisabledWithTheSwitch() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".calls.CallerNameService\"").substringBefore(">")
        assertTrue(service.contains("android:enabled=\"${CallerNameSettings.AVAILABLE}\""))
    }

    /** A caller_name.json saved with on = true (a test build that had the switch) reads as off, in the store the service reads. */
    @Test fun savedOnReadsAsOff() {
        assertFalse(CallerNameSettings.read(CallerNameSettings(on = true).toJson()).on)
        assertFalse(CallerNameSettings.read("""{"v":1,"on":true}""").on)
        assertFalse(CallerNameSettings.read(null).on)
        val store = code(File("src/main/java/app/heyrobot/calls/CallerNameService.kt")).substringAfter("object CallerNameStore").substringBefore("object CallerLookup")
        assertTrue(store.contains("CallerNameSettings.read("))
        assertFalse(store.contains("fromJson("))
    }
}
