// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** look.json (Settings › Display): wallpaper colours on by default, whatever the file holds. */
class LookSettingsTest {
    @Test fun theDefaultIsWallpaperColours() {
        assertTrue(LookSettings().wallpaperColors)
    }

    @Test fun bothChoicesRoundTrip() {
        for (w in listOf(true, false)) {
            val l = LookSettings(wallpaperColors = w)
            assertEquals(l, LookSettings.fromJson(l.toJson()))
        }
    }

    @Test fun theFileNamesItsVersionAndTheColours() {
        val o = JSONObject(LookSettings(wallpaperColors = false).toJson())
        assertEquals(1, o.getInt("v"))
        assertEquals(false, o.getBoolean("wallpaperColors"))
        // 1.0: the Classic look switch is gone, so it is no longer written.
        assertFalse(o.has("classic"))
    }

    @Test fun aFileFromBefore10WithTheClassicLookStillLoads() {
        // Its "classic" key is ignored, on or off; the colours are kept.
        assertEquals(LookSettings(wallpaperColors = false), LookSettings.fromJson("""{"v": 1, "wallpaperColors": false, "classic": true}"""))
        assertEquals(LookSettings(), LookSettings.fromJson("""{"v": 1, "wallpaperColors": true, "classic": false}"""))
        assertEquals(LookSettings(), LookSettings.fromJson("""{"classic": true}"""))
    }

    @Test fun missingEmptyOrGarbageGivesTheDefaults() {
        for (t in listOf(null, "", "   ", "not json", "[1, 2]", "{", "null", "{\"classic\": ")) assertEquals(t.toString(), LookSettings(), LookSettings.fromJson(t))
    }

    @Test fun unknownKeysAreIgnoredAndMissingKeysKeepTheirDefault() {
        assertEquals(LookSettings(wallpaperColors = false), LookSettings.fromJson("""{"v": 7, "wallpaperColors": false, "accent": "pink", "density": 2}"""))
        assertEquals(LookSettings(), LookSettings.fromJson("{}"))
    }

    @Test fun aWrongTypeKeepsTheDefault() {
        assertEquals(LookSettings(), LookSettings.fromJson("""{"wallpaperColors": "no"}"""))
        assertEquals(LookSettings(), LookSettings.fromJson("""{"wallpaperColors": null}"""))
    }
}
