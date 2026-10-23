// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTest {
    @Test fun onlyNumbersBooleansEnumsAndCodesAreLogged() {
        val line = RLog.format("brain_reply", arrayOf(
            "chunks" to 12, "tps" to 17.25, "ok" to true, "reason" to DoneReason.END, "src" to RLog.Code("wake"),
            "text" to "my secret question", "list" to listOf("a"), "none" to null, "nan" to Double.NaN,
        ))
        assertEquals("brain_reply chunks=12 tps=17.25 ok=true reason=END src=wake text=<redacted> list=<redacted> none=null nan=nan", line)
        assertFalse(line.contains("secret"))
    }

    @Test fun codesAreSanitised() {
        assertEquals("e src=abc_d-1.x:y", RLog.format("e", arrayOf("src" to RLog.Code("a b\nc_d-1.x:y"))))
    }

    @Test fun aFailureNamesItsCausesButNeverTheirMessages() {
        val lines = ArrayList<String>()
        RLog.sink = { lines += it }
        try {
            val t = ExceptionInInitializerError(java.util.regex.PatternSyntaxException("secret words", "my secret pattern", 4))
            RLog.w("tool_prepare", t, "n" to 1)
            assertEquals("tool_prepare n=1 error=ExceptionInInitializerError cause1=PatternSyntaxException", lines.single())
            assertFalse(lines.single().contains("secret"))
        } finally {
            RLog.sink = null
        }
        // A long chain stops at MAX_CAUSES; a loop of causes ends.
        var deep: Throwable = IllegalStateException("x")
        repeat(8) { deep = RuntimeException("level $it", deep) }
        assertEquals(1 + RLog.MAX_CAUSES, RLog.errorFields(deep).size)
        val a = Exception("a")
        a.initCause(Exception("b", a))
        assertEquals(listOf("error", "cause1"), RLog.errorFields(a).map { it.first })
        // An anonymous exception still has a name, never its message.
        val anon = RLog.format("e", RLog.errorFields(object : Exception("hidden words") {}))
        assertTrue(anon, anon.startsWith("e error=LogTest"))
        assertFalse(anon.contains("hidden"))
    }

    @Test fun loggingNeverThrowsOnTheJvm() {
        RLog.i("x", "a" to 1)
        RLog.w("y", IllegalStateException("message with content"))
    }
}
