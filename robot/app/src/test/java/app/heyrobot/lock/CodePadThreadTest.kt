// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The code pad's window never reads state.json, code.json or the Keystore on the main thread: every call of the pad's
 * steps (and LockStore) in LockCodeActivity runs inside withContext(Dispatchers.IO), in the engine the pad calls, or on
 * onStop's own thread.
 */
class CodePadThreadTest {
    private val calls = Regex("""\b(pad\.(open|tries|check|set|remove|reconcile)|LockStore\.readState|vault\.(isSet|check|set|remove))\(""")

    @Test fun thePadsReadsAreOffTheMainThread() {
        val src = File("src/main/java/app/heyrobot/lock/LockCodeActivity.kt").readLines()
        val window = src.takeWhile { !it.startsWith("internal class CodePad") }
        assertTrue(window.size in 100 until src.size)
        val onMain = window.withIndex().filter { (i, line) ->
            val t = line.trimStart()
            calls.containsMatchIn(line) && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/**") &&
                "withContext(Dispatchers.IO)" !in line && "override fun" !in line && "Thread({" !in window[i - 1]
        }.map { (i, line) -> "${i + 1}: ${line.trim()}" }
        assertEquals(emptyList<String>(), onMain)
    }
}
