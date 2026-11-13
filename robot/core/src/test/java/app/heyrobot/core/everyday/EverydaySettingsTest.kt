// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.everyday

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * everyday-set-plan.md §2.4 and D21: everyday_settings.json v1 (the plan's defaults, every value round trip, a later key
 * kept, a wrong type or a value out of range Damaged, a newer version TooNew and never written over).
 */
class EverydaySettingsTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val app = MusicApp("org.example.music", sha, "Music")

    @Test fun noFileIsThePlansDefaults() {
        val f = EverydaySettingsCodec.decode(null)
        assertEquals(EverydaySettingsFile.Ok(EverydaySettings()), f)
        val d = f.settings
        // §2.4: every switch on, no music app until you pick one (Q1), the record kept 30 days (Q3).
        assertTrue(d.music && d.listen && d.voices && d.askRobot && d.online)
        assertNull(d.musicApp)
        assertEquals(30, d.onlineDays)
        assertEquals("everyday_settings.json", EverydaySettingsCodec.FILE)
    }

    @Test fun pointAndReadsTilesAreOffByDefaultAndWrittenOnlyWhenOn() {
        assertEquals(false, EverydaySettings().readTiles)
        assertEquals(false, JSONObject(EverydaySettingsCodec.encode(EverydaySettings())).has("readTiles"))
        val on = EverydaySettings(readTiles = true)
        assertEquals(EverydaySettingsFile.Ok(on), EverydaySettingsCodec.decode(EverydaySettingsCodec.encode(on)))
        assertEquals(EverydaySettingsFile.Damaged, EverydaySettingsCodec.decode("""{"v":1,"readTiles":"yes"}"""))
    }

    @Test fun everyValueRoundTrips() {
        val s = EverydaySettings(music = false, musicApp = app, listen = false, voices = false, askRobot = false, online = false, onlineDays = 7)
        assertEquals(EverydaySettingsFile.Ok(s), EverydaySettingsCodec.decode(EverydaySettingsCodec.encode(s)))
        val o = JSONObject(EverydaySettingsCodec.encode(EverydaySettings()))
        assertEquals(1, o.getInt("v"))
        // No music app: the key isn't written.
        assertEquals(setOf("v", "music", "listen", "voices", "askRobot", "online", "onlineDays"), o.keys().asSequence().toSet())
        val withApp = JSONObject(EverydaySettingsCodec.encode(EverydaySettings(musicApp = app))).getJSONObject("musicApp")
        assertEquals(setOf("package", "sha256", "label"), withApp.keys().asSequence().toSet())
        assertEquals("org.example.music", withApp.getString("package"))
    }

    @Test fun aMissingKeyIsItsDefault() {
        assertEquals(EverydaySettingsFile.Ok(EverydaySettings(listen = false)), EverydaySettingsCodec.decode("""{"v":1,"listen":false}"""))
        assertEquals(EverydaySettingsFile.Ok(EverydaySettings()), EverydaySettingsCodec.decode("""{"v":1,"musicApp":null}"""))
    }

    @Test fun aKeyThisVersionDoesntKnowIsKeptAndWrittenBack() {
        val f = EverydaySettingsCodec.decode("""{"v":1,"music":false,"podcasts":["a"],"later":7}""") as EverydaySettingsFile.Ok
        assertEquals(EverydaySettings(music = false), f.read)
        assertEquals(setOf("podcasts", "later"), f.extra.keys)
        val again = JSONObject(EverydaySettingsCodec.encode(f.read.copy(online = false), f.extra))
        assertEquals("a", again.getJSONArray("podcasts").getString(0))
        assertEquals(7, again.getInt("later"))
        assertFalse(again.getBoolean("online"))
        // A known key never comes back from the extras.
        assertEquals(true, JSONObject(EverydaySettingsCodec.encode(EverydaySettings(), mapOf("music" to false, "v" to 9))).getBoolean("music"))
        assertEquals(1, JSONObject(EverydaySettingsCodec.encode(EverydaySettings(), mapOf("v" to 9))).getInt("v"))
    }

    @Test fun aWrongTypeOrValueIsDamaged() {
        for (bad in listOf("""{"v":1,"music":"yes"}""", """{"v":1,"online":1}""", """{"v":1,"onlineDays":0}""", """{"v":1,"onlineDays":91}""",
            """{"v":1,"onlineDays":7.5}""", """{"v":1,"onlineDays":"30"}""", """{"v":0}""", """{"music":true}""", """{"v":"1"}""", "not json", "[]",
            // Your music app: exactly a package, a SHA-256 in lower-case hex and a label of one line.
            """{"v":1,"musicApp":"org.example.music"}""",
            """{"v":1,"musicApp":{"package":"org.example.music","sha256":"$sha"}}""",
            """{"v":1,"musicApp":{"package":"org.example.music","sha256":"$sha","label":"Music","extra":1}}""",
            """{"v":1,"musicApp":{"package":"music","sha256":"$sha","label":"Music"}}""",
            """{"v":1,"musicApp":{"package":"org.example.music","sha256":"${sha.uppercase()}","label":"Music"}}""",
            """{"v":1,"musicApp":{"package":"org.example.music","sha256":"$sha","label":""}}""",
            """{"v":1,"musicApp":{"package":"org.example.music","sha256":"$sha","label":"Mu\nsic"}}""",
            """{"v":1,"musicApp":{"package":"org.example.music","sha256":"$sha","label":"${"m".repeat(101)}"}}""")) {
            assertEquals(bad, EverydaySettingsFile.Damaged, EverydaySettingsCodec.decode(bad))
        }
        assertEquals(EverydaySettings(), EverydaySettingsFile.Damaged.settings)
    }

    @Test fun aNewerFileIsTooNewAndNeverWrittenOver() {
        val newer = """{"v":2,"music":false,"somethingNew":{}}"""
        assertEquals(EverydaySettingsFile.TooNew(2), EverydaySettingsCodec.decode(newer))
        assertEquals(EverydaySettings(), EverydaySettingsCodec.decode(newer).settings)
        assertNull(EverydaySettingsWrite.update(newer) { it.copy(online = false) })
    }

    /** A save changes only what it changes, over the file as it is now; a damaged file gives way to a new one. */
    @Test fun anUpdateKeepsTheRestOfTheFile() {
        val raw = EverydaySettingsCodec.encode(EverydaySettings(musicApp = app), mapOf("later" to 1))
        val next = EverydaySettingsCodec.decode(EverydaySettingsWrite.update(raw) { it.copy(listen = false) }) as EverydaySettingsFile.Ok
        assertEquals(EverydaySettings(musicApp = app, listen = false), next.read)
        assertEquals(mapOf("later" to 1), next.extra)
        assertEquals(EverydaySettingsFile.Ok(EverydaySettings(voices = false)),
            EverydaySettingsCodec.decode(EverydaySettingsWrite.update("{broken") { it.copy(voices = false) }))
        assertEquals(EverydaySettingsFile.Ok(EverydaySettings(askRobot = false)),
            EverydaySettingsCodec.decode(EverydaySettingsWrite.update(null) { it.copy(askRobot = false) }))
    }
}
