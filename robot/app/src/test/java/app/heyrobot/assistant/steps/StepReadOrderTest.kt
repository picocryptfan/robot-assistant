// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.steps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Step by step and Read it to me share the panel's readCmd (stage-14-reading's merge): a step's words are logged as
 * StepRouter, every other reading as ReadRouter, in the turn's order after Files and before Lists and Correct, and
 * every router the panel names is one the spoken tool knows.
 */
class StepReadOrderTest {
    private val panel = File("src/main/java/app/heyrobot/assistant/PanelController.kt").readText().replace(Regex("\\s+"), " ")
    private val last = panel.substringAfter("lastRouter = listOf(").substringBefore("?: \"model\"")
    /** The turn itself: its `when` picks the first router with a claim, in this order. */
    private val turn = panel.substringAfter("val heldFix = PanelCorrect.held()")
    /** A router's claim condition: `val listCmd = if (...) PanelLists.route(...)`. */
    private fun guard(cmd: String) = panel.substringAfter("val $cmd = if (").substringBefore(") Panel")

    @Test fun aStepIsLoggedAsStepRouterAndAnyOtherReadingAsReadRouter() {
        val part = "readCmd to if (readCmd is app.heyrobot.core.read.StepsOf || readCmd is app.heyrobot.core.read.StepSay) \"StepRouter\" else \"ReadRouter\""
        assertTrue(part, last.contains(part))
    }

    @Test fun readComesAfterFilesAndBeforeListsAndCorrect() {
        val order = listOf("fileCmd to", "readCmd to", "listCmd to", "fixCmd to", "linkCmd to").map { last.indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test fun theTurnRunsReadAfterFilesAndBeforeListsAndCorrect() {
        val order = listOf("fileCmd != null ->", "readCmd != null ->", "listCmd != null ->", "fixCmd != null ->", "linkCmd != null ->").map { turn.indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test fun listsAndLinkLeaveAPhraseReadHasClaimed() {
        listOf("listCmd", "linkCmd").forEach { assertTrue("$it: ${guard(it)}", guard(it).contains("readCmd == null")) }
    }

    @Test fun correctLeavesAPhraseReadHasClaimed() {
        assertTrue(guard("fixCmd"), guard("fixCmd").contains("readCmd == null"))
    }

    @Test fun everyRouterThePanelNamesIsInTheSpokenList() {
        val spoken = File("../tools/spoken/spoken.json").readText()
        val routers = Regex("\"routers\": \\[([^\\]]*)]").find(spoken)!!.groupValues[1]
        val known = Regex("\"(\\w+)\"").findAll(routers).map { it.groupValues[1] }.toSet()
        val named = Regex("\"(\\w+Router)\"").findAll(last).map { it.groupValues[1] }.toSet()
        assertTrue(named.toString(), named.containsAll(listOf("StepRouter", "ReadRouter", "LogbookRouter")))
        assertEquals(emptySet<String>(), named - known)
    }

    @Test fun everyRouterTheSpokenListNamesIsOneThePanelHas() {
        val spoken = File("../tools/spoken/spoken.json").readText()
        fun list(key: String) = Regex("\"$key\": \\[([^\\]]*)]").find(spoken)!!.groupValues[1].let { r -> Regex("\"(\\w+)\"").findAll(r).map { it.groupValues[1] }.toSet() }
        val named = Regex("\"(\\w+Router)\"").findAll(last).map { it.groupValues[1] }.toSet()
        // PreRouter and ToolRouter label no turn of the panel's (routersNoTurn); "model" is the fallback.
        assertEquals(emptySet<String>(), list("routers") - list("routersNoTurn") - "model" - named)
    }
}
