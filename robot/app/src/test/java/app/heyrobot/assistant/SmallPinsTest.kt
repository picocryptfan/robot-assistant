// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Small rules read from the source (ls13-lows), where running the code would need a whole session or panel. */
class SmallPinsTest {
    private val roots = listOf(File("src/main/java"), File("../core/src/main/java"))
    private fun code(f: File) = Source.strip(f.readText(), keepStrings = false, kotlin = true)
    private fun source(path: String) = code(File("src/main/java/app/heyrobot/$path"))

    /** From the opening bracket after [at] to its closing one. */
    private fun args(text: String, at: Int): String {
        var depth = 0
        for (i in at until text.length) when (text[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return text.substring(at, i + 1)
        }
        return text.substring(at)
    }

    /**
     * Whether a JID is a phone number depends on the SMS gateway Robot texts through (Settings' smsGateway; F6): a call
     * that leaves the parameter out reads another gateway's addresses wrong, so none does outside the tests.
     */
    @Test fun everyGatewayIsTheSetting() {
        val calls = Regex("""\b(MessagePeople\.(personFor|personOf|withoutCard|named|find|matchesKey)|AnnounceSenders\.chosen|PeopleEdit\.merge|RestoreMerge\.people|Recipients\.forText)\(""")
        val without = roots.flatMap { r -> r.walkTopDown().filter { it.name.endsWith(".kt") }.toList() }.flatMap { f ->
            val text = code(f)
            calls.findAll(text).filter { m -> !args(text, m.range.last).contains("ateway") }.map { "${f.name}: ${it.value}" }.toList()
        }
        assertEquals(emptyList<String>(), without)
    }

    /** During a lock a session's call that would open the dialer (which the lock covers) goes to the call card instead (core CallTarget.duringLock). */
    @Test fun aSessionsCallDuringALockIsPlannedPastTheCoveredDialer() {
        val call = source("assistant/handsfree/PanelHandsFree.kt").let { it.substring(it.indexOf("private suspend fun call(")).substringBefore("private suspend fun callAnswer(") }
        assertTrue(call, call.contains("CallTarget.duringLock(") && call.contains("lockOn"))
    }

    /** The panel's attach button opens Camera, Photos and File: a screen reader says so, in both languages. */
    @Test fun theAttachButtonSaysWhatItOpens() {
        assertTrue(source("ui/classic/ClassicPanel.kt").contains("R.string.panel_add_photo_or_file"))
        fun label(dir: String) = Regex("""name="panel_add_photo_or_file">([^<]*)<""").find(File("src/main/res/$dir/strings_panel.xml").readText())?.groupValues?.get(1)
        assertEquals("Add a photo or a file", label("values"))
        assertEquals("Ajouter une photo ou un fichier", label("values-fr-rCA"))
    }
}
