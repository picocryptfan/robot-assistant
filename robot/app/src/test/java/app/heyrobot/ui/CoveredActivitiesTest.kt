// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import app.heyrobot.Flat
import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * review-panel-covered H1, H2: a window whose tap sends something, places a call or answers a confirm card refuses that tap
 * while another app's window covers it (Secure.kt's filterObscuredTouches, in onCreate before the content). How the test
 * finds them: every activity in the manifest whose own source calls one of the ways a tap acts ([acts]: the panel's confirm
 * and reply, the Termux card's run, the panel's actions, the summary's send, a call or a text); and [hosts], whose cards
 * act through composables in other files. A new activity that acts the same way can't leave the filter out.
 */
class CoveredActivitiesTest {
    private val robot = File(System.getProperty("robot.rootDir"))
    private val src = File(robot, "app/src/main/java/app/heyrobot")
    private val acts = Regex("""\bPanelController\.(confirm|reply)\(|\bTermuxPanel\.change\(|\bPanelActions\.Default\b|\bAssistantPanel\(|\bSummaryJobs\.send\(|\bACTION_CALL\b|\bSmsManager\b""")
    private val hosts = setOf("MainActivity")
    private val known = setOf("MainActivity", "CarModeActivity", "SummaryActivity")

    private fun code(f: File) = Source.strip(f.readText(), keepStrings = false)

    /** Robot's own activities, by simple name, with the file that declares each. */
    private fun activities(): Map<String, File> {
        val manifest = File(robot, "app/src/main/AndroidManifest.xml").readText()
        val names = Regex("""<activity\s[^>]*?android:name="\.([\w.]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("activities in the manifest: ${names.size}", names.size >= 20)
        return names.associate { n ->
            val simple = n.substringAfterLast('.')
            val dir = File(src, n.substringBeforeLast('.', "").replace('.', '/'))
            val decl = Regex("""\bclass\s+$simple\b""")
            val file = dir.listFiles().orEmpty().singleOrNull { it.extension == "kt" && decl.containsMatchIn(code(it)) }
                ?: throw AssertionError("no single file declares $n")
            simple to file
        }
    }

    @Test fun everyActivityThatSendsCallsOrConfirmsRefusesCoveredTaps() {
        val all = activities()
        val acting = all.filter { (name, f) -> name in hosts || acts.containsMatchIn(code(f)) }
        // The finder can't go blind: the windows the review found are among those it finds.
        assertEquals(emptySet<String>(), known - acting.keys)
        val open = acting.filter { (_, f) ->
            val on = Flat(code(f)).body("override fun onCreate(")
            val filter = on.at("window.filterObscuredTouches()")
            val content = on.at("setContent")
            filter < 0 || (content >= 0 && content < filter)
        }.keys.sorted()
        assertEquals(emptyList<String>(), open)
    }
}
