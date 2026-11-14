// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-calendar-lows-b: review-fix-calendar-lows finding 1 and its Nit, in Calendars.kt's provider code.
class CalendarsLowsBTest {
    private val src = listOf("src/main/java/app/heyrobot/sys/Calendars.kt", "app/src/main/java/app/heyrobot/sys/Calendars.kt")
        .map(::File).first { it.isFile }.readText()

    private fun body(sig: String): String {
        val i = src.indexOf(sig)
        assertTrue(sig, i >= 0)
        return src.substring(i, src.indexOf("\n    }\n", i))
    }

    // Finding 1: both one-occurrence paths ask whether the series has an exception before writing, and none is resent.
    @Test fun oneOccurrenceAsksFirstAndResendsNoException() {
        for (s in listOf("fun update(", "fun delete(")) assertTrue(s, "OccurrenceEdit.unlessChanged(write, { changed(ctx, id) })" in body(s))
        assertFalse("ORIGINAL_ID} = ?" in src.substring(src.indexOf("private fun excludeOps("), src.indexOf("private fun changed(")))
    }

    // The Nit: a split-off deleted with its series was forgotten only from the splits file, so it stayed in `added`.
    @Test fun aGoneSplitOffIsNoLongerRobots() {
        val b = body("private fun deleteSplits(")
        assertTrue(b, "PrivateEventsFiles.forgetAdded(ctx, it)" in b && "PrivateEventsFiles.forgetSplits(ctx, gone)" in b)
    }
}
