// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainWire
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFormat
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/** BrainService's front door: what it answers each message with, and whom it doesn't answer at all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrainServiceTest {
    private class Got(val what: Int, val reqId: Int, val msg: BrainMsg?)

    private lateinit var service: ServiceController<BrainService>
    private lateinit var to: Messenger
    private lateinit var replies: HandlerThread
    private lateinit var reply: Messenger
    private val got = LinkedBlockingQueue<Got>()
    private var destroyed = false

    @Before fun start() {
        service = Robolectric.buildService(BrainService::class.java).create()
        to = Messenger(service.get().onBind(null))
        replies = HandlerThread("test-replies").apply { start() }
        reply = Messenger(Handler(replies.looper) { m -> got.put(Got(m.what, m.arg1, BrainWire.decode(m))); true })
    }

    @After fun stop() {
        ShadowBinder.reset()
        if (!destroyed) service.destroy()
        replies.quitSafely()
        replies.join(5_000)
    }

    private fun send(what: Int, id: Int, msg: BrainMsg) = to.send(BrainWire.message(what, id, msg).apply { replyTo = reply })
    private fun next(): Got = got.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no answer came")
    private fun error(g: Got) = (g.msg as BrainMsg.Error).code
    private fun load(format: ModelFormat) = BrainMsg.Load(File("/nowhere/" + BrainModel.OWN.file).path, BrainModel.OWN, ModelBackend.CPU, 4096, format)

    @Test fun anUnknownMessageIsABadRequestUnderItsOwnNumber() {
        to.send(Message.obtain(null, 9999).apply { arg1 = 7; replyTo = reply; data = Bundle().apply { putString(BrainProtocol.KEY_JSON, "{}") } })
        val g = next()
        assertEquals(BrainProtocol.EV_ERROR, g.what)
        assertEquals(7, g.reqId)
        assertEquals(BrainError.BAD_REQUEST, error(g))
    }

    @Test fun jsonThatDoesNotParseOrIsMissingIsABadRequest() {
        to.send(Message.obtain(null, BrainProtocol.REQ_LOAD).apply { arg1 = 1; replyTo = reply; data = Bundle().apply { putString(BrainProtocol.KEY_JSON, "{broken") } })
        assertEquals(BrainError.BAD_REQUEST, error(next()))
        to.send(Message.obtain(null, BrainProtocol.REQ_LOAD).apply { arg1 = 2; replyTo = reply; data = Bundle().apply { putString(BrainProtocol.KEY_JSON, "{}") } })
        assertEquals(BrainError.BAD_REQUEST, error(next()))
        to.send(Message.obtain(null, BrainProtocol.REQ_GENERATE).apply { arg1 = 3; replyTo = reply })
        assertEquals(BrainError.BAD_REQUEST, error(next()))
    }

    @Test fun aMessageWithNowhereToAnswerIsDropped() {
        to.send(BrainWire.message(BrainProtocol.REQ_STATUS, 1, BrainMsg.Empty))
        send(BrainProtocol.REQ_STATUS, 2, BrainMsg.Empty)
        assertEquals(2, next().reqId)
    }

    @Test fun anotherAppsMessageIsNeverAnswered() {
        ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)
        send(BrainProtocol.REQ_STATUS, 1, BrainMsg.Empty)
        send(BrainProtocol.REQ_LOAD, 2, load(ModelFormat.GGUF))
        ShadowBinder.reset()
        send(BrainProtocol.REQ_STATUS, 3, BrainMsg.Empty)
        assertEquals(3, next().reqId)
        assertNull(got.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun statusSaysNothingIsLoaded() {
        send(BrainProtocol.REQ_STATUS, 4, BrainMsg.Empty)
        val g = next()
        assertEquals(BrainProtocol.EV_STATUS, g.what)
        assertEquals(4, g.reqId)
        assertFalse((g.msg as BrainMsg.Status).loaded)
    }

    @Test fun aLoadOfAFileThatIsNotThereIsAnsweredForEachKind() {
        for (f in listOf(ModelFormat.LITERT_LM, ModelFormat.GGUF)) {
            send(BrainProtocol.REQ_LOAD, 5, load(f))
            val g = next()
            assertEquals(BrainProtocol.EV_LOADED, g.what)
            val l = g.msg as BrainMsg.Loaded
            assertFalse(l.ok)
            assertEquals(LoadFailure.NO_FILE, l.failure)
            assertEquals(f, l.format)
        }
    }

    @Test fun aQuestionWithNoModelIsNotLoadedAndAnUnloadIsAcknowledged() {
        send(BrainProtocol.REQ_GENERATE, 6, BrainMsg.Generate("c", "S", emptyList(), 0, "q", false))
        assertEquals(BrainError.NOT_LOADED, error(next()))
        send(BrainProtocol.REQ_UNLOAD, 7, BrainMsg.Empty)
        assertEquals(BrainProtocol.EV_UNLOADED, next().what)
    }

    @Test fun destroyingTheServiceEndsItsThread() {
        service.destroy()
        destroyed = true
        val brain = Thread.getAllStackTraces().keys.filter { it.name == "robot-brain" }
        brain.forEach { it.join(5_000) }
        assertEquals(emptyList<Thread>(), brain.filter { it.isAlive })
    }
}
