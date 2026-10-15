// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun loggingNeverThrowsOnTheJvm() {
        RLog.i("x", "a" to 1)
        RLog.w("y", IllegalStateException("message with content"))
    }
}
