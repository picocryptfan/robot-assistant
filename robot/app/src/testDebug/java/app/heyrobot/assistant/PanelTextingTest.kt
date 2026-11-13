// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
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
import app.heyrobot.core.i18n.Lang
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
    /** The send_text card's status when the turn ends, as the panel's view would have set it (null: no card). */
    private var card: ActionStatus? = null
    /** What was sent to the turn, the reply's text then, and whether it was untrusted. */
    private val sent = mutableListOf<RawToolCall>()
    private var textAtTurn: String? = null
    private var untrustedSeen: Boolean? = null
    /**
     * What the port was asked, in order: "reply <text> | <spoken>", "asking <name>", "listenAfter <b>", "speak <s>",
     * "listen", "finish <id> <reason>", "fail <id> <text>", "leave".
     */
    private var langs = Langs.EN
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    /** 1.3: what the reply's card was to show, what the model is to answer a reword, and a reword said while the card waits. */
    private val marksAtTurn = mutableListOf<String?>()
    private var modelSays: String? = null
    private val rewords = ArrayDeque<String>()

    private val texting: PanelTexting = PanelTexting(state, replies, object : PanelTexting.Port {
        override val ctx get() = this@PanelTextingTest.ctx
        override val settings get() = this@PanelTextingTest.settings
        override val turnLangs get() = this@PanelTextingTest.langs
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
            marksAtTurn += PanelTexting.marks(replyId)
            val id = "a${sent.size}"
            fun show(c: ActionStatus) = state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(actions = it.actions.filter { a -> a.id != id } + UiAction(id, ToolName.SEND_TEXT, "Text Mom", c, null)) else it }) }
            rewords.removeFirstOrNull()?.let { said ->
                show(ActionStatus.PENDING)
                assertTrue(texting.reword(id, said))
                show(ActionStatus.DECLINED)
                return turn
            }
            card?.let(::show)
            return turn
        }
        override fun finishReply(replyId: String, reason: DoneReason) { calls += "finish $replyId $reason" }
        override fun failReply(replyId: String, message: String) { calls += "fail $replyId $message" }
        override suspend fun leaveForApp() { calls += "leave" }
    }, oneShot = { modelSays })

    init { app.heyrobot.sys.PeopleFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()) }
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

    /** LS12: "Texting Sam." stayed above a card you cancelled. A declined card takes the line away; a sent one keeps it. */
    @Test fun aDeclinedCardLeavesNoTextingLine() {
        card = ActionStatus.DECLINED
        assertTrue(run("text mom I'm running late"))
        assertEquals(PanelStrings.texting("name" to "Mom").render(Langs.EN), textAtTurn)
        assertEquals("", replies.text("r1"))
        card = ActionStatus.DONE
        assertTrue(run("text mom I'm running late"))
        assertEquals(PanelStrings.texting("name" to "Mom").render(Langs.EN), replies.text("r1"))
    }

    /** lp-check phase 2: with French menus, "Texting Sam." stayed English while the card waited. */
    @Test fun theTextingLineFollowsFrenchMenus() {
        langs = Langs(say = Lang.EN, ui = Lang.FR, system = Lang.EN)
        card = ActionStatus.DONE
        assertTrue(run("text mom I'm running late"))
        assertEquals("Texto à Mom.", textAtTurn)
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

    /** 1.3 (tidy-reword): fillers out, the last correction kept and marked on the card; none of it is sent without the card. */
    @Test fun aDictatedTextIsTidiedAndItsCorrectionMarked() {
        assertTrue(run("text Mom I'll be, uh, ten, no, fifteen minutes late"))
        assertEquals("I'll be fifteen minutes late", sent.single().args["message"])
        assertEquals("Check: “fifteen minutes” (you first said “ten”)", marksAtTurn.single())
        assertEquals(null, PanelTexting.marks("r1"))
    }

    /** 1.3: a reword while the card waits writes a new draft and a new card (the old one goes); it never sends. */
    @Test fun aRewordMakesANewCardWithTheFactsKept() {
        card = ActionStatus.DECLINED
        rewords += "warmer, and say I'm bringing dessert"
        modelSays = "Hi Mom! So sorry, I'll be 15 minutes late. I'm bringing dessert!"
        assertTrue(run("text Mom I'll be fifteen minutes late"))
        assertEquals(listOf("I'll be fifteen minutes late", "Hi Mom! So sorry, I'll be 15 minutes late. I'm bringing dessert!"), sent.map { it.args["message"] })
        assertEquals(listOf("a2"), state.value.turns.single().actions.map { it.id })
        // One that loses the time is kept out: the same draft comes back on its card, and Robot says why.
        sent.clear(); calls.clear()
        state.update { st -> st.copy(turns = st.turns.map { it.copy(actions = emptyList()) }) }
        rewords += "shorter"
        modelSays = "Running late, bringing dessert!"
        speak(true)
        assertTrue(run("text Mom I'll be fifteen minutes late"))
        assertEquals(listOf("I'll be fifteen minutes late", "I'll be fifteen minutes late"), sent.map { it.args["message"] })
        assertTrue(calls.any { it.startsWith("speak said: I couldn't reword it without changing") })
        // No card waits for this reply: a reword has nothing to change.
        assertFalse(texting.reword("a2", "warmer"))
    }

    @Test fun frenchIsTidiedToo() {
        // The answer to "what should the text say?", as French speech to text writes it.
        assertTrue(run("je serai là à euh cinq non six heures", textingFor = "mom", fromVoice = true))
        assertEquals("je serai là à six heures", sent.single().args["message"])
    }
}
