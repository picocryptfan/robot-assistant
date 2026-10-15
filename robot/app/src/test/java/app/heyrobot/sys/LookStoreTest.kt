// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.LookSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** look.json on disk: written whole (never half), read back as written, and the defaults when it is missing or damaged. */
class LookStoreTest {
    private val dir: File = Files.createTempDirectory("look").toFile()
    private val f = File(dir, LookSettings.FILE)

    @Test fun aMissingFileGivesTheDefaults() {
        assertFalse(f.exists())
        assertEquals(LookSettings(), LookStore.read(f))
    }

    @Test fun whatIsWrittenIsReadBack() {
        val l = LookSettings(wallpaperColors = false)
        LookStore.write(f, l)
        assertEquals(l, LookStore.read(f))
        LookStore.write(f, LookSettings())
        assertEquals(LookSettings(), LookStore.read(f))
        // Through a temporary file renamed over it: nothing else is left in the folder.
        assertEquals(listOf(LookSettings.FILE), dir.list()!!.toList())
    }

    @Test fun aDamagedFileGivesTheDefaultsAndIsReplacedByTheNextSave() {
        f.writeText("{\"wallpaperColors\": fal")
        assertEquals(LookSettings(), LookStore.read(f))
        LookStore.write(f, LookSettings(wallpaperColors = false))
        assertFalse(LookStore.read(f).wallpaperColors)
    }

    @Test fun aFileFromBefore10WithTheClassicLookLoadsAndLosesTheKeyOnTheNextSave() {
        // 0.9's look.json, with the Classic look switch on and Robot's own colours.
        f.writeText("""{"v": 1, "wallpaperColors": false, "classic": true}""")
        val l = LookStore.read(f)
        assertEquals(LookSettings(wallpaperColors = false), l)
        LookStore.write(f, l)
        assertFalse(f.readText().contains("classic"))
        assertEquals(l, LookStore.read(f))
    }

    @Test fun theFileLivesBesideSettingsJson() {
        val src = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot/sys/LookStore.kt").readText()
        assertTrue(src.contains("File(Paths.dir(ctx), LookSettings.FILE)"))
        assertEquals("look.json", LookSettings.FILE)
    }
}
