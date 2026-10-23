// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Looper
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.ControlRefusal
import app.heyrobot.core.DoneReason
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The model stream (PanelStream, from PanelController in 1.1): each piece of the reply is in the bubble before the voice
 * hears it, the request id is set for the whole request (Stop cancels it) and cleared after, a refused control text
 * reaches the turn, and a model that goes silent for a minute, before its first event or between two, is given up.
 * The watchdog runs in the panel's scope on the main looper, so the looper's clock drives it here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelStreamTest {
    private val scope = MainScope()
    private val looper = shadowOf(Looper.getMainLooper())
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", streaming = true))))
    private val replies = PanelReplies(state)
    private val client = BrainClient(RuntimeEnvironment.getApplication(), {})

    private inner class Port : PanelStream.Port {
        override var reqId = 0
        override val langs = Langs.EN
        var refused: ControlRefusal.Found? = null
        val spoken = ArrayList<String>()
        /** For each piece spoken: whether the bubble already ended with it, and the request id then. */
        val bubbleFirst = ArrayList<Boolean>()
        val idsThen = ArrayList<Int>()
        override fun refused(f: ControlRefusal.Found) { refused = f }
        override fun speak(replyId: String, text: String) {
            spoken += text
            bubbleFirst += replies.text(replyId).orEmpty().endsWith(text)
            idsThen += reqId
        }
    }

    private val port = Port()
    private val stream = PanelStream(scope, replies, port)

    @After fun end() = scope.cancel()

    @Suppress("UNCHECKED_CAST")
    private fun streams() = BrainClient::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(client) as MutableMap<Int, Channel<BrainMsg>>

    /** Request [id]'s events from [events] (then the end of the stream), collected on the main looper as a turn would. */
    private fun run(id: Int, vararg events: BrainMsg): () -> ModelOutcome? {
        val ch = Channel<BrainMsg>(Channel.UNLIMITED)
        events.forEach { ch.trySend(it) }
        ch.close()
        return start(id, ch)
    }

    private fun start(id: Int, ch: Channel<BrainMsg>): () -> ModelOutcome? {
        var out: ModelOutcome? = null
        scope.launch { out = stream.collect(client, id to ch.consumeAsFlow(), "r1") }
        looper.idle()
        return { out }
    }

    private fun done(reason: DoneReason) = BrainMsg.Done(reason, 1, 1, 1.0, false)

    @Test fun theWatchdogKeepsItsMinuteAndItsFiveSecondLook() {
        assertEquals(60_000L, PanelStream.EVENT_GAP_MS)
        assertEquals(5_000L, PanelStream.WATCHDOG_TICK_MS)
    }

    @Test fun eachPieceIsInTheBubbleBeforeTheVoiceHearsIt() {
        val reply = "Sure. The kettle is on, and the oven is warming up for the bread."
        val out = run(7, *reply.chunked(4).map { BrainMsg.Chunk(it) }.toTypedArray(), BrainMsg.Chunk(""), done(DoneReason.END))
        assertEquals(ModelOutcome.Done(DoneReason.END), out())
        assertEquals(reply, replies.text("r1"))
        assertEquals(Phase.ANSWERING, state.value.phase)
        // Everything shown is spoken, in order, each piece after it reached the bubble; the hold-back's flush too, while
        // the request is still the one under way.
        assertEquals(reply, port.spoken.joinToString(""))
        assertTrue(port.spoken.size > 1)
        assertTrue(port.bubbleFirst.toString(), port.bubbleFirst.all { it })
        assertEquals(List(port.spoken.size) { 7 }, port.idsThen)
        assertEquals(0, port.reqId)
    }

    @Test fun toolCallsRefusedControlTextAndErrorsComeBackAsBefore() {
        val calls = listOf(RawToolCall("set_timer", mapOf("minutes" to 5)))
        assertEquals(ModelOutcome.Calls(calls), run(1, BrainMsg.ToolCallsMsg(calls), done(DoneReason.TOOL_CALLS))())
        // A plain engine error: its own words, nothing refused.
        assertEquals(ModelOutcome.Failed(BrainError.ENGINE.textPhrase().render(Langs.EN)), run(2, BrainMsg.Error(BrainError.ENGINE, "decode"))())
        assertNull(port.refused)
        // llama.cpp refused a control token's text in the question: the turn hears what held it, and the reply says so.
        val found = ControlRefusal.Found(question = true)
        assertEquals(ModelOutcome.Failed(ControlRefusal.text(found).render(Langs.EN)),
            run(3, BrainMsg.Error(BrainError.ENGINE, ControlRefusal.detail(found)))())
        assertEquals(found, port.refused)
        // No terminal event at all: the model didn't answer.
        assertEquals(ModelOutcome.Failed(PanelStrings.modelDidntAnswer().render(Langs.EN)), run(4)())
        assertEquals(0, port.reqId)
        assertEquals("", replies.text("r1"))
    }

    @Test fun aModelSilentForAMinuteIsGivenUp() {
        val ch = Channel<BrainMsg>(Channel.UNLIMITED)
        streams()[9] = ch
        val out = start(9, ch)
        assertEquals(9, port.reqId)
        // 50 s without a word, then one: the minute starts again from it.
        looper.idleFor(Duration.ofSeconds(50))
        ch.trySend(BrainMsg.Chunk("Hm"))
        looper.idle()
        looper.idleFor(Duration.ofSeconds(55))
        assertNull(out())
        assertEquals(9, port.reqId)
        assertTrue(streams().containsKey(9))
        // A minute after that word: cancelled at :brain, abandoned here, and the reply says the model took too long.
        looper.idleFor(Duration.ofSeconds(10))
        looper.idle()
        assertEquals(ModelOutcome.Failed(PanelStrings.modelTimedOut().render(Langs.EN)), out())
        assertFalse(streams().containsKey(9))
        assertEquals(0, port.reqId)
        // What came before the silence stays in the bubble (the hold-back's flush included).
        assertEquals("Hm", replies.text("r1"))
    }
}
