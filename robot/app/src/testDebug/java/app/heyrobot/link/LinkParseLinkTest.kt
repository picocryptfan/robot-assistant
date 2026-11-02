// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import app.heyrobot.core.link.LinkParseIpc
import app.heyrobot.core.link.LinkParseWire
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.linkparse.LinkParseService
import app.heyrobot.linkparse.LinkParser
import app.heyrobot.linkparse.LinkSamples
import app.heyrobot.linkparse.LinkSamples.T
import app.heyrobot.linkparse.LinkSamples.bodies
import app.heyrobot.linkparse.LinkSamples.req
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * :link's side of :linkparse (LinkParseLink, job L4) through real Parcels and SharedMemory regions: against the real
 * LinkParseService (its binder in this process, the calls' uids set as the kernel would: Robot's own uid calling it, an
 * isolated uid calling back), every type and random and damaged plaintexts end exactly where the decoder alone does; and
 * against a :linkparse that misbehaves (crashes, hangs, answers garbage, an exception header, a reply past its cap, another
 * request's answer, says it isn't isolated, calls back from a uid that isn't an isolated one), each request gets one answer,
 * PARSER at worst, the process is ended and the next request gets a fresh one; nothing is sent before the proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkParseLinkTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val worker = Executors.newSingleThreadExecutor()
    private val binds = AtomicInteger()
    private lateinit var fake: FakeParse
    private lateinit var link: LinkParseLink
    private lateinit var realService: IBinder

    private enum class Mode { CUT, REAL, CRASH, HANG, GARBAGE, LYING, EXCEPTION, NOT_ISOLATED, OVERSIZE, TRAILING, HELLO_NOT_ISOLATED_UID, HELLO_REFUSED }

    /** A stand-in :linkparse: the real service's binder behind the kernel's uids, or one that misbehaves. */
    private inner class FakeParse : Binder() {
        @Volatile var mode = Mode.REAL
        @Volatile var dead = false
        val hellos = AtomicInteger()
        val decodes = AtomicInteger()
        val exits = AtomicInteger()
        @Volatile var lastPlain: ByteArray? = null
        /** The real service's binder (made on the main thread, as Android makes services; each stand-in "process" uses it). */
        private val real: IBinder get() = realService

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (dead) throw DeadObjectException()
            if (code == LinkParseIpc.EXIT) { exits.incrementAndGet(); dead = true; return true }
            if (code == LinkParseIpc.HELLO) hellos.incrementAndGet() else decodes.incrementAndGet()
            if (code == LinkParseIpc.HELLO) return hello(data, reply!!)
            if (code == LinkParseIpc.DECODE && mode != Mode.REAL) return misbehave(data, reply!!)
            ShadowBinder.setCallingUid(APP_UID)
            return real.transact(code, data, reply, flags)
        }

        /** HELLO: the real service's, its call back arriving from an isolated uid (or, misbehaving, from another). */
        private fun hello(data: Parcel, reply: Parcel): Boolean {
            data.enforceInterface(LinkParseIpc.DESCRIPTOR)
            val cb = data.readStrongBinder()
            if (mode == Mode.HELLO_REFUSED) { reply.writeInt(LinkParseIpc.REPLY_OK); reply.writeInt(0); return true }
            val from = if (mode == Mode.HELLO_NOT_ISOLATED_UID) APP_UID else ISOLATED_UID
            val wrapped = object : Binder() {
                override fun onTransact(c: Int, d: Parcel, r: Parcel?, f: Int): Boolean {
                    ShadowBinder.setCallingUid(from)
                    try { return cb.transact(c, d, r, f) } finally { ShadowBinder.setCallingUid(APP_UID) }
                }
            }
            val d = Parcel.obtain()
            try {
                d.writeInterfaceToken(LinkParseIpc.DESCRIPTOR)
                d.writeStrongBinder(wrapped)
                ShadowBinder.setCallingUid(APP_UID)
                return real.transact(LinkParseIpc.HELLO, d, reply, 0)
            } finally {
                d.recycle()
            }
        }

        private fun misbehave(data: Parcel, reply: Parcel): Boolean {
            when (mode) {
                Mode.CRASH -> { dead = true; throw DeadObjectException() }
                Mode.HANG -> { while (!dead) Thread.sleep(5); throw DeadObjectException() }
                Mode.CUT -> { link.end("test"); return true }
                Mode.EXCEPTION -> { reply.writeException(IllegalStateException()); return true }
                Mode.NOT_ISOLATED -> { reply.writeInt(LinkParseIpc.REPLY_NOT_ISOLATED); return true }
                else -> {}
            }
            data.enforceInterface(LinkParseIpc.DESCRIPTOR)
            val frame = data.createByteArray()!!
            data.readInt()
            val region = SharedMemory.CREATOR.createFromParcel(data)
            val ask = LinkParseWire.decodeRequest(frame, region.size)!!
            val buf = region.mapReadOnly()
            val plain = ByteArray(ask.size).also { buf.get(it) }
            SharedMemory.unmap(buf)
            lastPlain = plain
            reply.writeInt(LinkParseIpc.REPLY_OK)
            when (mode) {
                Mode.GARBAGE -> reply.writeByteArray(ByteArray(60) { 0x52 })
                // Another request's answer, a well-formed one: the request kept must be the one sent.
                Mode.LYING -> reply.writeByteArray(LinkParser.answer(LinkRequests.encode(req(FbAsk(T, T + 600)))!!, ask.cap))
                Mode.OVERSIZE -> { reply.writeInt(LinkParseIpc.MAX_REPLY + 1); reply.writeInt(0) }
                Mode.TRAILING -> { reply.writeByteArray(LinkParser.answer(plain, ask.cap)); reply.writeInt(7) }
                else -> reply.writeByteArray(LinkParser.answer(plain, ask.cap))
            }
            return true
        }
    }

    @Before fun setUp() {
        ctx.applicationInfo.uid = APP_UID
        realService = Robolectric.buildService(LinkParseService::class.java).create().get().onBind(Intent())
        ShadowProcess.setIsIsolated()
        fake = FakeParse()
        link = LinkParseLink(ctx, 500, 1_000, 0) { _, _, _, executor, conn ->
            binds.incrementAndGet()
            // Each binding is a fresh process: a new stand-in in the mode the test set.
            val mode = fake.mode
            fake = FakeParse().also { it.mode = mode }
            val f = fake
            executor.execute { conn.onServiceConnected(ComponentName(ctx, "app.heyrobot.linkparse.LinkParseService"), f) }
            true
        }
    }

    @After fun tearDown() {
        fake.dead = true
        link.end("test")
        worker.shutdownNow()
        ShadowBinder.reset()
        ShadowProcess.reset()
    }

    /** The node calls from its own thread, never the main one. */
    private fun <T> off(block: () -> T): T = worker.submit<T> { block() }.get(10, TimeUnit.SECONDS)

    private fun decode(plain: ByteArray, cap: Int = LinkRequests.LIVE): RequestRead? = off { link.decode(plain, cap) }

    /** What :link keeps of [plain] when :linkparse is honest: what the parser answers, checked. */
    private fun honest(plain: ByteArray, cap: Int = LinkRequests.LIVE): RequestRead = LinkParseWire.check(LinkParser.answer(plain, cap), plain, cap)!!

    @Test fun everyTypeTravelsAndIsKept() {
        for (b in bodies()) {
            val plain = LinkRequests.encode(req(b))!!
            val r = (decode(plain) as RequestRead.Ok).request
            assertEquals(b.type.wire, req(b), r)
            if (b is CardOffer) assertEquals(LinkSamples.sam, (r.body as CardOffer).card)
        }
        // One process for all of them, proven once.
        assertEquals(1, binds.get())
        assertEquals(1, fake.hellos.get())
        assertEquals(bodies().size, fake.decodes.get())
        // A payment request stays unsupported.
        assertTrue(decode(LinkSamples.unread("pay.request")) is RequestRead.Unsupported)
    }

    /** Random and damaged plaintexts through the IPC: never an exception, and each ends where the decoder alone does. */
    @Test fun damagedAndRandomPlaintextsThroughTheIpc() {
        val r = Random(1507)
        for (b in bodies()) {
            val good = LinkRequests.encode(req(b))!!
            val len = LinkRequests.json(req(b)).toByteArray().size
            repeat(60) {
                val p = good.copyOf()
                repeat(1 + r.nextInt(3)) { val i = r.nextInt(len); p[i] = if (r.nextBoolean()) r.nextInt(256).toByte() else "{}[]\",:0123456789 \\".random(kotlin.random.Random(r.nextLong())).code.toByte() }
                assertSame(honest(p), decode(p))
            }
        }
        repeat(300) {
            val p = ByteArray(512 * (1 + r.nextInt(4))).also(r::nextBytes)
            assertSame(honest(p), decode(p))
        }
        // Still the first process: no answer of an honest one is ever refused.
        assertEquals(1, binds.get())
        // Lengths the decoder refuses by themselves are refused here, nothing sent.
        val before = fake.decodes.get()
        assertEquals(LinkProblem.BAD_PADDING, (decode(ByteArray(100)) as RequestRead.Bad).problem)
        assertEquals(LinkProblem.TOO_LONG, (decode(ByteArray(4_096), LinkRequests.PUSH_FIRST) as RequestRead.Bad).problem)
        assertEquals(before, fake.decodes.get())
    }

    private fun assertSame(want: RequestRead, got: RequestRead?) {
        when (want) {
            is RequestRead.Ok -> assertEquals(want.request, (got as RequestRead.Ok).request)
            is RequestRead.Unsupported -> assertEquals(want.id to want.newer, (got as RequestRead.Unsupported).let { it.id to it.newer })
            is RequestRead.Bad -> assertEquals(want.problem, (got as RequestRead.Bad).problem)
        }
    }

    /** Each misbehaviour: PARSER for that request, the process told to end, and a fresh one reads the next request. */
    /** F3: :link going to sleep under a read says nothing about the request: as no reader, not as a reader that failed on it. */
    @Test fun endingItDuringAReadIsNotAFailedRead() {
        val plain = LinkRequests.encode(req(bodies().single { it.type.wire == "ev.propose" }))!!
        fake.mode = Mode.CUT
        assertNull(decode(plain))
        fake.mode = Mode.REAL
        assertTrue(decode(plain) is RequestRead.Ok)
    }

    @Test fun aMisbehavingReaderIsEndedAndReplaced() {
        val ev = bodies().single { it.type.wire == "ev.propose" }
        val plain = LinkRequests.encode(req(ev))!!
        assertTrue(decode(plain) is RequestRead.Ok)
        for (m in listOf(Mode.CRASH, Mode.HANG, Mode.GARBAGE, Mode.LYING, Mode.EXCEPTION, Mode.NOT_ISOLATED, Mode.OVERSIZE, Mode.TRAILING)) {
            // The process already running starts misbehaving.
            fake.mode = m
            val bound = binds.get()
            val bad = fake
            assertEquals(m.name, LinkProblem.PARSER, (decode(plain) as RequestRead.Bad).problem)
            assertTrue(m.name, bad.exits.get() == 1 || bad.dead)
            // What it was asked about: exactly the plaintext, in a region of exactly its size.
            if (m == Mode.LYING || m == Mode.GARBAGE) assertArrayEquals(plain, bad.lastPlain)
            // The next request: a fresh, honest process.
            fake.mode = Mode.REAL
            assertEquals(m.name, req(ev), (decode(plain) as RequestRead.Ok).request)
            assertEquals(m.name, bound + 1, binds.get())
        }
    }

    @Test fun nothingIsSentBeforeTheProof() {
        val plain = LinkRequests.encode(req(bodies()[6]))!!
        for (m in listOf(Mode.HELLO_NOT_ISOLATED_UID, Mode.HELLO_REFUSED)) {
            fake.mode = m
            assertNull(m.name, decode(plain))
            assertEquals(m.name, 0, fake.decodes.get())
            assertEquals(m.name, 1, fake.exits.get())
        }
        // A binding that never connects: no answer, nothing sent, within the bind time.
        val never = LinkParseLink(ctx, 500, 300, 0) { _, _, _, _, _ -> true }
        assertNull(off { never.decode(plain, LinkRequests.LIVE) })
        val refused = LinkParseLink(ctx, 500, 300, 0) { _, _, _, _, _ -> false }
        assertNull(off { refused.decode(plain, LinkRequests.LIVE) })
    }

    /** One that can't start isn't tried again for every request (the node's thread would wait each time), only after a while or the next wake. */
    @Test fun aReaderThatCantStartIsTriedAgainLater() {
        val plain = LinkRequests.encode(req(bodies()[6]))!!
        val tries = AtomicInteger()
        val failing = LinkParseLink(ctx, 500, 300, 60_000) { _, _, _, _, _ -> tries.incrementAndGet(); false }
        assertNull(off { failing.decode(plain, LinkRequests.LIVE) })
        assertNull(off { failing.decode(plain, LinkRequests.LIVE) })
        assertEquals(1, tries.get())
        failing.end("sleep")
        assertNull(off { failing.decode(plain, LinkRequests.LIVE) })
        assertEquals(2, tries.get())
    }

    @Test fun endingItSendsExitAndTheNextRequestStartsAFreshOne() {
        val plain = LinkRequests.encode(req(bodies()[6]))!!
        assertTrue(decode(plain) is RequestRead.Ok)
        val first = fake
        link.end("sleep")
        assertEquals(1, first.exits.get())
        assertTrue(decode(plain) is RequestRead.Ok)
        assertEquals(2, binds.get())
        assertEquals(1, fake.hellos.get())
    }

    private companion object {
        const val APP_UID = 10_123
        const val ISOLATED_UID = 99_042
    }
}
