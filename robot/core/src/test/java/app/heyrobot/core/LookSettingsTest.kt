package app.heyrobot.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** look.json (Settings › Display): wallpaper colours on and the standard look by default, whatever the file holds. */
class LookSettingsTest {
    @Test fun defaultsAreWallpaperColoursAndTheStandardLook() {
        val d = LookSettings()
        assertTrue(d.wallpaperColors)
        assertFalse(d.classic)
    }

    @Test fun everyCombinationRoundTrips() {
        for (w in listOf(true, false)) for (c in listOf(true, false)) {
            val l = LookSettings(wallpaperColors = w, classic = c)
            assertEquals(l, LookSettings.fromJson(l.toJson()))
        }
    }

    @Test fun theFileNamesItsVersionAndBothSwitches() {
        val o = JSONObject(LookSettings(wallpaperColors = false, classic = true).toJson())
        assertEquals(1, o.getInt("v"))
        assertEquals(false, o.getBoolean("wallpaperColors"))
        assertEquals(true, o.getBoolean("classic"))
    }

    @Test fun missingEmptyOrGarbageGivesTheDefaults() {
        for (t in listOf(null, "", "   ", "not json", "[1, 2]", "{", "null", "{\"classic\": ")) assertEquals(t.toString(), LookSettings(), LookSettings.fromJson(t))
    }

    @Test fun unknownKeysAreIgnoredAndMissingKeysKeepTheirDefault() {
        assertEquals(LookSettings(classic = true), LookSettings.fromJson("""{"v": 7, "classic": true, "accent": "pink", "density": 2}"""))
        assertEquals(LookSettings(wallpaperColors = false), LookSettings.fromJson("""{"wallpaperColors": false}"""))
        assertEquals(LookSettings(), LookSettings.fromJson("{}"))
    }

    @Test fun aWrongTypeKeepsTheDefault() {
        assertEquals(LookSettings(), LookSettings.fromJson("""{"wallpaperColors": "no", "classic": 1}"""))
        assertEquals(LookSettings(), LookSettings.fromJson("""{"wallpaperColors": null, "classic": "true"}"""))
    }
}
