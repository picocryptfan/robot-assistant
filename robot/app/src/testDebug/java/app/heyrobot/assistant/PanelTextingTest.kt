// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.DoneReason
import app.heyrobot.core.Person
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TextCommand
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.WakeSensitivity
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.PeopleFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Texting without the model in the panel (PanelTexting, from PanelController in 1.1). What counts as a text is core
 * TextCommand's (TextCommandTest); here, the panel's side: a question that isn't one goes to the model; on the lock screen
 * (unless texting is allowed there) only the unlock line and no call; a whole text is send_text with the name and words
 * you said, the conversation's trust as it was, and the short line shown first; names learned from notifications never
 * decide where the message starts; "text mom" asks what it should say, waits on that name and listens for the answer the
 * way it was asked; the answer loses only a spoken wake phrase; "cancel" drops it; the turn's end, failure and an opened
 * app end the reply as they say. No test covered the panel's side before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelTextingTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", streaming = true))))
    private val replies = PanelReplies(state)
    private var settings = RobotSettings(wakePhrases = listOf(WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM, true)), lockedTools = emptySet())
    private var locked = false
    private var shown = true
    private var turn = TurnResult(DoneReason.END)
    /** What was sent to the turn, the reply's text then, and whether it was untrusted. */
    private val sent = mutableListOf<RawToolCall>()
    private var textAtTurn: String? = null
    private var untrustedSeen: Boolean? = null
    /**
     * What the port was asked, in order: "reply <text> | <spoken>", "asking <name>", "listenAfter <b>", "speak <s>",
     * "listen", "finish <id> <reason>", "fail <id> <text>", "leave".
     */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    private val texting = PanelTexting(state, replies, object : PanelTexting.Port {
        override val ctx get() = this@PanelTextingTest.ctx
        override val settings get() = this@PanelTextingTest.settings
        override val turnLangs = Langs.EN
        override fun locked() = locked
        override val shown get() = this@PanelTextingTest.shown
        override var askingTextFor: String?
            get() = throw AssertionError("texting only sets the name it waits on")
            set(v) { calls += "asking $v" }
        override var listenAfterSpeech: Boolean
            get() = throw AssertionError("texting only sets the listen after speaking")
            set(v) { calls += "listenAfter $v" }
        override fun said(p: Phrase) = "said: " + p.render(Langs.EN)
        override fun speakSentence(s: String) { calls += "speak $s" }
        override fun startListening() { calls += "listen" }
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) { calls += "reply ${text.render(Langs.EN)} | ${spoken.render(Langs.EN)}" }
        override suspend fun textTurn(replyId: String, call: RawToolCall, untrusted: Boolean): TurnResult {
            sent += call
            textAtTurn = replies.text(replyId)
            untrustedSeen = untrusted
            return turn
        }
        override fun finishReply(replyId: String, reason: DoneReason) { calls += "finish $replyId $reason" }
        override fun failReply(replyId: String, message: String) { calls += "fail $replyId $message" }
        override suspend fun leaveForApp() { calls += "leave" }
    })

    @Before fun clean() { PeopleFiles.store(ctx).deleteAll() }
    @After fun cleanAfter() { PeopleFiles.store(ctx).deleteAll() }

    private fun run(q: String, textingFor: String? = null, fromVoice: Boolean = false, untrusted: Boolean = false) =
        runBlocking { texting.direct("r1", q, textingFor, fromVoice, untrusted) }

    private fun speak(on: Boolean) = state.update { it.copy(speak = on) }

    @Test fun aQuestionThatIsntATextGoesToTheModel() {
        assertFalse(run("what's the weather"))
        // A name only the contacts could tell, and none Robot may read: the model.
        assertFalse(run("text Bob"))
        assertTrue(calls.isEmpty() && sent.isEmpty())
    }

    @Test fun onTheLockScreenOnlyTheUnlockLineUnlessTextingIsAllowedThere() {
        locked = true
        settings = settings.copy(lockedTools = emptySet())
        assertTrue(run("text mom I'm late"))
        val unlock = PanelStrings.unlockToText().render(Langs.EN)
        assertEquals(listOf("reply $unlock | $unlock"), calls)
        assertTrue(sent.isEmpty())
        calls.clear()
        settings = settings.copy(lockedTools = setOf(ToolName.SEND_TEXT))
        assertTrue(run("text mom I'm late"))
        assertEquals(1, sent.size)
    }

    @Test fun aWholeTextIsYourWordsCallWithItsShortLineFirst() {
        turn = TurnResult(DoneReason.END, launchedApp = true)
        assertTrue(run("text mom I'm running late", untrusted = true))
        assertEquals(RawToolCall(ToolName.SEND_TEXT.id, mapOf("to" to "mom", "message" to "I'm running late")), sent.single())
        assertEquals(PanelStrings.texting("name" to "Mom").render(Langs.EN), textAtTurn)
        assertEquals(true, untrustedSeen)
        assertEquals(listOf("finish r1 END", "leave"), calls)
        calls.clear()
        turn = TurnResult(null, failure = "No number for Mom.", launchedApp = true)
        assertTrue(run("text mom I'm running late"))
        assertEquals(false, untrustedSeen)
        assertEquals(listOf("fail r1 No number for Mom."), calls)
    }

    @Test fun namesLearnedFromNotificationsNeverDecideWhereTheMessageStarts() {
        PeopleFiles.store(ctx).save(Person("6f0c2a3e-0000-4000-8000-000000000001", "Mary Ann Smith"))
        assertTrue(run("text mary ann see you at six", fromVoice = true))
        assertEquals(mapOf("to" to "mary ann", "message" to "see you at six"), sent.last().args)
        PeopleFiles.store(ctx).deleteAll()
        PeopleFiles.store(ctx).save(Person("6f0c2a3e-0000-4000-8000-000000000001", "Mary Ann Smith", confirmed = false))
        assertTrue(run("text mary ann see you at six", fromVoice = true))
        assertEquals(mapOf("to" to "mary", "message" to "ann see you at six"), sent.last().args)
    }

    @Test fun textMomAsksAndListensTheWayItWasAsked() {
        val ask = TextCommand.askMessageText("mom")
        // Spoken replies, asked by voice: said, then listen once Robot has said it.
        speak(true)
        assertTrue(run("text mom", fromVoice = true))
        assertEquals(ask.render(Langs.EN), replies.text("r1"))
        assertEquals(listOf("asking mom", "speak said: ${ask.render(Langs.EN)}", "listenAfter true", "finish r1 END"), calls)
        // Typed: said, but no listening.
        calls.clear()
        assertTrue(run("text mom"))
        assertEquals(listOf("asking mom", "speak said: ${ask.render(Langs.EN)}", "listenAfter false", "finish r1 END"), calls)
        // Silent replies, asked by voice: listen right after the reply ends; not when typed or once the panel is gone.
        speak(false)
        calls.clear()
        assertTrue(run("text mom", fromVoice = true))
        assertEquals(listOf("asking mom", "finish r1 END", "listen"), calls)
        calls.clear()
        assertTrue(run("text mom"))
        shown = false
        assertTrue(run("text mom", fromVoice = true))
        assertEquals(listOf("asking mom", "finish r1 END", "asking mom", "finish r1 END"), calls)
        assertTrue(sent.isEmpty())
    }

    @Test fun theAnswerLosesOnlyASpokenWakePhraseAndCancelDropsIt() {
        assertTrue(run("hey robot I'm running late", textingFor = "mom", fromVoice = true))
        assertEquals(mapOf("to" to "mom", "message" to "I'm running late"), sent.last().args)
        assertTrue(run("hey robot I'm running late", textingFor = "mom"))
        assertEquals(mapOf("to" to "mom", "message" to "hey robot I'm running late"), sent.last().args)
        calls.clear()
        assertTrue(run("never mind", textingFor = "mom", fromVoice = true))
        assertEquals(2, sent.size)
        assertTrue(calls.toString(), calls.single().startsWith("reply "))
    }
}
