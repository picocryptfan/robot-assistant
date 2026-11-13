package app.heyrobot.core.backup

import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.output.HeadsetFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup made before 7 Nov 2026 holds headset.json's "buttonsWhileTalking" (false, never shown). The new key
 * "earbudButtons" (on) replaces it, so that stored false is never read: the backup restores, its other rows as they were,
 * and the earbud buttons are on, as on a new install.
 */
class RestoreOldHeadsetKeyTest {
    private fun oldHeadsetJson(): String {
        val o = JSONObject(HeadsetFile(HeadsetSettings(followUpSeconds = 9, answerVoiceButton = true, speakInHeadset = false)).encode())
        val h = o.getJSONObject("headset")
        h.remove("earbudButtons")
        h.put("buttonsWhileTalking", false)
        return o.toString()
    }

    @Test fun aBackupHoldingTheOldKeyRestores() {
        val text = oldHeadsetJson()
        assertTrue(text.contains("\"buttonsWhileTalking\":false"))
        assertFalse(text.contains("earbudButtons"))
        val file = HeadsetFile.decode(text)
        assertEquals(9, file.settings.followUpSeconds)
        assertTrue(file.settings.answerVoiceButton)
        assertFalse(file.settings.speakInHeadset)
        assertTrue(file.settings.earbudButtons)
        for (mine in listOf(true, false)) {
            val g = RestoreSafety.headset(HeadsetFile(), file, mine)
            assertEquals(file.settings, g.value.settings)
            assertTrue(g.value.settings.earbudButtons)
        }
        // Written back, the file holds only the new key.
        val again = JSONObject(file.encode()).getJSONObject("headset")
        assertFalse(again.has("buttonsWhileTalking"))
        assertTrue(again.getBoolean("earbudButtons"))
    }
}
