// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** review-wikipedia-p4-c (Medium): EXIT and onUnbind end the process while an answer hangs in the library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikiServiceEndTest {
    // No Timeout rule: it moves the test off Robolectric's main thread; every wait below has its own bound (5 s).

    /** A library whose search never returns; its close is the real one (it counts, and releases nothing). */
    private class Hung : ZimNative by FakeZim() {
        val inCall = CountDownLatch(1)
        val never = CountDownLatch(1)
        val closed = AtomicInteger()
        override fun open(fd: Int): Long? = 42L
        override fun titles(handle: Long, query: String, max: Int): List<String>? { inCall.countDown(); never.await(); return null }
        override fun close(handle: Long) { closed.incrementAndGet() }
    }

    private fun ends(how: (WikiService, Binder) -> Unit) {
        ShadowProcess.setIsIsolated()
        val service = Robolectric.buildService(WikiService::class.java).create().get()
        ShadowBinder.setCallingUid(service.applicationInfo.uid)
        val zim = Hung()
        service.reader = WikiReader(zim).also { it.open(3) }
        val kills = AtomicInteger()
        service.kill = { kills.incrementAndGet() }
        val binder = service.onBind(null) as Binder
        val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        pool.submit {
            val data = Parcel.obtain().apply { writeInterfaceToken(WikiIpc.DESCRIPTOR); writeString("Halifax"); writeInt(5) }
            binder.transact(WikiIpc.TITLES, data, Parcel.obtain(), 0)
        }
        assertTrue(zim.inCall.await(5, TimeUnit.SECONDS))
        val done = pool.submit { how(service, binder) }
        done.get(5, TimeUnit.SECONDS)
        assertEquals(1, kills.get())
        pool.shutdownNow()
    }

    @Test fun unbindEndsWhileAnAnswerHangs() = ends { s, _ -> s.onUnbind(null) }

    @Test fun exitEndsWhileAnAnswerHangs() = ends { _, b ->
        b.transact(WikiIpc.EXIT, Parcel.obtain().apply { writeInterfaceToken(WikiIpc.DESCRIPTOR) }, Parcel.obtain(), 0)
    }
}
