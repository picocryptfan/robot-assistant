// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.mt

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import app.heyrobot.core.LangPair
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtError
import app.heyrobot.core.MtMsg
import app.heyrobot.core.MtPolicy
import app.heyrobot.core.MtPriority
import app.heyrobot.core.MtProtocol
import app.heyrobot.core.MtWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/** MtService with a stand-in for the library: what it answers, in what order it works, and what it frees. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MtServiceTest {
    private class Got(val what: Int, val reqId: Int, val msg: MtMsg?)

    private val slimt = FakeSlimt()
    private val b = MtBundle.FR_EN
    private val dir get() = b.directions.first().id
    private lateinit var service: ServiceController<MtService>
    private lateinit var to: Messenger
    private lateinit var replies: HandlerThread
    private lateinit var reply: Messenger
    private val got = LinkedBlockingQueue<Got>()
    private var destroyed = false

    @Before fun start() {
        MtService.native = slimt
        service = Robolectric.buildService(MtService::class.java).create()
        to = Messenger(service.get().onBind(null))
        replies = HandlerThread("test-replies").apply { start() }
        reply = Messenger(Handler(replies.looper) { m -> got.put(Got(m.what, m.arg1, MtWire.decode(m))); true })
    }

    @After fun stop() {
        ShadowBinder.reset()
        if (!destroyed) service.destroy()
        replies.quitSafely()
        replies.join(5_000)
        MtService.native = Slimt
    }

    private fun send(what: Int, id: Int, m: MtMsg) = to.send(MtWire.message(what, id, m).apply { replyTo = reply })
    private fun next(): Got = got.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no answer came")
    private fun error(g: Got): MtError {
        assertEquals(MtProtocol.EV_ERROR, g.what)
        return (g.msg as MtMsg.Error).error
    }
    private fun translate(id: Int, vararg lines: String, deadline: Long = 60_000, priority: MtPriority = MtPriority.BATCH) =
        send(MtProtocol.REQ_TRANSLATE, id, MtMsg.Translate(dir, lines.toList(), deadline, priority))
    private fun status(): MtMsg.Status {
        send(MtProtocol.REQ_STATUS, 900, MtMsg.Empty)
        return next().msg as MtMsg.Status
    }
    private fun load() {
        install(b, service.get().filesDir)
        send(MtProtocol.REQ_LOAD, 800, MtMsg.Load(LangPair.FR_EN.id))
        assertEquals(MtProtocol.EV_LOADED, next().what)
    }

    // ---------------------------------------------------------------- what comes in

    @Test fun anUnknownMessageOrBadJsonIsABadRequest() {
        to.send(Message.obtain(null, 9999).apply { arg1 = 3; replyTo = reply; data = Bundle().apply { putString(MtProtocol.KEY_JSON, "{}") } })
        val g = next()
        assertEquals(3, g.reqId)
        assertEquals(MtError.BAD_REQUEST, error(g))
        to.send(Message.obtain(null, MtProtocol.REQ_TRANSLATE).apply { replyTo = reply; data = Bundle().apply { putString(MtProtocol.KEY_JSON, "{broken") } })
        assertEquals(MtError.BAD_REQUEST, error(next()))
    }

    @Test fun aPairWithNoBundleIsABadRequest() {
        send(MtProtocol.REQ_LOAD, 1, MtMsg.Load("xx-yy"))
        assertEquals(MtError.BAD_REQUEST, error(next()))
    }

    @Test fun aTranslateOutsideItsLimitsIsABadRequestAndNeverQueued() {
        for (m in listOf(
            MtMsg.Translate("xx-yy", listOf("a"), 1000, MtPriority.BATCH),
            MtMsg.Translate(dir, emptyList(), 1000, MtPriority.BATCH),
            MtMsg.Translate(dir, List(MtPolicy.MAX_LINES + 1) { "a" }, 1000, MtPriority.BATCH),
            MtMsg.Translate(dir, listOf("a".repeat(MtPolicy.MAX_CHARS + 1)), 1000, MtPriority.BATCH),
            MtMsg.Translate(dir, List(17) { "a".repeat(MtPolicy.MAX_CHARS) }, 1000, MtPriority.BATCH),
            MtMsg.Translate(dir, listOf("a"), 0, MtPriority.BATCH),
            MtMsg.Translate(dir, listOf("a"), MtPolicy.MAX_DEADLINE_MS + 1, MtPriority.BATCH),
        )) {
            send(MtProtocol.REQ_TRANSLATE, 1, m)
            assertEquals(MtError.BAD_REQUEST, error(next()))
        }
        assertEquals(0, status().waiting)
    }

    @Test fun anotherAppIsNeverAnswered() {
        ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)
        send(MtProtocol.REQ_STATUS, 1, MtMsg.Empty)
        translate(2, "a")
        ShadowBinder.reset()
        assertEquals(900, run { send(MtProtocol.REQ_STATUS, 900, MtMsg.Empty); next().reqId })
        assertNull(got.poll(100, TimeUnit.MILLISECONDS))
        assertEquals(0, slimt.lines.size)
    }

    @Test fun aMessageWithNowhereToAnswerIsDropped() {
        to.send(MtWire.message(MtProtocol.REQ_STATUS, 1, MtMsg.Empty))
        assertEquals(900, run { send(MtProtocol.REQ_STATUS, 900, MtMsg.Empty); next().reqId })
    }

    // ---------------------------------------------------------------- loads

    @Test fun statusSaysWhetherTheEngineIsHereAndWhatIsInMemory() {
        assertTrue(status().engine)
        assertEquals(emptyList<String>(), status().loaded)
        slimt.available = false
        assertFalse(status().engine)
    }

    @Test fun aLoadWithoutTheLibraryIsNoEngine() {
        slimt.available = false
        send(MtProtocol.REQ_LOAD, 1, MtMsg.Load(LangPair.FR_EN.id))
        assertEquals(MtError.NO_ENGINE, error(next()))
    }

    @Test fun aLoadWithoutItsFilesIsNotDownloaded() {
        send(MtProtocol.REQ_LOAD, 1, MtMsg.Load(LangPair.FR_EN.id))
        assertEquals(MtError.NOT_DOWNLOADED, error(next()))
        assertEquals(0, slimt.opened.size)
    }

    @Test fun aLoadTheLibraryThrowsOnIsAFailureNotACrash() {
        install(b, service.get().filesDir)
        slimt.onOpen = { throw OutOfMemoryError("model") }
        send(MtProtocol.REQ_LOAD, 1, MtMsg.Load(LangPair.FR_EN.id))
        assertEquals(MtError.LOAD_FAILED, error(next()))
        slimt.onOpen = { true }
        load()
        assertEquals(b.directions.size, status().loaded.size)
    }

    @Test fun aLoadSaysWhatItLoaded() {
        install(b, service.get().filesDir)
        send(MtProtocol.REQ_LOAD, 5, MtMsg.Load(LangPair.FR_EN.id))
        val g = next()
        assertEquals(5, g.reqId)
        val l = g.msg as MtMsg.Loaded
        assertEquals(LangPair.FR_EN.id, l.pair)
        assertEquals(b.directions.map { it.id }.toSet(), l.ms.keys)
        assertEquals(b.directions.map { it.id }, status().loaded)
    }

    // ---------------------------------------------------------------- lines

    @Test fun aTranslateLoadsOnDemandAndAnswersEveryLineInOrder() {
        install(b, service.get().filesDir)
        translate(1, "un", "deux", "trois")
        val g = next()
        assertEquals(MtProtocol.EV_TRANSLATED, g.what)
        assertEquals(1, g.reqId)
        val t = g.msg as MtMsg.Translated
        assertEquals(listOf("UN", "DEUX", "TROIS"), t.out)
        assertEquals(3, t.ms.size)
        assertEquals(0, t.failed)
        assertEquals(0, t.timedOut)
    }

    @Test fun aTranslateWithNoEngineOrFilesEndsWithThatError() {
        translate(1, "un", "deux")
        assertEquals(MtError.NOT_DOWNLOADED, error(next()))
        slimt.available = false
        translate(2, "un")
        assertEquals(MtError.NO_ENGINE, error(next()))
        assertEquals(0, slimt.lines.size)
    }

    @Test fun aLineThatFailsOrThrowsIsCountedAndTheRestGoOn() {
        load()
        slimt.onTranslate = { l -> when (l) { "nul" -> null; "bad" -> throw IllegalStateException(l); else -> l } }
        translate(1, "a", "nul", "bad", "b")
        val t = next().msg as MtMsg.Translated
        assertEquals(listOf("a", null, null, "b"), t.out)
        assertEquals(2, t.failed)
    }

    /** Holds the worker in its first line until [go]. */
    private fun holding(): Pair<CountDownLatch, CountDownLatch> {
        val inLine = CountDownLatch(1)
        val go = CountDownLatch(1)
        slimt.onTranslate = { l ->
            inLine.countDown()
            go.await(10, TimeUnit.SECONDS)
            l
        }
        return inLine to go
    }

    @Test fun anInteractiveLineOvertakesABacklogBetweenTwoOfItsLines() {
        load()
        val (inLine, go) = holding()
        translate(1, "b1", "b2", "b3")
        assertTrue(inLine.await(10, TimeUnit.SECONDS))
        translate(2, "now", priority = MtPriority.INTERACTIVE)
        assertEquals(2 + 1, status().waiting)
        go.countDown()
        val first = next()
        assertEquals(2, first.reqId)
        assertEquals(1, next().reqId)
        assertEquals(listOf("b1", "now", "b2", "b3"), slimt.lines.toList())
    }

    @Test fun aCancelStopsTheLinesNotStartedAndAnswersWhatWasDone() {
        load()
        val (inLine, go) = holding()
        translate(1, "b1", "b2", "b3")
        assertTrue(inLine.await(10, TimeUnit.SECONDS))
        send(MtProtocol.REQ_CANCEL, 2, MtMsg.Cancel(1))
        status()
        go.countDown()
        val t = next().msg as MtMsg.Translated
        assertEquals(listOf("b1", null, null), t.out)
        assertEquals(2, t.timedOut)
        assertEquals(listOf("b1"), slimt.lines.toList())
    }

    @Test fun aCancelFromAnotherClientOrForNoRequestStopsNothing() {
        load()
        val (inLine, go) = holding()
        translate(1, "b1", "b2")
        assertTrue(inLine.await(10, TimeUnit.SECONDS))
        send(MtProtocol.REQ_CANCEL, 2, MtMsg.Cancel(99))
        val other = HandlerThread("test-other").apply { start() }
        to.send(MtWire.message(MtProtocol.REQ_CANCEL, 3, MtMsg.Cancel(1)).apply { replyTo = Messenger(Handler(other.looper)) })
        status()
        go.countDown()
        assertEquals(listOf("b1", "b2"), (next().msg as MtMsg.Translated).out)
        other.quitSafely()
    }

    @Test fun moreThanTheQueueHoldsIsBusy() {
        load()
        val (inLine, go) = holding()
        translate(1, "held")
        assertTrue(inLine.await(10, TimeUnit.SECONDS))
        repeat(MtPolicy.MAX_WAITING / MtPolicy.MAX_LINES) { i ->
            send(MtProtocol.REQ_TRANSLATE, 10 + i, MtMsg.Translate(dir, List(MtPolicy.MAX_LINES) { "a" }, 60_000, MtPriority.BATCH))
        }
        translate(2, "one too many")
        val g = next()
        assertEquals(2, g.reqId)
        assertEquals(MtError.BUSY, error(g))
        go.countDown()
    }

    // ---------------------------------------------------------------- letting go

    @Test fun anUnloadCancelsWhatWaitsThenAnswersAndFrees() {
        load()
        val (inLine, go) = holding()
        translate(1, "held", "next")
        assertTrue(inLine.await(10, TimeUnit.SECONDS))
        translate(2, "waits")
        send(MtProtocol.REQ_UNLOAD, 3, MtMsg.Empty)
        status()
        go.countDown()
        val all = listOf(next(), next(), next())
        assertEquals(setOf(1, 2), all.filter { it.what == MtProtocol.EV_ERROR }.map { it.reqId }.toSet())
        assertTrue(all.filter { it.what == MtProtocol.EV_ERROR }.all { error(it) == MtError.CANCELLED })
        assertEquals(MtProtocol.EV_UNLOADED, all.last().what)
        assertEquals(b.directions.size, slimt.closed.size)
        assertEquals(emptyList<String>(), status().loaded)
    }

    @Test fun theLastClientLeavingFreesTheEngine() {
        load()
        service.get().onUnbind(null)
        send(MtProtocol.REQ_UNLOAD, 3, MtMsg.Empty)
        assertEquals(MtProtocol.EV_UNLOADED, next().what)
        assertEquals(b.directions.size, slimt.closed.size)
    }

    @Test fun onlyMemoryPressureInTheBackgroundFreesTheEngine() {
        load()
        val closedBefore = slimt.closed.size
        service.get().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        // A load goes through the same line as an unload would: when it answers, a queued unload has run.
        load()
        assertEquals(closedBefore, slimt.closed.size)
        service.get().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        send(MtProtocol.REQ_UNLOAD, 3, MtMsg.Empty)
        assertEquals(MtProtocol.EV_UNLOADED, next().what)
        assertEquals(b.directions.size, slimt.closed.size)
    }

    @Test fun anUnboundLeavesAWaitingLineAlone() {
        load()
        val (inLine, go) = holding()
        translate(1, "held", "next")
        assertTrue(inLine.await(10, TimeUnit.SECONDS))
        service.get().onUnbind(null)
        status()
        go.countDown()
        assertEquals(listOf("held", "next"), (next().msg as MtMsg.Translated).out)
        assertEquals(0, slimt.closed.size)
    }

    @Test fun destroyingTheServiceCancelsWhatWaitsAndFrees() {
        load()
        val (inLine, go) = holding()
        translate(1, "held", "next")
        assertTrue(inLine.await(10, TimeUnit.SECONDS))
        service.destroy()
        destroyed = true
        go.countDown()
        assertEquals(MtError.CANCELLED, error(next()))
        val threads = Thread.getAllStackTraces().keys.filter { it.name == "robot-mt" || it.name == "robot-mt-inbox" }
        threads.forEach { it.join(5_000) }
        assertEquals(emptyList<Thread>(), threads.filter { it.isAlive })
        assertEquals(b.directions.size, slimt.closed.size)
    }
}
