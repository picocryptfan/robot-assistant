// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import app.heyrobot.Source
import app.heyrobot.assistant.car.CarCoveredLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix-covered-partial (review-covered-partial M1, M2, L1, L2): a refused Yes's line belongs to its card; in car mode it is
 * said aloud and the card takes a spoken answer; the summary's note goes once a Send goes through; Robot's own toasts
 * never refuse its own Yes and Send.
 */
class CoveredPartialTest {
    private val root = File(System.getProperty("robot.rootDir"))
    private val src = File(root, "app/src/main/java/app/heyrobot")
    private fun code(name: String) = Source.strip(File(src, name).readText(), keepStrings = false)
    private fun part(c: String, from: String, until: String): String {
        val a = c.indexOf(from).also { assertTrue(from, it >= 0) }
        return c.substring(a, c.indexOf(until, a + from.length).also { assertTrue(until, it > a) })
    }

    @Test fun theLineIsOnlyTheRefusedCards() {
        val l = CarCoveredLine(5_000)
        l.refused("a", 7, 0)
        assertEquals(7, l.line("a", 1_000))
        assertNull("another card never shows it", l.line("b", 1_000))
        assertNull("nor does it come back", l.line("a", 1_100))
        l.refused("a", 7, 2_000)
        l.answered()
        assertNull("any answer clears it", l.line("a", 2_100))
        l.refused("a", 7, 3_000)
        assertNull("for a few seconds only", l.line("a", 8_000))
        l.refused(null, 7, 9_000)
        assertNull("no card, no line", l.line(null, 9_100))
    }

    @Test fun carRefusalIsTheCardsOwn() {
        val c = code("assistant/car/CarModeActivity.kt")
        assertFalse("no line on a timer alone", "refusedUntil" in c)
        val answer = part(c, "private fun answer(", "private fun fourth(")
        val cleared = answer.indexOf("refusal.answered()")
        assertTrue("any answer clears the line", cleared >= 0 && cleared < answer.indexOf("PanelController.confirm("))
        assertTrue("the line is kept for the refused card", "refusal.refused(" in answer)
        assertTrue("shown only for that card", "refusal.line(" in part(c, "private fun refusedShown(", "private fun refresh("))
    }

    @Test fun carRefusalIsSaidAndHeard() {
        val c = code("assistant/car/CarModeActivity.kt")
        val answer = part(c, "private fun answer(", "private fun fourth(")
        assertTrue("a refused Yes is said aloud and heard", answer.indexOf("PanelController.coveredYes(").let { it in 0 until answer.indexOf("return refresh()") })
        val p = code("assistant/PanelController.kt")
        assertTrue("a reply card listens once after the line", "messagesListen(false)" in part(p, "fun coveredYes(", "\n    }"))
        val v = part(code("assistant/PanelCardVoice.kt"), "fun covered(", "\n    }")
        assertTrue("an action card listens after the line", "listenAfterSpeech = true" in v)
        assertTrue("a tap-only card is told so", "tapOnly(" in v && "CoveredLine.of(" in v)
        val t = File(root, "core/src/main/java/app/heyrobot/core/i18n/TurnStrings.kt").readText()
        assertTrue("the spoken lines", "\"covered_say\"" in t && "\"covered_move\"" in t)
        for (dir in listOf("values", "values-fr-rCA")) {
            val x = File(root, "app/src/main/res/$dir/strings_handsfree.xml").readText()
            assertTrue("$dir: the card's lines", "name=\"car_covered_voice\"" in x && "name=\"car_covered_move\"" in x)
        }
        assertTrue("the card says to answer by voice", "Answer by voice" in File(root, "app/src/main/res/values/strings_handsfree.xml").readText())
    }

    @Test fun summaryNoteGoesOnceSent() {
        val s = part(code("assistant/conversation/SummaryActivity.kt"), "if (covered.now) {", "SummaryJobs.send(")
        assertTrue("a Send that goes through clears the refusal note", "note = null" in s)
    }

    @Test fun aScreenGoingKeepsTheNextOnesHold() {
        val a = Any(); val b = Any()
        app.heyrobot.ui.common.RobotToast.up(a) {}
        app.heyrobot.ui.common.RobotToast.up(b) {}
        app.heyrobot.ui.common.RobotToast.down(a)
        assertTrue("b still holds", app.heyrobot.ui.common.RobotToast.screen() != null)
        app.heyrobot.ui.common.RobotToast.down(b)
        assertNull("none up: a toast", app.heyrobot.ui.common.RobotToast.screen())
    }

    @Test fun robotsOwnToastsNeverRefuseItsTaps() {
        val toasts = src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "RobotToast.kt" }
            .filter { "Toast.makeText(" in Source.strip(it.readText(), keepStrings = false) }.map { it.name }.toList()
        assertTrue("toasts only through RobotToast: $toasts", toasts.isEmpty())
        for (n in listOf("assistant/car/CarModeActivity.kt", "assistant/conversation/SummaryActivity.kt")) {
            val c = code(n)
            assertTrue("$n takes Robot's toasts while up", "RobotToast.up(" in c && "RobotToast.down(" in c)
        }
    }
}
