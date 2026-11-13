// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// earbud-headsup-b: a heads-up turned on before the calendar was allowed is armed when access arrives, and at app start.
class HeadsUpGrantTest {
    @Test fun calendarAccessArrivingArmsTheHeadsUp() {
        assertTrue(HeadsUpAlarms.calendarGranted(mapOf(Manifest.permission.READ_CALENDAR to true, Manifest.permission.WRITE_CALENDAR to false)))
        assertFalse(HeadsUpAlarms.calendarGranted(mapOf(Manifest.permission.READ_CALENDAR to false)))
        assertFalse(HeadsUpAlarms.calendarGranted(mapOf(Manifest.permission.READ_CONTACTS to true)))
        assertFalse(HeadsUpAlarms.calendarGranted(emptyMap()))
    }

    @Test fun mainActivityArmsOnTheResultAndAtStart() {
        val f = listOf("src/main/java/app/heyrobot/ui/MainActivity.kt", "app/src/main/java/app/heyrobot/ui/MainActivity.kt").map(::File).first { it.isFile }
        val src = f.readText()
        val people = src.substringAfter("val peoplePermissions = rememberLauncherForActivityResult").substringBefore("\n    }\n")
        assertTrue(people.contains("HeadsUpAlarms.calendarGranted(result)) HeadsUpAlarms.armSoon("))
        val create = src.substringAfter("override fun onCreate(").substringBefore("\n    }\n")
        assertTrue(create.contains("HeadsUpAlarms.arm(app)"))
    }
}
