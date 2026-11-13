// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.headset.PanelFollowUp.Heard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel's follow-up window (idea 7, keep-listening-plan.md). */
class PanelFollowUpTest {
    private val ok = PanelFollowUp.After(on = true, spoken = true, stopped = false, shown = true, handsFree = false, waitsForWords = false)

    @Test fun opensAfterASpokenAnswerOnlyWithTheSettingOn() {
        assertTrue(PanelFollowUp.opens(ok))
        assertFalse(PanelFollowUp.opens(ok.copy(on = false)))
        assertFalse("a typed question", PanelFollowUp.opens(ok.copy(spoken = false)))
        assertFalse("a stopped answer", PanelFollowUp.opens(ok.copy(stopped = true)))
        assertFalse(PanelFollowUp.opens(ok.copy(shown = false)))
        assertFalse("a headset session has its own", PanelFollowUp.opens(ok.copy(handsFree = true)))
    }

    @Test fun aCardOrQuestionListensAsBeforeNotTwice() {
        assertFalse(PanelFollowUp.opens(ok.copy(waitsForWords = true)))
    }

    @Test fun theWindowIsFiveSecondsAndWaitsForSomeoneTalking() {
        assertEquals(5_000L, PanelFollowUp.WINDOW_MS)
        assertFalse(PanelFollowUp.ends(4_999, hearing = false))
        assertTrue(PanelFollowUp.ends(5_000, hearing = false))
        assertFalse("still talking at 5 s", PanelFollowUp.ends(5_250, hearing = true))
        assertTrue(PanelFollowUp.ends(5_500, hearing = false))
    }

    @Test fun thatsAllAndSilenceEndIt() {
        listOf("That's all.", "Thanks, that's all", "thank you", "OK thanks", "No thanks!", "Nothing else", "that’s it",
            "C'est tout.", "Merci, c’est tout", "Non merci", "Merci beaucoup", "c'est beau", "Rien d'autre").forEach {
            assertEquals(it, Heard.DONE, PanelFollowUp.heard(it))
        }
        assertEquals(Heard.SILENCE, PanelFollowUp.heard(""))
        assertEquals(Heard.SILENCE, PanelFollowUp.heard("  ... "))
    }

    @Test fun theNextQuestionGoesOn() {
        listOf("And Sunday?", "What about Sunday", "Thanks, and Sunday?", "OK what time is it", "Et dimanche?", "Merci, et demain?",
            "Set a timer for that", "nothing on my calendar tomorrow?").forEach {
            assertEquals(it, Heard.QUESTION, PanelFollowUp.heard(it))
        }
    }

    @Test fun theSettingIsOffByDefaultAndKeptInHeadsetJson() {
        assertFalse(HeadsetSettings().panelFollowUp)
        assertFalse(HeadsetSettings.fromJson(JSONObject()).panelFollowUp)
        val on = HeadsetSettings(panelFollowUp = true)
        assertTrue(on.toJson().getBoolean("panelFollowUp"))
        assertEquals(on, HeadsetSettings.fromJson(on.toJson()))
    }
}
