package app.heyrobot.assistant

import android.os.Message
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainWire
import app.heyrobot.core.DoneReason
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The assistant's side of :brain's replies: "The model stopped unexpectedly" (BrainError.STOPPED: llama.cpp's isolated
 * process stopped under a reply) is news of the file it ran, whether or not anybody still waits for that reply.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrainClientTest {
    private var stopped = 0
    private val client = BrainClient(RuntimeEnvironment.getApplication(), {}, { stopped++ })
    private val onReply = BrainClient::class.java.getDeclaredMethod("onReply", Message::class.java).apply { isAccessible = true }

    private fun reply(what: Int, id: Int, m: BrainMsg) {
        onReply.invoke(client, BrainWire.message(what, id, m))
    }

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
}
