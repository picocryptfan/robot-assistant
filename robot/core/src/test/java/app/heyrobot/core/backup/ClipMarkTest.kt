// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F48: a copied passphrase is cleared after a minute even when the screen or process that copied it has gone. */
class ClipMarkTest {
    private val pass = "maple river stone cloud amber field"

    @Test fun waitsUntilDueThenClearsOnlyThePassphrase() {
        val m = ClipMark.make(pass, dueAt = 60_000)
        assertEquals(ClipMark.Action.WAIT, ClipMark.decide(m, pass, 59_999))
        assertEquals(ClipMark.Action.CLEAR, ClipMark.decide(m, pass, 60_000))
        assertEquals(ClipMark.Action.FORGET, ClipMark.decide(m, "your shopping list", 60_000))
    }

    @Test fun withoutAClipToReadItWaitsForTheFocus() {
        assertEquals(ClipMark.Action.WAIT, ClipMark.decide(ClipMark.make(pass, 0), null, 60_000))
    }

    @Test fun theEndOfABackupMakesItDueButNeverLater() {
        val m = ClipMark.make(pass, dueAt = 60_000)
        assertEquals(ClipMark.Action.CLEAR, ClipMark.decide(m.dueNow(10), pass, 10))
        assertEquals(5L, ClipMark.make(pass, 5).dueNow(10).dueAt)
    }

    @Test fun survivesTheFileAndNeverHoldsThePassphrase() {
        val m = ClipMark.make(pass, 60_000)
        val json = m.toJson()
        assertFalse(json.contains("maple"))
        assertEquals(m, ClipMark.fromJson(json))
        assertEquals(ClipMark.Action.CLEAR, ClipMark.decide(ClipMark.fromJson(json)!!, pass, 60_000))
        assertNotEquals(m.hash, ClipMark.make(pass, 60_000).hash)
    }

    @Test fun aDamagedFileIsNoMark() {
        assertNull(ClipMark.fromJson(null))
        assertNull(ClipMark.fromJson("not json"))
        assertNull(ClipMark.fromJson("{\"salt\":\"\",\"hash\":\"\",\"dueAt\":1}"))
    }
}
