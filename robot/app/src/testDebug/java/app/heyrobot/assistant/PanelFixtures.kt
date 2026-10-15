// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.LookSettings
import app.heyrobot.core.Tip
import app.heyrobot.core.TipKind
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.theme.RobotTheme

/** Every PanelActions call, by name and arguments ("confirm a1 true"), so a test sees which one a tap asked for. */
class RecordingActions : PanelActions {
    val calls = ArrayList<String>()
    /** What send answers (true: the question was taken). */
    var accept = true

    override fun send(text: String): Boolean { calls += "send $text"; return accept }
    override fun askAboutScreen(question: String) { calls += "askAboutScreen $question" }
    override fun keepConversation() { calls += "keepConversation" }
    override fun toggleSpeak() { calls += "toggleSpeak" }
    override fun newConversation() { calls += "newConversation" }
    override fun dismissNotice() { calls += "dismissNotice" }
    override fun openRobotApp() { calls += "openRobotApp" }
    override fun openAssistSettings() { calls += "openAssistSettings" }
    override fun openModelSettings() { calls += "openModelSettings" }
    override fun openPermissions() { calls += "openPermissions" }
    override fun openSettings() { calls += "openSettings" }
    override fun attachScreen() { calls += "attachScreen" }
    override fun removeDraft() { calls += "removeDraft" }
    override fun openCapture(mode: String) { calls += "openCapture $mode" }
    override fun readClaudeAnswer() { calls += "readClaudeAnswer" }
    override fun micPressed() { calls += "micPressed" }
    override fun micReleased() { calls += "micReleased" }
    override fun stopGenerating() { calls += "stopGenerating" }
    override fun stopSpeaking() { calls += "stopSpeaking" }
    override fun confirm(actionId: String, yes: Boolean) { calls += "confirm $actionId $yes" }
    override fun choose(actionId: String, index: Int, remember: Boolean) { calls += "choose $actionId $index $remember" }
    override fun undo(actionId: String) { calls += "undo $actionId" }
    override fun openHandOff(actionId: String) { calls += "openHandOff $actionId" }
    override fun acceptOffer(replyId: String) { calls += "acceptOffer $replyId" }
    override fun dismissOffer(replyId: String) { calls += "dismissOffer $replyId" }
    override fun endHelperMode() { calls += "endHelperMode" }
    override fun openSource(replyId: String, url: String) { calls += "openSource $replyId $url" }
}

/** Panel states for the tests: "Hello" / "Hi." and a text waiting for "Do it", and the phases around them. */
object PanelFixtures {
    val tips = listOf(Tip("Set a timer for 10 minutes"), Tip("What's 15 % of 80?"), Tip("What's on my screen?", TipKind.SCREEN))

    val idle = PanelState("c1", tips = tips, speak = true, sttAvailable = true)

    val listening = PanelState("c1", phase = Phase.LISTENING, partial = "set a timer for ten", speak = true, sttAvailable = true)

    val hello = UiTurn("u1", TurnRole.USER, "Hello")
    val hi = UiTurn("r1", TurnRole.ASSISTANT, "Hi. What do you need?")
    val textMom = UiTurn("u2", TurnRole.USER, "Text Mom I'm running late")
    val card = UiAction("a1", ToolName.SEND_TEXT, "Text **Mom** in Cheogram", ActionStatus.PENDING, null, body = "I'm running late")
    val cardReply = UiTurn("r2", TurnRole.ASSISTANT, "", actions = listOf(card))

    val thinking = PanelState("c1", turns = listOf(hello, hi, textMom, UiTurn("r2", TurnRole.ASSISTANT, "", streaming = true)),
        phase = Phase.THINKING, speak = true, keep = false)

    val answerWithCard = PanelState("c1", turns = listOf(hello, hi, textMom, cardReply), phase = Phase.CONFIRMING, speak = true)

    /** The card with two numbers to choose from and "Always use this for “mom”". */
    val choices = answerWithCard.copy(turns = listOf(hello, hi, textMom, cardReply.copy(actions = listOf(card.copy(
        choices = listOf("Mom · mobile", "Mom · work"), remember = "Always use this for “mom”")))))

    val doneCard = PanelState("c1", turns = listOf(
        UiTurn("u3", TurnRole.USER, "Set a timer for 10 minutes"),
        UiTurn("r3", TurnRole.ASSISTANT, "Timer set for 10 minutes.", actions = listOf(UiAction("a2", ToolName.SET_TIMER, "Timer for 10 minutes", ActionStatus.DONE, null))),
    ), speak = false)

    /** Robot's own answer, then the offer to ask a helper, then the helper's answer under its name. */
    val helperOffer = PanelState("c1", turns = listOf(
        UiTurn("u4", TurnRole.USER, "What's the latest on the Artemis program?"),
        UiTurn("r4", TurnRole.ASSISTANT, "My information may be out of date.", offer = Offer(api = true, text = "What's the latest on the Artemis program?",
            sends = listOf("your question"), asWell = true, name = "Jarvis")),
        UiTurn("u5", TurnRole.USER, "Why is the sky blue?"),
        UiTurn("r5", TurnRole.ASSISTANT, "Sunlight scatters off the air's molecules, and blue light scatters the most.", fromHelper = true, by = "Claude",
            sources = listOf("nasa.gov")),
    ), speak = true)
}

/** Shows the panel for [st] in Robot's theme (Robot's own colours, as every screenshot). */
fun ComposeContentTestRule.showPanel(st: PanelState, actions: PanelActions = RecordingActions(), dark: Boolean = false, onClose: () -> Unit = {}) {
    setContent {
        RobotTheme(dark = dark, look = LookSettings(wallpaperColors = false)) {
            // The app underneath, behind the scrim.
            Surface(color = MaterialTheme.colorScheme.background) { AssistantPanel(st, actions, onClose) }
        }
    }
}
