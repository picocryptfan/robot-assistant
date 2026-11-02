// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.linkparse

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import app.heyrobot.core.link.LinkParseIpc
import app.heyrobot.core.link.LinkParseWire
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.linkparse.LinkSamples.bodies
import app.heyrobot.linkparse.LinkSamples.req
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import java.util.concurrent.atomic.AtomicInteger

/**
 * :linkparse's own binder (LinkParseService, job L4), called through real Parcels and SharedMemory regions: in a process that
 * isn't isolated it touches nothing; it answers Robot's own uid alone (any other, an isolated one included, is refused before
 * anything is read); frames are exact; HELLO proves its uid through the caller's callback; DECODE answers every type in a
 * frame :link keeps. Replies are numbers and bytes only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkParseServiceTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var binder: IBinder
    private val calledBack = AtomicInteger()
    @Volatile private var callbackSays = 1

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(LinkParseIpc.CALLBACK)
            assertEquals(LinkParseIpc.CB_HELLO, code)
            assertEquals(Process.myPid(), data.readInt())
            calledBack.incrementAndGet()
            reply?.writeInt(callbackSays)
            return true
        }
    }

    @Before fun setUp() {
        app.applicationInfo.uid = APP_UID
        binder = Robolectric.buildService(LinkParseService::class.java).create().get().onBind(Intent())
        ShadowProcess.setIsIsolated()
    }

    @After fun tearDown() {
        ShadowBinder.reset()
        ShadowProcess.reset()
    }

    private fun call(code: Int, caller: Int = APP_UID, write: (Parcel) -> Unit): Parcel {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        write(d)
        ShadowBinder.setCallingUid(caller)
        assertTrue(binder.transact(code, d, r, 0))
        d.recycle()
        r.setDataPosition(0)
        return r
    }

    private fun hello(caller: Int = APP_UID) = call(LinkParseIpc.HELLO, caller) { it.writeInterfaceToken(LinkParseIpc.DESCRIPTOR); it.writeStrongBinder(callback) }

    private fun region(b: ByteArray, size: Int = b.size): SharedMemory = SharedMemory.create("t", size).also { m -> m.mapReadWrite().also { it.put(b); SharedMemory.unmap(it) } }

    private fun decode(plain: ByteArray, cap: Int = LinkRequests.LIVE, caller: Int = APP_UID, frame: ByteArray = LinkParseWire.encodeRequest(cap, plain.size),
        regionSize: Int = plain.size, after: (Parcel) -> Unit = {}): Parcel = call(LinkParseIpc.DECODE, caller) { d ->
        d.writeInterfaceToken(LinkParseIpc.DESCRIPTOR)
        d.writeByteArray(frame)
        d.writeInt(1)
        region(plain, regionSize).writeToParcel(d, 0)
        after(d)
    }

    private fun only(r: Parcel, code: Int) {
        assertEquals(code, r.readInt())
        assertEquals(0, r.dataAvail())
    }

    @Test fun notIsolatedItTouchesNothing() {
        ShadowProcess.setUid(APP_UID)
        assertFalse(Process.isIsolated())
        only(hello(), LinkParseIpc.REPLY_NOT_ISOLATED)
        only(decode(LinkRequests.encode(req(bodies()[0]))!!), LinkParseIpc.REPLY_NOT_ISOLATED)
        assertEquals(0, calledBack.get())
    }

    @Test fun onlyRobotsOwnUidIsAnswered() {
        val plain = LinkRequests.encode(req(bodies()[0]))!!
        for (caller in listOf(APP_UID + 1, 99_001, 0, 1_000, 10_000 + APP_UID)) {
            only(hello(caller), LinkParseIpc.REPLY_REFUSED)
            only(decode(plain, caller = caller), LinkParseIpc.REPLY_REFUSED)
        }
        assertEquals(0, calledBack.get())
        // EXIT from another uid ends nothing.
        call(LinkParseIpc.EXIT, APP_UID + 1) { it.writeInterfaceToken(LinkParseIpc.DESCRIPTOR) }
        assertFalse(ShadowProcess.wasKilled(Process.myPid()))
        call(LinkParseIpc.EXIT) { it.writeInterfaceToken(LinkParseIpc.DESCRIPTOR) }
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test fun helloProvesThroughTheCallback() {
        val r = hello()
        assertEquals(LinkParseIpc.REPLY_OK, r.readInt())
        assertEquals(1, r.readInt())
        assertEquals(0, r.dataAvail())
        assertEquals(1, calledBack.get())
        callbackSays = 0
        val no = hello()
        assertEquals(LinkParseIpc.REPLY_OK, no.readInt())
        assertEquals(0, no.readInt())
    }

    @Test fun framesAreExact() {
        val plain = LinkRequests.encode(req(bodies()[0]))!!
        // The wrong interface, HELLO without its binder or with more after it, an unknown call.
        only(call(LinkParseIpc.HELLO) { it.writeInterfaceToken("app.heyrobot.Other"); it.writeStrongBinder(callback) }, LinkParseIpc.REPLY_REFUSED)
        only(call(LinkParseIpc.HELLO) { it.writeInterfaceToken(LinkParseIpc.DESCRIPTOR) }, LinkParseIpc.REPLY_REFUSED)
        only(call(LinkParseIpc.HELLO) { it.writeInterfaceToken(LinkParseIpc.DESCRIPTOR); it.writeStrongBinder(callback); it.writeInt(7) }, LinkParseIpc.REPLY_REFUSED)
        assertFalse(binder.transact(LinkParseIpc.EXIT + 1, Parcel.obtain(), Parcel.obtain(), 0))
        // DECODE: a frame of another length or one that lies about the region, no region, anything after it.
        only(decode(plain, frame = LinkParseWire.encodeRequest(LinkRequests.LIVE, plain.size) + 0), LinkParseIpc.REPLY_REFUSED)
        only(decode(plain, frame = LinkParseWire.encodeRequest(LinkRequests.LIVE, plain.size).copyOf(11)), LinkParseIpc.REPLY_REFUSED)
        only(decode(plain, frame = LinkParseWire.encodeRequest(LinkRequests.LIVE, plain.size + 512)), LinkParseIpc.REPLY_REFUSED)
        only(decode(plain, regionSize = plain.size + 512), LinkParseIpc.REPLY_REFUSED)
        only(decode(plain) { it.writeInt(0) }, LinkParseIpc.REPLY_REFUSED)
        only(call(LinkParseIpc.DECODE) { d -> d.writeInterfaceToken(LinkParseIpc.DESCRIPTOR); d.writeByteArray(LinkParseWire.encodeRequest(LinkRequests.LIVE, plain.size)); d.writeInt(0) },
            LinkParseIpc.REPLY_REFUSED)
        only(call(LinkParseIpc.DECODE) { d -> d.writeInterfaceToken(LinkParseIpc.DESCRIPTOR); d.writeInt(1_000_000) }, LinkParseIpc.REPLY_REFUSED)
    }

    @Test fun everyTypeIsAnsweredInAFrameLinkKeeps() {
        for (b in bodies()) {
            val plain = LinkRequests.encode(req(b))!!
            val r = decode(plain)
            assertEquals(LinkParseIpc.REPLY_OK, r.readInt())
            val frame = r.createByteArray()!!
            assertEquals(0, r.dataAvail())
            assertEquals(b.type.wire, req(b), (LinkParseWire.check(frame, plain, LinkRequests.LIVE) as RequestRead.Ok).request)
        }
    }

    private companion object {
        const val APP_UID = 10_123
    }
}
