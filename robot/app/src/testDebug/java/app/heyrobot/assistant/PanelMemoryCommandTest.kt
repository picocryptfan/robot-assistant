// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Handler
import android.os.Looper
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Cue
import app.heyrobot.core.DoneReason
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageQuarantine
import app.heyrobot.sys.FileMemoryVault
import app.heyrobot.sys.SoftMemoryKeys
import app.heyrobot.voice.SpeechToText
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

/**
 * Memory's commands in the panel (PanelMemoryCommand, from PanelController in 1.1). What is kept and found is MemoryPanel's
 * (MemoryPanelTest); here, the panel's side: without a message, "remember that …" is saved with its card under the reply;
 * after a message it is never saved at once but goes to the card's turn as your own words (a code is refused there), with
 * the check-and-tap line shown first; the lock the panel reads now goes to the command; several items that fit
 * "forget …" make a pick card that waits with the panel confirming and is answered by voice, and whatever ends the wait
 * declines that card and stops listening; your pick (even one to remember) is what is forgotten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelMemoryCommandTest {
    private class FakeStt : SpeechToText {
        override var available = true
        var starts = 0
        var cancels = 0
        override fun start(listener: SpeechToText.Listener, shortAnswer: Boolean) { starts++ }
        override fun stop() {}
        override fun cancel() { cancels++ }
        override fun release() {}
    }

    private val scope = MainScope()
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", streaming = true))))
    private val replies = PanelReplies(state)
    private val screen = PanelScreenShare(scope, state, { true }, { 0L }, { Langs.EN }) { true to true }
    private val stt = FakeStt()
    private val cardVoice = PanelCardVoice(state, replies, screen, object : PanelCardVoice.Port {
        override val main = Handler(Looper.getMainLooper())
        override val stt: SpeechToText get() = this@PanelMemoryCommandTest.stt
        override val settings = RobotSettings()
        override val speaking = false
        override val shown = true
        override val spokenTurn = true
        override val turnLangs = Langs.EN
        override fun screenLangs() = Langs.EN
        override val routineTapOnly: Pair<String, String>? = null
        override fun wakePaused(paused: Boolean) {}
        override fun notify(text: String) {}
        override fun speakRobot(text: String) {}
        override fun said(p: Phrase) = p.render(Langs.EN)
        override fun speak(text: String) {}
        override fun cue(c: Cue) {}
        override fun confirm(actionId: String, yes: Boolean) {}
        override fun choose(actionId: String, index: Int) {}
    })
    private val chat = mutableListOf<ChatTurn>()
    private val confirmations = Confirmations()
    private var locked = false
    private var ids = 0
    /** What the port was asked, in order: "reply <text> | <spoken>", "say <text>", "finish <id> <reason>", "fail <id> <text>". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it
    private val cardCalls = mutableListOf<RawToolCall>()
    private var textAtCard: String? = null
    private var cardTurn = TurnResult(DoneReason.END)

    private val memory = PanelMemoryCommand(state, replies, cardVoice, chat, object : PanelMemoryCommand.Port {
        override val turnLangs = Langs.EN
        override fun locked() = locked
        override val confirmations get() = this@PanelMemoryCommandTest.confirmations
        override fun newId() = "id${ids++}"
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) { calls += "reply ${text.render(Langs.EN)} | ${spoken.render(Langs.EN)}" }
        override fun localReply(replyId: String, text: String, spoken: String) { calls += "reply $text | $spoken" }
        override fun sayInto(replyId: String, text: String) {
            replies.setText(replyId, text)
            calls += "say $text"
        }
        override suspend fun memoryCardTurn(replyId: String, call: RawToolCall): TurnResult {
            textAtCard = replies.text(replyId)
            cardCalls += call
            return cardTurn
        }
        override fun finishReply(replyId: String, reason: DoneReason) { calls += "finish $replyId $reason" }
        override fun failReply(replyId: String, message: String) { calls += "fail $replyId $message" }
    })

    // Memory: a real vault with a software key (as MemoryPanelTest), put back afterwards.
    private val dir: File = Files.createTempDirectory("panel-memory").toFile()
    private val vault = FileMemoryVault(dir, SoftMemoryKeys())
    private val saved = listOf(MemoryPanel.vaultFor, MemoryPanel.settingsFor, MemoryPanel.lockedNow)
    private val savedClock = MemoryPanel.clock
    private val savedZone = MemoryPanel.zone

    @Before fun setUp() {
        MemoryPanel.vaultFor = { vault }
        MemoryPanel.settingsFor = { MemorySettings() }
        MemoryPanel.lockedNow = { false }
        MemoryPanel.clock = { 1_790_000_000_000L }
        MemoryPanel.zone = { ZoneOffset.UTC }
        MemoryPanel.drop()
        MemoryPanel.newConversation()
    }

    @Suppress("UNCHECKED_CAST")
    @After fun end() {
        scope.cancel()
        MemoryPanel.drop()
        MemoryPanel.newConversation()
        MemoryPanel.vaultFor = saved[0] as () -> app.heyrobot.core.memory.MemoryVault
        MemoryPanel.settingsFor = saved[1] as () -> MemorySettings
        MemoryPanel.lockedNow = saved[2] as () -> Boolean
        MemoryPanel.clock = savedClock
        MemoryPanel.zone = savedZone
        dir.deleteRecursively()
    }

    private fun items(): List<MemoryItem> = (vault.load() as? MemoryLoad.Loaded)?.file?.items.orEmpty()

    private fun route(q: String) = MemoryPanel.route(q, false) ?: throw AssertionError("not a memory command: $q")

    private fun cards() = state.value.turns.single { it.id == "r1" }.actions

    /** A message in the conversation (a turn that held one): the quarantine applies. */
    private fun afterAMessage() { chat += ChatTurn("m1", TurnRole.ASSISTANT, "Mom wrote: see you at 6", 0L, messages = true) }

    @Test fun withoutAMessageRememberIsSavedWithItsCard() = runBlocking {
        memory.run("r1", route("Remember that Julie likes tulips"), spoken = true)
        assertEquals(listOf("Julie likes tulips"), items().map { it.text })
        assertEquals(listOf("reply Got it: Julie likes tulips. | Got it: Julie likes tulips."), calls)
        val card = cards().single()
        assertEquals(ToolName.REMEMBER, card.tool)
        assertEquals("id0", card.id)
        assertTrue(cardCalls.isEmpty())
    }

    @Test fun afterAMessageRememberIsYourWordsCardNeverSavedAtOnce() = runBlocking {
        afterAMessage()
        memory.run("r1", route("Remember that Julie likes tulips"), spoken = true)
        assertTrue(items().isEmpty())
        assertEquals(MessageQuarantine.checkAndTapText().render(Langs.EN), textAtCard)
        assertEquals(ToolName.REMEMBER.id, cardCalls.single().name)
        assertEquals(listOf("finish r1 END"), calls)
        calls.clear()
        cardTurn = TurnResult(null, failure = "That didn't work.")
        memory.run("r1", route("Remember that Julie likes tulips"), spoken = true)
        assertEquals(listOf("fail r1 That didn't work."), calls)
        // A code after a message is refused there: nothing saved, no card's turn.
        calls.clear()
        memory.run("r1", route("Remember that the door code is 4512"), spoken = true)
        assertEquals(2, cardCalls.size)
        assertTrue(items().isEmpty())
        assertTrue(calls.toString(), calls.single().startsWith("reply "))
    }

    @Test fun theLockThePanelReadsNowGoesToTheCommand() = runBlocking {
        val r = route("Remember that Bob plays chess")
        locked = true
        memory.run("r1", r, spoken = true)
        assertTrue(items().isEmpty())
        assertEquals(listOf("reply Unlock first to save to memory. | Unlock first to save to memory."), calls)
    }

    private fun twoJulies() {
        MemoryPanel.command(route("Remember that Julie likes tulips"), false, true)
        MemoryPanel.command(route("Remember that Julie is vegetarian"), false, true)
        assertEquals(2, items().size)
    }

    /** "Forget Julie" run until its pick card waits; the card. */
    private suspend fun waitForPick(job: () -> Unit): UiAction {
        job()
        withTimeout(5_000) { while (!confirmations.waiting) delay(5) }
        return replies.pendingCard()!!
    }

    @Test fun severalThatFitMakeAPickCardAnsweredByVoiceAndYourPickIsForgotten() = runBlocking {
        twoJulies()
        val run = launch { memory.run("r1", route("Forget Julie"), spoken = true) }
        val card = waitForPick { }
        assertEquals(ToolName.FORGET, card.tool)
        assertEquals(listOf("Julie is vegetarian", "Julie likes tulips"), card.choices.sorted())
        assertEquals(Phase.CONFIRMING, state.value.phase)
        assertEquals(listOf("say Which one should I forget?"), calls)
        // Answered by voice: listening for it now.
        assertEquals(1, stt.starts)
        assertEquals(Phase.LISTENING, state.value.cardHearing)
        // A pick ticked to remember is still that pick.
        confirmations.choose(card.id, card.choices.indexOf("Julie likes tulips"), remember = true)
        run.join()
        assertEquals(listOf("Julie is vegetarian"), items().map { it.text })
        assertEquals(1, stt.cancels)
        assertNull(state.value.cardHearing)
        assertEquals(Phase.THINKING, state.value.phase)
        val left = cards()
        assertTrue(left.toString(), left.none { it.id == card.id })
        assertEquals("Forgotten", left.single().summary)
        assertEquals("", replies.text("r1"))
        assertEquals("reply Forgotten. | Forgotten.", calls.last())
    }

    @Test fun noKeepsThemAndAStoppedTurnDeclinesTheCard() = runBlocking {
        twoJulies()
        val run = launch { memory.run("r1", route("Forget Julie"), spoken = true) }
        val card = waitForPick { }
        confirmations.answer(card.id, false)
        run.join()
        assertEquals(2, items().size)
        assertEquals("reply Okay, I'll keep them. | Okay, I'll keep them.", calls.last())
        // The turn stopped while the card waits: the card is declined where it is, and Robot stops listening.
        calls.clear()
        val stopped = launch { memory.run("r1", route("Forget Julie"), spoken = true) }
        val again = waitForPick { }
        val cancelsBefore = stt.cancels
        stopped.cancelAndJoin()
        assertEquals(ActionStatus.DECLINED, cards().single { it.id == again.id }.status)
        assertEquals(cancelsBefore + 1, stt.cancels)
        assertEquals(2, items().size)
        assertEquals(listOf("say Which one should I forget?"), calls)
    }
}
