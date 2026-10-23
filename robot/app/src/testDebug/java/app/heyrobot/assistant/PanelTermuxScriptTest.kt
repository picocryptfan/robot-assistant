// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ControlRefusal
import app.heyrobot.core.DoneReason
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.termux.ScriptMatch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Your script named in your own words in the panel (PanelTermuxScript, from PanelController in 1.1): the reply names the
 * script first; the turn is exactly one termux_script call with the name and arguments you said, and the conversation's
 * trust is passed on as it was (after screen text or a message, a card only a tap answers); what the script printed is
 * streamed into the reply and said, and nothing else is; the turn's end, failure and an app it opened end the reply as
 * they say. What may run is TermuxTool's (TermuxGateTest); no test covered the panel's side before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelTermuxScriptTest {
    private val scope = MainScope()
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r1", TurnRole.ASSISTANT, "", streaming = true))))
    private val replies = PanelReplies(state)
    private val spoken = mutableListOf<String>()
    private val stream = PanelStream(scope, replies, object : PanelStream.Port {
        override var reqId = 0
        override val langs = Langs.EN
        override fun refused(f: ControlRefusal.Found) {}
        override fun speak(replyId: String, text: String) { spoken += text }
    })

    /** What the turn port did: the reply's text when the turn began, the model, whether it was untrusted. */
    private var textAtTurn: String? = null
    private var model: TurnModel? = null
    private var untrustedSeen: Boolean? = null
    /** The tool's result the turn hands the model's answer (null: the turn doesn't get that far). */
    private var result: Map<String, Any?>? = null
    private var answered: ModelOutcome? = null
    private var turn = TurnResult(DoneReason.END)
    /** What the port was asked after the turn, in order: "finish <id> <reason>", "fail <id> <text>", "leave". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    private val script = PanelTermuxScript(replies, stream, object : PanelTermuxScript.Port {
        override val turnLangs = Langs.EN
        override suspend fun termuxTurn(replyId: String, model: TurnModel, untrusted: Boolean): TurnResult {
            textAtTurn = replies.text(replyId)
            this@PanelTermuxScriptTest.model = model
            untrustedSeen = untrusted
            result?.let { answered = model.answer(listOf(ToolName.TERMUX_SCRIPT.id to it)) }
            return turn
        }
        override fun finishReply(replyId: String, reason: DoneReason) { calls += "finish $replyId $reason" }
        override fun failReply(replyId: String, message: String) { calls += "fail $replyId $message" }
        override suspend fun leaveForApp() { calls += "leave" }
    })

    @After fun end() = scope.cancel()

    @Test fun theTurnIsExactlyYourScriptWithYourArguments() = runBlocking {
        script.run("r1", ScriptMatch("backup", "photos --dry"), untrusted = true)
        assertEquals(PanelStrings.yourScript("name" to "backup").render(Langs.EN), textAtTurn)
        assertEquals(true, untrustedSeen)
        val calls0 = (model!!.ask() as ModelOutcome.Calls).calls
        assertEquals(1, calls0.size)
        assertEquals(ToolName.TERMUX_SCRIPT.id, calls0[0].name)
        assertEquals(mapOf("name" to "backup", "args" to "photos --dry"), calls0[0].args)
        assertEquals(listOf("finish r1 END"), calls)
        script.run("r1", ScriptMatch("backup", ""), untrusted = false)
        assertEquals(false, untrustedSeen)
    }

    @Test fun whatItPrintedIsSaidAndNothingElse() = runBlocking {
        result = mapOf("detail" to "Ran your script.", "results" to listOf(mapOf("output" to "3 files\n")))
        script.run("r1", ScriptMatch("backup", ""), untrusted = false)
        assertEquals(ModelOutcome.Done(DoneReason.END), answered)
        assertEquals(listOf("Ran your script: 3 files."), spoken)
        assertTrue(replies.text("r1")!!, replies.text("r1")!!.endsWith("Ran your script: 3 files."))
        // A result with no sentence of its own: nothing is said or added.
        spoken.clear()
        result = mapOf("status" to "done")
        script.run("r1", ScriptMatch("backup", ""), untrusted = false)
        assertEquals(emptyList<String>(), spoken)
        assertEquals(PanelStrings.yourScript("name" to "backup").render(Langs.EN), replies.text("r1"))
    }

    @Test fun theTurnsEndIsTheReplys() = runBlocking {
        turn = TurnResult(DoneReason.CANCELLED, launchedApp = true)
        script.run("r1", ScriptMatch("backup", ""), untrusted = false)
        assertEquals(listOf("finish r1 CANCELLED", "leave"), calls)
        calls.clear()
        turn = TurnResult(null, failure = "Termux isn't set up.", launchedApp = true)
        script.run("r1", ScriptMatch("backup", ""), untrusted = false)
        assertEquals(listOf("fail r1 Termux isn't set up."), calls)
        calls.clear()
        turn = TurnResult(null)
        script.run("r1", ScriptMatch("backup", ""), untrusted = false)
        assertEquals(listOf("finish r1 END"), calls)
    }
}
