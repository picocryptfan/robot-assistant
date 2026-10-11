package app.heyrobot.core.termux

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Termux (B6) and the voice log's Termux codes (B9). */
class TermuxSettingsTest {
    @Test fun defaultsAreAllOff() {
        val d = TermuxSettings()
        assertFalse(d.enabled)
        assertFalse(d.readOnlyWithoutCard)
        assertFalse(d.helpers)
        assertFalse(d.keepInHistory)
        assertFalse(d.loadProfile)
        assertTrue(d.hideSecrets)
        assertEquals(CommandLevel.ASK, d.commands)
        assertEquals(SpokenYes.WITHOUT_WARNINGS, d.spokenYes)
        assertEquals(ApiCommands.WITH_CARD, d.api)
        assertEquals(60, d.limitSeconds)
        assertEquals(40, d.outputLines)
        assertEquals(1500, d.outputChars)
        assertEquals(ScriptLevel.ASK, d.script("anything"))
        assertTrue(d.lockScreenScripts.isEmpty())
        assertNull(d.trustedSigner)
        // Free-form commands have no "Just do it": Ask first or Off only.
        assertEquals(listOf("ASK", "OFF"), CommandLevel.entries.map { it.name })
        // Older files (no "termux" object), and anything unreadable, read as the defaults.
        assertEquals(d, TermuxSettings.fromJson(null))
        assertEquals(d, TermuxSettings.fromJson(JSONObject()))
        assertEquals(d, TermuxSettings.fromJson(JSONObject("{\"enabled\":\"maybe\",\"commands\":\"AUTO\",\"spokenYes\":\"ALWAYS\",\"api\":7}")))
    }

    @Test fun roundTrip() {
        val s = TermuxSettings(enabled = true, scripts = mapOf("backup" to ScriptLevel.AUTO, "sync" to ScriptLevel.OFF), commands = CommandLevel.OFF,
            readOnlyWithoutCard = true, spokenYes = SpokenYes.NEVER, limitSeconds = 120, outputLines = 20, outputChars = 900, hideSecrets = false,
            keepInHistory = true, loadProfile = true, api = ApiCommands.NOT_ALLOWED, helpers = true, lockScreenScripts = setOf("backup"),
            trustedSigner = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42")
        assertEquals(s, TermuxSettings.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test fun valuesAreKeptInRange() {
        val o = JSONObject("{\"limitSeconds\":1,\"outputLines\":100000,\"outputChars\":-5,\"scripts\":{\"../x\":\"AUTO\",\"ok\":\"AUTO\",\"bad\":\"NOPE\"}," +
            "\"lockScreenScripts\":[\"a/b\",\"ok\"],\"trustedSigner\":\"not hex\"}")
        val s = TermuxSettings.fromJson(o)
        assertEquals(10, s.limitSeconds)
        assertEquals(200, s.outputLines)
        assertEquals(200, s.outputChars)
        assertEquals(mapOf("ok" to ScriptLevel.AUTO), s.scripts)
        assertEquals(setOf("ok"), s.lockScreenScripts)
        assertNull(s.trustedSigner)
        assertEquals(600, TermuxSettings.limit(100_000))
        // A signer written with colons and lower case is kept normalized.
        val hex = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"
        assertEquals(hex, TermuxSettings.fromJson(JSONObject().put("trustedSigner", hex.lowercase().chunked(2).joinToString(":"))).trustedSigner)
    }

    @Test fun voiceLogCodes() {
        assertEquals(170, TermuxLog.FIRST_ID)
        assertEquals(179, TermuxLog.LAST_ID)
        assertTrue(listOf(TermuxLog.SENT, TermuxLog.DONE, TermuxLog.FAILED, TermuxLog.LATE).all { it in 170..179 })
        assertEquals(listOf(0, 1, 2, 4, 8, 16, 32, 64, 100, 100), listOf(0L, 1L, 1500L, 3000L, 8000L, 10_000L, 20_000L, 60_000L, 100_000L, 10_000_000L).map { TermuxLog.kbBucket(it) })
        assertEquals(9, TermuxLog.doneC(3000, truncated = true))
        assertEquals(mapOf(0 to TermuxExitBucket.OK, 1 to TermuxExitBucket.ERROR, 2 to TermuxExitBucket.ERROR, 124 to TermuxExitBucket.TIMEOUT,
            126 to TermuxExitBucket.NOT_RUN, 127 to TermuxExitBucket.NOT_RUN, 137 to TermuxExitBucket.STOPPED, 143 to TermuxExitBucket.STOPPED,
            130 to TermuxExitBucket.OTHER, 255 to TermuxExitBucket.OTHER, -1 to TermuxExitBucket.OTHER), listOf(0, 1, 2, 124, 126, 127, 137, 143, 130, 255, -1).associateWith { TermuxExitBucket.of(it) })
        // Stable, distinct ids for each code list.
        for (codes in listOf(TermuxRunKind.entries, TermuxExitBucket.entries)) {
            assertEquals(codes.size, codes.map { it.id }.toSet().size)
            assertTrue(codes.all { it.title.isNotBlank() })
        }
    }
}
