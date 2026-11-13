// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterItem
import app.heyrobot.core.online.LaterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LaterVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(id: String, at: Long = 1_000) = LaterItem(id, "Look up the ferry times to Digby", "ferry times to Digby", Lang.EN, at, asks = false)

    @Test fun sealedOnDiskAndKeptAcrossARestart() {
        val keys = SoftMemoryKeys()
        val dir = tmp.newFolder("later")
        LaterVault(dir, keys).update { it + item("a") }
        val bytes = java.io.File(dir, LaterVault.FILE).readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Digby"))
        // After a restart or a reboot: a new vault over the same folder and key reads it back.
        assertEquals(listOf(item("a")), LaterVault(dir, keys).load())
    }

    @Test fun theKeystoreBusyIsNotNowNeverEmpty() {
        val keys = SoftMemoryKeys()
        val v = LaterVault(tmp.newFolder("later"), keys)
        v.update { it + item("a") }
        keys.busy = true
        assertNull(v.load())
        assertNull(v.update { emptyList() })
        keys.busy = false
        assertEquals(1, v.load()?.size)
    }

    @Test fun aKeyGoneDropsTheQuestionsAndSendsNothing() {
        val keys = SoftMemoryKeys()
        val v = LaterVault(tmp.newFolder("later"), keys)
        v.update { it + item("a") }
        keys.keys.clear()
        assertEquals(emptyList<LaterItem>(), v.load())
        assertFalse(v.file.exists())
    }

    @Test fun onceOnlyAndExpiryThroughTheFile() {
        val v = LaterVault(tmp.newFolder("later"), SoftMemoryKeys())
        v.update { it + item("a", at = 0) + item("b", at = Later.WAIT_MS) }
        val after = v.update { Later.expire(it, Later.WAIT_MS + 5) }!!
        assertEquals(LaterState.EXPIRED, after.first { it.id == "a" }.state)
        val next = Later.next(after)!!
        v.update { Later.settle(it, next.id, LaterState.ANSWERED, "") }
        assertNull(Later.next(v.load()!!))
        assertTrue(v.update { Later.drop(it, setOf("a", "b")) }!!.isEmpty())
        assertFalse(v.file.exists())
    }
}
