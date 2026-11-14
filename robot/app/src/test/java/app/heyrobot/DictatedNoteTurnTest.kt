// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** review-fix-jot-down-after-note L1, L2, L5: the direct note path keeps what the model path keeps, names itself, and comes after the plans. */
class DictatedNoteTurnTest {
    private val panel = File("src/main/java/app/heyrobot/assistant/PanelController.kt").readText()
    private val branch = panel.substringAfter("dictated != null ->").substringBefore("else ->")

    @Test fun aDictatedNoteKeepsAHeldCorrectionAndTheAskForAHelper() {
        assertTrue(branch, "PanelCorrect.keep(heldFix)" in branch)
        assertTrue(branch, "helperChoice.asked(asked)" in branch)
    }

    @Test fun lastRouterNamesTheDirectNotePath() {
        assertTrue(branch, "lastRouter = \"NoteDictation\"" in branch)
    }

    @Test fun planAndMultiAsksComeBeforeTheNote() {
        val turn = panel.substringAfter("val dictated = ")
        val skill = turn.indexOf("skillCmd != null -> {")
        assertTrue(skill >= 0 && skill < turn.indexOf("dictated != null ->"))
    }
}
