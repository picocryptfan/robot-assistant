// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import app.heyrobot.Source
import app.heyrobot.assistant.car.CoveredLine
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix-covered-partial-b (review-covered-partial-b): what car mode's refused Yes says, and where the cards live. */
class CoveredPartialBTest {
    private val root = File(System.getProperty("robot.rootDir"))
    private val src = File(root, "app/src/main/java/app/heyrobot")
    private fun code(name: String) = Source.strip(File(src, name).readText(), keepStrings = false)
    private val said = "on my way"

    @Test fun aReplysLineFollowsTheSpokenYesGate() {
        assertEquals(CoveredLine.VOICE, CoveredLine.reply(true, true, true, true, said, said))
        assertEquals("Speak replies off, emoji only", CoveredLine.NO_ONLY, CoveredLine.reply(true, true, true, false, said, said))
        assertEquals("Answer cards by voice off", CoveredLine.NO_ONLY, CoveredLine.reply(true, true, false, true, said, said))
        assertEquals("not trusted", CoveredLine.NO_ONLY, CoveredLine.reply(true, false, true, true, said, said))
        assertEquals("changed since read out", CoveredLine.NO_ONLY, CoveredLine.reply(true, true, true, true, said, "$said now"))
        assertEquals("not read out", CoveredLine.NO_ONLY, CoveredLine.reply(true, true, true, true, null, said))
        val long = "word ".repeat(400).trim()
        assertEquals("too long aloud", CoveredLine.NO_ONLY, CoveredLine.reply(true, true, true, true, long, long))
        assertEquals("nothing listens", CoveredLine.TAP, CoveredLine.reply(false, true, true, true, said, said))
    }

    @Test fun onlyAVoiceLineSaysYesOrNoAndOnlyAListenSaysNo() {
        val en = Langs.of(Lang.EN)
        assertTrue("Say yes or no" in CoveredLine.VOICE.phrase.render(en))
        for (l in listOf(CoveredLine.NO_ONLY, CoveredLine.TAP)) assertFalse(l.name, "Say yes or no" in l.phrase.render(en))
        assertTrue("say no" in CoveredLine.NO_ONLY.phrase.render(en))
        assertFalse("say no" in CoveredLine.TAP.phrase.render(en))
        assertEquals("each its own card line", 3, CoveredLine.entries.map { it.res }.toSet().size)
    }

    @Test fun bothLinesUseTheirCardsOwnGate() {
        val p = code("assistant/PanelController.kt")
        val a = p.indexOf("fun coveredYes(").also { assertTrue(it >= 0) }
        val c = p.substring(a, p.indexOf("fun confirm(actionId: String", a))
        assertTrue("PanelMessages' own gate", "PanelMessages.coveredVoice(" in c)
        assertFalse("never car mode's trust alone", "CarModeController.trusted" in c)
    }

    /** A window of its own (a dialog, popup, menu or modal sheet). */
    private val ownWindow = Regex("""\b(Dialog|AlertDialog|BasicAlertDialog|DatePickerDialog|TimePickerDialog|Popup|DropdownMenu|ModalBottomSheet|ModalBottomSheetLayout)\s*[({]""")

    /** First review's L3: CoveredTouches reads the screen's own window; a card in a window of its own would skip its check. */
    @Test fun bothCardsStayInTheirScreensOwnWindow() {
        assertTrue(ownWindow.containsMatchIn("Dialog(onDismissRequest = {}) { Yes() }"))
        assertTrue(ownWindow.containsMatchIn("ModalBottomSheet(onDismissRequest = {}) { Card() }"))
        assertFalse(ownWindow.containsMatchIn("Card { Button(onClick = {}) { Text(yes) } }"))
        val files = listOf(File(src, "assistant/car/CarModeActivity.kt"), File(src, "assistant/conversation/SummaryActivity.kt")) +
            File(src, "ui/car").walk().filter { it.extension == "kt" }.toList()
        assertTrue(files.size > 2)
        for (f in files) assertFalse("${f.name} puts something in a window of its own", ownWindow.containsMatchIn(Source.strip(f.readText(), keepStrings = false)))
    }

    @Test fun summaryNoteGoesWithItsCard() {
        val s = code("assistant/conversation/SummaryActivity.kt")
        assertTrue("Cancel clears the note", "onCancel = { note = null; ask = null }" in s)
        assertTrue("another helper's card clears it", "{ note = null; ask = cardFor(meta, tr, o) }" in s)
    }

    @Test fun carMessagesAreToastsAgainWhilePaused() {
        val c = code("assistant/car/CarModeActivity.kt")
        assertTrue("RobotToast.down(this)" in c.substring(c.indexOf("override fun onPause()"), c.indexOf("override fun onStop()")))
    }
}
