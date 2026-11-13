// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// M3 (review-wikipedia): a :wiki process that dies (idle exit, a crash on a bad file) is noticed, its binding let go and
// bound once more; a reader that stays dead reads as "the copy isn't available", never as "nothing found".
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikiLinkTest {
    /** A reader that answers one hit, or throws as a dead process's binder does. */
    private class Reader : Binder() {
        var dead = false
        var calls = 0
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (dead) throw DeadObjectException()
            calls++
            reply?.writeInt(WikiIpc.REPLY_OK)
            reply?.let { WikiIpc.writeHits(it, listOf(WikiHit("A/Fjord", "Fjord"))) }
            return true
        }
    }

    private class Binds(vararg readers: Reader?) {
        val queue = ArrayDeque(readers.toList())
        var binds = 0
        var unbinds = 0
        var onDeath: (() -> Unit)? = null
        val bind: (() -> Unit) -> WikiLink.Bound? = { death ->
            binds++
            onDeath = death
            queue.removeFirstOrNull()?.let { r -> WikiLink.Bound(r) { unbinds++ } }
        }
    }

    /**
     * fix-wiki-rc1-b: Android connects a bound service later, on another thread. 9cc565aff read the binder before the wait,
     * so connect always got null and the copy never answered on rc1.
     */
    @Test fun aServiceThatConnectsLaterIsBound() {
        val reader = Reader()
        var unbinds = 0
        val b = WikiLink.bindOnce({ c -> Thread { Thread.sleep(100); c.onServiceConnected(null, reader) }.start(); true }, { unbinds++ }, 5_000) {}
        assertTrue(b?.binder === reader)
        assertEquals(0, unbinds)
    }

    /** M1 (review-fix-wiki-rc1-b): a stop while :wiki is starting interrupts the wait; the binding is let go, not kept. */
    @Test fun aStopDuringTheWaitLetsTheBindingGo() {
        var unbinds = 0
        var thrown: Throwable? = null
        val t = Thread { try { WikiLink.bindOnce({ true }, { unbinds++ }, 5_000) {} } catch (e: InterruptedException) { thrown = e } }
        t.start()
        Thread.sleep(100)
        t.interrupt()
        t.join(2_000)
        assertTrue(thrown is InterruptedException)
        assertEquals(1, unbinds)
    }

    /** M2: the old connection's death notice, arriving while a rebind waits under the lock, doesn't wait for it. */
    @Test fun aStaleDeathNoticeDoesNotWaitForTheRebind() {
        val first = Reader()
        var firstDeath: (() -> Unit)? = null
        var noticeDone = false
        var binds = 0
        val link = WikiLink { death ->
            if (++binds == 1) {
                firstDeath = death
                WikiLink.Bound(first) {}
            } else {
                val t = Thread { firstDeath!!.invoke() }.apply { start(); join(1_000) }
                noticeDone = !t.isAlive
                WikiLink.Bound(Reader()) {}
            }
        }
        link.titles("fjord")
        first.dead = true
        assertEquals("Fjord", link.titles("fjord").single().title)
        assertTrue(noticeDone)
    }

    @Test fun aServiceThatNeverConnectsIsLetGo() {
        var unbinds = 0
        assertTrue(WikiLink.bindOnce({ true }, { unbinds++ }, 200) {} == null)
        assertEquals(1, unbinds)
    }

    @Test fun aDeadProcessIsBoundOnceMoreAndAnswers() {
        val first = Reader()
        val second = Reader()
        val b = Binds(first, second)
        val link = WikiLink(b.bind)
        assertEquals("Fjord", link.titles("fjord").single().title)
        first.dead = true
        assertEquals("Fjord", link.titles("fjord").single().title)
        assertEquals(2, b.binds)
        assertEquals(1, b.unbinds)
        assertEquals(1, second.calls)
        assertFalse(link.gone)
    }

    @Test fun aReaderThatStaysDeadIsUnavailableNotNothingFound() {
        val first = Reader()
        val b = Binds(first, null)
        val link = WikiLink(b.bind)
        link.titles("fjord")
        first.dead = true
        assertTrue(link.titles("fjord").isEmpty())
        assertEquals(WikiIpc.REPLY_GONE, link.lastStatus)
        assertTrue(link.gone)
        // Gone for good: no third binding, every later call unavailable.
        assertTrue(link.search("fjord").isEmpty())
        assertEquals(WikiIpc.REPLY_GONE, link.lastStatus)
        assertEquals(2, b.binds)
    }

    @Test fun aDeathNoticeLetsTheBindingGo() {
        val b = Binds(Reader(), Reader())
        val link = WikiLink(b.bind)
        link.titles("fjord")
        b.onDeath!!.invoke()
        assertEquals(1, b.unbinds)
        assertEquals("Fjord", link.titles("fjord").single().title)
        assertEquals(2, b.binds)
    }
}
