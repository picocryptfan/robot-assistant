package app.heyrobot.assistant

import android.os.Message
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainWire
import app.heyrobot.core.DoneReason
import app.heyrobot.core.ProblemStage
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The assistant's side of :brain's replies: "The model stopped unexpectedly" (BrainError.STOPPED: llama.cpp's isolated
 * process stopped under a reply) is news of the file it ran, whether or not anybody still waits for that reply. 0.6.0 with
 * 0.4.7: where each request is on the model (one-shots too), and an engine error under one reaches its waiter, once,
 * before the GPU fallback hears of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrainClientTest {
    private var stopped = 0
    private val client = BrainClient(RuntimeEnvironment.getApplication(), {}, { stopped++ })
    private val onReply = BrainClient::class.java.getDeclaredMethod("onReply", Message::class.java).apply { isAccessible = true }

    private fun reply(what: Int, id: Int, m: BrainMsg, to: BrainClient = client) {
        onReply.invoke(to, BrainWire.message(what, id, m))
    }

    @Suppress("UNCHECKED_CAST")
    private fun streams(c: BrainClient) = BrainClient::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(c) as MutableMap<Int, Channel<BrainMsg>>

    @Suppress("UNCHECKED_CAST")
    private fun tracked(c: BrainClient) = BrainClient::class.java.getDeclaredField("tracked").apply { isAccessible = true }.get(c) as MutableSet<Int>

    /** A request [id] sent as [c.request] would (its waiter's channel, followed as one the model answers), without a binding. */
    private fun open(c: BrainClient, id: Int): Channel<BrainMsg> = Channel<BrainMsg>(Channel.UNLIMITED).also {
        streams(c)[id] = it
        tracked(c) += id
    }

    private fun drain(ch: Channel<BrainMsg>): List<BrainMsg> = generateSequence { ch.tryReceive().getOrNull() }.toList()

    @Test fun aStoppedModelIsNewsEvenForAReplyNobodyWaitsFor() {
        // A request the panel abandoned (no event for a minute), then ended by :brain's watchdog.
        reply(BrainProtocol.EV_ERROR, 41, BrainMsg.Error(BrainError.STOPPED, "model process stopped"))
        assertEquals(1, stopped)
        // A reply still awaited too, once, and its waiter gets the error.
        val ch = Channel<BrainMsg>(Channel.UNLIMITED)
        @Suppress("UNCHECKED_CAST")
        (BrainClient::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(client) as MutableMap<Int, Channel<BrainMsg>>)[7] = ch
        reply(BrainProtocol.EV_ERROR, 7, BrainMsg.Error(BrainError.STOPPED, "model process stopped"))
        assertEquals(2, stopped)
        assertEquals(BrainError.STOPPED, (ch.tryReceive().getOrNull() as BrainMsg.Error).code)
        // Anything else isn't.
        reply(BrainProtocol.EV_ERROR, 42, BrainMsg.Error(BrainError.ENGINE, "decode"))
        reply(BrainProtocol.EV_DONE, 43, BrainMsg.Done(DoneReason.CANCELLED, -1, -1, -1.0, false))
        assertEquals(2, stopped)
    }

    /**
     * A translation (a one-shot) waits behind the panel's question, starts, writes, and the engine fails it on the GPU: the
     * GPU fallback hears of it at the stage it was at (writing), and only after the one-shot's waiter has its error, its
     * one terminal event, in a closed channel. A late event for it reaches nobody and changes nothing.
     */
    @Test fun anEngineErrorUnderAOneShotEndsItOnceBeforeTheFallbackHears() {
        val heard = ArrayList<ProblemStage>()
        val seenByThen = ArrayList<List<BrainMsg>>()
        lateinit var ch: Channel<BrainMsg>
        val c = BrainClient(RuntimeEnvironment.getApplication(), {}, {}, { stage ->
            heard += stage
            // What the waiter has when the fallback starts (and may let go of the binding).
            seenByThen += drain(ch)
        })
        val question = open(c, 1)
        ch = open(c, 2)
        reply(BrainProtocol.EV_QUEUED, 1, BrainMsg.Queued(0), c)
        reply(BrainProtocol.EV_QUEUED, 2, BrainMsg.Queued(1), c)
        assertEquals(ProblemStage.READING, c.stage)
        reply(BrainProtocol.EV_CHUNK, 1, BrainMsg.Chunk("It's"), c)
        assertEquals(ProblemStage.WRITING, c.stage)
        reply(BrainProtocol.EV_DONE, 1, BrainMsg.Done(DoneReason.END, 10, 100, 12.0, false), c)
        // Only the translation, still waiting: between answers.
        assertNull(c.stage)
        reply(BrainProtocol.EV_QUEUED, 2, BrainMsg.Queued(0), c)
        assertEquals(ProblemStage.READING, c.stage)
        reply(BrainProtocol.EV_CHUNK, 2, BrainMsg.Chunk("Bonjour"), c)
        reply(BrainProtocol.EV_ERROR, 2, BrainMsg.Error(BrainError.ENGINE, "OpenCL"), c)
        assertEquals(listOf(ProblemStage.WRITING), heard)
        val got = seenByThen.single()
        assertEquals(1, got.count { it is BrainMsg.Error || it is BrainMsg.Done })
        assertEquals(BrainError.ENGINE, (got.last() as BrainMsg.Error).code)
        assertTrue(ch.isClosedForSend)
        assertNull(c.stage)
        // Late news of it: nobody waits, nothing is heard again.
        reply(BrainProtocol.EV_DONE, 2, BrainMsg.Done(DoneReason.CANCELLED, -1, -1, -1.0, false), c)
        reply(BrainProtocol.EV_ERROR, 2, BrainMsg.Error(BrainError.ENGINE, "again"), c)
        assertEquals(1, heard.size)
        // The panel's question before it: its own events, one end, untouched by the translation's failure.
        assertEquals(listOf(BrainMsg.Queued(0), BrainMsg.Chunk("It's")), drain(question).dropLast(1))
        assertTrue(question.isClosedForSend)
    }

    /** An engine error under a request waiting in :brain's queue (never on the model) is never the GPU failing. */
    @Test fun anErrorForAWaitingRequestIsNotTheModels() {
        val heard = ArrayList<ProblemStage>()
        val c = BrainClient(RuntimeEnvironment.getApplication(), {}, {}, { heard += it })
        val ch = open(c, 3)
        reply(BrainProtocol.EV_QUEUED, 3, BrainMsg.Queued(2), c)
        reply(BrainProtocol.EV_ERROR, 3, BrainMsg.Error(BrainError.ENGINE, null), c)
        assertEquals(emptyList<ProblemStage>(), heard)
        assertEquals(2, drain(ch).size)
    }

    /**
     * :brain stops while a one-shot writes and the panel's question waits: the death is at the writing stage (the GPU's
     * fallback, when it ran there), and every open request ends exactly once.
     */
    @Test fun aDeathUnderAOneShotEndsEveryRequestOnce() {
        val c = BrainClient(RuntimeEnvironment.getApplication(), {}, {})
        val oneShot = open(c, 4)
        val question = open(c, 5)
        reply(BrainProtocol.EV_QUEUED, 4, BrainMsg.Queued(0), c)
        reply(BrainProtocol.EV_CHUNK, 4, BrainMsg.Chunk("Hello"), c)
        reply(BrainProtocol.EV_QUEUED, 5, BrainMsg.Queued(1), c)
        assertEquals(ProblemStage.WRITING, c.stage)
        BrainClient::class.java.getDeclaredMethod("endAll").apply { isAccessible = true }.invoke(c)
        for (ch in listOf(oneShot, question)) {
            val got = drain(ch)
            assertEquals(1, got.count { it is BrainMsg.Error || it is BrainMsg.Done })
            assertTrue(got.last() is BrainMsg.Error)
            assertTrue(ch.isClosedForSend)
        }
        assertNull(c.stage)
        assertTrue(tracked(c).isEmpty() && streams(c).isEmpty())
    }
}
