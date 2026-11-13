// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import app.heyrobot.core.seal.FakeInboxWrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

// review-screenshots-b Low (ShotStore.kt:72): a switch-off cut short (the process killed part way) is finished on the
// next start, so neither the index nor its key is half kept.
class ShotStoreOffTest {
    /** A process kill: nothing after it runs. */
    private class Killed : Error()

    private class Keys : ShotKeys {
        val inner = FakeInboxWrap()
        var dying = false
        var deleted = false
        override fun create() {}
        override fun delete() { if (dying) throw Killed(); deleted = true }
        override fun wrap(plain: ByteArray) = inner.wrap(plain)
        override fun unwrap(wrapped: ByteArray): ByteArray = inner.unwrap(wrapped)
    }

    @Test fun aSwitchOffCutShortFinishesOnTheNextStart() {
        val parent = Files.createTempDirectory("shots").toFile()
        val dir = File(parent, "shots")
        try {
            val keys = Keys()
            val store = ShotStore(dir, keys)
            store.switchOn(1_000, 10)
            assertTrue(store.append(ShotBatch(listOf(ShotEntry(5, 2_000, "Harbourview Inn"))), store.state()))
            keys.deleted = false // switching on deleted any old key
            keys.dying = true
            try { store.switchOff(); fail("not killed") } catch (_: Killed) {}
            keys.dying = false
            val next = ShotStore(dir, keys)
            assertFalse("reads as off", next.state().on)
            assertEquals(ShotStore.Load.Off, next.load())
            assertTrue("the key", keys.deleted)
            assertFalse("the index", dir.exists())
        } finally {
            parent.deleteRecursively()
        }
    }
}
