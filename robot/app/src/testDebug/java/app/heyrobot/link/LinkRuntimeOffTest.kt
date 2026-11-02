// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.UserManager
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.r2r.QueueProblem
import app.heyrobot.sys.LinkSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.5, Robot to Robot (robot-to-robot-plan.md §6.1; job L5-fix): Off means off. A node already open when Robot to Robot is
 * turned off is closed at the next call, and LinkService then refuses everything but its state, turning it on and Start
 * over. Run on a real LinkNode (TestLinkNode) that LinkRuntime's test seam opens in place of the vault.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkRuntimeOffTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }

    @After fun tearDown() {
        LinkSettingsStore.update(ctx) { it.copy(on = false) }
        LinkRuntime.node(ctx)
        LinkRuntime.opener = null
    }

    private fun call(b: IBinder, code: Int, write: Parcel.() -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        data.writeInterfaceToken(LinkIpcProtocol.DESCRIPTOR)
        data.write()
        b.transact(code, data, reply, 0)
        reply.setDataPosition(0)
        data.recycle()
        return reply
    }

    @Test fun turningOffClosesTheOpenNodeAndRefusesEverythingButItsState() {
        val made = ArrayList<TestLinkNode>()
        LinkRuntime.opener = { _ -> TestLinkNode(tmp.newFolder()).also { made += it }.node }
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val n = LinkRuntime.node(ctx)
        assertNotNull(n)
        assertSame(n, LinkRuntime.node(ctx))
        assertEquals(1, made.size)
        val svc = Robolectric.buildService(LinkService::class.java).create().get()
        val b = svc.onBind(null)
        val id = ByteArray(16) { 1 }
        val send: Parcel.() -> Unit = { writeByteArray(id); writeInt(70); writeByteArray(id); writeLong(1_790_000_000L); writeLong(604_800); writeByteArray(ByteArray(512)) }
        // On: SEND reaches the node (which has no such pairing).
        val on = call(b, LinkIpcProtocol.SEND, send)
        assertEquals(LinkIpcProtocol.REPLY_OK, on.readInt())
        assertEquals(QueueProblem.NO_PAIRING.code, on.readInt())
        // On, a frame is read strictly (off, calls are refused before theirs is read): anything left over, an id that isn't
        // 16 bytes, a field missing: refused, nothing after it.
        for (bad in listOf<Parcel.() -> Unit>(
            { send(); writeInt(0) },
            { writeByteArray(ByteArray(15) { 1 }); writeInt(70); writeByteArray(id); writeLong(1_790_000_000L); writeLong(604_800); writeByteArray(ByteArray(512)) },
            { writeByteArray(id); writeInt(70); writeByteArray(id); writeLong(1_790_000_000L) },
        )) {
            val r = call(b, LinkIpcProtocol.SEND, bad)
            assertEquals(LinkIpcProtocol.REPLY_REFUSED, r.readInt())
            assertEquals(0, r.dataAvail())
        }
        // Off, written by the main app's Settings: the next call closes the node and opens nothing.
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = false) })
        assertNull(LinkRuntime.node(ctx))
        assertTrue(n!!.isClosed)
        assertNull(LinkRuntime.node(ctx))
        assertEquals(1, made.size)
        val off = call(b, LinkIpcProtocol.SEND, send)
        assertEquals(LinkIpcProtocol.REPLY_REFUSED, off.readInt())
        assertEquals(0, off.dataAvail())
        // Every call but STATUS, SET_UP and START_OVER is refused before its frame is read.
        for (code in LinkIpcProtocol.STATUS..LinkIpcProtocol.LAST) {
            if (code == LinkIpcProtocol.STATUS || code == LinkIpcProtocol.SET_UP || code == LinkIpcProtocol.START_OVER) continue
            assertEquals(code.toString(), LinkIpcProtocol.REPLY_REFUSED, call(b, code) {}.readInt())
        }
        val status = call(b, LinkIpcProtocol.STATUS) {}
        assertEquals(LinkIpcProtocol.REPLY_OK, status.readInt())
        status.readInt()
        assertEquals("off", 0, status.readInt())
        // On again: a new node, awake at once for the client still bound.
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        val again = LinkRuntime.node(ctx)!!
        assertEquals(2, made.size)
        assertTrue(again.status().get().awake)
        svc.onUnbind(null)
    }
}
